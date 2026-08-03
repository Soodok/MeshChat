# AI_CONTEXT.md — MeshChat 工程交接

> 本文件为 AI 协作交接文档。会话开始前必须阅读；会话结束前必须更新「交接块」。

## 项目定位

MeshChat 是面向**无公网/弱网极端环境**的近场安全通信应用。本仓库（`E:\MeshChat Project`）包含：**Android 前端**（Jetpack Compose，界面/导航/交互）与**设备内嵌去中心化后端框架**（协议/路由/身份/持久化/服务编排/BLE 传输）。

- 工程根目录：`E:\MeshChat Project`；git 远程：`https://github.com/Soodok/MeshChat`（main 分支）
- 包名：`com.meshchat.app`；minSdk 26 / targetSdk 36 / compileSdk 36（平台 36.1）
- **当前版本：v0.12.0（versionCode 23，构建时间 2026-08-03）**——版本更新规则：每次构建后 bump，安装包命名 `MeshChat-vX.Y.Z-debug.apk` 存于工程根目录
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
- **v0.6.0 探测增强**：节点状态每 200ms 刷新（`MeshService` tick，`REFRESH_INTERVAL_MS=200`）；节点行显示 RSSI 数值（dBm）+ 等级标签（S/A/B/C/D）；失联状态机——>1.5s 无扫描更新标记 `lost`（红色「失去连接·正在重连…」），>5s 移除；`BleTransport` 断开连接时移除记录，持续扫描自动重连。
- **v0.6.0 蓝牙质量评分模块**：`mesh/quality/BluetoothQuality.kt`（独立，供后续复用）——`grade(rssi, deviceFactor)` 返回 S/A/B/C/D 等级、`score(rssi)` 0-100 分、`bars(rssi)` 0-3 信号条；deviceFactor 预留设备能力修正。
- **v0.6.0 本机蓝牙信息**：`MeshChatApplication` 暴露 `localBluetoothName/Address`，身份页新增「本机蓝牙信息」（蓝牙名称/MAC 地址）区块。
- **v0.7.0 握手确认容错**（修复「被发起者接受后发起者收不到确认」）：`MeshService` 新增 `_ackRetries` 状态机——接受邀请后立即回发 `INVITE_ACK`，并由 tick 每 0.2s 持续重发，直至收到对端确认或超时（`ACK_RETRY_TIMEOUT_MS=30s`）；收到对端 `INVITE_ACK` 时建立会话并**回发一次 ack-of-ack** 令对端停止重发；已建立会话的对端再次发 `INVITE`（其确认丢失场景）时重发确认并重启重发窗口；`handleEnvelope` 忽略自身回环帧（防会话被自身 ACK 污染）。
- **v0.8.0 多版本兼容修复**（安卓 16 正常 / 12 能收不能发 / 11 卡退）：
  - **修复安卓 11 卡退根因**：Manifest 补 `BLUETOOTH`/`BLUETOOTH_ADMIN`（maxSdkVersion 30，Android ≤11 BLE 必需普通权限）；`ACCESS_FINE_LOCATION` 限 maxSdkVersion 30；`MeshChatApplication.localBluetoothName/Address` 加 runCatching 防御（原 `adapter.name` 在 Android ≤11 无 BLUETOOTH 权限时抛 SecurityException，ViewModelFactory 构造即崩）。
  - **权限按版本拆分**：`MainActivity.requiredPermissions` API≥31 请求 BLUETOOTH_SCAN/CONNECT/ADVERTISE，API≤30 只请求位置权限（原 3 个新权限在 Android 11 永不授予 → hasAllPermissions 恒 false → Mesh 永不启动）。
  - **安卓 12 能收不能发（待真机 logcat 确证）**：BleTransport 加诊断埋点（TAG=MeshBle：connect 状态/MTU 协商/服务发现/帧队列/写入返回值）+ 可靠性改进——connectGatt 统一主线程调用、requestMtu 失败不阻塞 discoverServices、服务发现失败重试 3 次（防 pendingFrames 永久滞留）、writeCharacteristic 失败打日志。
- **v0.9.x~v0.10.0 真机联调全链路打通**（A11 安卓11 GSI / A12 华为12 / A16 安卓16 三机实测）：
  - **draftId 过滤**：INVITE/INVITE_ACK 校验 dstId（防空广播把邀请泄露给无关节点弹窗）。
  - **修复 A11 扫描不工作**：根因是**位置服务未开启**（Android ≤11 BLE 扫描依赖位置服务），`location_mode=3` 后扫描/主动连接恢复。已通过 adb 开启，用户侧需注意。
  - **修复 notify NoSuchMethodError**：4 参数 `notifyCharacteristicChanged` 是 API 33+，Android 11 没有 → 按 `Build.VERSION.SDK_INT` 分支（<33 用 3 参数 + characteristic.value 传载荷）。
  - **GATT 双通道**：broadcast 同时走「central 写特征」（writeToConnectedClients）+「server notify 回传」（notifySubscribers，含 CCCD 订阅 + onCharacteristicChanged 接收回调）。被邀请方无需主动连接也能回 ACK。
  - **discoverServices 超时兜底**：5s 未回调自动重试（`DISCOVER_TIMEOUT_MS`，最多 3 次），解决部分 ROM/GSI 回调永不触发导致 pendingFrames 永久滞留。
  - **ACK 重发收敛**：`INVITE_ACK` 仅**首次**收到时回发 ack-of-ack（用 pendingInvites 判定发起方），防止双方无限互发确认刷屏挤占 BLE 带宽。
  - **短 ID 持久化**：`LocalIdentity` 短 ID 存 SharedPreferences（`meshchat_identity`），重启不变（原每次重启变 ID → 会话/路由失效）。
  - **TEXT 按 dstId 投递**：去掉 srcId∈sessions 白名单拦截，发往本机（dstId 匹配）即投递，会话内存态丢失不误丢消息。
  - **修复 UI 消息自环**：`MeshChatHome` 聊天列表点击硬编码 `conversationTarget="ME"` + `sendMessage` 硬编码 `conv-ME` → 全部提升到 ViewModel（`conversationTarget` StateFlow + `flatMapLatest` 消息流），点击进入真实会话、消息发往当前会话。
  - **convId 对称**：接收方落库用 `conv-<srcId>`（发送者短 ID）作会话键，收发双方读写同一会话（原发送方用对端 ID、接收方用发送者 ID → 消息存了查不到）。
  - 单测新增 `isReturnDefaultValues` 豁免（MeshService 使用 android.util.Log）。
- 前端已改为消费 `MeshRepository`（ViewModel 注入 factory）。
- **v0.11.0 修复消息方向显示**（用户真机反馈"B 收到消息像自己跟自己对话"）：`MeshRepository.toUiModel()` 原硬编码 `sentByMe = true`，所有消息（含收到的）都渲染在右侧自己气泡 → 改为 `sentByMe = srcId == service.shortId`（本机发出的靠右，对端发来的靠左）。真机通讯已正常。
- **v0.12.0 文件传输全链路**（用户反馈"还不能传文件"）：协议层 `FileBody.fileId` + 新 `FileAckBody`（缺失 bitmap）；`mesh/transfer/FileTransferManager` 传输引擎——窗口 32 块/块 200B/15s 窗口超时/5 次重试上限/60s 接收无进展清理/串行单文件；`AndroidFileSaver` 落盘 Downloads（API 29+ MediaStore 免权限，26-28 WRITE_EXTERNAL_STORAGE）；MeshService `sendFile` + FILE/FILE_ACK 一跳分发（dstId 校验，不进 outbox）；接收端收齐校验大小 → 落盘 → 回填 fileMeta downloadsUri → DELIVERED；UI 文件气泡（图标/文件名/大小/进度条/完成状态）+ 系统文件选择器（OpenDocument）+ 点击打开（ACTION_VIEW）。规格：`docs/superpowers/specs/2026-08-03-meshchat-file-transfer-design.md`；计划：`docs/superpowers/plans/2026-08-03-meshchat-file-transfer.md`。真机验收待做。
- git 历史：基线 `d138496` → 远程合并 `4d25192` → 设计规格 `3aa4fd4` → 计划 `75dddb0` → 任务 0-11 共 12 个实现提交（最新 `b6a2d2c`）→ 联调提交 `fd10d7d` → 交接块 `ab2f287`/`067618b` → v0.11.0 修复 `9e22674` → v0.12.0 文件传输 8 提交（`a8bdf2b`~`23172bb`）。

### 已验证内容
- `gradlew testDebugUnitTest`：**31/31 测试通过，0 失败**（原 22 + 文件传输 9：协议 fileId/FileAckBody 编解码、窗口重发/超时重发/重试上限/串行约束/乱序重组落盘、MeshService FILE 落库/dstId 过滤）。
- `gradlew assembleDebug`：**BUILD SUCCESSFUL**，APK `MeshChat-v0.12.0-debug.apk`。
- **真机三机（A11 GSI / A12 华为 / A16）实测打通**：握手→会话锁定→消息双向到达（MeshSvc 日志确认 `deliver kind=TEXT src=<对端> dst=<本机>` 与 `recv kind=TEXT` 双向出现）。
- **v0.11.0 双人真机聊天正常**（用户确认）：消息方向修复后 A↔B 可正常收发，对端消息显示在左侧、本机消息在右侧，不再是"自己跟自己对话"。

### 当前阻塞
- **GitHub 推送（用户决定暂缓）**：本地已提交至 v0.12.0 全部内容（`9e22674` 起的 10 个提交），领先 `origin/main`；推送被网络重置（梯子不稳定）。**用户明确"先不提交"**——下次 push 前先确认。备用源 `git clone https://soodok.online/meshchat_bare.git`（未同步 v0.11.0/v0.12.0）。
- 服务器注意：nginx `client_max_body_size` 默认 1M → 上传 bundle 需分块（≤400KB/块）；`/home/wwwroot` 不存在，实际 web 根为 `/var/www/html`。
- **A11（安卓 11 GSI）位置服务**：BLE 扫描依赖位置服务，已 adb 开启（location_mode=3）；若重刷/恢复出厂需重新开启。

### 下一步首要任务
1. **真机验收文件传输（v0.12.0）**：A↔B 建立会话后附件按钮选文件（<10MB）→ 气泡出现进度条 → 传输完成 → B 的 Downloads 出现完整文件 → 点击可打开；断连场景（传输中关蓝牙/移开）窗口重传生效；日志 `adb logcat -s MeshSvc MeshBle`。
2. 推送暂缓（用户已确认"先不提交"）；网络恢复后 `git push origin main` 同步本地领先提交（含 v0.12.0 全部 8 个），并同步备用源 `soodok.online/meshchat_bare.git`。
3. 三机全链路回归：握手→会话→双向消息→文件传输→失联重连→多跳转发（TTL 8）。
4. 按规格开放问题推进：真实加密接入（Cipher 接口占位）、WiFi Direct 载体（复用 MeshTransport 抽象）、群聊上层逻辑（协议载荷已就绪）。

### 本次涉及的关键文件
- 后端：`app/src/main/java/com/meshchat/app/mesh/**`（protocol/routing/identity/storage/transport/service）
- 对接：`app/src/main/java/com/meshchat/app/data/MeshRepository.kt`、`ui/MeshChatViewModel.kt`、`ui/MeshChatApp.kt`、`ui/MeshChatViewModelFactory.kt`
- 构建：`build.gradle.kts`、`app/build.gradle.kts`、`gradle.properties`、`app/src/main/AndroidManifest.xml`
- 文档：`README.md`、`AI_CONTEXT.md`、`docs/superpowers/specs/*.md`、`docs/superpowers/plans/*.md`
