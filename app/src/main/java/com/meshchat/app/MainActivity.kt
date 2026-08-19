package com.meshchat.app

import android.Manifest
import android.annotation.SuppressLint
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
import com.meshchat.app.security.model.SecurityCapability

class MainActivity : ComponentActivity() {
    /** 按系统版本请求正确的 BLE 权限：API 31+ 用新蓝牙权限；API <=30 用位置权限（旧权限由 Manifest 声明即授予）。
     *  NEARBY_WIFI_DEVICES 常量在 API 33+ 分支内引用（InlinedApi 常量为编译期内联，此处仅做字符串入队，运行时安全）。 */
    @SuppressLint("InlinedApi")
    private val requiredPermissions: Array<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
            add(Manifest.permission.NEARBY_WIFI_DEVICES)   // Wi-Fi Direct 增强（缺失则开关即崩）
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
                add(Manifest.permission.ACCESS_FINE_LOCATION)  // API 31-32 Wi-Fi Direct discoverPeers 仍需位置
            }
        } else {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)  // API 26-28 写公共 Downloads 需要
            }
        }
    }.toTypedArray()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val app = application as MeshChatApplication
            if (result.values.all { it }) {
                app.securityCapabilityManager.recordGranted(setOf(SecurityCapability.BLUETOOTH))
                ensureBluetoothAndStart()
            } else {
                // 用户拒绝只会降低 Mesh 能力；Compose UI 已建立，聊天历史和设置仍可进入。
                app.securityCapabilityManager.recordDenied(setOf(SecurityCapability.BLUETOOTH))
            }
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
        (application as MeshChatApplication).securityCapabilityManager.refresh()
        if (hasAllPermissions()) {
            ensureBluetoothAndStart()
        } else {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    override fun onStart() {
        super.onStart()
        // v1.1.58 应用锁（v1.1.73 恢复——被 Wi-Fi Direct 线重写 MainActivity 时丢失）：
        // 已设密码 → 每次进入前台锁定 UI（后台服务继续工作，数据密钥留在内存）
        val app = application as MeshChatApplication
        if (app.appLock.hasPassword) app.appLock.lock()
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
        (application as MeshChatApplication).securityCapabilityManager.refresh()
        // 回前台/重进：服务若被系统回收则自动重启（进入即开始寻找），并立即确认所有未送达消息
        val adapter = runCatching { getSystemService(BluetoothManager::class.java).adapter }.getOrNull()
        if (hasAllPermissions() && adapter != null && adapter.isEnabled) {
            (application as MeshChatApplication).startMesh()
            (application as MeshChatApplication).service.resendPendingNow()
        }
    }

    private fun hasAllPermissions(): Boolean =
        requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
}
