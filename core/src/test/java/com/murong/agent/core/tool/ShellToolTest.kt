package com.murong.agent.core.tool

import com.murong.agent.common.shell.ExtensionShellExecutor
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShellToolTest {

    @Test
    fun execute_defaultsToRootSystemEnvironmentAndCurrentProjectDirectory() = runBlocking {
        var executedCommand = ""
        var executedTimeout = 0
        var extensionCalled = false
        val tool = ShellTool(
            workingDirectoryProvider = { "/sdcard/My Project" },
            rootAvailableProvider = { true },
            systemCommandExecutor = { command, timeout ->
                executedCommand = command
                executedTimeout = timeout
                "system-ok"
            },
            extensionAvailableProvider = { true },
            extensionCommandExecutor = { _, _, _ ->
                extensionCalled = true
                ExtensionShellExecutor.Result(output = "unexpected")
            }
        )

        val result = tool.executeWithResult("""{"command":"pwd","timeout":7}""")

        assertEquals("system-ok", result.output)
        assertEquals(ToolExecutionStatus.SUCCESS, result.status)
        assertEquals(true, result.success)
        assertEquals(7, executedTimeout)
        assertTrue(executedCommand.startsWith("export PATH='/system/bin:"))
        assertTrue(executedCommand.contains("unset PREFIX TERMUX__PREFIX"))
        assertTrue(executedCommand.contains("cd '/sdcard/My Project' ||"))
        assertTrue(executedCommand.endsWith("; pwd"))
        assertFalse(extensionCalled)
    }

    @Test
    fun execute_usesAppUidExtensionEnvironmentWithoutRequiringRoot() = runBlocking {
        var systemCalled = false
        var extensionCommand = ""
        var extensionTimeout = 0
        var extensionDirectory: File? = null
        val tool = ShellTool(
            workingDirectoryProvider = { "/ignored" },
            rootAvailableProvider = { false },
            systemCommandExecutor = { _, _ ->
                systemCalled = true
                "unexpected"
            },
            extensionAvailableProvider = { true },
            extensionCommandExecutor = { command, timeout, directory ->
                extensionCommand = command
                extensionTimeout = timeout
                extensionDirectory = directory
                ExtensionShellExecutor.Result(output = "Python 3.14.6\n", exitCode = 0)
            }
        )

        val result = tool.execute(
            """{"command":"python --version","environment":"extension","working_directory":"/sdcard/project","timeout":20}"""
        )

        assertEquals("Python 3.14.6", result)
        assertEquals("python --version", extensionCommand)
        assertEquals(20, extensionTimeout)
        assertEquals(File("/sdcard/project"), extensionDirectory)
        assertFalse(systemCalled)
    }

    @Test
    fun execute_reportsExtensionExitCode() = runBlocking {
        val tool = ShellTool(
            rootAvailableProvider = { false },
            extensionAvailableProvider = { true },
            extensionCommandExecutor = { _, _, _ ->
                ExtensionShellExecutor.Result(output = "missing", exitCode = 127)
            }
        )

        val result = tool.executeWithResult("""{"command":"missing","environment":"extension"}""")

        assertEquals("Command execution error (exit 127):\nmissing", result.output)
        assertEquals(ToolExecutionStatus.FAILURE, result.status)
        assertEquals(false, result.success)
        assertEquals(127, result.exitCode)
        assertFalse(result.timedOut)
        assertEquals(false, result.resolvedSuccess)
    }

    @Test
    fun executeWithResult_reportsRootSystemExitCodeFromCommandMarker() = runBlocking {
        val tool = ShellTool(
            rootAvailableProvider = { true },
            systemCommandExecutor = { _, _ ->
                "permission denied\n__RSNX_EXIT_CODE__126"
            }
        )

        val result = tool.executeWithResult("""{"command":"./script.sh","environment":"system"}""")

        assertEquals("Command execution error (exit 126):\npermission denied", result.output)
        assertEquals(ToolExecutionStatus.FAILURE, result.status)
        assertEquals(false, result.success)
        assertEquals(126, result.exitCode)
        assertFalse(result.timedOut)
    }

    @Test
    fun executeWithResult_reportsRootSystemTimeoutStructurally() = runBlocking {
        val tool = ShellTool(
            rootAvailableProvider = { true },
            systemCommandExecutor = { _, _ ->
                "partial output\n__RSNX_TIMEOUT__\n__RSNX_EXIT_CODE__143"
            }
        )

        val result = tool.executeWithResult("""{"command":"sleep 30","timeout":2}""")

        assertTrue(result.output.startsWith("partial output"))
        assertEquals(ToolExecutionStatus.TIMED_OUT, result.status)
        assertEquals(false, result.success)
        assertEquals(143, result.exitCode)
        assertTrue(result.timedOut)
        assertEquals(false, result.resolvedSuccess)
    }

    @Test
    fun execute_rejectsUnknownEnvironment() = runBlocking {
        val tool = ShellTool(
            rootAvailableProvider = { true },
            extensionAvailableProvider = { true }
        )

        val result = tool.execute("""{"command":"id","environment":"auto"}""")

        assertEquals("Error: 'environment' must be 'system' or 'extension'.", result)
    }
}
