package com.rusty.museecho.protocol

import android.util.Log
import com.rusty.museecho.noise.ApplicationResponse
import com.rusty.museecho.noise.BodyChunk
import com.rusty.museecho.noise.DecryptedFrame
import com.rusty.museecho.noise.Header
import com.rusty.museecho.noise.NoiseTransport
import com.rusty.museecho.noise.NoiseXXInitiator
import com.rusty.museecho.noise.Reset
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** A binary WebSocket, abstracted so tests can run the session against a fake VM. */
interface WsConnection {
    suspend fun send(data: ByteArray)

    /** The next binary message, or null once the connection has closed. */
    suspend fun receive(): ByteArray?
    fun close()
}

fun interface WsConnector {
    /** Throws [UpgradeRejected] when the server refuses the upgrade with an HTTP status. */
    suspend fun connect(url: String, headers: Map<String, String>): WsConnection
}

class UpgradeRejected(val status: Int) : Exception("upgrade rejected: HTTP $status")

data class DeviceDescription(
    val nodeId: String,
    val displayName: String,
    val version: String,
    val commands: JsonObject,
) {
    // Same platform/family/model as the Linux SDK: LineageOS is Linux, and the
    // VM is known to accept these values from a community gadget.
    fun registerParams() = buildJsonObject {
        put("node_id", nodeId)
        put("display_name", displayName)
        put("platform", "linux")
        put("version", version)
        put("device_family", "homehub")
        put("model_id", "linux")
        put("is_wakeup_supported", false)
        put("commands_v2", commands)
    }
}

/** Result of a command the Muse invoked: `{"ok": true, "payload": …}` or `{"ok": false, "error": …}`. */
typealias CommandRunner = suspend (command: String, params: JsonObject, timeoutMs: Long?) -> JsonObject

/**
 * One control session with a Muse VM (port of `link_client.py`).
 *
 * Opens a WebSocket to `/v1/noise` with the per-VM bearer, runs the Noise XX
 * handshake, then holds a long-lived `POST /link-control` stream. Both
 * directions of that stream carry JSON messages, each prefixed with its length
 * as a little-endian u32. Other requests (chat, subscriptions) are separate
 * streams on the same session.
 */
class LinkSession(
    noiseHost: String,
    vmId: String,
    private val vmAuthToken: String,
    /** Null for a chat-only connection: no /link-control, no registration. */
    private val device: DeviceDescription?,
    private val runCommand: CommandRunner,
    private val connector: WsConnector,
    private val log: (String) -> Unit = { Log.i(TAG, it) },
) {
    enum class Outcome { CLOSED, AUTH_REJECTED, FORBIDDEN, UNPAIRED, STOPPED }

    private val url = "wss://$noiseHost$NOISE_PATH?vm_id=${uriComponent(vmId)}"
    private val sendLock = Mutex()
    private val invokes = Semaphore(MAX_CONCURRENT_INVOKES)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val streams = ConcurrentHashMap<Long, Channel<DecryptedFrame>>()
    private lateinit var ws: WsConnection
    private lateinit var transport: NoiseTransport
    private var controlStream = 0L
    private var registerId = ""

    @Volatile
    var registeredAt: Long? = null
        private set

    suspend fun run(): Outcome {
        ws = try {
            connector.connect(url, mapOf("Authorization" to "Bearer $vmAuthToken"))
        } catch (e: UpgradeRejected) {
            log("VM refused connection: HTTP ${e.status}")
            return if (e.status == 401) Outcome.AUTH_REJECTED else Outcome.FORBIDDEN
        }
        try {
            transport = withTimeout(HANDSHAKE_TIMEOUT_MS) { handshake() }
            if (device != null) {
                openControlStream()
            } else {
                // A chat-only connection is usable as soon as the handshake is done.
                registeredAt = System.currentTimeMillis()
                log("chat connection ready")
            }
            return readLoop()
        } finally {
            scope.cancel()
            streams.values.forEach { it.close() }
            streams.clear()
            ws.close()
        }
    }

    private suspend fun handshake(): NoiseTransport {
        val initiator = NoiseXXInitiator()
        initiator.initialize()
        ws.send(initiator.writeMessage1())
        val msg2 = ws.receive() ?: throw java.io.IOException("closed during Noise handshake")
        initiator.readMessage2(msg2)
        // The bearer already authenticated us at the upgrade; message 3
        // carries an empty payload.
        ws.send(initiator.writeMessage3())
        val (send, recv) = initiator.split()
        log("Noise session established")
        return NoiseTransport(send, recv)
    }

    private suspend fun openControlStream() {
        val encrypted = transport.startStreamRequest("POST", CONTROL_PATH)
        controlStream = encrypted.streamId
        sendFrames(encrypted.frames)
        registerId = UUID.randomUUID().toString()
        send(buildJsonObject {
            put("type", "req")
            put("id", registerId)
            put("method", "link.register")
            put("params", device!!.registerParams())
        })
        log("sent link.register as ${device!!.nodeId}")
    }

    // -- Requests on their own streams -----------------------------------------

    /** A request stream: response head and body chunks arrive on [frames]. */
    inner class Stream(val id: Long, val frames: Channel<DecryptedFrame>) {
        suspend fun sendBody(data: ByteArray, end: Boolean = false) =
            sendFrames(transport.encryptBodyChunk(id, data, end))

        suspend fun cancel() {
            if (streams.remove(id) != null) {
                frames.close()
                runCatching { sendFrames(transport.encryptReset(id)) }
            }
        }

        /** Collect the whole response: (status, body). */
        suspend fun collect(maxBytes: Int = MAX_RESPONSE_BYTES): Pair<Int, ByteArray> {
            var status = 0
            val body = java.io.ByteArrayOutputStream()
            try {
                for (frame in frames) {
                    val (data, ended) = when (val v = frame.value) {
                        is Reset -> throw java.io.IOException("stream reset: ${v.reason}")
                        is ApplicationResponse -> { status = v.status; v.body to v.endBody }
                        is BodyChunk -> v.data to v.endBody
                        else -> continue
                    }
                    body.write(data)
                    if (body.size() > maxBytes) throw java.io.IOException("response too large")
                    if (ended) return status to body.toByteArray()
                }
                throw java.io.IOException("session ended")
            } finally {
                streams.remove(id)
            }
        }
    }

    /** Open a request stream. With [endBody] false, more body follows via [Stream.sendBody]. */
    suspend fun openRequest(
        verb: String,
        path: String,
        headers: List<Header>,
        body: ByteArray = ByteArray(0),
        endBody: Boolean = true,
    ): Stream {
        val channel = Channel<DecryptedFrame>(Channel.UNLIMITED)
        // Registered before the request goes out, so no reply frame can beat it.
        val encrypted = sendLock.withLock {
            val e = transport.encryptHttpRequest(verb, path, body, headers, endBody)
            streams[e.streamId] = channel
            e.frames.forEach { ws.send(it) }
            e
        }
        return Stream(encrypted.streamId, channel)
    }

    /**
     * Post a user message to the Muse as coming from this device. The VM
     * attributes the turn to the device registered on this session and routes
     * any follow-up device commands back here. [sessionId] targets a side chat.
     */
    suspend fun sendChat(message: String, sessionId: String? = null): JsonObject {
        val body = buildJsonObject {
            put("message", message)
            put("output_modality", "text")
            device?.let { put("device_id", it.nodeId) }
            if (sessionId != null) put("session_id", sessionId)
        }
        val stream = openRequest("POST", CHAT_PATH, jsonHeaders(APP_ID), body.toString().toByteArray())
        val (status, response) = withTimeout(REQUEST_TIMEOUT_MS) { stream.collect() }
        val decoded: JsonElement = runCatching { Json.parseToJsonElement(response.toString(Charsets.UTF_8)) }
            .getOrElse { JsonPrimitive(response.toString(Charsets.UTF_8).take(2000)) }
        return buildJsonObject {
            put("ok", status in 200..299)
            put("status", status)
            put("response", decoded)
        }
    }

    // -- Control stream ---------------------------------------------------------

    suspend fun send(message: JsonObject) = sendFrames(transport.encryptBodyChunk(controlStream, encodeMessage(message)))

    private suspend fun sendFrames(frames: List<ByteArray>) = sendLock.withLock { frames.forEach { ws.send(it) } }

    private suspend fun readLoop(): Outcome {
        val decoder = MessageDecoder()
        while (true) {
            val raw = try {
                ws.receive()
            } catch (e: Exception) {
                log("control connection closed: $e")
                return Outcome.CLOSED
            } ?: run {
                log("control connection closed")
                return Outcome.CLOSED
            }
            val frame = transport.decryptFrame(raw) ?: continue
            if (frame.streamId != controlStream) {
                streams[frame.streamId]?.let { ch ->
                    ch.trySend(frame)
                    val v = frame.value
                    if (v is Reset || (v is ApplicationResponse && v.endBody) || (v is BodyChunk && v.endBody)) ch.close()
                }
                continue
            }
            val (data, ended) = when (val v = frame.value) {
                is Reset -> {
                    log("control stream reset: ${v.reason}")
                    return Outcome.CLOSED
                }
                is ApplicationResponse -> {
                    if (v.status >= 400) {
                        log("/link-control refused: HTTP ${v.status}")
                        return if (v.status == 403) Outcome.FORBIDDEN else Outcome.CLOSED
                    }
                    v.body to v.endBody
                }
                is BodyChunk -> v.data to v.endBody
                else -> continue
            }
            for (message in decoder.feed(data)) {
                handle(message)?.let { return it }
            }
            if (ended) {
                log("control stream ended by VM")
                return Outcome.CLOSED
            }
        }
    }

    private fun handle(message: JsonObject): Outcome? {
        if (message.str("id") == registerId && message["method"] == null) {
            if (message["error"] != null) {
                log("link.register rejected: ${message["error"]}")
            } else {
                registeredAt = System.currentTimeMillis()
                val status = (message["result"] as? JsonObject)?.str("status")
                log("registered with the Muse (${message.str("type") ?: "?"}, status ${status ?: "none"})")
                // As the ESP32 firmware does: once registration is confirmed,
                // heartbeat right away and then daily.
                if (message.str("type") == "res" && status == "registered") {
                    scope.launch {
                        while (true) {
                            runCatching { send(buildJsonObject { put("method", "link.heartbeat") }) }
                                .onSuccess { log("sent link.heartbeat") }
                            kotlinx.coroutines.delay(HEARTBEAT_INTERVAL_MS)
                        }
                    }
                }
            }
            return null
        }
        val event = message.str("event")
        if (event == "link.unpaired" || event == "node.unpaired") {
            log("the Muse removed this device")
            return Outcome.UNPAIRED
        }
        if (message.str("method") == "link.invoke") {
            scope.launch { invoke(message) }
        }
        return null
    }

    private suspend fun invoke(message: JsonObject) {
        val invokeId = message.str("id") ?: return
        val command = message.str("command") ?: ""
        val params = message["params"] as? JsonObject ?: JsonObject(emptyMap())
        val timeoutMs = (message["timeout_ms"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 }
        val shown = command.map { if (it.isISOControl()) '?' else it }.joinToString("")
        log("invoke $shown")
        val result = invokes.withPermit {
            val started = System.currentTimeMillis()
            val r = try {
                runCommand(command, params, timeoutMs)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                commandError("${e.javaClass.simpleName}: ${e.message}")
            }
            val ok = (r["ok"] as? JsonPrimitive)?.content == "true"
            log("$shown ${if (ok) "ok" else "failed"} in ${System.currentTimeMillis() - started} ms")
            r
        }
        runCatching {
            send(JsonObject(mapOf("method" to JsonPrimitive("link.result"), "id" to JsonPrimitive(invokeId)) + result))
        }
    }

    companion object {
        private const val TAG = "MuseLink"
        const val NOISE_PATH = "/v1/noise"
        const val CONTROL_PATH = "/link-control"
        const val CHAT_PATH = "/chat/stream"
        const val SUBSCRIBE_PATH = "/chat/subscribe"
        const val APP_ID = "musegadget"
        const val REQUEST_TIMEOUT_MS = 60_000L
        const val MAX_RESPONSE_BYTES = 1024 * 1024
        const val HANDSHAKE_TIMEOUT_MS = 20_000L
        const val MAX_CONCURRENT_INVOKES = 4
        const val HEARTBEAT_INTERVAL_MS = 24 * 3600_000L
        const val MAX_INBOUND_MESSAGE = 4 * 1024 * 1024

        fun jsonHeaders(appId: String, accept: String = "application/json") = listOf(
            Header("Content-Type", "application/json"),
            Header("Accept", accept),
            Header("x-request-id", UUID.randomUUID().toString()),
            Header("x-app-id", appId),
        )

        /** encodeURIComponent semantics, like the Python client's `quote(…, safe="-_.!~*'()")`. */
        fun uriComponent(text: String): String =
            URLEncoder.encode(text, "UTF-8").replace("+", "%20")
                .replace("%21", "!").replace("%7E", "~").replace("%27", "'")
                .replace("%28", "(").replace("%29", ")")

        fun encodeMessage(obj: JsonObject): ByteArray {
            val data = obj.toString().toByteArray()
            return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(data.size).array() + data
        }

        fun commandOk(payload: JsonObject) = buildJsonObject {
            put("ok", true)
            put("payload", payload)
        }

        fun commandError(message: String) = buildJsonObject {
            put("ok", false)
            put("error", message)
        }
    }
}

/** Splits the control stream into length-prefixed JSON messages that may span chunks. */
class MessageDecoder {
    private var buf = ByteArray(0)

    fun feed(data: ByteArray): List<JsonObject> {
        buf += data
        val out = mutableListOf<JsonObject>()
        while (buf.size >= 4) {
            val length = ByteBuffer.wrap(buf, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
            if (length > LinkSession.MAX_INBOUND_MESSAGE) throw IllegalStateException("inbound message too large: $length")
            if (buf.size < 4 + length) break
            val raw = buf.copyOfRange(4, 4 + length.toInt())
            buf = buf.copyOfRange(4 + length.toInt(), buf.size)
            if (raw.isEmpty()) continue // keepalive
            val parsed = runCatching { Json.parseToJsonElement(raw.toString(Charsets.UTF_8)) }.getOrNull()
            if (parsed is JsonObject) out += parsed
        }
        return out
    }
}
