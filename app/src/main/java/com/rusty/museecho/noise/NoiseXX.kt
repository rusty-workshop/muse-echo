package com.rusty.museecho.noise

import com.rusty.museecho.protocol.Crypto
import java.nio.ByteBuffer
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters

/** Noise_XX_25519_AESGCM_SHA256 (port of `noise_xx.py`). */
class NoiseProtocolError(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

private val PROTOCOL_NAME = "Noise_XX_25519_AESGCM_SHA256".toByteArray()
private const val DH_LEN = 32
private const val TAG_LEN = 16
private const val MAX_SAFE_NONCE = (1L shl 53) - 1

private val LOW_ORDER_POINTS: List<ByteArray> = listOf(
    "0000000000000000000000000000000000000000000000000000000000000000",
    "0100000000000000000000000000000000000000000000000000000000000000",
    "e0eb7a7c3b41b8ae1656e3faf19fc46ada098deb9c32b1fd866205165f49b800",
    "5f9c95bca3508c24b1d0b1559c83ef5b04445cc4581c8e86d8224eddd09f1157",
    "ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
    "edffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
    "eeffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
).map(Crypto::unhex)

class X25519KeyPair(val private: X25519PrivateKeyParameters) {
    val publicKey: ByteArray = private.generatePublicKey().encoded

    companion object {
        fun generate() = X25519KeyPair(X25519PrivateKeyParameters(Crypto.random))
        fun fromPrivate(bytes: ByteArray) = X25519KeyPair(X25519PrivateKeyParameters(bytes, 0))
    }
}

internal fun x25519(private: X25519KeyPair, publicKey: ByteArray): ByteArray {
    if (publicKey.size != DH_LEN) throw NoiseProtocolError("x25519: invalid public key length")
    if (LOW_ORDER_POINTS.any { java.security.MessageDigest.isEqual(it, publicKey) }) {
        throw NoiseProtocolError("x25519: rejected low-order public key")
    }
    val shared = ByteArray(32)
    try {
        private.private.generateSecret(X25519PublicKeyParameters(publicKey, 0), shared, 0)
    } catch (e: IllegalStateException) {
        throw NoiseProtocolError("x25519: DH produced all-zeros output", e)
    }
    if (shared.all { it == 0.toByte() }) throw NoiseProtocolError("x25519: DH produced all-zeros output")
    return shared
}

private fun noiseHkdf(ck: ByteArray, ikm: ByteArray, n: Int): List<ByteArray> {
    val temp = Crypto.hmacSha256(ck, ikm)
    val o1 = Crypto.hmacSha256(temp, byteArrayOf(1))
    val o2 = Crypto.hmacSha256(temp, o1 + byteArrayOf(2))
    if (n == 2) return listOf(o1, o2)
    return listOf(o1, o2, Crypto.hmacSha256(temp, o2 + byteArrayOf(3)))
}

private fun nonceIv(nonce: Long): ByteArray = ByteArray(4) + ByteBuffer.allocate(8).putLong(nonce).array()

/** One direction of an established (or in-handshake) Noise cipher. */
class CipherState {
    private var key: ByteArray? = null
    private var nonce = 0L
    private var poisoned = false

    fun initializeKey(k: ByteArray) {
        alive()
        if (k.size != 32) throw NoiseProtocolError("CipherState: AES-GCM key must be 32 bytes")
        key = k.copyOf()
        nonce = 0
    }

    fun hasKey() = key != null

    @Synchronized
    fun encryptWithAd(ad: ByteArray, plaintext: ByteArray): ByteArray {
        alive()
        val k = key ?: return plaintext
        val n = nextNonce()
        return try {
            Crypto.aesGcmSeal(k, nonceIv(n), plaintext, ad)
        } catch (e: Exception) {
            poisoned = true
            throw e
        }
    }

    @Synchronized
    fun decryptWithAd(ad: ByteArray, ciphertext: ByteArray): ByteArray {
        alive()
        val k = key ?: return ciphertext
        val n = nextNonce()
        return try {
            Crypto.aesGcmOpen(k, nonceIv(n), ciphertext, ad)
        } catch (e: Crypto.BadTag) {
            poisoned = true
            throw NoiseProtocolError("CipherState: decrypt failed", e)
        } catch (e: Exception) {
            poisoned = true
            throw e
        }
    }

    private fun nextNonce(): Long {
        if (nonce >= MAX_SAFE_NONCE) {
            poisoned = true
            throw NoiseProtocolError("CipherState: nonce exhausted")
        }
        return nonce++
    }

    private fun alive() {
        if (poisoned) throw NoiseProtocolError("CipherState: poisoned after prior failure")
    }
}

private class SymmetricState {
    var ck = ByteArray(32)
    var h = ByteArray(32)
    var cipher = CipherState()

    fun initialize() {
        h = PROTOCOL_NAME.copyOf(32) // name is exactly 28 bytes: padded with zeros
        ck = h.copyOf()
        mixHash(ByteArray(0))
    }

    fun mixHash(data: ByteArray) {
        h = Crypto.sha256(h, data)
    }

    fun mixKey(ikm: ByteArray) {
        val (newCk, tempK) = noiseHkdf(ck, ikm, 2)
        ck = newCk
        cipher = CipherState().apply { initializeKey(tempK) }
    }

    fun encryptAndHash(plaintext: ByteArray): ByteArray =
        cipher.encryptWithAd(h, plaintext).also { mixHash(it) }

    fun decryptAndHash(ciphertext: ByteArray): ByteArray =
        cipher.decryptWithAd(h, ciphertext).also { mixHash(ciphertext) }

    fun split(): Pair<CipherState, CipherState> {
        val (k1, k2) = noiseHkdf(ck, ByteArray(0), 2)
        ck = ByteArray(32)
        h = ByteArray(32)
        return CipherState().apply { initializeKey(k1) } to CipherState().apply { initializeKey(k2) }
    }
}

/**
 * The initiator side of XX: the device. The static key is generated fresh for
 * each session; the VM authenticates the device with the bearer token on the
 * WebSocket upgrade, not with this key.
 */
class NoiseXXInitiator(
    private val generateKey: () -> X25519KeyPair = X25519KeyPair::generate,
) {
    private enum class Phase { CREATED, INITIALIZED, MSG1_SENT, MSG2_READ, MSG3_SENT, SPLIT, DEAD }

    private val ss = SymmetricState()
    private var e: X25519KeyPair? = null
    private var re: ByteArray? = null
    private var phase = Phase.CREATED

    private fun require(expected: Phase, method: String) {
        if (phase == Phase.DEAD) throw NoiseProtocolError("NoiseXX: $method called on dead handshake")
        if (phase != expected) throw NoiseProtocolError("NoiseXX: $method called in wrong phase")
    }

    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (ex: Exception) {
        phase = Phase.DEAD
        throw ex
    }

    fun initialize() {
        require(Phase.CREATED, "initialize")
        ss.initialize()
        phase = Phase.INITIALIZED
    }

    fun writeMessage1(): ByteArray {
        require(Phase.INITIALIZED, "writeMessage1")
        return guarded {
            val eph = generateKey().also { e = it }
            ss.mixHash(eph.publicKey)
            ss.encryptAndHash(ByteArray(0))
            phase = Phase.MSG1_SENT
            eph.publicKey
        }
    }

    fun readMessage2(msg: ByteArray): ByteArray {
        require(Phase.MSG1_SENT, "readMessage2")
        if (msg.size < DH_LEN + DH_LEN + TAG_LEN + TAG_LEN) {
            phase = Phase.DEAD
            throw NoiseProtocolError("NoiseXX: message 2 too short (${msg.size})")
        }
        return guarded {
            var off = 0
            val remoteE = msg.copyOfRange(0, DH_LEN).also { re = it }
            ss.mixHash(remoteE)
            off += DH_LEN
            ss.mixKey(x25519(e!!, remoteE))
            val rs = ss.decryptAndHash(msg.copyOfRange(off, off + DH_LEN + TAG_LEN))
            off += DH_LEN + TAG_LEN
            ss.mixKey(x25519(e!!, rs))
            val payload = ss.decryptAndHash(msg.copyOfRange(off, msg.size))
            phase = Phase.MSG2_READ
            payload
        }
    }

    fun writeMessage3(): ByteArray {
        require(Phase.MSG2_READ, "writeMessage3")
        return guarded {
            val s = generateKey()
            val encS = ss.encryptAndHash(s.publicKey)
            ss.mixKey(x25519(s, re!!))
            val encPayload = ss.encryptAndHash(ByteArray(0))
            phase = Phase.MSG3_SENT
            encS + encPayload
        }
    }

    /** Returns (send, receive) cipher states. */
    fun split(): Pair<CipherState, CipherState> {
        require(Phase.MSG3_SENT, "split")
        phase = Phase.SPLIT
        e = null
        re = null
        return ss.split()
    }
}

/** The responder side, for tests (a stand-in for the Muse VM). */
class NoiseXXResponder(
    private val payload: ByteArray = ByteArray(0),
    private val generateKey: () -> X25519KeyPair = X25519KeyPair::generate,
) {
    private val ss = SymmetricState().apply { initialize() }
    private var e: X25519KeyPair? = null

    fun readMessage1AndWriteMessage2(msg1: ByteArray): ByteArray {
        if (msg1.size < DH_LEN) throw NoiseProtocolError("NoiseXX: message 1 too short")
        val remoteE = msg1.copyOfRange(0, DH_LEN)
        ss.mixHash(remoteE)
        ss.decryptAndHash(msg1.copyOfRange(DH_LEN, msg1.size))
        val eph = generateKey().also { e = it }
        ss.mixHash(eph.publicKey)
        ss.mixKey(x25519(eph, remoteE))
        val s = generateKey()
        val encS = ss.encryptAndHash(s.publicKey)
        ss.mixKey(x25519(s, remoteE))
        return eph.publicKey + encS + ss.encryptAndHash(payload)
    }

    fun readMessage3(msg3: ByteArray) {
        val rs = ss.decryptAndHash(msg3.copyOfRange(0, DH_LEN + TAG_LEN))
        ss.mixKey(x25519(e!!, rs))
        ss.decryptAndHash(msg3.copyOfRange(DH_LEN + TAG_LEN, msg3.size))
    }

    /** Returns (send, receive) from the responder's point of view. */
    fun split(): Pair<CipherState, CipherState> = ss.split().let { (c1, c2) -> c2 to c1 }
}
