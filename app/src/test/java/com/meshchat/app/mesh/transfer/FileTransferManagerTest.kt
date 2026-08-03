package com.meshchat.app.mesh.transfer

import com.meshchat.app.mesh.protocol.FileAckBody
import com.meshchat.app.mesh.protocol.FileBody
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
    }

    private class FakeSaver(private val dir: File) : FileSaver {
        override fun save(tmpFile: File, fileName: String, mime: String): String {
            val target = File(dir, fileName)
            tmpFile.copyTo(target, overwrite = true)
            return target.absolutePath
        }
    }

    /** 从广播帧里取出 FILE 块体。 */
    private fun fileChunks(frames: List<MeshFrame>): List<FileBody> =
        frames.mapNotNull { frame ->
            val env = runCatching { MeshJson.decodeEnvelope(frame.payloadText) }.getOrNull()
            env?.body as? FileBody
        }

    /** 从广播帧里取出 FILE_ACK 体。 */
    private fun ackBodies(frames: List<MeshFrame>): List<FileAckBody> =
        frames.mapNotNull { frame ->
            val env = runCatching { MeshJson.decodeEnvelope(frame.payloadText) }.getOrNull()
            env?.body as? FileAckBody
        }

    @Test
    fun `first window sends 32 chunks then retries only missing chunk and completes`() = runTest {
        val transport = CountingTransport()
        val dir = kotlin.io.path.createTempDirectory("mesh").toFile()
        val manager = FileTransferManager(
            transport = transport, shortId = "A", saver = FakeSaver(dir),
            scope = backgroundScope, windowTimeoutMs = 5_000, maxWindowRetries = 3,
        )
        val bytes = ByteArray(FileTransferManager.CHUNK_BYTES * 33) { it.toByte() }   // 33 块 → 2 个窗口
        val fileId = manager.sendFile(
            convId = "conv-B", dstId = "B",
            openSource = { ByteArrayInputStream(bytes) },
            fileName = "data.bin", mime = "application/octet-stream", size = bytes.size.toLong(),
        )!!
        assertTrue(fileId.isNotBlank())

        // 等首窗 32 块发出
        val firstWindow = awaitChunks(transport, 32)
        assertEquals(0, firstWindow.first().chunkIndex)
        assertEquals(31, firstWindow.last().chunkIndex)

        // 回 ACK：缺第 3 块
        manager.onFileAck(ack(fileId, 33, listOf(3)))
        val retried = awaitChunks(transport, 1)
        assertEquals(3, retried.first().chunkIndex)

        // 窗口完成 → 回 ACK 空推进第二窗（块 32）
        manager.onFileAck(ack(fileId, 33, emptyList()))
        val secondWindow = awaitChunks(transport, 1)
        assertEquals(32, secondWindow.first().chunkIndex)
        // 第二窗完成 → 再回 ACK 空 → 全部收齐
        manager.onFileAck(ack(fileId, 33, emptyList()))

        awaitDone(manager)
        assertEquals(TransferStatus.DONE, manager.progress.value?.status)
        assertEquals(33L * FileTransferManager.CHUNK_BYTES, manager.progress.value?.transferredBytes)
    }

    @Test
    fun `window timeout retransmits whole window`() = runTest {
        val transport = CountingTransport()
        val manager = FileTransferManager(
            transport = transport, shortId = "A", saver = FakeSaver(kotlin.io.path.createTempDirectory("m2").toFile()),
            scope = backgroundScope, windowTimeoutMs = 100, maxWindowRetries = 3,
        )
        val bytes = ByteArray(FileTransferManager.CHUNK_BYTES * 32) { 1 }
        manager.sendFile(
            convId = "conv-B", dstId = "B",
            openSource = { ByteArrayInputStream(bytes) },
            fileName = "b.bin", mime = "application/octet-stream", size = bytes.size.toLong(),
        )
        val first = awaitChunks(transport, 32)
        // 不回 ACK → 整窗重发
        val resent = awaitChunks(transport, 32)
        assertTrue(resent.all { c -> first.any { it.chunkIndex == c.chunkIndex } })
        // 补 ACK 完成
        manager.onFileAck(ack(first.first().fileId, 32, emptyList()))
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
    fun `file chunk frame fits BLE single-frame budget`() {
        // 帧必须 < MTU 512 的可用载荷（509B）并留余量：
        // 生产路径已截断 fileName(16 字符)/mime(30 字符)，此处模拟截断后的最坏输入 + 60B 块
        val body = FileBody(
            fileId = "f-12345678-1234-1234-1234-123456789012", fileName = "项目周报-第三季度-终版.pdf",
            mime = "application/vnd.openxmlformats-officedoc",
            size = 100000, totalChunks = 1700, chunkIndex = 0,
            chunkData = Base64.getEncoder().encodeToString(ByteArray(FileTransferManager.CHUNK_BYTES) { 1 }),
        )
        val env = MeshEnvelope(
            id = "e-12345678-1234-1234-1234-123456789012", kind = "FILE",
            srcId = "AB12", dstId = "CD34", convId = "conv-CD34", ttl = 8, ts = 1234567890, body = body,
        )
        val size = MeshJson.encodeEnvelope(env).toByteArray().size
        println("DIAG frame bytes=$size budget=470")
        assertTrue("帧 ${size}B 超 BLE 单帧预算 470B", size <= 470)
    }

    private fun envelope(fileId: String, body: FileBody) = MeshEnvelope(
        id = "env-$fileId", kind = "FILE", srcId = "A", dstId = "B",
        convId = "conv-A", ts = 1, body = body,
    )

    private fun ack(fileId: String, total: Int, missing: List<Int>) = MeshEnvelope(
        id = "ack-${fileId}", kind = "FILE_ACK", srcId = "B", dstId = "A",
        convId = "conv-A", ts = 1, body = FileAckBody(fileId, total, missing),
    )

    private suspend fun awaitChunks(t: CountingTransport, n: Int): List<FileBody> {
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
