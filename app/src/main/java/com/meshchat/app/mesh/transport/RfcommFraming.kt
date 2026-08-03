package com.meshchat.app.mesh.transport

import com.meshchat.app.mesh.protocol.MeshFrame
import java.io.InputStream
import java.io.OutputStream

/** 长度前缀分帧工具：4 字节大端长度 + 帧字节。流式 RFCOMM 必须自定帧边界。 */
object RfcommFraming {
    const val MAX_FRAME_BYTES = 1024 * 1024

    fun writeFrame(out: OutputStream, frame: MeshFrame) {
        val bytes = frame.encode()
        out.write(byteArrayOf(
            (bytes.size ushr 24).toByte(), (bytes.size ushr 16).toByte(),
            (bytes.size ushr 8).toByte(), bytes.size.toByte(),
        ))
        out.write(bytes)
        out.flush()
    }

    /** 读一帧；流已结束/损坏返回 null。 */
    fun readFrame(input: InputStream): MeshFrame? {
        val lenBytes = ByteArray(4)
        if (readFully(input, lenBytes) != 4) return null
        val len = (lenBytes[0].toInt() and 0xFF shl 24) or (lenBytes[1].toInt() and 0xFF shl 16) or
            (lenBytes[2].toInt() and 0xFF shl 8) or (lenBytes[3].toInt() and 0xFF)
        if (len <= 0 || len > MAX_FRAME_BYTES) return null
        val payload = ByteArray(len)
        if (readFully(input, payload) != len) return null
        return runCatching { MeshFrame.decode(payload) }.getOrNull()
    }

    /** 循环读满 buf；返回实际读到的字节数（流结束时可能 < buf.size）。 */
    fun readFully(input: InputStream, buf: ByteArray): Int {
        var total = 0
        while (total < buf.size) {
            val n = input.read(buf, total, buf.size - total)
            if (n < 0) break
            total += n
        }
        return total
    }
}
