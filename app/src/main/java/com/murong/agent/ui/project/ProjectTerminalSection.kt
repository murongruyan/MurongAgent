package com.murong.agent.ui.project

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.murong.agent.common.shell.KeepShellPublic
import com.murong.agent.common.toolchain.ToolchainManager
import com.murong.agent.ui.MurongGlassSurface
import com.murong.agent.ui.MurongProjectInsetCardShape
import com.murong.agent.ui.MurongProjectSectionCardShape
import com.murong.agent.ui.MurongTransientMessageBus
import com.murong.agent.ui.rememberMurongMutedTextColor
import com.murong.agent.ui.rememberMurongSurfaceColor
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

private const val PROJECT_TERMINAL_EXIT_MARKER = "__MURONG_EXIT_CODE__:"
private const val PROJECT_TERMINAL_LOG_LIMIT_PER_SESSION = 20
private const val PROJECT_TERMINAL_LOG_LIMIT_TOTAL = 120

private enum class ProjectTerminalTemplateCategory(val label: String) {
    BASIC("基础"),
    SEARCH("搜索"),
    GIT("Git"),
    BUILD("构建")
}

private enum class ProjectTerminalUtilityTab(val label: String) {
    COMMANDS("命令"),
    LOGS("日志"),
    SESSIONS("会话"),
    SETTINGS("设置")
}

private data class ProjectTerminalTemplateUi(
    val label: String,
    val command: String,
    val category: ProjectTerminalTemplateCategory
)

private data class ProjectTerminalLogEntryUi(
    val id: String = UUID.randomUUID().toString(),
    val commandId: String = id,
    val sessionId: String = "",
    val command: String,
    val workingDirectory: String,
    val output: String,
    val exitCode: Int?,
    val timestampMillis: Long,
    val startedAtMillis: Long = timestampMillis,
    val finishedAtMillis: Long? = timestampMillis,
    val commandStatus: ProjectTerminalCommandStatus = ProjectTerminalCommandStatus.COMPLETED,
    val environmentModeName: String? = null,
    val transcriptReference: String? = null
) {
    val statusLabel: String
        get() = when (exitCode) {
            null if commandStatus == ProjectTerminalCommandStatus.INTERRUPTED -> "已中断"
            null -> "执行完成"
            0 -> "退出码 0"
            else -> "退出码 $exitCode"
        }
}

private enum class ProjectTerminalLogStatusFilter(val label: String) {
    ALL("全部"),
    SUCCESS("成功"),
    FAILED("失败"),
    UNKNOWN("无退出码");

    fun matches(entry: ProjectTerminalLogEntryUi): Boolean {
        return when (this) {
            ALL -> true
            SUCCESS -> entry.exitCode == 0
            FAILED -> entry.exitCode != null && entry.exitCode != 0
            UNKNOWN -> entry.exitCode == null
        }
    }
}

private enum class ProjectTerminalTimeFilter(val label: String) {
    ALL("全部时间"),
    LAST_HOUR("近 1 小时"),
    TODAY("今天"),
    LAST_3_DAYS("近 3 天"),
    LAST_7_DAYS("近 7 天");

    fun matches(timestampMillis: Long, nowMillis: Long = System.currentTimeMillis()): Boolean {
        return when (this) {
            ALL -> true
            LAST_HOUR -> timestampMillis >= nowMillis - 60 * 60 * 1000L
            TODAY -> timestampMillis >= projectTerminalStartOfDayMillis(nowMillis)
            LAST_3_DAYS -> timestampMillis >= nowMillis - 3 * 24 * 60 * 60 * 1000L
            LAST_7_DAYS -> timestampMillis >= nowMillis - 7 * 24 * 60 * 60 * 1000L
        }
    }
}

private data class ProjectTerminalRecentCommandUi(
    val sessionId: String,
    val command: String
)

private data class ProjectTerminalQuickKeyUi(
    val label: String,
    val onClick: (ProjectTerminalSessionController) -> Unit
)

private data class ProjectTerminalClosedSessionSummaryUi(
    val id: String = UUID.randomUUID().toString(),
    val sessionLabel: String,
    val workingDirectory: String,
    val summary: String,
    val logCount: Int = 0,
    val lastCommand: String? = null,
    val lastLogPreview: String? = null,
    val closedAtMillis: Long
)

private data class ProjectTerminalSessionTabUi(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val workingDirectory: String,
    val environmentModeName: String = ProjectTerminalEnvironmentMode.TOOLCHAIN.name,
    val sessionKey: Int = 0
)

private data class ProjectTerminalUiSettings(
    val themeName: String = ProjectTerminalThemePreset.MIDNIGHT.name,
    val fontSize: Int = 18,
    val environmentModeName: String = ProjectTerminalEnvironmentMode.TOOLCHAIN.name,
    val settingsExpanded: Boolean = true,
    val floatingModeEnabled: Boolean = false,
    val floatingWindowRatio: Float = 0.35f
)

@Serializable
private data class PersistedProjectTerminalSessionTab(
    val id: String,
    val label: String,
    val workingDirectory: String,
    val environmentModeName: String = ProjectTerminalEnvironmentMode.TOOLCHAIN.name
)

@Serializable
private data class PersistedProjectTerminalLogEntry(
    val id: String,
    val commandId: String = "",
    val sessionId: String = "",
    val command: String,
    val workingDirectory: String,
    val output: String,
    val exitCode: Int?,
    val timestampMillis: Long,
    val startedAtMillis: Long? = null,
    val finishedAtMillis: Long? = null,
    val commandStatusName: String = "",
    val environmentModeName: String? = null,
    val transcriptReference: String? = null
)

@Serializable
private data class PersistedProjectTerminalRecentCommand(
    val sessionId: String,
    val command: String
)

@Serializable
private data class PersistedProjectTerminalClosedSessionSummary(
    val id: String,
    val sessionLabel: String,
    val workingDirectory: String,
    val summary: String,
    val logCount: Int = 0,
    val lastCommand: String? = null,
    val lastLogPreview: String? = null,
    val closedAtMillis: Long
)

@Serializable
private data class PersistedProjectTerminalStore(
    val activeSessionId: String? = null,
    val sessionTabs: List<PersistedProjectTerminalSessionTab> = emptyList(),
    val recentCommands: List<String> = emptyList(),
    val recentCommandEntries: List<PersistedProjectTerminalRecentCommand> = emptyList(),
    val closedSessionSummaries: List<PersistedProjectTerminalClosedSessionSummary> = emptyList(),
    val logs: List<PersistedProjectTerminalLogEntry> = emptyList()
)

@Composable
internal fun ProjectTerminalSection(
    currentProjectPath: String?,
    detectedRepos: List<ProjectDetectedRepoUi>,
    selectedRepoRoot: String?,
    onSelectRepoRoot: (String?) -> Unit
) {
    val hostContext = LocalContext.current
    val context = hostContext.applicationContext
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val terminalStateKey = remember(currentProjectPath) {
        currentProjectPath?.takeIf { it.isNotBlank() } ?: "__workspace_terminal__"
    }
    val hasSelectedProject = !currentProjectPath.isNullOrBlank()
    val defaultWorkingDirectory = remember(currentProjectPath, context) {
        File("/storage/emulated/0")
            .takeIf { it.exists() }
            ?.absolutePath
            ?: currentProjectPath?.takeIf { it.isNotBlank() && File(it).exists() }
            ?: context.filesDir.absolutePath
    }
    val preferredRootPath = selectedRepoRoot?.takeIf { it.isNotBlank() } ?: defaultWorkingDirectory
    val availableRepos = remember(defaultWorkingDirectory, currentProjectPath, detectedRepos, hasSelectedProject) {
        buildList {
            add(
                ProjectDetectedRepoUi(
                    rootPath = defaultWorkingDirectory,
                    displayName = if (hasSelectedProject) "工作区" else "默认目录",
                    relativePath = if (hasSelectedProject) "." else "app sandbox",
                    isWorkspaceRoot = true,
                    hasGitMetadata = detectedRepos.any { it.isWorkspaceRoot && it.hasGitMetadata },
                    hasReadme = hasSelectedProject,
                    hasGradleBuild = detectedRepos.any { it.isWorkspaceRoot && it.hasGradleBuild },
                    hasPackageJson = detectedRepos.any { it.isWorkspaceRoot && it.hasPackageJson },
                    hasGitHubWorkflows = detectedRepos.any { it.isWorkspaceRoot && it.hasGitHubWorkflows }
                )
            )
            addAll(detectedRepos.filterNot { it.rootPath == defaultWorkingDirectory })
        }.distinctBy { it.rootPath }
    }
    val terminalSettingsStore = remember(terminalStateKey) {
        ProjectTerminalUiSettingsStore(
            context = context,
            projectPath = terminalStateKey
        )
    }
    val terminalHistoryStore = remember(terminalStateKey) {
        ProjectTerminalHistoryStore(
            context = context,
            projectPath = terminalStateKey
        )
    }
    val persistedSettings = remember(terminalStateKey) { terminalSettingsStore.read() }
    val persistedTerminalStore = remember(terminalStateKey) { terminalHistoryStore.read() }
    var selectedTerminalThemeName by rememberSaveable(terminalStateKey) {
        mutableStateOf(persistedSettings.themeName)
    }
    val selectedTerminalTheme = remember(selectedTerminalThemeName) {
        ProjectTerminalThemePreset.entries.firstOrNull { it.name == selectedTerminalThemeName }
            ?: ProjectTerminalThemePreset.MIDNIGHT
    }
    var terminalFontSize by rememberSaveable(terminalStateKey) {
        mutableIntStateOf(persistedSettings.fontSize)
    }
    var defaultTerminalEnvironmentModeName by rememberSaveable(terminalStateKey) {
        mutableStateOf(persistedSettings.environmentModeName)
    }
    val defaultTerminalEnvironmentMode = remember(defaultTerminalEnvironmentModeName) {
        ProjectTerminalEnvironmentMode.entries.firstOrNull { it.name == defaultTerminalEnvironmentModeName }
            ?: ProjectTerminalEnvironmentMode.TOOLCHAIN
    }
    var terminalSettingsExpanded by rememberSaveable(terminalStateKey) {
        mutableStateOf(persistedSettings.settingsExpanded)
    }
    var floatingModeEnabled by rememberSaveable(terminalStateKey) {
        mutableStateOf(persistedSettings.floatingModeEnabled)
    }
    var floatingWindowRatio by rememberSaveable(terminalStateKey) {
        mutableStateOf(persistedSettings.floatingWindowRatio)
    }
    var systemOverlayRunning by rememberSaveable(terminalStateKey) {
        mutableStateOf(
            ProjectTerminalOverlayService.isRunning ||
                ProjectTerminalOverlayService.isOverlayEnabled(context)
        )
    }
    val initialSessionLabel = remember(preferredRootPath, currentProjectPath, availableRepos) {
        buildProjectTerminalSessionLabel(
            workingDirectory = preferredRootPath,
            currentProjectPath = currentProjectPath,
            availableRepos = availableRepos,
            existingTabs = emptyList()
        )
    }
    val restoredTabs = remember(terminalStateKey, preferredRootPath, defaultTerminalEnvironmentMode.name, initialSessionLabel) {
        // Relaunching the app always starts from a single fresh terminal tab.
        listOf(
            ProjectTerminalSessionTabUi(
                label = initialSessionLabel,
                workingDirectory = preferredRootPath,
                environmentModeName = defaultTerminalEnvironmentMode.name
            )
        )
    }
    val restoredOwnerSessionId = remember(restoredTabs) {
        restoredTabs.first().id
    }
    val sessionTabs = remember(terminalStateKey) {
        mutableStateListOf<ProjectTerminalSessionTabUi>().apply {
            addAll(restoredTabs)
        }
    }
    var activeSessionId by rememberSaveable(terminalStateKey) {
        mutableStateOf(restoredOwnerSessionId)
    }
    val activeSessionTab = sessionTabs.firstOrNull { it.id == activeSessionId } ?: sessionTabs.first()
    val activeEnvironmentMode = remember(activeSessionTab.environmentModeName) {
        ProjectTerminalEnvironmentMode.entries.firstOrNull { it.name == activeSessionTab.environmentModeName }
            ?: ProjectTerminalEnvironmentMode.TOOLCHAIN
    }
    LaunchedEffect(activeSessionTab.id) {
        if (activeSessionId != activeSessionTab.id) {
            activeSessionId = activeSessionTab.id
        }
    }
    val activeWorkingDirectory = activeSessionTab.workingDirectory
    val activeRepo = remember(activeWorkingDirectory, detectedRepos) {
        detectedRepos.firstOrNull { it.rootPath == activeWorkingDirectory }
    }
    val templates = remember(activeRepo, activeWorkingDirectory) {
        buildProjectTerminalTemplates(
            repo = activeRepo,
            workingDirectory = activeWorkingDirectory
        )
    }
    val sessionControllers = LinkedHashMap<String, ProjectTerminalSessionController>()
    var rootAvailable by rememberSaveable(terminalStateKey) { mutableStateOf(false) }
    LaunchedEffect(terminalStateKey) {
        rootAvailable = withContext(Dispatchers.IO) {
            KeepShellPublic.checkRoot()
        }
    }
    sessionTabs.forEach { tab ->
        val controller = key(tab.id, tab.sessionKey) {
            rememberProjectTerminalSessionController(
                ownerKey = terminalStateKey,
                sessionId = tab.id,
                workingDirectory = tab.workingDirectory,
                sessionKey = tab.sessionKey,
                environmentMode = ProjectTerminalEnvironmentMode.entries.firstOrNull { it.name == tab.environmentModeName }
                    ?: ProjectTerminalEnvironmentMode.TOOLCHAIN,
                rootAvailable = rootAvailable,
                initialTheme = selectedTerminalTheme,
                initialFontSize = terminalFontSize
            )
        }
        sessionControllers[tab.id] = controller
    }
    val terminalController = sessionControllers.getValue(activeSessionTab.id)
    var environmentDiagnostic by remember(activeSessionId, activeSessionTab.sessionKey) {
        mutableStateOf<ProjectTerminalEnvironmentDiagnostic?>(null)
    }
    var environmentDiagnosticRunning by remember(activeSessionId, activeSessionTab.sessionKey) {
        mutableStateOf(false)
    }
    LaunchedEffect(
        selectedTerminalThemeName,
        terminalFontSize,
        defaultTerminalEnvironmentModeName,
        terminalSettingsExpanded,
        floatingModeEnabled,
        floatingWindowRatio,
        terminalStateKey
    ) {
        terminalSettingsStore.write(
            ProjectTerminalUiSettings(
                themeName = selectedTerminalThemeName,
                fontSize = terminalFontSize,
                environmentModeName = defaultTerminalEnvironmentModeName,
                settingsExpanded = terminalSettingsExpanded,
                floatingModeEnabled = floatingModeEnabled,
                floatingWindowRatio = floatingWindowRatio
            )
        )
    }
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                systemOverlayRunning = ProjectTerminalOverlayService.isRunning ||
                    ProjectTerminalOverlayService.isOverlayEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    LaunchedEffect(
        systemOverlayRunning,
        activeWorkingDirectory,
        terminalFontSize,
        selectedTerminalThemeName
    ) {
        floatingModeEnabled = systemOverlayRunning
        if (!systemOverlayRunning) return@LaunchedEffect
        if (!ProjectTerminalOverlayService.canDrawOverlays(hostContext)) {
            systemOverlayRunning = false
            return@LaunchedEffect
        }
        ProjectTerminalOverlayService.startOverlay(
            context = context,
            workingDirectory = activeWorkingDirectory,
            fontSize = terminalFontSize,
            themeName = selectedTerminalThemeName,
            environmentMode = activeEnvironmentMode
        )
    }
    fun updateActiveSession(transform: (ProjectTerminalSessionTabUi) -> ProjectTerminalSessionTabUi) {
        val index = sessionTabs.indexOfFirst { it.id == activeSessionId }
        if (index >= 0) {
            sessionTabs[index] = transform(sessionTabs[index])
        }
    }
    fun openNewSession(
        targetWorkingDirectory: String = preferredRootPath,
        preferredLabel: String? = null,
        environmentModeName: String = activeSessionTab.environmentModeName.ifBlank { defaultTerminalEnvironmentMode.name }
    ) {
        val tab = ProjectTerminalSessionTabUi(
            label = preferredLabel
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { buildProjectTerminalUniqueSessionLabel(it, sessionTabs) }
                ?: buildProjectTerminalSessionLabel(
                    workingDirectory = targetWorkingDirectory,
                    currentProjectPath = currentProjectPath,
                    availableRepos = availableRepos,
                    existingTabs = sessionTabs
                ),
            workingDirectory = targetWorkingDirectory,
            environmentModeName = environmentModeName
        )
        sessionTabs.add(tab)
        activeSessionId = tab.id
    }
    val quickKeys = remember {
        listOf(
            ProjectTerminalQuickKeyUi("Ctrl+C") { it.sendCtrlC() },
            ProjectTerminalQuickKeyUi("Esc") { it.sendEscape() },
            ProjectTerminalQuickKeyUi("Tab") { it.sendTab() },
            ProjectTerminalQuickKeyUi("Enter") { it.sendEnter() },
            ProjectTerminalQuickKeyUi("←") { it.sendArrowLeft() },
            ProjectTerminalQuickKeyUi("↓") { it.sendArrowDown() },
            ProjectTerminalQuickKeyUi("↑") { it.sendArrowUp() },
            ProjectTerminalQuickKeyUi("→") { it.sendArrowRight() },
            ProjectTerminalQuickKeyUi("Clear") { it.clearScreen() }
        )
    }
    val recentCommands = remember(terminalStateKey) {
        mutableStateListOf<ProjectTerminalRecentCommandUi>().apply {
            val restoredEntries = persistedTerminalStore.recentCommandEntries
                .mapNotNull { entry ->
                    val sessionId = entry.sessionId.ifBlank { restoredOwnerSessionId }
                    val command = entry.command.trim()
                    if (command.isBlank()) {
                        null
                    } else {
                        ProjectTerminalRecentCommandUi(
                            sessionId = sessionId,
                            command = command
                        )
                    }
                }
            if (restoredEntries.isNotEmpty()) {
                addAll(restoredEntries.take(24))
            } else {
                addAll(
                    persistedTerminalStore.recentCommands.take(6).map { command ->
                        ProjectTerminalRecentCommandUi(
                            sessionId = restoredOwnerSessionId,
                            command = command
                        )
                    }
                )
            }
        }
    }
    val closedSessionSummaries = remember(terminalStateKey) {
        mutableStateListOf<ProjectTerminalClosedSessionSummaryUi>().apply {
            addAll(
                persistedTerminalStore.closedSessionSummaries.take(6).map { summary ->
                    ProjectTerminalClosedSessionSummaryUi(
                        id = summary.id,
                        sessionLabel = summary.sessionLabel,
                        workingDirectory = summary.workingDirectory,
                        summary = summary.summary,
                        logCount = summary.logCount,
                        lastCommand = summary.lastCommand,
                        lastLogPreview = summary.lastLogPreview,
                        closedAtMillis = summary.closedAtMillis
                    )
                }
            )
        }
    }
    val logs = remember(terminalStateKey) {
        mutableStateListOf<ProjectTerminalLogEntryUi>().apply {
            addAll(
                persistedTerminalStore.logs.take(PROJECT_TERMINAL_LOG_LIMIT_TOTAL).map {
                    ProjectTerminalLogEntryUi(
                        id = it.id,
                        commandId = it.commandId.ifBlank { it.id },
                        sessionId = it.sessionId.ifBlank { restoredOwnerSessionId },
                        command = it.command,
                        workingDirectory = it.workingDirectory,
                        output = it.output,
                        exitCode = it.exitCode,
                        timestampMillis = it.timestampMillis,
                        startedAtMillis = it.startedAtMillis ?: it.timestampMillis,
                        finishedAtMillis = it.finishedAtMillis ?: it.timestampMillis,
                        commandStatus = ProjectTerminalCommandStatus.entries.firstOrNull {
                            status -> status.name == it.commandStatusName
                        } ?: ProjectTerminalCommandStatus.COMPLETED,
                        environmentModeName = it.environmentModeName,
                        transcriptReference = it.transcriptReference
                    )
                }
            )
            val sessionIds = map { it.sessionId }.distinct()
            sessionIds.forEach { sessionId ->
                trimProjectTerminalLogsForSession(this, sessionId)
            }
            while (size > PROJECT_TERMINAL_LOG_LIMIT_TOTAL) removeLast()
        }
    }
    var commandInput by rememberSaveable(terminalStateKey) { mutableStateOf("") }
    var selectedCategory by rememberSaveable(terminalStateKey) {
        mutableStateOf(ProjectTerminalTemplateCategory.BASIC)
    }
    var utilityTabName by rememberSaveable(terminalStateKey) {
        mutableStateOf(ProjectTerminalUtilityTab.COMMANDS.name)
    }
    var renameEditorVisible by rememberSaveable(terminalStateKey) { mutableStateOf(false) }
    var renameInput by rememberSaveable(terminalStateKey) { mutableStateOf(activeSessionTab.label) }
    var terminalStatusMessage by rememberSaveable(terminalStateKey) { mutableStateOf("") }
    var logSearchQuery by rememberSaveable(terminalStateKey) { mutableStateOf("") }
    var logStatusFilter by rememberSaveable(terminalStateKey) {
        mutableStateOf(ProjectTerminalLogStatusFilter.ALL.name)
    }
    var sharedTimeFilterName by rememberSaveable(terminalStateKey) {
        mutableStateOf(ProjectTerminalTimeFilter.ALL.name)
    }
    var closedSummarySearchQuery by rememberSaveable(terminalStateKey) { mutableStateOf("") }
    var closeConfirmSessionId by rememberSaveable(terminalStateKey) { mutableStateOf<String?>(null) }
    var closeConfirmSummary by remember(terminalStateKey) { mutableStateOf<ProjectTerminalClosedSessionSummaryUi?>(null) }
    var isRunning by remember(terminalStateKey) { mutableStateOf(false) }
    val sessionTabsSnapshot = sessionTabs.toList()
    val recentCommandsSnapshot = recentCommands.toList()
    val closedSessionSummariesSnapshot = closedSessionSummaries.toList()
    val logsSnapshot = logs.toList()
    val activeRecentCommands = remember(recentCommandsSnapshot, activeSessionId) {
        recentCommandsSnapshot
            .filter { it.sessionId == activeSessionId }
            .map { it.command }
    }
    val activeSessionIndex = sessionTabs.indexOfFirst { it.id == activeSessionId }
    val selectedLogStatusFilter = remember(logStatusFilter) {
        ProjectTerminalLogStatusFilter.entries.firstOrNull { it.name == logStatusFilter }
            ?: ProjectTerminalLogStatusFilter.ALL
    }
    val selectedTimeFilter = remember(sharedTimeFilterName) {
        ProjectTerminalTimeFilter.entries.firstOrNull { it.name == sharedTimeFilterName }
            ?: ProjectTerminalTimeFilter.ALL
    }
    val selectedUtilityTab = remember(utilityTabName) {
        ProjectTerminalUtilityTab.entries.firstOrNull { it.name == utilityTabName }
            ?: ProjectTerminalUtilityTab.COMMANDS
    }
    val scopedLogs = remember(logsSnapshot, activeSessionId) {
        logsSnapshot.filter { it.sessionId == activeSessionId }
    }
    val timeScopedLogs = remember(scopedLogs, selectedTimeFilter) {
        scopedLogs.filter { selectedTimeFilter.matches(it.timestampMillis) }
    }
    val filteredLogs = remember(
        timeScopedLogs,
        logSearchQuery,
        selectedLogStatusFilter
    ) {
        val statusScopedLogs = timeScopedLogs
            .filter { selectedLogStatusFilter.matches(it) }
        val query = logSearchQuery.trim()
        if (query.isBlank()) {
            statusScopedLogs
        } else {
            val normalized = query.lowercase(Locale.getDefault())
            statusScopedLogs.filter { entry ->
                entry.command.lowercase(Locale.getDefault()).contains(normalized) ||
                    entry.workingDirectory.lowercase(Locale.getDefault()).contains(normalized) ||
                    entry.output.lowercase(Locale.getDefault()).contains(normalized) ||
                    entry.statusLabel.lowercase(Locale.getDefault()).contains(normalized)
            }
        }
    }
    val timeScopedClosedSessionSummaries = remember(closedSessionSummariesSnapshot, selectedTimeFilter) {
        closedSessionSummariesSnapshot.filter { selectedTimeFilter.matches(it.closedAtMillis) }
    }
    val filteredClosedSessionSummaries = remember(
        timeScopedClosedSessionSummaries,
        closedSummarySearchQuery,
    ) {
        val query = closedSummarySearchQuery.trim()
        if (query.isBlank()) {
            timeScopedClosedSessionSummaries
        } else {
            val normalized = query.lowercase(Locale.getDefault())
            timeScopedClosedSessionSummaries.filter { summary ->
                summary.sessionLabel.lowercase(Locale.getDefault()).contains(normalized) ||
                    summary.workingDirectory.lowercase(Locale.getDefault()).contains(normalized) ||
                    summary.summary.lowercase(Locale.getDefault()).contains(normalized) ||
                    summary.lastCommand.orEmpty().lowercase(Locale.getDefault()).contains(normalized) ||
                    summary.lastLogPreview.orEmpty().lowercase(Locale.getDefault()).contains(normalized)
            }
        }
    }

    fun buildClosingSessionSummary(tab: ProjectTerminalSessionTabUi): ProjectTerminalClosedSessionSummaryUi {
        val transcript = sessionControllers[tab.id]?.getTranscriptText()
        val summary = buildProjectTerminalTranscriptSummary(transcript)
        val sessionLogs = logs.filter { it.sessionId == tab.id }
        val latestLog = sessionLogs.maxByOrNull { it.timestampMillis }
        return ProjectTerminalClosedSessionSummaryUi(
            sessionLabel = tab.label,
            workingDirectory = tab.workingDirectory,
            summary = summary,
            logCount = sessionLogs.size,
            lastCommand = latestLog?.command,
            lastLogPreview = latestLog?.output?.trim()?.take(180)?.ifBlank { null },
            closedAtMillis = System.currentTimeMillis()
        )
    }
    fun archiveClosingSession(tab: ProjectTerminalSessionTabUi, preparedSummary: ProjectTerminalClosedSessionSummaryUi? = null) {
        val summaryEntry = preparedSummary ?: buildClosingSessionSummary(tab)
        closedSessionSummaries.removeAll { it.sessionLabel == tab.label && it.workingDirectory == tab.workingDirectory }
        closedSessionSummaries.add(
            0,
            summaryEntry
        )
        while (closedSessionSummaries.size > 6) closedSessionSummaries.removeLast()
        logs.removeAll { it.sessionId == tab.id }
    }
    fun closeSession(sessionId: String) {
        val index = sessionTabs.indexOfFirst { it.id == sessionId }
        if (index < 0) return
        val closingTab = sessionTabs[index]
        val summary = buildClosingSessionSummary(closingTab)
        ProjectTerminalSessionRegistry.release(
            ownerKey = terminalStateKey,
            sessionId = closingTab.id,
            sessionGeneration = closingTab.sessionKey
        )
        archiveClosingSession(closingTab, preparedSummary = summary)
        sessionTabs.removeAt(index)
        if (sessionTabs.isEmpty()) {
            val replacement = ProjectTerminalSessionTabUi(
                label = buildProjectTerminalSessionLabel(
                    workingDirectory = preferredRootPath,
                    currentProjectPath = currentProjectPath,
                    availableRepos = availableRepos,
                    existingTabs = emptyList()
                ),
                workingDirectory = preferredRootPath,
                environmentModeName = defaultTerminalEnvironmentMode.name
            )
            sessionTabs.add(replacement)
            activeSessionId = replacement.id
            terminalStatusMessage = "已关闭会话，并新建一个普通 shell"
        } else {
            val nextIndex = index.coerceAtMost(sessionTabs.lastIndex)
            activeSessionId = sessionTabs[nextIndex].id
            terminalStatusMessage = "已关闭 ${closingTab.label}"
        }
        closeConfirmSessionId = null
        closeConfirmSummary = null
    }
    fun closeActiveSession() = closeSession(activeSessionId)
    fun confirmCloseActiveSession() = closeActiveSession()
    fun cancelCloseActiveSession() {
        closeConfirmSessionId = null
        closeConfirmSummary = null
    }
    fun moveActiveSessionLeft() {
        val index = sessionTabs.indexOfFirst { it.id == activeSessionId }
        if (index <= 0) return
        val current = sessionTabs[index]
        sessionTabs[index] = sessionTabs[index - 1]
        sessionTabs[index - 1] = current
        terminalStatusMessage = "已左移会话"
    }
    fun moveActiveSessionRight() {
        val index = sessionTabs.indexOfFirst { it.id == activeSessionId }
        if (index < 0 || index >= sessionTabs.lastIndex) return
        val current = sessionTabs[index]
        sessionTabs[index] = sessionTabs[index + 1]
        sessionTabs[index + 1] = current
        terminalStatusMessage = "已右移会话"
    }
    fun pinActiveSession() {
        val index = sessionTabs.indexOfFirst { it.id == activeSessionId }
        if (index <= 0) return
        val current = sessionTabs.removeAt(index)
        sessionTabs.add(0, current)
        activeSessionId = current.id
        terminalStatusMessage = "已置顶当前会话"
    }
    fun repointActiveSession(targetWorkingDirectory: String) {
        val currentTab = activeSessionTab
        ProjectTerminalSessionRegistry.release(
            ownerKey = terminalStateKey,
            sessionId = currentTab.id,
            sessionGeneration = currentTab.sessionKey
        )
        updateActiveSession { tab ->
            tab.copy(
                label = buildProjectTerminalSessionLabel(
                    workingDirectory = targetWorkingDirectory,
                    currentProjectPath = currentProjectPath,
                    availableRepos = availableRepos,
                    existingTabs = sessionTabs.filterNot { it.id == tab.id }
                ),
                workingDirectory = targetWorkingDirectory,
                sessionKey = tab.sessionKey + 1
            )
        }
        if (hasSelectedProject) {
            onSelectRepoRoot(targetWorkingDirectory)
        }
    }
    fun restartActiveSession() {
        val currentTab = activeSessionTab
        ProjectTerminalSessionRegistry.release(
            ownerKey = terminalStateKey,
            sessionId = currentTab.id,
            sessionGeneration = currentTab.sessionKey
        )
        updateActiveSession { tab -> tab.copy(sessionKey = tab.sessionKey + 1) }
    }
    fun replaceActiveSessionEnvironment(next: ProjectTerminalEnvironmentMode) {
        val currentTab = activeSessionTab
        ProjectTerminalSessionRegistry.release(
            ownerKey = terminalStateKey,
            sessionId = currentTab.id,
            sessionGeneration = currentTab.sessionKey
        )
        updateActiveSession { tab ->
            tab.copy(
                environmentModeName = next.name,
                sessionKey = tab.sessionKey + 1
            )
        }
    }
    fun enterRootSession() {
        if (activeEnvironmentMode == ProjectTerminalEnvironmentMode.ROOT) return
        replaceActiveSessionEnvironment(ProjectTerminalEnvironmentMode.ROOT)
        terminalStatusMessage = "正在以 Root 启动扩展包 bash；输入 exit 将返回普通扩展包环境"
    }
    fun leaveRootSession(message: String = "已退出 Root shell，已返回扩展包环境") {
        if (activeEnvironmentMode != ProjectTerminalEnvironmentMode.ROOT) return
        replaceActiveSessionEnvironment(ProjectTerminalEnvironmentMode.TOOLCHAIN)
        terminalStatusMessage = message
    }
    fun restartAllSessions() {
        val currentTabs = sessionTabs.toList()
        currentTabs.forEach { tab ->
            ProjectTerminalSessionRegistry.release(
                ownerKey = terminalStateKey,
                sessionId = tab.id,
                sessionGeneration = tab.sessionKey
            )
        }
        currentTabs.forEachIndexed { index, tab ->
            sessionTabs[index] = tab.copy(sessionKey = tab.sessionKey + 1)
        }
    }
    val hasToolchainEnvironment = ToolchainManager.hasAvailableToolchain(context)
    val preferredEnvironmentLabel = ToolchainManager.describeActiveEnvironment(context = context)
    val currentEnvironmentLabel = ToolchainManager.describeActiveEnvironment(
        context = context,
        preferSystem = activeEnvironmentMode == ProjectTerminalEnvironmentMode.SYSTEM
    ).let { label ->
        if (activeEnvironmentMode == ProjectTerminalEnvironmentMode.ROOT) "Root $label" else label
    }
    val currentShellLabel = when (activeEnvironmentMode) {
        ProjectTerminalEnvironmentMode.TOOLCHAIN,
        ProjectTerminalEnvironmentMode.ROOT -> "bash"
        ProjectTerminalEnvironmentMode.SYSTEM -> if (isSystemBashEnabled(context)) {
            "/system/bin/bash"
        } else {
            "/system/bin/sh（系统 bash 已回退）"
        }
    }
    val currentEnvironmentSummary = when {
        hasToolchainEnvironment -> "$currentEnvironmentLabel · $currentShellLabel"
        else -> "$currentEnvironmentLabel · $currentShellLabel（未检测到扩展包）"
    }
    val environmentSwitchLabel = when {
        activeEnvironmentMode == ProjectTerminalEnvironmentMode.ROOT -> "退出 Root shell"
        !hasToolchainEnvironment -> "切换环境"
        activeEnvironmentMode == ProjectTerminalEnvironmentMode.SYSTEM -> "切到$preferredEnvironmentLabel"
        else -> "切到系统环境"
    }
    fun toggleTerminalEnvironment() {
        if (activeEnvironmentMode == ProjectTerminalEnvironmentMode.ROOT) {
            leaveRootSession()
            return
        }
        if (!hasToolchainEnvironment) {
            terminalStatusMessage = "当前还没检测到扩展包环境，所以只能用系统环境。先安装终端扩展包后，这里就能切换。"
            return
        }
        val nextEnvironmentModeName = when (activeEnvironmentMode) {
            ProjectTerminalEnvironmentMode.TOOLCHAIN -> ProjectTerminalEnvironmentMode.SYSTEM.name
            ProjectTerminalEnvironmentMode.SYSTEM -> ProjectTerminalEnvironmentMode.TOOLCHAIN.name
            ProjectTerminalEnvironmentMode.ROOT -> ProjectTerminalEnvironmentMode.TOOLCHAIN.name
        }
        defaultTerminalEnvironmentModeName = nextEnvironmentModeName
        updateActiveSession { tab ->
            tab.copy(
                environmentModeName = nextEnvironmentModeName,
                sessionKey = tab.sessionKey + 1
            )
        }
        val switchedLabel = ToolchainManager.describeActiveEnvironment(
            context = context,
            preferSystem = nextEnvironmentModeName == ProjectTerminalEnvironmentMode.SYSTEM.name
        )
        terminalStatusMessage = "已切换到$switchedLabel"
    }
    fun updateTerminalFontSize(delta: Int) {
        terminalFontSize = (terminalFontSize + delta).coerceIn(14, 30)
    }
    fun updateFloatingWindowRatio(delta: Float) {
        floatingWindowRatio = (floatingWindowRatio + delta).coerceIn(0.25f, 0.5f)
    }
    fun renameActiveSession() {
        val normalized = renameInput.trim().ifBlank { activeSessionTab.label }
        updateActiveSession { tab -> tab.copy(label = normalized) }
        renameInput = normalized
        renameEditorVisible = false
        terminalStatusMessage = "已重命名会话"
    }
    fun clearRecentCommands() {
        val removed = recentCommands.removeAll { it.sessionId == activeSessionId }
        if (!removed) return
        terminalStatusMessage = "已清空最近命令"
    }
    fun addRecentCommand(command: String) {
        recentCommands.removeAll { it.sessionId == activeSessionId && it.command == command }
        recentCommands.add(
            0,
            ProjectTerminalRecentCommandUi(
                sessionId = activeSessionId,
                command = command
            )
        )
        var keptForSession = 0
        for (index in recentCommands.lastIndex downTo 0) {
            if (recentCommands[index].sessionId == activeSessionId) {
                keptForSession += 1
                if (keptForSession > 6) {
                    recentCommands.removeAt(index)
                }
            }
        }
        while (recentCommands.size > 24) recentCommands.removeLast()
    }
    fun clearActiveSessionLogs() {
        val removed = logs.removeAll { it.sessionId == activeSessionId }
        if (!removed) return
        terminalStatusMessage = "已清空当前会话日志"
    }
    fun addSessionLog(entry: ProjectTerminalLogEntryUi) {
        logs.add(0, entry.copy(sessionId = activeSessionId))
        trimProjectTerminalLogsForSession(logs, activeSessionId)
        while (logs.size > PROJECT_TERMINAL_LOG_LIMIT_TOTAL) logs.removeLast()
    }
    fun copyLogsToClipboard() {
        if (filteredLogs.isEmpty()) return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(
            ClipData.newPlainText("MurongTerminalLogs", buildProjectTerminalLogsText(filteredLogs))
        )
        terminalStatusMessage = "已复制当前会话日志"
    }
    fun runEnvironmentDiagnostic() {
        if (environmentDiagnosticRunning) return
        environmentDiagnosticRunning = true
        terminalStatusMessage = "正在只读检查当前终端环境…"
        scope.launch {
            val diagnostic = runCatching {
                terminalController.runEnvironmentDiagnostic()
            }.getOrElse {
                projectTerminalEnvironmentDiagnosticUnavailable(
                    environmentMode = activeEnvironmentMode,
                    message = it.message ?: "检查命令无法执行"
                )
            }
            environmentDiagnostic = diagnostic
            environmentDiagnosticRunning = false
            terminalStatusMessage = diagnostic.headline
        }
    }
    fun copyEnvironmentDiagnosticToClipboard() {
        val diagnostic = environmentDiagnostic ?: return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(
            ClipData.newPlainText(
                "MurongTerminalEnvironmentDiagnostic",
                diagnostic.toClipboardText()
            )
        )
        terminalStatusMessage = "已复制终端环境诊断"
    }
    fun copySingleLog(entry: ProjectTerminalLogEntryUi) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(
            ClipData.newPlainText("MurongTerminalLog", buildProjectTerminalLogText(entry))
        )
        terminalStatusMessage = "已复制单条日志"
    }
    fun exportSingleLog(entry: ProjectTerminalLogEntryUi) {
        val output = exportProjectTerminalSingleLog(
            context = context,
            projectPath = terminalStateKey,
            sessionLabel = activeSessionTab.label,
            entry = entry
        )
        terminalStatusMessage = output.fold(
            onSuccess = { "单条日志已导出到 ${it.absolutePath}" },
            onFailure = { "单条日志导出失败：${it.message ?: "未知错误"}" }
        )
    }
    fun copyClosedSessionSummary(summary: ProjectTerminalClosedSessionSummaryUi) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(
            ClipData.newPlainText(
                "MurongTerminalClosedSession",
                buildProjectTerminalClosedSessionSummaryText(summary)
            )
        )
        terminalStatusMessage = "已复制关闭摘要"
    }
    fun exportClosedSessionSummary(summary: ProjectTerminalClosedSessionSummaryUi) {
        val output = exportProjectTerminalClosedSessionSummary(
            context = context,
            projectPath = terminalStateKey,
            sessionLabel = summary.sessionLabel,
            summary = summary
        )
        terminalStatusMessage = output.fold(
            onSuccess = { "关闭摘要已导出到 ${it.absolutePath}" },
            onFailure = { "关闭摘要导出失败：${it.message ?: "未知错误"}" }
        )
    }
    fun restoreClosedSessionAsTemplate(summary: ProjectTerminalClosedSessionSummaryUi) {
        openNewSession(
            targetWorkingDirectory = summary.workingDirectory,
            preferredLabel = summary.sessionLabel
        )
        terminalStatusMessage = "已按关闭摘要恢复为新会话"
    }
    fun exportClosedSessionSummaries() {
        if (filteredClosedSessionSummaries.isEmpty()) return
        val output = exportProjectTerminalClosedSessionSummaries(
            context = context,
            projectPath = terminalStateKey,
            summaries = filteredClosedSessionSummaries
        )
        terminalStatusMessage = output.fold(
            onSuccess = { "关闭摘要已导出到 ${it.absolutePath}" },
            onFailure = { "关闭摘要导出失败：${it.message ?: "未知错误"}" }
        )
    }
    fun clearClosedSessionSummaries() {
        if (closedSessionSummaries.isEmpty()) return
        closedSessionSummaries.clear()
        closedSummarySearchQuery = ""
        terminalStatusMessage = "已清空关闭摘要"
    }
    fun exportLogs() {
        if (filteredLogs.isEmpty()) return
        val output = exportProjectTerminalLogs(
            context = context,
            projectPath = terminalStateKey,
            sessionLabel = activeSessionTab.label,
            logs = filteredLogs
        )
        terminalStatusMessage = output.fold(
            onSuccess = { "日志已导出到 ${it.absolutePath}" },
            onFailure = { "日志导出失败：${it.message ?: "未知错误"}" }
        )
    }
    fun exportCurrentTranscript() {
        val transcript = terminalController.getTranscriptText()
        if (transcript.isNullOrBlank()) {
            terminalStatusMessage = "当前 transcript 为空"
            return
        }
        val output = exportProjectTerminalTranscript(
            context = context,
            projectPath = terminalStateKey,
            sessionLabel = activeSessionTab.label,
            transcript = transcript
        )
        terminalStatusMessage = output.fold(
            onSuccess = { "transcript 已导出到 ${it.absolutePath}" },
            onFailure = { "transcript 导出失败：${it.message ?: "未知错误"}" }
        )
    }

    LaunchedEffect(activeSessionId, activeSessionTab.label) {
        renameInput = activeSessionTab.label
    }
    LaunchedEffect(terminalStatusMessage) {
        val message = terminalStatusMessage.trim()
        if (message.isBlank()) return@LaunchedEffect
        MurongTransientMessageBus.show(message)
        delay(2200)
        if (terminalStatusMessage == message) {
            terminalStatusMessage = ""
        }
    }
    DisposableEffect(terminalController) {
        terminalController.onFontSizeChanged = { newSize ->
            if (terminalFontSize != newSize) {
                terminalFontSize = newSize
            }
        }
        terminalController.onSessionExit = { exitCode ->
            if (activeEnvironmentMode == ProjectTerminalEnvironmentMode.ROOT) {
                leaveRootSession(
                    message = if (exitCode == 0) {
                        "已退出 Root shell，已返回扩展包环境"
                    } else {
                        "Root shell 已结束（code $exitCode），已返回扩展包环境"
                    }
                )
            } else {
                val restartMessage = when {
                    exitCode < 0 -> "终端进程被中断，已自动重建（signal ${-exitCode}）"
                    exitCode > 0 -> "终端进程异常退出，已自动重建（code $exitCode）"
                    else -> "终端会话已退出，已自动重建"
                }
                restartActiveSession()
                terminalStatusMessage = restartMessage
            }
        }
        terminalController.onRootRequested = { enterRootSession() }
        terminalController.onCommandCompleted = { record, outputPreview ->
            addRecentCommand(record.command)
            addSessionLog(
                ProjectTerminalLogEntryUi(
                    commandId = record.commandId,
                    command = record.command,
                    workingDirectory = record.workingDirectory,
                    output = outputPreview.ifBlank { "（未捕获可显示输出；可导出完整 transcript 查看。）" },
                    exitCode = record.exitCode,
                    timestampMillis = record.timestampMillis,
                    startedAtMillis = record.startedAtMillis,
                    finishedAtMillis = record.finishedAtMillis,
                    commandStatus = record.status,
                    environmentModeName = record.environmentMode?.name,
                    transcriptReference = record.transcriptReference
                )
            )
        }
        onDispose {
            terminalController.onFontSizeChanged = null
            terminalController.onSessionExit = null
            terminalController.onRootRequested = null
            terminalController.onCommandCompleted = null
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .imePadding()
    ) {
        val density = LocalDensity.current
        val imeBottomPx = WindowInsets.ime.getBottom(density)
        val imeVisible = imeBottomPx > 0
        val terminalHeight = if (imeBottomPx > 0) null else maxHeight * 0.41f
        ProjectTerminalPanel(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .then(if (imeVisible) Modifier.fillMaxHeight() else Modifier)
                .padding(
                    horizontal = 8.dp,
                    vertical = if (imeVisible) 4.dp else 8.dp
                ),
            sessionTabs = sessionTabs,
            activeSessionId = activeSessionId,
            onSelectSession = { activeSessionId = it },
            onCloseSession = { closeSession(it) },
            onOpenNewSession = { openNewSession() },
            overlayRunning = systemOverlayRunning,
            onToggleOverlay = {
                if (systemOverlayRunning) {
                    ProjectTerminalOverlayService.stopOverlay(context)
                    systemOverlayRunning = false
                    terminalStatusMessage = "已关闭系统悬浮终端"
                } else if (!ProjectTerminalOverlayService.canDrawOverlays(hostContext)) {
                    hostContext.startActivity(
                        ProjectTerminalOverlayService.createOverlayPermissionIntent(hostContext)
                    )
                    terminalStatusMessage = "请授予悬浮窗权限后，再次点击启动"
                } else {
                    ProjectTerminalOverlayService.startOverlay(
                        context = context,
                        workingDirectory = activeWorkingDirectory,
                        fontSize = terminalFontSize,
                        themeName = selectedTerminalThemeName,
                        environmentMode = activeEnvironmentMode
                    )
                    systemOverlayRunning = true
                    terminalStatusMessage = "已启动系统悬浮终端，可切到软件外继续使用"
                }
            },
            environmentLabel = currentEnvironmentSummary,
            environmentSwitchLabel = environmentSwitchLabel,
            canSwitchEnvironment = true,
            onToggleEnvironment = { toggleTerminalEnvironment() },
            environmentDiagnostic = environmentDiagnostic,
            environmentDiagnosticRunning = environmentDiagnosticRunning,
            onRunEnvironmentDiagnostic = { runEnvironmentDiagnostic() },
            onCopyEnvironmentDiagnostic = { copyEnvironmentDiagnosticToClipboard() },
            controller = terminalController,
            terminalBodyHeight = terminalHeight,
            showAccessoryKeys = !imeVisible,
            keepTerminalPinnedToBottom = false,
            compactForIme = imeVisible
        )
    }

    LaunchedEffect(
        currentProjectPath,
        activeSessionId,
        sessionTabsSnapshot,
        recentCommandsSnapshot,
        closedSessionSummariesSnapshot,
        logsSnapshot
    ) {
        terminalHistoryStore.write(
            PersistedProjectTerminalStore(
                activeSessionId = activeSessionId,
                sessionTabs = sessionTabsSnapshot.map {
                    PersistedProjectTerminalSessionTab(
                        id = it.id,
                        label = it.label,
                        workingDirectory = it.workingDirectory,
                        environmentModeName = it.environmentModeName
                    )
                },
                recentCommands = recentCommandsSnapshot
                    .filter { it.sessionId == activeSessionId }
                    .map { it.command }
                    .take(6),
                recentCommandEntries = recentCommandsSnapshot.take(24).map {
                    PersistedProjectTerminalRecentCommand(
                        sessionId = it.sessionId,
                        command = it.command
                    )
                },
                closedSessionSummaries = closedSessionSummariesSnapshot.take(6).map {
                    PersistedProjectTerminalClosedSessionSummary(
                        id = it.id,
                        sessionLabel = it.sessionLabel,
                        workingDirectory = it.workingDirectory,
                        summary = it.summary,
                        logCount = it.logCount,
                        lastCommand = it.lastCommand,
                        lastLogPreview = it.lastLogPreview,
                        closedAtMillis = it.closedAtMillis
                    )
                },
                logs = logsSnapshot.take(PROJECT_TERMINAL_LOG_LIMIT_TOTAL).map {
                    PersistedProjectTerminalLogEntry(
                        id = it.id,
                        commandId = it.commandId,
                        sessionId = it.sessionId,
                        command = it.command,
                        workingDirectory = it.workingDirectory,
                        output = it.output,
                        exitCode = it.exitCode,
                        timestampMillis = it.timestampMillis,
                        startedAtMillis = it.startedAtMillis,
                        finishedAtMillis = it.finishedAtMillis,
                        commandStatusName = it.commandStatus.name,
                        environmentModeName = it.environmentModeName,
                        transcriptReference = it.transcriptReference
                    )
                }
            )
        )
    }
}

@Composable
private fun ProjectTerminalPanel(
    modifier: Modifier,
    sessionTabs: List<ProjectTerminalSessionTabUi>,
    activeSessionId: String,
    onSelectSession: (String) -> Unit,
    onCloseSession: (String) -> Unit,
    onOpenNewSession: () -> Unit,
    overlayRunning: Boolean,
    onToggleOverlay: () -> Unit,
    environmentLabel: String,
    environmentSwitchLabel: String,
    canSwitchEnvironment: Boolean,
    onToggleEnvironment: () -> Unit,
    environmentDiagnostic: ProjectTerminalEnvironmentDiagnostic?,
    environmentDiagnosticRunning: Boolean,
    onRunEnvironmentDiagnostic: () -> Unit,
    onCopyEnvironmentDiagnostic: () -> Unit,
    controller: ProjectTerminalSessionController,
    terminalBodyHeight: Dp?,
    showAccessoryKeys: Boolean,
    keepTerminalPinnedToBottom: Boolean,
    compactForIme: Boolean
) {
    val renderedTabs = sessionTabs.toList()
    val panelBackgroundColor = MaterialTheme.colorScheme.background
    val panelBorderColor = rememberMurongSurfaceColor()
    val panelContentColor = MaterialTheme.colorScheme.onSurface
    val mutedTextColor = rememberMurongMutedTextColor()
    LaunchedEffect(controller, panelBackgroundColor, panelContentColor) {
        controller.applyUiPalette(
            background = panelBackgroundColor,
            foreground = panelContentColor
        )
    }
    LaunchedEffect(controller, keepTerminalPinnedToBottom) {
        controller.setKeepTerminalPinnedToBottom(keepTerminalPinnedToBottom)
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = panelBackgroundColor,
        contentColor = panelContentColor,
        border = BorderStroke(1.dp, panelBorderColor.copy(alpha = 0.92f)),
        shadowElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = if (compactForIme) 2.dp else 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    renderedTabs.forEach { tab ->
                        TerminalTabItem(
                            label = tab.label,
                            selected = tab.id == activeSessionId,
                            onSelect = { onSelectSession(tab.id) },
                            onClose = { onCloseSession(tab.id) }
                        )
                    }
                    TerminalToolbarButton(
                        label = "+",
                        onClick = onOpenNewSession,
                        highlighted = true
                    )
                }
                if (!compactForIme) {
                    TerminalToolbarButton(
                        label = if (overlayRunning) "关浮窗" else "悬浮窗",
                        onClick = onToggleOverlay
                    )
                }
            }
            if (!compactForIme) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "当前：$environmentLabel",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = mutedTextColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    TerminalToolbarButton(
                        label = if (environmentDiagnosticRunning) "自检中" else "自检",
                        onClick = onRunEnvironmentDiagnostic,
                        enabled = !environmentDiagnosticRunning,
                        highlighted = environmentDiagnostic != null
                    )
                    TerminalToolbarButton(
                        label = environmentSwitchLabel,
                        onClick = onToggleEnvironment,
                        enabled = canSwitchEnvironment,
                        highlighted = canSwitchEnvironment
                    )
                }
                environmentDiagnostic?.let { diagnostic ->
                    TerminalEnvironmentDiagnosticCard(
                        diagnostic = diagnostic,
                        onCopy = onCopyEnvironmentDiagnostic
                    )
                }
            }

            if (!overlayRunning && showAccessoryKeys) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 10.dp, end = 10.dp, bottom = if (compactForIme) 8.dp else 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TerminalAccessoryKey(
                            label = "Ctrl",
                            active = controller.ctrlLocked,
                            onClick = { controller.toggleCtrlLock() }
                        )
                        TerminalAccessoryKey(
                            label = "Shift",
                            active = controller.shiftLocked,
                            onClick = { controller.toggleShiftLock() }
                        )
                        TerminalAccessoryKey(
                            label = "Alt",
                            active = controller.altLocked,
                            onClick = { controller.toggleAltLock() }
                        )
                        TerminalAccessoryKey(
                            label = "Tab",
                            onClick = { controller.sendTab() }
                        )
                        TerminalAccessoryKey(
                            label = "Esc",
                            onClick = { controller.sendEscape() }
                        )
                        TerminalAccessoryKey(
                            label = "Enter",
                            onClick = { controller.sendEnter() }
                        )
                        if (compactForIme) {
                            TerminalAccessoryKey(
                                label = "Paste",
                                onClick = { controller.pasteClipboard() }
                            )
                            TerminalAccessoryKey(
                                label = "←",
                                onClick = { controller.sendArrowLeft() }
                            )
                            TerminalAccessoryKey(
                                label = "↓",
                                onClick = { controller.sendArrowDown() }
                            )
                            TerminalAccessoryKey(
                                label = "↑",
                                onClick = { controller.sendArrowUp() }
                            )
                            TerminalAccessoryKey(
                                label = "→",
                                onClick = { controller.sendArrowRight() }
                            )
                        }
                    }
                    if (!compactForIme) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TerminalAccessoryKey(
                                label = "Paste",
                                onClick = { controller.pasteClipboard() }
                            )
                            TerminalAccessoryKey(
                                label = "←",
                                onClick = { controller.sendArrowLeft() }
                            )
                            TerminalAccessoryKey(
                                label = "↓",
                                onClick = { controller.sendArrowDown() }
                            )
                            TerminalAccessoryKey(
                                label = "↑",
                                onClick = { controller.sendArrowUp() }
                            )
                            TerminalAccessoryKey(
                                label = "→",
                                onClick = { controller.sendArrowRight() }
                            )
                        }
                    }
                }
            }

            Box(
                modifier = (
                    if (terminalBodyHeight != null) {
                        Modifier
                            .fillMaxWidth()
                            .height(terminalBodyHeight)
                    } else {
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    }
                    ).padding(
                        start = if (compactForIme) 2.dp else 0.dp,
                        end = if (compactForIme) 2.dp else 0.dp,
                        bottom = if (compactForIme) 0.dp else 12.dp
                    )
                    .background(panelBackgroundColor)
            ) {
                ProjectTerminalSessionSurface(
                    controller = controller,
                    modifier = Modifier.fillMaxSize()
                )
                if (overlayRunning) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = panelBackgroundColor,
                        contentColor = panelContentColor,
                        onClick = {}
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 18.dp, vertical = 20.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "已开启系统悬浮终端",
                                style = MaterialTheme.typography.titleMedium,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                                color = panelContentColor
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "软件内终端已隐藏，可切到软件外继续使用。点击右上角“关浮窗”后，会恢复当前页面内终端。",
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                                color = mutedTextColor
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectTerminalLogCard(
    entry: ProjectTerminalLogEntryUi,
    onCopy: () -> Unit,
    onExport: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = entry.command,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = buildString {
                        append(entry.workingDirectory)
                        append(" · ")
                        append(formatProjectTerminalTimestamp(entry.startedAtMillis))
                        entry.finishedAtMillis?.let { finished ->
                            append(" → ")
                            append(formatProjectTerminalTimestamp(finished))
                        }
                        entry.environmentModeName?.let { append(" · ").append(it) }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            TerminalStatusTag(
                text = entry.statusLabel,
                success = entry.commandStatus == ProjectTerminalCommandStatus.COMPLETED &&
                    (entry.exitCode == null || entry.exitCode == 0)
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AssistChip(
                onClick = onCopy,
                label = { Text("复制这条") }
            )
            AssistChip(
                onClick = onExport,
                label = { Text("导出这条") }
            )
        }
        SelectionContainer {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
                        shape = RoundedCornerShape(18.dp)
                    )
                    .padding(12.dp)
            ) {
                Text(
                    text = entry.output.ifBlank { "(无输出)" },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
        HorizontalDivider()
    }
}

@Composable
private fun ProjectTerminalClosedSessionSummaryCard(
    summary: ProjectTerminalClosedSessionSummaryUi,
    onCopy: () -> Unit,
    onExport: () -> Unit,
    onRestore: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "${summary.sessionLabel} · ${formatProjectTerminalTimestamp(summary.closedAtMillis)}",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = summary.workingDirectory,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = summary.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "归档日志 ${summary.logCount} 条"
                    + (summary.lastCommand?.let { " · 最近命令 $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            summary.lastLogPreview?.let { preview ->
                Text(
                    text = preview,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AssistChip(
                    onClick = onCopy,
                    label = { Text("复制摘要") }
                )
                AssistChip(
                    onClick = onExport,
                    label = { Text("导出摘要") }
                )
                AssistChip(
                    onClick = onRestore,
                    label = { Text("恢复会话") }
                )
            }
        }
    }
}

@Composable
private fun DangerAssistChip(
    onClick: () -> Unit,
    label: String,
    enabled: Boolean = true
) {
    AssistChip(
        onClick = onClick,
        enabled = enabled,
        label = { Text(label) },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.72f),
            labelColor = MaterialTheme.colorScheme.onErrorContainer,
            disabledContainerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
            disabledLabelColor = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.45f)
        )
    )
}

@Composable
private fun ProjectTerminalTimeFilterRow(
    selectedFilter: ProjectTerminalTimeFilter,
    onSelected: (ProjectTerminalTimeFilter) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ProjectTerminalTimeFilter.entries.forEach { filter ->
            FilterChip(
                selected = selectedFilter == filter,
                onClick = { onSelected(filter) },
                label = { Text(filter.label) }
            )
        }
    }
}

@Composable
private fun ProjectTerminalEmptyState(
    title: String,
    detail: String
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TerminalStatusTag(
    text: String,
    success: Boolean
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (success) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
        } else {
            MaterialTheme.colorScheme.error.copy(alpha = 0.14f)
        },
        contentColor = if (success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium
        )
    }
}

@Composable
private fun TerminalEnvironmentDiagnosticCard(
    diagnostic: ProjectTerminalEnvironmentDiagnostic,
    onCopy: () -> Unit
) {
    val surfaceColor = rememberMurongSurfaceColor()
    val concern = diagnostic.checks.firstOrNull {
        it.level != ProjectTerminalDiagnosticLevel.PASS
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
        shape = RoundedCornerShape(14.dp),
        color = surfaceColor.copy(alpha = 0.16f),
        border = BorderStroke(1.dp, surfaceColor.copy(alpha = 0.74f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = diagnostic.headline,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    color = when {
                        diagnostic.failureMessage != null || diagnostic.errorCount > 0 ->
                            MaterialTheme.colorScheme.error
                        diagnostic.warningCount > 0 -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.primary
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                TextButton(onClick = onCopy) {
                    Text("复制诊断")
                }
            }
            if (diagnostic.checks.isEmpty()) {
                Text(
                    text = "检查只读，不会安装、升级或修改软件包。",
                    style = MaterialTheme.typography.bodySmall,
                    color = rememberMurongMutedTextColor()
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    diagnostic.checks.forEach { check ->
                        TerminalDiagnosticTag(check = check)
                    }
                }
                Text(
                    text = concern?.let { it.label + "：" + it.detail }
                        ?: "命令、软件包依赖与共享存储均已通过检查。",
                    style = MaterialTheme.typography.bodySmall,
                    color = rememberMurongMutedTextColor(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun TerminalDiagnosticTag(check: ProjectTerminalEnvironmentCheck) {
    val color = when (check.level) {
        ProjectTerminalDiagnosticLevel.PASS -> MaterialTheme.colorScheme.primary
        ProjectTerminalDiagnosticLevel.WARNING -> MaterialTheme.colorScheme.tertiary
        ProjectTerminalDiagnosticLevel.ERROR -> MaterialTheme.colorScheme.error
    }
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = color.copy(alpha = 0.14f),
        contentColor = color
    ) {
        Text(
            text = check.label + " " + check.level.tagLabel,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1
        )
    }
}

private val ProjectTerminalDiagnosticLevel.tagLabel: String
    get() = when (this) {
        ProjectTerminalDiagnosticLevel.PASS -> "✓"
        ProjectTerminalDiagnosticLevel.WARNING -> "!"
        ProjectTerminalDiagnosticLevel.ERROR -> "×"
    }

@Composable
private fun TerminalToolbarButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    highlighted: Boolean = false
) {
    val surfaceColor = rememberMurongSurfaceColor()
    val contentColor = MaterialTheme.colorScheme.onSurface
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = MurongProjectSectionCardShape,
        border = BorderStroke(
            1.dp,
            if (highlighted) surfaceColor.copy(alpha = 0.95f) else surfaceColor.copy(alpha = 0.72f)
        ),
        color = when {
            !enabled -> MaterialTheme.colorScheme.background.copy(alpha = 0.42f)
            highlighted -> surfaceColor.copy(alpha = 0.28f)
            else -> MaterialTheme.colorScheme.background.copy(alpha = 0.82f)
        },
        contentColor = when {
            !enabled -> contentColor.copy(alpha = 0.36f)
            highlighted -> contentColor
            else -> contentColor.copy(alpha = 0.92f)
        }
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.titleSmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun TerminalTabItem(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit
) {
    val surfaceColor = rememberMurongSurfaceColor()
    val contentColor = MaterialTheme.colorScheme.onSurface
    Surface(
        shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp, bottomStart = 8.dp, bottomEnd = 8.dp),
        color = if (selected) surfaceColor.copy(alpha = 0.34f) else MaterialTheme.colorScheme.background.copy(alpha = 0.75f),
        contentColor = if (selected) contentColor else contentColor.copy(alpha = 0.68f),
        border = BorderStroke(1.dp, if (selected) surfaceColor.copy(alpha = 0.95f) else surfaceColor.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                modifier = Modifier.clickable(onClick = onSelect),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Surface(
                onClick = onClose,
                shape = RoundedCornerShape(6.dp),
                color = Color.Transparent,
                contentColor = if (selected) contentColor else contentColor.copy(alpha = 0.58f)
            ) {
                Text(
                    text = "x",
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun TerminalAccessoryKey(
    label: String,
    active: Boolean = false,
    onClick: () -> Unit
) {
    val surfaceColor = rememberMurongSurfaceColor()
    val contentColor = MaterialTheme.colorScheme.onSurface
    Surface(
        onClick = onClick,
        shape = MurongProjectInsetCardShape,
        color = if (active) surfaceColor.copy(alpha = 0.28f) else MaterialTheme.colorScheme.background.copy(alpha = 0.82f),
        contentColor = if (active) contentColor else contentColor.copy(alpha = 0.92f),
        border = BorderStroke(1.dp, if (active) surfaceColor.copy(alpha = 0.95f) else surfaceColor.copy(alpha = 0.58f))
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium
        )
    }
}

private fun buildProjectTerminalTemplates(
    repo: ProjectDetectedRepoUi?,
    workingDirectory: String
): List<ProjectTerminalTemplateUi> {
    val templates = mutableListOf(
        ProjectTerminalTemplateUi("当前目录", "pwd", ProjectTerminalTemplateCategory.BASIC),
        ProjectTerminalTemplateUi("列出文件", "ls -la", ProjectTerminalTemplateCategory.BASIC),
        ProjectTerminalTemplateUi("最近文件", "find . -maxdepth 2 | head -n 50", ProjectTerminalTemplateCategory.BASIC),
        ProjectTerminalTemplateUi("搜索 TODO", "grep -R \"TODO\" -n .", ProjectTerminalTemplateCategory.SEARCH),
        ProjectTerminalTemplateUi("搜索 FIXME", "grep -R \"FIXME\" -n .", ProjectTerminalTemplateCategory.SEARCH)
    )
    if (repo?.hasGitMetadata == true) {
        templates += listOf(
            ProjectTerminalTemplateUi("Git 状态", "git status --short --branch", ProjectTerminalTemplateCategory.GIT),
            ProjectTerminalTemplateUi("最近提交", "git log --oneline -n 10", ProjectTerminalTemplateCategory.GIT),
            ProjectTerminalTemplateUi("变更统计", "git diff --stat", ProjectTerminalTemplateCategory.GIT)
        )
    }
    if (repo?.hasGradleBuild == true || workingDirectory.endsWith("murongagent")) {
        templates += listOf(
            ProjectTerminalTemplateUi("Gradle 任务", "./gradlew tasks", ProjectTerminalTemplateCategory.BUILD),
            ProjectTerminalTemplateUi("Debug 构建", "./gradlew :app:assembleDebug", ProjectTerminalTemplateCategory.BUILD),
            ProjectTerminalTemplateUi("Release 安装", "./gradlew :app:installRelease", ProjectTerminalTemplateCategory.BUILD)
        )
    }
    if (repo?.hasPackageJson == true) {
        templates += listOf(
            ProjectTerminalTemplateUi("依赖安装", "npm install", ProjectTerminalTemplateCategory.BUILD),
            ProjectTerminalTemplateUi("开发模式", "npm run dev", ProjectTerminalTemplateCategory.BUILD),
            ProjectTerminalTemplateUi("生产构建", "npm run build", ProjectTerminalTemplateCategory.BUILD)
        )
    }
    return templates
}

private fun executeProjectTerminalCommand(
    workingDirectory: String,
    command: String
): ProjectTerminalLogEntryUi {
    if (!KeepShellPublic.checkRoot()) {
        return ProjectTerminalLogEntryUi(
            command = command,
            workingDirectory = workingDirectory,
            output = "当前设备未检测到可用 Root shell，无法执行命令。",
            exitCode = 1,
            timestampMillis = System.currentTimeMillis()
        )
    }
    val raw = KeepShellPublic.doCmdSync(
        buildProjectTerminalWrappedCommand(
            workingDirectory = workingDirectory,
            command = command
        )
    )
    val (output, exitCode) = parseProjectTerminalCommandOutput(raw)
    return ProjectTerminalLogEntryUi(
        command = command,
        workingDirectory = workingDirectory,
        output = output,
        exitCode = exitCode,
        timestampMillis = System.currentTimeMillis()
    )
}

private fun buildProjectTerminalWrappedCommand(
    workingDirectory: String,
    command: String
): String {
    val quotedDirectory = shellSingleQuote(workingDirectory)
    return """
        __murong_old_pwd="${'$'}(pwd)"
        __murong_exit_code=0
        if cd $quotedDirectory 2>/dev/null; then
            {
                $command
            } 2>&1
            __murong_exit_code=${'$'}?
            cd "${'$'}__murong_old_pwd" 2>/dev/null || true
        else
            echo "无法进入目录: $workingDirectory"
            __murong_exit_code=1
        fi
        printf '\n$PROJECT_TERMINAL_EXIT_MARKER%s\n' "${'$'}__murong_exit_code"
    """.trimIndent()
}

private fun parseProjectTerminalCommandOutput(raw: String): Pair<String, Int?> {
    val markerIndex = raw.lastIndexOf(PROJECT_TERMINAL_EXIT_MARKER)
    if (markerIndex < 0) return raw.trimEnd() to null
    val output = raw.substring(0, markerIndex).trimEnd()
    val exitCode = raw
        .substring(markerIndex + PROJECT_TERMINAL_EXIT_MARKER.length)
        .lineSequence()
        .firstOrNull()
        ?.trim()
        ?.toIntOrNull()
    return output to exitCode
}

private fun shellSingleQuote(raw: String): String = "'${raw.replace("'", "'\"'\"'")}'"

private fun formatProjectTerminalTimestamp(timestampMillis: Long): String {
    val formatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    return formatter.format(Date(timestampMillis))
}

private fun projectTerminalStartOfDayMillis(nowMillis: Long): Long {
    return Calendar.getInstance().apply {
        timeInMillis = nowMillis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}

private fun buildProjectTerminalLogText(entry: ProjectTerminalLogEntryUi): String {
    return buildString {
        append("命令: ")
        append(entry.command)
        append('\n')
        append("记录 ID: ")
        append(entry.commandId)
        append('\n')
        append("目录: ")
        append(entry.workingDirectory)
        append('\n')
        append("开始: ")
        append(formatProjectTerminalTimestamp(entry.startedAtMillis))
        append('\n')
        append("结束: ")
        append(entry.finishedAtMillis?.let(::formatProjectTerminalTimestamp) ?: "未完成")
        append('\n')
        entry.environmentModeName?.let { environment ->
            append("环境: ")
            append(environment)
            append('\n')
        }
        append("状态: ")
        append(entry.statusLabel)
        append('\n')
        entry.transcriptReference?.let { reference ->
            append("完整转录引用: ")
            append(reference)
            append('\n')
        }
        append('\n')
        append(entry.output.ifBlank { "(无输出)" })
    }
}

private fun buildProjectTerminalLogsText(logs: List<ProjectTerminalLogEntryUi>): String {
    return logs.joinToString("\n\n") { entry -> buildProjectTerminalLogText(entry) }
}

private fun buildProjectTerminalClosedSessionSummaryText(
    summary: ProjectTerminalClosedSessionSummaryUi
): String {
    return buildString {
        append("会话: ")
        append(summary.sessionLabel)
        append('\n')
        append("目录: ")
        append(summary.workingDirectory)
        append('\n')
        append("关闭时间: ")
        append(formatProjectTerminalTimestamp(summary.closedAtMillis))
        append('\n')
        append("归档日志: ")
        append(summary.logCount)
        append(" 条")
        summary.lastCommand?.takeIf { it.isNotBlank() }?.let {
            append('\n')
            append("最近命令: ")
            append(it)
        }
        summary.lastLogPreview?.takeIf { it.isNotBlank() }?.let {
            append('\n')
            append("最近输出: ")
            append(it)
        }
        append('\n')
        append('\n')
        append(summary.summary)
    }
}

private fun buildProjectTerminalClosedSessionSummariesText(
    summaries: List<ProjectTerminalClosedSessionSummaryUi>
): String {
    return summaries.joinToString("\n\n") { summary ->
        buildProjectTerminalClosedSessionSummaryText(summary)
    }
}

private fun exportProjectTerminalLogs(
    context: Context,
    projectPath: String,
    sessionLabel: String,
    logs: List<ProjectTerminalLogEntryUi>
): Result<File> {
    return runCatching {
        val exportDir = File(context.getExternalFilesDir(null), "terminal-exports").also { it.mkdirs() }
        if (!exportDir.exists() && !exportDir.mkdirs()) {
            throw IOException("无法创建导出目录")
        }
        val file = File(
            exportDir,
            "terminal-${sanitizeProjectTerminalExportName(sessionLabel)}-${projectPath.hashCode()}-${System.currentTimeMillis()}.txt"
        )
        file.writeText(buildProjectTerminalLogsText(logs))
        file
    }
}

private fun exportProjectTerminalSingleLog(
    context: Context,
    projectPath: String,
    sessionLabel: String,
    entry: ProjectTerminalLogEntryUi
): Result<File> {
    return runCatching {
        val exportDir = File(context.getExternalFilesDir(null), "terminal-exports").also { it.mkdirs() }
        if (!exportDir.exists() && !exportDir.mkdirs()) {
            throw IOException("无法创建导出目录")
        }
        val file = File(
            exportDir,
            "terminal-one-${sanitizeProjectTerminalExportName(sessionLabel)}-${projectPath.hashCode()}-${entry.timestampMillis}.txt"
        )
        file.writeText(buildProjectTerminalLogText(entry))
        file
    }
}

private fun exportProjectTerminalTranscript(
    context: Context,
    projectPath: String,
    sessionLabel: String,
    transcript: String
): Result<File> {
    return runCatching {
        val exportDir = File(context.getExternalFilesDir(null), "terminal-exports").also { it.mkdirs() }
        if (!exportDir.exists() && !exportDir.mkdirs()) {
            throw IOException("无法创建导出目录")
        }
        val file = File(
            exportDir,
            "transcript-${sanitizeProjectTerminalExportName(sessionLabel)}-${projectPath.hashCode()}-${System.currentTimeMillis()}.txt"
        )
        file.writeText(transcript)
        file
    }
}

private fun exportProjectTerminalClosedSessionSummary(
    context: Context,
    projectPath: String,
    sessionLabel: String,
    summary: ProjectTerminalClosedSessionSummaryUi
): Result<File> {
    return runCatching {
        val exportDir = File(context.getExternalFilesDir(null), "terminal-exports").also { it.mkdirs() }
        if (!exportDir.exists() && !exportDir.mkdirs()) {
            throw IOException("无法创建导出目录")
        }
        val file = File(
            exportDir,
            "closed-session-${sanitizeProjectTerminalExportName(sessionLabel)}-${projectPath.hashCode()}-${System.currentTimeMillis()}.txt"
        )
        file.writeText(buildProjectTerminalClosedSessionSummaryText(summary))
        file
    }
}

private fun exportProjectTerminalClosedSessionSummaries(
    context: Context,
    projectPath: String,
    summaries: List<ProjectTerminalClosedSessionSummaryUi>
): Result<File> {
    return runCatching {
        val exportDir = File(context.getExternalFilesDir(null), "terminal-exports").also { it.mkdirs() }
        if (!exportDir.exists() && !exportDir.mkdirs()) {
            throw IOException("无法创建导出目录")
        }
        val file = File(
            exportDir,
            "closed-session-all-${projectPath.hashCode()}-${System.currentTimeMillis()}.txt"
        )
        file.writeText(buildProjectTerminalClosedSessionSummariesText(summaries))
        file
    }
}

private fun sanitizeProjectTerminalExportName(raw: String): String {
    return raw.ifBlank { "session" }
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
        .take(48)
        .ifBlank { "session" }
}

private fun buildProjectTerminalTranscriptSummary(transcript: String?): String {
    val normalizedLines = transcript
        .orEmpty()
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toList()
    if (normalizedLines.isEmpty()) {
        return "关闭前没有可用 transcript。"
    }
    return normalizedLines
        .takeLast(6)
        .joinToString("  |  ")
        .take(280)
        .ifBlank { "关闭前没有可用 transcript。" }
}

private fun buildProjectTerminalUniqueSessionLabel(
    baseLabel: String,
    existingTabs: List<ProjectTerminalSessionTabUi>
): String {
    val normalizedBase = baseLabel.trim().ifBlank { "终端" }
    val existingCount = existingTabs.count {
        it.label == normalizedBase || it.label.startsWith("$normalizedBase ")
    }
    return if (existingCount == 0) normalizedBase else "$normalizedBase ${existingCount + 1}"
}

private fun trimProjectTerminalLogsForSession(
    logs: MutableList<ProjectTerminalLogEntryUi>,
    sessionId: String
) {
    var keptForSession = 0
    for (index in logs.lastIndex downTo 0) {
        if (logs[index].sessionId == sessionId) {
            keptForSession += 1
            if (keptForSession > PROJECT_TERMINAL_LOG_LIMIT_PER_SESSION) {
                logs.removeAt(index)
            }
        }
    }
}

private val PROJECT_TERMINAL_STORE_JSON = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

private class ProjectTerminalUiSettingsStore(
    context: Context,
    projectPath: String
) {
    private val prefs = context.getSharedPreferences("murong_project_terminal", Context.MODE_PRIVATE)
    private val keyPrefix = "project_${projectPath.hashCode()}_"

    fun read(): ProjectTerminalUiSettings {
        val newFontSizeKey = "${keyPrefix}font_size_v2"
        val savedFontSize = if (prefs.contains(newFontSizeKey)) {
            prefs.getInt(newFontSizeKey, 18)
        } else {
            (prefs.getInt("${keyPrefix}font_size", 28) / 2).coerceAtLeast(14)
        }
        return ProjectTerminalUiSettings(
            themeName = prefs.getString("${keyPrefix}theme", ProjectTerminalThemePreset.MIDNIGHT.name)
                ?: ProjectTerminalThemePreset.MIDNIGHT.name,
            fontSize = savedFontSize.coerceIn(14, 30),
            environmentModeName = prefs.getString(
                "${keyPrefix}environment_mode",
                ProjectTerminalEnvironmentMode.TOOLCHAIN.name
            ) ?: ProjectTerminalEnvironmentMode.TOOLCHAIN.name,
            settingsExpanded = prefs.getBoolean("${keyPrefix}settings_expanded", true),
            floatingModeEnabled = prefs.getBoolean("${keyPrefix}floating_mode_enabled", false),
            floatingWindowRatio = prefs.getFloat("${keyPrefix}floating_window_ratio", 0.35f)
                .coerceIn(0.25f, 0.5f)
        )
    }

    fun write(settings: ProjectTerminalUiSettings) {
        prefs.edit()
            .putString("${keyPrefix}theme", settings.themeName)
            .putInt("${keyPrefix}font_size_v2", settings.fontSize.coerceIn(14, 30))
            .putString("${keyPrefix}environment_mode", settings.environmentModeName)
            .putBoolean("${keyPrefix}settings_expanded", settings.settingsExpanded)
            .putBoolean("${keyPrefix}floating_mode_enabled", settings.floatingModeEnabled)
            .putFloat(
                "${keyPrefix}floating_window_ratio",
                settings.floatingWindowRatio.coerceIn(0.25f, 0.5f)
            )
            .apply()
    }
}

private class ProjectTerminalHistoryStore(
    context: Context,
    projectPath: String
) {
    private val prefs = context.getSharedPreferences("murong_project_terminal", Context.MODE_PRIVATE)
    private val key = "project_${projectPath.hashCode()}_history"

    fun read(): PersistedProjectTerminalStore {
        val raw = prefs.getString(key, null).orEmpty()
        if (raw.isBlank()) return PersistedProjectTerminalStore()
        return runCatching {
            PROJECT_TERMINAL_STORE_JSON.decodeFromString<PersistedProjectTerminalStore>(raw)
        }.getOrDefault(PersistedProjectTerminalStore())
    }

    fun write(store: PersistedProjectTerminalStore) {
        prefs.edit()
            .putString(key, PROJECT_TERMINAL_STORE_JSON.encodeToString(store))
            .apply()
    }
}

private fun buildProjectTerminalSessionLabel(
    workingDirectory: String,
    currentProjectPath: String?,
    availableRepos: List<ProjectDetectedRepoUi>,
    existingTabs: List<ProjectTerminalSessionTabUi>
): String {
    val baseLabel = availableRepos
        .firstOrNull { it.rootPath == workingDirectory }
        ?.let { repo ->
            if (repo.rootPath == currentProjectPath) "工作区" else repo.displayName
        }
        ?: File(workingDirectory).name.takeIf { it.isNotBlank() }
        ?: "终端"
    return buildProjectTerminalUniqueSessionLabel(baseLabel, existingTabs)
}
