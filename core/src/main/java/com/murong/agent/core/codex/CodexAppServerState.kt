package com.murong.agent.core.codex

/** Connection phase for the required initialize -> initialized handshake. */
enum class CodexConnectionPhase {
    NEW,
    INITIALIZING,
    INITIALIZE_ACCEPTED,
    READY,
    FAILED,
}

enum class CodexLoginStatus {
    IDLE,
    WAITING_FOR_DEVICE_AUTHORIZATION,
    CANCEL_REQUESTED,
    SUCCEEDED,
    FAILED,
}

data class CodexLoginState(
    val status: CodexLoginStatus = CodexLoginStatus.IDLE,
    val loginId: String? = null,
    val verificationUrl: String? = null,
    val userCode: String? = null,
    val error: String? = null,
)

/**
 * Small immutable state projection suitable for a ViewModel, service, or CLI.
 * It has no transport or Android dependency and can be rebuilt solely from
 * sent/received protocol messages.
 */
data class CodexAppServerState(
    val connectionPhase: CodexConnectionPhase = CodexConnectionPhase.NEW,
    val pendingRequests: Map<CodexRpcId, CodexClientRequest> = emptyMap(),
    val initializeResult: CodexInitializeResult? = null,
    val account: CodexAccount? = null,
    val requiresOpenaiAuth: Boolean? = null,
    val authMode: String? = null,
    val planType: String? = null,
    val rateLimits: CodexRateLimitsSnapshot? = null,
    val login: CodexLoginState = CodexLoginState(),
    val currentThread: CodexThread? = null,
    val activeTurn: CodexTurn? = null,
    val items: Map<String, CodexThreadItem> = emptyMap(),
    val agentTextByItemId: Map<String, String> = emptyMap(),
    val reasoningSummaryByItemId: Map<String, String> = emptyMap(),
    val reasoningContentByItemId: Map<String, String> = emptyMap(),
    val pendingApprovals: Map<CodexRpcId, CodexServerRequestPayload.Approval> = emptyMap(),
    val lastRpcError: CodexRpcError? = null,
    val lastTurnError: CodexTurnError? = null,
)

data class CodexStateUpdate(
    val state: CodexAppServerState,
    /** Typed response result when the response id matched a recorded request. */
    val responseResult: CodexResponseResult? = null,
)

object CodexAppServerStateReducer {
    /** Record a message immediately before the transport sends it. */
    fun sent(
        state: CodexAppServerState,
        message: CodexClientMessage,
    ): CodexAppServerState = when (message) {
        is CodexClientRequest -> {
            val nextPhase = if (message.kind == CodexRequestKind.INITIALIZE) {
                CodexConnectionPhase.INITIALIZING
            } else {
                state.connectionPhase
            }
            state.copy(
                connectionPhase = nextPhase,
                pendingRequests = state.pendingRequests + (message.id to message),
                lastRpcError = null,
            )
        }

        is CodexClientNotification -> if (message.method == "initialized") {
            state.copy(connectionPhase = CodexConnectionPhase.READY)
        } else {
            state
        }

        is CodexClientResponse -> state
    }

    fun received(
        state: CodexAppServerState,
        message: CodexServerMessage,
    ): CodexStateUpdate = when (message) {
        is CodexServerMessage.Response -> reduceResponse(state, message)
        is CodexServerMessage.ErrorResponse -> reduceRpcError(state, message)
        is CodexServerMessage.Notification -> CodexStateUpdate(
            reduceNotification(state, message.event),
        )

        is CodexServerMessage.ServerRequest -> CodexStateUpdate(
            reduceServerRequest(state, message),
        )
    }

    private fun reduceResponse(
        state: CodexAppServerState,
        response: CodexServerMessage.Response,
    ): CodexStateUpdate {
        val request = state.pendingRequests[response.id]
        val withoutPending = state.copy(pendingRequests = state.pendingRequests - response.id)
        if (request == null) return CodexStateUpdate(withoutPending)

        val result = CodexAppServerCodec.decodeResult(request, response)
        val next = when (result) {
            is CodexResponseResult.Initialize -> withoutPending.copy(
                connectionPhase = CodexConnectionPhase.INITIALIZE_ACCEPTED,
                initializeResult = result.value,
            )

            is CodexResponseResult.AccountRead -> withoutPending.copy(
                account = result.value.account,
                requiresOpenaiAuth = result.value.requiresOpenaiAuth,
                planType = result.value.account?.planType ?: withoutPending.planType,
            )

            is CodexResponseResult.RateLimits -> withoutPending.copy(
                rateLimits = result.value,
            )

            is CodexResponseResult.ModelList -> withoutPending

            is CodexResponseResult.DeviceCode -> withoutPending.copy(
                login = CodexLoginState(
                    status = CodexLoginStatus.WAITING_FOR_DEVICE_AUTHORIZATION,
                    loginId = result.value.loginId,
                    verificationUrl = result.value.verificationUrl,
                    userCode = result.value.userCode,
                ),
            )

            is CodexResponseResult.Thread -> withoutPending.copy(currentThread = result.value)
            is CodexResponseResult.Turn -> withoutPending.copy(activeTurn = result.value)
            is CodexResponseResult.Empty -> when (request.kind) {
                CodexRequestKind.ACCOUNT_LOGIN_CANCEL -> withoutPending.copy(
                    login = CodexLoginState(status = CodexLoginStatus.CANCEL_REQUESTED),
                )

                else -> withoutPending
            }

            is CodexResponseResult.Unknown -> withoutPending
        }
        return CodexStateUpdate(state = next, responseResult = result)
    }

    private fun reduceRpcError(
        state: CodexAppServerState,
        response: CodexServerMessage.ErrorResponse,
    ): CodexStateUpdate {
        val request = response.id?.let(state.pendingRequests::get)
        val pending = response.id?.let { state.pendingRequests - it } ?: state.pendingRequests
        return CodexStateUpdate(
            state.copy(
                connectionPhase = if (request?.kind == CodexRequestKind.INITIALIZE) {
                    CodexConnectionPhase.FAILED
                } else {
                    state.connectionPhase
                },
                pendingRequests = pending,
                lastRpcError = response.error,
            ),
        )
    }

    private fun reduceNotification(
        state: CodexAppServerState,
        event: CodexNotificationEvent,
    ): CodexAppServerState = when (event) {
        is CodexNotificationEvent.AccountUpdated -> state.copy(
            account = if (event.authMode == null) null else state.account,
            authMode = event.authMode,
            planType = event.planType,
        )

        is CodexNotificationEvent.LoginCompleted -> state.copy(
            login = state.login.copy(
                status = if (event.success == true) {
                    CodexLoginStatus.SUCCEEDED
                } else {
                    CodexLoginStatus.FAILED
                },
                loginId = event.loginId ?: state.login.loginId,
                error = event.error,
            ),
        )

        is CodexNotificationEvent.RateLimitsUpdated -> state.copy(
            rateLimits = state.rateLimits.mergeSparse(event.rateLimits),
        )

        is CodexNotificationEvent.ThreadStarted -> state.copy(currentThread = event.thread)
        is CodexNotificationEvent.TurnStarted -> state.copy(activeTurn = event.turn)
        is CodexNotificationEvent.TurnCompleted -> state.copy(
            activeTurn = event.turn,
            lastTurnError = event.turn.error,
        )

        is CodexNotificationEvent.AgentMessageDelta -> {
            val itemId = event.itemId ?: return state
            state.copy(
                agentTextByItemId = state.agentTextByItemId.append(itemId, event.delta),
            )
        }

        is CodexNotificationEvent.ReasoningDelta -> {
            val itemId = event.itemId ?: return state
            if (event.kind == CodexReasoningDeltaKind.SUMMARY) {
                state.copy(
                    reasoningSummaryByItemId = state.reasoningSummaryByItemId.append(
                        itemId,
                        event.delta,
                    ),
                )
            } else {
                state.copy(
                    reasoningContentByItemId = state.reasoningContentByItemId.append(
                        itemId,
                        event.delta,
                    ),
                )
            }
        }

        is CodexNotificationEvent.ItemStarted -> state.withItem(event.item)
        is CodexNotificationEvent.ItemCompleted -> state.withItem(event.item)
        is CodexNotificationEvent.Error -> state.copy(lastTurnError = event.error)
        is CodexNotificationEvent.ServerRequestResolved -> {
            val requestId = event.requestId ?: return state
            state.copy(pendingApprovals = state.pendingApprovals - requestId)
        }

        is CodexNotificationEvent.Unknown -> state
    }

    private fun reduceServerRequest(
        state: CodexAppServerState,
        message: CodexServerMessage.ServerRequest,
    ): CodexAppServerState = when (val payload = message.request) {
        is CodexServerRequestPayload.Approval -> state.copy(
            pendingApprovals = state.pendingApprovals + (message.id to payload),
        )

        is CodexServerRequestPayload.Unknown -> state
    }

    private fun CodexAppServerState.withItem(item: CodexThreadItem): CodexAppServerState {
        val itemId = item.id ?: return this
        return copy(items = items + (itemId to item))
    }

    private fun Map<String, String>.append(key: String, delta: String): Map<String, String> =
        this + (key to (this[key].orEmpty() + delta))

    private fun CodexRateLimitsSnapshot?.mergeSparse(
        update: CodexRateLimits,
    ): CodexRateLimitsSnapshot {
        val current = this?.rateLimits
        return CodexRateLimitsSnapshot(
            rateLimits = CodexRateLimits(
                primary = update.primary ?: current?.primary,
                secondary = update.secondary ?: current?.secondary,
                rateLimitReachedType = update.rateLimitReachedType ?: current?.rateLimitReachedType,
                raw = update.raw,
            ),
            resetCreditsAvailableCount = this?.resetCreditsAvailableCount,
            raw = update.raw,
        )
    }
}
