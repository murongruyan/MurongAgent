package com.murong.agent.core.codex

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/** Protocol-only login test: no real account, token, browser session, or auth file is used. */
class CodexAppServerLoginLoopTest {
    @Test
    fun deviceLoginAccountReadAndRestart_keepVerifiedSessionState() = runBlocking {
        val factory = FakeLoginTransportFactory()
        val client = CodexAppServerClient(
            transportFactory = factory,
            requestTimeoutMillis = 2_000L,
            restartDelaysMillis = emptyList(),
            dispatcher = Dispatchers.Default,
        )

        client.start()
        val deviceCode = client.startDeviceCodeLogin()
        assertEquals("fake-login", deviceCode.loginId)

        withTimeout(2_000L) {
            while (client.state.value.login.status != CodexLoginStatus.SUCCEEDED) delay(10L)
        }
        val account = client.accountRead()
        assertEquals("fake@example.com", account.account?.email)
        val rates = client.accountRateLimitsRead()
        assertEquals(12, rates.rateLimits?.primary?.usedPercent)

        client.stop()
        client.start()
        val restored = client.accountRead()
        assertEquals("fake@example.com", restored.account?.email)
        assertEquals(CodexLoginStatus.SUCCEEDED, client.state.value.login.status)
        client.close()
    }

    private class FakeLoginTransportFactory : CodexAppServerTransportFactory {
        override fun create(): CodexAppServerTransport = FakeLoginTransport()
    }

    private class FakeLoginTransport : CodexAppServerTransport {
        private val messagesFlow = MutableSharedFlow<JsonElement>(replay = 1, extraBufferCapacity = 8)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        override val messages: Flow<JsonElement> = messagesFlow.asSharedFlow()
        override val stderrLines: Flow<String> = emptyFlow()
        override val protocolViolations: Flow<CodexTransportProtocolViolation> = emptyFlow()
        override val ioFailures: Flow<CodexTransportIoFailure> = emptyFlow()
        override val exits: Flow<CodexTransportExit> = emptyFlow()

        override fun start() = Unit

        override suspend fun request(message: JsonObject): JsonElement {
            val id = message.getValue("id").toString()
            return when (message.getValue("method").jsonPrimitive.content) {
                "initialize" -> response(id, """{"userAgent":"fake","codexHome":"/fake","platformFamily":"unix","platformOs":"test"}""")
                "account/login/start" -> {
                    scope.launch {
                        delay(20L)
                        messagesFlow.emit(
                            Json.parseToJsonElement(
                                """{"method":"account/login/completed","params":{"loginId":"fake-login","success":true}}""",
                            ),
                        )
                        messagesFlow.emit(
                            Json.parseToJsonElement(
                                """{"method":"account/updated","params":{"authMode":"chatgpt","planType":"plus"}}""",
                            ),
                        )
                    }
                    response(id, """{"type":"chatgptDeviceCode","loginId":"fake-login","verificationUrl":"https://example.invalid/device","userCode":"FAKE-1234"}""")
                }
                "account/read" -> response(id, """{"account":{"type":"chatgpt","email":"fake@example.com","planType":"plus"},"requiresOpenaiAuth":true}""")
                "account/rateLimits/read" -> response(id, """{"rateLimits":{"primary":{"usedPercent":12,"resetsAt":123}}}""")
                "account/login/cancel", "account/logout" -> response(id, "{}")
                else -> error("Fake transport received unsupported method: ${message.getValue("method")}")
            }
        }

        override suspend fun send(message: JsonObject) = Unit

        override suspend fun close() {
            scope.cancel()
        }

        override fun kill() {
            scope.cancel()
        }

        private fun response(id: String, result: String): JsonElement =
            Json.parseToJsonElement("""{"id":$id,"result":$result}""")
    }

}
