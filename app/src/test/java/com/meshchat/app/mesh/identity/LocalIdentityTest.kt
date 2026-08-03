package com.meshchat.app.mesh.identity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalIdentityTest {
    @Test
    fun `short id is non empty and unique`() {
        val ids = (1..100).map { LocalIdentity().shortId }.toSet()
        assertEquals(100, ids.size)
        assertTrue(ids.all { it.length == 4 })
    }

    @Test
    fun `registry upsert and query`() {
        val registry = PeerRegistry()
        registry.upsert(PeerRecord("A001", "林宇航", lastSeen = 100, hops = 1))
        assertEquals("林宇航", registry.get("A001")?.displayName)
    }

    @Test
    fun `registry prune removes stale peers`() {
        val registry = PeerRegistry()
        registry.upsert(PeerRecord("A001", "p1", lastSeen = 100, hops = 1))
        registry.upsert(PeerRecord("B002", "p2", lastSeen = 10_000, hops = 2))
        registry.prune(now = 11_000, timeoutMillis = 5_000)
        assertNull(registry.get("A001"))
        assertEquals("p2", registry.get("B002")?.displayName)
    }
}
