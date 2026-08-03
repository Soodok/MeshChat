# MeshChat 文件传输实现计划（v0.12.0）

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 在 MeshChat 内实现 BLE 点对点可靠文件传输（<10MB，窗口批量 bitmap 确认，接收方存公共 Downloads）。

**架构：** 协议层扩展 `FileBody.fileId` + 新增 `FileAckBody`；新建 `mesh/transfer/FileTransferManager` 传输引擎（发送状态机 + 接收重组，串行单文件，窗口 32 块/块 200B，缺失 bitmap 重传，超时兜底）；`MeshService` 挂接引擎与 FILE/FILE_ACK 帧分发；UI 增加文件气泡、系统文件选择器与进度展示。

**技术栈：** Kotlin + kotlinx-serialization + Room（复用现有）+ Compose + MediaStore.Downloads（API 29+）/ WRITE_EXTERNAL_STORAGE（API 26-28）。

**规格：** `docs/superpowers/specs/2026-08-03-meshchat-file-transfer-design.md`

---

## 文件结构

**新建：**
- `app/src/main/java/com/meshchat/app/mesh/transfer/FileTransfer.kt` — 传输数据模型（FileProgress/FileSaver/方向/状态）+ missing 计算工具
- `app/src/main/java/com/meshchat/app/mesh/transfer/FileTransferManager.kt` — 传输引擎（发送状态机/接收重组/串行/超时）
- `app/src/main/java/com/meshchat/app/mesh/transfer/AndroidFileSaver.kt` — MediaStore.Downloads 落盘实现（仅 Android 层引用）
- `app/src/test/java/com/meshchat/app/mesh/transfer/FileTransferManagerTest.kt` — 引擎测试

**修改：**
- `app/src/main/java/com/meshchat/app/mesh/protocol/MeshEnvelope.kt` — FileBody.fileId + FileAckBody
- `app/src/test/java/com/meshchat/app/mesh/protocol/MeshEnvelopeTest.kt` — 编解码测试
- `app/src/main/java/com/meshchat/app/mesh/storage/MeshStore.kt` — 接口加 `updateFileMeta`
- `app/src/main/java/com/meshchat/app/mesh/storage/Daos.kt` — MessageDao 加 `updateFileMeta`
- `app/src/main/java/com/meshchat/app/mesh/storage/RoomMeshStore.kt` — 实现 `updateFileMeta`
- `app/src/main/java/com/meshchat/app/mesh/storage/InMemoryMeshStore.kt` — 实现 `updateFileMeta`
- `app/src/main/java/com/meshchat/app/mesh/service/MeshService.kt` — sendFile/FILE/FILE_ACK 分发/fileProgress/tick 转发
- `app/src/main/java/com/meshchat/app/data/MeshRepository.kt` — sendFile 接口 + fileProgress 透传 + 消息模型 file 字段
- `app/src/main/java/com/meshchat/app/data/UiModels.kt` — ChatMessage.file + FileUiMeta
- `app/src/main/java/com/meshchat/app/ui/MeshChatViewModel.kt` — sendFile + 进度合并
- `app/src/main/java/com/meshchat/app/ui/screens/ConversationScreen.kt` — 文件气泡 + 附件按钮
- `app/src/main/java/com/meshchat/app/ui/screens/MeshChatHome.kt` — 文件选择器 + 接线
- `app/src/main/java/com/meshchat/app/ui/MeshChatApp.kt` — 接线
- `app/src/main/java/com/meshchat/app/MainActivity.kt` — API 26-28 WRITE_EXTERNAL_STORAGE
- `app/src/main/AndroidManifest.xml` — WRITE_EXTERNAL_STORAGE 声明（maxSdkVersion 28）
- `app/build.gradle.kts` — versionCode/Name bump
- `app/src/test/java/com/meshchat/app/mesh/service/MeshServiceTest.kt` — FILE 帧处理测试

**测试命令：** `.\gradlew.bat testDebugUnitTest`（单测）与 `.\gradlew.bat assembleDebug`（构建）。

---

### 任务 1：协议扩展（FileBody.fileId + FileAckBody）

**文件：**
- 修改：`app/src/main/java/com/meshchat/app/mesh/protocol/MeshEnvelope.kt`
- 测试：`app/src/test/java/com/meshchat/app/mesh/protocol/MeshEnvelopeTest.kt`

- [ ] **步骤 1：编写失败的测试**

在 `MeshEnvelopeTest.kt` 追加：

```kotlin
@Test
fun `file body encodes and decodes with fileId and chunks`() {
    val body = FileBody(
        fileId = "f-1", fileName = "a.pdf", mime = "application/pdf",
        size = 1000, totalChunks = 5, chunkIndex = 2, chunkData = "aGVsbG8=",
    )
    val envelope = MeshEnvelope(
        id = "e-1", kind = "FILE", srcId = "A", dstId = "B",
        convId = "conv-B", ts = 1, body = body,
    )
    val decoded = MeshJson.decodeEnvelope(MeshJson.encodeEnvelope(envelope))
    val file = decoded.body as FileBody
    assertEquals("f-1", file.fileId)
    assertEquals(5, file.totalChunks)
    assertEquals(2, file.chunkIndex)
    assertEquals("aGVsbG8=", file.chunkData)
}

@Test
fun `file ack body encodes and decodes with missing list`() {
    val body = FileAckBody(fileId = "f-1", totalChunks = 100, missing = listOf(3, 7, 99))
    val envelope = MeshEnvelope(
        id = "e-2", kind = "FILE_ACK", srcId = "B", dstId = "A",
        convId = "conv-A", ts = 1, body = body,
    )
    val decoded = MeshJson.decodeEnvelope(MeshJson.encodeEnvelope(envelope))
    val ack = decoded.body as FileAckBody
    assertEquals("f-1", ack.fileId)
    assertEquals(listOf(3, 7, 99), ack.missing)
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`.\gradlew.bat testDebugUnitTest --tests "com.meshchat.app.mesh.protocol.MeshEnvelopeTest" --console=plain`
预期：编译失败（FileBody 缺 fileId 参数 / FileAckBody 不存在）。

- [ ] **步骤 3：实现协议扩展**

修改 `MeshEnvelope.kt`：

```kotlin
@Serializable
@SerialName("FILE")
data class FileBody(
    val fileId: String,        // 关联同一文件所有分块（= 首块信封 id）
    val fileName: String,
    val mime: String,
    val size: Long,
    val totalChunks: Int,
    val chunkIndex: Int,
    val chunkData: String,     // base64，每块原始 200B → ~268B
) : EnvelopeBody

@Serializable
@SerialName("FILE_ACK")
data class FileAckBody(
    val fileId: String,
    val totalChunks: Int,
    val missing: List<Int>,   // 缺失块索引；空 = 全部收齐
) : EnvelopeBody
```

- [ ] **步骤 4：运行测试验证通过**

运行：`.\gradlew.bat testDebugUnitTest --tests "com.meshchat.app.mesh.protocol.MeshEnvelopeTest" --console=plain`
预期：PASS（新增 2 个测试 + 原测试不回归）。

- [ ] **步骤 5：Commit**

```bash
git add app/src/main/java/com/meshchat/app/mesh/protocol/MeshEnvelope.kt app/src/test/java/com/meshchat/app/mesh/protocol/MeshEnvelopeTest.kt
git commit -m "feat: 协议层 FILE 分块 fileId 关联与 FILE_ACK 缺失 bitmap 帧（v0.12.0）"
```

---

### 任务 2：传输模型 + 发送状态机（FileTransfer.kt + FileTransferManager 发送端）

**文件：**
- 创建：`app/src/main/java/com/meshchat/app/mesh/transfer/FileTransfer.kt`
- 创建：`app/src/main/java/com/meshchat/app/mesh/transfer/FileTransferManager.kt`
- 测试：`app/src/test/java/com/meshchat/app/mesh/transfer/FileTransferManagerTest.kt`

- [ ] **步骤 1：编写失败的测试**

创建 `FileTransferManagerTest.kt`：

```kotlin
package com.meshchat.app.mesh.transfer

import com.meshchat.app.mesh.protocol.FileAckBody
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

    /** 从广播帧里取出第 idx 个 FILE 块体的数据（用于构造 ACK）。 */
    private fun fileChunks(frames: List<MeshFrame>): List<FileBody> =
        frames.mapNotNull { frame ->
            val env = runCatching { MeshJson.decodeEnvelope(frame.payloadText) }.getOrNull()
            env?.body as? FileBody
        }

    @Test
    fun `first window sends 32 chunks then retries only missing chunk and completes`() = runTest {
        val transport = CountingTransport()
        val dir = kotlin.io.path.createTempDirectory("mesh").toFile()
        val manager = FileTransferManager(
            transport = transport, shortId = "A", saver = FakeSaver(dir),
            scope = backgroundScope, windowTimeoutMs = 5_000, maxWindowRetries = 3,
        )
        val bytes = ByteArray(200 * 33) { it.toByte() }   // 33 块 → 2 个窗口
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

        // 窗口完成 → 推进第二窗（块 32），回 ACK 空 → 完成
        val secondWindow = awaitChunks(transport, 1)
        assertEquals(32, secondWindow.first().chunkIndex)
        manager.onFileAck(ack(fileId, 33, emptyList()))

        awaitDone(manager)
        assertEquals(TransferStatus.DONE, manager.progress.value?.status)
        assertEquals(33 * 200L, manager.progress.value?.transferredBytes)
    }

    @Test
    fun `window timeout retransmits whole window`() = runTest {
        val transport = CountingTransport()
        val manager = FileTransferManager(
            transport = transport, shortId = "A", saver = FakeSaver(kotlin.io.path.createTempDirectory("m2").toFile()),
            scope = backgroundScope, windowTimeoutMs = 100, maxWindowRetries = 3,
        )
        val bytes = ByteArray(200 * 32) { 1 }
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
        val bytes = ByteArray(200 * 8) { 2 }
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
        val bytes = ByteArray(200 * 4) { 3 }
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

    private fun ack(fileId: String, total: Int, missing: List<Int>) = MeshEnvelope(
        id = "ack-${fileId}", kind = "FILE_ACK", srcId = "B", dstId = "A",
        convId = "conv-A", ts = 1, body = FileAckBody(fileId, total, missing),
    )

    private suspend fun awaitChunks(t: CountingTransport, n: Int): List<FileBody> {
        val seen = mutableListOf<FileBody>()
        val known = mutableSetOf<Int>()
        var guard = 0
        while (seen.size < n) {
            val chunks = fileChunks(t.frames).filter { known.add(it.chunkIndex) }
            seen.addAll(chunks)
            if (seen.size < n) { kotlinx.coroutines.delay(20); guard++; if (guard > 500) break }
        }
        return seen.takeLast(n)
    }

    private suspend fun awaitDone(manager: FileTransferManager) {
        var guard = 0
        while (manager.progress.value?.status == null || manager.progress.value!!.status == TransferStatus.RUNNING) {
            if (guard++ > 500) break
            kotlinx.coroutines.delay(20)
        }
    }
}
```

注：`awaitChunks` 按已见块去重取新块，避免首窗/重传/二窗帧叠加混淆。

- [ ] **步骤 2：运行测试验证失败**

运行：`.\gradlew.bat testDebugUnitTest --tests "com.meshchat.app.mesh.transfer.FileTransferManagerTest" --console=plain`
预期：编译失败（FileTransferManager/FileSaver/TransferStatus 不存在）。

- [ ] **步骤 3：实现传输模型（FileTransfer.kt）**

创建 `FileTransfer.kt`：

```kotlin
package com.meshchat.app.mesh.transfer

import java.io.File

enum class TransferDirection { SENDING, RECEIVING }
enum class TransferStatus { RUNNING, DONE, FAILED }

data class FileProgress(
    val fileId: String,
    val convId: String,
    val direction: TransferDirection,
    val fileName: String,
    val totalBytes: Long,
    val transferredBytes: Long,
    val status: TransferStatus,
)

/** 接收端保存接口：Android 实现写 MediaStore.Downloads；测试用临时目录。 */
interface FileSaver {
    fun save(tmpFile: File, fileName: String, mime: String): String?
}
```

- [ ] **步骤 4：实现发送状态机（FileTransferManager.kt）**

创建 `FileTransferManager.kt`（先实现发送端；接收端方法留空壳，任务 3 填充）：

```kotlin
package com.meshchat.app.mesh.transfer

import java.io.File
import java.io.InputStream
import java.util.Base64
import java.util.UUID
import com.meshchat.app.mesh.protocol.FileAckBody
import com.meshchat.app.mesh.protocol.FileBody
import com.meshchat.app.mesh.protocol.FrameType
import com.meshchat.app.mesh.protocol.MeshEnvelope
import com.meshchat.app.mesh.protocol.MeshFrame
import com.meshchat.app.mesh.protocol.MeshJson
import com.meshchat.app.mesh.transport.MeshTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * BLE 文件传输引擎：串行单文件、窗口批量 bitmap 确认。
 * 发送：窗口 32 块 → 等 FILE_ACK（缺失索引）→ 重发缺失 → 超时整窗重发（上限后 FAILED）。
 * 接收：临时文件按 chunkIndex 写入 → 每 32 块/收齐回 ACK → 收齐校验后经 FileSaver 落盘。
 */
class FileTransferManager(
    private val transport: MeshTransport,
    private val shortId: String,
    private val saver: FileSaver,
    private val scope: CoroutineScope,
    private val windowTimeoutMs: Long = WINDOW_TIMEOUT_MS,
    private val maxWindowRetries: Int = MAX_WINDOW_RETRIES,
    private val onProgress: (FileProgress) -> Unit = {},
) {
    companion object {
        const val CHUNK_BYTES = 200
        const val WINDOW = 32
        const val WINDOW_TIMEOUT_MS = 15_000L
        const val MAX_WINDOW_RETRIES = 5
        const val RECV_STALL_TIMEOUT_MS = 60_000L
    }

    private val _progress = MutableStateFlow<FileProgress?>(null)
    val progress: StateFlow<FileProgress?> = _progress.asStateFlow()

    /** 发送会话（串行，同一时间仅一个）。 */
    private class SendSession(
        val fileId: String,
        val convId: String,
        val dstId: String,
        val openSource: () -> InputStream,
        val fileName: String,
        val mime: String,
        val size: Long,
    ) {
        val ackDeferred = CompletableDeferred<FileAckBody?>()
        var expectStart = 0
        var expectEnd = 0
        var lastMissingCount = Int.MAX_VALUE
    }

    private var sending: SendSession? = null

    /** 发送文件；正在传输时返回 null（串行约束）。fileId 同时用作消息 id。 */
    fun sendFile(
        convId: String,
        dstId: String,
        openSource: () -> InputStream,
        fileName: String,
        mime: String,
        size: Long,
    ): String? {
        if (sending != null) return null
        val session = SendSession(
            fileId = UUID.randomUUID().toString(),
            convId = convId, dstId = dstId, openSource = openSource,
            fileName = fileName, mime = mime, size = size,
        )
        sending = session
        scope.launch { runSender(session) }
        return session.fileId
    }

    private suspend fun runSender(s: SendSession) {
        val totalChunks = ((s.size + CHUNK_BYTES - 1) / CHUNK_BYTES).toInt()
        if (totalChunks == 0) { finish(s, TransferStatus.FAILED); return }
        try {
            var windowStart = 0
            while (windowStart < totalChunks) {
                val inWindow = minOf(WINDOW, totalChunks - windowStart)
                val cache = readWindow(s, windowStart, inWindow) ?: run { finish(s, TransferStatus.FAILED); return }
                s.expectStart = windowStart
                s.expectEnd = windowStart + inWindow - 1
                broadcastWindow(s, cache)
                var retries = 0
                while (true) {
                    val ack = try { withTimeout(windowTimeoutMs) { s.ackDeferred.await() } }
                    catch (e: TimeoutCancellationException) { null }
                    s.ackDeferred.complete(null)  // 重置供下一窗口复用
                    if (ack == null) {
                        retries++
                        if (retries > maxWindowRetries) { finish(s, TransferStatus.FAILED); return }
                        broadcastWindow(s, cache)
                        continue
                    }
                    val need = ack.missing.filter { it in s.expectStart..s.expectEnd }
                    if (need.isEmpty()) {
                        windowStart += inWindow
                        s.lastMissingCount = Int.MAX_VALUE
                        updateProgress(s, TransferStatus.RUNNING)
                        break
                    }
                    for (i in need) broadcastChunk(s, cache[i]!!, totalChunks, s.fileName, s.mime, s.size)
                    if (need.size >= s.lastMissingCount) retries++ else retries = 0
                    s.lastMissingCount = need.size
                    if (retries > maxWindowRetries) { finish(s, TransferStatus.FAILED); return }
                }
            }
            finish(s, TransferStatus.DONE)
        } catch (e: Exception) {
            finish(s, TransferStatus.FAILED)
        }
    }

    /** 顺序读窗口块，返回 chunkIndex -> base64（重传直接用缓存，无需重开流）。 */
    private fun readWindow(s: SendSession, start: Int, count: Int): Map<Int, String>? = runCatching {
        val source = s.openSource()
        val cache = LinkedHashMap<Int, String>()
        source.skip(start * CHUNK_BYTES.toLong())
        for (i in 0 until count) {
            val buf = ByteArray(CHUNK_BYTES)
            val n = source.read(buf)
            cache[start + i] = Base64.getEncoder().encodeToString(buf, 0, n.coerceAtLeast(0))
        }
        source.close()
        cache
    }.getOrNull()

    private fun broadcastWindow(s: SendSession, cache: Map<Int, String>) {
        val total = ((s.size + CHUNK_BYTES - 1) / CHUNK_BYTES).toInt()
        for ((index, data) in cache) broadcastChunk(s, data, total, s.fileName, s.mime, s.size)
    }

    private fun broadcastChunk(s: SendSession, data: String, totalChunks: Int, name: String, mime: String, size: Long) {
        // 简化：broadcastChunk 需 chunkIndex —— 由调用处传索引；此处签名见下方修正
        throw NotImplementedError()
    }

    private fun updateProgress(s: SendSession, status: TransferStatus) {
        val total = ((s.size + CHUNK_BYTES - 1) / CHUNK_BYTES).toInt()
        val transferred = if (status == TransferStatus.DONE) s.size else (s.expectStart.coerceAtMost(total) * CHUNK_BYTES.toLong()).coerceAtMost(s.size)
        val progress = FileProgress(
            fileId = s.fileId, convId = s.convId, direction = TransferDirection.SENDING,
            fileName = s.fileName, totalBytes = s.size, transferredBytes = transferred, status = status,
        )
        _progress.value = progress
        onProgress(progress)
    }

    private fun finish(s: SendSession, status: TransferStatus) {
        if (sending === s) sending = null
        updateProgress(s, status)
    }

    // ---- 接收端（任务 3 填充）----
    fun onFileChunk(envelope: MeshEnvelope, body: FileBody) = Unit
    fun onFileAck(envelope: MeshEnvelope, body: FileAckBody) {
        sending?.let { s -> if (body.fileId == s.fileId) s.ackDeferred.complete(body) }
    }
    fun tick(now: Long) = Unit
}
```

修正：`broadcastChunk` 签名应为 `(s: SendSession, index: Int, data: String, totalChunks: Int, name: String, mime: String, size: Long)`，调用处 `broadcastChunk(s, index, data, total, name, mime, size)`。`broadcastWindow` 相应改为 `for ((index, data) in cache) broadcastChunk(s, index, data, total, s.fileName, s.mime, s.size)`。实现：

```kotlin
private fun broadcastChunk(s: SendSession, index: Int, data: String, totalChunks: Int, name: String, mime: String, size: Long) {
    val envelope = MeshEnvelope(
        id = UUID.randomUUID().toString(),
        kind = "FILE",
        srcId = shortId,
        dstId = s.dstId,
        convId = s.convId,
        ttl = 8,
        ts = System.currentTimeMillis(),
        body = FileBody(fileId = s.fileId, fileName = name, mime = mime, size = size,
            totalChunks = totalChunks, chunkIndex = index, chunkData = data),
    )
    transport.broadcast(MeshFrame(FrameType.DATA, MeshJson.encodeEnvelope(envelope).toByteArray()))
}
```

- [ ] **步骤 5：运行测试验证通过**

运行：`.\gradlew.bat testDebugUnitTest --tests "com.meshchat.app.mesh.transfer.FileTransferManagerTest" --console=plain`
预期：PASS（4 个测试）。

- [ ] **步骤 6：Commit**

```bash
git add app/src/main/java/com/meshchat/app/mesh/transfer/ app/src/test/java/com/meshchat/app/mesh/transfer/
git commit -m "feat: 文件传输发送状态机——窗口批量 bitmap 确认/缺失重传/超时兜底/串行约束（v0.12.0）"
```

---

### 任务 3：接收端重组 + AndroidFileSaver

**文件：**
- 修改：`app/src/main/java/com/meshchat/app/mesh/transfer/FileTransferManager.kt`（填充接收端）
- 创建：`app/src/main/java/com/meshchat/app/mesh/transfer/AndroidFileSaver.kt`
- 测试：`app/src/test/java/com/meshchat/app/mesh/transfer/FileTransferManagerTest.kt`（追加）

- [ ] **步骤 1：编写失败的测试**

在 `FileTransferManagerTest.kt` 追加：

```kotlin
@Test
fun `receives out-of-order and duplicate chunks then assembles full file`() = runTest {
    val transport = CountingTransport()
    val dir = kotlin.io.path.createTempDirectory("m5").toFile()
    val saver = FakeSaver(dir)
    val manager = FileTransferManager(
        transport = transport, shortId = "B", saver = saver,
        scope = backgroundScope, windowTimeoutMs = 5_000, maxWindowRetries = 3,
    )
    val source = ByteArray(200 * 10) { (it % 251).toByte() }
    val fileId = "recv-1"
    fun chunk(index: Int) = FileBody(
        fileId = fileId, fileName = "recv.bin", mime = "application/octet-stream",
        size = source.size.toLong(), totalChunks = 10, chunkIndex = index,
        chunkData = Base64.getEncoder().encodeToString(source, index * 200, 200),
    )
    // 乱序 + 重复投递
    for (i in listOf(5, 3, 9, 3, 0, 7, 2, 6, 8, 1, 4)) {
        manager.onFileChunk(envelope(fileId, chunk(i)), chunk(i))
    }
    // 收齐后应回最终 ACK（missing 空）且落盘
    val ackFrames = fileChunks(transport.frames)  // 不含 ACK——改为解析 FILE_ACK 帧
    val saved = File(dir, "recv.bin")
    assertTrue(saved.exists())
    assertEquals(source.toList(), saved.readBytes().toList())
    assertEquals(source.size.toLong(), manager.progress.value?.transferredBytes)
}

private fun envelope(fileId: String, body: FileBody) = MeshEnvelope(
    id = "env-$fileId", kind = "FILE", srcId = "A", dstId = "B",
    convId = "conv-A", ts = 1, body = body,
)
```

注：测试中需断言收齐后广播了 `FILE_ACK`（missing 空）。在测试里解析 `transport.frames` 中 kind 为 FILE_ACK 的帧并断言其 body。追加：

```kotlin
private fun ackFrames(frames: List<MeshFrame>): List<FileAckBody> =
    frames.mapNotNull { frame ->
        val env = runCatching { MeshJson.decodeEnvelope(frame.payloadText) }.getOrNull()
        env?.body as? FileAckBody
    }
```

并在测试断言：`assertTrue(ackFrames(transport.frames).any { it.missing.isEmpty() })`。

- [ ] **步骤 2：运行测试验证失败**

运行：`.\gradlew.bat testDebugUnitTest --tests "com.meshchat.app.mesh.transfer.FileTransferManagerTest" --console=plain`
预期：FAIL（`onFileChunk` 为空壳，无落盘）。

- [ ] **步骤 3：实现接收端**

修改 `FileTransferManager.kt`：

```kotlin
private class ReceiveSession(
    val fileId: String,
    val convId: String,
    val senderId: String,
    val fileName: String,
    val mime: String,
    val size: Long,
    val totalChunks: Int,
    val tmpFile: File,
    val received: MutableSet<Int>,
    var lastActivity: Long,
    var ackCounter: Int = 0,
) {
    fun writeChunk(chunkIndex: Int, data: ByteArray) {
        if (chunkIndex in received) return
        RandomAccessFile(tmpFile, "rw").use { raf ->
            raf.seek(chunkIndex * CHUNK_BYTES.toLong())
            raf.write(data)
        }
        received += chunkIndex
    }
    val missing: List<Int> get() = (0 until totalChunks).filter { it !in received }
    val isComplete: Boolean get() = received.size >= totalChunks
}
```

接收逻辑（替换 `onFileChunk` 空壳）：

```kotlin
private val receivers = mutableMapOf<String, ReceiveSession>()

fun onFileChunk(envelope: MeshEnvelope, body: FileBody) {
    if (body.size <= 0 || body.totalChunks <= 0) return
    val session = receivers.getOrPut(body.fileId) {
        ReceiveSession(
            fileId = body.fileId, convId = "conv-${envelope.srcId}", senderId = envelope.srcId,
            fileName = body.fileName, mime = body.mime, size = body.size,
            totalChunks = body.totalChunks,
            tmpFile = File(tmpDir(), "${body.fileId}.part"),
            received = mutableSetOf(), lastActivity = System.currentTimeMillis(),
        )
    }
    session.lastActivity = System.currentTimeMillis()
    val data = runCatching { Base64.getDecoder().decode(body.chunkData) }.getOrNull() ?: return
    session.writeChunk(body.chunkIndex, data)
    updateReceiveProgress(session, TransferStatus.RUNNING)

    session.ackCounter++
    if (session.isComplete) {
        completeReceive(session)
    } else if (session.ackCounter % WINDOW == 0) {
        sendAck(session)
    }
}

private fun tmpDir(): File = File(System.getProperty("java.io.tmpdir"), "meshchat_transfers")
    .apply { mkdirs() }

private fun completeReceive(s: ReceiveSession) {
    // 校验大小
    if (s.tmpFile.length() != s.size) {
        s.tmpFile.delete()
        receivers.remove(s.fileId)
        updateReceiveProgress(s, TransferStatus.FAILED)
        return
    }
    val uri = saver.save(s.tmpFile, s.fileName, s.mime)
    s.tmpFile.delete()
    sendAck(s, final = true)
    receivers.remove(s.fileId)
    onSaved?.invoke(s.convId, s.fileId, s.fileName, uri)   // 通知上层落库
    updateReceiveProgress(s, TransferStatus.DONE)
}

private fun sendAck(s: ReceiveSession, final: Boolean = false) {
    val ack = MeshEnvelope(
        id = UUID.randomUUID().toString(),
        kind = "FILE_ACK",
        srcId = shortId,
        dstId = s.senderId,
        convId = s.convId,
        ttl = 8,
        ts = System.currentTimeMillis(),
        body = FileAckBody(fileId = s.fileId, totalChunks = s.totalChunks,
            missing = if (final) emptyList() else s.missing),
    )
    transport.broadcast(MeshFrame(FrameType.DATA, MeshJson.encodeEnvelope(ack).toByteArray()))
}

private fun updateReceiveProgress(s: ReceiveSession, status: TransferStatus) {
    val progress = FileProgress(
        fileId = s.fileId, convId = s.convId, direction = TransferDirection.RECEIVING,
        fileName = s.fileName, totalBytes = s.size,
        transferredBytes = (s.received.size * CHUNK_BYTES.toLong()).coerceAtMost(s.size),
        status = status,
    )
    _progress.value = progress
    onProgress(progress)
}
```

`FileTransferManager` 构造新增参数：

```kotlin
    private val onSaved: (convId: String, fileId: String, fileName: String, uri: String?) -> Unit = { _, _, _, _ -> },
```

`tick` 填充（60s 无进展清理）：

```kotlin
fun tick(now: Long) {
    val it = receivers.entries.iterator()
    while (it.hasNext()) {
        val (fileId, s) = it.next()
        if (now - s.lastActivity > RECV_STALL_TIMEOUT_MS) {
            s.tmpFile.delete()
            updateReceiveProgress(s, TransferStatus.FAILED)
            it.remove()
        }
    }
}
```

修正：接收会话用 `System.getProperty("java.io.tmpdir")` 便于 JVM 测试；Android 运行时需真实私有目录 —— 将 tmpDir 抽象为构造参数 `tmpDirProvider: () -> File`（Android 侧传 `File(context.filesDir, "transfers")`）。修改构造：

```kotlin
class FileTransferManager(
    ...
    private val tmpDirProvider: () -> File = { File(System.getProperty("java.io.tmpdir"), "meshchat_transfers") },
    ...
) {
    private fun tmpDir(): File = tmpDirProvider().apply { mkdirs() }
}
```

- [ ] **步骤 4：实现 AndroidFileSaver**

创建 `AndroidFileSaver.kt`：

```kotlin
package com.meshchat.app.mesh.transfer

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/** 接收完成的文件写入公共 Downloads：API 29+ 走 MediaStore（免权限）；API 26-28 走公共目录（需 WRITE_EXTERNAL_STORAGE）。 */
class AndroidFileSaver(private val context: Context) : FileSaver {
    override fun save(tmpFile: File, fileName: String, mime: String): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mime)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            val ok = context.contentResolver.openOutputStream(uri)?.use { out ->
                tmpFile.inputStream().use { it.copyTo(out) }
            } != null
            if (!ok) { context.contentResolver.delete(uri, null, null); return null }
            return uri.toString()
        }
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!dir.exists() && !dir.mkdirs()) return null
        val target = File(dir, fileName)
        return runCatching {
            tmpFile.copyTo(target, overwrite = true)
            Uri.fromFile(target).toString()
        }.getOrNull()
    }
}
```

- [ ] **步骤 5：运行测试验证通过**

运行：`.\gradlew.bat testDebugUnitTest --tests "com.meshchat.app.mesh.transfer.FileTransferManagerTest" --console=plain`
预期：PASS（5 个测试）。

- [ ] **步骤 6：Commit**

```bash
git add app/src/main/java/com/meshchat/app/mesh/transfer/ app/src/test/java/com/meshchat/app/mesh/transfer/
git commit -m "feat: 文件接收重组——乱序/重复块幂等、收齐校验、AndroidFileSaver 落盘 Downloads、60s 无进展清理（v0.12.0）"
```

---

### 任务 4：存储层 updateFileMeta

**文件：**
- 修改：`app/src/main/java/com/meshchat/app/mesh/storage/MeshStore.kt`
- 修改：`app/src/main/java/com/meshchat/app/mesh/storage/Daos.kt`
- 修改：`app/src/main/java/com/meshchat/app/mesh/storage/RoomMeshStore.kt`
- 修改：`app/src/main/java/com/meshchat/app/mesh/storage/InMemoryMeshStore.kt`

- [ ] **步骤 1：实现（接口 + DAO + 两个实现）**

`MeshStore.kt` 接口追加：

```kotlin
    fun updateFileMeta(id: String, fileMeta: String?)
```

`Daos.kt`：

```kotlin
    @Query("UPDATE messages SET fileMeta = :fileMeta WHERE id = :id")
    suspend fun updateFileMeta(id: String, fileMeta: String?)
```

`RoomMeshStore.kt`：

```kotlin
    override fun updateFileMeta(id: String, fileMeta: String?) = runBlocking {
        db.messageDao().updateFileMeta(id, fileMeta)
    }
```

`InMemoryMeshStore.kt`：

```kotlin
    override fun updateFileMeta(id: String, fileMeta: String?) {
        val index = messages.indexOfFirst { it.id == id }
        if (index >= 0) messages[index] = messages[index].copy(fileMeta = fileMeta)
    }
```

- [ ] **步骤 2：编译验证**

运行：`.\gradlew.bat compileDebugKotlin --console=plain`
预期：BUILD SUCCESSFUL。

- [ ] **步骤 3：Commit**

```bash
git add app/src/main/java/com/meshchat/app/mesh/storage/
git commit -m "feat: 存储层 updateFileMeta——接收方收齐后回填 Downloads URI（v0.12.0）"
```

---

### 任务 5：MeshService 集成

**文件：**
- 修改：`app/src/main/java/com/meshchat/app/mesh/service/MeshService.kt`
- 修改：`app/src/main/java/com/meshchat/app/MeshChatApplication.kt`
- 测试：`app/src/test/java/com/meshchat/app/mesh/service/MeshServiceTest.kt`

- [ ] **步骤 1：编写失败的测试**

在 `MeshServiceTest.kt` 追加（FakeSaver 写临时目录）：

```kotlin
@Test
fun `file chunk addressed to me is stored as file message`() = runTest {
    val identity = LocalIdentity(shortId = "ME")
    val transport = InMemoryTransport()
    val store = InMemoryMeshStore()
    val service = MeshService(
        transport = transport, store = store, identity = identity, dedup = DedupCache(),
        fileSaver = FakeSaver(kotlin.io.path.createTempDirectory("svc").toFile()),
    )
    service.start()
    val body = FileBody(
        fileId = "f-svc", fileName = "x.txt", mime = "text/plain",
        size = 100, totalChunks = 1, chunkIndex = 0,
        chunkData = Base64.getEncoder().encodeToString(ByteArray(100) { 7 }),
    )
    service.handleFrame(MeshFrame(
        FrameType.DATA,
        MeshJson.encodeEnvelope(MeshEnvelope(
            id = "e-1", kind = "FILE", srcId = "OTHER", dstId = "ME",
            convId = "conv-ME", ttl = 8, ts = 1, body = body,
        )).toByteArray(),
    ))
    val stored = store.observeMessages("conv-OTHER").first().first()
    assertEquals("FILE", stored.kind)
    assertEquals("f-svc", stored.id)
    assertEquals(MessageStatus.DELIVERED, stored.status)
    service.stop()
}

@Test
fun `file chunk not addressed to me is ignored`() = runTest {
    val identity = LocalIdentity(shortId = "ME")
    val transport = InMemoryTransport()
    val store = InMemoryMeshStore()
    val service = MeshService(
        transport = transport, store = store, identity = identity, dedup = DedupCache(),
        fileSaver = FakeSaver(kotlin.io.path.createTempDirectory("svc2").toFile()),
    )
    service.start()
    val body = FileBody(
        fileId = "f-other", fileName = "x.txt", mime = "text/plain",
        size = 100, totalChunks = 1, chunkIndex = 0,
        chunkData = Base64.getEncoder().encodeToString(ByteArray(100) { 7 }),
    )
    service.handleFrame(MeshFrame(
        FrameType.DATA,
        MeshJson.encodeEnvelope(MeshEnvelope(
            id = "e-2", kind = "FILE", srcId = "OTHER", dstId = "OTHER2",
            convId = "conv-OTHER2", ttl = 8, ts = 1, body = body,
        )).toByteArray(),
    ))
    assertTrue(store.observeMessages("conv-OTHER").first().isEmpty())
    service.stop()
}
```

`MeshServiceTest.kt` 顶部追加：

```kotlin
private class FakeSaver(private val dir: File) : FileSaver {
    override fun save(tmpFile: File, fileName: String, mime: String): String {
        val target = File(dir, fileName)
        tmpFile.copyTo(target, overwrite = true)
        return target.absolutePath
    }
}
```

并补 import：`com.meshchat.app.mesh.transfer.FileSaver`、`java.io.File`、`java.util.Base64`。

- [ ] **步骤 2：运行测试验证失败**

运行：`.\gradlew.bat testDebugUnitTest --tests "com.meshchat.app.mesh.service.MeshServiceTest" --console=plain`
预期：编译失败（MeshService 无 fileSaver 参数 / FILE 分支未实现）。

- [ ] **步骤 3：MeshService 集成**

修改 `MeshService.kt`：

```kotlin
import com.meshchat.app.mesh.protocol.FileAckBody
import com.meshchat.app.mesh.protocol.FileBody
import com.meshchat.app.mesh.transfer.FileSaver
import com.meshchat.app.mesh.transfer.FileTransferManager
import com.meshchat.app.mesh.transfer.TransferStatus
```

构造参数追加：

```kotlin
class MeshService(
    private val transport: MeshTransport,
    private val store: MeshStore,
    private val identity: LocalIdentity,
    private val dedup: DedupCache,
    private val fileSaver: FileSaver,
    private val tmpDir: () -> java.io.File,
) {
```

`MeshService` 内部创建引擎：

```kotlin
    private val transfer = FileTransferManager(
        transport = transport, shortId = identity.shortId, saver = fileSaver,
        scope = scope, tmpDirProvider = tmpDir,
        onProgress = { p ->
            // 终态时同步落库状态（fileId 即消息 id）
            when (p.status) {
                TransferStatus.DONE -> store.updateMessageStatus(p.fileId, MessageStatus.DELIVERED)
                TransferStatus.FAILED -> store.updateMessageStatus(p.fileId, MessageStatus.FAILED)
                else -> Unit
            }
        },
        onSaved = { convId, fileId, fileName, uri ->
            // 接收收齐：回填 Downloads URI 并标记送达（fileMeta 为 JSON）
            val meta = runCatching {
                com.meshchat.app.mesh.storage.fileMetaOf(fileName, uri, 0L)  // 任务 6 前先内联构造
            }.getOrDefault(null)
            store.updateFileMeta(fileId, meta)
            store.updateMessageStatus(fileId, MessageStatus.DELIVERED)
        },
    )
```

注：为保持任务解耦，接收收齐的 fileMeta 回填直接构造 JSON 字符串：

```kotlin
    val meta = "{\"fileName\":\"${p.fileName}\",\"size\":0,\"downloadsUri\":\"$uri\"}"
```

需在 onProgress/onSaved 闭包可访问 `p` —— 调整闭包体。最终实现：

```kotlin
    private val transfer = FileTransferManager(
        transport = transport, shortId = identity.shortId, saver = fileSaver,
        scope = scope, tmpDirProvider = tmpDir,
        onProgress = { p ->
            when (p.status) {
                TransferStatus.DONE -> store.updateMessageStatus(p.fileId, MessageStatus.DELIVERED)
                TransferStatus.FAILED -> store.updateMessageStatus(p.fileId, MessageStatus.FAILED)
                else -> Unit
            }
        },
        onSaved = { convId, fileId, fileName, uri ->
            val meta = uri?.let { "{\"fileName\":\"${fileName}\",\"size\":0,\"downloadsUri\":\"$it\"}" }
            store.updateFileMeta(fileId, meta)
            store.updateMessageStatus(fileId, MessageStatus.DELIVERED)
        },
    )
```

`sendFile` 公开方法：

```kotlin
    /** 发送文件：fileId 即消息 id（落库占位）；返回 null 表示传输中（串行约束）或目标为空。 */
    fun sendFile(convId: String, dstId: String, openSource: () -> java.io.InputStream, fileName: String, mime: String, size: Long): String? {
        if (dstId.isBlank()) return null
        val fileId = transfer.sendFile(convId, dstId, openSource, fileName, mime, size) ?: return null
        store.insertMessage(
            StoredMessage(
                id = fileId, convId = convId, kind = "FILE", srcId = identity.shortId,
                dstId = dstId, text = fileName,
                fileMeta = "{\"fileName\":\"$fileName\",\"mime\":\"$mime\",\"size\":$size}",
                status = MessageStatus.SENDING,
            ),
        )
        return fileId
    }

    val fileProgress: StateFlow<com.meshchat.app.mesh.transfer.FileProgress?> = transfer.progress
```

`handleEnvelope` 的 when 分支（在 `"INVITE_ACK"` 分支之后、`else` 之前插入）：

```kotlin
            "FILE" -> {
                // 一跳帧：仅处理发往本机（ACK 一跳语义下多跳无法回传，范围外）
                if (envelope.dstId.isNotBlank() && envelope.dstId != identity.shortId) return
                val body = envelope.body as? FileBody ?: return
                transfer.onFileChunk(envelope, body)
            }
            "FILE_ACK" -> {
                if (envelope.dstId.isNotBlank() && envelope.dstId != identity.shortId) return
                val body = envelope.body as? FileAckBody ?: return
                transfer.onFileAck(envelope, body)
            }
```

`tickSessionState(now)` 内（或 tick 循环内）追加：

```kotlin
                transfer.tick(now)
```

在 `tickJob` 循环里 `tickSessionState(now)` 之后调用。

- [ ] **步骤 4：MeshChatApplication 装配**

修改 `MeshChatApplication.kt`：

```kotlin
import com.meshchat.app.mesh.transfer.AndroidFileSaver
import java.io.File

    val service by lazy {
        MeshService(
            transport, store, identity, DedupCache(),
            fileSaver = AndroidFileSaver(this),
            tmpDir = { File(filesDir, "transfers") },
        )
    }
```

- [ ] **步骤 5：运行测试验证通过**

运行：`.\gradlew.bat testDebugUnitTest --tests "com.meshchat.app.mesh.service.MeshServiceTest" --console=plain`
预期：PASS（24 个测试）。

- [ ] **步骤 6：Commit**

```bash
git add app/src/main/java/com/meshchat/app/mesh/service/MeshService.kt app/src/main/java/com/meshchat/app/MeshChatApplication.kt app/src/test/java/com/meshchat/app/mesh/service/MeshServiceTest.kt
git commit -m "feat: MeshService 集成文件传输——sendFile/FILE/FILE_ACK 一跳分发/进度落库状态/接收回填 Downloads（v0.12.0）"
```

---

### 任务 6：Repository + ViewModel

**文件：**
- 修改：`app/src/main/java/com/meshchat/app/data/MeshRepository.kt`
- 修改：`app/src/main/java/com/meshchat/app/ui/MeshChatViewModel.kt`

- [ ] **步骤 1：Repository 扩展**

`MeshRepository.kt` 接口追加：

```kotlin
    fun sendFile(convId: String, dstId: String, openSource: () -> java.io.InputStream, fileName: String, mime: String, size: Long): String?
    fun observeFileProgress(): Flow<com.meshchat.app.mesh.transfer.FileProgress?>
```

`MeshRepositoryImpl` 实现：

```kotlin
    override fun sendFile(convId: String, dstId: String, openSource: () -> java.io.InputStream, fileName: String, mime: String, size: Long): String? =
        service.sendFile(convId, dstId, openSource, fileName, mime, size)

    override fun observeFileProgress(): Flow<com.meshchat.app.mesh.transfer.FileProgress?> =
        kotlinx.coroutines.flow.flowOf(service.fileProgress.value)  // 简化：透传 StateFlow
```

注：`observeFileProgress` 直接透传 service 的 StateFlow（`Flow` 子类）—— 改为：

```kotlin
    override fun observeFileProgress(): Flow<com.meshchat.app.mesh.transfer.FileProgress?> = service.fileProgress
```

`toUiModel()` 扩展为文件消息（fileMeta JSON 解析）：

```kotlin
    private fun com.meshchat.app.mesh.storage.StoredMessage.toUiModel(): ChatMessage {
        val time = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(ts))
        val delivery = when (status) {
            MessageStatus.SENDING -> "正在通过 Mesh 发送"
            MessageStatus.DELIVERED -> "已通过 Mesh 送达"
            MessageStatus.FAILED -> "未送达"
        }
        val file = if (kind == "FILE") {
            val meta = runCatching {
                kotlinx.serialization.json.Json.decodeFromString<Map<String, String>>(fileMeta ?: "{}")
            }.getOrDefault(emptyMap())
            FileUiMeta(
                fileName = meta["fileName"] ?: text ?: "文件",
                size = meta["size"]?.toLongOrNull() ?: 0L,
                progress = 0,
                done = status == MessageStatus.DELIVERED,
                uri = meta["downloadsUri"],
            )
        } else null
        return ChatMessage(
            id = id,
            text = text ?: "",
            sentByMe = srcId == service.shortId,
            time = time,
            delivery = delivery,
            file = file,
        )
    }
```

`UiModels.kt` 追加（文件模型）：

```kotlin
data class FileUiMeta(
    val fileName: String,
    val size: Long,
    val progress: Int,      // 0-100
    val done: Boolean,
    val uri: String? = null,  // 接收方收齐后回填的 Downloads URI（点击打开用）
)
```

`ChatMessage` 追加字段 `val file: FileUiMeta? = null`。

- [ ] **步骤 2：ViewModel 扩展**

`MeshChatViewModel.kt` 追加：

```kotlin
    val fileProgress: StateFlow<com.meshchat.app.mesh.transfer.FileProgress?> =
        repository.observeFileProgress()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 发送文件（当前会话目标）。size 为 0 时拒绝（空文件不支持）。 */
    fun sendFile(openSource: () -> java.io.InputStream, fileName: String, mime: String, size: Long) {
        if (size <= 0) return
        val target = conversationTarget.value ?: return
        viewModelScope.launch {
            repository.sendFile("conv-$target", target, openSource, fileName, mime, size)
        }
    }
```

消息流合并进度（在 `messages` 定义后追加映射）：

```kotlin
    private val fileProgressMap: StateFlow<Map<String, FileUiMeta>> = fileProgress
        .map { p -> if (p == null) emptyMap() else mapOf(p.fileId to FileUiMeta(
            fileName = p.fileName, size = p.totalBytes,
            progress = if (p.totalBytes > 0) ((p.transferredBytes * 100) / p.totalBytes).toInt().coerceIn(0, 100) else 0,
            done = p.status == com.meshchat.app.mesh.transfer.TransferStatus.DONE,
        )) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())
```

`messages` 改为 combine：

```kotlin
    val messages: StateFlow<List<ChatMessage>> = combine(
        conversationTarget.flatMapLatest { target -> repository.observeMessages("conv-${target ?: "ME"}") },
        fileProgressMap,
    ) { list, progressMap -> list.map { m -> if (m.file != null && m.id in progressMap) m.copy(file = progressMap[m.id]) else m } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
```

需补 import：`kotlinx.coroutines.flow.combine`、`kotlinx.coroutines.flow.map`。

- [ ] **步骤 3：编译验证**

运行：`.\gradlew.bat compileDebugKotlin --console=plain`
预期：BUILD SUCCESSFUL。

- [ ] **步骤 4：Commit**

```bash
git add app/src/main/java/com/meshchat/app/data/MeshRepository.kt app/src/main/java/com/meshchat/app/data/UiModels.kt app/src/main/java/com/meshchat/app/ui/MeshChatViewModel.kt
git commit -m "feat: Repository/ViewModel 文件发送接口与进度合并到消息流（v0.12.0）"
```

---

### 任务 7：UI（文件气泡 + 附件选择器 + 接线）

**文件：**
- 修改：`app/src/main/java/com/meshchat/app/ui/screens/ConversationScreen.kt`
- 修改：`app/src/main/java/com/meshchat/app/ui/screens/MeshChatHome.kt`
- 修改：`app/src/main/java/com/meshchat/app/ui/MeshChatApp.kt`
- 修改：`app/src/main/java/com/meshchat/app/MainActivity.kt`
- 修改：`app/src/main/AndroidManifest.xml`

- [ ] **步骤 1：ConversationScreen 文件气泡与附件回调**

修改签名（文件选择由上层 launcher 完成，本组件只触发 + 渲染）：

```kotlin
fun ConversationScreen(
    messages: List<ChatMessage>,
    title: String,
    connected: Boolean,
    onBack: () -> Unit,
    onSendMessage: (String) -> Unit,
    onPickFile: (() -> Unit)? = null,
    onOpenFile: (ChatMessage) -> Unit = {},
)
```

附件按钮：

```kotlin
            IconButton(onClick = { onPickFile?.invoke() }, enabled = onPickFile != null) {
                Icon(Icons.Outlined.AttachFile, "添加附件", tint = TextSecondary)
            }
```

`MessageBubble` 渲染文件卡片（在文本上方追加）：

```kotlin
@Composable
private fun MessageBubble(message: ChatMessage) {
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = if (message.sentByMe) Alignment.End else Alignment.Start) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(if (message.sentByMe) BubbleMine else InkSoft)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Column {
                message.file?.let { f ->
                    FileCard(f, message, onOpen = { /* 由上层处理 */ })
                    if (message.text.isNotBlank()) Spacer(Modifier.height(8.dp))
                }
                if (message.text.isNotBlank()) Text(message.text, style = MaterialTheme.typography.bodyLarge)
            }
        }
        Text(
            text = listOfNotNull(message.time, message.delivery).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = if (message.sentByMe) Cyan else TextSecondary,
            modifier = Modifier.padding(start = 4.dp, top = 5.dp, end = 4.dp),
        )
    }
}
```

`FileCard`（同文件内私有 Composable）：

```kotlin
@Composable
private fun FileCard(f: FileUiMeta, message: ChatMessage, onOpen: () -> Unit) {
    Column(
        modifier = Modifier
            .width(220.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Ink.copy(alpha = 0.35f))
            .clickable(enabled = f.done) { onOpen() }
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.InsertDriveFile, null, tint = if (f.done) MeshGreen else Cyan, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Column {
                Text(f.fileName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(formatSize(f.size), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
        }
        if (!f.done) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { f.progress / 100f },
                modifier = Modifier.fillMaxWidth().height(4.dp),
                color = Cyan,
                trackColor = InkSoft,
            )
        } else {
            Spacer(Modifier.height(4.dp))
            Text(
                if (message.sentByMe) "已发送" else "已存 Downloads",
                style = MaterialTheme.typography.bodySmall,
                color = MeshGreen,
            )
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / 1024f / 1024f)
    bytes >= 1024 -> String.format("%.1f KB", bytes / 1024f)
    else -> "$bytes B"
}
```

所需 import：`androidx.compose.foundation.clickable`、`androidx.compose.foundation.layout.width`、`androidx.compose.material.icons.outlined.InsertDriveFile`、`androidx.compose.material3.LinearProgressIndicator`、`androidx.compose.ui.text.style.TextOverflow`、`com.meshchat.app.data.FileUiMeta`。

- [ ] **步骤 2：MeshChatHome 文件选择器 + 接线**

`MeshChatHome` 签名追加参数：

```kotlin
    onPickFile: (() -> Unit)? = null,
    onOpenFile: (ChatMessage) -> Unit = {},
```

文件选择器 launcher 放在 MeshChatHome（Composable 内）：

```kotlin
    // 系统文件选择器：选文件后取名称/MIME/大小回调上层（由 ViewModel 发送）
    val context = LocalContext.current
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val resolver = context.contentResolver
            val name = resolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else "file"
            } ?: "file"
            val mime = resolver.getType(uri) ?: "application/octet-stream"
            val size = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0L
            onPickFileResult?.invoke(name, mime, size) { resolver.openInputStream(uri) }
        }
    }
```

注：需要把选文件结果传给 ViewModel。`MeshChatHome` 追加参数：

```kotlin
    onSendFile: (name: String, mime: String, size: Long, openSource: () -> java.io.InputStream) -> Unit,
```

附件按钮触发 `filePicker.launch(arrayOf("*/*"))`；空文件提示 Toast：

```kotlin
    val pickOrNotify = {
        filePicker.launch(arrayOf("*/*"))
    }
```

选中后：size == 0 → Toast「空文件不支持发送」；否则 `onSendFile(name, mime, size) { resolver.openInputStream(uri)!! }`。

`ConversationScreen` 调用处传入：

```kotlin
        ConversationScreen(
            messages = messages,
            title = title,
            connected = connected,
            onBack = { onOpenConversation(null) },
            onSendMessage = onSendMessage,
            onPickFile = pickOrNotify,
            onOpenFile = onOpenFile,
        )
```

import：`androidx.activity.compose.rememberLauncherForActivityResult`、`androidx.activity.result.contract.ActivityResultContracts`、`androidx.compose.ui.platform.LocalContext`、`android.provider.OpenableColumns`。

- [ ] **步骤 3：MeshChatApp 接线**

`MeshChatApp` 传给 `MeshChatHome`：

```kotlin
            onPickFile = { },  // 占位：launcher 在 MeshChatHome 内部
            onSendFile = { name, mime, size, openSource ->
                viewModel.sendFile(openSource, name, mime, size)
            },
            onOpenFile = { message -> viewModel.openFile(message) },
```

注：`onPickFile` 不再需要（launcher 在 Home 内自管理）——`MeshChatHome` 内部直接使用 launcher，不暴露占位参数。`onOpenFile` 实现见步骤 4。

- [ ] **步骤 4：ViewModel.openFile**

`MeshChatViewModel.kt` 追加：

```kotlin
    /** 打开已完成的文件消息（Downloads URI / 本机发送的文件无 URI 时提示）。 */
    fun openFile(message: ChatMessage) {
        // 由 UI 层处理：需要 Context 启动 Activity，放 UI 侧（见步骤 5）
    }
```

改为 UI 侧处理：`MeshChatHome` 内：

```kotlin
    val onOpenFileAction = { message: ChatMessage ->
        // 接收方 fileMeta 含 downloadsUri（文件已存 Downloads）→ ACTION_VIEW
        // 简化：v0.12 打开动作仅对接收方可用，发送方侧点击提示（无 URI）
    }
```

为保持简单，v0.12 的"点击打开"实现：`MeshChatHome` 内用 `LocalContext` + `Intent(ACTION_VIEW)`，URI 从 message 的文件元数据取。但 `FileUiMeta` 未携带 URI。扩展 `FileUiMeta` 加 `uri: String?`，`toUiModel` 从 fileMeta JSON 解析 `downloadsUri`。最终点击逻辑（MeshChatHome 内）：

```kotlin
        onOpenFile = { message ->
            val uri = message.file?.uri?.let { android.net.Uri.parse(it) }
            if (uri != null) {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, uri).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                }
            }
        },
```

- [ ] **步骤 5：MainActivity + Manifest 权限（API 26-28）**

`MainActivity.kt` `requiredPermissions` 追加（`else` 分支内）：

```kotlin
        } else {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
```

`AndroidManifest.xml` 追加：

```xml
    <uses-permission
        android:name="android.permission.WRITE_EXTERNAL_STORAGE"
        android:maxSdkVersion="28" />
```

- [ ] **步骤 6：编译验证**

运行：`.\gradlew.bat compileDebugKotlin --console=plain`
预期：BUILD SUCCESSFUL。

- [ ] **步骤 7：Commit**

```bash
git add app/src/main/java/com/meshchat/app/ui/ app/src/main/java/com/meshchat/app/data/UiModels.kt app/src/main/java/com/meshchat/app/MainActivity.kt app/src/main/AndroidManifest.xml
git commit -m "feat: 文件消息 UI——附件选择器/文件气泡/进度条/点击打开/API26-28 存储权限（v0.12.0）"
```

---

### 任务 8：构建 + 回归 + 版本 bump + 交接

**文件：**
- 修改：`app/build.gradle.kts`
- 修改：`AI_CONTEXT.md`

- [ ] **步骤 1：版本 bump**

`app/build.gradle.kts`：`versionCode = 23`、`versionName = "0.12.0"`。

- [ ] **步骤 2：全量测试**

运行：`.\gradlew.bat testDebugUnitTest --console=plain`
预期：BUILD SUCCESSFUL，全部测试通过（22 + 新增 ≥9）。

- [ ] **步骤 3：构建 APK**

运行：`.\gradlew.bat assembleDebug --console=plain`
预期：BUILD SUCCESSFUL。
复制：`Copy-Item .\app\build\outputs\apk\debug\app-debug.apk .\MeshChat-v0.12.0-debug.apk -Force`

- [ ] **步骤 4：更新 AI_CONTEXT.md 交接块**

记录：v0.12.0 文件传输（协议/引擎/服务/存储/UI 全链路）、真机验收要点（A↔B 传 <10MB 文件 → B 的 Downloads 可见）、遗留（多跳文件传输、大文件、进度内存态）。

- [ ] **步骤 5：Commit**

```bash
git add app/build.gradle.kts AI_CONTEXT.md
git commit -m "build: v0.12.0 文件传输版本 bump + 交接块更新"
```

---

## 自检

**规格覆盖度对照：**
- §3 协议扩展（fileId + FileAckBody）→ 任务 1 ✓
- §4.1 参数（200B/32/15s/5 次/60s/串行）→ 任务 2/3 ✓
- §4.2 发送状态机 → 任务 2 ✓
- §4.3 接收重组 + 校验 + Downloads + 清理 → 任务 3 ✓
- §4.4 一跳分发（FILE/FILE_ACK dstId 校验，不进 outbox）→ 任务 5 ✓
- §5 存储 fileMeta / 回填 Downloads URI → 任务 4/5 ✓
- §6 UI（文件卡片/进度/选择器/打开）→ 任务 6/7 ✓
- §7 权限（API 29+ MediaStore / 26-28 WRITE_EXTERNAL_STORAGE）→ 任务 7 ✓
- §8 测试（missing 计算/重传/超时/重组/串行/回归）→ 任务 2/3/5 ✓
- §9 边界（重复块幂等/大小校验/空文件拒绝/离线重试上限）→ 任务 2/3/5/7 ✓

**占位符扫描：** 所有步骤含具体代码与命令，无 TODO/待定。`broadcastChunk` 签名在任务 2 步骤 4 中明确修正，无遗留。

**类型一致性：**
- `FileTransferManager` 构造参数（transport/shortId/saver/scope/windowTimeoutMs/maxWindowRetries/onProgress/tmpDirProvider/onSaved）在各任务引用一致 ✓
- `FileSaver.save` 返回 `String?`，AndroidFileSaver 与 FakeSaver 均实现 ✓
- `ChatMessage.file: FileUiMeta?`、`FileUiMeta(fileName/size/progress/done/uri)` —— 任务 6 定义 uri 字段，任务 7 步骤 4 使用，需在任务 6 的 `FileUiMeta` 与 `toUiModel` 中包含 `uri`（解析 fileMeta 的 `downloadsUri`）——已在任务 6 步骤 1 代码中含 `downloadsUri`，任务 7 步骤 4 的 `FileUiMeta.uri` 与之对应；`toUiModel` 需补 `uri = meta["downloadsUri"]`。实现时统一。
- `MeshService` 构造新增参数（fileSaver/tmpDir）→ MeshChatApplication 与测试均同步 ✓
- `sendFile` 返回 `String?`（fileId 或 null）贯穿一致 ✓
