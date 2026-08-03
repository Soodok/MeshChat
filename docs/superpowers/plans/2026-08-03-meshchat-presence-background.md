# MeshChat 后台常驻 + 状态校准 + 节点命名实现计划（v0.14.0）

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** MeshChat 支持后台/息屏收消息弹通知；1s 心跳 PING/PONG 双向校准节点在线状态；用户自定义昵称交换与持久化；会话关系持久化（重启不丢、点击直达）。

**架构：** 新增 `PresenceBody`（PING/PONG 信封载荷）与 `MeshChatService`（前台服务宿主）；`MeshService` 加心跳逻辑（1s 广播 PING、收 PING 回 PONG、3s 超时判 lost）、`SessionStore` 会话持久化、`onIncomingMessage` 通知回调；UI 加昵称/后台开关设置、节点显示「昵称·短ID」。

**技术栈：** Kotlin + android.bluetooth(BLE 已有) + kotlinx.coroutines + Compose + Room + NotificationManager。

**规格：** `docs/superpowers/specs/2026-08-03-meshchat-presence-background-design.md`

---

## 文件结构

**新建：**
- `app/src/main/java/com/meshchat/app/mesh/service/SessionStore.kt` — 会话持久化接口 + SharedPrefs 实现
- `app/src/main/java/com/meshchat/app/mesh/service/NotificationHelper.kt` — 通知渠道/常驻通知/消息通知
- `app/src/main/java/com/meshchat/app/mesh/service/MeshChatService.kt` — 前台服务宿主

**修改：**
- `app/src/main/java/com/meshchat/app/mesh/protocol/MeshEnvelope.kt` — + `PresenceBody`（@SerialName("PING")）
- `app/src/main/java/com/meshchat/app/mesh/transport/MeshTransport.kt` — `MeshPeerInfo` + `displayName: String = ""`
- `app/src/main/java/com/meshchat/app/mesh/storage/MeshStore.kt` / `MeshDatabase.kt` / `InMemoryMeshStore.kt` — + `upsertPeer`
- `app/src/main/java/com/meshchat/app/mesh/service/MeshService.kt` — 心跳 + sessionStore + onIncomingMessage
- `app/src/main/java/com/meshchat/app/MeshChatApplication.kt` — displayName/backgroundEnabled 持久化 + startMesh 启前台服务
- `app/src/main/java/com/meshchat/app/MainActivity.kt` — POST_NOTIFICATIONS 请求
- `app/src/main/AndroidManifest.xml` — 服务注册 + 前台服务/通知权限
- `app/src/main/java/com/meshchat/app/data/UiModels.kt` — `MeshPeer` + `shortId`
- `app/src/main/java/com/meshchat/app/data/MeshRepository.kt` — peers 映射昵称
- `app/src/main/java/com/meshchat/app/ui/screens/MeshScreen.kt` / `MeshChatHome.kt` — 昵称显示 + 会话标题
- `app/src/main/java/com/meshchat/app/ui/screens/ProfileDetailScreens.kt` — 设置页昵称 + 后台开关
- `app/src/main/java/com/meshchat/app/ui/MeshChatViewModel.kt` / `MeshChatApp.kt` / `MeshChatViewModelFactory.kt` — 传参
- `app/src/test/java/com/meshchat/app/mesh/protocol/MeshEnvelopeTest.kt` — PING 编解码
- `app/src/test/java/com/meshchat/app/mesh/service/MeshServiceTest.kt` — 心跳/持久化/回调测试
- `app/build.gradle.kts` — versionCode 26 / versionName 0.14.0

**测试命令：** `.\gradlew.bat testDebugUnitTest` 与 `.\gradlew.bat assembleDebug`。

---

### 任务 1：协议与身份（PresenceBody + MeshPeerInfo.displayName + 本机昵称持久化）

**文件：**
- 修改：`app/src/main/java/com/meshchat/app/mesh/protocol/MeshEnvelope.kt`
- 修改：`app/src/main/java/com/meshchat/app/mesh/transport/MeshTransport.kt`
- 修改：`app/src/main/java/com/meshchat/app/MeshChatApplication.kt`
- 测试：`app/src/test/java/com/meshchat/app/mesh/protocol/MeshEnvelopeTest.kt`

- [ ] **步骤 1：编写失败的测试（PING body 编解码）**

在 `MeshEnvelopeTest.kt` 追加：

```kotlin
    @Test
    fun `presence body roundtrips with PING kind`() {
        val envelope = MeshEnvelope(
            id = "p1", kind = "PING", srcId = "AB12", dstId = "",
            convId = "conv-AB12", ttl = 8, ts = 1,
            body = PresenceBody(displayName = "老王"),
        )
        val decoded = MeshJson.decodeEnvelope(MeshJson.encodeEnvelope(envelope))
        assertEquals("PING", decoded.kind)
        assertEquals("老王", (decoded.body as PresenceBody).displayName)
    }
```

（若 `MeshEnvelopeTest.kt` 无 `assertEquals` import 则补 `org.junit.Assert.assertEquals`。）

- [ ] **步骤 2：运行测试验证失败**

运行：`.\gradlew.bat testDebugUnitTest --tests "com.meshchat.app.mesh.protocol.MeshEnvelopeTest" --console=plain`
预期：编译失败（PresenceBody 不存在）。

- [ ] **步骤 3：实现 PresenceBody**

`MeshEnvelope.kt` 的 `FileAckBody` 之后追加：

```kotlin
@Serializable
@SerialName("PING")
data class PresenceBody(val displayName: String) : EnvelopeBody
```

- [ ] **步骤 4：MeshPeerInfo 加 displayName**

`MeshTransport.kt`：

```kotlin
data class MeshPeerInfo(
    val shortId: String,
    val deviceAddress: String,
    val rssi: Int,
    val hops: Int = 1,
    val lost: Boolean = false,
    val displayName: String = "",
)
```

- [ ] **步骤 5：本机昵称持久化（Application）**

`MeshChatApplication.kt` 的 `identity` lazy 改为：

```kotlin
    val identity by lazy {
        val prefs = getSharedPreferences("meshchat_identity", Context.MODE_PRIVATE)
        val stored = prefs.getString("short_id", null)
        val id = stored ?: ShortIdGen.generate().also {
            prefs.edit().putString("short_id", it).apply()
        }
        val name = prefs.getString("display_name", null)
        LocalIdentity(shortId = id, displayName = name ?: "节点$id")
    }

    /** 本机昵称：设置页可改，持久化，随 PING 广播给邻近节点。 */
    var displayName: String
        get() = identity.displayName
        set(value) {
            identity.displayName = value.trim().ifEmpty { "节点${identity.shortId}" }
            getSharedPreferences("meshchat_identity", Context.MODE_PRIVATE)
                .edit().putString("display_name", identity.displayName).apply()
        }
```

- [ ] **步骤 6：运行测试验证通过**

运行：`.\gradlew.bat testDebugUnitTest --tests "com.meshchat.app.mesh.protocol.MeshEnvelopeTest" --console=plain`
预期：PASS（原有用例 + PING 编解码）。

- [ ] **步骤 7：Commit**

```bash
git add app/src/main/java/com/meshchat/app/mesh/protocol/MeshEnvelope.kt app/src/main/java/com/meshchat/app/mesh/transport/MeshTransport.kt app/src/main/java/com/meshchat/app/MeshChatApplication.kt app/src/test/java/com/meshchat/app/mesh/protocol/MeshEnvelopeTest.kt
git commit -m "feat: PresenceBody PING/PONG 载荷 + MeshPeerInfo.displayName + 本机昵称持久化（v0.14.0）"
```

---

### 任务 2：MeshStore.upsertPeer（peers 表昵称落库）

**文件：**
- 修改：`app/src/main/java/com/meshchat/app/mesh/storage/MeshStore.kt`
- 修改：`app/src/main/java/com/meshchat/app/mesh/storage/MeshDatabase.kt`
- 修改：`app/src/main/java/com/meshchat/app/mesh/storage/InMemoryMeshStore.kt`

- [ ] **步骤 1：MeshStore 接口加 upsertPeer**

`MeshStore.kt` 接口内追加：

```kotlin
    fun upsertPeer(shortId: String, displayName: String, lastSeen: Long, hops: Int)
```

- [ ] **步骤 2：RoomMeshStore 实现**

`MeshDatabase.kt` 的 `RoomMeshStore` 类内追加（复用现有 `peerDao().upsert`，`PeerEntity` 字段已齐）：

```kotlin
    override fun upsertPeer(shortId: String, displayName: String, lastSeen: Long, hops: Int) = runBlocking {
        db.peerDao().upsert(PeerEntity(shortId = shortId, displayName = displayName, lastSeen = lastSeen, hops = hops))
    }
```

- [ ] **步骤 3：InMemoryMeshStore 实现**

`InMemoryMeshStore.kt` 类内追加：

```kotlin
    private val peers = mutableMapOf<String, PeerEntity>()

    override fun upsertPeer(shortId: String, displayName: String, lastSeen: Long, hops: Int) {
        peers[shortId] = PeerEntity(shortId, displayName, lastSeen, hops)
    }

    fun observePeers(): Flow<List<PeerEntity>> = flowOf(peers.values.toList())
```

- [ ] **步骤 4：编译验证**

运行：`.\gradlew.bat compileDebugKotlin --console=plain`
预期：BUILD SUCCESSFUL。

- [ ] **步骤 5：Commit**

```bash
git add app/src/main/java/com/meshchat/app/mesh/storage/MeshStore.kt app/src/main/java/com/meshchat/app/mesh/storage/MeshDatabase.kt app/src/main/java/com/meshchat/app/mesh/storage/InMemoryMeshStore.kt
git commit -m "feat: MeshStore.upsertPeer——节点昵称/lastSeen 落库 peers 表（v0.14.0）"
```

---

### 任务 3：SessionStore + MeshService 会话持久化

**文件：**
- 创建：`app/src/main/java/com/meshchat/app/mesh/service/SessionStore.kt`
- 修改：`app/src/main/java/com/meshchat/app/mesh/service/MeshService.kt`
- 测试：`app/src/test/java/com/meshchat/app/mesh/service/MeshServiceTest.kt`

- [ ] **步骤 1：编写失败的测试**

`MeshServiceTest.kt` 追加（类内新增内存 SessionStore 替身 + 测试）：

```kotlin
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
```

- [ ] **步骤 2：运行测试验证失败**

运行：`.\gradlew.bat testDebugUnitTest --tests "com.meshchat.app.mesh.service.MeshServiceTest.sessions are saved" --console=plain`
预期：编译失败（SessionStore 不存在 / MeshService 无该参数）。

- [ ] **步骤 3：实现 SessionStore**

创建 `SessionStore.kt`：

```kotlin
package com.meshchat.app.mesh.service

import android.content.Context

/** 会话关系（短 ID 集合）持久化：重启后恢复已建立的对话关系。 */
interface SessionStore {
    fun load(): Set<String>
    fun save(sessions: Set<String>)
}

/** SharedPreferences 实现：会话是内存态之外的一层镜像，重启恢复用。 */
class SharedPrefsSessionStore(context: Context) : SessionStore {
    private val prefs = context.getSharedPreferences("meshchat_sessions", Context.MODE_PRIVATE)

    override fun load(): Set<String> = prefs.getStringSet("sessions", emptySet()) ?: emptySet()

    override fun save(sessions: Set<String>) {
        prefs.edit().putStringSet("sessions", sessions).apply()
    }
}
```

- [ ] **步骤 4：MeshService 集成**

`MeshService.kt`：

构造末尾（`rfcomm` 之后）新增参数：

```kotlin
    /** 会话关系持久化（默认内存 Noop，不持久化；生产注入 SharedPrefsSessionStore）。 */
    private val sessionStore: SessionStore = object : SessionStore {
        override fun load(): Set<String> = emptySet()
        override fun save(sessions: Set<String>) {}
    },
    /** 收到新消息回调（fromId/fromName/text）：MeshChatService 注入用于弹通知。 */
    private val onIncomingMessage: (fromId: String, fromName: String, text: String) -> Unit = { _, _, _ -> },
    /** 文件接收完成回调（fileName）：通知「文件已保存」。 */
    private val onFileSaved: (fileName: String) -> Unit = {},
```

`start()` 内（`started = true` 之后）追加：

```kotlin
        _sessions.value = sessionStore.load()   // 重启恢复已建立的会话关系
```

`acceptInvite()` 内 `_sessions.update { it + peerId }` 之后追加：

```kotlin
        sessionStore.save(_sessions.value)
```

`handleEnvelope` 的 `"INVITE_ACK"` 分支 `_sessions.update { it + envelope.srcId }` 之后追加：

```kotlin
                sessionStore.save(_sessions.value)
```

- [ ] **步骤 5：运行测试验证通过**

运行：`.\gradlew.bat testDebugUnitTest --tests "com.meshchat.app.mesh.service.MeshServiceTest" --console=plain`
预期：PASS（新增 + 原有全过）。

- [ ] **步骤 6：Commit**

```bash
git add app/src/main/java/com/meshchat/app/mesh/service/SessionStore.kt app/src/main/java/com/meshchat/app/mesh/service/MeshService.kt app/src/test/java/com/meshchat/app/mesh/service/MeshServiceTest.kt
git commit -m "feat: 会话持久化——SessionStore 接口 + SharedPrefs 实现 + MeshService 保存/恢复（v0.14.0）"
```

---

### 任务 4：MeshService 心跳（PING/PONG + lost 判定 + onIncomingMessage）

**文件：**
- 修改：`app/src/main/java/com/meshchat/app/mesh/service/MeshService.kt`
- 测试：`app/src/test/java/com/meshchat/app/mesh/service/MeshServiceTest.kt`

- [ ] **步骤 1：编写失败的测试**

`MeshServiceTest.kt` 追加（类内需 `com.meshchat.app.mesh.protocol.PresenceBody` import）：

```kotlin
    private fun pingFrame(srcId: String, name: String) = MeshFrame(
        FrameType.DATA,
        MeshJson.encodeEnvelope(
            MeshEnvelope(
                id = UUID.randomUUID().toString(), kind = "PING",
                srcId = srcId, dstId = "", convId = "conv-$srcId",
                ttl = 8, ts = 0, body = PresenceBody(displayName = name),
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
        service.handleFrame(pingFrame("OTHER", "老王"))
        service.heartbeatTick(t0 + 3_200)
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
    fun `incoming text triggers onIncomingMessage with peer name`() {
        val transport = CountingTransport()
        val received = mutableListOf<Triple<String, String, String>>()
        val service = MeshService(
            transport = transport, store = InMemoryMeshStore(), identity = LocalIdentity(shortId = "ME"), dedup = DedupCache(),
            onIncomingMessage = { fromId, fromName, text -> received.add(Triple(fromId, fromName, text)) },
        )
        service.start()
        service.handleFrame(pingFrame("OTHER", "老王"))   // 先让昵称入表
        service.handleFrame(MeshFrame(
            FrameType.DATA,
            MeshJson.encodeEnvelope(MeshEnvelope(
                id = "t1", kind = "TEXT", srcId = "OTHER", dstId = "ME",
                convId = "conv-ME", ttl = 8, ts = 1, body = TextBody("你好"),
            )).toByteArray(),
        ))
        kotlinx.coroutines.delay(20)
        assertTrue(received.isNotEmpty())
        assertEquals("OTHER", received.first().first)
        assertEquals("老王", received.first().second)
        assertEquals("你好", received.first().third)
        service.stop()
    }
```

- [ ] **步骤 2：运行测试验证失败**

运行：`.\gradlew.bat testDebugUnitTest --tests "com.meshchat.app.mesh.service.MeshServiceTest" --console=plain`
预期：编译失败（PresenceBody 已实现，但 `heartbeatTick` 不存在、PING/PONG 分支未处理）。

- [ ] **步骤 3：实现心跳**

`MeshService.kt`：

常量区（`REFRESH_INTERVAL_MS` 附近）修改：

```kotlin
private const val REFRESH_INTERVAL_MS = 200L      // 探测刷新周期 0.2s
private const val HEARTBEAT_INTERVAL_MS = 1_000L  // PING 广播周期：1s 校准一次
private const val LOST_HEARTBEAT_MS = 3_000L      // 超过该时长无任何 PING/PONG/扫描帧 → 判失联（容忍 1-2 帧丢失）
private const val LOST_REMOVE_MS = 5_000L         // 失联超过该时长 → 从列表移除
```

**删除原 `LOST_THRESHOLD_MS = 1_500L` 常量**（在线判定改由心跳 3s 阈值驱动，不再用扫描推断）。

类内（`_ackRetries` 之后）新增心跳状态与常量：

```kotlin
    /** 上次 PING 广播时刻（tick 200ms 节流到 1s）。 */
    private var lastPingAt = 0L
```

`start()` 中 `tickJob` 的 `delay(REFRESH_INTERVAL_MS)` 之后（`tickSessionState` 调用前）追加：

```kotlin
                heartbeatTick(now)
```

`tickSessionState` 之前新增：

```kotlin
    /**
     * 心跳 tick（tick 循环每 200ms 调用）：
     * 每 1s 广播 PING；按 3s 超时更新各节点在线状态。
     */
    internal fun heartbeatTick(now: Long) {
        if (now - lastPingAt >= HEARTBEAT_INTERVAL_MS) {
            lastPingAt = now
            sendPing()
        }
        val iterator = peerEntries.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next().value
            entry.lost = now - entry.lastSeen > LOST_HEARTBEAT_MS
            if (now - entry.lastSeen > LOST_REMOVE_MS) iterator.remove()
        }
        _peers.value = peerEntries.values.map { it.info.copy(lost = it.lost) }
    }

    /** 广播 PING（带本机昵称），对端收到回 PONG。 */
    private fun sendPing() {
        val env = MeshEnvelope(
            id = UUID.randomUUID().toString(), kind = "PING",
            srcId = identity.shortId, dstId = "", convId = "conv-${identity.shortId}",
            ttl = DEFAULT_TTL, ts = System.currentTimeMillis(),
            body = PresenceBody(identity.displayName),
        )
        transport.broadcast(MeshFrame(FrameType.DATA, MeshJson.encodeEnvelope(env).toByteArray()))
    }

    /** 标记节点可见：更新 lastSeen；带昵称时更新显示名并落库。 */
    private fun markSeen(peerId: String, displayName: String) {
        val now = System.currentTimeMillis()
        val existing = peerEntries[peerId]
        if (existing != null) {
            existing.lastSeen = now
            if (displayName.isNotBlank() && displayName != existing.info.displayName) {
                existing.info = existing.info.copy(displayName = displayName)
            }
        } else {
            peerEntries[peerId] = PeerEntry(
                MeshPeerInfo(shortId = peerId, deviceAddress = "", rssi = 0, hops = 1, displayName = displayName),
                lastSeen = now, lost = false,
            )
        }
        if (displayName.isNotBlank()) store.upsertPeer(peerId, displayName, now, 1)
    }
```

注意：`PeerEntry` 当前是 `data class PeerEntry(val info: MeshPeerInfo, ...)`——`val info` 需改为 `var info` 才能 copy 更新：

```kotlin
    private data class PeerEntry(var info: MeshPeerInfo, var lastSeen: Long, var lost: Boolean)
```

`peerJob`（foundPeers collector）改为保留已有昵称（扫描帧 displayName 为空，不能覆盖 PING 学到的昵称）：

```kotlin
        peerJob = scope.launch {
            transport.foundPeers.catch { }.collect { info ->
                val now = System.currentTimeMillis()
                val existing = peerEntries[info.shortId]
                peerEntries[info.shortId] = PeerEntry(
                    if (existing != null) info.copy(displayName = existing.info.displayName) else info,
                    lastSeen = now, lost = false,
                )
            }
        }
```

现有 `tickJob` 里原有「按 age 更新 lost/移除」逻辑删除，改为调用 `heartbeatTick(now)`（step 3 已加），原逻辑移除：

```kotlin
        tickJob = scope.launch {
            while (isActive) {
                delay(REFRESH_INTERVAL_MS)
                val now = System.currentTimeMillis()
                heartbeatTick(now)
                // 会话状态机每 0.2s 检测一次：向已接受邀请的对端持续重发确认，直至其确认或超时
                tickSessionState(now)
                // 文件传输接收超时清理（60s 无进展丢弃）
                transfer.tick(now)
            }
        }
```

`handleEnvelope` 的 when 分支中 `"INVITE_ACK"` 之后（`"FILE"` 之前）新增：

```kotlin
            "PING" -> {
                // 心跳广播帧：仅处理发往本机/广播；回 PONG 双向确认在线
                if (envelope.dstId.isNotBlank() && envelope.dstId != identity.shortId) return
                markSeen(envelope.srcId, (envelope.body as? PresenceBody)?.displayName ?: "")
                val pong = MeshEnvelope(
                    id = UUID.randomUUID().toString(), kind = "PONG",
                    srcId = identity.shortId, dstId = envelope.srcId, convId = "conv-${envelope.srcId}",
                    ttl = DEFAULT_TTL, ts = System.currentTimeMillis(),
                    body = PresenceBody(identity.displayName),
                )
                transport.broadcast(MeshFrame(FrameType.DATA, MeshJson.encodeEnvelope(pong).toByteArray()))
            }
            "PONG" -> {
                if (envelope.dstId.isNotBlank() && envelope.dstId != identity.shortId) return
                markSeen(envelope.srcId, (envelope.body as? PresenceBody)?.displayName ?: "")
            }
```

`transfer` 构造的 `onSaved` 回调内追加文件完成通知回调（现有 onSaved 已回填 URI，追加一行）：

```kotlin
        onSaved = { _, fileId, fileName, mime, size, uri ->
            // 接收收齐：回填 Downloads URI 并标记送达
            store.updateFileMeta(fileId, fileMetaJson(fileName, mime, size, uri))
            store.updateMessageStatus(fileId, MessageStatus.DELIVERED)
            onFileSaved(fileName)   // 追加：通知「文件已保存」
        },
```

`route()` 的 `ForwardDecision.Deliver` 分支（`store.insertMessage` 之后）新增消息回调：

```kotlin
                // 收到消息回调（通知用）：仅对端发来的 TEXT 触发
                if (envelope.kind == "TEXT" && envelope.srcId != identity.shortId) {
                    val fromName = peerEntries[envelope.srcId]?.info?.displayName?.ifBlank { envelope.srcId } ?: envelope.srcId
                    onIncomingMessage(envelope.srcId, fromName, (envelope.body as? TextBody)?.text ?: "")
                }
```

`handleEnvelope` 的 `"INVITE_ACK"` 分支中 `sendInviteAck` 为 `PresenceBody` 无关（保持 TextBody），不改。

- [ ] **步骤 4：运行测试验证通过**

运行：`.\gradlew.bat testDebugUnitTest --tests "com.meshchat.app.mesh.service.MeshServiceTest" --console=plain`
预期：PASS（新增 5 个 + 原有全过）。

- [ ] **步骤 5：全量回归**

运行：`.\gradlew.bat testDebugUnitTest --console=plain`
预期：全部通过（原 39 + 本任务新增 ≥5 + 任务 3 新增 1）。

- [ ] **步骤 6：Commit**

```bash
git add app/src/main/java/com/meshchat/app/mesh/service/MeshService.kt app/src/test/java/com/meshchat/app/mesh/service/MeshServiceTest.kt
git commit -m "feat: 1s PING/PONG 心跳校准——双向在线确认、3s 超时判失联、昵称交换、消息通知回调（v0.14.0）"
```

---

### 任务 5：MeshChatService 前台服务 + 通知 + 权限

**文件：**
- 创建：`app/src/main/java/com/meshchat/app/mesh/service/NotificationHelper.kt`
- 创建：`app/src/main/java/com/meshchat/app/mesh/service/MeshChatService.kt`
- 修改：`app/src/main/java/com/meshchat/app/MeshChatApplication.kt`
- 修改：`app/src/main/AndroidManifest.xml`
- 修改：`app/src/main/java/com/meshchat/app/MainActivity.kt`

- [ ] **步骤 1：实现 NotificationHelper**

创建 `NotificationHelper.kt`：

```kotlin
package com.meshchat.app.mesh.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.meshchat.app.MainActivity

/** 通知中心：渠道幂等创建；常驻通知 + 消息通知 + 文件完成通知。 */
class NotificationHelper(private val context: Context) {
    companion object {
        private const val SERVICE_CHANNEL = "meshchat_service"
        private const val MESSAGE_CHANNEL = "meshchat_messages"
        const val SERVICE_NOTIF_ID = 1001
        const val EXTRA_CONV_ID = "extra_conv_id"
    }

    private val nm = context.getSystemService(NotificationManager::class.java)

    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        nm.createNotificationChannel(
            NotificationChannel(SERVICE_CHANNEL, "MeshChat 后台服务", NotificationManager.IMPORTANCE_MIN),
        )
        nm.createNotificationChannel(
            NotificationChannel(MESSAGE_CHANNEL, "MeshChat 消息", NotificationManager.IMPORTANCE_HIGH),
        )
    }

    /** 常驻通知：前台服务必需，显示节点在线数。 */
    fun persistent(peerCount: Int): Notification {
        ensureChannels()
        val intent = Intent(context, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(context, SERVICE_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle("MeshChat 运行中")
            .setContentText("邻近节点 $peerCount · 消息自动同步")
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    fun updatePersistent(peerCount: Int) {
        nm.notify(SERVICE_NOTIF_ID, persistent(peerCount))
    }

    /** 新消息通知：标题=发送者昵称，内容=正文，点击进对应会话。 */
    fun showMessage(fromName: String, text: String, convId: String) {
        ensureChannels()
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        val intent = Intent(context, MainActivity::class.java).putExtra(EXTRA_CONV_ID, convId)
        val pi = PendingIntent.getActivity(
            context, convId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        nm.notify(
            convId.hashCode(),
            Notification.Builder(context, MESSAGE_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(fromName)
                .setContentText(text)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build(),
        )
    }

    /** 文件接收完成通知。 */
    fun showFileSaved(fileName: String) {
        ensureChannels()
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        nm.notify(
            "file-$fileName".hashCode(),
            Notification.Builder(context, MESSAGE_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_save)
                .setContentTitle("文件已保存")
                .setContentText(fileName)
                .setAutoCancel(true)
                .build(),
        )
    }
}
```

- [ ] **步骤 2：实现 MeshChatService**

创建 `MeshChatService.kt`：

```kotlin
package com.meshchat.app.mesh.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.meshchat.app.MeshChatApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** 前台服务宿主：后台/息屏常驻，BLE 持续收发，收到消息弹通知。 */
class MeshChatService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var notifications: NotificationHelper
    private val app: MeshChatApplication get() = application as MeshChatApplication

    override fun onCreate() {
        super.onCreate()
        notifications = NotificationHelper(this)
        notifications.ensureChannels()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 设置页关闭后台常驻：不启动前台通知，立即停止
        if (!app.backgroundEnabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat()
        app.service.start()
        scope.launch {
            app.service.peers.collect { notifications.updatePersistent(it.size) }
        }
        return START_STICKY   // 系统回收后自动重启（状态从 SharedPreferences 恢复）
    }

    private fun startForegroundCompat() {
        val notification = notifications.persistent(0)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NotificationHelper.SERVICE_NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NotificationHelper.SERVICE_NOTIF_ID, notification)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        app.service.stop()
        super.onDestroy()
    }
}
```

- [ ] **步骤 3：Application 装配（前台服务启动 + onIncomingMessage 注入）**

`MeshChatApplication.kt`：

- `MeshChatService` 的 import 与 `service` lazy 改：

```kotlin
import com.meshchat.app.mesh.service.MeshChatService
import com.meshchat.app.mesh.service.NotificationHelper
import com.meshchat.app.mesh.service.SharedPrefsSessionStore
```

```kotlin
    /** 后台常驻开关（设置页可改）：关闭时 App 前台才运行 Mesh 服务。 */
    var backgroundEnabled: Boolean
        get() = getSharedPreferences("meshchat_settings", Context.MODE_PRIVATE)
            .getBoolean("background_enabled", true)
        set(value) {
            getSharedPreferences("meshchat_settings", Context.MODE_PRIVATE)
                .edit().putBoolean("background_enabled", value).apply()
        }
```

```kotlin
    val service by lazy {
        val notifications = NotificationHelper(this)
        MeshService(
            transport, store, identity, DedupCache(),
            fileSaver = AndroidFileSaver(this),
            tmpDir = { File(filesDir, "transfers") },
            sessionStore = SharedPrefsSessionStore(this),
            onIncomingMessage = { fromId, fromName, text ->
                // 会话键 = conv-<发送者短ID>（接收方统一命名，见 toStoredMessage）
                notifications.showMessage(fromName, text.take(80), "conv-$fromId")
            },
            onFileSaved = { fileName -> notifications.showFileSaved(fileName) },
        )
    }
```

- `startMesh()` 改（`backgroundEnabled` 属性定义在 service lazy 之前/之后均可）：

```kotlin
    /** 启动 Mesh 服务：后台常驻开启时走前台服务（息屏/后台继续收发），否则前台直跑。 */
    fun startMesh() {
        if (backgroundEnabled) {
            startForegroundService(Intent(this, MeshChatService::class.java))
        } else {
            service.start()
        }
    }
```

- 通知点击打开会话的请求通道（类内追加）：

```kotlin
    /** 通知点击携带的会话请求（convId），MainActivity 写入、ViewModel 订阅打开会话。 */
    private val _conversationRequest = MutableStateFlow<String?>(null)
    val conversationRequest: StateFlow<String?> = _conversationRequest.asStateFlow()

    fun requestConversation(convId: String) {
        _conversationRequest.value = convId
    }
```

（补 import：`kotlinx.coroutines.flow.MutableStateFlow`、`kotlinx.coroutines.flow.StateFlow`、`kotlinx.coroutines.flow.asStateFlow`。）

- [ ] **步骤 4：Manifest 注册与权限**

`AndroidManifest.xml` 追加权限（`ACCESS_FINE_LOCATION` 之后）：

```xml
    <!-- 前台服务：后台/息屏常驻收消息（API 34+ 强制类型 connectedDevice） -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
    <!-- 通知（API 33+ 运行时请求） -->
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

`<application>` 内 `</activity>` 之后注册服务：

```xml
        <service
            android:name=".MeshChatService"
            android:exported="false"
            android:foregroundServiceType="connectedDevice" />
```

- [ ] **步骤 5：MainActivity 请求通知权限 + 处理通知点击**

`MainActivity.kt` 的 `requiredPermissions` buildList（API 31+ 分支）追加：

```kotlin
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
```

`onCreate` 内（`setContent` 之前）处理通知点击（conversationRequest 写入，由 ViewModel 订阅打开会话）：

```kotlin
        val convId = intent.getStringExtra(com.meshchat.app.mesh.service.NotificationHelper.EXTRA_CONV_ID)
        if (convId != null) {
            (application as MeshChatApplication).requestConversation(convId)
        }
```

- [ ] **步骤 6：编译验证**

运行：`.\gradlew.bat compileDebugKotlin --console=plain`
预期：BUILD SUCCESSFUL。

- [ ] **步骤 7：Commit**

```bash
git add app/src/main/java/com/meshchat/app/mesh/service/NotificationHelper.kt app/src/main/java/com/meshchat/app/mesh/service/MeshChatService.kt app/src/main/java/com/meshchat/app/MeshChatApplication.kt app/src/main/AndroidManifest.xml app/src/main/java/com/meshchat/app/MainActivity.kt
git commit -m "feat: MeshChatService 前台服务——后台/息屏常驻、消息通知弹窗、通知权限（v0.14.0）"
```

---

### 任务 6：UI（昵称设置 + 后台开关 + 节点昵称显示 + 会话标题）

**文件：**
- 修改：`app/src/main/java/com/meshchat/app/data/UiModels.kt`
- 修改：`app/src/main/java/com/meshchat/app/data/MeshRepository.kt`
- 修改：`app/src/main/java/com/meshchat/app/ui/screens/MeshScreen.kt`
- 修改：`app/src/main/java/com/meshchat/app/ui/screens/MeshChatHome.kt`
- 修改：`app/src/main/java/com/meshchat/app/ui/screens/ProfileDetailScreens.kt`
- 修改：`app/src/main/java/com/meshchat/app/ui/MeshChatViewModel.kt`
- 修改：`app/src/main/java/com/meshchat/app/ui/MeshChatViewModelFactory.kt`
- 修改：`app/src/main/java/com/meshchat/app/ui/MeshChatApp.kt`

- [ ] **步骤 1：MeshPeer 加 shortId，peers 映射昵称**

`UiModels.kt`：

```kotlin
data class MeshPeer(
    val name: String,       // 显示名（昵称，缺省回退短 ID）
    val shortId: String,    // 寻址标识（点击/匹配/会话键）
    val hops: Int,
    val strength: Int,
    val rssi: Int = 0,
    val lost: Boolean = false,
    val reachable: Boolean = true,
)
```

`MeshRepository.kt` 的 `toUiModel`：

```kotlin
    private fun MeshPeerInfo.toUiModel(): MeshPeer {
        val strength = BluetoothQuality.bars(rssi)
        return MeshPeer(
            name = displayName.ifBlank { shortId }, shortId = shortId, hops = hops, strength = strength,
            rssi = rssi, lost = lost, reachable = !lost,
        )
    }
```

- [ ] **步骤 2：MeshScreen 用 shortId 匹配/点击**

`MeshScreen.kt`：

```kotlin
        items(peers, key = { it.shortId }) { peer ->
            PeerRow(
                peer = peer,
                connected = peer.shortId in sessions,
                pending = peer.shortId in pendingInvites,
                onClick = { onPeerSelected(peer.shortId) },
            )
        }
```

`PeerRow` 内主标题行改为昵称 + 短 ID：

```kotlin
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(peer.name, style = MaterialTheme.typography.titleMedium)
            Text(
                "ID ${peer.shortId}",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = TextSecondary,
            )
        }
```

（原 `Text(peer.name, ..., modifier = Modifier.weight(1f))` 一行替换为上 Column 块。）

- [ ] **步骤 3：会话标题用昵称**

`MeshChatHome.kt` 的会话标题：

```kotlin
    if (conversationTarget != null) {
        val target = conversationTarget!!
        val title = when {
            target == "ME" -> "我"
            else -> peers.firstOrNull { it.shortId == target }?.name ?: target
        }
```

- [ ] **步骤 4：设置页（昵称输入 + 后台常驻开关）**

`ProfileDetailScreens.kt` 的 `GeneralSettingsScreen` 替换：

```kotlin
@Composable
fun GeneralSettingsScreen(
    displayName: String,
    onDisplayNameChange: (String) -> Unit,
    backgroundEnabled: Boolean,
    onBackgroundEnabledChange: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().background(Ink)) {
        DetailHeader(title = "通用设置", icon = Icons.Outlined.Settings, onBack = onBack)
        Text("节点昵称", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp))
        OutlinedTextField(
            value = displayName,
            onValueChange = onDisplayNameChange,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            singleLine = true,
            label = { Text("昵称（广播给邻近节点）") },
        )
        Text(
            "昵称随心跳广播，邻近节点将以此标识你。",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        HorizontalDivider(color = Divider, modifier = Modifier.padding(top = 14.dp))
        SettingsSwitchRow(
            title = "后台常驻",
            checked = backgroundEnabled,
            onCheckedChange = onBackgroundEnabledChange,
        )
        Text(
            "开启后息屏/退后台仍持续收发消息并弹通知。",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
    }
}
```

补 import：`androidx.compose.material3.OutlinedTextField`。

- [ ] **步骤 5：ViewModel / Factory / App 传参**

`MeshChatViewModel.kt` 构造与新增成员：

```kotlin
class MeshChatViewModel(
    private val repository: MeshRepository,
    val localBluetoothName: String,
    val localBluetoothAddress: String,
    private val displayNameProvider: () -> String,
    private val setDisplayName: (String) -> Unit,
    private val backgroundEnabledProvider: () -> Boolean,
    private val setBackgroundEnabled: (Boolean) -> Unit,
    private val conversationRequest: kotlinx.coroutines.flow.StateFlow<String?>,
) : ViewModel() {

    val localDisplayName: String get() = displayNameProvider()

    fun updateDisplayName(value: String) = setDisplayName(value)

    val backgroundEnabled: Boolean get() = backgroundEnabledProvider()

    fun updateBackgroundEnabled(value: Boolean) = setBackgroundEnabled(value)

    init {
        // 通知点击 → 打开对应会话（convId = conv-<shortId>，target 取短 ID）
        viewModelScope.launch {
            conversationRequest.collect { convId ->
                convId?.let { openConversation(it.substringAfterLast("-")) }
            }
        }
    }
```

`MeshChatViewModelFactory.kt`：

```kotlin
        return MeshChatViewModel(
            repository = MeshRepositoryImpl(app.service, app.store),
            localBluetoothName = app.localBluetoothName ?: "未知",
            localBluetoothAddress = app.localBluetoothAddress ?: "未知",
            displayNameProvider = { app.displayName },
            setDisplayName = { app.displayName = it },
            backgroundEnabledProvider = { app.backgroundEnabled },
            setBackgroundEnabled = { app.backgroundEnabled = it },
            conversationRequest = app.conversationRequest,
        ) as T
```

`MeshChatApp.kt` 的 settings 分支：

```kotlin
            "settings" -> GeneralSettingsScreen(
                displayName = viewModel.localDisplayName,
                onDisplayNameChange = viewModel::updateDisplayName,
                backgroundEnabled = viewModel.backgroundEnabled,
                onBackgroundEnabledChange = viewModel::updateBackgroundEnabled,
                onBack = { profileDetail = null },
            )
```

- [ ] **步骤 6：编译验证**

运行：`.\gradlew.bat compileDebugKotlin --console=plain`
预期：BUILD SUCCESSFUL。

- [ ] **步骤 7：Commit**

```bash
git add app/src/main/java/com/meshchat/app/data/UiModels.kt app/src/main/java/com/meshchat/app/data/MeshRepository.kt app/src/main/java/com/meshchat/app/ui/screens/MeshScreen.kt app/src/main/java/com/meshchat/app/ui/screens/MeshChatHome.kt app/src/main/java/com/meshchat/app/ui/screens/ProfileDetailScreens.kt app/src/main/java/com/meshchat/app/ui/MeshChatViewModel.kt app/src/main/java/com/meshchat/app/ui/MeshChatViewModelFactory.kt app/src/main/java/com/meshchat/app/ui/MeshChatApp.kt
git commit -m "feat: UI——节点昵称显示、昵称/后台常驻设置、会话标题用昵称（v0.14.0）"
```

---

### 任务 7：版本 bump + 全量构建 + 交接

**文件：**
- 修改：`app/build.gradle.kts`
- 修改：`AI_CONTEXT.md`

- [ ] **步骤 1：版本 bump**

`app/build.gradle.kts`：`versionCode = 26`、`versionName = "0.14.0"`。

- [ ] **步骤 2：全量测试 + 构建**

运行：`.\gradlew.bat testDebugUnitTest assembleDebug --console=plain`
预期：BUILD SUCCESSFUL，全部测试通过（原 39 + 任务 1 PING 编解码 + 任务 3 会话持久化 + 任务 4 心跳 5 个）。
复制：`Copy-Item .\app\build\outputs\apk\debug\app-debug.apk .\MeshChat-v0.14.0-debug.apk -Force`

- [ ] **步骤 3：更新 AI_CONTEXT.md 交接块**

记录：v0.14.0 后台常驻（前台服务/通知渠道/权限）、1s 心跳校准（PING/PONG/3s lost）、昵称（设置页/随心跳交换/peers 表落库）、会话持久化（SessionStore/重启恢复/点击直达）、UI 三态；真机验收要点（息屏收消息弹窗、双机状态对称、重启恢复、通知权限请求）。

- [ ] **步骤 4：Commit**

```bash
git add app/build.gradle.kts AI_CONTEXT.md
git commit -m "build: v0.14.0 后台常驻+状态校准+节点命名 装配/版本 bump/交接块"
```

---

## 自检

**规格覆盖度对照：**
- §3.1 后台常驻（前台服务/常驻通知/消息通知/文件完成通知/权限）→ 任务 5（onFileSaved 回调在任务 4 注入）✓
- §3.2 心跳校准（PING/PONG/1s/3s lost/start 抢校准）→ 任务 4 ✓
- §3.3 节点命名（昵称持久化/交换/落库/UI）→ 任务 1/2/4/6 ✓
- §3.4 会话持久化（SessionStore/重启恢复/点击直达）→ 任务 3 + 任务 6（UI 已支持直达，sessions 恢复后即生效）✓
- §3.5 UI 三态 → 任务 6 ✓
- §3.6 设置页（昵称/后台开关）→ 任务 6 ✓
- §4 集成表 → 任务 1-7 全覆盖 ✓
- §5 测试策略 → 任务 1/3/4 ✓
- §6 权限 → 任务 5 ✓
- §7 边界（START_STICKY 重启恢复、通知降级、心跳丢帧容忍、后台扫描豁免）→ 任务 4/5 ✓

**占位符扫描：** 无"待定/TODO"。所有代码步骤含完整可粘贴代码。

**类型一致性：**
- `PresenceBody(displayName: String)` 在 MeshEnvelope/测试/心跳中一致 ✓
- `MeshPeerInfo.displayName: String = ""` 默认值保证 BleTransport/现有测试构造不破坏 ✓
- `SessionStore.load()/save(Set<String>)` 接口与 SharedPrefs/内存实现/测试一致 ✓
- `MeshService` 新参 `sessionStore`/`onIncomingMessage` 带默认值，现有位置参数调用不破坏 ✓
- `MeshPeer(name, shortId, ...)` 在 UiModels/MeshRepository/MeshScreen/MeshChatHome 一致 ✓
- `heartbeatTick(now: Long)` internal，测试直接调用 ✓
- `MeshChatService`/`NotificationHelper`/`SharedPrefsSessionStore` 在 Application/Manifest/MainActivity 引用一致 ✓
- `app.displayName`/`app.backgroundEnabled` 属性在 Factory/ViewModel 一致 ✓
