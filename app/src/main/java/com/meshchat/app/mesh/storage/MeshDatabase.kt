package com.meshchat.app.mesh.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

@Database(
    entities = [MessageEntity::class, OutboxEntity::class, PeerEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class MeshDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun outboxDao(): OutboxDao
    abstract fun peerDao(): PeerDao

    companion object {
        fun build(context: Context): MeshDatabase =
            Room.databaseBuilder(context, MeshDatabase::class.java, "meshchat.db").build()
    }
}

class RoomMeshStore(private val db: MeshDatabase) : MeshStore {
    override fun insertMessage(message: StoredMessage) = runBlocking {
        db.messageDao().upsert(message.toEntity())
    }

    override fun updateMessageStatus(id: String, status: MessageStatus) = runBlocking {
        db.messageDao().updateStatus(id, status.name)
    }

    override fun updateFileMeta(id: String, fileMeta: String?) = runBlocking {
        db.messageDao().updateFileMeta(id, fileMeta)
    }

    override fun queryMessages(convId: String): List<StoredMessage> = runBlocking {
        db.messageDao().observeByConv(convId).first().map { it.toDomain() }
    }

    override fun observeMessages(convId: String): Flow<List<StoredMessage>> =
        db.messageDao().observeByConv(convId).map { list -> list.map { it.toDomain() } }

    override fun enqueueOutbox(entry: OutboxEntry) = runBlocking {
        db.outboxDao().insert(entry.toEntity())
    }

    override fun nextOutbox(now: Long): List<OutboxEntry> = runBlocking {
        db.outboxDao().next(now).map { it.toDomain() }
    }

    override fun removeOutbox(id: String) = runBlocking {
        db.outboxDao().remove(id)
    }

    override fun upsertPeer(shortId: String, displayName: String, lastSeen: Long, hops: Int) = runBlocking {
        db.peerDao().upsert(PeerEntity(shortId = shortId, displayName = displayName, lastSeen = lastSeen, hops = hops))
    }

    override fun loadPeers(): List<PeerEntity> = runBlocking {
        db.peerDao().observeAll().first()
    }

    override fun loadUndeliveredTexts(): List<StoredMessage> = runBlocking {
        db.messageDao().undeliveredTexts().map { it.toDomain() }
    }

    fun observePeers(): Flow<List<PeerEntity>> = db.peerDao().observeAll()

    private fun StoredMessage.toEntity() = MessageEntity(
        id = id, convId = convId, kind = kind, srcId = srcId, dstId = dstId,
        text = text, fileMeta = fileMeta, status = status.name, ts = ts,
    )

    private fun OutboxEntry.toEntity() = OutboxEntity(
        id = id, envelopeJson = envelopeJson, nextHop = nextHop,
        attempts = attempts, expireAt = expireAt,
    )

    private fun MessageEntity.toDomain() = StoredMessage(
        id = id, convId = convId, kind = kind, srcId = srcId, dstId = dstId,
        text = text, fileMeta = fileMeta,
        status = runCatching { MessageStatus.valueOf(status) }.getOrDefault(MessageStatus.SENDING),
        ts = ts,
    )

    private fun OutboxEntity.toDomain() = OutboxEntry(
        id = id, envelopeJson = envelopeJson, nextHop = nextHop,
        attempts = attempts, expireAt = expireAt,
    )
}
