package com.meshchat.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Ink = Color(0xFF081420)
val InkRaised = Color(0xFF101F2E)
val InkSoft = Color(0xFF172A3D)
val Cyan = Color(0xFF20C9E8)
val MeshGreen = Color(0xFF38D66B)
val MeshAmber = Color(0xFFFFB62E)
val MeshRed = Color(0xFFFF5A5A)
val TextPrimary = Color(0xFFF5F7FA)
val TextSecondary = Color(0xFF9BA9BB)
val Divider = Color(0xFF23394D)
val BubbleMine = Color(0xFF123249)

private val MeshColorScheme = darkColorScheme(
    primary = Cyan,
    onPrimary = Ink,
    secondary = MeshGreen,
    background = Ink,
    onBackground = TextPrimary,
    surface = InkRaised,
    onSurface = TextPrimary,
    surfaceVariant = InkSoft,
    onSurfaceVariant = TextSecondary,
    outline = Divider,
)

@Composable
fun MeshChatTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MeshColorScheme,
        typography = MeshTypography,
        content = content,
    )
}
