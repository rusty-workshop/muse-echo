package com.rusty.museecho.gadget

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

/**
 * Piper neural voices run inside the app with sherpa-onnx, so the quality
 * level can change instantly (no engine app to reinstall) and speech starts
 * after the first sentence is generated rather than the whole reply.
 * Models are downloaded on first use into the app's files dir.
 */
class NeuralVoice(private val dir: File, private val log: (String) -> Unit) {
    // Full-precision models: on the Echo's 32-bit ARM userspace onnxruntime's
    // int8 kernels ran 3x slower than fp32 (0.3x vs 1.0x real time for Low).
    enum class Level(val label: String, val archive: String, val sizeMb: Int) {
        LOW("Low", "vits-piper-en_US-lessac-low", 63),
        MEDIUM("Medium", "vits-piper-en_US-lessac-medium", 64),
        HIGH("High", "vits-piper-en_US-lessac-high", 110),
    }

    init {
        // Drop models from levels that no longer exist (e.g. the old int8 builds).
        val keep = Level.entries.map { it.archive }.toSet()
        dir.listFiles()?.filter { it.isDirectory && it.name !in keep }?.forEach { it.deleteRecursively() }
    }

    sealed class Status {
        data object Idle : Status()
        data class Downloading(val level: Level, val percent: Int) : Status()
        data class Ready(val level: Level) : Status()
        data class Failed(val level: Level, val why: String) : Status()
    }

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val http = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()
    private var tts: OfflineTts? = null
    private var loaded: Level? = null

    @Volatile
    private var cancel = false

    fun installed(level: Level) = modelFile(level) != null

    private fun levelDir(level: Level) = File(dir, level.archive)

    private fun modelFile(level: Level): File? =
        levelDir(level).listFiles()?.firstOrNull { it.name.endsWith(".onnx") }
            ?.takeIf { File(levelDir(level), "tokens.txt").isFile && File(levelDir(level), "espeak-ng-data").isDirectory }

    /** Download (if needed) and load [level]. Returns an error message, or null when ready. */
    suspend fun use(level: Level): String? = withContext(Dispatchers.IO) {
        try {
            if (!installed(level)) download(level)
            load(level)
            _status.value = Status.Ready(level)
            null
        } catch (e: Exception) {
            val why = e.message ?: e.javaClass.simpleName
            log("neural voice ${level.label}: $why")
            _status.value = Status.Failed(level, why)
            why
        }
    }

    private fun download(level: Level) {
        _status.value = Status.Downloading(level, 0)
        val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/${level.archive}.tar.bz2"
        val staging = File(dir, "${level.archive}.partial")
        staging.deleteRecursively()
        staging.mkdirs()
        http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) error("download failed (HTTP ${resp.code})")
            val body = resp.body ?: error("empty download")
            val total = body.contentLength().takeIf { it > 0 } ?: (level.sizeMb * 1_048_576L)
            val counting = object : java.io.FilterInputStream(body.byteStream()) {
                var read = 0L
                override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also {
                    if (it > 0) {
                        read += it
                        _status.value = Status.Downloading(level, (read * 100 / total).toInt().coerceAtMost(99))
                    }
                }
            }
            TarArchiveInputStream(BZip2CompressorInputStream(counting.buffered())).use { tar ->
                val root = staging.canonicalPath
                while (true) {
                    val entry = tar.nextEntry ?: break
                    // Archives hold one top-level folder; drop it.
                    val rel = entry.name.substringAfter('/', "")
                    if (rel.isEmpty()) continue
                    val out = File(staging, rel)
                    if (!out.canonicalPath.startsWith(root)) error("bad path in archive")
                    if (entry.isDirectory) out.mkdirs() else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { tar.copyTo(it) }
                    }
                }
            }
        }
        levelDir(level).deleteRecursively()
        if (!staging.renameTo(levelDir(level))) error("couldn't save the model")
        if (!installed(level)) error("model files missing after download")
        log("neural voice ${level.label} downloaded")
    }

    @Synchronized
    private fun load(level: Level) {
        if (loaded == level && tts != null) return
        val model = modelFile(level) ?: error("model not installed")
        val vits = OfflineTtsVitsModelConfig(
            model = model.absolutePath,
            tokens = File(levelDir(level), "tokens.txt").absolutePath,
            dataDir = File(levelDir(level), "espeak-ng-data").absolutePath,
        )
        // One sentence per callback, so playback can start after the first.
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(vits = vits, numThreads = 4, debug = false, provider = "cpu"),
            maxNumSentences = 1,
        )
        tts?.release()
        tts = OfflineTts(null, config)
        loaded = level
        log("neural voice ${level.label} loaded")
    }

    val ready: Boolean get() = tts != null && _status.value is Status.Ready

    /**
     * Speak [text], blocking until done or [stop]ped. Generation and playback
     * overlap: sentences are generated into a queue while earlier ones play,
     * and the first chunk is cut at the first comma so speech starts sooner.
     * Calls [onStart] when the first audio plays. Returns false if stopped or it failed.
     */
    fun speakBlocking(text: String, onStart: () -> Unit): Boolean {
        val engine = synchronized(this) { tts } ?: return false
        cancel = false
        val rate = engine.sampleRate()
        val queue = java.util.concurrent.LinkedBlockingQueue<FloatArray>()
        val end = FloatArray(0)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT), rate))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        val player = Thread({
            var started = false
            var written = 0L
            try {
                while (!cancel) {
                    val chunk = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                    if (chunk === end) break
                    if (!started) {
                        track.play()
                        started = true
                        onStart()
                    }
                    track.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING)
                    written += chunk.size
                }
                if (!cancel && started) {
                    // Let the buffer drain before reporting done.
                    val until = System.currentTimeMillis() + 1500
                    while (!cancel && track.playbackHeadPosition < written && System.currentTimeMillis() < until) Thread.sleep(20)
                }
            } finally {
                runCatching { track.stop() }
                track.release()
            }
        }, "neural-player")
        player.start()
        return try {
            for (part in firstChunks(text)) {
                if (cancel) break
                engine.generateWithCallback(part, 0, 1.0f) { samples ->
                    if (cancel) return@generateWithCallback 0
                    queue.put(samples.copyOf())
                    1
                }
            }
            queue.put(end)
            player.join()
            !cancel
        } catch (e: Exception) {
            log("neural voice failed: ${e.message}")
            cancel = true
            player.join(2000)
            false
        }
    }

        fun stop() {
        cancel = true
    }

    /**
     * Generate [text] without playing it and return how many seconds of audio
     * one second of work produces (above 1.0 = faster than real time).
     */
    fun speedTest(text: String): Double? {
        val engine = synchronized(this) { tts } ?: return null
        val t0 = System.nanoTime()
        val audio = engine.generate(text, 0, 1.0f)
        val took = (System.nanoTime() - t0) / 1e9
        val seconds = audio.samples.size.toDouble() / audio.sampleRate
        return if (took > 0) seconds / took else null
    }

    companion object {
        /** The text with its first sentence cut at the first comma (if long), so speech starts sooner. */
        fun firstChunks(text: String): List<String> {
            val t = text.trim()
            val sentenceEnd = Regex("""[.!?](\s|$)""").find(t)?.range?.first ?: t.length
            val comma = Regex("""[,;:](\s)""").find(t)?.range?.first
            if (comma == null || comma >= sentenceEnd || comma < 12 || sentenceEnd < 50) return listOf(t)
            return listOf(t.substring(0, comma + 1), t.substring(comma + 1).trim()).filter { it.isNotEmpty() }
        }
    }

    /** Synthesize [text] to mono 16 kHz PCM without playing it (for wake-phrase checks). */
    fun synthesize16k(text: String): ShortArray? {
        val engine = synchronized(this) { tts } ?: return null
        val audio = engine.generate(text, 0, 1.0f)
        val src = audio.samples
        val ratio = audio.sampleRate / 16_000.0
        val n = (src.size / ratio).toInt()
        // Linear resampling is plenty for a recogniser check.
        return ShortArray(n) { i ->
            val pos = i * ratio
            val j = pos.toInt().coerceAtMost(src.size - 1)
            val k = (j + 1).coerceAtMost(src.size - 1)
            val v = src[j] + (src[k] - src[j]) * (pos - j).toFloat()
            (v.coerceIn(-1f, 1f) * 32767).toInt().toShort()
        }
    }

    fun release() {
        cancel = true
        synchronized(this) {
            tts?.release()
            tts = null
            loaded = null
        }
    }
}
