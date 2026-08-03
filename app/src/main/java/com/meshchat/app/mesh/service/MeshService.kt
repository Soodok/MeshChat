package com.meshchat.app.mesh.service

import android.util.Log
import com.meshchat.app.mesh.identity.LocalIdentity
import com.meshchat.app.mesh.protocol.FileAckBody
import com.meshchat.app.mesh.protocol.FileBody
import com.meshchat.app.mesh.protocol.FrameType
import com.meshchat.app.mesh.protocol.MeshEnvelope
import com.meshchat.app.mesh.protocol.MeshFrame
import com.meshchat.app.mesh.protocol.MeshJson
import com.meshchat.app.mesh.protocol.PresenceBody
import com.meshchat.app.mesh.protocol.TextBody
import com.meshchat.app.mesh.routing.DedupCache
import com.meshchat.app.mesh.routing.ForwardDecision
import com.meshchat.app.mesh.routing.ForwardingDecision
import com.meshchat.app.mesh.storage.MessageStatus
import com.meshchat.app.mesh.storage.MeshStore
import com.meshchat.app.mesh.storage.OutboxEntry
import com.meshchat.app.mesh.storage.StoredMessage
import com.meshchat.app.mesh.transfer.FileSaver
import com.meshchat.app.mesh.transfer.FileTransferManager
import com.meshchat.app.mesh.transfer.TransferStatus
import com.meshchat.app.mesh.transport.MeshPeerInfo
import com.meshchat.app.mesh.transport.MeshTransport
import java.io.File
import java.util.UUID
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
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val DEFAULT_TTL = 8
private const val OUTBOX_TTL_MS = 60_000L
private const val REFRESH_INTERVAL_MS = 200L      // 探测刷新周期 0.2s
private const val HEARTBEAT_INTERVAL_MS = 1_000L  // PING 广播周期：1s 校准一次
private const val LOST_HEARTBEAT_MS = 3_000L      // 超过该时长无任何 PING/PONG/扫描帧 → 判失联（容忍 1-2 帧丢失）
private const val LOST_REMOVE_MS = 5_000L         // 失联超过该时长 → 从列表移除
private const val RECEIPT_TIMEOUT_MS = 5_000L     // 消息发出后未收到送达回执的等待时间，超时重发
private const val MAX_RECEIPT_RETRIES = 3         // 重发上限：超过标记 FAILED（不再无限卡 SENDING）

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
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false
    private var receiveJob: Job? = null
    private var peerJob: Job? = null
    private var tickJob: Job? = null

    /** 文件传输引擎：发送状态机 + 接收重组 + 窗口批量 bitmap 确认。 */
    private val transfer = FileTransferManager(
        transport = transport, shortId = identity.shortId, saver = fileSaver,
        scope = scope, tmpDirProvider = tmpDir,
        sendFrame = { dstId, frame -> sendFrame(dstId, frame) },
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

    /** 探测刷新周期：UI 节点状态每 200ms 更新一次（含 RSSI 与失联标注）。 */
    private val peerEntries = LinkedHashMap<String, PeerEntry>()

    private data class PeerEntry(var info: MeshPeerInfo, var lastSeen: Long, var lost: Boolean)

    /** 上次 PING 广播时刻（tick 200ms 节流到 1s）。 */
    private var lastPingAt = 0L

    /** 待送达确认的 TEXT：id -> (信封, 上次发送时刻, 重试次数)。回执（RECEIPT）是广播帧可能丢失，需超时重发。 */
    private class PendingText(val envelope: MeshEnvelope, var lastSentAt: Long, var retries: Int = 0)
    private val pendingReceipts = LinkedHashMap<String, PendingText>()

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
        _sessions.value = sessionStore.load()   // 重启恢复已建立的会话关系
        transport.start()
        rfcomm?.start()
        receiveJob = scope.launch {
            transport.incoming.catch { }.collect { frame -> handleFrame(frame) }
        }
        // RFCOMM 通道合流：文件帧经高速通道到达时同样走 handleFrame
        rfcomm?.incoming?.let { flow ->
            scope.launch { flow.catch { }.collect { frame -> handleFrame(frame) } }
        }
        peerJob = scope.launch {
            transport.foundPeers.catch { }.collect { info ->
                val now = System.currentTimeMillis()
                val existing = peerEntries[info.shortId]
                // 扫描帧不携带昵称（displayName 为空），保留心跳已学到的昵称，避免覆盖
                peerEntries[info.shortId] = PeerEntry(
                    if (existing != null) info.copy(displayName = existing.info.displayName) else info,
                    lastSeen = now, lost = false,
                )
            }
        }
        tickJob = scope.launch {
            while (isActive) {
                delay(REFRESH_INTERVAL_MS)
                val now = System.currentTimeMillis()
                heartbeatTick(now)
                resendPendingReceipts(now)
                // 会话状态机每 0.2s 检测一次：向已接受邀请的对端持续重发确认，直至其确认或超时
                tickSessionState(now)
                // 文件传输接收超时清理（60s 无进展丢弃）
                transfer.tick(now)
            }
        }
    }

    fun stop() {
        if (!started) return
        started = false
        receiveJob?.cancel()
        peerJob?.cancel()
        tickJob?.cancel()
        transport.stop()
        rfcomm?.stop()
        scope.cancel()
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
            body = TextBody(text),
        )
        store.insertMessage(
            StoredMessage(
                id = envelope.id, convId = convId, kind = "TEXT",
                srcId = envelope.srcId, dstId = dstId, text = text, ts = envelope.ts,
            ),
        )
        // 登记待确认：回执（RECEIPT）是广播帧可能丢失，由 resendPendingReceipts 超时重发收敛
        pendingReceipts[envelope.id] = PendingText(envelope, System.currentTimeMillis())
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
        if (rfcomm != null && rfcomm.isConnectedTo(dstId)) rfcomm.sendTo(dstId, frame)
        else transport.broadcast(frame)
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
                body = TextBody("对话请求"),
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
        transport.broadcast(
            MeshFrame(
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
            ),
        )
    }

    /**
     * 心跳 tick（tick 循环每 200ms 调用）：
     * 每 1s 广播 PING（带本机昵称）；按 3s 超时更新各节点在线状态。
     */
    internal fun heartbeatTick(now: Long) {
        if (now - lastPingAt >= HEARTBEAT_INTERVAL_MS) {
            lastPingAt = now
            sendPing()
        }
        val iterator = peerEntries.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next().value
            entry.lost = now - entry.lastSeen > LOST_HEARTBEAT_MS
            if (now - entry.lastSeen > LOST_REMOVE_MS) iterator.remove()
        }
        _peers.value = peerEntries.values.map { it.info.copy(lost = it.lost) }
    }

    /**
     * 待确认 TEXT 重发（tick 每 200ms 调用）：
     * 5s 未收到送达回执 → 重发同 id 消息（接收方 dedup 命中会补回执）；重试达上限标记 FAILED，避免状态卡 SENDING。
     */
    internal fun resendPendingReceipts(now: Long) {
        val it = pendingReceipts.entries.iterator()
        while (it.hasNext()) {
            val (id, p) = it.next()
            if (now - p.lastSentAt < RECEIPT_TIMEOUT_MS) continue
            if (p.retries >= MAX_RECEIPT_RETRIES) {
                Log.w(TAG, "text $id no receipt after ${MAX_RECEIPT_RETRIES} retries, mark FAILED")
                store.updateMessageStatus(id, MessageStatus.FAILED)
                it.remove()
                continue
            }
            p.retries++
            p.lastSentAt = now
            Log.w(TAG, "resend text $id retry=${p.retries}")
            transport.broadcast(MeshFrame(FrameType.DATA, MeshJson.encodeEnvelope(p.envelope).toByteArray()))
        }
    }

    /** 广播 PING（带本机昵称），对端收到回 PONG。 */
    private fun sendPing() {
        val env = MeshEnvelope(
            id = UUID.randomUUID().toString(), kind = "PING",
            srcId = identity.shortId, dstId = "", convId = "conv-${identity.shortId}",
            ttl = DEFAULT_TTL, ts = System.currentTimeMillis(),
            body = PresenceBody(identity.displayName),
        )
        transport.broadcast(MeshFrame(FrameType.DATA, MeshJson.encodeEnvelope(env).toByteArray()))
    }

    /** 标记节点可见：更新 lastSeen；带昵称时更新显示名并落库。 */
    private fun markSeen(peerId: String, displayName: String) {
        val now = System.currentTimeMillis()
        val existing = peerEntries[peerId]
        if (existing != null) {
            existing.lastSeen = now
            if (displayName.isNotBlank() && displayName != existing.info.displayName) {
                existing.info = existing.info.copy(displayName = displayName)
            }
        } else {
            peerEntries[peerId] = PeerEntry(
                MeshPeerInfo(shortId = peerId, deviceAddress = "", rssi = 0, hops = 1, displayName = displayName),
                lastSeen = now, lost = false,
            )
        }
        if (displayName.isNotBlank()) store.upsertPeer(peerId, displayName, now, 1)
        // 同步刷新 peers 流：UI/通知实时可见，无需等下一轮 tick
        _peers.value = peerEntries.values.map { it.info.copy(lost = it.lost) }
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

    fun handleFrame(frame: MeshFrame) {
        when (frame.type) {
            FrameType.DATA -> {
                val envelope = runCatching { MeshJson.decodeEnvelope(frame.payloadText) }
                    .getOrNull() ?: return
                handleEnvelope(envelope)
            }
            FrameType.RECEIPT -> handleReceipt(frame)
            else -> Unit // HELLO/ACK/PING 由传输层处理
        }
    }

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
                val pong = MeshEnvelope(
                    id = UUID.randomUUID().toString(), kind = "PONG",
                    srcId = identity.shortId, dstId = envelope.srcId, convId = "conv-${envelope.srcId}",
                    ttl = DEFAULT_TTL, ts = System.currentTimeMillis(),
                    body = PresenceBody(identity.displayName),
                )
                transport.broadcast(MeshFrame(FrameType.DATA, MeshJson.encodeEnvelope(pong).toByteArray()))
            }
            "PONG" -> {
                if (envelope.dstId.isNotBlank() && envelope.dstId != identity.shortId) return
                markSeen(envelope.srcId, (envelope.body as? PresenceBody)?.displayName ?: "")
            }
            "FILE" -> {
                // 一跳帧（同握手帧）：仅处理发往本机；非本机忽略（ACK 一跳语义下多跳无法回传）
                if (envelope.dstId.isNotBlank() && envelope.dstId != identity.shortId) return
                val body = envelope.body as? FileBody ?: return
                // 先落库占位（按 fileId 去重；upsert 幂等），再收块——收齐回调会置 DELIVERED，顺序不能反
                if (receivedFiles.add(body.fileId)) {
                    store.insertMessage(
                        StoredMessage(
                            id = body.fileId, convId = "conv-${envelope.srcId}", kind = "FILE",
                            srcId = envelope.srcId, dstId = envelope.dstId, text = body.fileName,
                            fileMeta = fileMetaJson(body.fileName, body.mime, body.size, null),
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
                if (envelope.dstId.isBlank() || envelope.dstId == identity.shortId || envelope.srcId in _sessions.value) {
                    route(envelope)
                }
            }
        }
    }

    private fun route(envelope: MeshEnvelope) {
        when (val decision = ForwardingDecision(identity.shortId, dedup).decide(envelope)) {
            ForwardDecision.Deliver -> {
                Log.d(TAG, "deliver kind=${envelope.kind} src=${envelope.srcId} dst=${envelope.dstId}")
                store.insertMessage(envelope.toStoredMessage())
                store.updateMessageStatus(envelope.id, MessageStatus.DELIVERED)
                sendReceipt(envelope)
                // 收到消息回调（通知用）：仅对端发来的 TEXT 触发
                if (envelope.kind == "TEXT" && envelope.srcId != identity.shortId) {
                    val fromName = peerEntries[envelope.srcId]?.info?.displayName?.ifBlank { envelope.srcId } ?: envelope.srcId
                    onIncomingMessage(envelope.srcId, fromName, (envelope.body as? TextBody)?.text ?: "")
                }
            }
            is ForwardDecision.Forward -> {
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
                transport.broadcast(MeshFrame(FrameType.DATA, MeshJson.encodeEnvelope(forwarded).toByteArray()))
            }
            ForwardDecision.Drop -> {
                // 重复 TEXT（发送方超时重发等确认）：本机已投递过，补发回执让发送方收敛，不再重复落库
                if (envelope.kind == "TEXT") sendReceipt(envelope)
            }
        }
    }

    private fun sendReceipt(envelope: MeshEnvelope) {
        val receipt = "{\"id\":\"${envelope.id}\",\"srcId\":\"${envelope.srcId}\",\"dstId\":\"${envelope.dstId}\"}"
        transport.broadcast(MeshFrame(FrameType.RECEIPT, receipt.toByteArray()))
    }

    private fun handleReceipt(frame: MeshFrame) {
        val text = frame.payloadText
        val id = Regex("\"id\":\"([^\"]+)\"").find(text)?.groupValues?.get(1) ?: return
        store.updateMessageStatus(id, MessageStatus.DELIVERED)
        pendingReceipts.remove(id)
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
