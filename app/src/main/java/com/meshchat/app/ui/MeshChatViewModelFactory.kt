package com.meshchat.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import com.meshchat.app.MeshChatApplication
import com.meshchat.app.data.MeshRepositoryImpl
import com.meshchat.app.data.ConversationPreferences

class MeshChatViewModelFactory : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MeshChatApplication
        return MeshChatViewModel(
            repository = MeshRepositoryImpl(app.service, app.store),
            localBluetoothName = app.localBluetoothName ?: "未知",
            localBluetoothAddress = app.localBluetoothAddress ?: "未知",
            displayNameProvider = { app.displayName },
            setDisplayName = { app.displayName = it },
            backgroundEnabledProvider = { app.backgroundEnabled },
            setBackgroundEnabled = { app.backgroundEnabled = it },
            autoDiscoveryProvider = { app.autoDiscovery },
            setAutoDiscovery = { app.autoDiscovery = it },
            conversationPreferences = ConversationPreferences(app),
            conversationRequest = app.conversationRequest,
            securityCapabilityManager = app.securityCapabilityManager,
            localSecurityCoordinator = app.localSecurityCoordinator,
            debugStats = app.debugStats,
            appLock = app.appLock,   // v1.1.58 应用锁
        ) as T
    }
}
