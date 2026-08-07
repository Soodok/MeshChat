package com.meshchat.app.mesh.protocol

/**
 * Air-payload security contract (v1.1.57).
 *
 * 点对点 TEXT 端到端加密（ECDH P-256 + HKDF-SHA256 + AES-256-GCM）+ 群聊对称加密（群密钥 AES-GCM）：
 * 消息体密文化，路由字段（dstId/ttl/convId/kind）明文供中继转发。握手（INVITE/INVITE_ACK）携带公钥
 * 派生会话密钥；强制加密——无密钥（老版本）不发明文。明文 TEXT（老版本对端）接收时保留显示过渡。
 */
object MeshTransportSecurity {
    const val CURRENT_MODE: String = "aes-gcm-v1"
    const val END_TO_END_ENCRYPTION_ENFORCED: Boolean = true
}
