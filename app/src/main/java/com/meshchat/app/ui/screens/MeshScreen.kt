package com.meshchat.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.BluetoothSearching
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.meshchat.app.data.MeshPeer
import com.meshchat.app.mesh.quality.BluetoothQuality
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
    onStartDiscovery: () -> Unit,
    onPeerSelected: (String) -> Unit,
) {
    var discovering by remember { mutableStateOf(true) }   // 进入即自动开始寻找（服务随 App 启动）
    LazyColumn(modifier = modifier, contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 18.dp)) {
        item { MeshTopology(peers = peers, sessions = sessions) }
        item {
            Text("附近节点（${peers.size}）", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp))
        }
        if (peers.isEmpty()) {
            item {
                Text(
                    text = if (discovering) "正在扫描邻近节点…" else "暂无邻近节点，请点击重新发现",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                )
            }
        }
        items(peers, key = { it.shortId }) { peer ->
            PeerRow(
                peer = peer,
                connected = peer.shortId in sessions,
                pending = peer.shortId in pendingInvites,
                onClick = { onPeerSelected(peer.shortId) },
            )
        }
        item {
            Button(
                onClick = {
                    onStartDiscovery()
                    discovering = true
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 24.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Color(0xFF081420)),
            ) {
                Icon(Icons.AutoMirrored.Outlined.BluetoothSearching, null)
                Text(if (discovering) "重新发现" else "开始附近发现", modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun MeshTopology(peers: List<MeshPeer>, sessions: Set<String>) {
    // 物理状态：节点位置/速度（运行时维护，跨 peers 变化保留）
    var canvasW by remember { mutableFloatStateOf(0f) }
    var canvasH by remember { mutableFloatStateOf(0f) }
    val nodes = remember { mutableStateListOf<TopoNode>() }
    var draggingNode by remember { mutableStateOf<TopoNode?>(null) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    // 帧计数器：触发 Canvas 重绘（节点内部 var 改动不触发重组，靠此驱动）
    var frame by remember { mutableIntStateOf(0) }

    // 同步 peers → nodes（保留已有节点位置，避免重组时跳变）
    LaunchedEffect(peers, sessions, canvasW, canvasH) {
        if (canvasW <= 0f || canvasH <= 0f) return@LaunchedEffect
        val existing = nodes.associateBy { it.id }
        val cx = canvasW / 2f
        val cy = canvasH / 2f
        nodes.clear()
        // 本机（保留物理状态）
        val oldMe = existing["ME"]
        nodes.add(TopoNode(
            id = "ME", name = "你", short = "ME", kind = TopoKind.ME, hops = 0, r = 7f,
            x = oldMe?.x ?: cx, y = oldMe?.y ?: cy,
            vx = oldMe?.vx ?: 0f, vy = oldMe?.vy ?: 0f,
        ))
        // 一跳节点（v1.0.x 全部为直连；多跳中继 v1.1.0 实装后会有真正的 REACHABLE）
        peers.forEach { peer ->
            // v1.0.x 三色映射：已会话=绿(DIRECT)，在线未会话=蓝(REACHABLE)，离线=灰(STALE)
            val actualKind = when {
                peer.shortId in sessions -> TopoKind.DIRECT
                peer.presence == PeerPresence.OFFLINE -> TopoKind.STALE
                else -> TopoKind.REACHABLE
            }
            val r = if (peer.hops <= 1) 7f else if (peer.hops == 2) 6f else 5f
            val old = existing[peer.shortId]
            nodes.add(TopoNode(
                id = peer.shortId, name = peer.name, short = peer.shortId.take(2),
                kind = actualKind, hops = peer.hops, r = r,
                x = old?.x ?: (cx + (Random.nextFloat() - 0.5f) * 60f),
                y = old?.y ?: (cy + (Random.nextFloat() - 0.5f) * 60f),
                vx = old?.vx ?: 0f, vy = old?.vy ?: 0f,
            ))
        }
    }

    // 物理循环（~60fps）
    LaunchedEffect(canvasW, canvasH) {
        if (canvasW <= 0f || canvasH <= 0f) return@LaunchedEffect
        while (true) {
            delay(16)
            topologyPhysicsStep(nodes, canvasW, canvasH)
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
                .height(340.dp)
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
                        detectDragGestures(
                            onDragStart = { offset ->
                                // 命中检测：找最近节点
                                draggingNode = nodes.minByOrNull {
                                    val dx = it.x - offset.x; val dy = it.y - offset.y
                                    dx * dx + dy * dy
                                }?.takeIf {
                                    val dx = it.x - offset.x; val dy = it.y - offset.y
                                    sqrt(dx * dx + dy * dy) <= it.r + 12f
                                }
                                draggingNode?.let { it.vx = 0f; it.vy = 0f }
                                if (draggingNode == null) {
                                    // 点击空白：取消选中
                                    selectedId = null
                                }
                            },
                            onDrag = { _, drag ->
                                draggingNode?.let {
                                    it.x += drag.x
                                    it.y += drag.y
                                }
                            },
                            onDragEnd = {
                                draggingNode?.let {
                                    it.vx = (Random.nextFloat() - 0.5f) * 0.4f
                                    it.vy = (Random.nextFloat() - 0.5f) * 0.4f
                                }
                                // 短按（未实际拖动）→ 选中
                                draggingNode?.let { selectedId = it.id }
                                draggingNode = null
                            },
                            onDragCancel = { draggingNode = null },
                        )
                    },
            ) {
                // 读 frame 建立重绘依赖（节点内部 var 改动靠此驱动重绘）
                @Suppress("UNUSED_VARIABLE") val redrawTrigger = frame
                drawDotGrid()
                drawTopologyEdges(nodes, selectedId)
                drawTopologyNodes(nodes, selectedId)
            }
        }
        Row(
            modifier = Modifier.padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Hub, null, tint = Cyan, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(8.dp))
            val onlineCount = peers.count { it.presence != PeerPresence.OFFLINE }
            val offlineCount = peers.count { it.presence == PeerPresence.OFFLINE }
            val sessionCount = peers.count { it.shortId in sessions }
            Text(
                "已会话 $sessionCount · 在线 $onlineCount · 失联 $offlineCount",
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
        }
    }
}

@Composable
private fun PeerRow(peer: MeshPeer, connected: Boolean, pending: Boolean, onClick: () -> Unit) {
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                SignalBars(peer.strength)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "${peer.rssi} dBm · 等级${BluetoothQuality.grade(peer.rssi).label}",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = if (peer.presence == PeerPresence.OFFLINE) TextSecondary else (if (peer.lost) MeshAmber else TextSecondary),
                )
            }
            val statusText = when (peer.presence) {
                PeerPresence.ONLINE -> when {
                    connected -> "已连接 · 点击进入会话"
                    pending -> "等待对方接受"
                    else -> "点击发起对话"
                }
                PeerPresence.SEARCHING -> "寻找中…"
                PeerPresence.RECONNECTING -> "断线重连中…"
                PeerPresence.OFFLINE -> "离线"
            }
            val statusColor = when (peer.presence) {
                PeerPresence.ONLINE -> if (connected) MeshGreen else TextSecondary
                PeerPresence.SEARCHING, PeerPresence.RECONNECTING -> MeshAmber
                PeerPresence.OFFLINE -> TextSecondary
            }
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = statusColor,
            )
        }
    }
    HorizontalDivider(color = MeshDivider, modifier = Modifier.padding(start = 80.dp))
}

// ===== 拓扑图数据模型 + 物理引擎 + 绘制（力导向 mesh 拓扑）=====

/** 拓扑节点分类（三色制：本机/直连绿/多跳蓝/失联灰）*/
private enum class TopoKind { ME, DIRECT, REACHABLE, STALE }

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

/** 物理引擎：库仑斥力 + 边弹簧 + 阻尼 + 微扰 + 边界反弹（无中心引力，自然分布）*/
private fun topologyPhysicsStep(nodes: List<TopoNode>, w: Float, h: Float) {
    val repulsion = 700f       // 库仑斥力系数
    val springK = 0.014f       // 弹簧刚度
    val springLen = 48f        // 弹簧自然长度
    val damping = 0.9f         // 速度阻尼
    val jitter = 0.015f        // 微扰（模拟设备移动）
    val maxSpeed = 2f          // 限速
    val margin = 30f           // 边界反弹区
    val me = nodes.firstOrNull { it.id == "ME" } ?: return

    // 1. 库仑斥力（O(n²)）
    for (i in nodes.indices) {
        for (j in i + 1 until nodes.size) {
            val a = nodes[i]; val b = nodes[j]
            var dx = b.x - a.x; var dy = b.y - a.y
            var dist = sqrt(dx * dx + dy * dy)
            if (dist < 1f) { dist = 1f; dx = 1f; dy = 0f }
            val f = repulsion / (dist * dist)
            val fx = dx / dist * f; val fy = dy / dist * f
            a.vx -= fx; a.vy -= fy
            b.vx += fx; b.vy += fy
        }
    }
    // 2. 边弹簧力（本机 ↔ peer，对称）
    nodes.forEach { n ->
        if (n.id == "ME") return@forEach
        var dx = n.x - me.x; var dy = n.y - me.y
        var dist = sqrt(dx * dx + dy * dy)
        if (dist < 1f) { dist = 1f; dx = 1f; dy = 0f }
        // 失联节点弹簧更弱（残存链路）
        val k = if (n.kind == TopoKind.STALE) springK * 0.2f else springK
        val diff = dist - springLen
        val fx = dx / dist * diff * k; val fy = dy / dist * diff * k
        me.vx += fx; me.vy += fy
        n.vx -= fx; n.vy -= fy
    }
    // 3. 阻尼 + 微扰 + 限速 + 位置更新 + 边界反弹
    nodes.forEach { n ->
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
    val spacing = 24f
    val dotColor = Cyan.copy(alpha = 0.06f)
    var y = spacing
    while (y < size.height) {
        var x = spacing
        while (x < size.width) {
            drawCircle(dotColor, 1f, Offset(x, y))
            x += spacing
        }
        y += spacing
    }
}

/** 绘制拓扑边（本机 ↔ peer，按节点状态着色）*/
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTopologyEdges(
    nodes: List<TopoNode>, selectedId: String?,
) {
    val me = nodes.firstOrNull { it.id == "ME" } ?: return
    nodes.forEach { n ->
        if (n.id == "ME") return@forEach
        val isSelEdge = selectedId != null && (n.id == selectedId || "ME" == selectedId)
        val isDim = selectedId != null && !isSelEdge
        val start = Offset(me.x, me.y)
        val end = Offset(n.x, n.y)
        when (n.kind) {
            TopoKind.STALE -> drawLine(
                color = TextSecondary.copy(alpha = if (isDim) 0.05f else 0.3f),
                start = start, end = end, strokeWidth = 1f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 4f), 0f),
            )
            TopoKind.DIRECT -> drawLine(
                color = MeshGreen.copy(alpha = if (isDim) 0.2f else if (isSelEdge) 1f else 0.8f),
                start = start, end = end, strokeWidth = if (isSelEdge) 2f else 1.5f,
                cap = StrokeCap.Round,
            )
            TopoKind.REACHABLE -> drawLine(
                color = Cyan.copy(alpha = if (isDim) 0.05f else if (isSelEdge) 0.7f else 0.25f),
                start = start, end = end, strokeWidth = if (isSelEdge) 1.5f else 1f,
                cap = StrokeCap.Round,
            )
            TopoKind.ME -> { /* 不会到达 */ }
        }
    }
}

/** 绘制拓扑节点 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTopologyNodes(
    nodes: List<TopoNode>, selectedId: String?,
) {
    nodes.forEach { n ->
        val isSel = n.id == selectedId
        val isDim = selectedId != null && !isSel
        val (stroke, fill, label) = when (n.kind) {
            TopoKind.ME -> Triple(Cyan, Cyan, Color(0xFF081420))
            TopoKind.DIRECT -> Triple(MeshGreen, InkSoft, MeshGreen)
            TopoKind.REACHABLE -> Triple(Cyan, InkSoft, Cyan)
            TopoKind.STALE -> Triple(TextSecondary, InkSoft, TextSecondary)
        }
        // 选中：白色外圈光晕
        if (isSel) {
            drawCircle(Color.White.copy(alpha = 0.5f), n.r + 5f, Offset(n.x, n.y))
        }
        // 节点填充
        drawCircle(
            color = if (isDim) fill.copy(alpha = 0.5f) else fill,
            radius = n.r,
            center = Offset(n.x, n.y),
        )
        // 描边
        drawCircle(
            color = if (isDim) stroke.copy(alpha = 0.4f) else stroke,
            radius = n.r,
            center = Offset(n.x, n.y),
            style = Stroke(width = if (isSel) 2.4f else 1.8f),
        )
        // 节点内短 ID（monospace）
        drawIntoCanvas { c ->
            val paint = android.graphics.Paint().apply {
                color = (if (isDim) label.copy(alpha = 0.4f) else label).toArgb()
                textSize = 7f * 2f  // px 粗略，Canvas 单位是 px
                isAntiAlias = true
                textAlign = android.graphics.Paint.Align.CENTER
                typeface = android.graphics.Typeface.MONOSPACE
            }
            val fm = paint.fontMetrics
            val baseline = n.y - (fm.ascent + fm.descent) / 2f
            c.nativeCanvas.drawText(n.short, n.x, baseline, paint)
        }
        // 本机下方小三角标识
        if (n.kind == TopoKind.ME) {
            val p = androidx.compose.ui.graphics.Path().apply {
                moveTo(n.x, n.y + n.r + 2f)
                lineTo(n.x - 2f, n.y + n.r + 5f)
                lineTo(n.x + 2f, n.y + n.r + 5f)
                close()
            }
            drawPath(p, Cyan.copy(alpha = 0.8f))
        }
        // 昵称标签（节点上方）
        drawIntoCanvas { c ->
            val paint = android.graphics.Paint().apply {
                color = (if (isSel) Color.White else Color(0xFFF5F7FA)).copy(
                    alpha = if (isDim) 0.35f else 0.9f
                ).toArgb()
                textSize = 8f * 2f
                isAntiAlias = true
                textAlign = android.graphics.Paint.Align.CENTER
                if (isSel) typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            c.nativeCanvas.drawText(n.name, n.x, n.y - n.r - 6f, paint)
        }
    }
}
