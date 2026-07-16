package com.murong.agent.core.codex

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class CodexAppServerCodecTest {
    @Test
    fun decodesResponseAndPreservesUnknownFields() {
        val message = CodexAppServerCodec.decode(
            """{"id":1,"result":{"futureField":{"enabled":true}},"futureEnvelope":"kept"}""",
        )

        val response = assertIs<CodexServerMessage.Response>(message)
        assertEquals(CodexRpcId.Number(1), response.id)
        assertEquals(
            "kept",
            response.raw["futureEnvelope"]?.jsonPrimitive?.content,
        )
        assertTrue(response.result != null)
    }

    @Test
    fun decodesRpcErrorIncludingRetryableOverloadData() {
        val message = CodexAppServerCodec.decode(
            """{"id":"req-1","error":{"code":-32001,"message":"Server overloaded; retry later.","data":{"retryAfterMs":500}}}""",
        )

        val response = assertIs<CodexServerMessage.ErrorResponse>(message)
        assertEquals(CodexRpcId.Text("req-1"), response.id)
        assertEquals(-32001, response.error.code)
        assertTrue(response.error.isRetryableOverload)
        assertEquals(
            500,
            response.error.data?.jsonObject?.get("retryAfterMs")?.jsonPrimitive?.content?.toInt(),
        )
    }

    @Test
    fun decodesDeviceCodeUsingCorrelatedLoginRequest() {
        val request = CodexAppServerProtocol.startChatGptDeviceCodeLogin(CodexRpcId.Number(4))
        val response = assertIs<CodexServerMessage.Response>(
            CodexAppServerCodec.decode(
                """{"id":4,"result":{"type":"chatgptDeviceCode","loginId":"login-1","verificationUrl":"https://auth.openai.com/codex/device","userCode":"ABCD-1234","newField":"safe"}}""",
            ),
        )

        val result = assertIs<CodexResponseResult.DeviceCode>(
            CodexAppServerCodec.decodeResult(request, response),
        )
        assertEquals("login-1", result.value.loginId)
        assertEquals("https://auth.openai.com/codex/device", result.value.verificationUrl)
        assertEquals("ABCD-1234", result.value.userCode)
        assertEquals("safe", result.value.raw["newField"]?.jsonPrimitive?.content)
    }

    @Test
    fun decodesAccountAndLoginNotificationsWithPlusPlan() {
        val updated = assertIs<CodexServerMessage.Notification>(
            CodexAppServerCodec.decode(
                """{"method":"account/updated","params":{"authMode":"chatgpt","planType":"plus","future":1}}""",
            ),
        )
        val account = assertIs<CodexNotificationEvent.AccountUpdated>(updated.event)
        assertEquals("chatgpt", account.authMode)
        assertEquals("plus", account.planType)
        assertEquals("1", account.rawParams["future"]?.jsonPrimitive?.content)

        val completed = assertIs<CodexServerMessage.Notification>(
            CodexAppServerCodec.decode(
                """{"method":"account/login/completed","params":{"loginId":"login-1","success":true,"error":null}}""",
            ),
        )
        val login = assertIs<CodexNotificationEvent.LoginCompleted>(completed.event)
        assertEquals(true, login.success)
        assertEquals("login-1", login.loginId)
    }

    @Test
    fun decodesOfficialRollingRateLimitsAndSparseUpdates() {
        val request = CodexAppServerProtocol.accountRateLimitsRead(CodexRpcId.Number(7))
        val response = assertIs<CodexServerMessage.Response>(
            CodexAppServerCodec.decode(
                """{"id":7,"result":{"rateLimits":{"primary":{"usedPercent":20,"windowDurationMins":300,"resetsAt":1730947200},"secondary":{"usedPercent":35,"windowDurationMins":10080,"resetsAt":1731547200},"rateLimitReachedType":null},"rateLimitResetCredits":{"availableCount":2}}}""",
            ),
        )
        val snapshot = assertIs<CodexResponseResult.RateLimits>(
            CodexAppServerCodec.decodeResult(request, response),
        ).value
        assertEquals(20, snapshot.rateLimits?.primary?.usedPercent)
        assertEquals(35, snapshot.rateLimits?.secondary?.usedPercent)
        assertEquals(2, snapshot.resetCreditsAvailableCount)

        val updated = assertIs<CodexServerMessage.Notification>(
            CodexAppServerCodec.decode(
                """{"method":"account/rateLimits/updated","params":{"rateLimits":{"primary":{"usedPercent":21}}}}""",
            ),
        )
        assertEquals(
            21,
            assertIs<CodexNotificationEvent.RateLimitsUpdated>(updated.event)
                .rateLimits.primary?.usedPercent,
        )
    }

    @Test
    fun decodesServerAdvertisedModelCapabilitiesWithoutInventingChoices() {
        val request = CodexAppServerProtocol.modelList(CodexRpcId.Number(8))
        val response = assertIs<CodexServerMessage.Response>(
            CodexAppServerCodec.decode(
                """{"id":8,"result":{"data":[{"id":"gpt-5-codex","displayName":"GPT-5 Codex","description":"Coding model","isDefault":true,"supportedReasoningEfforts":[{"reasoningEffort":"low","description":"Low"},{"reasoningEffort":"medium","description":"Medium"},{"reasoningEffort":"high","description":"High"}],"defaultReasoningEffort":"medium","additionalSpeedTiers":[{"id":"standard","displayName":"标准"},{"id":"fast","displayName":"快速","description":"更快响应"}]}],"nextCursor":"opaque-next"}}""",
            ),
        )
        val catalog = assertIs<CodexResponseResult.ModelList>(
            CodexAppServerCodec.decodeResult(request, response),
        ).value
        val model = catalog.models.single()
        assertEquals("gpt-5-codex", model.id)
        assertEquals(listOf("low", "medium", "high"), model.supportedReasoningEfforts)
        assertEquals("fast", model.speedTiers.last().id)
        assertEquals("opaque-next", catalog.nextCursor)
    }

    @Test
    fun decodesStreamingAndItemLifecycleNotifications() {
        val agent = notification(
            """{"method":"item/agentMessage/delta","params":{"threadId":"thr","turnId":"turn","itemId":"msg","delta":"hello "}}""",
        )
        assertEquals("hello ", assertIs<CodexNotificationEvent.AgentMessageDelta>(agent.event).delta)

        val summary = notification(
            """{"method":"item/reasoning/summaryTextDelta","params":{"threadId":"thr","turnId":"turn","itemId":"reason","summaryIndex":2,"delta":"checking"}}""",
        )
        val summaryDelta = assertIs<CodexNotificationEvent.ReasoningDelta>(summary.event)
        assertEquals(CodexReasoningDeltaKind.SUMMARY, summaryDelta.kind)
        assertEquals(2, summaryDelta.index)

        val content = notification(
            """{"method":"item/reasoning/textDelta","params":{"threadId":"thr","turnId":"turn","itemId":"reason","contentIndex":0,"delta":"raw"}}""",
        )
        assertEquals(
            CodexReasoningDeltaKind.CONTENT,
            assertIs<CodexNotificationEvent.ReasoningDelta>(content.event).kind,
        )

        val started = notification(
            """{"method":"item/started","params":{"threadId":"thr","turnId":"turn","item":{"id":"cmd","type":"commandExecution","status":"inProgress","command":"pwd","cwd":"/workspace","futureItemField":9}}}""",
        )
        val startedEvent = assertIs<CodexNotificationEvent.ItemStarted>(started.event)
        assertEquals("commandExecution", startedEvent.item.type)
        assertEquals("pwd", startedEvent.item.raw["command"]?.jsonPrimitive?.content)
        assertEquals("/workspace", startedEvent.item.raw["cwd"]?.jsonPrimitive?.content)
        assertEquals("9", startedEvent.item.raw["futureItemField"]?.jsonPrimitive?.content)

        val completed = notification(
            """{"method":"item/completed","params":{"threadId":"thr","turnId":"turn","item":{"id":"cmd","type":"commandExecution","status":"completed","aggregatedOutput":"/workspace","exitCode":0,"durationMs":12}}}""",
        )
        val completedItem = assertIs<CodexNotificationEvent.ItemCompleted>(completed.event).item
        assertEquals("completed", completedItem.status)
        assertEquals("/workspace", completedItem.raw["aggregatedOutput"]?.jsonPrimitive?.content)
        assertEquals("0", completedItem.raw["exitCode"]?.jsonPrimitive?.content)
    }

    @Test
    fun decodesTurnCompletedAndMidTurnErrorNotification() {
        val completed = notification(
            """{"method":"turn/completed","params":{"turn":{"id":"turn-1","status":"failed","items":[],"error":{"message":"quota","codexErrorInfo":"UsageLimitExceeded","additionalDetails":{"reset":1}}}}}""",
        )
        val turn = assertIs<CodexNotificationEvent.TurnCompleted>(completed.event).turn
        assertEquals("failed", turn.status)
        assertEquals("quota", turn.error?.message)

        val error = notification(
            """{"method":"error","params":{"error":{"message":"stream disconnected","codexErrorInfo":{"responseStreamDisconnected":{"httpStatusCode":502}}}}}""",
        )
        assertEquals(
            "stream disconnected",
            assertIs<CodexNotificationEvent.Error>(error.event).error.message,
        )
    }

    @Test
    fun decodesApprovalServerRequestsAndUnknownRequest() {
        val command = assertIs<CodexServerMessage.ServerRequest>(
            CodexAppServerCodec.decode(
                """{"id":60,"method":"item/commandExecution/requestApproval","params":{"threadId":"thr","turnId":"turn","itemId":"cmd","environmentId":"local","reason":"needs network","command":"curl example.com","cwd":"/workspace","commandActions":[],"availableDecisions":["accept","decline"],"futureApprovalField":true}}""",
            ),
        )
        val commandApproval = assertIs<CodexServerRequestPayload.Approval>(command.request)
        assertEquals(CodexApprovalKind.COMMAND_EXECUTION, commandApproval.kind)
        assertEquals("local", commandApproval.environmentId)
        assertEquals("/workspace", commandApproval.cwd)
        assertTrue(commandApproval.rawParams.containsKey("futureApprovalField"))

        val file = assertIs<CodexServerMessage.ServerRequest>(
            CodexAppServerCodec.decode(
                """{"id":"file-1","method":"item/fileChange/requestApproval","params":{"threadId":"thr","turnId":"turn","itemId":"patch","reason":"write file","grantRoot":"/workspace"}}""",
            ),
        )
        val fileApproval = assertIs<CodexServerRequestPayload.Approval>(file.request)
        assertEquals(CodexApprovalKind.FILE_CHANGE, fileApproval.kind)
        assertEquals("/workspace", fileApproval.grantRoot)

        val permissions = assertIs<CodexServerMessage.ServerRequest>(
            CodexAppServerCodec.decode(
                """{"id":61,"method":"item/permissions/requestApproval","params":{"threadId":"thr","turnId":"turn","itemId":"perm","permissions":{"fileSystem":{"write":["/workspace"]}}}}""",
            ),
        )
        assertEquals(
            CodexApprovalKind.PERMISSIONS,
            assertIs<CodexServerRequestPayload.Approval>(permissions.request).kind,
        )

        val unknown = assertIs<CodexServerMessage.ServerRequest>(
            CodexAppServerCodec.decode(
                """{"id":99,"method":"future/action","params":{"newShape":true}}""",
            ),
        )
        assertIs<CodexServerRequestPayload.Unknown>(unknown.request)
    }

    @Test
    fun unknownNotificationRemainsObservable() {
        val message = notification(
            """{"method":"future/event","params":{"payload":{"answer":42}}}""",
        )
        val event = assertIs<CodexNotificationEvent.Unknown>(message.event)
        assertEquals("future/event", event.method)
        assertTrue(event.rawParams.containsKey("payload"))
    }

    private fun notification(json: String): CodexServerMessage.Notification =
        assertIs(CodexAppServerCodec.decode(json))
}
