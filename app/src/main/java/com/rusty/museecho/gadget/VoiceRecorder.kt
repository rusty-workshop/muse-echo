package com.rusty.museecho.gadget

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/**
 * Records a voice note: 16 kHz mono 16-bit PCM, wrapped as WAV, the same
 * format the ESP32 boards upload. Capped at [MAX_SECONDS].
 */
class VoiceRecorder {
    @Volatile
    private var recording = false
    private var worker: Thread? = null
    private val pcm = ByteArrayOutputStream()

    /** Whether speech was heard in a hands-free note (set while recording). */
    @Volatile
    private var spoke = true

    /** One line about the last hands-free note's levels, for tuning (no audio). */
    @Volatile
    var summary: String? = null

    /** Live input level, 0..1, for the UI meter. */
    @Volatile
    var level = 0f
        private set

    /**
     * Start recording. [onLimit] fires when the note should end on its own:
     * at [MAX_SECONDS], or with [endOnSilence] once speech has been followed
     * by [SILENCE_MS] of quiet (or nothing was said within [NO_SPEECH_MS]).
     */
    @SuppressLint("MissingPermission") // checked by the caller before start()
    fun start(endOnSilence: Boolean = false, noiseFloor: Double = 60.0, onLimit: () -> Unit): Boolean {
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return false
        val record = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, RATE))
        } catch (e: Exception) {
            return false
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }
        synchronized(pcm) { pcm.reset() }
        spoke = !endOnSilence
        recording = true
        record.startRecording()
        worker = thread(name = "voice-note") {
            val buf = ShortArray(RATE / 20)
            val bytes = ByteBuffer.allocate(buf.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            val started = System.currentTimeMillis()
            var heardSpeech = false
            var quietSince = 0L
            // Adaptive: speech is well above the room's background, silence is back near it.
            var floor = noiseFloor
            val speechAbove = { maxOf(floor * 2.5, floor + 90, MIN_SPEECH_RMS) }
            val silenceBelow = { maxOf(floor * 1.7, floor + 45) }
            var peakRms = 0.0
            var reason = "button"
            try {
                while (recording) {
                    val n = record.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    bytes.clear()
                    var peak = 0
                    for (i in 0 until n) {
                        bytes.putShort(buf[i])
                        peak = maxOf(peak, kotlin.math.abs(buf[i].toInt()))
                    }
                    level = peak / 32768f
                    if (endOnSilence) {
                        val now = System.currentTimeMillis()
                        var sum = 0.0
                        for (i in 0 until n) sum += buf[i].toDouble() * buf[i]
                        val rms = kotlin.math.sqrt(sum / n)
                        peakRms = maxOf(peakRms, rms)
                        if (rms > speechAbove()) {
                            heardSpeech = true
                            spoke = true
                            quietSince = 0L
                        } else if (rms < silenceBelow()) {
                            if (quietSince == 0L) quietSince = now
                            // Keep learning the room while nobody's talking yet.
                            if (!heardSpeech) floor = floor * 0.9 + rms * 0.1
                        }
                        val done = when {
                            heardSpeech && quietSince != 0L && now - quietSince >= SILENCE_MS -> "silence".also { reason = it }
                            !heardSpeech && now - started >= NO_SPEECH_MS -> "no speech".also { reason = it }
                            now - started >= HANDS_FREE_MAX_MS -> "time limit".also { reason = it }
                            else -> null
                        }
                        if (done != null) {
                            recording = false
                            onLimit()
                        }
                    }
                    synchronized(pcm) {
                        pcm.write(bytes.array(), 0, n * 2)
                        if (pcm.size() >= MAX_SECONDS * RATE * 2) {
                            recording = false
                            onLimit()
                        }
                    }
                }
            } finally {
                record.stop()
                record.release()
                level = 0f
                if (endOnSilence) {
                    summary = "hands-free note: floor %.0f, loudest %.0f, speech above %.0f, ended by %s after %.1fs"
                        .format(noiseFloor, peakRms, speechAbove(), reason, (System.currentTimeMillis() - started) / 1000.0)
                }
            }
        }
        return true
    }

    /** Stops and returns the note as WAV, or null if it was too short, or (hands-free) nobody spoke. */
    fun stop(): ByteArray? {
        recording = false
        if (worker != Thread.currentThread()) worker?.join(1000)
        worker = null
        val data = synchronized(pcm) { pcm.toByteArray() }
        if (data.size < RATE * 2 * MIN_MS / 1000 || !spoke) return null
        return wav(data)
    }

    fun cancel() {
        recording = false
        worker?.join(1000)
        worker = null
    }

    companion object {
        const val RATE = 16_000
        const val MAX_SECONDS = 90
        const val MIN_MS = 400
        const val MIN_SPEECH_RMS = 120.0
        const val SILENCE_MS = 1_100L
        const val NO_SPEECH_MS = 6_000L
        const val HANDS_FREE_MAX_MS = 25_000L

        fun wav(pcm: ByteArray, rate: Int = RATE): ByteArray {
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray()); putInt(36 + pcm.size); put("WAVE".toByteArray())
                put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
                putInt(rate); putInt(rate * 2); putShort(2); putShort(16)
                put("data".toByteArray()); putInt(pcm.size)
            }
            return header.array() + pcm
        }
    }
}
