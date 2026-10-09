package com.rusty.museecho.gadget

import com.rusty.museecho.integrations.Timers
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What the Echo can do by itself when Muse is unreachable: timers, alarms,
 * the time and date, and the last weather it fetched. Works on typed text or
 * an offline transcript (Vosk speaks in lowercase words: "set a timer for
 * five minutes", "set an alarm for seven thirty a m").
 */
class OfflineAssistant(
    private val timers: Timers,
    private val weather: () -> com.rusty.museecho.integrations.WeatherNow?,
    private val name: () -> String,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun answer(raw: String): String {
        val t = normalise(raw)
        return when {
            t.isBlank() -> "I didn't catch that."
            Regex("""\b(cancel|stop|delete|clear)\b.*\b(timers?|alarms?)\b""").containsMatchIn(t) -> cancel(t)
            Regex("""\b(how (much|long)|time left|remaining)\b""").containsMatchIn(t) && Regex("""\btimers?\b""").containsMatchIn(t) -> left()
            Regex("""\btimer\b""").containsMatchIn(t) || Regex("""\b(set|start).*\bfor\b.*\b(seconds?|minutes?|hours?)\b""").containsMatchIn(t) -> timer(t)
            Regex("""\b(alarm|wake me)\b""").containsMatchIn(t) -> alarm(t)
            Regex("""\bwhat('?s| is) the (date|day)|what day|today's date\b""").containsMatchIn(t) ->
                "It's ${SimpleDateFormat("EEEE, MMMM d", Locale.US).format(Date(now()))}."
            Regex("""\b(what time|the time|time is it)\b""").containsMatchIn(t) ->
                "It's ${SimpleDateFormat("h:mm a", Locale.US).format(Date(now()))}."
            Regex("""\b(weather|temperature|outside|rain)\b""").containsMatchIn(t) -> weatherLine()
            else -> "I can't reach ${name()} right now. Offline I can set timers and alarms, and tell you the time and weather."
        }
    }

    private fun timer(t: String): String {
        val ms = duration(t) ?: return "How long should the timer be?"
        val label = Regex("""\b(?:for|called|named) (?:the |my )?([a-z ]+?)\s*$""").find(t)?.groupValues?.get(1)
            ?.takeIf { l -> !DURATION_WORD.containsMatchIn(l) && NUMBER_WORD.find(l) == null }?.trim().orEmpty()
        return runCatching { timers.addTimer(ms, label) }.fold(
            onSuccess = { "Timer set for ${Timers.formatDuration(ms)}." },
            onFailure = { it.message ?: "Couldn't set that timer." },
        )
    }

    private fun alarm(t: String): String {
        val time = clockTime(t) ?: return "What time should the alarm be?"
        return runCatching { timers.addAlarm(time, "Alarm", emptyList()) }.fold(
            onSuccess = { a -> "Alarm set for ${SimpleDateFormat("h:mm a", Locale.US).format(Date(a.dueAtMs))}." },
            onFailure = { it.message ?: "Couldn't set that alarm." },
        )
    }

    private fun cancel(t: String): String {
        val kind = when {
            Regex("""\balarms?\b""").containsMatchIn(t) -> "alarm"
            else -> "timer"
        }
        val n = timers.cancel("all", kind)
        if (timers.ringing.value.isNotEmpty()) timers.dismiss()
        return if (n == 0) "There's no $kind to cancel." else "Cancelled $n ${kind}${if (n == 1) "" else "s"}."
    }

    private fun left(): String {
        val running = timers.items.value.filter { !it.isAlarm }
        if (running.isEmpty()) return "No timers are running."
        return running.joinToString(" ") { "${it.label}: ${Timers.formatDuration(it.dueAtMs - now())} left." }
    }

    private fun weatherLine(): String {
        val w = weather() ?: return "I don't have the weather right now."
        return "It's ${w.tempF} degrees and ${w.summary.lowercase()}, with a high of ${w.highF} and a low of ${w.lowF}."
    }

    companion object {
        private val DURATION_WORD = Regex("""\b(seconds?|minutes?|hours?|mins?|secs?|hrs?)\b""")
        private val NUMBER_WORD = Regex("""\b(\d+|zero|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety|hundred|a|an|half)\b""")

        private val UNITS = mapOf(
            "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7,
            "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13,
            "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19,
        )
        private val TENS = mapOf(
            "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60, "seventy" to 70,
            "eighty" to 80, "ninety" to 90,
        )

        fun normalise(s: String) = s.lowercase(Locale.US).replace(Regex("""[^a-z0-9: ]"""), " ")
            .replace(Regex("""\s+"""), " ").trim()

        /** "twenty five" -> 25, "5" -> 5, "a"/"an" -> 1; reads from the start of [words]. Returns (value, words used). */
        fun number(words: List<String>): Pair<Int, Int>? {
            if (words.isEmpty()) return null
            words[0].toIntOrNull()?.let { return it to 1 }
            if (words[0] == "a" || words[0] == "an") return 1 to 1
            var total = 0
            var used = 0
            var i = 0
            while (i < words.size) {
                val w = words[i]
                when {
                    w in TENS && used == 0 -> { total += TENS.getValue(w); used++ }
                    w in UNITS && (used == 0 || (used == 1 && total % 10 == 0 && total >= 20)) -> { total += UNITS.getValue(w); used++ }
                    w == "hundred" && used > 0 -> { total *= 100; used++ }
                    else -> break
                }
                i++
            }
            return if (used == 0) null else total to used
        }

        /** Total of every "<number> <unit>" in [t], plus "half an hour" / "and a half". */
        fun duration(t: String): Long? {
            val words = t.split(' ')
            var ms = 0L
            var found = false
            var i = 0
            while (i < words.size) {
                if (words[i] == "half" && words.getOrNull(i + 1) in setOf("an", "a") && words.getOrNull(i + 2)?.startsWith("hour") == true) {
                    ms += 30 * 60_000L
                    found = true
                    i += 3
                    continue
                }
                val n = number(words.subList(i, words.size))
                if (n != null) {
                    // "two and a half minutes": the unit comes after "and a half".
                    val half = words.getOrNull(i + n.second) == "and" && words.getOrNull(i + n.second + 1) == "a" &&
                        words.getOrNull(i + n.second + 2) == "half"
                    val unitAt = i + n.second + if (half) 3 else 0
                    val unit = words.getOrNull(unitAt).orEmpty()
                    val factor = when {
                        unit.startsWith("sec") -> 1_000L
                        unit.startsWith("min") -> 60_000L
                        unit.startsWith("hour") || unit.startsWith("hr") -> 3_600_000L
                        else -> 0L
                    }
                    if (factor > 0) {
                        ms += n.first * factor + if (half) factor / 2 else 0
                        found = true
                        i = unitAt + 1
                        continue
                    }
                }
                i++
            }
            return if (found && ms > 0) ms else null
        }

        /** "seven thirty a m", "7:30 pm", "six", "noon" -> "7:30 am" style text Timers.parseTime understands. */
        fun clockTime(t: String): String? {
            if (Regex("""\bnoon\b""").containsMatchIn(t)) return "12:00 pm"
            if (Regex("""\bmidnight\b""").containsMatchIn(t)) return "12:00 am"
            Regex("""\b(\d{1,2}):(\d{2})\s*(a ?m|p ?m)?\b""").find(t)?.let { m ->
                return "${m.groupValues[1]}:${m.groupValues[2]}" + ampm(m.groupValues[3], t)
            }
            val after = t.substringAfter(" for ", t.substringAfter(" at ", t)).split(' ')
            val words = after.dropWhile { it !in UNITS && it !in TENS && it.toIntOrNull() == null }
            val hour = number(words.take(1))?.first ?: return null
            val rest = words.drop(1)
            val minute = when {
                // "a m" / "p m" are am/pm, not "a" = one minute.
                rest.firstOrNull() in setOf("a", "p") && rest.getOrNull(1) == "m" -> 0
                rest.firstOrNull() == "o" && rest.getOrNull(1) == "clock" -> 0
                rest.firstOrNull() == "oh" -> number(rest.drop(1))?.first ?: 0
                else -> number(rest)?.first?.takeIf { it < 60 } ?: 0
            }
            if (hour !in 0..23) return null
            return "%d:%02d".format(hour, minute) + ampm("", t)
        }

        private fun ampm(given: String, t: String): String {
            val g = given.replace(" ", "")
            return when {
                g == "am" || Regex("""\b(a m|am|in the morning)\b""").containsMatchIn(t) -> " am"
                g == "pm" || Regex("""\b(p m|pm|in the (afternoon|evening)|tonight)\b""").containsMatchIn(t) -> " pm"
                else -> ""
            }
        }
    }
}
