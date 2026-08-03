package com.murong.agent.common.toolchain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ToolchainManagerTest {
    @Test
    fun `native extension command resolves only a safe manifest target`() {
        val nativeRoot = File("build/test-native").absoluteFile
        assertEquals(
            File(nativeRoot, "libmurong_ext_safe.so"),
            ToolchainManager.resolveNativeCommandFile(
                commands = mapOf("codex-app-server" to "native/libmurong_ext_safe.so"),
                commandName = "codex-app-server",
                nativeLibraryDir = nativeRoot,
            ),
        )
        assertNull(
            ToolchainManager.resolveNativeCommandFile(
                commands = mapOf("codex-app-server" to "native/../outside.so"),
                commandName = "codex-app-server",
                nativeLibraryDir = nativeRoot,
            ),
        )
        assertNull(
            ToolchainManager.resolveNativeCommandFile(
                commands = mapOf("codex-app-server" to "bin/codex-app-server"),
                commandName = "codex-app-server",
                nativeLibraryDir = nativeRoot,
            ),
        )
    }


    @Test
    fun installFingerprint_changesWhenExtensionApkVersionChanges() {
        val previous = ToolchainManager.buildInstallFingerprint(
            packageName = "cc.rl1.murong.terminalextension",
            toolchainVersion = "termux-curated-v7",
            packageVersionCode = 26072801
        )
        val updated = ToolchainManager.buildInstallFingerprint(
            packageName = "cc.rl1.murong.terminalextension",
            toolchainVersion = "termux-curated-v7",
            packageVersionCode = 26072802
        )

        assertNotEquals(previous, updated)
        assertTrue(updated.contains("apk-26072802"))
    }

    @Test
    fun mutablePackageManagerScaffold_isNotARuntimeRequirement() {
        assertTrue(
            ToolchainManager.isMutablePackageManagerScaffoldPath(
                "var/lib/apt/lists/partial/.murong-keep"
            )
        )
        assertTrue(
            ToolchainManager.isMutablePackageManagerScaffoldPath(
                "var/cache/apt/archives/partial/.murong-keep"
            )
        )
        assertFalse(
            ToolchainManager.isMutablePackageManagerScaffoldPath(
                "var/lib/dpkg/status"
            )
        )
    }

    @Test
    fun mutablePackageManagerState_survivesExtensionBaseUpdates() {
        assertTrue(ToolchainManager.isMutablePackageManagerStatePath("var/lib/dpkg/status"))
        assertTrue(ToolchainManager.isMutablePackageManagerStatePath("var/lib/dpkg/info/python.list"))
        assertTrue(ToolchainManager.isMutablePackageManagerStatePath("var/cache/apt/archives/python.deb"))
        assertTrue(ToolchainManager.isMutablePackageManagerStatePath("etc/apt/sources.list"))
        assertFalse(ToolchainManager.isMutablePackageManagerStatePath("bin/python3.14"))
    }

    @Test
    fun isRuntimeToolchainLinkTarget_acceptsRelativeTarget() {
        assertTrue(ToolchainManager.isRuntimeToolchainLinkTarget("../../share/termux-keyring/key.gpg"))
    }

    @Test
    fun isRuntimeToolchainLinkTarget_rejectsAbsoluteTarget() {
        assertFalse(
            ToolchainManager.isRuntimeToolchainLinkTarget(
                "/data/data/com.termux/files/usr/share/termux-keyring/key.gpg"
            )
        )
    }

    @Test
    fun isSafeToolchainCommandName_acceptsPosixTestCommand() {
        assertTrue(ToolchainManager.isSafeToolchainCommandName("["))
    }

    @Test
    fun isSafeToolchainCommandName_rejectsPathTraversal() {
        assertFalse(ToolchainManager.isSafeToolchainCommandName("../../bin/bash"))
    }

    @Test
    fun shouldCreateCommandLink_keepsScriptAtItsOwnEntryPath() {
        assertFalse(
            ToolchainManager.shouldCreateCommandLink(
                "/toolchain/bin/bzdiff",
                "/toolchain/bin/bzdiff"
            )
        )
    }

    @Test
    fun shouldCreateCommandLink_linksNativeCommandIntoBinDirectory() {
        assertTrue(
            ToolchainManager.shouldCreateCommandLink(
                "/toolchain/bin/bash",
                "/data/app/extension/lib/arm64/libmurong_ext_bash.so"
            )
        )
    }

    @Test
    fun shouldReplaceCommandEntry_preservesPackageManagerInstalledFile() {
        assertFalse(
            ToolchainManager.shouldReplaceCommandEntry(
                entryExists = true,
                entryResolves = true,
                existingTarget = null,
                expectedTarget = "/extension/libcommand.so"
            )
        )
    }

    @Test
    fun shouldReplaceCommandEntry_repairsBrokenLink() {
        assertTrue(
            ToolchainManager.shouldReplaceCommandEntry(
                entryExists = true,
                entryResolves = false,
                existingTarget = "/removed/libcommand.so",
                expectedTarget = "/extension/libcommand.so"
            )
        )
    }

    @Test
    fun packageCompatibilityBindArguments_mapsTermuxRuntimePaths() {
        val arguments = ToolchainManager.packageCompatibilityBindArguments(
            rootDir = File("toolchain"),
            homeDir = File("terminal-home"),
            cacheDir = File("cache")
        )

        assertTrue(arguments.any { it.endsWith(":/data/data/com.termux/files/usr") })
        assertTrue(arguments.any { it.endsWith(":/data/data/com.termux/files/home") })
        assertTrue(arguments.any { it.endsWith(":/data/data/com.termux/cache") })
    }

    @Test
    fun translatePackageCompatibleCommand_mapsExecutableIntoGuestPrefix() {
        val root = File("toolchain").absoluteFile

        val command = ToolchainManager.translatePackageCompatibleCommand(
            listOf(File(root, "bin/python").absolutePath, "--version"),
            root
        )

        assertEquals("/data/data/com.termux/files/usr/bin/python", command.first())
        assertEquals("--version", command.last())
    }

    @Test
    fun packageCompatibilityGuestLauncher_usesSystemLinkerForBash() {
        val command = ToolchainManager.packageCompatibilityGuestLauncher(
            listOf("/data/data/com.termux/files/usr/bin/bash", "-i")
        )

        assertEquals("/system/bin/linker64", command.first())
        assertEquals("/data/data/com.termux/files/usr/bin/bash", command[1])
        assertEquals("-i", command.last())
    }

    @Test
    fun packageCompatibilityGuestLauncher_nonBashCommand_usesVersionedSymlinkFallback() {
        val command = ToolchainManager.packageCompatibilityGuestLauncher(
            listOf("python", "1.py")
        )

        assertEquals("/system/bin/linker64", command.first())
        assertEquals("/data/data/com.termux/files/usr/bin/bash", command[1])
        assertEquals("-c", command[2])
        assertTrue(command[3].contains("command_not_found_handle"))
        assertTrue(command[3].contains("__murong_resolve_command"))
        assertTrue(command[3].contains("exec \"${'$'}__murong_resolved\""))
        assertEquals("murong-package-launcher", command[4])
        assertEquals("python", command[5])
    }

    @Test
    fun buildVersionedCommandFallbackScript_installsCommandNotFoundHandler() {
        val script = ToolchainManager.buildVersionedCommandFallbackScript()

        assertTrue(script.contains("murong_exec_versioned_command"))
        assertTrue(script.contains("command_not_found_handle"))
        assertTrue(script.contains("PREFIX/bin"))
    }
}
