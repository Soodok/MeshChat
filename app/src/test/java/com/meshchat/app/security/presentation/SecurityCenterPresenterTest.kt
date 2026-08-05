package com.meshchat.app.security.presentation

import com.meshchat.app.security.capability.CapabilityRecovery
import com.meshchat.app.security.capability.SecurityCapabilityStatus
import com.meshchat.app.security.model.SecurityCapability
import com.meshchat.app.security.model.SecurityCapabilityState
import com.meshchat.app.security.model.SecurityLevel
import com.meshchat.app.security.model.SecurityAssessment
import com.meshchat.app.security.model.SecurityExplanation
import com.meshchat.app.security.model.SecuritySignal
import com.meshchat.app.security.model.SecuritySignalClassification
import com.meshchat.app.security.model.SecuritySignalSource
import org.junit.Assert.assertEquals
import org.junit.Test

class SecurityCenterPresenterTest {
    @Test
    fun `protection gaps are not presented as device compromise`() {
        val gap = SecuritySignal(
            id = "gap",
            source = SecuritySignalSource.LOCAL_POLICY,
            ruleId = "end-to-end-encryption-unavailable",
            severity = 90,
            confidence = 100,
            occurredAt = 1,
            expiresAt = 2,
            explanation = SecurityExplanation("title", "detail"),
            classification = SecuritySignalClassification.PROTECTION_GAP,
        )

        val summary = SecurityCenterPresenter.summary(
            SecurityAssessment(SecurityLevel.LIMITED, 0, listOf(gap), listOf(gap), emptySet()),
        )

        assertEquals(SecurityLevel.LIMITED, summary.level)
        assertEquals("本应用保护尚未完成", summary.title)
    }

    @Test
    fun `available and granted capabilities keep normal summary`() {
        val summary = SecurityCenterPresenter.summary(
            mapOf(
                SecurityCapability.BLUETOOTH to status(SecurityCapability.BLUETOOTH, SecurityCapabilityState.GRANTED),
                SecurityCapability.NOTIFICATIONS to status(SecurityCapability.NOTIFICATIONS, SecurityCapabilityState.AVAILABLE),
            ),
        )

        assertEquals(SecurityLevel.NORMAL, summary.level)
        assertEquals("基础保护已启用", summary.title)
    }

    @Test
    fun `missing capability is described as limited coverage not high risk`() {
        val summary = SecurityCenterPresenter.summary(
            mapOf(SecurityCapability.NOTIFICATIONS to status(SecurityCapability.NOTIFICATIONS, SecurityCapabilityState.DENIED)),
        )

        assertEquals(SecurityLevel.LIMITED, summary.level)
        assertEquals("部分检测受限", summary.title)
    }

    private fun status(capability: SecurityCapability, state: SecurityCapabilityState) = SecurityCapabilityStatus(
        capability = capability,
        state = state,
        isOptional = capability != SecurityCapability.BLUETOOTH,
        canRequest = state == SecurityCapabilityState.AVAILABLE,
        recovery = if (state == SecurityCapabilityState.AVAILABLE) CapabilityRecovery.REQUEST_PERMISSION else CapabilityRecovery.NONE,
    )
}
