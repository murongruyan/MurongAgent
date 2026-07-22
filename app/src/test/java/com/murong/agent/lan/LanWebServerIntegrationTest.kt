package com.murong.agent.lan

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class LanWebServerIntegrationTest {
    private class MemorySyncKeyStore : LanWebSyncKeyStore {
        private val values = linkedMapOf<String, ByteArray>()
        override fun put(clientId: String, key: ByteArray) { values[clientId] = key.copyOf() }
        override fun read(clientId: String): ByteArray? = values[clientId]?.copyOf()
        override fun remove(clientId: String) { values.remove(clientId)?.fill(0) }
        override fun clear() { values.values.forEach { it.fill(0) }; values.clear() }
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder().readTimeout(3, TimeUnit.SECONDS).build()
    private lateinit var accessStore: LanWebAccessStore
    private lateinit var server: LanWebServer
    private lateinit var gateway: FakeGateway
    private lateinit var workspaceBridge: LanWebComputerWorkspaceBridge
    private lateinit var desktopAgentBridge: LanWebDesktopAgentBridge
    private lateinit var pairingAuthenticator: LanWebPairingAuthenticator
    private lateinit var phoneIdentity: LanWebDeviceIdentity
    private lateinit var baseUrl: String

    @BeforeTest
    fun setUp() {
        val directory = createTempDirectory("lan-server-")
        val file = directory.resolve("access.json").toFile()
        accessStore = LanWebAccessStore(file, syncKeyStore = MemorySyncKeyStore())
        pairingAuthenticator = LanWebPairingAuthenticator(directory.resolve("pairing-auth.json").toFile())
        phoneIdentity = LanWebDeviceIdentity { keyPair() }
        gateway = FakeGateway()
        workspaceBridge = LanWebComputerWorkspaceBridge()
        desktopAgentBridge = LanWebDesktopAgentBridge()
        server = LanWebServer(
            context = null,
            bindAddress = "127.0.0.1",
            accessStore = accessStore,
            deviceIdentity = phoneIdentity,
            connectionCoordinator = LanWebConnectionCoordinator(
                accessStore = accessStore,
                identity = phoneIdentity,
                passwordAuthenticator = pairingAuthenticator::authenticate,
            ),
            pairingAuthenticator = pairingAuthenticator,
            chatBridge = gateway,
            eventHub = LanWebEventHub(),
            computerWorkspaceBridge = workspaceBridge,
            desktopAgentBridge = desktopAgentBridge,
            onAccessChanged = {},
            onPairingConsumed = {},
            port = 0,
            assetTextLoader = { path -> "asset:$path" }
        )
        server.start(5_000, false)
        baseUrl = "http://127.0.0.1:${server.listeningPort}"
    }

    @AfterTest
    fun tearDown() {
        server.stop()
    }

    @Test
    fun `static page is hardened and API is closed before pairing`() {
        client.newCall(Request.Builder().url("$baseUrl/").build()).execute().use { response ->
            assertEquals(200, response.code)
            assertNotNull(response.header("Content-Security-Policy"))
            assertEquals("DENY", response.header("X-Frame-Options"))
            assertNullHeader(response.header("Access-Control-Allow-Origin"))
        }
        client.newCall(Request.Builder().url("$baseUrl/api/v1/sessions").build()).execute().use { response ->
            assertEquals(401, response.code)
        }
        client.newCall(Request.Builder().url("$baseUrl/workspace-core.js").build()).execute().use { response ->
            assertEquals(200, response.code)
            assertTrue(response.body!!.string().contains("asset:lan_web/workspace-core.js"))
        }
    }

    @Test
    fun `pairing unlocks minimal API and duplicate mutations execute once`() {
        val cookie = pairClient()
        authenticated("/api/v1/sessions", cookie).execute().use { response ->
            assertEquals(200, response.code)
            assertTrue(response.body!!.string().contains("session123"))
        }

        val requestBody = """{"sessionId":"session123","message":"hello","requestId":"request-0001"}"""
        repeat(2) { index ->
            val request = Request.Builder()
                .url("$baseUrl/api/v1/messages")
                .header("Cookie", cookie)
                .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
                .build()
            client.newCall(request).execute().use { response ->
                assertEquals(if (index == 0) 202 else 409, response.code)
            }
        }
        assertEquals(1, gateway.enqueued.get())
    }

    @Test
    fun `SCRAM challenge route establishes stable device trust without sending the code`() {
        val now = System.currentTimeMillis()
        val code = pairingAuthenticator.beginTemporaryCode(now)
        accessStore.beginPairing(now = now, rawCode = code.value)
        val desktop = LanWebDeviceIdentity { keyPair() }
        val requesterEphemeral = keyPair()
        val unsignedChallenge = LanWebPairChallengeRequest(
            requestId = "connect-http-scram1",
            clientName = "Murong Desktop",
            deviceId = desktop.snapshot.deviceId,
            devicePublicKey = desktop.snapshot.publicKey,
            deviceFingerprint = desktop.snapshot.publicKeyFingerprint,
            ephemeralPublicKey = LanWebDeviceIdentity.publicKey(requesterEphemeral),
            platform = "windows",
            issuedAt = now,
            authMethod = LanWebTrustSource.TEMPORARY_CODE,
            clientNonce = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(18) { it.toByte() }),
            signature = "",
        )
        val challengeRequest = unsignedChallenge.copy(
            signature = desktop.sign(LanWebPairingAuthenticator.pairChallengeSignaturePayload(unsignedChallenge)),
        )
        val challenge = postPublic(
            LanWebContract.PAIR_CHALLENGE_PATH,
            json.encodeToString(challengeRequest),
        ).execute().use { response ->
            val text = response.body?.string().orEmpty()
            assertEquals(200, response.code, text)
            assertFalse(text.contains(code.value))
            json.decodeFromString<LanWebPairChallengeResponse>(text)
        }
        val salt = requireNotNull(LanWebScramCrypto.decode(challenge.salt, LanWebScramCrypto.SALT_BYTES))
        val authMessage = LanWebScramCrypto.authMessage(challengeRequest, challenge)
        val proof = LanWebScramCrypto.clientProof(
            code.value.replace("-", ""), salt, challenge.iterations, authMessage,
        )
        salt.fill(0)
        authMessage.fill(0)
        val unsignedConnection = LanWebConnectionRequest(
            requestId = challengeRequest.requestId,
            clientName = challengeRequest.clientName,
            deviceId = challengeRequest.deviceId,
            devicePublicKey = challengeRequest.devicePublicKey,
            deviceFingerprint = challengeRequest.deviceFingerprint,
            ephemeralPublicKey = challengeRequest.ephemeralPublicKey,
            platform = challengeRequest.platform,
            issuedAt = challengeRequest.issuedAt,
            authMethod = challengeRequest.authMethod,
            authProof = "${challenge.sessionId}.${proof.proof}",
            signature = "",
        )
        val connection = unsignedConnection.copy(
            signature = desktop.sign(LanWebConnectionCoordinator.connectionRequestSignaturePayload(unsignedConnection)),
        )
        postPublic(LanWebContract.CONNECTION_REQUEST_PATH, json.encodeToString(connection)).execute().use { response ->
            assertEquals(200, response.code, response.body?.string().orEmpty())
        }
        val unsignedStatus = LanWebConnectionStatusRequest(connection.requestId, connection.deviceId, now + 1, "")
        val status = unsignedStatus.copy(
            signature = desktop.sign(LanWebConnectionCoordinator.statusSignaturePayload(unsignedStatus)),
        )
        postPublic(LanWebContract.CONNECTION_STATUS_PATH, json.encodeToString(status)).execute().use { response ->
            val text = response.body?.string().orEmpty()
            assertEquals(200, response.code, text)
            val approved = json.decodeFromString<LanWebConnectionStatusResponse>(text)
            assertEquals(LanWebConnectionCoordinator.STATUS_APPROVED, approved.status)
            assertEquals(proof.expectedServerProof, approved.authServerProof)
        }
        assertEquals(LanWebTrustSource.TEMPORARY_CODE, accessStore.clients().single().trustSource)
        assertFalse(pairingAuthenticator.snapshot(now + 2).temporaryCodeAvailable)
    }

    @Test
    fun `legacy desktop proof still uses an active temporary code on the relay loopback`() {
        val temporaryCode = accessStore.beginPairing().value
        val body = json.encodeToString(
            LanWebPairRequest(
                clientName = "Older Murong Desktop",
                secureChannelVersion = LanWebContract.SECURE_PAIRING_VERSION,
                codeProof = LanWebPairingCrypto.codeProof(temporaryCode),
            )
        )
        val request = Request.Builder()
            .url("$baseUrl/api/v1/pair")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        client.newCall(request).execute().use { response ->
            val responseText = response.body?.string().orEmpty()
            assertEquals(200, response.code, responseText)
            assertNotNull(json.decodeFromString<LanWebPairResponse>(responseText).secureChannel)
            assertFalse(accessStore.isPairingAvailable())
        }
    }

    @Test
    fun `host and origin rebinding attempts are rejected`() {
        client.newCall(
            Request.Builder().url("$baseUrl/").header("Host", "evil.example:8765").build()
        ).execute().use { response -> assertEquals(403, response.code) }

        client.newCall(
            Request.Builder()
                .url("$baseUrl/")
                .header("Origin", "http://evil.example:${server.listeningPort}")
                .build()
        ).execute().use { response -> assertEquals(403, response.code) }
    }

    @Test
    fun `self revoke immediately invalidates bearer token`() {
        val cookie = pairClient()
        registerWorkspace(cookie)
        assertNotNull(workspaceBridge.activeWorkspace())
        val revoke = Request.Builder()
            .url("$baseUrl/api/v1/unpair")
            .header("Cookie", cookie)
            .post(ByteArray(0).toRequestBody(null))
            .build()
        client.newCall(revoke).execute().use { response -> assertEquals(200, response.code) }
        authenticated("/api/v1/sessions", cookie).execute().use { response -> assertEquals(401, response.code) }
        assertTrue(workspaceBridge.activeWorkspace() == null)
        assertFalse(desktopAgentBridge.state.value.connected)
    }

    @Test
    fun `paired Windows client registers and publishes bounded desktop task state`() {
        val cookie = pairClient()
        val nodeSessionId = "node-session-0001"
        postAuthenticated(
            "/api/v1/desktop-agent/register",
            cookie,
            """{"nodeSessionId":"$nodeSessionId","controlAllowed":true,"requestId":"desktop-register-0001"}"""
        ).execute().use { response ->
            assertEquals(200, response.code, response.body?.string().orEmpty())
        }
        postAuthenticated(
            "/api/v1/desktop-agent/snapshot",
            cookie,
            """{"nodeSessionId":"$nodeSessionId","sequence":1,"generatedAt":1000,"sessions":[{"id":"session_0001","title":"Desktop task","updatedAt":1000,"messageCount":1}],"activeSession":{"id":"session_0001","title":"Desktop task","updatedAt":1000,"messages":[{"id":"message-0001","role":"user","content":"hello","timestamp":1000}]}}"""
        ).execute().use { response ->
            assertEquals(202, response.code, response.body?.string().orEmpty())
        }
        authenticated("/api/v1/desktop-agent/status", cookie).execute().use { response ->
            val body = response.body!!.string()
            assertEquals(200, response.code, body)
            assertTrue(body.contains("\"connected\":true"), body)
            assertTrue(body.contains("Desktop task"), body)
            assertTrue(body.contains("\"controlAllowed\":true"), body)
        }
    }

    @Test
    fun `approval decision is authenticated and request id deduplicated`() {
        val cookie = pairClient()
        val body = """{"sessionId":"session123","approvalId":"approval-123","decision":"approve","requestId":"approval-request-0001"}"""
        repeat(2) { index ->
            val request = Request.Builder()
                .url("$baseUrl/api/v1/approval")
                .header("Cookie", cookie)
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .build()
            client.newCall(request).execute().use { response ->
                assertEquals(if (index == 0) 202 else 409, response.code)
            }
        }
        assertEquals(1, gateway.decisions.get())
    }

    @Test
    fun `authorized SSE starts with a current snapshot and no token in URL`() {
        val cookie = pairClient()
        authenticated("/api/v1/events", cookie).execute().use { response ->
            assertEquals(200, response.code)
            assertTrue(response.header("Content-Type").orEmpty().startsWith("text/event-stream"))
            val source = response.body!!.source()
            val lines = buildList {
                repeat(12) {
                    val line = source.readUtf8Line() ?: return@repeat
                    if (line.isEmpty()) return@buildList
                    add(line)
                }
            }
            assertTrue(lines.any { it == "event: snapshot" }, lines.joinToString("\n"))
            assertTrue(lines.any { it.contains("session123") }, lines.joinToString("\n"))
        }
    }

    @Test
    fun `authenticated browser registers heartbeats disconnects and reconnects a workspace capability`() {
        val cookie = pairClient()
        val workspaceSessionId = "workspace-session-0001"
        val registerBody = """{"workspaceSessionId":"$workspaceSessionId","label":"Desktop Project","readable":true,"writable":true,"requestId":"workspace-register-0001"}"""
        postAuthenticated("/api/v1/workspace/register", cookie, registerBody).execute().use { response ->
            assertEquals(200, response.code, response.body?.string().orEmpty())
        }
        authenticated("/api/v1/workspace/status", cookie).execute().use { response ->
            val body = response.body!!.string()
            assertEquals(200, response.code, body)
            assertTrue(body.contains("\"connected\":true"), body)
            assertTrue(body.contains("Desktop Project"), body)
        }

        postAuthenticated(
            "/api/v1/workspace/heartbeat",
            cookie,
            """{"workspaceSessionId":"$workspaceSessionId"}"""
        ).execute().use { response -> assertEquals(200, response.code, response.body?.string().orEmpty()) }

        postAuthenticated(
            "/api/v1/workspace/disconnect",
            cookie,
            """{"workspaceSessionId":"$workspaceSessionId"}"""
        ).execute().use { response -> assertEquals(200, response.code, response.body?.string().orEmpty()) }
        authenticated("/api/v1/workspace/status", cookie).execute().use { response ->
            assertTrue(response.body!!.string().contains("\"connected\":false"))
        }

        val reconnectedSessionId = "workspace-session-0002"
        postAuthenticated(
            "/api/v1/workspace/register",
            cookie,
            """{"workspaceSessionId":"$reconnectedSessionId","label":"Desktop Project","readable":true,"writable":true,"requestId":"workspace-register-0002"}"""
        ).execute().use { response ->
            assertEquals(200, response.code, response.body?.string().orEmpty())
        }
        authenticated("/api/v1/workspace/status", cookie).execute().use { response ->
            val body = response.body!!.string()
            assertTrue(body.contains("\"connected\":true"), body)
            assertTrue(body.contains(reconnectedSessionId), body)
        }
    }

    @Test
    fun `workspace change report may exceed generic request envelope`() {
        val cookie = pairClient()
        registerWorkspace(cookie)
        val pathSuffix = "界".repeat(200)
        val body = json.encodeToString(
            LanWebWorkspaceChangeReportRequest(
                workspaceSessionId = WORKSPACE_SESSION_ID,
                reportId = "changes-report-large-0001",
                changes = List(200) { index ->
                    LanWebWorkspaceObservedChange(
                        path = "changes/${index.toString().padStart(3, '0')}-$pathSuffix.txt",
                        kind = "modified"
                    )
                }
            )
        )
        assertTrue(body.toByteArray().size > LanWebContract.MAX_REQUEST_BODY_BYTES)
        assertTrue(body.toByteArray().size < LanWebContract.MAX_WORKSPACE_CHANGES_BODY_BYTES)

        postAuthenticated("/api/v1/workspace/changes", cookie, body).execute().use { response ->
            assertEquals(202, response.code, response.body?.string().orEmpty())
        }
    }

    @Test
    fun `workspace result transport accepts worst case escaped one MiB text envelope`() {
        val cookie = pairClient()
        registerWorkspace(cookie)
        val body = json.encodeToString(
            LanWebWorkspaceResultRequest(
                workspaceSessionId = WORKSPACE_SESSION_ID,
                requestId = "missing-result-request-0001",
                success = false,
                content = "\n".repeat(1024 * 1024)
            )
        )
        assertTrue(body.toByteArray().size > 2 * 1024 * 1024)
        assertTrue(body.toByteArray().size < LanWebContract.MAX_WORKSPACE_RESULT_BODY_BYTES)

        postAuthenticated("/api/v1/workspace/result", cookie, body).execute().use { response ->
            // The transport decoded the body; only the deliberately missing pending RPC conflicts.
            assertEquals(409, response.code, response.body?.string().orEmpty())
        }
    }

    private fun registerWorkspace(cookie: String) {
        val registerBody = """{"workspaceSessionId":"$WORKSPACE_SESSION_ID","label":"Desktop Project","readable":true,"writable":true,"requestId":"workspace-register-0001"}"""
        postAuthenticated("/api/v1/workspace/register", cookie, registerBody).execute().use { response ->
            assertEquals(200, response.code, response.body?.string().orEmpty())
        }
    }

    private fun pairClient(): String {
        val code = accessStore.beginPairing(now = System.currentTimeMillis()).value
        val body = """{"code":"$code","clientName":"Test Browser"}"""
        val request = Request.Builder()
            .url("$baseUrl/api/v1/pair")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return client.newCall(request).execute().use { response ->
            val responseText = response.body?.string().orEmpty()
            assertEquals(200, response.code, responseText)
            json.decodeFromString<LanWebPairResponse>(responseText)
            assertFalse(responseText.contains("accessToken"))
            val setCookie = response.header("Set-Cookie").orEmpty()
            assertTrue(setCookie.contains("HttpOnly"), setCookie)
            assertTrue(setCookie.contains("SameSite=Strict"), setCookie)
            val cookie = setCookie.substringBefore(';')
            val rawToken = cookie.substringAfter('=')
            assertFalse(accessStore.persistedTextForTest().orEmpty().contains(rawToken))
            cookie
        }
    }

    private fun authenticated(path: String, cookie: String) = client.newCall(
        Request.Builder()
            .url("$baseUrl$path")
            .header("Cookie", cookie)
            .build()
    )

    private fun postAuthenticated(path: String, cookie: String, body: String) = client.newCall(
        Request.Builder()
            .url("$baseUrl$path")
            .header("Cookie", cookie)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
    )

    private fun postPublic(path: String, body: String) = client.newCall(
        Request.Builder()
            .url("$baseUrl$path")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
    )

    private fun assertNullHeader(value: String?) {
        assertTrue(value == null)
    }

    private fun keyPair(): KeyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"))
        generateKeyPair()
    }

    private class FakeGateway : LanWebChatGateway {
        val enqueued = AtomicInteger(0)
        val decisions = AtomicInteger(0)

        override fun listSessions() = LanWebSessionsResponse(
            activeSessionId = "session123",
            sessions = listOf(
                LanWebSessionSummary(
                    id = "session123",
                    title = "Test",
                    updatedAt = 1L,
                    messageCount = 1,
                    providerId = "test",
                    modelName = "test",
                    active = true
                )
            )
        )

        override fun sessionDetail(sessionId: String, liveLimit: Int) =
            if (sessionId == "session123") {
                LanWebSessionDetail(
                    id = sessionId,
                    title = "Test",
                    active = true,
                    processing = false,
                    messages = emptyList()
                )
            } else null

        override fun liveState() = LanWebLiveState(
            session = LanWebLiveSessionState(
                id = "session123",
                title = "Test",
                processing = false,
                pendingApproval = LanWebApproval(
                    approvalId = "approval-123",
                    sessionId = "session123",
                    toolName = "shell",
                    summary = "Test approval",
                    detail = "detail",
                    arguments = "{}",
                    riskLevel = "HIGH",
                    approveEnabled = true
                )
            ),
            updatedAt = 1L
        )

        override fun enqueueMessage(sessionId: String, rawMessage: String): Result<Unit> {
            enqueued.incrementAndGet()
            return Result.success(Unit)
        }

        override fun decideApproval(sessionId: String, approvalId: String, approve: Boolean): Result<Unit> {
            if (sessionId != "session123" || approvalId != "approval-123" || !approve) {
                return Result.failure(IllegalArgumentException("stale approval"))
            }
            decisions.incrementAndGet()
            return Result.success(Unit)
        }
    }

    private companion object {
        const val WORKSPACE_SESSION_ID = "workspace-session-0001"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
