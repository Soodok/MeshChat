package com.meshchat.app.mesh.service

import com.meshchat.app.mesh.identity.LocalIdentity
import com.meshchat.app.mesh.protocol.FileBody
import com.meshchat.app.mesh.protocol.FrameType
import com.meshchat.app.mesh.protocol.MeshEnvelope
import com.meshchat.app.mesh.protocol.MeshFrame
import com.meshchat.app.mesh.protocol.MeshJson
import com.meshchat.app.mesh.protocol.TextBody
import com.meshchat.app.mesh.routing.DedupCache
import com.meshchat.app.mesh.storage.InMemoryMeshStore
import com.meshchat.app.mesh.storage.MessageStatus
import com.meshchat.app.mesh.transfer.FileSaver
import com.meshchat.app.mesh.transport.InMemoryTransport
import com.meshchat.app.mesh.transport.MeshTransport
import java.io.File
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshServiceTest {
    private class FakeSaver(private val dir: File) : FileSaver {
        override fun save(tmpFile: File, fileName: String, mime: String): String {
            val target = File(dir, fileName)
            tmpFile.copyTo(target, overwrite = true)
            return target.absolutePath
        }
    }

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

        // 自环投递以「发送者短 ID」为会话键落库（conv-ME）
        val stored = store.observeMessages("conv-ME").first().first()
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

        // 收到对端确认：会话锁定、停止重发；接受方不再回发（防双方无限互发确认刷屏）
        service.handleFrame(ackFrame("OTHER", "ME"))
        assertEquals(setOf("OTHER"), service.sessions.value)
        assertEquals(3, transport.broadcastCount)

        service.tickSessionState(t0 + 800)
        assertEquals(3, transport.broadcastCount) // 已确认，不再重发

        // 新接受但一直未确认：超过重发窗口后自动停止
        val t1 = System.currentTimeMillis()
        service.acceptInvite("OTHER2")
        assertEquals(4, transport.broadcastCount)
        service.tickSessionState(t1 + ACK_RETRY_TIMEOUT_MS + 10)
        assertEquals(4, transport.broadcastCount)
    }

    @Test
    fun `initiator replies ack-of-ack exactly once to stop retry loop`() {
        val identity = LocalIdentity(shortId = "ME")
        val transport = CountingTransport()
        val service = MeshService(
            transport = transport, store = InMemoryMeshStore(), identity = identity, dedup = DedupCache(),
        )

        // 发起方：发送邀请（pendingInvites 记录、广播 INVITE）
        service.sendInvite("OTHER")
        assertEquals(1, transport.broadcastCount)

        // 收到接受方确认：锁定会话、回发一次 ack-of-ack 让对端停止重发
        service.handleFrame(ackFrame("OTHER", "ME"))
        assertEquals(2, transport.broadcastCount)
        assertEquals(setOf("OTHER"), service.sessions.value)
        assertEquals(0, service.pendingInvites.value.size)

        // 接受方重发的冗余确认：不再回发，防止无限互发
        service.handleFrame(ackFrame("OTHER", "ME"))
        assertEquals(2, transport.broadcastCount)
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
        service.handleFrame(ackFrame("OTHER", "ME")) // 接受方收到确认：不回发（防循环）
        assertEquals(1, transport.broadcastCount)

        // 对端因丢失确认而再次发起邀请：本机应重发确认并重启重发窗口
        service.handleFrame(inviteFrame("OTHER", "ME"))
        assertEquals(2, transport.broadcastCount)
        assertEquals(setOf("OTHER"), service.sessions.value)
        service.tickSessionState(t0 + 400)
        assertEquals(3, transport.broadcastCount) // 重发窗口已重启
    }

    @Test
    fun `invite addressed to another peer is ignored to avoid broadcast leak`() {
        val identity = LocalIdentity(shortId = "ME")
        val transport = CountingTransport()
        val service = MeshService(
            transport = transport, store = InMemoryMeshStore(), identity = identity, dedup = DedupCache(),
        )

        // 广播帧：邀请发往 OTHER2，本机（ME）不得弹窗（invites 保持空）
        service.handleFrame(inviteFrame("OTHER", "OTHER2"))
        assertEquals(0, service.invites.value.size)

        // 发往本机的邀请正常入队
        service.handleFrame(inviteFrame("OTHER", "ME"))
        assertEquals(setOf("OTHER"), service.invites.value.keys.toSet())

        // 确认帧同样做 dstId 过滤：发往 OTHER2 的确认不得触发会话/重发停止
        val t0 = System.currentTimeMillis()
        service.acceptInvite("OTHER")
        val before = transport.broadcastCount
        service.handleFrame(ackFrame("OTHER", "OTHER2"))
        assertEquals(before, transport.broadcastCount) // 非本机确认不触发 ack-of-ack
        service.tickSessionState(t0 + 400)
        assertEquals(before + 1, transport.broadcastCount) // 仍在重发
    }

    @Test
    fun `file chunk addressed to me is stored as file message`() = runTest {
        val identity = LocalIdentity(shortId = "ME")
        val transport = InMemoryTransport()
        val store = InMemoryMeshStore()
        val service = MeshService(
            transport = transport, store = store, identity = identity, dedup = DedupCache(),
            fileSaver = FakeSaver(kotlin.io.path.createTempDirectory("svc").toFile()),
        )
        service.start()
        val body = FileBody(
            fileId = "f-svc", fileName = "x.txt", mime = "text/plain",
            size = 100, totalChunks = 1, chunkIndex = 0,
            chunkData = Base64.getEncoder().encodeToString(ByteArray(100) { 7 }),
        )
        service.handleFrame(MeshFrame(
            FrameType.DATA,
            MeshJson.encodeEnvelope(MeshEnvelope(
                id = "e-1", kind = "FILE", srcId = "OTHER", dstId = "ME",
                convId = "conv-ME", ttl = 8, ts = 1, body = body,
            )).toByteArray(),
        ))
        val stored = store.observeMessages("conv-OTHER").first().first()
        assertEquals("FILE", stored.kind)
        assertEquals("f-svc", stored.id)
        assertEquals(MessageStatus.DELIVERED, stored.status)
        service.stop()
    }

    @Test
    fun `file chunk not addressed to me is ignored`() = runTest {
        val identity = LocalIdentity(shortId = "ME")
        val transport = InMemoryTransport()
        val store = InMemoryMeshStore()
        val service = MeshService(
            transport = transport, store = store, identity = identity, dedup = DedupCache(),
            fileSaver = FakeSaver(kotlin.io.path.createTempDirectory("svc2").toFile()),
        )
        service.start()
        val body = FileBody(
            fileId = "f-other", fileName = "x.txt", mime = "text/plain",
            size = 100, totalChunks = 1, chunkIndex = 0,
            chunkData = Base64.getEncoder().encodeToString(ByteArray(100) { 7 }),
        )
        service.handleFrame(MeshFrame(
            FrameType.DATA,
            MeshJson.encodeEnvelope(MeshEnvelope(
                id = "e-2", kind = "FILE", srcId = "OTHER", dstId = "OTHER2",
                convId = "conv-OTHER2", ttl = 8, ts = 1, body = body,
            )).toByteArray(),
        ))
        assertTrue(store.observeMessages("conv-OTHER").first().isEmpty())
        service.stop()
    }
}
