package com.meshchat.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.meshchat.app.ui.theme.Cyan
import com.meshchat.app.ui.theme.Divider
import com.meshchat.app.ui.theme.Ink
import com.meshchat.app.ui.theme.InkSoft
import com.meshchat.app.ui.theme.MeshGreen
import com.meshchat.app.ui.theme.TextPrimary
import com.meshchat.app.ui.theme.TextSecondary

@Composable
fun IdentityKeyScreen(onBack: () -> Unit) {
    var copied by rememberSaveable { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxSize().background(Ink)) {
        DetailHeader(title = "身份密钥", icon = Icons.Outlined.Key, onBack = onBack)
        Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp)) {
            Text("本地演示身份", style = MaterialTheme.typography.titleMedium)
            Text(
                "以下内容仅用于前端展示，不会生成、备份或上传真实密钥。",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                modifier = Modifier.padding(top = 8.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 28.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(48.dp).clip(androidx.compose.foundation.shape.CircleShape).background(InkSoft), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Lock, null, tint = Cyan)
                }
                Column(modifier = Modifier.padding(start = 14.dp)) {
                    Text("身份已验证", style = MaterialTheme.typography.titleMedium)
                    Text("演示状态", style = MaterialTheme.typography.bodySmall, color = MeshGreen)
                }
            }
            Text("公钥指纹", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 34.dp, bottom = 10.dp))
            Text(
                "A5:F2:8C:01:77:4B:9E:30",
                style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium),
                color = TextPrimary,
            )
            Button(
                onClick = { copied = true },
                modifier = Modifier.fillMaxWidth().padding(top = 30.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Ink),
            ) {
                Icon(Icons.Outlined.ContentCopy, null)
                Text("模拟复制指纹", modifier = Modifier.padding(start = 8.dp))
            }
            AnimatedContent(targetState = copied, label = "copy confirmation") { didCopy ->
                Text(
                    text = if (didCopy) "已触发前端复制反馈" else "",
                    color = MeshGreen,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 14.dp),
                )
            }
        }
    }
}

@Composable
fun GeneralSettingsScreen(onBack: () -> Unit) {
    var sampleOne by rememberSaveable { mutableStateOf(true) }
    var sampleTwo by rememberSaveable { mutableStateOf(false) }
    var sampleThree by rememberSaveable { mutableStateOf(true) }

    Column(modifier = Modifier.fillMaxSize().background(Ink)) {
        DetailHeader(title = "通用设置", icon = Icons.Outlined.Settings, onBack = onBack)
        Text(
            "以下为前端演示开关，状态仅保存在当前页面。",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
        )
        SettingsSwitchRow("示例 1", sampleOne) { sampleOne = it }
        SettingsSwitchRow("示例 2", sampleTwo) { sampleTwo = it }
        SettingsSwitchRow("示例 3", sampleThree) { sampleThree = it }
    }
}

@Composable
private fun DetailHeader(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 42.dp, start = 12.dp, end = 24.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
        Icon(icon, null, tint = Cyan, modifier = Modifier.size(23.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 12.dp))
    }
    HorizontalDivider(color = Divider)
}

@Composable
private fun SettingsSwitchRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val stateColor = if (checked) MeshGreen else TextSecondary
    val scale by animateFloatAsState(
        targetValue = if (checked) 1.06f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "$title switch scale",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 24.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            AnimatedContent(targetState = checked, label = "$title state") { enabled ->
                Text(
                    text = if (enabled) "已开启" else "已关闭",
                    color = stateColor,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 5.dp),
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.graphicsLayer(scaleX = scale, scaleY = scale),
            colors = SwitchDefaults.colors(
                checkedThumbColor = Ink,
                checkedTrackColor = MeshGreen,
                uncheckedThumbColor = TextSecondary,
                uncheckedTrackColor = InkSoft,
            ),
        )
    }
    HorizontalDivider(color = Divider, modifier = Modifier.padding(start = 24.dp))
}
