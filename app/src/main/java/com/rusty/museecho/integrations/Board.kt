package com.rusty.museecho.integrations

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/** A small JSON-file-backed value with a StateFlow, for the Echo's home-screen boards. */
open class JsonFileState<T>(private val file: File, private val serializer: kotlinx.serialization.KSerializer<T>, default: T) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _value = MutableStateFlow(runCatching { json.decodeFromString(serializer, file.readText()) }.getOrDefault(default))
    val value: StateFlow<T> = _value.asStateFlow()

    @Synchronized
    fun update(transform: (T) -> T): T {
        val next = transform(_value.value)
        _value.value = next
        runCatching {
            val tmp = File(file.path + ".tmp")
            tmp.writeText(json.encodeToString(serializer, next))
            tmp.renameTo(file)
        }
        return next
    }
}

/** The pinned note on the home screen: a titled list (or a single line of text). */
@Serializable
data class PinnedNote(val title: String = "", val items: List<String> = emptyList())

class Notes(file: File) : JsonFileState<PinnedNote>(file, PinnedNote.serializer(), PinnedNote()) {
    fun set(title: String?, items: List<String>) = update {
        PinnedNote(title?.trim().orEmpty().take(40), items.map { i -> i.trim().take(80) }.filter { i -> i.isNotEmpty() }.take(MAX))
    }

    fun add(items: List<String>) = update { n ->
        val have = n.items.map { it.lowercase() }.toSet()
        n.copy(items = (n.items + items.map { it.trim().take(80) }.filter { it.isNotEmpty() && it.lowercase() !in have }).take(MAX))
    }

    /** Removes items matching (case-insensitive, or containing) each of [items]; returns how many went. */
    fun remove(items: List<String>): Int {
        var removed = 0
        update { n ->
            val keep = n.items.filterNot { item ->
                items.any { item.equals(it.trim(), true) || item.contains(it.trim(), true) }.also { if (it) removed++ }
            }
            n.copy(items = keep)
        }
        return removed
    }

    fun clear() = update { PinnedNote() }

    companion object {
        const val MAX = 12
    }
}

/** One exchange on the Echo, kept for the history screen. */
@Serializable
data class Exchange(val at: Long, val heard: String, val reply: String, val source: String? = null)

class History(file: File) : JsonFileState<List<Exchange>>(file, ListSerializer(Exchange.serializer()), emptyList()) {
    fun add(e: Exchange) = update { (it + e).takeLast(MAX) }

    companion object {
        const val MAX = 100
    }
}

/** The line Muse can set under the clock, e.g. the next calendar event. */
@Serializable
data class GlanceLine(val text: String = "", val untilMs: Long = 0)

class Glance(file: File) : JsonFileState<GlanceLine>(file, GlanceLine.serializer(), GlanceLine()) {
    fun set(text: String, minutes: Int?) = update {
        GlanceLine(text.trim().take(120), if (minutes != null && minutes > 0) System.currentTimeMillis() + minutes * 60_000L else 0)
    }

    fun current(now: Long = System.currentTimeMillis()): String =
        value.value.takeIf { it.untilMs == 0L || now < it.untilMs }?.text.orEmpty()
}

/** Today's weather from Open-Meteo (no key needed), the same source Sofa uses. */
@Serializable
data class WeatherNow(
    val tempF: Int, val highF: Int, val lowF: Int, val summary: String, val atMs: Long,
    /** Tomorrow's forecast ("" if it wasn't in the response). */
    val tomorrowSummary: String = "", val tomorrowHighF: Int? = null,
)

class Weather(private val http: OkHttpClient = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()) {
    suspend fun fetch(lat: Double, lon: Double): WeatherNow? = withContext(Dispatchers.IO) {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,weather_code&daily=temperature_2m_max,temperature_2m_min,weather_code" +
            "&temperature_unit=fahrenheit&timezone=auto&forecast_days=2"
        runCatching {
            http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val root = Json.parseToJsonElement(resp.body!!.string()) as JsonObject
                val current = root["current"] as JsonObject
                val daily = root["daily"] as JsonObject
                fun first(key: String) = ((daily[key] as JsonArray)[0] as JsonPrimitive).doubleOrNull ?: 0.0
                fun second(key: String) = ((daily[key] as? JsonArray)?.getOrNull(1) as? JsonPrimitive)?.doubleOrNull
                WeatherNow(
                    tempF = ((current["temperature_2m"] as JsonPrimitive).doubleOrNull ?: 0.0).let { Math.round(it).toInt() },
                    highF = Math.round(first("temperature_2m_max")).toInt(),
                    lowF = Math.round(first("temperature_2m_min")).toInt(),
                    summary = describe((current["weather_code"] as JsonPrimitive).intOrNull ?: -1),
                    atMs = System.currentTimeMillis(),
                    tomorrowSummary = second("weather_code")?.let { describe(it.toInt()) }.orEmpty(),
                    tomorrowHighF = second("temperature_2m_max")?.let { Math.round(it).toInt() },
                )
            }
        }.getOrNull()
    }

    companion object {
        /** WMO weather codes, as Open-Meteo reports them. */
        fun describe(code: Int): String = when (code) {
            0 -> "Clear"
            1 -> "Mostly clear"
            2 -> "Partly cloudy"
            3 -> "Overcast"
            45, 48 -> "Fog"
            51, 53, 55, 56, 57 -> "Drizzle"
            61, 63, 66 -> "Rain"
            65, 67 -> "Heavy rain"
            71, 73, 75, 77 -> "Snow"
            80, 81 -> "Showers"
            82 -> "Heavy showers"
            85, 86 -> "Snow showers"
            95 -> "Thunderstorms"
            96, 99 -> "Storms with hail"
            else -> ""
        }
    }
}

/** One line on the agenda page: a time (free text, e.g. "9:30 AM" or "All day") and what's happening. */
@Serializable
data class AgendaItem(val time: String = "", val title: String = "")

@Serializable
data class AgendaDay(val date: String = "", val items: List<AgendaItem> = emptyList(), val updatedMs: Long = 0)

/** The day's agenda Muse fills in (agenda.set), shown on the page left of the clock. */
class Agenda(file: File) : JsonFileState<AgendaDay>(file, AgendaDay.serializer(), AgendaDay()) {
    fun set(date: String, items: List<AgendaItem>) = update {
        AgendaDay(date.trim().take(40), items.map { AgendaItem(it.time.trim().take(20), it.title.trim().take(100)) }
            .filter { it.title.isNotEmpty() }.take(20), System.currentTimeMillis())
    }

    fun clear() = update { AgendaDay() }
}
