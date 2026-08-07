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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.meshchat.app.BuildConfig
import com.meshchat.app.security.local.AndroidLocalSecuritySignalCollector
import com.meshchat.app.ui.theme.Cyan
import com.meshchat.app.ui.theme.Divider
import com.meshchat.app.ui.theme.Ink
import com.meshchat.app.ui.theme.InkSoft
import com.meshchat.app.ui.theme.MeshAmber
import com.meshchat.app.ui.theme.MeshGreen
import com.meshchat.app.ui.theme.TextPrimary
import com.meshchat.app.ui.theme.TextSecondary

@Composable
fun IdentityKeyScreen(
    shortId: String,
    bluetoothName: String,
    bluetoothAddress: String,
    onBack: () -> Unit,
) {
    var copied by rememberSaveable { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxSize().background(Ink)) {
        DetailHeader(title = "身份", icon = Icons.Outlined.Key, onBack = onBack)
        Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp)) {
            Text("本机身份", style = MaterialTheme.typography.titleMedium)
            Text(
                "短 ID 即本机在 Mesh 网络中的寻址标识，对端通过它向本机投递消息。",
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
                    Text("本机短 ID", style = MaterialTheme.typography.titleMedium)
                    Text("广播通告与寻址标识", style = MaterialTheme.typography.bodySmall, color = MeshGreen)
                }
            }
            Text("短 ID", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 34.dp, bottom = 10.dp))
            Text(
                shortId,
                style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium),
                color = TextPrimary,
            )
            Button(
                onClick = { copied = true },
                modifier = Modifier.fillMaxWidth().padding(top = 30.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Ink),
            ) {
                Icon(Icons.Outlined.ContentCopy, null)
                Text("复制短 ID", modifier = Modifier.padding(start = 8.dp))
            }
            AnimatedContent(targetState = copied, label = "copy confirmation") { didCopy ->
                Text(
                    text = if (didCopy) "已复制" else "",
                    color = MeshGreen,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 14.dp),
                )
            }
            HorizontalDivider(color = Divider, modifier = Modifier.padding(top = 24.dp))
            Text("本机蓝牙信息", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp, bottom = 10.dp))
            Text(
                "蓝牙名称：$bluetoothName",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            Text(
                "MAC 地址：$bluetoothAddress",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
        }
    }
}

/** 应用锁密码对话框模式。 */
private enum class LockDialog { SET, CHANGE, REMOVE }

@Composable
fun GeneralSettingsScreen(
    displayName: String,
    onDisplayNameChange: (String) -> Unit,
    canEditDisplayName: Boolean,
    backgroundEnabled: Boolean,
    onBackgroundEnabledChange: (Boolean) -> Unit,
    /** v1.1.49：打开应用时自动搜索（默认开）。 */
    autoDiscovery: Boolean,
    onAutoDiscoveryChange: (Boolean) -> Unit,
    /** v1.1.58 应用锁（密码/指纹/DEK 加密密钥库）。 */
    hasLockPassword: Boolean,
    lockBiometricAvailable: Boolean,
    onSetLockPassword: (String) -> Unit,
    onChangeLockPassword: (old: String, new: String) -> Boolean,
    onRemoveLockPassword: () -> Unit,
    onBack: () -> Unit,
) {
    var lockDialog by remember { mutableStateOf<LockDialog?>(null) }
    var lockError by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize().background(Ink)) {
        DetailHeader(title = "通用设置", icon = Icons.Outlined.Settings, onBack = onBack)
        Text("节点昵称", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp))
        OutlinedTextField(
            value = displayName,
            onValueChange = onDisplayNameChange,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            enabled = canEditDisplayName,
            singleLine = true,
            label = { Text("昵称（广播给邻近节点）") },
        )
        Text(
            if (canEditDisplayName) "昵称会随心跳广播，邻近节点将以此标识你。" else "连接至少一台其他设备后，才可以修改昵称。",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        HorizontalDivider(color = Divider, modifier = Modifier.padding(top = 14.dp))
        SettingsSwitchRow(
            title = "后台常驻",
            checked = backgroundEnabled,
            onCheckedChange = onBackgroundEnabledChange,
        )
        Text(
            "开启后息屏/退后台仍持续收发消息并弹通知。",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        SettingsSwitchRow(
            title = "打开应用时自动搜索",
            checked = autoDiscovery,
            onCheckedChange = onAutoDiscoveryChange,
        )
        Text(
            "开启后每次进入应用自动开始蓝牙广播+扫描；关闭则需在 Mesh 页手动开启搜索。",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )

        // ---- v1.1.58 应用锁 ----
        HorizontalDivider(color = Divider, modifier = Modifier.padding(top = 14.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Lock, null, tint = Cyan, modifier = Modifier.size(20.dp))
            Text("应用锁", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 10.dp))
            Spacer(Modifier.weight(1f))
            if (hasLockPassword) {
                Box(
                    Modifier.size(8.dp).background(MeshGreen, androidx.compose.foundation.shape.CircleShape),
                )
                Text(
                    if (lockBiometricAvailable) "已启用 · 密码+指纹" else "已启用",
                    style = MaterialTheme.typography.bodySmall,
                    color = MeshGreen,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        Text(
            "设密码后，会话/群密钥以最高强度加密存储；每次进入应用需密码或指纹解锁；连续 5 次密码错误将锁定 30 秒；锁定期间通知不显示内容。",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp),
        )
        if (hasLockPassword) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp)) {
                Button(
                    onClick = {
                        lockError = null
                        lockDialog = LockDialog.CHANGE
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Ink),
                ) {
                    Text("修改密码")
                }
                Spacer(Modifier.width(12.dp))
                OutlinedButton(
                    onClick = {
                        lockError = null
                        lockDialog = LockDialog.REMOVE
                    },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MeshAmber),
                ) {
                    Text("移除密码")
                }
            }
        } else {
            Button(
                onClick = {
                    lockError = null
                    lockDialog = LockDialog.SET
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Ink),
            ) {
                Icon(Icons.Outlined.Lock, null)
                Text("设置密码", modifier = Modifier.padding(start = 8.dp))
            }
        }
        LockAutomationWarning()
    }

    when (lockDialog) {
        LockDialog.SET -> LockPasswordDialog(
            title = "设置应用锁密码",
            confirmText = "设置",
            onChangeLockPassword = null,
            onSetLockPassword = onSetLockPassword,
            onDismiss = { lockDialog = null },
        )
        LockDialog.CHANGE -> LockPasswordDialog(
            title = "修改应用锁密码",
            confirmText = "修改",
            onChangeLockPassword = onChangeLockPassword,
            onSetLockPassword = null,
            onDismiss = { lockDialog = null },
        )
        LockDialog.REMOVE -> AlertDialog(
            onDismissRequest = { lockDialog = null },
            title = { Text("移除应用锁密码？") },
            text = { Text("移除后不再要求解锁，已加密的会话/群密钥将回退为明文存储，且无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    onRemoveLockPassword()
                    lockDialog = null
                }) { Text("移除", color = MeshAmber) }
            },
            dismissButton = {
                TextButton(onClick = { lockDialog = null }) { Text("取消") }
            },
        )
        null -> Unit
    }
}

/** 防自动化警告（v1.1.58）：无障碍服务/调试器/可调试构建检测——本地只读信号，无网络。 */
@Composable
private fun LockAutomationWarning() {
    val context = LocalContext.current
    val warnings = remember {
        runCatching {
            AndroidLocalSecuritySignalCollector(context).collect(System.currentTimeMillis())
        }.getOrDefault(emptyList()).mapNotNull { s ->
            when (s.ruleId) {
                "debugger-attached" -> "检测到调试器连接——自动化/破解工具可能已附着"
                "accessibility-service-enabled" -> "检测到无障碍服务开启——自动化工具可借此模拟操作"
                "debuggable-build-enabled" -> "当前为可调试构建——调试通道已打开"
                else -> null
            }
        }
    }
    if (warnings.isNotEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 4.dp)
                .background(InkSoft, androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                .padding(12.dp),
        ) {
            Text("安全警告", style = MaterialTheme.typography.titleSmall, color = MeshAmber, fontWeight = FontWeight.Bold)
            warnings.forEach { w ->
                Text("· $w", style = MaterialTheme.typography.bodySmall, color = MeshAmber, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

/** 设置/修改密码对话框：SET 模式两个输入（新+确认）；CHANGE 模式三个（旧+新+确认）。 */
@Composable
private fun LockPasswordDialog(
    title: String,
    confirmText: String,
    onChangeLockPassword: ((old: String, new: String) -> Boolean)?,
    onSetLockPassword: ((String) -> Unit)?,
    onDismiss: () -> Unit,
) {
    var oldPassword by rememberSaveable { mutableStateOf("") }
    var newPassword by rememberSaveable { mutableStateOf("") }
    var confirmPassword by rememberSaveable { mutableStateOf("") }
    var dialogError by rememberSaveable { mutableStateOf<String?>(null) }

    fun submit() {
        when {
            newPassword.length < 4 -> dialogError = "密码至少 4 位"
            newPassword != confirmPassword -> dialogError = "两次输入的密码不一致"
            onChangeLockPassword != null && !onChangeLockPassword(oldPassword, newPassword) -> dialogError = "旧密码错误"
            else -> {
                onSetLockPassword?.invoke(newPassword)
                onDismiss()
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (onChangeLockPassword != null) {
                    OutlinedTextField(
                        value = oldPassword,
                        onValueChange = { oldPassword = it.take(64); dialogError = null },
                        singleLine = true,
                        label = { Text("旧密码") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                    Spacer(Modifier.height(10.dp))
                }
                OutlinedTextField(
                    value = newPassword,
                    onValueChange = { newPassword = it.take(64); dialogError = null },
                    singleLine = true,
                    label = { Text(if (onChangeLockPassword != null) "新密码" else "密码") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = confirmPassword,
                    onValueChange = { confirmPassword = it.take(64); dialogError = null },
                    singleLine = true,
                    label = { Text("确认密码") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                if (dialogError != null) {
                    Text(dialogError.orEmpty(), color = MeshAmber, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { submit() }, enabled = newPassword.isNotBlank() && confirmPassword.isNotBlank()) {
                Text(confirmText, color = Cyan)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
fun AboutScreen(onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().background(Ink)) {
        DetailHeader(title = "关于 MeshChat", icon = Icons.Outlined.Info, onBack = onBack)
        Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 22.dp)) {
            Text("MeshChat", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("离线近场安全通信", color = MeshGreen, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 6.dp))
            Text(
                "MeshChat 通过蓝牙发现附近设备，并以 Mesh 方式在没有互联网的场景中传递消息和文件。",
                color = TextSecondary,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 28.dp),
            )
            HorizontalDivider(color = Divider, modifier = Modifier.padding(vertical = 24.dp))
            Text("隐私与数据", style = MaterialTheme.typography.titleMedium)
            Text(
                "消息记录保存在本机。通信仅在附近设备间进行；请在共享设备上谨慎保留本地对话。",
                color = TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text("版本", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp))
            Text("v${BuildConfig.VERSION_NAME}", color = TextSecondary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
            Text("开源仓库", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp))
            val uriHandler = LocalUriHandler.current
            Text(
                "github.com/Soodok/MeshChat",
                color = Cyan,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .clickable { uriHandler.openUri("https://github.com/Soodok/MeshChat") },
            )
            Text("免责声明", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp))
            Text(
                "开源项目可能被用于恶意用途。本项目仅用于学习与合法的应急通信研究，作者对任何滥用或误用不承担责任；使用者须确保其使用方式符合所在地区法律法规。",
                color = MeshAmber,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
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
