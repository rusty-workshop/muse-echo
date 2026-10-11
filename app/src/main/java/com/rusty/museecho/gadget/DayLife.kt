package com.rusty.museecho.gadget

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What happened today, for the blob's diary. Kept small; starts over each day. */
@Serializable
data class DayFacts(
    val date: String = "",
    val talks: Int = 0,
    val timers: Int = 0,
    val pokes: Int = 0,
    val nudges: Int = 0,
    val tvMinutes: Int = 0,
    /** Short "9:41 pm lights went off" style notes, oldest first. */
    val events: List<String> = emptyList(),
    val musicMinutes: Int = 0,
    val friendVisits: Int = 0,
    val friendTaps: Int = 0,
    /** Everything with a time, for the settings page's timeline. */
    val marks: List<DayMark> = emptyList(),
)

/** One moment on today's timeline: minutes after midnight, what kind, and a few words. */
@Serializable
data class DayMark(val min: Int, val kind: String, val label: String = "")

/** One night's diary entry, written by Pixel as the blob. */
@Serializable
data class DiaryEntry(val date: String, val text: String, val atMs: Long)

/** Today's small thing for the clock page's sign: a word, a fact or a bit of history. */
@Serializable
data class DailyThing(val date: String, val kind: String, val title: String, val text: String)

object DayLog {
    private val json = Json { ignoreUnknownKeys = true }
    private var file: File? = null
    @Volatile
    private var facts = DayFacts()
    /** The day before (what it dreams about after midnight). */
    @Volatile
    var yesterday = DayFacts()
        private set

    fun init(f: File) {
        file = f
        facts = runCatching { json.decodeFromString(DayFacts.serializer(), f.readText()) }.getOrDefault(DayFacts())
        yesterday = runCatching { json.decodeFromString(DayFacts.serializer(), File(f.path + ".prev").readText()) }.getOrDefault(DayFacts())
    }

    private fun nowMin(): Int {
        val c = java.util.Calendar.getInstance()
        return c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE)
    }

    private fun DayFacts.mark(kind: String, label: String = "") = copy(marks = (marks + DayMark(nowMin(), kind, label.take(60))).takeLast(300))

    private fun todayDate() = java.time.LocalDate.now().toString()

    @Synchronized
    private fun update(change: (DayFacts) -> DayFacts) {
        val base = facts.takeIf { it.date == todayDate() } ?: run {
            // A new day: keep the old one for tonight's dreams.
            if (facts.date.isNotEmpty()) {
                yesterday = facts
                runCatching { file?.let { File(it.path + ".prev").writeText(json.encodeToString(DayFacts.serializer(), facts)) } }
            }
            DayFacts(date = todayDate())
        }
        facts = change(base)
        runCatching { file?.writeText(json.encodeToString(DayFacts.serializer(), facts)) }
    }

    fun talked() = update { it.copy(talks = it.talks + 1).mark("talk") }
    fun timerRang() = update { it.copy(timers = it.timers + 1).mark("timer") }
    fun poked() = update { it.copy(pokes = it.pokes + 1).mark("poke") }
    fun nudged() = update { it.copy(nudges = it.nudges + 1).mark("nudge") }
    fun tvMinute() = update { it.copy(tvMinutes = it.tvMinutes + 1) }
    /** A friend visit, described ("Sprout came over for tic-tac-toe (Sprout won)"), for the diary and the timeline. */
    fun friendVisited(what: String) {
        val t = java.text.SimpleDateFormat("h:mm a", java.util.Locale.US).format(java.util.Date()).lowercase()
        update { it.copy(friendVisits = it.friendVisits + 1, events = (it.events + "$t $what").takeLast(20)).mark("friend", what) }
    }

    fun friendTapped(name: String) = update { it.copy(friendTaps = it.friendTaps + 1).mark("friend_tap", name) }
    fun musicMinute() = update { it.copy(musicMinutes = it.musicMinutes + 1) }
    fun mark(kind: String, label: String = "") = update { it.mark(kind, label) }

    /** A diary-worthy moment ([what]), also on the timeline as [kind]. */
    fun event(what: String, kind: String = "event") {
        val t = java.text.SimpleDateFormat("h:mm a", java.util.Locale.US).format(java.util.Date()).lowercase()
        update { it.copy(events = (it.events + "$t $what").takeLast(20)).mark(kind, what) }
    }

    /** The day it dreams about: yesterday until noon, today after. */
    fun dreamDay(): DayFacts = if (java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) < 12) yesterday else today()

    fun today(): DayFacts = facts.takeIf { it.date == todayDate() } ?: DayFacts(date = todayDate())
}

/** The prompts and parsing for the diary and the thing of the day (pure, so it's unit-tested). */
object DayWriting {
    fun diaryPrompt(f: DayFacts, weather: String?): String = buildString {
        append("Write tonight's diary entry for the little blob that lives on my Echo Show's screen, in its own voice ")
        append("(first person, 3 to 5 short sentences, cute and a little silly, no emoji spam). Base it on these real things from today:\n")
        weather?.let { append("- weather: $it\n") }
        append("- I talked to it ${f.talks} time(s)\n")
        if (f.pokes > 0) append("- it got poked ${f.pokes} time(s)\n")
        if (f.timers > 0) append("- ${f.timers} timer(s) or alarm(s) went off\n")
        if (f.nudges > 0) append("- it reminded me to drink water / stretch / rest my eyes ${f.nudges} time(s)\n")
        if (f.musicMinutes >= 10) append("- music played on the laptop for about ${f.musicMinutes} min\n")
        if (f.friendVisits > 0) append("- its blob friends came over ${f.friendVisits} time(s) (what they did is in the notes below)\n")
        if (f.friendTaps > 0) append("- I tapped its friends ${f.friendTaps} time(s) (they giggled)\n")
        if (f.tvMinutes >= 10) append("- the TV was on for about ${f.tvMinutes / 60} h ${f.tvMinutes % 60} min\n")
        f.events.forEach { append("- $it\n") }
        append("Reply with only the diary text: no title, no date, no commands, and don't change anything on any device.")
    }

    const val THING_PROMPT =
        "Pick one small \"thing of the day\" for the little sign on my Echo Show: a word of the day, a fun fact, " +
            "or something that happened on this day in history. Rotate between the three from day to day and pick " +
            "something that suits my interests. Reply in exactly this format and nothing else (no commands, don't change any device):\n" +
            "KIND: word, fact or history\nTITLE: at most 4 words\nTEXT: one or two short sentences"

    /** Pixel's reply in [THING_PROMPT]'s format, or null if it didn't stick to it. */
    fun parseThing(reply: String, date: String): DailyThing? {
        fun field(name: String) = Regex("""(?im)^\W*$name\W*:\s*(.+)$""").find(reply)?.groupValues?.get(1)?.trim()?.trim('*', '"', ' ')
        val title = field("title")?.takeIf { it.isNotEmpty() } ?: return null
        val text = field("text")?.takeIf { it.isNotEmpty() } ?: return null
        val kind = when (field("kind")?.lowercase().orEmpty()) {
            in setOf("word", "word of the day") -> "word"
            in setOf("history", "on this day", "this day in history") -> "history"
            else -> "fact"
        }
        return DailyThing(date, kind, title.take(40), text.take(400))
    }

    fun thingIcon(kind: String) = when (kind) {
        "word" -> "📖"
        "history" -> "📜"
        else -> "💡"
    }

    /** "3 h 10 min" / "25 min". */
    fun awayFor(ms: Long): String {
        val min = (ms / 60_000).toInt()
        return if (min >= 60) "${min / 60} h ${min % 60} min" else "$min min"
    }
}

/** The diary entries, newest last; about two months kept. */
class Diary(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private val ser = kotlinx.serialization.builtins.ListSerializer(DiaryEntry.serializer())

    fun load(): List<DiaryEntry> = runCatching { json.decodeFromString(ser, file.readText()) }.getOrDefault(emptyList())

    fun add(e: DiaryEntry): List<DiaryEntry> {
        val list = (load().filter { it.date != e.date } + e).takeLast(60)
        runCatching { file.writeText(json.encodeToString(ser, list)) }
        return list
    }
}
