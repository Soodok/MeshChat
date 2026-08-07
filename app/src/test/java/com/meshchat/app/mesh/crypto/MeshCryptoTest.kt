package com.meshchat.app.mesh.crypto

import java.security.SecureRandom
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshCryptoTest {

    @Test
    fun `two parties derive identical session key via ecdh and hkdf`() {
        val a = MeshCrypto.generateKeyPair()
        val b = MeshCrypto.generateKeyPair()
        val secretA = MeshCrypto.sharedSecret(a.private, b.public)
        val secretB = MeshCrypto.sharedSecret(b.private, a.public)
        assertArrayEquals("ECDH 共享秘密应一致", secretA, secretB)
        val keyA = MeshCrypto.deriveSessionKey(secretA, "meshchat-e2ee-v1|A|B")
        val keyB = MeshCrypto.deriveSessionKey(secretB, "meshchat-e2ee-v1|A|B")
        assertArrayEquals("HKDF 派生会话密钥应一致", keyA, keyB)
        assertEquals("会话密钥 32B（AES-256）", 32, keyA.size)
    }

    @Test
    fun `different peer info yields different session keys`() {
        val a = MeshCrypto.generateKeyPair()
        val b = MeshCrypto.generateKeyPair()
        val secret = MeshCrypto.sharedSecret(a.private, b.public)
        val k1 = MeshCrypto.deriveSessionKey(secret, "peer1")
        val k2 = MeshCrypto.deriveSessionKey(secret, "peer2")
        assertFalse("不同 info 应派生不同密钥", k1.contentEquals(k2))
    }

    @Test
    fun `aes gcm round trip with aad`() {
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val plain = "hello mesh e2ee".toByteArray()
        val e = MeshCrypto.encrypt(key, plain, "TEXT|B")
        assertTrue(e.cipher.isNotBlank())
        assertTrue(e.iv.isNotBlank())
        val dec = MeshCrypto.decrypt(key, e.iv, e.cipher, "TEXT|B")
        assertArrayEquals(plain, dec)
    }

    @Test
    fun `aes gcm rejects tampered ciphertext and wrong aad`() {
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val e = MeshCrypto.encrypt(key, "secret".toByteArray(), "aad1")
        assertNull("AAD 不符应认证失败", MeshCrypto.decrypt(key, e.iv, e.cipher, "aad2"))
        val tampered = Base64.getDecoder().decode(e.cipher).copyOf()
            .also { it[0] = (it[0].toInt() xor 0xFF).toByte() }
        assertNull("篡改密文应认证失败", MeshCrypto.decrypt(key, e.iv, Base64.getEncoder().encodeToString(tampered), "aad1"))
        assertNull("错误密钥应失败", MeshCrypto.decrypt(ByteArray(32), e.iv, e.cipher, "aad1"))
    }

    @Test
    fun `public key b64 round trip and fingerprint`() {
        val pair = MeshCrypto.generateKeyPair()
        val b64 = MeshCrypto.publicKeyB64(pair)
        val pub = MeshCrypto.publicKeyFromB64(b64)
        assertArrayEquals("SPKI 往返应一致", pair.public.encoded, pub.encoded)
        val fp = MeshCrypto.fingerprint(b64)
        assertEquals(16, fp.length)
    }

    @Test
    fun `group key is random 32 bytes`() {
        val k1 = MeshCrypto.randomGroupKey()
        val k2 = MeshCrypto.randomGroupKey()
        assertEquals(32, k1.size)
        assertFalse("两次生成的群密钥应不同", k1.contentEquals(k2))
        assertNotNull(k1)
    }
}
