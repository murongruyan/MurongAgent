package com.murong.agent.core.loop

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatSessionExportPolicyTest {

    @Test
    fun canExportSession_allowsDoctorExportForEmptySession() {
        assertTrue(
            canExportSession(
                state = SessionState(),
                format = ConversationExportFormat.DOCTOR
            )
        )
    }

    @Test
    fun canExportSession_keepsMarkdownBlockedForEmptySession() {
        assertFalse(
            canExportSession(
                state = SessionState(),
                format = ConversationExportFormat.MARKDOWN
            )
        )
    }
}
