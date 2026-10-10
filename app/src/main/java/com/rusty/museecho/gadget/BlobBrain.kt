package com.rusty.museecho.gadget

/** Small judgement calls for the blob, kept free of Android so they can be unit tested. */
object BlobBrain {
    /** What a reply's tone suggests for the blob's face, and whether it's worth a hop. */
    data class Tone(val mood: Mood, val hop: Boolean = false)

    private val CELEBRATE = listOf("congrat", "well done", "great job", "nice work", "nailed it", "happy birthday", "🎉", "🥳", "woohoo", "hooray")
    private val SYMPATHY = listOf("sorry to hear", "sorry about", "that's rough", "that sucks", "condolence", "that's tough", "hang in there", "😢", "😞")
    private val JOKE = listOf("haha", "lol", "😄", "😂", "😉", "😜", "pun intended", "just kidding", "(joke)")
    private val WORRY = listOf("warning", "careful", "be careful", "urgent", "is down", "went down", "failed", "outage", "⚠")
    private val NEGATION = listOf("no ", "not ", "nothing ", "n't ", "without ", "never ")
    private val SURPRISE = listOf("wow", "whoa", "no way", "!?", "😮", "😲")

    /** A rough guess from words in the reply; null when nothing stands out. */
    fun tone(reply: String): Tone? {
        val t = reply.lowercase()
        // A word counts unless it's negated just before ("nothing urgent", "not a warning", "no outage").
        fun said(word: String): Boolean {
            var i = t.indexOf(word)
            while (i >= 0) {
                val before = t.substring(maxOf(0, i - 16), i)
                if (NEGATION.none { before.contains(it) }) return true
                i = t.indexOf(word, i + word.length)
            }
            return false
        }
        fun any(words: List<String>) = words.any(::said)
        return when {
            any(CELEBRATE) -> Tone(Mood.HAPPY, hop = true)
            any(SYMPATHY) -> Tone(Mood.SAD)
            any(WORRY) -> Tone(Mood.WORRIED)
            any(JOKE) -> Tone(Mood.WINK)
            any(SURPRISE) -> Tone(Mood.SURPRISED)
            else -> null
        }
    }

    private val YES = Regex("""^\W*(yes|yep|yeah|yup|sure|absolutely|definitely|of course|correct)\b""")
    private val NO = Regex("""^\W*(no|nope|nah|not really|not yet|unfortunately not|sadly,? no)\b""")
    private val NOT_REALLY_NO = Regex("""^\W*no (problem|worries|prob)\b""")

    /** Whether a reply opens with a plain yes (true) or no (false); null if it does neither. */
    fun yesNo(reply: String): Boolean? {
        val t = reply.trim().lowercase()
        return when {
            YES.containsMatchIn(t) -> true
            NOT_REALLY_NO.containsMatchIn(t) -> null
            NO.containsMatchIn(t) -> false
            else -> null
        }
    }

    private val TIME = Regex("""^\s*(\d{1,2})(?::(\d{2}))?\s*([ap])?\.?\s*m?\.?\s*$""", RegexOption.IGNORE_CASE)

    /** Minutes after midnight for an agenda time like "9:30", "9:30 AM", "9am", "14:00" or "noon"; null if it isn't one. */
    fun minutesOfDay(time: String): Int? {
        val s = time.trim().lowercase()
        if (s == "noon") return 12 * 60
        if (s == "midnight") return 0
        // "9:30 - 10:30": the start.
        val start = s.split('-', '–').first()
        val m = TIME.find(start) ?: return null
        var hour = m.groupValues[1].toInt()
        val minute = m.groupValues[2].ifEmpty { "0" }.toInt()
        val half = m.groupValues[3]
        if (minute > 59) return null
        if (half.isNotEmpty()) {
            if (hour !in 1..12) return null
            if (half == "p" && hour != 12) hour += 12
            if (half == "a" && hour == 12) hour = 0
        } else if (hour > 23) return null
        return hour * 60 + minute
    }

    private val ISO = Regex("""\d{4}-\d{2}-\d{2}""")
    private val SLASHED = Regex("""^(\d{1,2})/(\d{1,2})(?:/(\d{2,4}))?$""")
    private val DAY_NUM = Regex("""\b(\d{1,2})(st|nd|rd|th)?\b""")
    private val MONTHS = listOf("january", "february", "march", "april", "may", "june", "july", "august",
        "september", "october", "november", "december")

    /**
     * Whether an agenda's date (free text from Muse: "Saturday, October 10",
     * "2026-10-10", "Oct 10", "10/10"...) means [today]. Blank counts as today.
     */
    fun isToday(date: String, today: java.time.LocalDate): Boolean {
        val d = date.trim().lowercase()
        if (d.isEmpty() || d == "today") return true
        if (ISO.matches(d.take(10))) runCatching { return java.time.LocalDate.parse(d.take(10)) == today }
        SLASHED.find(d)?.let { m ->
            return m.groupValues[1].toInt() == today.monthValue && m.groupValues[2].toInt() == today.dayOfMonth
        }
        // "Saturday, October 10" / "Oct 10th": a month name (or its first 3 letters) and the day number.
        val month = MONTH_RES.indexOfFirst { it.containsMatchIn(d) }
        val day = DAY_NUM.find(d)?.groupValues?.get(1)?.toInt()
        return month + 1 == today.monthValue && day == today.dayOfMonth
    }

    private val MONTH_RES by lazy { MONTHS.map { Regex("""\b${it.take(3)}""") } }

    val SLEEP_TALK = listOf(
        "…five more minutes…", "…mmm, pizza…", "…no, the other wire…", "…zzz… whee…",
        "…who moved the moon…", "…I'm a very good blob…", "…shh, the server's sleeping…", "…more confetti…",
    )

    val BORED = listOf("psst… wanna chat?", "it's quiet in here…", "hello? anyone?", "I know a good joke…", "*taps mic*")
}
