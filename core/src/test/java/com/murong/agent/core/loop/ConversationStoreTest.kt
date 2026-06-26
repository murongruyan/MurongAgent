package com.murong.agent.core.loop

import com.murong.agent.core.tool.SessionHistoryToolPayload
import com.murong.agent.core.tool.StepSignOffReceipt
import com.murong.agent.core.tool.ToolStructuredPayload
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConversationStoreTest {

    @Test
    fun saveSession_afterDelete_doesNotResurrectSession() {
        val tempDir = Files.createTempDirectory("conversation-store-test")
        try {
            val store = ConversationStore(tempDir.toFile())
            val session = PersistedSession(
                id = "deadbeef",
                title = "待删除会话",
                createdAt = 1L,
                updatedAt = 1L,
                providerId = "test-provider",
                modelName = "test-model",
                messages = listOf(
                    PersistedMessage(
                        id = 1L,
                        role = "user",
                        content = "hello"
                    )
                )
            )

            assertTrue(store.saveSession(session))
            assertEquals(1, store.listSessions().size)

            store.deleteSession(session.id)

            assertNull(store.loadSession(session.id))
            assertTrue(store.listSessions().none { it.id == session.id })

            val resurrected = store.saveSession(session.copy(updatedAt = 2L))

            assertFalse(resurrected)
            assertNull(store.loadSession(session.id))
            assertTrue(store.listSessions().none { it.id == session.id })
            assertFalse(tempDir.resolve("${session.id}.json").toFile().exists())
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun deleteSession_persistsHiddenStateAcrossStoreRecreation() {
        val tempDir = Files.createTempDirectory("conversation-store-delete-persist-test")
        try {
            val session = PersistedSession(
                id = "deadbeef-persisted",
                title = "跨实例删除会话",
                createdAt = 1L,
                updatedAt = 1L,
                providerId = "test-provider",
                modelName = "test-model",
                messages = listOf(
                    PersistedMessage(
                        id = 1L,
                        role = "user",
                        content = "hello"
                    )
                )
            )
            val store = ConversationStore(tempDir.toFile())

            assertTrue(store.saveSession(session))
            store.deleteSession(session.id)

            val recreatedStore = ConversationStore(tempDir.toFile())

            assertNull(recreatedStore.loadSession(session.id))
            assertTrue(recreatedStore.listSessions().none { it.id == session.id })
            assertFalse(recreatedStore.saveSession(session.copy(updatedAt = 2L)))
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun deleteSession_archivesRetainedSummaryBeforeRemoval() {
        val tempDir = Files.createTempDirectory("conversation-store-archive-test")
        try {
            val store = ConversationStore(tempDir.toFile())
            val session = PersistedSession(
                id = "archive-01",
                title = "登录修复会话",
                createdAt = 11L,
                updatedAt = 22L,
                providerId = "test-provider",
                modelName = "test-model",
                sessionGoal = "修复登录态过期问题",
                projectPath = "C:/workspace/app",
                messages = listOf(
                    PersistedMessage(
                        id = 1L,
                        role = "user",
                        content = "登录态过期后会跳回登录页"
                    ),
                    PersistedMessage(
                        id = 2L,
                        role = "assistant",
                        content = "已定位到 token 刷新链缺失。"
                    )
                )
            )

            assertTrue(store.saveSession(session))

            store.deleteSession(session.id)

            val archived = assertNotNull(store.loadArchivedSessionSummary(session.id))
            assertEquals("archive-01", archived.originalSessionId)
            assertEquals("登录修复会话", archived.title)
            assertEquals("修复登录态过期问题", archived.sessionGoal)
            assertEquals("C:/workspace/app", archived.projectPath)
            assertEquals(2, archived.messageCount)
            assertEquals("修复登录态过期问题", archived.retainedSummary)
            assertEquals("archive-01#2", archived.anchorMessageReference)
            assertNull(store.loadSession(session.id))
            assertTrue(store.listSessions().none { it.id == session.id })
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun archivedSessions_persistAcrossStoreRecreation() {
        val tempDir = Files.createTempDirectory("conversation-store-archive-persist-test")
        try {
            val store = ConversationStore(tempDir.toFile())
            val session = PersistedSession(
                id = "archive-02",
                title = "发布检查会话",
                createdAt = 1L,
                updatedAt = 2L,
                providerId = "test-provider",
                modelName = "test-model",
                messages = listOf(
                    PersistedMessage(
                        id = 1L,
                        role = "assistant",
                        content = "发布 smoke test 需要覆盖登录校验"
                    )
                )
            )

            assertTrue(store.saveSession(session))
            store.deleteSession(session.id)

            val recreatedStore = ConversationStore(tempDir.toFile())

            val archived = assertNotNull(recreatedStore.loadArchivedSessionSummary(session.id))
            assertEquals("发布检查会话", archived.title)
            assertEquals("archive-02#1", archived.anchorMessageReference)
            assertTrue(recreatedStore.listArchivedSessions().any { it.originalSessionId == session.id })
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun listArchivedMemoryCandidates_buildsProjectScopedCandidateFromArchivedSession() {
        val tempDir = Files.createTempDirectory("conversation-store-memory-candidate-project-test")
        try {
            val store = ConversationStore(tempDir.toFile())
            val session = PersistedSession(
                id = "archive-memory-project",
                title = "登录修复会话",
                createdAt = 1L,
                updatedAt = 2L,
                providerId = "test-provider",
                modelName = "test-model",
                sessionGoal = "修复登录态过期问题",
                projectPath = "C:/workspace/app",
                messages = listOf(
                    PersistedMessage(
                        id = 1L,
                        role = "assistant",
                        content = "登录态过期后需要补 token 刷新"
                    )
                )
            )

            assertTrue(store.saveSession(session))
            store.deleteSession(session.id)

            val candidate = assertNotNull(
                store.listArchivedMemoryCandidates().firstOrNull {
                    it.sourceSessionId == session.id
                }
            )

            assertEquals(ArchivedMemoryCandidateScope.PROJECT, candidate.suggestedScope)
            assertEquals("修复登录态过期问题", candidate.suggestedTitle)
            assertEquals("修复登录态过期问题", candidate.suggestedContent)
            assertEquals("C:/workspace/app", candidate.sourceProjectPath)
            assertEquals("archive-memory-project#1", candidate.sourceAnchorMessageReference)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun listArchivedMemoryCandidates_buildsGlobalScopedCandidateWhenProjectPathMissing() {
        val tempDir = Files.createTempDirectory("conversation-store-memory-candidate-global-test")
        try {
            val store = ConversationStore(tempDir.toFile())
            val session = PersistedSession(
                id = "archive-memory-global",
                title = "通用发布经验",
                createdAt = 1L,
                updatedAt = 2L,
                providerId = "test-provider",
                modelName = "test-model",
                messages = listOf(
                    PersistedMessage(
                        id = 1L,
                        role = "assistant",
                        content = "发布 smoke test 需要覆盖登录校验"
                    )
                )
            )

            assertTrue(store.saveSession(session))
            store.deleteSession(session.id)

            val candidate = assertNotNull(
                store.listArchivedMemoryCandidates().firstOrNull {
                    it.sourceSessionId == session.id
                }
            )

            assertEquals(ArchivedMemoryCandidateScope.GLOBAL, candidate.suggestedScope)
            assertEquals("通用发布经验", candidate.suggestedTitle)
            assertEquals("发布 smoke test 需要覆盖登录校验", candidate.suggestedContent)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun markArchivedMemoryCandidateAccepted_hidesCandidateAndStoresAcceptanceMetadata() {
        val tempDir = Files.createTempDirectory("conversation-store-memory-candidate-accept-test")
        try {
            val store = ConversationStore(tempDir.toFile())
            val session = PersistedSession(
                id = "archive-memory-accept",
                title = "登录修复会话",
                createdAt = 1L,
                updatedAt = 2L,
                providerId = "test-provider",
                modelName = "test-model",
                sessionGoal = "修复登录态过期问题",
                projectPath = "C:/workspace/app",
                messages = listOf(
                    PersistedMessage(
                        id = 1L,
                        role = "assistant",
                        content = "登录态过期后需要补 token 刷新"
                    )
                )
            )

            assertTrue(store.saveSession(session))
            store.deleteSession(session.id)

            assertTrue(
                store.markArchivedMemoryCandidateAccepted(
                    sessionId = session.id,
                    scope = ArchivedMemoryCandidateScope.PROJECT,
                    memoryId = "mem-001",
                    acceptedAt = 123L
                )
            )

            assertNull(store.loadArchivedMemoryCandidate(session.id))
            assertTrue(store.listArchivedMemoryCandidates().none { it.sourceSessionId == session.id })
            val archived = assertNotNull(store.loadArchivedSessionSummary(session.id))
            assertEquals(ArchivedMemoryCandidateResolutionSource.USER_ACTION, archived.resolutionSource)
            assertEquals("已将归档候选保存为项目记忆。", archived.resolutionSummary)
            assertEquals(ArchivedMemoryCandidateScope.PROJECT, archived.acceptedMemoryScope)
            assertEquals("mem-001", archived.acceptedMemoryId)
            assertEquals(123L, archived.acceptedAt)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun acceptedArchivedMemoryCandidate_persistsAcrossStoreRecreation() {
        val tempDir = Files.createTempDirectory("conversation-store-memory-candidate-accept-persist-test")
        try {
            val store = ConversationStore(tempDir.toFile())
            val session = PersistedSession(
                id = "archive-memory-accept-persist",
                title = "通用发布经验",
                createdAt = 1L,
                updatedAt = 2L,
                providerId = "test-provider",
                modelName = "test-model",
                messages = listOf(
                    PersistedMessage(
                        id = 1L,
                        role = "assistant",
                        content = "发布 smoke test 需要覆盖登录校验"
                    )
                )
            )

            assertTrue(store.saveSession(session))
            store.deleteSession(session.id)
            assertTrue(
                store.markArchivedMemoryCandidateAccepted(
                    sessionId = session.id,
                    scope = ArchivedMemoryCandidateScope.GLOBAL,
                    memoryId = "mem-global-1",
                    acceptedAt = 456L,
                    resolutionSource = ArchivedMemoryCandidateResolutionSource.AUTO_DEDUPLICATED_REUSE,
                    resolutionSummary = "已将归档候选关联到现有全局记忆。"
                )
            )

            val recreatedStore = ConversationStore(tempDir.toFile())

            assertNull(recreatedStore.loadArchivedMemoryCandidate(session.id))
            assertTrue(
                recreatedStore.listArchivedMemoryCandidates().none {
                    it.sourceSessionId == session.id
                }
            )
            val archived = assertNotNull(recreatedStore.loadArchivedSessionSummary(session.id))
            assertEquals(
                ArchivedMemoryCandidateResolutionSource.AUTO_DEDUPLICATED_REUSE,
                archived.resolutionSource
            )
            assertEquals("已将归档候选关联到现有全局记忆。", archived.resolutionSummary)
            assertEquals(ArchivedMemoryCandidateScope.GLOBAL, archived.acceptedMemoryScope)
            assertEquals("mem-global-1", archived.acceptedMemoryId)
            assertEquals(456L, archived.acceptedAt)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun markArchivedMemoryCandidateDismissed_hidesCandidateAndStoresDismissMetadata() {
        val tempDir = Files.createTempDirectory("conversation-store-memory-candidate-dismiss-test")
        try {
            val store = ConversationStore(tempDir.toFile())
            val session = PersistedSession(
                id = "archive-memory-dismiss",
                title = "发布检查会话",
                createdAt = 1L,
                updatedAt = 2L,
                providerId = "test-provider",
                modelName = "test-model",
                messages = listOf(
                    PersistedMessage(
                        id = 1L,
                        role = "assistant",
                        content = "发布 smoke test 需要覆盖登录校验"
                    )
                )
            )

            assertTrue(store.saveSession(session))
            store.deleteSession(session.id)

            assertTrue(
                store.markArchivedMemoryCandidateDismissed(
                    sessionId = session.id,
                    dismissedReason = "暂不沉淀为长期记忆",
                    dismissedAt = 789L
                )
            )

            assertNull(store.loadArchivedMemoryCandidate(session.id))
            assertTrue(store.listArchivedMemoryCandidates().none { it.sourceSessionId == session.id })
            val archived = assertNotNull(store.loadArchivedSessionSummary(session.id))
            assertEquals(ArchivedMemoryCandidateStatus.DISMISSED, archived.candidateStatus)
            assertEquals(ArchivedMemoryCandidateResolutionSource.USER_ACTION, archived.resolutionSource)
            assertEquals("暂不沉淀为长期记忆", archived.resolutionSummary)
            assertEquals("暂不沉淀为长期记忆", archived.dismissedReason)
            assertEquals(789L, archived.dismissedAt)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun dismissedArchivedMemoryCandidate_persistsAcrossStoreRecreation() {
        val tempDir = Files.createTempDirectory("conversation-store-memory-candidate-dismiss-persist-test")
        try {
            val store = ConversationStore(tempDir.toFile())
            val session = PersistedSession(
                id = "archive-memory-dismiss-persist",
                title = "通用登录经验",
                createdAt = 1L,
                updatedAt = 2L,
                providerId = "test-provider",
                modelName = "test-model",
                messages = listOf(
                    PersistedMessage(
                        id = 1L,
                        role = "assistant",
                        content = "登录态过期后需要补 token 刷新"
                    )
                )
            )

            assertTrue(store.saveSession(session))
            store.deleteSession(session.id)
            assertTrue(
                store.markArchivedMemoryCandidateDismissed(
                    sessionId = session.id,
                    dismissedReason = "与现有规则重复",
                    dismissedAt = 999L
                )
            )

            val recreatedStore = ConversationStore(tempDir.toFile())

            assertNull(recreatedStore.loadArchivedMemoryCandidate(session.id))
            assertTrue(
                recreatedStore.listArchivedMemoryCandidates().none {
                    it.sourceSessionId == session.id
                }
            )
            val archived = assertNotNull(recreatedStore.loadArchivedSessionSummary(session.id))
            assertEquals(ArchivedMemoryCandidateStatus.DISMISSED, archived.candidateStatus)
            assertEquals(ArchivedMemoryCandidateResolutionSource.USER_ACTION, archived.resolutionSource)
            assertEquals("与现有规则重复", archived.resolutionSummary)
            assertEquals("与现有规则重复", archived.dismissedReason)
            assertEquals(999L, archived.dismissedAt)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun markArchivedMemoryCandidateConsumed_hidesCandidateAndStoresConsumedMetadata() {
        val tempDir = Files.createTempDirectory("conversation-store-memory-candidate-consumed-test")
        try {
            val store = ConversationStore(tempDir.toFile())
            val session = PersistedSession(
                id = "archive-memory-consumed",
                title = "回归检查会话",
                createdAt = 1L,
                updatedAt = 2L,
                providerId = "test-provider",
                modelName = "test-model",
                messages = listOf(
                    PersistedMessage(
                        id = 1L,
                        role = "assistant",
                        content = "登录流程已经在更高层规则里覆盖"
                    )
                )
            )

            assertTrue(store.saveSession(session))
            store.deleteSession(session.id)

            assertTrue(
                store.markArchivedMemoryCandidateConsumed(
                    sessionId = session.id,
                    consumedReason = "已并入发布检查清单",
                    consumedAt = 1200L
                )
            )

            assertNull(store.loadArchivedMemoryCandidate(session.id))
            assertTrue(store.listArchivedMemoryCandidates().none { it.sourceSessionId == session.id })
            val archived = assertNotNull(store.loadArchivedSessionSummary(session.id))
            assertEquals(ArchivedMemoryCandidateStatus.CONSUMED, archived.candidateStatus)
            assertEquals(ArchivedMemoryCandidateResolutionSource.USER_ACTION, archived.resolutionSource)
            assertEquals("已并入发布检查清单", archived.resolutionSummary)
            assertEquals("已并入发布检查清单", archived.consumedReason)
            assertEquals(1200L, archived.consumedAt)
            assertNull(archived.acceptedMemoryId)
            assertNull(archived.dismissedAt)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun consumedArchivedMemoryCandidate_persistsAcrossStoreRecreation() {
        val tempDir = Files.createTempDirectory("conversation-store-memory-candidate-consumed-persist-test")
        try {
            val store = ConversationStore(tempDir.toFile())
            val session = PersistedSession(
                id = "archive-memory-consumed-persist",
                title = "通用发布经验",
                createdAt = 1L,
                updatedAt = 2L,
                providerId = "test-provider",
                modelName = "test-model",
                messages = listOf(
                    PersistedMessage(
                        id = 1L,
                        role = "assistant",
                        content = "登录态检查已经固化在 smoke 流程中"
                    )
                )
            )

            assertTrue(store.saveSession(session))
            store.deleteSession(session.id)
            assertTrue(
                store.markArchivedMemoryCandidateConsumed(
                    sessionId = session.id,
                    consumedReason = "已吸收到现有流程",
                    consumedAt = 1300L
                )
            )

            val recreatedStore = ConversationStore(tempDir.toFile())

            assertNull(recreatedStore.loadArchivedMemoryCandidate(session.id))
            assertTrue(
                recreatedStore.listArchivedMemoryCandidates().none {
                    it.sourceSessionId == session.id
                }
            )
            val archived = assertNotNull(recreatedStore.loadArchivedSessionSummary(session.id))
            assertEquals(ArchivedMemoryCandidateStatus.CONSUMED, archived.candidateStatus)
            assertEquals(ArchivedMemoryCandidateResolutionSource.USER_ACTION, archived.resolutionSource)
            assertEquals("已吸收到现有流程", archived.resolutionSummary)
            assertEquals("已吸收到现有流程", archived.consumedReason)
            assertEquals(1300L, archived.consumedAt)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun saveSession_withWorkflowSignOffs_restoresStructuredReceiptState() {
        val tempDir = Files.createTempDirectory("conversation-store-workflow-test")
        try {
            val store = ConversationStore(tempDir.toFile())
            val session = PersistedSession(
                id = "workflow01",
                title = "工作流会话",
                createdAt = 1L,
                updatedAt = 2L,
                providerId = "test-provider",
                modelName = "test-model",
                canonicalWorkflowPlan = PersistedWorkflowPlan(
                    id = "plan-1",
                    goal = "修复问题",
                    summary = "先测后改",
                    steps = listOf("运行测试", "修复代码"),
                    currentStepIndex = 1,
                    nextStepHint = "继续修复代码",
                    status = "EXECUTING",
                    stepSignOffs = listOf(
                        PersistedWorkflowStepSignOff(
                            stepIndex = 0,
                            step = "运行测试",
                            reportedStep = "运行测试",
                            resultSummary = "测试已通过",
                            matchedEvidenceCount = 1,
                            totalEvidenceCount = 1,
                            matchedToolNames = listOf("shell"),
                            matchedSessionHistorySessionIds = listOf("session-test"),
                            matchedSessionHistoryMessageReferences = listOf("session-test#7"),
                            signedOffAt = 123L
                        )
                    )
                ),
                messages = emptyList()
            )

            assertTrue(store.saveSession(session))

            val restored = store.loadSession(session.id)
            assertEquals("修复问题", restored?.canonicalWorkflowPlan?.goal)
            assertEquals(1, restored?.canonicalWorkflowPlan?.stepSignOffs?.size)
            val signOff = restored?.canonicalWorkflowPlan?.stepSignOffs?.singleOrNull()
            assertEquals("运行测试", signOff?.step)
            assertEquals("测试已通过", signOff?.resultSummary)
            assertEquals(1, signOff?.matchedEvidenceCount)
            assertEquals(listOf("shell"), signOff?.matchedToolNames)
            assertEquals(listOf("session-test"), signOff?.matchedSessionHistorySessionIds)
            assertEquals(listOf("session-test#7"), signOff?.matchedSessionHistoryMessageReferences)
            assertEquals(123L, signOff?.signedOffAt)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun persistWorkflowPlanAndClarificationRequest_roundTripsRecentSessionHistoryClue() {
        val tempDir = Files.createTempDirectory("conversation-store-history-clue-test")
        try {
            val store = ConversationStore(tempDir.toFile())
            val workflowPlan = WorkflowPlanUi(
                id = "plan-history",
                goal = "修复登录问题",
                summary = "先定位后修复",
                recentSessionHistoryClue = SessionHistoryReferenceClueUi(
                    queries = listOf("登录"),
                    sessionIds = listOf("session-login"),
                    messageReferences = listOf("session-login#21"),
                    snippets = listOf("登录态过期后需要补 token 刷新"),
                    excerptWindows = listOf("2-4/6（指定消息附近）")
                )
            )
            val clarificationRequest = ClarificationRequestUi(
                id = "clarify-history",
                goal = "修复登录问题",
                question = "优先修登录还是 smoke test？",
                recentSessionHistoryClue = SessionHistoryReferenceClueUi(
                    queries = listOf("发布"),
                    sessionIds = listOf("session-release"),
                    messageReferences = listOf("session-release#34"),
                    snippets = listOf("发布 smoke test 需要覆盖登录校验")
                )
            )

            val restoredWorkflowPlan = store.restoreWorkflowPlan(
                store.persistWorkflowPlan(workflowPlan)
            )
            val restoredClarificationRequest = store.restoreClarificationRequest(
                store.persistClarificationRequest(clarificationRequest)
            )

            assertEquals(listOf("登录"), restoredWorkflowPlan?.recentSessionHistoryClue?.queries)
            assertEquals(
                listOf("登录态过期后需要补 token 刷新"),
                restoredWorkflowPlan?.recentSessionHistoryClue?.snippets
            )
            assertEquals(
                listOf("session-release#34"),
                restoredClarificationRequest?.recentSessionHistoryClue?.messageReferences
            )
            assertEquals(
                listOf("发布 smoke test 需要覆盖登录校验"),
                restoredClarificationRequest?.recentSessionHistoryClue?.snippets
            )
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun saveSession_withFinalReadinessReceipt_restoresStructuredFinalGateState() {
        val tempDir = Files.createTempDirectory("conversation-store-final-readiness-test")
        try {
            val store = ConversationStore(tempDir.toFile())
            val session = PersistedSession(
                id = "readiness01",
                title = "收口会话",
                createdAt = 1L,
                updatedAt = 2L,
                providerId = "test-provider",
                modelName = "test-model",
                lastFinalReadinessReceipt = PersistedFinalReadinessReceipt(
                    kind = FinalReadinessReceiptKind.INCOMPLETE_CANONICAL_WORKFLOW.name,
                    requiredAction = FinalReadinessRequiredAction.COMPLETE_REMAINING_PLAN.name,
                    message = "跨轮次计划仍未完成：还剩 2 个未签收步骤。",
                    remainingUnsignedSteps = 2,
                    nextRequiredStep = "修复工作流",
                    latestSignedOffStep = "定位问题",
                    latestSignedOffResultSummary = "已经完成排查",
                    latestSignedOffMatchedTools = listOf("shell"),
                    latestSignedOffSessionHistorySessionIds = listOf("session-login"),
                    latestSignedOffSessionHistoryMessageReferences = listOf("session-login#21")
                ),
                messages = emptyList()
            )

            assertTrue(store.saveSession(session))

            val restored = store.loadSession(session.id)
            val receipt = store.restoreFinalReadinessReceipt(restored?.lastFinalReadinessReceipt)
            assertEquals(FinalReadinessReceiptKind.INCOMPLETE_CANONICAL_WORKFLOW, receipt?.kind)
            assertEquals(FinalReadinessRequiredAction.COMPLETE_REMAINING_PLAN, receipt?.requiredAction)
            assertEquals(2, receipt?.remainingUnsignedSteps)
            assertEquals("修复工作流", receipt?.nextRequiredStep)
            assertEquals("定位问题", receipt?.latestSignedOffStep)
            assertEquals("已经完成排查", receipt?.latestSignedOffResultSummary)
            assertEquals(listOf("shell"), receipt?.latestSignedOffMatchedTools)
            assertEquals(listOf("session-login"), receipt?.latestSignedOffSessionHistorySessionIds)
            assertEquals(
                listOf("session-login#21"),
                receipt?.latestSignedOffSessionHistoryMessageReferences
            )
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun saveSession_withWriteSignOffFinalReadinessReceipt_restoresLatestWriteToolMetadata() {
        val tempDir = Files.createTempDirectory("conversation-store-final-write-receipt-test")
        try {
            val store = ConversationStore(tempDir.toFile())
            val session = PersistedSession(
                id = "readiness02",
                title = "写后签收会话",
                createdAt = 1L,
                updatedAt = 2L,
                providerId = "test-provider",
                modelName = "test-model",
                lastFinalReadinessReceipt = PersistedFinalReadinessReceipt(
                    kind = FinalReadinessReceiptKind.MISSING_COMPLETE_STEP_AFTER_WRITE.name,
                    requiredAction = FinalReadinessRequiredAction.SIGN_OFF_WITH_EVIDENCE.name,
                    message = "最终收口校验未通过：写工具 `code_edit` 已成功执行，但之后还没有成功且关联到该写工具的 `complete_step` 签收记录。",
                    latestSuccessfulWriteToolName = "code_edit"
                ),
                messages = emptyList()
            )

            assertTrue(store.saveSession(session))

            val restored = store.loadSession(session.id)
            val receipt = store.restoreFinalReadinessReceipt(restored?.lastFinalReadinessReceipt)
            assertEquals(FinalReadinessReceiptKind.MISSING_COMPLETE_STEP_AFTER_WRITE, receipt?.kind)
            assertEquals(FinalReadinessRequiredAction.SIGN_OFF_WITH_EVIDENCE, receipt?.requiredAction)
            assertEquals("code_edit", receipt?.latestSuccessfulWriteToolName)
            assertEquals(null, receipt?.remainingUnsignedSteps)
            assertEquals(emptyList<String>(), receipt?.latestSignedOffMatchedTools)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun saveSession_withToolCallSignOffReceipt_restoresRecentToolCallStructure() {
        val tempDir = Files.createTempDirectory("conversation-store-tool-call-test")
        try {
            val store = ConversationStore(tempDir.toFile())
            val session = PersistedSession(
                id = "toolcall01",
                title = "工具记录会话",
                createdAt = 1L,
                updatedAt = 2L,
                providerId = "test-provider",
                modelName = "test-model",
                recentToolCalls = listOf(
                    PersistedToolCallRecord(
                        id = "call-1",
                        toolName = "complete_step",
                        args = """{"step":"修改文件"}""",
                        result = "structured sign-off receipt",
                        isSuccess = true,
                        stepSignOffReceipt = PersistedStepSignOffReceipt(
                            reportedStep = "修改文件",
                            resultSummary = "demo.txt 已更新",
                            matchedEvidenceCount = 1,
                            totalEvidenceCount = 1,
                            matchedToolNames = listOf("mutating_tool"),
                            matchedSessionHistorySessionIds = listOf("session-a", "session-b"),
                            matchedSessionHistoryMessageReferences = listOf("session-a#1", "session-b#2"),
                            signOffTimestamp = 123L
                        ),
                        structuredPayload = ToolStructuredPayload(
                            sessionHistory = SessionHistoryToolPayload(
                                kind = "multi_excerpt",
                                query = "登录",
                                sessionIds = listOf("session-a", "session-b"),
                                messageReferences = listOf("session-a#1", "session-b#2"),
                                anchorMessageIds = listOf(1L, 2L),
                                matchedFields = listOf("消息正文"),
                                snippets = listOf("token 续期判断缺失", "需要补登录 smoke test"),
                                excerptWindows = listOf("3-5/9（关键词命中窗口）", "1-2/4（指定消息附近）")
                            )
                        ),
                        timestamp = 123L
                    )
                ),
                messages = emptyList()
            )

            assertTrue(store.saveSession(session))

            val restored = store.loadSession(session.id)
            val toolCall = store.restoreToolCallRecords(restored?.recentToolCalls.orEmpty()).singleOrNull()
            assertEquals("complete_step", toolCall?.toolName)
            assertEquals("structured sign-off receipt", toolCall?.result)
            assertEquals(listOf("mutating_tool"), toolCall?.stepSignOffReceipt?.matchedToolNames)
            assertEquals(
                listOf("session-a", "session-b"),
                toolCall?.stepSignOffReceipt?.matchedSessionHistorySessionIds
            )
            assertEquals(
                listOf("session-a#1", "session-b#2"),
                toolCall?.stepSignOffReceipt?.matchedSessionHistoryMessageReferences
            )
            assertEquals(123L, toolCall?.stepSignOffReceipt?.signOffTimestamp)
            assertEquals(
                listOf("session-a#1", "session-b#2"),
                toolCall?.structuredPayload?.sessionHistory?.messageReferences
            )
            assertEquals(
                listOf("token 续期判断缺失", "需要补登录 smoke test"),
                toolCall?.structuredPayload?.sessionHistory?.snippets
            )
            assertEquals(
                listOf("3-5/9（关键词命中窗口）", "1-2/4（指定消息附近）"),
                toolCall?.structuredPayload?.sessionHistory?.excerptWindows
            )
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun saveSession_withCheckpointRecoveryRecords_restoresRecentRecoveryTelemetry() {
        val tempDir = Files.createTempDirectory("conversation-store-recovery-record-test")
        try {
            val store = ConversationStore(tempDir.toFile())
            val session = PersistedSession(
                id = "recovery-record-01",
                title = "恢复记录会话",
                createdAt = 1L,
                updatedAt = 2L,
                providerId = "test-provider",
                modelName = "test-model",
                recentRecoveryRecords = listOf(
                    PersistedCheckpointRecoveryRecord(
                        id = "recovery-1",
                        checkpointId = "chk-1",
                        checkpointSummary = "恢复代码/对话: 修改 2 个文件",
                        scope = ConversationCheckpointScope.BOTH.name,
                        restoredFileCount = 2,
                        targetMessageIndex = 5,
                        timestamp = 123L
                    )
                ),
                messages = emptyList()
            )

            assertTrue(store.saveSession(session))

            val restored = store.loadSession(session.id)
            val records = store.restoreCheckpointRecoveryRecords(
                restored?.recentRecoveryRecords.orEmpty()
            )

            assertEquals(1, records.size)
            assertEquals("chk-1", records[0].checkpointId)
            assertEquals("恢复代码/对话: 修改 2 个文件", records[0].checkpointSummary)
            assertEquals(ConversationCheckpointScope.BOTH, records[0].scope)
            assertEquals(2, records[0].restoredFileCount)
            assertEquals(5, records[0].targetMessageIndex)
            assertEquals(123L, records[0].timestamp)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun saveSession_withFinalReadinessAudits_restoresRecentAuditTelemetry() {
        val tempDir = Files.createTempDirectory("conversation-store-final-readiness-audit-test")
        try {
            val store = ConversationStore(tempDir.toFile())
            val session = PersistedSession(
                id = "readiness-audit-01",
                title = "最终收口审计会话",
                createdAt = 1L,
                updatedAt = 2L,
                providerId = "test-provider",
                modelName = "test-model",
                recentFinalReadinessAudits = listOf(
                    PersistedFinalReadinessAuditRecord(
                        result = FinalReadinessAuditResult.BLOCKED.name,
                        recovered = false,
                        receiptKind = FinalReadinessReceiptKind.MISSING_COMPLETE_STEP_AFTER_WRITE.name,
                        requiredAction = FinalReadinessRequiredAction.SIGN_OFF_WITH_EVIDENCE.name,
                        latestSuccessfulWriteToolName = "code_edit",
                        latestSignedOffMatchedTools = emptyList()
                    ),
                    PersistedFinalReadinessAuditRecord(
                        result = FinalReadinessAuditResult.ALLOWED.name,
                        recovered = true,
                        receiptKind = FinalReadinessReceiptKind.INCOMPLETE_CANONICAL_WORKFLOW.name,
                        requiredAction = FinalReadinessRequiredAction.COMPLETE_REMAINING_PLAN.name,
                        remainingUnsignedSteps = 0,
                        nextRequiredStep = "验证发布",
                        latestSignedOffStep = "修复发布流程",
                        latestSignedOffMatchedTools = listOf("code_edit", "shell"),
                        latestSignedOffSessionHistorySessionIds = listOf("session-release"),
                        latestSignedOffSessionHistoryMessageReferences = listOf("session-release#34")
                    )
                ),
                messages = emptyList()
            )

            assertTrue(store.saveSession(session))

            val summary = store.listSessions().single()
            val restored = store.loadSession(session.id)
            val audits = store.restoreFinalReadinessAuditRecords(
                restored?.recentFinalReadinessAudits.orEmpty()
            )

            assertEquals(2, summary.finalReadinessAuditCount)
            assertNotNull(summary.latestFinalReadinessAuditSummary)
            assertTrue(summary.latestFinalReadinessAuditSummary.contains("仍阻塞"))
            assertTrue(summary.latestFinalReadinessAuditSummary.contains("code_edit"))
            assertEquals(FinalReadinessSessionStatusKind.BLOCKED, summary.latestFinalReadinessStatusKind)
            assertNotNull(summary.latestFinalReadinessReasonSummary)
            assertTrue(summary.latestFinalReadinessReasonSummary.contains("code_edit"))
            assertEquals(2, audits.size)
            assertEquals(FinalReadinessAuditResult.BLOCKED, audits[0].result)
            assertEquals(false, audits[0].recovered)
            assertEquals("code_edit", audits[0].latestSuccessfulWriteToolName)
            assertEquals(FinalReadinessAuditResult.ALLOWED, audits[1].result)
            assertEquals(true, audits[1].recovered)
            assertEquals("验证发布", audits[1].nextRequiredStep)
            assertEquals(listOf("code_edit", "shell"), audits[1].latestSignedOffMatchedTools)
            assertEquals(listOf("session-release"), audits[1].latestSignedOffSessionHistorySessionIds)
            assertEquals(
                listOf("session-release#34"),
                audits[1].latestSignedOffSessionHistoryMessageReferences
            )
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun saveSession_withTypedErrors_restoresFinalReadinessErrorKind() {
        val tempDir = Files.createTempDirectory("conversation-store-error-kind-test")
        try {
            val store = ConversationStore(tempDir.toFile())
            val session = PersistedSession(
                id = "error-kind-01",
                title = "错误类型会话",
                createdAt = 1L,
                updatedAt = 2L,
                providerId = "test-provider",
                modelName = "test-model",
                recentErrors = listOf(
                    PersistedErrorRecord(
                        id = "final-readiness-error",
                        message = "最终收口校验未通过：缺少 complete_step。",
                        kind = ErrorRecordKind.FINAL_READINESS.name,
                        timestamp = 10L
                    ),
                    PersistedErrorRecord(
                        id = "general-error",
                        message = "普通网络错误",
                        kind = ErrorRecordKind.GENERAL.name,
                        timestamp = 20L
                    )
                ),
                messages = emptyList()
            )

            assertTrue(store.saveSession(session))

            val restored = store.loadSession(session.id)
            val errors = store.restoreErrorRecords(restored?.recentErrors.orEmpty())

            assertEquals(2, errors.size)
            assertEquals(ErrorRecordKind.FINAL_READINESS, errors[0].kind)
            assertEquals("最终收口校验未通过：缺少 complete_step。", errors[0].message)
            assertEquals(ErrorRecordKind.GENERAL, errors[1].kind)
            assertEquals("普通网络错误", errors[1].message)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun saveSession_withCheckpointMetadata_restoresTypedCheckpointFields() {
        val tempDir = Files.createTempDirectory("conversation-store-checkpoint-test")
        try {
            val store = ConversationStore(tempDir.toFile())
            val session = PersistedSession(
                id = "checkpoint01",
                title = "Checkpoint 会话",
                createdAt = 1L,
                updatedAt = 2L,
                providerId = "test-provider",
                modelName = "test-model",
                checkpoints = listOf(
                    PersistedConversationCheckpoint(
                        id = "chk-1",
                        messageIndex = 3,
                        createdAt = 123L,
                        summary = "file/code_edit 修改了 2 个文件: A.kt、B.kt",
                        changedFiles = listOf("src/A.kt", "src/B.kt"),
                        kind = ConversationCheckpointKind.FILE_TURN.name,
                        scope = ConversationCheckpointScope.CODE.name,
                        source = ConversationCheckpointSource.TOOL_EXECUTION.name,
                        toolNames = listOf("file", "code_edit")
                    ),
                    PersistedConversationCheckpoint(
                        id = "chk-2",
                        messageIndex = 4,
                        createdAt = 124L,
                        summary = "回滚 1 个文件: A.kt",
                        changedFiles = listOf("src/A.kt"),
                        kind = ConversationCheckpointKind.ROLLBACK.name,
                        scope = ConversationCheckpointScope.BOTH.name,
                        source = ConversationCheckpointSource.ROLLBACK.name,
                        toolNames = listOf("rollback")
                    )
                ),
                messages = emptyList()
            )

            assertTrue(store.saveSession(session))

            val restored = store.loadSession(session.id)
            val checkpoints = store.restoreConversationCheckpoints(restored?.checkpoints.orEmpty())
            val mainSessionJson = tempDir.resolve("${session.id}.json").toFile().readText()
            val sidecarJson = tempDir.resolve("${session.id}.checkpoints.json").toFile().readText()
            val mainPayload = Json.parseToJsonElement(mainSessionJson).jsonObject
            val sidecarPayload = Json.parseToJsonElement(sidecarJson).jsonObject

            assertEquals(2, checkpoints.size)
            assertEquals(ConversationCheckpointKind.FILE_TURN, checkpoints.first().kind)
            assertEquals(ConversationCheckpointScope.CODE, checkpoints.first().scope)
            assertEquals(ConversationCheckpointSource.TOOL_EXECUTION, checkpoints.first().source)
            assertEquals(listOf("file", "code_edit"), checkpoints.first().toolNames)
            assertEquals(ConversationCheckpointKind.ROLLBACK, checkpoints.last().kind)
            assertEquals(ConversationCheckpointScope.BOTH, checkpoints.last().scope)
            assertEquals(ConversationCheckpointSource.ROLLBACK, checkpoints.last().source)
            assertEquals(listOf("rollback"), checkpoints.last().toolNames)
            assertEquals(0, mainPayload["checkpoints"]?.jsonArray?.size ?: 0)
            assertEquals(0, mainPayload["fileChanges"]?.jsonArray?.size ?: 0)
            assertEquals(2, sidecarPayload.getValue("checkpoints").jsonArray.size)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun saveSession_withCheckpointPromptSnapshots_restoresCheckpointPromptState() {
        val tempDir = Files.createTempDirectory("conversation-store-checkpoint-prompt-test")
        try {
            val store = ConversationStore(tempDir.toFile())
            val session = PersistedSession(
                id = "checkpoint-prompt-01",
                title = "Checkpoint Prompt 会话",
                createdAt = 1L,
                updatedAt = 2L,
                providerId = "test-provider",
                modelName = "test-model",
                checkpoints = listOf(
                    PersistedConversationCheckpoint(
                        id = "chk-prompt",
                        messageIndex = 2,
                        createdAt = 222L,
                        summary = "交互状态: 等待澄清回答",
                        scope = ConversationCheckpointScope.CONVERSATION.name,
                        promptSnapshot = PersistedConversationCheckpointPromptSnapshot(
                            pendingAskRequest = PersistedPendingAskRequest(
                                id = "ask-1",
                                title = "需要确认",
                                questions = listOf(
                                    PersistedAskQuestion(
                                        id = "q-1",
                                        question = "继续吗？",
                                        options = listOf(PersistedAskOption(label = "继续"))
                                    )
                                ),
                                recentSessionHistoryClue = PersistedSessionHistoryReferenceClue(
                                    messageReferences = listOf("session-login#21"),
                                    snippets = listOf("登录态过期后需要补 token 刷新")
                                )
                            ),
                            pendingWorkflowPlan = PersistedWorkflowPlan(
                                id = "plan-1",
                                goal = "修复问题",
                                summary = "等待执行",
                                recentSessionHistoryClue = PersistedSessionHistoryReferenceClue(
                                    messageReferences = listOf("session-login#21"),
                                    snippets = listOf("登录态过期后需要补 token 刷新")
                                )
                            ),
                            canonicalWorkflowPlan = PersistedWorkflowPlan(
                                id = "plan-1",
                                goal = "修复问题",
                                summary = "等待执行",
                                recentSessionHistoryClue = PersistedSessionHistoryReferenceClue(
                                    messageReferences = listOf("session-login#21"),
                                    snippets = listOf("登录态过期后需要补 token 刷新")
                                )
                            ),
                            pendingClarificationRequest = PersistedClarificationRequest(
                                id = "clarify-1",
                                goal = "修复问题",
                                question = "先改哪一块？",
                                recentSessionHistoryClue = PersistedSessionHistoryReferenceClue(
                                    messageReferences = listOf("session-release#34"),
                                    snippets = listOf("发布 smoke test 需要覆盖登录校验")
                                )
                            )
                        )
                    )
                ),
                messages = emptyList()
            )

            assertTrue(store.saveSession(session))

            val restored = store.loadSession(session.id)
            val checkpoints = store.restoreConversationCheckpoints(restored?.checkpoints.orEmpty())

            assertEquals(1, checkpoints.size)
            assertEquals("chk-prompt", checkpoints.single().id)
            assertEquals("ask-1", checkpoints.single().promptSnapshot?.pendingAskRequest?.id)
            assertEquals(
                listOf("session-login#21"),
                checkpoints.single().promptSnapshot?.pendingAskRequest
                    ?.recentSessionHistoryClue?.messageReferences
            )
            assertEquals("plan-1", checkpoints.single().promptSnapshot?.pendingWorkflowPlan?.id)
            assertEquals("plan-1", checkpoints.single().promptSnapshot?.canonicalWorkflowPlan?.id)
            assertEquals(
                listOf("session-login#21"),
                checkpoints.single().promptSnapshot?.pendingWorkflowPlan
                    ?.recentSessionHistoryClue?.messageReferences
            )
            assertEquals(
                "clarify-1",
                checkpoints.single().promptSnapshot?.pendingClarificationRequest?.id
            )
            assertEquals(
                listOf("发布 smoke test 需要覆盖登录校验"),
                checkpoints.single().promptSnapshot?.pendingClarificationRequest
                    ?.recentSessionHistoryClue?.snippets
            )
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun restoreConversationCheckpoints_defaultsUnknownOrMissingKindToFileTurn() {
        val tempDir = Files.createTempDirectory("conversation-store-checkpoint-compat")
        val store = ConversationStore(tempDir.toFile())
        try {
            val checkpoints = store.restoreConversationCheckpoints(
                listOf(
                    PersistedConversationCheckpoint(
                        id = "legacy-1",
                        messageIndex = 1,
                        createdAt = 1L,
                        summary = "旧记录",
                        changedFiles = listOf("src/A.kt"),
                        kind = "LEGACY_UNKNOWN"
                    ),
                    PersistedConversationCheckpoint(
                        id = "legacy-2",
                        messageIndex = 2,
                        createdAt = 2L,
                        summary = "默认记录",
                        changedFiles = listOf("src/B.kt")
                    )
                )
            )

            assertEquals(
                listOf(ConversationCheckpointKind.FILE_TURN, ConversationCheckpointKind.FILE_TURN),
                checkpoints.map { it.kind }
            )
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun restoreConversationCheckpoints_defaultsUnknownOrMissingScopeAndSourceToCompatibleValues() {
        val tempDir = Files.createTempDirectory("conversation-store-checkpoint-scope-compat")
        val store = ConversationStore(tempDir.toFile())
        try {
            val checkpoints = store.restoreConversationCheckpoints(
                listOf(
                    PersistedConversationCheckpoint(
                        id = "legacy-1",
                        messageIndex = 1,
                        createdAt = 1L,
                        summary = "旧记录",
                        changedFiles = listOf("src/A.kt"),
                        scope = "LEGACY_UNKNOWN",
                        source = "LEGACY_UNKNOWN"
                    ),
                    PersistedConversationCheckpoint(
                        id = "legacy-2",
                        messageIndex = 2,
                        createdAt = 2L,
                        summary = "默认记录",
                        changedFiles = listOf("src/B.kt")
                    )
                )
            )

            assertEquals(
                listOf(ConversationCheckpointScope.CODE, ConversationCheckpointScope.CODE),
                checkpoints.map { it.scope }
            )
            assertEquals(
                listOf(ConversationCheckpointSource.LEGACY_EMBEDDED, ConversationCheckpointSource.TOOL_EXECUTION),
                checkpoints.map { it.source }
            )
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun loadSession_withoutCheckpointSidecar_fallsBackToEmbeddedCheckpointData() {
        val tempDir = Files.createTempDirectory("conversation-store-checkpoint-legacy-session")
        try {
            val store = ConversationStore(tempDir.toFile())
            val sessionId = "legacy-checkpoint-session"
            val legacySession = PersistedSession(
                id = sessionId,
                title = "旧会话",
                createdAt = 1L,
                updatedAt = 2L,
                providerId = "test-provider",
                modelName = "test-model",
                checkpoints = listOf(
                    PersistedConversationCheckpoint(
                        id = "chk-legacy",
                        messageIndex = 1,
                        createdAt = 10L,
                        summary = "旧 checkpoint",
                        changedFiles = listOf("src/A.kt"),
                        kind = ConversationCheckpointKind.FILE_TURN.name,
                        source = ConversationCheckpointSource.LEGACY_EMBEDDED.name,
                        toolNames = listOf("file")
                    )
                ),
                fileChanges = listOf(
                    PersistedFileChangeRecord(
                        id = "rec-legacy",
                        path = "src/A.kt",
                        operation = "write",
                        beforeContent = "old",
                        afterContent = "new",
                        checkpointId = "chk-legacy"
                    )
                ),
                messages = emptyList()
            )
            tempDir.resolve("$sessionId.json").toFile().writeText(store.encodeSession(legacySession))

            val restored = store.loadSession(sessionId)

            assertEquals(1, restored?.checkpoints?.size)
            assertEquals("chk-legacy", restored?.checkpoints?.singleOrNull()?.id)
            assertEquals(
                ConversationCheckpointSource.LEGACY_EMBEDDED,
                store.restoreConversationCheckpoints(restored?.checkpoints.orEmpty()).single().source
            )
            assertEquals(1, restored?.fileChanges?.size)
            assertEquals("rec-legacy", restored?.fileChanges?.singleOrNull()?.id)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }
}
