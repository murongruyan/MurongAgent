package com.murong.agent.ui.chat

import com.murong.agent.core.config.ProviderConfig
import com.murong.agent.core.config.ToolApprovalMode
import com.murong.agent.core.config.WorkflowExecutionMode
import com.murong.agent.core.loop.ArchivedMemoryCandidate
import com.murong.agent.core.loop.ArchivedMemoryCandidateScope
import com.murong.agent.core.loop.ApprovalCardTelemetry
import com.murong.agent.core.loop.ApprovalPostureStatusKind
import com.murong.agent.core.loop.ApprovalPostureTelemetry
import com.murong.agent.core.loop.ApprovalRuntimeTelemetry
import com.murong.agent.core.loop.ApprovalScopeTelemetrySummary
import com.murong.agent.core.loop.ProjectApprovalCardTelemetry
import com.murong.agent.core.loop.RuntimeStatusKind
import com.murong.agent.core.loop.RuntimeStatusSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ChatRuntimeHostPresentationTest {

    @Test
    fun buildChatRuntimeHostPresentation_mergesPosturePromptAndSupplementalStatuses() {
        val askPresentation = pendingAsk().toAskPromptPresentation()
        val workflowPresentation = workflowPlan().toWorkflowPlanPromptPresentation()
        val clarificationPresentation = clarificationRequest().toClarificationPromptPresentation()
        val pendingPromptHostPresentation = buildPendingPromptHostPresentation(
            askPresentation = askPresentation,
            workflowPlanPresentation = workflowPresentation,
            clarificationPresentation = clarificationPresentation,
            interactionState = PendingPromptHostInteractionState(
                askRequestId = askPresentation.requestId,
                askInteractionState = AskPromptInteractionState(activeIndex = 1),
                workflowPlanRequestId = workflowPresentation.requestId,
                workflowPlanInteractionState = WorkflowPlanInteractionState(showRawPlan = true),
                clarificationRequestId = clarificationPresentation.requestId,
                clarificationInteractionState = ClarificationInteractionState(answer = "继续推进")
            ),
            isChatScreenVisible = true
        )
        val presentation = buildChatRuntimeHostPresentation(
            config = ProviderConfig(
                approvalMode = ToolApprovalMode.WHITELIST_AUTO,
                workflowExecutionMode = WorkflowExecutionMode.SINGLE_PASS,
                autoRouteBeforeExecution = true
            ),
            approvalRuntimeTelemetry = approvalRuntimeTelemetry(),
            runtimeStatusSnapshot = RuntimeStatusSnapshot(
                kind = RuntimeStatusKind.PENDING_WORKFLOW_PLAN,
                title = "等待计划确认",
                message = "raw snapshot"
            ),
            pendingPromptHostPresentation = pendingPromptHostPresentation,
            recentHistoryCluePresentation = workflowPresentation.recentHistoryClue,
            askPresentation = askPresentation,
            workflowPlanPresentation = workflowPresentation,
            clarificationPresentation = clarificationPresentation,
            autoRoutingInProgress = true,
            workflowPlanningInProgress = true,
            clarificationInProgress = true
        )

        assertEquals("审批: 跟随全局 (Auto（白名单）)", presentation.currentApprovalModeLabel)
        assertEquals("等待计划确认", presentation.topStatusStrip.title)
        assertEquals("计", presentation.topStatusStrip.badge)
        assertTrue(presentation.topStatusStrip.message.contains("完成 shared host 收口"))
        assertTrue(presentation.topStatusStrip.message.contains("步骤 1/2"))
        assertTrue(presentation.topStatusStrip.message.contains("最近历史线索"))
        assertTrue(presentation.topStatusStrip.message.contains("session-login#21"))
        assertEquals("跨会话历史线索", presentation.recentHistorySurfacePresentation?.title)
        assertEquals("来源：当前会话上下文", presentation.recentHistorySurfacePresentation?.detailSubtitle)
        assertTrue(
            presentation.recentHistorySurfacePresentation?.detailRows?.any {
                it.label == "摘录窗口"
            } == true
        )
        assertEquals(
            WorkflowPlanHostSurfaceKind.CHAT_INLINE,
            presentation.pendingPromptHostPresentation.workflowPlanHostSurface.kind
        )
        assertEquals(
            true,
            presentation.pendingPromptHostPresentation.workflowPlanHostSurface.interactionState.showRawPlan
        )
        assertEquals(
            listOf("自动分流", "执行计划", "澄清问题"),
            presentation.supplementalRuntimeStatuses.map { it.title }
        )
    }

    @Test
    fun buildChatRuntimeHostPresentation_whenIdleUsesApprovalPostureAsPrimaryStrip() {
        val presentation = buildChatRuntimeHostPresentation(
            config = ProviderConfig(
                approvalMode = ToolApprovalMode.ALL_APPROVAL,
                workflowExecutionMode = WorkflowExecutionMode.SINGLE_PASS
            ),
            approvalRuntimeTelemetry = approvalRuntimeTelemetry(),
            runtimeStatusSnapshot = null,
            askPresentation = null,
            workflowPlanPresentation = null,
            clarificationPresentation = null,
            autoRoutingInProgress = false,
            workflowPlanningInProgress = false,
            clarificationInProgress = false
        )

        assertEquals("审批姿态", presentation.topStatusStrip.title)
        assertTrue(presentation.topStatusStrip.message.contains("当前审批姿态"))
        assertTrue(presentation.topStatusStrip.compact)
        assertTrue(presentation.supplementalRuntimeStatuses.isEmpty())
        assertEquals(null, presentation.recentHistorySurfacePresentation)
    }

    @Test
    fun buildChatRuntimeHostPresentation_whenArchivedMemoryCandidatesExist_buildsConsumerSurface() {
        val presentation = buildChatRuntimeHostPresentation(
            config = ProviderConfig(),
            approvalRuntimeTelemetry = approvalRuntimeTelemetry(),
            runtimeStatusSnapshot = null,
            archivedMemoryCandidates = listOf(
                ArchivedMemoryCandidate(
                    sourceSessionId = "session-login",
                    sourceSessionTitle = "登录修复会话",
                    sourceProjectPath = "C:/workspace/app",
                    suggestedScope = ArchivedMemoryCandidateScope.PROJECT,
                    suggestedTitle = "修复登录态过期问题",
                    suggestedContent = "登录态过期后需要补 token 刷新",
                    sourceAnchorMessageReference = "session-login#21",
                    sourceArchivedAt = 10L,
                    sourceUpdatedAt = 20L,
                    sourceFinalReadinessSummary = "最终收口已确认登录链路恢复"
                ),
                ArchivedMemoryCandidate(
                    sourceSessionId = "session-release",
                    sourceSessionTitle = "发布检查会话",
                    suggestedScope = ArchivedMemoryCandidateScope.GLOBAL,
                    suggestedTitle = "发布 smoke test 检查项",
                    suggestedContent = "发布 smoke test 需要覆盖登录校验",
                    sourceArchivedAt = 30L,
                    sourceUpdatedAt = 40L
                ),
                ArchivedMemoryCandidate(
                    sourceSessionId = "session-observe",
                    sourceSessionTitle = "埋点审计会话",
                    suggestedScope = ArchivedMemoryCandidateScope.PROJECT,
                    suggestedTitle = "补齐错误埋点",
                    suggestedContent = "错误埋点需要带上 trace id",
                    sourceArchivedAt = 50L,
                    sourceUpdatedAt = 60L
                ),
                ArchivedMemoryCandidate(
                    sourceSessionId = "session-cleanup",
                    sourceSessionTitle = "清理会话",
                    suggestedScope = ArchivedMemoryCandidateScope.GLOBAL,
                    suggestedTitle = "保留清理 checklist",
                    suggestedContent = "清理前需要核对 rollout checklist",
                    sourceArchivedAt = 70L,
                    sourceUpdatedAt = 80L
                )
            ),
            askPresentation = null,
            workflowPlanPresentation = null,
            clarificationPresentation = null,
            autoRoutingInProgress = false,
            workflowPlanningInProgress = false,
            clarificationInProgress = false
        )

        val archivedMemorySurfacePresentation = assertNotNull(presentation.archivedMemorySurfacePresentation)
        assertEquals("归档记忆候选", archivedMemorySurfacePresentation.title)
        assertTrue(archivedMemorySurfacePresentation.message.contains("待处理 4 条"))
        assertTrue(archivedMemorySurfacePresentation.message.contains("修复登录态过期问题"))
        assertEquals(
            "来源：archive-on-forget · 当前待处理 4 条",
            archivedMemorySurfacePresentation.detailSubtitle
        )
        assertEquals(4, archivedMemorySurfacePresentation.totalCandidateCount)
        assertEquals(3, archivedMemorySurfacePresentation.candidates.size)
        assertEquals(4, archivedMemorySurfacePresentation.batchCandidates.size)
        val loginCandidate = assertNotNull(
            archivedMemorySurfacePresentation.candidates.find { it.sessionId == "session-login" }
        )
        assertEquals(ArchivedMemoryCandidateScope.PROJECT, loginCandidate.suggestedScope)
        assertEquals("登录修复会话", loginCandidate.sourceSessionTitle)
        assertEquals("修复登录态过期问题", loginCandidate.suggestedTitle)
        assertTrue(loginCandidate.suggestedContentPreview.contains("token 刷新"))
        assertEquals("session-login#21", loginCandidate.sourceAnchorMessageReference)
        assertEquals("最终收口已确认登录链路恢复", loginCandidate.sourceFinalReadinessSummary)
    }

    private fun approvalRuntimeTelemetry() = ApprovalRuntimeTelemetry(
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
        approvalCardTelemetry = ApprovalCardTelemetry(
            hasPendingApproval = false
        ),
        projectApprovalCardTelemetry = ProjectApprovalCardTelemetry()
    )

    private fun pendingAsk() = AskPromptPresentationTestFixtures.pendingAsk()

    private fun workflowPlan() = PendingExecutionPresentationTestFixtures.workflowPlan()

    private fun clarificationRequest() = PendingExecutionPresentationTestFixtures.clarificationRequest()
}
