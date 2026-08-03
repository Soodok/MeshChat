package com.meshchat.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meshchat.app.data.ChatMessage
import com.meshchat.app.data.ChatPreview
import com.meshchat.app.data.FileUiMeta
import com.meshchat.app.data.MeshPeer
import com.meshchat.app.data.MeshRepository
import com.meshchat.app.mesh.transfer.TransferStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MeshChatViewModel(
    private val repository: MeshRepository,
    val localBluetoothName: String,
    val localBluetoothAddress: String,
    private val displayNameProvider: () -> String,
    private val setDisplayName: (String) -> Unit,
    private val backgroundEnabledProvider: () -> Boolean,
    private val setBackgroundEnabled: (Boolean) -> Unit,
    private val conversationRequest: kotlinx.coroutines.flow.StateFlow<String?>,
) : ViewModel() {
    /** 当前打开的会话目标（对端短 ID）；null = 未打开会话。 */
    private val conversationTarget = MutableStateFlow<String?>(null)

    /** 供 UI 展示当前会话状态。 */
    val currentConversation: StateFlow<String?> = conversationTarget

    val localDisplayName: String get() = displayNameProvider()

    fun updateDisplayName(value: String) = setDisplayName(value)

    val backgroundEnabled: Boolean get() = backgroundEnabledProvider()

    fun updateBackgroundEnabled(value: Boolean) = setBackgroundEnabled(value)

    init {
        // 通知点击 → 打开对应会话（convId = conv-<shortId>，target 取短 ID）
        viewModelScope.launch {
            conversationRequest.collect { convId ->
                convId?.let { openConversation(it.substringAfterLast("-")) }
            }
        }
    }

    /** 文件传输进度（发送/接收统一）。 */
    val fileProgress: StateFlow<com.meshchat.app.mesh.transfer.FileProgress?> =
        repository.observeFileProgress()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 进度按 fileId 映射为气泡可用的文件元数据（用于动态进度条）。 */
    private val fileProgressMap: StateFlow<Map<String, FileUiMeta>> = fileProgress
        .map { p ->
            if (p == null) emptyMap()
            else mapOf(
                p.fileId to FileUiMeta(
                    fileName = p.fileName, size = p.totalBytes,
                    progress = if (p.totalBytes > 0) ((p.transferredBytes * 100) / p.totalBytes).toInt().coerceIn(0, 100) else 0,
                    done = p.status == TransferStatus.DONE,
                ),
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** 消息随当前会话切换：打开哪个会话就观察哪个会话的消息；文件消息叠加实时进度。
     *  未打开会话时发射空列表——否则 flatMapLatest 会 fallback 到 conv-ME，进入会话瞬间短暂显示上个会话/自环消息，
     *  列表 size 突变导致自动滚动被反复打断（视觉上"滚到底又弹回顶"）。 */
    val messages: StateFlow<List<ChatMessage>> = combine(
        conversationTarget.flatMapLatest { target ->
            if (target == null) flowOf(emptyList()) else repository.observeMessages("conv-$target")
        },
        fileProgressMap,
    ) { list, progressMap ->
        list.map { m -> if (m.file != null && m.id in progressMap) m.copy(file = progressMap[m.id]) else m }
    }
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

    /** 发送文件到当前会话（串行：传输中 sendFile 内部拒绝）。size 为 0 时拒绝（空文件不支持）。 */
    fun sendFile(openSource: () -> java.io.InputStream, fileName: String, mime: String, size: Long) {
        if (size <= 0) return
        val target = conversationTarget.value ?: return
        viewModelScope.launch {
            repository.sendFile("conv-$target", target, openSource, fileName, mime, size)
        }
    }
}
