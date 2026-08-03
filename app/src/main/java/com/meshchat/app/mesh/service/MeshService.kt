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

    fun handleFrame(frame: MeshFrame) {
        when (frame.type) {
            FrameType.DATA -> {
                val envelope = runCatching { MeshJson.decodeEnvelope(frame.payloadText) }
                    .getOrNull() ?: return
                route(envelope)
            }
            FrameType.RECEIPT -> handleReceipt(frame)
            else -> Unit // HELLO/ACK/PING 由传输层处理
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
