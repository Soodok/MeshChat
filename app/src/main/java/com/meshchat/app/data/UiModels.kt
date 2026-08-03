package com.meshchat.app.data

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.PersonOutline

enum class Reachability { REACHABLE, QUEUED }

data class ChatPreview(
    val id: String,
    val name: String,
    val snippet: String,
    val time: String,
    val reachability: Reachability,
    val unread: Boolean = false,
)

data class ChatMessage(
    val id: String,
    val text: String,
    val sentByMe: Boolean,
    val time: String,
    val delivery: String? = null,
)

data class MeshPeer(
    val name: String,
    val hops: Int,
    val strength: Int,
    val reachable: Boolean = true,
)

enum class MainDestination(val label: String, val icon: ImageVector) {
    CHATS("聊天", Icons.Outlined.Groups),
    MESH("Mesh", Icons.Outlined.Hub),
    PROFILE("我的", Icons.Outlined.PersonOutline),
}

val nearbyChats = listOf(
    ChatPreview("lin", "林宇航", "在营地北侧发现一条小路，正在探索中。", "2 分钟前", Reachability.REACHABLE),
    ChatPreview("rain", "陈雨桐", "物资已送达集合点，大家注意查看。", "15 分钟前", Reachability.REACHABLE),
    ChatPreview("mo", "周子墨", "天气转凉，记得多带一件外套。", "1 小时前", Reachability.REACHABLE),
    ChatPreview("zhao", "赵一诺", "我在水源地附近，可以过来帮忙。", "2 小时前", Reachability.REACHABLE, true),
    ChatPreview("sun", "孙梦琪", "把最新地图发我一下吧。", "昨天", Reachability.REACHABLE),
    ChatPreview("wu", "吴昊然", "能帮我带一下急救包吗？谢谢！", "昨天", Reachability.QUEUED),
)

val queuedChats = listOf(
    ChatPreview("wang", "王景明", "那边信号怎么样？", "昨天", Reachability.QUEUED),
    ChatPreview("shen", "沈清禾", "有新坐标，稍后发你。", "7 月 31 日", Reachability.QUEUED),
)

val meshPeers = listOf(
    MeshPeer("Bravo", 1, 3),
    MeshPeer("Charlie", 1, 3),
    MeshPeer("Delta", 1, 3),
    MeshPeer("Foxtrot", 1, 2),
    MeshPeer("Echo", 2, 2),
    MeshPeer("Golf", 2, 3),
)

val linMessages = listOf(
    ChatMessage("1", "你那边节点情况怎么样？\n我这边信号还可以，能稳定转发。", false, "10:21"),
    ChatMessage("2", "还行，附近有几个节点，\n已经连上了，2 跳路由。", true, "10:22", "已通过 Mesh 送达"),
    ChatMessage("3", "好的，我把钥匙包发你，\n等下我们一起更新配置。", false, "10:23"),
    ChatMessage("4", "收到，等下我在现场测试一下，\n有结果再同步你。", true, "10:24", "已通过 Mesh 送达"),
    ChatMessage("5", "没问题，注意安全。", false, "10:26"),
)
