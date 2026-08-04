# MeshChat 技术架构与功能基线

> 文档用途：记录当前 Android 版本的真实技术栈、模块职责、已实现能力、未完成能力和后续风险。
>
> 适用版本：`0.4.0`（`versionCode 5`）
>
> 更新时间：2026-08-04

## 0. 软件简介

### MeshChat 是什么

MeshChat 是一个不依赖公网服务器的近距离加密通信应用。它让附近的手机通过蓝牙 BLE 互相发现，并组成一个临时的 Mesh 网络，在没有移动网络或 Wi-Fi 的情况下传递消息。

它适合以下场景：

- 灾害救援、户外活动等网络不稳定的环境。
- 展会、校园、营地等需要近距离临时通信的场所。
- 不希望消息必须经过中心服务器的点对点通信场景。

MeshChat 不是普通的蓝牙文件传输工具，也不是依赖互联网账号登录的即时通信软件。当前版本的核心体验是：发现附近节点、建立对话、发送文本消息，消息可以经过中间节点转发到目标设备。

### 普通用户怎么使用

当前版本的预期使用流程如下：

1. 在两台 Android 手机上安装并打开 MeshChat。
2. 两台手机打开蓝牙，并允许应用使用附近设备权限。
3. 在 Mesh 页面点击“开始附近发现”。
4. 一台手机点击发现到的另一台设备，发起对话请求。
5. 对方在弹窗中选择接受。
6. 双方进入会话后发送文本消息。
7. 聊天页面显示消息内容和 Mesh 投递状态。

使用时应让两台手机保持较近距离，并让应用处于前台。当前版本主要验证 Android 双机前台通信，不应把它理解为已经支持后台持续运行、跨 iPhone 通信或大文件传输。

### 当前版本能做什么

- 显示附近真实发现的 Mesh 节点。
- 通过邀请和确认建立点对点会话。
- 发送和保存文本消息。
- 显示消息的发送、投递状态。
- 使用节点去重和 TTL 机制进行基础多跳转发。
- 在没有公网的情况下使用 BLE 传输消息。

### 当前版本暂时不能保证什么

- 应用退到后台或被系统杀死后的持续组网。
- iPhone 与 Android 之间直接互通。
- HarmonyOS NEXT 原生版本。
- 大文本、图片、文件的可靠分片传输。
- 已完成的端到端加密。当前协议字段仍使用 `enc = none`，真正的密钥协商和消息加密仍需接入。

### 一句话介绍

> MeshChat 是一款基于 BLE 的近距离离线 Mesh 通信应用，让附近 Android 设备在没有公网的情况下发现彼此、建立会话并传递文本消息。

## 1. 项目定位

MeshChat 是面向无公网、弱网络和近距离场景的 Android 去中心化通信应用。设备通过 BLE 发现附近节点，并使用自定义 Mesh 协议在节点之间转发消息。

当前项目不是 Bluetooth SIG 定义的 Bluetooth Mesh 标准实现，而是：

```text
Android BLE 广播 / 扫描 / GATT
                 ↓
自定义 MeshFrame + MeshEnvelope
                 ↓
去重、TTL 路由、转发、投递回执
                 ↓
Room 消息与出站队列持久化
                 ↓
Compose + ViewModel + StateFlow UI
```

当前版本的正式目标是：Android 8.0（API 26）及以上、支持 BLE 的设备、应用前台运行时的附近节点发现和点对点通信。

## 2. 技术栈

| 层次 | 当前技术 | 作用 |
|---|---|---|
| 应用语言 | Kotlin 2.2.10 | Android 业务和协议实现 |
| 构建 | Gradle 9.1.0、Android Gradle Plugin 9.0.0 | 工程构建和 APK 产物 |
| Android | `minSdk 26`、`targetSdk 36`、`compileSdk 36` | 系统兼容范围和编译 API |
| UI | Jetpack Compose、Material 3 | 声明式页面、组件和主题 |
| UI 状态 | ViewModel、StateFlow、`collectAsStateWithLifecycle()` | 单向状态下发和事件上报 |
| 异步 | Kotlin Coroutines、Flow | BLE 事件、Repository 数据流和后台任务 |
| 序列化 | `kotlinx.serialization` JSON | MeshEnvelope 和消息载荷编解码 |
| 本地存储 | Room 2.7.0、SQLite | 消息、出站队列和节点数据持久化 |
| 注解处理 | KSP | Room 编译期代码生成 |
| 传输 | Android BLE API、GATT、InMemoryTransport | 真实 BLE 与单元测试替身 |
| 测试 | JUnit、kotlinx-coroutines-test | 协议、路由、身份、服务行为测试 |

依赖版本以 [app/build.gradle.kts](../../app/build.gradle.kts) 为准，不应只依赖本文件中的版本文字。

## 3. 代码结构

```text
app/src/main/java/com/meshchat/app/
├── MainActivity.kt                 Android 入口、权限、蓝牙开关检查
├── MeshChatApplication.kt          Application 级依赖装配
├── data/
│   ├── MeshRepository.kt           UI 数据门面和领域模型转换
│   └── UiModels.kt                 Compose 使用的 UI 数据模型
├── mesh/
│   ├── protocol/                   MeshFrame、MeshEnvelope、载荷模型、JSON
│   ├── routing/                    去重缓存和 TTL 路由决策
│   ├── identity/                   本机短 ID 和节点注册能力
│   ├── storage/                    MeshStore、Room、实体、DAO、内存替身
│   ├── transport/                  BLE 和内存传输抽象
│   └── service/                    收发、转发、会话、回执编排
└── ui/
    ├── MeshChatApp.kt              Flow 收集和页面入口
    ├── MeshChatViewModel.kt        UI 状态、会话选择、用户事件
    ├── MeshChatViewModelFactory.kt Repository 注入
    ├── components/                 通用 Compose 组件
    ├── screens/                    聊天、Mesh、个人资料和设置页面
    └── theme/                      颜色、字体和主题
```

## 4. 分层职责

### 4.1 UI 层

Compose 页面只负责显示状态和上报用户动作：

- `MeshChatApp` 使用 `collectAsStateWithLifecycle()` 收集 ViewModel 的节点、会话、消息、邀请状态。
- `MeshChatHome` 管理页面导航和弹窗等页面级交互状态。
- `ChatsScreen` 显示真实会话列表。
- `MeshScreen` 显示真实发现节点、连接状态和发现按钮。
- `ConversationScreen` 显示当前会话消息和发送输入框。
- `ProfileScreen`、身份密钥页和设置页属于次级页面。

UI 不应直接访问 BLE、Room 或 `MeshService`。事件应通过回调上报到 ViewModel，再由 Repository 执行。

### 4.2 ViewModel 层

`MeshChatViewModel` 当前提供：

- `messages`：根据当前选中的会话 ID，通过 `flatMapLatest` 切换 Room 消息流。
- `conversations`：观察 Repository 生成的真实会话列表。
- `peers`：观察 BLE 发现的节点。
- `sessions`：观察已经完成握手的会话节点。
- `invites`：观察待处理的会话邀请。
- `selectedConversationId`：当前聊天目标。
- `startDiscovery()`、`sendInvite()`、`acceptInvite()`、`rejectInvite()`、`sendMessage()`：用户事件入口。

这是推荐的单向数据流：

```text
MeshService / Room
        ↓
MeshRepository Flow
        ↓
MeshChatViewModel StateFlow
        ↓
Compose collectAsStateWithLifecycle
        ↓
用户点击、输入、发送事件
        ↓
ViewModel → Repository → Service / Room
```

Android 官方建议使用生命周期感知的 Flow 收集方式，并通过单向数据流将状态与修改状态的逻辑分离。[Compose 状态与 Flow](https://developer.android.com/develop/ui/compose/state)

### 4.3 Repository 层

当前项目没有独立的 `ChatRepository`；聊天数据由 `MeshRepository` 统一提供。

`MeshRepositoryImpl` 负责：

- 将 `MeshService.peers` 转换为 UI 节点模型。
- 将 Room 消息转换为 UI 消息模型。
- 从节点会话和全部消息生成会话预览。
- 将消息发送、邀请和附近发现动作转发给 `MeshService`。
- 将消息的发送者 ID转换为 `sentByMe`，避免将所有消息错误显示为本机发送。

后续如果聊天业务继续扩展，可以把消息和会话相关能力拆出 `ChatRepository`，但当前不应为了命名而重复建立第二套数据源。

## 5. BLE 传输实现

### 5.1 发现流程

`BleTransport` 当前使用固定 UUID：

- Service UUID：`0000A5E1-0000-1000-8000-00805F9B34FB`
- Characteristic UUID：`0000A5E2-0000-1000-8000-00805F9B34FB`

启动后尝试同时完成：

1. 注册 GATT Server。
2. 创建可写 Characteristic。
3. 广播 Service UUID 和本机短 ID。
4. 扫描包含目标 Service Data 的附近设备。
5. 发现设备后建立 GATT Client 连接。
6. 连接后协商 MTU 并发现服务。
7. 服务发现完成后发送等待中的帧。

Android BLE 同时区分 Central/Peripheral 和 GATT Client/Server 两组角色；MeshChat 的每台设备都需要承担双重角色。[Android BLE 角色模型](https://developer.android.com/develop/connectivity/bluetooth/ble/ble-overview)

### 5.2 当前传输边界

当前 BLE 传输已经具备双机联调所需的主要骨架，但还存在以下明确边界：

- 扫描目前使用无过滤扫描，未形成完整的扫描超时和功耗策略。
- `sendTo(peerId, frame)` 尚未实现按节点定向发送。
- 当前主要通过已连接客户端广播帧。
- GATT 写入尚未实现基于实际 MTU 的分片和接收重组。
- `requestMtu(512)` 的实际协商结果尚未被记录并用于限制帧大小。
- GATT 回调对 Characteristic、写入偏移、异常和连接断开处理仍较轻量。
- `start()` 被重复调用时需要避免重复注册服务、扫描和收包协程。

BLE ATT 的有效单次写入长度受协商 MTU 限制，不能把一次 `writeCharacteristic` 当作可靠的大消息通道。[Bluetooth ATT 规范](https://www.bluetooth.com/wp-content/uploads/Files/Specification/HTML/Core-54/out/en/host/attribute-protocol--att-.html)

## 6. 协议与路由

### 6.1 MeshFrame

`MeshFrame` 是传输层帧：

```text
1 byte  frame type
2 bytes payload length
N bytes UTF-8 payload
```

支持的帧类型包括：`HELLO`、`DATA`、`ACK`、`RECEIPT`、`PING`。

### 6.2 MeshEnvelope

`MeshEnvelope` 是业务层消息信封，包含：

- `id`：消息唯一 ID，用于去重和回执。
- `kind`：`TEXT`、`FILE`、`GROUP` 等业务类型。
- `srcId` / `dstId`：来源节点和目标节点。
- `convId`：会话 ID。
- `ttl`：剩余转发跳数，默认 8。
- `ts`：消息时间戳。
- `enc`：当前默认值为 `none`。
- `body`：文本、文件或群组载荷。

### 6.3 路由决策

`ForwardingDecision` 当前使用：

- 消息 ID 去重，避免重复转发。
- 目标节点是本机时投递。
- TTL 减 1 后仍大于 0 时转发。
- TTL 耗尽时丢弃。

这是一种基础的受 TTL 约束的洪泛式转发，不是完整的按链路质量选择的 AODV 或最短路径路由。`MeshPeerInfo.hops` 和 RSSI 已预留给更复杂的路由策略。

## 7. 消息和会话流程

### 7.1 建立会话

```text
A 发现 B
  ↓
A 发送 INVITE
  ↓
B 显示邀请并接受/拒绝
  ↓
B 回复 INVITE_ACK
  ↓
双方把对方加入 sessions
  ↓
允许 TEXT 消息参与路由
```

当前服务会阻止没有建立会话关系的普通节点消息参与路由，但本机自环会话仍可用于测试。

### 7.2 发送消息

```text
Compose 输入
  ↓
ViewModel.sendMessage()
  ↓
MeshRepository.sendText()
  ↓
MeshService 创建 MeshEnvelope
  ↓
写入 Room messages
  ↓
ForwardingDecision 决定投递或转发
  ↓
目标节点投递并返回 RECEIPT
  ↓
Room 更新 DELIVERED 状态
```

## 8. Room 与离线数据

当前数据库包含三类实体：

| 表 | 当前用途 |
|---|---|
| `messages` | 保存消息内容、会话、来源、目标、状态和时间 |
| `outbox` | 保存待转发帧、重试次数、下一跳和过期时间 |
| `peers` | 预留节点持久化信息 |

Room DAO 已提供消息和节点查询 Flow。消息会话查询已经接入 UI 状态流，聊天列表由消息和会话状态生成。

需要注意：

- `RoomMeshStore` 当前通过同步包装方法执行写入，后续应改为 `suspend` DAO + 协程调用，避免阻塞服务线程。
- `outbox` 已有数据结构和 DAO，但当前尚未形成完整的后台取出、重试、失败标记和清理任务。
- `peers` 表和 DAO 已存在，但当前节点实时状态主要来自 `MeshService.peers`，节点持久化闭环仍需确认。
- 数据库版本为 1，新增字段或表时必须增加 migration，不应直接破坏已有本地数据库。

Room 推荐使用 DAO 分离数据库访问，使用 `Flow` 观察数据变化，使用协程执行异步写入。[Room 官方文档](https://developer.android.com/training/data-storage/room)，[Room 异步 DAO 查询](https://developer.android.com/training/data-storage/room/async-queries)

## 9. 功能完成度

| 功能 | 状态 | 说明 |
|---|---|---|
| Compose 主界面和底部导航 | 已实现 | 聊天、Mesh、个人资料页面存在 |
| 静态 Mock 节点和假消息 | 已移除 | UI 不再依赖早期演示列表 |
| 真实节点 StateFlow | 已实现 | `MeshService.peers` → Repository → ViewModel → Compose |
| 真实消息 StateFlow | 已实现 | Room `observeMessages()` → Repository → ViewModel |
| 多会话切换 | 已接入 | ViewModel 通过 `flatMapLatest` 切换当前会话消息流 |
| 邀请、接受、拒绝 | 已实现 | `INVITE` / `INVITE_ACK` 和 UI 弹窗已接入 |
| BLE 广播和扫描 | 已实现骨架 | 需要双机真机验证和异常处理完善 |
| GATT Server/Client | 已实现骨架 | 服务发现和等待帧补发已接入 |
| Mesh 去重和 TTL 转发 | 已实现 | 有单元测试覆盖核心决策 |
| Room 消息存储 | 已实现 | 消息、状态和出站实体存在 |
| 端到端加密 | 未实现 | `MeshEnvelope.enc` 当前为 `none`，Cipher 仍是规划项 |
| BLE 大帧分片重组 | 未实现 | 当前大于实际 GATT 写入能力的帧可能失败 |
| Wi-Fi Direct 传输 | 未实现 | 当前只有 BLE 和内存传输实现 |
| 文件消息完整业务 | 未实现 | 有 `FileBody` 协议模型，但服务/UI 仍以文本为主 |
| 群组消息完整业务 | 未实现 | 有 `GroupBody` 协议模型，但没有群组会话管理闭环 |
| 后台持续组网 | 未实现 | 当前按前台应用进程存活设计 |
| iOS / HarmonyOS NEXT 客户端 | 未实现 | 需要独立平台工程和 BLE 适配层 |

## 10. 安全边界

以下概念不能混为一谈：

| 能力 | 作用 | 当前状态 |
|---|---|---|
| BLE 广播和扫描 | 发现附近节点 | 已实现 |
| GATT 连接和写入 | 传输 MeshFrame | 已实现骨架 |
| 蓝牙链路加密或系统配对 | 保护系统蓝牙链路 | 未作为应用安全方案设计 |
| 应用层端到端加密 | 保证中继节点无法读取消息正文 | 未实现 |

MeshChat 后续的安全实现至少需要：身份公钥、密钥协商、消息加密、完整性认证、重放防护和密钥持久化。不能因为 GATT 连接成功，就宣传消息已经端到端加密。

## 11. Android 兼容性重点

当前 `minSdk = 26`，因此必须分别验证：

- Android 8-11：旧的蓝牙和定位权限路径。
- Android 12-13：`BLUETOOTH_SCAN`、`BLUETOOTH_ADVERTISE`、`BLUETOOTH_CONNECT` 附近设备权限。
- Android 14 及以上：MTU 协商、连接回调和长消息写入。
- 不同厂商：后台限制、蓝牙开关恢复、扫描频率和 GATT 稳定性。

当前 Manifest 已声明 BLE 必需特性，Android 官方允许通过 `uses-feature` 让应用商店过滤不支持 BLE 的设备；同时仍应在运行时检查蓝牙适配器、BLE 广播器和扫描器是否可用。[Android 蓝牙权限](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions)

## 12. 测试现状与测试建议

当前源码已有以下测试类别：

- `MeshFrame` 编解码和长度校验。
- `MeshEnvelope` 的文本、文件、群组载荷序列化。
- `DedupCache` 的去重和容量淘汰。
- `ForwardingDecision` 的投递、转发、TTL 耗尽和重复丢弃。
- `LocalIdentity` 和 `PeerRegistry`。
- `MeshService` 自环投递、回执和 TTL 转发。

后续建议补充：

1. ViewModel 在切换 `selectedConversationId` 后是否只显示目标会话消息。
2. Repository 是否正确区分本机消息和对端消息。
3. Room migration 和进程重启后消息是否保留。
4. BLE 连接成功、服务发现失败、MTU 失败、写入失败和断开重连。
5. Android 8-11、12-13、14+ 的权限和双机测试。
6. 长中文消息、加密后载荷和分片重组。

## 13. 当前优先级

### P0：先保证现有 Android 链路可靠

- 修正 Android 8-11 与 Android 12+ 的权限兼容。
- 为 BLE 扫描增加过滤、超时、停止和错误状态。
- 记录实际 MTU，并完成帧分片/重组。
- 增加连接断开、写入失败和重连状态。
- 防止 `MeshService.start()` 重复启动。

### P1：补齐数据和业务闭环

- 将 Room 同步包装改为协程 DAO。
- 建立出站队列处理器、重试、过期和失败状态。
- 明确节点表是否作为实时节点状态来源。
- 完成 `sendTo(peerId)` 或明确所有转发均采用广播策略。
- 接入真实应用层加密和身份验证。

### P2：完善产品能力

- 文件消息传输和分片。
- 群组会话、成员管理和群消息。
- 后台 BLE 运行策略。
- Wi-Fi Direct 高吞吐传输。

### P3：跨平台评估

- 固化平台无关的 MeshFrame、MeshEnvelope、握手和安全协议。
- 评估 iOS Core Bluetooth 的前后台限制。
- 单独评估 HarmonyOS NEXT 的 ArkTS/ArkUI 原生实现成本。

## 14. 开发约束

- Compose 页面不直接访问数据库、BLE 或 Service。
- ViewModel 负责状态组合和事件转发，不保存真实业务数据副本。
- Repository 负责数据源抽象和领域模型转换。
- 协议层不依赖 Compose、Activity 或 Android UI。
- 新增 Room 表或字段必须配套 migration 和测试。
- 新增消息类型必须同时更新编码、解码、路由、存储、UI 和测试。
- 不把测试替身 `InMemoryTransport` / `InMemoryMeshStore` 当作生产数据源。
- 不把协议预留模型当作已经完成的用户功能。

## 15. 参考资料

- [Jetpack Compose 状态与生命周期感知 Flow](https://developer.android.com/develop/ui/compose/state)
- [Android BLE 总览与角色模型](https://developer.android.com/develop/connectivity/bluetooth/ble/ble-overview)
- [Android 蓝牙权限](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions)
- [Android BLE 扫描](https://developer.android.com/develop/connectivity/bluetooth/ble/find-ble-devices)
- [Android 后台 BLE 通信](https://developer.android.com/develop/connectivity/bluetooth/ble/background)
- [Room 数据库](https://developer.android.com/training/data-storage/room)
- [Room 异步 DAO 查询](https://developer.android.com/training/data-storage/room/async-queries)
- [Bluetooth Core Specification - ATT](https://www.bluetooth.com/wp-content/uploads/Files/Specification/HTML/Core-54/out/en/host/attribute-protocol--att-.html)
