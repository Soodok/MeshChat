package com.meshchat.app.security.model

import org.junit.Test

class RedactedEvidencePolicyTest {
    @Test
    fun `approved categorical evidence is accepted`() {
        RedactedEvidencePolicy.requireSafe(
            mapOf("signal_type" to "overlay", "connection_rate_bucket" to "high"),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `ip address key is rejected`() {
        RedactedEvidencePolicy.requireSafe(mapOf("ip_address" to "192.168.1.2"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `message text value is rejected`() {
        RedactedEvidencePolicy.requireSafe(mapOf("signal_type" to "please read this private message"))
    }
}
