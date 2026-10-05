package com.rusty.museecho.protocol

import com.rusty.museecho.noise.ApplicationRequest
import com.rusty.museecho.noise.ApplicationResponse
import com.rusty.museecho.noise.BodyChunk
import com.rusty.museecho.noise.CipherState
import com.rusty.museecho.noise.Envelope
import com.rusty.museecho.noise.FrameValue
import com.rusty.museecho.noise.NoiseFrameDecoder
import com.rusty.museecho.noise.NoiseFraming
import com.rusty.museecho.noise.NoiseXXResponder
import com.rusty.museecho.noise.ServiceFrame
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.JsonObject

/** An in-process stand-in for the Muse VM's Noise endpoint, driven step by step by a test. */
class FakeVm {
    val toVm = Channel<ByteArray>(Channel.UNLIMITED)
    val toDevice = Channel<ByteArray>(Channel.UNLIMITED)
    var connectedUrl = ""
    var connectedHeaders = emptyMap<String, String>()
    private lateinit var send: CipherState
    private lateinit var recv: CipherState
    private val decoder = NoiseFrameDecoder()
    private var chunkId = 1000L

    val connector = WsConnector { url, headers ->
        connectedUrl = url
        connectedHeaders = headers
        object : WsConnection {
            override suspend fun send(data: ByteArray) = toVm.send(data)
            override suspend fun receive(): ByteArray? = toDevice.receiveCatching().getOrNull()
            override fun close() {
                toDevice.close()
            }
        }
    }

    suspend fun handshake() {
        val responder = NoiseXXResponder()
        toDevice.send(responder.readMessage1AndWriteMessage2(toVm.receive()))
        responder.readMessage3(toVm.receive())
        val (s, r) = responder.split()
        send = s
        recv = r
    }

    /** The next device -> VM service frame. */
    suspend fun next(): ServiceFrame {
        while (true) {
            val plain = recv.decryptWithAd(ByteArray(0), toVm.receive())
            val whole = decoder.decode(plain) ?: continue
            return Envelope.decodeServiceFrame(Envelope.decodeServiceRequest(whole))
        }
    }

    suspend fun reply(streamId: Long, value: FrameValue) {
        val plain = Envelope.encodeServiceResponse(Envelope.encodeServiceFrame(ServiceFrame(streamId, value)))
        NoiseFraming.encodeFrames(plain, chunkId++).forEach { toDevice.send(send.encryptWithAd(ByteArray(0), it)) }
    }

    suspend fun respond(streamId: Long, status: Int, body: ByteArray, end: Boolean) =
        reply(streamId, ApplicationResponse(status = status, body = body, endBody = end))

    suspend fun control(streamId: Long, message: JsonObject) =
        reply(streamId, BodyChunk(LinkSession.encodeMessage(message)))

    /** Reads device frames until a complete control-stream message arrives. */
    suspend fun nextMessage(decoder: MessageDecoder): JsonObject {
        while (true) {
            val frame = next()
            val data = when (val v = frame.value) {
                is BodyChunk -> v.data
                is ApplicationRequest -> v.body
                else -> continue
            }
            decoder.feed(data).firstOrNull()?.let { return it }
        }
    }
}
