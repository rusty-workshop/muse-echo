package com.rusty.museecho.protocol

import android.util.Log
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * BLE setup commands: the protocol logic behind the GATT characteristics
 * (port of `ble_setup.py`). Independent of the Bluetooth stack: a transport
 * delivers raw writes and sends framed notifications. Messages are handled one
 * at a time on a worker thread, in arrival order, and every send happens under
 * one lock so encrypted record counters reach the phone in order.
 */
class SetupController(
    private val pairing: PairingSession,
    private val identity: Identity,
    private val version: String,
    private val transport: Transport,
    private val network: Network,
    private val provision: (Credentials, commit: (() -> Boolean) -> Boolean) -> Unit,
    private val onComplete: () -> Unit = {},
    private val log: (String) -> Unit = { Log.i(TAG, it) },
) {
    interface Transport {
        /** Notify each packet in order; blocks until all are queued. */
        fun sendPackets(packets: List<ByteArray>)
        fun mtu(): Int
        fun disconnect(delayMs: Long)
    }

    interface Network {
        fun isOnline(): Boolean
        fun currentConnectionEntry(): JsonObject
    }

    data class Credentials(
        val accessToken: String,
        val refreshToken: String,
        val username: String,
        val apiUrl: String,
        val apiUrlV2: String,
        val noiseHost: String,
    )

    /** Setup could not finish; [status] is what the app is told. */
    class ProvisionFailed(val status: String) : Exception(status)

    private val assembler = ChunkAssembler()
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "ble-setup").apply { isDaemon = true } }
    private val txLock = Any()
    private val stateLock = Any()
    private var plaintextBlocked = false
    private var provisioning = false

    // -- Transport callbacks (any thread) -------------------------------------

    fun onWrite(packet: ByteArray) {
        val message = assembler.feed(packet) ?: return
        worker.execute {
            try {
                handleMessage(message)
            } catch (e: Exception) {
                log("setup command failed: $e")
            }
        }
    }

    fun onDisconnect() {
        log("BLE client disconnected; clearing pairing session")
        assembler.reset()
        synchronized(stateLock) { plaintextBlocked = false }
        pairing.reset()
    }

    fun stop() {
        worker.shutdownNow()
    }

    // -- Dispatch ---------------------------------------------------------------

    fun handleMessage(raw: ByteArray, decrypted: Boolean = false) {
        val command = try {
            Json.parseToJsonElement(raw.toString(Charsets.UTF_8)) as? JsonObject
        } catch (e: Exception) {
            null
        }
        if (command == null) {
            log("invalid command JSON (${raw.size} bytes)")
            sendStatus("error_invalid_command")
            return
        }
        val action = command.str("action") ?: ""
        log("RX action: ${action.ifEmpty { "?" }}${if (decrypted) " (encrypted)" else ""}")
        val blocked = synchronized(stateLock) { plaintextBlocked }

        when {
            !decrypted && action == "pairing_client_hello" -> handleHello(command)
            !decrypted && action == "pairing_encrypted" -> handleRecord(command)
            // Public metadata, safe in plaintext at any point. Apps re-read it
            // when they restart a handshake on the same connection.
            action == "get_device_info" -> sendJson(deviceInfo())
            !decrypted && blocked -> log("plaintext command ignored after pairing started: $action")
            !decrypted && action in SENSITIVE_ACTIONS -> sendStatus("error_encryption_required")
            decrypted && action == "pairing_client_finished" -> handleClientFinished(command)
            decrypted && action in SENSITIVE_ACTIONS && !pairing.confirmed ->
                sendStatus("error_pairing_confirm_required")
            decrypted && action == "wifi_scan" -> handleWifiScan()
            decrypted && action == "provision_v2" -> handleProvision(command)
            else -> sendStatus("error_unknown_action")
        }
    }

    fun deviceInfo(): JsonObject = JsonObject(
        mapOf(
            "type" to JsonPrimitive("device_info"),
            "node_id" to JsonPrimitive(identity.nodeId),
            "version" to JsonPrimitive(version),
        ) + pairing.deviceInfo() + mapOf(
            "build_sha" to JsonPrimitive(""),
            "network_ready" to JsonPrimitive(network.isOnline()),
        ),
    )

    private fun handleHello(command: JsonObject) {
        val ready = try {
            pairing.handleHello(command)
        } catch (e: PairingSession.PairingError) {
            sendStatus(e.status)
            return
        }
        synchronized(stateLock) { plaintextBlocked = true }
        sendJson(ready)
    }

    private fun handleRecord(envelope: JsonObject) {
        val plaintext = try {
            pairing.decrypt(envelope)
        } catch (e: PairingSession.PairingError) {
            sendStatus(e.status)
            transport.disconnect(DISCONNECT_AFTER_ERROR_MS)
            return
        }
        handleMessage(plaintext.toByteArray(), decrypted = true)
    }

    private fun handleClientFinished(command: JsonObject) {
        val gen = pairing.handleClientFinished(command)
        if (gen == 0) {
            sendStatus("error_pairing_decrypt")
            transport.disconnect(DISCONNECT_AFTER_ERROR_MS)
            return
        }
        log("pairing confirmed (app consent)")
        sendStatus("pairing_confirmed", gen)
    }

    private fun handleWifiScan() {
        val networks = if (network.isOnline()) listOf(network.currentConnectionEntry()) else emptyList()
        sendEncryptedJson(buildJsonObject {
            put("type", "wifi_scan_result")
            put("networks", JsonArray(networks))
        })
    }

    private fun handleProvision(command: JsonObject) {
        fun text(key: String) = command.str(key) ?: ""
        if (command.str("ssid") == null || command.str("password") == null ||
            text("access_token").isEmpty() || text("refresh_token").isEmpty() ||
            text("token_type") != "device"
        ) {
            sendStatus("error_missing_credentials")
            return
        }
        var gen = 0
        val refusal: String? = synchronized(stateLock) {
            if (provisioning) {
                "error_operation_in_progress"
            } else {
                gen = pairing.markProvisioning()
                provisioning = gen != 0
                if (gen != 0) null else "error_pairing_confirm_required"
            }
        }
        // Sent after the lock is released: sendStatus() takes stateLock itself.
        if (refusal != null) {
            sendStatus(refusal)
            return
        }
        // The Wi-Fi fields are deliberately dropped: this device only sets up
        // when it is already online.
        val credentials = Credentials(
            accessToken = text("access_token"),
            refreshToken = text("refresh_token"),
            username = text("username"),
            apiUrl = text("api_url"),
            apiUrlV2 = text("api_url_v2"),
            noiseHost = text("noise_host"),
        )
        thread(name = "provision", isDaemon = true) { runProvision(credentials, gen) }
    }

    private fun runProvision(credentials: Credentials, gen: Int) {
        try {
            sendStatus("wifi_connecting", gen)
            if (!network.isOnline()) {
                // Stay in provisioning so the app can retry, as the firmware does.
                pairing.extendProvisioning(gen)
                sendStatus("wifi_failed", gen)
                return
            }
            sendStatus("wifi_connected", gen)
            try {
                provision(credentials) { save -> pairing.commitProvisioning(gen, save) }
            } catch (e: ProvisionFailed) {
                log("provisioning failed: ${e.status}")
                sendStatus(e.status, gen)
                transport.disconnect(500)
                return
            }
            sendStatus("auth_ok", gen)
            log("setup complete")
            onComplete()
        } finally {
            synchronized(stateLock) { provisioning = false }
        }
    }

    // -- Sending ----------------------------------------------------------------

    fun sendStatus(status: String, gen: Int = 0) = synchronized(txLock) {
        val envelope = pairing.encryptStatus(status, gen)
        if (envelope != null) {
            sendLocked(envelope.toString())
            log("TX status (encrypted #${envelope.str("counter")}): $status")
            return@synchronized
        }
        val blocked = synchronized(stateLock) { plaintextBlocked }
        if (gen != 0 || blocked || status !in PLAINTEXT_STATUSES) {
            log("TX status suppressed: $status")
            return@synchronized
        }
        transport.sendPackets(listOf(status.toByteArray()))
        log("TX status: $status")
    }

    fun sendJson(obj: JsonObject) = synchronized(txLock) {
        sendLocked(obj.toString())
        log("TX ${obj.str("type")}")
    }

    fun sendEncryptedJson(obj: JsonObject, gen: Int = 0) = synchronized(txLock) {
        val envelope = pairing.encryptJson(obj.toString(), gen)
        if (envelope == null) {
            log("TX ${obj.str("type")} suppressed: no session")
            return@synchronized
        }
        sendLocked(envelope.toString())
        log("TX ${obj.str("type")} (encrypted #${envelope.str("counter")})")
    }

    private fun sendLocked(text: String) {
        transport.sendPackets(BleFraming.encodeChunks(text.toByteArray(), transport.mtu()))
    }

    companion object {
        private const val TAG = "MuseSetup"
        val SENSITIVE_ACTIONS = setOf(
            "provision", "provision_v2", "wifi_scan", "ota", "device.ota", "unpair", "set_wifi", "set_auth",
        )
        val PLAINTEXT_STATUSES = setOf(
            "error_encryption_required", "error_pairing_invalid_hello",
            "error_pairing_unavailable", "error_pairing_decrypt",
        )
        const val DISCONNECT_AFTER_ERROR_MS = 300L
    }
}
