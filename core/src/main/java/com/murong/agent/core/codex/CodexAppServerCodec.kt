package com.murong.agent.core.codex

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Encodes one app-server wire message and decodes one complete wire message.
 * Framing (JSONL, WebSocket text frame, etc.) intentionally lives elsewhere.
 */
object CodexAppServerCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
    }

    fun encode(message: CodexClientMessage): String = encodeToJson(message).toString()

    fun encodeToJson(message: CodexClientMessage): JsonObject = when (message) {
        is CodexClientRequest -> buildWireMessage(
            id = message.id,
            method = message.method,
            params = message.params,
        )

        is CodexClientNotification -> buildWireMessage(
            method = message.method,
            params = message.params,
        )

        is CodexClientResponse -> JsonObject(
            linkedMapOf(
                "id" to message.id.toJson(),
                "result" to message.result,
            ),
        )
    }

    fun decode(message: String): CodexServerMessage {
        val element = try {
            json.parseToJsonElement(message)
        } catch (error: SerializationException) {
            throw CodexProtocolException("Invalid Codex app-server JSON", error)
        }
        val root = element as? JsonObject
            ?: throw CodexProtocolException("Codex app-server message must be a JSON object")
        return decode(root)
    }

    fun decode(root: JsonObject): CodexServerMessage {
        val method = root.string("method")
        val id = root["id"].toRpcIdOrNull()

        if (method != null) {
            val params = root.objectValue("params") ?: JsonObject(emptyMap())
            return if (id == null) {
                CodexServerMessage.Notification(
                    method = method,
                    params = params,
                    event = decodeNotification(method, params),
                    raw = root,
                )
            } else {
                CodexServerMessage.ServerRequest(
                    id = id,
                    method = method,
                    params = params,
                    request = decodeServerRequest(method, params),
                    raw = root,
                )
            }
        }

        root.objectValue("error")?.let { error ->
            return CodexServerMessage.ErrorResponse(
                id = id,
                error = CodexRpcError(
                    code = error.int("code") ?: 0,
                    message = error.string("message").orEmpty(),
                    data = error["data"],
                    raw = error,
                ),
                raw = root,
            )
        }

        if (id != null && root.containsKey("result")) {
            return CodexServerMessage.Response(
                id = id,
                result = root["result"],
                raw = root,
            )
        }

        throw CodexProtocolException(
            "Unrecognized Codex app-server message: expected method, result, or error",
        )
    }

    /** Interprets a response using the request that supplied its otherwise context-free id. */
    fun decodeResult(
        request: CodexClientRequest,
        response: CodexServerMessage.Response,
    ): CodexResponseResult {
        require(request.id == response.id) { "Response id does not match request id" }
        val result = response.result as? JsonObject ?: JsonObject(emptyMap())
        return when (request.kind) {
            CodexRequestKind.INITIALIZE -> CodexResponseResult.Initialize(
                value = CodexInitializeResult(
                    userAgent = result.string("userAgent"),
                    codexHome = result.string("codexHome"),
                    platformFamily = result.string("platformFamily"),
                    platformOs = result.string("platformOs"),
                    raw = result,
                ),
                raw = result,
            )

            CodexRequestKind.ACCOUNT_READ -> CodexResponseResult.AccountRead(
                value = CodexAccountReadResult(
                    account = result.objectValue("account")?.let(::decodeAccount),
                    requiresOpenaiAuth = result.boolean("requiresOpenaiAuth"),
                    raw = result,
                ),
                raw = result,
            )

            CodexRequestKind.ACCOUNT_RATE_LIMITS_READ -> CodexResponseResult.RateLimits(
                value = CodexRateLimitsSnapshot(
                    rateLimits = result.objectValue("rateLimits")?.let(::decodeRateLimits),
                    resetCreditsAvailableCount = result.objectValue("rateLimitResetCredits")
                        ?.int("availableCount"),
                    raw = result,
                ),
                raw = result,
            )

            CodexRequestKind.MODEL_LIST -> CodexResponseResult.ModelList(
                value = CodexModelCatalog(
                    // v2 app-server returns the page in `data`; retain `models`
                    // as a backwards-compatible fallback for older snapshots.
                    models = (result.arrayValue("data") ?: result.arrayValue("models"))
                        ?.mapNotNull { (it as? JsonObject)?.let(::decodeModelDescriptor) }
                        .orEmpty(),
                    nextCursor = result.string("nextCursor"),
                    raw = result,
                ),
                raw = result,
            )

            CodexRequestKind.ACCOUNT_LOGIN_DEVICE_CODE -> CodexResponseResult.DeviceCode(
                value = CodexDeviceCode(
                    type = result.string("type"),
                    loginId = result.string("loginId"),
                    verificationUrl = result.string("verificationUrl"),
                    userCode = result.string("userCode"),
                    raw = result,
                ),
                raw = result,
            )

            CodexRequestKind.THREAD_START,
            CodexRequestKind.THREAD_RESUME,
            -> CodexResponseResult.Thread(
                value = decodeThread(result.objectValue("thread") ?: JsonObject(emptyMap())),
                raw = result,
            )

            CodexRequestKind.TURN_START -> CodexResponseResult.Turn(
                value = decodeTurn(result.objectValue("turn") ?: JsonObject(emptyMap())),
                raw = result,
            )

            CodexRequestKind.ACCOUNT_LOGIN_CANCEL,
            CodexRequestKind.ACCOUNT_LOGOUT,
            CodexRequestKind.TURN_INTERRUPT,
            -> CodexResponseResult.Empty(result)

            CodexRequestKind.UNKNOWN -> CodexResponseResult.Unknown(result)
        }
    }

    private fun decodeNotification(
        method: String,
        params: JsonObject,
    ): CodexNotificationEvent = when (method) {
        "account/updated" -> CodexNotificationEvent.AccountUpdated(
            authMode = params.string("authMode"),
            planType = params.string("planType"),
            rawParams = params,
        )

        "account/login/completed" -> CodexNotificationEvent.LoginCompleted(
            loginId = params.string("loginId"),
            success = params.boolean("success"),
            error = params.string("error"),
            rawParams = params,
        )

        "account/rateLimits/updated" -> CodexNotificationEvent.RateLimitsUpdated(
            rateLimits = decodeRateLimits(
                params.objectValue("rateLimits") ?: JsonObject(emptyMap()),
            ),
            rawParams = params,
        )

        "thread/started" -> CodexNotificationEvent.ThreadStarted(
            thread = decodeThread(params.objectValue("thread") ?: JsonObject(emptyMap())),
            rawParams = params,
        )

        "turn/started" -> CodexNotificationEvent.TurnStarted(
            turn = decodeTurn(params.objectValue("turn") ?: JsonObject(emptyMap())),
            rawParams = params,
        )

        "turn/completed" -> CodexNotificationEvent.TurnCompleted(
            turn = decodeTurn(params.objectValue("turn") ?: JsonObject(emptyMap())),
            rawParams = params,
        )

        "item/agentMessage/delta" -> CodexNotificationEvent.AgentMessageDelta(
            threadId = params.string("threadId"),
            turnId = params.string("turnId"),
            itemId = params.string("itemId"),
            delta = params.string("delta").orEmpty(),
            rawParams = params,
        )

        "item/reasoning/summaryTextDelta" -> CodexNotificationEvent.ReasoningDelta(
            kind = CodexReasoningDeltaKind.SUMMARY,
            threadId = params.string("threadId"),
            turnId = params.string("turnId"),
            itemId = params.string("itemId"),
            delta = params.string("delta").orEmpty(),
            index = params.int("summaryIndex"),
            rawParams = params,
        )

        "item/reasoning/textDelta" -> CodexNotificationEvent.ReasoningDelta(
            kind = CodexReasoningDeltaKind.CONTENT,
            threadId = params.string("threadId"),
            turnId = params.string("turnId"),
            itemId = params.string("itemId"),
            delta = params.string("delta").orEmpty(),
            index = params.int("contentIndex"),
            rawParams = params,
        )

        "item/started" -> CodexNotificationEvent.ItemStarted(
            threadId = params.string("threadId"),
            turnId = params.string("turnId"),
            item = decodeItem(params.objectValue("item") ?: JsonObject(emptyMap())),
            rawParams = params,
        )

        "item/completed" -> CodexNotificationEvent.ItemCompleted(
            threadId = params.string("threadId"),
            turnId = params.string("turnId"),
            item = decodeItem(params.objectValue("item") ?: JsonObject(emptyMap())),
            rawParams = params,
        )

        "error" -> CodexNotificationEvent.Error(
            error = decodeTurnError(params.objectValue("error") ?: params),
            rawParams = params,
        )

        "serverRequest/resolved" -> CodexNotificationEvent.ServerRequestResolved(
            threadId = params.string("threadId"),
            requestId = params["requestId"].toRpcIdOrNull(),
            rawParams = params,
        )

        else -> CodexNotificationEvent.Unknown(method = method, rawParams = params)
    }

    private fun decodeServerRequest(
        method: String,
        params: JsonObject,
    ): CodexServerRequestPayload {
        val kind = when (method) {
            "item/commandExecution/requestApproval" -> CodexApprovalKind.COMMAND_EXECUTION
            "item/fileChange/requestApproval" -> CodexApprovalKind.FILE_CHANGE
            "item/permissions/requestApproval" -> CodexApprovalKind.PERMISSIONS
            else -> if (method.endsWith("/requestApproval")) {
                CodexApprovalKind.UNKNOWN
            } else {
                return CodexServerRequestPayload.Unknown(method = method, rawParams = params)
            }
        }
        return CodexServerRequestPayload.Approval(
            kind = kind,
            threadId = params.string("threadId"),
            turnId = params.string("turnId"),
            itemId = params.string("itemId"),
            reason = params.string("reason"),
            environmentId = params.string("environmentId"),
            command = params["command"],
            cwd = params.string("cwd"),
            commandActions = params.arrayValue("commandActions"),
            grantRoot = params.string("grantRoot"),
            availableDecisions = params.arrayValue("availableDecisions"),
            permissions = params.objectValue("permissions"),
            method = method,
            rawParams = params,
        )
    }

    private fun decodeAccount(raw: JsonObject): CodexAccount = CodexAccount(
        type = raw.string("type"),
        email = raw.string("email"),
        planType = raw.string("planType"),
        raw = raw,
    )

    private fun decodeRateLimits(raw: JsonObject): CodexRateLimits = CodexRateLimits(
        primary = raw.objectValue("primary")?.let(::decodeRateLimitWindow),
        secondary = raw.objectValue("secondary")?.let(::decodeRateLimitWindow),
        rateLimitReachedType = raw.string("rateLimitReachedType"),
        raw = raw,
    )

    private fun decodeRateLimitWindow(raw: JsonObject): CodexRateLimitWindow =
        CodexRateLimitWindow(
            usedPercent = raw.int("usedPercent"),
            windowDurationMins = raw.int("windowDurationMins"),
            resetsAt = raw.long("resetsAt"),
            raw = raw,
        )

    private fun decodeModelDescriptor(raw: JsonObject): CodexModelDescriptor {
        val speedTiers = raw.arrayValue("additionalSpeedTiers")
            ?.mapNotNull { it.toSpeedTierOrNull() }
            .orEmpty()
            .ifEmpty {
                raw.arrayValue("serviceTiers")
                    ?.mapNotNull { it.toSpeedTierOrNull() }
                    .orEmpty()
            }
        return CodexModelDescriptor(
            id = raw.string("id") ?: raw.string("model").orEmpty(),
            displayName = raw.string("displayName") ?: raw.string("name"),
            description = raw.string("description"),
            isDefault = raw.boolean("isDefault"),
            hidden = raw.boolean("hidden"),
            supportedReasoningEfforts = raw.arrayValue("supportedReasoningEfforts")
                ?.mapNotNull { it.toReasoningEffortOrNull() }
                .orEmpty(),
            defaultReasoningEffort = raw.string("defaultReasoningEffort"),
            speedTiers = speedTiers,
            defaultSpeedTier = raw.string("defaultServiceTier") ?: raw.string("defaultSpeedTier"),
            raw = raw,
        )
    }

    private fun JsonElement.toReasoningEffortOrNull(): String? = when (this) {
        is JsonPrimitive -> contentOrNull?.takeIf { it.isNotBlank() }
        is JsonObject -> (string("reasoningEffort") ?: string("id") ?: string("name"))
            ?.takeIf { it.isNotBlank() }
        else -> null
    }

    private fun JsonElement.toSpeedTierOrNull(): CodexSpeedTier? = when (this) {
        is JsonPrimitive -> contentOrNull?.takeIf { it.isNotBlank() }?.let { id ->
            CodexSpeedTier(id = id, displayName = null, description = null, raw = JsonObject(emptyMap()))
        }

        is JsonObject -> {
            val id = string("id") ?: string("tier") ?: string("serviceTier")
                ?: string("speed") ?: string("name") ?: return null
            CodexSpeedTier(
                id = id,
                displayName = string("displayName") ?: string("name"),
                description = string("description"),
                raw = this,
            )
        }

        else -> null
    }

    private fun decodeThread(raw: JsonObject): CodexThread = CodexThread(
        id = raw.string("id"),
        preview = raw.string("preview"),
        modelProvider = raw.string("modelProvider"),
        createdAt = raw.long("createdAt"),
        status = raw.string("status"),
        turns = raw.arrayValue("turns")
            ?.mapNotNull { (it as? JsonObject)?.let(::decodeTurn) }
            .orEmpty(),
        raw = raw,
    )

    private fun decodeTurn(raw: JsonObject): CodexTurn = CodexTurn(
        id = raw.string("id"),
        status = raw.string("status"),
        items = raw.arrayValue("items")
            ?.mapNotNull { (it as? JsonObject)?.let(::decodeItem) }
            .orEmpty(),
        error = raw.objectValue("error")?.let(::decodeTurnError),
        raw = raw,
    )

    private fun decodeItem(raw: JsonObject): CodexThreadItem = CodexThreadItem(
        id = raw.string("id"),
        type = raw.string("type"),
        status = raw.string("status"),
        text = raw.string("text"),
        raw = raw,
    )

    private fun decodeTurnError(raw: JsonObject): CodexTurnError = CodexTurnError(
        message = raw.string("message"),
        codexErrorInfo = raw["codexErrorInfo"],
        additionalDetails = raw["additionalDetails"],
        raw = raw,
    )

    private fun buildWireMessage(
        id: CodexRpcId? = null,
        method: String,
        params: JsonObject?,
    ): JsonObject {
        val fields = linkedMapOf<String, JsonElement>()
        id?.let { fields["id"] = it.toJson() }
        fields["method"] = JsonPrimitive(method)
        params?.let { fields["params"] = it }
        return JsonObject(fields)
    }

    private fun CodexRpcId.toJson(): JsonPrimitive = when (this) {
        is CodexRpcId.Number -> JsonPrimitive(value)
        is CodexRpcId.Text -> JsonPrimitive(value)
    }

    internal fun JsonElement?.toRpcIdOrNull(): CodexRpcId? {
        val primitive = this as? JsonPrimitive ?: return null
        if (primitive === JsonNull) return null
        return if (primitive.isString) {
            CodexRpcId.Text(primitive.content)
        } else {
            primitive.longOrNull?.let { CodexRpcId.Number(it) }
                ?: throw CodexProtocolException("JSON-RPC id must be an integer or string")
        }
    }

    private fun JsonObject.string(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.boolean(name: String): Boolean? =
        (this[name] as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.int(name: String): Int? =
        (this[name] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.long(name: String): Long? =
        (this[name] as? JsonPrimitive)?.longOrNull

    private fun JsonObject.objectValue(name: String): JsonObject? = this[name] as? JsonObject
    private fun JsonObject.arrayValue(name: String): JsonArray? = this[name] as? JsonArray
}
