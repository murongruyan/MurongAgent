package com.murong.agent.core.codex

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodexAppServerStateTest {
    @Test
    fun handshakeAndDeviceLoginState_areDerivedFromProtocolMessages() {
        var state = CodexAppServerState()
        val initialize = CodexAppServerProtocol.initialize(
            id = CodexRpcId.Number(1),
            clientInfo = CodexClientInfo("murong_agent", "Murong Agent", "1.10"),
        )
        state = CodexAppServerStateReducer.sent(state, initialize)
        assertEquals(CodexConnectionPhase.INITIALIZING, state.connectionPhase)

        state = CodexAppServerStateReducer.received(
            state,
            CodexAppServerCodec.decode(
                """{"id":1,"result":{"userAgent":"codex-app-server/1","codexHome":"/data/codex","platformFamily":"unix","platformOs":"android","future":true}}""",
            ),
        ).state
        assertEquals(CodexConnectionPhase.INITIALIZE_ACCEPTED, state.connectionPhase)
        assertEquals("/data/codex", state.initializeResult?.codexHome)

        state = CodexAppServerStateReducer.sent(state, CodexAppServerProtocol.initialized())
        assertEquals(CodexConnectionPhase.READY, state.connectionPhase)

        val login = CodexAppServerProtocol.startChatGptDeviceCodeLogin(CodexRpcId.Number(2))
        state = CodexAppServerStateReducer.sent(state, login)
        val update = CodexAppServerStateReducer.received(
            state,
            CodexAppServerCodec.decode(
                """{"id":2,"result":{"type":"chatgptDeviceCode","loginId":"login-2","verificationUrl":"https://auth.openai.com/codex/device","userCode":"CODE-1234"}}""",
            ),
        )
        state = update.state
        assertIs<CodexResponseResult.DeviceCode>(update.responseResult)
        assertEquals(CodexLoginStatus.WAITING_FOR_DEVICE_AUTHORIZATION, state.login.status)
        assertEquals("CODE-1234", state.login.userCode)

        state = received(
            state,
            """{"method":"account/login/completed","params":{"loginId":"login-2","success":true,"error":null}}""",
        )
        state = received(
            state,
            """{"method":"account/updated","params":{"authMode":"chatgpt","planType":"plus"}}""",
        )
        assertEquals(CodexLoginStatus.SUCCEEDED, state.login.status)
        assertEquals("chatgpt", state.authMode)
        assertEquals("plus", state.planType)
    }

    @Test
    fun accountReadAndThreadTurnResponses_areCorrelatedByRequestId() {
        var state = CodexAppServerState(connectionPhase = CodexConnectionPhase.READY)
        val accountRead = CodexAppServerProtocol.accountRead(CodexRpcId.Text("account"))
        state = CodexAppServerStateReducer.sent(state, accountRead)
        state = received(
            state,
            """{"id":"account","result":{"account":{"type":"chatgpt","email":"user@example.com","planType":"plus","future":1},"requiresOpenaiAuth":true}}""",
        )
        assertEquals("user@example.com", state.account?.email)
        assertEquals(true, state.requiresOpenaiAuth)

        val startThread = CodexAppServerProtocol.threadStart(CodexRpcId.Number(10))
        state = CodexAppServerStateReducer.sent(state, startThread)
        state = received(
            state,
            """{"id":10,"result":{"thread":{"id":"thr-1","preview":"","modelProvider":"openai","createdAt":1730910000,"status":"idle","unknownThreadField":"kept"}}}""",
        )
        assertEquals("thr-1", state.currentThread?.id)

        val startTurn = CodexAppServerProtocol.turnStart(
            id = CodexRpcId.Number(11),
            threadId = "thr-1",
            input = listOf(CodexUserInput.Text("hello")),
        )
        state = CodexAppServerStateReducer.sent(state, startTurn)
        state = received(
            state,
            """{"id":11,"result":{"turn":{"id":"turn-1","status":"inProgress","items":[],"error":null}}}""",
        )
        assertEquals("turn-1", state.activeTurn?.id)
        assertTrue(state.pendingRequests.isEmpty())
    }

    @Test
    fun rateLimitSnapshotAndSparseUpdate_preserveWeeklyWindow() {
        var state = CodexAppServerState(connectionPhase = CodexConnectionPhase.READY)
        state = CodexAppServerStateReducer.sent(
            state,
            CodexAppServerProtocol.accountRateLimitsRead(CodexRpcId.Number(12)),
        )
        state = received(
            state,
            """{"id":12,"result":{"rateLimits":{"primary":{"usedPercent":12,"windowDurationMins":300,"resetsAt":100},"secondary":{"usedPercent":30,"windowDurationMins":10080,"resetsAt":200}}}}""",
        )
        state = received(
            state,
            """{"method":"account/rateLimits/updated","params":{"rateLimits":{"primary":{"usedPercent":13}}}}""",
        )
        assertEquals(13, state.rateLimits?.rateLimits?.primary?.usedPercent)
        assertEquals(30, state.rateLimits?.rateLimits?.secondary?.usedPercent)
        assertEquals(200, state.rateLimits?.rateLimits?.secondary?.resetsAt)
    }

    @Test
    fun streamingItemsAndApprovalLifecycle_areReducedWithoutTransport() {
        var state = CodexAppServerState(connectionPhase = CodexConnectionPhase.READY)
        state = received(
            state,
            """{"method":"item/started","params":{"threadId":"thr","turnId":"turn","item":{"id":"msg","type":"agentMessage","text":"","status":"inProgress"}}}""",
        )
        state = received(
            state,
            """{"method":"item/agentMessage/delta","params":{"threadId":"thr","turnId":"turn","itemId":"msg","delta":"Hello"}}""",
        )
        state = received(
            state,
            """{"method":"item/agentMessage/delta","params":{"threadId":"thr","turnId":"turn","itemId":"msg","delta":" world"}}""",
        )
        state = received(
            state,
            """{"method":"item/reasoning/summaryTextDelta","params":{"threadId":"thr","turnId":"turn","itemId":"reason","summaryIndex":0,"delta":"Inspect "}}""",
        )
        state = received(
            state,
            """{"method":"item/reasoning/summaryTextDelta","params":{"threadId":"thr","turnId":"turn","itemId":"reason","summaryIndex":0,"delta":"files"}}""",
        )
        assertEquals("Hello world", state.agentTextByItemId["msg"])
        assertEquals("Inspect files", state.reasoningSummaryByItemId["reason"])

        state = received(
            state,
            """{"id":77,"method":"item/fileChange/requestApproval","params":{"threadId":"thr","turnId":"turn","itemId":"patch","reason":"write"}}""",
        )
        assertEquals(CodexApprovalKind.FILE_CHANGE, state.pendingApprovals[CodexRpcId.Number(77)]?.kind)

        state = received(
            state,
            """{"method":"serverRequest/resolved","params":{"threadId":"thr","requestId":77}}""",
        )
        assertTrue(state.pendingApprovals.isEmpty())

        state = received(
            state,
            """{"method":"item/completed","params":{"threadId":"thr","turnId":"turn","item":{"id":"msg","type":"agentMessage","text":"Hello world","status":"completed"}}}""",
        )
        assertEquals("completed", state.items["msg"]?.status)

        state = received(
            state,
            """{"method":"turn/completed","params":{"turn":{"id":"turn","status":"completed","items":[],"error":null}}}""",
        )
        assertEquals("completed", state.activeTurn?.status)
        assertNull(state.lastTurnError)
    }

    @Test
    fun initializeError_clearsPendingAndMovesStateToFailed() {
        var state = CodexAppServerState()
        val request = CodexAppServerProtocol.initialize(
            CodexRpcId.Number(1),
            CodexClientInfo("murong_agent", "Murong Agent", "1.10"),
        )
        state = CodexAppServerStateReducer.sent(state, request)
        state = received(
            state,
            """{"id":1,"error":{"code":-32600,"message":"Already initialized","newErrorField":true}}""",
        )
        assertEquals(CodexConnectionPhase.FAILED, state.connectionPhase)
        assertEquals("Already initialized", state.lastRpcError?.message)
        assertTrue(state.pendingRequests.isEmpty())
    }

    @Test
    fun cancellingDeviceLogin_clearsTheExpiredCodeAndLoginId() {
        var state = CodexAppServerState(connectionPhase = CodexConnectionPhase.READY)
        val start = CodexAppServerProtocol.startChatGptDeviceCodeLogin(CodexRpcId.Number(1))
        state = CodexAppServerStateReducer.sent(state, start)
        state = received(
            state,
            """{"id":1,"result":{"type":"chatgptDeviceCode","loginId":"login-1","verificationUrl":"https://auth.openai.com/codex/device","userCode":"CODE-1234"}}""",
        )

        val cancel = CodexAppServerProtocol.cancelLogin(CodexRpcId.Number(2), "login-1")
        state = CodexAppServerStateReducer.sent(state, cancel)
        state = received(state, """{"id":2,"result":{}}""")

        assertEquals(CodexLoginStatus.CANCEL_REQUESTED, state.login.status)
        assertNull(state.login.loginId)
        assertNull(state.login.userCode)
        assertNull(state.login.verificationUrl)
    }

    private fun received(state: CodexAppServerState, json: String): CodexAppServerState =
        CodexAppServerStateReducer.received(state, CodexAppServerCodec.decode(json)).state
}
