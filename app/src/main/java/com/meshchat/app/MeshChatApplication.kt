package com.meshchat.app

import android.app.Application
import com.meshchat.app.mesh.identity.LocalIdentity
import com.meshchat.app.mesh.routing.DedupCache
import com.meshchat.app.mesh.service.MeshService
import com.meshchat.app.mesh.storage.MeshDatabase
import com.meshchat.app.mesh.storage.RoomMeshStore
import com.meshchat.app.mesh.transport.BleTransport

class MeshChatApplication : Application() {
    val store by lazy { RoomMeshStore(MeshDatabase.build(this)) }
    val identity by lazy { LocalIdentity() }
    val transport by lazy { BleTransport(this, advertiseShortId = identity.shortId) }
    val service by lazy { MeshService(transport, store, identity, DedupCache()) }

    /** 在 BLE 运行时权限就绪后调用，启动广播/扫描/GATT 服务。 */
    fun startMesh() {
        service.start()
    }
}
