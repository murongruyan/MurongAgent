package com.murong.agent.lan

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class LanWebDesktopAgentBridgeTest {
    @Test
    fun `read-only desktop task channel publishes snapshots and returns selected session`() = runBlocking {
        val bridge = LanWebDesktopAgentBridge()
        bridge.register("client-a", registration(controlAllowed = false)).getOrThrow()
        bridge.publishSnapshot("client-a", snapshot()).getOrThrow()

        assertTrue(bridge.state.value.connected)
        assertFalse(bridge.state.value.controlAllowed)
        assertEquals("Desktop task", bridge.state.value.snapshot?.activeSession?.title)

        val dispatch = async { bridge.commands.first() }
        val result = async { bridge.command("get_session", sessionId = SESSION_ID) }
        val command = dispatch.await()
        assertEquals("client-a", command.targetClientId)
        assertEquals("get_session", command.event.operation)
        bridge.complete(
            "client-a",
            LanWebDesktopAgentCommandResultRequest(
                nodeSessionId = NODE_SESSION_ID,
                requestId = command.event.requestId,
                success = true,
                session = snapshot().activeSession
            )
        ).getOrThrow()
        assertTrue(result.await().getOrThrow().success)
    }

    @Test
    fun `control commands require the persistent Windows permission`() = runBlocking {
        val bridge = LanWebDesktopAgentBridge()
        bridge.register("client-a", registration(controlAllowed = false)).getOrThrow()

        val result = bridge.command("send_message", sessionId = SESSION_ID, content = "hello")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("未开启手机控制"))
    }

    @Test
    fun `protocol v2 mirrors and answers desktop ask user requests`() = runBlocking {
        val bridge = LanWebDesktopAgentBridge()
        bridge.register("client-a", registration(controlAllowed = true).copy(protocolVersion = 2)).getOrThrow()
        val ask = LanWebDesktopAgentAskRequest(
            id = "ask-request-0001",
            sessionId = SESSION_ID,
            createdAt = 1_000,
            questions = listOf(
                LanWebDesktopAgentAskQuestion(
                    id = "q1",
                    header = "范围",
                    question = "先处理哪个端？",
                    options = listOf(
                        LanWebDesktopAgentAskOption("桌面", "继续桌面端"),
                        LanWebDesktopAgentAskOption("手机")
                    )
                )
            )
        )
        bridge.publishSnapshot(
            "client-a",
            snapshot().copy(
                sessions = snapshot().sessions.map { it.copy(pendingQuestion = true) },
                activeSession = snapshot().activeSession?.copy(running = true, pendingAsk = ask)
            )
        ).getOrThrow()
        assertEquals("范围", bridge.state.value.snapshot?.activeSession?.pendingAsk?.questions?.single()?.header)

        val dispatch = async { bridge.commands.first() }
        val pending = async {
            bridge.command(
                operation = "ask",
                sessionId = SESSION_ID,
                askId = ask.id,
                askAnswers = listOf(LanWebDesktopAgentAskAnswer("q1", listOf("桌面")))
            )
        }
        val command = dispatch.await()
        assertEquals("ask", command.event.operation)
        assertEquals(ask.id, command.event.askId)
        assertEquals(listOf("桌面"), command.event.askAnswers.single().selectedOptions)
        bridge.complete(
            "client-a",
            LanWebDesktopAgentCommandResultRequest(
                nodeSessionId = NODE_SESSION_ID,
                requestId = command.event.requestId,
                success = true,
                session = snapshot().activeSession
            )
        ).getOrThrow()
        assertTrue(pending.await().getOrThrow().success)
    }

    @Test
    fun `offline desktop mirror removes pending questions`() {
        var now = 1_000L
        val mirrorFile = createTempDirectory("desktop-ask-mirror").resolve("mirror.json").toFile()
        val bridge = LanWebDesktopAgentBridge(LanWebDesktopAgentMirrorStore(mirrorFile)).apply { nowProvider = { now } }
        bridge.register("client-a", registration(controlAllowed = true).copy(protocolVersion = 2)).getOrThrow()
        val ask = LanWebDesktopAgentAskRequest(
            id = "ask-request-0001", sessionId = SESSION_ID, createdAt = 1_000,
            questions = listOf(
                LanWebDesktopAgentAskQuestion(
                    id = "q1", question = "继续吗？",
                    options = listOf(LanWebDesktopAgentAskOption("继续"), LanWebDesktopAgentAskOption("停止"))
                )
            )
        )
        bridge.publishSnapshot(
            "client-a",
            snapshot().copy(
                sessions = snapshot().sessions.map { it.copy(pendingQuestion = true) },
                activeSession = snapshot().activeSession?.copy(running = true, pendingAsk = ask)
            )
        ).getOrThrow()
        now += 30_001L

        assertFalse(bridge.status().connected)
        assertFalse(bridge.state.value.snapshot?.sessions?.single()?.pendingQuestion == true)
        assertEquals(null, bridge.state.value.snapshot?.activeSession?.pendingAsk)
        val restored = LanWebDesktopAgentBridge(LanWebDesktopAgentMirrorStore(mirrorFile)).state.value.snapshot
        assertFalse(restored?.sessions?.single()?.pendingQuestion == true)
        assertEquals(null, restored?.activeSession?.pendingAsk)
    }

    @Test
    fun `protocol v3 mirrors validated workflow progress and v2 rejects it`() {
        val mirrorFile = createTempDirectory("desktop-plan-mirror").resolve("mirror.json").toFile()
        val bridge = LanWebDesktopAgentBridge(LanWebDesktopAgentMirrorStore(mirrorFile))
        bridge.register("client-a", registration(controlAllowed = true).copy(protocolVersion = 3)).getOrThrow()
        val plan = LanWebDesktopAgentWorkflowPlan(
            id = "plan-0001",
            summary = "Implement and verify",
            steps = listOf("Read files", "Run tests"),
            currentStepIndex = 1,
            status = "blocked",
            nextStepHint = "Continue with tests",
            stepSignOffs = listOf(
                LanWebDesktopAgentWorkflowStepSignOff(
                    stepIndex = 0,
                    resultSummary = "Files inspected",
                    matchedEvidence = 1,
                    totalEvidence = 1,
                    matchedToolNames = listOf("read_file"),
                    signedOffAt = 1_100
                )
            ),
            createdAt = 1_000,
            executionStartedAt = 1_050,
            updatedAt = 1_200
        )
        val withPlan = snapshot().copy(activeSession = snapshot().activeSession?.copy(workflowPlan = plan))

        bridge.publishSnapshot("client-a", withPlan).getOrThrow()

        val mirrored = assertNotNull(bridge.state.value.snapshot?.activeSession?.workflowPlan)
        assertEquals("blocked", mirrored.status)
        assertEquals(listOf("read_file"), mirrored.stepSignOffs.single().matchedToolNames)
        bridge.disconnect("client-a", NODE_SESSION_ID)
        val offline = assertNotNull(LanWebDesktopAgentBridge(LanWebDesktopAgentMirrorStore(mirrorFile)).state.value.snapshot?.activeSession?.workflowPlan)
        assertEquals(1, offline.currentStepIndex)

        val v2 = LanWebDesktopAgentBridge()
        v2.register("client-b", registration(controlAllowed = true).copy(nodeSessionId = "node-session-0002", protocolVersion = 2)).getOrThrow()
        assertTrue(
            v2.publishSnapshot(
                "client-b",
                withPlan.copy(nodeSessionId = "node-session-0002")
            ).isFailure
        )
    }

    @Test
    fun `desktop workflow projection rejects forged progress`() {
        val bridge = LanWebDesktopAgentBridge()
        bridge.register("client-a", registration(controlAllowed = true).copy(protocolVersion = 3)).getOrThrow()
        val forged = LanWebDesktopAgentWorkflowPlan(
            id = "plan-0001",
            summary = "Forged",
            steps = listOf("Read files"),
            currentStepIndex = 1,
            status = "completed",
            stepSignOffs = emptyList(),
            createdAt = 1_000,
            executionStartedAt = 1_050,
            updatedAt = 1_100
        )

        assertTrue(
            bridge.publishSnapshot(
                "client-a",
                snapshot().copy(activeSession = snapshot().activeSession?.copy(workflowPlan = forged))
            ).isFailure
        )
    }

    @Test
    fun `handoff command requires control and validates capability response`() = runBlocking {
        val syncKey = ByteArray(32) { (it + 1).toByte() }
        val bridge = LanWebDesktopAgentBridge(null, FixedSyncKeyStore(syncKey))
        bridge.register("client-a", registration(controlAllowed = true)).getOrThrow()
        bridge.publishSnapshot("client-a", snapshot()).getOrThrow()

        val dispatch = async { bridge.commands.first() }
        val pending = async { bridge.command("begin_handoff", sessionId = SESSION_ID) }
        val command = dispatch.await()
        assertEquals("begin_handoff", command.event.operation)
        assertEquals(null, command.event.handoffEnvelope)
        val token = "handoff-" + "a".repeat(64)
        val portable = "{\"format\":\"murong-portable-session\"}"
        val envelope = LanWebDeviceSyncCrypto.encrypt(
            key = syncKey,
            requestId = command.event.requestId,
            issuedAt = System.currentTimeMillis(),
            direction = LanWebDeviceSyncCrypto.DESKTOP_HANDOFF_TO_ANDROID,
            plaintext = "{\"handoffToken\":\"$token\",\"portableSession\":${jsonString(portable)}}"
        )
        bridge.complete(
            "client-a",
            LanWebDesktopAgentCommandResultRequest(
                nodeSessionId = NODE_SESSION_ID,
                requestId = command.event.requestId,
                success = true,
                handoffEnvelope = envelope
            )
        ).getOrThrow()
        val response = pending.await().getOrThrow()
        assertEquals(token, response.handoffToken)
        assertEquals(portable, response.portableSession)
        assertEquals(null, response.handoffEnvelope)
        assertEquals("android", bridge.state.value.snapshot?.activeSession?.executionOwner)

        val returnDispatch = async { bridge.commands.first() }
        val returning = async {
            bridge.command(
                operation = "return_handoff",
                sessionId = SESSION_ID,
                handoffToken = token,
                portableSession = portable
            )
        }
        val returnCommand = returnDispatch.await()
        assertEquals(null, returnCommand.event.handoffToken)
        assertEquals(null, returnCommand.event.portableSession)
        val returnEnvelope = assertNotNull(returnCommand.event.handoffEnvelope)
        assertEquals(LanWebDeviceSyncCrypto.DESKTOP_HANDOFF_TO_DESKTOP, returnEnvelope.direction)
        val decrypted = LanWebDeviceSyncCrypto.decrypt(syncKey, returnEnvelope)
        assertTrue(decrypted.contains(token))
        assertTrue(decrypted.contains("murong-portable-session"))
        bridge.complete(
            "client-a",
            LanWebDesktopAgentCommandResultRequest(
                nodeSessionId = NODE_SESSION_ID,
                requestId = returnCommand.event.requestId,
                success = true
            )
        ).getOrThrow()
        assertTrue(returning.await().getOrThrow().success)

        assertTrue(
            bridge.command(
                operation = "return_handoff",
                sessionId = SESSION_ID,
                handoffToken = "invalid",
                portableSession = "{}"
            ).isFailure
        )
    }

    @Test
    fun `snapshot rejects inconsistent execution owner metadata`() {
        val bridge = LanWebDesktopAgentBridge()
        bridge.register("client-a", registration(controlAllowed = true)).getOrThrow()

        val invalid = snapshot().copy(
            sessions = snapshot().sessions.map { it.copy(executionOwner = "desktop", handoffStartedAt = 1_000) }
        )

        assertTrue(bridge.publishSnapshot("client-a", invalid).isFailure)
    }

    @Test
    fun `expired desktop agent keeps a durable offline snapshot without stale runtime state`() {
        var now = 1_000L
        val mirrorFile = createTempDirectory("desktop-mirror").resolve("mirror.json").toFile()
        val bridge = LanWebDesktopAgentBridge(LanWebDesktopAgentMirrorStore(mirrorFile)).apply {
            nowProvider = { now }
        }
        bridge.register("client-a", registration(controlAllowed = true)).getOrThrow()
        bridge.publishSnapshot(
            "client-a",
            snapshot().copy(
                activeSession = snapshot().activeSession?.copy(
                    running = true,
                    pendingApproval = LanWebDesktopAgentApproval(
                        id = "approval-request-0001",
                        sessionId = SESSION_ID,
                        toolName = "shell",
                        summary = "Run command",
                        detail = "Details",
                        risk = "high"
                    )
                )
            )
        ).getOrThrow()

        now += 30_001L

        assertFalse(bridge.status().connected)
        val cached = assertNotNull(bridge.state.value.snapshot?.activeSession)
        assertEquals("Desktop task", cached.title)
        assertFalse(cached.running)
        assertEquals(null, cached.pendingApproval)

        val recreated = LanWebDesktopAgentBridge(LanWebDesktopAgentMirrorStore(mirrorFile))
        assertFalse(recreated.state.value.connected)
        assertEquals("hello", recreated.state.value.snapshot?.activeSession?.messages?.single()?.content)
        assertEquals(listOf("screen.png"), recreated.state.value.snapshot?.activeSession?.messages?.single()?.attachmentNames)
    }

    @Test
    fun `background session updates persist and can be opened while Windows is offline`() {
        val mirrorFile = createTempDirectory("desktop-mirror-updates").resolve("mirror.json").toFile()
        val bridge = LanWebDesktopAgentBridge(LanWebDesktopAgentMirrorStore(mirrorFile))
        bridge.register("client-a", registration(controlAllowed = true)).getOrThrow()
        val secondId = "session_0002"
        bridge.publishSnapshot(
            "client-a",
            snapshot().copy(
                sessions = snapshot().sessions + LanWebDesktopAgentTaskSummary(
                    id = secondId,
                    title = "Second task",
                    updatedAt = 2_000,
                    messageCount = 2
                ),
                sessionUpdates = listOf(
                    LanWebDesktopAgentTaskDetail(
                        id = secondId,
                        title = "Second task",
                        updatedAt = 2_000,
                        messages = listOf(
                            LanWebDesktopAgentMessage("message-0002", "user", "question", 1_500),
                            LanWebDesktopAgentMessage("message-0003", "assistant", "desktop answer", 2_000)
                        ),
                        messageCount = 2
                    )
                )
            )
        ).getOrThrow()
        bridge.disconnect("client-a", NODE_SESSION_ID)

        assertTrue(bridge.selectCachedSession(secondId))
        val selected = assertNotNull(bridge.state.value.snapshot?.activeSession)
        assertEquals(secondId, selected.id)
        assertEquals("desktop answer", selected.messages.last().content)
    }

    @Test
    fun `revoking the paired client deletes its durable desktop session mirror`() {
        val mirrorFile = createTempDirectory("desktop-mirror-revoke").resolve("mirror.json").toFile()
        val store = LanWebDesktopAgentMirrorStore(mirrorFile)
        val bridge = LanWebDesktopAgentBridge(store)
        bridge.register("client-a", registration(controlAllowed = true)).getOrThrow()
        bridge.publishSnapshot("client-a", snapshot()).getOrThrow()
        assertTrue(mirrorFile.exists())

        bridge.forgetClient("client-a")

        assertFalse(mirrorFile.exists())
        assertEquals(null, bridge.state.value.snapshot)
        assertEquals(null, LanWebDesktopAgentBridge(store).state.value.snapshot)
    }

    private fun registration(controlAllowed: Boolean) = LanWebDesktopAgentRegisterRequest(
        nodeSessionId = NODE_SESSION_ID,
        controlAllowed = controlAllowed,
        requestId = "desktop-register-0001"
    )

    private fun snapshot() = LanWebDesktopAgentSnapshotRequest(
        nodeSessionId = NODE_SESSION_ID,
        sequence = 1,
        generatedAt = 1_000,
        sessions = listOf(
            LanWebDesktopAgentTaskSummary(
                id = SESSION_ID,
                title = "Desktop task",
                updatedAt = 1_000,
                messageCount = 1
            )
        ),
        activeSession = LanWebDesktopAgentTaskDetail(
            id = SESSION_ID,
            title = "Desktop task",
            updatedAt = 1_000,
            messages = listOf(
                LanWebDesktopAgentMessage(
                    "message-0001", "user", "hello", 1_000,
                    attachmentNames = listOf("screen.png")
                )
            )
        )
    )

    companion object {
        private const val NODE_SESSION_ID = "node-session-0001"
        private const val SESSION_ID = "session_0001"
    }
}

private class FixedSyncKeyStore(key: ByteArray) : LanWebSyncKeyStore {
    private val value = key.copyOf()

    override fun put(clientId: String, key: ByteArray) = Unit

    override fun read(clientId: String): ByteArray = value.copyOf()

    override fun remove(clientId: String) = Unit

    override fun clear() = Unit
}

private fun jsonString(value: String): String = buildString(value.length + 2) {
    append('"')
    value.forEach { char ->
        when (char) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            else -> append(char)
        }
    }
    append('"')
}
