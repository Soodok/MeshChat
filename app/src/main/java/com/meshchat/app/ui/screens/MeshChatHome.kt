package com.meshchat.app.ui.screens

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.meshchat.app.data.ChatMessage
import com.meshchat.app.data.ChatPreview
import com.meshchat.app.data.MainDestination
import com.meshchat.app.data.MeshPeer
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
    pendingInvites: Set<String>,
    invites: Map<String, Long>,
    localShortId: String,
    localBluetoothName: String,
    localBluetoothAddress: String,
    conversationTarget: String?,
    onOpenConversation: (String?) -> Unit,
    onStartDiscovery: () -> Unit,
    onSendInvite: (String) -> Unit,
    onAcceptInvite: (String) -> Unit,
    onRejectInvite: (String) -> Unit,
    onSendMessage: (String) -> Unit,
) {
    var destinationName by rememberSaveable { mutableStateOf(MainDestination.CHATS.name) }
    var profileDetail by rememberSaveable { mutableStateOf<String?>(null) }
    val destination = MainDestination.valueOf(destinationName)

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
        val title = if (target == "ME") "我" else target
        val connected = target == "ME" || target in sessions
        ConversationScreen(
            messages = messages,
            title = title,
            connected = connected,
            onBack = { onOpenConversation(null) },
            onSendMessage = onSendMessage,
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
            "settings" -> GeneralSettingsScreen(onBack = { profileDetail = null })
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
                )
                MainDestination.PROFILE -> ProfileScreen(
                    modifier = Modifier.padding(contentPadding),
                    onOpenKeys = { profileDetail = "keys" },
                    onOpenSettings = { profileDetail = "settings" },
                )
            }
        }
    }
}
