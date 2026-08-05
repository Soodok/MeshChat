package com.meshchat.app.mesh.debug

import org.junit.Assert.assertEquals
import org.junit.Test

class DebugStatsTest {

    /** 可推进的虚拟时钟：测试速率窗口/清理时无需真实等待。 */
    private class FakeClock(var now: Long = 0L) {
        val tick: () -> Long = { now }
    }

    @Test
    fun `records sent and received counts with bytes`() {
        val clock = FakeClock()
        val stats = DebugStats(clock.tick)
        stats.recordSent(FrameKind.PING, 100)
        stats.recordSent(FrameKind.PING, 50)
        stats.recordReceived(FrameKind.TEXT, 200)
        val snap = stats.snapshot(5_000)
        assertEquals(2, snap.frames.getValue(FrameKind.PING).sent)
        assertEquals(150, snap.frames.getValue(FrameKind.PING).sentBytes)
        assertEquals(1, snap.frames.getValue(FrameKind.TEXT).received)
        assertEquals(200, snap.frames.getValue(FrameKind.TEXT).receivedBytes)
    }

    @Test
    fun `rate only counts events inside the window`() {
        val clock = FakeClock()
        val stats = DebugStats(clock.tick)
        stats.recordSent(FrameKind.PING, 10)   // t=0
        clock.now = 3_000
        stats.recordSent(FrameKind.PING, 10)   // t=3000
        clock.now = 6_000
        val snap5s = stats.snapshot(5_000)     // 窗口 [1000, 6000]：仅 3000 事件
        // sent 为累计数（2 次），窗口内数量体现在 rate
        assertEquals(2, snap5s.frames.getValue(FrameKind.PING).sent)
        assertEquals(1.0 / 5.0, snap5s.frames.getValue(FrameKind.PING).sentRatePerSec, 1e-9)
        // 窗口拉长到 10s 涵盖两事件
        val snap10s = stats.snapshot(10_000)
        assertEquals(2, snap10s.frames.getValue(FrameKind.PING).sent)
        assertEquals(2.0 / 10.0, snap10s.frames.getValue(FrameKind.PING).sentRatePerSec, 1e-9)
    }

    @Test
    fun `reset clears counts and queues`() {
        val clock = FakeClock()
        val stats = DebugStats(clock.tick)
        stats.recordSent(FrameKind.TEXT, 10)
        stats.recordBleBroadcast(30)
        stats.recordGattWrite(true)
        stats.recordResend("m1")
        stats.reset()
        val snap = stats.snapshot(5_000)
        assertEquals(0, snap.frames.getValue(FrameKind.TEXT).sent)
        assertEquals(0, snap.ble.broadcastCount)
        assertEquals(0, snap.ble.writeSuccess)
        assertEquals(0, snap.delivery.resends)
        assertEquals(0, snap.delivery.confirmed)
    }

    @Test
    fun `delivery resend histogram buckets 4 as four-plus`() {
        val clock = FakeClock()
        val stats = DebugStats(clock.tick)
        stats.recordResend("a"); stats.recordResend("a") // 2x
        stats.recordResend("b"); stats.recordResend("b"); stats.recordResend("b"); stats.recordResend("b"); stats.recordResend("b") // 5x → 4+
        val snap = stats.snapshot(5_000)
        assertEquals(2L, snap.delivery.resendHistogram[2])  // a 第2次 + b 第2次
        assertEquals(2L, snap.delivery.resendHistogram[4])  // b 第4、5次 → 4+ 桶
        stats.recordConfirmed("a")
        val after = stats.snapshot(5_000)
        assertEquals(1, after.delivery.confirmed)
        assertEquals(1.0, after.delivery.confirmationRate, 1e-9) // 1 confirmed / (1+0 pending)
    }

    @Test
    fun `ble and route counters aggregate`() {
        val clock = FakeClock()
        val stats = DebugStats(clock.tick)
        stats.recordBleBroadcast(40); stats.recordBleBroadcast(60)
        stats.recordScanResult(); stats.recordScanResult(); stats.recordScanResult()
        stats.recordGattConnectAttempt(); stats.recordGattConnectSuccess()
        stats.recordMtu(512)
        stats.recordRoute(RouteDecision.FORWARD)
        stats.recordRelayed()
        val snap = stats.snapshot(5_000)
        assertEquals(2, snap.ble.broadcastCount)
        assertEquals(100, snap.ble.broadcastBytes)
        assertEquals(3, snap.ble.scanResultCount)
        assertEquals(1, snap.ble.gattConnectAttempts)
        assertEquals(1, snap.ble.gattConnectSuccess)
        assertEquals(1, snap.ble.gattCurrent)
        assertEquals(512, snap.ble.mtu)
        assertEquals(1, snap.delivery.forwardCount)
        assertEquals(1, snap.delivery.relayedFrames)
    }

    @Test
    fun `received decode failures aggregate in failed stats`() {
        val clock = FakeClock()
        val stats = DebugStats(clock.tick)
        stats.recordReceivedFailure()
        clock.now = 500
        stats.recordReceivedFailure()
        stats.recordGattWrite(false)
        stats.recordNotify(false)
        val snap = stats.snapshot(5_000)
        assertEquals(2, snap.failures.receivedDecodeFailures)
        assertEquals(2.0 / 5.0, snap.failures.receivedDecodeRatePerSec, 1e-9)
        assertEquals(1, snap.failures.bleWriteFailed)
        assertEquals(1, snap.failures.bleNotifyFailed)
        stats.reset()
        val after = stats.snapshot(5_000)
        assertEquals(0, after.failures.receivedDecodeFailures)
    }

    @Test
    fun `issue forwards control commands to attached handler`() {
        val stats = DebugStats()
        var received: DebugControl? = null
        stats.attachControls { received = it }
        stats.issue(DebugControl.SetHeartbeat(500))
        assertEquals(DebugControl.SetHeartbeat(500), received)
        // 未注册 handler 时静默不抛（测试/未装配场景）
        DebugStats().issue(DebugControl.ResetControls)
    }

    @Test
    fun `receive success rate uses sliding window not cumulative`() {
        val clock = FakeClock()
        val stats = DebugStats(clock.tick)
        assertEquals(-1.0, stats.receiveSuccessRate(), 1e-9)   // 无样本
        stats.recordReceived(FrameKind.PING, 10)
        stats.recordReceived(FrameKind.PING, 10)
        assertEquals(1.0, stats.receiveSuccessRate(), 1e-9)    // 窗口内只收无失败 → 100%
        stats.recordReceivedFailure()
        stats.recordGattWrite(false)
        assertEquals(2.0 / 4.0, stats.receiveSuccessRate(), 1e-9)  // 2 收 + 2 失败（解码+写）
        // 窗口滑动：超过 5s 后事件全部过期 → 无样本（不累计历史）
        clock.now = 6_000
        assertEquals(-1.0, stats.receiveSuccessRate(), 1e-9)
        // 新窗口重新统计
        stats.recordReceived(FrameKind.PING, 10)
        assertEquals(1.0, stats.receiveSuccessRate(), 1e-9)
        stats.reset()
        assertEquals(-1.0, stats.receiveSuccessRate(), 1e-9)   // 清零后无样本
    }
}
