package com.meshchat.app.data

import com.meshchat.app.mesh.service.MeshService
import com.meshchat.app.mesh.storage.MessageStatus
import com.meshchat.app.mesh.storage.MeshStore
import com.meshchat.app.mesh.transport.MeshPeerInfo
import kotlinx.coroutines.flow.Flow
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
    fun observeInvites(): Flow<Map<String, Long>>
    fun sendText(convId: String, text: String)
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
        flowOf(emptyList()) // 会话数据源：待按后端会话表接入

    override fun observePeers(): Flow<List<MeshPeer>> =
        service.peers.map { list -> list.map { it.toUiModel() } }

    override fun startDiscovery() {
        service.start()
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

    override fun sendInvite(peerId: String) = service.sendInvite(peerId)

    override fun acceptInvite(peerId: String) = service.acceptInvite(peerId)

    override fun rejectInvite(peerId: String) = service.rejectInvite(peerId)

    private fun MeshPeerInfo.toUiModel(): MeshPeer {
        val strength = when {
            rssi >= -60 -> 3
            rssi >= -75 -> 2
            rssi >= -90 -> 1
            else -> 0
        }
        return MeshPeer(name = shortId, hops = hops, strength = strength, reachable = true)
    }

    private fun com.meshchat.app.mesh.storage.StoredMessage.toUiModel(): ChatMessage {
        val time = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(ts))
        val delivery = when (status) {
            MessageStatus.SENDING -> "正在通过 Mesh 发送"
            MessageStatus.DELIVERED -> "已通过 Mesh 送达"
            MessageStatus.FAILED -> "未送达"
        }
        return ChatMessage(
            id = id,
            text = text ?: "",
            sentByMe = true,
            time = time,
            delivery = delivery,
        )
    }
}
