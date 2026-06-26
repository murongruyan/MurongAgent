package com.murong.agent.ui

import com.murong.agent.core.config.ProviderConfig
import com.murong.agent.core.config.ToolApprovalMode
import com.murong.agent.core.loop.ApprovalPosturePendingSummaryTelemetry
import com.murong.agent.core.loop.ApprovalPostureTelemetry
import com.murong.agent.core.loop.ApprovalRuntimeTelemetry
import com.murong.agent.core.loop.ApprovalScopeTelemetrySummary
import com.murong.agent.core.loop.BackgroundActivityFocusTelemetry
import com.murong.agent.core.loop.RuntimeStatusKind
import com.murong.agent.core.loop.RuntimeStatusSnapshot

internal data class ApprovalPostureRuntimeFocusPresentation(
    val value: String,
    val summary: String
)

internal data class ApprovalPosturePendingSummaryPresentation(
    val text: String,
    val emphasized: Boolean
)

internal data class ApprovalPostureSecondaryPresentation(
    val pendingSummary: ApprovalPosturePendingSummaryPresentation?,
    val scopeSummary: ApprovalScopeTelemetrySummary,
    val runtimeFocus: ApprovalPostureRuntimeFocusPresentation? = null
)

internal data class ApprovalRuntimePosturePresentation(
    val approvalMode: ApprovalModeHostPresentation,
    val postureHost: ApprovalPostureHostPresentation,
    val secondary: ApprovalPostureSecondaryPresentation
) {
    val label: String
        get() = approvalMode.label

    val message: String
        get() = approvalMode.message

    val shortcutLabel: String
        get() = approvalMode.shortcutLabel
}

internal fun buildApprovalRuntimePosturePresentation(
    config: ProviderConfig,
    approvalRuntimeTelemetry: ApprovalRuntimeTelemetry,
    runtimeStatusSnapshot: RuntimeStatusSnapshot?
): ApprovalRuntimePosturePresentation {
    return buildApprovalRuntimePosturePresentation(
        globalMode = config.approvalMode,
        overrideMode = config.projectToolPreferences?.approvalMode,
        postureTelemetry = approvalRuntimeTelemetry.postureTelemetry,
        runtimeStatusSnapshot = runtimeStatusSnapshot
    )
}

internal fun buildApprovalRuntimePosturePresentation(
    globalMode: ToolApprovalMode,
    overrideMode: ToolApprovalMode?,
    postureTelemetry: ApprovalPostureTelemetry,
    runtimeStatusSnapshot: RuntimeStatusSnapshot?
): ApprovalRuntimePosturePresentation {
    val approvalMode = buildApprovalModeHostPresentation(
        globalMode = globalMode,
        overrideMode = overrideMode
    )
    return ApprovalRuntimePosturePresentation(
        approvalMode = approvalMode,
        postureHost = buildApprovalPostureHostPresentation(
            approvalMode = approvalMode,
            postureTelemetry = postureTelemetry
        ),
        secondary = buildApprovalPostureSecondaryPresentation(
            postureTelemetry = postureTelemetry,
            runtimeStatusSnapshot = runtimeStatusSnapshot,
            approvalMode = approvalMode
        )
    )
}

internal fun buildApprovalPostureSecondaryPresentation(
    postureTelemetry: ApprovalPostureTelemetry,
    runtimeStatusSnapshot: RuntimeStatusSnapshot?,
    approvalMode: ApprovalModeHostPresentation
): ApprovalPostureSecondaryPresentation {
    val runtimeFocusPresentation = buildBackgroundRuntimeFocusPresentation(
        snapshot = runtimeStatusSnapshot,
        approvalMode = approvalMode
    )
    return ApprovalPostureSecondaryPresentation(
        pendingSummary = postureTelemetry.pendingSummary?.toApprovalPosturePendingSummaryPresentation(),
        scopeSummary = postureTelemetry.scopeSummary,
        runtimeFocus = runtimeFocusPresentation?.let { presentation ->
            ApprovalPostureRuntimeFocusPresentation(
                value = presentation.label,
                summary = presentation.summary
            )
        }
    )
}

private data class BackgroundRuntimeFocusPresentation(
    val label: String,
    val summary: String
)

private fun buildBackgroundRuntimeFocusPresentation(
    snapshot: RuntimeStatusSnapshot?,
    approvalMode: ApprovalModeHostPresentation
): BackgroundRuntimeFocusPresentation? {
    if (snapshot?.kind != RuntimeStatusKind.BACKGROUND_ACTIVITY) return null
    val focusTelemetry = snapshot.backgroundActivityFocusTelemetry
        ?: buildLegacyBackgroundRuntimeFocusTelemetry(snapshot)
    val postureSummary = buildRuntimeApprovalPostureMessage(
        displayMode = snapshot.approvalPostureDisplayMode,
        postureLabel = approvalMode.label,
        fullMessage = approvalMode.message
    )
    val summary = buildString {
        append(focusTelemetry.summary)
        postureSummary?.takeIf { it.isNotBlank() }?.let { posture ->
            if (isNotBlank()) {
                append('\n')
            }
            append(posture)
        }
    }
    return BackgroundRuntimeFocusPresentation(label = focusTelemetry.label, summary = summary)
}

private fun ApprovalPosturePendingSummaryTelemetry.toApprovalPosturePendingSummaryPresentation(): ApprovalPosturePendingSummaryPresentation {
    return ApprovalPosturePendingSummaryPresentation(
        text = parts.joinToString(" · ") { sanitizeForUiDisplay(it) },
        emphasized = emphasized
    )
}

private fun buildLegacyBackgroundRuntimeFocusTelemetry(
    snapshot: RuntimeStatusSnapshot
): BackgroundActivityFocusTelemetry {
    val label = backgroundActivityFocusLabel(
        focusKind = snapshot.backgroundActivityFocusKind,
        fallbackTitle = snapshot.title
    ) ?: snapshot.title
    return BackgroundActivityFocusTelemetry(
        label = label,
        summary = snapshot.message
    )
}
