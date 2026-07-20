package com.murong.agent.ui.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExternalShareDraftTest {
    @Test
    fun buildExternalShareText_preservesDistinctSubjectAndBody() {
        assertEquals(
            "页面标题\nhttps://example.com/article",
            buildExternalShareText(" 页面标题 ", " https://example.com/article ")
        )
        assertEquals("同一段", buildExternalShareText("同一段", "同一段"))
    }

    @Test
    fun mergeExternalShareDraftText_appendsWithoutOverwritingCurrentDraft() {
        assertEquals(
            "原来的草稿\n新分享的内容",
            mergeExternalShareDraftText(
                currentText = "原来的草稿",
                sharedText = "新分享的内容",
                fileCount = 0
            )
        )
    }

    @Test
    fun mergeExternalShareDraftText_createsEditablePromptForFileOnlyShare() {
        assertEquals(
            "请查看分享的文件。",
            mergeExternalShareDraftText(currentText = "", sharedText = "", fileCount = 1)
        )
        assertEquals(
            "已有要求\n请查看分享的这些文件。",
            mergeExternalShareDraftText(currentText = "已有要求", sharedText = "", fileCount = 3)
        )
    }

    @Test
    fun sanitizeExternalShareFileName_removesPathsAndUnsafeCharacters() {
        assertEquals(
            "report_2026_.zip",
            sanitizeExternalShareFileName("../folder/report:2026?.zip", fallbackIndex = 2)
        )
        assertEquals(
            "shared-file-3",
            sanitizeExternalShareFileName("...", fallbackIndex = 3)
        )
    }

    @Test
    fun externalShareSizeViolation_enforcesPerFileAndTotalBudgets() {
        val limits = ExternalShareLimits(
            maxFiles = 4,
            maxFileBytes = 100,
            maxTotalBytes = 180
        )
        assertEquals(
            ExternalShareSizeViolation.FILE_TOO_LARGE,
            externalShareSizeViolation(declaredSize = 101, copiedTotalBytes = 0, limits = limits)
        )
        assertEquals(
            ExternalShareSizeViolation.TOTAL_TOO_LARGE,
            externalShareSizeViolation(declaredSize = 90, copiedTotalBytes = 100, limits = limits)
        )
        assertEquals(
            null,
            externalShareSizeViolation(declaredSize = 80, copiedTotalBytes = 100, limits = limits)
        )
    }

    @Test
    fun shouldDispatchExternalShare_skipsRestoredActivityButAcceptsANewIntent() {
        assertTrue(
            shouldDispatchExternalShare(
                intentAlreadyMarked = false,
                activityStateAlreadyDispatched = false,
                deliveredViaOnNewIntent = false
            )
        )
        assertFalse(
            shouldDispatchExternalShare(
                intentAlreadyMarked = false,
                activityStateAlreadyDispatched = true,
                deliveredViaOnNewIntent = false
            )
        )
        assertTrue(
            shouldDispatchExternalShare(
                intentAlreadyMarked = false,
                activityStateAlreadyDispatched = true,
                deliveredViaOnNewIntent = true
            )
        )
        assertFalse(
            shouldDispatchExternalShare(
                intentAlreadyMarked = true,
                activityStateAlreadyDispatched = false,
                deliveredViaOnNewIntent = true
            )
        )
    }
}
