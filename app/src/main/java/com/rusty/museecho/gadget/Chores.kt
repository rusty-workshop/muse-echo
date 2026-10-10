package com.rusty.museecho.gadget

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.rusty.museecho.BuildConfig
import com.rusty.museecho.MainActivity
import com.rusty.museecho.integrations.Agenda
import com.rusty.museecho.integrations.EchoBackup
import com.rusty.museecho.integrations.Hub
import com.rusty.museecho.integrations.PhotoFrame
import com.rusty.museecho.integrations.Timers
import com.rusty.museecho.integrations.Travel
import java.io.File
import java.util.Calendar
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The Echo's background jobs that talk to the rest of the house: heartbeat and
 * state for the Latitude (Kuma health, the watch), the watch's inbox, nightly
 * backups, the Syncthing photo folder, the internet check, staying in front,
 * leave-by times and the what's-new card.
 */
class Chores(
    private val context: Context,
    private val scope: CoroutineScope,
    private val hub: Hub,
    private val timers: Timers,
    private val frame: PhotoFrame,
    private val agenda: Agenda,
    private val say: (String) -> Unit,
    private val chime: () -> Unit,
    private val toFront: () -> Unit,
    private val isQuiet: () -> Boolean,
) {
    private val travel = Travel(context)
    private val prefs = context.getSharedPreferences("museecho", Context.MODE_PRIVATE)
    private val startedAt = System.currentTimeMillis()

    fun start() {
        scope.launch { heartbeat() }
        scope.launch { timers.items.collect { postState() } }
        scope.launch { inbox() }
        scope.launch { backups() }
        scope.launch { photoSync() }
        scope.launch { internet() }
        scope.launch { stayInFront() }
        scope.launch { leaveBy() }
        whatsNew()
    }

    // -- Heartbeat and state ----------------------------------------------------------------

    private suspend fun heartbeat() {
        while (true) {
            hub.heartbeat(buildJsonObject {
                put("version", BuildConfig.VERSION_NAME)
                put("link", GadgetState.link.value.name.lowercase())
                put("internet", GadgetState.internet.value)
                put("uptime_s", (System.currentTimeMillis() - startedAt) / 1000)
                put("on_screen", MainActivity.visible)
            })
            postState()
            delay(60_000)
        }
    }

    private suspend fun postState() {
        val now = System.currentTimeMillis()
        hub.postState(buildJsonObject {
            put("timers", buildJsonArray {
                timers.items.value.sortedBy { it.dueAtMs }.forEach { c ->
                    add(buildJsonObject {
                        put("label", c.label)
                        put("kind", c.kind)
                        put("due_ms", c.dueAtMs)
                        put("left_s", ((c.dueAtMs - now) / 1000).coerceAtLeast(0))
                    })
                }
            })
            put("ringing", GadgetState.ringing.value)
            put("quiet", GadgetState.quiet.value)
            put("at_ms", now)
        })
    }

    // -- The watch's inbox ------------------------------------------------------------------

    private suspend fun inbox() {
        while (true) {
            delay(if (isQuiet()) 20_000 else 4_000)
            for (item in hub.inbox()) {
                when ((item["type"] as? JsonPrimitive)?.contentOrNull) {
                    "timer" -> {
                        val secs = (item["seconds"] as? JsonPrimitive)?.intOrNull ?: continue
                        val label = (item["label"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                        val made = runCatching { timers.addTimer(secs * 1000L, label) }.getOrNull() ?: continue
                        GadgetState.log("timer from the watch: ${made.label}")
                        GadgetState.react(Reaction.HOP)
                        GadgetState.say("⏱ ${made.label}, from your watch", 8)
                        postState()
                    }
                    "announce" -> {
                        val text = (item["text"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: continue
                        GadgetState.log("announcement from the watch")
                        GadgetState.lastTouch = System.currentTimeMillis()
                        GadgetState.setAsleep(false)
                        GadgetState.react(Reaction.WAVE)
                        GadgetState.show(Screen.Text("📣 Announcement", text))
                        toFront()
                        if (!isQuiet()) say(text) else chime()
                    }
                }
            }
        }
    }

    // -- Nightly backup ---------------------------------------------------------------------

    private suspend fun backups() {
        delay(5 * 60_000L)
        while (true) {
            val last = prefs.getLong("last_backup_ms", 0)
            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            // Around 3 a.m., or whenever it's been over a day (e.g. the Echo was off at 3).
            if ((hour == 3 && System.currentTimeMillis() - last > 12 * 3_600_000L) ||
                System.currentTimeMillis() - last > 26 * 3_600_000L
            ) {
                backupNow()
            }
            delay(30 * 60_000L)
        }
    }

    /** Returns null when it worked, else what went wrong. */
    suspend fun backupNow(): String? {
        val bundle = withContext(Dispatchers.IO) { EchoBackup.collect(context, BuildConfig.VERSION_NAME) }
        val problem = hub.backup(bundle)
        if (problem == null) prefs.edit().putLong("last_backup_ms", System.currentTimeMillis()).apply()
        GadgetState.log(problem?.let { "backup failed: $it" } ?: "backed up to the Latitude (${bundle.length / 1024} KB)")
        return problem
    }

    /** Fetches the newest backup and writes it back; the caller restarts the app. Returns null or the problem. */
    suspend fun restore(): String? {
        val (bundle, problem) = hub.restore()
        if (bundle == null) return problem ?: "nothing to restore"
        return runCatching {
            val n = withContext(Dispatchers.IO) { EchoBackup.apply(context, bundle) }
            GadgetState.log("restored $n files from the Latitude backup")
            null
        }.getOrElse { "restore failed: ${it.message}" }
    }

    fun lastBackupMs(): Long = prefs.getLong("last_backup_ms", 0)

    // -- Photos from the Syncthing folder ---------------------------------------------------

    private suspend fun photoSync() {
        delay(30_000)
        while (true) {
            runCatching { syncPhotos() }.onFailure { GadgetState.log("photo sync failed: ${it.message}") }
            delay(15 * 60_000L)
        }
    }

    /** Mirrors the Latitude's "Echo Photos" folder into the frame as sync_* files; leaves other photos alone. */
    suspend fun syncPhotos(): Int {
        val remote = hub.photos() ?: return 0
        val dir = frame.dir
        fun localName(name: String, mtime: Long) = "sync_" + name.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9._-]"), "_") + "_$mtime.jpg"
        val wanted = remote.associateBy { localName(it.name, it.mtime) }
        var added = 0
        withContext(Dispatchers.IO) {
            dir.mkdirs()
            dir.listFiles().orEmpty().filter { it.name.startsWith("sync_") && it.name !in wanted }.forEach { it.delete() }
            for ((local, photo) in wanted) {
                val f = File(dir, local)
                if (f.isFile) continue
                val bytes = hub.photo(photo.name) ?: continue
                val tmp = File(dir, "$local.part")
                tmp.writeBytes(bytes)
                tmp.renameTo(f)
                added++
            }
        }
        if (added > 0) GadgetState.log("synced $added new photo${if (added == 1) "" else "s"} from the Latitude")
        return added
    }

    // -- Internet ---------------------------------------------------------------------------

    private val probe = OkHttpClient.Builder().callTimeout(8, TimeUnit.SECONDS).build()

    private suspend fun internet() {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        // Android's own validation answers fast when it changes; the probe keeps it honest
        // (LineageOS may not validate at all).
        cm?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                GadgetState.setInternet(false)
            }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) GadgetState.setInternet(true)
            }
        })
        var fails = 0
        while (true) {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    probe.newCall(Request.Builder().url("https://www.gstatic.com/generate_204").head().build()).execute()
                        .use { it.code == 204 || it.isSuccessful }
                }.getOrDefault(false)
            }
            // Two misses in a row before calling it down, so one slow answer doesn't flap the banner.
            fails = if (ok) 0 else fails + 1
            val was = GadgetState.internet.value
            val now = if (ok) true else if (fails >= 2) false else was
            if (now != was) {
                GadgetState.setInternet(now)
                if (was != null) GadgetState.log(if (now == true) "internet is back" else "internet is down")
            }
            delay(if (now == false) 20_000 else 60_000)
        }
    }

    // -- Staying in front -------------------------------------------------------------------

    private suspend fun stayInFront() {
        var awaySince = 0L
        while (true) {
            delay(15_000)
            if (MainActivity.visible || !Prefs(context).returnToFront) {
                awaySince = 0L
                continue
            }
            val now = System.currentTimeMillis()
            if (awaySince == 0L) awaySince = now
            if (now - awaySince >= 60_000) {
                GadgetState.log("back to the front after a minute away")
                awaySince = 0L
                toFront()
            }
        }
    }

    // -- Leave-by ---------------------------------------------------------------------------

    private suspend fun leaveBy() {
        val nudged = HashSet<String>()
        var computedAt = 0L
        var computedFor: Any? = null
        while (true) {
            val day = agenda.value.value
            val now = System.currentTimeMillis()
            if (day != computedFor || now - computedAt > 20 * 60_000L) {
                computedFor = day
                computedAt = now
                GadgetState.setLeaveBy(computeLeaveBy(day))
            }
            val cal = Calendar.getInstance()
            val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
            for ((key, leaveMin) in GadgetState.leaveBy.value) {
                if (nowMin in leaveMin..leaveMin + 3 && nudged.add("${java.time.LocalDate.now()} $key")) {
                    val title = key.substringBefore('|')
                    GadgetState.log("time to leave for an event")
                    GadgetState.react(Reaction.NUDGE)
                    GadgetState.say("Time to leave for $title! 🚗", 600)
                    if (!isQuiet()) chime()
                    toFront()
                }
            }
            delay(30_000)
        }
    }

    private suspend fun computeLeaveBy(day: com.rusty.museecho.integrations.AgendaDay): Map<String, Int> {
        if (!BlobBrain.isToday(day.date, java.time.LocalDate.now())) return emptyMap()
        val p = Prefs(context)
        val home = p.weatherLat to p.weatherLon
        val out = LinkedHashMap<String, Int>()
        for (item in day.items) {
            if (item.location.isBlank()) continue
            val start = BlobBrain.minutesOfDay(item.time) ?: continue
            val dest = travel.geocode(item.location) ?: continue
            val drive = travel.driveMinutes(home, dest) ?: continue
            out["${item.title}|${item.time}"] = start - Travel.leadMinutes(drive)
        }
        return out
    }

    // -- What's new -------------------------------------------------------------------------

    private fun whatsNew() {
        val seen = prefs.getInt("last_version_seen", 0)
        prefs.edit().putInt("last_version_seen", BuildConfig.VERSION_CODE).apply()
        // First run after a fresh install: nothing to compare against, so stay quiet.
        if (seen == 0 || seen >= BuildConfig.VERSION_CODE || BuildConfig.WHATS_NEW.isBlank()) return
        GadgetState.show(Screen.Text("What's new in ${BuildConfig.VERSION_NAME}", BuildConfig.WHATS_NEW))
    }
}
