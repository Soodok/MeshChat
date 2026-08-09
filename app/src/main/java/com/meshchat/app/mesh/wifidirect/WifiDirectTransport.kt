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
 * P1 范围：一对一连接打通。P2 扩展多成员星域（UDP 组内广播/成员表/断开重建）见规格 §8。
 */
class WifiDirectTransport(
    private val context: Context,
    private val shortId: String,
    private val displayName: String = "",
) {
    companion object {
        private const val TAG = "MeshWfd"
        const val SERVICE_TYPE = "_meshchat._tcp"
        const val TCP_PORT = 0x51C8          // 20936：应用层固定端口
        private const val DISCOVER_TIMEOUT_MS = 15_000L
        private const val TCP_CONNECT_TIMEOUT_MS = 5_000
        /** RECONNECTING 停留时长：之后自动回 DISCOVERING 重试（防状态机卡死永不重连）。 */
        private const val RECONNECT_BACKOFF_MS = 3_000L
        /** 搜索连续无邻居的轮数阈值：达到后主动 createGroup 成为 GO，等待对端 join（DnsSd 发现失败时的兜底建连路径）。 */
        private const val CREATE_GROUP_AFTER_ROUNDS = 3
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
    val incoming: SharedFlow<MeshFrame> = _incoming
    private val _foundPeers = MutableSharedFlow<MeshPeerInfo>(extraBufferCapacity = 64)
    val foundPeers: SharedFlow<MeshPeerInfo> = _foundPeers

    /** 状态机（StateFlow 镜像：Mesh 页据此显示 Wi-Fi Direct 连接状态/WIFI 信号栏）。线程安全。 */
    private val _state = MutableStateFlow(State.DISABLED)
    var state: State
        get() = _state.value
        private set(value) { _state.value = value }
    val stateFlow: StateFlow<State> = _state.asStateFlow()

    /** 不可用原因（State 为 DISABLED 且增强开关开启时，Mesh 页据此精确提示）。 */
    private val _unavailableReason = MutableStateFlow(UnavailableReason.NONE)
    val unavailableReason: StateFlow<UnavailableReason> = _unavailableReason.asStateFlow()

    /** 已请求 createGroup（防重复创建）；disable/失败重置。 */
    @Volatile private var goRequested = false

    /** 服务发现填充：shortId ↔ P2P 设备。 */
    private val knownDevices = ConcurrentHashMap<String, WifiP2pDevice>()
    /** 已建立 TCP 的成员：shortId ↔ (socket, 写锁)。 */
    private val sockets = ConcurrentHashMap<String, Pair<Socket, Any>>()
    /** 待身份识别的 TCP 连接：远端 IP ↔ (socket, 写锁)（REGISTER 到达后映射到 shortId）。 */
    private val tcpByIp = ConcurrentHashMap<String, Pair<Socket, Any>>()
    private var serverSocket: ServerSocket? = null
    @Volatile private var groupOwnerAddress: String? = null
    @Volatile private var isGroupOwner = false

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
        scope.launch { discoveryLoop() }
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
        runCatching { serverSocket?.close() }
        serverSocket = null
        groupOwnerAddress = null
        goRequested = false
        wlog("disabled")
    }

    fun isGrouped(): Boolean = state == State.GROUPED

    /** 组内已建 TCP 的成员 shortId。 */
    fun members(): Set<String> = sockets.keys.toSet()

    fun isConnectedTo(peerId: String): Boolean = sockets.containsKey(peerId)

    /** 发往单成员（TCP 可靠，多协程并发写按 socket 加锁防交错）。 */
    fun sendTo(peerId: String, frame: MeshFrame) {
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
                    knownDevices[id] = srcDevice
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
                if (knownDevices.isEmpty()) {
                    idleRounds++
                    // 连续多轮无任何 P2P 邻居 → 主动 createGroup 成为 GO，等待对端 join（DnsSd 发现失败时的兜底建连）
                    if (idleRounds >= CREATE_GROUP_AFTER_ROUNDS && !goRequested) {
                        goRequested = true
                        manager?.createGroup(channel, object : WifiP2pManager.ActionListener {
                            override fun onSuccess() { wlog("created group as GO, waiting for peers to join") }
                            override fun onFailure(reason: Int) {
                                wwlog("createGroup fail reason=$reason")
                                goRequested = false   // 允许下一轮重试
                            }
                        })
                    }
                } else {
                    idleRounds = 0
                    // 对每个已知设备尝试连接（已建 TCP 跳过；多设备并发 connect 由系统 GO negotiation 合并成一个 group）
                    knownDevices.forEach { (id, dev) ->
                        if (id != shortId && !sockets.containsKey(id)) connectTo(id, dev)
                    }
                }
            }
            delay(DISCOVER_TIMEOUT_MS)
        }
    }

    private fun connectTo(peerId: String, dev: WifiP2pDevice) {
        if (state != State.DISCOVERING) return
        state = State.CONNECTING
        val config = WifiP2pConfig().apply {
            deviceAddress = dev.deviceAddress
            groupOwnerIntent = 8   // 适中倾向；P2 多成员自动选主可调
        }
        manager?.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { wlog("connect initiated $peerId") }
            override fun onFailure(reason: Int) {
                wwlog("connect fail $peerId reason=$reason")
                state = State.DISCOVERING   // 回退：下一轮重试
            }
        })
    }

    private fun handleConnectionChange(info: WifiP2pInfo?) {
        val formed = info?.groupFormed == true
        val ownerAddr = info?.groupOwnerAddress?.hostAddress
        wlog("connection change formed=$formed go=${info?.isGroupOwner} owner=$ownerAddr")
        if (formed) {
            state = State.GROUPED
            groupOwnerAddress = ownerAddr
            isGroupOwner = info?.isGroupOwner == true
            if (isGroupOwner) {
                // GO：等待 client TCP 连入（startTcpServer accept 循环已就绪，身份由 REGISTER 识别）
                wlog("became GO, waiting for client tcp")
            } else {
                // Client：主动连接 GO 的 TCP 并宣告身份
                scope.launch { connectTcpTo(ownerAddr) }
            }
        } else {
            if (state != State.DISABLED) {
                state = State.RECONNECTING   // 短暂停留后自动回 DISCOVERING 重试（防状态机卡死在 RECONNECTING 永不重连）
                scope.launch {
                    delay(RECONNECT_BACKOFF_MS)
                    if (state == State.RECONNECTING) {
                        goRequested = false
                        state = State.DISCOVERING
                        wlog("back to discovering for reconnect")
                    }
                }
            }
        }
    }

    private fun startTcpServer() {
        scope.launch {
            val server = runCatching { ServerSocket(TCP_PORT) }.getOrNull()
                ?: run { wwlog("tcp listen failed"); return@launch }
            serverSocket = server
            while (scope.isActive && state != State.DISABLED) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                tcpByIp[socket.inetAddress.hostAddress] = socket to Any()   // 身份待 REGISTER 识别
                scope.launch { readLoop(socket) }
            }
        }
    }

    private suspend fun connectTcpTo(host: String?) {
        if (host == null) return
        val socket = runCatching {
            val s = Socket()
            s.connect(InetSocketAddress(InetAddress.getByName(host), TCP_PORT), TCP_CONNECT_TIMEOUT_MS)
            s
        }.getOrNull() ?: run { wwlog("tcp connect fail $host"); return }
        // Client 连入后立即向 GO 宣告身份（REGISTER 包装为 HELLO 帧）
        val hello = MeshFrame(
            FrameType.HELLO,
            WifiDirectFraming.encodeRegister(shortId, localGroupIp() ?: host, TCP_PORT, displayName),
        )
        runCatching { RfcommFraming.writeFrame(socket.getOutputStream(), hello) }
        tcpByIp[socket.inetAddress.hostAddress] = socket to Any()
        scope.launch { readLoop(socket) }
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
        sockets.entries.removeIf { it.value.first == socket }
        wlog("socket closed remote=$remoteKey")
    }

    private fun requestPeers() {
        manager?.requestPeers(channel) { peers ->
            // DnsSd TXT 回调已维护 knownDevices；此处仅留作状态同步扩展（P2 完善）
            peers.deviceList.forEach { dev -> Log.d(TAG, "peer in list: ${dev.deviceName} ${dev.deviceAddress}") }
        }
    }
}
