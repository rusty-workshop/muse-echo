package com.rusty.museecho.gadget

import com.rusty.museecho.noise.ApplicationResponse
import com.rusty.museecho.noise.BodyChunk
import com.rusty.museecho.noise.Reset
import com.rusty.museecho.protocol.LinkSession
import com.rusty.museecho.protocol.ReplyTracker
import java.util.Base64
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One push-to-talk (or typed) turn with the Muse, over the gadget's own Noise
 * session, as the ESP32 boards do it: open `POST /chat/subscribe` for reply
 * events, send the note with `POST /chat/stream`, then follow the reply until
 * it finishes and goes quiet.
 */
class ChatClient(
    private val session: () -> LinkSession?,
    private val nodeId: String,
) {
    class ChatError(message: String) : Exception(message)

    /**
     * Send [text], or a voice note when [wav] is given. [onUpdate] gets the
     * tracker each time what Muse heard or said changes. Returns the reply.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    suspend fun turn(text: String?, wav: ByteArray?, onUpdate: (ReplyTracker) -> Unit): String {
        val link = session()?.takeIf { it.registeredAt != null } ?: throw ChatError("Not connected to Muse")
        val tracker = ReplyTracker()
        val sub = link.openRequest(
            "POST", LinkSession.SUBSCRIBE_PATH,
            LinkSession.jsonHeaders(APP_ID, accept = "application/x-ndjson"), "{}".toByteArray(),
        )
        try {
            val body = buildJsonObject {
                put("message", text ?: "")
                put("output_modality", "text")
                put("device_id", nodeId)
                if (wav != null) {
                    put("items", JsonArray(listOf(buildJsonObject {
                        put("type", "file")
                        put("mime_type", "audio/wav")
                        put("filename", "voice_note.wav")
                        put("data_base64", Base64.getEncoder().encodeToString(wav))
                    })))
                }
            }
            return coroutineScope {
                val ack = async {
                    val chat = link.openRequest("POST", LinkSession.CHAT_PATH, LinkSession.jsonHeaders(APP_ID),
                        body.toString().toByteArray())
                    withTimeout(LinkSession.REQUEST_TIMEOUT_MS) { chat.collect() }
                }
                val lines = LineSplitter()
                var subStatus = 0
                var acked = false
                var quietSince = 0L
                val deadline = System.currentTimeMillis() + REPLY_TIMEOUT_MS
                while (true) {
                    val now = System.currentTimeMillis()
                    if (tracker.hasFinalReply && quietSince != 0L && now - quietSince >= SETTLE_MS) break
                    if (now >= deadline) {
                        if (tracker.replyText.isNotEmpty()) break
                        throw ChatError("Muse didn't answer")
                    }
                    if (!acked && ack.isCompleted) {
                        val (status, ackBody) = ack.await()
                        if (status !in 200..299) throw ChatError("Muse didn't take it (HTTP $status)")
                        val ids = ReplyTracker.parseAck(ackBody.toString(Charsets.UTF_8))
                            ?: throw ChatError("Muse didn't take it")
                        tracker.acknowledge(ids.first, ids.second)
                        acked = true
                        // Held events were just replayed; the whole reply may already be in.
                        quietSince = System.currentTimeMillis()
                        onUpdate(tracker)
                    }
                    val frame = select {
                        sub.frames.onReceiveCatching { it.getOrNull() ?: FRAME_CLOSED }
                        onTimeout(POLL_MS) { null }
                    }
                    if (frame === FRAME_CLOSED) {
                        if (tracker.replyText.isNotEmpty()) break
                        throw ChatError("Lost the reply stream")
                    }
                    if (frame == null) continue
                    val data = when (val v = frame.value) {
                        is ApplicationResponse -> {
                            subStatus = v.status
                            if (v.status >= 400) throw ChatError("Couldn't subscribe to replies (HTTP ${v.status})")
                            v.body
                        }
                        is BodyChunk -> v.data
                        is Reset -> throw ChatError("Reply stream reset")
                        else -> continue
                    }
                    if (subStatus != 200 && subStatus != 0) continue
                    var changed = false
                    lines.feed(data) { if (tracker.feedLine(it)) changed = true }
                    if (changed) {
                        quietSince = System.currentTimeMillis()
                        onUpdate(tracker)
                    }
                }
                ack.cancel()
                tracker.replyText
            }
        } finally {
            sub.cancel()
        }
    }

    /** Splits a byte stream into UTF-8 lines; an oversized line is dropped. */
    private class LineSplitter {
        private val buf = java.io.ByteArrayOutputStream()
        private var skipping = false

        fun feed(data: ByteArray, onLine: (String) -> Unit) {
            for (b in data) {
                if (b == '\n'.code.toByte()) {
                    if (!skipping && buf.size() > 0) onLine(buf.toString(Charsets.UTF_8.name()).trim())
                    buf.reset()
                    skipping = false
                } else if (!skipping) {
                    buf.write(b.toInt())
                    if (buf.size() > MAX_LINE) {
                        buf.reset()
                        skipping = true
                    }
                }
            }
        }
    }

    companion object {
        /** The Muse web app's id; the ESP32 boards use it for their chat turns. */
        const val APP_ID = "hatch-web"
        const val REPLY_TIMEOUT_MS = 90_000L
        const val SETTLE_MS = 3_000L
        const val POLL_MS = 250L
        const val MAX_LINE = 256 * 1024
        private val FRAME_CLOSED = com.rusty.museecho.noise.DecryptedFrame(0, Reset())
    }
}
