package com.meshchat.app.mesh.service

import com.meshchat.app.mesh.identity.LocalIdentity
import com.meshchat.app.mesh.protocol.FrameType
import com.meshchat.app.mesh.protocol.MeshJson
import com.meshchat.app.mesh.routing.DedupCache
import com.meshchat.app.mesh.storage.InMemoryMeshStore
import com.meshchat.app.mesh.storage.MessageStatus
import com.meshchat.app.mesh.transport.InMemoryTransport
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class MeshServiceTest {
    @Test
    fun `self loop delivers message and marks delivered`() = runTest {
        val identity = LocalIdentity(shortId = "ME")
        val transport = InMemoryTransport()
        val store = InMemoryMeshStore()
        val service = MeshService(
            transport = transport,
            store = store,
            identity = identity,
            dedup = DedupCache(),
        )
        service.start()

        service.sendText(convId = "c1", dstId = "ME", text = "你好")

        val stored = store.observeMessages("c1").first().first()
        assertEquals("你好", stored.text)
        assertEquals(MessageStatus.DELIVERED, stored.status)

        service.stop()
    }

    @Test
    fun `message for other peer is forwarded with decremented ttl`() = runTest {
        val identity = LocalIdentity(shortId = "ME")
        val transport = InMemoryTransport()
        val store = InMemoryMeshStore()
        val service = MeshService(
            transport = transport, store = store, identity = identity, dedup = DedupCache(),
        )
        service.start()

        service.sendText(convId = "c2", dstId = "OTHER", text = "hello")
        val frame = transport.incoming.replayCache.firstOrNull { it.type == FrameType.DATA }
        val envelope = frame?.let { MeshJson.decodeEnvelope(it.payloadText) }
        assertEquals("OTHER", envelope?.dstId)
        assertEquals(7, envelope?.ttl)

        service.stop()
    }
}
