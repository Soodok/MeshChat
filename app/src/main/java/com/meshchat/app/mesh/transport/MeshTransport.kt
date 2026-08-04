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
    /** 对端随广播（扫描响应）携带的送达确认键：本机已收到消息的压缩标识——扫描即可读到，无需 GATT 连接。 */
    val ackKeys: List<ByteArray> = emptyList(),
    /** 最后收到对端任何帧的时刻（ms）：帧到达即刷新，UI 据此显示"X 秒前信号"，远距离断连可直观感知。 */
    val lastSeenAt: Long = 0,
)

interface MeshTransport {
    val incoming: SharedFlow<MeshFrame>
    val foundPeers: SharedFlow<MeshPeerInfo>

    fun start()
    fun stop()
    fun broadcast(frame: MeshFrame)
    fun sendTo(peerId: String, frame: MeshFrame)

    /** 注入"本机已收到消息的确认键"提供器（MeshService 提供），供广播扫描响应携带。默认空实现（内存/测试替身不关心）。 */
    fun setAckProvider(provider: () -> List<ByteArray>) {}

    /** 广播数据变化（收到新消息）后刷新，让对端尽快从扫描读到确认键。默认空实现。 */
    fun refreshAdvertising() {}
}
