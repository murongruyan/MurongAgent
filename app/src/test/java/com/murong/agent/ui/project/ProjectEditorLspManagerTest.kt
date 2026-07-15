package com.murong.agent.ui.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProjectEditorLspManagerTest {

    @Test
    fun resolveProjectEditorLspState_marksSupportedLanguageAsLocalFallback() {
        val state = resolveProjectEditorLspState("kotlin")

        assertEquals(ProjectEditorLspMode.LOCAL_FALLBACK, state.mode)
        assertEquals(ProjectEditorLspLifecycle.MANAGED_LOCAL_FALLBACK, state.lifecycle)
        assertEquals(ProjectEditorDiagnosticsProvider.LOCAL_RULES, state.diagnosticsProvider)
        assertEquals("LSP 本地兜底", state.statusLabel)
        assertEquals("本地规则诊断", state.providerLabel)
        assertTrue(state.statusDetail.contains("真实 LSP provider"))
    }

    @Test
    fun resolveProjectEditorLspState_marksUnknownLanguageAsUnsupported() {
        val state = resolveProjectEditorLspState("plaintext")

        assertEquals(ProjectEditorLspMode.UNSUPPORTED, state.mode)
        assertEquals(ProjectEditorLspLifecycle.UNMANAGED_LOCAL_ONLY, state.lifecycle)
        assertEquals(ProjectEditorDiagnosticsProvider.LOCAL_RULES, state.diagnosticsProvider)
        assertEquals("LSP 未支持", state.statusLabel)
        assertEquals("本地规则诊断", state.providerLabel)
    }

    @Test
    fun buildProjectEditorLspDiagnosticsSnapshot_routesToLocalProviderForManagedLanguage() {
        val snapshot = buildProjectEditorLspDiagnosticsSnapshot(
            content = "name value",
            language = "yaml",
            workspaceRoot = "C:/workspace/demo",
            filePath = "C:/workspace/demo/app.yaml"
        )

        assertEquals(ProjectEditorLspMode.LOCAL_FALLBACK, snapshot.lspState.mode)
        assertEquals(ProjectEditorLspSessionStatus.MANAGED, snapshot.sessionState.status)
        assertTrue(snapshot.sessionState.sessionKey.startsWith("managed/"))
        assertTrue(snapshot.diagnostics.isNotEmpty())
    }

    @Test
    fun buildProjectEditorLspDiagnosticsSnapshot_keepsLocalDiagnosticsForUnsupportedLanguage() {
        val snapshot = buildProjectEditorLspDiagnosticsSnapshot(
            content = "<<<<<<< ours\nvalue\n=======\nother\n>>>>>>> theirs",
            language = "plaintext",
            workspaceRoot = "C:/workspace/demo",
            filePath = "C:/workspace/demo/notes.txt"
        )

        assertEquals(ProjectEditorLspMode.UNSUPPORTED, snapshot.lspState.mode)
        assertEquals(ProjectEditorLspSessionStatus.LOCAL_ONLY, snapshot.sessionState.status)
        assertEquals(3, snapshot.diagnostics.size)
    }

    @Test
    fun resolveProjectEditorLspSessionState_buildsManagedWorkspaceScopedKey() {
        val sessionState = resolveProjectEditorLspSessionState(
            workspaceRoot = "C:/workspace/demo",
            filePath = "C:/workspace/demo/src/MainActivity.kt",
            language = "kotlin"
        )

        assertEquals(ProjectEditorLspSessionStatus.MANAGED, sessionState.status)
        assertEquals("已建立托管 LSP 会话骨架", sessionState.sessionLabel)
        assertTrue(sessionState.sessionKey.contains("kotlin"))
        assertEquals("MainActivity.kt", sessionState.documentLabel)
    }

    @Test
    fun buildProjectEditorLspSessionFacts_includesSessionMetadata() {
        val facts = buildProjectEditorLspSessionFacts(
            resolveProjectEditorLspSessionState(
                workspaceRoot = "C:/workspace/demo",
                filePath = "C:/workspace/demo/src/MainActivity.kt",
                language = "kotlin"
            )
        )

        assertEquals(4, facts.size)
        assertTrue(facts.any { it.contains("会话键") })
        assertTrue(facts.any { it.contains("Workspace") })
    }

    @Test
    fun resolveProjectEditorLspLiveSessionState_marksManagedSessionAsRegisteringBeforeRegistryUpdate() {
        val sessionState = resolveProjectEditorLspSessionState(
            workspaceRoot = "C:/workspace/demo",
            filePath = "C:/workspace/demo/src/MainActivity.kt",
            language = "kotlin"
        )
        val liveState = resolveProjectEditorLspLiveSessionState(
            currentSessions = emptyMap(),
            sessionState = sessionState,
            lspState = resolveProjectEditorLspState("kotlin")
        )

        assertEquals(ProjectEditorLspLiveSessionPhase.REGISTERING, liveState.phase)
        assertEquals("managed/pending", liveState.transportLabel)
    }

    @Test
    fun reconcileProjectEditorLspLiveSessions_registersManagedFallbackSession() {
        val sessionState = resolveProjectEditorLspSessionState(
            workspaceRoot = "C:/workspace/demo",
            filePath = "C:/workspace/demo/src/MainActivity.kt",
            language = "kotlin"
        )
        val sessions = reconcileProjectEditorLspLiveSessions(
            currentSessions = emptyMap(),
            sessionState = sessionState,
            lspState = resolveProjectEditorLspState("kotlin")
        )
        val liveState = resolveProjectEditorLspLiveSessionState(
            currentSessions = sessions,
            sessionState = sessionState,
            lspState = resolveProjectEditorLspState("kotlin")
        )

        assertEquals(ProjectEditorLspLiveSessionPhase.READY_LOCAL_FALLBACK, liveState.phase)
        assertEquals("managed/local-skeleton", liveState.transportLabel)
        assertTrue(liveState.detail.contains("LspClient"))
    }

    @Test
    fun attachProjectEditorLspClientSkeleton_registersAttachedClientHandle() {
        val sessionState = resolveProjectEditorLspSessionState(
            workspaceRoot = "C:/workspace/demo",
            filePath = "C:/workspace/demo/src/MainActivity.kt",
            language = "kotlin"
        )
        val clients = attachProjectEditorLspClientSkeleton(
            currentClients = emptyMap(),
            sessionState = sessionState,
            lspState = resolveProjectEditorLspState("kotlin")
        )
        val client = clients[sessionState.sessionKey]

        assertEquals(ProjectEditorLspClientConnectionState.ATTACHED_LOCAL_FALLBACK, client?.connectionState)
        assertTrue(client?.clientId?.startsWith("lsp-client/") == true)
    }

    @Test
    fun detachProjectEditorLspClientSkeleton_marksExistingClientAsDetached() {
        val sessionState = resolveProjectEditorLspSessionState(
            workspaceRoot = "C:/workspace/demo",
            filePath = "C:/workspace/demo/src/MainActivity.kt",
            language = "kotlin"
        )
        val attached = attachProjectEditorLspClientSkeleton(
            currentClients = emptyMap(),
            sessionState = sessionState,
            lspState = resolveProjectEditorLspState("kotlin")
        )
        val detached = detachProjectEditorLspClientSkeleton(
            currentClients = attached,
            sessionKey = sessionState.sessionKey
        )
        val client = detached[sessionState.sessionKey]
        val liveState = resolveProjectEditorLspLiveSessionState(
            currentSessions = detached,
            sessionState = sessionState,
            lspState = resolveProjectEditorLspState("kotlin")
        )

        assertEquals(ProjectEditorLspClientConnectionState.DETACHED, client?.connectionState)
        assertEquals(ProjectEditorLspLiveSessionPhase.DETACHED, liveState.phase)
        assertEquals("managed/detached", liveState.transportLabel)
    }

    @Test
    fun buildProjectEditorLspLiveSessionFacts_includesPhaseAndTransport() {
        val facts = buildProjectEditorLspLiveSessionFacts(
            ProjectEditorLspLiveSessionState(
                phase = ProjectEditorLspLiveSessionPhase.READY_LOCAL_FALLBACK,
                phaseLabel = "托管 live session 已注册",
                providerLabel = "本地规则诊断",
                transportLabel = "managed/local-skeleton"
            )
        )

        assertEquals(3, facts.size)
        assertTrue(facts.any { it.contains("Live Session") })
        assertTrue(facts.any { it.contains("managed/local-skeleton") })
    }

    @Test
    fun buildProjectEditorLspClientFacts_includesClientIdAndConnectionLabel() {
        val facts = buildProjectEditorLspClientFacts(
            ProjectEditorLspClientHandle(
                clientId = "lsp-client/demo",
                connectionLabel = "LspClient 已附着，本地诊断兜底"
            )
        )

        assertEquals(2, facts.size)
        assertTrue(facts.any { it.contains("LspClient") })
        assertTrue(facts.any { it.contains("lsp-client/demo") })
    }

    @Test
    fun resolveProjectEditorLspRuntimeState_switchesProviderWhenClientIsAttached() {
        val runtimeState = resolveProjectEditorLspRuntimeState(
            baseState = resolveProjectEditorLspState("kotlin"),
            clientHandle = ProjectEditorLspClientHandle(
                connectionState = ProjectEditorLspClientConnectionState.ATTACHED_LOCAL_FALLBACK
            )
        )

        assertEquals(ProjectEditorDiagnosticsProvider.LSP_CLIENT_LOCAL_FALLBACK, runtimeState.diagnosticsProvider)
        assertEquals("LspClient 骨架 + 本地规则兜底", runtimeState.providerLabel)
    }

    @Test
    fun resolveProjectEditorLspRuntimeState_fallsBackToLocalRulesWhenClientIsDetached() {
        val runtimeState = resolveProjectEditorLspRuntimeState(
            baseState = resolveProjectEditorLspState("kotlin"),
            clientHandle = ProjectEditorLspClientHandle(
                connectionState = ProjectEditorLspClientConnectionState.DETACHED
            )
        )

        assertEquals(ProjectEditorDiagnosticsProvider.LOCAL_RULES, runtimeState.diagnosticsProvider)
        assertTrue(runtimeState.statusDetail.contains("已分离"))
    }


    @Test
    fun buildProjectEditorChromeSubtitle_appendsLspStatusLabel() {
        val subtitle = buildProjectEditorChromeSubtitle(
            relativePath = "app/src/main/MainActivity.kt",
            lspState = resolveProjectEditorLspState("kotlin")
        )

        assertEquals("app/src/main/MainActivity.kt · LSP 本地兜底", subtitle)
    }

    @Test
    fun buildProjectEditorDiagnosticsDialogSubtitle_includesLspStatusLabel() {
        val subtitle = buildProjectEditorDiagnosticsDialogSubtitle(
            diagnosticsCount = 3,
            lspState = resolveProjectEditorLspState("yaml")
        )

        assertEquals("共 3 条诊断结果 · LSP 本地兜底 · 本地规则诊断", subtitle)
    }
}
