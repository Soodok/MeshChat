# MeshChat 文件传输功能规格（v0.12.0）

> 日期：2026-08-03
> 版本：v0.12.0
> 前置：v0.11.0（双人真机聊天正常）；协议/存储层 FileBody、fileMeta 已就位

## 1. 背景与目标

真机联调后文字聊天已正常，用户反馈"还不能传文件"。在无公网/弱网（BLE）环境下支持点对点可靠文件传输。

**用户确认的需求约束**：
- 文件类型：图片/文档为主，规模 **<10MB**
- 可靠性：**批量 bitmap 确认**（非逐块 ACK、非尽力而为）
- 接收端处理：**直接存 Downloads**（用户可在文件管理器看到）
- 传输前提：会话已建立（握手完成）且双方实时在线

## 2. 现状（复用已有）

| 层 | 已有 | 需新增 |
|---|---|---|
| 协议 | `FileBody`（fileName/mime/size/totalChunks/chunkIndex/chunkData base64）、`MeshEnvelope.kind` 自由字符串 | `FileBody.fileId` 字段；`FileAckBody` |
| 路由 | `route()` 按 dstId 投递/转发，FILE 帧可走 else 分支多跳 | 无 |
| 存储 | `StoredMessage.fileMeta`、Room `MessageEntity.fileMeta`（无需迁移） | 无 |
| UI | ConversationScreen 附件按钮（空操作） | 文件气泡、文件选择器、进度 |
| 服务 | `sendText`/握手状态机 | `sendFile`、FILE/FILE_ACK 分发、传输引擎 |

## 3. 协议扩展

### 3.1 FileBody 增加 fileId

```kotlin
@Serializable
@SerialName("FILE")
data class FileBody(
    val fileId: String,        // 新增：关联同一文件所有分块；首块生成，全部分块复用
    val fileName: String,
    val mime: String,
    val size: Long,
    val totalChunks: Int,
    val chunkIndex: Int,
    val chunkData: String,     // base64，每块原始 200B → ~268B
) : EnvelopeBody
```

- `fileId` 用 `envelope.id` 之外独立生成（如首块创建时生成 UUID），避免与每块信封 id 混淆
- 每块帧 `envelope.id` 各自唯一（供去重/回执），`fileId` 标识文件
- 兼容说明：旧端解码含未知字段的 FileBody 会失败——同版本联调，不做向后兼容

### 3.2 新增 FileAckBody

```kotlin
@Serializable
@SerialName("FILE_ACK")
data class FileAckBody(
    val fileId: String,
    val totalChunks: Int,
    val missing: List<Int>,   // 缺失块索引；空 = 全部收齐
) : EnvelopeBody
```

- FILE_ACK 是**一跳帧**（类似 INVITE_ACK）：校验 dstId 为本机后直接处理，不进入 route() 转发

### 3.3 帧格式

- FILE 帧：`kind="FILE"`，body=FileBody，convId=发送方 `conv-<dstId>`（落库仍按 `conv-<srcId>` 对称规则）
- FILE_ACK 帧：`kind="FILE_ACK"`，body=FileAckBody，dstId=发送方短 ID

## 4. 传输引擎（新 `mesh/transfer/FileTransferManager.kt`）

### 4.1 参数

| 参数 | 值 | 依据 |
|---|---|---|
| CHUNK_BYTES | 200B 原始数据 | 实测 213B 写特征即失败；base64 后 ~268B，整帧 <300B，走 notify 双通道为主 |
| WINDOW | 32 块 | 一次广播窗口，兼顾吞吐与 ACK 频率 |
| WINDOW_TIMEOUT_MS | 15s | 窗口 ACK 未到 → 整窗重发 |
| MAX_WINDOW_RETRIES | 5 | 连续超时上限 → FAILED |
| RECV_STALL_TIMEOUT_MS | 60s | 接收端无新块超时 → 丢弃临时文件 |
| 并发 | 1 | 同时只传一个文件，队列串行 |

### 4.2 发送状态机

```
IDLE → sendFile() → SENDING → DONE / FAILED
```

- `sendFile(convId, dstId, uri, fileName, mime, size)`：
  1. 计算 totalChunks = ceil(size / 200)
  2. 生成 fileId（UUID）；落库占位消息（kind="FILE"，fileMeta JSON，status=SENDING）
  3. 流式分块：读取窗口 32 块 → 逐块 broadcast → 启动窗口计时器
  4. 收到 FILE_ACK：
     - missing 非空 → 按索引重读文件流、重发缺失块 → 重启计时器
     - missing 空 → 更新落库 DELIVERED → DONE
  5. 窗口超时 → 整窗重发（计数 +1），>5 次 → FAILED（落库状态 FAILED）
- 文件读取用 `InputStream` 流式 + `RandomAccessFile`/重开流定位缺失块，**不整文件驻留内存**
- 进度：`StateFlow<FileProgress>`（transferred/total/状态），按已确认块计算

### 4.3 接收状态机

```
收到 FILE 帧 → assembler[fileId] 收块 → 窗口满/收齐 → 回 FILE_ACK → 收齐校验 → 存 Downloads
```

- 每块按 `chunkIndex * 200` 写入临时文件（app 私有 filesDir/transfers/），重复块幂等覆盖
- 每收满 32 块（或收齐）回一次 FILE_ACK（missing=本文件缺失索引）
- 收齐：校验已收字节数 == size → 写 `MediaStore.Downloads`（API 29+ 免权限）→ 落库 fileMeta（含 Downloads URI）→ 回最终 FILE_ACK（missing 空）→ 清理临时文件
- 60s 无新块 → 丢弃临时文件、清理 assembler、落库 FAILED
- 接收进度同样汇入 `FileProgress` StateFlow

### 4.4 与 MeshService 集成

- `handleEnvelope` 新增分支：`"FILE"` → **一跳帧**（与握手帧一致）：校验 dstId 为本机后交给 engine 收块，非本机直接忽略（不进入 route() 转发——否则 FILE_ACK 一跳语义下多跳 ACK 无法回传，发送端必然超时失败；多跳文件传输列入范围外）
- `"FILE_ACK"` → 校验 dstId 为本机 → 交给 engine 处理
- 文件帧**不进 outbox**（避免数万条广播队列），传输前提为双方实时在线
- 本机发出的 FILE 帧：`sendFile` 直接 broadcast（与 sendText 相同路径）

## 5. 存储

- `StoredMessage`：kind="FILE"，text 存文件名（气泡展示用），fileMeta 存 JSON：
  ```json
  {"fileName":"a.pdf","mime":"application/pdf","size":123456,"totalChunks":618,"downloadsUri":null}
  ```
  发送方 downloadsUri 为 null；接收方收齐后回填 Downloads URI
- 进度（transferred/percent/status）**只存内存**（FileProgress），不落库；重启后气泡仅显示"已送达/未送达"

## 6. UI

### 6.1 数据模型

```kotlin
data class ChatMessage(
    val id: String,
    val text: String,
    val sentByMe: Boolean,
    val time: String,
    val delivery: String? = null,
    val file: FileUiMeta? = null,   // 新增
)

data class FileUiMeta(
    val fileName: String,
    val size: Long,
    val progress: Int,      // 0-100
    val done: Boolean,
)
```

### 6.2 气泡渲染

- 有 file 的 `ChatMessage` 渲染文件卡片：文件图标 + 文件名 + 大小（KB/MB 格式化）+ 进度条（发送/接收中）+ 完成状态（"已存 Downloads"/"已发送"）
- 完成的气泡可点击 → `ACTION_VIEW` 打开 Downloads URI

### 6.3 发送入口

- ConversationScreen 附件按钮 → 系统文件选择器（`ActivityResultContracts.OpenDocument`）→ 选中即发
- 发送中附件按钮禁用（串行约束），完成后恢复

## 7. 权限

- API 29+：`MediaStore.Downloads` 写入**免权限**
- API 26-28：需要 `WRITE_EXTERNAL_STORAGE` 运行时权限 → MainActivity 权限列表按版本分支追加（沿用现有 BLUETOOTH 权限拆分模式）

## 8. 测试策略

单测（`testDebugUnitTest`，沿用 InMemoryTransport 包装模式）：
1. bitmap/missing 计算：窗口内随机缺块 → missing 列表正确；空窗口 → missing 空
2. 发送状态机：首窗发出 32 块 → 回 ACK(missing=[3]) → 仅重发第 3 块 → 回 ACK(空) → DONE
3. 窗口超时：不回 ACK → 整窗重发 → 连续 5 次 → FAILED
4. 接收重组：模拟 100 块乱序/重复到达 → 临时文件字节与源一致 → 收齐校验通过
5. 串行约束：传输中再调 sendFile → 拒绝/排队
6. 现有 22 测试不回归

真机验收：A↔B 传输图片/PDF（<10MB），B 的 Downloads 可见完整文件，进度条与完成状态正确；断连场景窗口重传生效。

## 9. 边界情况

- **对端中途离线**：窗口重发 5 次失败 → 发送方 FAILED；接收方 60s 无进展清理
- **重复块**：按 chunkIndex 幂等覆盖
- **文件读失败/大小不符**：接收端校验 size，不符 → FAILED + 清理
- **传输中关会话/重启**：FileProgress 内存态丢失，气泡显示最后落库状态；文件传输中止
- **0 字节文件**：UI 层拒绝发送（选择后提示"空文件不支持发送"），totalChunks=0 不进入传输

## 10. 范围外（不做）

- 大文件（≥50MB）与断点续传（重启后续传）
- 多跳文件传输（FILE_ACK 一跳语义，多跳需 ACK 反向路由，后续版本）
- 并发多文件传输
- 文件加密（后续真实加密整体接入时覆盖）
- 发送队列持久化（outbox 化）
