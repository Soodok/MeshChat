package com.meshchat.app.security.risk

import com.meshchat.app.security.model.SecurityCapability
import com.meshchat.app.security.model.SecurityCapabilityState
import com.meshchat.app.security.model.SecurityExplanation
import com.meshchat.app.security.model.SecuritySignal
import com.meshchat.app.security.model.SecuritySignalSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityRiskEngineTest {
    private val now = 1_000_000L
    private val engine = SecurityRiskEngine()

    @Test
    fun `only active signals become redacted event records`() {
        val active = signal("active", now + 60_000L, mapOf("signal_type" to "overlay"))
        val expired = signal("expired", now, mapOf("signal_type" to "capture"))

        val result = engine.evaluate(
            signals = listOf(active, expired),
            capabilities = mapOf(SecurityCapability.VPN_SCAN to SecurityCapabilityState.DENIED),
            now = now,
        )

        assertEquals(1, result.events.size)
        assertEquals("active", result.events.single().id)
        assertEquals(mapOf("signal_type" to "overlay"), result.events.single().redactedEvidence)
        assertEquals(1, result.assessment.limitedCapabilities.size)
    }

    @Test
    fun `no signals produce no events`() {
        val result = engine.evaluate(emptyList(), emptyMap(), now)

        assertTrue(result.events.isEmpty())
    }

    private fun signal(id: String, expiresAt: Long, evidence: Map<String, String>) = SecuritySignal(
        id = id,
        source = SecuritySignalSource.LOCAL_POLICY,
        ruleId = "rule-$id",
        severity = 50,
        confidence = 100,
        occurredAt = now - 1L,
        expiresAt = expiresAt,
        explanation = SecurityExplanation("security.$id.title", "security.$id.detail"),
        redactedEvidence = evidence,
    )
}
