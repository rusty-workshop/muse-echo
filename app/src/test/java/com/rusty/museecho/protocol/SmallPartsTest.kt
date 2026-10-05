package com.rusty.museecho.protocol

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SmallPartsTest {
    @Test
    fun bleChunksRoundTripAtEveryMtu() {
        val data = ByteArray(1000) { (it * 7).toByte() }
        for (mtu in listOf(23, 64, 185, 517)) {
            val packets = BleFraming.encodeChunks(data, mtu)
            assertTrue(packets.all { it.size <= minOf(mtu - 3, 160) })
            val asm = ChunkAssembler()
            val out = packets.map { asm.feed(it) }
            assertTrue(out.dropLast(1).all { it == null })
            assertArrayEquals(data, out.last())
        }
    }

    @Test
    fun outOfOrderChunkDiscardsTheMessage() {
        val packets = BleFraming.encodeChunks(ByteArray(100), 23)
        val asm = ChunkAssembler()
        assertNull(asm.feed(packets[0]))
        assertNull(asm.feed(packets[2]))
        assertNull(asm.feed(packets.last()))
        assertArrayEquals(byteArrayOf(1, 2), asm.feed(byteArrayOf(1, 2))) // unchunked passes straight through
    }

    @Test
    fun storeKeepsIdentityAndValidatesTokens() {
        val dir = Files.createTempDirectory("store").toFile()
        val store = Store(dir)
        val id = store.loadOrCreateIdentity()
        assertEquals(id, Store(dir).loadOrCreateIdentity())
        assertNull(store.loadPairing())
        store.savePairing(PairingRecord("a", "r", noise_host = "n", access_token_saved_at = 5))
        assertEquals("n", Store(dir).loadPairing()!!.noise_host)
        // The file format matches the Linux SDK's pairing.json.
        assertTrue(File(dir, "pairing.json").readText().contains("\"access_token_saved_at\": 5"))

        try {
            store.saveSdkToken("mgst_short")
            fail("bad token accepted")
        } catch (e: IllegalArgumentException) {
        }
        val token = "mgst_" + "a".repeat(42) + "A"
        store.saveSdkToken("  $token\n")
        assertEquals(token, store.sdkToken())
        dir.deleteRecursively()
    }

    @Test
    fun uriComponentMatchesPythonQuote() {
        assertEquals("a%20b%2Fc!~*'()-_.", LinkSession.uriComponent("a b/c!~*'()-_."))
    }
}
