package com.meshchat.app.mesh.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.util.Base64

/**
 * v1.1.57 生产 E2EE 密钥存储：
 * - 本机 ECDH P-256 私钥存 **AndroidKeyStore**（不可导出，仅用于密钥协商）
 * - 对端会话密钥 / 群密钥存 SharedPrefs（Base64；已协商设备本地信任假设，见规格 §9）
 */
class AndroidE2eeKeyStore(context: Context) : E2eeKeyStore {
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

    override fun sessionKey(peerId: String): ByteArray? =
        prefs.getString("sk_$peerId", null)?.let { Base64.getDecoder().decode(it) }

    override fun saveSessionKey(peerId: String, key: ByteArray) {
        prefs.edit().putString("sk_$peerId", Base64.getEncoder().encodeToString(key)).apply()
    }

    override fun groupKey(groupId: String): ByteArray? =
        prefs.getString("gk_$groupId", null)?.let { Base64.getDecoder().decode(it) }

    override fun saveGroupKey(groupId: String, key: ByteArray) {
        prefs.edit().putString("gk_$groupId", Base64.getEncoder().encodeToString(key)).apply()
    }
}
