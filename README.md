# MeshChat

> 面向**无公网 / 弱网极端环境**的近场安全通信 Android 应用
> A near-field secure-communication Android app for **offline / weak-network** environments.

## 免责声明 / Disclaimer

> **开源项目可能被用于恶意用途。** 本项目仅用于学习与合法的应急通信研究。开源代码可被任何个人或组织以任何方式复制、修改与使用，包括但不限于违法犯罪用途；作者对任何滥用、误用或由此产生的后果不承担任何责任。使用者须自行确保其使用方式符合所在国家/地区的法律法规。
>
> **Open-source software can be abused.** This project is provided solely for learning and legitimate emergency-communication research. The code may be copied, modified, and used by anyone for any purpose, including illegal ones; the authors assume no liability for any misuse or for the consequences thereof. You are solely responsible for ensuring your usage complies with all applicable laws and regulations.

> **距离与安全免责。** 文中实测距离（空旷室内约 40 米、丛林野外约 20 米）来自特定机型与环境，实际范围因设备芯片、遮挡、天气而异，请以现场实测为准。端到端加密保护传输内容，但**无法防御第一次连接就被劫持**，高危场景请线下核对指纹。本项目不替代官方应急通信体系，不构成任何安全性承诺。
>
> **Range & security disclaimer.** Measured ranges (≈40 m indoors, ≈20 m in jungle) vary by device and environment — verify on site. E2EE protects message content but **cannot stop hijacking on the very first connection**; verify fingerprints offline for high-stakes scenarios. Not a replacement for official emergency-communication systems; no security guarantee implied.

## 项目简介 / About

不依赖基站、Wi-Fi 或任何互联网，仅靠蓝牙就能在设备之间组成一张去中心化网络：互相发现、建立对话、收发消息和文件。没信号的地方也能聊——灾后应急、野外作业、地下空间、隔离区，都适用。

No base station, Wi-Fi, or internet required. Just Bluetooth. Devices form a decentralized mesh to find each other, start conversations, and exchange messages and files — even where there is no signal. Built for disaster response, field work, underground spaces, and quarantined areas.

## 实测连接距离 / Measured Range

**空旷室内：约 40 米；丛林野外：约 20 米**（特定机型实测，因设备与环境而异）。
**≈40 m indoors in open space; ≈20 m in jungle/field vegetation** (measured on specific hardware; results vary).

走远了会显示「断线重连中」「离线」，走回来自动重新连上，全程无需手动操作。

Walk out of range → the app shows reconnecting/offline. Walk back → it reconnects by itself.

## 特性 / Features

**基本通信** — *Core messaging*

- **零基础设施聊天**：手机对手机直连，断网断电都能用
- **文件传输**：直接互发文件，进度实时可见
- **群聊**：一个区域内的设备可进同一个群（加密）
- **多跳中继**：隔着一台设备也能送达消息，列表会显示「经 XX 可达」
- **消息必达**：蓝牙丢包、切后台、息屏都不怕，反复确认直到对方收到
- **后台常驻**：锁屏也收消息，点通知直达对话

**安全与隐私** — *Security & privacy*

- **端到端加密**：消息在离开手机前就加密，中间任何设备都看不懂
- **中间人防护**：每个对话显示对方「指纹」，对方身份异常立刻红色告警
- **私人频道**：自定义频道名就能组一个「只有我们」的空间，不参与公共搜索
- **彻底拉黑**：拉黑/删除对话/换频道，立即断开所有连接，对方再也看不到你
- **应用锁**：设置密码 + 指纹解锁，别人拿到手机也打不开

**体验** — *Experience*

- **Mesh 拓扑图**：谁连着谁一图看清，节点可拖拽，状态颜色区分
- **离线评估**：锁屏、调试、无障碍等本地安全风险一目了然
- **调试中心**：收发包速度、连接质量、信号强度实时看

## 技术栈 / Tech Stack

Kotlin · Jetpack Compose · Room · kotlinx-serialization · Android 8.0+（API 26）
207 项单元测试，正式包 R8 混淆 + 签名（约 1.9 MB）。

## 构建与运行 / Build & Run

```bash
./gradlew testDebugUnitTest   # 单元测试 / Unit tests
./gradlew assembleRelease     # 正式包（R8 + 签名） / Release (R8 + signing)
```

- Android Studio 打开工程根目录即可运行 `app` 模块 / Open the project root in Android Studio and run the `app` module.
- 需要真机蓝牙（API 26+）；Android 11 及以下需开启系统位置服务 / Requires real-device Bluetooth (API 26+); Android 11 and below need the location service enabled.
- 正式签名凭证在 `keystore.properties`（不入库），**务必自行备份，丢失无法更新已发布版本** / Signing credentials live in `keystore.properties` (not committed). **Back them up — losing them makes released builds unupdatable.**

## 许可 / License

MIT License。本软件按"现状"提供，无任何明示或暗示担保。详见 `LICENSE`。
Provided "as is", without warranty of any kind. See `LICENSE`.
