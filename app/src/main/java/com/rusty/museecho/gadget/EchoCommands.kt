package com.rusty.museecho.gadget

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.BatteryManager
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import com.rusty.museecho.BuildConfig
import com.rusty.museecho.protocol.LinkSession.Companion.commandError
import com.rusty.museecho.protocol.LinkSession.Companion.commandOk
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The commands the Muse can invoke on the Echo Show, and their
 * `commands_v2` specs (same shape as the Linux SDK's COMMAND_SPECS).
 */
class EchoCommands(
    private val context: Context,
    private val speaker: Speaker,
    private val bringToFront: () -> Unit,
    private val home: HomeCommands? = null,
    private val quiet: () -> Boolean = { false },
    private val healthExtras: () -> JsonObject = { JsonObject(emptyMap()) },
) {
    private val http = OkHttpClient.Builder().callTimeout(45, TimeUnit.SECONDS).build()
    private val audio = context.getSystemService(AudioManager::class.java)
    private var player: MediaPlayer? = null

    val specs: JsonObject get() = JsonObject(ownSpecs + (home?.specs ?: JsonObject(emptyMap())))

    private val ownSpecs: JsonObject = buildJsonObject {
        spec("device.health",
            "Echo Show status: uptime, memory, storage, Wi-Fi signal, speaker volume, screen state and app version. " +
                "The Echo Show runs on mains power and has no battery.")
        spec("display.draw_url",
            "Download an image (JPEG, PNG, WebP or GIF) and show it full screen on the Echo Show's " +
                "${SCREEN_W}x$SCREEN_H colour touchscreen, scaled to fit. Wakes the screen and brings this " +
                "app to the front. Stays up until display.show_animation or someone taps it. " +
                "A 2:1 landscape image fills the screen best.",
            required = mapOf("url" to param("string", "http:// or https:// URL of the image.")),
            optional = mapOf("caption" to param("string", "Short line shown under the image.")),
            timeoutMs = 60_000)
        spec("display.show_text",
            "Show a large text card on the Echo Show (a note, a list, a reminder). Wakes the screen and " +
                "brings this app to the front. Around 300 characters fit comfortably.",
            required = mapOf("text" to param("string", "Body text. Newlines are kept.")),
            optional = mapOf("title" to param("string", "Optional heading.")))
        spec("display.show_animation",
            "Clear any image or text card and bring back the idle face and clock.")
        spec("display.brightness",
            "Set the screen brightness, 0 to 100. Without level, reports the current one.",
            optional = mapOf("level" to param("integer", "Brightness, 0 to 100.")))
        spec("voice.say",
            "Speak text aloud on the Echo Show's speaker with on-device text-to-speech, and show it as a caption.",
            required = mapOf("text" to param("string", "What to say.")),
            timeoutMs = 120_000)
        spec("voice.configure",
            "Set the speaker volume. Without volume, reports the current one.",
            optional = mapOf("volume" to param("integer", "Speaker volume, 0 to 100.")))
        spec("audio.play_url",
            "Stream an audio file or internet radio URL (MP3, AAC, OGG, WAV) on the Echo Show's speaker. " +
                "Replies once playback starts.",
            required = mapOf("url" to param("string", "http:// or https:// URL of the audio.")),
            optional = mapOf("title" to param("string", "What it is, shown on screen, e.g. \"KEXP\".")),
            timeoutMs = 45_000)
        spec("audio.stop", "Stop audio started with audio.play_url.")
        spec("display.set_profile",
            "Tell the Echo Show your name, so its screen says it instead of \"Muse\" " +
                "(e.g. \"Tap the mic to talk to <name>\"). Call this once with your own name.",
            required = mapOf("name" to param("string", "Your name, as you'd like it shown.")))
        spec("blob.mood",
            "Set the expression on the Echo Show's animated blob (your on-screen face) for a while, to match " +
                "the mood of what you're saying. Otherwise it follows what's going on by itself (sleepy at night, " +
                "curious while listening, worried if the server is down). neutral clears it.",
            required = mapOf("mood" to param("string", "One of: ${Mood.entries.joinToString { it.name.lowercase() }}.")),
            optional = mapOf("seconds" to param("integer", "How long to hold it, 5 to 3600. Default 60.")))
        spec("blob.react",
            "Play a one-off animation on the Echo Show's blob: celebrate (confetti, for good news or a win), " +
                "hop, nod, shake (head shake, 'no'), wink (for a joke), yawn, stretch, hiccup, peek, wander, " +
                "wave (hello/goodbye), startle.",
            required = mapOf("reaction" to param("string", "One of: ${Reaction.entries.joinToString { it.name.lowercase() }}.")))
        spec("blob.style",
            "Change how the Echo Show's blob looks: its colour and/or something it wears. Left alone it picks " +
                "accessories itself (santa hat in December, witch hat at Halloween, sunglasses when it's hot, " +
                "a scarf when it's cold, headphones with music, popcorn while the TV plays in the evening). " +
                "Give either or both; \"auto\" hands that part back to the automatic choice.",
            optional = mapOf(
                "color" to param("string", "auto, a name (${BlobStyles.COLORS.keys.joinToString()}) or #RRGGBB."),
                "accessory" to param("string", "auto, or one of: ${Accessory.entries.joinToString { it.name.lowercase() }} " +
                    "(none = wear nothing, even when it would pick something). party_hat suits birthdays."),
                "hours" to param("integer", "How long to keep it, 1 to 720. Default: until changed."),
            ))
        spec("blob.hold",
            "Have the Echo Show's blob hold up a little prop, optionally with a short label on a card " +
                "(e.g. a package with \"Delivery today\", an umbrella with \"Rain at 3\", a gift for a birthday). " +
                "none clears it.",
            required = mapOf("item" to param("string", "One of: ${HeldItem.entries.joinToString { it.name.lowercase() }}.")),
            optional = mapOf(
                "text" to param("string", "Label shown on a card, up to 40 characters."),
                "minutes" to param("integer", "How long to hold it, 1 to 1440. Default 60."),
            ))
        spec("blob.sequence",
            "Play a little choreographed show on the Echo Show's blob: a list of steps run in order. Each step " +
                "may set any of mood (+ mood_seconds), reaction, say (+ say_seconds), color / accessory (+ hours), " +
                "hold (+ hold_text, hold_minutes), then waits wait_ms before the next (default: the reaction's " +
                "length, else 1500, max 10000). Up to 20 steps and 60 seconds. Values are the same as blob.mood, " +
                "blob.react, blob.say, blob.style and blob.hold. Example for good news: hop, then celebrate with " +
                "say \"You did it!\", then accessory party_hat for 1 hour.",
            required = mapOf("steps" to param("array", "The steps, each an object as described.")),
            timeoutMs = 75_000)
        spec("blob.friend",
            "Invite one of the blob's friends (other little blobs, each with a name and personality) over to the Echo " +
                "Show's Blob page for a visit. They also drop by by themselves now and then.",
            optional = mapOf(
                "activity" to param("string", "One of: ${FriendsBrain.ACTIVITIES.joinToString()}. " +
                    "(tictactoe = tic-tac-toe, rps = rock-paper-scissors, hideseek = hide and seek, tea = tea party, " +
                    "gift = brings a present for the blob's room.) Default: one that suits the moment."),
                "name" to param("string", "Which friend, by name. Default: whoever hasn't been over for a while."),
                "minutes" to param("integer", "How long it stays, 1 to 600."),
            ))
        spec("blob.friends",
            "List the blob's friends: their names, personalities, bios, how close they are, their favourite games, " +
                "who they're closest to among the others, and recent letters.")
        spec("blob.fortune", "Crack open a fortune cookie on the Echo Show: the blob holds it up and reads the fortune " +
            "(for \"fortune\", \"give me a fortune cookie\"). Returns the fortune.")
        spec("trivia.start", "Start trivia night on the Echo Show: you're asked (in the background) for 5 questions, which " +
            "Rusty answers by tapping; the blob cheers or groans at each.",
            optional = mapOf("theme" to param("string", "A theme (\"animals\", \"space\", \"90s music\"...). Default: anything.")))
        spec("blob.letter",
            "Have one of the blob's friends send it a letter now (you'll be asked to write it, in the friend's voice). " +
                "The blob holds it up and reads it out; it's kept in the Blob page's mailbox.",
            optional = mapOf("name" to param("string", "Which friend. Default: the closest one.")))
        spec("blob.feelings",
            "Tell the blob (Puff) how Rusty is doing, so it responds: when Rusty mentions a rough day, Puff holds a little heart " +
                "and a friend comes over to cheer him up; good news gets confetti; a big day coming up (a test, an event) gets " +
                "good luck that morning and a \"how did it go?\" in the evening.",
            required = mapOf("feeling" to param("string", "rough, great, or big_day.")),
            optional = mapOf(
                "note" to param("string", "A few words, e.g. \"your maths test\" or \"the job interview\"."),
                "date" to param("string", "For big_day: the day, yyyy-MM-dd."),
            ))
        spec("blob.say",
            "Show a short silent speech bubble beside the Echo Show's blob, e.g. \"Don't forget the trash!\". " +
                "Nothing is spoken, so it suits quiet hours. Empty text clears it.",
            required = mapOf("text" to param("string", "Up to 120 characters.")),
            optional = mapOf("seconds" to param("integer", "How long it stays, 5 to 86400. Default 600.")))
    }

    suspend fun run(command: String, params: JsonObject, timeoutMs: Long?): JsonObject = try {
        if (command.startsWith("blob.")) {
            // Pixel wants to be seen: step out of the photo slideshow (and hold it off a while).
            GadgetState.showFrame(false)
            GadgetState.lastTouch = System.currentTimeMillis()
        }
        withTimeout(timeoutMs ?: DEFAULT_TIMEOUT_MS) {
            when (command) {
                "device.health" -> health()
                "display.draw_url" -> drawUrl(params.string("url"), params.string("caption"))
                "display.show_text" -> showText(params.string("text"), params.string("title"))
                "display.show_animation" -> {
                    GadgetState.show(Screen.Idle)
                    commandOk(JsonObject(emptyMap()))
                }
                "display.brightness" -> brightness(params.int("level"))
                "voice.say" -> say(params.string("text"))
                "voice.configure" -> volume(params.int("volume"))
                "audio.play_url" -> playUrl(params.string("url"), params.string("title"))
                "audio.stop" -> stopAudio()
                "display.set_profile" -> setProfile(params.string("name"))
                "blob.mood" -> {
                    val mood = Mood.entries.firstOrNull { it.name.equals(params.string("mood")?.trim(), ignoreCase = true) }
                        ?: return@withTimeout commandError("mood must be one of: ${Mood.entries.joinToString { it.name.lowercase() }}")
                    GadgetState.setMood(mood, (params.int("seconds") ?: 60).coerceIn(5, 3600))
                    commandOk(JsonObject(emptyMap()))
                }
                "blob.style" -> blobStyle(params.string("color"), params.string("accessory"), params.int("hours"))
                "blob.hold" -> hold(params.string("item"), params.string("text"), params.int("minutes"))
                "blob.sequence" -> sequence(params["steps"])
                "blob.friend" -> {
                    val life = GadgetService.instance?.life ?: return@withTimeout commandError("not ready yet")
                    val msg = life.visit(params.string("activity"), params.int("minutes"), params.string("name"))
                    if (msg == "already visiting") commandError(msg) else commandOk(buildJsonObject { put("status", msg) })
                }
                "blob.friends" -> {
                    val fs = GadgetState.friends.value.friends
                    commandOk(buildJsonObject {
                        put("friends", kotlinx.serialization.json.JsonArray(fs.map { f ->
                            buildJsonObject {
                                put("name", f.shownName); put("personality", f.p.label); put("bio", f.bio)
                                put("closeness", f.closeness); put("visits", f.visits)
                                f.favourite()?.let { put("favourite_game", FriendsBrain.activityLabel(it)) }
                                put("closest_to", kotlinx.serialization.json.JsonArray(GadgetState.friends.value.buddies(f.id).take(3)
                                    .map { kotlinx.serialization.json.JsonPrimitive("${it.first.shownName} (${it.second})") }))
                            }
                        }))
                    })
                }
                "blob.letter" -> {
                    val fl = GadgetService.instance?.life?.friends ?: return@withTimeout commandError("not ready yet")
                    val f = fl.friendByName(params.string("name")) ?: fl.store.data.friends.filter { it.name.isNotBlank() }.maxByOrNull { it.closeness }
                        ?: return@withTimeout commandError("no named friends yet")
                    // Written in the background (it asks you), so this returns straight away.
                    GadgetService.instance?.scopeLaunch { fl.writeLetter(f) }
                    commandOk(buildJsonObject { put("status", "${f.shownName} is writing a letter") })
                }
                "blob.fortune" -> {
                    val text = GadgetService.instance?.life?.fun_?.fortune() ?: return@withTimeout commandError("not ready yet")
                    commandOk(buildJsonObject { put("fortune", text) })
                }
                "trivia.start" -> {
                    val r = GadgetService.instance?.life?.fun_?.startTrivia(params.string("theme").orEmpty()) ?: return@withTimeout commandError("not ready yet")
                    commandOk(buildJsonObject { put("status", r) })
                }
                "blob.feelings" -> {
                    val fl = GadgetService.instance?.life?.friends ?: return@withTimeout commandError("not ready yet")
                    val r = fl.rustyFeels(params.string("feeling").orEmpty(), params.string("note").orEmpty(), params.string("date"))
                    if (r == "ok") commandOk(JsonObject(emptyMap())) else commandError(r)
                }
                "blob.say" -> {
                    val text = params.string("text").orEmpty()
                    if (text.length > 120) return@withTimeout commandError("text must be 120 characters or fewer")
                    GadgetState.say(text, (params.int("seconds") ?: 600).coerceIn(5, 86_400))
                    commandOk(JsonObject(emptyMap()))
                }
                "blob.react" -> {
                    val r = Reaction.entries.firstOrNull { it.name.equals(params.string("reaction")?.trim(), ignoreCase = true) }
                        ?: return@withTimeout commandError("reaction must be one of: ${Reaction.entries.joinToString { it.name.lowercase() }}")
                    GadgetState.react(r)
                    commandOk(JsonObject(emptyMap()))
                }
                else -> home?.run(command, params) ?: commandError("unknown command: $command")
            }
        }
    } catch (e: TimeoutCancellationException) {
        commandError("timed out")
    }

    /** The health report (also sent with every heartbeat, for the settings page). */
    fun healthReport(): JsonObject = health().let { (it["payload"] as? JsonObject) ?: it }

    private fun health(): JsonObject {
        val mem = ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        val stat = StatFs(context.filesDir.path)
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val hasBattery = battery?.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false) == true
        val wifi = context.applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
        @Suppress("DEPRECATION")
        val rssi = wifi?.connectionInfo?.rssi
        return commandOk(buildJsonObject {
            put("device", "Amazon Echo Show 5 (LineageOS ${android.os.Build.VERSION.RELEASE})")
            put("app_version", BuildConfig.VERSION_NAME)
            put("uptime_s", SystemClock.elapsedRealtime() / 1000)
            putJsonObject("memory") {
                put("total_mb", mem.totalMem / MB)
                put("available_mb", mem.availMem / MB)
                put("low", mem.lowMemory)
            }
            putJsonObject("storage") {
                put("total_mb", stat.totalBytes / MB)
                put("free_mb", stat.availableBytes / MB)
            }
            if (hasBattery) {
                put("battery_percent", battery!!.getIntExtra(BatteryManager.EXTRA_LEVEL, -1))
            } else {
                put("battery_percent", null as Int?)
            }
            if (rssi != null && rssi > -127) put("wifi_rssi_dbm", rssi)
            put("volume_percent", currentVolume())
            put("screen_on", context.getSystemService(PowerManager::class.java).isInteractive)
            put("tts_available", speaker.available)
            cpuTempC()?.let { put("cpu_temp_c", it) }
            runCatching { java.io.File("/proc/loadavg").readText().split(" ").take(3).map { it.toDouble() } }.getOrNull()
                ?.let { l -> put("load", kotlinx.serialization.json.JsonArray(l.map { kotlinx.serialization.json.JsonPrimitive(it) })) }
            healthExtras().forEach { (k, v) -> put(k, v) }
        })
    }

    /** The CPU's temperature from the kernel's thermal zones (the MediaTek "mtktscpu" one), if readable. */
    private fun cpuTempC(): Double? = runCatching {
        java.io.File("/sys/class/thermal").listFiles().orEmpty().firstNotNullOfOrNull { z ->
            val type = java.io.File(z, "type").takeIf { it.canRead() }?.readText()?.trim() ?: return@firstNotNullOfOrNull null
            if (type != "mtktscpu" && "cpu" !in type) return@firstNotNullOfOrNull null
            java.io.File(z, "temp").readText().trim().toDoubleOrNull()?.let { if (it > 1000) it / 1000 else it }
        }
    }.getOrNull()

    private suspend fun drawUrl(url: String?, caption: String?): JsonObject {
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            return commandError("url must be http:// or https://")
        }
        val bytes = withContext(Dispatchers.IO) {
            http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body ?: return@withContext null
                if (body.contentLength() > MAX_IMAGE_BYTES) return@withContext null
                body.byteStream().readNBytesCompat(MAX_IMAGE_BYTES)
            }
        } ?: return commandError("couldn't download the image")
        val bitmap = withContext(Dispatchers.Default) { decodeScaled(bytes) }
            ?: return commandError("not an image Android can decode")
        GadgetState.show(Screen.Image(bitmap, caption ?: ""))
        bringToFront()
        return commandOk(buildJsonObject {
            put("shown", true)
            put("width", bitmap.width)
            put("height", bitmap.height)
        })
    }

    private fun showText(text: String?, title: String?): JsonObject {
        if (text.isNullOrBlank()) return commandError("text is required")
        GadgetState.show(Screen.Text(title.orEmpty(), text))
        bringToFront()
        return commandOk(buildJsonObject { put("shown", true) })
    }

    private fun brightness(level: Int?): JsonObject {
        val resolver = context.contentResolver
        if (level != null) {
            if (!Settings.System.canWrite(context)) {
                return commandError("this app isn't allowed to change system settings yet; " +
                    "grant it with: adb shell appops set ${context.packageName} WRITE_SETTINGS allow")
            }
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, (level.coerceIn(0, 100) * 255 / 100).coerceAtLeast(1))
            GadgetState.showLevel("brightness", level / 100f)
        }
        val raw = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, 128)
        return commandOk(buildJsonObject { put("level", (raw * 100 + 127) / 255) })
    }

    private suspend fun say(text: String?): JsonObject {
        if (text.isNullOrBlank()) return commandError("text is required")
        GadgetState.setReply(text)
        bringToFront()
        if (quiet()) {
            return commandOk(buildJsonObject {
                put("spoken", false)
                put("shown", true)
                put("note", "quiet hours are on, so it was shown as a caption instead of spoken")
            })
        }
        if (!speaker.available) {
            return commandOk(buildJsonObject {
                put("spoken", false)
                put("shown", true)
                put("note", "no text-to-speech engine is installed on this Echo Show; the text was shown as a caption")
            })
        }
        val spoken = speaker.speakAndWait(Markdown.toSpeech(text))
        return commandOk(buildJsonObject {
            put("spoken", spoken)
            put("shown", true)
        })
    }

    private fun currentVolume(): Int {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return if (max == 0) 0 else (audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 + max / 2) / max
    }

    private fun volume(volume: Int?): JsonObject {
        if (volume != null) {
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, (volume.coerceIn(0, 100) * max + 50) / 100, 0)
        }
        return commandOk(buildJsonObject { put("volume", currentVolume()) })
    }

    private suspend fun playUrl(url: String?, title: String?): JsonObject {
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            return commandError("url must be http:// or https://")
        }
        stopPlayer()
        val started = kotlinx.coroutines.CompletableDeferred<Result<Int>>()
        withContext(Dispatchers.Main) {
            val mp = MediaPlayer()
            player = mp
            mp.setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            mp.setOnPreparedListener {
                it.start()
                startMeter(it.audioSessionId)
                GadgetState.setNowPlaying(title?.takeIf { t -> t.isNotBlank() } ?: android.net.Uri.parse(url).host ?: "Audio")
                started.complete(Result.success(it.duration))
            }
            mp.setOnErrorListener { _, what, extra ->
                started.complete(Result.failure(IllegalStateException("player error $what/$extra")))
                true
            }
            mp.setOnCompletionListener { stopPlayer() }
            mp.setDataSource(url)
            mp.prepareAsync()
        }
        return started.await().fold(
            onSuccess = { duration ->
                commandOk(buildJsonObject {
                    put("playing", true)
                    if (duration > 0) put("duration_s", duration / 1000)
                })
            },
            onFailure = {
                stopPlayer()
                commandError("couldn't play that URL: ${it.message}")
            },
        )
    }

    private fun hold(item: String?, text: String?, minutes: Int?): JsonObject {
        val held = HeldItem.entries.firstOrNull { it.name.equals(item?.trim()?.replace(' ', '_'), ignoreCase = true) }
            ?: return commandError("item must be one of: ${HeldItem.entries.joinToString { it.name.lowercase() }}")
        val label = text.orEmpty().trim()
        if (label.length > 40) return commandError("text must be 40 characters or fewer")
        GadgetState.hold(held, label, (minutes ?: 60).coerceIn(1, 1440))
        return commandOk(JsonObject(emptyMap()))
    }

    private suspend fun sequence(steps: kotlinx.serialization.json.JsonElement?): JsonObject {
        val list = (steps as? kotlinx.serialization.json.JsonArray)?.mapNotNull { it as? JsonObject }
            ?: return commandError("steps must be an array of objects")
        if (list.isEmpty() || list.size > 20) return commandError("give 1 to 20 steps")
        val deadline = System.currentTimeMillis() + 60_000
        list.forEachIndexed { i, step ->
            if (System.currentTimeMillis() > deadline) return commandError("stopped at step ${i + 1}: over 60 seconds")
            val problem = runStep(step)
            if (problem != null) return commandError("step ${i + 1}: $problem")
            val reaction = step.string("reaction")?.let { r -> Reaction.entries.firstOrNull { it.name.equals(r.trim(), ignoreCase = true) } }
            val wait = (step.int("wait_ms") ?: reaction?.ms?.toInt() ?: 1500).coerceIn(0, 10_000)
            if (i < list.size - 1) kotlinx.coroutines.delay(wait.toLong())
        }
        return commandOk(buildJsonObject { put("steps", list.size) })
    }

    /** One step of blob.sequence; returns what was wrong with it, if anything. */
    private fun runStep(step: JsonObject): String? {
        fun err(r: JsonObject) = (r["error"] as? JsonPrimitive)?.contentOrNull ?: "failed"
        fun ok(r: JsonObject) = (r["ok"] as? JsonPrimitive)?.content == "true"
        step.string("mood")?.let { m ->
            val mood = Mood.entries.firstOrNull { it.name.equals(m.trim(), ignoreCase = true) } ?: return "unknown mood $m"
            GadgetState.setMood(mood, (step.int("mood_seconds") ?: 60).coerceIn(5, 3600))
        }
        step.string("reaction")?.let { r ->
            GadgetState.react(Reaction.entries.firstOrNull { it.name.equals(r.trim(), ignoreCase = true) } ?: return "unknown reaction $r")
        }
        step.string("say")?.let { text ->
            if (text.length > 120) return "say must be 120 characters or fewer"
            GadgetState.say(text, (step.int("say_seconds") ?: 10).coerceIn(2, 86_400))
        }
        if (step.string("color") != null || step.string("accessory") != null) {
            val r = blobStyle(step.string("color"), step.string("accessory"), step.int("hours"))
            if (!ok(r)) return err(r)
        }
        step.string("hold")?.let { item ->
            val r = hold(item, step.string("hold_text"), step.int("hold_minutes"))
            if (!ok(r)) return err(r)
        }
        return null
    }

    private fun blobStyle(color: String?, accessory: String?, hours: Int?): JsonObject {
        if (color == null && accessory == null) return commandError("give color and/or accessory")
        val old = GadgetState.style.value.takeIf { it.active(System.currentTimeMillis()) } ?: BlobStyle()
        val newColor = when {
            color == null -> old.color
            color.trim().equals("auto", ignoreCase = true) -> null
            else -> BlobStyles.parseColor(color) ?: return commandError("color must be auto, a name (${BlobStyles.COLORS.keys.joinToString()}) or #RRGGBB")
        }
        val newAccessory = when {
            accessory == null -> old.accessory
            accessory.trim().equals("auto", ignoreCase = true) -> null
            else -> Accessory.entries.firstOrNull { it.name.equals(accessory.trim().replace(' ', '_'), ignoreCase = true) }
                ?: return commandError("accessory must be auto or one of: ${Accessory.entries.joinToString { it.name.lowercase() }}")
        }
        val until = hours?.coerceIn(1, 720)?.let { System.currentTimeMillis() + it * 3_600_000L } ?: 0L
        val style = BlobStyle(newColor, newAccessory, until)
        GadgetState.setStyle(style)
        BlobStyles.save(context, style)
        return commandOk(buildJsonObject {
            put("color", newColor?.let { c -> BlobStyles.COLORS.entries.firstOrNull { it.value == c }?.key ?: "#%06x".format(c and 0xFFFFFF) } ?: "auto")
            put("accessory", newAccessory?.name?.lowercase() ?: "auto")
        })
    }

    private fun setProfile(name: String?): JsonObject {
        val clean = name?.trim()?.take(32)
        if (clean.isNullOrEmpty()) return commandError("name is required")
        Prefs(context).museName = clean
        GadgetState.setMuseName(clean)
        return commandOk(buildJsonObject { put("name", clean) })
    }

    private fun stopAudio(): JsonObject {
        val was = player != null
        stopPlayer()
        return commandOk(buildJsonObject { put("stopped", was) })
    }

    // Measures how loud the music is, so the blob can bob along. Not every ROM
    // allows a Visualizer; then the blob just bobs at a steady pace.
    private var meter: android.media.audiofx.Visualizer? = null

    private fun startMeter(session: Int) {
        stopMeter()
        meter = runCatching {
            android.media.audiofx.Visualizer(session).apply {
                measurementMode = android.media.audiofx.Visualizer.MEASUREMENT_MODE_PEAK_RMS
                enabled = true
            }
        }.getOrNull()
        val m = meter ?: return
        val reading = android.media.audiofx.Visualizer.MeasurementPeakRms()
        GadgetState.musicLevel = {
            runCatching {
                m.getMeasurementPeakRms(reading)
                // Millibels: about -4500 is quiet music, 0 is full scale.
                ((reading.mRms + 4500) / 3500f).coerceIn(0f, 1f)
            }.getOrDefault(-1f)
        }
    }

    private fun stopMeter() {
        GadgetState.musicLevel = { -1f }
        meter?.let { runCatching { it.enabled = false }; it.release() }
        meter = null
    }

    fun stopPlayer() {
        stopMeter()
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
        GadgetState.setNowPlaying(null)
    }

    fun togglePause() {
        player?.let { if (it.isPlaying) it.pause() else it.start() }
    }

    fun isPlaying() = player?.isPlaying == true

    fun release() {
        stopPlayer()
    }

    companion object {
        const val SCREEN_W = 960
        const val SCREEN_H = 480
        const val DEFAULT_TIMEOUT_MS = 60_000L
        const val MAX_IMAGE_BYTES = 12 * 1024 * 1024
        private const val MB = 1024 * 1024

        /** Decode at no more than twice the screen size, so a huge photo can't exhaust memory. */
        fun decodeScaled(bytes: ByteArray): android.graphics.Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= SCREEN_W * 2 || bounds.outHeight / (sample * 2) >= SCREEN_H * 2) sample *= 2
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }

        private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray? {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = read(buf)
                if (n < 0) return out.toByteArray()
                out.write(buf, 0, n)
                if (out.size() > limit) return null
            }
        }

        private fun param(type: String, description: String) = buildJsonObject {
            put("type", type)
            put("description", description)
        }

        private fun kotlinx.serialization.json.JsonObjectBuilder.spec(
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

        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
    }
}
