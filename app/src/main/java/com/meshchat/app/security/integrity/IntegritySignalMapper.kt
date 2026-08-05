package com.meshchat.app.security.integrity

import com.meshchat.app.security.model.SecurityExplanation
import com.meshchat.app.security.model.SecuritySignal
import com.meshchat.app.security.model.SecuritySignalSource

/** Values normalized by the backend after it validates the integrity request binding and freshness. */
enum class AppAccessRisk {
    KNOWN_INSTALLED,
    UNKNOWN_INSTALLED,
    KNOWN_CAPTURING,
    UNKNOWN_CAPTURING,
    KNOWN_CONTROLLING,
    UNKNOWN_CONTROLLING,
    KNOWN_OVERLAYS,
    UNKNOWN_OVERLAYS,
}

enum class PlayProtectVerdict {
    NO_ISSUES,
    NO_DATA,
    POSSIBLE_RISK,
    MEDIUM_RISK,
    HIGH_RISK,
    UNEVALUATED,
}

/**
 * This object intentionally excludes the raw Integrity token, package metadata, device IDs,
 * app names, IPs, and endpoint history. It is usable only after server-side verification.
 */
data class ServerVerifiedIntegrityVerdict(
    val serverVerified: Boolean,
    val evaluatedAt: Long,
    val expiresAt: Long,
    val appAccessRisks: Set<AppAccessRisk> = emptySet(),
    val playProtect: PlayProtectVerdict = PlayProtectVerdict.UNEVALUATED,
) {
    init {
        require(expiresAt >= evaluatedAt)
    }
}

/** Adapter boundary for the future Play Integrity client + backend verifier. */
interface IntegrityVerdictProvider {
    suspend fun requestVerdictForSensitiveAction(action: SensitiveAction): ServerVerifiedIntegrityVerdict?
}

enum class SensitiveAction {
    BASIC_CHAT,
    EXPORT_IDENTITY,
    RESTORE_BACKUP,
    EXTERNAL_THREAT_LOOKUP,
}

enum class ActionDecision {
    ALLOW,
    REQUIRE_REVIEW,
}

/** Only sensitive actions are gated on high risk; basic chat remains available. */
class SensitiveActionGuard {
    fun decide(action: SensitiveAction, level: com.meshchat.app.security.model.SecurityLevel): ActionDecision = when {
        action == SensitiveAction.BASIC_CHAT -> ActionDecision.ALLOW
        level == com.meshchat.app.security.model.SecurityLevel.HIGH_RISK -> ActionDecision.REQUIRE_REVIEW
        else -> ActionDecision.ALLOW
    }
}

class IntegritySignalMapper {
    fun map(verdict: ServerVerifiedIntegrityVerdict): List<SecuritySignal> {
        if (!verdict.serverVerified) return emptyList()
        return buildList {
            verdict.appAccessRisks.forEach { risk -> risk.toSignal(verdict)?.let(::add) }
            verdict.playProtect.toSignal(verdict)?.let(::add)
        }
    }

    private fun AppAccessRisk.toSignal(verdict: ServerVerifiedIntegrityVerdict): SecuritySignal? = when (this) {
        AppAccessRisk.UNKNOWN_CONTROLLING -> verdict.signal("unknown_controlling", 90, 90, "security.integrity.controlling")
        AppAccessRisk.KNOWN_CONTROLLING -> verdict.signal("known_controlling", 70, 80, "security.integrity.controlling")
        AppAccessRisk.UNKNOWN_CAPTURING -> verdict.signal("unknown_capturing", 60, 85, "security.integrity.capturing")
        AppAccessRisk.KNOWN_CAPTURING -> verdict.signal("known_capturing", 45, 75, "security.integrity.capturing")
        AppAccessRisk.UNKNOWN_OVERLAYS -> verdict.signal("unknown_overlays", 50, 80, "security.integrity.overlays")
        AppAccessRisk.KNOWN_OVERLAYS -> verdict.signal("known_overlays", 35, 70, "security.integrity.overlays")
        AppAccessRisk.KNOWN_INSTALLED, AppAccessRisk.UNKNOWN_INSTALLED -> null
    }

    private fun PlayProtectVerdict.toSignal(verdict: ServerVerifiedIntegrityVerdict): SecuritySignal? = when (this) {
        PlayProtectVerdict.MEDIUM_RISK -> verdict.signal("play_protect_medium_risk", 70, 90, "security.integrity.play_protect")
        PlayProtectVerdict.HIGH_RISK -> verdict.signal("play_protect_high_risk", 90, 95, "security.integrity.play_protect")
        PlayProtectVerdict.NO_ISSUES,
        PlayProtectVerdict.NO_DATA,
        PlayProtectVerdict.POSSIBLE_RISK,
        PlayProtectVerdict.UNEVALUATED -> null
    }

    private fun ServerVerifiedIntegrityVerdict.signal(
        suffix: String,
        severity: Int,
        confidence: Int,
        explanationPrefix: String,
    ) = SecuritySignal(
        id = "integrity-$suffix-$evaluatedAt",
        source = SecuritySignalSource.PLAY_INTEGRITY,
        ruleId = "integrity.$suffix",
        severity = severity,
        confidence = confidence,
        occurredAt = evaluatedAt,
        expiresAt = expiresAt,
        explanation = SecurityExplanation(
            titleKey = "$explanationPrefix.title",
            detailKey = "$explanationPrefix.detail",
        ),
    )
}
