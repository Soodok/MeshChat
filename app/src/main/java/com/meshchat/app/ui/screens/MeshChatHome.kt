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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
    localShortId: String,
    onStartDiscovery: () -> Unit,
    onSendMessage: (String) -> Unit,
) {
    var destinationName by rememberSaveable { mutableStateOf(MainDestination.CHATS.name) }
    var conversationTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var profileDetail by rememberSaveable { mutableStateOf<String?>(null) }
    val destination = MainDestination.valueOf(destinationName)

    BackHandler(enabled = conversationTarget != null || profileDetail != null) {
        if (conversationTarget != null) conversationTarget = null else profileDetail = null
    }

    if (conversationTarget != null) {
        val title = if (conversationTarget == "ME") "我" else conversationTarget!!
        ConversationScreen(messages = messages, title = title, onBack = { conversationTarget = null }, onSendMessage = onSendMessage)
        return
    }

    if (profileDetail != null) {
        when (profileDetail) {
            "keys" -> IdentityKeyScreen(shortId = localShortId, onBack = { profileDetail = null })
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
                    onChatSelected = { conversationTarget = "ME" },
                )
                MainDestination.MESH -> MeshScreen(
                    modifier = Modifier.padding(contentPadding),
                    peers = peers,
                    onStartDiscovery = onStartDiscovery,
                    onPeerSelected = { conversationTarget = it },
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
