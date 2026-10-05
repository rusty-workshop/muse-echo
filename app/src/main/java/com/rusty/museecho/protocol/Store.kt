package com.rusty.museecho.protocol

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What a finished pairing leaves behind: the device token pair and where to use it. */
@Serializable
data class PairingRecord(
    val access_token: String,
    val refresh_token: String,
    val token_type: String = "device",
    val username: String = "",
    val api_url: String = "",
    val api_url_v2: String = "",
    val noise_host: String = "",
    val access_token_saved_at: Long = 0,
)

@Serializable
private data class IdentityFile(val mac: String)

/**
 * Persistent device state (port of `config.py`), in the app's private files
 * dir: `identity.json`, `pairing.json` and `sdk_token`. The layout and JSON
 * match the Linux SDK's `/var/lib/musegadget`, so the files can be moved
 * between this app and a Linux machine running `musegadget pair` (see
 * tools/pair-via-linux.sh).
 */
class Store(private val dir: File) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    fun loadOrCreateIdentity(): Identity {
        read(IDENTITY_FILE)?.let { text ->
            runCatching { json.decodeFromString<IdentityFile>(text) }.getOrNull()
                ?.mac?.takeIf { Identity.MAC_RE.matches(it) }?.let { return Identity(it) }
        }
        val identity = Identity(Identity.generateMac())
        write(IDENTITY_FILE, json.encodeToString(IdentityFile.serializer(), IdentityFile(identity.mac)))
        return identity
    }

    fun loadPairing(): PairingRecord? = read(PAIRING_FILE)?.let {
        runCatching { json.decodeFromString<PairingRecord>(it) }.getOrNull()
    }?.takeIf { it.access_token.isNotEmpty() && it.refresh_token.isNotEmpty() }

    fun savePairing(record: PairingRecord) = write(PAIRING_FILE, json.encodeToString(PairingRecord.serializer(), record))

    fun deletePairing() {
        File(dir, PAIRING_FILE).delete()
    }

    /** The SDK token, or null if none is saved. Throws if the saved one is malformed. */
    fun sdkToken(): String? {
        val token = read(SDK_TOKEN_FILE)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        require(isValidSdkToken(token)) { "the SDK token is not valid; copy it again from gadgets.muse.ai" }
        return token
    }

    fun saveSdkToken(token: String) {
        require(isValidSdkToken(token.trim())) { "that doesn't look like an SDK token (mgst_…)" }
        write(SDK_TOKEN_FILE, token.trim())
    }

    private fun read(name: String): String? = File(dir, name).takeIf { it.isFile }?.readText()

    /** Atomic write: a crash leaves either the old file or the new one. */
    private fun write(name: String, text: String) {
        dir.mkdirs()
        val tmp = File(dir, "$name.tmp")
        tmp.outputStream().use { out ->
            out.write(text.toByteArray())
            out.fd.sync()
        }
        if (!tmp.renameTo(File(dir, name))) error("could not save $name")
    }

    companion object {
        const val IDENTITY_FILE = "identity.json"
        const val PAIRING_FILE = "pairing.json"
        const val SDK_TOKEN_FILE = "sdk_token"
        private val SDK_TOKEN_RE = Regex("mgst_[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]")

        fun isValidSdkToken(token: String) = SDK_TOKEN_RE.matches(token)
    }
}
