package com.rusty.museecho.integrations

import android.content.Context
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Drive times for leave-by reminders, from OpenStreetMap: Nominatim turns an
 * address into coordinates (cached for good, and their usage policy asks for
 * at most one lookup a second with a real User-Agent) and the public OSRM
 * server gives the driving time. Neither knows about traffic, so callers add
 * some slack.
 */
class Travel(context: Context) {
    private val http = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
    private val prefs = context.getSharedPreferences("museecho_geo", Context.MODE_PRIVATE)
    private var lastLookup = 0L

    suspend fun geocode(address: String): Pair<Double, Double>? = withContext(Dispatchers.IO) {
        val key = address.trim().lowercase()
        prefs.getString(key, null)?.let { cached ->
            if (cached == "none") return@withContext null
            val (a, b) = cached.split(',')
            return@withContext a.toDouble() to b.toDouble()
        }
        val wait = 1_100 - (System.currentTimeMillis() - lastLookup)
        if (wait > 0) Thread.sleep(wait)
        lastLookup = System.currentTimeMillis()
        val url = "https://nominatim.openstreetmap.org/search?format=json&limit=1&countrycodes=us&q=" +
            URLEncoder.encode(address.trim(), "UTF-8")
        val found = runCatching {
            http.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute().use { r ->
                if (!r.isSuccessful) return@use null
                val first = (Json.parseToJsonElement(r.body!!.string()) as JsonArray).firstOrNull() as? JsonObject ?: return@use Double.NaN to Double.NaN
                val lat = (first["lat"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
                val lon = (first["lon"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
                if (lat == null || lon == null) Double.NaN to Double.NaN else lat to lon
            }
        }.getOrNull() ?: return@withContext null // network trouble: try again later, don't cache
        if (found.first.isNaN()) {
            prefs.edit().putString(key, "none").apply()
            return@withContext null
        }
        prefs.edit().putString(key, "${found.first},${found.second}").apply()
        found
    }

    /** Driving minutes from [from] to [to], rounded up; null if the router didn't answer. */
    suspend fun driveMinutes(from: Pair<Double, Double>, to: Pair<Double, Double>): Int? = withContext(Dispatchers.IO) {
        val url = "https://router.project-osrm.org/route/v1/driving/" +
            "${from.second},${from.first};${to.second},${to.first}?overview=false"
        runCatching {
            http.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute().use { r ->
                if (!r.isSuccessful) return@use null
                val routes = (Json.parseToJsonElement(r.body!!.string()) as JsonObject)["routes"] as? JsonArray
                val seconds = ((routes?.firstOrNull() as? JsonObject)?.get("duration") as? JsonPrimitive)?.doubleOrNull ?: return@use null
                kotlin.math.ceil(seconds / 60.0).toInt()
            }
        }.getOrNull()
    }

    companion object {
        private const val UA = "museecho/0.3 (Echo Show home display; personal use)"

        /** Minutes to leave before an event: the drive plus 15% for traffic and 5 to get going. */
        fun leadMinutes(drive: Int): Int = kotlin.math.ceil(drive * 1.15).toInt() + 5
    }
}
