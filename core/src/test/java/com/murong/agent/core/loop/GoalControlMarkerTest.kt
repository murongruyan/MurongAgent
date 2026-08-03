package com.murong.agent.core.loop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoalControlMarkerTest {

    @Test
    fun plainReply_withoutMarkers_returnsNull() {
        assertNull(parseGoalControlMarkers("好的，我继续推进。" ))
        assertNull(parseGoalControlMarkers(""))
        assertNull(parseGoalControlMarkers("   "))
    }

    @Test
    fun goalStartMarker_setsGoalAndStripsMarkerLine() {
        val result = markers(
            "[goal:start] 帮我重构登录模块并补测试\n好的，我先阅读现有实现。"
        )

        assertEquals("帮我重构登录模块并补测试", result.goalStart)
        assertEquals("好的，我先阅读现有实现。", result.remainingContent.trim())
        assertFalse(result.complete)
    }

    @Test
    fun goalCompleteMarker_flagsCompletionAndKeepsExplanation() {
        val result = markers(
            "[goal:complete]\n登录模块重构完成，测试全部通过。"
        )

        assertTrue(result.complete)
        assertEquals("登录模块重构完成，测试全部通过。", result.remainingContent.trim())
    }

    @Test
    fun pauseAndResumeMarkers_areDetected() {
        val pause = markers("[goal:pause]\n先等我确认。")
        val resume = markers("[goal:resume]\n继续。")

        assertTrue(pause.pause)
        assertEquals("先等我确认。", pause.remainingContent.trim())
        assertTrue(resume.resume)
    }

    @Test
    fun planStartMarker_isDetectedAndMarkerRemovedFromMiddle() {
        val result = markers(
            "我先梳理一下思路。\n[plan:start]\n然后我们按计划执行。"
        )

        assertTrue(result.planStart)
        assertEquals("我先梳理一下思路。\n然后我们按计划执行。", result.remainingContent.trim())
    }

    @Test
    fun combinedMarkers_areAllAppliedInOrder() {
        val result = markers(
            "[goal:start] 修复支付流程\n[plan:start]\n[goal:complete]"
        )

        assertEquals("修复支付流程", result.goalStart)
        assertTrue(result.planStart)
        assertTrue(result.complete)
        assertEquals("", result.remainingContent.trim())
    }

    private fun markers(raw: String): GoalControlMarkerResult =
        requireNotNull(parseGoalControlMarkers(raw)) { "expected control markers in: $raw" }
}
