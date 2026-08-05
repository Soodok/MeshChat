package com.meshchat.app.security.local

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.pm.ApplicationInfo
import android.app.KeyguardManager
import android.os.Debug
import android.view.accessibility.AccessibilityManager
import com.meshchat.app.mesh.protocol.MeshTransportSecurity
import com.meshchat.app.mesh.storage.MeshStorageSecurity
import com.meshchat.app.security.model.SecurityExplanation
import com.meshchat.app.security.model.SecuritySignal
import com.meshchat.app.security.model.SecuritySignalClassification
import com.meshchat.app.security.model.SecuritySignalSource

/**
 * Offline-only facts collected from public Android APIs. No app names, accessibility-service
 * identifiers, network endpoints, packet contents, or message data cross this boundary.
 */
data class LocalSecurityFacts(
    val debuggableBuild: Boolean,
    val accessibilityServiceEnabled: Boolean,
    val transportEncryptionEnforced: Boolean = true,
    val deviceSecure: Boolean = true,
    val debuggerAttached: Boolean = false,
    val messageStorageEncrypted: Boolean = true,
)

fun interface LocalSecuritySignalCollector {
    fun collect(now: Long): List<SecuritySignal>
}

/** Pure policy so the meaning of every local observation remains reviewable and testable. */
object LocalSecuritySignalPolicy {
    private const val CHECK_WINDOW_MILLIS = 24 * 60 * 60 * 1000L

    fun collect(facts: LocalSecurityFacts, now: Long): List<SecuritySignal> = buildList {
        if (!facts.transportEncryptionEnforced) {
            add(signal(
                id = "local-plaintext-mesh-transport",
                ruleId = "end-to-end-encryption-unavailable",
                severity = 90,
                confidence = 100,
                now = now,
                category = "transport-encryption",
                classification = SecuritySignalClassification.PROTECTION_GAP,
            ))
        }
        if (!facts.messageStorageEncrypted) {
            add(signal(
                id = "local-plaintext-message-storage",
                ruleId = "application-message-storage-encryption-unavailable",
                severity = 70,
                confidence = 90,
                now = now,
                category = "storage-encryption",
                classification = SecuritySignalClassification.PROTECTION_GAP,
            ))
        }
        if (!facts.deviceSecure) {
            add(signal(
                id = "local-device-screen-lock-disabled",
                ruleId = "device-screen-lock-disabled",
                severity = 40,
                confidence = 100,
                now = now,
                category = "device-protection",
                classification = SecuritySignalClassification.PROTECTION_GAP,
            ))
        }
        if (facts.debuggerAttached) {
            add(signal(
                id = "local-debugger-attached",
                ruleId = "debugger-attached",
                severity = 50,
                confidence = 100,
                now = now,
                category = "app-hardening",
                classification = SecuritySignalClassification.PROTECTION_GAP,
            ))
        }
        if (facts.debuggableBuild) {
            add(signal(
                id = "local-debuggable-build",
                ruleId = "debuggable-build-enabled",
                severity = 45,
                confidence = 100,
                now = now,
                category = "app-hardening",
                classification = SecuritySignalClassification.PROTECTION_GAP,
            ))
        }
        if (facts.accessibilityServiceEnabled) {
            // Accessibility services are often legitimate. This is a low-confidence review cue,
            // not an accusation that a service controls the device.
            add(signal(
                id = "local-accessibility-enabled",
                ruleId = "accessibility-service-enabled",
                severity = 20,
                confidence = 40,
                now = now,
                category = "device-configuration",
                classification = SecuritySignalClassification.INFORMATIONAL,
            ))
        }
    }

    private fun signal(
        id: String,
        ruleId: String,
        severity: Int,
        confidence: Int,
        now: Long,
        category: String,
        classification: SecuritySignalClassification,
    ) = SecuritySignal(
        id = id,
        source = SecuritySignalSource.LOCAL_POLICY,
        ruleId = ruleId,
        severity = severity,
        confidence = confidence,
        occurredAt = now,
        expiresAt = now + CHECK_WINDOW_MILLIS,
        explanation = SecurityExplanation(
            titleKey = "security.signal.$ruleId.title",
            detailKey = "security.signal.$ruleId.detail",
        ),
        redactedEvidence = mapOf(
            "signal_type" to ruleId,
            "source_class" to "local-platform",
            "confidence_bucket" to if (confidence >= 80) "high" else "low",
            "risk_category" to category,
            "policy_version" to "local-v1",
        ),
        classification = classification,
    )
}

class AndroidLocalSecuritySignalCollector(context: Context) : LocalSecuritySignalCollector {
    private val appContext = context.applicationContext

    override fun collect(now: Long): List<SecuritySignal> = LocalSecuritySignalPolicy.collect(
        facts = LocalSecurityFacts(
            debuggableBuild = appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
            accessibilityServiceEnabled = enabledAccessibilityServiceExists(),
            transportEncryptionEnforced = MeshTransportSecurity.END_TO_END_ENCRYPTION_ENFORCED,
            deviceSecure = appContext.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true,
            debuggerAttached = Debug.isDebuggerConnected(),
            messageStorageEncrypted = MeshStorageSecurity.APP_LEVEL_MESSAGE_ENCRYPTION_ENFORCED,
        ),
        now = now,
    )

    private fun enabledAccessibilityServiceExists(): Boolean = runCatching {
        appContext.getSystemService(AccessibilityManager::class.java)
            ?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            ?.isNotEmpty() == true
    }.getOrDefault(false)
}
