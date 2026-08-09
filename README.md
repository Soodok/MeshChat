# MeshChat

> 面向**无公网 / 弱网极端环境**的近场安全通信 Android 应用
> A near-field secure-communication Android app for **offline / weak-network** environments.

## 免责声明 / Disclaimer

> **开源项目可能被用于恶意用途。** 本项目仅用于学习与合法的应急通信研究。开源代码可被任何个人或组织以任何方式复制、修改与使用，包括但不限于违法犯罪用途；作者对任何滥用、误用或由此产生的后果不承担任何责任。使用者须自行确保其使用方式符合所在国家/地区的法律法规。
>
> **Open-source software can be abused.** This project is provided solely for learning and legitimate emergency-communication research. The code may be copied, modified, and used by anyone for any purpose, including illegal ones; the authors assume no liability for any misuse or for the consequences thereof. You are solely responsible for ensuring your usage complies with all applicable laws and regulations.

> **连接距离免责。** 文中的实测距离（空旷室内约 40 米、丛林野外约 20 米）来自特定机型与环境的实测结果。蓝牙有效范围受设备芯片、天线、发射功率档位、遮挡物、天气与电池状态影响，不同机型差异显著，实际距离请以现场实测为准。本项目不保证任何最低通信距离。
>
> **Range disclaimer.** The measured ranges cited here (≈40 m indoors in open space, ≈20 m in jungle/field vegetation) come from tests on specific devices in specific environments. Effective BLE range depends on device chipset, antenna, TX-power setting, obstacles, weather, and battery state, and varies greatly across models — always verify on site. No minimum range is guaranteed.

> **安全边界免责。** MeshChat 的端到端加密（ECDH + AES-256-GCM）保护**传输内容**不被窃听与篡改，密钥连续性（指纹 TOFU）能识别**已建立过会话的对端**公钥更换（重启/重装/被中间人替换）。但它**无法防御首次握手即被主动劫持**：若攻击者在你们的第一次连接时就替换了公钥，指纹告警无从比对。高危场景请线下核对指纹。本项目不替代官方应急通信体系，不构成任何安全性承诺。
>
> **Security disclaimer.** MeshChat's E2EE (ECDH + AES-256-GCM) protects the *content* of communication from eavesdropping and tampering, and key-continuity (fingerprint TOFU) detects when a peer you have already paired with changes its public key (reboot / reinstall / MITM replacement). It **cannot defend against an active hijack on the very first handshake**: if an attacker swaps the public key before your first-ever exchange, there is no stored fingerprint to compare against. Verify fingerprints offline for high-stakes scenarios. This project does not replace official emergency-communication systems and makes no security guarantee.

## 项目简介 / About

不依赖基站、Wi-Fi 路由或任何互联网基础设施，通过蓝牙在设备间自组织形成去中心化网状网络（Mesh）：设备互相发现、建立对话、收发消息与文件。适用于灾后应急、野外作业、地下空间、隔离区等场景。

No cellular base station, Wi-Fi router, or internet infrastructure is required. Devices self-organize into a decentralized mesh over Bluetooth: discovery, conversation setup, and message/file exchange happen directly between devices. Designed for disaster response, field operations, underground spaces, and quarantined areas.

## 实测连接距离 / Measured Range

**空旷室内：约 40 米；丛林野外：约 20 米**（特定机型实测，结果因设备与环境而异）。

**≈40 m indoors in open space; ≈20 m in jungle/field vegetation** (measured on specific hardware; results vary by device and environment).

MeshChat 基于 BLE 广播 + GATT 双通道，在低功率射频下即可维持稳定链路；配合 1s 心跳校准与失联/离线状态机，超出范围时状态实时反映（「断线重连中」→「离线」），回到范围内自动重新建立连接，无需人工干预。

Built on BLE advertising + dual GATT channels, MeshChat keeps links stable at low RF power; the 1 s heartbeat calibration and lost/offline state machine surface range exits in real time (reconnecting → offline) and re-establish links automatically when back in range.

## 特性 / Features

**通信链路（BLE 真机验证，稳定性优先）** — *Communication stack (verified on real BLE hardware, reliability-first)*

- **距离与稳定性**：低功耗 BLE 广播 + 传输功率可调（4 档，默认最高 +1dBm），空旷室内实测可达约 40 米
- 蓝牙发现：Service Data 携带短 ID 识别节点，扫描即持久化恢复
- 对话握手：INVITE → INVITE_ACK 会话锁定，ACK 持续重发收敛（30s 窗口），握手帧零丢失
- 消息收发：双向实时到达，TTL=8 转发决策 + LRU 去重
- **三层送达确认 + 零容错重发**：RECEIPT 回执 / PONG 携带 ackIds / 广播确认键三路冗余；发送方指数退避重发（永不 FAILED、永不移出队列），接收方 3min 重复回执窗口 —— 蓝牙后台空窗、丢帧场景必收敛
- 文件传输：窗口滑动 + bitmap 缺失确认，接收端落盘 Downloads，进度实时
- 心跳校准：1s PING / 200ms 状态刷新，在线-断线重连-离线三色状态机 + 毫秒级信号时间显示；关蓝牙→开自动重建传输层
- 多跳中继：纯中继 TEXT 转发 + RECEIPT 泛洪回传防环 + outbox 1s 重发兜底，2 跳路由学习/过期，列表显示「经 X 可达 · 2跳」
- **GATT 双通道可靠性**：central 写特征 + server notify 双向投递，MTU 512 协商 + 服务发现前暂存补写，低版本 Android 自动分支兼容
- 后台常驻：前台服务 + 通知直达，息屏/切后台消息照收

**端到端加密与安全（v1.1.57+）** — *E2EE & security*

- **端到端加密**：ECDH P-256 密钥协商 + HKDF-SHA256 派生 + AES-256-GCM（每条消息新 IV + 认证标签），空中传输全部密文化，路由字段明文供中继转发；密钥存 AndroidKeyStore（受限 ROM 自动降级内存密钥并如实提示）
- **MITM 防御 · 密钥连续性**：会话页显示对端公钥指纹（SHA-256 截断 hex）；首次握手信任并持久化（TOFU），之后公钥变化（重装/重启/被劫持）→ 红色告警「公钥已变化 · 可能被中间人攻击 · 请线下比对指纹」；本机密钥降级时身份页明确提示
- **隔离控制（真·断开）**：拉黑 / 删除对话 / 换频道 / 关闭搜索 → 立即断开全部既有连接 + 拒绝重连 + 发现层过滤 —— 对方瞬间失联，不存在「消息发不了但还连着你」的残留连接
- **频道系统**：公共频道 + 自定义私人频道（仅频道名，广播只暴露 SHA-256 指纹，不泄露频道名），私人频道内互连不被公共搜索发现
- 应用锁：PBKDF2 210k + KEK + AES-GCM 保护，后台/重启回前台自动锁定，可选生物识别
- 安全中心：本地安全评估（锁屏/调试/无障碍等离线信号）+ AES-GCM 加密事件记录（禁备份）+ 权限能力状态页 + 证据字段白名单
- 对话归档/删除/未读标记、拉黑名单管理、发现模式（正常/关闭/静默）

**群聊与多端** — *Group & transports*

- 群聊：泛洪广播域群会话（加密），群成员学习与去重，回执节流防风暴
- Wi-Fi Direct 增强（实验性）：免配对 DnsSd 发现 + TCP 传输，为高吞吐场景预留（默认关闭）

**Mesh 拓扑可视化** — *Mesh topology visualization*

- 力导向网状布局：库仑斥力 + 弹簧力 + 中心引力，去中心化自然分布
- 节点可拖拽，松手自动回归物理模拟
- 状态色制：直连绿 / 可达蓝 / 寻找中黄虚线，失联节点不绘制连接
- 相对尺寸自适应：节点、连线、字号随画布缩放

**工程** — *Engineering*

- 深色高信息密度视觉：墨蓝底色、Mesh 青色强调、终端风格等宽字体
- 207 项单元测试通过（协议/路由/身份/存储/传输/服务编排/多跳中继/文件传输/调试统计/安全中心/群聊/频道/加密）
- 正式签名 + R8 混淆 release 包（约 1.9 MB）
- 调试中心：实时仪表盘（收发包速率/帧类型分布/GATT 状态/路由表/送达链路），可调广播功率与刷新参数

## 架构 / Architecture

```
app/src/main/java/com/meshchat/app/
├── data/            # 前端数据源（MeshRepository 契约 + 实现）
├── mesh/
│   ├── protocol/    # 帧编解码、消息信封 + TEXT/FILE/GROUP 载荷、JSON 序列化
│   ├── routing/     # LRU 去重表、转发决策
│   ├── identity/    # 短 ID 身份（持久化）、节点注册表
│   ├── channel/     # 频道指纹（公共/私人频道隔离）
│   ├── crypto/      # E2EE 内核：ECDH/HKDF/AES-GCM、密钥存储、指纹
│   ├── storage/     # Room 持久化（消息/节点/会话，加密存储）
│   ├── transfer/    # 文件传输引擎（窗口/ACK 状态机）
│   ├── service/     # MeshService：发送-转发-投递-回执编排 + 前台服务 + 会话/拉黑/指纹存储
│   ├── transport/   # BLE 传输（GATT 双通道）、RFCOMM（留档）
│   └── wifidirect/  # Wi-Fi Direct 实验性载体（DnsSd + TCP）
├── security/        # 应用锁、安全能力、本地风险评估、加密事件记录
└── ui/              # Compose 界面（聊天/拓扑/设置/身份/安全中心/调试中心）
```

设备内嵌去中心化后端：每个节点既是客户端也是中继，消息通过邻居逐跳转发，无中心服务器。
An embedded decentralized backend: every node acts as both client and relay; messages hop through neighbors. No central server.

## 技术栈 / Tech Stack

- Kotlin 2.2.10 · Jetpack Compose · Material3
- AGP 9.0.0 · Gradle 9.1.0 · KSP · Room 2.7 · kotlinx-serialization
- minSdk 26（Android 8.0）/ targetSdk 36

## 构建与运行 / Build & Run

```bash
# 单元测试 / Unit tests
./gradlew testDebugUnitTest

# 正式包（R8 混淆 + 签名；需 keystore.properties，见下文）
# Release (R8 + signing; requires keystore.properties, see below)
./gradlew assembleRelease
```

- 用 Android Studio 打开工程根目录，同步后运行 `app` 模块 / Open the project root in Android Studio and run the `app` module.
- 需要真机蓝牙（API 26+）；Android 11 及以下还需开启系统位置服务（BLE 扫描依赖）/ Requires real-device Bluetooth (API 26+); Android 11 and below also need the system location service enabled (BLE scanning depends on it).
- 正式签名凭证存放在 `keystore.properties`（不入库）。**keystore 与密码务必自行备份，丢失将无法更新已发布版本** / Signing credentials live in `keystore.properties` (not committed). **Back up the keystore and passwords — losing them makes it impossible to update released builds.**

## 边界 / Limitations

- 多跳中继的路由学习与 UI 显示当前支持 2 跳（一跳直连 + 二跳经单一中继），2 跳以上不显示路由 / Multi-hop routing supports 2 hops (direct + one relay); beyond 2 hops routes are not displayed.
- 端到端加密无法防御**首次握手即被主动劫持**（密钥连续性依赖首次信任记录），高危场景请线下比对指纹 / E2EE cannot defend against hijacking on the very first handshake (key continuity relies on first-trust); verify fingerprints offline for high-stakes scenarios.
- 华为等受限 ROM 上 AndroidKeyStore 可能不支持密钥协商，自动降级为内存密钥（加密仍生效，重启后需重新握手） / On restricted ROMs (e.g. some Huawei builds) AndroidKeyStore may not support key agreement; the app falls back to in-memory keys (encryption still works; re-handshake needed after reboot).
- 蓝牙有效范围受设备与遮挡影响，实测空旷室内约 40 米、丛林野外约 20 米（因机型与环境而异） / Effective BLE range depends on device and obstacles; measured ≈40 m indoors in open space and ≈20 m in jungle vegetation (varies by device and environment).
- Wi-Fi Direct 载体为实验性（默认关闭）/ Wi-Fi Direct transport is experimental (off by default).

## 设计基准 / Design Baseline

视觉基准图见 `design/meshchat-visual-baseline.png`：墨蓝底色、青色强调、绿色连接、高信息密度终端风格。
Visual baseline: `design/meshchat-visual-baseline.png` — ink-blue background, cyan accents, green links, high-density terminal style.

## 许可 / License

MIT License

本软件按"现状"提供，无任何明示或暗示担保。详见 `LICENSE`。
This software is provided "as is", without warranty of any kind, express or implied. See `LICENSE`.
