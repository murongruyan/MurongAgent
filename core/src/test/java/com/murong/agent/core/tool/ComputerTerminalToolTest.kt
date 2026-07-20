package com.murong.agent.core.tool

import com.murong.agent.core.workspace.ComputerWorkspaceDescriptor
import com.murong.agent.core.workspace.ComputerWorkspaceGateway
import com.murong.agent.core.workspace.ComputerWorkspaceOperation
import com.murong.agent.core.workspace.ComputerWorkspaceRequest
import com.murong.agent.core.workspace.ComputerWorkspaceResponse
import com.murong.agent.core.workspace.ComputerTerminalDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class ComputerTerminalToolTest {
    @Test
    fun `terminal command always creates high risk approval and dispatches run`() = runBlocking {
        val gateway = FakeGateway(
            backends = listOf(
                ComputerTerminalDescriptor("powershell7", "PowerShell 7", "7.6.3"),
                ComputerTerminalDescriptor("wsl:Ubuntu", "WSL · Ubuntu", "WSL 2")
            )
        ).apply {
            next = ComputerWorkspaceResponse(
                success = true,
                stdout = "ok\n",
                exitCode = 0
            )
        }
        val tool = ComputerTerminalTool(gateway, allowExecute = true)
        val args =
            """{"command":"git status --short","terminal":"wsl:Ubuntu","path":"src","timeout_seconds":30}"""

        val approval = assertNotNull(tool.buildApprovalRequest(args))
        assertEquals(ApprovalRiskLevel.HIGH, approval.riskLevel)
        assertEquals("git status --short", approval.commandBoundaryValue)

        val result = tool.executeWithResult(args)
        assertTrue(result.success == true)
        assertEquals(ComputerWorkspaceOperation.RUN, gateway.lastRequest?.operation)
        assertEquals("wsl:Ubuntu", gateway.lastRequest?.terminalId)
        assertEquals("src", gateway.lastRequest?.relativePath)
        assertEquals(30_000L, gateway.lastRequest?.timeoutMillis)
        assertEquals(0, result.exitCode)
        assertTrue(result.output.contains("WSL · Ubuntu"))
    }

    @Test
    fun `terminal capability and read only mode both block dispatch`() = runBlocking {
        val noTerminal = FakeGateway(terminal = false)
        val deniedByNode = ComputerTerminalTool(noTerminal, allowExecute = true)
            .executeWithResult("""{"command":"pwd"}""")
        assertFalse(deniedByNode.success == true)
        assertTrue(deniedByNode.output.contains("没有启用"))
        assertEquals(null, noTerminal.lastRequest)

        val readOnly = FakeGateway()
        val deniedByMode = ComputerTerminalTool(readOnly, allowExecute = false)
            .executeWithResult("""{"command":"pwd"}""")
        assertFalse(deniedByMode.success == true)
        assertTrue(deniedByMode.output.contains("只读"))
        assertEquals(null, readOnly.lastRequest)
    }

    @Test
    fun `unavailable requested terminal blocks dispatch`() = runBlocking {
        val gateway = FakeGateway(
            backends = listOf(ComputerTerminalDescriptor("powershell7", "PowerShell 7", "7.6.3"))
        )

        val result = ComputerTerminalTool(gateway, allowExecute = true)
            .executeWithResult("""{"command":"pwd","terminal":"wsl:Ubuntu"}""")

        assertFalse(result.success == true)
        assertTrue(result.output.contains("未启用"))
        assertEquals(null, gateway.lastRequest)
    }

    private class FakeGateway(
        private val terminal: Boolean = true,
        private val backends: List<ComputerTerminalDescriptor> = emptyList()
    ) : ComputerWorkspaceGateway {
        var next = ComputerWorkspaceResponse(success = false, errorMessage = "not configured")
        var lastRequest: ComputerWorkspaceRequest? = null

        override fun activeWorkspace() = ComputerWorkspaceDescriptor(
            sessionId = "workspace-session",
            label = "Desktop",
            readable = true,
            writable = true,
            terminal = terminal,
            terminalBackends = backends
        )

        override suspend fun execute(request: ComputerWorkspaceRequest): ComputerWorkspaceResponse {
            lastRequest = request
            return next
        }
    }
}
