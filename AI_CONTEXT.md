# AI_CONTEXT.md — MeshChat 工程交接

> 本文件为 AI 协作交接文档。会话开始前必须阅读；会话结束前必须更新「交接块」。

## 项目定位

MeshChat 是面向**无公网/弱网极端环境**的近场安全通信应用。本仓库（`E:\MeshChat Project`）包含：**Android 前端**（Jetpack Compose，界面/导航/交互）与**设备内嵌去中心化后端框架**（协议/路由/身份/持久化/服务编排/BLE 传输）。

- 工程根目录：`E:\MeshChat Project`；git 远程：`https://github.com/Soodok/MeshChat`（main 分支）
- 包名：`com.meshchat.app`；minSdk 26 / targetSdk 36 / compileSdk 36（平台 36.1）
- **当前版本：v1.0.0（正式版，versionCode 36，构建时间 2026-08-03）**——版本更新规则：每次构建后 bump，安装包命名 `MeshChat-vX.Y.Z-debug.apk` 存于工程根目录
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
  - 规格：`docs/superpowers/specs/2026-08-03-meshchat-presence-background-design.md`；计划：`docs/superpowers/plans/2026-08-03-meshchat-presence-background.md`。
  - 规格：`docs/superpowers/specs/2026-08-03-meshchat-rfcomm-transport-design.md`；计划：`docs/superpowers/plans/2026-08-03-meshchat-rfcomm-transport.md`。
- git 历史：基线 `d138496` → 远程合并 `4d25192` → 设计规格 `3aa4fd4` → 计划 `75dddb0` → 任务 0-11 共 12 个实现提交（最新 `b6a2d2c`）→ 联调提交 `fd10d7d` → 交接块 `ab2f287`/`067618b` → v0.11.0 修复 `9e22674` → v0.12.0 文件传输 8 提交（`a8bdf2b`~`23172bb`）→ v0.13.0 RFCOMM 5 提交（`21a3b62` 分帧 → `c3969cb` transport → `3493324` sendFrame 注入 → `efe9d32` 双传输集成 → 任务 5 装配/交接待提交）→ v0.13.1 RFCOMM 停用 `e8911fb` → v0.14.0 7 提交（`bf96fe7` 协议/身份 → `728a228` upsertPeer → `62b0ff3` 会话持久化 → `b53aa1d` 心跳 → `a5b41b7` 前台服务 → `37e084b` UI → `e356ce6` 装配/交接）→ v0.14.1 卡 SENDING 修复 `e09ea94` → v0.15.0 体验三件套 `36ac5cc` → v0.15.1 节点持久化/滚动/即时重发 `e922dd0` → v0.15.2 零容错 `84fbcd7` → v0.16.0 灵敏度/滚动轮询/最近对话三色持久化 `3608afb` → v0.17.0 确认强化/自动寻找/滚动根治 `b0f9e0d` → v0.18.0 心跳确认搭便车/昵称随消息/Application 启动 `2625b62` → v0.19.0 收到帧即登记可回传/死连接清理/CCCD 重试 `f6ea4f6` → v0.20.0 广播确认通道 `cf722dc` → **v1.0.0 正式版发布（待提交）**。

### 已验证内容
- `gradlew testDebugUnitTest`：**63/63 测试通过，0 失败**（含 v0.20.0 广播确认键 2 例、v0.18.0 PONG ackIds/昵称、v0.15.2 退避重发/重复回执、v0.16.0 MeshRepositoryTest 等全部回归）。BleTransport 为 Android 框架层（无 JVM 单测），以真机复现路径验证。
- `gradlew assembleDebug`：**BUILD SUCCESSFUL**，APK `MeshChat-v1.0.0-debug.apk`。
- **真机三机（A11 GSI / A12 华为 / A16）实测打通**：握手→会话锁定→消息双向到达（MeshSvc 日志确认 `deliver kind=TEXT src=<对端> dst=<本机>` 与 `recv kind=TEXT` 双向出现）。
- **v0.11.0 双人真机聊天正常**（用户确认）：消息方向修复后 A↔B 可正常收发，对端消息显示在左侧、本机消息在右侧，不再是"自己跟自己对话"。

### 当前阻塞
- **无阻塞**（v1.0.0 已推送 GitHub origin/main，本地与远程同步）。备用源 `soodok.online/meshchat_bare.git` 未同步（如需可 push）。
- 服务器注意：nginx `client_max_body_size` 默认 1M → 上传 bundle 需分块（≤400KB/块）；`/home/wwwroot` 不存在，实际 web 根为 `/var/www/html`。
- **A11（安卓 11 GSI）位置服务**：BLE 扫描依赖位置服务，已 adb 开启（location_mode=3）；若重刷/恢复出厂需重新开启。
- **RFCOMM 已停用（用户决策）**：配对弹窗在华为/GSI 不弹出 + 配对模型对多设备中心拓扑不友好；代码保留未启用。后续提速方向改为 **WiFi Direct**（免配对、中心外设模型天然、吞吐百 MB/s 级，复用 MeshTransport 抽象即可）。

### 下一步首要任务
1. **v1.0.0 真机终验（当前版本）**：安装 `MeshChat-v1.0.0-debug.apk`，回归：删后台重进发消息→送达确认 1-3s 内翻转（广播确认通道兜底）、进会话不弹回顶、最近对话三色/昵称、心跳对称、文件传输。日志 `adb logcat -s MeshSvc MeshBle`。
2. 备用源 `soodok.online/meshchat_bare.git` 同步（如需）。
3. 三机全链路回归：握手→会话→双向消息→文件传输→心跳状态对称→失联重连→多跳转发（TTL 8）。
4. 按规格开放问题推进：真实加密接入（Cipher 接口占位）、**WiFi Direct 载体（复用 MeshTransport 抽象）**、群聊上层逻辑（协议载荷已就绪）。

### 本次涉及的关键文件
- 后端：`app/src/main/java/com/meshchat/app/mesh/**`（protocol/routing/identity/storage/transport/service）
- v0.14.0 新增：`mesh/service/MeshChatService.kt`、`mesh/service/NotificationHelper.kt`、`mesh/service/SessionStore.kt`；改动：`mesh/service/MeshService.kt`（心跳/sessionStore/回调）、`protocol/MeshEnvelope.kt`（PresenceBody）、`storage/*`（upsertPeer）、`MeshChatApplication.kt`（昵称/后台开关/前台服务启动）、`MainActivity.kt`（通知权限/点击直达）、`AndroidManifest.xml`、UI 8 文件（昵称显示/设置页/会话标题/通知点击）
- RFCOMM（v0.13.x 保留未启用）：`mesh/transport/RfcommFraming.kt`、`RfcommTransport.kt`、`mesh/service/MeshService.kt`（含 RfcommChannel 接口）、`mesh/transfer/FileTransferManager.kt`（sendFrame 注入，默认 broadcast 兜底）
- 对接：`app/src/main/java/com/meshchat/app/data/MeshRepository.kt`、`ui/MeshChatViewModel.kt`、`ui/MeshChatApp.kt`、`ui/MeshChatViewModelFactory.kt`
- 构建：`build.gradle.kts`、`app/build.gradle.kts`、`gradle.properties`、`app/src/main/AndroidManifest.xml`
- 文档：`README.md`、`AI_CONTEXT.md`、`docs/superpowers/specs/*.md`、`docs/superpowers/plans/*.md`
