package com.murong.agent.ui.assistant

import android.content.Context
import android.content.Intent
import com.murong.agent.ui.MainActivity
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/** Bridges Android's system-assistant entry points into the existing voice chat UI. */
object VoiceAssistantEntry {
    const val EXTRA_AUTO_START_VOICE_INPUT =
        "com.murong.agent.extra.AUTO_START_VOICE_INPUT"

    private val requestsChannel = Channel<Unit>(Channel.CONFLATED)
    val requests: Flow<Unit> = requestsChannel.receiveAsFlow()

    fun dispatch(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_AUTO_START_VOICE_INPUT, false) != true) return
        // Avoid replaying the same assist request after an Activity recreation.
        intent.removeExtra(EXTRA_AUTO_START_VOICE_INPUT)
        requestsChannel.trySend(Unit)
    }

    fun launchIntent(context: Context): Intent =
        Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(EXTRA_AUTO_START_VOICE_INPUT, true)
        }
}

/** Opens the conversation that owns an ongoing assistant task without starting voice input. */
object AssistantTaskEntry {
    const val EXTRA_OPEN_ASSISTANT_TASK =
        "com.murong.agent.extra.OPEN_ASSISTANT_TASK"

    private val requestsChannel = Channel<Unit>(Channel.CONFLATED)
    val requests: Flow<Unit> = requestsChannel.receiveAsFlow()

    fun dispatch(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_ASSISTANT_TASK, false) != true) return
        intent.removeExtra(EXTRA_OPEN_ASSISTANT_TASK)
        requestsChannel.trySend(Unit)
    }

    fun launchIntent(context: Context): Intent =
        Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(EXTRA_OPEN_ASSISTANT_TASK, true)
        }
}

/** Reopens the translucent voice-assistant conversation instead of the full in-app chat. */
object AssistantVoicePopupEntry {
    const val INVOCATION_SOURCE = "offscreen_return"
    const val EXTRA_FOLLOW_UP_PROMPT = "com.murong.agent.extra.VOICE_FOLLOW_UP_PROMPT"
    const val EXTRA_FOLLOW_UP_TASK_CONTEXT =
        "com.murong.agent.extra.VOICE_FOLLOW_UP_TASK_CONTEXT"

    fun launchIntent(
        context: Context,
        followUpPrompt: String? = null,
        followUpTaskContext: String? = null,
    ): Intent =
        MurongAssistActivity.createIntent(context, INVOCATION_SOURCE).apply {
            followUpPrompt?.trim()?.takeIf(String::isNotBlank)?.let { prompt ->
                putExtra(EXTRA_FOLLOW_UP_PROMPT, prompt)
            }
            followUpTaskContext?.trim()?.takeIf(String::isNotBlank)?.let { task ->
                putExtra(EXTRA_FOLLOW_UP_TASK_CONTEXT, task)
            }
        }
}

internal fun assistantFollowUpTaskText(userText: String, taskContext: String): String {
    val answer = userText.trim()
    val context = taskContext.trim()
    return if (context.isBlank()) answer else "$context\n外卖续接选择：$answer"
}

/** Opens the live, interactive view of the current isolated Phone Agent display. */
object AssistantOffscreenEntry {
    fun launchIntent(context: Context): Intent =
        Intent(context, AssistantIsolatedDisplayActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
}

internal enum class VoiceAssistantLaunchAction {
    START_RECOGNITION,
    REQUEST_MICROPHONE_PERMISSION
}

internal fun voiceAssistantLaunchAction(microphoneGranted: Boolean): VoiceAssistantLaunchAction =
    if (microphoneGranted) {
        VoiceAssistantLaunchAction.START_RECOGNITION
    } else {
        VoiceAssistantLaunchAction.REQUEST_MICROPHONE_PERMISSION
    }
