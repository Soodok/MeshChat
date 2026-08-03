package com.meshchat.app.mesh.storage

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** 服务层 JVM 测试用内存存储实现。 */
class InMemoryMeshStore : MeshStore {
    private val messages = mutableListOf<StoredMessage>()
    private val outbox = mutableListOf<OutboxEntry>()

    override fun insertMessage(message: StoredMessage) {
        messages.removeAll { it.id == message.id }
        messages.add(message)
    }

    override fun updateMessageStatus(id: String, status: MessageStatus) {
        val index = messages.indexOfFirst { it.id == id }
        if (index >= 0) messages[index] = messages[index].copy(status = status)
    }

    override fun updateFileMeta(id: String, fileMeta: String?) {
        val index = messages.indexOfFirst { it.id == id }
        if (index >= 0) messages[index] = messages[index].copy(fileMeta = fileMeta)
    }

    override fun queryMessages(convId: String): List<StoredMessage> =
        messages.filter { it.convId == convId }.sortedBy { it.ts }

    override fun observeMessages(convId: String): Flow<List<StoredMessage>> =
        flowOf(queryMessages(convId))

    override fun enqueueOutbox(entry: OutboxEntry) {
        outbox.removeAll { it.id == entry.id }
        outbox.add(entry)
    }

    override fun nextOutbox(now: Long): List<OutboxEntry> =
        outbox.filter { it.expireAt > now }

    override fun removeOutbox(id: String) {
        outbox.removeAll { it.id == id }
    }
}
