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
) : MeshTransport {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val _incoming = MutableSharedFlow<MeshFrame>(extraBufferCapacity = 64)
    override val incoming: SharedFlow<MeshFrame> = _incoming

    private val _foundPeers = MutableSharedFlow<MeshPeerInfo>(extraBufferCapacity = 64)
    override val foundPeers: SharedFlow<MeshPeerInfo> = _foundPeers

    private companion object {
        const val TAG = "MeshBle"
        const val MAX_DISCOVER_RETRIES = 3
        const val MAX_SERVICE_ADD_RETRIES = 5
        const val PENDING_FRAME_TIMEOUT_MS = 30_000L
        const val CONNECT_RETRY_COOLDOWN_MS = 5_000L
        const val DISCOVER_TIMEOUT_MS = 5_000L
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
                serverDevices[device.address] = device
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                serverDevices.remove(device.address)
                subscribedDevices.remove(device.address)
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
        writeToConnectedClients(frame)   // 通道1：本机 central → 已连接的对端（写特征）
        notifySubscribers(frame)          // 通道2：已连入本机的对端（server 连接，notify 回传）
    }

    override fun sendTo(peerId: String, frame: MeshFrame) { /* 按 peerId 解析地址后写入 */ }

    private fun registerServer() {
        gattServer = bluetoothManager.openGattServer(context, gattServerCallback)
        val service = BluetoothGattService(serviceUuid, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val characteristic = BluetoothGattCharacteristic(
            charUuid,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
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
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
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
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(null, settings, scanCallback)
    }

    private val advertiseCallback = object : AdvertiseCallback() {}
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
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
                MeshPeerInfo(shortId = shortId, deviceAddress = device.address, rssi = result.rssi, ackKeys = ackKeys),
            )
            connectTo(device)
        }
    }

    private fun connectTo(device: BluetoothDevice) {
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

                    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                        Log.d(TAG, "connect[${device.address}] newState=$newState status=$status")
                        when (newState) {
                            BluetoothProfile.STATE_CONNECTED -> {
                                connectAttempts.remove(device.address)
                                servicesDiscovered = false
                                runCatching { gatt.requestMtu(512) }
                                    .onFailure { Log.w(TAG, "requestMtu failed: $it") }
                                discoverServicesWithTimeout(gatt)
                            }
                            BluetoothProfile.STATE_DISCONNECTED -> {
                                // 移除连接记录：持续扫描重新发现时会自动重连（受冷却限制）
                                discoverTimer?.let { mainHandler.removeCallbacks(it) }
                                gatt.close()
                                gattClients.remove(device.address)
                                pendingFrames.remove(device.address)
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
                            Log.d(TAG, "services discovered[${device.address}]")
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
                            pendingFrames.remove(device.address)?.forEach { writeTo(gatt, it.second) }
                        } else if (discoverRetries++ < MAX_DISCOVER_RETRIES) {
                            Log.w(TAG, "services discover failed(status=$status), retry #$discoverRetries")
                            gatt.discoverServices()
                        } else {
                            Log.w(TAG, "services discover failed(status=$status), giving up")
                        }
                    }
                })
            }.onFailure { Log.e(TAG, "connectGatt[${device.address}] failed: $it") }.getOrNull() ?: return@post
            gattClients[device.address] = gatt
        }
    }

    private fun writeToConnectedClients(frame: MeshFrame) {
        val now = System.currentTimeMillis()
        // 快照 key 再遍历：写失败会移除死连接，不能在 forEach 中改 map
        gattClients.keys.toList().forEach { address ->
            val gatt = gattClients[address] ?: return@forEach
            val characteristic = gatt.getService(serviceUuid)?.getCharacteristic(charUuid)
            if (characteristic != null) {
                if (!writeTo(gatt, frame)) {
                    // 写失败：若链路已断（对端进程被杀后残留），移除死连接，下次扫描自动重建
                    val state = runCatching {
                        bluetoothManager.getConnectionState(gatt.device, BluetoothProfile.GATT)
                    }.getOrDefault(BluetoothProfile.STATE_DISCONNECTED)
                    if (state != BluetoothProfile.STATE_CONNECTED) {
                        Log.w(TAG, "write failed for $address, link dead, drop stale connection")
                        gattClients.remove(address)
                        runCatching { gatt.close() }
                    }
                }
            } else {
                // 服务尚未发现：暂存，待 onServicesDiscovered 后补写；超时帧丢弃防永久滞留
                val queued = pendingFrames.getOrPut(address) { mutableListOf() }
                queued.removeAll { now - it.first > PENDING_FRAME_TIMEOUT_MS }
                if (queued.size >= 32) queued.removeAt(0)
                queued.add(now to frame)
                Log.w(TAG, "service not ready for $address, queue frame (${frame.type}, ${frame.payload.size}B, queued=${queued.size})")
            }
        }
    }

    private fun writeTo(gatt: BluetoothGatt, frame: MeshFrame): Boolean {
        val characteristic = gatt.getService(serviceUuid)?.getCharacteristic(charUuid) ?: return false
        characteristic.value = frame.encode()
        val ok = runCatching { gatt.writeCharacteristic(characteristic) }.getOrDefault(false)
        if (!ok) Log.w(TAG, "writeCharacteristic failed (${frame.type}, ${frame.payload.size}B)")
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
                if (!ok) {
                    Log.w(TAG, "notify failed for $address")
                    // 对端连接可能已死/未真正订阅：移除登记，避免持续对死连接空发 notify；
                    // 对端下次写帧时（onCharacteristicWriteRequest）会自动重新登记
                    subscribedDevices.remove(address)
                }
            }
        }
    }
}
