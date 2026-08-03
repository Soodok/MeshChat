package com.meshchat.app.mesh.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
sealed interface EnvelopeBody

@Serializable
@SerialName("TEXT")
data class TextBody(val text: String, val replyTo: String? = null) : EnvelopeBody

@Serializable
@SerialName("FILE")
data class FileBody(
    val fileId: String,        // 关联同一文件所有分块（= 首块信封 id）
    val fileName: String,
    val mime: String,
    val size: Long,
    val totalChunks: Int,
    val chunkIndex: Int,
    val chunkData: String,     // base64，每块原始 200B → ~268B
) : EnvelopeBody

@Serializable
@SerialName("FILE_ACK")
data class FileAckBody(
    val fileId: String,
    val totalChunks: Int,
    val missing: List<Int>,   // 缺失块索引；空 = 全部收齐
) : EnvelopeBody

@Serializable
@SerialName("PING")
data class PresenceBody(val displayName: String) : EnvelopeBody

@Serializable
@SerialName("GROUP")
data class GroupBody(
    val op: String,           // JOIN | LEAVE | MSG
    val groupName: String? = null,
    val text: String? = null,
) : EnvelopeBody

@Serializable
data class MeshEnvelope(
    val id: String,
    val kind: String,
    val srcId: String,
    val dstId: String,
    val convId: String,
    val ttl: Int = 8,
    val ts: Long,
    val enc: String = "none",
    val body: EnvelopeBody,
)

object MeshJson {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encodeEnvelope(envelope: MeshEnvelope): String =
        json.encodeToString(MeshEnvelope.serializer(), envelope)

    fun decodeEnvelope(text: String): MeshEnvelope =
        json.decodeFromString(MeshEnvelope.serializer(), text)
}
