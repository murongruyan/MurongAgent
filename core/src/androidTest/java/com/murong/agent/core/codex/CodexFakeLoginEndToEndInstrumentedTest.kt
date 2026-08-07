package com.murong.agent.core.codex

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device-level login regression using a fake app-server backend. The test exercises the
 * production account vault and Android Keystore without creating a real OpenAI credential.
 */
@RunWith(AndroidJUnit4::class)
class CodexFakeLoginEndToEndInstrumentedTest {
    @Test
    fun fakeLogin_addCancelSwitchRestartAndLogout_preserveAccountIsolation() = runBlocking {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(
            targetContext.cacheDir,
            "codex-fake-login-e2e-${System.nanoTime()}",
        ).apply { mkdirs() }
        val isolatedContext = IsolatedFilesContext(targetContext, root)
        val firstBackend = FakeLoginBackend("first@example.invalid", "plus", usedPercent = 95)
        val secondBackend = FakeLoginBackend(
            "second@example.invalid",
            "pro",
            usedPercent = 20,
            autoComplete = false,
        )
        val firstClient = fakeClient(firstBackend)
        val secondClient = fakeClient(secondBackend)

        try {
            val manager = AndroidCodexAccountManager(isolatedContext)
            val firstId = manager.activeAccountId()
            assertFalse(manager.state.value.accounts.single().loggedIn)

            // Exercise the legacy auth.json path with an invalid placeholder token. It is
            // encrypted when this account becomes inactive and never leaves the test folder.
            manager.activeAuthFile().apply {
                parentFile?.mkdirs()
                writeText(FAKE_AUTH_JSON)
            }
            completeFakeLogin(firstClient, manager, firstBackend, expectedDurable = true)
            assertAccount(manager, firstId, "first@example.invalid", loggedIn = true, lowQuota = true)

            // This reproduces "Add account -> do not log in -> switch back". The first account
            // must be restored instead of being replaced by the empty new account.
            val second = manager.createAccount("备用账号")
            manager.activateAccount(second.id)
            secondClient.start()
            val cancelledLogin = secondClient.startDeviceCodeLogin()
            secondClient.cancelLogin(requireNotNull(cancelledLogin.loginId))
            secondClient.stop()
            assertFalse(secondBackend.loggedIn)
            assertFalse(manager.state.value.accounts.first { it.id == second.id }.loggedIn)
            assertFalse(File(root, "codex-home/auth.json").exists())
            val accountDocument = File(root, "codex-accounts-v1.json").readText()
            assertFalse(accountDocument.contains("fake-instrumentation-access-token"))

            manager.activateAccount(firstId)
            assertAccount(manager, firstId, "first@example.invalid", loggedIn = true, lowQuota = true)
            assertTrue(File(root, "codex-home/auth.json").isFile)
            assertFalse(manager.state.value.accounts.first { it.id == second.id }.loggedIn)

            // An account/read email is only a display snapshot. Without credential material the
            // slot must remain non-restorable instead of pretending that switching is safe.
            manager.activateAccount(second.id)
            secondBackend.autoComplete = true
            completeFakeLogin(secondClient, manager, secondBackend, expectedDurable = false)
            assertFalse(manager.activeAuthFile().exists())
            assertAccount(manager, second.id, "second@example.invalid", loggedIn = false, lowQuota = false)

            // Simulate the official file credential store completing its atomic auth.json write,
            // then capture it through the production Android Keystore path.
            manager.activeAuthFile().writeText(FAKE_AUTH_JSON)
            assertTrue(
                manager.captureRuntime(
                    secondClient.accountRead().account,
                    secondClient.accountRateLimitsRead(),
                ),
            )
            assertAccount(manager, second.id, "second@example.invalid", loggedIn = true, lowQuota = false)

            manager.activateAccount(firstId)
            assertEquals(second.id, manager.bestAccount())

            val reloaded = AndroidCodexAccountManager(isolatedContext)
            assertEquals(firstId, reloaded.activeAccountId())
            assertEquals(2, reloaded.state.value.accounts.size)
            assertAccount(reloaded, firstId, "first@example.invalid", loggedIn = true, lowQuota = true)
            assertAccount(reloaded, second.id, "second@example.invalid", loggedIn = true, lowQuota = false)
            assertEquals(second.id, reloaded.bestAccount())

            val transfer = reloaded.exportAccountPool()
            assertEquals(2, transfer.accounts.size)
            assertTrue(transfer.accounts.all { !it.authJson.isNullOrBlank() })
            val replicaRoot = File(root, "replica").apply { mkdirs() }
            val replica = AndroidCodexAccountManager(IsolatedFilesContext(targetContext, replicaRoot))
            assertEquals(2, replica.importAccountPool(transfer, replaceExisting = true))
            assertEquals(transfer.activeAccountId, replica.activeAccountId())
            assertEquals(2, replica.state.value.accounts.size)
            assertTrue(replica.state.value.accounts.all { it.loggedIn })
            val replicaDocument = File(replicaRoot, "codex-accounts-v1.json").readText()
            assertFalse(replicaDocument.contains("fake-instrumentation-access-token"))
            assertFalse(replicaDocument.contains("fake-instrumentation-refresh-token"))

            // Explicit logout clears only the selected account. It must not erase the other
            // account or force the user through device login again.
            reloaded.activateAccount(second.id)
            reloaded.clearActiveLogin()
            assertFalse(reloaded.state.value.accounts.first { it.id == second.id }.loggedIn)
            assertTrue(reloaded.state.value.accounts.first { it.id == firstId }.loggedIn)
            reloaded.activateAccount(firstId)
            assertTrue(reloaded.activeAuthFile().isFile)
        } finally {
            firstClient.close()
            secondClient.close()
            root.deleteRecursively()
        }
    }

    private suspend fun completeFakeLogin(
        client: CodexAppServerClient,
        manager: AndroidCodexAccountManager,
        backend: FakeLoginBackend,
        expectedDurable: Boolean,
    ) {
        client.start()
        assertEquals(null, client.accountRead().account)
        val login = client.startDeviceCodeLogin()
        assertEquals(backend.loginId, login.loginId)
        withTimeout(2_000L) {
            while (client.state.value.login.status != CodexLoginStatus.SUCCEEDED) delay(10L)
        }

        val account = client.accountRead().account
        assertNotNull(account)
        val rates = client.accountRateLimitsRead()
        assertEquals(expectedDurable, manager.captureRuntime(account, rates))

        client.stop()
        client.start()
        assertEquals(backend.email, client.accountRead().account?.email)
    }

    private fun fakeClient(backend: FakeLoginBackend): CodexAppServerClient = CodexAppServerClient(
        transportFactory = FakeLoginTransportFactory(backend),
        requestTimeoutMillis = 2_000L,
        restartDelaysMillis = emptyList(),
        dispatcher = Dispatchers.Default,
    )

    private fun assertAccount(
        manager: AndroidCodexAccountManager,
        id: String,
        email: String,
        loggedIn: Boolean,
        lowQuota: Boolean,
    ) {
        val account = manager.state.value.accounts.first { it.id == id }
        assertEquals(email, account.email)
        assertEquals(loggedIn, account.loggedIn)
        assertEquals(lowQuota, account.lowQuota)
    }

    private class IsolatedFilesContext(base: Context, private val root: File) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = root
    }

    private data class FakeLoginBackend(
        val email: String,
        val planType: String,
        val usedPercent: Int,
        val loginId: String = "fake-login-${email.substringBefore('@')}",
        var autoComplete: Boolean = true,
        var loggedIn: Boolean = false,
    )

    private class FakeLoginTransportFactory(
        private val backend: FakeLoginBackend,
    ) : CodexAppServerTransportFactory {
        override fun create(): CodexAppServerTransport = FakeLoginTransport(backend)
    }

    private class FakeLoginTransport(
        private val backend: FakeLoginBackend,
    ) : CodexAppServerTransport {
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
                "initialize" -> response(
                    id,
                    """{"userAgent":"fake-e2e","codexHome":"/fake","platformFamily":"unix","platformOs":"android-test"}""",
                )
                "account/login/start" -> {
                    if (backend.autoComplete) {
                        scope.launch {
                            delay(25L)
                            backend.loggedIn = true
                            messagesFlow.emit(
                                Json.parseToJsonElement(
                                    """{"method":"account/login/completed","params":{"loginId":"${backend.loginId}","success":true}}""",
                                ),
                            )
                            messagesFlow.emit(
                                Json.parseToJsonElement(
                                    """{"method":"account/updated","params":{"authMode":"chatgpt","planType":"${backend.planType}"}}""",
                                ),
                            )
                        }
                    }
                    response(
                        id,
                        """{"type":"chatgptDeviceCode","loginId":"${backend.loginId}","verificationUrl":"https://example.invalid/device","userCode":"FAKE-1234"}""",
                    )
                }
                "account/read" -> response(
                    id,
                    if (backend.loggedIn) {
                        """{"account":{"type":"chatgpt","email":"${backend.email}","planType":"${backend.planType}"},"requiresOpenaiAuth":true}"""
                    } else {
                        """{"account":null,"requiresOpenaiAuth":true}"""
                    },
                )
                "account/rateLimits/read" -> response(
                    id,
                    """{"rateLimits":{"primary":{"usedPercent":${backend.usedPercent},"resetsAt":123}}}""",
                )
                "account/login/cancel", "account/logout" -> {
                    backend.loggedIn = false
                    response(id, "{}")
                }
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

    private companion object {
        const val FAKE_AUTH_JSON =
            """{"auth_mode":"chatgpt","tokens":{"access_token":"fake-instrumentation-access-token","refresh_token":"fake-instrumentation-refresh-token"}}"""
    }
}
