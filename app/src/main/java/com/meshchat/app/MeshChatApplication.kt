package com.meshchat.app

import android.app.Application
import android.bluetooth.BluetoothManager
import com.meshchat.app.mesh.identity.LocalIdentity
import com.meshchat.app.mesh.routing.DedupCache
import com.meshchat.app.mesh.service.MeshService
import com.meshchat.app.mesh.storage.MeshDatabase
import com.meshchat.app.mesh.storage.RoomMeshStore
import com.meshchat.app.mesh.transport.BleTransport

class MeshChatApplication : Application() {
    private val bluetoothManager: BluetoothManager by lazy {
        getSystemService(BluetoothManager::class.java)
    }

    val store by lazy { RoomMeshStore(MeshDatabase.build(this)) }
    val identity by lazy { LocalIdentity() }
    val transport by lazy { BleTransport(this, advertiseShortId = identity.shortId) }
    val service by lazy { MeshService(transport, store, identity, DedupCache()) }

    /** 本机蓝牙名称（用于界面展示本设备蓝牙信息）；无权限/异常时返回 null 而非崩溃。 */
    val localBluetoothName: String? get() = runCatching { bluetoothManager.adapter?.name }.getOrNull()

    /** 本机蓝牙 MAC 地址（界面展示用）；无权限/异常时返回 null 而非崩溃。 */
    val localBluetoothAddress: String? get() = runCatching { bluetoothManager.adapter?.address }.getOrNull()

    /** 在 BLE 运行时权限就绪后调用，启动广播/扫描/GATT 服务。 */
    fun startMesh() {
        service.start()
    }
}
