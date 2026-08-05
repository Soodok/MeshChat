# 调试中心（Debug Center）实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 实现设置页入口的实时调试仪表盘——收发包速率/包数/字节、BLE 传输层、信号与路由、送达链路、文件传输五板块 + 7 项调节（刷新间隔/速率窗口/速率单位/板块显隐/节点排序/暂停/清零）。

**架构：** 纯 Kotlin 无线程的 `DebugStats` 统计内核（时间戳队列→任意窗口精确速率）+ 三个埋点源（MeshService/FileTransferManager/BleTransport，每处 1-2 行原子累加）+ `DebugCenterScreen` UI 由 ViewModel 按调节项驱动刷新循环。零侵入收发时序。

**技术栈：** Kotlin + Compose + coroutines StateFlow；JVM 单测（junit4 + kotlinx-coroutines-test）。

**规格：** `docs/superpowers/specs/2026-08-05-debug-center-design.md`

---

## 文件结构

- 创建：`app/src/main/java/com/meshchat/app/mesh/debug/DebugStats.kt` — 统计内核（计数/时间戳队列/快照聚合/清零）
- 创建：`app/src/test/java/com/meshchat/app/mesh/debug/DebugStatsTest.kt` — 内核单测（虚拟时钟）
- 修改：`app/src/main/java/com/meshchat/app/mesh/service/MeshService.kt` — 发送/接收/回执/路由/中继埋点
- 修改：`app/src/main/java/com/meshchat/app/mesh/transfer/FileTransferManager.kt` — FILE 块/ACK 埋点
- 修改：`app/src/main/java/com/meshchat/app/mesh/transport/BleTransport.kt` — 广播/扫描/GATT/MTU 埋点
- 创建：`app/src/main/java/com/meshchat/app/ui/screens/DebugCenterScreen.kt` — 仪表盘 UI + 调节面板
- 修改：`app/src/main/java/com/meshchat/app/ui/MeshChatViewModel.kt` — debugStats 注入 + 刷新循环 + 设置状态
- 修改：`app/src/main/java/com/meshchat/app/ui/MeshChatViewModelFactory.kt` — 注入 debugStats
- 修改：`app/src/main/java/com/meshchat/app/MeshChatApplication.kt` — debugStats 单例装配
- 修改：`app/src/main/java/com/meshchat/app/ui/MeshChatApp.kt` — 透传 + DebugCenterScreen 路由
- 修改：`app/src/main/java/com/meshchat/app/ui/screens/MeshChatHome.kt` — profileDetail 路由 "debug"
- 修改：`app/src/main/java/com/meshchat/app/ui/screens/ProfileScreen.kt` — 「调试中心」入口行
- 修改：`app/src/test/java/com/meshchat/app/mesh/service/MeshServiceTest.kt` — 埋点断言
- 修改：`app/build.gradle.kts` — v1.1.5/67
- 修改：`README.md`、`AI_CONTEXT.md` — 文档

---

### 任务 1：DebugStats 统计内核（TDD）

**文件：**
- 创建：`app/src/main/java/com/meshchat/app/mesh/debug/DebugStats.kt`
- 测试：`app/src/test/java/com/meshchat/app/mesh/debug/DebugStatsTest.kt`

- [ ] **步骤 1：编写失败的测试**

```kotlin
package com.meshchat.app.mesh.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugStatsTest {

    /** 可推进的虚拟时钟：测试速率窗口/清理时无需真实等待。 */
    private class FakeClock(var now: Long = 0L) {
        val tick: () -> Long = { now }
    }

    @Test
    fun `records sent and received counts with bytes`() {
        val clock = FakeClock()
        val stats = DebugStats(clock.tick)
        stats.recordSent(FrameKind.PING, 100)
        stats.recordSent(FrameKind.PING, 50)
        stats.recordReceived(FrameKind.TEXT, 200)
        val snap = stats.snapshot(5_000)
        assertEquals(2, snap.frames.getValue(FrameKind.PING).sent)
        assertEquals(150, snap.frames.getValue(FrameKind.PING).sentBytes)
        assertEquals(1, snap.frames.getValue(FrameKind.TEXT).received)
        assertEquals(200, snap.frames.getValue(FrameKind.TEXT).receivedBytes)
    }

    @Test
    fun `rate only counts events inside the window`() {
        val clock = FakeClock()
        val stats = DebugStats(clock.tick)
        stats.recordSent(FrameKind.PING, 10)   // t=0
        clock.now = 3_000
        stats.recordSent(FrameKind.PING, 10)   // t=3000
        clock.now = 6_000
        val snap5s = stats.snapshot(5_000)     // 窗口 [1000, 6000]：仅 3000 事件
        assertEquals(1, snap5s.frames.getValue(FrameKind.PING).sent)
        assertEquals(1.0 / 5.0, snap5s.frames.getValue(FrameKind.PING).sentRatePerSec, 1e-9)
        // 累计数不受窗口影响
        assertEquals(2, snap5s.frames.getValue(FrameKind.PING).sent)
        // 窗口拉长到 10s 涵盖两事件
        val snap10s = stats.snapshot(10_000)
        assertEquals(2, snap10s.frames.getValue(FrameKind.PING).sent)
        assertEquals(2.0 / 10.0, snap10s.frames.getValue(FrameKind.PING).sentRatePerSec, 1e-9)
    }

    @Test
    fun `reset clears counts and queues`() {
        val clock = FakeClock()
        val stats = DebugStats(clock.tick)
        stats.recordSent(FrameKind.TEXT, 10)
        stats.recordBleBroadcast(30)
        stats.recordGattWrite(true)
        stats.recordResend("m1")
        stats.reset()
        val snap = stats.snapshot(5_000)
        assertEquals(0, snap.frames.getValue(FrameKind.TEXT).sent)
        assertEquals(0, snap.ble.broadcastCount)
        assertEquals(0, snap.ble.writeSuccess)
        assertEquals(0, snap.delivery.resends)
        assertEquals(0, snap.delivery.confirmed)
    }

    @Test
    fun `delivery resend histogram buckets 4 as four-plus`() {
        val clock = FakeClock()
        val stats = DebugStats(clock.tick)
        stats.recordResend("a"); stats.recordResend("a") // 2x
        stats.recordResend("b"); stats.recordResend("b"); stats.recordResend("b"); stats.recordResend("b"); stats.recordResend("b") // 5x → 4+
        val snap = stats.snapshot(5_000)
        assertEquals(2, snap.delivery.resendHistogram[2])
        assertEquals(1, snap.delivery.resendHistogram[4])
        stats.recordConfirmed("a", 500L)
        val after = stats.snapshot(5_000)
        assertEquals(1, after.delivery.confirmed)
        assertEquals(1.0, after.delivery.confirmationRate, 1e-9) // 1 confirmed / (1+0 pending)
    }

    @Test
    fun `ble and route counters aggregate`() {
        val clock = FakeClock()
        val stats = DebugStats(clock.tick)
        stats.recordBleBroadcast(40); stats.recordBleBroadcast(60)
        stats.recordScanResult(); stats.recordScanResult(); stats.recordScanResult()
        stats.recordGattConnectAttempt(); stats.recordGattConnectSuccess()
        stats.recordMtu(512)
        stats.recordRoute(RouteDecision.FORWARD)
        stats.recordRelayed()
        val snap = stats.snapshot(5_000)
        assertEquals(2, snap.ble.broadcastCount)
        assertEquals(100, snap.ble.broadcastBytes)
        assertEquals(3, snap.ble.scanResultCount)
        assertEquals(1, snap.ble.gattConnectAttempts)
        assertEquals(1, snap.ble.gattConnectSuccess)
        assertEquals(1, snap.ble.gattCurrent)
        assertEquals(512, snap.ble.mtu)
        assertEquals(1, snap.delivery.forwardCount)
        assertEquals(1, snap.delivery.relayedFrames)
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`.\gradlew.bat testDebugUnitTest --tests "com.meshchat.app.mesh.debug.DebugStatsTest" --console=plain`
预期：编译失败（DebugStats 不存在）

- [ ] **步骤 3：创建 DebugStats 内核**

```kotlin
package com.meshchat.app.mesh.debug

import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** 帧类型分类（映射 MeshEnvelope.kind；RECEIPT 为 FrameType.RECEIPT 专用）。 */
enum class FrameKind { PING, PONG, INVITE, INVITE_ACK, TEXT, FILE_CHUNK, FILE_ACK, RECEIPT, OTHER }

/** 帧收发统计：sent/received 为累计数，速率按窗口内事件数计算（精确可调，无需重启）。 */
data class FrameStat(
    val sent: Long = 0, val sentBytes: Long = 0, val sentRatePerSec: Double = 0.0,
    val received: Long = 0, val receivedBytes: Long = 0, val receivedRatePerSec: Double = 0.0,
)

data class BleStats(
    val broadcastCount: Long = 0, val broadcastBytes: Long = 0, val broadcastRatePerSec: Double = 0.0,
    val scanResultCount: Long = 0, val scanStartedCount: Long = 0,
    val gattConnectAttempts: Long = 0, val gattConnectSuccess: Long = 0, val gattDisconnects: Long = 0,
    val gattCurrent: Int = 0, val mtu: Int = 0,
    val writeSuccess: Long = 0, val writeFailed: Long = 0,
    val notifySuccess: Long = 0, val notifyFailed: Long = 0,
    val writeRequestsReceived: Long = 0,
    val servicesDiscovered: Long = 0, val servicesDiscoverRetries: Long = 0,
)

data class PeerDebugInfo(
    val shortId: String, val displayName: String, val rssi: Int,
    val bars: Int, val presence: String, val hops: Int, val relayVia: String?,
    val lastSeenAgoMs: Long,
)

data class DeliveryStats(
    val pending: Int = 0, val confirmed: Long = 0, val resends: Long = 0,
    val resendHistogram: Map<Int, Long> = emptyMap(),
    val confirmationRate: Double = 0.0,
    val relayedFrames: Long = 0,
    val deliverCount: Long = 0, val forwardCount: Long = 0, val dropCount: Long = 0,
)

data class FileStats(
    val activeTransfer: Boolean = false, val direction: String? = null, val fileName: String? = null,
    val chunksTotal: Int = 0, val chunksProgress: Int = 0, val percent: Int = 0,
    val windowRetries: Long = 0,
)

data class SystemStats(
    val uptimeMs: Long = 0, val serviceStarted: Boolean = false, val bluetoothEnabled: Boolean = false,
    val totalMemoryKb: Long = 0, val freeMemoryKb: Long = 0,
)

data class DebugSnapshot(
    val timestampMs: Long = 0,
    val frames: Map<FrameKind, FrameStat> = emptyMap(),
    val ble: BleStats = BleStats(),
    val peers: List<PeerDebugInfo> = emptyList(),
    val routeEntries: Int = 0, val sessions: Int = 0, val pendingInvites: Int = 0,
    val delivery: DeliveryStats = DeliveryStats(),
    val file: FileStats = FileStats(),
    val system: SystemStats = SystemStats(),
)

/** 路由决策分类（route() 三分支）。 */
enum class RouteDecision { DELIVER, FORWARD, DROP }

/**
 * 调试统计内核：纯 Kotlin、无线程、无 Android 依赖。
 * 每个事件记 (timestampMs, bytes) 入队（保留 20s），snapshot(windowMs) 同步聚合——窗口可任意调节即时生效。
 * 埋点只做原子累加/入队，绝不改变调用方控制流。
 */
class DebugStats(
    private val clock: () -> Long = System::currentTimeMillis,
) {
    companion object {
        /** 时间戳队列保留上限：窗口最大 10s，保留 20s 足够且防内存增长。 */
        const val MAX_RETAIN_MS = 20_000L
        private fun kindOf(kind: String?): FrameKind = when (kind) {
            "PING" -> FrameKind.PING
            "PONG" -> FrameKind.PONG
            "INVITE" -> FrameKind.INVITE
            "INVITE_ACK" -> FrameKind.INVITE_ACK
            "TEXT" -> FrameKind.TEXT
            "FILE" -> FrameKind.FILE_CHUNK
            "FILE_ACK" -> FrameKind.FILE_ACK
            else -> FrameKind.OTHER
        }
        /** 由 MeshEnvelope.kind 字符串得到分类。 */
        fun kindOfEnvelope(kind: String?): FrameKind = kindOf(kind)
    }

    /** 单类型事件队列：线程安全，入队惰性清理过期项，计数时按窗口过滤。 */
    private class EventQueue {
        private val lock = Any()
        private val queue = ArrayDeque<Pair<Long, Int>>()

        fun push(now: Long, bytes: Int) = synchronized(lock) {
            queue.addLast(now to bytes)
            trim(now)
        }

        /** 返回 longArrayOf(窗口内事件数, 窗口内字节数)。 */
        fun statsSince(now: Long, windowMs: Long): LongArray = synchronized(lock) {
            val cutoff = now - windowMs
            while (queue.isNotEmpty() && queue.first().first < cutoff) queue.removeFirst()
            var count = 0L
            var bytes = 0L
            for ((_, b) in queue) { count++; bytes += b }
            longArrayOf(count, bytes)
        }

        fun reset() = synchronized(lock) { queue.clear() }

        private fun trim(now: Long) {
            val cutoff = now - MAX_RETAIN_MS
            while (queue.isNotEmpty() && queue.first().first < cutoff) queue.removeFirst()
        }
    }

    // ---- 帧计数 ----
    private val sentQ = ConcurrentHashMap<FrameKind, EventQueue>()
    private val recvQ = ConcurrentHashMap<FrameKind, EventQueue>()
    private val sentTotal = ConcurrentHashMap<FrameKind, Long>()
    private val sentBytesTotal = ConcurrentHashMap<FrameKind, Long>()
    private val recvTotal = ConcurrentHashMap<FrameKind, Long>()
    private val recvBytesTotal = ConcurrentHashMap<FrameKind, Long>()

    fun recordSent(kind: FrameKind, bytes: Int) {
        val now = clock()
        sentQ.computeIfAbsent(kind) { EventQueue() }.push(now, bytes)
        sentTotal.compute(kind) { _, v -> (v ?: 0) + 1 }
        sentBytesTotal.compute(kind) { _, v -> (v ?: 0) + bytes }
    }

    fun recordReceived(kind: FrameKind, bytes: Int) {
        val now = clock()
        recvQ.computeIfAbsent(kind) { EventQueue() }.push(now, bytes)
        recvTotal.compute(kind) { _, v -> (v ?: 0) + 1 }
        recvBytesTotal.compute(kind) { _, v -> (v ?: 0) + bytes }
    }

    // ---- BLE ----
    private val broadcastQ = EventQueue()
    private val bleLock = Any()
    private var broadcastCount = 0L
    private var broadcastBytes = 0L
    private var scanResultCount = 0L
    private var scanStartedCount = 0L
    private var gattConnectAttempts = 0L
    private var gattConnectSuccess = 0L
    private var gattDisconnects = 0L
    private var gattCurrent = 0
    private var mtu = 0
    private var writeSuccess = 0L
    private var writeFailed = 0L
    private var notifySuccess = 0L
    private var notifyFailed = 0L
    private var writeRequestsReceived = 0L
    private var servicesDiscovered = 0L
    private var servicesDiscoverRetries = 0L

    fun recordBleBroadcast(bytes: Int) {
        broadcastQ.push(clock(), bytes)
        synchronized(bleLock) { broadcastCount++; broadcastBytes += bytes }
    }

    fun recordScanResult() = synchronized(bleLock) { scanResultCount++ }
    fun recordScanStarted() = synchronized(bleLock) { scanStartedCount++ }
    fun recordGattConnectAttempt() = synchronized(bleLock) { gattConnectAttempts++ }
    fun recordGattConnectSuccess() = synchronized(bleLock) { gattConnectSuccess++; gattCurrent++ }
    fun recordGattDisconnect() = synchronized(bleLock) { gattDisconnects++; gattCurrent = (gattCurrent - 1).coerceAtLeast(0) }
    fun recordMtu(value: Int) = synchronized(bleLock) { mtu = value }
    fun recordGattWrite(ok: Boolean) = synchronized(bleLock) { if (ok) writeSuccess++ else writeFailed++ }
    fun recordNotify(ok: Boolean) = synchronized(bleLock) { if (ok) notifySuccess++ else notifyFailed++ }
    fun recordWriteRequestReceived() = synchronized(bleLock) { writeRequestsReceived++ }
    fun recordServicesDiscovered(ok: Boolean) = synchronized(bleLock) { if (ok) servicesDiscovered++ else servicesDiscoverRetries++ }

    // ---- 送达 / 路由 ----
    private val deliveryLock = Any()
    private val resendsByMsg = HashMap<String, Int>()
    private val resendHistogram = HashMap<Int, Long>()
    private var confirmed = 0L
    private var resends = 0L
    private var relayedFrames = 0L
    private var deliverCount = 0L
    private var forwardCount = 0L
    private var dropCount = 0L
    private var fileWindowRetries = 0L
    private val startedAt = clock()

    fun recordRelayed() = synchronized(deliveryLock) { relayedFrames++ }

    fun recordRoute(decision: RouteDecision) = synchronized(deliveryLock) {
        when (decision) {
            RouteDecision.DELIVER -> deliverCount++
            RouteDecision.FORWARD -> forwardCount++
            RouteDecision.DROP -> dropCount++
        }
    }

    /** 重发一次（按消息 id 分桶：1/2/3/4+ 次）。 */
    fun recordResend(msgId: String) = synchronized(deliveryLock) {
        resends++
        val n = (resendsByMsg[msgId] ?: 0) + 1
        resendsByMsg[msgId] = n
        val bucket = if (n >= 4) 4 else n
        resendHistogram[bucket] = (resendHistogram[bucket] ?: 0) + 1
    }

    /** 确认一次（回执/PONG ackIds/广播确认键收敛）。 */
    fun recordConfirmed(msgId: String) = synchronized(deliveryLock) {
        confirmed++
        resendsByMsg.remove(msgId)
    }

    fun recordFileWindowRetry() = synchronized(deliveryLock) { fileWindowRetries++ }

    /** 快照聚合：速率 = 窗口内事件数 / 窗口秒数；累计数不受窗口影响。 */
    fun snapshot(windowMs: Long): DebugSnapshot {
        val now = clock()
        val frames = FrameKind.entries.associateWith { kind ->
            val s = sentQ[kind]?.statsSince(now, windowMs)
            val r = recvQ[kind]?.statsSince(now, windowMs)
            FrameStat(
                sent = sentTotal[kind] ?: 0,
                sentBytes = sentBytesTotal[kind] ?: 0,
                sentRatePerSec = (s?.get(0) ?: 0) / (windowMs / 1000.0),
                received = recvTotal[kind] ?: 0,
                receivedBytes = recvBytesTotal[kind] ?: 0,
                receivedRatePerSec = (r?.get(0) ?: 0) / (windowMs / 1000.0),
            )
        }
        val broadcastWin = broadcastQ.statsSince(now, windowMs)
        val pending: Int
        val del: DeliveryStats
        synchronized(deliveryLock) {
            pending = pendingProvider()
            del = DeliveryStats(
                pending = pending,
                confirmed = confirmed,
                resends = resends,
                resendHistogram = resendHistogram.toMap(),
                confirmationRate = if (confirmed + pending > 0) confirmed.toDouble() / (confirmed + pending) else 0.0,
                relayedFrames = relayedFrames,
                deliverCount = deliverCount,
                forwardCount = forwardCount,
                dropCount = dropCount,
            )
        }
        val ble: BleStats
        synchronized(bleLock) {
            ble = BleStats(
                broadcastCount = broadcastCount,
                broadcastBytes = broadcastBytes,
                broadcastRatePerSec = broadcastWin[0] / (windowMs / 1000.0),
                scanResultCount = scanResultCount,
                scanStartedCount = scanStartedCount,
                gattConnectAttempts = gattConnectAttempts,
                gattConnectSuccess = gattConnectSuccess,
                gattDisconnects = gattDisconnects,
                gattCurrent = gattCurrent,
                mtu = mtu,
                writeSuccess = writeSuccess,
                writeFailed = writeFailed,
                notifySuccess = notifySuccess,
                notifyFailed = notifyFailed,
                writeRequestsReceived = writeRequestsReceived,
                servicesDiscovered = servicesDiscovered,
                servicesDiscoverRetries = servicesDiscoverRetries,
            )
        }
        val runtime = Runtime.getRuntime()
        return DebugSnapshot(
            timestampMs = now,
            frames = frames,
            ble = ble,
            peers = peersProvider(),
            routeEntries = routeEntriesProvider(),
            sessions = sessionsProvider(),
            pendingInvites = pendingInvitesProvider(),
            delivery = del,
            file = fileStatsProvider(),
            system = SystemStats(
                uptimeMs = now - startedAt,
                serviceStarted = serviceStartedProvider(),
                bluetoothEnabled = bluetoothEnabledProvider(),
                totalMemoryKb = runtime.totalMemory() / 1024,
                freeMemoryKb = runtime.freeMemory() / 1024,
            ),
        )
    }

    fun reset() {
        sentQ.values.forEach { it.reset() }; sentQ.clear()
        recvQ.values.forEach { it.reset() }; recvQ.clear()
        sentTotal.clear(); sentBytesTotal.clear(); recvTotal.clear(); recvBytesTotal.clear()
        broadcastQ.reset()
        synchronized(bleLock) {
            broadcastCount = 0; broadcastBytes = 0; scanResultCount = 0; scanStartedCount = 0
            gattConnectAttempts = 0; gattConnectSuccess = 0; gattDisconnects = 0; gattCurrent = 0; mtu = 0
            writeSuccess = 0; writeFailed = 0; notifySuccess = 0; notifyFailed = 0
            writeRequestsReceived = 0; servicesDiscovered = 0; servicesDiscoverRetries = 0
        }
        synchronized(deliveryLock) {
            resendsByMsg.clear(); resendHistogram.clear(); confirmed = 0; resends = 0
            relayedFrames = 0; deliverCount = 0; forwardCount = 0; dropCount = 0; fileWindowRetries = 0
        }
    }

    // ---- 由 MeshService 注入的数据提供器（快照时读实时状态，避免 DebugStats 依赖服务）----
    private var pendingProvider: () -> Int = { 0 }
    private var peersProvider: () -> List<PeerDebugInfo> = { emptyList() }
    private var routeEntriesProvider: () -> Int = { 0 }
    private var sessionsProvider: () -> Int = { 0 }
    private var pendingInvitesProvider: () -> Int = { 0 }
    private var fileStatsProvider: () -> FileStats = { FileStats(windowRetries = fileWindowRetries) }
    private var serviceStartedProvider: () -> Boolean = { false }
    private var bluetoothEnabledProvider: () -> Boolean = { false }

    /** MeshService 装配时注入实时状态读取器（纯读取，不持有服务引用）。 */
    fun attachProviders(
        pending: () -> Int,
        peers: () -> List<PeerDebugInfo>,
        routeEntries: () -> Int,
        sessions: () -> Int,
        pendingInvites: () -> Int,
        fileStats: () -> FileStats,
        serviceStarted: () -> Boolean,
        bluetoothEnabled: () -> Boolean,
    ) {
        pendingProvider = pending
        peersProvider = peers
        routeEntriesProvider = routeEntries
        sessionsProvider = sessions
        pendingInvitesProvider = pendingInvites
        fileStatsProvider = fileStats
        serviceStartedProvider = serviceStarted
        bluetoothEnabledProvider = bluetoothEnabled
    }
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：`.\gradlew.bat testDebugUnitTest --tests "com.meshchat.app.mesh.debug.DebugStatsTest" --console=plain`
预期：5 项全 PASS

- [ ] **步骤 5：Commit**

```bash
git add app/src/main/java/com/meshchat/app/mesh/debug/DebugStats.kt app/src/test/java/com/meshchat/app/mesh/debug/DebugStatsTest.kt
git commit -m "feat: 调试中心统计内核 DebugStats——帧/字节计数、窗口速率、BLE/送达/路由统计，5 项单测"
```

---

### 任务 2：MeshService 埋点

**文件：**
- 修改：`app/src/main/java/com/meshchat/app/mesh/service/MeshService.kt`
- 测试：`app/src/test/java/com/meshchat/app/mesh/service/MeshServiceTest.kt`

- [ ] **步骤 1：构造参数 + 内核装配**

在 `MeshService` 构造参数末尾追加（`onFileSaved` 之后）：

```kotlin
    /** 调试统计内核（默认独立实例，生产由 Application 注入共享单例）。 */
    private val debugStats: DebugStats = DebugStats(),
```

并在类体内 `transfer` 构造处传参（FileTransferManager 构造新参见任务 3）：

```kotlin
        sendFrame = { dstId, frame -> sendFrame(dstId, frame) },
        debugStats = debugStats,
```

- [ ] **步骤 2：接收埋点（handleFrame）**

替换 `handleFrame` 的 `FrameType.DATA ->` 分支开头（原 690-694 行）：

```kotlin
            FrameType.DATA -> {
                val envelope = runCatching { MeshJson.decodeEnvelope(frame.payloadText) }
                    .getOrNull()
                if (envelope == null) {
                    debugStats.recordReceived(FrameKind.OTHER, frame.payload.size)
                    return
                }
                debugStats.recordReceived(DebugStats.kindOfEnvelope(envelope.kind), frame.payload.size)
                handleEnvelope(envelope)
            }
            FrameType.RECEIPT -> {
                debugStats.recordReceived(FrameKind.RECEIPT, frame.payload.size)
                // （原 RECEIPT 逻辑保持不变）
```

- [ ] **步骤 3：发送埋点（统一出口记录器）**

新增私有方法（放 `sendFrame` 附近）：

```kotlin
    /** 发送统计（统一出口）：RECEIPT 帧按 RECEIPT 计，DATA 帧按信封 kind 计。 */
    private fun recordSentFrame(frame: MeshFrame) {
        val kind = if (frame.type == FrameType.RECEIPT) FrameKind.RECEIPT
            else runCatching { DebugStats.kindOfEnvelope(MeshJson.decodeEnvelope(frame.payloadText).kind) }
                .getOrDefault(FrameKind.OTHER)
        debugStats.recordSent(kind, frame.payload.size)
    }
```

在以下发送出口各加一行 `recordSentFrame(frame)`：
1. `sendFrame(dstId, frame)`（文件帧路由，原 329-332 行）——加在 `if (rfcomm...) ... else transport.broadcast(frame)` 之前
2. `broadcastData(envelope)`（原 916-918 行）——加在 `transport.broadcast(...)` 前（改传 envelope 计字节：`debugStats.recordSent(DebugStats.kindOfEnvelope(envelope.kind), MeshJson.encodeEnvelope(envelope).toByteArray().size)`）
3. `sendReceipt(envelope)`（原 920-925 行）——加在 `transport.broadcast(MeshFrame(FrameType.RECEIPT, ...))` 前：`debugStats.recordSent(FrameKind.RECEIPT, receipt.toByteArray().size)`
4. `heartbeatTick` 的 PING 广播（原 514 行 `transport.broadcast(MeshFrame(FrameType.DATA, ...))`）——先取 frame 变量再 broadcast
5. PONG 广播（原 799 行）——同上
6. `sendInviteAck` 广播（原 535 行）——同上

对 4/5/6 三处，将 `transport.broadcast(MeshFrame(...))` 改为：

```kotlin
                val frame = MeshFrame(FrameType.DATA, MeshJson.encodeEnvelope(env).toByteArray())
                recordSentFrame(frame)
                transport.broadcast(frame)
```

（按各方法现有变量名调整：heartbeatTick 用 `p.envelope`、PONG 用 `pong`、sendInviteAck 用 `env`。）

- [ ] **步骤 4：路由/中继/回执埋点**

1. `route()` 三分支（原 865-913 行）各加一行：
   - `ForwardDecision.Deliver ->` 分支开头：`debugStats.recordRoute(RouteDecision.DELIVER)`
   - `is ForwardDecision.Forward ->` 分支开头：`debugStats.recordRoute(RouteDecision.FORWARD); debugStats.recordRelayed()`
   - `ForwardDecision.Drop ->` 分支开头：`debugStats.recordRoute(RouteDecision.DROP)`
2. `resendPendingReceipts` 重发处（`broadcastData(p.envelope)` 调用前，原 514 行区域）：`debugStats.recordResend(p.envelope.id)`
3. 确认收敛点三处各加 `debugStats.recordConfirmed(id)`：
   - `handleReceipt`（原 931 行 `pendingReceipts.remove(id)` 前）
   - `confirmByAckKey`（原 594 行 `store.updateMessageStatus(id, ...DELIVERED)` 前）
   - `handleEnvelope` PONG ackIds 确认循环内（`pendingReceipts` 移除处）
4. `start()` 末尾调 `debugStats.attachProviders(...)`：

```kotlin
        // 调试中心快照数据源（纯读取，不参与收发逻辑）
        debugStats.attachProviders(
            pending = { pendingReceipts.size },
            peers = {
                peerEntries.entries.map { (id, e) ->
                    val info = e.info
                    PeerDebugInfo(
                        shortId = id, displayName = info.displayName,
                        rssi = info.rssi, bars = BluetoothQuality.bars(info.rssi),
                        presence = info.presence.name, hops = info.hops,
                        relayVia = routeEntries[id]?.via,
                        lastSeenAgoMs = if (info.lastSeenAt > 0) (System.currentTimeMillis() - info.lastSeenAt).coerceAtLeast(0) else -1,
                    )
                }
            },
            routeEntries = { routeEntries.size },
            sessions = { _sessions.value.size },
            pendingInvites = { _pendingInvites.value.size },
            fileStats = {
                val p = transfer.progress.value
                if (p == null) FileStats(windowRetries = debugStats.windowRetriesSnapshot())
                else FileStats(
                    activeTransfer = true, direction = p.direction.name,
                    fileName = p.fileName,
                    chunksTotal = ((p.totalBytes + 49) / 50).toInt().coerceAtLeast(0),
                    chunksProgress = ((p.transferredBytes + 49) / 50).toInt().coerceAtLeast(0),
                    percent = if (p.totalBytes > 0) ((p.transferredBytes * 100) / p.totalBytes).toInt() else 0,
                    windowRetries = debugStats.windowRetriesSnapshot(),
                )
            },
            serviceStarted = { started },
            bluetoothEnabled = { runCatching { transport.bluetoothEnabled() }.getOrDefault(false) },
        )
```

> 注：`debugStats.windowRetriesSnapshot()` 需在 DebugStats 增加公开读取（见步骤 5）。`transport.bluetoothEnabled()` 需在 MeshTransport 增加默认方法（见任务 4）。

- [ ] **步骤 5：DebugStats 补公开读取**

在 `DebugStats`（任务 1 文件）加：

```kotlin
    /** 文件传输窗口重发累计数（供 FileStats 组装）。 */
    fun windowRetriesSnapshot(): Long = synchronized(deliveryLock) { fileWindowRetries }
```

- [ ] **步骤 6：编写并运行单测断言**

在 `MeshServiceTest` 追加：

```kotlin
    @Test
    fun `debug stats records sent and received frames`() {
        val transport = CountingTransport()
        val stats = com.meshchat.app.mesh.debug.DebugStats()
        val service = MeshService(
            transport = transport, store = InMemoryMeshStore(), identity = LocalIdentity(shortId = "ME"),
            dedup = DedupCache(), debugStats = stats,
        )
        service.start()
        service.handleFrame(pingFrame("B", "Bob"))          // 收 PING → PONG 回复
        val snap = stats.snapshot(5_000)
        assertEquals(1, snap.frames.getValue(com.meshchat.app.mesh.debug.FrameKind.PING).received)
        assertEquals(1, snap.frames.getValue(com.meshchat.app.mesh.debug.FrameKind.PONG).sent)
        service.stop()
    }

    @Test
    fun `debug stats delivery confirmed increments on receipt`() {
        val transport = CountingTransport()
        val store = InMemoryMeshStore()
        val stats = com.meshchat.app.mesh.debug.DebugStats()
        val service = MeshService(
            transport = transport, store = store, identity = LocalIdentity(shortId = "ME"),
            dedup = DedupCache(), debugStats = stats,
        )
        service.start()
        service.sendText("conv-B", "B", "hi")
        val msgId = store.queryMessages("conv-B").single().id
        // 模拟对端回 RECEIPT（id 与消息一致）
        val receipt = "{\"id\":\"$msgId\",\"srcId\":\"B\",\"dstId\":\"ME\"}"
        service.handleFrame(MeshFrame(FrameType.RECEIPT, receipt.toByteArray()))
        val snap = stats.snapshot(5_000)
        assertEquals(1, snap.delivery.confirmed)
        assertEquals(1, snap.delivery.pending.coerceAtLeast(0) + 1 - 1) // 确认后待确认应为 0（pending=0）
        service.stop()
    }
```

运行：`.\gradlew.bat testDebugUnitTest --tests "com.meshchat.app.mesh.service.MeshServiceTest" --console=plain`
预期：全 PASS（41 项）

- [ ] **步骤 7：Commit**

```bash
git add app/src/main/java/com/meshchat/app/mesh/service/MeshService.kt app/src/main/java/com/meshchat/app/mesh/debug/DebugStats.kt app/src/test/java/com/meshchat/app/mesh/service/MeshServiceTest.kt
git commit -m "feat: MeshService 调试埋点——收发帧分类/回执确认/路由决策/中继计数，单测 +2"
```

---

### 任务 3：FileTransferManager 埋点

**文件：**
- 修改：`app/src/main/java/com/meshchat/app/mesh/transfer/FileTransferManager.kt`

- [ ] **步骤 1：构造参数**

构造参数末尾追加：

```kotlin
    /** 调试统计内核（透传 MeshService 注入）。 */
    private val debugStats: DebugStats = DebugStats(),
```

import：`com.meshchat.app.mesh.debug.DebugStats`、`com.meshchat.app.mesh.debug.FrameKind`

- [ ] **步骤 2：发送/接收/重试埋点**

1. `broadcastChunk`（原 261-274 行）——`sendFrame(...)` 前加：

```kotlin
        debugStats.recordSent(FrameKind.FILE_CHUNK, MeshJson.encodeEnvelope(envelope).toByteArray().size)
```

2. `onFileChunk`（原 295-319 行）——`session.writeChunk(...)` 前加：

```kotlin
        debugStats.recordReceived(FrameKind.FILE_CHUNK, envelope.body.asFile().chunkData.length)
```

> 注：`asFile()` 为 `(body as? FileBody)?.chunkData` 的辅助；直接使用现有 `body.chunkData.length`（envelope.body 已 cast 为 FileBody，见原方法首行）。

3. `sendAck(session)`（接收端 ACK 发送）——`sendFrame(...)` 前加：

```kotlin
        debugStats.recordSent(FrameKind.FILE_ACK, ackFrame.payload.size)
```

（若 sendAck 内部直接构造 MeshFrame，改为先取变量再埋点；找不到独立 sendAck 则在 onFileChunk 内 `sendAck(session)` 调用前对 ack 帧埋点——实现时以实际结构为准，保证 FILE_ACK 发送/接收各埋一处即可。）

4. `onFileAck`（原 321 行起）——`return` 前加：

```kotlin
        debugStats.recordReceived(FrameKind.FILE_ACK, envelope.body?.let { 0 } ?: 0)
```

（简化：`debugStats.recordReceived(FrameKind.FILE_ACK, envelope.body?.size ?: 0)`——如 FileAckBody 无 size 字段则传 0。）

5. 窗口超时重发（`runSender` 内 `broadcastWindow(s, cache)` 重试路径，原 206 行 `if (retries > maxWindowRetries)` 之前的 `broadcastWindow` 调用处）加：

```kotlin
                    debugStats.recordFileWindowRetry()
```

- [ ] **步骤 3：运行既有测试**

运行：`.\gradlew.bat testDebugUnitTest --tests "com.meshchat.app.mesh.transfer.FileTransferManagerTest" --console=plain`
预期：9 项全 PASS（默认 DebugStats 实例不影响行为）

- [ ] **步骤 4：Commit**

```bash
git add app/src/main/java/com/meshchat/app/mesh/transfer/FileTransferManager.kt
git commit -m "feat: FileTransferManager 调试埋点——FILE 块/FILE_ACK 收发、窗口重试计数"
```

---

### 任务 4：BleTransport 埋点

**文件：**
- 修改：`app/src/main/java/com/meshchat/app/mesh/transport/BleTransport.kt`
- 修改：`app/src/main/java/com/meshchat/app/mesh/transport/MeshTransport.kt`（接口加默认方法）

- [ ] **步骤 1：接口默认方法**

`MeshTransport` 接口加：

```kotlin
    /** 蓝牙开关状态（调试中心快照用；默认 false，实现覆盖）。 */
    fun bluetoothEnabled(): Boolean = false
```

- [ ] **步骤 2：构造参数 + 埋点**

`BleTransport` 构造参数追加：

```kotlin
    private val debugStats: DebugStats = DebugStats(),
```

import：`com.meshchat.app.mesh.debug.DebugStats`

按位置埋点（均为单行调用）：

| 位置（方法） | 埋点 |
|---|---|
| `broadcast(frame)`（原 178 行起，`transport.broadcast` 实现） | `debugStats.recordBleBroadcast(frame.payload.size)` |
| `startScanning()`（原 243 行起） | `debugStats.recordScanStarted()` |
| `onScanResult`（原 253 行起） | `debugStats.recordScanResult()` |
| `connectTo` 入口（原 279 行起） | `debugStats.recordGattConnectAttempt()` |
| `onConnectionStateChange` CONNECTED（原 91 行 server 侧 + 294 行 central 侧，各一处） | `debugStats.recordGattConnectSuccess()` |
| `onConnectionStateChange` DISCONNECTED（原 91/294 的 else 分支） | `debugStats.recordGattDisconnect()` |
| `requestMtu` 成功回调（原 300 行附近 `runCatching { gatt.requestMtu(512) }` 成功时） | `debugStats.recordMtu(gatt.mtu)` |
| `writeCharacteristic` 成功/失败（原 425-426 行） | `debugStats.recordGattWrite(ok)` |
| `notifyCharacteristicChanged` 成功/失败（原 442-452 行区域） | `debugStats.recordNotify(ok)` |
| `onCharacteristicWriteRequest`（原 124 行起） | `debugStats.recordWriteRequestReceived()` |
| `onServicesDiscovered` 成功（原 356 行起） | `debugStats.recordServicesDiscovered(true)`；重试分支 → `false` |
| `bluetoothEnabled()` override | `return runCatching { bluetoothManager.adapter?.isEnabled == true }.getOrDefault(false)` |

- [ ] **步骤 3：运行既有测试 + 编译**

运行：`.\gradlew.bat testDebugUnitTest assembleDebug --console=plain`
预期：BUILD SUCCESSFUL，测试全 PASS（BleTransport 无 JVM 测试，编译通过即可）

- [ ] **步骤 4：Commit**

```bash
git add app/src/main/java/com/meshchat/app/mesh/transport/BleTransport.kt app/src/main/java/com/meshchat/app/mesh/transport/MeshTransport.kt
git commit -m "feat: BleTransport 调试埋点——广播/扫描/GATT/MTU/写成败；MeshTransport 加 bluetoothEnabled 默认方法"
```

---

### 任务 5：UI（DebugCenterScreen + ViewModel 装配 + 入口）

**文件：**
- 创建：`app/src/main/java/com/meshchat/app/ui/screens/DebugCenterScreen.kt`
- 修改：`app/src/main/java/com/meshchat/app/ui/MeshChatViewModel.kt`
- 修改：`app/src/main/java/com/meshchat/app/ui/MeshChatViewModelFactory.kt`
- 修改：`app/src/main/java/com/meshchat/app/MeshChatApplication.kt`
- 修改：`app/src/main/java/com/meshchat/app/ui/MeshChatApp.kt`
- 修改：`app/src/main/java/com/meshchat/app/ui/screens/MeshChatHome.kt`
- 修改：`app/src/main/java/com/meshchat/app/ui/screens/ProfileScreen.kt`

- [ ] **步骤 1：Application 装配 debugStats 单例**

`MeshChatApplication`：

```kotlin
    /** 调试统计内核（真机调试中心数据源；内存态，重启清零）。 */
    val debugStats by lazy { com.meshchat.app.mesh.debug.DebugStats() }
```

- `transport` lazy 构造传 `debugStats = debugStats`
- `service` lazy 构造传 `debugStats = debugStats`

- [ ] **步骤 2：ViewModel 注入 + 刷新循环 + 设置状态**

`MeshChatViewModel` 构造参数追加：

```kotlin
    private val debugStats: com.meshchat.app.mesh.debug.DebugStats,
```

新状态与方法：

```kotlin
    // ---- 调试中心 ----
    data class DebugSettings(
        val refreshIntervalMs: Long = 1_000L,
        val windowMs: Long = 5_000L,
        val perMinute: Boolean = false,
        val showFrames: Boolean = true,
        val showBle: Boolean = true,
        val showRoutes: Boolean = true,
        val showDelivery: Boolean = true,
        val showFile: Boolean = true,
        val sortBy: String = "rssi",   // rssi / name / recent
        val paused: Boolean = false,
    )

    private val _debugSettings = MutableStateFlow(DebugSettings())
    val debugSettings: StateFlow<DebugSettings> = _debugSettings.asStateFlow()
    private val _debugSnapshot = MutableStateFlow<com.meshchat.app.mesh.debug.DebugSnapshot>(com.meshchat.app.mesh.debug.DebugSnapshot())
    val debugSnapshot: StateFlow<com.meshchat.app.mesh.debug.DebugSnapshot> = _debugSnapshot.asStateFlow()

    fun updateDebugSettings(transform: (DebugSettings) -> DebugSettings) {
        _debugSettings.value = transform(_debugSettings.value)
    }

    fun resetDebugStats() = debugStats.reset()

    fun startDebugLoop() {
        viewModelScope.launch {
            while (true) {
                val s = _debugSettings.value
                if (!s.paused) {
                    val snap = debugStats.snapshot(s.windowMs)
                    _debugSnapshot.value = snap.copy(peers = when (s.sortBy) {
                        "name" -> snap.peers.sortedBy { it.displayName }
                        "recent" -> snap.peers.sortedBy { it.lastSeenAgoMs }
                        else -> snap.peers.sortedByDescending { it.rssi }
                    })
                }
                delay(s.refreshIntervalMs)
            }
        }
    }
```

`init` 中调用 `startDebugLoop()`。

- [ ] **步骤 3：Factory 注入**

`MeshChatViewModelFactory` 构造 MeshChatViewModel 时加 `debugStats = app.debugStats`。

- [ ] **步骤 4：DebugCenterScreen**

创建 `DebugCenterScreen.kt`（完整代码）：

```kotlin
package com.meshchat.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.meshchat.app.mesh.debug.DebugSnapshot
import com.meshchat.app.mesh.debug.FrameKind
import com.meshchat.app.ui.MeshChatViewModel
import com.meshchat.app.ui.theme.*

private fun kb(v: Long) = if (v >= 1024) "%.1fKB".format(v / 1024.0) else "${v}B"
private fun rate(v: Double, perMinute: Boolean) =
    if (perMinute) "%.0f/min".format(v * 60) else "%.1f/s".format(v)

@Composable
fun DebugCenterScreen(
    snapshot: DebugSnapshot,
    settings: MeshChatViewModel.DebugSettings,
    onSettingsChange: (MeshChatViewModel.DebugSettings) -> Unit,
    onReset: () -> Unit,
    onBack: () -> Unit,
) {
    var settingsOpen by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxSize().background(Ink)) {
        Row(
            modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
            Text("调试中心", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = { onSettingsChange(settings.copy(paused = !settings.paused)) }) {
                Text(if (settings.paused) "继续" else "暂停")
            }
            TextButton(onClick = onReset) { Text("清零") }
            IconButton(onClick = { settingsOpen = true }) { Icon(Icons.Outlined.Settings, "设置") }
        }
        if (settingsOpen) {
            DebugSettingsPanel(settings, onSettingsChange, onClose = { settingsOpen = false })
        }
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (settings.showFrames) FramesCard(snapshot, settings.perMinute)
            if (settings.showBle) BleCard(snapshot)
            if (settings.showRoutes) RoutesCard(snapshot)
            if (settings.showDelivery) DeliveryCard(snapshot)
            if (settings.showFile) FileCard(snapshot)
            // 系统栏
            Text(
                "运行 ${snapshot.system.uptimeMs / 1000}s · 服务 ${if (snapshot.system.serviceStarted) "ON" else "OFF"} · 蓝牙 ${if (snapshot.system.bluetoothEnabled) "ON" else "OFF"} · 内存 ${kb(snapshot.system.freeMemoryKb * 1024)}/${kb(snapshot.system.totalMemoryKb * 1024)}",
                color = TextSecondary, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.padding(bottom = 16.dp),
            )
        }
    }
}
```

配套卡片 composable（同文件）：

```kotlin
@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().background(InkSoft.copy(alpha = 0.35f), RoundedCornerShape(10.dp)).padding(12.dp)) {
        Text(title, color = Cyan, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 8.dp))
        content()
    }
}

private val MONO = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)

@Composable
private fun StatRow(label: String, value: String, color: androidx.compose.ui.graphics.Color = TextSecondary) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = TextSecondary, style = MONO)
        Text(value, color = color, style = MONO)
    }
}

@Composable
private fun FramesCard(snap: DebugSnapshot, perMinute: Boolean) {
    SectionCard("收发包 · 速率") {
        val totalSent = snap.frames.values.sumOf { it.sent }
        val totalRecv = snap.frames.values.sumOf { it.received }
        val sentRate = snap.frames.values.sumOf { it.sentRatePerSec }
        val recvRate = snap.frames.values.sumOf { it.receivedRatePerSec }
        StatRow("总发送/接收", "↑${rate(sentRate, perMinute)} ↓${rate(recvRate, perMinute)} · ${totalSent}/${totalRecv} 包", Cyan)
        FrameKind.entries.forEach { kind ->
            val f = snap.frames[kind] ?: return@forEach
            StatRow(
                kind.name,
                "↑${rate(f.sentRatePerSec, perMinute)} ↓${rate(f.receivedRatePerSec, perMinute)} · ${f.sent}/${f.received} · ${kb(f.sentBytes)}/${kb(f.receivedBytes)}",
            )
        }
    }
}

@Composable
private fun BleCard(snap: DebugSnapshot) {
    val b = snap.ble
    SectionCard("BLE 传输层") {
        StatRow("广播", "${b.broadcastCount} 次 · ${kb(b.broadcastBytes)} · ${rate(b.broadcastRatePerSec, false)}")
        StatRow("扫描结果", b.scanResultCount.toString())
        StatRow("GATT 连接", "尝试 ${b.gattConnectAttempts} · 成功 ${b.gattConnectSuccess} · 当前 ${b.gattCurrent} · 断开 ${b.gattDisconnects}")
        StatRow("MTU", b.mtu.toString())
        StatRow("写入", "成功 ${b.writeSuccess} · 失败 ${b.writeFailed}")
        StatRow("Notify", "成功 ${b.notifySuccess} · 失败 ${b.notifyFailed}")
        StatRow("收到写请求", b.writeRequestsReceived.toString())
        StatRow("服务发现", "成功 ${b.servicesDiscovered} · 重试 ${b.servicesDiscoverRetries}")
    }
}

@Composable
private fun RoutesCard(snap: DebugSnapshot) {
    SectionCard("信号与路由") {
        StatRow("节点/会话/待邀请", "${snap.peers.size} · ${snap.sessions} · ${snap.pendingInvites}")
        StatRow("2 跳路由条目", snap.routeEntries.toString())
        snap.peers.forEach { p ->
            StatRow(
                "${p.shortId} ${p.displayName}".trim(),
                "${p.presence} · ${p.rssi}dBm(${p.bars}) · ${p.hops}跳${p.relayVia?.let { " 经$it" } ?: ""} · ${if (p.lastSeenAgoMs >= 0) "${p.lastSeenAgoMs}ms前" else "未见"}",
            )
        }
    }
}

@Composable
private fun DeliveryCard(snap: DebugSnapshot) {
    val d = snap.delivery
    SectionCard("送达链路") {
        StatRow("待确认/已确认", "${d.pending} · ${d.confirmed}", if (d.pending > 0) MeshAmber else MeshGreen)
        StatRow("确认率", "%.1f%%".format(d.confirmationRate * 100))
        StatRow("重发", d.resends.toString(), if (d.resends > 0) MeshAmber else TextSecondary)
        StatRow("重发分布", d.resendHistogram.entries.sortedBy { it.key }.joinToString(" ") { "${it.key}x:${it.value}" })
        StatRow("路由决策", "投递 ${d.deliverCount} · 转发 ${d.forwardCount} · 丢弃 ${d.dropCount}")
        StatRow("中继转发帧", d.relayedFrames.toString())
    }
}

@Composable
private fun FileCard(snap: DebugSnapshot) {
    val f = snap.file
    SectionCard("文件传输") {
        if (!f.activeTransfer) {
            StatRow("当前", "空闲", TextSecondary)
        } else {
            StatRow("方向/文件", "${f.direction} · ${f.fileName}")
            StatRow("进度", "${f.chunksProgress}/${f.chunksTotal} 块 · ${f.percent}%", Cyan)
        }
        StatRow("窗口重发", f.windowRetries.toString())
    }
}

@Composable
private fun DebugSettingsPanel(
    s: MeshChatViewModel.DebugSettings,
    onChange: (MeshChatViewModel.DebugSettings) -> Unit,
    onClose: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().background(InkSoft.copy(alpha = 0.5f)).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("调节", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onClose) { Text("关闭") }
        }
        Text("刷新间隔", color = TextSecondary, style = MONO)
        Row {
            listOf(500L to "0.5s", 1_000L to "1s", 2_000L to "2s", 5_000L to "5s").forEach { (v, label) ->
                FilterChip(selected = s.refreshIntervalMs == v, onClick = { onChange(s.copy(refreshIntervalMs = v)) }, label = { Text(label) }, modifier = Modifier.padding(end = 6.dp))
            }
        }
        Text("速率窗口", color = TextSecondary, style = MONO, modifier = Modifier.padding(top = 8.dp))
        Row {
            listOf(1_000L to "1s", 3_000L to "3s", 5_000L to "5s", 10_000L to "10s").forEach { (v, label) ->
                FilterChip(selected = s.windowMs == v, onClick = { onChange(s.copy(windowMs = v)) }, label = { Text(label) }, modifier = Modifier.padding(end = 6.dp))
            }
        }
        Text("速率单位", color = TextSecondary, style = MONO, modifier = Modifier.padding(top = 8.dp))
        Row {
            FilterChip(selected = !s.perMinute, onClick = { onChange(s.copy(perMinute = false)) }, label = { Text("包/s") }, modifier = Modifier.padding(end = 6.dp))
            FilterChip(selected = s.perMinute, onClick = { onChange(s.copy(perMinute = true)) }, label = { Text("包/min") })
        }
        Text("板块", color = TextSecondary, style = MONO, modifier = Modifier.padding(top = 8.dp))
        Row {
            listOf(
                "收发包" to s.showFrames, "BLE" to s.showBle, "信号" to s.showRoutes,
                "送达" to s.showDelivery, "文件" to s.showFile,
            ).forEach { (label, on) ->
                FilterChip(selected = on, onClick = { onChange(s.copy(showFrames = if (label == "收发包") !s.showFrames else s.showFrames, showBle = if (label == "BLE") !s.showBle else s.showBle, showRoutes = if (label == "信号") !s.showRoutes else s.showRoutes, showDelivery = if (label == "送达") !s.showDelivery else s.showDelivery, showFile = if (label == "文件") !s.showFile else s.showFile)) }, label = { Text(label) }, modifier = Modifier.padding(end = 6.dp))
            }
        }
        Text("节点排序", color = TextSecondary, style = MONO, modifier = Modifier.padding(top = 8.dp))
        Row {
            listOf("rssi" to "RSSI", "name" to "昵称", "recent" to "最近").forEach { (v, label) ->
                FilterChip(selected = s.sortBy == v, onClick = { onChange(s.copy(sortBy = v)) }, label = { Text(label) }, modifier = Modifier.padding(end = 6.dp))
            }
        }
    }
}
```

> 注：板块开关的 onClick 用 when 表达式比嵌套 copy 清晰——实现时可用辅助函数 `toggle(s, label)`。上述 `copy(...)` 链为示意，务必正确生成（每板块独立 toggle）。

- [ ] **步骤 5：路由装配**

1. `MeshChatHome`：profileDetail 分支加 `"debug" -> DebugCenterScreen(snapshot = debugSnapshot, settings = debugSettings, onSettingsChange = onUpdateDebugSettings, onReset = onResetDebugStats, onBack = { profileDetail = null })`；参数透传 `debugSnapshot/debugSettings/onUpdateDebugSettings/onResetDebugStats`
2. `ProfileScreen`：加 `ProfileRow(Icons.Outlined.BugReport, "调试中心", "实时收发/链路数据", onClick = onOpenDebugCenter)`（import `androidx.compose.material.icons.outlined.BugReport`），参数加 `onOpenDebugCenter`
3. `MeshChatApp`：透传 ViewModel 的 debug 状态与方法

- [ ] **步骤 6：编译 + 运行单测**

运行：`.\gradlew.bat testDebugUnitTest assembleDebug --console=plain`
预期：BUILD SUCCESSFUL，110 项测试全 PASS

- [ ] **步骤 7：Commit**

```bash
git add app/src/main/java/com/meshchat/app/ui/screens/DebugCenterScreen.kt app/src/main/java/com/meshchat/app/ui/MeshChatViewModel.kt app/src/main/java/com/meshchat/app/ui/MeshChatViewModelFactory.kt app/src/main/java/com/meshchat/app/MeshChatApplication.kt app/src/main/java/com/meshchat/app/ui/MeshChatApp.kt app/src/main/java/com/meshchat/app/ui/screens/MeshChatHome.kt app/src/main/java/com/meshchat/app/ui/screens/ProfileScreen.kt
git commit -m "feat: 调试中心 UI——五板块实时仪表盘 + 7 项调节（刷新/窗口/单位/板块/排序/暂停/清零）+ 设置页入口"
```

---

### 任务 6：版本/文档/全量验证

**文件：**
- 修改：`app/build.gradle.kts`、`README.md`、`AI_CONTEXT.md`

- [ ] **步骤 1：版本 bump**

`app/build.gradle.kts`：`versionCode = 67`、`versionName = "1.1.5"`

- [ ] **步骤 2：README 特性区**

在「安全中心（v1.1.3）」条目后加：

```
- 调试中心（v1.1.5）：设置页入口实时仪表盘——收发包速率/包数/字节（按帧类型）、BLE 传输层（广播/扫描/GATT/MTU）、信号与路由、送达链路（确认率/重发分布）、文件传输；7 项调节（刷新间隔/速率窗口/单位/板块/排序/暂停/清零），内存态重启清零
```

- [ ] **步骤 3：AI_CONTEXT 交接块**

- 版本行 → v1.1.5/67（构建时间写实际值）
- 进度区加 v1.1.5 条目（调试中心，埋点零侵入说明）
- 已验证内容加 110 项 + assembleDebug
- 当前阻塞/下一步更新为 v1.1.5 待真机验证

- [ ] **步骤 4：全量验证 + APK**

运行：`.\gradlew.bat testDebugUnitTest assembleDebug --console=plain`
预期：BUILD SUCCESSFUL，110/110 通过
复制：`Copy-Item app\build\outputs\apk\debug\app-debug.apk MeshChat-v1.1.5-debug.apk -Force`

- [ ] **步骤 5：Commit**

```bash
git add app/build.gradle.kts README.md AI_CONTEXT.md
git commit -m "build: v1.1.5 调试中心——版本 bump、README/AI_CONTEXT 更新"
```

---

## 自检记录

- **规格覆盖度**：规格 §3 数据模型（DebugSnapshot 全字段 ✓ 任务 1）、§4 五板块（FramesCard/BleCard/RoutesCard/DeliveryCard/FileCard ✓ 任务 5）、§5 七调节项（✓ 任务 5 DebugSettings）、§6 埋点清单（MeshService ✓ 任务 2 / FileTransferManager ✓ 任务 3 / BleTransport ✓ 任务 4）、§7 内核（✓ 任务 1）、§8 UI（✓ 任务 5）、§9 边界（重置/窗口上限/无异常 ✓ 内核设计）、§10 测试（DebugStatsTest ✓ 任务 1、MeshServiceTest ✓ 任务 2、回归 ✓ 任务 6）、§11 版本（✓ 任务 6）。
- **占位符扫描**：无 TODO/待定；FileTransferManager 的 sendAck 位置以"实现时以实际结构为准"标注（唯一不确定点，已给出两种落点）。
- **类型一致性**：`FrameKind`/`DebugSnapshot`/`DebugSettings`/`RouteDecision` 各任务签名一致；`windowRetriesSnapshot()`/`bluetoothEnabled()` 在任务 2/4 中补定义。
