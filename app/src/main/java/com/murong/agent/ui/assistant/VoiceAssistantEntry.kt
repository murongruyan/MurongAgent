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
