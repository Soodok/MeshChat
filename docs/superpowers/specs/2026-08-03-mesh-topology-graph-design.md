# MeshChat 网状拓扑图设计规格

- 日期：2026-08-03
- 状态：设计已确认（HTML 原型 5 轮迭代定稿），待实装
- 目标版本：v1.1.0（与多跳中继同版）
- HTML 原型：`mesh-screen-preview.html`（工程根目录）
- 依赖文档：`docs/superpowers/specs/2026-08-03-meshchat-multihop-relay-design.md`（多跳中继 v1）

## 1. 设计目标

替换 [MeshScreen.kt#L104-L133](file:///e:/MeshChat%20Project/app/src/main/java/com/meshchat/app/ui/screens/MeshScreen.kt#L104) 现有的静态 5 节点拓扑图（Canvas 硬编码坐标、无交互、无数据驱动），改为：

- **力导向自然布局**：节点平等参与物理模拟，非等角排列
- **去中心化**：本机只是网络中的一个普通节点，不固定中心、不放大；连接度高的 hub 节点自然居中，本机漂向边缘
- **三色制**：直连（绿）/ 多跳可达（蓝）/ 失联（灰），节点与边统一
- **可拖拽**：节点可自由拖动，松开后回归物理模拟
- **数据驱动**：拓扑数据由后端（MeshService）提供，UI 不硬编码任何节点
- **参考风格**：bitchat 1.6.0 "live topology map" 的网状风格

## 2. 视觉规范

### 2.1 配色（严格沿用 [MeshChatTheme.kt](file:///e:/MeshChat%20Project/app/src/main/java/com/meshchat/app/ui/theme/MeshChatTheme.kt)）

| 元素 | 颜色 | hex |
|---|---|---|
| 本机节点（实心 + 下三角） | Cyan | `#20C9E8` |
| 直连节点描边 / 直连边 | MeshGreen | `#38D66B` |
| 多跳可达节点描边 / 中继边 | Cyan | `#20C9E8` |
| 失联节点描边 / 失联边 | TextSecondary | `#9BA9BB` |
| 节点填充（非本机） | InkSoft | `#172A3D` |
| 画布背景 | Ink | `#081420` |
| 点阵网格 | Cyan α0.06 | `#20C9E8` @ 6% |

### 2.2 节点尺寸（按跳数递减）

| 跳数 | 半径 r | 节点内字号 | 昵称字号 |
|---|---|---|---|
| 本机 / 1 跳 | 7px | 7px bold | 8px |
| 2 跳 | 6px | 7px bold | 8px |
| 3 跳 | 5px | 7px bold | 8px |

- 节点内显示 shortId 前 2 位（monospace）
- 昵称标签在节点上方，距节点 `r + 6px`
- 本机下方小三角标识（底边 `r+2` 到 `r+5`，宽 4px），区分本机但不放大

### 2.3 边样式

| 边类型 | 颜色 | 线宽 | 样式 | 透明度 |
|---|---|---|---|---|
| direct（本机直连） | `#38D66B` | 1.5px | 实线 | 0.8 |
| relay（peer-peer 中继） | `#20C9E8` | 1px | 实线 | 0.25 |
| stale（失联残存） | `#9BA9BB` | 1px | 点线 `[1,3]` | 0.3 |
| 选中边（任一端点选中） | 原色 | +0.5px | 原样 | 1.0 |
| 未选中边（有选中时） | 原色 | 原样 | 原样 | ×0.2 |

### 2.4 选中/悬停

- 选中节点：描边 2.4px + 白色外圈（r+5，α0.5）+ 昵称加粗白色
- 悬停节点：原色外圈（r+5，α0.5）
- 选中时其他节点：整体 α0.4，昵称 α0.35

### 2.5 画布

- 高度：340dp（替换现有 270dp）
- 圆角 12dp，边框 `#1A2F44` 1dp
- 背景：极淡点阵网格（Cyan α0.06，24px 间距，1px 点）

## 3. 物理引擎参数

力导向布局，每帧（~60fps）执行：

| 参数 | 值 | 说明 |
|---|---|---|
| 库仑斥力 | 700 | 节点间互斥，防重叠 |
| 弹簧刚度 K | 0.014 | 边的吸引力 |
| 弹簧自然长度 | 48px | 边的目标长度 |
| 阻尼 | 0.9 | 速度衰减 |
| 微扰 jitter | 0.015 | 模拟设备移动 |
| 最大速度 | 2px/帧 | 限速防飞 |
| 边界 margin | 30px | 软反弹区 |
| 边界反弹系数 | -0.4 | 撞墙减速 |
| mesh 边弹簧系数 | ×0.4 | 中继边更松散 |

**关键：无中心引力**。节点自由分布，连接度高的节点因弹簧力自然居中，本机因连接度低漂向边缘。

### 初始分布

```
angle = (i / nodes.size) * 2π + random(0.5)
dist  = 25 + random(35)
pos   = center + (cos(angle)*dist, sin(angle)*dist)
```

## 4. 交互规范

| 手势 | 行为 |
|---|---|
| 拖拽节点 | 节点跟随手指，松开后带初速度回归物理模拟 |
| 点击节点 | 选中/取消选中，与下方节点列表联动高亮 |
| 点击空白 | 取消选中 |
| 悬停 | 节点高亮外圈 |
| 列表行点击 | 联动选中拓扑图对应节点 |

拖拽实现：按住时 `node.vx=vy=0`，位置直接跟随；松开时赋予随机初速度 `±0.2`。

## 5. 数据模型（UI 层，Kotlin）

### 5.1 拓扑专用模型（解耦于 MeshPeer）

新建 `app/src/main/java/com/meshchat/app/ui/screens/meshtopo/TopologyModels.kt`：

```kotlin
package com.meshchat.app.ui.screens.meshtopo

/** 拓扑节点分类（三色制）*/
enum class TopoNodeKind {
    ME,         // 本机：Cyan 实心 + 下三角
    DIRECT,     // 直连（1 跳）：绿描边
    REACHABLE,  // 多跳可达（2-3 跳）：Cyan 描边
    STALE,      // 失联：灰描边
}

/** 拓扑节点（UI 层，含物理状态）*/
data class TopoNode(
    val id: String,          // shortId（本机用 "ME"）
    val name: String,        // 显示名
    val shortLabel: String,  // 节点内 2 位标签（shortId 前 2 位）
    val kind: TopoNodeKind,
    val hops: Int,           // 0=本机, 1=直连, 2-3=多跳
    // 物理状态（运行时由 ViewModel 维护，不从后端来）
    var x: Float = 0f,
    var y: Float = 0f,
    var vx: Float = 0f,
    var vy: Float = 0f,
    val r: Float,            // 半径（按 hops 递减：7/6/5）
)

/** 拓扑边类型（三色制）*/
enum class TopoEdgeKind {
    DIRECT,  // 本机直连：绿实线
    RELAY,   // peer-peer 中继：Cyan 淡实线
    STALE,   // 失联残存：灰点线
}

/** 拓扑边 */
data class TopoEdge(
    val from: String,  // shortId
    val to: String,    // shortId
    val kind: TopoEdgeKind,
)

/** 拓扑快照（后端 → UI 的完整数据包）*/
data class TopologySnapshot(
    val nodes: List<TopoNode>,
    val edges: List<TopoEdge>,
) {
    companion object {
        val EMPTY = TopologySnapshot(emptyList(), emptyList())
    }
}
```

### 5.2 为什么不直接用 MeshPeer

- MeshPeer 是列表视图模型（线性），拓扑图是图模型（节点+边），职责不同
- MeshPeer 没有 peer-peer 边信息（多跳中继 v1.1.0 才有 routeEntries）
- 拓扑节点需要物理状态（x/y/vx/vy），这是 UI 层运行时状态，不属于数据层
- 解耦后：后端实现多跳中继时，只需提供 `TopologySnapshot`，拓扑图 UI 无需改动

## 6. 后端接口契约（给后端 AI 实装）

### 6.1 数据流

```
MeshService（peerEntries + routeEntries）
    ↓ 合成
MeshRepository.topologySnapshot: StateFlow<TopologySnapshot>
    ↓ 收集
MeshChatViewModel.topology: StateFlow<TopologySnapshot>
    ↓ 传递
MeshScreen → MeshTopologyCanvas(snapshot, onSelect)
```

### 6.2 Repository 接口（核心，给后端 AI）

在 [MeshRepository.kt](file:///e:/MeshChat%20Project/app/src/main/java/com/meshchat/app/data/MeshRepository.kt) 新增：

```kotlin
/**
 * 网状拓扑快照（供 MeshScreen 拓扑图消费）。
 *
 * 后端合成规则：
 * 1. 本机节点：id="ME", kind=ME, hops=0, r=7f
 * 2. 一跳节点：来自 peerEntries（presence != OFFLINE → kind=DIRECT; OFFLINE → kind=STALE）
 *    - 边：ME → peer.shortId, kind=DIRECT（OFFLINE 的改 STALE）
 * 3. 多跳节点：来自 routeEntries（v1.1.0 多跳中继设计文档 §7）
 *    - 节点 kind=REACHABLE, hops=routeEntry.hops
 *    - 边：routeEntry.via → routeEntry.远端shortId, kind=RELAY
 * 4. peer-peer mesh 边（可选，v1.1.0+）：若 routeEntries 暴露邻居间互联，合成 RELAY 边
 *    - 当前 v1.0.x：仅本机直连边 + 失联边，无 RELAY 边（routeEntries 尚未实装）
 *
 * 失效策略：
 * - presence==OFFLINE 的一跳节点 → kind=STALE，其直连边 → kind=STALE
 * - routeEntries 中 lastSeenAt 距今 > 30s 的条目 → 不纳入快照
 */
val topologySnapshot: StateFlow<TopologySnapshot>
```

### 6.3 当前版本（v1.0.x）合成规则

后端 AI 实装时，在 v1.0.x（多跳中继未实装前）按以下规则合成 `TopologySnapshot`：

```kotlin
// MeshRepository 内部
private fun buildTopology(peers: List<MeshPeer>): TopologySnapshot {
    val nodes = mutableListOf<TopoNode>()
    val edges = mutableListOf<TopoEdge>()

    // 1. 本机
    nodes += TopoNode(
        id = "ME", name = "你", shortLabel = "ME",
        kind = TopoNodeKind.ME, hops = 0, r = 7f,
    )

    // 2. 一跳节点 + 直连边
    peers.forEach { peer ->
        val isOffline = peer.presence == PeerPresence.OFFLINE
        val kind = if (isOffline) TopoNodeKind.STALE else TopoNodeKind.DIRECT
        nodes += TopoNode(
            id = peer.shortId,
            name = peer.name,
            shortLabel = peer.shortId.take(2),
            kind = kind,
            hops = 1,
            r = 7f,
        )
        edges += TopoEdge(
            from = "ME",
            to = peer.shortId,
            kind = if (isOffline) TopoEdgeKind.STALE else TopoEdgeKind.DIRECT,
        )
    }

    // 3. 多跳节点 + 中继边：v1.0.x 暂无 routeEntries，留空
    //    v1.1.0 多跳中继实装后，这里读取 routeEntries 补充 REACHABLE 节点 + RELAY 边

    return TopologySnapshot(nodes, edges)
}
```

### 6.4 v1.1.0 多跳中继实装后扩展

当后端 AI 实装 [多跳中继设计文档](file:///e:/MeshChat%20Project/docs/superpowers/specs/2026-08-03-meshchat-multihop-relay-design.md) §7 的 `routeEntries` 后，扩展 `buildTopology`：

```kotlin
// v1.1.0 扩展（后端 AI 实装多跳中继时追加）
routeEntries.forEach { (remoteId, entry) ->
    // entry: RouteEntry(via=B, hops=2, lastSeenAt=...)
    if (now - entry.lastSeenAt > 30_000L) return@forEach  // 超时剔除

    nodes += TopoNode(
        id = remoteId,
        name = remoteId,  // 2 跳节点无昵称，用 shortId
        shortLabel = remoteId.take(2),
        kind = TopoNodeKind.REACHABLE,
        hops = entry.hops,
        r = if (entry.hops == 2) 6f else 5f,
    )
    // 中继边：via → remoteId
    edges += TopoEdge(from = entry.via, to = remoteId, kind = TopoEdgeKind.RELAY)
}
```

**关键：UI 层（MeshTopologyCanvas）无需任何改动**，后端实装多跳中继后拓扑图自动显示 peer-peer mesh 边。

### 6.5 节点半径规则

```kotlin
fun radiusFor(hops: Int): Float = when {
    hops <= 1 -> 7f
    hops == 2 -> 6f
    else -> 5f
}
```

## 7. UI 实装方案

### 7.1 文件清单

| 文件 | 职责 | 状态 |
|---|---|---|
| `ui/screens/meshtopo/TopologyModels.kt` | 数据模型（§5.1） | 新建 |
| `ui/screens/meshtopo/MeshTopologyCanvas.kt` | Compose Canvas + 力导向 + 拖拽 | 新建 |
| `ui/screens/meshtopo/PhysicsEngine.kt` | 物理模拟（独立可测） | 新建 |
| `ui/screens/MeshScreen.kt` | 替换 MeshTopology 调用 | 修改 L104-L133 |
| `data/MeshRepository.kt` | 新增 topologySnapshot + buildTopology | 修改 |
| `ui/MeshChatViewModel.kt` | 暴露 topology: StateFlow<TopologySnapshot> | 修改 |

### 7.2 MeshTopologyCanvas 关键结构

```kotlin
@Composable
fun MeshTopologyCanvas(
    snapshot: TopologySnapshot,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 1. 节点物理状态（remember，跨快照保留位置）
    val physicsNodes = remember { mutableStateMapOf<String, TopoNode>() }
    // 同步 snapshot → physicsNodes（保留已有节点的 x/y/vx/vy）
    LaunchedEffect(snapshot) { syncNodes(physicsNodes, snapshot) }

    // 2. 物理循环（60fps）
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { now ->
                PhysicsEngine.step(physicsNodes, snapshot.edges, canvasW, canvasH)
            }
        }
    }

    // 3. Canvas 渲染
    Canvas(modifier.fillMaxWidth().height(340.dp).pointerInput { detectDragGestures(...) }) {
        drawDotGrid()
        drawEdges(snapshot.edges, physicsNodes, selectedId)
        drawNodes(physicsNodes.values, selectedId)
    }
}
```

### 7.3 PhysicsEngine（独立可测）

```kotlin
object PhysicsEngine {
    fun step(
        nodes: MutableMap<String, TopoNode>,
        edges: List<TopoEdge>,
        w: Float, h: Float,
    ) {
        // 1. 库仑斥力（O(n²)）
        // 2. 边弹簧力（DIRECT ×1.0, RELAY ×0.4, STALE ×0.2）
        // 3. 阻尼 + 微扰 + 限速
        // 4. 位置更新 + 边界反弹
    }
}
```

参数全部 §3 表中的常量，独立单测覆盖。

### 7.4 拖拽手势

```kotlin
Modifier.pointerInput(snapshot) {
    detectDragGestures(
        onDragStart = { offset ->
            draggingNode = hitTest(nodes, offset)
            draggingNode?.let { it.vx = 0f; it.vy = 0f }
        },
        onDrag = { change, dragAmount ->
            draggingNode?.let {
                it.x += dragAmount.x
                it.y += dragAmount.y
            }
        },
        onDragEnd = {
            draggingNode?.let {
                it.vx = (Random.nextFloat() - 0.5f) * 0.4f
                it.vy = (Random.nextFloat() - 0.5f) * 0.4f
            }
            draggingNode = null
        },
    )
}
```

## 8. 与现有代码的对接

### 8.1 MeshScreen.kt 改造

[MeshScreen.kt#L66](file:///e:/MeshChat%20Project/app/src/main/java/com/meshchat/app/ui/screens/MeshScreen.kt#L66) 现有：

```kotlin
item { MeshTopology(peersCount = peers.size) }
```

改为：

```kotlin
item {
    MeshTopologyCanvas(
        snapshot = topology,           // 新增参数：TopologySnapshot
        selectedId = selectedPeerId,   // 新增参数：与列表联动
        onSelect = { id -> selectedPeerId = id },
    )
}
```

`MeshScreen` 签名新增 `topology: TopologySnapshot` 参数，由 `MeshChatViewModel` 传入。

### 8.2 MeshChatViewModel 改造

```kotlin
val topology: StateFlow<TopologySnapshot> =
    repository.topologySnapshot
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TopologySnapshot.EMPTY)
```

### 8.3 列表联动

`MeshScreen` 内 `var selectedPeerId by remember { mutableStateOf<String?>(null) }`，同时驱动：
- `MeshTopologyCanvas` 高亮选中节点
- `PeerRow` 高亮选中行（新增 `selected` 参数 + 左边框 Cyan）

## 9. 性能预算

- 60fps 渲染（9 节点 × 15 边，O(n²)=81 次斥力计算/帧）
- 物理循环用 `withFrameNanos`，与 Compose 渲染同步
- 节点位置 `mutableStateMapOf` 触发重组，但 Canvas 内 `drawIntoCanvas` 直接读 map，不触发重组（性能关键路径用 `DrawScope` 直读）
- 拖拽用 `pointerInput`，不阻塞主线程

## 10. 验收标准

### 10.1 视觉

- [ ] 三色制正确：直连绿 / 多跳蓝 / 失联灰
- [ ] 本机 Cyan 实心 + 下三角，不放大
- [ ] 节点尺寸按跳数递减（7/6/5）
- [ ] 边样式正确（直连实线 / 中继淡实线 / 失联点线）
- [ ] 点阵网格背景

### 10.2 物理

- [ ] 启动后节点自然分布到平衡位置，不卡边缘
- [ ] 本机因连接度低漂向边缘，hub 节点居中
- [ ] 节点持续轻微漂动（jitter 生效）
- [ ] 拖拽节点流畅，松开后回归物理模拟

### 10.3 交互

- [ ] 拖拽节点（鼠标 + 触屏）
- [ ] 点击节点选中/取消
- [ ] 点击列表行联动拓扑图
- [ ] 点击拓扑节点联动列表行
- [ ] 选中时其他节点变暗

### 10.4 数据

- [ ] v1.0.x：显示本机 + 一跳 peers + 直连边
- [ ] v1.0.x：OFFLINE peers 显示为灰色失联节点 + 灰点线
- [ ] v1.1.0：多跳中继实装后，自动显示 REACHABLE 节点 + RELAY 边（UI 无需改动）

### 10.5 单测

- [ ] `PhysicsEngineTest`：斥力/弹簧/阻尼/边界反弹单测
- [ ] `MeshRepositoryTest.buildTopology`：v1.0.x 快照合成正确性
- [ ] 既有 63 例全量回归

## 11. 范围外（后续版本）

- 节点呼吸光晕（当前简化掉，后续可加 1s 心跳动画）
- 雷达扫描线（bitchat 1.7.0 风格，当前简化掉）
- 数据包流动光点（bitchat "little parcel" 风格）
- 3 跳以上路由可视化（依赖 v1.2+ ROUTE 帧或路由转发）
- 拓扑图节点右键菜单（发起对话/查看详情）
- 拓扑图缩放手势（pinch-to-zoom）

## 12. 实装顺序

1. **新建 TopologyModels.kt**（§5.1 数据模型）
2. **新建 PhysicsEngine.kt**（§3 物理引擎 + 单测）
3. **新建 MeshTopologyCanvas.kt**（§7.2 Compose Canvas + 拖拽）
4. **修改 MeshRepository.kt**（§6.3 buildTopology + topologySnapshot）
5. **修改 MeshChatViewModel.kt**（§8.2 暴露 topology）
6. **修改 MeshScreen.kt**（§8.1 替换 MeshTopology + 列表联动）
7. **单测 + 回归**
8. **真机验收**（三机：A11 / A12 / A16）

## 13. 给后端 AI 的接口摘要

**后端 AI 需要实装的唯一接口**：

```kotlin
// 在 MeshRepository.kt
val topologySnapshot: StateFlow<TopologySnapshot>
```

**合成规则**（v1.0.x）：
- 输入：`peers: List<MeshPeer>`（已有）
- 输出：`TopologySnapshot(nodes=[ME + peers], edges=[ME→peer for each peer])`
- OFFLINE peer → kind=STALE，边 kind=STALE

**合成规则**（v1.1.0 多跳中继实装后扩展）：
- 额外输入：`routeEntries: Map<String, RouteEntry>`（多跳中继设计文档 §7）
- 额外输出：REACHABLE 节点 + RELAY 边（via → remoteId）
- 超时条目（lastSeenAt > 30s）剔除

**UI 层零改动**即可支持 v1.1.0 扩展。
