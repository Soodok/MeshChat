package com.meshchat.app.mesh.storage

/**
 * Application-level storage protection state. Message bodies / file metadata / outbox envelopes
 * are encrypted with AES-256-GCM keyed by a non-exportable Android Keystore key
 * (see EncryptedMeshStore). Room schema itself stays plaintext (convId/timestamps are query keys).
 */
object MeshStorageSecurity {
    const val APP_LEVEL_MESSAGE_ENCRYPTION_ENFORCED: Boolean = true
}
