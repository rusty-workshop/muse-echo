package com.rusty.museecho.gadget

import com.rusty.museecho.noise.ApplicationRequest
import com.rusty.museecho.noise.BodyChunk
import com.rusty.museecho.protocol.DeviceDescription
import com.rusty.museecho.protocol.FakeVm
import com.rusty.museecho.protocol.LinkSession
import com.rusty.museecho.protocol.MessageDecoder
import com.rusty.museecho.protocol.str
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
            // A chat-only connection: handshake, then straight to requests (no /link-control, no register).
            val chat = ChatClient("homelink-abcdef")
            var opened = 0
            val chatJob = launch {
                chat.runChat {
                    if (opened++ == 0) {
                        LinkSession("h", "v", "t", null, { _, _, _ -> JsonObject(emptyMap()) }, vm.connector, log = {})
                    } else {
                        null
                    }
                }
            }
            vm.handshake()
            val sub = vm.next()
            assertEquals("/chat/subscribe", (sub.value as ApplicationRequest).path)
            vm.respond(sub.streamId, 200, ByteArray(0), end = false)
            while (!chat.feedOpen) delay(10)

            val updates = mutableListOf<String>()
            val wav = ByteArray(100) { 1 }
            val turn = async { chat.turn(null, wav) { _, r -> r?.let { updates += it.text } } }

            val post = vm.next()
            val postReq = post.value as ApplicationRequest
            assertEquals("/chat/stream", postReq.path)
            val body = Json.parseToJsonElement(String(postReq.body)).jsonObject
            val item = (body["items"] as JsonArray)[0].jsonObject
            assertEquals("audio/wav", item.str("mime_type"))
            assertEquals(java.util.Base64.getEncoder().encodeToString(wav), item.str("data_base64"))

            val ctx = """"chat_context":{"device_id":"homelink-abcdef"}"""
            // Reply events, one line split across two frames, around the ack.
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

            assertEquals("It's noon.", turn.await()!!.text)
            assertTrue(updates.contains("It's noon."))
            chatJob.cancel()

        }
    }
}
