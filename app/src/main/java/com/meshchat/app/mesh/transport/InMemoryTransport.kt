package com.meshchat.app.mesh.transport

import com.meshchat.app.mesh.protocol.MeshFrame
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** 测试替身：broadcast 回环到自身 incoming（replay 保留帧供断言），用于单机自环验证。 */
class InMemoryTransport : MeshTransport {
    private val _incoming = MutableSharedFlow<MeshFrame>(replay = 32, extraBufferCapacity = 64)
    override val incoming: SharedFlow<MeshFrame> = _incoming

    // Keep the latest discovery event so tests and newly started consumers do not lose a peer
    // merely because their collector has not been scheduled yet.
    private val _foundPeers = MutableSharedFlow<MeshPeerInfo>(replay = 1, extraBufferCapacity = 16)
    override val foundPeers: SharedFlow<MeshPeerInfo> = _foundPeers

    override fun start() = Unit
    override fun stop() = Unit
    override fun broadcast(frame: MeshFrame) {
        _incoming.tryEmit(frame)
    }
    override fun sendTo(peerId: String, frame: MeshFrame) {
        _incoming.tryEmit(frame)
    }
    /** 测试替身：无确认写 = 普通广播（回环到 incoming 供断言）。 */
    override fun writeUnreliable(frame: MeshFrame) {
        _incoming.tryEmit(frame)
    }

    /** 测试辅助：模拟扫描发现节点（可携带广播确认键）。 */
    fun emitPeer(info: MeshPeerInfo) {
        _foundPeers.tryEmit(info)
    }

    /** 发现层暂停标志（suspendSignaling/resumeSignaling 测试断言用）。 */
    @Volatile
    var discoverySuspended = false

    /** 最近一次广播功率档（setTxPowerLevel 测试断言用；默认 1dBm HIGH）。 */
    @Volatile
    var lastTxPowerLevel = 1

    /** 最近一次下发的发现模式（applyDiscoveryMode 测试断言用；默认 NORMAL）。 */
    @Volatile
    var lastDiscoveryMode = DiscoveryMode.NORMAL

    /** v1.1.66 最近一次下发的频道指纹（setChannel 测试断言用；默认 0 = 公共频道）。 */
    @Volatile
    var lastChannelFingerprint = 0L

    override fun suspendDiscovery() {
        discoverySuspended = true
    }

    override fun resumeDiscovery() {
        discoverySuspended = false
    }

    override fun applyDiscoveryMode(mode: DiscoveryMode) {
        lastDiscoveryMode = mode
    }

    override fun setTxPowerLevel(power: Int) {
        lastTxPowerLevel = power
    }

    override fun setChannel(fingerprint: Long) {
        lastChannelFingerprint = fingerprint
    }
}
