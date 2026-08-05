# 调试中心·主动控制设计（v1.1.9）

> 日期：2026-08-05 ｜ 前置：v1.1.8 调试中心统计版已实装（DebugStats 统计内核 + 五板块仪表盘 + 7 项观察调节）
> 本规格为调试中心**第二期：主动控制**——在纯观察统计之上，增加对真实发送节奏/发现链路的主动操控能力。

## 1. 背景与目标

v1.1.8 调试中心只能**被动观察**（收发包速率、BLE 统计、送达链路）。用户实测后要求增加**主动调试**能力：

- 控制心跳广播频率（PING 发送间隔，默认 1s）
- 控制消息重发退避（基础间隔 3s → 封顶 30s）
- 暂停/恢复广播+扫描（模拟静默/单通故障）
- 手动发送测试帧（主动探测链路）
- 一键恢复全部默认（内存态，重启亦回默认）

**约束**：
- 不修改协议/路由/存储逻辑；调节只影响**发送节奏与发现链路**，不改帧内容与路由决策
- 所有可调参数默认值与现实现一致，未调节时行为零变化
- 控制面全部**内存态**（重启回默认），与统计一致
- 保持"后端稳定性优先"：控制实现为独立公开方法 + volatile 参数，不影响既有收发时序

## 2. 架构总览

```
DebugCenterScreen (UI)
    │  调节项点击
    ▼
MeshChatViewModel.sendDebugControl(cmd)
    │
    ▼
DebugStats.issue(cmd) ──转发──► handler（MeshService.start() 注册）
    │                                │
    └──（内核仅转发，不持有服务引用）  ├──► MeshService 控制方法（心跳/重发/暂停/PING/重置）
                                     └──► BleTransport.suspendDiscovery()/resumeDiscovery()
```

- **DebugStats**：新增控制总线（纯 Kotlin、无线程、无 Android 依赖），只做命令转发
- **MeshService**：控制面实现者，注册 handler
- **BleTransport**：`suspendDiscovery()/resumeDiscovery()` 只操作 advertise+scan，不碰 GATT

## 3. 控制命令集（DebugControl）

`mesh/debug/DebugControl.kt` 新增 sealed class：

```kotlin
sealed class DebugControl {
    /** 心跳：PING 广播节流间隔 + 本机失联判定阈值（联动，见 §5） */
    data class SetHeartbeat(val intervalMs: Long, val lostMs: Long) : DebugControl()
    /** 重发退避：消息未确认重发的基础间隔与封顶 */
    data class SetResendPolicy(val baseMs: Long, val maxMs: Long) : DebugControl()
    /** 暂停/恢复广播+扫描（已建立 GATT 连接不受影响） */
    data object SuspendSignaling : DebugControl()
    data object ResumeSignaling : DebugControl()
    /** 立即广播一轮 PING（链路探测） */
    data object BroadcastPing : DebugControl()
    /** 恢复全部默认 */
    data object ResetControls : DebugControl()
}
```

DebugStats 新增：

```kotlin
private var controlHandler: ((DebugControl) -> Unit)? = null
fun attachControls(handler: (DebugControl) -> Unit) { controlHandler = handler }
fun issue(cmd: DebugControl) { controlHandler?.invoke(cmd) }   // 无 handler 时静默丢弃（测试/未装配场景）
```

## 4. 后端控制面

### 4.1 MeshService（`mesh/service/MeshService.kt`）

新增公开方法（全部幂等、可逆）：

```kotlin
fun setHeartbeat(intervalMs: Long, lostMs: Long) {
    heartbeatIntervalMs = intervalMs.coerceIn(200L, 10_000L)
    lostHeartbeatMs = lostMs.coerceIn(500L, 20_000L)
}
fun setResendPolicy(baseMs: Long, maxMs: Long) {
    resendBaseMs = baseMs.coerceIn(500L, 60_000L)
    resendMaxMs = maxMs.coerceIn(baseMs, 300_000L)
}
fun suspendSignaling() = transport.suspendDiscovery()
fun resumeSignaling() = transport.resumeDiscovery()
fun broadcastPing() { sendPing() }   // 复用现有心跳 PING 发送逻辑
fun resetDebugControls() {
    heartbeatIntervalMs = HEARTBEAT_INTERVAL_MS
    lostHeartbeatMs = LOST_HEARTBEAT_MS
    resendBaseMs = RECEIPT_TIMEOUT_MS
    resendMaxMs = MAX_RESEND_INTERVAL_MS
    resumeSignaling()   // 若处于暂停态，恢复
}
```

常量改读 volatile 字段（**默认值不变**，未调节时行为零变化）：

```kotlin
@Volatile private var heartbeatIntervalMs: Long = HEARTBEAT_INTERVAL_MS   // 现 1_000L
@Volatile private var lostHeartbeatMs: Long = LOST_HEARTBEAT_MS           // 现 2_000L
@Volatile private var resendBaseMs: Long = RECEIPT_TIMEOUT_MS             // 现 3_000L
@Volatile private var resendMaxMs: Long = MAX_RESEND_INTERVAL_MS          // 现 30_000L
```

使用处替换：
- `heartbeatTick`：`now - lastPingAt >= HEARTBEAT_INTERVAL_MS` → `>= heartbeatIntervalMs`（第 509 行）
- `heartbeatTick` 状态机：`age < LOST_HEARTBEAT_MS`、`entry.lost = age > LOST_HEARTBEAT_MS` → 读 `lostHeartbeatMs`（第 530/534 行）
- `resendPendingReceipts`：退避公式 `minOf(RECEIPT_TIMEOUT_MS * (1L shl ...), MAX_RESEND_INTERVAL_MS)` → `minOf(resendBaseMs * (1L shl ...), resendMaxMs)`（第 561 行）

`start()` 末尾注册控制 handler：

```kotlin
debugStats.attachControls { cmd -> when (cmd) {
    is DebugControl.SetHeartbeat -> setHeartbeat(cmd.intervalMs, cmd.lostMs)
    is DebugControl.SetResendPolicy -> setResendPolicy(cmd.baseMs, cmd.maxMs)
    DebugControl.SuspendSignaling -> suspendSignaling()
    DebugControl.ResumeSignaling -> resumeSignaling()
    DebugControl.BroadcastPing -> broadcastPing()
    DebugControl.ResetControls -> resetDebugControls()
} }
```

### 4.2 BleTransport（`mesh/transport/BleTransport.kt`）

新增两个方法——**只操作广播+扫描，保留 GATT server/clients 与已建立连接**：

```kotlin
/** 暂停发现层：停广播+停扫描；已建立 GATT 连接仍可收发（写/notify 不受影响）。 */
fun suspendDiscovery() {
    runCatching { bluetoothAdapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) }
    runCatching { bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback) }
}
/** 恢复发现层：重新广播+扫描。 */
fun resumeDiscovery() {
    runCatching { startAdvertising() }
    runCatching { startScanning() }
}
```

注意：`suspendDiscovery()` 重复调用安全（stop 幂等）；`resumeDiscovery()` 与 `start()` 幂等互不干扰。

## 5. 心跳↔失联联动规则

| 心跳间隔（intervalMs） | 失联阈值（lostMs，联动） | 说明 |
|---|---|---|
| 500ms | 1_000ms | 加速刷新（近距离高频场景） |
| 1_000ms | 2_000ms | **默认**（现实现） |
| 2_000ms | 4_000ms | 中频省电 |
| 5_000ms | 10_000ms | 低频省电（对端需同调否则误判失联） |

- 联动公式：`lostMs = intervalMs * 2`，下限 500ms/1_000ms
- UI 调节心跳时自动计算并下发成对的 `SetHeartbeat(interval, interval*2)`
- **边界提示**：失联判定是本机视角；对端未同步调节时，其"你在线"状态基于它自己的阈值，双方显示可能不对称——调试场景可接受，`ResetControls` 一键还原

## 6. UI：主动控制面板（`ui/screens/DebugCenterScreen.kt`）

在现五板块之上新增第六板块 **「主动控制」**（`ControlCard`），位于 FramesCard 之后：

- **心跳频率**：FilterChip 组 `0.5s / 1s / 2s / 5s`；选中态高亮；副标题显示联动值 `失联阈值 Xs`
- **重发退避**：FilterChip 组 `基础 3s / 10s / 30s`；副标题显示 `封顶 30s / 60s / 120s`
- **暂停/恢复**：单个切换按钮 `暂停广播+扫描` ⇄ `恢复广播+扫描`（暂停态红色高亮）
- **手动 PING**：按钮 `发 PING` + 最近发送反馈文案 `上次 PING：Xms 前`（复用 snapshot.system 时间基准或本地记录）
- **恢复默认**：按钮 `恢复默认`（下发 ResetControls + 本地重置选中态）

交互：
- 点击任一 FilterChip → `onControl(viewModel.mapControl(...))` → `viewModel.sendDebugControl(cmd)` → `debugStats.issue(cmd)`
- 控制面板数据源：ViewModel 新增 `debugControlState: StateFlow<DebugControlState>`（当前心跳/失联/重发档位 + paused 标记 + 上次 PING 时间），由 `sendDebugControl` 维护
- 与现 `DebugSettings`（观察类调节）分离：`DebugControlState` 独立 data class，避免混淆

**ViewModel 新增**：

```kotlin
data class DebugControlState(
    val heartbeatMs: Long = 1_000L, val lostMs: Long = 2_000L,
    val resendBaseMs: Long = 3_000L, val resendMaxMs: Long = 30_000L,
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
fun resetDebugControls() { debugStats.issue(DebugControl.ResetControls); _debugControlState.value = DebugControlState() }
```

## 7. 测试

### 7.1 MeshServiceTest 新增（+3）

1. **心跳间隔生效**：`setHeartbeat(2_000, 4_000)` 后推进 1s，PING 未发出（原默认 1s 会发）；推进到 2s，PING 发出
2. **重发退避生效**：`setResendPolicy(10_000, 60_000)` 后发送未确认消息，1s 时未重发；10s 时重发一次
3. **暂停/恢复**：`suspendSignaling()` 后 `transport.broadcast` 调用仍可（GATT 通道保留语义不变），但发现层停止（InMemoryTransport 扩展 `discoverySuspended` 标志可断言）；`resumeSignaling()` 恢复
   - 实现：InMemoryTransport 增加 `suspendDiscovery()/resumeDiscovery()` 覆写 + `discoverySuspended: Boolean` 断言位

### 7.2 DebugStatsTest 新增（+1）

4. **issue 转发**：attachControls 注册 handler 后 `issue(SetHeartbeat(500, 1000))` 收到命令；未注册时静默不抛

### 7.3 全量

原有 117 + 新增 5 = **122/122 通过**；`assembleDebug` BUILD SUCCESSFUL。

## 8. 边界与风险

- **协议/路由/存储零改动**：调节仅触及发送节奏常量与发现层开关
- **对端状态不对称**：心跳/失联双端判定，单端调节会出现瞬时状态差异（调试工具特性，`恢复默认` 兜底）
- **暂停广播+扫描的语义**：停的是 advertise+scan（发现层），已建立 GATT 连接的消息收发不受影响（符合"模拟单通/静默但保留已有链路"的调试意图）
- **内存态**：任何调节不持久化，重启回默认；不会污染正常用户路径
- **volatile 参数范围钳制**：所有 setter 做 coerceIn 防越界（心跳 200ms-10s、失联 500ms-20s、重发基础 500ms-60s、封顶 ≥ 基础 ≤ 300s）

## 9. 版本

- `app/build.gradle.kts`：versionCode 71 / versionName 1.1.9
- 新增文件：`mesh/debug/DebugControl.kt`
- 修改：`mesh/debug/DebugStats.kt`（控制总线）、`mesh/service/MeshService.kt`（控制面 + volatile）、`mesh/transport/BleTransport.kt`（suspend/resumeDiscovery）、`mesh/transport/InMemoryTransport.kt`（测试替身）、`ui/MeshChatViewModel.kt`（DebugControlState/sendDebugControl）、`ui/screens/DebugCenterScreen.kt`（ControlCard）、`README.md`、`AI_CONTEXT.md`
- 测试：`DebugStatsTest` +1、`MeshServiceTest` +3、InMemoryTransport 断言位适配
