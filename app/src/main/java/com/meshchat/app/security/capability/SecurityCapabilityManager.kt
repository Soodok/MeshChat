package com.meshchat.app.security.capability

import com.meshchat.app.security.model.SecurityCapability
import com.meshchat.app.security.model.SecurityCapabilityState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Reads platform availability only; requesting a system dialog remains a user-initiated UI action. */
fun interface SecurityCapabilityStateReader {
    fun readStates(): Map<SecurityCapability, SecurityCapabilityState>
}

/** Stores only a local denial timestamp. It must never store permission rationale text or user data. */
interface CapabilityPromptStore {
    fun lastDeniedAt(capability: SecurityCapability): Long?
    fun setLastDeniedAt(capability: SecurityCapability, timestamp: Long)
    fun clearDeniedAt(capability: SecurityCapability)
}

class InMemoryCapabilityPromptStore : CapabilityPromptStore {
    private val deniedAt = mutableMapOf<SecurityCapability, Long>()

    override fun lastDeniedAt(capability: SecurityCapability): Long? = deniedAt[capability]

    override fun setLastDeniedAt(capability: SecurityCapability, timestamp: Long) {
        deniedAt[capability] = timestamp
    }

    override fun clearDeniedAt(capability: SecurityCapability) {
        deniedAt.remove(capability)
    }
}

enum class CapabilityRecovery {
    NONE,
    WAIT,
    REQUEST_PERMISSION,
    OPEN_SYSTEM_SETTINGS,
}

data class SecurityCapabilityStatus(
    val capability: SecurityCapability,
    val state: SecurityCapabilityState,
    val isOptional: Boolean,
    val canRequest: Boolean,
    val recovery: CapabilityRecovery,
    val lastDeniedAt: Long? = null,
    val retryAt: Long? = null,
)

/**
 * Combines observable system state with the app's own denial cooldown. A denied optional
 * capability only reduces security coverage; this class has no mechanism to block chat.
 */
class SecurityCapabilityManager(
    private val stateReader: SecurityCapabilityStateReader,
    private val promptStore: CapabilityPromptStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val retryCooldownMs: Long = DEFAULT_RETRY_COOLDOWN_MS,
) {
    init {
        require(retryCooldownMs >= 0L)
    }

    private val _status = MutableStateFlow<Map<SecurityCapability, SecurityCapabilityStatus>>(emptyMap())
    val status: StateFlow<Map<SecurityCapability, SecurityCapabilityStatus>> = _status.asStateFlow()

    init {
        refresh()
    }

    fun refresh(): Map<SecurityCapability, SecurityCapabilityStatus> {
        val now = clock()
        val platformStates = stateReader.readStates()
        platformStates.filterValues { it == SecurityCapabilityState.GRANTED }
            .keys
            .forEach(promptStore::clearDeniedAt)
        return SecurityCapability.entries.associateWith { capability ->
            createStatus(capability, platformStates[capability] ?: SecurityCapabilityState.UNSUPPORTED, now)
        }.also { _status.value = it }
    }

    fun recordDenied(capabilities: Set<SecurityCapability>) {
        val now = clock()
        capabilities.forEach { promptStore.setLastDeniedAt(it, now) }
        refresh()
    }

    fun recordGranted(capabilities: Set<SecurityCapability>) {
        capabilities.forEach(promptStore::clearDeniedAt)
        refresh()
    }

    private fun createStatus(
        capability: SecurityCapability,
        platformState: SecurityCapabilityState,
        now: Long,
    ): SecurityCapabilityStatus {
        val deniedAt = promptStore.lastDeniedAt(capability)
        val retryAt = deniedAt?.plus(retryCooldownMs)
        val isPermissionPending = platformState == SecurityCapabilityState.AVAILABLE
        val wasDenied = isPermissionPending && deniedAt != null
        val state = if (wasDenied) SecurityCapabilityState.DENIED else platformState
        val canRequest = isPermissionPending && (retryAt == null || now >= retryAt)
        val recovery = when {
            platformState == SecurityCapabilityState.UNSUPPORTED || platformState == SecurityCapabilityState.NOT_CONFIGURED -> CapabilityRecovery.NONE
            platformState == SecurityCapabilityState.CONFLICTED || platformState == SecurityCapabilityState.DENIED -> CapabilityRecovery.OPEN_SYSTEM_SETTINGS
            canRequest -> CapabilityRecovery.REQUEST_PERMISSION
            wasDenied -> CapabilityRecovery.WAIT
            else -> CapabilityRecovery.NONE
        }
        return SecurityCapabilityStatus(
            capability = capability,
            state = state,
            isOptional = capability != SecurityCapability.BLUETOOTH,
            canRequest = canRequest,
            recovery = recovery,
            lastDeniedAt = deniedAt,
            retryAt = retryAt,
        )
    }

    private companion object {
        const val DEFAULT_RETRY_COOLDOWN_MS = 24L * 60L * 60L * 1_000L
    }
}
