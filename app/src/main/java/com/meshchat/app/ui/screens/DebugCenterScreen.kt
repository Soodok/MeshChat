package com.meshchat.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
            if (settings.showBle) BleCard(snapshot)
            if (settings.showRoutes) RoutesCard(snapshot)
            if (settings.showDelivery) DeliveryCard(snapshot)
            if (settings.showFile) FileCard(snapshot)
            // 底部系统栏（常驻）
            Text(
                "运行 ${snapshot.system.uptimeMs / 1000}s · 服务 ${if (snapshot.system.serviceStarted) "ON" else "OFF"} · " +
                    "蓝牙 ${if (snapshot.system.bluetoothEnabled) "ON" else "OFF"} · " +
                    "内存 ${kb(snapshot.system.freeMemoryKb * 1024)}/${kb(snapshot.system.totalMemoryKb * 1024)}",
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
            StatRow(
                "${p.shortId} ${p.displayName}".trim(),
                "${p.presence} · ${p.rssi}dBm(${p.bars}) · ${p.hops}跳${p.relayVia?.let { " 经$it" } ?: ""} · " +
                    if (p.lastSeenAgoMs >= 0) "${p.lastSeenAgoMs}ms前" else "未见",
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
                "收发包" to s.showFrames, "BLE" to s.showBle, "信号" to s.showRoutes,
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
    "BLE" -> s.copy(showBle = !s.showBle)
    "信号" -> s.copy(showRoutes = !s.showRoutes)
    "送达" -> s.copy(showDelivery = !s.showDelivery)
    "文件" -> s.copy(showFile = !s.showFile)
    else -> s
}
