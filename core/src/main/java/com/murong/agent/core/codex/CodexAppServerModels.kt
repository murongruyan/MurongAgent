package com.murong.agent.core.codex

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Transport-neutral models for the Codex app-server protocol.
 *
 * The app-server protocol resembles JSON-RPC 2.0, but deliberately omits the
 * `jsonrpc` member on the wire. Every decoded model keeps its raw JSON object so
 * newer server fields remain available to callers without a library update.
 *
 * @see <a href="https://github.com/openai/codex/blob/main/codex-rs/app-server/README.md">Codex app-server README</a>
 */
sealed interface CodexRpcId {
    data class Number(val value: Long) : CodexRpcId
    data class Text(val value: String) : CodexRpcId
}

enum class CodexRequestKind {
    INITIALIZE,
    ACCOUNT_READ,
    ACCOUNT_RATE_LIMITS_READ,
    MODEL_LIST,
    ACCOUNT_LOGIN_DEVICE_CODE,
    ACCOUNT_LOGIN_CANCEL,
    ACCOUNT_LOGOUT,
    THREAD_START,
    THREAD_RESUME,
    TURN_START,
    TURN_INTERRUPT,
    UNKNOWN,
}

sealed interface CodexClientMessage

data class CodexClientRequest(
    val id: CodexRpcId,
    val method: String,
    val params: JsonObject? = null,
    val kind: CodexRequestKind = CodexRequestKind.UNKNOWN,
) : CodexClientMessage

data class CodexClientNotification(
    val method: String,
    val params: JsonObject? = null,
) : CodexClientMessage

data class CodexClientResponse(
    val id: CodexRpcId,
    val result: JsonElement,
) : CodexClientMessage

sealed interface CodexServerMessage {
    val raw: JsonObject

    data class Response(
        val id: CodexRpcId,
        val result: JsonElement?,
        override val raw: JsonObject,
    ) : CodexServerMessage

    data class ErrorResponse(
        val id: CodexRpcId?,
        val error: CodexRpcError,
        override val raw: JsonObject,
    ) : CodexServerMessage

    data class Notification(
        val method: String,
        val params: JsonObject,
        val event: CodexNotificationEvent,
        override val raw: JsonObject,
    ) : CodexServerMessage

    data class ServerRequest(
        val id: CodexRpcId,
        val method: String,
        val params: JsonObject,
        val request: CodexServerRequestPayload,
        override val raw: JsonObject,
    ) : CodexServerMessage
}

data class CodexRpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null,
    val raw: JsonObject,
) {
    /** The app-server documents -32001 as a retryable overload response. */
    val isRetryableOverload: Boolean get() = code == -32001
}

data class CodexClientInfo(
    val name: String,
    val title: String,
    val version: String,
)

data class CodexInitializeCapabilities(
    val experimentalApi: Boolean? = null,
    val optOutNotificationMethods: List<String> = emptyList(),
    val mcpServerOpenaiFormElicitation: Boolean? = null,
    val extra: JsonObject = JsonObject(emptyMap()),
)

/** Common stable thread options; [extra] carries newer app-server options verbatim. */
data class CodexThreadOptions(
    val model: String? = null,
    val cwd: String? = null,
    val approvalPolicy: String? = null,
    val approvalsReviewer: String? = null,
    val sandbox: String? = null,
    val baseInstructions: String? = null,
    val developerInstructions: String? = null,
    val personality: String? = null,
    val ephemeral: Boolean? = null,
    val extra: JsonObject = JsonObject(emptyMap()),
)

/** Official `turn/start.additionalContext` source classification. */
enum class CodexAdditionalContextKind(val wireValue: String) {
    APPLICATION("application"),
    UNTRUSTED("untrusted"),
}

data class CodexAdditionalContext(
    val value: String,
    val kind: CodexAdditionalContextKind = CodexAdditionalContextKind.APPLICATION,
)

sealed interface CodexUserInput {
    data class Text(val text: String) : CodexUserInput
    data class Image(val url: String) : CodexUserInput
    data class LocalImage(val path: String) : CodexUserInput

    /** Allows a newer input variant to pass through without a protocol release. */
    data class Raw(val value: JsonObject) : CodexUserInput
}

data class CodexTurnOptions(
    val clientUserMessageId: String? = null,
    val cwd: String? = null,
    val approvalPolicy: String? = null,
    val approvalsReviewer: String? = null,
    val sandboxPolicy: JsonObject? = null,
    val model: String? = null,
    val effort: String? = null,
    val serviceTier: String? = null,
    val summary: String? = null,
    val personality: String? = null,
    val outputSchema: JsonObject? = null,
    val additionalContext: Map<String, CodexAdditionalContext> = emptyMap(),
    val extra: JsonObject = JsonObject(emptyMap()),
)

sealed interface CodexResponseResult {
    val raw: JsonObject

    data class Initialize(
        val value: CodexInitializeResult,
        override val raw: JsonObject,
    ) : CodexResponseResult

    data class AccountRead(
        val value: CodexAccountReadResult,
        override val raw: JsonObject,
    ) : CodexResponseResult

    data class RateLimits(
        val value: CodexRateLimitsSnapshot,
        override val raw: JsonObject,
    ) : CodexResponseResult

    data class ModelList(
        val value: CodexModelCatalog,
        override val raw: JsonObject,
    ) : CodexResponseResult

    data class DeviceCode(
        val value: CodexDeviceCode,
        override val raw: JsonObject,
    ) : CodexResponseResult

    data class Thread(
        val value: CodexThread,
        override val raw: JsonObject,
    ) : CodexResponseResult

    data class Turn(
        val value: CodexTurn,
        override val raw: JsonObject,
    ) : CodexResponseResult

    data class Empty(override val raw: JsonObject) : CodexResponseResult
    data class Unknown(override val raw: JsonObject) : CodexResponseResult
}

data class CodexInitializeResult(
    val userAgent: String?,
    val codexHome: String?,
    val platformFamily: String?,
    val platformOs: String?,
    val raw: JsonObject,
)

data class CodexAccountReadResult(
    val account: CodexAccount?,
    val requiresOpenaiAuth: Boolean?,
    val raw: JsonObject,
)

data class CodexAccount(
    val type: String?,
    val email: String?,
    val planType: String?,
    val raw: JsonObject,
)

/** A single rolling ChatGPT/Codex allowance window supplied by app-server. */
data class CodexRateLimitWindow(
    val usedPercent: Int?,
    val windowDurationMins: Int?,
    /** Unix time in seconds, supplied by the official service. */
    val resetsAt: Long?,
    val raw: JsonObject,
)

/** Snapshot returned by the official `account/rateLimits/read` RPC. */
data class CodexRateLimits(
    val primary: CodexRateLimitWindow?,
    val secondary: CodexRateLimitWindow?,
    val rateLimitReachedType: String?,
    val raw: JsonObject,
)

data class CodexRateLimitsSnapshot(
    val rateLimits: CodexRateLimits?,
    val resetCreditsAvailableCount: Int?,
    val raw: JsonObject,
)

/** Model capability information advertised by the official app-server. */
data class CodexModelCatalog(
    val models: List<CodexModelDescriptor>,
    /** Opaque cursor for the next server-advertised page, if any. */
    val nextCursor: String? = null,
    val raw: JsonObject,
)

data class CodexModelDescriptor(
    val id: String,
    val displayName: String?,
    val description: String?,
    val isDefault: Boolean?,
    val hidden: Boolean?,
    /** Preserve server order: it expresses the intended effort progression. */
    val supportedReasoningEfforts: List<String>,
    val defaultReasoningEffort: String?,
    val speedTiers: List<CodexSpeedTier>,
    val defaultSpeedTier: String?,
    val raw: JsonObject,
)

data class CodexSpeedTier(
    val id: String,
    val displayName: String?,
    val description: String?,
    val raw: JsonObject,
)

data class CodexDeviceCode(
    val type: String?,
    val loginId: String?,
    val verificationUrl: String?,
    val userCode: String?,
    val raw: JsonObject,
)

data class CodexThread(
    val id: String?,
    val preview: String?,
    val modelProvider: String?,
    val createdAt: Long?,
    val status: String?,
    val turns: List<CodexTurn>,
    val raw: JsonObject,
)

data class CodexTurn(
    val id: String?,
    val status: String?,
    val items: List<CodexThreadItem>,
    val error: CodexTurnError?,
    val raw: JsonObject,
)

data class CodexTurnError(
    val message: String?,
    val codexErrorInfo: JsonElement?,
    val additionalDetails: JsonElement?,
    val raw: JsonObject,
)

/** Minimal projection of the tagged ThreadItem union. [raw] is authoritative. */
data class CodexThreadItem(
    val id: String?,
    val type: String?,
    val status: String?,
    val text: String?,
    val raw: JsonObject,
)

sealed interface CodexNotificationEvent {
    val method: String
    val rawParams: JsonObject

    data class AccountUpdated(
        val authMode: String?,
        val planType: String?,
        override val rawParams: JsonObject,
    ) : CodexNotificationEvent {
        override val method: String = "account/updated"
    }

    data class LoginCompleted(
        val loginId: String?,
        val success: Boolean?,
        val error: String?,
        override val rawParams: JsonObject,
    ) : CodexNotificationEvent {
        override val method: String = "account/login/completed"
    }

    /** Sparse update; missing fields must retain values from the last snapshot. */
    data class RateLimitsUpdated(
        val rateLimits: CodexRateLimits,
        override val rawParams: JsonObject,
    ) : CodexNotificationEvent {
        override val method: String = "account/rateLimits/updated"
    }

    data class ThreadStarted(
        val thread: CodexThread,
        override val rawParams: JsonObject,
    ) : CodexNotificationEvent {
        override val method: String = "thread/started"
    }

    data class TurnStarted(
        val turn: CodexTurn,
        override val rawParams: JsonObject,
    ) : CodexNotificationEvent {
        override val method: String = "turn/started"
    }

    data class TurnCompleted(
        val turn: CodexTurn,
        override val rawParams: JsonObject,
    ) : CodexNotificationEvent {
        override val method: String = "turn/completed"
    }

    data class AgentMessageDelta(
        val threadId: String?,
        val turnId: String?,
        val itemId: String?,
        val delta: String,
        override val rawParams: JsonObject,
    ) : CodexNotificationEvent {
        override val method: String = "item/agentMessage/delta"
    }

    data class ReasoningDelta(
        val kind: CodexReasoningDeltaKind,
        val threadId: String?,
        val turnId: String?,
        val itemId: String?,
        val delta: String,
        val index: Int?,
        override val rawParams: JsonObject,
    ) : CodexNotificationEvent {
        override val method: String = when (kind) {
            CodexReasoningDeltaKind.SUMMARY -> "item/reasoning/summaryTextDelta"
            CodexReasoningDeltaKind.CONTENT -> "item/reasoning/textDelta"
        }
    }

    data class ItemStarted(
        val threadId: String?,
        val turnId: String?,
        val item: CodexThreadItem,
        override val rawParams: JsonObject,
    ) : CodexNotificationEvent {
        override val method: String = "item/started"
    }

    data class ItemCompleted(
        val threadId: String?,
        val turnId: String?,
        val item: CodexThreadItem,
        override val rawParams: JsonObject,
    ) : CodexNotificationEvent {
        override val method: String = "item/completed"
    }

    data class Error(
        val error: CodexTurnError,
        override val rawParams: JsonObject,
    ) : CodexNotificationEvent {
        override val method: String = "error"
    }

    data class ServerRequestResolved(
        val threadId: String?,
        val requestId: CodexRpcId?,
        override val rawParams: JsonObject,
    ) : CodexNotificationEvent {
        override val method: String = "serverRequest/resolved"
    }

    data class Unknown(
        override val method: String,
        override val rawParams: JsonObject,
    ) : CodexNotificationEvent
}

enum class CodexReasoningDeltaKind {
    SUMMARY,
    CONTENT,
}

sealed interface CodexServerRequestPayload {
    val method: String
    val rawParams: JsonObject

    data class Approval(
        val kind: CodexApprovalKind,
        val threadId: String?,
        val turnId: String?,
        val itemId: String?,
        val reason: String?,
        val environmentId: String?,
        val command: JsonElement?,
        val cwd: String?,
        val commandActions: JsonArray?,
        val grantRoot: String?,
        val availableDecisions: JsonArray?,
        val permissions: JsonObject?,
        override val method: String,
        override val rawParams: JsonObject,
    ) : CodexServerRequestPayload

    data class Unknown(
        override val method: String,
        override val rawParams: JsonObject,
    ) : CodexServerRequestPayload
}

enum class CodexApprovalKind {
    COMMAND_EXECUTION,
    FILE_CHANGE,
    PERMISSIONS,
    UNKNOWN,
}

enum class CodexApprovalDecision(val wireValue: String) {
    ACCEPT("accept"),
    ACCEPT_FOR_SESSION("acceptForSession"),
    DECLINE("decline"),
    CANCEL("cancel"),
}

class CodexProtocolException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)
