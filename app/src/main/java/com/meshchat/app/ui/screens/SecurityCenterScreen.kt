package com.meshchat.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.meshchat.app.security.capability.CapabilityRecovery
import com.meshchat.app.security.capability.SecurityCapabilityStatus
import com.meshchat.app.security.local.LocalSecuritySnapshot
import com.meshchat.app.security.local.LocalSecurityStorageState
import com.meshchat.app.security.model.SecurityCapability
import com.meshchat.app.security.model.SecurityCapabilityState
import com.meshchat.app.security.model.SecurityEvent
import com.meshchat.app.security.model.SecurityLevel
import com.meshchat.app.security.presentation.SecurityCenterPresenter
import com.meshchat.app.ui.theme.Cyan
import com.meshchat.app.ui.theme.Divider
import com.meshchat.app.ui.theme.Ink
import com.meshchat.app.ui.theme.InkRaised
import com.meshchat.app.ui.theme.MeshAmber
import com.meshchat.app.ui.theme.MeshGreen
import com.meshchat.app.ui.theme.TextSecondary

@Composable
fun SecurityCenterScreen(
    statuses: Map<SecurityCapability, SecurityCapabilityStatus>,
    localSnapshot: LocalSecuritySnapshot?,
    onRequestNotificationPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onRefreshLocalSecurity: () -> Unit,
    onDeleteLocalHistory: () -> Unit,
    onBack: () -> Unit,
    /** v1.1.59 应用锁（安全中心密码设置入口）。 */
    hasLockPassword: Boolean,
    lockBiometricAvailable: Boolean,
    /** v1.1.83 指纹版 DEK 副本是否真实存在（区别于"设备支持"）。 */
    lockFingerprintEnabled: Boolean,
    onSetLockPassword: (String) -> Unit,
    onChangeLockPassword: (old: String, new: String) -> Boolean,
    onRemoveLockPassword: () -> Unit,
    /** v1.1.83 设置密码后指纹副本缺失（keystore2 加密需认证）→ 弹认证启用指纹补写。 */
    onBiometricBlobMissing: () -> Boolean,
    onPrepareBiometricEnrollSession: () -> com.meshchat.app.security.lock.BiometricEnrollSession?,
    onFinishBiometricEnroll: (com.meshchat.app.security.lock.BiometricEnrollSession?, javax.crypto.Cipher?) -> Boolean,
) {
    LaunchedEffect(Unit) { onRefreshLocalSecurity() }
    val summary = SecurityCenterPresenter.summary(localSnapshot?.assessment)
    // v1.1.90 完善：只展示真实生效的能力（蓝牙/通知/完整性）；VPN 扫描与企业设备管理为本应用从未配置的
    // 框架脚手架，永远显示"未配置"对用户是噪音 → 过滤不展示。
    val orderedStatuses = SecurityCapability.entries
        .filter { it != SecurityCapability.VPN_SCAN && it != SecurityCapability.ENTERPRISE_MANAGEMENT }
        .mapNotNull { statuses[it] }
    var lockDialog by remember { mutableStateOf<LockDialog?>(null) }
    // v1.1.83 设置密码成功后指纹副本缺失 → 自动弹"启用指纹"认证
    var pendingEnableFingerprint by remember { mutableStateOf(false) }
    fun setLockPasswordAndMaybeEnroll(pw: String) {
        onSetLockPassword(pw)
        if (lockBiometricAvailable && onBiometricBlobMissing()) {
            pendingEnableFingerprint = true
        }
    }

    fun changeLockPasswordAndMaybeEnroll(old: String, new: String): Boolean {
        val ok = onChangeLockPassword(old, new)
        if (ok && lockBiometricAvailable && onBiometricBlobMissing()) {
            pendingEnableFingerprint = true
        }
        return ok
    }

    Column(modifier = Modifier.fillMaxSize().background(Ink)) {
        SecurityCenterHeader(onBack)
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp)) {
            item {
                Surface(color = InkRaised, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(summary.title, style = MaterialTheme.typography.titleLarge, color = summaryColor(summary.level))
                        Text(summary.detail, style = MaterialTheme.typography.bodyMedium, color = TextSecondary, modifier = Modifier.padding(top = 8.dp))
                        Text(
                            "离线优先：不上传检查结果、不读取消息、不申请 VPN。",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                        Row(modifier = Modifier.padding(top = 14.dp)) {
                            Button(
                                onClick = onRefreshLocalSecurity,
                                colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Ink),
                            ) { Text("重新本地检查") }
                            Spacer(Modifier.width(10.dp))
                            Button(
                                onClick = onDeleteLocalHistory,
                                colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = TextSecondary),
                            ) { Text("清除本地记录") }
                        }
                    }
                }
                if (localSnapshot?.storageState == LocalSecurityStorageState.UNAVAILABLE) {
                    Text(
                        "本地加密记录暂不可用；当前结果未被静默写入或删除。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MeshAmber,
                        modifier = Modifier.padding(top = 14.dp),
                    )
                }
                // v1.1.59 应用锁（密码设置入口）
                Text("应用锁", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 28.dp, bottom = 10.dp))
                Surface(color = InkRaised, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Lock, null, tint = Cyan, modifier = Modifier.size(20.dp))
                            Text("应用锁", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 10.dp))
                            Spacer(Modifier.weight(1f))
                            Box(
                                Modifier.size(8.dp).background(if (hasLockPassword) MeshGreen else TextSecondary, androidx.compose.foundation.shape.CircleShape),
                            )
                            Text(
                                if (hasLockPassword) "已启用" else "未设置",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (hasLockPassword) MeshGreen else TextSecondary,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                        Text(
                            // v1.1.90 应用锁文案简化：不再区分指纹启用状态（配合解锁界面隐藏指纹提示的新交互）
                            if (hasLockPassword) "已启用：每次进入应用需解锁，会话/群密钥以最高强度加密存储；回前台自动锁定，锁定期间通知不显示内容。"
                            else "设置密码后：每次进入应用需解锁，会话/群密钥以最高强度加密存储，锁定期间通知不显示内容。",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        if (hasLockPassword) {
                            Row(modifier = Modifier.padding(top = 14.dp)) {
                                Button(
                                    onClick = { lockDialog = LockDialog.CHANGE },
                                    colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Ink),
                                ) { Text("修改密码") }
                                Spacer(Modifier.width(10.dp))
                                OutlinedButton(
                                    onClick = { lockDialog = LockDialog.REMOVE },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MeshAmber),
                                ) { Text("移除密码") }
                            }
                        } else {
                            Button(
                                onClick = { lockDialog = LockDialog.SET },
                                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Ink),
                            ) { Text("设置密码") }
                        }
                    }
                }
                Text("本地检查记录", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 28.dp, bottom = 10.dp))
                if (localSnapshot?.events.isNullOrEmpty()) {
                    Text("暂无可保存的本地风险记录。", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                }
            }
            items(localSnapshot?.events.orEmpty(), key = { it.id }) { event -> LocalSecurityEventCard(event) }
            item {
                Text("安全能力", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 28.dp, bottom = 10.dp))
            }
            items(orderedStatuses, key = { it.capability.name }) { status ->
                CapabilityCard(status, onRequestNotificationPermission, onOpenAppSettings)
            }
            item {
                Text(
                    "本页不扫描其他应用内容，不定位他人 IP，也不会静默启用 VPN。",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 18.dp),
                )
            }
        }
    }

    when (lockDialog) {
        LockDialog.SET -> LockPasswordDialog(
            title = "设置应用锁密码",
            confirmText = "设置",
            onChangeLockPassword = null,
            // v1.1.83 设置成功后指纹副本缺失 → 自动弹"启用指纹"认证
            onSetLockPassword = ::setLockPasswordAndMaybeEnroll,
            onDismiss = { lockDialog = null },
        )
        LockDialog.CHANGE -> LockPasswordDialog(
            title = "修改应用锁密码",
            confirmText = "修改",
            onChangeLockPassword = ::changeLockPasswordAndMaybeEnroll,
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

    // v1.1.83 设置/修改密码成功但指纹副本缺失（keystore2 加密需认证）→ 自动弹"启用指纹"认证补写
    if (pendingEnableFingerprint) {
        EnableFingerprintPrompt(
            onPrepareSession = onPrepareBiometricEnrollSession,
            onFinishEnroll = onFinishBiometricEnroll,
            onDone = { pendingEnableFingerprint = false },
        )
    }
}

@Composable
private fun SecurityCenterHeader(onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 42.dp, start = 12.dp, end = 24.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
        Icon(Icons.Outlined.Security, null, tint = Cyan, modifier = Modifier.size(23.dp))
        Text("安全中心", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 12.dp))
    }
    HorizontalDivider(color = Divider)
}

@Composable
private fun LocalSecurityEventCard(event: SecurityEvent) {
    Surface(color = InkRaised, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(localEventTitle(event.ruleId), style = MaterialTheme.typography.titleMedium)
            Text(localEventDetail(event.ruleId), style = MaterialTheme.typography.bodySmall, color = TextSecondary, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

private fun localEventTitle(ruleId: String): String = when (ruleId) {
    "end-to-end-encryption-unavailable" -> "端到端加密尚未启用"
    "application-message-storage-encryption-unavailable" -> "聊天记录未启用应用层加密"
    "device-screen-lock-disabled" -> "设备未设置安全锁屏"
    "debugger-attached" -> "检测到调试器已附加"
    "debuggable-build-enabled" -> "应用调试构建已启用"
    "accessibility-service-enabled" -> "检测到已启用的无障碍服务"
    else -> "本地安全信号"
}

private fun localEventDetail(ruleId: String): String = when (ruleId) {
    "end-to-end-encryption-unavailable" -> "当前 Mesh 帧仍是明文协议。不要在此版本发送敏感内容；不会用伪加密掩盖此状态。"
    "application-message-storage-encryption-unavailable" -> "当前 Room 聊天库未接入经审查的加密数据库。系统文件加密不能替代应用层的密钥保护。"
    "device-screen-lock-disabled" -> "请在系统设置中启用 PIN、图案或密码锁屏，以降低设备被直接访问时的暴露。"
    "debugger-attached" -> "调试器可观察当前应用进程；请仅在你信任的开发环境中继续操作。"
    "debuggable-build-enabled" -> "调试构建不应作为正式发布包使用。此结论不依赖联网。"
    "accessibility-service-enabled" -> "无障碍服务通常是正常功能；本应用不读取服务名称，也不据此认定设备被控制。"
    else -> "仅保存脱敏规则标签；不包含消息、IP、应用名或网络内容。"
}

@Composable
private fun CapabilityCard(
    status: SecurityCapabilityStatus,
    onRequestNotificationPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    Surface(color = InkRaised, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(capabilityTitle(status.capability), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(stateLabel(status.state), style = MaterialTheme.typography.labelMedium, color = stateColor(status.state))
            }
            Text(capabilityDetail(status), style = MaterialTheme.typography.bodySmall, color = TextSecondary, modifier = Modifier.padding(top = 6.dp))
            when {
                status.capability == SecurityCapability.NOTIFICATIONS && status.canRequest -> {
                    Button(onClick = onRequestNotificationPermission, colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Ink), modifier = Modifier.padding(top = 14.dp)) { Text("启用通知") }
                }
                status.recovery == CapabilityRecovery.OPEN_SYSTEM_SETTINGS || (status.capability == SecurityCapability.BLUETOOTH && status.canRequest) -> {
                    Button(onClick = onOpenAppSettings, colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Ink), modifier = Modifier.padding(top = 14.dp)) { Text("打开系统设置") }
                }
            }
        }
    }
}

private fun capabilityTitle(capability: SecurityCapability): String = when (capability) {
    SecurityCapability.BLUETOOTH -> "附近设备"
    SecurityCapability.NOTIFICATIONS -> "消息提醒"
    SecurityCapability.INTEGRITY_CHECK -> "应用完整性"
    SecurityCapability.VPN_SCAN -> "授权网络扫描"
    SecurityCapability.ENTERPRISE_MANAGEMENT -> "企业设备管理"
}

private fun capabilityDetail(status: SecurityCapabilityStatus): String = when (status.capability) {
    SecurityCapability.BLUETOOTH -> "用于发现和连接附近节点。拒绝后仍可查看聊天和设置。"
    SecurityCapability.NOTIFICATIONS -> "用于新消息提醒；拒绝后不会影响聊天收发。"
    SecurityCapability.INTEGRITY_CHECK -> "本地检查不依赖 Play Integrity。该云端增强尚未配置，因此不会产生设备控制或恶意软件结论。"
    SecurityCapability.VPN_SCAN -> "未配置透明转发引擎；当前不会申请 VPN 授权或拦截网络。"
    SecurityCapability.ENTERPRISE_MANAGEMENT -> "个人版不读取企业设备管理记录。"
}

private fun stateLabel(state: SecurityCapabilityState): String = when (state) {
    SecurityCapabilityState.GRANTED -> "已启用"
    SecurityCapabilityState.AVAILABLE -> "可选"
    SecurityCapabilityState.DENIED -> "已关闭"
    SecurityCapabilityState.UNSUPPORTED -> "不可用"
    SecurityCapabilityState.NOT_CONFIGURED -> "未配置"
    SecurityCapabilityState.CONFLICTED -> "需处理"
}

private fun stateColor(state: SecurityCapabilityState): Color = when (state) {
    SecurityCapabilityState.GRANTED -> MeshGreen
    SecurityCapabilityState.AVAILABLE -> Cyan
    SecurityCapabilityState.DENIED, SecurityCapabilityState.CONFLICTED -> MeshAmber
    SecurityCapabilityState.UNSUPPORTED, SecurityCapabilityState.NOT_CONFIGURED -> TextSecondary
}

private fun summaryColor(level: SecurityLevel): Color = when (level) {
    SecurityLevel.NORMAL -> MeshGreen
    SecurityLevel.LIMITED, SecurityLevel.SUSPICIOUS, SecurityLevel.HIGH_RISK -> MeshAmber
}
