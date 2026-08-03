package com.meshchat.app.mesh.transport

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import com.meshchat.app.mesh.protocol.MeshFrame
import com.meshchat.app.mesh.service.RfcommChannel
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 经典蓝牙 RFCOMM 高吞吐载体：文件数据传输通道（BLE 发现/握手保留）。
 * 服务端 listen+accept；客户端 connect(address)（自动配对）；peerId→socket 映射；4 字节长度前缀分帧。
 */
class RfcommTransport(
    private val context: Context,
    private val sdpUuid: UUID = UUID.fromString("0000A5E3-0000-1000-8000-00805F9B34FB"),
) : MeshTransport, RfcommChannel {
    companion object {
        private const val TAG = "MeshRfcomm"
        private const val SERVICE_NAME = "MeshChat"
        private const val BOND_TIMEOUT_MS = 15_000L
        private const val CONNECT_TIMEOUT_MS = 10_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = runCatching { bluetoothManager.adapter }.getOrNull()

    private val _incoming = MutableSharedFlow<MeshFrame>(extraBufferCapacity = 64)
    override val incoming: SharedFlow<MeshFrame> = _incoming
    override val foundPeers: SharedFlow<MeshPeerInfo> = MutableSharedFlow()

    /** peerId → (socket, 写锁)。写需按 socket 加锁（多协程并发写会交错）。 */
    private val sockets = ConcurrentHashMap<String, Pair<BluetoothSocket, Any>>()
    private var serverSocket: BluetoothServerSocket? = null

    override fun start() {
        if (adapter == null || !adapter.isEnabled) { Log.w(TAG, "classic bluetooth unavailable, rfcomm disabled"); return }
        scope.launch { acceptLoop() }
    }

    override fun stop() {
        sockets.forEach { (_, pair) -> runCatching { pair.first.close() } }
        sockets.clear()
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private suspend fun acceptLoop() {
        val server = runCatching { adapter?.listenUsingRfcommWithServiceRecord(SERVICE_NAME, sdpUuid) }
            .getOrNull() ?: run { Log.w(TAG, "listen failed"); return }
        serverSocket = server
        while (scope.isActive) {
            val socket = runCatching { server.accept() }.getOrNull() ?: break
            // 服务端 accept 的连接暂以 MAC 占位 peerId（incoming 帧按信封 srcId 路由，无需 socket 映射）
            scope.launch { readLoop(socket, socket.remoteDevice.address) }
        }
    }

    /** 客户端主动连接（会话建立后由 MeshService 调用）：peerId 用于寻址，address 为经典蓝牙 MAC。 */
    override suspend fun connect(peerId: String, address: String): Boolean {
        val device = adapter?.getRemoteDevice(address) ?: return false
        if (!ensureBonded(device)) { Log.w(TAG, "bond failed for $address"); return false }
        val socket = runCatching {
            val s = device.createRfcommSocketToServiceRecord(sdpUuid)
            val started = java.util.concurrent.CompletableFuture.supplyAsync {
                s.connect()
            }
            started.get(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            s
        }.getOrNull() ?: run { Log.w(TAG, "connect failed for $address"); return false }
        sockets[peerId] = socket to Any()
        Log.d(TAG, "connected peer=$peerId addr=$address")
        scope.launch { readLoop(socket, peerId) }
        return true
    }

    override fun isConnectedTo(peerId: String): Boolean = sockets.containsKey(peerId)

    override fun sendTo(peerId: String, frame: MeshFrame) {
        val pair = sockets[peerId] ?: run { Log.w(TAG, "no rfcomm socket for $peerId"); return }
        try {
            synchronized(pair.second) {
                RfcommFraming.writeFrame(pair.first.outputStream, frame)
            }
        } catch (e: Exception) {
            Log.w(TAG, "write failed for $peerId: $e")
            sockets.remove(peerId)
            runCatching { pair.first.close() }
        }
    }

    override fun broadcast(frame: MeshFrame) {
        sockets.keys.forEach { sendTo(it, frame) }
    }

    private suspend fun readLoop(socket: BluetoothSocket, peerId: String?) {
        val input: InputStream = runCatching { socket.inputStream }.getOrNull() ?: return
        while (scope.isActive) {
            val frame = runCatching { RfcommFraming.readFrame(input) }.getOrNull() ?: break
            _incoming.emit(frame)
        }
        runCatching { socket.close() }
        if (peerId != null) sockets.remove(peerId)
        Log.d(TAG, "socket closed peer=$peerId addr=${socket.remoteDevice.address}")
    }

    /** 确保已配对：未配对则 createBond + 等待 ACTION_BOND_STATE_CHANGED（系统配对弹窗由用户确认）。 */
    private suspend fun ensureBonded(device: BluetoothDevice): Boolean {
        if (device.bondState == BluetoothDevice.BOND_BONDED) return true
        val latch = CountDownLatch(1)
        var result = false
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
                if (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1) == BluetoothDevice.BOND_BONDED) {
                    result = true; latch.countDown()
                } else if (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1) == BluetoothDevice.BOND_NONE) {
                    latch.countDown()
                }
            }
        }
        context.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
        val ok = runCatching { device.createBond() }.getOrDefault(false)
        if (!ok) { context.unregisterReceiver(receiver); return false }
        val done = latch.await(BOND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        context.unregisterReceiver(receiver)
        return done && result
    }
}
