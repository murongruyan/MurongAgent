package com.murong.agent.core.loop

import com.murong.agent.core.tool.SessionHistoryToolPayload
import com.murong.agent.core.tool.ToolStructuredPayload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatSessionPersistencePolicyTest {

    @Test
    fun hasPersistableSessionContent_whenOnlyFinalReadinessAuditsExist_returnsTrue() {
        val state = SessionState(
            recentFinalReadinessAudits = listOf(
                FinalReadinessAuditRecord(
                    result = FinalReadinessAuditResult.BLOCKED,
                    recovered = false,
                    receiptKind = FinalReadinessReceiptKind.MISSING_COMPLETE_STEP_AFTER_WRITE,
                    requiredAction = FinalReadinessRequiredAction.SIGN_OFF_WITH_EVIDENCE,
                    latestSuccessfulWriteToolName = "code_edit"
                )
            )
        )

        assertTrue(hasPersistableSessionContent(state))
    }

    @Test
    fun hasPersistableSessionContent_whenSessionIsEmpty_returnsFalse() {
        assertFalse(hasPersistableSessionContent(SessionState()))
    }

    @Test
    fun buildFinalReadinessContinuationContext_whenAuditIsBlocked_returnsReminder() {
        val context = buildFinalReadinessContinuationContext(
            listOf(
                FinalReadinessAuditRecord(
                    result = FinalReadinessAuditResult.BLOCKED,
                    recovered = false,
                    receiptKind = FinalReadinessReceiptKind.MISSING_COMPLETE_STEP_AFTER_WRITE,
                    requiredAction = FinalReadinessRequiredAction.SIGN_OFF_WITH_EVIDENCE,
                    latestSuccessfulWriteToolName = "code_edit"
                )
            )
        )

        assertNotNull(context)
        assertTrue(context.contains("最近一次最终收口状态"))
        assertTrue(context.contains("仍阻塞"))
        assertTrue(context.contains("complete_step"))
    }

    @Test
    fun buildFinalReadinessContinuationContext_whenAuditIsPlainAllowed_returnsNull() {
        val context = buildFinalReadinessContinuationContext(
            listOf(
                FinalReadinessAuditRecord(
                    result = FinalReadinessAuditResult.ALLOWED,
                    recovered = false,
                    receiptKind = FinalReadinessReceiptKind.INCOMPLETE_CANONICAL_WORKFLOW,
                    requiredAction = FinalReadinessRequiredAction.COMPLETE_REMAINING_PLAN,
                    remainingUnsignedSteps = 0
                )
            )
        )

        assertNull(context)
    }

    @Test
    fun buildFinalReadinessContinuationContext_whenAuditRecovered_returnsReminder() {
        val context = buildFinalReadinessContinuationContext(
            listOf(
                FinalReadinessAuditRecord(
                    result = FinalReadinessAuditResult.ALLOWED,
                    recovered = true,
                    receiptKind = FinalReadinessReceiptKind.INCOMPLETE_CANONICAL_WORKFLOW,
                    requiredAction = FinalReadinessRequiredAction.COMPLETE_REMAINING_PLAN,
                    remainingUnsignedSteps = 1
                )
            )
        )

        assertNotNull(context)
        assertTrue(context.contains("最近一次最终收口状态"))
        assertTrue(context.contains("提醒后已恢复放行"))
        assertTrue(context.contains("complete_step"))
    }

    @Test
    fun buildFinalReadinessContinuationContext_whenAuditCarriesSessionHistoryRefs_includesHistorySummary() {
        val context = buildFinalReadinessContinuationContext(
            listOf(
                FinalReadinessAuditRecord(
                    result = FinalReadinessAuditResult.ALLOWED,
                    recovered = true,
                    receiptKind = FinalReadinessReceiptKind.INCOMPLETE_CANONICAL_WORKFLOW,
                    requiredAction = FinalReadinessRequiredAction.COMPLETE_REMAINING_PLAN,
                    remainingUnsignedSteps = 1,
                    latestSignedOffSessionHistorySessionIds = listOf("session-login"),
                    latestSignedOffSessionHistoryMessageReferences = listOf("session-login#21")
                )
            )
        )

        assertNotNull(context)
        assertTrue(context.contains("最近命中的历史会话：session-login"))
        assertTrue(context.contains("最近命中的历史消息：session-login#21"))
    }

    @Test
    fun buildFinalReadinessContinuationContext_whenSessionLevelHistoryExists_mergesAuditAndSessionSources() {
        val context = buildFinalReadinessContinuationContext(
            audits = listOf(
                FinalReadinessAuditRecord(
                    result = FinalReadinessAuditResult.BLOCKED,
                    recovered = false,
                    receiptKind = FinalReadinessReceiptKind.MISSING_COMPLETE_STEP_AFTER_WRITE,
                    requiredAction = FinalReadinessRequiredAction.SIGN_OFF_WITH_EVIDENCE,
                    latestSuccessfulWriteToolName = "code_edit",
                    latestSignedOffSessionHistorySessionIds = listOf("session-login"),
                    latestSignedOffSessionHistoryMessageReferences = listOf("session-login#21")
                )
            ),
            recentSessionHistoryClue = SessionHistoryReferenceClueUi(
                queries = listOf("发布"),
                sessionIds = listOf("session-release"),
                messageReferences = listOf("session-release#34"),
                snippets = listOf("发布 smoke test 需要覆盖登录校验"),
                excerptWindows = listOf("2-4/6（指定消息附近）")
            )
        )

        assertNotNull(context)
        assertTrue(context.contains("最近一次最终收口状态"))
        assertTrue(context.contains("最近查询：发布"))
        assertTrue(context.contains("最近命中的历史会话：session-login、session-release"))
        assertTrue(context.contains("最近命中的历史消息：session-login#21、session-release#34"))
        assertTrue(context.contains("最近历史线索摘要：发布 smoke test 需要覆盖登录校验"))
        assertTrue(context.contains("最近摘录窗口：2-4/6（指定消息附近）"))
    }

    @Test
    fun buildRecentSessionHistoryReferenceContext_whenRecentHistoryPayloadExists_includesRecentSummary() {
        val context = buildRecentSessionHistoryReferenceContext(
            listOf(
                ToolCallRecordUi(
                    toolName = "session_history_search",
                    args = """{"message_reference":"session-login#21"}""",
                    result = "历史会话摘录",
                    isSuccess = true,
                    structuredPayload = ToolStructuredPayload(
                        sessionHistory = SessionHistoryToolPayload(
                            kind = "excerpt",
                            query = "登录",
                            sessionIds = listOf("session-login", "session-login"),
                            messageReferences = listOf("session-login#21", "session-login#21"),
                            snippets = listOf("登录态过期后需要补 token 刷新", "登录态过期后需要补 token 刷新"),
                            excerptWindows = listOf("2-4/6（指定消息附近）")
                        )
                    )
                ),
                ToolCallRecordUi(
                    toolName = "session_history_search",
                    args = """{"query":"发布"}""",
                    result = "历史会话命中 1 条",
                    isSuccess = true,
                    structuredPayload = ToolStructuredPayload(
                        sessionHistory = SessionHistoryToolPayload(
                            kind = "search",
                            query = "发布",
                            sessionIds = listOf("session-release"),
                            messageReferences = listOf("session-release#34"),
                            snippets = listOf("发布 smoke test 需要覆盖登录校验")
                        )
                    )
                ),
                ToolCallRecordUi(
                    toolName = "session_history_search",
                    args = """{"query":"失败"}""",
                    result = "Error: failed",
                    isSuccess = false,
                    structuredPayload = ToolStructuredPayload(
                        sessionHistory = SessionHistoryToolPayload(
                            kind = "search",
                            query = "失败",
                            sessionIds = listOf("session-failed"),
                            messageReferences = listOf("session-failed#1")
                        )
                    )
                )
            )
        )

        assertNotNull(context)
        assertTrue(context.contains("最近已引用过会话历史检索结果"))
        assertTrue(context.contains("最近查询：登录、发布"))
        assertTrue(context.contains("最近命中的历史会话：session-login、session-release"))
        assertTrue(context.contains("最近命中的历史消息：session-login#21、session-release#34"))
        assertTrue(context.contains("最近历史线索摘要：登录态过期后需要补 token 刷新、发布 smoke test 需要覆盖登录校验"))
        assertTrue(context.contains("最近摘录窗口：2-4/6（指定消息附近）"))
        assertFalse(context.contains("session-failed"))
    }

    @Test
    fun buildRecentSessionHistoryReferenceClue_whenRecentHistoryPayloadExists_returnsTypedSummary() {
        val clue = buildRecentSessionHistoryReferenceClue(
            listOf(
                ToolCallRecordUi(
                    toolName = "session_history_search",
                    args = """{"message_reference":"session-login#21"}""",
                    result = "历史会话摘录",
                    isSuccess = true,
                    structuredPayload = ToolStructuredPayload(
                        sessionHistory = SessionHistoryToolPayload(
                            kind = "excerpt",
                            query = "登录",
                            sessionIds = listOf("session-login"),
                            messageReferences = listOf("session-login#21"),
                            snippets = listOf("登录态过期后需要补 token 刷新"),
                            excerptWindows = listOf("2-4/6（指定消息附近）")
                        )
                    )
                ),
                ToolCallRecordUi(
                    toolName = "session_history_search",
                    args = """{"query":"发布"}""",
                    result = "历史会话命中 1 条",
                    isSuccess = true,
                    structuredPayload = ToolStructuredPayload(
                        sessionHistory = SessionHistoryToolPayload(
                            kind = "search",
                            query = "发布",
                            sessionIds = listOf("session-release"),
                            messageReferences = listOf("session-release#34"),
                            snippets = listOf("发布 smoke test 需要覆盖登录校验")
                        )
                    )
                )
            )
        )

        assertEquals(listOf("登录", "发布"), clue?.queries)
        assertEquals(listOf("session-login", "session-release"), clue?.sessionIds)
        assertEquals(listOf("session-login#21", "session-release#34"), clue?.messageReferences)
        assertEquals(
            listOf("登录态过期后需要补 token 刷新", "发布 smoke test 需要覆盖登录校验"),
            clue?.snippets
        )
        assertEquals(listOf("2-4/6（指定消息附近）"), clue?.excerptWindows)
    }

    @Test
    fun sessionStateRecentSessionHistoryClue_mergesPromptAndToolHistorySources() {
        val state = SessionState(
            recentToolCalls = listOf(
                ToolCallRecordUi(
                    toolName = "session_history_search",
                    args = """{"query":"发布"}""",
                    result = "历史会话命中 1 条",
                    isSuccess = true,
                    structuredPayload = ToolStructuredPayload(
                        sessionHistory = SessionHistoryToolPayload(
                            kind = "search",
                            query = "发布",
                            sessionIds = listOf("session-release"),
                            messageReferences = listOf("session-release#34"),
                            snippets = listOf("发布 smoke test 需要覆盖登录校验")
                        )
                    )
                )
            ),
            pendingWorkflowPlan = WorkflowPlanUi(
                goal = "修登录流程",
                summary = "补齐 token 刷新",
                steps = listOf("排查", "修复"),
                recentSessionHistoryClue = SessionHistoryReferenceClueUi(
                    queries = listOf("登录"),
                    sessionIds = listOf("session-login"),
                    messageReferences = listOf("session-login#21"),
                    snippets = listOf("登录态过期后需要补 token 刷新"),
                    excerptWindows = listOf("2-4/6（指定消息附近）")
                )
            ),
            canonicalWorkflowPlan = WorkflowPlanUi(
                goal = "修登录流程",
                summary = "补齐 token 刷新",
                steps = listOf("排查", "修复"),
                recentSessionHistoryClue = SessionHistoryReferenceClueUi(
                    queries = listOf("登录"),
                    sessionIds = listOf("session-login"),
                    messageReferences = listOf("session-login#21")
                )
            )
        )

        val clue = assertNotNull(state.recentSessionHistoryClue)
        assertEquals(listOf("登录", "发布"), clue.queries)
        assertEquals(listOf("session-login", "session-release"), clue.sessionIds)
        assertEquals(listOf("session-login#21", "session-release#34"), clue.messageReferences)
        assertEquals(
            listOf("登录态过期后需要补 token 刷新", "发布 smoke test 需要覆盖登录校验"),
            clue.snippets
        )
        assertEquals(listOf("2-4/6（指定消息附近）"), clue.excerptWindows)
    }

    @Test
    fun sessionStateRecentSessionHistoryContext_reusesMergedClueSummary() {
        val state = SessionState(
            pendingClarificationRequest = ClarificationRequestUi(
                goal = "发布前检查",
                question = "是否需要覆盖登录校验？",
                recentSessionHistoryClue = SessionHistoryReferenceClueUi(
                    queries = listOf("发布"),
                    sessionIds = listOf("session-release"),
                    messageReferences = listOf("session-release#34"),
                    snippets = listOf("发布 smoke test 需要覆盖登录校验")
                )
            )
        )

        val context = assertNotNull(state.recentSessionHistoryContext)
        assertTrue(context.contains("最近已引用过会话历史检索结果"))
        assertTrue(context.contains("最近查询：发布"))
        assertTrue(context.contains("最近命中的历史消息：session-release#34"))
    }

    @Test
    fun buildRecentSessionHistoryReferenceContext_whenHistoryPayloadHasNoReusableReferences_returnsNull() {
        val context = buildRecentSessionHistoryReferenceContext(
            listOf(
                ToolCallRecordUi(
                    toolName = "session_history_search",
                    args = "{}",
                    result = "历史会话命中 0 条",
                    isSuccess = true,
                    structuredPayload = ToolStructuredPayload(
                        sessionHistory = SessionHistoryToolPayload(kind = "search")
                    )
                )
            )
        )

        assertNull(context)
    }

    @Test
    fun buildTurnScopedAuxiliaryUserContext_whenRecentHistoryExists_includesHistoryBeforeMentionedFilesAndExtraContext() {
        val context = buildTurnScopedAuxiliaryUserContext(
            recentToolCalls = listOf(
                ToolCallRecordUi(
                    toolName = "session_history_search",
                    args = """{"message_reference":"session-login#21"}""",
                    result = "历史会话摘录",
                    isSuccess = true,
                    structuredPayload = ToolStructuredPayload(
                        sessionHistory = SessionHistoryToolPayload(
                            kind = "excerpt",
                            query = "登录",
                            sessionIds = listOf("session-login"),
                            messageReferences = listOf("session-login#21"),
                            snippets = listOf("登录态过期后需要补 token 刷新"),
                            excerptWindows = listOf("2-4/6（指定消息附近）")
                        )
                    )
                )
            ),
            mentionedFilesContext = "用户提到了这些文件：\n- app/src/Main.kt",
            extraContext = "已有澄清答案：使用生产环境配置。"
        )

        assertNotNull(context)
        assertTrue(context.startsWith("最近已引用过会话历史检索结果"))
        assertTrue(context.contains("用户提到了这些文件"))
        assertTrue(context.contains("已有澄清答案"))
        assertTrue(context.indexOf("最近已引用过会话历史检索结果") < context.indexOf("用户提到了这些文件"))
        assertTrue(context.indexOf("用户提到了这些文件") < context.indexOf("已有澄清答案"))
    }

    @Test
    fun buildTurnScopedAuxiliaryUserContext_whenSessionLevelClueExists_reusesSharedHistorySource() {
        val context = buildTurnScopedAuxiliaryUserContext(
            recentSessionHistoryClue = SessionHistoryReferenceClueUi(
                queries = listOf("登录", "发布"),
                sessionIds = listOf("session-login", "session-release"),
                messageReferences = listOf("session-login#21"),
                snippets = listOf("登录态过期后需要补 token 刷新"),
                excerptWindows = listOf("2-4/6（指定消息附近）")
            ),
            mentionedFilesContext = "用户提到了这些文件：\n- app/src/Main.kt",
            extraContext = "已有澄清答案：使用生产环境配置。"
        )

        assertNotNull(context)
        assertTrue(context.contains("最近查询：登录、发布"))
        assertTrue(context.contains("最近命中的历史会话：session-login、session-release"))
        assertTrue(context.contains("最近摘录窗口：2-4/6（指定消息附近）"))
        assertTrue(context.contains("用户提到了这些文件"))
        assertTrue(context.contains("已有澄清答案"))
    }

    @Test
    fun buildControllerSessionHistoryContext_whenStateAndLocalPromptCluesExist_mergesSharedSources() {
        val context = buildControllerSessionHistoryContext(
            state = SessionState(
                pendingWorkflowPlan = WorkflowPlanUi(
                    goal = "发布前检查",
                    summary = "检查发布链路",
                    recentSessionHistoryClue = SessionHistoryReferenceClueUi(
                        queries = listOf("发布"),
                        sessionIds = listOf("session-release"),
                        snippets = listOf("发布 smoke test 需要覆盖登录校验")
                    )
                ),
                recentToolCalls = listOf(
                    ToolCallRecordUi(
                        toolName = "session_history_search",
                        args = """{"message_reference":"session-login#21"}""",
                        result = "历史会话摘录",
                        isSuccess = true,
                        structuredPayload = ToolStructuredPayload(
                            sessionHistory = SessionHistoryToolPayload(
                                kind = "excerpt",
                                query = "登录",
                                sessionIds = listOf("session-login"),
                                messageReferences = listOf("session-login#21"),
                                snippets = listOf("登录态过期后需要补 token 刷新")
                            )
                        )
                    )
                )
            ),
            localClue = SessionHistoryReferenceClueUi(
                messageReferences = listOf("session-plan#8"),
                excerptWindows = listOf("3-5/9（指定消息附近）")
            )
        )

        assertNotNull(context)
        assertTrue(context.contains("最近查询：发布、登录"))
        assertTrue(context.contains("最近命中的历史会话：session-release、session-login"))
        assertTrue(context.contains("最近命中的历史消息：session-login#21、session-plan#8"))
        assertTrue(context.contains("最近摘录窗口：3-5/9（指定消息附近）"))
    }

    @Test
    fun buildControllerSessionHistoryContext_whenStateLacksSharedHistory_reusesLocalPromptClue() {
        val context = buildControllerSessionHistoryContext(
            state = SessionState(),
            localClue = SessionHistoryReferenceClueUi(
                queries = listOf("登录"),
                messageReferences = listOf("session-login#21"),
                snippets = listOf("登录态过期后需要补 token 刷新")
            )
        )

        assertNotNull(context)
        assertTrue(context.contains("最近查询：登录"))
        assertTrue(context.contains("最近命中的历史消息：session-login#21"))
        assertTrue(context.contains("最近历史线索摘要：登录态过期后需要补 token 刷新"))
    }

    @Test
    fun buildStateAwarePendingAskRequest_mergesSessionAndLocalHistoryClues() {
        val request = buildStateAwarePendingAskRequest(
            request = PendingAskRequestUi(
                title = "需要确认",
                questions = listOf(
                    AskQuestionUi(
                        id = "q1",
                        question = "优先修哪块？",
                        options = listOf(AskOptionUi("登录"), AskOptionUi("发布"))
                    )
                ),
                recentSessionHistoryClue = SessionHistoryReferenceClueUi(
                    messageReferences = listOf("session-login#21")
                )
            ),
            state = SessionState(
                pendingWorkflowPlan = WorkflowPlanUi(
                    goal = "修复问题",
                    summary = "先确认发布链路",
                    recentSessionHistoryClue = SessionHistoryReferenceClueUi(
                        queries = listOf("发布"),
                        sessionIds = listOf("session-release")
                    )
                )
            ),
            recentSessionHistoryClue = SessionHistoryReferenceClueUi(
                snippets = listOf("登录态过期后需要补 token 刷新")
            )
        )

        assertEquals(listOf("发布"), request.recentSessionHistoryClue?.queries)
        assertEquals(listOf("session-release"), request.recentSessionHistoryClue?.sessionIds)
        assertEquals(listOf("session-login#21"), request.recentSessionHistoryClue?.messageReferences)
        assertEquals(listOf("登录态过期后需要补 token 刷新"), request.recentSessionHistoryClue?.snippets)
    }

    @Test
    fun buildStateAwareLocalFallbackClarificationRequest_mergesSessionAndLocalHistoryClues() {
        val request = buildStateAwareLocalFallbackClarificationRequest(
            goal = "修复问题",
            mentionedFiles = listOf(
                FileMentionUi(path = "a.kt", displayPath = "a.kt"),
                FileMentionUi(path = "a.kt", displayPath = "src/a.kt")
            ),
            previousAnswers = listOf(
                ClarificationAnswerUi(
                    question = "先确认哪一块？",
                    answer = "先修登录"
                )
            ),
            turnIndex = 2,
            maxTurns = 4,
            source = ClarificationSource.AUTO_ROUTE,
            state = SessionState(
                pendingWorkflowPlan = WorkflowPlanUi(
                    goal = "修复问题",
                    summary = "先确认历史约束",
                    recentSessionHistoryClue = SessionHistoryReferenceClueUi(
                        queries = listOf("发布"),
                        sessionIds = listOf("session-release")
                    )
                )
            ),
            recentSessionHistoryClue = SessionHistoryReferenceClueUi(
                messageReferences = listOf("session-login#21"),
                snippets = listOf("登录态过期后需要补 token 刷新")
            )
        )

        assertEquals("修复问题", request.goal)
        assertEquals("为了继续准确执行，请再补充目前最关键的限制条件、优先级或预期结果。", request.question)
        assertEquals(listOf("a.kt"), request.mentionedFiles.map { it.path })
        assertEquals(listOf("发布"), request.recentSessionHistoryClue?.queries)
        assertEquals(listOf("session-release"), request.recentSessionHistoryClue?.sessionIds)
        assertEquals(listOf("session-login#21"), request.recentSessionHistoryClue?.messageReferences)
        assertEquals(listOf("登录态过期后需要补 token 刷新"), request.recentSessionHistoryClue?.snippets)
    }

    @Test
    fun clearRecoveredFinalReadinessState_whenAuditRecovered_clearsReceiptAndOnlyReadinessErrors() {
        val cleared = clearRecoveredFinalReadinessState(
            state = SessionState(
                recentErrors = listOf(
                    ErrorRecordUi(
                        message = "最终收口校验未通过：缺少 complete_step。",
                        kind = ErrorRecordKind.FINAL_READINESS
                    ),
                    ErrorRecordUi(message = "普通网络错误", kind = ErrorRecordKind.GENERAL)
                ),
                lastFinalReadinessReceipt = FinalReadinessReceipt(
                    kind = FinalReadinessReceiptKind.MISSING_COMPLETE_STEP_AFTER_WRITE,
                    requiredAction = FinalReadinessRequiredAction.SIGN_OFF_WITH_EVIDENCE,
                    message = "最终收口校验未通过：缺少 complete_step。"
                )
            ),
            audit = FinalReadinessAuditRecord(
                result = FinalReadinessAuditResult.ALLOWED,
                recovered = true,
                receiptKind = FinalReadinessReceiptKind.MISSING_COMPLETE_STEP_AFTER_WRITE,
                requiredAction = FinalReadinessRequiredAction.SIGN_OFF_WITH_EVIDENCE
            )
        )

        assertNull(cleared.lastFinalReadinessReceipt)
        assertEquals(1, cleared.recentErrors.size)
        assertEquals(ErrorRecordKind.GENERAL, cleared.recentErrors.single().kind)
        assertEquals("普通网络错误", cleared.recentErrors.single().message)
    }

    @Test
    fun clearRecoveredFinalReadinessState_whenAuditNotRecovered_keepsExistingState() {
        val state = SessionState(
            recentErrors = listOf(
                ErrorRecordUi(
                    message = "最终收口校验未通过：缺少 complete_step。",
                    kind = ErrorRecordKind.FINAL_READINESS
                )
            ),
            lastFinalReadinessReceipt = FinalReadinessReceipt(
                kind = FinalReadinessReceiptKind.MISSING_COMPLETE_STEP_AFTER_WRITE,
                requiredAction = FinalReadinessRequiredAction.SIGN_OFF_WITH_EVIDENCE,
                message = "最终收口校验未通过：缺少 complete_step。"
            )
        )

        val unchanged = clearRecoveredFinalReadinessState(
            state = state,
            audit = FinalReadinessAuditRecord(
                result = FinalReadinessAuditResult.BLOCKED,
                recovered = false,
                receiptKind = FinalReadinessReceiptKind.MISSING_COMPLETE_STEP_AFTER_WRITE,
                requiredAction = FinalReadinessRequiredAction.SIGN_OFF_WITH_EVIDENCE
            )
        )

        assertNotNull(unchanged.lastFinalReadinessReceipt)
        assertEquals(1, unchanged.recentErrors.size)
        assertEquals(ErrorRecordKind.FINAL_READINESS, unchanged.recentErrors.single().kind)
    }

    @Test
    fun normalizeRecoveredFinalReadinessState_whenLatestAuditRecovered_clearsLegacyReceiptAndErrors() {
        val normalized = normalizeRecoveredFinalReadinessState(
            SessionState(
                recentErrors = listOf(
                    ErrorRecordUi(
                        message = "最终收口校验未通过：缺少 complete_step。",
                        kind = ErrorRecordKind.GENERAL
                    ),
                    ErrorRecordUi(
                        message = "普通网络错误",
                        kind = ErrorRecordKind.GENERAL
                    )
                ),
                recentFinalReadinessAudits = listOf(
                    FinalReadinessAuditRecord(
                        result = FinalReadinessAuditResult.ALLOWED,
                        recovered = true,
                        receiptKind = FinalReadinessReceiptKind.MISSING_COMPLETE_STEP_AFTER_WRITE,
                        requiredAction = FinalReadinessRequiredAction.SIGN_OFF_WITH_EVIDENCE
                    ),
                    FinalReadinessAuditRecord(
                        result = FinalReadinessAuditResult.BLOCKED,
                        recovered = false,
                        receiptKind = FinalReadinessReceiptKind.MISSING_COMPLETE_STEP_AFTER_WRITE,
                        requiredAction = FinalReadinessRequiredAction.SIGN_OFF_WITH_EVIDENCE
                    )
                ),
                lastFinalReadinessReceipt = FinalReadinessReceipt(
                    kind = FinalReadinessReceiptKind.MISSING_COMPLETE_STEP_AFTER_WRITE,
                    requiredAction = FinalReadinessRequiredAction.SIGN_OFF_WITH_EVIDENCE,
                    message = "最终收口校验未通过：缺少 complete_step。"
                )
            )
        )

        assertNull(normalized.lastFinalReadinessReceipt)
        assertEquals(1, normalized.recentErrors.size)
        assertEquals("普通网络错误", normalized.recentErrors.single().message)
    }
}
