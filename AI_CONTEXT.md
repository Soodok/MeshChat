# AI_CONTEXT.md — MeshChat 工程交接

> 本文件为 AI 协作交接文档。会话开始前必须阅读；会话结束前必须更新「交接块」。

## 项目定位

MeshChat 是面向无公网/弱网场景的近场安全通信应用。本仓库（`E:\MeshChat Project`）仅包含 **Android 前端**（Jetpack Compose）：界面、导航、交互状态与演示数据。BLE / Wi-Fi Direct / Mesh 路由 / 真实转发 / 加密 / 服务端 / 持久化均**未实现**，页面状态全部为前端演示，便于后续模块接入。

- 工程根目录：`E:\MeshChat Project`（README 已同步修正，勿再沿用旧路径 `D:\MeshChat`）
- 包名：`com.meshchat.app`；minSdk 26 / targetSdk 36 / compileSdk 36（版本 36.1）
- 构建：AGP 9.0.0 + Kotlin Compose 插件 2.2.10 + Gradle 9.1.0（wrapper 内置）
- 视觉基准：`design/meshchat-visual-baseline.png`（墨蓝底 + 青色强调 + 绿色连接态）

## 目录结构

```
app/src/main/java/com/meshchat/app/
├── MainActivity.kt                 # 入口（Edge-to-Edge + 主题）
├── data/UiModels.kt                # 数据模型 + 演示数据（聊天/消息/Mesh 节点/导航枚举）
├── ui/MeshChatApp.kt               # 根 Composable，注入 ViewModel
├── ui/MeshChatViewModel.kt         # StateFlow 消息状态 + 本地发送
├── ui/components/MeshComponents.kt # 头像/信号条/安全提示组件
├── ui/screens/                     # Chats / Conversation / Mesh / Profile / ProfileDetail
└── ui/theme/                       # MeshChatTheme.kt（色板）、Type.kt（字号）
```

## 构建与环境（已修复并验证）

- `local.properties`：`sdk.dir=C:\Users\Qt\AppData\Local\Android\Sdk`（原指向已失效的 `C:\Users\24165\...`）
- compileSdk/targetSdk = 36，本机 SDK Platform 36.1 已安装（AGP 首次构建自动补装 android-36）
- 构建验证：`gradlew assembleDebug` → **BUILD SUCCESSFUL**（2026-08-03 10:14 产出 app-debug.apk 18 MB）
- git 基线：commit `d138496`（83 文件），`.gitignore` 已排除 build/.gradle/local.properties/*.apk

## 交接块

### 当前进度
- 完成全量代码审阅：5 个页面 + 组件 + 主题 + ViewModel 分层清晰，无编译级缺陷。
- 修复 P0 构建配置：`local.properties` SDK 路径、compileSdk/targetSdk 35→36、初始化 git 仓库并提交基线 `d138496`。
- 更新 README：工程路径 `E:\MeshChat Project`、SDK 版本 API 36。

### 已验证内容
- `gradlew assembleDebug` 全量构建通过（37 tasks），APK 正常产出。
- git 提交成功：83 files / 12949 insertions，工作区干净。

### 当前阻塞
- 无阻塞。构建链路已完全打通。

### 下一步首要任务
- 如继续前端演进：以 `design/meshchat-visual-baseline.png` 为基准开发新页面；当前所有聊天项均打开「林宇航」演示会话，可扩展为按 chat.id 映射不同会话数据。
- 如开始后端/协议：参照 README「边界」一节逐项接入（BLE → 路由 → 加密 → 服务端），接入后需更新 README 边界描述。

### 本次涉及的关键文件
- `local.properties`、`app/build.gradle.kts`、`.gitignore`、`README.md`、`AI_CONTEXT.md`
