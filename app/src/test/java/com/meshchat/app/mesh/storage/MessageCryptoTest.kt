package com.meshchat.app.mesh.storage

import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageCryptoTest {
    // JVM 可用的确定性 AES key（生产环境由 Android Keystore 生成，这里验证加解密逻辑本身）
    private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")

    @Test
    fun `encrypt then decrypt round trips`() {
        val plain = "你好，加密测试 123".encodeToByteArray()
        val encrypted = MessageCrypto.encrypt(key, plain)
        // 密文不可读（非明文原文）
        assertFalse(encrypted.decodeToString().contains("加密测试"))
        assertEquals(plain.toList(), MessageCrypto.decrypt(key, encrypted).toList())
    }

    @Test
    fun `legacy plaintext passes through unchanged`() {
        val legacy = "升级前的明文消息".encodeToByteArray()
        assertTrue(MessageCrypto.decrypt(key, legacy).contentEquals(legacy))
    }

    @Test
    fun `empty and too-short payloads pass through`() {
        assertTrue(MessageCrypto.decrypt(key, ByteArray(0)).isEmpty())
        val short = byteArrayOf(1, 2, 3, 4, 5) // 首字节是版本号但长度不足
        assertTrue(MessageCrypto.decrypt(key, short).contentEquals(short))
    }

    @Test
    fun `corrupt payload falls back to original`() {
        // 构造一段密文，篡改内容 → 解密失败应返回原文（不抛异常）
        val encrypted = MessageCrypto.encrypt(key, "重要内容".encodeToByteArray())
        val corrupted = encrypted.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0xFF).toByte() }
        assertTrue(MessageCrypto.decrypt(key, corrupted).contentEquals(corrupted))
    }

    @Test
    fun `same plaintext produces different ciphertext each time`() {
        val plain = "每次新 IV".encodeToByteArray()
        val a = MessageCrypto.encrypt(key, plain)
        val b = MessageCrypto.encrypt(key, plain)
        assertFalse(a.contentEquals(b))
    }
}
