package com.murong.agent.ui

import com.murong.agent.core.loop.RuntimeApprovalPostureDisplayMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RuntimeApprovalPosturePresentationTest {

    @Test
    fun buildRuntimeApprovalPostureMessage_returnsNullWhenHidden() {
        assertNull(
            buildRuntimeApprovalPostureMessage(
                displayMode = RuntimeApprovalPostureDisplayMode.HIDE,
                postureLabel = "Ask（全审批）",
                fullMessage = "所有工具调用都需要你显式确认"
            )
        )
    }

    @Test
    fun buildRuntimeApprovalPostureMessage_returnsLabelOnlySummaryWhenRequested() {
        assertEquals(
            "当前审批姿态：Ask（全审批）",
            buildRuntimeApprovalPostureMessage(
                displayMode = RuntimeApprovalPostureDisplayMode.LABEL_ONLY,
                postureLabel = "Ask（全审批）",
                fullMessage = "所有工具调用都需要你显式确认"
            )
        )
    }

    @Test
    fun buildRuntimeApprovalPostureMessage_returnsFullMessageWhenRequested() {
        assertEquals(
            "所有工具调用都需要你显式确认",
            buildRuntimeApprovalPostureMessage(
                displayMode = RuntimeApprovalPostureDisplayMode.FULL_MESSAGE,
                postureLabel = "Ask（全审批）",
                fullMessage = "所有工具调用都需要你显式确认"
            )
        )
    }
}
