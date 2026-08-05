package com.meshchat.app.security.model

import kotlinx.serialization.Serializable

/**
 * User-facing risk level. It deliberately describes observable signals, never a confirmed
 * infection, a controller identity, or a network-location conclusion.
 */
@Serializable
enum class SecurityLevel {
    NORMAL,
    LIMITED,
    SUSPICIOUS,
    HIGH_RISK,
}

/** Optional security features. A missing capability means reduced coverage, not a risk signal. */
enum class SecurityCapability {
    BLUETOOTH,
    NOTIFICATIONS,
    INTEGRITY_CHECK,
    VPN_SCAN,
    ENTERPRISE_MANAGEMENT,
}

enum class SecurityCapabilityState {
    AVAILABLE,
    GRANTED,
    DENIED,
    UNSUPPORTED,
    NOT_CONFIGURED,
    CONFLICTED,
}

/** Strict allowlist for metadata that may cross the security-event persistence boundary. */
object RedactedEvidencePolicy {
    private val allowedKeys = setOf(
        "signal_type",
        "connection_rate_bucket",
        "source_class",
        "policy_version",
        "confidence_bucket",
        "risk_category",
    )
    private val safeValue = Regex("[a-z0-9_.-]{1,64}")

    fun requireSafe(evidence: Map<String, String>) {
        require(evidence.size <= 8) { "Too many security evidence fields" }
        require(evidence.keys.all { it in allowedKeys }) { "Security evidence key is not allowlisted" }
        require(evidence.values.all { safeValue.matches(it) }) { "Security evidence value is not an approved label" }
    }
}

enum class SecuritySignalSource {
    LOCAL_POLICY,
    PLAY_INTEGRITY,
    USER_APPROVED_VPN_SCAN,
    ENTERPRISE_MANAGEMENT,
}

/**
 * Separates a device-risk observation from an app protection gap or neutral status note. This
 * prevents missing product features from being reported as a device compromise.
 */
enum class SecuritySignalClassification {
    DEVICE_RISK,
    PROTECTION_GAP,
    INFORMATIONAL,
}

/** Localizable text keys only. Evidence and UI copy stay outside the classifier. */
data class SecurityExplanation(
    val titleKey: String,
    val detailKey: String,
)

/**
 * A bounded, expiring indication of risk. [redactedEvidence] must never contain message text,
 * private keys, session keys, an integrity token, or raw packet/endpoint history.
 */
data class SecuritySignal(
    val id: String,
    val source: SecuritySignalSource,
    val ruleId: String,
    val severity: Int,
    val confidence: Int,
    val occurredAt: Long,
    val expiresAt: Long,
    val explanation: SecurityExplanation,
    val redactedEvidence: Map<String, String> = emptyMap(),
    val classification: SecuritySignalClassification = SecuritySignalClassification.DEVICE_RISK,
) {
    init {
        require(id.isNotBlank())
        require(ruleId.isNotBlank())
        require(severity in 0..100)
        require(confidence in 0..100)
        require(expiresAt >= occurredAt)
        RedactedEvidencePolicy.requireSafe(redactedEvidence)
    }

    fun isActiveAt(now: Long): Boolean = occurredAt <= now && now < expiresAt
}

@Serializable
enum class SecurityUserAction {
    DISMISSED,
    REVIEW_REQUESTED,
    DELETED,
}

/** Persistable, redacted audit record contract. Persistence is intentionally added in Step 07. */
@Serializable
data class SecurityEvent(
    val id: String,
    val level: SecurityLevel,
    val ruleId: String,
    val redactedEvidence: Map<String, String>,
    val userAction: SecurityUserAction? = null,
    val expiresAt: Long,
) {
    init {
        require(id.isNotBlank())
        require(ruleId.isNotBlank())
        RedactedEvidencePolicy.requireSafe(redactedEvidence)
    }
}

data class SecurityAssessment(
    val level: SecurityLevel,
    val score: Int,
    val activeSignals: List<SecuritySignal>,
    val protectionGaps: List<SecuritySignal>,
    val limitedCapabilities: Set<SecurityCapability>,
)

/**
 * Pure, deterministic policy for deriving a display state. It has no Android, database,
 * network, logging, or clock dependency so that every rule can be covered by JVM tests.
 */
class SecurityRiskClassifier(
    private val suspiciousThreshold: Int = 40,
    private val highRiskThreshold: Int = 70,
) {
    init {
        require(suspiciousThreshold in 1..100)
        require(highRiskThreshold in suspiciousThreshold..100)
    }

    fun assess(
        signals: List<SecuritySignal>,
        capabilities: Map<SecurityCapability, SecurityCapabilityState>,
        now: Long,
    ): SecurityAssessment {
        val activeSignals = signals
            .asSequence()
            .filter { it.isActiveAt(now) }
            .groupBy { it.ruleId }
            .values
            .map { duplicates -> duplicates.maxBy { it.effectiveScore() } }
            .sortedWith(compareByDescending<SecuritySignal> { it.effectiveScore() }.thenBy { it.id })
            .toList()
        val deviceRiskSignals = activeSignals.filter { it.classification == SecuritySignalClassification.DEVICE_RISK }
        val protectionGaps = activeSignals.filter { it.classification == SecuritySignalClassification.PROTECTION_GAP }
        val score = deviceRiskSignals.sumOf { it.effectiveScore() }.coerceAtMost(100)
        val limitedCapabilities = capabilities
            .filterValues { it != SecurityCapabilityState.AVAILABLE && it != SecurityCapabilityState.GRANTED }
            .keys

        val level = when {
            score >= highRiskThreshold -> SecurityLevel.HIGH_RISK
            score >= suspiciousThreshold -> SecurityLevel.SUSPICIOUS
            limitedCapabilities.isNotEmpty() || protectionGaps.isNotEmpty() -> SecurityLevel.LIMITED
            else -> SecurityLevel.NORMAL
        }
        return SecurityAssessment(level, score, activeSignals, protectionGaps, limitedCapabilities)
    }

    private fun SecuritySignal.effectiveScore(): Int = severity * confidence / 100
}
