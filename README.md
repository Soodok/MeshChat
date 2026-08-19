# MeshChat

> 面向**无公网 / 弱网极端环境**的近场安全通信 Android 应用
> A near-field secure-communication Android app for **offline / weak-network** environments.

![License: MIT](https://img.shields.io/badge/License-MIT-green) ![Language: Kotlin](https://img.shields.io/badge/Language-Kotlin-purple) ![Platform: Android 8.0+](https://img.shields.io/badge/Platform-Android%208.0%2B-blue) ![Version: v1.1.91](https://img.shields.io/badge/Version-v1.1.91-cyan) ![Tests: 221 passed](https://img.shields.io/badge/Tests-221%20passed-brightgreen) ![Encryption: E2EE](https://img.shields.io/badge/Encryption-E2EE%20(AES--256--GCM)-critical)

## 免责声明 / Disclaimer

> 本项目仅用于学习与合法的应急通信研究。开源代码可被任何个人或组织以任何方式复制、修改与使用，包括但不限于违法犯罪用途；作者对任何滥用、误用或由此产生的后果不承担任何责任。实测距离（空旷室内约 40 米、丛林野外约 20 米，测试机型蓝牙 5.1）因设备与环境而异，请以现场实测为准。端到端加密保护传输内容，但无法防御第一次连接就被劫持，高危场景请线下核对指纹。本项目不构成任何安全性承诺。
>
> For learning and legitimate emergency-communication research only. Code may be reused for any purpose, including illegal ones; no liability for misuse. Measured ranges (≈40 m indoors, ≈20 m in jungle; Bluetooth 5.1 test devices) vary by hardware and environment — verify on site. E2EE protects content but cannot stop hijacking on the very first connection; verify fingerprints offline for high-stakes scenarios. No security guarantee implied.

## 项目简介 / About

不依赖基站、Wi-Fi 或任何互联网，仅靠蓝牙就能在设备之间组成一张去中心化网络：互相发现、建立对话、收发消息和文件。没信号的地方也能聊。可选开启 Wi-Fi Direct 星域高速通道，与蓝牙双链路送达，消息更稳、文件更快。

No base station, Wi-Fi, or internet required. Just Bluetooth. Devices form a decentralized mesh to find each other, chat, and exchange files — even where there is no signal. Optionally enable the Wi-Fi Direct fast channel for dual-path delivery: more reliable messaging, faster files.

## 实测连接距离 / Measured Range

**蓝牙 5.1 实测：空旷室内约 40 米，丛林野外约 20 米。**

发射功率 4 档可调（默认最高），近距离降功率省电、远距离拉满换覆盖；走远了自动显示「断线重连中 / 离线」，走回来自动重连，无需手动操作。蓝牙断开时，已连接设备的 Wi-Fi Direct 星域自动顶上，消息仍双链路送达。

*Measured on Bluetooth 5.1 devices: ≈40 m indoors in open space, ≈20 m in jungle vegetation. TX power is adjustable across 4 levels to trade battery for range; out-of-range shows reconnecting/offline and reconnects automatically. When Bluetooth drops, the Wi-Fi Direct link on known peers takes over so messages still get through on both paths.*

## 差异化能力 / What Makes It Different

- **不连接也能通讯**：基于蓝牙广播收发消息，无需配对、无需稳定连接——连接只让通讯更快，不是前提
- **去中心化自愈多跳**：每台设备都是路由器，隔一台设备也能送达，有人离开自动绕路
- **端到端加密**：ECDH P-256 密钥协商 + AES-256-GCM，消息出手机前加密；每个对话显示对方公钥指纹
- **Wi-Fi Direct 星域高速通道**：与邻近设备自动成组，消息/回执/心跳双链路并行送达（按帧 ID 去重），文件块优先走高速通道
- **混合组网**：蓝牙失联时经 Wi-Fi Direct 发布，中继设备靠蓝牙继续传递——A-WiFi-B-BLE-C 双向回传，大型现场多组靠蓝牙互通
- **私人频道**：自定义频道名组一个「只有我们」的空间，不参与公共搜索
- **彻底拉黑**：拉黑 / 删除对话 / 换频道，立即断开全部连接，对方再也看不到你
- **消息必达**：广播丢包、切后台、息屏都不怕，自动重发确认直到对方收到
- **隐私逃生**：被威胁时快速连点应用标题 6 下，一键清除全部本地数据并退出（首次进入会告知该机制）
- **应用锁**：密码 / 指纹解锁，会话与群密钥以 AndroidKeyStore 最高强度加密存储，回前台自动锁定

*Broadcast-based messaging with no pairing or stable connection required. Every device is a router — multi-hop delivery that reroutes around gaps. E2EE (ECDH P-256 + AES-256-GCM) with per-conversation key fingerprints. Wi-Fi Direct mesh for dual-path delivery and fast file transfer; hybrid relaying (A-WiFi-B-BLE-C) keeps the mesh connected even when Bluetooth drops. Private channels invisible to public search. Blocking cuts every connection instantly. Messages are auto-retried until delivered. Emergency privacy wipe: tap the app title 6 times fast to erase all local data. App lock with password / fingerprint backed by AndroidKeyStore.*

## 基础功能 / Also Included

聊天、文件传输、群聊、多跳中继、后台常驻、应用锁、拓扑图、调试中心、离线安全评估——该有的都有，不啰嗦。

Chat, file transfer, group chat, background service, app lock, mesh topology, debug center, offline security audit — the essentials, all included.

## 技术栈 / Tech Stack

Kotlin · Jetpack Compose · Room · Kotlin 协程 · Android 8.0+（API 26）
蓝牙 BLE（广播 / 扫描 / GATT 可靠通道）· Wi-Fi Direct（星域高速通道）· 多跳中继
端到端加密：ECDH P-256 密钥协商 + AES-256-GCM · 应用锁（AndroidKeyStore）
221 项单元测试，正式包 R8 混淆 + 签名（约 1.5 MB）。

Kotlin · Jetpack Compose · Room · Kotlin Coroutines · Android 8.0+ (API 26). BLE (advertise/scan/reliable GATT) · Wi-Fi Direct · multi-hop relay. E2EE: ECDH P-256 + AES-256-GCM · app lock backed by AndroidKeyStore. 221 unit tests; release builds are R8-minified and signed (≈1.5 MB).

## 构建与运行 / Build & Run

```bash
./gradlew testDebugUnitTest   # 单元测试 / Unit tests
./gradlew assembleRelease     # 正式包（R8 + 签名） / Release (R8 + signing)
```

- Android Studio 打开工程根目录运行 `app` 模块 / Open in Android Studio and run the `app` module.
- 需要真机蓝牙（API 26+）；Android 11 及以下需开启系统位置服务 / Requires real-device Bluetooth (API 26+); Android 11 and below need the location service enabled.
- 正式签名凭证在 `keystore.properties`（不入库），务必自行备份 / Signing credentials live in `keystore.properties` (not committed). Back them up.
- Wi-Fi Direct 首次建组会弹系统确认框（Android Settings 进程弹出，应用无法绕过）/ First-time Wi-Fi Direct group creation shows a system confirmation dialog (owned by the Settings process; apps cannot bypass it).

## 作者 / Authors

- **Soodok** — 后端技术性功能：协议、路由、传输与加密（14 岁初中生 / a 14-year-old middle-school student）
- **ide-chen** — 前端设计与界面交互 / frontend design & UI interaction

开源开放，欢迎任何改进与批评。联系 QQ：1980380242 / Open source, feedback and pull requests welcome.

## 许可 / License

MIT License。本软件按"现状"提供，无任何明示或暗示担保。详见 `LICENSE`。
Provided "as is", without warranty of any kind. See `LICENSE`.
