package com.meshchat.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meshchat.app.data.ChatMessage
import com.meshchat.app.data.ChatPreview
import com.meshchat.app.data.ConversationPreferences
import com.meshchat.app.data.FileUiMeta
import com.meshchat.app.data.MeshPeer
import com.meshchat.app.data.MeshRepository
import com.meshchat.app.mesh.debug.DebugControl
import com.meshchat.app.mesh.transfer.TransferStatus
import com.meshchat.app.security.capability.SecurityCapabilityManager
import com.meshchat.app.security.capability.SecurityCapabilityStatus
import com.meshchat.app.security.model.SecurityCapability
import com.meshchat.app.security.local.LocalSecurityCoordinator
import com.meshchat.app.security.local.LocalSecuritySnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val conversationPreferences: ConversationPreferences,
    private val conversationRequest: kotlinx.coroutines.flow.StateFlow<String?>,
    private val securityCapabilityManager: SecurityCapabilityManager,
    private val localSecurityCoordinator: LocalSecurityCoordinator,
    private val debugStats: com.meshchat.app.mesh.debug.DebugStats,
) : ViewModel() {
    /** 当前打开的会话目标（对端短 ID）；null = 未打开会话。 */
    private val conversationTarget = MutableStateFlow<String?>(null)

    /** 供 UI 展示当前会话状态。 */
    val currentConversation: StateFlow<String?> = conversationTarget

    val localDisplayName: String get() = displayNameProvider()

    /** 安全中心在 Step 04 消费的只读能力状态；不影响基础聊天可用性。 */
    val securityCapabilities: StateFlow<Map<com.meshchat.app.security.model.SecurityCapability, SecurityCapabilityStatus>> =
        securityCapabilityManager.status

    private val _localSecuritySnapshot = MutableStateFlow<LocalSecuritySnapshot?>(null)
    val localSecuritySnapshot: StateFlow<LocalSecuritySnapshot?> = _localSecuritySnapshot

    fun recordSecurityCapabilityResult(capability: SecurityCapability, granted: Boolean) {
        if (granted) securityCapabilityManager.recordGranted(setOf(capability))
        else securityCapabilityManager.recordDenied(setOf(capability))
    }

    fun updateDisplayName(value: String) {
        if (sessions.value.isNotEmpty()) setDisplayName(value)
    }

    // ---- 调试中心（必须在 init 之前声明：Kotlin 按声明顺序初始化，init 里 startDebugLoop 立即读取这些属性）----
    /** 仪表盘调节项（内存态，重启回默认；暂停仅冻结刷新，计数继续累积）。 */
    data class DebugSettings(
        val refreshIntervalMs: Long = 1_000L,
        val windowMs: Long = 5_000L,
        val perMinute: Boolean = false,
        val showFrames: Boolean = true,
        val showBle: Boolean = true,
        val showRoutes: Boolean = true,
        val showDelivery: Boolean = true,
        val showFile: Boolean = true,
        val showControl: Boolean = true,   // 主动控制板块显隐
        val showFailure: Boolean = true,   // 失败包板块显隐
        val sortBy: String = "rssi",   // rssi / name / recent
        val paused: Boolean = false,
    )

    private val _debugSettings = MutableStateFlow(DebugSettings())
    val debugSettings: StateFlow<DebugSettings> = _debugSettings.asStateFlow()
    private val _debugSnapshot = MutableStateFlow<com.meshchat.app.mesh.debug.DebugSnapshot>(com.meshchat.app.mesh.debug.DebugSnapshot())
    val debugSnapshot: StateFlow<com.meshchat.app.mesh.debug.DebugSnapshot> = _debugSnapshot.asStateFlow()

    // ---- 调试中心·主动控制（内存态，重启回默认）----
    /** 当前生效控制档位/暂停标记/上次手动 PING 时刻。 */
    data class DebugControlState(
        val heartbeatMs: Long = 1_000L,
        val lostMs: Long = 2_000L,
        val resendBaseMs: Long = 3_000L,
        val resendMaxMs: Long = 30_000L,
        val signalingSuspended: Boolean = false,
        val lastPingAtMs: Long = -1L,   // -1 = 尚未手动发过
    )

    private val _debugControlState = MutableStateFlow(DebugControlState())
    val debugControlState: StateFlow<DebugControlState> = _debugControlState.asStateFlow()

    fun sendDebugControl(cmd: DebugControl) {
        debugStats.issue(cmd)
        _debugControlState.value = when (cmd) {
            is DebugControl.SetHeartbeat -> _debugControlState.value.copy(heartbeatMs = cmd.intervalMs, lostMs = cmd.lostMs)
            is DebugControl.SetResendPolicy -> _debugControlState.value.copy(resendBaseMs = cmd.baseMs, resendMaxMs = cmd.maxMs)
            DebugControl.SuspendSignaling -> _debugControlState.value.copy(signalingSuspended = true)
            DebugControl.ResumeSignaling -> _debugControlState.value.copy(signalingSuspended = false)
            DebugControl.BroadcastPing -> _debugControlState.value.copy(lastPingAtMs = System.currentTimeMillis())
            DebugControl.ResetControls -> DebugControlState()
        }
    }

    fun resetDebugControls() {
        debugStats.issue(DebugControl.ResetControls)
        _debugControlState.value = DebugControlState()
    }

    val backgroundEnabled: Boolean get() = backgroundEnabledProvider()

    fun updateBackgroundEnabled(value: Boolean) = setBackgroundEnabled(value)

    init {
        securityCapabilityManager.refresh()
        refreshLocalSecurity()
        // 通知点击 → 打开对应会话（convId = conv-<shortId>，target 取短 ID）
        viewModelScope.launch {
            conversationRequest.collect { convId ->
                convId?.let { openConversation(it.substringAfterLast("-")) }
            }
        }
        startDebugLoop()
    }

    fun updateDebugSettings(transform: (DebugSettings) -> DebugSettings) {
        _debugSettings.value = transform(_debugSettings.value)
    }

    fun resetDebugStats() = debugStats.reset()

    private fun startDebugLoop() {
        viewModelScope.launch {
            // 防御：整个循环体（含设置读取/快照聚合）异常只跳过本轮，绝不让循环崩溃拖垮进程
            while (true) {
                try {
                    val s = _debugSettings.value
                    if (!s.paused) {
                        val snap = debugStats.snapshot(s.windowMs)
                        _debugSnapshot.value = snap.copy(peers = when (s.sortBy) {
                            "name" -> snap.peers.sortedBy { it.displayName }
                            "recent" -> snap.peers.sortedBy { it.lastSeenAgoMs }
                            else -> snap.peers.sortedByDescending { it.rssi }
                        })
                    }
                } catch (t: Throwable) {
                    android.util.Log.w("MeshChatVM", "debug loop iteration failed", t)
                }
                delay(_debugSettings.value.refreshIntervalMs)
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

    private val archivedConversationIds = MutableStateFlow(conversationPreferences.archivedIds())
    private val readTimes = MutableStateFlow(conversationPreferences.readTimes())

    val conversations: StateFlow<List<ChatPreview>> = combine(
        repository.observeConversations(), archivedConversationIds, readTimes, conversationTarget,
    ) { list, archivedIds, reads, openConversationId ->
        list.map { preview ->
            preview.copy(
                archived = preview.id in archivedIds,
                unread = preview.id != openConversationId && !preview.lastMessageSentByMe && preview.lastMessageAt > (reads[preview.id] ?: 0L),
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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
        conversationTarget.value?.let { markConversationRead(it) }
        conversationTarget.value = target
        target?.let { markConversationRead(it) }
    }

    /** Runs on a worker: no network is consulted and no permission dialog is triggered. */
    fun refreshLocalSecurity() {
        viewModelScope.launch(Dispatchers.IO) {
            _localSecuritySnapshot.value = localSecurityCoordinator.refresh()
        }
    }

    fun deleteLocalSecurityHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            _localSecuritySnapshot.value = localSecurityCoordinator.deleteLocalHistory()
        }
    }

    fun toggleConversationArchived(peerId: String) {
        val archived = peerId !in archivedConversationIds.value
        conversationPreferences.setArchived(peerId, archived)
        archivedConversationIds.value = conversationPreferences.archivedIds()
    }

    fun deleteConversation(peerId: String) {
        viewModelScope.launch { repository.deleteConversation(peerId) }
    }

    private fun markConversationRead(peerId: String) {
        val now = System.currentTimeMillis()
        conversationPreferences.markRead(peerId, now)
        readTimes.value = readTimes.value + (peerId to now)
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
