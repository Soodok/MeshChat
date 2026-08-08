# MeshChat 频道系统设计规格（v1.1.66 目标）

> 日期：2026-08-08
> 状态：已确认（用户批准方案 1：广播指纹 + 三层隔离）
> 关联：v1.1.53 发现模式（DiscoveryMode）、v1.1.57 E2EE、v1.1.64 拉黑

## 1. 背景与目标

用户需求："新增一个频道系统——公共频道和可自定义私人频道，让一些人在私人频道内进行连接，防止被公共搜索"。

头脑风暴收敛后的需求定义（用户逐项确认）：

1. **单频道制**：本机一次只在一个频道（公共 或 某个私人频道），像对讲机/收音机。切换频道 = 切换整个可见范围。
2. **自定义频道名**：在 Mesh 界面主动选择，输入频道名进入私人频道；公共频道为默认态。
3. **仅频道名凭据**：无密码层。频道指纹 = 单向哈希（频道名）。
4. **频道隔离**：选择私人频道后，本机**只能被同频道节点发现、只能与同频道节点连接，不能跨频道**。
5. **频道名不可反推**：广播包不能携带明文频道名，嗅探者无法依据包内容反推频道名。
6. **旧会话处理**：切换频道后隔离但保留记录——Mesh 页只显示同频道节点，旧会话消息发送被拒绝（提示"对方不在当前频道"），切回原频道恢复；聊天记录保留不删。

核心目标：**发现层 + 连接层 + 发送层三层隔离**，正常使用本 App 的用户之间，跨频道节点互不可见、互不可连、消息互不可达。

## 2. 非目标（明确不做，YAGNI）

- **不做多频道订阅**（一次仅一个频道）。
- **不做频道密码/访问控制**：凭据就是频道名，知道频道名即可进入（用户确认）。
- **不做频道内独立加密**：频道内消息沿用现有 E2EE（v1.1.57），频道隔离是发现/连接/发送层，不重复加密。
- **不防恶意改装版 App**：BLE 广播物理开放，改装客户端仍能接收广播、连接 GATT。应用层隔离的固有局限，接受（威胁模型见 §9）。
- **不做频道列表浏览/频道搜索**：没有"发现频道"机制，频道靠成员之间口头/外部约定频道名。
- **不改路由/存储/身份/消息回执**：只动广播载荷 + 发现过滤 + 连接/发送校验。

## 3. 核心概念

### 3.1 频道（Channel）

- **公共频道（public）**：默认态。本机不广播频道指纹（指纹 = 0），发现层不过滤（现状行为）。
- **私人频道（private）**：本机配置一个频道名（非空字符串），广播携带频道指纹，发现层按指纹过滤。

### 3.2 频道指纹（Channel Fingerprint）

- 派生：`fingerprint = SHA-256("meshchat-channel-v1:" + 频道名)`，取前 **6 字节**（48 位）。
- 存储/比较类型：`Long`（0 ~ 2^48-1，高位 10 字节补 0）；**0 = 公共频道/未知/老版本设备**。
- 广播编码：6 字节二进制（低 6 字节），与短 ID 广播分离（见 §4.1）。
- 为什么 6 字节截断：
  - **防反推**：单向哈希不可逆；截断后字典攻击不可靠（多个频道名共享同一 48 位前缀的概率 ~2^-48，攻击者无法确认命中的是哪个名字）。
  - **包开销小**：6B 追加到广播包，仍在 BLE 31B 预算内。
- 碰撞风险：两个不同频道名指纹相同 → 互相可见，概率 ~2^-48，接受。

## 4. 协议与传输层改动

### 4.1 广播载荷（BleTransport）

现状（v1.1.x）：

```kotlin
// 广播 Service Data：主 Service Data 携带本机短 ID（4 字符 ASCII）
.addServiceData(ParcelUuid(serviceUuid), advertiseShortId.toByteArray())   // 4B
// 扫描响应携带送达确认键（独立 ACK_UUID，24B）
```

改动：

- **主 Service Data 保持短 ID 不变**（4B），保证老版本解析兼容（老版本只读主 Service Data 的短 ID，不受影响）。
- **新增独立 Service Data：`CHANNEL_UUID` 携带 6 字节频道指纹**（仿 ACK_UUID 的私有 UUID 风格）。本机在公共频道（指纹 = 0）时不携带该 Service Data。
- 广播包预算：Service UUID(2B) + 主 Service Data(2B+4B) + 频道 Service Data(2B+6B) = 16B ≤ 31B，充裕。

### 4.2 扫描解析与过滤（BleTransport.onScanResult）

改动 `onScanResult`：

1. 解析主 Service Data → 短 ID（现状逻辑不变）。
2. 解析 `CHANNEL_UUID` → 6 字节 → 组装 `Long`；缺失（老版本设备/公共节点）→ 0。
3. **频道过滤**：若解析指纹 `!= 本机当前频道指纹` → **不 emit 到 foundPeers、不 connectTo**（跨频道节点在传输层就不可见、不建立 GATT 连接）。

   注：现状 `connectTo(device)` 对所有扫描到的设备无条件自动连接；本改动使跨频道节点连自动连接都不会触发。

### 4.3 传输接口（MeshTransport / MeshPeerInfo / InMemoryTransport）

- `MeshPeerInfo` 新增字段：`channelFingerprint: Long = 0`（0 = 公共/未知/老版本）。
- `MeshTransport` 新增默认方法：`fun setChannel(fingerprint: Long) {}`（BleTransport 覆写：更新 volatile 指纹 + 重启广播生效；InMemoryTransport 覆写：记录断言位供测试）。
- InMemoryTransport 测试替身：foundPeers emit 时携带 channelFingerprint；可注入频道过滤行为（测试用）。

## 5. 服务层改动（MeshService）

### 5.1 频道状态

- 新增 `channelName: StateFlow<String?>`（null = 公共频道）+ `private var channelFingerprint: Long = 0`（volatile）。
- `fun setChannel(name: String?)`：
  1. `name == null` → 指纹 = 0（公共）；`name != null` → 指纹 = `ChannelFingerprint.of(name)`。
  2. `transport.setChannel(指纹)` + `transport.refreshAdvertising()`（换指纹广播）。
  3. **清空 `peerEntries`**（旧频道节点残留剔除）→ `refreshPeers()` 立即输出空列表，Mesh 页清空重新发现。
  4. 持久化频道名（委托注入的 provider，见 §6）。
  5. `_sessions`/历史消息**不清**（聊天记录保留）。

- `ChannelFingerprint` 工具（新文件，纯 JVM 可测）：
  `object ChannelFingerprint { fun of(name: String): Long = SHA-256("meshchat-channel-v1:$name") 前 6 字节组装 Long }`

### 5.2 发送层校验（sendText）

在现有 E2EE 会话密钥校验（v1.1.57）**之前**追加（频道隔离是比加密更外层的语义）：

```kotlin
// 频道校验：非自环目标必须位于当前频道（peerEntries 已按频道过滤；切换频道后旧节点不在表内）
if (!isSelfLoop) {
    val peer = peerEntries[dstId]?.info
    if (peer == null || peer.channelFingerprint != channelFingerprint) {
        Log.w(TAG, "channel: dst $dstId not in current channel, refusing send")
        return false   // 复用 sendText 的 Boolean 返回值，UI 区分提示
    }
}
// …… 其后才是现有会话密钥（E2EE）校验与加密发送逻辑
```

- 自环（conv-ME）不校验。
- 群消息（sendGroupMessage）不校验（群消息是独立泛洪域，与频道正交；v1.1.66 群消息不按频道隔离——见 §8 边界）。

### 5.3 接收层校验（handleEnvelope 入口）

在拉黑拦截（v1.1.64）之后追加：

```kotlin
// 频道校验：已记录节点（peerEntries 内）指纹不匹配当前频道 → 丢弃（防御残留连接/改装连入 GATT）
val known = peerEntries[envelope.srcId]
if (channelFingerprint != 0L && known != null && known.info.channelFingerprint != channelFingerprint) {
    return  // drop
}
```

- 仅对**已记录节点**校验（正常流程下发现层已过滤，未记录节点通常也不可达）。
- 公共频道（指纹 0）不拦截（保持现状行为，兼容老版本无指纹节点）。
- 已拉黑节点拦截优先级高于频道校验（先拉黑后频道，均为 drop，无冲突）。

### 5.4 与发现模式（DiscoveryMode）的交互

- 频道切换与 DiscoveryMode **正交**：
  - NORMAL：换指纹广播 + 按新频道扫描过滤，立即生效。
  - CLOSED（搜索停）：广播/扫描均停，切换只更新状态与持久化，恢复 NORMAL 后按新频道发现。
  - SILENT（只停广播）：扫描按当前频道过滤照常；广播恢复后携带新指纹。

## 6. 持久化（MeshChatApplication）

- `meshchat_settings` 新增键：`channel_name`（String?，null = 公共）。
- `applyChannel()`：启动时（onCreate，先于/随 applyAutoDiscovery）恢复频道——读偏好 → `service.setChannel(name)`（幂等）。
- ViewModel `setChannel(name)` 同步写偏好（仿 v1.1.64 silentMode 模式）。
- `applyAutoDiscovery` 不涉及频道（频道是独立维度，正交恢复）。

## 7. UI 改动（Mesh 页）

### 7.1 频道选择器

Mesh 页新增「频道」行（位置：拓扑图下方、「附近节点」标题上方，仿 v1.1.54 静默开关的置顶显眼风格）：

- 显示：`频道 · 公共` / `频道 · <频道名>`（私人频道名用 Cyan/MeshGreen 强调；公共用 TextSecondary）。
- 点击 → 对话框：
  - RadioButton：「公共频道」（恢复默认，全部节点可见）
  - 输入框：自定义频道名（非空）→ 确认进入私人频道
- 切换后：Toast「已切换至频道 <名>」/「已切换至公共频道」；Mesh 页节点清空并按新频道重新发现（服务层驱动，UI 自然刷新）。

### 7.2 发送被拒提示

- ViewModel `sendText` 返回 false 时区分原因：
  - 无会话密钥（E2EE）→ 现有「对方未启用加密」Toast。
  - **对方不在当前频道** → 新 Toast「对方不在当前频道，无法发送」。
- 会话页/对话列表不隐藏（旧会话保留显示，符合"隔离但保留记录"）；仅在发送时被拒。

### 7.3 节点显示

- Mesh 页节点不显示每节点频道标记（YAGNI）——进入私人频道后可见节点全部同频道，公共频道无需标记。

## 8. 边界与例外

- **群消息**：不按频道隔离（群消息是独立泛洪域，群成员可能分布在多个频道）。本版范围外，规格记录。
- **文件传输**：走现有会话/连接（sendFile 基于会话），频道校验由发送入口（sendText 同级）或现有连接过滤间接保证；不单独加文件频道校验。
- **老版本设备**：不广播 CHANNEL_UUID → 指纹 0：
  - 公共频道本机：可见老版本节点（0 匹配 0），老版本节点可见本机。✓ 兼容
  - 私人频道本机：看不到老版本节点（0 != 指纹），老版本节点也看不到本机（本机广播带指纹，老版本不解析但主 Service Data 短 ID 正常——**注意**：老版本解析主 Service Data 只看短 ID，不受新增 Service Data 影响，仍能解析出本机短 ID；但老版本无频道概念，会显示本机为普通节点，属预期行为，接受）。
- **会话密钥/握手**：频道切换不清理会话密钥（E2EE 密钥独立于频道）；切回原频道后旧会话可直接继续（节点重新发现后握手无需重做——会话密钥仍在）。
- **已拉黑节点**：拉黑拦截优先，与频道无关。

## 9. 威胁模型与局限

- **防谁**：防止**正常使用本 App 的用户**之间的跨频道接触——公共频道的用户看不到私人频道节点、无法连接、无法发消息；私人频道 A 的成员看不到私人频道 B 的节点。
- **不防谁**：恶意改装版客户端（可改解析逻辑强制显示所有广播、可绕过发送校验直接构造帧）。BLE 广播是物理开放的，任何客户端都能收到广播包；本设计保证的是"官方 App 的频道纪律"，不是密码学访问控制。频道名本身是共享秘密，知道频道名即可进入。
- **频道名保护**：广播只携带 6 字节截断哈希，无明文；嗅探者无法直接读出频道名，字典攻击因截断不可靠。

## 10. 测试计划

新增测试（沿用现有 JVM 单测体系，`gradlew testDebugUnitTest`）：

1. **ChannelFingerprintTest**（新文件）：
   - 同一频道名指纹确定且稳定；
   - 不同频道名指纹不同（抽样断言）；
   - 指纹为 6 字节截断（< 2^48，> 0）；
   - 公共频道指纹 = 0（由 setChannel(null) 语义覆盖）。
2. **MeshServiceTest 频道相关**：
   - `setChannel(null)` 公共 ↔ `setChannel("x")` 私人：`channelName` StateFlow 与指纹同步正确；
   - 切换频道后 `peerEntries` 清空、peers 流输出清空（emitPeer 后切频道 → 节点消失）；
   - `sendText` 跨频道拒绝：目标节点指纹 != 当前频道 → return false；同频道（指纹匹配）→ 发送成功；
   - `handleEnvelope` 已记录跨频道节点帧被丢弃（不落库不通知）。
3. **InMemoryTransport**：`setChannel` 断言位 + foundPeers 携带 channelFingerprint 字段。

存量测试适配检查：`MeshPeerInfo` 新增字段有默认值（0），所有既有构造零改动；`sendText` 新增频道校验——**存量 sendText 测试目标节点均需 seed 到 peerEntries 且指纹匹配**（0 == 0，公共频道下测试默认全过，无需改动；私人频道新测试显式设置指纹）。

## 11. 版本与交付

- versionCode 127 → **128** / versionName "1.1.65" → **"1.1.66"**。
- 交付物：`MeshChat-v1.1.66-debug.apk` / `MeshChat-v1.1.66-release.apk`（工程根目录）。
- 涉及文件预估：
  - 新增：`mesh/channel/ChannelFingerprint.kt`（或并入 MeshCrypto 风格独立文件）、`app/src/test/.../mesh/channel/ChannelFingerprintTest.kt`
  - 修改：`mesh/transport/BleTransport.kt`（CHANNEL_UUID 广播/解析/过滤/connectTo 过滤/setChannel）、`mesh/transport/MeshTransport.kt`（MeshPeerInfo.channelFingerprint + setChannel 默认方法）、`mesh/transport/InMemoryTransport.kt`（断言位+字段）、`mesh/service/MeshService.kt`（channelName StateFlow/setChannel/清表/sendText 校验/handleEnvelope 校验）、`MeshChatApplication.kt`（channel_name 偏好 + applyChannel）、`data/MeshRepository.kt` + `ui/MeshChatViewModel.kt`（channelName/setChannel + sendRejected 原因区分）、`ui/MeshChatApp.kt` + `ui/screens/MeshChatHome.kt` + `ui/screens/MeshScreen.kt`（频道行/对话框/Toast）、`MeshServiceTest.kt`、`app/build.gradle.kts`、`AI_CONTEXT.md`
