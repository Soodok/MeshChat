package com.meshchat.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.meshchat.app.mesh.debug.DebugSnapshot
import com.meshchat.app.mesh.debug.FrameKind
import com.meshchat.app.ui.MeshChatViewModel
import com.meshchat.app.ui.theme.Cyan
import com.meshchat.app.ui.theme.Ink
import com.meshchat.app.ui.theme.InkSoft
import com.meshchat.app.ui.theme.MeshAmber
import com.meshchat.app.ui.theme.MeshGreen
import com.meshchat.app.ui.theme.MeshRed
import com.meshchat.app.ui.theme.TextSecondary

private fun kb(v: Long) = if (v >= 1024) "%.1fKB".format(v / 1024.0) else "${v}B"
private fun rate(v: Double, perMinute: Boolean) =
    if (perMinute) "%.0f/min".format(v * 60) else "%.1f/s".format(v)

@Composable
fun DebugCenterScreen(
    snapshot: DebugSnapshot,
    settings: MeshChatViewModel.DebugSettings,
    onSettingsChange: (MeshChatViewModel.DebugSettings) -> Unit,
    onReset: () -> Unit,
    onBack: () -> Unit,
    controlState: MeshChatViewModel.DebugControlState,
    onControl: (com.meshchat.app.mesh.debug.DebugControl) -> Unit,
    onResetControls: () -> Unit,
    oscHistory: List<MeshChatViewModel.OscPoint>,
) {
    var settingsOpen by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxSize().background(Ink)) {
        Row(
            modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
            Text("调试中心", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = { onSettingsChange(settings.copy(paused = !settings.paused)) }) {
                Text(if (settings.paused) "继续" else "暂停")
            }
            TextButton(onClick = onReset) { Text("清零") }
            IconButton(onClick = { settingsOpen = true }) { Icon(Icons.Outlined.Settings, "设置") }
        }
        if (settingsOpen) {
            DebugSettingsPanel(settings, onSettingsChange, onClose = { settingsOpen = false })
        }
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (settings.showFrames) FramesCard(snapshot, settings.perMinute)
            if (settings.showOsc) OscilloscopeCard(oscHistory)
            if (settings.showFailure) FailureCard(snapshot)
            if (settings.showControl) ControlCard(controlState, onControl, onResetControls)
            if (settings.showBle) BleCard(snapshot)
            if (settings.showRoutes) RoutesCard(snapshot)
            if (settings.showDelivery) DeliveryCard(snapshot)
            if (settings.showFile) FileCard(snapshot)
            // 底部系统栏（常驻）——内存为本进程指标：Java 堆已用/上限 + PSS 真实占用
            Text(
                "运行 ${snapshot.system.uptimeMs / 1000}s · 服务 ${if (snapshot.system.serviceStarted) "ON" else "OFF"} · " +
                    "蓝牙 ${if (snapshot.system.bluetoothEnabled) "ON" else "OFF"} · " +
                    "内存 堆${kb(snapshot.system.heapUsedKb * 1024)}/${kb(snapshot.system.heapMaxKb * 1024)} · PSS ${kb(snapshot.system.pssKb * 1024)}",
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.padding(bottom = 16.dp),
            )
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(InkSoft.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
            .padding(12.dp),
    ) {
        Text(title, color = Cyan, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 8.dp))
        content()
    }
}

@Composable
private fun monoStyle() = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)

@Composable
private fun StatRow(label: String, value: String, color: Color = TextSecondary) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = TextSecondary, style = monoStyle(), maxLines = 1, softWrap = false)
        Text(value, color = color, style = monoStyle(), maxLines = 1, softWrap = false)
    }
}

@Composable
private fun FramesCard(snap: DebugSnapshot, perMinute: Boolean) {
    SectionCard("收发包 · 速率") {
        val totalSent = snap.frames.values.sumOf { it.sent }
        val totalRecv = snap.frames.values.sumOf { it.received }
        val sentRate = snap.frames.values.sumOf { it.sentRatePerSec }
        val recvRate = snap.frames.values.sumOf { it.receivedRatePerSec }
        StatRow("总发送/接收", "↑${rate(sentRate, perMinute)} ↓${rate(recvRate, perMinute)} · ${totalSent}/${totalRecv} 包", Cyan)
        FrameKind.entries.forEach { kind ->
            val f = snap.frames[kind] ?: return@forEach
            StatRow(
                kind.name,
                "↑${rate(f.sentRatePerSec, perMinute)} ↓${rate(f.receivedRatePerSec, perMinute)} · " +
                    "${f.sent}/${f.received} · ${kb(f.sentBytes)}/${kb(f.receivedBytes)}",
            )
        }
    }
}

@Composable
private fun BleCard(snap: DebugSnapshot) {
    val b = snap.ble
    SectionCard("BLE 传输层") {
        StatRow("广播", "${b.broadcastCount} 次 · ${kb(b.broadcastBytes)} · ${rate(b.broadcastRatePerSec, false)}")
        StatRow("扫描结果", b.scanResultCount.toString())
        StatRow("GATT 连接", "尝试 ${b.gattConnectAttempts} · 成功 ${b.gattConnectSuccess} · 当前 ${b.gattCurrent} · 断开 ${b.gattDisconnects}")
        StatRow("MTU", b.mtu.toString())
        StatRow("写入", "成功 ${b.writeSuccess} · 失败 ${b.writeFailed}", if (b.writeFailed > 0) MeshAmber else TextSecondary)
        StatRow("Notify", "成功 ${b.notifySuccess} · 失败 ${b.notifyFailed}", if (b.notifyFailed > 0) MeshAmber else TextSecondary)
        StatRow("收到写请求", b.writeRequestsReceived.toString())
        StatRow("服务发现", "成功 ${b.servicesDiscovered} · 重试 ${b.servicesDiscoverRetries}")
    }
}

@Composable
private fun RoutesCard(snap: DebugSnapshot) {
    SectionCard("信号与路由") {
        StatRow("节点/会话/待邀请", "${snap.peers.size} · ${snap.sessions} · ${snap.pendingInvites}")
        StatRow("2 跳路由条目", snap.routeEntries.toString())
        snap.peers.forEach { p ->
            // 协议层信号强度（PING 序列号缺口统计成功率；-1 = 样本不足/对端老版本无 seq）
            val link = if (p.linkSuccessRate >= 0) " · 信号${(p.linkSuccessRate * 100).toInt()}%(${p.linkSamples}包)" else ""
            val tx = if (p.txPower > Int.MIN_VALUE) " · TX${p.txPower}dBm" else ""
            val sigColor = when {
                p.linkSuccessRate < 0 -> TextSecondary
                p.linkSuccessRate >= 0.9 -> MeshGreen
                p.linkSuccessRate >= 0.6 -> Cyan
                else -> MeshAmber
            }
            StatRow(
                "${p.shortId} ${p.displayName}".trim(),
                "${p.presence} · ${p.rssi}dBm(${p.bars}) · ${p.hops}跳${p.relayVia?.let { " 经$it" } ?: ""} · " +
                    (if (p.lastSeenAgoMs >= 0) "${p.lastSeenAgoMs}ms前" else "未见") + link + tx,
                sigColor,
            )
        }
    }
}

@Composable
private fun DeliveryCard(snap: DebugSnapshot) {
    val d = snap.delivery
    SectionCard("送达链路") {
        StatRow("待确认/已确认", "${d.pending} · ${d.confirmed}", if (d.pending > 0) MeshAmber else MeshGreen)
        StatRow("确认率", "%.1f%%".format(d.confirmationRate * 100))
        StatRow("重发", d.resends.toString(), if (d.resends > 0) MeshAmber else TextSecondary)
        StatRow("重发分布", d.resendHistogram.entries.sortedBy { it.key }.joinToString(" ") { "${it.key}x:${it.value}" })
        StatRow("路由决策", "投递 ${d.deliverCount} · 转发 ${d.forwardCount} · 丢弃 ${d.dropCount}")
        StatRow("中继转发帧", d.relayedFrames.toString())
    }
}

@Composable
private fun FileCard(snap: DebugSnapshot) {
    val f = snap.file
    SectionCard("文件传输") {
        if (!f.activeTransfer) {
            StatRow("当前", "空闲", TextSecondary)
        } else {
            StatRow("方向/文件", "${f.direction} · ${f.fileName}")
            StatRow("进度", "${f.chunksProgress}/${f.chunksTotal} 块 · ${f.percent}%", Cyan)
        }
        StatRow("窗口重发", f.windowRetries.toString())
    }
}

@Composable
private fun DebugSettingsPanel(
    s: MeshChatViewModel.DebugSettings,
    onChange: (MeshChatViewModel.DebugSettings) -> Unit,
    onClose: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().background(InkSoft.copy(alpha = 0.5f)).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("调节", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onClose) { Text("关闭") }
        }
        Text("刷新间隔", color = TextSecondary, style = monoStyle())
        Row {
            listOf(500L to "0.5s", 1_000L to "1s", 2_000L to "2s", 5_000L to "5s").forEach { (v, label) ->
                FilterChip(selected = s.refreshIntervalMs == v, onClick = { onChange(s.copy(refreshIntervalMs = v)) }, label = { Text(label) }, modifier = Modifier.padding(end = 6.dp))
            }
        }
        Text("速率窗口", color = TextSecondary, style = monoStyle(), modifier = Modifier.padding(top = 8.dp))
        Row {
            listOf(1_000L to "1s", 3_000L to "3s", 5_000L to "5s", 10_000L to "10s").forEach { (v, label) ->
                FilterChip(selected = s.windowMs == v, onClick = { onChange(s.copy(windowMs = v)) }, label = { Text(label) }, modifier = Modifier.padding(end = 6.dp))
            }
        }
        Text("速率单位", color = TextSecondary, style = monoStyle(), modifier = Modifier.padding(top = 8.dp))
        Row {
            FilterChip(selected = !s.perMinute, onClick = { onChange(s.copy(perMinute = false)) }, label = { Text("包/s") }, modifier = Modifier.padding(end = 6.dp))
            FilterChip(selected = s.perMinute, onClick = { onChange(s.copy(perMinute = true)) }, label = { Text("包/min") })
        }
        Text("板块", color = TextSecondary, style = monoStyle(), modifier = Modifier.padding(top = 8.dp))
        Row {
            listOf(
                "收发包" to s.showFrames, "示波器" to s.showOsc, "失败包" to s.showFailure, "控制" to s.showControl, "BLE" to s.showBle, "信号" to s.showRoutes,
                "送达" to s.showDelivery, "文件" to s.showFile,
            ).forEach { (label, on) ->
                FilterChip(
                    selected = on,
                    onClick = { onChange(toggleSection(s, label)) },
                    label = { Text(label) },
                    modifier = Modifier.padding(end = 6.dp),
                )
            }
        }
        Text("节点排序", color = TextSecondary, style = monoStyle(), modifier = Modifier.padding(top = 8.dp))
        Row {
            listOf("rssi" to "RSSI", "name" to "昵称", "recent" to "最近").forEach { (v, label) ->
                FilterChip(selected = s.sortBy == v, onClick = { onChange(s.copy(sortBy = v)) }, label = { Text(label) }, modifier = Modifier.padding(end = 6.dp))
            }
        }
    }
}

private fun toggleSection(s: MeshChatViewModel.DebugSettings, label: String): MeshChatViewModel.DebugSettings = when (label) {
    "收发包" -> s.copy(showFrames = !s.showFrames)
    "示波器" -> s.copy(showOsc = !s.showOsc)
    "失败包" -> s.copy(showFailure = !s.showFailure)
    "控制" -> s.copy(showControl = !s.showControl)
    "BLE" -> s.copy(showBle = !s.showBle)
    "信号" -> s.copy(showRoutes = !s.showRoutes)
    "送达" -> s.copy(showDelivery = !s.showDelivery)
    "文件" -> s.copy(showFile = !s.showFile)
    else -> s
}

@Composable
private fun ControlCard(
    s: MeshChatViewModel.DebugControlState,
    onControl: (com.meshchat.app.mesh.debug.DebugControl) -> Unit,
    onResetControls: () -> Unit,
) {
    SectionCard("主动控制") {
        // 当前生效配置汇总（高密度终端风格）
        StatRow("心跳", "${s.heartbeatMs}ms · 失联阈值 ${s.lostMs}ms（固定）", Cyan)
        Row {
            listOf(50L to "0.05s", 100L to "0.1s", 200L to "0.2s", 400L to "0.4s").forEach { (v, label) ->
                FilterChip(
                    selected = s.heartbeatMs == v,
                    onClick = { onControl(com.meshchat.app.mesh.debug.DebugControl.SetHeartbeat(v)) },
                    label = { Text(label) },
                    modifier = Modifier.padding(end = 6.dp),
                )
            }
        }
        StatRow("重发退避", "基础 ${s.resendBaseMs}ms · 封顶 ${s.resendMaxMs}ms")
        Row {
            listOf(
                3_000L to "3s",
                10_000L to "10s",
                30_000L to "30s",
            ).forEach { (v, label) ->
                FilterChip(
                    selected = s.resendBaseMs == v,
                    onClick = { onControl(com.meshchat.app.mesh.debug.DebugControl.SetResendPolicy(v, when (v) { 3_000L -> 30_000L; 10_000L -> 60_000L; else -> 120_000L })) },
                    label = { Text(label) },
                    modifier = Modifier.padding(end = 6.dp),
                )
            }
        }
        StatRow("广播功率", "${s.txPowerDbm}dBm · 越高越远越耗电")
        Row {
            listOf(1 to "1", -7 to "-7", -15 to "-15", -21 to "-21").forEach { (v, label) ->
                FilterChip(
                    selected = s.txPowerDbm == v,
                    onClick = { onControl(com.meshchat.app.mesh.debug.DebugControl.SetTxPower(v)) },
                    label = { Text(label) },
                    modifier = Modifier.padding(end = 6.dp),
                )
            }
        }
        Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = s.signalingSuspended,
                onClick = {
                    onControl(
                        if (s.signalingSuspended) com.meshchat.app.mesh.debug.DebugControl.ResumeSignaling
                        else com.meshchat.app.mesh.debug.DebugControl.SuspendSignaling,
                    )
                },
                label = { Text(if (s.signalingSuspended) "恢复广播+扫描" else "暂停广播+扫描") },
            )
            FilterChip(
                selected = false,
                onClick = { onControl(com.meshchat.app.mesh.debug.DebugControl.BroadcastPing) },
                label = { Text("发 PING") },
            )
            TextButton(onClick = onResetControls) { Text("恢复默认") }
        }
        StatRow(
            "手动 PING",
            if (s.lastPingAtMs >= 0) "${(System.currentTimeMillis() - s.lastPingAtMs).coerceAtLeast(0)}ms 前 · 共 ${s.manualPingCount} 次"
            else "未发送",
        )
    }
}

@Composable
private fun FailureCard(snap: DebugSnapshot) {
    val f = snap.failures
    SectionCard("失败包") {
        StatRow("接收解码失败", "${f.receivedDecodeFailures} 次 · ${"%.1f/s".format(f.receivedDecodeRatePerSec)}", if (f.receivedDecodeFailures > 0) MeshAmber else TextSecondary)
        StatRow("送达不可确认", "${f.unconfirmed} 包", if (f.unconfirmed > 0) MeshAmber else MeshGreen)
        StatRow("BLE 写失败", f.bleWriteFailed.toString(), if (f.bleWriteFailed > 0) MeshAmber else TextSecondary)
        StatRow("BLE notify 失败", f.bleNotifyFailed.toString(), if (f.bleNotifyFailed > 0) MeshAmber else TextSecondary)
    }
}

/** 示波器：横轴=最近 96 个采样点（时间窗口随刷新间隔），绿=发送速率、蓝=接收速率、红=失败速率（包/秒，与收发包同单位）。 */
@Composable
private fun OscilloscopeCard(history: List<MeshChatViewModel.OscPoint>) {
    val last = history.lastOrNull()
    SectionCard("示波器") {
        StatRow(
            "实时速率",
            "↑${"%.1f/s".format(last?.sentRate ?: 0.0)} ↓${"%.1f/s".format(last?.recvRate ?: 0.0)} · 丢包 ${"%.1f/s".format(last?.failureRate ?: 0.0)}",
            Cyan,
        )
        androidx.compose.foundation.Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.5f)),
        ) {
            val w = size.width
            val h = size.height
            if (w <= 0f || h <= 0f) return@Canvas
            // 网格（示波器风格）
            val grid = Cyan.copy(alpha = 0.12f)
            for (i in 1 until 12) {
                val x = w * i / 12f
                drawLine(grid, androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Offset(x, h), 1f)
            }
            for (i in 1 until 4) {
                val y = h * i / 4f
                drawLine(grid, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(w, y), 1f)
            }
            if (history.size < 2) return@Canvas
            // y 轴动态缩放：收发速率以历史峰值为满量程；失败速率独立缩放（量级通常远小于收发）
            val maxRate = history.maxOf { maxOf(it.sentRate, it.recvRate) }.coerceAtLeast(0.1)
            val maxFail = history.maxOf { it.failureRate }.coerceAtLeast(0.1)
            val n = history.size
            val step = w / 95f
            fun yOf(rate: Double): Float = (h - (rate / maxRate * h).toFloat()).coerceIn(0f, h)
            fun yOfFail(rate: Double): Float = (h - (rate / maxFail * h).toFloat()).coerceIn(0f, h)
            fun trace(yMap: (Double) -> Float, selector: (MeshChatViewModel.OscPoint) -> Double): androidx.compose.ui.graphics.Path {
                val p = androidx.compose.ui.graphics.Path()
                history.forEachIndexed { i, pt ->
                    val x = w - (n - 1 - i) * step   // 最新采样在右端
                    val y = yMap(selector(pt))
                    if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                return p
            }
            // 发送绿线 / 接收蓝线
            drawPath(trace(::yOf) { it.sentRate }, MeshGreen, style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
            drawPath(trace(::yOf) { it.recvRate }, Cyan, style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
            // 失败速率红线（独立缩放：丢包/失败事件包每秒，与收发包同单位）
            drawPath(trace(::yOfFail) { it.failureRate }, MeshRed, style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
            // 扫描头：最新发送速率亮点（history.size >= 2 已保证 last 非空）
            drawCircle(Cyan, 3.5f, androidx.compose.ui.geometry.Offset(w, yOf(history.last().sentRate)))
        }
    }
}
