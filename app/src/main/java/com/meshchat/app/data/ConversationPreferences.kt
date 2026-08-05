package com.meshchat.app.data

import android.content.Context

/** Persists UI-only conversation state separately from Mesh transport records. */
class ConversationPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("meshchat_conversations", Context.MODE_PRIVATE)

    fun archivedIds(): Set<String> = prefs.getStringSet("archived_ids", emptySet()) ?: emptySet()

    fun setArchived(id: String, archived: Boolean) {
        val next = archivedIds().toMutableSet().apply { if (archived) add(id) else remove(id) }
        prefs.edit().putStringSet("archived_ids", next).apply()
    }

    fun readTimes(): Map<String, Long> = prefs.all
        .filterKeys { it.startsWith("read_") }
        .mapValues { (_, value) -> value as? Long ?: 0L }

    fun markRead(id: String, timestamp: Long) {
        prefs.edit().putLong("read_$id", timestamp).apply()
    }
}
