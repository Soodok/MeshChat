package com.meshchat.app.security.lock

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.1.58 应用锁加密内核测试（纯 JVM）。 */
class LockCryptoTest {

    @Test
    fun `deriveKek is deterministic for same password and salt`() {
        val salt = ByteArray(LockCrypto.SALT_BYTES) { it.toByte() }
        val k1 = LockCrypto.deriveKek("password123", salt)
        val k2 = LockCrypto.deriveKek("password123", salt)
        assertArrayEquals(k1, k2)
        assertTrue(k1.size == LockCrypto.DEK_BYTES)
    }

    @Test
    fun `deriveKek differs for different passwords and salts`() {
        val salt1 = ByteArray(LockCrypto.SALT_BYTES) { it.toByte() }
        val salt2 = ByteArray(LockCrypto.SALT_BYTES) { (it + 1).toByte() }
        val k1 = LockCrypto.deriveKek("password123", salt1)
        val k2 = LockCrypto.deriveKek("password124", salt1)
        val k3 = LockCrypto.deriveKek("password123", salt2)
        assertFalse(k1.contentEquals(k2))
        assertFalse(k1.contentEquals(k3))
    }

    @Test
    fun `dek encryption round trip with correct password`() {
        val salt = ByteArray(LockCrypto.SALT_BYTES) { 7 }
        val kek = LockCrypto.deriveKek("my-pass", salt)
        val dek = LockCrypto.randomDek()
        assertTrue(dek.size == LockCrypto.DEK_BYTES)
        val blob = LockCrypto.encryptDek(kek, dek)
        val decrypted = LockCrypto.decryptDek(kek, blob)
        assertNotNull(decrypted)
        assertArrayEquals(dek, decrypted)
    }

    @Test
    fun `wrong password fails GCM authentication`() {
        val salt = ByteArray(LockCrypto.SALT_BYTES) { 7 }
        val kek = LockCrypto.deriveKek("right-pass", salt)
        val dek = LockCrypto.randomDek()
        val blob = LockCrypto.encryptDek(kek, dek)
        val wrongKek = LockCrypto.deriveKek("wrong-pass", salt)
        assertNull(LockCrypto.decryptDek(wrongKek, blob))
    }

    @Test
    fun `tampered dek blob fails to decrypt`() {
        val salt = ByteArray(LockCrypto.SALT_BYTES) { 7 }
        val kek = LockCrypto.deriveKek("pass", salt)
        val dek = LockCrypto.randomDek()
        val blob = LockCrypto.encryptDek(kek, dek)
        // 篡改密文中段（Base64 解码后翻转一个字节再编码）
        val raw = java.util.Base64.getDecoder().decode(blob)
        raw[raw.size - 1] = (raw[raw.size - 1].toInt() xor 0xFF).toByte()
        val tampered = java.util.Base64.getEncoder().encodeToString(raw)
        assertNull(LockCrypto.decryptDek(kek, tampered))
    }

    @Test
    fun `key store entry round trip with dek`() {
        val dek = LockCrypto.randomDek()
        val secret = "session-key-bytes-0123456789".encodeToByteArray()
        val blob = LockCrypto.encryptWithDek(dek, secret)
        val decrypted = LockCrypto.decryptWithDek(dek, blob)
        assertNotNull(decrypted)
        assertArrayEquals(secret, decrypted)
    }

    @Test
    fun `wrong dek cannot decrypt key store entry`() {
        val dek = LockCrypto.randomDek()
        val other = LockCrypto.randomDek()
        val blob = LockCrypto.encryptWithDek(dek, "secret".encodeToByteArray())
        assertNull(LockCrypto.decryptWithDek(other, blob))
    }

    @Test
    fun `each encryption uses fresh iv producing distinct blobs`() {
        val dek = LockCrypto.randomDek()
        val secret = "same-content".encodeToByteArray()
        val b1 = LockCrypto.encryptWithDek(dek, secret)
        val b2 = LockCrypto.encryptWithDek(dek, secret)
        assertFalse(b1 == b2)
        assertArrayEquals(secret, LockCrypto.decryptWithDek(dek, b1))
        assertArrayEquals(secret, LockCrypto.decryptWithDek(dek, b2))
    }
}
