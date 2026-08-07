package com.meshchat.app.security.lock

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * v1.1.58 应用锁加密内核（纯 JVM，标准 JCA）：
 * 密码 → PBKDF2-HMAC-SHA256（OWASP 推荐 210k 迭代）→ KEK → AES-256-GCM 加密 DEK（32B 随机数据密钥）。
 * DEK 用于加密敏感密钥库（E2EE 会话密钥/群密钥）——设密码后密钥库密文存储，无密码不可读。
 */
object LockCrypto {
    const val PBKDF2_ALG = "PBKDF2WithHmacSHA256"
    const val TRANSFORMATION = "AES/GCM/NoPadding"
    const val GCM_TAG_BITS = 128
    const val GCM_IV_BYTES = 12
    const val SALT_BYTES = 16
    const val DEK_BYTES = 32
    const val ITERATIONS = 210_000

    /** 密码 → KEK（32B）。 */
    fun deriveKek(password: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, DEK_BYTES * 8)
        return SecretKeyFactory.getInstance(PBKDF2_ALG).generateSecret(spec).encoded
    }

    /** 随机 DEK（32B）。 */
    fun randomDek(): ByteArray = ByteArray(DEK_BYTES).also { SecureRandom().nextBytes(it) }

    /** KEK 加密 DEK（返回 Base64：iv + ciphertext）。 */
    fun encryptDek(kek: ByteArray, dek: ByteArray): String {
        val iv = ByteArray(GCM_IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(kek, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        val ct = cipher.doFinal(dek)
        return Base64.getEncoder().encodeToString(iv + ct)
    }

    /** 用 KEK 解密 DEK blob；密码错误/篡改 → null（GCM 认证兜底）。 */
    fun decryptDek(kek: ByteArray, blobB64: String): ByteArray? = runCatching {
        val raw = Base64.getDecoder().decode(blobB64)
        val iv = raw.copyOfRange(0, GCM_IV_BYTES)
        val ct = raw.copyOfRange(GCM_IV_BYTES, raw.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(kek, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.doFinal(ct)
    }.getOrNull()

    /** DEK 加密任意字节（密钥库条目）。 */
    fun encryptWithDek(dek: ByteArray, plain: ByteArray): String {
        val iv = ByteArray(GCM_IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(dek, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        val ct = cipher.doFinal(plain)
        return Base64.getEncoder().encodeToString(iv + ct)
    }

    /** DEK 解密任意密文；失败返回 null。 */
    fun decryptWithDek(dek: ByteArray, blobB64: String): ByteArray? = runCatching {
        val raw = Base64.getDecoder().decode(blobB64)
        val iv = raw.copyOfRange(0, GCM_IV_BYTES)
        val ct = raw.copyOfRange(GCM_IV_BYTES, raw.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(dek, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.doFinal(ct)
    }.getOrNull()
}
