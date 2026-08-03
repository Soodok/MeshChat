package com.meshchat.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.meshchat.app.mesh.transport.PeerPresence
import com.meshchat.app.ui.theme.Cyan
import com.meshchat.app.ui.theme.InkSoft
import com.meshchat.app.ui.theme.MeshAmber
import com.meshchat.app.ui.theme.MeshGreen
import com.meshchat.app.ui.theme.TextSecondary

@Composable
fun PresenceAvatar(presence: PeerPresence, modifier: Modifier = Modifier) {
    Box(modifier = modifier.size(56.dp), contentAlignment = Alignment.BottomEnd) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(InkSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.PersonOutline,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(28.dp),
            )
        }
        when (presence) {
            PeerPresence.ONLINE -> Box(Modifier.size(14.dp).clip(CircleShape).background(MeshGreen))
            PeerPresence.SEARCHING, PeerPresence.RECONNECTING -> Box(
                Modifier.size(18.dp).clip(CircleShape).background(InkSoft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.AccessTime, null, tint = MeshAmber, modifier = Modifier.size(14.dp))
            }
            PeerPresence.OFFLINE -> Box(Modifier.size(14.dp).clip(CircleShape).background(TextSecondary))
        }
    }
}

@Composable
fun SecurityNote(modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Lock, null, tint = Cyan, modifier = Modifier.size(18.dp))
        Text(
            text = "端到端加密 · 消息仅在你的设备间传输",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
fun SignalBars(strength: Int) {
    Row(verticalAlignment = Alignment.Bottom) {
        repeat(3) { index ->
            Box(
                modifier = Modifier
                    .padding(horizontal = 1.dp)
                    .size(width = 4.dp, height = (7 + index * 4).dp)
                    .clip(CircleShape)
                    .background(if (index < strength) Cyan else Color(0xFF32475C)),
            )
        }
    }
}
