package com.meshchat.app.ui

import androidx.lifecycle.ViewModel
import com.meshchat.app.data.ChatMessage
import com.meshchat.app.data.linMessages
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class MeshChatViewModel : ViewModel() {
    private val _messages = MutableStateFlow(linMessages)
    val messages = _messages.asStateFlow()

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        _messages.update { messages ->
            messages + ChatMessage(
                id = "local-${System.currentTimeMillis()}",
                text = text.trim(),
                sentByMe = true,
                time = "刚刚",
                delivery = "正在通过 Mesh 发送",
            )
        }
    }
}
