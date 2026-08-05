# AI_CONTEXT.md — MeshChat 工程交接

> 本文件为 AI 协作交接文档。会话开始前必须阅读；会话结束前必须更新「交接块」。

## 项目定位

MeshChat 是面向**无公网/弱网极端环境**的近场安全通信应用。本仓库（`E:\MeshChat Project`）包含：**Android 前端**（Jetpack Compose，界面/导航/交互）与**设备内嵌去中心化后端框架**（协议/路由/身份/持久化/服务编排/BLE 传输）。

- 工程根目录：`E:\MeshChat Project`；git 远程：`https://github.com/Soodok/MeshChat`（main 分支）
- 包名：`com.meshchat.app`；minSdk 26 / targetSdk 36 / compileSdk 36（平台 36.1）
- **当前版本：v1.1.13（versionCode 75，构建时间 2026-08-05）**——版本更新规则：每次构建后 bump，安装包命名 `MeshChat-vX.Y.Z-debug.apk` 存于工程根目录
- **v1.0.25 release 首包**：`MeshChat-v1.0.25-release.apk`（12,537,519 B，比 debug 19,192,426 B 小 35%）——`app/build.gradle.kts` 新增 `signingConfigs.release`（暂用 Android debug keystore，`~/.android/debug.keystore`）+ `buildTypes.release`（`isMinifyEnabled=false` 首次不开混淆，无 proguard-rules.pro，Room/Compose/序列化混淆会崩；后续补规则文件可开 R8）。apksigner verify 通过（Android Debug 证书）。
- **上架签名升级（v1.0.25 正式包）**：用户决策「GitHub 开源 + R8 开启」。① **正式 keystore**：`meshchat-release.keystore`（RSA 2048/10000 天，别名 meshchat，CN=MeshChat O=Soodok）已生成，凭证在 `keystore.properties`（**两者均 gitignore 不入库，密码须用户自行备份，丢失无法更新**）；`signingConfigs.release` 改读 keystore.properties，缺失时占位符使 assembleRelease 失败防误发。② **R8 开启**：`isMinifyEnabled=true + isShrinkResources=true`，`app/proguard-rules.pro` 含 kotlinx-serialization（`$$serializer`/`Companion`/`serializer()` keep + includedescriptorclasses）+ Room 兜底规则。③ **正式包**：`MeshChat-v1.0.25-release.apk`（**1,480,966 B ≈ 1.48MB**，12.5MB→1.48MB -88%），apksigner verify 通过（CN=MeshChat O=Soodok，非 Android Debug）。⚠️ R8 混淆后未真机验证，首次安装需重点回归：会话握手/消息收发/文件传输（serialization 反序列化）。
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
│   ├── transport/                  # 传输抽象(MeshTransport)、InMemoryTransport(测试替身)、BleTransport(BLE 实现)、RfcommFraming/RfcommTransport(RFCOMM 高速载体)
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
- **v0.12.0 文件传输全链路**（用户反馈"还不能传文件"）：协议层 `FileBody.fileId` + 新 `FileAckBody`（缺失 bitmap）；`mesh/transfer/FileTransferManager` 传输引擎——窗口 32 块/块 50B/15s 窗口超时/5 次重试上限/60s 接收无进展清理/串行单文件；`AndroidFileSaver` 落盘 Downloads（API 29+ MediaStore 免权限，26-28 WRITE_EXTERNAL_STORAGE）；MeshService `sendFile` + FILE/FILE_ACK 一跳分发（dstId 校验，不进 outbox）；接收端收齐校验大小 → 落盘 → 回填 fileMeta downloadsUri → DELIVERED；UI 文件气泡（图标/文件名/大小/进度条/完成状态）+ 系统文件选择器（OpenDocument）+ 点击打开（ACTION_VIEW）。规格：`docs/superpowers/specs/2026-08-03-meshchat-file-transfer-design.md`；计划：`docs/superpowers/plans/2026-08-03-meshchat-file-transfer.md`。
- **v0.12.0 文件传输 BLE MTU 修复**（真机 A12 发不过去 → 根因实测）：200B 块整帧 **661-684B**，超过 MTU 512 可用载荷 509B，广播必失败 → 对端一个块收不到 → 窗口超时 5 次 FAILED（进度 0）。修复：块 200→**50B**（整帧 ~453B < 470B 预算测试约束）；MeshService 截断 fileName(≤16 字符)/mime(≤30 字符)（长元数据会推超载荷）；引擎加诊断日志（TAG=`MeshFile`：sendFile 入口/窗口广播/ACK/超时/finish/崩溃）。
- **v0.12.0 文件传输 ACK 帧超 MTU 修复**（真机几十 KB MD 卡住 → 根因实测）：接收端 ACK 的 missing 是**全文件缺失索引列表**，随文件膨胀（1000 块缺失实测 **4024B**）→ ACK 帧超 MTU → 发送端永远收不到确认 → 窗口超时循环（发送端进度 0、接收端收少量块后卡住）。修复：ACK missing **截断 40 项**（发送端只关心当前窗口 32 块内缺失，更早窗口已收齐，窗口内缺失必在列表前部）+ **端到端 4 窗口联动测试**（A 广播→B 收块回截断 ACK→A 推进，100 块文件字节一致落盘）。**注意：MAX_ACK_MISSING=40 与窗口 32 强耦合，改 WINDOW 必须同步。**
- **v0.13.0 RFCOMM 高吞吐载体**（用户反馈 BLE 文件传输"很慢/停很久"，批准自实现系统级 RFCOMM 方案）：
  - **RfcommFraming**（纯 JVM 可测）：4 字节大端长度前缀 + 帧字节；readFrame 读长度判 0/1MB 上限（超限视为损坏流断开）；readFully 循环读满（InputStream.read 不保证读满）。3 单测覆盖往返/中途关闭返回 null/空流 null。
  - **RfcommTransport**：实现 MeshTransport + 新 RfcommChannel 契约（MeshService 只依赖 isConnectedTo/sendTo/connect/start/stop/incoming，可测替身）；服务端 `listenUsingRfcommWithServiceRecord` + accept 协程循环；客户端 connect 含 `createBond()` 自动配对 + ACTION_BOND_STATE_CHANGED 广播等待（15s 超时）+ `createRfcommSocketToServiceRecord`（10s 超时）；peerId→socket ConcurrentHashMap + 每 socket 写锁（多协程并发写交错）；读循环断连清理映射；服务端 accept 连接暂以 MAC 占位 peerId（incoming 帧按信封 srcId 路由，无需 socket 映射）。
  - **FileTransferManager sendFrame 注入**：构造新参 `sendFrame: (dstId, frame) -> Unit`（默认 broadcast 兜底），broadcastChunk/sendAck 全部改走 sendFrame。
  - **MeshService 双传输集成**：构造 `rfcomm: RfcommChannel? = null`（向后兼容）；start() 时 rfcomm.start() + incoming 合流 collector（都走 handleFrame）；stop() 时 rfcomm.stop()；`sendFrame` 路由——RFCOMM 已连接走 sendTo、否则 BLE broadcast；INVITE_ACK **首次**建立会话后自动 `connectRfcomm(peerId)`（从 _peers 取 deviceAddress，失败静默回退 BLE，不阻塞会话）。
  - **注意（计划偏离）**：计划原定 MeshService 依赖具体类 RfcommTransport，但测试无法构造（需 Context）→ 改为抽最小接口 `RfcommChannel`（定义于 MeshService.kt），RfcommTransport 与测试替身共同实现。行为与规格一致。
- **v0.13.1 决策：RFCOMM 停用（代码保留）**——真机无配对弹窗（`createBond()` 依赖系统 UI，华为/GSI 不可靠）+ 用户判定配对模型对「多设备中心连接」拓扑不友好（N 设备=N 次配对确认）+ 现 BLE 1-2KB/s 对 <10MB 文件可接受 → **MeshChatApplication 不再装配 rfcomm**（MeshService rfcomm 参数默认 null，运行时不启动 RFCOMM、不触发配对），RfcommFraming/RfcommTransport/RfcommChannel/sendFrame 注入代码全部保留留档（未来 WiFi Direct 载体可参考复用，WiFi Direct 免配对且吞吐百 MB/s 级）。
- **v0.14.0 后台常驻 + 状态校准 + 节点命名**（用户反馈：会话状态不同步、蓝牙名随机不知道谁是谁、要求息屏收消息）：
  - **前台服务**：`MeshChatService`（startForeground + FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE + START_STICKY）宿主；`NotificationHelper` 两渠道（常驻低优先级「运行中·N 节点在线」+ 消息高优先级「昵称：内容」）；收到 TEXT/文件完成弹通知，点击 PendingIntent 直达会话（Application.conversationRequest → ViewModel 订阅打开）；权限新增 FOREGROUND_SERVICE/FOREGROUND_SERVICE_CONNECTED_DEVICE/POST_NOTIFICATIONS（33+ 运行时请求）；设置页「后台常驻」开关（默认开，关则前台直跑）。
  - **1s 心跳校准**：协议新 `PresenceBody`（@SerialName("PING")）；`heartbeatTick`（tick 200ms 内节流）每 1s 广播 PING（带昵称）；收 PING 回 PONG（双向确认）+ markSeen（lastSeen 更新/昵称 upsert peers 表）；3s 无任何帧判 lost（LOST_HEARTBEAT_MS，取代原 1.5s 扫描推断）；5s 移除；start 立即广播一轮 PING 抢校准。**在线状态双方对称收敛，UI 三态（在线/失联/会话态）由心跳驱动**。
  - **会话持久化**：`SessionStore` 接口 + SharedPrefs 实现；acceptInvite/INVITE_ACK 同步保存、start 恢复；重启后已会话节点点击直达（不再重新 INVITE）。
  - **节点命名**：设置页昵称输入（meshchat_identity.display_name 持久化）；随 PING/INVITE 交换；`MeshPeerInfo.displayName` + `MeshStore.upsertPeer` 落库；UI 节点行「昵称 · ID短ID」，会话标题用昵称。
  - **v0.14.1 修复消息卡 SENDING**（用户反馈：后台切换频繁时对方已收到但本机一直"正在通过 Mesh 发送"）：根因——送达回执（RECEIPT）是**一次性广播帧**，无重传，蓝牙暂停/重启期间丢失后发送方永远等不到 → 状态卡 SENDING（文件传输有窗口 ACK 所以没事）。修复：发送方 `pendingReceipts` 登记每条 TEXT，5s（`RECEIPT_TIMEOUT_MS`）未收回执 → 重发同 id 消息，3 次（`MAX_RECEIPT_RETRIES`）后标 FAILED；**接收方对 dedup 命中的重复 TEXT 补发 RECEIPT**（route Drop 分支特判），重发可收敛不再重复落库。tick 每 200ms 调 `resendPendingReceipts`。
- **v0.15.0 对话体验三件套**（用户反馈：进会话要自动滚底/对方发消息跟随滚动；对话列表只显示短 ID；主界面节点列表重启后空白）：
  - **自动滚动**：ConversationScreen 用 `rememberLazyListState`——进入会话 `scrollToItem` 滚底；新消息到达且 `isNearBottom`（最后 2 项可见）时 `animateScrollToItem` 跟随滚动；用户上滑看历史不被打断。
  - **对话列表昵称**：`observeConversations` 从 `sessions.combine(peers)` 映射，name = 对端 displayName（回退短 ID）。
  - **节点持久化 + 三色状态**：`MeshPeerInfo.presence`（新枚举 `PeerPresence`）+ `MeshStore.loadPeers`；`start()` 从 peers 表 `restoreKnownPeers()` 恢复已知节点（SEARCHING 黄）；心跳状态机——lastSeen==0 寻找中（黄）、<3s 在线（绿）、<30s 断线重连中（黄）、≥30s 离线（黑）；**节点不再因离线移除**（保留显示置黑）；MeshScreen PeerRow 三色文案「已连接/寻找中…/断线重连中…/离线」。
  - **v0.15.2 零容错改造 + 会话页网络状况**（用户：两机均 0.15.1 仍未生效，要求"增加容错率、各种可能性零容错；发送时实时显示对方网络状况，程序据此协商确认送达"）：
  - **送达确认零容错（三层自愈）**：①发送方**指数退避无限重发**（5s→10s→20s→40s→60s 封顶），**永不 FAILED、永不移出队列**——之前 3 次重发后标 FAILED 并移除是"后台切换空窗"下永久误报的根因；收到回执即 DELIVERED。②接收方**60s 窗口重复回执**（`recentReceived`，heartbeat 每 3s 补发近期消息 RECEIPT）——不依赖发送方重发也能收敛，双方在线时段必达。③**PING 触发即时重发**（对方心跳在线立即补发，后台恢复秒级收敛）+ 重启恢复 SENDING 消息（v0.15.1 已有）。
  - **节点持久化兜底**：peers 表为空时**从消息历史反推对端**（`MeshStore.loadKnownPeerIds`，`SELECT DISTINCT convId`）→ 老版本升级上来主界面不空；Room 访问全 runCatching（异常不杀接收循环）。
  - **会话页网络状况实时显示**：ConversationScreen 顶部新增对方状态指示——绿点"对方在线 · 消息即时送达"/黄点"正在寻找对方…"/黄点"对方断线重连中…"/灰点"对方离线 · 消息将排队待对方上线"（来自 peers.presence，1s 心跳驱动）。用户可实时判断消息去向，与程序协商机制互相印证。
  - **滚动加固**：首次滚底后延迟 80ms 再滚一次（布局未完成导致"滚一段距离滚不动"）。
- **v0.16.0 三处修正**（用户反馈：状态可更灵敏、自动滚动"进的一瞬间到底又弹回顶"（性能好布局太快被后续重组覆盖）、最近对话也要三色+持久化效果）：
  - **状态更灵敏**：`LOST_HEARTBEAT_MS` 3s→**2s**（容忍 1 帧丢失）、`OFFLINE_THRESHOLD_MS` 30s→**15s**——失联/离线反映快一倍，双端状态更快对称收敛。
  - **滚动彻底修复**：滚底改为「轮询确认」——每 40ms 滚一次并校验 `lastVisible >= totalItemsCount-1`，被重组拉回顶立即重滚，连续 2 次停底才算稳定（最多 1.6s）——根治"性能太好、一进就在顶部/弹回顶"。
  - **最近对话三色 + 持久化兜底**：`ChatPreview` 新增 `presence`（与节点列表同款绿/黄/灰）；`observeConversations` 改为 `sessions ∪ 消息历史反推的对话`（`MeshStore.observeConversationIds`，Room Flow 流式响应）——即使会话关系持久化丢失/重装，最近对话列表也不空；`PresenceAvatar` 改收 `PeerPresence` 三色渲染；REACHABLE/QUEUED 分区随状态实时切换（在线绿进「最近对话」，寻找中/重连/离线黄灰进「等待路由」）。
- **v0.17.0 四连修**（用户反馈：发送状态仍有 bug 要再提容错、等待路由不显示昵称、进软件要自动寻找、滚动"进的一瞬间到底又弹回顶"依旧）：
  - **送达确认再强化（"只要在发送就不断确认"）**：重发退避 5s→**3s** 起步、封顶 60s→**30s**（确认频率翻倍）；接收方重复回执窗口 60s→**3min**（覆盖长时间后台空窗）；**PONG 也触发即时重发**（原仅 PING，确认机会翻倍）；新增 `MeshService.resendPendingNow()`——**MainActivity.onResume 回前台立即扫一遍未确认消息**（重进软件马上确认，不等退避计时）。
  - **进入软件自动开始寻找**：根因——服务启动已装配（onCreate→前台服务→service.start()），但**服务被系统回收后回前台不重启**（onResume 未调）。修复：onResume 调 `startMesh()`（幂等）+ `resendPendingNow()`；Mesh 页空态文案改「正在扫描邻近节点…」（进入即自动扫描，无需手动点按钮）。
  - **滚动弹回顶根治**：根因——ViewModel `messages` 流在目标会话为 null 时 fallback `conv-ME`，进入会话瞬间短暂渲染上个会话/自环消息，列表 size 突变使 LaunchedEffect 反复重启。修复：**目标为 null 时发射空列表**（`flowOf(emptyList())`），进入会话只出现该会话消息；滚动循环改「先滚（suspend 等完成）→ 等一帧校验 → 被拉走立即重滚，连续 4 次稳定停底才算完成（上限 5s）」。
  - **等待路由昵称**：名字缺失的根因是"从未被 PING/扫描记录昵称"（扫描帧不带名、协议限制）；自动扫描 + 1s 心跳上线后昵称 1s 内补上（随 B 自动寻找一并解决）。
- **v0.18.0 硬实时同步三连**（用户反馈：等待路由昵称仍未显示、删后台重进不自动搜索、已送达卡 SENDING 要"每次心跳检查同时确认对方消息"）：
  - **送达确认搭心跳便车（硬实时）**：协议 `PresenceBody` 新增 `ackIds: List<String>`——**PONG 随心跳携带"本机已收到的对端消息 id 列表"**，发送方收 PONG 立即标记 DELIVERED 并移出重发队列（先消化回执再重发剩余，避免刚确认的消息被重复发送）；PING/PONG 每 1s 双向互换，**确认复用已验证通畅的心跳通道，彻底绕开独立 RECEIPT 广播在 BLE 上的丢帧**——这就是"每次检查对方状态时同时回应对方正在发送的消息"。原 RECEIPT 广播机制保留为冗余通道。
  - **昵称随消息传播**：协议 `TextBody` 新增 `displayName`——sendText/sendInvite 携带本机昵称，**接收方 route Deliver 时 markSeen 即学昵称并落库**（不再依赖 PING 时序），对话列表/等待路由立刻显示名字、重启可恢复。
  - **删后台重进自动搜索（最硬版）**：`MeshChatApplication.onCreate` 直接 `service.start()`（进程一启动即扫描/心跳；此刻 Activity 未建、Android 12+ 限制前台服务后台启动，故只启 Mesh 本体，前台服务由 MainActivity onCreate/onResume 补上，幂等）。
- **v0.19.0 重启后收不到回执根治**（用户：一方删后台重进后发消息，发送方卡"正在推送"直到再次重启——"再删后台重进又同步了"）：
  - **根因**：BLE notify 回传通道依赖 server 端 `subscribedDevices`（CCCD 订阅记录）。**一方进程被杀后重连时 CCCD 订阅写入经常丢失**（Android GATT 老毛病），导致对端 server 认为"无人订阅"，回执/PONG 的 notify 被静默丢弃 → 重启方永远收不到送达确认，直到**第二次**重启把订阅补上才恢复——与"再删后台重进又同步"完全吻合。
  - **修复（收到帧即视为可回传，硬逻辑）**：`BleTransport.onCharacteristicWriteRequest` 收到对端任何写帧时**无条件登记 serverDevices + subscribedDevices**——收到帧本身证明链路活着，回执沿"刚收到消息的链路"反向 notify 发回，**不再依赖 CCCD 订阅是否写成功**；配合对端 central 侧 `setCharacteristicNotification(true)` 已调用，notify 必达。
  - **死连接清理**：写失败且链路已断（`getConnectionState != CONNECTED`）→ 移除并 close 死 GATT（防残留占用，下次扫描重建）；notify 失败 → 移出订阅记录（对端下次写帧自动重新登记）。
  - **CCCD 写入重试**：central 侧 `writeDescriptor` 失败 → 200ms 后重试一次。
- **v0.20.0 广播确认通道（送达确认第三通道，物理级硬同步）**（用户：v0.19.0 仍无效且单方重启无法恢复，需双方同时重启或等很久——GATT 连接状态问题在 Android 上不可静态修复）：
  - **根因升级**：送达确认依赖 GATT 连接（notify/写通道）。一方进程被杀后，其与对端的连接在 Android 栈内进入异常状态，单方重启无法重建可用回传通道 → 确认永远丢失。
  - **方案（彻底绕开连接状态）**：把确认信息放进**蓝牙广播/扫描通道**——广播与扫描常开、**不需要任何 GATT 连接**，只要两台设备在无线电范围内且都在扫描，确认必然可达。
  - **实现**：`BleTransport.startAdvertising()` 增加**扫描响应（scanResponse）**，用独立 Service Data UUID（`ACK_UUID=0xA5E3`，与短 ID 广播互不干扰、老版本兼容）携带本机已收消息的**4 字节压缩确认键**（最多 6 个，`msgId.hashCode()` 低 4 字节，跨进程确定性一致）；`onScanResult` 解析对端扫描响应里的确认键 → 通过 `MeshPeerInfo.ackKeys` 上抛；`MeshService` 收到消息后 `transport.refreshAdvertising()`（stop+100ms 后重启广播刷新确认键）；发送方 `confirmByAckKey` 命中待确认消息即标记 DELIVERED。
  - **三层确认冗余**：RECEIPT 广播（GATT）→ PONG ackIds（心跳，GATT）→ **扫描响应广播确认（无连接依赖）**——任一层到达即确认送达。
  - `MeshTransport` 接口新增默认方法 `setAckProvider`/`refreshAdvertising`（测试替身零改动）；`MeshChatApplication` 注入 `transport.setAckProvider { service.broadcastAckKeys() }`。
- **v1.0.0 正式版发布**（用户确认"功能基本都实现了"）：核心功能齐备——近场通信全链路（发现/握手/会话/消息/文件传输/心跳校准/三色状态/节点持久化/后台常驻/通知弹窗/送达三层确认冗余）。**已推送 GitHub origin/main（本地领先 41 提交全部同步，含 v0.13.1~v1.0.0）。**
- **v1.0.1 信号格数阈值调整**（用户指定）：|RSSI| ≤75 满格、≤85 两格、≤100 一格、>100 零格（原 -60/-75/-90 过严）——`BluetoothQuality.bars()` 更新，Mesh 页 SignalBars 同步生效。
- **v1.0.2 对话 UI 头部压缩**（用户反馈"会话建立/对方在线两条横条太占空间"）：移除会话页两个独立状态横条，**合并进标题栏名字下方一行**（圆点+文字：未建立会话=琥珀"等待对方接受对话请求…"；已建立=绿/黄/灰按对端在线/寻找/重连/离线）；`ConversationHeader` 新增 `connected`/`peerPresence` 参数，删除无用 Lock import。
- **v1.0.3 Mesh 拓扑图重构为力导向网状图**（用户要求"参考 bitchat 网状风格、去中心化、可自由移动、高科技"）：替换 `MeshScreen.kt` 原 5 节点硬编码静态拓扑（Canvas 固定坐标、无交互），改为**力导向布局 + 可拖拽 + 三色制**。参考 bitchat 1.6.0 "live topology map" 风格，经 HTML 原型 5 轮迭代定稿（`mesh-screen-preview.html`）。
  - **力导向物理引擎**（`topologyPhysicsStep`）：库仑斥力 700 + 边弹簧 0.014/48px + 阻尼 0.9 + 微扰 0.015 + 限速 2px/帧 + 边界反弹 margin 30。**无中心引力**，节点自然分布；本机参与物理不固定（v1.0.x 因直连所有 peer 会偏中心，v1.1.0 多跳中继实装后本机连接度降低自然漂边）。
  - **三色制**：已会话=绿(DIRECT, MeshGreen)、在线未会话=蓝(REACHABLE, Cyan)、离线=灰(STALE, TextSecondary)；本机=Cyan 实心+下三角；节点半径按 hops 递减(7/6/5)；边按节点状态着色（绿实线/蓝淡实线/灰点线）。
  - **交互**：拖拽节点（`detectDragGestures`，命中检测 r+12）、短按选中（白色外圈光晕 + 其他节点变暗 α0.4）、点击空白取消。
  - **数据驱动**：`MeshTopology(peers, sessions)` 直接消费现有 `List<MeshPeer>` + `Set<String>`，无新数据模型。v1.0.x 三色映射：sessions 包含→绿、presence==OFFLINE→灰、其余→蓝。
  - **点阵网格背景**（Cyan α0.06，24px 间距）+ 圆角 12dp 容器 + 底部统计行（已会话/在线/失联计数）。
  - **后端接口预留**：v1.1.0 多跳中继实装后，只需扩展节点合成逻辑（读 `routeEntries` 补充 REACHABLE 节点 + RELAY 边），UI 零改动。设计规格：`docs/superpowers/specs/2026-08-03-mesh-topology-graph-design.md`。
- **v1.0.13 蓝牙重搜强制重建**（用户反馈：两机先关蓝牙进软件→开蓝牙→点"重新发现"仍互相搜不到，必须重进一台才能搜到）：根因——进入 App 时蓝牙未开，`BleTransport.start()` 静默失败（runCatching 吞掉）但 `MeshService.started` 已置位；开蓝牙后点重新发现→`service.start()` 幂等守卫直接 return，BLE 永不重建。修复：新增 `MeshService.restartDiscovery()`（`transport.stop()+start()` 强制重建传输层，连接/订阅/队列全清），`MeshRepository.startDiscovery()` 改调 `service.start()+restartDiscovery()`——**"重新发现"按钮现在会强行搜索**。单测 +1（restart discovery rebuilds transport）。
  - 规格：`docs/superpowers/specs/2026-08-03-meshchat-presence-background-design.md`；计划：`docs/superpowers/plans/2026-08-03-meshchat-presence-background.md`。
  - 规格：`docs/superpowers/specs/2026-08-03-meshchat-rfcomm-transport-design.md`；计划：`docs/superpowers/plans/2026-08-03-meshchat-rfcomm-transport.md`。
- git 历史：基线 `d138496` → 远程合并 `4d25192` → 设计规格 `3aa4fd4` → 计划 `75dddb0` → 任务 0-11 共 12 个实现提交（最新 `b6a2d2c`）→ 联调提交 `fd10d7d` → 交接块 `ab2f287`/`067618b` → v0.11.0 修复 `9e22674` → v0.12.0 文件传输 8 提交（`a8bdf2b`~`23172bb`）→ v0.13.0 RFCOMM 5 提交（`21a3b62` 分帧 → `c3969cb` transport → `3493324` sendFrame 注入 → `efe9d32` 双传输集成 → 任务 5 装配/交接待提交）→ v0.13.1 RFCOMM 停用 `e8911fb` → v0.14.0 7 提交（`bf96fe7` 协议/身份 → `728a228` upsertPeer → `62b0ff3` 会话持久化 → `b53aa1d` 心跳 → `a5b41b7` 前台服务 → `37e084b` UI → `e356ce6` 装配/交接）→ v0.14.1 卡 SENDING 修复 `e09ea94` → v0.15.0 体验三件套 `36ac5cc` → v0.15.1 节点持久化/滚动/即时重发 `e922dd0` → v0.15.2 零容错 `84fbcd7` → v0.16.0 灵敏度/滚动轮询/最近对话三色持久化 `3608afb` → v0.17.0 确认强化/自动寻找/滚动根治 `b0f9e0d` → v0.18.0 心跳确认搭便车/昵称随消息/Application 启动 `2625b62` → v0.19.0 收到帧即登记可回传/死连接清理/CCCD 重试 `f6ea4f6` → v0.20.0 广播确认通道 `cf722dc` → v1.0.0 正式版发布 `92d03b2` → v1.0.1 信号格数阈值 `c7a9a67` → v1.0.2 对话 UI 头部合并 `1449bcd` → v1.0.3 拓扑图力导向重构 `0248c28` → v1.0.4~1.0.10 拓扑图体验调优/拖拽重写（`bc2e728`/`941c040`/`8b37e40`，前端实装）→ v1.0.13 蓝牙重搜强制重建 `a862af2` → v1.0.14 markSeen 抖动修复 `5b3e5ec` → v1.0.15 拓扑图 mesh 设计恢复 `81a9c5a` → v1.0.16 帧到达即刷新+信号时间 `029a71b` → v1.0.17 信号时间毫秒精度 `fd5d273`（已推送 origin/main）→ v1.0.18 拓扑图四项优化 `b254d39` → v1.0.19 三项修复 `348b0f5` → v1.0.20 失联不显示+再缩 `c4f94cd` → v1.0.21 延迟秒数移位 `aa59f5d` → v1.0.22 正方形+等比缩小 `3337de6` → v1.0.23 全宽正方形+真缩小 `8193b91` → v1.0.24 蓝牙关→开自动重建 `360374c`（v1.0.18~24 已全部推送 origin/main）→ v1.0.25 release 签名/R8 混淆 `9d0f373` → v1.1.0 多跳中继 `9d7c62c`（已全部推送 origin/main）→ README v1.1.0 `e0cb500` → 贡献指南 `ecf57b0`。
- **v1.1.1 队友修复融合（2026-08-04，zip: `MeshChat-v1.0.12-complete-project.zip` 解包对比）**：用户提供队友独立开发线（v1.0.11/v1.0.12），要求"不干扰主线程逻辑、不改拓扑图效果、尽可能融合进主线"。7 项逐项移植（全部为稳定性修复，未改动协议/心跳/路由/拓扑渲染逻辑）：
  - **#1 键盘适配**：`AndroidManifest.xml` MainActivity `windowSoftInputMode="adjustResize"` + `ConversationScreen.kt` Header `statusBarsPadding()`（原固定 top=44dp 会被状态栏遮挡）。
  - **#2 服务重启**：`MeshService.stop()` 去掉 `scope.cancel()`（原 stop 后 scope 已取消，再次 start 无法 launch → 服务停后不能重启），改 `transfer.cancel()` 只清活动传输；加注释说明。
  - **#3 通知订阅单例**：`MeshChatService` 前台服务 `peers.collect` 用 `Job?` 单例（原每次 onStartCommand 都订阅新 collector → 内存/通知刷新负担）。
  - **#4 缓存清理**：`MeshService` 新增 `prunePersistentCaches(now, force)`（启动 force 一次 + tick 每 6h 节流）——清过期 outbox（`expireAt<=now`）+ 30 天未见节点（`lastSeen<now-30d`）；`MeshStore`/`OutboxDao`/`PeerDao`/`InMemoryMeshStore`/`RoomMeshStore` 补齐 prune 方法；**拓扑图 `MAX_TOPOLOGY_PEERS=48`**：`MeshScreen.kt` 拓扑绘制前 `sortedWith(会话>在线等级>shortId).take(48)`——仅截断绘制节点数防 O(n²) 卡顿，节点位置由 existing 保留，渲染效果零改动（含拓扑图 48 上限，列表/统计行仍用完整 peers）。
  - **#5 Room v1→v2 迁移**：`MeshDatabase` version 1→2 + `MIGRATION_1_2`（仅加 4 个查询索引：messages convId+ts / messages status+kind / outbox expireAt / peers lastSeen），无损。
  - **#6 文件传输**：`FileTransferManager` 加 `cancel()`（停活动传输）、`acknowledgeCompletedFile()`（对已保存文件只回 FILE_ACK 空 missing，重启后对端重传不重复落盘）、`cleanupOrphanedTemporaryFiles()`（init 清 .part 残留）；`sendFile` 的 `senderJob = scope.launch{...}`（此前未赋引用，cancel 无效）；`finish` 同步置空 `senderJob`；`MeshService` FILE 分支 `alreadySaved` 检测命中即 `acknowledgeCompletedFile` + return。
  - **#7 接收流异常隔离**：`MeshService.start()` collector 逐帧 `runCatching { handleFrame(frame) }`（原一帧抛异常 → 整个 collect 协程结束 → 接收流永久停），RFCOMM 合流 collector 同步处理。
  - **测试适配**：`MeshServiceTest.markSeen does not override other peers presence` 种子 `lastSeen=0` 与新 prune 语义冲突（0 < now-30d 被启动时 force prune 剪除 → 断言 null）→ 种子改 `System.currentTimeMillis()`，断言不变（队友同类测试即用当前时间）。
  - **版本/文档**：versionCode 63 / versionName 1.1.1；README 特性加「健壮性（v1.1.1）」一条；`.gitignore` 加 `*.zip`/`*.bundle`（队友包/备份不入库）。
  - **未融合（明确保留主线程现状）**：拓扑图排序基准/节点大小/布局常量全部保留主线版本；队友的 65 项测试未并入（主线 72 项全绿即可）。
- **v1.1.2 崩溃修复（2026-08-04，用户实测"安装后打开直接卡退"）**：根因——融合 v1.1.1 时**只移植了 MIGRATION_1_2 的 CREATE INDEX SQL，漏了 Entities.kt 的 @Entity 索引声明**（队友版本二者同步，主线漏了实体）。Room 迁移后按 @Entity 推导的期望 schema **精确校验**迁移结果：期望 `indices=[]` vs 实际 `indices=[index_messages_convId_ts, index_messages_status_kind, index_outbox_expireAt, index_peers_lastSeen]` → `IllegalStateException: Migration didn't properly handle` → 数据库打开即崩（`Application.onCreate` 的 runCatching 吞掉首次异常，但 `by lazy` 缓存异常，后续任何 store 访问必抛 → UI 启动即闪退）。**为何单测全绿**：单测走 InMemoryMeshStore，不碰 Room。**为何"全新安装不崩、覆盖升级才崩"**：全新安装走 createAllTables 无迁移；升级触发迁移+校验。修复：[Entities.kt](file:///e:/MeshChat%20Project/app/src/main/java/com/meshchat/app/mesh/storage/Entities.kt) 补回 4 个 `@Entity(indices=[...])` 声明，与迁移 SQL 完全一致（表名/列名/索引名/顺序，验证：生成代码 `MeshDatabase_Impl.kt` createAllTables 的 CREATE INDEX 与 MIGRATION_1_2 逐条一致）。迁移 SQL 幂等（IF NOT EXISTS）+ 迁移失败不损坏 DB → **用户无需清数据，覆盖安装 v1.1.2 即可正常迁移**。
- **v1.1.3 队友二轮改良融合（2026-08-05，zip: `MeshChat-complete-20260805-final.zip`）**：队友基于主线 v1.1.2（基线验证：Entities/BleTransport/FileTransferManager 等核心文件与主线逐字节一致，仅行尾符差异），用户决策全收。融合方式：robocopy 覆盖 `app/src` 后，用 `git checkout --` 恢复 25 个纯行尾符假差异文件，最终差异 = 19 个修改 + 8 个新增（与 ignore-cr diff 精确一致）。**核心传输/协议/路由/心跳逻辑零改动**。
  - **安全中心（新增 `security/` 包 11 文件 + SecurityCenterScreen + 9 个测试套件 37 项）**：① `SecurityCapabilityManager`——权限/能力状态机（BLUETOOTH/NOTIFICATIONS/INTEGRITY_CHECK/VPN_SCAN/ENTERPRISE_MANAGEMENT），拒绝 24h 冷却，**启动不弹权限窗**（仅用户从 UI 主动请求）；② `LocalSecurityCoordinator`——纯本地评估（debuggable/锁屏/无障碍/调试器 6 类离线信号，无网络/VPN/Play Integrity 依赖），风险分类器纯 JVM 可测；③ `EncryptedSecurityEventStore`——**真 AES-256-GCM**（AndroidKeyStore 不可导出密钥 + 每次写新 IV + noBackupFilesDir + AtomicFile），证据字段白名单（`RedactedEvidencePolicy` 仅 6 类 key，防消息内容/密钥入库）；④ `SecurityScanSessionController`/`IntegritySignalMapper`——**纯占位状态机**（无 VpnService、不实际调 Play Integrity，未来可接）；⑤ `MeshTransportSecurity` 诚实标注 `legacy-plaintext-v1`（防 UI 假宣称加密）。UI：设置→「安全中心」页（能力状态/本地评估/删除本地安全历史）。
  - **聊天体验**：① 对话列表改由 `observeAllMessages` 推导——显示**最后消息内容+时间**、按最近排序（原"已建立对话"占位）；② 对话行 ⋮ 菜单——**归档/取消归档**（`ConversationPreferences` 持久化）+ **删除对话**（确认弹窗 → `deleteConversation` 删消息 + `MeshService.removeSession` 解除会话）；③ **未读标记**（readTimes 持久化，打开会话 markRead）；④ Mesh 页列表分「附近节点（lastSeenAt>0 且非 OFFLINE）/历史连接（已会话不在附近）」——**拓扑图本体零改动**（用户约束）；⑤ 设置页昵称**有会话才能改**。
  - **安全加固（Manifest）**：`allowBackup=false`（禁云备份，与安全中心 noBackup 配套）、`usesCleartextTraffic=false` + `network_security_config.xml`（禁明文+仅系统 CA，无 pin）、POST_NOTIFICATIONS 权限（仅用户主动请求）、`WRITE_EXTERNAL_STORAGE` 限 API≤28、新图标 `ic_launcher_meshchat.png`（1.4MB，mipmap-nodpi，替换默认图标）。
  - **基础设施**：`InMemoryTransport.foundPeers` 加 `replay=1`（测试稳定：新 collector 不丢最近发现）。
  - **测试**：+37 项（security 9 套件），总 **109/109 通过**；build.gradle.kts 零差异（安全中心无新依赖）。
  - **未融合（刻意排除）**：无——用户三项决策全收。
- **v1.1.4 删除对话后节点不消失修复（2026-08-05，用户实测）**：根因——队友的 `deleteConversation` 只删消息（`store.deleteConversation`）+ 解除会话（`service.removeSession`），**不碰 peerEntries / peers 表** → Mesh 页"附近节点"计数不变（在线节点物理仍在扫描结果里）。修复：① `MeshStore.deletePeer(shortId)`（PeerDao.remove 已存在，补齐接口 + InMemory/Room 实现）；② `MeshService.removePeer(peerId)`——从 `peerEntries` + 2 跳 `routeEntries` + peers 持久化缓存移除并 `refreshPeers()`（立即从 UI 消失、重启不恢复）；③ `MeshRepository.deleteConversation` 组合调用。**物理限制**：节点若真在附近，扫描/心跳数百毫秒内会重新发现并重新入表（这是真实存在，非缓存残留；如需"永久忽略"应另做黑名单功能）。单测 +1（removePeer removes node from peers flow and store，覆盖删除/持久化/重新发现三态），总 **110/110 通过**。
- **v1.1.5 调试中心实装（2026-08-05，规格 `docs/superpowers/specs/2026-08-05-debug-center-design.md` + 计划 `docs/superpowers/plans/2026-08-05-debug-center.md`）**：
  - **统计内核 `mesh/debug/DebugStats.kt`（纯 Kotlin 无线程）**：每事件记 (ts, bytes) 入队（保留 20s），`snapshot(windowMs)` 同步聚合——**速率窗口 1-10s 任意调节即时生效**（窗口计数非破坏性，可动态放大）；并发安全（队列级锁 + ConcurrentHashMap）；`attachProviders` 由 MeshService 注入实时状态读取器（待确认数/节点表/路由表/文件进度/服务状态/蓝牙开关），内核不依赖服务。
  - **埋点（每处 1-2 行原子累加，零侵入收发时序）**：MeshService（`recordSentFrame` 统一出口：sendFrame/broadcastData/sendReceipt/PING/PONG/INVITE_ACK/重发；接收在 handleFrame；路由决策 DELIVER/FORWARD/DROP + 中继转发计数；回执确认三处：handleReceipt/confirmByAckKey/PONG ackIds）+ FileTransferManager（FILE 块/FILE_ACK 收发、窗口重试）+ BleTransport（广播/扫描/GATT 连接成败/MTU/写与 notify 成败/服务发现/收到写请求）+ MeshTransport 接口加 `bluetoothEnabled()` 默认方法。
  - **UI `DebugCenterScreen`（设置→调试中心入口）**：五板块（收发包速率明细/ BLE 传输层/ 信号与路由/ 送达链路/ 文件传输）+ 底部系统栏；**7 项调节**（刷新间隔 0.5-5s、速率窗口 1-10s、包/s↔包/min、板块显隐、节点排序 RSSI/昵称/最近、暂停/继续、清零）；ViewModel 刷新循环按调节项驱动，数据全内存态重启清零。
  - **测试**：DebugStatsTest 5 项（计数/窗口速率/清零/重发分桶/聚合）+ MeshServiceTest +2（收发帧统计、回执确认），总 **117/117 通过**。
- **v1.1.6 启动崩溃修复（2026-08-05，用户实测 v1.1.5"打不开，屡次停止运行"）**：根因——**Manifest 自 v0.14.0 引入前台服务起就缺失 `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_CONNECTED_DEVICE` 权限**（git 历史核对 a5b41b7/22ef6b9/HEAD 三代 Manifest 均无）。本项目 targetSdk 36：① Android 9+（targetSdk≥28）`startForegroundService()` 缺 `FOREGROUND_SERVICE` 抛 `SecurityException`；② Android 14+ 强制 FGS type 权限，`MeshChatService.startForegroundCompat()`（API 34+ 分支显式传 `FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE`）缺对应 type 权限同样抛 `SecurityException`。该调用链在 `MainActivity.onCreate`→`ensureBluetoothAndStart()`→`startMesh()`→`startForegroundService()` 主线程无 runCatching 保护 → **首帧即崩，App 打不开**。修复：`AndroidManifest.xml` 补两个 uses-permission（normal 级，声明即授予，无需运行时请求）。**为何单测全绿**：单测不启动 Android 组件，Manifest 权限属纯系统校验。
- **v1.1.7 启动崩溃中间排查（2026-08-05）**：曾假设 CME（主线程快照遍历 `peerEntries` LinkedHashMap vs 后台 200ms 心跳并发写）为根因并修复（改 ConcurrentHashMap + try-catch + provider runCatching）。**该假设被 v1.1.8 的模拟器复现推翻——CME 修复不是根因，但改动无害且合理（并发安全增强），保留**。
- **v1.1.8 启动崩溃真根因（2026-08-05，Android 16 模拟器复现确认）**：**Kotlin 属性初始化顺序**。`MeshChatViewModel` 的 `init` 块在 `_debugSettings`/`_debugSnapshot` 属性声明**之前**执行；`startDebugLoop()` 里 `viewModelScope.launch` 使用 **Dispatchers.Main.immediate**，在 init 中启动会**同步立即执行**协程体到第一个挂起点 → 首轮 `_debugSettings.value` 读取到**尚未初始化的 null** → `NullPointerException: MutableStateFlow.getValue() on a null object reference` → 协程异常未捕获 → 主线程崩溃 → **启动即崩（界面都没进去）**。崩溃栈：`MeshChatViewModel$startDebugLoop$1.invokeSuspend(113 行)` → `<init>(81 行)`。**为何 v1.1.5 起崩**：v1.1.5 才加 startDebugLoop；v1.1.3/4 无此代码正常。**为何单测全绿**：单测不构造 MeshChatViewModel。**为何 v1.1.7 try-catch 未挡住**：try 放在 `val s = _debugSettings.value` 之后，NPE 发生在 try 外。**修复**：① 调试中心属性声明块（DebugSettings/_debugSettings/debugSettings/_debugSnapshot/debugSnapshot）**整体移到 init 块之前**（Kotlin 按声明顺序初始化）② `startDebugLoop` 循环体 try-catch **覆盖整个循环体**（含设置读取），任何迭代异常只跳过本轮。**模拟器验证**：Android 16（API 36，targetSdk 36 同版本）headless 模拟器安装 `MeshChat-v1.1.8-debug.apk` 启动 → **无 FATAL EXCEPTION + 进程持续存活**（v1.1.7 同镜像复现 NPE 崩溃，v1.1.8 通过）。**重大教训**：`viewModelScope.launch`（Main.immediate）在 init 里同步执行协程体，init 中访问声明在其后的属性必崩；后续在 ViewModel init 中启动协程必须先确认属性声明顺序。
- **v1.1.9 调试中心主动控制（2026-08-05，规格 `docs/superpowers/specs/2026-08-05-debug-center-active-control-design.md` + 计划 `docs/superpowers/plans/2026-08-05-debug-center-active-control.md`）**：在纯观察统计之上增加真实发送节奏/发现链路的主动操控：
  - **控制总线**：`mesh/debug/DebugControl.kt` sealed class 命令集（SetHeartbeat/SetResendPolicy/SuspendSignaling/ResumeSignaling/BroadcastPing/ResetControls）；DebugStats 加 `attachControls/issue` 纯转发，内核不持有服务引用。
  - **MeshService 控制面**：4 个 `@Volatile` 参数（heartbeatIntervalMs/lostHeartbeatMs/resendBaseMs/resendMaxMs，默认值=原常量，未调节零变化）+ 6 个公开方法（setHeartbeat/setResendPolicy/suspendSignaling/resumeSignaling/broadcastPing/resetDebugControls，setter 均 coerceIn 钳制）；heartbeatTick 节流、失联状态机、重发退避公式改读 volatile；`start()` 注册 handler。
  - **发现层暂停/恢复**：MeshTransport 接口加 `suspendDiscovery/resumeDiscovery` 默认方法；BleTransport 覆写（只停/启 advertise+scan，保留 GATT server/clients 与已建立连接收发）；InMemoryTransport 覆写 + discoverySuspended 断言位。
  - **UI ControlCard**（DebugCenterScreen 第六板块「主动控制」）：心跳 0.5s/1s/2s/5s（失联阈值联动×2）、重发基础 3s/10s/30s（封顶 30s/60s/120s）、暂停/恢复切换、手动发 PING + 上次发送反馈、恢复默认；板块显隐可调。
  - **心跳↔失联联动**：`lostMs = intervalMs * 2`，UI 下发成对命令；对端未同步调节时状态显示不对称（调试场景可接受，恢复默认兜底）。
  - **测试**：DebugStatsTest +1（issue 转发）+ MeshServiceTest +3（心跳间隔/重发退避/暂停恢复），总 **121/121 通过**。
  - **约束**：协议/路由/存储零改动；全部内存态重启回默认。
- **v1.1.10 调试中心高频心跳 + 失败包（2026-08-05，用户要求"心跳最低 0.05s + 显示收到的包与失败包"）**：
  - **独立心跳协程**：`heartbeatJob`（`delay(heartbeatIntervalMs.coerceIn(50, 10_000))` 循环 + `sendPingIfDue()`），PING 广播与 200ms tick 完全解耦——心跳档位最低 **0.05s（50ms）** 真实生效；`heartbeatTick` 移除 PING 发送（仅保留状态机/回执/路由/refreshPeers）。**物理边界**：BLE 广播受系统约 100ms 最小间隔限制，50ms/100ms 档在已建立 GATT 连接通道（写/notify）上真实生效，广播通道被系统合并。`setHeartbeat` 下限放宽（interval 50ms / lostMs 100ms）。
  - **失败包统计**：`DebugStats.recordReceivedFailure()` + `FailedStats`（receivedDecodeFailures/速率、unconfirmed=待确认 pending、bleWriteFailed、bleNotifyFailed）并入 DebugSnapshot.failures；MeshService handleFrame 解码失败分支埋点；reset 清理。
  - **UI**：「失败包」板块（FailureCard，解码失败/送达不可确认/BLE 写与 notify 失败，非零琥珀色高亮）+ 心跳档位扩至六档 0.05s/0.1s/0.5s/1s/2s/5s（失联阈值 ×2 联动）+ 板块显隐 showFailure。
  - **测试**：DebugStatsTest +1（失败包聚合）+ MeshServiceTest +1（50ms 高频档），旧心跳测试（heartbeatTick 驱动）改用 `sendPingIfDue`（独立协程语义）；总 **123/123 通过**。
- **v1.1.11 心跳档位收敛（2026-08-05，用户实测后调整）**：① 心跳只保留 **0.05s/0.1s/0.2s/0.4s 四档**（移除 0.5s/1s/2s/5s）② **失联阈值固定 2s 默认，不再自主调节/联动**——`DebugControl.SetHeartbeat(intervalMs)` 单参数化，`MeshService.setHeartbeat(intervalMs)` 不再修改 `lostHeartbeatMs`（字段保留默认 LOST_HEARTBEAT_MS=2s）；UI StatRow 显示"失联阈值 2s（固定）"。默认心跳 1s 不在四档内（恢复默认后无选中态，符合预期）。测试适配（DebugStatsTest/MeshServiceTest 单参数）。
- **v1.1.12 主动控制面板布局修复（2026-08-05，用户反馈"调节频率下面一大块空白"）**：**根因**（Android 16 模拟器 uiautomator 坐标实测确认）——重发退避 3 个 FilterChip 长 label（"基础3s·封顶30s"等）在 Row 中溢出，第三个 chip（"基础30s·封顶120s"）被异常拉伸（bounds y=1505..1717 高 212px，正常 chip 高 53px），造成该区域大块留白。**修复**：① 重发退避 FilterChip 改短 label（3s/10s/30s），封顶值移入 StatRow 汇总行 ② 面板整体改为高密度「当前生效配置」汇总（心跳/失联阈值固定/重发基础/封顶 两行 StatRow）③ 手动 PING 常显（未发送时显示"未发送"）+ 新增累计次数统计（DebugControlState.manualPingCount）。**模拟器复验**：修复后 3 个重发 chip 全部 y=1521..1574 高 53px 单行，无异常拉伸，布局紧凑。**教训**：Compose Row 内 FilterChip label 过长会溢出并把子项异常拉伸（Row 不换行），面板内 chip label 应保持简短，长信息放 StatRow 汇总行。
- **v1.1.13 调试中心示波器（2026-08-05，用户要求"bitchat 风格可视化矩形图示，类似示波器"）**：
  - **数据采样**：`MeshChatViewModel` 新增 `OscPoint(sentRate, recvRate, failurePulse)` + `oscHistory`（StateFlow，环形 96 点，内存态重启清零）；`startDebugLoop` 每轮 snapshot 后追加总发送/接收速率 + 失败脉冲（解码失败 + BLE 写/notify 失败与上次累计的差值，强度 0-3）。
  - **UI `OscilloscopeCard`**（DebugCenterScreen 新板块，位于收发包之后）：Canvas 示波器——深色底 + 12×4 青色网格、绿线发送速率、蓝线接收速率（y 轴按历史峰值动态缩放）、琥珀竖线失败脉冲（高度∝强度）、右侧扫描头亮点；顶部 StatRow 实时值（↑发送 ↓接收 · 脉冲总数）；板块显隐 showOsc。
  - **传参链**：MeshChatApp collect oscHistory → MeshChatHome → DebugCenterScreen。
  - **验证**：Android 16 模拟器实测——示波器板块正常渲染（标题 + "↑1.0/s ↓0.0/s · 脉冲 0"，发送线来自 1s 心跳 PING），进程无崩溃；单测 123/123 通过（采样在 ViewModel 层，无新增测试）。

### 已验证内容
- **v1.1.13 示波器验证**：`testDebugUnitTest` **123/123 通过，0 失败**；`assembleDebug` **BUILD SUCCESSFUL**（versionCode 75 / versionName 1.1.13）；**Android 16 模拟器实测**：示波器板块渲染正常（标题 + "↑1.0/s ↓0.0/s · 脉冲 0"，发送线来自 1s 心跳），进程无崩溃。APK `MeshChat-v1.1.13-debug.apk`。⚠️ 待用户真机确认波形视觉效果。
- **v1.1.12 主动控制面板布局修复验证**：`testDebugUnitTest` **123/123 通过，0 失败**；`assembleDebug` **BUILD SUCCESSFUL**（versionCode 74 / versionName 1.1.12）；**Android 16 模拟器 uiautomator 复验**：重发退避 3 个 FilterChip 全部单行（y=1521..1574 高 53px，修复前第三个 chip 高 212px 异常拉伸），布局紧凑无空白。APK `MeshChat-v1.1.12-debug.apk`。⚠️ 待用户真机确认。
- **v1.1.11 心跳档位收敛验证**：`testDebugUnitTest` **123/123 通过，0 失败**（SetHeartbeat 单参数化后 DebugStatsTest/MeshServiceTest 全回归）；`assembleDebug` **BUILD SUCCESSFUL**（versionCode 73 / versionName 1.1.11）；APK `MeshChat-v1.1.11-debug.apk`。⚠️ 心跳档位与布局经 v1.1.12 调整。
- **v1.1.10 高频心跳 + 失败包验证**：`testDebugUnitTest` **123/123 通过，0 失败**（DebugStatsTest 7 + MeshServiceTest 47 + 其余回归）；`assembleDebug` **BUILD SUCCESSFUL**（versionCode 72 / versionName 1.1.10）；APK `MeshChat-v1.1.10-debug.apk`。⚠️ 心跳档位经 v1.1.11 收敛为四档。
- **v1.1.9 调试中心主动控制验证**：`testDebugUnitTest` **121/121 通过，0 失败**（DebugStatsTest 6 + MeshServiceTest 45 + 其余回归）；`assembleDebug` **BUILD SUCCESSFUL**（versionCode 71 / versionName 1.1.9）；APK `MeshChat-v1.1.9-debug.apk`。⚠️ 待用户真机验证：主动控制面板各项调节生效（心跳/重发/暂停恢复/手动 PING/恢复默认）。
- **v1.1.8 启动崩溃真根因修复验证（决定性）**：`testDebugUnitTest` **117/117 通过，0 失败**；`assembleDebug` **BUILD SUCCESSFUL**（versionCode 70 / versionName 1.1.8）；**Android 16（API 36）headless 模拟器实测**：v1.1.7 同镜像安装启动 → 复现 NPE 崩溃栈（`startDebugLoop$1` 113 行）；v1.1.8 安装启动 → **无 FATAL EXCEPTION + 进程持续存活**，App 正常进入权限请求/主界面。APK `MeshChat-v1.1.8-debug.apk`。⚠️ 用户真机已确认正常（"OK，正常了"）。
- **v1.1.7 启动崩溃中间排查验证**：`testDebugUnitTest` **117/117 通过**；`assembleDebug` SUCCESS（versionCode 69）。⚠️ 模拟器验证发现非根因（见 v1.1.8），改动（ConcurrentHashMap 等）保留。
- **v1.1.6 启动崩溃修复验证**：`testDebugUnitTest` **117/117 通过，0 失败**；`assembleDebug` **BUILD SUCCESSFUL**（versionCode 68 / versionName 1.1.6，merged manifest 确认含 FOREGROUND_SERVICE + FOREGROUND_SERVICE_CONNECTED_DEVICE）；APK `MeshChat-v1.1.6-debug.apk`。⚠️ 用户实测仍崩（真根因在 v1.1.7 已修，权限修复本身保留）。
- **v1.1.5 调试中心验证**：`testDebugUnitTest` **117/117 通过，0 失败**（DebugStatsTest 5 项 + MeshServiceTest 调试统计 2 项 + 既有 110 项全回归）；`assembleDebug` **BUILD SUCCESSFUL**（versionCode 67 / versionName 1.1.5）；APK `MeshChat-v1.1.5-debug.apk`。⚠️ 待用户真机验证：调试中心各板块实时数据、7 项调节生效、暂停/清零。
- **v1.1.4 删除对话修复验证**：`testDebugUnitTest` **110/110 通过，0 失败**（含新测试 removePeer removes node from peers flow and store）；`assembleDebug` **BUILD SUCCESSFUL**（versionCode 66 / versionName 1.1.4）；APK `MeshChat-v1.1.4-debug.apk`（20,794,387 B）。⚠️ 待用户真机确认删除对话后 Mesh 页节点消失。
- **v1.1.3 队友二轮融合验证**：`testDebugUnitTest` **109/109 通过，0 失败**（18 套件：原 72 + security 9 套件 37 项）；`assembleDebug` **BUILD SUCCESSFUL**（versionCode 65 / versionName 1.1.3，merged manifest 确认）；APK `MeshChat-v1.1.3-debug.apk`（20,794,387 B，含新图标+安全中心）。⚠️ 待用户真机安装验证（行为变更：allowBackup=false、新图标、对话列表显示最后消息、Mesh 页附近/历史分组、昵称需有会话才能改）。
- **v1.1.2 崩溃修复验证**：`testDebugUnitTest` **72/72 通过**；`assembleDebug` **BUILD SUCCESSFUL**（versionCode 64 / versionName 1.1.2）；生成代码 `MeshDatabase_Impl.kt` createAllTables 的 4 条 CREATE INDEX 与 MIGRATION_1_2 逐条一致（期望 schema = 迁移后实际 schema，校验通过）。⚠️ 待用户真机覆盖安装确认不再卡退（无模拟器/真机，迁移路径无法本地自动复现）。
- **v1.1.1 队友融合后回归**：`gradlew testDebugUnitTest` **72/72 通过，0 失败**（含 #2 服务重启后 start 再 launch、#7 逐帧异常隔离、markSeen 夹具更新后全绿）；`gradlew assembleDebug` **BUILD SUCCESSFUL**（versionCode 63 / versionName 1.1.1）。
- `gradlew testDebugUnitTest`：**72/72 测试通过，0 失败**（v1.1.0 新增 7 例中继测试：非会话节点转发 TEXT/中继不落库不通知/RECEIPT 转发一次去重/PING 第 3 次带 relays/路由学习/路由随中继失联过期/心跳不转发；含 v1.0.16 markSeen lastSeenAt 断言、v1.0.14 markSeen 不覆盖、v1.0.13 restart discovery、v0.20.0 广播确认键等全部回归）。BleTransport 为 Android 框架层（无 JVM 单测），以真机复现路径验证。
- `gradlew assembleDebug`：**BUILD SUCCESSFUL**，APK `MeshChat-v1.1.1-debug.apk`（19,192,430 B 级）。
- v1.0.3 编译验证：`compileDebugKotlin` SUCCESS（力导向拓扑图 + 物理引擎 + 拖拽手势 + 三色绘制全部编译通过）。
- **真机三机（A11 GSI / A12 华为 / A16）实测打通**：握手→会话锁定→消息双向到达（MeshSvc 日志确认 `deliver kind=TEXT src=<对端> dst=<本机>` 与 `recv kind=TEXT` 双向出现）。
- **v0.11.0 双人真机聊天正常**（用户确认）：消息方向修复后 A↔B 可正常收发，对端消息显示在左侧、本机消息在右侧，不再是"自己跟自己对话"。

### 当前阻塞
- **⚠️ v1.1.3 融合 + v1.1.4 修复 + v1.1.5 调试中心 + v1.1.6 FGS + v1.1.7 并发 + v1.1.8 崩溃修复（真机已确认）+ v1.1.9 主动控制 + v1.1.10 高频心跳/失败包 + v1.1.11 档位收敛 + v1.1.12 布局修复 + v1.1.13 示波器，待用户真机验证后提交推送**：改动全部在工作区未提交。**待办**：① 用户安装 `MeshChat-v1.1.13-debug.apk` 验证（**重点：示波器波形区域**——绿/蓝速率线随心跳/消息跳动、失败脉冲、扫描头；其余：主动控制面板、失败包、整体回归）；② 确认后 git add + commit + push origin/main。
- **⚠️ 推送阻塞（网络）已解除**：此前 `github.com:443` TCP reset/超时导致的推送积压（v1.0.25 `9d0f373` + v1.1.0 `9d7c62c`）已全部推送成功，本地与 origin/main 同步。GitHub Release（挂 APK）仍可后续用 gh CLI 或网页：仓库 https://github.com/Soodok/MeshChat 的 Releases 页上传 `MeshChat-v1.1.1-debug.apk`。
- **无阻塞**（v1.0.0 已推送 GitHub origin/main，本地与远程同步）。备用源 `soodok.online/meshchat_bare.git` 未同步（如需可 push）。
- 服务器注意：nginx `client_max_body_size` 默认 1M → 上传 bundle 需分块（≤400KB/块）；`/home/wwwroot` 不存在，实际 web 根为 `/var/www/html`。
- **A11（安卓 11 GSI）位置服务**：BLE 扫描依赖位置服务，已 adb 开启（location_mode=3）；若重刷/恢复出厂需重新开启。
- **RFCOMM 已停用（用户决策）**：配对弹窗在华为/GSI 不弹出 + 配对模型对多设备中心拓扑不友好；代码保留未启用。后续提速方向改为 **WiFi Direct**（免配对、中心外设模型天然、吞吐百 MB/s 级，复用 MeshTransport 抽象即可）。
- **v1.0.14 修复 markSeen 批量乐观更新 bug（前端报告已采纳）+ v1.0.15 前端恢复拓扑图设计**：后端按报告 8.1+8.2 修复 markSeen（仅显式更新当前 peer，`_peers.value = map { it.info }` 保留状态机裁决，单测 +1）。前端 v1.0.15 据此恢复此前因闪烁回退的设计：① mesh 骨干边（peer↔peer 互连，`TopoEdge` + `drawMeshEdges` 淡色 Cyan α0.15）② 四色制（新增 `TopoKind.SEARCHING` 黄虚线 + 残存弱弹簧 0.2x）③ 失联断线（STALE 不画边/无弹簧力，仅受斥力漂走）④ peer↔peer 弹簧力（`meshSpringK=0.003`，让图有网状结构不趋向直线）。后端修复后 peers 流稳定，clear+重建不再闪。
- **v1.0.16 帧到达即刷新 + 信号时间显示**（用户反馈"远距离显示连着但实际断了、想要刷新更频繁"）：根因——markSeen 更新 lastSeen 但该值不在 UI 数据里，帧稀疏时 info 内容不变 → StateFlow 不 emit → UI 永远显示在线。修复：`MeshPeerInfo` 新增 `lastSeenAt`（最后收到帧时刻），markSeen/扫描帧每次到达都更新 → **info 必变 → _peers 流必 emit → 帧到达即刷新**；`MeshPeer` 透传，MeshScreen PeerRow 新增 ticker 显示"**信号时间**"。失联 peer 状态不被拉起，抖动不回潮。
- **v1.0.17 信号时间毫秒精度**（用户指定"做成毫秒吧"）：PeerRow ticker 100ms，**<1s 显示 `Xms前`、≥1s 显示 `Xs前`**——近距离帧密集能看到毫秒级跳动（0ms→900ms→归零），远距离断连数字持续增大。
- **v1.0.18 拓扑图四项优化**（用户反馈"整体靠中间/失联 15s 消失/相对大小"）：① **中心引力** `centerK=0.0015`——所有非 pinned 节点向画布中心微弱拉拢，运动时被弹簧/斥力盖过、静止时主导把整体拉回中间（拖拽中跳过）② **失联 15s 移除** `STALE_TTL_MS=15_000`——STALE 节点超时从拓扑图消失（PeerRow 列表仍保留），`TopoNode.staleAt` 记录首次变 STALE 时刻，跨同步继承 ③ **相对大小** `BASE_CANVAS=360`——所有尺寸（节点半径/弹簧长度/边距/字号/线宽/三角/命中半径）基于 `scale = min(w,h)/360` 缩放，适配不同分辨率/屏幕大小 ④ 斥力系数 ∝ scale²（保持视觉一致），margin 80→60（给中心引力更多空间）。测试 64/64 通过，APK 19,192,426 B。
- **v1.0.19 三项修复**（用户反馈"等级取消/拓扑图小一点/失联节点 15s 又出现"）：① **PeerRow 去掉等级标签**——只显示 `${rssi} dBm` + ageText，删 `BluetoothQuality` import ② **拓扑图缩小** `aspectRatio 1f → 0.85f`——高度比宽度短，整体视觉小一点 ③ **STALE 15s 重现 bug 根治**——根因：v1.0.18 的 `staleAt` 存在 `TopoNode` 里，节点被移除后 `existing` 不再包含它，下次同步 `old=null` → `staleAt` 重置为 now → 15s 重计时 → 永远超不了时。修复：用独立的 `staleAtMap: mutableStateMapOf<String, Long>()` 跨同步持久化，不依赖 nodes 列表是否还包含该节点；非 STALE 清除记录，STALE 首次记录 now，超时 15s 不加入 nodes。删除 `TopoNode.staleAt` 字段。测试 64/64 通过，APK 19,192,426 B。
- **v1.0.20 失联节点直接不显示 + 拓扑图再缩 0.5 倍**（用户反馈"算了，失联节点就直接默认不显示了.然后扩扑图再做小一点,0.5倍"）：① **失联节点（OFFLINE）直接从拓扑图过滤**——`peers.forEach` 开头 `if (presence == OFFLINE) return@forEach`，不加入 nodes；删除 `staleAtMap`/`STALE_TTL_MS`/`now` 计时逻辑（不再需要，v1.0.19 的 15s 方案整体废弃）；PeerRow 列表仍显示"离线" ② **拓扑图 aspectRatio 0.85 → 0.425**（再缩 0.5 倍）。TopoKind.STALE 枚举/绘制分支保留为不可达死代码（不清理，避免扩大改动面）。测试 64/64 通过，APK 19,192,426 B。
- **v1.0.21 延迟秒数移到网络符号前**（用户反馈"延迟秒数写在网络符号前面，不然上面刷新延迟的时候，旁边网络也跟着抖"）：PeerRow 布局从 `SignalBars + [dBm+ageText]` 改为 `[ageText] + SignalBars + [dBm]`——ageText（100ms 刷新、宽度变化）在最左侧，SignalBars 固定在中间不再随 ageText 宽度变化左右抖动。ageText 保留原色逻辑（lost 黄/offline 灰），dBm 固定 TextSecondary。测试 64/64 通过，APK 19,192,426 B。
- **v1.0.22 拓扑图恢复正方形 + 整体缩小 + 节点/连线等比缩小**（用户反馈"大小没变、边框变大了变成长方形、要正方形、里面节点连接变小"）：v1.0.20 的 `aspectRatio(0.425f)` 扁长方体错误方向——宽度没变导致视觉"边框变大"。修复：`fillMaxWidth(0.6f) + aspectRatio(1f)`——60% 宽的正方形（整体明显变小，居中显示）；scale = min(w,h)/360 随盒子变小自动从 ~1.0 降到 ~0.6，节点半径（27→~16）、连线宽度（4→~2.4）、字号、弹簧长度、命中半径全部等比缩小。测试 64/64 通过，APK 19,192,426 B。
- **v1.0.23 框恢复全宽正方形 + 节点/连线真正缩小**（用户反馈"你这不就框边小了吗？我要求是里面的节点显示变小"）：**教训——缩框没意义**：节点基础尺寸是相对 BASE_CANVAS 的绝对像素，`scale = min(w,h)/360` 下节点相对框占比恒为 27/360=7.5%，框缩放节点等比缩放，视觉占比不变。真正让节点变小的做法是**降低绝对基础尺寸**：① 框恢复 `fillMaxWidth() + aspectRatio(1f)`（全宽正方形）② 节点半径 27/22/19 → **16/13/11**（相对占比 7.5%→4.4%，明显变小）③ 连线宽 mesh 2.5→1.5、SEARCHING 4.5→3、DIRECT 5.5→3.5、REACHABLE 4→2.5 ④ 描边 7→4、短 ID 字号 27→16、昵称 33→20、三角缩小 ⑤ 物理 springLen 120→80、repulsion 6000→3500（节点小图更紧凑）。测试 64/64 通过，APK 19,192,426 B。
- **v1.0.24 蓝牙关→开自动重建传输层**（用户反馈两个严重后端问题）：① 关蓝牙设备在 B 上仍刷新帧到达 3-4 秒——**发送端系统关蓝牙延迟**（活跃 GATT 连接先优雅断开再停广播，期间 PING 仍发）+ 本机 2s 失联阈值叠加，非接收端 bug，App 层无解。② 正常对话一方关蓝牙 1-2s 再开，该设备无法重连且收不到消息（对端显示在线/已送达但实际收不到）——**真 bug**：Android 关蓝牙会杀掉本 App 的广播/扫描/GATT，重开后**不会自动恢复**，而 `service.started` 仍 true、全工程无任何 `ACTION_ADAPTER_STATE_CHANGED` 监听 → 本机"听不见"任何帧。修复：`MeshChatApplication.onCreate` 注册系统蓝牙状态接收器，`STATE_ON` 后延迟 500ms 调 `service.restartDiscovery()`（transport.stop+start 重建，绕开 started 守卫）——蓝牙重开即自动恢复收发，无需重进/点重新发现。
- **v1.1.0 多跳中继实装**（用户"先把中继组网之前计划的方案实现"，按规格 `docs/superpowers/specs/2026-08-03-meshchat-multihop-relay-design.md` 全量落地）：
  - **协议**：`PresenceBody.relays`（本机一跳邻居列表，默认空兼容老版本）。
  - **纯中继转发**：`handleEnvelope` else 分支 TEXT 不再要求会话关系——非本机 TEXT（ttl>1）即转发（TTL 递减由 ForwardingDecision 处理），转发带 50-250ms 随机抖动错开多机同步广播防风暴（本机发消息不抖动）。
  - **RECEIPT 泛洪回传**：`"receipt-$id"` 去重键防环；中间节点（非发送方）收到未见过回执转发一次，发送方收到自己 pending 回执只确认不转发（泛洪终点）。`sendReceipt` 也 mark 去重键防回环。
  - **outbox 重发兜底**：`resendOutbox(now)` 接入 200ms tick——每条目 1s 节流、≤3 次重试或过期即移除（转发丢帧尽力而为）。
  - **路由表**：`routeEntries`（远端→via/hops/lastSeenAt），PING 分支 `learnRoutes` 学习（一跳优先：relay 已是新鲜一跳节点则忽略），heartbeatTick 清理（中继失联 >15s 或条目 >30s 未确认即移除）；`MeshPeerInfo.relayVia` 合成 2 跳节点进 peers 流（presence 恒 ONLINE，UI 据此显示"经中继可达"）。
  - **PING 节流**：`pingCount % 3 == 0` 才带 relays（3s 一次，控带宽）。
  - **UI**：PeerRow 中继节点显示"经 X 可达 · 2跳"（隐藏信号行）；会话页 Header 显示"经 X 可达 · 消息经中继送达"；发往 2 跳目标的消息送达文案追加"· 经中继"。拓扑图零改动（2 跳节点自动呈现为 Cyan 小节点 + mesh 边）。
  - 单测 +7（规格 12.1 全部覆盖），72/72 通过。范围外：文件/握手/群组多跳、3 跳+、加密、路由持久化。

### 下一步首要任务
0. **v1.1.13 真机验证（当前版本）**：用户安装 `MeshChat-v1.1.13-debug.apk` → ① 能正常打开 ② **示波器**（Profile→调试中心→示波器板块）：绿/蓝速率线随心跳/消息跳动、失败时琥珀脉冲、扫描头亮点；配合心跳档位切 0.05s 观察波形变密 ③ 主动控制面板、失败包、暂停/恢复、恢复默认 ④ 其余回归：删除对话后节点消失、安全中心、归档/删除/未读、附近/历史分组、新图标 → 确认后提交推送 origin/main（v1.1.3~v1.1.13 全部积压）。
1. **v1.1.0 真机验证——多跳中继三机验收**：按规格 §12.2 排布 A—B—C（相邻两两可达、A 与 C 互不可见）：① A 的 Mesh 页/拓扑图看到 C"经 B 可达 · 2跳"（Cyan 小节点）② A 给 C 发 TEXT → C 收到、A 状态翻"已送达"（RECEIPT 经 B 回传），消息文案带"· 经中继" ③ C 回 TEXT → A 收到（对称）④ 会话页 Header 显示"经 B 可达 · 消息经中继送达" ⑤ B 删后台 → A 的路由重新学习，B 不在线期间 outbox 重发兜底 ⑥ 关 B 蓝牙 → A 侧 C 路由移除。其余回归：一跳直连收发/送达确认、蓝牙关→开自动恢复（v1.0.24）、拓扑图、文件传输。
2. **v1.0.13 蓝牙重搜验证**：安装 `MeshChat-v1.0.13-debug.apk`，重点复现蓝牙重搜路径：两机先关蓝牙进软件 → 开蓝牙 → 点"重新发现" → 应互相搜到（不再需要重进）。其余回归：Mesh 页拓扑图（力导向/拖拽/三色）、送达确认、滚动、文件传输。
3. 备用源 `soodok.online/meshchat_bare.git` 同步（如需）。
4. 三机全链路回归：握手→会话→双向消息→文件传输→心跳状态对称→失联重连→多跳转发（TTL 8）。
5. 按规格开放问题推进：真实加密接入（Cipher 接口占位）、**WiFi Direct 载体（复用 MeshTransport 抽象）**、群聊上层逻辑（协议载荷已就绪）、**多跳中继 v1.1.0**（实装后拓扑图自动显示 peer-peer mesh 边，本机自然去中心化——见 `docs/superpowers/specs/2026-08-03-meshchat-multihop-relay-design.md` + `2026-08-03-mesh-topology-graph-design.md` §6.4）。

### 本次涉及的关键文件
- 后端：`app/src/main/java/com/meshchat/app/mesh/**`（protocol/routing/identity/storage/transport/service）
- v0.14.0 新增：`mesh/service/MeshChatService.kt`、`mesh/service/NotificationHelper.kt`、`mesh/service/SessionStore.kt`；改动：`mesh/service/MeshService.kt`（心跳/sessionStore/回调）、`protocol/MeshEnvelope.kt`（PresenceBody）、`storage/*`（upsertPeer）、`MeshChatApplication.kt`（昵称/后台开关/前台服务启动）、`MainActivity.kt`（通知权限/点击直达）、`AndroidManifest.xml`、UI 8 文件（昵称显示/设置页/会话标题/通知点击）
- RFCOMM（v0.13.x 保留未启用）：`mesh/transport/RfcommFraming.kt`、`RfcommTransport.kt`、`mesh/service/MeshService.kt`（含 RfcommChannel 接口）、`mesh/transfer/FileTransferManager.kt`（sendFrame 注入，默认 broadcast 兜底）
- 对接：`app/src/main/java/com/meshchat/app/data/MeshRepository.kt`、`ui/MeshChatViewModel.kt`、`ui/MeshChatApp.kt`、`ui/MeshChatViewModelFactory.kt`
- 构建：`build.gradle.kts`、`app/build.gradle.kts`、`gradle.properties`、`app/src/main/AndroidManifest.xml`
- 文档：`README.md`、`AI_CONTEXT.md`、`docs/superpowers/specs/*.md`、`docs/superpowers/plans/*.md`
- **v1.0.3 拓扑图重构**：`ui/screens/MeshScreen.kt`（`MeshTopology` 力导向重写 + `TopoNode`/`TopoKind`/`topologyPhysicsStep`/`drawDotGrid`/`drawTopologyEdges`/`drawTopologyNodes` 全部内联）；HTML 原型 `mesh-screen-preview.html`；设计规格 `docs/superpowers/specs/2026-08-03-mesh-topology-graph-design.md`；版本 `app/build.gradle.kts`（v1.0.3/versionCode 39）
- **本次（2026-08-04 闪烁 bug 调查）**：仅产出报告 `docs/handoff/2026-08-04-markseen-flicker-bug.md`，未改任何源码；调查涉及读取 `mesh/service/MeshService.kt`（markSeen 第 506-526 行 / heartbeatTick 第 403-433 行）、`data/MeshRepository.kt`（observeConversations 第 39-55 行）、`ui/screens/ChatsScreen.kt`（分 section 渲染）、`ui/screens/MeshScreen.kt`（拓扑图增量更新）；git 历史核对 commit `36ac5cc`（v0.15.0）
- **本次（v1.1.1 队友融合）**：`mesh/service/MeshService.kt`（start collector 逐帧 runCatching / prunePersistentCaches / stop 去 scope.cancel 加 transfer.cancel / FILE alreadySaved 检测）、`mesh/service/MeshChatService.kt`（订阅单例 Job）、`mesh/storage/{MeshStore,Daos,InMemoryMeshStore,MeshDatabase}.kt`（v2 迁移 + 4 索引 + prune）、`mesh/transfer/FileTransferManager.kt`（senderJob / cancel / acknowledgeCompletedFile / cleanupOrphanedTemporaryFiles）、`ui/screens/ConversationScreen.kt`（statusBarsPadding）、`ui/screens/MeshScreen.kt`（MAX_TOPOLOGY_PEERS=48）、`AndroidManifest.xml`（adjustResize）、`app/build.gradle.kts`（v1.1.1/63）、`README.md`、`.gitignore`（*.zip/*.bundle）、`MeshServiceTest.kt`（夹具 lastSeen 修正）
- **v1.1.2 崩溃修复**：`mesh/storage/Entities.kt`（@Entity 补 4 索引声明，与 MIGRATION_1_2 对齐——**本次卡退根因文件**）、`app/build.gradle.kts`（v1.1.2/64）
- **v1.1.3 队友二轮融合**：新增 `security/`（capability/integrity/local/model/presentation/risk/vpn 11 文件）、`ui/screens/SecurityCenterScreen.kt`、`data/ConversationPreferences.kt`、`mesh/protocol/MeshTransportSecurity.kt`、`mesh/storage/MeshStorageSecurity.kt`、`res/xml/network_security_config.xml`、`res/mipmap-nodpi/ic_launcher_meshchat.png`、`app/src/test/.../security/`（9 套件）；修改 `MeshChatApplication.kt`（安全注入）/`MainActivity.kt`（权限回调）/`AndroidManifest.xml`（allowBackup=false+禁明文+新图标）/`MeshChatViewModel.kt`（归档/已读/安全）/`MeshChatViewModelFactory.kt`/`MeshRepository.kt`（observeAllMessages+deleteConversation）/`UiModels.kt`（ChatPreview 字段）/`MeshStore.kt`+`Daos.kt`+`InMemoryMeshStore.kt`+`MeshDatabase.kt`（observeAllMessages/deleteConversation）/`MeshService.kt`（removeSession）/`ChatsScreen.kt`（归档删除菜单）/`MeshChatHome.kt`（安全中心/About 路由）/`MeshChatApp.kt`（通知权限 launcher）/`MeshScreen.kt`（附近/历史分组，拓扑本体零改动）/`ProfileScreen.kt`+`ProfileDetailScreens.kt`（入口+About 页）/`InMemoryTransport.kt`（replay=1）；版本 `app/build.gradle.kts`（v1.1.3/65）
- **v1.1.4 删除对话后节点不消失**：`mesh/storage/MeshStore.kt`+`InMemoryMeshStore.kt`+`RoomMeshStore.kt`（deletePeer）/`mesh/service/MeshService.kt`（removePeer）/`data/MeshRepository.kt`（deleteConversation 组合）/`MeshServiceTest.kt`（removePeer 测试 +1）；版本 `app/build.gradle.kts`（v1.1.4/66）
- **v1.1.5 调试中心**：新增 `mesh/debug/DebugStats.kt`（统计内核：EventQueue/FrameKind/kindOfEnvelope/snapshot/attachProviders/reset/recordConfirmed/recordResend/windowRetriesSnapshot）、`ui/screens/DebugCenterScreen.kt`（五板块+7 项调节+系统栏）、`app/src/test/.../mesh/debug/DebugStatsTest.kt`（5 项）；修改 `mesh/service/MeshService.kt`（recordSentFrame 统一出口+handleFrame 接收埋点+路由三分支+回执确认三处+attachProviders）、`mesh/transfer/FileTransferManager.kt`（FILE 块/ACK/窗口重试埋点）、`mesh/transport/BleTransport.kt`（广播/扫描/GATT/MTU/写与 notify/服务发现埋点 + bluetoothEnabled）、`mesh/transport/MeshTransport.kt`（bluetoothEnabled 默认方法）、`ui/MeshChatViewModel.kt`（DebugSettings/debugSnapshot/debugSettings/startDebugLoop/updateDebugSettings/resetDebugStats）、`ui/MeshChatViewModelFactory.kt`（debugStats 注入）、`MeshChatApplication.kt`（debugStats lazy + 构造注入）、`ui/MeshChatApp.kt`（collect+透传）、`ui/screens/MeshChatHome.kt`（"debug" 分支路由）、`ui/screens/ProfileScreen.kt`（调试中心入口行）、`MeshServiceTest.kt`（调试统计 2 项）；版本 `app/build.gradle.kts`（v1.1.5/67）、`README.md`、`AI_CONTEXT.md`；规格 `docs/superpowers/specs/2026-08-05-debug-center-design.md`、计划 `docs/superpowers/plans/2026-08-05-debug-center.md`
- **v1.1.6 启动崩溃修复**：`app/src/main/AndroidManifest.xml`（补 `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_CONNECTED_DEVICE` 两个 uses-permission，与 `mesh/service/MeshChatService.kt` 的 `startForegroundCompat()` 显式 type 配套）、`app/build.gradle.kts`（v1.1.6/68）、`AI_CONTEXT.md`
- **v1.1.7 启动崩溃中间排查（CME 假设，已被 v1.1.8 推翻但改动保留）**：`mesh/service/MeshService.kt`（`peerEntries`/`routeEntries` 第 164/156 行 `LinkedHashMap` → `ConcurrentHashMap` + import）、`mesh/debug/DebugStats.kt`（`snapshot()` 对外部 provider 全部 runCatching）、`app/build.gradle.kts`（v1.1.7/69）
- **v1.1.8 启动崩溃真根因修复**：`ui/MeshChatViewModel.kt`（**调试中心属性声明块整体移到 init 块之前**——`DebugSettings`/`_debugSettings`/`debugSettings`/`_debugSnapshot`/`debugSnapshot` 从 init 之后移到 `val backgroundEnabled` 之前；`startDebugLoop` 循环体 try-catch 覆盖含 `_debugSettings.value` 读取的整个迭代——**本次崩溃根因文件**）、`app/build.gradle.kts`（v1.1.8/70）、`AI_CONTEXT.md`
- **v1.1.9 调试中心主动控制**：新增 `mesh/debug/DebugControl.kt`（sealed class 命令集）；修改 `mesh/debug/DebugStats.kt`（attachControls/issue 控制总线）、`mesh/service/MeshService.kt`（4 volatile 参数 + 6 控制方法 + 4 处常量使用处替换 + handler 注册）、`mesh/transport/MeshTransport.kt`（suspendDiscovery/resumeDiscovery 默认方法）、`mesh/transport/BleTransport.kt`（覆写：只停/启广播+扫描保留 GATT）、`mesh/transport/InMemoryTransport.kt`（覆写 + discoverySuspended 断言位）、`ui/MeshChatViewModel.kt`（DebugControlState/sendDebugControl/resetDebugControls + DebugSettings.showControl）、`ui/screens/DebugCenterScreen.kt`（ControlCard 面板 + 板块显隐）、`ui/screens/MeshChatHome.kt`（透传）、`ui/MeshChatApp.kt`（collect+透传）；版本 `app/build.gradle.kts`（v1.1.9/71）、`README.md`、`AI_CONTEXT.md`；规格 `docs/superpowers/specs/2026-08-05-debug-center-active-control-design.md`、计划 `docs/superpowers/plans/2026-08-05-debug-center-active-control.md`；测试 `DebugStatsTest` +1、`MeshServiceTest` +3
- **v1.1.10 高频心跳 + 失败包**：`mesh/service/MeshService.kt`（**独立心跳协程 heartbeatJob + sendPingIfDue**——PING 与 200ms tick 解耦支持 50ms 档；setHeartbeat 下限放宽 50ms/100ms；handleFrame 解码失败埋点）、`mesh/debug/DebugStats.kt`（recordReceivedFailure + FailedStats 并入 snapshot.failures + reset）、`ui/MeshChatViewModel.kt`（DebugSettings.showFailure）、`ui/screens/DebugCenterScreen.kt`（FailureCard 失败包板块 + 心跳六档 0.05s~5s + 板块显隐）；版本 `app/build.gradle.kts`（v1.1.10/72）、`README.md`、`AI_CONTEXT.md`；测试 `DebugStatsTest` +1、`MeshServiceTest` +1（50ms 档）、旧心跳测试改 `sendPingIfDue`
- **v1.1.11 心跳档位收敛**：`mesh/debug/DebugControl.kt`（SetHeartbeat 单参数化 intervalMs）、`mesh/service/MeshService.kt`（setHeartbeat 不再修改 lostHeartbeatMs，失联固定 2s）、`ui/MeshChatViewModel.kt`（sendDebugControl 分支适配 + DebugControlState.lostMs 注释）、`ui/screens/DebugCenterScreen.kt`（心跳四档 0.05/0.1/0.2/0.4s + "失联阈值 2s（固定）"）、`DebugStatsTest`/`MeshServiceTest`（单参数适配）；版本 `app/build.gradle.kts`（v1.1.11/73）、`README.md`、`AI_CONTEXT.md`
- **v1.1.12 主动控制面板布局修复**：`ui/screens/DebugCenterScreen.kt`（**重发退避 FilterChip 短 label 3s/10s/30s 修 Row 溢出拉伸——本次空白根因文件**；面板改高密度「当前生效配置」StatRow 汇总 + 手动 PING 常显）、`ui/MeshChatViewModel.kt`（DebugControlState.manualPingCount 手动 PING 累计计数）；版本 `app/build.gradle.kts`（v1.1.12/74）、`README.md`、`AI_CONTEXT.md`
- **v1.1.13 调试中心示波器**：`ui/MeshChatViewModel.kt`（OscPoint/oscHistory 采样 + prevFailureTotal 脉冲差值 + DebugSettings.showOsc）、`ui/screens/DebugCenterScreen.kt`（**OscilloscopeCard Canvas 示波器**——网格/绿发送/蓝接收/琥珀失败脉冲/扫描头 + 实时值行 + 板块显隐）、`ui/screens/MeshChatHome.kt`（透传 oscHistory）、`ui/MeshChatApp.kt`（collect 透传）；版本 `app/build.gradle.kts`（v1.1.13/75）、`README.md`、`AI_CONTEXT.md`
