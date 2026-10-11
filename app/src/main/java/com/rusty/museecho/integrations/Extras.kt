package com.rusty.museecho.integrations

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.OkHttpClient
import okhttp3.Request

/** One tap-to-send button on the home screen: a label and the request it sends. */
@Serializable
data class QuickAction(val label: String, val prompt: String)

class QuickActions(file: File) : JsonFileState<List<QuickAction>>(file, ListSerializer(QuickAction.serializer()), DEFAULTS) {
    fun set(actions: List<QuickAction>) = update {
        actions.map { QuickAction(it.label.trim().take(18), it.prompt.trim().take(500)) }
            .filter { it.label.isNotEmpty() && it.prompt.isNotEmpty() }.take(MAX)
    }

    companion object {
        const val MAX = 4
        val DEFAULTS = listOf(
            QuickAction("Goodnight", "Goodnight routine: turn the TV off if it's on, tell me my first alarm or event " +
                "tomorrow, and say goodnight in one short sentence."),
            QuickAction("Movie time", "Movie time: set the Echo Show brightness to 10, switch the Roku TV to its home " +
                "screen, and confirm in a few words."),
            QuickAction("What's up?", "Anything I should know right now? Weather, my next event, and any problems on " +
                "the Latitude server, in under 40 words."),
            QuickAction("Heading out", "I'm heading out. Anything I should take or know before leaving? Under 30 words."),
        )
    }
}

/**
 * Optional quick-action sets for parts of the day; an empty set falls back to the
 * all-day buttons. Morning is 5 a.m. to noon, afternoon noon to 5 p.m., evening the rest.
 */
@Serializable
data class QuickActionSets(
    val morning: List<QuickAction> = emptyList(),
    val afternoon: List<QuickAction> = emptyList(),
    val evening: List<QuickAction> = emptyList(),
) {
    fun forHour(hour: Int): List<QuickAction> = when (hour) {
        in 5..11 -> morning
        in 12..16 -> afternoon
        else -> evening
    }

    companion object {
        fun periodOf(hour: Int) = when (hour) { in 5..11 -> "morning"; in 12..16 -> "afternoon"; else -> "evening" }
    }
}

class QuickActionTimes(file: File) : JsonFileState<QuickActionSets>(file, QuickActionSets.serializer(), QuickActionSets()) {
    private fun clean(list: List<QuickAction>) = list.map { QuickAction(it.label.trim().take(18), it.prompt.trim().take(500)) }
        .filter { it.label.isNotEmpty() && it.prompt.isNotEmpty() }.take(QuickActions.MAX)

    fun set(period: String, list: List<QuickAction>) = update { s ->
        when (period) {
            "morning" -> s.copy(morning = clean(list))
            "afternoon" -> s.copy(afternoon = clean(list))
            "evening" -> s.copy(evening = clean(list))
            else -> s
        }
    }
}

/**
 * Photos for the idle slideshow: files pushed to the app's `frame/` folder
 * (adb or the laptop) plus pictures Muse adds by URL, cached locally.
 */
class PhotoFrame(val dir: File, private val http: OkHttpClient = OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).build()) {
    init {
        dir.mkdirs()
    }

    fun photos(): List<File> = dir.listFiles()
        ?.filter { it.isFile && it.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp") && it.length() > 0 }
        ?.sortedBy { it.name }.orEmpty()

    /** Download [url] into the frame. Returns the saved file's name. */
    suspend fun add(url: String): String = withContext(Dispatchers.IO) {
        require(url.startsWith("http://") || url.startsWith("https://")) { "url must be http:// or https://" }
        require(photos().size < MAX_PHOTOS) { "the frame is full ($MAX_PHOTOS photos); remove some first" }
        http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) error("download failed (HTTP ${resp.code})")
            val bytes = resp.body?.bytes() ?: error("empty download")
            require(bytes.size <= 12 * 1024 * 1024) { "image too large" }
            val ext = when {
                bytes.size > 3 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() -> "png"
                bytes.size > 12 && String(bytes, 8, 4) == "WEBP" -> "webp"
                bytes.size > 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "jpg"
                else -> error("not a JPEG, PNG or WebP image")
            }
            val name = "url-" + MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
                .joinToString("") { "%02x".format(it) }.take(12) + "." + ext
            File(dir, name).writeBytes(bytes)
            name
        }
    }

    /** Remove one photo by file name, or all with "all". Returns how many went. */
    fun remove(which: String): Int {
        val targets = if (which.equals("all", true)) photos() else photos().filter { it.name == which }
        targets.forEach { it.delete() }
        return targets.size
    }

    companion object {
        const val MAX_PHOTOS = 200
    }
}
