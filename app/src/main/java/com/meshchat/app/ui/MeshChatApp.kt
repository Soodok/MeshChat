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
    val pendingInvites by viewModel.pendingInvites.collectAsStateWithLifecycle()
    val invites by viewModel.invites.collectAsStateWithLifecycle()
    val currentConversation by viewModel.currentConversation.collectAsStateWithLifecycle()
    val securityCapabilities by viewModel.securityCapabilities.collectAsStateWithLifecycle()
    val localSecuritySnapshot by viewModel.localSecuritySnapshot.collectAsStateWithLifecycle()
    val debugSnapshot by viewModel.debugSnapshot.collectAsStateWithLifecycle()
    val debugSettings by viewModel.debugSettings.collectAsStateWithLifecycle()
    val debugControlState by viewModel.debugControlState.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.recordSecurityCapabilityResult(SecurityCapability.NOTIFICATIONS, granted)
    }
    Surface(modifier = androidx.compose.ui.Modifier.fillMaxSize(), color = Ink) {
        MeshChatHome(
            messages = messages,
            conversations = conversations,
            peers = peers,
            sessions = sessions,
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
            onOpenConversation = viewModel::openConversation,
            onToggleConversationArchived = viewModel::toggleConversationArchived,
            onDeleteConversation = viewModel::deleteConversation,
            onStartDiscovery = viewModel::startDiscovery,
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
        )
    }
}
