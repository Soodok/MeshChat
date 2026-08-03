package com.meshchat.app

import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import com.meshchat.app.mesh.identity.LocalIdentity
import com.meshchat.app.mesh.identity.ShortIdGen
import com.meshchat.app.mesh.routing.DedupCache
import com.meshchat.app.mesh.service.MeshService
import com.meshchat.app.mesh.storage.MeshDatabase
import com.meshchat.app.mesh.storage.RoomMeshStore
import com.meshchat.app.mesh.transfer.AndroidFileSaver
import com.meshchat.app.mesh.transport.BleTransport
import java.io.File

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
    val transport by lazy { BleTransport(this, advertiseShortId = identity.shortId) }
    val service by lazy {
        MeshService(
            transport, store, identity, DedupCache(),
            fileSaver = AndroidFileSaver(this),
            tmpDir = { File(filesDir, "transfers") },
            // rfcomm 不装配：配对弹窗依赖系统 UI 不可靠，且对多设备中心连接拓扑不友好（用户决策停用，代码保留）
        )
    }

    /** 本机蓝牙名称（用于界面展示本设备蓝牙信息）；无权限/异常时返回 null 而非崩溃。 */
    val localBluetoothName: String? get() = runCatching { bluetoothManager.adapter?.name }.getOrNull()

    /** 本机蓝牙 MAC 地址（界面展示用）；无权限/异常时返回 null 而非崩溃。 */
    val localBluetoothAddress: String? get() = runCatching { bluetoothManager.adapter?.address }.getOrNull()

    /** 在 BLE 运行时权限就绪后调用，启动广播/扫描/GATT 服务。 */
    fun startMesh() {
        service.start()
    }
}
