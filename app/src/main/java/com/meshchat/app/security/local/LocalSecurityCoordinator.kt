package com.meshchat.app.security.local

import com.meshchat.app.security.model.SecurityEvent
import com.meshchat.app.security.model.SecurityAssessment
import com.meshchat.app.security.risk.SecurityEventStore
import com.meshchat.app.security.risk.SecurityRiskEngine

enum class LocalSecurityStorageState { READY, UNAVAILABLE }

data class LocalSecuritySnapshot(
    val assessment: SecurityAssessment,
    val events: List<SecurityEvent>,
    val checkedAt: Long?,
    val storageState: LocalSecurityStorageState,
)

/**
 * Coordinates only local work: Android platform status -> deterministic policy -> encrypted
 * no-backup event store. It has no HTTP client, telemetry, VPN, or Play Integrity dependency.
 */
class LocalSecurityCoordinator(
    private val signalCollector: LocalSecuritySignalCollector,
    private val riskEngine: SecurityRiskEngine,
    private val eventStore: SecurityEventStore,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun refresh(): LocalSecuritySnapshot {
        val checkedAt = now()
        val evaluation = riskEngine.evaluate(
            signals = signalCollector.collect(checkedAt),
            // Cloud integrity and VPN coverage are optional enhancements. Their absence must not
            // downgrade an offline local assessment.
            capabilities = emptyMap(),
            now = checkedAt,
        )
        return try {
            eventStore.pruneExpired(checkedAt)
            evaluation.events.forEach(eventStore::upsert)
            LocalSecuritySnapshot(
                assessment = evaluation.assessment,
                events = eventStore.read().filter { it.expiresAt > checkedAt }.sortedByDescending { it.expiresAt },
                checkedAt = checkedAt,
                storageState = LocalSecurityStorageState.READY,
            )
        } catch (_: Exception) {
            // Keep the in-memory result visible. A storage failure must be explicit rather than
            // silently deleting evidence or changing the assessment.
            LocalSecuritySnapshot(
                assessment = evaluation.assessment,
                events = evaluation.events,
                checkedAt = checkedAt,
                storageState = LocalSecurityStorageState.UNAVAILABLE,
            )
        }
    }

    fun deleteLocalHistory(): LocalSecuritySnapshot {
        val checkedAt = now()
        val assessment = riskEngine.evaluate(
            signals = signalCollector.collect(checkedAt),
            capabilities = emptyMap(),
            now = checkedAt,
        ).assessment
        return try {
            eventStore.deleteAll()
            LocalSecuritySnapshot(assessment, emptyList(), checkedAt, LocalSecurityStorageState.READY)
        } catch (_: Exception) {
            LocalSecuritySnapshot(assessment, emptyList(), checkedAt, LocalSecurityStorageState.UNAVAILABLE)
        }
    }
}
