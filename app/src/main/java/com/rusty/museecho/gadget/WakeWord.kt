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

    /** Start listening for [phrase]. Returns an error message, or null when it's listening. */
    @SuppressLint("MissingPermission") // the caller checks RECORD_AUDIO
    fun start(phrase: String): String? {
        stop()
        if (!modelPresent) return "speech model missing (push vosk-model-small-en-us to the app's files dir)"
        val words = phrase.lowercase().split(Regex("[^a-z']+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return "the wake phrase is empty"
        val target = words.joinToString(" ")
        // "hey" is short and often heard as noise; the phrase's last word alone also counts.
        val shortTarget = words.last().takeIf { words.size > 1 && it.length >= 3 }
        running = true
        paused = false
        worker = thread(name = "wake-word", isDaemon = true) {
            try {
                LibVosk.setLogLevel(LogLevel.WARNINGS)
                val m = model ?: Model(modelDir.absolutePath).also { model = it }
                val entries = listOfNotNull(target, shortTarget, STOP_WORD, "[unk]").distinct()
                val grammar = JsonArray(entries.map { JsonPrimitive(it) }).toString()
                Recognizer(m, RATE.toFloat(), grammar).use { rec -> listen(rec, target, shortTarget) }
            } catch (e: Throwable) {
                log("wake word stopped: ${e.message}")
                running = false
            }
        }
        log("listening for \"$target\"")
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
                // Track the floor: fall fast toward quiet, rise slowly with steady noise (a fan, a TV).
                noiseFloor = if (level < noiseFloor) noiseFloor * 0.7 + level * 0.3 else noiseFloor * 0.995 + level * 0.005
                noiseFloor = noiseFloor.coerceIn(5.0, 2_000.0)
                if (level >= maxOf(GATE_RMS, noiseFloor * GATE_OVER_FLOOR)) {
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
                val final = rec.acceptWaveForm(chunk, n)
                val text = textOf(if (final) rec.result else rec.partialResult, if (final) "text" else "partial")
                // "stop" only as a whole, finished utterance, so it isn't picked out of a longer phrase.
                if (final && text.trim() == STOP_WORD) {
                    onStopWord()
                    continue
                }
                val heard = text.contains(target) ||
                    (shortTarget != null && !strict() && text.split(' ').any { it == shortTarget })
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
        const val GATE_RMS = 90.0
        const val GATE_OVER_FLOOR = 1.8
        const val HOLD_MS = 2_500L
        const val DEFAULT_FLOOR = 60.0
        const val PRE_ROLL_CHUNKS = 8

        private fun textOf(json: String, key: String): String =
            ((runCatching { Json.parseToJsonElement(json) }.getOrNull() as? JsonObject)?.get(key) as? JsonPrimitive)
                ?.contentOrNull.orEmpty()
    }
}
