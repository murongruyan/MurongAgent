package com.murong.agent.lan

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

class DesktopPlatformIdentityTest {
    @Test
    fun `legacy registrations remain Windows compatible`() {
        val json = Json { ignoreUnknownKeys = false }
        val workspace = json.decodeFromString<LanWebWorkspaceRegisterRequest>(
            """{"workspaceSessionId":"workspace-session-0001","label":"Project","requestId":"register-request-0001"}"""
        )
        val desktop = json.decodeFromString<LanWebDesktopAgentRegisterRequest>(
            """{"nodeSessionId":"node-session-0001","requestId":"desktop-register-0001"}"""
        )

        assertEquals("windows", workspace.platform)
        assertEquals("", workspace.architecture)
        assertEquals("windows", desktop.sourcePlatform)
        assertEquals("", desktop.sourceArchitecture)
    }

    @Test
    fun `workspace registration exposes Linux ARM64 identity`() {
        val bridge = LanWebComputerWorkspaceBridge()
        val descriptor = bridge.register(
            "client-a",
            LanWebWorkspaceRegisterRequest(
                workspaceSessionId = "workspace-session-0001",
                label = "Linux Project",
                platform = "linux",
                architecture = "arm64",
                requestId = "register-request-0001"
            )
        ).getOrThrow()

        assertEquals("linux", descriptor.platform)
        assertEquals("arm64", descriptor.architecture)
        assertEquals("linux", bridge.status("client-a").platform)
        assertEquals("arm64", bridge.status("client-a").architecture)
        assertEquals("Linux · ARM64", desktopPlatformLabel(descriptor.platform, descriptor.architecture))
    }

    @Test
    fun `desktop mirror preserves macOS Apple Silicon identity offline`() {
        val mirrorFile = createTempDirectory("desktop-platform-mirror").resolve("mirror.json").toFile()
        val bridge = LanWebDesktopAgentBridge(LanWebDesktopAgentMirrorStore(mirrorFile))
        bridge.register(
            "client-a",
            LanWebDesktopAgentRegisterRequest(
                nodeSessionId = "node-session-0001",
                sourcePlatform = "darwin",
                sourceArchitecture = "arm64",
                requestId = "desktop-register-0001"
            )
        ).getOrThrow()
        bridge.publishSnapshot(
            "client-a",
            LanWebDesktopAgentSnapshotRequest(
                nodeSessionId = "node-session-0001",
                sourcePlatform = "darwin",
                sourceArchitecture = "arm64",
                sequence = 1,
                generatedAt = 1_000
            )
        ).getOrThrow()

        assertEquals("macOS · Apple Silicon", desktopPlatformLabel(
            bridge.state.value.sourcePlatform,
            bridge.state.value.sourceArchitecture
        ))
        bridge.disconnect("client-a", "node-session-0001")
        assertFalse(bridge.state.value.connected)
        assertEquals("darwin", bridge.state.value.sourcePlatform)
        assertEquals("arm64", bridge.state.value.sourceArchitecture)

        val restored = LanWebDesktopAgentBridge(LanWebDesktopAgentMirrorStore(mirrorFile))
        assertEquals("darwin", restored.state.value.sourcePlatform)
        assertEquals("arm64", restored.state.value.sourceArchitecture)
    }

    @Test
    fun `snapshot identity cannot differ from registered computer`() {
        val bridge = LanWebDesktopAgentBridge()
        bridge.register(
            "client-a",
            LanWebDesktopAgentRegisterRequest(
                nodeSessionId = "node-session-0001",
                sourcePlatform = "linux",
                sourceArchitecture = "amd64",
                requestId = "desktop-register-0001"
            )
        ).getOrThrow()

        val result = bridge.publishSnapshot(
            "client-a",
            LanWebDesktopAgentSnapshotRequest(
                nodeSessionId = "node-session-0001",
                sourcePlatform = "windows",
                sourceArchitecture = "amd64",
                sequence = 1,
                generatedAt = 1_000
            )
        )

        assertTrue(result.isFailure)
    }
}
