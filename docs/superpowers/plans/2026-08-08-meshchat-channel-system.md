# 频道系统实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 实现 v1.1.66 频道系统——单频道制（公共/私人），私人频道仅同频道节点可发现/连接/通信，广播只携带 6 字节截断哈希指纹，嗅探者无法反推频道名。

**架构：** 三层隔离（发现层 → 连接层 → 发送层）。BLE 广播新增独立 `CHANNEL_UUID` Service Data 携带 6 字节频道指纹（`SHA-256("meshchat-channel-v1:" + 频道名)` 截断）；扫描侧解析指纹不匹配不 emit 不自动连 GATT；MeshService 发送/接收层校验指纹。公共频道指纹 = 0 不校验（兼容存量行为与老版本）。切换频道 = 换指纹广播 + 清空节点/路由表重新发现；频道名持久化重启恢复。

**技术栈：** Kotlin + JCA（SHA-256）+ BLE AdvertiseData/ScanRecord + Compose（AlertDialog/RadioButton/OutlinedTextField）+ 现有 JVM 单测体系。

**规格：** `docs/superpowers/specs/2026-08-08-meshchat-channel-system-design.md`

---

## 文件结构

| 文件 | 职责 |
|---|---|
| `app/src/main/java/com/meshchat/app/mesh/channel/ChannelFingerprint.kt`（新） | 频道指纹派生（SHA-256 前 6 字节 → Long，0 保留给公共） |
| `app/src/test/java/com/meshchat/app/mesh/channel/ChannelFingerprintTest.kt`（新） | 指纹确定性/区分度/6 字节截断 |
| `app/src/main/java/com/meshchat/app/mesh/transport/MeshTransport.kt` | `MeshPeerInfo.channelFingerprint` 字段 + `setChannel` 默认方法 |
| `app/src/main/java/com/meshchat/app/mesh/transport/BleTransport.kt` | `CHANNEL_UUID` 广播 + 扫描解析/指纹过滤 + `setChannel` 覆写 |
| `app/src/main/java/com/meshchat/app/mesh/transport/InMemoryTransport.kt` | `lastChannelFingerprint` 断言位 + `setChannel` 覆写 |
| `app/src/main/java/com/meshchat/app/mesh/service/MeshService.kt` | `channelName` StateFlow + `setChannel`（清表/换广播）+ sendText 校验 + handleEnvelope 校验 + `isPeerInCurrentChannel` |
| `app/src/main/java/com/meshchat/app/MeshChatApplication.kt` | `channelName` 偏好 + `applyChannel()` 启动恢复 |
| `app/src/main/java/com/meshchat/app/data/MeshRepository.kt` | `channelName`/`setChannel`/`isPeerInCurrentChannel` 接口与实现 |
| `app/src/main/java/com/meshchat/app/ui/MeshChatViewModel.kt` | `channelName`/`setChannel`（持久化）+ sendRejected 原因区分 |
| `app/src/main/java/com/meshchat/app/ui/MeshChatViewModelFactory.kt` | `persistChannelName` 注入 |
| `app/src/main/java/com/meshchat/app/ui/screens/MeshScreen.kt` | 频道行 + 选择对话框 + Toast |
| `app/src/main/java/com/meshchat/app/ui/screens/MeshChatHome.kt` | `channelName`/`onSetChannel` 透传 |
| `app/src/main/java/com/meshchat/app/ui/MeshChatApp.kt` | collect channelName + `onSetChannel = viewModel::setChannel` |
| `app/src/test/java/com/meshchat/app/mesh/service/MeshServiceTest.kt` | 频道状态/清表/发送校验/接收校验 |
| `app/build.gradle.kts` | versionCode 128 / versionName 1.1.66 |

---

### 任务 1：ChannelFingerprint 工具 + 测试

**文件：**
- 创建：`app/src/main/java/com/meshchat/app/mesh/channel/ChannelFingerprint.kt`
- 测试：`app/src/test/java/com/meshchat/app/mesh/channel/ChannelFingerprintTest.kt`

- [ ] **步骤 1：编写失败的测试**

```kotlin
package com.meshchat.app.mesh.channel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelFingerprintTest {
    @Test
    fun `same channel name produces stable fingerprint`() {
        assertEquals(ChannelFingerprint.of("mesh-team"), ChannelFingerprint.of("mesh-team"))
    }

    @Test
    fun `different channel names produce different fingerprints`() {
        assertNotEquals(ChannelFingerprint.of("mesh-team"), ChannelFingerprint.of("other-team"))
    }

    @Test
    fun `fingerprint fits 6 bytes and never collides with public channel sentinel`() {
        val fp = ChannelFingerprint.of("mesh-team")
        assertTrue("指纹必须 > 0（0 保留给公共频道）", fp > 0)
        assertTrue("指纹必须 ≤ 2^48-1（6 字节截断）", fp < (1L shl 48))
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`.\gradlew.bat testDebugUnitTest --tests "com.meshchat.app.mesh.channel.ChannelFingerprintTest"`
预期：FAIL（编译错误：`ChannelFingerprint` 未定义）

- [ ] **步骤 3：编写实现**

```kotlin
package com.meshchat.app.mesh.channel

import java.security.MessageDigest

/**
 * 频道指纹（v1.1.66）：SHA-256("meshchat-channel-v1:" + 频道名) 前 6 字节 → Long（0 ~ 2^48-1）。
 * - 0 为保留值（公共频道/未知/老版本设备）；of() 若巧合算出 0（概率 2^-48）则返回 1，避免被当成公共。
 * - 单向哈希 + 48 位截断：广播只携带指纹，嗅探者无法从包内容反推频道名，字典攻击不可靠。
 */
object ChannelFingerprint {
    fun of(name: String): Long {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("meshchat-channel-v1:$name".toByteArray(Charsets.UTF_8))
        var v = 0L
        for (i in 0 until 6) v = (v shl 8) or (digest[i].toLong() and 0xFF)
        return if (v == 0L) 1L else v
    }
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：`.\gradlew.bat testDebugUnitTest --tests "com.meshchat.app.mesh.channel.ChannelFingerprintTest"`
预期：PASS（3/3）

- [ ] **步骤 5：Commit**

```bash
git add app/src/main/java/com/meshchat/app/mesh/channel/ChannelFingerprint.kt app/src/test/java/com/meshchat/app/mesh/channel/ChannelFingerprintTest.kt
git commit -m "v1.1.66 频道指纹派生工具 + 测试"
```

---

### 任务 2：传输接口（MeshPeerInfo + setChannel）

**文件：**
- 修改：`app/src/main/java/com/meshchat/app/mesh/transport/MeshTransport.kt`
- 修改：`app/src/main/java/com/meshchat/app/mesh/transport/InMemoryTransport.kt`

- [ ] **步骤 1：MeshPeerInfo 加字段 + 接口加默认方法**

`MeshTransport.kt`：

```kotlin
data class MeshPeerInfo(
    ...
    val signalRatio: Double = -1.0,
    /** 对端所属频道指纹（v1.1.66）；0 = 公共频道/未知/老版本设备。发现层已按本机频道过滤，同频道节点才可见。 */
    val channelFingerprint: Long = 0,
)
```

接口尾部（`isConnectedTo` 后）加：

```kotlin
    /**
     * v1.1.66 设置本机频道指纹（0 = 公共频道）：BleTransport 覆写更新广播携带的指纹并重启广播生效；
     * InMemoryTransport 覆写记录断言位供测试。
     */
    fun setChannel(fingerprint: Long) {}
```

- [ ] **步骤 2：InMemoryTransport 覆写 + 断言位**

`InMemoryTransport.kt`（在 `var lastDiscoveryMode` 附近加）：

```kotlin
    /** v1.1.66 频道指纹断言位：setChannel 记录，测试断言转发。 */
    var lastChannelFingerprint = 0L
```

（`setChannel` 覆写加到 `lastTxPowerLevel`/`lastDiscoveryMode` 覆写旁）：

```kotlin
    override fun setChannel(fingerprint: Long) { lastChannelFingerprint = fingerprint }
```

- [ ] **步骤 3：运行测试验证编译**

运行：`.\gradlew.bat testDebugUnitTest --tests "com.meshchat.app.mesh.service.MeshServiceTest" --tests "com.meshchat.app.mesh.transport.*"`
预期：PASS（存量全过，新字段有默认值 0 零改动）

- [ ] **步骤 4：Commit**

```bash
git add app/src/main/java/com/meshchat/app/mesh/transport/MeshTransport.kt app/src/main/java/com/meshchat/app/mesh/transport/InMemoryTransport.kt
git commit -m "v1.1.66 传输接口：MeshPeerInfo.channelFingerprint + setChannel"
```

---

### 任务 3：BleTransport 广播指纹 + 扫描过滤

**文件：**
- 修改：`app/src/main/java/com/meshchat/app/mesh/transport/BleTransport.kt`

- [ ] **步骤 1：加 CHANNEL_UUID + 指纹字段 + setChannel 覆写**

companion object 的 `ACK_UUID` 旁（约 80 行）：

```kotlin
        /** v1.1.66 频道指纹 Service Data UUID：广播携带 6 字节频道指纹（公共频道不携带），扫描方按指纹过滤。 */
        val CHANNEL_UUID: UUID = UUID.fromString("0000A5E4-0000-1000-8000-00805F9B34FB")
```

类字段（`peerIds` 附近）：

```kotlin
    /** v1.1.66 本机频道指纹（0 = 公共频道）；广播携带与扫描过滤依据。 */
    @Volatile private var channelFingerprint: Long = 0L
```

类中（`setTxPowerLevel` 覆写旁）：

```kotlin
    override fun setChannel(fingerprint: Long) { channelFingerprint = fingerprint }
```

- [ ] **步骤 2：广播携带指纹（startAdvertising 的 AdvertiseData 构建处，约 344-350 行）**

将：

```kotlin
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            // 广播包携带本机发射功率：接收端扫描可读（ScanResult.getTxPowerLevel），结合 RSSI 估算路径损耗/距离
            .setIncludeTxPowerLevel(true)
            .addServiceUuid(ParcelUuid(serviceUuid))
            .addServiceData(ParcelUuid(serviceUuid), advertiseShortId.toByteArray())
            .build()
```

改为：

```kotlin
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            // 广播包携带本机发射功率：接收端扫描可读（ScanResult.getTxPowerLevel），结合 RSSI 估算路径损耗/距离
            .setIncludeTxPowerLevel(true)
            .addServiceUuid(ParcelUuid(serviceUuid))
            .addServiceData(ParcelUuid(serviceUuid), advertiseShortId.toByteArray())
            .apply {
                // v1.1.66 私人频道广播携带频道指纹（独立 Service Data；公共频道不携带，主 Service Data 短 ID 不变 → 老版本解析不受影响）
                if (channelFingerprint != 0L) {
                    addServiceData(ParcelUuid(CHANNEL_UUID), fingerprintToBytes(channelFingerprint))
                }
            }
            .build()
```

- [ ] **步骤 3：扫描解析指纹 + 频道过滤（onScanResult，shortId 解析后、ackKeys 解析附近，约 396-419 行）**

在 `val ackData = ...` 之前插入：

```kotlin
            // v1.1.66 频道过滤：扫描到的节点指纹必须匹配本机当前频道，否则不可见、不自动连接（跨频道节点在传输层即隔离）
            val peerChannelFp = bytesToFingerprint(record.serviceData[ParcelUuid(CHANNEL_UUID)])
            if (peerChannelFp != channelFingerprint) return
```

将 `_foundPeers.tryEmit(MeshPeerInfo(...))` 的构造加字段：

```kotlin
                    txPower = record.txPowerLevel,
                    channelFingerprint = peerChannelFp,   // v1.1.66 频道指纹（0 = 公共/老版本）
```

- [ ] **步骤 4：指纹编解码工具函数（类内 private）**

```kotlin
    /** 6 字节大端编码（与 ChannelFingerprint.of 截断序一致）。 */
    private fun fingerprintToBytes(fp: Long): ByteArray {
        val b = ByteArray(6)
        for (i in 0 until 6) b[i] = ((fp shr (40 - i * 8)) and 0xFF).toByte()
        return b
    }

    /** 解析 6 字节指纹 → Long；缺失/长度不足 → 0（公共频道/老版本设备）。 */
    private fun bytesToFingerprint(b: ByteArray?): Long {
        if (b == null || b.size < 6) return 0L
        var v = 0L
        for (i in 0 until 6) v = (v shl 8) or (b[i].toLong() and 0xFF)
        return v
    }
```

- [ ] **步骤 5：运行测试验证编译**

运行：`.\gradlew.bat testDebugUnitTest`
预期：PASS（BleTransport 为 Android 框架层无 JVM 单测，编译 + 存量回归；InMemory 测试不受影响）

- [ ] **步骤 6：Commit**

```bash
git add app/src/main/java/com/meshchat/app/mesh/transport/BleTransport.kt
git commit -m "v1.1.66 BleTransport：CHANNEL_UUID 指纹广播 + 扫描过滤"
```

---

### 任务 4：MeshService 频道状态与三层校验

**文件：**
- 修改：`app/src/main/java/com/meshchat/app/mesh/service/MeshService.kt`
- 测试：`app/src/test/java/com/meshchat/app/mesh/service/MeshServiceTest.kt`

- [ ] **步骤 1：编写失败的测试**

在 `MeshServiceTest.kt` 类内新增（放在文件末尾；文件头 import 区加 `import com.meshchat.app.mesh.channel.ChannelFingerprint`）：

```kotlin
    // ===== v1.1.66 频道系统 =====

    @Test
    fun `setChannel switches fingerprint and clears peers`() {
        service.start()
        transport.emitPeer(MeshPeerInfo(shortId = "F1", deviceAddress = "AA:BB:CC", rssi = -50, channelFingerprint = 0L))
        service.heartbeatTick(System.currentTimeMillis() + 100)
        assertTrue("公共频道下 F1 可见", service.peers.value.any { it.shortId == "F1" })

        service.setChannel("mesh-team")
        assertEquals("频道名状态更新", "mesh-team", service.channelName.value)
        assertEquals("传输层收到指纹", ChannelFingerprint.of("mesh-team"), transport.lastChannelFingerprint)
        assertTrue("切换后节点表清空", service.peers.value.isEmpty())

        service.setChannel(null)
        assertNull("切回公共频道", service.channelName.value)
        assertEquals("公共频道指纹归零", 0L, transport.lastChannelFingerprint)
    }

    @Test
    fun `sendText refuses cross-channel target in private channel`() {
        service.start()
        service.setChannel("mesh-team")
        val fp = ChannelFingerprint.of("mesh-team")
        // 同频道节点：可发送
        transport.emitPeer(MeshPeerInfo(shortId = "SAME", deviceAddress = "AA:BB:CC", rssi = -50, channelFingerprint = fp))
        service.heartbeatTick(System.currentTimeMillis() + 100)
        service.seedSessionKeyForTesting("SAME")
        assertTrue("同频道可发送", service.sendText("conv-SAME", "SAME", "hi"))
        // 跨频道节点（已记录但指纹不匹配）：拒绝
        transport.emitPeer(MeshPeerInfo(shortId = "CROSS", deviceAddress = "DD:EE:FF", rssi = -50, channelFingerprint = ChannelFingerprint.of("other")))
        service.heartbeatTick(System.currentTimeMillis() + 100)
        service.seedSessionKeyForTesting("CROSS")
        assertFalse("跨频道拒绝发送", service.sendText("conv-CROSS", "CROSS", "hi"))
        // 未发现节点（peerEntries 无记录）：拒绝
        assertFalse("未发现节点拒绝发送", service.sendText("conv-GHOST", "GHOST", "hi"))
    }

    @Test
    fun `sendText still works in public channel without fingerprint match`() {
        service.start()
        // 公共频道（指纹 0）：目标节点未发现也允许发送（保持存量 outbox 排队行为）
        service.seedSessionKeyForTesting("GHOST")
        assertTrue("公共频道未发现节点可发送", service.sendText("conv-GHOST", "GHOST", "hi"))
    }

    @Test
    fun `handleEnvelope drops cross-channel frame from known peer`() {
        service.start()
        service.setChannel("mesh-team")
        transport.emitPeer(MeshPeerInfo(shortId = "CROSS", deviceAddress = "AA:BB:CC", rssi = -50, channelFingerprint = ChannelFingerprint.of("other")))
        service.heartbeatTick(System.currentTimeMillis() + 100)
        // 跨频道节点发来 TEXT：被丢弃（不落库）
        service.handleFrame(textFrame("t1", "CROSS", service.shortId, "hello"))
        assertTrue("跨频道消息不落库", store.observeMessages("conv-CROSS").value.isEmpty())
    }
```

> 注：现有测试模式确认——`service.handleFrame(MeshFrame)` 是帧注入入口、`service.seedSessionKeyForTesting(peerId)` 种会话密钥、`store` 为测试可访问的 MeshStore、`transport.emitPeer` 后需 `service.heartbeatTick` 触发 refreshPeers。`textFrame(id, srcId, dstId, text)` 为测试已有 helper。

- [ ] **步骤 2：运行测试验证失败**

运行：`.\gradlew.bat testDebugUnitTest --tests "com.meshchat.app.mesh.service.MeshServiceTest"`
预期：FAIL（`setChannel`/`channelName`/`lastChannelFingerprint` 未定义）

- [ ] **步骤 3：编写实现（MeshService）**

import 区加：

```kotlin
import com.meshchat.app.mesh.channel.ChannelFingerprint
```

`peerEntries` 声明（256 行）附近加字段：

```kotlin
    // ===== v1.1.66 频道系统（单频道制：公共 / 私人）=====
    /** 当前频道名（null = 公共频道）。 */
    private val _channelName = MutableStateFlow<String?>(null)
    val channelName: StateFlow<String?> = _channelName.asStateFlow()
    /** 当前频道指纹（0 = 公共频道）。 */
    @Volatile private var channelFingerprint: Long = 0L
```

类内（`blockPeer` 附近）加方法：

```kotlin
    /**
     * v1.1.66 切换频道：null = 公共频道；非空 = 私人频道（指纹 = SHA-256 截断）。
     * 切换后清空节点表与 2 跳路由（旧频道残留剔除），重新发现只按新频道过滤；会话/消息记录保留。
     */
    fun setChannel(name: String?) {
        val trimmed = name?.trim()?.takeIf { it.isNotEmpty() }
        val fp = if (trimmed == null) 0L else ChannelFingerprint.of(trimmed)
        _channelName.value = trimmed
        channelFingerprint = fp
        transport.setChannel(fp)
        transport.refreshAdvertising()   // v1.1.63 模式守卫：仅 NORMAL 重启广播；CLOSED/SILENT 广播本就停，扫描过滤读 volatile 即时生效
        peerEntries.clear()
        routeEntries.clear()             // 2 跳中继路由同样按频道隔离，旧频道路由失效
        refreshPeers()
    }

    /** v1.1.66 对端是否在当前频道（发送拒绝原因区分）：公共频道恒 true；私人频道要求节点已发现且指纹匹配。 */
    fun isPeerInCurrentChannel(peerId: String): Boolean {
        if (channelFingerprint == 0L) return true
        return peerEntries[peerId]?.info?.channelFingerprint == channelFingerprint
    }
```

`sendText`（559 行）在 `val isSelfLoop = ...` 之后、`val key = ...` 之前插入：

```kotlin
        // v1.1.66 频道校验：私人频道下目标必须已发现且同频道；公共频道不校验（保持存量 outbox 排队行为）
        if (!isSelfLoop && channelFingerprint != 0L) {
            val peer = peerEntries[dstId]?.info
            if (peer == null || peer.channelFingerprint != channelFingerprint) {
                Log.w(TAG, "channel: dst $dstId not in current channel, refusing send")
                return false
            }
        }
```

`handleEnvelope`（1505 行拉黑拦截之后）插入：

```kotlin
        // v1.1.66 频道校验：私人频道下已记录节点指纹不匹配 → 丢弃（防御残留连接/改装连入 GATT server）
        if (channelFingerprint != 0L) {
            peerEntries[envelopeIn.srcId]?.let { known ->
                if (known.info.channelFingerprint != channelFingerprint) {
                    Log.d(TAG, "drop cross-channel frame from ${envelopeIn.srcId}")
                    return
                }
            }
        }
```

- [ ] **步骤 4：运行测试验证通过**

运行：`.\gradlew.bat testDebugUnitTest --tests "com.meshchat.app.mesh.service.MeshServiceTest"`
预期：PASS（新 4 项 + 存量全过——公共频道默认指纹 0，存量 sendText 测试跳过频道校验）

- [ ] **步骤 5：Commit**

```bash
git add app/src/main/java/com/meshchat/app/mesh/service/MeshService.kt app/src/test/java/com/meshchat/app/mesh/service/MeshServiceTest.kt
git commit -m "v1.1.66 MeshService：频道状态 + 发现/发送/接收三层校验"
```

---

### 任务 5：持久化 + Repository/ViewModel/Factory 接线

**文件：**
- 修改：`app/src/main/java/com/meshchat/app/MeshChatApplication.kt`
- 修改：`app/src/main/java/com/meshchat/app/data/MeshRepository.kt`
- 修改：`app/src/main/java/com/meshchat/app/ui/MeshChatViewModel.kt`
- 修改：`app/src/main/java/com/meshchat/app/ui/MeshChatViewModelFactory.kt`

- [ ] **步骤 1：Application 加 channelName 偏好 + applyChannel**

`MeshChatApplication.kt`（`silentMode` 属性 118-124 行之后）：

```kotlin
    /**
     * v1.1.66 当前频道名偏好（null = 公共频道）：重启恢复频道隔离。
     * 纯偏好读写，不直接动服务；生效由 applyChannel 在启动/蓝牙重建时下发。
     */
    var channelName: String?
        get() = getSharedPreferences("meshchat_settings", Context.MODE_PRIVATE)
            .getString("channel_name", null)
        set(value) {
            getSharedPreferences("meshchat_settings", Context.MODE_PRIVATE)
                .edit().putString("channel_name", value?.trim()?.takeIf { it.isNotEmpty() }).apply()
        }
```

`applyAutoDiscovery()` 旁加：

```kotlin
    /** v1.1.66 启动恢复频道：读偏好 → service.setChannel（幂等，与发现模式正交）。 */
    private fun applyChannel() {
        service.setChannel(channelName)
    }
```

在 `applyAutoDiscovery()` 的**每个调用点之后**并列调用 `applyChannel()`（onCreate 启动流程约 171 行、蓝牙 ON 重建约 196/219 行处——找到全部 `applyAutoDiscovery()` 调用，其后加一行 `applyChannel()`）。

- [ ] **步骤 2：Repository 接口 + 实现**

`MeshRepository.kt` 接口（`unblockPeer` 后）：

```kotlin
    /** v1.1.66 当前频道名（null = 公共频道）。 */
    val channelName: kotlinx.coroutines.flow.StateFlow<String?>
    /** v1.1.66 切换频道（null = 公共频道；非空 = 私人频道，仅同频道可发现/连接）。 */
    fun setChannel(name: String?)
    /** v1.1.66 对端是否在当前频道（发送被拒原因区分）。 */
    fun isPeerInCurrentChannel(peerId: String): Boolean
```

实现类：

```kotlin
    override val channelName: kotlinx.coroutines.flow.StateFlow<String?> = service.channelName

    override fun setChannel(name: String?) = service.setChannel(name)

    override fun isPeerInCurrentChannel(peerId: String): Boolean = service.isPeerInCurrentChannel(peerId)
```

- [ ] **步骤 3：ViewModel**

构造参数（`setSilentMode` 旁，约 50 行）：

```kotlin
    /** v1.1.66 频道名持久化（Application.channelName setter 注入）。 */
    private val persistChannelName: (String?) -> Unit,
```

类内（`setDiscoveryMode` 旁）：

```kotlin
    val channelName: kotlinx.coroutines.flow.StateFlow<String?> = repository.channelName

    /** v1.1.66 切换频道：持久化 + 下发服务层（换指纹广播/清表/重新发现）。 */
    fun setChannel(name: String?) {
        persistChannelName(name)
        repository.setChannel(name)
    }
```

`sendMessage` 中发送被拒文案（约 426-427 行）：

```kotlin
                val sent = repository.sendText("conv-$target", text.trim())
                if (!sent) {
                    // v1.1.66 区分拒绝原因：E2EE 无密钥 vs 对方不在当前频道
                    _sendRejected.value = if (repository.isPeerInCurrentChannel(target)) {
                        "对方未启用加密，无法发送消息"
                    } else {
                        "对方不在当前频道，无法发送"
                    }
                }
```

- [ ] **步骤 4：Factory 注入**

`MeshChatViewModelFactory.kt`（`setSilentMode = { app.silentMode = it }` 旁，约 30 行）：

```kotlin
            persistChannelName = { app.channelName = it },   // v1.1.66 频道名持久化
```

- [ ] **步骤 5：运行测试验证编译**

运行：`.\gradlew.bat testDebugUnitTest`
预期：PASS（ViewModel 为 UI 层无 JVM 测试，编译确认）

- [ ] **步骤 6：Commit**

```bash
git add app/src/main/java/com/meshchat/app/MeshChatApplication.kt app/src/main/java/com/meshchat/app/data/MeshRepository.kt app/src/main/java/com/meshchat/app/ui/MeshChatViewModel.kt app/src/main/java/com/meshchat/app/ui/MeshChatViewModelFactory.kt
git commit -m "v1.1.66 频道持久化 + Repository/ViewModel/Factory 接线"
```

---

### 任务 6：UI（Mesh 页频道选择器 + 发送被拒提示）

**文件：**
- 修改：`app/src/main/java/com/meshchat/app/ui/screens/MeshScreen.kt`
- 修改：`app/src/main/java/com/meshchat/app/ui/screens/MeshChatHome.kt`
- 修改：`app/src/main/java/com/meshchat/app/ui/MeshChatApp.kt`

- [ ] **步骤 1：MeshScreen 加参数 + import**

签名（`onBlockPeer` 之后）：

```kotlin
    /** v1.1.66 当前频道名（null = 公共频道）与切换回调。 */
    channelName: String?,
    onSetChannel: (String?) -> Unit,
```

import 区加：

```kotlin
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
```

- [ ] **步骤 2：MeshScreen 主函数加频道行 + 对话框状态**

`blockTarget` 状态旁：

```kotlin
    // v1.1.66 频道选择对话框状态
    var showChannelDialog by remember { mutableStateOf(false) }
    var channelInput by remember { mutableStateOf("") }
```

拓扑图 item（`item { MeshTopology(...) }`）之后、`item { Row(... "附近节点" ...) }` 之前插入：

```kotlin
        item {
            // v1.1.66 频道选择器：单频道制——私人频道仅同频道成员可发现/连接（防公共搜索）
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp)
                    .clickable { showChannelDialog = true },
            ) {
                Icon(
                    Icons.Outlined.Campaign, null,
                    tint = if (channelName.isNullOrBlank()) TextSecondary else Cyan,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("频道", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (channelName.isNullOrBlank()) "公共频道 · 全部节点可见"
                        else "私人频道 · $channelName",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (channelName.isNullOrBlank()) TextSecondary else Cyan,
                    )
                }
            }
        }
```

- [ ] **步骤 3：MeshScreen 加选择对话框（blockTarget 确认框旁）**

```kotlin
    // v1.1.66 频道选择对话框：公共频道 / 自定义私人频道名
    if (showChannelDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showChannelDialog = false },
            title = { Text("选择频道") },
            text = {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { channelInput = "" },
                    ) {
                        RadioButton(selected = channelName.isNullOrBlank(), onClick = null)
                        Text("公共频道 · 全部节点可见")
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = channelInput,
                        onValueChange = { channelInput = it },
                        label = { Text("私人频道名") },
                        placeholder = { Text("仅同频道成员可发现/连接") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    val name = channelInput.trim()
                    onSetChannel(name.ifEmpty { null })
                    android.widget.Toast.makeText(
                        context,
                        if (name.isEmpty()) "已切换至公共频道" else "已切换至频道 $name",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                    showChannelDialog = false
                }) { Text("切换") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showChannelDialog = false }) { Text("取消") }
            },
        )
    }
```

- [ ] **步骤 4：MeshChatHome 透传**

签名（`onBlockPeer` 后）：

```kotlin
    /** v1.1.66 当前频道名与切换回调。 */
    channelName: String?,
    onSetChannel: (String?) -> Unit,
```

MeshScreen 调用处（`onBlockPeer = onBlockPeer,` 后）：

```kotlin
                    channelName = channelName,
                    onSetChannel = onSetChannel,
```

- [ ] **步骤 5：MeshChatApp 接线**

`MeshChatApp.kt`（`onBlockPeer = viewModel::blockPeer,` 旁）：

```kotlin
            channelName = channelName,
            onSetChannel = viewModel::setChannel,
```

并在 collect 区（参照 `blockedPeers` 收集方式）加：

```kotlin
        val channelName by viewModel.channelName.collectAsState()
```

- [ ] **步骤 6：运行测试验证编译**

运行：`.\gradlew.bat testDebugUnitTest`
预期：PASS（编译确认，UI 改动无新测试）

- [ ] **步骤 7：Commit**

```bash
git add app/src/main/java/com/meshchat/app/ui/screens/MeshScreen.kt app/src/main/java/com/meshchat/app/ui/screens/MeshChatHome.kt app/src/main/java/com/meshchat/app/ui/MeshChatApp.kt
git commit -m "v1.1.66 UI：Mesh 页频道选择器 + 发送被拒原因区分"
```

---

### 任务 7：版本 bump + 全量验证 + 交付

**文件：**
- 修改：`app/build.gradle.kts`
- 修改：`AI_CONTEXT.md`

- [ ] **步骤 1：版本 bump**

`app/build.gradle.kts`：

```kotlin
        versionCode = 128
        versionName = "1.1.66"
```

- [ ] **步骤 2：全量测试**

运行：`.\gradlew.bat testDebugUnitTest`
预期：BUILD SUCCESSFUL，全部测试通过（181 存量 + 新增 ChannelFingerprintTest 3 + MeshServiceTest 4 = 188）

- [ ] **步骤 3：双包构建**

运行：`.\gradlew.bat assembleDebug` 然后 `.\gradlew.bat assembleRelease`
预期：BUILD SUCCESSFUL × 2

复制 APK（PowerShell）：

```powershell
Copy-Item "app\build\outputs\apk\debug\app-debug.apk" "MeshChat-v1.1.66-debug.apk" -Force
Copy-Item "app\build\outputs\apk\release\app-release.apk" "MeshChat-v1.1.66-release.apk" -Force
```

- [ ] **步骤 4：AI_CONTEXT 交接 + 推送**

更新 `AI_CONTEXT.md`：版本行 → v1.1.66/128；当前进度加 v1.1.66 条目；已验证内容加 v1.1.66；下一步首要任务置顶真机验证清单（① 双机同频道名互相可见/连接/通信 ② 换频道后旧频道节点消失、旧会话发送被拒 ③ 公共频道下看不到私人频道节点 ④ 重启后频道保持 ⑤ 老版本设备在公共频道可见）；本次涉及关键文件加 v1.1.66。

```bash
git add app/build.gradle.kts AI_CONTEXT.md
git commit -m "v1.1.66 频道系统：版本 bump + AI_CONTEXT 交接"
git branch --show-current   # 确认 main 再推送
git push origin main        # 若网络阻塞，记录 AI_CONTEXT 后下次重试
```
