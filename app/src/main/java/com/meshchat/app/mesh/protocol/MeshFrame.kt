package com.meshchat.app.mesh.protocol

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

enum class FrameType(val code: Byte) {
    HELLO(0x01), DATA(0x02), ACK(0x03), RECEIPT(0x04), PING(0x05);

    companion object {
        fun fromCode(code: Byte): FrameType? = entries.firstOrNull { it.code == code }
    }
}

data class MeshFrame(val type: FrameType, val payload: ByteArray) {
    val payloadText: String get() = String(payload, StandardCharsets.UTF_8)

    override fun equals(other: Any?): Boolean =
        other is MeshFrame && other.type == type && payload.contentEquals(other.payload)

    override fun hashCode(): Int = 31 * type.hashCode() + payload.contentHashCode()

    fun encode(): ByteArray = ByteBuffer.allocate(HEADER_SIZE + payload.size)
        .put(type.code)
        .putShort(payload.size.toShort())
        .put(payload)
        .array()

    companion object {
        const val HEADER_SIZE = 3

        fun decode(bytes: ByteArray): MeshFrame {
            require(bytes.size >= HEADER_SIZE) { "frame too short" }
            val buffer = ByteBuffer.wrap(bytes)
            val type = FrameType.fromCode(buffer.get())
                ?: throw IllegalArgumentException("unknown frame type")
            val length = buffer.short.toInt() and 0xFFFF
            require(length == bytes.size - HEADER_SIZE) { "length mismatch" }
            val payload = ByteArray(length)
            buffer.get(payload)
            return MeshFrame(type, payload)
        }
    }
}
