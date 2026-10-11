package com.rusty.museecho.integrations

import android.content.Context
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The Sofa laptop companion daemon (GET /shortcuts, POST /command with
 * RUN_SHORTCUT), so Muse can fire the same safelisted shortcuts as Sofa's
 * Coding Mode. Uses the companion's own pairing token, entered in setup.
 */
/** What the laptop companion reports: battery and what's playing. */
data class LaptopStatus(
    val batteryPercent: Int?, val charging: Boolean, val track: String, val artist: String, val playing: Boolean,
    val volume: Int = 0, val muted: Boolean = false,
    val keepAwake: Boolean = false, val nightlight: Boolean = false, val brightness: Int? = null,
)

/** A desktop notification mirrored from the laptop. */
data class LaptopNotification(val id: Int, val app: String, val title: String, val text: String, val atMs: Long)

class Laptop(context: Context) {
    data class Shortcut(val key: String, val label: String)

    private val prefs = context.getSharedPreferences("museecho", Context.MODE_PRIVATE)
    private val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()

    var host: String
        get() = prefs.getString("laptop_host", null) ?: DEFAULT_HOST
        set(v) = prefs.edit().putString("laptop_host", v.trim()).apply()

    var token: String?
        get() = prefs.getString("laptop_token", null)
        set(v) = prefs.edit().putString("laptop_token", v?.trim()).apply()

    val configured: Boolean get() = !token.isNullOrBlank()

    suspend fun shortcuts(): List<Shortcut> = withContext(Dispatchers.IO) {
        val body = call(Request.Builder().url("http://$host/shortcuts").get()) ?: return@withContext emptyList()
        (Json.parseToJsonElement(body) as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val key = (o["key"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            Shortcut(key, (o["label"] as? JsonPrimitive)?.contentOrNull ?: key)
        }
    }

    /** Whether the companion answers at all (the laptop is on and home). */
    suspend fun online(): Boolean = withContext(Dispatchers.IO) {
        call(Request.Builder().url("http://$host/shortcuts").get()) != null
    }

    suspend fun status(): LaptopStatus? = withContext(Dispatchers.IO) {
        val body = call(Request.Builder().url("http://$host/status").get()) ?: return@withContext null
        runCatching {
            val o = Json.parseToJsonElement(body) as JsonObject
            val m = o["media"] as? JsonObject
            val b = o["battery"] as? JsonObject
            fun str(e: JsonObject?, k: String) = (e?.get(k) as? JsonPrimitive)?.contentOrNull.orEmpty()
            LaptopStatus(
                batteryPercent = str(b, "percent").toIntOrNull(),
                charging = str(b, "charging") == "true",
                track = str(m, "track"), artist = str(m, "artist"),
                playing = str(m, "isPlaying") == "true",
                volume = (o["volumePercent"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0,
                muted = (o["muted"] as? JsonPrimitive)?.contentOrNull == "true",
                keepAwake = (o["keepAwake"] as? JsonPrimitive)?.contentOrNull == "true",
                nightlight = (o["nightlight"] as? JsonPrimitive)?.contentOrNull == "true",
                brightness = (o["brightness"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull(),
            )
        }.getOrNull()
    }

    /**
     * One long-poll of the companion's desktop-notification feed (the same one the watch
     * mirrors). [after] -1 just learns the latest id. Returns (latest id, new items) or null.
     */
    suspend fun notifications(after: Int, waitS: Int): Pair<Int, List<LaptopNotification>>? = withContext(Dispatchers.IO) {
        val body = call(Request.Builder().url("http://$host/notifications?after=$after&wait=$waitS").get(), longPoll = true)
            ?: return@withContext null
        runCatching {
            val o = Json.parseToJsonElement(body) as JsonObject
            val latest = (o["latest"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: after
            val items = (o["items"] as? JsonArray).orEmpty().mapNotNull { e ->
                val n = e as? JsonObject ?: return@mapNotNull null
                fun s(k: String) = (n[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
                LaptopNotification(s("id").toIntOrNull() ?: return@mapNotNull null, s("app"), s("title"), s("text"), System.currentTimeMillis())
            }
            latest to items
        }.getOrNull()
    }

    /** A plain companion command (PLAY_PAUSE, NEXT, SET_VOLUME...). Returns whether it worked. */
    suspend fun command(command: String, intArg: Int = 0, stringArg: String = ""): Boolean = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("command", command)
            put("intArg", intArg)
            put("stringArg", stringArg)
        }
        val body = call(Request.Builder().url("http://$host/command")
            .post(payload.toString().toRequestBody("application/json".toMediaType())), allowError = true) ?: return@withContext false
        runCatching { ((Json.parseToJsonElement(body) as JsonObject)["ok"] as? JsonPrimitive)?.contentOrNull == "true" }.getOrDefault(false)
    }

    /** Returns (ok, message) from the companion. */
    suspend fun run(shortcutKey: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("command", "RUN_SHORTCUT")
            put("intArg", 0)
            put("stringArg", shortcutKey)
        }
        val body = call(Request.Builder().url("http://$host/command")
            .post(payload.toString().toRequestBody("application/json".toMediaType())), allowError = true)
            ?: return@withContext false to "couldn't reach the laptop at $host"
        val obj = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
        val ok = (obj?.get("ok") as? JsonPrimitive)?.contentOrNull == "true"
        ok to ((obj?.get("message") as? JsonPrimitive)?.contentOrNull ?: body.take(200))
    }

    private val longHttp = http.newBuilder().callTimeout(75, TimeUnit.SECONDS).readTimeout(70, TimeUnit.SECONDS).build()

    private fun call(builder: Request.Builder, allowError: Boolean = false, longPoll: Boolean = false): String? = runCatching {
        (if (longPoll) longHttp else http).newCall(builder.header("Authorization", "Bearer ${token.orEmpty()}").build()).execute().use {
            if (it.isSuccessful || allowError) it.body?.string() else null
        }
    }.getOrNull()

    companion object {
        const val DEFAULT_HOST = "192.168.1.68:8765"
    }
}
