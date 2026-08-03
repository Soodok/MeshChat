# MeshChat 后端框架设计（设备内嵌去中心化）

> 日期：2026-08-03 ｜ 状态：设计已确认，待实现计划
> 工程：`E:\MeshChat Project` ｜ 关联文档：`README.md`、`AI_CONTEXT.md`

## 1. 背景与目标

MeshChat 是面向**无公网/弱网极端环境**的近场安全通信应用。本设计定义其**后端服务框架**——运行于每个 Android 节点的设备内嵌去中心化后端，节点互为中继，无中心服务器。

- **运行形态**：方案 A，设备内嵌去中心化（每节点既是对等端也是转发节点）
- **通讯载体**：蓝牙（BLE）优先，WiFi 后续以同一传输抽象接入
- **功能范围**：单聊文本 + 送达回执、群聊、文件/图片传输（不含语音）
- **网络规模**：单 Mesh 网络 ≤100 节点，存储-转发多跳接力网络
- **加密强度**：演示级（占位接口，真实密码学后续接入，不改变帧/信封结构）
- **身份模型**：短 ID + 显示名

## 2. 需求决策记录

| 决策项 | 结论 | 依据 |
|---|---|---|
| 后端形态 | 设备内嵌去中心化 | 无网极端环境定位，节点自组织 |
| 功能范围 | 单聊+回执 / 群聊 / 文件传输 | 用户确认（不含语音） |
| 网络规模 | ≤100 节点 | 多跳接力，非全互联（BLE 连接并发上限约束） |
| 加密 | 演示级占位 | 本期打通框架，密码学后续接入 |
| 身份 | 短 ID + 显示名 | 寻址简单，指纹字段预留 |

## 3. 第 1 节：模块架构与消息协议（已确认）

### 3.1 模块架构（单模块包分层）

沿用现有单 `app` 模块，后端框架落位包结构：

```
com.meshchat.app.mesh/
├── protocol/   # 协议层：帧格式、消息信封、类型定义、序列化
├── transport/  # 传输层：BLE 发现、连接管理、帧收发（本期实现；WiFi 后续按同接口扩展）
├── routing/    # 路由层：转发决策、去重表、TTL 管理、待转发队列
├── identity/   # 身份层：本地节点 ID/显示名、节点注册表、占位密钥
├── storage/    # 持久化层：Room 实体与 DAO
└── service/    # 服务层：MeshService（应用侧门面，供 ViewModel 调用）
```

分层原则：每层只依赖下层；`transport` 向上暴露传输载体抽象接口 `MeshTransport`，本期由 `BleTransport` 实现，未来 `WifiDirectTransport` 按同一接口接入，路由/协议层零改动。

### 3.2 消息协议

BLE 物理通道传输的最小单元为**帧（MeshFrame）**：

```
MeshFrame（二进制头部 + 载荷）
┌────────┬────────┬────────────────┐
│ type   │ length │ payload        │
│ 1B     │ 2B     │ N B            │
└────────┴────────┴────────────────┘
```

帧类型（`FrameType`）：`HELLO`（握手/邻居通告）、`DATA`（消息接力）、`ACK`（逐跳确认）、`RECEIPT`（端到端回执）、`PING`（存活探测）。

`DATA` 帧承载**统一消息信封（MeshEnvelope）**，演示阶段以 JSON 序列化（便于抓包调试，后续可换二进制）：

```json
{
  "id": "UUID-16B",          // 全局唯一消息 ID（去重与回执依据）
  "kind": "TEXT|FILE|GROUP", // 消息类别
  "srcId": "短ID",           // 源节点
  "dstId": "短ID",           // 目标节点（单聊）或群 ID（群聊）
  "convId": "会话ID",        // 会话标识
  "ttl": 8,                  // 剩余跳数（由 routing 递减）
  "ts": "epoch-ms",
  "body": { ... }            // 载荷
}
```

三类载荷：
- `TEXT`：`{ text, replyTo? }`
- `FILE`：`{ fileName, mime, size, totalChunks, chunkIndex, chunkData(Base64) }`——文件分片接力，每片独立信封，目标端重组。**分片默认 20KB/片**（载荷经 BLE 信道时由帧层再切分为物理包，与文件分片正交）。
- `GROUP`：`{ op: JOIN|LEAVE|MSG, groupName?, text? }`——群成员管理与群消息共用；**成员变更经 GROUP 帧在网络中尽力传播，各节点本地最终一致（本期无强一致约束）**。

**演示级加密占位**：信封预留 `enc` 字段与可插拔 `Cipher` 接口，本期透传明文；接口契约已定，后续接真实加密不改变帧/信封结构。

## 4. 第 2 节：传输与路由

### 4.1 发现与邻居维护

- **广播**：节点以 BLE 广播通告自身，广播载荷包含服务标识前缀与短 ID（如 `MESHCHAT:<shortId>`），并周期性刷新。
- **扫描**：后台持续扫描邻近节点，按前缀过滤；发现新节点即纳入邻居表。
- **邻居表**：记录 `peerId / 显示名 / RSSI / 最近心跳时间 / 连接状态`；以 `PING` 帧周期探测存活，超时剔除。

### 4.2 连接模型（局部星型）

- 每节点同时承担 **GATT Server（Peripheral）** 与 **GATT Client（Central）** 双角色：对外暴露 `MeshChatService` 供邻近节点连入，同时主动连接邻近节点。
- **连接预算**：活跃连接设上限（6-8 条），超出按 LRU 淘汰——应对 BLE 并发连接硬限制，亦是「多跳接力」拓扑存在的根本原因。

### 4.3 存储-转发接力

- **发送**：应用消息 → 生成信封（`ttl=8`）→ 写入持久化 `outbox` → 推送给邻近节点。
- **接收与转发**：收到 `DATA` 帧 → **去重检查**（消息 ID 缓存，LRU 容量 512）→ 判定目标：
  - `dstId` 为本节点 → 投递应用层 + 回发端到端 `RECEIPT`；
  - 否则 `ttl-1 > 0` → 写入 `outbox` 待转发队列 → 转发给除来源外的其他邻近节点（**回环防护**）。
- **持久化接力**：`outbox` 落盘，进程被杀/重启后继续转发，应对拓扑变化。

### 4.4 回执语义（双层次）

- **逐跳 `ACK`**：每跳收到帧立即回确认——链路层可靠性，保证接力不丢。
- **端到端 `RECEIPT`**：目标节点确认投递后沿网络回传——仅最终成功才更新 UI「已送达」状态。
- **重试策略**：`outbox` 条目在 TTL 窗口内定时重试（指数退避），TTL 耗尽标记失败。

## 5. 第 3 节：持久化（Room + SQLite）

| 表 | 关键字段 |
|---|---|
| `conversations` | id、类型(SINGLE/GROUP)、对方/群名、最后消息摘要、未读数 |
| `messages` | id、convId、kind、text/fileMeta、srcId、dstId、status、ts |
| `outbox` | envelope、nextHop、attempts、expireAt（待转发与重试队列） |
| `peers` | shortId、displayName、lastSeen、hops（已知节点注册表） |
| `groups` | id、name、ownerId、members（群成员清单） |

## 6. 第 4 节：前端对接契约

定义 `MeshRepository` 接口供 ViewModel 消费，现有 UI 零改动接入：

```kotlin
interface MeshRepository {
    fun observeConversations(): Flow<List<ConversationPreview>>
    fun observeMessages(convId: String): Flow<List<Message>>
    fun sendText(convId: String, text: String): Result<Message>
    fun sendFile(convId: String, uri: Uri): Result<Unit>
    fun createGroup(name: String, memberIds: List<String>): Result<Group>
    fun observePeers(): Flow<List<MeshPeer>>
}
```

- 现状：演示数据经假实现注入 ViewModel。
- 本期：替换为 `MeshRepositoryImpl`（基于 `MeshService` + Room），ViewModel 不感知来源变化。
- 前端「已连接/2 跳/等待路由」等状态字段由后端数据源真实驱动。

## 7. 第 5 节：错误处理与测试策略

### 7.1 错误处理

- 传输失败 → outbox 重试（指数退避）；TTL 耗尽 → 消息标记失败，UI 显示「未送达」。
- 文件分片缺失 → 目标端重组校验，本期整体超时丢弃，重传机制后续增强。
- BLE 不可用/权限拒绝 → 明确错误提示，状态层降级（不崩溃）。
- 去重表溢出 → LRU 淘汰。

### 7.2 测试策略

- **JVM 单测**（`protocol`/`routing` 纯逻辑）：信封序列化往返、去重表、TTL 递减、outbox 重试策略。
- **集成冒烟**：单机自环（发送给自己）验证消息闭环；双真机 BLE 接力联调。
- 验证命令：`gradlew test`（单测）+ `gradlew assembleDebug`（构建）。

## 8. 开放问题

- 加密模块的具体密码学方案（后续阶段）
- WiFi 直连接入的载体细节（后续阶段）
