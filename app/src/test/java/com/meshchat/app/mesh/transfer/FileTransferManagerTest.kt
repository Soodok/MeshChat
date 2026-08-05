package com.meshchat.app.mesh.transfer

import com.meshchat.app.mesh.protocol.FileAckBody
import com.meshchat.app.mesh.protocol.FileBody
import com.meshchat.app.mesh.protocol.FileBodyV2
import com.meshchat.app.mesh.protocol.FrameType
import com.meshchat.app.mesh.protocol.MeshEnvelope
import com.meshchat.app.mesh.protocol.MeshFrame
import com.meshchat.app.mesh.protocol.MeshJson
import com.meshchat.app.mesh.transport.InMemoryTransport
import com.meshchat.app.mesh.transport.MeshTransport
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Base64
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FileTransferManagerTest {

    private class CountingTransport : MeshTransport {
        private val inner = InMemoryTransport()
        var frames = mutableListOf<MeshFrame>()
        override val incoming = inner.incoming
        override val foundPeers = inner.foundPeers
        override fun start() = inner.start()
        override fun stop() = inner.stop()
        override fun broadcast(frame: MeshFrame) {
            frames.add(frame)
            inner.broadcast(frame)
        }
        override fun sendTo(peerId: String, frame: MeshFrame) = inner.sendTo(peerId, frame)
        /** 文件数据块走无确认写（v1.1.27）：测试替身 = 普通广播（记录 + 回环）。 */
        override fun writeUnreliable(frame: MeshFrame) {
            frames.add(frame)
            inner.broadcast(frame)
        }
    }

    private class FakeSaver(private val dir: File) : FileSaver {
        override fun save(tmpFile: File, fileName: String, mime: String): String {
            val target = File(dir, fileName)
            tmpFile.copyTo(target, overwrite = true)
            return target.absolutePath
        }
    }

    /** 块视图（统一 FILE 单块 / FILE2 多块）。 */
    private data class ChunkView(val fileId: String, val chunkIndex: Int)

    /** 从广播帧里取出 FILE/FILE2 块。 */
    private fun fileChunks(frames: List<MeshFrame>): List<ChunkView> =
        frames.mapNotNull { frame ->
            val env = runCatching { MeshJson.decodeEnvelope(frame.payloadText) }.getOrNull() ?: return@mapNotNull null
            when (val b = env.body) {
                is FileBody -> listOf(ChunkView(b.fileId, b.chunkIndex))
                is FileBodyV2 -> b.chunks.indices.map { ChunkView(b.fid, b.start + it) }
                else -> null
            }
        }.flatten()

    /** 从广播帧里取出 FILE_ACK 体。 */
    private fun ackBodies(frames: List<MeshFrame>): List<FileAckBody> =
        frames.mapNotNull { frame ->
            val env = runCatching { MeshJson.decodeEnvelope(frame.payloadText) }.getOrNull()
            env?.body as? FileAckBody
        }

    @Test
    fun `first window sends 8 chunks then retries only missing chunk and completes`() = runTest {
        val transport = CountingTransport()
        val dir = kotlin.io.path.createTempDirectory("mesh").toFile()
        val manager = FileTransferManager(
            transport = transport, shortId = "A", saver = FakeSaver(dir),
            scope = backgroundScope, windowTimeoutMs = 5_000, maxWindowRetries = 3,
        )
        val w = FileTransferManager.WINDOW
        val bytes = ByteArray(FileTransferManager.CHUNK_BYTES * 33) { it.toByte() }   // 33 块 → 5 窗口
        val fileId = manager.sendFile(
            convId = "conv-B", dstId = "B",
            openSource = { ByteArrayInputStream(bytes) },
            fileName = "data.bin", mime = "application/octet-stream", size = bytes.size.toLong(),
        )!!
        assertTrue(fileId.isNotBlank())

        // 等首窗 w 块发出
        val firstWindow = awaitChunks(transport, w)
        assertEquals(0, firstWindow.first().chunkIndex)
        assertEquals(w - 1, firstWindow.last().chunkIndex)

        // 回 ACK：缺第 3 块 → 仅重发第 3 块
        manager.onFileAck(ack(fileId, 33, listOf(3)))
        val retried = awaitChunks(transport, 1)
        assertEquals(3, retried.first().chunkIndex)

        // 连续回 empty ACK 推进剩余窗口直至完成
        var guard = 0
        while (manager.progress.value?.status != TransferStatus.DONE && guard++ < 60) {
            manager.onFileAck(ack(fileId, 33, emptyList()))
            kotlinx.coroutines.delay(20)
        }
        assertEquals(TransferStatus.DONE, manager.progress.value?.status)
        assertEquals(33L * FileTransferManager.CHUNK_BYTES, manager.progress.value?.transferredBytes)
    }

    @Test
    fun `window timeout retransmits whole window`() = runTest {
        val transport = CountingTransport()
        val manager = FileTransferManager(
            transport = transport, shortId = "A", saver = FakeSaver(kotlin.io.path.createTempDirectory("m2").toFile()),
            scope = backgroundScope, windowTimeoutMs = 500, maxWindowRetries = 3,
        )
        val bytes = ByteArray(FileTransferManager.CHUNK_BYTES * 32) { 1 }
        manager.sendFile(
            convId = "conv-B", dstId = "B",
            openSource = { ByteArrayInputStream(bytes) },
            fileName = "b.bin", mime = "application/octet-stream", size = bytes.size.toLong(),
        )
        val w = FileTransferManager.WINDOW
        val first = awaitChunks(transport, w)
        // 不回 ACK → 整窗重发
        val resent = awaitChunks(transport, w)
        assertTrue(resent.all { c -> first.any { it.chunkIndex == c.chunkIndex } })
        // 循环补 empty ACK 推进全部窗口（32 块 / 窗口 8 = 4 窗口）
        var guard = 0
        while (manager.progress.value?.status != TransferStatus.DONE && guard++ < 30) {
            manager.onFileAck(ack(first.first().fileId, 32, emptyList()))
            kotlinx.coroutines.delay(20)
        }
        awaitDone(manager)
        assertEquals(TransferStatus.DONE, manager.progress.value?.status)
    }

    @Test
    fun `fails after max window retries without ack`() = runTest {
        val transport = CountingTransport()
        val manager = FileTransferManager(
            transport = transport, shortId = "A", saver = FakeSaver(kotlin.io.path.createTempDirectory("m3").toFile()),
            scope = backgroundScope, windowTimeoutMs = 50, maxWindowRetries = 2,
        )
        val bytes = ByteArray(FileTransferManager.CHUNK_BYTES * 8) { 2 }
        manager.sendFile(
            convId = "conv-B", dstId = "B",
            openSource = { ByteArrayInputStream(bytes) },
            fileName = "c.bin", mime = "application/octet-stream", size = bytes.size.toLong(),
        )
        awaitChunks(transport, 8)   // 首窗
        awaitDone(manager)
        assertEquals(TransferStatus.FAILED, manager.progress.value?.status)
    }

    @Test
    fun `rejects concurrent send while transferring`() = runTest {
        val transport = CountingTransport()
        val manager = FileTransferManager(
            transport = transport, shortId = "A", saver = FakeSaver(kotlin.io.path.createTempDirectory("m4").toFile()),
            scope = backgroundScope, windowTimeoutMs = 200, maxWindowRetries = 2,
        )
        val bytes = ByteArray(FileTransferManager.CHUNK_BYTES * 4) { 3 }
        val id1 = manager.sendFile(
            convId = "conv-B", dstId = "B",
            openSource = { ByteArrayInputStream(bytes) },
            fileName = "d.bin", mime = "application/octet-stream", size = bytes.size.toLong(),
        )
        awaitChunks(transport, 4)
        val id2 = manager.sendFile(
            convId = "conv-B", dstId = "B",
            openSource = { ByteArrayInputStream(bytes) },
            fileName = "e.bin", mime = "application/octet-stream", size = bytes.size.toLong(),
        )
        assertNull(id2)   // 串行：传输中拒绝
        manager.onFileAck(ack(id1!!, 4, emptyList()))
        awaitDone(manager)
        assertEquals(TransferStatus.DONE, manager.progress.value?.status)
    }

    @Test
    fun `receives out-of-order and duplicate chunks then assembles full file`() = runTest {
        val transport = CountingTransport()
        val dir = kotlin.io.path.createTempDirectory("m5").toFile()
        val manager = FileTransferManager(
            transport = transport, shortId = "B", saver = FakeSaver(dir),
            scope = backgroundScope, windowTimeoutMs = 5_000, maxWindowRetries = 3,
        )
        val source = ByteArray(FileTransferManager.CHUNK_BYTES * 10) { (it % 251).toByte() }
        val fileId = "recv-1"
        fun chunk(index: Int) = FileBody(
            fileId = fileId, fileName = "recv.bin", mime = "application/octet-stream",
            size = source.size.toLong(), totalChunks = 10, chunkIndex = index,
            chunkData = Base64.getEncoder().encodeToString(
                source.copyOfRange(index * FileTransferManager.CHUNK_BYTES, (index + 1) * FileTransferManager.CHUNK_BYTES),
            ),
        )
        // 乱序 + 重复投递
        for (i in listOf(5, 3, 9, 3, 0, 7, 2, 6, 8, 1, 4)) {
            manager.onFileChunk(envelope(fileId, chunk(i)))
        }
        // 收齐后回最终 ACK（missing 空）且落盘
        assertTrue(ackBodies(transport.frames).any { it.missing.isEmpty() })
        val saved = File(dir, "recv.bin")
        assertTrue(saved.exists())
        assertEquals(source.toList(), saved.readBytes().toList())
        assertEquals(TransferStatus.DONE, manager.progress.value?.status)
        assertEquals(source.size.toLong(), manager.progress.value?.transferredBytes)
    }

    @Test
    fun `file2 frame with two chunks fits BLE single-frame budget`() {
        // v1.1.27 多块合并帧：CHUNKS_PER_FRAME 块 + FILE2 短字段头，必须 < MTU 512 可用载荷（509B）
        val body = FileBodyV2(
            fid = "f-12345678-1234-1234-1234-123456789012", n = "项目周报-第三季度-终版.pdf",
            m = "application/vnd.openxmlformats-officedoc",
            sz = 100000, tot = 1700, start = 0,
            chunks = List(FileTransferManager.CHUNKS_PER_FRAME) {
                Base64.getEncoder().encodeToString(ByteArray(FileTransferManager.CHUNK_BYTES) { 1 })
            },
        )
        val env = MeshEnvelope(
            id = "f123456789012-0", kind = "FILE2",
            srcId = "AB12", dstId = "CD34", convId = "conv-CD34", ttl = 8, ts = 1234567890, body = body,
        )
        val size = MeshJson.encodeEnvelope(env).toByteArray().size
        println("DIAG FILE2 frame bytes=$size budget=509")
        assertTrue("FILE2 帧 ${size}B 超 MTU 512 可用载荷 509B", size <= 509)
    }

    @Test
    fun `file chunk frame fits BLE single-frame budget`() {
        // 老 FILE 格式（v1.1.27 起仅接收老版本设备帧，本机发送已改 FILE2）：老版本块大小 90B → 整帧 ~505B
        val body = FileBody(
            fileId = "f-12345678-1234-1234-1234-123456789012", fileName = "项目周报-第三季度-终版.pdf",
            mime = "application/vnd.openxmlformats-officedoc",
            size = 100000, totalChunks = 1700, chunkIndex = 0,
            chunkData = Base64.getEncoder().encodeToString(ByteArray(90) { 1 }),   // 老版本 v1.1.26 块 90B
        )
        val env = MeshEnvelope(
            id = "e-12345678-1234-1234-1234-123456789012", kind = "FILE",
            srcId = "AB12", dstId = "CD34", convId = "conv-CD34", ttl = 8, ts = 1234567890, body = body,
        )
        val size = MeshJson.encodeEnvelope(env).toByteArray().size
        println("DIAG FILE(old) frame bytes=$size budget=509")
        assertTrue("老 FILE 帧 ${size}B 超 MTU 512 可用载荷 509B", size <= 509)
    }

    @Test
    fun `file ack frame stays under BLE budget for large file`() = runTest {
        // 大文件（1000 块）接收端回 ACK 时 missing 是全文件缺失列表 → 帧会随文件膨胀超 MTU，发送端收不到确认
        val transport = CountingTransport()
        val manager = FileTransferManager(
            transport = transport, shortId = "B", saver = FakeSaver(kotlin.io.path.createTempDirectory("ack").toFile()),
            scope = backgroundScope, windowTimeoutMs = 5_000, maxWindowRetries = 3,
        )
        val fileId = "big-1"
        val total = 1000
        fun chunk(index: Int) = FileBody(
            fileId = fileId, fileName = "big.md", mime = "text/markdown",
            size = 50000L, totalChunks = total, chunkIndex = index,
            chunkData = Base64.getEncoder().encodeToString(ByteArray(FileTransferManager.CHUNK_BYTES) { 1 }),
        )
        // 收 32 块（每满一窗口 8 块回 ACK → 共 4 次）
        for (i in 0 until 32) manager.onFileChunk(envelope(fileId, chunk(i)))
        val ackFrame = transport.frames.lastOrNull { frame ->
            val env = runCatching { MeshJson.decodeEnvelope(frame.payloadText) }.getOrNull()
            env?.body is FileAckBody
        }
        assertTrue("应回 ACK 帧", ackFrame != null)
        val ackBytes = ackFrame!!.payload.size
        println("DIAG ack bytes=$ackBytes budget=470")
        assertTrue("ACK 帧 ${ackBytes}B 超 BLE 单帧预算 470B", ackBytes <= 470)
    }

    @Test
    fun `end to end 100-chunk file over 3 windows with truncated acks`() = runTest {
        // 真实联动：A 广播 → B 收块回 ACK（截断 40 项）→ A 推进窗口，验证大文件完整传输
        val transport = InMemoryTransport()
        val dirB = kotlin.io.path.createTempDirectory("e2e").toFile()
        val a = FileTransferManager(
            transport = transport, shortId = "A", saver = FakeSaver(kotlin.io.path.createTempDirectory("e2eA").toFile()),
            scope = backgroundScope, windowTimeoutMs = 5_000, maxWindowRetries = 5,
        )
        val b = FileTransferManager(
            transport = transport, shortId = "B", saver = FakeSaver(dirB),
            scope = backgroundScope, windowTimeoutMs = 5_000, maxWindowRetries = 5,
        )
        val bytes = ByteArray(FileTransferManager.CHUNK_BYTES * 100) { (it % 97).toByte() }  // 100 块 → 4 窗口
        val relay = backgroundScope.launch {
            transport.incoming.collect { frame ->
                val env = runCatching { MeshJson.decodeEnvelope(frame.payloadText) }.getOrNull() ?: return@collect
                when (env.body) {
                    is FileBody, is FileBodyV2 -> if (env.dstId == "B") b.onFileChunk(env)
                    is FileAckBody -> if (env.dstId == "A") a.onFileAck(env)
                    else -> Unit
                }
            }
        }
        a.sendFile("conv-B", "B", { ByteArrayInputStream(bytes) }, "big.bin", "application/octet-stream", bytes.size.toLong())
        awaitDone(a)
        relay.cancel()
        assertEquals(TransferStatus.DONE, a.progress.value?.status)
        val saved = File(dirB, "big.bin")
        assertTrue("B 应落盘完整文件", saved.exists())
        assertEquals("文件字节一致", bytes.toList(), saved.readBytes().toList())
    }

    @Test
    fun `file chunks go through writeUnreliable not injected sendFrame`() = runTest {
        val transport = CountingTransport()
        val sentVia = mutableListOf<Pair<String, MeshFrame>>()
        val manager = FileTransferManager(
            transport = transport, shortId = "A", saver = FakeSaver(kotlin.io.path.createTempDirectory("sf").toFile()),
            scope = backgroundScope, windowTimeoutMs = 5_000, maxWindowRetries = 3,
            sendFrame = { dst, frame -> sentVia.add(dst to frame) },
        )
        val bytes = ByteArray(FileTransferManager.CHUNK_BYTES * 8) { 5 }
        val fileId = manager.sendFile("conv-B", "B", { ByteArrayInputStream(bytes) }, "f.bin", "application/octet-stream", bytes.size.toLong())!!
        // 数据块走 writeUnreliable（CountingTransport 记录到 frames）：8 块 = CHUNKS_PER_FRAME(2) × 4 帧
        var guard = 0
        while (fileChunks(transport.frames).size < 8 && guard++ < 100) kotlinx.coroutines.delay(20)
        assertTrue("首窗 8 块应经 writeUnreliable 到达", fileChunks(transport.frames).size >= 8)
        assertEquals("数据块不得走注入 sendFrame（仅 ACK 通道）", 0, sentVia.size)
        // 全部窗口完成后停发
        while (manager.progress.value?.status != TransferStatus.DONE && guard++ < 200) {
            manager.onFileAck(ack(fileId, 8, emptyList()))
            kotlinx.coroutines.delay(20)
        }
        assertEquals(TransferStatus.DONE, manager.progress.value?.status)
    }

    private fun envelope(fileId: String, body: FileBody) = MeshEnvelope(
        id = "env-$fileId", kind = "FILE", srcId = "A", dstId = "B",
        convId = "conv-A", ts = 1, body = body,
    )

    private fun ack(fileId: String, total: Int, missing: List<Int>) = MeshEnvelope(
        id = "ack-${fileId}", kind = "FILE_ACK", srcId = "B", dstId = "A",
        convId = "conv-A", ts = 1, body = FileAckBody(fileId, total, missing),
    )

    private suspend fun awaitChunks(t: CountingTransport, n: Int): List<ChunkView> {
        val before = t.frames.size
        var guard = 0
        while (true) {
            val chunks = fileChunks(t.frames.drop(before))
            if (chunks.size >= n) return chunks.takeLast(n)
            if (guard++ > 500) return chunks
            kotlinx.coroutines.delay(20)
        }
    }

    private suspend fun awaitDone(manager: FileTransferManager) {
        var guard = 0
        while (manager.progress.value?.status == null || manager.progress.value!!.status == TransferStatus.RUNNING) {
            if (guard++ > 500) break
            kotlinx.coroutines.delay(20)
        }
    }
}
