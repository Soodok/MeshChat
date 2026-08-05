package com.meshchat.app.mesh.transfer

import android.util.Log
import com.meshchat.app.mesh.debug.DebugLogBuffer
import com.meshchat.app.mesh.debug.FrameKind
import com.meshchat.app.mesh.protocol.File3
import com.meshchat.app.mesh.protocol.FileAckBody
import com.meshchat.app.mesh.protocol.FileBody
import com.meshchat.app.mesh.protocol.FileBodyV2
import com.meshchat.app.mesh.protocol.FrameType
import com.meshchat.app.mesh.protocol.MeshEnvelope
import com.meshchat.app.mesh.protocol.MeshFrame
import com.meshchat.app.mesh.protocol.MeshJson
import com.meshchat.app.mesh.transport.MeshTransport
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Base64
import java.util.UUID
import java.util.zip.DeflaterInputStream
import java.util.zip.InflaterInputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * BLE 文件传输引擎：串行单文件、窗口批量 bitmap 确认。
 * 发送：窗口 32 块 → 等 FILE_ACK（缺失索引）→ 重发缺失 → 超时整窗重发（上限后 FAILED）。
 * 接收：临时文件按 chunkIndex 写入 → 每 32 块/收齐回 ACK → 收齐校验后经 FileSaver 落盘。
 */
class FileTransferManager(
    private val transport: MeshTransport,
    private val shortId: String,
    private val saver: FileSaver,
    private val scope: CoroutineScope,
    private val windowTimeoutMs: Long = WINDOW_TIMEOUT_MS,
    private val maxWindowRetries: Int = MAX_WINDOW_RETRIES,
    private val tmpDirProvider: () -> File = { File(System.getProperty("java.io.tmpdir"), "meshchat_transfers") },
    private val onProgress: (FileProgress) -> Unit = {},
    private val onSaved: (convId: String, fileId: String, fileName: String, mime: String, size: Long, uri: String?) -> Unit = { _, _, _, _, _, _ -> },
    /** 文件帧发送通道：RFCOMM 连接时走 sendTo，否则回退 broadcast（由 MeshService 注入）。 */
    private val sendFrame: (dstId: String, frame: MeshFrame) -> Unit = { _, frame -> transport.broadcast(frame) },
    /** 调试统计内核（透传 MeshService 注入）。 */
    private val debugStats: com.meshchat.app.mesh.debug.DebugStats = com.meshchat.app.mesh.debug.DebugStats(),
) {
    companion object {
        private const val TAG = "MeshFile"
        // 老 FILE/FILE2 接收兼容：块定位步长 120B（v1.1.27）。新发送端（v1.1.28）统一 FILE3 二进制帧，
        // 块大小 File3.CHUNK_BYTES=480（无 base64 膨胀，25B 头 + 480B 数据 = 505B ≤ 509B，数据占比 95%）。
        const val CHUNK_BYTES = 120
        // 窗口 8 块：32 块（16KB/窗）超 BLE 实际吞吐（1-5KB/s），15s 内收不齐 → 整窗重发恶性循环
        const val WINDOW = 8
        const val WINDOW_TIMEOUT_MS = 1_000L    // ACK 全丢时的兜底：每块 ACK 模式下窗口往返 ~500ms，1s 足够；越短丢 ACK 恢复越快
        /**
         * 单窗口重试上限（v1.1.38：**生产无上限**——重连/重发次数不设限，零容错，仅链路断开（isConnectedTo=false）
         * 才停止传输；v1.1.37 的 12 次在长传中仍可能偶发耗尽）。测试/调试可显式传小值验证超时路径。
         */
        const val MAX_WINDOW_RETRIES = Int.MAX_VALUE
        const val RECV_STALL_TIMEOUT_MS = 60_000L
        /**
         * 窗口内逐帧间隔（v1.1.28）：2ms——文件帧走 GATT WRITE_NO_RESPONSE 无确认写（v1.1.27 起），
         * 链路已建立、无写往返，丢帧由窗口重传兜底（初发与补发一致）。
         * 0ms 完全无节流会让 notify/写队列瞬间过载被蓝牙栈丢弃（v1.1.26 的 30ms 节流下从未突发过）；
         * 2ms = 500 帧/s 上限，远超 GATT 实际能力（100-300/s），吞吐无损但避免瞬时突发。
         */
        const val BROADCAST_INTERVAL_MS = 2L
        /**
         * 每帧携带块数（v1.1.27 FILE2）：固定 1。v1.1.28 起发送端改用 FILE3 二进制帧
         * （File3.CHUNK_BYTES=480/帧），该常量仅老 FILE2 帧 DIAG 预算测试引用。
         */
        const val CHUNKS_PER_FRAME = 1
        /**
         * ACK 缺失列表截断上限：发送端只 filter 当前窗口内缺失（窗口 8 块），
         * 更早窗口已收齐才推进（need 空），故窗口内缺失必位于全文件缺失列表前部，
         * take(WINDOW) 恰好覆盖。= WINDOW 强耦合，改 WINDOW 必须同步。
         * 40 项时代 ACK 帧 ~300B、60 个/s（每块回）→ ACK 洪泛挤占 BLE 带宽超过数据本身；8 项 → 帧 ~120B。
         */
        const val MAX_ACK_MISSING = WINDOW
        /**
         * 接收端 ACK 合并兜底：距上次 ACK 超过该时长仍有未确认新块 → 立即回（最后不足整窗的收尾不拖到窗口超时）。
         * v1.1.37：300→150ms——ACK 是窗口超时主因（单帧易丢），提高重发频率让发送端更快收敛；120B 小帧带宽占比可忽略。
         */
        const val ACK_FLUSH_MS = 150L

        /**
         * 发送端数据块大小（v1.1.36，MTU 感知动态）：块 + 61B v2 CHUNK 帧头必须 ≤ 协商 MTU 载荷（mtu-3）。
         * 真机 MTU 常协商不足 512（497/247 常见），v1.1.35 硬编码 456 块（帧 509B）在载荷 <509 时每帧写失败
         * → 发送方 write FAILED / 0 块。此函数按 transport.currentMtu() 动态降块，帧永远 ≤ 载荷。
         * mtu ≤ 0（未知/测试替身）按 512 计（上限块）。下限 64B 保块数可控（MTU 极端小时宁可多块也不能超帧）。
         */
        fun dynamicChunkBytes(mtu: Int): Int {
            val effective = if (mtu <= 0) 512 else mtu
            val payload = effective - 3
            val header = 61   // v2 CHUNK 固定头（36 字符完整 UUID fid）
            return (payload - header).coerceIn(64, File3.CHUNK_BYTES)
        }
    }

    private val _progress = MutableStateFlow<FileProgress?>(null)
    val progress: StateFlow<FileProgress?> = _progress.asStateFlow()

    /** 发送会话（串行，同一时间仅一个）。 */
    private class SendSession(
        val fileId: String,
        val convId: String,
        val dstId: String,
        val openSource: () -> InputStream,
        val fileName: String,
        val mime: String,
        val size: Long,
        /** v1.1.36：MTU 感知动态块大小（发送端块 = 帧数据部分；帧头 61B，帧 ≤ 协商 MTU 载荷）。 */
        val chunkBytes: Int,
    ) {
        var expectStart = 0
        var expectEnd = 0
        var lastMissingCount = Int.MAX_VALUE
        /** v1.1.28 预处理产物：压缩/原样复制后的数据文件（发送期间存在，完毕删除）。 */
        var dataFile: File? = null
        var compressed: Boolean = false
        /** 预处理后算出的总块数（压缩后字节数 / chunkBytes）。 */
        var totalChunks = 0
    }

    /** 接收会话（临时文件 + 已收块集合）。v1.1.28 元数据可后补（FILE3 START 帧乱序/晚到）。 */
    private class ReceiveSession(
        val fileId: String,
        val convId: String,
        val senderId: String,
        val tmpFile: File,
        val received: MutableSet<Int>,
        var lastActivity: Long,
        var ackCounter: Int = 0,
        /** 自上次 ACK 后是否有新块未确认（tick 兜底回 ACK 用）。 */
        var ackDirty: Boolean = false,
        var lastAckAt: Long = 0L,
        // v1.1.28：以下元数据由 FILE3 START 帧填充，老 FILE/FILE2 路径构造时直接传入
        var fileName: String = "",
        var mime: String = "application/octet-stream",
        var size: Long = 0L,
        var totalChunks: Int = 0,
        var compressed: Boolean = false,
        /**
         * 块定位步长：老 FILE/FILE2 用 CHUNK_BYTES(120)；FILE3 v1 老帧（v1.1.28~35）用 LEGACY_CHUNK_BYTES(456)。
         * FILE3 v2 帧（v1.1.36+）不依赖本字段——帧内携带 byteOffset 显式定位（块大小随发送端 MTU 动态变化）。
         */
        val chunkSize: Int = CHUNK_BYTES,
    ) {
        /** 写块；返回是否为新块（重复块幂等跳过并返回 false，用于触发立即回 ACK）。 */
        fun writeChunk(chunkIndex: Int, data: ByteArray): Boolean =
            writeChunkAt(chunkIndex, chunkIndex * chunkSize.toLong(), data)

        /** 按字节偏移写块（v1.1.36 FILE3 v2 帧用：发送端块大小动态，偏移由帧携带）；返回是否为新块。 */
        fun writeChunkAt(chunkIndex: Int, byteOffset: Long, data: ByteArray): Boolean {
            if (chunkIndex in received) return false
            java.io.RandomAccessFile(tmpFile, "rw").use { raf ->
                raf.seek(byteOffset)
                raf.write(data)
            }
            received += chunkIndex
            return true
        }

        val missing: List<Int> get() =
            if (totalChunks <= 0) emptyList() else (0 until totalChunks).filter { it !in received }
        val isComplete: Boolean get() = totalChunks > 0 && received.size >= totalChunks
        /** 元数据是否齐备（totalChunks + 文件名已知），收齐后可落盘。 */
        val metaReady: Boolean get() = totalChunks > 0 && fileName.isNotEmpty()
    }

    private var sending: SendSession? = null
    private var senderJob: kotlinx.coroutines.Job? = null
    private val receivers = mutableMapOf<String, ReceiveSession>()

    /** 当前等待 ACK 的 waiter（每轮等待窗口前重建，避免旧引用 complete 丢失）。 */
    private var ackWaiter: CompletableDeferred<FileAckBody?>? = null

    /** 广播窗口期间到达的 ACK（此时 ackWaiter 为 null）：缓存下来，下轮等待立即消费，防止丢失后超时重发。 */
    private var pendingAck: FileAckBody? = null

    init {
        // 启动清理孤儿 .part 临时文件（移植队友 v1.0.12）：进程中断后残留的 .part 无法续传，占空间
        cleanupOrphanedTemporaryFiles()
    }

    /** 停止活动传输（供 MeshService.stop 调用）；不 cancel 共享 scope，服务可重启。 */
    fun cancel() {
        senderJob?.cancel()
        senderJob = null
        sending = null
        ackWaiter?.cancel()
        ackWaiter = null
        pendingAck = null
        receivers.values.forEach { it.tmpFile.delete() }
        receivers.clear()
        _progress.value = null
    }

    /** 对已保存文件回发完成 ACK（重启后对端重传场景），不重复落盘。 */
    fun acknowledgeCompletedFile(fileId: String, convId: String, senderId: String, totalChunks: Int) {
        val ack = MeshEnvelope(
            id = UUID.randomUUID().toString(), kind = "FILE_ACK",
            srcId = shortId, dstId = senderId, convId = convId,
            ttl = 8, ts = System.currentTimeMillis(),
            body = FileAckBody(fileId = fileId, totalChunks = totalChunks, missing = emptyList()),
        )
        val frame = MeshFrame(FrameType.DATA, MeshJson.encodeEnvelope(ack).toByteArray())
        debugStats.recordSent(FrameKind.FILE_ACK, frame.payload.size)
        sendFrame(senderId, frame)
    }

    /** 孤儿 .part 临时文件：进程重启后无法续传，立即删除。 */
    private fun cleanupOrphanedTemporaryFiles() {
        runCatching {
            tmpDirProvider().apply { mkdirs() }.listFiles { file -> file.isFile && file.name.endsWith(".part") }
                ?.forEach { it.delete() }
        }
    }

    /** 发送文件；正在传输时返回 null（串行约束）。fileId 同时用作消息 id。 */
    fun sendFile(
        convId: String,
        dstId: String,
        openSource: () -> InputStream,
        fileName: String,
        mime: String,
        size: Long,
    ): String? {
        if (sending != null) return null
        val session = SendSession(
            fileId = UUID.randomUUID().toString(),
            convId = convId, dstId = dstId, openSource = openSource,
            fileName = fileName, mime = mime, size = size,
            // v1.1.36：块大小按当前协商 MTU 动态计算——MTU 协商不足 512 时自动降块，帧永不超载荷（防 write FAILED/0 块）
            chunkBytes = dynamicChunkBytes(transport.currentMtu()),
        )
        Log.d(TAG, "sendFile start fileId=${session.fileId} size=$size chunks=${(size + session.chunkBytes - 1) / session.chunkBytes} chunkBytes=${session.chunkBytes} mtu=${transport.currentMtu()} name=$fileName")
        DebugLogBuffer.log(TAG, "sendFile start size=$size name=$fileName chunkBytes=${session.chunkBytes} mtu=${transport.currentMtu()}")
        sending = session
        senderJob = scope.launch { runSender(session) }
        return session.fileId
    }

    private suspend fun runSender(s: SendSession) {
        // v1.1.28 预处理：压缩（不可压缩回退原样）→ dataFile，确定总块数（压缩后字节数 / File3.CHUNK_BYTES）
        if (!prepareData(s)) {
            Log.e(TAG, "prepareData failed for ${s.fileId}")
            DebugLogBuffer.log(TAG, "prepareData FAILED size=${s.size}")
            finish(s, TransferStatus.FAILED)
            return
        }
        val totalChunks = s.totalChunks
        if (totalChunks == 0) { finish(s, TransferStatus.FAILED); return }
        try {
            var windowStart = 0
            while (windowStart < totalChunks) {
                val inWindow = minOf(WINDOW, totalChunks - windowStart)
                val cache = readWindow(s, windowStart, inWindow)
                if (cache == null) {
                    Log.e(TAG, "readWindow failed for ${s.fileId} at $windowStart")
                    finish(s, TransferStatus.FAILED); return
                }
                s.expectStart = windowStart
                s.expectEnd = windowStart + inWindow - 1
                Log.d(TAG, "send window ${s.fileId} [$windowStart..${s.expectEnd}]/${totalChunks} frames=${inWindow} chunkBytes=${s.chunkBytes}")
                DebugLogBuffer.log(TAG, "send window [$windowStart..${s.expectEnd}]/$totalChunks frames=$inWindow")
                broadcastWindow(s, cache)
                var retries = 0
                while (true) {
                    // v1.1.38：重试无上限（零容错），仅链路断开才停止——对端 GATT 连接已断则不再硬撑重发
                    if (!transport.isConnectedTo(s.dstId)) {
                        Log.w(TAG, "link to ${s.dstId} disconnected, abort ${s.fileId} at window [$windowStart..${s.expectEnd}]")
                        DebugLogBuffer.log(TAG, "send ABORT link disconnected dst=${s.dstId}")
                        finish(s, TransferStatus.FAILED)
                        return
                    }
                    val waiter = CompletableDeferred<FileAckBody?>()
                    ackWaiter = waiter
                    // 广播期间到达的 ACK 缓存在 pendingAck：立即消费，避免等待满窗口超时
                    pendingAck?.let { waiter.complete(it); pendingAck = null }
                    val ack = try { withTimeout(windowTimeoutMs) { waiter.await() } }
                    catch (e: TimeoutCancellationException) { null }
                    ackWaiter = null
                    if (ack == null) {
                        retries++
                        Log.w(TAG, "window timeout ${s.fileId} [$windowStart..${s.expectEnd}] retry=$retries")
                        DebugLogBuffer.log(TAG, "window timeout [$windowStart..${s.expectEnd}] retry=$retries")
                        if (retries > maxWindowRetries) { finish(s, TransferStatus.FAILED); return }
                        debugStats.recordFileWindowRetry()
                        // v1.1.37 退避：连续超时说明链路正卡顿，重发前等 300ms×retries（封顶 2s）给链路恢复时间，
                        // 避免在蓝牙栈忙/干扰期密集重发加剧拥塞（v1.1.36 前 1s 连发 5 次即放弃，卡顿 >5s 必失败）
                        delay(minOf(2_000L, 300L * retries))
                        broadcastWindow(s, cache)
                        continue
                    }
                    val need = ack.missing.filter { it in s.expectStart..s.expectEnd }
                    Log.d(TAG, "ack ${s.fileId} missing=${ack.missing.size} inWindow=${need.size}")
                    DebugLogBuffer.log(TAG, "recv FILE_ACK missing=${ack.missing.size} inWindow=${need.size}")
                    if (need.isEmpty()) {
                        windowStart += inWindow
                        s.lastMissingCount = Int.MAX_VALUE
                        updateProgress(s, TransferStatus.RUNNING)
                        break
                    }
                    // 补发缺失块（v1.1.28）：FILE3 走 GATT 无确认写，无节流连发，丢帧由后续 ACK 循环收敛。
                    // v1.1.25 的 30ms 节流是针对旧广播/notify 连发的丢帧防护，GATT 写场景不再需要。
                    for (i in need) {
                        broadcastChunk3(s, i, cache[i]!!)
                        delay(BROADCAST_INTERVAL_MS)
                    }
                    if (need.size >= s.lastMissingCount) retries++ else retries = 0
                    s.lastMissingCount = need.size
                    if (retries > maxWindowRetries) { finish(s, TransferStatus.FAILED); return }
                }
            }
            finish(s, TransferStatus.DONE)
        } catch (e: Exception) {
            Log.e(TAG, "sender crashed ${s.fileId}: $e")
            finish(s, TransferStatus.FAILED)
        }
    }

    /**
     * v1.1.28 发送预处理：压缩（或原样复制）源流到 dataFile，算总块数。
     * **openSource 只调用一次**（真机上 ContentResolver 流某些 provider 只能消费一次，二次打开会抛异常——
     * v1.1.28 真机 0 块根因）：先落盘 raw，再从 raw 决定压缩/原样，压缩无效（已压缩的图片/视频）直接复用 raw。
     * 数据块大小 File3.CHUNK_BYTES=480（二进制无 base64 膨胀）。
     */
    private fun prepareData(s: SendSession): Boolean = try {
        val dir = tmpDir()
        val raw = File(dir, "${s.fileId}.raw")
        s.openSource().use { input -> FileOutputStream(raw).use { output -> input.copyTo(output) } }
        if (s.size >= File3.COMPRESS_MIN_BYTES) {
            val dz = File(dir, "${s.fileId}.dz")
            // 压缩容错：deflate 异常（IO/大文件超时等）回退原样传输，绝不因压缩失败丢弃整个文件
            val compressed = runCatching {
                FileInputStream(raw).use { input ->
                    DeflaterInputStream(input).use { def -> FileOutputStream(dz).use { output -> def.copyTo(output) } }
                }
                dz.length() < raw.length()
            }.getOrDefault(false)
            if (compressed) {
                // 压缩有效（文本/JSON/文档等）：用压缩文件，释放 raw
                s.dataFile = dz
                s.compressed = true
                raw.delete()
            } else {
                // 压缩无效（已压缩的图片/视频/二进制）或压缩异常：原样传输，释放 dz
                dz.delete()
                s.dataFile = raw
                s.compressed = false
            }
        } else {
            // 小文件不压缩：deflate 对短数据反而膨胀
            s.dataFile = raw
            s.compressed = false
        }
        val file = s.dataFile ?: return false
        s.totalChunks = ((file.length() + s.chunkBytes - 1) / s.chunkBytes).toInt()
        s.totalChunks > 0
    } catch (e: Exception) {
        Log.e(TAG, "prepareData error: $e")
        s.dataFile?.delete()
        false
    }

    /** 顺序读窗口块（重传直接用缓存，无需重开流）。FILE3 数据源为预处理后的 dataFile。 */
    private fun readWindow(s: SendSession, start: Int, count: Int): Map<Int, ByteArray>? = runCatching {
        val file = s.dataFile ?: return@runCatching null
        val cache = LinkedHashMap<Int, ByteArray>()
        FileInputStream(file).use { source ->
            // InputStream.skip 不保证跳过全部字节（Java 契约），必须循环丢弃到目标偏移，否则多窗口读到的块错位
            var remaining = start * s.chunkBytes.toLong()
            while (remaining > 0) {
                val n = source.skip(minOf(remaining, 1L shl 16))
                if (n <= 0) break
                remaining -= n
            }
            val buf = ByteArray(s.chunkBytes)
            for (i in 0 until count) {
                val n = source.read(buf)
                if (n <= 0) break
                cache[start + i] = buf.copyOf(n)
            }
        }
        cache
    }.getOrNull()

    /** 广播窗口全部块（v1.1.28 FILE3）：每窗口先发一次 START 帧（幂等，元数据可靠），再以 2ms 间隔发数据块。 */
    private suspend fun broadcastWindow(s: SendSession, cache: Map<Int, ByteArray>) {
        broadcastStart3(s)
        var first = true
        for (chunkIndex in cache.keys.sorted()) {
            if (!first) delay(BROADCAST_INTERVAL_MS)
            first = false
            broadcastChunk3(s, chunkIndex, cache[chunkIndex]!!)
        }
    }

    /**
     * START 元数据帧（v1.1.28）：文件名/mime/原始大小/压缩标志/总块数。每窗口重发保证到达（幂等）。
     * v1.1.31 起走确认写 broadcast：模拟器+真机复现 WRITE_NO_RESPONSE 无确认写返回 true 但被蓝牙栈
     * 静默丢弃（不回调无法感知失败），文件帧全丢、接收端 0 块——文件帧回退确认写（与心跳同路径，可靠）。
     */
    private fun broadcastStart3(s: SendSession) {
        val payload = File3.encodeStart(
            srcId = shortId, fid = s.fileId, totalChunks = s.totalChunks,
            origSize = s.size, compressed = s.compressed, name = s.fileName, mime = s.mime,
        )
        val frame = MeshFrame(FrameType.DATA, payload)
        debugStats.recordSent(FrameKind.FILE_CHUNK, payload.size)
        transport.broadcast(frame)
    }

    /**
     * 数据块帧（v1.1.28 FILE3 二进制；v1.1.36 v2：头带 byteOffset，接收端按字节偏移写盘，块大小可动态）。
     * v1.1.31 起走确认写 broadcast：无确认写（WRITE_NO_RESPONSE）在 Android 蓝牙栈静默丢帧
     * （返回 true 但实际未送达、无回调），文件帧回退确认写保证可靠；丢帧仍由窗口重传兜底。
     * 老版本对端 decode MC3 帧失败自动丢帧。
     */
    private fun broadcastChunk3(s: SendSession, seq: Int, data: ByteArray) {
        val payload = File3.encodeChunk(shortId, s.fileId, seq, seq * s.chunkBytes.toLong(), data)
        val frame = MeshFrame(FrameType.DATA, payload)
        debugStats.recordSent(FrameKind.FILE_CHUNK, payload.size)
        transport.broadcast(frame)
    }

    private fun updateProgress(s: SendSession, status: TransferStatus) {
        // v1.1.28 压缩后块数与原始字节数不等：进度按块数比例映射到原始 size，显示平滑、100% = 传完
        val total = s.totalChunks
        val transferred = if (status == TransferStatus.DONE) s.size
            else (s.expectStart.toLong() * s.size / total.coerceAtLeast(1)).coerceAtMost(s.size)
        val progress = FileProgress(
            fileId = s.fileId, convId = s.convId, direction = TransferDirection.SENDING,
            fileName = s.fileName, totalBytes = s.size, transferredBytes = transferred, status = status,
        )
        _progress.value = progress
        onProgress(progress)
    }

    private fun finish(s: SendSession, status: TransferStatus) {
        if (sending === s) {
            sending = null
            senderJob = null
        }
        if (status == TransferStatus.FAILED) DebugLogBuffer.log(TAG, "send FAILED size=${s.size}")
        if (status == TransferStatus.DONE) DebugLogBuffer.log(TAG, "send DONE size=${s.size}")
        // 清理预处理产物（压缩/复制数据文件）
        s.dataFile?.delete()
        s.dataFile = null
        updateProgress(s, status)
    }

    // ---- 接收端 ----

    fun onFileChunk(envelope: MeshEnvelope) {
        when (val body = envelope.body) {
            is FileBody -> handleChunks(
                envelope, body.fileId, body.fileName, body.mime, body.size, body.totalChunks,
                listOf(body.chunkIndex to body.chunkData),
            )
            is FileBodyV2 -> handleChunks(
                envelope, body.fid, body.n, body.m, body.sz, body.tot,
                body.chunks.mapIndexed { i, data -> (body.start + i) to data },
            )
            else -> return
        }
    }

    /** 统一处理块到达（老 FileBody 单块 / 新 FileBodyV2 多块）：写盘 + ACK 合并（每窗口一次/重复块立即回/tick 兜底）。 */
    private fun handleChunks(
        envelope: MeshEnvelope,
        fileId: String,
        fileName: String,
        mime: String,
        size: Long,
        totalChunks: Int,
        chunks: List<Pair<Int, String>>,
    ) {
        if (size <= 0 || totalChunks <= 0) return
        val session = receivers.getOrPut(fileId) {
            ReceiveSession(
                fileId = fileId, convId = "conv-${envelope.srcId}", senderId = envelope.srcId,
                fileName = fileName, mime = mime, size = size,
                totalChunks = totalChunks,
                tmpFile = File(tmpDir(), "${fileId}.part"),
                received = mutableSetOf(), lastActivity = System.currentTimeMillis(),
            )
        }
        session.lastActivity = System.currentTimeMillis()
        for ((index, dataStr) in chunks) {
            val data = runCatching { Base64.getDecoder().decode(dataStr) }.getOrNull() ?: continue
            debugStats.recordReceived(FrameKind.FILE_CHUNK, data.size)
            val isNew = session.writeChunk(index, data)   // 重复块内部幂等跳过并返回 false
            session.ackDirty = true
            if (session.isComplete) {
                completeReceive(session)
                return  // 已收齐：后续块（含本帧剩余）忽略，防重建已删除的 tmpFile
            } else if (!isNew) {
                // 重复块 = 发送端正在等确认/超时重发：立即回 ACK 让其收敛，不等窗口边界
                sendAck(session)
            } else if (session.ackCounter++ % WINDOW == 0) {
                // ACK 合并：每收满一窗口回一次，避免逐块 ACK 洪泛（60 个/s × 300B 挤占带宽超过数据本身）
                sendAck(session)
            }
        }
        // 不足整窗口的收尾由 tick 的 ACK_FLUSH_MS 兜底回 ACK（不拖到发送端 1s 窗口超时）
        updateReceiveProgress(session, TransferStatus.RUNNING)
    }

    // ---- v1.1.28 FILE3 二进制帧接收 ----

    /** FILE3 帧入口（MeshService.handleFrame 魔数旁路后调用）：START 建/更新会话元数据，CHUNK 写块。 */
    fun onFile3Frame(payload: ByteArray) {
        when (val f = File3.parse(payload)) {
            is File3.Frame.StartFrame -> handleStart3(f.start)
            is File3.Frame.ChunkFrame -> handleChunk3(f.chunk)
            null -> debugStats.recordReceivedFailure()
        }
    }

    private fun handleStart3(start: File3.Start) {
        if (start.origSize <= 0 || start.totalChunks <= 0) return
        val session = receivers.getOrPut(start.fid) {
            ReceiveSession(
                fileId = start.fid, convId = "conv-${start.srcId}", senderId = start.srcId,
                tmpFile = File(tmpDir(), "${start.fid}.part"),
                received = mutableSetOf(), lastActivity = System.currentTimeMillis(),
                // v1 老帧（v1.1.28~35 发送端，块固定 456）定位步长；v2 帧走 byteOffset 不依赖
                chunkSize = File3.LEGACY_CHUNK_BYTES,
            )
        }
        session.lastActivity = System.currentTimeMillis()
        session.fileName = start.name
        session.mime = start.mime
        session.size = start.origSize
        session.totalChunks = start.totalChunks
        session.compressed = start.compressed
        // 块已先到齐（START 乱序/重发）：补上元数据即可收尾
        if (session.isComplete) completeReceive(session)
    }

    private fun handleChunk3(chunk: File3.Chunk) {
        if (chunk.data.isEmpty()) return
        val session = receivers.getOrPut(chunk.fid) {
            // 元数据可能未到（START 帧晚到）：先建会话收块，START 到达后补齐（handleStart3）
            ReceiveSession(
                fileId = chunk.fid, convId = "conv-${chunk.srcId}", senderId = chunk.srcId,
                tmpFile = File(tmpDir(), "${chunk.fid}.part"),
                received = mutableSetOf(), lastActivity = System.currentTimeMillis(),
                // v1 老帧定位步长；v2 帧（byteOffset ≥ 0）写盘不依赖
                chunkSize = File3.LEGACY_CHUNK_BYTES,
            )
        }
        session.lastActivity = System.currentTimeMillis()
        debugStats.recordReceived(FrameKind.FILE_CHUNK, chunk.data.size)
        // v2 帧（块大小随发送端 MTU 动态）按帧内字节偏移写盘；v1 老帧回退 seq×456
        val isNew = if (chunk.byteOffset >= 0) {
            session.writeChunkAt(chunk.seq, chunk.byteOffset, chunk.data)
        } else {
            session.writeChunk(chunk.seq, chunk.data)
        }
        session.ackDirty = true
        if (session.isComplete && session.metaReady) {
            completeReceive(session)
        } else if (!isNew) {
            // 重复块 = 发送端正在等确认/超时重发：立即回 ACK 让其收敛
            sendAck(session)
        } else if (session.ackCounter++ % WINDOW == 0) {
            // ACK 合并：每收满一窗口回一次
            sendAck(session)
        }
        updateReceiveProgress(session, TransferStatus.RUNNING)
    }

    fun onFileAck(envelope: MeshEnvelope) {
        val body = envelope.body as? FileAckBody ?: return
        debugStats.recordReceived(FrameKind.FILE_ACK, 0)
        val s = sending ?: return
        if (body.fileId == s.fileId) {
            Log.d(TAG, "recv FILE_ACK ${body.fileId} missing=${body.missing.size}")
            val waiter = ackWaiter
            if (waiter != null) waiter.complete(body) else pendingAck = body
        }
    }

    /** 接收超时清理：由外部 tick 驱动（MeshService 200ms 循环）。 */
    fun tick(now: Long) {
        val it = receivers.entries.iterator()
        while (it.hasNext()) {
            val (fileId, s) = it.next()
            if (now - s.lastActivity > RECV_STALL_TIMEOUT_MS) {
                s.tmpFile.delete()
                updateReceiveProgress(s, TransferStatus.FAILED)
                it.remove()
            } else if (s.ackDirty && s.metaReady && now - s.lastAckAt > ACK_FLUSH_MS) {
                // ACK 合并兜底：最后不足整窗的收尾/发送端已停的窗口 → 立即回 ACK，避免拖到 1s 窗口超时整窗重发
                // metaReady 限定：元数据未到（FILE3 START 帧丢失）不回 ACK，等发送端窗口重发 START
                sendAck(s)
            }
        }
    }

    private fun tmpDir(): File = tmpDirProvider().apply { mkdirs() }

    private fun completeReceive(s: ReceiveSession) {
        if (!s.isComplete || !s.metaReady) return
        val tmp = s.tmpFile
        val payloadFile: File
        if (s.compressed) {
            // v1.1.28 FILE3 压缩：tmp 是 deflate 流，解压还原原始内容后再落盘
            payloadFile = File(tmpDir(), "${s.fileId}.final")
            try {
                InflaterInputStream(FileInputStream(tmp)).use { input ->
                    FileOutputStream(payloadFile).use { output -> input.copyTo(output) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "inflate failed ${s.fileId}: $e")
                tmp.delete()
                payloadFile.delete()
                receivers.remove(s.fileId)
                updateReceiveProgress(s, TransferStatus.FAILED)
                return
            }
        } else {
            payloadFile = tmp
        }
        if (payloadFile.length() != s.size) {
            tmp.delete()
            if (payloadFile !== tmp) payloadFile.delete()
            receivers.remove(s.fileId)
            DebugLogBuffer.log(TAG, "recv FAILED size mismatch expected=${s.size} got=${payloadFile.length()}")
            updateReceiveProgress(s, TransferStatus.FAILED)
            return
        }
        val uri = saver.save(payloadFile, s.fileName, s.mime)
        tmp.delete()
        if (payloadFile !== tmp) payloadFile.delete()
        sendAck(s, final = true)
        receivers.remove(s.fileId)
        onSaved(s.convId, s.fileId, s.fileName, s.mime, s.size, uri)
        updateReceiveProgress(s, TransferStatus.DONE)
    }

    private fun sendAck(s: ReceiveSession, final: Boolean = false) {
        val ack = MeshEnvelope(
            id = UUID.randomUUID().toString(),
            kind = "FILE_ACK",
            srcId = shortId,
            dstId = s.senderId,
            convId = s.convId,
            ttl = 8,
            ts = System.currentTimeMillis(),
            body = FileAckBody(fileId = s.fileId, totalChunks = s.totalChunks,
                missing = if (final) emptyList() else s.missing.take(MAX_ACK_MISSING)),
        )
        val frame = MeshFrame(FrameType.DATA, MeshJson.encodeEnvelope(ack).toByteArray())
        debugStats.recordSent(FrameKind.FILE_ACK, frame.payload.size)
        s.ackDirty = false
        s.lastAckAt = System.currentTimeMillis()
        sendFrame(s.senderId, frame)
    }

    private fun updateReceiveProgress(s: ReceiveSession, status: TransferStatus) {
        // v1.1.28 压缩场景：块数按比例映射到原始 size（totalChunks 未到=0 时显示 0，元数据到达后恢复）
        val transferred = if (s.totalChunks > 0)
            (s.received.size.toLong() * s.size / s.totalChunks).coerceAtMost(s.size)
        else 0L
        val progress = FileProgress(
            fileId = s.fileId, convId = s.convId, direction = TransferDirection.RECEIVING,
            fileName = s.fileName, totalBytes = s.size,
            transferredBytes = transferred,
            status = status,
        )
        _progress.value = progress
        onProgress(progress)
    }
}
