package com.rusty.museecho.noise

import com.rusty.museecho.protocol.Crypto

/** One protobuf frame on the Noise connection (port of `framing.py`). */
data class NoiseTransportFrame(
    val chunkId: Long = 0,
    val chunkIndex: Long = 0,
    val totalChunks: Long = 1,
    val payload: ByteArray = ByteArray(0),
)

object NoiseFraming {
    const val MAX_CHUNK_PAYLOAD = 65489
    const val MAX_PENDING_ASSEMBLIES = 16
    const val MAX_TOTAL_CHUNKS = 256
    const val MAX_ASSEMBLY_BYTES = 16 * 1024 * 1024
    const val ASSEMBLY_TTL_MS = 60_000L

    fun encode(frame: NoiseTransportFrame): ByteArray {
        var out = ByteArray(0)
        if (frame.chunkId != 0L) out += Proto.int64Field(1, frame.chunkId)
        if (frame.chunkIndex != 0L) out += Proto.uint32Field(2, frame.chunkIndex)
        if (frame.totalChunks != 0L) out += Proto.uint32Field(3, frame.totalChunks)
        if (frame.payload.isNotEmpty()) out += Proto.delimited(4, frame.payload)
        return out
    }

    fun decode(data: ByteArray): NoiseTransportFrame {
        var chunkId = 0L
        var chunkIndex = 0L
        var total = 1L
        var payload = ByteArray(0)
        val r = Proto.Reader(data)
        while (r.hasMore()) {
            val (field, wire) = r.key()
            when (field) {
                1 -> { r.expect(wire, Proto.VARINT, "NoiseTransportFrame.chunk_id"); chunkId = r.varint() }
                2 -> { r.expect(wire, Proto.VARINT, "NoiseTransportFrame.chunk_index"); chunkIndex = Proto.decodeUint32(r.varint()) }
                3 -> { r.expect(wire, Proto.VARINT, "NoiseTransportFrame.total_chunks"); total = Proto.decodeUint32(r.varint()) }
                4 -> { r.expect(wire, Proto.DELIMITED, "NoiseTransportFrame.payload"); payload = r.delimited() }
                else -> r.skip(wire)
            }
        }
        return NoiseTransportFrame(chunkId, chunkIndex, total, payload)
    }

    fun encodeFrames(data: ByteArray, chunkId: Long = Crypto.random.nextLong()): List<ByteArray> {
        val total = maxOf(1, (data.size + MAX_CHUNK_PAYLOAD - 1) / MAX_CHUNK_PAYLOAD)
        require(total <= MAX_TOTAL_CHUNKS) { "payload too large for noise framing (${data.size} bytes)" }
        if (data.isEmpty()) return listOf(encode(NoiseTransportFrame(chunkId, 0, 1, ByteArray(0))))
        return (0 until total).map { i ->
            val start = i * MAX_CHUNK_PAYLOAD
            encode(NoiseTransportFrame(chunkId, i.toLong(), total.toLong(),
                data.copyOfRange(start, minOf(start + MAX_CHUNK_PAYLOAD, data.size))))
        }
    }
}

/** Reassembles chunked Noise frames; poisoned (unusable) after any malformed frame. */
class NoiseFrameDecoder(private val now: () -> Long = System::currentTimeMillis) {
    private class Assembly(val total: Long, val created: Long) {
        val chunks = HashMap<Long, ByteArray>()
        var bytes = 0
    }

    private val pending = HashMap<Long, Assembly>()
    private var poisoned = false

    fun decode(frameBytes: ByteArray): ByteArray? {
        if (poisoned) throw IllegalStateException("NoiseFrameDecoder: poisoned after prior failure")
        try {
            val f = NoiseFraming.decode(frameBytes)
            require(f.totalChunks in 1..NoiseFraming.MAX_TOTAL_CHUNKS.toLong()) { "invalid totalChunks" }
            require(f.chunkIndex in 0 until f.totalChunks) { "chunkIndex out of range" }
            require(f.payload.size <= NoiseFraming.MAX_CHUNK_PAYLOAD) { "payload too large for noise frame" }
            val t = now()
            pending.entries.removeIf { t - it.value.created > NoiseFraming.ASSEMBLY_TTL_MS }

            val asm = pending[f.chunkId] ?: run {
                require(pending.size < NoiseFraming.MAX_PENDING_ASSEMBLIES) { "too many pending noise frame assemblies" }
                Assembly(f.totalChunks, t).also { pending[f.chunkId] = it }
            }
            if (asm.total != f.totalChunks) {
                pending.remove(f.chunkId)
                throw IllegalArgumentException("inconsistent totalChunks for chunkId")
            }
            if (f.chunkIndex in asm.chunks) {
                pending.remove(f.chunkId)
                throw IllegalArgumentException("duplicate chunkIndex ${f.chunkIndex}")
            }
            asm.bytes += f.payload.size
            if (asm.bytes > NoiseFraming.MAX_ASSEMBLY_BYTES) {
                pending.remove(f.chunkId)
                throw IllegalArgumentException("assembly exceeded byte budget")
            }
            asm.chunks[f.chunkIndex] = f.payload
            if (asm.chunks.size < asm.total) return null
            pending.remove(f.chunkId)
            val out = java.io.ByteArrayOutputStream()
            for (i in 0 until asm.total) out.write(asm.chunks.getValue(i))
            return out.toByteArray()
        } catch (e: Exception) {
            poisoned = true
            throw e
        }
    }
}

data class EncryptedFrames(val streamId: Long, val frames: List<ByteArray>)

/** A decrypted VM -> device frame: a response head, a body chunk, or a reset. */
data class DecryptedFrame(val streamId: Long, val value: FrameValue)

/**
 * HTTP-over-Noise multiplexing (port of `transport.py`). Requests get
 * sequential stream ids from 1; each encrypted WebSocket message carries one
 * protobuf transport frame.
 */
class NoiseTransport(
    private val send: CipherState,
    private val recv: CipherState,
    private val chunkIds: () -> Long = Crypto.random::nextLong,
) {
    private val decoder = NoiseFrameDecoder()
    private var nextStreamId = 1L
    private var dead = false

    private fun alive() {
        if (dead) throw NoiseProtocolError("NoiseTransport: dead after prior failure")
    }

    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (e: Exception) {
        dead = true
        throw e
    }

    @Synchronized
    fun encryptHttpRequest(
        verb: String,
        path: String,
        body: ByteArray = ByteArray(0),
        headers: List<Header> = emptyList(),
        endBody: Boolean = true,
        service: ServiceType = ServiceType.DAEMON,
    ): EncryptedFrames {
        alive()
        return guarded {
            val streamId = nextStreamId++
            val frame = ServiceFrame(streamId, ApplicationRequest(verb, path, headers, body, endBody))
            EncryptedFrames(streamId, encryptRequest(service, frame))
        }
    }

    fun startStreamRequest(verb: String, path: String, headers: List<Header> = emptyList()) =
        encryptHttpRequest(verb, path, ByteArray(0), headers, endBody = false)

    @Synchronized
    fun encryptBodyChunk(streamId: Long, data: ByteArray, endBody: Boolean = false): List<ByteArray> {
        alive()
        return guarded { encryptRequest(ServiceType.DAEMON, ServiceFrame(streamId, BodyChunk(data, endBody))) }
    }

    @Synchronized
    fun encryptReset(streamId: Long, reason: String = "", code: ResetCode = ResetCode.CANCELLED): List<ByteArray> {
        alive()
        return guarded { encryptRequest(ServiceType.DAEMON, ServiceFrame(streamId, Reset(code, reason))) }
    }

    /** Null while a chunked frame is still being reassembled. */
    @Synchronized
    fun decryptFrame(ciphertext: ByteArray): DecryptedFrame? {
        alive()
        return guarded {
            val plain = recv.decryptWithAd(ByteArray(0), ciphertext)
            val reassembled = decoder.decode(plain) ?: return@guarded null
            val payload = Envelope.decodeServiceResponse(reassembled)
            if (payload.isEmpty()) throw NoiseProtocolError("empty ServiceResponse payload")
            val frame = Envelope.decodeServiceFrame(payload)
            when (val v = frame.value) {
                is ApplicationResponse, is BodyChunk, is Reset -> DecryptedFrame(frame.streamId, v)
                is ApplicationRequest -> throw NoiseProtocolError("NoiseTransport: unexpected request frame from server")
                null -> null
            }
        }
    }

    private fun encryptRequest(service: ServiceType, frame: ServiceFrame): List<ByteArray> {
        val request = Envelope.encodeServiceRequest(service, Envelope.encodeServiceFrame(frame))
        return NoiseFraming.encodeFrames(request, chunkIds()).map { send.encryptWithAd(ByteArray(0), it) }
    }
}
