package com.murong.agent.ui.chat

import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.murong.agent.core.loop.WorkflowPlanStatusUi
import com.murong.agent.ui.MurongDialog
import com.murong.agent.ui.MurongGlassSurface
import com.murong.agent.ui.MurongLargeDialogScaffold
import com.murong.agent.ui.MurongOutlinedActionButton
import com.murong.agent.ui.MurongPopupSurface
import com.murong.agent.ui.MurongTagButton
import com.murong.agent.ui.rememberMurongAccentColor
import com.murong.agent.ui.rememberMurongChromeColor
import com.murong.agent.ui.rememberMurongMutedTextColor
import com.murong.agent.ui.rememberMurongSurfaceColor

@Composable
internal fun WorkflowPlanDialog(
    presentation: WorkflowPlanPromptPresentation,
    interactionState: WorkflowPlanInteractionState? = null,
    onInteractionStateChange: ((WorkflowPlanInteractionState) -> Unit)? = null,
    isProcessing: Boolean,
    onFork: () -> Unit,
    onExecute: () -> Unit,
    onDismiss: () -> Unit
) {
    MurongDialog(onDismissRequest = onDismiss) {
        MurongPopupSurface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            WorkflowPlanPromptContainer(
                presentation = presentation,
                interactionState = interactionState,
                onInteractionStateChange = onInteractionStateChange,
                showDetailedHistory = true,
                isProcessing = isProcessing,
                onFork = onFork,
                onExecute = onExecute,
                onDismiss = onDismiss
            )
        }
    }
}

@Composable
internal fun WorkflowPlanPromptCard(
    presentation: WorkflowPlanPromptPresentation,
    interactionState: WorkflowPlanInteractionState? = null,
    onInteractionStateChange: ((WorkflowPlanInteractionState) -> Unit)? = null,
    isProcessing: Boolean,
    onFork: () -> Unit,
    onExecute: () -> Unit,
    onDismiss: () -> Unit
) {
    val chromeColor = rememberMurongChromeColor()
    MurongGlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        shape = MaterialTheme.shapes.large,
        contentPadding = PaddingValues(14.dp),
        surfaceColorOverride = chromeColor.copy(alpha = 0.72f)
    ) {
        WorkflowPlanPromptContent(
            presentation = presentation,
            interactionState = interactionState,
            onInteractionStateChange = onInteractionStateChange,
            showDetailedHistory = false,
            isProcessing = isProcessing,
            onFork = onFork,
            onExecute = onExecute,
            onDismiss = onDismiss
        )
    }
}

@Composable
private fun WorkflowPlanPromptContainer(
    presentation: WorkflowPlanPromptPresentation,
    interactionState: WorkflowPlanInteractionState? = null,
    onInteractionStateChange: ((WorkflowPlanInteractionState) -> Unit)? = null,
    showDetailedHistory: Boolean,
    isProcessing: Boolean,
    onFork: () -> Unit,
    onExecute: () -> Unit,
    onDismiss: () -> Unit
) {
    MurongLargeDialogScaffold(onDismissRequest = onDismiss) {
        MurongGlassSurface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = MaterialTheme.shapes.large,
            contentPadding = PaddingValues(16.dp)
        ) {
            WorkflowPlanPromptContent(
                presentation = presentation,
                interactionState = interactionState,
                onInteractionStateChange = onInteractionStateChange,
                showDetailedHistory = showDetailedHistory,
                isProcessing = isProcessing,
                onFork = onFork,
                onExecute = onExecute,
                onDismiss = onDismiss
            )
        }
    }
}

@Composable
private fun WorkflowPlanPromptContent(
    presentation: WorkflowPlanPromptPresentation,
    interactionState: WorkflowPlanInteractionState? = null,
    onInteractionStateChange: ((WorkflowPlanInteractionState) -> Unit)? = null,
    showDetailedHistory: Boolean,
    isProcessing: Boolean,
    onFork: () -> Unit,
    onExecute: () -> Unit,
    onDismiss: () -> Unit
) {
    var localInteractionState by remember(presentation.requestId) {
        mutableStateOf(buildInitialWorkflowPlanInteractionState())
    }
    val currentInteractionState = interactionState ?: localInteractionState
    val updateInteractionState: (WorkflowPlanInteractionState) -> Unit = remember(
        onInteractionStateChange
    ) {
        { nextState ->
            if (onInteractionStateChange != null) {
                onInteractionStateChange(nextState)
            } else {
                localInteractionState = nextState
            }
        }
    }
    val sessionPresentation = remember(presentation, currentInteractionState, isProcessing) {
        buildWorkflowPlanSessionPresentation(
            presentation = presentation,
            interactionState = currentInteractionState,
            isProcessing = isProcessing
        )
    }
    val accent = rememberMurongAccentColor()
    val surfaceColor = rememberMurongSurfaceColor()
    val mutedTextColor = rememberMurongMutedTextColor()

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = presentation.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = presentation.goal,
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedTextColor,
                    maxLines = 2
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            WorkflowPlanStatusBadge(status = presentation.status)
        }
        if (presentation.stageChips.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = presentation.stageSectionTitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    presentation.stageChips.forEach { stage ->
                        WorkflowPlanStageChip(
                            label = stage.label,
                            isCurrent = stage.isCurrent,
                            isCompleted = stage.isCompleted
                        )
                    }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = presentation.progressSectionTitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = presentation.progressLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = mutedTextColor
                )
            }
            LinearProgressIndicator(
                progress = { presentation.progressFraction.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth()
            )
        }
        Text(
            text = presentation.summary,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        presentation.recentHistoryClue?.let { historyClue ->
            RecentHistoryClueSurface(
                historyClue = historyClue,
                accent = accent,
                mutedTextColor = mutedTextColor,
                surfaceColor = surfaceColor,
                showDetailedRows = showDetailedHistory
            )
        }
        MurongGlassSurface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            surfaceColorOverride = surfaceColor.copy(alpha = 0.70f)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = presentation.nextStepTitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = accent
                )
                Text(
                    text = presentation.nextStepHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            presentation.stepRows.forEach { step ->
                WorkflowPlanStepRow(
                    badgeLabel = step.badgeLabel,
                    step = step.step,
                    status = step.status
                )
            }
        }
        if (presentation.mentionedFiles.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = presentation.mentionedFilesSectionTitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    presentation.mentionedFiles.forEach { displayPath ->
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = surfaceColor.copy(alpha = 0.68f)
                        ) {
                            Text(
                                text = displayPath,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }
        }
        sessionPresentation.rawPlanToggleLabel?.let { rawPlanToggleLabel ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                MurongTagButton(
                    text = rawPlanToggleLabel,
                    onClick = {
                        updateInteractionState(
                            toggleWorkflowPlanRawPlan(
                                state = currentInteractionState,
                                presentation = presentation
                            )
                        )
                    }
                )
                AnimatedVisibility(visible = sessionPresentation.showRawPlan) {
                    MurongGlassSurface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        contentPadding = PaddingValues(12.dp),
                        surfaceColorOverride = surfaceColor.copy(alpha = 0.68f)
                    ) {
                        NativeSelectableScrollableText(
                            text = sessionPresentation.rawPlanContent.orEmpty(),
                            modifier = Modifier.fillMaxWidth(),
                            monospace = true,
                            fontSizeSp = 12f,
                            maxHeight = 320.dp
                        )
                    }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = sessionPresentation.status.summaryLabel,
                style = MaterialTheme.typography.labelMedium,
                color = accent
            )
            Text(
                text = sessionPresentation.status.guidance,
                style = MaterialTheme.typography.bodySmall,
                color = mutedTextColor
            )
            sessionPresentation.status.disabledHint?.let { disabledHint ->
                Text(
                    text = disabledHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedTextColor
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MurongOutlinedActionButton(
                text = sessionPresentation.dismissLabel,
                onClick = onDismiss,
                enabled = !isProcessing,
                modifier = Modifier.weight(1f)
            )
            MurongOutlinedActionButton(
                text = "分叉会话",
                onClick = onFork,
                enabled = !isProcessing,
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = onExecute,
                enabled = sessionPresentation.executeAction.enabled,
                modifier = Modifier.weight(1f)
            ) {
                Text(sessionPresentation.executeAction.label)
            }
        }
    }
}

@Composable
internal fun ClarificationDialog(
    presentation: ClarificationPromptPresentation,
    interactionState: ClarificationInteractionState? = null,
    onInteractionStateChange: ((ClarificationInteractionState) -> Unit)? = null,
    isProcessing: Boolean,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit
) {
    MurongDialog(onDismissRequest = onDismiss) {
        MurongPopupSurface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            ClarificationPromptContainer(
                presentation = presentation,
                interactionState = interactionState,
                onInteractionStateChange = onInteractionStateChange,
                showDetailedHistory = true,
                isProcessing = isProcessing,
                onSubmit = onSubmit,
                onDismiss = onDismiss
            )
        }
    }
}

@Composable
internal fun ClarificationPromptCard(
    presentation: ClarificationPromptPresentation,
    interactionState: ClarificationInteractionState? = null,
    onInteractionStateChange: ((ClarificationInteractionState) -> Unit)? = null,
    isProcessing: Boolean,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val chromeColor = rememberMurongChromeColor()
    MurongGlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        shape = MaterialTheme.shapes.large,
        contentPadding = PaddingValues(14.dp),
        surfaceColorOverride = chromeColor.copy(alpha = 0.70f)
    ) {
        ClarificationPromptContent(
            presentation = presentation,
            interactionState = interactionState,
            onInteractionStateChange = onInteractionStateChange,
            showDetailedHistory = false,
            isProcessing = isProcessing,
            onSubmit = onSubmit,
            onDismiss = onDismiss
        )
    }
}

@Composable
private fun ClarificationPromptContainer(
    presentation: ClarificationPromptPresentation,
    interactionState: ClarificationInteractionState? = null,
    onInteractionStateChange: ((ClarificationInteractionState) -> Unit)? = null,
    showDetailedHistory: Boolean,
    isProcessing: Boolean,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit
) {
    MurongLargeDialogScaffold(onDismissRequest = onDismiss) {
        MurongGlassSurface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = MaterialTheme.shapes.large,
            contentPadding = PaddingValues(16.dp)
        ) {
            ClarificationPromptContent(
                presentation = presentation,
                interactionState = interactionState,
                onInteractionStateChange = onInteractionStateChange,
                showDetailedHistory = showDetailedHistory,
                isProcessing = isProcessing,
                onSubmit = onSubmit,
                onDismiss = onDismiss
            )
        }
    }
}

@Composable
private fun ClarificationPromptContent(
    presentation: ClarificationPromptPresentation,
    interactionState: ClarificationInteractionState? = null,
    onInteractionStateChange: ((ClarificationInteractionState) -> Unit)? = null,
    showDetailedHistory: Boolean,
    isProcessing: Boolean,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var localInteractionState by remember(presentation.requestId) {
        mutableStateOf(buildInitialClarificationInteractionState())
    }
    val currentInteractionState = interactionState ?: localInteractionState
    val updateInteractionState: (ClarificationInteractionState) -> Unit = remember(
        onInteractionStateChange
    ) {
        { nextState ->
            if (onInteractionStateChange != null) {
                onInteractionStateChange(nextState)
            } else {
                localInteractionState = nextState
            }
        }
    }
    val sessionPresentation = remember(presentation, currentInteractionState, isProcessing) {
        buildClarificationSessionPresentation(
            presentation = presentation,
            interactionState = currentInteractionState,
            isProcessing = isProcessing
        )
    }
    val accent = rememberMurongAccentColor()
    val surfaceColor = rememberMurongSurfaceColor()
    val mutedTextColor = rememberMurongMutedTextColor()

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = presentation.title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        presentation.subtitle?.let { subtitle ->
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = mutedTextColor
            )
        }
        Text(
            text = sessionPresentation.status.summaryLabel,
            style = MaterialTheme.typography.labelMedium,
            color = accent
        )
        presentation.previousAnswersSummary?.let { previousAnswersSummary ->
            MurongGlassSurface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                surfaceColorOverride = surfaceColor.copy(alpha = 0.70f)
            ) {
                Text(
                    text = previousAnswersSummary,
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedTextColor
                )
            }
        }
        presentation.recentHistoryClue?.let { historyClue ->
            RecentHistoryClueSurface(
                historyClue = historyClue,
                accent = accent,
                mutedTextColor = mutedTextColor,
                surfaceColor = surfaceColor,
                showDetailedRows = showDetailedHistory
            )
        }
        Text(
            text = presentation.question,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        OutlinedTextField(
            value = sessionPresentation.draftAnswer,
            onValueChange = { answer ->
                updateInteractionState(
                    updateClarificationAnswer(
                        state = currentInteractionState,
                        answer = answer
                    )
                )
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(presentation.inputPlaceholder) },
            enabled = !isProcessing,
            maxLines = 4
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = sessionPresentation.status.guidance,
                style = MaterialTheme.typography.bodySmall,
                color = mutedTextColor
            )
            sessionPresentation.status.disabledHint?.let { disabledHint ->
                Text(
                    text = disabledHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedTextColor
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MurongOutlinedActionButton(
                text = sessionPresentation.dismissLabel,
                onClick = onDismiss,
                enabled = !isProcessing,
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = {
                    consumeClarificationSubmitAnswer(sessionPresentation)?.let(onSubmit)
                },
                enabled = sessionPresentation.submitAction.enabled,
                modifier = Modifier.weight(1f)
            ) {
                Text(sessionPresentation.submitAction.label)
            }
        }
    }
}

@Composable
private fun RecentHistoryClueSurface(
    historyClue: RecentHistoryCluePresentation,
    accent: Color,
    mutedTextColor: Color,
    surfaceColor: Color,
    showDetailedRows: Boolean
) {
    MurongGlassSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        surfaceColorOverride = surfaceColor.copy(alpha = 0.70f)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = historyClue.title,
                style = MaterialTheme.typography.labelMedium,
                color = accent
            )
            Text(
                text = historyClue.summary,
                style = MaterialTheme.typography.bodySmall,
                color = mutedTextColor
            )
            if (showDetailedRows && historyClue.detailRows.isNotEmpty()) {
                Column(
                    modifier = Modifier.padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    historyClue.detailRows.forEach { row ->
                        Text(
                            text = "${row.label}: ${row.value}",
                            style = MaterialTheme.typography.bodySmall,
                            color = mutedTextColor
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkflowPlanStatusBadge(status: WorkflowPlanStatusUi) {
    val accent = rememberMurongAccentColor()
    val chromeColor = rememberMurongChromeColor()
    val containerColor = when (status) {
        WorkflowPlanStatusUi.READY -> accent.copy(alpha = 0.18f)
        WorkflowPlanStatusUi.EXECUTING -> chromeColor.copy(alpha = 0.72f)
        WorkflowPlanStatusUi.BLOCKED -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.18f)
        WorkflowPlanStatusUi.COMPLETED -> accent.copy(alpha = 0.14f)
    }
    val contentColor = when (status) {
        WorkflowPlanStatusUi.READY -> MaterialTheme.colorScheme.primary
        WorkflowPlanStatusUi.EXECUTING -> MaterialTheme.colorScheme.secondary
        WorkflowPlanStatusUi.BLOCKED -> MaterialTheme.colorScheme.tertiary
        WorkflowPlanStatusUi.COMPLETED -> accent
    }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = containerColor
    ) {
        Text(
            text = workflowPlanStatusText(status),
            style = MaterialTheme.typography.labelMedium,
            color = contentColor,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun WorkflowPlanStageChip(
    label: String,
    isCurrent: Boolean,
    isCompleted: Boolean
) {
    val accent = rememberMurongAccentColor()
    val surfaceColor = rememberMurongSurfaceColor()
    val mutedTextColor = rememberMurongMutedTextColor()
    val containerColor = when {
        isCurrent -> accent.copy(alpha = 0.18f)
        isCompleted -> surfaceColor.copy(alpha = 0.72f)
        else -> surfaceColor.copy(alpha = 0.48f)
    }
    val contentColor = when {
        isCurrent -> MaterialTheme.colorScheme.primary
        isCompleted -> MaterialTheme.colorScheme.secondary
        else -> mutedTextColor
    }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = containerColor
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = contentColor,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun WorkflowPlanStepRow(
    badgeLabel: String,
    step: String,
    status: WorkflowPlanStepPresentationStatus
) {
    val surfaceColor = rememberMurongSurfaceColor()
    val mutedTextColor = rememberMurongMutedTextColor()
    val badgeColor = when (status) {
        WorkflowPlanStepPresentationStatus.COMPLETED -> Color(0xFF2E7D32)
        WorkflowPlanStepPresentationStatus.CURRENT -> MaterialTheme.colorScheme.primary
        WorkflowPlanStepPresentationStatus.PENDING -> mutedTextColor
    }
    val containerColor = when (status) {
        WorkflowPlanStepPresentationStatus.COMPLETED -> Color(0x142E7D32)
        WorkflowPlanStepPresentationStatus.CURRENT -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f)
        WorkflowPlanStepPresentationStatus.PENDING -> surfaceColor.copy(alpha = 0.46f)
    }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = containerColor,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top
        ) {
            Text(
                text = badgeLabel,
                style = MaterialTheme.typography.labelSmall,
                color = badgeColor
            )
            Text(
                text = step,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun NativeSelectableScrollableText(
    text: String,
    modifier: Modifier = Modifier,
    monospace: Boolean = false,
    fontSizeSp: Float = 12f,
    maxHeight: androidx.compose.ui.unit.Dp = 320.dp
) {
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    AndroidView(
        modifier = modifier.heightIn(max = maxHeight),
        factory = { context ->
            ScrollView(context).apply {
                overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                isVerticalScrollBarEnabled = true
                clipToPadding = false
                addView(
                    TextView(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                        setTextIsSelectable(true)
                        setPadding(0, 0, 0, 0)
                        includeFontPadding = false
                        setLineSpacing(0f, 1.15f)
                        textSize = fontSizeSp
                        if (monospace) {
                            typeface = android.graphics.Typeface.MONOSPACE
                        }
                    }
                )
                setOnTouchListener { view, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN,
                        MotionEvent.ACTION_MOVE -> view.parent?.requestDisallowInterceptTouchEvent(true)

                        MotionEvent.ACTION_UP,
                        MotionEvent.ACTION_CANCEL -> view.parent?.requestDisallowInterceptTouchEvent(false)
                    }
                    false
                }
            }
        },
        update = { scrollView ->
            val textView = scrollView.getChildAt(0) as TextView
            textView.text = text
            textView.setTextColor(textColor)
            textView.textSize = fontSizeSp
            textView.typeface = if (monospace) {
                android.graphics.Typeface.MONOSPACE
            } else {
                android.graphics.Typeface.DEFAULT
            }
        }
    )
}
