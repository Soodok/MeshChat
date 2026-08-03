package com.meshchat.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meshchat.app.data.ChatMessage
import com.meshchat.app.data.ChatPreview
import com.meshchat.app.data.MeshPeer
import com.meshchat.app.data.MeshRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MeshChatViewModel(
    private val repository: MeshRepository,
    val localBluetoothName: String,
    val localBluetoothAddress: String,
) : ViewModel() {
    val messages: StateFlow<List<ChatMessage>> = repository.observeMessages("conv-ME")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val conversations: StateFlow<List<ChatPreview>> = repository.observeConversations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val peers: StateFlow<List<MeshPeer>> = repository.observePeers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val sessions: StateFlow<Set<String>> = repository.observeSessions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val pendingInvites: StateFlow<Set<String>> = repository.observePendingInvites()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val invites: StateFlow<Map<String, Long>> = repository.observeInvites()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val localShortId: String = repository.localShortId()

    fun startDiscovery() {
        viewModelScope.launch { repository.startDiscovery() }
    }

    fun sendInvite(peerId: String) {
        viewModelScope.launch { repository.sendInvite(peerId) }
    }

    fun acceptInvite(peerId: String) {
        viewModelScope.launch { repository.acceptInvite(peerId) }
    }

    fun rejectInvite(peerId: String) {
        viewModelScope.launch { repository.rejectInvite(peerId) }
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch { repository.sendText("conv-ME", text.trim()) }
    }
}
