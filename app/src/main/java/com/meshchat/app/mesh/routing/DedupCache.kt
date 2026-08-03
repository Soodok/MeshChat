package com.meshchat.app.mesh.routing

class DedupCache(private val capacity: Int = 512) {
    // accessOrder=true 时迭代顺序为最近使用序，首元素即最久未用
    private val seen = LinkedHashMap<String, Boolean>(capacity, 0.75f, true)

    fun contains(id: String): Boolean = seen.containsKey(id)

    fun mark(id: String) {
        seen[id] = true
        while (seen.size > capacity) {
            seen.remove(seen.keys.first())
        }
    }
}
