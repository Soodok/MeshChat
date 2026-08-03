package com.meshchat.app.mesh.transport

import com.meshchat.app.mesh.protocol.MeshFrame
import kotlinx.coroutines.flow.SharedFlow

/** 节点在线状态（三色模型：在线绿 / 寻找中·重连中黄 / 离线黑）。 */
enum class PeerPresence { ONLINE, SEARCHING, RECONNECTING, OFFLINE }

/** 传输层发现的邻近节点信息。 */
data class MeshPeerInfo(
    val shortId: String,
    val deviceAddress: String,
    val rssi: Int,
    val hops: Int = 1,
    val lost: Boolean = false,
    val displayName: String = "",
    val presence: PeerPresence = PeerPresence.ONLINE,
)

interface MeshTransport {
    val incoming: SharedFlow<MeshFrame>
    val foundPeers: SharedFlow<MeshPeerInfo>

    fun start()
    fun stop()
    fun broadcast(frame: MeshFrame)
    fun sendTo(peerId: String, frame: MeshFrame)
}
