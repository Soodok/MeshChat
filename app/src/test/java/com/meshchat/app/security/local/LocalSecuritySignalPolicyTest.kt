package com.meshchat.app.security.local

import com.meshchat.app.security.model.SecurityLevel
import com.meshchat.app.security.risk.SecurityRiskEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSecuritySignalPolicyTest {
    private val now = 1_000_000L

    @Test
    fun `release-like device with no local observations produces no signal`() {
        assertTrue(LocalSecuritySignalPolicy.collect(LocalSecurityFacts(false, false, transportEncryptionEnforced = true), now).isEmpty())
    }

    @Test
    fun `debuggable app is an explainable local risk signal with only allowed evidence`() {
        val signal = LocalSecuritySignalPolicy.collect(LocalSecurityFacts(true, false, transportEncryptionEnforced = true), now).single()

        assertEquals("debuggable-build-enabled", signal.ruleId)
        assertEquals(SecurityLevel.LIMITED, SecurityRiskEngine().evaluate(listOf(signal), emptyMap(), now).assessment.level)
        assertEquals(setOf("signal_type", "source_class", "confidence_bucket", "risk_category", "policy_version"), signal.redactedEvidence.keys)
    }

    @Test
    fun `accessibility is a low-confidence review cue rather than a compromise finding`() {
        val signal = LocalSecuritySignalPolicy.collect(LocalSecurityFacts(false, true, transportEncryptionEnforced = true), now).single()

        assertEquals("accessibility-service-enabled", signal.ruleId)
        assertEquals(SecurityLevel.NORMAL, SecurityRiskEngine().evaluate(listOf(signal), emptyMap(), now).assessment.level)
    }

    @Test
    fun `plaintext mesh transport is surfaced as high risk instead of being called encrypted`() {
        val signal = LocalSecuritySignalPolicy.collect(LocalSecurityFacts(false, false, transportEncryptionEnforced = false), now).single()

        assertEquals("end-to-end-encryption-unavailable", signal.ruleId)
        assertEquals(SecurityLevel.LIMITED, SecurityRiskEngine().evaluate(listOf(signal), emptyMap(), now).assessment.level)
    }

    @Test
    fun `unencrypted application message storage is not confused with platform file encryption`() {
        val signal = LocalSecuritySignalPolicy.collect(
            LocalSecurityFacts(false, false, transportEncryptionEnforced = true, messageStorageEncrypted = false),
            now,
        ).single()

        assertEquals("application-message-storage-encryption-unavailable", signal.ruleId)
        assertEquals(SecurityLevel.LIMITED, SecurityRiskEngine().evaluate(listOf(signal), emptyMap(), now).assessment.level)
    }

    @Test
    fun `missing screen lock is a local configuration warning`() {
        val signal = LocalSecuritySignalPolicy.collect(
            LocalSecurityFacts(false, false, transportEncryptionEnforced = true, deviceSecure = false),
            now,
        ).single()

        assertEquals("device-screen-lock-disabled", signal.ruleId)
        assertEquals(SecurityLevel.LIMITED, SecurityRiskEngine().evaluate(listOf(signal), emptyMap(), now).assessment.level)
    }

    @Test
    fun `attached debugger is visible as a local app hardening warning`() {
        val signal = LocalSecuritySignalPolicy.collect(
            LocalSecurityFacts(false, false, transportEncryptionEnforced = true, debuggerAttached = true),
            now,
        ).single()

        assertEquals("debugger-attached", signal.ruleId)
        assertEquals(SecurityLevel.LIMITED, SecurityRiskEngine().evaluate(listOf(signal), emptyMap(), now).assessment.level)
    }
}
