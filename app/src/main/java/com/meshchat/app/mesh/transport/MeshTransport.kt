package com.meshchat.app.mesh.transport

import com.meshchat.app.mesh.protocol.MeshFrame
import kotlinx.coroutines.flow.SharedFlow

/** 传输层发现的邻近节点信息。 */
data class MeshPeerInfo(
    val shortId: String,
    val deviceAddress: String,
    val rssi: Int,
    val hops: Int = 1,
    val lost: Boolean = false,
)

interface MeshTransport {
    val incoming: SharedFlow<MeshFrame>
    val foundPeers: SharedFlow<MeshPeerInfo>

    fun start()
    fun stop()
    fun broadcast(frame: MeshFrame)
    fun sendTo(peerId: String, frame: MeshFrame)
}
