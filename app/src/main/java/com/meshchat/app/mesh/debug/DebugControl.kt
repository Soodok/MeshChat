package com.meshchat.app.mesh.debug

/** 调试中心主动控制命令（经 DebugStats 控制总线转发到 MeshService 控制面；内存态，重启回默认）。 */
sealed class DebugControl {
    /** 心跳：PING 广播节流间隔（失联阈值固定保持默认 2s，不随心跳联动——用户决策）。 */
    data class SetHeartbeat(val intervalMs: Long) : DebugControl()
    /** 重发退避：消息未确认重发的基础间隔与封顶。 */
    data class SetResendPolicy(val baseMs: Long, val maxMs: Long) : DebugControl()
    /** 暂停广播+扫描（发现层；已建立 GATT 连接不受影响）。 */
    data object SuspendSignaling : DebugControl()
    /** 恢复广播+扫描。 */
    data object ResumeSignaling : DebugControl()
    /** 立即广播一轮 PING（链路探测）。 */
    data object BroadcastPing : DebugControl()
    /** 恢复全部默认。 */
    data object ResetControls : DebugControl()
}
