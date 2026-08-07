package com.meshchat.app.mesh.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.meshchat.app.security.lock.LockCrypto
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.util.Base64

/**
 * v1.1.57 生产 E2EE 密钥存储：
 * - 本机 ECDH P-256 私钥存 **AndroidKeyStore**（不可导出，仅用于密钥协商）
 * - 对端会话密钥 / 群密钥存 SharedPrefs；v1.1.58 起设密码后由应用锁 DEK 加密存储
 *   （dekProvider 返回非空 = 已解锁，读写走 AES-GCM；未设密码/未解锁 = 明文兼容迁移期）。
 */
class AndroidE2eeKeyStore(
    context: Context,
    private val dekProvider: () -> ByteArray?,
) : E2eeKeyStore {
    private val prefs = context.getSharedPreferences("meshchat_e2ee", Context.MODE_PRIVATE)
    private val keyStore: KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private val alias = "meshchat_e2ee_p256"

    override fun localKeyPair(): KeyPair {
        if (keyStore.containsAlias(alias)) {
            val entry = keyStore.getEntry(alias, null) as KeyStore.PrivateKeyEntry
            return KeyPair(entry.certificate.publicKey, entry.privateKey)
        }
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        generator.initialize(
            KeyGenParameterSpec.Builder(alias, 1 shl 4 /* KeyProperties.PURPOSE_AGREE，API 28+ 常量，数字值保 minSdk 26 编译 */)
                .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build(),
        )
        return generator.generateKeyPair()
    }

    override fun sessionKey(peerId: String): ByteArray? = read("sk_$peerId")

    override fun saveSessionKey(peerId: String, key: ByteArray) = write("sk_$peerId", key)

    override fun groupKey(groupId: String): ByteArray? = read("gk_$groupId")

    override fun saveGroupKey(groupId: String, key: ByteArray) = write("gk_$groupId", key)

    /** 读：设密码且已解锁 → 解密；老明文（未设密码时期写入）或解密失败 → 透传原值（兼容）。 */
    private fun read(key: String): ByteArray? {
        val raw = prefs.getString(key, null) ?: return null
        val dek = dekProvider() ?: return runCatching { Base64.getDecoder().decode(raw) }.getOrNull()
        return runCatching {
            val bytes = Base64.getDecoder().decode(raw)
            // 老明文条目不是 AES-GCM blob（前 12B 是 IV 且解密必失败）→ 解密失败回退原文
            val d = LockCrypto.decryptWithDek(dek, raw)
            if (d != null) d else bytes
        }.getOrNull()
    }

    /** 写：设密码且已解锁 → DEK 加密存储；否则明文（未设密码状态）。 */
    private fun write(key: String, value: ByteArray) {
        val dek = dekProvider()
        val out = if (dek != null) LockCrypto.encryptWithDek(dek, value)
        else Base64.getEncoder().encodeToString(value)
        prefs.edit().putString(key, out).apply()
    }
}
