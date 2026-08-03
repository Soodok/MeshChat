package com.meshchat.app.mesh.storage

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "messages")
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

@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey val id: String,
    val envelopeJson: String,
    val nextHop: String?,
    val attempts: Int,
    val expireAt: Long,
)

@Entity(tableName = "peers")
data class PeerEntity(
    @PrimaryKey val shortId: String,
    val displayName: String,
    val lastSeen: Long,
    val hops: Int,
)
