package com.rusty.museecho.gadget

import com.rusty.museecho.noise.ApplicationRequest
import com.rusty.museecho.noise.BodyChunk
import com.rusty.museecho.noise.Reset
import com.rusty.museecho.protocol.DeviceDescription
import com.rusty.museecho.protocol.FakeVm
import com.rusty.museecho.protocol.LinkSession
import com.rusty.museecho.protocol.MessageDecoder
import com.rusty.museecho.protocol.str
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatClientTest {
    @Test
    fun voiceNoteTurnCollectsTheReply() = runBlocking<Unit> {
        withTimeout(20_000) {
            val vm = FakeVm()
            val session = LinkSession("h", "v", "t", DeviceDescription("homelink-abcdef", "Echo", "1", JsonObject(emptyMap())),
                { _, _, _ -> JsonObject(emptyMap()) }, vm.connector, log = {})
            val run = async { session.run() }
            vm.handshake()
            val control = vm.next().streamId
            val register = vm.nextMessage(MessageDecoder())
            vm.respond(control, 200, LinkSession.encodeMessage(buildJsonObject {
                put("id", register.str("id"))
                putJsonObject("result") {}
            }), end = false)
            while (session.registeredAt == null) delay(10)

            val updates = mutableListOf<String>()
            val chat = ChatClient({ session }, "homelink-abcdef")
            val wav = ByteArray(100) { 1 }
            val turn = async { chat.turn(null, wav) { updates += it.replyText } }

            val sub = vm.next()
            assertEquals("/chat/subscribe", (sub.value as ApplicationRequest).path)
            val post = vm.next()
            val postReq = post.value as ApplicationRequest
            assertEquals("/chat/stream", postReq.path)
            val body = Json.parseToJsonElement(String(postReq.body)).jsonObject
            val item = (body["items"] as JsonArray)[0].jsonObject
            assertEquals("audio/wav", item.str("mime_type"))
            assertEquals(java.util.Base64.getEncoder().encodeToString(wav), item.str("data_base64"))

            vm.respond(sub.streamId, 200, ByteArray(0), end = false)
            // Reply events, one split across two frames, before and after the ack.
            val ctx = """"chat_context":{"device_id":"homelink-abcdef"}"""
            // Reply events, one line split across two frames, before and after the ack.
            vm.reply(sub.streamId, BodyChunk(
                ("""{"event":"message.user","payload":{$ctx,"display_text":"what time is it","message_id":"u1"},"seq":1,"type":"event"}
{"event":"delta.message_start","payload":{$ctx,"message_id":"assistant-msg-a1","reply_to_message_id":""},"seq":2,"type":"event"}
{"event":"delta.text_append","payload":{"message_id":"assistant-msg-a1","text":"It's "},"seq":3,"type":"event"}
{"event":"delta.text_ap""").toByteArray()))
            vm.respond(post.streamId, 200, """{"channel":"main","message_id":"u1","reply_to_message_id":"u1"}""".toByteArray(), end = true)
            vm.reply(sub.streamId, BodyChunk(
                ("""pend","payload":{"message_id":"assistant-msg-a1","text":"noon."},"seq":4,"type":"event"}
{"event":"delta.message_done","payload":{$ctx,"message_id":"assistant-msg-a1"},"seq":5,"type":"event"}
""").toByteArray()))

            assertEquals("It's noon.", turn.await())
            assertTrue(updates.contains("It's noon."))
            // The subscription is cancelled once the turn settles.
            val cancel = vm.next()
            assertEquals(sub.streamId, cancel.streamId)
            assertTrue(cancel.value is Reset)

            vm.control(control, buildJsonObject { put("event", "link.unpaired") })
            run.await()
        }
    }
}
