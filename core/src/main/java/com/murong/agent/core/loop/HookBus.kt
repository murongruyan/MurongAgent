package com.murong.agent.core.loop

import com.murong.agent.core.mcp.McpServerStatus
import com.murong.agent.core.tool.ApprovalRiskLevel
import com.murong.agent.core.tool.StepSignOffReceipt
import com.murong.agent.core.tool.SubagentUiEvent
import com.murong.agent.core.tool.ToolFileChange
import com.murong.agent.core.tool.ToolStructuredPayload

data class PreToolUseHookContext(
    val toolName: String,
    val args: String,
    val callId: String? = null
)

data class PostToolUseHookContext(
    val toolName: String,
    val args: String,
    val result: String,
    val modelContextResult: String,
    val isSuccess: Boolean,
    val fileChanges: List<ToolFileChange> = emptyList(),
    val stepSignOffReceipt: StepSignOffReceipt? = null,
    val structuredPayload: ToolStructuredPayload? = null
)

data class PostLlmCallHookContext(
    val messageId: Long,
    val content: String,
    val reasoning: String? = null
)

data class PreCompactHookContext(
    val sessionId: String,
    val messageCount: Int,
    val summary: String
)

data class ApprovalRequestedHookContext(
    val toolName: String,
    val summary: String,
    val detail: String,
    val rawArgs: String,
    val riskLevel: ApprovalRiskLevel,
    val requestSubject: String,
    val scopeSummary: String? = null,
    val explanationLabel: String? = null,
    val explanationDetail: String? = null
)

data class ApprovalResolvedHookContext(
    val toolName: String,
    val summary: String,
    val decision: String,
    val scopeSummary: String? = null,
    val explanationLabel: String? = null,
    val explanationDetail: String? = null
)

data class ErrorRecordedHookContext(
    val message: String,
    val kind: ErrorRecordKind = ErrorRecordKind.GENERAL
)

data class SessionLifecycleNoticeHookContext(
    val sessionId: String,
    val notice: String,
    val source: String
)

data class SubagentLifecycleHookContext(
    val event: SubagentUiEvent
)

data class McpConnectionStatusHookContext(
    val trigger: String,
    val attemptedServerNames: List<String> = emptyList(),
    val statuses: List<McpServerStatus> = emptyList()
)

enum class SessionTransitionPhase {
    STOPPED,
    STARTED
}

data class SessionTransitionHookContext(
    val sessionId: String,
    val phase: SessionTransitionPhase,
    val trigger: String,
    val counterpartSessionId: String? = null,
    val sessionTitle: String? = null,
    val projectPath: String? = null
)

data class NotificationHookContext(
    val sessionId: String,
    val channel: String,
    val message: String,
    val source: String
)

data class SystemMessageHookContext(
    val sessionId: String,
    val messageId: Long,
    val content: String,
    val source: String
)

interface HookBusObserver {
    fun onPreToolUse(context: PreToolUseHookContext): PreToolUseHookContext = context
    fun onPostToolUse(context: PostToolUseHookContext): PostToolUseHookContext = context
    fun onPostLlmCall(context: PostLlmCallHookContext): PostLlmCallHookContext = context
    fun onPreCompact(context: PreCompactHookContext): PreCompactHookContext = context
    fun onApprovalRequested(context: ApprovalRequestedHookContext): ApprovalRequestedHookContext = context
    fun onApprovalResolved(context: ApprovalResolvedHookContext): ApprovalResolvedHookContext = context
    fun onErrorRecorded(context: ErrorRecordedHookContext): ErrorRecordedHookContext = context
    fun onSessionLifecycleNotice(context: SessionLifecycleNoticeHookContext): SessionLifecycleNoticeHookContext = context
    fun onSubagentLifecycle(context: SubagentLifecycleHookContext): SubagentLifecycleHookContext = context
    fun onMcpConnectionStatus(context: McpConnectionStatusHookContext): McpConnectionStatusHookContext = context
    fun onSessionTransition(context: SessionTransitionHookContext): SessionTransitionHookContext = context
    fun onNotification(context: NotificationHookContext): NotificationHookContext = context
    fun onSystemMessage(context: SystemMessageHookContext): SystemMessageHookContext = context
}

class HookBusRunner(
    private val observers: List<HookBusObserver> = emptyList()
) {
    fun dispatchPreToolUse(context: PreToolUseHookContext): PreToolUseHookContext {
        return observers.fold(context) { acc, observer ->
            runCatching { observer.onPreToolUse(acc) }.getOrElse { acc }
        }
    }

    fun dispatchPostToolUse(context: PostToolUseHookContext): PostToolUseHookContext {
        return observers.fold(context) { acc, observer ->
            runCatching { observer.onPostToolUse(acc) }.getOrElse { acc }
        }
    }

    fun dispatchPostLlmCall(context: PostLlmCallHookContext): PostLlmCallHookContext {
        return observers.fold(context) { acc, observer ->
            runCatching { observer.onPostLlmCall(acc) }.getOrElse { acc }
        }
    }

    fun dispatchPreCompact(context: PreCompactHookContext): PreCompactHookContext {
        return observers.fold(context) { acc, observer ->
            runCatching { observer.onPreCompact(acc) }.getOrElse { acc }
        }
    }

    fun dispatchApprovalRequested(context: ApprovalRequestedHookContext): ApprovalRequestedHookContext {
        return observers.fold(context) { acc, observer ->
            runCatching { observer.onApprovalRequested(acc) }.getOrElse { acc }
        }
    }

    fun dispatchApprovalResolved(context: ApprovalResolvedHookContext): ApprovalResolvedHookContext {
        return observers.fold(context) { acc, observer ->
            runCatching { observer.onApprovalResolved(acc) }.getOrElse { acc }
        }
    }

    fun dispatchErrorRecorded(context: ErrorRecordedHookContext): ErrorRecordedHookContext {
        return observers.fold(context) { acc, observer ->
            runCatching { observer.onErrorRecorded(acc) }.getOrElse { acc }
        }
    }

    fun dispatchSessionLifecycleNotice(context: SessionLifecycleNoticeHookContext): SessionLifecycleNoticeHookContext {
        return observers.fold(context) { acc, observer ->
            runCatching { observer.onSessionLifecycleNotice(acc) }.getOrElse { acc }
        }
    }

    fun dispatchSubagentLifecycle(context: SubagentLifecycleHookContext): SubagentLifecycleHookContext {
        return observers.fold(context) { acc, observer ->
            runCatching { observer.onSubagentLifecycle(acc) }.getOrElse { acc }
        }
    }

    fun dispatchMcpConnectionStatus(context: McpConnectionStatusHookContext): McpConnectionStatusHookContext {
        return observers.fold(context) { acc, observer ->
            runCatching { observer.onMcpConnectionStatus(acc) }.getOrElse { acc }
        }
    }

    fun dispatchSessionTransition(context: SessionTransitionHookContext): SessionTransitionHookContext {
        return observers.fold(context) { acc, observer ->
            runCatching { observer.onSessionTransition(acc) }.getOrElse { acc }
        }
    }

    fun dispatchNotification(context: NotificationHookContext): NotificationHookContext {
        return observers.fold(context) { acc, observer ->
            runCatching { observer.onNotification(acc) }.getOrElse { acc }
        }
    }

    fun dispatchSystemMessage(context: SystemMessageHookContext): SystemMessageHookContext {
        return observers.fold(context) { acc, observer ->
            runCatching { observer.onSystemMessage(acc) }.getOrElse { acc }
        }
    }
}

internal fun applyPostLlmHookToMessage(
    message: ChatMessageUi,
    hookBus: HookBusRunner
): ChatMessageUi {
    val updated = hookBus.dispatchPostLlmCall(
        PostLlmCallHookContext(
            messageId = message.id,
            content = message.content,
            reasoning = message.reasoning
        )
    )
    return message.copy(
        content = updated.content,
        reasoning = updated.reasoning
    )
}

internal fun applyPreCompactHookToSummary(
    sessionId: String,
    messageCount: Int,
    summary: String,
    hookBus: HookBusRunner
): String {
    return hookBus.dispatchPreCompact(
        PreCompactHookContext(
            sessionId = sessionId,
            messageCount = messageCount,
            summary = summary
        )
    ).summary
}

internal fun applyApprovalRequestedHook(
    toolName: String,
    summary: String,
    detail: String,
    rawArgs: String,
    riskLevel: ApprovalRiskLevel,
    requestSubject: String,
    scopeSummary: String?,
    explanationLabel: String?,
    explanationDetail: String?,
    hookBus: HookBusRunner
): ApprovalRequestedHookContext {
    return hookBus.dispatchApprovalRequested(
        ApprovalRequestedHookContext(
            toolName = toolName,
            summary = summary,
            detail = detail,
            rawArgs = rawArgs,
            riskLevel = riskLevel,
            requestSubject = requestSubject,
            scopeSummary = scopeSummary,
            explanationLabel = explanationLabel,
            explanationDetail = explanationDetail
        )
    )
}

internal fun applyApprovalResolvedHook(
    toolName: String,
    summary: String,
    decision: String,
    scopeSummary: String?,
    explanationLabel: String?,
    explanationDetail: String?,
    hookBus: HookBusRunner
): ApprovalResolvedHookContext {
    return hookBus.dispatchApprovalResolved(
        ApprovalResolvedHookContext(
            toolName = toolName,
            summary = summary,
            decision = decision,
            scopeSummary = scopeSummary,
            explanationLabel = explanationLabel,
            explanationDetail = explanationDetail
        )
    )
}

internal fun applyErrorRecordedHook(
    message: String,
    kind: ErrorRecordKind,
    hookBus: HookBusRunner
): ErrorRecordUi {
    val updated = hookBus.dispatchErrorRecorded(
        ErrorRecordedHookContext(
            message = message,
            kind = kind
        )
    )
    return ErrorRecordUi(
        message = updated.message,
        kind = updated.kind
    )
}

internal fun applySessionLifecycleNoticeHook(
    sessionId: String,
    notice: String,
    source: String,
    hookBus: HookBusRunner
): String {
    return hookBus.dispatchSessionLifecycleNotice(
        SessionLifecycleNoticeHookContext(
            sessionId = sessionId,
            notice = notice,
            source = source
        )
    ).notice
}

internal fun applySubagentLifecycleHook(
    event: SubagentUiEvent,
    hookBus: HookBusRunner
): SubagentUiEvent {
    return hookBus.dispatchSubagentLifecycle(
        SubagentLifecycleHookContext(event = event)
    ).event
}

internal fun applyMcpConnectionStatusHook(
    trigger: String,
    attemptedServerNames: List<String>,
    statuses: List<McpServerStatus>,
    hookBus: HookBusRunner
): McpConnectionStatusHookContext {
    return hookBus.dispatchMcpConnectionStatus(
        McpConnectionStatusHookContext(
            trigger = trigger,
            attemptedServerNames = attemptedServerNames,
            statuses = statuses
        )
    )
}

internal fun applySessionTransitionHook(
    sessionId: String,
    phase: SessionTransitionPhase,
    trigger: String,
    counterpartSessionId: String?,
    sessionTitle: String?,
    projectPath: String?,
    hookBus: HookBusRunner
): SessionTransitionHookContext {
    return hookBus.dispatchSessionTransition(
        SessionTransitionHookContext(
            sessionId = sessionId,
            phase = phase,
            trigger = trigger,
            counterpartSessionId = counterpartSessionId,
            sessionTitle = sessionTitle,
            projectPath = projectPath
        )
    )
}

internal fun applyNotificationHook(
    sessionId: String,
    channel: String,
    message: String,
    source: String,
    hookBus: HookBusRunner
): String {
    return hookBus.dispatchNotification(
        NotificationHookContext(
            sessionId = sessionId,
            channel = channel,
            message = message,
            source = source
        )
    ).message
}

internal fun applySystemMessageHook(
    sessionId: String,
    messageId: Long,
    content: String,
    source: String,
    hookBus: HookBusRunner
): String {
    return hookBus.dispatchSystemMessage(
        SystemMessageHookContext(
            sessionId = sessionId,
            messageId = messageId,
            content = content,
            source = source
        )
    ).content
}
