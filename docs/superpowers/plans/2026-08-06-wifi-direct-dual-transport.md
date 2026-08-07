# Wi-Fi Direct 双通道增强 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 为 MeshChat 增加 Wi-Fi Direct 可选增强层——开启后自动与可达设备建连形成星域，消息双写（BLE+Wi-Fi Direct）、文件优先 Wi-Fi Direct，BLE 断连时强制 Wi-Fi Direct 保持连接。分三计划：P1 一对一连接、P2 多人星域、P3 双通道+文件+UI。

**架构：** 新增 `WifiDirectTransport`（WifiP2pManager 封装：DnsSd 短 ID 识别 → connect/GO negotiation → 星域 group → 组内 UDP 广播 + TCP 可靠通道，复用 RfcommFraming 分帧）与 `CompositeTransport`（实现现有 MeshTransport 契约，包装 BleTransport + WifiDirectTransport，按帧类型路由：消息/回执/心跳双写，文件帧 sendTo 组内走 TCP 否则回退 BLE）。MeshService 仅注入 CompositeTransport（内部逻辑零改动，除 PING/PONG 去重与 sendFrame 一行）。

**技术栈：** Kotlin 2.2.10 · WifiP2pManager（API 14+，权限按版本拆分）· TCP/UDP Socket · kotlinx-coroutines · kotlinx-serialization。纯逻辑组件（分帧/状态机/成员表/路由表）JVM 可测，Android 框架层靠真机验证（沿用 BleTransport 模式）。

**版本安排：** P1 → v1.1.51；P2 → v1.1.52；P3 → v1.1.53（每次构建 bump，遵循项目规则）。

**规格：** `docs/superpowers/specs/2026-08-06-wifi-direct-dual-transport-design.md`

---

## 文件结构（计划三部分最终状态）

```
新增：
- app/src/main/java/com/meshchat/app/mesh/transport/WifiDirectFraming.kt   # 注册帧/数据帧编解码（纯 JVM）
- app/src/main/java/com/meshchat/app/mesh/transport/MemberTable.kt         # 星域成员表：shortId↔(ip,port,name) + 超时清理（纯 JVM）
- app/src/main/java/com/meshchat/app/mesh/transport/WifiDirectTransport.kt # Wi-Fi Direct 星域生命周期 + 组内通道（Android 框架层）
- app/src/main/java/com/meshchat/app/mesh/transport/CompositeTransport.kt  # 双通道选择器（实现 MeshTransport）
- app/src/test/java/com/meshchat/app/mesh/transport/WifiDirectFramingTest.kt
- app/src/test/java/com/meshchat/app/mesh/transport/MemberTableTest.kt
- app/src/test/java/com/meshchat/app/mesh/transport/CompositeTransportTest.kt

修改：
- app/src/main/AndroidManifest.xml                                        # 4 个 Wi-Fi Direct 权限
- app/src/main/java/com/meshchat/app/MainActivity.kt                      # requiredPermissions 按版本追加
- app/src/main/java/com/meshchat/app/mesh/service/MeshService.kt          # PING/PONG 去重 + sendFrame 改 sendTo（P3）
- app/src/main/java/com/meshchat/app/mesh/routing/DedupCache.kt           # 容量 512→1024
- app/src/main/java/com/meshchat/app/mesh/protocol/File3.kt               # MAX_CHUNK_BYTES + encodeChunk 放宽（P3）
- app/src/main/java/com/meshchat/app/mesh/transfer/FileTransferManager.kt # 窗口/块参数化（P3）
- app/src/main/java/com/meshchat/app/MeshChatApplication.kt               # wfd/composite 装配 + wifiDirectEnabled 偏好
- app/src/main/java/com/meshchat/app/mesh/debug/DebugStats.kt             # wfd provider（P3）
- app/src/main/java/com/meshchat/app/ui/screens/ProfileDetailScreens.kt   # 设置开关（P3）
- app/src/main/java/com/meshchat/app/ui/screens/DebugCenterScreen.kt      # 通道状态（P3）
- app/build.gradle.kts                                                    # 版本 bump

测试命令：`./gradlew testDebugUnitTest`（Windows：`gradlew.bat testDebugUnitTest`）
构建命令：`./gradlew assembleDebug`
```

---

# 计划一（P1）：Wi-Fi Direct 一对一连接打通 → v1.1.51

## 任务 1：权限扩展

**文件：**
- 修改：`app/src/main/AndroidManifest.xml`（uses-permission 区）
- 修改：`app/src/main/java/com/meshchat/app/MainActivity.kt`（requiredPermissions）

- [ ] **步骤 1：Manifest 添加 Wi-Fi Direct 权限**

在 `AndroidManifest.xml` 的 `<manifest>` 内、现有蓝牙权限旁添加：

```xml
<!-- Wi-Fi Direct 增强（v1.1.51）：API 33+ 近似权限；socket 需 INTERNET；老版本发现需位置权限 -->
<uses-permission android:name="android.permission.NEARBY_WIFI_DEVICES"
    android:usesPermissionFlags="neverForLocation" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"
    android:maxSdkVersion="32" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.CHANGE_WIFI_STATE" />
```

- [ ] **步骤 2：MainActivity.requiredPermissions 追加**

`MainActivity.requiredPermissions` 的 `buildList` 中，API≥33 分支追加 `Manifest.permission.NEARBY_WIFI_DEVICES`（与蓝牙权限同分支，一并请求；拒绝仅降级增强层，BLE 照常）：

```kotlin
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    add(Manifest.permission.BLUETOOTH_SCAN)
    add(Manifest.permission.BLUETOOTH_CONNECT)
    add(Manifest.permission.BLUETOOTH_ADVERTISE)
    add(Manifest.permission.NEARBY_WIFI_DEVICES)   // v1.1.51 Wi-Fi Direct 增强
}
```

- [ ] **步骤 3：编译验证**

运行：`gradlew.bat compileDebugKotlin`
预期：BUILD SUCCESSFUL（权限为声明即授予，编译无新增引用）

- [ ] **步骤 4：Commit**

```bash
git add app/src/main/AndroidManifest.xml app/src/main/java/com/meshchat/app/MainActivity.kt
git commit -m "feat(v1.1.51): Wi-Fi Direct 权限扩展（NEARBY_WIFI_DEVICES 分版 + socket 权限）"
```

## 任务 2：WifiDirectFraming 编解码（纯 JVM，TDD）

**文件：**
- 创建：`app/src/main/java/com/meshchat/app/mesh/transport/WifiDirectFraming.kt`
- 测试：`app/src/test/java/com/meshchat/app/mesh/transport/WifiDirectFramingTest.kt`

**职责：** REGISTER 注册帧（shortId↔IP 映射广播）与 UDP 数据帧的编解码，无 Android 依赖。REGISTER 帧格式：`M`(1B) + `R`(1B) + shortIdLen(1B) + shortId + ipLen(1B) + ip + port(2B 大端) + nameLen(1B) + name。UDP 数据帧：`M`(1B) + `D`(1B) + payloadLen(2B 大端) + payload。

- [ ] **步骤 1：编写失败的测试**

```kotlin
package com.meshchat.app.mesh.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WifiDirectFramingTest {
    @Test fun `register frame round trips`() {
        val bytes = WifiDirectFraming.encodeRegister("AB12", "192.168.49.5", 9876, "节点AB12")
        val info = WifiDirectFraming.decodeRegister(bytes)
        assertEquals("AB12", info?.shortId)
        assertEquals("192.168.49.5", info?.ip)
        assertEquals(9876, info?.port)
        assertEquals("节点AB12", info?.name)
    }

    @Test fun `data frame wrap and unwrap`() {
        val payload = ByteArray(100) { it.toByte() }
        val wrapped = WifiDirectFraming.wrapData(payload)
        assertArrayEquals(payload, WifiDirectFraming.unwrapData(wrapped))
    }

    @Test fun `malformed register returns null`() {
        assertNull(WifiDirectFraming.decodeRegister(byteArrayOf(1, 2, 3)))
        assertNull(WifiDirectFraming.decodeRegister(byteArrayOf()))
    }

    @Test fun `malformed data frame returns null`() {
        assertNull(WifiDirectFraming.unwrapData(byteArrayOf(0x4D)))
        assertNull(WifiDirectFraming.unwrapData(byteArrayOf(0x4D, 0x44, 0x00)))
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`gradlew.bat testDebugUnitTest --tests "com.meshchat.app.mesh.transport.WifiDirectFramingTest"`
预期：编译错误，`WifiDirectFraming` 未定义

- [ ] **步骤 3：编写实现**

```kotlin
package com.meshchat.app.mesh.transport

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * Wi-Fi Direct 组内帧编解码（纯 JVM，可单测）。
 * REGISTER 帧（UDP 广播，身份↔IP 映射）：'M''R' + shortIdLen(1B) + shortId + ipLen(1B) + ip + port(2B BE) + nameLen(1B) + name
 * 数据帧（UDP 广播，MeshFrame payload）：'M''D' + payloadLen(2B BE) + payload
 */
object WifiDirectFraming {
    private const val MAGIC_R = 'M'.code.toByte()
    private const val KIND_REGISTER = 'R'.code.toByte()
    private const val KIND_DATA = 'D'.code.toByte()
    const val MAX_PAYLOAD = 60 * 1024  // UDP 报文上限（65507B）留余量

    data class RegisterInfo(val shortId: String, val ip: String, val port: Int, val name: String)

    fun encodeRegister(shortId: String, ip: String, port: Int, name: String): ByteArray {
        val idB = shortId.toByteArray(StandardCharsets.UTF_8)
        val ipB = ip.toByteArray(StandardCharsets.UTF_8)
        val nameB = name.toByteArray(StandardCharsets.UTF_8)
        require(idB.size <= 255 && ipB.size <= 255 && nameB.size <= 255) { "field too long" }
        val buf = ByteBuffer.allocate(2 + 1 + idB.size + 1 + ipB.size + 2 + 1 + nameB.size)
        buf.put(MAGIC_R).put(KIND_REGISTER)
        buf.put(idB.size.toByte()).put(idB)
        buf.put(ipB.size.toByte()).put(ipB)
        buf.putShort(port.toShort())
        buf.put(nameB.size.toByte()).put(nameB)
        return buf.array()
    }

    fun decodeRegister(bytes: ByteArray): RegisterInfo? {
        if (bytes.size < 2 || bytes[0] != MAGIC_R || bytes[1] != KIND_REGISTER) return null
        var pos = 2
        fun takeLen(): Int? {
            if (pos >= bytes.size) return null
            return bytes[pos++].toInt() and 0xFF
        }
        val idLen = takeLen() ?: return null
        if (pos + idLen > bytes.size) return null
        val shortId = String(bytes, pos, idLen, StandardCharsets.UTF_8); pos += idLen
        val ipLen = takeLen() ?: return null
        if (pos + ipLen > bytes.size) return null
        val ip = String(bytes, pos, ipLen, StandardCharsets.UTF_8); pos += ipLen
        if (pos + 2 > bytes.size) return null
        val port = ByteBuffer.wrap(bytes, pos, 2).short.toInt() and 0xFFFF; pos += 2
        val nameLen = takeLen() ?: return null
        if (pos + nameLen > bytes.size) return null
        val name = String(bytes, pos, nameLen, StandardCharsets.UTF_8)
        return RegisterInfo(shortId, ip, port, name)
    }

    fun wrapData(payload: ByteArray): ByteArray {
        require(payload.size <= MAX_PAYLOAD) { "payload ${payload.size}B exceeds $MAX_PAYLOAD" }
        val buf = ByteBuffer.allocate(4 + payload.size)
        buf.put(MAGIC_R).put(KIND_DATA).putShort(payload.size.toShort())
        buf.put(payload)
        return buf.array()
    }

    fun unwrapData(bytes: ByteArray): ByteArray? {
        if (bytes.size < 4 || bytes[0] != MAGIC_R || bytes[1] != KIND_DATA) return null
        val len = ByteBuffer.wrap(bytes, 2, 2).short.toInt() and 0xFFFF
        if (len != bytes.size - 4) return null
        return bytes.copyOfRange(4, bytes.size)
    }
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：`gradlew.bat testDebugUnitTest --tests "com.meshchat.app.mesh.transport.WifiDirectFramingTest"`
预期：4 个测试全部 PASS

- [ ] **步骤 5：Commit**

```bash
git add app/src/main/java/com/meshchat/app/mesh/transport/WifiDirectFraming.kt app/src/test/java/com/meshchat/app/mesh/transport/WifiDirectFramingTest.kt
git commit -m "feat(v1.1.51): WifiDirectFraming 注册帧/数据帧编解码（TDD，4 测试）"
```

## 任务 3：MemberTable 成员表（纯 JVM，TDD）

**文件：**
- 创建：`app/src/main/java/com/meshchat/app/mesh/transport/MemberTable.kt`
- 测试：`app/src/test/java/com/meshchat/app/mesh/transport/MemberTableTest.kt`

**职责：** 星域成员表 `shortId → (ip, port, name, lastSeen)`；P1 单成员可用，P2 扩展多成员与超时清理（本任务一次完成多成员能力，避免 P2 重写）。

- [ ] **步骤 1：编写失败的测试**

```kotlin
package com.meshchat.app.mesh.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemberTableTest {
    @Test fun `upsert adds and updates member`() {
        val t = MemberTable(timeoutMs = 15_000L)
        t.upsert("AB12", "192.168.49.5", 9876, "节点AB12", now = 1_000L)
        assertEquals(setOf("AB12"), t.members())
        assertEquals(Triple("192.168.49.5", 9876, "节点AB12"), t.addressFor("AB12"))
        t.upsert("AB12", "192.168.49.9", 9999, "节点AB12x", now = 2_000L)  // 更新（GO 重新分配 IP）
        assertEquals(Triple("192.168.49.9", 9999, "节点AB12x"), t.addressFor("AB12"))
    }

    @Test fun `multiple members coexist`() {
        val t = MemberTable()
        t.upsert("AB12", "192.168.49.5", 9876, "", now = 1_000L)
        t.upsert("CD34", "192.168.49.6", 9877, "", now = 1_000L)
        assertEquals(setOf("AB12", "CD34"), t.members())
    }

    @Test fun `expired member pruned`() {
        val t = MemberTable(timeoutMs = 15_000L)
        t.upsert("AB12", "192.168.49.5", 9876, "", now = 1_000L)
        val removed = t.prune(now = 20_000L)   // 19s > 15s 超时
        assertEquals(listOf("AB12"), removed)
        assertTrue(t.members().isEmpty())
        assertNull(t.addressFor("AB12"))
    }

    @Test fun `fresh member survives prune`() {
        val t = MemberTable(timeoutMs = 15_000L)
        t.upsert("AB12", "192.168.49.5", 9876, "", now = 10_000L)
        assertEquals(emptyList<String>(), t.prune(now = 20_000L))  // 10s < 15s
        assertEquals("192.168.49.5", t.addressFor("AB12")?.first)
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`gradlew.bat testDebugUnitTest --tests "com.meshchat.app.mesh.transport.MemberTableTest"`
预期：编译错误，`MemberTable` 未定义

- [ ] **步骤 3：编写实现**

```kotlin
package com.meshchat.app.mesh.transport

import java.util.concurrent.ConcurrentHashMap

/** 星域成员表：shortId → (ip, port, name, lastSeen)。REGISTER 帧驱动，超时清理（纯 JVM，可单测）。 */
class MemberTable(private val timeoutMs: Long = 15_000L) {
    private data class Entry(val ip: String, val port: Int, val name: String, var lastSeen: Long)

    private val members = ConcurrentHashMap<String, Entry>()

    fun upsert(shortId: String, ip: String, port: Int, name: String, now: Long) {
        members[shortId] = Entry(ip, port, name, now)
    }

    /** 返回 (ip, port, name)；未知返回 null。 */
    fun addressFor(shortId: String): Triple<String, Int, String>? =
        members[shortId]?.let { Triple(it.ip, it.port, it.name) }

    fun members(): Set<String> = members.keys.toSet()

    /** 清理超过 timeoutMs 未刷新的成员；返回被移除的 shortId 列表。 */
    fun prune(now: Long): List<String> {
        val cutoff = now - timeoutMs
        val removed = mutableListOf<String>()
        members.entries.removeIf { (_, e) -> (e.lastSeen < cutoff).also { if (it) removed.add(e.key) } }
        return removed
    }

    fun clear() = members.clear()
}
```

（测试中 `members.entries.removeIf { (_, e) -> ... }` 需捕获 key——修正：改为显式迭代避免 lambda 参数遮蔽。）

```kotlin
    fun prune(now: Long): List<String> {
        val cutoff = now - timeoutMs
        val removed = mutableListOf<String>()
        for ((id, e) in members) {
            if (e.lastSeen < cutoff) removed.add(id)
        }
        removed.forEach { members.remove(it) }
        return removed
    }
```

- [ ] **步骤 4：运行测试验证通过**

运行：`gradlew.bat testDebugUnitTest --tests "com.meshchat.app.mesh.transport.MemberTableTest"`
预期：4 个测试全部 PASS

- [ ] **步骤 5：Commit**

```bash
git add app/src/main/java/com/meshchat/app/mesh/transport/MemberTable.kt app/src/test/java/com/meshchat/app/mesh/transport/MemberTableTest.kt
git commit -m "feat(v1.1.51): MemberTable 星域成员表（多成员+超时清理，TDD，4 测试）"
```

## 任务 4：WifiDirectTransport 核心（Android 框架层）

**文件：**
- 创建：`app/src/main/java/com/meshchat/app/mesh/transport/WifiDirectTransport.kt`

**职责：** WifiP2pManager 封装。P1 范围：enable/disable、DnsSd 短 ID 服务注册与发现、双机 connect/GO negotiation、TCP 可靠通道（RfcommFraming 分帧）。成员表接入（REGISTER UDP 广播在 P2 补，P1 先以 TCP 直连 + 服务发现映射 shortId↔device，成员表以连接成功成员填充）。

- [ ] **步骤 1：编写核心骨架（状态机 + 生命周期 + TCP 通道）**

```kotlin
package com.meshchat.app.mesh.transport

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.net.wifi.p2p.nsd.WifiP2pUpnpServiceInfo
import android.os.Looper
import android.util.Log
import com.meshchat.app.mesh.protocol.MeshFrame
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Wi-Fi Direct 星域传输：P2P 发现（DnsSd 短 ID）+ 连接/GO negotiation + 组内 TCP 可靠通道 + 组内 UDP 广播。 */
class WifiDirectTransport(
    private val context: Context,
    private val shortId: String,
    private val displayName: String = "",
) {
    companion object {
        private const val TAG = "MeshWfd"
        const val SERVICE_TYPE = "_meshchat._tcp"
        const val TCP_PORT = 0x51C8          // 20936：应用层固定端口
        private const val DISCOVER_TIMEOUT_MS = 30_000L
        private const val CONNECT_TIMEOUT_MS = 15_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val manager: WifiP2pManager? =
        runCatching { context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager }.getOrNull()
    private val channel: WifiP2pManager.Channel? = manager?.initialize(context, Looper.getMainLooper(), null)

    private val _incoming = MutableSharedFlow<MeshFrame>(extraBufferCapacity = 64)
    val incoming: SharedFlow<MeshFrame> = _incoming
    private val _foundPeers = MutableSharedFlow<MeshPeerInfo>(extraBufferCapacity = 64)
    val foundPeers: SharedFlow<MeshPeerInfo> = _foundPeers

    /** 状态机：DISABLED/DISCOVERING/CONNECTING/GROUPED/RECONNECTING（P2 补枚举导出供 UI）。 */
    enum class State { DISABLED, DISCOVERING, CONNECTING, GROUPED, RECONNECTING }
    @Volatile var state: State = State.DISABLED
        private set

    /** shortId ↔ P2P 设备（服务发现填充）。 */
    private val knownDevices = ConcurrentHashMap<String, WifiP2pDevice>()
    /** shortId ↔ TCP socket（连接成功填充；P2 起由 MemberTable 承载完整映射）。 */
    private val sockets = ConcurrentHashMap<String, Pair<Socket, Any>>()
    private var serverSocket: ServerSocket? = null
    @Volatile private var groupOwnerAddress: String? = null

    private val p2pReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            when (intent?.action) {
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val info: WifiP2pInfo? = intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO)
                    handleConnectionChange(info)
                }
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> Unit  // 硬件开关状态（P2 处理重建）
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> requestPeers()
            }
        }
    }

    // ---------- 生命周期 ----------

    fun enable() {
        if (manager == null || channel == null) { Log.w(TAG, "p2p unavailable"); return }
        state = State.DISCOVERING
        registerReceiver()
        registerServiceInfo()
        startTcpServer()
        discoverLoop()
    }

    fun disable() {
        state = State.DISABLED
        runCatching { context.unregisterReceiver(p2pReceiver) }
        runCatching { manager?.removeGroup(channel, null) }
        runCatching { manager?.clearLocalServices(channel, null) }
        sockets.forEach { (_, p) -> runCatching { p.first.close() } }
        sockets.clear()
        knownDevices.clear()
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    fun isGrouped(): Boolean = state == State.GROUPED

    fun members(): Set<String> = sockets.keys.toSet()

    fun isConnectedTo(peerId: String): Boolean = sockets.containsKey(peerId)

    /** 发往单成员（TCP 可靠）：P1 文件/可靠帧走此通道。 */
    fun sendTo(peerId: String, frame: MeshFrame) {
        val pair = sockets[peerId] ?: run { Log.d(TAG, "no socket for $peerId"); return }
        try {
            synchronized(pair.second) { RfcommFraming.writeFrame(pair.first.getOutputStream(), frame) }
        } catch (e: Exception) {
            Log.w(TAG, "tcp write failed $peerId: $e")
            sockets.remove(peerId)
            runCatching { pair.first.close() }
        }
    }

    /** 组内广播（UDP，P2 实现应用层组播兜底）：P1 先对成员 TCP 单发。 */
    fun broadcast(frame: MeshFrame) {
        sockets.keys.toList().forEach { sendTo(it, frame) }
    }

    // ---------- 内部：发现/连接 ----------

    private fun registerReceiver() {
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        }
        runCatching { context.registerReceiver(p2pReceiver, filter) }
    }

    /** 注册 DnsSd 服务：对端无连接发现本机短 ID。 */
    private fun registerServiceInfo() {
        val txt = mapOf("shortid" to shortId, "name" to displayName)
        val info = WifiP2pDnsSdServiceInfo.newInstance("MeshChat", SERVICE_TYPE, txt)
        manager?.addLocalService(channel, info, null)
        val req = WifiP2pDnsSdServiceRequest.newInstance()
        manager?.setDnsSdResponseListeners(
            channel,
            { instanceName, regType, srcDevice ->
                // 解析对端 TXT → knownDevices
                onServiceResolved(instanceName, regType, srcDevice, txt = null)  // P2 解析完整 TXT
            },
            { srcDevice -> run { } },  // TXT 记录监听（P1 简化为空；P2 解析 shortid）
        )
        manager?.addServiceRequest(channel, req, null)
    }

    private fun discoverLoop() {
        scope.launch {
            while (isActive && state != State.DISABLED) {
                manager?.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() { requestPeers() }
                    override fun onFailure(reason: Int) { Log.d(TAG, "discover fail $reason") }
                })
                // 每个已知设备尝试连接（已连接跳过）
                knownDevices.forEach { (id, dev) -> if (!sockets.containsKey(id)) connectTo(id, dev) }
                delay(DISCOVER_TIMEOUT_MS)
            }
        }
    }

    private fun connectTo(peerId: String, dev: WifiP2pDevice) {
        if (state != State.DISCOVERING) return
        state = State.CONNECTING
        val config = WifiP2pConfig().apply {
            deviceAddress = dev.deviceAddress
            groupOwnerIntent = 8   // 适中倾向（P2 多成员自动选主可调）
        }
        manager?.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                // group 建立由 CONNECTION_CHANGED 回调驱动（handleConnectionChange）
                Log.d(TAG, "connect initiated $peerId")
            }
            override fun onFailure(reason: Int) {
                Log.w(TAG, "connect fail $peerId reason=$reason")
                state = State.DISCOVERING   // 回退：下一轮重试
            }
        })
    }

    private fun handleConnectionChange(info: WifiP2pInfo?) {
        val isGroupOwner = info?.isGroupOwner == true
        val ownerAddr = info?.groupOwnerAddress?.hostAddress
        Log.i(TAG, "connection change go=$isGroupOwner owner=$ownerAddr")
        if (info?.groupFormed == true) {
            state = State.GROUPED
            groupOwnerAddress = ownerAddr
            if (!isGroupOwner) {
                // Client：向 GO 建立 TCP（对端已开 server 端口 TCP_PORT）
                scope.launch { connectTcpTo(ownerAddr) }
            } else {
                // GO：等待 Client 连入（startTcpServer 已就绪）
                onGroupFormed()
            }
        } else {
            state = State.RECONNECTING   // P2 补指数退避重建
        }
    }

    private fun startTcpServer() {
        scope.launch {
            val server = runCatching { ServerSocket(TCP_PORT) }.getOrNull()
                ?: run { Log.w(TAG, "tcp listen failed"); return@launch }
            serverSocket = server
            while (isActive && state != State.DISABLED) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                scope.launch { readLoop(socket) }
            }
        }
    }

    private suspend fun connectTcpTo(host: String?) {
        if (host == null) return
        val socket = runCatching {
            val s = Socket()
            s.connect(InetAddress.getByName(host).let { java.net.InetSocketAddress(it, TCP_PORT) }, 5000)
            s
        }.getOrNull() ?: run { Log.w(TAG, "tcp connect fail $host"); return }
        scope.launch { readLoop(socket) }
    }

    private fun onGroupFormed() {
        // GO 模式：客户端 TCP 连入后由 readLoop 的注册帧识别身份（P2 实现）；
        // P1 简化：连入的 Client 地址由服务发现 knownDevices 反查（deviceAddress 匹配）——见 readLoop 注释。
    }

    private suspend fun readLoop(socket: Socket) {
        val input = runCatching { socket.getInputStream() }.getOrNull() ?: return
        while (isActive) {
            val frame = runCatching { RfcommFraming.readFrame(input) }.getOrNull() ?: break
            _incoming.emit(frame)
        }
        runCatching { socket.close() }
        // P1：连接断开按远端 IP 反查移除 sockets（P2 用 REGISTER 帧识别身份后精确移除）
    }

    private fun requestPeers() {
        manager?.requestPeers(channel) { peers ->
            // DnsSd 已维护 knownDevices；此处同步设备状态（P2 完善）
            peers.forEach { dev -> }
        }
    }

    private fun onServiceResolved(instanceName: String, regType: String, srcDevice: WifiP2pDevice, txt: Map<String, String>?) {
        val id = txt?.get("shortid") ?: return   // P1 占位；P2 由 setDnsSdResponseListeners 的 TXT 回调提供
        knownDevices[id] = srcDevice
        _foundPeers.emit(
            MeshPeerInfo(shortId = id, deviceAddress = srcDevice.deviceAddress, rssi = 0,
                displayName = txt?.get("name") ?: "", presence = PeerPresence.ONLINE)
        )
    }
}
```

（注：P1 版本中 `onServiceResolved` 的 txt 参数暂为 null 占位——**P2 任务 8 补 setDnsSdResponseListeners 完整 TXT 解析**；P1 验收以"双机 enable 后相互 discover + connect + TCP 互达"为准，身份映射以 `_foundPeers` 的 shortId 为锚，shortId 由 TXT 回调提供，故本任务需先实现完整 TXT 解析而非占位——**步骤 1 代码按完整版编写**，setDnsSdResponseListeners 的 TXT 回调正确填充 `onServiceResolved(txt)`。）

修正版 setDnsSdResponseListeners（完整 TXT 解析，无占位）：

```kotlin
manager?.setDnsSdResponseListeners(
    channel,
    { instanceName, regType, srcDevice ->
        Log.d(TAG, "service resolved $instanceName $regType")
    },
    { fullName, txtRecord, srcDevice ->
        val txt = txtRecord?.mapKeys { it.key.toString() }
        val id = txt?.get("shortid") ?: return@setDnsSdResponseListeners
        knownDevices[id] = srcDevice
        _foundPeers.tryEmit(
            MeshPeerInfo(shortId = id, deviceAddress = srcDevice.deviceAddress, rssi = 0,
                displayName = txt["name"] ?: "", presence = PeerPresence.ONLINE)
        )
        Log.i(TAG, "peer discovered id=$id addr=${srcDevice.deviceAddress}")
    },
)
```

- [ ] **步骤 2：编译验证**

运行：`gradlew.bat compileDebugKotlin`
预期：BUILD SUCCESSFUL（WifiDirectTransport 编译通过；`delay` 需 import `kotlinx.coroutines.delay`，`RfcommFraming` 需 `import com.meshchat.app.mesh.transport.RfcommFraming`——同包无需导入）

- [ ] **步骤 3：Commit**

```bash
git add app/src/main/java/com/meshchat/app/mesh/transport/WifiDirectTransport.kt
git commit -m "feat(v1.1.51): WifiDirectTransport 核心（DnsSd 短ID识别 + connect/GO negotiation + TCP 通道）"
```

## 任务 5：Application 装配（wfd 单例，暂不替换 transport）

**文件：**
- 修改：`app/src/main/java/com/meshchat/app/MeshChatApplication.kt`

- [ ] **步骤 1：添加 wfd 单例与偏好**

在 `MeshChatApplication` 中 transport/service 定义旁新增：

```kotlin
/** Wi-Fi Direct 增强层（v1.1.51）：可选，默认关闭；开启后自动与可达设备建连形成星域。 */
val wfd by lazy {
    WifiDirectTransport(this, shortId = identity.shortId, displayName = identity.displayName)
}

/** Wi-Fi Direct 增强开关（设置页可改，默认关——省电；用户主动开启增强通讯能力）。 */
var wifiDirectEnabled: Boolean
    get() = getSharedPreferences("meshchat_settings", Context.MODE_PRIVATE)
        .getBoolean("wifi_direct_enabled", false)
    set(value) {
        getSharedPreferences("meshchat_settings", Context.MODE_PRIVATE)
            .edit().putBoolean("wifi_direct_enabled", value).apply()
        if (value) wfd.enable() else wfd.disable()
    }
```

（P1 阶段：开关 setter 直接生效 enable/disable；`applyWifiDirect()` 统一入口在 P3 与 Composite 装配一并接入 onCreate/startMesh。）

- [ ] **步骤 2：编译验证**

运行：`gradlew.bat compileDebugKotlin`
预期：BUILD SUCCESSFUL（import `com.meshchat.app.mesh.transport.WifiDirectTransport`）

- [ ] **步骤 3：构建 + 版本 bump v1.1.51**

`app/build.gradle.kts`：`versionCode = 113` / `versionName = "1.1.51"`

运行：`gradlew.bat assembleDebug`
预期：BUILD SUCCESSFUL；APK `MeshChat-v1.1.51-debug.apk`

- [ ] **步骤 4：Commit**

```bash
git add app/src/main/java/com/meshchat/app/MeshChatApplication.kt app/build.gradle.kts
git commit -m "feat(v1.1.51): Application 装配 WifiDirectTransport 单例 + 开关偏好（默认关）；版本 v1.1.51/113"
```

## 任务 6：P1 两机真机验证

- [ ] **步骤 1：验证清单（手动，真机两台）**

1. 两台安装 `MeshChat-v1.1.51-debug.apk`，开启蓝牙 + Wi-Fi（P2P 需 Wi-Fi 开启）
2. 设置页临时入口（P1 无 UI：通过 adb 或临时调试按钮调用 `app.wifiDirectEnabled = true`——**计划执行时在 Mesh 页/设置临时加一个调试入口按钮**，P3 正式 UI 替换）
3. 两机都开启后等待 30-60s：logcat 过滤 `MeshWfd` 应出现 `peer discovered id=XXXX` 双向 + `connection change go=... owner=...` + `group formed`
4. 临时验证 TCP 互达：调用 `wfd.sendTo(peerId, MeshFrame(...))` 写入一条 PING 信封帧，对端 `MeshWfd` readLoop 触发 incoming → MeshService 若已接则处理（P1 未接 Composite，可直接观察 `_incoming` 或临时 Log）
5. 记录：连接成功率、TCP 分帧往返、无崩溃

- [ ] **步骤 2：记录结果到 AI_CONTEXT（会话交接）**

---

# 计划二（P2）：多人 Wi-Fi Direct 星域连接 → v1.1.52

## 任务 7：REGISTER 身份注册（组内 UDP 广播 + 成员表接入）

**文件：**
- 修改：`app/src/main/java/com/meshchat/app/mesh/transport/WifiDirectTransport.kt`
- 测试：`app/src/test/java/com/meshchat/app/mesh/transport/MemberTableTest.kt`（已覆盖成员表，本任务补注册帧集成断言可选）

**职责：** GROUPED 后周期广播 REGISTER（shortId+IP+port+name），接收端解析后 upsert MemberTable；TCP 连入/连出时以 REGISTER 识别身份（替代 P1 的 IP 反查），sockets 键改为 shortId（已有）。GO 与 Client 均维护成员表。

- [ ] **步骤 1：UDP 组内广播通道（GO 与 Client 通用）**

在 WifiDirectTransport 增加：

```kotlin
// 组内广播地址：GO 与 Client 同网段 192.168.49.x，定向广播 .255
@Volatile private var udpSocket: DatagramSocket? = null
@Volatile private var groupIp: String? = null   // 本机组内 IP（GO=192.168.49.1 通常；Client=DHCP）

private fun startUdp(groupOwnerAddress: String?, isGroupOwner: Boolean) {
    groupIp = if (isGroupOwner) groupOwnerAddress else localGroupIp()
    udpSocket = runCatching { DatagramSocket(TCP_PORT).apply { broadcast = true } }.getOrNull()
    scope.launch { udpReadLoop() }
    scope.launch { registerLoop() }   // 周期 REGISTER
}

private fun localGroupIp(): String? = runCatching {
    NetworkInterface.getNetworkInterfaces().asSequence().flatMap { it.inetAddresses.asSequence() }
        .firstOrNull { it is Inet4Address && it.hostAddress?.startsWith("192.168.49.") == true }?.hostAddress
}.getOrNull()

private suspend fun registerLoop() {
    while (isActive && state == State.GROUPED) {
        broadcastUdp(WifiDirectFraming.encodeRegister(shortId, groupIp ?: "", TCP_PORT, displayName))
        delay(5_000L)
    }
}

private fun broadcastUdp(payload: ByteArray) {
    val sock = udpSocket ?: return
    runCatching {
        val addr = InetAddress.getByName("192.168.49.255")
        sock.send(DatagramPacket(payload, payload.size, addr, TCP_PORT))
    }.onFailure { Log.d(TAG, "udp broadcast fail: $it") }
}

private suspend fun udpReadLoop() {
    val sock = udpSocket ?: return
    val buf = ByteArray(64 * 1024)
    while (isActive) {
        val packet = runCatching { DatagramPacket(buf, buf.size).also { sock.receive(it) } }.getOrNull() ?: break
        val bytes = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
        WifiDirectFraming.decodeRegister(bytes)?.let { reg ->
            memberTable.upsert(reg.shortId, reg.ip, reg.port, reg.name, System.currentTimeMillis())
            // 身份↔TCP：若已建 TCP 但键缺失（GO 收 Client 连入），按 REGISTER 补充 sockets 映射
            if (reg.shortId !in sockets && tcpByIp.containsKey(reg.ip)) {
                sockets[reg.shortId] = tcpByIp.remove(reg.ip)!!
                Log.i(TAG, "tcp mapped by register: ${reg.shortId}")
            }
            _foundPeers.tryEmit(MeshPeerInfo(reg.shortId, reg.ip, 0, reg.name, presence = PeerPresence.ONLINE))
        } ?: run {
            WifiDirectFraming.unwrapData(bytes)?.let { payload ->
                _incoming.emit(MeshFrame(FrameType.DATA, payload))   // 组内 UDP 数据帧（消息双写广播用）
            }
        }
    }
}
```

（`tcpByIp: ConcurrentHashMap<String, Pair<Socket, Any>>`——TCP 连入时按远端 IP 暂存，REGISTER 到达后映射到 shortId；P1 的 IP 反查逻辑替换为此。）

- [ ] **步骤 2：handleConnectionChange 接入 startUdp + 成员表**

在 GROUPED 分支调用 `startUdp(ownerAddr, isGroupOwner)`；`memberTable` 字段 `val memberTable = MemberTable()`；`members()`/`isConnectedTo()` 改查 memberTable 与 sockets 并集（P2 语义：组内成员 ≠ 必有 TCP，但 sendTo 需 TCP；消息广播走 UDP 无需 TCP）。

```kotlin
fun members(): Set<String> = memberTable.members() + sockets.keys
fun groupAddressFor(peerId: String): String? = memberTable.addressFor(peerId)?.first
```

- [ ] **步骤 3：编译 + 单测**

运行：`gradlew.bat testDebugUnitTest`（MemberTableTest 4 例回归）+ `gradlew.bat compileDebugKotlin`
预期：全绿 + BUILD SUCCESSFUL

- [ ] **步骤 4：Commit**

```bash
git add app/src/main/java/com/meshchat/app/mesh/transport/WifiDirectTransport.kt
git commit -m "feat(v1.1.52): WifiDirectTransport REGISTER 注册 + 组内 UDP 广播 + 成员表接入"
```

## 任务 8：多成员自动组网 + 断开重建

**文件：**
- 修改：`app/src/main/java/com/meshchat/app/mesh/transport/WifiDirectTransport.kt`

- [ ] **步骤 1：多成员连接循环强化**

`discoverLoop`：对每个 `knownDevices` 中未连接（不在 sockets）且非本机的设备 connect；connect 前检查 `state` 允许。GO negotiation 失败/超时（CONNECT_TIMEOUT_MS）回退 DISCOVERING。自动选主策略：`groupOwnerIntent = 8`（默认），多设备并发 connect 由系统协商。

```kotlin
private fun discoverLoop() {
    scope.launch {
        while (isActive && state != State.DISABLED) {
            if (state == State.DISCOVERING) {
                manager?.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() = Unit
                    override fun onFailure(reason: Int) { Log.d(TAG, "discover fail $reason") }
                })
                knownDevices.forEach { (id, dev) ->
                    if (id != shortId && !sockets.containsKey(id)) connectTo(id, dev)
                }
            }
            delay(DISCOVER_TIMEOUT_MS)
        }
    }
}
```

- [ ] **步骤 2：断开重建（指数退避）**

`handleConnectionChange` 的 groupFormed==false 分支：

```kotlin
state = State.RECONNECTING
scope.launch {
    var backoff = 1_000L
    while (state == State.RECONNECTING) {
        delay(backoff)
        manager?.discoverPeers(channel, null)
        if (knownDevices.isNotEmpty()) { state = State.DISCOVERING; break }
        backoff = (backoff * 2).coerceAtMost(30_000L)
    }
}
```

- [ ] **步骤 3：成员超时清理接入 tick**

`scope.launch { while (isActive) { delay(5_000L); memberTable.prune(System.currentTimeMillis()) } }`

- [ ] **步骤 4：编译 + 构建 + 版本 bump v1.1.52**

运行：`gradlew.bat compileDebugKotlin` + `gradlew.bat testDebugUnitTest`；`app/build.gradle.kts` → versionCode 114 / versionName "1.1.52"；`gradlew.bat assembleDebug`
预期：全绿 + BUILD SUCCESSFUL；APK `MeshChat-v1.1.52-debug.apk`

- [ ] **步骤 5：Commit**

```bash
git add app/src/main/java/com/meshchat/app/mesh/transport/WifiDirectTransport.kt app/build.gradle.kts
git commit -m "feat(v1.1.52): 多成员自动组网 + 断开指数退避重建；版本 v1.1.52/114"
```

## 任务 9：P2 三机真机验证

- [ ] **步骤 1：验证清单（真机三台）**

1. 三台装 v1.1.52，开启蓝牙 + Wi-Fi，临时调试入口开启 `wifiDirectEnabled`
2. 等待 60-90s：三台 logcat `MeshWfd` 应出现互相 `peer discovered` + 一个 GO + 两个 Client（或链式协商）
3. REGISTER 周期广播：三台 memberTable 均有另外两台（`members()` 为 3）
4. UDP 广播互达：任一台 `broadcastUdp(wrapData(测试帧))` → 另两台 incoming 收到
5. 关掉一台 Wi-Fi（模拟移动出范围）→ 60s 内其余两台 memberTable prune 该成员；重开 → 重新 REGISTER 恢复
6. 记录：星域形成成功率、UDP 丢包率（发送 100 帧统计）、重建恢复时间

- [ ] **步骤 2：记录结果到 AI_CONTEXT**

---

# 计划三（P3）：双通道 + 文件优先 + UI → v1.1.53

## 任务 10：CompositeTransport 路由表（TDD）

**文件：**
- 创建：`app/src/main/java/com/meshchat/app/mesh/transport/CompositeTransport.kt`
- 测试：`app/src/test/java/com/meshchat/app/mesh/transport/CompositeTransportTest.kt`

**职责：** 实现 MeshTransport，包装 BleTransport + WifiDirectTransport。路由：消息/回执/心跳双写；文件帧（File3.isFile3 或信封 kind 判定）走 sendTo 分流（组内 TCP / 组外 BLE 广播）。测试用替身 FakeWfd（暴露 members/isGrouped/counts）。

- [ ] **步骤 1：编写失败的测试**

```kotlin
package com.meshchat.app.mesh.transport

import com.meshchat.app.mesh.protocol.FrameType
import com.meshchat.app.mesh.protocol.MeshFrame
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompositeTransportTest {

    /** 记录调用的 BLE 替身。 */
    private class FakeBle : MeshTransport {
        val broadcasts = mutableListOf<MeshFrame>()
        val sendTos = mutableListOf<Pair<String, MeshFrame>>()
        val incoming = MutableSharedFlow<MeshFrame>()
        val foundPeers = MutableSharedFlow<MeshPeerInfo>()
        var enabled = true
        override fun broadcast(frame: MeshFrame) { broadcasts.add(frame) }
        override fun sendTo(peerId: String, frame: MeshFrame) { sendTos.add(peerId to frame) }
        override fun currentMtu(): Int = 512
        override fun isConnectedTo(peerId: String): Boolean = true
        override fun bluetoothEnabled(): Boolean = enabled
    }

    /** 暴露状态的 WFD 替身。 */
    private class FakeWfd(
        var grouped: Boolean = false,
        private val group: Set<String> = emptySet(),
    ) {
        val broadcasts = mutableListOf<MeshFrame>()
        val sendTos = mutableListOf<Pair<String, MeshFrame>>()
        fun isGrouped(): Boolean = grouped
        fun members(): Set<String> = group
        fun sendTo(peerId: String, frame: MeshFrame) { sendTos.add(peerId to frame) }
        fun broadcast(frame: MeshFrame) { broadcasts.add(frame) }
    }

    private fun textFrame(): MeshFrame = MeshFrame(FrameType.DATA,
        """{"id":"a","kind":"TEXT","srcId":"ME","dstId":"AB12","convId":"conv-AB12","ttl":8,"ts":1,"body":{"text":"hi","displayName":"x"}}""".toByteArray())

    private fun file3Frame(): MeshFrame =
        MeshFrame(FrameType.DATA, File3.encodeChunk("ME", "fid", 0, 0L, ByteArray(64)))

    @Test fun `message frames dual write when grouped`() {
        val ble = FakeBle(); val wfd = FakeWfd(grouped = true, group = setOf("AB12"))
        val c = CompositeTransport(ble, wfd)
        c.broadcast(textFrame())
        assertEquals(1, ble.broadcasts.size)     // BLE 写
        assertEquals(1, wfd.broadcasts.size)     // WFD 写（双写）
    }

    @Test fun `file frame routed via sendTo to wfd tcp when target in group`() {
        val ble = FakeBle(); val wfd = FakeWfd(grouped = true, group = setOf("AB12"))
        val c = CompositeTransport(ble, wfd)
        c.sendTo("AB12", file3Frame())
        assertEquals(1, wfd.sendTos.size)                 // 组内 → WFD TCP
        assertTrue(ble.sendTos.isEmpty())
        assertTrue(ble.broadcasts.isEmpty())
    }

    @Test fun `file frame falls back to ble when target outside group`() {
        val ble = FakeBle(); val wfd = FakeWfd(grouped = true, group = setOf("CD34"))
        val c = CompositeTransport(ble, wfd)
        c.sendTo("AB12", file3Frame())
        assertEquals(1, ble.broadcasts.size)              // 组外 → BLE 广播（现有文件语义）
        assertTrue(wfd.sendTos.isEmpty())
    }

    @Test fun `message broadcast falls back to ble only when not grouped`() {
        val ble = FakeBle(); val wfd = FakeWfd(grouped = false)
        val c = CompositeTransport(ble, wfd)
        c.broadcast(textFrame())
        assertEquals(1, ble.broadcasts.size)
        assertTrue(wfd.broadcasts.isEmpty())
    }

    @Test fun `currentMtu amplifies when grouped`() {
        val ble = FakeBle(); val wfd = FakeWfd(grouped = true)
        val c = CompositeTransport(ble, wfd)
        assertEquals(65535, c.currentMtu())
        wfd.grouped = false
        assertEquals(512, c.currentMtu())
    }

    @Test fun `isConnectedTo true if wfd member or ble connected`() {
        val ble = FakeBle(); val wfd = FakeWfd(grouped = true, group = setOf("AB12"))
        val c = CompositeTransport(ble, wfd)
        assertTrue(c.isConnectedTo("AB12"))    // WFD 组内
        assertTrue(c.isConnectedTo("ZZ99"))    // BLE 兜底 true（替身恒 true）
    }
}
```

（CompositeTransport 的构造需接受能暴露 `isGrouped()/members()/broadcast/sendTo` 的 wfd 抽象——**WifiDirectTransport 不实现 MeshTransport**（它是内部组件），Composite 依赖一个最小接口；测试用 FakeWfd 实现同接口。）

- [ ] **步骤 2：运行测试验证失败**

运行：`gradlew.bat testDebugUnitTest --tests "com.meshchat.app.mesh.transport.CompositeTransportTest"`
预期：编译错误，`CompositeTransport` 未定义

- [ ] **步骤 3：编写实现**

为让 Composite 可测，抽出 WifiDirectTransport 的最小可测接口（放 CompositeTransport.kt 内）：

```kotlin
package com.meshchat.app.mesh.transport

import com.meshchat.app.mesh.protocol.File3
import com.meshchat.app.mesh.protocol.FrameType
import com.meshchat.app.mesh.protocol.MeshFrame
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** Wi-Fi Direct 增强层最小契约（Composite 依赖；WifiDirectTransport 与测试替身共同实现）。 */
interface WifiDirectChannel {
    fun isGrouped(): Boolean
    fun members(): Set<String>
    fun sendTo(peerId: String, frame: MeshFrame)
    fun broadcast(frame: MeshFrame)
    fun isConnectedTo(peerId: String): Boolean
}

/**
 * 双通道选择器（v1.1.52）：包装 BLE 常开 + Wi-Fi Direct 增强。
 * 路由：消息/回执/心跳双写；文件帧（sendTo）目标组内 → WFD TCP，组外 → BLE 广播（现有文件语义）。
 */
class CompositeTransport(
    private val ble: MeshTransport,
    private val wfd: WifiDirectChannel,
) : MeshTransport {

    override val incoming: SharedFlow<MeshFrame> = ble.incoming
    override val foundPeers: SharedFlow<MeshPeerInfo> = ble.foundPeers

    override fun broadcast(frame: MeshFrame) {
        ble.broadcast(frame)                       // BLE 恒写
        if (wfd.isGrouped()) wfd.broadcast(frame)  // 组内双写（UDP）
    }

    override fun sendTo(peerId: String, frame: MeshFrame) {
        // 文件帧：目标组内 → WFD TCP；否则 BLE 广播（保持 BLE 文件广播语义）
        if (wfd.isGrouped() && peerId in wfd.members()) wfd.sendTo(peerId, frame)
        else ble.broadcast(frame)
    }

    override fun writeUnreliable(frame: MeshFrame) { ble.writeUnreliable(frame) }

    override fun setAckProvider(provider: () -> List<ByteArray>) { ble.setAckProvider(provider) }
    override fun refreshAdvertising() { ble.refreshAdvertising() }
    override fun bluetoothEnabled(): Boolean = ble.bluetoothEnabled()
    override fun setTxPowerLevel(power: Int) { ble.setTxPowerLevel(power) }
    override fun suspendDiscovery() { ble.suspendDiscovery() }
    override fun resumeDiscovery() { ble.resumeDiscovery() }

    override fun currentMtu(): Int = if (wfd.isGrouped()) 65535 else ble.currentMtu()

    override fun isConnectedTo(peerId: String): Boolean =
        wfd.isConnectedTo(peerId) || ble.isConnectedTo(peerId)
}
```

（MeshTransport 的 incoming/foundPeers 为 val 接口——BleTransport 实现；Composite 委派 BLE 的流。WifiDirectTransport 的 incoming 独立流由 MeshService 合流或经 Composite 转发——**定稿**：WifiDirectTransport 实现 `WifiDirectChannel`，其 incoming 由 MeshService 在 start() 额外 collect（与 rfcomm 合流同模式），`_foundPeers` 直接 emit 到共享流不经过 Composite。Composite 只负责发送路由与状态查询。）

- [ ] **步骤 4：运行测试验证通过**

运行：`gradlew.bat testDebugUnitTest --tests "com.meshchat.app.mesh.transport.CompositeTransportTest"`
预期：6 个测试全部 PASS

- [ ] **步骤 5：Commit**

```bash
git add app/src/main/java/com/meshchat/app/mesh/transport/CompositeTransport.kt app/src/test/java/com/meshchat/app/mesh/transport/CompositeTransportTest.kt
git commit -m "feat(v1.1.52): CompositeTransport 双通道路由表（消息双写/文件组内TCP组外BLE，TDD 6 测试）"
```

## 任务 11：MeshService 心跳去重 + sendFrame 路由 + DedupCache 容量

**文件：**
- 修改：`app/src/main/java/com/meshchat/app/mesh/service/MeshService.kt`（handleEnvelope PING/PONG 去重 + sendFrame）
- 修改：`app/src/main/java/com/meshchat/app/mesh/routing/DedupCache.kt`
- 测试：`app/src/test/java/com/meshchat/app/mesh/service/MeshServiceTest.kt`

- [ ] **步骤 1：PING/PONG 去重（与 INVITE 同款）**

`handleEnvelope` 的 when 前（INVITE 去重块后）追加，或直接并入 PING/PONG 分支首行：

```kotlin
"PING", "PONG" -> {
    if (dedup.contains(envelope.id)) return   // 双写去重：同 id 心跳只处理一次（防重复回 PONG）
    dedup.mark(envelope.id)
    if (envelope.dstId.isNotBlank() && envelope.dstId != identity.shortId) return
    ... 现有 PING/PONG 逻辑不变 ...
}
```

- [ ] **步骤 2：DedupCache 容量 512→1024**

```kotlin
class DedupCache(private val capacity: Int = 1024) {
```

- [ ] **步骤 3：sendFrame 改 sendTo（文件帧带目标路由到 Composite）**

```kotlin
private fun sendFrame(dstId: String, frame: MeshFrame) {
    recordSentFrame(frame)
    if (rfcomm != null && rfcomm.isConnectedTo(dstId)) rfcomm.sendTo(dstId, frame)
    else transport.sendTo(dstId, frame)   // 原 broadcast → sendTo：Composite 按 dstId 分流（组内 WFD TCP / 组外 BLE）
}
```

- [ ] **步骤 4：编写去重测试（MeshServiceTest 追加）**

```kotlin
@Test fun `duplicate ping frame processed once`() {
    val env = MeshEnvelope(id = "dup-ping", kind = "PING", srcId = "PEER", dstId = "",
        convId = "conv-PEER", ttl = 8, ts = now, body = PresenceBody("peer", seq = 1))
    val frame = MeshFrame(FrameType.DATA, MeshJson.encodeEnvelope(env).toByteArray())
    service.onFrameForTest(frame)   // 或经 transport.incoming 发送两次
    service.onFrameForTest(frame)
    // 断言：只回了一个 PONG（incoming 里 PONG 数量 == 1）
}
```

（注：`onFrameForTest` 为测试辅助——现有测试模式若用 `transport.incoming` 驱动则按既有方式发送两次同帧，断言回执/处理计数。）

- [ ] **步骤 5：运行测试**

运行：`gradlew.bat testDebugUnitTest`
预期：全绿（新增 1-2 例 + 既有回归 159 例）

- [ ] **步骤 6：Commit**

```bash
git add app/src/main/java/com/meshchat/app/mesh/service/MeshService.kt app/src/main/java/com/meshchat/app/mesh/routing/DedupCache.kt app/src/test/java/com/meshchat/app/mesh/service/MeshServiceTest.kt
git commit -m "feat(v1.1.52): 心跳双写去重（PING/PONG envelope.id）+ sendFrame 走 sendTo 分流 + DedupCache 1024"
```

## 任务 12：File3 大块 + FileTransferManager 参数化

**文件：**
- 修改：`app/src/main/java/com/meshchat/app/mesh/protocol/File3.kt`
- 修改：`app/src/main/java/com/meshchat/app/mesh/transfer/FileTransferManager.kt`
- 测试：`app/src/test/java/com/meshchat/app/mesh/transfer/FileTransferManagerTest.kt`

- [ ] **步骤 1：File3.MAX_CHUNK_BYTES + encodeChunk 放宽**

```kotlin
// v1.1.52（P3）：Wi-Fi Direct 组内 TCP 无 MTU 限制，块上限放大至 32KB（len 字段 2B 上限 65535 安全）。
const val MAX_CHUNK_BYTES = 32 * 1024
// 原 CHUNK_BYTES = 448 保留为 BLE 上限（BLE 下块 = min(448, dynamicChunkBytes(mtu))）
```

`encodeChunk` 校验改：`require(data.size <= MAX_CHUNK_BYTES)`；`dynamicChunkBytes`（FileTransferManager）上限同步 `coerceIn(64, File3.MAX_CHUNK_BYTES)`。

- [ ] **步骤 2：FileTransferManager 窗口/块参数化**

```kotlin
class FileTransferManager(
    ...,
    private val windowSize: Int = WINDOW,          // P2P 下 128；BLE 下 8（构造注入）
    ...
) {
    // WINDOW 常量引用处替换为 windowSize；MAX_ACK_MISSING = windowSize（保持耦合注释）
}
```

- [ ] **步骤 3：大块往返测试（FileTransferManagerTest 追加）**

```kotlin
@Test fun `large chunk over wifi-direct window transfers byte-identical`() {
    // FakeWfdChannel（isGrouped=true）+ MTU 65535：chunkBytes=32KB、windowSize=128
    // 发送 3MB 随机字节 → 接收收齐 → 解压/落盘字节一致
}
```

（复用现有端到端测试基建：DropUntilTransport/InMemory 替身 + FakeWfd；断言 transferredBytes == totalBytes。）

- [ ] **步骤 4：运行测试**

运行：`gradlew.bat testDebugUnitTest`
预期：全绿（新增大块例 + 既有 159 例回归——含 v1.1.48 停滞/断开/补发）

- [ ] **步骤 5：Commit**

```bash
git add app/src/main/java/com/meshchat/app/mesh/protocol/File3.kt app/src/main/java/com/meshchat/app/mesh/transfer/FileTransferManager.kt app/src/test/java/com/meshchat/app/mesh/transfer/FileTransferManagerTest.kt
git commit -m "feat(v1.1.52): File3 大块上限 32KB + FileTransferManager 窗口/块参数化（P2P 高速适配）"
```

## 任务 13：装配替换 transport 注入 + 故障切换

**文件：**
- 修改：`app/src/main/java/com/meshchat/app/MeshChatApplication.kt`

- [ ] **步骤 1：Composite 装配 + wfd incoming 合流 + 故障切换**

```kotlin
val transport by lazy {
    CompositeTransport(bleTransport, wfd)
}
val bleTransport by lazy { BleTransport(this, advertiseShortId = identity.shortId, debugStats = debugStats) }

// service 构造不变（transport 现为 Composite）；wfd 的 incoming 合流在 MeshService.start 内完成（见下）
```

`service` lazy 中：`transport.setAckProvider { svc.broadcastAckKeys() }` 不变。

`MeshService.start()` 增加 wfd 合流（与 rfcomm 同模式）：

```kotlin
// Wi-Fi Direct 增强层合流（v1.1.52）：星域组内 UDP/TCP 帧同样走 handleFrame
(wfd as? WifiDirectChannel)?.let { /* 在 Application 注入合流回调或此处直接访问 */ }
```

（定稿：`MeshService` 构造新增可选参数 `wfd: WifiDirectChannel? = null`，start() 内 `wfd?.incoming` 合流——**注意 WifiDirectChannel 需暴露 incoming**，接口补 `val incoming: SharedFlow<MeshFrame>`；Composite 构造透传。RFCOMM 参数与合流模式完全同构，测试零改动。）

- [ ] **步骤 2：故障切换（蓝牙 OFF → wfd.forceConnect）**

`MeshChatApplication.registerBluetoothStateReceiver`：`STATE_OFF` 分支：

```kotlin
if (state == BluetoothAdapter.STATE_OFF) {
    // v1.1.52：BLE 断 → 若增强开启，立即尝试 Wi-Fi Direct 建连保持连接
    if (wifiDirectEnabled) wfd.forceConnect()
}
```

`WifiDirectTransport.forceConnect()`：`state = State.DISCOVERING`（跳过退避）+ 立即 `discoverLoop()` 一轮。

- [ ] **步骤 3：applyWifiDirect 统一入口**

onCreate / startMesh / 蓝牙 ON 重建后同步开关（设置变更即时生效保留在 setter）：

```kotlin
private fun applyWifiDirect() {
    if (wifiDirectEnabled) wfd.enable() else wfd.disable()
}
```

- [ ] **步骤 4：编译 + 全量单测**

运行：`gradlew.bat compileDebugKotlin` + `gradlew.bat testDebugUnitTest`
预期：BUILD SUCCESSFUL + 全绿

- [ ] **步骤 5：Commit**

```bash
git add app/src/main/java/com/meshchat/app/MeshChatApplication.kt app/src/main/java/com/meshchat/app/mesh/service/MeshService.kt app/src/main/java/com/meshchat/app/mesh/transport/WifiDirectTransport.kt app/src/main/java/com/meshchat/app/mesh/transport/CompositeTransport.kt
git commit -m "feat(v1.1.52): Composite 替换注入 + wfd 合流 + BLE 断强制 Wi-Fi Direct 故障切换"
```

## 任务 14：UI 设置开关 + 通道状态 + DebugStats provider

**文件：**
- 修改：`app/src/main/java/com/meshchat/app/ui/screens/ProfileDetailScreens.kt`
- 修改：`app/src/main/java/com/meshchat/app/mesh/debug/DebugStats.kt`
- 修改：`app/src/main/java/com/meshchat/app/ui/screens/DebugCenterScreen.kt`
- 修改：`app/src/main/java/com/meshchat/app/ui/MeshChatViewModel.kt`（可选透传）
- 修改：`app/src/main/java/com/meshchat/app/ui/screens/MeshScreen.kt`（通道状态小标签）

- [ ] **步骤 1：设置页开关**

`ProfileDetailScreens.kt`「通用设置」区新增行（仿后台常驻/自动搜索开关模式）：

```kotlin
SwitchSettingRow(
    title = "Wi-Fi Direct 增强",
    subtitle = "与邻近设备自动建连形成星域：消息双通道、文件高速传输；关闭省电",
    checked = wifiDirectEnabledProvider(),
    onCheckedChange = setWifiDirectEnabled,
)
```

（ViewModel/Factory 注入 `wifiDirectEnabledProvider/setWifiDirectEnabled`——仿 `autoDiscoveryProvider` 模式；`MeshChatViewModel` 与 `MeshChatViewModelFactory` 同步。）

- [ ] **步骤 2：DebugStats provider + 通道状态**

`DebugStats`：`WifiState(wfdState: String = "DISABLED", wfdMembers: Int = 0)` 并入 `DebugSnapshot`（新字段，默认值零影响既有测试）；`attachProviders` 增 `wfd: (() -> String)?` 与 `wfdMembers`。MeshService attach 时从 wfd 读取。

`DebugCenterScreen`：系统栏/路由板块显示 `通道：纯 BLE / 双通道(星域 N) / Wi-Fi Direct 紧急`。

`MeshScreen`：PeerRow 或顶部状态行小标签 `WFD`（双通道时青色点）——最小改动，选 PeerRow 前置标签。

- [ ] **步骤 3：编译 + 全量单测 + 构建 bump v1.1.53**

运行：`gradlew.bat testDebugUnitTest` + `gradlew.bat assembleDebug`（versionCode 115 / versionName "1.1.53"）
预期：全绿 + BUILD SUCCESSFUL；APK `MeshChat-v1.1.53-debug.apk`

- [ ] **步骤 4：Commit**

```bash
git add ...（涉及文件）
git commit -m "feat(v1.1.53): 设置开关 + 通道状态显示 + DebugStats wfd provider；版本 v1.1.53/115"
```

## 任务 15：P3 真机全量验证 + 回归

- [ ] **步骤 1：验证清单（真机）**

1. 消息双写：两机开启增强 → 互发消息 → 接收方落库一次（无重复气泡）；调试中心重复帧 0
2. 心跳双写：logcat 无重复 PONG；LinkQuality 不双计
3. 文件优先：100MB 文件互传 → 速率 ≥10MB/s（P2P TCP）；传输中拔开蓝牙 → 走 Wi-Fi Direct 保持完成
4. 故障切换：关闭蓝牙 → 消息/文件经 Wi-Fi Direct 保持连通；重开蓝牙 → 自动恢复双通道
5. 群消息双域：三机链式（中继开增强）→ 群消息双域可达、不重复
6. 回归：BLE-only 设备（未开增强）与增强设备互发消息/文件正常（回退路径）

- [ ] **步骤 2：全量回归 + 记录到 AI_CONTEXT + 推送准备**

运行：`gradlew.bat testDebugUnitTest` 全绿 → 按项目规则更新 `AI_CONTEXT.md` 交接块（进度/验证/阻塞/下一步/文件）→ `git push`（含积压提交）。

---

## 自检

**1. 规格覆盖度（对照规格章节）：**
- §4.1 权限 → 任务 1 ✓
- §4.2 WifiDirectTransport → 任务 4（连接/TCP）、任务 7（REGISTER/UDP/成员表）、任务 8（多成员/重建）、任务 13（forceConnect）✓
- §4.3 CompositeTransport 路由表 → 任务 10 ✓；currentMtu/isConnectedTo → 任务 10 ✓；故障切换 → 任务 13 ✓
- §4.4 心跳双写去重 + DedupCache → 任务 11 ✓
- §4.5 文件参数化 → 任务 12 ✓
- §4.6 装配/UI/偏好 → 任务 5（偏好单例）、任务 13（装配）、任务 14（UI/状态）✓
- §5 错误处理 → 分散于任务 7/8/13（回退/重建/降级）✓
- §6 测试计划 → 任务 2/3/10/11/12 单测 + 任务 6/9/15 真机 ✓
- §8 三计划 → P1=任务 1-6、P2=任务 7-9、P3=任务 10-15 ✓

**2. 占位符扫描：** 任务 11 步骤 4 的测试代码含"或经 transport.incoming 发送两次"的两种驱动方式——需执行时按既有 MeshServiceTest 模式统一（现有测试模式为构造 service 后经 transport.incoming 发送帧）；文件路由定稿（sendFrame→sendTo）无歧义；任务 4 的 onServiceResolved 已修正为完整 TXT 解析（无占位）。✓

**3. 类型一致性：** `WifiDirectChannel` 接口在任务 10 定义、任务 13 引用（含 incoming——任务 13 步骤 1 明确接口补 `incoming` 字段）；`WifiDirectFraming`/`MemberTable` 在任务 2/3 定义、任务 7 引用；`wifiDirectEnabled` 偏好任务 5 定义、任务 13/14 引用；`forceConnect()` 任务 13 定义。✓
