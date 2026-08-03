package com.murong.agent.ui.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GoalModeIntentTest {

    @Test
    fun ordinaryMessage_hasNoGoalIntent() {
        assertNull(detectGoalModeIntent("帮我看看这个报错"))
        assertNull(detectGoalModeIntent(""))
        assertNull(detectGoalModeIntent("  "))
    }

    @Test
    fun goalModePhrase_withTask_returnsTaskAsGoal() {
        assertEquals(
            "帮我重构登录模块",
            detectGoalModeIntent("开个目标模式，帮我重构登录模块")
        )
        assertEquals(
            "把登录模块重构并补测试",
            detectGoalModeIntent("开启目标模式：把登录模块重构并补测试")
        )
        assertEquals(
            "帮我把登录模块重构",
            detectGoalModeIntent("帮我把登录模块重构，开个目标模式干吧")
        )
    }

    @Test
    fun goalModePhrase_withoutTask_keepsWholeMessageAsGoal() {
        assertEquals("你开个目标模式开始干吧", detectGoalModeIntent("你开个目标模式开始干吧"))
        assertEquals("开启目标模式", detectGoalModeIntent("开启目标模式"))
    }

    @Test
    fun englishGoalModePhrase_isDetected() {
        assertEquals("refactor the login module", detectGoalModeIntent("open goal mode, refactor the login module"))
    }
}
