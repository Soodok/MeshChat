package com.meshchat.app.mesh.service

import android.util.Log
import com.meshchat.app.mesh.debug.DebugControl
import com.meshchat.app.mesh.debug.DebugStats
import com.meshchat.app.mesh.debug.FileStats
import com.meshchat.app.mesh.debug.FrameKind
import com.meshchat.app.mesh.debug.PeerDebugInfo
import com.meshchat.app.mesh.debug.RouteDecision
import com.meshchat.app.mesh.identity.LocalIdentity
import com.meshchat.app.mesh.protocol.FileAckBody
import com.meshchat.app.mesh.protocol.FileBody
import com.meshchat.app.mesh.protocol.File3
import com.meshchat.app.mesh.protocol.FileBodyV2
import com.meshchat.app.mesh.protocol.FrameType
import com.meshchat.app.mesh.protocol.MeshEnvelope
import com.meshchat.app.mesh.protocol.MeshFrame
import com.meshchat.app.mesh.protocol.MeshJson
import com.meshchat.app.mesh.protocol.PresenceBody
import com.meshchat.app.mesh.protocol.TextBody
import com.meshchat.app.mesh.quality.BluetoothQuality
import com.meshchat.app.mesh.routing.DedupCache
import com.meshchat.app.mesh.routing.ForwardDecision
import com.meshchat.app.mesh.routing.ForwardingDecision
import com.meshchat.app.mesh.storage.MessageStatus
import com.meshchat.app.mesh.storage.MeshStore
import com.meshchat.app.mesh.storage.OutboxEntry
import com.meshchat.app.mesh.storage.PeerEntity
import com.meshchat.app.mesh.storage.StoredMessage
import com.meshchat.app.mesh.transfer.FileSaver
import com.meshchat.app.mesh.transfer.FileTransferManager
import com.meshchat.app.mesh.transfer.TransferStatus
import com.meshchat.app.mesh.transport.MeshPeerInfo
import com.meshchat.app.mesh.transport.MeshTransport
import com.meshchat.app.mesh.transport.PeerPresence
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val DEFAULT_TTL = 8
private const val OUTBOX_TTL_MS = 60_000L
private const val REFRESH_INTERVAL_MS = 200L      // 探测刷新周期 0.2s
private const val HEARTBEAT_INTERVAL_MS = 1_000L  // PING 广播周期：1s 校准一次
private const val LOST_HEARTBEAT_MS = 2_000L      // 超过该时长无任何 PING/PONG/扫描帧 → 判失联（容忍 1 帧丢失，更灵敏）
private const val OFFLINE_THRESHOLD_MS = 15_000L  // 无心跳超过该时长 → 离线（保留显示置黑，更快反映失联）
private const val SEARCHING_TIMEOUT_MS = 6_000L   // 持久化恢复后 6 秒仍未找到 → 自动失联（避免无限寻找）
private const val RECEIPT_TIMEOUT_MS = 3_000L     // 消息发出后未收到送达回执的等待时间，超时重发（更快确认）
private const val MAX_RESEND_INTERVAL_MS = 30_000L // 重发退避封顶：3s→6s→12s→24s→30s，永不 FAILED（零容错，持续确认）
private const val RECEIPT_REPEAT_INTERVAL_MS = 3_000L    // 接收方重复回执周期（近期消息周期性补发）
private const val RECEIPT_REPEAT_WINDOW_MS = 180_000L    // 重复回执窗口：收到消息后 3min 内周期性补发（覆盖长时间后台空窗）

// ===== 缓存维护（移植队友 v1.0.11）：长期运行清理过期投递记录与长期未见节点 =====
private const val CACHE_MAINTENANCE_INTERVAL_MS = 6 * 60 * 60 * 1_000L  // 缓存维护周期：每 6h 一次（tick 节流）
private const val PEER_CACHE_RETENTION_MS = 30L * 24 * 60 * 60 * 1_000L // 节点缓存保留：30 天未见即清除（不删聊天记录/已存文件）

// ===== v1.1.0 多跳中继 =====
private const val OUTBOX_RESEND_INTERVAL_MS = 1_000L  // 中继转发 outbox 重发节流：每条目每 1s 最多重发一次
private const val OUTBOX_MAX_ATTEMPTS = 3             // 中继转发 outbox 重试上限：3 次后放弃（尽力而为，转发丢帧由 dedup 防重复）
private const val PING_RELAYS_EVERY = 3               // PING 每 3 次（3s）携带一次 relays 路由信息（绑定 1s 心跳节流控带宽）
private const val RELAY_FRESH_WINDOW_MS = 10_000L     // 一跳邻居判定：lastSeen 距今 ≤10s 才计入 relays（新鲜邻居才值得广播）
private const val ROUTE_EXPIRE_MS = 30_000L           // 路由条目超时：中继 3 次心跳周期（~30s）未再确认即失效移除
private const val FORWARD_JITTER_MIN_MS = 50L         // 转发抖动下界：错开多机同步转发，防广播风暴
private const val FORWARD_JITTER_MAX_MS = 250L

/** 默认广播发射功率(dBm)：Android 四档最高 ADVERTISE_TX_POWER_HIGH = +1dBm。 */
private const val DEFAULT_TX_POWER_DBM = 1
/** 合法广播功率档(dBm)：UltraLow/Low/Medium/High。 */
private val TX_POWER_LEVELS = intArrayOf(1, -7, -15, -21)

private const val TAG = "MeshSvc"

/** 接受邀请后持续重发确认的上限：超过则停止，避免无限广播空耗。 */
internal const val ACK_RETRY_TIMEOUT_MS = 30_000L

/** RFCOMM 高速通道最小契约：MeshService 只依赖连接查询/点对点写/生命周期，不绑定具体实现（可测替身）。 */
interface RfcommChannel {
    val incoming: SharedFlow<MeshFrame>
    fun start()
    fun stop()
    suspend fun connect(peerId: String, address: String): Boolean
    fun isConnectedTo(peerId: String): Boolean
    fun sendTo(peerId: String, frame: MeshFrame)
}

class MeshService(
    private val transport: MeshTransport,
    private val store: MeshStore,
    private val identity: LocalIdentity,
    private val dedup: DedupCache,
    private val fileSaver: FileSaver = object : FileSaver {
        override fun save(tmpFile: File, fileName: String, mime: String): String? = null
    },
    private val tmpDir: () -> File = { File(System.getProperty("java.io.tmpdir"), "meshchat_transfers") },
    /** RFCOMM 高吞吐通道（可选）：文件帧优先走它，无连接回退 BLE broadcast。 */
    private val rfcomm: RfcommChannel? = null,
    /** 会话关系持久化（默认内存 Noop，不持久化；生产注入 SharedPrefsSessionStore）。 */
    private val sessionStore: SessionStore = object : SessionStore {
        override fun load(): Set<String> = emptySet()
        override fun save(sessions: Set<String>) {}
    },
    /** 收到新消息回调（fromId/fromName/text）：MeshChatService 注入用于弹通知。 */
    private val onIncomingMessage: (fromId: String, fromName: String, text: String) -> Unit = { _, _, _ -> },
    /** 文件接收完成回调（fileName）：通知「文件已保存」。 */
    private val onFileSaved: (fileName: String) -> Unit = {},
    /** 调试统计内核（默认独立实例，生产由 Application 注入共享单例）。 */
    private val debugStats: DebugStats = DebugStats(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false
    private var receiveJob: Job? = null
    private var peerJob: Job? = null
    private var tickJob: Job? = null
    private var heartbeatJob: Job? = null
    /** 上次缓存维护时刻：tick 节流（6h 一次），启动时 force 一次。 */
    private var lastCacheMaintenanceAt = 0L

    /** 文件传输引擎：发送状态机 + 接收重组 + 窗口批量 bitmap 确认。 */
    private val transfer = FileTransferManager(
        transport = transport, shortId = identity.shortId, saver = fileSaver,
        scope = scope, tmpDirProvider = tmpDir,
        sendFrame = { dstId, frame -> sendFrame(dstId, frame) },
        debugStats = debugStats,
        onProgress = { p ->
            // 终态同步落库状态（fileId 即消息 id）
            when (p.status) {
                TransferStatus.DONE -> store.updateMessageStatus(p.fileId, MessageStatus.DELIVERED)
                TransferStatus.FAILED -> store.updateMessageStatus(p.fileId, MessageStatus.FAILED)
                else -> Unit
            }
        },
        onSaved = { _, fileId, fileName, mime, size, uri ->
            // 接收收齐：回填 Downloads URI 并标记送达
            store.updateFileMeta(fileId, fileMetaJson(fileName, mime, size, uri))
            store.updateMessageStatus(fileId, MessageStatus.DELIVERED)
            onFileSaved(fileName)   // 通知「文件已保存」
        },
    )

    /** 接收端已落库的文件 id（占位消息去重）。 */
    private val receivedFiles = mutableSetOf<String>()

    /** 文件传输进度（发送/接收统一，含终态）。 */
    val fileProgress: StateFlow<com.meshchat.app.mesh.transfer.FileProgress?> = transfer.progress

    private val _peers = MutableStateFlow<List<MeshPeerInfo>>(emptyList())
    val peers: StateFlow<List<MeshPeerInfo>> = _peers.asStateFlow()

    // ===== v1.1.0 多跳中继：路由表 =====
    /** 2 跳路由条目：远端节点 -> (经由中继 shortId, 跳数, 最后确认时刻)。内存态，重启重建。 */
    private data class RouteEntry(val via: String, val hops: Int, val lastSeenAt: Long)
    private val routeEntries = ConcurrentHashMap<String, RouteEntry>()
    /** PING 计数器：每 PING_RELAYS_EVERY 次心跳携带一次 relays 路由信息。 */
    private var pingCount = 0
    /** PING 序列号：每次广播递增（v1.1.16 协议层信号强度统计用）。 */
    private var pingSeq = 0
    /** 中继转发 outbox 重发状态：id -> 上次重发时刻 / 重试次数（内存态）。 */
    private val outboxLastSent = HashMap<String, Long>()
    private val outboxAttempts = HashMap<String, Int>()

    /**
     * 链路质量窗口（v1.1.16）：基于对端 PING 序列号缺口估算收包成功率——协议层信号强度，不依赖系统 RSSI。
     * 收到 seq 时把 [lastSeq+1, seq-1] 判为丢失、seq 判为收到；窗口按序号前移，超过 size 重建（对端重启/长期失联后重新累计）。
     */
    internal class LinkQualityWindow(private val size: Int = 64) {
        private val hit = BooleanArray(size)
        private var baseSeq = 0   // 窗口起点 seq（含）
        private var lastSeq = 0   // 最后成功解析的 seq
        private var filled = 0    // 窗口内已判定格数
        private var got = 0       // 窗口内命中（收到）格数

        /** 收到带 seq 的 PING 后更新统计；返回窗口内成功率(0-1)，样本不足返回 -1。 */
        fun onPing(seq: Int): Double {
            if (seq <= 0) return -1.0
            if (lastSeq == 0 || seq - baseSeq >= size) {
                // 首样本 / 序号大幅跳变（重启或长期失联）→ 重建窗口
                java.util.Arrays.fill(hit, false)
                baseSeq = seq; lastSeq = seq; filled = 0; got = 0
                mark(seq, true)
                return -1.0
            }
            if (seq <= lastSeq) return rate() // 乱序/重复，忽略
            for (s in lastSeq + 1..seq) mark(s, s == seq) // 缺口判丢、当前判收
            lastSeq = seq
            return rate()
        }

        private fun mark(seq: Int, isHit: Boolean) {
            val idx = seq - baseSeq
            if (idx !in 0 until size) return
            if (hit[idx]) return // 已判定，不重复计
            hit[idx] = true
            filled++
            if (isHit) got++
        }

        /** 窗口内成功率(0-1)；无样本返回 -1。 */
        fun rate(): Double = if (filled > 0) got.toDouble() / filled else -1.0

        /** 窗口内已判定样本数。 */
        val samples: Int get() = filled
    }

    /** 一跳邻居链路质量：shortId -> 收包成功率窗口（内存态，重启清零）。 */
    private val peerLinkQuality = ConcurrentHashMap<String, LinkQualityWindow>()

    /** 探测刷新周期：UI 节点状态每 200ms 更新一次（含 RSSI 与失联标注）。 */
    private val peerEntries = ConcurrentHashMap<String, PeerEntry>()

    private data class PeerEntry(var info: MeshPeerInfo, var lastSeen: Long, var lost: Boolean)

    /** 上次 PING 广播时刻（tick 200ms 节流到 1s）。 */
    private var lastPingAt = 0L

    // ---- 调试主动控制（volatile 可调；默认值=常量，未调节时行为零变化；内存态重启回默认）----
    @Volatile private var heartbeatIntervalMs: Long = HEARTBEAT_INTERVAL_MS
    @Volatile private var lostHeartbeatMs: Long = LOST_HEARTBEAT_MS
    @Volatile private var resendBaseMs: Long = RECEIPT_TIMEOUT_MS
    @Volatile private var resendMaxMs: Long = MAX_RESEND_INTERVAL_MS
    /** 广播发射功率(dBm)：默认 +1dBm（Android 四档最高）；仅 1/-7/-15/-21 合法。 */
    @Volatile private var txPowerDbm: Int = DEFAULT_TX_POWER_DBM

    /** 服务启动时刻（用于持久化恢复节点的寻找超时判定）。 */
    private val startupAt = System.currentTimeMillis()

    /** 待送达确认的 TEXT：id -> (信封, 上次发送时刻, 重试次数, 广播确认键)。回执（RECEIPT）是广播帧可能丢失，需超时重发。 */
    private class PendingText(val envelope: MeshEnvelope, var lastSentAt: Long, var retries: Int = 0, val ackKey: ByteArray)
    private val pendingReceipts = LinkedHashMap<String, PendingText>()

    /** 近期收到的消息：msgId -> (信封, 收到时刻)。60s 窗口内周期性重复回执，确保发送方必能收敛。 */
    private val recentReceived = LinkedHashMap<String, Pair<MeshEnvelope, Long>>()
    private var lastReceiptRepeatAt = 0L

    /** 本机短 ID（对端寻址标识）。 */
    val shortId: String get() = identity.shortId

    /** 已建立对话关系的对端节点集合。 */
    private val _sessions = MutableStateFlow<Set<String>>(emptySet())
    val sessions: StateFlow<Set<String>> = _sessions.asStateFlow()

    /** 已发送邀请、等待对方接受的对端节点集合（发起方反馈状态）。 */
    private val _pendingInvites = MutableStateFlow<Set<String>>(emptySet())
    val pendingInvites: StateFlow<Set<String>> = _pendingInvites.asStateFlow()

    /** 收到的待确认对话请求：peerId -> 请求时间戳。 */
    private val _invites = MutableStateFlow<Map<String, Long>>(emptyMap())
    val invites: StateFlow<Map<String, Long>> = _invites.asStateFlow()

    /** 已接受邀请、正在向对端持续重发确认的节点：peerId -> 重发开始时间戳。 */
    private val _ackRetries = MutableStateFlow<Map<String, Long>>(emptyMap())

    fun start() {
        if (started) return // 幂等：防止「开始附近发现」被重复点击导致重复启动
        started = true
        prunePersistentCaches(System.currentTimeMillis(), force = true)
        _sessions.value = sessionStore.load()   // 重启恢复已建立的会话关系
        restoreKnownPeers()                     // 重启恢复已知节点（寻找中状态，心跳/扫描补在线）
        restorePendingReceipts()                // 重启恢复未确认消息（进程被杀后重发不丢失）
        transport.start()
        rfcomm?.start()
        receiveJob = scope.launch {
            // 逐帧隔离异常（移植队友 v1.0.12）：单帧处理异常不终止整个接收流
            transport.incoming.collect { frame ->
                runCatching { handleFrame(frame) }
                    .onFailure { Log.w(TAG, "incoming frame handling failed", it) }
            }
        }
        // RFCOMM 通道合流：文件帧经高速通道到达时同样走 handleFrame
        rfcomm?.incoming?.let { flow ->
            scope.launch {
                flow.collect { frame ->
                    runCatching { handleFrame(frame) }
                        .onFailure { Log.w(TAG, "RFCOMM frame handling failed", it) }
                }
            }
        }
        peerJob = scope.launch {
            transport.foundPeers.collect { info ->
                runCatching {
                    // 广播确认（第三通道）：对端随扫描响应广播"已收到的消息确认键"——
                    // 无需任何 GATT 连接，双方在无线电范围内且都在扫描即可交换确认（彻底绕开连接状态问题）
                    info.ackKeys.forEach { key -> confirmByAckKey(key) }
                    val now = System.currentTimeMillis()
                    val existing = peerEntries[info.shortId]
                    // 扫描帧不携带昵称（displayName 为空），保留心跳已学到的昵称，避免覆盖；
                    // lastSeenAt 每次扫描帧到达都刷新 → info 必变 → _peers 流必 emit
                    val displayName = existing?.info?.displayName ?: ""
                    peerEntries[info.shortId] = PeerEntry(
                        if (existing != null) info.copy(displayName = displayName, lastSeenAt = now) else info.copy(lastSeenAt = now),
                        lastSeen = now, lost = false,
                    )
                    // 扫描也落库：节点持久化不依赖 PING 交互，重启后必定恢复
                    store.upsertPeer(info.shortId, displayName.ifBlank { info.displayName }, now, info.hops)
                }.onFailure { Log.w(TAG, "peer update handling failed", it) }
            }
        }
        tickJob = scope.launch {
            while (isActive) {
                delay(REFRESH_INTERVAL_MS)
                val now = System.currentTimeMillis()
                heartbeatTick(now)
                resendPendingReceipts(now)
                // 缓存维护：启动 force + 每 6h 节流（清过期 outbox/30 天未见节点）
                prunePersistentCaches(now)
                // 中继转发 outbox 重发（每 1s 节流，≤3 次）：转发丢帧兜底，尽力而为
                resendOutbox(now)
                // 会话状态机每 0.2s 检测一次：向已接受邀请的对端持续重发确认，直至其确认或超时
                tickSessionState(now)
                // 文件传输接收超时清理（60s 无进展丢弃）
                transfer.tick(now)
            }
        }
        // 独立心跳协程：与 200ms tick 解耦，支持 50ms 级高频调试档（间隔实时读取可动态调节）
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(heartbeatIntervalMs.coerceIn(50L, 10_000L))
                sendPingIfDue()
            }
        }
        // 调试中心快照数据源（纯读取，不参与收发逻辑）
        debugStats.attachProviders(
            pending = { pendingReceipts.size },
            peers = {
                peerEntries.entries.map { (id, e) ->
                    val info = e.info
                    val lq = peerLinkQuality[id]
                    PeerDebugInfo(
                        shortId = id, displayName = info.displayName,
                        rssi = info.rssi, bars = BluetoothQuality.bars(info.rssi),
                        presence = info.presence.name, hops = info.hops,
                        relayVia = routeEntries[id]?.via,
                        lastSeenAgoMs = if (info.lastSeenAt > 0) (System.currentTimeMillis() - info.lastSeenAt).coerceAtLeast(0) else -1,
                        txPower = info.txPower,
                        linkSuccessRate = lq?.rate() ?: -1.0,
                        linkSamples = lq?.samples ?: 0,
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
        // 调试主动控制：UI 调节经 DebugStats 控制总线转发到本服务控制面
        debugStats.attachControls { cmd ->
            when (cmd) {
                is DebugControl.SetHeartbeat -> setHeartbeat(cmd.intervalMs)
                is DebugControl.SetResendPolicy -> setResendPolicy(cmd.baseMs, cmd.maxMs)
                DebugControl.SuspendSignaling -> suspendSignaling()
                DebugControl.ResumeSignaling -> resumeSignaling()
                is DebugControl.SetTxPower -> setTxPower(cmd.txPowerDbm)
                DebugControl.BroadcastPing -> broadcastPing()
                DebugControl.ResetControls -> resetDebugControls()
            }
        }
    }

    /** 移除可再生的持久化缓存（过期 outbox、长期未见节点）；聊天记录与已存文件保留。 */
    private fun prunePersistentCaches(now: Long, force: Boolean = false) {
        if (!force && now - lastCacheMaintenanceAt < CACHE_MAINTENANCE_INTERVAL_MS) return
        lastCacheMaintenanceAt = now
        runCatching {
            store.pruneExpiredOutbox(now)
            store.prunePeersNotSeenSince(now - PEER_CACHE_RETENTION_MS)
        }.onFailure { Log.w(TAG, "cache maintenance failed", it) }
    }

    /**
     * 强制重新搜索：停掉并重建 BLE 传输层，清空遗留状态。
     *
     * 适用场景：进入 App 时蓝牙未开启（transport.start() 静默失败但 started 已置位），
     * 之后开启蓝牙——此时 start() 幂等守卫会直接返回，BLE 广播/扫描永远不会重建，
     * 只能重进 App 恢复。此方法绕过守卫，stop+start 重建传输层（连接/订阅/队列全清）。
     */
    fun restartDiscovery() {
        Log.w(TAG, "restartDiscovery: rebuild BLE transport, clear stale state")
        runCatching { transport.stop() }
        runCatching { transport.start() }
    }

    fun stop() {
        if (!started) return
        started = false
        receiveJob?.cancel()
        peerJob?.cancel()
        tickJob?.cancel()
        heartbeatJob?.cancel()
        transfer.cancel()
        transport.stop()
        rfcomm?.stop()
        // 注意：不 cancel scope——stop 后 start() 需能再次 launch（修复"服务停止后无法再次启动"）
    }

    fun sendText(convId: String, dstId: String, text: String) {
        val envelope = MeshEnvelope(
            id = UUID.randomUUID().toString(),
            kind = "TEXT",
            srcId = identity.shortId,
            dstId = dstId,
            convId = convId,
            ttl = DEFAULT_TTL,
            ts = System.currentTimeMillis(),
            body = TextBody(text, displayName = identity.displayName),
        )
        store.insertMessage(
            StoredMessage(
                id = envelope.id, convId = convId, kind = "TEXT",
                srcId = envelope.srcId, dstId = dstId, text = text, ts = envelope.ts,
            ),
        )
        // 登记待确认：回执（RECEIPT）是广播帧可能丢失，由 resendPendingReceipts 超时重发收敛
        pendingReceipts[envelope.id] = PendingText(envelope, System.currentTimeMillis(), ackKey = ackKeyFor(envelope.id))
        route(envelope)
    }

    /** 发送文件：fileId 即消息 id（落库占位）；返回 null 表示传输中（串行约束）或目标为空。 */
    fun sendFile(convId: String, dstId: String, openSource: () -> java.io.InputStream, fileName: String, mime: String, size: Long): String? {
        if (dstId.isBlank()) return null
        // BLE 帧预算：文件名/MIME 截断（长元数据会把整帧推超 MTU 512 的 509B 载荷，对端收不到）
        val safeName = if (fileName.length <= 16) fileName else fileName.take(16)
        val safeMime = if (mime.length <= 30) mime else mime.take(30)
        val fileId = transfer.sendFile(convId, dstId, openSource, safeName, safeMime, size) ?: return null
        store.insertMessage(
            StoredMessage(
                id = fileId, convId = convId, kind = "FILE", srcId = identity.shortId,
                dstId = dstId, text = safeName,
                fileMeta = fileMetaJson(safeName, safeMime, size, null),
                status = MessageStatus.SENDING, ts = System.currentTimeMillis(),
            ),
        )
        return fileId
    }

    /** 文件帧发送路由：RFCOMM 已连接则走高速通道，否则 BLE broadcast 兜底。 */
    private fun sendFrame(dstId: String, frame: MeshFrame) {
        recordSentFrame(frame)
        if (rfcomm != null && rfcomm.isConnectedTo(dstId)) rfcomm.sendTo(dstId, frame)
        else transport.broadcast(frame)
    }

    /** 发送统计（统一出口）：RECEIPT 帧按 RECEIPT 计，DATA 帧按信封 kind 计。 */
    private fun recordSentFrame(frame: MeshFrame) {
        val kind = if (frame.type == FrameType.RECEIPT) FrameKind.RECEIPT
            else runCatching { DebugStats.kindOfEnvelope(MeshJson.decodeEnvelope(frame.payloadText).kind) }
                .getOrDefault(FrameKind.OTHER)
        debugStats.recordSent(kind, frame.payload.size)
    }

    /** 会话建立后按 BLE 扫描到的对端 MAC 发起 RFCOMM 连接（配对弹窗由系统处理，失败静默回退 BLE）。 */
    private fun connectRfcomm(peerId: String) {
        val rf = rfcomm ?: return
        if (rf.isConnectedTo(peerId)) return
        val address = _peers.value.firstOrNull { it.shortId == peerId }?.deviceAddress ?: return
        scope.launch {
            Log.d(TAG, "rfcomm connect attempt peer=$peerId addr=$address")
            rf.connect(peerId, address)
        }
    }

    /** fileMeta 列 JSON 序列化（fileName/mime 转义，防止引号破坏 JSON）。 */
    private fun fileMetaJson(fileName: String, mime: String, size: Long, uri: String?): String {
        fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
        return """{"fileName":"${esc(fileName)}","mime":"${esc(mime)}","size":$size,"downloadsUri":"${uri?.let { esc(it) } ?: ""}"}"""
    }

    /** 向对端发起对话请求（建立对话关系的前置握手）。 */
    fun sendInvite(peerId: String) {
        if (peerId in _sessions.value) return
        _pendingInvites.update { it + peerId }
        route(
            MeshEnvelope(
                id = UUID.randomUUID().toString(),
                kind = "INVITE",
                srcId = identity.shortId,
                dstId = peerId,
                convId = "conv-$peerId",
                ttl = DEFAULT_TTL,
                ts = System.currentTimeMillis(),
                body = TextBody("对话请求", displayName = identity.displayName),
            ),
        )
    }

    /** 接受对话请求：建立会话关系并启动持续确认（每 0.2s 重发 INVITE_ACK，直至对端确认或超时）。 */
    fun acceptInvite(peerId: String) {
        _sessions.update { it + peerId }
        sessionStore.save(_sessions.value)
        _invites.update { it - peerId }
        _ackRetries.update { it + (peerId to System.currentTimeMillis()) }
        sendInviteAck(peerId)
    }

    /** 发送对话接受确认帧。 */
    private fun sendInviteAck(peerId: String) {
        val frame = MeshFrame(
            FrameType.DATA,
            MeshJson.encodeEnvelope(
                MeshEnvelope(
                    id = UUID.randomUUID().toString(),
                    kind = "INVITE_ACK",
                    srcId = identity.shortId,
                    dstId = peerId,
                    convId = "conv-$peerId",
                    ttl = DEFAULT_TTL,
                    ts = System.currentTimeMillis(),
                    body = TextBody("已接受"),
                ),
            ).toByteArray(),
        )
        recordSentFrame(frame)
        transport.broadcast(frame)
    }

    /**
     * 启动时恢复已知节点（寻找中状态）：主界面不再空，心跳/扫描到达即转在线。
     * peers 表为空时从消息历史反推对端兜底。Room 访问异常静默降级（不阻塞启动）。
     */
    private fun restoreKnownPeers() {
        var known = runCatching { store.loadPeers() }.getOrDefault(emptyList())
        if (known.isEmpty()) {
            // 兜底：历史消息中的会话对端（老版本升级上来 peers 表可能为空）
            val fromHistory = runCatching { store.loadKnownPeerIds() }.getOrDefault(emptyList())
            if (fromHistory.isNotEmpty()) {
                Log.w(TAG, "peers table empty, restore ${fromHistory.size} peers from message history")
                known = fromHistory.map { PeerEntity(shortId = it, displayName = "", lastSeen = 0L, hops = 1) }
            }
        }
        Log.d(TAG, "restore ${known.size} known peers from store")
        for (p in known) {
            peerEntries.putIfAbsent(
                p.shortId,
                PeerEntry(
                    MeshPeerInfo(
                        shortId = p.shortId, deviceAddress = "", rssi = 0, hops = p.hops,
                        displayName = p.displayName, lost = true, presence = PeerPresence.SEARCHING,
                    ),
                    lastSeen = 0L, lost = true,
                ),
            )
        }
        refreshPeers()
    }

    /**
     * 重启恢复未确认消息：进程被杀后 pendingReceipts 丢失，从库中 SENDING 状态的 TEXT 重建重发队列。
     */
    private fun restorePendingReceipts() {
        val undelivered = store.loadUndeliveredTexts()
        if (undelivered.isEmpty()) return
        Log.w(TAG, "restore ${undelivered.size} undelivered texts for retransmission")
        for (m in undelivered) {
            pendingReceipts.putIfAbsent(
                m.id,
                PendingText(
                    MeshEnvelope(
                        id = m.id, kind = "TEXT", srcId = m.srcId, dstId = m.dstId, convId = m.convId,
                        ttl = DEFAULT_TTL, ts = m.ts, body = TextBody(m.text ?: ""),
                    ),
                    // 立即可重发（视为已超时），对方在线（PING）即收敛
                    lastSentAt = System.currentTimeMillis() - RECEIPT_TIMEOUT_MS,
                    ackKey = ackKeyFor(m.id),
                ),
            )
        }
    }

    /**
     * 心跳 tick（tick 循环每 200ms 调用）：
     * 按三色状态机更新各节点：在线绿 / 断线重连黄 / 离线黑（保留不删除）。
     * PING 广播已由独立心跳协程（heartbeatJob）负责——支持 50ms 级高频调试档。
     */
    internal fun heartbeatTick(now: Long) {
        // 重复回执：近期收到的消息每 3s 补发一次回执（60s 窗口），发送方在线时段内必达
        if (now - lastReceiptRepeatAt >= RECEIPT_REPEAT_INTERVAL_MS) {
            lastReceiptRepeatAt = now
            val rit = recentReceived.entries.iterator()
            while (rit.hasNext()) {
                val (msgId, pair) = rit.next()
                if (now - pair.second > RECEIPT_REPEAT_WINDOW_MS) rit.remove()
                else sendReceipt(pair.first)
            }
        }
        val iterator = peerEntries.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next().value
            val age = now - entry.lastSeen
            val presence = when {
                entry.lastSeen == 0L && now - startupAt < SEARCHING_TIMEOUT_MS -> PeerPresence.SEARCHING  // 持久化恢复，6s 内寻找中
                entry.lastSeen == 0L -> PeerPresence.OFFLINE              // 6s 仍未找到 → 自动失联
                age < lostHeartbeatMs -> PeerPresence.ONLINE            // 有心跳 → 在线
                age < OFFLINE_THRESHOLD_MS -> PeerPresence.RECONNECTING   // 短暂失联 → 断线重连中
                else -> PeerPresence.OFFLINE                              // 长时间无响应 → 离线（保留）
            }
            entry.lost = age > lostHeartbeatMs
            entry.info = entry.info.copy(lost = entry.lost, presence = presence)
        }
        // v1.1.0 路由清理：中继失联（lastSeen 超 OFFLINE_THRESHOLD 或已移除）→ 移除经它的路由；
        // 条目自身超时（ROUTE_EXPIRE_MS 未再确认，即中继 3 次心跳周期）→ 移除。
        val rit = routeEntries.entries.iterator()
        while (rit.hasNext()) {
            val (peerId, r) = rit.next()
            val relay = peerEntries[r.via]
            if (relay == null || now - relay.lastSeen > OFFLINE_THRESHOLD_MS || now - r.lastSeenAt > ROUTE_EXPIRE_MS) {
                Log.d(TAG, "route expired: $peerId via ${r.via}")
                rit.remove()
            }
        }
        refreshPeers()
    }

    /**
     * 待确认 TEXT 重发（tick 每 200ms 调用；pingTriggered = 对方心跳在线时立即重发）：
     * 指数退避（5s→60s 封顶），**永不标记 FAILED、永不从队列移除**——直到收到回执（DELIVERED）为止，
     * 覆盖任意断线/后台空窗（零容错）。配合接收方 60s 重复回执窗口，双方在线时段内必收敛。
     */
    internal fun resendPendingReceipts(now: Long, pingTriggered: Boolean = false) {
        val it = pendingReceipts.entries.iterator()
        while (it.hasNext()) {
            val (id, p) = it.next()
            // 退避间隔：重试越多间隔越长（5s, 10s, 20s, 40s, 60s 封顶）
            val gap = if (pingTriggered) 0L else minOf(resendBaseMs * (1L shl minOf(p.retries, 4)), resendMaxMs)
            if (now - p.lastSentAt < gap) continue
            p.retries++
            p.lastSentAt = now
            Log.w(TAG, "resend text $id retry=${p.retries}${if (pingTriggered) " (ping-triggered)" else ""}")
            debugStats.recordResend(id)
            val frame = MeshFrame(FrameType.DATA, MeshJson.encodeEnvelope(p.envelope).toByteArray())
            recordSentFrame(frame)
            transport.broadcast(frame)
        }
    }

    /** UI 回到前台（onResume）时调用：立即按 ping-triggered 语义扫一遍未确认消息，不等退避计时。 */
    fun resendPendingNow() {
        if (!started) return
        resendPendingReceipts(System.currentTimeMillis(), pingTriggered = true)
    }

    /** 广播 PING（带本机昵称），对端收到回 PONG。每 PING_RELAYS_EVERY 次携带一跳邻居列表（路由信息搭心跳便车）。 */
    private fun sendPing() {
        pingCount++
        pingSeq++
        // 路由信息节流：前 2 次心跳不带（空列表省带宽），第 3 次（3s）带一次
        val relays = if (pingCount % PING_RELAYS_EVERY == 0) currentRelays() else emptyList()
        val env = MeshEnvelope(
            id = UUID.randomUUID().toString(), kind = "PING",
            srcId = identity.shortId, dstId = "", convId = "conv-${identity.shortId}",
            ttl = DEFAULT_TTL, ts = System.currentTimeMillis(),
            body = PresenceBody(identity.displayName, relays = relays, seq = pingSeq),
        )
        val frame = MeshFrame(FrameType.DATA, MeshJson.encodeEnvelope(env).toByteArray())
        recordSentFrame(frame)
        transport.broadcast(frame)
    }

    /**
     * 心跳到期检查（独立心跳协程每心跳间隔调用一次；now 可注入便于测试）。
     * 与 200ms tick 解耦，支持 50ms 级高频调试档——BLE 广播受系统约 100ms 最小间隔限制，
     * 高频档在已建立 GATT 连接通道（写/notify）上真实生效。
     */
    internal fun sendPingIfDue(now: Long = System.currentTimeMillis()) {
        if (now - lastPingAt >= heartbeatIntervalMs) {
            lastPingAt = now
            sendPing()
        }
    }

    // ===== 调试主动控制（UI 调节经 DebugStats 控制总线下发；全部幂等可逆）=====
    /** 心跳间隔（失联阈值保持 LOST_HEARTBEAT_MS=2s 固定，不随心跳联动——用户决策）。 */
    fun setHeartbeat(intervalMs: Long) {
        heartbeatIntervalMs = intervalMs.coerceIn(50L, 10_000L)
    }

    /** 消息重发退避（基础间隔 + 封顶）。 */
    fun setResendPolicy(baseMs: Long, maxMs: Long) {
        resendBaseMs = baseMs.coerceIn(500L, 60_000L)
        resendMaxMs = maxMs.coerceIn(baseMs, 300_000L)
    }

    /** 暂停发现层（广播+扫描；已建立 GATT 连接收发不受影响）。 */
    fun suspendSignaling() = transport.suspendDiscovery()

    /** 恢复发现层。 */
    fun resumeSignaling() = transport.resumeDiscovery()

    /** 广播发射功率(dBm)：仅接受 Android 四档（1/-7/-15/-21），非法忽略；重启广播生效。 */
    fun setTxPower(power: Int) {
        if (power !in TX_POWER_LEVELS) return
        txPowerDbm = power
        transport.setTxPowerLevel(power)
    }

    /** 立即广播一轮 PING（链路探测）。 */
    fun broadcastPing() = sendPing()

    /** 恢复全部默认并确保未处于暂停态。 */
    fun resetDebugControls() {
        heartbeatIntervalMs = HEARTBEAT_INTERVAL_MS
        lostHeartbeatMs = LOST_HEARTBEAT_MS
        resendBaseMs = RECEIPT_TIMEOUT_MS
        resendMaxMs = MAX_RESEND_INTERVAL_MS
        if (txPowerDbm != DEFAULT_TX_POWER_DBM) {
            txPowerDbm = DEFAULT_TX_POWER_DBM
            transport.setTxPowerLevel(DEFAULT_TX_POWER_DBM)
        }
        resumeSignaling()
    }

    /** 本机一跳邻居 shortId 列表（lastSeen 距今 ≤ RELAY_FRESH_WINDOW_MS 的新鲜节点；上限 8 个控帧预算）。 */
    private fun currentRelays(): List<String> {
        val now = System.currentTimeMillis()
        return peerEntries.entries.asSequence()
            .filter { (_, e) -> e.lastSeen > 0 && now - e.lastSeen <= RELAY_FRESH_WINDOW_MS }
            .map { it.key }
            .take(8)
            .toList()
    }

    /**
     * 从 PING 携带的 relays 学习 2 跳路由（v1.1.0）：relay 已是本机一跳节点（lastSeen 新鲜）则忽略
     * （一跳优先，不走中继）；否则记"经 srcId 可达"。相同远端多中继时保留最新确认的条目。
     */
    private fun learnRoutes(srcId: String, body: PresenceBody) {
        val relays = body.relays
        if (relays.isEmpty()) return
        val now = System.currentTimeMillis()
        for (relay in relays) {
            if (relay == identity.shortId) continue
            val direct = peerEntries[relay]
            if (direct != null && now - direct.lastSeen <= RELAY_FRESH_WINDOW_MS) continue // 一跳优先
            routeEntries[relay] = RouteEntry(via = srcId, hops = 2, lastSeenAt = now)
        }
        refreshPeers()  // 新学路由立即可见（markSeen 的刷新发生在 learnRoutes 之前）
    }

    /** 本机近期收到的、来自指定对端的消息 id 列表（最多 50 条，随心跳 PONG 回执给对端确认送达）。 */
    private fun ackIdsFor(srcId: String): List<String> =
        recentReceived.values.asSequence()
            .filter { it.first.srcId == srcId }
            .map { it.first.id }
            .take(50)
            .toList()

    /** 消息 id → 4 字节确定性确认键（String.hashCode 跨进程一致；广播载荷有限，用压缩键表示"已收到哪些消息"）。 */
    internal fun ackKeyFor(msgId: String): ByteArray {
        val h = msgId.hashCode()
        return byteArrayOf((h ushr 24).toByte(), (h ushr 16).toByte(), (h ushr 8).toByte(), h.toByte())
    }

    /** 本机近期收到的消息确认键（最多 6 个，最新优先，去重；供广播扫描响应携带，对端扫描即可确认送达）。 */
    fun broadcastAckKeys(): List<ByteArray> =
        recentReceived.values.asSequence()
            .map { ackKeyFor(it.first.id) }
            .distinctBy { it.contentHashCode() }
            .take(6)
            .toList()

    /** 广播确认：对端扫描响应携带的确认键命中待确认消息 → 立即标记送达（第三通道，与 GATT 连接状态无关）。 */
    private fun confirmByAckKey(key: ByteArray) {
        val it = pendingReceipts.entries.iterator()
        while (it.hasNext()) {
            val (id, p) = it.next()
            if (p.ackKey.contentEquals(key)) {
                it.remove()
                store.updateMessageStatus(id, MessageStatus.DELIVERED)
                debugStats.recordConfirmed(id)
                Log.d(TAG, "delivery confirmed by broadcast ack msg=$id")
            }
        }
    }

    /** 标记节点可见：更新 lastSeen；带昵称时更新显示名并落库。 */
    private fun markSeen(peerId: String, displayName: String) {
        val now = System.currentTimeMillis()
        val existing = peerEntries[peerId]
        if (existing != null) {
            existing.lastSeen = now
            existing.lost = false
            // 显式更新当前 peer 为在线；displayName 为空时保留已学昵称（不覆盖）。
            // lastSeenAt 每次帧到达都刷新 → info 必变 → _peers 流必 emit → UI 每帧刷新（远距离断连可感知）
            val updatedName = if (displayName.isNotBlank()) displayName else existing.info.displayName
            existing.info = existing.info.copy(
                displayName = updatedName,
                lost = false,
                presence = PeerPresence.ONLINE,
                lastSeenAt = now,
            )
        } else {
            peerEntries[peerId] = PeerEntry(
                MeshPeerInfo(
                    shortId = peerId, deviceAddress = "", rssi = 0, hops = 1,
                    displayName = displayName, lost = false, presence = PeerPresence.ONLINE,
                ),
                lastSeen = now, lost = false,
            )
        }
        // 总是落库（昵称可能为空/扫描帧）：保证重启后节点持久化恢复，不再依赖 PING 交换
        val name = if (displayName.isNotBlank()) displayName else existing?.info?.displayName ?: ""
        runCatching { store.upsertPeer(peerId, name, now, existing?.info?.hops ?: 1) }
        // v1.1.0：该节点变成一跳直连 → 移除"经中继可达"路由条目（一跳优先，避免重复显示）
        if (routeEntries.remove(peerId) != null) Log.d(TAG, "route via-relay dropped: $peerId now direct")
        // 同步刷新 peers 流：仅当前 peer 被显式更新为 ONLINE，其他 peer 保留 heartbeatTick 状态机裁决的 presence
        // （修复：原代码全员 copy(lost=false, presence=ONLINE) 覆盖所有 peer，与状态机打架 → 失联 peer 以 1Hz 抖动）
        refreshPeers()
    }

    /** 记录对端 PING 序列号：协议层收包成功率/丢包率统计（v1.1.16）。 */
    private fun recordLinkQuality(peerId: String, seq: Int) {
        peerLinkQuality.computeIfAbsent(peerId) { LinkQualityWindow() }.onPing(seq)
    }

    /**
     * 刷新 peers 流：一跳节点（peerEntries，presence 由状态机裁决）+ 2 跳节点（routeEntries 合成，
     * relayVia 非空）。**同 id 只保留一条**：一跳在线 → 一跳优先（忽略 2 跳条目）；
     * 一跳失联但经中继仍可达 → 用 2 跳版本覆盖（显示"经中继可达"，而非灰色离线——修复聊天列表/节点
     * 列表对失联后隔墙可达节点显示陈旧 OFFLINE 的问题，且避免同 id 重复条目让 UI firstOrNull 取到旧状态）。
     */
    private fun refreshPeers() {
        val now = System.currentTimeMillis()
        val signal = debugStats.receiveSuccessRate()  // 信号强度 = 全局接收成功率（用户指定算法）
        val result = LinkedHashMap<String, MeshPeerInfo>()
        peerEntries.values.forEach { e -> result[e.info.shortId] = e.info.copy(signalRatio = signal) }
        routeEntries.forEach { (peerId, r) ->
            val direct = peerEntries[peerId]
            val directOnline = direct != null && now - direct.lastSeen <= lostHeartbeatMs
            if (!directOnline) {
                result[peerId] = MeshPeerInfo(
                    shortId = peerId, deviceAddress = "", rssi = 0, hops = r.hops,
                    displayName = direct?.info?.displayName ?: "",  // 保留已学昵称
                    lost = false, presence = PeerPresence.ONLINE,
                    relayVia = r.via, lastSeenAt = r.lastSeenAt,
                )
            }
        }
        _peers.value = result.values.toList()
    }

    /**
     * 会话状态机（每 0.2s 由 tick 驱动一次）：
     * 对已接受邀请的对端持续重发 INVITE_ACK，直至收到对端确认或超时，确保发起方必能进入对话状态。
     */
    internal fun tickSessionState(now: Long) {
        for ((peerId, startedAt) in _ackRetries.value) {
            when {
                now - startedAt > ACK_RETRY_TIMEOUT_MS -> _ackRetries.update { it - peerId }
                else -> sendInviteAck(peerId)
            }
        }
    }

    /** 拒绝对话请求。 */
    fun rejectInvite(peerId: String) {
        _invites.update { it - peerId }
    }

    /** Removes the local conversation relationship without blocking future incoming messages. */
    fun removeSession(peerId: String) {
        _sessions.update { it - peerId }
        sessionStore.save(_sessions.value)
        _pendingInvites.update { it - peerId }
        _invites.update { it - peerId }
        _ackRetries.update { it - peerId }
    }

    /**
     * 遗忘节点：从内存表（peerEntries）+ 2 跳路由表 + peers 持久化缓存中移除，UI 立即消失、重启不恢复。
     * 若节点物理仍在附近，扫描/心跳会在数百毫秒内重新发现（这是真实存在，不是缓存残留）。
     */
    fun removePeer(peerId: String) {
        peerEntries.remove(peerId)
        routeEntries.remove(peerId)
        runCatching { store.deletePeer(peerId) }
        refreshPeers()
    }

    fun handleFrame(frame: MeshFrame) {
        when (frame.type) {
            FrameType.DATA -> {
                // v1.1.28 FILE3 二进制文件帧（MC3 魔数）：纯二进制载荷，旁路 JSON 解析直交文件传输层
                if (File3.isFile3(frame.payload)) {
                    debugStats.recordReceived(FrameKind.FILE_CHUNK, frame.payload.size)
                    handleFile3Frame(frame.payload)
                    return
                }
                val envelope = runCatching { MeshJson.decodeEnvelope(frame.payloadText) }
                    .getOrNull()
                if (envelope == null) {
                    debugStats.recordReceived(FrameKind.OTHER, frame.payload.size)
                    debugStats.recordReceivedFailure()   // 失败包：收到但无法解析的不完整帧
                    return
                }
                debugStats.recordReceived(DebugStats.kindOfEnvelope(envelope.kind), frame.payload.size)
                handleEnvelope(envelope)
            }
            FrameType.RECEIPT -> {
                debugStats.recordReceived(FrameKind.RECEIPT, frame.payload.size)
                // v1.1.0 中继转发：确认沿网络泛洪回传（A←B←C 双向可及）——"receipt-$id" 去重键防环。
                // 中间节点（非发送方）收到未见过回执转发一次；发送方收到自己的回执只确认不转发
                // （泛洪终点，停止重发，避免无谓的多一跳广播）。
                val id = Regex("\"id\":\"([^\"]+)\"").find(frame.payloadText)?.groupValues?.get(1)
                if (id != null) {
                    if (pendingReceipts.containsKey(id)) {
                        handleReceipt(frame)
                    } else {
                        val key = "receipt-$id"
                        if (!dedup.contains(key)) {
                            dedup.mark(key)
                            transport.broadcast(frame)
                        }
                        handleReceipt(frame)
                    }
                } else {
                    handleReceipt(frame)
                }
            }
            else -> Unit // HELLO/ACK/PING 由传输层处理
        }
    }

    /**
     * FILE3 二进制文件帧处理（v1.1.28）：START 帧落库占位（按 fileId 去重，与 FILE/FILE2 分支同构，
     * 独立实现避免扰动老路径），CHUNK/START 均交 FileTransferManager。帧内自带 srcId/fid，
     * 无 JSON 信封（文件帧点对点一跳，不参与多跳中继）。
     */
    private fun handleFile3Frame(payload: ByteArray) {
        when (val f = File3.parse(payload)) {
            is File3.Frame.StartFrame -> {
                val start = f.start
                if (start.srcId == identity.shortId) return // 自身回环帧
                val fileId = start.fid
                // 先落库占位（按 fileId 去重；upsert 幂等），再收块——收齐回调会置 DELIVERED，顺序不能反
                if (receivedFiles.add(fileId)) {
                    val alreadySaved = store.queryMessages("conv-${start.srcId}").any {
                        it.id == fileId && it.status == MessageStatus.DELIVERED
                    }
                    if (alreadySaved) {
                        transfer.acknowledgeCompletedFile(
                            fileId = fileId,
                            convId = "conv-${start.srcId}",
                            senderId = start.srcId,
                            totalChunks = start.totalChunks,
                        )
                        return
                    }
                    store.insertMessage(
                        StoredMessage(
                            id = fileId, convId = "conv-${start.srcId}", kind = "FILE",
                            srcId = start.srcId, dstId = identity.shortId, text = start.name,
                            fileMeta = fileMetaJson(start.name, start.mime, start.origSize, null),
                            status = MessageStatus.SENDING, ts = System.currentTimeMillis(),
                        ),
                    )
                }
                transfer.onFile3Frame(payload)
            }
            is File3.Frame.ChunkFrame -> transfer.onFile3Frame(payload)
            null -> debugStats.recordReceivedFailure()
        }
    }

    /**
     * 中继转发 outbox 重发（tick 每 200ms 调用）：转发帧丢帧兜底。
     * 每条目每 OUTBOX_RESEND_INTERVAL_MS（1s）最多重发一次；重试 OUTBOX_MAX_ATTEMPTS（3 次）或过期即移除。
     * 转发与投递共用 envelope id 去重，重复广播由对端 DedupCache 收敛。
     */
    internal fun resendOutbox(now: Long) {
        val entries = runCatching { store.nextOutbox(now) }.getOrDefault(emptyList())
        for (e in entries) {
            val last = outboxLastSent[e.id]
            if (last != null && now - last < OUTBOX_RESEND_INTERVAL_MS) continue
            val attempts = outboxAttempts[e.id] ?: e.attempts
            if (attempts >= OUTBOX_MAX_ATTEMPTS || now >= e.expireAt) {
                runCatching { store.removeOutbox(e.id) }
                outboxLastSent.remove(e.id)
                outboxAttempts.remove(e.id)
                continue
            }
            outboxLastSent[e.id] = now
            outboxAttempts[e.id] = attempts + 1
            val env = runCatching { MeshJson.decodeEnvelope(e.envelopeJson) }.getOrNull() ?: continue
            broadcastData(env)
        }
    }

    /** 查询对端是否经中继可达（v1.1.0）：命中路由表返回经由节点 shortId，否则 null。 */
    fun relayViaFor(peerId: String): String? = routeEntries[peerId]?.via

    private fun handleEnvelope(envelope: MeshEnvelope) {
        if (envelope.srcId == identity.shortId) return // 忽略自身回环帧
        Log.d(TAG, "recv kind=${envelope.kind} src=${envelope.srcId} dst=${envelope.dstId} sessions=${_sessions.value.size}")
        // 握手帧走双通道（write + notify）可能重复送达，按信封 id 去重
        if (envelope.kind == "INVITE" || envelope.kind == "INVITE_ACK") {
            if (dedup.contains(envelope.id)) return
            dedup.mark(envelope.id)
        }
        when (envelope.kind) {
            "INVITE" -> {
                // 邀请是一跳点对点帧，仅处理发往本机的（防空广播把邀请泄露给无关节点弹窗）
                if (envelope.dstId.isNotBlank() && envelope.dstId != identity.shortId) return
                if (envelope.srcId in _sessions.value) {
                    // 已建立会话的对端再次发起请求（其确认可能丢失）：重发确认并重启重发窗口，帮助双方收敛
                    _ackRetries.update { it + (envelope.srcId to System.currentTimeMillis()) }
                    sendInviteAck(envelope.srcId)
                } else {
                    _invites.update { it + (envelope.srcId to envelope.ts) }
                }
            }
            "INVITE_ACK" -> {
                // 确认同样为一跳点对点帧，仅处理发往本机的
                if (envelope.dstId.isNotBlank() && envelope.dstId != identity.shortId) return
                val firstTime = envelope.srcId !in _sessions.value
                _sessions.update { it + envelope.srcId }
                sessionStore.save(_sessions.value)
                _invites.update { it - envelope.srcId }
                _pendingInvites.update { it - envelope.srcId }
                _ackRetries.update { it - envelope.srcId }
                // 仅首次收到确认时回发一次（ack-of-ack），让对端停止重发；
                // 之后对端重发的冗余 ACK 不再回发，防止双方无限互发确认刷屏
                if (firstTime) {
                    sendInviteAck(envelope.srcId)
                    // 会话建立 → 尝试建立 RFCOMM 高速通道（文件传输用）；失败静默回退 BLE
                    connectRfcomm(envelope.srcId)
                }
            }
            "PING" -> {
                // 心跳广播帧：仅处理发往本机/广播；回 PONG 双向确认在线，同时交换昵称
                if (envelope.dstId.isNotBlank() && envelope.dstId != identity.shortId) return
                markSeen(envelope.srcId, (envelope.body as? PresenceBody)?.displayName ?: "")
                // v1.1.16：按 PING 序列号缺口统计收包成功率/丢包率（协议层信号强度，不依赖系统 RSSI）
                (envelope.body as? PresenceBody)?.seq?.takeIf { it > 0 }?.let { recordLinkQuality(envelope.srcId, it) }
                // v1.1.0：从 PING 携带的 relays 学习 2 跳路由（每 3 次心跳搭一次便车）
                (envelope.body as? PresenceBody)?.let { learnRoutes(envelope.srcId, it) }
                // 对方在线 → 立即重发未确认消息（后台恢复场景秒级收敛，不等 3s 定时）
                resendPendingReceipts(System.currentTimeMillis(), pingTriggered = true)
                // 硬实时送达确认：回 PONG 携带本机已收到的对端消息 id——确认搭心跳便车，
                // 复用已验证通畅的双向心跳通道，彻底绕开独立回执广播（RECEIPT）在 BLE 上的丢帧
                val pong = MeshEnvelope(
                    id = UUID.randomUUID().toString(), kind = "PONG",
                    srcId = identity.shortId, dstId = envelope.srcId, convId = "conv-${envelope.srcId}",
                    ttl = DEFAULT_TTL, ts = System.currentTimeMillis(),
                    body = PresenceBody(identity.displayName, ackIds = ackIdsFor(envelope.srcId)),
                )
                val pongFrame = MeshFrame(FrameType.DATA, MeshJson.encodeEnvelope(pong).toByteArray())
                recordSentFrame(pongFrame)
                transport.broadcast(pongFrame)
            }
            "PONG" -> {
                if (envelope.dstId.isNotBlank() && envelope.dstId != identity.shortId) return
                markSeen(envelope.srcId, (envelope.body as? PresenceBody)?.displayName ?: "")
                // 硬实时送达确认：先消化对方随心跳回执的消息（标记送达并移出队列），再重发仍未确认的
                (envelope.body as? PresenceBody)?.ackIds?.forEach { id ->
                    if (pendingReceipts.remove(id) != null) {
                        store.updateMessageStatus(id, MessageStatus.DELIVERED)
                        debugStats.recordConfirmed(id)
                    }
                }
                // 对方确认本机心跳 → 立即重发仍未确认的消息（PING/PONG 双触发，确认机会翻倍）
                resendPendingReceipts(System.currentTimeMillis(), pingTriggered = true)
            }
            "FILE", "FILE2" -> {
                // 一跳帧（同握手帧）：仅处理发往本机；非本机忽略（ACK 一跳语义下多跳无法回传）。
                // FILE2（v1.1.27）多块合并传输，fileId 取 FileBodyV2.fid；老版本对端 decode FILE2 失败自动丢帧。
                if (envelope.dstId.isNotBlank() && envelope.dstId != identity.shortId) return
                val body = envelope.body
                val fileId = when (body) {
                    is FileBody -> body.fileId
                    is FileBodyV2 -> body.fid
                    else -> return
                }
                // 先落库占位（按 fileId 去重；upsert 幂等），再收块——收齐回调会置 DELIVERED，顺序不能反
                if (receivedFiles.add(fileId)) {
                    // 重启后对端重传已保存文件：不重复落盘，仅回发完成 ACK（移植队友 v1.0.12）
                    val alreadySaved = store.queryMessages("conv-${envelope.srcId}").any {
                        it.id == fileId && it.status == MessageStatus.DELIVERED
                    }
                    if (alreadySaved) {
                        transfer.acknowledgeCompletedFile(
                            fileId = fileId,
                            convId = "conv-${envelope.srcId}",
                            senderId = envelope.srcId,
                            totalChunks = (body as? FileBody)?.totalChunks
                                ?: (body as FileBodyV2).tot,
                        )
                        return
                    }
                    val (name, mime, size) = when (body) {
                        is FileBody -> Triple(body.fileName, body.mime, body.size)
                        is FileBodyV2 -> Triple(body.n, body.m, body.sz)
                        else -> return
                    }
                    store.insertMessage(
                        StoredMessage(
                            id = fileId, convId = "conv-${envelope.srcId}", kind = "FILE",
                            srcId = envelope.srcId, dstId = envelope.dstId, text = name,
                            fileMeta = fileMetaJson(name, mime, size, null),
                            status = MessageStatus.SENDING, ts = envelope.ts,
                        ),
                    )
                }
                transfer.onFileChunk(envelope)
            }
            "FILE_ACK" -> {
                if (envelope.dstId.isNotBlank() && envelope.dstId != identity.shortId) return
                transfer.onFileAck(envelope)
            }
            else -> {
                // 投递以目标寻址为准：发往本机的消息直接投递，不依赖会话白名单
                //（会话是内存态，重启即空；若按 srcId in sessions 拦截，会话丢失后消息被误丢）
                if (envelope.kind == "TEXT") {
                    // v1.1.0 纯中继：任何设备收到的非本机 TEXT 帧都转发（无需会话关系）——
                    // 路过的设备天然当路由器；TTL≤1 不再转发（防无限扩散）。转发带抖动错开多机同步广播。
                    if (envelope.dstId == identity.shortId || envelope.dstId.isBlank()) {
                        route(envelope)
                    } else if (envelope.ttl - 1 > 0) {
                        route(envelope, jitter = true)
                    }
                } else if (envelope.dstId.isBlank() || envelope.dstId == identity.shortId || envelope.srcId in _sessions.value) {
                    route(envelope)
                }
            }
        }
    }

    private fun route(envelope: MeshEnvelope, jitter: Boolean = false) {
        when (val decision = ForwardingDecision(identity.shortId, dedup).decide(envelope)) {
            ForwardDecision.Deliver -> {
                debugStats.recordRoute(RouteDecision.DELIVER)
                Log.d(TAG, "deliver kind=${envelope.kind} src=${envelope.srcId} dst=${envelope.dstId}")
                store.insertMessage(envelope.toStoredMessage())
                store.updateMessageStatus(envelope.id, MessageStatus.DELIVERED)
                sendReceipt(envelope)
                if (envelope.kind == "TEXT") {
                    // 收到消息即学对方昵称（TEXT 随信封携带 displayName）并落库：
                    // 对话列表/等待路由立刻显示名字，不依赖 PING 心跳时序
                    markSeen(envelope.srcId, (envelope.body as? TextBody)?.displayName ?: "")
                    // 记录近期收到的消息：窗口内周期性重复回执 + 心跳 PONG 携带确认 + 广播扫描响应确认，发送方必能收敛
                    recentReceived[envelope.id] = envelope to System.currentTimeMillis()
                    // 确认键变化 → 刷新广播，让对端尽快从扫描读到（无需 GATT 连接）
                    transport.refreshAdvertising()
                }
                // 收到消息回调（通知用）：仅对端发来的 TEXT 触发
                if (envelope.kind == "TEXT" && envelope.srcId != identity.shortId) {
                    val fromName = peerEntries[envelope.srcId]?.info?.displayName?.ifBlank { envelope.srcId } ?: envelope.srcId
                    onIncomingMessage(envelope.srcId, fromName, (envelope.body as? TextBody)?.text ?: "")
                }
            }
            is ForwardDecision.Forward -> {
                debugStats.recordRoute(RouteDecision.FORWARD)
                debugStats.recordRelayed()
                val forwarded = envelope.copy(ttl = decision.ttl)
                Log.d(TAG, "forward kind=${envelope.kind} src=${envelope.srcId} dst=${envelope.dstId} ttl=${decision.ttl}")
                store.enqueueOutbox(
                    OutboxEntry(
                        id = forwarded.id,
                        envelopeJson = MeshJson.encodeEnvelope(forwarded),
                        nextHop = null,
                        expireAt = System.currentTimeMillis() + OUTBOX_TTL_MS,
                    ),
                )
                // 转发抖动（v1.1.0）：错开多机同步转发，防广播风暴。
                // 本机发起的消息（route 默认 jitter=false）直接广播不等；只有"收到他人帧后转发"才抖动。
                if (jitter) {
                    val j = FORWARD_JITTER_MIN_MS +
                        Random.nextLong(FORWARD_JITTER_MAX_MS - FORWARD_JITTER_MIN_MS + 1)
                    scope.launch { delay(j); broadcastData(forwarded) }
                } else {
                    broadcastData(forwarded)
                }
            }
            ForwardDecision.Drop -> {
                debugStats.recordRoute(RouteDecision.DROP)
                // 重复 TEXT（发送方超时重发等确认）：本机已投递过，补发回执让发送方收敛，不再重复落库
                if (envelope.kind == "TEXT") sendReceipt(envelope)
            }
        }
    }

    /** 广播 DATA 帧（转发/重发共用出口）。 */
    private fun broadcastData(envelope: MeshEnvelope) {
        debugStats.recordSent(DebugStats.kindOfEnvelope(envelope.kind), MeshJson.encodeEnvelope(envelope).toByteArray().size)
        transport.broadcast(MeshFrame(FrameType.DATA, MeshJson.encodeEnvelope(envelope).toByteArray()))
    }

    private fun sendReceipt(envelope: MeshEnvelope) {
        // 本机发出的回执登记去重：广播回环时不再当转发帧处理（回执泛洪仅由中间节点转发）
        dedup.mark("receipt-${envelope.id}")
        val receipt = "{\"id\":\"${envelope.id}\",\"srcId\":\"${envelope.srcId}\",\"dstId\":\"${envelope.dstId}\"}"
        debugStats.recordSent(FrameKind.RECEIPT, receipt.toByteArray().size)
        transport.broadcast(MeshFrame(FrameType.RECEIPT, receipt.toByteArray()))
    }

    private fun handleReceipt(frame: MeshFrame) {
        val text = frame.payloadText
        val id = Regex("\"id\":\"([^\"]+)\"").find(text)?.groupValues?.get(1) ?: return
        store.updateMessageStatus(id, MessageStatus.DELIVERED)
        pendingReceipts.remove(id)
        debugStats.recordConfirmed(id)
    }

    private fun MeshEnvelope.toStoredMessage(): StoredMessage {
        val text = (body as? TextBody)?.text
        // 会话键以「发送者短 ID」为统一命名基准（conv-<srcId>）：
        // 发送方用对端 ID 命名、接收方用发送者 ID 命名会导致收发双方读写不同会话键，消息存了却查不到。
        return StoredMessage(
            id = id, convId = "conv-$srcId", kind = kind, srcId = srcId, dstId = dstId,
            text = text, ts = ts, status = MessageStatus.DELIVERED,
        )
    }
}
