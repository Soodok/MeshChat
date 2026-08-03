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
    fun enqueueOutbox(entry: OutboxEntry)
    fun nextOutbox(now: Long): List<OutboxEntry>
    fun removeOutbox(id: String)
    fun upsertPeer(shortId: String, displayName: String, lastSeen: Long, hops: Int)
}
