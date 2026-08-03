package com.meshchat.app.mesh.routing

import com.meshchat.app.mesh.protocol.MeshEnvelope

sealed interface ForwardDecision {
    data object Deliver : ForwardDecision
    data object Drop : ForwardDecision
    data class Forward(val ttl: Int) : ForwardDecision
}

class ForwardingDecision(private val localId: String, private val dedup: DedupCache) {
    fun decide(envelope: MeshEnvelope): ForwardDecision {
        if (dedup.contains(envelope.id)) return ForwardDecision.Drop
        dedup.mark(envelope.id)
        return when {
            envelope.dstId == localId -> ForwardDecision.Deliver
            envelope.ttl - 1 > 0 -> ForwardDecision.Forward(envelope.ttl - 1)
            else -> ForwardDecision.Drop
        }
    }
}
