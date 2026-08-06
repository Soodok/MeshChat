# MeshChat 群组交流 + 多跳补全 设计规格

- 日期：2026-08-06
- 状态：方案待用户审查
- 目标版本：阶段 A（群消息 MVP）= v1.1.50；阶段 B（多跳补墙）= v1.1.51
- 依赖：v1.1.0 多跳中继（TEXT 纯中继 / RECEIPT 泛洪 / 2 跳路由已实装）

## 1. 背景与目标

MeshChat 现状：点对点 TEXT 经中继最多 2 跳可达（v1.1.0）；**无群聊**；**2 跳节点无法建立会话**（INVITE 不中继）；群协议载荷（GroupBody）是 v1.0 遗留占位、上层零实现且缺群唯一 ID。

本规格两阶段：

- **阶段 A（v1.1.50）群消息 MVP**：群 = groupId 广播域，群消息走现有多跳中继泛洪，已订阅节点投递落库；群列表/创建群/群会话 UI。
- **阶段 B（v1.1.51）多跳补墙**：INVITE/INVITE_ACK 中继（任意跳建立会话）；验证 TEXT 泛洪任意跳可达。

范围外：群成员表/群主/邀请确认（广播域模型刻意不要）、群消息送达回执（MVP 不做）、**文件多跳（阶段 C，大工程单独设计）**、3 跳路由可视化（泛洪已覆盖，YAGNI，见 §4.2）。

## 2. 现状盘点

### 2.1 已就绪（v1.1.0，直接复用）

- TEXT 纯中继：任何设备收到非本机 TEXT 转发（TTL 8 递减 + DedupCache 去重 + 50-250ms 抖动），`handleEnvelope` else 分支 TEXT 处理。
- RECEIPT 泛洪回传："receipt-$id" 去重键，全网转发一次。
- 2 跳路由：PING 携带 `relays`（每 3 次心跳带一次），`learnRoutes` 学习 routeEntries，中继失联/超时清理。
- outbox 重发兜底：tick 1s 节流、≤3 次。
- UI：MeshPeer.relayVia、"经 X 可达 · 2跳"。

### 2.2 缺口（本规格要补）

| 缺口 | 现状 | 影响 |
|------|------|------|
| GROUP 零实现 | `GroupBody(op, groupName, text)` 占位；else 分支非 TEXT 帧只有 dstId 空/本机/会话成员才进 route；`toStoredMessage` 只读 TextBody → 群消息永不落库/永不显示 | 群聊完全不可用 |
| GroupBody 缺 groupId | 无群唯一 ID，无法寻址群消息 | 协议层第一缺口，必须先补 |
| INVITE 一跳 | `if (dstId.isNotBlank() && dstId != 本机) return` | 2 跳节点无法建会话：消息能中继到 C，但 A 点 C 发 INVITE 到不了 → 无法发起会话 |
| 3 跳路由 | relays 不转发，路由表天然 2 跳封顶 | 见 §4.2：泛洪实际已覆盖，仅缺可视化，YAGNI 不做 |

## 3. 阶段 A：群消息 MVP（v1.1.50）

### 3.1 设计决策

| 决策点 | 选择 | 理由 |
|--------|------|------|
| 群模型 | **广播域**：群消息 dstId=groupId，全网泛洪转发，已订阅节点投递落库 | 无中心/应急场景最自然；复用现有多跳泛洪，零成员管理 |
| 成员管理 | **无**：加入/退出 = 本地订阅/取消订阅 groupId（持久化） | JOIN/LEAVE op 预留但 MVP 不做成员表/群主/邀请 |
| 群消息确认 | **不做**（MVP）：群消息落库即 DELIVERED | 多成员回执泛洪放大流量；避免发送方卡"发送中" |
| 群 ID | 发送方生成（复用 ShortIdGen 风格，8 字符），创建时带群名 | 短（帧预算）+ 全网唯一 |
| 群名传播 | 随群消息/群创建广播携带 groupName，接收方学习 | 无中央目录，靠消息扩散 |
| 群通知 | 群消息弹通知（与点对点一致） | 应急场景需可见 |

### 3.2 协议变更（MeshEnvelope.kt）

```kotlin
@Serializable
@SerialName("GROUP")
data class GroupBody(
    val op: String,                 // "MSG"（MVP）；"JOIN"/"LEAVE" 预留
    val groupId: String,            // 新增：群唯一 ID（8 字符）
    val groupName: String? = null,  // 群名（创建时携带，随消息传播）
    val text: String? = null,
    val displayName: String = "",   // 新增：发送者昵称（群聊需区分谁说的，同 TextBody）
) : EnvelopeBody
```

- 兼容：老版本无 GROUP 功能（死代码），加字段零兼容负担；新版本不发旧格式 GROUP。

### 3.3 群消息发送（MeshService）

```kotlin
fun sendGroupMessage(groupId: String, text: String) {
    // envelope: kind="GROUP", dstId=groupId, convId="group-$groupId"
    // body=GroupBody(op="MSG", groupId, groupName=本机已知群名, text, displayName)
    // 本地立即落库（convId="group-$groupId", DELIVERED——MVP 无回执，不卡发送中）
    // 广播（走 route → 泛洪转发）
}
```

### 3.4 群消息接收（广播域模型，MeshService handleEnvelope 新增 "GROUP" 分支）

```
kind == "GROUP":
  body = envelope.body as? GroupBody ?: return
  if (body.groupId in joinedGroups) {
      route(envelope)                       // 已订阅 → Deliver 落库
      learnGroupName(body.groupId, body.groupName)  // 群名学习
  } else if (envelope.ttl - 1 > 0) {
      route(envelope, jitter = true)        // 未订阅 → 纯中继转发（与 TEXT 对称）
  }
```

- `route` 的 Deliver 分支：`toStoredMessage` 增加 GroupBody 分支（`text = body.text`，convId 用信封的 `group-<groupId>`，**不是** `conv-$srcId`——群会话键与点对点命名空间隔离）。需区分：GroupBody 的 convId 在 `MeshEnvelope.toStoredMessage` 中按 `convId` 字段直用，而非重写为 `conv-$srcId`。
- 群名学习：`groups: ConcurrentHashMap<groupId, name>` + 持久化。
- 已订阅判定：`joinedGroups: Set<String>`（SharedPreferences 持久化，同 SessionStore 模式）。

### 3.5 存储（GroupStore）

- SharedPrefs `meshchat_groups`：`joined: Set<String>`、`names: Map<groupId, name>`。
- 接口仿 SessionStore：`loadJoined()/saveJoined()/loadNames()/saveNames()`。

### 3.6 UI

- **群列表**：聊天页（ChatsScreen）顶部新增"群组"分区——已订阅群（群名 + 最后消息摘要，可最小化）；"创建群"按钮 → 输入群名 → 生成 groupId + 本地订阅 + 广播群创建帧（带群名，可选）。
- **群会话页**：复用 ConversationScreen，`conversationTarget` 支持群（target=groupId，会话键 group-<groupId>）；发送走 `sendGroupMessage`；**气泡显示发送者昵称**（群聊必需，点对点不显示）。
- **导航**：MeshChatHome 增加群入口路由（与 profileDetail 类似的 detail 机制）。

### 3.7 边界与错误处理

| 场景 | 行为 |
|------|------|
| 群消息风暴 | TTL 8 + DedupCache + 50-250ms 抖动（复用 TEXT 中继，全网每节点最多转发一次） |
| 重复群消息 | dedup 按信封 id 收敛 |
| 群名冲突/更新 | 后到覆盖（简单，无校验） |
| 未订阅节点收到群消息 | 只转发不落库不通知 |
| 群消息 TTL 耗尽 | 转发停止，Drop |
| 发送方未订阅自己创建的群 | 创建即订阅（本地） |

### 3.8 阶段 A 单测（MeshServiceTest 新增）

1. `group message delivered to subscriber and stored in group conversation`：已订阅节点收到 GROUP → 落库 convId=group-x、text 正确、不回执。
2. `group message forwarded by non subscriber without storing`：未订阅节点转发、insertMessage 不被调用。
3. `group body round trips groupId and displayName`：编解码。
4. `group name learned from group message`：收到带 groupName 的 GROUP → groups 表更新。
5. `group message ttl exhausted not forwarded`：TTL≤1 不扩散。
6. 既有 147 例全量回归。

## 4. 阶段 B：多跳补墙（v1.1.51）

### 4.1 INVITE/INVITE_ACK 中继（任意跳建会话）

现状（handleEnvelope INVITE/INVITE_ACK 分支首行）：
```kotlin
if (envelope.dstId.isNotBlank() && envelope.dstId != identity.shortId) return
```

改为与 TEXT 对称的纯中继：
```kotlin
"INVITE", "INVITE_ACK" -> {
    if (envelope.dstId.isBlank() || envelope.dstId == identity.shortId) {
        ...现有处理（弹窗/确认）...
    } else if (envelope.ttl - 1 > 0) {
        route(envelope, jitter = true)   // 中继转发：无关节点不弹窗，仅转发
    }
}
```

- 安全：dstId 寻址保证无关节点只转发不弹窗（现有逻辑已含 dstId 校验）。
- 去重：INVITE/INVITE_ACK 已有 dedup 标记（handleEnvelope 顶部）。
- 效果：A 发 INVITE(dstId=C) → B 转发 → C 弹窗接受 → INVITE_ACK(dstId=A) → B 转发 → A 会话建立 → 此后 TEXT 已中继可达。**2 跳/多跳点对点会话打通**。
- 限制：FILE 仍不中继（阶段 C），多跳会话可发消息但文件不可达——文档明确标注。

### 4.2 3 跳路由：不做，靠泛洪（YAGNI）

- 事实：TEXT/群消息走泛洪（TTL 8），A—B—C—D 链式排布下 D **已能收到** A 的消息（B→C 转发→C→D 转发），无需路由表。
- 缺失的只是"3 跳可达"的路由可视化与 routeEntries 扩展——本规格**不做**（泛洪已覆盖功能，可视化是锦上添花；如用户后续要 UI 显示 3 跳，单独加）。
- 路由表/UI 保持 2 跳（现状）。

### 4.3 阶段 B 单测（MeshServiceTest 新增）

1. `invite relayed to third node via middle hop`：B 收到 A→C 的 INVITE → 转发（TTL 递减），不弹窗。
2. `invite ack relayed back establishing session`：INVITE_ACK 经 B 转发 → A 会话建立。
3. `relay node does not show invite dialog`：B 收到非本机 INVITE，_invites 不更新。
4. `text reaches third node across two hops`：A→C TEXT 经 B 转发到 C（3 节点链式）。
5. 既有全量回归。

## 5. 阶段 C（范围外，仅记录）：文件多跳

FileTransferManager 是点对点窗口 ACK 协议（GATT 写 + 单对端 FILE_ACK），多跳需逐跳转发 + 端到端确认（或 FILE3 帧进中继转发 + ACK 泛洪回传），与聊天中继机制不同，工程量大，另行设计。群文件传输同样依赖此能力。

## 6. 测试计划

### 6.1 单测
见 §3.8（A）+ §4.3（B），分批随版本跑全量回归。

### 6.2 模拟器三机验证（A—B—C 链式，netsimd BLE）

- 阶段 A：A 创建群 → A、C（经 B）都订阅 → A 发群消息 → C 落库显示；B（未订阅）只转发不显示；群会话气泡显示昵称。
- 阶段 B：A 点 C（2 跳）→ INVITE 经 B 到 C → C 接受 → A 会话建立 → A→C 消息"经中继送达"。

### 6.3 真机三机验收

- 三台链式排布（相邻两两可达）：群消息三机可见性、2 跳建会话、消息往返。

## 7. 涉及文件

- 协议：`mesh/protocol/MeshEnvelope.kt`（GroupBody.groupId/displayName）
- 服务：`mesh/service/MeshService.kt`（GROUP 分支、INVITE 中继、toStoredMessage GroupBody 分支、sendGroupMessage、群订阅表、群名学习）
- 存储：`mesh/service/GroupStore.kt`（新，SharedPrefs 持久化）
- 数据：`data/UiModels.kt`（群会话模型）、`data/MeshRepository.kt`（sendGroupMessage/observeGroups）
- UI：`ui/screens/ChatsScreen.kt`（群组分区+创建群）、`ui/screens/ConversationScreen.kt`（群会话+昵称气泡）、`ui/MeshChatViewModel.kt`、`ui/screens/MeshChatHome.kt`（群入口）
- 测试：`test/.../MeshServiceTest.kt`

## 8. 分批实施顺序

1. **v1.1.50（阶段 A）**：协议 GroupBody+groupId → MeshService GROUP 分支 + 群订阅/群名 + GroupStore → Repository/ViewModel → UI（群列表/创建/群会话/昵称气泡）→ 单测 → 模拟器三机验证。
2. **v1.1.51（阶段 B）**：INVITE/INVITE_ACK 中继 → 单测 → 模拟器三机验证。
