package com.rusty.museecho.gadget

import android.content.Context
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * The playful extras around Puff: fortune cookies, letters Rusty posts from the phone (by mail truck,
 * the next day), the monthly costume, the umbrella-or-raincoat debate, the mystery visitor, the
 * late-night radio show, the pet's tricks and trivia night.
 */
class FunLife(
    private val context: Context,
    private val scope: CoroutineScope,
    private val life: BlobLife,
    private val isQuiet: () -> Boolean,
    private val quietAsk: suspend (String) -> String?,
) {
    private val store get() = life.friends.store
    private val prefs = context.getSharedPreferences("museecho", Context.MODE_PRIVATE)
    private val rnd = java.util.Random()

    fun start() {
        scope.launch { delay(5 * 60_000L); refillFortune() }
        scope.launch { mailLoop() }
        scope.launch { costumeLoop() }
        scope.launch { debateLoop() }
        scope.launch { mysteryLoop() }
        scope.launch { radioLoop() }
        scope.launch { petShowOffLoop() }
        scope.launch { briefPixel() }
    }

    /**
     * Once per new [BRIEFING_VERSION]: tell Pixel (in the background, no reply needed) what's new in
     * the blob's world, so it can talk about it. blob.world is the live source; this is the heads-up.
     */
    private suspend fun briefPixel() {
        delay(6 * 60_000L)
        if (prefs.getInt("pixel_briefed", 0) >= BRIEFING_VERSION) return
        val name = GadgetState.blobCalled()
        val ok = quietAsk(
            "A heads-up about new things in $name's world on my Echo Show (the blob that lives there), so you can talk " +
                "about them; no need to do anything now, just reply \"got it\".\n" +
                "- Fortune cookies: blob.fortune cracks one open (for \"fortune\" or \"give me a fortune cookie\").\n" +
                "- Trivia night: trivia.start with a theme; 5 questions you'll be asked to write, Rusty taps the answers, $name cheers or groans.\n" +
                "- Rusty can write $name letters from his phone; they arrive by mail truck the next morning and $name writes back.\n" +
                "- When the laptop plays something lively, a dance visit turns into a disco with a mirror ball.\n" +
                "- The evening before rain or snow, $name and a friend argue about umbrella or raincoat (or earmuffs or scarf); Rusty settles it from his phone and $name wears the winner.\n" +
                "- A mystery visitor in a trench coat (one of the friends in disguise) sometimes drops 3 clues; Rusty guesses who from his phone. Never give the answer away while it's unsolved.\n" +
                "- $name's pet is learning tricks Rusty teaches from his phone, a practice a day.\n" +
                "- A new costume arrives in $name's closet each month.\n" +
                "- Most evenings around 8 to 9:30, $name does a little radio show in speech bubbles.\n" +
                "- Also: phone.find rings Rusty's phone (\"where's my phone\").\n" +
                "For the current state of any of this (clues, the debate, trick progress, outfit, trivia streaks, the last radio show), call blob.world.",
        )
        if (ok != null) {
            prefs.edit().putInt("pixel_briefed", BRIEFING_VERSION).apply()
            GadgetState.log("briefed Pixel on what's new")
        }
    }

    private fun today() = LocalDate.now().toString()
    private fun free() = GadgetState.friend.value == null && GadgetState.trip.value == null

    // -- Fortune cookies ----------------------------------------------------------------------------

    /** Crack one open now: Puff holds it up and reads the slip. One is kept ready so it's instant. */
    fun fortune(): String {
        val text = prefs.getString("fortune_next", null)?.takeIf { it.isNotBlank() } ?: LOCAL_FORTUNES.random()
        prefs.edit().remove("fortune_next").apply()
        store.update { it.copy(fortunes = (it.fortunes + Fortune(today(), text)).takeLast(60)) }
        GadgetState.react(Reaction.HOP)
        GadgetState.hold(HeldItem.FORTUNE, "🥠 $text", 2)
        GadgetState.say("🥠 *crack!* \"$text\"", 15)
        DayLog.event("opened a fortune cookie: \"$text\"", "fortune")
        scope.launch { delay(30_000); refillFortune() }
        return text
    }

    private suspend fun refillFortune() {
        if (!prefs.getString("fortune_next", null).isNullOrBlank()) return
        val recent = store.data.fortunes.takeLast(10).joinToString("; ") { it.text }
        val text = quietAsk(
            "Write one fortune-cookie fortune for ${GadgetState.blobCalled()}, the little blob on my Echo Show, and the person who " +
                "lives with it: silly, sweet or wise, under 15 words, like a real fortune cookie slip. Not like these recent ones: " +
                "$recent. Reply with only the fortune, no quotes, no commands, and don't change anything on any device.",
        )?.lines()?.firstOrNull { it.isNotBlank() }?.trim()?.trim('"', '“', '”', '*')?.take(120) ?: return
        if (text.length >= 8) prefs.edit().putString("fortune_next", text).apply()
    }

    // -- Letters from Rusty --------------------------------------------------------------------------

    /** A letter Rusty wrote in the phone app: it's posted, and arrives by mail truck tomorrow morning. */
    fun letterFromRusty(text: String) {
        val clean = text.trim().take(1000).takeIf { it.isNotEmpty() } ?: return
        store.update { it.copy(mail = (it.mail + MailFromRusty(clean, System.currentTimeMillis(), LocalDate.now().plusDays(1).toString())).takeLast(20)) }
        DayLog.event("Rusty posted it a letter (it comes tomorrow)", "mail")
    }

    private suspend fun mailLoop() {
        delay(2 * 60_000L)
        while (true) {
            val now = System.currentTimeMillis()
            store.data.mail.firstOrNull { !it.delivered && it.arrive <= today() }?.let { m ->
                if (life.hour() >= 9 && !isQuiet() && life.idle() && free()) deliver(m)
            }
            store.data.mail.firstOrNull { it.delivered && !it.replied && now - it.deliveredMs > 90 * 60_000L }?.let { m ->
                if (!isQuiet()) answer(m)
            }
            delay(5 * 60_000L)
        }
    }

    private suspend fun deliver(m: MailFromRusty) {
        GadgetState.mailTruckAt = System.currentTimeMillis()
        GadgetState.log("a letter from Rusty arrived")
        delay(2_800) // the truck pulls up
        GadgetState.react(Reaction.HOP)
        GadgetState.hold(HeldItem.LETTER, "💌 From Rusty", 30)
        GadgetState.say("The mail truck! A letter… from RUSTY!! 💌", 12)
        val now = System.currentTimeMillis()
        store.update { d ->
            d.copy(mail = d.mail.map { if (it.sentMs == m.sentMs) it.copy(delivered = true, deliveredMs = now) else it },
                letters = (d.letters + Letter("rusty", today(), m.text, now, read = true)).takeLast(80))
        }
        DayLog.event("got a letter from Rusty in the mail", "mail")
    }

    private suspend fun answer(m: MailFromRusty) {
        val name = GadgetState.blobCalled()
        val lately = DayLog.today().events.takeLast(6)
        val reply = quietAsk(
            "You are $name, the little blob who lives on Rusty's Echo Show. Rusty sent you this letter:\n\"${m.text}\"\n" +
                "Write your reply letter: warm and playful, 3 to 6 short sentences, answering what he wrote" +
                (if (lately.isNotEmpty()) " and mentioning something from your day (${lately.joinToString("; ")})" else "") +
                ". Start with \"Dear Rusty,\" and sign it from $name. Reply with only the letter: no commands, and don't change anything on any device.",
        ) ?: return
        val now = System.currentTimeMillis()
        store.update { d ->
            d.copy(mail = d.mail.map { if (it.sentMs == m.sentMs) it.copy(replied = true) else it },
                letters = (d.letters + Letter("rusty", today(), reply.take(1500), now, read = false, fromBlob = true)).takeLast(80))
        }
        GadgetState.hold(HeldItem.LETTER, "📮 Wrote back to Rusty", 10)
        GadgetState.say("I wrote back to Rusty! 📮", 10)
        DayLog.event("wrote back to Rusty's letter", "mail")
    }

    // -- The costume closet --------------------------------------------------------------------------

    /** A new costume for the month arrives in a box, and Puff tries it on. */
    private suspend fun costumeLoop() {
        delay(3 * 60_000L)
        while (true) {
            val d = LocalDate.now()
            val month = d.toString().take(7)
            if (store.data.costumeMonth != month && !isQuiet() && life.hour() in 8..20 && life.idle() && free()) {
                val c = COSTUMES.getValue(d.monthValue)
                life.friends.addOutfit(c)
                store.update { it.copy(costumeMonth = month) }
                GadgetState.react(Reaction.CELEBRATE)
                GadgetState.hold(HeldItem.PACKAGE, "📦 This month's costume!", 3)
                GadgetState.say("A box! This month's costume: ${costumeLabel(c)}! ✨", 15)
                DayLog.event("got this month's costume: ${costumeLabel(c)}", "wardrobe")
                delay(5_000)
                life.friends.wearToday(c)
            }
            delay(30 * 60_000L)
        }
    }

    // -- Umbrella or raincoat? -----------------------------------------------------------------------

    private suspend fun debateLoop() {
        delay(4 * 60_000L)
        while (true) {
            val now = LocalDateTime.now()
            val tomorrow = now.toLocalDate().plusDays(1).toString()
            val w = GadgetState.weather.value?.tomorrowSummary.orEmpty().lowercase()
            val wet = listOf("rain", "drizzle", "shower", "storm", "thunder", "snow").any { it in w }
            if (wet && now.hour in 17..20 && store.data.debate?.date != tomorrow && Prefs(context).blobFriend && !isQuiet() &&
                life.idle() && free() && store.data.friends.any { it.name.isNotBlank() }
            ) life.friends.visit("raingear", 3)
            // On the day: wear the winner (Puff's own pick if nobody settled it).
            val d = store.data.debate
            if (d != null && d.date == today() && !d.worn && now.hour in 6..11 && !isQuiet()) {
                val pick = d.choice.ifEmpty { d.puff }
                store.update { it.copy(debate = d.copy(choice = pick, by = d.by.ifEmpty { "puff" }, worn = true)) }
                when (pick) {
                    "umbrella" -> GadgetState.hold(HeldItem.UMBRELLA, "☔ Umbrella day!", 240)
                    "raincoat" -> life.friends.wearToday("RAINCOAT", announce = false)
                    "earmuffs" -> life.friends.wearToday("EARMUFFS", announce = false)
                    "scarf" -> life.friends.wearToday("SCARF", announce = false)
                }
                GadgetState.say(if (d.choice.isEmpty()) "Nobody decided, so… $pick! I win! 😄" else "Wearing the $pick, like Rusty said! ☔", 20)
            }
            delay(10 * 60_000L)
        }
    }

    // -- The mystery visitor -------------------------------------------------------------------------

    private suspend fun mysteryLoop() {
        delay(10 * 60_000L)
        while (true) {
            val named = store.data.friends.filter { it.name.isNotBlank() }
            val last = prefs.getLong("mystery_last", 0L)
            if (named.size >= 3 && System.currentTimeMillis() - last > 12 * 86_400_000L && store.data.mystery?.open != true &&
                life.hour() in 10..19 && Prefs(context).blobFriend && !isQuiet() && life.idle() && free() && rnd.nextFloat() < 0.025f
            ) {
                prefs.edit().putLong("mystery_last", System.currentTimeMillis()).apply()
                life.friends.visit("mystery", 3, named.random().name)
            }
            delay(20 * 60_000L)
        }
    }

    // -- Puff's radio show ---------------------------------------------------------------------------

    private suspend fun radioLoop() {
        delay(5 * 60_000L)
        while (true) {
            val now = LocalDateTime.now()
            // Some time between 8 and 9:30 pm, a different minute each night.
            val at = 20 * 60 + (now.toLocalDate().toEpochDay() * 37 % 90).toInt()
            val min = now.hour * 60 + now.minute
            if (store.data.radio.none { it.date == today() } && min >= at && now.hour < 22 && !isQuiet() && life.idle() && free()) radioShow()
            delay(5 * 60_000L)
        }
    }

    /** Two minutes on air: Puff at the mic, its show in speech bubbles. */
    suspend fun radioShow() {
        val name = GadgetState.blobCalled()
        val d = store.data
        val todayKey = today()
        val visitors = d.today.keys.filter { it.startsWith("$todayKey|") }.mapNotNull { k -> d.friends.firstOrNull { it.id == k.substringAfter('|') }?.shownName }
        val buddy = d.friends.filter { it.name.isNotBlank() }.randomOrNull()?.shownName
        val w = GadgetState.weather.value
        val facts = (DayLog.today().events.takeLast(8) + (if (visitors.isNotEmpty()) listOf("friends over today: ${visitors.joinToString()}") else emptyList()))
        val reply = quietAsk(
            "Write tonight's little radio show by $name, the blob on my Echo Show, broadcasting from its room: exactly 6 short lines " +
                "(each under 80 characters), one per line, no numbering. Open with a station ident (make up a cute station name), " +
                "then today's highlights from these real things:\n" + facts.joinToString("\n") { "- $it" } +
                (buddy?.let { "\nGive a shout-out to its friend $it." } ?: "") +
                (w?.tomorrowSummary?.takeIf { it.isNotBlank() }?.let { "\nTomorrow's weather: $it, high ${w.tomorrowHighF ?: w.highF}°F." } ?: "") +
                "\nEnd with a cosy goodnight sign-off. Reply with only the 6 lines: no commands, and don't change anything on any device.",
        )
        if (!life.idle() || !free()) return
        val lines = reply?.lines()?.map { it.trim().trimStart('-', '•', '*', ' ').replace(Regex("^\\d+[.)]\\s*"), "").trim('"') }
            ?.filter { it.isNotBlank() }?.take(6)?.takeIf { it.size >= 4 }
            ?: listOfNotNull("📻 This is Radio $name, live from my room!", "Today was a good one.",
                buddy?.let { "Shout-out to $it! You're the best." }, w?.tomorrowSummary?.takeIf { it.isNotBlank() }?.let { "Tomorrow: $it." },
                "That's the show. Goodnight, everyone! 🌙")
        store.update { it.copy(radio = (it.radio + RadioShow(todayKey, lines)).takeLast(14)) }
        val each = 19_000L
        GadgetState.setOnAir(System.currentTimeMillis() + lines.size * each + 3_000)
        GadgetState.hold(HeldItem.MIC, "📻 On air", lines.size * each.toInt() / 60_000 + 1)
        GadgetState.log("radio show on air")
        try {
            for (l in lines) {
                GadgetState.say("🎙️ $l", (each / 1000).toInt())
                delay(each)
            }
        } finally {
            GadgetState.setOnAir(0)
            GadgetState.hold(HeldItem.NONE, "", 0)
        }
        DayLog.event("did its late-night radio show", "radio")
    }

    // -- Pet tricks ----------------------------------------------------------------------------------

    /** One practice of [trick] (from the phone): once a trick a day; learned after a few days. */
    fun practise(trick: String): String {
        val pet = store.data.pet ?: return "no pet yet"
        val t = tricksFor(pet.kind).firstOrNull { it.equals(trick.trim(), ignoreCase = true) } ?: return "not a trick it can learn"
        val name = pet.name.ifBlank { "The ${pet.label}" }
        val have = pet.tricks[t] ?: 0
        if (have >= 100) {
            showTrick(t, true)
            GadgetState.say("$name shows off its ${t.lowercase()}! ✨", 10)
            return "showed it off"
        }
        if ("${today()}|$t" in pet.practised) {
            GadgetState.say("$name already practised the ${t.lowercase()} today. Tomorrow! 💤", 8)
            return "already practised today"
        }
        val next = (have + 30 + rnd.nextInt(11)).coerceAtMost(100)
        store.update { d ->
            d.copy(pet = d.pet?.copy(tricks = d.pet.tricks + (t to next), practised = d.pet.practised.filter { it.startsWith(today()) } + "${today()}|$t"))
        }
        showTrick(t, next >= 100)
        if (next >= 100) {
            GadgetState.react(Reaction.CELEBRATE)
            GadgetState.say("🎉 $name learned the ${t.lowercase()}!!", 20)
            DayLog.event("${pet.name.ifBlank { "its ${pet.label}" }} learned a trick: ${t.lowercase()}", "pet")
        } else GadgetState.say("$name practised the ${t.lowercase()}… $next% there! 🐾", 12)
        return if (next >= 100) "learned it!" else "$next%"
    }

    private fun showTrick(t: String, learned: Boolean) = GadgetState.showPetTrick(GadgetState.PetTrickShow(t, System.currentTimeMillis(), learned))

    /** Now and then, a learned trick unprompted. */
    private suspend fun petShowOffLoop() {
        delay(15 * 60_000L)
        while (true) {
            val pet = store.data.pet
            val learned = pet?.tricks?.filterValues { it >= 100 }?.keys.orEmpty()
            if (learned.isNotEmpty() && !isQuiet() && life.idle() && life.hour() in 9..21 && rnd.nextFloat() < 0.15f) {
                val t = learned.random()
                showTrick(t, true)
                GadgetState.say("Look! ${pet!!.name.ifBlank { "My ${pet.label}" }} does the ${t.lowercase()}! ✨", 8)
            }
            delay(30 * 60_000L)
        }
    }

    // -- Trivia night --------------------------------------------------------------------------------

    private var triviaJob: Job? = null

    /** Five questions from Pixel on [theme], answered by tapping on the Echo. */
    fun startTrivia(theme: String): String {
        val g = GadgetState.trivia.value
        if (g != null && !g.done && !g.failed) return "already playing"
        val th = theme.trim().ifBlank { "anything" }.take(40)
        GadgetState.setTrivia(GadgetState.TriviaGame(th))
        GadgetService.instance?.showApp()
        GadgetState.react(Reaction.HOP)
        triviaJob?.cancel()
        triviaJob = scope.launch {
            val reply = GadgetService.instance?.askQuietlyNow(
                "Make 5 multiple-choice trivia questions on the theme \"$th\" for a fun quiz night on my Echo Show: friendly, " +
                    "mixed difficulty, facts you're sure of. Reply with only JSON, no markdown: [{\"q\": \"the question, under 90 " +
                    "characters\", \"options\": [four short answers, under 28 characters each], \"answer\": the index (0-3) of the " +
                    "right one}]. Vary where the right answer is. Don't run any commands or change anything on any device.",
            )
            val qs = parseTrivia(reply)
            if (GadgetState.trivia.value?.theme != th) return@launch
            if (qs.size < 3) {
                GadgetState.setTrivia(GadgetState.TriviaGame(th, loading = false, failed = true))
                return@launch
            }
            GadgetState.setTrivia(GadgetState.TriviaGame(th, qs.take(5), loading = false))
            // Walked away: it packs up after a few minutes.
            while (true) {
                delay(15_000)
                val now = GadgetState.trivia.value ?: break
                if (now.done || now.failed) break
                if (System.currentTimeMillis() - now.lastTouchMs > 4 * 60_000L) { GadgetState.setTrivia(null); break }
            }
        }
        return "trivia night started ($th)"
    }

    fun answerTrivia(i: Int) {
        val g = GadgetState.trivia.value ?: return
        if (g.loading || g.done || g.picked >= 0 || g.at >= g.questions.size) return
        val q = g.questions[g.at]
        val right = i == q.answer
        GadgetState.setTrivia(g.copy(picked = i, score = g.score + if (right) 1 else 0, lastTouchMs = System.currentTimeMillis()))
        store.update { d ->
            val s = if (right) d.trivia.streak + 1 else 0
            d.copy(trivia = d.trivia.copy(streak = s, bestStreak = maxOf(d.trivia.bestStreak, s)))
        }
        if (right) {
            GadgetState.react(Reaction.CELEBRATE)
            GadgetState.say(listOf("YES!! 🎉", "You know everything! 🤩", "Correct! 🧠✨").random(), 3)
        } else {
            GadgetState.react(Reaction.SHAKE)
            GadgetState.say("Aww, nooo! 😩", 3)
        }
        scope.launch {
            delay(2_800)
            val g2 = GadgetState.trivia.value ?: return@launch
            if (g2.at != g.at) return@launch
            val next = g2.copy(at = g2.at + 1, picked = -1)
            GadgetState.setTrivia(next)
            if (next.done) finishTrivia(next)
        }
    }

    private fun finishTrivia(g: GadgetState.TriviaGame) {
        store.update { d ->
            d.copy(trivia = d.trivia.copy(played = d.trivia.played + 1, bestScore = maxOf(d.trivia.bestScore, g.score),
                rounds = (d.trivia.rounds + TriviaRound(today(), g.theme, g.score)).takeLast(20)))
        }
        val n = g.questions.size
        GadgetState.react(if (g.score * 2 >= n) Reaction.CELEBRATE else Reaction.HOP)
        GadgetState.say(when {
            g.score == n -> "A PERFECT $n/$n!! 🏆"
            g.score * 2 >= n -> "${g.score} out of $n! Nice! 🎉"
            else -> "${g.score} out of $n… rematch? 😅"
        }, 10)
        DayLog.event("played trivia night (${g.theme}): ${g.score}/$n", "trivia")
    }

    fun closeTrivia() {
        triviaJob?.cancel()
        GadgetState.setTrivia(null)
    }

    companion object {
        /** Bump to send Pixel a new heads-up (briefPixel) after adding things it should know about. */
        const val BRIEFING_VERSION = 1

        /** A new costume each month (added to the closet on the first day Puff's up). */
        val COSTUMES = mapOf(1 to "EARMUFFS", 2 to "HEART_GLASSES", 3 to "CLOVER_HAT", 4 to "BUNNY_EARS", 5 to "FLOWER_CROWN",
            6 to "SUNHAT", 7 to "STAR_GLASSES", 8 to "SNORKEL", 9 to "GRAD_CAP", 10 to "SCARECROW_HAT", 11 to "LEAF_CROWN", 12 to "ANTLERS")

        fun costumeLabel(name: String) = name.lowercase().replace('_', ' ')

        /** Emoji for each outfit (the Wardrobe tab and the phone's closet). */
        val OUTFIT_EMOJI = mapOf("PARTY_HAT" to "🎉", "SANTA_HAT" to "🎅", "WITCH_HAT" to "🧙", "SUNGLASSES" to "😎", "SCARF" to "🧣",
            "HEADPHONES" to "🎧", "CROWN" to "👑", "BOW" to "🎀", "FLOWER" to "🌸", "POPCORN" to "🍿", "RAINCOAT" to "🧥",
            "EARMUFFS" to "❄️", "HEART_GLASSES" to "💘", "CLOVER_HAT" to "☘️", "BUNNY_EARS" to "🐰", "FLOWER_CROWN" to "💐",
            "SUNHAT" to "👒", "STAR_GLASSES" to "🎆", "SNORKEL" to "🤿", "GRAD_CAP" to "🎓", "SCARECROW_HAT" to "🌾",
            "LEAF_CROWN" to "🍂", "ANTLERS" to "🦌")

        fun tricksFor(kind: String) = when (kind) {
            "snail" -> listOf("Eye-stalk wave", "Shell spin", "Speedy dash")
            "sprout" -> listOf("Leaf wiggle", "Bloom on cue", "Little bow")
            else -> listOf("Bubble heart", "Hoop jump", "Twirl")
        }

        private val LOCAL_FORTUNES = listOf(
            "A small kindness today will come back as a big smile.",
            "You will find something you lost. Probably a sock.",
            "Today is a good day to have a snack.",
            "Someone is thinking nice thoughts about you right now.",
            "Your next nap will be legendary.",
            "Big things are coming. Also small, cute things.",
            "Help! I'm stuck in a fortune cookie factory! (Just kidding.)",
            "The best is yet to come, and it might have sprinkles.",
            "You are braver than you think and comfier than you know.",
            "A surprise is waiting where you least expect it.",
        )

        /** Pixel's questions, from JSON (forgiving about extra text around it). */
        fun parseTrivia(reply: String?): List<GadgetState.TriviaQ> {
            val s = reply ?: return emptyList()
            val a = s.indexOf('[')
            val b = s.lastIndexOf(']')
            if (a < 0 || b <= a) return emptyList()
            val arr = runCatching { Json.parseToJsonElement(s.substring(a, b + 1)) as JsonArray }.getOrNull() ?: return emptyList()
            return arr.mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val q = (o["q"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val opts = (o["options"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.take(40) }
                    ?.filter { it.isNotEmpty() }?.takeIf { it.size in 2..4 } ?: return@mapNotNull null
                val ans = (o["answer"] as? JsonPrimitive)?.let { p -> p.intOrNull ?: opts.indexOfFirst { it.equals(p.contentOrNull, true) } }
                    ?.takeIf { it in opts.indices } ?: return@mapNotNull null
                GadgetState.TriviaQ(q.take(140), opts, ans)
            }
        }
    }
}
