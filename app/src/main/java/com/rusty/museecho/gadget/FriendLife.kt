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
        scope.launch { namingLoop() }
        scope.launch { visitLoop() }
        scope.launch { longingLoop() }
        scope.launch { diaryLoop() }
        scope.launch { watchEvents() }
    }

    private fun on() = Prefs(context).blobFriend

    // -- Who ------------------------------------------------------------------------------------

    /** A new friend joins the circle (named by Pixel shortly after). */
    private fun newFriend(): Friend {
        val have = store.data.friends
        val p = Personality.entries.firstOrNull { pp -> have.none { it.personality == pp.name } } ?: Personality.entries.random()
        val color = FriendsBrain.COLORS.firstOrNull { c -> have.none { it.color == c } } ?: FriendsBrain.COLORS.random()
        val f = Friend(id = java.util.UUID.randomUUID().toString().take(8), personality = p.name, color = color, metMs = System.currentTimeMillis())
        store.update { it.copy(friends = it.friends + f) }
        GadgetState.log("a new friend (${p.label})")
        return f
    }

    /** More friends turn up over the weeks: a second after 5 days, a third after 12, a fourth after 25. */
    private fun maybeNewFriend() {
        val have = store.data.friends
        val first = have.minOfOrNull { it.metMs } ?: return
        val days = (System.currentTimeMillis() - first) / 86_400_000L
        val due = 1 + listOf(5L, 12L, 25L).count { days >= it }
        if (have.size < due && have.all { it.visits > 0 }) newFriend()
    }

    /** Pixel names each friend and writes a little bio. */
    private suspend fun namingLoop() {
        delay(2 * 60_000L)
        while (true) {
            store.data.friends.firstOrNull { it.name.isBlank() }?.let { f ->
                val named = quietAsk(FriendsBrain.namePrompt(f, store.data.friends.filter { it.name.isNotBlank() }))
                    ?.let(FriendsBrain::parseName)
                if (named != null) {
                    store.edit(f.id) { it.copy(name = named.first, bio = named.second) }
                    GadgetState.log("friend named ${named.first}")
                }
            }
            delay(10 * 60_000L)
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
        val second = if (act in setOf("chat", "tea", "party", "dance", "story", "movie") && rnd.nextFloat() < 0.2f) pickFriend(f.id) else null
        val ids = listOfNotNull(f.id, second?.id)
        val base = when (act) {
            "sleepover" -> 9 * 60
            "nap" -> 5
            "movie" -> 8
            "dance", "tea", "party", "story" -> 3
            "tag", "catch" -> 1
            else -> 2
        }
        // Closer friends stay longer.
        val len = ((minutes?.coerceIn(1, 600) ?: base) * 60_000L * (if (minutes == null) 1f + f.closeness / 100f else 1f)).toLong()
        val now = System.currentTimeMillis()
        val v = FriendVisit(act, now, now + len, f.color, ids)
        prefs.edit().putLong("friend_last", now).apply()
        GadgetState.log("friend visiting: ${f.shownName} ($act)")
        // On the clock page, the blob mentions who's over (they're on the Blob page).
        if (GadgetState.idlePage.value != 8 && !isQuiet()) GadgetState.say("${f.shownName} came over! 👯", 8)
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
            val text = quietAsk(FriendsBrain.friendDiaryPrompt(f, acts.distinct())) ?: continue
            store.update { it.copy(diary = (it.diary + FriendDiaryEntry(f.id, today, text.take(1200), System.currentTimeMillis())).takeLast(200)) }
            GadgetState.log("${f.name}'s diary written")
        }
    }
}
