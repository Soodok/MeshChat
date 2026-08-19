package com.meshchat.app.mesh.service

import android.util.Log
import com.meshchat.app.mesh.channel.ChannelFingerprint
import com.meshchat.app.mesh.crypto.E2eeKeyStore
import com.meshchat.app.mesh.crypto.InMemoryE2eeKeyStore
import com.meshchat.app.mesh.crypto.MeshCrypto
import com.meshchat.app.mesh.debug.DebugControl
import com.meshchat.app.mesh.debug.DebugStats
import com.meshchat.app.mesh.debug.FileStats
import com.meshchat.app.mesh.debug.DebugLogBuffer
import com.meshchat.app.mesh.debug.FrameKind
import com.meshchat.app.mesh.debug.PeerDebugInfo
import com.meshchat.app.mesh.debug.RouteDecision
import com.meshchat.app.mesh.identity.LocalIdentity
import com.meshchat.app.mesh.identity.ShortIdGen
import com.meshchat.app.mesh.protocol.BlockBody
import com.meshchat.app.mesh.protocol.EnvelopeBody
import com.meshchat.app.mesh.protocol.FileAckBody
import com.meshchat.app.mesh.protocol.FileBody
import com.meshchat.app.mesh.protocol.File3
import com.meshchat.app.mesh.protocol.FileBodyV2
import com.meshchat.app.mesh.protocol.FrameType
import com.meshchat.app.mesh.protocol.GroupBody
import com.meshchat.app.mesh.protocol.MeshEnvelope
import com.meshchat.app.mesh.protocol.MeshFrame
import com.meshchat.app.mesh.protocol.MeshJson
import com.meshchat.app.mesh.protocol.PresenceBody
import com.meshchat.app.mesh.protocol.SecBody
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
import com.meshchat.app.mesh.transport.DiscoveryMode
import com.meshchat.app.mesh.transport.MeshTransport
import com.meshchat.app.mesh.transport.PeerPresence
import com.meshchat.app.mesh.transport.LinkInfo
import com.meshchat.app.mesh.transport.LinkState
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val DEFAULT_TTL = 8
private const val OUTBOX_TTL_MS = 60_000L
private const val REFRESH_INTERVAL_MS = 200L      // 探测刷新周期 0.2s
/** v1.1.81 心跳（PING 广播）默认周期：1.5s（用户：500ms 占用信道太严重，主要依靠 GATT 保活；GATT 畅通即低频）。 */
private const val HEARTBEAT_INTERVAL_MS = 1_500L
/** v1.1.81 心跳最高频率：GATT 全部失联（无在线节点）时 50ms 高频尝试恢复（BLE 广播受系统 ~100ms 最小间隔限制，GATT 写通道上真实生效）。 */
private const val MIN_HEARTBEAT_INTERVAL_MS = 50L
private const val LOST_HEARTBEAT_MS = 2_000L      // 超过该时长无任何 PING/PONG/扫描帧 → 判失联（固定 2s 不随心跳联动——用户决策；500ms 心跳下容忍 4 帧丢失）
/** v1.1.78 直连→中继降级阈值：一跳直连失联 ≥3s 才降级为"经中继可达"；v1.1.80 提到 5s（用户：中继场景 3s 频繁误报"正在尝试连接"，可容忍 5s）。 */
private const val DIRECT_RELAY_FALLBACK_MS = 5_000L
/** v1.1.80 中继链路段新鲜度阈值：中继方上报该节点心跳年龄 >5s → 该段疑似断 → 显示"重连中"（不等 30s 路由过期）。 */
private const val RELAY_LINK_FRESH_MS = 5_000L
/** v1.1.80 直连边重连窗口：确认过直连但超 RELAY_LINK_FRESH_MS 未刷新 → 黄（重连中）；再超本窗口 → 移除（无连接）。 */
private const val LINK_RECONNECT_WINDOW_MS = 20_000L
private const val OFFLINE_THRESHOLD_MS = 15_000L  // 无心跳超过该时长 → 离线（保留显示置黑，更快反映失联）
private const val SEARCHING_TIMEOUT_MS = 6_000L   // 持久化恢复后 6 秒仍未找到 → 自动失联（避免无限寻找）
private const val RECEIPT_TIMEOUT_MS = 3_000L     // 消息发出后未收到送达回执的等待时间，超时重发（更快确认）
private const val MAX_RESEND_INTERVAL_MS = 30_000L // 重发退避封顶：3s→6s→12s→24s→30s，永不 FAILED（零容错，持续确认）
private const val RECEIPT_REPEAT_INTERVAL_MS = 3_000L    // 接收方重复回执周期（近期消息周期性补发）
private const val RECEIPT_REPEAT_WINDOW_MS = 180_000L    // 重复回执窗口：收到消息后 3min 内周期性补发（覆盖长时间后台空窗）

// ===== v1.1.50 群消息 MVP =====
private const val GROUP_RESEND_INTERVAL_MS = 5_000L      // 群消息重发间隔：固定 5s（新 id 重发 = 新泛洪，仿真铁证）
private const val GROUP_MAX_RESENDS = 3                  // 群消息重发上限：≤3 次（不依赖确认——确认来自近端 <300ms，依赖它会杀掉重发）
private const val GROUP_CONFIRM_TIMEOUT_MS = 30_000L     // 群消息总超时：30s 无任一成员确认 → "可能未送达"（诚实标注）
private const val GROUP_RECEIPT_CHANCE = 0.3             // 群回执节流概率：30%（仿真：带宽 +50-100% 换发送方真实感知）
private const val GROUP_RECEIPT_DELAY_MAX_MS = 500L      // 群回执随机延迟上界：0-500ms（错峰防风暴）
private const val GROUP_DUP_WINDOW_MS = 600_000L       // 内容指纹键存活期：10 分钟（键唯一=一个 msgId 一条；覆盖重启恢复重发的长间隔）
private const val GROUP_DUP_MAX_PER_KEY = 3             // 同指纹窗口内时间戳队列上限（防异常增长）
private const val GROUP_RECEIPT_ID_PREFIX = "G$"         // 群回执 id 前缀：与点对点（envelope.id 键）命名空间隔离

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

/**
 * v1.1.84 Wi-Fi Direct 高速通道最小契约（MeshService 只依赖帧合流/连接查询/点对点写，可测替身）。
 * 与 RfcommChannel 同构：incoming 合流 handleFrame，foundPeers 合流节点表，sendTo 单发（TCP 可靠）。
 */
interface WfdChannel {
    val incoming: SharedFlow<MeshFrame>
    val foundPeers: SharedFlow<MeshPeerInfo>
    fun isConnectedTo(peerId: String): Boolean
    fun sendTo(peerId: String, frame: MeshFrame)
    /** v1.1.87 已建 TCP 的组内成员（心跳/消息双链路广播用）。 */
    fun members(): Set<String>
}

/** 群列表条目（v1.1.50）：id + 显示名（缺省"群-<id>"，随消息/创建帧学习群名后更新）+ 本机见过的发言成员数（v1.1.54）。 */
data class GroupInfo(val id: String, val name: String, val memberCount: Int = 0)

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
    /** v1.1.84 Wi-Fi Direct 高速通道（可选，最高优先）：P2P TCP 已连节点单播帧优先走它。 */
    private val wfd: WfdChannel? = null,
    /** 会话关系持久化（默认内存 Noop，不持久化；生产注入 SharedPrefsSessionStore）。 */
    private val sessionStore: SessionStore = object : SessionStore {
        override fun load(): Set<String> = emptySet()
        override fun save(sessions: Set<String>) {}
    },
    /** 群组订阅/群名持久化（v1.1.50；默认 Noop，生产注入 SharedPrefsGroupStore）。 */
    private val groupStore: GroupStore = NoopGroupStore,
    /** 群回执节流参数（v1.1.50；测试注入确定性，生产默认 30% + 0-500ms）。 */
    private val groupReceiptChance: Double = GROUP_RECEIPT_CHANCE,
    private val groupReceiptDelayMaxMs: Long = GROUP_RECEIPT_DELAY_MAX_MS,
    /** 收到新消息回调（fromId/fromName/text/convId）：MeshChatService 注入用于弹通知。convId = 群会话键或 conv-<fromId>。 */
    private val onIncomingMessage: (fromId: String, fromName: String, text: String, convId: String) -> Unit = { _, _, _, _ -> },
    /** 文件接收完成回调（fileName）：通知「文件已保存」。 */
    private val onFileSaved: (fileName: String) -> Unit = {},
    /** 调试统计内核（默认独立实例，生产由 Application 注入共享单例）。 */
    private val debugStats: DebugStats = DebugStats(),
    /** v1.1.57 E2EE 密钥存储（默认内存实现；生产注入 AndroidKeyStore + SharedPrefs 实现）。 */
    private val e2eeStore: E2eeKeyStore = InMemoryE2eeKeyStore(),
    /** v1.1.64 拉黑持久化（删除对话 = 拉黑；默认 Noop，生产注入 SharedPrefsBlockedStore）。 */
    private val blockedStore: BlockedStore = NoopBlockedStore,
    /** v1.1.74 对端公钥指纹持久化（密钥连续性 TOFU，MITM 防御；默认 Noop，生产注入 SharedPrefsPeerKeyStore）。 */
    private val peerKeyStore: PeerKeyStore = NoopPeerKeyStore,
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
        // v1.1.84 链路存活判据扩展到任一高速通道：wfd/rfcomm 已连时文件不再因 BLE 断开被误判中止
        isConnectedTo = { peerId ->
            transport.isConnectedTo(peerId) ||
                (rfcomm?.isConnectedTo(peerId) == true) ||
                (wfd?.isConnectedTo(peerId) == true)
        },
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
    private data class RouteEntry(
        val via: String, val hops: Int, val lastSeenAt: Long,
        /** v1.1.80 中继链路段新鲜度（ms）：中继方上报的该节点心跳年龄（0 = 未知/老版本）。 */
        val relayAgeMs: Long = 0,
        /** v1.1.80 中继链路段是否新鲜：中继方最近一次携带 relays 时含该节点。false = B-C 段疑似断（立即降级显示，不等路由过期）。 */
        val relayFresh: Boolean = true,
    )
    private val routeEntries = ConcurrentHashMap<String, RouteEntry>()
    /** PING 计数器：每 PING_RELAYS_EVERY 次心跳携带一次 relays 路由信息。 */
    private var pingCount = 0
    /** PING 序列号：每次广播递增（v1.1.16 协议层信号强度统计用）。 */
    private var pingSeq = 0
    /** 中继转发 outbox 重发状态：id -> 上次重发时刻 / 重试次数（内存态）。 */
    private val outboxLastSent = HashMap<String, Long>()
    private val outboxAttempts = HashMap<String, Int>()

    // ===== v1.1.80 中继链路健康 + 直连边学习 =====
    /** 学习到的节点对直连边：key = "短ID小|短ID大"，value = 最近一次确认时刻（对端 PING relays = 对端一跳邻居 → 对端与这些节点直连）。 */
    private val directLinks = ConcurrentHashMap<String, Long>()
    /** 直连边状态流（拓扑图 peer-peer 边着色：绿=直连，黄=重连中；无边 = 未知或无直连）。 */
    private val _links = MutableStateFlow<List<LinkInfo>>(emptyList())
    val links: StateFlow<List<LinkInfo>> = _links.asStateFlow()
    /** 直连往返延迟（rttMs）：最近一次 PING/PONG 往返，供 UI 显示"延迟"。 */
    private val peerRtt = ConcurrentHashMap<String, Long>()

    private fun linkKey(a: String, b: String) = if (a < b) "$a|$b" else "$b|$a"

    /** 刷新直连边状态流：DIRECT（≤ RELAY_LINK_FRESH_MS 内确认）→ RECONNECTING（超时未确认，仍在窗口内）。now 可注入（tick 用注入时间驱动状态转换）。 */
    private fun refreshLinks(now: Long = System.currentTimeMillis()) {
        _links.value = directLinks.entries.map { (k, t) ->
            val sep = k.indexOf('|')
            LinkInfo(
                k.substring(0, sep), k.substring(sep + 1),
                if (now - t <= RELAY_LINK_FRESH_MS) LinkState.DIRECT else LinkState.RECONNECTING,
                t,
            )
        }
    }

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

    // ===== v1.1.66 频道系统（单频道制：公共 / 私人）=====
    /** 当前频道名（null = 公共频道）。 */
    private val _channelName = MutableStateFlow<String?>(null)
    val channelName: StateFlow<String?> = _channelName.asStateFlow()
    /** 当前频道指纹（0 = 公共频道）。 */
    @Volatile private var channelFingerprint: Long = 0L

    // ===== v1.1.74 MITM 防御（密钥连续性 TOFU）=====
    /** 对端公钥指纹与首次握手记录不一致（身份变更，可能被中间人劫持）的节点集合。 */
    private val _peerKeyChanged = MutableStateFlow<Set<String>>(emptySet())
    val peerKeyChanged: StateFlow<Set<String>> = _peerKeyChanged.asStateFlow()

    /** 对端公钥指纹（首次握手记录）；null = 尚未与对方完成握手/未记录。 */
    fun peerFingerprint(peerId: String): String? = peerKeyStore.fingerprint(peerId)

    /**
     * 节点条目。v1.1.55 起区分两个"活着"信号源：
     * - lastSeen：最近任何帧（协议+扫描）——广播可见性。
     * - appSeenAt：最近**协议帧**（markSeen 刷新，PING/PONG/TEXT/GROUP）——应用层活跃性。
     * - scanSeenAt：最近**扫描帧**（advertise，peerJob 刷新）——蓝牙栈广播活跃性。
     * 协议帧失联（appSeenAt 过期）但广播新鲜（scanSeenAt 新鲜）→ UNRESPONSIVE（对方应用层无响应）。
     */
    private data class PeerEntry(var info: MeshPeerInfo, var lastSeen: Long, var lost: Boolean, var appSeenAt: Long = 0L, var scanSeenAt: Long = 0L)

    /** 上次 PING 广播时刻（tick 200ms 节流到 1s）。 */
    private var lastPingAt = 0L

    // ---- 调试主动控制（volatile 可调；默认值=常量，未调节时行为零变化；内存态重启回默认）----
    @Volatile private var heartbeatIntervalMs: Long = HEARTBEAT_INTERVAL_MS
    /** v1.1.81 手动心跳档（调试中心 setHeartbeat）：非 null 时固定该档，动态心跳不覆盖。 */
    @Volatile private var manualHeartbeatMs: Long? = null
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

    // ===== v1.1.50 群消息 MVP =====
    /** 已订阅群 ID 集合（本地订阅 = 加入群，持久化）。 */
    private val _joinedGroups = MutableStateFlow<Set<String>>(emptySet())
    val joinedGroups: StateFlow<Set<String>> = _joinedGroups.asStateFlow()
    /** 群名：groupId -> name（随消息/创建帧学习，持久化）。用 StateFlow 承载——groups 合成流才能响应群名更新。 */
    private val _groupNames = MutableStateFlow<Map<String, String>>(emptyMap())
    private val groupNames: Map<String, String> get() = _groupNames.value
    /**
     * 群成员数（v1.1.54）：groupId -> 本机见过的去重发言者数（收到该群消息的 srcId 集合，内存态）。
     * 广播域模型无成员表，此为近似统计——仅统计本机在线期间发过消息的成员。
     */
    private val _groupMembers = MutableStateFlow<Map<String, Int>>(emptyMap())
    /** 群成员去重集合（v1.1.54）：groupId -> 已统计过的发送者短 ID（防重复计数）。 */
    private val groupMemberIds = ConcurrentHashMap<String, MutableSet<String>>()

    // ===== v1.1.64 拉黑（删除对话 = 拒绝连接与消息）=====
    /** 已拉黑短 ID（持久化）：其 INVITE/TEXT/PING 等所有帧被忽略——对方无法连接、无法发消息。 */
    private val _blockedPeers = MutableStateFlow(blockedStore.load())
    val blockedPeers: StateFlow<Set<String>> = _blockedPeers.asStateFlow()

    /** 拉黑：拒绝该节点的连接与消息（删除对话时调用）。v1.1.67 拉黑即断开已建立连接，对方立即失联→离线。
     *  v1.1.79：先广播 BLOCK 通知（连接还在时送达概率最高），对方收到后解除会话+清指纹+断连变回陌生节点；
     *  本机同步清除对端密钥指纹（下次解除拉黑重连时重新 TOFU 确立新密钥）。 */
    fun blockPeer(peerId: String) {
        sendBlockNotice(peerId)   // v1.1.79 通知对方"你已被拉黑"
        _sessions.update { it - peerId }
        sessionStore.save(_sessions.value)
        _pendingInvites.update { it - peerId }
        _invites.update { it - peerId }
        _ackRetries.update { it - peerId }
        _blockedPeers.update { it + peerId }
        blockedStore.save(_blockedPeers.value)
        peerKeyStore.remove(peerId)   // v1.1.79 清除指纹，重新握手重新 TOFU
        removePeer(peerId)
        // v1.1.67 隔离彻底化：断开已建立的 GATT 连接（对方收不到本机心跳）+ 发现层过滤 + server 拒绝重连
        transport.disconnectPeer(peerId)
        transport.setBlockedPeers(_blockedPeers.value)
        Log.i(TAG, "blocked peer $peerId")
    }

    /** v1.1.79 广播拉黑通知帧（明文控制帧，同 INVITE 语义；对方收到后解除会话+清指纹+断连）。 */
    private fun sendBlockNotice(peerId: String) {
        val env = MeshEnvelope(
            id = "blk-${identity.shortId}-${System.currentTimeMillis()}",
            kind = "BLOCK", srcId = identity.shortId, dstId = peerId,
            convId = "", ttl = 3, ts = System.currentTimeMillis(),
            body = BlockBody(reason = "blocked"),
        )
        val frame = MeshFrame(FrameType.DATA, MeshJson.encodeEnvelope(env).toByteArray())
        recordSentFrame(frame)
        transport.broadcast(frame)
        DebugLogBuffer.log("MeshSvc", "已广播拉黑通知给 $peerId")
    }

    /** 解除拉黑：恢复可连接/收发。v1.1.67 同步解除发现层过滤。 */
    fun unblockPeer(peerId: String) {
        _blockedPeers.update { it - peerId }
        blockedStore.save(_blockedPeers.value)
        transport.setBlockedPeers(_blockedPeers.value)   // v1.1.67 解除过滤，重新可发现/可连接
        Log.i(TAG, "unblocked peer $peerId")
    }

    // ===== v1.1.66 频道系统 =====
    /**
     * 切换频道：null = 公共频道；非空 = 私人频道（指纹 = SHA-256 截断，广播只携带指纹不泄露频道名）。
     * 切换后清空节点表与 2 跳路由（旧频道残留剔除），重新发现只按新频道过滤；会话/消息记录保留。
     */
    fun setChannel(name: String?) {
        val trimmed = name?.trim()?.takeIf { it.isNotEmpty() }
        val fp = if (trimmed == null) 0L else ChannelFingerprint.of(trimmed)
        _channelName.value = trimmed
        channelFingerprint = fp
        transport.setChannel(fp)
        // v1.1.67 换频道断开所有旧连接：旧频道/公共频道节点不再收到本机心跳（隔离彻底化）
        transport.disconnectAll()
        transport.refreshAdvertising()   // v1.1.63 模式守卫：仅 NORMAL 重启广播；CLOSED/SILENT 广播本就停，扫描过滤读 volatile 即时生效
        peerEntries.clear()
        routeEntries.clear()             // 2 跳中继路由同样按频道隔离，旧频道路由失效
        refreshPeers()
    }

    /** v1.1.66 对端是否在当前频道（发送拒绝原因区分）：公共频道恒 true；私人频道要求节点已发现且指纹匹配。 */
    fun isPeerInCurrentChannel(peerId: String): Boolean {
        if (channelFingerprint == 0L) return true
        return peerEntries[peerId]?.info?.channelFingerprint == channelFingerprint
    }

    // ===== v1.1.57 端到端加密（E2EE）=====
    /**
     * 本机 ECDH P-256 密钥对（AndroidKeyStore 私钥不可导出；测试内存实现）。
     * v1.1.63 防崩：AndroidKeyStore 生成失败（华为等 ROM 对 EC + PURPOSE_AGREE 支持不完整 → KeyGenParameterSpec
     * 或 generateKeyPair 抛异常，用户实测"点击发起连接崩溃"）→ 降级内存密钥对（MeshCrypto.generateKeyPair），
     * 握手仍可用（重启后密钥变化需重新握手）；异常写诊断日志（调试中心可导出定位）。
     */
    private val localKeyPair: java.security.KeyPair by lazy {
        runCatching { e2eeStore.localKeyPair() }.getOrElse { t ->
            Log.w(TAG, "AndroidKeyStore key pair failed, fallback to in-memory key", t)
            DebugLogBuffer.log("E2EE", "AndroidKeyStore 密钥生成失败，降级内存密钥（${t.message ?: t.javaClass.simpleName}）")
            keyFallback = true
            MeshCrypto.generateKeyPair()
        }
    }
    /** v1.1.74 本机密钥是否降级内存密钥（不持久）：重启后更换 → 对端会收到身份变化提示。 */
    @Volatile private var keyFallback = false
    val localKeyFallback: Boolean get() = keyFallback
    /** 本机公钥 SPKI Base64（握手交换）。 */
    private val localPubKeyB64: String by lazy { MeshCrypto.publicKeyB64(localKeyPair) }
    /** 对端会话密钥缓存（peerId → 32B）；启动时从 e2eeStore 惰性加载。 */
    private val sessionKeys = ConcurrentHashMap<String, ByteArray>()
    /** 群密钥缓存（groupId → 32B）；启动时从 e2eeStore 惰性加载。 */
    private val groupKeys = ConcurrentHashMap<String, ByteArray>()
    /** 群列表（合成流）：joinedGroups × groupNames × groupMembers。 */
    val groups: StateFlow<List<GroupInfo>> = combine(_joinedGroups, _groupNames, _groupMembers) { ids, names, members ->
        ids.sorted().map { GroupInfo(it, names[it] ?: "群-$it", members[it] ?: 0) }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** 待确认群消息：逻辑 msgId -> 状态。重发用**新 envelope id**（同 id 被节点级去重挡住，仿真铁证无效）。 */
    private class PendingGroupMsg(
        val groupId: String,
        val text: String,
        val msgId: String,
        val groupName: String?,
        val firstSentAt: Long,
        var lastSentAt: Long,
        var retries: Int = 0,
    )
    /** 群回执队列：键 "G$msgId"（"G$" 前缀与点对点 pendingReceipts 的 envelope.id 键命名空间隔离）。 */
    private val pendingGroupReceipts = LinkedHashMap<String, PendingGroupMsg>()
    /** 群消息内容指纹去重表（新 id 重发副作用）：fingerprint -> 最近时间戳队列（10s 窗口，≤3 条）。 */
    private val groupMsgFingerprints = ConcurrentHashMap<String, ArrayDeque<Long>>()

    fun start() {
        if (started) return // 幂等：防止「开始附近发现」被重复点击导致重复启动
        started = true
        prunePersistentCaches(System.currentTimeMillis(), force = true)
        _sessions.value = sessionStore.load()   // 重启恢复已建立的会话关系
        restoreKnownPeers()                     // 重启恢复已知节点（寻找中状态，心跳/扫描补在线）
        restorePendingReceipts()                // 重启恢复未确认消息（进程被杀后重发不丢失）
        _joinedGroups.value = groupStore.loadJoined()   // v1.1.50：重启恢复已订阅群
        _groupNames.value = groupStore.loadNames()      // v1.1.50：重启恢复已学群名（先于群队列恢复，重发帧带正确群名）
        restorePendingGroupReceipts()           // v1.1.50：重启恢复未确认群消息（SENDING 群消息重建重发队列）
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
        // v1.1.84 Wi-Fi Direct 通道合流：P2P TCP 到达的帧（消息/心跳/文件块/ACK）同样走 handleFrame
        wfd?.incoming?.let { flow ->
            scope.launch {
                flow.collect { frame ->
                    runCatching { handleFrame(frame) }
                        .onFailure { Log.w(TAG, "WFD frame handling failed", it) }
                }
            }
        }
        peerJob = scope.launch {
            transport.foundPeers.collect { info -> onPeerFound(info) }
            // v1.1.84 Wi-Fi Direct 发现的节点合流进同一节点表（保留已学 hops/昵称，不覆盖 BLE 信息）
            wfd?.foundPeers?.collect { info -> onPeerFound(info) }
        }
        tickJob = scope.launch {
            while (isActive) {
                delay(REFRESH_INTERVAL_MS)
                val now = System.currentTimeMillis()
                // 单轮异常只跳过本轮（审查 S2）：pendingGroupReceipts 等 LinkedHashMap 与接收协程并发
                // 修改可能抛 CME，无隔离会杀死整个 tick（心跳/重发/缓存维护全停）
                runCatching {
                    heartbeatTick(now)
                    resendPendingReceipts(now)
                    // v1.1.50：待确认群消息重发（固定 5s 新 id 重发 ≤3 次 + 30s 超时"可能未送达" + 指纹表清理）
                    resendPendingGroupReceipts(now)
                    // 缓存维护：启动 force + 每 6h 节流（清过期 outbox/30 天未见节点）
                    prunePersistentCaches(now)
                    // 中继转发 outbox 重发（每 1s 节流，≤3 次）：转发丢帧兜底，尽力而为
                    resendOutbox(now)
                    // 会话状态机每 0.2s 检测一次：向已接受邀请的对端持续重发确认，直至其确认或超时
                    tickSessionState(now)
                    // 文件传输接收超时清理（60s 无进展丢弃）
                    transfer.tick(now)
                }.onFailure { Log.w(TAG, "tick iteration failed", it) }
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

    /**
     * 发送点对点消息。v1.1.57 强制加密：非自环目标必须有会话密钥（握手已交换公钥派生），
     * 无密钥（对方旧版本/未协商）→ 拒绝发送返回 false，绝不发明文。自环（dstId=本机）保持明文。
     */
    fun sendText(convId: String, dstId: String, text: String): Boolean {
        val isSelfLoop = dstId == identity.shortId
        // v1.1.66 频道校验：私人频道下目标必须已发现且同频道；公共频道不校验（保持存量 outbox 排队行为）
        if (!isSelfLoop && channelFingerprint != 0L) {
            val peer = peerEntries[dstId]?.info
            if (peer == null || peer.channelFingerprint != channelFingerprint) {
                Log.w(TAG, "channel: dst $dstId not in current channel, refusing send")
                return false
            }
        }
        val key = if (isSelfLoop) null else sessionKeyFor(dstId)
        if (!isSelfLoop && key == null) {
            Log.w(TAG, "e2ee: no session key for $dstId, refusing plaintext send")
            return false
        }
        val body: EnvelopeBody
        val enc: String
        if (isSelfLoop) {
            body = TextBody(text, displayName = identity.displayName)
            enc = "none"
        } else {
            body = encryptBody(TextBody(text, displayName = identity.displayName), key!!, "p2p", "TEXT|$dstId")
            enc = "aes-gcm-v1"
        }
        val envelope = MeshEnvelope(
            id = UUID.randomUUID().toString(),
            kind = "TEXT",
            srcId = identity.shortId,
            dstId = dstId,
            convId = convId,
            ttl = DEFAULT_TTL,
            ts = System.currentTimeMillis(),
            enc = enc,
            body = body,
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
        // v1.1.87 消息双链路：WFD 已连时并行 TCP 单发（BLE 泛洪照旧）——任一链路到达即送达
        dualSendToWfd(envelope)
        return true
    }

    // ===== v1.1.50 群消息 =====
    /** 发送群消息：msgId = 逻辑消息 ID（= 首帧 envelope.id）；重发新 envelope 时不变，回执按 "G$msgId" 匹配。 */
    fun sendGroupMessage(groupId: String, text: String) {
        sendGroupMessageWithId(groupId, text, UUID.randomUUID().toString())
    }

    /**
     * 发送群消息（带显式 msgId，测试/恢复用）：本地落库 SENDING（id=msgId）、登记群回执队列、
     * 首帧广播走 route 泛洪。重发由 resendPendingGroupReceipts 驱动——**必须新 envelope id**
     * （同 id 重发被节点级 DedupCache 挡住完全无效，仿真 §3.9.1 铁证），msgId 保持不变。
     */
    fun sendGroupMessageWithId(groupId: String, text: String, msgId: String) {
        val groupKey = groupKeyFor(groupId)
        if (groupKey == null) {
            // v1.1.57 群聊对称加密：无群密钥（未创建/未收到群密钥）→ 拒绝发送（防明文广播）
            Log.w(TAG, "e2ee: no group key for $groupId, refusing plaintext group send")
            return
        }
        val now = System.currentTimeMillis()
        val groupName = groupNames[groupId]
        val inner = GroupBody(
            op = "MSG", groupId = groupId, msgId = msgId,
            groupName = groupName, text = text, displayName = identity.displayName,
        )
        val body = encryptBody(inner, groupKey, "group-$groupId", "GROUP|group-$groupId")
        val envelope = MeshEnvelope(
            id = msgId, kind = "GROUP",
            srcId = identity.shortId, dstId = groupId, convId = "group-$groupId",
            ttl = DEFAULT_TTL, ts = now, enc = "aes-gcm-v1", body = body,
        )
        store.insertMessage(
            StoredMessage(
                id = msgId, convId = "group-$groupId", kind = "GROUP",
                srcId = identity.shortId, dstId = groupId, text = text, ts = now,
                status = MessageStatus.SENDING,
            ),
        )
        pendingGroupReceipts["$GROUP_RECEIPT_ID_PREFIX$msgId"] =
            PendingGroupMsg(groupId, text, msgId, groupName, firstSentAt = now, lastSentAt = now)
        route(envelope)   // 本机发起广播（不抖动），邻居泛洪转发
    }

    /** 创建群：生成 8 字符群 ID + 群密钥（32B，群聊对称加密）+ 本地订阅 + 广播 JOIN（带群名+群密钥）传播。 */
    fun createGroup(groupName: String): String {
        val groupId = ShortIdGen.generate(8)
        val groupKey = MeshCrypto.randomGroupKey()
        saveGroupKey(groupId, groupKey)
        joinGroup(groupId, groupName)
        // 群名/群密钥随 JOIN 帧传播：成员加入即获得群密钥，后续群消息加密互通
        val env = MeshEnvelope(
            id = UUID.randomUUID().toString(), kind = "GROUP",
            srcId = identity.shortId, dstId = groupId, convId = "group-$groupId",
            ttl = DEFAULT_TTL, ts = System.currentTimeMillis(),
            body = GroupBody(
                op = "JOIN", groupId = groupId, groupName = groupName,
                groupKey = java.util.Base64.getEncoder().encodeToString(groupKey),
            ),
        )
        route(env)
        return groupId
    }

    /** 加入群 = 本地订阅 groupId（持久化）；群名非空时一并学习。 */
    fun joinGroup(groupId: String, groupName: String? = null) {
        if (groupId in _joinedGroups.value) return
        _joinedGroups.update { it + groupId }
        groupStore.saveJoined(_joinedGroups.value)
        if (!groupName.isNullOrBlank()) learnGroupName(groupId, groupName)
    }

    /** 群名学习（后到覆盖，持久化；groups 合成流随 _groupNames 更新自动刷新）。 */
    private fun learnGroupName(groupId: String, groupName: String?) {
        if (groupName.isNullOrBlank()) return
        if (_groupNames.value[groupId] == groupName) return
        _groupNames.value = _groupNames.value + (groupId to groupName)
        groupStore.saveNames(_groupNames.value)
    }

    // ===== v1.1.57 端到端加密（E2EE）辅助 =====
    /** HKDF info：绑定收发双方短 ID（排序保证两端派生一致且与其他对端隔离）。 */
    private fun keyInfo(peerId: String): String =
        "meshchat-e2ee-v1|" + listOf(identity.shortId, peerId).sorted().joinToString("|")

    private fun sessionKeyFor(peerId: String): ByteArray? =
        sessionKeys[peerId] ?: e2eeStore.sessionKey(peerId)?.also { sessionKeys[peerId] = it }

    private fun groupKeyFor(groupId: String): ByteArray? =
        groupKeys[groupId] ?: e2eeStore.groupKey(groupId)?.also { groupKeys[groupId] = it }

    private fun saveGroupKey(groupId: String, key: ByteArray) {
        groupKeys[groupId] = key
        e2eeStore.saveGroupKey(groupId, key)
    }

    /** 收到对端公钥 → ECDH 共享秘密 → HKDF 派生会话密钥（双方各自派生相同密钥）。 */
    private fun deriveSessionKey(peerId: String, peerPubB64: String) {
        if (peerPubB64.isBlank()) return
        val peerPub = runCatching { MeshCrypto.publicKeyFromB64(peerPubB64) }.getOrNull()
            ?: run { Log.w(TAG, "e2ee: bad peer pubkey from $peerId"); return }
        // v1.1.78 密钥连续性语义（用户修订）：公钥变化（重启/重装/降级路径/拉黑后重建）→ **视为重新首次握手**
        // 直接接受新密钥并覆盖 TOFU 记录，不再判 MITM 红色告警（用户：密钥不同 = 默认曾经未发现过，重新建立连接确立新密钥）。
        // 对话记录按短 ID 保留（convId 不随密钥变化）。首次 = 信任并记录。
        val fp = MeshCrypto.fingerprint(peerPubB64)
        val prev = peerKeyStore.fingerprint(peerId)
        if (prev == null) {
            peerKeyStore.saveFingerprint(peerId, fp)
            _peerKeyChanged.update { it - peerId }
        } else if (prev != fp) {
            Log.i(TAG, "e2ee: key renewed for $peerId (prev=$prev now=$fp) — accept as re-handshake")
            DebugLogBuffer.log("E2EE", "对端 $peerId 公钥指纹变化（$prev → $fp），视为重新握手确立新密钥")
            peerKeyStore.saveFingerprint(peerId, fp)   // 覆盖 TOFU 记录，重新确立
            _peerKeyChanged.update { it - peerId }
        }
        val secret = MeshCrypto.sharedSecret(localKeyPair.private, peerPub)
        val key = MeshCrypto.deriveSessionKey(secret, keyInfo(peerId))
        sessionKeys[peerId] = key
        e2eeStore.saveSessionKey(peerId, key)
    }

    /** 加密内层 body（多态 JSON）为 SecBody。 */
    private fun encryptBody(body: EnvelopeBody, key: ByteArray, ctx: String, aad: String): SecBody {
        val inner = MeshJson.json.encodeToString(EnvelopeBody.serializer(), body)
        val e = MeshCrypto.encrypt(key, inner.encodeToByteArray(), aad)
        return SecBody(e.cipher, e.iv, ctx)
    }

    /** 解密 SecBody → 还原内层 body；失败（无密钥/认证失败/格式错）返回 null（丢弃，防篡改消息）。 */
    private fun decryptSecBody(envelope: MeshEnvelope): EnvelopeBody? {
        val sec = envelope.body as? SecBody ?: return null
        val key = when {
            sec.ctx == "p2p" -> sessionKeyFor(envelope.srcId)
            sec.ctx.startsWith("group-") -> groupKeyFor(sec.ctx.removePrefix("group-"))
            else -> null
        }
        if (key == null) { Log.w(TAG, "e2ee: no key ctx=${sec.ctx} src=${envelope.srcId}"); return null }
        // AAD 与发送方一致：点对点用 dstId（中继转发不改 dstId，接收端 = 发送端视角）
        val aad = if (sec.ctx == "p2p") "TEXT|${envelope.dstId}" else "GROUP|${sec.ctx}"
        val plain = MeshCrypto.decrypt(key, sec.iv, sec.cipher, aad)
            ?: run { Log.w(TAG, "e2ee: auth failed src=${envelope.srcId}"); return null }
        return runCatching {
            MeshJson.json.decodeFromString(EnvelopeBody.serializer(), plain.decodeToString())
        }.getOrNull()
    }

    /** 测试辅助：直接注入对端会话密钥（模拟握手已交换公钥）。 */
    internal fun seedSessionKeyForTesting(
        peerId: String,
        key: ByteArray = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) },
    ) {
        sessionKeys[peerId] = key
        e2eeStore.saveSessionKey(peerId, key)
    }

    /** 测试辅助：本机 ECDH 公钥（SPKI Base64）——构造握手帧验证密钥派生。 */
    internal val publicKeyB64ForTest: String get() = localPubKeyB64

    /** 测试辅助：直接注入群密钥。 */
    internal fun seedGroupKeyForTesting(groupId: String, key: ByteArray = MeshCrypto.randomGroupKey()) {
        saveGroupKey(groupId, key)
    }

    /**
     * 群消息内容指纹去重（v1.1.50，msgId 锚修订）：fingerprint = (groupId|srcId|msgId)。
     *
     * 锚选 **msgId**（逻辑消息 ID）而非文本：重发帧 msgId 不变 = 同一逻辑消息 → 判重复不落库；
     * 新消息 msgId 不同 = 合法新消息 → 不误杀（text 作锚会吞掉同群同发送者 10s 内连发的相同文本，
     * 如应急场景连发"收到"——审查 M2 发现）。
     *
     * 时间基准用**本机时间**：键唯一（一个 msgId 一条），窗口只是"键存活期"（10 分钟，覆盖
     * 重启恢复重发的长间隔），不再用 envelope.ts 差值——原 text 锚方案下清理/判定时钟基准混用，
     * 跨设备时钟偏差 >10s 时误删指纹导致重发帧重复投递（审查 M3 发现）。
     *
     * **仅已订阅节点落库/回执前调用，中继节点绝不用此表拦帧**——转发只看 DedupCache（envelope.id 防环），
     * 两层去重各司其职。
     */
    internal fun isGroupDup(envelope: MeshEnvelope, body: GroupBody): Boolean {
        val msgId = body.msgId
        if (msgId.isBlank()) return false
        val fingerprint = "${body.groupId}|${envelope.srcId}|$msgId"
        val now = System.currentTimeMillis()
        val q = groupMsgFingerprints.computeIfAbsent(fingerprint) { ArrayDeque() }
        synchronized(q) {
            while (q.isNotEmpty() && now - q.firstOrNull()!! > GROUP_DUP_WINDOW_MS) q.removeFirst()
            val isDup = q.isNotEmpty()
            q.addLast(now)
            if (q.size > GROUP_DUP_MAX_PER_KEY) q.removeFirst()
            return isDup
        }
    }

    /**
     * 群回执（节流，v1.1.50）：成员收到群消息后 30% 概率 + 0-500ms 随机延迟 → RECEIPT 泛洪回传。
     * 回执 id = "G$msgId"（与点对点命名空间隔离）；发送方收任一有效回执 → DELIVERED（"已送达"=至少一个成员）。
     * 重发的新 id 帧不回执：同 msgId 的回执去重键（receipt-G$msgId）在调度时即标记，后续同逻辑消息帧直接跳过。
     */
    private fun maybeSendGroupReceipt(envelope: MeshEnvelope, body: GroupBody) {
        val msgId = body.msgId
        if (msgId.isBlank()) return
        if (Random.nextDouble() >= groupReceiptChance) return   // 30% 节流（仿真：带宽 +50-100% 换真实感知）
        val receiptId = "$GROUP_RECEIPT_ID_PREFIX$msgId"
        // 本成员已为这个逻辑消息发过回执 → 不再发（新 id 重发帧走这里，同 msgId 只回执一次）
        if (dedup.contains("receipt-$receiptId")) return
        dedup.mark("receipt-$receiptId")
        val delayMs = if (groupReceiptDelayMaxMs <= 0) 0L else Random.nextLong(0L, groupReceiptDelayMaxMs + 1L)
        scope.launch {
            delay(delayMs)
            val receipt = "{\"id\":\"$receiptId\",\"srcId\":\"${identity.shortId}\",\"dstId\":\"${envelope.srcId}\"}"
            debugStats.recordSent(FrameKind.RECEIPT, receipt.toByteArray().size)
            transport.broadcast(MeshFrame(FrameType.RECEIPT, receipt.toByteArray()))
        }
    }

    /**
     * 待确认群消息重发（tick 每 200ms 调用）：固定 5s 重发一次、**新 envelope id**（新泛洪）、≤3 次、
     * 不依赖确认（确认来自近端 <300ms，依赖它会杀掉重发）；30s 总超时 → "可能未送达"（FAILED 渲染琥珀）
     * 并移出队列。顺带清理内容指纹表的空键（窗口滑过后删除，防长期运行内存增长）。
     */
    internal fun resendPendingGroupReceipts(now: Long) {
        val fit = groupMsgFingerprints.entries.iterator()
        while (fit.hasNext()) {
            val (_, q) = fit.next()
            synchronized(q) {
                while (q.isNotEmpty() && now - q.firstOrNull()!! > GROUP_DUP_WINDOW_MS) q.removeFirst()
                if (q.isEmpty()) fit.remove()
            }
        }
        val it = pendingGroupReceipts.entries.iterator()
        while (it.hasNext()) {
            val (key, p) = it.next()
            if (now - p.lastSentAt < GROUP_RESEND_INTERVAL_MS) continue
            // 30s 总超时 → "可能未送达"（诚实标注：回执只能证明至少一个成员收到），停止重发
            if (now - p.firstSentAt >= GROUP_CONFIRM_TIMEOUT_MS) {
                store.updateMessageStatus(p.msgId, MessageStatus.FAILED)
                it.remove()
                continue
            }
            // 已重发满 ≤3 次：停止重发，等 30s 总超时收尾（避免无限广播空耗带宽）
            if (p.retries >= GROUP_MAX_RESENDS) continue
            p.retries++
            p.lastSentAt = now
            Log.w(TAG, "resend group msg=${p.msgId} retry=${p.retries} (new envelope id)")
            debugStats.recordResend(p.msgId)
            // v1.1.57 群聊对称加密：重发同样加密（群密钥缺失则放弃——无法送达）
            val groupKey = groupKeyFor(p.groupId) ?: run {
                Log.w(TAG, "e2ee: resend group ${p.msgId} dropped, no group key")
                it.remove()
                continue
            }
            val inner = GroupBody(
                op = "MSG", groupId = p.groupId, msgId = p.msgId,
                groupName = p.groupName, text = p.text, displayName = identity.displayName,
            )
            val envelope = MeshEnvelope(
                id = UUID.randomUUID().toString(),   // 新 id = 新泛洪（同 id 被节点级去重挡住，仿真铁证无效）
                kind = "GROUP",
                srcId = identity.shortId, dstId = p.groupId, convId = "group-${p.groupId}",
                ttl = DEFAULT_TTL, ts = now, enc = "aes-gcm-v1",
                body = encryptBody(inner, groupKey, "group-${p.groupId}", "GROUP|group-${p.groupId}"),
            )
            broadcastData(envelope)
        }
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

    /** 单播帧发送路由：v1.1.84 Wi-Fi Direct TCP（最快）→ RFCOMM → BLE 广播兜底（中继仍可达）。 */
    private fun sendFrame(dstId: String, frame: MeshFrame) {
        recordSentFrame(frame)
        when {
            wfd != null && wfd.isConnectedTo(dstId) -> wfd.sendTo(dstId, frame)
            rfcomm != null && rfcomm.isConnectedTo(dstId) -> rfcomm.sendTo(dstId, frame)
            else -> transport.broadcast(frame)
        }
    }

    /**
     * v1.1.87 消息双链路：点对点帧（TEXT/重发/PONG）在 WFD 已连时**并行**经 TCP 单发——不替换 BLE 泛洪，
     * 两路任一到达即送达（蓝牙不稳时消息仍走 WFD，反之亦然）。对端对重复帧按 envelope.id 去重（ForwardingDecision
     * Drop / handleEnvelope 幂等），无重复投递。
     */
    private fun dualSendToWfd(envelope: MeshEnvelope) {
        val dst = envelope.dstId
        if (dst.isBlank() || dst == identity.shortId || wfd?.isConnectedTo(dst) != true) return
        val frame = MeshFrame(FrameType.DATA, MeshJson.encodeEnvelope(envelope).toByteArray())
        recordSentFrame(frame)
        wfd.sendTo(dst, frame)
    }

    /** v1.1.87 回执双链路：RECEIPT 帧在 WFD 已连时并行单发（BLE 广播照旧）——蓝牙不稳时送达确认仍能回传收敛。 */
    private fun dualSendReceiptToWfd(envelope: MeshEnvelope) {
        val dst = envelope.dstId
        if (dst.isBlank() || dst == identity.shortId || wfd?.isConnectedTo(dst) != true) return
        val receipt = "{\"id\":\"${envelope.id}\",\"srcId\":\"${envelope.srcId}\",\"dstId\":\"${envelope.dstId}\"}"
        wfd.sendTo(dst, MeshFrame(FrameType.RECEIPT, receipt.toByteArray()))
    }

    /**
     * v1.1.88 中继转发双链路：转发帧目标若为本机 WFD 组成员 → 并行经 WFD TCP 单发（BLE 泛洪已在 route 转发）。
     * 目标节点蓝牙断开/在 BLE 覆盖外时仍能经 WFD 直达——A-WiFi-B-BLE-C 与 C-BLE-B-WiFi-A 混合链闭环。
     * 对端对重复帧按 envelope.id 去重（ForwardingDecision Drop），无重复投递。
     */
    private fun dualRelayToWfd(forwarded: MeshEnvelope) {
        val dst = forwarded.dstId
        if (dst.isBlank() || dst == identity.shortId || wfd?.isConnectedTo(dst) != true) return
        val frame = MeshFrame(FrameType.DATA, MeshJson.encodeEnvelope(forwarded).toByteArray())
        recordSentFrame(frame)
        wfd.sendTo(dst, frame)
    }

    /**
     * v1.1.84 节点发现统一合流（BLE 扫描 + Wi-Fi Direct DnsSd）：
     * 刷新 lastSeen/scanSeenAt，保留已学昵称与 hops（WFD 节点不携带 hops，不得覆盖 BLE/心跳学到的跳数）。
     */
    private fun onPeerFound(info: MeshPeerInfo) {
        runCatching {
            // 广播确认（第三通道）：对端随扫描响应广播"已收到的消息确认键"——
            // 无需任何 GATT 连接，双方在无线电范围内且都在扫描即可交换确认（彻底绕开连接状态问题）
            info.ackKeys.forEach { key -> confirmByAckKey(key) }
            val now = System.currentTimeMillis()
            val existing = peerEntries[info.shortId]
            // 扫描帧不携带昵称（displayName 为空），保留心跳已学到的昵称，避免覆盖；
            // lastSeenAt 每次扫描帧到达都刷新 → info 必变 → _peers 流必 emit
            val displayName = existing?.info?.displayName ?: ""
            val hops = if (info.hops > 0 || existing == null) info.hops else existing.info.hops
            // v1.1.55：扫描帧只刷新 lastSeen（广播可见）+ scanSeenAt，**不刷新 appSeenAt**——
            // advertise 只证明蓝牙栈活着，不代表应用层活跃（对方后台冻结时广播仍在，
            // appSeenAt 保持过期 → heartbeatTick 判 UNRESPONSIVE，诚实标注"无响应"而非假在线）
            peerEntries[info.shortId] = PeerEntry(
                if (existing != null) info.copy(displayName = displayName, lastSeenAt = now, hops = hops)
                else info.copy(lastSeenAt = now, hops = hops),
                lastSeen = now, lost = false,
                appSeenAt = existing?.appSeenAt ?: 0L, scanSeenAt = now,
            )
            // 扫描也落库：节点持久化不依赖 PING 交互，重启后必定恢复
            store.upsertPeer(info.shortId, displayName.ifBlank { info.displayName }, now, hops)
        }.onFailure { Log.w(TAG, "peer update handling failed", it) }
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
                body = TextBody("对话请求", displayName = identity.displayName, pubKey = localPubKeyB64),
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
                    body = TextBody("已接受", pubKey = localPubKeyB64),
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
            // v1.1.57 强制加密：恢复重发也必须加密；无会话密钥（对方密钥已失）→ 跳过
            val isSelfLoop = m.dstId == identity.shortId
            val key = if (isSelfLoop) null else sessionKeyFor(m.dstId)
            if (!isSelfLoop && key == null) {
                Log.w(TAG, "e2ee: skip restore ${m.id}, no session key for ${m.dstId}")
                continue
            }
            val body: EnvelopeBody = if (isSelfLoop) TextBody(m.text ?: "")
                else encryptBody(TextBody(m.text ?: ""), key!!, "p2p", "TEXT|${m.dstId}")
            pendingReceipts.putIfAbsent(
                m.id,
                PendingText(
                    MeshEnvelope(
                        id = m.id, kind = "TEXT", srcId = m.srcId, dstId = m.dstId, convId = m.convId,
                        ttl = DEFAULT_TTL, ts = m.ts,
                        enc = if (isSelfLoop) "none" else "aes-gcm-v1", body = body,
                    ),
                    // 立即可重发（视为已超时），对方在线（PING）即收敛
                    lastSentAt = System.currentTimeMillis() - RECEIPT_TIMEOUT_MS,
                    ackKey = ackKeyFor(m.id),
                ),
            )
        }
    }

    /**
     * 重启恢复未确认群消息（v1.1.50）：进程被杀后 pendingGroupReceipts 丢失，从库中 SENDING 状态的
     * GROUP 消息重建群重发队列。**firstSentAt 重置为重启时刻**（旧 ts 可能是数小时前，直接用会让
     * 30s 总超时立即触发标"可能未送达"）；lastSentAt 置为过期 → 下一个 tick 立即可用新 id 重发。
     */
    private fun restorePendingGroupReceipts() {
        val undelivered = runCatching { store.loadUndeliveredGroups() }.getOrDefault(emptyList())
        if (undelivered.isEmpty()) return
        Log.w(TAG, "restore ${undelivered.size} undelivered group messages for retransmission")
        val now = System.currentTimeMillis()
        for (m in undelivered) {
            // 防御（审查 S4）：异常 convId 不匹配 group- 前缀时跳过，避免把整个 convId 当 groupId 寻址
            if (!m.convId.startsWith("group-")) {
                Log.w(TAG, "skip group resend restore ${m.id}: unexpected convId ${m.convId}")
                continue
            }
            val groupId = m.convId.removePrefix("group-")
            pendingGroupReceipts.putIfAbsent(
                "$GROUP_RECEIPT_ID_PREFIX${m.id}",
                PendingGroupMsg(
                    groupId = groupId, text = m.text ?: "", msgId = m.id,
                    groupName = groupNames[groupId],
                    firstSentAt = now, lastSentAt = now - GROUP_RESEND_INTERVAL_MS,
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
            val appAge = now - entry.appSeenAt    // 协议帧年龄（应用层活跃性）
            val scanAge = now - entry.lastSeen    // 最近任何帧年龄（广播可见性）
            val presence = when {
                entry.lastSeen == 0L && now - startupAt < SEARCHING_TIMEOUT_MS -> PeerPresence.SEARCHING  // 持久化恢复，6s 内寻找中
                entry.lastSeen == 0L -> PeerPresence.OFFLINE              // 6s 仍未找到 → 自动失联
                appAge < lostHeartbeatMs -> PeerPresence.ONLINE          // 有协议帧（PING/PONG/TEXT）→ 应用层活跃 → 在线
                scanAge < lostHeartbeatMs -> PeerPresence.UNRESPONSIVE   // v1.1.55：协议死但广播新鲜——对方蓝牙栈活着、应用层无响应（后台冻结/进程未恢复）
                scanAge < OFFLINE_THRESHOLD_MS -> PeerPresence.RECONNECTING   // 短暂失联 → 断线重连中
                else -> PeerPresence.OFFLINE                              // 长时间无响应 → 离线（保留）
            }
            entry.lost = scanAge > lostHeartbeatMs
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
        // v1.1.80 直连边清理：超 LINK_RECONNECT_WINDOW_MS 未确认 → 移除（节点对之间无连接）；状态流随 tick 刷新（DIRECT→RECONNECTING 推进）
        val dlit = directLinks.entries.iterator()
        while (dlit.hasNext()) {
            val (k, t) = dlit.next()
            if (now - t > LINK_RECONNECT_WINDOW_MS) {
                Log.d(TAG, "link expired: $k")
                dlit.remove()
            }
        }
        refreshLinks(now)   // 直连边状态随 tick 推进（DIRECT→RECONNECTING），now 注入保证测试/真实时钟一致
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
            // v1.1.87 重发双链路：WFD 已连时并行单发（BLE 丢帧时重发仍经 WFD 到达）
            dualSendToWfd(p.envelope)
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
        val now = System.currentTimeMillis()
        // 路由信息节流：前 2 次心跳不带（空列表省带宽），第 3 次（1.5s）带一次
        val withRoutes = pingCount % PING_RELAYS_EVERY == 0
        val relays = if (withRoutes) currentRelays() else emptyList()
        // v1.1.80：与 relays 对齐携带各邻居心跳年龄（中继链路段新鲜度/延迟）——对端据此实时判断中继是否仍通
        val relayAges = if (withRoutes) relays.map { now - (peerEntries[it]?.lastSeen ?: now) } else emptyList()
        val env = MeshEnvelope(
            id = UUID.randomUUID().toString(), kind = "PING",
            srcId = identity.shortId, dstId = "", convId = "conv-${identity.shortId}",
            ttl = DEFAULT_TTL, ts = now,
            body = PresenceBody(identity.displayName, relays = relays, seq = pingSeq, relayAges = relayAges),
        )
        val frame = MeshFrame(FrameType.DATA, MeshJson.encodeEnvelope(env).toByteArray())
        recordSentFrame(frame)
        transport.broadcast(frame)
        // v1.1.87 心跳双链路：PING 并行发给所有 WFD 已连成员（BLE 丢帧/蓝牙不稳时对端仍感知本机在线 + 学路由）
        wfd?.members()?.forEach { member ->
            if (member != identity.shortId) wfd.sendTo(member, frame)
        }
    }

    /**
     * 心跳到期检查（独立心跳协程每心跳间隔调用一次；now 可注入便于测试）。
     * 与 200ms tick 解耦，支持 50ms 级高频调试档——BLE 广播受系统约 100ms 最小间隔限制，
     * 高频档在已建立 GATT 连接通道（写/notify）上真实生效。
     *
     * v1.1.53：SILENT/已建连接下所有模式保活 PING（静默模式可连接联系人，关系人经保活感知在线）。
     * v1.1.77 修订：**CLOSED（彻底离线）停发 PING**——不产生任何蓝牙活动，与"普通离线"一致。
     */
    internal fun sendPingIfDue(now: Long = System.currentTimeMillis()) {
        // v1.1.77 彻底离线（CLOSED）：不产生任何蓝牙活动，心跳 PING 停发（恢复 NORMAL 后 lastPingAt 过期立即补发）
        if (_discoveryMode.value == DiscoveryMode.CLOSED) return
        // v1.1.81 动态心跳：有在线节点（GATT 保活畅通）→ 低频 1.5s；全部失联（GATT 连不上）→ 50ms 高频加速恢复。
        // 手动调试档（setHeartbeat）优先，不被动态覆盖。
        if (manualHeartbeatMs == null) {
            val anyOnline = peerEntries.values.any { now - it.appSeenAt <= lostHeartbeatMs }
            heartbeatIntervalMs = if (anyOnline) HEARTBEAT_INTERVAL_MS else MIN_HEARTBEAT_INTERVAL_MS
        }
        if (now - lastPingAt >= heartbeatIntervalMs) {
            lastPingAt = now
            sendPing()
        }
    }

    // ===== 调试主动控制（UI 调节经 DebugStats 控制总线下发；全部幂等可逆）=====
    /** 心跳间隔（失联阈值保持 LOST_HEARTBEAT_MS=2s 固定，不随心跳联动——用户决策）。手动档优先于 v1.1.81 动态心跳。 */
    fun setHeartbeat(intervalMs: Long) {
        manualHeartbeatMs = intervalMs.coerceIn(50L, 10_000L)
        heartbeatIntervalMs = manualHeartbeatMs!!
    }

    /** 消息重发退避（基础间隔 + 封顶）。 */
    fun setResendPolicy(baseMs: Long, maxMs: Long) {
        resendBaseMs = baseMs.coerceIn(500L, 60_000L)
        resendMaxMs = maxMs.coerceIn(baseMs, 300_000L)
    }

    /** 暂停发现层（广播+扫描；已建立 GATT 连接收发不受影响）。 */
    fun suspendSignaling() = setDiscoveryMode(DiscoveryMode.CLOSED)

    /** 恢复发现层。 */
    fun resumeSignaling() = setDiscoveryMode(DiscoveryMode.NORMAL)

    // ===== v1.1.53 发现模式（取代 v1.1.49 discoveryEnabled 布尔；用户最终设计）=====
    /**
     * 发现模式状态：NORMAL 全开 / CLOSED 全停（autoDiscovery=关 启动态，保留连接与保活）/
     * SILENT 静默模式（**只停广播**——陌生人扫不到，scan/自动连接/已建立连接与保活全部照常）。
     * 状态流供 UI 渲染；模式经 transport.applyDiscoveryMode 生效。
     */
    private val _discoveryMode = MutableStateFlow(DiscoveryMode.NORMAL)
    val discoveryMode: StateFlow<DiscoveryMode> = _discoveryMode.asStateFlow()

    /** 下发发现模式（幂等：同模式重复调用不重复下发）。 */
    fun setDiscoveryMode(mode: DiscoveryMode) {
        if (_discoveryMode.value == mode) return
        _discoveryMode.value = mode
        _discoveryEnabled.value = mode != DiscoveryMode.CLOSED
        transport.applyDiscoveryMode(mode)
        // v1.1.67 关闭搜索 = 彻底断开全部连接（用户决策：反转 v1.1.53"CLOSED 保留连接与保活"）——
        // 对方立即收不到本机心跳，失联→离线，无法继续连接/看见本机在线；恢复搜索后重新扫描自动重连
        if (mode == DiscoveryMode.CLOSED) transport.disconnectAll()
    }

    /** v1.1.49 兼容：发现层是否活动（SILENT 保留 scan/连接，仅广播不可见，故也算活动）。同步维护无异步窗口。 */
    private val _discoveryEnabled = MutableStateFlow(true)
    val discoveryEnabled: StateFlow<Boolean> = _discoveryEnabled.asStateFlow()

    /** v1.1.49 兼容：暂停广播+扫描（= 关闭扫描模式）。 */
    fun suspendDiscovery() = setDiscoveryMode(DiscoveryMode.CLOSED)

    /** v1.1.49 兼容：恢复广播+扫描（= 正常模式）。 */
    fun resumeDiscovery() = setDiscoveryMode(DiscoveryMode.NORMAL)

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
        manualHeartbeatMs = null   // v1.1.81：恢复默认后回到动态心跳
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
     * v1.1.80：① 学习 srcId 与其一跳邻居之间的直连边（拓扑图 peer-peer 边如实显示）；② 记录中继链路段
     * 新鲜度（relayAgeMs/relayFresh）——srcId 本次携带 relays 但不含某旧路由节点 → 该段疑似断，立即降级显示。
     */
    private fun learnRoutes(srcId: String, body: PresenceBody) {
        val relays = body.relays
        if (relays.isEmpty()) return
        val now = System.currentTimeMillis()
        val ages = body.relayAges   // 与 relays 对齐（老版本空 → 全 0 未知）
        for (i in relays.indices) {
            val relay = relays[i]
            if (relay == identity.shortId) continue
            // srcId 与 relay 之间直连（srcId 的 relays = 其一跳邻居），无论 relay 是否也与本机直连
            directLinks[linkKey(srcId, relay)] = now
            val direct = peerEntries[relay]
            if (direct != null && now - direct.lastSeen <= RELAY_FRESH_WINDOW_MS) continue // 一跳优先
            val age = ages.getOrElse(i) { 0L }
            routeEntries[relay] = RouteEntry(
                via = srcId, hops = 2, lastSeenAt = now,
                relayAgeMs = age, relayFresh = age <= RELAY_LINK_FRESH_MS,
            )
        }
        // v1.1.80：中继方本次携带了 relays（非空）但某旧路由节点不在其中 → 被移出中继方新鲜邻居列表 → B-C 疑似断
        // （不等到 ROUTE_EXPIRE_MS 才降级，B-C 断的瞬间 A 侧即可感知）
        routeEntries.forEach { (peerId, r) ->
            if (r.via == srcId && peerId !in relays && r.relayFresh) {
                routeEntries[peerId] = r.copy(relayFresh = false)
                Log.d(TAG, "relay link degraded: $peerId via $srcId dropped from relays")
            }
        }
        refreshPeers()   // 新学路由立即可见（markSeen 的刷新发生在 learnRoutes 之前）
        refreshLinks()   // 直连边立即可见
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

    /** 标记节点可见：更新 lastSeen + appSeenAt（协议帧=应用层活跃）；带昵称时更新显示名并落库。 */
    private fun markSeen(peerId: String, displayName: String) {
        val now = System.currentTimeMillis()
        val existing = peerEntries[peerId]
        if (existing != null) {
            existing.lastSeen = now
            existing.appSeenAt = now   // v1.1.55：协议帧到达 = 应用层活跃（区别于纯广播）
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
                appSeenAt = now,  // v1.1.55：协议帧到达 = 应用层活跃
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
        // v1.1.60：CLOSED（停止搜索）时只保留已会话节点——非会话历史节点（含 2 跳中继）不输出到 peers 流。
        // 顶部"发现节点 N"与 Mesh 页据此不再把已离线/无扫描的历史节点算入（用户：关闭蓝牙搜索后没搜到就不该显示；
        // 已会话联系人靠 GATT 保活心跳保持 ONLINE，照常显示）。恢复 NORMAL 后 peerEntries 全量恢复。
        val searchStopped = discoveryMode.value == DiscoveryMode.CLOSED
        peerEntries.values.forEach { e ->
            val info = e.info.copy(signalRatio = signal, rttMs = peerRtt[e.info.shortId] ?: 0L)
            if (searchStopped && info.shortId !in _sessions.value) return@forEach
            result[info.shortId] = info
        }
        routeEntries.forEach { (peerId, r) ->
            if (searchStopped) return@forEach
            val direct = peerEntries[peerId]
            // v1.1.78（用户：近距离直连优先）：直连失联 <5s 仍视为直连可用（不降级中继）；
            // ≥5s（DIRECT_RELAY_FALLBACK_MS）才用 2 跳版本覆盖显示"经中继可达"。持续扫描下直连一恢复立即切回直连（markSeen 清 routeEntries）。
            val directOnline = direct != null && now - direct.lastSeen <= DIRECT_RELAY_FALLBACK_MS
            if (!directOnline) {
                result[peerId] = MeshPeerInfo(
                    shortId = peerId, deviceAddress = "", rssi = 0, hops = r.hops,
                    displayName = direct?.info?.displayName ?: "",  // 保留已学昵称
                    // v1.1.80 中继链路健康：relayFresh=false（中继方已把该节点移出新鲜邻居列表）→ 降级为"重连中"（琥珀），
                    // 而非继续显示"经中继可达"（绿色）——B-C 断的瞬间 A 即可感知，不等 30s 路由过期。
                    lost = false, presence = if (r.relayFresh) PeerPresence.ONLINE else PeerPresence.RECONNECTING,
                    relayVia = r.via, relayAgeMs = r.relayAgeMs, lastSeenAt = r.lastSeenAt,
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
                    DebugLogBuffer.log("MeshSvc", "recv FILE3 frame ${frame.payload.size}B")
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
                    // v1.1.50：群回执（id="G$msgId"）也在此收敛——发送方命中任一队列即本地确认，不再转发
                    if (pendingReceipts.containsKey(id) || pendingGroupReceipts.containsKey(id)) {
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

    /** 查询对端是否经中继可达（v1.1.0）：命中路由表返回经由节点 shortId，否则 null。
     *  v1.1.78（用户：直连优先）：一跳直连新鲜（<3s）时返回 null——直连连得上就不走中继（含送达文案）。 */
    fun relayViaFor(peerId: String): String? {
        val direct = peerEntries[peerId]
        if (direct != null && System.currentTimeMillis() - direct.lastSeen <= DIRECT_RELAY_FALLBACK_MS) return null
        return routeEntries[peerId]?.via
    }

    private fun handleEnvelope(envelopeIn: MeshEnvelope) {
        if (envelopeIn.srcId == identity.shortId) return // 忽略自身回环帧
        // v1.1.64 拉黑：已拉黑节点的所有帧（INVITE/TEXT/PING/群消息）直接忽略——对方无法连接、无法发消息
        if (envelopeIn.srcId in _blockedPeers.value) {
            Log.d(TAG, "drop frame from blocked peer ${envelopeIn.srcId}")
            return
        }
        // v1.1.66 频道校验：私人频道下已记录节点指纹不匹配 → 丢弃（防御残留连接/改装连入 GATT server）
        if (channelFingerprint != 0L) {
            peerEntries[envelopeIn.srcId]?.let { known ->
                if (known.info.channelFingerprint != channelFingerprint) {
                    Log.d(TAG, "drop cross-channel frame from ${envelopeIn.srcId}")
                    return
                }
            }
        }
        // v1.1.57 E2EE：SecBody → 解密还原内层 body（TextBody/GroupBody）再走原逻辑；
        // 解密失败（无密钥/认证失败）→ 丢弃（防篡改/监听者注入）。信封路由字段（kind/dstId/ttl）不加密。
        var envelope = envelopeIn
        decryptSecBody(envelopeIn)?.let { resolved ->
            envelope = envelopeIn.copy(body = resolved, enc = "aes-gcm-v1")
        }
        Log.d(TAG, "recv kind=${envelope.kind} src=${envelope.srcId} dst=${envelope.dstId} sessions=${_sessions.value.size}")
        // 握手/控制帧走双通道（write + notify）可能重复送达，按信封 id 去重
        if (envelope.kind == "INVITE" || envelope.kind == "INVITE_ACK" || envelope.kind == "BLOCK") {
            if (dedup.contains(envelope.id)) return
            dedup.mark(envelope.id)
        }
        when (envelope.kind) {
            "INVITE" -> {
                // 邀请是一跳点对点帧，仅处理发往本机的（防空广播把邀请泄露给无关节点弹窗）
                if (envelope.dstId.isNotBlank() && envelope.dstId != identity.shortId) return
                // v1.1.57 E2EE：对端公钥 → 派生会话密钥（后续消息加密互通）
                (envelope.body as? TextBody)?.pubKey?.let { deriveSessionKey(envelope.srcId, it) }
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
                // v1.1.57 E2EE：对端公钥 → 派生会话密钥
                (envelope.body as? TextBody)?.pubKey?.let { deriveSessionKey(envelope.srcId, it) }
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
            "BLOCK" -> {
                // v1.1.79 拉黑通知（对方拉黑我）：解除会话 + 清除对端密钥指纹 + 断开连接，变回陌生节点。
                // 不互拉黑（尊重"仅单向拒绝"语义——对方仍可被我搜索到，但重新邀请会被对方丢弃）。
                val src = envelope.srcId
                _sessions.update { it - src }
                sessionStore.save(_sessions.value)
                _pendingInvites.update { it - src }
                _invites.update { it - src }
                _ackRetries.update { it - src }
                peerKeyStore.remove(src)   // 指纹重立：下次握手重新 TOFU 确立新密钥
                transport.disconnectPeer(src)
                DebugLogBuffer.log("MeshSvc", "收到 $src 的拉黑通知，已解除会话并清除密钥指纹")
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
                    // v1.1.80：回带所回应 PING 的 ts → 发送方收到后 rtt ≈ now - pingTs（实时延迟显示）
                    body = PresenceBody(identity.displayName, ackIds = ackIdsFor(envelope.srcId), pingTs = envelope.ts),
                )
                val pongFrame = MeshFrame(FrameType.DATA, MeshJson.encodeEnvelope(pong).toByteArray())
                recordSentFrame(pongFrame)
                transport.broadcast(pongFrame)
                // v1.1.87 PONG 双链路：WFD 已连时并行单发（蓝牙不稳时对端仍收到送达确认 + 本机在线状态）
                dualSendToWfd(pong)
            }
            "PONG" -> {
                if (envelope.dstId.isNotBlank() && envelope.dstId != identity.shortId) return
                // v1.1.80 往返延迟测量：PONG 回带所回应 PING 的 ts → rtt ≈ now - pingTs（广播往返近似单跳延迟；异常值忽略）。
                // 必须在 markSeen（其内部 refreshPeers 输出本轮 rttMs）之前写入，否则 peers 流要等下一轮才带出新延迟。
                (envelope.body as? PresenceBody)?.pingTs?.takeIf { it > 0 }?.let { pt ->
                    val rtt = System.currentTimeMillis() - pt
                    if (rtt in 0..10_000) peerRtt[envelope.srcId] = rtt
                }
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
            "GROUP" -> {
                // ===== v1.1.50 群消息（广播域模型）=====
                // dstId=groupId≠本机，**投递**不走 ForwardingDecision（会判 Forward 只转发不落库）——
                // 显式双动作：已订阅 → 落库+回执；随后**转发**仍复用 route（ForwardingDecision 只会判
                // Forward，DedupCache 防环 + TTL 递减 + 抖动错峰）；订阅者即中继，泛洪延伸所有成员。
                val body = envelope.body as? GroupBody ?: return
                if (body.op == "JOIN") {
                    // 群创建帧（MVP 仅传播群名+群密钥）：学习群名；携带群密钥则保存（群聊对称加密前提）
                    learnGroupName(body.groupId, body.groupName)
                    if (body.groupKey.isNotBlank()) {
                        val gk = runCatching { java.util.Base64.getDecoder().decode(body.groupKey) }.getOrNull()
                        if (gk != null) saveGroupKey(body.groupId, gk)
                    }
                } else if (body.groupId in _joinedGroups.value) {
                    if (!isGroupDup(envelope, body)) {   // 内容指纹去重：新 id 重发/环路重复不重复落库
                        learnGroupName(body.groupId, body.groupName)
                        markSeen(envelope.srcId, body.displayName)   // 昵称学习（同 TEXT，供气泡/通知显示）
                        // v1.1.54：群成员统计（本机见过的去重发言者，内存态；近似成员数显示）
                        // computeIfAbsent：首帧 key 不存在时先建集合，避免 ?.add 返回 null 导致首帧不计数
                        _groupMembers.value = _groupMembers.value + (body.groupId to ((_groupMembers.value[body.groupId] ?: 0).let {
                            if (groupMemberIds.computeIfAbsent(body.groupId) { java.util.Collections.newSetFromMap(ConcurrentHashMap()) }.add(envelope.srcId)) it + 1 else it
                        }))
                        store.insertMessage(envelope.toStoredMessage())
                        maybeSendGroupReceipt(envelope, body)
                        // 群通知（与点对点一致；convId=group-<groupId> 点击直达群会话）
                        val fromName = peerEntries[envelope.srcId]?.info?.displayName?.ifBlank { envelope.srcId } ?: envelope.srcId
                        onIncomingMessage(envelope.srcId, fromName, body.text ?: "", envelope.convId)
                    }
                }
                // 无条件转发（已订阅也转发；DedupCache 防环 + TTL 递减 + 抖动错峰）
                if (envelope.ttl - 1 > 0) route(envelope, jitter = true)
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
                    onIncomingMessage(envelope.srcId, fromName, (envelope.body as? TextBody)?.text ?: "", "conv-${envelope.srcId}")
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
                // v1.1.88 中继转发双链路（A-WiFi-B-BLE-C 混合回传闭环）：
                // 转发帧目标若为本机 WFD 组成员 → 并行经 WFD TCP 单发（BLE 泛洪照旧）。目标蓝牙断开/在 BLE
                // 覆盖外时仍能经 WFD 直达；对端对重复帧按 envelope.id 去重。反向 C-BLE-B-WiFi-A 由此打通。
                dualRelayToWfd(forwarded)
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
        // v1.1.87 回执双链路：WFD 已连时并行单发（蓝牙不稳时送达确认仍回传收敛）
        dualSendReceiptToWfd(envelope)
    }

    private fun handleReceipt(frame: MeshFrame) {
        val text = frame.payloadText
        val id = Regex("\"id\":\"([^\"]+)\"").find(text)?.groupValues?.get(1) ?: return
        if (id.startsWith(GROUP_RECEIPT_ID_PREFIX)) {
            // v1.1.50 群回执：id="G$msgId" → 路由到独立群队列；"已送达" = 至少一个成员确认（非全员，诚实标注）
            if (pendingGroupReceipts.remove(id) != null) {
                store.updateMessageStatus(id.removePrefix(GROUP_RECEIPT_ID_PREFIX), MessageStatus.DELIVERED)
                debugStats.recordConfirmed(id)
            }
        } else {
            store.updateMessageStatus(id, MessageStatus.DELIVERED)
            pendingReceipts.remove(id)
            debugStats.recordConfirmed(id)
        }
    }

    private fun MeshEnvelope.toStoredMessage(): StoredMessage {
        val text = (body as? TextBody)?.text ?: (body as? GroupBody)?.text
        // 会话键：TEXT 以「发送者短 ID」为统一命名基准（conv-<srcId>）——发送方用对端 ID 命名、
        // 接收方用发送者 ID 命名会导致收发双方读写不同会话键，消息存了却查不到。
        // GROUP 用信封自带 convId（group-<groupId>）——群会话键与点对点命名空间隔离。
        val convId = if (body is GroupBody) convId else "conv-$srcId"
        return StoredMessage(
            id = id, convId = convId, kind = kind, srcId = srcId, dstId = dstId,
            text = text, ts = ts, status = MessageStatus.DELIVERED,
        )
    }
}
