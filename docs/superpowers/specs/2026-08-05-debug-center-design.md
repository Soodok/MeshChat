# 调试中心（Debug Center）设计规格

- 日期：2026-08-05
- 目标版本：v1.1.5（versionCode 67）
- 状态：待实现
- 关联：MeshChat Android 前端 + 设备内嵌后端框架

## 1. 目标与范围

真机/联调现场需要一个**实时、精密、可调节**的调试仪表盘，替代翻 logcat 的低效路径。

- 实时展示：发送/接收**包数、字节数、速率**（按帧类型分类）、BLE 传输层指标、信号与路由、消息送达链路、文件传输、系统状态。
- 所有数据来自**真实收发路径埋点**（原子计数 + 时间戳队列），不伪造、不推断。
- 全部**内存态**：重启清零，不落库、不影响主线程逻辑、不改任何收发时序。
- 提供丰富的调节项：刷新间隔、速率窗口、速率单位、板块显隐/折叠、节点排序、暂停/继续、清零。

范围外：日志导出/分享、历史持久化、黑名单功能、加密（沿用现有协议）。

## 2. 架构（3 个单元 + 装配）

```
┌─────────────────────────────────────────────────────────┐
│ UI 层  DebugCenterScreen.kt（设置页入口，四板块+调节面板）│
│        MeshChatViewModel 持有 debugStats + 刷新循环      │
└──────────────────────────┬──────────────────────────────┘
                           │ collectAsState（按用户刷新间隔）
┌──────────────────────────▼──────────────────────────────┐
│ 统计内核 mesh/debug/DebugStats.kt（纯 Kotlin，可 JVM 测） │
│  · ConcurrentHashMap<FrameKind, EventQueue>              │
│  · 每事件记 (timestampMs, bytes) → 任意窗口速率精确可调   │
│  · snapshot(windowMs) 同步聚合，无后台线程                │
└──────────────────────────┬──────────────────────────────┘
                           │ recordXxx(...)（原子追加，1 行埋点）
┌──────────────────────────▼──────────────────────────────┐
│ 埋点 MeshService / FileTransferManager / BleTransport    │
└─────────────────────────────────────────────────────────┘
```

- `DebugStats` 是纯统计容器：**不持 scope、不启线程**。刷新节奏由 ViewModel 控制（暂停=停刷新循环，计数不受影响）。
- 注入方式：`MeshChatApplication` 持有 `debugStats` 单例，传给 `MeshService`（构造参数 `debugStats: DebugStats = DebugStats()`，向后兼容）与 `BleTransport`；`FileTransferManager` 由 MeshService 透传。

## 3. 数据模型

```kotlin
// mesh/debug/DebugStats.kt
enum class FrameKind { PING, PONG, INVITE, INVITE_ACK, TEXT, FILE_CHUNK, FILE_ACK, RECEIPT, OTHER }

data class FrameStat(
    val sent: Long, val sentBytes: Long, val sentRatePerSec: Double,   // 窗口内速率
    val received: Long, val receivedBytes: Long, val receivedRatePerSec: Double,
)

data class BleStats(
    val broadcastCount: Long, val broadcastBytes: Long, val broadcastRatePerSec: Double,
    val scanResultCount: Long, val scanStartedCount: Long,
    val gattConnectAttempts: Long, val gattConnectSuccess: Long, val gattDisconnects: Long,
    val gattCurrent: Int, val mtu: Int,
    val writeSuccess: Long, val writeFailed: Long,
    val notifySuccess: Long, val notifyFailed: Long,
    val writeRequestsReceived: Long,   // onCharacteristicWriteRequest（对端写帧）
    val servicesDiscovered: Long, val servicesDiscoverRetries: Long,
)

data class PeerDebugInfo(
    val shortId: String, val displayName: String, val rssi: Int,
    val bars: Int, val presence: String, val hops: Int, val relayVia: String?,
    val lastSeenAgoMs: Long,
)

data class DeliveryStats(
    val pending: Int, val confirmed: Long, val resends: Long,
    val resendHistogram: Map<Int, Long>,   // 重发次数 1/2/3/4+ → 次数
    val confirmationRate: Double,          // confirmed / (confirmed + pending)
    val relayedFrames: Long,               // 中继转发帧数（独立计数；转发帧同时计入原 kind 的 sent）
)

data class FileStats(
    val activeTransfer: Boolean, val direction: String?, val fileName: String?,
    val chunksTotal: Int, val chunksProgress: Int, val percent: Int,
    val windowRetries: Long,
)

data class SystemStats(
    val uptimeMs: Long, val serviceStarted: Boolean, val bluetoothEnabled: Boolean,
    val totalMemoryKb: Long, val freeMemoryKb: Long,
)

data class DebugSnapshot(
    val timestampMs: Long,
    val frames: Map<FrameKind, FrameStat>,
    val ble: BleStats,
    val peers: List<PeerDebugInfo>,
    val routeEntries: Int, val sessions: Int, val pendingInvites: Int,
    val dedupHits: Long, val dedupNew: Long,
    val delivery: DeliveryStats,
    val file: FileStats,
    val system: SystemStats,
)
```

## 4. 详细指标清单（仪表盘四板块 + 系统栏）

### 板块 1：收发包（核心）
- 总览行：总发送速率、总接收速率（包/s，可切 包/min）、累计发送/接收包数、累计字节（B/KB）
- 帧类型明细行（每种一行，等宽字体）：
  `PING   ↑12/s  ↓11/s   送1,234 收1,198  送3.4KB 收3.3KB`
  覆盖：PING / PONG / INVITE / INVITE_ACK / TEXT / FILE_CHUNK / FILE_ACK / RECEIPT / OTHER（无法解析的帧单列，反映异常）
- 中继转发帧数（独立一行，在送达板块或收发包底部）：转发的帧同时计入原 kind 的 sent

### 板块 2：BLE 传输层
- 广播：次数、字节、速率
- 扫描：结果帧数、扫描启动次数
- GATT：连接尝试/成功/断开、当前连接数、MTU 协商值
- 写入通道：写成功/失败、notify 成功/失败、收到对端写请求数
- 服务发现：成功数、超时重试数

### 板块 3：信号与路由
- 汇总行：节点数（在线/寻找中/离线/失联）、2 跳路由条目数、会话数、待邀请数
- 节点明细（可排序）：shortId、昵称、RSSI、信号格、presence、hops、经中继、lastSeen 距今
- 去重表：命中/新增计数（反映重放/重发频度）

### 板块 4：送达链路
- 当前待确认数、累计确认数、重发总次数
- 重发次数分布：1x / 2x / 3x / 4x+
- 确认率（%）、最近一次确认耗时（记录发送→确认时刻差，毫秒）

### 板块 5：文件传输
- 当前活动传输（方向/文件名/块进度/百分比）、窗口重发次数

### 底部系统栏（常驻一行）
- 运行时长、服务启动状态、蓝牙开关、内存（总/空闲 KB）

## 5. 调节选项清单（设置面板）

| 调节项 | 选项 | 生效方式 |
|--------|------|---------|
| 刷新间隔 | 0.5s / 1s / 2s / 5s | ViewModel 刷新循环 delay 动态调整 |
| 速率窗口 | 1s / 3s / 5s / 10s | 每次 snapshot(windowMs) 传入，无需重启 |
| 速率单位 | 包/s / 包/min | UI 格式化 |
| 板块显隐 | 收发包/BLE/信号路由/送达/文件 各勾选 | 设置面板开关，实时隐藏卡片 |
| 节点排序 | RSSI / 昵称 / 最近活动 | 刷新时排序 |
| 暂停/继续 | 一键 | 冻结仪表盘刷新，计数继续累积 |
| 清零 | 一键 | DebugStats.reset() 清空全部计数与队列 |

调节项存内存态（与仪表盘同生命周期），重启回默认（1s / 5s / 包/s）。

## 6. 埋点清单（每处 1-2 行，纯原子累加）

### MeshService.kt
| 位置 | 埋点 |
|------|------|
| `sendFrame(dstId, frame)` | 解码 envelope.kind → `recordSent(kind, bytes)` |
| 中继转发分支（非本机 TEXT 转发处） | `recordRelayed()`（独立转发计数） |
| `handleEnvelope` 入口（解码后） | `recordReceived(kind, bytes)`；解码失败 → `recordReceived(OTHER, 0)` |
| `resendPendingReceipts` 重发 TEXT 时 | `delivery.recordResend(id)` |
| 消息确认（DELIVERED 标记处：RECEIPT/PONG ackIds/广播确认键） | `delivery.recordConfirmed(id, latencyMs)` |
| `pendingReceipts` 登记时 | `delivery.recordPending(id)`（snapshot 时取 size） |
| DedupCache 调用处（命中/新增） | `recordDedup(hit = boolean)` |

### FileTransferManager.kt
| 位置 | 埋点 |
|------|------|
| `broadcastChunk` | `recordSent(FILE_CHUNK, bytes)` |
| `sendAck` / `onFileAck` | `recordSent/received(FILE_ACK, bytes)` |
| `onFileChunk` | `recordReceived(FILE_CHUNK, bytes)` |
| 窗口超时重发 | `file.recordWindowRetry()` |

### BleTransport.kt
| 位置 | 埋点 |
|------|------|
| `broadcast(frame)` | `recordBleBroadcast(bytes)` |
| `onScanResult` | `recordScanResult()` |
| `connectTo` 入口 / `onConnectionStateChange` CONNECTED/DISCONNECTED | 连接尝试/成功/断开 |
| `requestMtu` 成功回调 | `recordMtu(value)` |
| `writeCharacteristic` 成功/失败 | `recordGattWrite(ok)` |
| `notifyCharacteristicChanged` 成功/失败 | `recordNotify(ok)` |
| `onCharacteristicWriteRequest` | `recordWriteRequestReceived()` |
| `onServicesDiscovered`（成功/重试） | `recordServicesDiscovered(ok)` |

> 所有埋点只做整数累加/时间戳入队，**绝不**改变控制流、不加锁竞争、不抛异常（内部 runCatching）。

## 7. DebugStats 内核设计

```kotlin
class DebugStats(private val clock: () -> Long = System::currentTimeMillis) {
    // 帧事件：每 FrameKind 两个队列（发/收），元素 (tsMs, bytes)
    private val sentQ = ConcurrentHashMap<FrameKind, ArrayDeque<Pair<Long, Int>>>()
    private val recvQ = ConcurrentHashMap<FrameKind, ArrayDeque<Pair<Long, Int>>>()
    private val ble = ... // AtomicLong 各字段 + 广播事件队列
    private val delivery = ... // 同步块内维护 map
    // 时间戳队列上限：保留 MAX_RETAIN_MS(20s) 内事件，窗口最大 10s 足够；入队时惰性清理尾部

    fun recordSent(kind: FrameKind, bytes: Int)
    fun recordReceived(kind: FrameKind, bytes: Int)
    fun snapshot(windowMs: Long): DebugSnapshot   // 同步聚合：按 tsMs > now-windowMs 过滤计数/计速
    fun reset()                                    // 清空全部
}
```

- **速率精确性**：速率 = 窗口内事件数 / 窗口秒数（用双精度），窗口由调用方每次传入 → 调节即时生效。
- **内存上限**：每队列最多保留 20s 事件；心跳 1Hz + 高转发下事件量极小（<10^3 级），无内存风险。
- **并发安全**：队列用 `ConcurrentHashMap` + 每队列 `synchronized`；AtomicLong 计数器。
- **时钟注入**：`clock` 参数供 JVM 测试虚拟时间推进。

## 8. UI 设计（DebugCenterScreen）

- 入口：设置页「调试中心」行（`ProfileScreen` 新增 `ProfileRow` → `onOpenDebugCenter`），与「安全中心」「关于」并列。
- 布局：
  - 顶部工具条：返回 + 标题「调试中心」 + 暂停/继续按钮 + 清零按钮 + 设置（齿轮）按钮
  - 设置面板：点击齿轮弹出（ModalBottomSheet/AlertDialog），列出 §5 全部调节项
  - 板块卡片：`标题栏（点击折叠/展开，折叠态显示摘要）` + 内容区（等宽字体、紧凑行距）
  - 底部：系统栏常驻一行
- 风格：沿用现有高密度终端风（Ink 背景、等宽字体、TextSecondary 标签、Cyan 强调、11px 级字号）。
- 数据流：`MeshChatViewModel` 新增 `debugStats` 引用；`LaunchedEffect(refreshInterval, paused)` 循环 `delay(interval)` → `snapshot.value = debugStats.snapshot(windowMs)`（StateFlow）；UI `collectAsState`。

## 9. 错误处理与边界

- 埋点异常：全部 runCatching 包裹或纯整数运算（无异常路径），绝不影响收发。
- 解码失败帧：归入 OTHER 计数，不中断（与 handleEnvelope 现有 runCatching 隔离一致）。
- 速率窗口 > 保留上限：snapshot 只统计保留内事件，速率偏低但不报错（UI 最大 10s < 保留 20s，不会触发）。
- 并发：快照读取与写入可并发（队列级锁），快照可能差一个事件，可接受。
- Long 溢出：实际不可达，不处理。
- 清零时机：仅用户点击；重启自然清零（内存态）。

## 10. 测试计划

- `DebugStatsTest`（纯 JVM，注入虚拟 clock）：
  - 计数累加（sent/received/bytes）
  - 速率窗口：固定窗口内事件 → 精确速率；窗口外事件不计
  - reset 清空
  - 并发冒烟：多线程并发 record 后总数正确
- `MeshServiceTest` 增补：
  - 发送 TEXT → `stats.frames[TEXT].sent == 1`
  - 收到 PING → `stats.frames[PING].received == 1`
  - 回执确认 → `delivery.confirmed` 增加
- 回归：现有 110 项全绿。

## 11. 版本与文档

- bump v1.1.5 / versionCode 67；README 特性区加「调试中心（v1.1.5）」；AI_CONTEXT 交接块更新。
- 分阶段：内核+埋点 → UI+装配 → 测试 → 版本/文档。
