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

    val SLEEP_TALK = listOf(
        "…five more minutes…", "…mmm, pizza…", "…no, the other wire…", "…zzz… whee…",
        "…who moved the moon…", "…I'm a very good blob…", "…shh, the server's sleeping…", "…more confetti…",
    )

    val BORED = listOf("psst… wanna chat?", "it's quiet in here…", "hello? anyone?", "I know a good joke…", "*taps mic*")
}
