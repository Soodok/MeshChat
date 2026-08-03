package com.meshchat.app.data

import com.meshchat.app.mesh.service.MeshService
import com.meshchat.app.mesh.storage.MessageStatus
import com.meshchat.app.mesh.storage.MeshStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

interface MeshRepository {
    fun observeMessages(convId: String): Flow<List<ChatMessage>>
    fun sendText(convId: String, text: String)
}

class MeshRepositoryImpl(
    private val service: MeshService,
    private val store: MeshStore,
) : MeshRepository {

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
