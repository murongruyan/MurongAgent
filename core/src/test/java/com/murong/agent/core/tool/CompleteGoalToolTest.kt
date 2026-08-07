package com.murong.agent.core.tool

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompleteGoalToolTest {
    @Test
    fun completeGoal_successEndsTurnAndReturnsStructuredSuccess() = runBlocking {
        var completedResult = ""
        val tool = CompleteGoalTool(
            currentGoalProvider = { "完成目标" },
            completionBlockReasonProvider = { null },
            onGoalCompleted = { completedResult = it }
        )

        val result = tool.executeWithResult("""{"result":"构建和测试均已通过"}""")

        assertTrue(tool.terminatesTurnOnSuccess)
        assertEquals(true, result.resolvedSuccess)
        assertEquals("构建和测试均已通过", completedResult)
    }

    @Test
    fun completeGoal_incompletePlanDoesNotClearGoal() = runBlocking {
        var completed = false
        val tool = CompleteGoalTool(
            currentGoalProvider = { "完成目标" },
            completionBlockReasonProvider = { "计划尚未完成" },
            onGoalCompleted = { completed = true }
        )

        val result = tool.executeWithResult("""{"result":"完成"}""")

        assertEquals(false, result.resolvedSuccess)
        assertFalse(completed)
        assertTrue(result.output.contains("计划尚未完成"))
    }
}
