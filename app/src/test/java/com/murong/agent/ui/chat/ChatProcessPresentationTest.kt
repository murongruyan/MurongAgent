package com.murong.agent.ui.chat

import com.murong.agent.core.loop.ChatMessageUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatProcessPresentationTest {
    @Test
    fun `buildChatProcessSummary aggregates reasoning reads commands and file changes`() {
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
            )
        )

        val summary = buildChatProcessSummary(messages)

        assertEquals(1, summary.reasoningCount)
        assertEquals(1, summary.readFileCount)
        assertEquals(1, summary.commandCount)
        assertEquals(1, summary.changedFileCount)
        assertTrue(summary.labels().contains("思考过程"))
        assertTrue(summary.labels().contains("已读取 1 个文件"))
        assertTrue(summary.labels().contains("已运行 1 个命令"))
        assertTrue(summary.labels().contains("已修改 1 个文件"))
    }

    @Test
    fun `process message classification excludes final assistant reply`() {
        val reasoning = ChatMessageUi(id = 1, role = "assistant", content = "", reasoning = "分析中")
        val tool = ChatMessageUi(id = 2, role = "tool_exec", content = "工具输出")
        val finalReply = ChatMessageUi(id = 3, role = "assistant", content = "已完成", reasoning = "简短思考")

        assertTrue(isChatProcessMessage(reasoning))
        assertTrue(isChatProcessMessage(tool))
        assertFalse(isChatProcessMessage(finalReply))
    }

    @Test
    fun `buildChatProcessSummary marks failed executions`() {
        val summary = buildChatProcessSummary(
            listOf(ChatMessageUi(id = 1, role = "tool_exec", content = "执行失败: permission denied"))
        )

        assertTrue(summary.hasFailure)
        assertTrue(summary.labels().contains("存在失败"))
    }
}
