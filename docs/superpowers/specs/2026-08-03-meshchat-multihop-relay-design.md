# MeshChat 网状中继（多跳）v1 设计规格

- 日期：2026-08-03
- 状态：方案已确认，文档待用户审查
- 目标版本：v1.1.0（多跳中继）

## 1. 背景与目标

MeshChat 当前是"一跳直连"通信：A 与 C 必须在 BLE 无线电范围内（且建立 GATT 连接）才能收发消息。当 A 与 C 之间隔着设备 B（A、B、C 各在相邻范围内）时，A 无法与 C 通信。

本版目标：**打通多跳中继**——消息经中间设备（纯中继，无需会话关系）转发，使 A→B→C 的隔设备通信成立，且送达确认能沿中继回传。为后续群组（GROUP 载荷已定义）在网状网络中交流铺路。

范围外（本版不做，见 §11）：群组上层逻辑、文件多跳、真实加密、3 跳以上路由可视化、路由表持久化。

## 2. 术语

| 术语 | 含义 |
|------|------|
| 一跳节点 | 直接扫描/心跳可达的节点（现有 peerEntries） |
| 中继 / 中间节点 | 转发他人消息的节点（无需与收发双方有会话） |
| 2 跳节点 | 经某个中继可达、非直接相邻的节点 |
| 路由条目 | 远端节点 → (经由中继 shortId, 跳数) |
| relays | 本机一跳邻居 shortId 列表（随 PING 携带） |

## 3. 现状盘点（可复用骨架）

- **转发决策**：`ForwardingDecision.decide()` 已实现——dstId==本机→Deliver；TTL-1>0→Forward；否则 Drop；DedupCache 去重防环。
- **限制**：`MeshService.handleEnvelope` 的 else 分支仅当 `dstId.isBlank() || dstId==本机 || srcId in sessions` 才 route——**非会话节点间的帧被丢弃，纯中继不成立**；且 outbox 只落库、tick 未重发（转发帧丢即丢）。
- **心跳**：PING/PONG 每 1s 广播（PresenceBody：displayName + ackIds），双向校准在线状态。
- **送达确认三层冗余**：RECEIPT 广播（FrameType.RECEIPT）、PONG ackIds、广播扫描响应 ackKeys——当前均为一跳语义。

## 4. 设计决策（已与用户确认）

| 决策点 | 选择 | 理由 |
|--------|------|------|
| 中继范围 | **纯中继**：任何设备收到非本机 TEXT/RECEIPT 帧都转发，不要求会话关系 | 路过的设备天然当路由器；用户确认，加密后置 |
| 心跳转发 | **不转发** PING/PONG（一跳内有效） | 每台设备每 1s 广播 PING，若全网转发即风暴 |
| 路由信息 | **搭心跳便车**：PresenceBody 新增 `relays`，每 3 次心跳（3s）携带一次 | 用户选定；避免独立路由帧、绑定 1s 心跳节流，2~3 次搭一次控带宽 |
| 路由可见范围 | **2 跳**：A 听到 B 的 PING（带 B 的邻居列表）→ 知道 C 经 B 可达 | 满足"隔一台设备通信"的验证目标，YAGNI |
| 多跳消息类型 | **TEXT + RECEIPT** 中继；FILE/FILE_ACK、INVITE/INVITE_ACK、GROUP 不中继 | 用户选定：本版只中继文本消息 |
| 防风暴 | TTL 递减（沿用 8）+ DedupCache 去重 + 转发前随机 50-250ms 抖动 | 每节点每帧最多转发一次，总量=节点数×帧数 |
| 在线状态 | 保持一跳语义（心跳不转发） | 防止全网风暴；2 跳对端显示"经中继可达"而非"在线" |

## 5. 协议变更

### 5.1 PresenceBody 新增 relays

```kotlin
@Serializable
@SerialName("PING")
data class PresenceBody(
    val displayName: String,
    val ackIds: List<String> = emptyList(),   // 已有：送达确认键（心跳 PONG 携带）
    val relays: List<String> = emptyList(),   // 新增：本机一跳邻居 shortId 列表
) : EnvelopeBody
```

- 默认空列表：老版本设备解码新增字段缺失时按空处理，双向兼容。
- 仅 PING 携带 relays（PONG 不需要：PONG 是对 PING 的应答，回程已有一跳）。
- 发送节奏：`sendPing()` 维护计数器，`pingCount % 3 == 0` 时 relays=当前一跳邻居（lastSeen 距今 ≤10s 的 peerEntries 键），否则 relays=空。

## 6. 转发逻辑（MeshService）

### 6.1 纯中继：TEXT 帧

改造 `handleEnvelope` 的 else 分支（现为 `if (dstId.isBlank() || dstId == identity.shortId || srcId in _sessions.value) route(...)`）：

- 对 TEXT 帧（kind=="TEXT"）：
  - `dstId == identity.shortId` → route（Deliver）
  - `dstId != identity.shortId` 且 `ttl - 1 > 0` → route（Forward，TTL 递减后继续广播）——**移除 srcId in sessions 限制**
  - 其余 → 不处理
- 转发帧与投递帧共用 `route()`：`ForwardingDecision` 已按 dstId 决策 Deliver/Forward/Drop，语义不变。

### 6.2 RECEIPT 转发（确认回传，防环）

`handleFrame` 的 RECEIPT 分支（现为收到即 `handleReceipt`）：

- 解析 RECEIPT 载荷中的 `id`，以 `"receipt-$id"` 作为去重键写入 DedupCache（独立命名空间，与消息去重互不干扰）：
  - 未见过 → ① `handleReceipt`（本机确认自己的 pending）② 转发一次（broadcast 同帧）
  - 已见过 → 仅 `handleReceipt` 本地处理，不转发
- 效果：RECEIPT 沿网络扩散一遍即停，A 最终能收到 C 的回执。

### 6.3 心跳不转发

PING/PONG 保持现状：`handleEnvelope` 的 "PING"/"PONG" 分支只 markSeen/回执，不进入 route/Forward。INVITE/INVITE_ACK、FILE/FILE_ACK、GROUP 同样不进入转发路径（保持一跳）。

### 6.4 转发抖动

`route()` 的 Forward 分支在 `transport.broadcast` 前增加随机延迟 50-250ms（`scope.launch { delay(random); broadcast }`），错开多机同步转发，防风暴。

### 6.5 outbox 重发接入 tick

`tickJob` 每 200ms 调用新增 `resendOutbox(now)`：

- `store.nextOutbox(now)` 取未过期条目（expireAt=发出时+OUTBOX_TTL_MS）
- **每条目节流至每 1s 重发一次**（记录上次重发时刻，距上次 <1s 跳过），重发时重新 broadcast（envelope 重编码）
- `attempts >= 3` 或 `expireAt` 已过 → `store.removeOutbox(id)` 移除
- 中继丢帧由 outbox 重发兜底（转发可靠性，最多重试 3 次 / 3 秒）

## 7. 路由表（MeshService）

### 7.1 数据结构

```kotlin
private data class RouteEntry(val via: String, val hops: Int, val lastSeenAt: Long)
private val routeEntries = LinkedHashMap<String, RouteEntry>()  // 远端 shortId -> 路由
```

### 7.2 学习

- 收到 PING（srcId=B，body.relays 非空）：对每个 relay C：
  - 若 C 已是本机一跳节点（peerEntries 中有且 lastSeen 新鲜）→ 忽略（一跳优先）
  - 否则写入/覆盖 `routeEntries[C] = RouteEntry(via=B, hops=2, now)`（若已有更短/更新的条目则保留更优者）
- 一跳节点不进 routeEntries（直接用 peerEntries）。

### 7.3 失效

- 中继 B 心跳超时（peerEntries[B].lastSeen 距今 > OFFLINE_THRESHOLD_MS=15s）→ 移除所有 `routeEntries` 中 via==B 的条目。
- 条目自身超时（lastSeenAt 距今 > 30s，即 B 的 3 次心跳周期未再确认）→ 移除。
- 清理挂在 heartbeatTick 内（每 200ms tick，开销小）。

### 7.4 对外暴露

`MeshPeerInfo.hops` 已有字段（默认 1）。2 跳节点通过路由表合成：

```kotlin
// peers 流在 tick 刷新时：对每个 routeEntries 条目生成 "中继可达" 的展示信息
// 复用现有 peers StateFlow：新增字段 relayVia（经由中继 shortId，默认空）
data class MeshPeerInfo(
    ...,
    val relayVia: String = "",   // 经中继可达时的经由节点；空 = 一跳
)
```

`MeshRepository` 的 `MeshPeer` UI 模型同步增加 `relayVia`，UI 据此显示。

## 8. 发送与确认语义

- 发送 TEXT：目标为一跳节点 → 直接广播（现有）；目标不在 peers 但 routeEntries 有 2 跳条目 → 照常广播（中继自动转发），消息状态文案显示"经中继送达"。
- 送达确认：C 收到后发 RECEIPT（现有）+ PONG ackIds 经中继泛洪回传（§6.2）→ A 的 pendingReceipts 收敛 → 状态翻"已送达"。广播扫描响应 ackKeys 通道在一跳内仍有效（A 直接听到 C 时）。
- 中间节点 B 收到 TEXT（非本机）只转发，不落库、不弹通知、不影响 B 的 UI。

## 9. UI 变更

- **Mesh 页节点行**（MeshScreen PeerRow）：`relayVia` 非空时显示"经 {relayVia} 可达 · 2跳"（替代信号条文案位置，保留 RSSI 数值）；一跳节点不变。
- **会话页标题栏状态行**（ConversationScreen Header）：对端非一跳在线、但经中继可达（2 跳）时，状态文案为"经 {relayVia} 可达 · 消息经中继送达"（颜色 TextSecondary 灰）。
- 消息气泡：发送方对 2 跳目标发送的消息，送达状态文案追加"· 经中继"。

## 10. 边界与错误处理

| 场景 | 行为 |
|------|------|
| 中继掉线（转发中断） | outbox 每 1s 重发 ≤3 次；路由条目随中继失联移除 |
| 收到自己刚转发的帧（环） | DedupCache 按信封 id 去重，已见即 Drop |
| RECEIPT 环 | "receipt-$id" 去重键，全网最多转发一次 |
| 转发抖动期间消息重发 | 同一 envelope id 去重，重复广播由 dedup 收敛 |
| 老版本设备（无 relays） | relays 默认空，行为与现状一致（一跳）；老版本收到新版 PING 忽略未知字段 |
| 2 跳节点发消息回来 | 对称逻辑：其 PING 也带 relays，双向学习 |

## 11. 范围外（后续版本）

- 群组（GROUP 载荷已有：JOIN/LEAVE/MSG + 群消息多跳）
- 文件多跳（FILE/FILE_ACK 中继 + 窗口重传）
- 真实加密（中继节点当前可见明文；用户已确认"加密后面再搞"）
- 3 跳以上路由（relays 不转发，天然只到 2 跳；如需 3 跳+需引入 ROUTE 帧或路由转发）
- 路由表持久化（当前内存态，重启重建）

## 12. 测试计划

### 12.1 单测（MeshServiceTest 新增）

1. `non session peer relays text to third node`：B（非会话节点）收到 A→C 的 TEXT，route 后 broadcast 转发（TTL 递减）。
2. `relayed text is not stored or notified on relay node`：B 转发但 insertMessage/onIncomingMessage 不被调用。
3. `receipt is forwarded once and deduplicated`：B 收到 RECEIPT → 处理 + 转发一次；重复收到不转发。
4. `ping carries relays every third heartbeat`：sendPing 第 3 次带 relays、前 2 次为空。
5. `route entries learned from ping relays`：收到带 relays=[C] 的 PING → routeEntries 有 C→(via=B, hops=2)。
6. `route entries expire when relay goes offline`：中继超时后条目移除。
7. `ping pong are not forwarded`：收到 PING/PONG 不产生转发 broadcast。
8. 既有 63 例全量回归。

### 12.2 真机验收（3 台：A11 / A12 / A16）

- 排布 A—B—C（相邻两两可达，A 与 C 互不可见）：
  1. A 的 Mesh 页看到 C"经 B 可达 · 2跳"
  2. A 给 C 发 TEXT → C 收到；A 状态翻"已送达"（RECEIPT 经 B 回传）
  3. C 回 TEXT → A 收到（对称）
  4. B 中途删后台 → 重进后 A 的路由条目重新学习；B 不在线期间 A→C 消息经 outbox 重发，B 恢复后补达（尽力而为）
  5. 关 B 的蓝牙 → A 侧 C 变"经中继不可达"（路由移除）

## 13. 涉及文件

- 协议：`mesh/protocol/MeshEnvelope.kt`（PresenceBody.relays）
- 服务：`mesh/service/MeshService.kt`（转发改造、RECEIPT 转发、outbox 重发、路由表、PING relays 节流）
- 传输模型：`mesh/transport/MeshTransport.kt`（MeshPeerInfo.relayVia）
- 数据：`data/UiModels.kt`（MeshPeer.relayVia）、`data/MeshRepository.kt`（透传）
- UI：`ui/screens/MeshScreen.kt`（节点行）、`ui/screens/ConversationScreen.kt`（状态行/气泡）
- 测试：`test/.../MeshServiceTest.kt`
