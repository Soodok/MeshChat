package com.meshchat.app.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.meshchat.app.data.MeshPeer
import com.meshchat.app.ui.components.SignalBars
import com.meshchat.app.ui.theme.Cyan
import com.meshchat.app.ui.theme.Divider as MeshDivider
import com.meshchat.app.ui.theme.InkSoft
import com.meshchat.app.ui.theme.MeshGreen
import com.meshchat.app.ui.theme.TextSecondary

@Composable
fun MeshScreen(
    modifier: Modifier = Modifier,
    peers: List<MeshPeer>,
    onStartDiscovery: () -> Unit,
) {
    LazyColumn(modifier = modifier, contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 18.dp)) {
        item { MeshTopology() }
        item {
            Text("附近节点（${peers.size}）", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp))
        }
        if (peers.isEmpty()) {
            item {
                Text(
                    text = "暂无邻近节点，请开始附近发现",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                )
            }
        }
        items(peers, key = { it.name }) { peer -> PeerRow(peer) }
        item {
            Button(
                onClick = onStartDiscovery,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 24.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Color(0xFF081420)),
            ) {
                Icon(Icons.AutoMirrored.Outlined.BluetoothSearching, null)
                Text("开始附近发现", modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun MeshTopology() {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(modifier = Modifier.fillMaxWidth().height(270.dp), contentAlignment = Alignment.Center) {
            androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                val center = this.center
                val nodes = listOf(
                    androidx.compose.ui.geometry.Offset(size.width * .5f, 38f),
                    androidx.compose.ui.geometry.Offset(size.width * .15f, size.height * .38f),
                    androidx.compose.ui.geometry.Offset(size.width * .85f, size.height * .38f),
                    androidx.compose.ui.geometry.Offset(size.width * .25f, size.height * .82f),
                    androidx.compose.ui.geometry.Offset(size.width * .75f, size.height * .82f),
                )
                nodes.forEach { node -> drawLine(Cyan.copy(alpha = .42f), center, node, 2.dp.toPx(), cap = StrokeCap.Round) }
                drawCircle(Cyan.copy(alpha = .12f), 54.dp.toPx(), center)
                drawCircle(Cyan, 40.dp.toPx(), center)
                nodes.forEach { node ->
                    drawCircle(InkSoft, 28.dp.toPx(), node)
                    drawCircle(Cyan.copy(alpha = .7f), 28.dp.toPx(), node, style = Stroke(1.dp.toPx()))
                }
            }
            Icon(Icons.Outlined.Hub, null, tint = Color(0xFF081420), modifier = Modifier.size(34.dp))
            Text("你", color = TextSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.align(Alignment.Center).padding(top = 106.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Hub, null, tint = Cyan, modifier = Modifier.size(19.dp))
            Text("2 跳路由可用", color = TextSecondary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun PeerRow(peer: MeshPeer) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(42.dp).clip(androidx.compose.foundation.shape.CircleShape).background(InkSoft), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.PhoneAndroid, null, tint = TextSecondary)
        }
        Spacer(Modifier.width(14.dp))
        Text(peer.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SignalBars(peer.strength)
            Text(
                text = if (peer.hops == 1) "直接 · 1 跳" else "${peer.hops} 跳",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
    HorizontalDivider(color = MeshDivider, modifier = Modifier.padding(start = 80.dp))
}
