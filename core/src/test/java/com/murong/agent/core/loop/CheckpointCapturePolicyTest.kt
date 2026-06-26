package com.murong.agent.core.loop

import com.murong.agent.core.tool.ToolFileChange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CheckpointCapturePolicyTest {

    @Test
    fun captureTurnCheckpointFileChanges_reusesCheckpointAndPreservesFirstBeforeContent() {
        val firstUpdate = captureTurnCheckpointFileChanges(
            captureState = TurnCheckpointCaptureState(),
            toolName = "file",
            fileChanges = listOf(
                ToolFileChange(
                    path = "src/A.kt",
                    operation = "write",
                    beforeContent = "v1",
                    afterContent = "v2",
                    changedAt = 100L
                )
            ),
            existingCheckpoint = null,
            messageIndex = 12,
            checkpointIdFactory = { "chk-1" }
        )
        val secondUpdate = captureTurnCheckpointFileChanges(
            captureState = assertNotNull(firstUpdate).nextState,
            toolName = "code_edit",
            fileChanges = listOf(
                ToolFileChange(
                    path = "src/A.kt",
                    operation = "search_replace",
                    beforeContent = "v2",
                    afterContent = "v3",
                    changedAt = 200L
                ),
                ToolFileChange(
                    path = "src/B.kt",
                    operation = "create",
                    beforeContent = null,
                    afterContent = "new",
                    changedAt = 210L
                )
            ),
            existingCheckpoint = firstUpdate.checkpoint,
            messageIndex = 12,
            checkpointIdFactory = { "unused" }
        )

        assertNotNull(secondUpdate)
        assertEquals("chk-1", secondUpdate.checkpoint.id)
        assertEquals(listOf("src/A.kt", "src/B.kt"), secondUpdate.checkpoint.changedFiles)
        assertEquals(ConversationCheckpointScope.CODE, secondUpdate.checkpoint.scope)
        assertEquals(ConversationCheckpointSource.TOOL_EXECUTION, secondUpdate.checkpoint.source)
        assertEquals(setOf("file", "code_edit"), secondUpdate.nextState.toolNames)
        assertEquals("v1", secondUpdate.records.first().beforeContent)
        assertEquals("v3", secondUpdate.records.first().afterContent)
        assertEquals("chk-1-1", secondUpdate.records.first().id)
    }

    @Test
    fun resolveCheckpointRollbackRecords_prefersLatestChangeFirst() {
        val ordered = resolveCheckpointRollbackRecords(
            listOf(
                FileChangeRecordUi(
                    id = "rec-1",
                    path = "src/A.kt",
                    operation = "write",
                    changedAt = 100L,
                    checkpointId = "chk-1"
                ),
                FileChangeRecordUi(
                    id = "rec-2",
                    path = "src/A.kt",
                    operation = "search_replace",
                    changedAt = 200L,
                    checkpointId = "chk-1"
                )
            )
        )

        assertEquals(listOf("rec-2", "rec-1"), ordered.map { it.id })
    }

    @Test
    fun projectConversationRollbackState_keepsOnlyStateBeforeTargetMessage() {
        val projection = projectConversationRollbackState(
            state = SessionState(
                messages = listOf(
                    ChatMessageUi(id = 1L, role = "user", content = "u1"),
                    ChatMessageUi(
                        id = 2L,
                        role = "assistant",
                        content = "a1",
                        subagentRunId = "run-keep",
                        subagentBatchId = "batch-keep"
                    ),
                    ChatMessageUi(id = 3L, role = "tool_exec", content = "tool"),
                    ChatMessageUi(
                        id = 4L,
                        role = "assistant",
                        content = "a2",
                        subagentRunId = "run-drop",
                        subagentBatchId = "batch-drop"
                    )
                ),
                subagentRuns = listOf(
                    SubagentRunUi(runId = "run-keep", status = "completed", goal = "keep", model = "demo"),
                    SubagentRunUi(runId = "run-drop", status = "completed", goal = "drop", model = "demo")
                ),
                subagentBatches = listOf(
                    SubagentBatchUi(batchId = "batch-keep", parentGoal = "keep", label = "keep", status = "completed"),
                    SubagentBatchUi(batchId = "batch-drop", parentGoal = "drop", label = "drop", status = "completed")
                ),
                compressionSnapshots = listOf(
                    ContextCompressionUi(
                        id = "cmp-1",
                        version = 1,
                        summary = "old",
                        sourceMessageCount = 2,
                        sourceEndMessageId = 2L,
                        sourceEndMessageIndex = 1,
                        active = false
                    ),
                    ContextCompressionUi(
                        id = "cmp-2",
                        version = 2,
                        summary = "new",
                        sourceMessageCount = 4,
                        sourceEndMessageId = 4L,
                        sourceEndMessageIndex = 3,
                        active = true
                    )
                ),
                compressionSnapshot = ContextCompressionUi(
                    id = "cmp-2",
                    version = 2,
                    summary = "new",
                    sourceMessageCount = 4,
                    sourceEndMessageId = 4L,
                    sourceEndMessageIndex = 3,
                    active = true
                ),
                checkpoints = listOf(
                    ConversationCheckpointUi(
                        id = "chk-drop",
                        messageIndex = 3,
                        createdAt = 30L,
                        summary = "drop"
                    ),
                    ConversationCheckpointUi(
                        id = "chk-keep",
                        messageIndex = 1,
                        createdAt = 10L,
                        summary = "keep"
                    )
                ),
                fileChanges = listOf(
                    FileChangeRecordUi(id = "rec-keep", path = "src/A.kt", operation = "write", checkpointId = "chk-keep"),
                    FileChangeRecordUi(id = "rec-drop", path = "src/B.kt", operation = "write", checkpointId = "chk-drop"),
                    FileChangeRecordUi(id = "rec-free", path = "src/C.kt", operation = "read", checkpointId = null)
                )
            ),
            targetExclusiveIndex = 2
        )

        assertEquals(listOf(1L, 2L), projection.messages.map { it.id })
        assertEquals(listOf("run-keep"), projection.subagentRuns.map { it.runId })
        assertEquals(listOf("batch-keep"), projection.subagentBatches.map { it.batchId })
        assertEquals(listOf("chk-keep"), projection.checkpoints.map { it.id })
        assertEquals(listOf("rec-keep", "rec-free"), projection.fileChanges.map { it.id })
        assertNotNull(projection.compressionSnapshot)
        assertEquals("cmp-1", projection.compressionSnapshot.id)
        assertEquals(listOf("cmp-1"), projection.compressionSnapshots.map { it.id })
        assertTrue(projection.compressionSnapshots.single().active)
    }

    @Test
    fun finalizeTurnCheckpoint_promotesExistingFileTurnCheckpointToBothScope() {
        val checkpoint = finalizeTurnCheckpoint(
            captureState = TurnCheckpointCaptureState(
                checkpointId = "chk-1",
                toolNames = setOf("file"),
                changedFiles = listOf("src/A.kt")
            ),
            checkpoints = listOf(
                ConversationCheckpointUi(
                    id = "chk-1",
                    messageIndex = 1,
                    createdAt = 10L,
                    summary = "file 修改了 1 个文件: A.kt",
                    changedFiles = listOf("src/A.kt"),
                    scope = ConversationCheckpointScope.CODE,
                    source = ConversationCheckpointSource.TOOL_EXECUTION,
                    toolNames = listOf("file")
                )
            ),
            messages = listOf(
                ChatMessageUi(id = 1L, role = "user", content = "hello", timestamp = 1L),
                ChatMessageUi(id = 2L, role = "assistant", content = "done", timestamp = 2L),
                ChatMessageUi(id = 3L, role = "tool_exec", content = "write", timestamp = 3L),
                ChatMessageUi(id = 4L, role = "assistant", content = "final", timestamp = 4L)
            ),
            checkpointIdFactory = { "unused" }
        )

        assertNotNull(checkpoint)
        assertEquals("chk-1", checkpoint.id)
        assertEquals(3, checkpoint.messageIndex)
        assertEquals(ConversationCheckpointScope.BOTH, checkpoint.scope)
        assertEquals(listOf("src/A.kt"), checkpoint.changedFiles)
        assertEquals(listOf("file"), checkpoint.toolNames)
    }

    @Test
    fun finalizeTurnCheckpoint_createsConversationScopedCheckpointWhenTurnHasNoFileChanges() {
        val checkpoint = finalizeTurnCheckpoint(
            captureState = TurnCheckpointCaptureState(),
            checkpoints = emptyList(),
            messages = listOf(
                ChatMessageUi(id = 1L, role = "user", content = "hello", timestamp = 1L),
                ChatMessageUi(
                    id = 2L,
                    role = "assistant",
                    content = "这里是总结\n下一步继续",
                    timestamp = 20L
                )
            ),
            checkpointIdFactory = { "chk-conversation" }
        )

        assertNotNull(checkpoint)
        assertEquals("chk-conversation", checkpoint.id)
        assertEquals(1, checkpoint.messageIndex)
        assertEquals(20L, checkpoint.createdAt)
        assertEquals(ConversationCheckpointScope.CONVERSATION, checkpoint.scope)
        assertEquals(ConversationCheckpointSource.TOOL_EXECUTION, checkpoint.source)
        assertEquals("对话推进: 这里是总结 下一步继续", checkpoint.summary)
        assertTrue(checkpoint.changedFiles.isEmpty())
    }

    @Test
    fun resolveCheckpointRecoveryPlan_rejectsCodeRollbackForConversationOnlyCheckpoint() {
        val error = assertFailsWith<IllegalArgumentException> {
            resolveCheckpointRecoveryPlan(
                state = SessionState(
                    messages = listOf(
                        ChatMessageUi(id = 1L, role = "user", content = "hello")
                    )
                ),
                checkpoint = ConversationCheckpointUi(
                    id = "chk-conversation",
                    messageIndex = 0,
                    createdAt = 10L,
                    summary = "conversation",
                    scope = ConversationCheckpointScope.CONVERSATION
                ),
                requestedScope = ConversationCheckpointScope.CODE
            )
        }

        assertEquals("该检查点只有对话可恢复，不能回滚代码", error.message)
    }

    @Test
    fun resolveCheckpointRecoveryPlan_rejectsConversationRecoveryWhenCheckpointIsCurrentBoundary() {
        val checkpoint = ConversationCheckpointUi(
            id = "chk-latest",
            messageIndex = 1,
            createdAt = 10L,
            summary = "latest",
            scope = ConversationCheckpointScope.CONVERSATION
        )

        val error = assertFailsWith<IllegalArgumentException> {
            resolveCheckpointRecoveryPlan(
                state = SessionState(
                    messages = listOf(
                        ChatMessageUi(id = 1L, role = "user", content = "hello"),
                        ChatMessageUi(id = 2L, role = "assistant", content = "done")
                    ),
                    checkpoints = listOf(checkpoint)
                ),
                checkpoint = checkpoint,
                requestedScope = ConversationCheckpointScope.CONVERSATION
            )
        }

        assertEquals("该检查点已经是当前对话边界，无需恢复", error.message)
    }

    @Test
    fun resolveCheckpointRecoveryPlan_rejectsCodeCheckpointWithoutFileRecords() {
        val checkpoint = ConversationCheckpointUi(
            id = "chk-code",
            messageIndex = 0,
            createdAt = 10L,
            summary = "code",
            scope = ConversationCheckpointScope.CODE
        )

        val error = assertFailsWith<IllegalArgumentException> {
            resolveCheckpointRecoveryPlan(
                state = SessionState(
                    messages = listOf(
                        ChatMessageUi(id = 1L, role = "user", content = "hello")
                    ),
                    checkpoints = listOf(checkpoint)
                ),
                checkpoint = checkpoint,
                requestedScope = ConversationCheckpointScope.CODE
            )
        }

        assertEquals("该检查点没有可回滚的代码修改", error.message)
    }

    @Test
    fun resolveCheckpointRecoveryPlan_keepsFutureRecordsForBothRecovery() {
        val checkpoint = ConversationCheckpointUi(
            id = "chk-base",
            messageIndex = 1,
            createdAt = 10L,
            summary = "base",
            scope = ConversationCheckpointScope.BOTH
        )
        val laterCheckpoint = ConversationCheckpointUi(
            id = "chk-later",
            messageIndex = 3,
            createdAt = 20L,
            summary = "later",
            scope = ConversationCheckpointScope.CODE
        )

        val plan = resolveCheckpointRecoveryPlan(
            state = SessionState(
                messages = listOf(
                    ChatMessageUi(id = 1L, role = "user", content = "u1"),
                    ChatMessageUi(id = 2L, role = "assistant", content = "a1"),
                    ChatMessageUi(id = 3L, role = "user", content = "u2"),
                    ChatMessageUi(id = 4L, role = "assistant", content = "a2")
                ),
                checkpoints = listOf(laterCheckpoint, checkpoint),
                fileChanges = listOf(
                    FileChangeRecordUi(
                        id = "rec-base",
                        path = "src/Base.kt",
                        operation = "write",
                        changedAt = 10L,
                        checkpointId = "chk-base"
                    ),
                    FileChangeRecordUi(
                        id = "rec-later",
                        path = "src/Later.kt",
                        operation = "write",
                        changedAt = 30L,
                        checkpointId = "chk-later"
                    )
                )
            ),
            checkpoint = checkpoint,
            requestedScope = ConversationCheckpointScope.BOTH
        )

        assertEquals(2, plan.targetExclusiveIndex)
        assertEquals(listOf("rec-base"), plan.checkpointRecords.map { it.id })
        assertEquals(listOf("rec-later"), plan.futureRecords.map { it.id })
    }

    @Test
    fun buildCheckpointRecoveryEvent_usesRequestedScopeForRollbackMetadata() {
        val checkpoint = ConversationCheckpointUi(
            id = "chk-both",
            messageIndex = 2,
            createdAt = 10L,
            summary = "assistant turn",
            scope = ConversationCheckpointScope.BOTH,
            source = ConversationCheckpointSource.TOOL_EXECUTION
        )

        val rollbackEvent = buildCheckpointRecoveryEvent(
            rollbackCheckpointId = "chk-rollback",
            checkpoint = checkpoint,
            restoredScope = ConversationCheckpointScope.CODE,
            messageIndex = 5,
            createdAt = 99L,
            changedFiles = listOf("src/A.kt")
        )

        assertEquals("chk-rollback", rollbackEvent.id)
        assertEquals(5, rollbackEvent.messageIndex)
        assertEquals(99L, rollbackEvent.createdAt)
        assertEquals(ConversationCheckpointKind.ROLLBACK, rollbackEvent.kind)
        assertEquals(ConversationCheckpointScope.CODE, rollbackEvent.scope)
        assertEquals(ConversationCheckpointSource.ROLLBACK, rollbackEvent.source)
        assertEquals("回滚代码: assistant turn", rollbackEvent.summary)
        assertEquals(listOf("src/A.kt"), rollbackEvent.changedFiles)
        assertEquals(listOf("rollback"), rollbackEvent.toolNames)
    }

    @Test
    fun buildCheckpointRecoverySystemMessage_usesScopeSpecificCopy() {
        val checkpoint = ConversationCheckpointUi(
            id = "chk-1",
            messageIndex = 1,
            createdAt = 10L,
            summary = "对话推进: hello",
            scope = ConversationCheckpointScope.CONVERSATION
        )

        assertEquals(
            "已按检查点恢复对话：对话推进: hello",
            buildCheckpointRecoverySystemMessage(
                checkpoint = checkpoint,
                restoredScope = ConversationCheckpointScope.CONVERSATION
            )
        )
        assertEquals(
            "已按检查点恢复代码与对话：对话推进: hello",
            buildCheckpointRecoverySystemMessage(
                checkpoint = checkpoint,
                restoredScope = ConversationCheckpointScope.BOTH
            )
        )
    }

    @Test
    fun buildCheckpointRecoverySystemMessage_whenRecentSessionHistoryExists_appendsCompactHistorySuffix() {
        val checkpoint = ConversationCheckpointUi(
            id = "chk-2",
            messageIndex = 2,
            createdAt = 10L,
            summary = "发布前回滚",
            scope = ConversationCheckpointScope.BOTH
        )

        val message = buildCheckpointRecoverySystemMessage(
            checkpoint = checkpoint,
            restoredScope = ConversationCheckpointScope.BOTH,
            recentSessionHistoryClue = SessionHistoryReferenceClueUi(
                queries = listOf("发布"),
                messageReferences = listOf("session-release#34"),
                snippets = listOf("发布 smoke test 需要覆盖登录校验")
            )
        )

        assertEquals(
            "已按检查点恢复代码与对话：发布前回滚（沿用历史线索：查询 发布；消息 session-release#34）",
            message
        )
    }

    @Test
    fun buildCheckpointRecoveryRecord_capturesScopeAndRestoredFileCount() {
        val checkpoint = ConversationCheckpointUi(
            id = "chk-1",
            messageIndex = 4,
            createdAt = 10L,
            summary = "修改 2 个文件",
            scope = ConversationCheckpointScope.BOTH
        )

        val record = buildCheckpointRecoveryRecord(
            checkpoint = checkpoint,
            restoredScope = ConversationCheckpointScope.BOTH,
            restoredFileCount = 2,
            timestamp = 88L
        )

        assertEquals("chk-1", record.checkpointId)
        assertEquals("恢复代码/对话: 修改 2 个文件", record.checkpointSummary)
        assertEquals(ConversationCheckpointScope.BOTH, record.scope)
        assertEquals(2, record.restoredFileCount)
        assertEquals(4, record.targetMessageIndex)
        assertEquals(88L, record.timestamp)
    }

    @Test
    fun buildCheckpointRecoveryRecord_whenRecentSessionHistoryExists_reusesCompactHistorySummary() {
        val checkpoint = ConversationCheckpointUi(
            id = "chk-3",
            messageIndex = 6,
            createdAt = 10L,
            summary = "恢复检查点",
            scope = ConversationCheckpointScope.CONVERSATION
        )

        val record = buildCheckpointRecoveryRecord(
            checkpoint = checkpoint,
            restoredScope = ConversationCheckpointScope.CONVERSATION,
            restoredFileCount = 0,
            recentSessionHistoryClue = SessionHistoryReferenceClueUi(
                queries = listOf("登录"),
                sessionIds = listOf("session-login"),
                snippets = listOf("登录态过期后需要补 token 刷新")
            ),
            timestamp = 99L
        )

        assertEquals(
            "恢复对话: 恢复检查点（沿用历史线索：查询 登录；会话 session-login）",
            record.checkpointSummary
        )
        assertEquals(99L, record.timestamp)
    }

    @Test
    fun resolveCheckpointRecoveryPlan_allowsCurrentBoundaryRecoveryWhenPromptSnapshotDiffers() {
        val checkpoint = ConversationCheckpointUi(
            id = "chk-prompt",
            messageIndex = 1,
            createdAt = 10L,
            summary = "交互状态: 等待用户确认",
            scope = ConversationCheckpointScope.CONVERSATION,
            promptSnapshot = ConversationCheckpointPromptSnapshotUi(
                pendingAskRequest = PendingAskRequestUi(
                    id = "ask-1",
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

        val plan = resolveCheckpointRecoveryPlan(
            state = SessionState(
                messages = listOf(
                    ChatMessageUi(id = 1L, role = "user", content = "hello"),
                    ChatMessageUi(id = 2L, role = "assistant", content = "done")
                ),
                checkpoints = listOf(checkpoint),
                pendingWorkflowPlan = WorkflowPlanUi(
                    id = "plan-1",
                    goal = "修复问题",
                    summary = "当前计划"
                ),
                canonicalWorkflowPlan = WorkflowPlanUi(
                    id = "plan-1",
                    goal = "修复问题",
                    summary = "当前计划"
                )
            ),
            checkpoint = checkpoint,
            requestedScope = ConversationCheckpointScope.CONVERSATION
        )

        assertEquals(2, plan.targetExclusiveIndex)
        assertTrue(plan.checkpointRecords.isEmpty())
        assertTrue(plan.futureRecords.isEmpty())
    }

    @Test
    fun projectConversationRollbackState_restoresPromptSnapshotAndTrimsLaterSameBoundaryCheckpoint() {
        val targetCheckpoint = ConversationCheckpointUi(
            id = "chk-target",
            messageIndex = 1,
            createdAt = 10L,
            summary = "交互状态: 等待澄清回答",
            scope = ConversationCheckpointScope.CONVERSATION,
            promptSnapshot = ConversationCheckpointPromptSnapshotUi(
                pendingWorkflowPlan = WorkflowPlanUi(
                    id = "plan-restore",
                    goal = "修复问题",
                    summary = "等待执行",
                    recentSessionHistoryClue = SessionHistoryReferenceClueUi(
                        messageReferences = listOf("session-login#21")
                    )
                ),
                canonicalWorkflowPlan = WorkflowPlanUi(
                    id = "plan-restore",
                    goal = "修复问题",
                    summary = "等待执行",
                    recentSessionHistoryClue = SessionHistoryReferenceClueUi(
                        messageReferences = listOf("session-login#21")
                    )
                ),
                pendingClarificationRequest = ClarificationRequestUi(
                    id = "clarify-1",
                    goal = "修复问题",
                    question = "先改哪一块？",
                    recentSessionHistoryClue = SessionHistoryReferenceClueUi(
                        snippets = listOf("发布 smoke test 需要覆盖登录校验")
                    )
                ),
                pendingAskRequest = PendingAskRequestUi(
                    id = "ask-1",
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
        val laterCheckpoint = ConversationCheckpointUi(
            id = "chk-later",
            messageIndex = 1,
            createdAt = 20L,
            summary = "交互状态: 已关闭提问卡片",
            scope = ConversationCheckpointScope.CONVERSATION
        )

        val projection = projectConversationRollbackState(
            state = SessionState(
                messages = listOf(
                    ChatMessageUi(id = 1L, role = "user", content = "hello"),
                    ChatMessageUi(id = 2L, role = "assistant", content = "done")
                ),
                checkpoints = listOf(laterCheckpoint, targetCheckpoint),
                pendingWorkflowPlan = WorkflowPlanUi(
                    id = "plan-current",
                    goal = "当前状态",
                    summary = "已变化"
                ),
                canonicalWorkflowPlan = WorkflowPlanUi(
                    id = "plan-current",
                    goal = "当前状态",
                    summary = "已变化"
                )
            ),
            targetExclusiveIndex = 2,
            targetCheckpoint = targetCheckpoint
        )

        assertEquals(listOf("chk-target"), projection.checkpoints.map { it.id })
        assertEquals("plan-restore", projection.pendingWorkflowPlan?.id)
        assertEquals("plan-restore", projection.canonicalWorkflowPlan?.id)
        assertEquals("clarify-1", projection.pendingClarificationRequest?.id)
        assertEquals(
            listOf("session-login#21"),
            projection.pendingWorkflowPlan?.recentSessionHistoryClue?.messageReferences
        )
        assertEquals(
            listOf("发布 smoke test 需要覆盖登录校验"),
            projection.pendingClarificationRequest?.recentSessionHistoryClue?.snippets
        )
        assertEquals("ask-1", projection.pendingAskRequest?.id)
        assertEquals(true, projection.pendingAskRequest?.isReplayOnly)
    }

    @Test
    fun buildConversationCheckpointPromptSnapshot_stripsReplayOnlyAskMarkers() {
        val snapshot = buildConversationCheckpointPromptSnapshot(
            SessionState(
                pendingAskRequest = PendingAskRequestUi(
                    id = "ask-1",
                    title = "需要确认",
                    questions = listOf(
                        AskQuestionUi(
                            id = "q-1",
                            question = "继续吗？",
                            options = listOf(AskOptionUi("继续"))
                        )
                    ),
                    recentSessionHistoryClue = SessionHistoryReferenceClueUi(
                        messageReferences = listOf("session-login#21"),
                        snippets = listOf("登录态过期后需要补 token 刷新")
                    ),
                    isReplayOnly = true,
                    replayNotice = "replayed"
                )
            )
        )

        assertEquals("ask-1", snapshot?.pendingAskRequest?.id)
        assertEquals(false, snapshot?.pendingAskRequest?.isReplayOnly)
        assertEquals(null, snapshot?.pendingAskRequest?.replayNotice)
        assertEquals(
            listOf("session-login#21"),
            snapshot?.pendingAskRequest?.recentSessionHistoryClue?.messageReferences
        )
    }

    @Test
    fun hasCheckpointPromptSnapshotDifference_ignoresReplayOnlyAskMarkers() {
        val checkpoint = ConversationCheckpointUi(
            id = "chk-ask",
            messageIndex = 1,
            createdAt = 10L,
            summary = "交互状态: 等待用户确认",
            scope = ConversationCheckpointScope.CONVERSATION,
            promptSnapshot = ConversationCheckpointPromptSnapshotUi(
                pendingAskRequest = PendingAskRequestUi(
                    id = "ask-1",
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

        assertEquals(
            false,
            hasCheckpointPromptSnapshotDifference(
                state = SessionState(
                    pendingAskRequest = PendingAskRequestUi(
                        id = "ask-1",
                        title = "需要确认",
                        questions = listOf(
                            AskQuestionUi(
                                id = "q-1",
                                question = "继续吗？",
                                options = listOf(AskOptionUi("继续"))
                            )
                        ),
                        isReplayOnly = true,
                        replayNotice = "replayed"
                    )
                ),
                checkpoint = checkpoint
            )
        )
    }
}
