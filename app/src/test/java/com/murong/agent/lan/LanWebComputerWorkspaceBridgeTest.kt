package com.murong.agent.lan

import com.murong.agent.core.workspace.ComputerWorkspaceOperation
import com.murong.agent.core.workspace.ComputerWorkspaceRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class LanWebComputerWorkspaceBridgeTest {
    @Test
    fun `registered browser completes one time read RPC`() = runBlocking {
        val bridge = LanWebComputerWorkspaceBridge()
        bridge.register("client-a", registration()).getOrThrow()
        val dispatch = async { bridge.requests.first() }
        val result = async {
            bridge.execute(ComputerWorkspaceRequest(ComputerWorkspaceOperation.READ, "README.md"))
        }

        val request = dispatch.await()
        assertEquals("client-a", request.targetClientId)
        assertEquals("read", request.event.operation)
        bridge.complete(
            "client-a",
            LanWebWorkspaceResultRequest(
                workspaceSessionId = WORKSPACE_SESSION,
                requestId = request.event.requestId,
                success = true,
                content = "hello",
                sha256 = "a".repeat(64),
                size = 5
            )
        ).getOrThrow()

        val response = result.await()
        assertTrue(response.success)
        assertEquals("hello", response.content)
        assertTrue(
            bridge.complete(
                "client-a",
                LanWebWorkspaceResultRequest(
                    workspaceSessionId = WORKSPACE_SESSION,
                    requestId = request.event.requestId,
                    success = false
                )
            ).isFailure
        )
    }

    @Test
    fun `another client cannot replace an active capability`() {
        val bridge = LanWebComputerWorkspaceBridge()
        bridge.register("client-a", registration()).getOrThrow()

        assertTrue(bridge.register("client-b", registration(requestId = "register-b-0001")).isFailure)
        assertEquals(WORKSPACE_SESSION, bridge.activeWorkspace()?.sessionId)
    }

    @Test
    fun `expired heartbeat removes capability`() {
        var now = 1_000L
        val bridge = LanWebComputerWorkspaceBridge().apply { nowProvider = { now } }
        bridge.register("client-a", registration()).getOrThrow()
        assertNotNull(bridge.activeWorkspace())

        now += 30_001L

        assertNull(bridge.activeWorkspace())
        assertFalse(bridge.status("client-a").connected)
    }

    @Test
    fun `disconnect fails pending write and never replays it`() = runBlocking {
        val bridge = LanWebComputerWorkspaceBridge()
        bridge.register("client-a", registration()).getOrThrow()
        val dispatch = async { bridge.requests.first() }
        val result = async {
            bridge.execute(
                ComputerWorkspaceRequest(
                    ComputerWorkspaceOperation.WRITE,
                    "src/a.kt",
                    content = "new",
                    expectedSha256 = "a".repeat(64)
                )
            )
        }
        dispatch.await()

        bridge.disconnect("client-a", WORKSPACE_SESSION)

        val response = result.await()
        assertFalse(response.success)
        assertEquals("workspace_disconnected", response.errorCode)
        assertNull(bridge.activeWorkspace())
    }

    @Test
    fun `registered node exposes terminal capability and completes run RPC`() = runBlocking {
        val bridge = LanWebComputerWorkspaceBridge()
        bridge.register(
            "client-a",
            registration(
                terminals = listOf(
                    LanWebTerminalBackend("powershell7", "PowerShell 7", "7.6.3"),
                    LanWebTerminalBackend("wsl:Ubuntu", "WSL · Ubuntu", "WSL 2")
                )
            )
        ).getOrThrow()
        val status = bridge.status("client-a")
        assertTrue(status.terminal)
        assertEquals(listOf("powershell7", "wsl:Ubuntu"), status.terminals.map { it.id })
        val dispatch = async { bridge.requests.first() }
        val result = async {
            bridge.execute(
                ComputerWorkspaceRequest(
                    operation = ComputerWorkspaceOperation.RUN,
                    relativePath = ".",
                    command = "Get-Location",
                    terminalId = "powershell7",
                    timeoutMillis = 30_000L
                )
            )
        }

        val request = dispatch.await()
        assertEquals("run", request.event.operation)
        assertEquals("Get-Location", request.event.command)
        assertEquals("powershell7", request.event.terminalId)
        assertEquals(30_000L, request.event.timeoutMillis)
        bridge.complete(
            "client-a",
            LanWebWorkspaceResultRequest(
                workspaceSessionId = WORKSPACE_SESSION,
                requestId = request.event.requestId,
                success = true,
                stdout = "C:\\project\n",
                exitCode = 0
            )
        ).getOrThrow()

        assertEquals(0, result.await().exitCode)
    }

    @Test
    fun `terminal and mkdir require node capabilities`() = runBlocking {
        val bridge = LanWebComputerWorkspaceBridge()
        bridge.register("client-a", registration(writable = false, terminal = false)).getOrThrow()

        val mkdir = bridge.execute(
            ComputerWorkspaceRequest(ComputerWorkspaceOperation.MKDIR, "generated")
        )
        val run = bridge.execute(
            ComputerWorkspaceRequest(ComputerWorkspaceOperation.RUN, ".", command = "pwd")
        )

        assertEquals("write_permission_missing", mkdir.errorCode)
        assertEquals("terminal_permission_missing", run.errorCode)
    }

    @Test
    fun `run rejects terminal not exposed by node`() = runBlocking {
        val bridge = LanWebComputerWorkspaceBridge()
        bridge.register(
            "client-a",
            registration(
                terminals = listOf(LanWebTerminalBackend("powershell7", "PowerShell 7", "7.6.3"))
            )
        ).getOrThrow()

        val response = bridge.execute(
            ComputerWorkspaceRequest(
                ComputerWorkspaceOperation.RUN,
                ".",
                command = "pwd",
                terminalId = "wsl:Ubuntu"
            )
        )

        assertEquals("terminal_unavailable", response.errorCode)
    }

    @Test
    fun `external change batch is deduplicated and acknowledged`() {
        val bridge = LanWebComputerWorkspaceBridge()
        bridge.register("client-a", registration()).getOrThrow()
        val report = LanWebWorkspaceChangeReportRequest(
            workspaceSessionId = WORKSPACE_SESSION,
            reportId = "changes-report-0001",
            changes = listOf(
                LanWebWorkspaceObservedChange("src/a.kt", "modified"),
                LanWebWorkspaceObservedChange("src/b.kt", "created")
            )
        )
        bridge.recordChanges("client-a", report).getOrThrow()
        bridge.recordChanges("client-a", report).getOrThrow()

        val batch = assertNotNull(bridge.prepareExternalChanges())
        assertEquals(1, Regex("modified file: src/a.kt").findAll(batch.attachment).count())
        assertTrue(batch.attachment.contains("created file: src/b.kt"))

        bridge.acknowledgeExternalChanges(batch)
        assertNull(bridge.prepareExternalChanges())
    }

    @Test
    fun `change report rejects traversal`() {
        val bridge = LanWebComputerWorkspaceBridge()
        bridge.register("client-a", registration()).getOrThrow()

        val result = bridge.recordChanges(
            "client-a",
            LanWebWorkspaceChangeReportRequest(
                workspaceSessionId = WORKSPACE_SESSION,
                reportId = "changes-report-0002",
                changes = listOf(LanWebWorkspaceObservedChange("../secret", "modified"))
            )
        )

        assertTrue(result.isFailure)
    }

    private fun registration(
        requestId: String = "register-a-0001",
        writable: Boolean = true,
        terminal: Boolean = false,
        terminals: List<LanWebTerminalBackend> = emptyList()
    ) = LanWebWorkspaceRegisterRequest(
        workspaceSessionId = WORKSPACE_SESSION,
        label = "Desktop Project",
        readable = true,
        writable = writable,
        terminal = terminal,
        terminals = terminals,
        requestId = requestId
    )

    private companion object {
        const val WORKSPACE_SESSION = "workspace-session-0001"
    }
}
