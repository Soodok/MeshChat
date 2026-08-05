package com.meshchat.app.security.risk

import com.meshchat.app.security.model.SecurityAssessment
import com.meshchat.app.security.model.SecurityCapability
import com.meshchat.app.security.model.SecurityCapabilityState
import com.meshchat.app.security.model.SecurityEvent
import com.meshchat.app.security.model.SecurityRiskClassifier
import com.meshchat.app.security.model.SecuritySignal

data class SecurityRiskEvaluation(
    val assessment: SecurityAssessment,
    val events: List<SecurityEvent>,
)

/** Pure local policy: converts active, explainable signals into redacted event records. */
class SecurityRiskEngine(private val classifier: SecurityRiskClassifier = SecurityRiskClassifier()) {
    fun evaluate(
        signals: List<SecuritySignal>,
        capabilities: Map<SecurityCapability, SecurityCapabilityState>,
        now: Long,
    ): SecurityRiskEvaluation {
        val assessment = classifier.assess(signals, capabilities, now)
        val events = assessment.activeSignals.map { signal ->
            val signalLevel = classifier.assess(listOf(signal), emptyMap(), now).level
            SecurityEvent(
                id = signal.id,
                level = signalLevel,
                ruleId = signal.ruleId,
                redactedEvidence = signal.redactedEvidence,
                expiresAt = signal.expiresAt,
            )
        }
        return SecurityRiskEvaluation(assessment, events)
    }
}
