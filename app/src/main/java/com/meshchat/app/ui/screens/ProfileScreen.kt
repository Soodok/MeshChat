package com.meshchat.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.meshchat.app.ui.theme.Cyan
import com.meshchat.app.ui.theme.Divider as MeshDivider
import com.meshchat.app.ui.theme.InkSoft
import com.meshchat.app.ui.theme.TextSecondary

@Composable
fun ProfileScreen(
    modifier: Modifier = Modifier,
    /** v1.1.91 本机昵称：替换顶部固定的"我"标识，随设置实时显示。 */
    displayName: String,
    onOpenKeys: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenSecurityCenter: () -> Unit,
    onOpenDebugCenter: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())   // v1.1.91 我的页可滚动，防小屏内容被遮挡
            .padding(top = 14.dp),
    ) {
        Row(modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(64.dp).clip(androidx.compose.foundation.shape.CircleShape).background(InkSoft), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.PersonOutline, null, tint = Cyan, modifier = Modifier.size(34.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Text(displayName, style = MaterialTheme.typography.titleLarge)
                Text("本机身份", color = TextSecondary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
            }
        }
        HorizontalDivider(color = MeshDivider)
        ProfileRow(Icons.Outlined.Key, "身份密钥", "查看本机短 ID", onClick = onOpenKeys)
        ProfileRow(Icons.Outlined.Settings, "通用设置", "设置项", onClick = onOpenSettings)
        ProfileRow(Icons.Outlined.Security, "安全中心", "权限状态与安全能力", onClick = onOpenSecurityCenter)
        ProfileRow(Icons.Outlined.BugReport, "调试中心", "实时收发/链路数据", onClick = onOpenDebugCenter)
        ProfileRow(Icons.Outlined.Info, "关于 MeshChat", "版本、通信方式与隐私说明", onClick = onOpenAbout)
        HorizontalDivider(color = MeshDivider)
        Text("MeshChat · 离线近场安全通信", color = TextSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(24.dp))
    }
}

@Composable
private fun ProfileRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Cyan)
        Column(modifier = Modifier.weight(1f).padding(start = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextSecondary, modifier = Modifier.padding(top = 4.dp))
        }
        Icon(Icons.Outlined.ChevronRight, null, tint = TextSecondary)
    }
}
