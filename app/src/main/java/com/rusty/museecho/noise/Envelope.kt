package com.rusty.museecho.noise

/**
 * Hatch Noise service envelopes (port of `envelope.py`): HTTP-shaped requests,
 * responses, body chunks and resets multiplexed by stream id, wrapped in a
 * ServiceRequest (device -> VM) or ServiceResponse (VM -> device).
 */
data class Header(val key: String = "", val value: String = "")

enum class ServiceType(val code: Int) { DAEMON(0), SENTINEL(1), VAULT(2), AUTHD(3) }

enum class ResetCode(val code: Int) {
    UNSPECIFIED(0), CANCELLED(1), TIMEOUT(2), PROTOCOL_ERROR(3),
    REFUSED_STREAM(4), INTERNAL_ERROR(5), SERVICE_UNAVAILABLE(6);

    companion object {
        fun of(code: Int) = entries.firstOrNull { it.code == code } ?: throw ProtoError("unknown reset code")
    }
}

sealed class FrameValue

data class ApplicationRequest(
    val verb: String = "",
    val path: String = "",
    val headers: List<Header> = emptyList(),
    val body: ByteArray = ByteArray(0),
    val endBody: Boolean = false,
) : FrameValue()

data class ApplicationResponse(
    val status: Int = 0,
    val headers: List<Header> = emptyList(),
    val body: ByteArray = ByteArray(0),
    val endBody: Boolean = false,
) : FrameValue()

data class BodyChunk(val data: ByteArray = ByteArray(0), val endBody: Boolean = false) : FrameValue()

data class Reset(val code: ResetCode = ResetCode.UNSPECIFIED, val reason: String = "") : FrameValue()

data class ServiceFrame(val streamId: Long = 0, val value: FrameValue? = null)

object Envelope {
    fun encodeHeader(h: Header): ByteArray {
        var out = ByteArray(0)
        if (h.key.isNotEmpty()) out += Proto.string(1, h.key)
        if (h.value.isNotEmpty()) out += Proto.string(2, h.value)
        return out
    }

    fun decodeHeader(data: ByteArray): Header {
        var key = ""
        var value = ""
        val r = Proto.Reader(data)
        while (r.hasMore()) {
            val (field, wire) = r.key()
            when (field) {
                1 -> { r.expect(wire, Proto.DELIMITED, "Header.key"); key = r.string() }
                2 -> { r.expect(wire, Proto.DELIMITED, "Header.value"); value = r.string() }
                else -> r.skip(wire)
            }
        }
        return Header(key, value)
    }

    fun encodeRequest(req: ApplicationRequest): ByteArray {
        var out = ByteArray(0)
        if (req.verb.isNotEmpty()) out += Proto.string(1, req.verb)
        if (req.path.isNotEmpty()) out += Proto.string(2, req.path)
        req.headers.forEach { out += Proto.delimited(3, encodeHeader(it)) }
        if (req.body.isNotEmpty()) out += Proto.delimited(4, req.body)
        if (req.endBody) out += Proto.boolField(5, true)
        return out
    }

    fun decodeRequest(data: ByteArray): ApplicationRequest {
        var verb = ""
        var path = ""
        val headers = mutableListOf<Header>()
        var body = ByteArray(0)
        var endBody = false
        val r = Proto.Reader(data)
        while (r.hasMore()) {
            val (field, wire) = r.key()
            when (field) {
                1 -> { r.expect(wire, Proto.DELIMITED, "ApplicationRequest.verb"); verb = r.string() }
                2 -> { r.expect(wire, Proto.DELIMITED, "ApplicationRequest.path"); path = r.string() }
                3 -> { r.expect(wire, Proto.DELIMITED, "ApplicationRequest.headers"); headers += decodeHeader(r.delimited()) }
                4 -> { r.expect(wire, Proto.DELIMITED, "ApplicationRequest.body"); body = r.delimited() }
                5 -> { r.expect(wire, Proto.VARINT, "ApplicationRequest.end_body"); endBody = r.varint() != 0L }
                else -> r.skip(wire)
            }
        }
        return ApplicationRequest(verb, path, headers, body, endBody)
    }

    fun encodeResponse(resp: ApplicationResponse): ByteArray {
        var out = ByteArray(0)
        if (resp.status != 0) out += Proto.int32Field(1, resp.status)
        resp.headers.forEach { out += Proto.delimited(2, encodeHeader(it)) }
        if (resp.body.isNotEmpty()) out += Proto.delimited(3, resp.body)
        if (resp.endBody) out += Proto.boolField(4, true)
        return out
    }

    fun decodeResponse(data: ByteArray): ApplicationResponse {
        var status = 0
        val headers = mutableListOf<Header>()
        var body = ByteArray(0)
        var endBody = false
        val r = Proto.Reader(data)
        while (r.hasMore()) {
            val (field, wire) = r.key()
            when (field) {
                1 -> { r.expect(wire, Proto.VARINT, "ApplicationResponse.status"); status = Proto.decodeInt32(r.varint()) }
                2 -> { r.expect(wire, Proto.DELIMITED, "ApplicationResponse.headers"); headers += decodeHeader(r.delimited()) }
                3 -> { r.expect(wire, Proto.DELIMITED, "ApplicationResponse.body"); body = r.delimited() }
                4 -> { r.expect(wire, Proto.VARINT, "ApplicationResponse.end_body"); endBody = r.varint() != 0L }
                else -> r.skip(wire)
            }
        }
        return ApplicationResponse(status, headers, body, endBody)
    }

    fun encodeBodyChunk(chunk: BodyChunk): ByteArray {
        var out = ByteArray(0)
        if (chunk.data.isNotEmpty()) out += Proto.delimited(1, chunk.data)
        if (chunk.endBody) out += Proto.boolField(2, true)
        return out
    }

    fun decodeBodyChunk(data: ByteArray): BodyChunk {
        var bytes = ByteArray(0)
        var endBody = false
        val r = Proto.Reader(data)
        while (r.hasMore()) {
            val (field, wire) = r.key()
            when (field) {
                1 -> { r.expect(wire, Proto.DELIMITED, "BodyChunk.data"); bytes = r.delimited() }
                2 -> { r.expect(wire, Proto.VARINT, "BodyChunk.end_body"); endBody = r.varint() != 0L }
                else -> r.skip(wire)
            }
        }
        return BodyChunk(bytes, endBody)
    }

    fun encodeReset(reset: Reset): ByteArray {
        var out = ByteArray(0)
        if (reset.code != ResetCode.UNSPECIFIED) out += Proto.int32Field(1, reset.code.code)
        if (reset.reason.isNotEmpty()) out += Proto.string(2, reset.reason)
        return out
    }

    fun decodeReset(data: ByteArray): Reset {
        var code = ResetCode.UNSPECIFIED
        var reason = ""
        val r = Proto.Reader(data)
        while (r.hasMore()) {
            val (field, wire) = r.key()
            when (field) {
                1 -> { r.expect(wire, Proto.VARINT, "Reset.code"); code = ResetCode.of(Proto.decodeInt32(r.varint())) }
                2 -> { r.expect(wire, Proto.DELIMITED, "Reset.reason"); reason = r.string() }
                else -> r.skip(wire)
            }
        }
        return Reset(code, reason)
    }

    fun encodeServiceFrame(frame: ServiceFrame): ByteArray {
        var out = ByteArray(0)
        if (frame.streamId != 0L) out += Proto.int64Field(1, frame.streamId)
        out += when (val v = frame.value) {
            null -> ByteArray(0)
            is ApplicationRequest -> Proto.delimited(2, encodeRequest(v))
            is ApplicationResponse -> Proto.delimited(3, encodeResponse(v))
            is BodyChunk -> Proto.delimited(4, encodeBodyChunk(v))
            is Reset -> Proto.delimited(5, encodeReset(v))
        }
        return out
    }

    fun decodeServiceFrame(data: ByteArray): ServiceFrame {
        var streamId = 0L
        var value: FrameValue? = null
        val r = Proto.Reader(data)
        while (r.hasMore()) {
            val (field, wire) = r.key()
            when (field) {
                1 -> { r.expect(wire, Proto.VARINT, "ServiceFrame.stream_id"); streamId = r.varint() }
                2 -> { r.expect(wire, Proto.DELIMITED, "ServiceFrame.request"); value = decodeRequest(r.delimited()) }
                3 -> { r.expect(wire, Proto.DELIMITED, "ServiceFrame.response"); value = decodeResponse(r.delimited()) }
                4 -> { r.expect(wire, Proto.DELIMITED, "ServiceFrame.body_chunk"); value = decodeBodyChunk(r.delimited()) }
                5 -> { r.expect(wire, Proto.DELIMITED, "ServiceFrame.reset"); value = decodeReset(r.delimited()) }
                else -> r.skip(wire)
            }
        }
        return ServiceFrame(streamId, value)
    }

    fun encodeServiceRequest(service: ServiceType, payload: ByteArray): ByteArray {
        var out = ByteArray(0)
        if (service != ServiceType.DAEMON) out += Proto.varintField(1, service.code.toLong())
        if (payload.isNotEmpty()) out += Proto.delimited(2, payload)
        return out
    }

    /** Returns the ServiceRequest payload (the service type is not needed on this side). */
    fun decodeServiceRequest(data: ByteArray): ByteArray {
        var payload = ByteArray(0)
        val r = Proto.Reader(data)
        while (r.hasMore()) {
            val (field, wire) = r.key()
            when (field) {
                1 -> { r.expect(wire, Proto.VARINT, "ServiceRequest.service"); r.varint() }
                2 -> { r.expect(wire, Proto.DELIMITED, "ServiceRequest.payload"); payload = r.delimited() }
                else -> r.skip(wire)
            }
        }
        return payload
    }

    fun encodeServiceResponse(payload: ByteArray): ByteArray =
        if (payload.isEmpty()) ByteArray(0) else Proto.delimited(1, payload)

    fun decodeServiceResponse(data: ByteArray): ByteArray {
        var payload = ByteArray(0)
        val r = Proto.Reader(data)
        while (r.hasMore()) {
            val (field, wire) = r.key()
            when (field) {
                1 -> { r.expect(wire, Proto.DELIMITED, "ServiceResponse.payload"); payload = r.delimited() }
                else -> r.skip(wire)
            }
        }
        return payload
    }
}
