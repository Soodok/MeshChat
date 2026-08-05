package com.meshchat.app.mesh.transfer

import com.meshchat.app.mesh.protocol.File3
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
import java.util.Random
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FileTransferManagerTest {

    /** 不可压缩随机数据：发送端压缩回退原文件，块数 = ceil(size / File3.CHUNK_BYTES)，测试断言稳定。 */
    private val rng = Random(42)
    private fun randomBytes(n: Int): ByteArray = ByteArray(n).also { rng.nextBytes(it) }

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

    /** 从广播帧里取出 FILE/FILE2/FILE3 块。 */
    private fun fileChunks(frames: List<MeshFrame>): List<ChunkView> =
        frames.mapNotNull { frame ->
            // v1.1.28 FILE3 二进制帧：直接解析（无 JSON 信封）
            if (File3.isFile3(frame.payload)) {
                val parsed = File3.parse(frame.payload) as? File3.Frame.ChunkFrame ?: return@mapNotNull null
                return@mapNotNull listOf(ChunkView(parsed.chunk.fid, parsed.chunk.seq))
            }
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
        val total = 33
        val bytes = randomBytes(File3.CHUNK_BYTES * total)   // 33 块 → 5 窗口（不可压缩 → 不压缩原样传输）
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
        manager.onFileAck(ack(fileId, total, listOf(3)))
        val retried = awaitChunks(transport, 1)
        assertEquals(3, retried.first().chunkIndex)

        // 连续回 empty ACK 推进剩余窗口直至完成
        var guard = 0
        while (manager.progress.value?.status != TransferStatus.DONE && guard++ < 60) {
            manager.onFileAck(ack(fileId, total, emptyList()))
            kotlinx.coroutines.delay(20)
        }
        assertEquals(TransferStatus.DONE, manager.progress.value?.status)
        assertEquals(total.toLong() * File3.CHUNK_BYTES, manager.progress.value?.transferredBytes)
    }

    @Test
    fun `window timeout retransmits whole window`() = runTest {
        val transport = CountingTransport()
        val manager = FileTransferManager(
            transport = transport, shortId = "A", saver = FakeSaver(kotlin.io.path.createTempDirectory("m2").toFile()),
            scope = backgroundScope, windowTimeoutMs = 500, maxWindowRetries = 3,
        )
        val bytes = randomBytes(File3.CHUNK_BYTES * 32)
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
        val bytes = randomBytes(File3.CHUNK_BYTES * 8)
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
        val bytes = randomBytes(File3.CHUNK_BYTES * 4)
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
        val bytes = randomBytes(File3.CHUNK_BYTES * 100)  // 100 块 → 13 窗口
        val relay = backgroundScope.launch {
            transport.incoming.collect { frame ->
                // v1.1.28 FILE3 二进制帧（无 JSON 信封）：直接转发给 B；ACK 仍为 JSON
                if (File3.isFile3(frame.payload)) {
                    when (File3.parse(frame.payload)) {
                        is File3.Frame.ChunkFrame, is File3.Frame.StartFrame -> b.onFile3Frame(frame.payload)
                        null -> Unit
                    }
                } else {
                    val env = runCatching { MeshJson.decodeEnvelope(frame.payloadText) }.getOrNull() ?: return@collect
                    when (env.body) {
                        is FileAckBody -> if (env.dstId == "A") a.onFileAck(env)
                        else -> Unit
                    }
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
    fun `file chunks go through broadcast not injected sendFrame`() = runTest {
        val transport = CountingTransport()
        val sentVia = mutableListOf<Pair<String, MeshFrame>>()
        val manager = FileTransferManager(
            transport = transport, shortId = "A", saver = FakeSaver(kotlin.io.path.createTempDirectory("sf").toFile()),
            scope = backgroundScope, windowTimeoutMs = 5_000, maxWindowRetries = 3,
            sendFrame = { dst, frame -> sentVia.add(dst to frame) },
        )
        val bytes = randomBytes(File3.CHUNK_BYTES * 8)
        val fileId = manager.sendFile("conv-B", "B", { ByteArrayInputStream(bytes) }, "f.bin", "application/octet-stream", bytes.size.toLong())!!
        // 数据块走 broadcast（CountingTransport 记录到 frames，v1.1.31 起无确认写改确认写 broadcast）：8 块 + START 帧
        var guard = 0
        while (fileChunks(transport.frames).size < 8 && guard++ < 100) kotlinx.coroutines.delay(20)
        assertTrue("首窗 8 块应经 broadcast 到达", fileChunks(transport.frames).size >= 8)
        assertTrue("START 元数据帧应经 broadcast 发出", transport.frames.any {
            File3.isFile3(it.payload) && File3.parse(it.payload) is File3.Frame.StartFrame
        })
        assertEquals("数据块不得走注入 sendFrame（仅 ACK 通道）", 0, sentVia.size)
        // 全部窗口完成后停发
        while (manager.progress.value?.status != TransferStatus.DONE && guard++ < 200) {
            manager.onFileAck(ack(fileId, 8, emptyList()))
            kotlinx.coroutines.delay(20)
        }
        assertEquals(TransferStatus.DONE, manager.progress.value?.status)
    }

    @Test
    fun `single-use source stream still transfers - v1-1-28 0-block root cause`() = runTest {
        // v1.1.28 真机"一块都没有成功"回归：ContentResolver 流某些 provider 只能消费一次，
        // 旧 prepareData 在压缩回退分支（图片/视频不可压缩必走）二次调用 openSource → 抛异常 → 一帧不发 FAILED。
        // 修复：openSource 只调用一次。此测试精确复现该场景（image/jpeg + 单次流 + 不可压缩）。
        val transport = CountingTransport()
        val manager = FileTransferManager(
            transport = transport, shortId = "A", saver = FakeSaver(kotlin.io.path.createTempDirectory("su").toFile()),
            scope = backgroundScope, windowTimeoutMs = 5_000, maxWindowRetries = 3,
        )
        var opened = 0
        val bytes = randomBytes(File3.CHUNK_BYTES * 8)
        val fileId = manager.sendFile(
            convId = "conv-B", dstId = "B",
            openSource = {
                opened++
                if (opened > 1) throw IllegalStateException("stream already consumed (ContentResolver single-use)")
                ByteArrayInputStream(bytes)
            },
            fileName = "photo.jpg", mime = "image/jpeg", size = bytes.size.toLong(),
        )!!
        var guard = 0
        while (fileChunks(transport.frames).size < 8 && guard++ < 100) kotlinx.coroutines.delay(20)
        assertEquals("openSource 应只被调用一次", 1, opened)
        assertTrue("首窗 8 块应发出（openSource 单次 + 压缩回退原样传输）", fileChunks(transport.frames).size >= 8)
        while (manager.progress.value?.status != TransferStatus.DONE && guard++ < 200) {
            manager.onFileAck(ack(fileId, 8, emptyList()))
            kotlinx.coroutines.delay(20)
        }
        assertEquals(TransferStatus.DONE, manager.progress.value?.status)
    }

    /** 前 dropFrames 帧全部丢弃（模拟链路卡顿期：START+整窗多次重发全被吞），之后正常转发——验证窗口重试/退避收敛而非过早 FAILED。 */
    private class DropUntilTransport(
        private val inner: InMemoryTransport,
        private val dropFrames: Int,
    ) : MeshTransport {
        private var dropped = 0
        override val incoming = inner.incoming
        override val foundPeers = inner.foundPeers
        override fun start() = inner.start()
        override fun stop() = inner.stop()
        override fun sendTo(peerId: String, frame: MeshFrame) = inner.sendTo(peerId, frame)
        override fun broadcast(frame: MeshFrame) {
            if (dropped < dropFrames) { dropped++; return }   // 卡顿期静默丢弃
            inner.broadcast(frame)
        }
    }

    @Test
    fun `mid-transfer stall recovers via retries instead of failing`() = runTest {
        // v1.1.37 用户"文件过大或中途卡顿直接传失败"回归：旧 MAX_WINDOW_RETRIES=5，卡顿 >5s（≥6 次窗口超时）即 FAILED。
        // 此处丢弃 54 帧（≈6 轮 START+8 块整窗重发），单窗口需重试 6 次才通过——旧上限 5 必失败，新上限 12 收敛 DONE。
        val inner = InMemoryTransport()
        val dirB = kotlin.io.path.createTempDirectory("stallB").toFile()
        val a = FileTransferManager(
            transport = DropUntilTransport(inner, 54), shortId = "A",
            saver = FakeSaver(kotlin.io.path.createTempDirectory("stallA").toFile()),
            scope = backgroundScope, windowTimeoutMs = 200, maxWindowRetries = 12,
        )
        val b = FileTransferManager(
            transport = inner, shortId = "B", saver = FakeSaver(dirB),
            scope = backgroundScope, windowTimeoutMs = 200, maxWindowRetries = 12,
        )
        val relay = backgroundScope.launch {
            inner.incoming.collect { frame ->
                if (File3.isFile3(frame.payload)) {
                    when (File3.parse(frame.payload)) {
                        is File3.Frame.ChunkFrame, is File3.Frame.StartFrame -> b.onFile3Frame(frame.payload)
                        null -> Unit
                    }
                } else {
                    val env = runCatching { MeshJson.decodeEnvelope(frame.payloadText) }.getOrNull() ?: return@collect
                    if (env.body is FileAckBody && env.dstId == "A") a.onFileAck(env)
                }
            }
        }
        val bytes = randomBytes(File3.CHUNK_BYTES * 16)   // 16 块 = 2 窗口
        a.sendFile("conv-B", "B", { ByteArrayInputStream(bytes) }, "stall.bin", "application/octet-stream", bytes.size.toLong())
        awaitDone(a)
        relay.cancel()
        assertEquals("卡顿期重试后应收敛而非 FAILED", TransferStatus.DONE, a.progress.value?.status)
        val saved = File(dirB, "stall.bin")
        assertTrue("B 应落盘完整文件", saved.exists())
        assertEquals("文件字节一致", bytes.toList(), saved.readBytes().toList())
    }

    /** 模拟对端 GATT 连接已断开：isConnectedTo 恒 false——v1.1.38 发送端无上限重试的停止条件。 */
    private class DisconnectedTransport(private val inner: InMemoryTransport) : MeshTransport {
        override val incoming = inner.incoming
        override val foundPeers = inner.foundPeers
        override fun start() = inner.start()
        override fun stop() = inner.stop()
        override fun sendTo(peerId: String, frame: MeshFrame) = inner.sendTo(peerId, frame)
        override fun broadcast(frame: MeshFrame) = inner.broadcast(frame)
        override fun isConnectedTo(peerId: String): Boolean = false
    }

    @Test
    fun `sender stops when link disconnects instead of retrying forever`() = runTest {
        // v1.1.38 用户"重连次数直接无上限，除非断开连接"：连接存活时无限重试（零容错），
        // 链路断开（isConnectedTo=false）则立即停止 FAILED，绝不无限硬撑。
        val manager = FileTransferManager(
            transport = DisconnectedTransport(InMemoryTransport()), shortId = "A",
            saver = FakeSaver(kotlin.io.path.createTempDirectory("discA").toFile()),
            scope = backgroundScope, windowTimeoutMs = 200, maxWindowRetries = Int.MAX_VALUE,   // 生产无上限
        )
        val bytes = randomBytes(File3.CHUNK_BYTES * 8)
        manager.sendFile("conv-B", "B", { ByteArrayInputStream(bytes) }, "disc.bin", "application/octet-stream", bytes.size.toLong())
        awaitDone(manager)
        assertEquals("链路断开应立即 FAILED 而非无限重试", TransferStatus.FAILED, manager.progress.value?.status)
    }

    @Test
    fun `duplicate chunks after completion do not reset receiver progress`() = runTest {
        // v1.1.40 用户"传太快发送方显示已发送、接收方进度条没走满还在发送中、实际已送达"：收齐落盘后发送方
        // 仍可能补发/重发（最终 ACK 丢失 → v1.1.38 无上限重发），旧逻辑 getOrPut 重建幽灵会话（totalChunks=0）
        // 把进度覆盖回 RUNNING 0%。修复：completedFiles 拦截 + 回最终 ACK 让发送方收敛。
        val manager = FileTransferManager(
            transport = CountingTransport(), shortId = "B",
            saver = FakeSaver(kotlin.io.path.createTempDirectory("dupB").toFile()),
            scope = backgroundScope, windowTimeoutMs = 200, maxWindowRetries = 5,
        )
        val fid = "12345678-1234-1234-1234-123456789012"
        val chunkBytes = File3.CHUNK_BYTES
        val bytes = randomBytes(chunkBytes * 8)
        // 完整收齐：START + 8 块
        manager.onFile3Frame(File3.encodeStart("A", fid, 8, bytes.size.toLong(), false, "dup.bin", "application/octet-stream"))
        for (i in 0 until 8) {
            manager.onFile3Frame(
                File3.encodeChunk("A", fid, i, i.toLong() * chunkBytes, bytes.copyOfRange(i * chunkBytes, (i + 1) * chunkBytes)),
            )
        }
        assertEquals("收齐应 DONE", TransferStatus.DONE, manager.progress.value?.status)
        // 发送方最终 ACK 丢失 → 整窗重发 START + 块 → 重复帧到达
        manager.onFile3Frame(File3.encodeStart("A", fid, 8, bytes.size.toLong(), false, "dup.bin", "application/octet-stream"))
        manager.onFile3Frame(File3.encodeChunk("A", fid, 0, 0L, bytes.copyOfRange(0, chunkBytes)))
        manager.onFile3Frame(File3.encodeChunk("A", fid, 7, 7L * chunkBytes, bytes.copyOfRange(7 * chunkBytes, 8 * chunkBytes)))
        assertEquals("补发重复帧不得重置进度", TransferStatus.DONE, manager.progress.value?.status)
        assertEquals("进度保持 100%", 100, manager.progress.value!!.transferredBytes * 100 / manager.progress.value!!.totalBytes)
    }

    @Test
    fun `chunk before start does not ack empty missing prematurely`() = runTest {
        // v1.1.48 幽灵会话（START 首播丢失只剩块）：totalChunks=0 时 missing=空，旧逻辑 ackCounter%WINDOW==0 即回 ACK
        // → 发送端误判"全部收到"提前 DONE、START 永远补不来 → 文件静默丢失。修复：元数据未齐（metaReady=false）不回 ACK。
        val transport = CountingTransport()
        val manager = FileTransferManager(
            transport = transport, shortId = "B",
            saver = FakeSaver(kotlin.io.path.createTempDirectory("ghostB").toFile()),
            scope = backgroundScope, windowTimeoutMs = 200, maxWindowRetries = 5,
        )
        val fid = "12345678-1234-1234-1234-123456789013"
        val data = randomBytes(File3.CHUNK_BYTES)
        // 只有 CHUNK、无 START（START 首播被 BLE 吞帧场景）
        manager.onFile3Frame(File3.encodeChunk("A", fid, 0, 0L, data))
        assertTrue("无元数据的幽灵会话不得回空 missing ACK（防发送端误判完成）", ackBodies(transport.frames).isEmpty())
        // START 随后到达（窗口重发必先发 START）：补元数据 → 收齐落盘 DONE
        manager.onFile3Frame(File3.encodeStart("A", fid, 1, data.size.toLong(), false, "ghost.bin", "application/octet-stream"))
        assertEquals("补 START 后应收齐 DONE", TransferStatus.DONE, manager.progress.value?.status)
        assertTrue("收齐后应回最终 ACK（missing 空）", ackBodies(transport.frames).any { it.missing.isEmpty() })
    }

    @Test
    fun `sender aborts after stall timeout instead of retrying forever`() = runTest {
        // v1.1.48 用户"发送方卡着不动"：链路存活（isConnectedTo=true）但 ACK 永远不来（芯片级写拒绝/对端回执全丢），
        // v1.1.38 无上限重试会让发送端无限"卡着不动"。窗口停滞超时（此处 300ms）给出收尾 → FAILED。
        val manager = FileTransferManager(
            transport = CountingTransport(), shortId = "A",
            saver = FakeSaver(kotlin.io.path.createTempDirectory("stallA2").toFile()),
            scope = backgroundScope, windowTimeoutMs = 50, maxWindowRetries = Int.MAX_VALUE,
            stallTimeoutMs = 300,
        )
        val bytes = randomBytes(File3.CHUNK_BYTES * 8)
        manager.sendFile("conv-B", "B", { ByteArrayInputStream(bytes) }, "stall2.bin", "application/octet-stream", bytes.size.toLong())
        awaitDone(manager)
        assertEquals("窗口停滞超时应 FAILED 而非无限重试", TransferStatus.FAILED, manager.progress.value?.status)
    }

    @Test
    fun `file3 frames fit BLE single-frame budget`() {
        // v1.1.36 v2 CHUNK：61B 头（含 36 字符完整 UUID fid + 8B byteOffset）+ 448B 数据 = 509B ≤ MTU 512 载荷。
        // 历史教训：v1.1.28 用 8 字符短 fid 测试漏网（头 25B），生产 fid=完整 UUID 头超预算 → 真机 0 块；
        // v1.1.35 块 480→456（53B 头 + 456B = 509B）贴着上限，但真机 MTU 常协商不足 512 → 仍 write FAILED，
        // v1.1.36 起发送端按 currentMtu() 动态降块（此测试断言 MTU=512 上限情形）。
        val fullFid = "12345678-1234-1234-1234-123456789012"   // 36 字符（与 UUID.randomUUID().toString() 同长，无前缀）
        val chunk = File3.encodeChunk("AB12", fullFid, 0, 0L, ByteArray(File3.CHUNK_BYTES) { 1 })
        println("DIAG FILE3 CHUNK v2 bytes=${chunk.size} budget=509")
        assertTrue("FILE3 CHUNK 帧 ${chunk.size}B 超 509B", chunk.size <= 509)

        val start = File3.encodeStart(
            srcId = "AB12", fid = fullFid, totalChunks = 100, origSize = 48000, compressed = true,
            name = "项目周报-第三季度-终版-超长中文文件名".repeat(4),   // 120 字 ≈ 360B（含截断防御）
            mime = "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        )
        println("DIAG FILE3 START bytes=${start.size} budget=509")
        assertTrue("FILE3 START 帧 ${start.size}B 超 509B", start.size <= 509)
    }

    @Test
    fun `chunk bytes adapt to negotiated mtu`() {
        // v1.1.36 动态块大小：MTU 协商不足 512 时块自动变小，帧 ≤ mtu-3 载荷（防 write FAILED/0 块）
        val max = FileTransferManager.dynamicChunkBytes(-1)     // 未知/测试替身 → 按 512
        assertEquals(File3.CHUNK_BYTES, max)
        assertTrue(File3.encodeChunk("AB12", "12345678-1234-1234-1234-123456789012", 0, 0L, ByteArray(max) { 1 }).size <= 512 - 3)
        val mtu497 = FileTransferManager.dynamicChunkBytes(497)
        assertTrue("MTU 497 块应 < 上限", mtu497 < File3.CHUNK_BYTES)
        assertTrue(File3.encodeChunk("AB12", "12345678-1234-1234-1234-123456789012", 0, 0L, ByteArray(mtu497) { 1 }).size <= 497 - 3)
        val mtu247 = FileTransferManager.dynamicChunkBytes(247)
        assertTrue("MTU 247 块应显著变小", mtu247 < mtu497)
        assertTrue(File3.encodeChunk("AB12", "12345678-1234-1234-1234-123456789012", 0, 0L, ByteArray(mtu247) { 1 }).size <= 247 - 3)
        assertTrue("块下限 64", FileTransferManager.dynamicChunkBytes(120) >= 64)
    }

    @Test
    fun `file3 compressed transfer decompresses to original`() = runTest {
        // v1.1.28 压缩传输 e2e：可压缩数据 → 发送端 deflate 压缩分块 → 接收端收齐解压 → 字节一致
        val transport = InMemoryTransport()
        val dirB = kotlin.io.path.createTempDirectory("e2e-c").toFile()
        val a = FileTransferManager(
            transport = transport, shortId = "A", saver = FakeSaver(kotlin.io.path.createTempDirectory("e2e-cA").toFile()),
            scope = backgroundScope, windowTimeoutMs = 5_000, maxWindowRetries = 5,
        )
        val b = FileTransferManager(
            transport = transport, shortId = "B", saver = FakeSaver(dirB),
            scope = backgroundScope, windowTimeoutMs = 5_000, maxWindowRetries = 5,
        )
        // 可压缩内容：重复文本（deflate 压缩率显著）→ 传输块数大幅减少
        val bytes = "MeshChat 近场安全通信——文件传输内置压缩验证。".repeat(400).toByteArray()
        val relay = backgroundScope.launch {
            transport.incoming.collect { frame ->
                if (File3.isFile3(frame.payload)) {
                    when (File3.parse(frame.payload)) {
                        is File3.Frame.ChunkFrame, is File3.Frame.StartFrame -> b.onFile3Frame(frame.payload)
                        null -> Unit
                    }
                } else {
                    val env = runCatching { MeshJson.decodeEnvelope(frame.payloadText) }.getOrNull() ?: return@collect
                    when (env.body) {
                        is FileAckBody -> if (env.dstId == "A") a.onFileAck(env)
                        else -> Unit
                    }
                }
            }
        }
        a.sendFile("conv-B", "B", { ByteArrayInputStream(bytes) }, "doc.txt", "text/plain", bytes.size.toLong())
        awaitDone(a)
        relay.cancel()
        assertEquals(TransferStatus.DONE, a.progress.value?.status)
        val saved = File(dirB, "doc.txt")
        assertTrue("B 应落盘解压后的完整文件", saved.exists())
        assertEquals("解压后字节与原始一致", bytes.toList(), saved.readBytes().toList())
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
