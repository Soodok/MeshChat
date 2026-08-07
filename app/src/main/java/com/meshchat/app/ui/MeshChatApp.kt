package com.meshchat.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meshchat.app.ui.screens.MeshChatHome
import com.meshchat.app.ui.theme.Ink
import com.meshchat.app.security.model.SecurityCapability

@Composable
fun MeshChatApp(viewModel: MeshChatViewModel = viewModel(factory = MeshChatViewModelFactory())) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val peers by viewModel.peers.collectAsStateWithLifecycle()
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val pendingInvites by viewModel.pendingInvites.collectAsStateWithLifecycle()
    val invites by viewModel.invites.collectAsStateWithLifecycle()
    val currentConversation by viewModel.currentConversation.collectAsStateWithLifecycle()
    val securityCapabilities by viewModel.securityCapabilities.collectAsStateWithLifecycle()
    val localSecuritySnapshot by viewModel.localSecuritySnapshot.collectAsStateWithLifecycle()
    val debugSnapshot by viewModel.debugSnapshot.collectAsStateWithLifecycle()
    val debugSettings by viewModel.debugSettings.collectAsStateWithLifecycle()
    val debugControlState by viewModel.debugControlState.collectAsStateWithLifecycle()
    val oscHistory by viewModel.oscHistory.collectAsStateWithLifecycle()
    val debugLogLines by viewModel.debugLogLines.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    // v1.1.57 E2EE：发送被拒（对方未启用加密）→ Toast 提示
    val sendRejected by viewModel.sendRejected.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(sendRejected) {
        if (sendRejected != null) {
            android.widget.Toast.makeText(context, sendRejected, android.widget.Toast.LENGTH_SHORT).show()
            viewModel.consumeSendRejected()
        }
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.recordSecurityCapabilityResult(SecurityCapability.NOTIFICATIONS, granted)
    }
    // v1.1.58 应用锁：已设密码且回前台 → 锁定，解锁屏覆盖主 UI
    val appLocked by viewModel.appLocked.collectAsStateWithLifecycle()
    val lockout by viewModel.lockout.collectAsStateWithLifecycle()
    val lockPasswordEnabled by viewModel.lockPasswordEnabled.collectAsStateWithLifecycle()
    Surface(modifier = androidx.compose.ui.Modifier.fillMaxSize(), color = Ink) {
        if (appLocked) {
            com.meshchat.app.ui.screens.AppLockScreen(
                biometricAvailable = viewModel.lockBiometricAvailable(),
                lockout = lockout,
                onVerifyPassword = viewModel::verifyLockPassword,
                onFinishBiometricUnlock = viewModel::finishBiometricUnlockAfterAuth,
                onRemainingLockoutMs = viewModel::remainingLockoutMs,
            )
            return@Surface
        }
        MeshChatHome(
            messages = messages,
            conversations = conversations,
            peers = peers,
            sessions = sessions,
            groups = groups,
            onCreateGroup = viewModel::createGroup,
            onJoinGroup = viewModel::joinGroup,
            pendingInvites = pendingInvites,
            invites = invites,
            localShortId = viewModel.localShortId,
            localBluetoothName = viewModel.localBluetoothName,
            localBluetoothAddress = viewModel.localBluetoothAddress,
            conversationTarget = currentConversation,
            displayName = viewModel.localDisplayName,
            onDisplayNameChange = viewModel::updateDisplayName,
            backgroundEnabled = viewModel.backgroundEnabled,
            onBackgroundEnabledChange = viewModel::updateBackgroundEnabled,
            autoDiscovery = viewModel.autoDiscovery,
            onAutoDiscoveryChange = viewModel::updateAutoDiscovery,
            discoveryMode = viewModel.discoveryMode.collectAsStateWithLifecycle().value,
            onSetDiscoveryMode = viewModel::setDiscoveryMode,
            onOpenConversation = viewModel::openConversation,
            onToggleConversationArchived = viewModel::toggleConversationArchived,
            onDeleteConversation = viewModel::deleteConversation,
            onSendInvite = viewModel::sendInvite,
            onAcceptInvite = viewModel::acceptInvite,
            onRejectInvite = viewModel::rejectInvite,
            onSendMessage = viewModel::sendMessage,
            onSendFile = { name, mime, size, openSource ->
                viewModel.sendFile(openSource, name, mime, size)
            },
            onOpenFile = { message ->
                val uri = message.file?.uri?.let { android.net.Uri.parse(it) }
                if (uri != null) {
                    runCatching {
                        context.startActivity(
                            android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }
            },
            securityCapabilities = securityCapabilities,
            localSecuritySnapshot = localSecuritySnapshot,
            onRequestNotificationPermission = {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    viewModel.recordSecurityCapabilityResult(SecurityCapability.NOTIFICATIONS, granted = true)
                }
            },
            onOpenSecuritySettings = {
                context.startActivity(
                    android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(android.net.Uri.fromParts("package", context.packageName, null)),
                )
            },
            onRefreshLocalSecurity = viewModel::refreshLocalSecurity,
            onDeleteLocalSecurityHistory = viewModel::deleteLocalSecurityHistory,
            debugSnapshot = debugSnapshot,
            debugSettings = debugSettings,
            onUpdateDebugSettings = { settings -> viewModel.updateDebugSettings { settings } },
            onResetDebugStats = viewModel::resetDebugStats,
            debugControlState = debugControlState,
            onDebugControl = viewModel::sendDebugControl,
            onResetDebugControls = viewModel::resetDebugControls,
            oscHistory = oscHistory,
            debugLogLines = debugLogLines,
            onClearDebugLogs = viewModel::clearDebugLogs,
            hasLockPassword = lockPasswordEnabled,
            lockBiometricAvailable = viewModel.lockBiometricAvailable(),
            onSetLockPassword = viewModel::setLockPassword,
            onChangeLockPassword = viewModel::changeLockPassword,
            onRemoveLockPassword = viewModel::removeLockPassword,
        )
    }
}
