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

    @Test
    fun `food delivery voice answer keeps original phone task context`() {
        val continued = assistantFollowUpTaskText(
            userText = "你自己看哪些好喝，点那个",
            taskContext = "打开美团把地址更改到江苏省张家港市德积镇的地址点一杯蜜雪冰城",
        )

        assert(continued.contains("打开美团"))
        assert(continued.contains("外卖续接选择：你自己看哪些好喝，点那个"))
        assertEquals(
            AssistantTaskKind.PHONE_FOREGROUND,
            AssistantRequestRouter.classify(continued).kind,
        )
    }
}
