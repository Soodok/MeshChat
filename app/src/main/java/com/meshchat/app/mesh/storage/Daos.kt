package com.meshchat.app.mesh.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MessageEntity)

    @Query("UPDATE messages SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String)

    @Query("UPDATE messages SET fileMeta = :fileMeta WHERE id = :id")
    suspend fun updateFileMeta(id: String, fileMeta: String?)

    @Query("SELECT * FROM messages WHERE convId = :convId ORDER BY ts ASC")
    fun observeByConv(convId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE status = 'SENDING' AND kind = 'TEXT'")
    suspend fun undeliveredTexts(): List<MessageEntity>

    @Query("SELECT DISTINCT convId FROM messages")
    suspend fun knownConvIds(): List<String>

    @Query("SELECT DISTINCT convId FROM messages")
    fun knownConvIdsFlow(): Flow<List<String>>
}

@Dao
interface OutboxDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: OutboxEntity)

    @Query("SELECT * FROM outbox WHERE expireAt > :now ORDER BY attempts ASC LIMIT 20")
    suspend fun next(now: Long): List<OutboxEntity>

    @Query("DELETE FROM outbox WHERE id = :id")
    suspend fun remove(id: String)

    @Query("DELETE FROM outbox WHERE expireAt <= :now")
    suspend fun removeExpired(now: Long)
}

@Dao
interface PeerDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PeerEntity)

    @Query("DELETE FROM peers WHERE shortId = :id")
    suspend fun remove(id: String)

    @Query("SELECT * FROM peers")
    fun observeAll(): Flow<List<PeerEntity>>

    @Query("DELETE FROM peers WHERE lastSeen < :cutoff")
    suspend fun removeNotSeenSince(cutoff: Long)
}
