package com.murong.agent.ui.chat

import com.murong.agent.core.loop.ConversationCheckpointKind
import com.murong.agent.core.loop.ConversationCheckpointSource
import com.murong.agent.core.loop.ConversationCheckpointUi
import com.murong.agent.core.loop.FileChangeRecordUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ChatWorkspaceReviewPresentationTest {
    @Test
    fun `review keeps the latest state of each file and totals exact line changes`() {
        val checkpoint = checkpoint(changedFiles = listOf("src/A.kt", "src/B.kt"))
        val review = buildChatWorkspaceReview(
            checkpoint = checkpoint,
            records = listOf(
                record(
                    id = "a-old",
                    path = "src/A.kt",
                    before = "a\nb\nc\n",
                    after = "a\nx\nc\n",
                    changedAt = 100L
                ),
                record(
                    id = "a-new",
                    path = "src/A.kt",
                    before = "a\nb\nc\n",
                    after = "a\nx\nc\ny\n",
                    changedAt = 200L
                ),
                record(
                    id = "b-new",
                    path = "src/B.kt",
                    before = null,
                    after = "one\ntwo\n",
                    changedAt = 210L
                )
            )
        )

        assertNotNull(review)
        assertEquals(listOf("a-new", "b-new"), review!!.files.map { it.record.id })
        assertEquals(4, review.addedLines)
        assertEquals(1, review.deletedLines)
        assertEquals(2, review.files.size)
    }

    @Test
    fun `reviews exclude conversation only and rollback checkpoints`() {
        val records = listOf(
            record(
                id = "change",
                path = "src/A.kt",
                before = "before",
                after = "after",
                changedAt = 100L
            )
        )
        val conversationOnly = checkpoint(
            changedFiles = emptyList(),
            source = ConversationCheckpointSource.TOOL_EXECUTION
        )
        val rollback = checkpoint(
            id = "rollback",
            changedFiles = listOf("src/A.kt"),
            source = ConversationCheckpointSource.ROLLBACK
        )

        assertEquals(
            emptyList<ChatWorkspaceReviewUi>(),
            buildChatWorkspaceReviews(
                checkpoints = listOf(conversationOnly, rollback),
                fileChanges = records
            )
        )
        assertNull(buildChatWorkspaceReview(conversationOnly, emptyList()))
    }

    @Test
    fun `line stats handle created deleted and unchanged files`() {
        assertEquals(2 to 0, calculateLineChangeStats(null, "one\ntwo\n"))
        assertEquals(0 to 2, calculateLineChangeStats("one\ntwo\n", null))
        assertEquals(0 to 0, calculateLineChangeStats("same\n", "same\n"))
    }

    private fun checkpoint(
        id: String = "checkpoint",
        changedFiles: List<String>,
        source: ConversationCheckpointSource = ConversationCheckpointSource.TOOL_EXECUTION
    ) = ConversationCheckpointUi(
        id = id,
        messageIndex = 4,
        summary = "summary",
        changedFiles = changedFiles,
        kind = ConversationCheckpointKind.FILE_TURN,
        source = source
    )

    private fun record(
        id: String,
        path: String,
        before: String?,
        after: String?,
        changedAt: Long
    ) = FileChangeRecordUi(
        id = id,
        path = path,
        operation = "write",
        beforeContent = before,
        afterContent = after,
        changedAt = changedAt,
        checkpointId = "checkpoint"
    )
}
