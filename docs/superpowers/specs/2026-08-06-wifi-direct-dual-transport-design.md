# MeshChat Wi-Fi Direct 双通道增强 设计规格

- 日期：2026-08-06
- 状态：**待审查**（brainstorming 定稿后进入 writing-plans）
- 目标版本：P1+P2（传输层）= v1.1.51；P3（双通道+文件+UI）= v1.1.52（用户定稿三计划，见 §8）
- 依赖：v1.1.50 群消息 MVP（156 测试全绿）；v1.1.0 多跳中继；BLE 全链路（真机验证）
- 前置参考：RFCOMM 载体 v0.13.x 已停用代码（RfcommFraming 可复用）、RfcommChannel 契约（MeshService.kt）、MeshTransport 抽象

## 1. 背景与目标

MeshChat 当前唯一物理载体是 BLE：吞吐实测峰值 67KB/s（文件），多跳泛洪 mesh（发现/心跳/消息/群消息）已实装且真机验证。用户目标：**用 Wi-Fi Direct 扩展 mesh 能力与极端环境通讯**。

Wi-Fi Direct 平台事实（已验证）：
- 数据通讯必须建立 P2P group（GO + Client 星型）；发现阶段（discoverPeers / DnsSd 服务发现）**无连接**可携带少量身份信息。
- 实测吞吐 20-50MB/s（5GHz），比 BLE 快 300-750 倍；范围 30-200m（BLE 10-30m）。
- **无配对弹窗**（相对 RFCOMM 停用根因的关键优势）。
- 限制：单设备同一时刻只能在一个 P2P group；P2P 期间可能抢占传统 WiFi 天线；功耗高于 BLE。
- Android 没有类似 BLE 广播的"无连接数据承载"（802.11 管理帧不承载应用数据）。

本规格采用**用户定稿方案**：BLE mesh 常开不变，Wi-Fi Direct 作为可选增强层——开启后自动与可达设备建连形成星域，组内设备**双通道**（消息双写、文件优先 Wi-Fi Direct），组外保持纯 BLE；BLE 断连时**强制 Wi-Fi Direct 保持连接**。

## 2. 现状盘点（已读源码确认）

| 项 | 现状 | 与本规格的关系 |
|---|---|---|
| `MeshTransport` 抽象 | 10 个方法（broadcast/sendTo/writeUnreliable/currentMtu/isConnectedTo/setTxPowerLevel/suspendDiscovery/…） | WifiDirectTransport 与 CompositeTransport 都实现它；MeshService 依赖抽象，**可整体替换注入** |
| `MeshService` | 构造注入 `transport: MeshTransport`；所有帧经 `transport.broadcast/sendTo`；`connectRfcomm()` 会话建立时尝试建高速通道（当前 rfcomm 参数默认 null） | 替换注入 CompositeTransport 即可，服务层零改动（或极小） |
| `RfcommFraming` | 4 字节长度前缀分帧，纯 JVM，3 单测 | **直接复用**于 Wi-Fi Direct TCP 通道 |
| `RfcommChannel` 契约 | incoming/start/stop/connect/isConnectedTo/sendTo（点对点语义） | Wi-Fi Direct 比它强（需 broadcast/组播），故不走此契约，直接实现 MeshTransport |
| `FileTransferManager` | 窗口 8 / 块 ≤448B（BLE MTU 设计）/ dynamicChunkBytes(mtu) / 无上限重试 / 停滞收尾 45s | 需参数化 CHUNK_BYTES 与 WINDOW 以适配无 MTU 限制的 P2P |
| `File3` | v2 CHUNK 帧带 byteOffset 显式定位（支持任意块）；encodeChunk `require(data.size <= CHUNK_BYTES)` 硬校验 448 | 大块需放宽该硬校验（P2P 下 16KB 级） |
| `DedupCache` | LRU 容量 512 | 消息双写去重靠它（envelope.id） |
| PING/PONG 处理 | **无去重**：收到 PING 必回 PONG（新 id）；markSeen/learnRoutes/LinkQuality/ackIds 均幂等 | 心跳双写必须在 PING/PONG 分支补 envelope.id 去重（防重复回 PONG/重复处理） |
| 群消息 | GROUP 分支 + 内容指纹去重（10s 窗） | 双写后重复帧被指纹收敛，零改造 |
| `MeshChatApplication` | transport/service 装配点（lazy 单例） | 新增 wfd/composite 装配；Wi-Fi Direct 开关偏好 |
| 权限（MainActivity） | 按版本拆分 BLE 权限 | 需扩展 Wi-Fi Direct 权限（见 §4.1） |

## 3. 设计决策（用户确认）

| 决策点 | 选择 | 理由 |
|--------|------|------|
| 总体架构 | **BLE 常开 + Wi-Fi Direct 可选增强**：开启后自动与可达设备建连形成星域，组内双通道，组外纯 BLE；BLE 断 → 强制 Wi-Fi Direct 保持 | 用户定稿；分层容错，极端环境不中断 |
| 星域形成 | **自动**：所有设备 discover + connect，GO negotiation 自动选 GO；无需手动分组 | 用户方案；Wi-Fi Direct 设计意图 |
| 消息路由（TEXT/GROUP/RECEIPT） | **双写全通道**：BLE.broadcast + Wi-Fi Direct 组内广播 | 用户决策；可靠性最高，DedupCache/群指纹收敛 |
| 文件路由（FILE3/START/ACK） | **优先 Wi-Fi Direct**（组内 TCP 单写）；组外/不可用回退 BLE | 用户决策；带宽诉求，双写浪费 |
| 心跳路由（PING/PONG） | **也双写**；接收端补 envelope.id 去重 | 用户决策；双写冗余，去重防重复 PONG/重复处理 |
| 开关默认 | **关闭**（设置页可随时开启） | 用户决策；省电优先 |
| 帧识别 | P2P 服务发现（DnsSd TXT 携带 shortid） | 发现阶段无连接即知身份，与 BLE Service Data 思路同构 |

## 4. 详细设计

### 4.1 权限（Manifest + MainActivity）

```xml
<!-- API 33+：NEARBY_WIFI_DEVICES 近似权限替代位置权限 -->
<uses-permission android:name="android.permission.NEARBY_WIFI_DEVICES"
    android:usesPermissionFlags="neverForLocation" />
<!-- API ≤32：discoverPeers 需要位置权限；socket 需要 INTERNET -->
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"
    android:maxSdkVersion="32" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.CHANGE_WIFI_STATE" />
```

- `MainActivity.requiredPermissions` 扩展：API 33+ 追加 `NEARBY_WIFI_DEVICES`（usesPermissionFlags="neverForLocation"）；API ≤32 追加位置权限（与 BLE 同权限复用，无需新增运行时请求）。`INTERNET/ACCESS_WIFI_STATE/CHANGE_WIFI_STATE` 为 normal 级声明即授予。
- 拒绝 Wi-Fi Direct 权限 → 增强层不可用，Composite 纯 BLE 运行（同 BLE 权限拒绝降级语义）。

### 4.2 WifiDirectTransport（新文件 `mesh/transport/WifiDirectTransport.kt`）

**职责**：Wi-Fi Direct 星域的生命周期 + 组内数据通道。**实现 `MeshTransport`**（组内能力完整：broadcast=组内广播、sendTo=组内点对点）。

**状态机**：
```
DISABLED（开关关/未初始化）
  │ enable()
  ▼
DISCOVERING（discoverPeers + DnsSd 服务发现，持续扫描）
  │ 发现可达设备（短 ID 已知）→ connect()
  ▼
CONNECTING（GO negotiation）
  ├─ 成为 GO：createGroup（若范围内无现存 group）
  └─ 成为 Client：join 对端 group
  ▼
GROUPED（组内广播/收发就绪）
  │ GROUP_REMOVED / CONNECTION_CHANGED 断开
  ▼
RECONNECTING（指数退避 1s→2s→…→30s 重建）──→ DISCOVERING
```

**帧识别（无连接阶段）**：
- `enable()` 时注册 `WifiP2pDnsSdServiceInfo`：`_meshchat._tcp`，TXT 携带 `shortid=<本机短 ID>`、`name=<昵称>`。
- `WifiP2pDnsSdServiceRequest` 解析对端 TXT → 维护 `shortId ↔ WifiP2pDevice` 映射（与 BLE peers 短 ID 体系对齐）。
- 设备名不可控（系统设置），**不依赖设备名**。

**组内数据通道（GROUPED 后）**：
- IP：GO 分配 192.168.49.x（`WifiP2pInfo.groupOwnerAddress`）；GO 与各 Client 同网段。
- **身份 ↔ IP 映射**：组内 UDP 广播注册帧 `REGISTER{shortId, ip, port}`（周期性 5s 重发 + 新成员加入时立即广播）；各端维护 `shortId → (ip, port)` 表；GO 额外维护全表。
- **broadcast（广播语义）**：UDP 定向广播帧 `FrameMessage{DATA: bytes}` 发往 **192.168.49.255**（802.11 P2P 广播，GO 转发全组；组内所有成员含 GO 均可达）。分组为 UDP 报文（≤65507B；Wi-Fi Direct 下信封帧 ≤64KB 无压力）。可靠性：消息双写场景由对端去重 + 重发兜底；纯组内场景（文件）不走此通道。
- **sendTo（可靠语义）**：TCP（复用 RfcommFraming 分帧）+ 每连接写锁（多协程并发写交错防护，仿 RfcommTransport）。文件/大帧走此通道。
- **incoming**：UDP 广播 + TCP 两路合并 `MutableSharedFlow<MeshFrame>`。
- **foundPeers**：星域成员以 `MeshPeerInfo(shortId, deviceAddress=WiFi MAC, rssi=0, displayName, presence=ONLINE)` 上抛，供 Composite 判定"目标在组内"。

**生命周期 API**（Composite 调用）：
```kotlin
fun enable()          // 开关开：启动服务发现 + 自动连接循环
fun disable()         // 开关关：disconnectGroup + removeGroup + 停止扫描
fun isGrouped(): Boolean
fun members(): Set<String>          // 组内成员 shortId（含 GO）
fun groupAddressFor(peerId): String? // 组内 peer IP（sendTo 用）
fun forceConnect()    // 紧急重连：BLE 断开时立即尝试（绕过退避）
```

**关键细节**：
- GO negotiation 结果回调 `WifiP2pManager.ActionListener`（SUCCESS/FAILURE）+ 广播接收 `WIFI_P2P_CONNECTION_CHANGED_ACTION`/`WIFI_P2P_STATE_CHANGED_ACTION`/`WIFI_P2P_PEERS_CHANGED_ACTION`。
- 连接循环节流：每 15s 扫描一轮（discoverPeers 有 30s 超时上限），对未连接且已知 shortId 的设备逐个 connect；已连接不重复。
- 全部操作 runCatching 包裹（厂商栈差异），失败静默回退（Composite 继续纯 BLE）。
- 权限校验：`WifiP2pManager.isInitiator` 等无需；`context.checkSelfPermission` 前置检查。

### 4.3 CompositeTransport（新文件 `mesh/transport/CompositeTransport.kt`）

**职责**：双通道选择器，实现 `MeshTransport`，包装 `BleTransport` + `WifiDirectTransport`。**MeshService 仅注入此对象，内部逻辑零改动**（除心跳去重一行，见 §4.4）。

**路由表（核心策略）**：

| 帧类别 | 判定 | 动作 |
|--------|------|------|
| TEXT / GROUP | 无条件 | `ble.broadcast(frame)` + `wfd.broadcast(frame)`（**双写**；wfd 未 GROUPED 时仅 BLE） |
| RECEIPT | 无条件 | 双写（同消息） |
| PING / PONG | 无条件 | 双写（同消息）；接收端去重见 §4.4 |
| FILE3 CHUNK / START / FILE_ACK | `dstId in wfd.members()` | `wfd.sendTo(dstId, frame)`（TCP 单写） |
| 同上 | 目标组外 | `ble.broadcast(frame)`（回退，现有路径） |
| 上述之外 | — | 默认 BLE |

**接口方法委派**：
- `currentMtu()`：`wfd.isGrouped() ? WIFI_MTU(65535) : ble.currentMtu()`——文件引擎 dynamicChunkBytes 自动放大块（上限受 §4.5 参数化约束）。
- `isConnectedTo(peerId)`：`wfd.members().contains(peerId) || ble.isConnectedTo(peerId)`。
- `setAckProvider/refreshAdvertising/bluetoothEnabled/setTxPowerLevel/suspendDiscovery/resumeDiscovery`：委派 BLE（广播确认键等 BLE 专属语义）。
- `writeUnreliable(frame)`：文件专用——`ble.writeUnreliable(frame)`（保持 BLE 语义；文件帧实际由 FileTransferManager 按 §4.5 走 sendFrame→composite 路由，此方法仅透传）。

**故障切换（极端容错，用户核心需求）**：
- `bluetoothEnabled()`（BLE 层返回 false，蓝牙关闭）→ Composite 触发 `wfd.forceConnect()`：立即尝试建连/保持（若开关已开）；**若开关未开，不自动开启**（尊重用户省电决策，仅提示）——设计默认；如需"BLE 断自动开 Wi-Fi Direct"作为可配项，规格标注由用户决定是否默认行为。
- Wi-Fi Direct 断开重建由 WifiDirectTransport 内部负责（RECONNECTING 退避），Composite 不干预。

**消息送达语义**：双写下 BLE/P2P 任一通道到达即投递；接收端 DedupCache（envelope.id）与群内容指纹去重，**同一逻辑消息最多落库一次**。

### 4.4 心跳双写幂等改造（MeshService 极小改动）

现状：PING/PONG 分支无去重（§2 确认）。双写后组内设备收到两份 PING/PONG，重复处理副作用：

| 处理 | 幂等性 |
|------|--------|
| markSeen / lastSeen | ✅ 幂等 |
| recordLinkQuality（seq 缺口） | ✅ 幂等（seq<=lastSeq 忽略） |
| learnRoutes | ✅ 幂等 |
| resendPendingReceipts(pingTriggered) | ✅ 幂等 |
| ackIds 确认（pendingReceipts.remove） | ✅ 幂等 |
| **回 PONG（收到 PING 每次必回新 id）** | ❌ **非幂等：双写→回 2 个 PONG→流量翻倍** |

**改造**：`handleEnvelope` PING/PONG 分支首行补去重（与 INVITE 同款）：
```kotlin
"PING", "PONG" -> {
    if (dedup.contains(envelope.id)) return
    dedup.mark(envelope.id)
    ...
}
```
- DedupCache 容量 512（LRU）。PING 1s/节点速率，10 节点 ≈ 600 条/分 → 512 容量 ≈ 51s 窗口，够用；但 TEXT 双写也占用同表。**评估**：容量扩至 1024（一行常量改动，内存 ~KB 级），消除 TEXT/PING 互相挤出的尾部风险。— 纳入 M2。
- PONG 本身不再被去重拦截前的重复问题影响（去重后单次处理）。

### 4.5 文件传输适配（M3，FileTransferManager 参数化）

- `File3.CHUNK_BYTES` 448（BLE 上限）→ 引入 `File3.MAX_CHUNK_BYTES = 32 * 1024`（P2P 帧预算：61B 头 + 32KB 数据 + UDP/TCP 无 MTU 限制；len 字段 2B 上限 65535 安全）。`encodeChunk` 硬校验改为 `data.size <= MAX_CHUNK_BYTES`；`dynamicChunkBytes(mtu)` 上限同步放开（`coerceIn(64, MAX_CHUNK_BYTES)`）。
- `FileTransferManager.WINDOW` 8 → 构造参数 `windowSize`（P2P 下 128）；`MAX_ACK_MISSING = windowSize`（强耦合注释保留）。
- **不改协议帧格式**：v2 CHUNK 的 byteOffset 已支持任意块；接收端不再依赖固定块（v1.1.36 已解耦）。
- 收发兼容：老版本设备走 BLE（块 ≤448）不受影响；新版本 P2P 大块帧不被老版本解析（双方同版本场景为主，兼容语义同 FILE3 引入时）。
- 停滞收尾/断开停止/回退 BLE 逻辑全保留（v1.1.48 行为不变）。

### 4.6 装配与 UI

**MeshChatApplication**：
- 新增 `val wfd by lazy { WifiDirectTransport(context, shortId=identity.shortId, debugStats) }`
- `val transport by lazy { CompositeTransport(bleTransport, wfd, ...) }`（替换现有 `transport`，service 构造不变）
- 新增偏好 `wifiDirectEnabled`（默认 **false**，SharedPreferences `meshchat_settings`）；`applyWifiDirect()` 在 onCreate/startMesh/蓝牙 ON 重建后同步 enable/disable。
- 蓝牙状态接收器（现有 `registerBluetoothStateReceiver`）：`STATE_OFF` →（若增强开启）`wfd.forceConnect()` 保持连接（故障切换）。

**UI**：
- 设置页（ProfileDetailScreens）「通用设置」新增「Wi-Fi Direct 增强」开关（默认关，注释省电）。
- Mesh 页/调试中心：当前通道状态（纯 BLE / 双通道 / Wi-Fi Direct 紧急保持）——PeerRow 或顶部状态行小标签；调试中心 DebugSnapshot 增补 `wfdState`/`wfdMembers`（DebugStats 增 provider，内核零逻辑改动）。

## 5. 错误处理

| 场景 | 行为 |
|---|---|
| Wi-Fi Direct 硬件不支持/被禁用 | WifiDirectTransport 启动检测失败 → Composite 纯 BLE，日志提示一次 |
| 权限被拒 | 增强层不可用，纯 BLE（降级语义同 BLE 权限） |
| P2P 连接失败/超时 | 回退 BLE（消息单通道照常）；下一轮扫描重试（15s 循环） |
| group 断开（移动/干扰） | RECONNECTING 指数退避（1s→30s）自动重建；期间消息继续 BLE 单通道 |
| 双写重复帧 | DedupCache（envelope.id）+ 群内容指纹（10s 窗）收敛，同一逻辑消息落库一次 |
| 文件目标不在组内 | composite 回退 BLE 现有文件路径（67KB/s），功能不丢失 |
| BLE 关闭 | Composite 触发 wfd.forceConnect()（若增强开启）；消息/文件改走 Wi-Fi Direct 保持连通 |
| 传统 WiFi 上网被 P2P 抢占 | 设置页提示文案（本项目定位无网/弱网环境，可忽略） |
| 心跳双写流量 | PING/PONG 组内翻倍（~460B/s/成员级），去重后处理单次；DedupCache 扩 1024 兜底 |

## 6. 测试计划

### 6.1 单测（JVM）

1. **WifiDirectTransportTest**（非 Android 部分，注入 FakeP2pManager 或抽纯逻辑）：
   - 注册帧编解码（REGISTER{shortId,ip,port}）
   - UDP/TCP 分帧复用 RfcommFraming 往返
   - 状态机迁移（DISABLED→DISCOVERING→CONNECTING→GROUPED→RECONNECTING）
   - 成员表增删（GO 收到 REGISTER / 超时清理）
2. **CompositeTransportTest**（测试替身 BleTransport + FakeWifiDirectTransport）：
   - 消息双写：TEXT/GROUP/RECEIPT → BLE+WFD 双调用
   - 文件优先：目标组内 → 仅 WFD TCP；目标组外 → 仅 BLE
   - currentMtu：GROUPED→65535 / 非 GROUPED→BLE 值
   - isConnectedTo：WFD 组内 ‖ BLE
   - 心跳双写 + 接收去重（MeshService 层：PING 双收只回一个 PONG）
3. **FileTransferManager 大块测试**：32KB 块 + WINDOW 128 往返字节一致；v1.1.48 停滞/断开/补发回归。
4. MeshService 心跳去重测试：同 id PING 双到 → 仅回 1 PONG、markSeen 单次语义、LinkQuality 不双计。
5. 既有 156 例全量回归（Composite 替换注入后 MeshServiceTest 全绿）。

### 6.2 模拟器/真机

- 模拟器（netsimd 支持 P2P 有限）→ 重点真机：两机开启增强 → 自动建连 → 消息双写去重验证（调试中心看重复帧为 0）→ 关蓝牙 → 消息走 Wi-Fi Direct 保持收发。
- 三机：A—B—C 链式，B 开启增强（自动组星域），A/C 未开（纯 BLE）→ 群消息双域可达。
- 文件：100MB 双机传 → 速率 ≥10MB/s（P2P TCP）且与 BLE 回退对照。

## 7. 边界与范围外

- **范围外**：Wi-Fi Direct 多跳 mesh（时间分片/按需组网，论文方案）——M3 实验单独立项，**不阻塞本规格**；跨星域文件传输（GO 存储转发）——后续；Wi-Fi Aware（NAN）无连接数据——不采用（OEM 支持参差）。
- 星域 = 自动形成的单一 group；多 group 并存时（物理分散），跨域消息靠各自 GO 的 BLE 泛洪互联（现有 mesh 语义）。
- Wi-Fi Direct 组内成员数受 GO 能力限制（典型 ≤10 台），超过自动溢出到 BLE 泛洪（无感知降级）。

## 8. 分批实施顺序（用户定稿三计划，对应原 M1-M4）

```
计划一（P1）Wi-Fi Direct 一对一连接打通
   = 原 M1 双人子集：权限（Manifest+MainActivity 扩展）→ WifiDirectTransport 核心
     （discoverPeers + DnsSd 短 ID 识别 + connect/GO negotiation 双人 + TCP 通道 + RfcommFraming 分帧）
   → 装配（Application 新增 wfd 单例，暂不替换 transport 注入——BLE 单通道照常）
   → 单测（分帧/状态机/成员表）→ 两机真机验证"增强开启→自动建连→组内互达"

计划二（P2）多人 Wi-Fi Direct 星域连接
   = 原 M1 多人扩展：多成员星域（REGISTER 身份↔IP 映射表 + 组内 UDP 广播 + 成员管理/超时清理
     + GO negotiation 自动选主 + group 断开指数退避重建）
   → 单测（多成员状态/注册帧/广播收敛）→ 三机真机验证"多成员星域→组内互达"

计划三（P3）彻底完成
   = 原 M2+M3+M4：CompositeTransport 双通道（消息/回执/心跳双写 + PING/PONG 去重 + DedupCache 512→1024
     + currentMtu 放大 + 故障切换 BLE 断→强制 Wi-Fi Direct）→ 文件 P2P 优先
     （File3.MAX_CHUNK_BYTES + encodeChunk 放宽 + FileTransferManager 窗口/块参数化 + composite 文件路由）
     → UI/状态（设置开关默认关 + 通道状态显示 + DebugStats provider）
   → 单测（路由/去重/大块往返）→ 真机验证（消息双写零重复落库、关蓝牙走 P2P 保持、100MB 互传）
```

## 9. 涉及文件

- 新增：`mesh/transport/WifiDirectTransport.kt`、`mesh/transport/CompositeTransport.kt`、`test/.../mesh/transport/WifiDirectTransportTest.kt`、`CompositeTransportTest.kt`
- 修改：`AndroidManifest.xml`（4 权限）、`MainActivity.kt`（requiredPermissions）、`mesh/service/MeshService.kt`（PING/PONG 去重 + DedupCache 容量）、`mesh/protocol/File3.kt`（MAX_CHUNK_BYTES）、`mesh/transfer/FileTransferManager.kt`（窗口/块参数化）、`mesh/debug/DebugStats.kt`（wfd provider）、`MeshChatApplication.kt`（wfd/composite 装配 + 开关偏好 + 蓝牙 OFF 触发 forceConnect）、`ui/screens/ProfileDetailScreens.kt`（设置开关）、`ui/screens/MeshScreen.kt`/`DebugCenterScreen.kt`（通道状态）、`app/build.gradle.kts`（版本 bump）
- 复用：`RfcommFraming.kt`（TCP 分帧）、`MeshTransport.kt`（契约）、`DedupCache`、群指纹去重

## 10. 风险

- **厂商栈差异**：GO negotiation / UDP 广播在部分 ROM 行为不同 → 全部 runCatching + 回退 BLE，增强层失败零影响。
- **UDP 广播可达性**：192.168.49.255 定向广播在部分 ROM 被过滤 → 兜底：GO 应用层组播（遍历成员表逐 socket 转发）；M1 真机验证后定稿广播实现。
- **DedupCache 容量**：双写 + 高频心跳共同占用 → 512→1024 一行改动。
- **P2P 抢占传统 WiFi**：无网环境无感；在线场景设置页提示。
- **功耗**：默认关闭 + 按需建连 + 空闲可断开（后续优化项），M4 后视实测。
