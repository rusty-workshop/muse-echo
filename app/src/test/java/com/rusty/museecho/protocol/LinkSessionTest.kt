package com.rusty.museecho.protocol

import com.rusty.museecho.noise.ApplicationRequest
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkSessionTest {
    private val commands = buildJsonObject {
        putJsonObject("echo.test") { put("description", "test") }
    }

    @Test
    fun registersServesInvokesChatsAndUnpairs() = runBlocking {
        withTimeout(15_000) {
            val vm = FakeVm()
            val session = LinkSession(
                noiseHost = "vm.example", vmId = "vm 1/ü", vmAuthToken = "tok",
                device = DeviceDescription("homelink-abcdef", "Echo Show", "0.1.0", commands),
                runCommand = { command, params, _ ->
                    LinkSession.commandOk(buildJsonObject {
                        put("command", command)
                        put("x", params["x"]!!)
                    })
                },
                connector = vm.connector,
                log = {},
            )
            val run = async { session.run() }
            vm.handshake()
            assertEquals("wss://vm.example/v1/noise?vm_id=vm%201%2F%C3%BC", vm.connectedUrl)
            assertEquals("Bearer tok", vm.connectedHeaders["Authorization"])

            val open = vm.next()
            val req = open.value as ApplicationRequest
            assertEquals("/link-control", req.path)
            assertEquals(false, req.endBody)
            val control = open.streamId

            val decoder = MessageDecoder()
            val register = vm.nextMessage(decoder)
            assertEquals("link.register", register.str("method"))
            val params = register["params"]!!.jsonObject
            assertEquals("homelink-abcdef", params.str("node_id"))
            assertEquals(commands, params["commands_v2"])

            vm.respond(control, 200, LinkSession.encodeMessage(buildJsonObject {
                put("id", register.str("id"))
                putJsonObject("result") {}
            }), end = false)
            while (session.registeredAt == null) delay(10)

            vm.control(control, buildJsonObject {
                put("method", "link.invoke")
                put("id", "inv-1")
                put("command", "echo.test")
                putJsonObject("params") { put("x", 7) }
            })
            val result = vm.nextMessage(decoder)
            assertEquals("link.result", result.str("method"))
            assertEquals("inv-1", result.str("id"))
            assertEquals("true", result["ok"]!!.jsonPrimitive.content)
            assertEquals("7", result["payload"]!!.jsonObject["x"]!!.jsonPrimitive.content)

            val chat = async { session.sendChat("hello muse") }
            val chatOpen = vm.next()
            val chatReq = chatOpen.value as ApplicationRequest
            assertEquals("/chat/stream", chatReq.path)
            assertTrue(chatReq.endBody)
            val body = Json.parseToJsonElement(String(chatReq.body)).jsonObject
            assertEquals("hello muse", body.str("message"))
            assertEquals("homelink-abcdef", body.str("device_id"))
            vm.respond(chatOpen.streamId, 200, """{"result":{"message_id":"m1"}}""".toByteArray(), end = true)
            val chatResult = chat.await()
            assertEquals("true", chatResult["ok"]!!.jsonPrimitive.content)

            vm.control(control, buildJsonObject { put("event", "link.unpaired") })
            assertEquals(LinkSession.Outcome.UNPAIRED, run.await())
        }
    }

    @Test
    fun rejectedUpgradeMapsToOutcome() = runBlocking {
        val session = LinkSession("h", "v", "t", DeviceDescription("n", "d", "1", JsonObject(emptyMap())),
            { _, _, _ -> JsonObject(emptyMap()) }, { _, _ -> throw UpgradeRejected(401) }, log = {})
        assertEquals(LinkSession.Outcome.AUTH_REJECTED, session.run())
        val forbidden = LinkSession("h", "v", "t", DeviceDescription("n", "d", "1", JsonObject(emptyMap())),
            { _, _, _ -> JsonObject(emptyMap()) }, { _, _ -> throw UpgradeRejected(403) }, log = {})
        assertEquals(LinkSession.Outcome.FORBIDDEN, forbidden.run())
    }

    @Test
    fun controlRefusalEndsTheSession() = runBlocking {
        withTimeout(10_000) {
            val vm = FakeVm()
            val session = LinkSession("h", "v", "t", DeviceDescription("n", "d", "1", JsonObject(emptyMap())),
                { _, _, _ -> JsonObject(emptyMap()) }, vm.connector, log = {})
            val run = async { session.run() }
            vm.handshake()
            val control = vm.next().streamId
            vm.respond(control, 403, ByteArray(0), end = true)
            assertEquals(LinkSession.Outcome.FORBIDDEN, run.await())
        }
    }
}
