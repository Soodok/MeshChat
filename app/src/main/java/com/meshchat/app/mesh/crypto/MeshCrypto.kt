package com.meshchat.app.mesh.crypto

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * v1.1.57 端到端加密内核（纯 JVM，标准 JCA）：
 * ECDH P-256 密钥协商 + HKDF-SHA256 派生 + AES-256-GCM（每条消息新 IV + 认证标签）。
 * 空中传输的消息体全部密文化，路由字段（dstId/ttl/convId）留在信封明文供中继转发。
 */
object MeshCrypto {
    const val KEY_ALG = "EC"
    const val CURVE = "secp256r1"                 // P-256
    const val KEY_AGREEMENT = "ECDH"
    const val HMAC_ALG = "HmacSHA256"
    const val TRANSFORMATION = "AES/GCM/NoPadding"
    const val GCM_TAG_BITS = 128
    const val IV_BYTES = 12
    const val KEY_BYTES = 32                      // AES-256
    /** HKDF 盐：固定常量（非秘密，派生绑定协议上下文即可）。 */
    private val HKDF_SALT = "meshchat-hkdf-salt-v1".encodeToByteArray()

    /** 生成 ECDH P-256 密钥对。 */
    fun generateKeyPair(): KeyPair =
        KeyPairGenerator.getInstance(KEY_ALG).apply { initialize(java.security.spec.ECGenParameterSpec(CURVE)) }
            .generateKeyPair()

    /** 公钥 → SPKI X.509 Base64（握手交换）。 */
    fun publicKeyB64(keyPair: KeyPair): String =
        Base64.getEncoder().encodeToString(keyPair.public.encoded)

    /** SPKI Base64 → PublicKey。 */
    fun publicKeyFromB64(b64: String): PublicKey =
        KeyFactory.getInstance(KEY_ALG).generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(b64)))

    /** ECDH 共享秘密（32B，X 坐标）。 */
    fun sharedSecret(privateKey: PrivateKey, peerPublic: PublicKey): ByteArray =
        KeyAgreement.getInstance(KEY_AGREEMENT).run {
            init(privateKey); doPhase(peerPublic, true); generateSecret()
        }

    /**
     * HKDF-SHA256（RFC 5869）：固定盐 extract + info 绑定 expand，输出 32B 会话密钥。
     * info 绑定收发双方短 ID → 双方推导出相同密钥且与其他对端隔离。
     */
    fun deriveSessionKey(sharedSecret: ByteArray, info: String): ByteArray = hkdfSha256(sharedSecret, info)

    /** 生成随机 32B 群密钥。 */
    fun randomGroupKey(): ByteArray = ByteArray(KEY_BYTES).also { java.security.SecureRandom().nextBytes(it) }

    private fun hkdfSha256(ikm: ByteArray, info: String, length: Int = KEY_BYTES): ByteArray {
        // Extract
        val mac = Mac.getInstance(HMAC_ALG)
        mac.init(SecretKeySpec(HKDF_SALT, HMAC_ALG))
        val prk = mac.doFinal(ikm)
        // Expand
        val hmac = Mac.getInstance(HMAC_ALG)
        hmac.init(SecretKeySpec(prk, HMAC_ALG))
        val infoBytes = info.encodeToByteArray()
        val out = ByteArray(length)
        var offset = 0
        var t = ByteArray(0)
        var counter = 1
        while (offset < length) {
            hmac.reset()
            hmac.update(t)
            hmac.update(infoBytes)
            hmac.update(counter.toByte())
            t = hmac.doFinal()
            val n = minOf(t.size, length - offset)
            t.copyInto(out, offset, 0, n)
            offset += n
            counter++
        }
        return out
    }

    /** 加密结果（Base64 编码，便于 JSON 传输）。 */
    data class Encrypted(val iv: String, val cipher: String)

    /** AES-256-GCM 加密（新随机 IV + AAD 绑定上下文）。 */
    fun encrypt(key: ByteArray, plain: ByteArray, aad: String): Encrypted {
        val iv = ByteArray(IV_BYTES).also { java.security.SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        if (aad.isNotEmpty()) cipher.updateAAD(aad.encodeToByteArray())
        val ct = cipher.doFinal(plain)
        return Encrypted(
            iv = Base64.getEncoder().encodeToString(iv),
            cipher = Base64.getEncoder().encodeToString(ct),
        )
    }

    /** AES-256-GCM 解密；失败（密钥错/被篡改/IV 错）返回 null（GCM 认证标签兜底）。 */
    fun decrypt(key: ByteArray, ivB64: String, cipherB64: String, aad: String): ByteArray? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(GCM_TAG_BITS, Base64.getDecoder().decode(ivB64)),
        )
        if (aad.isNotEmpty()) cipher.updateAAD(aad.encodeToByteArray())
        cipher.doFinal(Base64.getDecoder().decode(cipherB64))
    }.getOrNull()

    /** 公钥 SHA-256 指纹（前 8 字节 hex）——UI 安全标识/调试。 */
    fun fingerprint(pubB64: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(Base64.getDecoder().decode(pubB64))
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }
}
