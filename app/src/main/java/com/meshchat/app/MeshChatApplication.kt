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
import com.meshchat.app.mesh.identity.LocalIdentity
import com.meshchat.app.mesh.identity.ShortIdGen
import com.meshchat.app.mesh.routing.DedupCache
import com.meshchat.app.mesh.service.MeshChatService
import com.meshchat.app.mesh.service.MeshService
import com.meshchat.app.mesh.service.NotificationHelper
import com.meshchat.app.mesh.service.SharedPrefsGroupStore
import com.meshchat.app.mesh.service.SharedPrefsSessionStore
import com.meshchat.app.mesh.storage.EncryptedMeshStore
import com.meshchat.app.mesh.storage.MeshDatabase
import com.meshchat.app.mesh.storage.RoomMeshStore
import com.meshchat.app.mesh.transfer.AndroidFileSaver
import com.meshchat.app.mesh.transport.BleTransport
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

class MeshChatApplication : Application() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val bluetoothManager: BluetoothManager by lazy {
        getSystemService(BluetoothManager::class.java)
    }

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

    val transport by lazy { BleTransport(this, advertiseShortId = identity.shortId, debugStats = debugStats) }
    val service by lazy {
        val notifications = NotificationHelper(this)
        val svc = MeshService(
            transport, store, identity, DedupCache(),
            fileSaver = AndroidFileSaver(this),
            tmpDir = { File(filesDir, "transfers") },
            sessionStore = SharedPrefsSessionStore(this),
            groupStore = SharedPrefsGroupStore(this),   // v1.1.50：群订阅/群名持久化
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
    }

    /** 按"打开应用时自动搜索"设置同步发现层：关闭 = 启动进入 CLOSED（广播+扫描全停，保留连接与保活）；开启 = NORMAL。 */
    private fun applyAutoDiscovery() {
        service.setDiscoveryMode(
            if (autoDiscovery) com.meshchat.app.mesh.transport.DiscoveryMode.NORMAL
            else com.meshchat.app.mesh.transport.DiscoveryMode.CLOSED,
        )
    }

    override fun onCreate() {
        super.onCreate()
        registerBluetoothStateReceiver()
        // 进程一启动即开始扫描/心跳（"删掉后台再进"也自动寻找）：
        // 此刻 MainActivity 尚未创建、Android 12+ 限制前台服务后台启动，故直接启动 Mesh 逻辑本体（幂等），
        // 前台服务由 MainActivity onCreate/onResume 的 startMesh() 补上（App 已在前台，无启动限制）。
        runCatching { service.start() }
        applyAutoDiscovery()
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
                    }, 500L)
                }
            }
        }
        // 系统受保护广播可投递给 NOT_EXPORTED 动态接收器（不接收其他 App 的任意广播）
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }
}
