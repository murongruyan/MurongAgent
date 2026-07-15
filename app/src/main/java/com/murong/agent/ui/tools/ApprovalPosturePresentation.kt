package com.murong.agent.ui.tools

import com.murong.agent.ui.ApprovalPostureDetailNote
import com.murong.agent.ui.ApprovalPostureDetailRow
import com.murong.agent.ui.ApprovalRuntimePosturePresentation
import com.murong.agent.ui.buildApprovalPostureCopyPresentation
import com.murong.agent.ui.buildApprovalPostureScopeSummaryValue

internal data class ApprovalPostureOverviewPresentation(
    val sectionTitle: String,
    val headline: String,
    val supportText: String,
    val detailRows: List<ApprovalPostureDetailRow>,
    val secondaryNotes: List<ApprovalPostureDetailNote>
)

internal fun buildApprovalPostureOverviewPresentation(
    runtimePosturePresentation: ApprovalRuntimePosturePresentation
): ApprovalPostureOverviewPresentation {
    val copy = buildApprovalPostureCopyPresentation(
        runtimeFocusLabel = runtimePosturePresentation.secondary.runtimeFocus?.value,
        pendingSummary = runtimePosturePresentation.secondary.pendingSummary?.text
    )
    return ApprovalPostureOverviewPresentation(
        sectionTitle = copy.sectionTitle,
        headline = runtimePosturePresentation.postureHost.headline,
        supportText = runtimePosturePresentation.postureHost.supportText,
        detailRows = listOf(
            ApprovalPostureDetailRow(copy.approvalModeLabel, runtimePosturePresentation.label),
            ApprovalPostureDetailRow(copy.currentStatusLabel, runtimePosturePresentation.postureHost.currentStatus),
            ApprovalPostureDetailRow(copy.runtimeFocusLabel, copy.runtimeFocusValue),
            ApprovalPostureDetailRow(
                copy.scopeSummaryLabel,
                buildApprovalPostureScopeSummaryValue(runtimePosturePresentation.secondary.scopeSummary)
            )
        ),
        secondaryNotes = buildList {
            copy.pendingSummaryText?.let { pendingSummaryText ->
                add(
                    ApprovalPostureDetailNote(
                        text = pendingSummaryText,
                        emphasized = runtimePosturePresentation.secondary.pendingSummary?.emphasized == true
                    )
                )
            }
            runtimePosturePresentation.secondary.runtimeFocus?.let { runtimeFocus ->
                add(ApprovalPostureDetailNote(text = runtimeFocus.summary))
            }
        }
    )
}
