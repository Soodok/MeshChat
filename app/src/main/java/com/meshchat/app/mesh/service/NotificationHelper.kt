package com.meshchat.app.mesh.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.meshchat.app.MainActivity

/**
 * 通知中心：渠道幂等创建；常驻通知 + 消息通知 + 文件完成通知。
 * v1.1.58 应用锁：锁定态通知不显示发送者与内容（防锁屏/下拉栏泄露）。
 */
class NotificationHelper(
    private val context: Context,
    private val lockedProvider: () -> Boolean = { false },
) {
    companion object {
        private const val SERVICE_CHANNEL = "meshchat_service"
        private const val MESSAGE_CHANNEL = "meshchat_messages"
        const val SERVICE_NOTIF_ID = 1001
        const val EXTRA_CONV_ID = "extra_conv_id"
    }

    private val nm = context.getSystemService(NotificationManager::class.java)

    fun ensureChannels() {
        // minSdk 26（API 26+）恒满足渠道要求，无需 SDK 分支
        nm.createNotificationChannel(
            NotificationChannel(SERVICE_CHANNEL, "MeshChat 后台服务", NotificationManager.IMPORTANCE_MIN),
        )
        nm.createNotificationChannel(
            NotificationChannel(MESSAGE_CHANNEL, "MeshChat 消息", NotificationManager.IMPORTANCE_HIGH),
        )
    }

    /** 常驻通知：前台服务必需，显示节点在线数。 */
    fun persistent(peerCount: Int): Notification {
        ensureChannels()
        val intent = Intent(context, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(context, SERVICE_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle("MeshChat 运行中")
            .setContentText("邻近节点 $peerCount · 消息自动同步")
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    fun updatePersistent(peerCount: Int) {
        nm.notify(SERVICE_NOTIF_ID, persistent(peerCount))
    }

    /** 新消息通知：标题=发送者昵称，内容=正文，点击进对应会话。v1.1.58 锁定态只显示"新消息"隐藏内容。 */
    fun showMessage(fromName: String, text: String, convId: String) {
        if (!canNotify()) return
        ensureChannels()
        val locked = lockedProvider()
        val intent = Intent(context, MainActivity::class.java).putExtra(EXTRA_CONV_ID, convId)
        val pi = PendingIntent.getActivity(
            context, convId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        nm.notify(
            convId.hashCode(),
            Notification.Builder(context, MESSAGE_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(if (locked) "MeshChat 新消息" else fromName)
                .setContentText(if (locked) "应用已锁定 · 解锁后查看" else text)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build(),
        )
    }

    /** 文件接收完成通知。v1.1.58 锁定态不显示文件名。 */
    fun showFileSaved(fileName: String) {
        if (!canNotify()) return
        ensureChannels()
        val locked = lockedProvider()
        nm.notify(
            "file-$fileName".hashCode(),
            Notification.Builder(context, MESSAGE_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_save)
                .setContentTitle(if (locked) "MeshChat 新文件" else "文件已保存")
                .setContentText(if (locked) "应用已锁定 · 解锁后查看" else fileName)
                .setAutoCancel(true)
                .build(),
        )
    }

    /** API 33+ 需 POST_NOTIFICATIONS 运行时授权；未授权静默降级（服务照常运行）。 */
    private fun canNotify(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}
