package com.meshchat.app.mesh.wifidirect

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * Wi-Fi Direct 组内帧编解码（纯 JVM，可单测）。
 * REGISTER 帧（UDP 广播，身份↔IP 映射）：'M''R' + shortIdLen(1B) + shortId + ipLen(1B) + ip + port(2B BE) + nameLen(1B) + name
 * 数据帧（UDP 广播，MeshFrame payload）：'M''D' + payloadLen(2B BE) + payload
 */
object WifiDirectFraming {
    private const val MAGIC_R = 'M'.code.toByte()
    private const val KIND_REGISTER = 'R'.code.toByte()
    private const val KIND_DATA = 'D'.code.toByte()
    const val MAX_PAYLOAD = 60 * 1024  // UDP 报文上限（65507B）留余量

    data class RegisterInfo(val shortId: String, val ip: String, val port: Int, val name: String)

    fun encodeRegister(shortId: String, ip: String, port: Int, name: String): ByteArray {
        val idB = shortId.toByteArray(StandardCharsets.UTF_8)
        val ipB = ip.toByteArray(StandardCharsets.UTF_8)
        val nameB = name.toByteArray(StandardCharsets.UTF_8)
        require(idB.size <= 255 && ipB.size <= 255 && nameB.size <= 255) { "field too long" }
        val buf = ByteBuffer.allocate(2 + 1 + idB.size + 1 + ipB.size + 2 + 1 + nameB.size)
        buf.put(MAGIC_R).put(KIND_REGISTER)
        buf.put(idB.size.toByte()).put(idB)
        buf.put(ipB.size.toByte()).put(ipB)
        buf.putShort(port.toShort())
        buf.put(nameB.size.toByte()).put(nameB)
        return buf.array()
    }

    fun decodeRegister(bytes: ByteArray): RegisterInfo? {
        if (bytes.size < 2 || bytes[0] != MAGIC_R || bytes[1] != KIND_REGISTER) return null
        var pos = 2
        fun takeLen(): Int? {
            if (pos >= bytes.size) return null
            return bytes[pos++].toInt() and 0xFF
        }
        val idLen = takeLen() ?: return null
        if (pos + idLen > bytes.size) return null
        val shortId = String(bytes, pos, idLen, StandardCharsets.UTF_8); pos += idLen
        val ipLen = takeLen() ?: return null
        if (pos + ipLen > bytes.size) return null
        val ip = String(bytes, pos, ipLen, StandardCharsets.UTF_8); pos += ipLen
        if (pos + 2 > bytes.size) return null
        val port = ByteBuffer.wrap(bytes, pos, 2).short.toInt() and 0xFFFF; pos += 2
        val nameLen = takeLen() ?: return null
        if (pos + nameLen > bytes.size) return null
        val name = String(bytes, pos, nameLen, StandardCharsets.UTF_8)
        return RegisterInfo(shortId, ip, port, name)
    }

    fun wrapData(payload: ByteArray): ByteArray {
        require(payload.size <= MAX_PAYLOAD) { "payload ${payload.size}B exceeds $MAX_PAYLOAD" }
        val buf = ByteBuffer.allocate(4 + payload.size)
        buf.put(MAGIC_R).put(KIND_DATA).putShort(payload.size.toShort())
        buf.put(payload)
        return buf.array()
    }

    fun unwrapData(bytes: ByteArray): ByteArray? {
        if (bytes.size < 4 || bytes[0] != MAGIC_R || bytes[1] != KIND_DATA) return null
        val len = ByteBuffer.wrap(bytes, 2, 2).short.toInt() and 0xFFFF
        if (len != bytes.size - 4) return null
        return bytes.copyOfRange(4, bytes.size)
    }
}
