package com.rusty.museecho.gadget

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
        scope.launch { comicLoop() }
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
        if (have.size < due.coerceAtMost(12) && have.all { it.visits > 0 }) newFriend()
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

    private suspend fun visitLoop() {
        while (true) {
            delay((25 + rnd.nextInt(30)) * 60_000L)
            maybeNewFriend()
            if (!on() || isQuiet() || !life.idle() || life.hour() !in 9..21) continue
            // Closer friends drop by more often.
            val closest = store.data.friends.maxOfOrNull { it.closeness } ?: 0
            val gap = (3 * 3_600_000L * (1f - closest / 200f)).toLong()
            if (System.currentTimeMillis() - prefs.getLong("friend_last", 0L) < gap) continue
            if (System.currentTimeMillis() - GadgetState.lastTouch < 60_000) continue
            if (rnd.nextFloat() < 0.35f) visit()
        }
    }

    /** What to do, for the moment: music means dancing, the TV in the evening a movie, afternoons tea... */
    private fun pickActivity(f: Friend): String {
        val h = life.hour()
        val st = GadgetState.laptopStatus.value
        val roll = rnd.nextFloat()
        return when {
            st?.playing == true && st.musicVibe.isNotEmpty() && roll < 0.6f -> "dance"
            GadgetState.tvPlaying.value && h >= 17 && roll < 0.7f -> "movie"
            h in 14..16 && roll < 0.35f -> "tea"
            h >= 19 && roll < 0.3f -> "nap"
            roll < 0.07f && f.closeness >= 20 && FriendsBrain.GIFTS.any { !life.has(it) } -> "gift"
            roll < 0.13f && movable().isNotEmpty() -> "redecorate"
            roll < 0.17f && f.closeness >= 15 -> "squabble"
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
                    }
                }
                // Morning: a sleepover ends.
                if (wasQuiet == true && !q && GadgetState.friend.value?.activity == "sleepover") endVisit()
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

    /** Once a day each: birthdays, Halloween, snowball weather, the summer block party, a talent show now and then. */
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
            "%02d-%02d".format(today.monthValue, today.dayOfMonth) == Prefs(context).blobBirthday && h in 10..20 && once("blob_birthday") -> {
                partyFor = "${GadgetState.blobName.value.ifBlank { "the blob" }}'s birthday"
                visit("party")
            }
            today.monthValue == 10 && today.dayOfMonth == 31 && h in 17..21 && once("halloween") -> visit("trickortreat")
            w != null && ("snow" in w.summary.lowercase() || (w.tempF <= 40 && today.monthValue in listOf(11, 12, 1, 2))) && h in 10..18 &&
                rnd.nextFloat() < 0.3f && once("snowball") -> visit("snowball")
            today.monthValue in 6..8 && today.dayOfWeek == java.time.DayOfWeek.SATURDAY && h in 14..18 &&
                prefs.getString("blockparty_month", "") != "${today.year}-${today.monthValue}" -> {
                prefs.edit().putString("blockparty_month", "${today.year}-${today.monthValue}").apply()
                visit("blockparty")
            }
            h in 17..20 && store.data.friends.count { it.name.isNotBlank() } >= 2 &&
                System.currentTimeMillis() - prefs.getLong("talentshow_last", 0L) > 5 * 86_400_000L && rnd.nextFloat() < 0.2f -> {
                prefs.edit().putLong("talentshow_last", System.currentTimeMillis()).apply()
                visit("talentshow")
            }
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
        val f = friendByName(name) ?: pickFriend() ?: newFriend()
        val act = activity?.lowercase()?.replace("-", "")?.replace(" ", "")?.let { a ->
            when (a) { "tictactoe", "ttt" -> "tictactoe"; "rockpaperscissors" -> "rps"; "hideandseek" -> "hideseek"; "teatime" -> "tea"; else -> a }
        }?.takeIf { it in FriendsBrain.ACTIVITIES } ?: pickActivity(f)
        // Two at once for the sociable things, now and then.
        val group = act in setOf("birthday", "trickortreat", "blockparty", "talentshow")
        val second = if (group || act in setOf("chat", "tea", "party", "dance", "story", "movie") && rnd.nextFloat() < 0.3f) pickBuddy(f) else null
        val ids = listOfNotNull(f.id, second?.id)
        val base = when (act) {
            "sleepover" -> 9 * 60
            "nap" -> 5
            "movie" -> 8
            "dance", "tea", "party", "story", "birthday", "blockparty", "talentshow" -> 3
            "trickortreat" -> 2
            "tag", "catch" -> 1
            else -> 2
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
                    "blockparty" -> blockParty(f, second, v)
                    "talentshow" -> talentShow(f, second)
                    else -> squabble(f)
                }
                // Good friends hug goodbye.
                if (f.closeness >= 30 && act != "squabble") hug(2_000)
                GadgetState.setFriendSay(if (act == "sleepover") "Thanks for having me! ☀️" else "Bye! 👋", f.id)
                second?.let { GadgetState.setFriendSay("Bye bye! 👋", it.id) }
                GadgetState.react(Reaction.WAVE)
                delay(2_200)
            } finally {
                GadgetState.clearFriendSay()
                GadgetState.scene { FriendScene() }
                GadgetState.setFriend(null)
                record(f, act, detail, second)
            }
        }
        return "${f.shownName} is visiting (${FriendsBrain.activityLabel(act)})"
    }

    private fun names(ids: List<String>) = ids.mapNotNull { store.friend(it)?.shownName }.joinToString(" and ")

    private fun record(f: Friend, act: String, detail: String, second: Friend?) {
        val today = java.time.LocalDate.now().toString()
        val gain = if (act in FriendsBrain.GAMES) 5 else if (act == "squabble") 1 else 3
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

    private suspend fun squabble(f: Friend): String {
        for ((mine, line) in FriendsBrain.SQUABBLE) if (mine) blob(line, 3_000) else say(f, line, 3_000)
        GadgetState.scene { it.copy(apart = true) }
        GadgetState.setMood(Mood.SAD, 10)
        delay(7_000)
        GadgetState.scene { it.copy(apart = false) }
        for ((mine, line) in FriendsBrain.MAKE_UP) if (mine) blob(line, 3_000) else say(f, line, 3_000)
        hug(2_500)
        remember(f, "the great cookie fight")
        return "a silly squabble over a cookie, then they made up"
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
            if (System.currentTimeMillis() - prefs.getLong("letter_last", 0L) < 36 * 3_600_000L || rnd.nextFloat() > 0.3f) continue
            val f = store.data.friends.filter { it.name.isNotBlank() }.maxByOrNull { it.closeness + rnd.nextInt(30) } ?: continue
            writeLetter(f)
        }
    }

    /** A letter from [f] now. */
    suspend fun writeLetter(f: Friend): Boolean {
        val d = store.data
        val recent = (f.memories.takeLast(2) + d.happenings.filter { f.id in it.ids }.takeLast(2).map { it.text } +
            listOfNotNull(f.played.maxByOrNull { it.value }?.key?.let { "they love playing ${FriendsBrain.activityLabel(it)} together" }))
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
        val f = store.friend(l.friendId) ?: return false
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

    // -- The comic strip ------------------------------------------------------------------------------

    /** After 8 pm, Pixel turns the day into a three-panel comic. */
    private suspend fun comicLoop() {
        var tries = 0
        var triedFor = ""
        while (true) {
            delay(10 * 60_000L)
            val now = java.time.LocalDateTime.now()
            val today = now.toLocalDate().toString()
            if (now.hour < 20 || store.data.comics.any { it.date == today }) continue
            if (triedFor != today) { triedFor = today; tries = 0 }
            if (tries++ >= 3) continue
            comicNow()
        }
    }

    suspend fun comicNow(): Boolean {
        val today = java.time.LocalDate.now().toString()
        val f = DayLog.today()
        val facts = buildList {
            GadgetState.weather.value?.let { add("weather: ${it.summary}") }
            if (f.talks > 0) add("Rusty talked to it ${f.talks} times")
            if (f.pokes > 0) add("it got poked ${f.pokes} times")
            if (f.tvMinutes >= 10) add("the TV was on for ${f.tvMinutes} minutes")
            addAll(f.events.takeLast(10))
        }.ifEmpty { listOf("a quiet day at home") }
        val name = GadgetState.blobName.value.ifBlank { "Blob" }
        val comic = quietAsk(FriendsBrain.comicPrompt(name, facts, store.data.friends.filter { it.name.isNotBlank() }))
            ?.let { FriendsBrain.parseComic(it, today) } ?: return false
        store.update { it.copy(comics = (it.comics.filter { c -> c.date != today } + comic).takeLast(60)) }
        GadgetState.log("comic: ${comic.title}")
        return true
    }

    fun markLetterRead(atMs: Long) {
        store.update { d -> d.copy(letters = d.letters.map { if (it.atMs == atMs) it.copy(read = true) else it }) }
    }

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
        return "${f.shownName}'s birthday party"
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

    private suspend fun blockParty(f: Friend, second: Friend?, v: FriendVisit): String {
        GadgetState.scene { it.copy(party = true) }
        blob("Block party! 🎶", 2_500)
        say(f, "Everyone's here! 🎈", 2_500)
        second?.let { say(it, "Turn it up! 🔊", 2_000) }
        GadgetState.setMood(Mood.HAPPY, ((v.untilMs - System.currentTimeMillis()) / 1000).toInt().coerceAtLeast(5))
        untilEnd(v)
        GadgetState.scene { it.copy(party = false) }
        return "the summer block party"
    }

    private suspend fun talentShow(f: Friend, second: Friend?): String {
        val acts = listOfNotNull(f, second)
        blob("Welcome to the talent show! 🎭", 3_000)
        for (a in acts) {
            for (line in FriendsBrain.talentAct(a)) say(a, line, 3_000)
            GadgetState.react(Reaction.HOP)
            delay(800)
        }
        // Rusty votes (a box pops up); if nobody does within a minute and a half, the blob picks.
        blob("Who was best? Tap to vote! 🗳️", 2_000)
        GadgetState.voted = null
        GadgetState.setVote(acts.map { it.id to it.shownName } to System.currentTimeMillis() + 90_000L)
        val until = System.currentTimeMillis() + 90_000L
        while (GadgetState.voted == null && System.currentTimeMillis() < until) delay(500)
        GadgetState.setVote(null)
        val winner = acts.firstOrNull { it.id == GadgetState.voted } ?: acts.random()
        val byVote = GadgetState.voted != null
        GadgetState.react(Reaction.CELEBRATE)
        say(winner, "I won!! 🏆", 3_000)
        store.edit(winner.id) { it.copy(trophies = it.trophies + 1) }
        remember(winner, "winning the talent show")
        return "${winner.shownName} won the talent show" + if (byVote) " (Rusty's vote)" else ""
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
