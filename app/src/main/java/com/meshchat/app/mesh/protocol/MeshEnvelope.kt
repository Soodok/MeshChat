package com.meshchat.app.mesh.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
sealed interface EnvelopeBody

@Serializable
@SerialName("TEXT")
data class TextBody(
    val text: String,
    val replyTo: String? = null,
    val displayName: String = "",
    /** v1.1.57 E2EE：握手（INVITE/INVITE_ACK）携带本机 ECDH P-256 公钥（SPKI Base64）；空 = 老版本不支持加密。 */
    val pubKey: String = "",
) : EnvelopeBody

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

/**
 * 文件传输 v2（v1.1.27，仅文件传输）：短字段名 + 多块合并，突破 BLE 吞吐瓶颈。
 * 每帧携带多块（start..start+chunks.size-1），把 JSON 信封头摊薄到多块上；
 * 老版本 decode 时 @SerialName("FILE2") 不在多态类型表 → 反序列化失败 → 帧被丢弃（双方需同时升级，消息/心跳零影响）。
 */
@Serializable
@SerialName("FILE2")
data class FileBodyV2(
    val fid: String,           // fileId（完整 UUID，接收去重/落库关联）
    val n: String,             // fileName（发送方已截断 ≤16）
    val m: String,             // mime（发送方已截断 ≤30）
    val sz: Long,              // size
    val tot: Int,              // totalChunks
    val start: Int,            // 本帧首块索引
    val chunks: List<String>,  // 本帧块数据（base64，1~CHUNKS_PER_FRAME 个）
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
data class PresenceBody(
    val displayName: String,
    /** 心跳确认携带：本机已收到的对端消息 id 列表（送达确认随 PING/PONG 往返，复用已验证通畅的心跳通道，不依赖丢帧的独立回执广播）。 */
    val ackIds: List<String> = emptyList(),
    /** 中继路由（v1.1.0）：本机一跳邻居 shortId 列表，随 PING 每 3 次携带一次（3s），对端据此学习 2 跳路由。 */
    val relays: List<String> = emptyList(),
    /** 心跳序列号（v1.1.16）：每次 PING 递增，接收端按缺口统计收包成功率/丢包率（协议层信号强度，不依赖系统 RSSI）；0 = 老版本未携带。 */
    val seq: Int = 0,
) : EnvelopeBody

@Serializable
@SerialName("GROUP")
data class GroupBody(
    val op: String,                 // "MSG"（消息）/ "JOIN"（创建传播群名，MVP）；"LEAVE" 预留
    val groupId: String,            // 群唯一 ID（8 字符，创建者生成）
    val msgId: String = "",         // 逻辑消息 ID（= 首次发送的 envelope.id；重发新 envelope 时不变，回执按此匹配）
    val groupName: String? = null,  // 群名（创建时携带，随消息传播学习）
    val text: String? = null,
    val displayName: String = "",   // 发送者昵称（群聊区分谁说的，同 TextBody）
    /** v1.1.57 群聊对称加密：创建者生成的 32B 群密钥（Base64），随 JOIN/首条消息传播给成员；防被动监听。 */
    val groupKey: String = "",
) : EnvelopeBody

/**
 * v1.1.57 传输加密 body：内层为原 TextBody/GroupBody 的 JSON 密文（AES-256-GCM）。
 * 路由字段（dstId/ttl/convId/kind）留在信封明文——中继零改动只转发不解密。
 * ctx = "p2p"（点对点会话密钥）或 "group-<groupId>"（群密钥）。
 */
@Serializable
@SerialName("SEC")
data class SecBody(
    val cipher: String,   // 内层 body JSON 的 AES-GCM 密文（Base64）
    val iv: String,       // 12B IV（Base64）
    val ctx: String,      // "p2p" / "group-<groupId>"
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
