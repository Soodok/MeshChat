# MeshChat RFCOMM 高吞吐传输实现计划（v0.13.0）

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 为 MeshChat 增加经典蓝牙 RFCOMM 高吞吐传输载体，文件数据走 RFCOMM（100-300 KB/s），BLE 保留发现/握手/聊天，吞吐提升 10-50 倍。

**架构：** 新增 `RfcommTransport`（实现 `MeshTransport`：服务端 listen+accept、客户端 connect+配对、peerId→socket 映射、4 字节长度前缀分帧）；MeshService 集成双传输——incoming 合并、会话建立后自动连 RFCOMM、文件帧 `sendFrame` 路由（RFCOMM 优先，BLE 兜底）。

**技术栈：** Kotlin + android.bluetooth（BluetoothServerSocket/BluetoothSocket/BluetoothDevice）+ kotlinx.coroutines + 现有 MeshFrame/MeshService/FileTransferManager。

**规格：** `docs/superpowers/specs/2026-08-03-meshchat-rfcomm-transport-design.md`

---

## 文件结构

**新建：**
- `app/src/main/java/com/meshchat/app/mesh/transport/RfcommFraming.kt` — 流分帧（writeFrame/readFrame/readFully，纯 JVM 可测）
- `app/src/main/java/com/meshchat/app/mesh/transport/RfcommTransport.kt` — RFCOMM 载体（Android 层）
- `app/src/test/java/com/meshchat/app/mesh/transport/RfcommFramingTest.kt` — 分帧测试

**修改：**
- `app/src/main/java/com/meshchat/app/mesh/transfer/FileTransferManager.kt` — 构造注入 `sendFrame`，广播/ACK 改走 sendFrame
- `app/src/main/java/com/meshchat/app/mesh/service/MeshService.kt` — rfcomm 注入、incoming 合并、会话后自动连接、sendFrame 路由
- `app/src/main/java/com/meshchat/app/MeshChatApplication.kt` — 装配 RfcommTransport
- `app/src/test/java/com/meshchat/app/mesh/transfer/FileTransferManagerTest.kt` — sendFrame 注入测试
- `app/src/test/java/com/meshchat/app/mesh/service/MeshServiceTest.kt` — sendFrame 路由测试
- `app/build.gradle.kts` — versionCode/Name bump

**测试命令：** `.\gradlew.bat testDebugUnitTest` 与 `.\gradlew.bat assembleDebug`。

---

### 任务 1：流分帧核心（RfcommFraming + 测试）

**文件：**
- 创建：`app/src/main/java/com/meshchat/app/mesh/transport/RfcommFraming.kt`
- 测试：`app/src/test/java/com/meshchat/app/mesh/transport/RfcommFramingTest.kt`

- [ ] **步骤 1：编写失败的测试**

创建 `RfcommFramingTest.kt`：

```kotlin
package com.meshchat.app.mesh.transport

import com.meshchat.app.mesh.protocol.FrameType
import com.meshchat.app.mesh.protocol.MeshFrame
import java.io.PipedInputStream
import java.io.PipedOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RfcommFramingTest {
    private fun pipePair(): Pair<PipedInputStream, PipedOutputStream> {
        val out = PipedOutputStream()
        val input = PipedInputStream(out, 8192)
        return input to out
    }

    @Test
    fun `frames roundtrip through stream`() {
        val (input, out) = pipePair()
        val frames = listOf(
            MeshFrame(FrameType.DATA, "{\"a\":1}".toByteArray()),
            MeshFrame(FrameType.DATA, ByteArray(300) { 7 }),
            MeshFrame(FrameType.RECEIPT, "x".toByteArray()),
        )
        frames.forEach { RfcommFraming.writeFrame(out, it) }
        frames.forEach { assertEquals(it, RfcommFraming.readFrame(input)) }
    }

    @Test
    fun `returns null when stream closed between frames`() {
        val (input, out) = pipePair()
        RfcommFraming.writeFrame(out, MeshFrame(FrameType.DATA, "abc".toByteArray()))
        out.close()
        val first = RfcommFraming.readFrame(input)
        assertEquals("abc", first?.payloadText)
        assertNull(RfcommFraming.readFrame(input))
    }

    @Test
    fun `empty stream returns null`() {
        val (input, out) = pipePair()
        out.close()
        assertNull(RfcommFraming.readFrame(input))
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`.\gradlew.bat testDebugUnitTest --tests "com.meshchat.app.mesh.transport.RfcommFramingTest" --console=plain`
预期：编译失败（RfcommFraming 不存在）。

- [ ] **步骤 3：实现分帧**

创建 `RfcommFraming.kt`：

```kotlin
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
```

注：`MeshFrame.decode(payload)` 若不存在，改为 `MeshFrame(FrameType.DATA, payload)` 并检查 `MeshFrame` 的 decode 方法（见任务 1 步骤 3 末尾自查：`MeshFrame` 有 `decode(ByteArray)` 则用之，否则用 `runCatching { MeshFrame(FrameType.DATA, payload) }`）。

- [ ] **步骤 4：运行测试验证通过**

运行：`.\gradlew.bat testDebugUnitTest --tests "com.meshchat.app.mesh.transport.RfcommFramingTest" --console=plain`
预期：PASS（3 个测试）。

- [ ] **步骤 5：Commit**

```bash
git add app/src/main/java/com/meshchat/app/mesh/transport/RfcommFraming.kt app/src/test/java/com/meshchat/app/mesh/transport/RfcommFramingTest.kt
git commit -m "feat: RFCOMM 流分帧——4 字节长度前缀写读 + readFully 循环读满（v0.13.0）"
```

---

### 任务 2：RfcommTransport 实现

**文件：**
- 创建：`app/src/main/java/com/meshchat/app/mesh/transport/RfcommTransport.kt`

- [ ] **步骤 1：实现 RfcommTransport**

创建 `RfcommTransport.kt`：

```kotlin
package com.meshchat.app.mesh.transport

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import com.meshchat.app.mesh.protocol.MeshFrame
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 经典蓝牙 RFCOMM 高吞吐载体：文件数据传输通道（BLE 发现/握手保留）。
 * 服务端 listen+accept；客户端 connect(address)（自动配对）；peerId→socket 映射；4 字节长度前缀分帧。
 */
class RfcommTransport(
    private val context: Context,
    private val sdpUuid: UUID = UUID.fromString("0000A5E3-0000-1000-8000-00805F9B34FB"),
) : MeshTransport {
    companion object {
        private const val TAG = "MeshRfcomm"
        private const val SERVICE_NAME = "MeshChat"
        private const val BOND_TIMEOUT_MS = 15_000L
        private const val CONNECT_TIMEOUT_MS = 10_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = runCatching { bluetoothManager.adapter }.getOrNull()

    private val _incoming = MutableSharedFlow<MeshFrame>(extraBufferCapacity = 64)
    override val incoming: SharedFlow<MeshFrame> = _incoming
    override val foundPeers: SharedFlow<MeshPeerInfo> = MutableSharedFlow()

    /** peerId → (socket, 读协程是否活跃)。写需按 socket 加锁（多协程并发写会交错）。 */
    private val sockets = ConcurrentHashMap<String, Pair<BluetoothSocket, Any>>()
    private var serverSocket: BluetoothServerSocket? = null

    override fun start() {
        if (adapter == null || !adapter.isEnabled) { Log.w(TAG, "classic bluetooth unavailable, rfcomm disabled"); return }
        scope.launch { acceptLoop() }
    }

    override fun stop() {
        sockets.forEach { (_, pair) -> runCatching { pair.first.close() } }
        sockets.clear()
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private suspend fun acceptLoop() {
        val server = runCatching { adapter?.listenUsingRfcommWithServiceRecord(SERVICE_NAME, sdpUuid) }
            .getOrNull() ?: run { Log.w(TAG, "listen failed"); return }
        serverSocket = server
        while (isActive) {
            val socket = runCatching { server.accept() }.getOrNull() ?: break
            scope.launch { readLoop(socket, address = socket.remoteDevice.address) }
        }
    }

    /** 客户端主动连接（会话建立后由 MeshService 调用）：peerId 用于寻址，address 为经典蓝牙 MAC。 */
    suspend fun connect(peerId: String, address: String): Boolean {
        val device = adapter?.getRemoteDevice(address) ?: return false
        if (!ensureBonded(device)) { Log.w(TAG, "bond failed for $address"); return false }
        val socket = runCatching {
            val s = device.createRfcommSocketToServiceRecord(sdpUuid)
            val started = java.util.concurrent.CompletableFuture.supplyAsync {
                s.connect()
            }
            started.get(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            s
        }.getOrNull() ?: run { Log.w(TAG, "connect failed for $address"); return false }
        sockets[peerId] = socket to Any()
        Log.d(TAG, "connected peer=$peerId addr=$address")
        scope.launch { readLoop(socket, peerId) }
        return true
    }

    fun isConnectedTo(peerId: String): Boolean = sockets.containsKey(peerId)

    override fun sendTo(peerId: String, frame: MeshFrame) {
        val pair = sockets[peerId] ?: run { Log.w(TAG, "no rfcomm socket for $peerId"); return }
        try {
            synchronized(pair.second) {
                RfcommFraming.writeFrame(pair.first.outputStream, frame)
            }
        } catch (e: Exception) {
            Log.w(TAG, "write failed for $peerId: $e")
            sockets.remove(peerId)
            runCatching { pair.first.close() }
        }
    }

    override fun broadcast(frame: MeshFrame) {
        sockets.keys.forEach { sendTo(it, frame) }
    }

    private suspend fun readLoop(socket: BluetoothSocket, peerId: String?) {
        val input: InputStream = runCatching { socket.inputStream }.getOrNull() ?: return
        while (isActive) {
            val frame = runCatching { RfcommFraming.readFrame(input) }.getOrNull() ?: break
            _incoming.emit(frame)
        }
        runCatching { socket.close() }
        if (peerId != null) sockets.remove(peerId)
        Log.d(TAG, "socket closed peer=$peerId addr=${socket.remoteDevice.address}")
    }

    /** 确保已配对：未配对则 createBond + 等待 ACTION_BOND_STATE_CHANGED（系统配对弹窗由用户确认）。 */
    private suspend fun ensureBonded(device: BluetoothDevice): Boolean {
        if (device.bondState == BluetoothDevice.BOND_BONDED) return true
        val latch = CountDownLatch(1)
        var result = false
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
                if (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1) == BluetoothDevice.BOND_BONDED) {
                    result = true; latch.countDown()
                } else if (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1) == BluetoothDevice.BOND_NONE) {
                    latch.countDown()
                }
            }
        }
        context.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
        val ok = runCatching { device.createBond() }.getOrDefault(false)
        if (!ok) { context.unregisterReceiver(receiver); return false }
        val done = latch.await(BOND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        context.unregisterReceiver(receiver)
        return done && result
    }
}
```

- [ ] **步骤 2：编译验证**

运行：`.\gradlew.bat compileDebugKotlin --console=plain`
预期：BUILD SUCCESSFUL。

- [ ] **步骤 3：Commit**

```bash
git add app/src/main/java/com/meshchat/app/mesh/transport/RfcommTransport.kt
git commit -m "feat: RfcommTransport——服务端 listen/accept、客户端 connect 自动配对、peerId→socket 映射、分帧读写（v0.13.0）"
```

---

### 任务 3：FileTransferManager sendFrame 注入

**文件：**
- 修改：`app/src/main/java/com/meshchat/app/mesh/transfer/FileTransferManager.kt`
- 测试：`app/src/test/java/com/meshchat/app/mesh/transfer/FileTransferManagerTest.kt`

- [ ] **步骤 1：编写失败的测试**

在 `FileTransferManagerTest.kt` 追加：

```kotlin
@Test
fun `chunks are sent through injected sendFrame instead of broadcast`() = runTest {
    val transport = CountingTransport()
    val sentVia = mutableListOf<Pair<String, MeshFrame>>()
    val manager = FileTransferManager(
        transport = transport, shortId = "A", saver = FakeSaver(kotlin.io.path.createTempDirectory("sf").toFile()),
        scope = backgroundScope, windowTimeoutMs = 5_000, maxWindowRetries = 3,
        sendFrame = { dst, frame -> sentVia.add(dst to frame) },
    )
    val bytes = ByteArray(FileTransferManager.CHUNK_BYTES * 8) { 5 }
    manager.sendFile("conv-B", "B", { ByteArrayInputStream(bytes) }, "f.bin", "application/octet-stream", bytes.size.toLong())
    awaitChunks(transport, 0)   // 不依赖 broadcast；等 sendFrame 收到首窗
    var guard = 0
    while (sentVia.size < 8 && guard++ < 100) kotlinx.coroutines.delay(20)
    assertTrue("sendFrame 应被调用 8 次", sentVia.size >= 8)
    assertTrue(sentVia.all { it.first == "B" })
    // 全部窗口完成后停发
    while (manager.progress.value?.status != TransferStatus.DONE && guard++ < 100) {
        manager.onFileAck(ack(manager.progress.value?.fileId ?: "x", 8, emptyList()))
        kotlinx.coroutines.delay(20)
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`.\gradlew.bat testDebugUnitTest --tests "com.meshchat.app.mesh.transfer.FileTransferManagerTest.sendFrame" --console=plain`
预期：编译失败（FileTransferManager 无 sendFrame 参数）。

- [ ] **步骤 3：实现 sendFrame 注入**

修改 `FileTransferManager.kt` 构造：

```kotlin
class FileTransferManager(
    private val transport: MeshTransport,
    private val shortId: String,
    private val saver: FileSaver,
    private val scope: CoroutineScope,
    private val windowTimeoutMs: Long = WINDOW_TIMEOUT_MS,
    private val maxWindowRetries: Int = MAX_WINDOW_RETRIES,
    private val tmpDirProvider: () -> File = { File(System.getProperty("java.io.tmpdir"), "meshchat_transfers") },
    private val onProgress: (FileProgress) -> Unit = {},
    private val onSaved: (convId: String, fileId: String, fileName: String, mime: String, size: Long, uri: String?) -> Unit = { _, _, _, _, _, _ -> },
    /** 文件帧发送通道：RFCOMM 连接时走 sendTo，否则回退 broadcast（由 MeshService 注入）。 */
    private val sendFrame: (dstId: String, frame: MeshFrame) -> Unit = { _, frame -> transport.broadcast(frame) },
) {
```

`broadcastChunk` 改：

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
        sendFrame(s.dstId, MeshFrame(FrameType.DATA, MeshJson.encodeEnvelope(envelope).toByteArray()))
    }
```

`sendAck` 改：

```kotlin
    private fun sendAck(s: ReceiveSession, final: Boolean = false) {
        val ack = MeshEnvelope(
            ...
            body = FileAckBody(fileId = s.fileId, totalChunks = s.totalChunks,
                missing = if (final) emptyList() else s.missing.take(MAX_ACK_MISSING)),
        )
        sendFrame(s.senderId, MeshFrame(FrameType.DATA, MeshJson.encodeEnvelope(ack).toByteArray()))
    }
```

- [ ] **步骤 4：运行测试验证通过**

运行：`.\gradlew.bat testDebugUnitTest --tests "com.meshchat.app.mesh.transfer.FileTransferManagerTest" --console=plain`
预期：PASS（新增 sendFrame 测试 + 原有全过）。

- [ ] **步骤 5：Commit**

```bash
git add app/src/main/java/com/meshchat/app/mesh/transfer/FileTransferManager.kt app/src/test/java/com/meshchat/app/mesh/transfer/FileTransferManagerTest.kt
git commit -m "feat: FileTransferManager sendFrame 注入——文件帧/ACK 可走 RFCOMM 通道（v0.13.0）"
```

---

### 任务 4：MeshService 集成双传输

**文件：**
- 修改：`app/src/main/java/com/meshchat/app/mesh/service/MeshService.kt`
- 测试：`app/src/test/java/com/meshchat/app/mesh/service/MeshServiceTest.kt`

- [ ] **步骤 1：编写失败的测试**

在 `MeshServiceTest.kt` 追加（验证 sendFrame 路由：RFCOMM 连接时走 sendTo 而非 broadcast）：

```kotlin
@Test
fun `file chunk goes through rfcomm sendTo when connected`() = runTest {
    val identity = LocalIdentity(shortId = "ME")
    val transport = CountingTransport()
    val store = InMemoryMeshStore()
    val rfcommSent = mutableListOf<Pair<String, MeshFrame>>()
    val rfcomm = object : FakeRfcomm {
        override fun isConnectedTo(peerId: String) = peerId == "OTHER"
        override fun sendTo(peerId: String, frame: MeshFrame) { rfcommSent.add(peerId to frame) }
    }
    val service = MeshService(
        transport = transport, store = store, identity = identity, dedup = DedupCache(),
        rfcomm = rfcomm,
    )
    service.sendFile("conv-OTHER", "OTHER", { ByteArrayInputStream(ByteArray(100) { 1 }) }, "f.txt", "text/plain", 100)
    var guard = 0
    while (rfcommSent.isEmpty() && transport.broadcastCount == 0 && guard++ < 100) kotlinx.coroutines.delay(20)
    assertTrue("RFCOMM 连接时应走 sendTo 而非 BLE broadcast", rfcommSent.isNotEmpty())
    assertEquals("OTHER", rfcommSent.first().first)
    service.stop()
}
```

`MeshServiceTest.kt` 顶部追加接口定义：

```kotlin
/** MeshService 依赖的 RFCOMM 最小接口（可测替身）。 */
interface FakeRfcomm {
    fun isConnectedTo(peerId: String): Boolean
    fun sendTo(peerId: String, frame: MeshFrame)
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`.\gradlew.bat testDebugUnitTest --tests "com.meshchat.app.mesh.service.MeshServiceTest.file chunk goes through rfcomm" --console=plain`
预期：编译失败（MeshService 无 rfcomm 参数 / sendFile 不走 rfcomm）。

- [ ] **步骤 3：MeshService 集成**

修改 `MeshService.kt`：

构造新增参数：

```kotlin
    private val fileSaver: FileSaver = object : FileSaver { override fun save(tmpFile: File, fileName: String, mime: String): String? = null },
    private val tmpDir: () -> File = { File(System.getProperty("java.io.tmpdir"), "meshchat_transfers") },
    /** RFCOMM 高吞吐通道（可选）：文件帧优先走它，无连接回退 BLE broadcast。 */
    private val rfcomm: RfcommTransport? = null,
) {
```

`start()` 中追加（receiveJob 之后）：

```kotlin
        rfcomm?.start()
        rfcomm?.incoming?.let { flow ->
            scope.launch { flow.catch { }.collect { frame -> handleFrame(frame) } }
        }
```

`stop()` 中追加：

```kotlin
        rfcomm?.stop()
```

新增 sendFrame 路由（文件传输专用）：

```kotlin
    /** 文件帧发送路由：RFCOMM 已连接则走高速通道，否则 BLE broadcast 兜底。 */
    private fun sendFrame(dstId: String, frame: MeshFrame) {
        if (rfcomm != null && rfcomm.isConnectedTo(dstId)) rfcomm.sendTo(dstId, frame)
        else transport.broadcast(frame)
    }
```

`FileTransferManager` 构造传入 sendFrame：

```kotlin
    private val transfer = FileTransferManager(
        transport = transport, shortId = identity.shortId, saver = fileSaver,
        scope = scope, tmpDirProvider = tmpDir,
        sendFrame = { dstId, frame -> sendFrame(dstId, frame) },
        onProgress = { ... },
        onSaved = { ... },
    )
```

`sendFile` 不变（transfer.sendFile 内部已走 sendFrame）。

会话建立后自动连接 RFCOMM（INVITE_ACK 首次建立 session 分支追加）：

```kotlin
                if (firstTime) sendInviteAck(envelope.srcId)
                // 会话建立 → 尝试建立 RFCOMM 高速通道（文件传输用）；失败静默回退 BLE
                connectRfcomm(envelope.srcId)
```

```kotlin
    /** 会话建立后按 BLE 扫描到的对端 MAC 发起 RFCOMM 连接（配对弹窗由系统处理）。 */
    private fun connectRfcomm(peerId: String) {
        val rf = rfcomm ?: return
        if (rf.isConnectedTo(peerId)) return
        val address = _peers.value.firstOrNull { it.shortId == peerId }?.deviceAddress ?: return
        scope.launch {
            Log.d(TAG, "rfcomm connect attempt peer=$peerId addr=$address")
            rf.connect(peerId, address)
        }
    }
```

`_peers` 需要暴露 `peers`（已有 `peers: StateFlow<List<MeshPeerInfo>>`）——直接用 `_peers.value`。

- [ ] **步骤 4：运行测试验证通过**

运行：`.\gradlew.bat testDebugUnitTest --tests "com.meshchat.app.mesh.service.MeshServiceTest" --console=plain`
预期：PASS（新增 rfcomm 路由测试 + 原有全过）。

- [ ] **步骤 5：Commit**

```bash
git add app/src/main/java/com/meshchat/app/mesh/service/MeshService.kt app/src/test/java/com/meshchat/app/mesh/service/MeshServiceTest.kt
git commit -m "feat: MeshService 双传输集成——RFCOMM incoming 合并、会话后自动连接、文件帧 sendFrame 路由（v0.13.0）"
```

---

### 任务 5：装配 + 构建 + 版本 bump + 交接

**文件：**
- 修改：`app/src/main/java/com/meshchat/app/MeshChatApplication.kt`
- 修改：`app/build.gradle.kts`
- 修改：`AI_CONTEXT.md`

- [ ] **步骤 1：装配**

`MeshChatApplication.kt`：

```kotlin
import com.meshchat.app.mesh.transport.RfcommTransport

    val rfcomm by lazy { RfcommTransport(this) }
    val service by lazy {
        MeshService(
            transport, store, identity, DedupCache(),
            fileSaver = AndroidFileSaver(this),
            tmpDir = { File(filesDir, "transfers") },
            rfcomm = rfcomm,
        )
    }
```

- [ ] **步骤 2：版本 bump**

`app/build.gradle.kts`：`versionCode = 24`、`versionName = "0.13.0"`。

- [ ] **步骤 3：全量测试 + 构建**

运行：`.\gradlew.bat testDebugUnitTest assembleDebug --console=plain`
预期：BUILD SUCCESSFUL，全部测试通过（34 + 新增 ≥5）。
复制：`Copy-Item .\app\build\outputs\apk\debug\app-debug.apk .\MeshChat-v0.13.0-debug.apk -Force`

- [ ] **步骤 4：更新 AI_CONTEXT.md 交接块**

记录：v0.13.0 RFCOMM 高吞吐载体（分帧/transport/双传输集成/会话后自动连接/文件帧路由）、真机验收要点（传 20KB 秒级、配对弹窗一次、断连回退 BLE）、遗留（聊天仍走 BLE、多跳 RFCOMM 不做）。

- [ ] **步骤 5：Commit**

```bash
git add app/build.gradle.kts AI_CONTEXT.md app/src/main/java/com/meshchat/app/MeshChatApplication.kt
git commit -m "build: v0.13.0 RFCOMM 载体装配 + 版本 bump + 交接块更新"
```

---

## 自检

**规格覆盖度对照：**
- §3.1 接口实现（incoming/broadcast/sendTo/connect/isConnectedTo）→ 任务 2 ✓
- §3.2 连接与配对（listen/accept/connect/createBond/广播等待）→ 任务 2 ✓
- §3.3 流分帧（长度前缀/readFully）→ 任务 1 ✓
- §3.4 IO 并发（Dispatchers.IO、synchronized 写、SharedFlow 合流、ConcurrentHashMap）→ 任务 2 ✓
- §4 MeshService 集成（rfcomm 注入、incoming 合并、会话后自动连接、sendFrame 路由）→ 任务 4 ✓
- §5 测试（分帧/sendTo 路由/FileTransferManager sendFrame/现有回归）→ 任务 1/3/4 ✓
- §6 权限（无新权限）→ 无需任务 ✓
- §7 边界（配对失败回退、socket 断开清理、蓝牙未开启降级、帧超长防御）→ 任务 2 ✓

**占位符扫描：** 所有步骤含具体代码。MeshFrame.decode 在任务 1 步骤 3 有自查说明。

**类型一致性：**
- `sendFrame: (dstId: String, frame: MeshFrame) -> Unit` 在 FileTransferManager 与 MeshService 定义一致 ✓
- `rfcomm.connect(peerId, address): Boolean`、`isConnectedTo(peerId): Boolean`、`sendTo(peerId, frame)` 贯穿一致 ✓
- 测试替身 FakeRfcomm 只暴露 MeshService 用到的方法（isConnectedTo/sendTo），与 RfcommTransport 方法签名对齐 ✓
