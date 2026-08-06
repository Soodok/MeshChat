package com.meshchat.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.content.Intent
import android.provider.OpenableColumns
import android.widget.Toast
import com.meshchat.app.data.ChatMessage
import com.meshchat.app.data.ChatPreview
import com.meshchat.app.data.MainDestination
import com.meshchat.app.data.MeshPeer
import com.meshchat.app.security.capability.SecurityCapabilityStatus
import com.meshchat.app.security.local.LocalSecuritySnapshot
import com.meshchat.app.security.model.SecurityCapability
import com.meshchat.app.ui.MeshChatViewModel
import com.meshchat.app.ui.components.SecurityNote
import com.meshchat.app.ui.theme.Cyan
import com.meshchat.app.ui.theme.Ink
import com.meshchat.app.ui.theme.InkRaised
import com.meshchat.app.ui.theme.MeshGreen
import com.meshchat.app.ui.theme.TextSecondary

@Composable
fun MeshChatHome(
    messages: List<ChatMessage>,
    conversations: List<ChatPreview>,
    peers: List<MeshPeer>,
    sessions: Set<String>,
    /** v1.1.50 群列表（已订阅群）。 */
    groups: List<com.meshchat.app.mesh.service.GroupInfo>,
    onCreateGroup: (String) -> Unit,
    onJoinGroup: (String) -> Unit,
    pendingInvites: Set<String>,
    invites: Map<String, Long>,
    localShortId: String,
    localBluetoothName: String,
    localBluetoothAddress: String,
    conversationTarget: String?,
    displayName: String,
    onDisplayNameChange: (String) -> Unit,
    backgroundEnabled: Boolean,
    onBackgroundEnabledChange: (Boolean) -> Unit,
    /** v1.1.49：打开应用时自动搜索设置。 */
    autoDiscovery: Boolean,
    onAutoDiscoveryChange: (Boolean) -> Unit,
    /** v1.1.49：发现开关（是否在广播+扫描）。 */
    discoveryEnabled: Boolean,
    onToggleDiscovery: () -> Unit,
    onOpenConversation: (String?) -> Unit,
    onToggleConversationArchived: (String) -> Unit,
    onDeleteConversation: (String) -> Unit,
    onStartDiscovery: () -> Unit,
    onSendInvite: (String) -> Unit,
    onAcceptInvite: (String) -> Unit,
    onRejectInvite: (String) -> Unit,
    onSendMessage: (String) -> Unit,
    onSendFile: (name: String, mime: String, size: Long, openSource: () -> java.io.InputStream) -> Unit,
    onOpenFile: (ChatMessage) -> Unit = {},
    securityCapabilities: Map<SecurityCapability, SecurityCapabilityStatus>,
    localSecuritySnapshot: LocalSecuritySnapshot?,
    onRequestNotificationPermission: () -> Unit,
    onOpenSecuritySettings: () -> Unit,
    onRefreshLocalSecurity: () -> Unit,
    onDeleteLocalSecurityHistory: () -> Unit,
    debugSnapshot: com.meshchat.app.mesh.debug.DebugSnapshot,
    debugSettings: MeshChatViewModel.DebugSettings,
    onUpdateDebugSettings: (MeshChatViewModel.DebugSettings) -> Unit,
    onResetDebugStats: () -> Unit,
    debugControlState: MeshChatViewModel.DebugControlState,
    onDebugControl: (com.meshchat.app.mesh.debug.DebugControl) -> Unit,
    onResetDebugControls: () -> Unit,
    oscHistory: List<MeshChatViewModel.OscPoint>,
    debugLogLines: List<String>,
    onClearDebugLogs: () -> Unit,
) {
    var destinationName by rememberSaveable { mutableStateOf(MainDestination.CHATS.name) }
    var profileDetail by rememberSaveable { mutableStateOf<String?>(null) }
    val destination = MainDestination.valueOf(destinationName)
    val context = LocalContext.current

    // 系统文件选择器：选文件后取名称/MIME/大小，回调上层发送
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val resolver = context.contentResolver
            val name = resolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) c.getString(idx) else "file"
                } else "file"
            } ?: "file"
            val mime = resolver.getType(uri) ?: "application/octet-stream"
            // v1.1.48：部分 provider（云盘/压缩成员/管道描述符）openAssetFileDescriptor.length 返回 0/-1，
            // 真文件被误判"空文件"拒发（用户"选完文件显示 0B 发不了"）。三级解析：
            // ① OpenableColumns.SIZE 列（DocumentsUI/下载/本地文件必带）② AFD length ③ 读流数字节（最后兜底）
            var size = resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.SIZE)
                    if (idx >= 0 && !c.isNull(idx)) c.getLong(idx) else -1L
                } else -1L
            } ?: -1L
            if (size <= 0) {
                size = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            }
            if (size <= 0) {
                // 管道/未知长度描述符：读流计真实字节（仅极少数 provider 走到；大文件走此兜底较慢但可发）
                size = resolver.openInputStream(uri)?.use { input ->
                    val buf = ByteArray(8192)
                    var count = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        count += n
                    }
                    count
                } ?: -1L
            }
            if (size <= 0L) {
                Toast.makeText(context, "无法获取文件大小，暂不支持发送", Toast.LENGTH_SHORT).show()
            } else {
                onSendFile(name, mime, size) { resolver.openInputStream(uri)!! }
            }
        }
    }

    // 收到的对话请求弹窗
    var pendingInvite by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(invites) {
        pendingInvite = invites.keys.firstOrNull()
    }
    if (pendingInvite != null) {
        AlertDialog(
            onDismissRequest = { onRejectInvite(pendingInvite!!); pendingInvite = null },
            title = { Text("对话请求") },
            text = { Text("节点 ${pendingInvite} 请求与你建立对话，是否接受？") },
            confirmButton = {
                TextButton(onClick = { onAcceptInvite(pendingInvite!!); pendingInvite = null }) { Text("接受") }
            },
            dismissButton = {
                TextButton(onClick = { onRejectInvite(pendingInvite!!); pendingInvite = null }) { Text("拒绝") }
            },
            containerColor = InkRaised,
        )
    }

    BackHandler(enabled = conversationTarget != null || profileDetail != null) {
        if (conversationTarget != null) onOpenConversation(null) else profileDetail = null
    }

    if (conversationTarget != null) {
        val target = conversationTarget!!
        // v1.1.50：群会话（groupId 在已订阅群中）——标题用群名，状态行/附件按钮按群语义
        val isGroupConv = groups.any { it.id == target }
        val title = when {
            target == "ME" -> "我"
            isGroupConv -> groups.firstOrNull { it.id == target }?.name ?: target
            else -> peers.firstOrNull { it.shortId == target }?.name ?: target
        }
        val connected = target == "ME" || target in sessions
        val peerPresence = if (target == "ME") com.meshchat.app.mesh.transport.PeerPresence.ONLINE
            else peers.firstOrNull { it.shortId == target }?.presence
        val relayVia = if (target == "ME") "" else peers.firstOrNull { it.shortId == target }?.relayVia ?: ""
        ConversationScreen(
            messages = messages,
            title = title,
            connected = connected,
            peerPresence = peerPresence,
            relayVia = relayVia,
            onBack = { onOpenConversation(null) },
            onSendMessage = onSendMessage,
            onPickFile = if (isGroupConv) null else ({ filePicker.launch(arrayOf("*/*")) }),
            onOpenFile = onOpenFile,
            isGroup = isGroupConv,
        )
        return
    }

    if (profileDetail != null) {
        when (profileDetail) {
            "keys" -> IdentityKeyScreen(
                shortId = localShortId,
                bluetoothName = localBluetoothName,
                bluetoothAddress = localBluetoothAddress,
                onBack = { profileDetail = null },
            )
            "settings" -> GeneralSettingsScreen(
                displayName = displayName,
                onDisplayNameChange = onDisplayNameChange,
                canEditDisplayName = sessions.isNotEmpty(),
                backgroundEnabled = backgroundEnabled,
                onBackgroundEnabledChange = onBackgroundEnabledChange,
                autoDiscovery = autoDiscovery,
                onAutoDiscoveryChange = onAutoDiscoveryChange,
                onBack = { profileDetail = null },
            )
            "about" -> AboutScreen(onBack = { profileDetail = null })
            "debug" -> DebugCenterScreen(
                snapshot = debugSnapshot,
                settings = debugSettings,
                onSettingsChange = onUpdateDebugSettings,
                onReset = onResetDebugStats,
                onBack = { profileDetail = null },
                controlState = debugControlState,
                onControl = onDebugControl,
                onResetControls = onResetDebugControls,
                oscHistory = oscHistory,
                debugLogLines = debugLogLines,
                onClearLogs = onClearDebugLogs,
            )
            "security" -> SecurityCenterScreen(
                statuses = securityCapabilities,
                localSnapshot = localSecuritySnapshot,
                onRequestNotificationPermission = onRequestNotificationPermission,
                onOpenAppSettings = onOpenSecuritySettings,
                onRefreshLocalSecurity = onRefreshLocalSecurity,
                onDeleteLocalHistory = onDeleteLocalSecurityHistory,
                onBack = { profileDetail = null },
            )
        }
        return
    }

    Scaffold(
        containerColor = Ink,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 20.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("MeshChat", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Icon(destination.icon, contentDescription = "${destination.label}页面", tint = Cyan, modifier = Modifier.size(28.dp))
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.foundation.layout.Box(
                        Modifier.size(10.dp).background(MeshGreen, androidx.compose.foundation.shape.CircleShape),
                    )
                    Text(
                        text = "发现节点 ${peers.size}",
                        color = MeshGreen,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 9.dp),
                    )
                }
            }
        },
        bottomBar = {
            Column {
                if (destination == MainDestination.CHATS) SecurityNote(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                )
                NavigationBar(
                    containerColor = InkRaised,
                    modifier = Modifier.navigationBarsPadding(),
                ) {
                    MainDestination.entries.forEach { item ->
                        NavigationBarItem(
                            selected = destination == item,
                            onClick = { destinationName = item.name },
                            icon = { Icon(item.icon, contentDescription = item.label) },
                            label = { Text(item.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Cyan,
                                selectedTextColor = Cyan,
                                indicatorColor = Color.Transparent,
                                unselectedIconColor = TextSecondary,
                                unselectedTextColor = TextSecondary,
                            ),
                        )
                    }
                }
            }
        },
    ) { contentPadding ->
        AnimatedContent(targetState = destination, label = "main destination") { activeDestination ->
            when (activeDestination) {
                MainDestination.CHATS -> ChatsScreen(
                    modifier = Modifier.padding(contentPadding),
                    conversations = conversations,
                    onChatSelected = { onOpenConversation(it) }, // 进入所选会话（id = 对端短 ID），而非硬编码"我"
                    onToggleArchived = onToggleConversationArchived,
                    onDeleteConversation = onDeleteConversation,
                    // v1.1.50：群组分区 + 创建群/加入群 + 进入群会话
                    groups = groups,
                    onGroupSelected = onOpenConversation,
                    onCreateGroup = onCreateGroup,
                    onJoinGroup = onJoinGroup,
                )
                MainDestination.MESH -> MeshScreen(
                    modifier = Modifier.padding(contentPadding),
                    peers = peers,
                    sessions = sessions,
                    pendingInvites = pendingInvites,
                    onStartDiscovery = onStartDiscovery,
                    onPeerSelected = { peerId ->
                        // 未建立会话则先发邀请；无论状态都进入会话页（发起方即时反馈）
                        if (peerId !in sessions) onSendInvite(peerId)
                        onOpenConversation(peerId)
                    },
                    discoveryEnabled = discoveryEnabled,
                    onToggleDiscovery = onToggleDiscovery,
                )
                MainDestination.PROFILE -> ProfileScreen(
                    modifier = Modifier.padding(contentPadding),
                    onOpenKeys = { profileDetail = "keys" },
                    onOpenSettings = { profileDetail = "settings" },
                    onOpenAbout = { profileDetail = "about" },
                    onOpenSecurityCenter = { profileDetail = "security" },
                    onOpenDebugCenter = { profileDetail = "debug" },
                )
            }
        }
    }
}
