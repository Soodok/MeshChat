package com.meshchat.app.mesh.routing

import com.meshchat.app.mesh.protocol.MeshEnvelope
import com.meshchat.app.mesh.protocol.TextBody
import org.junit.Assert.assertEquals
import org.junit.Test

class ForwardingDecisionTest {
    private fun envelope(dst: String, ttl: Int, id: String = "m") =
        MeshEnvelope(id = id, kind = "TEXT", srcId = "A", dstId = dst, convId = "c", ttl = ttl, ts = 1, body = TextBody("x"))

    private val decision = ForwardingDecision(localId = "B", dedup = DedupCache())

    @Test
    fun `delivers when destination is local`() {
        assertEquals(ForwardDecision.Deliver, decision.decide(envelope("B", 8)))
    }

    @Test
    fun `forwards to others while ttl remains`() {
        assertEquals(ForwardDecision.Forward(7), decision.decide(envelope("C", 8)))
    }

    @Test
    fun `drops when ttl exhausted`() {
        assertEquals(ForwardDecision.Drop, decision.decide(envelope("C", 1)))
    }

    @Test
    fun `drops duplicates`() {
        val env = envelope("C", 8)
        decision.decide(env)
        assertEquals(ForwardDecision.Drop, decision.decide(env))
    }
}
