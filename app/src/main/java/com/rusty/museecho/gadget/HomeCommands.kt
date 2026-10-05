package com.rusty.museecho.gadget

import android.content.Context
import com.rusty.museecho.integrations.Apps
import com.rusty.museecho.integrations.Laptop
import com.rusty.museecho.integrations.Roku
import com.rusty.museecho.integrations.Timers
import com.rusty.museecho.protocol.LinkSession.Companion.commandError
import com.rusty.museecho.protocol.LinkSession.Companion.commandOk
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/**
 * Commands that reach past the Echo itself: the Roku TV, Sofa's modes,
 * timers and alarms, and the laptop's shortcuts.
 */
class HomeCommands(
    private val context: Context,
    private val roku: Roku,
    private val timers: Timers,
    private val laptop: Laptop,
    private val bringToFront: () -> Unit,
) {
    val specs: JsonObject = buildJsonObject {
        val keys = Roku.KEYS.keys.joinToString(", ")
        spec("tv.status", "What the living-room Roku TV is doing: its name, power state and the app on screen.")
        spec("tv.key", "Press a button on the Roku TV remote.",
            required = mapOf("key" to param("string", "One of: $keys.")),
            optional = mapOf("times" to param("integer", "Press it this many times (1-20). Default 1.")))
        spec("tv.apps", "List the apps installed on the Roku TV.")
        spec("tv.launch", "Open an app on the Roku TV, e.g. Netflix, YouTube, Hulu, Plex.",
            required = mapOf("app" to param("string", "App name (from tv.apps) or its numeric Roku id.")))
        spec("tv.type", "Type text into the search box or text field currently focused on the Roku TV.",
            required = mapOf("text" to param("string", "Text to type.")))
        spec("sofa.mode", "Switch the Echo Show to Rusty's Sofa control-panel app in a given mode.",
            required = mapOf("mode" to param("string", "One of: ${Apps.SOFA_MODES.joinToString(", ")}. " +
                "school = homework/assignments, tv = TV remote, coding = laptop hotkeys, ambient = clock/photo screensaver.")))
        spec("timer.set", "Start a countdown timer on the Echo Show. It shows on screen and rings when done.",
            optional = mapOf(
                "minutes" to param("number", "Length in minutes (may be fractional)."),
                "seconds" to param("integer", "Length in seconds, added to minutes."),
                "label" to param("string", "What it's for, e.g. \"pasta\". Spoken when it rings."),
            ))
        spec("alarm.set", "Set an alarm on the Echo Show for a clock time; optionally repeating on certain days.",
            required = mapOf("time" to param("string", "Like 07:30, 7:30 am or 6pm (Echo's local time).")),
            optional = mapOf(
                "label" to param("string", "What it's for. Spoken when it rings."),
                "days" to param("array", "Repeat on these weekdays, e.g. [\"monday\",\"friday\"]. Omit for a one-off."),
            ))
        spec("timers.list", "List the timers and alarms set on the Echo Show, with time remaining.")
        spec("timers.cancel", "Cancel timers/alarms on the Echo Show, or silence one that's ringing.",
            required = mapOf("which" to param("string", "An id or label from timers.list, \"all\", or \"ringing\" to silence.")),
            optional = mapOf("kind" to param("string", "Only \"timer\" or only \"alarm\".")))
        spec("laptop.shortcuts", "List the shortcuts Rusty has allowed on his laptop (same safelist as Sofa's Coding Mode).")
        spec("laptop.run", "Run one of the laptop's safelisted shortcuts (from laptop.shortcuts).",
            required = mapOf("shortcut" to param("string", "Its key or label.")),
            timeoutMs = 40_000)
    }

    /** Null when [command] isn't one of these. */
    suspend fun run(command: String, params: JsonObject): JsonObject? = when (command) {
        "tv.status" -> withTv { ip ->
            val info = roku.deviceInfo(ip)
            val app = roku.activeApp(ip)
            commandOk(buildJsonObject {
                put("name", info["user-device-name"] ?: info["friendly-device-name"] ?: "Roku")
                put("model", info["model-name"] ?: "")
                put("power", info["power-mode"] ?: "unknown")
                put("app", app?.name ?: "Home")
                put("ip", ip)
            })
        }
        "tv.key" -> withTv { ip ->
            val word = params.str("key")?.lowercase()?.replace(' ', '_')
            val key = Roku.KEYS[word] ?: Roku.KEYS.values.firstOrNull { it.equals(params.str("key"), true) }
                ?: return@withTv commandError("unknown key; use one of ${Roku.KEYS.keys.joinToString(", ")}")
            val times = (params.num("times")?.toInt() ?: 1).coerceIn(1, 20)
            var ok = true
            repeat(times) { ok = ok && roku.keypress(ip, key); if (times > 1) kotlinx.coroutines.delay(250) }
            if (ok) commandOk(buildJsonObject { put("pressed", key); put("times", times) }) else commandError("the TV didn't respond")
        }
        "tv.apps" -> withTv { ip ->
            commandOk(buildJsonObject {
                put("apps", buildJsonArray { roku.apps(ip).forEach { add(buildJsonObject { put("id", it.id); put("name", it.name) }) } })
            })
        }
        "tv.launch" -> withTv { ip ->
            val want = params.str("app") ?: return@withTv commandError("app is required")
            val apps = roku.apps(ip)
            val app = apps.firstOrNull { it.id == want } ?: apps.firstOrNull { it.name.equals(want, true) }
                ?: apps.firstOrNull { it.name.contains(want, true) }
                ?: return@withTv commandError("no app called \"$want\"; installed: ${apps.joinToString { it.name }}")
            if (roku.launch(ip, app.id)) commandOk(buildJsonObject { put("launched", app.name) }) else commandError("the TV didn't respond")
        }
        "tv.type" -> withTv { ip ->
            val text = params.str("text") ?: return@withTv commandError("text is required")
            if (roku.type(ip, text)) commandOk(buildJsonObject { put("typed", text) }) else commandError("the TV didn't respond")
        }
        "sofa.mode" -> {
            val mode = params.str("mode")?.lowercase()?.trim()
            when {
                mode !in Apps.SOFA_MODES -> commandError("mode must be one of ${Apps.SOFA_MODES.joinToString(", ")}")
                !Apps.installed(context, Apps.SOFA) -> commandError("Sofa isn't installed on this Echo Show")
                else -> {
                    withContext(Dispatchers.Main) { Apps.openSofa(context, mode!!) }
                    commandOk(buildJsonObject { put("mode", mode) })
                }
            }
        }
        "timer.set" -> {
            val ms = ((params.num("minutes") ?: 0.0) * 60_000 + (params.num("seconds") ?: 0.0) * 1_000).toLong()
            runCatching { timers.addTimer(ms, params.str("label").orEmpty()) }.fold(
                onSuccess = {
                    bringToFront()
                    commandOk(buildJsonObject { put("id", it.id); put("label", it.label); put("rings_at", clock(it.dueAtMs)) })
                },
                onFailure = { commandError(it.message ?: "couldn't set the timer") },
            )
        }
        "alarm.set" -> {
            val days = (params["days"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
            runCatching { timers.addAlarm(params.str("time").orEmpty(), params.str("label").orEmpty(), days) }.fold(
                onSuccess = {
                    commandOk(buildJsonObject {
                        put("id", it.id)
                        put("label", it.label)
                        put("rings_at", SimpleDateFormat("EEE h:mm a", Locale.US).format(Date(it.dueAtMs)))
                        put("repeats", if (it.days.isEmpty()) "no" else it.days.joinToString { d -> d.lowercase() })
                    })
                },
                onFailure = { commandError(it.message ?: "couldn't set the alarm") },
            )
        }
        "timers.list" -> commandOk(buildJsonObject {
            val now = System.currentTimeMillis()
            put("items", buildJsonArray {
                timers.items.value.forEach {
                    add(buildJsonObject {
                        put("id", it.id)
                        put("kind", it.kind)
                        put("label", it.label)
                        put("rings_at", SimpleDateFormat("EEE h:mm:ss a", Locale.US).format(Date(it.dueAtMs)))
                        put("remaining", Timers.formatDuration(it.dueAtMs - now))
                        if (it.days.isNotEmpty()) put("repeats", it.days.joinToString { d -> d.lowercase() })
                    })
                }
            })
            put("ringing", timers.ringing.value.size)
        })
        "timers.cancel" -> {
            val which = params.str("which") ?: return commandError("which is required")
            if (which.equals("ringing", true)) {
                val n = timers.ringing.value.size
                timers.dismiss()
                commandOk(buildJsonObject { put("silenced", n) })
            } else {
                val n = timers.cancel(which, params.str("kind")?.lowercase())
                if (n == 0) commandError("nothing matched \"$which\"") else commandOk(buildJsonObject { put("cancelled", n) })
            }
        }
        "laptop.shortcuts" -> withLaptop {
            val list = laptop.shortcuts()
            if (list.isEmpty()) commandError("couldn't reach the laptop at ${laptop.host}, or it has no shortcuts")
            else commandOk(buildJsonObject {
                put("shortcuts", buildJsonArray { list.forEach { add(buildJsonObject { put("key", it.key); put("label", it.label) }) } })
            })
        }
        "laptop.run" -> withLaptop {
            val want = params.str("shortcut") ?: return@withLaptop commandError("shortcut is required")
            val list = laptop.shortcuts()
            val key = list.firstOrNull { it.key == want }?.key ?: list.firstOrNull { it.label.equals(want, true) }?.key
                ?: list.firstOrNull { it.label.contains(want, true) }?.key ?: want
            val (ok, message) = laptop.run(key)
            if (ok) commandOk(buildJsonObject { put("ran", key); put("message", message) }) else commandError(message)
        }
        else -> null
    }

    private suspend fun withTv(block: suspend (String) -> JsonObject): JsonObject {
        val ip = roku.ip() ?: return commandError("couldn't find a Roku on the network")
        return block(ip)
    }

    private suspend fun withLaptop(block: suspend () -> JsonObject): JsonObject =
        if (!laptop.configured) commandError("the laptop link isn't set up; add the Sofa companion token in Muse Echo's setup")
        else block()

    private fun clock(ms: Long) = SimpleDateFormat("h:mm:ss a", Locale.US).format(Date(ms))

    companion object {
        internal fun param(type: String, description: String) = buildJsonObject {
            put("type", type)
            put("description", description)
        }

        internal fun JsonObjectBuilder.spec(
            name: String,
            description: String,
            required: Map<String, JsonObject> = emptyMap(),
            optional: Map<String, JsonObject> = emptyMap(),
            timeoutMs: Long? = null,
        ) {
            put(name, buildJsonObject {
                put("description", description)
                put("required", JsonObject(required))
                put("optional", JsonObject(optional))
                if (timeoutMs != null) put("timeout_ms", timeoutMs)
            })
        }

        private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        private fun JsonObject.num(key: String) = (this[key] as? JsonPrimitive)?.doubleOrNull
    }
}
