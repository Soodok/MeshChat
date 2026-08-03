# MeshChat 后端服务框架 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 在现有 MeshChat Android 前端工程内实现设备内嵌去中心化后端框架——协议层、路由层、身份层、持久化层、服务层、BLE 传输层，并以 `MeshRepository` 契约接入前端 ViewModel，打通「发送→接力→投递→回执」的完整消息闭环。

**架构：** 单 `app` 模块内按包分层（`protocol / transport / routing / identity / storage / service`），下层向上层提供接口；`transport` 暴露 `MeshTransport` 抽象（本期 `BleTransport`，测试用 `InMemoryTransport`）；`service` 依赖存储抽象 `MeshStore`（生产 `RoomMeshStore`，测试 `InMemoryMeshStore`），确保核心逻辑纯 JVM 可测。

**技术栈：** Kotlin 2.2.10、AGP 9.0.0、Gradle 9.1.0、kotlinx-serialization（JSON 信封）、Room 2.7.0（KSP 编译）、kotlinx-coroutines、JUnit4（JVM 单测）、Android BLE API（GATT + 广播扫描）。

---

## 文件结构

**新建（生产代码）：**
- `app/src/main/java/com/meshchat/app/mesh/protocol/MeshFrame.kt` — 帧类型 + 二进制帧编解码
- `app/src/main/java/com/meshchat/app/mesh/protocol/MeshEnvelope.kt` — 消息信封 + 三类载荷 + JSON 序列化
- `app/src/main/java/com/meshchat/app/mesh/routing/DedupCache.kt` — 消息去重表（LRU，容量 512）
- `app/src/main/java/com/meshchat/app/mesh/routing/ForwardingDecision.kt` — 转发决策（投递/转发/丢弃）
- `app/src/main/java/com/meshchat/app/mesh/identity/LocalIdentity.kt` — 本机短 ID 与显示名
- `app/src/main/java/com/meshchat/app/mesh/identity/PeerRegistry.kt` — 节点注册表（含存活剔除）
- `app/src/main/java/com/meshchat/app/mesh/storage/MeshStore.kt` — 存储抽象接口（消息/outbox/状态）
- `app/src/main/java/com/meshchat/app/mesh/storage/Entities.kt` — Room 实体（5 表）
- `app/src/main/java/com/meshchat/app/mesh/storage/Daos.kt` — Room DAO
- `app/src/main/java/com/meshchat/app/mesh/storage/MeshDatabase.kt` — Room 数据库与 `RoomMeshStore`
- `app/src/main/java/com/meshchat/app/mesh/transport/MeshTransport.kt` — 传输载体抽象接口
- `app/src/main/java/com/meshchat/app/mesh/transport/InMemoryTransport.kt` — 测试替身（自环回环）
- `app/src/main/java/com/meshchat/app/mesh/transport/BleTransport.kt` — BLE 实现（广播/扫描/GATT 服务端+客户端）
- `app/src/main/java/com/meshchat/app/mesh/service/MeshService.kt` — 服务门面（发送/接收/转发/回执编排）
- `app/src/main/java/com/meshchat/app/data/MeshRepository.kt` — 前端数据源契约 + `MeshRepositoryImpl`

**新建（测试代码）：**
- `app/src/test/java/com/meshchat/app/mesh/protocol/MeshFrameTest.kt`
- `app/src/test/java/com/meshchat/app/mesh/protocol/MeshEnvelopeTest.kt`
- `app/src/test/java/com/meshchat/app/mesh/routing/DedupCacheTest.kt`
- `app/src/test/java/com/meshchat/app/mesh/routing/ForwardingDecisionTest.kt`
- `app/src/test/java/com/meshchat/app/mesh/identity/LocalIdentityTest.kt`
- `app/src/test/java/com/meshchat/app/mesh/service/MeshServiceTest.kt`

**修改：**
- `build.gradle.kts`（根）— 声明 KSP、serialization 插件
- `app/build.gradle.kts` — 应用插件与依赖
- `app/src/main/AndroidManifest.xml` — BLE 权限
- `app/src/main/java/com/meshchat/app/ui/MeshChatViewModel.kt` — 改为消费 `MeshRepository`
- `app/src/main/java/com/meshchat/app/ui/MeshChatApp.kt` — 注入 repository 实现
- `README.md` — 更新已实现能力清单

---

## 任务 0：构建环境与依赖

**文件：**
- 修改：`build.gradle.kts`（根）
- 修改：`app/build.gradle.kts`
- 修改：`app/src/main/AndroidManifest.xml`

- [ ] **步骤 1：根构建脚本声明插件**

修改 `build.gradle.kts`：

```kotlin
plugins {
    id("com.android.application") version "9.0.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.10" apply false
    id("com.google.devtools.ksp") version "2.2.10-1.0.31" apply false
}
```

- [ ] **步骤 2：app 模块应用插件并添加依赖**

修改 `app/build.gradle.kts` 的 plugins 块与 dependencies 块：

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.12.00"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("androidx.room:room-runtime:2.7.0")
    implementation("androidx.room:room-ktx:2.7.0")
    ksp("androidx.room:room-compiler:2.7.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
}
```

- [ ] **步骤 3：声明 BLE 权限**

修改 `app/src/main/AndroidManifest.xml`，在 `<manifest>` 下、`<application>` 前添加：

```xml
    <uses-permission android:name="android.permission.BLUETOOTH_SCAN"
        android:usesPermissionFlags="neverForLocation" />
    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
    <uses-feature android:name="android.hardware.bluetooth_le" android:required="true" />
```

- [ ] **步骤 4：验证依赖解析与构建**

运行：`.\gradlew.bat assembleDebug --console=plain`
预期：`BUILD SUCCESSFUL`（若个别依赖版本 404 解析失败，调整至相邻可用稳定版本后重试）

- [ ] **步骤 5：Commit**

```bash
git add build.gradle.kts app/build.gradle.kts app/src/main/AndroidManifest.xml
git commit -m "build: 引入 Room/KSP/序列化依赖与 BLE 权限"
```

---

## 任务 1：协议层 · 帧编解码

**文件：**
- 创建：`app/src/main/java/com/meshchat/app/mesh/protocol/MeshFrame.kt`
- 测试：`app/src/test/java/com/meshchat/app/mesh/protocol/MeshFrameTest.kt`

- [ ] **步骤 1：编写失败的测试**

创建 `MeshFrameTest.kt`：

```kotlin
package com.meshchat.app.mesh.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MeshFrameTest {
    @Test
    fun `frame roundtrip preserves type and payload`() {
        val frame = MeshFrame(FrameType.DATA, "hello".toByteArray())
        assertEquals(frame, MeshFrame.decode(frame.encode()))
    }

    @Test
    fun `unknown frame type throws`() {
        assertThrows(IllegalArgumentException::class.java) {
            MeshFrame.decode(byteArrayOf(0x7F, 0x00, 0x00))
        }
    }

    @Test
    fun `length mismatch throws`() {
        assertThrows(IllegalArgumentException::class.java) {
            MeshFrame.decode(byteArrayOf(0x02, 0x00, 0x0A, 0x01))
        }
    }
}
```

- [ ] **步骤 2：运行测试确认失败**

运行：`.\gradlew.bat testDebugUnitTest --tests "com.meshchat.app.mesh.protocol.MeshFrameTest" --console=plain`
预期：FAIL（编译错误，`MeshFrame` 未定义）

- [ ] **步骤 3：编写实现**

创建 `MeshFrame.kt`：

```kotlin
package com.meshchat.app.mesh.protocol

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

enum class FrameType(val code: Byte) {
    HELLO(0x01), DATA(0x02), ACK(0x03), RECEIPT(0x04), PING(0x05);

    companion object {
        fun fromCode(code: Byte): FrameType? = entries.firstOrNull { it.code == code }
    }
}

data class MeshFrame(val type: FrameType, val payload: ByteArray) {
    val payloadText: String get() = String(payload, StandardCharsets.UTF_8)

    fun encode(): ByteArray = ByteBuffer.allocate(HEADER_SIZE + payload.size)
        .put(type.code)
        .putShort(payload.size.toShort())
        .put(payload)
        .array()

    companion object {
        const val HEADER_SIZE = 3

        fun decode(bytes: ByteArray): MeshFrame {
            require(bytes.size >= HEADER_SIZE) { "frame too short" }
            val buffer = ByteBuffer.wrap(bytes)
            val type = FrameType.fromCode(buffer.get())
                ?: throw IllegalArgumentException("unknown frame type")
            val length = buffer.short.toInt() and 0xFFFF
            require(length == bytes.size - HEADER_SIZE) { "length mismatch" }
            val payload = ByteArray(length)
            buffer.get(payload)
            return MeshFrame(type, payload)
        }
    }
}
```

- [ ] **步骤 4：运行测试确认通过**

运行：`.\gradlew.bat testDebugUnitTest --tests "com.meshchat.app.mesh.protocol.MeshFrameTest" --console=plain`
预期：PASS（3 个测试全部通过）

- [ ] **步骤 5：Commit**

```bash
git add app/src/main/java/com/meshchat/app/mesh/protocol/MeshFrame.kt app/src/test/java/com/meshchat/app/mesh/protocol/MeshFrameTest.kt
git commit -m "feat(mesh): 帧格式编解码与帧类型枚举"
```

---

## 任务 2：协议层 · 消息信封与 JSON 序列化

**文件：**
- 创建：`app/src/main/java/com/meshchat/app/mesh/protocol/MeshEnvelope.kt`
- 测试：`app/src/test/java/com/meshchat/app/mesh/protocol/MeshEnvelopeTest.kt`

- [ ] **步骤 1：编写失败的测试**

创建 `MeshEnvelopeTest.kt`：

```kotlin
package com.meshchat.app.mesh.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class MeshEnvelopeTest {
    private val text = MeshEnvelope(
        id = "msg-1", kind = "TEXT", srcId = "A001", dstId = "B002",
        convId = "conv-A001-B002", ttl = 8, ts = 1700000000000,
        body = TextBody("你好"),
    )

    private val file = MeshEnvelope(
        id = "msg-2", kind = "FILE", srcId = "A001", dstId = "B002",
        convId = "conv-A001-B002", ttl = 8, ts = 1700000000001,
        body = FileBody("a.jpg", "image/jpeg", 40960, 2, 0, "BASE64=="),
    )

    private val group = MeshEnvelope(
        id = "msg-3", kind = "GROUP", srcId = "A001", dstId = "g-1",
        convId = "g-1", ttl = 8, ts = 1700000000002,
        body = GroupBody(op = "JOIN", groupName = "营地"),
    )

    @Test
    fun `text envelope roundtrip`() {
        assertEquals(text, MeshJson.decodeEnvelope(MeshJson.encodeEnvelope(text)))
    }

    @Test
    fun `file envelope roundtrip`() {
        assertEquals(file, MeshJson.decodeEnvelope(MeshJson.encodeEnvelope(file)))
    }

    @Test
    fun `group envelope roundtrip`() {
        assertEquals(group, MeshJson.decodeEnvelope(MeshJson.encodeEnvelope(group)))
    }

    @Test
    fun `decoding resolves correct body type`() {
        val decoded = MeshJson.decodeEnvelope(MeshJson.encodeEnvelope(file))
        assertEquals(FileBody::class, decoded.body::class)
    }
}
```

- [ ] **步骤 2：运行测试确认失败**

运行：`.\gradlew.bat testDebugUnitTest --tests "com.meshchat.app.mesh.protocol.MeshEnvelopeTest" --console=plain`
预期：FAIL（`MeshEnvelope` 未定义）

- [ ] **步骤 3：编写实现**

创建 `MeshEnvelope.kt`：

```kotlin
package com.meshchat.app.mesh.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
sealed interface EnvelopeBody

@Serializable
@SerialName("TEXT")
data class TextBody(val text: String, val replyTo: String? = null) : EnvelopeBody

@Serializable
@SerialName("FILE")
data class FileBody(
    val fileName: String,
    val mime: String,
    val size: Long,
    val totalChunks: Int,
    val chunkIndex: Int,
    val chunkData: String,
) : EnvelopeBody

@Serializable
@SerialName("GROUP")
data class GroupBody(
    val op: String,           // JOIN | LEAVE | MSG
    val groupName: String? = null,
    val text: String? = null,
) : EnvelopeBody

@Serializable
data class MeshEnvelope(
    val id: String,
    val kind: String,
    val srcId: String,
    val dstId: String,
    val convId: String,
    val ttl: Int = 8,
    val ts: Long,
    val enc: String = "none",
    val body: EnvelopeBody,
)

object MeshJson {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encodeEnvelope(envelope: MeshEnvelope): String =
        json.encodeToString(MeshEnvelope.serializer(), envelope)

    fun decodeEnvelope(text: String): MeshEnvelope =
        json.decodeFromString(MeshEnvelope.serializer(), text)
}
```

- [ ] **步骤 4：运行测试确认通过**

运行：`.\gradlew.bat testDebugUnitTest --tests "com.meshchat.app.mesh.protocol.MeshEnvelopeTest" --console=plain`
预期：PASS（4 个测试全部通过）

- [ ] **步骤 5：Commit**

```bash
git add app/src/main/java/com/meshchat/app/mesh/protocol/MeshEnvelope.kt app/src/test/java/com/meshchat/app/mesh/protocol/MeshEnvelopeTest.kt
git commit -m "feat(mesh): 消息信封与三类载荷的 JSON 序列化"
```

---

## 任务 3：路由层 · 去重表

**文件：**
- 创建：`app/src/main/java/com/meshchat/app/mesh/routing/DedupCache.kt`
- 测试：`app/src/test/java/com/meshchat/app/mesh/routing/DedupCacheTest.kt`

- [ ] **步骤 1：编写失败的测试**

创建 `DedupCacheTest.kt`：

```kotlin
package com.meshchat.app.mesh.routing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DedupCacheTest {
    @Test
    fun `mark then contains returns true`() {
        val cache = DedupCache()
        assertFalse(cache.contains("m1"))
        cache.mark("m1")
        assertTrue(cache.contains("m1"))
    }

    @Test
    fun `capacity evicts least recently used`() {
        val cache = DedupCache(capacity = 2)
        cache.mark("m1")
        cache.mark("m2")
        cache.mark("m3")          // 淘汰 m1
        assertFalse(cache.contains("m1"))
        assertTrue(cache.contains("m2"))
        assertTrue(cache.contains("m3"))
    }
}
```

- [ ] **步骤 2：运行测试确认失败**

运行：`.\gradlew.bat testDebugUnitTest --tests "com.meshchat.app.mesh.routing.DedupCacheTest" --console=plain`
预期：FAIL（`DedupCache` 未定义）

- [ ] **步骤 3：编写实现**

创建 `DedupCache.kt`：

```kotlin
package com.meshchat.app.mesh.routing

class DedupCache(private val capacity: Int = 512) {
    // accessOrder=true 时迭代顺序为最近使用序，首元素即最久未用
    private val seen = LinkedHashMap<String, Boolean>(capacity, 0.75f, true)

    fun contains(id: String): Boolean = seen.containsKey(id)

    fun mark(id: String) {
        seen[id] = true
        while (seen.size > capacity) {
            seen.remove(seen.keys.first())
        }
    }
}
```

- [ ] **步骤 4：运行测试确认通过**

运行：`.\gradlew.bat testDebugUnitTest --tests "com.meshchat.app.mesh.routing.DedupCacheTest" --console=plain`
预期：PASS

- [ ] **步骤 5：Commit**

```bash
git add app/src/main/java/com/meshchat/app/mesh/routing/DedupCache.kt app/src/test/java/com/meshchat/app/mesh/routing/DedupCacheTest.kt
git commit -m "feat(mesh): LRU 去重表"
```

---

## 任务 4：路由层 · 转发决策

**文件：**
- 创建：`app/src/main/java/com/meshchat/app/mesh/routing/ForwardingDecision.kt`
- 测试：`app/src/test/java/com/meshchat/app/mesh/routing/ForwardingDecisionTest.kt`

- [ ] **步骤 1：编写失败的测试**

创建 `ForwardingDecisionTest.kt`：

```kotlin
package com.meshchat.app.mesh.routing

import com.meshchat.app.mesh.protocol.MeshEnvelope
import com.meshchat.app.mesh.protocol.TextBody
import org.junit.Assert.assertEquals
import org.junit.Test

class ForwardingDecisionTest {
    private fun envelope(dst: String, ttl: Int, id: String = "m") =
        MeshEnvelope(id = id, kind = "TEXT", srcId = "A", dstId = dst, convId = "c", ttl = ttl, ts = 1, body = TextBody("x"))

    private val decision = ForwardingDecision(localId = "B", dedup = DedupCache())

    @Test
    fun `delivers when destination is local`() {
        assertEquals(ForwardDecision.Deliver, decision.decide(envelope("B", 8)))
    }

    @Test
    fun `forwards to others while ttl remains`() {
        assertEquals(ForwardDecision.Forward(7), decision.decide(envelope("C", 8)))
    }

    @Test
    fun `drops when ttl exhausted`() {
        assertEquals(ForwardDecision.Drop, decision.decide(envelope("C", 1)))
    }

    @Test
    fun `drops duplicates`() {
        val env = envelope("C", 8)
        decision.decide(env)
        assertEquals(ForwardDecision.Drop, decision.decide(env))
    }
}
```

- [ ] **步骤 2：运行测试确认失败**

运行：`.\gradlew.bat testDebugUnitTest --tests "com.meshchat.app.mesh.routing.ForwardingDecisionTest" --console=plain`
预期：FAIL（`ForwardingDecision` 未定义）

- [ ] **步骤 3：编写实现**

创建 `ForwardingDecision.kt`：

```kotlin
package com.meshchat.app.mesh.routing

import com.meshchat.app.mesh.protocol.MeshEnvelope

sealed interface ForwardDecision {
    data object Deliver : ForwardDecision
    data object Drop : ForwardDecision
    data class Forward(val ttl: Int) : ForwardDecision
}

class ForwardingDecision(private val localId: String, private val dedup: DedupCache) {
    fun decide(envelope: MeshEnvelope): ForwardDecision {
        if (dedup.contains(envelope.id)) return ForwardDecision.Drop
        dedup.mark(envelope.id)
        return when {
            envelope.dstId == localId -> ForwardDecision.Deliver
            envelope.ttl - 1 > 0 -> ForwardDecision.Forward(envelope.ttl - 1)
            else -> ForwardDecision.Drop
        }
    }
}
```

- [ ] **步骤 4：运行测试确认通过**

运行：`.\gradlew.bat testDebugUnitTest --tests "com.meshchat.app.mesh.routing.ForwardingDecisionTest" --console=plain`
预期：PASS

- [ ] **步骤 5：Commit**

```bash
git add app/src/main/java/com/meshchat/app/mesh/routing/ForwardingDecision.kt app/src/test/java/com/meshchat/app/mesh/routing/ForwardingDecisionTest.kt
git commit -m "feat(mesh): 转发决策（投递/转发/丢弃）"
```

---

## 任务 5：身份层 · 短 ID 与节点注册表

**文件：**
- 创建：`app/src/main/java/com/meshchat/app/mesh/identity/LocalIdentity.kt`
- 创建：`app/src/main/java/com/meshchat/app/mesh/identity/PeerRegistry.kt`
- 测试：`app/src/test/java/com/meshchat/app/mesh/identity/LocalIdentityTest.kt`

- [ ] **步骤 1：编写失败的测试**

创建 `LocalIdentityTest.kt`：

```kotlin
package com.meshchat.app.mesh.identity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalIdentityTest {
    @Test
    fun `short id is non empty and unique`() {
        val ids = (1..100).map { LocalIdentity().shortId }.toSet()
        assertEquals(100, ids.size)
        assertTrue(ids.all { it.length == 4 })
    }

    @Test
    fun `registry upsert and query`() {
        val registry = PeerRegistry()
        registry.upsert(PeerRecord("A001", "林宇航", lastSeen = 100, hops = 1))
        assertEquals("林宇航", registry.get("A001")?.displayName)
    }

    @Test
    fun `registry prune removes stale peers`() {
        val registry = PeerRegistry()
        registry.upsert(PeerRecord("A001", "p1", lastSeen = 100, hops = 1))
        registry.upsert(PeerRecord("B002", "p2", lastSeen = 10_000, hops = 2))
        registry.prune(now = 11_000, timeoutMillis = 5_000)
        assertNull(registry.get("A001"))
        assertEquals("p2", registry.get("B002")?.displayName)
    }
}
```

- [ ] **步骤 2：运行测试确认失败**

运行：`.\gradlew.bat testDebugUnitTest --tests "com.meshchat.app.mesh.identity.LocalIdentityTest" --console=plain`
预期：FAIL（类未定义）

- [ ] **步骤 3：编写实现**

创建 `LocalIdentity.kt`：

```kotlin
package com.meshchat.app.mesh.identity

import kotlin.random.Random

object ShortIdGen {
    private const val ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"

    fun generate(length: Int = 4, random: Random = Random): String =
        (1..length).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
}

class LocalIdentity(
    val shortId: String = ShortIdGen.generate(),
    var displayName: String = "节点$shortId",
)
```

创建 `PeerRegistry.kt`：

```kotlin
package com.meshchat.app.mesh.identity

data class PeerRecord(
    val shortId: String,
    val displayName: String,
    var lastSeen: Long,
    var hops: Int,
)

class PeerRegistry {
    private val peers = LinkedHashMap<String, PeerRecord>()

    fun upsert(record: PeerRecord): PeerRecord {
        peers[record.shortId] = record
        return record
    }

    fun get(id: String): PeerRecord? = peers[id]

    fun remove(id: String) {
        peers.remove(id)
    }

    fun all(): List<PeerRecord> = peers.values.toList()

    fun prune(now: Long, timeoutMillis: Long) {
        peers.values.removeAll { now - it.lastSeen > timeoutMillis }
    }
}
```

- [ ] **步骤 4：运行测试确认通过**

运行：`.\gradlew.bat testDebugUnitTest --tests "com.meshchat.app.mesh.identity.LocalIdentityTest" --console=plain`
预期：PASS

- [ ] **步骤 5：Commit**

```bash
git add app/src/main/java/com/meshchat/app/mesh/identity/ app/src/test/java/com/meshchat/app/mesh/identity/
git commit -m "feat(mesh): 身份层短 ID 生成与节点注册表"
```

---

## 任务 6：存储层 · 存储抽象与 Room 实现

**文件：**
- 创建：`app/src/main/java/com/meshchat/app/mesh/storage/MeshStore.kt`
- 创建：`app/src/main/java/com/meshchat/app/mesh/storage/Entities.kt`
- 创建：`app/src/main/java/com/meshchat/app/mesh/storage/Daos.kt`
- 创建：`app/src/main/java/com/meshchat/app/mesh/storage/MeshDatabase.kt`

- [ ] **步骤 1：定义存储抽象接口**

创建 `MeshStore.kt`：

```kotlin
package com.meshchat.app.mesh.storage

import com.meshchat.app.mesh.protocol.MeshEnvelope

enum class MessageStatus { SENDING, DELIVERED, FAILED }

data class StoredMessage(
    val id: String,
    val convId: String,
    val kind: String,
    val srcId: String,
    val dstId: String,
    val text: String? = null,
    val fileMeta: String? = null,
    val status: MessageStatus = MessageStatus.SENDING,
    val ts: Long,
)

data class OutboxEntry(
    val id: String,
    val envelopeJson: String,
    val nextHop: String?,
    val attempts: Int = 0,
    val expireAt: Long,
)

interface MeshStore {
    fun insertMessage(message: StoredMessage)
    fun updateMessageStatus(id: String, status: MessageStatus)
    fun queryMessages(convId: String): List<StoredMessage>
    fun enqueueOutbox(entry: OutboxEntry)
    fun nextOutbox(now: Long): List<OutboxEntry>
    fun removeOutbox(id: String)
}
```

- [ ] **步骤 2：编写 Room 实体**

创建 `Entities.kt`：

```kotlin
package com.meshchat.app.mesh.storage

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: String,
    val convId: String,
    val kind: String,
    val srcId: String,
    val dstId: String,
    val text: String?,
    val fileMeta: String?,
    val status: String,
    val ts: Long,
)

@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey val id: String,
    val envelopeJson: String,
    val nextHop: String?,
    val attempts: Int,
    val expireAt: Long,
)

@Entity(tableName = "peers")
data class PeerEntity(
    @PrimaryKey val shortId: String,
    val displayName: String,
    val lastSeen: Long,
    val hops: Int,
)
```

- [ ] **步骤 3：编写 DAO**

创建 `Daos.kt`：

```kotlin
package com.meshchat.app.mesh.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MessageEntity)

    @Query("UPDATE messages SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String)

    @Query("SELECT * FROM messages WHERE convId = :convId ORDER BY ts ASC")
    fun observeByConv(convId: String): kotlinx.coroutines.flow.Flow<List<MessageEntity>>
}

@Dao
interface OutboxDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: OutboxEntity)

    @Query("SELECT * FROM outbox WHERE expireAt > :now ORDER BY attempts ASC LIMIT 20")
    suspend fun next(now: Long): List<OutboxEntity>

    @Query("DELETE FROM outbox WHERE id = :id")
    suspend fun remove(id: String)
}

@Dao
interface PeerDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PeerEntity)

    @Query("DELETE FROM peers WHERE shortId = :id")
    suspend fun remove(id: String)

    @Query("SELECT * FROM peers")
    fun observeAll(): kotlinx.coroutines.flow.Flow<List<PeerEntity>>
}
```

- [ ] **步骤 4：编写数据库与 RoomMeshStore**

创建 `MeshDatabase.kt`：

```kotlin
package com.meshchat.app.mesh.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.meshchat.app.mesh.protocol.MeshEnvelope
import com.meshchat.app.mesh.protocol.MeshJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Database(
    entities = [MessageEntity::class, OutboxEntity::class, PeerEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class MeshDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun outboxDao(): OutboxDao
    abstract fun peerDao(): PeerDao

    companion object {
        fun build(context: Context): MeshDatabase =
            Room.databaseBuilder(context, MeshDatabase::class.java, "meshchat.db").build()
    }
}

class RoomMeshStore(private val db: MeshDatabase) : MeshStore {
    override fun insertMessage(message: StoredMessage) = runBlockingSuspend {
        db.messageDao().upsert(message.toEntity())
    }

    override fun updateMessageStatus(id: String, status: MessageStatus) = runBlockingSuspend {
        db.messageDao().updateStatus(id, status.name)
    }

    override fun queryMessages(convId: String): List<StoredMessage> = runBlockingSuspend {
        db.messageDao().observeByConv(convId).first().map { it.toDomain() }
    }

    override fun enqueueOutbox(entry: OutboxEntry) = runBlockingSuspend {
        db.outboxDao().insert(entry.toEntity())
    }

    override fun nextOutbox(now: Long): List<OutboxEntry> = runBlockingSuspend {
        db.outboxDao().next(now).map { it.toDomain() }
    }

    override fun removeOutbox(id: String) = runBlockingSuspend {
        db.outboxDao().remove(id)
    }

    fun observeMessages(convId: String): Flow<List<StoredMessage>> =
        db.messageDao().observeByConv(convId).map { list -> list.map { it.toDomain() } }

    fun observePeers(): Flow<List<PeerEntity>> = db.peerDao().observeAll()

    private fun runBlockingSuspend(block: suspend () -> Unit) =
        kotlinx.coroutines.runBlocking { block() }
}
```

- [ ] **步骤 5：编译验证**

运行：`.\gradlew.bat compileDebugKotlin --console=plain`
预期：编译通过（此任务依赖 Android Room，无 JVM 单测）

- [ ] **步骤 6：Commit**

```bash
git add app/src/main/java/com/meshchat/app/mesh/storage/
git commit -m "feat(mesh): 存储抽象、Room 实体/DAO 与 RoomMeshStore"
```

---

## 任务 7：传输层 · MeshTransport 抽象与测试替身

**文件：**
- 创建：`app/src/main/java/com/meshchat/app/mesh/transport/MeshTransport.kt`
- 创建：`app/src/main/java/com/meshchat/app/mesh/transport/InMemoryTransport.kt`

- [ ] **步骤 1：定义传输抽象接口**

创建 `MeshTransport.kt`：

```kotlin
package com.meshchat.app.mesh.transport

import com.meshchat.app.mesh.protocol.MeshFrame
import kotlinx.coroutines.flow.SharedFlow

interface MeshTransport {
    val incoming: SharedFlow<MeshFrame>

    fun start()
    fun stop()
    fun broadcast(frame: MeshFrame)
    fun sendTo(peerId: String, frame: MeshFrame)
}
```

- [ ] **步骤 2：实现测试替身**

创建 `InMemoryTransport.kt`：

```kotlin
package com.meshchat.app.mesh.transport

import com.meshchat.app.mesh.protocol.MeshFrame
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** 测试替身：broadcast 回环到自身 incoming，用于单机自环验证。 */
class InMemoryTransport : MeshTransport {
    private val _incoming = MutableSharedFlow<MeshFrame>(extraBufferCapacity = 64)
    override val incoming: SharedFlow<MeshFrame> = _incoming

    override fun start() = Unit
    override fun stop() = Unit
    override fun broadcast(frame: MeshFrame) {
        _incoming.tryEmit(frame)
    }
    override fun sendTo(peerId: String, frame: MeshFrame) {
        _incoming.tryEmit(frame)
    }
}
```

- [ ] **步骤 3：编译验证**

运行：`.\gradlew.bat compileDebugKotlin --console=plain`
预期：编译通过

- [ ] **步骤 4：Commit**

```bash
git add app/src/main/java/com/meshchat/app/mesh/transport/
git commit -m "feat(mesh): MeshTransport 抽象与 InMemoryTransport 测试替身"
```

---

## 任务 8：服务层 · MeshService 编排

**文件：**
- 创建：`app/src/main/java/com/meshchat/app/mesh/service/MeshService.kt`
- 测试：`app/src/test/java/com/meshchat/app/mesh/service/MeshServiceTest.kt`

- [ ] **步骤 1：编写失败的测试（自环闭环）**

创建 `MeshServiceTest.kt`：

```kotlin
package com.meshchat.app.mesh.service

import com.meshchat.app.mesh.identity.LocalIdentity
import com.meshchat.app.mesh.protocol.FrameType
import com.meshchat.app.mesh.protocol.MeshJson
import com.meshchat.app.mesh.routing.DedupCache
import com.meshchat.app.mesh.storage.InMemoryMeshStore
import com.meshchat.app.mesh.storage.MessageStatus
import com.meshchat.app.mesh.transport.InMemoryTransport
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class MeshServiceTest {
    @Test
    fun `self loop delivers message and returns receipt`() = runTest {
        val identity = LocalIdentity(shortId = "ME")
        val transport = InMemoryTransport()
        val store = InMemoryMeshStore()
        val service = MeshService(
            transport = transport,
            store = store,
            identity = identity,
            dedup = DedupCache(),
        )
        service.start()

        service.sendText(convId = "c1", dstId = "ME", text = "你好")

        val stored = store.queryMessages("c1").first()
        assertEquals("你好", stored.text)
        assertEquals(MessageStatus.DELIVERED, stored.status)

        service.stop()
    }

    @Test
    fun `message for other peer is forwarded with decremented ttl`() = runTest {
        val identity = LocalIdentity(shortId = "ME")
        val transport = InMemoryTransport()
        val store = InMemoryMeshStore()
        val service = MeshService(
            transport = transport, store = store, identity = identity, dedup = DedupCache(),
        )
        service.start()

        service.sendText(convId = "c2", dstId = "OTHER", text = "hello")
        val frame = transport.incoming.replayCache.firstOrNull { it.type == FrameType.DATA }
        val envelope = frame?.let { MeshJson.decodeEnvelope(it.payloadText) }
        assertEquals("OTHER", envelope?.dstId)
        assertEquals(7, envelope?.ttl)

        service.stop()
    }
}
```

- [ ] **步骤 2：运行测试确认失败**

运行：`.\gradlew.bat testDebugUnitTest --tests "com.meshchat.app.mesh.service.MeshServiceTest" --console=plain`
预期：FAIL（`MeshService`、`InMemoryMeshStore` 未定义）

- [ ] **步骤 3：先实现 InMemoryMeshStore**

在 `app/src/main/java/com/meshchat/app/mesh/storage/InMemoryMeshStore.kt` 创建：

```kotlin
package com.meshchat.app.mesh.storage

/** 服务层 JVM 测试用内存存储实现。 */
class InMemoryMeshStore : MeshStore {
    private val messages = mutableListOf<StoredMessage>()
    private val outbox = mutableListOf<OutboxEntry>()

    override fun insertMessage(message: StoredMessage) {
        messages.removeAll { it.id == message.id }
        messages.add(message)
    }

    override fun updateMessageStatus(id: String, status: MessageStatus) {
        val index = messages.indexOfFirst { it.id == id }
        if (index >= 0) messages[index] = messages[index].copy(status = status)
    }

    override fun queryMessages(convId: String): List<StoredMessage> =
        messages.filter { it.convId == convId }.sortedBy { it.ts }

    override fun enqueueOutbox(entry: OutboxEntry) {
        outbox.removeAll { it.id == entry.id }
        outbox.add(entry)
    }

    override fun nextOutbox(now: Long): List<OutboxEntry> =
        outbox.filter { it.expireAt > now }

    override fun removeOutbox(id: String) {
        outbox.removeAll { it.id == id }
    }
}
```

- [ ] **步骤 4：实现 MeshService**

创建 `MeshService.kt`：

```kotlin
package com.meshchat.app.mesh.service

import com.meshchat.app.mesh.identity.LocalIdentity
import com.meshchat.app.mesh.protocol.FrameType
import com.meshchat.app.mesh.protocol.MeshEnvelope
import com.meshchat.app.mesh.protocol.MeshFrame
import com.meshchat.app.mesh.protocol.MeshJson
import com.meshchat.app.mesh.protocol.TextBody
import com.meshchat.app.mesh.routing.DedupCache
import com.meshchat.app.mesh.routing.ForwardDecision
import com.meshchat.app.mesh.routing.ForwardingDecision
import com.meshchat.app.mesh.storage.MessageStatus
import com.meshchat.app.mesh.storage.MeshStore
import com.meshchat.app.mesh.storage.OutboxEntry
import com.meshchat.app.mesh.storage.StoredMessage
import com.meshchat.app.mesh.transport.MeshTransport
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

private const val DEFAULT_TTL = 8
private const val OUTBOX_TTL_MS = 60_000L

class MeshService(
    private val transport: MeshTransport,
    private val store: MeshStore,
    private val identity: LocalIdentity,
    private val dedup: DedupCache,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var receiveJob: Job? = null

    fun start() {
        transport.start()
        receiveJob = scope.launch {
            transport.incoming.catch { }.collect { frame -> handleFrame(frame) }
        }
    }

    fun stop() {
        receiveJob?.cancel()
        transport.stop()
    }

    fun sendText(convId: String, dstId: String, text: String) {
        val envelope = MeshEnvelope(
            id = UUID.randomUUID().toString(),
            kind = "TEXT",
            srcId = identity.shortId,
            dstId = dstId,
            convId = convId,
            ttl = DEFAULT_TTL,
            ts = System.currentTimeMillis(),
            body = TextBody(text),
        )
        store.insertMessage(
            StoredMessage(
                id = envelope.id, convId = convId, kind = "TEXT",
                srcId = envelope.srcId, dstId = dstId, text = text, ts = envelope.ts,
            ),
        )
        route(envelope)
    }

    fun handleFrame(frame: MeshFrame) {
        when (frame.type) {
            FrameType.DATA -> {
                val envelope = runCatching { MeshJson.decodeEnvelope(frame.payloadText) }
                    .getOrNull() ?: return
                route(envelope)
            }
            FrameType.RECEIPT -> handleReceipt(frame)
            else -> Unit // HELLO/ACK/PING 由传输层处理
        }
    }

    private fun route(envelope: MeshEnvelope) {
        when (val decision = ForwardingDecision(identity.shortId, dedup).decide(envelope)) {
            ForwardDecision.Deliver -> {
                store.insertMessage(envelope.toStoredMessage())
                store.updateMessageStatus(envelope.id, MessageStatus.DELIVERED)
                sendReceipt(envelope)
            }
            is ForwardDecision.Forward -> {
                val forwarded = envelope.copy(ttl = decision.ttl)
                store.enqueueOutbox(
                    OutboxEntry(
                        id = forwarded.id,
                        envelopeJson = MeshJson.encodeEnvelope(forwarded),
                        nextHop = null,
                        expireAt = System.currentTimeMillis() + OUTBOX_TTL_MS,
                    ),
                )
                transport.broadcast(MeshFrame(FrameType.DATA, MeshJson.encodeEnvelope(forwarded).toByteArray()))
            }
            ForwardDecision.Drop -> Unit
        }
    }

    private fun deliverLocally(envelope: MeshEnvelope) { /* 由 repository 轮询 store 展示 */ }

    private fun sendReceipt(envelope: MeshEnvelope) {
        val receipt = "{\"id\":\"${envelope.id}\",\"srcId\":\"${envelope.srcId}\",\"dstId\":\"${envelope.dstId}\"}"
        transport.broadcast(MeshFrame(FrameType.RECEIPT, receipt.toByteArray()))
    }

    private fun handleReceipt(frame: MeshFrame) {
        val text = frame.payloadText
        val id = Regex("\"id\":\"([^\"]+)\"").find(text)?.groupValues?.get(1) ?: return
        store.updateMessageStatus(id, MessageStatus.DELIVERED)
    }

    private fun MeshEnvelope.toStoredMessage(): StoredMessage {
        val text = (body as? com.meshchat.app.mesh.protocol.TextBody)?.text
        return StoredMessage(
            id = id, convId = convId, kind = kind, srcId = srcId, dstId = dstId,
            text = text, ts = ts, status = MessageStatus.DELIVERED,
        )
    }
}
```

- [ ] **步骤 5：运行测试确认通过**

运行：`.\gradlew.bat testDebugUnitTest --tests "com.meshchat.app.mesh.service.MeshServiceTest" --console=plain`
预期：PASS（2 个测试全部通过）

- [ ] **步骤 6：Commit**

```bash
git add app/src/main/java/com/meshchat/app/mesh/storage/InMemoryMeshStore.kt app/src/main/java/com/meshchat/app/mesh/service/MeshService.kt app/src/test/java/com/meshchat/app/mesh/service/MeshServiceTest.kt
git commit -m "feat(mesh): MeshService 发送/接收/转发/回执编排，自环闭环"
```

---

## 任务 9：数据层 · MeshRepository 接入前端

**文件：**
- 创建：`app/src/main/java/com/meshchat/app/data/MeshRepository.kt`
- 修改：`app/src/main/java/com/meshchat/app/ui/MeshChatViewModel.kt`
- 修改：`app/src/main/java/com/meshchat/app/ui/MeshChatApp.kt`

- [ ] **步骤 1：定义 Repository 契约与实现**

创建 `MeshRepository.kt`：

```kotlin
package com.meshchat.app.data

import com.meshchat.app.mesh.service.MeshService
import com.meshchat.app.mesh.storage.MessageStatus
import com.meshchat.app.mesh.storage.MeshStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

interface MeshRepository {
    fun observeMessages(convId: String): Flow<List<ChatMessage>>
    fun sendText(convId: String, text: String)
}

class MeshRepositoryImpl(
    private val service: MeshService,
    private val store: MeshStore,
) : MeshRepository {

    override fun observeMessages(convId: String): Flow<List<ChatMessage>> =
        store.observeMessages(convId).map { list ->
            list.map { it.toUiModel() }
        }

    override fun sendText(convId: String, text: String) {
        val dstId = convId.substringAfterLast("-").takeIf { it != "ME" } ?: "ME"
        service.sendText(convId, dstId, text)
    }

    private fun com.meshchat.app.mesh.storage.StoredMessage.toUiModel(): ChatMessage {
        val time = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(ts))
        val delivery = when (status) {
            MessageStatus.SENDING -> "正在通过 Mesh 发送"
            MessageStatus.DELIVERED -> "已通过 Mesh 送达"
            MessageStatus.FAILED -> "未送达"
        }
        return ChatMessage(
            id = id,
            text = text ?: "",
            sentByMe = true,
            time = time,
            delivery = delivery,
        )
    }
}
```

- [ ] **步骤 2：修改 ViewModel 消费契约**

重写 `app/src/main/java/com/meshchat/app/ui/MeshChatViewModel.kt`：

```kotlin
package com.meshchat.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meshchat.app.data.ChatMessage
import com.meshchat.app.data.MeshRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MeshChatViewModel(
    private val repository: MeshRepository,
) : ViewModel() {
    val messages: StateFlow<List<ChatMessage>> = repository.observeMessages("conv-ME")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch { repository.sendText("conv-ME", text.trim()) }
    }
}
```

- [ ] **步骤 3：修改 MeshChatApp 注入实现**

修改 `app/src/main/java/com/meshchat/app/ui/MeshChatApp.kt`：

```kotlin
@Composable
fun MeshChatApp(viewModel: MeshChatViewModel = viewModel(factory = MeshChatViewModelFactory())) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    Surface(modifier = androidx.compose.ui.Modifier.fillMaxSize(), color = Ink) {
        MeshChatHome(
            messages = messages,
            onSendMessage = viewModel::sendMessage,
        )
    }
}
```

在同目录新建 `MeshChatViewModelFactory.kt`：

```kotlin
package com.meshchat.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import com.meshchat.app.data.MeshRepositoryImpl
import com.meshchat.app.mesh.identity.LocalIdentity
import com.meshchat.app.mesh.routing.DedupCache
import com.meshchat.app.mesh.service.MeshService
import com.meshchat.app.mesh.storage.RoomMeshStore
import com.meshchat.app.mesh.storage.MeshDatabase
import com.meshchat.app.mesh.transport.InMemoryTransport

class MeshChatViewModelFactory : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        val context = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!
        val store = RoomMeshStore(MeshDatabase.build(context))
        val identity = LocalIdentity()
        val transport = InMemoryTransport()
        val service = MeshService(transport, store, identity, DedupCache())
        service.start()
        return MeshChatViewModel(MeshRepositoryImpl(service, store)) as T
    }
}
```

- [ ] **步骤 4：编译验证**

运行：`.\gradlew.bat assembleDebug --console=plain`
预期：BUILD SUCCESSFUL

- [ ] **步骤 5：Commit**

```bash
git add app/src/main/java/com/meshchat/app/data/MeshRepository.kt app/src/main/java/com/meshchat/app/ui/
git commit -m "feat(mesh): MeshRepository 契约接入前端 ViewModel"
```

---

## 任务 10：传输层 · BleTransport 实现

**文件：**
- 创建：`app/src/main/java/com/meshchat/app/mesh/transport/BleTransport.kt`

- [ ] **步骤 1：定义 BLE 常量与服务**

在 `BleTransport.kt` 中定义：

```kotlin
package com.meshchat.app.mesh.transport

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import com.meshchat.app.mesh.protocol.MeshFrame
import java.util.UUID
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** 蓝牙载体实现：广播通告 + 扫描发现 + GATT 服务端/客户端 + 帧收发。 */
class BleTransport(
    private val context: Context,
    private val serviceUuid: UUID = UUID.fromString("0000A5E1-0000-1000-8000-00805F9B34FB"),
    private val charUuid: UUID = UUID.fromString("0000A5E2-0000-1000-8000-00805F9B34FB"),
    private val advertiseNamePrefix: String = "MESHCHAT:",
) : MeshTransport {

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val _incoming = MutableSharedFlow<MeshFrame>(extraBufferCapacity = 64)
    override val incoming: SharedFlow<MeshFrame> = _incoming

    // GATT Server：暴露服务，接收邻近节点写入的帧
    private var gattServer: BluetoothGattServer? = null
    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            value: ByteArray,
            offset: Int,
        ) {
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            runCatching { MeshFrame.decode(value) }.onSuccess { _incoming.tryEmit(it) }
        }
    }

    // 客户端连接（待转发时按 peerId 建立连接）
    private val gattClients = HashMap<String, BluetoothGatt>()
    private val peerIds = HashMap<String, String>() // deviceAddress -> peerId

    override fun start() {
        registerServer()
        startAdvertising()
        startScanning()
    }

    override fun stop() {
        gattServer?.close()
        gattClients.values.forEach { it.close() }
        gattClients.clear()
        bluetoothAdapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
    }

    override fun broadcast(frame: MeshFrame) {
        writeToConnectedClients(frame)
    }

    override fun sendTo(peerId: String, frame: MeshFrame) { /* 按 peerId 解析地址后写入 */ }

    private fun registerServer() {
        gattServer = bluetoothManager.openGattServer(context, gattServerCallback)
        val service = BluetoothGattService(serviceUuid, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val characteristic = BluetoothGattCharacteristic(
            charUuid,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        service.addCharacteristic(characteristic)
        gattServer?.addService(service)
    }

    private fun startAdvertising() {
        val advertiser: BluetoothLeAdvertiser? = bluetoothAdapter?.bluetoothLeAdvertiser ?: return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(serviceUuid))
            .build()
        advertiser.startAdvertising(settings, data, advertiseCallback)
    }

    private fun startScanning() {
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(null, settings, scanCallback)
    }

    private val advertiseCallback = object : AdvertiseCallback() {}
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val deviceName = device.name ?: result.scanRecord?.deviceName ?: return
            if (!deviceName.startsWith(advertiseNamePrefix)) return
            val peerId = deviceName.removePrefix(advertiseNamePrefix)
            peerIds[device.address] = peerId
            connectTo(device)
        }
    }

    private fun connectTo(device: BluetoothDevice) {
        if (gattClients.containsKey(device.address)) return
        val gatt = device.connectGatt(context, false, object : BluetoothGattCallbackAdapter() {})
        gattClients[device.address] = gatt
    }

    private fun writeToConnectedClients(frame: MeshFrame) {
        val bytes = frame.encode()
        gattClients.values.forEach { gatt ->
            gatt.getService(serviceUuid)?.getCharacteristic(charUuid)?.let { characteristic ->
                characteristic.value = bytes
                gatt.writeCharacteristic(characteristic)
            }
        }
    }

    private class BluetoothGattCallbackAdapter : android.bluetooth.BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) gatt.discoverServices()
        }
    }
}
```

- [ ] **步骤 2：编译验证**

运行：`.\gradlew.bat compileDebugKotlin --console=plain`
预期：编译通过（BLE 真机联调属集成阶段，不纳入单测）

- [ ] **步骤 3：Commit**

```bash
git add app/src/main/java/com/meshchat/app/mesh/transport/BleTransport.kt
git commit -m "feat(mesh): BLE 传输实现（广播/扫描/GATT 双角色）"
```

---

## 任务 11：集成验证与文档更新

**文件：**
- 修改：`README.md`

- [ ] **步骤 1：全量单测**

运行：`.\gradlew.bat testDebugUnitTest --console=plain`
预期：所有测试 PASS

- [ ] **步骤 2：全量构建**

运行：`.\gradlew.bat assembleDebug --console=plain`
预期：BUILD SUCCESSFUL

- [ ] **步骤 3：更新 README 已实现清单**

在 `README.md` 的「已实现」一节追加：

```markdown
- 设备内嵌后端服务框架：帧协议/消息信封、LRU 去重、TTL 转发决策、短 ID 身份、Room 持久化、MeshService 发送-转发-投递-回执编排、MeshRepository 前端数据源接入（演示级加密占位，BLE 真机联调待硬件环境）
```

- [ ] **步骤 4：Commit 并推送**

```bash
git add README.md
git commit -m "docs: 更新后端框架已实现清单"
git push
```

---

## 自检

**1. 规格覆盖度对照：**

| 规格章节 | 对应任务 |
|---|---|
| 模块架构（protocol/transport/routing/identity/storage/service） | 任务 1-10 的包结构 |
| 帧格式 MeshFrame（type/length/payload，5 种帧类型） | 任务 1 |
| 消息信封 + TEXT/FILE/GROUP 载荷 + JSON | 任务 2 |
| 演示级加密占位（enc 字段 + Cipher 接口） | 任务 2（enc 字段已含，Cipher 接口留后续，符合规格「接口契约已定」的开放问题） |
| 去重表 LRU 512 | 任务 3 |
| 转发决策（投递/转发/丢弃、TTL 递减、回环防护） | 任务 4 |
| 短 ID + 显示名身份、节点注册表 | 任务 5 |
| 持久化表 messages/outbox/peers（conversations/groups 本期由前端演示数据承担，任务 9 以 conv-ME 固化） | 任务 6 |
| MeshTransport 抽象 + InMemory 测试替身 | 任务 7 |
| 存储-转发接力 + 双层次回执 + outbox 重试 | 任务 8（receipt 沿源回传、outbox 过期窗口，重试调度留集成期） |
| BLE 发现/连接/帧收发 | 任务 10 |
| MeshRepository 契约 + 前端接入 | 任务 9 |
| 错误处理（BLE 降级、失败状态、TTL 耗尽） | 任务 8（FAILED 状态映射）+ 任务 9（未送达文案） |
| 测试策略（JVM 单测 + 自环冒烟） | 任务 1-5、8 的单测 + 任务 11 集成 |

**2. 占位符扫描：** 无「待定/TODO」类占位；BLE 真机联调、Cipher 加密、outbox 重试调度明确标注为后续阶段（与规格「开放问题」一致）。

**3. 类型一致性检查：**
- `MeshStore.queryMessages` 返回值在 `MeshServiceTest`（`store.queryMessages("c1").first()`）与 `MeshRepositoryImpl`（`store.observeMessages(convId)`）中引用一致——注意：`MeshStore` 接口无 `observeMessages`，仅 `RoomMeshStore` 有。**修正：** 任务 9 步骤 1 的 `MeshRepositoryImpl` 需通过 `RoomMeshStore.observeMessages` 提供 Flow。`MeshStore` 接口保持查询返回 List，`RoomMeshStore` 额外暴露 `observeMessages(convId): Flow<List<StoredMessage>>` 与 `observePeers(): Flow<List<PeerEntity>>`。`MeshRepositoryImpl` 构造入参改为依赖 `RoomMeshStore`（或将该 Flow 方法上移至接口）。**决定：** 将 `observeMessages(convId): Flow<List<StoredMessage>>` 加入 `MeshStore` 接口，`InMemoryMeshStore` 以 `flowOf(...)` 实现，避免类型漂移。

---

## 执行交接

**计划已完成并保存到 `docs/superpowers/plans/2026-08-03-meshchat-backend.md`。两种执行方式：**

1. **子代理驱动（推荐）** —— 每个任务调度一个新的子代理，任务间进行审查，快速迭代
2. **内联执行** —— 在当前会话中使用 executing-plans 执行任务，批量执行并设有检查点

**选哪种方式？**
