# MeshChat 传输加密（E2EE + 群聊对称加密）设计规格

- 日期：2026-08-07
- 状态：**已实现（v1.1.57）**——点对点 TEXT E2EE 强制加密 + 群聊对称加密 + 蓝牙未开拒绝搜索弹窗申请；184/184 单测通过，双包构建
- 版本：v1.1.57

## 1. 背景与目标

当前空中传输明文（`MeshTransportSecurity.CURRENT_MODE = "legacy-plaintext-v1"`），任何蓝牙监听设备（嗅探器）可解析广播/写入帧中的消息内容。用户要求：

1. **点对点消息（TEXT）端到端加密**：仅收发双方可读，中继/监听者只能看到密文。
2. **群聊对称加密**：群消息用群密钥加密，防被动监听破解；不追求成员间不可见（群成员本就可见全部群消息）。
3. **强制加密**：新版本之间消息必须加密；无密钥协商能力（老版本）无法互通点对点消息（用户接受升级过渡期影响）。
4. 路由字段（dstId/ttl/convId/kind）保持明文——中继转发需要，且不泄露消息内容。

## 2. 威胁模型

| 威胁 | 防护 |
|------|------|
| 被动监听（蓝牙嗅探广播/GATT 写） | 消息体 AES-256-GCM 加密；群聊群密钥加密 |
| 中继节点窥探 | 中继只转发不解密（E2EE 语义） |
| 离线密钥窃取（设备丢失） | 本机私钥存 AndroidKeyStore（不可导出），丢失即不可恢复 |
| 消息重放 | AES-GCM 每条消息新 IV + GCM 认证标签 |

**不在范围**：恶意群成员（群密钥共享，成员间可见——群聊定义如此）；中间人（无公钥基础设施，首次握手未认证——BLE 近场物理接触场景，诚实标注此局限）。

## 3. 协议改动

### 3.1 新加密 body（点对点与群聊统一）

```kotlin
@Serializable
@SerialName("SEC")
data class SecBody(
    val cipher: String,   // 内层 body JSON 的 AES-GCM 密文（Base64）
    val iv: String,       // 12B IV（Base64）
    val ctx: String,      // 密钥上下文："p2p"（点对点会话密钥）/"group-<groupId>"（群密钥）
) : EnvelopeBody
```

- 信封 kind 仍为 "TEXT"（群聊 "GROUP"），body 换成 SecBody——**中继零改动**（只看信封，不解析 body 语义）。
- 密文内层 = 原 TextBody / GroupBody 的 JSON 序列化（多态结构不变，解密后按原逻辑处理）。

### 3.2 握手携带公钥（INVITE / INVITE_ACK）

- `TextBody` 增加可选字段 `pubKey: String = ""`（本机 ECDH P-256 公钥 SPKI Base64）。
- INVITE 与 INVITE_ACK 都携带 pubKey；收到对端 pubKey 即派生会话密钥（见 §4.2）。
- 老版本无 pubKey 字段（默认空）→ 判定"不支持加密"。

### 3.3 群密钥分发

- `GroupBody` 增加字段 `groupKey: String = ""`：创建群时创建者生成 32B 群密钥；`createGroup` 的 JOIN 帧携带群密钥（明文——广播域内首次加入时的密钥交换，加入者即群成员；威胁模型：群消息防被动监听，群密钥首传暴露在**创建者→新成员**的单次广播中，属已知局限，后续可改用点对点密钥加密群密钥）。
- 收到带 groupKey 的 JOIN/群消息 → 存群密钥，之后群消息用群密钥加解密。
- 群密钥变更（新成员加入不轮换——MVP 简化，记录局限）。

## 4. 密钥管理

### 4.1 本机密钥对

- 首次启动生成 ECDH P-256 密钥对，私钥存 **AndroidKeyStore**（`meshchat_e2ee_p256`，不可导出），公钥 SPKI Base64 用于握手交换与 UI 指纹显示。
- 纯 JVM 测试：`MeshCrypto` 接口抽象密钥存储（测试用内存实现）。

### 4.2 点对点会话密钥

- 收到对端公钥 → `ECDH(本机私钥, 对端公钥)` → 共享秘密 → **HKDF-SHA256**（salt=固定, info=`"meshchat-e2ee-v1|<shortId>"`）→ 32B 会话密钥。
- 会话密钥按 `peerId → key` 内存缓存（ConcurrentHashMap）+ 持久化（SharedPrefs，加密存储可选——MVP 用明文偏好存派生密钥，泄露风险=已协商设备本地，可接受并记录）。
- 双方各自派生相同密钥（ECDH 对称性）。

### 4.3 群密钥

- `groupId → 32B AES 密钥`，内存 + 持久化（SharedPrefs）。

## 5. 加解密流程

### 5.1 发送 TEXT（强制加密）

```
sendText(dstId):
  key = sessionKey[dstId]
  if key == null → 不发送，UI 提示"对方版本不支持加密"（不落库/标注不可用）
  inner = json(TextBody(text, displayName))
  cipher, iv = AES-GCM(key, inner, aad = "TEXT|dstId")
  落库 SENDING（明文 text 本机可见），信封 body = SecBody(cipher, iv, "p2p")
```

### 5.2 接收 TEXT/SEC

```
handleEnvelope(kind=TEXT, body):
  if body is TextBody（明文，老版本）→ 标注"未加密 · 对方旧版本"后按原流程投递（升级过渡保留可用性）
  if body is SecBody:
    key = ctx 为 p2p → sessionKey[srcId]；group-* → groupKey[groupId]
    if key == null → 标注"加密消息 · 无法解密"（不投递正文，保留占位）
    else 解密 → 内层 TextBody/GroupBody → 原流程（落库/去重/回执/转发）
```

### 5.3 群消息

- 发送：`key = groupKey[groupId]`，无群密钥 → 不发（提示）。
- 接收：SecBody(ctx="group-<id>") → groupKey 解密 → GroupBody。

### 5.4 消息 ID/去重/回执

- 消息 id（envelope.id）/msgId 不变（信封层），去重/回执逻辑零改动。
- 回执（RECEIPT）不含正文，无需加密。

## 6. 强制加密语义（升级影响）

| 场景 | 行为 |
|------|------|
| 双方 v1.1.57+ | 握手交换公钥 → 消息全加密 |
| 发送方新 / 接收方旧 | 发送方无会话密钥 → 拒绝发送并提示；旧版本照常收不到新版本消息（新版本不会发明文） |
| 接收方新 / 发送方旧 | 旧版本发明文 TEXT → 新版本显示"未加密 · 对方旧版本"（过渡期保留可读） |
| 群聊成员混版本 | 群消息 SEC 加密；旧版本成员无法解密群消息（提示"加密消息"） |

## 7. 文件改动清单

- 新增 `mesh/protocol/MeshEnvelope.kt`：`SecBody` + TextBody.pubKey + GroupBody.groupKey
- 新增 `mesh/crypto/MeshCrypto.kt`：ECDH 密钥对生成/共享秘密、HKDF-SHA256、AES-GCM 加解密（纯 JVM 可测）
- 新增 `mesh/crypto/E2eeKeyStore.kt`：本机密钥对/对端公钥/会话密钥/群密钥存储抽象 + Android 实现 + 测试内存实现
- `mesh/service/MeshService.kt`：握手带公钥、密钥派生、sendText 加密、handleEnvelope 解密、群密钥分发
- `data/MeshRepository.kt` / `ui/MeshChatViewModel.kt`：加密能力状态（是否已协商密钥）、发送拦截提示
- `mesh/protocol/MeshTransportSecurity.kt`：更新模式常量 + 能力标志
- UI：聊天页/节点页加密状态标注（锁图标/文案）
- 测试：MeshCryptoTest（ECDH 派生一致/AES 往返/HKDF）、MeshServiceTest（握手派生/加密往返/明文过渡/群密钥）

## 8. 回归清单

1. 双新版本：握手 → 双方派生一致密钥 → 消息加密到达解密显示 → 回执正常
2. 中继：加密消息经中间节点转发到达（中继不解析 body）
3. 老版本明文 TEXT 接收 → "未加密"标注
4. 群聊：创建群带群密钥 → 成员加入收群密钥 → 群消息加密解密
5. 重启：密钥持久化恢复，加密继续工作
6. 消息去重/重发/回执在 SEC body 下不回归
7. 全部 162 存量测试不回归

## 9. 已知局限（诚实标注）

- 无公钥认证（无 PKI/指纹验证 UI）——近场 BLE 假设物理接触可信；后续可加指纹比对。
- 群密钥首传明文（创建者→新成员单次广播）；群成员不轮换。
- 会话密钥派生后持久化为明文 SharedPrefs（设备已获信任假设）。
