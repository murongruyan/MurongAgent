package com.murong.agent.ui.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProjectTerminalCommandRecordsTest {

    @Test
    fun parser_readsCommandExitCodeDirectoryAndEpochSeconds() {
        val record = parseProjectTerminalCommandRecord(
            "pkg install python\u001F/storage/emulated/0/备份\u001F0\u001F1784295000"
        )

        requireNotNull(record)
        assertEquals("pkg install python", record.command)
        assertEquals("/storage/emulated/0/备份", record.workingDirectory)
        assertEquals(0, record.exitCode)
        assertEquals(1_784_295_000_000L, record.timestampMillis)
    }

    @Test
    fun parser_rejectsIncompleteOrBlankRecords() {
        assertNull(parseProjectTerminalCommandRecord("python\u001F/storage/emulated/0"))
        assertNull(parseProjectTerminalCommandRecord(" \u001F/storage/emulated/0\u001F0\u001F1"))
    }

    @Test
    fun preview_keepsOnlyTheVisibleOutputBeforeNextPrompt() {
        val preview = projectTerminalOutputPreviewForCommand(
            transcript = """
                $ sdcard > python --version
                Python 3.14.0
                $ sdcard >
            """.trimIndent(),
            command = "python --version"
        )

        assertEquals("Python 3.14.0", preview)
    }

    @Test
    fun structuredStartAndFinish_preserveCommandIdentityAndCtrlCInterruption() {
        val started = requireNotNull(
            parseProjectTerminalCommandRecordEvent(
                "S\u001Fcmd-8\u001Fsleep 5\u001F/storage/emulated/0\u001F1784295000"
            )
        )
        val finished = requireNotNull(
            parseProjectTerminalCommandRecordEvent(
                "E\u001Fcmd-8\u001F130\u001F/storage/emulated/0\u001F1784295002"
            )
        )

        val record = requireNotNull(
            projectTerminalCommandRecordFromEvents(started, finished, "interactive:session#cmd-8")
        )

        assertEquals("cmd-8", record.commandId)
        assertEquals(1_784_295_000_000L, record.startedAtMillis)
        assertEquals(1_784_295_002_000L, record.finishedAtMillis)
        assertEquals(ProjectTerminalCommandStatus.INTERRUPTED, record.status)
    }

    @Test
    fun orderedPreview_usesTheNextMatchingCommandInsteadOfTheLastOne() {
        val transcript = """
            $ sdcard > echo same
            first
            $ sdcard > echo same
            second
            $ sdcard >
        """.trimIndent()

        val first = projectTerminalOutputPreviewAfterOffset(transcript, "echo same", 0)
        val second = projectTerminalOutputPreviewAfterOffset(transcript, "echo same", first.nextSearchOffset)

        assertEquals("first", first.content)
        assertEquals("second", second.content)
        assertTrue(second.nextSearchOffset > first.nextSearchOffset)
    }
}
