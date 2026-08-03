package com.meshchat.app.mesh.service

import com.meshchat.app.mesh.identity.LocalIdentity
import com.meshchat.app.mesh.protocol.FrameType
import com.meshchat.app.mesh.protocol.MeshEnvelope
import com.meshchat.app.mesh.protocol.MeshFrame
import com.meshchat.app.mesh.protocol.MeshJson
import com.meshchat.app.mesh.protocol.TextBody
import com.meshchat.app.mesh.routing.DedupCache
import com.meshchat.app.mesh.storage.InMemoryMeshStore
import com.meshchat.app.mesh.storage.MessageStatus
import com.meshchat.app.mesh.transport.InMemoryTransport
import com.meshchat.app.mesh.transport.MeshTransport
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class MeshServiceTest {
    /** 统计广播次数的传输替身：验证握手确认的重发与停止。 */
    private class CountingTransport : MeshTransport {
        private val inner = InMemoryTransport()
        var broadcastCount = 0
        override val incoming = inner.incoming
        override val foundPeers = inner.foundPeers
        override fun start() = inner.start()
        override fun stop() = inner.stop()
        override fun broadcast(frame: MeshFrame) {
            broadcastCount++
            inner.broadcast(frame)
        }
        override fun sendTo(peerId: String, frame: MeshFrame) = inner.sendTo(peerId, frame)
    }

    private fun ackFrame(srcId: String, dstId: String) = MeshFrame(
        FrameType.DATA,
        MeshJson.encodeEnvelope(
            MeshEnvelope(
                id = UUID.randomUUID().toString(), kind = "INVITE_ACK",
                srcId = srcId, dstId = dstId, convId = "conv-$dstId",
                ttl = 8, ts = 0, body = TextBody("已接受"),
            ),
        ).toByteArray(),
    )

    private fun inviteFrame(srcId: String, dstId: String) = MeshFrame(
        FrameType.DATA,
        MeshJson.encodeEnvelope(
            MeshEnvelope(
                id = UUID.randomUUID().toString(), kind = "INVITE",
                srcId = srcId, dstId = dstId, convId = "conv-$dstId",
                ttl = 8, ts = 0, body = TextBody("对话请求"),
            ),
        ).toByteArray(),
    )

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

    @Test
    fun `accept invite resends ack until peer confirms or timeout`() {
        val identity = LocalIdentity(shortId = "ME")
        val transport = CountingTransport()
        val service = MeshService(
            transport = transport, store = InMemoryMeshStore(), identity = identity, dedup = DedupCache(),
        )
        // 不调用 start()：直接驱动状态机与帧处理，避免真实 tick 干扰计数

        val t0 = System.currentTimeMillis()
        service.acceptInvite("OTHER")
        assertEquals(1, transport.broadcastCount) // 接受时立即回发一次确认

        service.tickSessionState(t0 + 400)        // 0.2s 检测：持续重发
        service.tickSessionState(t0 + 600)
        assertEquals(3, transport.broadcastCount)

        // 对端确认到达：建立会话、停止重发，并回发一次确认（ack-of-ack）
        service.handleFrame(ackFrame("OTHER", "ME"))
        assertEquals(setOf("OTHER"), service.sessions.value)
        assertEquals(4, transport.broadcastCount)

        service.tickSessionState(t0 + 800)
        assertEquals(4, transport.broadcastCount) // 已确认，不再重发

        // 新接受但一直未确认：超过重发窗口后自动停止
        val t1 = System.currentTimeMillis()
        service.acceptInvite("OTHER2")
        assertEquals(5, transport.broadcastCount)
        service.tickSessionState(t1 + ACK_RETRY_TIMEOUT_MS + 10)
        assertEquals(5, transport.broadcastCount)
    }

    @Test
    fun `repeated invite from sessioned peer re-sends ack for convergence`() {
        val identity = LocalIdentity(shortId = "ME")
        val transport = CountingTransport()
        val service = MeshService(
            transport = transport, store = InMemoryMeshStore(), identity = identity, dedup = DedupCache(),
        )

        val t0 = System.currentTimeMillis()
        service.acceptInvite("OTHER")             // 接受并回发确认
        service.handleFrame(ackFrame("OTHER", "ME")) // 对端确认已收到（ack-of-ack）
        assertEquals(2, transport.broadcastCount)

        // 对端因丢失确认而再次发起邀请：本机应重发确认并重启重发窗口
        service.handleFrame(inviteFrame("OTHER", "ME"))
        assertEquals(3, transport.broadcastCount)
        assertEquals(setOf("OTHER"), service.sessions.value)
        service.tickSessionState(t0 + 400)
        assertEquals(4, transport.broadcastCount) // 重发窗口已重启
    }
}
