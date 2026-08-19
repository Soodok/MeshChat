package com.meshchat.app.mesh.wifidirect

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.meshchat.app.mesh.debug.DebugLogBuffer
import com.meshchat.app.mesh.protocol.FrameType
import com.meshchat.app.mesh.protocol.MeshFrame
import com.meshchat.app.mesh.transport.MeshPeerInfo
import com.meshchat.app.mesh.transport.PeerPresence
import com.meshchat.app.mesh.transport.RfcommFraming
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Wi-Fi Direct 星域传输（Beta v1.1.51，独立目录 mesh/wifidirect）：
 * P2P 发现（DnsSd 携带短 ID，无连接识别）→ connect/GO negotiation 自动选主 → 组内 TCP 可靠通道
 * （RfcommFraming 4 字节长度前缀分帧）。双向 REGISTER（HELLO 帧）完成 TCP 连接的身份识别。
 *
 * v1.1.84 接入 MeshService 消息路由（WfdChannel）：P2P TCP 通道不再空转——
 * 单播帧（消息/心跳/文件块）优先走它，BLE 广播仅作中继/兜底。
 *
 * P1 范围：一对一连接打通。P2 扩展多成员星域（UDP 组内广播/成员表/断开重建）见规格 §8。
 */
class WifiDirectTransport(
    private val context: Context,
    private val shortId: String,
    private val displayName: String = "",
) : com.meshchat.app.mesh.service.WfdChannel {
    companion object {
        private const val TAG = "MeshWfd"
        const val SERVICE_TYPE = "_meshchat._tcp"
        const val TCP_PORT = 0x51C8          // 20936：应用层固定端口
        /** v1.1.84 发现轮周期 15s→5s：更快重试建连，收敛从"分钟级"降到"秒级"（竞争失败的节点 5s 内再试）。 */
        private const val DISCOVER_TIMEOUT_MS = 5_000L
        private const val TCP_CONNECT_TIMEOUT_MS = 5_000
        /** RECONNECTING 停留时长：之后自动回 DISCOVERING 重试（防状态机卡死永不重连）。 */
        private const val RECONNECT_BACKOFF_MS = 3_000L
        /** v1.1.85 pending connect 滞留超时：自动清除（防 connect 回调丢失/卡死导致永不重连）。 */
        private const val PENDING_CONNECT_TIMEOUT_MS = 30_000L
        /** v1.1.85 connect 错开延迟：MAC 大者延迟再 connect（先发者主导 negotiation，后发者 join，避免同时 connect 冲突）。 */
        private const val CONNECT_STAGGER_MS = 3_000L
        /** v1.1.85 连续 connect 失败阈值：达到后退避（环境异常/目标是自己时的失败风暴防御）。 */
        private const val MAX_CONNECT_FAILURES = 3
        /** v1.1.85 connect 失败退避时长。 */
        private const val CONNECT_BACKOFF_MS = 60_000L
        /** v1.1.86 标准 Wi-Fi Direct GO IP（Android P2P 固定子网 192.168.49.1）：部分 ROM 的 groupOwnerAddress 为 null 时兜底。 */
        private const val DEFAULT_GO_IP = "192.168.49.1"
        /** v1.1.86 TCP 保活周期：组内无业务流量时仍周期性写身份帧——① 探测半开 socket（read/write 双向探活）
         * ② 保持组活跃防 ROM 因"长时间无流量"丢组。HELLO 重复注册只补映射不回发，无 ping-pong 风暴。 */
        private const val TCP_KEEPALIVE_MS = 3_000L
    }

    enum class State { DISABLED, DISCOVERING, CONNECTING, GROUPED, RECONNECTING }

    /** 增强层不可用原因（Mesh 页精确提示：区分 Wi-Fi 未开 / 权限缺失 / 设备不支持）。 */
    enum class UnavailableReason { NONE, WIFI_OFF, PERMISSION_MISSING, NOT_SUPPORTED }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val manager: WifiP2pManager? =
        runCatching { context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager }.getOrNull()
    private val channel: WifiP2pManager.Channel? =
        runCatching { manager?.initialize(context, Looper.getMainLooper(), null) }.getOrNull()

    private val _incoming = MutableSharedFlow<MeshFrame>(extraBufferCapacity = 64)
    override val incoming: SharedFlow<MeshFrame> = _incoming
    private val _foundPeers = MutableSharedFlow<MeshPeerInfo>(extraBufferCapacity = 64)
    override val foundPeers: SharedFlow<MeshPeerInfo> = _foundPeers

    /** 状态机（StateFlow 镜像：Mesh 页据此显示 Wi-Fi Direct 连接状态/WIFI 信号栏）。线程安全。 */
    private val _state = MutableStateFlow(State.DISABLED)
    var state: State
        get() = _state.value
        private set(value) { _state.value = value }
    val stateFlow: StateFlow<State> = _state.asStateFlow()

    /** 不可用原因（State 为 DISABLED 且增强开关开启时，Mesh 页据此精确提示）。 */
    private val _unavailableReason = MutableStateFlow(UnavailableReason.NONE)
    val unavailableReason: StateFlow<UnavailableReason> = _unavailableReason.asStateFlow()

    /** 服务发现填充：deviceAddress ↔ P2P 设备（v1.1.85 主源 = requestPeers 设备发现，DnsSd TXT 仅加速身份识别）。 */
    private val knownDevices = ConcurrentHashMap<String, WifiP2pDevice>()
    /** v1.1.85 deviceAddress → shortId（DnsSd TXT 提供；无则连接后由 HELLO REGISTER 学习）。 */
    private val shortIdByAddress = ConcurrentHashMap<String, String>()
    /** 正在发起 connect 的 deviceAddress（防 5s 循环内对同一设备重复 connect）。 */
    private val pendingConnects = ConcurrentHashMap.newKeySet<String>()
    /** 已建 TCP 的 deviceAddress（防对已连接设备反复 connect）。 */
    private val connectedAddresses = ConcurrentHashMap.newKeySet<String>()
    /** 已建立 TCP 的成员：shortId ↔ (socket, 写锁)。 */
    private val sockets = ConcurrentHashMap<String, Pair<Socket, Any>>()
    /** 待身份识别的 TCP 连接：远端 IP ↔ (socket, 写锁)（REGISTER 到达后映射到 shortId）。 */
    private val tcpByIp = ConcurrentHashMap<String, Pair<Socket, Any>>()
    private var serverSocket: ServerSocket? = null
    @Volatile private var groupOwnerAddress: String? = null
    @Volatile private var isGroupOwner = false
    /** v1.1.85 本机 P2P MAC（GO negotiation 确定性选主/排除自己用；获取失败回落默认 intent）。 */
    @Volatile private var localDeviceAddress: String? = null
    /** v1.1.85 本机 P2P 设备名（requestPeers 列表可能含自己，用 MAC+设备名双重判据排除）。 */
    @Volatile private var localDeviceName: String? = null
    /** v1.1.85 各 deviceAddress 连续 connect 失败次数（≥3 退避 60s：环境异常/目标是自己时的失败风暴防御）。 */
    private val connectFailures = ConcurrentHashMap<String, Int>()
    /** v1.1.86 组内 TCP 连接进行中守卫（防 handleConnectionChange 重复 formed=true / 断线重试并发多次 connect）。 */
    @Volatile private var tcpConnecting = false
    /**
     * v1.1.88 GO 边缘偏好（用户："GO 位置优先提供给边缘节点，边缘失联时与中心节点通过 WiFi 连接"）：
     * BLE 失联兜底（MeshChatApplication 看门狗）期间置 true → 本机 connect 时 groupOwnerIntent=15（倾向当 GO）
     * 且**立即发起**（不等 MAC 字典序错开）——边缘节点主导建组，中心节点按 MAC 规则延迟 join。
     * 对连通性本身无影响（组内点对点），但让失联边缘节点主动掌握建组主动权。
     */
    @Volatile private var preferGroupOwner = false

    /** v1.1.88 设置 GO 边缘偏好（BLE 失联时 true，恢复时 false）。 */
    fun setPreferGroupOwner(value: Boolean) {
        preferGroupOwner = value
    }

    private val p2pReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            when (intent?.action) {
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO, WifiP2pInfo::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO)
                    }
                    handleConnectionChange(info)
                }
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> Unit
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> requestPeers()
            }
        }
    }

    // ---------- 日志（logcat + 调试中心诊断日志双写：真机免 adb 查看连接链路）----------

    /** 信息级：写入 logcat + 调试中心诊断日志。 */
    private fun wlog(msg: String) {
        Log.i(TAG, msg)
        DebugLogBuffer.log(TAG, msg)
    }

    /** 警告级：写入 logcat + 调试中心诊断日志。 */
    private fun wwlog(msg: String) {
        Log.w(TAG, msg)
        DebugLogBuffer.log(TAG, msg)
    }

    // ---------- 生命周期 ----------

    fun enable() {
        // 精确诊断不可用原因：Wi-Fi 未开 / 权限缺失 / P2P 服务不可用（Mesh 页据此提示用户具体操作）
        val reason = diagnoseUnavailable()
        _unavailableReason.value = reason
        if (reason != UnavailableReason.NONE) {
            state = State.DISABLED
            wwlog("wfd unavailable reason=$reason (wifi=${wifiEnabled()} manager=${manager != null} channel=${channel != null})")
            return
        }
        if (state != State.DISABLED) return
        state = State.DISCOVERING
        registerReceiver()
        registerServiceInfo()
        startTcpServer()
        // v1.1.85 获取本机 P2P 信息（MAC + 设备名）：requestPeers 的 deviceList 可能包含本机自己，
        // 需用本机 MAC/设备名双重判据排除，否则会 connect 自己（reason=2 死循环）
        runCatching { manager?.requestDeviceInfo(channel ?: return@runCatching) { d ->
            if (d != null) {
                localDeviceAddress = d.deviceAddress
                localDeviceName = d.deviceName
                wlog("local p2p device name=${d.deviceName} addr=${d.deviceAddress}")
            }
        } }
        scope.launch { discoveryLoop() }
        // v1.1.86 TCP 保活：组内无流量时周期性写身份帧，探半开 socket + 防 ROM 丢闲置组
        scope.launch { tcpKeepaliveLoop() }
        wlog("enabled shortId=$shortId")
    }

    /** 诊断增强层不可用原因（每次 enable 时评估；区分 Wi-Fi 开关/权限/P2P 支持）。 */
    private fun diagnoseUnavailable(): UnavailableReason {
        if (!wifiEnabled()) return UnavailableReason.WIFI_OFF
        // API 33+ 需 NEARBY_WIFI_DEVICES；API 31-32 需位置权限；更老版本无需运行时权限
        val needed = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> Manifest.permission.NEARBY_WIFI_DEVICES
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> Manifest.permission.ACCESS_FINE_LOCATION
            else -> null
        }
        if (needed != null &&
            ContextCompat.checkSelfPermission(context, needed) != PackageManager.PERMISSION_GRANTED
        ) return UnavailableReason.PERMISSION_MISSING
        if (manager == null || channel == null) return UnavailableReason.NOT_SUPPORTED
        return UnavailableReason.NONE
    }

    /** Wi-Fi 是否开启（Wi-Fi Direct 依赖 Wi-Fi 射频；蓝牙开 ≠ Wi-Fi 开）。 */
    private fun wifiEnabled(): Boolean = runCatching {
        val wm = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wm.isWifiEnabled
    }.getOrDefault(false)

    fun disable() {
        state = State.DISABLED
        runCatching { context.unregisterReceiver(p2pReceiver) }
        runCatching { manager?.removeGroup(channel, null) }
        runCatching { manager?.clearLocalServices(channel, null) }
        sockets.forEach { (_, p) -> runCatching { p.first.close() } }
        sockets.clear()
        tcpByIp.forEach { (_, p) -> runCatching { p.first.close() } }
        tcpByIp.clear()
        knownDevices.clear()
        shortIdByAddress.clear()
        pendingConnects.clear()
        connectedAddresses.clear()
        connectFailures.clear()
        tcpConnecting = false   // v1.1.86 复位 TCP 连接守卫（disable→enable 重启用）
        runCatching { serverSocket?.close() }
        serverSocket = null
        groupOwnerAddress = null
        wlog("disabled")
    }

    fun isGrouped(): Boolean = state == State.GROUPED

    /** 组内已建 TCP 的成员 shortId（v1.1.87 心跳/消息双链路广播用）。 */
    override fun members(): Set<String> = sockets.keys.toSet()

    override fun isConnectedTo(peerId: String): Boolean = sockets.containsKey(peerId)

    /** 发往单成员（TCP 可靠，多协程并发写按 socket 加锁防交错）。 */
    override fun sendTo(peerId: String, frame: MeshFrame) {
        val pair = sockets[peerId] ?: run { Log.d(TAG, "no socket for $peerId"); return }
        try {
            synchronized(pair.second) { RfcommFraming.writeFrame(pair.first.getOutputStream(), frame) }
        } catch (e: Exception) {
            wwlog("tcp write failed $peerId: $e")
            sockets.remove(peerId)
            runCatching { pair.first.close() }
        }
    }

    /** 组内广播：P1 对已建 TCP 成员单发（P2 改 UDP 组内广播 + 应用层组播兜底）。 */
    fun broadcast(frame: MeshFrame) {
        sockets.keys.toList().forEach { sendTo(it, frame) }
    }

    // ---------- 发现与连接 ----------

    private fun registerReceiver() {
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        }
        // 系统受保护广播可投递给 NOT_EXPORTED 动态接收器（不接收其他 App 的任意广播）
        runCatching { ContextCompat.registerReceiver(context, p2pReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED) }
    }

    /** 注册 DnsSd 服务（对端无连接发现本机短 ID）+ 监听对端服务并解析 TXT。整体异常隔离——实验性功能任何权限/栈异常降级静默，绝不崩溃。 */
    private fun registerServiceInfo() {
        runCatching {
            val txt = mapOf("shortid" to shortId, "name" to displayName)
            val info = WifiP2pDnsSdServiceInfo.newInstance("MeshChat", SERVICE_TYPE, txt)
            manager?.addLocalService(channel, info, null)
            val req = WifiP2pDnsSdServiceRequest.newInstance()
            manager?.setDnsSdResponseListeners(
                channel,
                { instanceName, regType, srcDevice ->
                    Log.d(TAG, "service resolved $instanceName $regType")
                },
                { fullName, txtRecord, srcDevice ->
                    val txt = txtRecord?.mapKeys { it.key.toString() }
                    val id = txt?.get("shortid") ?: return@setDnsSdResponseListeners
                    // v1.1.85 knownDevices 主键改 deviceAddress（requestPeers 设备发现可直连）；TXT 仅补 shortId 映射加速
                    knownDevices[srcDevice.deviceAddress] = srcDevice
                    shortIdByAddress[srcDevice.deviceAddress] = id
                    _foundPeers.tryEmit(
                        MeshPeerInfo(shortId = id, deviceAddress = srcDevice.deviceAddress, rssi = 0,
                            displayName = txt["name"] ?: "", presence = PeerPresence.ONLINE),
                    )
                    wlog("peer discovered id=$id addr=${srcDevice.deviceAddress}")
                },
            )
            manager?.addServiceRequest(channel, req, null)
        }.onFailure { wwlog("registerServiceInfo failed: $it") }
    }

    private suspend fun discoveryLoop() {
        var idleRounds = 0
        while (scope.isActive && state != State.DISABLED) {
            if (state == State.DISCOVERING) {
                manager?.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() = Unit
                    override fun onFailure(reason: Int) { wwlog("discover fail reason=$reason") }
                })
                // v1.1.82 关键修复：DnsSd 服务发现（setDnsSdResponseListeners 的 TXT 回调只由 discoverServices 触发）。
                // 原代码只 discoverPeers（设备发现）→ knownDevices 永远为空 → 建连链路整体断裂。两路发现并行，
                // discoverServices 超时后需重新触发，故放在循环内随轮次刷新。
                manager?.discoverServices(channel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() = Unit
                    override fun onFailure(reason: Int) { wwlog("discover services fail reason=$reason") }
                })
                // v1.1.85 设备发现主源：requestPeers（模拟器/部分 ROM 的 DnsSd TXT 回调不触发，
                // 仅 discoverPeers 无服务发现 → knownDevices 空 → 双方各自 createGroup 成独立 GO 永不互通）。
                // 设备层发现拿不到 shortId（应用 ID），建连走"MAC 直连 + TCP 后 HELLO REGISTER 互换身份"。
                // v1.1.85 同步化：requestPeers 回调异步（主线程），直接判 isEmpty 会拿到空表误触发 createGroup；
                // 阻塞等待回调结果（≤2s），决策基于实时设备列表。
                val peers = requestPeersBlocking()
                // v1.1.85 关键：requestPeers 为空（系统发现未就绪/间歇失败）时**不得清空** DnsSd TXT 已学设备——
                // 否则误清后 knownDevices 空 → 误触发 createGroup 兜底（双方各自独立 GO 永不互通）。
                if (peers.isNotEmpty()) {
                    val addrs = peers.map { it.deviceAddress }
                    knownDevices.entries.removeIf { (addr, _) -> addr !in addrs }
                    peers.forEach { dev -> knownDevices[dev.deviceAddress] = dev }
                }
                if (knownDevices.isEmpty()) {
                    // v1.1.85 移除自动 createGroup 兜底：createGroup 成 GO 后系统停止 discovery，
                    // 会截断仍在进行的设备发现（10s 内发现未完成即触发 → 双方各自成独立 GO 永不互通，
                    // 且 12s 超时循环让 UI 反复"已连接→重连中"抖动）。改为持续搜索，直到发现对端走 connect。
                    wlog("no peers yet, keep searching (round=$idleRounds)")
                    idleRounds++
                } else {
                    idleRounds = 0
                    // v1.1.85 按 deviceAddress 直连：跳过自己/正在连接/已建 TCP；身份由 TCP 后 HELLO 互换确认。
                    // 排除自己用 MAC+设备名双判据（requestPeers 的 deviceList 可能包含本机；requestDeviceInfo 异步可能未回）。
                    knownDevices.forEach { (addr, dev) ->
                        val isSelf = (localDeviceAddress != null && addr == localDeviceAddress) ||
                            (localDeviceName != null && dev.deviceName == localDeviceName)
                        if (!isSelf && addr !in pendingConnects) scheduleConnect(addr, dev)
                    }
                }
            }
            delay(DISCOVER_TIMEOUT_MS)
        }
    }

    /**
     * v1.1.85 阻塞获取 P2P 设备列表：requestPeers 回调走主线程（Looper.getMainLooper），
     * 本函数在 IO 协程中 CountDownLatch 等待（≤2s），返回实时列表。回调永不触发时返回空表（防挂死）。
     */
    private suspend fun requestPeersBlocking(): List<WifiP2pDevice> {
        val mgr = manager ?: return emptyList()
        val ch = channel ?: return emptyList()
        val latch = CountDownLatch(1)
        var list = emptyList<WifiP2pDevice>()
        runCatching { mgr.requestPeers(ch) { peers -> list = peers.deviceList.toList(); latch.countDown() } }
            .onFailure { return emptyList() }
        latch.await(2, TimeUnit.SECONDS)
        return list
    }

    /**
     * v1.1.85 调度 connect：MAC 小者立即 connect（先发者主导 GO negotiation），大者错开 CONNECT_STAGGER_MS 再 connect
     * （此时先发者 negotiation 已完成，后发者作为 join 方加入）——避免双方同时 connect 触发系统 P2P 冲突（reason=2）。
     */
    private fun scheduleConnect(peerAddress: String, dev: WifiP2pDevice) {
        if (state != State.DISCOVERING) return
        if (peerAddress in pendingConnects) return
        // v1.1.85 连续失败退避中（环境异常/目标是自己）：跳过本轮，不刷 connect 失败风暴
        if ((connectFailures[peerAddress] ?: 0) >= MAX_CONNECT_FAILURES) return
        pendingConnects.add(peerAddress)
        val mine = localDeviceAddress
        // v1.1.88 边缘偏好优先：preferGroupOwner（BLE 失联中）者立即 connect 主导建组，其余按 MAC 字典序错开
        val delayMs = if (preferGroupOwner || (mine != null && mine < peerAddress)) 0L else CONNECT_STAGGER_MS
        scope.launch {
            delay(delayMs)
            if (state != State.DISCOVERING) { pendingConnects.remove(peerAddress); return@launch }
            connectNow(peerAddress, dev)
        }
    }

    private fun connectNow(peerAddress: String, dev: WifiP2pDevice) {
        state = State.CONNECTING
        val config = WifiP2pConfig().apply {
            deviceAddress = dev.deviceAddress
            // v1.1.84 确定性选主：本机 P2P MAC 小者 groupOwnerIntent 高 → 更可能成 GO，
            // 双方用同一规则（MAC 字典序）避免 GO negotiation 冲突（DnsSd 拿不到 shortId 时改用 MAC）。
            // v1.1.88 GO 边缘偏好：BLE 失联中的边缘节点 intent=15 抢先当 GO，中心节点作为 join 方加入。
            val mine = localDeviceAddress
            groupOwnerIntent = if (preferGroupOwner || (mine != null && mine < peerAddress)) 15 else 0
        }
        manager?.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                wlog("connect initiated $peerAddress")
                connectFailures.remove(peerAddress)
            }

            override fun onFailure(reason: Int) {
                wwlog("connect fail $peerAddress reason=$reason")
                pendingConnects.remove(peerAddress)
                val fails = (connectFailures[peerAddress] ?: 0) + 1
                connectFailures[peerAddress] = fails
                if (fails >= MAX_CONNECT_FAILURES) {
                    // v1.1.85 连续失败（环境异常/目标是自己）：退避 60s 再允许重试，防失败风暴刷屏/污染 P2P 栈
                    wwlog("connect to $peerAddress failed $fails times, backing off 60s")
                    scope.launch { delay(CONNECT_BACKOFF_MS); connectFailures.remove(peerAddress) }
                    state = State.DISCOVERING
                    return
                }
                // v1.1.85 失败退避：先进 RECONNECTING 停 3s 再回 DISCOVERING，降低 discover/connect 频率——
                // 模拟器 P2P 并发 discover+connect 频繁失败（reason=2），高频重试反而互相喂失败风暴
                state = State.RECONNECTING
                scope.launch {
                    delay(RECONNECT_BACKOFF_MS)
                    if (state == State.RECONNECTING) state = State.DISCOVERING
                }
            }
        })
    }

    private fun handleConnectionChange(info: WifiP2pInfo?) {
        val formed = info?.groupFormed == true
        val ownerAddr = info?.groupOwnerAddress?.hostAddress
        wlog("connection change formed=$formed go=${info?.isGroupOwner} owner=$ownerAddr sockets=${sockets.size}")
        if (formed) {
            state = State.GROUPED
            groupOwnerAddress = ownerAddr
            isGroupOwner = info?.isGroupOwner == true
            if (isGroupOwner) {
                // GO：等待 client TCP 连入（startTcpServer accept 循环已就绪，身份由 REGISTER 识别）
                wlog("became GO, waiting for client tcp")
            } else {
                // Client：主动连接 GO 的 TCP 并宣告身份（幂等：connectTcpTo 内部有进行中守卫）
                scope.launch { connectTcpTo(ownerAddr) }
            }
        } else {
            if (state == State.DISABLED) return
            // v1.1.86 防抖动：系统在 GO 协商/信道切换/扫描期间常发 groupFormed=false 伪事件。
            // 若 TCP 套接字仍存活 → 组实际未断，忽略该广播，绝不用 discover/connect 打断既有组
            // （v1.1.85 的 removeGroup + 重发现正是"连上→几十秒→重连中→搜索→又连上"循环的元凶）。
            if (sockets.isNotEmpty()) {
                wlog("group formed=false but ${sockets.size} tcp alive, ignore")
                return
            }
            // v1.1.85 group 解散：清空 pending connect（允许对已见设备立即重连）
            pendingConnects.clear()
            state = State.RECONNECTING   // 短暂停留后自动回 DISCOVERING 重试（防状态机卡死在 RECONNECTING 永不重连）
            scope.launch {
                delay(RECONNECT_BACKOFF_MS)
                if (state == State.RECONNECTING) {
                    state = State.DISCOVERING
                    wlog("back to discovering for reconnect")
                }
            }
        }
    }

    /** GO 侧 TCP 服务：0.0.0.0:TCP_PORT 监听 client 连入。v1.1.86 accept 循环异常/退出后自动重新绑定继续服务
     *  （组重建后 GO 仍需能接受新 client；一次性监听循环若退出则 GO 永远收不到 TCP 连接）。 */
    private fun startTcpServer() {
        scope.launch {
            while (scope.isActive && state != State.DISABLED) {
                val server = runCatching { ServerSocket(TCP_PORT) }.getOrNull()
                    ?: run { wwlog("tcp listen failed, retry in 5s"); delay(5_000); continue }
                serverSocket = server
                while (scope.isActive && state != State.DISABLED) {
                    val socket = runCatching { server.accept() }.getOrNull() ?: break
                    tcpByIp[socket.inetAddress.hostAddress] = socket to Any()   // 身份待 REGISTER 识别
                    scope.launch { readLoop(socket) }
                }
                runCatching { server.close() }
                serverSocket = null
                wlog("tcp accept loop ended, rebind")
            }
        }
    }

    private suspend fun connectTcpTo(host: String?, maxAttempts: Int = Int.MAX_VALUE) {
        if (tcpConnecting) return
        tcpConnecting = true
        try {
            // v1.1.86 标准 GO IP 兜底：Android P2P 固定子网，部分 ROM 的 groupOwnerAddress 为 null
            val go = host?.takeIf { it.isNotBlank() } ?: DEFAULT_GO_IP
            /**
             * v1.1.86 关键：**不再因 TCP 失败 removeGroup**。v1.1.85 在 5×(5s 超时+2s 退避)≈35s 全部失败后
             * 主动解散组——这正是"连上几十秒→突然重连中→搜索→又连上"循环的直接引擎（每次组建立后约 35s
             * 被本机亲手拆掉）。组是稀缺资源，TCP 失败只是瞬态（GO accept 未就绪/接口未起/信道切换），
             * 组存活期内无限重试（退避封顶 10s）；组解散时循环自然退出，交给 handleConnectionChange 重发现。
             * maxAttempts：socket 断线触发的有限重试（默认组建立时无限）。
             */
            var attempt = 0
            while (scope.isActive && state == State.GROUPED && attempt < maxAttempts) {
                val s = runCatching {
                    val sock = Socket()
                    sock.tcpNoDelay = true
                    sock.keepAlive = true
                    sock.connect(InetSocketAddress(InetAddress.getByName(go), TCP_PORT), TCP_CONNECT_TIMEOUT_MS)
                    sock
                }.getOrNull()
                if (s != null) {
                    // Client 连入后立即向 GO 宣告身份（REGISTER 包装为 HELLO 帧）；写失败 = socket 即刻坏，继续重试
                    val hello = MeshFrame(
                        FrameType.HELLO,
                        WifiDirectFraming.encodeRegister(shortId, localGroupIp() ?: go, TCP_PORT, displayName),
                    )
                    val wrote = runCatching { RfcommFraming.writeFrame(s.getOutputStream(), hello) }.isSuccess
                    if (!wrote) {
                        wwlog("tcp hello write failed, close and retry")
                        runCatching { s.close() }
                        attempt++
                        continue
                    }
                    wlog("tcp established to GO $go (attempt ${attempt + 1})")
                    tcpByIp[s.inetAddress.hostAddress] = s to Any()
                    scope.launch { readLoop(s) }
                    return
                }
                attempt++
                wwlog("tcp connect fail $go attempt=$attempt (group stays, keep retrying)")
                delay(minOf(10_000L, 2_000L * attempt))
            }
            // 有限重试耗尽（socket 断线触发路径）仍失败：回全量重发现（组可能已坏/GO 已不可达）
            if (state == State.GROUPED) {
                wwlog("tcp retry to GO exhausted, fall back to rediscovery")
                pendingConnects.clear()
                state = State.RECONNECTING
                scope.launch {
                    delay(RECONNECT_BACKOFF_MS)
                    if (state == State.RECONNECTING) {
                        state = State.DISCOVERING
                        wlog("back to discovering after tcp retry exhausted")
                    }
                }
            }
        } finally {
            tcpConnecting = false
        }
    }

    /** 组内 IP：GO = groupOwnerAddress；Client = 本机 192.168.49.x 网卡地址。 */
    private fun localGroupIp(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence().flatMap { it.inetAddresses.asSequence() }
            .firstOrNull { it is Inet4Address && it.hostAddress?.startsWith("192.168.49.") == true }?.hostAddress
    }.getOrNull()

    /** TCP 读循环：HELLO（REGISTER 身份注册）帧映射 shortId；其余帧上抛 incoming。 */
    private suspend fun readLoop(socket: Socket) {
        val input = runCatching { socket.getInputStream() }.getOrNull() ?: return
        val remoteKey = socket.inetAddress.hostAddress
        while (scope.isActive) {
            val frame = runCatching { RfcommFraming.readFrame(input) }.getOrNull() ?: break
            if (frame.type == FrameType.HELLO) {
                val reg = WifiDirectFraming.decodeRegister(frame.payload)
                if (reg != null && reg.shortId != shortId) {
                    // 首次识别（tcpByIp 命中）：建立映射 + 回发本机身份（对方据此认识本机）
                    val pending = tcpByIp.remove(remoteKey)
                    if (pending != null) {
                        sockets[reg.shortId] = pending
                        _foundPeers.tryEmit(
                            MeshPeerInfo(shortId = reg.shortId, deviceAddress = reg.ip, rssi = 0,
                                displayName = reg.name, presence = PeerPresence.ONLINE),
                        )
                        wlog("tcp peer identified: ${reg.shortId} ip=${reg.ip}")
                        sendTo(reg.shortId, MeshFrame(
                            FrameType.HELLO,
                            WifiDirectFraming.encodeRegister(
                                shortId, groupOwnerAddress ?: localGroupIp() ?: "", TCP_PORT, displayName),
                        ))
                    } else {
                        // 重复注册：仅确保映射存在，不回发（防双方无限互发身份帧）
                        sockets[reg.shortId] = sockets[reg.shortId] ?: (socket to Any())
                    }
                }
            } else {
                _incoming.emit(frame)
            }
        }
        runCatching { socket.close() }
        tcpByIp.remove(remoteKey)
        val removed = sockets.entries.removeIf { it.value.first == socket }
        wlog("socket closed remote=$remoteKey removed=$removed")
        // v1.1.86 以 TCP 存活为准驱动重连：最后一个已识别 socket 断开 → 组可能仍存活（P2P 广播可能误报已解散），
        // 原地有限重连 TCP（不打断组，最多 3 次 ≈ ≤20s）；失败或无 GO 地址才回全量重发现。
        if (removed && sockets.isEmpty() && state == State.GROUPED) {
            val go = groupOwnerAddress
            if (go != null) {
                wlog("last tcp closed, retry tcp to GO $go (group stays)")
                scope.launch { connectTcpTo(go, maxAttempts = 3) }
            } else {
                pendingConnects.clear()
                state = State.RECONNECTING
                scope.launch {
                    delay(RECONNECT_BACKOFF_MS)
                    if (state == State.RECONNECTING) {
                        state = State.DISCOVERING
                        wlog("back to discovering after socket loss")
                    }
                }
            }
        }
    }

    /** v1.1.86 TCP 保活：组内无业务流量时周期性向所有已识别 socket 写 HELLO 身份帧——
     * ① 探测半开写通路（死 socket 的写最终抛异常 → 清理 → 触发重连）
     * ② 保持组活跃，防 ROM 因长时间无流量丢组。
     * 重复注册（readLoop 对已知 peer 的 HELLO 只补映射不回发）→ 无 ping-pong 风暴，带宽可忽略。 */
    private suspend fun tcpKeepaliveLoop() {
        while (scope.isActive) {
            delay(TCP_KEEPALIVE_MS)
            if (state != State.GROUPED || sockets.isEmpty()) continue
            val frame = MeshFrame(
                FrameType.HELLO,
                WifiDirectFraming.encodeRegister(
                    shortId, groupOwnerAddress ?: localGroupIp() ?: "", TCP_PORT, displayName),
            )
            sockets.keys.toList().forEach { peerId ->
                val pair = sockets[peerId] ?: return@forEach
                try {
                    synchronized(pair.second) { RfcommFraming.writeFrame(pair.first.getOutputStream(), frame) }
                } catch (e: Exception) {
                    wwlog("tcp keepalive write failed $peerId: $e")
                    sockets.remove(peerId, pair)
                    runCatching { pair.first.close() }
                }
            }
        }
    }

    private fun requestPeers() {
        manager?.requestPeers(channel) { peers ->
            // DnsSd TXT 回调已维护 knownDevices；此处仅留作状态同步扩展（P2 完善）
            peers.deviceList.forEach { dev -> Log.d(TAG, "peer in list: ${dev.deviceName} ${dev.deviceAddress}") }
        }
    }
}
