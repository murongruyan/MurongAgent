package com.murong.agent.core.loop

import com.murong.agent.core.mcp.McpServerStatus
import com.murong.agent.core.tool.ApprovalRiskLevel
import com.murong.agent.core.tool.SubagentUiEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HookBusTest {

    @Test
    fun applyPostLlmHookToMessage_updatesReasoningAndContent() {
        val message = ChatMessageUi(
            id = 7L,
            role = "assistant",
            content = "原始回答",
            reasoning = "原始推理",
            isStreaming = false
        )
        val hookBus = HookBusRunner(
            observers = listOf(
                object : HookBusObserver {
                    override fun onPostLlmCall(context: PostLlmCallHookContext): PostLlmCallHookContext {
                        return context.copy(
                            content = context.content + " [hooked]",
                            reasoning = (context.reasoning ?: "") + " [hooked]"
                        )
                    }
                }
            )
        )

        val updated = applyPostLlmHookToMessage(message, hookBus)

        assertEquals("原始回答 [hooked]", updated.content)
        assertEquals("原始推理 [hooked]", updated.reasoning)
        assertEquals(false, updated.isStreaming)
    }

    @Test
    fun applyPreCompactHookToSummary_updatesCompressionSummary() {
        val summary = applyPreCompactHookToSummary(
            sessionId = "session-1",
            messageCount = 3,
            summary = "基础摘要",
            hookBus = HookBusRunner(
                observers = listOf(
                    object : HookBusObserver {
                        override fun onPreCompact(context: PreCompactHookContext): PreCompactHookContext {
                            return context.copy(
                                summary = "${context.summary}\n- hook session=${context.sessionId}\n- hook messages=${context.messageCount}"
                            )
                        }
                    }
                )
            )
        )

        assertTrue(summary.contains("基础摘要"))
        assertTrue(summary.contains("hook session=session-1"))
        assertTrue(summary.contains("hook messages=3"))
    }

    @Test
    fun dispatchPostToolUse_keepsOriginalContextWhenObserverFails() {
        val context = PostToolUseHookContext(
            toolName = "shell",
            args = """{"command":"pwd"}""",
            result = "ok",
            modelContextResult = "ok",
            isSuccess = true
        )
        val hookBus = HookBusRunner(
            observers = listOf(
                object : HookBusObserver {
                    override fun onPostToolUse(context: PostToolUseHookContext): PostToolUseHookContext {
                        error("boom")
                    }
                }
            )
        )

        val updated = hookBus.dispatchPostToolUse(context)

        assertEquals(context, updated)
    }

    @Test
    fun applyApprovalRequestedHook_updatesPendingApprovalContext() {
        val updated = applyApprovalRequestedHook(
            toolName = "shell",
            summary = "运行命令",
            detail = "等待审批",
            rawArgs = """{"command":"pwd"}""",
            riskLevel = ApprovalRiskLevel.HIGH,
            requestSubject = "命令执行",
            scopeSummary = "shell_exec",
            explanationLabel = "需要确认",
            explanationDetail = "原始说明",
            hookBus = HookBusRunner(
                observers = listOf(
                    object : HookBusObserver {
                        override fun onApprovalRequested(context: ApprovalRequestedHookContext): ApprovalRequestedHookContext {
                            return context.copy(
                                summary = "${context.summary} [hooked]",
                                explanationDetail = "${context.explanationDetail} [hooked]"
                            )
                        }
                    }
                )
            )
        )

        assertEquals("运行命令 [hooked]", updated.summary)
        assertEquals("原始说明 [hooked]", updated.explanationDetail)
        assertEquals("命令执行", updated.requestSubject)
    }

    @Test
    fun applyErrorRecordedHook_updatesErrorRecordBeforeStorage() {
        val record = applyErrorRecordedHook(
            message = "原始错误",
            kind = ErrorRecordKind.GENERAL,
            hookBus = HookBusRunner(
                observers = listOf(
                    object : HookBusObserver {
                        override fun onErrorRecorded(context: ErrorRecordedHookContext): ErrorRecordedHookContext {
                            return context.copy(
                                message = "${context.message} [hooked]",
                                kind = ErrorRecordKind.FINAL_READINESS
                            )
                        }
                    }
                )
            )
        )

        assertEquals("原始错误 [hooked]", record.message)
        assertEquals(ErrorRecordKind.FINAL_READINESS, record.kind)
    }

    @Test
    fun applySessionLifecycleNoticeHook_updatesNoticeContent() {
        val notice = applySessionLifecycleNoticeHook(
            sessionId = "session-1",
            notice = "当前会话仍在处理中",
            source = "guard:load_session",
            hookBus = HookBusRunner(
                observers = listOf(
                    object : HookBusObserver {
                        override fun onSessionLifecycleNotice(context: SessionLifecycleNoticeHookContext): SessionLifecycleNoticeHookContext {
                            return context.copy(
                                notice = "${context.notice} [hooked:${context.source}]"
                            )
                        }
                    }
                )
            )
        )

        assertEquals("当前会话仍在处理中 [hooked:guard:load_session]", notice)
    }

    @Test
    fun applySubagentLifecycleHook_updatesQueuedSubagentEvent() {
        val updated = applySubagentLifecycleHook(
            event = SubagentUiEvent.Queued(
                runId = "run-1",
                goal = "原始任务",
                model = "gpt-test",
                reasoningEffort = "high",
                allowedTools = listOf("read"),
                retryCount = 0
            ),
            hookBus = HookBusRunner(
                observers = listOf(
                    object : HookBusObserver {
                        override fun onSubagentLifecycle(context: SubagentLifecycleHookContext): SubagentLifecycleHookContext {
                            val event = context.event as SubagentUiEvent.Queued
                            return context.copy(
                                event = event.copy(goal = "${event.goal} [hooked]")
                            )
                        }
                    }
                )
            )
        ) as SubagentUiEvent.Queued

        assertEquals("原始任务 [hooked]", updated.goal)
        assertEquals("run-1", updated.runId)
    }

    @Test
    fun applyMcpConnectionStatusHook_keepsAttemptedServersAndUpdatesStatuses() {
        val updated = applyMcpConnectionStatusHook(
            trigger = "create_mcp_server",
            attemptedServerNames = listOf("filesystem"),
            statuses = listOf(
                McpServerStatus(
                    name = "filesystem",
                    connected = false,
                    toolCount = 0,
                    error = "连接失败"
                )
            ),
            hookBus = HookBusRunner(
                observers = listOf(
                    object : HookBusObserver {
                        override fun onMcpConnectionStatus(context: McpConnectionStatusHookContext): McpConnectionStatusHookContext {
                            return context.copy(
                                statuses = context.statuses.map { status ->
                                    status.copy(error = "${status.error} [hooked]")
                                }
                            )
                        }
                    }
                )
            )
        )

        assertEquals("create_mcp_server", updated.trigger)
        assertEquals(listOf("filesystem"), updated.attemptedServerNames)
        assertEquals("连接失败 [hooked]", updated.statuses.single().error)
    }

    @Test
    fun applySessionTransitionHook_updatesTriggerAndTitle() {
        val updated = applySessionTransitionHook(
            sessionId = "session-new",
            phase = SessionTransitionPhase.STARTED,
            trigger = "new_session",
            counterpartSessionId = "session-old",
            sessionTitle = "新对话",
            projectPath = null,
            hookBus = HookBusRunner(
                observers = listOf(
                    object : HookBusObserver {
                        override fun onSessionTransition(context: SessionTransitionHookContext): SessionTransitionHookContext {
                            return context.copy(
                                trigger = "${context.trigger} [hooked]",
                                sessionTitle = "${context.sessionTitle} [hooked]"
                            )
                        }
                    }
                )
            )
        )

        assertEquals(SessionTransitionPhase.STARTED, updated.phase)
        assertEquals("new_session [hooked]", updated.trigger)
        assertEquals("新对话 [hooked]", updated.sessionTitle)
        assertEquals("session-old", updated.counterpartSessionId)
    }

    @Test
    fun applyNotificationHook_updatesReplayNoticeMessage() {
        val updated = applyNotificationHook(
            sessionId = "session-1",
            channel = "pending_prompt_replay",
            message = "检测到恢复态审批",
            source = "session_restore_replay",
            hookBus = HookBusRunner(
                observers = listOf(
                    object : HookBusObserver {
                        override fun onNotification(context: NotificationHookContext): NotificationHookContext {
                            return context.copy(
                                message = "${context.message} [hooked:${context.channel}]"
                            )
                        }
                    }
                )
            )
        )

        assertEquals("检测到恢复态审批 [hooked:pending_prompt_replay]", updated)
    }

    @Test
    fun applySystemMessageHook_updatesVisibleSystemMessageContent() {
        val updated = applySystemMessageHook(
            sessionId = "session-1",
            messageId = 42L,
            content = "⚠️ 未配置 API Key",
            source = "send_message:missing_api_key",
            hookBus = HookBusRunner(
                observers = listOf(
                    object : HookBusObserver {
                        override fun onSystemMessage(context: SystemMessageHookContext): SystemMessageHookContext {
                            return context.copy(
                                content = "${context.content} [hooked:${context.source}]"
                            )
                        }
                    }
                )
            )
        )

        assertEquals("⚠️ 未配置 API Key [hooked:send_message:missing_api_key]", updated)
    }
}
