package com.meshchat.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.meshchat.app.data.ChatMessage
import com.meshchat.app.ui.theme.BubbleMine
import com.meshchat.app.ui.theme.Cyan
import com.meshchat.app.ui.theme.Ink
import com.meshchat.app.ui.theme.InkSoft
import com.meshchat.app.ui.theme.MeshGreen
import com.meshchat.app.ui.theme.TextSecondary

@Composable
fun ConversationScreen(
    messages: List<ChatMessage>,
    title: String,
    onBack: () -> Unit,
    onSendMessage: (String) -> Unit,
) {
    var draft by rememberSaveable { mutableStateOf("") }
    Column(modifier = Modifier.fillMaxSize().background(Ink).imePadding()) {
        ConversationHeader(title, onBack)
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Lock, null, tint = Cyan, modifier = Modifier.size(16.dp))
            Text("消息已端到端加密", color = TextSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 7.dp))
        }
        Text(
            "2026-08-03",
            color = TextSecondary,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        )
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(messages, key = { it.id }) { message -> MessageBubble(message) }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { }) { Icon(Icons.Outlined.AttachFile, "添加附件", tint = TextSecondary) }
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text("输入消息") },
                modifier = Modifier.weight(1f),
                singleLine = true,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = { onSendMessage(draft); draft = "" },
                enabled = draft.isNotBlank(),
                modifier = Modifier.size(50.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Cyan),
            ) { Icon(Icons.AutoMirrored.Outlined.Send, "发送", tint = Ink) }
        }
    }
}

@Composable
private fun ConversationHeader(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 44.dp, start = 12.dp, end = 20.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
        Box(Modifier.size(42.dp).clip(androidx.compose.foundation.shape.CircleShape).background(InkSoft), contentAlignment = Alignment.Center) {
            Text(title.take(1), style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = if (message.sentByMe) Alignment.End else Alignment.Start) {
        Box(
            modifier = Modifier
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
                .background(if (message.sentByMe) BubbleMine else InkSoft)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(message.text, style = MaterialTheme.typography.bodyLarge)
        }
        Text(
            text = listOfNotNull(message.time, message.delivery).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = if (message.sentByMe) Cyan else TextSecondary,
            modifier = Modifier.padding(start = 4.dp, top = 5.dp, end = 4.dp),
        )
    }
}
