# AI_CONTEXT.md — MeshChat 工程交接

> 本文件为 AI 协作交接文档。会话开始前必须阅读；会话结束前必须更新「交接块」。

## 项目定位

MeshChat 是面向**无公网/弱网极端环境**的近场安全通信应用。本仓库（`E:\MeshChat Project`）包含：**Android 前端**（Jetpack Compose，界面/导航/交互）与**设备内嵌去中心化后端框架**（协议/路由/身份/持久化/服务编排/BLE 传输）。

- 工程根目录：`E:\MeshChat Project`；git 远程：`https://github.com/Soodok/MeshChat`（main 分支）
- 包名：`com.meshchat.app`；minSdk 26 / targetSdk 36 / compileSdk 36（平台 36.1）
- **当前版本：v0.4.0（versionCode 5，构建时间 2026-08-03 14:54）**——版本更新规则：每次构建后 bump，安装包命名 `MeshChat-vX.Y.Z-debug.apk` 存于工程根目录
- 构建：AGP 9.0.0 + Kotlin 2.2.10（内置 Kotlin）+ KSP 2.2.10-2.0.2 + Room 2.7.0 + kotlinx-serialization 1.8.1 + Gradle 9.1.0
- 注意：`gradle.properties` 中 `android.disallowKotlinSourceSets=false`（AGP 9 内置 Kotlin 与 KSP 集成的必要豁免，实验性）
- 视觉基准：`design/meshchat-visual-baseline.png`

## 目录结构

```
app/src/main/java/com/meshchat/app/
├── MainActivity.kt                 # 入口（Edge-to-Edge + 主题）
├── data/UiModels.kt                # 前端数据模型 + 演示数据
├── data/MeshRepository.kt          # 前端数据源契约 + MeshRepositoryImpl（对接后端）
├── mesh/
│   ├── protocol/                   # 帧编解码(MeshFrame)、消息信封(MeshEnvelope)+TEXT/FILE/GROUP 载荷、JSON 序列化(MeshJson)
│   ├── routing/                    # LRU 去重表(DedupCache)、转发决策(ForwardingDecision)
│   ├── identity/                   # 短 ID 生成(LocalIdentity)、节点注册表(PeerRegistry)
│   ├── storage/                    # 存储抽象(MeshStore)、Room 实体/DAO/MeshDatabase+RoomMeshStore、InMemoryMeshStore(测试用)
│   ├── transport/                  # 传输抽象(MeshTransport)、InMemoryTransport(测试替身)、BleTransport(BLE 实现)
│   └── service/                    # MeshService（发送/接收/转发/回执编排，TTL=8，outbox 持久化队列）
├── ui/                             # MeshChatApp / MeshChatViewModel / Factory / components / screens / theme
└── meshchat.db 由 Room 自动创建（messages/outbox/peers 三表）
```

## 设计规格与实现计划

- 规格：`docs/superpowers/specs/2026-08-03-meshchat-backend-design.md`（已确认）
- 计划：`docs/superpowers/plans/2026-08-03-meshchat-backend.md`（11 任务，已全部执行）

## 交接块

### 当前进度
- 后端框架 11 个任务全部实现并提交：构建环境、帧协议、消息信封、去重表、转发决策、身份层、Room 存储、传输抽象、MeshService 编排、Repository 前端接入、BLE 传输。
- 真机联调修复：Manifest 补 `BLUETOOTH_ADVERTISE`（BLE 广播必需）；`BleTransport.start()` 三段 runCatching 降级防崩；「开始附近发现」按钮已绑定 `MeshService.start()`。
- BLE 发现链路修复（v0.2.0）：广播数据由 Service UUID 改为 **Service Data 携带本机短 ID**，扫描按 Service Data 识别节点（原按设备名前缀过滤，广播却不带名 → 永远发现不了）；`MeshService` 聚合 `foundPeers` 至 `peers` StateFlow，`MeshRepository.observePeers()` 已接真实数据；按钮点击后有「正在扫描邻近节点…/重新发现」反馈，发现节点实时显示。
- 演示数据已全部移除（`UiModels.kt` 的 nearbyChats/queuedChats/meshPeers/linMessages）；**v0.3.0 进一步清除演示残留**：顶部「已连接·6 个节点」→「发现节点 N」（真实 peers 数）；会话页/拓扑页「已连接·2 跳」「2 跳路由可用」假状态删除；身份页改显真实短 ID（原演示指纹/「身份已验证」移除）；设置页示例开关移除；「本机身份·已验证」→「本机身份」。
- **v0.3.0 点对点通讯入口打通**：Mesh 页点击节点 → 进入以该节点短 ID 为标题的会话，发消息 `dstId=节点短ID`，经 GATT 连接写入对端（原 `conv-ME` 自环仅保留在聊天列表「我」）。
- **v0.3.0 蓝牙状态检查**：MainActivity 启动时校验蓝牙开启（`adapter.isEnabled`），未开启 Toast 提示「蓝牙未开启，请先开启蓝牙后重试」。
- **v0.4.0 对话握手协议**：点击节点先发 `INVITE` 对话请求 → 对端 AlertDialog「接受/拒绝」→ 接受回发 `INVITE_ACK` 建立会话关系（`MeshService.sessions`）；**仅已建立会话关系的节点间消息才被路由投递**（TEXT 投递前校验 srcId ∈ sessions 或自环）；节点列表显示「已连接·点击进入会话/点击发起对话」。
- **v0.4.0 GATT 写入可靠性**：连接后 `requestMtu(512)`（容纳消息信封，原默认 23B 中文 JSON 必失败）；服务发现（`onServicesDiscovered`）前写入暂存 `pendingFrames` 待发现后补写（原 `getService` 为 null 时消息静默丢弃）。
- **v0.4.0 键盘适配**：ConversationScreen 根 Column 统一 `imePadding()`，输入行去重（原仅输入行有 imePadding，edge-to-edge 下布局异常）。
- 前端已改为消费 `MeshRepository`（ViewModel 注入 factory）。
- git 历史：基线 `d138496` → 远程合并 `4d25192` → 设计规格 `3aa4fd4` → 计划 `75dddb0` → 任务 0-11 共 12 个实现提交（最新 `b6a2d2c`）。

### 已验证内容
- `gradlew testDebugUnitTest`：**18/18 测试通过，0 失败**（帧编解码/信封序列化/去重/转发决策/身份/服务自环闭环）。
- `gradlew assembleDebug`：**BUILD SUCCESSFUL**。
- 服务层自环闭环（MeshServiceTest）：发送→投递→DELIVERED 状态、转发帧 TTL 递减 7 均验证通过。

### 当前阻塞
- **BLE 真机双机联调**：代码链路就绪（广播短 ID、GATT 连接+MTU 协商+服务发现等待+帧接力、对话握手、会话级投递校验）；待真机双机验证「握手→会话→消息投递」全链路（当前无设备连接）。
- **GitHub 推送**：链路偶发 `Connection was reset`（间歇性），本地提交安全；重试即成功。

### 下一步首要任务
1. BLE 真机双机联调（v0.4.0）：流程 = 双方打开 App 发现节点 → A 点击节点发起对话 → B 弹窗接受 → 会话建立 → 互发消息验证 GATT 投递与回执。联调反馈渠道：logcat 抓 `MeshService`/`BleTransport`。已知待验证点：MTU 协商实际值、`writeCharacteristic` 对超长载荷（>协商 MTU）仍会失败（分片未实现）。
2. 按规格开放问题推进：真实加密接入（Cipher 接口占位）、WiFi Direct 载体（复用 MeshTransport 抽象）、群聊/文件传输上层逻辑（协议载荷已就绪）。
3. 后端数据源接入前端：`MeshRepository.observeConversations()` 返回空流，待接入 Room 会话表驱动聊天列表（当前仅「我」自环会话与握手建立的节点会话可进）。

### 本次涉及的关键文件
- 后端：`app/src/main/java/com/meshchat/app/mesh/**`（protocol/routing/identity/storage/transport/service）
- 对接：`app/src/main/java/com/meshchat/app/data/MeshRepository.kt`、`ui/MeshChatViewModel.kt`、`ui/MeshChatApp.kt`、`ui/MeshChatViewModelFactory.kt`
- 构建：`build.gradle.kts`、`app/build.gradle.kts`、`gradle.properties`、`app/src/main/AndroidManifest.xml`
- 文档：`README.md`、`AI_CONTEXT.md`、`docs/superpowers/specs/*.md`、`docs/superpowers/plans/*.md`
