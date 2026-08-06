package com.meshchat.app.mesh.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 消息落库加密核心（v1.1.24）：AES-256-GCM，每次写加密用全新 IV。
 *
 * 格式：`[版本字节][12B IV][ciphertext]`。
 * - 老明文兼容：非本格式（首字节非版本号）按原文透传——已有明文消息库不迁移可读，新写入全部加密（渐进迁移）。
 * - 损坏/密钥不可用兜底：解密失败返回原文（不崩读库；安全语义宁可乱码也不伪加密掩盖）。
 * 纯 JVM 可测（注入任意 SecretKey）；生产密钥由 Android Keystore 保管（不可导出）。
 */
internal object MessageCrypto {
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val FORMAT_VERSION: Byte = 1
    private const val GCM_IV_BYTES = 12
    private const val GCM_TAG_BITS = 128
    const val KEY_ALIAS = "meshchat.messages.v1"

    fun encrypt(key: SecretKey, plainText: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
        val cipherText = cipher.doFinal(plainText)
        return byteArrayOf(FORMAT_VERSION) + cipher.iv + cipherText
    }

    fun decrypt(key: SecretKey, payload: ByteArray): ByteArray {
        if (payload.isEmpty() || payload.size <= 1 + GCM_IV_BYTES || payload[0] != FORMAT_VERSION) {
            return payload // 老明文 / 空值透传
        }
        return runCatching {
            val iv = payload.copyOfRange(1, 1 + GCM_IV_BYTES)
            val cipherText = payload.copyOfRange(1 + GCM_IV_BYTES, payload.size)
            Cipher.getInstance(TRANSFORMATION).run {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
                doFinal(cipherText)
            }
        }.getOrElse { payload }
    }
}

/**
 * 消息落库加密装饰器：对敏感字段（消息正文 / 文件元数据 / outbox 信封副本）落库前 AES-GCM 加密，
 * 读取时解密。Room schema 与协议完全不动，InMemoryMeshStore（测试）不加密。
 *
 * 解密失败/老明文一律透传原值，保证升级后老消息库可读、损坏数据不崩读库。
 */
class EncryptedMeshStore(
    private val delegate: MeshStore,
    context: Context,
) : MeshStore {

    /** 应用级 AES-256 密钥（Android Keystore，不可导出；换机/重装后旧密文不可解密——与安全事件存储同语义）。 */
    private val key: SecretKey by lazy {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(MessageCrypto.KEY_ALIAS, null) as? SecretKey) ?: run {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
                init(
                    KeyGenParameterSpec.Builder(
                        MessageCrypto.KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setKeySize(256)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build(),
                )
            }.generateKey()
        }
    }

    override fun insertMessage(message: StoredMessage) =
        delegate.insertMessage(message.encryptSensitive())

    override fun updateMessageStatus(id: String, status: MessageStatus) =
        delegate.updateMessageStatus(id, status)

    override fun updateFileMeta(id: String, fileMeta: String?) =
        delegate.updateFileMeta(id, fileMeta?.let { encryptText(it) })

    override fun queryMessages(convId: String): List<StoredMessage> =
        delegate.queryMessages(convId).map { it.decryptSensitive() }

    override fun observeMessages(convId: String): Flow<List<StoredMessage>> =
        delegate.observeMessages(convId).map { list -> list.map { it.decryptSensitive() } }

    override fun observeAllMessages(): Flow<List<StoredMessage>> =
        delegate.observeAllMessages().map { list -> list.map { it.decryptSensitive() } }

    override fun deleteConversation(convId: String) = delegate.deleteConversation(convId)

    override fun enqueueOutbox(entry: OutboxEntry) =
        delegate.enqueueOutbox(entry.copy(envelopeJson = encryptText(entry.envelopeJson)))

    override fun nextOutbox(now: Long): List<OutboxEntry> =
        delegate.nextOutbox(now).map { it.copy(envelopeJson = decryptText(it.envelopeJson)) }

    override fun removeOutbox(id: String) = delegate.removeOutbox(id)

    override fun pruneExpiredOutbox(now: Long) = delegate.pruneExpiredOutbox(now)

    override fun upsertPeer(shortId: String, displayName: String, lastSeen: Long, hops: Int) =
        delegate.upsertPeer(shortId, displayName, lastSeen, hops)

    override fun deletePeer(shortId: String) = delegate.deletePeer(shortId)

    override fun prunePeersNotSeenSince(cutoff: Long) = delegate.prunePeersNotSeenSince(cutoff)

    override fun loadPeers(): List<PeerEntity> = delegate.loadPeers()

    override fun loadUndeliveredTexts(): List<StoredMessage> =
        delegate.loadUndeliveredTexts().map { it.decryptSensitive() }

    override fun loadUndeliveredGroups(): List<StoredMessage> =
        delegate.loadUndeliveredGroups().map { it.decryptSensitive() }

    override fun loadKnownPeerIds(): List<String> = delegate.loadKnownPeerIds()

    override fun observeConversationIds(): Flow<List<String>> = delegate.observeConversationIds()

    // ===== 加解密辅助 =====

    private fun StoredMessage.encryptSensitive(): StoredMessage =
        copy(text = text?.let { encryptText(it) }, fileMeta = fileMeta?.let { encryptText(it) })

    private fun StoredMessage.decryptSensitive(): StoredMessage =
        copy(text = text?.let { decryptText(it) }, fileMeta = fileMeta?.let { decryptText(it) })

    /** 明文 → Base64(AES-GCM 密文)：Room TEXT 列无法直接存二进制。 */
    private fun encryptText(plain: String): String =
        Base64.encodeToString(
            MessageCrypto.encrypt(key, plain.encodeToByteArray()),
            Base64.NO_WRAP,
        )

    /** Base64 密文 → 明文；非 Base64（老明文）或解密失败透传原值。 */
    private fun decryptText(payload: String): String {
        val bytes = runCatching { Base64.decode(payload, Base64.NO_WRAP) }
            .getOrElse { payload.encodeToByteArray() }
        return MessageCrypto.decrypt(key, bytes).decodeToString()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}
