package com.rusty.museecho.gadget

import kotlinx.serialization.json.put
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.Color

/**
 * The blob's friends: who they are (named by Pixel), when they visit and what they get up to,
 * their diaries, postcards and gifts. Each visit is a little script that moves the friends and
 * fills in [GadgetState.friendScene] for the Blob page to draw.
 */
class FriendLife(
    private val context: Context,
    private val scope: CoroutineScope,
    private val life: BlobLife,
    private val isQuiet: () -> Boolean,
    private val quietAsk: suspend (String) -> String?,
) {
    val store = FriendsStore(java.io.File(context.filesDir, "friends.json"))
    private val prefs = context.getSharedPreferences("museecho", Context.MODE_PRIVATE)
    private val rnd = java.util.Random()
    private var job: Job? = null
    /** Nobody's visited in days: the next visit is a big reunion. */
    private var missing = false

    fun start() {
        GadgetState.setFriends(store.data)
        GadgetState.setRoomShift(loadShift())
        if (store.data.friends.isEmpty()) newFriend()
        // Friends from before birthdays existed get one now.
        if (store.data.friends.any { it.birthday.isBlank() }) store.update { d ->
            d.copy(friends = d.friends.map { if (it.birthday.isBlank()) it.copy(birthday = FriendsBrain.randomBirthday(rnd)) else it })
        }
        scope.launch { replyLoop() }
        scope.launch { storyLoop() }
        scope.launch { morningLoop() }
        scope.launch { treasureLoop() }
        scope.launch { tripLoop() }
        scope.launch { penpalLoop() }
        scope.launch { snifflesLoop() }
        scope.launch { buildingLoop() }
        scope.launch { dailyLoop() }
        GadgetState.setSnowman(prefs.getBoolean("snowman", false))
        prefs.getString("souvenir", "")!!.split('|').takeIf { it.size == 4 && it[3] == java.time.LocalDate.now().toString() }
            ?.let { GadgetState.setSouvenir(GadgetState.Souvenir(it[0], it[1], it[2], it[3])) }
        scope.launch { stickerLoop() }
        scope.launch { gardenLoop() }
        scope.launch { namingLoop() }
        scope.launch { visitLoop() }
        scope.launch { longingLoop() }
        scope.launch { diaryLoop() }
        scope.launch { watchEvents() }
        scope.launch { hangoutLoop() }
        scope.launch { letterLoop() }
    }

    private fun on() = Prefs(context).blobFriend

    // -- Who ------------------------------------------------------------------------------------

    /** A new friend joins the circle (named by Pixel shortly after). */
    private fun newFriend(): Friend {
        val have = store.data.friends
        val p = Personality.entries.firstOrNull { pp -> have.none { it.personality == pp.name } } ?: Personality.entries.random()
        val color = FriendsBrain.COLORS.firstOrNull { c -> have.none { it.color == c } } ?: FriendsBrain.COLORS.random()
        val f = Friend(id = java.util.UUID.randomUUID().toString().take(8), personality = p.name, color = color, metMs = System.currentTimeMillis(),
            birthday = FriendsBrain.randomBirthday(rnd))
        store.update { it.copy(friends = it.friends + f) }
        GadgetState.log("a new friend (${p.label})")
        return f
    }

    /**
     * More friends turn up over the weeks: after 5, 12 and 25 days, then one every two weeks
     * (up to 12), on top of any added by hand.
     */
    private fun maybeNewFriend() {
        val have = store.data.friends
        val first = have.minOfOrNull { it.metMs } ?: return
        val days = (System.currentTimeMillis() - first) / 86_400_000L
        val due = 1 + listOf(5L, 12L, 25L).count { days >= it } + ((days - 25) / 14).coerceAtLeast(0).toInt() +
            prefs.getInt("friends_bonus", 0)
        if (have.size < due.coerceAtMost(12) && have.all { it.visits > 0 }) { if (Prefs(context).puffHouseBuild) startBuilding() else newFriend() }
    }

    /** New friends now (the settings page, Pixel); Pixel names them over the next few minutes. */
    fun addFriends(count: Int): Int {
        val n = count.coerceIn(1, 6).coerceAtMost(12 - store.data.friends.size).coerceAtLeast(0)
        repeat(n) { newFriend() }
        prefs.edit().putInt("friends_bonus", prefs.getInt("friends_bonus", 0) + n).apply()
        return n
    }

    /** Pixel names each friend and writes a little bio (one at a time, so the names all differ). */
    private suspend fun namingLoop() {
        delay(2 * 60_000L)
        while (true) {
            // The blob itself gets a name first.
            if (Prefs(context).blobName.isBlank()) {
                quietAsk(FriendsBrain.blobNamePrompt(store.data.friends.filter { it.name.isNotBlank() }))?.let(FriendsBrain::parseBlobName)
                    ?.takeIf { n -> store.data.friends.none { it.name.equals(n, ignoreCase = true) } && !n.equals(GadgetState.museName.value, ignoreCase = true) }
                    ?.let { n ->
                        Prefs(context).blobName = n
                        GadgetState.setBlobName(n)
                        GadgetState.log("the blob is called $n")
                        if (!isQuiet()) GadgetState.say("My name is $n! 😊", 15)
                    }
            }
            val f = store.data.friends.firstOrNull { it.name.isBlank() }
            if (f != null) {
                val named = quietAsk(FriendsBrain.namePrompt(f, store.data.friends.filter { it.name.isNotBlank() }))
                    ?.let(FriendsBrain::parseName)
                    ?.takeIf { n -> store.data.friends.none { it.name.equals(n.first, ignoreCase = true) } }
                if (named != null) {
                    store.edit(f.id) { it.copy(name = named.first, bio = named.second) }
                    GadgetState.log("friend named ${named.first}")
                }
            }
            // Quickly while there are names to give, then just a look now and then.
            delay(if (store.data.friends.any { it.name.isBlank() }) 60_000L else 10 * 60_000L)
        }
    }

    private fun pickFriend(exclude: String? = null): Friend? {
        val list = store.data.friends.filter { it.id != exclude }
        if (list.isEmpty()) return null
        // Whoever hasn't been over for longest is likeliest (a brand new one first).
        return list.sortedBy { it.lastVisitMs }.let { s -> if (rnd.nextInt(3) == 0) s.random() else s.first() }
    }

    fun friendByName(name: String?): Friend? =
        name?.takeIf { it.isNotBlank() }?.let { n -> store.data.friends.firstOrNull { it.name.equals(n.trim(), ignoreCase = true) } }

    // -- When -------------------------------------------------------------------------------------

    /**
     * Through the day something's usually going on: a friend over (5–8 minutes, longer for close
     * friends), then 4–12 minutes of quiet before the next thing; about a quarter of those
     * turns are Puff doing something on its own instead. Never while someone's using the Echo.
     */
    private suspend fun visitLoop() {
        var nextAt = System.currentTimeMillis() + 3 * 60_000L
        var checks = 0
        while (true) {
            delay(30_000)
            if (++checks % 20 == 0) maybeNewFriend()
            if (GadgetState.friend.value != null || soloJob?.isActive == true || GadgetState.trip.value != null) {
                // Something's on: the next one comes a little while after it ends.
                nextAt = System.currentTimeMillis() + (4 + rnd.nextInt(9)) * 60_000L
                continue
            }
            // A loud song on the laptop: a friend may drop in to dance even between visits.
            val st = GadgetState.laptopStatus.value
            if (st?.playing == true && st.musicVibe == "hype" && on() && !isQuiet() && life.idle() && rnd.nextFloat() < 0.1f &&
                System.currentTimeMillis() - prefs.getLong("friend_last", 0L) > 5 * 60_000L) { visit("dance"); continue }
            if (System.currentTimeMillis() < nextAt) continue
            if (isQuiet() || !life.idle() || life.hour() !in 8..21) continue
            if (System.currentTimeMillis() - GadgetState.lastHuman < 20_000) continue
            if (on() && rnd.nextFloat() < 0.75f) visit() else solo()
        }
    }

    // -- Puff on its own ---------------------------------------------------------------------------

    private var soloJob: Job? = null

    /** A minute or two of Puff doing something by itself (reading, star jumps, a coffee...). */
    fun solo(): String {
        if (soloJob?.isActive == true || GadgetState.friend.value != null) return "busy"
        val today = java.time.LocalDate.now().toString()
        val (item, lines) = when {
            prefs.getString("garden_watered", "") != today && rnd.nextBoolean() -> {
                prefs.edit().putString("garden_watered", today).apply()
                DayLog.event("watered the window-box garden", "garden")
                HeldItem.WATERING_CAN to listOf("Watering my garden 🌱", "Grow, little plants! 🌿", "There. All watered! 💧")
            }
            rnd.nextInt(5) == 0 -> {
                scope.launch { delay(40_000); addBook(GadgetState.BlobCalled()) }
                HeldItem.NOTE to listOf("Reading a book 📖", "*turns a page*", "What a good story! ✨")
            }
            else -> SOLO.random()
        }
        val name = GadgetState.blobName.value.ifBlank { "the blob" }
        soloJob = scope.launch {
            GadgetState.log("$name on its own: ${lines.first()}")
            GadgetState.hold(item, "", 2)
            for ((i, line) in lines.withIndex()) {
                GadgetState.say(line, 6)
                GadgetState.react(if (i % 2 == 0) Reaction.HOP else Reaction.NOD)
                delay(25_000)
            }
            GadgetState.hold(HeldItem.NONE, "", 0)
        }
        return lines.first()
    }

    private val SOLO = listOf(
        HeldItem.NOTE to listOf("Writing in my diary 📝", "Dear diary… 🤔", "There. All written down! ✨"),
        HeldItem.COFFEE to listOf("Coffee break ☕", "*sip*… ahh.", "Cozy. 😌"),
        HeldItem.STAR to listOf("Star jumps! ⭐", "One… two… three… 💪", "Phew, that's my workout! 😅"),
        HeldItem.CAMERA to listOf("Taking photos 📸", "Say cheese! 🧀", "That one's going in the album!"),
        HeldItem.LETTER to listOf("Rereading old letters 💌", "Hehe, I love this one.", "My friends are the best. 💛"),
        HeldItem.BALLOON to listOf("Look, a balloon! 🎈", "Don't float away… 🙈", "Got it! 😄"),
        HeldItem.UMBRELLA to listOf("Practising my umbrella twirl ☂️", "*twirl*", "Ta-da! 🌂"),
    )

    /** What to do, for the moment: music means dancing, the TV in the evening a movie, afternoons tea... */
    private fun pickActivity(f: Friend): String {
        val h = life.hour()
        val st = GadgetState.laptopStatus.value
        val roll = rnd.nextFloat()
        return when {
            st?.playing == true && st.musicVibe.isNotEmpty() && roll < 0.6f -> "dance"
            GadgetState.tvPlaying.value && h >= 17 && roll < 0.7f -> "movie"
            h in 12..17 && roll < 0.35f && GadgetState.weather.value?.summary?.lowercase()?.let { w ->
                listOf("rain", "drizzle", "shower", "storm", "thunder").any { it in w } } == true -> "boardgame"
            roll in 0.55f..0.6f -> "boardgame"
            (h >= 20 || h < 1) && GadgetState.weather.value?.summary?.lowercase()?.let { "clear" in it || "sun" in it } == true &&
                roll < (if (FriendsBrain.meteorShower(java.time.LocalDate.now()) != null) 0.6f else 0.3f) -> "stargazing"
            h in 14..16 && roll < 0.35f -> "tea"
            h >= 19 && roll < 0.3f -> "nap"
            roll < 0.07f && f.closeness >= 20 && FriendsBrain.GIFTS.any { !life.has(it) } -> "gift"
            roll < 0.13f && movable().isNotEmpty() -> "redecorate"
            roll < 0.25f && f.name.isNotBlank() -> "sad"
            else -> listOf("chat", "chat", "tag", "tictactoe", "rps", "catch", "hideseek", "story").random()
        }
    }

    private fun movable() = listOf(Decor.PLANT, Decor.LAMP, Decor.RECORD_PLAYER, Decor.UMBRELLA_STAND, Decor.RARE_PLANT, Decor.LAVA_LAMP)
        .filter { life.has(it) }

    /** Comfort when the server goes down or a severe alert arrives; a party on holidays and countdown days; sleepovers. */
    private suspend fun watchEvents() {
        scope.launch {
            var lastDown = false
            GadgetState.server.collect { s ->
                val down = s?.overall == "down"
                if (down && !lastDown) eventVisit("comfort", "comfort")
                lastDown = down
            }
        }
        scope.launch {
            // A new severe weather alert (each one only once, even across restarts).
            GadgetState.alerts.collect { list ->
                val severe = list.filter { it.severity.equals("Severe", true) || it.severity.equals("Extreme", true) }.map { it.id }.toSet()
                val known = prefs.getString("friend_alerts_seen", "")!!.split('\n').toSet()
                if ((severe - known).isNotEmpty()) {
                    prefs.edit().putString("friend_alerts_seen", (known + severe).toList().takeLast(50).joinToString("\n")).apply()
                    eventVisit("comfort", "comfort")
                }
            }
        }
        scope.launch {
            var wasQuiet: Boolean? = null
            GadgetState.quiet.collect { q ->
                // Quiet hours starting: now and then, a friend stays the night.
                if (wasQuiet == false && q && on()) {
                    val pal = store.data.friends.filter { it.closeness >= 30 }.randomOrNull()
                    val lastSleepover = prefs.getLong("sleepover_last", 0L)
                    if (pal != null && rnd.nextFloat() < 0.25f && System.currentTimeMillis() - lastSleepover > 3 * 86_400_000L) {
                        prefs.edit().putLong("sleepover_last", System.currentTimeMillis()).apply()
                        visit("sleepover", null, pal.name)
                    } else if (pal != null && Prefs(context).puffAwaySleepovers && GadgetState.friend.value == null && GadgetState.trip.value == null && rnd.nextFloat() < 0.12f &&
                        System.currentTimeMillis() - prefs.getLong("away_sleepover_last", 0L) > 5 * 86_400_000L) {
                        sleepOverAt(pal)
                    }
                }
                // Morning: a sleepover ends.
                if (wasQuiet == true && !q && GadgetState.friend.value?.activity == "sleepover") endVisit()
                if (wasQuiet == true && !q) GadgetState.trip.value?.takeIf { it.overnight }?.let { homeFromSleepover(it) }
                wasQuiet = q
            }
        }
        while (true) {
            delay(15 * 60_000L)
            seasonal()
            val today = java.time.LocalDate.now()
            val party = FriendsBrain.holiday(today)
                ?: GadgetState.specialDays.value.firstOrNull { d ->
                    runCatching { java.time.LocalDate.parse(d.date.take(10)) == today }.getOrDefault(false)
                }?.title
            if (party != null && life.hour() in 10..20 && prefs.getString("party_day", "") != today.toString() && life.idle() && !isQuiet()) {
                prefs.edit().putString("party_day", today.toString()).apply()
                partyFor = party
                visit("party")
            }
        }
    }

    /** Once a day each: birthdays, Halloween, snowball weather. */
    private fun seasonal() {
        if (!on() || isQuiet() || !life.idle() || GadgetState.friend.value != null) return
        val today = java.time.LocalDate.now()
        val h = life.hour()
        fun once(key: String): Boolean {
            val k = "event_${key}_$today"
            if (prefs.getBoolean(k, false)) return false
            prefs.edit().putBoolean(k, true).apply()
            return true
        }
        val bday = store.data.friends.firstOrNull { it.name.isNotBlank() && FriendsBrain.isBirthday(it, today) }
        val w = GadgetState.weather.value
        when {
            bday != null && h in 10..20 && once("birthday_${bday.id}") -> visit("birthday", null, bday.name)
            "%02d-%02d".format(today.monthValue, today.dayOfMonth) == Prefs(context).blobBirthday && h in 10..20 &&
                today.year.toString() !in store.data.puffBirthdays && store.data.friends.any { it.name.isNotBlank() } && once("blob_birthday") -> {
                visit("puffbirthday")
            }
            today.monthValue == 10 && today.dayOfMonth == 31 && h in 17..21 && once("halloween") -> visit("trickortreat")
            FriendsBrain.festival(today, h)?.let { once("fest_${it.name}") } == true -> {
                val fest = FriendsBrain.festival(today, h)!!
                GadgetState.react(Reaction.HOP)
                GadgetState.say(if (fest == FriendsBrain.Festival.FAIR) "There's a harvest fair on our street today! 🍎🎪" else "The winter lights parade is tonight! 🏮✨", 12)
                DayLog.event(if (fest == FriendsBrain.Festival.FAIR) "went to the harvest fair on the street" else "watched the winter lights parade", "festival")
            }
            // The first snow of the winter: everyone over to build a snowman.
            w != null && "snow" in w.summary.lowercase() && h in 8..19 &&
                prefs.getString("firstsnow_season", "") != (if (today.monthValue >= 7) today.year else today.year - 1).toString() -> {
                prefs.edit().putString("firstsnow_season", (if (today.monthValue >= 7) today.year else today.year - 1).toString()).apply()
                visit("firstsnow")
            }
            // The first Sunday of the month, mid-afternoon: everyone comes over for the group photo.
            today.dayOfWeek == java.time.DayOfWeek.SUNDAY && today.dayOfMonth <= 7 && h in 14..17 &&
                store.data.friends.count { it.name.isNotBlank() } >= 2 && once("groupphoto") -> visit("groupphoto")
            FriendsBrain.Club.entries.firstOrNull { c -> c.day == today.dayOfWeek && h in c.hours }?.let { c ->
                store.data.friends.firstOrNull { it.name.isNotBlank() && FriendsBrain.clubOf(it) == c }?.takeIf { once("club_${c.name}") }
                    ?.let { member -> c to member }
            } != null -> {
                val c = FriendsBrain.Club.entries.first { it.day == today.dayOfWeek && h in it.hours }
                val member = store.data.friends.first { it.name.isNotBlank() && FriendsBrain.clubOf(it) == c }
                visit(c.name.lowercase() + "club", null, member.name)
            }
            w != null && ("snow" in w.summary.lowercase() || (w.tempF <= 40 && today.monthValue in listOf(11, 12, 1, 2))) && h in 10..18 &&
                rnd.nextFloat() < 0.3f && once("snowball") -> visit("snowball")
        }
    }

    private var partyFor = ""

    private fun eventVisit(activity: String, key: String) {
        if (!on() || isQuiet()) return
        val last = prefs.getLong("friend_event_$key", 0L)
        if (System.currentTimeMillis() - last < 3 * 3_600_000L) return
        prefs.edit().putLong("friend_event_$key", System.currentTimeMillis()).apply()
        scope.launch {
            delay(20_000)
            visit(activity)
        }
    }

    /** Nobody's come round in two days: the blob looks out of the window for them; a postcard after three. */
    private suspend fun longingLoop() {
        while (true) {
            delay(60 * 60_000L)
            val now = System.currentTimeMillis()
            val friends = store.data.friends.filter { it.name.isNotBlank() && it.visits > 0 }
            val lastAny = friends.maxOfOrNull { it.lastVisitMs } ?: continue
            if (now - lastAny > 2 * 86_400_000L) {
                missing = true
                if (!isQuiet() && life.idle() && rnd.nextInt(4) == 0) {
                    val who = friends.maxByOrNull { it.closeness }!!
                    GadgetState.react(Reaction.PEEK)
                    GadgetState.say("I wonder where ${who.name} is… 🪟", 20)
                }
            }
            // Away three days or more: a postcard (once per absence).
            friends.filter { now - it.lastVisitMs > 3 * 86_400_000L && it.postcardMs < it.lastVisitMs }.randomOrNull()?.let { f ->
                val text = FriendsBrain.POSTCARDS.random()
                store.update { d ->
                    d.copy(postcards = (d.postcards + Postcard(f.id, text, java.time.LocalDate.now().toString())).takeLast(12),
                        friends = d.friends.map { if (it.id == f.id) it.copy(postcardMs = now) else it })
                }
                life.unlock(Decor.POSTCARDS, " from ${f.name}", quietly = true)
                DayLog.event("got a postcard from ${f.name}: \"$text\"", "friend")
                if (!isQuiet()) {
                    GadgetState.react(Reaction.HOP)
                    GadgetState.hold(HeldItem.LETTER, "💌 Postcard from ${f.name}", 30)
                    GadgetState.say(text, 30)
                }
            }
        }
    }

    // -- Visits -----------------------------------------------------------------------------------

    fun endVisit() {
        job?.cancel()
    }

    /** Pixel's tap on a friend (from the screen or the watch): it giggles, waves or blushes. */
    fun tapped(id: String? = null) {
        val v = GadgetState.friend.value ?: return
        val fid = id?.takeIf { it in v.friendIds } ?: v.friendIds.firstOrNull() ?: return
        val f = store.friend(fid) ?: return
        val reaction = when (f.p) {
            Personality.SHY -> "blush"
            else -> listOf("giggle", "wave", "blush").random()
        }
        GadgetState.scene { it.copy(tappedId = fid, tappedUntil = System.currentTimeMillis() + 2_500) }
        GadgetState.setFriendSay(when (reaction) { "giggle" -> "Hehe! 😆"; "wave" -> "👋"; else -> "☺️" }, fid)
        scope.launch {
            delay(2_500)
            GadgetState.setFriendSay(null, fid)
        }
        DayLog.friendTapped(f.shownName)
    }

    /**
     * A visit now: [activity] (null picks one for the moment), for about [minutes], from the
     * friend called [name] (null picks one). Returns what happened, for Pixel.
     */
    fun visit(activity: String? = null, minutes: Int? = null, name: String? = null): String {
        if (GadgetState.friend.value != null) return "already visiting"
        if (GadgetState.trip.value != null) return "Puff's out visiting"
        val f = friendByName(name) ?: pickFriend() ?: newFriend()
        val act = activity?.lowercase()?.replace("-", "")?.replace(" ", "")?.let { a ->
            when (a) { "tictactoe", "ttt" -> "tictactoe"; "rockpaperscissors" -> "rps"; "hideandseek" -> "hideseek"; "teatime" -> "tea"; else -> a }
        }?.takeIf { it in FriendsBrain.ACTIVITIES } ?: pickActivity(f)
        // Two at once for the sociable things, now and then.
        val group = act in setOf("birthday", "trickortreat")
        val clubMate = if (act.endsWith("club")) store.data.friends.firstOrNull {
            it.id != f.id && it.name.isNotBlank() && FriendsBrain.clubOf(it).name.lowercase() == act.removeSuffix("club")
        } else null
        val second = clubMate ?: if (group || act in setOf("chat", "tea", "party", "dance", "story", "movie") && rnd.nextFloat() < 0.3f) pickBuddy(f) else null
        val guest: Friend? = null
        GadgetState.setGuest(guest)
        val ids = if (act in setOf("groupphoto", "puffbirthday", "firstsnow")) listOf(f.id) + store.data.friends.filter { it.id != f.id && it.name.isNotBlank() }.map { it.id }
            else listOfNotNull(f.id, second?.id)
        val base = when (act) {
            "sleepover" -> 9 * 60
            "movie" -> 10
            "nap" -> 6
            else -> 5 + rnd.nextInt(4)
        }
        // Closer friends stay longer.
        val len = ((minutes?.coerceIn(1, 600) ?: base) * 60_000L * (if (minutes == null) 1f + f.closeness / 100f else 1f)).toLong()
        val now = System.currentTimeMillis()
        val v = FriendVisit(act, now, now + len, f.color, ids)
        prefs.edit().putLong("friend_last", now).apply()
        GadgetState.log("friend visiting: ${f.shownName} ($act)")
        // Somewhere else (another page), the blob mentions who's over when you come back.
        if (GadgetState.idlePage.value !in setOf(0, 8) && !isQuiet()) GadgetState.say("${f.shownName} came over! 👯", 8)
        val reunion = missing
        missing = false
        job = scope.launch {
            GadgetState.scene { FriendScene() }
            GadgetState.setFriend(v)
            var detail = ""
            try {
                delay(1_800) // walking in
                if (reunion) {
                    GadgetState.react(Reaction.CELEBRATE)
                    GadgetState.say("${f.shownName}! I missed you! 🥹", 6)
                    hug(2_500)
                }
                // Two friends arriving together are often in the middle of something they did.
                if (second != null) store.data.happenings.lastOrNull { !it.told && f.id in it.ids && second.id in it.ids }?.let { h ->
                    say(f, FriendsBrain.hangoutLine(f, second, h.text.substringAfter(" and ").substringAfter(" ")), 3_800)
                    say(second, "It's true! 😆", 2_500)
                    told(h)
                }
                playChapter(listOfNotNull(f, second))
                scope.launch { delay(6_000); life.friendSnapshot("${FriendsBrain.activityLabel(act).replaceFirstChar { it.uppercase() }} with ${names(ids)}") }
                detail = when (act) {
                    "chat" -> chat(f, second)
                    "tag" -> tag(f, v)
                    "nap" -> nap(f, v)
                    "tictactoe" -> ticTacToe(f)
                    "rps" -> rps(f)
                    "catch" -> catch(f)
                    "dance" -> dance(f, v)
                    "movie" -> movie(f, v)
                    "tea" -> tea(f, second, v)
                    "sleepover" -> sleepover(f, v)
                    "hideseek" -> hideSeek(f)
                    "story" -> story(f, v)
                    "party" -> party(f, v)
                    "comfort" -> comfort(f)
                    "gift" -> gift(f)
                    "redecorate" -> redecorate(f)
                    "birthday" -> birthday(f, second, v)
                    "trickortreat" -> trickOrTreat(f, second, v)
                    "snowball" -> snowball(f)
                    "sad" -> cheerUp(f)
                    "treasure" -> hideTreasure(f)
                    "bookclub" -> bookClub(f, second, v)
                    "danceclub" -> danceClub(f, second, v)
                    "gardenclub" -> gardenClub(f, second, v)
                    "groupphoto" -> groupPhoto(f, v)
                    "cheerrusty" -> cheerRusty(f, second)
                    "puffbirthday" -> puffBirthday(f, v)
                    "stargazing" -> stargazing(f, second, v)
                    "welcome" -> welcome(f)
                    "boardgame" -> boardGame(f, v)
                    "firstsnow" -> firstSnow(f, v)
                    else -> chat(f, second)
                }
                // After the game or whatever it was, they stay and hang out until it's time to go.
                linger(f, second, v)
                // Good friends hug goodbye.
                if (f.closeness >= 30) hug(2_000)
                GadgetState.setFriendSay(if (act == "sleepover") "Thanks for having me! ☀️" else "Bye! 👋", f.id)
                second?.let { GadgetState.setFriendSay("Bye bye! 👋", it.id) }
                GadgetState.react(Reaction.WAVE)
                delay(2_200)
            } finally {
                GadgetState.clearFriendSay()
                GadgetState.scene { FriendScene() }
                GadgetState.setFriend(null)
                GadgetState.setGuest(null)
                record(f, act, detail, second)
            }
        }
        return "${f.shownName} is visiting (${FriendsBrain.activityLabel(act)})"
    }

    private fun names(ids: List<String>) = ids.mapNotNull { store.friend(it)?.shownName }.joinToString(" and ")

    private fun record(f: Friend, act: String, detail: String, second: Friend?) {
        val today = java.time.LocalDate.now().toString()
        val gain = if (act in FriendsBrain.GAMES) 5 else 3
        for (fr in listOfNotNull(f, second)) {
            store.update { d ->
                val key = "$today|${fr.id}"
                d.copy(
                    friends = d.friends.map {
                        if (it.id != fr.id) it else it.copy(
                            visits = it.visits + 1, lastVisitMs = System.currentTimeMillis(),
                            closeness = (it.closeness + gain).coerceAtMost(100),
                            played = it.played + (act to (it.played[act] ?: 0) + 1),
                        )
                    },
                    today = (d.today.filterKeys { it.startsWith(today) } + (key to ((d.today[key] ?: emptyList()) + act))),
                )
            }
        }
        if (second != null) bump(f.id, second.id, 4)
        val who = names(listOfNotNull(f.id, second?.id))
        DayLog.friendVisited("$who came over for ${FriendsBrain.activityLabel(act)}" + if (detail.isNotEmpty()) " ($detail)" else "")
    }

    private fun remember(f: Friend, memory: String) {
        store.edit(f.id) { it.copy(memories = (it.memories + memory).takeLast(8)) }
    }

    private suspend fun say(f: Friend, text: String, ms: Long = 3_800) {
        GadgetState.setFriendSay(text, f.id)
        delay(ms)
        GadgetState.setFriendSay(null, f.id)
    }

    private suspend fun blob(text: String, ms: Long = 3_800) {
        GadgetState.say(text, (ms / 1000).toInt().coerceAtLeast(2))
        delay(ms)
    }

    private suspend fun hug(ms: Long) {
        GadgetState.scene { it.copy(hug = true) }
        GadgetState.setMood(Mood.HAPPY, (ms / 1000).toInt() + 5)
        delay(ms)
        GadgetState.scene { it.copy(hug = false) }
    }

    private suspend fun untilEnd(v: FriendVisit, leaveMs: Long = 4_500) =
        delay((v.untilMs - System.currentTimeMillis() - leaveMs).coerceAtLeast(0))

    private val HANG_LINES = listOf(
        false to "This is nice. ☺️", true to "Mm-hm. 😊", false to "What should we do next?", true to "Just hanging out is good.",
        false to "Your room's so cozy!", true to "Thanks! 🏠", false to "*hums a little tune* ♪", true to "*bobs along* ♪",
        false to "I like your window.", true to "You can see the weather from it! 🌤️", false to "Hehe. 😄", true to "Hehe! 😆",
    )

    /** Quiet time together after the activity: the odd line or wiggle every so often, until the visit's up. */
    private suspend fun linger(f: Friend, second: Friend?, v: FriendVisit) {
        while (System.currentTimeMillis() < v.untilMs - 6_000) {
            delay((18 + rnd.nextInt(20)) * 1_000L)
            if (System.currentTimeMillis() >= v.untilMs - 6_000) break
            // Showing off a trick it's good at, now and then.
            store.data.tricks.filter { it.value >= 60 }.keys.randomOrNull()?.takeIf { rnd.nextInt(5) == 0 }?.let { trick ->
                GadgetState.say("Watch this! 😎", 3)
                delay(1_500)
                perform(trick, store.data.tricks[trick] ?: 0)
                delay(3_000)
                GadgetState.setFriendSay(listOf("Wow!! 👏", "How did you do that?! 😮", "Teach me! 🤩").random(), f.id)
                delay(3_000)
                GadgetState.clearFriendSay()
                continue
            }
            val (mine, line) = HANG_LINES.random()
            if (mine) GadgetState.say(line, 5) else GadgetState.setFriendSay(line, (if (second != null && rnd.nextBoolean()) second else f).id)
            if (rnd.nextInt(3) == 0) GadgetState.react(listOf(Reaction.HOP, Reaction.NOD, Reaction.WINK).random())
            delay(5_000)
            GadgetState.clearFriendSay()
        }
    }

    // -- What they do -----------------------------------------------------------------------------

    private suspend fun chat(f: Friend, second: Friend?): String {
        GadgetState.react(Reaction.WAVE)
        // News from its own life first: something it got up to with another friend.
        store.data.happenings.lastOrNull { !it.told && f.id in it.ids }?.let { h ->
            val other = h.ids.firstOrNull { it != f.id }?.let { store.friend(it) }
            if (other != null) {
                say(f, FriendsBrain.hangoutLine(f, other, h.what()), 4_200)
                blob("No way! 😄", 2_500)
                told(h)
            }
        }
        for ((mine, line) in FriendsBrain.chat(f, rnd)) if (mine) blob(line, 4_200) else say(f, line, 4_200)
        second?.let { s -> say(s, "Can I join? 🙋", 3_000); blob("The more the merrier! 🎈", 3_000) }
        GadgetState.setMood(Mood.HAPPY, 30)
        return ""
    }

    private suspend fun tag(f: Friend, v: FriendVisit): String {
        say(f, "Tag, you're it! 🏃", 2_000)
        while (System.currentTimeMillis() < v.untilMs - 4_000) {
            GadgetState.react(listOf(Reaction.WANDER, Reaction.HOP, Reaction.WANDER).random())
            delay(Reaction.WANDER.ms)
        }
        GadgetState.setMood(Mood.HAPPY, 30)
        blob("Phew! 😅", 2_500)
        return ""
    }

    private suspend fun nap(f: Friend, v: FriendVisit): String {
        say(f, if (f.p == Personality.SLEEPY) "*yaaawn* nap time? 😴" else "Mind if I nap here? 😴", 3_000)
        GadgetState.setMood(Mood.SLEEPY, ((v.untilMs - System.currentTimeMillis()) / 1000).toInt().coerceAtLeast(5))
        untilEnd(v)
        GadgetState.setMood(Mood.NEUTRAL, 0)
        GadgetState.react(Reaction.STRETCH)
        return ""
    }

    private suspend fun ticTacToe(f: Friend): String {
        say(f, "Tic-tac-toe? I'm O! ⭕", 2_500)
        val (moves, winner) = FriendsBrain.ticTacToe(rnd)
        val cells = CharArray(9) { ' ' }
        moves.forEachIndexed { i, cell ->
            cells[cell] = if (i % 2 == 0) 'X' else 'O'
            GadgetState.scene { it.copy(board = String(cells)) }
            if (i % 2 == 0) GadgetState.react(Reaction.LEAN_IN)
            delay(1_300)
        }
        delay(800)
        val result = when (winner) {
            'X' -> { GadgetState.react(Reaction.CELEBRATE); say(f, "Rematch next time! 😤", 3_000); store.edit(f.id) { it.copy(losses = it.losses + 1) }; "the blob won" }
            'O' -> { say(f, "I win! 🎉", 2_500); GadgetState.setMood(Mood.SAD, 8); blob("Aww. Good game! 🤝", 3_000); store.edit(f.id) { it.copy(wins = it.wins + 1) }; "${f.shownName} won" }
            else -> { blob("A draw! 🤝", 3_000); "a draw" }
        }
        GadgetState.scene { it.copy(board = null) }
        return result
    }

    private suspend fun rps(f: Friend): String {
        say(f, "Rock, paper, scissors! Best of three!", 2_500)
        var score = 0
        repeat(3) {
            say(f, "Rock… paper… scissors…", 1_800)
            val a = rnd.nextInt(3); val b = rnd.nextInt(3)
            GadgetState.say(FriendsBrain.RPS_EMOJI[a], 3)
            GadgetState.setFriendSay(FriendsBrain.RPS_EMOJI[b], f.id)
            val r = FriendsBrain.rps(a, b)
            score += r
            if (r > 0) GadgetState.react(Reaction.HOP) else if (r < 0) GadgetState.setMood(Mood.SURPRISED, 2)
            delay(2_600)
            GadgetState.setFriendSay(null, f.id)
        }
        return when {
            score > 0 -> { GadgetState.react(Reaction.CELEBRATE); blob("I win! 🏆", 3_000); store.edit(f.id) { it.copy(losses = it.losses + 1) }; "the blob won" }
            score < 0 -> { say(f, "Victory! 🏆", 3_000); store.edit(f.id) { it.copy(wins = it.wins + 1) }; "${f.shownName} won" }
            else -> { blob("Tie! Again sometime 😄", 3_000); "a tie" }
        }
    }

    private suspend fun catch(f: Friend): String {
        say(f, "Catch! ⚾", 2_000)
        var bonked = false
        repeat(8) { i ->
            val toFriend = i % 2 == 0
            GadgetState.scene { it.copy(throwAt = System.currentTimeMillis(), toFriend = toFriend) }
            delay(1_300)
            if (!toFriend && !bonked && rnd.nextFloat() < 0.18f) {
                bonked = true
                GadgetState.react(Reaction.STARTLE)
                GadgetState.say("Ow! 😵", 3)
                say(f, "Oops! Sorry!! 😅", 2_500)
                remember(f, "the time the ball bonked you")
            } else delay(300)
        }
        GadgetState.scene { it.copy(throwAt = 0) }
        return if (bonked) "the ball bonked the blob" else ""
    }

    private suspend fun dance(f: Friend, v: FriendVisit): String {
        say(f, "I heard music! 💃", 2_500)
        GadgetState.setMood(Mood.HAPPY, ((v.untilMs - System.currentTimeMillis()) / 1000).toInt().coerceAtLeast(5))
        untilEnd(v)
        return ""
    }

    private suspend fun movie(f: Friend, v: FriendVisit): String {
        GadgetState.scene { it.copy(popcorn = true) }
        say(f, "Ooh, what are we watching? 🍿", 3_000)
        blob("Shh, it's starting! 🎬", 3_000)
        untilEnd(v)
        say(f, "That was good! 🍿", 2_500)
        GadgetState.scene { it.copy(popcorn = false) }
        return ""
    }

    private suspend fun tea(f: Friend, second: Friend?, v: FriendVisit): String {
        val birthday = GadgetState.specialDays.value.any { "birthday" in it.title.lowercase() &&
            runCatching { java.time.LocalDate.parse(it.date.take(10)) == java.time.LocalDate.now() }.getOrDefault(false) }
        val snack = if (birthday) "🎂" else listOf("🍪", "🧁", "🍩").random()
        say(f, "I brought ${if (birthday) "cake! 🎂" else "snacks! $snack"}", 3_000)
        GadgetState.scene { it.copy(teacups = true, snack = snack) }
        blob("Tea time! ☕", 3_000)
        second?.let { say(it, "One lump or two? 🫖", 3_000) }
        untilEnd(v)
        GadgetState.scene { it.copy(teacups = false, snack = "") }
        return ""
    }

    private suspend fun sleepover(f: Friend, v: FriendVisit): String {
        GadgetState.scene { it.copy(pillow = true) }
        say(f, "Sleepover!! 🛏️", 3_000)
        blob("Yay! 🌙", 2_500)
        untilEnd(v, 2_000)
        return "a sleepover"
    }

    private suspend fun hideSeek(f: Friend): String {
        val spots = listOf(Decor.PLANT, Decor.LAMP, Decor.BOOKSHELF, Decor.RARE_PLANT).filter { life.has(it) }
        val spot = spots.randomOrNull() ?: Decor.RUG
        say(f, "Hide and seek! Count to ten! 🙈", 2_500)
        blob("1… 2… 3… …10! Ready or not! 👀", 3_000)
        GadgetState.scene { it.copy(hidingAt = spot.name) }
        repeat(3) {
            GadgetState.react(listOf(Reaction.PEEK, Reaction.WANDER, Reaction.PEEK).random())
            delay(2_800)
        }
        GadgetState.react(Reaction.HOP)
        blob("Found you! 😄", 2_000)
        GadgetState.scene { it.copy(hidingAt = null) }
        say(f, "Aww, again later! 😆", 2_500)
        return "${f.shownName} hid behind the ${spot.name.lowercase().replace('_', ' ')}"
    }

    private suspend fun story(f: Friend, v: FriendVisit): String {
        val friendReads = rnd.nextBoolean()
        GadgetState.scene { it.copy(book = true) }
        for (line in FriendsBrain.STORY) {
            if (friendReads) say(f, line, 3_800) else blob(line, 3_800)
        }
        // The listener has dozed off by the end.
        if (friendReads) GadgetState.setMood(Mood.SLEEPY, 20) else say(f, "Zzz… 💤", 3_000)
        GadgetState.scene { it.copy(book = false) }
        return if (friendReads) "${f.shownName} read a story" else "the blob read ${f.shownName} a story"
    }

    private suspend fun party(f: Friend, v: FriendVisit): String {
        val what = partyFor.ifBlank { FriendsBrain.holiday(java.time.LocalDate.now()) ?: "today" }
        GadgetState.scene { it.copy(party = true) }
        val now = System.currentTimeMillis()
        val keep = GadgetState.style.value
        GadgetState.setStyle(keep.copy(accessory = Accessory.PARTY_HAT, untilMs = v.untilMs))
        GadgetState.react(Reaction.CELEBRATE)
        say(f, "Happy $what! 🎉", 3_000)
        GadgetState.hold(HeldItem.CAKE, "🎉 $what", ((v.untilMs - now) / 60_000).toInt().coerceAtLeast(1))
        untilEnd(v)
        GadgetState.setStyle(keep)
        GadgetState.scene { it.copy(party = false) }
        remember(f, "the $what party")
        return "a party for $what"
    }

    private suspend fun comfort(f: Friend): String {
        say(f, "Hey… it'll be okay. 🤗", 3_000)
        hug(3_000)
        blob("Thanks. I feel better. 💛", 3_000)
        return "${f.shownName} came to cheer it up"
    }

    private suspend fun gift(f: Friend): String {
        val d = FriendsBrain.GIFTS.firstOrNull { !life.has(it) } ?: return chat(f, null)
        say(f, "I brought you something! 🎁", 3_000)
        GadgetState.hold(HeldItem.GIFT, "From ${f.shownName}", 1)
        delay(2_500)
        life.unlock(d, " from ${f.shownName}", quietly = true)
        GadgetState.react(Reaction.CELEBRATE)
        blob("${d.label.replaceFirstChar { it.uppercase() }}! Thank you!! 🥰", 3_500)
        remember(f, "when you gave me ${d.label}")
        return "${f.shownName} gave it ${d.label}"
    }

    private suspend fun redecorate(f: Friend): String {
        val d = movable().randomOrNull() ?: return chat(f, null)
        val item = d.label.removePrefix("a ").removePrefix("an ")
        say(f, "Ooh, what if the $item went over here? 🛋️", 3_000)
        val shift = (if (rnd.nextBoolean()) 1 else -1) * (24f + rnd.nextInt(20))
        setShift(d, shift)
        delay(1_800)
        return if (rnd.nextBoolean()) {
            GadgetState.react(Reaction.NOD)
            blob("I love it! 😍", 3_000)
            "${f.shownName} moved the $item (and it stayed)"
        } else {
            GadgetState.setMood(Mood.CURIOUS, 6)
            blob("Hmm… 🤔", 3_000)
            say(f, "No? 😅", 2_000)
            setShift(d, 0f)
            blob("Back where it was. 😌", 2_500)
            "${f.shownName} moved the $item, and the blob moved it back"
        }
    }

    // -- Friends with each other ------------------------------------------------------------------

    private fun Happening.what() = text.substringAfter(" and ").substringAfter(" ")

    /** Someone [f] gets on with (closer bonds likelier), for a visit together. */
    private fun pickBuddy(f: Friend): Friend? {
        val others = store.data.friends.filter { it.id != f.id }
        if (others.isEmpty()) return null
        val weights = others.map { 10 + store.data.bond(f.id, it.id) }
        var roll = rnd.nextInt(weights.sum())
        others.forEachIndexed { i, o -> roll -= weights[i]; if (roll < 0) return o }
        return others.last()
    }

    private fun bump(a: String, b: String, by: Int) {
        store.update { d -> d.copy(bonds = d.bonds + (FriendsBrain.pair(a, b) to ((d.bonds[FriendsBrain.pair(a, b)] ?: 0) + by).coerceAtMost(100))) }
    }

    private fun told(h: Happening) {
        store.update { d -> d.copy(happenings = d.happenings.map { if (it == h) it.copy(told = true) else it }) }
        DayLog.event("heard that ${h.text}", "friend")
    }

    /** Two friends get up to something together right now (the playground). */
    fun hangoutNow(): String? {
        val named = store.data.friends.filter { it.name.isNotBlank() }
        if (named.size < 2) return null
        val a = named.random()
        val b = pickBuddy(a)?.takeIf { it.name.isNotBlank() } ?: return null
        val what = FriendsBrain.HANGOUTS.random()
        store.update { d -> d.copy(happenings = (d.happenings + Happening(java.time.LocalDate.now().toString(), listOf(a.id, b.id), "${a.name} and ${b.name} $what")).takeLast(40)) }
        bump(a.id, b.id, 6)
        return "${a.name} and ${b.name} $what"
    }

    /** Now and then, two friends get up to something on their own (once or twice a day). */
    private suspend fun hangoutLoop() {
        while (true) {
            delay(30 * 60_000L)
            val named = store.data.friends.filter { it.name.isNotBlank() }
            if (named.size < 2 || life.hour() !in 10..20) continue
            val today = java.time.LocalDate.now().toString()
            if (store.data.happenings.count { it.date == today } >= 2 || rnd.nextFloat() > 0.15f) continue
            val a = named.random()
            val b = pickBuddy(a)?.takeIf { it.name.isNotBlank() } ?: continue
            val what = FriendsBrain.HANGOUTS.random()
            store.update { d -> d.copy(happenings = (d.happenings + Happening(today, listOf(a.id, b.id), "${a.name} and ${b.name} $what")).takeLast(40)) }
            bump(a.id, b.id, 6)
            GadgetState.log("friends hung out: ${a.name} and ${b.name} $what")
        }
    }

    // -- Letters ------------------------------------------------------------------------------------

    /** Every couple of days a friend writes the blob a letter (Pixel writes it, in the friend's voice). */
    private suspend fun letterLoop() {
        delay(10 * 60_000L)
        while (true) {
            delay(30 * 60_000L)
            if (!on() || isQuiet() || life.hour() !in 9..20) continue
            // Someone Puff cheered up writes to say thanks (a couple of hours later).
            store.data.owesThanks?.let { id -> store.friend(id) }?.takeIf {
                System.currentTimeMillis() - it.lastVisitMs > 2 * 3_600_000L
            }?.let { f ->
                store.update { it.copy(owesThanks = null) }
                writeLetter(f)
                continue
            }
            if (System.currentTimeMillis() - prefs.getLong("letter_last", 0L) < 36 * 3_600_000L || rnd.nextFloat() > 0.3f) continue
            val f = store.data.friends.filter { it.name.isNotBlank() }.maxByOrNull { it.closeness + rnd.nextInt(30) } ?: continue
            writeLetter(f)
        }
    }

    /** A letter from [f] now. */
    suspend fun writeLetter(f: Friend): Boolean {
        val d = store.data
        val recent = (f.memories.takeLast(2) + d.happenings.filter { f.id in it.ids }.takeLast(2).map { it.text } +
            listOfNotNull(f.played.maxByOrNull { it.value }?.key?.let { "they love playing ${FriendsBrain.activityLabel(it)} together" }) +
            listOfNotNull(storyContext()))
        val lastReply = d.letters.lastOrNull { it.fromBlob && it.friendId == f.id }?.text
        val text = quietAsk(FriendsBrain.letterPrompt(f, d.friends.filter { it.id != f.id && it.name.isNotBlank() }, recent,
            GadgetState.blobName.value.ifBlank { "Blob" }, lastReply)) ?: return false
        prefs.edit().putLong("letter_last", System.currentTimeMillis()).apply()
        store.update { it.copy(letters = (it.letters + Letter(f.id, java.time.LocalDate.now().toString(), text.take(1200), System.currentTimeMillis())).takeLast(60)) }
        GadgetState.log("letter from ${f.name}")
        DayLog.event("got a letter from ${f.name}", "friend")
        if (!isQuiet() && !GadgetState.asleep.value) {
            GadgetState.react(Reaction.HOP)
            GadgetState.hold(HeldItem.LETTER, "✉️ Letter from ${f.name}", 60)
            // It reads the letter out, a sentence at a time, in its bubble.
            scope.launch {
                delay(2_000)
                for (part in text.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }.take(8)) {
                    if (GadgetState.talk.value != Talk.IDLE && GadgetState.talk.value != Talk.REPLIED) break
                    GadgetState.say(part, 5)
                    delay(5_200)
                }
            }
        }
        return true
    }

    /** About an hour after a letter arrives, the blob writes back (Pixel writes it in the blob's voice). */
    private suspend fun replyLoop() {
        while (true) {
            delay(20 * 60_000L)
            if (isQuiet()) continue
            val d = store.data
            val due = d.letters.lastOrNull { l ->
                !l.fromBlob && System.currentTimeMillis() - l.atMs > 60 * 60_000L &&
                    d.letters.none { it.fromBlob && it.friendId == l.friendId && it.atMs > l.atMs }
            } ?: continue
            replyTo(due)
        }
    }

    suspend fun replyTo(l: Letter): Boolean {
        val f = store.friend(l.friendId) ?: store.data.penpal?.takeIf { l.friendId == FriendsBrain.PENPAL_ID }?.let { p ->
            Friend(FriendsBrain.PENPAL_ID, p.name, "${p.bio} It lives in ${p.place}.")
        } ?: return false
        val name = GadgetState.blobName.value.ifBlank { "Blob" }
        val text = quietAsk(FriendsBrain.replyPrompt(name, f, l.text)) ?: return false
        store.update { it.copy(letters = (it.letters + Letter(f.id, java.time.LocalDate.now().toString(), text.take(1200),
            System.currentTimeMillis(), read = true, fromBlob = true)).takeLast(80)) }
        GadgetState.log("wrote back to ${f.name}")
        DayLog.event("wrote a letter back to ${f.name}", "friend")
        if (!isQuiet() && !GadgetState.asleep.value) {
            GadgetState.react(Reaction.HOP)
            GadgetState.hold(HeldItem.LETTER, "📮 To ${f.name}", 10)
            GadgetState.say("Posting my reply to ${f.name}! 📮", 10)
        }
        return true
    }

    fun markLetterRead(atMs: Long) {
        store.update { d -> d.copy(letters = d.letters.map { if (it.atMs == atMs) it.copy(read = true) else it }) }
    }

    // -- Stories --------------------------------------------------------------------------------

    /** A new story a day or so after the last one ends (Pixel writes it). */
    private suspend fun storyLoop() {
        delay(5 * 60_000L)
        while (true) {
            val d = store.data
            val named = d.friends.filter { it.name.isNotBlank() }
            val lastEnded = prefs.getLong("story_ended", 0L)
            if ((d.story == null || d.story.done) && named.isNotEmpty() && System.currentTimeMillis() - lastEnded > 20 * 3_600_000L && !isQuiet()) {
                val story = quietAsk(FriendsBrain.storyPrompt(GadgetState.blobCalled(), named, d.pastStories))
                    ?.let { FriendsBrain.parseStory(it, java.time.LocalDate.now().toString()) }
                if (story != null) {
                    // The cast as friend ids where they match (the blob itself can be in it too).
                    val ids = story.cast.mapNotNull { n -> named.firstOrNull { it.name.equals(n, ignoreCase = true) }?.id }
                    store.update { it.copy(story = story.copy(cast = ids.ifEmpty { listOf(named.first().id) })) }
                    GadgetState.log("a new story: ${story.title}")
                    DayLog.event("a new adventure began: \"${story.title}\"", "story")
                }
            }
            delay(30 * 60_000L)
        }
    }

    /** "Current story" for letters. */
    private fun storyContext(): String? = store.data.story?.takeIf { !it.done && it.at > 0 }?.let { s ->
        "the friends' current adventure \"${s.title}\", so far: " + s.chapters.take(s.at).joinToString(" / ") { it.line }
    }

    /** The next chapter of the story, once a day, in the first visit that can tell it. */
    private suspend fun playChapter(present: List<Friend>) {
        val s = store.data.story ?: return
        val beat = s.next ?: return
        val today = java.time.LocalDate.now().toString()
        if (s.lastPlayed == today) return
        val speaker = present.firstOrNull { it.name.equals(beat.who, ignoreCase = true) }
        when {
            speaker != null -> say(speaker, beat.line, 5_000)
            beat.who.equals(GadgetState.blobName.value, ignoreCase = true) || beat.who.equals("blob", true) -> blob(beat.line, 5_000)
            else -> blob("${beat.who} says: \"${beat.line}\"", 5_000)
        }
        val next = s.copy(at = s.at + 1, lastPlayed = today)
        store.update { it.copy(story = next) }
        DayLog.event("story \"${s.title}\" (${s.at + 1}/${s.chapters.size}): ${beat.line}", "story")
        if (next.done) {
            GadgetState.react(Reaction.CELEBRATE)
            blob("The end! 🎉 What an adventure.", 3_000)
            for (id in s.cast) store.friend(id)?.let { remember(it, "our adventure \"${s.title}\"") }
            store.update { it.copy(pastStories = (it.pastStories + s.title).takeLast(20)) }
            prefs.edit().putLong("story_ended", System.currentTimeMillis()).apply()
        } else blob(listOf("Ooh! 😮", "No way!", "And then what?! 👀", "I can't wait! ✨").random(), 2_500)
    }

    // -- The garden ---------------------------------------------------------------------

    /** The garden grows by the day, faster after rain. */
    private suspend fun gardenLoop() {
        if (prefs.getLong("garden_planted", 0L) == 0L) prefs.edit().putLong("garden_planted", System.currentTimeMillis()).apply()
        while (true) {
            val today = java.time.LocalDate.now()
            val w = GadgetState.weather.value?.summary.orEmpty().lowercase()
            if (("rain" in w || "shower" in w || "drizzle" in w) && prefs.getString("garden_rain_day", "") != today.toString()) {
                prefs.edit().putString("garden_rain_day", today.toString()).putInt("garden_rain", prefs.getInt("garden_rain", 0) + 1).apply()
            }
            val days = ((System.currentTimeMillis() - prefs.getLong("garden_planted", 0L)) / 86_400_000L).toInt()
            GadgetState.setGarden(FriendsBrain.gardenStage(days, prefs.getInt("garden_rain", 0), today.monthValue))
            delay(60 * 60_000L)
        }
    }

    // -- Gifts from Rusty ---------------------------------------------------------------------------

    /** Rusty sent a gift from the settings page: Puff unwraps it. [kind] hat / snack / room. */
    fun giftFromRusty(kind: String, value: String, note: String) = scope.launch {
        prefs.edit().putInt("gifts_sent", prefs.getInt("gifts_sent", 0) + 1).apply()
        GadgetState.lastTouch = System.currentTimeMillis()
        GadgetState.setAsleep(false)
        GadgetState.hold(HeldItem.GIFT, "🎁 From Rusty", 1)
        GadgetState.react(Reaction.HOP)
        GadgetState.say("A present? For me?! 🎁", 4)
        delay(3_500)
        GadgetState.react(Reaction.CELEBRATE)
        val what = when (kind) {
            "hat" -> Accessory.entries.firstOrNull { it.name.equals(value, true) }?.let { a ->
                addOutfit(a.name)
                GadgetState.setStyle(GadgetState.style.value.copy(accessory = a, untilMs = System.currentTimeMillis() + 86_400_000L))
                BlobStyles.save(context, GadgetState.style.value)
                "a ${a.name.lowercase().replace('_', ' ')}"
            } ?: "a hat"
            "snack" -> {
                val item = if (value.equals("coffee", true)) HeldItem.COFFEE else HeldItem.CAKE
                GadgetState.hold(item, "", 15)
                value.ifBlank { "a snack" }
            }
            "room" -> {
                val d = FriendsBrain.MARKET.firstOrNull { !life.has(it) } ?: FriendsBrain.GIFTS.firstOrNull { !life.has(it) }
                if (d != null) { life.unlock(d, " from Rusty", quietly = true); d.label } else { GadgetState.hold(HeldItem.STAR, "", 15); "a shiny star" }
            }
            else -> { GadgetState.hold(HeldItem.STAR, "", 15); "a shiny star" }
        }
        GadgetState.say("$what! Thank you, Rusty!! 🥰" + if (note.isNotBlank()) "  \"${note.take(80)}\"" else "", 12)
        DayLog.event("got a gift from Rusty: $what" + if (note.isNotBlank()) " (note: \"$note\")" else "", "gift")
    }

    // -- Wardrobe --------------------------------------------------------------------------------

    fun addOutfit(name: String) {
        if (name in store.data.wardrobe || Accessory.entries.none { it.name == name }) return
        store.update { it.copy(wardrobe = it.wardrobe + name) }
        DayLog.event("got ${name.lowercase().replace('_', ' ')} for its wardrobe", "wardrobe")
    }

    /** Wear [name] for the rest of today (from the Wardrobe tab, or Puff's own morning pick). */
    fun wearToday(name: String?, announce: Boolean = true) {
        val today = java.time.LocalDate.now()
        val endOfDay = today.plusDays(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val acc = name?.let { n -> Accessory.entries.firstOrNull { it.name == n } } ?: Accessory.NONE
        GadgetState.setStyle(GadgetState.style.value.copy(accessory = if (acc == Accessory.NONE) null else acc, untilMs = endOfDay))
        BlobStyles.save(context, GadgetState.style.value)
        store.update { it.copy(outfit = "$today|${acc.name}") }
        if (announce && acc != Accessory.NONE && !isQuiet()) {
            GadgetState.react(Reaction.HOP)
            GadgetState.say("Today's outfit: ${acc.name.lowercase().replace('_', ' ')}! ✨", 8)
        }
    }

    // -- Mornings: outfit ---------------------------------------------------

    private suspend fun morningLoop() {
        delay(2 * 60_000L)
        while (true) {
            val today = java.time.LocalDate.now()
            val h = life.hour()
            // A new season or holiday: Puff decorates its room for it (and packs the old things away).
            val theme = RoomTheme.of(today)
            if (prefs.getString("room_theme", "") != theme.name && !isQuiet() && h in 8..20 && life.idle()) {
                val old = prefs.getString("room_theme", "")
                prefs.edit().putString("room_theme", theme.name).apply()
                GadgetState.setRoomTheme(theme.name)
                if (!old.isNullOrEmpty()) {
                    GadgetState.react(Reaction.HOP)
                    GadgetState.say("Decorating for ${theme.label}! ${theme.emoji}", 15)
                    DayLog.event("decorated its room for ${theme.label}", "room")
                }
            }
            if (!isQuiet() && h in 6..11) {
                // Holiday outfits join the wardrobe on their day.
                when {
                    today.monthValue == 12 && today.dayOfMonth <= 26 -> addOutfit("SANTA_HAT")
                    today.monthValue == 10 && today.dayOfMonth >= 24 -> addOutfit("WITCH_HAT")
                    (today.monthValue == 1 && today.dayOfMonth == 1) || (today.monthValue == 7 && today.dayOfMonth == 4) -> addOutfit("PARTY_HAT")
                }
                if (!store.data.outfit.startsWith(today.toString())) {
                    val style = GadgetState.style.value
                    if (style.accessory != null && style.active(System.currentTimeMillis())) {
                        // Already wearing something (a gift, or Pixel's choice): that's today's outfit.
                        store.update { it.copy(outfit = "$today|${style.accessory.name}") }
                    } else {
                        val w = GadgetState.weather.value
                        val special = GadgetState.specialDays.value.any { runCatching { java.time.LocalDate.parse(it.date.take(10)) == today }.getOrDefault(false) }
                        wearToday(FriendsBrain.pickOutfit(store.data.wardrobe, w?.tempF, w?.summary.orEmpty(), special, rnd))
                    }
                }
                bigDayToday()?.takeIf { prefs.getString("bigday_luck", "") != today.toString() }?.let { note ->
                    prefs.edit().putString("bigday_luck", today.toString()).apply()
                    GadgetState.react(Reaction.CELEBRATE)
                    GadgetState.say("Good luck with $note today, Rusty! 🍀", 60)
                }
            }
            // Evening after a big day: how did it go?
            if (!isQuiet() && h in 18..21) bigDayToday()?.takeIf { prefs.getString("bigday_ask", "") != today.toString() }?.let { note ->
                prefs.edit().putString("bigday_ask", today.toString()).apply()
                GadgetState.say("How did $note go? 💛", 120)
            }
            delay(15 * 60_000L)
        }
    }

    // -- Tricks ----------------------------------------------------------------------------------

    /** One practice of [trick] (the Play tab): skill goes up, and Puff tries it. Returns the new skill. */
    fun practise(trick: String): Int {
        val today = java.time.LocalDate.now().toString()
        val days = prefs.getString("practice_days", "")!!.split(',').filter { it.isNotBlank() }.toSet()
        if (today !in days) prefs.edit().putString("practice_days", (days + today).sorted().takeLast(60).joinToString(",")).apply()
        val key = "$today|$trick"
        val goes = store.data.practice[key] ?: 0
        val skill = FriendsBrain.practiced(store.data.tricks[trick] ?: 0, goes)
        store.update { d -> d.copy(tricks = d.tricks + (trick to skill), practice = d.practice.filterKeys { it.startsWith(today) } + (key to goes + 1)) }
        scope.launch { perform(trick, skill) }
        if (skill >= 90 && (store.data.tricks[trick] ?: 0) < 90) DayLog.event("mastered ${FriendsBrain.TRICKS.toMap()[trick] ?: trick}", "trick")
        return skill
    }

    /** Puff does the trick: the better it knows it, the likelier it lands. */
    suspend fun perform(trick: String, skill: Int) {
        val lands = rnd.nextInt(100) < skill + 10
        when (trick) {
            "spin" -> GadgetState.react(if (lands) Reaction.WANDER else Reaction.DIZZY)
            "juggle" -> {
                GadgetState.hold(HeldItem.BALLOON, "", 1)
                repeat(3) { GadgetState.react(Reaction.HOP); delay(Reaction.HOP.ms) }
                if (!lands) GadgetState.hold(HeldItem.NONE, "", 0)
            }
            "backflip" -> GadgetState.react(if (lands) Reaction.STRETCH else Reaction.STARTLE).also { GadgetState.react(Reaction.HOP) }
            "playdead" -> { GadgetState.setMood(if (lands) Mood.SLEEPY else Mood.WINK, 4) }
            else -> GadgetState.react(Reaction.WAVE)
        }
        delay(1_500)
        if (lands) {
            if (skill >= 90) GadgetState.react(Reaction.CELEBRATE)
            GadgetState.say(if (skill >= 90) "Ta-da! ✨ Perfect!" else "Did it! 😄", 4)
        } else GadgetState.say(listOf("Oops! 😅", "Wobbly… 🙃", "Almost! 🤏").random(), 4)
    }

    // -- The Sunday paper --------------------------------------------------------------------------

    // -- Special visits ---------------------------------------------------------------------------

    private suspend fun birthday(f: Friend, second: Friend?, v: FriendVisit): String {
        GadgetState.scene { it.copy(party = true) }
        GadgetState.react(Reaction.CELEBRATE)
        blob("Happy birthday, ${f.shownName}! 🎂", 3_500)
        GadgetState.hold(HeldItem.CAKE, "🎂 ${f.shownName}'s birthday", ((v.untilMs - System.currentTimeMillis()) / 60_000).toInt().coerceAtLeast(1))
        say(f, if (f.p == Personality.SHY) "…for me? 🥹" else "My birthday!! 🎉", 3_000)
        second?.let { s ->
            say(s, "I got you something! 🎁", 3_000)
            say(f, "Aww, thank you!! 💛", 2_500)
        }
        untilEnd(v)
        GadgetState.scene { it.copy(party = false) }
        remember(f, "my birthday party")
        addOutfit("PARTY_HAT")
        return "${f.shownName}'s birthday party"
    }

    private suspend fun cheerUp(f: Friend): String {
        val reason = FriendsBrain.sadReason(f, rnd)
        GadgetState.scene { it.copy(sadId = f.id) }
        say(f, reason, 4_000)
        GadgetState.setMood(Mood.WORRIED, 6)
        blob("Oh no! 🥺 Come here.", 2_500)
        hug(3_000)
        blob(listOf("It's okay. I'm here. 💛", "Let's have some tea and forget about it. ☕", "You're the best, you know that? ✨").random(), 3_500)
        GadgetState.scene { it.copy(sadId = null) }
        say(f, "Thanks, ${GadgetState.blobName.value.ifBlank { "friend" }}. I feel better. 💛", 3_500)
        remember(f, "when ${GadgetState.blobCalled()} cheered me up (${reason.replace(Regex("[^\\p{L}\\p{N} ,'!?]+$"), "").trimEnd('…', ' ')})")
        store.update { it.copy(owesThanks = f.id) }
        return "cheered up ${f.shownName}, who was sad"
    }

    // -- Group photo day -------------------------------------------------------------------------------

    private suspend fun groupPhoto(f: Friend, v: FriendVisit): String {
        GadgetState.scene { it.copy(groupPhoto = true) }
        blob("It's group photo day! 📸", 3_000)
        say(f, "Everyone squeeze in! 🤗", 3_000)
        delay(2_000)
        blob("Say cheese! 🧀", 2_000)
        GadgetState.scene { it.copy(flashAt = System.currentTimeMillis()) }
        delay(400)
        val month = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy", java.util.Locale.US))
        life.groupSnapshot("Group photo · $month")
        GadgetState.react(Reaction.CELEBRATE)
        say(f, "Perfect! 📸", 2_500)
        for (id in v.friendIds) store.friend(id)?.let { remember(it, "the group photo in $month") }
        untilEnd(v)
        GadgetState.scene { it.copy(groupPhoto = false) }
        return "everyone came over for the $month group photo"
    }

    // -- Puff's trips ------------------------------------------------------------------------------------

    /** Now and then Puff goes round to a friend's for half an hour, and comes back with a souvenir. */
    private suspend fun tripLoop() {
        delay(20 * 60_000L)
        while (true) {
            delay(15 * 60_000L)
            val today = java.time.LocalDate.now().toString()
            if (!on() || isQuiet() || life.hour() !in 11..17 || prefs.getString("trip_day", "") == today) continue
            if (GadgetState.friend.value != null || !life.idle() || System.currentTimeMillis() - GadgetState.lastHuman < 10 * 60_000L) continue
            if (rnd.nextFloat() > 0.25f) continue
            store.data.friends.filter { it.name.isNotBlank() }.randomOrNull()?.let { trip(it) }
        }
    }

    fun trip(f: Friend, minutes: Int = 20 + rnd.nextInt(21)) = scope.launch {
        prefs.edit().putString("trip_day", java.time.LocalDate.now().toString()).apply()
        GadgetState.react(Reaction.WAVE)
        GadgetState.hold(HeldItem.SUITCASE, "", 1)
        GadgetState.say("I'm off to ${f.shownName}'s! Back soon! 🧳", 5)
        delay(4_000)
        val until = System.currentTimeMillis() + minutes * 60_000L
        GadgetState.setTrip(GadgetState.Trip(f.id, until))
        DayLog.event("went round to ${f.shownName}'s house", "trip")
        GadgetState.log("trip to ${f.shownName}'s")
        val left = GadgetState.lastHuman
        // Home on time, or straight away if Rusty needs it (a touch or talking).
        while (System.currentTimeMillis() < until && GadgetState.lastHuman == left &&
            (GadgetState.talk.value == Talk.IDLE || GadgetState.talk.value == Talk.REPLIED)) delay(5_000)
        GadgetState.setTrip(null)
        val souvenir = when {
            FriendsBrain.MARKET.any { !life.has(it) } && rnd.nextBoolean() -> FriendsBrain.MARKET.first { !life.has(it) }.let { life.unlock(it, " from ${f.shownName}'s", quietly = true); it.label }
            listOf("FLOWER", "BOW", "SCARF", "CROWN", "SUNGLASSES").any { it !in store.data.wardrobe } ->
                listOf("FLOWER", "BOW", "SCARF", "CROWN", "SUNGLASSES").first { it !in store.data.wardrobe }.also { addOutfit(it) }.lowercase().let { "a $it" }
            else -> listOf("a smooth pebble", "a pressed flower", "a tiny drawing", "a cookie for later", "a friendship bracelet").random()
        }
        GadgetState.react(Reaction.HOP)
        GadgetState.hold(HeldItem.GIFT, "🎁 Souvenir from ${f.shownName}'s", 10)
        GadgetState.say("I'm back! ${f.shownName} gave me $souvenir! 😊", 10)
        val (e, gift) = FriendsBrain.SOUVENIRS.random()
        val today = java.time.LocalDate.now().toString()
        prefs.edit().putString("souvenir", "$e|$gift|${f.shownName}|$today").apply()
        scope.launch {
            delay(12_000)
            GadgetState.setSouvenir(GadgetState.Souvenir(e, gift, f.shownName, today))
            GadgetState.say("And I brought YOU something, Rusty: $gift! $e", 10)
        }
        DayLog.event("came home from ${f.shownName}'s with $souvenir", "trip")
        remember(f, "when ${GadgetState.blobCalled()} came over to my house")
    }

    // -- Rusty's news ------------------------------------------------------------------------------------

    /**
     * Pixel tells the blob how Rusty's doing: [feeling] rough / great / big_day (with [date],
     * yyyy-MM-dd, and a [note] like "your maths test"). Puff and friends respond.
     */
    fun rustyFeels(feeling: String, note: String, date: String?): String {
        when (feeling.lowercase()) {
            "rough", "sad", "bad" -> {
                GadgetState.hold(HeldItem.HEART, "💛 For Rusty", 120)
                GadgetState.setMood(Mood.WORRIED, 20)
                GadgetState.say("Sending you a big hug, Rusty. 💛", 30)
                DayLog.event("heard Rusty was having a rough day" + if (note.isNotBlank()) " ($note)" else "", "rusty")
                // A friend comes over to cheer Rusty up (soon, if nobody's over).
                scope.launch {
                    delay(90_000)
                    if (GadgetState.friend.value == null && !isQuiet()) visit("cheerrusty")
                }
            }
            "great", "good", "happy" -> {
                GadgetState.react(Reaction.CELEBRATE)
                GadgetState.say("Yay, Rusty! 🎉" + if (note.isNotBlank()) " $note!" else "", 30)
                GadgetState.setMood(Mood.HAPPY, 600)
                DayLog.event("celebrated Rusty's good news" + if (note.isNotBlank()) " ($note)" else "", "rusty")
            }
            else -> {
                val d = date?.takeIf { runCatching { java.time.LocalDate.parse(it) }.isSuccess } ?: return "date (yyyy-MM-dd) is needed for a big day"
                val list = (prefs.getString("big_days", "")!!.split('\n').filter { it.isNotBlank() } + "$d|${note.take(60)}").takeLast(20)
                prefs.edit().putString("big_days", list.joinToString("\n")).apply()
                GadgetState.say("I'll remember! 📝 Good luck with ${note.ifBlank { "your big day" }}!", 15)
            }
        }
        return "ok"
    }

    private suspend fun cheerRusty(f: Friend, second: Friend?): String {
        say(f, "We heard you're having a rough day, Rusty… 💛", 4_000)
        second?.let { say(it, "We made you a card! 💌", 3_000) }
        hug(3_000)
        blob("You've got this, Rusty. We're all here. 🤗", 4_000)
        return "came over to cheer Rusty up"
    }

    /** Big days: good luck in the morning, "how did it go?" in the evening. */
    private fun bigDayToday(): String? {
        val today = java.time.LocalDate.now().toString()
        return prefs.getString("big_days", "")!!.split('\n').firstOrNull { it.startsWith("$today|") }?.substringAfter('|')
    }

    // -- Mornings and bedtime ------------------------------------------------------------------------------

    /** The first touch of the day: a hug, the weather in a line, and what's first on today. */
    fun morningHello() = scope.launch {
        val w = GadgetState.weather.value
        val first = GadgetState.agendaToday().firstOrNull()
        GadgetState.react(Reaction.HOP)
        GadgetState.hold(HeldItem.HEART, "", 1)
        val lines = listOfNotNull(
            "Good morning, Rusty! 🤗",
            w?.let { "Today: ${it.summary.lowercase()}, up to ${it.highF}°." },
            bigDayToday()?.let { "Big day: $it! Good luck! 🍀" }
                ?: first?.let { "First up: ${if (it.time.isNotBlank()) "${it.time} " else ""}${it.title}." } ?: "Nothing on the calendar today. 😌",
        )
        for (line in lines) {
            GadgetState.say(line, 5)
            delay(4_500)
        }
    }

    /** A one-line bedtime story starring Puff's friends. */
    fun bedtimeStory(): String {
        val friends = store.data.friends.filter { it.name.isNotBlank() }
        val a = friends.randomOrNull()?.name ?: "a little blob"
        val b = friends.filter { it.name != a }.randomOrNull()?.name ?: "the moon"
        val pet = "a sleepy snail"
        return listOf(
            "Once upon a time, $pet raced the moon… and won. The end. 🌙",
            "Once, $a found a star in the garden and gave it to $b to keep. Goodnight. ⭐",
            "Long ago, $a and $b built a pillow fort so big the clouds came to sleep in it. 💤",
            "Once upon a time, ${GadgetState.BlobCalled()} wished for a dream, and the night said yes. 🌌",
            "$pet carried a tiny lantern all the way to dreamland, very slowly. 🏮",
        ).random()
    }

    // -- The pen pal --------------------------------------------------------------------------------

    /** A pen pal far away (Pixel invents it), who writes every few days; Puff writes back like to anyone. */
    private suspend fun penpalLoop() {
        delay(8 * 60_000L)
        while (true) {
            if (store.data.penpal == null && !isQuiet()) {
                quietAsk(FriendsBrain.penpalPrompt(GadgetState.blobCalled()))?.let(FriendsBrain::parsePenpal)?.let { p ->
                    store.update { it.copy(penpal = p) }
                    GadgetState.log("pen pal: ${p.name} in ${p.place}")
                    DayLog.event("got a pen pal: ${p.name}, who lives in ${p.place}", "letter")
                }
            }
            val p = store.data.penpal
            val gap = (4 + ((p?.name?.length ?: 0) % 3)) * 86_400_000L
            if (p != null && !isQuiet() && life.hour() in 10..19 && System.currentTimeMillis() - prefs.getLong("penpal_last", 0L) > gap) penpalLetterNow()
            delay(60 * 60_000L)
        }
    }

    suspend fun penpalLetterNow(birthday: Boolean = false): Boolean {
        val p = store.data.penpal ?: return false
        val lastReply = store.data.letters.lastOrNull { it.fromBlob && it.friendId == FriendsBrain.PENPAL_ID }?.text
        val text = quietAsk(FriendsBrain.penpalLetterPrompt(p, GadgetState.blobCalled(), lastReply, birthday)) ?: return false
        prefs.edit().putLong("penpal_last", System.currentTimeMillis()).apply()
        store.update { d ->
            d.copy(letters = (d.letters + Letter(FriendsBrain.PENPAL_ID, java.time.LocalDate.now().toString(), text.take(1200), System.currentTimeMillis())).takeLast(80),
                postcards = (d.postcards + Postcard(FriendsBrain.PENPAL_ID, "Greetings from ${p.place}! 🌍", java.time.LocalDate.now().toString())).takeLast(12))
        }
        life.unlock(Decor.POSTCARDS, " from ${p.place}", quietly = true)
        DayLog.event("got a letter from its pen pal ${p.name} in ${p.place}", "letter")
        if (!isQuiet()) {
            GadgetState.react(Reaction.HOP)
            if (birthday) {
                GadgetState.hold(HeldItem.PACKAGE, "📦 Parcel from ${p.name} (${p.place})", 60)
                GadgetState.say("A birthday parcel from my pen pal in ${p.place}! 📦", 12)
            } else {
                GadgetState.hold(HeldItem.LETTER, "🌍 Letter from ${p.name} (${p.place})", 60)
                GadgetState.say("A letter from my pen pal in ${p.place}! 🌍", 12)
            }
        }
        return true
    }

    // -- Books ---------------------------------------------------------------------------------------

    /** A new book on Puff's shelf, read by [reader] (Pixel makes up the title and the review). */
    suspend fun addBook(reader: String) {
        val b = quietAsk(FriendsBrain.bookPrompt(reader, store.data.books.map { it.title }.takeLast(15)))
            ?.let { FriendsBrain.parseBook(it, reader, java.time.LocalDate.now().toString()) } ?: return
        store.update { it.copy(books = (it.books + b).takeLast(40)) }
        DayLog.event("$reader read \"${b.title}\"", "book")
        GadgetState.log("book: ${b.title}")
    }

    // -- Relatives and weather adventures -------------------------------------------------------------

    // -- Puff's birthday and stargazing ----------------------------------------

    private suspend fun puffBirthday(f: Friend, v: FriendVisit): String {
        val today = java.time.LocalDate.now()
        store.update { it.copy(puffBirthdays = (it.puffBirthdays + today.year.toString()).distinct().takeLast(10)) }
        val everyone = v.friendIds.mapNotNull { store.friend(it) }
        // Lights off while they hide...
        GadgetState.scene { it.copy(lightsOff = true) }
        blob("Huh? Why's it so dark in here? 🤔", 3_500)
        delay(1_500)
        GadgetState.scene { it.copy(lightsOff = false, party = true) }
        everyone.forEach { GadgetState.setFriendSay("SURPRISE!! 🎉", it.id) }
        GadgetState.react(Reaction.CELEBRATE)
        delay(3_000)
        GadgetState.clearFriendSay()
        blob("For ME?! 🥹 You remembered!", 3_000)
        val keep = GadgetState.style.value
        GadgetState.setStyle(keep.copy(accessory = Accessory.PARTY_HAT, untilMs = v.untilMs))
        GadgetState.hold(HeldItem.CAKE, "🎂 ${GadgetState.BlobCalled()}'s birthday", ((v.untilMs - System.currentTimeMillis()) / 60_000).toInt().coerceAtLeast(1))
        // A present from each of them.
        val presents = everyone.map { fr ->
            val (e, what) = FriendsBrain.presentFor(fr.p)
            say(fr, "Happy birthday! I got you $what! $e", 3_200)
            blob("Thank you, ${fr.shownName}! 💛", 2_000)
            "$what from ${fr.shownName}"
        }
        DayLog.event("had a surprise birthday party; presents: ${presents.joinToString()}", "birthday")
        scope.launch { delay(4_000); life.groupSnapshot("${GadgetState.BlobCalled()}'s birthday · ${today.year}") }
        // The pen pal's parcel arrives later in the day.
        if (store.data.penpal != null) scope.launch { delay(20 * 60_000L); penpalLetterNow(birthday = true) }
        untilEnd(v)
        GadgetState.setStyle(keep)
        GadgetState.scene { it.copy(party = false) }
        addOutfit("PARTY_HAT")
        return "a surprise party for ${GadgetState.blobCalled()}'s birthday"
    }

    /** Pixel's answer to [prompt] if it comes within [ms] (else null; a late answer is just dropped). */
    private suspend fun <T> askWithin(prompt: String, ms: Long, parse: (String) -> T?): T? {
        var got: T? = null
        var done = false
        scope.launch { got = quietAsk(prompt)?.let(parse); done = true }
        val until = System.currentTimeMillis() + ms
        while (!done && System.currentTimeMillis() < until) delay(1_000)
        return got
    }

    private suspend fun stargazing(f: Friend, second: Friend?, v: FriendVisit): String {
        GadgetState.scene { it.copy(stargaze = true) }
        val today = java.time.LocalDate.now()
        val (moon, phase) = FriendsBrain.moonPhase(today)
        val shower = FriendsBrain.meteorShower(today)
        say(f, "Look at all the stars! ✨", 3_000)
        blob("And the moon's a $phase tonight! $moon", 3_500)
        shower?.let { say(f, "It's $it tonight! Wish on a shooting star! 🌠", 4_000) }
        val by = listOfNotNull(GadgetState.BlobCalled(), f.shownName, second?.shownName).joinToString(" & ")
        val c = askWithin(FriendsBrain.constellationPrompt(f.shownName, GadgetState.blobCalled(), store.data.constellations.map { it.name }.takeLast(20)), 45_000) {
            FriendsBrain.parseConstellation(it, by, today.toString())
        }
        if (c != null) {
            store.update { it.copy(constellations = (it.constellations + c).takeLast(40)) }
            say(f, "Those stars look like ${c.shape.trimEnd('.')}! 🔭", 3_800)
            blob("Let's call it ${c.name}! ⭐", 3_500)
            DayLog.event("named a constellation \"${c.name}\" with ${f.shownName}", "stars")
        }
        untilEnd(v)
        GadgetState.scene { it.copy(stargaze = false) }
        return "stargazing ($phase${shower?.let { ", $it" } ?: ""}${c?.let { "; named ${it.name}" } ?: ""})"
    }

    // -- Sniffles ------------------------------------------------------------------------------------

    private suspend fun snifflesLoop() {
        delay(60_000)
        while (true) {
            val today = java.time.LocalDate.now()
            if (prefs.getString("sniffle_day", "") == today.toString()) {
                GadgetState.setSniffles(true, prefs.getBoolean("sniffle_blanket", false))
                if (!isQuiet() && life.idle() && !GadgetState.asleep.value && rnd.nextInt(5) == 0) {
                    GadgetState.react(Reaction.STARTLE)
                    GadgetState.say(listOf("Achoo! 🤧", "*sniff* 🤧", "Ah… ah… ACHOO! 🤧").random(), 4)
                }
            } else {
                if (GadgetState.sniffles.value) {
                    GadgetState.setSniffles(false, false)
                    if (!isQuiet()) GadgetState.say("I feel all better today! 💪", 10)
                }
                if (on() && Prefs(context).puffSniffles && life.hour() in 7..10 && prefs.getString("sniffle_checked", "") != today.toString()) {
                    prefs.edit().putString("sniffle_checked", today.toString()).apply()
                    val since = prefs.getString("sniffle_last", "")!!.let { runCatching { java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.parse(it), today) }.getOrDefault(99L) }
                    if (since >= 14 && rnd.nextInt(12) == 0) startSniffles()
                }
            }
            delay(10 * 60_000L)
        }
    }

    fun startSniffles(soonMin: Long = 40) {
        val today = java.time.LocalDate.now().toString()
        prefs.edit().putString("sniffle_day", today).putString("sniffle_last", today).putBoolean("sniffle_blanket", false).apply()
        GadgetState.setSniffles(true, false)
        DayLog.event("woke up with the sniffles", "sick")
        if (!isQuiet()) GadgetState.say("*sniff* I think I've got a cold… 🤧", 10)
    }

    /** Sniffly days switched off: better straight away. */
    fun stopSniffles() {
        prefs.edit().putString("sniffle_day", "").apply()
        GadgetState.setSniffles(false, false)
    }

    /** Rusty tapped Puff on a sniffly day: tucked in under a blanket. */
    fun tuckBlanket(): Boolean {
        if (!GadgetState.sniffles.value || GadgetState.sickBlanket.value) return false
        prefs.edit().putBoolean("sniffle_blanket", true).apply()
        GadgetState.react(Reaction.TUCK_IN)
        GadgetState.setSniffles(true, true)
        GadgetState.say("Thank you, Rusty… so cozy 🥹", 8)
        DayLog.event("Rusty tucked it in with a blanket", "sick")
        return true
    }

    // -- The new house on the street -------------------------------------------------------------------

    private fun startBuilding() {
        if (store.data.building != null || store.data.friends.size >= 12) return
        store.update { it.copy(building = Building(java.time.LocalDate.now().toString())) }
        DayLog.event("saw builders start a new house down the street", "friend")
        GadgetState.log("a new house is going up")
    }

    /** The playground: a house nearly done (or, if one's going up, finished now). */
    fun buildNow() {
        val b = store.data.building
        val start = java.time.LocalDate.now().minusDays(if (b == null) 6 else 7).toString()
        store.update { it.copy(building = (b ?: Building(start)).copy(startDate = start)) }
        scope.launch { checkBuilding() }
    }

    private suspend fun buildingLoop() {
        delay(5 * 60_000L)
        while (true) {
            checkBuilding()
            delay(15 * 60_000L)
        }
    }

    private fun checkBuilding() {
        val b = store.data.building ?: return
        val days = java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.parse(b.startDate), java.time.LocalDate.now())
        when {
            b.friendId.isEmpty() && days >= 7 -> {
                val f = newFriend()
                store.update { it.copy(building = b.copy(friendId = f.id)) }
                DayLog.event("someone moved into the new house down the street!", "friend")
                if (!isQuiet()) GadgetState.say("Someone's moved into the new house! 🏡", 10)
            }
            b.friendId.isNotEmpty() && !b.welcomed -> {
                val f = store.friend(b.friendId)
                if (f == null) store.update { it.copy(building = null) }
                else if (f.name.isNotBlank() && on() && !isQuiet() && life.idle() && GadgetState.friend.value == null && life.hour() in 10..19) visit("welcome", null, f.name)
            }
            b.welcomed && days >= 9 -> store.update { it.copy(building = null) }
        }
    }

    private suspend fun welcome(f: Friend): String {
        say(f, "Hi! I just moved in down the street! 👋", 3_500)
        GadgetState.hold(HeldItem.BAG, "🧺 Welcome basket", 3)
        blob("Welcome to the street, ${f.shownName}! I made you a basket! 🧺", 3_500)
        say(f, "For me? Thank you!! 🥹", 3_000)
        blob("There's jam, cookies and a map of the neighbourhood! 🗺️", 3_500)
        store.update { d -> d.copy(building = d.building?.copy(welcomed = true)) }
        DayLog.event("welcomed the new neighbour ${f.shownName} with a basket", "friend")
        return "welcomed new neighbour ${f.shownName} with a basket"
    }

    // -- Board games and snow -------------------------------

    private suspend fun boardGame(f: Friend, v: FriendVisit): String {
        var a = 1
        var b = 1
        GadgetState.scene { it.copy(snakes = Triple(a, b, 0)) }
        say(f, "Snakes and ladders? 🎲", 2_500)
        var puffTurn = true
        while (a < 30 && b < 30 && System.currentTimeMillis() < v.untilMs - 15_000) {
            val roll = 1 + rnd.nextInt(6)
            val (to, what) = FriendsBrain.snakesMove(if (puffTurn) a else b, roll)
            if (puffTurn) a = to else b = to
            GadgetState.scene { it.copy(snakes = Triple(a, b, roll)) }
            val line = "🎲 $roll!" + when (what) { "ladder" -> " Up the ladder! 🪜"; "snake" -> " Oh no, a snake! 🐍"; else -> "" }
            if (puffTurn) blob(line, 2_200) else say(f, line, 2_200)
            puffTurn = !puffTurn
            delay(700)
        }
        val winner = when { a >= 30 -> GadgetState.BlobCalled(); b >= 30 -> f.shownName; else -> null }
        if (winner != null) store.update { it.copy(boardWins = it.boardWins + (winner to (it.boardWins[winner] ?: 0) + 1)) }
        when {
            a >= 30 -> { GadgetState.react(Reaction.CELEBRATE); blob("I won! 🎉", 3_000); say(f, "Rematch! 😤", 2_500) }
            b >= 30 -> { say(f, "I won! 🏆", 3_000); blob("Good game! 🤝", 2_500) }
            else -> blob("Let's finish it next time! 🎲", 2_500)
        }
        untilEnd(v)
        GadgetState.scene { it.copy(snakes = null) }
        return "snakes and ladders" + (winner?.let { " ($it won)" } ?: "")
    }

    private suspend fun firstSnow(f: Friend, v: FriendVisit): String {
        val others = v.friendIds.drop(1).mapNotNull { store.friend(it) }
        say(f, "IT'S SNOWING!! ❄️", 3_000)
        others.take(3).forEach { GadgetState.setFriendSay("Snow! Snow! ☃️", it.id) }
        delay(2_500)
        GadgetState.clearFriendSay()
        blob("The first snow! Let's build a snowman! ⛄", 3_500)
        delay(4_000)
        prefs.edit().putBoolean("snowman", true).apply()
        GadgetState.setSnowman(true)
        GadgetState.react(Reaction.CELEBRATE)
        say(f, "Perfect! He's staying till he melts! ⛄", 3_500)
        DayLog.event("built a snowman in its room on the first snow of the winter", "snow")
        untilEnd(v)
        return "the first snow, and a snowman"
    }

    private fun addCard(holiday: String, date: String, text: String) {
        if (store.data.cards.any { it.date == date }) return
        val from = (listOf(GadgetState.BlobCalled()) + store.data.friends.filter { it.name.isNotBlank() }.map { it.shownName }).joinToString(", ")
        store.update { it.copy(cards = (it.cards + HolidayCard(holiday, date, text, from)).takeLast(20)) }
    }

    // -- Puff's sleepover at a friend's house -----------------------------------------------------------

    /** Puff stays the night at [f]'s (home when quiet hours end; [testMs] brings it back sooner, for the playground). */
    fun sleepOverAt(f: Friend, testMs: Long? = null) {
        prefs.edit().putLong("away_sleepover_last", System.currentTimeMillis()).apply()
        val trip = GadgetState.Trip(f.id, System.currentTimeMillis() + (testMs ?: (12 * 60 * 60_000L)), overnight = true)
        GadgetState.setTrip(trip)
        if (testMs != null) scope.launch { delay(testMs); if (GadgetState.trip.value == trip) homeFromSleepover(trip) }
        DayLog.event("went for a sleepover at ${f.shownName}'s house", "trip")
        GadgetState.log("sleepover at ${f.shownName}'s")
        remember(f, "when ${GadgetState.blobCalled()} slept over at my house")
    }

    private fun homeFromSleepover(t: GadgetState.Trip) {
        GadgetState.setTrip(null)
        val who = store.friend(t.friendId)?.shownName ?: "my friend"
        GadgetState.react(Reaction.HOP)
        GadgetState.say("I'm home! I slept over at $who's 😴💛", 10)
        DayLog.event("came home from the sleepover at $who's", "trip")
    }

    // -- Every half hour: Rusty's letter, anniversaries, the weather calendar, the snowman, house upgrades, cards --

    private suspend fun dailyLoop() {
        delay(4 * 60_000L)
        while (true) {
            runCatching { daily() }.onFailure { GadgetState.log("daily: ${it.message}") }
            delay(30 * 60_000L)
        }
    }

    private suspend fun daily() {
        val today = java.time.LocalDate.now()
        val h = life.hour()
        val d = store.data
        // An overnight trip that somehow outlived the morning.
        GadgetState.trip.value?.takeIf { it.overnight && System.currentTimeMillis() > it.untilMs }?.let { homeFromSleepover(it) }
        // The weather calendar: today's doodle and mood (updated through the day; the evening's one sticks).
        val w = GadgetState.weather.value
        val visits = d.today.filterKeys { it.startsWith("$today|") }.values.sumOf { it.size }
        val mood = when {
            GadgetState.sniffles.value -> "🤧"
            today.year.toString() in d.puffBirthdays && "%02d-%02d".format(today.monthValue, today.dayOfMonth) == Prefs(context).blobBirthday -> "🥳"
            visits >= 3 -> "😄"
            visits >= 1 -> "🙂"
            else -> "😌"
        }
        val cell = "${FriendsBrain.weatherDoodle(w?.summary, w?.tempF)}|$mood"
        if (w != null && d.weatherDays[today.toString()] != cell)
            store.update { it.copy(weatherDays = (it.weatherDays + (today.toString() to cell)).toSortedMap().entries.toList().takeLast(400).associate { e -> e.key to e.value }) }
        // The snowman thaws on a warm day.
        if (GadgetState.snowman.value && w != null && w.tempF >= 42 && "snow" !in w.summary.lowercase()) {
            prefs.edit().putBoolean("snowman", false).apply()
            GadgetState.setSnowman(false)
            DayLog.event("found its snowman had melted", "snow")
            if (!isQuiet()) GadgetState.say("Oh no… the snowman melted. 💧 Bye, snowman!", 10)
        }
        if (!on()) return
        // Friendship anniversaries: they swap cards (theirs goes on the wall).
        for (f in d.friends.filter { it.name.isNotBlank() }) {
            val mark = FriendsBrain.anniversary(f.metMs, today) ?: continue
            val key = "anniv_${f.id}_$today"
            if (prefs.getBoolean(key, false)) continue
            prefs.edit().putBoolean(key, true).apply()
            store.update { it.copy(postcards = (it.postcards + Postcard(f.id, "Happy $mark of friendship! 💌", today.toString())).takeLast(12)) }
            life.unlock(Decor.POSTCARDS, " from ${f.shownName}", quietly = true)
            DayLog.event("swapped cards with ${f.shownName}: $mark of friendship", "friend")
            if (!isQuiet()) {
                GadgetState.hold(HeldItem.HEART, "💌 $mark with ${f.shownName}", 60)
                GadgetState.say("It's $mark since I met ${f.shownName}! We made each other cards 💌", 10)
            }
        }
        // Houses grow as friends get closer.
        for (f in d.friends.filter { it.name.isNotBlank() }) {
            val lvl = FriendsBrain.upgradeLevel(f.closeness)
            if (lvl <= (d.upgrades[f.id] ?: 0)) continue
            store.update { it.copy(upgrades = it.upgrades + (f.id to lvl)) }
            val what = FriendsBrain.upgradeName(f.p, lvl)
            DayLog.event("${f.shownName} built $what onto its house", "friend")
            if (!isQuiet()) GadgetState.say("${f.shownName} built $what! 🏗️ Come see on the street", 10)
        }
        // A holiday with no card yet (no time to make one yesterday): a quick one this morning.
        FriendsBrain.holiday(today)?.let { hol ->
            if (d.cards.none { it.date == today.toString() }) addCard(hol, today.toString(), "Happy $hol, Rusty! Love from all of us. 💛")
        }
        // Sunday evening: Puff writes Rusty a letter about its week.
        if (today.dayOfWeek == java.time.DayOfWeek.SUNDAY && h in 18..21 && prefs.getString("rusty_letter_day", "") != today.toString() && !isQuiet()) {
            prefs.edit().putString("rusty_letter_day", today.toString()).apply()
            writeToRusty()
        }
    }

    suspend fun writeToRusty(): Boolean {
        val today = java.time.LocalDate.now()
        val weekAgo = today.minusDays(7).toString()
        val d = store.data
        val facts = buildList {
            GadgetState.diary.value.filter { it.date >= weekAgo }.takeLast(7).forEach { add(it.text.take(140)) }
            d.books.filter { it.date >= weekAgo }.forEach { add("read \"${it.title}\"") }
            d.stickers.filterValues { it >= weekAgo }.keys.mapNotNull { id -> FriendsBrain.STICKERS.firstOrNull { it.id == id }?.title }.forEach { add("earned the sticker $it") }
        }.ifEmpty { listOf("a quiet, cozy week at home") }
        val text = quietAsk(FriendsBrain.letterToRustyPrompt(GadgetState.BlobCalled(), facts)) ?: return false
        store.update { it.copy(letters = (it.letters + Letter("rusty", today.toString(), text.take(1500), System.currentTimeMillis(), read = false, fromBlob = true)).takeLast(80)) }
        DayLog.event("wrote Rusty a letter about its week", "letter")
        if (!isQuiet()) {
            GadgetState.hold(HeldItem.LETTER, "💌 A letter for Rusty", 120)
            GadgetState.say("I wrote you a letter, Rusty! It's in my ✉️ mailbox 💌", 12)
        }
        return true
    }

    // -- For the phone app --------------------------------------------------------------------------

    /** One line about what Puff's up to, for the phone's widget. */
    fun doingLine(): String {
        val d = store.data
        GadgetState.trip.value?.let { t ->
            val who = d.friends.firstOrNull { it.id == t.friendId }?.shownName ?: "a friend"
            return if (t.overnight) "Sleeping over at $who's 😴" else "Round at $who's house 🧳"
        }
        if (GadgetState.asleep.value || GadgetState.tuckedIn.value) return "Fast asleep 😴"
        GadgetState.friend.value?.let { v ->
            val names = v.friendIds.mapNotNull { id -> d.friends.firstOrNull { it.id == id }?.shownName ?: GadgetState.guest.value?.takeIf { it.id == id }?.shownName }
            val who = when {
                names.size > 3 -> "everyone"
                else -> names.joinToString(" & ")
            }
            return FriendsBrain.activityLabel(v.activity).replaceFirstChar { it.uppercase() } + if (who.isNotEmpty()) " with $who" else ""
        }
        if (GadgetState.sniffles.value) return "Got the sniffles 🤧"
        return if (isQuiet()) "Winding down for the night 🌙" else "Hanging out at home 🏠"
    }

    /** Where everyone is on the street, for the phone's Neighbourhood map. */
    fun streetInfo() = kotlinx.serialization.json.buildJsonObject {
        val now = System.currentTimeMillis()
        val visiting = GadgetState.friend.value?.friendIds.orEmpty().toSet()
        val h = life.hour()
        put("where", kotlinx.serialization.json.buildJsonObject {
            store.data.friends.forEach { f ->
                val w = GadgetState.trip.value?.takeIf { it.friendId == f.id }?.let { FriendsBrain.Where.HOME } ?: FriendsBrain.whereabouts(f, visiting, now, h)
                put(f.id, w.name)
            }
        })
        FriendsBrain.festival(java.time.LocalDate.now(), h)?.let { put("festival", it.name) }
            ?: GadgetState.festivalPreview.value?.takeIf { it.second > now }?.let { put("festival", it.first) }
        GadgetState.friend.value?.let { put("activity", it.activity) }
        GadgetState.trip.value?.let { put("puff_at", it.friendId) }
        put("snowman", GadgetState.snowman.value)
        put("sniffles", GadgetState.sniffles.value)
        put("hour", h)
    }

    /** A game Rusty played against Puff on the phone: the shared scoreboard, and Puff's reaction. */
    fun phoneGame(game: String, outcome: Int) {
        val key = when (outcome) { 1 -> "play_you"; -1 -> "play_puff"; else -> "play_draw" }
        prefs.edit().putInt(key, prefs.getInt(key, 0) + 1).apply()
        val name = GadgetState.BlobCalled()
        when (outcome) {
            1 -> { GadgetState.setMood(Mood.SAD, 6); GadgetState.say("You beat me on your phone! 😤 Rematch?", 8) }
            -1 -> { GadgetState.react(Reaction.CELEBRATE); GadgetState.say("I won on your phone! 🎉", 8) }
            else -> { GadgetState.react(Reaction.NOD); GadgetState.say("A draw on your phone! 🤝", 8) }
        }
        DayLog.event("played $game with Rusty on the phone (${when (outcome) { 1 -> "Rusty won"; -1 -> "$name won"; else -> "a draw" }})", "game")
        checkStickers()
    }

    // -- Stickers -----------------------------------------------------------------------------------

    private suspend fun stickerLoop() {
        delay(3 * 60_000L)
        while (true) {
            checkStickers()
            delay(10 * 60_000L)
        }
    }

    fun checkStickers() {
        val d = store.data
        val have = d.stickers.keys
        val today = java.time.LocalDate.now().toString()
        val named = d.friends.filter { it.name.isNotBlank() }
        val visitedToday = d.today.keys.filter { it.startsWith("$today|") }.map { it.substringAfter('|') }.toSet()
        fun earned(id: String): Boolean = when (id) {
            "first_friend_visit" -> d.friends.any { it.visits > 0 }
            "group_photo" -> GadgetState.friendAlbum.value.any { "Group photo" in it.caption }
            "all_friends_one_day" -> named.size >= 2 && named.all { it.id in visitedToday }
            "first_letter" -> d.letters.any { !it.fromBlob }
            "first_reply" -> d.letters.any { it.fromBlob }
            "penpal" -> d.penpal != null
            "story_done" -> d.pastStories.isNotEmpty()
            "three_stories" -> d.pastStories.size >= 3
            "snowball" -> d.friends.any { (it.played["snowball"] ?: 0) > 0 }
            "treasure_1" -> prefs.getInt("treasure_found", 0) >= 1
            "treasure_10" -> prefs.getInt("treasure_found", 0) >= 10
            "trick_master" -> d.tricks.values.any { it >= 90 }
            "practice_5" -> prefs.getString("practice_days", "")!!.split(',').count { it.isNotBlank() } >= 5
            "games_10" -> prefs.getInt("play_you", 0) >= 10
            "gifts_5" -> prefs.getInt("gifts_sent", 0) >= 5
            "garden_bloom" -> GadgetState.garden.value >= 3
            "books_5" -> d.books.size >= 5
            "diary_7" -> GadgetState.diary.value.size >= 7
            else -> false
        }
        val new = FriendsBrain.STICKERS.filter { it.id !in have && earned(it.id) }
        if (new.isEmpty()) return
        store.update { it.copy(stickers = it.stickers + new.associate { s -> s.id to today }) }
        // One ceremony at a time (the rest just appear in the book).
        val s = new.first()
        GadgetState.log("sticker: ${s.title}")
        DayLog.event("earned the \"${s.title}\" sticker", "sticker")
        if (!isQuiet() && !GadgetState.asleep.value) {
            GadgetState.react(Reaction.CELEBRATE)
            GadgetState.hold(HeldItem.STAR, "${s.emoji} ${s.title}", 5)
            GadgetState.say(if (s.who == "you") "🏅 New sticker for you, Rusty: ${s.title}!" else "🏅 New sticker: ${s.title}!", 10)
        }
    }

    // -- Clubs --------------------------------------------------------------------------------------

    private suspend fun bookClub(f: Friend, second: Friend?, v: FriendVisit): String {
        GadgetState.scene { it.copy(book = true) }
        say(f, "Book club is now in session! 📚", 3_000)
        second?.let { say(it, "I couldn't put it down! 📖", 3_000) }
        blob(listOf("The ending surprised me! 😮", "I liked the part with the dragon. 🐉", "I read it twice! ✨").random(), 3_000)
        say(f, "Same time next week? 🤓", 2_500)
        scope.launch { delay(60_000); addBook(f.shownName) }
        untilEnd(v)
        GadgetState.scene { it.copy(book = false) }
        return "book club"
    }

    private suspend fun danceClub(f: Friend, second: Friend?, v: FriendVisit): String {
        GadgetState.scene { it.copy(party = true) }
        say(f, "Dance club! Five, six, seven, eight! 💃", 3_000)
        second?.let { say(it, "Spin! 🌀", 2_000) }
        GadgetState.react(Reaction.WANDER)
        blob("I'm getting the hang of it! 🕺", 3_000)
        GadgetState.setMood(Mood.HAPPY, ((v.untilMs - System.currentTimeMillis()) / 1000).toInt().coerceAtLeast(5))
        untilEnd(v)
        GadgetState.scene { it.copy(party = false) }
        return "dance club"
    }

    private suspend fun gardenClub(f: Friend, second: Friend?, v: FriendVisit): String {
        say(f, "Garden club is here to help! 🌻", 3_000)
        GadgetState.hold(HeldItem.WATERING_CAN, "", 2)
        second?.let { say(it, "Your window box looks lovely! 🌱", 3_000) }
        // A good watering counts like a rainy day for the garden.
        prefs.edit().putInt("garden_rain", prefs.getInt("garden_rain", 0) + 1).apply()
        blob("Thank you, garden club! 🌸", 3_000)
        untilEnd(v)
        return "garden club (they helped the window box grow)"
    }

    // -- Treasure hunts ------------------------------------------------------------------------------

    private var pendingRiddle: Treasure? = null

    /** Every couple of days a friend hides something in Puff's room and leaves a riddle; Rusty finds it by tapping. */
    private suspend fun treasureLoop() {
        delay(6 * 60_000L)
        while (true) {
            val today = java.time.LocalDate.now().toString()
            val t = store.data.treasure
            // Not found by evening: Puff finds it itself.
            if (t != null && !t.found && t.date == today && life.hour() >= 21) finishTreasure(byPuff = true)
            else if (t != null && !t.found && t.date != today) store.update { it.copy(treasure = null) }
            val due = (t == null || t.found || t.date != today) &&
                System.currentTimeMillis() - prefs.getLong("treasure_last", 0L) > 2 * 86_400_000L
            if (due && on() && !isQuiet() && life.hour() in 10..18 && rnd.nextFloat() < 0.3f) treasureNow()
            delay(30 * 60_000L)
        }
    }

    /** Start a hunt now: a friend writes a riddle (with Pixel) and comes over to hide the treasure. */
    suspend fun treasureNow(): Boolean {
        val today = java.time.LocalDate.now().toString()
        val f = store.data.friends.filter { it.name.isNotBlank() }.randomOrNull() ?: return false
        val spot = FriendsBrain.HIDING_SPOTS.keys.filter { life.has(it) }.randomOrNull() ?: return false
        val item = spot.label.removePrefix("a ").removePrefix("an ")
        val riddle = quietAsk(FriendsBrain.riddlePrompt(item, f.name))?.takeIf { it.isNotBlank() } ?: return false
        prefs.edit().putLong("treasure_last", System.currentTimeMillis()).apply()
        pendingRiddle = Treasure(spot.name, riddle.take(200), f.id, today)
        if (visit("treasure", null, f.name) == "already visiting") {
            // Someone's already over: the hider leaves it as a note instead.
            pendingRiddle?.let { tr -> store.update { d -> d.copy(treasure = tr) } }
            pendingRiddle = null
            GadgetState.hold(HeldItem.NOTE, "🗺️ Treasure hunt from ${f.name}", 30)
        }
        return true
    }

    private suspend fun hideTreasure(f: Friend): String {
        val t = pendingRiddle ?: return chat(f, null)
        pendingRiddle = null
        say(f, "Psst… I hid something in your room! 🤫", 3_000)
        store.update { it.copy(treasure = t) }
        say(f, t.riddle, 6_000)
        blob("A treasure hunt! Can you find it, Rusty? 🗺️", 3_500)
        return "${f.shownName} hid a treasure"
    }

    /** Rusty tapped [item] (a Decor name) in Puff's room during a hunt. */
    fun guess(item: String) {
        val t = store.data.treasure?.takeIf { !it.found } ?: return
        if (item == t.item) {
            prefs.edit().putInt("treasure_found", prefs.getInt("treasure_found", 0) + 1).apply()
            scope.launch { finishTreasure(byPuff = false) }
            return
        }
        store.update { it.copy(treasure = t.copy(wrong = t.wrong + 1)) }
        val hint = if (t.wrong + 1 >= 2) " Hint: it starts with \"${Decor.valueOf(t.item).label.removePrefix("a ").removePrefix("an ").first().uppercase()}\"." else ""
        GadgetState.react(Reaction.SHAKE)
        GadgetState.say("Not there! 🤔$hint", 6)
    }

    private suspend fun finishTreasure(byPuff: Boolean) {
        val t = store.data.treasure ?: return
        store.update { it.copy(treasure = t.copy(found = true)) }
        val where = Decor.valueOf(t.item).label.removePrefix("a ").removePrefix("an ")
        val hider = store.friend(t.friendId)
        // The prize: a new outfit if there's one to get, else something for the room, else a shiny star.
        val outfit = listOf("CROWN", "SCARF", "SUNGLASSES", "PARTY_HAT", "FLOWER", "BOW").firstOrNull { it !in store.data.wardrobe }
        val prize = when {
            outfit != null -> { addOutfit(outfit); "a ${outfit.lowercase().replace('_', ' ')}" }
            FriendsBrain.MARKET.any { !life.has(it) } -> FriendsBrain.MARKET.first { !life.has(it) }.let { life.unlock(it, " from a treasure hunt", quietly = true); it.label }
            else -> { GadgetState.hold(HeldItem.STAR, "", 30); "a shiny star" }
        }
        GadgetState.react(Reaction.CELEBRATE)
        GadgetState.say(if (byPuff) "I found the treasure under the $where! It's $prize! ✨" else "You found it! Under the $where: $prize! 🎉", 12)
        DayLog.event((if (byPuff) "found" else "Rusty found") + " ${hider?.name ?: "a friend"}'s treasure under the $where ($prize)", "treasure")
        hider?.let { remember(it, "the treasure I hid under the $where") }
    }

    private suspend fun trickOrTreat(f: Friend, second: Friend?, v: FriendVisit): String {
        GadgetState.scene { it.copy(costume = true) }
        say(f, "Trick or treat! 🎃", 3_000)
        second?.let { say(it, "Trick or treat!! 👻", 2_500) }
        blob("Here's some candy! 🍬", 3_000)
        GadgetState.scene { it.copy(teacups = false, snack = "🍬") }
        say(f, "Happy Halloween! 🦇", 2_500)
        untilEnd(v)
        GadgetState.scene { it.copy(costume = false, snack = "") }
        return "trick-or-treating"
    }

    private suspend fun snowball(f: Friend): String {
        GadgetState.scene { it.copy(snow = true) }
        say(f, "Snowball fight! ❄️", 2_000)
        var hits = 0
        repeat(8) { i ->
            val toFriend = i % 2 == 0
            GadgetState.scene { it.copy(throwAt = System.currentTimeMillis(), toFriend = toFriend) }
            delay(1_300)
            if (rnd.nextFloat() < 0.3f) {
                hits++
                if (toFriend) say(f, "Brr! 🥶", 1_500) else { GadgetState.react(Reaction.STARTLE); GadgetState.say("Eek! 🥶", 2) }
            } else delay(300)
        }
        GadgetState.scene { it.copy(throwAt = 0, snow = false) }
        remember(f, "the big snowball fight")
        return "a snowball fight ($hits hits)"
    }

    // -- The room ---------------------------------------------------------------------------------

    private fun loadShift(): Map<String, Float> = prefs.getString("room_shift", "")!!.split(',').mapNotNull { e ->
        e.split('=').takeIf { it.size == 2 }?.let { (k, v) -> v.toFloatOrNull()?.let { k to it } }
    }.toMap()

    private fun setShift(d: Decor, v: Float) {
        val m = (GadgetState.roomShift.value - d.name).let { if (v == 0f) it else it + (d.name to v) }
        prefs.edit().putString("room_shift", m.entries.joinToString(",") { "${it.key}=${it.value}" }).apply()
        GadgetState.setRoomShift(m)
    }

    // -- Diaries ----------------------------------------------------------------------------------

    /** After 9:30 pm, each friend who came over today writes its own diary entry. */
    private suspend fun diaryLoop() {
        while (true) {
            delay(10 * 60_000L)
            val now = java.time.LocalDateTime.now()
            if (now.hour < 21 || (now.hour == 21 && now.minute < 30)) continue
            val today = now.toLocalDate().toString()
            val d = store.data
            val due = d.today.filterKeys { it.startsWith("$today|") }.mapNotNull { (k, acts) ->
                val id = k.substringAfter('|')
                store.friend(id)?.takeIf { f -> f.name.isNotBlank() && d.diary.none { it.friendId == id && it.date == today } }?.let { it to acts }
            }
            val (f, acts) = due.firstOrNull() ?: continue
            val also = d.happenings.filter { it.date == today && f.id in it.ids }.map { it.text }
            val text = quietAsk(FriendsBrain.friendDiaryPrompt(f, acts.distinct(), alsoToday = also)) ?: continue
            store.update { it.copy(diary = (it.diary + FriendDiaryEntry(f.id, today, text.take(1200), System.currentTimeMillis())).takeLast(200)) }
            GadgetState.log("${f.name}'s diary written")
        }
    }
}
