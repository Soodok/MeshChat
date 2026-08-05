package com.meshchat.app.mesh.service

import com.meshchat.app.mesh.identity.LocalIdentity
import com.meshchat.app.mesh.protocol.FileBody
import com.meshchat.app.mesh.protocol.FrameType
import com.meshchat.app.mesh.protocol.MeshEnvelope
import com.meshchat.app.mesh.protocol.MeshFrame
import com.meshchat.app.mesh.protocol.MeshJson
import com.meshchat.app.mesh.protocol.PresenceBody
import com.meshchat.app.mesh.protocol.TextBody
import com.meshchat.app.mesh.routing.DedupCache
import com.meshchat.app.mesh.storage.InMemoryMeshStore
import com.meshchat.app.mesh.storage.MessageStatus
import com.meshchat.app.mesh.storage.StoredMessage
import com.meshchat.app.mesh.transfer.FileSaver
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
    fun `file chunk goes through rfcomm sendTo when connected`() = runTest {
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
        var guard = 0
        while (rfcommSent.isEmpty() && transport.broadcastCount == 0 && guard++ < 100) kotlinx.coroutines.delay(20)
        assertTrue("RFCOMM 连接时应走 sendTo 而非 BLE broadcast", rfcommSent.isNotEmpty())
        assertEquals("OTHER", rfcommSent.first().first)
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
    fun `heartbeat pings at most once per second`() {
        val transport = CountingTransport()
        val service = MeshService(
            transport = transport, store = InMemoryMeshStore(), identity = LocalIdentity(shortId = "ME"), dedup = DedupCache(),
        )
        val t0 = System.currentTimeMillis()
        service.heartbeatTick(t0)                    // 首帧
        service.heartbeatTick(t0 + 200)
        service.heartbeatTick(t0 + 400)
        service.heartbeatTick(t0 + 600)
        service.heartbeatTick(t0 + 800)
        assertEquals(1, dataKinds(transport.frames).count { it == "PING" })
        service.heartbeatTick(t0 + 1_000)            // 满 1s → 第二帧
        assertEquals(2, dataKinds(transport.frames).count { it == "PING" })
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
        val received = mutableListOf<Triple<String, String, String>>()
        val service = MeshService(
            transport = transport, store = InMemoryMeshStore(), identity = LocalIdentity(shortId = "ME"), dedup = DedupCache(),
            onIncomingMessage = { fromId, fromName, text -> received.add(Triple(fromId, fromName, text)) },
        )
        service.start()
        service.handleFrame(pingFrame("OTHER", "老王"))   // 先让昵称入表
        service.handleFrame(textFrame("t1", "OTHER", "ME", "你好"))
        assertTrue(received.isNotEmpty())
        assertEquals("OTHER", received.first().first)
        assertEquals("老王", received.first().second)
        assertEquals("你好", received.first().third)
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
            onIncomingMessage = { _, _, _ -> notified++ },
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
        service.heartbeatTick(t0)          // PING #1
        service.heartbeatTick(t0 + 1_000)  // PING #2
        service.heartbeatTick(t0 + 2_000)  // PING #3 → 携带 relays
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
}
