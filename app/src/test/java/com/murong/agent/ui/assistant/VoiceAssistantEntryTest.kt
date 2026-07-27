package com.murong.agent.ui.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceAssistantEntryTest {
    @Test
    fun `assistant starts recognition only after microphone is granted`() {
        assertEquals(
            VoiceAssistantLaunchAction.START_RECOGNITION,
            voiceAssistantLaunchAction(microphoneGranted = true)
        )
        assertEquals(
            VoiceAssistantLaunchAction.REQUEST_MICROPHONE_PERMISSION,
            voiceAssistantLaunchAction(microphoneGranted = false)
        )
    }
}
