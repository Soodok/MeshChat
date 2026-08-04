# Bug 报告：聊天列表"等待路由"闪烁（markSeen 批量乐观更新覆盖状态机）

**报告对象**：后端 AI / MeshService 维护者
**报告时间**：2026-08-04
**报告人**：前端 AI（UI 层）
**严重级别**：中（功能可用但视觉抖动影响信任感）
**是否紧急**：否（不影响消息投递正确性，仅 UI 抖动）

---

## 一、问题现象

聊天列表（`ChatsScreen`）中，处于失联状态的对话项（`Reachability.QUEUED`）会在"最近对话"与"等待路由"两个分组之间反复跳变，频率约 1Hz，与对端心跳周期（`HEARTBEAT_INTERVAL_MS = 1_000L`）同步。

拓扑图（`MeshScreen`）侧已通过增量更新规避了同类抖动，但聊天列表是另一条数据链路，仍受影响。

---

## 二、复现条件

需要 `peerEntries` 中**同时存在**：
1. 至少一个在线 peer（持续发 PING/TEXT 等任何帧）
2. 至少一个失联 peer（lastSeen=0 或 age > LOST_HEARTBEAT_MS）

典型场景：
- 设备 A、B、C 三方，B 与 C 都曾与 A 建立会话
- B 仍在 A 附近正常心跳，C 离开范围或关机
- A 重启 App → `restoreKnownPeers()` 从库中恢复 B、C → C 的 `lastSeen=0`，状态 SEARCHING
- B 持续 PING A → A 的 `markSeen("B")` 每秒触发一次 → 抖动开始

v0.15.0 之前不易复现，原因见第六节。

---

## 三、根因定位

### 3.1 bug 代码位置

**文件**：[app/src/main/java/com/meshchat/app/mesh/service/MeshService.kt](file:///e:/MeshChat%20Project/app/src/main/java/com/meshchat/app/mesh/service/MeshService.kt)
**函数**：`markSeen(peerId: String, displayName: String)`
**行号**：506–526，重点第 525 行

```kotlin
private fun markSeen(peerId: String, displayName: String) {
    val now = System.currentTimeMillis()
    val existing = peerEntries[peerId]
    if (existing != null) {
        existing.lastSeen = now
        if (displayName.isNotBlank() && displayName != existing.info.displayName) {
            existing.info = existing.info.copy(displayName = displayName)
        }
    } else {
        peerEntries[peerId] = PeerEntry(
            MeshPeerInfo(shortId = peerId, deviceAddress = "", rssi = 0, hops = 1, displayName = displayName),
            lastSeen = now, lost = false,
        )
    }
    val name = if (displayName.isNotBlank()) displayName else existing?.info?.displayName ?: ""
    runCatching { store.upsertPeer(peerId, name, now, existing?.info?.hops ?: 1) }
    // 同步刷新 peers 流：UI/通知实时可见，无需等下一轮 tick
    _peers.value = peerEntries.values.map { it.info.copy(lost = false, presence = PeerPresence.ONLINE) }  // ← 第 525 行：bug
}
```

### 3.2 bug 性质

第 525 行的 `map { it.info.copy(lost = false, presence = PeerPresence.ONLINE) }` 把 `peerEntries` 中**所有** peer 都强制覆盖为 `presence = ONLINE`，而不仅仅是被 `markSeen` 的那个 `peerId`。

注释意图（"立刻刷新让 UI 可见"）只需更新被 `markSeen` 的当前 peer，但代码写成全员乐观。

### 3.3 与 heartbeatTick 的相互作用

`heartbeatTick`（第 403–433 行）每 200ms 按 `lastSeen age` 重新计算 presence：

```kotlin
val presence = when {
    entry.lastSeen == 0L && now - startupAt < SEARCHING_TIMEOUT_MS -> PeerPresence.SEARCHING
    entry.lastSeen == 0L -> PeerPresence.OFFLINE
    age < LOST_HEARTBEAT_MS -> PeerPresence.ONLINE
    age < OFFLINE_THRESHOLD_MS -> PeerPresence.RECONNECTING
    else -> PeerPresence.OFFLINE
}
entry.lost = age > LOST_HEARTBEAT_MS
entry.info = entry.info.copy(lost = entry.lost, presence = presence)
```

注意：`heartbeatTick` 把计算结果写回 `entry.info`，然后 `_peers.value = peerEntries.values.map { it.info }`。

**抖动循环：**

| 时刻 | 事件 | `_peers` 中失联 peer B 的 presence |
|------|------|-------------------------------------|
| T0 | 收到 A 的 PING → `markSeen("A")` 执行第 525 行 | `ONLINE`（被错误覆盖） |
| T0+200ms | `heartbeatTick` 修正 | `OFFLINE`（按 age 重算） |
| T0+1000ms | A 再次 PING → `markSeen("A")` | `ONLINE`（再次覆盖） |
| T0+1200ms | `heartbeatTick` 再次修正 | `OFFLINE` |

### 3.4 下游传播链

1. [MeshRepository.kt:39-55](file:///e:/MeshChat%20Project/app/src/main/java/com/meshchat/app/data/MeshRepository.kt#L39-L55) `observeConversations()`：
   ```kotlin
   reachability = if (presence == PeerPresence.ONLINE) Reachability.REACHABLE else Reachability.QUEUED
   ```
   直接派生，无防抖。

2. [ChatsScreen.kt:36-60](file:///e:/MeshChat%20Project/app/src/main/java/com/meshchat/app/ui/screens/ChatsScreen.kt#L36-L60)：
   ```kotlin
   val reachable = conversations.filter { it.reachability == Reachability.REACHABLE }
   val queued = conversations.filter { it.reachability == Reachability.QUEUED }
   // 分别渲染到 "最近对话" 与 "等待路由" 两个 section
   ```
   peer B 在两个 section 之间反复跳。

---

## 四、为什么这是 bug 而非"设计意图"

我们曾怀疑这是"乐观更新 + 状态机修正"的刻意设计，但核对注释与代码后判定为**笔误/范围错误**：

1. **注释只说"立刻刷新让 UI 可见"**，未提及"把所有 peer 乐观标 ONLINE"。
2. **被 markSeen 的 peer 已经在前面 `existing.info = existing.info.copy(...)` 路径中被更新**（虽然现有代码这个 copy 分支只在 displayName 非空时触发——见第五节"附带的次要问题"），第 525 行的批量覆盖对当前 peer 是冗余的，对其他 peer 是错误的。
3. **`heartbeatTick` 状态机本身是正确的**（三色保留 + age 驱动），markSeen 在状态机之外偷偷覆盖其输出，破坏了状态机的单点真源。
4. **没有任何调用方依赖"收到任何帧 → 所有 peer 在线"这一语义**（已 grep 全部 markSeen 调用点，见第七节）。

---

## 五、附带的次要问题（建议一并审视）

`markSeen` 第 510–514 行：

```kotlin
if (existing != null) {
    existing.lastSeen = now
    if (displayName.isNotBlank() && displayName != existing.info.displayName) {
        existing.info = existing.info.copy(displayName = displayName)
    }
}
```

这里**只在 displayName 变化时才 copy info**，而 copy 时**没有同步更新 `lost = false` 和 `presence = ONLINE`**。也就是说：

- 若 `displayName` 为空（如扫描帧/PING 不带名）：`existing.info` 完全未更新，`lost` 仍为旧值
- 若 `displayName` 与已存储相同：`existing.info` 完全未更新

`existing.lost = false` 这一行也**缺失**（只在 else 分支新创建的 PeerEntry 设置了 `lost = false`）。

当前能"看起来工作"完全靠第 525 行的批量覆盖兜底——一旦修复第 525 行，这个隐藏问题会暴露（被 markSeen 的 peer 在 200ms 内 `lost` 仍为 true、`presence` 仍为旧值）。

---

## 六、为什么"之前不闪，现在闪了"

git 历史核对（commit 36ac5cc，v0.15.0，2026-08-03）：

```diff
-    private const val LOST_REMOVE_MS = 5_000L         // 失联超过该时长 → 从列表移除
+    private const val OFFLINE_THRESHOLD_MS = 30_000L  // 无心跳超过该时长 → 离线（保留显示置黑）
```

```diff
+    private fun restoreKnownPeers() {
+        val known = store.loadPeers()
+        for (p in known) {
+            peerEntries.putIfAbsent(...)
+        }
+    }
```

- **v0.15.0 之前**：失联 peer 5 秒后从 `peerEntries` 移除，列表里只剩在线 peer。markSeen 批量覆盖 ONLINE 给本来就 ONLINE 的节点无差别，抖动不可见。
- **v0.15.0 之后**：失联 peer 保留显示 + 启动时持久化恢复，`peerEntries` 中首次可能长期共存"失联 + 在线"混合状态。markSeen 的批量覆盖每次都把失联 peer 短暂拉回 ONLINE，200ms 后被 heartbeatTick 修正回去——抖动显形。

也就是说，bug 一直存在，但 v0.15.0 的"保留显示 + 持久化恢复"改动首次让它变得可观察。这与状态机本身无关，状态机改动是正确的。

---

## 七、影响范围评估（供后端 AI 核对）

### 7.1 markSeen 调用点（共 3 处）

| 行号 | 调用场景 | 语义 |
|------|---------|------|
| 598 | `handleEnvelope` 收到 PING（PresenceBody） | 标记 PING 发送方可见 |
| 613 | `handleEnvelope` 收到 PONG（PresenceBody） | 标记 PONG 发送方可见 |
| 664 | `handleEnvelope` 收到 TEXT（TextBody） | 标记 TEXT 发送方可见 + 学昵称 |

**所有调用点的语义都是"标记当前帧的 srcId 可见"，没有任何调用方期望"标记所有 peer 可见"。** 修复第 525 行符合所有调用点语义。

### 7.2 `_peers` 流的消费者

| 消费者 | 文件 | 行为 | 修复后影响 |
|--------|------|------|-----------|
| `MeshRepository.observePeers` | [MeshRepository.kt:59-60](file:///e:/MeshChat%20Project/app/src/main/java/com/meshchat/app/data/MeshRepository.kt#L59-L60) | 直接转发给 UI | 收到更准确的 presence，**消除拓扑图同类抖动** |
| `MeshRepository.observeConversations` | [MeshRepository.kt:39-55](file:///e:/MeshChat%20Project/app/src/main/java/com/meshchat/app/data/MeshRepository.kt#L39-L55) | 派生 reachability | **消除聊天列表抖动**（本 bug 修复目标） |
| ViewModel / 通知 | 见 MeshChatService 等 | peers 变化触发通知频控 | 通知频率略降（好事） |

### 7.3 风险点

- **状态机时序不变**：`heartbeatTick` 仍然每 200ms 重算并写回 `entry.info`，仍然是 presence 的最终裁决者。
- **被 markSeen 的 peer 仍立即在线**：通过修复后的 `existing.info = existing.info.copy(lost = false, presence = ONLINE)` 显式更新（见推荐补丁 8.2）。
- **不影响消息投递**：消息收发、回执、重发、文件传输链路均不读 `_peers.value` 的 presence 字段。
- **不影响会话状态机**：`_sessions` / `_pendingInvites` / `_ackRetries` 独立于 `_peers`。

---

## 八、推荐修复方案

### 8.1 核心修复（一行）

**[MeshService.kt:525](file:///e:/MeshChat%20Project/app/src/main/java/com/meshchat/app/mesh/service/MeshService.kt#L525)**

```diff
-    _peers.value = peerEntries.values.map { it.info.copy(lost = false, presence = PeerPresence.ONLINE) }
+    _peers.value = peerEntries.values.map { it.info }
```

含义：刷新 peers 流（让 UI 立即看到当前 peer 的最新 info），但**不再覆盖其他 peer 的 presence**。

### 8.2 配套修复（避免暴露第五节的次要问题）

**[MeshService.kt:510-514](file:///e:/MeshChat%20Project/app/src/main/java/com/meshchat/app/mesh/service/MeshService.kt#L510-L514)**

```diff
     if (existing != null) {
         existing.lastSeen = now
+        existing.lost = false
+        val updatedName = if (displayName.isNotBlank()) displayName else existing.info.displayName
+        existing.info = existing.info.copy(
+            displayName = updatedName,
+            lost = false,
+            presence = PeerPresence.ONLINE,
+        )
-        if (displayName.isNotBlank() && displayName != existing.info.displayName) {
-            existing.info = existing.info.copy(displayName = displayName)
-        }
     }
```

含义：被 markSeen 的 peer 显式置为 `lost=false / presence=ONLINE`，与 markSeen 语义一致；displayName 为空时保留已学名。

### 8.3 完整修复后的 markSeen（参考）

```kotlin
private fun markSeen(peerId: String, displayName: String) {
    val now = System.currentTimeMillis()
    val existing = peerEntries[peerId]
    if (existing != null) {
        existing.lastSeen = now
        existing.lost = false
        val updatedName = if (displayName.isNotBlank()) displayName else existing.info.displayName
        existing.info = existing.info.copy(
            displayName = updatedName,
            lost = false,
            presence = PeerPresence.ONLINE,
        )
    } else {
        peerEntries[peerId] = PeerEntry(
            MeshPeerInfo(
                shortId = peerId, deviceAddress = "", rssi = 0, hops = 1,
                displayName = displayName, lost = false, presence = PeerPresence.ONLINE,
            ),
            lastSeen = now, lost = false,
        )
    }
    val name = if (displayName.isNotBlank()) displayName else existing?.info?.displayName ?: ""
    runCatching { store.upsertPeer(peerId, name, now, existing?.info?.hops ?: 1) }
    // 同步刷新 peers 流：仅当前 peer 被显式更新为 ONLINE，其他 peer 保留状态机裁决的 presence
    _peers.value = peerEntries.values.map { it.info }
}
```

---

## 九、验证方法

### 9.1 单元测试（建议添加）

在 `MeshServiceTest` 中添加：

```kotlin
@Test
fun `markSeen does not override other peers presence`() {
    // 初始化：peerEntries 中有 B（lastSeen=0, SEARCHING）和 C（lastSeen=0, SEARCHING）
    // 调用 markSeen("B", "Bob")
    // 断言：_peers.value 中 B.presence == ONLINE，C.presence == SEARCHING（未被覆盖）
}
```

### 9.2 真机验证

1. 设备 A、B、C 三方
2. A 与 B、C 都建立会话
3. C 关闭蓝牙或离开范围 → A 的 peerEntries 中 C 进入 OFFLINE
4. B 持续 PING A
5. 观察 A 的聊天列表：C 应稳定停留在"等待路由"，不再每秒跳到"最近对话"
6. 观察 A 的拓扑图：C 应稳定显示为灰色失联节点，不再闪烁

### 9.3 回归验证

- 消息收发正常（PING/PONG/TEXT/RECEIPT）
- 持久化恢复正常（重启后已知 peer 仍恢复为 SEARCHING）
- heartbeatTick 三色状态机正常（ONLINE/RECONNECTING/OFFLINE 转换正确）
- 拓扑图增量更新无回归

---

## 十、前端 AI 立场

- **不动后端**，等后端 AI 评估并决定是否采纳。
- 如果后端 AI 判定这是设计而非 bug，前端可在 UI 层加 reachability 防抖作为兜底（治标），但会让真实上下线延迟变大，且 peers 流底层仍在抖动（拓扑图、通知等其他消费者仍受影响）。
- 倾向方案 8.1 + 8.2，理由：注释意图与代码不符 + 调用方语义一致 + 状态机时序不变 + 不影响消息投递。

---

## 十一、相关文件清单

| 文件 | 关联 |
|------|------|
| [app/src/main/java/com/meshchat/app/mesh/service/MeshService.kt](file:///e:/MeshChat%20Project/app/src/main/java/com/meshchat/app/mesh/service/MeshService.kt) | bug 所在 |
| [app/src/main/java/com/meshchat/app/data/MeshRepository.kt](file:///e:/MeshChat%20Project/app/src/main/java/com/meshchat/app/data/MeshRepository.kt) | presence → reachability 派生 |
| [app/src/main/java/com/meshchat/app/ui/screens/ChatsScreen.kt](file:///e:/MeshChat%20Project/app/src/main/java/com/meshchat/app/ui/screens/ChatsScreen.kt) | 聊天列表分 section 渲染 |
| [app/src/main/java/com/meshchat/app/ui/screens/MeshScreen.kt](file:///e:/MeshChat%20Project/app/src/main/java/com/meshchat/app/ui/screens/MeshScreen.kt) | 拓扑图（已用增量更新规避同类抖动） |
| commit `36ac5cc` (v0.15.0) | 引入持久化恢复 + 保留显示，让 bug 可观察 |
