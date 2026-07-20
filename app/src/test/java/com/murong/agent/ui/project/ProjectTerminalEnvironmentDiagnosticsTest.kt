package com.murong.agent.ui.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProjectTerminalEnvironmentDiagnosticsTest {

    @Test
    fun parser_readsOnlyTheMatchingCompleteDiagnosticBlock() {
        val runId = "diagnostic-run"
        val diagnostic = parseProjectTerminalEnvironmentDiagnosticTranscript(
            transcript = """
                prompt output
                __MURONG_ENV_DIAG_START__old-run
                command|python|missing|无法解析
                __MURONG_ENV_DIAG_END__old-run
                __MURONG_ENV_DIAG_START__diagnostic-run
                meta|environment|ok|扩展包环境
                meta|version|ok|1.4
                meta|shell|ok|/data/user/0/com.murong.agent/files/toolchain/arm64-v8a/bin/bash
                meta|path|ok|/data/data/com.termux/files/usr/bin:/system/bin
                meta|prefix|ok|/data/data/com.termux/files/usr
                meta|architecture|ok|aarch64
                meta|working-directory|ok|/storage/emulated/0/备份
                command|python|ok|由当前 shell 解析
                command|apt|ok|/data/data/com.termux/files/usr/bin/apt
                package|apt-get-check|warn|检测到未满足依赖
                storage|shared-storage|ok|/storage/emulated/0 可读取
                __MURONG_ENV_DIAG_END__diagnostic-run
            """.trimIndent(),
            runId = runId
        )

        assertNotNull(diagnostic)
        assertEquals("扩展包环境", diagnostic.environment)
        assertEquals("1.4", diagnostic.environmentVersion)
        assertEquals("aarch64", diagnostic.architecture)
        assertEquals("/storage/emulated/0/备份", diagnostic.workingDirectory)
        assertEquals("/data/data/com.termux/files/usr", diagnostic.prefix)
        assertEquals(4, diagnostic.checks.size)
        assertEquals(ProjectTerminalDiagnosticLevel.WARNING, diagnostic.checks[2].level)
        assertEquals("检查完成，有 1 项需要留意", diagnostic.headline)
        assertTrue(diagnostic.toClipboardText().contains("软件包依赖"))
    }

    @Test
    fun parser_requiresTheEndMarkerBeforeReturningAResult() {
        val diagnostic = parseProjectTerminalEnvironmentDiagnosticTranscript(
            transcript = """
                __MURONG_ENV_DIAG_START__pending-run
                meta|environment|ok|系统环境
                command|sh|ok|/system/bin/sh
            """.trimIndent(),
            runId = "pending-run"
        )

        assertNull(diagnostic)
    }

    @Test
    fun parser_marksMissingCommandsAsErrorsAndStorageAsWarnings() {
        val diagnostic = parseProjectTerminalEnvironmentDiagnosticTranscript(
            transcript = """
                __MURONG_ENV_DIAG_START__failure-run
                meta|environment|ok|系统环境
                command|python|missing|无法解析
                storage|shared-storage|warn|无法读取 /storage/emulated/0，请检查存储权限
                __MURONG_ENV_DIAG_END__failure-run
            """.trimIndent(),
            runId = "failure-run"
        )

        assertNotNull(diagnostic)
        assertEquals(1, diagnostic.errorCount)
        assertEquals(1, diagnostic.warningCount)
        assertEquals("发现 1 项不可用", diagnostic.headline)
        assertTrue(diagnostic.toClipboardText().contains("[不可用] 命令 python"))
    }

    @Test
    fun unavailableDiagnostic_keepsTheFailureReasonCopyable() {
        val diagnostic = projectTerminalEnvironmentDiagnosticUnavailable(
            environmentMode = ProjectTerminalEnvironmentMode.TOOLCHAIN,
            message = "9 秒内没有收到完整结果"
        )

        assertFalse(diagnostic.isHealthy)
        assertTrue(diagnostic.headline.contains("9 秒内没有收到完整结果"))
        assertTrue(diagnostic.toClipboardText().contains("结果: 9 秒内没有收到完整结果"))
    }

    @Test
    fun command_isReadOnlyAndChecksPackageDependencyHealth() {
        val command = buildProjectTerminalEnvironmentDiagnosticCommand(
            runId = "safe-run",
            environmentMode = ProjectTerminalEnvironmentMode.TOOLCHAIN
        )

        assertTrue(command.contains("apt-get check"))
        assertTrue(command.contains("command -v"))
        assertTrue(command.contains("/storage/emulated/0"))
        assertTrue(command.contains("meta version"))
        assertTrue(command.contains("meta architecture"))
        assertFalse(command.contains("pkg install"))
        assertFalse(command.contains("apt install"))
        assertFalse(command.contains("apt upgrade"))
    }
}
