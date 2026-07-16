package com.murong.agent.core.codex

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class CodexAppServerProtocolTest {
    private val json = Json

    @Test
    fun initializeAndInitialized_matchHeaderlessAppServerHandshake() {
        val request = CodexAppServerProtocol.initialize(
            id = CodexRpcId.Number(0),
            clientInfo = CodexClientInfo(
                name = "murong_agent",
                title = "Murong Agent",
                version = "1.10",
            ),
            capabilities = CodexInitializeCapabilities(
                experimentalApi = false,
                optOutNotificationMethods = listOf("rawResponseItem/completed"),
            ),
        )

        val root = encodedObject(request)
        assertFalse(root.containsKey("jsonrpc"))
        assertEquals(0, root["id"]?.jsonPrimitive?.content?.toInt())
        assertEquals("initialize", root["method"]?.jsonPrimitive?.content)
        assertEquals(
            "murong_agent",
            root["params"]?.jsonObject?.get("clientInfo")?.jsonObject?.get("name")
                ?.jsonPrimitive?.content,
        )
        assertFalse(
            root["params"]?.jsonObject?.get("capabilities")?.jsonObject
                ?.get("experimentalApi")?.jsonPrimitive?.boolean ?: true,
        )

        val initialized = encodedObject(CodexAppServerProtocol.initialized())
        assertEquals("initialized", initialized["method"]?.jsonPrimitive?.content)
        assertNull(initialized["id"])
        assertNull(initialized["params"])
    }

    @Test
    fun accountRequests_encodeOfficialDeviceCodeFlow() {
        val read = encodedObject(
            CodexAppServerProtocol.accountRead(CodexRpcId.Number(1), refreshToken = true),
        )
        assertEquals("account/read", read["method"]?.jsonPrimitive?.content)
        assertEquals(true, read["params"]?.jsonObject?.get("refreshToken")?.jsonPrimitive?.boolean)

        val rateLimits = encodedObject(
            CodexAppServerProtocol.accountRateLimitsRead(CodexRpcId.Number(11)),
        )
        assertEquals("account/rateLimits/read", rateLimits["method"]?.jsonPrimitive?.content)
        assertNull(rateLimits["params"])

        val models = encodedObject(CodexAppServerProtocol.modelList(CodexRpcId.Number(12)))
        assertEquals("model/list", models["method"]?.jsonPrimitive?.content)
        assertEquals(
            100,
            models["params"]?.jsonObject?.get("limit")?.jsonPrimitive?.content?.toInt(),
        )
        assertFalse(models["params"]?.jsonObject?.containsKey("includeHidden") ?: true)

        val login = encodedObject(
            CodexAppServerProtocol.startChatGptDeviceCodeLogin(CodexRpcId.Number(2)),
        )
        assertEquals("account/login/start", login["method"]?.jsonPrimitive?.content)
        assertEquals(
            "chatgptDeviceCode",
            login["params"]?.jsonObject?.get("type")?.jsonPrimitive?.content,
        )

        val cancel = encodedObject(
            CodexAppServerProtocol.cancelLogin(CodexRpcId.Number(3), "login-123"),
        )
        assertEquals("account/login/cancel", cancel["method"]?.jsonPrimitive?.content)
        assertEquals(
            "login-123",
            cancel["params"]?.jsonObject?.get("loginId")?.jsonPrimitive?.content,
        )

        val logout = encodedObject(CodexAppServerProtocol.logout(CodexRpcId.Number(4)))
        assertEquals("account/logout", logout["method"]?.jsonPrimitive?.content)
        assertNull(logout["params"])
    }

    @Test
    fun threadAndTurnRequests_encodeStableMinimumAndRawExtensions() {
        val threadStart = encodedObject(
            CodexAppServerProtocol.threadStart(
                id = CodexRpcId.Text("thread-request"),
                options = CodexThreadOptions(
                    cwd = "/workspace",
                    approvalsReviewer = "user",
                    extra = json.parseToJsonElement("""{"futureOption":42}""").jsonObject,
                ),
            ),
        )
        assertEquals("thread/start", threadStart["method"]?.jsonPrimitive?.content)
        assertEquals("thread-request", threadStart["id"]?.jsonPrimitive?.content)
        assertEquals(42, threadStart["params"]?.jsonObject?.get("futureOption")?.jsonPrimitive?.content?.toInt())

        val resume = encodedObject(
            CodexAppServerProtocol.threadResume(
                id = CodexRpcId.Number(6),
                threadId = "thr_123",
            ),
        )
        assertEquals("thread/resume", resume["method"]?.jsonPrimitive?.content)
        assertEquals("thr_123", resume["params"]?.jsonObject?.get("threadId")?.jsonPrimitive?.content)

        val turn = encodedObject(
            CodexAppServerProtocol.turnStart(
                id = CodexRpcId.Number(7),
                threadId = "thr_123",
                input = listOf(
                    CodexUserInput.Text("Run tests"),
                    CodexUserInput.LocalImage("/tmp/screenshot.png"),
                ),
                options = CodexTurnOptions(model = "gpt-5.1-codex", effort = "medium"),
            ),
        )
        val input = turn["params"]?.jsonObject?.get("input")?.jsonArray
        assertEquals("text", input?.get(0)?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals("localImage", input?.get(1)?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals("gpt-5.1-codex", turn["params"]?.jsonObject?.get("model")?.jsonPrimitive?.content)

        val interrupt = encodedObject(
            CodexAppServerProtocol.turnInterrupt(
                id = CodexRpcId.Number(8),
                threadId = "thr_123",
                turnId = "turn_456",
            ),
        )
        assertEquals("turn/interrupt", interrupt["method"]?.jsonPrimitive?.content)
        assertEquals("turn_456", interrupt["params"]?.jsonObject?.get("turnId")?.jsonPrimitive?.content)
    }

    @Test
    fun additionalContext_usesOfficialApplicationAndUntrustedWireKinds() {
        val turn = encodedObject(
            CodexAppServerProtocol.turnStart(
                id = CodexRpcId.Number(9),
                threadId = "thr_123",
                input = listOf(CodexUserInput.Text("hello")),
                options = CodexTurnOptions(
                    additionalContext = linkedMapOf(
                        "murong.rules" to CodexAdditionalContext("always test changes"),
                        "murong.user_selected" to CodexAdditionalContext(
                            value = "a user-selected skill",
                            kind = CodexAdditionalContextKind.UNTRUSTED,
                        ),
                    ),
                ),
            ),
        )

        val context = turn["params"]?.jsonObject?.get("additionalContext")?.jsonObject
        assertEquals(
            "application",
            context?.get("murong.rules")?.jsonObject?.get("kind")?.jsonPrimitive?.content,
        )
        assertEquals(
            "untrusted",
            context?.get("murong.user_selected")?.jsonObject?.get("kind")?.jsonPrimitive?.content,
        )
    }

    @Test
    fun approvalResponse_encodesDecisionAsServerRequestResult() {
        val response = encodedObject(
            CodexAppServerProtocol.approvalResponse(
                CodexRpcId.Text("approval-1"),
                CodexApprovalDecision.ACCEPT_FOR_SESSION,
            ),
        )

        assertEquals("approval-1", response["id"]?.jsonPrimitive?.content)
        assertEquals(
            "acceptForSession",
            response["result"]?.jsonObject?.get("decision")?.jsonPrimitive?.content,
        )
        assertNull(response["method"])
    }

    private fun encodedObject(message: CodexClientMessage): JsonObject =
        json.parseToJsonElement(CodexAppServerCodec.encode(message)).jsonObject
}
