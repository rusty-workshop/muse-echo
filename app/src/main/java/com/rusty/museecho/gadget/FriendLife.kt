package com.rusty.museecho.gadget

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
        scope.launch { comicLoop() }
        scope.launch { storyLoop() }
        scope.launch { morningLoop() }
        scope.launch { treasureLoop() }
        scope.launch { tripLoop() }
        scope.launch { penpalLoop() }
        scope.launch { stickerLoop() }
        // Every friend has a pet of its own.
        if (store.data.friends.any { it.petKind.isBlank() }) store.update { d ->
            d.copy(friends = d.friends.map { if (it.petKind.isBlank()) it.copy(petKind = FriendsBrain.petFor(it.p)) else it })
        }
        scope.launch { gazetteLoop() }
        scope.launch { gardenLoop() }
        if (store.data.pet == null) store.update { it.copy(pet = Pet(FriendsBrain.PET_KINDS.random(), bornMs = System.currentTimeMillis())) }
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
            birthday = FriendsBrain.randomBirthday(rnd), petKind = FriendsBrain.petFor(p))
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
            store.data.pet?.takeIf { it.name.isBlank() && Prefs(context).blobName.isNotBlank() }?.let { pet ->
                quietAsk(FriendsBrain.petNamePrompt(pet.kind, Prefs(context).blobName, store.data.friends.map { it.name } + Prefs(context).blobName))
                    ?.let(FriendsBrain::parseBlobName)?.let { n ->
                        store.update { it.copy(pet = it.pet?.copy(name = n)) }
                        GadgetState.log("the pet ${pet.label} is called $n")
                    }
            }
            // Then the friends' pets, once their owners have names.
            store.data.friends.firstOrNull { it.name.isNotBlank() && it.petKind.isNotBlank() && it.petName.isBlank() }?.let { owner ->
                val taken = store.data.friends.flatMap { listOf(it.name, it.petName) } + listOfNotNull(store.data.pet?.name, Prefs(context).blobName)
                quietAsk(FriendsBrain.petNamePrompt(owner.petKind, owner.name, taken.filter { it.isNotBlank() }))?.let(FriendsBrain::parseBlobName)?.let { n ->
                    store.edit(owner.id) { it.copy(petName = n) }
                    GadgetState.log("${owner.name}'s ${owner.petKind} is called $n")
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
        val pet = store.data.pet?.takeIf { it.name.isNotBlank() }
        val (item, lines) = when {
            // Breakfast for the pet comes first each day.
            pet != null && pet.fedDate != today -> {
                store.update { it.copy(pet = it.pet?.copy(fedDate = today)) }
                GadgetState.petPokedAt = System.currentTimeMillis()
                DayLog.event("fed ${pet.name} the ${pet.label}", "pet")
                HeldItem.NONE to listOf("Breakfast time, ${pet.name}! 🍽️", "${pet.name} loves it. 😊", "Good ${pet.label}! 💛")
            }
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
            GadgetState.weather.value?.summary?.lowercase()?.let { "rain" in it || "drizzle" in it || "shower" in it } == true && h in 9..19 && roll < 0.4f -> "puddles"
            (GadgetState.weather.value?.windMph ?: 0) >= 15 && h in 9..18 && roll < 0.4f -> "kites"
            (GadgetState.weather.value?.tempF ?: 0) >= 85 && h in 11..18 && roll < 0.4f -> "lollies"
            roll > 0.94f && System.currentTimeMillis() - prefs.getLong("cousin_last", 0L) > 5 * 86_400_000L -> "cousin"
            h in 14..16 && roll < 0.35f -> "tea"
            h >= 19 && roll < 0.3f -> "nap"
            roll < 0.07f && f.closeness >= 20 && FriendsBrain.GIFTS.any { !life.has(it) } -> "gift"
            roll < 0.13f && movable().isNotEmpty() -> "redecorate"
            roll < 0.17f && f.closeness >= 15 -> "squabble"
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
            today.monthValue == 12 && today.dayOfMonth == 31 && h >= 18 && once("year_review") -> scope.launch { reviewNow(today.year.toString()) }
            today.dayOfWeek == java.time.DayOfWeek.SATURDAY && h in 10..15 && once("market") -> visit("market")
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
        if (GadgetState.trip.value != null) return "Puff's out visiting"
        val f = friendByName(name) ?: pickFriend() ?: newFriend()
        val act = activity?.lowercase()?.replace("-", "")?.replace(" ", "")?.let { a ->
            when (a) { "tictactoe", "ttt" -> "tictactoe"; "rockpaperscissors" -> "rps"; "hideandseek" -> "hideseek"; "teatime" -> "tea"; else -> a }
        }?.takeIf { it in FriendsBrain.ACTIVITIES } ?: pickActivity(f)
        // Two at once for the sociable things, now and then.
        val group = act in setOf("birthday", "trickortreat", "blockparty", "talentshow", "market")
        val clubMate = if (act.endsWith("club")) store.data.friends.firstOrNull {
            it.id != f.id && it.name.isNotBlank() && FriendsBrain.clubOf(it).name.lowercase() == act.removeSuffix("club")
        } else null
        val second = clubMate ?: if (group || act in setOf("chat", "tea", "party", "dance", "story", "movie") && rnd.nextFloat() < 0.3f) pickBuddy(f) else null
        val guest = if (act == "cousin") FriendsBrain.relativeOf(f.p).let { r ->
            val c = Color(f.color)
            Friend(id = "guest", name = r.names[(f.id.hashCode() and 0x7fffffff) % r.names.size], personality = f.personality,
                color = (0xFF000000 or (androidx.core.graphics.ColorUtils.blendARGB(f.color.toInt(), if (r.scale > 1f) 0xFF808080.toInt() else 0xFFFFFFFF.toInt(), 0.35f).toLong() and 0xFFFFFF)),
                scaleOverride = r.scale, bio = r.relation)
        } else null
        GadgetState.setGuest(guest)
        val ids = if (guest != null) listOf(f.id, "guest") else if (act == "groupphoto") listOf(f.id) + store.data.friends.filter { it.id != f.id && it.name.isNotBlank() }.map { it.id }
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
                    "blockparty" -> blockParty(f, second, v)
                    "talentshow" -> talentShow(f, second)
                    "sad" -> cheerUp(f)
                    "market" -> marketDay(f, second, v)
                    "treasure" -> hideTreasure(f)
                    "bookclub" -> bookClub(f, second, v)
                    "danceclub" -> danceClub(f, second, v)
                    "gardenclub" -> gardenClub(f, second, v)
                    "groupphoto" -> groupPhoto(f, v)
                    "cheerrusty" -> cheerRusty(f, second)
                    "cousin" -> cousinVisit(f, GadgetState.guest.value)
                    "puddles" -> puddles(f, second, v)
                    "kites" -> kites(f, second, v)
                    "lollies" -> lollies(f, second, v)
                    else -> squabble(f)
                }
                // After the game or whatever it was, they stay and hang out until it's time to go.
                linger(f, second, v)
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
                GadgetState.setGuest(null)
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
            // The pets run off to play together now and then.
            if (f.petName.isNotBlank() && store.data.pet != null && rnd.nextInt(5) == 0) {
                GadgetState.setFriendSay("${f.petName} wants to play with ${store.data.pet?.name?.ifBlank { null } ?: "your pet"}! 🐾", f.id)
                GadgetState.scene { it.copy(petsPlay = true) }
                GadgetState.petPokedAt = System.currentTimeMillis()
                delay(8_000)
                GadgetState.scene { it.copy(petsPlay = false) }
                GadgetState.clearFriendSay()
                continue
            }
            val pet = store.data.pet?.takeIf { it.name.isNotBlank() }
            val (mine, line) = if (pet != null && rnd.nextInt(6) == 0) false to "How's ${pet.name} doing? 🥰" else HANG_LINES.random()
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
            listOfNotNull(storyContext(), d.pet?.takeIf { it.name.isNotBlank() }?.let { "${GadgetState.blobCalled()} has a pet ${it.label} called ${it.name}" }))
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
            storyContext()?.let { add(it) }
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

    /** "Current story" for letters and comics. */
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

    // -- The pet and the garden ---------------------------------------------------------------------

    /** Tapping the pet: it wiggles and Puff says something about it. */
    fun petTapped() {
        val pet = store.data.pet ?: return
        GadgetState.petPokedAt = System.currentTimeMillis()
        store.update { it.copy(pet = it.pet?.copy(pats = pet.pats + 1)) }
        val name = pet.name.ifBlank { "my ${pet.label}" }
        GadgetState.say(when (pet.kind) {
            "snail" -> "$name peeks out! 🐌"
            "sprout" -> "$name wiggles its leaves! 🌱"
            else -> "$name does a little spin! 🐟"
        }, 5)
        GadgetState.react(Reaction.HOP)
        DayLog.mark("pet", "patted ${pet.name}")
    }

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

    /** Rusty sent a gift from the settings page: Puff unwraps it. [kind] hat / snack / room / pet. */
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
            else -> {
                GadgetState.petPokedAt = System.currentTimeMillis()
                "a treat for ${store.data.pet?.name?.ifBlank { null } ?: "my pet"}"
            }
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

    // -- Mornings: outfit and last night's dream ---------------------------------------------------

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
                if (store.data.dreams.none { it.date == today.toString() }) dreamNow()
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

    suspend fun dreamNow(): Boolean {
        val today = java.time.LocalDate.now().toString()
        val y = DayLog.yesterday
        val facts = (y.events.takeLast(8) + listOfNotNull(
            store.data.story?.takeIf { it.at > 0 }?.let { "the story \"${it.title}\"" },
            store.data.pet?.takeIf { it.name.isNotBlank() }?.let { "its pet ${it.label} ${it.name}" },
        )).ifEmpty { listOf("a quiet, cozy day") }
        val text = quietAsk(FriendsBrain.dreamPrompt(GadgetState.blobCalled(), facts)) ?: return false
        store.update { it.copy(dreams = (it.dreams + Dream(today, text.take(600))).takeLast(60)) }
        GadgetState.log("dream written")
        if (!isQuiet()) GadgetState.say("💭 " + text.substringBefore('.').take(110) + "…", 15)
        return true
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

    private suspend fun gazetteLoop() {
        delay(4 * 60_000L)
        while (true) {
            val now = java.time.LocalDateTime.now()
            val today = now.toLocalDate().toString()
            if (now.dayOfWeek == java.time.DayOfWeek.SUNDAY && now.hour >= 17 && store.data.gazettes.none { it.date == today }) gazetteNow()
            delay(30 * 60_000L)
        }
    }

    suspend fun gazetteNow(): Boolean {
        val d = store.data
        val weekAgo = java.time.LocalDate.now().minusDays(7).toString()
        val name = { id: String -> d.friends.firstOrNull { it.id == id }?.shownName ?: "a friend" }
        val facts = buildList {
            d.happenings.filter { it.date >= weekAgo }.forEach { add(it.text) }
            d.letters.filter { it.date >= weekAgo }.forEach { add(if (it.fromBlob) "${GadgetState.blobCalled()} wrote to ${name(it.friendId)}" else "${name(it.friendId)} sent a letter") }
            d.comics.filter { it.date >= weekAgo }.forEach { add("comic: ${it.title}") }
            d.story?.let { add("the adventure \"${it.title}\" is at chapter ${it.at} of ${it.chapters.size}") }
            d.pastStories.lastOrNull()?.let { add("the last adventure was \"$it\"") }
            d.friends.filter { it.trophies > 0 }.forEach { add("${it.name} has ${it.trophies} talent show trophies") }
            d.pet?.takeIf { it.name.isNotBlank() }?.let { add("${GadgetState.blobCalled()}'s pet ${it.label} ${it.name} is ${(System.currentTimeMillis() - it.bornMs) / 86_400_000L} days old") }
            addAll(DayLog.today().events.takeLast(5))
        }.ifEmpty { listOf("a quiet week in the neighbourhood") }
        val g = quietAsk(FriendsBrain.gazettePrompt(GadgetState.blobCalled(), facts))
            ?.let { FriendsBrain.parseGazette(it, java.time.LocalDate.now().toString()) } ?: return false
        store.update { it.copy(gazettes = (it.gazettes + g).takeLast(30)) }
        GadgetState.log("gazette: ${g.headline}")
        if (!isQuiet()) {
            GadgetState.hold(HeldItem.NOTE, "🗞️ ${g.headline.take(36)}", 30)
            GadgetState.say("Extra! Extra! The Blob Gazette is out! 🗞️", 10)
        }
        return true
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

    private suspend fun marketDay(f: Friend, second: Friend?, v: FriendVisit): String {
        GadgetState.scene { it.copy(market = true) }
        blob("It's market day! 🧺", 2_500)
        say(f, "Fresh ${FriendsBrain.marketGoods(f)}! Get them here!", 3_000)
        second?.let { say(it, "${FriendsBrain.marketGoods(it).replaceFirstChar { c -> c.uppercase() }}, half price! 🏷️", 3_000) }
        val buy = FriendsBrain.MARKET.firstOrNull { !life.has(it) }
        val bought = if (buy != null) {
            blob("Ooh, I'll take ${buy.label}! ${buy.emoji}", 3_000)
            GadgetState.hold(HeldItem.GIFT, "${buy.emoji} New for my room", 3)
            life.unlock(buy, " from market day", quietly = true)
            buy.label
        } else {
            val hat = listOf("CROWN", "SCARF", "SUNGLASSES", "PARTY_HAT", "FLOWER", "BOW").firstOrNull { it !in store.data.wardrobe }
            if (hat != null) {
                blob("Ooh, a ${hat.lowercase().replace('_', ' ')}! For my wardrobe! 👗", 3_000)
                addOutfit(hat)
                "a ${hat.lowercase().replace('_', ' ')}"
            } else {
                blob("I'll take some ${FriendsBrain.marketGoods(f).substringBefore(' ')}! 😄", 3_000)
                FriendsBrain.marketGoods(f).substringBeforeLast(' ')
            }
        }
        untilEnd(v)
        GadgetState.scene { it.copy(market = false) }
        return "market day (came home with $bought)"
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

    /** A one-line bedtime story starring Puff's friends and pet. */
    fun bedtimeStory(): String {
        val friends = store.data.friends.filter { it.name.isNotBlank() }
        val a = friends.randomOrNull()?.name ?: "a little blob"
        val b = friends.filter { it.name != a }.randomOrNull()?.name ?: "the moon"
        val pet = store.data.pet?.name?.ifBlank { null } ?: "a sleepy snail"
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

    suspend fun penpalLetterNow(): Boolean {
        val p = store.data.penpal ?: return false
        val lastReply = store.data.letters.lastOrNull { it.fromBlob && it.friendId == FriendsBrain.PENPAL_ID }?.text
        val text = quietAsk(FriendsBrain.penpalLetterPrompt(p, GadgetState.blobCalled(), lastReply)) ?: return false
        prefs.edit().putLong("penpal_last", System.currentTimeMillis()).apply()
        store.update { d ->
            d.copy(letters = (d.letters + Letter(FriendsBrain.PENPAL_ID, java.time.LocalDate.now().toString(), text.take(1200), System.currentTimeMillis())).takeLast(80),
                postcards = (d.postcards + Postcard(FriendsBrain.PENPAL_ID, "Greetings from ${p.place}! 🌍", java.time.LocalDate.now().toString())).takeLast(12))
        }
        life.unlock(Decor.POSTCARDS, " from ${p.place}", quietly = true)
        DayLog.event("got a letter from its pen pal ${p.name} in ${p.place}", "letter")
        if (!isQuiet()) {
            GadgetState.react(Reaction.HOP)
            GadgetState.hold(HeldItem.LETTER, "🌍 Letter from ${p.name} (${p.place})", 60)
            GadgetState.say("A letter from my pen pal in ${p.place}! 🌍", 12)
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

    private suspend fun cousinVisit(f: Friend, guest: Friend?): String {
        prefs.edit().putLong("cousin_last", System.currentTimeMillis()).apply()
        val g = guest ?: return chat(f, null)
        say(f, "This is my ${g.bio}, ${g.name}! 🤗", 3_500)
        GadgetState.setFriendSay(when {
            g.scale < 0.6f -> "hi!!! (I'm VERY small) 👋"
            g.scale > 1.2f -> "Hello, dear. What a lovely room. 👵"
            else -> "Hiya! 👋"
        }, "guest")
        delay(3_500)
        GadgetState.setFriendSay(null, "guest")
        blob("Welcome, ${g.name}! 🤗", 3_000)
        GadgetState.react(Reaction.WAVE)
        DayLog.event("${f.shownName} brought their ${g.bio} ${g.name} over", "friend")
        return "${f.shownName} brought their ${g.bio} ${g.name}"
    }

    private suspend fun puddles(f: Friend, second: Friend?, v: FriendVisit): String {
        GadgetState.scene { it.copy(puddles = true) }
        say(f, "It's raining! Puddle jumping! 🌧️👢", 3_000)
        second?.let { say(it, "SPLASH! 💦", 2_000) }
        blob("Wheee! 💦", 2_500)
        GadgetState.react(Reaction.HOP)
        untilEnd(v)
        GadgetState.scene { it.copy(puddles = false) }
        return "puddle jumping in the rain"
    }

    private suspend fun kites(f: Friend, second: Friend?, v: FriendVisit): String {
        GadgetState.scene { it.copy(kites = true) }
        say(f, "It's so windy! Kite time! 🪁", 3_000)
        second?.let { say(it, "Mine's going higher! 🪁", 2_500) }
        blob("Look at them go! 🌬️", 2_500)
        untilEnd(v)
        GadgetState.scene { it.copy(kites = false) }
        return "kite flying on a windy day"
    }

    private suspend fun lollies(f: Friend, second: Friend?, v: FriendVisit): String {
        GadgetState.scene { it.copy(teacups = false, snack = "🍦", party = false) }
        say(f, "It's SO hot. Ice lollies! 🍭", 3_000)
        second?.let { say(it, "Brain freeze! 🥶", 2_500) }
        blob("Mmm, cold! 🍦", 2_500)
        untilEnd(v)
        GadgetState.scene { it.copy(snack = "") }
        return "an ice-lolly party on a hot day"
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
            "talent_vote" -> prefs.getInt("talent_votes", 0) > 0
            "treasure_1" -> prefs.getInt("treasure_found", 0) >= 1
            "treasure_10" -> prefs.getInt("treasure_found", 0) >= 10
            "trick_master" -> d.tricks.values.any { it >= 90 }
            "practice_5" -> prefs.getString("practice_days", "")!!.split(',').count { it.isNotBlank() } >= 5
            "games_10" -> prefs.getInt("play_you", 0) >= 10
            "gifts_5" -> prefs.getInt("gifts_sent", 0) >= 5
            "pet_month" -> d.pet?.let { System.currentTimeMillis() - it.bornMs > 30 * 86_400_000L } == true
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

    // -- Year (or month) in review ---------------------------------------------------------------

    /** A special edition of the Gazette looking back over [period]: "2026" or "2026-10". */
    suspend fun reviewNow(period: String): Boolean {
        val d = store.data
        val inPeriod = { date: String -> date.startsWith(period) }
        val label = if (period.length == 4) period else runCatching {
            java.time.YearMonth.parse(period).format(java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy", java.util.Locale.US))
        }.getOrDefault(period)
        val name = { id: String -> d.friends.firstOrNull { it.id == id }?.name ?: d.penpal?.name ?: "a friend" }
        val facts = buildList {
            GadgetState.diary.value.filter { inPeriod(it.date) }.takeLast(10).forEach { add("diary: ${it.text.take(160)}") }
            d.comics.filter { inPeriod(it.date) }.forEach { add("comic: ${it.title}") }
            d.letters.filter { inPeriod(it.date) && !it.fromBlob }.forEach { add("letter from ${name(it.friendId)}") }
            d.happenings.filter { inPeriod(it.date) }.forEach { add(it.text) }
            d.pastStories.forEach { add("adventure: $it") }
            d.stickers.filterValues { inPeriod(it) }.keys.mapNotNull { id -> FriendsBrain.STICKERS.firstOrNull { it.id == id }?.title }.forEach { add("sticker: $it") }
            d.books.filter { inPeriod(it.date) }.forEach { add("book: ${it.title}") }
            d.friends.filter { it.trophies > 0 }.forEach { add("${it.name}: ${it.trophies} talent show trophies") }
            GadgetState.friendAlbum.value.filter { inPeriod(it.date) }.forEach { add("photo: ${it.caption}") }
        }.ifEmpty { listOf("a cozy, quiet stretch at home") }
        val g = quietAsk(FriendsBrain.reviewPrompt(GadgetState.blobCalled(), label, facts))
            ?.let { FriendsBrain.parseGazette(it, java.time.LocalDate.now().toString()) } ?: return false
        val photos = GadgetState.friendAlbum.value.filter { inPeriod(it.date) }.let { all ->
            (all.filter { "Group photo" in it.caption } + all.shuffled()).distinct().take(4).map { it.file }
        }
        store.update { it.copy(gazettes = (it.gazettes + g.copy(headline = "✨ $label in review: ${g.headline}", photos = photos)).takeLast(30)) }
        GadgetState.log("review: $label")
        if (!isQuiet()) GadgetState.say("📰 A special edition: $label in review!", 10)
        return true
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
        if (byVote) prefs.edit().putInt("talent_votes", prefs.getInt("talent_votes", 0) + 1).apply()
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
