package com.murong.agent.ui.chat

import com.murong.agent.core.loop.ApprovalPostureStatusKind
import com.murong.agent.core.loop.ApprovalPostureTelemetry
import com.murong.agent.core.loop.ApprovalScopeTelemetrySummary
import com.murong.agent.core.loop.RuntimeStatusKind
import com.murong.agent.core.loop.BackgroundActivityFocusKind
import com.murong.agent.core.loop.RuntimeApprovalPostureDisplayMode
import com.murong.agent.core.loop.RuntimeStatusSnapshot
import com.murong.agent.core.config.ToolApprovalMode
import com.murong.agent.ui.buildApprovalModeFollowGlobalOptionPresentation
import com.murong.agent.ui.buildApprovalRuntimePosturePresentation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApprovalModeRuntimePresentationTest {

    @Test
    fun buildApprovalRuntimePosturePresentation_marksGlobalModeAsFollowingGlobal() {
        val presentation = approvalRuntimePosturePresentation(
            globalMode = ToolApprovalMode.WHITELIST_AUTO,
            overrideMode = null
        )

        assertEquals("Auto（白名单）（跟随全局）", presentation.label)
        assertTrue(presentation.message.contains("当前审批姿态"))
        assertTrue(presentation.message.contains("命中白名单的操作可自动通过"))
    }

    @Test
    fun buildApprovalRuntimePosturePresentation_marksOverrideAsProjectScoped() {
        val presentation = approvalRuntimePosturePresentation(
            globalMode = ToolApprovalMode.ALL_APPROVAL,
            overrideMode = ToolApprovalMode.ALL_AUTO
        )

        assertEquals("Yolo（全自动）（项目覆盖）", presentation.label)
        assertTrue(presentation.message.contains("所有已启用工具直接执行"))
        assertTrue(presentation.message.contains("不再弹出人工审批"))
    }

    @Test
    fun buildTopStatusStripPresentation_whenIdleShowsApprovalPostureStrip() {
        val approvalPresentation = approvalRuntimePosturePresentation(
            globalMode = ToolApprovalMode.WHITELIST_AUTO,
            overrideMode = null
        )

        val strip = buildTopStatusStripPresentation(
            snapshot = null,
            approvalRuntimePosturePresentation = approvalPresentation
        )

        assertEquals("审批姿态", strip.title)
        assertTrue(strip.message.contains("当前审批姿态"))
        assertTrue(strip.message.contains("命中白名单的操作可自动通过"))
        assertTrue(strip.compact)
    }

    @Test
    fun buildTopStatusStripPresentation_whenRuntimeActiveKeepsRuntimeTitleAndAppendsPosture() {
        val approvalPresentation = approvalRuntimePosturePresentation(
            globalMode = ToolApprovalMode.ALL_APPROVAL,
            overrideMode = ToolApprovalMode.ALL_AUTO
        )

        val strip = buildTopStatusStripPresentation(
            snapshot = RuntimeStatusSnapshot(
                kind = RuntimeStatusKind.EXECUTING,
                title = "处理中",
                message = "当前正在执行任务。"
            ),
            approvalRuntimePosturePresentation = approvalPresentation
        )

        assertEquals("处理中", strip.title)
        assertTrue(strip.message.contains("当前正在执行任务"))
        assertTrue(strip.message.contains("当前审批姿态"))
        assertTrue(strip.message.contains("Yolo（全自动）（项目覆盖）"))
    }

    @Test
    fun buildTopStatusStripPresentation_whenPendingApprovalOnlyAppendsPostureLabel() {
        val approvalPresentation = approvalRuntimePosturePresentation(
            globalMode = ToolApprovalMode.ALL_APPROVAL,
            overrideMode = ToolApprovalMode.ALL_AUTO
        )

        val strip = buildTopStatusStripPresentation(
            snapshot = RuntimeStatusSnapshot(
                kind = RuntimeStatusKind.PENDING_APPROVAL,
                title = "等待审批",
                message = "关键工具始终审批：向用户确认发布方案。工具 `ask_user` 属于关键交互操作。",
                approvalPostureDisplayMode = RuntimeApprovalPostureDisplayMode.LABEL_ONLY
            ),
            approvalRuntimePosturePresentation = approvalPresentation
        )

        assertEquals("等待审批", strip.title)
        assertTrue(strip.message.contains("关键工具始终审批"))
        assertTrue(strip.message.contains("当前审批姿态：Yolo（全自动）（项目覆盖）"))
        assertTrue(!strip.message.contains("大多数操作默认直接通过"))
    }

    @Test
    fun buildTopStatusStripPresentation_whenBackgroundPendingApprovalOnlyAppendsPostureLabel() {
        val approvalPresentation = approvalRuntimePosturePresentation(
            globalMode = ToolApprovalMode.WHITELIST_AUTO,
            overrideMode = null
        )

        val strip = buildTopStatusStripPresentation(
            snapshot = RuntimeStatusSnapshot(
                kind = RuntimeStatusKind.BACKGROUND_ACTIVITY,
                title = "后台任务待审批",
                message = "当前仍有后台子代理任务未终结：待审批 1 个，排队中 1 个。当前: 请求高权限修复 子代理需要更高工具权限。",
                approvalPostureDisplayMode = RuntimeApprovalPostureDisplayMode.LABEL_ONLY,
                backgroundActivityFocusKind = BackgroundActivityFocusKind.PENDING_APPROVAL
            ),
            approvalRuntimePosturePresentation = approvalPresentation
        )

        assertEquals("后台任务待审批", strip.title)
        assertEquals("后台待审批", strip.badge)
        assertTrue(strip.message.contains("待审批 1 个"))
        assertTrue(strip.message.contains("当前审批姿态：Auto（白名单）（跟随全局）"))
        assertTrue(!strip.message.contains("命中白名单的操作可自动通过"))
    }

    @Test
    fun buildTopStatusStripPresentation_whenBackgroundRunningShowsFocusedBadge() {
        val approvalPresentation = approvalRuntimePosturePresentation(
            globalMode = ToolApprovalMode.ALL_APPROVAL,
            overrideMode = null
        )

        val strip = buildTopStatusStripPresentation(
            snapshot = RuntimeStatusSnapshot(
                kind = RuntimeStatusKind.BACKGROUND_ACTIVITY,
                title = "后台任务运行中",
                message = "当前仍有会话级后台任务未终结：运行中 1 个。当前: Shell 后台任务 · logcat -d 后台任务已启动，正在执行。",
                backgroundActivityFocusKind = BackgroundActivityFocusKind.RUNNING
            ),
            approvalRuntimePosturePresentation = approvalPresentation
        )

        assertEquals("后台任务运行中", strip.title)
        assertEquals("后台运行", strip.badge)
        assertTrue(strip.message.contains("当前审批姿态"))
    }

    @Test
    fun buildTopStatusStripPresentation_whenReplayOnlyPendingApprovalDoesNotAppendPosture() {
        val approvalPresentation = approvalRuntimePosturePresentation(
            globalMode = ToolApprovalMode.ALL_APPROVAL,
            overrideMode = ToolApprovalMode.ALL_AUTO
        )

        val strip = buildTopStatusStripPresentation(
            snapshot = RuntimeStatusSnapshot(
                kind = RuntimeStatusKind.PENDING_APPROVAL,
                title = "等待审批",
                message = "这是从已恢复会话中重放的审批请求。原始执行现场已经结束，当前只能查看或关闭，不能继续原调用。",
                approvalPostureDisplayMode = RuntimeApprovalPostureDisplayMode.HIDE
            ),
            approvalRuntimePosturePresentation = approvalPresentation
        )

        assertEquals("等待审批", strip.title)
        assertEquals(
            "这是从已恢复会话中重放的审批请求。原始执行现场已经结束，当前只能查看或关闭，不能继续原调用。",
            strip.message
        )
    }

    @Test
    fun buildTopStatusStripPresentation_whenReplayOnlyPendingAskDoesNotAppendPosture() {
        val approvalPresentation = approvalRuntimePosturePresentation(
            globalMode = ToolApprovalMode.WHITELIST_AUTO,
            overrideMode = null
        )

        val strip = buildTopStatusStripPresentation(
            snapshot = RuntimeStatusSnapshot(
                kind = RuntimeStatusKind.PENDING_ASK,
                title = "等待回答",
                message = "这是从已恢复会话中重放的提问卡片。原始等待链路已经结束，当前只能查看或关闭，不能继续原提问。",
                approvalPostureDisplayMode = RuntimeApprovalPostureDisplayMode.HIDE
            ),
            approvalRuntimePosturePresentation = approvalPresentation
        )

        assertEquals("等待回答", strip.title)
        assertEquals(
            "这是从已恢复会话中重放的提问卡片。原始等待链路已经结束，当前只能查看或关闭，不能继续原提问。",
            strip.message
        )
    }

    @Test
    fun buildTopStatusStripPresentation_whenPendingPromptRuntimePresentationExists_prefersTypedPromptCopy() {
        val approvalPresentation = approvalRuntimePosturePresentation(
            globalMode = ToolApprovalMode.WHITELIST_AUTO,
            overrideMode = null
        )

        val strip = buildTopStatusStripPresentation(
            snapshot = RuntimeStatusSnapshot(
                kind = RuntimeStatusKind.PENDING_WORKFLOW_PLAN,
                title = "等待计划确认",
                message = "raw snapshot"
            ),
            approvalRuntimePosturePresentation = approvalPresentation,
            pendingPromptRuntimePresentation = PendingPromptRuntimePresentation(
                title = "等待计划确认",
                message = "完成 shared host 收口\n待执行 · 步骤 1/2",
                badge = "计"
            )
        )

        assertEquals("等待计划确认", strip.title)
        assertEquals("计", strip.badge)
        assertEquals("完成 shared host 收口\n待执行 · 步骤 1/2", strip.message)
    }

    @Test
    fun buildApprovalModeDialogFollowGlobalSubtitle_usesActualGlobalModeLabel() {
        val subtitle = buildApprovalModeFollowGlobalOptionPresentation(
            globalMode = ToolApprovalMode.ALL_APPROVAL
        ).subtitle

        assertEquals("当前全局：Ask（全审批）", subtitle)
    }

    private fun approvalRuntimePosturePresentation(
        globalMode: ToolApprovalMode,
        overrideMode: ToolApprovalMode?
    ) = buildApprovalRuntimePosturePresentation(
        globalMode = globalMode,
        overrideMode = overrideMode,
        postureTelemetry = ApprovalPostureTelemetry(
            statusKind = ApprovalPostureStatusKind.IDLE,
            hasPendingApproval = false,
            pendingApprovalIsReplayOnly = false,
            hasReusableScopes = false,
            hasRecentInvalidations = false,
            scopeSummary = ApprovalScopeTelemetrySummary(
                currentApprovalCount = 0,
                inheritedApprovalCount = 0,
                recentApprovalCount = 0,
                recentInvalidationCount = 0
            )
        ),
        runtimeStatusSnapshot = null
    )
}
