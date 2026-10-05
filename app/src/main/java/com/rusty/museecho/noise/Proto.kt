package com.rusty.museecho.noise

import java.io.ByteArrayOutputStream

/** Minimal protobuf wire helpers for the Hatch Noise envelopes (port of `_proto.py`). */
class ProtoError(message: String) : IllegalArgumentException(message)

internal object Proto {
    const val VARINT = 0
    const val FIXED64 = 1
    const val DELIMITED = 2
    const val FIXED32 = 5

    private fun validField(n: Long) = n != 0L && n <= (1L shl 29) - 1 && n !in 19000L..19999L

    /** Encode [value] as an unsigned 64-bit varint (negative Longs are their two's complement). */
    fun varint(value: Long): ByteArray {
        val out = ByteArrayOutputStream()
        var v = value
        while (true) {
            val b = (v and 0x7F).toInt()
            v = v ushr 7
            if (v != 0L) out.write(b or 0x80) else {
                out.write(b)
                return out.toByteArray()
            }
        }
    }

    fun key(field: Int, wire: Int): ByteArray = varint(((field shl 3) or wire).toLong())

    fun varintField(field: Int, value: Long) = key(field, VARINT) + varint(value)

    fun int64Field(field: Int, value: Long) = varintField(field, value)

    /** int32 is sign-extended to 64 bits on the wire. */
    fun int32Field(field: Int, value: Int) = varintField(field, value.toLong())

    fun uint32Field(field: Int, value: Long): ByteArray {
        if (value < 0 || value > 0xFFFFFFFFL) throw ProtoError("uint32 value out of range")
        return varintField(field, value)
    }

    fun boolField(field: Int, value: Boolean) = varintField(field, if (value) 1 else 0)

    fun delimited(field: Int, payload: ByteArray) = key(field, DELIMITED) + varint(payload.size.toLong()) + payload

    fun string(field: Int, value: String) = delimited(field, value.toByteArray(Charsets.UTF_8))

    /** Reads protobuf fields from a byte array. */
    class Reader(private val data: ByteArray) {
        var offset = 0
            private set

        fun hasMore() = offset < data.size

        fun varint(): Long {
            var value = 0L
            var shift = 0
            for (i in 0 until 10) {
                if (offset >= data.size) throw ProtoError("truncated varint")
                val b = data[offset++].toInt() and 0xFF
                if (i == 9 && (b and 0xFE) != 0) throw ProtoError("malformed varint")
                value = value or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return value
                shift += 7
            }
            throw ProtoError("malformed varint")
        }

        /** Returns (field number, wire type). */
        fun key(): Pair<Int, Int> {
            val k = varint()
            val field = k ushr 3
            val wire = (k and 7).toInt()
            if (!validField(field)) throw ProtoError("invalid field number")
            if (wire !in setOf(VARINT, FIXED64, DELIMITED, FIXED32)) throw ProtoError("invalid wire type")
            return field.toInt() to wire
        }

        fun delimited(): ByteArray {
            val len = varint()
            if (len < 0 || offset + len > data.size) throw ProtoError("truncated delimited field")
            val out = data.copyOfRange(offset, offset + len.toInt())
            offset += len.toInt()
            return out
        }

        fun string(): String {
            val raw = delimited()
            val decoder = Charsets.UTF_8.newDecoder()
            return try {
                decoder.decode(java.nio.ByteBuffer.wrap(raw)).toString()
            } catch (e: java.nio.charset.CharacterCodingException) {
                throw ProtoError("invalid utf-8 string")
            }
        }

        fun skip(wire: Int) {
            when (wire) {
                VARINT -> varint()
                FIXED64 -> advance(8, "fixed64")
                DELIMITED -> delimited()
                FIXED32 -> advance(4, "fixed32")
                else -> throw ProtoError("invalid wire type")
            }
        }

        private fun advance(n: Int, what: String) {
            if (offset + n > data.size) throw ProtoError("truncated $what field")
            offset += n
        }

        fun expect(actual: Int, expected: Int, what: String) {
            if (actual != expected) throw ProtoError("$what wrong wire type")
        }
    }

    fun decodeInt32(raw: Long): Int {
        if (raw < Int.MIN_VALUE || raw > Int.MAX_VALUE) throw ProtoError("int32 value out of range")
        return raw.toInt()
    }

    fun decodeUint32(raw: Long): Long {
        if (raw < 0 || raw > 0xFFFFFFFFL) throw ProtoError("uint32 value out of range")
        return raw
    }
}
