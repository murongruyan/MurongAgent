package com.murong.agent.ui

import com.murong.agent.core.loop.BackgroundActivityFocusKind
import kotlin.test.Test
import kotlin.test.assertEquals

class RuntimeFocusPresentationTest {

    @Test
    fun backgroundActivityFocusLabel_returnsSharedPendingApprovalLabel() {
        assertEquals(
            "后台待审批",
            backgroundActivityFocusLabel(BackgroundActivityFocusKind.PENDING_APPROVAL)
        )
    }

    @Test
    fun backgroundActivityFocusLabel_returnsFallbackTitleForOtherKind() {
        assertEquals(
            "后台任务",
            backgroundActivityFocusLabel(
                focusKind = BackgroundActivityFocusKind.OTHER,
                fallbackTitle = "后台任务"
            )
        )
    }
}
