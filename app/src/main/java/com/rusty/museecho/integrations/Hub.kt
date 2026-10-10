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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.OkHttpClient
import okhttp3.Request

@Serializable
data class MonitorState(val name: String, val status: String, val since: String = "")

@Serializable
data class ServerStatus(val overall: String, val monitors: List<MonitorState> = emptyList(), val checked_at: String = "")

@Serializable
data class HomelabMonitor(val name: String, val status: String, val since: String = "", val uptime_24h: Double? = null)

@Serializable
data class HomelabDisk(val total_gb: Double = 0.0, val used_pct: Double = 0.0)

@Serializable
data class HomelabMemory(val total_mb: Int = 0, val used_pct: Double? = null)

@Serializable
data class HomelabBattery(val percent: Int = 0, val on_ac: Boolean = true)

@Serializable
data class HomelabContainer(val name: String, val state: String, val health: String = "")

@Serializable
data class HomelabBackup(val at: String? = null, val ok: Boolean = false, val running: Boolean = false)

/** The Latitude in more detail, for the Echo's homelab page. */
@Serializable
data class Homelab(
    val overall: String = "",
    val monitors: List<HomelabMonitor> = emptyList(),
    val disk: HomelabDisk = HomelabDisk(),
    val memory: HomelabMemory = HomelabMemory(),
    val load: List<Double> = emptyList(),
    val cpus: Int = 1,
    val battery: HomelabBattery? = null,
    val containers: List<HomelabContainer> = emptyList(),
    val backup: HomelabBackup = HomelabBackup(),
    val uptime_s: Long? = null,
)

/** A photo in the Latitude's Syncthing "Echo Photos" folder. */
@Serializable
data class HubPhoto(val name: String, val size: Long = 0, val mtime: Long = 0)

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

    private val jsonType = "application/json".toMediaType()

    private fun post(path: String, body: String, headers: Map<String, String> = emptyMap()): Pair<Int, String?> = runCatching {
        val req = Request.Builder().url("http://$host$path").post(body.toRequestBody(jsonType))
        headers.forEach { (k, v) -> req.header(k, v) }
        http.newCall(req.build()).execute().use { it.code to it.body?.string() }
    }.getOrDefault(0 to null)

    /** Every minute: the Echo is alive (echo-hub turns this into a health check Kuma can watch). */
    suspend fun heartbeat(info: JsonObject): Boolean = withContext(Dispatchers.IO) { post("/echo/heartbeat", info.toString()).first == 200 }

    /** Timers etc., for the watch. */
    suspend fun postState(state: JsonObject): Boolean = withContext(Dispatchers.IO) { post("/echo/state", state.toString()).first == 200 }

    /** Commands queued for the Echo (from the watch); each is handed over once. */
    suspend fun inbox(): List<JsonObject> = withContext(Dispatchers.IO) {
        get("/echo/inbox")?.let { runCatching { (json.parseToJsonElement(it) as JsonArray).mapNotNull { e -> e as? JsonObject } }.getOrNull() }.orEmpty()
    }

    suspend fun homelab(): Homelab? = withContext(Dispatchers.IO) {
        get("/homelab")?.let { runCatching { json.decodeFromString(Homelab.serializer(), it) }.getOrNull() }
    }

    suspend fun photos(): List<HubPhoto>? = withContext(Dispatchers.IO) {
        get("/photos")?.let { runCatching { json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(HubPhoto.serializer()), it) }.getOrNull() }
    }

    suspend fun photo(name: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            val url = "http://$host/photos/" + java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")
            http.newCall(Request.Builder().url(url).build()).execute().use { if (it.isSuccessful) it.body?.bytes() else null }
        }.getOrNull()
    }

    /**
     * The key that guards this Echo's backups on the hub. Made once; echo-hub trusts the
     * first key it's shown (after a reflash, reset it there: `echo_hub.py reset-echo-key`).
     */
    private val backupKey: String
        get() = prefs.getString("hub_backup_key", null) ?: java.security.SecureRandom().let { rnd ->
            ByteArray(24).also(rnd::nextBytes).joinToString("") { "%02x".format(it) }
        }.also { prefs.edit().putString("hub_backup_key", it).apply() }

    /** Registers the key (if the hub has none yet) and uploads [bundle]. Returns null or what went wrong. */
    suspend fun backup(bundle: String): String? = withContext(Dispatchers.IO) {
        val key = backupKey
        val (reg, _) = post("/echo/register", """{"key":"$key"}""")
        if (reg == 0) return@withContext "couldn't reach the echo-hub at $host"
        if (reg != 200) return@withContext "the echo-hub knows a different Echo (reset its key on the Latitude)"
        val (code, body) = post("/echo/backup", bundle, mapOf("X-Echo-Key" to key))
        if (code == 200) null else "backup failed (HTTP $code${body?.let { ": " + it.take(80) } ?: ""})"
    }

    /** The newest backup, or null with why. */
    suspend fun restore(): Pair<String?, String?> = withContext(Dispatchers.IO) {
        val key = backupKey
        val (reg, _) = post("/echo/register", """{"key":"$key"}""")
        if (reg == 0) return@withContext null to "couldn't reach the echo-hub at $host"
        if (reg != 200) return@withContext null to "the echo-hub knows a different Echo: on the Latitude run " +
            "`python3 ~/.local/share/echo-hub/echo_hub.py reset-echo-key`, then try again"
        runCatching {
            http.newCall(Request.Builder().url("http://$host/echo/backup").header("X-Echo-Key", key).build()).execute().use {
                when {
                    it.isSuccessful -> it.body?.string() to null
                    it.code == 404 -> null to "no backup on the Latitude yet"
                    else -> null to "restore failed (HTTP ${it.code})"
                }
            }
        }.getOrElse { null to "couldn't reach the echo-hub at $host" }
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
