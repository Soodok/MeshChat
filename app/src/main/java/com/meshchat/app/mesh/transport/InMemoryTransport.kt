package com.meshchat.app.mesh.transport

import com.meshchat.app.mesh.protocol.MeshFrame
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** 测试替身：broadcast 回环到自身 incoming（replay 保留帧供断言），用于单机自环验证。 */
class InMemoryTransport : MeshTransport {
    private val _incoming = MutableSharedFlow<MeshFrame>(replay = 32, extraBufferCapacity = 64)
    override val incoming: SharedFlow<MeshFrame> = _incoming

    override fun start() = Unit
    override fun stop() = Unit
    override fun broadcast(frame: MeshFrame) {
        _incoming.tryEmit(frame)
    }
    override fun sendTo(peerId: String, frame: MeshFrame) {
        _incoming.tryEmit(frame)
    }
}
