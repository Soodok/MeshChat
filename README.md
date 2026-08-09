# MeshChat

> 没有网络也能聊 —— 蓝牙直连的近距离安全通信 App
> Offline-friendly near-field secure messaging over Bluetooth, no internet needed.

## 简介 / About

不依赖基站、Wi-Fi 或任何互联网基础设施，手机之间通过蓝牙直接组网通信：互相发现、建立对话、收发消息与文件。适合灾后应急、野外作业、地下空间等断网环境。

Works without cellular, Wi-Fi, or any internet infrastructure. Devices connect directly over Bluetooth to discover each other, chat, and exchange files. Built for disaster response, field operations, underground spaces, and any offline environment.

## 实测连接距离 / Measured Range

- **空旷室内：约 40 米**（特定机型实测，因设备与环境而异）
- **丛林野外：约 20 米**

超出范围自动显示离线，回到范围自动重连，无需手动操作。

**≈40 m indoors in open space; ≈20 m in jungle/field vegetation** (measured on specific hardware; results vary).

## 特性 / Features

- **无网通信**：不依赖基站 / 路由 / Wi-Fi / 互联网，蓝牙直接组网
- **去中心化 Mesh**：没有中心服务器，每台设备既是用户也是中继，一台都不能少，节点越多网络越广
- **消息 + 文件**：实时聊天、传输文件，后台息屏也能收到消息
- **端到端加密**：内容全程加密，防窃听、防篡改
- **身份防伪**：对话页显示对方密钥指纹；若对方身份异常变化（重装 / 重启 / 被劫持）会红色告警，提示线下核对
- **多跳中继**：人链传信，A 的消息可经 B 转发到 C
- **私人频道**：自定义频道名，私人频道内互连不会被公共搜索发现
- **真·隔离**：删除对话 / 拉黑 / 换频道 / 关搜索，立即断开对方，对方瞬间看不见你
- **群聊**：广播域群会话，消息加密
- **应用锁**：密码 + 指纹解锁，保护隐私
- **后台常驻**：切后台、息屏消息照收，关闭蓝牙再打开自动恢复连接
- **深色高信息密度 UI**：终端风格，暗色高对比，适合长时间盯屏

## 免责声明 / Disclaimer

- 本项目仅用于学习与合法的应急通信研究，**禁止用于任何违法犯罪用途**；作者对任何滥用或由此产生的后果不承担责任。使用者须自行确保使用符合所在国家/地区的法律法规。
- 文中的连接距离为特定机型与环境实测，蓝牙有效范围受设备、遮挡、天气等影响，**实际距离请以现场实测为准**。
- 端到端加密能保护传输内容，但**无法防御第一次连接就被中间人劫持**（首次握手无可比对的历史指纹）；高危场景请线下核对密钥指纹。本项目不替代官方应急通信体系。

This project is for learning and legitimate emergency-communication research only. **Do not use it for illegal purposes**; the authors assume no liability for misuse. Measured ranges vary by device and environment. E2EE protects content but cannot defend against a hijack on the very first handshake — verify fingerprints offline for high-stakes use. This is not a replacement for official emergency-communication systems.

## 构建与运行 / Build & Run

```bash
./gradlew testDebugUnitTest   # 单元测试
./gradlew assembleRelease     # 正式包（R8 混淆 + 签名）
```

- 用 Android Studio 打开工程根目录，运行 `app` 模块
- 需要真机蓝牙（Android 8.0+）；Android 11 及以下需开启系统位置服务

## 许可 / License

MIT License。本软件按"现状"提供，无任何明示或暗示担保。
MIT License. This software is provided "as is", without warranty of any kind.
