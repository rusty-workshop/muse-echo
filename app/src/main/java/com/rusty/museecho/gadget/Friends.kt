package com.rusty.museecho.gadget

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** How a friend behaves: how it talks and moves, and how big it is. */
enum class Personality(val label: String, val scale: Float, val blurb: String) {
    BOUNCY("bouncy", 1.0f, "always hopping, talks a lot"),
    SHY("shy", 0.9f, "quiet, blushes, warms up slowly"),
    SLEEPY("sleepy", 1.25f, "big, slow and yawny"),
    SPARKLY("sparkly", 1.0f, "glittery and a bit dramatic"),
    TINY("tiny", 0.7f, "very small, very brave"),
}

/** One of the blob's friends. Named (with a little bio) by Pixel when it first visits. */
@Serializable
data class Friend(
    val id: String,
    val name: String = "",
    val bio: String = "",
    val personality: String = Personality.BOUNCY.name,
    val color: Long = 0xFFFF9EC7,
    val closeness: Int = 0,
    val visits: Int = 0,
    val lastVisitMs: Long = 0,
    val metMs: Long = 0,
    /** Activity name to times played (the favourite is the most played game). */
    val played: Map<String, Int> = emptyMap(),
    val wins: Int = 0,
    val losses: Int = 0,
    /** Little moments to bring up later ("the time the ball bonked you"). */
    val memories: List<String> = emptyList(),
    /** Last time it sent a postcard (once per absence). */
    val postcardMs: Long = 0,
    /** Its birthday, "MM-dd" (a party at the blob's that day). */
    val birthday: String = "",
    /** Talent shows won. */
    val trophies: Int = 0,
) {
    val p get() = Personality.entries.firstOrNull { it.name == personality } ?: Personality.BOUNCY
    val shownName get() = name.ifBlank { "New friend" }
    fun favourite(): String? = played.filterKeys { it in FriendsBrain.GAMES }.maxByOrNull { it.value }?.key
}

/** A friend's own diary: what it thought of a visit, in its voice. */
@Serializable
data class FriendDiaryEntry(val friendId: String, val date: String, val text: String, val atMs: Long)

/** Something two friends did together off-screen ("Moss and Doze built a pillow fort"). */
@Serializable
data class Happening(val date: String, val ids: List<String>, val text: String, val told: Boolean = false)

/** A letter from a friend, written by Pixel in its voice; kept in the blob's mailbox. */
@Serializable
data class Letter(val friendId: String, val date: String, val text: String, val atMs: Long, val read: Boolean = false,
    /** The blob's own reply, sent to [friendId] (rather than a letter from them). */
    val fromBlob: Boolean = false)

/** One panel of the daily comic: who's in it, how they look, where, and what's said. */
@Serializable
data class Panel(val who: List<String>, val mood: String = "happy", val prop: String = "", val place: String = "room",
    val speech: String = "", val caption: String = "")

/** One step of a multi-day story: who says it, and the line. */
@Serializable
data class StoryBeat(val who: String, val line: String)

/** A little story that plays out over several days, a chapter a day, in visits, letters and comics. */
@Serializable
data class Storyline(val title: String, val cast: List<String>, val chapters: List<StoryBeat>, val at: Int = 0,
    val started: String = "", val lastPlayed: String = "") {
    val done get() = at >= chapters.size
    val next get() = chapters.getOrNull(at)
}

/** Puff's pet: what it is, its name, and when it arrived (it grows over the weeks). */
@Serializable
data class Pet(val kind: String, val name: String = "", val bornMs: Long = 0, val fedDate: String = "", val pats: Int = 0) {
    /** 0.6 when new, full size after a month. */
    fun growth(nowMs: Long) = (0.6f + 0.4f * ((nowMs - bornMs) / (30 * 86_400_000f))).coerceIn(0.6f, 1f)
    val label get() = when (kind) { "snail" -> "snail"; "sprout" -> "sprout"; else -> "goldfish" }
}

/** The evening comic strip about the blob's day (three panels, written by Pixel). */
@Serializable
data class Comic(val date: String, val title: String, val panels: List<Panel>)

/** A postcard from a friend who's been away a while (pinned on the room's wall). */
@Serializable
data class Postcard(val friendId: String, val text: String, val date: String)

@Serializable
data class FriendsData(
    val friends: List<Friend> = emptyList(),
    val diary: List<FriendDiaryEntry> = emptyList(),
    val postcards: List<Postcard> = emptyList(),
    /** Activity names done today per friend id ("2026-10-11|id" -> list), for the diaries. */
    val today: Map<String, List<String>> = emptyMap(),
    /** How close two friends are with each other ("idA|idB", ids sorted) 0..100. */
    val bonds: Map<String, Int> = emptyMap(),
    val happenings: List<Happening> = emptyList(),
    val letters: List<Letter> = emptyList(),
    val comics: List<Comic> = emptyList(),
    val story: Storyline? = null,
    val pastStories: List<String> = emptyList(),
    val pet: Pet? = null,
    /** A friend Puff cheered up, who should write to say thanks (its id). */
    val owesThanks: String? = null,
) {
    fun bond(a: String, b: String) = bonds[FriendsBrain.pair(a, b)] ?: 0

    /** [id]'s closest friends among the others, closest first (only real bonds). */
    fun buddies(id: String): List<Pair<Friend, Int>> = friends.filter { it.id != id }
        .map { it to bond(id, it.id) }.filter { it.second > 0 }.sortedByDescending { it.second }
}

/** The friends, their diaries and postcards, in one small JSON file. */
class FriendsStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    var data: FriendsData = runCatching { json.decodeFromString(FriendsData.serializer(), file.readText()) }.getOrDefault(FriendsData())
        private set

    @Synchronized
    fun update(change: (FriendsData) -> FriendsData): FriendsData {
        data = change(data)
        runCatching { file.writeText(json.encodeToString(FriendsData.serializer(), data)) }
        GadgetState.setFriends(data)
        return data
    }

    fun friend(id: String) = data.friends.firstOrNull { it.id == id }

    fun edit(id: String, change: (Friend) -> Friend) = update { d -> d.copy(friends = d.friends.map { if (it.id == id) change(it) else it }) }
}

/** The pure parts: lines, games, names (unit-tested). */
object FriendsBrain {
    val GAMES = setOf("tictactoe", "rps", "catch", "hideseek", "tag")

    /** Everything a visit can be. */
    val ACTIVITIES = listOf("chat", "tag", "nap", "tictactoe", "rps", "catch", "dance", "movie", "tea", "sleepover",
        "hideseek", "story", "party", "comfort", "gift", "redecorate", "squabble",
        "birthday", "trickortreat", "snowball", "blockparty", "talentshow", "sad", "market")

    fun pair(a: String, b: String) = if (a < b) "$a|$b" else "$b|$a"

    /** Things friends get up to together when the blob isn't watching (past tense, after "A and B"). */
    val HANGOUTS = listOf("built a pillow fort", "had a picnic in the garden", "went puddle jumping", "watched the stars",
        "raced snails (the snails won)", "baked lopsided cookies", "made a blanket cape each", "had a staring contest",
        "found a really smooth pebble", "got lost in the sock drawer", "played hide and seek for three hours",
        "tried to catch the moon in a bucket")

    /** What a friend blurts out about an adventure, in its own voice ([other] did it with them). */
    fun hangoutLine(f: Friend, other: Friend, what: String): String = when {
        other.p == Personality.SLEEPY && rndOf(what) == 0 -> "${other.shownName} fell asleep at my house again! 😴"
        f.p == Personality.SPARKLY -> "Darling, ${other.shownName} and I $what. It was ICONIC. ✨"
        f.p == Personality.SHY -> "…${other.shownName} and I $what. It was nice. ☺️"
        f.p == Personality.TINY -> "${other.shownName} and I $what!! I was very brave! 💪"
        f.p == Personality.SLEEPY -> "*yawn* ${other.shownName} and I $what… then I napped."
        else -> "Guess what! ${other.shownName} and I $what! 🎉"
    }

    private fun rndOf(s: String) = s.length % 2

    fun letterPrompt(f: Friend, others: List<Friend>, recent: List<String>, blobName: String = "Blob", lastReply: String? = null): String =
        "Write a short letter (3 to 5 sentences, cute and in character) from ${f.name}, a ${f.p.label} little blob " +
            "(${f.p.blurb}; ${f.bio}), to its friend $blobName, the blob who lives on my Echo Show. " +
            (if (others.isNotEmpty()) "Its other friends are ${others.joinToString { it.name }}. " else "") +
            (if (recent.isNotEmpty()) "Things that happened lately: ${recent.joinToString("; ")}. " else "") +
            (lastReply?.let { "$blobName's last letter to ${f.name} said: \"$it\" so answer it. " } ?: "") +
            "Start with \"Dear $blobName,\" and sign it from ${f.name}. Reply with only the letter: no commands, and don't change anything on any device."

    fun replyPrompt(blobName: String, f: Friend, letter: String): String =
        "You are $blobName, the little blob that lives on my Echo Show (your on-screen face). Your friend ${f.name} " +
            "(a ${f.p.label} blob; ${f.bio}) sent you this letter:\n\"$letter\"\n" +
            "Write a short, warm reply (3 to 4 sentences, cute, in your own voice) answering what it said. " +
            "Start with \"Dear ${f.name},\" and sign it from $blobName. Reply with only the letter: no commands, and don't change anything on any device."

    fun blobNamePrompt(friends: List<Friend>): String =
        "The little animated blob on my Echo Show (your on-screen face, the one with the room and the friends" +
            (if (friends.isNotEmpty()) " ${friends.joinToString { it.name }}" else "") +
            ") needs a name of its own. Pick a short, cute one-word name that's different from its friends' names and from your own. " +
            "Reply in exactly this format and nothing else (no commands, don't change any device):\nNAME: the name"

    fun parseBlobName(reply: String): String? =
        Regex("""(?im)^\W*name\W*:\s*(.+)$""").find(reply)?.groupValues?.get(1)?.trim()?.trim('*', '"', ' ', '.', '!')
            ?.split(Regex("\\s+"))?.firstOrNull()?.takeIf { it.length in 2..16 }?.replaceFirstChar { it.uppercase() }

    /** A birthday for a new friend: any day of the year ("MM-dd"). */
    fun randomBirthday(rnd: java.util.Random): String {
        val d = java.time.LocalDate.of(2026, 1, 1).plusDays(rnd.nextInt(365).toLong())
        return "%02d-%02d".format(d.monthValue, d.dayOfMonth)
    }

    fun isBirthday(f: Friend, date: java.time.LocalDate) = f.birthday == "%02d-%02d".format(date.monthValue, date.dayOfMonth)

    /** What each personality does at the talent show (a few lines, in order). */
    fun talentAct(f: Friend): List<String> = when (f.p) {
        Personality.BOUNCY -> listOf("🎤 For my act… a bouncy song!", "♪ Boing boing, la la la ♪", "♪ BOING! ♪ 🎉")
        Personality.SHY -> listOf("…um. I'll do a magic trick. 🪄", "*pulls a pebble out of a hat*", "Ta-da… ☺️")
        Personality.SLEEPY -> listOf("My talent is… *yawn*…", "…sleeping on cue. 😴", "Zzz… (nailed it)")
        Personality.SPARKLY -> listOf("Prepare to be DAZZLED! ✨", "*twirls in a cloud of glitter* 💫", "Thank you, thank you! 👑")
        Personality.TINY -> listOf("Watch this!! 💪", "*does a tiny backflip*", "I stuck the landing!! 🏅")
    }

    val COMIC_MOODS = setOf("happy", "sleepy", "surprised", "sad", "curious", "wink", "worried", "neutral")
    val COMIC_PROPS = setOf("", "umbrella", "coffee", "letter", "gift", "cake", "star", "heart", "note", "hourglass", "balloon", "package", "sunglasses")
    val COMIC_PLACES = setOf("room", "window", "neighbourhood", "night", "outside")

    fun comicPrompt(blobName: String, facts: List<String>, friends: List<Friend>): String =
        "Write tonight's 3-panel comic strip about $blobName, the little blob on my Echo Show, based on these real things from today:\n" +
            facts.joinToString("\n") { "- $it" } + "\n" +
            (if (friends.isNotEmpty()) "Its friends (who can appear): ${friends.joinToString { "${it.name} (${it.p.label})" }}.\n" else "") +
            "Make it a tiny story with a punchline in panel 3. Reply in exactly this format and nothing else (no commands, don't change any device):\n" +
            "TITLE: a short title\n" +
            "PANEL: who | mood | prop | place | speech | caption\n(three PANEL lines)\n" +
            "who = $blobName and/or up to two friend names, comma separated; mood = one of ${COMIC_MOODS.joinToString()}; " +
            "prop = one of ${COMIC_PROPS.filter { it.isNotEmpty() }.joinToString()} or none; place = one of ${COMIC_PLACES.joinToString()}; " +
            "speech = what the first one says (under 60 characters); caption = a narrator line (under 40 characters, may be empty)."

    fun parseComic(reply: String, date: String): Comic? {
        val title = Regex("""(?im)^\W*title\W*:\s*(.+)$""").find(reply)?.groupValues?.get(1)?.trim()?.trim('*', '"') ?: "Today"
        val panels = Regex("""(?im)^\W*panel(?:\s*\d)?\W*:\s*(.+)$""").findAll(reply).mapNotNull { m ->
            val parts = m.groupValues[1].split("|").map { it.trim().trim('*', '"') }
            if (parts.size < 5) return@mapNotNull null
            val who = parts[0].split(",").map { it.trim() }.filter { it.isNotEmpty() }.take(3)
            if (who.isEmpty()) return@mapNotNull null
            Panel(who, parts[1].lowercase().takeIf { it in COMIC_MOODS } ?: "happy",
                parts[2].lowercase().takeIf { it in COMIC_PROPS } ?: "", parts[3].lowercase().takeIf { it in COMIC_PLACES } ?: "room",
                parts[4].take(80), parts.getOrNull(5).orEmpty().take(60))
        }.take(3).toList()
        return if (panels.size == 3) Comic(date, title.take(50), panels) else null
    }

    enum class Where(val label: String) { HOME("home"), OUT("out and about"), AWAY("away on a trip"), VISITING("visiting the blob"), ASLEEP("asleep") }

    /** Where a friend is right now, for the neighbourhood: a little routine of its own, steady within the hour. */
    fun whereabouts(f: Friend, visiting: Set<String>, nowMs: Long, hour: Int): Where = when {
        f.id in visiting -> Where.VISITING
        f.postcardMs > f.lastVisitMs && nowMs - f.postcardMs < 2 * 86_400_000L -> Where.AWAY
        hour >= 23 || hour < 7 -> Where.ASLEEP
        f.p == Personality.SLEEPY -> if (hour in 13..15) Where.ASLEEP else Where.HOME
        ((f.id.hashCode() + nowMs / 3_600_000L) % 3).toInt() == 0 -> Where.OUT
        else -> Where.HOME
    }

    fun storyPrompt(blobName: String, friends: List<Friend>, past: List<String>): String =
        "Start a little story that will play out over 4 or 5 days for $blobName (the blob on my Echo Show) and its friends " +
            friends.joinToString { "${it.name} (${it.p.label}: ${it.bio})" } + ". Something small and cute with a goal, e.g. a climb, a show, " +
            "a lost thing, a building project, a surprise. " + (if (past.isNotEmpty()) "Earlier stories (don't repeat): ${past.joinToString()}. " else "") +
            "Pick 1 to 3 of the friends as the cast. One chapter a day; each chapter is ONE short line (under 90 characters) said by a cast member " +
            "(or by $blobName), moving the story on; the last chapter wraps it up happily. Reply in exactly this format and nothing else " +
            "(no commands, don't change any device):\nTITLE: the title\nCAST: names, comma separated\nCHAPTER: who | the line\n(4 or 5 CHAPTER lines)"

    fun parseStory(reply: String, date: String): Storyline? {
        val title = Regex("""(?im)^\W*title\W*:\s*(.+)$""").find(reply)?.groupValues?.get(1)?.trim()?.trim('*', '"') ?: return null
        val cast = Regex("""(?im)^\W*cast\W*:\s*(.+)$""").find(reply)?.groupValues?.get(1)?.split(",")?.map { it.trim().trim('*') }
            ?.filter { it.isNotEmpty() }.orEmpty()
        val beats = Regex("""(?im)^\W*chapter(?:\s*\d)?\W*:\s*(.+)$""").findAll(reply).mapNotNull { m ->
            val parts = m.groupValues[1].split("|", limit = 2).map { it.trim().trim('*', '"') }
            if (parts.size == 2 && parts[1].isNotEmpty()) StoryBeat(parts[0], parts[1].take(120)) else null
        }.take(6).toList()
        return if (beats.size >= 3 && cast.isNotEmpty()) Storyline(title.take(60), cast.take(3), beats, 0, date) else null
    }

    val PET_KINDS = listOf("goldfish", "snail", "sprout")

    fun petNamePrompt(kind: String, blobName: String, taken: List<String>): String =
        "$blobName (the blob on my Echo Show) just got a tiny pet $kind for its room. Give it a short, cute one-word name" +
            (if (taken.isNotEmpty()) " that isn't one of these: ${taken.joinToString()}" else "") +
            ". Reply in exactly this format and nothing else (no commands, don't change any device):\nNAME: the name"

    /** Why a friend might turn up sad (Puff cheers it up). */
    fun sadReason(f: Friend, rnd: java.util.Random): String = when (f.p) {
        Personality.SHY -> listOf("I lost my favourite pebble… 🥺", "Someone laughed at my bow… 😢")
        Personality.SLEEPY -> listOf("I had a bad dream… 😟", "I couldn't sleep at all… 😩")
        Personality.SPARKLY -> listOf("My glitter ran out!! 😭", "Nobody noticed my new sparkle… 😔")
        Personality.TINY -> listOf("I couldn't reach the top shelf… 😞", "A pigeon was mean to me… 😢")
        Personality.BOUNCY -> listOf("I bounced too high and bonked my head… 🤕", "It rained on my picnic… 🌧️")
    }.let { it[rnd.nextInt(it.size)] }

    /** What each friend sells on market day. */
    fun marketGoods(f: Friend): String = when (f.p) {
        Personality.BOUNCY -> "bouncy balls 🎾"
        Personality.SHY -> "smooth pebbles 🪨"
        Personality.SLEEPY -> "extra-soft pillows 🛌"
        Personality.SPARKLY -> "glitter jars ✨"
        Personality.TINY -> "tiny hats 🎩"
    }

    /** Things Puff can bring home from the market (and get as gifts). */
    val MARKET = listOf(Decor.WIND_CHIME, Decor.TEAPOT, Decor.CUSHION, Decor.BUNTING, Decor.WALL_CLOCK)

    /** The window-box garden's stage from its age in days (rain counts double). */
    fun gardenStage(days: Int, rainyDays: Int, month: Int): Int {
        val grown = days + rainyDays
        val stage = when { grown < 2 -> 0; grown < 5 -> 1; grown < 9 -> 2; else -> 3 }
        // Flowers only in the growing season (March to October); otherwise just leaves.
        return if (stage == 3 && month !in 3..10) 2 else stage
    }

    fun activityLabel(a: String) = when (a) {
        "tictactoe" -> "tic-tac-toe"
        "rps" -> "rock-paper-scissors"
        "hideseek" -> "hide and seek"
        "tea" -> "tea time"
        "movie" -> "movie night"
        "dance" -> "a dance party"
        "story" -> "storytime"
        "party" -> "a party"
        "comfort" -> "a cheer-up visit"
        "gift" -> "a gift"
        "redecorate" -> "redecorating"
        "squabble" -> "a silly squabble"
        "sleepover" -> "a sleepover"
        "birthday" -> "a birthday party"
        "trickortreat" -> "trick-or-treating"
        "snowball" -> "a snowball fight"
        "blockparty" -> "the block party"
        "talentshow" -> "a talent show"
        "sad" -> "a cheer-up chat"
        "market" -> "market day"
        "nap" -> "a nap"
        else -> a
    }

    /** Colours friends get (one each, in turn). */
    val COLORS = listOf(0xFFFF9EC7, 0xFF7FE0A8, 0xFFFFC266, 0xFF9AB8FF, 0xFFFF8A65, 0xFFC79BFF)

    fun namePrompt(f: Friend, others: List<Friend>): String = buildString {
        append("The little blob on my Echo Show has a new friend who visits it: another blob, ")
        append("${f.p.label} (${f.p.blurb}), coloured ${colorName(f.color)}. ")
        if (others.isNotEmpty()) append("Its other friends are already called ${others.joinToString { it.name }}, so pick something different. ")
        append("Give it a short, cute name (one word) and a two-sentence bio about who it is and what it likes. ")
        append("Reply in exactly this format and nothing else (no commands, don't change any device):\nNAME: the name\nBIO: the bio")
    }

    fun parseName(reply: String): Pair<String, String>? {
        fun field(k: String) = Regex("""(?im)^\W*$k\W*:\s*(.+)$""").find(reply)?.groupValues?.get(1)?.trim()?.trim('*', '"', ' ')
        val name = field("name")?.split(Regex("\\s+"))?.firstOrNull()?.trim('.', ',', '!')?.takeIf { it.length in 2..16 } ?: return null
        val bio = field("bio")?.takeIf { it.isNotEmpty() } ?: return null
        return name.replaceFirstChar { it.uppercase() } to bio.take(300)
    }

    fun colorName(c: Long) = when (c) {
        0xFFFF9EC7 -> "pink"
        0xFF7FE0A8 -> "mint green"
        0xFFFFC266 -> "golden yellow"
        0xFF9AB8FF -> "periwinkle blue"
        0xFFFF8A65 -> "coral orange"
        0xFFC79BFF -> "lavender"
        else -> "pastel"
    }

    fun friendDiaryPrompt(f: Friend, did: List<String>, blobName: String = "the blob", alsoToday: List<String> = emptyList()): String =
        "Write a short diary entry (2 to 4 sentences, first person, cute and in character) for ${f.name}, a ${f.p.label} " +
            "little blob (${f.p.blurb}; ${f.bio}) who visited its friend $blobName on my Echo Show today. " +
            "Today they did: ${did.joinToString { activityLabel(it) }}. " +
            (f.memories.lastOrNull()?.let { "Something memorable: $it. " } ?: "") +
            (if (alsoToday.isNotEmpty()) "Also today: ${alsoToday.joinToString("; ")}. " else "") +
            "Reply with only the diary text: no title, no commands, and don't change anything on any device."

    /** A tic-tac-toe game played out: the moves in order (cell 0..8, X first) and the winner ('X', 'O' or null for a draw). */
    fun ticTacToe(rnd: java.util.Random): Pair<List<Int>, Char?> {
        val board = CharArray(9) { ' ' }
        val moves = mutableListOf<Int>()
        var turn = 'X'
        while (true) {
            val free = (0 until 9).filter { board[it] == ' ' }
            if (free.isEmpty()) return moves to null
            // Take a win if there is one, else block, else anywhere (a little sloppy on purpose).
            val pick = free.firstOrNull { wins(board, it, turn) && rnd.nextInt(4) > 0 }
                ?: free.firstOrNull { wins(board, it, if (turn == 'X') 'O' else 'X') && rnd.nextInt(3) > 0 }
                ?: free[rnd.nextInt(free.size)]
            board[pick] = turn
            moves += pick
            if (winner(board) != null) return moves to turn
            turn = if (turn == 'X') 'O' else 'X'
        }
    }

    private val LINES = listOf(listOf(0, 1, 2), listOf(3, 4, 5), listOf(6, 7, 8), listOf(0, 3, 6), listOf(1, 4, 7), listOf(2, 5, 8), listOf(0, 4, 8), listOf(2, 4, 6))

    fun winner(b: CharArray): Char? = LINES.firstOrNull { (a, c, d) -> b[a] != ' ' && b[a] == b[c] && b[a] == b[d] }?.let { b[it[0]] }

    private fun wins(b: CharArray, cell: Int, who: Char): Boolean {
        val copy = b.copyOf()
        copy[cell] = who
        return winner(copy) == who
    }

    /** Puff's tic-tac-toe move as O: win if it can, block you, else the middle or anywhere (a bit beatable). */
    fun puffMove(b: CharArray, rnd: java.util.Random): Int {
        val free = (0 until 9).filter { b[it] == ' ' }
        return free.firstOrNull { wins(b, it, 'O') }
            ?: free.firstOrNull { wins(b, it, 'X') && rnd.nextInt(5) > 0 }
            ?: (if (4 in free && rnd.nextBoolean()) 4 else null)
            ?: free[rnd.nextInt(free.size)]
    }

    /** Rock-paper-scissors: 1 if [a] beats [b], -1 if it loses, 0 for a tie (0 rock, 1 paper, 2 scissors). */
    fun rps(a: Int, b: Int): Int = when ((a - b + 3) % 3) { 0 -> 0; 1 -> 1; else -> -1 }

    val RPS_EMOJI = listOf("✊", "✋", "✌️")

    /** Chat lines: (true = the blob, false = the friend), adjusted to the friend's personality. */
    fun chat(f: Friend, rnd: java.util.Random): List<Pair<Boolean, String>> {
        val base = LifeMath.FRIEND_CHATS[rnd.nextInt(LifeMath.FRIEND_CHATS.size)]
        val opener = when (f.p) {
            Personality.SHY -> false to "…hi. 👉👈"
            Personality.SLEEPY -> false to "*yawn* …oh, hi. 😪"
            Personality.SPARKLY -> false to "Darling! ✨ I've arrived!"
            Personality.TINY -> false to "Hi!! (down here!) 👋"
            Personality.BOUNCY -> false to "HI HI HI! 🎈"
        }
        // Now and then they bring up something from before.
        val memory = f.memories.takeIf { it.isNotEmpty() && rnd.nextInt(3) == 0 }?.let { m ->
            listOf(true to "Remember $m? 😂", false to "How could I forget! 🤣")
        }.orEmpty()
        return listOf(opener) + base.drop(1) + memory
    }

    val STORY = listOf("Once upon a time…", "…there was a very round blob…", "…who lived on a shelf…", "…and dreamed of the moon…",
        "…so it bounced, and bounced…", "…all the way up…", "…and the moon said hi. 🌙", "The end. 💤")

    val SQUABBLE = listOf(false to "That's MY cookie! 🍪", true to "I saw it first!", false to "Hmph. 😤", true to "Hmph! 😤")

    val MAKE_UP = listOf(true to "…sorry. We can share? 🥺", false to "Halves! 🍪 Friends?", true to "Best friends. 💛")

    val POSTCARDS = listOf("Hi from the beach! 🏖️ The sand gets everywhere.", "Greetings from the mountains! 🏔️ It's chilly up here.",
        "Visiting my cousin in the fridge. 🧊 Brr!", "Hello from the library! 📚 Shhh.", "On a boat! ⛵ I'm a little seasick.",
        "Camping under the stars! ⭐ Wish you were here.", "At a bakery. 🥐 Don't tell anyone.", "Hi from the moon! 🌙 (not really)")

    val GIFTS = listOf(Decor.LAVA_LAMP, Decor.FRIENDSHIP_PHOTO, Decor.RARE_PLANT)

    /** Today's holiday, if any (a party day). */
    fun holiday(date: java.time.LocalDate): String? = when (date.monthValue to date.dayOfMonth) {
        1 to 1 -> "New Year's Day"
        2 to 14 -> "Valentine's Day"
        7 to 4 -> "the Fourth of July"
        10 to 31 -> "Halloween"
        12 to 25 -> "Christmas"
        else -> null
    }
}
