# MeshChat MITM 防御设计规格（最小版：公钥指纹 + 密钥连续性告警）

> 日期：2026-08-08
> 状态：已确认（用户选择"最小版"）
> 关联：v1.1.57 E2EE、v1.1.63 AndroidKeyStore 降级

## 1. 背景与目标

用户安全咨询"中间人设备伪造和解密劫持"后确认：当前 E2EE 防被动监听但不防主动中间人（`deriveSessionKey` 无条件接受握手帧公钥，无身份认证）。用户选定**最小版**防御范围：

1. **公钥指纹显示**：握手后展示对端公钥指纹（hex 分组），供用户线下比对。
2. **密钥连续性（TOFU）**：记住首次握手记录的对端公钥指纹，之后变化 → 红色告警（检测 MITM 的核心信号）。
3. **本机降级密钥提示**：华为 ROM 降级内存密钥不持久，身份页提示，避免用户困惑。

明确不做（最小版范围外）：二维码比对、检测后自动断开、自动拉黑、短 ID↔公钥绑定、配对码 SAS、RSSI 异常检测。

## 2. 非目标

- 不做自动阻断/断开（最小版告警不阻断通信，消息照常收发）。
- 不做协议改动（纯本地逻辑，双机无需同时升级；老版本无指纹功能零影响）。
- 不解决 AndroidKeyStore EC 密钥协商在华为 ROM 的限制（硬件层无解，维持 v1.1.63 内存密钥降级）。

## 3. 核心概念

### 3.1 公钥指纹（复用现有 `MeshCrypto.fingerprint`）

- 计算：`SHA-256(对端公钥 SPKI 字节)` 前 **8 字节** → 16 位小写 hex。
- 显示：4 组 4 位（如 `a1b2 c3d4 e5f6 7890`），monospace。
- 单向不可逆：指纹泄露不反推公钥（公钥本就公开，指纹用于人工比对而非保密）。

### 3.2 密钥连续性（Trust On First Use）

- 首次成功握手（`deriveSessionKey` 收到对端公钥）→ 持久化对端公钥指纹。
- 之后每次握手比对：
  - 无记录 → 写入（首次信任）。
  - 一致 → 正常（不标记）。
  - **不一致 → 标记该 peer 为"身份变更"**（红色告警信号）。

## 4. 服务层改动（MeshService）

### 4.1 PeerKeyStore（新文件，仿 blockedStore/sessionStore 模式）

```kotlin
interface PeerKeyStore {
    /** 首次握手记录的对端公钥指纹；无记录 null。 */
    fun fingerprint(peerId: String): String?
    fun saveFingerprint(peerId: String, fp: String)
}
object NoopPeerKeyStore : PeerKeyStore { ... }              // 测试/未注入
class SharedPrefsPeerKeyStore(context: Context) : PeerKeyStore { ... }  // meshchat_e2ee，键 fp_<peerId>
```

### 4.2 状态与指纹比对

- 新增字段：`private val _peerKeyChanged = MutableStateFlow<Set<String>>(emptySet())` + `val peerKeyChanged: StateFlow<Set<String>>`（身份变更集合）。
- 新增 `fun peerFingerprint(peerId: String): String? = peerKeyStore.fingerprint(peerId)`（UI 显示）。
- `deriveSessionKey` 公钥解析成功后、派生会话密钥前插入指纹比对：

```kotlin
// v1.1.74 密钥连续性（TOFU）：比对首次握手记录的对端公钥指纹，变化 → 标记身份变更（MITM 告警）
val fp = MeshCrypto.fingerprint(peerPubB64)
val prev = peerKeyStore.fingerprint(peerId)
if (prev == null) {
    peerKeyStore.saveFingerprint(peerId, fp)
    _peerKeyChanged.update { it - peerId }
} else if (prev != fp) {
    Log.w(TAG, "e2ee: KEY CHANGED for $peerId (prev=$prev now=$fp) — possible MITM")
    _peerKeyChanged.update { it + peerId }
}
```

- 构造新增参数 `peerKeyStore: PeerKeyStore = NoopPeerKeyStore`（向后兼容）。

### 4.3 本机密钥降级标志

- `localKeyPair` lazy 降级分支置 `@Volatile private var keyFallback = true`；成功分支 false。
- 新增 `val localKeyFallback: Boolean get() = keyFallback`（UI 身份页提示"密钥不持久"）。

## 5. 持久化（MeshChatApplication）

- 注入 `SharedPrefsPeerKeyStore(this)` 到 MeshService 构造（e2eeStore 旁）。

## 6. UI 改动

### 6.1 会话页 Header（ConversationScreen.ConversationHeader）

在对方状态行下方新增指纹行（当前会话 target）：
- 无记录（首次/未验证）：`TextSecondary` `指纹 <fp>`（4 组 4 位）。
- 已记录且一致：`MeshGreen` `指纹 <fp> · 与上次一致`。
- **身份变更（peerKeyChanged 含 target）**：`MeshRed` `⚠ 公钥已变化 · 可能被中间人攻击 · 请线下比对指纹`。
- 自环会话（conv-ME）/群会话不显示指纹行。

传参：`ConversationScreen` 新增 `peerFingerprint: String?`、`peerKeyChanged: Boolean`（ViewModel 按当前会话 target 解析）。

### 6.2 身份页本机密钥提示（ProfileDetailScreens Keys 区）

- `localKeyFallback == true` 时显示：`MeshAmber` `本机密钥不持久（设备限制）· 重启后更换，对方可能收到身份变化提示`。

## 7. 边界与例外

- **降级密钥重启场景**：本机重启后公钥变化 → 对端记录过旧指纹 → 对端标记身份变更（红色告警）。这是**事实**（密钥确实变了），文案中性（"重启/重装或中间人，请线下确认"），非误报。
- **首次连接即被 MITM**：TOFU 记录的是攻击者公钥 → 无法事后检出（TOFU 固有局限）；需用户线下比对指纹弥补。规格明示此局限。
- **告警不阻断**：消息照常收发（最小版语义），用户自行决定是否继续。
- **群消息/文件传输**：指纹行仅点对点会话页显示；群消息不显示（泛洪域，身份模型不同）。

## 8. 测试计划

1. **MeshServiceTest**：
   - 首次握手记录对端公钥指纹（`peerFingerprint(peerId)` 非空）；
   - 同指纹再次握手不标记 `peerKeyChanged`；
   - 不同指纹握手标记 `peerKeyChanged` 含该 peer。
2. **MeshCryptoTest**：`fingerprint` 确定性（已存在，补充分组显示断言可选）。
3. 存量回归：`deriveSessionKey` 新增指纹逻辑在公共默认路径（NoopPeerKeyStore）零影响。

## 9. 版本与交付

- versionCode 135 → **136** / versionName "1.1.73" → **"1.1.74"**。
- 涉及文件：
  - 新增：`mesh/service/PeerKeyStore.kt`（接口 + Noop + SharedPrefs）
  - 修改：`mesh/service/MeshService.kt`（peerKeyChanged/peerFingerprint/deriveSessionKey 指纹比对/keyFallback）、`MeshChatApplication.kt`（注入 SharedPrefsPeerKeyStore）、`data/MeshRepository.kt`、`ui/MeshChatViewModel.kt`（peerKeyChanged/peerFingerprint/localKeyFallback 透传）、`ui/screens/ConversationScreen.kt`（Header 指纹行）、`ui/screens/ProfileDetailScreens.kt`（Keys 区降级提示）、`MeshChatApp.kt`/`MeshChatHome.kt`（传参）、`MeshServiceTest.kt`、`app/build.gradle.kts`、`AI_CONTEXT.md`
