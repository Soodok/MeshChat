# MeshChat

> 面向**无公网 / 弱网极端环境**的近场安全通信 Android 应用
> A near-field secure-communication Android app for **offline / weak-network** environments.

## 免责声明 / Disclaimer

> **开源项目可能被用于恶意用途。** 本项目仅用于学习与合法的应急通信研究。开源代码可被任何个人或组织以任何方式复制、修改与使用，包括但不限于违法犯罪用途；作者对任何滥用、误用或由此产生的后果不承担任何责任。使用者须自行确保其使用方式符合所在国家/地区的法律法规。
>
> **Open-source software can be abused.** This project is provided solely for learning and legitimate emergency-communication research. The code may be copied, modified, and used by anyone for any purpose, including illegal ones; the authors assume no liability for any misuse or for the consequences thereof. You are solely responsible for ensuring your usage complies with all applicable laws and regulations.

## 项目简介 / About

不依赖基站、Wi-Fi 路由或任何互联网基础设施，通过蓝牙在设备间自组织形成去中心化网状网络（Mesh）：设备互相发现、建立对话、收发消息与文件。适用于灾后应急、野外作业、地下空间、隔离区等场景。

No cellular base station, Wi-Fi router, or internet infrastructure is required. Devices self-organize into a decentralized mesh over Bluetooth: discovery, conversation setup, and message/file exchange happen directly between devices. Designed for disaster response, field operations, underground spaces, and quarantined areas.

## 特性 / Features

**通信链路（BLE 真机验证）** — *Communication stack (verified on real BLE hardware)*

- 蓝牙发现：Service Data 携带短 ID 识别节点，扫描即持久化恢复
- 对话握手：INVITE → INVITE_ACK 会话锁定，ACK 持续重发收敛（30s 窗口），杜绝握手丢帧
- 消息收发：双向实时到达，TTL=8 转发决策 + LRU 去重
- 三层送达确认：RECEIPT 回执 / PONG 携带 ackIds / 广播确认键（扫描响应），广播丢帧场景必收敛
- 文件传输：窗口滑动 + bitmap 缺失确认，接收端落盘 Downloads，进度实时
- 心跳校准：1s PING / 200ms 状态刷新，在线-断线重连-离线三色状态机 + 信号时间（毫秒级）显示
- 多跳中继（v1.1.0）：纯中继 TEXT 转发（去会话白名单）+ RECEIPT 泛洪回传防环 + outbox 1s 重发兜底，2 跳路由学习/过期，列表显示"经 X 可达 · 2跳"
- 后台常驻：前台服务 + 通知直达，蓝牙关→开自动重建传输层
- 安全中心（v1.1.3）：本地安全评估（锁屏/调试/无障碍等离线信号）+ AES-GCM 加密事件记录（AndroidKeyStore，禁备份）+ 权限能力状态页（拒绝冷却不骚扰）+ 证据字段白名单防消息入库；对话归档/删除/未读标记、列表显示最后消息与时间、Mesh 页「附近/历史」分组；Manifest 加固（禁云备份/禁明文流量）
- 调试中心（v1.1.5）：设置页入口实时仪表盘——收发包速率/包数/字节（按帧类型 PING/PONG/INVITE/TEXT/FILE/ACK/回执）、BLE 传输层（广播/扫描/GATT/MTU/写与 notify 成败）、信号与路由（节点 RSSI/路由表）、送达链路（确认率/重发分布/中继转发）、文件传输；7 项调节（刷新间隔/速率窗口/单位/板块/排序/暂停/清零），内存态重启清零，埋点零侵入收发时序
- 调试中心主动控制（v1.1.9）：调节心跳广播频率（0.5s/1s/2s/5s，失联阈值联动×2）、消息重发退避（基础3s/10s/30s·封顶联动）、暂停/恢复广播+扫描（保留已建 GATT 连接）、手动发 PING 链路探测、一键恢复默认（全部内存态重启回默认，未调节时行为零变化）
- 调试中心高频心跳 + 失败包（v1.1.10）：心跳档位 0.05s/0.1s/0.2s/0.4s 四档（独立心跳协程与 200ms tick 解耦，已连接 GATT 通道真实生效；BLE 广播受系统约 100ms 最小间隔限制；失联阈值固定 2s 不联动）；新增「失败包」板块——接收解码失败（收到但无法解析）、送达不可确认、BLE 写/notify 失败实时统计
- 调试中心布局修复（v1.1.12）：主动控制面板重发退避 FilterChip 长 label 溢出导致第三个 chip 异常拉伸留白——改短 label（3s/10s/30s）并将封顶值移到汇总行；面板改为高密度「当前生效配置」汇总（心跳/失联/重发基础/封顶）+ 手动 PING 常显统计（次数/时间）
- 广播功率调节 + TX power 读取（v1.1.15）：调试中心主动控制新增广播发射功率 4 档（1/-7/-15/-21 dBm，默认最高 +1dBm，重启广播生效）；广播包携带 TX power 字段，接收端扫描读到对端发射功率
**Mesh 拓扑可视化** — *Mesh topology visualization*

- 力导向网状布局：库仑斥力 + 弹簧力 + 中心引力，去中心化自然分布
- 节点可拖拽，松手自动回归物理模拟
- 状态色制：直连绿 / 可达蓝 / 寻找中黄虚线，失联节点不显示
- 相对尺寸自适应：节点、连线、字号随画布缩放，不同分辨率表现一致

**工程** — *Engineering*

- 深色高信息密度视觉：墨蓝底色、Mesh 青色强调、终端风格等宽字体
- 130 项单元测试通过（协议/路由/存储/服务编排/传输/多跳中继/调试统计/安全中心）
- 正式签名 + R8 混淆 release 包（约 2.8 MB）

## 架构 / Architecture

```
app/src/main/java/com/meshchat/app/
├── data/            # 前端数据源（MeshRepository 契约 + 实现）
├── mesh/
│   ├── protocol/    # 帧编解码、消息信封 + TEXT/FILE 载荷、JSON 序列化
│   ├── routing/     # LRU 去重表、转发决策
│   ├── identity/    # 短 ID 身份（持久化）
│   ├── storage/     # Room 持久化（消息/节点/会话）
│   ├── transfer/    # 文件传输引擎（窗口/ACK 状态机）
│   ├── service/     # MeshService：发送-转发-投递-回执编排 + 前台服务
│   └── transport/   # BLE 传输（GATT 双通道）、传输抽象
└── ui/              # Compose 界面（聊天/拓扑/设置/身份）
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

- 端到端加密当前为演示级占位（Cipher 接口预留），未接入真实密钥协商 / E2E encryption is a demo-grade placeholder (Cipher interface reserved); no real key agreement yet.
- 多跳中继的路由学习与 UI 显示当前支持 2 跳（一跳直连 + 二跳经单一中继），2 跳以上不显示路由 / Multi-hop routing supports 2 hops (direct + one relay); beyond 2 hops routes are not displayed.
- Wi-Fi Direct 高速载体、群聊为规划项 / Wi-Fi Direct transport and group chat are planned.
- 蓝牙传输有效范围受设备与遮挡影响，空旷室内通常为数十米内（实测以机型为准） / Effective BLE range depends on device and obstacles; typically tens of meters indoors in open space (verify per device).

## 设计基准 / Design Baseline

视觉基准图见 `design/meshchat-visual-baseline.png`：墨蓝底色、青色强调、绿色连接、高信息密度终端风格。
Visual baseline: `design/meshchat-visual-baseline.png` — ink-blue background, cyan accents, green links, high-density terminal style.

## 许可 / License

MIT License

本软件按"现状"提供，无任何明示或暗示担保。详见 `LICENSE`。
This software is provided "as is", without warranty of any kind, express or implied. See `LICENSE`.
