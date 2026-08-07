package com.meshchat.app.mesh.wifidirect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemberTableTest {
    @Test fun `upsert adds and updates member`() {
        val t = MemberTable(timeoutMs = 15_000L)
        t.upsert("AB12", "192.168.49.5", 9876, "节点AB12", now = 1_000L)
        assertEquals(setOf("AB12"), t.members())
        assertEquals(Triple("192.168.49.5", 9876, "节点AB12"), t.addressFor("AB12"))
        t.upsert("AB12", "192.168.49.9", 9999, "节点AB12x", now = 2_000L)  // 更新（GO 重新分配 IP）
        assertEquals(Triple("192.168.49.9", 9999, "节点AB12x"), t.addressFor("AB12"))
    }

    @Test fun `multiple members coexist`() {
        val t = MemberTable()
        t.upsert("AB12", "192.168.49.5", 9876, "", now = 1_000L)
        t.upsert("CD34", "192.168.49.6", 9877, "", now = 1_000L)
        assertEquals(setOf("AB12", "CD34"), t.members())
    }

    @Test fun `expired member pruned`() {
        val t = MemberTable(timeoutMs = 15_000L)
        t.upsert("AB12", "192.168.49.5", 9876, "", now = 1_000L)
        val removed = t.prune(now = 20_000L)   // 19s > 15s 超时
        assertEquals(listOf("AB12"), removed)
        assertTrue(t.members().isEmpty())
        assertNull(t.addressFor("AB12"))
    }

    @Test fun `fresh member survives prune`() {
        val t = MemberTable(timeoutMs = 15_000L)
        t.upsert("AB12", "192.168.49.5", 9876, "", now = 10_000L)
        assertEquals(emptyList<String>(), t.prune(now = 20_000L))  // 10s < 15s
        assertEquals("192.168.49.5", t.addressFor("AB12")?.first)
    }

    @Test fun `upsert refreshes lastSeen and survives prune`() {
        val t = MemberTable(timeoutMs = 15_000L)
        t.upsert("AB12", "192.168.49.5", 9876, "", now = 1_000L)
        t.upsert("AB12", "192.168.49.5", 9876, "", now = 19_000L)  // 周期 REGISTER 刷新
        assertEquals(emptyList<String>(), t.prune(now = 20_000L))
    }

    @Test fun `clear removes all`() {
        val t = MemberTable()
        t.upsert("AB12", "192.168.49.5", 9876, "", now = 1_000L)
        t.clear()
        assertTrue(t.members().isEmpty())
    }
}
