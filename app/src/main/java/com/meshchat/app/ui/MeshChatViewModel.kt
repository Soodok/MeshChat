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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MeshChatViewModel(
    private val repository: MeshRepository,
    val localBluetoothName: String,
    val localBluetoothAddress: String,
    private val displayNameProvider: () -> String,
    private val setDisplayName: (String) -> Unit,
    private val backgroundEnabledProvider: () -> Boolean,
    private val setBackgroundEnabled: (Boolean) -> Unit,
    private val autoDiscoveryProvider: () -> Boolean,
    private val setAutoDiscovery: (Boolean) -> Unit,
    private val wifiDirectEnabledProvider: () -> Boolean,
    private val setWifiDirectEnabled: (Boolean) -> Unit,
    private val conversationPreferences: ConversationPreferences,
    private val conversationRequest: kotlinx.coroutines.flow.StateFlow<String?>,
    private val securityCapabilityManager: SecurityCapabilityManager,
    private val localSecurityCoordinator: LocalSecurityCoordinator,
    private val debugStats: com.meshchat.app.mesh.debug.DebugStats,
    /** v1.1.58 应用锁：密码/指纹解锁 + DEK 保护敏感密钥库。 */
    private val appLock: com.meshchat.app.security.lock.AppLockManager,
    /** v1.1.64 静默偏好持久化（Application 纯写 SharedPrefs，不触发服务调用）。 */
    private val setSilentMode: (Boolean) -> Unit,
    /** v1.1.66 频道名持久化（Application.channelName setter 注入）。 */
    private val persistChannelName: (String?) -> Unit,
    /** Wi-Fi Direct 增强层状态流（Mesh 页 WIFI 状态栏数据源）。 */
    private val wifiDirectStateInput: kotlinx.coroutines.flow.StateFlow<com.meshchat.app.mesh.wifidirect.WifiDirectTransport.State>,
    /** Wi-Fi Direct 不可用原因（Mesh 页精确提示：Wi-Fi 未开/权限缺失/设备不支持）。 */
    private val wifiDirectUnavailableInput: kotlinx.coroutines.flow.StateFlow<com.meshchat.app.mesh.wifidirect.WifiDirectTransport.UnavailableReason>,
) : ViewModel() {
    /** 当前打开的会话目标（对端短 ID）；null = 未打开会话。 */
    private val conversationTarget = MutableStateFlow<String?>(null)

    /** Wi-Fi Direct 完整状态（DISABLED/DISCOVERING/CONNECTING/GROUPED/RECONNECTING）：Mesh 页据此显示三态 WIFI 状态栏。 */
    val wifiDirectState: StateFlow<com.meshchat.app.mesh.wifidirect.WifiDirectTransport.State> = wifiDirectStateInput

    /** Wi-Fi Direct 不可用原因（DISABLED + 开关开启时 Mesh 页精确提示）。 */
    val wifiDirectUnavailable: StateFlow<com.meshchat.app.mesh.wifidirect.WifiDirectTransport.UnavailableReason> = wifiDirectUnavailableInput

    /** Wi-Fi Direct 星域是否已连接（GROUPED）：快捷判定。 */
    val wifiDirectActive: StateFlow<Boolean> = wifiDirectStateInput
        .map { it == com.meshchat.app.mesh.wifidirect.WifiDirectTransport.State.GROUPED }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // ---- v1.1.58 应用锁（解锁屏 UI / 设置页密码区数据源）----
    /** 是否锁定（回前台自动锁，需密码/指纹解锁）。 */
    val appLocked: StateFlow<Boolean> = appLock.locked

    /** 连续失败锁定状态（5 次失败锁 30s；UI 显示倒计时）。 */
    val lockout: StateFlow<com.meshchat.app.security.lock.LockoutState> = appLock.lockout

    /** 是否已设密码（响应式，设置页"已启用/设置密码"切换）。 */
    val lockPasswordEnabled: StateFlow<Boolean> = appLock.passwordEnabled

    fun hasLockPassword(): Boolean = appLock.hasPassword

    fun lockBiometricAvailable(): Boolean = appLock.biometricAvailable()

    /** 设密码（<4 位抛 IllegalArgumentException，UI 自行校验）。 */
    fun setLockPassword(password: String) = appLock.setPassword(password)

    /** 改密码：旧密码错误返回 false（UI 提示）。 */
    fun changeLockPassword(oldPassword: String, newPassword: String): Boolean =
        appLock.changePassword(oldPassword, newPassword)

    fun removeLockPassword() = appLock.removePassword()

    /** 密码解锁：正确返回 true 并解锁；错误递增失败计数。 */
    fun verifyLockPassword(password: String): Boolean = appLock.verifyPassword(password)

    /** v1.1.75 指纹解锁：准备认证会话（Cipher 绑定生物密钥）；null = 指纹数据不可用。 */
    fun prepareBiometricSession(): com.meshchat.app.security.lock.BiometricAuthSession? =
        appLock.prepareBiometricSession()

    /** v1.1.75 指纹解锁：BiometricPrompt 认证成功回调里调用（认证后解密，兼容华为等 ROM）。 */
    fun finishBiometricUnlockAfterAuth(
        session: com.meshchat.app.security.lock.BiometricAuthSession? = null,
    ): Boolean = appLock.finishBiometricUnlockAfterAuth(session)

    /** 剩余锁定毫秒数（>0 时禁用解锁并倒计时）。 */
    fun remainingLockoutMs(): Long = appLock.remainingLockoutMs()

    /** v1.1.57 E2EE：发送被拒的一次性提示（UI collect 显示 Toast 后清空）。 */
    private val _sendRejected = MutableStateFlow<String?>(null)
    val sendRejected: StateFlow<String?> = _sendRejected
    fun consumeSendRejected() { _sendRejected.value = null }

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
        val showOsc: Boolean = true,       // 示波器板块显隐
        val showLog: Boolean = true,       // 诊断日志板块显隐（v1.1.34）
        val sortBy: String = "rssi",   // rssi / name / recent
        val paused: Boolean = false,
    )

    private val _debugSettings = MutableStateFlow(DebugSettings())
    val debugSettings: StateFlow<DebugSettings> = _debugSettings.asStateFlow()
    private val _debugSnapshot = MutableStateFlow<com.meshchat.app.mesh.debug.DebugSnapshot>(com.meshchat.app.mesh.debug.DebugSnapshot())
    val debugSnapshot: StateFlow<com.meshchat.app.mesh.debug.DebugSnapshot> = _debugSnapshot.asStateFlow()

    // ---- 调试中心·诊断日志（v1.1.34）：内存环形缓冲 + 导出 Download（真机免 adb 抓日志）----
    private val _debugLogLines = MutableStateFlow<List<String>>(emptyList())
    val debugLogLines: StateFlow<List<String>> = _debugLogLines.asStateFlow()
    fun refreshDebugLogs() {
        _debugLogLines.value = com.meshchat.app.mesh.debug.DebugLogBuffer.recent(120)
    }
    fun clearDebugLogs() {
        com.meshchat.app.mesh.debug.DebugLogBuffer.clear()
        _debugLogLines.value = emptyList()
    }

    // ---- 调试中心·主动控制（内存态，重启回默认）----
    /** 当前生效控制档位/暂停标记/上次手动 PING 时刻。 */
    data class DebugControlState(
        val heartbeatMs: Long = 1_000L,
        val lostMs: Long = 2_000L,          // 失联阈值固定默认 2s，不可调
        val resendBaseMs: Long = 3_000L,
        val resendMaxMs: Long = 30_000L,
        val signalingSuspended: Boolean = false,
        val lastPingAtMs: Long = -1L,   // -1 = 尚未手动发过
        val manualPingCount: Int = 0,   // 手动 PING 累计次数
        val txPowerDbm: Int = 1,        // 广播发射功率档（默认 +1dBm HIGH）
    )

    // ---- 调试中心·示波器（采样历史，内存态；随刷新循环追加，重启清零）----
    /** 单次采样点：发送/接收总速率 + 本轮失败事件速率（包/秒，与收发包同单位，可直观对比）。 */
    data class OscPoint(val sentRate: Double, val recvRate: Double, val failureRate: Double)

    private val _oscHistory = MutableStateFlow<List<OscPoint>>(emptyList())
    val oscHistory: StateFlow<List<OscPoint>> = _oscHistory.asStateFlow()
    private var prevFailureTotal = 0L   // 上次采样时失败累计（解码失败 + BLE 写/notify 失败）

    private val _debugControlState = MutableStateFlow(DebugControlState())
    val debugControlState: StateFlow<DebugControlState> = _debugControlState.asStateFlow()

    fun sendDebugControl(cmd: DebugControl) {
        debugStats.issue(cmd)
        _debugControlState.value = when (cmd) {
            is DebugControl.SetHeartbeat -> _debugControlState.value.copy(heartbeatMs = cmd.intervalMs)
            is DebugControl.SetResendPolicy -> _debugControlState.value.copy(resendBaseMs = cmd.baseMs, resendMaxMs = cmd.maxMs)
            DebugControl.SuspendSignaling -> _debugControlState.value.copy(signalingSuspended = true)
            DebugControl.ResumeSignaling -> _debugControlState.value.copy(signalingSuspended = false)
            is DebugControl.SetTxPower -> _debugControlState.value.copy(txPowerDbm = cmd.txPowerDbm)
            DebugControl.BroadcastPing -> _debugControlState.value.copy(
                lastPingAtMs = System.currentTimeMillis(),
                manualPingCount = _debugControlState.value.manualPingCount + 1,
            )
            DebugControl.ResetControls -> DebugControlState()
        }
    }

    fun resetDebugControls() {
        debugStats.issue(DebugControl.ResetControls)
        _debugControlState.value = DebugControlState()
    }

    val backgroundEnabled: Boolean get() = backgroundEnabledProvider()

    fun updateBackgroundEnabled(value: Boolean) = setBackgroundEnabled(value)

    /** 打开应用时自动搜索（v1.1.49，默认开）：关闭后启动/回前台不自动广播+扫描。 */
    val autoDiscovery: Boolean get() = autoDiscoveryProvider()

    fun updateAutoDiscovery(value: Boolean) = setAutoDiscovery(value)

    /** Wi-Fi Direct 增强（Beta v1.1.51，默认关）：开启后自动与邻近设备建连形成星域（消息双通道/文件高速）。 */
    val wifiDirectEnabled: Boolean get() = wifiDirectEnabledProvider()

    fun updateWifiDirectEnabled(value: Boolean) = setWifiDirectEnabled(value)

    /**
     * v1.1.53 发现模式（取代 v1.1.49 布尔开关）：
     * NORMAL 全开 / CLOSED 全停（autoDiscovery=关 启动态）/ SILENT 静默（只停广播，scan/连接/保活照常）。
     */
    val discoveryMode: StateFlow<com.meshchat.app.mesh.transport.DiscoveryMode> = repository.discoveryMode

    /** v1.1.53 下发发现模式。v1.1.64 同步持久化静默偏好（重启/蓝牙重建后恢复静默）。 */
    fun setDiscoveryMode(mode: com.meshchat.app.mesh.transport.DiscoveryMode) {
        repository.setDiscoveryMode(mode)
        setSilentMode(mode == com.meshchat.app.mesh.transport.DiscoveryMode.SILENT)
    }

    /** 切换静默模式：NORMAL ↔ SILENT（静默 = 陌生人扫不到你，其余功能照常）。 */
    fun toggleSilentMode() {
        if (repository.discoveryMode.value == com.meshchat.app.mesh.transport.DiscoveryMode.SILENT) {
            repository.setDiscoveryMode(com.meshchat.app.mesh.transport.DiscoveryMode.NORMAL)
            setSilentMode(false)
        } else {
            repository.setDiscoveryMode(com.meshchat.app.mesh.transport.DiscoveryMode.SILENT)
            setSilentMode(true)
        }
    }

    // ---- v1.1.64 拉黑（删除对话 = 拒绝连接与消息；v1.1.65 Mesh 页未连接节点也可主动拉黑）----
    val blockedPeers: StateFlow<Set<String>> = repository.blockedPeers

    fun blockPeer(peerId: String) = repository.blockPeer(peerId)

    fun unblockPeer(peerId: String) = repository.unblockPeer(peerId)

    // ---- v1.1.66 频道系统（单频道制：公共 / 私人）----
    val channelName: StateFlow<String?> = repository.channelName

    /** 切换频道：持久化 + 下发服务层（换指纹广播/清表/重新发现）。null = 公共频道。 */
    fun setChannel(name: String?) {
        persistChannelName(name)
        repository.setChannel(name)
    }

    // ---- v1.1.74 MITM 防御（公钥指纹 + 密钥连续性告警）----
    /** 对端公钥指纹与首次记录不一致（身份变更）的节点集合。 */
    val peerKeyChanged: StateFlow<Set<String>> = repository.peerKeyChanged

    /** 对端公钥指纹（首次握手记录）；null = 未握手。 */
    fun peerFingerprint(peerId: String): String? = repository.peerFingerprint(peerId)

    /** 本机密钥是否降级内存密钥（不持久，重启更换——身份页提示用）。 */
    val localKeyFallback: Boolean get() = repository.localKeyFallback

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
                        refreshDebugLogs()
                        val snap = debugStats.snapshot(s.windowMs)
                        _debugSnapshot.value = snap.copy(peers = when (s.sortBy) {
                            "name" -> snap.peers.sortedBy { it.displayName }
                            "recent" -> snap.peers.sortedBy { it.lastSeenAgoMs }
                            else -> snap.peers.sortedByDescending { it.rssi }
                        })
                        // 示波器采样：总收发速率 + 本轮失败事件速率（包/秒，与收发包同单位）
                        val f = snap.failures
                        val failureTotal = f.receivedDecodeFailures + f.bleWriteFailed + f.bleNotifyFailed
                        val failureRate = (failureTotal - prevFailureTotal) / (s.refreshIntervalMs / 1000.0)
                        prevFailureTotal = failureTotal
                        _oscHistory.value = (
                            _oscHistory.value + OscPoint(
                                sentRate = snap.frames.values.sumOf { it.sentRatePerSec },
                                recvRate = snap.frames.values.sumOf { it.receivedRatePerSec },
                                failureRate = failureRate,
                            )
                            ).takeLast(OSC_MAX_POINTS)
                    }
                } catch (t: Throwable) {
                    android.util.Log.w("MeshChatVM", "debug loop iteration failed", t)
                }
                delay(_debugSettings.value.refreshIntervalMs)
            }
        }
    }

    private companion object {
        const val OSC_MAX_POINTS = 96   // 示波器横轴采样点数（刷新间隔 × 96 = 时间窗口）
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
        // 审查 M1 修复：flatMapLatest 触发时同步判定会话键（读 _groupTargetIds），
        // 创建群后立即打开会话也走 group-<id>，不再依赖异步 groups 刷新
        conversationTarget.map { target -> target to (target != null && isGroupTarget(target)) }
            .flatMapLatest { (target, isGroup) ->
                if (target == null) flowOf(emptyList())
                else repository.observeMessages(if (isGroup) "group-$target" else "conv-$target")
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

    /** v1.1.50 群列表（已订阅群：id + 显示名）。 */
    val groups: StateFlow<List<com.meshchat.app.mesh.service.GroupInfo>> = repository.observeGroups()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 已确认的群目标 ID（审查 M1 修复）：createGroup/joinGroup 成功后**同步登记**。
     * service.groups 是 combine 跨协程异步刷新，创建群后立即 openConversation 时可能读到旧值，
     * 导致消息流误用 conv-<groupId>（群消息"消失"）。此集合提供同步判定源，消除竞态窗口。
     */
    private val _groupTargetIds = MutableStateFlow<Set<String>>(emptySet())

    /** v1.1.50：会话目标是否为群（groupId 在已订阅群集合中）。同步源优先，异步 groups 兜底。 */
    private fun isGroupTarget(target: String): Boolean =
        target in _groupTargetIds.value || groups.value.any { it.id == target }

    val pendingInvites: StateFlow<Set<String>> = repository.observePendingInvites()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val invites: StateFlow<Map<String, Long>> = repository.observeInvites()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val localShortId: String = repository.localShortId()

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

    /** 向当前会话发送消息：目标取当前会话，而非硬编码"我"（修复发消息永远自环）。
     *  v1.1.50：群会话走 sendGroupMessage（泛洪广播域），点对点走 sendText。 */
    fun sendMessage(text: String) {
        if (text.isBlank()) return
        val target = conversationTarget.value ?: return
        viewModelScope.launch {
            if (isGroupTarget(target)) {
                repository.sendGroupMessage(target, text.trim())
            } else {
                // v1.1.57 E2EE 强制加密：无会话密钥（对方旧版本/未协商）→ 拒绝发送并提示
                // v1.1.66 区分拒绝原因：无会话密钥 vs 对方不在当前频道（私人频道隔离）
                val sent = repository.sendText("conv-$target", text.trim())
                if (!sent) {
                    _sendRejected.value = if (repository.isPeerInCurrentChannel(target)) {
                        "对方未启用加密，无法发送消息"
                    } else {
                        "对方不在当前频道，无法发送"
                    }
                }
            }
        }
    }

    /** v1.1.50 创建群：生成群 ID + 本地订阅 + 广播群创建帧，创建后直接进入群会话。
     *  _groupTargetIds 先同步登记（审查 M1），openConversation 的消息流才能立即走 group- 会话键。 */
    fun createGroup(groupName: String) {
        if (groupName.isBlank()) return
        viewModelScope.launch {
            val groupId = repository.createGroup(groupName.trim())
            _groupTargetIds.update { it + groupId }
            openConversation(groupId)
        }
    }

    /** v1.1.50 加入群（审查 M4 修复）：输入群 ID 本地订阅（持久化），加入后进入群会话。 */
    fun joinGroup(groupId: String) {
        if (groupId.isBlank()) return
        viewModelScope.launch {
            repository.joinGroup(groupId.trim())
            _groupTargetIds.update { it + groupId.trim() }
            openConversation(groupId.trim())
        }
    }

    /** 发送文件到当前会话（串行：传输中 sendFile 内部拒绝）。size 为 0 时拒绝（空文件不支持）。
     *  群会话不支持文件（文件多跳 = 阶段 C 范围外），直接忽略。 */
    fun sendFile(openSource: () -> java.io.InputStream, fileName: String, mime: String, size: Long) {
        if (size <= 0) return
        val target = conversationTarget.value ?: return
        if (isGroupTarget(target)) return
        viewModelScope.launch {
            repository.sendFile("conv-$target", target, openSource, fileName, mime, size)
        }
    }
}
