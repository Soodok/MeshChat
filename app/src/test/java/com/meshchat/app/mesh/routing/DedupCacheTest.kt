package com.meshchat.app.mesh.routing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DedupCacheTest {
    @Test
    fun `mark then contains returns true`() {
        val cache = DedupCache()
        assertFalse(cache.contains("m1"))
        cache.mark("m1")
        assertTrue(cache.contains("m1"))
    }

    @Test
    fun `capacity evicts least recently used`() {
        val cache = DedupCache(capacity = 2)
        cache.mark("m1")
        cache.mark("m2")
        cache.mark("m3")          // 淘汰 m1
        assertFalse(cache.contains("m1"))
        assertTrue(cache.contains("m2"))
        assertTrue(cache.contains("m3"))
    }
}
