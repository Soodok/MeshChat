package com.meshchat.app.security.local

import com.meshchat.app.security.model.SecurityEvent
import com.meshchat.app.security.risk.SecurityEventStore
import com.meshchat.app.security.risk.SecurityRiskEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSecurityCoordinatorTest {
    private val now = 1_000_000L

    @Test
    fun `refresh stays local and persists only the generated redacted event`() {
        val store = MemoryStore()
        val coordinator = coordinator(store, LocalSecurityFacts(debuggableBuild = true, accessibilityServiceEnabled = false, transportEncryptionEnforced = true))

        val snapshot = coordinator.refresh()

        assertEquals(LocalSecurityStorageState.READY, snapshot.storageState)
        assertEquals(listOf("debuggable-build-enabled"), snapshot.events.map { it.ruleId })
        assertEquals(1, store.events.size)
    }

    @Test
    fun `storage failure is shown and does not erase the current in-memory finding`() {
        val coordinator = coordinator(FailingStore(), LocalSecurityFacts(debuggableBuild = true, accessibilityServiceEnabled = false, transportEncryptionEnforced = true))

        val snapshot = coordinator.refresh()

        assertEquals(LocalSecurityStorageState.UNAVAILABLE, snapshot.storageState)
        assertEquals(listOf("debuggable-build-enabled"), snapshot.events.map { it.ruleId })
    }

    @Test
    fun `delete removes encrypted-history abstraction without changing active assessment`() {
        val store = MemoryStore()
        val coordinator = coordinator(store, LocalSecurityFacts(debuggableBuild = true, accessibilityServiceEnabled = false, transportEncryptionEnforced = true))
        coordinator.refresh()

        val snapshot = coordinator.deleteLocalHistory()

        assertTrue(snapshot.events.isEmpty())
        assertTrue(store.events.isEmpty())
        assertEquals(0, snapshot.assessment.score)
    }

    private fun coordinator(store: SecurityEventStore, facts: LocalSecurityFacts) = LocalSecurityCoordinator(
        signalCollector = LocalSecuritySignalCollector { LocalSecuritySignalPolicy.collect(facts, now) },
        riskEngine = SecurityRiskEngine(),
        eventStore = store,
        now = { now },
    )

    private class MemoryStore : SecurityEventStore {
        val events = mutableListOf<SecurityEvent>()
        override fun read(): List<SecurityEvent> = events.toList()
        override fun upsert(event: SecurityEvent) { events.removeAll { it.id == event.id }; events += event }
        override fun pruneExpired(now: Long) { events.removeAll { it.expiresAt <= now } }
        override fun deleteAll() { events.clear() }
    }

    private class FailingStore : SecurityEventStore {
        override fun read(): List<SecurityEvent> = error("store unavailable")
        override fun upsert(event: SecurityEvent) = error("store unavailable")
        override fun pruneExpired(now: Long) = error("store unavailable")
        override fun deleteAll() = error("store unavailable")
    }
}
