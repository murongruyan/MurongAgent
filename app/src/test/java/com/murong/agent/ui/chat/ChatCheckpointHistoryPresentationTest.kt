package com.murong.agent.ui.chat

import com.murong.agent.core.loop.CheckpointRecoveryRecordUi
import com.murong.agent.core.loop.ConversationCheckpointPromptSnapshotUi
import com.murong.agent.core.loop.ConversationCheckpointScope
import com.murong.agent.core.loop.ConversationCheckpointSource
import com.murong.agent.core.loop.ConversationCheckpointUi
import com.murong.agent.core.loop.FileChangeRecordUi
import com.murong.agent.core.loop.PendingAskRequestUi
import com.murong.agent.core.loop.AskOptionUi
import com.murong.agent.core.loop.AskQuestionUi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatCheckpointHistoryPresentationTest {

    @Test
    fun buildChatCheckpointActivityHintPresentation_returnsNullWhenNoCheckpointActivity() {
        val presentation = buildChatCheckpointActivityHintPresentation(
            checkpoints = emptyList(),
            fileChanges = emptyList(),
            recentRecoveryRecords = emptyList()
        )

        assertNull(presentation)
    }

    @Test
    fun buildChatCheckpointActivityHintPresentation_prefersLatestRecoverySignal() {
        val presentation = buildChatCheckpointActivityHintPresentation(
            checkpoints = listOf(
                ConversationCheckpointUi(
                    id = "chk-1",
                    messageIndex = 2,
                    createdAt = 100L,
                    summary = "修改 2 个文件",
                    changedFiles = listOf("A.kt", "B.kt"),
                    scope = ConversationCheckpointScope.BOTH,
                    source = ConversationCheckpointSource.TOOL_EXECUTION
                )
            ),
            fileChanges = listOf(
                FileChangeRecordUi(
                    id = "rec-1",
                    path = "A.kt",
                    operation = "update",
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
                    targetMessageIndex = 2,
                    timestamp = 200L
                )
            )
        )

        assertNotNull(presentation)
        assertEquals("修改/恢复活动 · 1 批 / 1 文件 / 1 次恢复", presentation.title)
        assertTrue(presentation.message.contains("最近恢复全部"))
        assertTrue(presentation.message.contains("修改 2 个文件"))
    }

    @Test
    fun buildChatCheckpointActivityHintPresentation_normalizesLegacyRecoverySummaryWhenCheckpointMissing() {
        val presentation = buildChatCheckpointActivityHintPresentation(
            checkpoints = emptyList(),
            fileChanges = emptyList(),
            recentRecoveryRecords = listOf(
                CheckpointRecoveryRecordUi(
                    id = "recovery-1",
                    checkpointId = "missing-checkpoint",
                    checkpointSummary = "恢复对话: 对话推进",
                    scope = ConversationCheckpointScope.CONVERSATION,
                    restoredFileCount = 0,
                    targetMessageIndex = 2,
                    timestamp = 200L
                )
            )
        )

        assertNotNull(presentation)
        assertTrue(presentation.message.contains("最近恢复对话"))
        assertTrue(presentation.message.contains("对话推进"))
        assertTrue(!presentation.message.contains("恢复对话: 对话推进"))
    }

    @Test
    fun buildChatCheckpointHistoryPresentation_mergesCheckpointsAndRecoveries() {
        val presentation = buildChatCheckpointHistoryPresentation(
            checkpoints = listOf(
                ConversationCheckpointUi(
                    id = "chk-1",
                    messageIndex = 3,
                    createdAt = 100L,
                    summary = "修改 2 个文件",
                    changedFiles = listOf("A.kt", "B.kt"),
                    scope = ConversationCheckpointScope.BOTH,
                    source = ConversationCheckpointSource.TOOL_EXECUTION
                )
            ),
            fileChanges = listOf(
                FileChangeRecordUi(
                    id = "rec-1",
                    path = "A.kt",
                    operation = "update",
                    checkpointId = "chk-1"
                ),
                FileChangeRecordUi(
                    id = "rec-2",
                    path = "B.kt",
                    operation = "create",
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
                    timestamp = 200L
                )
            )
        )

        assertEquals("本轮修改与恢复", presentation.title)
        assertTrue(hasChatCheckpointActivity(presentation))
        assertEquals("修改 2 个文件", presentation.checkpoints.single().title)
        assertTrue(presentation.checkpoints.single().subtitle.contains("恢复全部"))
        assertTrue(presentation.checkpoints.single().changedFilesPreview.contains("A.kt"))

        val recovery = assertNotNull(findChatCheckpointRecoveryPresentation(presentation, "recovery-1"))
        assertEquals("最近恢复全部", recovery.title)
        assertTrue(recovery.detailSubtitle.contains("目标消息位置 4"))
        assertTrue(recovery.detailContent.contains("来源检查点: chk-1"))
    }

    @Test
    fun buildChatCheckpointHistoryPresentation_usesTypedPromptMetadataForPromptCheckpoint() {
        val presentation = buildChatCheckpointHistoryPresentation(
            checkpoints = listOf(
                ConversationCheckpointUi(
                    id = "chk-ask",
                    messageIndex = 3,
                    createdAt = 100L,
                    summary = "交互状态: 等待用户确认",
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
            recentRecoveryRecords = emptyList()
        )

        val checkpoint = presentation.checkpoints.single()
        assertEquals("等待用户确认", checkpoint.title)
        assertTrue(checkpoint.subtitle.contains("确认状态"))
        assertTrue(checkpoint.changedFilesPreview.contains("是否继续执行数据库迁移"))
    }

    @Test
    fun buildChatCheckpointActivityHintPresentation_usesTypedPromptPreviewForLatestCheckpoint() {
        val presentation = buildChatCheckpointActivityHintPresentation(
            checkpoints = listOf(
                ConversationCheckpointUi(
                    id = "chk-ask",
                    messageIndex = 2,
                    createdAt = 100L,
                    summary = "交互状态: 等待用户确认",
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
            recentRecoveryRecords = emptyList()
        )

        assertNotNull(presentation)
        assertTrue(presentation.message.contains("等待用户确认"))
        assertTrue(presentation.message.contains("提问卡片: 是否继续执行数据库迁移？"))
    }

    @Test
    fun buildChatCheckpointHistoryPresentation_usesTypedCheckpointMetadataForRecovery() {
        val checkpoint = ConversationCheckpointUi(
            id = "chk-ask",
            messageIndex = 2,
            createdAt = 100L,
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
        val presentation = buildChatCheckpointHistoryPresentation(
            checkpoints = listOf(checkpoint),
            fileChanges = emptyList(),
            recentRecoveryRecords = listOf(
                CheckpointRecoveryRecordUi(
                    id = "recovery-1",
                    checkpointId = "chk-ask",
                    checkpointSummary = "旧恢复摘要",
                    scope = ConversationCheckpointScope.CONVERSATION,
                    restoredFileCount = 0,
                    targetMessageIndex = 2,
                    timestamp = 200L
                )
            )
        )

        val recovery = assertNotNull(findChatCheckpointRecoveryPresentation(presentation, "recovery-1"))
        assertTrue(recovery.subtitle.contains("等待用户确认"))
        assertTrue(recovery.subtitle.contains("提问卡片: 是否继续执行数据库迁移？"))
        assertTrue(recovery.detailContent.contains("恢复摘要: 等待用户确认"))
    }

    @Test
    fun buildChatCheckpointActivityHintPresentation_usesTypedCheckpointMetadataForLatestRecovery() {
        val checkpoint = ConversationCheckpointUi(
            id = "chk-plan",
            messageIndex = 2,
            createdAt = 100L,
            summary = "fallback-plan",
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
        val presentation = buildChatCheckpointActivityHintPresentation(
            checkpoints = listOf(checkpoint),
            fileChanges = emptyList(),
            recentRecoveryRecords = listOf(
                CheckpointRecoveryRecordUi(
                    id = "recovery-1",
                    checkpointId = "chk-plan",
                    checkpointSummary = "旧恢复摘要",
                    scope = ConversationCheckpointScope.CONVERSATION,
                    restoredFileCount = 0,
                    targetMessageIndex = 2,
                    timestamp = 200L
                )
            )
        )

        assertNotNull(presentation)
        assertTrue(presentation.message.contains("最近恢复对话"))
        assertTrue(presentation.message.contains("等待用户确认"))
        assertTrue(presentation.message.contains("提问卡片: 是否继续执行数据库迁移？"))
    }
}
