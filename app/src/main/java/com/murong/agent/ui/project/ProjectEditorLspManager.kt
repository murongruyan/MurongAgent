package com.murong.agent.ui.project

import java.io.File
import java.util.Locale

internal enum class ProjectEditorLspMode {
    LOCAL_FALLBACK,
    UNSUPPORTED
}

internal enum class ProjectEditorLspLifecycle {
    MANAGED_LOCAL_FALLBACK,
    UNMANAGED_LOCAL_ONLY
}

internal enum class ProjectEditorDiagnosticsProvider {
    LOCAL_RULES,
    LSP_CLIENT_LOCAL_FALLBACK
}

internal enum class ProjectEditorLspSessionStatus {
    MANAGED,
    LOCAL_ONLY
}

internal enum class ProjectEditorLspLiveSessionPhase {
    LOCAL_ONLY,
    REGISTERING,
    READY_LOCAL_FALLBACK,
    DETACHED
}

internal enum class ProjectEditorLspClientConnectionState {
    ATTACHED_LOCAL_FALLBACK,
    DETACHED
}

internal data class ProjectEditorLspState(
    val mode: ProjectEditorLspMode = ProjectEditorLspMode.UNSUPPORTED,
    val lifecycle: ProjectEditorLspLifecycle = ProjectEditorLspLifecycle.UNMANAGED_LOCAL_ONLY,
    val diagnosticsProvider: ProjectEditorDiagnosticsProvider = ProjectEditorDiagnosticsProvider.LOCAL_RULES,
    val statusLabel: String = "LSP 未支持",
    val providerLabel: String = "本地规则诊断",
    val statusDetail: String = "当前语言暂未纳入 LSP Manager 生命周期骨架，继续使用本地规则诊断。"
)

internal data class ProjectEditorLspSessionState(
    val status: ProjectEditorLspSessionStatus = ProjectEditorLspSessionStatus.LOCAL_ONLY,
    val sessionKey: String = "local/plaintext",
    val sessionLabel: String = "未进入托管 LSP 会话",
    val workspaceLabel: String = "当前文件未映射到 LSP workspace",
    val documentLabel: String = "当前文档未建立会话标识"
)

internal data class ProjectEditorLspLiveSessionState(
    val sessionKey: String = "local/plaintext",
    val phase: ProjectEditorLspLiveSessionPhase = ProjectEditorLspLiveSessionPhase.LOCAL_ONLY,
    val phaseLabel: String = "未接入 live session",
    val sessionLabel: String = "当前仅走本地诊断会话",
    val providerLabel: String = "本地规则诊断",
    val transportLabel: String = "local-only",
    val detail: String = "当前文件仍直接使用本地规则诊断，尚未进入托管 live session。"
)

internal data class ProjectEditorLspClientHandle(
    val sessionKey: String = "local/plaintext",
    val clientId: String = "lsp-client/local-plaintext",
    val connectionState: ProjectEditorLspClientConnectionState =
        ProjectEditorLspClientConnectionState.DETACHED,
    val connectionLabel: String = "LspClient 未附着",
    val sessionLabel: String = "当前仅走本地诊断会话",
    val providerLabel: String = "本地规则诊断",
    val transportLabel: String = "local-only",
    val detail: String = "当前没有托管 LspClient。"
)

internal data class ProjectEditorLspDiagnosticsSnapshot(
    val diagnostics: List<ProjectEditorDiagnostic> = emptyList(),
    val lspState: ProjectEditorLspState = ProjectEditorLspState(),
    val sessionState: ProjectEditorLspSessionState = ProjectEditorLspSessionState()
)

private val LSP_MANAGER_DIAGNOSTIC_LANGUAGES = setOf(
    "kotlin", "java", "groovy", "javascript", "typescript",
    "json", "python", "bash", "c", "cpp", "toml",
    "ini", "conf", "properties", "yaml", "html",
    "xml", "css", "sql", "markdown"
)

internal fun resolveProjectEditorLspState(language: String?): ProjectEditorLspState {
    val normalized = language?.trim()?.lowercase(Locale.getDefault()).orEmpty()
    return if (normalized in LSP_MANAGER_DIAGNOSTIC_LANGUAGES) {
        ProjectEditorLspState(
            mode = ProjectEditorLspMode.LOCAL_FALLBACK,
            lifecycle = ProjectEditorLspLifecycle.MANAGED_LOCAL_FALLBACK,
            diagnosticsProvider = ProjectEditorDiagnosticsProvider.LOCAL_RULES,
            statusLabel = "LSP 本地兜底",
            providerLabel = "本地规则诊断",
            statusDetail = "当前语言已纳入 LSP Manager 生命周期骨架，当前诊断源为本地规则，后续可切到真实 LSP provider。"
        )
    } else {
        ProjectEditorLspState()
    }
}

internal fun buildProjectEditorLspDiagnosticsSnapshot(
    content: String,
    language: String?,
    workspaceRoot: String? = null,
    filePath: String? = null
): ProjectEditorLspDiagnosticsSnapshot {
    val lspState = resolveProjectEditorLspState(language)
    val sessionState = resolveProjectEditorLspSessionState(
        workspaceRoot = workspaceRoot,
        filePath = filePath,
        language = language,
        lspState = lspState
    )
    val diagnostics = when (lspState.diagnosticsProvider) {
        ProjectEditorDiagnosticsProvider.LOCAL_RULES -> buildProjectEditorDiagnostics(content, language)
        ProjectEditorDiagnosticsProvider.LSP_CLIENT_LOCAL_FALLBACK -> buildProjectEditorDiagnostics(content, language)
    }
    return ProjectEditorLspDiagnosticsSnapshot(
        diagnostics = diagnostics,
        lspState = lspState,
        sessionState = sessionState
    )
}

internal fun resolveProjectEditorLspSessionState(
    workspaceRoot: String?,
    filePath: String?,
    language: String?,
    lspState: ProjectEditorLspState = resolveProjectEditorLspState(language)
): ProjectEditorLspSessionState {
    val normalizedLanguage = language?.trim()?.lowercase(Locale.getDefault()).orEmpty().ifBlank { "plaintext" }
    val normalizedWorkspace = workspaceRoot?.replace('\\', '/')?.trimEnd('/').orEmpty()
    val normalizedFilePath = filePath?.replace('\\', '/').orEmpty()
    val workspaceLabel = normalizedWorkspace.ifBlank { "当前文件未映射到 LSP workspace" }
    val documentLabel = normalizedFilePath
        .takeIf { it.isNotBlank() }
        ?.let { it.substringAfterLast('/').ifBlank { it } }
        ?: "当前文档未建立会话标识"
    return if (lspState.lifecycle == ProjectEditorLspLifecycle.MANAGED_LOCAL_FALLBACK) {
        ProjectEditorLspSessionState(
            status = ProjectEditorLspSessionStatus.MANAGED,
            sessionKey = buildManagedLspSessionKey(normalizedWorkspace, normalizedLanguage),
            sessionLabel = "已建立托管 LSP 会话骨架",
            workspaceLabel = workspaceLabel,
            documentLabel = documentLabel
        )
    } else {
        ProjectEditorLspSessionState(
            status = ProjectEditorLspSessionStatus.LOCAL_ONLY,
            sessionKey = "local/$normalizedLanguage",
            sessionLabel = "当前仅走本地诊断会话",
            workspaceLabel = workspaceLabel,
            documentLabel = documentLabel
        )
    }
}

internal fun buildProjectEditorLspSessionFacts(sessionState: ProjectEditorLspSessionState): List<String> {
    return listOf(
        "会话状态：${sessionState.sessionLabel}",
        "会话键：${sessionState.sessionKey}",
        "Workspace：${sessionState.workspaceLabel}",
        "文档：${sessionState.documentLabel}"
    )
}

internal fun resolveProjectEditorLspRuntimeState(
    baseState: ProjectEditorLspState,
    clientHandle: ProjectEditorLspClientHandle?
): ProjectEditorLspState {
    return when (clientHandle?.connectionState) {
        ProjectEditorLspClientConnectionState.ATTACHED_LOCAL_FALLBACK -> {
            baseState.copy(
                diagnosticsProvider = ProjectEditorDiagnosticsProvider.LSP_CLIENT_LOCAL_FALLBACK,
                providerLabel = "LspClient 骨架 + 本地规则兜底",
                statusDetail = "当前文档已附着托管 LspClient 骨架；真实 language server 尚未接入，诊断继续由本地规则兜底。"
            )
        }
        ProjectEditorLspClientConnectionState.DETACHED -> {
            baseState.copy(
                diagnosticsProvider = ProjectEditorDiagnosticsProvider.LOCAL_RULES,
                providerLabel = "本地规则诊断",
                statusDetail = "当前文档对应的 LspClient 骨架已分离，诊断回退为纯本地规则。"
            )
        }
        null -> baseState
    }
}

internal fun attachProjectEditorLspClientSkeleton(
    currentClients: Map<String, ProjectEditorLspClientHandle>,
    sessionState: ProjectEditorLspSessionState,
    lspState: ProjectEditorLspState
): Map<String, ProjectEditorLspClientHandle> {
    if (sessionState.status != ProjectEditorLspSessionStatus.MANAGED) {
        return currentClients
    }
    val attachedClient = ProjectEditorLspClientHandle(
        sessionKey = sessionState.sessionKey,
        clientId = buildProjectEditorLspClientId(sessionState.sessionKey),
        connectionState = ProjectEditorLspClientConnectionState.ATTACHED_LOCAL_FALLBACK,
        connectionLabel = "LspClient 已附着，本地诊断兜底",
        sessionLabel = sessionState.sessionLabel,
        providerLabel = lspState.providerLabel,
        transportLabel = "managed/local-skeleton",
        detail = "当前会话已进入 LSP Manager live session 骨架，真实 LspClient 尚未接入，先由本地规则 provider 兜底。"
    )
    return if (currentClients[sessionState.sessionKey] == attachedClient) {
        currentClients
    } else {
        currentClients + (sessionState.sessionKey to attachedClient)
    }
}

internal fun detachProjectEditorLspClientSkeleton(
    currentClients: Map<String, ProjectEditorLspClientHandle>,
    sessionKey: String
): Map<String, ProjectEditorLspClientHandle> {
    val existing = currentClients[sessionKey] ?: return currentClients
    val detachedClient = existing.copy(
        connectionState = ProjectEditorLspClientConnectionState.DETACHED,
        connectionLabel = "LspClient 已分离",
        transportLabel = "managed/detached",
        detail = "当前会话已从 LSP Manager live session 分离，若再次聚焦该文档会重新附着本地兜底 client 骨架。"
    )
    return if (detachedClient == existing) {
        currentClients
    } else {
        currentClients + (sessionKey to detachedClient)
    }
}

internal fun reconcileProjectEditorLspLiveSessions(
    currentSessions: Map<String, ProjectEditorLspClientHandle>,
    sessionState: ProjectEditorLspSessionState,
    lspState: ProjectEditorLspState
): Map<String, ProjectEditorLspClientHandle> {
    return attachProjectEditorLspClientSkeleton(
        currentClients = currentSessions,
        sessionState = sessionState,
        lspState = lspState
    )
}

internal fun resolveProjectEditorLspLiveSessionState(
    currentSessions: Map<String, ProjectEditorLspClientHandle>,
    sessionState: ProjectEditorLspSessionState,
    lspState: ProjectEditorLspState
): ProjectEditorLspLiveSessionState {
    if (sessionState.status != ProjectEditorLspSessionStatus.MANAGED) {
        return ProjectEditorLspLiveSessionState(
            sessionKey = sessionState.sessionKey,
            phase = ProjectEditorLspLiveSessionPhase.LOCAL_ONLY,
            phaseLabel = "未接入 live session",
            sessionLabel = sessionState.sessionLabel,
            providerLabel = lspState.providerLabel,
            transportLabel = "local-only",
            detail = "当前语言仍直接走本地规则诊断，没有托管 live session。"
        )
    }
    val registeredClient = currentSessions[sessionState.sessionKey]
    return when {
        registeredClient == null -> ProjectEditorLspLiveSessionState(
            sessionKey = sessionState.sessionKey,
            phase = ProjectEditorLspLiveSessionPhase.REGISTERING,
            phaseLabel = "托管 live session 待注册",
            sessionLabel = sessionState.sessionLabel,
            providerLabel = lspState.providerLabel,
            transportLabel = "managed/pending",
            detail = "当前文档已进入托管会话范围，正在等待 LSP Manager 注册 live session。"
        )
        registeredClient.connectionState == ProjectEditorLspClientConnectionState.DETACHED -> {
            ProjectEditorLspLiveSessionState(
                sessionKey = sessionState.sessionKey,
                phase = ProjectEditorLspLiveSessionPhase.DETACHED,
                phaseLabel = "托管 live session 已分离",
                sessionLabel = registeredClient.sessionLabel,
                providerLabel = registeredClient.providerLabel,
                transportLabel = registeredClient.transportLabel,
                detail = registeredClient.detail
            )
        }
        else -> ProjectEditorLspLiveSessionState(
            sessionKey = sessionState.sessionKey,
            phase = ProjectEditorLspLiveSessionPhase.READY_LOCAL_FALLBACK,
            phaseLabel = "托管 live session 已注册",
            sessionLabel = registeredClient.sessionLabel,
            providerLabel = registeredClient.providerLabel,
            transportLabel = registeredClient.transportLabel,
            detail = registeredClient.detail
        )
    }
}

internal fun buildProjectEditorLspLiveSessionFacts(
    liveSessionState: ProjectEditorLspLiveSessionState
): List<String> {
    return listOf(
        "Live Session：${liveSessionState.phaseLabel}",
        "Live Provider：${liveSessionState.providerLabel}",
        "Live Transport：${liveSessionState.transportLabel}"
    )
}

internal fun buildProjectEditorLspClientFacts(clientHandle: ProjectEditorLspClientHandle?): List<String> {
    if (clientHandle == null) return emptyList()
    return listOf(
        "LspClient：${clientHandle.connectionLabel}",
        "Client Id：${clientHandle.clientId}"
    )
}

internal fun buildProjectEditorChromeSubtitle(
    relativePath: String,
    lspState: ProjectEditorLspState
): String {
    return listOf(relativePath.takeIf { it.isNotBlank() }, lspState.statusLabel)
        .joinToString(" · ")
}

internal fun buildProjectEditorDiagnosticsDialogSubtitle(
    diagnosticsCount: Int,
    lspState: ProjectEditorLspState
): String {
    return "共 $diagnosticsCount 条诊断结果 · ${lspState.statusLabel} · ${lspState.providerLabel}"
}

private fun buildManagedLspSessionKey(workspaceRoot: String, language: String): String {
    val workspaceSegment = workspaceRoot
        .ifBlank { "workspace/unknown" }
        .replace(':', '_')
        .replace('/', '|')
    return "managed/$workspaceSegment/$language"
}

private fun buildProjectEditorLspClientId(sessionKey: String): String {
    return "lsp-client/${sessionKey.replace('/', '-').replace('|', '-').replace(':', '-')}"
}
