package com.rusty.museecho.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Routes the always-open `/chat/subscribe` NDJSON stream. Each line is
 * `{"type":"event","event":"<name>","seq":N,"payload":{…}}`. The stream
 * carries every account event (agent status, approvals, other chats), so
 * only user and assistant messages are looked at, and each assistant reply
 * is classified by where it belongs:
 *
 * - [Origin.THIS_DEVICE]: tagged `chat_context.device_id` = this Echo (replies
 *   to Echo turns, and anything Muse later sends "to" the Echo);
 * - [Origin.OTHER_GADGET]: tagged with another gadget (e.g. the Latitude's
 *   `send-user-msg` alerts and Muse's answer to them);
 * - [Origin.MUSE_INITIATED]: untagged, while no user message is being handled
 *   (a user message stays pending until its `task.status` completes), i.e.
 *   scheduled tasks, reminders, Muse following up on its own;
 * - [Origin.OTHER]: anything else, e.g. a conversation on the phone. Never shown.
 */
class ChatFeed(private val deviceId: String, private val now: () -> Long = System::currentTimeMillis) {
    enum class Origin { THIS_DEVICE, OTHER_GADGET, MUSE_INITIATED, OTHER }

    data class Reply(
        val id: String,
        val origin: Origin,
        val device: String?,
        val text: String,
        val images: List<String>,
        val final: Boolean,
        /** What prompted it, when known: the user message's text and the device it came from. */
        val prompt: String?,
        val promptDevice: String?,
    )

    sealed class Update {
        data class Heard(val messageId: String, val text: String, val device: String?) : Update()
        data class Assistant(val reply: Reply) : Update()
    }

    /** Mutable: a message's text often arrives in a later event than the message itself. */
    private class UserMsg(var text: String, val device: String?, val at: Long)

    private class Draft(val origin: Origin, val device: String?, val prompt: UserMsg?) {
        val text = StringBuilder()
        val images = linkedSetOf<String>()
        var final = false
    }

    private var lastSeq = 0L
    private var lastUser: UserMsg? = null

    /** A user message Muse is still working on (cleared when its task completes). */
    private var pendingUser: UserMsg? = null
    private val users = LinkedHashMap<String, UserMsg>()
    private val drafts = LinkedHashMap<String, Draft>()

    /** Feed one line; returns what changed (usually nothing). */
    fun feed(line: String): Update? {
        val top = runCatching { Json.parseToJsonElement(line) }.getOrNull() as? JsonObject ?: return null
        if (top.str("type") != "event") return null
        val seq = (top["seq"] as? JsonPrimitive)?.longOrNull ?: 0L
        if (seq != 0L) {
            // A reconnect starts the counter over; only drop exact replays.
            if (seq <= lastSeq && lastSeq - seq < REPLAY_WINDOW) return null
            lastSeq = seq
        }
        val payload = top["payload"] as? JsonObject ?: JsonObject(emptyMap())
        val event = top.str("event") ?: payload.str("event_name") ?: return null
        if (event == "task.status") {
            if (payload.str("status") in DONE_STATUSES) pendingUser = null
            return null
        }
        val msg = payload.str("message_id") ?: return null
        val device = ((payload["chat_context"] as? JsonObject)?.get("device_id") as? JsonPrimitive)
            ?.takeIf { it.isString }?.content
        val text = listOf("display_text", "content", "text").firstNotNullOfOrNull { payload.str(it) } ?: ""

        return when (event) {
            "message.user" -> {
                val ready = (payload["display_text_ready"] as? JsonPrimitive)?.booleanOrNull ?: true
                // "<transcript>\n[file:audio/wav …]", or "[Voice note]" before transcription.
                val clean = text.substringBefore("\n[file:").trim()
                val known = users[msg]
                val user = known ?: UserMsg("", device, now()).also {
                    users[msg] = it
                    trim(users)
                }
                if (known == null) {
                    lastUser = user
                    pendingUser = user
                }
                if (clean.isEmpty() || clean == "[Voice note]" || !ready || user.text == clean) return null
                user.text = clean
                Update.Heard(msg, clean, device)
            }
            "delta.message_start" -> {
                if (msg in drafts) return null
                drafts[msg] = Draft(classify(device), device, lastUser?.takeIf { now() - it.at < PROMPT_WINDOW_MS })
                trim(drafts)
                collectImages(payload, drafts.getValue(msg))
                null
            }
            "delta.text_append" -> {
                val d = drafts[msg] ?: return null
                if (d.final || text.isEmpty()) return null
                d.text.append(text)
                Update.Assistant(snapshot(msg, d))
            }
            "delta.message_done", "message.assistant" -> {
                val d = drafts[msg] ?: run {
                    if (event != "message.assistant") return null
                    Draft(classify(device), device, lastUser?.takeIf { now() - it.at < PROMPT_WINDOW_MS })
                        .also { drafts[msg] = it }
                }
                if (event == "message.assistant" && (payload["display_text_ready"] as? JsonPrimitive)?.booleanOrNull == false) return null
                collectImages(payload, d)
                val before = d.text.toString()
                if (text.isNotEmpty() && text != before) {
                    d.text.setLength(0)
                    d.text.append(text)
                }
                if (d.final && d.text.toString() == before) return null
                d.final = true
                MARKDOWN_IMAGE.findAll(d.text).forEach { d.images += it.groupValues[1] }
                if (d.text.isBlank() && d.images.isEmpty()) return null
                Update.Assistant(snapshot(msg, d))
            }
            else -> null
        }
    }

    private fun classify(device: String?): Origin = when {
        device == deviceId -> Origin.THIS_DEVICE
        device != null && device.startsWith("homelink-") -> Origin.OTHER_GADGET
        // A stuck pending message (missed completion) stops blocking after a while.
        device == null && (pendingUser?.let { now() - it.at > STUCK_MS } ?: true) -> Origin.MUSE_INITIATED
        else -> Origin.OTHER
    }

    private fun snapshot(id: String, d: Draft) = Reply(
        id = id, origin = d.origin, device = d.device, text = d.text.toString(), images = d.images.toList(),
        final = d.final, prompt = d.prompt?.text?.takeIf { it.isNotEmpty() }, promptDevice = d.prompt?.device,
    )

    private fun collectImages(payload: JsonObject, d: Draft) {
        for (key in listOf("resources", "presentations", "widgets", "attachments", "items")) {
            payload[key]?.let { findImages(it, d.images) }
        }
        MARKDOWN_IMAGE.findAll(payload.str("text").orEmpty() + payload.str("display_text").orEmpty())
            .forEach { d.images += it.groupValues[1] }
    }

    private fun <K, V> trim(map: LinkedHashMap<K, V>) {
        while (map.size > 64) map.remove(map.keys.first())
    }

    companion object {
        const val PROMPT_WINDOW_MS = 30_000L
        const val STUCK_MS = 15 * 60_000L
        private val DONE_STATUSES = setOf("completed", "failed", "cancelled", "canceled", "error")
        private const val REPLAY_WINDOW = 10_000L
        private val MARKDOWN_IMAGE = Regex("""!\[[^\]]*]\((https?://[^)\s]+)\)""")
        private val IMAGE_EXT = Regex("""(?i)\.(png|jpe?g|webp|gif)(\?.*)?$""")

        /**
         * Image URLs anywhere inside rich-reply JSON: a URL-ish field whose
         * value looks like an image, or whose object says it's an image.
         */
        fun findImages(e: JsonElement, out: MutableSet<String>) {
            when (e) {
                is JsonArray -> e.forEach { findImages(it, out) }
                is JsonObject -> {
                    val isImage = listOf("mime_type", "content_type", "type", "kind").any {
                        e.str(it)?.lowercase()?.let { v -> v.startsWith("image") } == true
                    }
                    for ((k, v) in e) {
                        val s = (v as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                        if (s != null && s.startsWith("http") &&
                            (IMAGE_EXT.containsMatchIn(s) || (isImage && k.lowercase() in URL_KEYS) || k.lowercase().contains("image"))
                        ) {
                            out += s
                        } else if (v !is JsonPrimitive) {
                            findImages(v, out)
                        }
                    }
                }
                else -> Unit
            }
        }

        /** (message_id, reply_to_message_id) from a `/chat/stream` ack body. */
        fun parseAck(body: String): Pair<String, String>? {
            val root = runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return null
            val obj = root["result"] as? JsonObject ?: root
            val id = obj.str("message_id")?.takeIf { it.isNotEmpty() } ?: return null
            return id to (obj.str("reply_to_message_id") ?: "")
        }

        private val URL_KEYS = setOf("url", "uri", "src", "href", "download_url", "preview_url", "thumbnail_url")
    }
}
