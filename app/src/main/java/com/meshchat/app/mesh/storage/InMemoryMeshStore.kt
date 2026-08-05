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

    override fun observeAllMessages(): Flow<List<StoredMessage>> = flowOf(messages.sortedBy { it.ts })

    override fun deleteConversation(convId: String) {
        messages.removeAll { it.convId == convId }
    }

    override fun enqueueOutbox(entry: OutboxEntry) {
        outbox.removeAll { it.id == entry.id }
        outbox.add(entry)
    }

    override fun nextOutbox(now: Long): List<OutboxEntry> =
        outbox.filter { it.expireAt > now }

    override fun removeOutbox(id: String) {
        outbox.removeAll { it.id == id }
    }

    override fun pruneExpiredOutbox(now: Long) {
        outbox.removeAll { it.expireAt <= now }
    }

    private val peers = mutableMapOf<String, PeerEntity>()

    override fun upsertPeer(shortId: String, displayName: String, lastSeen: Long, hops: Int) {
        peers[shortId] = PeerEntity(shortId, displayName, lastSeen, hops)
    }

    override fun deletePeer(shortId: String) {
        peers.remove(shortId)
    }

    override fun prunePeersNotSeenSince(cutoff: Long) {
        peers.entries.removeAll { it.value.lastSeen < cutoff }
    }

    override fun loadPeers(): List<PeerEntity> = peers.values.toList()

    override fun loadUndeliveredTexts(): List<StoredMessage> =
        messages.filter { it.kind == "TEXT" && it.status == MessageStatus.SENDING }

    override fun loadKnownPeerIds(): List<String> =
        messages.map { it.convId.substringAfterLast("-") }.distinct().filter { it != "ME" }

    override fun observeConversationIds(): Flow<List<String>> =
        flowOf(messages.map { it.convId }.distinct())

    fun observePeers(): Flow<List<PeerEntity>> = flowOf(peers.values.toList())
}
