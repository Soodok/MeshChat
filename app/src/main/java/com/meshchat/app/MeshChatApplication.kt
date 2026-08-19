package com.meshchat.app

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.meshchat.app.mesh.crypto.AndroidE2eeKeyStore
import com.meshchat.app.mesh.identity.LocalIdentity
import com.meshchat.app.mesh.identity.ShortIdGen
import com.meshchat.app.mesh.routing.DedupCache
import com.meshchat.app.mesh.service.MeshChatService
import com.meshchat.app.mesh.service.MeshService
import com.meshchat.app.mesh.service.NotificationHelper
import com.meshchat.app.mesh.service.SharedPrefsBlockedStore
import com.meshchat.app.mesh.service.SharedPrefsGroupStore
import com.meshchat.app.mesh.service.SharedPrefsSessionStore
import com.meshchat.app.mesh.service.SharedPrefsPeerKeyStore
import com.meshchat.app.mesh.storage.EncryptedMeshStore
import com.meshchat.app.mesh.storage.MeshDatabase
import com.meshchat.app.mesh.storage.RoomMeshStore
import com.meshchat.app.mesh.transfer.AndroidFileSaver
import com.meshchat.app.mesh.transport.BleTransport
import com.meshchat.app.mesh.transport.PeerPresence
import com.meshchat.app.mesh.wifidirect.WifiDirectTransport
import com.meshchat.app.security.capability.AndroidSecurityCapabilityStateReader
import com.meshchat.app.security.capability.SecurityCapabilityManager
import com.meshchat.app.security.capability.SharedPreferencesCapabilityPromptStore
import com.meshchat.app.security.local.AndroidLocalSecuritySignalCollector
import com.meshchat.app.security.local.LocalSecurityCoordinator
import com.meshchat.app.security.risk.EncryptedSecurityEventStore
import com.meshchat.app.security.risk.SecurityRiskEngine
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** v1.1.87 蓝牙全失联持续该时长 → 自动启用 WFD 备份通道（用户：蓝牙不稳时 WFD 顶上）。 */
private const val WFD_AUTO_ENABLE_AFTER_MS = 5_000L
/** v1.1.87 蓝牙失联看门狗轮询周期。 */
private const val WFD_AUTO_WATCH_INTERVAL_MS = 2_000L

class MeshChatApplication : Application() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val bluetoothManager: BluetoothManager by lazy {
        getSystemService(BluetoothManager::class.java)
    }
    /** v1.1.87 蓝牙全失联起始时刻（0 = 未失联）：看门狗据此判定"失联持续 >5s"。 */
    @Volatile private var bleLostSince = 0L

    /** 消息存储：落库加密装饰器（v1.1.24）——正文/文件元数据/outbox 信封 AES-GCM + Keystore，Room schema 不动。 */
    val store by lazy { EncryptedMeshStore(RoomMeshStore(MeshDatabase.build(this)), this) }

    /** 权限/能力状态集中管理；只读系统状态，绝不在应用启动时弹出可选安全能力的授权框。 */
    val securityCapabilityManager by lazy {
        SecurityCapabilityManager(
            stateReader = AndroidSecurityCapabilityStateReader(this),
            promptStore = SharedPreferencesCapabilityPromptStore(this),
        )
    }

    /** Local-first security pipeline; it has no network, VPN, or cloud-service dependency. */
    val localSecurityCoordinator by lazy {
        LocalSecurityCoordinator(
            signalCollector = AndroidLocalSecuritySignalCollector(this),
            riskEngine = SecurityRiskEngine(),
            eventStore = EncryptedSecurityEventStore(this),
        )
    }

    /** 本机身份：短 ID 持久化存储，重启后保持同一 ID（否则会话/路由随重启失效）。 */
    val identity by lazy {
        val prefs = getSharedPreferences("meshchat_identity", Context.MODE_PRIVATE)
        val stored = prefs.getString("short_id", null)
        val id = stored ?: ShortIdGen.generate().also {
            prefs.edit().putString("short_id", it).apply()
        }
        val name = prefs.getString("display_name", null)
        LocalIdentity(shortId = id, displayName = name ?: "节点$id")
    }

    /** 本机昵称：设置页可改，持久化，随 PING 广播给邻近节点。 */
    var displayName: String
        get() = identity.displayName
        set(value) {
            identity.displayName = value.trim().ifEmpty { "节点${identity.shortId}" }
            getSharedPreferences("meshchat_identity", Context.MODE_PRIVATE)
                .edit().putString("display_name", identity.displayName).apply()
        }

    /** 后台常驻开关（设置页可改）：关闭时 App 前台才运行 Mesh 服务。 */
    var backgroundEnabled: Boolean
        get() = getSharedPreferences("meshchat_settings", Context.MODE_PRIVATE)
            .getBoolean("background_enabled", true)
        set(value) {
            getSharedPreferences("meshchat_settings", Context.MODE_PRIVATE)
                .edit().putBoolean("background_enabled", value).apply()
        }

    /**
     * 打开应用时自动搜索开关（v1.1.49，设置页可改，默认开）：
     * 关闭后 App 启动/回前台不自动开始蓝牙广播+扫描（但服务/心跳/已建立连接照常），
     * 用户可在 Mesh 页用搜索开关手动开启。
     */
    var autoDiscovery: Boolean
        get() = getSharedPreferences("meshchat_settings", Context.MODE_PRIVATE)
            .getBoolean("auto_discovery", true)
        set(value) {
            getSharedPreferences("meshchat_settings", Context.MODE_PRIVATE)
                .edit().putBoolean("auto_discovery", value).apply()
        }

    /** 调试统计内核（真机调试中心数据源；内存态，重启清零）。 */
    val debugStats by lazy { com.meshchat.app.mesh.debug.DebugStats() }
    /** v1.1.58 应用锁：密码/指纹解锁 + DEK 保护敏感密钥库。 */
    val appLock by lazy { com.meshchat.app.security.lock.AppLockManager(this) }

    /**
     * v1.1.64 静默模式偏好（持久化）：开启后重启/蓝牙重建仍保持静默（陌生人扫不到本机）。
     * 纯偏好读写，不直接动服务；生效由 applyDiscoveryMode 在启动/蓝牙重建时下发。
     */
    var silentMode: Boolean
        get() = getSharedPreferences("meshchat_settings", Context.MODE_PRIVATE)
            .getBoolean("silent_mode", false)
        set(value) {
            getSharedPreferences("meshchat_settings", Context.MODE_PRIVATE)
                .edit().putBoolean("silent_mode", value).apply()
        }

    /**
     * v1.1.66 当前频道名偏好（null = 公共频道）：重启恢复频道隔离。
     * 纯偏好读写，不直接动服务；生效由 applyChannel 在启动/蓝牙重建时下发。
     */
    var channelName: String?
        get() = getSharedPreferences("meshchat_settings", Context.MODE_PRIVATE)
            .getString("channel_name", null)
        set(value) {
            getSharedPreferences("meshchat_settings", Context.MODE_PRIVATE)
                .edit().putString("channel_name", value?.trim()?.takeIf { it.isNotEmpty() }).apply()
        }

    /** Wi-Fi Direct 增强层（Beta v1.1.51，独立目录 mesh/wifidirect）：开启后自动与可达设备建连形成星域。 */
    val wfd by lazy {
        WifiDirectTransport(this, shortId = identity.shortId, displayName = identity.displayName)
    }

    /**
     * Wi-Fi Direct 增强开关（设置页可改，**v1.1.87 默认开——用户决策"WFD 默认保持连接状态，作为蓝牙不稳时的
     * 备份通道 + 传文件高速通道"；v1.1.51~86 默认关省电）。首次启动迁移：老用户 prefs 已存 false 的补写为 true，
     * 否则改默认值对存量安装不生效。
     */
    var wifiDirectEnabled: Boolean
        get() {
            val prefs = getSharedPreferences("meshchat_settings", Context.MODE_PRIVATE)
            // v1.1.87 首次运行迁移：默认开启
            if (!prefs.contains("wifi_direct_enabled")) prefs.edit().putBoolean("wifi_direct_enabled", true).apply()
            return prefs.getBoolean("wifi_direct_enabled", true)
        }
        set(value) {
            getSharedPreferences("meshchat_settings", Context.MODE_PRIVATE)
                .edit().putBoolean("wifi_direct_enabled", value).apply()
            if (value) wfd.enable() else wfd.disable()
        }

    val transport by lazy { BleTransport(this, advertiseShortId = identity.shortId, debugStats = debugStats) }
    val service by lazy {
        val notifications = NotificationHelper(this) { appLock.locked.value }   // v1.1.58 锁定态通知隐藏内容
        val svc = MeshService(
            transport, store, identity, DedupCache(),
            fileSaver = AndroidFileSaver(this),
            tmpDir = { File(filesDir, "transfers") },
            wfd = wfd,   // v1.1.84：Wi-Fi Direct 星域高速通道接入消息路由（单播帧最高优先）
            sessionStore = SharedPrefsSessionStore(this),
            groupStore = SharedPrefsGroupStore(this),   // v1.1.50：群订阅/群名持久化
            blockedStore = SharedPrefsBlockedStore(this),   // v1.1.64：删除对话=拉黑持久化
            peerKeyStore = SharedPrefsPeerKeyStore(this),   // v1.1.74：对端公钥指纹持久化（MITM 防御 TOFU）
            e2eeStore = AndroidE2eeKeyStore(this) { appLock.dek() },   // v1.1.57 E2EE 密钥；v1.1.58 设密码后 DEK 加密存储
            onIncomingMessage = { fromId, fromName, text, convId ->
                // v1.1.50：convId = 群会话键（group-<id>）或点对点 conv-<fromId>，通知点击直达对应会话
                notifications.showMessage(fromName, text.take(80), convId)
            },
            onFileSaved = { fileName -> notifications.showFileSaved(fileName) },
            debugStats = debugStats,
        )
        // 广播确认键注入：BleTransport 扫描响应携带本机已收消息键，对端扫描即确认送达（无需 GATT 连接）
        transport.setAckProvider { svc.broadcastAckKeys() }
        svc
    }

    /** 通知点击携带的会话请求（convId），MainActivity 写入、ViewModel 订阅打开会话。 */
    private val _conversationRequest = MutableStateFlow<String?>(null)
    val conversationRequest: StateFlow<String?> = _conversationRequest.asStateFlow()

    fun requestConversation(convId: String) {
        _conversationRequest.value = convId
    }

    /** 本机蓝牙名称（用于界面展示本设备蓝牙信息）；无权限/异常时返回 null 而非崩溃。 */
    val localBluetoothName: String? get() = runCatching { bluetoothManager.adapter?.name }.getOrNull()

    /** 本机蓝牙 MAC 地址（界面展示用）；无权限/异常时返回 null 而非崩溃。 */
    val localBluetoothAddress: String? get() = runCatching { bluetoothManager.adapter?.address }.getOrNull()

    /** 启动 Mesh 服务：后台常驻开启时走前台服务（息屏/后台继续收发），否则前台直跑。 */
    fun startMesh() {
        if (backgroundEnabled) {
            startForegroundService(Intent(this, MeshChatService::class.java))
        } else {
            service.start()
        }
        // v1.1.49：打开应用时自动搜索关闭 → 启动后不自动广播+扫描（服务/心跳/已建立连接照常）
        applyAutoDiscovery()
        // v1.1.66：启动恢复频道（公共/私人，与发现模式正交）
        applyChannel()
        // v1.1.85：启动恢复 Wi-Fi Direct 星域（选项开启后搜索始终自动，无需每次手动开关）
        applyWifiDirect()
    }

    /** v1.1.85 按偏好恢复 Wi-Fi Direct 星域：选项开启 → enable（幂等，已在跑则跳过）；WiFi 未开则 DISABLED 等待。 */
    private fun applyWifiDirect() {
        if (wifiDirectEnabled) wfd.enable()
    }

    /**
     * v1.1.87 蓝牙失联看门狗（用户："WFD 作为蓝牙连接不稳定时启用"，默认保持连接 + 双链路并行搜索恢复）：
     * 已会话节点全部失联（peers 流无 ONLINE 会话节点）持续 5s → 若 WFD 未运行则 wfd.enable()。
     * **临时启用不改偏好**——手动关闭 WFD 偏好的用户，蓝牙失联时仍能获得备份通道；BLE 恢复（出现在线节点）即清零。
     * WFD 建组需系统弹窗人手确认一次（Android Settings 进程，App 无权免弹）。
     */
    private val wfdAutoWatchdog = object : Runnable {
        override fun run() {
            runCatching {
                val online = service.peers.value.any {
                    it.shortId in service.sessions.value && it.presence == PeerPresence.ONLINE
                }
                val now = System.currentTimeMillis()
                if (!online) {
                    if (bleLostSince == 0L) {
                        bleLostSince = now
                        // v1.1.88 GO 边缘偏好：BLE 失联中的本机倾向当 GO 主导建组（中心节点按 MAC 规则 join 加入）
                        wfd.setPreferGroupOwner(true)
                    }
                    if (now - bleLostSince >= WFD_AUTO_ENABLE_AFTER_MS &&
                        wfd.state == WifiDirectTransport.State.DISABLED
                    ) {
                        Log.w("MeshApp", "BLE all-lost >5s, auto-enable Wi-Fi Direct backup channel")
                        wfd.enable()
                        bleLostSince = now   // 防高频重复 enable（enable 幂等，此处防每轮重试）
                    }
                } else {
                    bleLostSince = 0L
                    // v1.1.88 BLE 恢复 → 撤 GO 边缘偏好，回到 MAC 确定性选主
                    wfd.setPreferGroupOwner(false)
                }
            }.onFailure { Log.w("MeshApp", "wfd watchdog iteration failed", it) }
            mainHandler.postDelayed(this, WFD_AUTO_WATCH_INTERVAL_MS)
        }
    }

    /**
     * 按设置同步发现层（v1.1.64 含静默恢复）：
     * autoDiscovery 关 → CLOSED（广播+扫描全停）；静默开 → SILENT（只停广播）；否则 NORMAL。
     * 启动（onCreate）/蓝牙重开重建后调用——此前静默模式不持久化，重启/蓝牙重建后广播被重新打开
     * （用户实测"静默模式下陌生人仍可搜到"）。
     */
    private fun applyAutoDiscovery() {
        val mode = when {
            !autoDiscovery -> com.meshchat.app.mesh.transport.DiscoveryMode.CLOSED
            silentMode -> com.meshchat.app.mesh.transport.DiscoveryMode.SILENT
            else -> com.meshchat.app.mesh.transport.DiscoveryMode.NORMAL
        }
        service.setDiscoveryMode(mode)
    }

    /** v1.1.66 启动恢复频道：读偏好 → service.setChannel（幂等，与发现模式正交）。 */
    private fun applyChannel() {
        service.setChannel(channelName)
    }

    override fun onCreate() {
        super.onCreate()
        registerBluetoothStateReceiver()
        // v1.1.87 蓝牙失联看门狗：全在线节点消失持续 5s → 自动启用 WFD 备份通道（双链路并行恢复）
        mainHandler.post(wfdAutoWatchdog)
        // 进程一启动即开始扫描/心跳（"删掉后台再进"也自动寻找）：
        // 此刻 MainActivity 尚未创建、Android 12+ 限制前台服务后台启动，故直接启动 Mesh 逻辑本体（幂等），
        // 前台服务由 MainActivity onCreate/onResume 的 startMesh() 补上（App 已在前台，无启动限制）。
        runCatching { service.start() }
        applyAutoDiscovery()
        // v1.1.66：进程启动即恢复频道（重启后保持频道隔离）
        applyChannel()
        // v1.1.85：进程启动即恢复 Wi-Fi Direct 星域（重启后选项开启则自动搜索）
        applyWifiDirect()
    }

    /**
     * 蓝牙关→开自动重建 BLE 传输层（v1.0.24）：
     *
     * Android 关闭蓝牙时会杀掉本 App 注册的广播/扫描/GATT 连接，且**蓝牙重开后不会自动恢复**；
     * 此时 `service.started` 仍为 true，没有任何代码再调 startAdvertising/startScanning——本机
     * "听不见"任何帧：对端显示在线/已送达（保留显示 + 无限重发）但消息实际收不到，本机也无法重连。
     *
     * 监听系统蓝牙状态广播，蓝牙重新开启后延迟 500ms 强制 `transport.stop()+start()` 重建
     * （等蓝牙栈完全就绪，立即重建时 advertiser/scanner 可能尚未初始化完）。
     */
    private fun registerBluetoothStateReceiver() {
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                if (state == BluetoothAdapter.STATE_ON) {
                    Log.i("MeshApp", "bluetooth ON -> rebuild BLE transport (restartDiscovery)")
                    mainHandler.postDelayed({
                        runCatching { service.restartDiscovery() }
                        // v1.1.49：自动搜索关闭时，重建链路后仍保持"不广播+不扫描"（GATT/心跳照常）
                        applyAutoDiscovery()
                        // v1.1.66：蓝牙重建后恢复频道（广播指纹重新生效）
                        applyChannel()
                    }, 500L)
                }
            }
        }
        // 系统受保护广播可投递给 NOT_EXPORTED 动态接收器（不接收其他 App 的任意广播）
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }
}
