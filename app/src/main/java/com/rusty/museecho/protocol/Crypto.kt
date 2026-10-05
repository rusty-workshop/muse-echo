package com.rusty.museecho.protocol

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.asn1.x9.ECNamedCurveTable
import org.bouncycastle.crypto.agreement.ECDHBasicAgreement
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.bouncycastle.util.BigIntegers

/** Small crypto helpers shared by BLE pairing and the Noise session. */
object Crypto {
    val random = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

    fun sha256(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        parts.forEach { md.update(it) }
        return md.digest()
    }

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        // HMAC accepts an empty key, but SecretKeySpec does not.
        mac.init(SecretKeySpec(if (key.isEmpty()) ByteArray(32) else key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    /** RFC 5869 HKDF-Expand with SHA-256. */
    fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var t = ByteArray(0)
        var counter = 1
        while (out.size() < length) {
            t = hmacSha256(prk, t + info + byteArrayOf(counter.toByte()))
            out.write(t)
            counter++
        }
        return out.toByteArray().copyOf(length)
    }

    /** RFC 5869 HKDF (extract then expand) with SHA-256. */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray =
        hkdfExpand(hmacSha256(salt, ikm), info, length)

    class BadTag : Exception("AES-GCM tag mismatch")

    /** AES-256-GCM seal; returns ciphertext with the 16-byte tag appended. */
    fun aesGcmSeal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        return cipher.doFinal(plaintext)
    }

    /** AES-256-GCM open of ciphertext||tag. Throws [BadTag] on failure. */
    fun aesGcmOpen(key: ByteArray, nonce: ByteArray, sealed: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        try {
            return cipher.doFinal(sealed)
        } catch (e: AEADBadTagException) {
            throw BadTag()
        } catch (e: javax.crypto.BadPaddingException) {
            throw BadTag()
        }
    }

    fun b64url(data: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(data)

    private val B64URL_RE = Regex("[A-Za-z0-9_-]+")

    /** Decode unpadded base64url, rejecting anything the firmware rejects. */
    fun b64urlDecode(text: Any?, maxChars: Int = 4096): ByteArray {
        require(text is String && text.length in 1..maxChars) { "invalid base64url length" }
        require(text.length % 4 != 1 && B64URL_RE.matches(text)) { "invalid base64url" }
        return Base64.getUrlDecoder().decode(text)
    }

    fun hex(data: ByteArray): String = data.joinToString("") { "%02x".format(it) }

    fun unhex(text: String): ByteArray =
        ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

/** P-256 ECDH, as BLE pairing uses it. */
class P256KeyPair(val privateScalar: BigInteger) {
    val publicPoint: ByteArray = DOMAIN.g.multiply(privateScalar).normalize().getEncoded(false)

    fun agree(peerUncompressed: ByteArray): ByteArray {
        val point = DOMAIN.curve.decodePoint(peerUncompressed)
        require(point.isValid && !point.isInfinity) { "invalid P-256 point" }
        val agreement = ECDHBasicAgreement()
        agreement.init(ECPrivateKeyParameters(privateScalar, DOMAIN))
        val secret = agreement.calculateAgreement(ECPublicKeyParameters(point, DOMAIN))
        return BigIntegers.asUnsignedByteArray(32, secret)
    }

    companion object {
        val DOMAIN: ECDomainParameters = ECNamedCurveTable.getByName("secp256r1").let {
            ECDomainParameters(it.curve, it.g, it.n, it.h)
        }

        fun generate(): P256KeyPair {
            while (true) {
                val d = BigInteger(1, Crypto.randomBytes(32))
                if (d.signum() > 0 && d < DOMAIN.n) return P256KeyPair(d)
            }
        }

        /** True if [bytes] decode to a valid point on P-256. */
        fun isValidPoint(bytes: ByteArray): Boolean = try {
            val p = DOMAIN.curve.decodePoint(bytes)
            p.isValid && !p.isInfinity
        } catch (e: IllegalArgumentException) {
            false
        }
    }
}
