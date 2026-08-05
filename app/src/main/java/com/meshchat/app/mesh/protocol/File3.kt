package com.meshchat.app.mesh.protocol

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * FILE3 二进制文件帧（v1.1.28）：文件传输极限优化——纯二进制载荷，无 base64/JSON 膨胀。
 * 对比 FILE2（JSON 信封 + base64 块，数据占比 24%），FILE3 数据占比 ~95%（480/505）。
 *
 * 帧类型：
 *  - CHUNK：数据块帧（25B 头 + 原始字节），走 GATT 无确认写，丢帧由窗口重传兜底
 *  - START：元数据帧（文件名/mime/原始大小/压缩标志/总块数），每窗口重发一次保证可靠（幂等）
 *
 * 字节布局（全部大端）：
 *  公共头：magic "MC3"(3B) + ver(1B) + kind(1B) + srcIdLen(1B) + srcId + fidLen(1B) + fid
 *  CHUNK：公共头 + seq(4B) + len(2B) + data
 *  START：公共头 + totalChunks(4B) + origSize(8B) + compressed(1B) + nameLen(2B) + name + mimeLen(2B) + mime
 *
 * 文件不参与多跳中继（点对点一跳，见 MeshService），帧内带 srcId/fid 即可寻址，
 * 无需 JSON 信封的 dstId/convId/ttl/ts。
 */
object File3 {
    const val MAGIC = "MC3"
    const val VER = 0x01

    const val KIND_CHUNK = 0x01
    const val KIND_START = 0x02

    /** 数据块字节（v1.1.28）：二进制无 base64 膨胀，CHUNK 帧 25B 头 + 480B = 505B ≤ MTU 512 可用载荷 509B。 */
    const val CHUNK_BYTES = 480

    /** START 帧元数据预算（BLE 单帧硬限制）：文件名 UTF-8 最大字节、mime 最大字节（超长截断防御）。 */
    const val MAX_NAME_BYTES = 400
    const val MAX_MIME_BYTES = 80

    /** 文件小于该阈值不压缩：deflate 对小文件反而膨胀，压缩开销不值得。 */
    const val COMPRESS_MIN_BYTES = 1024L

    data class Start(
        val srcId: String,
        val fid: String,
        val totalChunks: Int,
        val origSize: Long,
        val compressed: Boolean,
        val name: String,
        val mime: String,
    )

    data class Chunk(
        val srcId: String,
        val fid: String,
        val seq: Int,
        val data: ByteArray,
    )

    sealed class Frame {
        data class StartFrame(val start: Start) : Frame()
        data class ChunkFrame(val chunk: Chunk) : Frame()
    }

    fun encodeStart(srcId: String, fid: String, totalChunks: Int, origSize: Long, compressed: Boolean, name: String, mime: String): ByteArray {
        var nameBytes = name.toByteArray(StandardCharsets.UTF_8)
        var mimeBytes = mime.toByteArray(StandardCharsets.UTF_8)
        // BLE 单帧硬限制（≤509B）：超长文件名/mime 按字节截断防御（极限情况下文件头仍可送达）
        if (nameBytes.size > MAX_NAME_BYTES) nameBytes = nameBytes.copyOfRange(0, MAX_NAME_BYTES)
        if (mimeBytes.size > MAX_MIME_BYTES) mimeBytes = mimeBytes.copyOfRange(0, MAX_MIME_BYTES)
        val srcBytes = srcId.toByteArray(StandardCharsets.UTF_8)
        val fidBytes = fid.toByteArray(StandardCharsets.UTF_8)
        // 固定头 24B：magic3+ver1+kind1+srcLen1+fidLen1+total4+orig8+comp1+nameLen2+mimeLen2
        val buf = ByteBuffer.allocate(24 + srcBytes.size + fidBytes.size + nameBytes.size + mimeBytes.size)
        buf.put(MAGIC.toByteArray(StandardCharsets.US_ASCII))
        buf.put(VER.toByte())
        buf.put(KIND_START.toByte())
        buf.put(srcBytes.size.toByte())
        buf.put(srcBytes)
        buf.put(fidBytes.size.toByte())
        buf.put(fidBytes)
        buf.putInt(totalChunks)
        buf.putLong(origSize)
        buf.put(if (compressed) 1 else 0)
        buf.putShort(nameBytes.size.toShort())
        buf.put(nameBytes)
        buf.putShort(mimeBytes.size.toShort())
        buf.put(mimeBytes)
        return buf.array()
    }

    fun encodeChunk(srcId: String, fid: String, seq: Int, data: ByteArray): ByteArray {
        require(data.size <= CHUNK_BYTES) { "chunk ${data.size}B exceeds $CHUNK_BYTES" }
        val srcBytes = srcId.toByteArray(StandardCharsets.UTF_8)
        val fidBytes = fid.toByteArray(StandardCharsets.UTF_8)
        // 固定头 13B：magic3+ver1+kind1+srcLen1+fidLen1+seq4+len2
        val buf = ByteBuffer.allocate(13 + srcBytes.size + fidBytes.size + data.size)
        buf.put(MAGIC.toByteArray(StandardCharsets.US_ASCII))
        buf.put(VER.toByte())
        buf.put(KIND_CHUNK.toByte())
        buf.put(srcBytes.size.toByte())
        buf.put(srcBytes)
        buf.put(fidBytes.size.toByte())
        buf.put(fidBytes)
        buf.putInt(seq)
        buf.putShort(data.size.toShort())
        buf.put(data)
        return buf.array()
    }

    /** 魔数校验：是否为 FILE3 二进制帧（MeshService.handleFrame 旁路 JSON 解析用）。 */
    fun isFile3(payload: ByteArray): Boolean =
        payload.size >= 3 && payload[0] == 'M'.code.toByte() &&
            payload[1] == 'C'.code.toByte() && payload[2] == '3'.code.toByte()

    /** 解析帧；非法/版本不符返回 null（老版本不识别 MC3 帧，在 handleFrame JSON 解析失败处自然丢弃）。 */
    fun parse(payload: ByteArray): Frame? {
        if (!isFile3(payload)) return null
        if (payload.size < 6) return null
        if (payload[3] != VER.toByte()) return null
        val kind = payload[4].toInt()
        var pos = 5
        val srcLen = payload[pos].toInt() and 0xFF; pos++
        if (pos + srcLen > payload.size) return null
        val srcId = String(payload, pos, srcLen, StandardCharsets.UTF_8); pos += srcLen
        if (pos >= payload.size) return null
        val fidLen = payload[pos].toInt() and 0xFF; pos++
        if (pos + fidLen > payload.size) return null
        val fid = String(payload, pos, fidLen, StandardCharsets.UTF_8); pos += fidLen
        return when (kind) {
            KIND_CHUNK -> {
                if (pos + 6 > payload.size) return null
                val seq = ByteBuffer.wrap(payload, pos, 4).int; pos += 4
                val len = ByteBuffer.wrap(payload, pos, 2).short.toInt() and 0xFFFF; pos += 2
                if (len > CHUNK_BYTES || pos + len > payload.size) return null
                val data = ByteArray(len)
                System.arraycopy(payload, pos, data, 0, len)
                Frame.ChunkFrame(Chunk(srcId, fid, seq, data))
            }
            KIND_START -> {
                if (pos + 13 > payload.size) return null
                val totalChunks = ByteBuffer.wrap(payload, pos, 4).int; pos += 4
                val origSize = ByteBuffer.wrap(payload, pos, 8).long; pos += 8
                val compressed = payload[pos].toInt() != 0; pos++
                if (pos + 2 > payload.size) return null
                val nameLen = ByteBuffer.wrap(payload, pos, 2).short.toInt() and 0xFFFF; pos += 2
                if (pos + nameLen > payload.size) return null
                val name = String(payload, pos, nameLen, StandardCharsets.UTF_8); pos += nameLen
                if (pos + 2 > payload.size) return null
                val mimeLen = ByteBuffer.wrap(payload, pos, 2).short.toInt() and 0xFFFF; pos += 2
                if (pos + mimeLen > payload.size) return null
                val mime = String(payload, pos, mimeLen, StandardCharsets.UTF_8)
                Frame.StartFrame(Start(srcId, fid, totalChunks, origSize, compressed, name, mime))
            }
            else -> null
        }
    }
}
