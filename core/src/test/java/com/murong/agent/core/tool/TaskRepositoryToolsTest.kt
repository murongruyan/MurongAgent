package com.murong.agent.core.tool

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TaskRepositoryToolsTest {

    @Test
    fun shouldAutoWindowRemoteTaskRepositoryFileRead_triggersForLongContentOrManyLines() {
        val longContent = "A".repeat(REMOTE_TASK_REPOSITORY_AUTO_READ_WINDOW_CHAR_LIMIT)
        val manyLinesContent = buildString {
            repeat(REMOTE_TASK_REPOSITORY_AUTO_READ_WINDOW_LINE_TRIGGER) { index ->
                append("line ")
                append(index + 1)
                append('\n')
            }
        }

        assertTrue(
            shouldAutoWindowRemoteTaskRepositoryFileRead(
                content = longContent,
                totalLines = 10
            )
        )
        assertTrue(
            shouldAutoWindowRemoteTaskRepositoryFileRead(
                content = manyLinesContent,
                totalLines = REMOTE_TASK_REPOSITORY_AUTO_READ_WINDOW_LINE_TRIGGER
            )
        )
        assertFalse(
            shouldAutoWindowRemoteTaskRepositoryFileRead(
                content = "short",
                totalLines = 10
            )
        )
    }

    @Test
    fun renderRemoteTaskRepositoryFileForTest_autoWindowsLargeFileAndAddsHint() {
        val content = buildString {
            repeat(REMOTE_TASK_REPOSITORY_AUTO_READ_WINDOW_LINE_TRIGGER + 30) { index ->
                append("line ")
                append(index + 1)
                if (index != REMOTE_TASK_REPOSITORY_AUTO_READ_WINDOW_LINE_TRIGGER + 29) {
                    append('\n')
                }
            }
        }

        val rendered = renderRemoteTaskRepositoryFileForTest(
            repoLabel = "murong/agent",
            path = "ChatScreen.kt",
            content = content
        )

        assertTrue(rendered.contains("Showing lines: 1-$REMOTE_TASK_REPOSITORY_DEFAULT_READ_WINDOW_LINES"))
        assertTrue(rendered.contains("Note: 文件较大，未指定行号时默认只返回首个安全窗口"))
        assertTrue(rendered.contains("line $REMOTE_TASK_REPOSITORY_DEFAULT_READ_WINDOW_LINES"))
        assertFalse(rendered.contains("line ${REMOTE_TASK_REPOSITORY_DEFAULT_READ_WINDOW_LINES + 1}"))
    }

    @Test
    fun renderRemoteTaskRepositoryFileForTest_respectsExplicitWindowForLargeFile() {
        val content = buildString {
            repeat(REMOTE_TASK_REPOSITORY_AUTO_READ_WINDOW_LINE_TRIGGER + 30) { index ->
                append("line ")
                append(index + 1)
                if (index != REMOTE_TASK_REPOSITORY_AUTO_READ_WINDOW_LINE_TRIGGER + 29) {
                    append('\n')
                }
            }
        }

        val rendered = renderRemoteTaskRepositoryFileForTest(
            repoLabel = "murong/agent",
            path = "ChatScreen.kt",
            content = content,
            startLine = 240,
            endLine = 245
        )

        assertTrue(rendered.contains("Showing lines: 240-245"))
        assertTrue(rendered.contains("line 240"))
        assertTrue(rendered.contains("line 245"))
        assertFalse(rendered.contains("line 239"))
        assertFalse(rendered.contains("line 246"))
        assertFalse(rendered.contains("Note: 文件较大，未指定行号时默认只返回首个安全窗口"))
    }
}
