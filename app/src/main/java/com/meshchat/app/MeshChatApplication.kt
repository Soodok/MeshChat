package com.meshchat.app

import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import com.meshchat.app.mesh.identity.LocalIdentity
import com.meshchat.app.mesh.identity.ShortIdGen
import com.meshchat.app.mesh.routing.DedupCache
import com.meshchat.app.mesh.service.MeshChatService
import com.meshchat.app.mesh.service.MeshService
import com.meshchat.app.mesh.service.NotificationHelper
import com.meshchat.app.mesh.service.SharedPrefsSessionStore
import com.meshchat.app.mesh.storage.MeshDatabase
import com.meshchat.app.mesh.storage.RoomMeshStore
import com.meshchat.app.mesh.transfer.AndroidFileSaver
import com.meshchat.app.mesh.transport.BleTransport
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MeshChatApplication : Application() {
    private val bluetoothManager: BluetoothManager by lazy {
        getSystemService(BluetoothManager::class.java)
    }

    val store by lazy { RoomMeshStore(MeshDatabase.build(this)) }

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

    val transport by lazy { BleTransport(this, advertiseShortId = identity.shortId) }
    val service by lazy {
        val notifications = NotificationHelper(this)
        val svc = MeshService(
            transport, store, identity, DedupCache(),
            fileSaver = AndroidFileSaver(this),
            tmpDir = { File(filesDir, "transfers") },
            sessionStore = SharedPrefsSessionStore(this),
            onIncomingMessage = { fromId, fromName, text ->
                // 会话键 = conv-<发送者短ID>（接收方统一命名，见 toStoredMessage）
                notifications.showMessage(fromName, text.take(80), "conv-$fromId")
            },
            onFileSaved = { fileName -> notifications.showFileSaved(fileName) },
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
    }

    override fun onCreate() {
        super.onCreate()
        // 进程一启动即开始扫描/心跳（"删掉后台再进"也自动寻找）：
        // 此刻 MainActivity 尚未创建、Android 12+ 限制前台服务后台启动，故直接启动 Mesh 逻辑本体（幂等），
        // 前台服务由 MainActivity onCreate/onResume 的 startMesh() 补上（App 已在前台，无启动限制）。
        runCatching { service.start() }
    }
}
