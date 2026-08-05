package com.meshchat.app.mesh.debug

import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

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

/** 失败包统计（信息不可确认/不完整包）：接收解码失败 + 送达不可确认 + BLE 发送失败。 */
data class FailedStats(
    val receivedDecodeFailures: Long = 0,       // 收到但无法解析的帧累计数
    val receivedDecodeRatePerSec: Double = 0.0, // 窗口内解码失败速率
    val unconfirmed: Int = 0,                    // 发出后尚未收到确认的包（pending）
    val bleWriteFailed: Long = 0,                // BLE 写特征失败累计
    val bleNotifyFailed: Long = 0,               // BLE notify 失败累计
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
    val failures: FailedStats = FailedStats(),
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

        /** 由 MeshEnvelope.kind 字符串得到帧分类（RECEIPT 是独立 FrameType，由调用方直接传 FrameKind.RECEIPT）。 */
        fun kindOfEnvelope(kind: String?): FrameKind = when (kind) {
            "PING" -> FrameKind.PING
            "PONG" -> FrameKind.PONG
            "INVITE" -> FrameKind.INVITE
            "INVITE_ACK" -> FrameKind.INVITE_ACK
            "TEXT" -> FrameKind.TEXT
            "FILE" -> FrameKind.FILE_CHUNK
            "FILE_ACK" -> FrameKind.FILE_ACK
            else -> FrameKind.OTHER
        }
    }

    /** 单类型事件队列：线程安全，入队惰性清理过期项，计数时按窗口过滤。 */
    private class EventQueue {
        private val lock = Any()
        private val queue = ArrayDeque<Pair<Long, Int>>()

        fun push(now: Long, bytes: Int) = synchronized(lock) {
            queue.addLast(now to bytes)
            trim(now)
        }

        /** 返回 longArrayOf(窗口内事件数, 窗口内字节数)。非破坏性：只计数，不删除（过期清理由 push 的 trim 负责，保证窗口可动态放大）。 */
        fun statsSince(now: Long, windowMs: Long): LongArray = synchronized(lock) {
            val cutoff = now - windowMs
            var count = 0L
            var bytes = 0L
            for ((ts, b) in queue) {
                if (ts < cutoff) continue
                count++; bytes += b
            }
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

    // ---- 失败包（信息不可确认/不完整包）----
    private val receivedFailuresQ = EventQueue()
    private var receivedFailures = 0L

    /** 收到但无法解析的帧（解码失败/不完整包）。 */
    fun recordReceivedFailure() {
        val now = clock()
        receivedFailuresQ.push(now, 0)
        receivedFailures++
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

    /** 文件传输窗口重发累计数（供 FileStats 组装）。 */
    fun windowRetriesSnapshot(): Long = synchronized(deliveryLock) { fileWindowRetries }

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
        val del: DeliveryStats
        synchronized(deliveryLock) {
            val pending = runCatching { pendingProvider() }.getOrDefault(0)
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
        val failuresWin = receivedFailuresQ.statsSince(now, windowMs)
        val failed: FailedStats
        synchronized(bleLock) {
            failed = FailedStats(
                receivedDecodeFailures = receivedFailures,
                receivedDecodeRatePerSec = failuresWin[0] / (windowMs / 1000.0),
                unconfirmed = del.pending,
                bleWriteFailed = writeFailed,
                bleNotifyFailed = notifyFailed,
            )
        }
        return DebugSnapshot(
            timestampMs = now,
            frames = frames,
            ble = ble,
            // 外部数据提供器不信任：任一异常只影响对应字段，绝不让快照/调用方崩溃
            peers = runCatching { peersProvider() }.getOrDefault(emptyList()),
            routeEntries = runCatching { routeEntriesProvider() }.getOrDefault(0),
            sessions = runCatching { sessionsProvider() }.getOrDefault(0),
            pendingInvites = runCatching { pendingInvitesProvider() }.getOrDefault(0),
            delivery = del,
            file = runCatching { fileStatsProvider() }.getOrDefault(FileStats(windowRetries = fileWindowRetries)),
            system = SystemStats(
                uptimeMs = now - startedAt,
                serviceStarted = runCatching { serviceStartedProvider() }.getOrDefault(false),
                bluetoothEnabled = runCatching { bluetoothEnabledProvider() }.getOrDefault(false),
                totalMemoryKb = runtime.totalMemory() / 1024,
                freeMemoryKb = runtime.freeMemory() / 1024,
            ),
            failures = failed,
        )
    }

    fun reset() {
        sentQ.values.forEach { it.reset() }; sentQ.clear()
        recvQ.values.forEach { it.reset() }; recvQ.clear()
        sentTotal.clear(); sentBytesTotal.clear(); recvTotal.clear(); recvBytesTotal.clear()
        broadcastQ.reset()
        receivedFailuresQ.reset(); receivedFailures = 0
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

    // ---- 控制总线（UI → MeshService 控制面；内核仅转发，不持有服务引用）----
    private var controlHandler: ((DebugControl) -> Unit)? = null

    fun attachControls(handler: (DebugControl) -> Unit) {
        controlHandler = handler
    }

    fun issue(cmd: DebugControl) {
        controlHandler?.invoke(cmd)
    }
}
