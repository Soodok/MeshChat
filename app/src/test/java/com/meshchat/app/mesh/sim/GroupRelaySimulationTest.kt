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

    private data class Config(
        val nodes: Int,      // 节点数（链长 = nodes-1）
        val members: Int,    // 群成员数（含发送方 A1，取链头 members 个）
        val dropP: Double,   // 每跳广播丢包率
        val ackMode: AckMode,
    )

    private data class Result(
        val lastArrivalMs: Double,  // 消息到达全部成员的最晚时刻
        val confirmMs: Double,      // 发送方收到确认的时刻（NONE = -1）
        val msgFrames: Int,         // 消息帧（送达边数，即实际广播次数）
        val ackFrames: Int,         // 回执帧（送达边数）
        val allMembersReached: Boolean,  // 本次运行消息是否到达全部成员（NONE 风险核心指标）
    )

    // 事件：时刻 / 所在节点 / 帧种类 / msgId / 帧 srcId / 帧 dstId / ttl
    private class Evt(
        val time: Long,
        val node: Int,
        val isAck: Boolean,
        val msgId: Int,
        val srcId: Int,
        val dstId: Int,
        val ttl: Int,
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

        val queue = PriorityQueue<Evt>()
        val sender = 0
        val members = (0 until cfg.members).toSet()
        // t=0 发送方广播群消息（msgId=1）
        queue.add(Evt(0, sender, false, 1, sender, -1, 8))

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
                        if (rng.nextDouble() < cfg.dropP) continue
                        ackFrames++
                        queue.add(Evt(e.time + 100 + jitter, nb, true, e.msgId, e.srcId, e.dstId, e.ttl - 1))
                    }
                }
                continue
            }
            // 消息帧
            if (!msgSeen[e.node].add(e.msgId)) continue
            if (e.node in members && e.node != sender && !receivedMsgAt.containsKey(e.node)) {
                receivedMsgAt[e.node] = e.time
                // 成员回执策略（发送方不回执给自己）
                when (cfg.ackMode) {
                    AckMode.NONE -> Unit
                    AckMode.ALL -> scheduleAck(queue, rng, e, 0, always = true)
                    AckMode.RANDOM -> scheduleAck(queue, rng, e, 500, always = false)
                }
            }
            if (e.ttl - 1 > 0) {
                val jitter = 50 + rng.nextInt(200)
                for (nb in neighbors[e.node]) {
                    if (rng.nextDouble() < cfg.dropP) continue
                    msgFrames++
                    queue.add(Evt(e.time + 100 + jitter, nb, false, e.msgId, e.srcId, e.dstId, e.ttl - 1))
                }
            }
        }

        val lastArrival = receivedMsgAt.values.maxOrNull()?.toDouble() ?: -1.0
        val allReached = (members - sender).all { receivedMsgAt.containsKey(it) }
        return Result(lastArrival, senderConfirmedAt.toDouble(), msgFrames, ackFrames, allReached)
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
}
