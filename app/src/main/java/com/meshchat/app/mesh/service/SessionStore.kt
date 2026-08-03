package com.meshchat.app.mesh.service

import android.content.Context

/** 会话关系（短 ID 集合）持久化：重启后恢复已建立的对话关系。 */
interface SessionStore {
    fun load(): Set<String>
    fun save(sessions: Set<String>)
}

/** SharedPreferences 实现：会话是内存态之外的一层镜像，重启恢复用。 */
class SharedPrefsSessionStore(context: Context) : SessionStore {
    private val prefs = context.getSharedPreferences("meshchat_sessions", Context.MODE_PRIVATE)

    override fun load(): Set<String> = prefs.getStringSet("sessions", emptySet()) ?: emptySet()

    override fun save(sessions: Set<String>) {
        prefs.edit().putStringSet("sessions", sessions).apply()
    }
}
