package com.meshchat.app.security.capability

import com.meshchat.app.security.model.SecurityCapability
import com.meshchat.app.security.model.SecurityCapabilityState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityCapabilityManagerTest {
    private var now = 1_000_000L
    private val reader = FakeReader()
    private val store = InMemoryCapabilityPromptStore()
    private val manager = SecurityCapabilityManager(
        stateReader = reader,
        promptStore = store,
        clock = { now },
        retryCooldownMs = 10_000L,
    )

    @Test
    fun `fresh optional permission is available and requestable`() {
        reader.states[SecurityCapability.NOTIFICATIONS] = SecurityCapabilityState.AVAILABLE

        val status = manager.refresh().getValue(SecurityCapability.NOTIFICATIONS)

        assertEquals(SecurityCapabilityState.AVAILABLE, status.state)
        assertTrue(status.canRequest)
        assertEquals(CapabilityRecovery.REQUEST_PERMISSION, status.recovery)
    }

    @Test
    fun `explicit denial warns without making capability unavailable forever`() {
        reader.states[SecurityCapability.VPN_SCAN] = SecurityCapabilityState.AVAILABLE
        manager.recordDenied(setOf(SecurityCapability.VPN_SCAN))

        val duringCooldown = manager.status.value.getValue(SecurityCapability.VPN_SCAN)
        assertEquals(SecurityCapabilityState.DENIED, duringCooldown.state)
        assertFalse(duringCooldown.canRequest)
        assertEquals(CapabilityRecovery.WAIT, duringCooldown.recovery)

        now += 10_000L
        val afterCooldown = manager.refresh().getValue(SecurityCapability.VPN_SCAN)
        assertEquals(SecurityCapabilityState.DENIED, afterCooldown.state)
        assertTrue(afterCooldown.canRequest)
        assertEquals(CapabilityRecovery.REQUEST_PERMISSION, afterCooldown.recovery)
    }

    @Test
    fun `system settings grant clears previous denial and returns granted state`() {
        reader.states[SecurityCapability.BLUETOOTH] = SecurityCapabilityState.AVAILABLE
        manager.recordDenied(setOf(SecurityCapability.BLUETOOTH))
        reader.states[SecurityCapability.BLUETOOTH] = SecurityCapabilityState.GRANTED
        manager.refresh()

        val status = manager.status.value.getValue(SecurityCapability.BLUETOOTH)

        assertEquals(SecurityCapabilityState.GRANTED, status.state)
        assertEquals(null, status.lastDeniedAt)
        assertFalse(status.isOptional)
    }

    @Test
    fun `unsupported and conflicted platform states are preserved`() {
        reader.states[SecurityCapability.INTEGRITY_CHECK] = SecurityCapabilityState.NOT_CONFIGURED
        reader.states[SecurityCapability.ENTERPRISE_MANAGEMENT] = SecurityCapabilityState.CONFLICTED

        val statuses = manager.refresh()

        assertEquals(SecurityCapabilityState.NOT_CONFIGURED, statuses.getValue(SecurityCapability.INTEGRITY_CHECK).state)
        assertEquals(SecurityCapabilityState.CONFLICTED, statuses.getValue(SecurityCapability.ENTERPRISE_MANAGEMENT).state)
        assertEquals(CapabilityRecovery.NONE, statuses.getValue(SecurityCapability.INTEGRITY_CHECK).recovery)
        assertEquals(CapabilityRecovery.OPEN_SYSTEM_SETTINGS, statuses.getValue(SecurityCapability.ENTERPRISE_MANAGEMENT).recovery)
    }

    private class FakeReader : SecurityCapabilityStateReader {
        val states = mutableMapOf<SecurityCapability, SecurityCapabilityState>()

        override fun readStates(): Map<SecurityCapability, SecurityCapabilityState> = states.toMap()
    }
}
