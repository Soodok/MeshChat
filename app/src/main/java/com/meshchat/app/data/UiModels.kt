package com.meshchat.app.data

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.PersonOutline
import com.meshchat.app.mesh.transport.PeerPresence

enum class Reachability { REACHABLE, QUEUED }

data class ChatPreview(
    val id: String,
    val name: String,
    val snippet: String,
    val time: String,
    val reachability: Reachability,
    val unread: Boolean = false,
    val presence: PeerPresence = PeerPresence.ONLINE,  // 三色状态（与节点列表一致）：在线绿/寻找中·重连黄/离线黑
)

data class ChatMessage(
    val id: String,
    val text: String,
    val sentByMe: Boolean,
    val time: String,
    val delivery: String? = null,
    val file: FileUiMeta? = null,
)

data class FileUiMeta(
    val fileName: String,
    val size: Long,
    val progress: Int,      // 0-100
    val done: Boolean,
    val uri: String? = null,  // 接收方收齐后回填的 Downloads URI（点击打开用）
)

data class MeshPeer(
    val name: String,       // 显示名（昵称，缺省回退短 ID）
    val shortId: String,    // 寻址标识（点击/匹配/会话键）
    val hops: Int,
    val strength: Int,
    val rssi: Int = 0,
    val lost: Boolean = false,
    val reachable: Boolean = true,
    val presence: PeerPresence = PeerPresence.ONLINE,  // 三色状态：在线绿/寻找中·重连黄/离线黑
    val lastSeenAt: Long = 0,  // 最后收到对端帧的时刻（ms）：UI 显示"X 秒前信号"
)

enum class MainDestination(val label: String, val icon: ImageVector) {
    CHATS("聊天", Icons.Outlined.Groups),
    MESH("Mesh", Icons.Outlined.Hub),
    PROFILE("我的", Icons.Outlined.PersonOutline),
}
