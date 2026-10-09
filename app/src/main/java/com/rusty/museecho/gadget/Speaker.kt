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
 * On-device text-to-speech: the in-app neural voice ([NeuralVoice]) when a
 * quality level is chosen and loaded, otherwise the system TTS engine.
 * Without either, [available] is false and callers fall back to captions.
 */
class Speaker(context: Context, private val neural: NeuralVoice? = null) {
    @Volatile
    private var systemAvailable = false

    val available: Boolean get() = neural?.ready == true || systemAvailable

    /** Plays neural speech off the main thread, one utterance at a time. */
    private val neuralWorker = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "neural-voice").apply { isDaemon = true } }

    private val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val appContext = context.applicationContext
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    // The init callback can fire inside the constructor (on failure), before
    // `tts` is assigned, so it's always handled on the next main-loop turn.
    // Prefer the neural (Piper) engine when it's installed; it sounds far more human than RHVoice.
    private var engine: String? = PREFERRED_ENGINES.firstOrNull { installed(it) }
    private var tts: TextToSpeech = TextToSpeech(appContext, { status -> main.post { onInit(status, retried = false) } }, engine)

    private fun installed(pkg: String) = runCatching { appContext.packageManager.getPackageInfo(pkg, 0) }.isSuccess

    private val startedAt = ConcurrentHashMap<String, Long>()

    private fun onInit(status: Int, retried: Boolean) {
        if (status != TextToSpeech.SUCCESS && !retried) {
            // The system default can name an engine that's no longer installed;
            // fall back to whichever engine is.
            val engine = tts.engines.firstOrNull()?.name
            if (engine != null) {
                tts.shutdown()
                this.engine = engine
                tts = TextToSpeech(appContext, { status -> main.post { onInit(status, retried = true) } }, engine)
                return
            }
        }
        systemAvailable = status == TextToSpeech.SUCCESS && configure()
        GadgetState.log(if (systemAvailable) "system text-to-speech ready (${engine ?: tts.defaultEngine})" else "no system text-to-speech engine")
    }

    private fun configure(): Boolean {
        val result = tts.setLanguage(Locale.US)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            if (tts.setLanguage(Locale.getDefault()) < TextToSpeech.LANG_AVAILABLE) return false
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {
                GadgetState.setSpeaking(true)
                startedAt.remove(utteranceId)?.let { GadgetState.log("speech started after ${System.currentTimeMillis() - it} ms") }
            }
            override fun onDone(utteranceId: String) {
                GadgetState.setSpeaking(false)
                current = ""
                pending.remove(utteranceId)?.complete(true)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) {
                GadgetState.setSpeaking(false)
                current = ""
                pending.remove(utteranceId)?.complete(false)
            }
        })
        return true
    }

    /** The text being spoken now, so a spoken "stop" in it isn't mistaken for you. */
    @Volatile
    var current = ""
        private set

    private fun neuralReady() = neural?.ready == true

    /** Neural speech for [text]; completes [done] when finished or stopped. */
    private fun speakNeural(text: String, done: CompletableDeferred<Boolean>?) {
        current = text
        val asked = System.currentTimeMillis()
        neuralWorker.execute {
            val ok = neural!!.speakBlocking(text) {
                GadgetState.setSpeaking(true)
                GadgetState.log("speech started after ${System.currentTimeMillis() - asked} ms")
            }
            GadgetState.setSpeaking(false)
            if (current == text) current = ""
            done?.complete(ok)
        }
    }

    fun speak(text: String) {
        if (neuralReady()) {
            neural!!.stop()
            speakNeural(text, null)
            return
        }
        if (!systemAvailable) return
        current = text
        val id = UUID.randomUUID().toString()
        startedAt[id] = System.currentTimeMillis()
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    /** Speak [text] and wait (up to 2 minutes) until it has been said. */
    suspend fun speakAndWait(text: String): Boolean {
        if (neuralReady()) {
            neural!!.stop()
            val done = CompletableDeferred<Boolean>()
            val id = UUID.randomUUID().toString()
            pending[id] = done
            speakNeural(text, done)
            return (withTimeoutOrNull(300_000) { done.await() } ?: false).also { pending.remove(id) }
        }
        if (!systemAvailable) return false
        val id = UUID.randomUUID().toString()
        current = text
        val done = CompletableDeferred<Boolean>()
        pending[id] = done
        startedAt[id] = System.currentTimeMillis()
        if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) {
            pending.remove(id)
            return false
        }
        return withTimeoutOrNull(120_000) { done.await() } ?: false
    }

    companion object {
        /** Neural voices first (sherpa-onnx Piper engine), then whatever is the default. */
        val PREFERRED_ENGINES = listOf("com.k2fsa.sherpa.onnx.tts.engine")
    }

    fun stop() {
        neural?.stop()
        tts.stop()
        GadgetState.setSpeaking(false)
        current = ""
        // tts.stop() doesn't call onDone, so release anyone waiting on speakAndWait.
        pending.values.forEach { it.complete(false) }
        pending.clear()
    }

    fun shutdown() {
        neural?.release()
        neuralWorker.shutdownNow()
        tts.shutdown()
    }
}
