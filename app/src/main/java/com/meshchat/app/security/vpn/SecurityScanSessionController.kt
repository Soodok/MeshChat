package com.meshchat.app.security.vpn

import com.meshchat.app.security.model.SecurityExplanation
import com.meshchat.app.security.model.SecuritySignal
import com.meshchat.app.security.model.SecuritySignalSource

enum class SecurityScanState {
    IDLE,
    AWAITING_SYSTEM_CONSENT,
    RUNNING,
    DENIED,
    CONFLICTED,
    STOPPED,
    TIMED_OUT,
}

data class SecurityScanSession(
    val state: SecurityScanState,
    val startedAt: Long? = null,
    val stopsAt: Long? = null,
)

/**
 * Consent-first session state machine. It has no VpnService dependency and cannot intercept
 * traffic by itself; only a later, audited forwarding engine may transition it to RUNNING.
 */
class SecurityScanSessionController(
    private val durationMs: Long = DEFAULT_SCAN_DURATION_MS,
) {
    init {
        require(durationMs in 1L..MAX_SCAN_DURATION_MS)
    }

    fun requestStart(): SecurityScanSession = SecurityScanSession(SecurityScanState.AWAITING_SYSTEM_CONSENT)

    fun onSystemConsent(granted: Boolean, now: Long): SecurityScanSession = if (granted) {
        SecurityScanSession(SecurityScanState.RUNNING, startedAt = now, stopsAt = now + durationMs)
    } else {
        SecurityScanSession(SecurityScanState.DENIED)
    }

    fun onExistingVpnConflict(): SecurityScanSession = SecurityScanSession(SecurityScanState.CONFLICTED)

    fun stop(): SecurityScanSession = SecurityScanSession(SecurityScanState.STOPPED)

    fun advance(session: SecurityScanSession, now: Long): SecurityScanSession =
        if (session.state == SecurityScanState.RUNNING && session.stopsAt != null && now >= session.stopsAt) {
            SecurityScanSession(SecurityScanState.TIMED_OUT)
        } else {
            session
        }

    private companion object {
        const val DEFAULT_SCAN_DURATION_MS = 15L * 60L * 1_000L
        const val MAX_SCAN_DURATION_MS = 15L * 60L * 1_000L
    }
}

enum class NetworkProtocol { TCP, UDP, OTHER }

/**
 * An in-memory aggregate only. It must not contain an IP address, DNS name, payload, app name,
 * or packet bytes, and must be discarded when the scan ends.
 */
data class ConnectionMetadataSummary(
    val protocol: NetworkProtocol,
    val destinationPort: Int,
    val connectionsInMinute: Int,
) {
    init {
        require(destinationPort in 0..65535)
        require(connectionsInMinute >= 0)
    }
}

/** Local heuristic is deliberately low-confidence; threat-intelligence lookups remain a later opt-in step. */
class ConnectionMetadataRuleEngine {
    fun evaluate(summary: ConnectionMetadataSummary, observedAt: Long): SecuritySignal? {
        if (summary.connectionsInMinute < HIGH_CONNECTION_RATE_PER_MINUTE) return null
        return SecuritySignal(
            id = "vpn-high-connection-rate-$observedAt",
            source = SecuritySignalSource.USER_APPROVED_VPN_SCAN,
            ruleId = "vpn.high_connection_rate",
            severity = 35,
            confidence = 60,
            occurredAt = observedAt,
            expiresAt = observedAt + SIGNAL_TTL_MS,
            explanation = SecurityExplanation(
                titleKey = "security.vpn.high_connection_rate.title",
                detailKey = "security.vpn.high_connection_rate.detail",
            ),
            redactedEvidence = mapOf("connection_rate_bucket" to "high"),
        )
    }

    private companion object {
        const val HIGH_CONNECTION_RATE_PER_MINUTE = 500
        const val SIGNAL_TTL_MS = 15L * 60L * 1_000L
    }
}
