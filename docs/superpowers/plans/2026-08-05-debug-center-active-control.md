# 调试中心·主动控制 实现计划（v1.1.9）

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 给调试中心增加主动控制面板——可调心跳广播频率（联动失联阈值）、消息重发退避、暂停/恢复广播+扫描、手动发 PING、一键恢复默认。

**架构：** `DebugControl` sealed class 命令集经 DebugStats 新增控制总线（`attachControls`/`issue`）转发到 MeshService 控制面（volatile 参数 + 公开方法），BleTransport 提供 `suspendDiscovery()/resumeDiscovery()`（只停广播+扫描）。UI 新增「主动控制」板块。全部内存态、默认值不变、协议/路由/存储零改动。

**技术栈：** Kotlin + Coroutines + Compose Material3（FilterChip/TextButton）+ kotlinx-coroutines-test（runTest 虚拟时间）

**规格：** `docs/superpowers/specs/2026-08-05-debug-center-active-control-design.md`

---

## 文件结构

**新增：**
- `app/src/main/java/com/meshchat/app/mesh/debug/DebugControl.kt` — 主动控制命令集（sealed class）

**修改：**
- `app/src/main/java/com/meshchat/app/mesh/debug/DebugStats.kt` — 控制总线：`attachControls(handler)` + `issue(cmd)`（纯转发，内核不持有服务引用）
- `app/src/main/java/com/meshchat/app/mesh/service/MeshService.kt` — 控制面：4 个 `@Volatile` 参数（默认值=现常量）+ `setHeartbeat/setResendPolicy/suspendSignaling/resumeSignaling/broadcastPing/resetDebugControls` + 4 处常量使用处替换 + `start()` 注册 handler
- `app/src/main/java/com/meshchat/app/mesh/transport/MeshTransport.kt` — 接口加默认方法 `suspendDiscovery()/resumeDiscovery()`
- `app/src/main/java/com/meshchat/app/mesh/transport/BleTransport.kt` — 覆写：只停/启 advertise+scan，保留 GATT
- `app/src/main/java/com/meshchat/app/mesh/transport/InMemoryTransport.kt` — 覆写 + `discoverySuspended` 断言位（测试用）
- `app/src/main/java/com/meshchat/app/ui/MeshChatViewModel.kt` — `DebugControlState` + `sendDebugControl(cmd)` + `resetDebugControls()`（声明在 init 之前，遵守 v1.1.8 教训）
- `app/src/main/java/com/meshchat/app/ui/MeshChatViewModel.kt` — `DebugSettings` 加 `showControl`（板块显隐）
- `app/src/main/java/com/meshchat/app/ui/screens/DebugCenterScreen.kt` — 新增 `ControlCard` 面板 + 3 个新参数
- `app/src/main/java/com/meshchat/app/ui/screens/MeshChatHome.kt` — 透传 `debugControlState/onDebugControl/onResetDebugControls` 到 "debug" 分支
- `app/src/main/java/com/meshchat/app/ui/MeshChatApp.kt` — collect `debugControlState` + 透传
- `app/build.gradle.kts` — v1.1.9 / versionCode 71
- `README.md` — 调试中心条目补主动控制
- `AI_CONTEXT.md` — 版本行/进度/验证/阻塞/下一步/关键文件

**测试：**
- `app/src/test/java/com/meshchat/app/mesh/debug/DebugStatsTest.kt` — +1（issue 转发）
- `app/src/test/java/com/meshchat/app/mesh/service/MeshServiceTest.kt` — +3（心跳间隔/重发退避/暂停恢复）

---

### 任务 1：DebugControl 命令集 + DebugStats 控制总线（TDD）

**文件：**
- 创建：`app/src/main/java/com/meshchat/app/mesh/debug/DebugControl.kt`
- 修改：`app/src/main/java/com/meshchat/app/mesh/debug/DebugStats.kt`
- 测试：`app/src/test/java/com/meshchat/app/mesh/debug/DebugStatsTest.kt`

- [ ] **步骤 1：编写失败的测试**（DebugStatsTest.kt 末尾追加）

```kotlin
    @Test
    fun `issue forwards control commands to attached handler`() {
        val stats = DebugStats()
        var received: DebugControl? = null
        stats.attachControls { received = it }
        stats.issue(DebugControl.SetHeartbeat(500, 1_000))
        assertEquals(DebugControl.SetHeartbeat(500, 1_000), received)
        // 未注册 handler 时静默不抛（测试/未装配场景）
        DebugStats().issue(DebugControl.ResetControls)
    }
```

- [ ] **步骤 2：运行测试验证失败**

运行：`.\gradlew.bat testDebugUnitTest --tests "com.meshchat.app.mesh.debug.DebugStatsTest" --console=plain`
预期：编译失败，`Unresolved reference: DebugControl`（attachControls/issue 未定义）

- [ ] **步骤 3：创建 DebugControl.kt**

```kotlin
package com.meshchat.app.mesh.debug

/** 调试中心主动控制命令（经 DebugStats 控制总线转发到 MeshService 控制面；内存态，重启回默认）。 */
sealed class DebugControl {
    /** 心跳：PING 广播节流间隔 + 本机失联判定阈值（UI 联动下发 interval*2）。 */
    data class SetHeartbeat(val intervalMs: Long, val lostMs: Long) : DebugControl()
    /** 重发退避：消息未确认重发的基础间隔与封顶。 */
    data class SetResendPolicy(val baseMs: Long, val maxMs: Long) : DebugControl()
    /** 暂停广播+扫描（发现层；已建立 GATT 连接不受影响）。 */
    data object SuspendSignaling : DebugControl()
    /** 恢复广播+扫描。 */
    data object ResumeSignaling : DebugControl()
    /** 立即广播一轮 PING（链路探测）。 */
    data object BroadcastPing : DebugControl()
    /** 恢复全部默认。 */
    data object ResetControls : DebugControl()
}
```

- [ ] **步骤 4：DebugStats.kt 加控制总线**（在 `attachProviders` 方法之后追加）

```kotlin
    // ---- 控制总线（UI → MeshService 控制面；内核仅转发，不持有服务引用）----
    private var controlHandler: ((DebugControl) -> Unit)? = null

    fun attachControls(handler: (DebugControl) -> Unit) {
        controlHandler = handler
    }

    fun issue(cmd: DebugControl) {
        controlHandler?.invoke(cmd)
    }
```

- [ ] **步骤 5：运行测试验证通过**

运行：`.\gradlew.bat testDebugUnitTest --tests "com.meshchat.app.mesh.debug.DebugStatsTest" --console=plain`
预期：PASS，6 项全绿（原 5 + 新 1）

- [ ] **步骤 6：Commit**

```bash
git add app/src/main/java/com/meshchat/app/mesh/debug/DebugControl.kt app/src/main/java/com/meshchat/app/mesh/debug/DebugStats.kt app/src/test/java/com/meshchat/app/mesh/debug/DebugStatsTest.kt
git commit -m "feat: DebugControl 主动控制命令集 + DebugStats 控制总线（attachControls/issue 转发）"
```

---

### 任务 2：MeshTransport 接口 + InMemoryTransport 覆写（发现层暂停）

**文件：**
- 修改：`app/src/main/java/com/meshchat/app/mesh/transport/MeshTransport.kt`
- 修改：`app/src/main/java/com/meshchat/app/mesh/transport/InMemoryTransport.kt`

- [ ] **步骤 1：MeshTransport.kt 加默认方法**（在 `bluetoothEnabled()` 默认方法附近追加）

```kotlin
    /** 暂停发现层（广播+扫描）；默认无操作，BleTransport 覆写。 */
    fun suspendDiscovery() = Unit

    /** 恢复发现层（广播+扫描）；默认无操作，BleTransport 覆写。 */
    fun resumeDiscovery() = Unit
```

- [ ] **步骤 2：InMemoryTransport.kt 覆写 + 断言位**

```kotlin
    /** 发现层暂停标志（suspendSignaling/resumeSignaling 测试断言用）。 */
    @Volatile
    var discoverySuspended = false

    override fun suspendDiscovery() {
        discoverySuspended = true
    }

    override fun resumeDiscovery() {
        discoverySuspended = false
    }
```

（放在 `emitPeer` 之后；`import kotlin.jvm.Volatile` 不需要——Kotlin 默认可用）

- [ ] **步骤 3：编译验证**

运行：`.\gradlew.bat compileDebugKotlin --console=plain`
预期：BUILD SUCCESSFUL

- [ ] **步骤 4：Commit**

```bash
git add app/src/main/java/com/meshchat/app/mesh/transport/MeshTransport.kt app/src/main/java/com/meshchat/app/mesh/transport/InMemoryTransport.kt
git commit -m "feat: MeshTransport 接口加 suspendDiscovery/resumeDiscovery 默认方法，InMemoryTransport 覆写 + 断言位"
```

---

### 任务 3：MeshService 控制面 + 3 个测试（TDD）

**文件：**
- 修改：`app/src/main/java/com/meshchat/app/mesh/service/MeshService.kt`
- 测试：`app/src/test/java/com/meshchat/app/mesh/service/MeshServiceTest.kt`

- [ ] **步骤 1：编写失败的测试**（MeshServiceTest.kt 末尾追加 3 个测试；在 `import com.meshchat.app.mesh.debug.DebugStats` 之后无需新增 import——测试只用 MeshService 公开方法）

```kotlin
    @Test
    fun `heartbeat interval can be adjusted via debug control`() = runTest {
        val identity = LocalIdentity(shortId = "ME")
        val transport = CountingTransport()
        val service = MeshService(
            transport = transport, store = InMemoryMeshStore(), identity = identity, dedup = DedupCache(),
        )
        service.start()

        service.setHeartbeat(2_000, 4_000)
        val t0 = System.currentTimeMillis() + 10_000   // 虚拟推进 10s：确保距 start 时 lastPingAt 超过任何心跳间隔
        service.heartbeatTick(t0)                       // 必发一轮 PING，lastPingAt = t0
        transport.frames.clear()
        service.heartbeatTick(t0 + 500)                 // 0.5s < 2s → 不发
        assertTrue("0.5s 后不应发 PING", transport.frames.isEmpty())
        service.heartbeatTick(t0 + 2_500)               // 2.5s ≥ 2s → 发
        assertTrue("2.5s 后应发 PING", transport.frames.isNotEmpty())
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

        service.suspendSignaling()
        assertTrue(transport.discoverySuspended)
        service.resumeSignaling()
        assertTrue(!transport.discoverySuspended)
    }
```

- [ ] **步骤 2：运行测试验证失败**

运行：`.\gradlew.bat testDebugUnitTest --tests "com.meshchat.app.mesh.service.MeshServiceTest" --console=plain`
预期：编译失败，`Unresolved reference: setHeartbeat / setResendPolicy / suspendSignaling / resumeSignaling`

- [ ] **步骤 3：MeshService.kt 加 import**

```kotlin
import com.meshchat.app.mesh.debug.DebugControl
```
（加在 `import com.meshchat.app.mesh.debug.DebugStats` 之后）

- [ ] **步骤 4：MeshService.kt 加 volatile 参数**（在 `private var lastPingAt = 0L`（第 169 行）附近追加）

```kotlin
    // ---- 调试主动控制（volatile 可调；默认值=常量，未调节时行为零变化；内存态重启回默认）----
    @Volatile private var heartbeatIntervalMs: Long = HEARTBEAT_INTERVAL_MS
    @Volatile private var lostHeartbeatMs: Long = LOST_HEARTBEAT_MS
    @Volatile private var resendBaseMs: Long = RECEIPT_TIMEOUT_MS
    @Volatile private var resendMaxMs: Long = MAX_RESEND_INTERVAL_MS
```

- [ ] **步骤 5：MeshService.kt 加控制方法**（放在 `sendPing()` 定义之后，或任何类内合适位置）

```kotlin
    // ===== 调试主动控制（UI 调节经 DebugStats 控制总线下发；全部幂等可逆）=====
    /** 心跳间隔 + 失联阈值（联动由 UI 保证 lostMs = intervalMs * 2）。 */
    fun setHeartbeat(intervalMs: Long, lostMs: Long) {
        heartbeatIntervalMs = intervalMs.coerceIn(200L, 10_000L)
        lostHeartbeatMs = lostMs.coerceIn(500L, 20_000L)
    }

    /** 消息重发退避（基础间隔 + 封顶）。 */
    fun setResendPolicy(baseMs: Long, maxMs: Long) {
        resendBaseMs = baseMs.coerceIn(500L, 60_000L)
        resendMaxMs = maxMs.coerceIn(baseMs, 300_000L)
    }

    /** 暂停发现层（广播+扫描；已建立 GATT 连接收发不受影响）。 */
    fun suspendSignaling() = transport.suspendDiscovery()

    /** 恢复发现层。 */
    fun resumeSignaling() = transport.resumeDiscovery()

    /** 立即广播一轮 PING（链路探测）。 */
    fun broadcastPing() = sendPing()

    /** 恢复全部默认并确保未处于暂停态。 */
    fun resetDebugControls() {
        heartbeatIntervalMs = HEARTBEAT_INTERVAL_MS
        lostHeartbeatMs = LOST_HEARTBEAT_MS
        resendBaseMs = RECEIPT_TIMEOUT_MS
        resendMaxMs = MAX_RESEND_INTERVAL_MS
        resumeSignaling()
    }
```

- [ ] **步骤 6：替换 4 处常量使用处**

① 第 509 行 `if (now - lastPingAt >= HEARTBEAT_INTERVAL_MS) {` → `if (now - lastPingAt >= heartbeatIntervalMs) {`
② 第 530 行 `age < LOST_HEARTBEAT_MS -> PeerPresence.ONLINE` → `age < lostHeartbeatMs -> PeerPresence.ONLINE`
③ 第 534 行 `entry.lost = age > LOST_HEARTBEAT_MS` → `entry.lost = age > lostHeartbeatMs`
④ 第 561 行 `minOf(RECEIPT_TIMEOUT_MS * (1L shl minOf(p.retries, 4)), MAX_RESEND_INTERVAL_MS)` → `minOf(resendBaseMs * (1L shl minOf(p.retries, 4)), resendMaxMs)`

- [ ] **步骤 7：start() 末尾注册控制 handler**（在 `debugStats.attachProviders(...)` 之后、`fun start()` 结束前追加）

```kotlin
        // 调试主动控制：UI 调节经 DebugStats 控制总线转发到本服务控制面
        debugStats.attachControls { cmd ->
            when (cmd) {
                is DebugControl.SetHeartbeat -> setHeartbeat(cmd.intervalMs, cmd.lostMs)
                is DebugControl.SetResendPolicy -> setResendPolicy(cmd.baseMs, cmd.maxMs)
                DebugControl.SuspendSignaling -> suspendSignaling()
                DebugControl.ResumeSignaling -> resumeSignaling()
                DebugControl.BroadcastPing -> broadcastPing()
                DebugControl.ResetControls -> resetDebugControls()
            }
        }
```

- [ ] **步骤 8：运行测试验证通过**

运行：`.\gradlew.bat testDebugUnitTest --tests "com.meshchat.app.mesh.service.MeshServiceTest" --console=plain`
预期：PASS，45 项全绿（原 42 + 新 3）

- [ ] **步骤 9：Commit**

```bash
git add app/src/main/java/com/meshchat/app/mesh/service/MeshService.kt app/src/test/java/com/meshchat/app/mesh/service/MeshServiceTest.kt
git commit -m "feat: MeshService 调试主动控制面——volatile 心跳/失联/重发参数 + 6 个控制方法 + handler 注册；单测 +3"
```

---

### 任务 4：BleTransport 覆写 suspendDiscovery/resumeDiscovery

**文件：**
- 修改：`app/src/main/java/com/meshchat/app/mesh/transport/BleTransport.kt`

- [ ] **步骤 1：覆写两个方法**（放在 `bluetoothEnabled()`（第 191-192 行）之后）

```kotlin
    /** 调试控制：暂停发现层——只停广播+扫描，保留 GATT server/clients 与已建立连接收发。 */
    override fun suspendDiscovery() {
        Log.d(TAG, "suspendDiscovery: stop advertising + scanning")
        runCatching { bluetoothAdapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) }
        runCatching { bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback) }
    }

    /** 调试控制：恢复发现层——重新广播+扫描（与 start() 幂等互不干扰）。 */
    override fun resumeDiscovery() {
        Log.d(TAG, "resumeDiscovery: restart advertising + scanning")
        runCatching { startAdvertising() }
        runCatching { startScanning() }
    }
```

- [ ] **步骤 2：编译验证**

运行：`.\gradlew.bat compileDebugKotlin --console=plain`
预期：BUILD SUCCESSFUL

- [ ] **步骤 3：Commit**

```bash
git add app/src/main/java/com/meshchat/app/mesh/transport/BleTransport.kt
git commit -m "feat: BleTransport 覆写 suspendDiscovery/resumeDiscovery——只停/启广播+扫描，保留 GATT 连接"
```

---

### 任务 5：ViewModel 主动控制状态

**文件：**
- 修改：`app/src/main/java/com/meshchat/app/ui/MeshChatViewModel.kt`

- [ ] **步骤 1：加 import**

```kotlin
import com.meshchat.app.mesh.debug.DebugControl
```
（加在 `import com.meshchat.app.mesh.debug.DebugSnapshot` 相关 import 附近——若无此 import 则加在调试中心相关 import 区）

- [ ] **步骤 2：DebugSettings 加 showControl 板块开关**

```kotlin
        val showFile: Boolean = true,
        val showControl: Boolean = true,   // 主动控制板块显隐
```

- [ ] **步骤 3：在调试中心属性区（`debugSnapshot` 之后、`updateDebugSettings` 之前）追加主动控制状态与方法**

```kotlin
    // ---- 调试中心·主动控制（内存态，重启回默认）----
    /** 当前生效控制档位/暂停标记/上次手动 PING 时刻。 */
    data class DebugControlState(
        val heartbeatMs: Long = 1_000L,
        val lostMs: Long = 2_000L,
        val resendBaseMs: Long = 3_000L,
        val resendMaxMs: Long = 30_000L,
        val signalingSuspended: Boolean = false,
        val lastPingAtMs: Long = -1L,   // -1 = 尚未手动发过
    )

    private val _debugControlState = MutableStateFlow(DebugControlState())
    val debugControlState: StateFlow<DebugControlState> = _debugControlState.asStateFlow()

    fun sendDebugControl(cmd: DebugControl) {
        debugStats.issue(cmd)
        _debugControlState.value = when (cmd) {
            is DebugControl.SetHeartbeat -> _debugControlState.value.copy(heartbeatMs = cmd.intervalMs, lostMs = cmd.lostMs)
            is DebugControl.SetResendPolicy -> _debugControlState.value.copy(resendBaseMs = cmd.baseMs, resendMaxMs = cmd.maxMs)
            DebugControl.SuspendSignaling -> _debugControlState.value.copy(signalingSuspended = true)
            DebugControl.ResumeSignaling -> _debugControlState.value.copy(signalingSuspended = false)
            DebugControl.BroadcastPing -> _debugControlState.value.copy(lastPingAtMs = System.currentTimeMillis())
            DebugControl.ResetControls -> DebugControlState()
        }
    }

    fun resetDebugControls() {
        debugStats.issue(DebugControl.ResetControls)
        _debugControlState.value = DebugControlState()
    }
```

（注意：此区块位于 init 块之前——调试中心属性区在 v1.1.8 已整体移到 init 前，保持该顺序）

- [ ] **步骤 4：编译验证**

运行：`.\gradlew.bat compileDebugKotlin --console=plain`
预期：BUILD SUCCESSFUL

- [ ] **步骤 5：Commit**

```bash
git add app/src/main/java/com/meshchat/app/ui/MeshChatViewModel.kt
git commit -m "feat: ViewModel 调试主动控制状态 DebugControlState + sendDebugControl/resetDebugControls"
```

---

### 任务 6：UI ControlCard 面板 + 传参链

**文件：**
- 修改：`app/src/main/java/com/meshchat/app/ui/screens/DebugCenterScreen.kt`
- 修改：`app/src/main/java/com/meshchat/app/ui/screens/MeshChatHome.kt`
- 修改：`app/src/main/java/com/meshchat/app/ui/MeshChatApp.kt`

- [ ] **步骤 1：DebugCenterScreen 签名加 3 参数 + 渲染 ControlCard**

`DebugCenterScreen` 签名（`onReset` 之后）加：

```kotlin
    controlState: MeshChatViewModel.DebugControlState,
    onControl: (com.meshchat.app.mesh.debug.DebugControl) -> Unit,
    onResetControls: () -> Unit,
```

板块渲染区（`if (settings.showFile) FileCard(snapshot)` 之后）加：

```kotlin
            if (settings.showControl) ControlCard(controlState, onControl, onResetControls)
```

- [ ] **步骤 2：DebugCenterScreen 加 ControlCard 可组合函数**（文件末尾追加）

```kotlin
@Composable
private fun ControlCard(
    s: MeshChatViewModel.DebugControlState,
    onControl: (com.meshchat.app.mesh.debug.DebugControl) -> Unit,
    onResetControls: () -> Unit,
) {
    SectionCard("主动控制") {
        StatRow("心跳频率", "${s.heartbeatMs}ms · 失联阈值 ${s.lostMs}ms", Cyan)
        Row {
            listOf(500L to "0.5s", 1_000L to "1s", 2_000L to "2s", 5_000L to "5s").forEach { (v, label) ->
                FilterChip(
                    selected = s.heartbeatMs == v,
                    onClick = { onControl(com.meshchat.app.mesh.debug.DebugControl.SetHeartbeat(v, v * 2)) },
                    label = { Text(label) },
                    modifier = Modifier.padding(end = 6.dp),
                )
            }
        }
        Text("重发退避", color = TextSecondary, style = monoStyle(), modifier = Modifier.padding(top = 8.dp))
        Row {
            listOf(
                3_000L to "基础3s·封顶30s",
                10_000L to "基础10s·封顶60s",
                30_000L to "基础30s·封顶120s",
            ).forEach { (v, label) ->
                FilterChip(
                    selected = s.resendBaseMs == v,
                    onClick = { onControl(com.meshchat.app.mesh.debug.DebugControl.SetResendPolicy(v, when (v) { 3_000L -> 30_000L; 10_000L -> 60_000L; else -> 120_000L })) },
                    label = { Text(label) },
                    modifier = Modifier.padding(end = 6.dp),
                )
            }
        }
        Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = s.signalingSuspended,
                onClick = {
                    onControl(
                        if (s.signalingSuspended) com.meshchat.app.mesh.debug.DebugControl.ResumeSignaling
                        else com.meshchat.app.mesh.debug.DebugControl.SuspendSignaling,
                    )
                },
                label = { Text(if (s.signalingSuspended) "恢复广播+扫描" else "暂停广播+扫描") },
            )
            FilterChip(
                selected = false,
                onClick = { onControl(com.meshchat.app.mesh.debug.DebugControl.BroadcastPing) },
                label = { Text("发 PING") },
            )
            TextButton(onClick = onResetControls) { Text("恢复默认") }
        }
        if (s.lastPingAtMs >= 0) {
            StatRow("上次手动 PING", "${(System.currentTimeMillis() - s.lastPingAtMs).coerceAtLeast(0)}ms 前")
        }
    }
}
```

- [ ] **步骤 3：DebugSettingsPanel 的板块显隐加「控制」**

`DebugSettingsPanel` 的板块列表（`"收发包" to s.showFrames, ... "文件" to s.showFile`）加一项：

```kotlin
                "控制" to s.showControl,
```

`toggleSection` 函数加分支：

```kotlin
    "控制" -> s.copy(showControl = !s.showControl)
```

- [ ] **步骤 4：MeshChatHome 签名加 3 参数 + debug 分支透传**

签名（`onResetDebugStats` 之后）加：

```kotlin
    debugControlState: MeshChatViewModel.DebugControlState,
    onDebugControl: (com.meshchat.app.mesh.debug.DebugControl) -> Unit,
    onResetDebugControls: () -> Unit,
```

`"debug" -> DebugCenterScreen(...)` 分支调用加参数：

```kotlin
                controlState = debugControlState,
                onControl = onDebugControl,
                onResetControls = onResetDebugControls,
```

- [ ] **步骤 5：MeshChatApp collect + 透传**

`val debugSettings by viewModel.debugSettings.collectAsStateWithLifecycle()` 之后加：

```kotlin
    val debugControlState by viewModel.debugControlState.collectAsStateWithLifecycle()
```

`MeshChatHome(...)` 调用（`onResetDebugStats = viewModel::resetDebugStats,` 之后）加：

```kotlin
            debugControlState = debugControlState,
            onDebugControl = viewModel::sendDebugControl,
            onResetDebugControls = viewModel::resetDebugControls,
```

- [ ] **步骤 6：编译验证**

运行：`.\gradlew.bat compileDebugKotlin --console=plain`
预期：BUILD SUCCESSFUL

- [ ] **步骤 7：Commit**

```bash
git add app/src/main/java/com/meshchat/app/ui/screens/DebugCenterScreen.kt app/src/main/java/com/meshchat/app/ui/screens/MeshChatHome.kt app/src/main/java/com/meshchat/app/ui/MeshChatApp.kt
git commit -m "feat: 调试中心主动控制面板 ControlCard——心跳/重发/暂停恢复/手动PING/恢复默认 + 板块显隐"
```

---

### 任务 7：版本/文档/全量验证

**文件：**
- 修改：`app/build.gradle.kts`
- 修改：`README.md`
- 修改：`AI_CONTEXT.md`

- [ ] **步骤 1：版本 bump**

`app/build.gradle.kts`：`versionCode = 70` → `versionCode = 71`；`versionName = "1.1.8"` → `versionName = "1.1.9"`

- [ ] **步骤 2：README 调试中心条目补主动控制**

定位 `- 调试中心（v1.1.5）：` 行，在其后追加新行：

```markdown
- 调试中心主动控制（v1.1.9）：调节心跳广播频率（0.5s/1s/2s/5s，失联阈值联动×2）、消息重发退避（基础3s/10s/30s·封顶联动）、暂停/恢复广播+扫描（保留已建 GATT 连接）、手动发 PING 链路探测、一键恢复默认（全部内存态重启回默认，未调节时行为零变化）
```

- [ ] **步骤 3：AI_CONTEXT.md 更新**

① 版本行：`**当前版本：v1.1.8（versionCode 70...` → `**当前版本：v1.1.9（versionCode 71，构建时间 2026-08-05）**`
② 进度区追加 v1.1.9 条目（DebugControl 控制总线/volatile 参数/6 控制方法/ControlCard 面板/测试 121）
③ 已验证内容追加 v1.1.9（121/121 + assembleDebug SUCCESS + APK）
④ 当前阻塞更新（v1.1.9 加入待验证列表）
⑤ 下一步首要任务更新（v1.1.9 真机验证，含主动控制面板操作验证）
⑥ 本次涉及的关键文件追加 v1.1.9 条目

- [ ] **步骤 4：全量验证**

运行：`.\gradlew.bat testDebugUnitTest assembleDebug --console=plain`
预期：BUILD SUCCESSFUL；测试 **121/121**（DebugStatsTest 6 + MeshServiceTest 45 + 其余回归）
核对：`app/build/test-results/testDebugUnitTest/` 各 XML `failures="0" errors="0"`，总数 121

- [ ] **步骤 5：复制 APK**

```powershell
Copy-Item app\build\outputs\apk\debug\app-debug.apk "MeshChat-v1.1.9-debug.apk" -Force
```
预期：`MeshChat-v1.1.9-debug.apk` 生成于工程根目录

- [ ] **步骤 6：Commit**

```bash
git add app/build.gradle.kts README.md AI_CONTEXT.md
git commit -m "build: v1.1.9 调试中心主动控制——版本 bump（71）、README/AI_CONTEXT 更新（122/122 验证）"
```

---

## 自检对照（规格覆盖）

| 规格章节 | 计划任务 |
|---|---|
| §3 DebugControl 命令集 | 任务 1 |
| §3 DebugStats attachControls/issue | 任务 1 |
| §4.1 MeshService 控制面 + volatile + handler | 任务 3 |
| §4.2 BleTransport suspend/resumeDiscovery | 任务 4 |
| §5 心跳↔失联联动（UI 下发 interval*2） | 任务 6（ControlCard 心跳行） |
| §6 UI ControlCard + 板块显隐 | 任务 6 |
| §6 ViewModel DebugControlState/sendDebugControl | 任务 5 |
| §7 测试（DebugStatsTest +1、MeshServiceTest +3） | 任务 1、任务 3 |
| §9 版本 v1.1.9/71 + 文档 | 任务 7 |
| §8 边界（coerceIn 钳制、幂等、内存态） | 任务 3 步骤 5（setter coerceIn）、任务 3 步骤 6（默认值不变） |

**类型一致性检查：** `DebugControl.SetHeartbeat/SetResendPolicy/SuspendSignaling/ResumeSignaling/BroadcastPing/ResetControls` 全计划统一；`sendDebugControl/resetDebugControls/debugControlState/DebugControlState` 在任务 5/6 中签名一致；`suspendDiscovery/resumeDiscovery` 在任务 2/3/4 中一致。
