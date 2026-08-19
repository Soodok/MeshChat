package com.meshchat.app.data

import com.meshchat.app.mesh.quality.BluetoothQuality
import com.meshchat.app.mesh.service.MeshService
import com.meshchat.app.mesh.storage.MessageStatus
import com.meshchat.app.mesh.storage.MeshStore
import com.meshchat.app.mesh.transport.LinkInfo
import com.meshchat.app.mesh.transport.MeshPeerInfo
import com.meshchat.app.mesh.transport.PeerPresence
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

interface MeshRepository {
    fun observeConversations(): Flow<List<ChatPreview>>
    fun observeMessages(convId: String): Flow<List<ChatMessage>>
    fun observePeers(): Flow<List<MeshPeer>>
    /** v1.1.80 节点对直连边（拓扑图 peer-peer 边着色：绿=直连，黄=重连中；无边 = 无直连/未知）。 */
    fun observeLinks(): Flow<List<LinkInfo>>
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
    /** v1.1.64 拉黑：拒绝该节点的连接与消息（删除对话时自动拉黑）。 */
    val blockedPeers: kotlinx.coroutines.flow.StateFlow<Set<String>>
    /** v1.1.65 主动拉黑（Mesh 页未连接节点也可拉黑）。 */
    fun blockPeer(peerId: String)
    fun unblockPeer(peerId: String)
    /** v1.1.66 当前频道名（null = 公共频道）。 */
    val channelName: kotlinx.coroutines.flow.StateFlow<String?>
    /** v1.1.66 切换频道（null = 公共频道；非空 = 私人频道，仅同频道可发现/连接）。 */
    fun setChannel(name: String?)
    /** v1.1.66 对端是否在当前频道（发送被拒原因区分）。 */
    fun isPeerInCurrentChannel(peerId: String): Boolean
    /** v1.1.74 MITM 防御：对端公钥指纹与首次记录不一致（身份变更）的节点集合。 */
    val peerKeyChanged: kotlinx.coroutines.flow.StateFlow<Set<String>>
    /** v1.1.74 对端公钥指纹（首次握手记录）；null = 未握手。 */
    fun peerFingerprint(peerId: String): String?
    /** v1.1.74 本机密钥是否降级内存密钥（不持久，重启更换）。 */
    val localKeyFallback: Boolean
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

    override fun observeLinks(): Flow<List<LinkInfo>> = service.links

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
        // v1.1.76：删除对话 = 仅清理本地记录 + 解除会话 + 移除节点（不再拉黑）。
        // 重新搜索可再次发现对方并重新建立对话；彻底拒绝请用 Mesh 页「拉黑」按钮。
        service.removeSession(peerId)
        service.removePeer(peerId)
    }

    override val blockedPeers: kotlinx.coroutines.flow.StateFlow<Set<String>> = service.blockedPeers

    override fun blockPeer(peerId: String) = service.blockPeer(peerId)

    override fun unblockPeer(peerId: String) = service.unblockPeer(peerId)

    override val channelName: kotlinx.coroutines.flow.StateFlow<String?> = service.channelName

    override fun setChannel(name: String?) = service.setChannel(name)

    override fun isPeerInCurrentChannel(peerId: String): Boolean = service.isPeerInCurrentChannel(peerId)

    override val peerKeyChanged: kotlinx.coroutines.flow.StateFlow<Set<String>> = service.peerKeyChanged

    override fun peerFingerprint(peerId: String): String? = service.peerFingerprint(peerId)

    override val localKeyFallback: Boolean get() = service.localKeyFallback

    private fun MeshPeerInfo.toUiModel(): MeshPeer {
        // v1.1.81 信号格数 = 直接 dBm（用户：协议层"收发失联包"信号判断抽象易误判，改回 RSSI；阈值 90/100/110）
        val strength = BluetoothQuality.bars(rssi)
        return MeshPeer(
            name = displayName.ifBlank { shortId }, shortId = shortId, hops = hops, strength = strength,
            rssi = rssi, lost = lost, reachable = !lost, presence = presence, lastSeenAt = lastSeenAt,
            relayVia = relayVia, signalRatio = signalRatio,
            relayAgeMs = relayAgeMs, rttMs = rttMs,   // v1.1.80 中继链路健康/直连延迟
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
            // v1.1.86 修复：fileMeta 的 "size" 是 JSON 数字（fileMetaJson 写入），旧实现按 Map<String,String>
            // 解码必抛类型不匹配 → 整段解析失败回退空表 → 气泡大小恒显 0B（仅活动传输的进度覆盖能显示真值）。
            // 改用 JsonObject 按字段读取，兼容数字与字符串两种形态。
            val meta = runCatching {
                kotlinx.serialization.json.Json.parseToJsonElement(fileMeta ?: "{}").jsonObject
            }.getOrNull()
            val sizePrim = meta?.get("size")?.jsonPrimitive
            FileUiMeta(
                fileName = meta?.get("fileName")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: text ?: "文件",
                size = sizePrim?.let {
                    if (it.isString) it.content.toLongOrNull() else it.longOrNull
                } ?: 0L,
                progress = 0,
                done = status == MessageStatus.DELIVERED,
                uri = meta?.get("downloadsUri")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
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
