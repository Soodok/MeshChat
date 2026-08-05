package com.meshchat.app.mesh.transfer

import android.util.Log
import com.meshchat.app.mesh.debug.FrameKind
import com.meshchat.app.mesh.protocol.FileAckBody
import com.meshchat.app.mesh.protocol.FileBody
import com.meshchat.app.mesh.protocol.FileBodyV2
import com.meshchat.app.mesh.protocol.FrameType
import com.meshchat.app.mesh.protocol.MeshEnvelope
import com.meshchat.app.mesh.protocol.MeshFrame
import com.meshchat.app.mesh.protocol.MeshJson
import com.meshchat.app.mesh.transport.MeshTransport
import java.io.File
import java.io.InputStream
import java.util.Base64
import java.util.UUID
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
        // 块 120B（base64 160B）→ FILE2 单块帧 ~498B < MTU 512 可用载荷 509B（DIAG 实测）。
        // 数据占比 120/498 = 24%（vs FILE 90B 块 505B 帧 18%）。大块单块优于小块多块：
        // base64 固定 +4/3 膨胀且信封头 ~310B 固定，多块合并摊薄头需要头 ≤269B（当前头装不下 2 块），
        // 块越大每字节数据的头开销越小。无确认写（writeUnreliable）后写次数不再是瓶颈，每帧数据量决定吞吐。
        const val CHUNK_BYTES = 120
        // 窗口 8 块（~960B/窗）：32 块（16KB/窗）超 BLE 实际吞吐（1-5KB/s），15s 内收不齐 → 整窗重发恶性循环
        const val WINDOW = 8
        const val WINDOW_TIMEOUT_MS = 1_000L    // ACK 全丢时的兜底：每块 ACK 模式下窗口往返 ~500ms，1s 足够；越短丢 ACK 恢复越快
        const val MAX_WINDOW_RETRIES = 5
        const val RECV_STALL_TIMEOUT_MS = 60_000L
        /** 窗口内逐帧广播的间隔：BLE notify 连发会触发系统丢弃，30ms 节流显著降丢帧（初发与补发都必须节流）。 */
        const val BROADCAST_INTERVAL_MS = 30L
        /**
         * 每帧携带块数（v1.1.27）：固定 1——FILE2 信封头 ~310B 使 2 块（2×120 base64 320B）超 MTU，
         * 而块大小与块数在 base64 下等价（数据量×4/3），大块单块的头摊薄效果最佳。头压缩（fid 短化等）后可改 2+。
         */
        const val CHUNKS_PER_FRAME = 1
        /**
         * ACK 缺失列表截断上限：发送端只 filter 当前窗口内缺失（窗口 8 块），
         * 更早窗口已收齐才推进（need 空），故窗口内缺失必位于全文件缺失列表前部，
         * take(WINDOW) 恰好覆盖。= WINDOW 强耦合，改 WINDOW 必须同步。
         * 40 项时代 ACK 帧 ~300B、60 个/s（每块回）→ ACK 洪泛挤占 BLE 带宽超过数据本身；8 项 → 帧 ~120B。
         */
        const val MAX_ACK_MISSING = WINDOW
        /** 接收端 ACK 合并兜底：距上次 ACK 超过该时长仍有未确认新块 → 立即回（最后不足整窗的收尾不拖到窗口超时）。 */
        const val ACK_FLUSH_MS = 300L
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
    ) {
        var expectStart = 0
        var expectEnd = 0
        var lastMissingCount = Int.MAX_VALUE
    }

    /** 接收会话（临时文件 + 已收块集合）。 */
    private class ReceiveSession(
        val fileId: String,
        val convId: String,
        val senderId: String,
        val fileName: String,
        val mime: String,
        val size: Long,
        val totalChunks: Int,
        val tmpFile: File,
        val received: MutableSet<Int>,
        var lastActivity: Long,
        var ackCounter: Int = 0,
        /** 自上次 ACK 后是否有新块未确认（tick 兜底回 ACK 用）。 */
        var ackDirty: Boolean = false,
        var lastAckAt: Long = 0L,
    ) {
        /** 写块；返回是否为新块（重复块幂等跳过并返回 false，用于触发立即回 ACK）。 */
        fun writeChunk(chunkIndex: Int, data: ByteArray): Boolean {
            if (chunkIndex in received) return false
            java.io.RandomAccessFile(tmpFile, "rw").use { raf ->
                raf.seek(chunkIndex * CHUNK_BYTES.toLong())
                raf.write(data)
            }
            received += chunkIndex
            return true
        }

        val missing: List<Int> get() = (0 until totalChunks).filter { it !in received }
        val isComplete: Boolean get() = received.size >= totalChunks
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
        )
        Log.d(TAG, "sendFile start fileId=${session.fileId} size=$size chunks=${(size + CHUNK_BYTES - 1) / CHUNK_BYTES} name=$fileName")
        sending = session
        senderJob = scope.launch { runSender(session) }
        return session.fileId
    }

    private suspend fun runSender(s: SendSession) {
        val totalChunks = ((s.size + CHUNK_BYTES - 1) / CHUNK_BYTES).toInt()
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
                Log.d(TAG, "send window ${s.fileId} [$windowStart..${s.expectEnd}]/${totalChunks} frames=${inWindow} chunkBytes=$CHUNK_BYTES")
                broadcastWindow(s, cache)
                var retries = 0
                while (true) {
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
                        if (retries > maxWindowRetries) { finish(s, TransferStatus.FAILED); return }
                        debugStats.recordFileWindowRetry()
                        broadcastWindow(s, cache)
                        continue
                    }
                    val need = ack.missing.filter { it in s.expectStart..s.expectEnd }
                    Log.d(TAG, "ack ${s.fileId} missing=${ack.missing.size} inWindow=${need.size}")
                    if (need.isEmpty()) {
                        windowStart += inWindow
                        s.lastMissingCount = Int.MAX_VALUE
                        updateProgress(s, TransferStatus.RUNNING)
                        break
                    }
                    // 补发缺失块：必须与初发同样 30ms 节流——BLE 广播连发触发系统丢弃，
                    // 零节流突发补发（曾达 180 p/s）会丢得更狠 → missing 不收敛 → retries 秒级爆到上限 FAILED
                    for (i in need) {
                        broadcastFrameV2(s, listOf(i to cache[i]!!), totalChunks, s.fileName, s.mime, s.size)
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

    /** 顺序读窗口块，返回 chunkIndex -> base64（重传直接用缓存，无需重开流）。 */
    private fun readWindow(s: SendSession, start: Int, count: Int): Map<Int, String>? = runCatching {
        val source = s.openSource()
        val cache = LinkedHashMap<Int, String>()
        // InputStream.skip 不保证跳过全部字节（Java 契约），必须循环丢弃到目标偏移，否则多窗口读到的块错位
        var remaining = start * CHUNK_BYTES.toLong()
        val buf = ByteArray(CHUNK_BYTES)
        while (remaining > 0) {
            val n = source.read(buf, 0, minOf(CHUNK_BYTES, remaining.toInt()))
            if (n <= 0) break
            remaining -= n
        }
        for (i in 0 until count) {
            val n = source.read(buf)
            cache[start + i] = Base64.getEncoder().encodeToString(buf.copyOfRange(0, n.coerceAtLeast(0)))
        }
        source.close()
        cache
    }.getOrNull()

    /** 广播窗口全部块（每帧 CHUNKS_PER_FRAME 块，块间 30ms 节流，避免 BLE notify 连发触发系统丢弃）。 */
    private suspend fun broadcastWindow(s: SendSession, cache: Map<Int, String>) {
        val total = ((s.size + CHUNK_BYTES - 1) / CHUNK_BYTES).toInt()
        var first = true
        cache.keys.sorted().chunked(CHUNKS_PER_FRAME).forEach { frame ->
            if (!first) delay(BROADCAST_INTERVAL_MS)
            first = false
            broadcastFrameV2(s, frame.map { it to cache[it]!! }, total, s.fileName, s.mime, s.size)
        }
    }

    /**
     * 广播 FILE2 帧（v1.1.27 多块合并 + 无确认写）：
     * 每帧带 1~CHUNKS_PER_FRAME 块，数据块走 transport.writeUnreliable（GATT WRITE_NO_RESPONSE，
     * 无写往返瓶颈）；丢帧由窗口重传兜底。ACK/普通消息仍走可靠写。老版本对端 decode FILE2 失败自动丢帧。
     */
    private fun broadcastFrameV2(
        s: SendSession,
        chunks: List<Pair<Int, String>>,
        totalChunks: Int,
        name: String,
        mime: String,
        size: Long,
    ) {
        val envelope = MeshEnvelope(
            // 块帧信封 id 短化（dedup 唯一即可，省 20+ 字符）
            id = "f${s.fileId.take(12)}-${chunks.first().first}",
            kind = "FILE2",
            srcId = shortId,
            dstId = s.dstId,
            convId = s.convId,
            ttl = 8,
            ts = System.currentTimeMillis(),
            body = FileBodyV2(
                fid = s.fileId, n = name, m = mime, sz = size, tot = totalChunks,
                start = chunks.first().first, chunks = chunks.map { it.second },
            ),
        )
        val frame = MeshFrame(FrameType.DATA, MeshJson.encodeEnvelope(envelope).toByteArray())
        debugStats.recordSent(FrameKind.FILE_CHUNK, frame.payload.size)
        transport.writeUnreliable(frame)
    }

    private fun updateProgress(s: SendSession, status: TransferStatus) {
        val total = ((s.size + CHUNK_BYTES - 1) / CHUNK_BYTES).toInt()
        val transferred = if (status == TransferStatus.DONE) s.size
            else (s.expectStart.coerceAtMost(total) * CHUNK_BYTES.toLong()).coerceAtMost(s.size)
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
            } else if (s.ackDirty && now - s.lastAckAt > ACK_FLUSH_MS) {
                // ACK 合并兜底：最后不足整窗的收尾/发送端已停的窗口 → 立即回 ACK，避免拖到 1s 窗口超时整窗重发
                sendAck(s)
            }
        }
    }

    private fun tmpDir(): File = tmpDirProvider().apply { mkdirs() }

    private fun completeReceive(s: ReceiveSession) {
        if (s.tmpFile.length() != s.size) {
            s.tmpFile.delete()
            receivers.remove(s.fileId)
            updateReceiveProgress(s, TransferStatus.FAILED)
            return
        }
        val uri = saver.save(s.tmpFile, s.fileName, s.mime)
        s.tmpFile.delete()
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
        val progress = FileProgress(
            fileId = s.fileId, convId = s.convId, direction = TransferDirection.RECEIVING,
            fileName = s.fileName, totalBytes = s.size,
            transferredBytes = (s.received.size * CHUNK_BYTES.toLong()).coerceAtMost(s.size),
            status = status,
        )
        _progress.value = progress
        onProgress(progress)
    }
}
