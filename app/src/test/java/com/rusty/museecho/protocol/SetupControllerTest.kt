package com.rusty.museecho.protocol

import com.rusty.museecho.TestUtil
import java.math.BigInteger
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plays the Muse phone app's side of BLE setup against the controller, over
 * chunked packets at a small MTU, using the published vector's keys.
 */
class SetupControllerTest {
    private val v = (TestUtil.json("link_pairing_v5.json")["vectors"] as JsonArray)
        .map { it as JsonObject }.first { it["name"]!!.jsonPrimitive.content == "community_app_v5" }
    private fun s(key: String) = v[key]!!.jsonPrimitive.content

    private val keys = PairingSession.deriveSessionKeys(
        Crypto.unhex(s("ecdh_secret_hex")), Crypto.b64urlDecode(s("mobile_nonce")),
        Crypto.b64urlDecode(s("device_nonce")), Crypto.b64urlDecode(s("transcript_hash")),
    )

    private class Phone : SetupController.Transport {
        val inbox = LinkedBlockingQueue<ByteArray>()
        private val assembler = ChunkAssembler()
        var disconnected = false
        override fun sendPackets(packets: List<ByteArray>) {
            packets.forEach { p -> assembler.feed(p)?.let { inbox.put(it) } }
        }
        override fun mtu() = 23
        override fun disconnect(delayMs: Long) {
            disconnected = true
        }
        fun next(): JsonObject = Json.parseToJsonElement(String(inbox.poll(5, TimeUnit.SECONDS)!!)) as JsonObject
    }

    private var phoneCounter = 0L
    private var deviceCounter = 0L

    private fun seal(text: String): ByteArray {
        val counter = phoneCounter++
        val sealed = Crypto.aesGcmSeal(keys.mobileTx, PairingSession.recordNonce(0, counter), text.toByteArray(),
            PairingSession.recordAad(s("session_id"), 0, counter))
        return buildJsonObject {
            put("action", "pairing_encrypted")
            put("session_id", s("session_id"))
            put("counter", counter.toString())
            put("ciphertext", Crypto.b64url(sealed.copyOfRange(0, sealed.size - 16)))
            put("tag", Crypto.b64url(sealed.copyOfRange(sealed.size - 16, sealed.size)))
        }.toString().toByteArray()
    }

    private fun open(env: JsonObject): JsonObject {
        assertEquals("pairing_encrypted", env.str("type"))
        val counter = deviceCounter++
        assertEquals(counter.toString(), env.str("counter"))
        val plain = Crypto.aesGcmOpen(keys.mobileRx, PairingSession.recordNonce(1, counter),
            Crypto.b64urlDecode(env.str("ciphertext")) + Crypto.b64urlDecode(env.str("tag")),
            PairingSession.recordAad(s("session_id"), 1, counter))
        return Json.parseToJsonElement(String(plain)) as JsonObject
    }

    /** Write a message the way the phone does: chunked to the MTU. */
    private fun write(controller: SetupController, data: ByteArray) =
        BleFraming.encodeChunks(data, 23).forEach(controller::onWrite)

    @Test
    fun fullSetupSavesCredentials() {
        val phone = Phone()
        var saved: SetupController.Credentials? = null
        var completed = false
        val identity = Identity(s("mac"))
        val pairing = PairingSession(
            nodeId = s("node_id"), deviceId = s("device_id"), mac = s("mac"), firmwareVersion = s("firmware_version"),
            sdkToken = "mgst_x",
            generateKey = { P256KeyPair(BigInteger(s("device_private_scalar_hex"), 16)) },
            randomBytes = { Crypto.b64urlDecode(s("device_nonce")) },
        )
        val controller = SetupController(
            pairing = pairing, identity = identity, version = s("firmware_version"), transport = phone,
            network = object : SetupController.Network {
                override fun isOnline() = true
                override fun currentConnectionEntry() = buildJsonObject {
                    put("ssid", "Home")
                    put("rssi", -40)
                    put("secure", false)
                }
            },
            provision = { creds, commit -> assertTrue(commit { saved = creds; true }) },
            onComplete = { completed = true },
            log = {},
        )

        write(controller, """{"action":"get_device_info"}""".toByteArray())
        val info = phone.next()
        assertEquals("device_info", info.str("type"))
        assertEquals("homelink-000001", info.str("node_id"))
        assertEquals("confirm_app", info.str("pairing_policy"))

        // Sensitive commands need encryption.
        write(controller, """{"action":"wifi_scan"}""".toByteArray())
        assertEquals("error_encryption_required", String(phone.inbox.poll(5, TimeUnit.SECONDS)!!))

        write(controller, buildJsonObject {
            put("action", "pairing_client_hello")
            put("version", 5)
            put("pairing_auth", "none")
            put("pairing_policy", "confirm_app")
            put("mobile_pub", s("mobile_pub"))
            put("mobile_nonce", s("mobile_nonce"))
        }.toString().toByteArray())
        val ready = phone.next()
        assertEquals("pairing_ready", ready.str("type"))
        assertEquals(s("transcript_hash"), ready.str("transcript_hash"))

        write(controller, seal("""{"action":"pairing_client_finished"}"""))
        val confirmed = open(phone.next())
        assertEquals("pairing_confirmed", confirmed.str("status"))
        assertEquals("mgst_x", confirmed.str("sdk_token"))

        write(controller, seal("""{"action":"wifi_scan"}"""))
        val scan = open(phone.next())
        assertEquals("wifi_scan_result", scan.str("type"))
        assertEquals("Home", (scan["networks"] as JsonArray)[0].jsonObject.str("ssid"))

        write(controller, seal(buildJsonObject {
            put("action", "provision_v2")
            put("ssid", "Home")
            put("password", "")
            put("access_token", "acc")
            put("refresh_token", "hatch_refresh:ref")
            put("token_type", "device")
            put("username", "rusty")
            put("api_url_v2", "https://api.example")
            put("noise_host", "noise.example")
        }.toString()))
        assertEquals(listOf("wifi_connecting", "wifi_connected", "auth_ok"),
            List(3) { open(phone.next()).str("status") })
        Thread.sleep(100)
        assertTrue(completed)
        assertEquals("acc", saved!!.accessToken)
        assertEquals("noise.example", saved!!.noiseHost)
    }

    @Test
    fun tamperedRecordDisconnects() {
        val phone = Phone()
        val controller = SetupController(
            pairing = PairingSession(s("node_id"), s("device_id"), s("mac"), "1",
                generateKey = { P256KeyPair(BigInteger(s("device_private_scalar_hex"), 16)) },
                randomBytes = { Crypto.b64urlDecode(s("device_nonce")) }),
            identity = Identity(s("mac")), version = "1", transport = phone,
            network = object : SetupController.Network {
                override fun isOnline() = true
                override fun currentConnectionEntry() = JsonObject(emptyMap())
            },
            provision = { _, _ -> }, log = {},
        )
        write(controller, buildJsonObject {
            put("action", "pairing_client_hello")
            put("version", 5)
            put("pairing_auth", "none")
            put("pairing_policy", "confirm_app")
            put("mobile_pub", s("mobile_pub"))
            put("mobile_nonce", s("mobile_nonce"))
        }.toString().toByteArray())
        phone.next()
        val good = Json.parseToJsonElement(String(seal("""{"action":"pairing_client_finished"}"""))).jsonObject
        val bad = JsonObject(good + ("tag" to kotlinx.serialization.json.JsonPrimitive(Crypto.b64url(ByteArray(16)))))
        write(controller, bad.toString().toByteArray())
        Thread.sleep(300)
        // As in the reference: once a handshake started, plaintext errors are
        // suppressed and the device just drops the connection.
        assertEquals(null, phone.inbox.poll())
        assertTrue(phone.disconnected)
    }
}
