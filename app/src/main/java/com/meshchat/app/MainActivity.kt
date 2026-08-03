package com.meshchat.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.meshchat.app.ui.MeshChatApp
import com.meshchat.app.ui.theme.MeshChatTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MeshChatTheme {
                MeshChatApp()
            }
        }
    }
}
