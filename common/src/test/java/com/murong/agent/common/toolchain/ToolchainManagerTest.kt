package com.murong.agent.common.toolchain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ToolchainManagerTest {

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
}
