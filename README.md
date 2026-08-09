# MeshChat

> 面向**无公网 / 弱网极端环境**的近场安全通信 Android 应用
> A near-field secure-communication Android app for **offline / weak-network** environments.

## 免责声明 / Disclaimer

> 本项目仅用于学习与合法的应急通信研究。开源代码可被任何个人或组织以任何方式复制、修改与使用，包括但不限于违法犯罪用途；作者对任何滥用、误用或由此产生的后果不承担任何责任。实测距离（空旷室内约 40 米、丛林野外约 20 米，测试机型蓝牙 5.1）因设备与环境而异，请以现场实测为准。端到端加密保护传输内容，但无法防御第一次连接就被劫持，高危场景请线下核对指纹。本项目不构成任何安全性承诺。
>
> For learning and legitimate emergency-communication research only. Code may be reused for any purpose, including illegal ones; no liability for misuse. Measured ranges (≈40 m indoors, ≈20 m in jungle; Bluetooth 5.1 test devices) vary by hardware and environment — verify on site. E2EE protects content but cannot stop hijacking on the very first connection; verify fingerprints offline for high-stakes scenarios. No security guarantee implied.

## 项目简介 / About

不依赖基站、Wi-Fi 或任何互联网，仅靠蓝牙就能在设备之间组成一张去中心化网络：互相发现、建立对话、收发消息和文件。没信号的地方也能聊。

No base station, Wi-Fi, or internet required. Just Bluetooth. Devices form a decentralized mesh to find each other, chat, and exchange files — even where there is no signal.

## 实测连接距离 / Measured Range

**蓝牙 5.1 实测：空旷室内约 40 米，丛林野外约 20 米。**

发射功率 4 档可调（默认最高），近距离降功率省电、远距离拉满换覆盖；走远了自动显示「断线重连中 / 离线」，走回来自动重连，无需手动操作。

*Measured on Bluetooth 5.1 devices: ≈40 m indoors in open space, ≈20 m in jungle vegetation. TX power is adjustable across 4 levels to trade battery for range; out-of-range shows reconnecting/offline and reconnects automatically.*

## 差异化能力 / What Makes It Different

- **不连接也能通讯**：基于蓝牙广播收发消息，无需配对、无需稳定连接——连接只让通讯更快，不是前提
- **去中心化自愈多跳**：每台设备都是路由器，隔一台设备也能送达，有人离开自动绕路
- **端到端加密 + 中间人告警**：消息出手机前加密；每个对话显示对方公钥指纹，身份异常立刻红色告警
- **私人频道**：自定义频道名组一个「只有我们」的空间，不参与公共搜索
- **彻底拉黑**：拉黑 / 删除对话 / 换频道，立即断开全部连接，对方再也看不到你
- **消息必达**：广播丢包、切后台、息屏都不怕，自动重发确认直到对方收到

*Broadcast-based messaging with no pairing or stable connection required. Every device is a router — multi-hop delivery that reroutes around gaps. E2EE with per-conversation key fingerprints that alert on identity change. Private channels invisible to public search. Blocking cuts every connection instantly. Messages are auto-retried until delivered.*

## 基础功能 / Also Included

聊天、文件传输、群聊、多跳中继、后台常驻、应用锁、拓扑图、调试中心、离线安全评估——该有的都有，不啰嗦。

Chat, file transfer, group chat, background service, app lock, mesh topology, debug center, offline security audit — the essentials, all included.

## 技术栈 / Tech Stack

Kotlin · Jetpack Compose · Room · Android 8.0+（API 26）
207 项单元测试，正式包 R8 混淆 + 签名（约 1.9 MB）。

## 构建与运行 / Build & Run

```bash
./gradlew testDebugUnitTest   # 单元测试 / Unit tests
./gradlew assembleRelease     # 正式包（R8 + 签名） / Release (R8 + signing)
```

- Android Studio 打开工程根目录运行 `app` 模块 / Open in Android Studio and run the `app` module.
- 需要真机蓝牙（API 26+）；Android 11 及以下需开启系统位置服务 / Requires real-device Bluetooth (API 26+); Android 11 and below need the location service enabled.
- 正式签名凭证在 `keystore.properties`（不入库），务必自行备份 / Signing credentials live in `keystore.properties` (not committed). Back them up.

## 许可 / License

MIT License。本软件按"现状"提供，无任何明示或暗示担保。详见 `LICENSE`。
Provided "as is", without warranty of any kind. See `LICENSE`.
