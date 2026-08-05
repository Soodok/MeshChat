package com.meshchat.app.mesh.debug

/**
 * 诊断日志环形缓冲（v1.1.34）：App 内内存态记录关键传输事件（BLE 写/notify/连接/MTU、文件窗口/ACK/失败、
 * FILE3 接收），供调试中心查看与一键导出到 Download——真机无法连电脑抓 logcat 时用文件管理器导出日志。
 * 线程安全；纯 Kotlin 无 Android 依赖。
 */
object DebugLogBuffer {
    private const val MAX = 3000
    private val lock = Any()
    private val queue = ArrayDeque<String>()

    fun log(tag: String, msg: String) {
        val line = "[${System.currentTimeMillis()}] $tag: $msg"
        synchronized(lock) {
            queue.addLast(line)
            while (queue.size > MAX) queue.removeFirst()
        }
    }

    /** 最近 n 条（调试中心显示）。 */
    fun recent(n: Int): List<String> = synchronized(lock) { queue.toList().takeLast(n) }

    /** 全量导出（Download 文件）。 */
    fun dump(): String = synchronized(lock) { queue.joinToString("\n") }

    fun clear() = synchronized(lock) { queue.clear() }

    fun size(): Int = synchronized(lock) { queue.size }
}
