package com.meshchat.app.mesh.storage

/**
 * Application-level storage protection state. Room currently uses the platform SQLite database
 * without a reviewed encrypted-database integration. Android file-based encryption is not a
 * substitute for an application key hierarchy or encrypted attachment storage.
 */
object MeshStorageSecurity {
    const val APP_LEVEL_MESSAGE_ENCRYPTION_ENFORCED: Boolean = false
}
