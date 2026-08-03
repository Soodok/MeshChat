package com.meshchat.app.mesh.identity

data class PeerRecord(
    val shortId: String,
    val displayName: String,
    var lastSeen: Long,
    var hops: Int,
)

class PeerRegistry {
    private val peers = LinkedHashMap<String, PeerRecord>()

    fun upsert(record: PeerRecord): PeerRecord {
        peers[record.shortId] = record
        return record
    }

    fun get(id: String): PeerRecord? = peers[id]

    fun remove(id: String) {
        peers.remove(id)
    }

    fun all(): List<PeerRecord> = peers.values.toList()

    fun prune(now: Long, timeoutMillis: Long) {
        peers.values.removeAll { now - it.lastSeen > timeoutMillis }
    }
}
