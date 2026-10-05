package com.rusty.museecho.integrations

import java.io.File
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** A countdown timer or a clock alarm. Alarms with [days] repeat; without, they ring once. */
@Serializable
data class Countdown(
    val id: String,
    val kind: String, // "timer" or "alarm"
    val label: String,
    val dueAtMs: Long,
    val time: String = "", // alarms: "HH:mm"
    val days: List<String> = emptyList(), // alarms: MONDAY..SUNDAY
    val durationMs: Long = 0, // timers
) {
    val isAlarm: Boolean get() = kind == "alarm"
}

/**
 * Timers and alarms the Muse (or you, through Muse) set on the Echo. Kept in
 * a JSON file so they survive restarts; the service ticks [due] every second.
 */
class Timers(private val file: File, private val now: () -> Long = System::currentTimeMillis) {
    private val json = Json { ignoreUnknownKeys = true }
    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<Countdown>> = _items.asStateFlow()

    /** What's ringing right now (rang and not yet dismissed). */
    private val _ringing = MutableStateFlow<List<Countdown>>(emptyList())
    val ringing: StateFlow<List<Countdown>> = _ringing.asStateFlow()

    fun addTimer(durationMs: Long, label: String): Countdown {
        require(durationMs in 1_000..(24 * 3600_000L)) { "timer must be between 1 second and 24 hours" }
        val item = Countdown(newId(), "timer", label.ifBlank { formatDuration(durationMs) + " timer" },
            now() + durationMs, durationMs = durationMs)
        save(_items.value + item)
        return item
    }

    fun addAlarm(time: String, label: String, days: List<String>): Countdown {
        val t = parseTime(time) ?: throw IllegalArgumentException("time must look like 07:30 or 7:30 pm")
        val dayList = days.map { parseDay(it) ?: throw IllegalArgumentException("unknown day: $it") }.distinct()
        val hhmm = "%02d:%02d".format(t.hour, t.minute)
        val item = Countdown(newId(), "alarm", label.ifBlank { "Alarm" }, nextOccurrence(hhmm, dayList.map { it.name }),
            time = hhmm, days = dayList.map { it.name })
        save(_items.value + item)
        return item
    }

    /** Cancel by id, by label (case-insensitive), or everything with "all". Returns how many went. */
    fun cancel(which: String, kind: String? = null): Int {
        val before = _items.value
        val keep = before.filterNot { item ->
            (kind == null || item.kind == kind) &&
                (which.equals("all", true) || item.id == which || item.label.equals(which, true))
        }
        save(keep)
        return before.size - keep.size
    }

    fun dismiss() {
        _ringing.value = emptyList()
    }

    /** Moves due items to [ringing]; repeating alarms are rescheduled. Returns the ones that just rang. */
    fun tick(): List<Countdown> {
        val t = now()
        val (due, rest) = _items.value.partition { it.dueAtMs <= t }
        if (due.isEmpty()) return emptyList()
        val rescheduled = due.filter { it.isAlarm && it.days.isNotEmpty() }
            .map { it.copy(dueAtMs = nextOccurrence(it.time, it.days, after = t + 60_000)) }
        save(rest + rescheduled)
        _ringing.value = _ringing.value + due
        return due
    }

    fun nextOccurrence(hhmm: String, days: List<String>, after: Long = now()): Long {
        val zone = ZoneId.systemDefault()
        val base = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(after), zone)
        val time = LocalTime.parse(hhmm)
        val allowed = days.map { DayOfWeek.valueOf(it) }.toSet()
        for (offset in 0..7) {
            val candidate = base.toLocalDate().plusDays(offset.toLong()).atTime(time)
            if (!candidate.isAfter(base)) continue
            if (allowed.isEmpty() || candidate.dayOfWeek in allowed) {
                return candidate.atZone(zone).toInstant().toEpochMilli()
            }
        }
        error("no next occurrence")
    }

    private fun save(list: List<Countdown>) {
        val sorted = list.sortedBy { it.dueAtMs }
        _items.value = sorted
        runCatching {
            val tmp = File(file.path + ".tmp")
            tmp.writeText(json.encodeToString(ListSerializer(Countdown.serializer()), sorted))
            tmp.renameTo(file)
        }
    }

    private fun load(): List<Countdown> = runCatching {
        json.decodeFromString(ListSerializer(Countdown.serializer()), file.readText())
    }.getOrDefault(emptyList())

    private fun newId() = UUID.randomUUID().toString().take(8)

    companion object {
        private val TIME_RE = Regex("""^\s*(\d{1,2})(?::(\d{2}))?\s*([ap])\.?m?\.?\s*$|^\s*(\d{1,2}):(\d{2})\s*$""", RegexOption.IGNORE_CASE)

        fun parseTime(text: String): LocalTime? {
            val m = TIME_RE.matchEntire(text) ?: return null
            return if (m.groupValues[4].isNotEmpty()) {
                val h = m.groupValues[4].toInt()
                val min = m.groupValues[5].toInt()
                if (h in 0..23 && min in 0..59) LocalTime.of(h, min) else null
            } else {
                var h = m.groupValues[1].toInt()
                val min = m.groupValues[2].ifEmpty { "0" }.toInt()
                if (h !in 1..12 || min !in 0..59) return null
                val pm = m.groupValues[3].equals("p", true)
                if (h == 12) h = 0
                LocalTime.of(if (pm) h + 12 else h, min)
            }
        }

        fun parseDay(text: String): DayOfWeek? {
            val t = text.trim().lowercase()
            return DayOfWeek.entries.firstOrNull {
                val full = it.getDisplayName(TextStyle.FULL, Locale.US).lowercase()
                t == full || (t.length >= 3 && full.startsWith(t))
            }
        }

        fun formatDuration(ms: Long): String {
            val s = (ms + 999) / 1000
            val h = s / 3600
            val m = (s % 3600) / 60
            val sec = s % 60
            return buildList {
                if (h > 0) add("${h}h")
                if (m > 0) add("${m}m")
                if (sec > 0 && h == 0L) add("${sec}s")
            }.joinToString(" ").ifEmpty { "0s" }
        }

        /** "4:59" / "1:02:03" for on-screen countdowns. */
        fun clock(ms: Long): String {
            val s = maxOf(0, (ms + 999) / 1000)
            val h = s / 3600
            return if (h > 0) "%d:%02d:%02d".format(h, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
        }
    }
}
