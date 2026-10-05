package com.rusty.museecho.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Follows one chat turn on the `/chat/subscribe` NDJSON stream, the way the
 * ESP32 firmware's `muse_chat_link.c` does: each line is
 * `{"type":"event","seq":N,"payload":{…}}`, and the events that matter are
 *
 * - `message.user`: what Muse heard (a voice note's transcript arrives here);
 * - `delta.message_start` / `delta.text_append`: the reply as it streams;
 * - `delta.message_done` / `message.assistant`: a finished reply.
 *
 * Events can arrive before the `/chat/stream` ack names our message id, so
 * rows are held until [acknowledge] and only those related to the turn count.
 */
class ReplyTracker {
    data class Row(
        val event: String,
        val msg: String,
        val replyTo: String,
        val text: String,
        val ready: Boolean,
        val seq: Long,
    )

    private var noteId = ""
    private var parentId = ""
    private var lastSeq = 0L
    private val early = mutableListOf<Row>()
    private val deltaText = LinkedHashMap<String, StringBuilder>()
    private val deltaParent = HashMap<String, String>()
    private val finished = LinkedHashMap<String, String>()

    /** The transcript of what Muse heard, once known. */
    var heard: String = ""
        private set

    /** Reply text so far: finished messages, then the one still streaming. */
    val replyText: String
        get() {
            val parts = finished.values.toMutableList()
            deltaText.forEach { (id, text) -> if (id !in finished && text.isNotEmpty()) parts += text.toString() }
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
            "delta.message_start", "delta.text_append" -> {
                if (row.msg.isEmpty() || !related(row)) return false
                if (row.replyTo.isNotEmpty()) deltaParent[row.msg] = row.replyTo
                val buf = deltaText.getOrPut(row.msg) { StringBuilder() }
                if (row.event == "delta.text_append" && row.text.isNotEmpty()) {
                    buf.append(row.text)
                    return row.msg !in finished
                }
                return false
            }
            "delta.message_done", "message.assistant" -> {
                if (row.msg.isEmpty() || !related(row)) return false
                if (row.event == "message.assistant" && !row.ready) return false
                val text = row.text.ifEmpty { deltaText[row.msg]?.toString().orEmpty() }
                if (text.isEmpty()) return false
                if (finished[row.msg] == text) return false
                finished[row.msg] = text
                return true
            }
        }
        return false
    }

    private fun related(row: Row): Boolean {
        val parent = row.replyTo.ifEmpty { deltaParent[row.msg].orEmpty() }
        // A reply with no parent at all can only be correlated by timing; accept it.
        return parent.isEmpty() || ours(parent)
    }

    companion object {
        fun parse(line: String): Row? {
            val top = runCatching { Json.parseToJsonElement(line) }.getOrNull() as? JsonObject ?: return null
            if (top.str("type") != "event") return null
            val payload = top["payload"] as? JsonObject
            fun pick(key: String): String? = payload?.str(key) ?: top.str(key)
            val text = listOf("display_text", "content", "text").firstNotNullOfOrNull { pick(it) } ?: ""
            val ready = ((payload?.get("display_text_ready") ?: top["display_text_ready"]) as? JsonPrimitive)
                ?.booleanOrNull ?: true
            return Row(
                event = payload?.str("event_name") ?: payload?.str("event") ?: "",
                msg = pick("message_id") ?: pick("id") ?: "",
                replyTo = pick("reply_to_message_id") ?: pick("parent_message_id") ?: "",
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
