package com.meshchat.app.mesh.quality

/**
 * 蓝牙链路质量等级评分模块。
 *
 * 依据测得的 RSSI（dBm）与设备蓝牙能力因子评估链路质量，产出等级与 0-100 分数。
 * 本模块独立于传输/路由层，供后续功能（信号可视化、路由优选、可靠性调度）复用。
 */
enum class QualityGrade(val label: String) {
    EXCELLENT("S"),
    GOOD("A"),
    FAIR("B"),
    WEAK("C"),
    POOR("D"),
}

object BluetoothQuality {

    /**
     * 基于 RSSI 评定链路等级。
     *
     * @param rssi 测得信号强度（dBm，负值，越大越强）
     * @param deviceFactor 设备蓝牙能力因子（0..1，默认 1.0）。
     *        弱蓝牙功能设备（如广播 TX 功率低）可传入 <1 的因子修正评分，
     *        使同一距离下能力弱的设备获得保守评级。
     */
    fun grade(rssi: Int, deviceFactor: Float = 1f): QualityGrade {
        val adjusted = rssi * deviceFactor.coerceIn(0.5f, 1.5f)
        return when {
            adjusted >= -55 -> QualityGrade.EXCELLENT
            adjusted >= -65 -> QualityGrade.GOOD
            adjusted >= -75 -> QualityGrade.FAIR
            adjusted >= -85 -> QualityGrade.WEAK
            else -> QualityGrade.POOR
        }
    }

    /** 链路质量分（0-100）：RSSI >= -40 计 100 分，<= -100 计 0 分，线性插值。 */
    fun score(rssi: Int): Int {
        val raw = ((rssi + 100).toFloat() / 60f * 100f).toInt()
        return raw.coerceIn(0, 100)
    }

    /** 信号强度条数（0-3），与前端 SignalBars 对齐。 */
    fun bars(rssi: Int): Int = when {
        rssi >= -60 -> 3
        rssi >= -75 -> 2
        rssi >= -90 -> 1
        else -> 0
    }
}
