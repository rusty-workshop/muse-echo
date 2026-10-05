package com.rusty.museecho.protocol

import com.rusty.museecho.TestUtil
import java.math.BigInteger
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Checks the pairing port against Meta's published `link_pairing_v5.json` vectors. */
class PairingVectorsTest {
    private val vectors = (TestUtil.json("link_pairing_v5.json")["vectors"] as JsonArray).map { it as JsonObject }
    private fun JsonObject.s(key: String) = this[key]!!.jsonPrimitive.content
    private fun vector(name: String) = vectors.first { it.s("name") == name }

    @Test
    fun communityTranscriptsMatch() {
        for (name in listOf("community_v5", "community_app_v5")) {
            val v = vector(name)
            val transcript = PairingSession.buildTranscript(
                community = true,
                authEpoch = v["pairing_auth_epoch"]!!.jsonPrimitive.int,
                policy = v.s("pairing_policy"),
                deviceId = v.s("device_id"), nodeId = v.s("node_id"), mac = v.s("mac"),
                firmwareVersion = v.s("firmware_version"),
                mobilePub = v.s("mobile_pub"), devicePub = v.s("device_pub"),
                mobileNonce = v.s("mobile_nonce"), deviceNonce = v.s("device_nonce"),
            )
            assertEquals(name, v.s("transcript"), transcript)
            assertEquals(name, v.s("transcript_hash"), Crypto.b64url(Crypto.sha256(transcript.toByteArray())))
        }
    }

    @Test
    fun keyScheduleMatches() {
        for (name in listOf("community_v5", "community_app_v5", "official_v5")) {
            val v = vector(name)
            val device = P256KeyPair(BigInteger(v.s("device_private_scalar_hex"), 16))
            assertEquals(v.s("device_pub"), Crypto.b64url(device.publicPoint))
            val ecdh = device.agree(Crypto.b64urlDecode(v.s("mobile_pub")))
            assertEquals(v.s("ecdh_secret_hex"), Crypto.hex(ecdh))
            val keys = PairingSession.deriveSessionKeys(
                ecdh, Crypto.b64urlDecode(v.s("mobile_nonce")), Crypto.b64urlDecode(v.s("device_nonce")),
                Crypto.b64urlDecode(v.s("transcript_hash")),
            )
            assertEquals(v.s("mobile_tx_key_hex"), Crypto.hex(keys.mobileTx))
            assertEquals(v.s("mobile_rx_key_hex"), Crypto.hex(keys.mobileRx))
            assertEquals(v.s("session_id"), Crypto.b64url(keys.sessionId))
            assertEquals(v.s("client_finished_aad"),
                String(PairingSession.recordAad(v.s("session_id"), PairingSession.TO_DEVICE, 0)))
            val plain = Crypto.aesGcmOpen(
                keys.mobileTx, PairingSession.recordNonce(PairingSession.TO_DEVICE, 0),
                Crypto.b64urlDecode(v.s("client_finished_ciphertext")) + Crypto.b64urlDecode(v.s("client_finished_tag")),
                PairingSession.recordAad(v.s("session_id"), PairingSession.TO_DEVICE, 0),
            )
            assertEquals(v.s("client_finished_plaintext"), String(plain))
        }
    }

    private fun session(v: JsonObject, sdkToken: String? = null, clock: () -> Double = { 0.0 }) = PairingSession(
        nodeId = v.s("node_id"), deviceId = v.s("device_id"), mac = v.s("mac"),
        firmwareVersion = v.s("firmware_version"), sdkToken = sdkToken, clock = clock,
        generateKey = { P256KeyPair(BigInteger(v.s("device_private_scalar_hex"), 16)) },
        randomBytes = { Crypto.b64urlDecode(v.s("device_nonce")) },
    )

    private fun hello(v: JsonObject) = buildJsonObject {
        put("action", "pairing_client_hello")
        put("version", 5)
        put("pairing_auth", "none")
        put("pairing_policy", "confirm_app")
        put("mobile_pub", v.s("mobile_pub"))
        put("mobile_nonce", v.s("mobile_nonce"))
    }

    private fun clientFinished(v: JsonObject) = buildJsonObject {
        put("type", "pairing_encrypted")
        put("session_id", v.s("session_id"))
        put("counter", "0")
        put("ciphertext", v.s("client_finished_ciphertext"))
        put("tag", v.s("client_finished_tag"))
    }

    @Test
    fun deviceHandshakeReproducesTheVector() {
        val v = vector("community_app_v5")
        val s = session(v, sdkToken = "mgst_test")
        val ready = s.handleHello(hello(v))
        assertEquals("pairing_ready", ready.s("type"))
        assertEquals(v.s("device_pub"), ready.s("device_pub"))
        assertEquals(v.s("transcript_hash"), ready.s("transcript_hash"))
        assertEquals(v.s("session_id"), ready.s("session_id"))

        val plaintext = s.decrypt(clientFinished(v))
        assertEquals(v.s("client_finished_plaintext"), plaintext)
        val gen = s.handleClientFinished(kotlinx.serialization.json.Json.parseToJsonElement(plaintext) as JsonObject)
        assertNotEquals(0, gen)
        assertTrue(s.confirmed)

        // The confirmation the device sends back opens with the mobile's rx key
        // and carries the SDK token.
        val env = s.encryptStatus("pairing_confirmed", gen)!!
        val keys = PairingSession.deriveSessionKeys(
            Crypto.unhex(v.s("ecdh_secret_hex")), Crypto.b64urlDecode(v.s("mobile_nonce")),
            Crypto.b64urlDecode(v.s("device_nonce")), Crypto.b64urlDecode(v.s("transcript_hash")),
        )
        val opened = Crypto.aesGcmOpen(
            keys.mobileRx, PairingSession.recordNonce(PairingSession.FROM_DEVICE, 0),
            Crypto.b64urlDecode(env.s("ciphertext")) + Crypto.b64urlDecode(env.s("tag")),
            PairingSession.recordAad(v.s("session_id"), PairingSession.FROM_DEVICE, 0),
        )
        assertEquals("""{"type":"status","status":"pairing_confirmed","sdk_token":"mgst_test"}""", String(opened))
        assertEquals("0", env.s("counter"))
    }

    @Test
    fun replayedRecordIsRejectedAndClearsTheSession() {
        val v = vector("community_app_v5")
        val s = session(v)
        s.handleHello(hello(v))
        s.decrypt(clientFinished(v))
        try {
            s.decrypt(clientFinished(v))
            fail("replay accepted")
        } catch (e: PairingSession.PairingError) {
            assertEquals(PairingSession.ERROR_DECRYPT, e.status)
        }
        assertEquals(PairingSession.State.IDLE, s.currentState)
        assertNull(s.encryptJson("{}"))
    }

    @Test
    fun badHellosAreRejected() {
        val v = vector("community_app_v5")
        val good = hello(v)
        val bad = listOf(
            JsonObject(good + ("version" to JsonPrimitive(4))),
            JsonObject(good + ("version" to JsonPrimitive(true))),
            JsonObject(good + ("pairing_policy" to JsonPrimitive("confirm_press"))),
            JsonObject(good + ("mobile_pub" to JsonPrimitive(v.s("device_nonce")))),
            JsonObject(good + ("mobile_pub" to JsonPrimitive("BA" + "A".repeat(85)))), // 65 bytes, not on the curve
        )
        for (msg in bad) {
            try {
                session(v).handleHello(msg)
                fail("accepted $msg")
            } catch (e: PairingSession.PairingError) {
                assertEquals(PairingSession.ERROR_INVALID_HELLO, e.status)
            }
        }
    }

    @Test
    fun sessionExpiresWithoutClientFinished() {
        val v = vector("community_app_v5")
        var now = 0.0
        val s = session(v, clock = { now })
        s.handleHello(hello(v))
        now = PairingSession.CLIENT_FINISHED_TIMEOUT_S + 1.0
        try {
            s.decrypt(clientFinished(v))
            fail("expired session accepted a record")
        } catch (e: PairingSession.PairingError) {
            assertEquals(PairingSession.ERROR_DECRYPT, e.status)
        }
    }

    @Test
    fun identityNamesShareTheSuffix() {
        val id = Identity("02:00:00:ab:cd:ef")
        assertEquals("homelink-abcdef", id.nodeId)
        assertEquals("MuseGadgetABCDEF", id.bleName)
        assertEquals("hatch-link:02:00:00:ab:cd:ef", id.deviceId)
        val mac = Identity.generateMac { ByteArray(it) { 0xFF.toByte() } }
        assertEquals("fe:ff:ff:ff:ff:ff", mac)
        assertArrayEquals(byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 7), PairingSession.recordNonce(0, 7))
    }
}
