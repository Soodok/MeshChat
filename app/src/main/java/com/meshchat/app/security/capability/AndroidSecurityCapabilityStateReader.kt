package com.meshchat.app.security.capability

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
import com.meshchat.app.security.model.SecurityCapability
import com.meshchat.app.security.model.SecurityCapabilityState

/**
 * Android-only adapter. It checks currently observable capability state but deliberately does
 * not launch permission activities, invoke Play Integrity, or prepare/start a VPN.
 */
class AndroidSecurityCapabilityStateReader(private val context: Context) : SecurityCapabilityStateReader {
    override fun readStates(): Map<SecurityCapability, SecurityCapabilityState> = mapOf(
        SecurityCapability.BLUETOOTH to bluetoothState(),
        SecurityCapability.NOTIFICATIONS to notificationState(),
        SecurityCapability.INTEGRITY_CHECK to SecurityCapabilityState.NOT_CONFIGURED,
        SecurityCapability.VPN_SCAN to SecurityCapabilityState.NOT_CONFIGURED,
        SecurityCapability.ENTERPRISE_MANAGEMENT to SecurityCapabilityState.UNSUPPORTED,
    )

    private fun bluetoothState(): SecurityCapabilityState {
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
            ?: return SecurityCapabilityState.UNSUPPORTED
        return if (requiredBluetoothPermissions().all(::isGranted)) {
            SecurityCapabilityState.GRANTED
        } else {
            SecurityCapabilityState.AVAILABLE
        }
    }

    private fun notificationState(): SecurityCapabilityState {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !isGranted(Manifest.permission.POST_NOTIFICATIONS)) {
            return SecurityCapabilityState.AVAILABLE
        }
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            return SecurityCapabilityState.DENIED
        }
        return SecurityCapabilityState.GRANTED
    }

    private fun requiredBluetoothPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
        )
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
}
