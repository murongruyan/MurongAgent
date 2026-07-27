package com.murong.agent.ui.chat

import com.murong.agent.core.loop.ChatMessageUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatProcessPresentationTest {
    @Test
    fun `buildChatProcessSummary aggregates tool calls without counting model text`() {
        val messages = listOf(
            ChatMessageUi(
                id = 1,
                role = "assistant",
                content = "",
                reasoning = "先检查文件"
            ),
            ChatMessageUi(
                id = 2,
                role = "tool_exec",
                content = """
                    🔧 正在执行: **read_file**
                    ```json
                    {"path":"app/src/Main.kt"}
                    ```
                """.trimIndent()
            ),
            ChatMessageUi(
                id = 3,
                role = "tool_exec",
                content = """
                    🔧 正在执行: **run_command**
                    ```json
                    {"command":"./gradlew test"}
                    ```
                """.trimIndent()
            ),
            ChatMessageUi(
                id = 4,
                role = "tool_exec",
                content = """
                    📦 **edit_file** 执行结果:
                    已完成
                    本次文件变更:
                    - app/src/Main.kt
                """.trimIndent()
            ),
            ChatMessageUi(
                id = 5,
                role = "tool_exec",
                content = """
                    📦 **run_command** 执行结果:
                    tests passed
                """.trimIndent()
            )
        )

        val summary = buildChatProcessSummary(messages)

        assertEquals(0, summary.reasoningCount)
        assertEquals(1, summary.readFileCount)
        assertEquals(1, summary.commandCount)
        assertEquals(1, summary.changedFileCount)
        assertFalse(summary.labels().contains("思考过程"))
        assertTrue(summary.labels().contains("已读取 1 个文件"))
        assertTrue(summary.labels().contains("已运行 1 个命令"))
        assertTrue(summary.labels().contains("已修改 1 个文件"))
    }

    @Test
    fun `process message classification only includes tools and subagents`() {
        val reasoning = ChatMessageUi(id = 1, role = "assistant", content = "", reasoning = "分析中")
        val tool = ChatMessageUi(id = 2, role = "tool_exec", content = "工具输出")
        val finalReply = ChatMessageUi(id = 3, role = "assistant", content = "已完成", reasoning = "简短思考")
        val system = ChatMessageUi(id = 4, role = "system", content = "任务已终止")
        val subagent = ChatMessageUi(id = 5, role = "subagent", content = "子代理运行")

        assertFalse(isChatProcessMessage(reasoning))
        assertTrue(isChatProcessMessage(tool))
        assertFalse(isChatProcessMessage(finalReply))
        assertFalse(isChatProcessMessage(system))
        assertTrue(isChatProcessMessage(subagent))
    }

    @Test
    fun `buildChatProcessSummary marks failed executions`() {
        val summary = buildChatProcessSummary(
            listOf(ChatMessageUi(id = 1, role = "tool_exec", content = "执行失败: permission denied"))
        )

        assertTrue(summary.hasFailure)
        assertTrue(summary.labels().contains("存在失败"))
    }

    @Test
    fun `internal tool names are rendered as readable Chinese actions`() {
        assertEquals("列出文件", displayToolName("list_files"))
        assertEquals("搜索代码", displayToolName("code_search"))
        assertEquals("运行命令", displayToolName("run_terminal"))
    }
}
