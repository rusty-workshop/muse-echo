package com.rusty.museecho.gadget

import com.rusty.museecho.noise.ApplicationResponse
import com.rusty.museecho.noise.BodyChunk
import com.rusty.museecho.noise.Reset
import com.rusty.museecho.protocol.ChatFeed
import com.rusty.museecho.protocol.LinkSession
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Talking to Muse over the gadget's own Noise session.
 *
 * [runFeed] holds one `POST /chat/subscribe` stream open for as long as the
 * session lives and publishes what [ChatFeed] makes of it on [updates]: the
 * Echo's own turns, alerts from other gadgets, and messages Muse starts by
 * itself all arrive there. [turn] sends a message with `POST /chat/stream`
 * and follows its reply on the same feed.
 */
class ChatClient(
    private val session: () -> LinkSession?,
    private val nodeId: String,
) {
    class ChatError(message: String) : Exception(message)

    private val _updates = MutableSharedFlow<ChatFeed.Update>(
        extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val updates: SharedFlow<ChatFeed.Update> = _updates.asSharedFlow()

    @Volatile
    var feedOpen = false
        private set

    /** Keep the subscription open on [link] while it's registered; reopens after drops. */
    suspend fun runFeed(link: LinkSession) {
        val feed = ChatFeed(nodeId)
        while (link.registeredAt != null) {
            try {
                val sub = link.openRequest(
                    "POST", LinkSession.SUBSCRIBE_PATH,
                    LinkSession.jsonHeaders(APP_ID, accept = "application/x-ndjson"), "{}".toByteArray(),
                )
                try {
                    feedOpen = true
                    val lines = LineSplitter()
                    for (frame in sub.frames) {
                        val data = when (val v = frame.value) {
                            is ApplicationResponse -> {
                                if (v.status >= 400) throw ChatError("subscribe refused (HTTP ${v.status})")
                                v.body
                            }
                            is BodyChunk -> v.data
                            is Reset -> break
                            else -> continue
                        }
                        lines.feed(data) { line -> feed.feed(line)?.let { _updates.tryEmit(it) } }
                    }
                } finally {
                    feedOpen = false
                    sub.cancel()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                GadgetState.log("reply feed: ${e.message}")
            }
            delay(FEED_RETRY_MS)
        }
    }

    /**
     * Send [text], or a voice note when [wav] is given, and follow the reply.
     * [onUpdate] gets (what Muse heard, the reply so far) as they change.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    suspend fun turn(text: String?, wav: ByteArray?, onUpdate: (String, ChatFeed.Reply?) -> Unit): ChatFeed.Reply? {
        val link = session()?.takeIf { it.registeredAt != null } ?: throw ChatError("Not connected to Muse")
        if (!feedOpen) throw ChatError("Muse's reply stream isn't open yet")
        val body = buildJsonObject {
            put("message", text ?: "")
            put("output_modality", "text")
            put("device_id", nodeId)
            if (wav != null) {
                put("items", JsonArray(listOf(buildJsonObject {
                    put("type", "file")
                    put("mime_type", "audio/wav")
                    put("filename", "voice_note.wav")
                    put("data_base64", Base64.getEncoder().encodeToString(wav))
                })))
            }
        }
        return coroutineScope {
            // Listen before sending: the reply can beat the ack.
            val inbox = Channel<ChatFeed.Update>(Channel.UNLIMITED)
            val collector = async(start = CoroutineStart.UNDISPATCHED) { updates.collect { inbox.send(it) } }
            try {
                val chat = link.openRequest("POST", LinkSession.CHAT_PATH, LinkSession.jsonHeaders(APP_ID),
                    body.toString().toByteArray())
                val (status, ackBody) = withTimeout(LinkSession.REQUEST_TIMEOUT_MS) { chat.collect() }
                if (status !in 200..299) throw ChatError("Muse didn't take it (HTTP $status)")
                val noteId = ChatFeed.parseAck(ackBody.toString(Charsets.UTF_8))?.first
                    ?: throw ChatError("Muse didn't take it")

                var heard = text.orEmpty()
                val replies = LinkedHashMap<String, ChatFeed.Reply>()
                var quietSince = 0L
                val deadline = System.currentTimeMillis() + REPLY_TIMEOUT_MS
                while (true) {
                    val now = System.currentTimeMillis()
                    if (replies.isNotEmpty() && replies.values.all { it.final } && now - quietSince >= SETTLE_MS) break
                    if (now >= deadline) {
                        if (replies.isNotEmpty()) break
                        throw ChatError("Muse didn't answer")
                    }
                    val update = select<ChatFeed.Update?> {
                        inbox.onReceive { it }
                        onTimeout(POLL_MS) { null }
                    } ?: continue
                    when (update) {
                        is ChatFeed.Update.Heard -> if (update.messageId == noteId) {
                            heard = update.text
                            onUpdate(heard, combine(replies.values))
                        }
                        is ChatFeed.Update.Assistant -> if (update.reply.origin == ChatFeed.Origin.THIS_DEVICE) {
                            replies[update.reply.id] = update.reply
                            quietSince = System.currentTimeMillis()
                            onUpdate(heard, combine(replies.values))
                        }
                    }
                }
                combine(replies.values)
            } finally {
                collector.cancel()
            }
        }
    }

    /** Several reply messages shown as one. */
    private fun combine(replies: Collection<ChatFeed.Reply>): ChatFeed.Reply? {
        if (replies.isEmpty()) return null
        return replies.first().copy(
            text = replies.joinToString("\n\n") { it.text }.trim(),
            images = replies.flatMap { it.images }.distinct(),
            final = replies.all { it.final },
        )
    }

    /** Splits a byte stream into UTF-8 lines; an oversized line is dropped. */
    private class LineSplitter {
        private val buf = java.io.ByteArrayOutputStream()
        private var skipping = false

        fun feed(data: ByteArray, onLine: (String) -> Unit) {
            for (b in data) {
                if (b == '\n'.code.toByte()) {
                    if (!skipping && buf.size() > 0) onLine(buf.toString(Charsets.UTF_8.name()).trim())
                    buf.reset()
                    skipping = false
                } else if (!skipping) {
                    buf.write(b.toInt())
                    if (buf.size() > MAX_LINE) {
                        buf.reset()
                        skipping = true
                    }
                }
            }
        }
    }

    companion object {
        /** The Muse web app's id; the ESP32 boards use it for their chat turns. */
        const val APP_ID = "hatch-web"
        const val REPLY_TIMEOUT_MS = 120_000L
        const val SETTLE_MS = 2_500L
        const val POLL_MS = 250L
        const val FEED_RETRY_MS = 5_000L
        const val MAX_LINE = 512 * 1024
    }
}
