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
data class Letter(val friendId: String, val date: String, val text: String, val atMs: Long, val read: Boolean = false)

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
        "hideseek", "story", "party", "comfort", "gift", "redecorate", "squabble")

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

    fun letterPrompt(f: Friend, others: List<Friend>, recent: List<String>): String =
        "Write a short letter (3 to 5 sentences, cute and in character) from ${f.name}, a ${f.p.label} little blob " +
            "(${f.p.blurb}; ${f.bio}), to its friend the blob who lives on my Echo Show. " +
            (if (others.isNotEmpty()) "Its other friends are ${others.joinToString { it.name }}. " else "") +
            (if (recent.isNotEmpty()) "Things that happened lately: ${recent.joinToString("; ")}. " else "") +
            "Start with \"Dear Blob,\" and sign it from ${f.name}. Reply with only the letter: no commands, and don't change anything on any device."

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
