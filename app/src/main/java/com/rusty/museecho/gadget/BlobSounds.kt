package com.rusty.museecho.gadget

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** The blob's little noises. */
enum class BlobSound { TAP, HOP, YAWN, CELEBRATE, WAVE, STARTLE, NOD, SHAKE, WINK, HICCUP, EARS }

/**
 * Tiny synthesized chirps and boops (no sound files): short sine sweeps with a
 * soft attack and decay, mixed very quietly. Each is rendered once and kept.
 */
class BlobSounds {
    private val cache = HashMap<BlobSound, ShortArray>()
    @Volatile private var playing: AudioTrack? = null

    fun play(sound: BlobSound) {
        val pcm = synchronized(cache) { cache.getOrPut(sound) { render(sound) } }
        playing?.let { runCatching { it.stop(); it.release() } }
        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(pcm.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
        }.getOrNull() ?: return
        track.write(pcm, 0, pcm.size)
        track.setNotificationMarkerPosition(pcm.size)
        track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
            override fun onMarkerReached(t: AudioTrack) {
                runCatching { t.release() }
                if (playing === t) playing = null
            }
            override fun onPeriodicNotification(t: AudioTrack) {}
        })
        playing = track
        runCatching { track.play() }
    }

    companion object {
        const val RATE = 22_050
        private const val LOUDNESS = 0.11

        /** A note: frequency sweeping from [f0] to [f1] over [ms], with an optional wobble. */
        private data class Note(val f0: Double, val f1: Double, val ms: Int, val wobble: Double = 0.0, val gap: Int = 0)

        private fun notes(sound: BlobSound): List<Note> = when (sound) {
            BlobSound.TAP -> listOf(Note(620.0, 480.0, 70))
            BlobSound.HOP -> listOf(Note(380.0, 820.0, 110), Note(820.0, 600.0, 60))
            BlobSound.YAWN -> listOf(Note(520.0, 240.0, 650, wobble = 0.03))
            BlobSound.CELEBRATE -> listOf(Note(523.0, 523.0, 70, gap = 15), Note(659.0, 659.0, 70, gap = 15),
                Note(784.0, 784.0, 70, gap = 15), Note(1047.0, 1100.0, 160))
            BlobSound.WAVE -> listOf(Note(660.0, 660.0, 80, gap = 30), Note(880.0, 900.0, 120))
            BlobSound.STARTLE -> listOf(Note(300.0, 1250.0, 90))
            BlobSound.NOD -> listOf(Note(440.0, 440.0, 70, gap = 40), Note(550.0, 560.0, 90))
            BlobSound.SHAKE -> listOf(Note(500.0, 480.0, 70, gap = 40), Note(380.0, 360.0, 100))
            BlobSound.WINK -> listOf(Note(1300.0, 1500.0, 60))
            BlobSound.HICCUP -> listOf(Note(700.0, 1100.0, 45))
            BlobSound.EARS -> listOf(Note(700.0, 950.0, 70, gap = 20), Note(950.0, 1150.0, 60))
        }

        /** Renders a sound to 16-bit PCM (pure: unit-testable). */
        fun render(sound: BlobSound): ShortArray {
            val out = ArrayList<Short>()
            for (n in notes(sound)) {
                val len = RATE * n.ms / 1000
                var phase = 0.0
                for (i in 0 until len) {
                    val p = i.toDouble() / len
                    val f = (n.f0 + (n.f1 - n.f0) * p) * (1 + n.wobble * sin(2 * PI * 6 * i / RATE))
                    phase += 2 * PI * f / RATE
                    // 6 ms attack, then a gentle exponential tail: no clicks.
                    val attack = (i / (RATE * 0.006)).coerceAtMost(1.0)
                    val env = attack * exp(-3.2 * p)
                    // A touch of the octave makes it rounder than a bare sine.
                    val s = (sin(phase) + 0.25 * sin(2 * phase)) / 1.25
                    out.add((s * env * LOUDNESS * Short.MAX_VALUE).toInt().toShort())
                }
                repeat(RATE * n.gap / 1000) { out.add(0) }
            }
            return out.toShortArray()
        }

        /** Which reaction makes which sound (null: silent). */
        fun forReaction(r: Reaction): BlobSound? = when (r) {
            Reaction.HOP, Reaction.NUDGE -> BlobSound.HOP
            Reaction.CELEBRATE -> BlobSound.CELEBRATE
            Reaction.YAWN, Reaction.TUCK_IN -> BlobSound.YAWN
            Reaction.WAVE -> BlobSound.WAVE
            Reaction.STARTLE -> BlobSound.STARTLE
            Reaction.NOD -> BlobSound.NOD
            Reaction.SHAKE -> BlobSound.SHAKE
            Reaction.WINK -> BlobSound.WINK
            Reaction.HICCUP -> BlobSound.HICCUP
            else -> null
        }
    }
}
