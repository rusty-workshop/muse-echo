package com.rusty.museecho.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyTrackerTest {
    private fun ev(seq: Int, event: String, extra: String) =
        """{"type":"event","seq":$seq,"payload":{"event_name":"$event",$extra}}"""

    @Test
    fun followsAVoiceNoteTurn() {
        val t = ReplyTracker()
        // Arrives before the ack: held, then replayed.
        t.feedLine(ev(1, "message.user", """"message_id":"u1","content":"[Voice note]""""))
        t.acknowledge("u1", "")
        assertEquals("", t.heard)
        assertTrue(t.feedLine(ev(2, "message.user", """"message_id":"u1","content":"turn on the lights\n[file:audio/wav voice_note.wav]"""")))
        assertEquals("turn on the lights", t.heard)

        t.feedLine(ev(3, "delta.message_start", """"message_id":"a1","reply_to_message_id":"u1""""))
        assertTrue(t.feedLine(ev(4, "delta.text_append", """"message_id":"a1","text":"Sure, """")))
        assertTrue(t.feedLine(ev(5, "delta.text_append", """"message_id":"a1","text":"done.""""))) // no parent: known from start
        assertEquals("Sure, done.", t.replyText)
        assertFalse(t.hasFinalReply)

        // Unrelated traffic from another conversation is ignored.
        assertFalse(t.feedLine(ev(6, "delta.text_append", """"message_id":"x9","reply_to_message_id":"other","text":"nope"""")))
        // Replayed sequence numbers are ignored.
        assertFalse(t.feedLine(ev(5, "delta.text_append", """"message_id":"a1","text":"dup"""")))

        assertTrue(t.feedLine(ev(7, "delta.message_done", """"message_id":"a1","reply_to_message_id":"u1"""")))
        assertTrue(t.hasFinalReply)
        assertEquals("Sure, done.", t.replyText)
        // The persisted full message coalesces with the streamed one.
        assertFalse(t.feedLine(ev(8, "message.assistant", """"message_id":"a1","reply_to_message_id":"u1","display_text":"Sure, done.","display_text_ready":true""")))
        assertEquals("Sure, done.", t.replyText)
    }

    @Test
    fun parsesAcks() {
        assertEquals("m1" to "p0", ReplyTracker.parseAck("""{"result":{"message_id":"m1","reply_to_message_id":"p0"}}"""))
        assertEquals("m2" to "", ReplyTracker.parseAck("""{"message_id":"m2"}"""))
        assertEquals(null, ReplyTracker.parseAck("""{"result":{}}"""))
    }
}
