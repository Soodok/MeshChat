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
    val archived: Boolean = false,
    val lastMessageAt: Long = 0L,
    val lastMessageSentByMe: Boolean = true,
    val presence: PeerPresence = PeerPresence.ONLINE,  // 三色状态（与节点列表一致）：在线绿/寻找中·重连黄/离线黑
)

data class ChatMessage(
    val id: String,
    val text: String,
    val sentByMe: Boolean,
    val time: String,
    val delivery: String? = null,
    val file: FileUiMeta? = null,
    val senderName: String? = null,  // v1.1.50 群聊：非本机消息显示发送者昵称（点对点为 null）
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
    val relayVia: String = "",  // 经中继可达的经由节点 shortId（v1.1.0 多跳）；空 = 一跳直连
    val signalRatio: Double = -1.0,  // 链路信号强度(0-1) = PONG 回应速率 ÷ PING 发送速率；-1 样本不足（v1.1.17）
    val relayAgeMs: Long = 0,   // v1.1.80 中继链路段新鲜度（ms）：中继方上报该节点心跳年龄；直连 = 0
    val rttMs: Long = 0,        // v1.1.80 直连往返延迟估算（ms）：PING/PONG 往返；0 = 尚无样本
)

enum class MainDestination(val label: String, val icon: ImageVector) {
    CHATS("聊天", Icons.Outlined.Groups),
    MESH("Mesh", Icons.Outlined.Hub),
    PROFILE("我的", Icons.Outlined.PersonOutline),
}
