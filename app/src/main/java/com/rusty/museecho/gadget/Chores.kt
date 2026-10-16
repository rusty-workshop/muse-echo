@file:OptIn(kotlinx.coroutines.FlowPreview::class)

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
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
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
    private val laptop: com.rusty.museecho.integrations.Laptop,
    private val applySettings: (kotlinx.serialization.json.JsonObject) -> List<String>,
    private val say: (String) -> Unit,
    private val chime: () -> Unit,
    private val toFront: () -> Unit,
    private val isQuiet: () -> Boolean,
) {
    private val travel = Travel(context)
    private val prefs = context.getSharedPreferences("museecho", Context.MODE_PRIVATE)
    private val startedAt = System.currentTimeMillis()
    private val PREVIEW_MS = 5_000L

    fun start() {
        scope.launch { heartbeat() }
        scope.launch { timers.items.collect { postState() } }
        // The blob's look for the watch: whenever it changes (settled for a moment), and once a minute.
        scope.launch {
            kotlinx.coroutines.flow.merge(GadgetState.mood, GadgetState.bubble, GadgetState.held, GadgetState.style,
                GadgetState.friend, GadgetState.friendSay, GadgetState.quiet, GadgetState.tuckedIn, GadgetState.talk)
                .debounce(1_500).collect { postState() }
        }
        scope.launch {
            while (true) {
                delay(60_000)
                postState()
            }
        }
        scope.launch { inbox() }
        scope.launch { backups() }
        scope.launch { photoSync() }
        scope.launch { internet() }
        scope.launch { stayInFront() }
        scope.launch { leaveBy() }
        scope.launch { laptopStatus() }
        scope.launch { laptopNotifications() }
        scope.launch { settingsSnapshot() }
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
                GadgetService.instance?.healthReport()?.let { put("health", it) }
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
            put("blob", blobState(now))
        })
    }

    /** Roughly how the blob looks right now, for the watch (it draws its own little copy). */
    private fun blobState(now: Long) = buildJsonObject {
        val pixel = GadgetState.mood.value?.takeIf { it.untilMs > now }?.mood
        val mood = when {
            pixel != null -> pixel
            GadgetState.tuckedIn.value || GadgetState.asleep.value -> Mood.SLEEPY
            GadgetState.quiet.value -> Mood.DROWSY
            GadgetState.talk.value == Talk.LISTENING -> Mood.CURIOUS
            GadgetState.server.value?.overall == "down" -> Mood.WORRIED
            GadgetState.nowPlaying.value != null || GadgetState.laptopStatus.value?.playing == true -> Mood.HAPPY
            else -> Mood.NEUTRAL
        }
        put("mood", mood.name.lowercase())
        GadgetState.style.value.takeIf { it.active(now) }?.let { st ->
            st.color?.let { put("color", "#%06X".format(it and 0xFFFFFF)) }
            st.accessory?.takeIf { it != Accessory.NONE }?.let { put("accessory", it.name.lowercase()) }
        }
        GadgetState.bubble.value?.takeIf { it.untilMs > now }?.let { put("bubble", it.text) }
        GadgetState.held.value?.takeIf { it.untilMs > now }?.let { h -> put("holding", h.item.name.lowercase()); if (h.text.isNotEmpty()) put("label", h.text) }
        put("doing", GadgetService.instance?.life?.friends?.doingLine() ?: "")
        GadgetState.laptopStatus.value?.let { l ->
            put("laptop", buildJsonObject { put("playing", l.playing); put("track", l.track); put("artist", l.artist); l.batteryPercent?.let { put("battery", it) } })
        }
        GadgetState.trip.value?.let { t ->
            put("away_at", GadgetState.friends.value.friends.firstOrNull { it.id == t.friendId }?.shownName ?: "a friend's")
            put("away_overnight", t.overnight)
        }
        if (GadgetState.sniffles.value) put("sniffles", true)
        if (GadgetState.asleep.value || GadgetState.tuckedIn.value) put("asleep", true)
        GadgetState.vote.value?.let { (acts, _) ->
            put("vote", buildJsonArray { acts.forEach { (id, name) -> add(buildJsonObject { put("id", id); put("name", name) }) } })
        }
        GadgetState.friend.value?.let { v ->
            put("visitors", buildJsonArray {
                v.friendIds.forEach { id ->
                    val f = GadgetState.friends.value.friends.firstOrNull { it.id == id } ?: GadgetState.guest.value?.takeIf { it.id == id } ?: return@forEach
                    add(buildJsonObject { put("id", f.id); put("name", f.shownName); put("color", "#%06X".format(f.color and 0xFFFFFF)); put("scale", f.scale)
                        GadgetState.friendSay.value[f.id]?.let { put("says", it) } })
                }
            })
            put("friend", v.activity)
            val f = v.friendIds.firstOrNull()?.let { id -> GadgetState.friends.value.friends.firstOrNull { it.id == id } }
            put("friend_id", f?.id ?: "")
            put("friend_name", f?.shownName ?: "Friend")
            put("friend_color", "#%06X".format((f?.color ?: v.color) and 0xFFFFFF))
            GadgetState.friendSay.value[f?.id]?.let { put("friend_says", it) }
        }
    }

    // -- The watch's inbox ------------------------------------------------------------------

    private suspend fun inbox() {
        while (true) {
            // Quicker while someone's watching the screen from the phone (taps should land promptly).
            val now0 = System.currentTimeMillis()
            delay(if (now0 < GadgetState.screenWantedUntil || now0 < GadgetState.fastInboxUntil) 500 else if (isQuiet()) 20_000 else 4_000)
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
                    "screen" -> {
                        // The laptop settings page is watching: send a picture every 2 s for a little while.
                        val wasIdle = GadgetState.screenWantedUntil < System.currentTimeMillis()
                        GadgetState.screenWantedUntil = System.currentTimeMillis() + 10_000
                        if ((item["fast"] as? JsonPrimitive)?.booleanOrNull == true) GadgetState.screenFastUntil = System.currentTimeMillis() + 10_000
                        if (wasIdle) scope.launch { streamScreen() }
                    }
                    "blob" -> {
                        val kind = (item["kind"] as? JsonPrimitive)?.contentOrNull ?: continue
                        val value = (item["value"] as? JsonPrimitive)?.contentOrNull ?: continue
                        playground(kind, value)
                    }
                    "settings" -> {
                        val changes = item["changes"] as? kotlinx.serialization.json.JsonObject ?: continue
                        applySettings(changes)
                        postSettings()
                    }
                    "poke" -> if ((item["target"] as? JsonPrimitive)?.contentOrNull == "friend") {
                        // The watch tapped the friend instead.
                        GadgetService.instance?.life?.friends?.tapped((item["friend_id"] as? JsonPrimitive)?.contentOrNull)
                        postState()
                    } else {
                        // A tap on the watch: the blob hops and says hi (a little chirp, not overnight).
                        GadgetState.log("poked from the ${(item["from"] as? JsonPrimitive)?.contentOrNull ?: "watch"}")
                        DayLog.poked()
                        GadgetState.react(if (Math.random() < 0.5) Reaction.HOP else Reaction.WINK)
                        if (!isQuiet()) {
                            GadgetState.chirp(BlobSound.TAP)
                            GadgetState.say(if ((item["from"] as? JsonPrimitive)?.contentOrNull == "phone") "👋 Hi from your phone!" else "👋 Hi from your wrist!", 6)
                        }
                        postState()
                    }
                    // A tap or swipe from the phone's live view (x, y as fractions of the screen).
                    "touch" -> {
                        fun f(k: String) = (item[k] as? JsonPrimitive)?.floatOrNull?.coerceIn(0f, 1f)
                        val x = f("x") ?: continue
                        val y = f("y") ?: continue
                        val kind = (item["kind"] as? JsonPrimitive)?.contentOrNull ?: "tap"
                        MainActivity.remoteTouch(kind, x, y, f("x2") ?: x, f("y2") ?: y)
                    }
                    // The phone's remote is open: quick checks for a while.
                    "hello" -> GadgetState.fastInboxUntil = System.currentTimeMillis() + 15_000
                    // A button on the phone's remote: the TV (Roku keys) or the laptop (media keys and shortcuts).
                    "remote" -> {
                        GadgetState.fastInboxUntil = System.currentTimeMillis() + 15_000
                        val key = (item["key"] as? JsonPrimitive)?.contentOrNull ?: continue
                        val svc = GadgetService.instance ?: continue
                        when ((item["target"] as? JsonPrimitive)?.contentOrNull) {
                            "tv" -> scope.launch { svc.tvKey(key) }
                            "laptop" -> if (key.startsWith("shortcut:")) scope.launch { svc.laptopShortcut(key.removePrefix("shortcut:")) }
                                else svc.laptopMedia(key)
                        }
                    }
                    // Shared from the phone: a card on the Echo.
                    "card" -> {
                        val text = (item["text"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: continue
                        val title = (item["title"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: "📱 From your phone"
                        GadgetState.lastTouch = System.currentTimeMillis()
                        GadgetState.setAsleep(false)
                        GadgetState.show(Screen.Text(title, text))
                        toFront()
                    }
                    // Typed on the phone into whatever box is open on the Echo.
                    "type" -> {
                        val text = (item["text"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                        val enter = (item["enter"] as? JsonPrimitive)?.booleanOrNull == true
                        MainActivity.remoteType(text, enter)
                    }
                    "invite" -> {
                        val name = (item["name"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                        val act = (item["activity"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                        val r = GadgetService.instance?.life?.visit(act, null, name)
                        GadgetState.log("invite from the phone: $r")
                        postState()
                    }
                    "game" -> {
                        val outcome = (item["outcome"] as? JsonPrimitive)?.intOrNull ?: continue
                        val game = (item["game"] as? JsonPrimitive)?.contentOrNull ?: "a game"
                        GadgetService.instance?.life?.friends?.phoneGame(game, outcome)
                        postState()
                    }
                    "vote" -> {
                        val id = (item["id"] as? JsonPrimitive)?.contentOrNull ?: continue
                        if (GadgetState.vote.value?.first?.any { it.first == id } == true) {
                            GadgetState.voted = id
                            GadgetState.setVote(null)
                        }
                    }
                    "photo" -> {
                        val id = (item["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { Regex("[0-9a-f]{16}").matches(it) } ?: continue
                        val dest = (item["dest"] as? JsonPrimitive)?.contentOrNull ?: "frame"
                        val caption = (item["caption"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                        scope.launch { phonePhoto(id, dest, caption) }
                    }
                    // The fun things from the phone app.
                    "fortune" -> { toFront(); GadgetService.instance?.life?.fun_?.fortune(); postState() }
                    "rustyletter" -> (item["text"] as? JsonPrimitive)?.contentOrNull?.let { GadgetService.instance?.life?.fun_?.letterFromRusty(it) }
                    "settle" -> (item["choice"] as? JsonPrimitive)?.contentOrNull?.let { c ->
                        if (GadgetService.instance?.life?.friends?.settleDebate(c) == true) toFront()
                    }
                    "mystery_guess" -> (item["friend_id"] as? JsonPrimitive)?.contentOrNull?.let { id ->
                        if (GadgetService.instance?.life?.friends?.guessMystery(id) != null) toFront()
                    }
                    "pettrick" -> (item["trick"] as? JsonPrimitive)?.contentOrNull?.let { t ->
                        toFront()
                        GadgetState.log("pet trick from the phone: " + GadgetService.instance?.life?.fun_?.practise(t))
                    }
                    "trivia" -> GadgetService.instance?.life?.fun_?.startTrivia((item["theme"] as? JsonPrimitive)?.contentOrNull.orEmpty())
                    "outfit" -> (item["name"] as? JsonPrimitive)?.contentOrNull?.let { n ->
                        val fl = GadgetService.instance?.life?.friends
                        if (fl != null && (n == "NONE" || n in fl.store.data.wardrobe)) { toFront(); fl.wearToday(n.takeIf { it != "NONE" }) }
                    }
                    "friends_add" -> {
                        val n = (item["count"] as? JsonPrimitive)?.intOrNull ?: 1
                        val added = GadgetService.instance?.life?.friends?.addFriends(n) ?: 0
                        GadgetState.log("new friends from the laptop: $added")
                    }
                    "gift" -> {
                        val kind = (item["kind"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                        val value = (item["value"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                        val note = (item["note"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                        toFront()
                        GadgetService.instance?.life?.friends?.giftFromRusty(kind, value, note)
                    }
                    "export" -> scope.launch {
                        val ok = hub.postExport(EchoBackup.collect(context, BuildConfig.VERSION_NAME, shareable = true))
                        GadgetState.log("world export for the laptop: ${if (ok) "sent" else "failed"}")
                    }
                    "import" -> scope.launch {
                        val bundle = hub.fetchImport() ?: return@launch
                        val n = runCatching { EchoBackup.apply(context, bundle, shareable = true) }.getOrElse {
                            GadgetState.log("world import failed: ${it.message}")
                            return@launch
                        }
                        GadgetState.log("world import: $n files; restarting")
                        GadgetState.show(Screen.Text("📦 Restored", "Puff's world was restored from a file. Restarting…"))
                        delay(2_000)
                        context.getSharedPreferences("museecho", Context.MODE_PRIVATE).edit().putLong("last_crash_relaunch", 0).commit()
                        ErrorLog.scheduleRelaunch(context)
                        android.os.Process.killProcess(android.os.Process.myPid())
                    }
                    "ask" -> (item["what"] as? JsonPrimitive)?.contentOrNull?.let { askNow(it) }
                    // A year (2026) or month (2026-10) in review, as a special Gazette.
                    "review" -> {
                        val period = (item["period"] as? JsonPrimitive)?.contentOrNull?.takeIf { Regex("""\d{4}(-\d{2})?""").matches(it) }
                            ?: java.time.YearMonth.now().toString()
                        GadgetService.instance?.life?.friends?.let { fl -> scope.launch { fl.reviewNow(period) } }
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

    /** A photo sent from Rusty's phone: into the frame's slideshow, or Puff's album. */
    private suspend fun phonePhoto(id: String, dest: String, caption: String) {
        val bytes = hub.phonePhoto(id) ?: return GadgetState.log("phone photo $id: couldn't fetch it")
        if (dest == "album") {
            GadgetService.instance?.life?.addPhonePhoto(bytes, caption.ifBlank { "From Rusty's phone" })
            GadgetState.say("Ooh, a photo from Rusty! It's in my album 📸", 8)
        } else {
            withContext(Dispatchers.IO) {
                frame.dir.mkdirs()
                val f = File(frame.dir, "phone_$id.jpg")
                val tmp = File(frame.dir, "phone_$id.jpg.part")
                tmp.writeBytes(bytes)
                tmp.renameTo(f)
            }
            GadgetState.say("A new photo for the slideshow, from your phone 🖼️", 8)
        }
        GadgetState.log("photo from the phone → $dest")
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

    // -- Laptop ------------------------------------------------------------------------------

    /** Battery and what's playing, every minute while the laptop answers (cleared when it's away). */
    private suspend fun laptopStatus() {
        while (true) {
            // Overnight too (for the late-night wind-down), just less often; quicker while music plays
            // so the blob's moves follow the song.
            val st = if (laptop.configured) laptop.status() else null
            GadgetState.setLaptopStatus(st)
            delay(when {
                isQuiet() -> 180_000L
                st?.playing == true -> 20_000L
                else -> 60_000L
            })
        }
    }

    /** The laptop's desktop notifications as quiet chips (apps can be muted in Setup). */
    private suspend fun laptopNotifications() {
        var after = -1
        var fails = 0
        while (true) {
            val p = Prefs(context)
            if (!laptop.configured || !p.laptopNotifications) {
                after = -1
                delay(30_000)
                continue
            }
            val r = laptop.notifications(after, if (after < 0) 0 else 50)
            if (r == null) {
                fails++
                delay(minOf(15_000L * fails, 120_000L))
                continue
            }
            fails = 0
            val (latest, items) = r
            if (after >= 0) {
                for (n in items) {
                    val app = n.app.ifBlank { "Laptop" }
                    if (app !in p.laptopKnownApps) p.laptopKnownApps = p.laptopKnownApps + app
                    if (app in p.laptopMutedApps || isQuiet() || GadgetState.guestMode()) continue
                    GadgetState.addLaptopNote(n)
                }
            }
            after = latest
        }
    }

    // -- Settings from the laptop (echo-hub's /settings page) -------------------------------

    private suspend fun settingsSnapshot() {
        delay(10_000)
        while (true) {
            postSettings()
            delay(5 * 60_000L)
        }
    }

    /** What the settings page shows: current values plus the choices for each. */
    suspend fun postSettings() {
        val p = Prefs(context)
        hub.postSettings(buildJsonObject {
            put("clock_face", p.clockFace)
            put("frame_clock", p.frameClock)
            put("night_scene", p.nightScene)
            put("theme", p.theme)
            put("background", p.background)
            put("room_sense", p.roomSense)
            put("blob_name", p.blobName)
            put("still_until_ms", if (p.stillUntil == Long.MAX_VALUE) -1L else p.stillUntil)
            put("quiet_until_ms", if (p.quietUntil == Long.MAX_VALUE) -1L else p.quietUntil)
            put("blob_room", p.blobRoom)
            put("page_order", buildJsonArray { com.rusty.museecho.ui.HomePage.ordered(p.pageOrder).forEach { add(JsonPrimitive(it.name)) } })
            put("pages_hidden", buildJsonArray { p.pagesHidden.forEach { add(JsonPrimitive(it)) } })
            put("page_labels", buildJsonObject {
                com.rusty.museecho.ui.HomePage.entries.forEach { put(it.name, if (it == com.rusty.museecho.ui.HomePage.BLOB) GadgetState.BlobCalled() else it.label) }
            })
            put("blob_friend", p.blobFriend)
            put("day_night_look", p.dayNightLook)
            put("ambience", p.ambience)
            put("ui_sounds", p.uiSounds)
            put("weather_accents", p.weatherAccents)
            put("guest_wifi_ssid", p.guestWifiSsid)
            // Never the password itself: just whether there is one (the page leaves the box empty).
            put("guest_wifi_pass", "")
            put("guest_wifi_has_pass", p.guestWifiPass.isNotEmpty())
            put("guest_wifi_sec", p.guestWifiSec)
            put("user_name", p.userName)
            put("puff_sniffles", p.puffSniffles)
            put("puff_away_sleepovers", p.puffAwaySleepovers)
            put("puff_house_build", p.puffHouseBuild)
            put("laptop_break", p.laptopBreak)
            put("warm_evenings", p.warmEvenings)
            put("bedtime_hint", p.bedtimeHint)
            put("diary", p.diary)
            put("daily_thing", p.dailyThing)
            put("wellness_water", p.wellnessWater)
            put("wellness_stretch", p.wellnessStretch)
            put("wellness_eyes", p.wellnessEyes)
            put("wellness_start", p.wellnessStart)
            put("wellness_end", p.wellnessEnd)
            put("routines", buildJsonArray {
                routinesNow().forEach { r ->
                    add(buildJsonObject {
                        put("id", r.id); put("time", r.time); put("label", r.label); put("prompt", r.prompt); put("enabled", r.enabled)
                        put("days", buildJsonArray { r.days.forEach { add(JsonPrimitive(it)) } })
                    })
                }
            })
            put("frame_delay_min", p.frameDelayMin)
            put("blob_energy", p.blobEnergy)
            put("blob_sounds", p.blobSounds)
            put("speak_replies", p.speakReplies)
            put("follow_up", p.followUp)
            put("come_to_front", p.comeToFront)
            put("return_to_front", p.returnToFront)
            put("show_server_pill", p.showServerPill)
            put("auto_brightness", p.autoBrightness)
            put("laptop_notifications", p.laptopNotifications)
            put("wake_sensitivity", p.wakeSensitivity.name)
            put("guest_until_ms", GadgetState.guestUntil.value)
            put("quick_actions", buildJsonArray {
                quickActionsNow().forEach { a -> add(buildJsonObject { put("label", a.label); put("prompt", a.prompt) }) }
            })
            put("quick_sets", buildJsonObject {
                val sets = quickSetsNow()
                for ((name, list) in listOf("morning" to sets.morning, "afternoon" to sets.afternoon, "evening" to sets.evening)) {
                    put(name, buildJsonArray { list.forEach { a -> add(buildJsonObject { put("label", a.label); put("prompt", a.prompt) }) } })
                }
            })
            // Friendly names and a line about each, where the internal names don't say enough.
            put("choice_labels", buildJsonObject {
                put("wake_sensitivity", buildJsonObject {
                    WakeWord.Sensitivity.entries.forEach { put(it.name, it.label) }
                })
                put("theme", buildJsonObject { com.rusty.museecho.ui.AppTheme.entries.forEach { put(it.name, it.label) } })
                put("background", buildJsonObject { com.rusty.museecho.ui.AppBackground.entries.forEach { put(it.name, it.label) } })
            })
            put("choice_hints", buildJsonObject {
                put("wake_sensitivity", buildJsonObject {
                    put("LOW", "Needs a clear, close phrase it's very sure of (92%). Fewest false wake-ups.")
                    put("MEDIUM", "Hears you across the room; needs the whole phrase, clearly (85%). A good default.")
                    put("HIGH", "Wakes most easily: quiet speech and the last word alone (76%). Most false wake-ups.")
                })
            })
            put("choices", buildJsonObject {
                put("clock_face", buildJsonArray { com.rusty.museecho.ui.ClockFace.entries.forEach { add(JsonPrimitive(it.name)) } })
                put("frame_clock", buildJsonArray { com.rusty.museecho.ui.FrameClock.entries.forEach { add(JsonPrimitive(it.name)) } })
                put("wake_sensitivity", buildJsonArray { WakeWord.Sensitivity.entries.forEach { add(JsonPrimitive(it.name)) } })
                put("night_scene", buildJsonArray { com.rusty.museecho.ui.NightScene.entries.forEach { add(JsonPrimitive(it.name)) } })
                put("theme", buildJsonArray { com.rusty.museecho.ui.AppTheme.entries.forEach { add(JsonPrimitive(it.name)) } })
                put("background", buildJsonArray { com.rusty.museecho.ui.AppBackground.entries.forEach { add(JsonPrimitive(it.name)) } })
            })
            // Everything the blob playground can show.
            put("blob", buildJsonObject {
                fun list(vararg names: String) = buildJsonArray { names.forEach { add(JsonPrimitive(it)) } }
                put("reaction", buildJsonArray { Reaction.entries.forEach { add(JsonPrimitive(it.name)) } })
                put("mood", buildJsonArray { Mood.entries.forEach { add(JsonPrimitive(it.name)) } })
                put("accessory", buildJsonArray { Accessory.entries.filter { it != Accessory.NONE }.forEach { add(JsonPrimitive(it.name)) } })
                put("prop", buildJsonArray { HeldItem.entries.filter { it != HeldItem.NONE }.forEach { add(JsonPrimitive(it.name)) } })
                put("color", buildJsonArray { BlobStyles.COLORS.keys.forEach { add(JsonPrimitive(it)) } })
                put("weather", list("RAIN", "HEAVY_RAIN", "THUNDERSTORMS", "SNOW", "CLEAR", "FOG"))
                put("holiday", list("FIREWORKS", "DECEMBER_SNOW", "VALENTINE_HEARTS", "HALLOWEEN_BATS"))
                put("friend", buildJsonArray { (FriendsBrain.ACTIVITIES.filter { it != "treasure" } + listOf("treasure", "trip", "letter", "reply", "hangout", "comic", "dream", "gazette", "penpal", "book", "stickers", "review", "sniffles", "build", "fair", "parade", "souvenir", "rustyletter", "overnight", "collect", "card")).forEach { add(JsonPrimitive(it.uppercase())) } })
                put("state", list("THINKING", "SLEEP", "TUCKED_IN", "MUSIC", "HOURGLASS", "ALERT_GLOW", "BUSY_GEAR", "MUTED", "BUBBLE", "FRIEND"))
            })
            put("version", BuildConfig.VERSION_NAME)
        })
    }

    var askNow: (String) -> Unit = {}
    var quickActionsNow: () -> List<com.rusty.museecho.integrations.QuickAction> = { emptyList() }
    var routinesNow: () -> List<com.rusty.museecho.integrations.Routine> = { emptyList() }
    var quickSetsNow: () -> com.rusty.museecho.integrations.QuickActionSets = { com.rusty.museecho.integrations.QuickActionSets() }

    private suspend fun streamScreen() {
        while (System.currentTimeMillis() < GadgetState.screenWantedUntil) {
            val jpeg = MainActivity.capture()
            hub.postScreen(jpeg)
            // Once a second for the phone's view (you're touching through it), every 2 s for the laptop's.
            delay(if (System.currentTimeMillis() < GadgetState.screenFastUntil) 900 else 2_000)
        }
    }

    /** The blob playground on the settings page: anything the blob can do, for about five seconds. */
    private fun playground(kind: String, value: String) {
        GadgetState.lastTouch = System.currentTimeMillis()
        GadgetState.setAsleep(false)
        GadgetState.showFrame(false)
        when (kind) {
            "reaction" -> Reaction.entries.firstOrNull { it.name == value }?.let { GadgetState.react(it) }
            "mood" -> Mood.entries.firstOrNull { it.name == value }?.let { GadgetState.setMood(it, 5) }
            "state" -> when (value) {
                "BUBBLE" -> GadgetState.say("Hi there! 👋 This is a speech bubble.", 5)
                // A short real visit (a minute), since a friend can't pop in for just 5 seconds.
                "FRIEND" -> GadgetService.instance?.life?.visit("chat", 1)
                else -> GadgetState.preview(kind, value, PREVIEW_MS)
            }
            // A real (short) visit doing that: friends can't pop in for just 5 seconds.
            "friend" -> when (value.uppercase()) {
                // A letter from the closest friend now, and two friends hanging out (the next chat brings it up).
                "LETTER" -> GadgetService.instance?.life?.friends?.let { fl ->
                    fl.store.data.friends.filter { it.name.isNotBlank() }.maxByOrNull { it.closeness }?.let { f -> scope.launch { fl.writeLetter(f) } }
                }
                "HANGOUT" -> GadgetService.instance?.life?.friends?.hangoutNow()?.let { GadgetState.log("friends hung out: $it") }
                "COMIC" -> GadgetService.instance?.life?.friends?.let { fl -> scope.launch { fl.comicNow() } }
                "DREAM" -> GadgetService.instance?.life?.friends?.let { fl -> scope.launch { fl.dreamNow() } }
                "TREASURE" -> GadgetService.instance?.life?.friends?.let { fl -> scope.launch { fl.treasureNow() } }
                "TRIP" -> GadgetService.instance?.life?.friends?.let { fl -> fl.store.data.friends.filter { it.name.isNotBlank() }.randomOrNull()?.let { fl.trip(it, 1) } }
                "GAZETTE" -> GadgetService.instance?.life?.friends?.let { fl -> scope.launch { fl.gazetteNow() } }
                "PENPAL" -> GadgetService.instance?.life?.friends?.let { fl -> scope.launch { fl.penpalLetterNow() } }
                "BOOK" -> GadgetService.instance?.life?.friends?.let { fl -> scope.launch { fl.addBook(GadgetState.BlobCalled()) } }
                "STICKERS" -> GadgetService.instance?.life?.friends?.checkStickers()
                "SNIFFLES" -> GadgetService.instance?.life?.friends?.startSniffles(soonMin = 2)
                "BUILD" -> GadgetService.instance?.life?.friends?.buildNow()
                "RUSTYLETTER" -> GadgetService.instance?.life?.friends?.let { fl -> scope.launch { fl.writeToRusty() } }
                "OVERNIGHT" -> GadgetService.instance?.life?.friends?.let { fl ->
                    fl.store.data.friends.filter { it.name.isNotBlank() }.randomOrNull()?.let { fl.sleepOverAt(it, testMs = 3 * 60_000L) }
                }
                "COLLECT" -> GadgetService.instance?.life?.friends?.collect(listOf("Shells", "Leaves", "Stamps", "Stones").random(), "from the playground")
                "CARD" -> GadgetService.instance?.life?.friends?.store?.let { st ->
                    val today = java.time.LocalDate.now().toString()
                    if (st.data.cards.none { it.date == today }) st.update { it.copy(cards = it.cards + HolidayCard("Card Test", today,
                        "Just a test card, Rusty! 💛", GadgetState.BlobCalled())) }
                }
                "FAIR", "PARADE" -> GadgetState.previewFestival(value.uppercase(), System.currentTimeMillis() + 10 * 60_000L)
                "SOUVENIR" -> FriendsBrain.SOUVENIRS.random().let { (e, what) ->
                    GadgetState.setSouvenir(GadgetState.Souvenir(e, what, GadgetService.instance?.life?.friends?.store?.data?.friends?.firstOrNull { it.name.isNotBlank() }?.shownName ?: "a friend",
                        java.time.LocalDate.now().toString()))
                }
                "REVIEW" -> GadgetService.instance?.life?.friends?.let { fl -> scope.launch { fl.reviewNow(java.time.YearMonth.now().toString()) } }
                "REPLY" -> GadgetService.instance?.life?.friends?.let { fl ->
                    fl.store.data.letters.lastOrNull { !it.fromBlob }?.let { l -> scope.launch { fl.replyTo(l) } }
                }
                else -> GadgetService.instance?.life?.visit(value.lowercase(), if (value.uppercase() == "SLEEPOVER") 2 else 1)
            }
            else -> GadgetState.preview(kind, value, PREVIEW_MS)
        }
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
