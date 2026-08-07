package com.meshchat.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.GroupAdd
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meshchat.app.data.ChatPreview
import com.meshchat.app.data.Reachability
import com.meshchat.app.mesh.service.GroupInfo
import com.meshchat.app.ui.components.PresenceAvatar
import com.meshchat.app.ui.theme.Cyan
import com.meshchat.app.ui.theme.Divider as MeshDivider
import com.meshchat.app.ui.theme.TextSecondary

@Composable
fun ChatsScreen(
    modifier: Modifier = Modifier,
    conversations: List<ChatPreview>,
    onChatSelected: (String) -> Unit,
    onToggleArchived: (String) -> Unit,
    onDeleteConversation: (String) -> Unit,
    /** v1.1.50 群组：已订阅群列表 + 进入群会话 + 创建群 + 加入群（审查 M4 修复：输入群 ID 订阅）。 */
    groups: List<GroupInfo> = emptyList(),
    onGroupSelected: (String) -> Unit = {},
    onCreateGroup: (String) -> Unit = {},
    onJoinGroup: (String) -> Unit = {},
) {
    val active = conversations.filterNot { it.archived }
    val reachable = active.filter { it.reachability == Reachability.REACHABLE }
    val queued = active.filter { it.reachability == Reachability.QUEUED }
    val archived = conversations.filter { it.archived }
    var showCreateGroup by remember { mutableStateOf(false) }
    var groupNameDraft by remember { mutableStateOf("") }
    var showJoinGroup by remember { mutableStateOf(false) }
    var joinGroupDraft by remember { mutableStateOf("") }
    LazyColumn(modifier = modifier, contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 8.dp)) {
        // v1.1.50 群组分区：已订阅群（群名 + 群 ID）+ 创建群/加入群按钮（常显，空态给引导文案）
        item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "群组",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { joinGroupDraft = ""; showJoinGroup = true }) {
                        Icon(Icons.Outlined.GroupAdd, "加入群", tint = TextSecondary)
                    }
                    IconButton(onClick = { groupNameDraft = ""; showCreateGroup = true }) {
                        Icon(Icons.Outlined.Add, "创建群", tint = Cyan)
                    }
                }
            }
            if (groups.isEmpty()) {
                item {
                    Text(
                        text = "无已加入群 · 点右上角 + 创建",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
                    )
                }
            }
            items(groups, key = { it.id }) { group ->
                GroupRow(group, onClick = { onGroupSelected(group.id) })
            }
            item { Spacer(Modifier.height(6.dp)) }
        if (active.isEmpty() && archived.isEmpty()) {
            item {
                Text(
                    text = "暂无对话",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 32.dp),
                )
            }
        } else {
            if (reachable.isNotEmpty()) {
                item { SectionLabel("最近对话") }
                items(reachable, key = { it.id }) { chat ->
                    ChatRow(chat, onClick = { onChatSelected(chat.id) }, onToggleArchived = { onToggleArchived(chat.id) }, onDelete = { onDeleteConversation(chat.id) })
                }
            }
            if (queued.isNotEmpty()) {
                item { SectionLabel("等待路由", topPadding = 22.dp) }
                items(queued, key = { it.id }) { chat ->
                    ChatRow(chat, onClick = { onChatSelected(chat.id) }, onToggleArchived = { onToggleArchived(chat.id) }, onDelete = { onDeleteConversation(chat.id) })
                }
            }
            if (archived.isNotEmpty()) {
                item { SectionLabel("已归档（${archived.size}）", topPadding = 22.dp) }
                items(archived, key = { it.id }) { chat ->
                    ChatRow(chat, onClick = { onChatSelected(chat.id) }, onToggleArchived = { onToggleArchived(chat.id) }, onDelete = { onDeleteConversation(chat.id) })
                }
            }
        }
    }
    if (showCreateGroup) {
        AlertDialog(
            onDismissRequest = { showCreateGroup = false },
            title = { Text("创建群") },
            text = {
                OutlinedTextField(
                    value = groupNameDraft,
                    onValueChange = { groupNameDraft = it },
                    placeholder = { Text("群名") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCreateGroup = false
                        if (groupNameDraft.isNotBlank()) onCreateGroup(groupNameDraft)
                    },
                    enabled = groupNameDraft.isNotBlank(),
                ) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { showCreateGroup = false }) { Text("取消") } },
        )
    }
    // v1.1.50（审查 M4 修复）：加入已有群——输入创建者告知的群 ID（8 字符），本地订阅即加入
    if (showJoinGroup) {
        AlertDialog(
            onDismissRequest = { showJoinGroup = false },
            title = { Text("加入群") },
            text = {
                OutlinedTextField(
                    value = joinGroupDraft,
                    onValueChange = { joinGroupDraft = it },
                    placeholder = { Text("群 ID（向创建者获取）") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showJoinGroup = false
                        if (joinGroupDraft.isNotBlank()) onJoinGroup(joinGroupDraft)
                    },
                    enabled = joinGroupDraft.isNotBlank(),
                ) { Text("加入") }
            },
            dismissButton = { TextButton(onClick = { showJoinGroup = false }) { Text("取消") } },
        )
    }
}

/** v1.1.50 群行：群名 + 群 ID（广播域标识，短 ID 风格）。 */
@Composable
private fun GroupRow(group: GroupInfo, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 24.dp, end = 24.dp),
    ) {
        Row(
            modifier = Modifier.padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.foundation.layout.Box(
                Modifier
                    .height(34.dp)
                    .width(34.dp)
                    .background(Cyan.copy(alpha = 0.12f), androidx.compose.foundation.shape.CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Groups, null, tint = Cyan, modifier = Modifier.width(20.dp).height(20.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(group.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    // v1.1.54：成员数 = 本机见过的去重发言者（广播域无成员表，近似值）
                    text = if (group.memberCount > 0) "成员 ${group.memberCount} · ${group.id}" else "暂无发言 · ${group.id}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            }
            Text("群聊", style = MaterialTheme.typography.bodySmall, color = Cyan)
        }
        HorizontalDivider(color = MeshDivider)
    }
}

@Composable
private fun SectionLabel(text: String, topPadding: androidx.compose.ui.unit.Dp = 8.dp) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = 24.dp, top = topPadding, bottom = 8.dp),
    )
}

@Composable
private fun ChatRow(
    chat: ChatPreview,
    onClick: () -> Unit,
    onToggleArchived: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var confirmDelete by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 24.dp, end = 24.dp),
    ) {
        Row(
            modifier = Modifier.padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PresenceAvatar(chat.presence)
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(chat.name, style = MaterialTheme.typography.titleLarge)
                Text(
                    text = chat.snippet,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(chat.time, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                if (chat.unread) androidx.compose.foundation.layout.Box(
                    Modifier
                        .height(9.dp)
                        .width(9.dp)
                        .background(Cyan, androidx.compose.foundation.shape.CircleShape),
                )
            }
            androidx.compose.foundation.layout.Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Outlined.MoreVert, "会话操作", tint = TextSecondary)
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text(if (chat.archived) "取消归档" else "归档对话") },
                        onClick = { menuExpanded = false; onToggleArchived() },
                    )
                    DropdownMenuItem(
                        text = { Text("删除对话") },
                        onClick = { menuExpanded = false; confirmDelete = true },
                    )
                }
            }
        }
        HorizontalDivider(color = MeshDivider)
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除对话？") },
            text = { Text("将删除与 ${chat.name} 的本地消息记录，并解除本机对话关系。") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }
}
