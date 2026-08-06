package com.meshchat.app.mesh.storage

import com.meshchat.app.mesh.protocol.MeshEnvelope
import kotlinx.coroutines.flow.Flow

enum class MessageStatus { SENDING, DELIVERED, FAILED }

data class StoredMessage(
    val id: String,
    val convId: String,
    val kind: String,
    val srcId: String,
    val dstId: String,
    val text: String? = null,
    val fileMeta: String? = null,
    val status: MessageStatus = MessageStatus.SENDING,
    val ts: Long,
)

data class OutboxEntry(
    val id: String,
    val envelopeJson: String,
    val nextHop: String?,
    val attempts: Int = 0,
    val expireAt: Long,
)

interface MeshStore {
    fun insertMessage(message: StoredMessage)
    fun updateMessageStatus(id: String, status: MessageStatus)
    fun updateFileMeta(id: String, fileMeta: String?)
    fun queryMessages(convId: String): List<StoredMessage>
    fun observeMessages(convId: String): Flow<List<StoredMessage>>
    fun observeAllMessages(): Flow<List<StoredMessage>>
    fun deleteConversation(convId: String)
    fun enqueueOutbox(entry: OutboxEntry)
    fun nextOutbox(now: Long): List<OutboxEntry>
    fun removeOutbox(id: String)
    /** 删除已过期（expireAt ≤ now）的投递记录（缓存维护，不删聊天记录）。 */
    fun pruneExpiredOutbox(now: Long)
    fun upsertPeer(shortId: String, displayName: String, lastSeen: Long, hops: Int)
    /** 删除指定节点缓存（删除对话时同步遗忘该节点，不再从持久化恢复）。 */
    fun deletePeer(shortId: String)
    /** 删除 lastSeen 早于 cutoff 的节点缓存（缓存维护，不删聊天记录/已存文件）。 */
    fun prunePeersNotSeenSince(cutoff: Long)
    fun loadPeers(): List<PeerEntity>
    /** 进程重启后恢复未确认（SENDING）的 TEXT，重建重发队列。 */
    fun loadUndeliveredTexts(): List<StoredMessage>
    /** 进程重启后恢复未确认（SENDING）的群消息（v1.1.50），重建群重发队列。 */
    fun loadUndeliveredGroups(): List<StoredMessage>
    /** 从消息历史反推已知对端短 ID（peers 表为空时的兜底）。 */
    fun loadKnownPeerIds(): List<String>
    /** 从消息历史反推的对话 convId 列表（最近对话持久化兜底；Room 流式响应，消息变化自动更新）。 */
    fun observeConversationIds(): Flow<List<String>>
}
