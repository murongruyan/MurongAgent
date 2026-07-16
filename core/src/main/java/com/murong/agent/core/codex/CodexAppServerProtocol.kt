package com.murong.agent.core.codex

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Creates app-server requests without depending on stdio, sockets, or Android APIs. */
object CodexAppServerProtocol {
    fun initialize(
        id: CodexRpcId,
        clientInfo: CodexClientInfo,
        capabilities: CodexInitializeCapabilities? = null,
    ): CodexClientRequest {
        val params = linkedMapOf<String, JsonElement>()
        params["clientInfo"] = JsonObject(
            linkedMapOf(
                "name" to JsonPrimitive(clientInfo.name),
                "title" to JsonPrimitive(clientInfo.title),
                "version" to JsonPrimitive(clientInfo.version),
            ),
        )
        capabilities?.let { value ->
            val fields = value.extra.toMutableMap()
            value.experimentalApi?.let { fields["experimentalApi"] = JsonPrimitive(it) }
            if (value.optOutNotificationMethods.isNotEmpty()) {
                fields["optOutNotificationMethods"] = JsonArray(
                    value.optOutNotificationMethods.map(::JsonPrimitive),
                )
            }
            value.mcpServerOpenaiFormElicitation?.let {
                fields["mcpServerOpenaiFormElicitation"] = JsonPrimitive(it)
            }
            params["capabilities"] = JsonObject(fields)
        }
        return CodexClientRequest(
            id = id,
            method = "initialize",
            params = JsonObject(params),
            kind = CodexRequestKind.INITIALIZE,
        )
    }

    /** Must be sent after a successful initialize response. */
    fun initialized(): CodexClientNotification = CodexClientNotification(method = "initialized")

    fun accountRead(
        id: CodexRpcId,
        refreshToken: Boolean = false,
    ): CodexClientRequest = CodexClientRequest(
        id = id,
        method = "account/read",
        params = JsonObject(mapOf("refreshToken" to JsonPrimitive(refreshToken))),
        kind = CodexRequestKind.ACCOUNT_READ,
    )

    /** Reads official ChatGPT/Codex rolling rate limits; no credentials are exposed. */
    fun accountRateLimitsRead(id: CodexRpcId): CodexClientRequest = CodexClientRequest(
        id = id,
        method = "account/rateLimits/read",
        kind = CodexRequestKind.ACCOUNT_RATE_LIMITS_READ,
    )

    /**
     * Lists models available to the currently authenticated official account.
     *
     * `model/list` is paginated in the v2 app-server protocol. Do not send the
     * newer `includeHidden` extension here: v0.144.5 must remain compatible.
     */
    fun modelList(
        id: CodexRpcId,
        cursor: String? = null,
        limit: Int = MODEL_LIST_PAGE_SIZE,
    ): CodexClientRequest {
        val params = linkedMapOf<String, JsonElement>("limit" to JsonPrimitive(limit))
        cursor?.takeIf { it.isNotBlank() }?.let { params["cursor"] = JsonPrimitive(it) }
        return CodexClientRequest(
            id = id,
            method = "model/list",
            params = JsonObject(params),
            kind = CodexRequestKind.MODEL_LIST,
        )
    }

    private const val MODEL_LIST_PAGE_SIZE = 100

    fun startChatGptDeviceCodeLogin(id: CodexRpcId): CodexClientRequest = CodexClientRequest(
        id = id,
        method = "account/login/start",
        params = JsonObject(mapOf("type" to JsonPrimitive("chatgptDeviceCode"))),
        kind = CodexRequestKind.ACCOUNT_LOGIN_DEVICE_CODE,
    )

    fun cancelLogin(id: CodexRpcId, loginId: String): CodexClientRequest = CodexClientRequest(
        id = id,
        method = "account/login/cancel",
        params = JsonObject(mapOf("loginId" to JsonPrimitive(loginId))),
        kind = CodexRequestKind.ACCOUNT_LOGIN_CANCEL,
    )

    fun logout(id: CodexRpcId): CodexClientRequest = CodexClientRequest(
        id = id,
        method = "account/logout",
        kind = CodexRequestKind.ACCOUNT_LOGOUT,
    )

    fun threadStart(
        id: CodexRpcId,
        options: CodexThreadOptions = CodexThreadOptions(),
    ): CodexClientRequest = CodexClientRequest(
        id = id,
        method = "thread/start",
        params = threadParams(options),
        kind = CodexRequestKind.THREAD_START,
    )

    fun threadResume(
        id: CodexRpcId,
        threadId: String,
        options: CodexThreadOptions = CodexThreadOptions(),
    ): CodexClientRequest {
        val fields = threadParams(options).toMutableMap()
        fields["threadId"] = JsonPrimitive(threadId)
        return CodexClientRequest(
            id = id,
            method = "thread/resume",
            params = JsonObject(fields),
            kind = CodexRequestKind.THREAD_RESUME,
        )
    }

    fun turnStart(
        id: CodexRpcId,
        threadId: String,
        input: List<CodexUserInput>,
        options: CodexTurnOptions = CodexTurnOptions(),
    ): CodexClientRequest {
        val fields = options.extra.toMutableMap()
        fields["threadId"] = JsonPrimitive(threadId)
        fields["input"] = JsonArray(input.map(::encodeInput))
        options.clientUserMessageId?.let { fields["clientUserMessageId"] = JsonPrimitive(it) }
        options.cwd?.let { fields["cwd"] = JsonPrimitive(it) }
        options.approvalPolicy?.let { fields["approvalPolicy"] = JsonPrimitive(it) }
        options.approvalsReviewer?.let { fields["approvalsReviewer"] = JsonPrimitive(it) }
        options.sandboxPolicy?.let { fields["sandboxPolicy"] = it }
        options.model?.let { fields["model"] = JsonPrimitive(it) }
        options.effort?.let { fields["effort"] = JsonPrimitive(it) }
        options.serviceTier?.let { fields["serviceTier"] = JsonPrimitive(it) }
        options.summary?.let { fields["summary"] = JsonPrimitive(it) }
        options.personality?.let { fields["personality"] = JsonPrimitive(it) }
        options.outputSchema?.let { fields["outputSchema"] = it }
        if (options.additionalContext.isNotEmpty()) {
            fields["additionalContext"] = JsonObject(
                options.additionalContext.mapValues { (_, context) ->
                    JsonObject(
                        linkedMapOf(
                            "value" to JsonPrimitive(context.value),
                            "kind" to JsonPrimitive(context.kind.wireValue),
                        )
                    )
                }
            )
        }
        return CodexClientRequest(
            id = id,
            method = "turn/start",
            params = JsonObject(fields),
            kind = CodexRequestKind.TURN_START,
        )
    }

    fun turnInterrupt(
        id: CodexRpcId,
        threadId: String,
        turnId: String,
    ): CodexClientRequest = CodexClientRequest(
        id = id,
        method = "turn/interrupt",
        params = JsonObject(
            linkedMapOf(
                "threadId" to JsonPrimitive(threadId),
                "turnId" to JsonPrimitive(turnId),
            ),
        ),
        kind = CodexRequestKind.TURN_INTERRUPT,
    )

    fun approvalResponse(
        id: CodexRpcId,
        decision: CodexApprovalDecision,
    ): CodexClientResponse = CodexClientResponse(
        id = id,
        result = JsonObject(mapOf("decision" to JsonPrimitive(decision.wireValue))),
    )

    /** Allows structured/future approval decisions to be passed through unchanged. */
    fun approvalResponse(id: CodexRpcId, decision: JsonElement): CodexClientResponse =
        CodexClientResponse(
            id = id,
            result = JsonObject(mapOf("decision" to decision)),
        )

    private fun threadParams(options: CodexThreadOptions): JsonObject {
        val fields = options.extra.toMutableMap()
        options.model?.let { fields["model"] = JsonPrimitive(it) }
        options.cwd?.let { fields["cwd"] = JsonPrimitive(it) }
        options.approvalPolicy?.let { fields["approvalPolicy"] = JsonPrimitive(it) }
        options.approvalsReviewer?.let { fields["approvalsReviewer"] = JsonPrimitive(it) }
        options.sandbox?.let { fields["sandbox"] = JsonPrimitive(it) }
        options.baseInstructions?.let { fields["baseInstructions"] = JsonPrimitive(it) }
        options.developerInstructions?.let {
            fields["developerInstructions"] = JsonPrimitive(it)
        }
        options.personality?.let { fields["personality"] = JsonPrimitive(it) }
        options.ephemeral?.let { fields["ephemeral"] = JsonPrimitive(it) }
        return JsonObject(fields)
    }

    private fun encodeInput(input: CodexUserInput): JsonObject = when (input) {
        is CodexUserInput.Text -> JsonObject(
            linkedMapOf(
                "type" to JsonPrimitive("text"),
                "text" to JsonPrimitive(input.text),
            ),
        )

        is CodexUserInput.Image -> JsonObject(
            linkedMapOf(
                "type" to JsonPrimitive("image"),
                "url" to JsonPrimitive(input.url),
            ),
        )

        is CodexUserInput.LocalImage -> JsonObject(
            linkedMapOf(
                "type" to JsonPrimitive("localImage"),
                "path" to JsonPrimitive(input.path),
            ),
        )

        is CodexUserInput.Raw -> input.value
    }
}
