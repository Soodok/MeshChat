package com.meshchat.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import com.meshchat.app.MeshChatApplication
import com.meshchat.app.data.MeshRepositoryImpl

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
            conversationRequest = app.conversationRequest,
        ) as T
    }
}
