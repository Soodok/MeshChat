package com.meshchat.app.mesh.storage

import android.content.Context
import androidx.room.Database
import androidx.room.migration.Migration
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

@Database(
    entities = [MessageEntity::class, OutboxEntity::class, PeerEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class MeshDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun outboxDao(): OutboxDao
    abstract fun peerDao(): PeerDao

    companion object {
        /** v1 → v2 无损迁移（移植队友 v1.0.12）：仅加查询索引，不动表结构，历史数据完整保留。 */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_convId_ts ON messages (convId, ts)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_status_kind ON messages (status, kind)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_outbox_expireAt ON outbox (expireAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_peers_lastSeen ON peers (lastSeen)")
            }
        }

        fun build(context: Context): MeshDatabase =
            Room.databaseBuilder(context, MeshDatabase::class.java, "meshchat.db")
                .addMigrations(MIGRATION_1_2)
                .build()
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

    override fun observeAllMessages(): Flow<List<StoredMessage>> =
        db.messageDao().observeAll().map { list -> list.map { it.toDomain() } }

    override fun deleteConversation(convId: String) = runBlocking {
        db.messageDao().deleteConversation(convId)
    }

    override fun enqueueOutbox(entry: OutboxEntry) = runBlocking {
        db.outboxDao().insert(entry.toEntity())
    }

    override fun nextOutbox(now: Long): List<OutboxEntry> = runBlocking {
        db.outboxDao().next(now).map { it.toDomain() }
    }

    override fun removeOutbox(id: String) = runBlocking {
        db.outboxDao().remove(id)
    }

    override fun pruneExpiredOutbox(now: Long) = runBlocking {
        db.outboxDao().removeExpired(now)
    }

    override fun upsertPeer(shortId: String, displayName: String, lastSeen: Long, hops: Int) = runBlocking {
        db.peerDao().upsert(PeerEntity(shortId = shortId, displayName = displayName, lastSeen = lastSeen, hops = hops))
    }

    override fun deletePeer(shortId: String) = runBlocking {
        db.peerDao().remove(shortId)
    }

    override fun prunePeersNotSeenSince(cutoff: Long) = runBlocking {
        db.peerDao().removeNotSeenSince(cutoff)
    }

    override fun loadPeers(): List<PeerEntity> = runBlocking {
        db.peerDao().observeAll().first()
    }

    override fun loadUndeliveredTexts(): List<StoredMessage> = runBlocking {
        db.messageDao().undeliveredTexts().map { it.toDomain() }
    }

    override fun loadKnownPeerIds(): List<String> = runBlocking {
        db.messageDao().knownConvIds().map { it.substringAfterLast("-") }.filter { it != "ME" }
    }

    override fun observeConversationIds(): Flow<List<String>> = db.messageDao().knownConvIdsFlow()

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
