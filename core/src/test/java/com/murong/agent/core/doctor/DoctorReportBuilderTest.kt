package com.murong.agent.core.doctor

import com.murong.agent.core.config.ProviderConfig
import com.murong.agent.core.loop.PersistedCheckpointRecoveryRecord
import com.murong.agent.core.loop.PersistedFinalReadinessAuditRecord
import com.murong.agent.core.loop.PersistedErrorRecord
import com.murong.agent.core.loop.PersistedMessage
import com.murong.agent.core.loop.PersistedSession
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DoctorReportBuilderTest {

    @Test
    fun buildDoctorReport_reportsSanitizedSummaryWithoutRawPathsOrSecrets() {
        val report = buildDoctorReport(
            session = PersistedSession(
                id = "session-1",
                title = "Release /workspace/repo",
                createdAt = 1L,
                updatedAt = 2L,
                providerId = "deepseek",
                modelName = "deepseek-v4-flash",
                projectPath = "C:\\Users\\Alice\\workspace\\repo",
                recentErrors = listOf(
                    PersistedErrorRecord(
                        id = "error-1",
                        message = "Failed to use alice@example.com at /home/alice/repo with Bearer abcdefghijklmn",
                        kind = "GENERAL",
                        timestamp = 3L
                    )
                ),
                recentRecoveryRecords = listOf(
                    PersistedCheckpointRecoveryRecord(
                        id = "recovery-1",
                        checkpointId = "checkpoint-7",
                        checkpointSummary = "Restore C:\\Users\\Alice\\workspace\\repo after crash",
                        scope = "FILE_TURN",
                        restoredFileCount = 2,
                        targetMessageIndex = 9,
                        timestamp = 6L
                    )
                ),
                recentFinalReadinessAudits = listOf(
                    PersistedFinalReadinessAuditRecord(
                        result = "ALLOWED",
                        recovered = true,
                        receiptKind = "INCOMPLETE_CANONICAL_WORKFLOW",
                        requiredAction = "COMPLETE_REMAINING_PLAN",
                        remainingUnsignedSteps = 2,
                        nextRequiredStep = "verify alice@example.com at /workspace/repo",
                        latestSignedOffStep = "Patch login flow",
                        latestSignedOffMatchedTools = listOf("task_repo_apply_patch"),
                        latestSignedOffSessionHistorySessionIds = listOf("session-42"),
                        latestSignedOffSessionHistoryMessageReferences = listOf("msg-9", "msg-10")
                    )
                ),
                messages = listOf(
                    PersistedMessage(
                        id = 1L,
                        role = "user",
                        content = "hello"
                    )
                )
            ),
            config = ProviderConfig(
                activeProviderId = "deepseek",
                deepseekApiKey = "sk-test-secret",
                githubToken = "ghp_1234567890abcdef",
                githubBackendSessionToken = "backend-secret-token",
                githubViewerLogin = "alice@example.com"
            ),
            pendingCrash = PendingCrashReport(
                timestamp = 5L,
                threadName = "main",
                exceptionType = "java.lang.IllegalStateException",
                message = "Bearer crash-token at /data/user/0/app/cache",
                stackTrace = "IllegalStateException at C:\\Users\\Alice\\workspace\\repo",
                fatal = true
            ),
            generatedAt = 4L
        )

        assertTrue(report.contains("# Doctor Report"))
        assertTrue(report.contains("provider_id: deepseek"))
        assertTrue(report.contains("github_token_present: true"))
        assertTrue(report.contains("recent_error_count: 1"))
        assertTrue(report.contains("pending_crash_present: true"))
        assertTrue(report.contains("pending_crash_exception: java.lang.IllegalStateException"))
        assertTrue(report.contains("final_readiness_audit_count: 1"))
        assertTrue(report.contains("recovery_record_count: 1"))
        assertTrue(report.contains("latest_final_readiness_status: 提醒后已恢复放行"))
        assertTrue(report.contains("latest_recovery_present: true"))
        assertTrue(report.contains("latest_recovery_checkpoint: checkpoint-7"))
        assertTrue(report.contains("pending_crash_stack_preview:"))
        assertFalse(report.contains("alice@example.com"))
        assertFalse(report.contains("backend-secret-token"))
        assertFalse(report.contains("ghp_1234567890abcdef"))
        assertFalse(report.contains("""C:\Users\Alice\workspace\repo"""))
        assertFalse(report.contains("/home/alice/repo"))
        assertTrue(report.contains("[REDACTED_EMAIL]"))
        assertTrue(report.contains("[REDACTED_BEARER]"))
        assertTrue(report.contains("[REDACTED_PATH]"))
    }
}
