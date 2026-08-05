package com.meshchat.app.security.integrity

import com.meshchat.app.security.model.SecurityLevel
import com.meshchat.app.security.model.SecurityRiskClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IntegritySignalMapperTest {
    private val mapper = IntegritySignalMapper()
    private val now = 1_000_000L

    @Test
    fun `unverified verdict produces no security signal`() {
        val signals = mapper.map(
            ServerVerifiedIntegrityVerdict(
                serverVerified = false,
                evaluatedAt = now,
                expiresAt = now + 60_000L,
                appAccessRisks = setOf(AppAccessRisk.UNKNOWN_CONTROLLING),
                playProtect = PlayProtectVerdict.HIGH_RISK,
            ),
        )

        assertTrue(signals.isEmpty())
    }

    @Test
    fun `unknown controlling capability maps to a high risk signal without attribution`() {
        val signal = mapper.map(verified(appAccessRisks = setOf(AppAccessRisk.UNKNOWN_CONTROLLING))).single()

        assertEquals("integrity.unknown_controlling", signal.ruleId)
        assertEquals(emptyMap<String, String>(), signal.redactedEvidence)
        val level = SecurityRiskClassifier().assess(listOf(signal), emptyMap(), now).level
        assertEquals(SecurityLevel.HIGH_RISK, level)
    }

    @Test
    fun `play protect medium risk is a signal rather than a compromise claim`() {
        val signal = mapper.map(verified(playProtect = PlayProtectVerdict.MEDIUM_RISK)).single()

        assertEquals("integrity.play_protect_medium_risk", signal.ruleId)
        assertEquals("security.integrity.play_protect.title", signal.explanation.titleKey)
    }

    @Test
    fun `high risk only protects sensitive actions and never blocks basic chat`() {
        val guard = SensitiveActionGuard()

        assertEquals(ActionDecision.REQUIRE_REVIEW, guard.decide(SensitiveAction.EXPORT_IDENTITY, SecurityLevel.HIGH_RISK))
        assertEquals(ActionDecision.ALLOW, guard.decide(SensitiveAction.BASIC_CHAT, SecurityLevel.HIGH_RISK))
        assertEquals(ActionDecision.ALLOW, guard.decide(SensitiveAction.RESTORE_BACKUP, SecurityLevel.LIMITED))
    }

    private fun verified(
        appAccessRisks: Set<AppAccessRisk> = emptySet(),
        playProtect: PlayProtectVerdict = PlayProtectVerdict.NO_ISSUES,
    ) = ServerVerifiedIntegrityVerdict(
        serverVerified = true,
        evaluatedAt = now,
        expiresAt = now + 60_000L,
        appAccessRisks = appAccessRisks,
        playProtect = playProtect,
    )
}
