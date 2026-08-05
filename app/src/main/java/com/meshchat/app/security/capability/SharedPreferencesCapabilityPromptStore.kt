package com.meshchat.app.security.capability

import android.content.Context
import com.meshchat.app.security.model.SecurityCapability

/** App-private, non-sensitive preference: only records when a user dismissed an optional prompt. */
class SharedPreferencesCapabilityPromptStore(context: Context) : CapabilityPromptStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun lastDeniedAt(capability: SecurityCapability): Long? =
        preferences.getLong(keyFor(capability), NO_TIMESTAMP).takeUnless { it == NO_TIMESTAMP }

    override fun setLastDeniedAt(capability: SecurityCapability, timestamp: Long) {
        preferences.edit().putLong(keyFor(capability), timestamp).apply()
    }

    override fun clearDeniedAt(capability: SecurityCapability) {
        preferences.edit().remove(keyFor(capability)).apply()
    }

    private fun keyFor(capability: SecurityCapability): String = "denied_at_${capability.name.lowercase()}"

    private companion object {
        const val PREFERENCES_NAME = "meshchat_security_capabilities"
        const val NO_TIMESTAMP = -1L
    }
}
