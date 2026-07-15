package com.murong.agent.ui.tools

import com.murong.agent.core.loop.CheckpointRecoveryRecordUi
import com.murong.agent.core.loop.ClarificationRequestUi
import com.murong.agent.core.loop.ClarificationSource
import com.murong.agent.core.loop.ConversationCheckpointPromptSnapshotUi
import com.murong.agent.core.loop.ConversationCheckpointScope
import com.murong.agent.core.loop.ConversationCheckpointSource
import com.murong.agent.core.loop.ConversationCheckpointUi
import com.murong.agent.core.loop.FileChangeRecordUi
import com.murong.agent.core.loop.PendingAskRequestUi
import com.murong.agent.core.loop.AskOptionUi
import com.murong.agent.core.loop.AskQuestionUi
import com.murong.agent.core.loop.WorkflowPlanUi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CheckpointToolsPresentationTest {

    @Test
    fun buildCheckpointToolsPresentation_mergesCheckpointListAndFileChangeDetails() {
        val presentation = buildCheckpointToolsPresentation(
            checkpoints = listOf(
                ConversationCheckpointUi(
                    id = "chk-1",
                    messageIndex = 3,
                    createdAt = 1711111111000,
                    summary = "修改 2 个文件",
                    changedFiles = listOf("a.kt", "b.kt"),
                    scope = ConversationCheckpointScope.BOTH,
                    source = ConversationCheckpointSource.ROLLBACK
                )
            ),
            fileChanges = listOf(
                FileChangeRecordUi(
                    id = "rec-1",
                    path = "a.kt",
                    operation = "update",
                    beforeContent = "old",
                    afterContent = "new",
                    diffPreview = "@@ -1 +1 @@",
                    changedAt = 1711111112000,
                    checkpointId = "chk-1"
                ),
                FileChangeRecordUi(
                    id = "rec-2",
                    path = "b.kt",
                    operation = "create",
                    afterContent = "content",
                    changedAt = 1711111113000,
                    checkpointId = "chk-1"
                )
            ),
            recentRecoveryRecords = listOf(
                CheckpointRecoveryRecordUi(
                    id = "recovery-1",
                    checkpointId = "chk-1",
                    checkpointSummary = "恢复代码/对话: 修改 2 个文件",
                    scope = ConversationCheckpointScope.BOTH,
                    restoredFileCount = 2,
                    targetMessageIndex = 3,
                    timestamp = 1711111114000
                )
            )
        )

        assertEquals("1", presentation.checkpointCountLabel)
        assertEquals("2", presentation.fileChangeCountLabel)
        assertEquals("1", presentation.recoveryCountLabel)
        assertEquals("最近恢复以 全部 1 为主", presentation.recoveryOverviewLabel)
        assertEquals("修改 2 个文件", presentation.checkpoints.single().title)
        assertEquals(ConversationCheckpointScope.BOTH, presentation.checkpoints.single().rollbackScope)
        assertEquals("代码/对话", presentation.checkpoints.single().scopeLabel)
        assertEquals("回滚生成", presentation.checkpoints.single().sourceLabel)
        assertEquals("恢复全部", presentation.checkpoints.single().rollbackLabel)
        assertTrue(presentation.checkpoints.single().rollbackDescription.contains("文件修改和对话上下文"))
        assertTrue(presentation.checkpoints.single().subtitle.contains("代码/对话"))
        assertTrue(presentation.checkpoints.single().subtitle.contains("回滚生成"))
        assertTrue(presentation.checkpoints.single().detailSubtitle.contains("恢复域: 代码/对话"))
        assertTrue(presentation.checkpoints.single().detailSubtitle.contains("来源: 回滚生成"))
        assertEquals(listOf("rec-1", "rec-2"), presentation.checkpoints.single().recordIds)
        assertEquals("最近恢复全部", presentation.recoveries.single().title)
        assertTrue(presentation.recoveries.single().subtitle.contains("代码/对话"))
        assertEquals("修改 2 个文件", presentation.recoveries.single().summaryPreview)
        assertEquals("chk-1", presentation.recoveries.single().checkpointId)
        assertTrue(presentation.recoveries.single().detailContent.contains("恢复摘要: 修改 2 个文件"))
        assertEquals("update", presentation.fileChanges.first().title)
        assertEquals("a.kt", presentation.fileChanges.first().subtitle)
        assertEquals("@@ -1 +1 @@", presentation.fileChanges.first().detailContent)
    }

    @Test
    fun resolveCheckpointRecordPresentations_preservesCheckpointScopedRecordOrder() {
        val presentation = buildCheckpointToolsPresentation(
            checkpoints = listOf(
                ConversationCheckpointUi(
                    id = "chk-1",
                    messageIndex = 1,
                    summary = "批次一"
                )
            ),
            fileChanges = listOf(
                FileChangeRecordUi(
                    id = "rec-1",
                    path = "a.kt",
                    operation = "update",
                    checkpointId = "chk-1"
                ),
                FileChangeRecordUi(
                    id = "rec-2",
                    path = "b.kt",
                    operation = "delete",
                    checkpointId = null
                ),
                FileChangeRecordUi(
                    id = "rec-3",
                    path = "c.kt",
                    operation = "create",
                    checkpointId = "chk-1"
                )
            )
        )

        val checkpoint = assertNotNull(findCheckpointToolPresentation(presentation, "chk-1"))
        val records = resolveCheckpointRecordPresentations(presentation, checkpoint)

        assertEquals(listOf("rec-1", "rec-3"), records.map { it.id })
        assertTrue(records.all { it.checkpointId == "chk-1" })
    }

    @Test
    fun findCheckpointRecoveryToolPresentation_returnsMatchingRecoveryRecord() {
        val presentation = buildCheckpointToolsPresentation(
            checkpoints = emptyList(),
            fileChanges = emptyList(),
            recentRecoveryRecords = listOf(
                CheckpointRecoveryRecordUi(
                    id = "recovery-1",
                    checkpointId = "chk-1",
                    checkpointSummary = "恢复对话: 对话推进",
                    scope = ConversationCheckpointScope.CONVERSATION,
                    restoredFileCount = 0,
                    targetMessageIndex = 2,
                    timestamp = 100L
                )
            )
        )

        val recovery = assertNotNull(findCheckpointRecoveryToolPresentation(presentation, "recovery-1"))

        assertEquals("最近恢复对话", recovery.title)
        assertEquals("对话推进", recovery.summaryPreview)
        assertEquals(null, recovery.checkpointId)
        assertTrue(recovery.detailSubtitle.contains("目标消息位置: 3"))
        assertTrue(recovery.detailContent.contains("恢复摘要: 对话推进"))
    }

    @Test
    fun formatCheckpointRecoveryPresentationSummary_normalizesLegacyFallbackSummary() {
        val bothSummary = formatCheckpointRecoveryPresentationSummary(
            CheckpointRecoveryRecordUi(
                id = "recovery-both",
                checkpointId = "chk-1",
                checkpointSummary = "恢复代码/对话: 修改 2 个文件",
                scope = ConversationCheckpointScope.BOTH,
                restoredFileCount = 2,
                targetMessageIndex = 1,
                timestamp = 100L
            )
        )
        val conversationSummary = formatCheckpointRecoveryPresentationSummary(
            CheckpointRecoveryRecordUi(
                id = "recovery-conversation",
                checkpointId = "chk-2",
                checkpointSummary = "恢复对话: 对话推进",
                scope = ConversationCheckpointScope.CONVERSATION,
                restoredFileCount = 0,
                targetMessageIndex = 1,
                timestamp = 100L
            )
        )
        val blankSummary = formatCheckpointRecoveryPresentationSummary(
            CheckpointRecoveryRecordUi(
                id = "recovery-blank",
                checkpointId = "chk-3",
                checkpointSummary = "   ",
                scope = ConversationCheckpointScope.CODE,
                restoredFileCount = 1,
                targetMessageIndex = 1,
                timestamp = 100L
            )
        )

        assertEquals("修改 2 个文件", bothSummary)
        assertEquals("对话推进", conversationSummary)
        assertEquals("已恢复代码修改", blankSummary)
    }

    @Test
    fun buildCheckpointRecoveryOverviewLabel_summarizesMixedRecoveryScopes() {
        val label = buildCheckpointRecoveryOverviewLabel(
            listOf(
                CheckpointRecoveryRecordUi(
                    id = "recovery-both",
                    checkpointId = "chk-1",
                    checkpointSummary = "恢复代码/对话: 修改 2 个文件",
                    scope = ConversationCheckpointScope.BOTH,
                    restoredFileCount = 2,
                    targetMessageIndex = 1,
                    timestamp = 100L
                ),
                CheckpointRecoveryRecordUi(
                    id = "recovery-conversation-1",
                    checkpointId = "chk-2",
                    checkpointSummary = "恢复对话: 对话推进",
                    scope = ConversationCheckpointScope.CONVERSATION,
                    restoredFileCount = 0,
                    targetMessageIndex = 2,
                    timestamp = 101L
                ),
                CheckpointRecoveryRecordUi(
                    id = "recovery-conversation-2",
                    checkpointId = "chk-3",
                    checkpointSummary = "恢复对话: 继续确认",
                    scope = ConversationCheckpointScope.CONVERSATION,
                    restoredFileCount = 0,
                    targetMessageIndex = 3,
                    timestamp = 102L
                ),
                CheckpointRecoveryRecordUi(
                    id = "recovery-code",
                    checkpointId = "chk-4",
                    checkpointSummary = "恢复代码: 回滚 1 个文件",
                    scope = ConversationCheckpointScope.CODE,
                    restoredFileCount = 1,
                    targetMessageIndex = 4,
                    timestamp = 103L
                )
            )
        )

        assertEquals("最近恢复分布: 全部 1 · 对话 2 · 代码 1", label)
    }

    @Test
    fun buildCheckpointToolsPresentation_sortsRecoveriesByLatestTimestamp() {
        val presentation = buildCheckpointToolsPresentation(
            checkpoints = emptyList(),
            fileChanges = emptyList(),
            recentRecoveryRecords = listOf(
                CheckpointRecoveryRecordUi(
                    id = "recovery-old",
                    checkpointId = "chk-1",
                    checkpointSummary = "恢复对话: 较早记录",
                    scope = ConversationCheckpointScope.CONVERSATION,
                    restoredFileCount = 0,
                    targetMessageIndex = 1,
                    timestamp = 100L
                ),
                CheckpointRecoveryRecordUi(
                    id = "recovery-new",
                    checkpointId = "chk-2",
                    checkpointSummary = "恢复代码: 最新记录",
                    scope = ConversationCheckpointScope.CODE,
                    restoredFileCount = 1,
                    targetMessageIndex = 2,
                    timestamp = 300L
                ),
                CheckpointRecoveryRecordUi(
                    id = "recovery-mid",
                    checkpointId = "chk-3",
                    checkpointSummary = "恢复代码/对话: 中间记录",
                    scope = ConversationCheckpointScope.BOTH,
                    restoredFileCount = 2,
                    targetMessageIndex = 3,
                    timestamp = 200L
                )
            )
        )

        assertEquals(
            listOf("recovery-new", "recovery-mid", "recovery-old"),
            presentation.recoveries.map { it.id }
        )
        assertEquals(listOf(300L, 200L, 100L), presentation.recoveries.map { it.timestamp })
    }

    @Test
    fun buildCheckpointRecoveryTimelineGroups_groupsByDayAndBuildsTypeSummary() {
        val groups = buildCheckpointRecoveryTimelineGroups(
            listOf(
                CheckpointRecoveryToolPresentation(
                    id = "recovery-a",
                    title = "最近恢复全部",
                    subtitle = "代码/对话 · 文件恢复 2",
                    detailTitle = "最近恢复全部",
                    detailSubtitle = "detail-a",
                    detailContent = "content-a",
                    scope = ConversationCheckpointScope.BOTH,
                    summaryPreview = "全部恢复",
                    timestamp = 1720137600000L
                ),
                CheckpointRecoveryToolPresentation(
                    id = "recovery-b",
                    title = "最近恢复对话",
                    subtitle = "对话恢复 · 文件恢复 0",
                    detailTitle = "最近恢复对话",
                    detailSubtitle = "detail-b",
                    detailContent = "content-b",
                    scope = ConversationCheckpointScope.CONVERSATION,
                    summaryPreview = "恢复对话",
                    timestamp = 1720137601000L
                ),
                CheckpointRecoveryToolPresentation(
                    id = "recovery-c",
                    title = "最近回滚代码",
                    subtitle = "代码恢复 · 文件恢复 1",
                    detailTitle = "最近回滚代码",
                    detailSubtitle = "detail-c",
                    detailContent = "content-c",
                    scope = ConversationCheckpointScope.CODE,
                    summaryPreview = "恢复代码",
                    timestamp = 1720051200000L
                )
            )
        )

        assertEquals(2, groups.size)
        assertEquals("07-05", groups[0].dayLabel)
        assertEquals("全部 1 · 对话 1", groups[0].summaryLabel)
        assertEquals(listOf("recovery-b", "recovery-a"), groups[0].records.map { it.id })
        assertEquals("07-04", groups[1].dayLabel)
        assertEquals("代码 1", groups[1].summaryLabel)
        assertEquals(listOf("recovery-c"), groups[1].records.map { it.id })
    }

    @Test
    fun formatCheckpointPresentationTitle_prefersTypedPromptMetadata() {
        val clarificationCheckpoint = ConversationCheckpointUi(
            id = "chk-clarify",
            messageIndex = 1,
            summary = "交互状态: 等待澄清回答",
            scope = ConversationCheckpointScope.CONVERSATION,
            source = ConversationCheckpointSource.TOOL_EXECUTION,
            promptSnapshot = ConversationCheckpointPromptSnapshotUi(
                pendingClarificationRequest = ClarificationRequestUi(
                    goal = "修复问题",
                    question = "先修哪一块？",
                    source = ClarificationSource.AUTO_INTERRUPT
                )
            )
        )
        val askCheckpoint = ConversationCheckpointUi(
            id = "chk-ask",
            messageIndex = 1,
            summary = "交互状态: 等待用户确认",
            scope = ConversationCheckpointScope.CONVERSATION,
            source = ConversationCheckpointSource.TOOL_EXECUTION,
            promptSnapshot = ConversationCheckpointPromptSnapshotUi(
                pendingAskRequest = PendingAskRequestUi(
                    title = "需要确认",
                    questions = listOf(
                        AskQuestionUi(
                            id = "q-1",
                            question = "继续吗？",
                            options = listOf(AskOptionUi("继续"))
                        )
                    )
                )
            )
        )
        val planCheckpoint = ConversationCheckpointUi(
            id = "chk-plan",
            messageIndex = 1,
            summary = "交互状态: 已生成执行计划",
            scope = ConversationCheckpointScope.CONVERSATION,
            source = ConversationCheckpointSource.TOOL_EXECUTION,
            promptSnapshot = ConversationCheckpointPromptSnapshotUi(
                pendingWorkflowPlan = WorkflowPlanUi(
                    goal = "修复问题",
                    summary = "先跑测试再修复"
                )
            )
        )

        assertEquals("执行中等待澄清回答", formatCheckpointPresentationTitle(clarificationCheckpoint))
        assertEquals("等待用户确认", formatCheckpointPresentationTitle(askCheckpoint))
        assertEquals("已生成执行计划", formatCheckpointPresentationTitle(planCheckpoint))
        assertEquals("澄清状态", formatCheckpointPresentationKindLabel(clarificationCheckpoint))
        assertEquals("确认状态", formatCheckpointPresentationKindLabel(askCheckpoint))
        assertEquals("计划状态", formatCheckpointPresentationKindLabel(planCheckpoint))
        assertTrue(
            formatCheckpointPresentationPreview(clarificationCheckpoint)
                ?.contains("先修哪一块")
                == true
        )
        assertTrue(
            formatCheckpointPresentationPreview(askCheckpoint)
                ?.contains("继续吗")
                == true
        )
        assertTrue(
            formatCheckpointPresentationPreview(planCheckpoint)
                ?.contains("先跑测试再修复")
                == true
        )
    }

    @Test
    fun buildCheckpointToolsPresentation_buildsTypedPromptDetailContent() {
        val presentation = buildCheckpointToolsPresentation(
            checkpoints = listOf(
                ConversationCheckpointUi(
                    id = "chk-clarify",
                    messageIndex = 1,
                    summary = "fallback-clarify",
                    scope = ConversationCheckpointScope.CONVERSATION,
                    source = ConversationCheckpointSource.TOOL_EXECUTION,
                    promptSnapshot = ConversationCheckpointPromptSnapshotUi(
                        pendingClarificationRequest = ClarificationRequestUi(
                            goal = "修复登录问题",
                            question = "先看崩溃还是先看接口？",
                            source = ClarificationSource.AUTO_ROUTE
                        )
                    )
                ),
                ConversationCheckpointUi(
                    id = "chk-ask",
                    messageIndex = 1,
                    summary = "fallback-ask",
                    scope = ConversationCheckpointScope.CONVERSATION,
                    source = ConversationCheckpointSource.TOOL_EXECUTION,
                    promptSnapshot = ConversationCheckpointPromptSnapshotUi(
                        pendingAskRequest = PendingAskRequestUi(
                            title = "需要确认的改动范围",
                            questions = listOf(
                                AskQuestionUi(
                                    id = "q-1",
                                    question = "要不要一起改测试？",
                                    options = listOf(
                                        AskOptionUi("一起改"),
                                        AskOptionUi("先不改")
                                    )
                                )
                            )
                        )
                    )
                ),
                ConversationCheckpointUi(
                    id = "chk-plan",
                    messageIndex = 1,
                    summary = "fallback-plan",
                    scope = ConversationCheckpointScope.CONVERSATION,
                    source = ConversationCheckpointSource.TOOL_EXECUTION,
                    promptSnapshot = ConversationCheckpointPromptSnapshotUi(
                        pendingWorkflowPlan = WorkflowPlanUi(
                            goal = "修复登录问题",
                            summary = "先复现，再补日志，最后修复",
                            nextStepHint = "先跑登录相关单测"
                        )
                    )
                )
            ),
            fileChanges = emptyList()
        )

        val clarificationDetail = assertNotNull(
            findCheckpointToolPresentation(presentation, "chk-clarify")
        ).detailContent
        val askDetail = assertNotNull(
            findCheckpointToolPresentation(presentation, "chk-ask")
        ).detailContent
        val planDetail = assertNotNull(
            findCheckpointToolPresentation(presentation, "chk-plan")
        ).detailContent

        assertTrue(clarificationDetail.contains("澄清问题: 先看崩溃还是先看接口？"))
        assertTrue(clarificationDetail.contains("澄清来源: 自动分流"))
        assertTrue(askDetail.contains("卡片标题: 需要确认的改动范围"))
        assertTrue(askDetail.contains("可选项: 一起改 / 先不改"))
        assertTrue(planDetail.contains("计划摘要: 先复现，再补日志，最后修复"))
        assertTrue(planDetail.contains("下一步建议: 先跑登录相关单测"))
    }

    @Test
    fun buildCheckpointToolsPresentation_usesTypedCheckpointMetadataForRecoveryDetail() {
        val presentation = buildCheckpointToolsPresentation(
            checkpoints = listOf(
                ConversationCheckpointUi(
                    id = "chk-ask",
                    messageIndex = 1,
                    summary = "fallback-summary",
                    scope = ConversationCheckpointScope.CONVERSATION,
                    source = ConversationCheckpointSource.TOOL_EXECUTION,
                    promptSnapshot = ConversationCheckpointPromptSnapshotUi(
                        pendingAskRequest = PendingAskRequestUi(
                            title = "需要确认",
                            questions = listOf(
                                AskQuestionUi(
                                    id = "q-1",
                                    question = "是否继续执行数据库迁移？",
                                    options = listOf(AskOptionUi("继续"))
                                )
                            )
                        )
                    )
                )
            ),
            fileChanges = emptyList(),
            recentRecoveryRecords = listOf(
                CheckpointRecoveryRecordUi(
                    id = "recovery-1",
                    checkpointId = "chk-ask",
                    checkpointSummary = "旧恢复摘要",
                    scope = ConversationCheckpointScope.CONVERSATION,
                    restoredFileCount = 0,
                    targetMessageIndex = 1,
                    timestamp = 100L
                )
            )
        )

        val recovery = assertNotNull(findCheckpointRecoveryToolPresentation(presentation, "recovery-1"))
        assertTrue(recovery.detailContent.contains("恢复摘要: 等待用户确认"))
        assertTrue(recovery.detailContent.contains("提问卡片: 是否继续执行数据库迁移？"))
        assertTrue(recovery.detailContent.contains("恢复动作: 最近恢复对话"))
        assertEquals("等待用户确认 · 提问卡片: 是否继续执行数据库迁移？", recovery.summaryPreview)
        assertEquals("chk-ask", recovery.checkpointId)
    }
}
