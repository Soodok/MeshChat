package com.meshchat.app

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.meshchat.app.ui.MeshChatApp
import com.meshchat.app.ui.theme.MeshChatTheme

class MainActivity : ComponentActivity() {
    /** 按系统版本请求正确的 BLE 权限：API 31+ 用新蓝牙权限；API <=30 用位置权限（旧权限由 Manifest 声明即授予）。 */
    private val requiredPermissions: Array<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.WRITE_EXTERNAL_STORAGE)  // API 26-28 写公共 Downloads 需要
        }
    }.toTypedArray()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
            if (hasAllPermissions()) ensureBluetoothAndStart()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 通知点击 → 打开对应会话（conversationRequest 由 ViewModel 订阅）
        val convId = intent.getStringExtra(com.meshchat.app.mesh.service.NotificationHelper.EXTRA_CONV_ID)
        if (convId != null) {
            (application as MeshChatApplication).requestConversation(convId)
        }
        enableEdgeToEdge()
        setContent {
            MeshChatTheme {
                MeshChatApp()
            }
        }
        if (hasAllPermissions()) {
            ensureBluetoothAndStart()
        } else {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    private fun ensureBluetoothAndStart() {
        val manager = getSystemService(BluetoothManager::class.java)
        val adapter = manager.adapter
        if (adapter == null || !adapter.isEnabled) {
            Toast.makeText(this, "蓝牙未开启，请先开启蓝牙后重试", Toast.LENGTH_LONG).show()
            return
        }
        (application as MeshChatApplication).startMesh()
    }

    override fun onResume() {
        super.onResume()
        // 回前台/重进：服务若被系统回收则自动重启（进入即开始寻找），并立即确认所有未送达消息
        val adapter = runCatching { getSystemService(BluetoothManager::class.java).adapter }.getOrNull()
        if (adapter != null && adapter.isEnabled) {
            (application as MeshChatApplication).startMesh()
            (application as MeshChatApplication).service.resendPendingNow()
        }
    }

    private fun hasAllPermissions(): Boolean =
        requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
}
