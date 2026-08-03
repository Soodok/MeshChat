# MeshChat RFCOMM 高吞吐传输载体规格（v0.13.0）

> 日期：2026-08-03
> 版本：v0.13.0
> 前置：v0.12.0（文件传输功能链路已通，但 BLE 吞吐 ~几 KB/s，用户实测"很慢/卡顿"）

## 1. 背景与目标

用户反馈文件传输太慢。根因：BLE 硬件上限（实际 10-50 KB/s，我们的保守实现下更慢）。
安卓系统蓝牙分享用 OPP/OBEX over 经典蓝牙 RFCOMM（100-300 KB/s），但直接调系统分享会绕过 MeshChat 的协议/会话/加密体系且进度不可控。

**目标**：MeshChat 自实现 **RFCOMM 传输载体**（经典蓝牙），复用现有 `MeshTransport` 抽象/协议栈/会话/ACK/文件重组，吞吐提升 10-50 倍，保持自包含与可控。

**架构**：`RfcommTransport` 与 `BleTransport` 共存——BLE 负责发现/握手/聊天（低功耗），RFCOMM 负责文件数据（高吞吐）。文件帧优先走 RFCOMM，无连接时回退 BLE。

## 2. 现状（复用已有）

| 项 | 现状 |
|---|---|
| `MeshTransport` 接口 | `incoming/foundPeers/start/stop/broadcast/sendTo` 已定义；`sendTo(peerId, frame)` 目前**空实现**（BleTransport）——RFCOMM 在这里实现点对点写入 |
| `MeshPeerInfo` | 已有 `deviceAddress` 字段——BLE 扫描的 `device.address` 即经典蓝牙 MAC（Android 两者同一 MAC），`peerIds[address]=shortId` 映射已维护 |
| 协议栈 | MeshFrame 编码/解码、MeshService 路由、FileTransferManager 全部可复用，只换数据通道 |
| 权限 | `BLUETOOTH_CONNECT`（API 31+）/ `BLUETOOTH`（API≤30）已在 Manifest，经典蓝牙可用；无需新权限 |

## 3. RfcommTransport 设计（新 `mesh/transport/RfcommTransport.kt`）

### 3.1 接口实现

```kotlin
class RfcommTransport(
    private val context: Context,
    private val serviceName: String = "MeshChat",
    private val sdpUuid: UUID = UUID.fromString("0000A5E3-0000-1000-8000-00805F9B34FB"),
) : MeshTransport {
    override val incoming: SharedFlow<MeshFrame>   // 所有 socket 读到的帧合流（Mutex 保护）
    override val foundPeers: SharedFlow<MeshPeerInfo>  // RFCOMM 不主动发现，保持空（BLE 负责发现）

    override fun start()        // 服务端 listen + accept 循环
    override fun stop()         // 关闭全部 socket / server socket
    override fun broadcast(frame: MeshFrame)  // 写所有已连接 socket（等同 sendTo 全量）
    override fun sendTo(peerId: String, frame: MeshFrame)  // peerId → address → socket 写入

    /** 客户端主动连接（会话建立后由 MeshService 调用）：address 为经典蓝牙 MAC。 */
    fun connect(address: String): Boolean
    fun isConnectedTo(peerId: String): Boolean
}
```

### 3.2 连接与配对

- **服务端**：`bluetoothAdapter.listenUsingRfcommWithServiceRecord(serviceName, sdpUuid)` → `BluetoothServerSocket`；协程循环 `accept()` → 每连接起读循环 → `incoming` 发射帧
- **客户端** `connect(address)`：
  1. `adapter.getRemoteDevice(address)`；若 `bondState != BONDED` → `createBond()` + 注册 `ACTION_BOND_STATE_CHANGED` 广播等待（超时 15s）
  2. `device.createRfcommSocketToServiceRecord(sdpUuid)` → `connect()`（阻塞，放 Dispatchers.IO，超时 ~10s）
  3. 成功后建立 peerId↔socket 映射（peerId 由 MeshService 传入——connect 时带 shortId 参数）
  4. 起读循环
- **配对弹窗**：Android 11+ 系统配对 UI（BLUETOOTH_CONNECT 已授权），首次连接需用户确认

### 3.3 流分帧（可单测核心）

RFCOMM 是字节流，必须自定帧边界：

```kotlin
/** 帧写入：4 字节大端长度前缀 + 帧字节。 */
fun writeFrame(out: OutputStream, frame: MeshFrame) {
    val bytes = frame.encode()
    out.write(byteArrayOf((bytes.size ushr 24).toByte(), (bytes.size ushr 16).toByte(),
        (bytes.size ushr 8).toByte(), bytes.size.toByte()))
    out.write(bytes)
    out.flush()
}

/** 帧读取：先读 4 字节长度，再读满该长度的字节，解析 MeshFrame。 */
fun readFrame(input: InputStream): MeshFrame? {
    val lenBytes = ByteArray(4)
    if (readFully(input, lenBytes) != 4) return null
    val len = (lenBytes[0].toInt() and 0xFF shl 24) or (lenBytes[1].toInt() and 0xFF shl 16) or
        (lenBytes[2].toInt() and 0xFF shl 8) or (lenBytes[3].toInt() and 0xFF)
    if (len <= 0 || len > 1024 * 1024) return null
    val payload = ByteArray(len)
    if (readFully(input, payload) != len) return null
    return MeshFrame.decode(payload)
}
```

`readFully` 循环读满（InputStream.read 不保证读满）。

### 3.4 IO 与并发

- 全部 socket IO 在 `Dispatchers.IO` 协程（每连接一个读循环）
- 写操作按 socket 加锁（多协程广播/发送并发写同一 socket 会交错字节）
- `incoming` 用 `MutableSharedFlow(extraBufferCapacity = 64)`（与 BleTransport 一致）
- peerId → socket 映射：`ConcurrentHashMap<String, Socket>` + 地址反向映射

## 4. MeshService 集成

- 构造新增 `rfcomm: RfcommTransport? = null`（默认 null 保持向后兼容，测试/无经典蓝牙环境不启用）
- `start()`：`rfcomm?.start()`；`stop()`：`rfcomm?.stop()`
- **incoming 合并**：`rfcomm?.incoming` 与 `transport.incoming` 两个 collector 都走 `handleFrame`
- **连接触发**：会话建立（INVITE_ACK 首次建立 session）后，从 `peers` 找对端 `deviceAddress` → `rfcomm.connect(shortId, address)`（协程，失败静默——文件回退 BLE 仍可传）
- **帧发送路由**（文件传输核心）：

```kotlin
private fun sendFrame(dstId: String, frame: MeshFrame) {
    if (rfcomm?.isConnectedTo(dstId) == true) rfcomm.sendTo(dstId, frame)
    else transport.broadcast(frame)
}
```

`FileTransferManager` 构造新增注入 `sendFrame: (dstId: String, frame: MeshFrame) -> Unit = { _, f -> transport.broadcast(f) }`，发送端广播窗口/重发块全部改走 `sendFrame(s.dstId, frame)`；`sendAck` 同理。

## 5. 测试策略

- **分帧**（JVM 可测）：`FrameStream` 用本地 `PipedInputStream/PipedOutputStream` 或 loopback Socket 对——写入多帧（含超长/短帧）→ 读出逐帧还原；长度前缀字节序正确；流断开返回 null
- **sendTo 路由**：RfcommTransport 的 peerId→socket 映射（注入 fake socket）——已连接的 peer 写对应流，未连接静默/回退
- **MeshService**：注入假 rfcomm（连接态）→ sendFrame 走 sendTo 而非 broadcast（CountingTransport 断言）
- 现有 34 测试不回归；真机验收：会话建立后自动 RFCOMM 连接，传 20KB MD 秒级完成

## 6. 权限

- 无新权限。经典蓝牙由已有 `BLUETOOTH_CONNECT`（API 31+）/ `BLUETOOTH`+`BLUETOOTH_ADMIN`（API≤30，Manifest 已声明）覆盖

## 7. 边界情况

- **配对拒绝/超时**：connect 返回 false，MeshService 不再重试（本次会话）；文件回退 BLE
- **socket 中途断开**：读循环异常 → 移除映射 → 下次 sendFrame 回退 broadcast；会话重连时重新 connect
- **经典蓝牙未开启**：`adapter` 为 null 或 `!isEnabled` → RfcommTransport 不启动（start 内 runCatching 降级）
- **多设备**：每 peer 一个 socket；broadcast 写全部
- **帧超长防御**：读帧长度 > 1MB 视为损坏流，断开该连接

## 8. 范围外（不做）

- RFCOMM 取代 BLE（发现/握手/聊天仍走 BLE，混合保留低功耗场景）
- 聊天消息走 RFCOMM（当前只文件数据走高速通道，聊天保持 BLE）
- 经典蓝牙自动发现（发现仍用 BLE 扫描，RFCOMM 只做已发现节点的点对点连接）
- 传输加密（现有 enc 字段占位，后续整体接入）
