package com.meshchat.app.data

import com.meshchat.app.mesh.service.MeshService
import com.meshchat.app.mesh.storage.MessageStatus
import com.meshchat.app.mesh.storage.MeshStore
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
    fun sendText(convId: String, text: String)
    fun startDiscovery()
}

class MeshRepositoryImpl(
    private val service: MeshService,
    private val store: MeshStore,
) : MeshRepository {

    override fun observeConversations(): Flow<List<ChatPreview>> =
        flowOf(emptyList()) // 会话数据源：待按后端会话表接入

    override fun observePeers(): Flow<List<MeshPeer>> =
        flowOf(emptyList()) // 节点数据源：待按邻居表接入

    override fun startDiscovery() {
        service.start()
    }

    override fun observeMessages(convId: String): Flow<List<ChatMessage>> =
        store.observeMessages(convId).map { list -> list.map { it.toUiModel() } }

    override fun sendText(convId: String, text: String) {
        val dstId = convId.substringAfterLast("-").takeIf { it != "ME" } ?: "ME"
        service.sendText(convId, dstId, text)
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
