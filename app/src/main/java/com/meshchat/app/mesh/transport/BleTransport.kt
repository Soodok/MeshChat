package com.meshchat.app.mesh.transport

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
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
import android.os.ParcelUuid
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

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val _incoming = MutableSharedFlow<MeshFrame>(extraBufferCapacity = 64)
    override val incoming: SharedFlow<MeshFrame> = _incoming

    private val _foundPeers = MutableSharedFlow<MeshPeerInfo>(extraBufferCapacity = 64)
    override val foundPeers: SharedFlow<MeshPeerInfo> = _foundPeers

    // GATT Server：暴露服务，接收邻近节点写入的帧
    private var gattServer: BluetoothGattServer? = null
    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            if (value != null) runCatching { MeshFrame.decode(value) }.onSuccess { _incoming.tryEmit(it) }
        }
    }

    // 客户端连接（待转发时按 peerId 建立连接）
    private val gattClients = HashMap<String, BluetoothGatt>()
    private val peerIds = HashMap<String, String>() // deviceAddress -> peerId

    override fun start() {
        runCatching { registerServer() }
        runCatching { startAdvertising() }
        runCatching { startScanning() }
    }

    override fun stop() {
        gattServer?.close()
        gattClients.values.forEach { it.close() }
        gattClients.clear()
        bluetoothAdapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
    }

    override fun broadcast(frame: MeshFrame) {
        writeToConnectedClients(frame)
    }

    override fun sendTo(peerId: String, frame: MeshFrame) { /* 按 peerId 解析地址后写入 */ }

    private fun registerServer() {
        gattServer = bluetoothManager.openGattServer(context, gattServerCallback)
        val service = BluetoothGattService(serviceUuid, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val characteristic = BluetoothGattCharacteristic(
            charUuid,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        service.addCharacteristic(characteristic)
        gattServer?.addService(service)
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
        advertiser.startAdvertising(settings, data, advertiseCallback)
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
            peerIds[device.address] = shortId
            _foundPeers.tryEmit(
                MeshPeerInfo(shortId = shortId, deviceAddress = device.address, rssi = result.rssi),
            )
            connectTo(device)
        }
    }

    private fun connectTo(device: BluetoothDevice) {
        if (gattClients.containsKey(device.address)) return
        val gatt = device.connectGatt(context, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) gatt.discoverServices()
            }
        })
        gattClients[device.address] = gatt
    }

    private fun writeToConnectedClients(frame: MeshFrame) {
        val bytes = frame.encode()
        gattClients.values.forEach { gatt ->
            gatt.getService(serviceUuid)?.getCharacteristic(charUuid)?.let { characteristic ->
                characteristic.value = bytes
                gatt.writeCharacteristic(characteristic)
            }
        }
    }
}
