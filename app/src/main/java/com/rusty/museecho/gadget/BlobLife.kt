package com.rusty.museecho.gadget

import android.content.Context
import com.rusty.museecho.integrations.Hub
import java.util.Calendar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** Things in the blob's room, unlocked over the weeks and by what happens. */
enum class Decor(val label: String, val emoji: String) {
    WINDOW("a window", "🪟"), RUG("a rug", "🟪"), PLANT("a plant", "🪴"), LAMP("a lamp", "💡"),
    BOOKSHELF("a bookshelf", "📚"), STAR_POSTER("a star poster", "⭐"), FAIRY_LIGHTS("fairy lights", "✨"),
    MOVIE_POSTER("a movie poster", "🎬"), UMBRELLA_STAND("an umbrella stand", "☂️"), SNOW_GLOBE("a snow globe", "❄️"),
    PUMPKIN("a pumpkin", "🎃"), PHOTO_FRAME("a photo on the wall", "🖼️"), RECORD_PLAYER("a record player", "🎶"),
    TROPHY("a little trophy", "🏆"),
}

/** Pure helpers (unit-tested). */
object LifeMath {
    /**
     * Sunrise and sunset in minutes after local midnight (NOAA's simple formula, good to a few
     * minutes). Null in polar day / night.
     */
    fun sunTimes(lat: Double, lon: Double, date: java.time.LocalDate, zone: java.time.ZoneId): Pair<Int, Int>? {
        val n = date.dayOfYear
        val g = 2 * PI / 365 * (n - 1)
        val eqTime = 229.18 * (0.000075 + 0.001868 * cos(g) - 0.032077 * sin(g) - 0.014615 * cos(2 * g) - 0.040849 * sin(2 * g))
        val decl = 0.006918 - 0.399912 * cos(g) + 0.070257 * sin(g) - 0.006758 * cos(2 * g) + 0.000907 * sin(2 * g) -
            0.002697 * cos(3 * g) + 0.00148 * sin(3 * g)
        val latR = Math.toRadians(lat)
        val c = cos(Math.toRadians(90.833)) / (cos(latR) * cos(decl)) - tan(latR) * tan(decl)
        if (c < -1 || c > 1) return null
        val ha = Math.toDegrees(acos(c))
        val offsetMin = zone.rules.getOffset(date.atStartOfDay(zone).toInstant().plusSeconds(12 * 3600)).totalSeconds / 60.0
        val noon = 720 - 4 * lon - eqTime + offsetMin
        return (noon - 4 * ha).toInt() to (noon + 4 * ha).toInt()
    }

    /**
     * The warm evening tint, 0..1: fades in over the hour around sunset, full through the
     * night, and fades out after sunrise.
     */
    fun warmth(nowMin: Int, sunrise: Int, sunset: Int): Float = when {
        nowMin in (sunset - 30) until (sunset + 60) -> (nowMin - (sunset - 30)) / 90f
        nowMin >= sunset + 60 || nowMin < sunrise -> 1f
        nowMin in sunrise until (sunrise + 30) -> 1f - (nowMin - sunrise) / 30f
        else -> 0f
    }.coerceIn(0f, 1f)

    /**
     * The usual lights-off time from recent nights (minutes after noon, so midnight doesn't wrap),
     * once there are at least five; the median, so one late night doesn't move it.
     */
    fun usualBedtime(afterNoon: List<Int>): Int? {
        if (afterNoon.size < 5) return null
        val s = afterNoon.sorted()
        return s[s.size / 2]
    }

    /** Minutes after noon for a clock time (10 pm is 600, 1 am is 780). */
    fun afterNoon(hour: Int, minute: Int): Int = ((hour + 12) % 24) * 60 + minute

    fun clock(afterNoon: Int): String {
        val m = (afterNoon + 12 * 60) % (24 * 60)
        val h = m / 60
        return "%d:%02d %s".format(if (h % 12 == 0) 12 else h % 12, m % 60, if (h < 12) "am" else "pm")
    }

    /** Which room items are due after [days] of the blob having a room. */
    fun dueByAge(days: Int): List<Decor> = buildList {
        add(Decor.WINDOW); add(Decor.RUG)
        if (days >= 2) add(Decor.PLANT)
        if (days >= 4) add(Decor.LAMP)
        if (days >= 7) add(Decor.BOOKSHELF)
        if (days >= 10) add(Decor.STAR_POSTER)
        if (days >= 21) add(Decor.FAIRY_LIGHTS)
    }

    val FRIEND_CHATS = listOf(
        listOf(true to "Oh hi! 👋", false to "Hiii! I was in the neighbourhood.", true to "Want to see my room?", false to "Ooh, cozy! 🏠"),
        listOf(false to "Knock knock!", true to "Who's there?", false to "Blob.", true to "Blob who?", false to "Blob-solutely happy to see you! 😄"),
        listOf(false to "I brought snacks 🍪", true to "You're the best!", false to "Don't tell the humans.", true to "🤫"),
        listOf(true to "Guess what I did today?", false to "Napped?", true to "...yes. 😴", false to "Same!"),
        listOf(false to "Is it just me or is it bouncy in here?", true to "It's always bouncy here!", false to "Wheee! 🎈"),
        listOf(true to "Look, I can do a hop!", false to "I can do two!", true to "Show-off. 😝"),
    )
}

/**
 * The blob's ongoing life beyond single reactions: its room, a friend who visits, keeping an
 * eye on laptop use, the weekly homelab recap, warm evenings and bedtime. Each loop is cheap
 * and checks its own switch in Prefs.
 */
class BlobLife(
    private val context: Context,
    private val scope: CoroutineScope,
    private val hub: Hub,
    private val isQuiet: () -> Boolean,
    private val quietAsk: suspend (String) -> String?,
    private val diaryCount: () -> Int,
    private val framePhotos: () -> Int,
) {
    private val prefs = context.getSharedPreferences("museecho", Context.MODE_PRIVATE)

    fun start() {
        loadRoom()
        GadgetState.setWeekRecap(prefs.getString("week_recap", null)?.let { it to prefs.getLong("week_recap_ms", 0L) })
        scope.launch { roomLoop() }
        scope.launch { friendLoop() }
        scope.launch { laptopLoop() }
        scope.launch { weekRecapLoop() }
        scope.launch { eveningLoop() }
    }

    private fun idle() = GadgetState.talk.value.let { it == Talk.IDLE || it == Talk.REPLIED } && !GadgetState.speaking.value &&
        !GadgetState.ringing.value && GadgetState.screen.value is Screen.Idle && !GadgetState.asleep.value

    private fun hour() = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

    // -- The room -----------------------------------------------------------------------------

    private fun owned(): Set<String> = prefs.getString("room_decor", "")!!.split(',').filter { it.isNotEmpty() }.toSet()

    fun loadRoom() {
        GadgetState.setRoom(if (Prefs(context).blobRoom) owned() else emptySet())
    }

    /** Add [d] to the room (once), with a little show if anyone's around. */
    fun unlock(d: Decor, why: String = "") {
        val have = owned()
        if (d.name in have) return
        prefs.edit().putString("room_decor", (have + d.name).joinToString(",")).apply()
        loadRoom()
        GadgetState.log("room: got ${d.name}")
        DayLog.event("got ${d.label} for its room$why", "room")
        if (Prefs(context).blobRoom && !isQuiet() && !GadgetState.asleep.value) {
            GadgetState.react(Reaction.HOP)
            GadgetState.say("New for my room: ${d.label}! ${d.emoji}", 20)
        }
    }

    private suspend fun roomLoop() {
        if (prefs.getLong("room_since", 0L) == 0L) prefs.edit().putLong("room_since", System.currentTimeMillis()).apply()
        delay(90_000)
        while (true) {
            if (!isQuiet()) {
                val days = ((System.currentTimeMillis() - prefs.getLong("room_since", 0L)) / 86_400_000L).toInt()
                // One new thing at a time, so each gets its moment.
                val next = LifeMath.dueByAge(days).firstOrNull { it.name !in owned() }
                    ?: eventDecor().firstOrNull { it.name !in owned() }
                next?.let { unlock(it) }
            }
            delay(20 * 60_000L)
        }
    }

    /** Items earned by what's been happening. */
    private fun eventDecor(): List<Decor> = buildList {
        val f = DayLog.today()
        val y = DayLog.yesterday
        val month = Calendar.getInstance().get(Calendar.MONTH)
        if (f.tvMinutes >= 60 || y.tvMinutes >= 60) add(Decor.MOVIE_POSTER)
        val w = GadgetState.weather.value?.summary.orEmpty().lowercase()
        if ("rain" in w || "shower" in w || "drizzle" in w || "thunder" in w) add(Decor.UMBRELLA_STAND)
        if (month == Calendar.DECEMBER) add(Decor.SNOW_GLOBE)
        if (month == Calendar.OCTOBER) add(Decor.PUMPKIN)
        if (framePhotos() > 0) add(Decor.PHOTO_FRAME)
        if (f.musicMinutes >= 30 || y.musicMinutes >= 30) add(Decor.RECORD_PLAYER)
        if (diaryCount() >= 7) add(Decor.TROPHY)
    }

    // -- The friend ---------------------------------------------------------------------------

    private var friendJob: kotlinx.coroutines.Job? = null

    /** A visit now: "chat", "tag" or "nap" (null picks one for the time of day). */
    fun visit(activity: String? = null, minutes: Int? = null): String {
        if (GadgetState.friend.value != null) return "already visiting"
        val act = activity?.lowercase()?.takeIf { it in setOf("chat", "tag", "nap") }
            ?: if (hour() >= 19 || hour() < 7) "nap" else listOf("chat", "chat", "tag").random()
        val len = (minutes?.coerceIn(1, 30) ?: when (act) { "nap" -> 5; "tag" -> 1; else -> 2 }) * 60_000L
        val now = System.currentTimeMillis()
        val colors = listOf(0xFFFF9EC7, 0xFFFFC266, 0xFF7FE0A8, 0xFF9AB8FF, 0xFFFF8A65)
        val v = FriendVisit(act, now, now + len, colors.random())
        prefs.edit().putLong("friend_last", now).apply()
        DayLog.friendVisited()
        GadgetState.log("friend visiting ($act)")
        friendJob = scope.launch {
            GadgetState.setFriend(v)
            try {
                delay(1_800) // walking in
                when (act) {
                    "chat" -> {
                        val talk = LifeMath.FRIEND_CHATS.random()
                        GadgetState.react(Reaction.WAVE)
                        for ((mine, line) in talk) {
                            if (mine) GadgetState.say(line, 4) else GadgetState.setFriendSay(line)
                            delay(4_200)
                            if (!mine) GadgetState.setFriendSay(null)
                        }
                        GadgetState.setMood(Mood.HAPPY, 30)
                        delay((v.untilMs - System.currentTimeMillis() - 2_500).coerceAtLeast(0))
                        GadgetState.setFriendSay("Bye! 👋")
                        GadgetState.react(Reaction.WAVE)
                        delay(2_000)
                    }
                    "tag" -> {
                        GadgetState.setFriendSay("Tag, you're it! 🏃")
                        delay(2_000)
                        GadgetState.setFriendSay(null)
                        while (System.currentTimeMillis() < v.untilMs - 3_000) {
                            GadgetState.react(listOf(Reaction.WANDER, Reaction.HOP, Reaction.WANDER).random())
                            delay(Reaction.WANDER.ms)
                        }
                        GadgetState.setMood(Mood.HAPPY, 30)
                        GadgetState.say("Phew! 😅", 5)
                        GadgetState.setFriendSay("Again tomorrow! 👋")
                        delay(2_500)
                    }
                    else -> {
                        GadgetState.setFriendSay("Mind if I nap here? 😴")
                        delay(3_000)
                        GadgetState.setFriendSay(null)
                        GadgetState.setMood(Mood.SLEEPY, ((v.untilMs - System.currentTimeMillis()) / 1000).toInt().coerceAtLeast(5))
                        delay((v.untilMs - System.currentTimeMillis()).coerceAtLeast(0))
                        GadgetState.setMood(Mood.NEUTRAL, 0)
                        GadgetState.react(Reaction.STRETCH)
                        GadgetState.setFriendSay("Thanks for the nap! 👋")
                        delay(2_500)
                    }
                }
            } finally {
                GadgetState.setFriendSay(null)
                GadgetState.setFriend(null)
            }
        }
        return "visiting ($act)"
    }

    fun endVisit() {
        friendJob?.cancel()
    }

    private suspend fun friendLoop() {
        while (true) {
            delay((25 + (Math.random() * 30).toInt()) * 60_000L)
            if (!Prefs(context).blobFriend || isQuiet() || !idle() || hour() !in 9..21) continue
            if (System.currentTimeMillis() - prefs.getLong("friend_last", 0L) < 3 * 3_600_000L) continue
            if (System.currentTimeMillis() - GadgetState.lastTouch < 60_000) continue
            if (Math.random() < 0.35) visit()
        }
    }

    // -- Laptop time ---------------------------------------------------------------------------

    private suspend fun laptopLoop() {
        var nudgedStreak = false
        var lateNudgedFor = ""
        var lastAlbum = ""
        while (true) {
            delay(60_000)
            val st = GadgetState.laptopStatus.value ?: continue
            if (st.playing && st.musicVibe.isNotEmpty()) DayLog.musicMinute()
            // A new album starting: the blob spins a little record.
            if (st.playing && st.album.isNotBlank() && st.album != lastAlbum) {
                if (lastAlbum.isNotEmpty()) GadgetState.vinylAt = System.currentTimeMillis()
                lastAlbum = st.album
            }
            if (!Prefs(context).laptopBreak) continue
            if (st.activeMinutes < 5) nudgedStreak = false
            val h = hour()
            val tonight = java.time.LocalDate.now().minusDays(if (h < 12) 1 else 0).toString()
            when {
                // Late at night and still going: wind down (once a night; quiet hours too, silently).
                (h >= 23 || h < 4) && st.activeMinutes >= 15 && st.idleSeconds < 180 && lateNudgedFor != tonight -> {
                    lateNudgedFor = tonight
                    GadgetState.react(Reaction.YAWN)
                    GadgetState.hold(HeldItem.NOTE, "🌙 It's late, time to wind down?", 10)
                    GadgetState.say("It's getting late… maybe wrap up soon? 🌙", 30)
                    DayLog.event("told you it was late while you were on the laptop", "laptop")
                }
                st.activeMinutes >= 120 && !nudgedStreak && !isQuiet() -> {
                    nudgedStreak = true
                    GadgetState.react(Reaction.NUDGE)
                    GadgetState.hold(HeldItem.NOTE, "💻 ${st.activeMinutes / 60} h at the laptop, take a break?", 15)
                    GadgetState.say("You've been at it a while! Stretch? 🙆", 30)
                    DayLog.event("suggested a break after ${st.activeMinutes / 60} hours on the laptop", "laptop")
                }
            }
        }
    }

    // -- Weekly homelab recap ------------------------------------------------------------------

    private suspend fun weekRecapLoop() {
        delay(3 * 60_000L)
        while (true) {
            val now = java.time.LocalDateTime.now()
            val last = prefs.getLong("week_recap_ms", 0L)
            // Sunday evenings, once.
            if (now.dayOfWeek == java.time.DayOfWeek.SUNDAY && now.hour >= 18 && System.currentTimeMillis() - last > 3 * 86_400_000L) {
                runWeekRecap()
            }
            delay(30 * 60_000L)
        }
    }

    /** Fetch the week's numbers from the echo-hub and have Pixel write them up. */
    suspend fun runWeekRecap(): Boolean {
        val facts = hub.weekFacts() ?: return false
        val text = quietAsk(
            "Write a short, friendly recap of this week on my homelab server (the Dell Latitude), 3 to 5 sentences, " +
                "like a little weekly news bulletin: mention anything that went wrong or stood out, and say so if " +
                "it was a quiet week. Here are the real numbers (uptime is percent over 7 days; speeds are Mbps):\n$facts\n" +
                "Reply with only the recap text: no title, no commands, and don't change anything on any device.",
        ) ?: return false
        val at = System.currentTimeMillis()
        prefs.edit().putString("week_recap", text.take(1500)).putLong("week_recap_ms", at).apply()
        GadgetState.setWeekRecap(text.take(1500) to at)
        GadgetState.log("weekly homelab recap written")
        return true
    }

    // -- Evenings ------------------------------------------------------------------------------

    private suspend fun eveningLoop() {
        var hintedFor = ""
        while (true) {
            val p = Prefs(context)
            val now = java.time.LocalDateTime.now()
            val nowMin = now.hour * 60 + now.minute
            val sun = LifeMath.sunTimes(p.weatherLat, p.weatherLon, now.toLocalDate(), java.time.ZoneId.systemDefault())
            GadgetState.setWarmth(if (p.warmEvenings && sun != null) LifeMath.warmth(nowMin, sun.first, sun.second) else 0f)
            // Up well past the usual lights-off time, with the lights on and someone around.
            val usual = LifeMath.usualBedtime(bedtimes())
            val tonight = now.toLocalDate().minusDays(if (now.hour < 12) 1 else 0).toString()
            val sinceNoon = LifeMath.afterNoon(now.hour, now.minute)
            if (p.bedtimeHint && usual != null && hintedFor != tonight && sinceNoon > usual + 60 && sinceNoon < 17 * 60 &&
                GadgetState.roomDark.value == false && System.currentTimeMillis() - GadgetState.lastTouch < 15 * 60_000L && idle()
            ) {
                hintedFor = tonight
                GadgetState.react(Reaction.YAWN)
                GadgetState.say("It's past your usual bedtime (about ${LifeMath.clock(usual)}) 🌙", 60)
            }
            delay(60_000)
        }
    }

    private fun bedtimes(): List<Int> = prefs.getString("bedtimes", "")!!.split(',').mapNotNull { it.toIntOrNull() }

    /** The room went dark: if it's evening or night, remember the time (one per night, the first). */
    fun lightsOff() {
        val now = java.time.LocalDateTime.now()
        if (now.hour in 5..19) return
        val night = now.toLocalDate().minusDays(if (now.hour < 12) 1 else 0).toString()
        if (prefs.getString("bedtime_night", "") == night) return
        val list = (bedtimes() + LifeMath.afterNoon(now.hour, now.minute)).takeLast(14)
        prefs.edit().putString("bedtimes", list.joinToString(",")).putString("bedtime_night", night).apply()
    }
}
