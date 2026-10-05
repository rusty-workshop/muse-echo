package com.rusty.museecho.protocol

import com.rusty.museecho.protocol.ChatFeed.Origin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Event shapes as the Muse VM sends them on /chat/subscribe (synthetic ids and text). */
class ChatFeedTest {
    private val me = "homelink-echo01"
    private var clock = 1_000_000L
    private var seq = 0

    private fun ev(event: String, payload: String) =
        """{"event":"$event","payload":{$payload},"seq":${++seq},"ts_ms":1,"type":"event"}"""

    private fun ctx(device: String) = """"chat_context":{"chat_id":"c1","device_id":"$device","output_modality":"text"}"""

    private fun reply(feed: ChatFeed, id: String, device: String?, vararg parts: String): ChatFeed.Reply {
        val c = device?.let { ctx(it) + "," } ?: ""
        feed.feed(ev("delta.message_start", """$c"message_id":"$id","reply_to_message_id":"""""))
        parts.forEach { feed.feed(ev("delta.text_append", """"message_id":"$id","text":"$it"""")) }
        val done = feed.feed(ev("delta.message_done", """$c"message_id":"$id""""))
        return (done as ChatFeed.Update.Assistant).reply
    }

    @Test
    fun classifiesRepliesByWhereTheyBelong() {
        val feed = ChatFeed(me) { clock }

        // An Echo turn: heard text, then a reply tagged for the Echo.
        val heard = feed.feed(ev("message.user", """${ctx(me)},"display_text":"what's the weather","display_text_ready":true,"message_id":"u1""""))
        assertEquals("what's the weather", (heard as ChatFeed.Update.Heard).text)
        val r1 = reply(feed, "a1", me, "Sunny ", "and warm.")
        assertEquals(Origin.THIS_DEVICE, r1.origin)
        assertEquals("Sunny and warm.", r1.text)
        assertTrue(r1.final)
        feed.feed(ev("task.status", """"status":"completed""""))

        // An alert from the server gadget, and Muse's answer to it.
        // Its text arrives in a later event than the message itself.
        feed.feed(ev("message.user", """${ctx("homelink-server")},"display_text":"","display_text_ready":false,"message_id":"u2""""))
        feed.feed(ev("delta.message_start", """${ctx("homelink-server")},"message_id":"a2","reply_to_message_id":"""""))
        feed.feed(ev("message.user", """${ctx("homelink-server")},"display_text":"Backup failed","display_text_ready":true,"message_id":"u2""""))
        val r2 = reply(feed, "a2", "homelink-server", "The nightly backup failed.")
        assertEquals(Origin.OTHER_GADGET, r2.origin)
        assertEquals("Backup failed", r2.prompt)
        feed.feed(ev("task.status", """"status":"completed""""))

        // A chat on the phone (untagged) stays off the Echo, even if Muse is slow.
        feed.feed(ev("message.user", """"display_text":"phone question","message_id":"u3""""))
        clock += 120_000
        assertEquals(Origin.OTHER, reply(feed, "a3", null, "phone answer").origin)
        feed.feed(ev("task.status", """"status":"completed""""))

        // Muse speaking up on its own: untagged, nothing pending.
        clock += 60_000
        assertEquals(Origin.MUSE_INITIATED, reply(feed, "a4", null, "Reminder: bins go out tonight.").origin)
    }

    @Test
    fun ignoresNoiseAndReplays() {
        val feed = ChatFeed(me) { clock }
        assertNull(feed.feed("""{"event":"agent.status","payload":{"activity_code":"online"},"seq":5,"type":"event"}"""))
        assertNull(feed.feed("""{"event":"approvals.snapshot","payload":{"pending_approvals":[]},"seq":6,"type":"event"}"""))
        assertNull(feed.feed("not json"))
        feed.feed("""{"event":"delta.message_start","payload":{${ctx(me)},"message_id":"m"},"seq":7,"type":"event"}""")
        val first = feed.feed("""{"event":"delta.text_append","payload":{"message_id":"m","text":"hi"},"seq":8,"type":"event"}""")
        assertTrue(first is ChatFeed.Update.Assistant)
        assertNull(feed.feed("""{"event":"delta.text_append","payload":{"message_id":"m","text":"hi"},"seq":8,"type":"event"}"""))
    }

    @Test
    fun findsImagesInRichReplies() {
        val feed = ChatFeed(me) { clock }
        feed.feed(ev("delta.message_start", """${ctx(me)},"message_id":"p","resources":[{"type":"image","url":"https://x.test/a"}]"""))
        feed.feed(ev("delta.text_append", """"message_id":"p","text":"Here: ![fox](https://x.test/fox.jpg)""""))
        val done = feed.feed(ev("delta.message_done", """${ctx(me)},"message_id":"p","presentations":[{"kind":"card","image_url":"https://x.test/c.png?s=1"}]""")) as ChatFeed.Update.Assistant
        assertEquals(setOf("https://x.test/a", "https://x.test/c.png?s=1", "https://x.test/fox.jpg"), done.reply.images.toSet())
    }

    @Test
    fun parsesAcks() {
        assertEquals("m1" to "m1", ChatFeed.parseAck("""{"channel":"main","message_id":"m1","reply_to_message_id":"m1"}"""))
        assertEquals("m2" to "", ChatFeed.parseAck("""{"result":{"message_id":"m2"}}"""))
        assertNull(ChatFeed.parseAck("""{"result":{}}"""))
    }
}
