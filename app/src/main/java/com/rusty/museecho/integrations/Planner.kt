package com.rusty.museecho.integrations

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/** A big day Muse is counting down to ("3 sleeps until…"). [date] is yyyy-MM-dd. */
@Serializable
data class SpecialDay(val title: String, val date: String, val emoji: String = "")

@Serializable
data class SpecialDayList(val days: List<SpecialDay> = emptyList())

class SpecialDays(file: File) : JsonFileState<SpecialDayList>(file, SpecialDayList.serializer(), SpecialDayList()) {
    fun add(day: SpecialDay) = update { list ->
        SpecialDayList((list.days.filterNot { it.title.equals(day.title, true) } + day).sortedBy { it.date }.take(MAX))
    }

    fun remove(title: String) = update { list -> SpecialDayList(list.days.filterNot { it.title.equals(title.trim(), true) }) }

    /** Drop days that have passed. */
    fun prune(today: java.time.LocalDate) = update { list ->
        SpecialDayList(list.days.filter { runCatching { !java.time.LocalDate.parse(it.date).isBefore(today) }.getOrDefault(false) })
    }

    companion object {
        const val MAX = 10
    }
}

/** Today's reminders from Muse: the blob holds a sticky note and taps it when one is due. [time] is free text. */
@Serializable
data class Reminder(val text: String, val time: String = "")

@Serializable
data class ReminderDay(val date: String = "", val items: List<Reminder> = emptyList())

class Reminders(file: File) : JsonFileState<ReminderDay>(file, ReminderDay.serializer(), ReminderDay()) {
    fun set(date: String, items: List<Reminder>) = update {
        ReminderDay(date, items.map { Reminder(it.text.trim().take(80), it.time.trim().take(20)) }.filter { it.text.isNotEmpty() }.take(10))
    }

    fun clear() = update { ReminderDay() }
}

/** A scheduled request: at [time] (HH:mm, 24 h) on [days] (MONDAY..; empty = every day), ask Muse [prompt]. */
@Serializable
data class Routine(val id: String, val time: String, val days: List<String> = emptyList(), val label: String,
                   val prompt: String, val enabled: Boolean = true)

@Serializable
data class RoutineList(val items: List<Routine> = emptyList())

class Routines(file: File) : JsonFileState<RoutineList>(file, RoutineList.serializer(), RoutineList()) {
    fun set(items: List<Routine>) = update {
        RoutineList(items.filter { r ->
            r.prompt.isNotBlank() && runCatching { java.time.LocalTime.parse(r.time) }.isSuccess
        }.map { it.copy(label = it.label.trim().take(30).ifBlank { "Routine" }, prompt = it.prompt.trim().take(500),
            days = it.days.filter { d -> runCatching { java.time.DayOfWeek.valueOf(d) }.isSuccess }) }.take(20))
    }
}

/** Schoolwork with a due date (from Muse, off the school calendar). [due] is yyyy-MM-dd. */
@Serializable
data class Assignment(val title: String, val due: String, val time: String = "", val course: String = "")

@Serializable
data class AssignmentList(val items: List<Assignment> = emptyList())

class Assignments(file: File) : JsonFileState<AssignmentList>(file, AssignmentList.serializer(), AssignmentList()) {
    fun set(items: List<Assignment>) = update {
        AssignmentList(items.map { Assignment(it.title.trim().take(60), it.due.trim(), it.time.trim().take(20), it.course.trim().take(30)) }
            .filter { it.title.isNotEmpty() && runCatching { java.time.LocalDate.parse(it.due) }.isSuccess }
            .sortedBy { it.due }.take(20))
    }

    fun prune(today: java.time.LocalDate) = update { list ->
        AssignmentList(list.items.filter { runCatching { !java.time.LocalDate.parse(it.due).isBefore(today) }.getOrDefault(false) })
    }
}

/** An active National Weather Service alert for here. */
@Serializable
data class WeatherAlert(
    val id: String,
    val event: String,
    val headline: String = "",
    val severity: String = "",
    val description: String = "",
    val instruction: String = "",
    val endsMs: Long = 0,
)

/** api.weather.gov: free, no key, US only; it asks for a User-Agent that identifies the app. */
class WeatherAlerts(private val http: OkHttpClient = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()) {
    suspend fun fetch(lat: Double, lon: Double): List<WeatherAlert>? = withContext(Dispatchers.IO) {
        val url = "https://api.weather.gov/alerts/active?point=%.4f,%.4f".format(java.util.Locale.US, lat, lon)
        runCatching {
            http.newCall(
                Request.Builder().url(url)
                    .header("User-Agent", "museecho (Echo Show home display)")
                    .header("Accept", "application/geo+json").build()
            ).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val root = Json.parseToJsonElement(resp.body!!.string()) as JsonObject
                (root["features"] as? JsonArray).orEmpty().mapNotNull { f ->
                    val p = (f as? JsonObject)?.get("properties") as? JsonObject ?: return@mapNotNull null
                    fun str(k: String) = (p[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
                    val ends = (str("ends").ifEmpty { str("expires") }).let { t ->
                        runCatching { java.time.OffsetDateTime.parse(t).toInstant().toEpochMilli() }.getOrDefault(0L)
                    }
                    WeatherAlert(str("id").ifEmpty { str("event") + ends }, str("event"), str("headline"), str("severity"),
                        str("description").take(1500), str("instruction").take(800), ends)
                }.filter { it.event.isNotEmpty() }
            }
        }.getOrNull()
    }
}
