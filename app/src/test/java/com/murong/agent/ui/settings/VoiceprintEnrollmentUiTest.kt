package com.murong.agent.ui.settings

import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class VoiceprintEnrollmentUiTest {
    @Test
    fun `five enrollment rounds provide distinct practical guidance`() {
        val hints = (1..5).map(::voiceprintEnrollmentHint)

        assertNotEquals(hints[0], hints[1])
        assertNotEquals(hints[1], hints[2])
        assertTrue(hints[3].contains("偏离"))
        assertTrue(hints[4].contains("最后一次"))
    }
}
