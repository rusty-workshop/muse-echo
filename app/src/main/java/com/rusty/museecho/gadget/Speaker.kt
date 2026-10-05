package com.rusty.museecho.gadget

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * On-device text-to-speech. LineageOS ships without a TTS engine, so this
 * reports [available] = false until one (e.g. RHVoice or eSpeak NG from
 * F-Droid) is installed; callers then fall back to captions.
 */
class Speaker(context: Context) {
    @Volatile
    var available = false
        private set

    private val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        available = status == TextToSpeech.SUCCESS && configure()
        GadgetState.log(if (available) "text-to-speech ready" else "no text-to-speech engine; replies are captions only")
    }

    private fun configure(): Boolean {
        val result = tts.setLanguage(Locale.US)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            if (tts.setLanguage(Locale.getDefault()) < TextToSpeech.LANG_AVAILABLE) return false
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {}
            override fun onDone(utteranceId: String) {
                pending.remove(utteranceId)?.complete(true)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) {
                pending.remove(utteranceId)?.complete(false)
            }
        })
        return true
    }

    fun speak(text: String) {
        if (!available) return
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
    }

    /** Speak [text] and wait (up to 2 minutes) until it has been said. */
    suspend fun speakAndWait(text: String): Boolean {
        if (!available) return false
        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Boolean>()
        pending[id] = done
        if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) {
            pending.remove(id)
            return false
        }
        return withTimeoutOrNull(120_000) { done.await() } ?: false
    }

    fun stop() {
        tts.stop()
    }

    fun shutdown() {
        tts.shutdown()
    }
}
