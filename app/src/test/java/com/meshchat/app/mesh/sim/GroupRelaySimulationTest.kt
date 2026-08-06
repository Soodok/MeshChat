package com.meshchat.app.mesh.sim

import java.util.PriorityQueue
import kotlin.random.Random
import org.junit.Test

/**
 * 极端网络群消息发送延迟仿真（v1.1.50 群消息 MVP 设计决策验证）。
 *
 * 目的：量化「群消息不加回执」在极端网络（高丢包/多跳/多成员）下的真实风险，
 *       并对比「加回执」各节流策略的确认延迟与带宽代价，给设计决策提供数据。
 *
 * 模型（离散事件，确定性种子，蒙特卡洛平均）：
 *  - 拓扑：链式（A1-A2-...-An），相邻节点一跳可达（BLE 广播域）
 *  - 一跳广播延迟：100ms 基线（BLE 广播周期）；泛洪转发抖动 50-250ms（现网实现）
 *  - 每跳广播丢包率 p（逐邻居独立判定，10%~40%）
 *  - 泛洪：TTL 8（现网值）、消息级去重（节点只处理一次）、转发带抖动
 *  - 群消息：发送方 A1 在 t=0 广播一条群消息，目标=全部成员（广播域模型）
 *  - 回执策略：
 *      NONE   = 无回执（v1.1.50 草案）
 *      ALL    = 每个成员收到立即回执一次（RECEIPT 泛洪回传，同帧去重）
 *      RANDOM = 每个成员延迟 0-500ms 随机 + 30% 概率回执（节流，错开泛洪）
 *  - 发送方确认时刻：收到任一有效 RECEIPT（按 msgId 匹配）
 *
 * 度量：最晚成员到达 / 发送方确认延迟 / 消息帧数 / 回执帧数（带宽放大）。
 */
class GroupRelaySimulationTest {

    private enum class AckMode { NONE, ALL, RANDOM }

    /** 丢包剖面：CONSTANT = 全程恒定丢包；TRANSIENT = 前 8s 高丢包后恢复（遮挡/移动场景，重发价值最大）。 */
    private enum class DropProfile { CONSTANT, TRANSIENT }

    private data class Config(
        val nodes: Int,      // 节点数（链长 = nodes-1）
        val members: Int,    // 群成员数（含发送方 A1，取链头 members 个）
        val dropP: Double,   // 每跳广播丢包率
        val ackMode: AckMode,
        /** 发送方重发次数（v1.1.50 修订：未确认群消息每 5s 重发同 msgId）。 */
        val resends: Int = 0,
        /** true = 确认即停（收到任一回执停止重发）；false = 固定重发 N 次。 */
        val stopOnConfirm: Boolean = false,
        /** true = 重发用**新 msgId**（新 envelope id = 新泛洪，能推进到未收节点，代价已收节点重复接收）；false = 同 msgId（仿真已证伪：节点级去重挡住，重发完全无效）。 */
        val newIdOnResend: Boolean = false,
        val dropProfile: DropProfile = DropProfile.CONSTANT,
    ) {
        /** 时刻 t 的丢包率（瞬时剖面：前 8s 高丢包，之后 5% 正常）。 */
        fun dropAt(t: Long): Double = when (dropProfile) {
            DropProfile.CONSTANT -> dropP
            DropProfile.TRANSIENT -> if (t < 8_000L) dropP else 0.05
        }
    }

    private data class Result(
        val lastArrivalMs: Double,  // 消息到达全部成员的最晚时刻
        val confirmMs: Double,      // 发送方收到确认的时刻（NONE = -1）
        val msgFrames: Int,         // 消息帧（送达边数，即实际广播次数）
        val ackFrames: Int,         // 回执帧（送达边数）
        val allMembersReached: Boolean,  // 本次运行消息是否到达全部成员（NONE 风险核心指标）
        val dupReceives: Int,       // 新 id 重发造成的成员重复接收次数（重复成本）
    )

    // 事件：时刻 / 所在节点 / 帧种类 / msgId / 帧 srcId / 帧 dstId / ttl / 是否发送方重发
    private class Evt(
        val time: Long,
        val node: Int,
        val isAck: Boolean,
        val msgId: Int,
        val srcId: Int,
        val dstId: Int,
        val ttl: Int,
        val isResend: Boolean = false,
    ) : Comparable<Evt> {
        override fun compareTo(other: Evt): Int = time.compareTo(other.time)
    }

    private fun simulate(cfg: Config, seed: Int): Result {
        val rng = Random(seed)
        // 链式拓扑：i 与 i-1, i+1 相邻
        val neighbors: Array<IntArray> = Array(cfg.nodes) { i ->
            listOf(i - 1, i + 1).filter { it in 0 until cfg.nodes }.toIntArray()
        }
        val msgSeen = Array(cfg.nodes) { HashSet<Int>() }   // 消息级去重
        val ackSeen = Array(cfg.nodes) { HashSet<Int>() }   // 回执去重（"receipt-id" 语义）
        val receivedMsgAt = HashMap<Int, Long>()             // 成员 node -> 首次收到群消息时刻
        var senderConfirmedAt = -1L
        var msgFrames = 0
        var ackFrames = 0
        var dupReceives = 0

        val queue = PriorityQueue<Evt>()
        val sender = 0
        val members = (0 until cfg.members).toSet()
        // t=0 发送方广播群消息（msgId=1）
        queue.add(Evt(0, sender, false, 1, sender, -1, 8))
        // 预生成重发事件（5s 间隔；同 msgId 或新 msgId（每次 +1）由 newIdOnResend 决定）
        for (i in 1..cfg.resends) {
            val mid = if (cfg.newIdOnResend) 1 + i else 1
            queue.add(Evt(5_000L * i, sender, false, mid, sender, -1, 8, isResend = true))
        }

        while (queue.isNotEmpty()) {
            val e = queue.poll()
            if (e.isAck) {
                if (e.node == sender) {
                    // 发送方收到回执：首个确认即记录
                    if (senderConfirmedAt < 0) senderConfirmedAt = e.time
                    continue
                }
                if (!ackSeen[e.node].add(e.msgId)) continue
                // 转发回执（TTL 内），带抖动
                if (e.ttl - 1 > 0) {
                    val jitter = 50 + rng.nextInt(200)
                    for (nb in neighbors[e.node]) {
                        if (rng.nextDouble() < cfg.dropAt(e.time)) continue
                        ackFrames++
                        queue.add(Evt(e.time + 100 + jitter, nb, true, e.msgId, e.srcId, e.dstId, e.ttl - 1))
                    }
                }
                continue
            }
            // 消息帧
            if (e.isResend && e.node == sender && cfg.stopOnConfirm && senderConfirmedAt >= 0) continue // 确认即停策略：已确认不再重发
            if (!msgSeen[e.node].add(e.msgId)) continue
            if (e.node in members && e.node != sender && !receivedMsgAt.containsKey(e.node)) {
                receivedMsgAt[e.node] = e.time
                // 成员回执策略（发送方不回执给自己；已收成员对新 id 重发不再回执——真实实现同）
                when (cfg.ackMode) {
                    AckMode.NONE -> Unit
                    AckMode.ALL -> scheduleAck(queue, rng, e, 0, always = true)
                    AckMode.RANDOM -> scheduleAck(queue, rng, e, 500, always = false)
                }
            } else if (e.node in members && e.node != sender && e.msgId > 1) {
                // 新 id 重发到达已收成员：重复接收（UI 层需内容去重，此处统计成本）
                dupReceives++
            }
            if (e.ttl - 1 > 0) {
                val jitter = 50 + rng.nextInt(200)
                for (nb in neighbors[e.node]) {
                    if (rng.nextDouble() < cfg.dropAt(e.time)) continue
                    msgFrames++
                    queue.add(Evt(e.time + 100 + jitter, nb, false, e.msgId, e.srcId, e.dstId, e.ttl - 1))
                }
            }
        }

        val lastArrival = receivedMsgAt.values.maxOrNull()?.toDouble() ?: -1.0
        val allReached = (members - sender).all { receivedMsgAt.containsKey(it) }
        return Result(lastArrival, senderConfirmedAt.toDouble(), msgFrames, ackFrames, allReached, dupReceives)
    }

    private fun scheduleAck(
        queue: PriorityQueue<Evt>,
        rng: Random,
        e: Evt,
        maxDelay: Int,
        always: Boolean,
    ) {
        if (!always && rng.nextDouble() > 0.30) return   // RANDOM：30% 概率回执
        val delay = if (maxDelay > 0) rng.nextInt(maxDelay) else 0
        val ack = Evt(e.time + delay, e.node, true, e.msgId, e.node, 0, 8)
        // 回执先到本节点"发送"，下一跳由邻居转发；此处直接入队从本节点泛洪
        queue.add(ack)
    }

    private fun avg(vals: List<Double>): Double = if (vals.isEmpty()) -1.0 else vals.average()

    @Test
    fun `print group relay simulation under extreme network`() {
        val runs = 40
        val scenarios = listOf(
            Triple(5, 5, 0.20),   // 5 节点链 / 全员成员 / 20% 丢包
            Triple(5, 5, 0.40),   // 同上，40% 极端丢包
            Triple(8, 8, 0.20),   // 8 节点链（7 跳）/ 全员 / 20%
            Triple(8, 4, 0.40),   // 8 节点链 / 半员 / 40% 极端
            Triple(12, 12, 0.30), // 12 节点（11 跳）/ 全员 / 30%
        )
        println("=".repeat(120))
        println("MeshChat 群消息极端网络仿真（离散事件，${runs} 次蒙特卡洛平均；一跳=100ms；TTL=8；转发抖动 50-250ms）")
        println("=".repeat(120))
        println("%-30s %-12s %-12s %-12s %-12s %-12s %-10s".format(
            "场景", "全员到达率", "最晚成员到达", "发送方确认", "消息帧", "回执帧", "带宽放大"))
        println("-".repeat(120))
        for ((n, m, p) in scenarios) {
            for (mode in AckMode.entries) {
                val results = (0 until runs).map { simulate(Config(n, m, p, mode), seed = 1000 + it) }
                val reachRate = results.count { it.allMembersReached }.toDouble() / runs * 100
                val lastArr = avg(results.filter { it.allMembersReached }.map { it.lastArrivalMs })
                val confirm = if (mode == AckMode.NONE) -1.0 else avg(results.map { it.confirmMs })
                val msgF = results.map { it.msgFrames }.average()
                val ackF = results.map { it.ackFrames }.average()
                val totalF = msgF + ackF
                val label = "${n}节点/成员$m/丢包${(p * 100).toInt()}%/${mode}"
                val amp = if (mode == AckMode.NONE) 1.0 else totalF / msgF.coerceAtLeast(1.0)
                println("%-30s %-12s %-12s %-12s %-12s %-12s %-10s".format(
                    label,
                    "%.0f%%".format(reachRate),
                    if (lastArr >= 0) "%.0f ms".format(lastArr) else "未到达",
                    if (confirm >= 0) "%.0f ms".format(confirm) else "无确认",
                    "%.1f".format(msgF),
                    "%.1f".format(ackF),
                    "%.2fx".format(amp),
                ))
            }
            println("-".repeat(120))
        }
    }

    /**
     * v1.1.50 修订方案验证：**重发机制生效时的表现**（用户指定重点）。
     * 对比两种重发策略：
     *  - 固定重发（stopOnConfirm=false）：重发 N 次与确认解耦——群消息目的=全员到达，确认只代表近端成员；
     *  - 确认即停（stopOnConfirm=true）：收到任一确认停止重发（原草案，仿真预期其因近端确认太快而失效）。
     * 测量：全员到达率提升、确认延迟、消息/回执帧（带宽）。
     */
    @Test
    fun `print resend mechanism effect under extreme network`() {
        val runs = 60
        // (label, Config)——三种重发姿势对照：无重发 / 同 id 重发（节点级去重挡住→预期无效）/ 新 id 重发（新泛洪能推进）
        val scenarios = listOf(
            // 场景 1：持续 40% 极端丢包，8 节点链
            "8节点/成员8/持续40% 无重发" to Config(8, 8, 0.40, AckMode.RANDOM, resends = 0),
            "8节点/成员8/持续40% 同id重发3次" to Config(8, 8, 0.40, AckMode.RANDOM, resends = 3),
            "8节点/成员8/持续40% 新id重发3次" to Config(8, 8, 0.40, AckMode.RANDOM, resends = 3, newIdOnResend = true),
            // 场景 2：瞬时丢包（前 8s 60%，之后恢复 5%）——遮挡/移动场景，重发价值最大
            "8节点/成员8/瞬时60%→5% 无重发" to Config(8, 8, 0.60, AckMode.RANDOM, resends = 0, dropProfile = DropProfile.TRANSIENT),
            "8节点/成员8/瞬时60%→5% 同id重发3次" to Config(8, 8, 0.60, AckMode.RANDOM, resends = 3, dropProfile = DropProfile.TRANSIENT),
            "8节点/成员8/瞬时60%→5% 新id重发3次" to Config(8, 8, 0.60, AckMode.RANDOM, resends = 3, newIdOnResend = true, dropProfile = DropProfile.TRANSIENT),
            // 场景 3：12 节点超长链 30% 丢包
            "12节点/成员12/持续30% 无重发" to Config(12, 12, 0.30, AckMode.RANDOM, resends = 0),
            "12节点/成员12/持续30% 同id重发3次" to Config(12, 12, 0.30, AckMode.RANDOM, resends = 3),
            "12节点/成员12/持续30% 新id重发3次" to Config(12, 12, 0.30, AckMode.RANDOM, resends = 3, newIdOnResend = true),
        )
        println()
        println("=".repeat(130))
        println("MeshChat 群消息【重发机制】仿真（${runs} 次蒙特卡洛；节流回执 30%+随机延迟；重发 5s 间隔）")
        println("=".repeat(130))
        println("%-36s %-12s %-12s %-10s %-10s %-10s".format(
            "场景", "全员到达率", "发送方确认", "消息帧", "回执帧", "重复接收"))
        println("-".repeat(130))
        for ((label, cfg) in scenarios) {
            val results = (0 until runs).map { simulate(cfg, seed = 2000 + it) }
            val reachRate = results.count { it.allMembersReached }.toDouble() / runs * 100
            val confirm = avg(results.map { it.confirmMs })
            val msgF = results.map { it.msgFrames }.average()
            val ackF = results.map { it.ackFrames }.average()
            val dup = results.map { it.dupReceives }.average()
            println("%-36s %-12s %-12s %-10s %-10s %-10s".format(
                label,
                "%.0f%%".format(reachRate),
                if (confirm >= 0) "%.0f ms".format(confirm) else "无确认",
                "%.1f".format(msgF),
                "%.1f".format(ackF),
                "%.1f".format(dup),
            ))
        }
        println("-".repeat(130))
    }
}
