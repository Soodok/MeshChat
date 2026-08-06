# MeshChat 群组交流 + 多跳补全 设计规格

- 日期：2026-08-06
- 状态：**阶段 A 已确认并实施（v1.1.50，2026-08-06）**；阶段 B 待实施（v1.1.51）
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
| 群消息确认 | **节流回执 + 有限重发**（修订：原草案"不做"被仿真否决）——成员 30% 概率+0-500ms 随机延迟回执一次；发送方收任一有效回执 → DELIVERED，30s 超时 → "可能未送达"（琥珀）；未确认群消息每 5s 重发 ≤3 次 | 仿真见 §3.9：无回执下全员到达率仅 0-48%（虚假"已送达"）；全回执带宽翻倍且仍只确认"至少一个成员"；节流回执带宽 +50-100% 换来发送方真实感知 |
| 群 ID | 发送方生成（复用 ShortIdGen 风格，8 字符），创建时带群名 | 短（帧预算）+ 全网唯一 |
| 群名传播 | 随群消息/群创建广播携带 groupName，接收方学习 | 无中央目录，靠消息扩散 |
| 群通知 | 群消息弹通知（与点对点一致） | 应急场景需可见 |
| 状态语义（诚实标注） | "已送达" = 至少一个成员确认（**非全员**）；"可能未送达" = 超时无确认 | 群消息做不到低成本全员确认（泛洪丢包），必须诚实标注防虚假送达感 |

### 3.2 协议变更（MeshEnvelope.kt）

```kotlin
@Serializable
@SerialName("GROUP")
data class GroupBody(
    val op: String,                 // "MSG"（消息）/ "JOIN"（创建传播群名，MVP）；"LEAVE" 预留
    val groupId: String,            // 新增：群唯一 ID（8 字符，创建者生成）
    val msgId: String = "",         // 新增（2026-08-06 确认）：逻辑消息 ID = 首次发送的 envelope.id；
                                    // 重发新 envelope 时不变，回执按此匹配（新增字段比"回执按 envelope.id"更可靠——
                                    // 新 id 重发后首帧 id 与重发帧 id 不同，按首帧 id 匹配才能跨帧确认同一逻辑消息）
    val groupName: String? = null,  // 群名（创建时携带，随消息传播）
    val text: String? = null,
    val displayName: String = "",   // 新增：发送者昵称（群聊需区分谁说的，同 TextBody）
) : EnvelopeBody
```

- 兼容：老版本无 GROUP 功能（死代码），加字段零兼容负担；新版本不发旧格式 GROUP。
- `msgId` 语义：首帧 `envelope.id == body.msgId`；重发帧 `envelope.id` 更换、`body.msgId` 不变——**内容指纹去重（§3.3）与群回执（§3.4，`"G$msgId"`）都以 msgId/内容为锚，而非 envelope.id**。

### 3.3 群消息发送（MeshService）

```kotlin
fun sendGroupMessage(groupId: String, text: String) {
    val msgId = UUID.randomUUID().toString()          // 逻辑消息 ID（= 首帧 envelope.id）
    sendGroupMessageWithId(groupId, text, msgId)
}

fun sendGroupMessageWithId(groupId: String, text: String, msgId: String) {
    // envelope: kind="GROUP", dstId=groupId, convId="group-$groupId", id=msgId（首帧）
    // body=GroupBody(op="MSG", groupId, msgId, groupName=本机已知群名, text, displayName)
    // 本地落库（convId="group-$groupId", SENDING——待确认，不直接 DELIVERED，id=msgId）
    // 登记 pendingGroupReceipts["G$msgId"] = PendingGroupMsg(...)   ← 独立队列，键带 "G$" 前缀与点对点隔离
    // 广播（走 route → 泛洪转发，本机不抖动）
    // 重发（2026-08-06 修订 v2，仿真 §3.9.1 铁证）：
    //   每 5s 重发一次，用【新 envelope id】（新 id = 新泛洪，能推进到未收节点；
    //   同 id 重发被节点级 DedupCache 挡住，完全无效——首次泛洪已标记沿途节点，重发帧推不动）
    //   固定重发 ≤3 次、【不依赖确认状态】——确认来自近端成员（<300ms），
    //   依赖它会让重发在远端最需要时永不触发（瞬时丢包场景到达率 0%→88% 靠新 id 重发救活）
    //   30s 总超时仍未确认 → 状态"可能未送达"（琥珀色，诚实标注——回执只能证明至少一个成员收到）
}

// ===== 两层去重各司其职（2026-08-06 确认，msgId 锚修订 2026-08-06 审查）=====
// ① DedupCache（节点级，envelope.id）：防转发环——重发的新 id 必须放行（不在此表命中）；
// ② 内容指纹（应用级，收方本地）：防重复投递——(groupId|srcId|msgId) + 存活期，
//    落库/回执前检查。中继节点【绝不用指纹拦帧】（只转发，不管内容是否重复）。
// 指纹实现：fingerprint = "$groupId|$srcId|$msgId"
//   【锚用 msgId 而非 text（审查 M2 修订）】：重发帧 msgId 不变 = 同一逻辑消息 → 判重复；
//   新消息 msgId 不同 = 合法新消息 → 不误杀（text 作锚会吞掉同群同发送者短时间内连发的相同文本，
//   如应急连发"收到"；且 text 锚无法覆盖重启恢复重发（ts 隔数小时窗口已滑过）导致重复落库）
//   存活期 10 分钟（键唯一=一个 msgId 一条；覆盖重启恢复重发的长间隔；最初 10s 窗口是错的，已修正）
//   时间基准用本机时间（审查 M3 修订）：键唯一后窗口只是"键存活期"，不再需要跨设备 ts 差值——
//   原方案判定用 envelope.ts（发送方时钟）而清理用本机时钟，偏差 >10s 时误删指纹 → 重发帧重复投递
//   存储 ConcurrentHashMap<String, ArrayDeque<Long>>：同指纹窗口内时间戳队列（≤3 条），
//   tick 顺带清理空键（防长期运行内存增长）
```

- 群消息与点对点消息的**重发机制不同**：点对点同 id 重发靠"重复即补回执"特殊逻辑（接收方对 dedup 命中帧补发 RECEIPT）；群消息没有该逻辑，**必须新 id 重发**（仿真铁证）。
- 重发频率低于点对点（群消息重发会全网放大）：5s/≤3 次。
- **UI 内容级去重（重发副作用的必需配套）**：新 id 重发会让已收成员重复收到同内容（仿真 2.3-6 次/运行）→ 群会话落库按内容指纹去重，防止重复气泡。

### 3.4 群消息接收（广播域模型，MeshService handleEnvelope 新增 "GROUP" 分支）

```
kind == "GROUP":
  body = envelope.body as? GroupBody ?: return
  if (body.op == "JOIN") {                      // 创建传播帧（MVP 仅传群名）
      learnGroupName(body.groupId, body.groupName)
  } else if (body.groupId in joinedGroups) {    // 已订阅 → 显式双动作（落库 + 回执）
      // ⚠️ 不走 ForwardingDecision：dstId=groupId≠本机会判 Forward（只转发不落库）——2026-08-06 确认的投递路径修正
      if (!isGroupDup(envelope, body)) {        // 内容指纹去重（§3.3）：新 id 重发/环路重复不重复落库
          learnGroupName(body.groupId, body.groupName)  // 群名学习
          markSeen(envelope.srcId, body.displayName)    // 昵称学习（同 TEXT，供气泡显示/通知）
          store.insertMessage(envelope.toStoredMessage())  // convId = 信封的 group-<groupId>（不是 conv-$srcId）
          onIncomingMessage(...)                // 群通知（与点对点一致，打开 group-<groupId>）
          maybeSendGroupReceipt(envelope, body) // 节流回执：30% 概率 + 0-500ms 随机延迟（重发的新 id 帧不回执——内容指纹识别）
      }
  }
  if (envelope.ttl - 1 > 0) {                   // 无条件转发（已订阅也转发：订阅者即中继，泛洪才能延伸到所有成员）
      route(envelope, jitter = true)            // 复用 ForwardingDecision（DedupCache 防环 + TTL 递减 + 抖动）
  }

// ===== 群回执（2026-08-06 确认：独立队列 + "G$" 前缀命名空间）=====
maybeSendGroupReceipt(envelope, body):
  if (random.nextDouble() >= 0.30) return                     // 30% 节流（仿真：带宽 +50-100% 换真实感知）
  scope.launch { delay(random 0-500ms)
      if (!isGroupDup(envelope, body)) return@launch           // 内容指纹识别：已收成员对新 id 重发不回执
      dedup.mark("receipt-G$msgId")                            // 回执去重键与点对点（receipt-$id）隔离
      broadcast RECEIPT {"id":"G$msgId","srcId":本机,"dstId":发送方}  // 泛洪回传，复用 "receipt-$id" 去重防环
  }

handleReceipt: id.startsWith("G$") → pendingGroupReceipts.remove(id) 命中 → 该 msgId 标 DELIVERED
              （"已送达" = 至少一个成员确认，非全员——诚实标注）
```

- 回执泛洪复用现有 RECEIPT 机制（`"receipt-$id"` 去重 + 中间节点转发一次），发送方 `pendingGroupReceipts` 命中即 DELIVERED。
- 节流必要性（仿真）：全成员回执带宽放大 ~2x；30% 概率+随机延迟降至 ~1.5-2x 且确认延迟仅 +100-150ms。
- **群回执与点对点回执隔离**：点对点 `pendingReceipts` 键 = envelope.id（UUID）；群回执 id = `"G$msgId"`（"G$" 前缀 + 逻辑 msgId）——`handleReceipt` 按前缀路由到独立群队列，互不干扰；`handleFrame` RECEIPT 分支的"发送方命中"判断同时查两个队列。
- `route` 的 Deliver 分支：`toStoredMessage` 增加 GroupBody 分支（`text = body.text`，convId 用信封的 `group-<groupId>`，**不是** `conv-$srcId`——群会话键与点对点命名空间隔离）。需区分：GroupBody 的 convId 在 `MeshEnvelope.toStoredMessage` 中按 `convId` 字段直用，而非重写为 `conv-$srcId`。
- 群名学习：`groupNames: Map<groupId, name>` + 持久化（GroupStore.loadNames/saveNames）。
- 已订阅判定：`joinedGroups: Set<String>`（SharedPreferences 持久化，同 SessionStore 模式）。

### 3.5 存储（GroupStore）

- SharedPrefs `meshchat_groups`：`joined: Set<String>`、`names: Map<groupId, name>`。
- 接口仿 SessionStore：`loadJoined()/saveJoined()/loadNames()/saveNames()`。

### 3.6 UI

- **群列表**：聊天页（ChatsScreen）顶部新增"群组"分区——已订阅群（群名 + 群 ID，可最小化）；"创建群"按钮 → 输入群名 → 生成 groupId + 本地订阅 + 广播群创建帧（带群名，可选）。
- **加入群（2026-08-06 审查 M4 修复）**：群组分区新增"加入群"按钮 → 输入创建者告知的 8 字符群 ID → 本地订阅（持久化）+ 进入群会话。JOIN 帧仅传播群名，不自动订阅（防陌生群误订阅）。非创建者据此加入已有群。
- **群会话页**：复用 ConversationScreen，`conversationTarget` 支持群（target=groupId，会话键 group-<groupId>）；发送走 `sendGroupMessage`；**气泡显示发送者昵称**（群聊必需，点对点不显示）。
- **导航**：MeshChatHome 增加群入口路由（与 profileDetail 类似的 detail 机制）。
- **创建群后立即进入会话（2026-08-06 审查 M1 修复）**：ViewModel 以同步集合登记已确认群目标 ID（`_groupTargetIds`），消息流/发送的群判定不再依赖异步 `groups` 合成流——消除"创建群后消息流误用 conv-<groupId>"的时序竞态。

### 3.7 边界与错误处理

| 场景 | 行为 |
|------|------|
| 群消息风暴 | TTL 8 + DedupCache + 50-250ms 抖动（复用 TEXT 中继，全网每节点最多转发一次） |
| 重复群消息 | dedup 按信封 id 收敛 |
| 回执风暴 | 30% 概率 + 0-500ms 随机延迟 + RECEIPT "receipt-$id" 全网去重一次（三层节流） |
| 群名冲突/更新 | 后到覆盖（简单，无校验） |
| 未订阅节点收到群消息 | 只转发不落库不通知 |
| 群消息 TTL 耗尽 | 转发停止，Drop |
| 发送方未订阅自己创建的群 | 创建即订阅（本地） |
| 群消息超时无确认 | 状态"可能未送达"（琥珀色），30s 后不再重发 |

### 3.8 阶段 A 单测（MeshServiceTest 新增）

1. `group message delivered to subscriber and stored in group conversation`：已订阅节点收到 GROUP → 落库 convId=group-x、text 正确。
2. `group message forwarded by non subscriber without storing`：未订阅节点转发、insertMessage 不被调用。
3. `group body round trips groupId and displayName`：编解码。
4. `group name learned from group message`：收到带 groupName 的 GROUP → groups 表更新。
5. `group message ttl exhausted not forwarded`：TTL≤1 不扩散。
6. `group receipt throttled and confirms sender`：成员收到群消息 → 节流回执；发送方收任一有效回执 → 状态 DELIVERED。
7. `group message resent with new id until timeout`：群消息固定 5s 新 id 重发 ≤3 次（不依赖确认）、30s 超时标"可能未送达"；已收成员对新 id 重发**不回执**（msgId 指纹识别）。
8. `same text twice with different msgId both delivered`（审查 M2 回归）：同文本不同 msgId 都落库；同 msgId 重发不重复落库。
9. `group conversations not restored as known peers`（审查 S3 回归）：群会话键不反推为对端节点。
10. 既有 147 例全量回归。

### 3.9 极端网络仿真（决策依据，已入库可复现）

`app/src/test/java/com/meshchat/app/mesh/sim/GroupRelaySimulationTest.kt`——离散事件仿真（链式拓扑、一跳 100ms、TTL 8、转发抖动 50-250ms、每跳独立丢包、40 次蒙特卡洛）：

| 场景 | 全员到达率 | NONE 发送方感知 | 节流回执确认 | 带宽代价 |
|------|-----------|----------------|-------------|---------|
| 5 节点/20% 丢包 | 48% | 立即"已送达"（假） | ~440ms | 1.68x |
| 5 节点/40% 丢包 | 13% | 立即"已送达"（假） | ~194ms | 1.47x |
| 8 节点/20% 丢包 | 28% | 立即"已送达"（假） | ~434ms | 1.86x |
| 8 节点/40% 丢包 | 20% | 立即"已送达"（假） | ~185ms | 1.52x |
| 12 节点/30% 丢包 | 0% | 立即"已送达"（假） | ~341ms | 2.04x |

**结论**：① 无回执 = 虚假确认（到达率 0-48% 却显示"已送达"）→ **否决"不加回执"**；② 全回执只确认"至少一个成员收到"且带宽翻倍 → 全员确认在泛洪下不可行；③ **节流回执（30% 概率+随机延迟）是折中**：带宽 +50-100%、确认延迟 190-440ms、发送方有真实感知；④ 有限重发（5s×3）把"新泛洪机会"给丢包窗口后的成员，直接提升全员到达率；⑤ 状态诚实标注："已送达"= 至少一个成员，"可能未送达"= 超时。

#### 3.9.1 重发机制仿真（修订 v2 依据，同文件第二测试方法）

60 次蒙特卡洛，三种重发姿势对照：

| 场景 | 无重发 | 同 id 重发×3 | 新 id 重发×3 |
|------|--------|-------------|-------------|
| 8 节点/持续 40% 到达率 | 5% | 5%（**无效**） | 8%（3.4x 帧换 1.6x 到达） |
| 8 节点/瞬时 60%→5%（遮挡恢复） | 0% | 0%（**无效**） | **88%**（重发救活） |
| 12 节点/持续 30% | 0% | 0%（无效） | 0%（超长链救不了） |

**铁证**：
- **同 id 重发完全无效**（帧数、到达率与无重发完全一致）——节点级去重（DedupCache "见过即丢弃"）在首次泛洪已标记沿途节点，同 id 重发帧到不了未收节点（必经之路全被去重挡住）。点对点重发同 id 有效是因为"重复即补回执"特殊逻辑，群消息没有。
- **必须新 id 重发**：新 envelope id = 新泛洪，能推进到未收节点；**瞬时丢包（前 8s 60% 后恢复）到达率 0%→88%**——遮挡/移动是最常见真实场景，重发价值巨大；持续恶劣链路性价比低（帧 3.4x 换到达 1.6x）但无害；超长链（12 节点 11 跳）重发 3 次救不了（到达率指数衰减，物理限制，接受）。
- **确认来自近端（<300ms）→ 重发必须固定执行、不依赖确认**：若"确认即停"，近端快速确认会杀掉重发，瞬时场景到达率回到 0%（仿真验证）。
- **新 id 重发的副作用**：已收成员重复接收（2.3-6 次/运行）→ **UI 内容级去重**必需（§3.3）。
- **确认延迟**：新 id 重发下确认 1.0-7.9s（近端首轮漏收时靠重发补），无重发 224-285ms（但那是"近端确认"不是"全员到达"）。

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
