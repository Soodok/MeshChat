package com.meshchat.app.mesh.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.meshchat.app.MeshChatApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** 前台服务宿主：后台/息屏常驻，BLE 持续收发，收到消息弹通知。 */
class MeshChatService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var notifications: NotificationHelper
    private val app: MeshChatApplication get() = application as MeshChatApplication

    override fun onCreate() {
        super.onCreate()
        notifications = NotificationHelper(this)
        notifications.ensureChannels()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 设置页关闭后台常驻：不启动前台通知，立即停止
        if (!app.backgroundEnabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat()
        app.service.start()
        scope.launch {
            app.service.peers.collect { notifications.updatePersistent(it.size) }
        }
        return START_STICKY   // 系统回收后自动重启（状态从 SharedPreferences 恢复）
    }

    private fun startForegroundCompat() {
        val notification = notifications.persistent(0)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NotificationHelper.SERVICE_NOTIF_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NotificationHelper.SERVICE_NOTIF_ID, notification)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        app.service.stop()
        super.onDestroy()
    }
}
