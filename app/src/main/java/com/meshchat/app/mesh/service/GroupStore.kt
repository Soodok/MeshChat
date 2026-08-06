package com.meshchat.app.mesh.service

import android.content.Context

/** 群组订阅/群名持久化（v1.1.50 群消息 MVP）：重启后恢复已订阅群与群名。 */
interface GroupStore {
    fun loadJoined(): Set<String>
    fun saveJoined(joined: Set<String>)
    fun loadNames(): Map<String, String>
    fun saveNames(names: Map<String, String>)
}

/** 测试/未注入时的默认空实现。 */
object NoopGroupStore : GroupStore {
    override fun loadJoined(): Set<String> = emptySet()
    override fun saveJoined(joined: Set<String>) {}
    override fun loadNames(): Map<String, String> = emptyMap()
    override fun saveNames(names: Map<String, String>) {}
}

/** SharedPreferences 实现：joined = 已订阅群 ID 集合；names 以 name_<groupId> 键存群名。 */
class SharedPrefsGroupStore(context: Context) : GroupStore {
    private val prefs = context.getSharedPreferences("meshchat_groups", Context.MODE_PRIVATE)

    override fun loadJoined(): Set<String> = prefs.getStringSet("joined", emptySet()) ?: emptySet()

    override fun saveJoined(joined: Set<String>) {
        prefs.edit().putStringSet("joined", joined).apply()
    }

    override fun loadNames(): Map<String, String> =
        prefs.all.entries
            .filter { it.key.startsWith(NAME_PREFIX) }
            .associate { it.key.removePrefix(NAME_PREFIX) to it.value.toString() }

    override fun saveNames(names: Map<String, String>) {
        prefs.edit().apply {
            names.forEach { (id, name) -> putString("$NAME_PREFIX$id", name) }
            apply()
        }
    }

    private companion object {
        const val NAME_PREFIX = "name_"
    }
}
