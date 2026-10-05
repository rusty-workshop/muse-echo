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
    }

    suspend fun run(command: String, params: JsonObject, timeoutMs: Long?): JsonObject = try {
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
                else -> home?.run(command, params) ?: commandError("unknown command: $command")
            }
        }
    } catch (e: TimeoutCancellationException) {
        commandError("timed out")
    }

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
            healthExtras().forEach { (k, v) -> put(k, v) }
        })
    }

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

    fun stopPlayer() {
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
