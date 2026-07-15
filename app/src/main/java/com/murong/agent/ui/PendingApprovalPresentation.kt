package com.murong.agent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.murong.agent.core.loop.PendingApprovalHostTelemetry
import com.murong.agent.core.loop.PendingApprovalDetailTelemetry
import com.murong.agent.core.loop.PendingApprovalUi
import com.murong.agent.core.loop.PendingApprovalRowTelemetry
import com.murong.agent.core.loop.resolvePendingApprovalHostTelemetry
import com.murong.agent.core.loop.resolvePendingApprovalDetailTelemetry
import com.murong.agent.core.tool.ApprovalRiskLevel

internal data class PendingApprovalPresentation(
    val toolName: String = "",
    val headline: String,
    val supportText: String?,
    val rows: List<Pair<String, String>>,
    val rawArgsLabel: String,
    val approveLabel: String,
    val rejectLabel: String,
    val rawArgs: String = "",
    val approveEnabled: Boolean = false,
    val riskLevel: ApprovalRiskLevel? = null,
    val replayNotice: String? = null,
    val explanationLabel: String? = null,
    val explanationDetail: String? = null
)

@Composable
internal fun PendingApprovalSummaryCard(
    presentation: PendingApprovalPresentation,
    modifier: Modifier = Modifier
) {
    if (presentation.rows.isEmpty() && presentation.supportText.isNullOrBlank()) return
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            presentation.rows.forEach { (label, value) ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            presentation.supportText?.takeIf { it.isNotBlank() }?.let { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

internal fun PendingApprovalUi.toPendingApprovalPresentation(): PendingApprovalPresentation {
    return resolvePendingApprovalHostTelemetry(this).toPendingApprovalPresentation()
}

internal fun PendingApprovalHostTelemetry.toPendingApprovalPresentation(): PendingApprovalPresentation {
    return detailTelemetry.toPendingApprovalPresentation(
        toolName = toolName,
        rawArgs = rawArgs,
        approveEnabled = approveEnabled,
        riskLevel = riskLevel,
        replayNotice = replayNotice,
        explanationLabel = explanationLabel,
        explanationDetail = explanationDetail
    )
}

internal fun PendingApprovalDetailTelemetry.toPendingApprovalPresentation(): PendingApprovalPresentation {
    return toPendingApprovalPresentation(toolName = "")
}

private fun PendingApprovalDetailTelemetry.toPendingApprovalPresentation(
    toolName: String,
    rawArgs: String = "",
    approveEnabled: Boolean = false,
    riskLevel: ApprovalRiskLevel? = null,
    replayNotice: String? = null,
    explanationLabel: String? = null,
    explanationDetail: String? = null
): PendingApprovalPresentation {
    return PendingApprovalPresentation(
        toolName = sanitizeForUiDisplay(toolName),
        headline = sanitizeForUiDisplay(headline),
        supportText = supportText?.takeIf { it.isNotBlank() }?.let(::sanitizeForUiDisplay),
        rows = rows.map(PendingApprovalRowTelemetry::toUiRow),
        rawArgsLabel = sanitizeForUiDisplay(rawArgsLabel),
        approveLabel = sanitizeForUiDisplay(approveLabel),
        rejectLabel = sanitizeForUiDisplay(rejectLabel),
        rawArgs = sanitizeForUiDisplay(rawArgs),
        approveEnabled = approveEnabled,
        riskLevel = riskLevel,
        replayNotice = replayNotice?.let(::sanitizeForUiDisplay),
        explanationLabel = explanationLabel?.let(::sanitizeForUiDisplay),
        explanationDetail = explanationDetail?.let(::sanitizeForUiDisplay)
    )
}

private fun PendingApprovalRowTelemetry.toUiRow(): Pair<String, String> {
    return sanitizeForUiDisplay(label) to sanitizeForUiDisplay(value)
}
