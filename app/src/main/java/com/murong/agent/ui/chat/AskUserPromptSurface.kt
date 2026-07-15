package com.murong.agent.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.murong.agent.core.loop.AskAnswerUi
import com.murong.agent.ui.MurongDialog
import com.murong.agent.ui.MurongGlassSurface
import com.murong.agent.ui.MurongLargeDialogScaffold
import com.murong.agent.ui.MurongOutlinedActionButton
import com.murong.agent.ui.MurongPopupSurface
import com.murong.agent.ui.rememberMurongAccentColor
import com.murong.agent.ui.rememberMurongChromeColor
import com.murong.agent.ui.rememberMurongMutedTextColor
import com.murong.agent.ui.rememberMurongSurfaceColor

@Composable
internal fun AskUserDialog(
    presentation: AskPromptPresentation,
    interactionState: AskPromptInteractionState? = null,
    onInteractionStateChange: ((AskPromptInteractionState) -> Unit)? = null,
    onSubmit: (List<AskAnswerUi>) -> Unit,
    onDismiss: () -> Unit
) {
    MurongDialog(onDismissRequest = onDismiss) {
        MurongPopupSurface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            AskUserPromptContainer(
                presentation = presentation,
                interactionState = interactionState,
                onInteractionStateChange = onInteractionStateChange,
                onSubmit = onSubmit,
                onDismiss = onDismiss
            )
        }
    }
}

@Composable
internal fun AskUserPromptCard(
    presentation: AskPromptPresentation,
    interactionState: AskPromptInteractionState? = null,
    onInteractionStateChange: ((AskPromptInteractionState) -> Unit)? = null,
    onSubmit: (List<AskAnswerUi>) -> Unit,
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
        AskUserPromptContent(
            presentation = presentation,
            interactionState = interactionState,
            onInteractionStateChange = onInteractionStateChange,
            onSubmit = onSubmit,
            onDismiss = onDismiss
        )
    }
}

@Composable
private fun AskUserPromptContainer(
    presentation: AskPromptPresentation,
    interactionState: AskPromptInteractionState? = null,
    onInteractionStateChange: ((AskPromptInteractionState) -> Unit)? = null,
    onSubmit: (List<AskAnswerUi>) -> Unit,
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
            AskUserPromptContent(
                presentation = presentation,
                interactionState = interactionState,
                onInteractionStateChange = onInteractionStateChange,
                onSubmit = onSubmit,
                onDismiss = onDismiss
            )
        }
    }
}

@Composable
private fun AskUserPromptContent(
    presentation: AskPromptPresentation,
    interactionState: AskPromptInteractionState? = null,
    onInteractionStateChange: ((AskPromptInteractionState) -> Unit)? = null,
    onSubmit: (List<AskAnswerUi>) -> Unit,
    onDismiss: () -> Unit
) {
    var localInteractionState by remember(presentation.requestId) {
        mutableStateOf(buildInitialAskPromptInteractionState())
    }
    val currentInteractionState = interactionState ?: localInteractionState
    val updateInteractionState: (AskPromptInteractionState) -> Unit = remember(
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
    val replayOnly = presentation.replayOnly
    val accent = rememberMurongAccentColor()
    val surfaceColor = rememberMurongSurfaceColor()
    val mutedTextColor = rememberMurongMutedTextColor()
    val sessionPresentation = remember(
        currentInteractionState,
        replayOnly,
    ) {
        buildAskPromptSessionPresentation(
            presentation = presentation,
            interactionState = currentInteractionState
        )
    } ?: return
    val currentQuestion = sessionPresentation.currentQuestion
    val currentSelections = sessionPresentation.currentSelections
    val currentCustomAnswer = sessionPresentation.currentCustomAnswer

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = presentation.title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        presentation.replayNotice?.let { notice ->
            Text(
                text = notice,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        Text(
            text = sessionPresentation.status.progressLabel,
            style = MaterialTheme.typography.bodySmall,
            color = mutedTextColor
        )
        Text(
            text = sessionPresentation.status.guidance,
            style = MaterialTheme.typography.bodySmall,
            color = if (replayOnly) MaterialTheme.colorScheme.error else mutedTextColor
        )
        Text(
            text = sessionPresentation.questionProgressLabel,
            style = MaterialTheme.typography.labelMedium,
            color = accent
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            sessionPresentation.questionChips.forEachIndexed { index, question ->
                FilterChip(
                    selected = question.isActive,
                    onClick = {
                        updateInteractionState(
                            selectAskPromptQuestion(
                                state = currentInteractionState,
                                questionIndex = index,
                                questionCount = presentation.questions.size
                            )
                        )
                    },
                    label = { Text(question.label) },
                    leadingIcon = if (question.isAnswered) {
                        { Text(text = "✓", style = MaterialTheme.typography.labelMedium) }
                    } else {
                        null
                    }
                )
            }
        }
        MurongGlassSurface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            surfaceColorOverride = surfaceColor.copy(alpha = 0.70f)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = currentQuestion.title,
                    style = MaterialTheme.typography.labelLarge,
                    color = accent
                )
                Text(
                    text = currentQuestion.prompt,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = currentQuestion.selectionGuidance,
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedTextColor
                )
            }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            currentQuestion.options.forEach { option ->
                FilterChip(
                    selected = option.label in currentSelections && currentCustomAnswer.isBlank(),
                    enabled = !replayOnly,
                    onClick = {
                        if (!replayOnly) {
                            updateInteractionState(
                                updateAskPromptOptionSelection(
                                    state = currentInteractionState,
                                    question = currentQuestion,
                                    optionLabel = option.label
                                )
                            )
                        }
                    },
                    label = {
                        Column {
                            Text(option.label)
                            option.description
                                ?.takeIf { it.isNotBlank() }
                                ?.let { description ->
                                    Text(
                                        text = description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = mutedTextColor
                                    )
                                }
                        }
                    }
                )
            }
        }
        sessionPresentation.replayOnlyHint?.let { replayOnlyHint ->
            Text(
                text = replayOnlyHint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        OutlinedTextField(
            value = currentCustomAnswer,
            onValueChange = { value ->
                if (!replayOnly) {
                    updateInteractionState(
                        updateAskPromptCustomAnswer(
                            state = currentInteractionState,
                            questionId = currentQuestion.id,
                            value = value
                        )
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text(sessionPresentation.customInputPlaceholder)
            },
            maxLines = 3,
            enabled = !replayOnly
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MurongOutlinedActionButton(
                text = sessionPresentation.dismissLabel,
                onClick = onDismiss,
                modifier = Modifier
            )
            sessionPresentation.previousAction?.let { previousAction ->
                MurongOutlinedActionButton(
                    text = previousAction.label,
                    onClick = {
                        performAskPromptPreviousAction(
                            sessionPresentation = sessionPresentation,
                            state = currentInteractionState
                        )?.let { nextState ->
                            updateInteractionState(nextState)
                        }
                    },
                    modifier = Modifier
                )
            }
            Button(
                onClick = {
                    when (
                        val actionResult = performAskPromptPrimaryAction(
                            sessionPresentation = sessionPresentation,
                            state = currentInteractionState
                        )
                    ) {
                        is AskPromptPrimaryActionResult.Navigate -> {
                            updateInteractionState(actionResult.state)
                        }

                        is AskPromptPrimaryActionResult.Submit -> {
                            onSubmit(actionResult.answers)
                        }

                        AskPromptPrimaryActionResult.NoOp -> Unit
                    }
                },
                enabled = sessionPresentation.primaryAction.enabled,
                modifier = Modifier
            ) {
                Text(sessionPresentation.primaryAction.label)
            }
        }
        sessionPresentation.status.disabledHint?.let { disabledHint ->
            Text(
                text = disabledHint,
                style = MaterialTheme.typography.bodySmall,
                color = if (replayOnly) MaterialTheme.colorScheme.error else mutedTextColor
            )
        }
    }
}
