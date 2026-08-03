package com.meshchat.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meshchat.app.data.ChatMessage
import com.meshchat.app.data.ChatPreview
import com.meshchat.app.data.MeshPeer
import com.meshchat.app.data.MeshRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MeshChatViewModel(
    private val repository: MeshRepository,
    val localBluetoothName: String,
    val localBluetoothAddress: String,
) : ViewModel() {
    /** 当前打开的会话目标（对端短 ID）；null = 未打开会话。 */
    private val conversationTarget = MutableStateFlow<String?>(null)

    /** 供 UI 展示当前会话状态。 */
    val currentConversation: StateFlow<String?> = conversationTarget

    /** 消息随当前会话切换：打开哪个会话就观察哪个会话的消息。 */
    val messages: StateFlow<List<ChatMessage>> = conversationTarget
        .flatMapLatest { target -> repository.observeMessages("conv-${target ?: "ME"}") }
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

    /** 打开/关闭会话（target = 对端短 ID；null = 返回列表）。 */
    fun openConversation(target: String?) {
        conversationTarget.value = target
    }

    /** 向当前会话发送消息：目标取当前会话，而非硬编码"我"（修复发消息永远自环）。 */
    fun sendMessage(text: String) {
        if (text.isBlank()) return
        val target = conversationTarget.value ?: return
        viewModelScope.launch { repository.sendText("conv-$target", text.trim()) }
    }
}
