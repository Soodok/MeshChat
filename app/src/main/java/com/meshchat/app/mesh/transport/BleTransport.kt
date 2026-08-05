package com.meshchat.app.mesh.transport

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import com.meshchat.app.mesh.debug.DebugLogBuffer
import com.meshchat.app.mesh.debug.DebugStats
import com.meshchat.app.mesh.protocol.MeshFrame
import java.util.UUID
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** 蓝牙载体实现：广播通告（Service Data 携带短 ID）+ 扫描发现（按 Service Data 识别）+ GATT 服务端/客户端 + 帧收发。 */
class BleTransport(
    private val context: Context,
    private val serviceUuid: UUID = UUID.fromString("0000A5E1-0000-1000-8000-00805F9B34FB"),
    private val charUuid: UUID = UUID.fromString("0000A5E2-0000-1000-8000-00805F9B34FB"),
    private val advertiseShortId: String = "0000",
    /** 调试统计内核（默认独立实例，生产由 Application 注入共享单例）。 */
    private val debugStats: DebugStats = DebugStats(),
) : MeshTransport {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val _incoming = MutableSharedFlow<MeshFrame>(extraBufferCapacity = 64)
    override val incoming: SharedFlow<MeshFrame> = _incoming

    /** 当前广播发射功率档(dBm)：默认 +1dBm HIGH（可经调试中心调节，重启广播生效）。 */
    @Volatile
    private var txPowerDbm: Int = AdvertiseSettings.ADVERTISE_TX_POWER_HIGH

    /**
     * 当前协商的 GATT MTU（v1.1.36）：onMtuChanged 成功时更新，默认 23。
     * 文件传输引擎按 currentMtu() 动态算块大小；写帧前检查 payload ≤ mtu-3，不足排队等 MTU（防 MTU 未协商完成即写大帧失败）。
     */
    @Volatile
    private var negotiatedMtu = 23

    /** v1.1.42 写失败容错状态：address -> 连续写失败次数（成功清零，≥5 触发连接重建自愈）。 */
    private val failStreak = HashMap<String, Int>()
    /** v1.1.42 补写重试退避指数：address -> 当前退避档（200ms×2^n，封顶 2s）。 */
    private val flushBackoff = HashMap<String, Int>()
    /** v1.1.42 write FAILED 日志限频（3s 内只打一条，防重试刷屏）。 */
    @Volatile
    private var lastWriteFailLogAt = 0L

    /**
     * v1.1.43 per-connection MTU：client 侧每条 GATT 连接的协商 MTU（不同对端设备 MTU 能力不同——
     * 对端只支持 247/185 等小 MTU 时，230B+ 帧写它必"大小不接受"）。onMtuChanged 更新，断开移除。
     */
    private val gattMtu = HashMap<BluetoothGatt, Int>()
    /** v1.1.43 server 侧 per-device MTU：notify 载荷受对端 central 连接的 MTU 限制，超限 notify 必失败（回执/PONG 丢失）。 */
    private val serverMtu = HashMap<String, Int>()
    /** v1.1.43 超限帧跳过日志限频（5s 一条，防刷屏）。 */
    @Volatile
    private var lastOversizeLogAt = 0L

    private val _foundPeers = MutableSharedFlow<MeshPeerInfo>(extraBufferCapacity = 64)
    override val foundPeers: SharedFlow<MeshPeerInfo> = _foundPeers

    private companion object {
        const val TAG = "MeshBle"
        const val MAX_DISCOVER_RETRIES = 3
        const val MAX_SERVICE_ADD_RETRIES = 5
        const val PENDING_FRAME_TIMEOUT_MS = 30_000L
        const val CONNECT_RETRY_COOLDOWN_MS = 5_000L
        const val DISCOVER_TIMEOUT_MS = 5_000L
        /** 写失败连续次数阈值（v1.1.42）：连接显示 CONNECTED 但写持续失败 → 写通道疑似坏死，强制重建连接自愈。 */
        const val MAX_WRITE_FAIL_STREAK = 5
        /** 客户端特征配置描述符（CCCD）标准 UUID，用于订阅 notify。 */
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
        /** 扫描响应携带的送达确认键 Service Data UUID：对端扫描即读到，无需 GATT 连接。 */
        val ACK_UUID: UUID = UUID.fromString("0000A5E3-0000-1000-8000-00805F9B34FB")
    }

    /** 送达确认键提供器（MeshService 注入：本机已收到消息的压缩键，最新优先，最多 6 个）。 */
    private var ackProvider: () -> List<ByteArray> = { emptyList() }

    override fun setAckProvider(provider: () -> List<ByteArray>) {
        ackProvider = provider
    }

    /** 收到新消息后刷新广播：确认键变化，让对端尽快从扫描读到（广播更新有频率限制，短延迟后重启）。 */
    override fun refreshAdvertising() {
        val advertiser = bluetoothAdapter?.bluetoothLeAdvertiser ?: return
        mainHandler.post {
            runCatching { advertiser.stopAdvertising(advertiseCallback) }
            mainHandler.postDelayed({ startAdvertising() }, 100L)
        }
    }

    // GATT Server：暴露服务，接收邻近节点写入的帧
    private var gattServer: BluetoothGattServer? = null
    private var serviceAddAttempts = 0
    /** 已连接（作为 server 被连入）的设备：address -> device；用于 notify 回传。 */
    private val serverDevices = HashMap<String, BluetoothDevice>()
    /** 已订阅 notify 的 server 连接设备地址。 */
    private val subscribedDevices = HashSet<String>()
    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            Log.d(TAG, "service added status=$status (${if (status == BluetoothGatt.GATT_SUCCESS) "OK" else "FAIL"})")
            if (status != BluetoothGatt.GATT_SUCCESS) addServiceWithRetry(service)
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            Log.d(TAG, "server conn[${device.address}] newState=$newState status=$status")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                debugStats.recordGattConnectSuccess()
                serverDevices[device.address] = device
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                debugStats.recordGattDisconnect()
                serverDevices.remove(device.address)
                subscribedDevices.remove(device.address)
                serverMtu.remove(device.address)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            val enabled = value != null && value.isNotEmpty() && value[0].toInt() != 0
            if (enabled) {
                subscribedDevices.add(device.address)
                Log.d(TAG, "subscribed[${device.address}]")
            } else {
                subscribedDevices.remove(device.address)
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            Log.d(TAG, "server mtu[${device.address}] mtu=$mtu")
            DebugLogBuffer.log(TAG, "server mtu[${device.address}] mtu=$mtu")
            // v1.1.43：记录 server 侧该对端连接的 MTU（notify 载荷校验用）
            serverMtu[device.address] = mtu
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            debugStats.recordWriteRequestReceived()
            Log.d(TAG, "write request from ${device.address} (${value?.size ?: 0}B)")
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            // 关键修复：收到对端写帧即视为"可 notify 回传"——收到帧本身证明链路活着。
            // 对端进程被杀后重连时，CCCD 订阅写入经常丢失（server 端 subscribedDevices 为空），
            // 回执/PONG 的 notify 会被静默丢弃，导致重启方永远收不到送达确认（直到二次重启补订阅）。
            // 此处无条件登记，保证回执沿"刚收到消息的链路"反向发回，不依赖 CCCD 是否写成功。
            serverDevices[device.address] = device
            subscribedDevices.add(device.address)
            if (value != null) runCatching { MeshFrame.decode(value) }.onSuccess { _incoming.tryEmit(it) }
        }
    }

    // 客户端连接（待转发时按 peerId 建立连接）
    private val gattClients = HashMap<String, BluetoothGatt>()
    private val peerIds = HashMap<String, String>() // deviceAddress -> peerId
    private val connectAttempts = HashMap<String, Long>() // deviceAddress -> 上次连接尝试时间（失败冷却）
    // 待服务发现后补写的帧：address -> (入队时间戳, 帧)；超时未补写则丢弃，防止永久滞留
    private val pendingFrames = HashMap<String, MutableList<Pair<Long, MeshFrame>>>()

    override fun start() {
        Log.d(TAG, "start: shortId=$advertiseShortId")
        runCatching { registerServer() }
            .onSuccess { Log.d(TAG, "gatt server registered") }
            .onFailure { Log.e(TAG, "registerServer failed: $it") }
        runCatching { startAdvertising() }
            .onSuccess { Log.d(TAG, "advertising started") }
            .onFailure { Log.e(TAG, "startAdvertising failed: $it") }
        runCatching { startScanning() }
            .onSuccess { Log.d(TAG, "scanning started") }
            .onFailure { Log.e(TAG, "startScanning failed: $it") }
    }

    override fun stop() {
        gattServer?.close()
        gattServer = null
        serverDevices.clear()
        subscribedDevices.clear()
        gattClients.values.forEach { it.close() }
        gattClients.clear()
        connectAttempts.clear()
        pendingFrames.clear()
        bluetoothAdapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
    }

    override fun broadcast(frame: MeshFrame) {
        debugStats.recordBleBroadcast(frame.payload.size)
        writeToConnectedClients(frame)   // 通道1：本机 central → 已连接的对端（写特征，可靠确认写）
        notifySubscribers(frame)          // 通道2：已连入本机的对端（server 连接，notify 回传）
    }

    /** 无确认写（v1.1.27，仅文件数据块）：GATT WRITE_NO_RESPONSE 不等待应答 → 突破确认写往返（~30/s）瓶颈。 */
    override fun writeUnreliable(frame: MeshFrame) {
        debugStats.recordBleBroadcast(frame.payload.size)
        writeToConnectedClients(frame, unreliable = true)
        notifySubscribers(frame)
    }

    override fun sendTo(peerId: String, frame: MeshFrame) { /* 按 peerId 解析地址后写入 */ }

    override fun bluetoothEnabled(): Boolean =
        runCatching { bluetoothAdapter?.isEnabled == true }.getOrDefault(false)

    /** 当前协商 GATT MTU（文件传输引擎动态块大小依据）。 */
    override fun currentMtu(): Int = negotiatedMtu

    /**
     * 对端是否有活跃 GATT 连接（v1.1.39 修正）：**central 与 server 侧连接都算**——
     * 聊天/传输实际靠双通道（本机 central 写对端 + 对端连入本机后 notify 回传），
     * 本机 GATT server 服务可有可无（"聊天已把 gatt 服务扔了"），且对端可能只以 server 侧连入。
     * 只查 central 连接表会误杀仅 notify 通道可达的场景 → 文件传输错误停止。
     * 用 BluetoothManager.getConnectionState（全局，不分角色）判定：任一条 GATT 连接存活即可传。
     */
    override fun isConnectedTo(peerId: String): Boolean {
        val address = peerIds.entries.firstOrNull { it.value == peerId }?.key ?: return false
        return runCatching {
            val device = bluetoothAdapter?.getRemoteDevice(address) ?: return false
            bluetoothManager.getConnectionState(device, BluetoothProfile.GATT) == BluetoothProfile.STATE_CONNECTED
        }.getOrDefault(false)
    }

    /** 调试控制：设置广播发射功率(dBm，仅限四档)——重启广播生效（广播更新有频率限制）。 */
    override fun setTxPowerLevel(power: Int) {
        if (power != AdvertiseSettings.ADVERTISE_TX_POWER_ULTRA_LOW &&
            power != AdvertiseSettings.ADVERTISE_TX_POWER_LOW &&
            power != AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM &&
            power != AdvertiseSettings.ADVERTISE_TX_POWER_HIGH
        ) return
        txPowerDbm = power
        Log.d(TAG, "setTxPowerLevel: ${power}dBm")
        refreshAdvertising()
    }

    /** 调试控制：暂停发现层——只停广播+扫描，保留 GATT server/clients 与已建立连接收发。 */
    override fun suspendDiscovery() {
        Log.d(TAG, "suspendDiscovery: stop advertising + scanning")
        runCatching { bluetoothAdapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) }
        runCatching { bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback) }
    }

    /** 调试控制：恢复发现层——重新广播+扫描（与 start() 幂等互不干扰）。 */
    override fun resumeDiscovery() {
        Log.d(TAG, "resumeDiscovery: restart advertising + scanning")
        runCatching { startAdvertising() }
        runCatching { startScanning() }
    }

    private fun registerServer() {
        gattServer = bluetoothManager.openGattServer(context, gattServerCallback)
        val service = BluetoothGattService(serviceUuid, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val characteristic = BluetoothGattCharacteristic(
            charUuid,
            // WRITE_NO_RESPONSE 属性（v1.1.27）：文件数据块走无确认写突破往返瓶颈；老版本对端无此属性时客户端回退确认写
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        // CCCD：对端 central 订阅 notify，server 即可向其回传帧（不依赖本机主动连接）
        characteristic.addDescriptor(
            BluetoothGattDescriptor(
                UUID.fromString("00002902-0000-1000-8000-00805F9B34FB"),
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
            ),
        )
        service.addCharacteristic(characteristic)
        addServiceWithRetry(service)
    }

    /** 注册 GATT 服务，失败自动重试（部分 ROM/GSI 首次 addService 会失败）。 */
    private fun addServiceWithRetry(service: BluetoothGattService) {
        if (serviceAddAttempts >= MAX_SERVICE_ADD_RETRIES) {
            Log.e(TAG, "addService giving up after $serviceAddAttempts attempts")
            return
        }
        serviceAddAttempts++
        val ok = runCatching { gattServer?.addService(service) ?: false }.getOrDefault(false)
        if (!ok) {
            Log.w(TAG, "addService returned false (attempt #$serviceAddAttempts), retrying in 500ms")
            mainHandler.postDelayed({ addServiceWithRetry(service) }, 500L)
        }
    }

    private fun startAdvertising() {
        val advertiser = bluetoothAdapter?.bluetoothLeAdvertiser ?: return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(txPowerDbm)
            .setConnectable(true)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            // 广播包携带本机发射功率：接收端扫描可读（ScanResult.getTxPowerLevel），结合 RSSI 估算路径损耗/距离
            .setIncludeTxPowerLevel(true)
            .addServiceUuid(ParcelUuid(serviceUuid))
            .addServiceData(ParcelUuid(serviceUuid), advertiseShortId.toByteArray())
            .build()
        // 扫描响应携带送达确认键（独立 Service Data，与短 ID 广播互不干扰、老版本兼容）：
        // 对端无需任何 GATT 连接，扫描本机广播即可读到"已收到哪些消息"并确认送达（硬实时第三通道）
        val ackBytes = ackProvider().take(6)
            .reduceOrNull { acc, k -> acc + k }   // 6 × 4B = 24B ≤ 扫描响应 31B 预算
        val scanResponse = if (ackBytes != null && ackBytes.isNotEmpty()) {
            AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addServiceData(ParcelUuid(ACK_UUID), ackBytes)
                .build()
        } else null
        advertiser.startAdvertising(settings, data, scanResponse, advertiseCallback)
    }

    private fun startScanning() {
        debugStats.recordScanStarted()
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(null, settings, scanCallback)
    }

    private val advertiseCallback = object : AdvertiseCallback() {}
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            debugStats.recordScanResult()
            val device = result.device ?: return
            val record = result.scanRecord ?: return
            // 识别广播中的 Service Data：内容为本机短 ID
            val shortId = record.serviceData[ParcelUuid(serviceUuid)]
                ?.toString(Charsets.UTF_8)
                ?.takeIf { it.isNotBlank() } ?: return
            // 解析扫描响应携带的送达确认键（4B/个）：对端已收到的消息，本机据此确认送达
            val ackData = record.serviceData[ParcelUuid(ACK_UUID)]
            val ackKeys: List<ByteArray> = if (ackData != null && ackData.isNotEmpty()) {
                buildList {
                    var i = 0
                    while (i + 4 <= ackData.size) {
                        add(ackData.copyOfRange(i, i + 4))
                        i += 4
                    }
                }
            } else emptyList()
            peerIds[device.address] = shortId
            _foundPeers.tryEmit(
                MeshPeerInfo(
                    shortId = shortId, deviceAddress = device.address, rssi = result.rssi,
                    ackKeys = ackKeys,
                    // 广播包带 TX power 字段时有效（本工程互发必带）；老版本/未知 = Int.MIN_VALUE
                    txPower = record.txPowerLevel,
                ),
            )
            connectTo(device)
        }
    }

    private fun connectTo(device: BluetoothDevice) {
        debugStats.recordGattConnectAttempt()
        if (gattClients.containsKey(device.address)) return
        val now = System.currentTimeMillis()
        val lastTry = connectAttempts[device.address]
        if (lastTry != null && now - lastTry < CONNECT_RETRY_COOLDOWN_MS) return // 失败冷却，防高频重连
        connectAttempts[device.address] = now
        // connectGatt 文档要求在带 Looper 的线程调用，统一调度到主线程
        mainHandler.post {
            if (gattClients.containsKey(device.address)) return@post
            val gatt = runCatching {
                device.connectGatt(context, false, object : BluetoothGattCallback() {
                    private var discoverRetries = 0
                    private var servicesDiscovered = false
                    private var discoverTimer: Runnable? = null
                    /** v1.1.44：MTU 协商超时重试定时器（onMtuChanged 3s 未到 → 重发 requestMtu）。 */
                    private var mtuTimer: Runnable? = null

                    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                        Log.d(TAG, "connect[${device.address}] newState=$newState status=$status")
                        when (newState) {
                            BluetoothProfile.STATE_CONNECTED -> {
                                debugStats.recordGattConnectSuccess()
                                connectAttempts.remove(device.address)
                                servicesDiscovered = false
                                DebugLogBuffer.log(TAG, "connect[${device.address}] CONNECTED status=$status")
                                // v1.1.44：**requestMtu 移到 onServicesDiscovered 成功之后**——部分蓝牙栈在服务发现
                                // 完成前请求 MTU 会被忽略/失败，导致该连接 MTU 停在 23 → 所有大帧"大小不接受"
                                // （用户日志大量写失败真根因）。服务就绪后再协商 MTU，协商成功率大幅提升。
                                // v1.1.27 曾加 setPreferredPhy(2M)+requestConnectionPriority(HIGH)（吞吐优化）——
                                // v1.1.33 移除：该组合在部分 Android 13+/16 设备上致连接参数异常、大帧（~500B）ATT 写
                                // 静默失败（v1.1.26 无此调用时文件传输正常 30 块/s，v1.1.27 起全 0 块，回退验证）。
                                discoverServicesWithTimeout(gatt)
                                // v1.1.41：连接恢复立即补写排队帧（连接抖动期的写不再丢失）
                                tryFlush(device.address)
                            }
                            BluetoothProfile.STATE_DISCONNECTED -> {
                                debugStats.recordGattDisconnect()
                                DebugLogBuffer.log(TAG, "connect[${device.address}] DISCONNECTED status=$status")
                                // 移除连接记录：持续扫描重新发现时会自动重连（受冷却限制）
                                discoverTimer?.let { mainHandler.removeCallbacks(it) }
                                mtuTimer?.let { mainHandler.removeCallbacks(it) }
                                gatt.close()
                                gattClients.remove(device.address)
                                gattMtu.remove(gatt)
                                // v1.1.41：**保留 pendingFrames**——连接抖动期排队的帧等重连后补写（30s 超时兜底清理）
                                // v1.1.42：断开即重建起点，失败计数/退避清零
                                failStreak.remove(device.address)
                                flushBackoff.remove(device.address)
                            }
                        }
                    }

                    /** discoverServices + 超时兜底：部分 ROM 上回调永不触发，导致帧滞留 pendingFrames。 */
                    private fun discoverServicesWithTimeout(gatt: BluetoothGatt) {
                        gatt.discoverServices()
                        val timer = object : Runnable {
                            override fun run() {
                                when {
                                    servicesDiscovered -> Unit
                                    discoverRetries++ < MAX_DISCOVER_RETRIES -> {
                                        Log.w(TAG, "discoverServices timeout for ${device.address}, retry #$discoverRetries")
                                        gatt.discoverServices()
                                        mainHandler.postDelayed(this, DISCOVER_TIMEOUT_MS)
                                    }
                                    else -> Log.w(TAG, "discoverServices timeout for ${device.address}, giving up")
                                }
                            }
                        }
                        discoverTimer = timer
                        mainHandler.postDelayed(timer, DISCOVER_TIMEOUT_MS)
                    }

                    override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                        Log.d(TAG, "mtu[${device.address}] mtu=$mtu status=$status")
                        DebugLogBuffer.log(TAG, "mtu[${device.address}] mtu=$mtu status=$status")
                        mtuTimer?.let { mainHandler.removeCallbacks(it) }
                        if (status == BluetoothGatt.GATT_SUCCESS) {
                            debugStats.recordMtu(mtu)
                            negotiatedMtu = mtu
                            // v1.1.43：记录该连接的 MTU（per-connection 写前校验用）
                            gattMtu[gatt] = mtu
                            // MTU 就绪后补写排队帧（服务可能早已发现、帧因超 MTU 滞留——v1.1.36 关键补写点）
                            tryFlush(device.address)
                        } else {
                            // v1.1.44：协商失败也重试（3s 后）
                            val retry = object : Runnable {
                                override fun run() {
                                    Log.w(TAG, "mtu negotiation failed(status=$status) for ${device.address}, retry")
                                    runCatching { gatt.requestMtu(512) }
                                }
                            }
                            mtuTimer = retry
                            mainHandler.postDelayed(retry, 3_000L)
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onCharacteristicChanged(
                        gatt: BluetoothGatt,
                        characteristic: BluetoothGattCharacteristic,
                    ) {
                        // API 33 以下走此重载：值从特征对象读取
                        onCharacteristicChanged(gatt, characteristic, characteristic.value ?: ByteArray(0))
                    }

                    override fun onCharacteristicChanged(
                        gatt: BluetoothGatt,
                        characteristic: BluetoothGattCharacteristic,
                        value: ByteArray,
                    ) {
                        Log.d(TAG, "notify received from ${device.address} (${value.size}B)")
                        runCatching { MeshFrame.decode(value) }.onSuccess { _incoming.tryEmit(it) }
                    }

                    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                        servicesDiscovered = true
                        discoverTimer?.let { mainHandler.removeCallbacks(it) }
                        if (status == BluetoothGatt.GATT_SUCCESS) {
                            debugStats.recordServicesDiscovered(true)
                            Log.d(TAG, "services discovered[${device.address}]")
                            DebugLogBuffer.log(TAG, "services discovered[${device.address}] status=$status")
                            // v1.1.44：服务就绪后再协商 MTU（CONNECTED 时请求太早会被部分栈忽略/失败 → 大帧大小不接受）。
                            // onMtuChanged 成功后 tryFlush 会把排队帧按新 MTU 补写。
                            runCatching { gatt.requestMtu(512) }
                                .onFailure { Log.w(TAG, "requestMtu failed: $it") }
                            // v1.1.44：MTU 协商超时兜底——3s 内 onMtuChanged 未更新该连接 MTU → 重发 requestMtu
                            mtuTimer?.let { mainHandler.removeCallbacks(it) }
                            val mtuRetry = object : Runnable {
                                override fun run() {
                                    if (!gattMtu.containsKey(gatt)) {
                                        Log.w(TAG, "mtu negotiation timeout for ${device.address}, retry")
                                        runCatching { gatt.requestMtu(512) }
                                    }
                                }
                            }
                            mtuTimer = mtuRetry
                            mainHandler.postDelayed(mtuRetry, 3_000L)
                            // 订阅对端 notify（写 CCCD），对端即可通过 server→central 通道回传帧
                            val char = gatt.getService(serviceUuid)?.getCharacteristic(charUuid)
                            if (char != null) {
                                runCatching {
                                    gatt.setCharacteristicNotification(char, true)
                                    char.getDescriptor(CCCD_UUID)?.let { cccd ->
                                        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                        val written = gatt.writeDescriptor(cccd)
                                        if (!written) {
                                            // 订阅写入失败：短延迟重试一次（对端订阅记录未建立会丢回执 notify）
                                            mainHandler.postDelayed(
                                                { runCatching { gatt.writeDescriptor(cccd) } },
                                                200L,
                                            )
                                        }
                                    }
                                }
                            }
                            // 服务发现成功：统一补写排队帧（含因 MTU 不足滞留的）
                            tryFlush(device.address)
                        } else if (discoverRetries++ < MAX_DISCOVER_RETRIES) {
                            debugStats.recordServicesDiscovered(false)
                            Log.w(TAG, "services discover failed(status=$status), retry #$discoverRetries")
                            gatt.discoverServices()
                        } else {
                            debugStats.recordServicesDiscovered(false)
                            Log.w(TAG, "services discover failed(status=$status), giving up")
                        }
                    }
                })
            }.onFailure { Log.e(TAG, "connectGatt[${device.address}] failed: $it") }.getOrNull() ?: return@post
            gattClients[device.address] = gatt
        }
    }

    private fun writeToConnectedClients(frame: MeshFrame, unreliable: Boolean = false) {
        // 快照 key 再遍历：写失败会移除死连接，不能在 forEach 中改 map
        gattClients.keys.toList().forEach { address ->
            val gatt = gattClients[address] ?: return@forEach
            val characteristic = gatt.getService(serviceUuid)?.getCharacteristic(charUuid)
            val connected = runCatching {
                bluetoothManager.getConnectionState(gatt.device, BluetoothProfile.GATT) == BluetoothProfile.STATE_CONNECTED
            }.getOrDefault(false)
            // v1.1.43：用该连接自己的协商 MTU 校验（不同对端 MTU 能力不同，全局值会误判）
            val connMtu = gattMtu[gatt] ?: negotiatedMtu
            when {
                characteristic == null || !connected ->
                    // 服务尚未发现 / 连接非 CONNECTED：暂存，待就绪后补写；超时帧丢弃防永久滞留
                    queueFrame(address, frame)
                frame.payload.size > connMtu - 3 ->
                    // v1.1.44：帧超该连接当前 MTU（"大小不接受"）——MTU 协商是异步的，服务发现后 requestMtu(512)
                    // 会经 onMtuChanged 把 MTU 变大（23→512），**排队等 MTU 就绪后补写送达**（不跳过不丢帧）；
                    // 30s 超时兜底清理（对端真不支持大 MTU 时不再硬发）。v1.1.43 的"跳过"治标不治本——帧到不了对端。
                    queueFrame(address, frame)
                else -> {
                    if (!writeTo(gatt, frame, unreliable)) {
                        // 写失败：若链路已断（对端进程被杀后残留），移除死连接，下次扫描自动重建
                        val state = runCatching {
                            bluetoothManager.getConnectionState(gatt.device, BluetoothProfile.GATT)
                        }.getOrDefault(BluetoothProfile.STATE_DISCONNECTED)
                        if (state != BluetoothProfile.STATE_CONNECTED) {
                            Log.w(TAG, "write failed for $address, link dead, drop stale connection")
                            gattClients.remove(address)
                            runCatching { gatt.close() }
                            failStreak.remove(address)
                        } else {
                            // 连接显示 CONNECTED 但写仍失败（栈层写被拒/并发在途）：排队 + 退避重试（帧不丢）；
                            // 连续失败 ≥5 次 → 连接写通道已坏死，强制重建自愈
                            recordWriteFailure(address, frame)
                        }
                    } else {
                        // 写成功：清零失败计数与退避
                        failStreak.remove(address)
                        flushBackoff.remove(address)
                    }
                }
            }
        }
    }

    /** v1.1.43 超限帧（大小不接受）跳过日志：5s 限频，防刷屏。 */
    private fun logOversize(target: String, payloadBytes: Int, mtu: Int) {
        val now = System.currentTimeMillis()
        if (now - lastOversizeLogAt > 5_000) {
            lastOversizeLogAt = now
            Log.w(TAG, "frame ${payloadBytes}B > MTU ${mtu - 3} for $target, skip (upper-layer resend)")
            DebugLogBuffer.log(TAG, "skip OVERSIZE ${payloadBytes}B > ${mtu - 3} for $target")
        }
    }

    /** 暂存待补写帧（服务未发现 / 连接非 CONNECTED / MTU 不足 / 栈忙）；超时清理 + 限长防无限增长。 */
    private fun queueFrame(address: String, frame: MeshFrame) {
        val now = System.currentTimeMillis()
        val queued = pendingFrames.getOrPut(address) { mutableListOf() }
        queued.removeAll { now - it.first > PENDING_FRAME_TIMEOUT_MS }
        if (queued.size >= 32) queued.removeAt(0)
        queued.add(now to frame)
        Log.d(TAG, "queue frame for $address (${frame.type}, ${frame.payload.size}B, queued=${queued.size})")
    }

    /** 记录一次写失败：入队保帧 + 退避重试；连续失败达阈值强制重建连接（v1.1.42 自愈）。 */
    private fun recordWriteFailure(address: String, frame: MeshFrame) {
        val streak = (failStreak[address] ?: 0) + 1
        failStreak[address] = streak
        queueFrame(address, frame)
        if (streak >= MAX_WRITE_FAIL_STREAK) {
            Log.w(TAG, "write failed $streak times consecutively for $address, force reconnect to recover")
            DebugLogBuffer.log(TAG, "force RECONNECT $address (${streak} consecutive write failures)")
            failStreak.remove(address)
            flushBackoff.remove(address)
            forceReconnect(address)
        } else {
            scheduleFlush(address)
        }
    }

    /** 补写重试退避（v1.1.42）：200ms×2^n 封顶 2s——写通道瞬态故障时给栈恢复时间，避免高频重试加剧失败。 */
    private fun scheduleFlush(address: String) {
        val attempt = (flushBackoff[address] ?: 0).coerceAtMost(4)
        flushBackoff[address] = attempt + 1
        val delayMs = minOf(2_000L, 200L * (1 shl attempt))
        mainHandler.postDelayed({ tryFlush(address) }, delayMs)
    }

    /** 强制重建到对端的连接（v1.1.42）：写通道连续失败疑似坏死——close 旧 gatt + 绕过冷却立即重连。 */
    private fun forceReconnect(address: String) {
        val old = gattClients.remove(address) ?: return
        runCatching { old.disconnect() }
        runCatching { old.close() }
        connectAttempts.remove(address)   // 绕过冷却，立即重建
        val device = runCatching { bluetoothAdapter?.getRemoteDevice(address) }.getOrNull() ?: return
        mainHandler.post { connectTo(device) }
    }

    /** 服务发现成功/MTU 就绪/连接恢复后补写排队帧；仍不满足条件的（连接未回/MTU 未到）留队等下次触发。 */
    private fun tryFlush(address: String) {
        val gatt = gattClients[address] ?: return
        val now = System.currentTimeMillis()
        val pending = pendingFrames.remove(address) ?: return
        val keep = mutableListOf<Pair<Long, MeshFrame>>()
        var failed = false
        for ((ts, frame) in pending) {
            if (now - ts > PENDING_FRAME_TIMEOUT_MS) continue   // 超时帧丢弃（对端长期不可达，不再硬发）
            val characteristic = gatt.getService(serviceUuid)?.getCharacteristic(charUuid)
            val connected = runCatching {
                bluetoothManager.getConnectionState(gatt.device, BluetoothProfile.GATT) == BluetoothProfile.STATE_CONNECTED
            }.getOrDefault(false)
            // v1.1.43：per-connection MTU 校验（不同对端能力不同）
            val connMtu = gattMtu[gatt] ?: negotiatedMtu
            if (characteristic != null && connected && frame.payload.size <= connMtu - 3) {
                if (writeTo(gatt, frame)) {
                    failStreak.remove(address)
                    flushBackoff.remove(address)
                } else {
                    failed = true
                    keep.add(ts to frame)   // 写失败：帧不丢，重新入队等下次退避重试
                }
            } else {
                keep.add(ts to frame)   // 服务未就绪/连接未回/超 MTU：留队等下次触发（重建后新 MTU 可能容纳）
            }
        }
        if (keep.isNotEmpty()) {
            pendingFrames[address] = keep
            if (failed) {
                // 有写失败：计数 + 退避重试（或连续失败重建）
                val streak = (failStreak[address] ?: 0) + 1
                failStreak[address] = streak
                if (streak >= MAX_WRITE_FAIL_STREAK) {
                    Log.w(TAG, "flush write failed $streak times for $address, force reconnect")
                    DebugLogBuffer.log(TAG, "force RECONNECT $address (flush, $streak consecutive failures)")
                    failStreak.remove(address)
                    flushBackoff.remove(address)
                    forceReconnect(address)
                } else {
                    scheduleFlush(address)
                }
            }
        }
    }

    /**
     * 写特征值。**API 33+ 用单参**（`writeCharacteristic(char)` + value/writeType）——v1.1.26 实证路径，
     * 真机日志铁证：三参（API 33+ 弃用）连 230B 心跳都 write FAILED（v1.1.32/33 全失败）。
     * API 26-32 用三参（单参 NoSuchMethodError）。返回值：API 33+ 单参 boolean；API 26-32 三参
     * 编译期 int（SDK 36 定义）运行时 boolean（true=1/false=0）→ 判 r != 0。
     */
    private fun writeCharacteristicCompat(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        bytes: ByteArray,
        writeType: Int,
    ): Boolean {
        characteristic.writeType = writeType
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // API 33+：单参重载内部使用 characteristic.writeType + characteristic.value（v1.1.26 方式）
            characteristic.value = bytes
            runCatching { gatt.writeCharacteristic(characteristic) }.getOrDefault(false)
        } else {
            val r = runCatching { gatt.writeCharacteristic(characteristic, bytes, writeType) }.getOrDefault(0)
            r != 0
        }
    }

    private fun writeTo(gatt: BluetoothGatt, frame: MeshFrame, unreliable: Boolean = false): Boolean {
        val characteristic = gatt.getService(serviceUuid)?.getCharacteristic(charUuid) ?: return false
        val bytes = frame.encode()
        if (unreliable) {
            // 无确认写（文件数据块）：不等待对端 GATT 应答 → 写往返消失。丢帧由应用层窗口重传兜底。
            // 对端特性无 WRITE_NO_RESPONSE 属性（老版本）→ 写失败回退可靠确认写。
            val okNoAck = writeCharacteristicCompat(
                gatt, characteristic, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
            )
            if (!okNoAck) {
                Log.w(TAG, "writeNoAck failed, fallback to acknowledged write (${frame.type}, ${frame.payload.size}B)")
                DebugLogBuffer.log(TAG, "writeNoAck FAILED, fallback (${frame.type}, ${frame.payload.size}B)")
                val ok = writeCharacteristicCompat(
                    gatt, characteristic, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                )
                debugStats.recordGattWrite(ok)
                return ok
            }
            debugStats.recordGattWrite(true)
            return true
        }
        val ok = writeCharacteristicCompat(
            gatt, characteristic, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
        )
        debugStats.recordGattWrite(ok)
        if (!ok) {
            // v1.1.42 日志限频：写失败由退避重试/重建自愈兜底（帧不丢），不再每条失败都刷日志
            val now = System.currentTimeMillis()
            if (now - lastWriteFailLogAt > 3_000) {
                lastWriteFailLogAt = now
                Log.w(TAG, "writeCharacteristic failed (${frame.type}, ${frame.payload.size}B)")
                DebugLogBuffer.log(TAG, "write FAILED (${frame.type}, ${frame.payload.size}B)")
            }
        }
        return ok
    }

    /** 通道2：通过 GATT Server 的 notify 向已订阅的 central 对端回传帧（无需本机主动连接）。 */
    private fun notifySubscribers(frame: MeshFrame) {
        val server = gattServer ?: return
        val characteristic = server.getService(serviceUuid)?.getCharacteristic(charUuid) ?: return
        val bytes = frame.encode()
        // notifyCharacteristicChanged 必须在主线程调用，否则部分 ROM 直接抛异常
        mainHandler.post {
            subscribedDevices.forEach { address ->
                val device = serverDevices[address] ?: return@forEach
                // v1.1.43：notify 载荷受该对端连接的 MTU 限制——超限 notify 必失败（回执/PONG 静默丢失），跳过由上层重发兜底
                val mtu = serverMtu[address] ?: negotiatedMtu
                if (bytes.size > mtu - 3) {
                    logOversize(address, bytes.size, mtu)
                    return@forEach
                }
                val ok = runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        // API 33+：4 参数重载，载荷作为参数传入
                        server.notifyCharacteristicChanged(device, characteristic, false, bytes)
                    } else {
                        // API 33 以下：无 4 参数重载（否则 NoSuchMethodError），载荷写入特征对象后走 3 参数版本
                        characteristic.value = bytes
                        server.notifyCharacteristicChanged(device, characteristic, false)
                    }
                }.onFailure { Log.e(TAG, "notify error for $address: $it") }.isSuccess
                debugStats.recordNotify(ok)
                if (!ok) {
                    Log.w(TAG, "notify failed for $address")
                    DebugLogBuffer.log(TAG, "notify FAILED for $address (${bytes.size}B)")
                    // 对端连接可能已死/未真正订阅：移除登记，避免持续对死连接空发 notify；
                    // 对端下次写帧时（onCharacteristicWriteRequest）会自动重新登记
                    subscribedDevices.remove(address)
                }
            }
        }
    }
}
