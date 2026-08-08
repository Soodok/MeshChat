package com.meshchat.app.ui.screens

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.BluetoothSearching
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.BluetoothDisabled
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.meshchat.app.data.MeshPeer
import com.meshchat.app.mesh.transport.PeerPresence
import com.meshchat.app.ui.components.SignalBars
import com.meshchat.app.ui.theme.Cyan
import com.meshchat.app.ui.theme.Divider as MeshDivider
import com.meshchat.app.ui.theme.InkSoft
import com.meshchat.app.ui.theme.MeshAmber
import com.meshchat.app.ui.theme.MeshGreen
import com.meshchat.app.ui.theme.MeshRed
import com.meshchat.app.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

@Composable
fun MeshScreen(
    modifier: Modifier = Modifier,
    peers: List<MeshPeer>,
    sessions: Set<String>,
    pendingInvites: Set<String>,
    onPeerSelected: (String) -> Unit,
    /** v1.1.53 发现模式（NORMAL 全开 / CLOSED 全停 / SILENT 静默只停广播）。 */
    discoveryMode: com.meshchat.app.mesh.transport.DiscoveryMode,
    onSetDiscoveryMode: (com.meshchat.app.mesh.transport.DiscoveryMode) -> Unit,
    /** v1.1.64 拉黑：删除对话 = 拒绝连接与消息；Mesh 页已拉黑节点点击可解除。 */
    blockedPeers: Set<String>,
    onUnblockPeer: (String) -> Unit,
    /** v1.1.65 主动拉黑：未连接/未对话节点也可在 Mesh 页拉黑（不依赖连接状态）。 */
    onBlockPeer: (String) -> Unit,
    /** v1.1.66 当前频道名（null = 公共频道）与切换回调：私人频道仅同频道成员可发现/连接。 */
    channelName: String?,
    onSetChannel: (String?) -> Unit,
) {
    // v1.1.57：蓝牙未开启时拒绝开启搜索并弹系统授权窗申请打开（ACTION_REQUEST_ENABLE）
    val context = LocalContext.current
    val bluetoothEnableLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { }
    val requestBluetoothEnable = {
        runCatching {
            bluetoothEnableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        }
    }
    // v1.1.64 解除拉黑确认目标
    var unblockTarget by remember { mutableStateOf<String?>(null) }
    // v1.1.65 主动拉黑确认目标（未连接节点也可拉黑）
    var blockTarget by remember { mutableStateOf<String?>(null) }
    // v1.1.66 频道选择对话框状态
    var showChannelDialog by remember { mutableStateOf(false) }
    var channelInput by remember { mutableStateOf("") }
    val nearbyPeers = peers.filter { it.lastSeenAt > 0L && it.presence != PeerPresence.OFFLINE }
    val historyPeers = peers.filter { it.shortId in sessions && it !in nearbyPeers }
    LazyColumn(modifier = modifier, contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 18.dp)) {
        item { MeshTopology(peers = peers, sessions = sessions) }
        item {
            // v1.1.66 频道选择器：单频道制——私人频道仅同频道成员可发现/连接（防公共搜索）
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp)
                    .clickable { showChannelDialog = true },
            ) {
                Icon(
                    Icons.Outlined.Campaign, null,
                    tint = if (channelName.isNullOrBlank()) TextSecondary else Cyan,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("频道", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (channelName.isNullOrBlank()) "公共频道 · 全部节点可见"
                        else "私人频道 · $channelName",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (channelName.isNullOrBlank()) TextSecondary else Cyan,
                    )
                }
            }
        }
        item {
            // v1.1.53：搜索开关已整合进底部"蓝牙搜索"按钮，标题行不再独立放开关
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp),
            ) {
                Text("附近节点（${nearbyPeers.size}）", style = MaterialTheme.typography.titleMedium)
            }
        }
        if (nearbyPeers.isEmpty()) {
            item {
                Text(
                    text = when (discoveryMode) {
                        com.meshchat.app.mesh.transport.DiscoveryMode.NORMAL -> "正在扫描邻近节点…"
                        com.meshchat.app.mesh.transport.DiscoveryMode.CLOSED -> "搜索已停止 · 点击下方按钮开启"
                        com.meshchat.app.mesh.transport.DiscoveryMode.SILENT -> "静默模式 · 陌生人不可见，可发现他人"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (discoveryMode == com.meshchat.app.mesh.transport.DiscoveryMode.NORMAL) TextSecondary else MeshAmber,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                )
            }
        }
        items(nearbyPeers, key = { it.shortId }) { peer ->
            val blocked = peer.shortId in blockedPeers
            PeerRow(
                peer = peer,
                connected = peer.shortId in sessions,
                pending = peer.shortId in pendingInvites,
                blocked = blocked,
                onClick = {
                    // v1.1.64：已拉黑节点点击 = 解除拉黑确认；否则正常连接流程
                    if (blocked) unblockTarget = peer.shortId else onPeerSelected(peer.shortId)
                },
                onBlock = { blockTarget = peer.shortId },   // v1.1.65 主动拉黑
            )
        }
        if (historyPeers.isNotEmpty()) {
            item {
                Text("历史连接（${historyPeers.size}）", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp))
            }
            items(historyPeers, key = { "history-${it.shortId}" }) { peer ->
                PeerRow(
                    peer = peer,
                    connected = false,
                    pending = false,
                    blocked = peer.shortId in blockedPeers,
                    onClick = { onPeerSelected(peer.shortId) },
                    onBlock = { blockTarget = peer.shortId },   // v1.1.65 主动拉黑
                )
            }
        }
        item {
            // v1.1.54 静默模式开关（置顶显眼）：开启 = 只停广播（陌生人扫不到你），scan/连接/保活照常
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 4.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("静默模式", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "不被陌生人发现 · 仍可连接联系人与新设备",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                    )
                }
                androidx.compose.material3.Switch(
                    checked = discoveryMode == com.meshchat.app.mesh.transport.DiscoveryMode.SILENT,
                    onCheckedChange = { on ->
                        // 关闭静默 = 恢复可被发现（开广播）→ 蓝牙未开启则拒绝并弹窗申请
                        if (!on && !bluetoothEnabled(context)) {
                            requestBluetoothEnable()
                        } else {
                            onSetDiscoveryMode(
                                if (on) com.meshchat.app.mesh.transport.DiscoveryMode.SILENT
                                else com.meshchat.app.mesh.transport.DiscoveryMode.NORMAL,
                            )
                        }
                    },
                )
            }
        }
        item {
            // v1.1.54 蓝牙搜索按钮：点击开/关搜索（NORMAL ↔ CLOSED）；静默时点击恢复搜索。
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (discoveryMode == com.meshchat.app.mesh.transport.DiscoveryMode.NORMAL) Cyan
                        else MeshRed.copy(alpha = 0.12f),
                    )
                    .clickable {
                        when (discoveryMode) {
                            com.meshchat.app.mesh.transport.DiscoveryMode.NORMAL -> onSetDiscoveryMode(com.meshchat.app.mesh.transport.DiscoveryMode.CLOSED)
                            com.meshchat.app.mesh.transport.DiscoveryMode.CLOSED,
                            com.meshchat.app.mesh.transport.DiscoveryMode.SILENT,
                            -> {
                                // v1.1.57：开启搜索需要广播+扫描 → 蓝牙未开启则拒绝并弹窗申请
                                if (!bluetoothEnabled(context)) {
                                    requestBluetoothEnable()
                                } else {
                                    onSetDiscoveryMode(com.meshchat.app.mesh.transport.DiscoveryMode.NORMAL)
                                }
                            }
                        }
                    }
                    .padding(horizontal = 20.dp, vertical = 16.dp),
            ) {
                Icon(
                    if (discoveryMode == com.meshchat.app.mesh.transport.DiscoveryMode.NORMAL) {
                        Icons.AutoMirrored.Outlined.BluetoothSearching
                    } else {
                        Icons.Outlined.BluetoothDisabled
                    },
                    null,
                    tint = if (discoveryMode == com.meshchat.app.mesh.transport.DiscoveryMode.NORMAL) Color(0xFF081420) else MeshRed,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = when (discoveryMode) {
                        com.meshchat.app.mesh.transport.DiscoveryMode.NORMAL -> "搜索中 · 点击关闭"
                        com.meshchat.app.mesh.transport.DiscoveryMode.CLOSED -> "搜索已停止 · 点击开启"
                        com.meshchat.app.mesh.transport.DiscoveryMode.SILENT -> "静默模式 · 点击恢复搜索"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                    color = if (discoveryMode == com.meshchat.app.mesh.transport.DiscoveryMode.NORMAL) Color(0xFF081420) else MeshRed,
                    modifier = Modifier.weight(1f),
                )
                // 录像键状态指示（纯视觉，点击由整行处理）：仅 NORMAL 显示"录制中"
                DiscoveryToggleButton(enabled = discoveryMode == com.meshchat.app.mesh.transport.DiscoveryMode.NORMAL)
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }

    // v1.1.64 解除拉黑确认框
    unblockTarget?.let { target ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { unblockTarget = null },
            title = { Text("解除拉黑 ${target}？") },
            text = { Text("解除后对方可重新向您发起连接并发送消息。") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    onUnblockPeer(target)
                    onPeerSelected(target)   // 解除后直接进入连接流程
                    unblockTarget = null
                }) { Text("解除拉黑", color = MeshRed) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { unblockTarget = null }) { Text("取消") }
            },
        )
    }
    // v1.1.65 主动拉黑确认框：未连接节点也可拉黑，无需对方在场/连接
    blockTarget?.let { target ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { blockTarget = null },
            title = { Text("拉黑 ${target}？") },
            text = { Text("拉黑后对方无法向您发起连接、发送消息或邀请。即使对方当前不在线，拉黑也立即生效并持久保存。") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    onBlockPeer(target)
                    blockTarget = null
                }) { Text("拉黑", color = MeshRed) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { blockTarget = null }) { Text("取消") }
            },
        )
    }
    // v1.1.66 频道选择对话框：公共频道 / 自定义私人频道名（仅同频道成员可发现/连接）
    if (showChannelDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showChannelDialog = false },
            title = { Text("选择频道") },
            text = {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { channelInput = "" },
                    ) {
                        RadioButton(selected = channelName.isNullOrBlank(), onClick = null)
                        Text("公共频道 · 全部节点可见")
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = channelInput,
                        onValueChange = { channelInput = it },
                        label = { Text("私人频道名") },
                        placeholder = { Text("仅同频道成员可发现/连接") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    val name = channelInput.trim()
                    onSetChannel(name.ifEmpty { null })
                    android.widget.Toast.makeText(
                        context,
                        if (name.isEmpty()) "已切换至公共频道" else "已切换至频道 $name",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                    showChannelDialog = false
                }) { Text("切换") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showChannelDialog = false }) { Text("取消") }
            },
        )
    }
}

/** v1.1.57：本机蓝牙是否开启（广播/扫描前提）。 */
private fun bluetoothEnabled(context: Context): Boolean =
    runCatching {
        (context.getSystemService(BluetoothManager::class.java))?.adapter?.isEnabled == true
    }.getOrDefault(false)

/**
 * 搜索开关状态指示（v1.1.49 录像键样式；v1.1.52 起整合进"蓝牙搜索"按钮，纯视觉不独立响应点击）：
 * 搜索中 = 红色实心圆 + 白点（录制中），已停止 = 红色空心圆环 + 中心红点。
 */
@Composable
private fun DiscoveryToggleButton(enabled: Boolean) {
    val diameter = 34.dp
    val ring = 2.dp
    Box(
        modifier = Modifier
            .size(diameter)
            .clip(CircleShape)
            .then(
                if (enabled) {
                    Modifier.background(MeshRed)
                } else {
                    Modifier.background(MeshRed.copy(alpha = 0.12f))
                        .border(ring, MeshRed, CircleShape)
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (enabled) {
            // 录制中：中心白点
            Box(
                Modifier.size(10.dp).clip(CircleShape).background(Color.White),
            )
        } else {
            // 已停止：中心小红点
            Box(
                Modifier.size(14.dp).clip(CircleShape).background(MeshRed),
            )
        }
    }
}

@Composable
private fun MeshTopology(peers: List<MeshPeer>, sessions: Set<String>) {
    // 物理状态：节点位置/速度（运行时维护，跨 peers 变化保留）
    var canvasW by remember { mutableFloatStateOf(0f) }
    var canvasH by remember { mutableFloatStateOf(0f) }
    val nodes = remember { mutableStateListOf<TopoNode>() }
    val edges = remember { mutableStateListOf<TopoEdge>() }
    var draggingNode by remember { mutableStateOf<TopoNode?>(null) }
    // 帧计数器：触发 Canvas 重绘（节点内部 var 改动不触发重组，靠此驱动）
    var frame by remember { mutableIntStateOf(0) }

    // 绘制上限：优先保留已会话/在线节点（仅截断绘制数，位置由 existing 保留，渲染效果不变）
    val topologyPeers = peers
        .sortedWith(
            compareBy<MeshPeer>(
                { if (it.shortId in sessions) 0 else 1 },
                {
                    when (it.presence) {
                        PeerPresence.ONLINE -> 0
                        PeerPresence.UNRESPONSIVE -> 1   // v1.1.55：广播可见但无响应——优先于普通失联保留显示
                        PeerPresence.RECONNECTING -> 2
                        PeerPresence.SEARCHING -> 3
                        PeerPresence.OFFLINE -> 4
                    }
                },
                { it.shortId },
            ),
        )
        .take(MAX_TOPOLOGY_PEERS)

    // 同步 peers → nodes + edges（保留已有节点位置，避免重组时跳变）
    LaunchedEffect(peers, sessions, canvasW, canvasH) {
        if (canvasW <= 0f || canvasH <= 0f) return@LaunchedEffect
        val scale = minOf(canvasW, canvasH) / BASE_CANVAS
        val existing = nodes.associateBy { it.id }
        val cx = canvasW / 2f
        val cy = canvasH / 2f
        nodes.clear()
        // 本机（保留物理状态）
        val oldMe = existing["ME"]
        nodes.add(TopoNode(
            id = "ME", name = "你", short = "ME", kind = TopoKind.ME, hops = 0, r = 10f * scale,
            x = oldMe?.x ?: cx, y = oldMe?.y ?: cy,
            vx = oldMe?.vx ?: 0f, vy = oldMe?.vy ?: 0f,
        ))
        // 一跳节点（失联节点直接不显示）
        topologyPeers.forEach { peer ->
            if (peer.presence == PeerPresence.OFFLINE) return@forEach
            val actualKind = when {
                peer.presence == PeerPresence.SEARCHING || peer.presence == PeerPresence.RECONNECTING ||
                    peer.presence == PeerPresence.UNRESPONSIVE -> TopoKind.SEARCHING   // v1.1.55：无响应 = 黄虚线（广播可见但应用层无响应）
                peer.shortId in sessions -> TopoKind.DIRECT
                else -> TopoKind.REACHABLE
            }
            val old = existing[peer.shortId]
            val r = (if (peer.hops <= 1) 10f else if (peer.hops == 2) 8f else 7f) * scale
            nodes.add(TopoNode(
                id = peer.shortId, name = peer.name, short = peer.shortId.take(2),
                kind = actualKind, hops = peer.hops, r = r,
                x = old?.x ?: (cx + (Random.nextFloat() - 0.5f) * 160f * scale),
                y = old?.y ?: (cy + (Random.nextFloat() - 0.5f) * 160f * scale),
                vx = old?.vx ?: 0f, vy = old?.vy ?: 0f,
            ))
        }
        // 生成 peer-peer mesh 骨干边（非失联 peer 之间互连）
        edges.clear()
        val activePeers = nodes.filter { it.id != "ME" && it.kind != TopoKind.STALE }
        for (i in activePeers.indices) {
            for (j in i + 1 until activePeers.size) {
                edges.add(TopoEdge(activePeers[i].id, activePeers[j].id))
            }
        }
    }

    // 物理循环（~60fps，被拖节点跳过物理，位置由手指直接控制）
    LaunchedEffect(canvasW, canvasH) {
        if (canvasW <= 0f || canvasH <= 0f) return@LaunchedEffect
        while (true) {
            delay(16)
            topologyPhysicsStep(nodes, edges, canvasW, canvasH, draggingNode?.id)
            frame++
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                .background(InkSoft.copy(alpha = 0.4f))
                .onSizeChanged {
                    canvasW = it.width.toFloat()
                    canvasH = it.height.toFloat()
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        // 用 awaitPointerEvent 直接轮询指针事件，绕过 drag() 的 touch slop 和事件路由：
                        // 按下瞬间命中 → 记录偏移 → 每帧用绝对位置 node = pointer + off 完全跟手 → 抬起赋初速度
                        val hitScale = minOf(size.width, size.height).toFloat() / BASE_CANVAS
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            down.consume()
                            val node = nodes.minByOrNull {
                                val dx = it.x - down.position.x
                                val dy = it.y - down.position.y
                                dx * dx + dy * dy
                            }?.takeIf {
                                val dx = it.x - down.position.x
                                val dy = it.y - down.position.y
                                sqrt(dx * dx + dy * dy) <= it.r + 60f * hitScale
                            }
                            if (node != null) {
                                node.vx = 0f
                                node.vy = 0f
                                val offX = node.x - down.position.x
                                val offY = node.y - down.position.y
                                draggingNode = node
                                // 直接轮询：每一帧读取指针绝对位置，无 touch slop、无增量累积
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) { change.consume(); break }
                                    change.consume()
                                    node.x = change.position.x + offX
                                    node.y = change.position.y + offY
                                    frame++
                                }
                                // 抬起：赋初速度回归物理
                                node.vx = (Random.nextFloat() - 0.5f) * 1.5f
                                node.vy = (Random.nextFloat() - 0.5f) * 1.5f
                                draggingNode = null
                            }
                        }
                    },
            ) {
                // 读 frame 建立重绘依赖（节点内部 var 改动靠此驱动重绘）
                @Suppress("UNUSED_VARIABLE") val redrawTrigger = frame
                drawDotGrid()
                drawMeshEdges(nodes, edges)
                drawTopologyEdges(nodes)
                drawTopologyNodes(nodes)
            }
        }
        Row(
            modifier = Modifier.padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Hub, null, tint = Cyan, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(8.dp))
            val onlineCount = peers.count { it.presence == PeerPresence.ONLINE }
            val offlineCount = peers.count { it.presence == PeerPresence.OFFLINE }
            val unresponsiveCount = peers.count { it.presence == PeerPresence.UNRESPONSIVE }
            val sessionCount = peers.count { it.shortId in sessions }
            Text(
                // v1.1.55：UNRESPONSIVE（广播可见·无响应）单独计数，不再混入"在线"（避免"显示连上但送不到"的假状态）
                if (unresponsiveCount > 0) "已会话 $sessionCount · 在线 $onlineCount · 无响应 $unresponsiveCount · 失联 $offlineCount"
                else "已会话 $sessionCount · 在线 $onlineCount · 失联 $offlineCount",
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
        }
    }
}

@Composable
private fun PeerRow(peer: MeshPeer, connected: Boolean, pending: Boolean, blocked: Boolean, onClick: () -> Unit, onBlock: () -> Unit) {
    // 100ms 刷新"信号时间"：帧到达 → lastSeenAt 更新 → 数字归零跳动（<1s 毫秒显示）；帧停止 → 数字持续增大 → 直观感知断连
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(100); now = System.currentTimeMillis() } }
    val ageMs = if (peer.lastSeenAt > 0) (now - peer.lastSeenAt).coerceAtLeast(0) else -1
    val ageText = when {
        ageMs < 0 -> ""
        ageMs < 1_000 -> "${ageMs}ms前"
        else -> "${ageMs / 1_000}s前"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(42.dp).clip(androidx.compose.foundation.shape.CircleShape).background(InkSoft), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.PhoneAndroid, null, tint = TextSecondary)
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(peer.name, style = MaterialTheme.typography.titleMedium)
            Text(
                "ID ${peer.shortId}",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = TextSecondary,
            )
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // v1.1.0 经中继可达（2 跳）：无真实信号/帧到达，隐藏信号行，改显"经 X 可达"
            if (peer.relayVia.isBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = ageText,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = if (peer.presence == PeerPresence.OFFLINE) TextSecondary else (if (peer.lost) MeshAmber else TextSecondary),
                    )
                    Spacer(Modifier.width(8.dp))
                    SignalBars(peer.strength)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        // v1.1.17：有协议层信号强度显示百分比，否则回退 dBm（对端老版本/样本不足）
                        text = if (peer.signalRatio >= 0) "${(peer.signalRatio * 100).toInt()}%" else "${peer.rssi} dBm",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = TextSecondary,
                    )
                }
            }
            val statusText = when {
                blocked -> "已拉黑 · 点击解除"   // v1.1.64：删除对话 = 拉黑
                peer.relayVia.isNotBlank() -> "经 ${peer.relayVia} 可达 · 2跳"
                else -> when (peer.presence) {
                    PeerPresence.ONLINE -> when {
                        connected -> "已连接 · 点击进入会话"
                        pending -> "等待对方接受"
                        else -> "点击发起对话"
                    }
                    PeerPresence.UNRESPONSIVE -> "广播可见 · 应用无响应"   // v1.1.55：诚实标注"显示连上但送不到"
                    PeerPresence.SEARCHING -> "寻找中…"
                    PeerPresence.RECONNECTING -> "断线重连中…"
                    PeerPresence.OFFLINE -> "离线"
                }
            }
            val statusColor = when {
                blocked -> MeshRed.copy(alpha = 0.8f)   // v1.1.64 已拉黑红色
                peer.relayVia.isNotBlank() -> MeshGreen
                else -> when (peer.presence) {
                    PeerPresence.ONLINE -> if (connected) MeshGreen else TextSecondary
                    PeerPresence.UNRESPONSIVE -> MeshAmber   // v1.1.55：琥珀 = 广播可见但无响应
                    PeerPresence.SEARCHING, PeerPresence.RECONNECTING -> MeshAmber
                    PeerPresence.OFFLINE -> TextSecondary
                }
            }
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = statusColor,
            )
            if (!blocked) {
                // v1.1.65：未拉黑节点提供主动拉黑入口（未连接陌生人也可拉黑，拉黑后对方无法连接/发消息）
                IconButton(onClick = onBlock, modifier = Modifier.size(26.dp)) {
                    Icon(Icons.Outlined.Block, "拉黑", tint = TextSecondary, modifier = Modifier.size(15.dp))
                }
            }
        }
    }
    HorizontalDivider(color = MeshDivider, modifier = Modifier.padding(start = 80.dp))
}

// ===== 拓扑图数据模型 + 物理引擎 + 绘制（力导向 mesh 拓扑）=====

/** 基准画布尺寸（典型手机宽度），所有尺寸相对此缩放，适配不同分辨率/屏幕大小 */
private const val BASE_CANVAS = 360f

/** 拓扑图节点绘制上限（移植队友 v1.0.12）：力导向为 O(n²)，超过则物理循环卡顿；仅截断绘制节点数，不改渲染效果 */
private const val MAX_TOPOLOGY_PEERS = 48

/** 拓扑节点分类（四色制：本机/直连绿/多跳蓝/寻找中黄虚线/失联灰）*/
private enum class TopoKind { ME, DIRECT, REACHABLE, SEARCHING, STALE }

/** 拓扑节点（UI 运行时物理状态，var 字段不触发重组，靠 frame 计数器驱动重绘）*/
private class TopoNode(
    val id: String,
    var name: String,
    var short: String,
    var kind: TopoKind,
    var hops: Int,
    var r: Float,
    var x: Float,
    var y: Float,
    var vx: Float = 0f,
    var vy: Float = 0f,
)

/** 拓扑边（peer-peer mesh 骨干，v1.0.x 用虚拟边模拟网状结构；v1.1.0 多跳中继后由 routeEntries 驱动）*/
private class TopoEdge(val a: String, val b: String)

/** 物理引擎：库仑斥力 + 边弹簧(本机↔peer + peer↔peer mesh 骨干) + 中心引力 + 阻尼 + 微扰 + 边界反弹
 *  pinnedId 节点跳过力计算与位置更新（拖拽中由手指直接控制位置）
 *  失联节点(STALE)无本机边弹簧力——线断开，仅受斥力与中心引力自然漂走
 *  中心引力极弱（centerK=0.0015）：节点运动时被弹簧/斥力盖过，静止时主导把整体拉回画布中心
 *  所有尺寸基于 scale = min(w,h)/BASE_CANVAS 缩放，适配不同分辨率/屏幕大小 */
private fun topologyPhysicsStep(nodes: List<TopoNode>, edges: List<TopoEdge>, w: Float, h: Float, pinnedId: String? = null) {
    val scale = minOf(w, h) / BASE_CANVAS
    val repulsion = 2600f * scale * scale   // 库仑斥力系数（与距离平方反比，系数需 ∝ scale² 保持视觉一致）
    val springK = 0.008f                    // 本机↔peer 弹簧刚度
    val meshSpringK = 0.003f                // peer↔peer mesh 骨干弹簧刚度（弱，避免拉成直线）
    val springLen = 60f * scale             // 弹簧自然长度（节点变小，间距收紧）
    val damping = 0.9f                      // 速度阻尼
    val jitter = 0.05f * scale              // 微扰
    val maxSpeed = 6f * scale               // 限速
    val margin = 60f * scale                // 边界反弹区
    val centerK = 0.0015f                   // 中心引力系数（弱，运动时被其他力盖过，静止时主导靠拢）
    val cx = w / 2f
    val cy = h / 2f
    val me = nodes.firstOrNull { it.id == "ME" } ?: return
    val byId = nodes.associateBy { it.id }

    // 1. 库仑斥力（O(n²)，pinned 节点不接收力——位置由手指控制）
    for (i in nodes.indices) {
        for (j in i + 1 until nodes.size) {
            val a = nodes[i]; val b = nodes[j]
            var dx = b.x - a.x; var dy = b.y - a.y
            var dist = sqrt(dx * dx + dy * dy)
            if (dist < 1f) { dist = 1f; dx = 1f; dy = 0f }
            val f = repulsion / (dist * dist)
            val fx = dx / dist * f; val fy = dy / dist * f
            if (a.id != pinnedId) { a.vx -= fx; a.vy -= fy }
            if (b.id != pinnedId) { b.vx += fx; b.vy += fy }
        }
    }
    // 2. 本机↔peer 弹簧力（失联节点无弹簧——线断开）
    nodes.forEach { n ->
        if (n.id == "ME") return@forEach
        if (n.kind == TopoKind.STALE) return@forEach  // 失联：无弹簧，线断开
        var dx = n.x - me.x; var dy = n.y - me.y
        var dist = sqrt(dx * dx + dy * dy)
        if (dist < 1f) { dist = 1f; dx = 1f; dy = 0f }
        // 寻找中节点弹簧更弱（残存链路）
        val k = if (n.kind == TopoKind.SEARCHING) springK * 0.2f else springK
        val diff = dist - springLen
        val fx = dx / dist * diff * k; val fy = dy / dist * diff * k
        if (me.id != pinnedId) { me.vx += fx; me.vy += fy }
        if (n.id != pinnedId) { n.vx -= fx; n.vy -= fy }
    }
    // 3. peer↔peer mesh 骨干弹簧力（弱，让图有网状结构，不趋向直线）
    edges.forEach { e ->
        val a = byId[e.a] ?: return@forEach
        val b = byId[e.b] ?: return@forEach
        var dx = b.x - a.x; var dy = b.y - a.y
        var dist = sqrt(dx * dx + dy * dy)
        if (dist < 1f) { dist = 1f; dx = 1f; dy = 0f }
        val diff = dist - springLen
        val fx = dx / dist * diff * meshSpringK; val fy = dy / dist * diff * meshSpringK
        if (a.id != pinnedId) { a.vx += fx; a.vy += fy }
        if (b.id != pinnedId) { b.vx -= fx; b.vy -= fy }
    }
    // 4. 中心引力：所有非 pinned 节点向画布中心微弱拉拢（运动时被其他力盖过，静止时主导靠拢）
    nodes.forEach { n ->
        if (n.id == pinnedId) return@forEach
        n.vx += (cx - n.x) * centerK
        n.vy += (cy - n.y) * centerK
    }
    // 5. 阻尼 + 微扰 + 限速 + 位置更新 + 边界反弹（pinned 节点跳过）
    nodes.forEach { n ->
        if (n.id == pinnedId) return@forEach
        n.vx *= damping; n.vy *= damping
        n.vx += (Random.nextFloat() - 0.5f) * jitter
        n.vy += (Random.nextFloat() - 0.5f) * jitter
        val sp = sqrt(n.vx * n.vx + n.vy * n.vy)
        if (sp > maxSpeed) { n.vx = n.vx / sp * maxSpeed; n.vy = n.vy / sp * maxSpeed }
        n.x += n.vx; n.y += n.vy
        if (n.x < margin) { n.x = margin; n.vx *= -0.4f }
        if (n.x > w - margin) { n.x = w - margin; n.vx *= -0.4f }
        if (n.y < margin) { n.y = margin; n.vy *= -0.4f }
        if (n.y > h - margin) { n.y = h - margin; n.vy *= -0.4f }
    }
}

/** 点阵网格背景 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawDotGrid() {
    val scale = size.minDimension / BASE_CANVAS
    val spacing = 90f * scale
    val dotColor = Cyan.copy(alpha = 0.06f)
    var y = spacing
    while (y < size.height) {
        var x = spacing
        while (x < size.width) {
            drawCircle(dotColor, 2.5f * scale, Offset(x, y))
            x += spacing
        }
        y += spacing
    }
}

/** 绘制 peer-peer mesh 骨干边（淡色，非失联 peer 之间互连）*/
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMeshEdges(
    nodes: List<TopoNode>, edges: List<TopoEdge>,
) {
    val scale = size.minDimension / BASE_CANVAS
    val byId = nodes.associateBy { it.id }
    edges.forEach { e ->
        val a = byId[e.a] ?: return@forEach
        val b = byId[e.b] ?: return@forEach
        drawLine(
            color = Cyan.copy(alpha = 0.15f),
            start = Offset(a.x, a.y), end = Offset(b.x, b.y),
            strokeWidth = 1.2f * scale,
            cap = StrokeCap.Round,
        )
    }
}

/** 绘制本机↔peer 边（按节点状态着色，失联不画——线断开）*/
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTopologyEdges(
    nodes: List<TopoNode>,
) {
    val scale = size.minDimension / BASE_CANVAS
    val me = nodes.firstOrNull { it.id == "ME" } ?: return
    nodes.forEach { n ->
        if (n.id == "ME") return@forEach
        if (n.kind == TopoKind.STALE) return@forEach  // 失联：不画边，线断开
        val start = Offset(me.x, me.y)
        val end = Offset(n.x, n.y)
        when (n.kind) {
            TopoKind.SEARCHING -> drawLine(
                color = MeshAmber.copy(alpha = 0.6f),
                start = start, end = end, strokeWidth = 2.2f * scale,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f * scale, 4f * scale), 0f),
            )
            TopoKind.DIRECT -> drawLine(
                color = MeshGreen.copy(alpha = 0.8f),
                start = start, end = end, strokeWidth = 2.6f * scale,
                cap = StrokeCap.Round,
            )
            TopoKind.REACHABLE -> drawLine(
                color = Cyan.copy(alpha = 0.25f),
                start = start, end = end, strokeWidth = 1.8f * scale,
                cap = StrokeCap.Round,
            )
            TopoKind.ME, TopoKind.STALE -> { /* 不会到达 */ }
        }
    }
}

/** 绘制拓扑节点（所有尺寸基于画布缩放，适配不同分辨率/屏幕大小）*/
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTopologyNodes(
    nodes: List<TopoNode>,
) {
    val scale = size.minDimension / BASE_CANVAS
    nodes.forEach { n ->
        val cx = n.x
        val cy = n.y
        val (stroke, fill, label) = when (n.kind) {
            TopoKind.ME -> Triple(Cyan, Cyan, Color(0xFF081420))
            TopoKind.DIRECT -> Triple(MeshGreen, InkSoft, MeshGreen)
            TopoKind.REACHABLE -> Triple(Cyan, InkSoft, Cyan)
            TopoKind.SEARCHING -> Triple(MeshAmber, InkSoft, MeshAmber)
            TopoKind.STALE -> Triple(TextSecondary, InkSoft, TextSecondary)
        }
        // 节点填充
        drawCircle(
            color = fill,
            radius = n.r,
            center = Offset(cx, cy),
        )
        // 描边
        drawCircle(
            color = stroke,
            radius = n.r,
            center = Offset(cx, cy),
            style = Stroke(width = 3f * scale),
        )
        // 节点内短 ID（monospace）
        drawIntoCanvas { c ->
            val paint = android.graphics.Paint().apply {
                color = label.toArgb()
                textSize = 11f * scale
                isAntiAlias = true
                textAlign = android.graphics.Paint.Align.CENTER
                typeface = android.graphics.Typeface.MONOSPACE
            }
            val fm = paint.fontMetrics
            val baseline = cy - (fm.ascent + fm.descent) / 2f
            c.nativeCanvas.drawText(n.short, cx, baseline, paint)
        }
        // 本机下方小三角标识
        if (n.kind == TopoKind.ME) {
            val p = androidx.compose.ui.graphics.Path().apply {
                moveTo(cx, cy + n.r + 3f * scale)
                lineTo(cx - 4f * scale, cy + n.r + 9f * scale)
                lineTo(cx + 4f * scale, cy + n.r + 9f * scale)
                close()
            }
            drawPath(p, Cyan.copy(alpha = 0.8f))
        }
        // 昵称标签（节点上方）
        drawIntoCanvas { c ->
            val paint = android.graphics.Paint().apply {
                color = Color(0xFFF5F7FA).copy(alpha = 0.9f).toArgb()
                textSize = 15f * scale
                isAntiAlias = true
                textAlign = android.graphics.Paint.Align.CENTER
            }
            c.nativeCanvas.drawText(n.name, cx, cy - n.r - 12f * scale, paint)
        }
    }
}
