package com.meshchat.app.mesh.service

import com.meshchat.app.mesh.channel.ChannelFingerprint
import com.meshchat.app.mesh.crypto.MeshCrypto
import com.meshchat.app.mesh.identity.LocalIdentity
import com.meshchat.app.mesh.protocol.EnvelopeBody
import com.meshchat.app.mesh.protocol.File3
import com.meshchat.app.mesh.protocol.FileBody
import com.meshchat.app.mesh.protocol.FrameType
import com.meshchat.app.mesh.protocol.GroupBody
import com.meshchat.app.mesh.protocol.MeshEnvelope
import com.meshchat.app.mesh.protocol.MeshFrame
import com.meshchat.app.mesh.protocol.MeshJson
import com.meshchat.app.mesh.protocol.PresenceBody
import com.meshchat.app.mesh.protocol.SecBody
import com.meshchat.app.mesh.protocol.TextBody
import com.meshchat.app.mesh.routing.DedupCache
import com.meshchat.app.mesh.storage.InMemoryMeshStore
import com.meshchat.app.mesh.storage.MessageStatus
import com.meshchat.app.mesh.storage.StoredMessage
import com.meshchat.app.mesh.transfer.FileSaver
import com.meshchat.app.mesh.transport.DiscoveryMode
import com.meshchat.app.mesh.transport.InMemoryTransport
import com.meshchat.app.mesh.transport.MeshPeerInfo
import com.meshchat.app.mesh.transport.MeshTransport
import com.meshchat.app.mesh.transport.PeerPresence
import java.io.File
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    /** 统计广播次数 + 记录帧的传输替身：验证心跳重发与停止。 */
    private class CountingTransport : MeshTransport {
        private val inner = InMemoryTransport()
        var broadcastCount = 0
        var frames = mutableListOf<MeshFrame>()
        override val incoming = inner.incoming
        override val foundPeers = inner.foundPeers
        var startCount = 0
        var stopCount = 0
        override fun start() { startCount++; inner.start() }
        override fun stop() { stopCount++; inner.stop() }
        override fun broadcast(frame: MeshFrame) {
            broadcastCount++
            frames.add(frame)
            inner.broadcast(frame)
        }
        override fun sendTo(peerId: String, frame: MeshFrame) = inner.sendTo(peerId, frame)
        /** 文件数据块走无确认写（v1.1.27）：测试替身 = 普通广播（记录 + 回环）。 */
        override fun writeUnreliable(frame: MeshFrame) {
            frames.add(frame)
            inner.broadcast(frame)
        }
        /** 测试辅助：模拟扫描发现节点（可携带广播确认键）。 */
        fun emitPeer(info: MeshPeerInfo) = inner.emitPeer(info)
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

        service.seedSessionKeyForTesting("OTHER")   // v1.1.57 E2EE：非自环发送需会话密钥
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

    private class MemorySessionStore : SessionStore {
        val saved = mutableListOf<Set<String>>()
        var stored: Set<String> = emptySet()
        override fun load(): Set<String> = stored
        override fun save(sessions: Set<String>) {
            saved.add(sessions)
            stored = sessions
        }
    }

    @Test
    fun `sessions are saved on accept and restored on start`() {
        val identity = LocalIdentity(shortId = "ME")
        val transport = CountingTransport()
        val sessionStore = MemorySessionStore()
        val service = MeshService(
            transport = transport, store = InMemoryMeshStore(), identity = identity, dedup = DedupCache(),
            sessionStore = sessionStore,
        )
        service.acceptInvite("OTHER")
        assertTrue("acceptInvite 后应保存会话", sessionStore.stored.contains("OTHER"))

        // 新实例（模拟重启）从同一 store 恢复
        val restarted = MeshService(
            transport = CountingTransport(), store = InMemoryMeshStore(), identity = identity, dedup = DedupCache(),
            sessionStore = sessionStore,
        )
        restarted.start()
        assertEquals(setOf("OTHER"), restarted.sessions.value)
        restarted.stop()
    }

    @Test
    fun `file chunks go through writeUnreliable even when rfcomm connected`() = runTest {
        val identity = LocalIdentity(shortId = "ME")
        val transport = CountingTransport()
        val store = InMemoryMeshStore()
        val rfcommSent = mutableListOf<Pair<String, MeshFrame>>()
        val rfcomm = object : RfcommChannel {
            override val incoming = MutableSharedFlow<MeshFrame>()
            override fun start() {}
            override fun stop() {}
            override suspend fun connect(peerId: String, address: String) = false
            override fun isConnectedTo(peerId: String) = peerId == "OTHER"
            override fun sendTo(peerId: String, frame: MeshFrame) { rfcommSent.add(peerId to frame) }
        }
        val service = MeshService(
            transport = transport, store = store, identity = identity, dedup = DedupCache(),
            rfcomm = rfcomm,
        )
        service.sendFile("conv-OTHER", "OTHER", { java.io.ByteArrayInputStream(ByteArray(100) { 1 }) }, "f.txt", "text/plain", 100)
        // v1.1.28：文件数据块统一走 writeUnreliable（无确认写）且为 FILE3 二进制帧（MC3 魔数），不再经 rfcomm/sendFrame。
        // MeshService 内部 scope 为 Dispatchers.Default（真实节流）→ 用真实等待而非测试虚拟时钟
        var guard = 0
        fun isFile3(frame: MeshFrame) = File3.isFile3(frame.payload)
        while (transport.frames.none(::isFile3) && guard++ < 100) Thread.sleep(20)
        assertTrue("文件块应经 writeUnreliable 发出（FILE3 帧）", transport.frames.any(::isFile3))
        assertEquals("数据块不再走 rfcomm sendTo", 0, rfcommSent.size)
        service.stop()
    }

    private fun pingFrame(srcId: String, name: String, relays: List<String> = emptyList()) = MeshFrame(
        FrameType.DATA,
        MeshJson.encodeEnvelope(
            MeshEnvelope(
                id = UUID.randomUUID().toString(), kind = "PING",
                srcId = srcId, dstId = "", convId = "conv-$srcId",
                ttl = 8, ts = 0, body = PresenceBody(displayName = name, relays = relays),
            ),
        ).toByteArray(),
    )

    private fun pongFrame(srcId: String, name: String, dstId: String) = MeshFrame(
        FrameType.DATA,
        MeshJson.encodeEnvelope(
            MeshEnvelope(
                id = UUID.randomUUID().toString(), kind = "PONG",
                srcId = srcId, dstId = dstId, convId = "conv-$srcId",
                ttl = 8, ts = 0, body = PresenceBody(displayName = name),
            ),
        ).toByteArray(),
    )

    private fun dataKinds(frames: List<MeshFrame>): List<String> =
        frames.mapNotNull { runCatching { MeshJson.decodeEnvelope(it.payloadText) }.getOrNull()?.kind }

    @Test
    fun `restart discovery rebuilds transport to clear stale state`() {
        val transport = CountingTransport()
        val service = MeshService(
            transport = transport, store = InMemoryMeshStore(), identity = LocalIdentity(shortId = "ME"), dedup = DedupCache(),
        )
        service.start()
        assertEquals(1, transport.startCount)
        // 蓝牙从关到开/连接残留：强制重建传输层（绕过 start 幂等守卫）
        service.restartDiscovery()
        assertEquals("强制重搜应重建传输层", 2, transport.startCount)
        assertTrue("应先停止旧传输层以清空遗留状态", transport.stopCount >= 1)
        service.stop()
    }

    @Test
    fun `markSeen does not override other peers presence`() {
        val transport = CountingTransport()
        val store = InMemoryMeshStore()
        store.upsertPeer("B", "Bob", System.currentTimeMillis(), 1)
        store.upsertPeer("C", "Carol", System.currentTimeMillis(), 1)
        val service = MeshService(
            transport = transport, store = store, identity = LocalIdentity(shortId = "ME"), dedup = DedupCache(),
        )
        service.start()
        // B、C 均为启动恢复的 SEARCHING（lastSeen 距今不久，不会被启动时的缓存清理（30 天未见）剪除）
        assertEquals(PeerPresence.SEARCHING, service.peers.value.firstOrNull { it.shortId == "B" }?.presence)
        // B 心跳在线 → markSeen(B)
        service.handleFrame(pingFrame("B", "Bob"))
        assertEquals("被 markSeen 的 peer 应在线", PeerPresence.ONLINE, service.peers.value.firstOrNull { it.shortId == "B" }?.presence)
        assertEquals("其他 peer 不应被乐观覆盖为在线", PeerPresence.SEARCHING, service.peers.value.firstOrNull { it.shortId == "C" }?.presence)
        // 帧到达应刷新 lastSeenAt（UI 据此显示"X 秒前信号"）
        val lastSeen = service.peers.value.firstOrNull { it.shortId == "B" }?.lastSeenAt ?: 0L
        assertTrue("帧到达应刷新 lastSeenAt", System.currentTimeMillis() - lastSeen < 2_000)
        // 昵称为空（扫描帧）不覆盖已学昵称
        service.handleFrame(pingFrame("B", ""))
        assertEquals("空昵称应保留已学名", "Bob", service.peers.value.firstOrNull { it.shortId == "B" }?.displayName)
        service.stop()
    }

    @Test
    fun `removePeer removes node from peers flow and store`() {
        val transport = CountingTransport()
        val store = InMemoryMeshStore()
        store.upsertPeer("B", "Bob", System.currentTimeMillis(), 1)
        val service = MeshService(
            transport = transport, store = store, identity = LocalIdentity(shortId = "ME"), dedup = DedupCache(),
        )
        service.start()
        // 启动恢复的已知节点在 peers 流中
        assertEquals("B", service.peers.value.firstOrNull { it.shortId == "B" }?.shortId)
        // 删除对话的配套动作：removePeer 应从内存表 + 持久化 + 流中移除节点
        service.removePeer("B")
        assertEquals("删除后 peers 流不应再含该节点", null, service.peers.value.firstOrNull { it.shortId == "B" })
        assertTrue("节点应从持久化缓存移除", store.loadPeers().none { it.shortId == "B" })
        // 再次收到该节点帧 → 重新入表（物理在线会重新被发现，属预期）
        service.handleFrame(pingFrame("B", "Bob"))
        assertEquals("再次收到帧应重新入表", "B", service.peers.value.firstOrNull { it.shortId == "B" }?.shortId)
        service.stop()
    }

    @Test
    fun `debug stats records sent and received frames`() {
        val transport = CountingTransport()
        val stats = com.meshchat.app.mesh.debug.DebugStats()
        val service = MeshService(
            transport = transport, store = InMemoryMeshStore(), identity = LocalIdentity(shortId = "ME"),
            dedup = DedupCache(), debugStats = stats,
        )
        service.start()
        service.handleFrame(pingFrame("B", "Bob"))          // 收 PING → 回 PONG
        val snap = stats.snapshot(5_000)
        assertEquals(1, snap.frames.getValue(com.meshchat.app.mesh.debug.FrameKind.PING).received)
        assertEquals(1, snap.frames.getValue(com.meshchat.app.mesh.debug.FrameKind.PONG).sent)
        service.stop()
    }

    @Test
    fun `debug stats delivery confirmed increments on receipt`() {
        val transport = CountingTransport()
        val store = InMemoryMeshStore()
        val stats = com.meshchat.app.mesh.debug.DebugStats()
        val service = MeshService(
            transport = transport, store = store, identity = LocalIdentity(shortId = "ME"),
            dedup = DedupCache(), debugStats = stats,
        )
        service.start()
        service.seedSessionKeyForTesting("B")   // v1.1.57 E2EE：非自环发送需会话密钥
        service.sendText("conv-B", "B", "hi")
        val msgId = store.queryMessages("conv-B").single().id
        // 模拟对端回 RECEIPT（id 与消息一致）
        val receipt = "{\"id\":\"$msgId\",\"srcId\":\"B\",\"dstId\":\"ME\"}"
        service.handleFrame(MeshFrame(FrameType.RECEIPT, receipt.toByteArray()))
        val snap = stats.snapshot(5_000)
        assertEquals(1, snap.delivery.confirmed)
        assertEquals(0, snap.delivery.pending)   // 确认后待确认队列已清空
        service.stop()
    }

    @Test
    fun `ping replies pong and records peer name`() {
        val transport = CountingTransport()
        val service = MeshService(
            transport = transport, store = InMemoryMeshStore(), identity = LocalIdentity(shortId = "ME"), dedup = DedupCache(),
        )
        val before = transport.broadcastCount
        service.handleFrame(pingFrame("OTHER", "老王"))
        assertTrue("收 PING 应回 PONG", dataKinds(transport.frames.drop(before)).contains("PONG"))
        assertEquals("OTHER", service.peers.value.first().shortId)
        assertEquals("老王", service.peers.value.first().displayName)
    }

    @Test
    fun `pong records peer seen without reply`() {
        val transport = CountingTransport()
        val service = MeshService(
            transport = transport, store = InMemoryMeshStore(), identity = LocalIdentity(shortId = "ME"), dedup = DedupCache(),
        )
        service.handleFrame(pongFrame("OTHER", "老王", "ME"))
        assertEquals("老王", service.peers.value.first().displayName)
        val kinds = dataKinds(transport.frames)
        assertTrue("PONG 不应触发回发", !kinds.contains("PING"))
    }

    @Test
    fun `peer marked lost after heartbeat timeout and revived by ping`() {
        val transport = CountingTransport()
        val service = MeshService(
            transport = transport, store = InMemoryMeshStore(), identity = LocalIdentity(shortId = "ME"), dedup = DedupCache(),
        )
        val t0 = System.currentTimeMillis()
        service.handleFrame(pingFrame("OTHER", "老王"))
        assertEquals(false, service.peers.value.first().lost)
        service.heartbeatTick(t0 + 3_100)
        assertEquals("3s 无心跳应判失联", true, service.peers.value.first().lost)
        service.handleFrame(pingFrame("OTHER", "老王"))   // 心跳恢复（markSeen 写真实时钟）
        val t1 = System.currentTimeMillis()
        service.heartbeatTick(t1 + 100)                   // 恢复后 100ms → 在线
        assertEquals("恢复心跳应回在线", false, service.peers.value.first().lost)
    }

    @Test
    fun `heartbeat pings at most every 500ms`() {
        val transport = CountingTransport()
        val service = MeshService(
            transport = transport, store = InMemoryMeshStore(), identity = LocalIdentity(shortId = "ME"), dedup = DedupCache(),
        )
        val t0 = System.currentTimeMillis()
        service.sendPingIfDue(t0)                    // 首帧
        service.sendPingIfDue(t0 + 200)
        service.sendPingIfDue(t0 + 400)
        assertEquals(1, dataKinds(transport.frames).count { it == "PING" })
        service.sendPingIfDue(t0 + 500)              // 满 500ms → 第二帧（v1.1.56 默认心跳 500ms）
        assertEquals(2, dataKinds(transport.frames).count { it == "PING" })
        service.sendPingIfDue(t0 + 600)
        service.sendPingIfDue(t0 + 800)
        assertEquals(2, dataKinds(transport.frames).count { it == "PING" })
        service.sendPingIfDue(t0 + 1_000)            // 距上次 500ms → 第三帧
        assertEquals(3, dataKinds(transport.frames).count { it == "PING" })
    }

    @Test
    fun `peers loaded from store start as searching`() {
        val transport = CountingTransport()
        val store = InMemoryMeshStore()
        store.upsertPeer("OTHER", "老王", System.currentTimeMillis(), 1)
        val service = MeshService(
            transport = transport, store = store, identity = LocalIdentity(shortId = "ME"), dedup = DedupCache(),
        )
        service.start()
        val peer = service.peers.value.firstOrNull { it.shortId == "OTHER" }
        assertTrue("持久化节点应加载", peer != null)
        assertEquals("老王", peer?.displayName)
        assertEquals("从未在本会话见过应标记寻找中", PeerPresence.SEARCHING, peer?.presence)
        service.stop()
    }

    @Test
    fun `peer visible only via scan frames is UNRESPONSIVE not online`() {
        val transport = CountingTransport()
        val service = MeshService(
            transport = transport, store = InMemoryMeshStore(),
            identity = LocalIdentity(shortId = "ME"), dedup = DedupCache(),
        )
        service.start()
        val t0 = System.currentTimeMillis()

        // 对方只广播（扫描帧持续到达），从未发过协议帧（App 应用层无响应/被系统冻结）
        transport.emitPeer(MeshPeerInfo(shortId = "OTHER", deviceAddress = "AA:BB:CC", rssi = -50))
        Thread.sleep(200) // foundPeers collector 异步写入 peerEntries
        service.heartbeatTick(t0 + 300)
        assertEquals("仅扫描可见不应在线", PeerPresence.UNRESPONSIVE, service.peers.value.first().presence)

        // 收到协议帧（PING）→ 应用层活跃 → ONLINE
        service.handleFrame(pingFrame("OTHER", "老王"))
        assertEquals("协议帧到达应在线", PeerPresence.ONLINE, service.peers.value.first().presence)

        // 协议帧停发：先等 appSeenAt 真实过期（>2s），再刷扫描帧 → 广播新鲜、协议死 → UNRESPONSIVE
        Thread.sleep(2_100)
        transport.emitPeer(MeshPeerInfo(shortId = "OTHER", deviceAddress = "AA:BB:CC", rssi = -50))
        Thread.sleep(200)
        service.heartbeatTick(System.currentTimeMillis() + 100)
        assertEquals("协议失联但广播可见应标记无响应", PeerPresence.UNRESPONSIVE, service.peers.value.first().presence)
        service.stop()
    }

    @Test
    fun `CLOSED discovery mode hides non-session peers from peers flow`() {
        // v1.1.60：关闭蓝牙搜索（CLOSED）后，非会话历史节点（含离线/无响应残留）不再输出到 peers 流——
        // 顶部"发现节点 N"不再把"没搜索到"的历史节点算入（用户实测：无会话+无历史+关搜索仍显示 1）；
        // 已会话联系人仍显示（GATT 保活心跳）。恢复 NORMAL 后全量恢复。
        val transport = CountingTransport()
        val service = MeshService(
            transport = transport, store = InMemoryMeshStore(),
            identity = LocalIdentity(shortId = "ME"), dedup = DedupCache(),
        )
        service.start()

        // 曾扫描到对方（历史节点，未建立会话）
        transport.emitPeer(MeshPeerInfo(shortId = "OTHER", deviceAddress = "AA:BB:CC", rssi = -50))
        Thread.sleep(200) // foundPeers collector 异步写入 peerEntries
        service.heartbeatTick(System.currentTimeMillis() + 100)
        assertTrue("NORMAL 下历史节点可见", service.peers.value.any { it.shortId == "OTHER" })

        // 关闭搜索 → 非会话节点从 peers 流消失（顶部"发现节点"应显示 0）
        service.setDiscoveryMode(DiscoveryMode.CLOSED)
        service.heartbeatTick(System.currentTimeMillis() + 100)
        assertTrue("CLOSED 下非会话节点应隐藏", service.peers.value.none { it.shortId == "OTHER" })

        // 已会话联系人仍显示
        service.acceptInvite("FRIEND")
        transport.emitPeer(MeshPeerInfo(shortId = "FRIEND", deviceAddress = "BB:CC:DD", rssi = -60))
        Thread.sleep(200)
        service.heartbeatTick(System.currentTimeMillis() + 100)
        assertTrue("CLOSED 下已会话联系人仍可见", service.peers.value.any { it.shortId == "FRIEND" })

        // 恢复搜索 → 历史节点重新可见
        service.setDiscoveryMode(DiscoveryMode.NORMAL)
        service.heartbeatTick(System.currentTimeMillis() + 100)
        assertTrue("恢复 NORMAL 后历史节点重新可见", service.peers.value.any { it.shortId == "OTHER" })
        service.stop()
    }

    @Test
    fun `presence transitions online then reconnecting then offline without removal`() {
        val transport = CountingTransport()
        val service = MeshService(
            transport = transport, store = InMemoryMeshStore(), identity = LocalIdentity(shortId = "ME"), dedup = DedupCache(),
        )
        val t0 = System.currentTimeMillis()
        service.handleFrame(pingFrame("OTHER", "老王"))
        assertEquals("有心跳应在线", PeerPresence.ONLINE, service.peers.value.first().presence)

        val t1 = System.currentTimeMillis()
        service.heartbeatTick(t1 + 3_100)          // 3.1s 无心跳 → 断线重连中
        assertEquals("短暂失联应标记重连中", PeerPresence.RECONNECTING, service.peers.value.first().presence)

        val t2 = System.currentTimeMillis()
        service.heartbeatTick(t2 + 31_000)         // 30s+ 无心跳 → 离线（保留不删除）
        assertEquals("长时间离线应标记离线", PeerPresence.OFFLINE, service.peers.value.first().presence)
        assertTrue("离线节点应保留不删除", service.peers.value.any { it.shortId == "OTHER" })
    }

    @Test
    fun `ping triggers immediate resend of pending text`() {
        val transport = CountingTransport()
        val store = InMemoryMeshStore()
        val service = MeshService(
            transport = transport, store = store, identity = LocalIdentity(shortId = "ME"), dedup = DedupCache(),
        )
        service.seedSessionKeyForTesting("OTHER")   // v1.1.57 E2EE：非自环发送需会话密钥
        service.sendText("conv-OTHER", "OTHER", "hi")
        assertEquals(1, transport.broadcastCount)

        // 对方心跳在线 → 立即重发未确认消息（不等 3s 定时）+ 回 PONG，后台恢复场景秒级收敛
        service.handleFrame(pingFrame("OTHER", "老王"))
        assertEquals("PING 应触发重发(TEXT) + 回 PONG", 3, transport.broadcastCount)
        assertEquals("消息应被重发一次", 2, dataKinds(transport.frames).count { it == "TEXT" })
        service.handleFrame(pingFrame("OTHER", "老王"))
        assertEquals(5, transport.broadcastCount)
    }

    @Test
    fun `pong also triggers immediate resend of pending text`() {
        val transport = CountingTransport()
        val store = InMemoryMeshStore()
        val service = MeshService(
            transport = transport, store = store, identity = LocalIdentity(shortId = "ME"), dedup = DedupCache(),
        )
        service.seedSessionKeyForTesting("OTHER")   // v1.1.57 E2EE：非自环发送需会话密钥
        service.sendText("conv-OTHER", "OTHER", "hi")
        assertEquals(1, transport.broadcastCount)

        // 对方回 PONG（应答本机 PING）→ 同样立即重发未确认消息（PING/PONG 双触发，确认机会翻倍）
        service.handleFrame(pongFrame("OTHER", "老王", "ME"))
        assertEquals("PONG 应触发重发", 2, transport.broadcastCount)
        assertEquals("消息应被重发一次", 2, dataKinds(transport.frames).count { it == "TEXT" })
    }

    @Test
    fun `pong ack ids confirm delivery immediately`() {
        val transport = CountingTransport()
        val store = InMemoryMeshStore()
        val service = MeshService(
            transport = transport, store = store, identity = LocalIdentity(shortId = "ME"), dedup = DedupCache(),
        )
        service.seedSessionKeyForTesting("OTHER")   // v1.1.57 E2EE：非自环发送需会话密钥
        service.sendText("conv-OTHER", "OTHER", "hi")
        val msgId = store.queryMessages("conv-OTHER").first().id
        assertEquals(MessageStatus.SENDING, store.queryMessages("conv-OTHER").first().status)

        // 对方随心跳 PONG 携带本机消息的 ackIds（硬实时确认，复用心跳通道）→ 立即标记送达
        service.handleFrame(
            MeshFrame(
                FrameType.DATA,
                MeshJson.encodeEnvelope(
                    MeshEnvelope(
                        id = UUID.randomUUID().toString(), kind = "PONG",
                        srcId = "OTHER", dstId = "ME", convId = "conv-ME",
                        ttl = 8, ts = System.currentTimeMillis(),
                        body = PresenceBody("老王", ackIds = listOf(msgId)),
                    ),
                ).toByteArray(),
            ),
        )
        assertEquals("PONG 携带 ackIds 应立即标记已送达", MessageStatus.DELIVERED, store.queryMessages("conv-OTHER").first().status)
        service.resendPendingReceipts(System.currentTimeMillis() + 10_000)
        assertEquals("确认后不再重发", 1, transport.broadcastCount)
    }

    @Test
    fun `ping reply pong carries ack ids for received messages`() {
        val transport = CountingTransport()
        val service = MeshService(
            transport = transport, store = InMemoryMeshStore(), identity = LocalIdentity(shortId = "ME"), dedup = DedupCache(),
        )
        service.handleFrame(textFrame("m1", "OTHER", "ME", "hi"))   // 本机收到 OTHER 的消息
        val before = transport.broadcastCount
        service.handleFrame(pingFrame("OTHER", "老王"))              // OTHER 心跳在线
        val pongEnv = transport.frames.drop(before)
            .mapNotNull { runCatching { MeshJson.decodeEnvelope(it.payloadText) }.getOrNull() }
            .first { it.kind == "PONG" }
        val ack = (pongEnv.body as? PresenceBody)?.ackIds
        assertTrue("回 PONG 应携带已收到消息的 ackIds", ack != null && ack.contains("m1"))
    }

    @Test
    fun `received text carries sender name into peers`() {
        val transport = CountingTransport()
        val store = InMemoryMeshStore()
        val service = MeshService(
            transport = transport, store = store, identity = LocalIdentity(shortId = "ME"), dedup = DedupCache(),
        )
        // 对方发来带昵称的消息（不依赖 PING 时序，收到消息即学到昵称）
        service.handleFrame(
            MeshFrame(
                FrameType.DATA,
                MeshJson.encodeEnvelope(
                    MeshEnvelope(
                        id = UUID.randomUUID().toString(), kind = "TEXT",
                        srcId = "OTHER", dstId = "ME", convId = "conv-OTHER",
                        ttl = 8, ts = System.currentTimeMillis(),
                        body = TextBody("hi", displayName = "老王"),
                    ),
                ).toByteArray(),
            ),
        )
        val peer = service.peers.value.firstOrNull { it.shortId == "OTHER" }
        assertEquals("收到消息即学到对方昵称", "老王", peer?.displayName)
        assertEquals("昵称应落库供重启恢复", "老王", store.loadPeers().firstOrNull { it.shortId == "OTHER" }?.displayName)
    }

    @Test
    fun `broadcast ack keys confirm delivery without gatt connection`() {
        val transport = CountingTransport()
        val store = InMemoryMeshStore()
        val service = MeshService(
            transport = transport, store = store, identity = LocalIdentity(shortId = "ME"), dedup = DedupCache(),
        )
        service.start()
        service.seedSessionKeyForTesting("OTHER")   // v1.1.57 E2EE：非自环发送需会话密钥
        service.sendText("conv-OTHER", "OTHER", "hi")
        val msgId = store.queryMessages("conv-OTHER").first().id
        assertEquals(MessageStatus.SENDING, store.queryMessages("conv-OTHER").first().status)

        // 对端广播（扫描响应）携带确认键——无需任何 GATT 连接/心跳，扫描到广播即确认送达
        transport.emitPeer(
            MeshPeerInfo(shortId = "OTHER", deviceAddress = "AA:BB:CC:DD:EE:FF", rssi = -50, ackKeys = listOf(service.ackKeyFor(msgId))),
        )
        // foundPeers collector 在 Dispatchers.Default 异步处理，轮询等待确认落地
        val deadline = System.currentTimeMillis() + 2_000
        while (store.queryMessages("conv-OTHER").first().status == MessageStatus.SENDING && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertEquals("广播确认键应立即标记已送达", MessageStatus.DELIVERED, store.queryMessages("conv-OTHER").first().status)
        service.resendPendingReceipts(System.currentTimeMillis() + 10_000)
        assertEquals("确认后不再重发", 1, transport.broadcastCount)
        service.stop()
    }

    @Test
    fun `broadcast ack keys expose received messages`() {
        val service = MeshService(
            transport = CountingTransport(), store = InMemoryMeshStore(),
            identity = LocalIdentity(shortId = "ME"), dedup = DedupCache(),
        )
        service.handleFrame(textFrame("m1", "OTHER", "ME", "hi"))
        assertTrue("本机已收消息应出现在广播确认键中", service.broadcastAckKeys().any { it.contentEquals(service.ackKeyFor("m1")) })
    }

    @Test
    fun `undelivered texts are re-registered on start`() {
        val transport = CountingTransport()
        val store = InMemoryMeshStore()
        // 模拟进程被杀前未确认的消息（SENDING 落库）
        store.insertMessage(
            StoredMessage(
                id = "m1", convId = "conv-OTHER", kind = "TEXT", srcId = "ME", dstId = "OTHER",
                text = "hi", status = MessageStatus.SENDING, ts = 1,
            ),
        )
        val service = MeshService(
            transport = transport, store = store, identity = LocalIdentity(shortId = "ME"), dedup = DedupCache(),
        )
        service.start()
        // 对方在线 → 重启后未确认消息应立即重发
        service.handleFrame(pingFrame("OTHER", "老王"))
        assertTrue("重启后未确认消息应重发", transport.broadcastCount >= 1)
        service.stop()
    }

    @Test
    fun `text without receipt retransmits with backoff and never fails`() {
        val transport = CountingTransport()
        val store = InMemoryMeshStore()
        val service = MeshService(
            transport = transport, store = store, identity = LocalIdentity(shortId = "ME"), dedup = DedupCache(),
        )
        val t0 = System.currentTimeMillis()
        service.seedSessionKeyForTesting("OTHER")   // v1.1.57 E2EE：非自环发送需会话密钥
        service.sendText("conv-OTHER", "OTHER", "hi")
        val msgId = store.queryMessages("conv-OTHER").first().id
        assertEquals(MessageStatus.SENDING, store.queryMessages("conv-OTHER").first().status)
        assertEquals(1, transport.broadcastCount)   // 首次广播

        service.resendPendingReceipts(t0 + 4_000)      // 退避 3s → retry 1
        assertEquals("3s 未确认应重发", 2, transport.broadcastCount)
        service.resendPendingReceipts(t0 + 10_000)     // 退避 6s（距上次 6s）→ retry 2
        assertEquals(3, transport.broadcastCount)
        // 超上限不 FAILED 不清除：零容错，继续退避重发直到收到回执
        service.resendPendingReceipts(t0 + 22_000)     // 退避 12s → retry 3
        service.resendPendingReceipts(t0 + 46_000)     // 退避 24s → retry 4
        assertEquals("退避重发应继续", 5, transport.broadcastCount)
        assertEquals("不得标记 FAILED，保持 SENDING 等待收敛", MessageStatus.SENDING, store.queryMessages("conv-OTHER").first().status)

        // 收到回执 → 送达 + 停止重发
        service.handleFrame(receiptFrame(msgId))
        assertEquals(MessageStatus.DELIVERED, store.queryMessages("conv-OTHER").first().status)
        service.resendPendingReceipts(t0 + 96_000)
        assertEquals("回执后不再重发", 5, transport.broadcastCount)
    }

    @Test
    fun `receiving node repeats receipt for recent messages`() {
        val transport = CountingTransport()
        val store = InMemoryMeshStore()
        val service = MeshService(
            transport = transport, store = store, identity = LocalIdentity(shortId = "ME"), dedup = DedupCache(),
        )
        service.handleFrame(textFrame("m1", "OTHER", "ME", "hi"))
        val before = transport.frames.count { it.type == FrameType.RECEIPT }
        assertEquals("首次送达应回执", 1, before)

        val t0 = System.currentTimeMillis()
        service.heartbeatTick(t0 + 3_100)          // 3s 后心跳周期 → 重复回执
        assertEquals("近期消息应周期性重复回执", before + 1, transport.frames.count { it.type == FrameType.RECEIPT })
    }

    @Test
    fun `peers are restored from message history when peer table empty`() {
        val transport = CountingTransport()
        val store = InMemoryMeshStore()
        // peers 表为空，但历史消息里有对端会话（conv-OTHER）
        store.insertMessage(
            StoredMessage(id = "h1", convId = "conv-OTHER", kind = "TEXT", srcId = "ME", dstId = "OTHER", text = "hi", ts = 1),
        )
        val service = MeshService(
            transport = transport, store = store, identity = LocalIdentity(shortId = "ME"), dedup = DedupCache(),
        )
        service.start()
        assertTrue("消息历史对端应恢复为已知节点", service.peers.value.any { it.shortId == "OTHER" })
        service.stop()
    }

    private fun textFrame(id: String, srcId: String, dstId: String, text: String) = MeshFrame(
        FrameType.DATA,
        MeshJson.encodeEnvelope(
            MeshEnvelope(id = id, kind = "TEXT", srcId = srcId, dstId = dstId, convId = "conv-$dstId", ttl = 8, ts = 1, body = TextBody(text)),
        ).toByteArray(),
    )

    @Test
    fun `incoming text triggers onIncomingMessage with peer name`() {
        val transport = CountingTransport()
        data class Notify(val fromId: String, val fromName: String, val text: String, val convId: String)
        val received = mutableListOf<Notify>()
        val service = MeshService(
            transport = transport, store = InMemoryMeshStore(), identity = LocalIdentity(shortId = "ME"), dedup = DedupCache(),
            onIncomingMessage = { fromId, fromName, text, convId -> received.add(Notify(fromId, fromName, text, convId)) },
        )
        service.start()
        service.handleFrame(pingFrame("OTHER", "老王"))   // 先让昵称入表
        service.handleFrame(textFrame("t1", "OTHER", "ME", "你好"))
        assertTrue(received.isNotEmpty())
        assertEquals("OTHER", received.first().fromId)
        assertEquals("老王", received.first().fromName)
        assertEquals("你好", received.first().text)
        assertEquals("conv-OTHER", received.first().convId)
        service.stop()
    }

    private fun receiptFrame(msgId: String) = MeshFrame(
        FrameType.RECEIPT,
        "{\"id\":\"$msgId\",\"srcId\":\"OTHER\",\"dstId\":\"ME\"}".toByteArray(),
    )

    @Test
    fun `receipt stops retransmission`() {
        val transport = CountingTransport()
        val store = InMemoryMeshStore()
        val service = MeshService(
            transport = transport, store = store, identity = LocalIdentity(shortId = "ME"), dedup = DedupCache(),
        )
        val t0 = System.currentTimeMillis()
        service.seedSessionKeyForTesting("OTHER")   // v1.1.57 E2EE：非自环发送需会话密钥
        service.sendText("conv-OTHER", "OTHER", "hi")
        val msgId = store.queryMessages("conv-OTHER").first().id
        service.resendPendingReceipts(t0 + 6_000)
        assertEquals(2, transport.broadcastCount)

        service.handleFrame(receiptFrame(msgId))     // 收到回执
        assertEquals(MessageStatus.DELIVERED, store.queryMessages("conv-OTHER").first().status)
        service.resendPendingReceipts(t0 + 12_000)
        assertEquals("回执后不再重发", 2, transport.broadcastCount)
    }

    @Test
    fun `duplicate text from resend triggers receipt again`() {
        val transport = CountingTransport()
        val store = InMemoryMeshStore()
        val service = MeshService(
            transport = transport, store = store, identity = LocalIdentity(shortId = "ME"), dedup = DedupCache(),
        )
        service.handleFrame(textFrame("m1", "OTHER", "ME", "hi"))
        assertEquals(1, transport.broadcastCount)    // Deliver 后回 RECEIPT
        assertEquals(1, store.queryMessages("conv-OTHER").size)

        // 发送方重发的同 id 消息：dedup 命中 Drop，但必须补发回执让发送方收敛
        service.handleFrame(textFrame("m1", "OTHER", "ME", "hi"))
        assertEquals("重复帧应补发 RECEIPT", 2, transport.broadcastCount)
        assertEquals("不得重复落库", 1, store.queryMessages("conv-OTHER").size)
    }

    // ===== v1.1.0 多跳中继 =====

    @Test
    fun `non session peer relays text to third node`() {
        val transport = CountingTransport()
        val store = InMemoryMeshStore()
        val service = MeshService(
            transport = transport, store = store, identity = LocalIdentity(shortId = "ME"), dedup = DedupCache(),
        )
        // 中继节点 B：与 A、C 均无会话关系（sessions 空），收到 A→C 的 TEXT 应转发（TTL 递减）
        service.handleFrame(textFrame("t1", "A", "C", "hi"))
        Thread.sleep(300)  // 等转发抖动 50-250ms
        val forwarded = transport.frames
            .filter { it.type == FrameType.DATA }
            .map { MeshJson.decodeEnvelope(it.payloadText) }
            .firstOrNull { it.dstId == "C" }
        assertTrue("非会话节点应转发 TEXT 到 C", forwarded != null)
        assertEquals(7, forwarded?.ttl)
    }

    @Test
    fun `relayed text is not stored or notified on relay node`() {
        var notified = 0
        val store = InMemoryMeshStore()
        val service = MeshService(
            transport = CountingTransport(), store = store,
            identity = LocalIdentity(shortId = "ME"), dedup = DedupCache(),
            onIncomingMessage = { _, _, _, _ -> notified++ },
        )
        service.handleFrame(textFrame("t1", "A", "C", "hi"))
        Thread.sleep(300)
        assertEquals("中继节点不得弹通知", 0, notified)
        assertTrue("中继节点不得落库", store.queryMessages("conv-A").isEmpty())
    }

    @Test
    fun `receipt is forwarded once and deduplicated`() {
        val transport = CountingTransport()
        val service = MeshService(
            transport = transport, store = InMemoryMeshStore(),
            identity = LocalIdentity(shortId = "ME"), dedup = DedupCache(),
        )
        service.handleFrame(receiptFrame("t1"))
        assertEquals("首次收到回执应转发一次", 1, transport.broadcastCount)
        service.handleFrame(receiptFrame("t1"))
        assertEquals("重复回执去重不转发", 1, transport.broadcastCount)
    }

    @Test
    fun `ping carries relays every third heartbeat`() {
        val transport = CountingTransport()
        val service = MeshService(
            transport = transport, store = InMemoryMeshStore(),
            identity = LocalIdentity(shortId = "ME"), dedup = DedupCache(),
        )
        // 先让 B 成为本机一跳新鲜邻居（lastSeen 距今 ≤10s）
        service.handleFrame(pingFrame("B", "老王"))
        val t0 = System.currentTimeMillis()
        service.sendPingIfDue(t0)          // PING #1
        service.sendPingIfDue(t0 + 1_000)  // PING #2
        service.sendPingIfDue(t0 + 2_000)  // PING #3 → 携带 relays
        val pings = transport.frames
            .filter { it.type == FrameType.DATA }
            .map { MeshJson.decodeEnvelope(it.payloadText) }
            .filter { it.kind == "PING" && it.srcId == "ME" }
        assertEquals(3, pings.size)
        assertEquals("前 2 次心跳不带 relays", emptyList<String>(), (pings[0].body as PresenceBody).relays)
        assertEquals(emptyList<String>(), (pings[1].body as PresenceBody).relays)
        assertEquals("第 3 次心跳携带一跳邻居", listOf("B"), (pings[2].body as PresenceBody).relays)
    }

    @Test
    fun `route entries learned from ping relays`() {
        val service = MeshService(
            transport = CountingTransport(), store = InMemoryMeshStore(),
            identity = LocalIdentity(shortId = "ME"), dedup = DedupCache(),
        )
        // B 广播 PING 携带其一跳邻居 C → 本机学习"C 经 B 可达（2 跳）"
        service.handleFrame(pingFrame("B", "老王", relays = listOf("C")))
        val c = service.peers.value.firstOrNull { it.shortId == "C" }
        assertTrue("应学习 2 跳路由 C→via B", c != null)
        assertEquals("B", c?.relayVia)
        assertEquals(2, c?.hops)
    }

    @Test
    fun `route entries expire when relay goes offline`() {
        val service = MeshService(
            transport = CountingTransport(), store = InMemoryMeshStore(),
            identity = LocalIdentity(shortId = "ME"), dedup = DedupCache(),
        )
        val t0 = System.currentTimeMillis()
        service.handleFrame(pingFrame("B", "老王", relays = listOf("C")))
        assertTrue("学习后 C 应可达", service.peers.value.any { it.shortId == "C" })
        // 中继 B 心跳超时（> OFFLINE_THRESHOLD_MS=15s）→ 经它的路由移除
        service.heartbeatTick(t0 + 20_000)
        assertTrue("中继失联后路由应过期", service.peers.value.none { it.shortId == "C" })
    }

    @Test
    fun `ping pong are not forwarded`() {
        val transport = CountingTransport()
        val service = MeshService(
            transport = transport, store = InMemoryMeshStore(),
            identity = LocalIdentity(shortId = "ME"), dedup = DedupCache(),
        )
        // PING 只触发本机回 PONG（1 次广播），PING 本身不转发
        service.handleFrame(pingFrame("B", "老王"))
        val dataFrames = transport.frames
            .filter { it.type == FrameType.DATA }
            .map { MeshJson.decodeEnvelope(it.payloadText) }
        assertEquals("PING 不应被转发，仅回 PONG", 1, dataFrames.size)
        assertEquals("PONG", dataFrames[0].kind)
        // PONG 不产生任何转发广播
        val pongFrame = MeshFrame(
            FrameType.DATA,
            MeshJson.encodeEnvelope(
                MeshEnvelope(
                    id = "p1", kind = "PONG", srcId = "B", dstId = "ME", convId = "conv-ME",
                    ttl = 8, ts = 0, body = PresenceBody(displayName = "老王"),
                ),
            ).toByteArray(),
        )
        service.handleFrame(pongFrame)
        assertEquals("PONG 不应产生广播", 1, transport.broadcastCount)
    }

    @Test
    fun `heartbeat interval can be adjusted via debug control`() = runTest {
        val identity = LocalIdentity(shortId = "ME")
        val transport = CountingTransport()
        val service = MeshService(
            transport = transport, store = InMemoryMeshStore(), identity = identity, dedup = DedupCache(),
        )
        service.start()

        service.setHeartbeat(2_000)
        val t0 = System.currentTimeMillis() + 10_000   // 虚拟推进 10s：确保距 start 时 lastPingAt 超过任何心跳间隔
        service.sendPingIfDue(t0)                       // 必发一轮 PING，lastPingAt = t0
        transport.frames.clear()
        service.sendPingIfDue(t0 + 500)                 // 0.5s < 2s → 不发
        assertTrue("0.5s 后不应发 PING", transport.frames.isEmpty())
        service.sendPingIfDue(t0 + 2_500)               // 2.5s ≥ 2s → 发
        assertTrue("2.5s 后应发 PING", transport.frames.isNotEmpty())
    }

    @Test
    fun `heartbeat supports 50ms high frequency via debug control`() = runTest {
        val identity = LocalIdentity(shortId = "ME")
        val transport = CountingTransport()
        val service = MeshService(
            transport = transport, store = InMemoryMeshStore(), identity = identity, dedup = DedupCache(),
        )
        service.start()

        service.setHeartbeat(50)
        val t0 = System.currentTimeMillis() + 10_000
        service.sendPingIfDue(t0)           // 首次必发，lastPingAt = t0
        transport.frames.clear()
        service.sendPingIfDue(t0 + 30)      // 30ms < 50ms → 不发
        assertTrue("30ms 内不应发 PING", transport.frames.isEmpty())
        service.sendPingIfDue(t0 + 60)      // 60ms ≥ 50ms → 发
        assertTrue("60ms 后应发 PING", transport.frames.isNotEmpty())
    }

    @Test
    fun `resend policy can be adjusted via debug control`() = runTest {
        val identity = LocalIdentity(shortId = "ME")
        val transport = CountingTransport()
        val service = MeshService(
            transport = transport, store = InMemoryMeshStore(), identity = identity, dedup = DedupCache(),
        )
        service.start()

        service.setResendPolicy(10_000, 60_000)
        service.seedSessionKeyForTesting("OTHER")   // v1.1.57 E2EE：非自环发送需会话密钥
        service.sendText(convId = "c1", dstId = "OTHER", text = "hi")  // 非会话节点 → 转发，pendingReceipts 登记未确认
        val t0 = System.currentTimeMillis() + 5_000      // 距发送约 5s < 新基础 10s
        transport.frames.clear()
        service.resendPendingReceipts(t0, pingTriggered = false)
        assertTrue("10s 内不应重发", transport.frames.isEmpty())
        service.resendPendingReceipts(t0 + 10_000, pingTriggered = false)  // 距发送约 15s ≥ 10s
        assertTrue("10s 后应重发", transport.frames.isNotEmpty())
    }

    @Test
    fun `signaling can be suspended and resumed via debug control`() = runTest {
        val identity = LocalIdentity(shortId = "ME")
        val transport = InMemoryTransport()
        val service = MeshService(
            transport = transport, store = InMemoryMeshStore(), identity = identity, dedup = DedupCache(),
        )
        service.start()

        service.suspendSignaling()   // v1.1.53：暂停发现 = 关闭扫描模式
        assertEquals(DiscoveryMode.CLOSED, transport.lastDiscoveryMode)
        service.resumeSignaling()
        assertEquals(DiscoveryMode.NORMAL, transport.lastDiscoveryMode)
    }

    @Test
    fun `discovery modes forward to transport and update state`() = runTest {
        // v1.1.53：发现模式三态（取代 v1.1.49 布尔开关）——NORMAL 全开 / CLOSED 全停 / SILENT 只停广播
        val identity = LocalIdentity(shortId = "ME")
        val transport = InMemoryTransport()
        val service = MeshService(
            transport = transport, store = InMemoryMeshStore(), identity = identity, dedup = DedupCache(),
        )
        service.start()

        assertTrue("默认 NORMAL", service.discoveryMode.value == DiscoveryMode.NORMAL)
        assertTrue("默认发现层活动", service.discoveryEnabled.value)
        service.setDiscoveryMode(DiscoveryMode.CLOSED)
        assertEquals("CLOSED 转发 transport", DiscoveryMode.CLOSED, transport.lastDiscoveryMode)
        assertTrue("CLOSED 后 discoveryEnabled=false", !service.discoveryEnabled.value)
        service.setDiscoveryMode(DiscoveryMode.SILENT)
        assertEquals("SILENT 转发 transport", DiscoveryMode.SILENT, transport.lastDiscoveryMode)
        assertTrue("SILENT 保留 scan/连接，也算活动", service.discoveryEnabled.value)
        service.setDiscoveryMode(DiscoveryMode.NORMAL)
        assertEquals("NORMAL 转发 transport", DiscoveryMode.NORMAL, transport.lastDiscoveryMode)
        assertTrue("恢复 NORMAL 后 discoveryEnabled=true", service.discoveryEnabled.value)
    }

    @Test
    fun `heartbeat pings continue in all discovery modes`() {
        // v1.1.53（用户最终设计）：所有模式都发 PING——broadcast 只走 GATT 写/notify（与 advertise 无关），
        // 静默/关闭扫描下保活已建立连接正是"继续连接联系人、关系人经保活感知在线"所需
        val identity = LocalIdentity(shortId = "ME")
        val transport = CountingTransport()
        val service = MeshService(
            transport = transport, store = InMemoryMeshStore(), identity = identity, dedup = DedupCache(),
        )
        service.start()
        val t0 = System.currentTimeMillis()
        val pingCount = { transport.frames.count {
            it.type == FrameType.DATA && MeshJson.decodeEnvelope(it.payloadText).kind == "PING"
        } }

        service.sendPingIfDue(t0)                       // NORMAL：发 PING
        assertEquals("NORMAL 应发心跳", 1, pingCount())
        service.setDiscoveryMode(DiscoveryMode.CLOSED)  // 全停（autoDiscovery=关）但保活
        service.sendPingIfDue(t0 + 1_000)
        assertEquals("CLOSED 保留连接保活", 2, pingCount())
        service.setDiscoveryMode(DiscoveryMode.SILENT)  // 静默只停广播，保活照常
        service.sendPingIfDue(t0 + 2_000)
        assertEquals("SILENT 保留连接保活", 3, pingCount())
        service.stop()
    }

    @Test
    fun `tx power can be adjusted via debug control and reset to default`() = runTest {
        val identity = LocalIdentity(shortId = "ME")
        val transport = InMemoryTransport()
        val service = MeshService(
            transport = transport, store = InMemoryMeshStore(), identity = identity, dedup = DedupCache(),
        )
        service.start()

        // 默认 +1dBm HIGH
        assertEquals(1, transport.lastTxPowerLevel)

        // 合法四档：1/-7/-15/-21
        service.setTxPower(-7)
        assertEquals(-7, transport.lastTxPowerLevel)
        service.setTxPower(-15)
        assertEquals(-15, transport.lastTxPowerLevel)
        service.setTxPower(-21)
        assertEquals(-21, transport.lastTxPowerLevel)

        // 非法档忽略
        service.setTxPower(0)
        assertEquals("非法档不应生效", -21, transport.lastTxPowerLevel)
        service.setTxPower(5)
        assertEquals("非法档不应生效", -21, transport.lastTxPowerLevel)

        // 恢复默认回 +1dBm
        service.resetDebugControls()
        assertEquals(1, transport.lastTxPowerLevel)
    }

    // ===== v1.1.16 协议层信号强度（PING seq 缺口统计）=====

    @Test
    fun `link quality window computes success rate from ping seq gaps`() {
        val w = MeshService.LinkQualityWindow()
        assertEquals(-1.0, w.rate(), 1e-9)                 // 无样本
        assertEquals(-1.0, w.onPing(0), 1e-9)              // 老版本 seq=0 忽略
        assertEquals(-1.0, w.onPing(5), 1e-9)              // 首样本，仅记录
        assertEquals(1.0, w.onPing(6), 1e-9)               // 连续 → 100%
        assertEquals(1.0, w.onPing(7), 1e-9)
        assertEquals(0.8, w.onPing(9), 1e-9)               // 丢 seq8：5 判定 4 收到 → 80%
        assertEquals(5.0 / 6.0, w.onPing(10), 1e-9)        // 6 判定 5 收到
        assertEquals(5.0 / 6.0, w.onPing(7), 1e-9)         // 重复 seq 忽略
        assertEquals(5.0 / 6.0, w.onPing(3), 1e-9)         // 过期 seq 忽略
    }

    @Test
    fun `link quality window rebuilds on large seq jump`() {
        val w = MeshService.LinkQualityWindow()
        w.onPing(1)
        w.onPing(2)
        w.onPing(3)
        // 大幅跳变（对端重启/长期失联）→ 重建窗口，返回 -1
        assertEquals(-1.0, w.onPing(200), 1e-9)
        // 重建后从 200 重新累计
        assertEquals(1.0, w.onPing(201), 1e-9)
    }

    @Test
    fun `received ping seq updates peer link quality`() = runTest {
        val identity = LocalIdentity(shortId = "ME")
        val transport = InMemoryTransport()
        val stats = com.meshchat.app.mesh.debug.DebugStats()
        val service = MeshService(
            transport = transport, store = InMemoryMeshStore(), identity = identity, dedup = DedupCache(),
            debugStats = stats,
        )
        service.start()

        fun ping(seq: Int) = MeshEnvelope(
            id = UUID.randomUUID().toString(), kind = "PING",
            srcId = "PEER", dstId = "", convId = "conv-PEER",
            ttl = 8, ts = System.currentTimeMillis(),
            body = PresenceBody(displayName = "peer", seq = seq),
        )
        // 对端心跳 seq 1,2,4（3 丢失）
        service.handleFrame(MeshFrame(FrameType.DATA, MeshJson.encodeEnvelope(ping(1)).toByteArray()))
        service.handleFrame(MeshFrame(FrameType.DATA, MeshJson.encodeEnvelope(ping(2)).toByteArray()))
        service.handleFrame(MeshFrame(FrameType.DATA, MeshJson.encodeEnvelope(ping(4)).toByteArray()))

        val snap = stats.snapshot(1_000)
        val peer = snap.peers.first { it.shortId == "PEER" }
        assertEquals(0.75, peer.linkSuccessRate, 1e-9)     // 3 收 1 丢
        assertEquals(4, peer.linkSamples)
    }

    @Test
    fun `outgoing ping carries incrementing seq`() = runTest {
        val identity = LocalIdentity(shortId = "ME")
        val transport = CountingTransport()
        val service = MeshService(
            transport = transport, store = InMemoryMeshStore(), identity = identity, dedup = DedupCache(),
        )
        service.start()
        transport.frames.clear()
        val t0 = System.currentTimeMillis()
        service.sendPingIfDue(t0)
        assertEquals(1, transport.frames.size)
        val env1 = MeshJson.decodeEnvelope(transport.frames[0].payloadText)
        val seq1 = (env1.body as PresenceBody).seq
        assertTrue("PING 应带递增 seq", seq1 > 0)
        service.sendPingIfDue(t0 + 10)                       // 心跳间隔内节流，不重复发
        assertEquals(1, transport.frames.size)
        service.sendPingIfDue(t0 + 1_100)                    // 下个心跳周期
        assertEquals(2, transport.frames.size)
        val env2 = MeshJson.decodeEnvelope(transport.frames[1].payloadText)
        assertEquals("seq 应递增", seq1 + 1, (env2.body as PresenceBody).seq)
    }

    // ===== v1.1.20 Mesh 页信号 = 全局接收成功率（接收包 ÷ (接收包+失败包)）=====

    @Test
    fun `mesh peer signal ratio equals global receive success rate`() = runTest {
        val identity = LocalIdentity(shortId = "ME")
        val transport = InMemoryTransport()
        val stats = com.meshchat.app.mesh.debug.DebugStats()
        val service = MeshService(
            transport = transport, store = InMemoryMeshStore(), identity = identity, dedup = DedupCache(),
            debugStats = stats,
        )
        service.start()

        // 先制造 1 次失败事件，再收 1 个对端 PING（接收包 +1 并触发 refreshPeers）
        stats.recordReceivedFailure()
        val pingFromPeer = MeshEnvelope(
            id = UUID.randomUUID().toString(), kind = "PING",
            srcId = "PEER", dstId = "", convId = "conv-PEER",
            ttl = 8, ts = System.currentTimeMillis(),
            body = PresenceBody(displayName = "peer", seq = 1),
        )
        service.handleFrame(MeshFrame(FrameType.DATA, MeshJson.encodeEnvelope(pingFromPeer).toByteArray()))

        val peer = service.peers.value.first { it.shortId == "PEER" }
        assertEquals(0.5, peer.signalRatio, 1e-9)          // 1 收 ÷ (1 收 + 1 失败)
    }

    @Test
    fun `direct peer takes precedence over relay entry and deduplicates`() {
        val transport = CountingTransport()
        val service = MeshService(
            transport = transport, store = InMemoryMeshStore(),
            identity = LocalIdentity(shortId = "ME"), dedup = DedupCache(),
        )
        service.start()
        // 先经中继学到 C（C 未直连）
        service.handleFrame(pingFrame("B", "小B", relays = listOf("C")))
        assertTrue("应先以中继可达出现", service.peers.value.any { it.shortId == "C" && it.relayVia == "B" })
        // C 直接出现（扫描帧）→ foundPeers collector 更新 peerEntries（lastSeen 新鲜）；collector 异步，等待其写入
        transport.emitPeer(MeshPeerInfo(shortId = "C", deviceAddress = "AA:BB:CC", rssi = -60))
        Thread.sleep(200)
        service.heartbeatTick(System.currentTimeMillis())
        val c = service.peers.value.filter { it.shortId == "C" }
        assertEquals("同 id 只保留一条", 1, c.size)
        assertTrue("一跳直连优先（relayVia 清空）", c.first().relayVia.isBlank())
        service.stop()
    }

    // ===== v1.1.50 群消息 MVP =====

    /** 群消息帧（MSG）：id = envelope id；msgId = 逻辑消息 ID（重发帧换 id 保持 msgId）。 */
    private fun groupMsgFrame(
        id: String, srcId: String, groupId: String, text: String, msgId: String,
        displayName: String = "", groupName: String? = null, ttl: Int = 8, ts: Long = 1,
    ) = MeshFrame(
        FrameType.DATA,
        MeshJson.encodeEnvelope(
            MeshEnvelope(
                id = id, kind = "GROUP", srcId = srcId, dstId = groupId, convId = "group-$groupId",
                ttl = ttl, ts = ts,
                body = GroupBody(op = "MSG", groupId = groupId, msgId = msgId, groupName = groupName, text = text, displayName = displayName),
            ),
        ).toByteArray(),
    )

    /** 群创建帧（JOIN，仅传播群名）。 */
    private fun groupJoinFrame(id: String, srcId: String, groupId: String, groupName: String, ts: Long = 1) = MeshFrame(
        FrameType.DATA,
        MeshJson.encodeEnvelope(
            MeshEnvelope(
                id = id, kind = "GROUP", srcId = srcId, dstId = groupId, convId = "group-$groupId",
                ttl = 8, ts = ts,
                body = GroupBody(op = "JOIN", groupId = groupId, groupName = groupName),
            ),
        ).toByteArray(),
    )

    @Test
    fun `group message delivered to subscriber and stored in group conversation`() {
        val transport = CountingTransport()
        val store = InMemoryMeshStore()
        val service = MeshService(
            transport = transport, store = store,
            identity = LocalIdentity(shortId = "ME"), dedup = DedupCache(),
        )
        service.joinGroup("G1", "应急群")
        assertEquals(setOf("G1"), service.joinedGroups.value)
        service.handleFrame(groupMsgFrame("e1", "A", "G1", "hi", "m1", "小明", ts = 1))
        val stored = store.queryMessages("group-G1")
        assertEquals(1, stored.size)
        assertEquals("hi", stored.first().text)
        assertEquals("GROUP", stored.first().kind)
        assertEquals(MessageStatus.DELIVERED, stored.first().status)
        assertEquals("接收方落库 id = 信封 id（真实首帧时 = msgId）", "e1", stored.first().id)
        service.stop()
    }

    @Test
    fun `group message forwarded by non subscriber without storing`() {
        val transport = CountingTransport()
        val store = InMemoryMeshStore()
        val service = MeshService(
            transport = transport, store = store,
            identity = LocalIdentity(shortId = "ME"), dedup = DedupCache(),
        )
        service.handleFrame(groupMsgFrame("e1", "A", "G1", "hi", "m1", "小明", ttl = 8, ts = 1))
        Thread.sleep(300)   // 等转发抖动 50-250ms
        val forwarded = transport.frames
            .filter { it.type == FrameType.DATA }
            .map { MeshJson.decodeEnvelope(it.payloadText) }
            .firstOrNull { it.dstId == "G1" }
        assertTrue("未订阅节点应转发群消息", forwarded != null)
        assertEquals(7, forwarded?.ttl)
        assertTrue("未订阅不落库", store.queryMessages("group-G1").isEmpty())
        service.stop()
    }

    @Test
    fun `group body round trips groupId and displayName`() {
        val body = GroupBody(op = "MSG", groupId = "G1", msgId = "m1", groupName = "应急群", text = "hi", displayName = "小明")
        val env = MeshEnvelope(id = "m1", kind = "GROUP", srcId = "A", dstId = "G1", convId = "group-G1", ttl = 8, ts = 1, body = body)
        val decoded = MeshJson.decodeEnvelope(MeshJson.encodeEnvelope(env))
        val g = decoded.body as GroupBody
        assertEquals("GROUP", decoded.kind)
        assertEquals("G1", g.groupId)
        assertEquals("m1", g.msgId)
        assertEquals("应急群", g.groupName)
        assertEquals("hi", g.text)
        assertEquals("小明", g.displayName)
    }

    @Test
    fun `group name learned from group message and join frame`() {
        val store = InMemoryMeshStore()
        val service = MeshService(
            transport = CountingTransport(), store = store,
            identity = LocalIdentity(shortId = "ME"), dedup = DedupCache(),
        )
        service.joinGroup("G1")
        service.handleFrame(groupMsgFrame("e1", "A", "G1", "hi", "m1", "小明", groupName = "应急群", ts = 1))
        Thread.sleep(100)   // groups 合成流异步刷新
        assertTrue("随消息学习群名", service.groups.value.any { it.id == "G1" && it.name == "应急群" })
        // JOIN 帧先学到群名、后加入：加入后应显示已学群名
        service.handleFrame(groupJoinFrame("j1", "B", "G2", "通知群", ts = 2))
        service.joinGroup("G2")
        Thread.sleep(100)
        assertTrue("随创建帧学习群名", service.groups.value.any { it.id == "G2" && it.name == "通知群" })
        service.stop()
    }

    @Test
    fun `group message ttl exhausted not forwarded`() {
        val transport = CountingTransport()
        val store = InMemoryMeshStore()
        val service = MeshService(
            transport = transport, store = store,
            identity = LocalIdentity(shortId = "ME"), dedup = DedupCache(),
        )
        service.handleFrame(groupMsgFrame("e1", "A", "G1", "hi", "m1", "小明", ttl = 1, ts = 1))
        Thread.sleep(300)
        assertTrue("TTL≤1 不再转发", transport.frames.none { it.type == FrameType.DATA })
        assertTrue("TTL 耗尽不落库", store.queryMessages("group-G1").isEmpty())
        service.stop()
    }

    @Test
    fun `group receipt throttled and confirms sender`() {
        // 成员 B（订阅者，测试注入 100% 回执概率 + 0ms 延迟；生产默认 30% + 0-500ms）
        val memberStore = InMemoryMeshStore()
        val memberTransport = CountingTransport()
        val member = MeshService(
            transport = memberTransport, store = memberStore,
            identity = LocalIdentity(shortId = "B"), dedup = DedupCache(),
            groupReceiptChance = 1.0, groupReceiptDelayMaxMs = 0L,
        )
        member.joinGroup("G1")
        member.handleFrame(groupMsgFrame("e1", "A", "G1", "hi", "m1", "小明", ts = 1))
        Thread.sleep(100)   // 等 0ms 延迟回执协程发出
        val receipts = memberTransport.frames.filter { it.type == FrameType.RECEIPT }
        assertEquals("成员应以 30%（测试注入 100%）概率回执一次", 1, receipts.size)
        assertTrue("回执 id = G\$msgId", receipts.first().payloadText.contains("G\$m1"))
        // 同 msgId 的新 id 重发帧（e2）：内容指纹去重 → 不落库、不回执
        member.handleFrame(groupMsgFrame("e2", "A", "G1", "hi", "m1", "小明", ts = 5_001))
        Thread.sleep(100)
        assertEquals("新 id 重发不回执", 1, memberTransport.frames.count { it.type == FrameType.RECEIPT })
        assertEquals("新 id 重发不重复落库", 1, memberStore.queryMessages("group-G1").size)
        // 发送方 A：收任一有效回执（G$m1）→ "已送达"（至少一个成员确认）
        val senderStore = InMemoryMeshStore()
        val sender = MeshService(
            transport = CountingTransport(), store = senderStore,
            identity = LocalIdentity(shortId = "A"), dedup = DedupCache(),
        )
        sender.seedGroupKeyForTesting("G1")   // v1.1.57 群聊对称加密：发送需群密钥
        sender.sendGroupMessageWithId("G1", "hi", "m1")
        assertEquals(MessageStatus.SENDING, senderStore.queryMessages("group-G1").first().status)
        sender.handleFrame(receiptFrame("G\$m1"))
        assertEquals(MessageStatus.DELIVERED, senderStore.queryMessages("group-G1").first().status)
        sender.stop()
        member.stop()
    }

    @Test
    fun `group message resent with new id until timeout`() {
        val transport = CountingTransport()
        val store = InMemoryMeshStore()
        val service = MeshService(
            transport = transport, store = store,
            identity = LocalIdentity(shortId = "ME"), dedup = DedupCache(),
        )
        val t0 = System.currentTimeMillis()
        val gk = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }   // v1.1.57 显式群密钥（测试解密验证用）
        service.seedGroupKeyForTesting("G1", gk)
        service.sendGroupMessageWithId("G1", "hi", "m1")
        val firstId = MeshJson.decodeEnvelope(transport.frames.last { it.type == FrameType.DATA }.payloadText).id
        assertEquals("首帧 id = 逻辑 msgId", "m1", firstId)

        // 5s 前不重发（+100ms 裕量防毫秒级时钟偏移）
        service.resendPendingGroupReceipts(t0 + 4_000)
        assertEquals(1, transport.frames.count { it.type == FrameType.DATA })

        // 5s → 新 envelope id 重发（msgId 不变——回执按 msgId 匹配）；body 群密钥加密（SecBody）
        service.resendPendingGroupReceipts(t0 + 5_100)
        val resendEnv = MeshJson.decodeEnvelope(transport.frames.last { it.type == FrameType.DATA }.payloadText)
        assertEquals("重发必须新 envelope id", false, resendEnv.id == firstId)
        val sec = resendEnv.body as SecBody
        val inner = MeshCrypto.decrypt(gk, sec.iv, sec.cipher, "GROUP|group-G1")!!
        val gb = MeshJson.json.decodeFromString(EnvelopeBody.serializer(), inner.decodeToString()) as GroupBody
        assertEquals("m1", gb.msgId)
        assertEquals("hi", gb.text)

        // ≤3 次重发（t+5/10/15s）；第 4 个窗口（t+20s）不再重发
        service.resendPendingGroupReceipts(t0 + 10_100)
        service.resendPendingGroupReceipts(t0 + 15_100)
        service.resendPendingGroupReceipts(t0 + 20_100)
        assertEquals("首帧 + 3 次重发 = 4 帧", 4, transport.frames.count { it.type == FrameType.DATA })

        // 30s 总超时 → "可能未送达"（FAILED，UI 渲染琥珀）
        service.resendPendingGroupReceipts(t0 + 30_100)
        assertEquals(MessageStatus.FAILED, store.queryMessages("group-G1").first().status)
        service.stop()
    }

    @Test
    fun `undelivered group messages restored on start and resent with fresh timeout`() {
        val transport = CountingTransport()
        val store = InMemoryMeshStore()
        // 模拟进程被杀前未确认的群消息（SENDING 落库，ts 已是 1 小时前——重启后 30s 超时必须重新计时）
        store.insertMessage(
            StoredMessage(
                id = "m1", convId = "group-G1", kind = "GROUP", srcId = "ME", dstId = "G1",
                text = "hi", status = MessageStatus.SENDING, ts = System.currentTimeMillis() - 3_600_000,
            ),
        )
        val service = MeshService(
            transport = transport, store = store, identity = LocalIdentity(shortId = "ME"), dedup = DedupCache(),
        )
        service.start()
        val gk = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }   // v1.1.57 显式群密钥（恢复重发解密验证用）
        service.seedGroupKeyForTesting("G1", gk)
        // 恢复后立即可用新 id 重发（lastSentAt 置过期，不等 5s）
        val t0 = System.currentTimeMillis()
        service.resendPendingGroupReceipts(t0 + 100)
        val resendEnv = transport.frames
            .filter { it.type == FrameType.DATA }
            .map { MeshJson.decodeEnvelope(it.payloadText) }
            .lastOrNull { it.dstId == "G1" }
        assertTrue("重启后未确认群消息应立即重发", resendEnv != null)
        val gsec = resendEnv!!.body as SecBody
        val ginner = MeshCrypto.decrypt(gk, gsec.iv, gsec.cipher, "GROUP|group-G1")!!
        val gresend = MeshJson.json.decodeFromString(EnvelopeBody.serializer(), ginner.decodeToString()) as GroupBody
        assertEquals("m1", gresend.msgId)
        assertEquals("重发必须新 id", false, resendEnv.id == "m1")
        // 30s 总超时从重启时刻重新计时（旧 ts 已过 1 小时，若沿用会立即标 FAILED）。
        // 注意 t+29s 时距上次重发已满 5s 会再触发一次重发（lastSentAt 推进），
        // 故超时断言推到 t+34s（间隔满 5s 且 30s 总超时已过）。
        service.resendPendingGroupReceipts(t0 + 29_000)
        assertEquals("30s 前仍 SENDING", MessageStatus.SENDING, store.queryMessages("group-G1").first().status)
        service.resendPendingGroupReceipts(t0 + 34_100)
        assertEquals("30s 超时标可能未送达", MessageStatus.FAILED, store.queryMessages("group-G1").first().status)
        service.stop()
    }

    @Test
    fun `same text twice with different msgId both delivered`() {
        // 审查 M2 修复：指纹锚为 msgId（非 text）——同群同发送者连发相同文本是合法消息，不得误杀
        val store = InMemoryMeshStore()
        val service = MeshService(
            transport = CountingTransport(), store = store,
            identity = LocalIdentity(shortId = "ME"), dedup = DedupCache(),
        )
        service.joinGroup("G1")
        service.handleFrame(groupMsgFrame("e1", "A", "G1", "好的", "m1", "小明", ts = 1))
        service.handleFrame(groupMsgFrame("e2", "A", "G1", "好的", "m2", "小明", ts = 2))
        assertEquals("同文本不同 msgId 都落库", 2, store.queryMessages("group-G1").size)
        // 同 msgId 的新 id 重发帧：判重复不落库
        service.handleFrame(groupMsgFrame("e3", "A", "G1", "好的", "m2", "小明", ts = 3))
        assertEquals("同 msgId 重发不重复落库", 2, store.queryMessages("group-G1").size)
        service.stop()
    }

    @Test
    fun `group conversations not restored as known peers`() {
        // 审查 S3 修复：peers 表为空 + 消息历史只有群消息 → 群 ID 不得被当对端节点恢复
        val store = InMemoryMeshStore()
        store.insertMessage(
            StoredMessage(
                id = "m1", convId = "group-G1", kind = "GROUP", srcId = "A", dstId = "G1",
                text = "hi", ts = 1, status = MessageStatus.DELIVERED,
            ),
        )
        val service = MeshService(
            transport = CountingTransport(), store = store,
            identity = LocalIdentity(shortId = "ME"), dedup = DedupCache(),
        )
        service.start()
        Thread.sleep(100)
        assertTrue("群 ID 不得被当对端节点恢复", service.peers.value.none { it.shortId == "G1" })
        service.stop()
    }

    @Test
    fun `group member count tracks distinct senders`() {
        // v1.1.54：群成员数 = 本机见过的去重发言者（广播域无成员表，近似统计）
        val store = InMemoryMeshStore()
        val service = MeshService(
            transport = CountingTransport(), store = store,
            identity = LocalIdentity(shortId = "ME"), dedup = DedupCache(),
        )
        service.joinGroup("G1")
        service.handleFrame(groupMsgFrame("e1", "A", "G1", "hi", "m1", "小明", ts = 1))
        service.handleFrame(groupMsgFrame("e2", "B", "G1", "yo", "m2", "小刚", ts = 2))
        // 同发送者再来一条：成员数不重复计数
        service.handleFrame(groupMsgFrame("e3", "A", "G1", "again", "m3", "小明", ts = 3))
        Thread.sleep(100)   // groups 合成流异步刷新
        val group = service.groups.value.first { it.id == "G1" }
        assertEquals("去重发言者数", 2, group.memberCount)
        service.stop()
    }

    // ===== v1.1.57 端到端加密（E2EE）=====

    private fun inviteWithKey(srcId: String, dstId: String, pubKey: String) = MeshFrame(
        FrameType.DATA,
        MeshJson.encodeEnvelope(
            MeshEnvelope(
                id = UUID.randomUUID().toString(), kind = "INVITE",
                srcId = srcId, dstId = dstId, convId = "conv-$dstId",
                ttl = 8, ts = 0, body = TextBody("对话请求", pubKey = pubKey),
            ),
        ).toByteArray(),
    )

    private fun ackWithKey(srcId: String, dstId: String, pubKey: String) = MeshFrame(
        FrameType.DATA,
        MeshJson.encodeEnvelope(
            MeshEnvelope(
                id = UUID.randomUUID().toString(), kind = "INVITE_ACK",
                srcId = srcId, dstId = dstId, convId = "conv-$dstId",
                ttl = 8, ts = 0, body = TextBody("已接受", pubKey = pubKey),
            ),
        ).toByteArray(),
    )

    @Test
    fun `e2ee handshake derives matching keys and encrypted text round trips`() {
        val aStore = InMemoryMeshStore()
        val bStore = InMemoryMeshStore()
        val aTransport = CountingTransport()
        val bTransport = CountingTransport()
        val a = MeshService(
            transport = aTransport, store = aStore, identity = LocalIdentity(shortId = "A"), dedup = DedupCache(),
        )
        val b = MeshService(
            transport = bTransport, store = bStore, identity = LocalIdentity(shortId = "B"), dedup = DedupCache(),
        )
        // 握手：A INVITE（带 A 公钥）→ B 派生；B ACK（带 B 公钥）→ A 派生
        b.handleFrame(inviteWithKey("A", "B", a.publicKeyB64ForTest))
        a.handleFrame(ackWithKey("B", "A", b.publicKeyB64ForTest))
        // A → B 发送（有会话密钥 → 加密）
        assertTrue("有会话密钥应可发送", a.sendText("conv-B", "B", "hello"))
        val env = MeshJson.decodeEnvelope(aTransport.frames.last { it.type == FrameType.DATA }.payloadText)
        assertTrue("空中消息应为密文", env.body is SecBody)
        assertTrue("路由字段保持明文", env.dstId == "B" && env.kind == "TEXT")
        // B 收到 → 解密 → 落库（conv-A = 发送者视角）
        b.handleFrame(MeshFrame(FrameType.DATA, MeshJson.encodeEnvelope(env).toByteArray()))
        val stored = bStore.queryMessages("conv-A").first()
        assertEquals("hello", stored.text)
        assertEquals(MessageStatus.DELIVERED, stored.status)
        // 反向 B → A 同样加密往返
        assertTrue("B 发 A", b.sendText("conv-A", "A", "reply"))
        val env2 = MeshJson.decodeEnvelope(bTransport.frames.last { it.type == FrameType.DATA }.payloadText)
        assertTrue(env2.body is SecBody)
        a.handleFrame(MeshFrame(FrameType.DATA, MeshJson.encodeEnvelope(env2).toByteArray()))
        assertTrue("A 收到 B 加密回复", aStore.queryMessages("conv-B").any { it.text == "reply" })
        a.stop(); b.stop()
    }

    @Test
    fun `text send without session key is refused under mandatory encryption`() {
        val transport = CountingTransport()
        val store = InMemoryMeshStore()
        val service = MeshService(
            transport = transport, store = store,
            identity = LocalIdentity(shortId = "ME"), dedup = DedupCache(),
        )
        assertFalse("无会话密钥应拒绝发送", service.sendText("conv-OTHER", "OTHER", "hi"))
        assertTrue("拒绝发送不应广播任何帧", transport.frames.isEmpty())
        // 自环仍可用（本机内部投递，不经空中）
        assertTrue(service.sendText("conv-ME", "ME", "self"))
        assertEquals("self", store.queryMessages("conv-ME").first().text)
        service.stop()
    }

    @Test
    fun `plaintext text from legacy peer still delivered for upgrade transition`() {
        val store = InMemoryMeshStore()
        val service = MeshService(
            transport = CountingTransport(), store = store,
            identity = LocalIdentity(shortId = "ME"), dedup = DedupCache(),
        )
        // 老版本（明文 TextBody，无 pubKey）→ 新版本保留投递（升级过渡可读）
        service.handleFrame(textFrame("m1", "OTHER", "ME", "legacy hi"))
        assertEquals("legacy hi", store.queryMessages("conv-OTHER").first().text)
        assertEquals(MessageStatus.DELIVERED, store.queryMessages("conv-OTHER").first().status)
        service.stop()
    }

    // ===== v1.1.74 MITM 防御（密钥连续性 TOFU）=====

    private class MemoryPeerKeyStore : PeerKeyStore {
        private val map = mutableMapOf<String, String>()
        override fun fingerprint(peerId: String): String? = map[peerId]
        override fun saveFingerprint(peerId: String, fp: String) { map[peerId] = fp }
    }

    @Test
    fun `first handshake records peer key fingerprint (TOFU)`() {
        val keyStore = MemoryPeerKeyStore()
        val service = MeshService(
            transport = CountingTransport(), store = InMemoryMeshStore(),
            identity = LocalIdentity(shortId = "B"), dedup = DedupCache(),
            peerKeyStore = keyStore,
        )
        val aPubB64 = MeshCrypto.publicKeyB64(MeshCrypto.generateKeyPair())
        // A INVITE（带 A 公钥）→ B 派生会话密钥 + 首次信任并记录指纹
        service.handleFrame(inviteWithKey("A", "B", aPubB64))
        assertEquals("首次握手应记录对端指纹", MeshCrypto.fingerprint(aPubB64), service.peerFingerprint("A"))
        assertTrue("首次握手不应标记身份变更", "A" !in service.peerKeyChanged.value)
        service.stop()
    }

    @Test
    fun `same peer key fingerprint does not flag identity change`() {
        val keyStore = MemoryPeerKeyStore()
        val service = MeshService(
            transport = CountingTransport(), store = InMemoryMeshStore(),
            identity = LocalIdentity(shortId = "B"), dedup = DedupCache(),
            peerKeyStore = keyStore,
        )
        val aPubB64 = MeshCrypto.publicKeyB64(MeshCrypto.generateKeyPair())
        // 首次握手 → 记录；相同公钥再次握手（重连/重复 ACK）→ 指纹一致，不告警
        service.handleFrame(inviteWithKey("A", "B", aPubB64))
        service.handleFrame(ackWithKey("A", "B", aPubB64))
        assertTrue("指纹一致不应标记身份变更", "A" !in service.peerKeyChanged.value)
        service.stop()
    }

    @Test
    fun `changed peer key fingerprint flags identity change (possible MITM)`() {
        val keyStore = MemoryPeerKeyStore()
        val service = MeshService(
            transport = CountingTransport(), store = InMemoryMeshStore(),
            identity = LocalIdentity(shortId = "B"), dedup = DedupCache(),
            peerKeyStore = keyStore,
        )
        val firstPubB64 = MeshCrypto.publicKeyB64(MeshCrypto.generateKeyPair())
        service.handleFrame(inviteWithKey("A", "B", firstPubB64))
        assertTrue("首次握手不应标记身份变更", "A" !in service.peerKeyChanged.value)
        // 对方公钥更换（重启/重装或被中间人劫持）→ 指纹不一致 → 标记身份变更告警
        val secondPubB64 = MeshCrypto.publicKeyB64(MeshCrypto.generateKeyPair())
        assertFalse("测试前提：两次公钥指纹必须不同", MeshCrypto.fingerprint(firstPubB64) == MeshCrypto.fingerprint(secondPubB64))
        service.handleFrame(inviteWithKey("A", "B", secondPubB64))
        assertTrue("指纹变化应标记身份变更（可能 MITM）", "A" in service.peerKeyChanged.value)
        assertEquals("记录保留首次指纹供人工比对", MeshCrypto.fingerprint(firstPubB64), service.peerFingerprint("A"))
        service.stop()
    }

    @Test
    fun `group key distributed via join frame enables encrypted group messages`() {
        val creatorStore = InMemoryMeshStore()
        val memberStore = InMemoryMeshStore()
        val creatorTransport = CountingTransport()
        val memberTransport = CountingTransport()
        val creator = MeshService(
            transport = creatorTransport, store = creatorStore,
            identity = LocalIdentity(shortId = "A"), dedup = DedupCache(),
        )
        val member = MeshService(
            transport = memberTransport, store = memberStore,
            identity = LocalIdentity(shortId = "B"), dedup = DedupCache(),
        )
        // A 创建群：生成群密钥随 JOIN 帧广播
        val gid = creator.createGroup("应急")
        val joinEnv = MeshJson.decodeEnvelope(creatorTransport.frames.last { it.type == FrameType.DATA }.payloadText)
        // B 订阅 + 收 JOIN → 学习群密钥
        member.joinGroup(gid)
        member.handleFrame(MeshFrame(FrameType.DATA, MeshJson.encodeEnvelope(joinEnv).toByteArray()))
        // A 发群消息（群密钥加密）
        creator.sendGroupMessageWithId(gid, "hi all", "m1")
        val env = MeshJson.decodeEnvelope(creatorTransport.frames.last { it.type == FrameType.DATA }.payloadText)
        assertTrue("群消息应为密文", env.body is SecBody)
        // B 收到 → 群密钥解密 → 落库
        member.handleFrame(MeshFrame(FrameType.DATA, MeshJson.encodeEnvelope(env).toByteArray()))
        val stored = memberStore.queryMessages("group-$gid").first()
        assertEquals("hi all", stored.text)
        assertEquals(MessageStatus.DELIVERED, stored.status)
        creator.stop(); member.stop()
    }

    // ===== v1.1.66 频道系统 =====

    /** 测试辅助：emitPeer 异步进 peerEntries，refreshPeers 需 heartbeatTick 触发；轮询直至节点进入 peers 流。 */
    private fun awaitPeerDiscovered(service: MeshService, shortId: String) {
        var guard = 0
        while (guard++ < 100) {
            service.heartbeatTick(System.currentTimeMillis())
            if (service.peers.value.any { it.shortId == shortId }) return
            Thread.sleep(20)
        }
        assertTrue("节点 $shortId 应进入 peers 流", service.peers.value.any { it.shortId == shortId })
    }

    @Test
    fun `setChannel switches fingerprint and clears peers`() = runTest {
        val identity = LocalIdentity(shortId = "ME")
        val transport = InMemoryTransport()
        val store = InMemoryMeshStore()
        val service = MeshService(transport = transport, store = store, identity = identity, dedup = DedupCache())
        service.start()
        transport.emitPeer(MeshPeerInfo(shortId = "F1", deviceAddress = "AA:BB:CC", rssi = -50, channelFingerprint = 0L))
        awaitPeerDiscovered(service, "F1")
        assertTrue("公共频道下 F1 可见", service.peers.value.any { it.shortId == "F1" })

        service.setChannel("mesh-team")
        assertEquals("频道名状态更新", "mesh-team", service.channelName.value)
        assertEquals("传输层收到指纹", ChannelFingerprint.of("mesh-team"), transport.lastChannelFingerprint)
        assertTrue("切换后节点表清空", service.peers.value.isEmpty())

        service.setChannel(null)
        assertNull("切回公共频道", service.channelName.value)
        assertEquals("公共频道指纹归零", 0L, transport.lastChannelFingerprint)
        service.stop()
    }

    @Test
    fun `sendText refuses cross-channel target in private channel`() = runTest {
        val identity = LocalIdentity(shortId = "ME")
        val transport = InMemoryTransport()
        val store = InMemoryMeshStore()
        val service = MeshService(transport = transport, store = store, identity = identity, dedup = DedupCache())
        service.start()
        service.setChannel("mesh-team")
        val fp = ChannelFingerprint.of("mesh-team")
        // 同频道节点：可发送
        transport.emitPeer(MeshPeerInfo(shortId = "SAME", deviceAddress = "AA:BB:CC", rssi = -50, channelFingerprint = fp))
        awaitPeerDiscovered(service, "SAME")
        service.seedSessionKeyForTesting("SAME")
        assertTrue("同频道可发送", service.sendText("conv-SAME", "SAME", "hi"))
        // 跨频道节点（已记录但指纹不匹配）：拒绝
        transport.emitPeer(MeshPeerInfo(shortId = "CROSS", deviceAddress = "DD:EE:FF", rssi = -50, channelFingerprint = ChannelFingerprint.of("other")))
        awaitPeerDiscovered(service, "CROSS")
        service.seedSessionKeyForTesting("CROSS")
        assertFalse("跨频道拒绝发送", service.sendText("conv-CROSS", "CROSS", "hi"))
        // 未发现节点（peerEntries 无记录）：拒绝
        assertFalse("未发现节点拒绝发送", service.sendText("conv-GHOST", "GHOST", "hi"))
        service.stop()
    }

    @Test
    fun `sendText still works in public channel without fingerprint match`() = runTest {
        val identity = LocalIdentity(shortId = "ME")
        val transport = InMemoryTransport()
        val store = InMemoryMeshStore()
        val service = MeshService(transport = transport, store = store, identity = identity, dedup = DedupCache())
        service.start()
        // 公共频道（指纹 0）：目标节点未发现也允许发送（保持存量 outbox 排队行为）
        service.seedSessionKeyForTesting("GHOST")
        assertTrue("公共频道未发现节点可发送", service.sendText("conv-GHOST", "GHOST", "hi"))
        service.stop()
    }

    @Test
    fun `handleEnvelope drops cross-channel frame from known peer`() = runTest {
        val identity = LocalIdentity(shortId = "ME")
        val transport = InMemoryTransport()
        val store = InMemoryMeshStore()
        val service = MeshService(transport = transport, store = store, identity = identity, dedup = DedupCache())
        service.start()
        service.setChannel("mesh-team")
        transport.emitPeer(MeshPeerInfo(shortId = "CROSS", deviceAddress = "AA:BB:CC", rssi = -50, channelFingerprint = ChannelFingerprint.of("other")))
        awaitPeerDiscovered(service, "CROSS")
        // 跨频道节点发来 TEXT：被丢弃（不落库）
        service.handleFrame(textFrame("t1", "CROSS", service.shortId, "hello"))
        assertTrue("跨频道消息不落库", store.observeMessages("conv-CROSS").first().isEmpty())
        service.stop()
    }

    // ===== v1.1.67 隔离彻底化（断连 + 过滤）=====

    @Test
    fun `blockPeer disconnects peer and pushes blocked filter to transport`() = runTest {
        val identity = LocalIdentity(shortId = "ME")
        val transport = InMemoryTransport()
        val store = InMemoryMeshStore()
        val service = MeshService(transport = transport, store = store, identity = identity, dedup = DedupCache())
        service.start()
        transport.emitPeer(MeshPeerInfo(shortId = "B", deviceAddress = "AA:BB:CC", rssi = -50))
        awaitPeerDiscovered(service, "B")
        service.blockPeer("B")
        assertEquals("拉黑即断开已建立连接", "B", transport.lastDisconnectedPeer)
        assertEquals("拉黑集合下发给传输层", setOf("B"), transport.lastBlockedPeers)
        service.stop()
    }

    @Test
    fun `unblockPeer removes peer from blocked filter`() = runTest {
        val identity = LocalIdentity(shortId = "ME")
        val transport = InMemoryTransport()
        val store = InMemoryMeshStore()
        val service = MeshService(transport = transport, store = store, identity = identity, dedup = DedupCache())
        service.start()
        service.blockPeer("B")
        service.unblockPeer("B")
        assertEquals("解除拉黑同步过滤集合", emptySet<String>(), transport.lastBlockedPeers)
        service.stop()
    }

    @Test
    fun `setChannel disconnects all old connections`() = runTest {
        val identity = LocalIdentity(shortId = "ME")
        val transport = InMemoryTransport()
        val store = InMemoryMeshStore()
        val service = MeshService(transport = transport, store = store, identity = identity, dedup = DedupCache())
        service.start()
        service.setChannel("mesh-team")
        assertEquals("换频道断开全部旧连接", 1, transport.disconnectAllCount)
        service.stop()
    }

    @Test
    fun `setDiscoveryMode CLOSED disconnects all and NORMAL does not`() = runTest {
        val identity = LocalIdentity(shortId = "ME")
        val transport = InMemoryTransport()
        val store = InMemoryMeshStore()
        val service = MeshService(transport = transport, store = store, identity = identity, dedup = DedupCache())
        service.start()
        service.setDiscoveryMode(com.meshchat.app.mesh.transport.DiscoveryMode.CLOSED)
        assertEquals("关闭搜索断开全部连接", 1, transport.disconnectAllCount)
        service.setDiscoveryMode(com.meshchat.app.mesh.transport.DiscoveryMode.NORMAL)
        assertEquals("恢复搜索不额外断连", 1, transport.disconnectAllCount)
        service.stop()
    }
}
