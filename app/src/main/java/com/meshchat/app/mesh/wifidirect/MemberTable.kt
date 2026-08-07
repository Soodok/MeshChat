package com.meshchat.app.mesh.wifidirect

import java.util.concurrent.ConcurrentHashMap

/** 星域成员表：shortId → (ip, port, name, lastSeen)。REGISTER 帧驱动，超时清理（纯 JVM，可单测）。 */
class MemberTable(private val timeoutMs: Long = 15_000L) {
    private data class Entry(val ip: String, val port: Int, val name: String, var lastSeen: Long)

    private val members = ConcurrentHashMap<String, Entry>()

    fun upsert(shortId: String, ip: String, port: Int, name: String, now: Long) {
        members[shortId] = Entry(ip, port, name, now)
    }

    /** 返回 (ip, port, name)；未知返回 null。 */
    fun addressFor(shortId: String): Triple<String, Int, String>? =
        members[shortId]?.let { Triple(it.ip, it.port, it.name) }

    fun members(): Set<String> = members.keys.toSet()

    /** 清理超过 timeoutMs 未刷新的成员；返回被移除的 shortId 列表。 */
    fun prune(now: Long): List<String> {
        val cutoff = now - timeoutMs
        val removed = mutableListOf<String>()
        for ((id, e) in members) {
            if (e.lastSeen < cutoff) removed.add(id)
        }
        removed.forEach { members.remove(it) }
        return removed
    }

    fun clear() = members.clear()
}
