package com.rusty.museecho.integrations

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

@Serializable
data class MonitorState(val name: String, val status: String, val since: String = "")

@Serializable
data class ServerStatus(val overall: String, val monitors: List<MonitorState> = emptyList(), val checked_at: String = "")

@Serializable
data class Activity(val busy: Boolean = false, val kinds: List<String> = emptyList())

@Serializable
data class Release(val versionCode: Int, val versionName: String, val sha256: String, val notes: String = "")

/**
 * The Latitude's echo-hub (latitude-echo-hub repo): homelab status from
 * Uptime Kuma, and the newest Muse Echo build for over-the-air updates.
 */
class Hub(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).callTimeout(120, TimeUnit.SECONDS).build()
    private val prefs = context.getSharedPreferences("museecho", Context.MODE_PRIVATE)

    var host: String
        get() = prefs.getString("hub_host", null) ?: DEFAULT_HOST
        set(v) = prefs.edit().putString("hub_host", v.trim()).apply()

    private fun get(path: String): String? = runCatching {
        http.newCall(Request.Builder().url("http://$host$path").build()).execute().use {
            if (it.isSuccessful) it.body?.string() else null
        }
    }.getOrNull()

    suspend fun status(): ServerStatus? = withContext(Dispatchers.IO) {
        get("/status")?.let { runCatching { json.decodeFromString(ServerStatus.serializer(), it) }.getOrNull() }
    }

    suspend fun activity(): Activity? = withContext(Dispatchers.IO) {
        get("/activity")?.let { runCatching { json.decodeFromString(Activity.serializer(), it) }.getOrNull() }
    }

    suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        get("/museecho/latest.json")?.let { runCatching { json.decodeFromString(Release.serializer(), it) }.getOrNull() }
    }

    /** Download the release APK and check its SHA-256. Returns the file. */
    suspend fun download(release: Release): File = withContext(Dispatchers.IO) {
        val file = File(context.cacheDir, "update.apk")
        http.newCall(Request.Builder().url("http://$host/museecho/app.apk").build()).execute().use { resp ->
            if (!resp.isSuccessful) error("download failed (HTTP ${resp.code})")
            resp.body!!.byteStream().use { input -> file.outputStream().use { input.copyTo(it) } }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        if (!digest.equals(release.sha256, true)) {
            file.delete()
            error("update failed its checksum")
        }
        file
    }

    /**
     * Hand the APK to Android's installer. Android shows its own confirmation
     * (and, the first time, asks to allow installs from Muse Echo); the
     * signature must match the installed app, so only builds from the same
     * key can replace it.
     */
    fun install(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply { setAppPackageName(context.packageName) }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("museecho.apk", 0, apk.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val intent = Intent(context, InstallResultReceiver::class.java)
            val pending = PendingIntent.getBroadcast(context, id, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            session.commit(pending.intentSender)
        }
    }

    companion object {
        const val DEFAULT_HOST = "192.168.1.158:8094"
    }
}

/** Android reports install progress here; "pending user action" means show its confirmation screen. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> com.rusty.museecho.gadget.GadgetState.log("update installed")
            else -> com.rusty.museecho.gadget.GadgetState.log(
                "update not installed: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "cancelled"}")
        }
    }
}
