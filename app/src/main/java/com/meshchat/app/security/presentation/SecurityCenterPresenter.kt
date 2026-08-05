package com.meshchat.app.security.presentation

import com.meshchat.app.security.capability.SecurityCapabilityStatus
import com.meshchat.app.security.model.SecurityCapability
import com.meshchat.app.security.model.SecurityAssessment
import com.meshchat.app.security.model.SecurityLevel
import com.meshchat.app.security.model.SecurityRiskClassifier

data class SecurityCenterSummary(
    val level: SecurityLevel,
    val title: String,
    val detail: String,
)

/** Presentation-only mapping. Missing permissions mean reduced coverage, never a compromise claim. */
object SecurityCenterPresenter {
    /** Local-first dashboard wording. A normal result is deliberately limited to local checks. */
    fun summary(assessment: SecurityAssessment?): SecurityCenterSummary {
        if (assessment == null) {
            return SecurityCenterSummary(SecurityLevel.LIMITED, "正在进行本地检查", "检查只在本机执行；聊天和附近通信不会等待结果。")
        }
        if (assessment.score == 0 && assessment.protectionGaps.isNotEmpty()) {
            return SecurityCenterSummary(
                SecurityLevel.LIMITED,
                "本应用保护尚未完成",
                "这是产品保护缺口，不表示设备被控制或感染；请查看下方记录。",
            )
        }
        return when (assessment.level) {
            SecurityLevel.NORMAL -> SecurityCenterSummary(assessment.level, "本地检查未发现可验证风险", "这不是设备无风险保证；未配置的云端与 VPN 能力不会参与本地结论。")
            SecurityLevel.LIMITED -> SecurityCenterSummary(assessment.level, "本地检查覆盖受限", "部分本机状态无法读取，但聊天和附近通信不受影响。")
            SecurityLevel.SUSPICIOUS -> SecurityCenterSummary(assessment.level, "发现需复核的本地信号", "请查看下方记录；信号不代表设备感染、控制者或来源。")
            SecurityLevel.HIGH_RISK -> SecurityCenterSummary(assessment.level, "发现高风险本地信号", "建议暂停敏感操作并人工复核；基础聊天仍由你决定是否继续使用。")
        }
    }

    fun summary(statuses: Map<SecurityCapability, SecurityCapabilityStatus>): SecurityCenterSummary {
        val level = SecurityRiskClassifier().assess(
            signals = emptyList(),
            capabilities = statuses.mapValues { it.value.state },
            now = 0L,
        ).level
        return when (level) {
            SecurityLevel.NORMAL -> SecurityCenterSummary(level, "基础保护已启用", "当前没有可解释的风险信号。")
            SecurityLevel.LIMITED -> SecurityCenterSummary(level, "部分检测受限", "部分可选能力未启用或不可用；基础聊天不受影响。")
            SecurityLevel.SUSPICIOUS -> SecurityCenterSummary(level, "存在风险信号", "请查看事件详情并按建议处理。")
            SecurityLevel.HIGH_RISK -> SecurityCenterSummary(level, "高风险信号", "敏感操作应暂缓，建议进行人工复核。")
        }
    }
}
