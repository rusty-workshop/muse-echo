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
    private val notes: com.rusty.museecho.integrations.Notes? = null,
    private val glance: com.rusty.museecho.integrations.Glance? = null,
    private val frame: com.rusty.museecho.integrations.PhotoFrame? = null,
    private val quick: com.rusty.museecho.integrations.QuickActions? = null,
    private val showFrame: () -> Unit = {},
    private val agenda: com.rusty.museecho.integrations.Agenda? = null,
    private val specialDays: com.rusty.museecho.integrations.SpecialDays? = null,
    private val reminders: com.rusty.museecho.integrations.Reminders? = null,
    private val assignments: com.rusty.museecho.integrations.Assignments? = null,
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
        spec("notes.get", "Read the note pinned on the Echo Show's home screen (a titled list, e.g. a shopping list).")
        spec("notes.set", "Replace the pinned note on the Echo Show's home screen. It stays until cleared.",
            optional = mapOf(
                "title" to param("string", "Heading, e.g. \"Shopping\". Short."),
                "items" to param("array", "List lines, e.g. [\"milk\",\"eggs\"]. Up to ${com.rusty.museecho.integrations.Notes.MAX}."),
            ))
        spec("notes.add", "Add lines to the pinned note on the Echo Show (skips ones already there).",
            required = mapOf("items" to param("array", "Lines to add.")))
        spec("notes.remove", "Remove lines from the pinned note (matches case-insensitively, or by contained text).",
            required = mapOf("items" to param("array", "Lines to remove.")))
        spec("notes.clear", "Take the pinned note off the Echo Show's home screen.")
        spec("glance.set", "Set the one-line glance under the clock on the Echo Show's home screen, e.g. the next calendar " +
            "event or a reminder. Next to it the Echo shows its own weather.",
            required = mapOf("text" to param("string", "One short line, under ~60 characters.")),
            optional = mapOf("minutes" to param("integer", "Clear it after this many minutes. Omit to keep it.")))
        spec("glance.clear", "Clear the glance line under the clock.")
        spec("agenda.set", "Fill in the agenda page on the Echo Show (swipe left from the clock): today's events in order. " +
            "Replaces what's there; call it each morning (and when plans change).",
            required = mapOf("items" to param("array", "Events: [{\"time\": \"9:30 AM\" or \"All day\", \"title\": \"Dentist\", " +
                "\"location\": \"street address (optional)\"}], in order. With a time and an address the Echo works out " +
                "the drive and shows when to leave, with a nudge.")),
            optional = mapOf(
                "date" to param("string", "Heading, e.g. \"Friday, October 9\". Defaults to today."),
                "upcoming" to param("array", "The next few days for the \"Coming up\" section (up to 7 days, 8 events each): " +
                    "[{\"date\": \"2026-10-11\", \"items\": [{\"time\", \"title\"}]}]. Days with no events can be left out."),
            ))
        spec("countdown.add",
            "Count down to a big day on the Echo Show: the blob shows \"3 sleeps until <title>\" and tears a page off a " +
                "little calendar each morning. For trips, birthdays, holidays, anything Rusty is looking forward to. " +
                "Adding the same title again replaces it.",
            required = mapOf(
                "title" to param("string", "Short, e.g. \"Disney trip\"."),
                "date" to param("string", "yyyy-MM-dd."),
            ),
            optional = mapOf("emoji" to param("string", "One emoji to show with it.")))
        spec("countdown.remove", "Stop counting down to a day.", required = mapOf("title" to param("string", "Its title.")))
        spec("countdown.list", "The days the Echo Show is counting down to.")
        spec("reminders.set",
            "Today's reminders on the Echo Show: the blob holds a sticky note, and when a reminder's time comes it taps " +
                "the note and shows it. Replaces today's list; call it with the morning brief and when reminders change.",
            required = mapOf("items" to param("array", "[{\"text\": \"Call time 10:30\", \"time\": \"10:00 AM\"}]; time is optional.")))
        spec("reminders.clear", "Clear today's reminders on the Echo Show.")
        spec("assignments.set",
            "Schoolwork due soon, from Rusty's school calendar: the Echo Show counts each down on a card (amber the day " +
                "before, red on the day) and lists them on its agenda page. Replaces the whole list; send what's due in " +
                "the next two weeks.",
            required = mapOf("items" to param("array", "[{\"title\": \"ASL assignment\", \"due\": \"2026-10-13\", " +
                "\"time\": \"11:59 PM\" (optional), \"course\": \"ASL\" (optional)}]")))
        spec("assignments.clear", "Clear the schoolwork list on the Echo Show.")
        spec("agenda.clear", "Clear the agenda page on the Echo Show.")
        spec("frame.add", "Add a picture to the Echo Show's photo frame (the slideshow it shows when idle). Downloads and keeps it.",
            required = mapOf("url" to param("string", "http(s) URL of a JPEG, PNG or WebP image.")))
        spec("frame.list", "List the pictures in the Echo Show's photo frame.")
        spec("frame.remove", "Remove a picture from the photo frame by file name (from frame.list), or \"all\".",
            required = mapOf("which" to param("string", "File name, or \"all\".")))
        spec("frame.show", "Start the photo-frame slideshow on the Echo Show now.")
        spec("quick_actions.get", "Read the quick-action buttons on the Echo Show's home screen (label and the request each sends to you).")
        spec("quick_actions.set", "Replace the Echo Show's quick-action buttons (up to 4). Each sends its prompt to you when tapped.",
            required = mapOf("actions" to param("array", "List of {\"label\": short button text, \"prompt\": the request to send}.")))
        spec("media.control", "Control whatever is playing on the Echo Show (any app, or audio.play_url).",
            required = mapOf("action" to param("string", "One of: play_pause, play, pause, next, previous, stop.")))
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
        "notes.get" -> withNotes { n ->
            val note = n.value.value
            commandOk(buildJsonObject {
                put("title", note.title)
                put("items", JsonArray(note.items.map { JsonPrimitive(it) }))
            })
        }
        "notes.set" -> withNotes { n ->
            val note = n.set(params.str("title"), params.list("items"))
            commandOk(buildJsonObject { put("title", note.title); put("count", note.items.size) })
        }
        "notes.add" -> withNotes { n ->
            val note = n.add(params.list("items"))
            commandOk(buildJsonObject { put("count", note.items.size) })
        }
        "notes.remove" -> withNotes { n ->
            commandOk(buildJsonObject { put("removed", n.remove(params.list("items"))) })
        }
        "notes.clear" -> withNotes { n ->
            n.clear()
            commandOk(buildJsonObject { put("cleared", true) })
        }
        "glance.set" -> glance?.let { g ->
            val text = params.str("text") ?: return commandError("text is required")
            g.set(text, params.num("minutes")?.toInt())
            commandOk(buildJsonObject { put("shown", text.take(120)) })
        } ?: commandError("not available")
        "glance.clear" -> glance?.let { g ->
            g.set("", null)
            commandOk(buildJsonObject { put("cleared", true) })
        } ?: commandError("not available")
        "agenda.set" -> agenda?.let { a ->
            val items = (params["items"] as? JsonArray)?.mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                com.rusty.museecho.integrations.AgendaItem(o.str("time").orEmpty(), o.str("title") ?: return@mapNotNull null, o.str("location").orEmpty())
            } ?: return commandError("items must be a list of {time, title}")
            val date = params.str("date") ?: SimpleDateFormat("EEEE, MMMM d", Locale.US).format(Date())
            val upcoming = (params["upcoming"] as? JsonArray).orEmpty().mapNotNull { d ->
                val o = d as? JsonObject ?: return@mapNotNull null
                val dayItems = (o["items"] as? JsonArray).orEmpty().mapNotNull { e ->
                    val io = e as? JsonObject ?: return@mapNotNull null
                    com.rusty.museecho.integrations.AgendaItem(io.str("time").orEmpty(), io.str("title") ?: return@mapNotNull null, io.str("location").orEmpty())
                }
                com.rusty.museecho.integrations.UpcomingDay(o.str("date") ?: return@mapNotNull null, dayItems)
            }
            val saved = a.set(date, items, upcoming)
            commandOk(buildJsonObject { put("date", saved.date); put("count", saved.items.size) })
        } ?: commandError("not available")
        "countdown.add" -> specialDays?.let { sd ->
            val title = params.str("title")?.trim()?.take(30)?.takeIf { it.isNotEmpty() } ?: return commandError("title is required")
            val date = params.str("date")?.trim() ?: return commandError("date is required (yyyy-MM-dd)")
            val day = runCatching { java.time.LocalDate.parse(date) }.getOrNull() ?: return commandError("date must be yyyy-MM-dd")
            if (day.isBefore(java.time.LocalDate.now())) return commandError("that date has passed")
            sd.add(com.rusty.museecho.integrations.SpecialDay(title, day.toString(), params.str("emoji")?.take(8).orEmpty()))
            commandOk(buildJsonObject {
                put("title", title)
                put("days_away", java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.now(), day))
            })
        } ?: commandError("not available")
        "countdown.remove" -> specialDays?.let { sd ->
            val title = params.str("title") ?: return commandError("title is required")
            val before = sd.value.value.days.size
            sd.remove(title)
            commandOk(buildJsonObject { put("removed", before != sd.value.value.days.size) })
        } ?: commandError("not available")
        "countdown.list" -> specialDays?.let { sd ->
            commandOk(buildJsonObject {
                put("days", buildJsonArray {
                    sd.value.value.days.forEach { d -> add(buildJsonObject { put("title", d.title); put("date", d.date); put("emoji", d.emoji) }) }
                })
            })
        } ?: commandError("not available")
        "reminders.set" -> reminders?.let { rm ->
            val items = (params["items"] as? JsonArray)?.mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                com.rusty.museecho.integrations.Reminder(o.str("text") ?: return@mapNotNull null, o.str("time").orEmpty())
            } ?: return commandError("items must be a list of {text, time?}")
            val saved = rm.set(java.time.LocalDate.now().toString(), items)
            commandOk(buildJsonObject { put("count", saved.items.size) })
        } ?: commandError("not available")
        "assignments.set" -> assignments?.let { store ->
            val items = (params["items"] as? JsonArray)?.mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                com.rusty.museecho.integrations.Assignment(o.str("title") ?: return@mapNotNull null, o.str("due") ?: return@mapNotNull null,
                    o.str("time").orEmpty(), o.str("course").orEmpty())
            } ?: return commandError("items must be a list of {title, due, time?, course?}")
            val saved = store.set(items)
            commandOk(buildJsonObject { put("count", saved.items.size) })
        } ?: commandError("not available")
        "assignments.clear" -> assignments?.let { store ->
            store.set(emptyList())
            commandOk(buildJsonObject { put("cleared", true) })
        } ?: commandError("not available")
        "reminders.clear" -> reminders?.let { rm ->
            rm.clear()
            commandOk(buildJsonObject { put("cleared", true) })
        } ?: commandError("not available")
        "agenda.clear" -> agenda?.let { a ->
            a.clear()
            commandOk(buildJsonObject { put("cleared", true) })
        } ?: commandError("not available")
        "frame.add" -> frame?.let { f ->
            val url = params.str("url") ?: return commandError("url is required")
            runCatching { f.add(url) }.fold(
                onSuccess = { commandOk(buildJsonObject { put("added", it); put("count", f.photos().size) }) },
                onFailure = { commandError(it.message ?: "couldn't add it") },
            )
        } ?: commandError("not available")
        "frame.list" -> frame?.let { f ->
            commandOk(buildJsonObject { put("photos", JsonArray(f.photos().map { JsonPrimitive(it.name) })) })
        } ?: commandError("not available")
        "frame.remove" -> frame?.let { f ->
            val which = params.str("which") ?: return commandError("which is required")
            val n = f.remove(which)
            if (n == 0) commandError("no photo called \"$which\"") else commandOk(buildJsonObject { put("removed", n) })
        } ?: commandError("not available")
        "frame.show" -> frame?.let { f ->
            if (f.photos().isEmpty()) commandError("the frame has no photos yet; add some with frame.add")
            else {
                withContext(Dispatchers.Main) { showFrame() }
                commandOk(buildJsonObject { put("showing", f.photos().size) })
            }
        } ?: commandError("not available")
        "quick_actions.get" -> quick?.let { q ->
            commandOk(buildJsonObject {
                put("actions", JsonArray(q.value.value.map { a -> buildJsonObject { put("label", a.label); put("prompt", a.prompt) } }))
            })
        } ?: commandError("not available")
        "quick_actions.set" -> quick?.let { q ->
            val list = (params["actions"] as? JsonArray)?.mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val label = o.str("label") ?: return@mapNotNull null
                val prompt = o.str("prompt") ?: return@mapNotNull null
                com.rusty.museecho.integrations.QuickAction(label, prompt)
            } ?: return commandError("actions must be a list of {label, prompt}")
            val saved = q.set(list)
            commandOk(buildJsonObject { put("count", saved.size) })
        } ?: commandError("not available")
        "media.control" -> {
            val key = when (params.str("action")?.lowercase()) {
                "play_pause", "toggle" -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                "play" -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY
                "pause" -> android.view.KeyEvent.KEYCODE_MEDIA_PAUSE
                "next" -> android.view.KeyEvent.KEYCODE_MEDIA_NEXT
                "previous", "prev" -> android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS
                "stop" -> android.view.KeyEvent.KEYCODE_MEDIA_STOP
                else -> return commandError("action must be play_pause, play, pause, next, previous or stop")
            }
            val audio = context.getSystemService(android.media.AudioManager::class.java)
            audio.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, key))
            audio.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, key))
            commandOk(buildJsonObject { put("sent", params.str("action")) })
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

    private inline fun withNotes(block: (com.rusty.museecho.integrations.Notes) -> JsonObject): JsonObject =
        notes?.let(block) ?: commandError("not available")

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

        /** A string list, also accepting a single string or a comma-separated one. */
        private fun JsonObject.list(key: String): List<String> = when (val v = this[key]) {
            is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            is JsonPrimitive -> v.contentOrNull?.split(',', '\n').orEmpty()
            else -> emptyList()
        }.map { it.trim() }.filter { it.isNotEmpty() }
    }
}
