package com.murong.agent.core.config

enum class ApprovalPosture {
    ASK,
    AUTO,
    YOLO
}

enum class ApprovalModeSource {
    FOLLOW_GLOBAL,
    PROJECT_OVERRIDE
}

data class ApprovalModePresentation(
    val posture: ApprovalPosture,
    val label: String,
    val description: String
)

data class ResolvedApprovalModePresentation(
    val effectiveMode: ToolApprovalMode,
    val overrideMode: ToolApprovalMode?,
    val source: ApprovalModeSource,
    val modePresentation: ApprovalModePresentation,
    val sourceLabel: String,
    val labelWithSource: String,
    val runtimeMessage: String,
    val shortcutLabel: String
)

fun ToolApprovalMode.toApprovalModePresentation(): ApprovalModePresentation = when (this) {
    ToolApprovalMode.READ_ONLY -> ApprovalModePresentation(
        posture = ApprovalPosture.ASK,
        label = "Ask（只读）",
        description = "当前姿态相当于 ask，并额外限制为只读自动放行；写入和执行类操作会被明显收紧。"
    )
    ToolApprovalMode.ALL_APPROVAL -> ApprovalModePresentation(
        posture = ApprovalPosture.ASK,
        label = "Ask（全审批）",
        description = "当前姿态相当于 ask，所有工具调用都需要你显式确认后才会继续。"
    )
    ToolApprovalMode.WHITELIST_AUTO -> ApprovalModePresentation(
        posture = ApprovalPosture.AUTO,
        label = "Auto（白名单）",
        description = "当前姿态相当于 auto，命中白名单的操作可自动通过；关键配置和交互工具仍需 fresh approval。"
    )
    ToolApprovalMode.ALL_AUTO -> ApprovalModePresentation(
        posture = ApprovalPosture.YOLO,
        label = "Yolo（全自动）",
        description = "当前姿态相当于 yolo，大多数操作默认直接通过；关键配置和交互工具仍需 fresh approval。"
    )
}

fun ToolApprovalMode.approvalModeLabel(): String = toApprovalModePresentation().label

fun ToolApprovalMode.approvalModeDescription(): String = toApprovalModePresentation().description

fun resolveApprovalModePresentation(
    globalMode: ToolApprovalMode,
    overrideMode: ToolApprovalMode?
): ResolvedApprovalModePresentation {
    val effectiveMode = overrideMode ?: globalMode
    val modePresentation = effectiveMode.toApprovalModePresentation()
    val source = if (overrideMode == null) {
        ApprovalModeSource.FOLLOW_GLOBAL
    } else {
        ApprovalModeSource.PROJECT_OVERRIDE
    }
    val sourceLabel = when (source) {
        ApprovalModeSource.FOLLOW_GLOBAL -> "跟随全局"
        ApprovalModeSource.PROJECT_OVERRIDE -> "项目覆盖"
    }
    val labelWithSource = "${modePresentation.label}（$sourceLabel）"
    return ResolvedApprovalModePresentation(
        effectiveMode = effectiveMode,
        overrideMode = overrideMode,
        source = source,
        modePresentation = modePresentation,
        sourceLabel = sourceLabel,
        labelWithSource = labelWithSource,
        runtimeMessage = "当前审批姿态：$labelWithSource。${modePresentation.description}",
        shortcutLabel = when (source) {
            ApprovalModeSource.FOLLOW_GLOBAL -> "审批: 跟随全局 (${modePresentation.label})"
            ApprovalModeSource.PROJECT_OVERRIDE -> "审批: ${modePresentation.label}"
        }
    )
}
