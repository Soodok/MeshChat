package com.meshchat.app.data

import com.meshchat.app.mesh.quality.BluetoothQuality
import com.meshchat.app.mesh.service.MeshService
import com.meshchat.app.mesh.storage.MessageStatus
import com.meshchat.app.mesh.storage.MeshStore
import com.meshchat.app.mesh.transport.MeshPeerInfo
import com.meshchat.app.mesh.transport.PeerPresence
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

interface MeshRepository {
    fun observeConversations(): Flow<List<ChatPreview>>
    fun observeMessages(convId: String): Flow<List<ChatMessage>>
    fun observePeers(): Flow<List<MeshPeer>>
    fun observeSessions(): Flow<Set<String>>
    fun observePendingInvites(): Flow<Set<String>>
    fun observeInvites(): Flow<Map<String, Long>>
    /** v1.1.57 E2EE 强制加密：返回是否发送成功（false = 无会话密钥拒绝发送，对方旧版本/未协商）。 */
    fun sendText(convId: String, text: String): Boolean
    fun sendFile(convId: String, dstId: String, openSource: () -> java.io.InputStream, fileName: String, mime: String, size: Long): String?
    fun observeFileProgress(): Flow<com.meshchat.app.mesh.transfer.FileProgress?>
    fun sendInvite(peerId: String)
    fun acceptInvite(peerId: String)
    fun rejectInvite(peerId: String)
    fun deleteConversation(peerId: String)
    fun startDiscovery()
    fun localShortId(): String
    /** 发现开关（v1.1.49）：当前是否在广播+扫描。 */
    val discoveryEnabled: kotlinx.coroutines.flow.StateFlow<Boolean>
    fun suspendDiscovery()
    fun resumeDiscovery()
    /** v1.1.53 发现模式：NORMAL 全开 / CLOSED 全停 / SILENT 静默（只停广播，scan/连接/保活照常）。 */
    val discoveryMode: kotlinx.coroutines.flow.StateFlow<com.meshchat.app.mesh.transport.DiscoveryMode>
    fun setDiscoveryMode(mode: com.meshchat.app.mesh.transport.DiscoveryMode)
    /** v1.1.50 群消息：已订阅群列表 / 创建群（返回群 ID）/ 加入群（输入群 ID 本地订阅）/ 发送群消息。 */
    fun observeGroups(): Flow<List<com.meshchat.app.mesh.service.GroupInfo>>
    fun createGroup(groupName: String): String
    fun joinGroup(groupId: String)
    fun sendGroupMessage(groupId: String, text: String)
}

class MeshRepositoryImpl(
    private val service: MeshService,
    private val store: MeshStore,
) : MeshRepository {

    override fun observeConversations(): Flow<List<ChatPreview>> =
        combine(service.sessions, store.observeAllMessages(), service.peers) { sessionIds, messages, peers ->
            // 会话集合 + 消息历史反推的对话兜底：即使会话关系持久化丢失/重装，最近对话列表也不空（持久化效果）
            val latestByConversation = messages.groupBy { it.convId }
                .mapValues { (_, entries) -> entries.maxByOrNull { it.ts }!! }
            // v1.1.50：群会话（group-<id>）从点对点列表排除（群会话在"群组"分区展示）
            val ids = (sessionIds + latestByConversation.keys
                .filter { !it.startsWith("group-") }
                .map { it.substringAfterLast("-") })
                .distinct()
                .filter { it.isNotBlank() && it != "ME" }
            ids.map { id ->
                val peer = peers.firstOrNull { it.shortId == id }
                val name = peer?.displayName?.ifBlank { id } ?: id
                val presence = peer?.presence ?: PeerPresence.SEARCHING
                val latest = latestByConversation["conv-$id"]
                ChatPreview(
                    id = id,
                    name = name,
                    snippet = latest?.text?.ifBlank { "附件" } ?: "已建立对话",
                    time = latest?.let { SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(it.ts)) } ?: "",
                    reachability = if (presence == PeerPresence.ONLINE) Reachability.REACHABLE else Reachability.QUEUED,
                    presence = presence,
                    lastMessageAt = latest?.ts ?: 0L,
                    lastMessageSentByMe = latest?.srcId == service.shortId,
                )
            }
        }.map { it.sortedByDescending { preview -> preview.lastMessageAt } }

    override fun observePendingInvites(): Flow<Set<String>> = service.pendingInvites

    override fun observePeers(): Flow<List<MeshPeer>> =
        service.peers.map { list -> list.map { it.toUiModel() } }

    override fun startDiscovery() {
        service.start()               // 确保 Mesh 逻辑已启动（幂等）
        service.restartDiscovery()    // 强制重建 BLE：清空遗留状态（蓝牙从关到开/连接残留时必需）
    }

    override fun localShortId(): String = service.shortId

    override val discoveryEnabled: kotlinx.coroutines.flow.StateFlow<Boolean> = service.discoveryEnabled

    override fun suspendDiscovery() = service.suspendDiscovery()

    override fun resumeDiscovery() = service.resumeDiscovery()

    override val discoveryMode: kotlinx.coroutines.flow.StateFlow<com.meshchat.app.mesh.transport.DiscoveryMode> = service.discoveryMode

    override fun setDiscoveryMode(mode: com.meshchat.app.mesh.transport.DiscoveryMode) = service.setDiscoveryMode(mode)

    override fun observeMessages(convId: String): Flow<List<ChatMessage>> =
        store.observeMessages(convId).map { list -> list.map { it.toUiModel() } }

    override fun observeSessions(): Flow<Set<String>> = service.sessions

    override fun observeInvites(): Flow<Map<String, Long>> = service.invites

    override fun sendText(convId: String, text: String): Boolean {
        // 自环会话（conv-ME）目标为本机短 ID，触发本地投递；节点会话目标为其短 ID
        val dstId = if (convId == "conv-ME") service.shortId else convId.substringAfterLast("-")
        return service.sendText(convId, dstId, text)
    }

    override fun sendFile(convId: String, dstId: String, openSource: () -> java.io.InputStream, fileName: String, mime: String, size: Long): String? =
        service.sendFile(convId, dstId, openSource, fileName, mime, size)

    override fun observeFileProgress(): Flow<com.meshchat.app.mesh.transfer.FileProgress?> =
        service.fileProgress

    override fun sendInvite(peerId: String) = service.sendInvite(peerId)

    override fun acceptInvite(peerId: String) = service.acceptInvite(peerId)

    override fun rejectInvite(peerId: String) = service.rejectInvite(peerId)

    override fun observeGroups(): Flow<List<com.meshchat.app.mesh.service.GroupInfo>> = service.groups

    override fun createGroup(groupName: String): String = service.createGroup(groupName)

    override fun joinGroup(groupId: String) = service.joinGroup(groupId)

    override fun sendGroupMessage(groupId: String, text: String) = service.sendGroupMessage(groupId, text)

    override fun deleteConversation(peerId: String) {
        store.deleteConversation("conv-$peerId")
        service.removeSession(peerId)
        service.removePeer(peerId)   // 同时遗忘节点：Mesh 页立即消失、重启不恢复（在线节点会被重新发现）
    }

    private fun MeshPeerInfo.toUiModel(): MeshPeer {
        // 信号格数由协议层速率比决定（≥60% 满格 / ≥25% 两格 / ≥5% 一格）；样本不足回退 RSSI 格数
        val strength = if (signalRatio >= 0.0) {
            when {
                signalRatio >= 0.6 -> 3
                signalRatio >= 0.25 -> 2
                signalRatio >= 0.05 -> 1
                else -> 0
            }
        } else {
            BluetoothQuality.bars(rssi)
        }
        return MeshPeer(
            name = displayName.ifBlank { shortId }, shortId = shortId, hops = hops, strength = strength,
            rssi = rssi, lost = lost, reachable = !lost, presence = presence, lastSeenAt = lastSeenAt,
            relayVia = relayVia, signalRatio = signalRatio,
        )
    }

    private fun com.meshchat.app.mesh.storage.StoredMessage.toUiModel(): ChatMessage {
        val time = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(ts))
        val sentByMe = srcId == service.shortId
        // v1.1.0：发往 2 跳目标（经中继可达）的消息，送达文案追加"经中继"标注路径
        val viaRelay = sentByMe && service.relayViaFor(dstId) != null
        val isGroup = kind == "GROUP"
        // v1.1.50 群消息状态诚实标注：FAILED 群消息渲染"可能未送达"（回执只能证明至少一个成员收到，非全员）
        val delivery = when (status) {
            MessageStatus.SENDING -> "正在通过 Mesh 发送"
            MessageStatus.DELIVERED -> "已通过 Mesh 送达"
            MessageStatus.FAILED -> if (isGroup) "可能未送达" else "未送达"
        } + if (viaRelay) " · 经中继" else ""
        val file = if (kind == "FILE") {
            val meta = runCatching {
                kotlinx.serialization.json.Json.decodeFromString<Map<String, String>>(fileMeta ?: "{}")
            }.getOrDefault(emptyMap())
            FileUiMeta(
                fileName = meta["fileName"] ?: text ?: "文件",
                size = meta["size"]?.toLongOrNull() ?: 0L,
                progress = 0,
                done = status == MessageStatus.DELIVERED,
                uri = meta["downloadsUri"]?.takeIf { it.isNotBlank() },
            )
        } else null
        // v1.1.50 群聊气泡昵称：非本机群消息解析发送者昵称（markSeen 已学习，回退短 ID）
        val senderName = if (isGroup && !sentByMe) {
            service.peers.value.firstOrNull { it.shortId == srcId }?.displayName?.ifBlank { srcId } ?: srcId
        } else null
        return ChatMessage(
            id = id,
            text = text ?: "",
            sentByMe = srcId == service.shortId,
            time = time,
            delivery = delivery,
            file = file,
            senderName = senderName,
        )
    }
}
