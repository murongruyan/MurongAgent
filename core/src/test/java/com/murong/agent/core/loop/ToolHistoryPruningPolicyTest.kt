package com.murong.agent.core.loop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ToolHistoryPruningPolicyTest {

    @Test
    fun planStaleToolResultElision_elidesOlderLargeSuccessfulToolResults() {
        val largePayload = "A".repeat(STALE_TOOL_RESULT_ELISION_MIN_PAYLOAD_CHARS + 400)
        val messages = mutableListOf(
            userMessage(1, "任务"),
            assistantMessage(2, "先读取"),
            toolResultMessage(3, "read_file", largePayload)
        )
        repeat(9) { index ->
            messages += assistantMessage((4 + index).toLong(), "filler-$index")
        }
        messages += userMessage(20, "继续")
        messages += assistantMessage(21, "ok")

        val plan = planStaleToolResultElision(
            messages = messages,
            keptIndices = setOf(2),
            recentMessageWindow = 10
        )

        assertEquals(setOf(2), plan.elidedIndices)
        assertTrue(plan.savedCharsEstimate > 500)
        val summary = buildElidedToolResultHistorySummary(messages[2].content)
        assertNotNull(summary)
        assertTrue(summary.contains("read_file"))
        assertTrue(summary.contains("字符"))
    }

    @Test
    fun planStaleToolResultElision_keepsErrorsFileChangesAndCompleteStep() {
        val largePayload = "B".repeat(STALE_TOOL_RESULT_ELISION_MIN_PAYLOAD_CHARS + 200)
        val messages = mutableListOf(
            userMessage(1, "任务"),
            assistantMessage(2, "开始"),
            toolResultMessage(3, "shell", "Error: " + largePayload),
            toolResultMessage(4, "complete_step", "Step signed off.\n" + largePayload),
            toolResultMessage(
                5,
                "task_repo_update_file",
                largePayload,
                includeFileChanges = true
            )
        )
        repeat(9) { index ->
            messages += assistantMessage((6 + index).toLong(), "filler-$index")
        }
        messages += userMessage(30, "继续")

        val plan = planStaleToolResultElision(
            messages = messages,
            keptIndices = setOf(2, 3, 4),
            recentMessageWindow = 10
        )

        assertTrue(plan.elidedIndices.isEmpty(), "错误、签收和带文件变更的结果不应被省略")
        assertEquals(0, plan.savedCharsEstimate)
    }

    @Test
    fun planStaleToolResultElision_keepsRecentOrPostUserToolResults() {
        val largePayload = "C".repeat(STALE_TOOL_RESULT_ELISION_MIN_PAYLOAD_CHARS + 200)
        val messages = mutableListOf(
            userMessage(1, "任务")
        )
        repeat(10) { index ->
            messages += assistantMessage((2 + index).toLong(), "filler-$index")
        }
        messages += userMessage(20, "最后一个用户问题")
        messages += toolResultMessage(21, "read_file", largePayload)
        messages += assistantMessage(22, "recent")

        val plan = planStaleToolResultElision(
            messages = messages,
            keptIndices = setOf(12),
            recentMessageWindow = 10
        )

        assertFalse(12 in plan.elidedIndices, "最近区域或最后一个 user 之后的工具结果不应被省略")
    }

    private fun userMessage(id: Long, content: String): ChatMessageUi {
        return ChatMessageUi(
            id = id,
            role = "user",
            content = content
        )
    }

    private fun assistantMessage(id: Long, content: String): ChatMessageUi {
        return ChatMessageUi(
            id = id,
            role = "assistant",
            content = content
        )
    }

    private fun toolResultMessage(
        id: Long,
        toolName: String,
        payload: String,
        includeFileChanges: Boolean = false
    ): ChatMessageUi {
        val content = buildString {
            append("📦 **")
            append(toolName)
            append("** 执行结果:\n```\n")
            append(payload)
            append("\n```")
            if (includeFileChanges) {
                append("\n\n本次文件变更:\n- demo.txt (修改)\n")
            }
        }
        return ChatMessageUi(
            id = id,
            role = "tool_exec",
            content = content
        )
    }
}
