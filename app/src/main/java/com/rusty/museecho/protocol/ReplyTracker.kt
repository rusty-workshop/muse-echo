package com.rusty.museecho.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Follows one chat turn on the `/chat/subscribe` NDJSON stream. Each line is
 * `{"type":"event","event":"<name>","seq":N,"payload":{…}}`; the stream also
 * carries every other account event (agent status, approvals, other chats),
 * so only these count:
 *
 * - `message.user` with our message id: what Muse heard (a voice note's
 *   transcript arrives here);
 * - `delta.message_start` tagged with this device in `chat_context.device_id`:
 *   a reply to this device begins (assistant replies carry no usable
 *   `reply_to_message_id`, so the device tag is what ties them to the Echo);
 * - `delta.text_append` / `delta.message_done` for a reply we've seen start;
 * - `message.assistant` tagged with this device: a complete reply.
 *
 * Events can arrive before the `/chat/stream` ack names our message id, so
 * rows are held until [acknowledge].
 */
class ReplyTracker(private val deviceId: String) {
    data class Row(
        val event: String,
        val msg: String,
        val replyTo: String,
        val device: String?,
        val text: String,
        val ready: Boolean,
        val seq: Long,
    )

    private var noteId = ""
    private var parentId = ""
    private var lastSeq = 0L
    private val early = mutableListOf<Row>()
    private val replies = LinkedHashMap<String, StringBuilder>()
    private val finished = LinkedHashMap<String, String>()

    /** The transcript of what Muse heard, once known. */
    var heard: String = ""
        private set

    /** Reply text so far: finished messages, then any still streaming. */
    val replyText: String
        get() {
            val parts = mutableListOf<String>()
            replies.forEach { (id, text) -> (finished[id] ?: text.toString()).takeIf { it.isNotBlank() }?.let(parts::add) }
            return parts.joinToString("\n\n")
        }

    /** True once at least one reply message has finished. */
    val hasFinalReply: Boolean get() = finished.isNotEmpty()

    val acknowledged: Boolean get() = noteId.isNotEmpty()

    /** Record the `/chat/stream` ack; replays rows that arrived before it. */
    fun acknowledge(messageId: String, replyToMessageId: String) {
        noteId = messageId
        parentId = replyToMessageId
        val held = early.toList()
        early.clear()
        held.forEach { apply(it) }
    }

    /** Feed one NDJSON line; returns true if the visible state changed. */
    fun feedLine(line: String): Boolean {
        val row = parse(line) ?: return false
        if (row.seq != 0L) {
            if (row.seq <= lastSeq) return false
            lastSeq = row.seq
        }
        if (!acknowledged) {
            early += row
            return false
        }
        return apply(row)
    }

    private fun ours(id: String) = id.isNotEmpty() && (id == noteId || id == parentId)

    /** A reply is for this device if it says so, or (untagged) answers our message. */
    private fun forUs(row: Row) = if (row.device != null) row.device == deviceId else ours(row.replyTo)

    private fun apply(row: Row): Boolean {
        when (row.event) {
            "message.user" -> {
                if (!ours(row.msg)) return false
                // "<transcript>\n[file:audio/wav …]", or "[Voice note]" before transcription.
                val text = row.text.substringBefore("\n[file:").trim()
                if (text.isEmpty() || text == "[Voice note]" || text == heard) return false
                heard = text
                return true
            }
            "delta.message_start" -> {
                if (row.msg.isEmpty() || !forUs(row) || row.msg in replies) return false
                replies[row.msg] = StringBuilder()
                return false
            }
            "delta.text_append" -> {
                val buf = replies[row.msg] ?: return false
                if (row.text.isEmpty() || row.msg in finished) return false
                buf.append(row.text)
                return true
            }
            "delta.message_done", "message.assistant" -> {
                if (row.msg.isEmpty()) return false
                if (row.msg !in replies) {
                    if (row.event != "message.assistant" || !forUs(row)) return false
                    replies[row.msg] = StringBuilder()
                }
                if (row.event == "message.assistant" && !row.ready) return false
                val text = row.text.ifEmpty { replies[row.msg].toString() }
                if (text.isBlank() || finished[row.msg] == text) return false
                finished[row.msg] = text
                return true
            }
        }
        return false
    }

    companion object {
        fun parse(line: String): Row? {
            val top = runCatching { Json.parseToJsonElement(line) }.getOrNull() as? JsonObject ?: return null
            if (top.str("type") != "event") return null
            val payload = top["payload"] as? JsonObject ?: JsonObject(emptyMap())
            fun pick(key: String): String? = payload.str(key) ?: top.str(key)
            val text = listOf("display_text", "content", "text").firstNotNullOfOrNull { pick(it) } ?: ""
            val ready = (payload["display_text_ready"] as? JsonPrimitive)?.booleanOrNull ?: true
            val device = ((payload["chat_context"] as? JsonObject)?.get("device_id") as? JsonPrimitive)
                ?.takeIf { it.isString }?.content
            return Row(
                event = top.str("event") ?: payload.str("event_name") ?: payload.str("event") ?: "",
                msg = pick("message_id") ?: "",
                replyTo = pick("reply_to_message_id").orEmpty(),
                device = device,
                text = text,
                ready = ready,
                seq = (top["seq"] as? JsonPrimitive)?.longOrNull ?: 0L,
            )
        }

        /** (message_id, reply_to_message_id) from a `/chat/stream` ack body. */
        fun parseAck(body: String): Pair<String, String>? {
            val root = runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return null
            val obj = root["result"] as? JsonObject ?: root
            val id = obj.str("message_id")?.takeIf { it.isNotEmpty() } ?: return null
            return id to (obj.str("reply_to_message_id") ?: "")
        }
    }
}
