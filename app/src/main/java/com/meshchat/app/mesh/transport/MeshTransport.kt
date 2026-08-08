package com.meshchat.app.mesh.transport

import com.meshchat.app.mesh.protocol.MeshFrame
import kotlinx.coroutines.flow.SharedFlow

/**
 * 节点在线状态（v1.1.55 四态模型：在线绿 / 无响应琥珀 / 寻找中·重连中黄 / 离线黑）。
 * - ONLINE：最近收到协议帧（PING/PONG/TEXT）→ 应用层活跃。
 * - UNRESPONSIVE：协议帧失联但广播（advertise）仍可见——对方蓝牙栈活着但应用层无响应
 *   （后台冻结/进程被杀未恢复/单向链路断），"显示连上但消息送不到"的诚实标注。
 * - SEARCHING/RECONNECTING/OFFLINE：原有失联分级。
 */
enum class PeerPresence { ONLINE, UNRESPONSIVE, SEARCHING, RECONNECTING, OFFLINE }

/**
 * 发现模式（v1.1.53，用户最终设计）：
 * - NORMAL：广播+扫描全开——可被发现、可发现别人、自动连接。
 * - CLOSED：停广播+停扫描（内部态，供"打开应用时自动搜索=关"启动使用；保留已建立连接与保活）。
 * - SILENT（静默模式）：**只停广播（陌生人扫不到你）**——scan/自动连接/已建立连接与保活全部照常：
 *   可以连接陌生人并建立关系、可以继续连接联系人（关系人经 GATT 保活感知你在线），仅广播域不可见。
 */
enum class DiscoveryMode { NORMAL, CLOSED, SILENT }

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
    /** 经中继可达的经由节点 shortId（v1.1.0）；空 = 一跳直连。路由表合成的 2 跳节点此字段非空。 */
    val relayVia: String = "",
    /** 对端广播发射功率(dBm)；广播包未带 TX power 字段时 = Int.MIN_VALUE（未知）。 */
    val txPower: Int = Int.MIN_VALUE,
    /** 链路信号强度(0-1) = 从对端收到 PONG 的速率 ÷ 本机 PING 发送速率（v1.1.17，协议层双向质量，替代 RSSI）；-1 = 样本不足。 */
    val signalRatio: Double = -1.0,
    /** 对端所属频道指纹（v1.1.66）；0 = 公共频道/未知/老版本设备。发现层已按本机频道过滤，同频道节点才可见。 */
    val channelFingerprint: Long = 0,
)

interface MeshTransport {
    val incoming: SharedFlow<MeshFrame>
    val foundPeers: SharedFlow<MeshPeerInfo>

    fun start()
    fun stop()
    fun broadcast(frame: MeshFrame)
    fun sendTo(peerId: String, frame: MeshFrame)

    /**
     * 无确认写（v1.1.27，仅文件数据块用）：GATT WRITE_NO_RESPONSE，不等待对端应答 → 突破确认写往返
     * （30 write/s）瓶颈。丢帧由应用层窗口重传兜底。默认等同 broadcast（可靠），实现方覆盖。
     */
    fun writeUnreliable(frame: MeshFrame) { broadcast(frame) }

    /** 注入"本机已收到消息的确认键"提供器（MeshService 提供），供广播扫描响应携带。默认空实现（内存/测试替身不关心）。 */
    fun setAckProvider(provider: () -> List<ByteArray>) {}

    /** 广播数据变化（收到新消息）后刷新，让对端尽快从扫描读到确认键。默认空实现。 */
    fun refreshAdvertising() {}

    /** 蓝牙开关状态（调试中心快照用；默认 false，实现覆盖）。 */
    fun bluetoothEnabled(): Boolean = false

    /** 设置广播发射功率(dBm，仅限 -21/-15/-7/1 四档)；广播更新有频率限制，需重启广播生效。默认空实现（内存/测试替身）。 */
    fun setTxPowerLevel(power: Int) {}

    /** 暂停发现层（广播+扫描）；默认无操作，BleTransport 覆写。 */
    fun suspendDiscovery() = Unit

    /** 恢复发现层（广播+扫描）；默认无操作，BleTransport 覆写。 */
    fun resumeDiscovery() = Unit

    /**
     * 下发发现模式（v1.1.53）：单独开关广播（可被发现）/扫描（发现他人）+ 断开全部连接。
     * 默认空实现（内存/测试替身），BleTransport 覆写。
     */
    fun applyDiscoveryMode(mode: DiscoveryMode) = Unit

    /**
     * 当前协商的 GATT MTU 字节数（BleTransport 覆写，onMtuChanged 更新）；-1 = 未知/非 BLE 载体。
     * 文件传输引擎据此动态计算块大小，保证帧 ≤ MTU-3 载荷，避免硬编码大块在 MTU 协商不足的真机上写失败。
     */
    fun currentMtu(): Int = -1

    /**
     * 与对端（shortId）是否有活跃 GATT 连接（v1.1.38 文件传输无上限重试的停止条件）：
     * **central 与 server 侧连接都算**（聊天双通道：本机写对端 / 对端连入后 notify 回传）。
     * 重连/重发次数不设限（零容错），仅对端完全断开（无任何 GATT 连接）才停止。
     * 默认 true（内存/测试替身假定始终连接）。
     */
    fun isConnectedTo(peerId: String): Boolean = true

    /**
     * v1.1.66 设置本机频道指纹（0 = 公共频道）：BleTransport 覆写更新广播携带的指纹并重启广播生效；
     * InMemoryTransport 覆写记录断言位供测试。
     */
    fun setChannel(fingerprint: Long) {}
}
