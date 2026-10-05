package com.rusty.museecho.noise

import com.rusty.museecho.TestUtil
import com.rusty.museecho.protocol.Crypto
import com.rusty.museecho.protocol.LinkSession
import com.rusty.museecho.protocol.MessageDecoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Byte-for-byte interop with Meta's Python Noise implementation, using vectors
 * from tools/gen_noise_vectors.py (fixed keys and chunk ids).
 */
class NoiseInteropTest {
    private val v = TestUtil.json("noise_vectors.json")
    private fun hex(key: String) = Crypto.unhex(v[key]!!.jsonPrimitive.content)
    private fun hexList(key: String) = (v[key] as JsonArray).map { Crypto.unhex(it.jsonPrimitive.content) }
    private fun priv(name: String) = X25519KeyPair.fromPrivate(Crypto.unhex((v["privates"] as JsonObject)[name]!!.jsonPrimitive.content))

    private fun handshake(): Pair<NoiseXXInitiator, Pair<CipherState, CipherState>> {
        val keys = ArrayDeque(listOf(priv("init_e"), priv("init_s")))
        val init = NoiseXXInitiator { keys.removeFirst() }
        init.initialize()
        assertArrayEquals(hex("msg1"), init.writeMessage1())
        val payload = init.readMessage2(hex("msg2"))
        assertEquals(v["responder_payload"]!!.jsonPrimitive.content, String(payload))
        assertArrayEquals(hex("msg3"), init.writeMessage3())
        return init to init.split()
    }

    @Test
    fun handshakeMatchesPython() {
        handshake()
    }

    @Test
    fun deviceFramesMatchPython() {
        val (_, ciphers) = handshake()
        val ids = ArrayDeque(listOf(0x0102030405060708L, -5L, 77L))
        val transport = NoiseTransport(ciphers.first, ciphers.second) { ids.removeFirst() }

        val control = transport.startStreamRequest("POST", LinkSession.CONTROL_PATH)
        assertEquals(v["control_stream_id"]!!.jsonPrimitive.long, control.streamId)
        assertFramesEqual(hexList("control_frames"), control.frames)

        val register = Json.parseToJsonElement(v["register_json"]!!.jsonPrimitive.content) as JsonObject
        assertFramesEqual(hexList("register_frames"),
            transport.encryptBodyChunk(control.streamId, LinkSession.encodeMessage(register)))
        assertFramesEqual(hexList("reset_frames"), transport.encryptReset(control.streamId))
    }

    @Test
    fun decryptsVmFramesFromPython() {
        val (_, ciphers) = handshake()
        val transport = NoiseTransport(ciphers.first, ciphers.second)
        val frames = hexList("vm_frames").map { transport.decryptFrame(it) }
        val head = frames[0]!!.value as ApplicationResponse
        assertEquals(200, head.status)
        assertEquals(1L, frames[0]!!.streamId)
        val messages = MessageDecoder().feed(head.body)
        assertEquals(v["vm_reply_json"]!!.jsonPrimitive.content, messages.single().toString())
        val end = frames[1]!!.value as BodyChunk
        assertTrue(end.endBody)
    }

    @Test
    fun largePayloadsAreChunkedAndReassembled() {
        val data = ByteArray(NoiseFraming.MAX_CHUNK_PAYLOAD * 2 + 10) { it.toByte() }
        val frames = NoiseFraming.encodeFrames(data, 99)
        assertEquals(3, frames.size)
        val decoder = NoiseFrameDecoder()
        assertNull(decoder.decode(frames[2]))
        assertNull(decoder.decode(frames[0]))
        assertArrayEquals(data, decoder.decode(frames[1]))
    }

    private fun assertFramesEqual(expected: List<ByteArray>, actual: List<ByteArray>) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (e, a) -> assertEquals(Crypto.hex(e), Crypto.hex(a)) }
    }
}
