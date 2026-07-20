package com.murong.agent.core.tool

import com.murong.agent.core.workspace.ComputerWorkspaceDescriptor
import com.murong.agent.core.workspace.ComputerWorkspaceEntry
import com.murong.agent.core.workspace.ComputerWorkspaceGateway
import com.murong.agent.core.workspace.ComputerWorkspaceOperation
import com.murong.agent.core.workspace.ComputerWorkspaceRequest
import com.murong.agent.core.workspace.ComputerWorkspaceResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class ComputerWorkspaceToolTest {
    @Test
    fun `relative path boundary rejects traversal absolute and Windows forms`() {
        listOf(
            "../secret.txt",
            "src/../secret.txt",
            "/etc/passwd",
            "C:/Windows/win.ini",
            "src\\main.kt",
            "src//main.kt",
            " src/main.kt"
        ).forEach { path ->
            assertTrue(normalizeComputerWorkspaceRelativePath(path, allowRoot = false).isFailure, path)
        }
        assertEquals(
            "src/main.kt",
            normalizeComputerWorkspaceRelativePath("src/main.kt", allowRoot = false).getOrThrow()
        )
        assertEquals(".", normalizeComputerWorkspaceRelativePath(".", allowRoot = true).getOrThrow())
        assertTrue(normalizeComputerWorkspaceRelativePath(".", allowRoot = false).isFailure)
    }

    @Test
    fun `read returns content and hash without approval`() = runBlocking {
        val gateway = FakeGateway().apply {
            next = ComputerWorkspaceResponse(
                success = true,
                content = "hello",
                sha256 = "a".repeat(64),
                size = 5
            )
        }
        val tool = ComputerWorkspaceTool(gateway, allowWrite = true)
        val args = """{"operation":"read","path":"README.md"}"""

        assertNull(tool.buildApprovalRequest(args))
        val result = tool.executeWithResult(args)

        assertTrue(result.success == true)
        assertTrue(result.output.contains("hello"))
        assertTrue(result.output.contains("a".repeat(64)))
        assertEquals("README.md", gateway.lastRequest?.relativePath)
    }

    @Test
    fun `write requires high risk approval and preserves structured file change`() = runBlocking {
        val gateway = FakeGateway().apply {
            next = ComputerWorkspaceResponse(
                success = true,
                sha256 = "b".repeat(64),
                diffPreview = "- old\n+ new"
            )
        }
        val tool = ComputerWorkspaceTool(gateway, allowWrite = true)
        val args = """{"operation":"write","path":"src/a.kt","content":"new","expected_sha256":"${"a".repeat(64)}"}"""

        val approval = assertNotNull(tool.buildApprovalRequest(args))
        assertEquals(ApprovalRiskLevel.HIGH, approval.riskLevel)
        assertTrue(approval.pathBoundaryValue.orEmpty().endsWith(":src/a.kt"))

        val result = tool.executeWithResult(args)
        assertTrue(result.success == true)
        assertEquals(1, result.fileChanges.size)
        assertEquals("computer://Desktop/src/a.kt", result.fileChanges.single().path)
        assertEquals("- old\n+ new", result.fileChanges.single().diffPreview)
    }

    @Test
    fun `mkdir requires high risk approval and records directory change`() = runBlocking {
        val gateway = FakeGateway().apply {
            next = ComputerWorkspaceResponse(success = true, directory = true, created = true)
        }
        val tool = ComputerWorkspaceTool(gateway, allowWrite = true)
        val args = """{"operation":"mkdir","path":"src/generated"}"""

        assertEquals(ApprovalRiskLevel.HIGH, assertNotNull(tool.buildApprovalRequest(args)).riskLevel)
        val result = tool.executeWithResult(args)

        assertTrue(result.success == true)
        assertEquals(ComputerWorkspaceOperation.MKDIR, gateway.lastRequest?.operation)
        assertEquals("mkdir", result.fileChanges.single().operation)
    }

    @Test
    fun `read only turn rejects write before gateway dispatch`() = runBlocking {
        val gateway = FakeGateway()
        val tool = ComputerWorkspaceTool(gateway, allowWrite = false)

        val result = tool.executeWithResult(
            """{"operation":"write","path":"a.txt","content":"new"}"""
        )

        assertFalse(result.success == true)
        assertTrue(result.output.contains("只读"))
        assertNull(gateway.lastRequest)
    }

    @Test
    fun `list formats relative entries`() = runBlocking {
        val gateway = FakeGateway().apply {
            next = ComputerWorkspaceResponse(
                success = true,
                entries = listOf(
                    ComputerWorkspaceEntry("src", "src", directory = true),
                    ComputerWorkspaceEntry("README.md", "README.md", directory = false, size = 12)
                )
            )
        }
        val result = ComputerWorkspaceTool(gateway, allowWrite = true).executeWithResult(
            """{"operation":"list","path":"."}"""
        )

        assertTrue(result.output.contains("dir \tsrc"))
        assertTrue(result.output.contains("file\tREADME.md"))
    }

    private class FakeGateway : ComputerWorkspaceGateway {
        var next = ComputerWorkspaceResponse(success = false, errorMessage = "not configured")
        var lastRequest: ComputerWorkspaceRequest? = null

        override fun activeWorkspace() = ComputerWorkspaceDescriptor(
            sessionId = "workspace-session",
            label = "Desktop",
            readable = true,
            writable = true
        )

        override suspend fun execute(request: ComputerWorkspaceRequest): ComputerWorkspaceResponse {
            lastRequest = request
            return next
        }
    }
}
