package com.meshchat.app.security.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SecurityRiskClassifierTest {
    private val classifier = SecurityRiskClassifier()
    private val now = 1_000_000L

    @Test
    fun `no active signals and all capabilities available is normal`() {
        val assessment = classifier.assess(
            signals = emptyList(),
            capabilities = SecurityCapability.entries.associateWith { SecurityCapabilityState.AVAILABLE },
            now = now,
        )

        assertEquals(SecurityLevel.NORMAL, assessment.level)
        assertEquals(0, assessment.score)
        assertEquals(emptyList<SecuritySignal>(), assessment.activeSignals)
    }

    @Test
    fun `unavailable optional capability is limited rather than suspicious`() {
        val assessment = classifier.assess(
            signals = emptyList(),
            capabilities = mapOf(SecurityCapability.VPN_SCAN to SecurityCapabilityState.DENIED),
            now = now,
        )

        assertEquals(SecurityLevel.LIMITED, assessment.level)
        assertEquals(setOf(SecurityCapability.VPN_SCAN), assessment.limitedCapabilities)
    }

    @Test
    fun `protection gap is limited coverage rather than device risk`() {
        val gap = signal(id = "storage", ruleId = "storage-encryption", severity = 100, confidence = 100)
            .copy(classification = SecuritySignalClassification.PROTECTION_GAP)

        val assessment = classifier.assess(listOf(gap), emptyMap(), now)

        assertEquals(SecurityLevel.LIMITED, assessment.level)
        assertEquals(0, assessment.score)
        assertEquals(listOf(gap), assessment.protectionGaps)
    }

    @Test
    fun `weak distinct signals stack into suspicious`() {
        val assessment = classifier.assess(
            signals = listOf(
                signal(id = "access-overlay", ruleId = "overlay", severity = 30, confidence = 80),
                signal(id = "screen-capture", ruleId = "capture", severity = 25, confidence = 80),
            ),
            capabilities = emptyMap(),
            now = now,
        )

        assertEquals(SecurityLevel.SUSPICIOUS, assessment.level)
        assertEquals(44, assessment.score)
    }

    @Test
    fun `strong active signal is high risk without claiming compromise`() {
        val assessment = classifier.assess(
            signals = listOf(signal(id = "play-protect", ruleId = "known-harmful-app", severity = 90, confidence = 90)),
            capabilities = emptyMap(),
            now = now,
        )

        assertEquals(SecurityLevel.HIGH_RISK, assessment.level)
        assertEquals(81, assessment.score)
        assertEquals("known-harmful-app", assessment.activeSignals.single().ruleId)
    }

    @Test
    fun `expired signals do not affect assessment`() {
        val assessment = classifier.assess(
            signals = listOf(signal(id = "old", ruleId = "old-signal", severity = 100, confidence = 100, expiresAt = now)),
            capabilities = emptyMap(),
            now = now,
        )

        assertEquals(SecurityLevel.NORMAL, assessment.level)
        assertEquals(0, assessment.score)
        assertEquals(emptyList<SecuritySignal>(), assessment.activeSignals)
    }

    @Test
    fun `future signals do not affect assessment before they occur`() {
        val assessment = classifier.assess(
            signals = listOf(
                SecuritySignal(
                    id = "future",
                    source = SecuritySignalSource.LOCAL_POLICY,
                    ruleId = "future-signal",
                    severity = 100,
                    confidence = 100,
                    occurredAt = now + 1L,
                    expiresAt = now + 60_000L,
                    explanation = SecurityExplanation("security.future.title", "security.future.detail"),
                ),
            ),
            capabilities = emptyMap(),
            now = now,
        )

        assertEquals(SecurityLevel.NORMAL, assessment.level)
        assertEquals(0, assessment.score)
    }

    @Test
    fun `duplicate rule evidence has a bounded contribution`() {
        val assessment = classifier.assess(
            signals = (1..10).map { index ->
                signal(id = "repeat-$index", ruleId = "overlay", severity = 40, confidence = 100)
            },
            capabilities = emptyMap(),
            now = now,
        )

        assertEquals(SecurityLevel.SUSPICIOUS, assessment.level)
        assertEquals(40, assessment.score)
        assertEquals(1, assessment.activeSignals.size)
    }

    private fun signal(
        id: String,
        ruleId: String,
        severity: Int,
        confidence: Int,
        expiresAt: Long = now + 60_000L,
    ) = SecuritySignal(
        id = id,
        source = SecuritySignalSource.LOCAL_POLICY,
        ruleId = ruleId,
        severity = severity,
        confidence = confidence,
        occurredAt = now - 1L,
        expiresAt = expiresAt,
        explanation = SecurityExplanation(
            titleKey = "security.signal.$ruleId.title",
            detailKey = "security.signal.$ruleId.detail",
        ),
    )
}
