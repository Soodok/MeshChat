package com.meshchat.app.security.risk

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.meshchat.app.security.model.SecurityEvent
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

interface SecurityEventStore {
    fun read(): List<SecurityEvent>
    fun upsert(event: SecurityEvent)
    fun pruneExpired(now: Long)
    fun deleteAll()
}

class SecurityEventStoreException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/**
 * App-private, no-backup event store. AES-GCM uses a fresh IV for every write; the Keystore key
 * is non-exportable and restricted to AES/GCM encrypt/decrypt operations. Call from a worker,
 * never from the UI thread.
 */
class EncryptedSecurityEventStore(context: Context) : SecurityEventStore {
    private val file = AtomicFile(File(context.noBackupFilesDir, FILE_NAME))
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Synchronized
    override fun read(): List<SecurityEvent> {
        if (!file.baseFile.exists()) return emptyList()
        return try {
            val plainText = decrypt(file.openRead().use { it.readBytes() })
            json.decodeFromString<List<SecurityEvent>>(plainText.decodeToString())
        } catch (error: SecurityEventStoreException) {
            throw error
        } catch (error: Exception) {
            throw SecurityEventStoreException("Unable to read encrypted security events", error)
        }
    }

    @Synchronized
    override fun upsert(event: SecurityEvent) {
        val next = read().filterNot { it.id == event.id } + event
        write(next)
    }

    @Synchronized
    override fun pruneExpired(now: Long) {
        write(read().filter { it.expiresAt > now })
    }

    @Synchronized
    override fun deleteAll() {
        if (file.baseFile.exists()) file.delete()
    }

    private fun write(events: List<SecurityEvent>) {
        val output = file.startWrite()
        try {
            output.write(encrypt(json.encodeToString(events).encodeToByteArray()))
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw SecurityEventStoreException("Unable to write encrypted security events", error)
        }
    }

    private fun encrypt(plainText: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val cipherText = cipher.doFinal(plainText)
        return byteArrayOf(FORMAT_VERSION) + cipher.iv + cipherText
    }

    private fun decrypt(payload: ByteArray): ByteArray {
        if (payload.size <= 1 + GCM_IV_BYTES || payload[0] != FORMAT_VERSION) {
            throw SecurityEventStoreException("Unsupported encrypted security event format")
        }
        val iv = payload.copyOfRange(1, 1 + GCM_IV_BYTES)
        val cipherText = payload.copyOfRange(1 + GCM_IV_BYTES, payload.size)
        return Cipher.getInstance(TRANSFORMATION).run {
            init(Cipher.DECRYPT_MODE, key(), javax.crypto.spec.GCMParameterSpec(GCM_TAG_BITS, iv))
            doFinal(cipherText)
        }
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()
    }

    private companion object {
        const val FILE_NAME = "security-events-v1.bin"
        const val KEY_ALIAS = "meshchat.security-events.v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val FORMAT_VERSION: Byte = 1
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
    }
}
