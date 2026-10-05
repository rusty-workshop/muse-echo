package com.rusty.museecho.protocol

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/**
 * Device side of Muse Gadget BLE pairing, protocol version 5.
 *
 * A port of the Linux SDK's `pairing.py`. Community mode only: `pairing_auth`
 * is "none" and the policy is `confirm_app`, so a valid client-finished record
 * confirms the session without a physical button. The transcript, key schedule
 * and record encryption match the reference firmware byte for byte (checked
 * against Meta's published vectors in the unit tests).
 *
 * Community pairing protects setup secrets from passive observers. It does not
 * authenticate the ECDH peer, so it cannot stop an active man-in-the-middle.
 */
class PairingSession(
    private val nodeId: String,
    private val deviceId: String,
    private val mac: String,
    firmwareVersion: String,
    private val sdkToken: String? = null,
    private val clock: () -> Double = { System.nanoTime() / 1e9 },
    private val generateKey: () -> P256KeyPair = P256KeyPair::generate,
    private val randomBytes: (Int) -> ByteArray = Crypto::randomBytes,
) {
    class PairingError(val status: String) : Exception(status)

    enum class State { IDLE, WAIT_CLIENT_FINISHED, READY, PROVISIONING }

    private val firmwareVersion = firmwareVersion.ifEmpty { "unknown" }
    private val lock = Any()
    private var generation = 0
    private var state = State.IDLE
    private var deadline = 0.0
    private var rxKey: ByteArray? = null
    private var txKey: ByteArray? = null
    private var sessionIdB64 = ""
    private var rxCounter = 0L
    private var txCounter = 0L

    fun deviceInfo(): Map<String, JsonElement> = mapOf(
        "device_id" to JsonPrimitive(deviceId),
        "mac" to JsonPrimitive(mac),
        "model" to JsonPrimitive(MODEL),
        "pairing_protocol" to JsonPrimitive(VERSION),
        "pairing_auth" to JsonPrimitive(AUTH_COMMUNITY),
        "pairing_auth_epoch" to JsonPrimitive(0),
        "pairing_policy" to JsonPrimitive(POLICY_APP),
    )

    val currentState: State get() = synchronized(lock) { expireLocked(); state }

    val confirmed: Boolean get() = currentState == State.READY || currentState == State.PROVISIONING

    fun isCurrent(gen: Int): Boolean = synchronized(lock) { gen != 0 && gen == generation }

    fun reset() = synchronized(lock) { resetLocked() }

    /** Start a session from `pairing_client_hello`; returns `pairing_ready`. */
    fun handleHello(message: JsonObject): JsonObject {
        val version = (message["version"] as? JsonPrimitive)
        val versionOk = version != null && !version.isString &&
            version.booleanOrNull == null && version.doubleOrNull == VERSION.toDouble()
        if (!versionOk ||
            message.str("pairing_auth") != AUTH_COMMUNITY ||
            message.str("pairing_policy") != POLICY_APP
        ) throw PairingError(ERROR_INVALID_HELLO)

        synchronized(lock) {
            resetLocked()
            val mobilePub: ByteArray
            val mobileNonce: ByteArray
            try {
                mobilePub = Crypto.b64urlDecode(message.str("mobile_pub"))
                mobileNonce = Crypto.b64urlDecode(message.str("mobile_nonce"))
                require(mobilePub.size == 65 && mobilePub[0] == 0x04.toByte() && mobileNonce.size == 16)
                require(P256KeyPair.isValidPoint(mobilePub))
            } catch (e: IllegalArgumentException) {
                resetLocked()
                throw PairingError(ERROR_INVALID_HELLO)
            }

            val deviceKey = generateKey()
            val devicePub = deviceKey.publicPoint
            val deviceNonce = randomBytes(16)
            val transcript = buildTranscript(
                deviceId = deviceId, nodeId = nodeId, mac = mac, firmwareVersion = firmwareVersion,
                mobilePub = Crypto.b64url(mobilePub), devicePub = Crypto.b64url(devicePub),
                mobileNonce = Crypto.b64url(mobileNonce), deviceNonce = Crypto.b64url(deviceNonce),
            )
            val transcriptHash = Crypto.sha256(transcript.toByteArray())
            val ecdh = deviceKey.agree(mobilePub)
            val keys = deriveSessionKeys(ecdh, mobileNonce, deviceNonce, transcriptHash)

            rxKey = keys.mobileTx
            txKey = keys.mobileRx
            sessionIdB64 = Crypto.b64url(keys.sessionId)
            rxCounter = 0
            txCounter = 0
            state = State.WAIT_CLIENT_FINISHED
            deadline = clock() + CLIENT_FINISHED_TIMEOUT_S
            return buildJsonObject {
                put("type", "pairing_ready")
                put("version", VERSION)
                put("device_id", deviceId)
                put("node_id", nodeId)
                put("mac", mac)
                put("model", MODEL)
                put("firmware_version", firmwareVersion)
                put("pairing_auth", AUTH_COMMUNITY)
                put("pairing_auth_epoch", 0)
                put("pairing_policy", POLICY_APP)
                put("device_pub", Crypto.b64url(devicePub))
                put("device_nonce", Crypto.b64url(deviceNonce))
                put("transcript_hash", Crypto.b64url(transcriptHash))
                put("session_id", sessionIdB64)
            }
        }
    }

    /**
     * Open one mobile-to-device `pairing_encrypted` record. Any failure (wrong
     * session, skipped or replayed counter, bad tag, expiry) clears the session.
     */
    fun decrypt(envelope: JsonObject): String = synchronized(lock) {
        if (expireLocked() || state == State.IDLE) {
            resetLocked()
            throw PairingError(ERROR_DECRYPT)
        }
        val plaintext = try {
            require(envelope.str("session_id") == sessionIdB64)
            val counter = parseCounter(envelope.str("counter"))
            require(counter == rxCounter)
            val ciphertext = Crypto.b64urlDecode(envelope.str("ciphertext"), 16384)
            val tag = Crypto.b64urlDecode(envelope.str("tag"))
            require(tag.size == 16)
            Crypto.aesGcmOpen(
                rxKey!!, recordNonce(TO_DEVICE, counter), ciphertext + tag,
                recordAad(sessionIdB64, TO_DEVICE, counter),
            ).toString(Charsets.UTF_8)
        } catch (e: Exception) {
            resetLocked()
            throw PairingError(ERROR_DECRYPT)
        }
        rxCounter++
        plaintext
    }

    /**
     * Confirm the session after the first decrypted record. Returns the new
     * generation, or 0 (after clearing the session) if the record is invalid.
     */
    fun handleClientFinished(command: JsonObject): Int = synchronized(lock) {
        val ok = command == buildJsonObject { put("action", "pairing_client_finished") } &&
            !expireLocked() && state == State.WAIT_CLIENT_FINISHED && rxCounter == 1L
        if (!ok) {
            resetLocked()
            return 0
        }
        generation++
        state = State.READY
        deadline = clock() + CONFIRMED_TIMEOUT_S
        generation
    }

    /** Enter provisioning from a confirmed session; returns its generation. */
    fun markProvisioning(): Int = synchronized(lock) {
        if (!expireLocked() && state == State.READY) {
            generation++
            state = State.PROVISIONING
            deadline = clock() + PROVISIONING_TIMEOUT_S
        }
        if (state == State.PROVISIONING) generation else 0
    }

    fun extendProvisioning(gen: Int): Boolean = synchronized(lock) {
        provisioningLocked(gen).also { if (it) deadline = clock() + PROVISIONING_TIMEOUT_S }
    }

    /** Run [commit] under the pairing lock if the session is still valid. */
    fun commitProvisioning(gen: Int, commit: () -> Boolean): Boolean = synchronized(lock) {
        provisioningLocked(gen) && commit()
    }

    /**
     * Seal a device-to-mobile record. Returns null when there is no active
     * session, or when a nonzero [gen] no longer matches.
     */
    fun encryptJson(plaintext: String, gen: Int = 0): JsonObject? = synchronized(lock) {
        if ((gen != 0 && gen != generation) || expireLocked() || state == State.IDLE) return null
        val counter = txCounter
        val sealed = Crypto.aesGcmSeal(
            txKey!!, recordNonce(FROM_DEVICE, counter), plaintext.toByteArray(),
            recordAad(sessionIdB64, FROM_DEVICE, counter),
        )
        txCounter++
        buildJsonObject {
            put("type", "pairing_encrypted")
            put("session_id", sessionIdB64)
            put("counter", counter.toString())
            put("ciphertext", Crypto.b64url(sealed.copyOfRange(0, sealed.size - 16)))
            put("tag", Crypto.b64url(sealed.copyOfRange(sealed.size - 16, sealed.size)))
        }
    }

    fun encryptStatus(status: String, gen: Int = 0): JsonObject? {
        val message = buildJsonObject {
            put("type", "status")
            put("status", status)
            // Apps read only type and status, so older ones ignore the token.
            if (sdkToken != null && status == "pairing_confirmed") put("sdk_token", sdkToken)
        }
        return encryptJson(message.toString(), gen)
    }

    private fun provisioningLocked(gen: Int): Boolean =
        gen != 0 && gen == generation && !expireLocked() && state == State.PROVISIONING

    private fun resetLocked() {
        generation++
        clearLocked()
    }

    private fun clearLocked() {
        state = State.IDLE
        deadline = 0.0
        rxKey = null
        txKey = null
        sessionIdB64 = ""
        rxCounter = 0
        txCounter = 0
    }

    private fun expireLocked(): Boolean {
        if (state == State.IDLE || clock() <= deadline) return false
        // Drop the keys but keep the generation, so the owner of the expired
        // session can still recognise it and close the connection.
        clearLocked()
        return true
    }

    data class SessionKeys(val mobileTx: ByteArray, val mobileRx: ByteArray, val sessionId: ByteArray)

    companion object {
        const val VERSION = 5
        const val MODEL = "hatch_link"
        const val SUITE = "p256-hkdf-sha256-aes-gcm-v1"
        const val POLICY_BUTTON = "confirm_press"
        const val POLICY_APP = "confirm_app"
        const val AUTH_OFFICIAL = "fleet_ecdsa_p256_v1"
        const val AUTH_COMMUNITY = "none"
        const val BUTTON_CONFIRM_TIMEOUT_S = 60
        const val RECORD_LABEL = "hatch-link ble setup v1"
        val SESSION_ID_LABEL = "hatch-link session id v1".toByteArray()
        const val CLIENT_FINISHED_TIMEOUT_S = 60
        const val CONFIRMED_TIMEOUT_S = 120
        const val PROVISIONING_TIMEOUT_S = 120
        const val ERROR_INVALID_HELLO = "error_pairing_invalid_hello"
        const val ERROR_DECRYPT = "error_pairing_decrypt"
        const val TO_DEVICE = 0
        const val FROM_DEVICE = 1

        private val DECIMAL_RE = Regex("[0-9]+")

        fun parseCounter(text: String?): Long {
            require(text != null && DECIMAL_RE.matches(text))
            // The wire allows up to 2^64-1; Kotlin's Long tops out at 2^63-1,
            // and no real session sends anywhere near either.
            return text.toBigInteger().also { require(it < java.math.BigInteger.ONE.shiftLeft(63)) }.toLong()
        }

        /** Canonical v5 transcript; SHA-256 of it is the `transcript_hash`. */
        fun buildTranscript(
            community: Boolean = true,
            authEpoch: Int = 0,
            policy: String = POLICY_APP,
            deviceId: String,
            nodeId: String,
            mac: String,
            firmwareVersion: String,
            mobilePub: String,
            devicePub: String,
            mobileNonce: String,
            deviceNonce: String,
        ): String {
            val button = policy == POLICY_BUTTON
            require(button || (community && policy == POLICY_APP)) { "unsupported pairing policy: $policy" }
            require(if (community) authEpoch == 0 else authEpoch > 0) { "invalid auth epoch: $authEpoch" }
            require(listOf(deviceId, nodeId, mac, firmwareVersion, mobilePub, devicePub, mobileNonce, deviceNonce)
                .all { it.isNotEmpty() }) { "transcript fields must be non-empty" }
            return listOf(
                "hatch-link-pairing-v$VERSION",
                "version=$VERSION",
                "initiator_role=mobile",
                "responder_role=link",
                "device_id=$deviceId",
                "node_id=$nodeId",
                "mac=$mac",
                "model=$MODEL",
                "firmware_version=$firmwareVersion",
                "selected_cipher_suite=$SUITE",
                "pairing_auth=${if (community) AUTH_COMMUNITY else AUTH_OFFICIAL}",
                "pairing_auth_epoch=$authEpoch",
                "pairing_policy=$policy",
                "confirm_timeout_seconds=${if (button) BUTTON_CONFIRM_TIMEOUT_S else 0}",
                "mobile_pub=$mobilePub",
                "device_pub=$devicePub",
                "mobile_nonce=$mobileNonce",
                "device_nonce=$deviceNonce",
            ).joinToString("\n")
        }

        /** `mobileTx` decrypts records the device receives; `mobileRx` encrypts what it sends. */
        fun deriveSessionKeys(
            ecdhSecret: ByteArray,
            mobileNonce: ByteArray,
            deviceNonce: ByteArray,
            transcriptHash: ByteArray,
        ): SessionKeys {
            val salt = Crypto.sha256(mobileNonce + deviceNonce + transcriptHash)
            val sessionSecret = Crypto.hkdf(ecdhSecret, salt, RECORD_LABEL.toByteArray(), 32)
            val mobileTx = Crypto.hkdfExpand(sessionSecret, "mobile->device".toByteArray(), 32)
            val mobileRx = Crypto.hkdfExpand(sessionSecret, "device->mobile".toByteArray(), 32)
            val sessionId = Crypto.sha256(SESSION_ID_LABEL + transcriptHash + ecdhSecret).copyOf(16)
            return SessionKeys(mobileTx, mobileRx, sessionId)
        }

        fun recordNonce(direction: Int, counter: Long): ByteArray =
            byteArrayOf(direction.toByte(), 0, 0, 0) + java.nio.ByteBuffer.allocate(8).putLong(counter).array()

        fun recordAad(sessionIdB64: String, direction: Int, counter: Long): ByteArray {
            val arrow = if (direction == TO_DEVICE) "m2d" else "d2m"
            return "$RECORD_LABEL|$sessionIdB64|$arrow|$counter".toByteArray()
        }
    }
}

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
