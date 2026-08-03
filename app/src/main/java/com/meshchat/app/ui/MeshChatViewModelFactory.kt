package com.meshchat.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import com.meshchat.app.data.MeshRepositoryImpl
import com.meshchat.app.mesh.identity.LocalIdentity
import com.meshchat.app.mesh.routing.DedupCache
import com.meshchat.app.mesh.service.MeshService
import com.meshchat.app.mesh.storage.MeshDatabase
import com.meshchat.app.mesh.storage.RoomMeshStore
import com.meshchat.app.mesh.transport.InMemoryTransport

class MeshChatViewModelFactory : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        val context = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!
        val store = RoomMeshStore(MeshDatabase.build(context))
        val identity = LocalIdentity()
        val service = MeshService(InMemoryTransport(), store, identity, DedupCache())
        service.start()
        return MeshChatViewModel(MeshRepositoryImpl(service, store)) as T
    }
}
