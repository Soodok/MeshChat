# MeshChat 后台常驻 + 状态校准 + 节点命名规格（v0.14.0）

> 日期：2026-08-03
> 版本：v0.14.0
> 前置：v0.13.1（RFCOMM 已停用，纯 BLE；会话为内存态、在线靠扫描推断、节点无命名）

## 1. 背景与目标

用户真机反馈两类问题：

1. **会话状态不同步**：一方退后台/被杀后，另一方 UI 仍显示"已连接"；重启方显示"未连接"，点击又要等对方接受；但双方实际上仍能互发消息——状态显示与实际能力割裂。
2. **节点无法识别**：节点以随机短 ID 标识，用户不知道哪台设备是谁。

同时用户提出硬性需求：**软件可挂后台、息屏状态下也能收到消息并弹窗**；**状态校准必须准确，可以一秒校准一次**。

**目标（v0.14.0）**：

- **后台/息屏常驻**：MeshChat 前台服务，息屏/退后台仍收消息并弹通知。
- **1s 心跳校准**：PING/PONG 双向确认，双方对节点在线状态认知对称收敛，3s 超时判失联。
- **节点命名**：用户自定义昵称，随心跳/邀请交换，持久化，UI 显示「昵称 + 短ID」。
- **会话持久化**：已建立的对话关系重启不丢，已会话节点点击直达、不再要求重新接受。

## 2. 现状（复用已有）

| 项 | 现状 |
|---|---|
| `MeshService` | 普通 Kotlin 类（CoroutineScope 管理），`start()/stop()` 编排 BLE 收发/转发/握手/文件传输；`_sessions` 为内存 `MutableStateFlow<Set<String>>`；tick 200ms 刷新 `peers`（lost 由扫描 RSSI 活跃度推断，>1.5s lost、>5s 移除） |
| `MeshEnvelopeBody` | sealed interface + `@SerialName` 多态（TEXT/FILE/FILE_ACK/GROUP），`MeshJson.encodeEnvelope/decodeEnvelope`；**新增 body 类型无需改序列化器** |
| `MeshTransport/MeshPeerInfo` | `MeshPeerInfo(shortId, deviceAddress, rssi, hops, lost)` 无 displayName；BLE 广播 Service Data 携带短 ID |
| `PeerEntity` | peers 表**已有 `displayName`/`lastSeen`/`hops` 字段**，但**无 upsert 写入路径**（`observePeers` 存在，无人消费） |
| `LocalIdentity` | 短 ID 持久化于 SharedPreferences `meshchat_identity`（键 `short_id`），无昵称 |
| UI | `MeshScreen.PeerRow` 显示 `peer.name`（当前 = 短 ID）+ RSSI/lost/会话态文字；点击节点走 INVITE 流程 |
| 权限 | `BLUETOOTH_CONNECT`（31+）/`BLUETOOTH`+`BLUETOOTH_ADMIN`（≤30）已有；**无 FOREGROUND_SERVICE / POST_NOTIFICATIONS** |
| 测试 | 39/39 通过（含 RfcommFraming/心跳无关项） |

## 3. 设计

### 3.1 后台常驻（前台服务）

**`MeshChatService : Service`（新，`app/src/main/java/com/meshchat/app/MeshChatService.kt`）**

- 职责：作为 `MeshService` 的 Android 宿主，持有实例并管理其生命周期；维护常驻通知与消息通知。
- `onCreate`：创建 `NotificationHelper`、构建 `MeshService`（复用 Application 的 transport/store/identity/dedup/saver/tmpDir + 新增 sessionStore/onIncomingMessage 回调）。
- `onStartCommand`：`startForeground(ID, 常驻通知, FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)`（API 34+ 传 type，33- 传 0）→ `meshService.start()` → 返回 `START_STICKY`（系统杀后自动重启并恢复状态）。
- `onDestroy`：`meshService.stop()`。
- 启动入口：`MeshChatApplication.startMesh()` 改为 `startForegroundService(Intent(this, MeshChatService::class.java))`；**设置页"后台常驻"开关（默认开）为 false 时不启动服务**（退化为当前 Application 直持 MeshService 的行为）。

**通知渠道（`NotificationHelper`，新）**：

- `meshchat_service`：常驻通知「MeshChat 运行中 · N 节点在线」，低优先级 `IMPORTANCE_MIN`，不可滑动关闭（`FLAG_NO_CLEAR`）。
- `meshchat_messages`：消息通知 `IMPORTANCE_HIGH`——收到 TEXT 时显示 `标题=发送者昵称，内容=消息正文`，点击 `PendingIntent` 打开 MainActivity 并定位到对应会话（EXTRA convId）；文件接收完成显示「文件已保存到 Downloads」。
- `POST_NOTIFICATIONS`（API 33+）运行时授权失败时静默降级（不弹消息通知，功能不受影响）。

### 3.2 1s 心跳校准

**协议（`MeshEnvelope.kt` 追加）**：

```kotlin
@Serializable
@SerialName("PING")
data class PresenceBody(val displayName: String) : EnvelopeBody
```

- PING：`kind="PING"`，`dstId=""`（广播），`body=PresenceBody(本机昵称)`。
- PONG：`kind="PONG"`，`dstId=对端短ID`（定向，广播发出带目标），`body=PresenceBody(本机昵称)`。

**MeshService 心跳逻辑**：

- `tick` 循环内每 **1000ms** 广播一次 PING（`HEARTBEAT_INTERVAL_MS=1_000L`；tick 本身 200ms 不变，内部计数节流）。
- `handleEnvelope` 新增分支：
  - `"PING"`：`dstId` 为空或本机时处理——`markSeen(srcId, displayName)`（更新 lastSeen + upsert 昵称）→ 回发 PONG（定向 `dstId=srcId`）。
  - `"PONG"`：`dstId` 为本机时 `markSeen(srcId, displayName)`。
- **在线判定**：`lost = now - lastSeen > LOST_HEARTBEAT_MS(3_000L)`——不再用扫描 RSSI 推断；3s 内任何 PING/PONG/帧到达都续期。扫描帧（`foundPeers`）仍可续期 lastSeen（低功耗兜底）。
- `start()` 时立即广播一轮 PING（不等首个 1s 周期）——进入 App 即校准。
- 节点移除阈值沿用 `LOST_REMOVE_MS=5_000L`（心跳模式每 1s 一帧，5s 无任何帧即真失联，列表移除合理）。
- PING 帧 <100B，远小于 BLE 帧预算，不与文件窗口流量争带宽（文件块帧间已有 30ms 节流）。

**`MeshPeerInfo` 追加 `displayName: String = ""`**（默认值保持现有构造点兼容——BleTransport 扫描构造、测试构造均可不改）。

### 3.3 节点命名

- **本机昵称**：`LocalIdentity` 加 `displayName` 属性，持久化于 SharedPreferences `meshchat_identity`（键 `display_name`，默认 = 短 ID）。设置页新增昵称输入框，保存即生效。
- **昵称交换**：PING/INVITE/INVITE_ACK 携带 `PresenceBody/TextBody` 之外的信封——INVITE 系列沿用 TextBody 不改；昵称交换走 PING/PONG（每 1s 一次，天然持续同步）。
- **落库**：`MeshStore` 新增 `upsertPeer(shortId, displayName, lastSeen, hops)`（peers 表，`PeerEntity` 字段已齐，Room `@Upsert` 或 DAO insert-on-conflict-replace；InMemoryMeshStore 同步实现）。收到 PING/PONG/扫描时调用。
- **UI**：节点行主标题 = `displayName`，副标题/次要信息带 `shortId`（`PeerRow` 显示「昵称 · 短ID」）。

### 3.4 会话持久化

- 新接口 `SessionStore`（`mesh/service/SessionStore.kt`）：
  ```kotlin
  interface SessionStore {
      fun load(): Set<String>
      fun save(sessions: Set<String>)
  }
  ```
- 生产实现 `SharedPrefsSessionStore(context)`（SharedPreferences `meshchat_sessions`，存字符串集合）；测试用内存替身。
- `MeshService` 构造新增 `sessionStore: SessionStore = object : SessionStore { override fun load() = emptySet<String>(); override fun save(sessions: Set<String>) {} }`——**默认值为内存 Noop（不持久化），保持现有测试/无 Context 构造兼容**；Application/MeshChatService 装配时传入 `SharedPrefsSessionStore(context)` 启用持久化。
- `_sessions` 每次变更（acceptInvite / INVITE_ACK 建立 / 启动恢复）后同步 `sessionStore.save(_sessions.value)`；`start()` 时 `sessionStore.load()` 恢复 `_sessions`。
- **点击直达**：`MeshChatHome/ViewModel` 判断节点 shortId ∈ sessions（持久化恢复后）→ 点击直接进入会话，不再发 INVITE。

### 3.5 UI 三态解耦

`MeshScreen.PeerRow` 状态文案（数据来自 `MeshService.peers` StateFlow，心跳驱动 lost）：

| 在线(lost=false) | 会话态 | 文案 | 颜色 |
|---|---|---|---|
| ✓ | 已会话 | 已连接 · 点击进入会话 | MeshGreen |
| ✓ | 未会话 | 点击发起对话 | TextSecondary |
| ✓ | pending | 等待对方接受 | MeshAmber |
| ✗ (lost) | 任意 | 失去连接 · 正在重连… | MeshRed |

- 点击行为：`connected && sessions.contains(shortId)` → 直达会话；否则走 INVITE 流程（现状不变）。

### 3.6 设置页

设置页新增两个条目：

1. **昵称**：文本输入框，保存到 `meshchat_identity.display_name`（重启生效于下次 PING；即时生效于本机 UI）。
2. **后台常驻**：Switch，默认开，存 `meshchat_settings.background_enabled`。关闭时：退出 App 即停服务（不 startForegroundService），再次打开 App 恢复。

## 4. 集成

| 文件 | 改动 |
|---|---|
| `protocol/MeshEnvelope.kt` | + `PresenceBody`（@SerialName("PING")） |
| `transport/MeshTransport.kt` | `MeshPeerInfo` + `displayName: String = ""` |
| `identity/LocalIdentity.kt` | + `displayName`（SharedPreferences 持久化） |
| `service/MeshService.kt` | 心跳（PING/PONG 分发、markSeen、lost 判定、1s 节流、start 抢校准）；`sessionStore` 注入与同步；`upsertPeer` 调用；`onIncomingMessage: (fromId: String, fromName: String, text: String) -> Unit = { _, _, _ -> }` 回调（TEXT 落库时触发，MeshChatService 弹通知） |
| `service/SessionStore.kt`（新） | 接口 + SharedPrefs 实现 |
| `service/MeshChatService.kt`（新） | 前台服务宿主 + NotificationHelper |
| `storage/MeshStore.kt`、`MeshDatabase.kt`、`InMemoryMeshStore.kt` | + `upsertPeer` |
| `MeshChatApplication.kt` | `startMesh()` → `startForegroundService`；`sessionStore` 装配；`backgroundEnabled` 开关读取 |
| `ui/.../MeshScreen.kt`、`MeshChatHome.kt`、`MeshChatViewModel.kt`、`MeshRepository.kt` | 昵称显示、三态文案、已会话点击直达、设置页昵称/后台开关 |
| `AndroidManifest.xml` | 注册 `MeshChatService`（`foregroundServiceType="connectedDevice"`）+ 新权限 |
| `MainActivity.kt` | `POST_NOTIFICATIONS` 运行时请求（API 33+） |
| `app/build.gradle.kts` | versionCode 26 / versionName 0.14.0 |

## 5. 测试策略

- 协议：`PresenceBody` 编解码（MeshEnvelopeTest 追加）。
- MeshService（新增测试，用内存 SessionStore/计数 transport 替身）：
  - 收到 PING → 回 PONG（定向 dstId=srcId）+ lastSeen 更新 + upsertPeer 昵称。
  - 收到 PONG → lastSeen 更新。
  - 3s 无心跳 → `peers` 节点 `lost=true`；恢复心跳 → `lost=false`。
  - sessions 持久化：save 在 acceptInvite/INVITE_ACK 后触发、load 在 start 恢复。
  - 心跳 PING 广播频率：模拟 tick 推进，1s 一帧（不刷屏）。
  - `onIncomingMessage` 回调在 TEXT 投递时触发。
- 现有 39 测试不回归（`MeshPeerInfo` 默认参数保证构造点兼容）。
- 真机验收：息屏/退后台收发消息 + 通知弹窗；双机状态 1s 内对称收敛；重启后会话/昵称/节点恢复；点击已会话节点直达。

## 6. 权限

- Manifest 新增：`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_CONNECTED_DEVICE`（API 34+，Manifest 声明 + 启动时 type）、`POST_NOTIFICATIONS`。
- 运行时：`POST_NOTIFICATIONS`（API 33+）加入 MainActivity 权限请求列表；拒绝则消息通知静默降级。

## 7. 边界情况

- **前台服务被杀**：`START_STICKY` 重启 → 状态从 SharedPreferences 恢复（sessions/昵称/peers）→ 立即 PING 校准。
- **蓝牙关闭/扫描被限**：心跳停 → 节点标 lost；恢复后自动校准。后台扫描限制由前台服务豁免。
- **心跳丢帧**：3s 窗口容忍 1-2 帧丢失，不误标 lost。
- **通知被拒（API 33+ 未授权）**：不弹消息通知，常驻通知也降级为不展示（服务照常运行）。
- **短 ID 冲突**：沿用现状（低概率，不处理）。
- **首次启动**：sessions 空、昵称=短 ID、无已知节点——UI 正常显示空态。

## 8. 范围外（不做）

- 消息离线推送（无公网，无推送服务）。
- 心跳带宽优化（多跳转发 PING 不做，PING 仅一跳广播）。
- 锁屏界面自定义（用系统通知即可）。
- 会话关系删除/黑名单管理（后续迭代）。
