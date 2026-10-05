package com.rusty.museecho.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Event shapes as the Muse VM actually sends them on /chat/subscribe (synthetic ids and text). */
class ReplyTrackerTest {
    private val me = "homelink-a2d9ef"

    private fun ev(seq: Int, event: String, payload: String) =
        """{"event":"$event","payload":{$payload},"seq":$seq,"ts_ms":1,"type":"event"}"""

    private fun ctx(device: String) =
        """"chat_context":{"chat_id":"c1","chat_kind":"direct","device_id":"$device","output_modality":"text"}"""

    @Test
    fun followsAReplyTaggedForThisDevice() {
        val t = ReplyTracker(me)
        // Noise before the ack: status events and our own user message.
        t.feedLine(ev(10, "agent.status", """"activity_code":"online""""))
        t.feedLine(ev(11, "message.user", """${ctx(me)},"display_text":"[Voice note]","display_text_ready":true,"message_id":"u1""""))
        t.acknowledge("u1", "u1")
        assertEquals("", t.heard)
        assertTrue(t.feedLine(ev(12, "message.user", """${ctx(me)},"display_text":"what time is it\n[file:audio/wav voice_note.wav]","message_id":"u1"""")))
        assertEquals("what time is it", t.heard)

        t.feedLine(ev(13, "delta.message_start", """${ctx(me)},"message_id":"assistant-msg-a1","reply_to_message_id":"","seq":1"""))
        assertTrue(t.feedLine(ev(14, "delta.text_append", """"message_id":"assistant-msg-a1","parent_message_id":"assistant-msg-a1","reply_to_message_id":"","text":"It's """")))
        assertTrue(t.feedLine(ev(15, "delta.text_append", """"message_id":"assistant-msg-a1","reply_to_message_id":"","text":"noon."""")))
        assertEquals("It's noon.", t.replyText)
        assertFalse(t.hasFinalReply)

        // A reply to a chat on the phone is someone else's.
        assertFalse(t.feedLine(ev(16, "delta.message_start", """${ctx("phone")},"message_id":"assistant-msg-x","reply_to_message_id":"","seq":9""")))
        assertFalse(t.feedLine(ev(17, "delta.text_append", """"message_id":"assistant-msg-x","text":"nope"""")))
        // Replayed sequence numbers are ignored.
        assertFalse(t.feedLine(ev(15, "delta.text_append", """"message_id":"assistant-msg-a1","text":"dup"""")))

        assertTrue(t.feedLine(ev(18, "delta.message_done", """${ctx(me)},"message_id":"assistant-msg-a1","reply_to_message_id":"","seq":9""")))
        assertTrue(t.hasFinalReply)
        assertEquals("It's noon.", t.replyText)
        // The persisted full message coalesces with the streamed one.
        assertFalse(t.feedLine(ev(19, "message.assistant", """${ctx(me)},"message_id":"assistant-msg-a1","display_text":"It's noon.","display_text_ready":true""")))
        assertEquals("It's noon.", t.replyText)
    }

    @Test
    fun repliesThatArriveBeforeTheAckStillCount() {
        val t = ReplyTracker(me)
        t.feedLine(ev(1, "delta.message_start", """${ctx(me)},"message_id":"m","reply_to_message_id":"""""))
        t.feedLine(ev(2, "delta.text_append", """"message_id":"m","text":"OK""""))
        t.feedLine(ev(3, "delta.message_done", """${ctx(me)},"message_id":"m""""))
        t.acknowledge("u9", "u9")
        assertTrue(t.hasFinalReply)
        assertEquals("OK", t.replyText)
    }

    @Test
    fun parsesAcks() {
        assertEquals("m1" to "m1", ReplyTracker.parseAck("""{"channel":"main","message_id":"m1","reply_to_message_id":"m1","session_id":"s"}"""))
        assertEquals("m2" to "", ReplyTracker.parseAck("""{"result":{"message_id":"m2"}}"""))
        assertEquals(null, ReplyTracker.parseAck("""{"result":{}}"""))
    }
}
