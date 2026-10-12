package com.rusty.museecho.gadget

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import kotlin.concurrent.thread
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer

/**
 * Hands-free wake phrase, spotted on the Echo with Vosk. The recognizer is
 * limited to a grammar of just the phrase plus "[unk]", which keeps it cheap
 * enough to run all day on the Echo's quad A53 and makes false triggers
 * rare. Nothing leaves the device until the phrase is heard.
 *
 * The model (vosk-model-small-en-us) lives in the app's external files dir,
 * pushed there with adb, so it isn't baked into the APK.
 */
class WakeWord(
    private val modelDir: File,
    private val onWake: () -> Unit,
    private val log: (String) -> Unit,
    /** Heard "stop" on its own; the caller decides whether it means anything. */
    private val onStopWord: () -> Unit = {},
    /** While true (the Echo is speaking), only the full phrase counts, so it can't wake itself. */
    private val strict: () -> Boolean = { false },
    /** A sudden loud bang (a door slam, a dropped pan): far above the room's usual level. */
    private val onLoud: () -> Unit = {},
    /** The TV or music is playing: other voices about, so it needs to be surer. */
    private val noisy: () -> Boolean = { false },
) {
    @Volatile
    private var running = false

    @Volatile
    private var paused = false
    private var worker: Thread? = null
    private var model: Model? = null

    /**
     * The room's background level (RMS), learned from the quiet stretches the
     * listener hears; hands-free notes measure speech against it.
     */
    @Volatile
    var noiseFloor = DEFAULT_FLOOR
        private set

    val modelPresent: Boolean get() = File(modelDir, "am").isDirectory && File(modelDir, "graph").isDirectory

    /**
     * How readily it wakes: the volume gate (RMS floor, and multiple of the
     * room's background) and whether the phrase's last word alone counts.
     */
    /**
     * [minConf]: how sure Vosk must be of every word of the phrase (its per-word confidence) on
     * a finished utterance. LOW is the strictest; HIGH wakes most easily.
     */
    enum class Sensitivity(val label: String, val gateRms: Double, val overFloor: Double, val lastWordAlone: Boolean, val minConf: Double) {
        LOW("Strict", 180.0, 2.5, false, 0.92),
        MEDIUM("Balanced", 120.0, 2.0, false, 0.85),
        HIGH("Eager", 90.0, 1.8, true, 0.76),
    }

    @Volatile
    private var sensitivity = Sensitivity.HIGH

    /** Start listening for [phrase]. Returns an error message, or null when it's listening. */
    @SuppressLint("MissingPermission") // the caller checks RECORD_AUDIO
    fun start(phrase: String, sensitivity: Sensitivity = Sensitivity.HIGH): String? {
        this.sensitivity = sensitivity
        stop()
        if (!modelPresent) return "speech model missing (push vosk-model-small-en-us to the app's files dir)"
        val words = phrase.lowercase().split(Regex("[^a-z']+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return "the wake phrase is empty"
        val target = words.joinToString(" ")
        // "hey" is short and often heard as noise; the phrase's last word alone also counts.
        val shortTarget = words.last().takeIf { sensitivity.lastWordAlone && words.size > 1 && it.length >= 3 }
        running = true
        paused = false
        worker = thread(name = "wake-word", isDaemon = true) {
            try {
                LibVosk.setLogLevel(LogLevel.WARNINGS)
                val m = model ?: Model(modelDir.absolutePath).also { model = it }
                // Everyday words sit alongside the phrase so ordinary speech has somewhere else to
                // land, instead of being squeezed into the closest thing to the phrase.
                val entries = (listOfNotNull(target, shortTarget, STOP_WORD) + DISTRACTORS.filter { it !in words } + "[unk]").distinct()
                val grammar = JsonArray(entries.map { JsonPrimitive(it) }).toString()
                Recognizer(m, RATE.toFloat(), grammar).use { rec ->
                    rec.setWords(true)
                    listen(rec, target, shortTarget)
                }
            } catch (e: Throwable) {
                log("wake word stopped: ${e.message}")
                running = false
            }
        }
        log("listening for \"$target\" (${sensitivity.label.lowercase()} sensitivity)")
        return null
    }

    @SuppressLint("MissingPermission")
    private fun listen(rec: Recognizer, target: String, shortTarget: String?) {
        val bufBytes = maxOf(AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), 4096)
        val chunk = ByteArray(RATE / 10 * 2) // 100 ms
        var record: AudioRecord? = null
        // Volume gate: in a quiet room Vosk sees nothing at all. Recent chunks
        // are kept so the start of the phrase isn't clipped when sound begins.
        val preRoll = ArrayDeque<ByteArray>()
        var feedUntil = 0L
        try {
            while (running) {
                if (paused) {
                    // Give the microphone back while a push-to-talk note records.
                    record?.let { it.stop(); it.release() }
                    record = null
                    Thread.sleep(100)
                    continue
                }
                val r = record ?: AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufBytes).also {
                    if (it.state != AudioRecord.STATE_INITIALIZED) {
                        it.release()
                        throw IllegalStateException("couldn't open the microphone")
                    }
                    it.startRecording()
                    rec.reset()
                    record = it
                }
                val n = r.read(chunk, 0, chunk.size)
                if (n < 0) {
                    // The audio server restarted (or the mic went away): reopen it.
                    log("microphone lost ($n); reopening")
                    r.release()
                    record = null
                    Thread.sleep(1_000)
                    continue
                }
                if (n == 0) continue
                val now = System.currentTimeMillis()
                val level = rms(chunk, n)
                if (level > maxOf(LOUD_RMS, noiseFloor * 12)) onLoud()
                // Track the floor: fall fast toward quiet, rise slowly with steady noise (a fan, a TV).
                noiseFloor = if (level < noiseFloor) noiseFloor * 0.7 + level * 0.3 else noiseFloor * 0.995 + level * 0.005
                noiseFloor = noiseFloor.coerceIn(5.0, 2_000.0)
                if (level >= maxOf(sensitivity.gateRms, noiseFloor * sensitivity.overFloor)) {
                    if (now >= feedUntil) {
                        // Waking from quiet: replay the pre-roll first.
                        rec.reset()
                        preRoll.forEach { rec.acceptWaveForm(it, it.size) }
                    }
                    feedUntil = now + HOLD_MS
                }
                if (now >= feedUntil) {
                    preRoll.addLast(chunk.copyOf(n))
                    if (preRoll.size > PRE_ROLL_CHUNKS) preRoll.removeFirst()
                    continue
                }
                preRoll.clear()
                // Only finished utterances count (partial results are guesses that change as you speak),
                // and only when Vosk is sure of every word of the phrase.
                if (!rec.acceptWaveForm(chunk, n)) continue
                val json = rec.result
                val text = textOf(json, "text").trim()
                // "stop" only as a whole utterance, so it isn't picked out of a longer phrase.
                if (text == STOP_WORD) {
                    onStopWord()
                    continue
                }
                val need = sensitivity.minConf + if (noisy()) 0.05 else 0.0
                val full = phraseConfidence(json, target.split(' '))
                // The last word alone (Eager only): must be the whole utterance, and surer still.
                val alone = if (shortTarget != null && !strict() && text == shortTarget) phraseConfidence(json, listOf(shortTarget)) else null
                val heard = (full != null && full >= need) || (alone != null && alone >= minOf(0.97, need + 0.12))
                if (!heard && (full != null || alone != null)) {
                    log("wake word: heard \"$text\" but only %.0f%% sure; ignored".format(100 * (full ?: alone ?: 0.0)))
                }
                if (heard) {
                    rec.reset()
                    // Pause first, so the note recorder gets the microphone straight away.
                    paused = true
                    onWake()
                }
            }
        } finally {
            record?.let { runCatching { it.stop() }; it.release() }
        }
    }

    /**
     * Silent check that the recogniser can spot [phrase]: feeds it [pcm16k]
     * (mono 16 kHz samples, e.g. the phrase synthesized by the neural voice)
     * and returns what it heard with the same grammar the listener uses.
     */
    fun recognise(phrase: String, pcm16k: ShortArray): String = recogniseWithConfidence(phrase, pcm16k).first

    /** Like the listener (same grammar, distractors included): what it heard, and how sure it was of the phrase. */
    fun recogniseWithConfidence(phrase: String, pcm16k: ShortArray): Pair<String, Double?> {
        if (!modelPresent) return "(speech model missing)" to null
        val words = phrase.lowercase().split(Regex("[^a-z']+")).filter { it.isNotBlank() }
        val m = synchronized(this) { model ?: Model(modelDir.absolutePath).also { model = it } }
        val entries = (listOf(words.joinToString(" "), STOP_WORD) + DISTRACTORS.filter { it !in words } + "[unk]").distinct()
        val grammar = JsonArray(entries.map { JsonPrimitive(it) }).toString()
        return Recognizer(m, RATE.toFloat(), grammar).use { rec ->
            rec.setWords(true)
            val bytes = java.nio.ByteBuffer.allocate(pcm16k.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            pcm16k.forEach { bytes.putShort(it) }
            rec.acceptWaveForm(bytes.array(), bytes.position())
            val json = rec.finalResult
            textOf(json, "text") to phraseConfidence(json, words)
        }
    }

    /** Offline transcription of a voice note (16 kHz mono PCM16 bytes), with no grammar limit. */
    fun transcribe(pcm16le: ByteArray): String {
        if (!modelPresent) return ""
        val m = synchronized(this) { model ?: Model(modelDir.absolutePath).also { model = it } }
        return Recognizer(m, RATE.toFloat()).use { rec ->
            var off = 0
            while (off < pcm16le.size) {
                val n = minOf(8_000, pcm16le.size - off)
                rec.acceptWaveForm(pcm16le.copyOfRange(off, off + n), n)
                off += n
            }
            textOf(rec.finalResult, "text")
        }
    }

    /** Hand the microphone over (e.g. to push-to-talk); [resume] takes it back. */
    fun pause() {
        paused = true
    }

    fun resume() {
        paused = false
    }

    val listening: Boolean get() = running

    fun stop() {
        running = false
        worker?.join(1500)
        worker = null
    }

    fun release() {
        stop()
        model?.close()
        model = null
    }

    companion object {
        /** RMS (16-bit) that counts as a bang, whatever the room's floor. */
        const val LOUD_RMS = 4_000.0

        const val RATE = 16_000
        const val MODEL_DIR_NAME = "vosk-model"
        const val STOP_WORD = "stop"

        /** Root-mean-square level of 16-bit little-endian PCM, 0..32768. */
        fun rms(pcm: ByteArray, n: Int): Double {
            var sum = 0.0
            var count = 0
            var i = 0
            while (i + 1 < n) {
                val sample = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toDouble()
                sum += sample * sample
                count++
                i += 2
            }
            return if (count == 0) 0.0 else kotlin.math.sqrt(sum / count)
        }

        /** About -45 dBFS: quieter than speech across a room, louder than the Echo's idle noise floor. */
        const val HOLD_MS = 2_500L
        const val DEFAULT_FLOOR = 60.0
        const val PRE_ROLL_CHUNKS = 8

        /** Common words for the grammar, so speech that isn't the phrase doesn't get forced into it. */
        val DISTRACTORS = listOf("hey", "hi", "hello", "okay", "yeah", "yes", "no", "the", "a", "and", "it", "is", "that", "this",
            "what", "you", "i", "to", "so", "play", "say", "day", "they", "may", "pay", "pick", "pickle", "pistol", "pencil",
            "people", "pixie", "fixed", "nickel", "tickle", "little", "simple", "sell", "seven", "special", "basil", "mixer", "picture")

        /**
         * The lowest per-word confidence of [phrase] in a final result (with words on), or null
         * if the phrase isn't in it.
         */
        fun phraseConfidence(json: String, phrase: List<String>): Double? {
            val arr = (runCatching { Json.parseToJsonElement(json) }.getOrNull() as? JsonObject)?.get("result") as? JsonArray ?: return null
            val words = arr.mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val w = (o["word"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                w to ((o["conf"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() ?: 0.0)
            }
            if (phrase.isEmpty()) return null
            for (i in 0..words.size - phrase.size) {
                if (phrase.indices.all { words[i + it].first == phrase[it] }) return phrase.indices.minOf { words[i + it].second }
            }
            return null
        }

        private fun textOf(json: String, key: String): String =
            ((runCatching { Json.parseToJsonElement(json) }.getOrNull() as? JsonObject)?.get(key) as? JsonPrimitive)
                ?.contentOrNull.orEmpty()
    }
}
