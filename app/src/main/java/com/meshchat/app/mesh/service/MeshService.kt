package com.meshchat.app.mesh.service

import com.meshchat.app.mesh.identity.LocalIdentity
import com.meshchat.app.mesh.protocol.FrameType
import com.meshchat.app.mesh.protocol.MeshEnvelope
import com.meshchat.app.mesh.protocol.MeshFrame
import com.meshchat.app.mesh.protocol.MeshJson
import com.meshchat.app.mesh.protocol.TextBody
import com.meshchat.app.mesh.routing.DedupCache
import com.meshchat.app.mesh.routing.ForwardDecision
import com.meshchat.app.mesh.routing.ForwardingDecision
import com.meshchat.app.mesh.storage.MessageStatus
import com.meshchat.app.mesh.storage.MeshStore
import com.meshchat.app.mesh.storage.OutboxEntry
import com.meshchat.app.mesh.storage.StoredMessage
import com.meshchat.app.mesh.transport.MeshPeerInfo
import com.meshchat.app.mesh.transport.MeshTransport
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val DEFAULT_TTL = 8
private const val OUTBOX_TTL_MS = 60_000L

class MeshService(
    private val transport: MeshTransport,
    private val store: MeshStore,
    private val identity: LocalIdentity,
    private val dedup: DedupCache,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var receiveJob: Job? = null
    private var peerJob: Job? = null

    private val _peers = MutableStateFlow<List<MeshPeerInfo>>(emptyList())
    val peers: StateFlow<List<MeshPeerInfo>> = _peers.asStateFlow()

    /** 本机短 ID（对端寻址标识）。 */
    val shortId: String get() = identity.shortId

    /** 已建立对话关系的对端节点集合。 */
    private val _sessions = MutableStateFlow<Set<String>>(emptySet())
    val sessions: StateFlow<Set<String>> = _sessions.asStateFlow()

    /** 收到的待确认对话请求：peerId -> 请求时间戳。 */
    private val _invites = MutableStateFlow<Map<String, Long>>(emptyMap())
    val invites: StateFlow<Map<String, Long>> = _invites.asStateFlow()

    fun start() {
        transport.start()
        receiveJob = scope.launch {
            transport.incoming.catch { }.collect { frame -> handleFrame(frame) }
        }
        peerJob = scope.launch {
            transport.foundPeers.catch { }.collect { info ->
                _peers.update { current ->
                    (current.filterNot { it.shortId == info.shortId } + info)
                }
            }
        }
    }

    fun stop() {
        receiveJob?.cancel()
        peerJob?.cancel()
        transport.stop()
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
        route(envelope)
    }

    /** 向对端发起对话请求（建立对话关系的前置握手）。 */
    fun sendInvite(peerId: String) {
        if (peerId in _sessions.value) return
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

    /** 接受对话请求：建立会话关系并回发确认。 */
    fun acceptInvite(peerId: String) {
        _sessions.update { it + peerId }
        _invites.update { it - peerId }
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
        when (envelope.kind) {
            "INVITE" -> {
                if (envelope.srcId !in _sessions.value) {
                    _invites.update { it + (envelope.srcId to envelope.ts) }
                }
            }
            "INVITE_ACK" -> {
                _sessions.update { it + envelope.srcId }
                _invites.update { it - envelope.srcId }
            }
            else -> {
                // 仅已建立对话关系的节点（或本机自环）间的消息参与路由投递
                if (envelope.srcId in _sessions.value || envelope.srcId == identity.shortId) {
                    route(envelope)
                }
            }
        }
    }

    private fun route(envelope: MeshEnvelope) {
        when (val decision = ForwardingDecision(identity.shortId, dedup).decide(envelope)) {
            ForwardDecision.Deliver -> {
                store.insertMessage(envelope.toStoredMessage())
                store.updateMessageStatus(envelope.id, MessageStatus.DELIVERED)
                sendReceipt(envelope)
            }
            is ForwardDecision.Forward -> {
                val forwarded = envelope.copy(ttl = decision.ttl)
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
            ForwardDecision.Drop -> Unit
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
    }

    private fun MeshEnvelope.toStoredMessage(): StoredMessage {
        val text = (body as? TextBody)?.text
        return StoredMessage(
            id = id, convId = convId, kind = kind, srcId = srcId, dstId = dstId,
            text = text, ts = ts, status = MessageStatus.DELIVERED,
        )
    }
}
