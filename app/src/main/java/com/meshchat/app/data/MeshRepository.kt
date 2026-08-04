package com.meshchat.app.data

import com.meshchat.app.mesh.quality.BluetoothQuality
import com.meshchat.app.mesh.service.MeshService
import com.meshchat.app.mesh.storage.MessageStatus
import com.meshchat.app.mesh.storage.MeshStore
import com.meshchat.app.mesh.transport.MeshPeerInfo
import com.meshchat.app.mesh.transport.PeerPresence
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

interface MeshRepository {
    fun observeConversations(): Flow<List<ChatPreview>>
    fun observeMessages(convId: String): Flow<List<ChatMessage>>
    fun observePeers(): Flow<List<MeshPeer>>
    fun observeSessions(): Flow<Set<String>>
    fun observePendingInvites(): Flow<Set<String>>
    fun observeInvites(): Flow<Map<String, Long>>
    fun sendText(convId: String, text: String)
    fun sendFile(convId: String, dstId: String, openSource: () -> java.io.InputStream, fileName: String, mime: String, size: Long): String?
    fun observeFileProgress(): Flow<com.meshchat.app.mesh.transfer.FileProgress?>
    fun sendInvite(peerId: String)
    fun acceptInvite(peerId: String)
    fun rejectInvite(peerId: String)
    fun startDiscovery()
    fun localShortId(): String
}

class MeshRepositoryImpl(
    private val service: MeshService,
    private val store: MeshStore,
) : MeshRepository {

    override fun observeConversations(): Flow<List<ChatPreview>> =
        combine(service.sessions, store.observeConversationIds(), service.peers) { sessionIds, historyConvIds, peers ->
            // 会话集合 + 消息历史反推的对话兜底：即使会话关系持久化丢失/重装，最近对话列表也不空（持久化效果）
            val ids = (sessionIds + historyConvIds.map { it.substringAfterLast("-") })
                .distinct()
                .filter { it.isNotBlank() && it != "ME" }
            ids.map { id ->
                val peer = peers.firstOrNull { it.shortId == id }
                val name = peer?.displayName?.ifBlank { id } ?: id
                val presence = peer?.presence ?: PeerPresence.SEARCHING
                ChatPreview(
                    id = id, name = name, snippet = "已建立对话", time = "",
                    reachability = if (presence == PeerPresence.ONLINE) Reachability.REACHABLE else Reachability.QUEUED,
                    presence = presence,
                )
            }
        }

    override fun observePendingInvites(): Flow<Set<String>> = service.pendingInvites

    override fun observePeers(): Flow<List<MeshPeer>> =
        service.peers.map { list -> list.map { it.toUiModel() } }

    override fun startDiscovery() {
        service.start()               // 确保 Mesh 逻辑已启动（幂等）
        service.restartDiscovery()    // 强制重建 BLE：清空遗留状态（蓝牙从关到开/连接残留时必需）
    }

    override fun localShortId(): String = service.shortId

    override fun observeMessages(convId: String): Flow<List<ChatMessage>> =
        store.observeMessages(convId).map { list -> list.map { it.toUiModel() } }

    override fun observeSessions(): Flow<Set<String>> = service.sessions

    override fun observeInvites(): Flow<Map<String, Long>> = service.invites

    override fun sendText(convId: String, text: String) {
        // 自环会话（conv-ME）目标为本机短 ID，触发本地投递；节点会话目标为其短 ID
        val dstId = if (convId == "conv-ME") service.shortId else convId.substringAfterLast("-")
        service.sendText(convId, dstId, text)
    }

    override fun sendFile(convId: String, dstId: String, openSource: () -> java.io.InputStream, fileName: String, mime: String, size: Long): String? =
        service.sendFile(convId, dstId, openSource, fileName, mime, size)

    override fun observeFileProgress(): Flow<com.meshchat.app.mesh.transfer.FileProgress?> =
        service.fileProgress

    override fun sendInvite(peerId: String) = service.sendInvite(peerId)

    override fun acceptInvite(peerId: String) = service.acceptInvite(peerId)

    override fun rejectInvite(peerId: String) = service.rejectInvite(peerId)

    private fun MeshPeerInfo.toUiModel(): MeshPeer {
        val strength = BluetoothQuality.bars(rssi)
        return MeshPeer(
            name = displayName.ifBlank { shortId }, shortId = shortId, hops = hops, strength = strength,
            rssi = rssi, lost = lost, reachable = !lost, presence = presence, lastSeenAt = lastSeenAt,
        )
    }

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
                uri = meta["downloadsUri"]?.takeIf { it.isNotBlank() },
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
}
