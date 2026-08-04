package com.meshchat.app.mesh.storage

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// ⚠️ 索引声明必须与 MeshDatabase.MIGRATION_1_2 的 CREATE INDEX 完全一致：
// Room 迁移后按 @Entity 推导的期望 schema 校验迁移结果，缺一即抛 "Migration didn't properly handle" 启动崩溃
@Entity(
    tableName = "messages",
    indices = [Index(value = ["convId", "ts"]), Index(value = ["status", "kind"])],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val convId: String,
    val kind: String,
    val srcId: String,
    val dstId: String,
    val text: String?,
    val fileMeta: String?,
    val status: String,
    val ts: Long,
)

@Entity(tableName = "outbox", indices = [Index(value = ["expireAt"])])
data class OutboxEntity(
    @PrimaryKey val id: String,
    val envelopeJson: String,
    val nextHop: String?,
    val attempts: Int,
    val expireAt: Long,
)

@Entity(tableName = "peers", indices = [Index(value = ["lastSeen"])])
data class PeerEntity(
    @PrimaryKey val shortId: String,
    val displayName: String,
    val lastSeen: Long,
    val hops: Int,
)
