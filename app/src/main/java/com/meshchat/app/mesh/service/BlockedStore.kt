package com.meshchat.app.mesh.service

import android.content.Context

/** 拉黑（拒绝连接/消息）持久化（v1.1.64）：删除对话 = 拉黑对端，重启后保持。 */
interface BlockedStore {
    fun load(): Set<String>
    fun save(blocked: Set<String>)
}

/** 测试/未注入时的默认空实现。 */
object NoopBlockedStore : BlockedStore {
    override fun load(): Set<String> = emptySet()
    override fun save(blocked: Set<String>) {}
}

/** SharedPreferences 实现：blocked = 已拉黑短 ID 集合。 */
class SharedPrefsBlockedStore(context: Context) : BlockedStore {
    private val prefs = context.getSharedPreferences("meshchat_blocked", Context.MODE_PRIVATE)

    override fun load(): Set<String> = prefs.getStringSet(KEY_BLOCKED, emptySet()) ?: emptySet()

    override fun save(blocked: Set<String>) {
        prefs.edit().putStringSet(KEY_BLOCKED, blocked).apply()
    }

    private companion object {
        const val KEY_BLOCKED = "blocked"
    }
}
