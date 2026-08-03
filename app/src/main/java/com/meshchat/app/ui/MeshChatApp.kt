package com.meshchat.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meshchat.app.ui.screens.MeshChatHome
import com.meshchat.app.ui.theme.Ink

@Composable
fun MeshChatApp(viewModel: MeshChatViewModel = viewModel(factory = MeshChatViewModelFactory())) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val peers by viewModel.peers.collectAsStateWithLifecycle()
    Surface(modifier = androidx.compose.ui.Modifier.fillMaxSize(), color = Ink) {
        MeshChatHome(
            messages = messages,
            conversations = conversations,
            peers = peers,
            localShortId = viewModel.localShortId,
            onStartDiscovery = viewModel::startDiscovery,
            onSendMessage = viewModel::sendMessage,
        )
    }
}
