package com.meshchat.app.data

import com.meshchat.app.mesh.identity.LocalIdentity
import com.meshchat.app.mesh.protocol.FrameType
import com.meshchat.app.mesh.protocol.MeshEnvelope
import com.meshchat.app.mesh.protocol.MeshFrame
import com.meshchat.app.mesh.protocol.MeshJson
import com.meshchat.app.mesh.protocol.PresenceBody
import com.meshchat.app.mesh.routing.DedupCache
import com.meshchat.app.mesh.service.MeshService
import com.meshchat.app.mesh.storage.InMemoryMeshStore
import com.meshchat.app.mesh.storage.StoredMessage
import com.meshchat.app.mesh.transport.InMemoryTransport
import com.meshchat.app.mesh.transport.MeshTransport
import com.meshchat.app.mesh.transport.PeerPresence
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshRepositoryTest {

    private class CountingTransport : MeshTransport {
        private val inner = InMemoryTransport()
        override val incoming = inner.incoming
        override val foundPeers = inner.foundPeers
        override fun start() = inner.start()
        override fun stop() = inner.stop()
        override fun broadcast(frame: MeshFrame) = inner.broadcast(frame)
        override fun sendTo(peerId: String, frame: MeshFrame) = inner.sendTo(peerId, frame)
    }

    private fun pingFrame(srcId: String, name: String) = MeshFrame(
        FrameType.DATA,
        MeshJson.encodeEnvelope(
            MeshEnvelope(
                id = UUID.randomUUID().toString(), kind = "PING",
                srcId = srcId, dstId = "", convId = "conv-$srcId",
                ttl = 8, ts = System.currentTimeMillis(), body = PresenceBody(name),
            ),
        ).toByteArray(),
    )

    @Test
    fun `conversations fall back to message history when sessions empty`() = runTest {
        val store = InMemoryMeshStore()
        store.insertMessage(
            StoredMessage(id = "m1", convId = "conv-OTHER", kind = "TEXT", srcId = "ME", dstId = "OTHER", text = "hi", ts = 1_000),
        )
        val repo = MeshRepositoryImpl(
            MeshService(transport = CountingTransport(), store = store, identity = LocalIdentity("ME"), dedup = DedupCache()),
            store,
        )
        val convs = repo.observeConversations().first()
        assertTrue("会话关系为空时，消息历史应反推最近对话（持久化兜底）", convs.any { it.id == "OTHER" })
        val c = convs.first { it.id == "OTHER" }
        assertEquals("历史反推但本会话未见，应标记寻找中", PeerPresence.SEARCHING, c.presence)
        assertEquals(Reachability.QUEUED, c.reachability)
    }

    @Test
    fun `conversation presence reflects peer presence and name`() = runTest {
        val store = InMemoryMeshStore()
        val service = MeshService(transport = CountingTransport(), store = store, identity = LocalIdentity("ME"), dedup = DedupCache())
        service.acceptInvite("OTHER")
        val repo = MeshRepositoryImpl(service, store)
        service.handleFrame(pingFrame("OTHER", "老王"))
        val convs = repo.observeConversations().first { list -> list.any { it.id == "OTHER" && it.presence == PeerPresence.ONLINE } }
        val c = convs.first { it.id == "OTHER" }
        assertEquals("老王", c.name)
        assertEquals("在线心跳应标记绿色", PeerPresence.ONLINE, c.presence)
        assertEquals(Reachability.REACHABLE, c.reachability)
    }
}
