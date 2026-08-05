package com.meshchat.app.security.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class SecurityScanSessionControllerTest {
    private val controller = SecurityScanSessionController()
    private val now = 1_000_000L

    @Test
    fun `scan only runs after explicit system consent`() {
        assertEquals(SecurityScanState.AWAITING_SYSTEM_CONSENT, controller.requestStart().state)
        assertEquals(SecurityScanState.DENIED, controller.onSystemConsent(granted = false, now = now).state)

        val running = controller.onSystemConsent(granted = true, now = now)
        assertEquals(SecurityScanState.RUNNING, running.state)
        assertEquals(now + 15L * 60L * 1_000L, running.stopsAt)
    }

    @Test
    fun `scan stops at fifteen minute deadline or manual stop`() {
        val running = controller.onSystemConsent(granted = true, now = now)

        assertEquals(SecurityScanState.RUNNING, controller.advance(running, now + 15L * 60L * 1_000L - 1L).state)
        assertEquals(SecurityScanState.TIMED_OUT, controller.advance(running, now + 15L * 60L * 1_000L).state)
        assertEquals(SecurityScanState.STOPPED, controller.stop().state)
    }

    @Test
    fun `only aggregate high connection rate produces a low confidence signal`() {
        val engine = ConnectionMetadataRuleEngine()
        assertNull(engine.evaluate(ConnectionMetadataSummary(NetworkProtocol.TCP, 443, 499), now))

        val signal = engine.evaluate(ConnectionMetadataSummary(NetworkProtocol.TCP, 443, 500), now)
        assertNotNull(signal)
        assertEquals("vpn.high_connection_rate", signal!!.ruleId)
        assertEquals(mapOf("connection_rate_bucket" to "high"), signal.redactedEvidence)
    }
}
