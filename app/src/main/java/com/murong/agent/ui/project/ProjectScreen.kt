@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.murong.agent.ui.project

import android.content.ClipData
import android.net.Uri
import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.murong.agent.core.config.GlobalMemory
import com.murong.agent.core.config.GlobalRule
import com.murong.agent.core.config.GlobalSkill
import com.murong.agent.core.config.ProjectToolPreferences
import com.murong.agent.core.config.SessionProjectConfig
import com.murong.agent.core.config.ProjectWorkflowRiskLevel
import com.murong.agent.core.config.ProjectWorkflowType
import com.murong.agent.core.config.ProviderConfig
import com.murong.agent.core.config.ToolApprovalMode
import com.murong.agent.core.config.WorkflowFailureFallbackMode
import com.murong.agent.core.loop.ProjectKnowledgeSnapshotUi
import com.murong.agent.core.loop.SessionSummary
import com.murong.agent.ui.toSessionReadinessPresentation
import com.murong.agent.ui.buildApprovalModeFollowGlobalOptionPresentation
import com.murong.agent.ui.buildApprovalModeHostPresentation
import com.murong.agent.ui.buildApprovalModeOptionPresentations
import com.murong.agent.core.provider.ChatMessage
import com.murong.agent.core.provider.ChatRequest
import com.murong.agent.core.provider.ProviderRegistry
import com.murong.agent.common.shell.KeepShellPublic
import com.murong.agent.common.utils.RootFile
import com.murong.agent.ui.ProjectSecondaryChromeState
import com.murong.agent.ui.MurongDialog
import com.murong.agent.ui.MurongGlassSurface
import com.murong.agent.ui.MurongInfoCard
import com.murong.agent.ui.MurongLargeDialogScaffold
import com.murong.agent.ui.MurongPopupSurface
import com.murong.agent.ui.MurongSecondaryPageFrame
import com.murong.agent.ui.MurongPrimaryPageSurface
import com.murong.agent.ui.MurongTagButton
import com.murong.agent.ui.MemoryDraftImportCard
import com.murong.agent.ui.RuleDraftImportCard
import com.murong.agent.ui.SkillDraftImportCard
import com.murong.agent.ui.rememberMurongAccentColor
import com.murong.agent.ui.rememberMurongBottomBarScrollPadding
import com.murong.agent.ui.rememberMurongChromeColor
import com.murong.agent.ui.rememberMurongMutedTextColor
import com.murong.agent.ui.rememberMurongSurfaceColor
import com.murong.agent.ui.rememberMurongSurfaceTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.put
import java.io.File
import java.nio.charset.MalformedInputException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit


private data class ProjectEditorDerivedState(
    val diagnostics: List<ProjectEditorDiagnostic> = emptyList(),
    val conflictBlocks: List<ProjectGitConflictBlock> = emptyList(),
    val foldRegions: List<ProjectFoldRegion> = emptyList(),
    val outlineEntries: List<ProjectOutlineEntry> = emptyList(),
    val lspState: ProjectEditorLspState = ProjectEditorLspState(),
    val lspSessionState: ProjectEditorLspSessionState = ProjectEditorLspSessionState()
)

private data class ProjectEditorDerivedSeed(
    val sourceText: String,
    val language: String,
    val derivedState: ProjectEditorDerivedState
)

private fun findOwningRepo(
    filePath: String,
    repos: List<ProjectDetectedRepoUi>
): ProjectDetectedRepoUi? {
    return repos
        .filter { repo ->
            filePath == repo.rootPath || filePath.startsWith(repo.rootPath + File.separator)
        }
        .maxByOrNull { it.rootPath.length }
}

@Composable
private fun ProjectScreenLargeDialog(
    title: String,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    val tokens = rememberMurongSurfaceTokens()
    LaunchedEffect(title, subtitle, tokens.popupContainerColor, tokens.popupGlassColor, tokens.popupBlurRadius) {
    }
    MurongDialog(onDismissRequest = onDismissRequest) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            MurongPopupSurface(
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp, bottomStart = 24.dp, bottomEnd = 24.dp),
                forceOpaque = true,
                modifier = modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.74f)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
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
                                text = title,
                                style = MaterialTheme.typography.titleMedium
                            )
                            subtitle?.takeIf { it.isNotBlank() }?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            content = actions
                        )
                    }
                    HorizontalDivider()
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        content = content
                    )
                }
            }
        }
    }
}

@Composable
private fun ProjectScreenPopupDialog(
    title: String,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    val tokens = rememberMurongSurfaceTokens()
    LaunchedEffect(title, subtitle, tokens.popupContainerColor, tokens.popupGlassColor, tokens.popupBlurRadius) {
    }
    MurongDialog(onDismissRequest = onDismissRequest) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            MurongPopupSurface(
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp, bottomStart = 24.dp, bottomEnd = 24.dp),
                forceOpaque = true,
                modifier = modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.56f)
                    .heightIn(min = 220.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
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
                                text = title,
                                style = MaterialTheme.typography.titleMedium
                            )
                            subtitle?.takeIf { it.isNotBlank() }?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            content = actions
                        )
                    }
                    HorizontalDivider()
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        content = content
                    )
                }
            }
        }
    }
}

@Composable
internal fun ProjectScreen(
    config: ProviderConfig,
    currentProjectPath: String?,
    currentProjectScopePath: String?,
    projectKnowledgeDraftPaths: List<String>,
    projectKnowledgeSnapshots: List<ProjectKnowledgeSnapshotUi>,
    projectRules: List<GlobalRule>,
    projectMemories: List<GlobalMemory>,
    projectSkills: List<GlobalSkill>,
    projectToolPreferences: ProjectToolPreferences?,
    repoScopedConfigs: Map<String, SessionProjectConfig>,
    selectedViewerTaskRepository: ProjectGitHubRepoRef?,
    mcpToolNames: List<String>,
    sessions: List<SessionSummary>,
    onNewTask: () -> Unit,
    onOpenChat: () -> Unit,
    onProjectScopeChanged: (String?) -> Unit,
    onUpdateProjectConfig: (String?, List<GlobalRule>?, List<GlobalMemory>?, List<GlobalSkill>?) -> Unit,
    onUpdateProjectToolPreferences: (String?, ProjectToolPreferences?) -> Unit,
    onUpdateSelectedViewerTaskRepository: (ProjectGitHubRepoRef?) -> Unit,
    onUpdateProjectKnowledgeDraftPaths: (List<String>) -> Unit,
    onSaveProjectKnowledgeSnapshot: (String, List<String>) -> Unit,
    onRenameProjectKnowledgeSnapshot: (String, String) -> Unit,
    onApplyProjectKnowledgeSnapshot: (String) -> Unit,
    onDeleteProjectKnowledgeSnapshot: (String) -> Unit,
    onProjectSecondaryHostBridgeStateChanged: (ProjectSecondaryHostBridgeState) -> Unit = {},
    projectSecondaryHostBackProgress: Float = 0f,
    editorMenuActionSignal: Int = 0,
    editorMenuAction: ProjectEditorMenuAction? = null
) {
    val workspaceRoot = remember(currentProjectPath) {
        currentProjectPath
            ?.takeIf { File(it).isDirectory || RootFile.dirExists(it) }
            ?.let(::File)
    }
    val detectedRepos by produceState(
        initialValue = emptyList<ProjectDetectedRepoUi>(),
        key1 = workspaceRoot?.absolutePath
    ) {
        value = workspaceRoot?.let { root ->
            withContext(Dispatchers.IO) { detectProjectGitRepositories(root) }
        }.orEmpty()
    }
    var selectedRepoRoot by rememberSaveable(currentProjectPath) { mutableStateOf<String?>(null) }
    fun selectRepoRoot(target: String?) {
        selectedRepoRoot = target
        onProjectScopeChanged(target)
    }
    LaunchedEffect(currentProjectPath, currentProjectScopePath, detectedRepos) {
        val selectedStillValid = selectedRepoRoot != null && detectedRepos.any { it.rootPath == selectedRepoRoot }
        if (!selectedStillValid) {
            selectedRepoRoot = currentProjectScopePath
                ?.takeIf { scope -> detectedRepos.any { it.rootPath == scope } }
                ?: detectedRepos.firstOrNull { it.isWorkspaceRoot }?.rootPath
                ?: detectedRepos.firstOrNull()?.rootPath
                ?: workspaceRoot?.absolutePath
        }
    }
    var primaryNavigationState by remember(currentProjectPath) {
        mutableStateOf(ProjectPrimaryNavigationState())
    }
    val selectedTab = primaryNavigationState.selectedTab
    val navigationTargetTab = primaryNavigationState.navigationTargetTab
    var secondaryRegistryState by remember(currentProjectPath) {
        mutableStateOf(ProjectSecondaryRegistryState())
    }
    val editorPageChromeState = secondaryRegistryState.editorChromeState
    val gitPageChromeState = secondaryRegistryState.gitChromeState
    val secondaryHostBackProgress = secondaryRegistryState.backProgress
    val editorSecondaryCloseRequest = secondaryRegistryState.editorCloseRequest
    val gitSecondaryCloseRequest = secondaryRegistryState.gitCloseRequest
    val projectPagerState = rememberPagerState(
        initialPage = selectedTab.ordinal,
        pageCount = { ProjectPrimaryTab.entries.size }
    )
    val visibleProjectTab = ProjectPrimaryTab.entries[projectPagerState.currentPage]
    val settledProjectTab = ProjectPrimaryTab.entries[projectPagerState.settledPage]
    val secondaryHostState = resolveProjectSecondaryHostState(
        selectedTab = selectedTab,
        visibleTab = visibleProjectTab,
        settledTab = settledProjectTab,
        editorChromeState = editorPageChromeState,
        gitChromeState = gitPageChromeState,
        editorCloseRequest = editorSecondaryCloseRequest,
        gitCloseRequest = gitSecondaryCloseRequest
    )
    val primaryNavigationHostState = resolveProjectPrimaryNavigationHostState(
        navigationState = primaryNavigationState,
        pagerCurrentTab = visibleProjectTab,
        pagerSettledTab = settledProjectTab,
        secondaryHostState = secondaryHostState
    )
    val activeProjectSecondaryState = secondaryHostState.activeChromeState
    val secondaryHostRuntimeState = remember(
        secondaryHostState,
        secondaryHostBackProgress
    ) {
        resolveProjectSecondaryHostRuntimeState(
            hostState = secondaryHostState,
            backProgress = secondaryHostBackProgress
        )
    }
    val secondaryHostBackProgressBucket = if (secondaryHostBackProgress > 0f) {
        (secondaryHostBackProgress * 10).toInt().coerceIn(0, 10)
    } else {
        -1
    }

    fun dispatchSecondaryRegistryAction(action: ProjectSecondaryRegistryAction) {
        secondaryRegistryState = reduceProjectSecondaryRegistryState(
            state = secondaryRegistryState,
            action = action
        )
    }

    fun dispatchPrimaryNavigationAction(action: ProjectPrimaryNavigationAction) {
        primaryNavigationState = reduceProjectPrimaryNavigationState(
            state = primaryNavigationState,
            action = action
        )
    }

    val bottomBarScrollPadding = rememberMurongBottomBarScrollPadding()
    val chromeColor = rememberMurongChromeColor()
    val mutedTextColor = rememberMurongMutedTextColor()
    val activeConfigScopePath = selectedRepoRoot ?: currentProjectPath
    val activeProjectConfig = remember(
        activeConfigScopePath,
        repoScopedConfigs,
        projectRules,
        projectMemories,
        projectSkills,
        projectToolPreferences
    ) {
        activeConfigScopePath?.let(repoScopedConfigs::get) ?: SessionProjectConfig(
            projectRules = projectRules,
            projectMemories = projectMemories,
            projectSkills = projectSkills,
            projectToolPreferences = projectToolPreferences
        )
    }
    LaunchedEffect(primaryNavigationHostState.scrollTargetTab) {
        val targetTab = primaryNavigationHostState.scrollTargetTab ?: return@LaunchedEffect
        if (projectPagerState.currentPage != targetTab.ordinal) {
            projectPagerState.scrollToPage(targetTab.ordinal)
        }
    }
    LaunchedEffect(primaryNavigationHostState.shouldConsumeNavigationTarget) {
        if (primaryNavigationHostState.shouldConsumeNavigationTarget) {
            dispatchPrimaryNavigationAction(ProjectPrimaryNavigationAction.ConsumeNavigationTarget)
        }
    }
    LaunchedEffect(primaryNavigationHostState.settledTabToSync) {
        primaryNavigationHostState.settledTabToSync?.let { settledTab ->
            dispatchPrimaryNavigationAction(
                ProjectPrimaryNavigationAction.SyncSettledTab(settledTab)
            )
        }
    }
    LaunchedEffect(secondaryHostRuntimeState.bridgeState) {
        onProjectSecondaryHostBridgeStateChanged(secondaryHostRuntimeState.bridgeState)
    }
    LaunchedEffect(secondaryRegistryState.command, secondaryHostState.activeCloseRequest) {
        if (secondaryRegistryState.command != ProjectSecondaryHostCommand.CLOSE_ACTIVE_SECONDARY) {
            return@LaunchedEffect
        }
        val closeRequest = secondaryHostState.activeCloseRequest ?: return@LaunchedEffect
        closeRequest.invoke()
        dispatchSecondaryRegistryAction(ProjectSecondaryRegistryAction.ConsumeCommand)
    }

    LaunchedEffect(secondaryHostRuntimeState.shouldResetBackProgress) {
        if (secondaryHostRuntimeState.shouldResetBackProgress) {
            dispatchSecondaryRegistryAction(ProjectSecondaryRegistryAction.UpdateBackProgress(0f))
        }
    }
    LaunchedEffect(
        secondaryHostBackProgressBucket,
        secondaryHostState.owner,
        secondaryHostState.backProgressMode,
        secondaryHostState.isActive
    ) {
        if (secondaryHostBackProgressBucket < 0) return@LaunchedEffect
    }
    BackHandler(enabled = secondaryHostRuntimeState.canHandleHostBackGesture) {
        dispatchSecondaryRegistryAction(
            ProjectSecondaryRegistryAction.RequestCloseActiveSecondary
        )
    }
    PredictiveBackHandler(enabled = secondaryHostRuntimeState.canHandleHostBackGesture) { progress ->
        try {
            progress.collect { backEvent ->
                dispatchSecondaryRegistryAction(
                    ProjectSecondaryRegistryAction.UpdateBackProgress(backEvent.progress)
                )
            }
            dispatchSecondaryRegistryAction(ProjectSecondaryRegistryAction.UpdateBackProgress(1f))
            dispatchSecondaryRegistryAction(
                ProjectSecondaryRegistryAction.RequestCloseActiveSecondary
            )
        } catch (_: CancellationException) {
            dispatchSecondaryRegistryAction(ProjectSecondaryRegistryAction.UpdateBackProgress(0f))
        } finally {
            dispatchSecondaryRegistryAction(ProjectSecondaryRegistryAction.UpdateBackProgress(0f))
        }
    }
    MurongPrimaryPageSurface(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentPadding = PaddingValues(vertical = 6.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (primaryNavigationHostState.showPrimaryChrome) {
                ProjectPrimaryChrome(
                    currentProjectPath = currentProjectPath,
                    selectedTaskRepository = selectedViewerTaskRepository,
                    detectedRepos = detectedRepos,
                    mutedTextColor = mutedTextColor,
                    chromeColor = chromeColor,
                    selectedTab = selectedTab,
                    onSelectTab = { tab ->
                        dispatchPrimaryNavigationAction(
                            ProjectPrimaryNavigationAction.SelectTab(tab)
                        )
                    },
                    onPrimaryAction = onNewTask
                )
            }
            HorizontalPager(
                state = projectPagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                userScrollEnabled = primaryNavigationHostState.userScrollEnabled
            ) { page ->
                when (ProjectPrimaryTab.entries[page]) {
                    ProjectPrimaryTab.EDITOR -> ProjectEditorSection(
                        config = config,
                        currentProjectPath = currentProjectPath,
                        selectedTaskRepository = selectedViewerTaskRepository,
                        detectedRepos = detectedRepos,
                        selectedRepoRoot = selectedRepoRoot,
                        onSelectRepoRoot = ::selectRepoRoot,
                        sessions = sessions,
                        onOpenChat = onOpenChat,
                        onNewTask = onNewTask,
                        onProjectSecondaryPageChanged = { chromeState ->
                            dispatchSecondaryRegistryAction(
                                ProjectSecondaryRegistryAction.UpdateEditorChromeState(chromeState)
                            )
                        },
                        onRegisterSecondaryCloseRequest = { closeRequest ->
                            dispatchSecondaryRegistryAction(
                                ProjectSecondaryRegistryAction.RegisterEditorCloseRequest(closeRequest)
                            )
                        },
                        selectedPrimaryTab = selectedTab,
                        onSelectPrimaryTab = { tab ->
                            dispatchPrimaryNavigationAction(
                                ProjectPrimaryNavigationAction.SelectTab(tab)
                            )
                        },
                        onOpenSelectedTaskRepository = {
                            dispatchPrimaryNavigationAction(
                                ProjectPrimaryNavigationAction.SelectTab(ProjectPrimaryTab.GIT)
                            )
                        },
                        showOuterPrimaryChrome = primaryNavigationHostState.showPrimaryChrome,
                        primaryChromeColor = chromeColor,
                        primaryMutedTextColor = mutedTextColor,
                        projectSecondaryBackProgress = projectSecondaryHostBackProgress,
                        editorMenuActionSignal = editorMenuActionSignal,
                        editorMenuAction = editorMenuAction
                    )

                    ProjectPrimaryTab.CONFIG -> ProjectConfigSection(
                        config = config,
                        scopeLabel = detectedRepos.firstOrNull { it.rootPath == activeConfigScopePath }?.let { repo ->
                            if (repo.isWorkspaceRoot) {
                                "当前作用域：工作区根仓库"
                            } else {
                                "当前作用域：${repo.relativePath}"
                            }
                        } ?: "当前作用域：整个工作区",
                        projectRules = activeProjectConfig.projectRules,
                        projectMemories = activeProjectConfig.projectMemories,
                        projectSkills = activeProjectConfig.projectSkills,
                        projectToolPreferences = activeProjectConfig.projectToolPreferences,
                        onUpdateProjectConfig = { rules, memories, skills ->
                            onUpdateProjectConfig(activeConfigScopePath, rules, memories, skills)
                        },
                        onUpdateProjectToolPreferences = { preferences ->
                            onUpdateProjectToolPreferences(activeConfigScopePath, preferences)
                        }
                    )

                    ProjectPrimaryTab.TERMINAL -> ProjectTerminalSection(
                        currentProjectPath = currentProjectPath,
                        detectedRepos = detectedRepos,
                        selectedRepoRoot = selectedRepoRoot,
                        onSelectRepoRoot = ::selectRepoRoot
                    )

                    ProjectPrimaryTab.GIT -> ProjectGitSection(
                        config = config,
                        currentProjectPath = currentProjectPath,
                        detectedRepos = detectedRepos,
                        selectedRepoRoot = selectedRepoRoot,
                        onSelectRepoRoot = ::selectRepoRoot,
                        selectedViewerTaskRepository = selectedViewerTaskRepository,
                        draftPathCount = projectKnowledgeDraftPaths.size,
                        snapshotCount = projectKnowledgeSnapshots.size,
                        mcpToolCount = mcpToolNames.size,
                        onProjectSecondaryPageChanged = { chromeState ->
                            dispatchSecondaryRegistryAction(
                                ProjectSecondaryRegistryAction.UpdateGitChromeState(chromeState)
                            )
                        },
                        onRegisterSecondaryCloseRequest = { closeRequest ->
                            dispatchSecondaryRegistryAction(
                                ProjectSecondaryRegistryAction.RegisterGitCloseRequest(closeRequest)
                            )
                        },
                        selectedPrimaryTab = selectedTab,
                        onSelectPrimaryTab = { tab ->
                            dispatchPrimaryNavigationAction(
                                ProjectPrimaryNavigationAction.SelectTab(tab)
                            )
                        },
                        onUpdateSelectedViewerTaskRepository = onUpdateSelectedViewerTaskRepository,
                        primaryChromeColor = chromeColor,
                        primaryMutedTextColor = mutedTextColor,
                        projectSecondaryBackProgress = projectSecondaryHostBackProgress,
                        onProjectSecondaryBackProgressChanged = { progress ->
                            dispatchSecondaryRegistryAction(
                                ProjectSecondaryRegistryAction.UpdateBackProgress(progress)
                            )
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ProjectPrimaryChrome(
    currentProjectPath: String?,
    selectedTaskRepository: ProjectGitHubRepoRef? = null,
    detectedRepos: List<ProjectDetectedRepoUi>,
    mutedTextColor: Color,
    chromeColor: Color,
    selectedTab: ProjectPrimaryTab,
    onSelectTab: (ProjectPrimaryTab) -> Unit,
    onPrimaryAction: (() -> Unit)? = null
) {
    MurongGlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        shape = RoundedCornerShape(24.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "项目工作区",
                    style = MaterialTheme.typography.labelMedium,
                    color = mutedTextColor
                )
                Text(
                    text = currentProjectPath?.takeIf { it.isNotBlank() } ?: "还没选择项目文件夹",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (currentProjectPath.isNullOrBlank()) {
                    selectedTaskRepository?.let { repo ->
                        Text(
                            text = "当前任务仓库: ${repo.owner}/${repo.repo}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (detectedRepos.isNotEmpty()) {
                    val gitRepoCount = detectedRepos.count { it.hasGitMetadata }
                    Text(
                        text = "已识别 ${detectedRepos.size} 个项目根目录，其中 $gitRepoCount 个是 Git 仓库",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            onPrimaryAction?.let { action ->
                OutlinedButton(onClick = action) {
                    Text("选择文件夹")
                }
            }
        }
    }
    PrimaryScrollableTabRow(
        selectedTabIndex = selectedTab.ordinal,
        containerColor = chromeColor.copy(alpha = 0.32f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        divider = {}
    ) {
        ProjectPrimaryTab.entries.forEach { tab ->
            Tab(
                selected = selectedTab == tab,
                onClick = { onSelectTab(tab) },
                selectedContentColor = MaterialTheme.colorScheme.onSurface,
                unselectedContentColor = mutedTextColor,
                text = { Text(tab.label) }
            )
        }
    }
}

@Composable
private fun ProjectEditorSection(
    config: ProviderConfig,
    currentProjectPath: String?,
    selectedTaskRepository: ProjectGitHubRepoRef?,
    detectedRepos: List<ProjectDetectedRepoUi>,
    selectedRepoRoot: String?,
    onSelectRepoRoot: (String) -> Unit,
    sessions: List<SessionSummary>,
    onOpenChat: () -> Unit,
    onNewTask: () -> Unit,
    onProjectSecondaryPageChanged: (ProjectSecondaryChromeState) -> Unit,
    onRegisterSecondaryCloseRequest: (((() -> Unit)?) -> Unit),
    selectedPrimaryTab: ProjectPrimaryTab,
    onSelectPrimaryTab: (ProjectPrimaryTab) -> Unit,
    onOpenSelectedTaskRepository: () -> Unit,
    showOuterPrimaryChrome: Boolean,
    primaryChromeColor: Color,
    primaryMutedTextColor: Color,
    projectSecondaryBackProgress: Float,
    editorMenuActionSignal: Int,
    editorMenuAction: ProjectEditorMenuAction?
) {
    val scope = rememberCoroutineScope()
    val bottomBarScrollPadding = rememberMurongBottomBarScrollPadding()
    val editorSurfaceColor = rememberMurongSurfaceColor()
    val editorChromeColor = rememberMurongChromeColor()
    val editorMutedTextColor = rememberMurongMutedTextColor()
    val editorBackgroundColor = MaterialTheme.colorScheme.background.copy(alpha = 0.96f)
    val projectRoot = remember(currentProjectPath) {
        currentProjectPath
            ?.takeIf { File(it).isDirectory || RootFile.dirExists(it) }
            ?.let(::File)
    }
    val detectedRepoRoots = remember(detectedRepos) { detectedRepos.map { it.rootPath }.toSet() }
    val entriesByDir = remember(currentProjectPath) { mutableStateMapOf<String, List<ProjectTreeEntry>>() }
    var currentRepoViewOnly by rememberSaveable(currentProjectPath) { mutableStateOf(false) }
    var expandedDirs by rememberSaveable(
        currentProjectPath,
        stateSaver = listSaver<Set<String>, String>(
            save = { value -> value.toList() },
            restore = { restored -> restored.toSet() }
        )
    ) {
        mutableStateOf(projectRoot?.absolutePath?.let { setOf(it) } ?: emptySet())
    }
    var selectedFilePath by remember(currentProjectPath) { mutableStateOf<String?>(null) }
    var loadedContent by remember(currentProjectPath) { mutableStateOf("") }
    var editorValue by remember(currentProjectPath) { mutableStateOf(TextFieldValue("")) }
    var editorMode by remember(currentProjectPath) { mutableStateOf(ProjectEditorMode.EDIT) }
    var isTreeLoading by remember(currentProjectPath) { mutableStateOf(false) }
    var isFileLoading by remember(currentProjectPath) { mutableStateOf(false) }
    var isSaving by remember(currentProjectPath) { mutableStateOf(false) }
    var editorError by remember(currentProjectPath) { mutableStateOf<String?>(null) }
    var showUnsavedChangesDialog by remember(currentProjectPath) { mutableStateOf(false) }
    var reloadVersion by remember(currentProjectPath) { mutableStateOf(0) }
    var searchQuery by remember(currentProjectPath) { mutableStateOf("") }
    var searchResult by remember(currentProjectPath) { mutableStateOf<ProjectSearchResultUi?>(null) }
    var remoteSearchResults by remember(currentProjectPath, selectedTaskRepository) {
        mutableStateOf<List<ProjectGitHubGlobalSearchResultUi>>(emptyList())
    }
    var isSearching by remember(currentProjectPath) { mutableStateOf(false) }
    var focusedSearchLine by remember(currentProjectPath) { mutableStateOf<Int?>(null) }
    var focusedSearchQuery by remember(currentProjectPath) { mutableStateOf<String?>(null) }
    var editorSearchQuery by remember(currentProjectPath) { mutableStateOf("") }
    var editorReplaceQuery by remember(currentProjectPath) { mutableStateOf("") }
    var editorSearchRequest by remember(currentProjectPath) { mutableStateOf(ProjectEditorSearchRequest()) }
    var showSearchReplaceDialog by remember(currentProjectPath) { mutableStateOf(false) }
    var showLineReplaceDialog by remember(currentProjectPath) { mutableStateOf(false) }
    var lineReplaceDraft by remember(currentProjectPath) { mutableStateOf("") }
    var showLanguageDialog by remember(currentProjectPath) { mutableStateOf(false) }
    var editorWordWrapEnabled by rememberSaveable(currentProjectPath) { mutableStateOf(true) }
    var editorTextFieldFocused by remember(currentProjectPath) { mutableStateOf(false) }
    var editorFocusClearPending by remember(currentProjectPath) { mutableStateOf(false) }
    var editorFocusClearSignal by remember(currentProjectPath) { mutableStateOf(0) }
    var showOutlineDialog by remember(currentProjectPath) { mutableStateOf(false) }
    var showDiagnosticsDialog by remember(currentProjectPath) { mutableStateOf(false) }
    var showConflictResolverDialog by remember(currentProjectPath) { mutableStateOf(false) }
    var languageOverride by remember(currentProjectPath) { mutableStateOf<String?>(null) }
    var isAiCompleting by remember(currentProjectPath) { mutableStateOf(false) }
    var showAiCompletionDialog by remember(currentProjectPath) { mutableStateOf(false) }
    var aiCompletionCandidate by remember(currentProjectPath) { mutableStateOf<ProjectAiCompletionCandidateUi?>(null) }
    var lastHandledEditorMenuSignal by rememberSaveable(currentProjectPath) { mutableStateOf(0) }
    var editorDerivedSeed by remember(currentProjectPath) { mutableStateOf<ProjectEditorDerivedSeed?>(null) }
    val collapsedFoldRegions = remember(currentProjectPath, selectedFilePath) { mutableStateMapOf<Int, Boolean>() }
    val undoStack = remember(currentProjectPath) { mutableStateListOf<TextFieldValue>() }
    val redoStack = remember(currentProjectPath) { mutableStateListOf<TextFieldValue>() }
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val focusManager = LocalFocusManager.current
    val isRemoteTaskMode = projectRoot == null && selectedTaskRepository != null
    val remoteEntriesByDir = remember(currentProjectPath, selectedTaskRepository) {
        mutableStateMapOf<String, List<ProjectTreeEntry>>()
    }
    val remoteEntryInfoByPath = remember(currentProjectPath, selectedTaskRepository) {
        mutableStateMapOf<String, ProjectGitHubRemoteEntryUi>()
    }
    var remoteExpandedDirs by rememberSaveable(
        currentProjectPath,
        selectedTaskRepository,
        stateSaver = listSaver<Set<String>, String>(
            save = { value -> value.toList() },
            restore = { restored -> restored.toSet() }
        )
    ) {
        mutableStateOf(setOf(""))
    }
    var remoteTaskRepoState by remember(currentProjectPath, selectedTaskRepository) {
        mutableStateOf(ProjectGitHubRemoteBrowserState.empty(selectedTaskRepository))
    }
    var selectedRemoteTaskFile by remember(currentProjectPath, selectedTaskRepository) {
        mutableStateOf<ProjectGitHubRemoteFileUi?>(null)
    }
    var remoteTaskRepoRefDraft by remember(currentProjectPath, selectedTaskRepository) {
        mutableStateOf("")
    }
    var remoteTaskFileDialogState by remember(currentProjectPath, selectedTaskRepository) {
        mutableStateOf<ProjectGitHubRemoteFileUi?>(null)
    }
    var remoteTaskFileContentDraft by remember(currentProjectPath, selectedTaskRepository) {
        mutableStateOf("")
    }
    var remoteTaskFileCommitMessageDraft by remember(currentProjectPath, selectedTaskRepository) {
        mutableStateOf("")
    }
    var isRemoteTaskRepoLoading by remember(currentProjectPath, selectedTaskRepository) {
        mutableStateOf(false)
    }
    var isRemoteTaskRepoActionRunning by remember(currentProjectPath, selectedTaskRepository) {
        mutableStateOf(false)
    }
    var remoteTaskFeedbackMessage by remember(currentProjectPath, selectedTaskRepository) {
        mutableStateOf<String?>(null)
    }
    var remoteTaskFeedbackIsError by remember(currentProjectPath, selectedTaskRepository) {
        mutableStateOf(false)
    }
    val activeTreeRootPath = remember(projectRoot?.absolutePath, selectedRepoRoot, currentRepoViewOnly) {
        when {
            projectRoot == null -> null
            currentRepoViewOnly && !selectedRepoRoot.isNullOrBlank() -> selectedRepoRoot
            else -> projectRoot.absolutePath
        }
    }
    val activeTreeRoot = remember(activeTreeRootPath) {
        activeTreeRootPath?.let(::File)
    }
    val recentSessionCardModel = remember(sessions, currentProjectPath, selectedRepoRoot) {
        buildProjectRecentSessionReadinessCardModel(
            sessions = sessions,
            currentProjectPath = currentProjectPath,
            selectedRepoRoot = selectedRepoRoot
        )
    }
    val treeListState = rememberSaveable(
        currentProjectPath,
        saver = LazyListState.Saver
    ) {
        LazyListState()
    }
    val editorScrollOffsetsByFile = remember(currentProjectPath) { mutableStateMapOf<String, Int>() }
    var lastOpenedFilePath by rememberSaveable(currentProjectPath) { mutableStateOf<String?>(null) }
    var lspClientHandles by remember(currentProjectPath) {
        mutableStateOf<Map<String, ProjectEditorLspClientHandle>>(emptyMap())
    }
    var activeManagedLspSessionKey by remember(currentProjectPath) { mutableStateOf<String?>(null) }

    suspend fun applyOpenedEditorState(
        path: String,
        content: String,
        focusLine: Int? = null,
        focusQuery: String? = null
    ) {
        val initialValue = createInitialEditorValue(content, focusLine, focusQuery)
        val resolvedLanguage = languageOverride ?: projectLanguageForPath(path)
        val derivedSeedState = withContext(Dispatchers.Default) {
            val diagnosticsSnapshot = buildProjectEditorLspDiagnosticsSnapshot(
                content = content,
                language = resolvedLanguage,
                workspaceRoot = currentProjectPath,
                filePath = path
            )
            val conflictBlocks = detectGitConflictBlocks(content)
            val foldRegions = detectProjectFoldRegions(content, resolvedLanguage)
            ProjectEditorDerivedState(
                diagnostics = diagnosticsSnapshot.diagnostics,
                conflictBlocks = conflictBlocks,
                foldRegions = foldRegions,
                outlineEntries = buildProjectOutlineEntries(foldRegions, resolvedLanguage),
                lspState = diagnosticsSnapshot.lspState,
                lspSessionState = diagnosticsSnapshot.sessionState
            )
        }
        selectedFilePath = path
        loadedContent = content
        editorValue = initialValue
        editorDerivedSeed = ProjectEditorDerivedSeed(
            sourceText = content,
            language = resolvedLanguage.orEmpty(),
            derivedState = derivedSeedState
        )
        aiCompletionCandidate = null
        showAiCompletionDialog = false
        editorMode = ProjectEditorMode.EDIT
        focusedSearchLine = focusLine ?: currentLineNumber(content, initialValue.selection.start)
        focusedSearchQuery = focusQuery?.takeIf { it.isNotBlank() }
        undoStack.clear()
        redoStack.clear()
    }

    suspend fun ensureRemotePathExpanded(path: String) {
        val normalizedPath = path.replace('\\', '/').trim('/')
        if (normalizedPath.isBlank()) return
        val segments = normalizedPath.split('/').dropLast(1)
        if (segments.isEmpty()) return
        var current = ""
        val dirsToExpand = mutableListOf<String>()
        segments.forEach { segment ->
            current = if (current.isBlank()) segment else "$current/$segment"
            dirsToExpand += current
            if (!remoteEntriesByDir.containsKey(current)) {
                val repository = selectedTaskRepository ?: return@forEach
                val result = withContext(Dispatchers.IO) {
                    refreshProjectGitHubRemoteBrowserState(
                        currentRepo = repository,
                        gitRemoteUrl = null,
                        token = config.githubToken.trim(),
                        currentBranch = null,
                        defaultBranch = null,
                        refDraft = remoteTaskRepoRefDraft,
                        targetPath = current,
                        resetRefIfBlank = false,
                        apiBaseUrl = config.getGitHubApiBaseUrl()
                    )
                }
                result.nextRefDraft?.let { remoteTaskRepoRefDraft = it }
                remoteTaskRepoState = result.state
                remoteEntriesByDir[current] = result.state.entries.map { entry ->
                    remoteEntryInfoByPath[entry.path] = entry
                    ProjectTreeEntry(
                        absolutePath = entry.path,
                        name = entry.name,
                        isDirectory = entry.isDirectory
                    )
                }
            }
        }
        remoteExpandedDirs = remoteExpandedDirs + dirsToExpand
    }

    fun loadDir(path: String) {
        scope.launch {
            isTreeLoading = true
            val result = withContext(Dispatchers.IO) {
                runCatching { listProjectEntries(File(path)) }
            }
            result
                .onSuccess { entriesByDir[path] = it }
                .onFailure { error ->
                    entriesByDir[path] = emptyList()
                    editorError = error.message ?: "目录读取失败"
                }
            isTreeLoading = false
        }
    }

    suspend fun ensurePathExpanded(path: String) {
        val rootPath = activeTreeRootPath ?: return
        val dirs = projectAncestorDirs(
            rootPath = rootPath,
            filePath = path
        )
        dirs.forEach { dirPath ->
            if (!entriesByDir.containsKey(dirPath)) {
                val result = withContext(Dispatchers.IO) {
                    runCatching { listProjectEntries(File(dirPath)) }
                }
                result
                    .onSuccess { entriesByDir[dirPath] = it }
                    .onFailure { error -> editorError = error.message ?: "目录读取失败" }
            }
        }
        expandedDirs = expandedDirs + dirs
    }

    fun openFile(path: String, focusLine: Int? = null, focusQuery: String? = null) {
        scope.launch {
            isFileLoading = true
            editorError = null
            lastOpenedFilePath = path
            editorSearchRequest = ProjectEditorSearchRequest()
            selectedFilePath = path
            aiCompletionCandidate = null
            showAiCompletionDialog = false
            selectedRemoteTaskFile = null
            ensurePathExpanded(path)
            try {
                val content = withContext(Dispatchers.IO) {
                    readProjectFile(File(path))
                }
                applyOpenedEditorState(
                    path = path,
                    content = content,
                    focusLine = focusLine,
                    focusQuery = focusQuery
                )
            } catch (error: Throwable) {
                selectedFilePath = path
                loadedContent = ""
                editorValue = TextFieldValue("")
                editorDerivedSeed = null
                aiCompletionCandidate = null
                showAiCompletionDialog = false
                focusedSearchLine = null
                focusedSearchQuery = null
                undoStack.clear()
                redoStack.clear()
                editorError = error.message ?: "文件读取失败"
            } finally {
                isFileLoading = false
            }
        }
    }

    fun updateEditorValue(newValue: TextFieldValue, recordUndo: Boolean = true) {
        if (newValue == editorValue) return
        val previousValue = editorValue
        val textChanged = newValue.text != previousValue.text
        val largeFocusedEditorMode =
            (editorTextFieldFocused || editorFocusClearPending) &&
                (previousValue.text.length >= 32_000 || projectLineCount(previousValue.text) >= 700)
        if (recordUndo && textChanged) {
            if (undoStack.lastOrNull() != previousValue) {
                undoStack.add(previousValue)
                if (undoStack.size > MAX_PROJECT_EDITOR_HISTORY) {
                    undoStack.removeAt(0)
                }
            }
            redoStack.clear()
        }
        editorValue = newValue
        if (textChanged || !largeFocusedEditorMode) {
            focusedSearchLine = currentLineNumber(newValue.text, newValue.selection.start)
        }
    }

    fun jumpToDiagnostic(diagnostic: ProjectEditorDiagnostic) {
        val safeRange = TextRange(
            diagnostic.startIndex.coerceIn(0, editorValue.text.length),
            diagnostic.endIndex.coerceIn(0, editorValue.text.length).coerceAtLeast(diagnostic.startIndex.coerceIn(0, editorValue.text.length))
        )
        focusedSearchLine = diagnostic.lineNumber
        updateEditorValue(
            editorValue.copy(selection = if (safeRange.collapsed) TextRange(safeRange.start) else safeRange),
            recordUndo = false
        )
        showDiagnosticsDialog = false
    }

    fun jumpToConflictBlock(block: ProjectGitConflictBlock) {
        val safeRange = lineSelectionForRange(editorValue.text, block.startLine, block.endLine)
        focusedSearchLine = block.startLine
        updateEditorValue(
            editorValue.copy(selection = safeRange),
            recordUndo = false
        )
        showConflictResolverDialog = false
    }

    fun applyConflictResolution(block: ProjectGitConflictBlock, resolution: ProjectGitConflictResolution) {
        val replacementLines = conflictResolutionLines(block, resolution)
        val newContent = resolveGitConflictBlock(editorValue.text, block, resolution)
        val endLine = (block.startLine + replacementLines.size - 1).coerceAtLeast(block.startLine)
        focusedSearchLine = block.startLine
        updateEditorValue(
            editorValue.copy(
                text = newContent,
                selection = lineSelectionForRange(newContent, block.startLine, endLine)
            )
        )
    }

    fun navigateEditorHistory(backward: Boolean) {
        if (backward) {
            if (undoStack.isEmpty()) return
            redoStack.add(editorValue)
            editorValue = undoStack.removeAt(undoStack.lastIndex)
        } else {
            if (redoStack.isEmpty()) return
            undoStack.add(editorValue)
            editorValue = redoStack.removeAt(redoStack.lastIndex)
        }
        focusedSearchLine = currentLineNumber(editorValue.text, editorValue.selection.start)
    }

    fun applyLineAction(action: ProjectLineAction) {
        val lineBlock = selectedLineBlock(editorValue.text, editorValue.selection)
        val lineRange = lineBlock.startLine..lineBlock.endLine
        val lineText = lineBlock.text
        when (action) {
            ProjectLineAction.COPY -> {
                copyTextToClipboard(context, lineText)
            }
            ProjectLineAction.CUT -> {
                copyTextToClipboard(context, lineText)
                val newContent = replaceLines(editorValue.text, lineRange, emptyList())
                val targetLine = lineRange.first.coerceAtMost(projectLineCount(newContent))
                updateEditorValue(editorValue.copy(text = newContent, selection = lineSelectionForRange(newContent, targetLine, targetLine)))
            }
            ProjectLineAction.DELETE -> {
                val newContent = replaceLines(editorValue.text, lineRange, emptyList())
                val targetLine = lineRange.first.coerceAtMost(projectLineCount(newContent))
                updateEditorValue(editorValue.copy(text = newContent, selection = lineSelectionForRange(newContent, targetLine, targetLine)))
            }
            ProjectLineAction.CLEAR -> {
                val newContent = replaceLines(editorValue.text, lineRange, List(lineRange.last - lineRange.first + 1) { "" })
                updateEditorValue(editorValue.copy(text = newContent, selection = lineSelectionForRange(newContent, lineRange.first, lineRange.last)))
            }
            ProjectLineAction.REPLACE -> {
                lineReplaceDraft = lineText
                showLineReplaceDialog = true
                return
            }
            ProjectLineAction.DUPLICATE -> {
                val newContent = insertLinesAfter(editorValue.text, lineRange.last, lineText.lines())
                val insertedStart = lineRange.last + 1
                val insertedEnd = insertedStart + lineText.lines().size - 1
                updateEditorValue(editorValue.copy(text = newContent, selection = lineSelectionForRange(newContent, insertedStart, insertedEnd)))
            }
            ProjectLineAction.UPPERCASE -> {
                val newContent = replaceLines(editorValue.text, lineRange, lineText.lines().map { it.uppercase(Locale.getDefault()) })
                updateEditorValue(editorValue.copy(text = newContent, selection = lineSelectionForRange(newContent, lineRange.first, lineRange.last)))
            }
            ProjectLineAction.LOWERCASE -> {
                val newContent = replaceLines(editorValue.text, lineRange, lineText.lines().map { it.lowercase(Locale.getDefault()) })
                updateEditorValue(editorValue.copy(text = newContent, selection = lineSelectionForRange(newContent, lineRange.first, lineRange.last)))
            }
            ProjectLineAction.INDENT_MORE -> {
                val newContent = replaceLines(editorValue.text, lineRange, lineText.lines().map { "    $it" })
                updateEditorValue(editorValue.copy(text = newContent, selection = lineSelectionForRange(newContent, lineRange.first, lineRange.last)))
            }
            ProjectLineAction.INDENT_LESS -> {
                val newContent = replaceLines(editorValue.text, lineRange, lineText.lines().map { it.removePrefix("    ").removePrefix("\t") })
                updateEditorValue(editorValue.copy(text = newContent, selection = lineSelectionForRange(newContent, lineRange.first, lineRange.last)))
            }
            ProjectLineAction.TOGGLE_COMMENT -> {
                val newContent = replaceLines(
                    editorValue.text,
                    lineRange,
                    lineText.lines().map { toggleLineComment(it, languageOverride ?: projectLanguageForPath(selectedFilePath)) }
                )
                updateEditorValue(editorValue.copy(text = newContent, selection = lineSelectionForRange(newContent, lineRange.first, lineRange.last)))
            }
        }
        focusedSearchLine = lineRange.first
    }

    fun findNextInEditor() {
        val query = editorSearchQuery.trim()
        if (query.isBlank()) {
            editorError = "请输入搜索内容"
            return
        }
        focusedSearchQuery = query
        editorMode = ProjectEditorMode.EDIT
        editorSearchRequest = ProjectEditorSearchRequest(
            token = editorSearchRequest.token + 1,
            action = ProjectEditorSearchAction.NEXT
        )
    }

    fun replaceCurrentMatch() {
        val query = editorSearchQuery.trim()
        if (query.isBlank()) {
            editorError = "请输入搜索内容"
            return
        }
        focusedSearchQuery = query
        editorMode = ProjectEditorMode.EDIT
        editorSearchRequest = ProjectEditorSearchRequest(
            token = editorSearchRequest.token + 1,
            action = ProjectEditorSearchAction.REPLACE_CURRENT
        )
    }

    fun jumpToOutlineEntry(entry: ProjectOutlineEntry) {
        val offset = lineStartOffset(editorValue.text, entry.lineNumber)
        collapsedFoldRegions.remove(entry.startLine)
        focusedSearchLine = entry.lineNumber
        focusedSearchQuery = null
        editorMode = ProjectEditorMode.PREVIEW
        updateEditorValue(
            editorValue.copy(selection = TextRange(offset)),
            recordUndo = false
        )
        showOutlineDialog = false
    }

    fun jumpToFoldRegion(region: ProjectFoldRegion, selectRegion: Boolean, previewMode: Boolean) {
        val selection = if (selectRegion) {
            lineSelectionForRange(editorValue.text, region.startLine, region.endLine)
        } else {
            TextRange(lineStartOffset(editorValue.text, region.startLine))
        }
        collapsedFoldRegions.remove(region.startLine)
        focusedSearchLine = region.startLine
        focusedSearchQuery = null
        editorMode = if (previewMode) ProjectEditorMode.PREVIEW else ProjectEditorMode.EDIT
        updateEditorValue(
            editorValue.copy(selection = selection),
            recordUndo = false
        )
    }

    fun toggleFoldRegionFromEditor(region: ProjectFoldRegion, collapsed: Boolean) {
        if (collapsed) {
            collapsedFoldRegions[region.startLine] = true
            focusedSearchLine = null
            focusedSearchQuery = null
            editorMode = ProjectEditorMode.PREVIEW
            updateEditorValue(
                editorValue.copy(selection = TextRange(lineStartOffset(editorValue.text, region.startLine))),
                recordUndo = false
            )
        } else {
            collapsedFoldRegions.remove(region.startLine)
            jumpToFoldRegion(region, selectRegion = false, previewMode = false)
        }
    }

    fun replaceAllMatches() {
        val query = editorSearchQuery.trim()
        if (query.isBlank()) {
            editorError = "请输入搜索内容"
            return
        }
        focusedSearchQuery = query
        editorMode = ProjectEditorMode.EDIT
        editorSearchRequest = ProjectEditorSearchRequest(
            token = editorSearchRequest.token + 1,
            action = ProjectEditorSearchAction.REPLACE_ALL
        )
    }

    fun applyAiCompletionCandidate() {
        val candidate = aiCompletionCandidate ?: return
        val selection = editorValue.selection
        val newContent = editorValue.text.replaceRange(selection.min, selection.max, candidate.text)
        val newCursor = selection.min + candidate.text.length
        updateEditorValue(
            editorValue.copy(
                text = newContent,
                selection = TextRange(newCursor)
            )
        )
        showAiCompletionDialog = false
    }

    fun requestAiCompletion() {
        val selectedPath = selectedFilePath
        if (selectedPath == null) {
            editorError = "请先打开一个文件再使用 AI 补全"
            return
        }
        val apiKey = config.getActiveApiKey().trim()
        if (apiKey.isBlank()) {
            editorError = "请先在设置中配置当前 Provider 的 API Key"
            return
        }
        val provider = ProviderRegistry.getActiveProvider(config.activeProviderId)
        val languageName = languageOverride ?: projectLanguageForPath(selectedFilePath) ?: "plain text"
        val cursorLine = currentLineNumber(editorValue.text, editorValue.selection.start)
        val prompt = buildProjectAiCompletionPrompt(
            filePath = selectedPath,
            language = languageName,
            content = editorValue.text,
            selection = editorValue.selection
        )
        scope.launch {
            isAiCompleting = true
            editorError = null
            showAiCompletionDialog = false
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    provider.chat(
                        request = ChatRequest(
                            messages = listOf(
                                ChatMessage(
                                    role = "system",
                                    content = "你是移动端代码编辑器里的代码自动补全助手。只返回应该插入或替换的代码文本本身，不要解释，不要加三引号代码块，不要重复原文上下文。"
                                ),
                                ChatMessage(role = "user", content = prompt)
                            ),
                            model = config.getActiveModel(),
                            temperature = 0.2,
                            maxTokens = 768,
                            stream = false,
                            reasoningEffort = config.getActiveReasoningEffort()
                        ),
                        apiKey = apiKey,
                        baseUrl = config.getActiveBaseUrl()
                    )
                }
            }
            result
                .onSuccess { response ->
                    val normalized = normalizeAiCompletionText(response.content)
                    if (normalized.isBlank()) {
                        editorError = "AI 没有返回可用补全文本"
                    } else {
                        aiCompletionCandidate = ProjectAiCompletionCandidateUi(
                            text = normalized,
                            score = 1f
                        )
                        focusedSearchLine = cursorLine
                        showAiCompletionDialog = true
                    }
                }
                .onFailure { error ->
                    editorError = error.message ?: "AI 补全失败"
                }
            isAiCompleting = false
        }
    }

    fun runSearch() {
        val query = searchQuery.trim()
        if (query.isBlank()) {
            searchResult = null
            remoteSearchResults = emptyList()
            return
        }
        val root = activeTreeRoot
        scope.launch {
            isSearching = true
            editorError = null
            if (isRemoteTaskMode) {
                val repository = selectedTaskRepository
                val token = config.githubToken.trim()
                if (token.isBlank()) {
                    remoteSearchResults = emptyList()
                    editorError = "请先在设置中配置 GitHub Token"
                    isSearching = false
                    return@launch
                }
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val fileNameMatches = searchProjectGitHubFileNames(
                            query = query,
                            repo = repository,
                            token = token,
                            apiBaseUrl = config.getGitHubApiBaseUrl(),
                            ref = remoteTaskRepoState.currentRef
                        )
                        val codeMatches = searchProjectGitHubCodeMatches(
                            query = query,
                            repo = repository,
                            token = token,
                            apiBaseUrl = config.getGitHubApiBaseUrl(),
                            ref = remoteTaskRepoState.currentRef
                        )
                        (fileNameMatches + codeMatches)
                    }
                }
                result
                    .onSuccess { remoteSearchResults = it }
                    .onFailure { error ->
                        remoteSearchResults = emptyList()
                        editorError = error.message ?: "远端搜索失败"
                    }
            } else {
                val safeRoot = root ?: return@launch
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        searchProjectEntries(
                            root = safeRoot,
                            query = query,
                            scope = ProjectSearchScope.FILE_NAME
                        )
                    }
                }
                result
                    .onSuccess { searchResult = it }
                    .onFailure { error ->
                        searchResult = ProjectSearchResultUi(
                            query = query,
                            scope = ProjectSearchScope.FILE_NAME,
                            hits = emptyList(),
                            totalCount = 0,
                            truncated = false
                        )
                        editorError = error.message ?: "搜索失败"
                    }
            }
            isSearching = false
        }
    }

    LaunchedEffect(activeTreeRootPath, reloadVersion) {
        entriesByDir.clear()
        expandedDirs = activeTreeRootPath?.let { setOf(it) } ?: emptySet()
        if (activeTreeRoot != null) {
            loadDir(activeTreeRoot.absolutePath)
        }
    }

    LaunchedEffect(searchQuery, activeTreeRootPath) {
        if (searchQuery.isBlank()) {
            searchResult = null
        } else {
            runSearch()
        }
    }

    val editedContent = editorValue.text
    val dirty = selectedFilePath != null && editedContent != loadedContent
    val language = languageOverride ?: projectLanguageForPath(selectedFilePath)
    val isLargeEditorContent =
        editedContent.length >= 32_000 || projectLineCount(editedContent) >= 700
    val editorInteractionActive = editorTextFieldFocused || editorFocusClearPending
    val deferEditorDerivedRecompute = editorInteractionActive && isLargeEditorContent && dirty
    val derivedSeed = editorDerivedSeed
    val hasMatchingDerivedSeed =
        derivedSeed?.sourceText == editedContent &&
            derivedSeed.language == language.orEmpty()
    val editorDerivedState by produceState(
        initialValue = if (hasMatchingDerivedSeed) {
            derivedSeed.derivedState
        } else {
            ProjectEditorDerivedState()
        },
        key1 = editedContent,
        key2 = language,
        key3 = derivedSeed
    ) {
        if (hasMatchingDerivedSeed) {
            value = derivedSeed.derivedState
            return@produceState
        }
        if (deferEditorDerivedRecompute) {
            delay(220)
        }
        value = withContext(Dispatchers.Default) {
            val diagnosticsSnapshot = buildProjectEditorLspDiagnosticsSnapshot(
                content = editedContent,
                language = language,
                workspaceRoot = currentProjectPath,
                filePath = selectedFilePath
            )
            val conflicts = detectGitConflictBlocks(editedContent)
            val regions = detectProjectFoldRegions(editedContent, language)
            ProjectEditorDerivedState(
                diagnostics = diagnosticsSnapshot.diagnostics,
                conflictBlocks = conflicts,
                foldRegions = regions,
                outlineEntries = buildProjectOutlineEntries(regions, language),
                lspState = diagnosticsSnapshot.lspState,
                lspSessionState = diagnosticsSnapshot.sessionState
            )
        }
    }
    val editorDiagnostics = editorDerivedState.diagnostics
    val conflictBlocks = editorDerivedState.conflictBlocks
    val foldRegions = editorDerivedState.foldRegions
    val outlineEntries = editorDerivedState.outlineEntries
    val largeFocusedEditorMode =
        editorInteractionActive && isLargeEditorContent
    val currentLine = if (largeFocusedEditorMode) {
        -1
    } else {
        currentLineNumber(editedContent, editorValue.selection.start)
    }
    val currentLineDiagnostic = remember(editorDiagnostics, currentLine, largeFocusedEditorMode) {
        if (largeFocusedEditorMode) null else editorDiagnostics.firstOrNull { it.lineNumber == currentLine }
    }
    val currentFoldRegion = remember(foldRegions, currentLine, largeFocusedEditorMode) {
        if (largeFocusedEditorMode) null else foldRegions.lastOrNull { currentLine in it.startLine..it.endLine }
    }
    val currentOutlineEntry = remember(currentFoldRegion, outlineEntries, largeFocusedEditorMode) {
        currentFoldRegion?.let { region -> outlineEntries.firstOrNull { it.startLine == region.startLine } }
    }
    val currentOutlineIndex = remember(currentOutlineEntry, outlineEntries) {
        currentOutlineEntry?.let { entry -> outlineEntries.indexOfFirst { it.startLine == entry.startLine } } ?: -1
    }
    val currentFoldRegionIndex = remember(currentFoldRegion, foldRegions) {
        currentFoldRegion?.let { region -> foldRegions.indexOfFirst { it.startLine == region.startLine } } ?: -1
    }
    val previousFoldRegion = remember(currentFoldRegionIndex, foldRegions) {
        if (currentFoldRegionIndex > 0) foldRegions[currentFoldRegionIndex - 1] else null
    }
    val nextFoldRegion = remember(currentFoldRegionIndex, foldRegions) {
        if (currentFoldRegionIndex >= 0 && currentFoldRegionIndex < foldRegions.lastIndex) {
            foldRegions[currentFoldRegionIndex + 1]
        } else {
            null
        }
    }
    val isCurrentRegionCollapsed = currentFoldRegion?.let { collapsedFoldRegions[it.startLine] == true } == true

    LaunchedEffect(foldRegions) {
        val validStarts = foldRegions.map { it.startLine }.toSet()
        collapsedFoldRegions.keys
            .toList()
            .filterNot(validStarts::contains)
            .forEach(collapsedFoldRegions::remove)
    }

    fun refreshRemoteTaskRepository(
        targetPath: String = remoteTaskRepoState.currentPath,
        resetRefIfBlank: Boolean = false
    ) {
        val repository = selectedTaskRepository ?: return
        scope.launch {
            isRemoteTaskRepoLoading = true
            val result = withContext(Dispatchers.IO) {
                refreshProjectGitHubRemoteBrowserState(
                    currentRepo = repository,
                    gitRemoteUrl = null,
                    token = config.githubToken.trim(),
                    currentBranch = null,
                    defaultBranch = null,
                    refDraft = remoteTaskRepoRefDraft,
                    targetPath = targetPath,
                    resetRefIfBlank = resetRefIfBlank,
                    apiBaseUrl = config.getGitHubApiBaseUrl()
                )
            }
            isRemoteTaskRepoLoading = false
            result.nextRefDraft?.let { remoteTaskRepoRefDraft = it }
            remoteTaskRepoState = result.state
            val normalizedPath = targetPath.replace('\\', '/').trim('/')
            remoteEntriesByDir[normalizedPath] = result.state.entries.map { entry ->
                remoteEntryInfoByPath[entry.path] = entry
                ProjectTreeEntry(
                    absolutePath = entry.path,
                    name = entry.name,
                    isDirectory = entry.isDirectory
                )
            }
            result.feedbackMessage?.let {
                remoteTaskFeedbackMessage = it
                remoteTaskFeedbackIsError = true
            }
        }
    }

    fun openRemoteTaskRepositoryEntry(entry: ProjectGitHubRemoteEntryUi) {
        if (entry.isDirectory) {
            refreshRemoteTaskRepository(targetPath = entry.path)
            return
        }
        val repository = selectedTaskRepository ?: return
        scope.launch {
            isRemoteTaskRepoActionRunning = true
            val result = withContext(Dispatchers.IO) {
                openProjectGitHubRemoteFileEditor(
                    currentRepo = repository,
                    gitRemoteUrl = null,
                    token = config.githubToken.trim(),
                    currentRemoteRef = remoteTaskRepoState.currentRef,
                    refDraft = remoteTaskRepoRefDraft,
                    currentBranch = null,
                    defaultBranch = null,
                    entry = entry,
                    apiBaseUrl = config.getGitHubApiBaseUrl()
                )
            }
            isRemoteTaskRepoActionRunning = false
            if (result.file != null) {
                selectedRemoteTaskFile = result.file
                remoteTaskFileCommitMessageDraft = result.commitMessageDraft
                lastOpenedFilePath = result.file.path
                editorSearchRequest = ProjectEditorSearchRequest()
                editorError = null
                isFileLoading = true
                try {
                    ensureRemotePathExpanded(result.file.path)
                    applyOpenedEditorState(
                        path = result.file.path,
                        content = result.contentDraft
                    )
                } catch (error: Throwable) {
                    editorError = error.message ?: "读取远端文件失败"
                } finally {
                    isFileLoading = false
                }
                remoteTaskFeedbackMessage = null
            } else {
                remoteTaskFeedbackMessage = result.feedbackMessage ?: "读取远端文件失败"
                remoteTaskFeedbackIsError = true
            }
        }
    }

    fun submitRemoteTaskRepositoryFileSave(file: ProjectGitHubRemoteFileUi) {
        val repository = selectedTaskRepository ?: return
        scope.launch {
            isSaving = true
            isRemoteTaskRepoActionRunning = true
            val commitMessage = remoteTaskFileCommitMessageDraft
                .trim()
                .ifBlank { "Update ${file.path} from project editor" }
            val result = withContext(Dispatchers.IO) {
                saveProjectGitHubRemoteFileEditorAction(
                    currentRepo = repository,
                    gitRemoteUrl = null,
                    token = config.githubToken.trim(),
                    currentRemoteRef = remoteTaskRepoState.currentRef,
                    defaultBranch = null,
                    file = file,
                    contentDraft = editedContent,
                    commitMessageDraft = commitMessage,
                    apiBaseUrl = config.getGitHubApiBaseUrl(),
                    refreshTargetPath = remoteTaskRepoState.currentPath
                )
            }
            isRemoteTaskRepoActionRunning = false
            remoteTaskFeedbackMessage = result.feedbackMessage
            remoteTaskFeedbackIsError = !result.success
            if (result.shouldRefreshBrowser) {
                refreshRemoteTaskRepository(targetPath = result.refreshTargetPath)
            }
            if (result.success) {
                loadedContent = editedContent
                val reopenEntry = ProjectGitHubRemoteEntryUi(
                    name = file.name,
                    path = file.path,
                    type = "file",
                    sha = file.sha,
                    size = editedContent.length.toLong(),
                    htmlUrl = file.htmlUrl,
                    downloadUrl = file.downloadUrl
                )
                val reopenResult = withContext(Dispatchers.IO) {
                    openProjectGitHubRemoteFileEditor(
                        currentRepo = repository,
                        gitRemoteUrl = null,
                        token = config.githubToken.trim(),
                        currentRemoteRef = remoteTaskRepoState.currentRef,
                        refDraft = remoteTaskRepoRefDraft,
                        currentBranch = null,
                        defaultBranch = null,
                        entry = reopenEntry,
                        apiBaseUrl = config.getGitHubApiBaseUrl()
                    )
                }
                reopenResult.file?.let { refreshedFile ->
                    selectedRemoteTaskFile = refreshedFile
                    remoteTaskFileCommitMessageDraft = "Update ${refreshedFile.path} from project editor"
                }
            }
            isSaving = false
        }
    }

    fun requestSaveCurrentEditorFile() {
        val remoteFile = selectedRemoteTaskFile
        if (remoteFile != null) {
            editorError = null
            submitRemoteTaskRepositoryFileSave(remoteFile)
            return
        }
        val path = selectedFilePath ?: return
        scope.launch {
            isSaving = true
            editorError = null
            val result = withContext(Dispatchers.IO) {
                runCatching { saveProjectFile(File(path), editedContent) }
            }
            result
                .onSuccess { loadedContent = editedContent }
                .onFailure { error -> editorError = error.message ?: "保存失败" }
            isSaving = false
        }
    }

    LaunchedEffect(selectedTaskRepository, projectRoot, config.githubToken, config.githubApiBaseUrl, reloadVersion) {
        if (isRemoteTaskMode) {
            remoteEntriesByDir.clear()
            remoteEntryInfoByPath.clear()
            remoteExpandedDirs = setOf("")
            refreshRemoteTaskRepository(resetRefIfBlank = true)
        }
    }

    if (projectRoot == null && !isRemoteTaskMode) {
        val taskRepositoryLabel = selectedTaskRepository?.let { "${it.owner}/${it.repo}" }
        EmptyEditorState(
            title = if (taskRepositoryLabel != null) "未选择本地项目" else "未选择可编辑项目",
            message = currentProjectPath?.let {
                "当前项目路径不是可访问的文件夹：$it"
            } ?: if (taskRepositoryLabel != null) {
                "当前任务仓库是 $taskRepositoryLabel。未选择本地项目时，这里不会显示本地文件树；可以先进入任务仓库继续远端编辑，或改为选择一个本地项目文件夹。"
            } else {
                "先选择一个项目文件夹，项目页才会显示文件树和编辑器。"
            },
            actionLabel = if (taskRepositoryLabel != null) "进入任务仓库" else "选择项目文件夹",
            onAction = if (taskRepositoryLabel != null) onOpenSelectedTaskRepository else onNewTask,
            secondaryActionLabel = if (taskRepositoryLabel != null) "选择项目文件夹" else null,
            onSecondaryAction = if (taskRepositoryLabel != null) onNewTask else null
        )
        return
    }

    val treeRootPath = if (isRemoteTaskMode) {
        buildString {
            append(selectedTaskRepository.owner)
            append('/')
            append(selectedTaskRepository.repo)
        }
    } else {
        activeTreeRootPath ?: projectRoot!!.absolutePath
    }
    val treeRoot = activeTreeRoot ?: projectRoot
    val visibleEntries = if (isRemoteTaskMode) {
        flattenVisibleEntries(
            rootPath = "",
            entriesByDir = remoteEntriesByDir,
            expandedDirs = remoteExpandedDirs
        )
    } else {
        flattenVisibleEntries(
            rootPath = treeRootPath,
            entriesByDir = entriesByDir,
            expandedDirs = expandedDirs
        )
    }
    val currentFileName = selectedFilePath
        ?.replace('\\', '/')
        ?.substringAfterLast('/')
        .orEmpty()
    val currentRelativePath = if (isRemoteTaskMode) {
        selectedFilePath.orEmpty()
    } else {
        selectedFilePath
            ?.removePrefix(projectRoot!!.absolutePath)
            ?.removePrefix(File.separator)
            .orEmpty()
    }
    val editorLspState = editorDerivedState.lspState
    val editorLspSessionState = editorDerivedState.lspSessionState
    LaunchedEffect(editorLspSessionState.sessionKey, editorLspSessionState.status, editorLspState.providerLabel) {
        var nextClients = lspClientHandles
        val previousManagedSessionKey = activeManagedLspSessionKey
        if (!previousManagedSessionKey.isNullOrBlank() && previousManagedSessionKey != editorLspSessionState.sessionKey) {
            nextClients = detachProjectEditorLspClientSkeleton(
                currentClients = nextClients,
                sessionKey = previousManagedSessionKey
            )
        }
        if (editorLspSessionState.status == ProjectEditorLspSessionStatus.MANAGED) {
            nextClients = attachProjectEditorLspClientSkeleton(
                currentClients = nextClients,
                sessionState = editorLspSessionState,
                lspState = editorLspState
            )
            activeManagedLspSessionKey = editorLspSessionState.sessionKey
        } else {
            activeManagedLspSessionKey = null
        }
        lspClientHandles = nextClients
    }
    val editorLspLiveSessionState = remember(editorLspSessionState, editorLspState, lspClientHandles) {
        resolveProjectEditorLspLiveSessionState(
            currentSessions = lspClientHandles,
            sessionState = editorLspSessionState,
            lspState = editorLspState
        )
    }
    val editorLspClientHandle = remember(editorLspSessionState, lspClientHandles) {
        lspClientHandles[editorLspSessionState.sessionKey]
    }
    val editorRuntimeLspState = remember(editorLspState, editorLspClientHandle) {
        resolveProjectEditorLspRuntimeState(
            baseState = editorLspState,
            clientHandle = editorLspClientHandle
        )
    }

    SideEffect {
        onProjectSecondaryPageChanged(
            if (selectedFilePath != null) {
                ProjectSecondaryChromeState(
                    active = true,
                    title = currentFileName,
                    subtitle = buildProjectEditorChromeSubtitle(currentRelativePath, editorRuntimeLspState),
                    supportsEditorMenu = true,
                    wordWrapEnabled = editorWordWrapEnabled
                )
            } else {
                ProjectSecondaryChromeState()
            }
        )
    }

    fun exitEditorPage() {
        editorTextFieldFocused = false
        editorFocusClearPending = false
        editorSearchRequest = ProjectEditorSearchRequest()
        selectedFilePath = null
        focusedSearchLine = null
        focusedSearchQuery = null
        aiCompletionCandidate = null
        showAiCompletionDialog = false
    }

    fun handleEditorCloseRequest() {
        when {
            showAiCompletionDialog -> {
                showAiCompletionDialog = false
                aiCompletionCandidate = null
            }

            showConflictResolverDialog -> {
                showConflictResolverDialog = false
            }

            showDiagnosticsDialog -> {
                showDiagnosticsDialog = false
            }

            showOutlineDialog -> {
                showOutlineDialog = false
            }

            showLanguageDialog -> {
                showLanguageDialog = false
            }

            showLineReplaceDialog -> {
                showLineReplaceDialog = false
            }

            showSearchReplaceDialog -> {
                showSearchReplaceDialog = false
            }

            selectedFilePath != null && dirty -> {
                showUnsavedChangesDialog = true
            }

            editorTextFieldFocused || editorFocusClearPending -> {
                focusManager.clearFocus(force = true)
                editorTextFieldFocused = false
                editorFocusClearPending = true
                editorFocusClearSignal += 1
            }

            selectedFilePath != null -> {
                exitEditorPage()
            }
        }
    }

    SideEffect {
        onRegisterSecondaryCloseRequest(
            if (selectedFilePath != null) {
                { handleEditorCloseRequest() }
            } else {
                null
            }
        )
    }

    LaunchedEffect(selectedFilePath, searchQuery, visibleEntries, lastOpenedFilePath) {
        if (selectedFilePath != null || searchQuery.isNotBlank()) return@LaunchedEffect
        val targetPath = lastOpenedFilePath ?: return@LaunchedEffect
        val targetIndex = visibleEntries.indexOfFirst { it.entry.absolutePath == targetPath }
        if (targetIndex >= 0) {
            treeListState.scrollToItem(targetIndex + 2)
        }
    }

    LaunchedEffect(selectedFilePath, projectSecondaryBackProgress) {
    }

    ProjectNestedPredictiveBackHost(
        detailVisible = selectedFilePath != null,
        backProgress = projectSecondaryBackProgress,
        listContent = {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 12.dp, top = 12.dp, end = 12.dp, bottom = bottomBarScrollPadding),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (!showOuterPrimaryChrome && (selectedFilePath != null || projectSecondaryBackProgress > 0f)) {
                    ProjectPrimaryChrome(
                        currentProjectPath = currentProjectPath,
                        selectedTaskRepository = selectedTaskRepository,
                        detectedRepos = detectedRepos,
                        mutedTextColor = primaryMutedTextColor,
                        chromeColor = primaryChromeColor,
                        selectedTab = selectedPrimaryTab,
                        onSelectTab = onSelectPrimaryTab,
                        onPrimaryAction = onNewTask
                    )
                }
                MurongGlassSurface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(26.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = { query -> searchQuery = query },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            decorationBox = { innerTextField ->
                                Box(modifier = Modifier.fillMaxWidth()) {
                                    if (searchQuery.isBlank()) {
                                        Text(
                                            text = when {
                                                isRemoteTaskMode -> "搜索远端任务仓库文件名或代码"
                                                currentRepoViewOnly -> "搜索当前仓库文件名"
                                                else -> "搜索工作区文件名"
                                            },
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    innerTextField()
                                }
                            }
                        )
                        IconButton(
                            onClick = { reloadVersion++ },
                            modifier = Modifier.width(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Refresh,
                                contentDescription = "刷新",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = treeRootPath,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!isRemoteTaskMode && detectedRepos.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            detectedRepos.forEach { repo ->
                                FilterChip(
                                    selected = repo.rootPath == selectedRepoRoot,
                                    onClick = { onSelectRepoRoot(repo.rootPath) },
                                    label = {
                                        Text(
                                            when {
                                                repo.isWorkspaceRoot -> "${repo.displayName}（根）"
                                                repo.hasGitMetadata -> repo.displayName
                                                else -> "${repo.displayName}（项目）"
                                            },
                                            fontSize = 12.sp
                                        )
                                    }
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = !currentRepoViewOnly,
                                onClick = { currentRepoViewOnly = false },
                                label = { Text("整个工作区", fontSize = 12.sp) }
                            )
                            FilterChip(
                                selected = currentRepoViewOnly,
                                onClick = { currentRepoViewOnly = true },
                                enabled = !selectedRepoRoot.isNullOrBlank(),
                                label = { Text("当前仓库", fontSize = 12.sp) }
                            )
                        }
                    }
                }

                recentSessionCardModel?.let { cardModel ->
                    ProjectInsetCard(
                        surfaceColorOverride = editorChromeColor.copy(alpha = 0.18f)
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
                                    text = "最近会话状态",
                                    style = MaterialTheme.typography.labelLarge
                                )
                                Text(
                                    text = cardModel.session.title.ifBlank { "新对话" },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = editorMutedTextColor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = cardModel.supportText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = editorMutedTextColor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = cardModel.statusLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (cardModel.blocked) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.primary
                                    }
                                )
                                Text(
                                    text = cardModel.readinessSummary,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (cardModel.blocked) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.primary
                                    },
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                cardModel.readinessReasonSummary?.let { reasonSummary ->
                                    Text(
                                        text = "说明：$reasonSummary",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = editorMutedTextColor,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            OutlinedButton(onClick = onOpenChat) {
                                Text(cardModel.actionLabel)
                            }
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    LazyColumn(
                        state = treeListState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        item(key = "status") {
                            when {
                                isRemoteTaskMode && isRemoteTaskRepoActionRunning -> Text(
                                    "远端文件处理中...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                isRemoteTaskMode && isRemoteTaskRepoLoading -> Text(
                                    "正在读取远端目录...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                isSearching -> Text(
                                    "搜索中...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                isTreeLoading -> Text(
                                    "读取中...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        if (isRemoteTaskMode && searchQuery.isNotBlank()) {
                            when {
                                isSearching -> {
                                    item(key = "remote-search-loading") {
                                        Text(
                                            "正在搜索远端仓库文件",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                remoteSearchResults.isEmpty() -> {
                                    item(key = "remote-search-empty") {
                                        Text(
                                            "没有匹配的远端文件",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                else -> {
                                    item(key = "remote-search-count") {
                                        Text(
                                            "命中 ${remoteSearchResults.size} 条远端结果",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    items(
                                        remoteSearchResults,
                                        key = { "${it.repoOwner}/${it.repoName}/${it.filePath}/${it.matchLabel}" }
                                    ) { result ->
                                        val matchedPath = result.filePath.orEmpty()
                                        ProjectInsetCard(
                                            shape = RoundedCornerShape(12.dp),
                                            surfaceColorOverride = editorChromeColor.copy(alpha = 0.22f),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable(
                                                    enabled = matchedPath.isNotBlank() && !isRemoteTaskRepoActionRunning
                                                ) {
                                                    if (matchedPath.isNotBlank()) {
                                                        openRemoteTaskRepositoryEntry(
                                                            ProjectGitHubRemoteEntryUi(
                                                                name = result.title,
                                                                path = matchedPath,
                                                                type = "file",
                                                                sha = null,
                                                                size = 0,
                                                                htmlUrl = result.url
                                                            )
                                                        )
                                                    }
                                                }
                                        ) {
                                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Text(
                                                    text = result.title,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = result.filePath.orEmpty(),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                result.matchLabel?.let { matchLabel ->
                                                    Text(
                                                        text = matchLabel,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.primary
                                                    )
                                                }
                                                result.matchSnippet
                                                    ?.takeIf { it.isNotBlank() }
                                                    ?.let { snippet ->
                                                        Text(
                                                            text = snippet,
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                            maxLines = 2,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                    }
                                            }
                                        }
                                    }
                                }
                            }
                        } else if (!isRemoteTaskMode && searchQuery.isNotBlank()) {
                            val result = searchResult
                            when {
                                result == null -> {
                                    item(key = "search-empty-loading") {
                                        Text(
                                            "正在搜索文件名",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                result.hits.isEmpty() -> {
                                    item(key = "search-empty") {
                                        Text(
                                            "没有匹配的文件名",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                else -> {
                                    item(key = "search-count") {
                                        Text(
                                            "命中 ${result.totalCount} 个文件",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    items(result.hits, key = { it.filePath }) { hit ->
                                        val owningRepo = findOwningRepo(hit.filePath, detectedRepos)
                                        ProjectSearchResultRow(
                                            hit = hit,
                                            query = searchQuery,
                                            repoLabel = owningRepo
                                                ?.takeIf { !currentRepoViewOnly && detectedRepos.size > 1 }
                                                ?.let { repo ->
                                                    if (repo.isWorkspaceRoot) "工作区根仓库" else repo.displayName
                                                },
                                            onOpen = {
                                                owningRepo?.let { onSelectRepoRoot(it.rootPath) }
                                                openFile(hit.filePath)
                                            }
                                        )
                                    }
                                }
                            }
                        } else {
                            item(key = "tree-root") {
                                ProjectTreeRow(
                                    entry = ProjectTreeEntry(
                                        absolutePath = if (isRemoteTaskMode) treeRootPath else treeRoot!!.absolutePath,
                                        name = if (isRemoteTaskMode) {
                                            "${selectedTaskRepository.owner}/${selectedTaskRepository.repo}"
                                        } else {
                                            treeRoot!!.name.ifBlank { treeRoot.absolutePath }
                                        },
                                        isDirectory = true
                                    ),
                                    rootPath = treeRootPath,
                                    depth = 0,
                                    isExpanded = true,
                                    isSelected = false,
                                    repoBadge = if (isRemoteTaskMode) {
                                        if (remoteTaskRepoState.currentRef.isBlank()) "远端任务仓库" else "远端任务仓库 · ${remoteTaskRepoState.currentRef}"
                                    } else if (treeRoot!!.absolutePath in detectedRepoRoots) {
                                        if (treeRoot.absolutePath == selectedRepoRoot) "Git 仓库 · 当前" else "Git 仓库"
                                    } else null,
                                    onToggleDir = {
                                        if (isRemoteTaskMode) {
                                            refreshRemoteTaskRepository(targetPath = "")
                                        }
                                    },
                                    onSelectFile = {}
                                )
                            }
                            if (isRemoteTaskMode && visibleEntries.isEmpty() && !isRemoteTaskRepoLoading) {
                                item(key = "tree-empty-remote") {
                                    Text(
                                        "当前远端目录没有可显示的文件或文件夹。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            if (!isRemoteTaskMode && visibleEntries.isEmpty() && !isTreeLoading) {
                                item(key = "tree-empty") {
                                    Text(
                                        "当前目录没有可显示的文件或文件夹。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            items(
                                visibleEntries,
                                key = { it.entry.absolutePath }
                            ) { item ->
                                ProjectTreeRow(
                                    entry = item.entry,
                                    rootPath = treeRootPath,
                                    depth = item.depth,
                                    isExpanded = if (isRemoteTaskMode) {
                                        item.entry.isDirectory && remoteExpandedDirs.contains(item.entry.absolutePath)
                                    } else {
                                        item.entry.isDirectory && expandedDirs.contains(item.entry.absolutePath)
                                    },
                                    isSelected = selectedFilePath == item.entry.absolutePath,
                                    repoBadge = if (isRemoteTaskMode) {
                                        remoteEntryInfoByPath[item.entry.absolutePath]
                                            ?.let { entry ->
                                                if (entry.isDirectory) "目录" else entry.typeLabel
                                            }
                                    } else if (item.entry.absolutePath in detectedRepoRoots) {
                                        if (item.entry.absolutePath == selectedRepoRoot) "Git 仓库 · 当前" else "Git 仓库"
                                    } else null,
                                    onToggleDir = { path ->
                                        if (isRemoteTaskMode) {
                                            if (remoteExpandedDirs.contains(path)) {
                                                remoteExpandedDirs = remoteExpandedDirs - path
                                            } else {
                                                remoteExpandedDirs = remoteExpandedDirs + path
                                                if (!remoteEntriesByDir.containsKey(path)) {
                                                    refreshRemoteTaskRepository(targetPath = path)
                                                }
                                            }
                                        } else {
                                            expandedDirs = if (expandedDirs.contains(path)) {
                                                expandedDirs - path
                                            } else {
                                                if (!entriesByDir.containsKey(path)) loadDir(path)
                                                expandedDirs + path
                                            }
                                        }
                                    },
                                    onSelectFile = { path ->
                                        if (isRemoteTaskMode) {
                                            remoteEntryInfoByPath[path]
                                                ?.let(::openRemoteTaskRepositoryEntry)
                                        } else {
                                            openFile(path)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        },
        detailContent = {
        val initialEditorScrollOffset = selectedFilePath?.let { path -> editorScrollOffsetsByFile[path] } ?: 0
        val isRawJsonFile = remember(selectedFilePath, language) {
            isJsonLikeProjectFile(selectedFilePath, language)
        }
        val editorLanguageLabel = remember(language, languageOverride, isRawJsonFile) {
            when {
                isRawJsonFile -> "json"
                !languageOverride.isNullOrBlank() -> languageOverride!!
                !language.isNullOrBlank() -> language
                else -> "text"
            }
        }
        val quickInsertActions = remember(isRawJsonFile) {
            if (isRawJsonFile) PROJECT_EDITOR_JSON_SYMBOL_ACTIONS else PROJECT_EDITOR_SYMBOL_ACTIONS
        }

        fun insertQuickAction(action: ProjectQuickInsertAction) {
            val selection = editorValue.selection
            val insertedText = action.insertText
            val newText = editorValue.text.replaceRange(selection.min, selection.max, insertedText)
            val cursorOffset = quickInsertCursorOffset(insertedText)
            val cursor = (selection.min + cursorOffset).coerceIn(0, newText.length)
            updateEditorValue(
                editorValue.copy(
                    text = newText,
                    selection = TextRange(cursor)
                )
            )
        }

        fun handleEditorMenuAction(action: ProjectEditorMenuAction) {
            when (action) {
                ProjectEditorMenuAction.SEARCH_REPLACE -> showSearchReplaceDialog = true
                ProjectEditorMenuAction.TOGGLE_WORD_WRAP -> editorWordWrapEnabled = !editorWordWrapEnabled
                ProjectEditorMenuAction.LANGUAGE -> showLanguageDialog = true
                ProjectEditorMenuAction.DIAGNOSTICS -> showDiagnosticsDialog = true
                ProjectEditorMenuAction.CONFLICTS -> {
                    if (conflictBlocks.isEmpty()) {
                        editorError = "当前文件没有检测到 Git 冲突块"
                    } else {
                        showConflictResolverDialog = true
                    }
                }
                ProjectEditorMenuAction.OUTLINE -> showOutlineDialog = true
                ProjectEditorMenuAction.AI_COMPLETION -> requestAiCompletion()
                else -> action.toLineAction()?.let(::applyLineAction)
            }
        }

        LaunchedEffect(editorMenuActionSignal) {
            if (
                selectedFilePath != null &&
                editorMenuActionSignal != 0 &&
                editorMenuActionSignal != lastHandledEditorMenuSignal
            ) {
                lastHandledEditorMenuSignal = editorMenuActionSignal
                editorMenuAction?.let(::handleEditorMenuAction)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MurongGlassSurface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                surfaceColorOverride = editorChromeColor.copy(alpha = 0.14f)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MurongTagButton(text = editorLanguageLabel, onClick = {})
                    IconButton(
                        onClick = { navigateEditorHistory(backward = true) },
                        enabled = undoStack.isNotEmpty()
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "后退"
                        )
                    }
                    IconButton(
                        onClick = { navigateEditorHistory(backward = false) },
                        enabled = redoStack.isNotEmpty()
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowForward,
                            contentDescription = "前进"
                        )
                    }
                    Button(
                        onClick = ::requestSaveCurrentEditorFile,
                        enabled = dirty && !isSaving
                    ) {
                        Text(if (isSaving) "保存中" else "保存")
                    }
                    Spacer(modifier = Modifier.weight(1f))
                }
            }

            editorError?.let {
                val chromeColor = rememberMurongChromeColor()
                MurongGlassSurface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    shape = RoundedCornerShape(18.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                    surfaceColorOverride = chromeColor.copy(alpha = 0.62f)
                ) {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            MurongGlassSurface(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                surfaceColorOverride = Color.Transparent
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(editorBackgroundColor.copy(alpha = 0.72f), RoundedCornerShape(24.dp))
                        .padding(6.dp)
                ) {
                    if (isFileLoading) {
                        EmptyEditorState("正在读取文件", "请稍候，正在加载当前文件内容。")
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(editorChromeColor.copy(alpha = 0.05f), RoundedCornerShape(18.dp))
                        ) {
                            ProjectCodeEditorPane(
                                editorValue = editorValue,
                                onValueChange = { updateEditorValue(it) },
                                language = language,
                                wordWrapEnabled = editorWordWrapEnabled,
                                searchQuery = editorSearchQuery,
                                searchRequest = editorSearchRequest,
                                replaceQuery = editorReplaceQuery,
                                onSearchStateChange = { searchState ->
                                    if (searchState.currentMatch != null) {
                                        focusedSearchQuery = editorSearchQuery.trim().takeIf { it.isNotBlank() }
                                        focusedSearchLine = currentLineNumber(
                                            editorValue.text,
                                            searchState.currentMatch.start
                                        )
                                    }
                                },
                                diagnostics = editorDiagnostics,
                                initialScrollOffset = initialEditorScrollOffset,
                                onScrollOffsetChange = { offset ->
                                    selectedFilePath?.let { path ->
                                        editorScrollOffsetsByFile[path] = offset
                                    }
                                },
                                backgroundColor = editorBackgroundColor,
                                surfaceColor = editorSurfaceColor,
                                focusClearSignal = editorFocusClearSignal,
                                onEditorFocusChanged = { hasFocus ->
                                    editorTextFieldFocused = hasFocus
                                    editorFocusClearPending = false
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .navigationBarsPadding(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                quickInsertActions.forEach { action ->
                    MurongTagButton(
                        text = action.label,
                        onClick = { insertQuickAction(action) }
                    )
                }
            }
        }
        }
    )

    if (showSearchReplaceDialog) {
        ProjectScreenLargeDialog(
            title = "搜索与替换",
            onDismissRequest = { showSearchReplaceDialog = false },
            actions = {
                TextButton(onClick = { findNextInEditor() }) { Text("下一处") }
                TextButton(onClick = { replaceCurrentMatch() }) { Text("替换当前") }
                TextButton(onClick = { replaceAllMatches() }) { Text("全部替换") }
                TextButton(onClick = { showSearchReplaceDialog = false }) { Text("关闭") }
            }
        ) {
            OutlinedTextField(
                value = editorSearchQuery,
                onValueChange = { editorSearchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("搜索内容") }
            )
            OutlinedTextField(
                value = editorReplaceQuery,
                onValueChange = { editorReplaceQuery = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("替换为") }
            )
        }
    }

    if (showUnsavedChangesDialog) {
        ProjectScreenPopupDialog(
            title = "文件已修改",
            onDismissRequest = { showUnsavedChangesDialog = false },
            actions = {
                TextButton(onClick = { showUnsavedChangesDialog = false }) {
                    Text("取消")
                }
                TextButton(
                    onClick = {
                        showUnsavedChangesDialog = false
                        exitEditorPage()
                    }
                ) {
                    Text("不保存")
                }
                Button(
                    onClick = {
                        showUnsavedChangesDialog = false
                        requestSaveCurrentEditorFile()
                    },
                    enabled = !isSaving
                ) {
                    Text(if (isSaving) "保存中" else "保存")
                }
            }
        ) {
            Text(
                text = "当前文件还有未保存改动，返回前要先保存吗？",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }

    if (showLineReplaceDialog) {
        ProjectScreenPopupDialog(
            title = "替换当前行",
            onDismissRequest = { showLineReplaceDialog = false },
            actions = {
                TextButton(onClick = { showLineReplaceDialog = false }) { Text("取消") }
                TextButton(
                    onClick = {
                        val lineBlock = selectedLineBlock(editorValue.text, editorValue.selection)
                        val replacementLines = lineReplaceDraft.lines()
                        val newContent = replaceLines(editorValue.text, lineBlock.startLine..lineBlock.endLine, replacementLines)
                        updateEditorValue(
                            editorValue.copy(
                                text = newContent,
                                selection = lineSelectionForRange(
                                    newContent,
                                    lineBlock.startLine,
                                    lineBlock.startLine + replacementLines.size - 1
                                )
                            )
                        )
                        focusedSearchLine = lineBlock.startLine
                        showLineReplaceDialog = false
                    }
                ) { Text("确定") }
            }
        ) {
            OutlinedTextField(
                value = lineReplaceDraft,
                onValueChange = { lineReplaceDraft = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("新行内容") }
            )
        }
    }

    if (showAiCompletionDialog) {
        val candidate = aiCompletionCandidate
        ProjectScreenLargeDialog(
            title = "AI 自动补全",
            subtitle = if (editorValue.selection.collapsed) {
                "基于当前光标位置生成，确认后会直接插入。"
            } else {
                "基于当前选区生成，确认后会替换选中内容。"
            },
            onDismissRequest = { showAiCompletionDialog = false },
            actions = {
                TextButton(
                    onClick = { requestAiCompletion() },
                    enabled = !isAiCompleting
                ) {
                    Text(if (isAiCompleting) "生成中" else "重新生成")
                }
                TextButton(
                    onClick = { applyAiCompletionCandidate() },
                    enabled = candidate != null
                ) {
                    Text(if (editorValue.selection.collapsed) "插入" else "替换")
                }
                TextButton(onClick = { showAiCompletionDialog = false }) {
                    Text("关闭")
                }
            }
        ) {
            Text(
                text = if (editorValue.selection.collapsed) {
                    "下面是基于当前光标位置生成的补全建议，确认后会插入到光标处。"
                } else {
                    "下面是基于当前选区生成的替换建议，确认后会替换选中的内容。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(editorSurfaceColor.copy(alpha = 0.56f), RoundedCornerShape(16.dp))
                    .padding(12.dp)
            ) {
                Text(
                    text = candidate?.text ?: "",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }

    if (showOutlineDialog) {
        ProjectScreenLargeDialog(
            title = "代码大纲",
            onDismissRequest = { showOutlineDialog = false },
            actions = {
                TextButton(onClick = { showOutlineDialog = false }) { Text("关闭") }
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(320.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (outlineEntries.isEmpty()) {
                    Text(
                        text = "当前文件没有识别到可跳转的函数或代码块。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    outlineEntries.forEach { entry ->
                        TextButton(
                            onClick = { jumpToOutlineEntry(entry) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = entry.kind,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(end = 8.dp)
                                )
                                Text(
                                    text = "${"  ".repeat(entry.depth.coerceAtMost(4))}${entry.label}",
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "第 ${entry.lineNumber} 行",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showLanguageDialog) {
        ProjectScreenPopupDialog(
            title = "切换语法高亮",
            onDismissRequest = { showLanguageDialog = false },
            actions = {
                TextButton(onClick = { showLanguageDialog = false }) { Text("关闭") }
            }
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                PROJECT_LANGUAGE_CHOICES.forEach { option ->
                    TextButton(
                        onClick = {
                            languageOverride = option.value
                            showLanguageDialog = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(option.label)
                    }
                }
            }
        }
    }

    if (showDiagnosticsDialog) {
        ProjectScreenLargeDialog(
            title = "错误列表",
            subtitle = buildProjectEditorDiagnosticsDialogSubtitle(
                diagnosticsCount = editorDiagnostics.size,
                lspState = editorRuntimeLspState
            ),
            onDismissRequest = { showDiagnosticsDialog = false },
            actions = {
                TextButton(onClick = { showDiagnosticsDialog = false }) { Text("关闭") }
            }
        ) {
            val chromeColor = rememberMurongChromeColor()
            val mutedTextColor = rememberMurongMutedTextColor()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = editorRuntimeLspState.statusDetail,
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedTextColor
                )
                buildProjectEditorLspSessionFacts(editorLspSessionState).forEach { fact ->
                    Text(
                        text = fact,
                        style = MaterialTheme.typography.bodySmall,
                        color = mutedTextColor
                    )
                }
                buildProjectEditorLspLiveSessionFacts(editorLspLiveSessionState).forEach { fact ->
                    Text(
                        text = fact,
                        style = MaterialTheme.typography.bodySmall,
                        color = mutedTextColor
                    )
                }
                buildProjectEditorLspClientFacts(editorLspClientHandle).forEach { fact ->
                    Text(
                        text = fact,
                        style = MaterialTheme.typography.bodySmall,
                        color = mutedTextColor
                    )
                }
                Text(
                    text = editorLspLiveSessionState.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedTextColor
                )
                if (editorDiagnostics.isEmpty()) {
                    Text(
                        text = "当前没有识别到明显的结构错误。",
                        style = MaterialTheme.typography.bodySmall,
                        color = mutedTextColor
                    )
                } else {
                    editorDiagnostics.forEach { diagnostic ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { jumpToDiagnostic(diagnostic) },
                            shape = RoundedCornerShape(8.dp),
                            color = chromeColor.copy(alpha = 0.62f)
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "第 ${diagnostic.lineNumber} 行",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.error
                                )
                                Text(
                                    text = diagnostic.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showConflictResolverDialog) {
        ProjectScreenLargeDialog(
            title = if (currentFileName.isBlank()) {
                "处理 Git 冲突"
            } else {
                "处理 Git 冲突: $currentFileName"
            },
            subtitle = "共 ${conflictBlocks.size} 个冲突块",
            onDismissRequest = { showConflictResolverDialog = false },
            actions = {
                TextButton(onClick = { showConflictResolverDialog = false }) { Text("关闭") }
            }
        ) {
            val surfaceColor = rememberMurongSurfaceColor()
            val mutedTextColor = rememberMurongMutedTextColor()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (conflictBlocks.isEmpty()) {
                    Text(
                        text = "当前文件没有检测到 Git 冲突块。",
                        style = MaterialTheme.typography.bodySmall,
                        color = mutedTextColor
                    )
                } else {
                    conflictBlocks.forEachIndexed { index, block ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            tonalElevation = 2.dp,
                            color = surfaceColor.copy(alpha = 0.58f)
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "冲突 ${index + 1} · 第 ${block.startLine}-${block.endLine} 行",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    text = "当前: ${block.currentLabel} · 对方: ${block.incomingLabel}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = mutedTextColor
                                )
                                if (block.currentLines.isNotEmpty()) {
                                    Text(
                                        text = "当前片段: ${block.currentLines.joinToString(" ").trim().take(80)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = mutedTextColor
                                    )
                                }
                                if (block.incomingLines.isNotEmpty()) {
                                    Text(
                                        text = "对方片段: ${block.incomingLines.joinToString(" ").trim().take(80)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = mutedTextColor
                                    )
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedButton(onClick = { jumpToConflictBlock(block) }) {
                                        Text("定位")
                                    }
                                    OutlinedButton(onClick = {
                                        applyConflictResolution(block, ProjectGitConflictResolution.KEEP_CURRENT)
                                    }) {
                                        Text("保留当前")
                                    }
                                    OutlinedButton(onClick = {
                                        applyConflictResolution(block, ProjectGitConflictResolution.KEEP_INCOMING)
                                    }) {
                                        Text("保留对方")
                                    }
                                    Button(onClick = {
                                        applyConflictResolution(block, ProjectGitConflictResolution.KEEP_BOTH)
                                    }) {
                                        Text("保留双方")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectTreeRow(
    entry: ProjectTreeEntry,
    rootPath: String,
    depth: Int,
    isExpanded: Boolean,
    isSelected: Boolean,
    repoBadge: String?,
    onToggleDir: (String) -> Unit,
    onSelectFile: (String) -> Unit
) {
    val accent = rememberMurongAccentColor()
    val surfaceColor = rememberMurongSurfaceColor()
    val mutedTextColor = rememberMurongMutedTextColor()
    val background = if (isSelected) {
        accent.copy(alpha = 0.16f)
    } else {
        Color.Transparent
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (entry.isDirectory) onToggleDir(entry.absolutePath) else onSelectFile(entry.absolutePath)
            }
            .background(background)
            .padding(start = 6.dp + (depth * 14).dp, top = 6.dp, end = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (entry.isDirectory) {
            Text(
                text = if (isExpanded) "v" else ">",
                style = MaterialTheme.typography.bodyMedium,
                color = mutedTextColor
            )
            Spacer(Modifier.width(10.dp))
        } else {
            Spacer(Modifier.width(24.dp))
        }
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                text = if (depth == 0) entry.name else entry.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            repoBadge?.let { badge ->
                Text(
                    text = badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun IconText(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        androidx.compose.material3.Icon(icon, contentDescription = null)
        Text(label)
    }
}

@Composable
private fun ProjectConfigSection(
    config: ProviderConfig,
    scopeLabel: String,
    projectRules: List<GlobalRule>,
    projectMemories: List<GlobalMemory>,
    projectSkills: List<GlobalSkill>,
    projectToolPreferences: ProjectToolPreferences?,
    onUpdateProjectConfig: (List<GlobalRule>?, List<GlobalMemory>?, List<GlobalSkill>?) -> Unit,
    onUpdateProjectToolPreferences: (ProjectToolPreferences?) -> Unit
) {
    var approvalModeOverride by remember(projectToolPreferences) {
        mutableStateOf(projectToolPreferences?.approvalMode)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
            .padding(bottom = 168.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        MurongInfoCard(title = "", titleVisible = false) {
            Text("项目配置", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = scopeLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "这里的规则、记忆、技能和审批策略都跟当前仓库作用域绑定，不再混成整个工作区的一锅。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        ProjectSectionCard {
            Text("审批模式", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = buildApprovalModeHostPresentation(
                    globalMode = config.approvalMode,
                    overrideMode = approvalModeOverride
                ).message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        approvalModeOverride = null
                        onUpdateProjectToolPreferences(
                            normalizeProjectToolPreferences(
                                (projectToolPreferences ?: ProjectToolPreferences()).copy(approvalMode = null)
                            )
                        )
                    }
                    .padding(vertical = 4.dp)
            ) {
                RadioButton(
                    selected = approvalModeOverride == null,
                    onClick = {
                        approvalModeOverride = null
                        onUpdateProjectToolPreferences(
                            normalizeProjectToolPreferences(
                                (projectToolPreferences ?: ProjectToolPreferences()).copy(approvalMode = null)
                            )
                        )
                    }
                )
                Spacer(Modifier.width(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("跟随全局", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        buildApprovalModeFollowGlobalOptionPresentation(config.approvalMode).subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            buildApprovalModeOptionPresentations().forEach { optionPresentation ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            approvalModeOverride = optionPresentation.mode
                            onUpdateProjectToolPreferences(
                                normalizeProjectToolPreferences(
                                    (projectToolPreferences ?: ProjectToolPreferences()).copy(
                                        approvalMode = optionPresentation.mode
                                    )
                                )
                            )
                        }
                        .padding(vertical = 4.dp)
                ) {
                    RadioButton(
                        selected = approvalModeOverride == optionPresentation.mode,
                        onClick = {
                            approvalModeOverride = optionPresentation.mode
                            onUpdateProjectToolPreferences(
                                normalizeProjectToolPreferences(
                                    (projectToolPreferences ?: ProjectToolPreferences()).copy(
                                        approvalMode = optionPresentation.mode
                                    )
                                )
                            )
                        }
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(optionPresentation.title, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            optionPresentation.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        SectionTitle("规则 (${projectRules.size})")
        ProjectRuleEditorInline(rules = projectRules, onRulesChanged = { onUpdateProjectConfig(it, null, null) })

        SectionTitle("记忆 (${projectMemories.size})")
        ProjectMemoryEditorInline(memories = projectMemories, onMemoriesChanged = { onUpdateProjectConfig(null, it, null) })

        SectionTitle("技能 (${projectSkills.size})")
        ProjectSkillEditorInline(skills = projectSkills, onSkillsChanged = { onUpdateProjectConfig(null, null, it) })
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp)
    )
}

@Composable
private fun ProjectRuleEditorInline(rules: List<GlobalRule>, onRulesChanged: (List<GlobalRule>) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ProjectInsetCard {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("启用且内容非空的项目规则会注入当前项目对话上下文。", style = MaterialTheme.typography.bodySmall)
                Text(
                    "如果你在聊天里明确要求“保存为项目规则”，模型会手动创建；不会自动识别普通对话。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (rules.isEmpty()) {
            ProjectInsetCard {
                Text(
                    "暂无项目规则，可添加例如“先看 logcat 再改代码”“这个仓库一律用中文总结”等项目级约束。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            rules.forEach { rule ->
                ProjectRuleCard(
                    rule = rule,
                    onChanged = { updatedRule ->
                        onRulesChanged(rules.map { if (it.id == updatedRule.id) updatedRule else it })
                    },
                    onDelete = {
                        onRulesChanged(rules.filterNot { it.id == rule.id })
                    }
                )
            }
        }
        RuleDraftImportCard(
            onImportDrafts = { imported ->
                if (imported.isNotEmpty()) {
                    onRulesChanged(rules + imported)
                }
            },
            buttonLabel = "手动导入规则"
        )
        TextButton(onClick = {
            val updated = rules + GlobalRule(
                id = UUID.randomUUID().toString().take(8),
                title = "新规则",
                content = ""
            )
            onRulesChanged(updated)
        }) { Text("+ 添加规则") }
    }
}

@Composable
private fun ProjectMemoryEditorInline(memories: List<GlobalMemory>, onMemoriesChanged: (List<GlobalMemory>) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ProjectInsetCard {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("项目记忆适合保存仓库长期事实、约定和背景。", style = MaterialTheme.typography.bodySmall)
                Text(
                    "启用且内容非空时会进入当前项目上下文；只有你明确要求保存时，模型才会手动创建。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (memories.isEmpty()) {
            ProjectInsetCard {
                Text(
                    "暂无项目记忆，可添加例如“发布分支命名规则”“这个目录是设备端实际挂载点”等长期背景。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            memories.forEach { memory ->
                ProjectMemoryCard(
                    memory = memory,
                    onChanged = { updatedMemory ->
                        onMemoriesChanged(memories.map { if (it.id == updatedMemory.id) updatedMemory else it })
                    },
                    onDelete = {
                        onMemoriesChanged(memories.filterNot { it.id == memory.id })
                    }
                )
            }
        }
        MemoryDraftImportCard(
            onImportDrafts = { imported ->
                if (imported.isNotEmpty()) {
                    onMemoriesChanged(memories + imported)
                }
            },
            buttonLabel = "手动导入记忆"
        )
        TextButton(onClick = {
            val updated = memories + GlobalMemory(
                id = UUID.randomUUID().toString().take(8),
                title = "新记忆",
                content = ""
            )
            onMemoriesChanged(updated)
        }) { Text("+ 添加记忆") }
    }
}

@Composable
private fun ProjectRuleCard(
    rule: GlobalRule,
    onChanged: (GlobalRule) -> Unit,
    onDelete: () -> Unit
) {
    ProjectInsetCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (rule.enabled) "已启用" else "已停用",
                    color = if (rule.enabled) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = rule.enabled,
                        onCheckedChange = { checked ->
                            onChanged(rule.copy(enabled = checked))
                        }
                    )
                    TextButton(onClick = onDelete) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            OutlinedTextField(
                value = rule.title,
                onValueChange = { onChanged(rule.copy(title = it)) },
                label = { Text("规则标题") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall
            )

            OutlinedTextField(
                value = rule.content,
                onValueChange = { onChanged(rule.copy(content = it)) },
                label = { Text("规则内容") },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 100.dp),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                minLines = 4,
                maxLines = 8
            )
        }
    }
}

@Composable
private fun ProjectMemoryCard(
    memory: GlobalMemory,
    onChanged: (GlobalMemory) -> Unit,
    onDelete: () -> Unit
) {
    ProjectInsetCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (memory.enabled) "已启用" else "已停用",
                    color = if (memory.enabled) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = memory.enabled,
                        onCheckedChange = { checked ->
                            onChanged(memory.copy(enabled = checked))
                        }
                    )
                    TextButton(onClick = onDelete) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            OutlinedTextField(
                value = memory.title,
                onValueChange = { onChanged(memory.copy(title = it)) },
                label = { Text("记忆标题") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall
            )

            OutlinedTextField(
                value = memory.content,
                onValueChange = { onChanged(memory.copy(content = it)) },
                label = { Text("记忆内容") },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 100.dp),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                minLines = 4,
                maxLines = 8
            )
        }
    }
}

@Composable
private fun ProjectSkillEditorInline(skills: List<GlobalSkill>, onSkillsChanged: (List<GlobalSkill>) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        skills.forEach { skill ->
            ProjectInsetCard {
                Column(Modifier.padding(8.dp)) {
                    Text(
                        skill.title.ifBlank { "未命名技能" },
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        skill.description.take(60),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        SkillDraftImportCard(onImportDrafts = { imported ->
            val merged = (skills + imported).distinctBy { it.id }
            onSkillsChanged(merged)
        })
    }
}

@Composable
internal fun ProjectSectionCard(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(20.dp),
    surfaceColorOverride: Color? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    MurongGlassSurface(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        contentPadding = PaddingValues(14.dp),
        surfaceColorOverride = surfaceColorOverride,
        content = content
    )
}

@Composable
internal fun ProjectInsetCard(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(16.dp),
    surfaceColorOverride: Color? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val fallback = rememberMurongSurfaceColor().copy(alpha = 0.42f)
    MurongGlassSurface(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        contentPadding = PaddingValues(8.dp),
        surfaceColorOverride = surfaceColorOverride ?: fallback,
        content = content
    )
}

@Composable
private fun ProjectLocalGitChangeRow(
    change: ProjectGitFileChangeUi,
    badgeLabel: String,
    actionLabel: String,
    actionEnabled: Boolean,
    onOpenDiff: (() -> Unit)?,
    onAction: (() -> Unit)?
) {
    val mutedTextColor = rememberMurongMutedTextColor()
    val surfaceColor = rememberMurongSurfaceColor()
    ProjectInsetCard(
        shape = RoundedCornerShape(12.dp),
        surfaceColorOverride = surfaceColor.copy(alpha = 0.52f)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = change.displayPath,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = badgeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = mutedTextColor
                )
            }
            onOpenDiff?.let {
                TextButton(onClick = it) {
                    Text("差异")
                }
            }
            onAction?.let {
                OutlinedButton(
                    onClick = it,
                    enabled = actionEnabled
                ) {
                    Text(actionLabel)
                }
            }
        }
    }
}

@Composable
private fun ProjectLocalGitRepositoryCard(
    repoLabel: String?,
    repoSubtitle: String?,
    status: ProjectGitStatusUi,
    recentOperationRecords: List<ProjectGitOperationRecordUi>,
    isActive: Boolean,
    isBusy: Boolean,
    onSelectRepo: (() -> Unit)?,
    onRefresh: () -> Unit,
    onStagePath: (ProjectGitFileChangeUi) -> Unit,
    onOpenDiff: (ProjectGitFileChangeUi) -> Unit,
    onUnstagePath: ((ProjectGitFileChangeUi) -> Unit)? = null,
    onStageAll: (() -> Unit)? = null,
    onSyncAll: (() -> Unit)? = null
) {
    val chromeColor = rememberMurongChromeColor()
    val mutedTextColor = rememberMurongMutedTextColor()
    val pendingChanges = remember(status.modifiedFiles, status.untrackedFiles) {
        status.modifiedFiles + status.untrackedFiles
    }
    val operationSummary = remember(recentOperationRecords) {
        buildProjectGitOperationSummary(recentOperationRecords)
    }
    ProjectSectionCard(
        shape = RoundedCornerShape(14.dp),
        surfaceColorOverride = if (isActive) {
            chromeColor.copy(alpha = 0.46f)
        } else {
            chromeColor.copy(alpha = 0.28f)
        }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (repoLabel != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = repoLabel,
                            style = MaterialTheme.typography.titleSmall
                        )
                        repoSubtitle?.takeIf { it.isNotBlank() }?.let { subtitle ->
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = mutedTextColor
                            )
                        }
                    }
                    if (isActive) {
                        Text(
                            text = "当前仓库",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        onSelectRepo?.let {
                            OutlinedButton(onClick = it) {
                                Text("切换")
                            }
                        }
                    }
                }
            }
            Text(
                text = "更改:",
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text = if (status.branchSummary.isNotBlank()) {
                    "当前分支: ${status.branchSummary}"
                } else {
                    "当前分支: 未知"
                },
                style = MaterialTheme.typography.bodySmall,
                color = mutedTextColor
            )
            if (pendingChanges.isEmpty()) {
                Text(
                    text = "当前没有未暂存更改。",
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedTextColor
                )
            } else {
                pendingChanges.forEach { change ->
                    ProjectLocalGitChangeRow(
                        change = change,
                        badgeLabel = if (status.untrackedFiles.any { it.actionPath == change.actionPath }) {
                            "未跟踪"
                        } else {
                            "已修改"
                        },
                        actionLabel = "暂存",
                        actionEnabled = !isBusy,
                        onOpenDiff = { onOpenDiff(change) },
                        onAction = { onStagePath(change) }
                    )
                }
            }
            if (status.stagedFiles.isNotEmpty()) {
                Text(
                    text = "已暂存 ${status.stagedFiles.size} 个文件",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                status.stagedFiles.take(6).forEach { change ->
                    ProjectLocalGitChangeRow(
                        change = change,
                        badgeLabel = "已暂存",
                        actionLabel = "取消暂存",
                        actionEnabled = !isBusy,
                        onOpenDiff = { onOpenDiff(change) },
                        onAction = onUnstagePath?.let { { it(change) } }
                    )
                }
            }
            if (status.conflictedFiles.isNotEmpty()) {
                Text(
                    text = "冲突 ${status.conflictedFiles.size} 个文件",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error
                )
                status.conflictedFiles.take(4).forEach { change ->
                    ProjectLocalGitChangeRow(
                        change = change,
                        badgeLabel = "存在冲突",
                        actionLabel = "差异",
                        actionEnabled = !isBusy,
                        onOpenDiff = { onOpenDiff(change) },
                        onAction = { onOpenDiff(change) }
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onRefresh,
                    enabled = !isBusy
                ) {
                    Text("刷新")
                }
                OutlinedButton(
                    onClick = { onStageAll?.invoke() },
                    enabled = onStageAll != null && !isBusy
                ) {
                    Text("全部暂存")
                }
                OutlinedButton(
                    onClick = { onSyncAll?.invoke() },
                    enabled = onSyncAll != null && !isBusy
                ) {
                    Text("全部同步")
                }
            }
            Text(
                text = "推送拉取记录",
                style = MaterialTheme.typography.titleSmall
            )
            if (recentOperationRecords.isEmpty()) {
                Text(
                    text = "当前仓库还没有记录，后续的暂存、同步、提交和分支操作都会显示在这里。",
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedTextColor
                )
            } else {
                Text(
                    text = buildString {
                        append("共 ${operationSummary.totalCount} 条")
                        append(" · 成功 ${operationSummary.successCount}")
                        append(" · 同步 ${operationSummary.syncCount}")
                        operationSummary.latestTimeLabel?.let { append(" · 最近 $it") }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedTextColor
                )
                recentOperationRecords.firstOrNull()?.let { record ->
                    Text(
                        text = "${record.categoryLabel} · ${record.title}",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (record.isSuccess) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )
                    Text(
                        text = record.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = mutedTextColor,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Text(
                text = buildString {
                    append("上游: ")
                    append(status.upstreamBranch?.takeIf { it.isNotBlank() } ?: "未绑定")
                },
                style = MaterialTheme.typography.bodySmall,
                color = mutedTextColor
            )
            Text(
                text = "领先 ${status.aheadCount} · 落后 ${status.behindCount} · 远端分支 ${status.remoteBranches.size}",
                style = MaterialTheme.typography.bodySmall,
                color = mutedTextColor
            )
            status.remoteUrl?.takeIf { it.isNotBlank() }?.let { remote ->
                Text(
                    text = remote,
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedTextColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ProjectGitDeferredContent(
    content: @Composable () -> Unit
) {
    content()
}

@Composable
private fun ProjectGitSectionColumn(
    fillAvailableHeight: Boolean = false,
    scrollable: Boolean = true,
    showSectionTitle: Boolean = true,
    showRefreshAction: Boolean = false,
    isRefreshEnabled: Boolean = true,
    onRefresh: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val bottomBarScrollPadding = rememberMurongBottomBarScrollPadding()
    val columnModifier = if (fillAvailableHeight) {
        Modifier.fillMaxSize()
    } else {
        Modifier.fillMaxWidth()
    }
    Column(
        modifier = columnModifier
            .then(
                if (scrollable) {
                    Modifier.verticalScroll(rememberScrollState())
                } else {
                    Modifier
                }
            )
            .then(
                if (fillAvailableHeight) {
                    Modifier
                } else {
                    Modifier.padding(
                        start = 16.dp,
                        top = 16.dp,
                        end = 16.dp,
                        bottom = if (scrollable) bottomBarScrollPadding else 16.dp
                    )
                }
            ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (showSectionTitle) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Git / GitHub", style = MaterialTheme.typography.titleMedium)
                if (showRefreshAction && onRefresh != null) {
                    OutlinedButton(
                        onClick = onRefresh,
                        enabled = isRefreshEnabled
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("刷新")
                    }
                }
            }
        }
        content()
    }
}

@Composable
private fun ProjectGitSection(
    config: ProviderConfig,
    currentProjectPath: String?,
    detectedRepos: List<ProjectDetectedRepoUi>,
    selectedRepoRoot: String?,
    onSelectRepoRoot: (String) -> Unit,
    selectedViewerTaskRepository: ProjectGitHubRepoRef?,
    draftPathCount: Int,
    snapshotCount: Int,
    mcpToolCount: Int,
    onProjectSecondaryPageChanged: (ProjectSecondaryChromeState) -> Unit,
    onRegisterSecondaryCloseRequest: (((() -> Unit)?) -> Unit),
    selectedPrimaryTab: ProjectPrimaryTab,
    onSelectPrimaryTab: (ProjectPrimaryTab) -> Unit,
    onUpdateSelectedViewerTaskRepository: (ProjectGitHubRepoRef?) -> Unit,
    primaryChromeColor: Color,
    primaryMutedTextColor: Color,
    projectSecondaryBackProgress: Float,
    onProjectSecondaryBackProgressChanged: (Float) -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val projectStoragePath = currentProjectPath?.takeIf { it.isNotBlank() }
        ?: context.filesDir.absolutePath
    var downloadStore by remember(currentProjectPath) {
        mutableStateOf(loadProjectGitHubDownloadStore(projectStoragePath))
    }
    val repoStatusSummaries = remember(currentProjectPath) { mutableStateMapOf<String, ProjectGitStatusUi>() }
    val repoCardExpandedStates = remember(currentProjectPath) { mutableStateMapOf<String, Boolean>() }
    val activeProjectPath = selectedRepoRoot ?: currentProjectPath
    var gitState by remember(activeProjectPath) { mutableStateOf(ProjectGitStatusUi.empty(activeProjectPath)) }
    var isGitLoading by remember(activeProjectPath) { mutableStateOf(false) }
    var isGitActionRunning by remember(activeProjectPath) { mutableStateOf(false) }
    var feedbackMessage by remember(activeProjectPath) { mutableStateOf<String?>(null) }
    var pendingDiscardTarget by remember(currentProjectPath) {
        mutableStateOf<ProjectGitDiscardTargetUi?>(null)
    }
    var gitOperationRecords by remember(currentProjectPath) {
        mutableStateOf(listOf<ProjectGitOperationRecordUi>())
    }
    var showCommitDialog by remember(activeProjectPath) { mutableStateOf(false) }
    var commitTitleDraft by remember(activeProjectPath) { mutableStateOf("") }
    var commitDetailDraft by remember(activeProjectPath) { mutableStateOf("") }
    var diffPreview by remember(activeProjectPath) { mutableStateOf<ProjectGitDiffPreviewUi?>(null) }
    var showBranchDialog by remember(activeProjectPath) { mutableStateOf(false) }
    var newBranchName by remember(activeProjectPath) { mutableStateOf("") }
    var githubActionsState by remember(activeProjectPath) { mutableStateOf(ProjectGitHubActionsState.empty()) }
    var isGitHubLoading by remember(activeProjectPath) { mutableStateOf(false) }
    var isGitHubActionRunning by remember(activeProjectPath) { mutableStateOf(false) }
    var workflowDispatchTarget by remember(activeProjectPath) { mutableStateOf<ProjectGitHubWorkflowUi?>(null) }
    var workflowDispatchRefDraft by remember(activeProjectPath) { mutableStateOf("") }
    var workflowDispatchInputs by remember(activeProjectPath) {
        mutableStateOf(listOf<ProjectGitHubWorkflowDispatchInputUi>())
    }
    var workflowDispatchBranchRefs by remember(activeProjectPath) { mutableStateOf(listOf<String>()) }
    var isWorkflowDispatchBranchesLoading by remember(activeProjectPath) { mutableStateOf(false) }
    var isWorkflowDispatchSchemaLoading by remember(activeProjectPath) { mutableStateOf(false) }
    var workflowDispatchSchemaMessage by remember(activeProjectPath) { mutableStateOf<String?>(null) }
    var artifactDialogState by remember(activeProjectPath) { mutableStateOf<ProjectGitHubArtifactDialogUi?>(null) }
    var workflowRunDetailDialogState by remember(activeProjectPath) { mutableStateOf<ProjectGitHubWorkflowRunDetailUi?>(null) }
    var showInitGitDialog by remember(activeProjectPath) { mutableStateOf(false) }
    var initBranchDraft by remember(activeProjectPath) { mutableStateOf("main") }
    var initContinueToCreateGitHubRepo by remember(activeProjectPath) { mutableStateOf(false) }
    var showCreateGitHubRepoDialog by remember(activeProjectPath) { mutableStateOf(false) }
    var createGitHubRepoNameDraft by remember(activeProjectPath) {
        mutableStateOf(suggestProjectGitHubRepoName(activeProjectPath))
    }
    var createGitHubRepoDescriptionDraft by remember(activeProjectPath) { mutableStateOf("") }
    var createGitHubRepoPrivateFlag by remember(activeProjectPath) { mutableStateOf(false) }
    var createGitHubRepoBindOriginFlag by remember(activeProjectPath) { mutableStateOf(true) }
    var showCreateReleaseDialog by remember(activeProjectPath) { mutableStateOf(false) }
    var releaseEditTarget by remember(activeProjectPath) { mutableStateOf<ProjectGitHubReleaseUi?>(null) }
    var releaseDraftState by remember(activeProjectPath) {
        mutableStateOf(clearProjectGitHubReleaseDraftState())
    }
    var releaseAssetDialogState by remember(activeProjectPath) { mutableStateOf<ProjectGitHubReleaseAssetDialogUi?>(null) }
    var remoteRepoState by remember(activeProjectPath) { mutableStateOf(ProjectGitHubRemoteBrowserState.empty()) }
    var isRemoteRepoLoading by remember(activeProjectPath) { mutableStateOf(false) }
    var remoteRepoRefDraft by remember(activeProjectPath) { mutableStateOf("") }
    var remoteFileDialogState by remember(activeProjectPath) { mutableStateOf<ProjectGitHubRemoteFileUi?>(null) }
    var remoteFileContentDraft by remember(activeProjectPath) { mutableStateOf("") }
    var remoteFileCommitMessageDraft by remember(activeProjectPath) { mutableStateOf("") }
    var pendingRemoteFileSaveConfirmation by remember(activeProjectPath) { mutableStateOf(false) }
    var issueDetailStore by remember(activeProjectPath) {
        mutableStateOf(clearProjectGitHubIssueDetailStore())
    }
    var pullRequestDetailStore by remember(activeProjectPath) {
        mutableStateOf(clearProjectGitHubPullRequestDetailStore())
    }
    var showCreateIssueDialog by remember(activeProjectPath) { mutableStateOf(false) }
    var createIssueDraftState by remember(activeProjectPath) {
        mutableStateOf(clearProjectGitHubIssueDraftState())
    }
    var showCreatePullRequestDialog by remember(activeProjectPath) { mutableStateOf(false) }
    var createPullRequestDraftState by remember(activeProjectPath) {
        mutableStateOf(
            clearProjectGitHubPullRequestDraftState(
                currentBranch = gitState.currentBranch,
                defaultBranch = githubActionsState.defaultBranch,
                upstreamBranch = gitState.upstreamBranch
            )
        )
    }
    var viewerRepositoriesState by remember(currentProjectPath) {
        mutableStateOf(ProjectGitHubViewerRepositoriesState.empty())
    }
    var isViewerRepositoriesLoading by remember(currentProjectPath) { mutableStateOf(false) }
    var standaloneNavigationState by remember(currentProjectPath) {
        mutableStateOf(clearProjectGitHubStandaloneNavigationState())
    }
    var showStandaloneRepoBrowserSecondary by remember(currentProjectPath) {
        mutableStateOf(false)
    }
    var selectedViewerReadme by remember(currentProjectPath) {
        mutableStateOf<ProjectGitHubReadmeUi?>(null)
    }
    var selectedViewerReadmeErrorMessage by remember(currentProjectPath) {
        mutableStateOf<String?>(null)
    }
    var isViewerReadmeLoading by remember(currentProjectPath) { mutableStateOf(false) }
    var viewerRepositoryMenuTarget by remember(currentProjectPath) {
        mutableStateOf<ProjectGitHubAccountRepoUi?>(null)
    }
    var viewerDescriptionEditTarget by remember(currentProjectPath) {
        mutableStateOf<ProjectGitHubAccountRepoUi?>(null)
    }
    var viewerDescriptionDraft by remember(currentProjectPath) {
        mutableStateOf("")
    }
    var suspectedRepoMenuTarget by remember(currentProjectPath) {
        mutableStateOf<ProjectDetectedRepoUi?>(null)
    }
    var standaloneSecondaryCloseRequest by remember(currentProjectPath) {
        mutableStateOf<(() -> Unit)?>(null)
    }
    val activeDetectedRepo = remember(activeProjectPath, detectedRepos) {
        activeProjectPath?.let { path ->
            detectedRepos.firstOrNull { it.rootPath == path }
        }
    }
    val activeRepoFeatureLabels = remember(activeDetectedRepo) {
        activeDetectedRepo?.let(::buildProjectDetectedRepoFeatureLabels).orEmpty()
    }
    val selectedViewerRepository = standaloneNavigationState.selectedRepo
    val selectedViewerSection = standaloneNavigationState.selectedSection
    val currentRepoOperationRecords = remember(gitOperationRecords, gitState.repoRoot) {
        val currentRepoRoot = gitState.repoRoot
        if (currentRepoRoot.isNullOrBlank()) {
            gitOperationRecords
        } else {
            gitOperationRecords.filter { it.repoRoot == currentRepoRoot }
        }
    }

    fun resetCommitDraft() {
        commitTitleDraft = ""
        commitDetailDraft = ""
    }

    fun resetReleaseDraft() {
        releaseDraftState = clearProjectGitHubReleaseDraftState()
    }

    fun resetCreateGitHubRepoDraft() {
        createGitHubRepoNameDraft = suggestProjectGitHubRepoName(activeProjectPath)
        createGitHubRepoDescriptionDraft = suggestProjectGitHubRepoDescription(activeDetectedRepo)
        createGitHubRepoPrivateFlag = false
        createGitHubRepoBindOriginFlag = true
    }

    fun resetCreateIssueDraft() {
        createIssueDraftState = clearProjectGitHubIssueDraftState()
    }

    fun resetCreatePullRequestDraft() {
        createPullRequestDraftState = clearProjectGitHubPullRequestDraftState(
            currentBranch = gitState.currentBranch,
            defaultBranch = githubActionsState.defaultBranch,
            upstreamBranch = gitState.upstreamBranch
        )
    }

    fun appendGitOperationRecord(
        title: String,
        detail: String,
        repoRoot: String?,
        isSuccess: Boolean
    ) {
        gitOperationRecords = listOf(
            createProjectGitOperationRecord(
                title = title,
                detail = detail,
                repoRoot = repoRoot,
                isSuccess = isSuccess
            )
        ) + gitOperationRecords.take(11)
    }

    fun updateSelectedViewerTaskRepository(repo: ProjectGitHubRepoRef?) {
        onUpdateSelectedViewerTaskRepository(repo)
    }

    fun loadIssueComments(issueNumber: Long) {
        val repo = githubActionsState.repo
        val token = config.githubToken.trim()
        issueDetailStore = beginProjectGitHubIssueCommentsRefresh(issueDetailStore)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                loadProjectGitHubCommentsForDetail(
                    repo = repo,
                    issueNumber = issueNumber,
                    token = token,
                    apiBaseUrl = config.getGitHubApiBaseUrl(),
                    errorFallback = "读取 Issue 评论失败"
                )
            }
            if (issueDetailStore.issue?.number == issueNumber) {
                issueDetailStore = applyProjectGitHubIssueCommentsRefreshResult(
                    currentStore = issueDetailStore,
                    result = result
                )
                result.feedbackMessage?.let { feedbackMessage = it }
            }
        }
    }

    fun loadPullRequestComments(pullNumber: Long) {
        val repo = githubActionsState.repo
        val token = config.githubToken.trim()
        pullRequestDetailStore = beginProjectGitHubPullRequestCommentsRefresh(pullRequestDetailStore)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                loadProjectGitHubCommentsForDetail(
                    repo = repo,
                    issueNumber = pullNumber,
                    token = token,
                    apiBaseUrl = config.getGitHubApiBaseUrl(),
                    errorFallback = "读取 Pull Request 评论失败"
                )
            }
            if (pullRequestDetailStore.pullRequest?.number == pullNumber) {
                pullRequestDetailStore = applyProjectGitHubPullRequestCommentsRefreshResult(
                    currentStore = pullRequestDetailStore,
                    result = result
                )
                result.feedbackMessage?.let { feedbackMessage = it }
            }
        }
    }

    fun loadPullRequestReviews(pullNumber: Long) {
        val repo = githubActionsState.repo
        val token = config.githubToken.trim()
        pullRequestDetailStore = beginProjectGitHubPullRequestReviewsRefresh(pullRequestDetailStore)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                loadProjectGitHubPullRequestReviewsForDetail(
                    repo = repo,
                    pullNumber = pullNumber,
                    token = token,
                    apiBaseUrl = config.getGitHubApiBaseUrl()
                )
            }
            if (pullRequestDetailStore.pullRequest?.number == pullNumber) {
                pullRequestDetailStore = applyProjectGitHubPullRequestReviewsRefreshResult(
                    currentStore = pullRequestDetailStore,
                    result = result
                )
                result.feedbackMessage?.let { feedbackMessage = it }
            }
        }
    }

    fun loadPullRequestFiles(pullNumber: Long) {
        val repo = githubActionsState.repo
        val token = config.githubToken.trim()
        pullRequestDetailStore = beginProjectGitHubPullRequestFilesRefresh(pullRequestDetailStore)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                loadProjectGitHubPullRequestFilesForDetail(
                    repo = repo,
                    pullNumber = pullNumber,
                    token = token,
                    apiBaseUrl = config.getGitHubApiBaseUrl()
                )
            }
            if (pullRequestDetailStore.pullRequest?.number == pullNumber) {
                pullRequestDetailStore = applyProjectGitHubPullRequestFilesRefreshResult(
                    currentStore = pullRequestDetailStore,
                    result = result
                )
                result.feedbackMessage?.let { feedbackMessage = it }
            }
        }
    }

    fun loadPullRequestReviewComments(pullNumber: Long) {
        val repo = githubActionsState.repo
        val token = config.githubToken.trim()
        pullRequestDetailStore = beginProjectGitHubPullRequestReviewCommentsRefresh(pullRequestDetailStore)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                loadProjectGitHubPullRequestReviewCommentsForDetail(
                    repo = repo,
                    pullNumber = pullNumber,
                    token = token,
                    apiBaseUrl = config.getGitHubApiBaseUrl()
                )
            }
            if (pullRequestDetailStore.pullRequest?.number == pullNumber) {
                pullRequestDetailStore = applyProjectGitHubPullRequestReviewCommentsRefreshResult(
                    currentStore = pullRequestDetailStore,
                    result = result
                )
                result.feedbackMessage?.let { feedbackMessage = it }
            }
        }
    }

    fun openIssueDetail(issue: ProjectGitHubIssueUi) {
        issueDetailStore = openProjectGitHubIssueDetailStore(issue)
        loadIssueComments(issue.number)
    }

    fun openPullRequestDetail(pullRequest: ProjectGitHubPullRequestUi) {
        val repo = githubActionsState.repo
        val token = config.githubToken.trim()
        pullRequestDetailStore = openProjectGitHubPullRequestDetailStore(pullRequest)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                loadProjectGitHubPullRequestDetailBootstrapData(
                    repo = repo,
                    pullNumber = pullRequest.number,
                    token = token,
                    apiBaseUrl = config.getGitHubApiBaseUrl()
                )
            }
            if (pullRequestDetailStore.pullRequest?.number == pullRequest.number) {
                pullRequestDetailStore = applyProjectGitHubPullRequestDetailBootstrapLoadResult(
                    currentStore = pullRequestDetailStore,
                    result = result
                )
                result.feedbackMessage?.let { feedbackMessage = it }
            }
        }
    }

    fun openGitHubPage(url: String?, fallbackMessage: String) {
        val target = url?.trim().orEmpty()
        if (target.isBlank()) {
            feedbackMessage = fallbackMessage
            return
        }
        runCatching { uriHandler.openUri(target) }
            .onFailure { error ->
                feedbackMessage = error.message ?: "无法打开 GitHub 页面"
            }
    }

    fun recordGitHubDownload(
        typeLabel: String,
        title: String,
        fileName: String,
        downloadId: Long,
        sourceUrl: String?,
        repo: ProjectGitHubRepoRef? = null,
        repoLabel: String? = null
    ) {
        val newStore = recordProjectGitHubDownload(
            currentStore = downloadStore,
            typeLabel = typeLabel,
            title = title,
            fileName = fileName,
            downloadId = downloadId,
            sourceUrl = sourceUrl,
            repo = repo,
            repoLabel = repoLabel
        )
        downloadStore = newStore
        saveProjectGitHubDownloadStore(projectStoragePath, newStore)
    }

    fun deleteGitHubDownloadRecord(recordId: String) {
        val newStore = deleteProjectGitHubDownloadRecord(downloadStore, recordId)
        downloadStore = newStore
        saveProjectGitHubDownloadStore(projectStoragePath, newStore)
    }

    fun clearGitHubDownloadHistory() {
        val newStore = clearProjectGitHubDownloadHistory(downloadStore)
        downloadStore = newStore
        saveProjectGitHubDownloadStore(projectStoragePath, newStore)
    }

    fun refreshGitState() {
        val projectPath = activeProjectPath
        if (projectPath.isNullOrBlank()) {
            gitState = ProjectGitStatusUi.empty(projectPath)
            return
        }
        scope.launch {
            isGitLoading = true
            val status = withContext(Dispatchers.IO) { loadProjectGitStatus(projectPath) }
            gitState = status
            repoStatusSummaries[projectPath] = status
            isGitLoading = false
        }
    }

    fun syncRepoStatus(rootPath: String, status: ProjectGitStatusUi) {
        repoStatusSummaries[rootPath] = status
        if (activeProjectPath == rootPath) {
            gitState = status
        }
    }

    fun refreshRepoStatus(rootPath: String) {
        scope.launch {
            val isActiveRepo = activeProjectPath == rootPath
            if (isActiveRepo) {
                isGitLoading = true
            }
            val status = withContext(Dispatchers.IO) { loadProjectGitStatus(rootPath) }
            syncRepoStatus(rootPath, status)
            if (isActiveRepo) {
                isGitLoading = false
            }
        }
    }

    fun runGitAction(
        successFallback: String,
        block: suspend () -> ProjectGitCommandResult
    ) {
        scope.launch {
            isGitActionRunning = true
            val result = withContext(Dispatchers.IO) { block() }
            val detailMessage = when {
                result.success -> result.output.ifBlank { successFallback }
                else -> result.error ?: result.output.ifBlank { "Git 操作失败" }
            }
            feedbackMessage = detailMessage
            appendGitOperationRecord(
                title = successFallback,
                detail = detailMessage,
                repoRoot = gitState.repoRoot,
                isSuccess = result.success
            )
            isGitActionRunning = false
            if (result.success) {
                refreshGitState()
            }
        }
    }

    fun runGitAction(
        successFallback: String,
        onSuccess: () -> Unit,
        block: suspend () -> ProjectGitCommandResult
    ) {
        scope.launch {
            isGitActionRunning = true
            val result = withContext(Dispatchers.IO) { block() }
            val detailMessage = when {
                result.success -> result.output.ifBlank { successFallback }
                else -> result.error ?: result.output.ifBlank { "Git 操作失败" }
            }
            feedbackMessage = detailMessage
            appendGitOperationRecord(
                title = successFallback,
                detail = detailMessage,
                repoRoot = gitState.repoRoot,
                isSuccess = result.success
            )
            isGitActionRunning = false
            if (result.success) {
                refreshGitState()
                onSuccess()
            }
        }
    }

    fun runGitActionForRepo(
        repoRoot: String,
        successFallback: String,
        block: suspend () -> ProjectGitCommandResult
    ) {
        scope.launch {
            isGitActionRunning = true
            val result = withContext(Dispatchers.IO) { block() }
            val detailMessage = when {
                result.success -> result.output.ifBlank { successFallback }
                else -> result.error ?: result.output.ifBlank { "Git 操作失败" }
            }
            feedbackMessage = detailMessage
            appendGitOperationRecord(
                title = successFallback,
                detail = detailMessage,
                repoRoot = repoRoot,
                isSuccess = result.success
            )
            isGitActionRunning = false
            if (result.success) {
                val status = withContext(Dispatchers.IO) { loadProjectGitStatus(repoRoot) }
                syncRepoStatus(repoRoot, status)
            }
        }
    }

    fun openDiff(change: ProjectGitFileChangeUi) {
        val repoRoot = gitState.repoRoot ?: return
        scope.launch {
            isGitActionRunning = true
            val preview = withContext(Dispatchers.IO) { loadProjectGitDiffPreview(repoRoot, change) }
            isGitActionRunning = false
            if (preview == null) {
                feedbackMessage = "无法读取该文件的 Git 差异"
            } else {
                diffPreview = preview
            }
        }
    }

    fun openDiffForRepo(repoRoot: String, change: ProjectGitFileChangeUi) {
        scope.launch {
            isGitActionRunning = true
            val preview = withContext(Dispatchers.IO) { loadProjectGitDiffPreview(repoRoot, change) }
            isGitActionRunning = false
            if (preview == null) {
                feedbackMessage = "无法读取该文件的 Git 差异"
            } else {
                diffPreview = preview
            }
        }
    }

    fun refreshGitHubActions() {
        val repo = parseProjectGitHubRepoRef(gitState.remoteUrl)
        if (!gitState.isRepository) {
            githubActionsState = ProjectGitHubActionsState.empty()
            remoteRepoState = ProjectGitHubRemoteBrowserState.empty()
            return
        }
        if (repo == null) {
            githubActionsState = ProjectGitHubActionsState.empty().copy(
                errorMessage = "当前 origin 远端还不能识别成 GitHub 仓库地址。"
            )
            remoteRepoState = ProjectGitHubRemoteBrowserState.empty().copy(
                errorMessage = "当前 origin 远端还不能识别成 GitHub 仓库地址。"
            )
            return
        }
        val token = config.githubToken.trim()
        if (token.isBlank()) {
            githubActionsState = ProjectGitHubActionsState.empty(repo).copy(
                errorMessage = "请先去设置页填写 GitHub Token。"
            )
            remoteRepoState = ProjectGitHubRemoteBrowserState.empty(repo).copy(
                errorMessage = "请先去设置页填写 GitHub Token。"
            )
            return
        }
        scope.launch {
            isGitHubLoading = true
            githubActionsState = withContext(Dispatchers.IO) {
                loadProjectGitHubActions(
                    repo = repo,
                    token = token,
                    apiBaseUrl = config.getGitHubApiBaseUrl()
                )
            }
            isGitHubLoading = false
        }
    }

    fun refreshWholeGitPage() {
        scope.launch {
            isGitLoading = true
            if (!activeProjectPath.isNullOrBlank()) {
                val activeStatus = withContext(Dispatchers.IO) { loadProjectGitStatus(activeProjectPath) }
                gitState = activeStatus
                repoStatusSummaries[activeProjectPath] = activeStatus
            }
            if (detectedRepos.isNotEmpty()) {
                val summaries = withContext(Dispatchers.IO) {
                    detectedRepos.associate { repo ->
                        repo.rootPath to if (repo.hasGitMetadata) {
                            loadProjectGitStatus(repo.rootPath)
                        } else {
                            ProjectGitStatusUi.empty(repo.rootPath).copy(
                                errorMessage = "当前目录是独立项目，但没有检测到 `.git` 仓库。"
                            )
                        }
                    }
                }
                repoStatusSummaries.clear()
                repoStatusSummaries.putAll(summaries)
                activeProjectPath?.let { path ->
                    summaries[path]?.let { gitState = it }
                }
            }
            isGitLoading = false
            if (gitState.isRepository && !gitState.remoteUrl.isNullOrBlank() && config.githubToken.isNotBlank()) {
                refreshGitHubActions()
            }
        }
    }

    fun openWorkflowDispatchDialog(
        workflow: ProjectGitHubWorkflowUi,
        preferredRef: String?
    ) {
        workflowDispatchTarget = workflow
        workflowDispatchRefDraft = preferredRef
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: gitState.currentBranch
            ?: githubActionsState.defaultBranch
            ?: selectedViewerRepository?.defaultBranch
            ?: "main"
        workflowDispatchInputs = emptyList()
        workflowDispatchBranchRefs = emptyList()
        isWorkflowDispatchBranchesLoading = false
        workflowDispatchSchemaMessage = null
        isWorkflowDispatchSchemaLoading = false
    }

    fun refreshViewerRepositories(
        preserveSelection: Boolean = true
    ) {
        val token = config.githubToken.trim()
        if (token.isBlank()) {
            viewerRepositoriesState = ProjectGitHubViewerRepositoriesState.empty().copy(
                errorMessage = "请先在设置页填写 GitHub Token。"
            )
            standaloneNavigationState = reduceProjectGitHubStandaloneNavigationState(
                currentState = standaloneNavigationState,
                action = ProjectGitHubStandaloneNavigationAction.ClearNavigation
            )
            return
        }
        val previousSelection = selectedViewerRepository
        scope.launch {
            isViewerRepositoriesLoading = true
            val state = withContext(Dispatchers.IO) {
                loadProjectGitHubViewerRepositories(
                    token = token,
                    apiBaseUrl = config.getGitHubApiBaseUrl()
                )
            }
            viewerRepositoriesState = state
            if (preserveSelection && previousSelection != null) {
                standaloneNavigationState = reduceProjectGitHubStandaloneNavigationState(
                    currentState = standaloneNavigationState,
                    action = ProjectGitHubStandaloneNavigationAction.SyncSelectedRepo(
                        repo = state.repositories.firstOrNull { repo ->
                            repo.owner == previousSelection.owner && repo.name == previousSelection.name
                        }
                    )
                )
            } else if (!preserveSelection) {
                standaloneNavigationState = reduceProjectGitHubStandaloneNavigationState(
                    currentState = standaloneNavigationState,
                    action = ProjectGitHubStandaloneNavigationAction.ClearNavigation
                )
            }
            isViewerRepositoriesLoading = false
        }
    }

    fun refreshSelectedViewerRepositoryDetail(
        repository: ProjectGitHubAccountRepoUi? = selectedViewerRepository
    ) {
        val targetRepository = repository ?: return
        val token = config.githubToken.trim()
        if (token.isBlank()) {
            githubActionsState = ProjectGitHubActionsState.empty(targetRepository.repoRef).copy(
                errorMessage = "请先在设置页填写 GitHub Token。"
            )
            selectedViewerReadme = null
            selectedViewerReadmeErrorMessage = "请先在设置页填写 GitHub Token。"
            return
        }
        scope.launch {
            isGitHubLoading = true
            githubActionsState = withContext(Dispatchers.IO) {
                loadProjectGitHubActions(
                    repo = targetRepository.repoRef,
                    token = token,
                    apiBaseUrl = config.getGitHubApiBaseUrl()
                )
            }
            isGitHubLoading = false
        }
        scope.launch {
            isViewerReadmeLoading = true
            val result = withContext(Dispatchers.IO) {
                loadProjectGitHubReadme(
                    repo = targetRepository.repoRef,
                    token = token,
                    apiBaseUrl = config.getGitHubApiBaseUrl()
                )
            }
            selectedViewerReadme = result.readme
            selectedViewerReadmeErrorMessage = result.error
            isViewerReadmeLoading = false
        }
    }

    fun openViewerRepositoryDescriptionEditor(
        repository: ProjectGitHubAccountRepoUi
    ) {
        viewerDescriptionEditTarget = repository
        viewerDescriptionDraft = repository.description
    }

    fun applyUpdatedViewerRepository(
        repository: ProjectGitHubAccountRepoUi
    ) {
        standaloneNavigationState = reduceProjectGitHubStandaloneNavigationState(
            currentState = standaloneNavigationState,
            action = ProjectGitHubStandaloneNavigationAction.SyncSelectedRepo(repository)
        )
        viewerRepositoriesState = viewerRepositoriesState.copy(
            repositories = viewerRepositoriesState.repositories.map { current ->
                if (current.owner == repository.owner && current.name == repository.name) {
                    repository
                } else {
                    current
                }
            }
        )
    }

    fun submitViewerRepositoryDescription() {
        val repository = viewerDescriptionEditTarget ?: return
        val token = config.githubToken.trim()
        scope.launch {
            isGitHubActionRunning = true
            val result = withContext(Dispatchers.IO) {
                updateProjectGitHubRepositoryDescription(
                    repo = repository.repoRef,
                    description = viewerDescriptionDraft.trim(),
                    token = token,
                    apiBaseUrl = config.getGitHubApiBaseUrl()
                )
            }
            isGitHubActionRunning = false
            if (result.success && result.value != null) {
                applyUpdatedViewerRepository(result.value)
                viewerDescriptionEditTarget = null
                feedbackMessage = if (selectedViewerTaskRepository == repository.repoRef) {
                    "已更新 ${repository.fullName} 的仓库简介。"
                } else {
                    "已更新 ${repository.fullName} 的仓库简介，这次修改已由你手动确认。"
                }
            } else {
                feedbackMessage = result.error ?: "更新仓库简介失败"
            }
        }
    }

    fun refreshStandaloneRemoteRepositoryBrowser(
        targetPath: String = remoteRepoState.currentPath,
        resetRefIfBlank: Boolean = false
    ) {
        val repository = selectedViewerRepository ?: return
        scope.launch {
            isRemoteRepoLoading = true
            val result = withContext(Dispatchers.IO) {
                refreshProjectGitHubRemoteBrowserState(
                    currentRepo = repository.repoRef,
                    gitRemoteUrl = null,
                    token = config.githubToken.trim(),
                    currentBranch = null,
                    defaultBranch = githubActionsState.defaultBranch ?: repository.defaultBranch,
                    refDraft = remoteRepoRefDraft,
                    targetPath = targetPath,
                    resetRefIfBlank = resetRefIfBlank,
                    apiBaseUrl = config.getGitHubApiBaseUrl()
                )
            }
            isRemoteRepoLoading = false
            result.nextRefDraft?.let { remoteRepoRefDraft = it }
            remoteRepoState = result.state
            result.feedbackMessage?.let { feedbackMessage = it }
        }
    }

    fun openStandaloneReadmeEditor(readme: ProjectGitHubReadmeUi) {
        val repository = selectedViewerRepository ?: return
        scope.launch {
            isGitHubActionRunning = true
            val result = withContext(Dispatchers.IO) {
                openProjectGitHubRemoteFileEditor(
                    currentRepo = repository.repoRef,
                    gitRemoteUrl = null,
                    token = config.githubToken.trim(),
                    currentRemoteRef = remoteRepoState.currentRef,
                    refDraft = remoteRepoRefDraft,
                    currentBranch = null,
                    defaultBranch = githubActionsState.defaultBranch ?: repository.defaultBranch,
                    entry = ProjectGitHubRemoteEntryUi(
                        name = readme.name,
                        path = readme.path,
                        type = "file",
                        sha = null,
                        size = readme.content.toByteArray().size.toLong(),
                        htmlUrl = readme.htmlUrl
                    ),
                    apiBaseUrl = config.getGitHubApiBaseUrl()
                )
            }
            isGitHubActionRunning = false
            if (result.file != null) {
                remoteFileDialogState = result.file
                remoteFileContentDraft = result.contentDraft
                remoteFileCommitMessageDraft = result.commitMessageDraft
            } else {
                feedbackMessage = result.feedbackMessage ?: "读取远端文件失败"
            }
        }
    }

    fun refreshRemoteRepositoryBrowser(
        targetPath: String = remoteRepoState.currentPath,
        resetRefIfBlank: Boolean = false
    ) {
        scope.launch {
            isRemoteRepoLoading = true
            val result = withContext(Dispatchers.IO) {
                refreshProjectGitHubRemoteBrowserState(
                    currentRepo = githubActionsState.repo,
                    gitRemoteUrl = gitState.remoteUrl,
                    token = config.githubToken.trim(),
                    currentBranch = gitState.currentBranch,
                    defaultBranch = githubActionsState.defaultBranch,
                    refDraft = remoteRepoRefDraft,
                    targetPath = targetPath,
                    resetRefIfBlank = resetRefIfBlank,
                    apiBaseUrl = config.getGitHubApiBaseUrl()
                )
            }
            isRemoteRepoLoading = false
            result.nextRefDraft?.let { remoteRepoRefDraft = it }
            remoteRepoState = result.state
            result.feedbackMessage?.let { feedbackMessage = it }
        }
    }

    fun openRemoteRepositoryEntry(entry: ProjectGitHubRemoteEntryUi) {
        if (entry.isDirectory) {
            refreshRemoteRepositoryBrowser(targetPath = entry.path)
            return
        }
        scope.launch {
            isGitHubActionRunning = true
            val result = withContext(Dispatchers.IO) {
                openProjectGitHubRemoteFileEditor(
                    currentRepo = githubActionsState.repo,
                    gitRemoteUrl = gitState.remoteUrl,
                    token = config.githubToken.trim(),
                    currentRemoteRef = remoteRepoState.currentRef,
                    refDraft = remoteRepoRefDraft,
                    currentBranch = gitState.currentBranch,
                    defaultBranch = githubActionsState.defaultBranch,
                    entry = entry,
                    apiBaseUrl = config.getGitHubApiBaseUrl()
                )
            }
            isGitHubActionRunning = false
            if (result.file != null) {
                remoteFileDialogState = result.file
                remoteFileContentDraft = result.contentDraft
                remoteFileCommitMessageDraft = result.commitMessageDraft
            } else {
                feedbackMessage = result.feedbackMessage ?: "读取远端文件失败"
            }
        }
    }

    fun openCurrentGitHubRepoPage() {
        openGitHubPage(
            githubActionsState.repoHtmlUrl,
            "当前仓库还没有可打开的 GitHub 页面地址。"
        )
    }

    fun openWorkflowArtifacts(run: ProjectGitHubWorkflowRunUi) {
        val repo = githubActionsState.repo ?: return
        val token = config.githubToken.trim()
        if (token.isBlank()) {
            feedbackMessage = "请先在设置页填写 GitHub Token。"
            return
        }
        scope.launch {
            isGitHubActionRunning = true
            val result = withContext(Dispatchers.IO) {
                loadProjectGitHubArtifacts(
                    repo = repo,
                    runId = run.id,
                    token = token,
                    apiBaseUrl = config.getGitHubApiBaseUrl()
                )
            }
            isGitHubActionRunning = false
            if (result.success) {
                artifactDialogState = ProjectGitHubArtifactDialogUi(
                    runTitle = run.displayTitle.ifBlank { run.name.ifBlank { "运行 #${run.runNumber}" } },
                    artifacts = result.artifacts,
                    runId = run.id,
                    runHtmlUrl = run.htmlUrl
                )
            } else {
                feedbackMessage = result.error ?: "读取工作流产物失败"
            }
        }
    }

    fun openWorkflowRunDetail(run: ProjectGitHubWorkflowRunUi) {
        val repo = githubActionsState.repo ?: return
        val token = config.githubToken.trim()
        if (token.isBlank()) {
            feedbackMessage = "请先在设置页填写 GitHub Token。"
            return
        }
        scope.launch {
            isGitHubActionRunning = true
            val result = withContext(Dispatchers.IO) {
                loadProjectGitHubWorkflowRunDetail(
                    repo = repo,
                    runId = run.id,
                    token = token,
                    apiBaseUrl = config.getGitHubApiBaseUrl()
                )
            }
            isGitHubActionRunning = false
            if (result.success) {
                workflowRunDetailDialogState = result.detail
            } else {
                feedbackMessage = result.error ?: "读取工作流运行详情失败"
            }
        }
    }

    fun requestWorkflowRunDetail(
        run: ProjectGitHubWorkflowRunUi,
        onLoaded: (ProjectGitHubWorkflowRunDetailUi?) -> Unit
    ) {
        val repo = githubActionsState.repo
        if (repo == null) {
            feedbackMessage = "当前还没有可用的 GitHub 仓库。"
            onLoaded(null)
            return
        }
        val token = config.githubToken.trim()
        if (token.isBlank()) {
            feedbackMessage = "请先在设置页填写 GitHub Token。"
            onLoaded(null)
            return
        }
        scope.launch {
            isGitHubActionRunning = true
            val result = withContext(Dispatchers.IO) {
                loadProjectGitHubWorkflowRunDetail(
                    repo = repo,
                    runId = run.id,
                    token = token,
                    apiBaseUrl = config.getGitHubApiBaseUrl()
                )
            }
            isGitHubActionRunning = false
            if (result.success) {
                onLoaded(result.detail)
            } else {
                feedbackMessage = result.error ?: "读取工作流运行详情失败"
                onLoaded(null)
            }
        }
    }

    fun refreshStandaloneWorkflowRunDetail(
        currentDetail: ProjectGitHubWorkflowRunDetailUi,
        onLoaded: (ProjectGitHubWorkflowRunDetailUi?) -> Unit
    ) {
        scope.launch {
            isGitHubActionRunning = true
            val result = withContext(Dispatchers.IO) {
                refreshProjectGitHubWorkflowRunDetailAction(
                    currentDetail = currentDetail,
                    repo = githubActionsState.repo,
                    token = config.githubToken.trim(),
                    apiBaseUrl = config.getGitHubApiBaseUrl()
                )
            }
            isGitHubActionRunning = false
            result.updatedDetail?.let(onLoaded)
            result.feedbackMessage?.let { feedbackMessage = it }
            if (result.updatedDetail == null) {
                onLoaded(null)
            }
        }
    }

    LaunchedEffect(activeProjectPath, gitState.remoteUrl, config.githubToken) {
        remoteFileDialogState = null
        remoteFileContentDraft = ""
        remoteFileCommitMessageDraft = ""
        val fallbackRef = gitState.currentBranch ?: githubActionsState.defaultBranch ?: "main"
        remoteRepoRefDraft = fallbackRef
        if (parseProjectGitHubRepoRef(gitState.remoteUrl) != null && config.githubToken.isNotBlank()) {
            refreshRemoteRepositoryBrowser(targetPath = "", resetRefIfBlank = true)
        } else {
            remoteRepoState = ProjectGitHubRemoteBrowserState.empty()
        }
    }

    fun runGitHubAction(
        successFallback: String,
        refreshAfterSuccess: Boolean = true,
        block: suspend () -> ProjectGitHubCommandResult
    ) {
        scope.launch {
            isGitHubActionRunning = true
            val result = withContext(Dispatchers.IO) { block() }
            feedbackMessage = when {
                result.success -> result.message.ifBlank { successFallback }
                else -> result.error ?: result.message.ifBlank { "GitHub 操作失败" }
            }
            isGitHubActionRunning = false
            if (result.success && refreshAfterSuccess) {
                refreshGitHubActions()
            }
        }
    }

    fun runGitHubMutationAction(
        block: suspend () -> ProjectGitHubMutationActionResult,
        onResult: (ProjectGitHubMutationActionResult) -> Unit = {}
    ) {
        scope.launch {
            isGitHubActionRunning = true
            val result = withContext(Dispatchers.IO) { block() }
            isGitHubActionRunning = false
            feedbackMessage = result.feedbackMessage
            result.nextReleaseDraft?.let { releaseDraftState = it }
            result.nextIssueDraft?.let { createIssueDraftState = it }
            result.nextPullRequestDraft?.let { createPullRequestDraftState = it }
            onResult(result)
            if (result.shouldRefreshGitHubActions) {
                refreshGitHubActions()
            }
        }
    }

    LaunchedEffect(activeProjectPath) {
        refreshGitState()
    }

    LaunchedEffect(detectedRepos) {
        repoStatusSummaries.clear()
        if (detectedRepos.isEmpty()) return@LaunchedEffect
        val summaries = withContext(Dispatchers.IO) {
            detectedRepos.associate { repo ->
                repo.rootPath to if (repo.hasGitMetadata) {
                    loadProjectGitStatus(repo.rootPath)
                } else {
                    ProjectGitStatusUi.empty(repo.rootPath).copy(
                        errorMessage = "当前目录是独立项目，但没有检测到 `.git` 仓库。"
                    )
                }
            }
        }
        repoStatusSummaries.putAll(summaries)
    }

    LaunchedEffect(
        gitState.remoteUrl,
        gitState.isRepository,
        config.githubToken,
        config.githubApiBaseUrl,
        activeProjectPath
    ) {
        if (activeProjectPath.isNullOrBlank()) return@LaunchedEffect
        if (!gitState.isRepository) {
            githubActionsState = ProjectGitHubActionsState.empty()
        } else {
            refreshGitHubActions()
        }
    }

    LaunchedEffect(activeProjectPath, config.githubToken, config.githubApiBaseUrl) {
        if (activeProjectPath.isNullOrBlank()) {
            refreshViewerRepositories()
        }
    }

    LaunchedEffect(
        activeProjectPath,
        selectedViewerRepository?.owner,
        selectedViewerRepository?.name,
        config.githubToken,
        config.githubApiBaseUrl
    ) {
        if (activeProjectPath.isNullOrBlank()) {
            selectedViewerRepository?.let { refreshSelectedViewerRepositoryDetail(it) }
        }
    }

    LaunchedEffect(
        activeProjectPath,
        selectedViewerRepository?.owner,
        selectedViewerRepository?.name,
        selectedViewerSection,
        githubActionsState.defaultBranch
    ) {
        val selectedRepo = selectedViewerRepository
        if (
            activeProjectPath.isNullOrBlank() &&
            selectedRepo != null &&
            selectedViewerSection == ProjectGitHubStandaloneSection.OVERVIEW
        ) {
            val currentRepo = remoteRepoState.repo
            if (
                currentRepo == null ||
                currentRepo.owner != selectedRepo.owner ||
                currentRepo.repo != selectedRepo.name
            ) {
                refreshStandaloneRemoteRepositoryBrowser(
                    targetPath = "",
                    resetRefIfBlank = true
                )
            }
        }
    }

    val showStandaloneBrowserPage =
        activeProjectPath.isNullOrBlank() || showStandaloneRepoBrowserSecondary
    val showStandaloneViewerSecondaryPage = isProjectGitHubStandaloneSecondaryPage(
        activeProjectPath = activeProjectPath,
        currentState = standaloneNavigationState,
        forceSecondaryPage = showStandaloneRepoBrowserSecondary
    )
    val isGitSecondaryPage = showStandaloneViewerSecondaryPage
    fun closeStandaloneViewerRepoSelection() {
        standaloneNavigationState = reduceProjectGitHubStandaloneNavigationState(
            currentState = standaloneNavigationState,
            action = ProjectGitHubStandaloneNavigationAction.ClearNavigation
        )
        selectedViewerReadme = null
        selectedViewerReadmeErrorMessage = null
        remoteRepoState = ProjectGitHubRemoteBrowserState.empty()
        remoteRepoRefDraft = ""
    }
    fun closeStandaloneRepoBrowserSecondaryPage() {
        showStandaloneRepoBrowserSecondary = false
        standaloneSecondaryCloseRequest = null
        closeStandaloneViewerRepoSelection()
    }
    fun handleStandaloneViewerSecondaryCloseRequest() {
        val nextState = reduceProjectGitHubStandaloneNavigationState(
            currentState = standaloneNavigationState,
            action = ProjectGitHubStandaloneNavigationAction.CloseSecondaryLayer
        )
        standaloneNavigationState = nextState
        if (nextState.selectedRepo == null) {
            selectedViewerReadme = null
            selectedViewerReadmeErrorMessage = null
            remoteRepoState = ProjectGitHubRemoteBrowserState.empty()
            remoteRepoRefDraft = ""
        }
    }
    val activeGitSecondaryCloseRequest =
        resolveProjectGitHubStandaloneCloseRequest(
            activeProjectPath = activeProjectPath,
            currentState = standaloneNavigationState,
            nestedCloseRequest = standaloneSecondaryCloseRequest,
            fallbackCloseRequest = ::handleStandaloneViewerSecondaryCloseRequest,
            forceSecondaryPage = showStandaloneRepoBrowserSecondary,
            clearSecondaryPageRequest = ::closeStandaloneRepoBrowserSecondaryPage
        )
    val activeGitForegroundCloseLayer = resolveProjectGitHubForegroundCloseLayer(
        ProjectGitHubForegroundCloseState(
            hasPendingRemoteFileSaveConfirmation = pendingRemoteFileSaveConfirmation,
            hasViewerDescriptionEditor = viewerDescriptionEditTarget != null,
            hasViewerRepositoryMenu = viewerRepositoryMenuTarget != null,
            hasSuspectedRepoMenu = suspectedRepoMenuTarget != null,
            isGlobalSearchVisible = false,
            isGlobalTaskCenterVisible = false,
            hasWorkflowDispatch = workflowDispatchTarget != null,
            hasWorkflowRunDetail = workflowRunDetailDialogState != null,
            hasArtifactDialog = artifactDialogState != null,
            hasReleaseAssetDialog = releaseAssetDialogState != null,
            hasRemoteFileDialog = remoteFileDialogState != null,
            hasPullRequestDetail = pullRequestDetailStore.pullRequest != null,
            hasIssueDetail = issueDetailStore.issue != null,
            hasDiffPreview = diffPreview != null,
            hasCreatePullRequestDialog = showCreatePullRequestDialog,
            hasCreateIssueDialog = showCreateIssueDialog,
            hasCreateOrEditReleaseDialog = showCreateReleaseDialog || releaseEditTarget != null,
            hasCreateGitHubRepositoryDialog = showCreateGitHubRepoDialog,
            hasInitGitDialog = showInitGitDialog,
            hasBranchDialog = showBranchDialog,
            hasCommitDialog = showCommitDialog
        )
    )
    LaunchedEffect(
        showStandaloneViewerSecondaryPage,
        showStandaloneRepoBrowserSecondary,
        selectedViewerRepository?.fullName,
        selectedViewerSection,
    ) {
        onProjectSecondaryPageChanged(
            if (showStandaloneViewerSecondaryPage) {
                ProjectSecondaryChromeState(
                    active = true,
                    title = when {
                        selectedViewerRepository == null -> "当前账户的所有仓库"
                        selectedViewerSection == ProjectGitHubStandaloneSection.OVERVIEW -> {
                            selectedViewerRepository.name.ifBlank { "仓库详情" }
                        }
                        else -> selectedViewerSection.label
                    },
                    subtitle = selectedViewerRepository?.fullName?.ifBlank { "GitHub 仓库详情" }
                        ?: "复用未选择本地项目时的 Git 页面",
                    supportsEditorMenu = false
                )
            } else {
                ProjectSecondaryChromeState()
            }
        )
    }

    fun closeRemoteFileDialog() {
        remoteFileDialogState = null
        remoteFileContentDraft = ""
        remoteFileCommitMessageDraft = ""
        pendingRemoteFileSaveConfirmation = false
    }


    val handleGitSecondaryCloseRequest = {
        when (activeGitForegroundCloseLayer) {
            ProjectGitHubForegroundCloseLayer.PENDING_REMOTE_FILE_SAVE_CONFIRMATION -> {
                pendingRemoteFileSaveConfirmation = false
            }

            ProjectGitHubForegroundCloseLayer.VIEWER_DESCRIPTION_EDITOR -> {
                viewerDescriptionEditTarget = null
            }

            ProjectGitHubForegroundCloseLayer.VIEWER_REPOSITORY_MENU -> {
                viewerRepositoryMenuTarget = null
            }

            ProjectGitHubForegroundCloseLayer.SUSPECTED_REPO_MENU -> {
                suspectedRepoMenuTarget = null
            }

            ProjectGitHubForegroundCloseLayer.GLOBAL_SEARCH -> Unit

            ProjectGitHubForegroundCloseLayer.GLOBAL_TASK_CENTER -> Unit

            ProjectGitHubForegroundCloseLayer.WORKFLOW_DISPATCH -> {
                workflowDispatchTarget = null
                workflowDispatchInputs = emptyList()
                workflowDispatchBranchRefs = emptyList()
                isWorkflowDispatchBranchesLoading = false
                workflowDispatchSchemaMessage = null
                isWorkflowDispatchSchemaLoading = false
            }

            ProjectGitHubForegroundCloseLayer.WORKFLOW_RUN_DETAIL -> {
                workflowRunDetailDialogState = null
            }

            ProjectGitHubForegroundCloseLayer.ARTIFACT_DIALOG -> {
                artifactDialogState = null
            }

            ProjectGitHubForegroundCloseLayer.RELEASE_ASSET_DIALOG -> {
                releaseAssetDialogState = null
            }

            ProjectGitHubForegroundCloseLayer.REMOTE_FILE_DIALOG -> {
                closeRemoteFileDialog()
            }

            ProjectGitHubForegroundCloseLayer.PULL_REQUEST_DETAIL -> {
                pullRequestDetailStore = clearProjectGitHubPullRequestDetailStore()
            }

            ProjectGitHubForegroundCloseLayer.ISSUE_DETAIL -> {
                issueDetailStore = clearProjectGitHubIssueDetailStore()
            }

            ProjectGitHubForegroundCloseLayer.DIFF_PREVIEW -> {
                diffPreview = null
            }

            ProjectGitHubForegroundCloseLayer.CREATE_PULL_REQUEST -> {
                showCreatePullRequestDialog = false
            }

            ProjectGitHubForegroundCloseLayer.CREATE_ISSUE -> {
                showCreateIssueDialog = false
            }

            ProjectGitHubForegroundCloseLayer.CREATE_OR_EDIT_RELEASE -> {
                showCreateReleaseDialog = false
                releaseEditTarget = null
                resetReleaseDraft()
            }

            ProjectGitHubForegroundCloseLayer.CREATE_GITHUB_REPOSITORY -> {
                showCreateGitHubRepoDialog = false
            }

            ProjectGitHubForegroundCloseLayer.INIT_GIT -> {
                showInitGitDialog = false
            }

            ProjectGitHubForegroundCloseLayer.BRANCH_DIALOG -> {
                showBranchDialog = false
            }

            ProjectGitHubForegroundCloseLayer.COMMIT_DIALOG -> {
                showCommitDialog = false
            }

            ProjectGitHubForegroundCloseLayer.NONE -> {
                if (showStandaloneViewerSecondaryPage) {
                    handleStandaloneViewerSecondaryCloseRequest()
                }
            }
        }
    }

    LaunchedEffect(isGitSecondaryPage) {
        if (!isGitSecondaryPage) {
            onProjectSecondaryBackProgressChanged(0f)
        }
    }

    BackHandler(enabled = activeGitSecondaryCloseRequest != null) {
        activeGitSecondaryCloseRequest?.invoke()
    }

    PredictiveBackHandler(enabled = activeGitSecondaryCloseRequest != null) { progress ->
        try {
            progress.collect { backEvent ->
                onProjectSecondaryBackProgressChanged(backEvent.progress)
            }
            onProjectSecondaryBackProgressChanged(1f)
            activeGitSecondaryCloseRequest?.invoke()
        } catch (_: CancellationException) {
            onProjectSecondaryBackProgressChanged(0f)
        } finally {
            onProjectSecondaryBackProgressChanged(0f)
        }
    }

    LaunchedEffect(
        isGitSecondaryPage,
        showStandaloneViewerSecondaryPage,
        selectedViewerSection,
        standaloneSecondaryCloseRequest
    ) {
        onRegisterSecondaryCloseRequest(activeGitSecondaryCloseRequest)
    }

    ProjectGitSectionColumn(
        fillAvailableHeight = showStandaloneViewerSecondaryPage,
        scrollable = !showStandaloneViewerSecondaryPage,
        showSectionTitle = !showStandaloneViewerSecondaryPage,
        showRefreshAction = !showStandaloneViewerSecondaryPage,
        isRefreshEnabled = !isGitLoading && !isGitActionRunning && !isGitHubActionRunning,
        onRefresh = ::refreshWholeGitPage
    ) {
        if (showStandaloneBrowserPage) {
            Box(
                modifier = if (showStandaloneViewerSecondaryPage) {
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                } else {
                    Modifier.fillMaxWidth()
                }
            ) {
                ProjectGitDeferredContent {
                ProjectGitHubStandaloneBrowserSection(
                    tokenConfigured = config.githubToken.isNotBlank(),
                    repoListState = viewerRepositoriesState,
                    isRepoListLoading = isViewerRepositoriesLoading,
                    selectedRepo = selectedViewerRepository,
                    selectedTaskRepo = selectedViewerTaskRepository,
                    activeSection = selectedViewerSection,
                    repoDetailState = githubActionsState,
                    isRepoDetailLoading = isGitHubLoading,
                    readme = selectedViewerReadme,
                    readmeErrorMessage = selectedViewerReadmeErrorMessage,
                    isReadmeLoading = isViewerReadmeLoading,
                    onRefreshRepoList = ::refreshViewerRepositories,
                    onSelectRepo = { repo ->
                        standaloneNavigationState = reduceProjectGitHubStandaloneNavigationState(
                            currentState = standaloneNavigationState,
                            action = ProjectGitHubStandaloneNavigationAction.SelectRepo(repo)
                        )
                        remoteRepoState = ProjectGitHubRemoteBrowserState.empty(repo.repoRef)
                        remoteRepoRefDraft = ""
                    },
                    onShowRepoMenu = { repo ->
                        viewerRepositoryMenuTarget = repo
                    },
                    onEditRepoDescription = ::openViewerRepositoryDescriptionEditor,
                    onBackToRepoList = ::closeStandaloneViewerRepoSelection,
                    onChangeSection = {
                        standaloneNavigationState = reduceProjectGitHubStandaloneNavigationState(
                            currentState = standaloneNavigationState,
                            action = ProjectGitHubStandaloneNavigationAction.SelectSection(it)
                        )
                    },
                    onRefreshRepoDetail = ::refreshSelectedViewerRepositoryDetail,
                    onOpenRepoPage = {
                        openGitHubPage(
                            selectedViewerRepository?.htmlUrl ?: githubActionsState.repoHtmlUrl,
                            "当前仓库还没有可打开的 GitHub 页面地址。"
                        )
                    },
                    remoteRepoState = remoteRepoState,
                    isRemoteRepoLoading = isRemoteRepoLoading,
                    isActionRunning = isGitHubActionRunning,
                    remoteRepoRefDraft = remoteRepoRefDraft,
                    onRemoteRepoRefDraftChange = { remoteRepoRefDraft = it },
                    onRefreshRemoteRepository = {
                        refreshStandaloneRemoteRepositoryBrowser(resetRefIfBlank = true)
                    },
                    onApplyRemoteRef = {
                        refreshStandaloneRemoteRepositoryBrowser(targetPath = remoteRepoState.currentPath)
                    },
                    onOpenRemoteParent = {
                        refreshStandaloneRemoteRepositoryBrowser(
                            targetPath = parentProjectGitHubRepoPath(remoteRepoState.currentPath).orEmpty()
                        )
                    },
                    onOpenRemoteRoot = {
                        refreshStandaloneRemoteRepositoryBrowser(targetPath = "")
                    },
                    onOpenRemoteEntry = { entry ->
                        openRemoteRepositoryEntry(entry)
                    },
                    onOpenRemoteEntryPage = { entry ->
                        openGitHubPage(
                            entry.htmlUrl,
                            "当前条目还没有可打开的 GitHub 页面地址。"
                        )
                    },
                    onRunWorkflow = { workflow ->
                        openWorkflowDispatchDialog(
                            workflow = workflow,
                            preferredRef = githubActionsState.defaultBranch ?: selectedViewerRepository?.defaultBranch
                        )
                    },
                    onOpenWorkflowPage = { workflow ->
                        openGitHubPage(
                            workflow.htmlUrl,
                            "当前工作流还没有可打开的 GitHub 页面地址。"
                        )
                    },
                    onOpenArtifacts = ::openWorkflowArtifacts,
                    onOpenRunPage = { run ->
                        openGitHubPage(
                            run.htmlUrl,
                            "当前运行记录还没有可打开的 GitHub 页面地址。"
                        )
                    },
                    onDownloadRunLogs = { run ->
                        val repo = githubActionsState.repo ?: return@ProjectGitHubStandaloneBrowserSection
                        val token = config.githubToken.trim()
                        if (token.isBlank()) {
                            feedbackMessage = "请先在设置页填写 GitHub Token。"
                            return@ProjectGitHubStandaloneBrowserSection
                        }
                        val result = enqueueProjectGitHubWorkflowLogsDownload(
                            context = context,
                            repo = repo,
                            runId = run.id,
                            runDisplayTitle = run.displayTitle.ifBlank { run.name.ifBlank { "运行 #${run.runNumber}" } },
                            token = token,
                            apiBaseUrl = config.getGitHubApiBaseUrl()
                        )
                        recordGitHubDownload(
                            typeLabel = "工作流日志",
                            title = run.displayTitle.ifBlank { run.name.ifBlank { "运行 #${run.runNumber}" } },
                            fileName = result.fileName,
                            downloadId = result.downloadId,
                            sourceUrl = run.htmlUrl,
                            repo = repo
                        )
                        feedbackMessage = "已开始下载 ${result.fileName}"
                    },
                    onOpenRunDetail = ::openWorkflowRunDetail,
                    onLoadRunDetail = ::requestWorkflowRunDetail,
                    onRefreshRunDetail = ::refreshStandaloneWorkflowRunDetail,
                    onDownloadWorkflowArtifact = { detail, artifact ->
                        val result = enqueueProjectGitHubWorkflowArtifactDownloadAction(
                            context = context,
                            repo = githubActionsState.repo,
                            artifact = artifact,
                            sourceUrl = detail.htmlUrl,
                            token = config.githubToken.trim()
                        )
                        result.downloadRecord?.let { record ->
                            recordGitHubDownload(
                                typeLabel = record.typeLabel,
                                title = record.title,
                                fileName = record.fileName,
                                downloadId = record.downloadId,
                                sourceUrl = record.sourceUrl,
                                repo = record.repo
                            )
                        }
                        result.feedbackMessage?.let { feedbackMessage = it }
                    },
                    onCreateRelease = {
                        releaseDraftState = createProjectGitHubReleaseDraftState(
                            githubActionsState.defaultBranch ?: selectedViewerRepository?.defaultBranch
                        )
                        showCreateReleaseDialog = true
                    },
                    onEditRelease = { release ->
                        releaseEditTarget = release
                        releaseDraftState = editProjectGitHubReleaseDraftState(release)
                    },
                    onToggleReleaseMode = { release, makeDraft ->
                        runGitHubMutationAction(
                            block = {
                                toggleProjectGitHubReleaseModeAction(
                                    repo = githubActionsState.repo,
                                    release = release,
                                    token = config.githubToken.trim(),
                                    apiBaseUrl = config.getGitHubApiBaseUrl(),
                                    makeDraft = makeDraft
                                )
                            }
                        )
                    },
                    onTogglePrerelease = { release, makePrerelease ->
                        runGitHubMutationAction(
                            block = {
                                toggleProjectGitHubReleasePrereleaseAction(
                                    repo = githubActionsState.repo,
                                    release = release,
                                    token = config.githubToken.trim(),
                                    apiBaseUrl = config.getGitHubApiBaseUrl(),
                                    makePrerelease = makePrerelease
                                )
                            }
                        )
                    },
                    onDeleteRelease = { release ->
                        runGitHubMutationAction(
                            block = {
                                deleteProjectGitHubReleaseAction(
                                    repo = githubActionsState.repo,
                                    release = release,
                                    token = config.githubToken.trim(),
                                    apiBaseUrl = config.getGitHubApiBaseUrl()
                                )
                            }
                        )
                    },
                    onOpenReleasePage = { release ->
                        openGitHubPage(
                            release.htmlUrl,
                            "当前 Release 还没有可打开的 GitHub 页面地址。"
                        )
                    },
                    onOpenReleaseAssets = { release ->
                        releaseAssetDialogState = ProjectGitHubReleaseAssetDialogUi(
                            releaseTitle = release.name.ifBlank { release.tagName },
                            assets = release.assets,
                            releaseHtmlUrl = release.htmlUrl
                        )
                    },
                    onCreateIssue = {
                        resetCreateIssueDraft()
                        showCreateIssueDialog = true
                    },
                    onOpenIssueDetail = ::openIssueDetail,
                    onToggleIssueState = { issue, shouldClose ->
                        val repo = githubActionsState.repo ?: return@ProjectGitHubStandaloneBrowserSection
                        val token = config.githubToken.trim()
                        if (token.isBlank()) {
                            feedbackMessage = "请先在设置页填写 GitHub Token。"
                            return@ProjectGitHubStandaloneBrowserSection
                        }
                        runGitHubAction(if (shouldClose) "已关闭 Issue #${issue.number}" else "已重新打开 Issue #${issue.number}") {
                            updateProjectGitHubIssueState(
                                repo = repo,
                                issueNumber = issue.number,
                                close = shouldClose,
                                token = token,
                                apiBaseUrl = config.getGitHubApiBaseUrl()
                            )
                        }
                    },
                    onOpenIssuePage = { issue ->
                        openGitHubPage(
                            issue.htmlUrl,
                            "当前 Issue 还没有可打开的 GitHub 页面地址。"
                        )
                    },
                    onCreatePullRequest = {
                        resetCreatePullRequestDraft()
                        showCreatePullRequestDialog = true
                    },
                    onOpenPullRequestDetail = ::openPullRequestDetail,
                    onTogglePullRequestState = { pullRequest, shouldClose ->
                        val repo = githubActionsState.repo ?: return@ProjectGitHubStandaloneBrowserSection
                        val token = config.githubToken.trim()
                        if (token.isBlank()) {
                            feedbackMessage = "请先在设置页填写 GitHub Token。"
                            return@ProjectGitHubStandaloneBrowserSection
                        }
                        runGitHubAction(if (shouldClose) "已关闭 PR #${pullRequest.number}" else "已重新打开 PR #${pullRequest.number}") {
                            updateProjectGitHubPullRequestState(
                                repo = repo,
                                pullNumber = pullRequest.number,
                                close = shouldClose,
                                token = token,
                                apiBaseUrl = config.getGitHubApiBaseUrl()
                            )
                        }
                    },
                    onMergePullRequest = { pullRequest ->
                        val repo = githubActionsState.repo ?: return@ProjectGitHubStandaloneBrowserSection
                        val token = config.githubToken.trim()
                        if (token.isBlank()) {
                            feedbackMessage = "请先在设置页填写 GitHub Token。"
                            return@ProjectGitHubStandaloneBrowserSection
                        }
                        runGitHubAction("已合并 PR #${pullRequest.number}") {
                            mergeProjectGitHubPullRequest(
                                repo = repo,
                                pullNumber = pullRequest.number,
                                title = pullRequest.title,
                                token = token,
                                apiBaseUrl = config.getGitHubApiBaseUrl()
                            )
                        }
                    },
                    onOpenPullRequestPage = { pullRequest ->
                        openGitHubPage(
                            pullRequest.htmlUrl,
                            "当前 Pull Request 还没有可打开的 GitHub 页面地址。"
                        )
                    },
                    onEditReadme = ::openStandaloneReadmeEditor,
                    onRegisterNestedCloseRequest = { closeRequest ->
                        standaloneSecondaryCloseRequest = closeRequest
                    },
                    backProgress = projectSecondaryBackProgress,
                    forceListInternalScroll = showStandaloneRepoBrowserSecondary
                )
                }
            }
            feedbackMessage?.takeIf { it.isNotBlank() }?.let { message ->
                ProjectSectionCard(
                    shape = RoundedCornerShape(12.dp),
                    surfaceColorOverride = rememberMurongChromeColor().copy(alpha = 0.58f)
                ) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            viewerRepositoryMenuTarget?.let { repo ->
                ProjectScreenLargeDialog(
                    title = "仓库操作",
                    subtitle = repo.fullName,
                    onDismissRequest = { viewerRepositoryMenuTarget = null },
                    actions = {
                        TextButton(onClick = { viewerRepositoryMenuTarget = null }) {
                            Text("取消")
                        }
                        TextButton(
                            onClick = {
                                updateSelectedViewerTaskRepository(repo.repoRef)
                                viewerRepositoryMenuTarget = null
                                feedbackMessage = "已将 ${repo.fullName} 设为当前任务仓库。"
                            }
                        ) {
                            Text("设为任务仓库")
                        }
                    }
                ) {
                    Text(
                        text = if (selectedViewerTaskRepository == repo.repoRef) {
                            "当前仓库已经是任务仓库，可直接编辑。"
                        } else {
                            "设为任务仓库后，后续默认允许直接编辑这个仓库；查看其他仓库仍不受限制。"
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            viewerDescriptionEditTarget?.let { repo ->
                ProjectScreenLargeDialog(
                    title = "编辑仓库简介",
                    subtitle = repo.fullName,
                    onDismissRequest = { viewerDescriptionEditTarget = null },
                    actions = {
                        TextButton(onClick = { viewerDescriptionEditTarget = null }) {
                            Text("取消")
                        }
                        Button(
                            onClick = { submitViewerRepositoryDescription() },
                            enabled = !isGitHubActionRunning
                        ) {
                            Text(if (isGitHubActionRunning) "保存中" else "保存")
                        }
                    }
                ) {
                    Text(
                        text = if (selectedViewerTaskRepository == repo.repoRef) {
                            "当前是任务仓库，保存后会直接更新 GitHub 仓库简介。"
                        } else {
                            "当前不是任务仓库，保存按钮相当于这次修改的手动确认。"
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = viewerDescriptionDraft,
                        onValueChange = { viewerDescriptionDraft = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("仓库简介") },
                        minLines = 3,
                        maxLines = 6
                    )
                }
            }
        } else {
            ProjectGitDeferredContent {
            val surfaceColor = rememberMurongSurfaceColor()
            val chromeColor = rememberMurongChromeColor()
            val mutedTextColor = rememberMurongMutedTextColor()
            val gitMetadataRepos = detectedRepos.filter { it.hasGitMetadata }
            val suspectedRepos = detectedRepos.filter { !it.hasGitMetadata }
            if (detectedRepos.isNotEmpty()) {
                val gitRepoCount = detectedRepos.count { it.hasGitMetadata }
                ProjectSectionCard(
                    shape = RoundedCornerShape(14.dp),
                    surfaceColorOverride = chromeColor.copy(alpha = 0.42f)
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "当前工作区识别到 ${detectedRepos.size} 个项目根目录，其中 $gitRepoCount 个是 Git 仓库",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = "下面优先列出自动识别到的项目根目录；只有带 `.git` 的目录才会启用完整 Git/GitHub 操作。",
                            style = MaterialTheme.typography.bodySmall,
                            color = mutedTextColor
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            detectedRepos.forEach { repo ->
                                FilterChip(
                                    selected = repo.rootPath == selectedRepoRoot,
                                    onClick = { onSelectRepoRoot(repo.rootPath) },
                                    label = {
                                        Text(
                                            when {
                                                repo.isWorkspaceRoot -> "${repo.displayName}（根）"
                                                repo.hasGitMetadata -> repo.relativePath
                                                else -> "${repo.relativePath}（项目）"
                                            },
                                            fontSize = 12.sp
                                        )
                                    }
                                )
                            }
                        }
                        detectedRepos.mapNotNull { repo ->
                            repoStatusSummaries[repo.rootPath]?.let { status ->
                                ProjectRepoStatusSummaryUi(repo, status)
                            }
                        }.forEach { summary ->
                            val isExpanded = repoCardExpandedStates[summary.repo.rootPath]
                                ?: summary.status.hasWorkingTreeChanges
                            ProjectGitRepoWorkCard(
                                summary = summary,
                                isSelected = summary.repo.rootPath == selectedRepoRoot,
                                isExpanded = isExpanded,
                                mutedTextColor = mutedTextColor,
                                surfaceColor = surfaceColor,
                                chromeColor = chromeColor,
                                isBusy = isGitActionRunning,
                                onToggleExpanded = {
                                    repoCardExpandedStates[summary.repo.rootPath] = !isExpanded
                                },
                                onSelectRepo = { onSelectRepoRoot(summary.repo.rootPath) },
                                onOpenDiff = { openDiffForRepo(summary.repo.rootPath, it) },
                                onStageFile = { change ->
                                    runGitActionForRepo(summary.repo.rootPath, "已暂存 ${change.displayPath}") {
                                        runEmbeddedGitAction {
                                            embeddedGitStagePath(summary.repo.rootPath, change.actionPath)
                                        }
                                    }
                                },
                                onUnstageFile = { change ->
                                    runGitActionForRepo(summary.repo.rootPath, "已取消暂存 ${change.displayPath}") {
                                        runEmbeddedGitAction {
                                            embeddedGitUnstagePath(summary.repo.rootPath, change.actionPath)
                                        }
                                    }
                                },
                                onDiscardFile = { change ->
                                    pendingDiscardTarget = ProjectGitDiscardTargetUi(
                                        repoRoot = summary.repo.rootPath,
                                        repoLabel = summary.repo.displayName,
                                        change = change
                                    )
                                },
                                onOpenCommit = {
                                    if (summary.repo.rootPath == selectedRepoRoot) {
                                        showCommitDialog = true
                                    }
                                },
                                onOpenBranches = {
                                    if (summary.repo.rootPath == selectedRepoRoot) {
                                        showBranchDialog = true
                                    }
                                },
                                onStageAll = {
                                    runGitActionForRepo(summary.repo.rootPath, "已全部暂存") {
                                        runEmbeddedGitAction {
                                            embeddedGitStageAll(summary.repo.rootPath)
                                        }
                                    }
                                },
                                onPushAll = {
                                    runGitActionForRepo(summary.repo.rootPath, "已完成推送") {
                                        runEmbeddedGitAction {
                                            embeddedGitPush(summary.repo.rootPath, config.githubToken.trim())
                                        }
                                    }
                                },
                                onDiscardAll = {
                                    pendingDiscardTarget = ProjectGitDiscardTargetUi(
                                        repoRoot = summary.repo.rootPath,
                                        repoLabel = summary.repo.displayName,
                                        change = null
                                    )
                                }
                            )
                        }
                    }
                }
            }
            if (suspectedRepos.isNotEmpty()) {
                ProjectSectionCard(
                    shape = RoundedCornerShape(14.dp),
                    surfaceColorOverride = surfaceColor.copy(alpha = 0.56f)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "疑似仓库 ${suspectedRepos.size}",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = "当前还没有识别到 `.git` 的目录保留在这里，需要时可以直接切换或初始化。",
                            style = MaterialTheme.typography.bodySmall,
                            color = mutedTextColor
                        )
                        suspectedRepos.take(4).forEach { repo ->
                            val childRepoCandidates = detectedRepos
                                .filter { candidate ->
                                    candidate.rootPath != repo.rootPath &&
                                        candidate.rootPath.startsWith(repo.rootPath + File.separator)
                                }
                                .sortedWith(
                                    compareBy<ProjectDetectedRepoUi> { !it.hasGitMetadata }
                                        .thenByDescending {
                                            buildProjectDetectedRepoFeatureLabels(it).size
                                        }
                                        .thenBy { it.relativePath.lowercase(Locale.getDefault()) }
                                )
                            val repoFeatureLabels = remember(repo) {
                                buildProjectDetectedRepoFeatureLabels(repo)
                            }
                            ProjectInsetCard(
                                modifier = Modifier.combinedClickable(
                                    onClick = { onSelectRepoRoot(repo.rootPath) },
                                    onLongClick = { suspectedRepoMenuTarget = repo }
                                ),
                                shape = RoundedCornerShape(12.dp),
                                surfaceColorOverride = chromeColor.copy(alpha = 0.48f)
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
                                            text = repo.displayName,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        Text(
                                            text = repo.relativePath,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = mutedTextColor
                                        )
                                        Row(
                                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            repoFeatureLabels.ifEmpty { listOf("待初始化") }.forEach { label ->
                                                FilterChip(
                                                    selected = true,
                                                    onClick = {},
                                                    label = { Text(label) }
                                                )
                                            }
                                        }
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        if (repo.rootPath != activeProjectPath) {
                                            TextButton(
                                                onClick = { onSelectRepoRoot(repo.rootPath) },
                                                enabled = !isGitLoading && !isGitActionRunning
                                            ) {
                                                Text("切换")
                                            }
                                        }
                                        OutlinedButton(
                                            onClick = {
                                                onSelectRepoRoot(repo.rootPath)
                                                showInitGitDialog = true
                                            },
                                            enabled = !isGitLoading && !isGitActionRunning
                                        ) {
                                            Text("初始化")
                                        }
                                    }
                                }
                                Text(
                                    text = if (childRepoCandidates.isEmpty()) {
                                        "点按先切到这个目录，右侧可直接初始化 Git；当前目录下还没有识别到更像独立项目的子路径。"
                                    } else {
                                        "点按切到当前目录，右侧可直接初始化；长按可改到更具体的子项目，已识别 ${childRepoCandidates.size} 个子项目候选。"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = mutedTextColor
                                )
                            }
                        }
                    }
                }
            }
            ProjectSectionCard(
                shape = RoundedCornerShape(14.dp),
                surfaceColorOverride = chromeColor.copy(alpha = 0.24f)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "当前账户仓库入口",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = "如果要看当前 GitHub 账号下的全部仓库，这里直接拉起未选择本地项目时的那套 Git 页面。",
                        style = MaterialTheme.typography.bodySmall,
                        color = mutedTextColor
                    )
                    activeDetectedRepo?.displayName?.takeIf { it.isNotBlank() }?.let { repoName ->
                        Text(
                            text = "当前项目: $repoName",
                            style = MaterialTheme.typography.bodySmall,
                            color = mutedTextColor
                        )
                    }
                    Button(
                        onClick = {
                            showStandaloneRepoBrowserSecondary = true
                            refreshViewerRepositories(preserveSelection = false)
                        },
                        enabled = !isViewerRepositoriesLoading
                    ) {
                        Text(if (isViewerRepositoriesLoading) "加载中" else "当前账户的所有仓库")
                    }
                }
            }
            }
        }
    }

    suspectedRepoMenuTarget?.let { repo ->
        val childRepoCandidates = detectedRepos
            .filter { candidate ->
                candidate.rootPath != repo.rootPath &&
                    candidate.rootPath.startsWith(repo.rootPath + File.separator)
            }
            .sortedWith(
                compareBy<ProjectDetectedRepoUi> { !it.hasGitMetadata }
                    .thenByDescending { buildProjectDetectedRepoFeatureLabels(it).size }
                    .thenBy { it.relativePath.lowercase(Locale.getDefault()) }
            )
        val repoFeatureLabels = remember(repo) { buildProjectDetectedRepoFeatureLabels(repo) }
        ProjectScreenLargeDialog(
            title = "切换子文件夹 / 初始化",
            subtitle = repo.displayName,
            onDismissRequest = { suspectedRepoMenuTarget = null },
            actions = {
                TextButton(onClick = { suspectedRepoMenuTarget = null }) {
                    Text("关闭")
                }
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "疑似仓库先保留当前目录，长按后可以把作用域切到下面识别到的子项目。",
                    style = MaterialTheme.typography.bodySmall
                )
                if (repoFeatureLabels.isNotEmpty()) {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        repoFeatureLabels.forEach { label ->
                            FilterChip(
                                selected = true,
                                onClick = {},
                                label = { Text(label) }
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            onSelectRepoRoot(repo.rootPath)
                            suspectedRepoMenuTarget = null
                            feedbackMessage = "已切到目录 ${repo.relativePath}"
                        }
                    ) {
                        Text("切到当前目录")
                    }
                    Button(
                        onClick = {
                            onSelectRepoRoot(repo.rootPath)
                            suspectedRepoMenuTarget = null
                            showInitGitDialog = true
                        },
                        enabled = !isGitLoading && !isGitActionRunning
                    ) {
                        Text("在此初始化")
                    }
                }
                if (childRepoCandidates.isEmpty()) {
                    Text(
                        text = "当前目录下还没有识别到更具体的子项目，先在这里初始化 Git 会更稳妥。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    childRepoCandidates.forEach { candidate ->
                        val candidateFeatureLabels = remember(candidate) {
                            buildProjectDetectedRepoFeatureLabels(candidate)
                        }
                        ProjectInsetCard(
                            shape = RoundedCornerShape(10.dp),
                            surfaceColorOverride = if (candidate.hasGitMetadata) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.32f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSelectRepoRoot(candidate.rootPath)
                                    suspectedRepoMenuTarget = null
                                    feedbackMessage = "已切到子文件夹 ${candidate.relativePath}"
                                }
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = if (candidate.hasGitMetadata) {
                                        "${candidate.relativePath}（Git 仓库）"
                                    } else {
                                        candidate.relativePath
                                    },
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = candidate.rootPath,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(
                                        onClick = {
                                            onSelectRepoRoot(candidate.rootPath)
                                            suspectedRepoMenuTarget = null
                                            feedbackMessage = "已切到子文件夹 ${candidate.relativePath}"
                                        }
                                    ) {
                                        Text(if (candidate.hasGitMetadata) "切到仓库" else "切到这里")
                                    }
                                    if (!candidate.hasGitMetadata) {
                                        OutlinedButton(
                                            onClick = {
                                                onSelectRepoRoot(candidate.rootPath)
                                                suspectedRepoMenuTarget = null
                                                showInitGitDialog = true
                                            },
                                            enabled = !isGitLoading && !isGitActionRunning
                                        ) {
                                            Text("直接初始化")
                                        }
                                    }
                                }
                                Row(
                                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    candidateFeatureLabels.ifEmpty {
                                        listOf(if (candidate.hasGitMetadata) "Git 仓库" else "待初始化")
                                    }.forEach { label ->
                                        FilterChip(
                                            selected = true,
                                            onClick = {},
                                            label = { Text(label) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showInitGitDialog) {
        ProjectScreenLargeDialog(
            title = "初始化 Git",
            onDismissRequest = { showInitGitDialog = false },
            actions = {
                TextButton(onClick = {
                    showInitGitDialog = false
                    initContinueToCreateGitHubRepo = false
                }) {
                    Text("取消")
                }
                Button(
                    onClick = {
                        val projectPath = activeProjectPath ?: return@Button
                        val branchName = initBranchDraft.trim().ifBlank { "main" }
                        val continueToCreateRepo = initContinueToCreateGitHubRepo
                        showInitGitDialog = false
                        runGitAction(
                            successFallback = "已初始化 Git 仓库",
                            onSuccess = {
                                if (continueToCreateRepo) {
                                    resetCreateGitHubRepoDraft()
                                    createGitHubRepoBindOriginFlag = true
                                    showCreateGitHubRepoDialog = true
                                }
                                initContinueToCreateGitHubRepo = false
                            }
                        ) {
                            initializeProjectGitRepository(projectPath, branchName)
                        }
                    },
                    enabled = activeProjectPath != null && !isGitLoading && !isGitActionRunning
                ) {
                    Text("初始化")
                }
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                activeProjectPath?.let { path ->
                    ProjectInsetCard(
                        shape = RoundedCornerShape(10.dp),
                        surfaceColorOverride = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = activeDetectedRepo?.displayName ?: File(path).name.ifBlank { path },
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = path,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                activeRepoFeatureLabels.ifEmpty { listOf("当前目录") }.forEach { label ->
                                    FilterChip(
                                        selected = true,
                                        onClick = {},
                                        label = { Text(label) }
                                    )
                                }
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = initBranchDraft,
                    onValueChange = { initBranchDraft = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("默认分支") },
                    placeholder = { Text("main") },
                    singleLine = true
                )
                Text(
                    text = "会在当前目录创建 `.git` 并尽量把默认分支设为你输入的名称。初始化完成后，这里会重新按单仓库或多仓库模式识别。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FilterChip(
                    selected = initContinueToCreateGitHubRepo,
                    onClick = {
                        if (config.githubToken.isNotBlank()) {
                            initContinueToCreateGitHubRepo = !initContinueToCreateGitHubRepo
                        }
                    },
                    label = {
                        Text(
                            if (config.githubToken.isNotBlank()) {
                                "初始化后继续创建 GitHub 仓库并绑定 origin"
                            } else {
                                "先配置 GitHub Token 后，才能继续创建远端仓库"
                            }
                        )
                    }
                )
                Text(
                    text = if (initContinueToCreateGitHubRepo) {
                        "初始化成功后会直接弹出 GitHub 建仓弹窗，并默认保留绑定 origin。"
                    } else {
                        "这一版先完成目录级初始化；后续如果要推 GitHub，可继续走“新建 GitHub 仓库并绑定 origin”。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (showCreateGitHubRepoDialog) {
        ProjectScreenLargeDialog(
            title = "新建 GitHub 仓库",
            onDismissRequest = { showCreateGitHubRepoDialog = false },
            actions = {
                TextButton(onClick = { showCreateGitHubRepoDialog = false }) {
                    Text("取消")
                }
                Button(
                    onClick = {
                        val projectPath = activeProjectPath ?: return@Button
                        val repoName = createGitHubRepoNameDraft.trim()
                        val token = config.githubToken.trim()
                        if (repoName.isBlank() || token.isBlank()) return@Button
                        showCreateGitHubRepoDialog = false
                        scope.launch {
                            isGitHubActionRunning = true
                            val createResult = withContext(Dispatchers.IO) {
                                createProjectGitHubRepository(
                                    repoName = repoName,
                                    description = createGitHubRepoDescriptionDraft.trim(),
                                    isPrivate = createGitHubRepoPrivateFlag,
                                    token = token,
                                    apiBaseUrl = config.getGitHubApiBaseUrl()
                                )
                            }
                            if (!createResult.success || createResult.repo == null) {
                                isGitHubActionRunning = false
                                val failureMessage = createResult.error ?: "创建 GitHub 仓库失败"
                                feedbackMessage = failureMessage
                                appendGitOperationRecord(
                                    title = "创建 GitHub 仓库",
                                    detail = failureMessage,
                                    repoRoot = projectPath,
                                    isSuccess = false
                                )
                                return@launch
                            }
                            var message = "已创建 GitHub 仓库 ${createResult.repo.owner}/${createResult.repo.repo}"
                            var operationSucceeded = true
                            if (createGitHubRepoBindOriginFlag) {
                                val remoteUrl = createResult.recommendedRemoteUrl
                                if (remoteUrl.isNullOrBlank()) {
                                    isGitHubActionRunning = false
                                    val missingRemoteMessage = "GitHub 已创建仓库，但没有返回可绑定的远端地址"
                                    feedbackMessage = missingRemoteMessage
                                    appendGitOperationRecord(
                                        title = "创建 GitHub 仓库",
                                        detail = missingRemoteMessage,
                                        repoRoot = projectPath,
                                        isSuccess = false
                                    )
                                    return@launch
                                }
                                val bindResult = withContext(Dispatchers.IO) {
                                    bindProjectGitHubRemote(
                                        projectPath = projectPath,
                                        remoteUrl = remoteUrl,
                                        preferredBranch = gitState.currentBranch ?: initBranchDraft.trim().ifBlank { "main" }
                                    )
                                }
                                message = if (bindResult.success) {
                                    "$message，并已绑定 origin（默认使用 HTTPS 远端）"
                                } else {
                                    operationSucceeded = false
                                    "$message，但绑定 origin 失败: ${bindResult.error ?: bindResult.output}"
                                }
                            }
                            isGitHubActionRunning = false
                            feedbackMessage = message
                            appendGitOperationRecord(
                                title = if (createGitHubRepoBindOriginFlag) {
                                    "创建 GitHub 仓库并绑定 origin"
                                } else {
                                    "创建 GitHub 仓库"
                                },
                                detail = message,
                                repoRoot = projectPath,
                                isSuccess = operationSucceeded
                            )
                            refreshGitState()
                        }
                    },
                    enabled = createGitHubRepoNameDraft.isNotBlank() &&
                        config.githubToken.isNotBlank() &&
                        !isGitHubActionRunning
                ) {
                    Text("创建")
                }
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                activeProjectPath?.let { path ->
                    ProjectInsetCard(
                        shape = RoundedCornerShape(10.dp),
                        surfaceColorOverride = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = activeDetectedRepo?.displayName ?: File(path).name.ifBlank { path },
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = path,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                activeRepoFeatureLabels.ifEmpty { listOf("当前目录") }.forEach { label ->
                                    FilterChip(
                                        selected = true,
                                        onClick = {},
                                        label = { Text(label) }
                                    )
                                }
                            }
                            Text(
                                text = if (gitState.isRepository) {
                                    "会基于当前本地仓库创建远端；如果勾选绑定 origin，创建完成后会直接写入远端地址。"
                                } else {
                                    "当前目录还没有 `.git`；如果保留绑定 origin，创建远端时会先初始化本地 Git，再写入 origin。"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = createGitHubRepoNameDraft,
                    onValueChange = { createGitHubRepoNameDraft = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("仓库名") },
                    placeholder = { Text("例如：MurongAgent") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = createGitHubRepoDescriptionDraft,
                    onValueChange = { createGitHubRepoDescriptionDraft = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("描述") },
                    minLines = 3,
                    maxLines = 5
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !createGitHubRepoPrivateFlag,
                        onClick = { createGitHubRepoPrivateFlag = false },
                        label = { Text("公开") }
                    )
                    FilterChip(
                        selected = createGitHubRepoPrivateFlag,
                        onClick = { createGitHubRepoPrivateFlag = true },
                        label = { Text("私有") }
                    )
                }
                FilterChip(
                    selected = createGitHubRepoBindOriginFlag,
                    onClick = { createGitHubRepoBindOriginFlag = !createGitHubRepoBindOriginFlag },
                    label = {
                        Text(
                            if (gitState.isRepository) "创建后绑定为 origin"
                            else "创建后自动初始化 Git 并绑定 origin"
                        )
                    }
                )
                Text(
                    text = "会使用当前设置页里的 GitHub Token 创建仓库。若同时绑定 origin，这里会默认写入 HTTPS 远端，后续由内置 Git 优先使用 Token 认证。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (showCommitDialog) {
        val finalCommitMessage = buildGitCommitMessage(commitTitleDraft, commitDetailDraft)
        ProjectScreenLargeDialog(
            title = "提交更改",
            onDismissRequest = { showCommitDialog = false },
            actions = {
                TextButton(
                    onClick = {
                        showCommitDialog = false
                        resetCommitDraft()
                    }
                ) {
                    Text("取消")
                }
                Button(
                    onClick = {
                        val repoRoot = gitState.repoRoot ?: return@Button
                        val message = finalCommitMessage
                        if (message.isBlank()) return@Button
                        showCommitDialog = false
                        runGitAction("提交完成") {
                            runEmbeddedGitAction {
                                embeddedGitCommit(repoRoot, message)
                            }
                        }
                        resetCommitDraft()
                    },
                    enabled = finalCommitMessage.isNotBlank() && !isGitActionRunning
                ) {
                    Text("提交")
                }
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val surfaceColor = rememberMurongSurfaceColor()
                val mutedTextColor = rememberMurongMutedTextColor()
                val chromeColor = rememberMurongChromeColor()
                Text(
                    text = buildString {
                        append("当前分支 ${gitState.currentBranch ?: "未知"}")
                        append(" · 已暂存 ${gitState.stagedFiles.size}")
                        append(" · 工作区待处理 ${gitState.modifiedFiles.size + gitState.untrackedFiles.size}")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedTextColor
                )
                Text(
                    text = "已暂存 ${gitState.stagedFiles.size} 个文件",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                if (gitState.stagedFiles.isNotEmpty()) {
                    ProjectInsetCard(
                        shape = RoundedCornerShape(10.dp),
                        surfaceColorOverride = surfaceColor.copy(alpha = 0.60f)
                    ) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            gitState.stagedFiles.take(6).forEach { file ->
                                Text(
                                    text = "${file.statusLabel} · ${file.displayPath}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = mutedTextColor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (gitState.stagedFiles.size > 6) {
                                Text(
                                    text = "还有 ${gitState.stagedFiles.size - 6} 个文件未展开",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = mutedTextColor
                                )
                            }
                        }
                    }
                }
                Text(
                    text = "常用模板",
                    style = MaterialTheme.typography.labelMedium
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PROJECT_GIT_COMMIT_TEMPLATES.forEach { template ->
                        OutlinedButton(
                            onClick = {
                                commitTitleDraft = template.prefix
                                if (commitDetailDraft.isBlank()) {
                                    commitDetailDraft = template.detailHint
                                }
                            }
                        ) {
                            Text(template.label)
                        }
                    }
                }
                OutlinedTextField(
                    value = commitTitleDraft,
                    onValueChange = { commitTitleDraft = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("标题") },
                    placeholder = { Text("例如：fix: 修复项目页提交弹窗交互") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = commitDetailDraft,
                    onValueChange = { commitDetailDraft = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("详细说明") },
                    placeholder = {
                        Text("可选，补充本次改动范围、原因或注意事项")
                    },
                    minLines = 4,
                    maxLines = 8
                )
                if (finalCommitMessage.isNotBlank()) {
                    ProjectInsetCard(
                        shape = RoundedCornerShape(10.dp),
                        surfaceColorOverride = chromeColor.copy(alpha = 0.58f)
                    ) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "最终提交信息预览",
                                style = MaterialTheme.typography.labelMedium
                            )
                            Text(
                                text = finalCommitMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = mutedTextColor
                            )
                        }
                    }
                }
                currentRepoOperationRecords.firstOrNull()?.let { lastRecord ->
                    ProjectInsetCard(
                        shape = RoundedCornerShape(10.dp),
                        surfaceColorOverride = surfaceColor.copy(alpha = 0.52f)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "最近一次 Git 操作",
                                style = MaterialTheme.typography.labelMedium
                            )
                            Text(
                                text = "${lastRecord.categoryLabel} · ${lastRecord.title} · ${lastRecord.timeLabel}",
                                style = MaterialTheme.typography.bodySmall,
                                color = mutedTextColor
                            )
                            Text(
                                text = lastRecord.detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (lastRecord.isSuccess) {
                                    mutedTextColor
                                } else {
                                    MaterialTheme.colorScheme.error
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    pendingDiscardTarget?.let { target ->
        val filePath = target.change?.displayPath
        ProjectScreenLargeDialog(
            title = if (filePath == null) "确认全部撤回同步云端" else "确认撤回文件",
            subtitle = target.repoLabel,
            onDismissRequest = { pendingDiscardTarget = null },
            actions = {
                TextButton(onClick = { pendingDiscardTarget = null }) {
                    Text("取消")
                }
                Button(
                    onClick = {
                        pendingDiscardTarget = null
                        if (target.change == null) {
                            runGitActionForRepo(target.repoRoot, "已撤回本地改动") {
                                runEmbeddedGitAction {
                                    embeddedGitSyncDiscardAll(target.repoRoot, config.githubToken.trim())
                                }
                            }
                        } else {
                            runGitActionForRepo(target.repoRoot, "已撤回 ${target.change.displayPath}") {
                                runEmbeddedGitAction {
                                    embeddedGitDiscardPath(
                                        repoRoot = target.repoRoot,
                                        relativePath = target.change.actionPath,
                                        githubToken = config.githubToken.trim()
                                    )
                                }
                            }
                        }
                    },
                    enabled = !isGitActionRunning
                ) {
                    Text("确认撤回")
                }
            }
        ) {
            Text(
                text = if (filePath == null) {
                    "会丢弃该仓库所有未提交改动，并尽量恢复到远端跟踪分支当前状态。"
                } else {
                    "会丢弃 `$filePath` 的本地改动，并尽量恢复到远端跟踪分支当前状态。"
                },
                style = MaterialTheme.typography.bodySmall
            )
        }
    }


    if (showBranchDialog) {
        ProjectScreenLargeDialog(
            title = "分支管理",
            onDismissRequest = { showBranchDialog = false },
            actions = {
                TextButton(
                    onClick = {
                        showBranchDialog = false
                        newBranchName = ""
                    }
                ) {
                    Text("关闭")
                }
                Button(
                    onClick = {
                        val repoRoot = gitState.repoRoot ?: return@Button
                        val targetBranch = newBranchName.trim()
                        if (targetBranch.isBlank()) return@Button
                        showBranchDialog = false
                        runGitAction("已切换到新分支 $targetBranch") {
                            runEmbeddedGitAction {
                                embeddedGitCreateBranch(repoRoot, targetBranch)
                            }
                        }
                        newBranchName = ""
                    },
                    enabled = newBranchName.isNotBlank() && !isGitActionRunning
                ) {
                    Text("新建并切换")
                }
            }
        ) {
            val surfaceColor = rememberMurongSurfaceColor()
            val chromeColor = rememberMurongChromeColor()
            val mutedTextColor = rememberMurongMutedTextColor()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = buildString {
                        append("当前 ${gitState.currentBranch ?: "未知"}")
                        append(" · 本地分支 ${gitState.localBranches.size}")
                        append(" · 远端分支 ${gitState.remoteBranches.size}")
                        if (!gitState.upstreamBranch.isNullOrBlank()) {
                            append(" · 上游 ${gitState.upstreamBranch}")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedTextColor
                )
                currentRepoOperationRecords.firstOrNull()?.let { lastRecord ->
                    ProjectInsetCard(
                        shape = RoundedCornerShape(10.dp),
                        surfaceColorOverride = chromeColor.copy(alpha = 0.48f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "最近一次仓库操作",
                                style = MaterialTheme.typography.labelMedium
                            )
                            Text(
                                text = "${lastRecord.categoryLabel} · ${lastRecord.title} · ${lastRecord.timeLabel}",
                                style = MaterialTheme.typography.bodySmall,
                                color = mutedTextColor
                            )
                            Text(
                                text = lastRecord.detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (lastRecord.isSuccess) {
                                    mutedTextColor
                                } else {
                                    MaterialTheme.colorScheme.error
                                }
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = newBranchName,
                    onValueChange = { newBranchName = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("新分支名") },
                    placeholder = { Text("例如：feature/mobile-git") },
                    singleLine = true
                )
                Text(
                    "本地分支",
                    style = MaterialTheme.typography.labelMedium,
                    color = mutedTextColor
                )
                if (gitState.localBranches.isNotEmpty()) {
                    ProjectInsetCard(
                        shape = RoundedCornerShape(10.dp),
                        surfaceColorOverride = surfaceColor.copy(alpha = 0.46f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "当前分支会显示跟踪信息，点“切换”即可直接切到目标本地分支。",
                            style = MaterialTheme.typography.bodySmall,
                            color = mutedTextColor
                        )
                    }
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    gitState.localBranches.forEach { branch ->
                        ProjectInsetCard(
                            shape = RoundedCornerShape(12.dp),
                            surfaceColorOverride = surfaceColor.copy(alpha = 0.58f),
                            modifier = Modifier.fillMaxWidth()
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
                                        if (branch.isCurrent) "${branch.name} · 当前" else branch.name,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    branch.trackInfo?.takeIf { it.isNotBlank() }?.let { track ->
                                        Text(
                                            track,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = mutedTextColor
                                        )
                                    }
                                }
                                if (!branch.isCurrent) {
                                    OutlinedButton(
                                        onClick = {
                                            val repoRoot = gitState.repoRoot ?: return@OutlinedButton
                                            showBranchDialog = false
                                            runGitAction("已切换到 ${branch.name}") {
                                                runEmbeddedGitAction {
                                                    embeddedGitCheckoutBranch(repoRoot, branch.name)
                                                }
                                            }
                                        },
                                        enabled = !isGitActionRunning
                                    ) {
                                        Text("切换")
                                    }
                                }
                            }
                        }
                    }
                    if (gitState.remoteBranches.isNotEmpty()) {
                        Text(
                            "远端分支",
                            style = MaterialTheme.typography.labelMedium,
                            color = mutedTextColor
                        )
                        ProjectInsetCard(
                            shape = RoundedCornerShape(10.dp),
                            surfaceColorOverride = chromeColor.copy(alpha = 0.46f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "点“跟踪”会按远端分支名创建或切换本地分支，并自动建立上游关系。",
                                style = MaterialTheme.typography.bodySmall,
                                color = mutedTextColor
                            )
                        }
                        gitState.remoteBranches.forEach { remoteBranch ->
                            val suggestedLocalName = remoteBranch.substringAfter('/', remoteBranch)
                            ProjectInsetCard(
                                shape = RoundedCornerShape(12.dp),
                                surfaceColorOverride = chromeColor.copy(alpha = 0.56f),
                                modifier = Modifier.fillMaxWidth()
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
                                            remoteBranch,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        Text(
                                            "本地建议分支名: $suggestedLocalName",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = mutedTextColor
                                        )
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            val repoRoot = gitState.repoRoot ?: return@OutlinedButton
                                            showBranchDialog = false
                                            runGitAction("已跟踪并切换到 $suggestedLocalName") {
                                                runEmbeddedGitAction {
                                                    embeddedGitCheckoutRemoteBranch(repoRoot, remoteBranch)
                                                }
                                            }
                                        },
                                        enabled = !isGitActionRunning
                                    ) {
                                        Text("跟踪")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(
        workflowDispatchTarget?.id,
        githubActionsState.repo?.owner,
        githubActionsState.repo?.repo,
        config.githubToken,
        config.githubApiBaseUrl
    ) {
        val workflow = workflowDispatchTarget ?: return@LaunchedEffect
        val repo = githubActionsState.repo ?: return@LaunchedEffect
        val token = config.githubToken.trim()
        if (token.isBlank()) {
            workflowDispatchBranchRefs = emptyList()
            isWorkflowDispatchBranchesLoading = false
            return@LaunchedEffect
        }
        isWorkflowDispatchBranchesLoading = true
        val result = withContext(Dispatchers.IO) {
            loadProjectGitHubBranches(
                repo = repo,
                token = token,
                apiBaseUrl = config.getGitHubApiBaseUrl()
            )
        }
        val preferredBranches = listOfNotNull(
            workflowDispatchRefDraft.trim().takeIf { it.isNotBlank() },
            githubActionsState.defaultBranch?.takeIf { it.isNotBlank() },
            selectedViewerRepository?.defaultBranch?.takeIf { it.isNotBlank() }
        ).distinct()
        workflowDispatchBranchRefs = preferredBranches +
            result.branches.filterNot { branch -> preferredBranches.contains(branch) }
        if (!result.error.isNullOrBlank()) {
            workflowDispatchSchemaMessage = result.error
        }
        isWorkflowDispatchBranchesLoading = false
    }

    LaunchedEffect(
        workflowDispatchTarget?.id,
        workflowDispatchRefDraft,
        githubActionsState.repo?.owner,
        githubActionsState.repo?.repo,
        config.githubToken,
        config.githubApiBaseUrl
    ) {
        val workflow = workflowDispatchTarget ?: return@LaunchedEffect
        val repo = githubActionsState.repo ?: return@LaunchedEffect
        val token = config.githubToken.trim()
        if (token.isBlank()) {
            workflowDispatchInputs = emptyList()
            workflowDispatchSchemaMessage = "请先在设置页填写 GitHub Token。"
            isWorkflowDispatchSchemaLoading = false
            return@LaunchedEffect
        }
        val ref = workflowDispatchRefDraft.trim()
            .ifBlank { githubActionsState.defaultBranch ?: selectedViewerRepository?.defaultBranch ?: "main" }
        isWorkflowDispatchSchemaLoading = true
        val result = withContext(Dispatchers.IO) {
            loadProjectGitHubWorkflowDispatchSchema(
                repo = repo,
                workflowPath = workflow.path,
                ref = ref,
                token = token,
                apiBaseUrl = config.getGitHubApiBaseUrl()
            )
        }
        workflowDispatchInputs = mergeProjectGitHubWorkflowDispatchInputs(
            current = workflowDispatchInputs,
            parsed = result.inputs
        )
        workflowDispatchSchemaMessage = when {
            result.inputs.isNotEmpty() -> "已自动识别 ${result.inputs.size} 个 workflow_dispatch 参数。"
            !result.error.isNullOrBlank() -> "${result.error}，可手动补充参数。"
            else -> "当前工作流没有声明 workflow_dispatch.inputs，可直接运行或手动补充参数。"
        }
        isWorkflowDispatchSchemaLoading = false
    }

    workflowDispatchTarget?.let { workflow ->
        val quickRefs = listOfNotNull(
            workflowRunDetailDialogState?.headBranch?.takeIf { it.isNotBlank() },
            gitState.currentBranch?.takeIf { it.isNotBlank() },
            githubActionsState.defaultBranch?.takeIf { it.isNotBlank() },
            selectedViewerRepository?.defaultBranch?.takeIf { it.isNotBlank() },
            gitState.upstreamBranch?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        ).distinct()
        ProjectGitHubWorkflowDispatchDialog(
            workflow = workflow,
            refDraft = workflowDispatchRefDraft,
            quickRefs = quickRefs,
            branchRefs = workflowDispatchBranchRefs,
            isBranchRefsLoading = isWorkflowDispatchBranchesLoading,
            inputs = workflowDispatchInputs,
            isSchemaLoading = isWorkflowDispatchSchemaLoading,
            schemaMessage = workflowDispatchSchemaMessage,
            isActionRunning = isGitHubActionRunning,
            onRefDraftChange = { workflowDispatchRefDraft = it },
            onInputKeyChange = { index, value ->
                workflowDispatchInputs = workflowDispatchInputs.mapIndexed { currentIndex, item ->
                    if (currentIndex == index) item.copy(key = value) else item
                }
            },
            onInputValueChange = { index, value ->
                workflowDispatchInputs = workflowDispatchInputs.mapIndexed { currentIndex, item ->
                    if (currentIndex == index) item.copy(value = value) else item
                }
            },
            onAddInput = {
                workflowDispatchInputs = workflowDispatchInputs + ProjectGitHubWorkflowDispatchInputUi()
            },
            onRemoveInput = { index ->
                workflowDispatchInputs = workflowDispatchInputs.filterIndexed { currentIndex, _ ->
                    currentIndex != index
                }
            },
            onConfirm = {
                val repo = githubActionsState.repo ?: return@ProjectGitHubWorkflowDispatchDialog
                val token = config.githubToken.trim()
                val ref = workflowDispatchRefDraft.trim()
                if (token.isBlank() || ref.isBlank()) return@ProjectGitHubWorkflowDispatchDialog
                val missingRequiredInputs = workflowDispatchInputs.filter { input ->
                    input.autoDetected &&
                        input.required &&
                        input.key.isNotBlank() &&
                        input.value.trim().ifBlank { input.defaultValue?.trim().orEmpty() }.isBlank()
                }
                if (missingRequiredInputs.isNotEmpty()) {
                    feedbackMessage = "请先填写必填参数：${
                        missingRequiredInputs.joinToString("、") { it.key }
                    }"
                    return@ProjectGitHubWorkflowDispatchDialog
                }
                val inputs = workflowDispatchInputs
                    .mapNotNull { input ->
                        val key = input.key.trim()
                        val value = input.value.trim().ifBlank { input.defaultValue?.trim().orEmpty() }
                        if (key.isBlank() || value.isBlank()) null else key to value
                    }
                    .toMap()
                workflowDispatchTarget = null
                runGitHubAction("已触发工作流 ${workflow.name}") {
                    dispatchProjectGitHubWorkflow(
                        repo = repo,
                        workflowId = workflow.id,
                        ref = ref,
                        inputs = inputs,
                        token = token,
                        apiBaseUrl = config.getGitHubApiBaseUrl()
                    )
                }
            },
            onDismiss = { workflowDispatchTarget = null }
        )
    }

    artifactDialogState?.let { dialog ->
        ProjectGitHubArtifactDialog(
            dialog = dialog,
            onDismiss = { artifactDialogState = null },
            onOpenRunPage = { url ->
                openGitHubPage(
                    url,
                    "当前运行记录还没有可打开的 GitHub 页面地址。"
                )
            },
            onDownloadRunLogs = { target ->
                val result = enqueueProjectGitHubWorkflowLogsDownloadAction(
                    context = context,
                    repo = githubActionsState.repo,
                    runId = target.runId,
                    runDisplayTitle = target.runTitle,
                    sourceUrl = target.runHtmlUrl,
                    token = config.githubToken.trim(),
                    apiBaseUrl = config.getGitHubApiBaseUrl()
                )
                result.downloadRecord?.let { record ->
                    recordGitHubDownload(
                        typeLabel = record.typeLabel,
                        title = record.title,
                        fileName = record.fileName,
                        downloadId = record.downloadId,
                        sourceUrl = record.sourceUrl,
                        repo = record.repo
                    )
                }
                result.feedbackMessage?.let { feedbackMessage = it }
            },
            onDownloadArtifact = { target, artifact ->
                val result = enqueueProjectGitHubWorkflowArtifactDownloadAction(
                    context = context,
                    repo = githubActionsState.repo,
                    artifact = artifact,
                    sourceUrl = target.runHtmlUrl,
                    token = config.githubToken.trim()
                )
                result.downloadRecord?.let { record ->
                    recordGitHubDownload(
                        typeLabel = record.typeLabel,
                        title = record.title,
                        fileName = record.fileName,
                        downloadId = record.downloadId,
                        sourceUrl = record.sourceUrl,
                        repo = record.repo
                    )
                }
                result.feedbackMessage?.let { feedbackMessage = it }
            }
        )
    }

    workflowRunDetailDialogState?.let { detail ->
        ProjectGitHubWorkflowRunDetailDialog(
            detail = detail,
            onDismiss = { workflowRunDetailDialogState = null },
            onOpenRunPage = { url ->
                openGitHubPage(
                    url,
                    "当前运行详情还没有可打开的 GitHub 页面地址。"
                )
            },
            onRefreshDetail = { currentDetail ->
                scope.launch {
                    isGitHubActionRunning = true
                    val result = withContext(Dispatchers.IO) {
                        refreshProjectGitHubWorkflowRunDetailAction(
                            currentDetail = currentDetail,
                            repo = githubActionsState.repo,
                            token = config.githubToken.trim(),
                            apiBaseUrl = config.getGitHubApiBaseUrl()
                        )
                    }
                    isGitHubActionRunning = false
                    result.updatedDetail?.let { workflowRunDetailDialogState = it }
                    result.feedbackMessage?.let { feedbackMessage = it }
                }
            },
            onRerun = { currentDetail ->
                val workflow = githubActionsState.workflows.firstOrNull { candidate ->
                    candidate.name == currentDetail.workflowName || candidate.path.endsWith(currentDetail.workflowName)
                }
                if (workflow == null) {
                    feedbackMessage = "当前仓库里还没找到可重跑的工作流定义，请先刷新工作流列表。"
                } else {
                    openWorkflowDispatchDialog(
                        workflow = workflow,
                        preferredRef = currentDetail.headBranch
                    )
                }
            },
            onDownloadLogs = { currentDetail ->
                val result = enqueueProjectGitHubWorkflowLogsDownloadAction(
                    context = context,
                    repo = githubActionsState.repo,
                    runId = currentDetail.id,
                    runDisplayTitle = currentDetail.title,
                    sourceUrl = currentDetail.htmlUrl,
                    token = config.githubToken.trim(),
                    apiBaseUrl = config.getGitHubApiBaseUrl()
                )
                result.downloadRecord?.let { record ->
                    recordGitHubDownload(
                        typeLabel = record.typeLabel,
                        title = record.title,
                        fileName = record.fileName,
                        downloadId = record.downloadId,
                        sourceUrl = record.sourceUrl,
                        repo = record.repo
                    )
                }
                result.feedbackMessage?.let { feedbackMessage = it }
            },
            onDownloadArtifact = { currentDetail, artifact ->
                val result = enqueueProjectGitHubWorkflowArtifactDownloadAction(
                    context = context,
                    repo = githubActionsState.repo,
                    artifact = artifact,
                    sourceUrl = currentDetail.htmlUrl,
                    token = config.githubToken.trim()
                )
                result.downloadRecord?.let { record ->
                    recordGitHubDownload(
                        typeLabel = record.typeLabel,
                        title = record.title,
                        fileName = record.fileName,
                        downloadId = record.downloadId,
                        sourceUrl = record.sourceUrl,
                        repo = record.repo
                    )
                }
                result.feedbackMessage?.let { feedbackMessage = it }
            }
        )
    }

    releaseEditTarget?.let { release ->
        ProjectScreenLargeDialog(
            title = "编辑 Release",
            onDismissRequest = { releaseEditTarget = null },
            actions = {
                TextButton(onClick = { releaseEditTarget = null }) {
                    Text("取消")
                }
                Button(
                    onClick = {
                        runGitHubMutationAction(
                            block = {
                                submitProjectGitHubEditReleaseAction(
                                    repo = githubActionsState.repo,
                                    release = release,
                                    token = config.githubToken.trim(),
                                    apiBaseUrl = config.getGitHubApiBaseUrl(),
                                    draft = releaseDraftState
                                )
                            },
                            onResult = { result ->
                                if (result.shouldDismissDialog) {
                                    releaseEditTarget = null
                                }
                            }
                        )
                    },
                    enabled = releaseDraftState.releaseTag.isNotBlank() && !isGitHubActionRunning
                ) {
                    Text("保存")
                }
            }
        ) {
            val mutedTextColor = rememberMurongMutedTextColor()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = releaseDraftState.releaseTag,
                    onValueChange = {
                        releaseDraftState = releaseDraftState.copy(releaseTag = it)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Tag") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = releaseDraftState.releaseName,
                    onValueChange = {
                        releaseDraftState = releaseDraftState.copy(releaseName = it)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("标题") },
                    placeholder = { Text("可留空，GitHub 会显示 Tag") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = releaseDraftState.releaseBody,
                    onValueChange = {
                        releaseDraftState = releaseDraftState.copy(releaseBody = it)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("发布说明") },
                    minLines = 6,
                    maxLines = 12
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = releaseDraftState.isDraft,
                        onClick = {
                            releaseDraftState =
                                toggleProjectGitHubReleaseDraftState(releaseDraftState)
                        },
                        label = { Text("草稿") }
                    )
                    FilterChip(
                        selected = releaseDraftState.isPrerelease,
                        onClick = {
                            releaseDraftState =
                                toggleProjectGitHubReleasePrereleaseState(releaseDraftState)
                        },
                        label = { Text("预发布") }
                    )
                }
                Text(
                    text = if (releaseDraftState.isDraft) {
                        "当前将保存为草稿 Release"
                    } else if (releaseDraftState.isPrerelease) {
                        "当前将保存为预发布 Release"
                    } else {
                        "当前将保存为正式 Release"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedTextColor
                )
            }
        }
    }

    if (showCreateReleaseDialog) {
        ProjectScreenLargeDialog(
            title = "新建 Release",
            onDismissRequest = {
                showCreateReleaseDialog = false
                resetReleaseDraft()
            },
            actions = {
                TextButton(
                    onClick = {
                        showCreateReleaseDialog = false
                        resetReleaseDraft()
                    }
                ) {
                    Text("取消")
                }
                Button(
                    onClick = {
                        runGitHubMutationAction(
                            block = {
                                submitProjectGitHubCreateReleaseAction(
                                    repo = githubActionsState.repo,
                                    token = config.githubToken.trim(),
                                    apiBaseUrl = config.getGitHubApiBaseUrl(),
                                    draft = releaseDraftState
                                )
                            },
                            onResult = { result ->
                                if (result.shouldDismissDialog) {
                                    showCreateReleaseDialog = false
                                }
                            }
                        )
                    },
                    enabled = releaseDraftState.releaseTag.isNotBlank() && !isGitHubActionRunning
                ) {
                    Text("创建")
                }
            }
        ) {
            val mutedTextColor = rememberMurongMutedTextColor()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = releaseDraftState.releaseTag,
                    onValueChange = {
                        releaseDraftState = releaseDraftState.copy(releaseTag = it)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Tag") },
                    placeholder = { Text("例如：0.9.0-preview") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = releaseDraftState.releaseName,
                    onValueChange = {
                        releaseDraftState = releaseDraftState.copy(releaseName = it)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("标题") },
                    placeholder = { Text("例如：六月版本更新") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = releaseDraftState.releaseBody,
                    onValueChange = {
                        releaseDraftState = releaseDraftState.copy(releaseBody = it)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("发布说明") },
                    minLines = 6,
                    maxLines = 12
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = releaseDraftState.isDraft,
                        onClick = {
                            releaseDraftState =
                                toggleProjectGitHubReleaseDraftState(releaseDraftState)
                        },
                        label = { Text("草稿") }
                    )
                    FilterChip(
                        selected = releaseDraftState.isPrerelease,
                        onClick = {
                            releaseDraftState =
                                toggleProjectGitHubReleasePrereleaseState(releaseDraftState)
                        },
                        label = { Text("预发布") }
                    )
                }
                Text(
                    text = if (releaseDraftState.isDraft) {
                        "创建后先保留为草稿"
                    } else if (releaseDraftState.isPrerelease) {
                        "创建后会标记为预发布"
                    } else {
                        "创建后会作为正式 Release 展示"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedTextColor
                )
            }
        }
    }

    releaseAssetDialogState?.let { dialog ->
        ProjectGitHubReleaseAssetDialog(
            dialog = dialog,
            onDismiss = { releaseAssetDialogState = null },
            isActionRunning = isGitHubActionRunning,
            onOpenReleasePage = { url ->
                openGitHubPage(
                    url,
                    "当前 Release 还没有可打开的 GitHub 页面地址。"
                )
            },
            onDownloadAsset = { target, asset ->
                val result = enqueueProjectGitHubReleaseAssetDownloadAction(
                    context = context,
                    repo = githubActionsState.repo,
                    asset = asset,
                    sourceUrl = asset.browserDownloadUrl ?: target.releaseHtmlUrl,
                    token = config.githubToken.trim()
                )
                result.downloadRecord?.let { record ->
                    recordGitHubDownload(
                        typeLabel = record.typeLabel,
                        title = record.title,
                        fileName = record.fileName,
                        downloadId = record.downloadId,
                        sourceUrl = record.sourceUrl,
                        repo = record.repo
                    )
                }
                result.feedbackMessage?.let { feedbackMessage = it }
            },
            onDeleteAsset = { target, asset ->
                runGitHubMutationAction(
                    block = {
                        deleteProjectGitHubReleaseAssetAction(
                            repo = githubActionsState.repo,
                            asset = asset,
                            token = config.githubToken.trim(),
                            apiBaseUrl = config.getGitHubApiBaseUrl()
                        )
                    },
                    onResult = { result ->
                        if (result.success) {
                            val remainingAssets = target.assets.filterNot { it.id == asset.id }
                            releaseAssetDialogState = if (remainingAssets.isEmpty()) {
                                target.copy(assets = emptyList())
                            } else {
                                target.copy(assets = remainingAssets)
                            }
                        }
                    }
                )
            }
        )
    }

    if (showCreateIssueDialog) {
        ProjectScreenLargeDialog(
            title = "新建 Issue",
            onDismissRequest = {
                showCreateIssueDialog = false
                resetCreateIssueDraft()
            },
            actions = {
                TextButton(
                    onClick = {
                        showCreateIssueDialog = false
                        resetCreateIssueDraft()
                    }
                ) {
                    Text("取消")
                }
                Button(
                    onClick = {
                        runGitHubMutationAction(
                            block = {
                                submitProjectGitHubCreateIssueAction(
                                    repo = githubActionsState.repo,
                                    token = config.githubToken.trim(),
                                    apiBaseUrl = config.getGitHubApiBaseUrl(),
                                    draft = createIssueDraftState
                                )
                            },
                            onResult = { result ->
                                if (result.shouldDismissDialog) {
                                    showCreateIssueDialog = false
                                }
                            }
                        )
                    },
                    enabled = createIssueDraftState.title.isNotBlank() && !isGitHubActionRunning
                ) {
                    Text("创建")
                }
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = createIssueDraftState.title,
                    onValueChange = {
                        createIssueDraftState = createIssueDraftState.copy(title = it)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("标题") },
                    placeholder = { Text("例如：共享存储仓库识别异常") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = createIssueDraftState.body,
                    onValueChange = {
                        createIssueDraftState = createIssueDraftState.copy(body = it)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("正文") },
                    placeholder = { Text("补充复现步骤、预期行为和日志") },
                    minLines = 6,
                    maxLines = 12
                )
            }
        }
    }

    if (showCreatePullRequestDialog) {
        ProjectScreenLargeDialog(
            title = "新建 Pull Request",
            onDismissRequest = {
                showCreatePullRequestDialog = false
                resetCreatePullRequestDraft()
            },
            actions = {
                TextButton(
                    onClick = {
                        showCreatePullRequestDialog = false
                        resetCreatePullRequestDraft()
                    }
                ) {
                    Text("取消")
                }
                Button(
                    onClick = {
                        runGitHubMutationAction(
                            block = {
                                submitProjectGitHubCreatePullRequestAction(
                                    repo = githubActionsState.repo,
                                    token = config.githubToken.trim(),
                                    apiBaseUrl = config.getGitHubApiBaseUrl(),
                                    draft = createPullRequestDraftState
                                )
                            },
                            onResult = { result ->
                                if (result.shouldDismissDialog) {
                                    showCreatePullRequestDialog = false
                                    createPullRequestDraftState =
                                        clearProjectGitHubPullRequestDraftState(
                                            currentBranch = gitState.currentBranch,
                                            defaultBranch = githubActionsState.defaultBranch,
                                            upstreamBranch = gitState.upstreamBranch
                                        )
                                }
                            }
                        )
                    },
                    enabled = createPullRequestDraftState.title.isNotBlank() &&
                        createPullRequestDraftState.head.isNotBlank() &&
                        createPullRequestDraftState.base.isNotBlank() &&
                        !isGitHubActionRunning
                ) {
                    Text("创建")
                }
            }
        ) {
            val mutedTextColor = rememberMurongMutedTextColor()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = createPullRequestDraftState.title,
                    onValueChange = {
                        createPullRequestDraftState =
                            createPullRequestDraftState.copy(title = it)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("标题") },
                    placeholder = { Text("例如：修复共享存储下 Git 仓库识别") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = createPullRequestDraftState.head,
                    onValueChange = {
                        createPullRequestDraftState =
                            createPullRequestDraftState.copy(head = it)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("来源分支") },
                    placeholder = { Text("例如：feature/storage-git-fix") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = createPullRequestDraftState.base,
                    onValueChange = {
                        createPullRequestDraftState =
                            createPullRequestDraftState.copy(base = it)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("目标分支") },
                    placeholder = { Text("例如：main") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = createPullRequestDraftState.body,
                    onValueChange = {
                        createPullRequestDraftState =
                            createPullRequestDraftState.copy(body = it)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("说明") },
                    placeholder = { Text("补充改动摘要、验证结果和影响范围") },
                    minLines = 6,
                    maxLines = 12
                )
                FilterChip(
                    selected = createPullRequestDraftState.isDraft,
                    onClick = {
                        createPullRequestDraftState = createPullRequestDraftState.copy(
                            isDraft = !createPullRequestDraftState.isDraft
                        )
                    },
                    label = { Text("草稿 PR") }
                )
                Text(
                    text = if (createPullRequestDraftState.isDraft) {
                        "创建后会先保留为草稿 Pull Request。"
                    } else {
                        "创建后会作为普通 Pull Request 提交到目标分支。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedTextColor
                )
            }
        }
    }

    diffPreview?.let { preview ->
        ProjectScreenLargeDialog(
            title = preview.title,
            onDismissRequest = { diffPreview = null },
            actions = {
                TextButton(onClick = { diffPreview = null }) {
                    Text("关闭")
                }
            }
        ) {
            ProjectSectionCard(shape = RoundedCornerShape(12.dp)) {
                val renderedDiffLines = remember(preview.content) {
                    preview.content.lineSequence()
                        .map { line -> projectGitBuildRenderedDiffLine(line) }
                        .toList()
                }
                SelectionContainer {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        renderedDiffLines.forEachIndexed { index, line ->
                            if (index > 0 && line.type == ProjectGitRenderedDiffLineType.FILE_HEADER) {
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                            ProjectGitRenderedDiffLine(
                                line = line,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }
    }

    issueDetailStore.issue?.let { issue ->
        val repo = githubActionsState.repo
        val token = config.githubToken.trim()
        val closeIssueDetailDialog = {
            issueDetailStore = clearProjectGitHubIssueDetailStore()
        }
        ProjectGitHubIssueDetailDialog(
            issue = issue,
            comments = issueDetailStore.comments,
            isCommentsLoading = issueDetailStore.isCommentsLoading,
            isActionRunning = isGitHubActionRunning,
            draft = issueDetailStore.commentDraft,
            onDraftChange = {
                issueDetailStore = issueDetailStore.copy(commentDraft = it)
            },
            onDismiss = closeIssueDetailDialog,
            onOpenIssuePage = { url ->
                openGitHubPage(
                    url,
                    "当前 Issue 还没有可打开的 GitHub 页面地址。"
                )
            },
            onRefreshComments = { loadIssueComments(issue.number) },
            onSubmitComment = {
                scope.launch {
                    isGitHubActionRunning = true
                    val result = withContext(Dispatchers.IO) {
                        submitProjectGitHubIssueDetailComment(
                            currentStore = issueDetailStore,
                            repo = repo,
                            token = token,
                            apiBaseUrl = config.getGitHubApiBaseUrl()
                        )
                    }
                    isGitHubActionRunning = false
                    result.feedbackMessage?.let { feedbackMessage = it }
                    result.nextStore?.let { issueDetailStore = it }
                    if (result.shouldRefreshComments) {
                        loadIssueComments(issue.number)
                    }
                    if (result.shouldRefreshGitHubActions) {
                        refreshGitHubActions()
                    }
                }
            },
            onToggleIssueState = {
                scope.launch {
                    isGitHubActionRunning = true
                    val result = withContext(Dispatchers.IO) {
                        toggleProjectGitHubIssueDetailState(
                            currentStore = issueDetailStore,
                            repo = repo,
                            token = token,
                            apiBaseUrl = config.getGitHubApiBaseUrl()
                        )
                    }
                    isGitHubActionRunning = false
                    result.feedbackMessage?.let { feedbackMessage = it }
                    result.nextStore?.let { issueDetailStore = it }
                    if (result.shouldRefreshGitHubActions) {
                        refreshGitHubActions()
                    }
                }
            },
            canToggleIssueState = repo != null && token.isNotBlank()
        )
    }

    pullRequestDetailStore.pullRequest?.let { pullRequest ->
        val repo = githubActionsState.repo
        val token = config.githubToken.trim()
        val closePullRequestDetailDialog = {
            pullRequestDetailStore = clearProjectGitHubPullRequestDetailStore()
        }
        ProjectGitHubPullRequestDetailDialog(
            pullRequest = pullRequest,
            comments = pullRequestDetailStore.comments,
            isCommentsLoading = pullRequestDetailStore.isCommentsLoading,
            commentDraft = pullRequestDetailStore.commentDraft,
            onCommentDraftChange = {
                pullRequestDetailStore = pullRequestDetailStore.copy(commentDraft = it)
            },
            onRefreshComments = { loadPullRequestComments(pullRequest.number) },
            onSubmitComment = {
                scope.launch {
                    isGitHubActionRunning = true
                    val result = withContext(Dispatchers.IO) {
                        submitProjectGitHubPullRequestDetailComment(
                            currentStore = pullRequestDetailStore,
                            repo = repo,
                            token = token,
                            apiBaseUrl = config.getGitHubApiBaseUrl()
                        )
                    }
                    isGitHubActionRunning = false
                    result.feedbackMessage?.let { feedbackMessage = it }
                    result.nextStore?.let { pullRequestDetailStore = it }
                    if (result.shouldRefreshComments) {
                        loadPullRequestComments(pullRequest.number)
                    }
                    if (result.shouldRefreshGitHubActions) {
                        refreshGitHubActions()
                    }
                }
            },
            reviews = pullRequestDetailStore.reviews,
            isReviewsLoading = pullRequestDetailStore.isReviewsLoading,
            reviewDraft = pullRequestDetailStore.reviewDraft,
            onReviewDraftChange = {
                pullRequestDetailStore = pullRequestDetailStore.copy(reviewDraft = it)
            },
            onRefreshReviews = { loadPullRequestReviews(pullRequest.number) },
            onSubmitReview = { event ->
                scope.launch {
                    isGitHubActionRunning = true
                    val result = withContext(Dispatchers.IO) {
                        submitProjectGitHubPullRequestDetailReview(
                            currentStore = pullRequestDetailStore,
                            repo = repo,
                            token = token,
                            apiBaseUrl = config.getGitHubApiBaseUrl(),
                            event = event,
                        )
                    }
                    isGitHubActionRunning = false
                    result.feedbackMessage?.let { feedbackMessage = it }
                    result.nextStore?.let { pullRequestDetailStore = it }
                    if (result.shouldRefreshReviews) {
                        loadPullRequestReviews(pullRequest.number)
                    }
                    if (result.shouldRefreshGitHubActions) {
                        refreshGitHubActions()
                    }
                }
            },
            files = pullRequestDetailStore.files,
            reviewComments = pullRequestDetailStore.reviewComments,
            isFilesLoading = pullRequestDetailStore.isFilesLoading,
            isReviewCommentsLoading = pullRequestDetailStore.isReviewCommentsLoading,
            reviewCommentDraft = pullRequestDetailStore.reviewCommentDraft,
            onReviewCommentDraftChange = {
                pullRequestDetailStore = pullRequestDetailStore.copy(reviewCommentDraft = it)
            },
            reviewCommentPathDraft = pullRequestDetailStore.reviewCommentPathDraft,
            onReviewCommentPathDraftChange = {
                pullRequestDetailStore = pullRequestDetailStore.copy(reviewCommentPathDraft = it)
            },
            reviewCommentLineDraft = pullRequestDetailStore.reviewCommentLineDraft,
            onReviewCommentLineDraftChange = {
                pullRequestDetailStore = pullRequestDetailStore.copy(reviewCommentLineDraft = it)
            },
            onPickReviewCommentPath = {
                pullRequestDetailStore = pullRequestDetailStore.copy(reviewCommentPathDraft = it)
            },
            onPickReviewCommentLine = {
                pullRequestDetailStore = pullRequestDetailStore.copy(reviewCommentLineDraft = it)
            },
            onRefreshReviewComments = {
                loadPullRequestFiles(pullRequest.number)
                loadPullRequestReviewComments(pullRequest.number)
            },
            onSubmitReviewComment = {
                scope.launch {
                    isGitHubActionRunning = true
                    val result = withContext(Dispatchers.IO) {
                        submitProjectGitHubPullRequestDetailReviewComment(
                            currentStore = pullRequestDetailStore,
                            repo = repo,
                            token = token,
                            apiBaseUrl = config.getGitHubApiBaseUrl()
                        )
                    }
                    isGitHubActionRunning = false
                    result.feedbackMessage?.let { feedbackMessage = it }
                    result.nextStore?.let { pullRequestDetailStore = it }
                    if (result.shouldRefreshReviewComments) {
                        loadPullRequestReviewComments(pullRequest.number)
                    }
                }
            },
            onReplyToReviewComment = { commentId, body, onSuccess ->
                scope.launch {
                    isGitHubActionRunning = true
                    val result = withContext(Dispatchers.IO) {
                        replyProjectGitHubPullRequestDetailReviewComment(
                            currentStore = pullRequestDetailStore,
                            repo = repo,
                            token = token,
                            apiBaseUrl = config.getGitHubApiBaseUrl(),
                            commentId = commentId,
                            body = body
                        )
                    }
                    isGitHubActionRunning = false
                    result.feedbackMessage?.let { feedbackMessage = it }
                    result.nextStore?.let { pullRequestDetailStore = it }
                    if (result.success) {
                        onSuccess()
                    }
                    if (result.shouldRefreshReviewComments) {
                        loadPullRequestReviewComments(pullRequest.number)
                    }
                }
            },
            isActionRunning = isGitHubActionRunning,
            onDismiss = closePullRequestDetailDialog,
            onOpenPullRequestPage = { url ->
                openGitHubPage(
                    url,
                    "当前 Pull Request 还没有可打开的 GitHub 页面地址。"
                )
            },
            onMerge = {
                scope.launch {
                    isGitHubActionRunning = true
                    val result = withContext(Dispatchers.IO) {
                        mergeProjectGitHubPullRequestDetail(
                            currentStore = pullRequestDetailStore,
                            repo = repo,
                            token = token,
                            apiBaseUrl = config.getGitHubApiBaseUrl()
                        )
                    }
                    isGitHubActionRunning = false
                    result.feedbackMessage?.let { feedbackMessage = it }
                    result.nextStore?.let { pullRequestDetailStore = it }
                    if (result.shouldRefreshGitHubActions) {
                        refreshGitHubActions()
                    }
                }
            },
            canMerge = repo != null && token.isNotBlank(),
            onTogglePullRequestState = {
                scope.launch {
                    isGitHubActionRunning = true
                    val result = withContext(Dispatchers.IO) {
                        toggleProjectGitHubPullRequestDetailState(
                            currentStore = pullRequestDetailStore,
                            repo = repo,
                            token = token,
                            apiBaseUrl = config.getGitHubApiBaseUrl()
                        )
                    }
                    isGitHubActionRunning = false
                    result.feedbackMessage?.let { feedbackMessage = it }
                    result.nextStore?.let { pullRequestDetailStore = it }
                    if (result.shouldRefreshGitHubActions) {
                        refreshGitHubActions()
                    }
                }
            },
            canTogglePullRequestState = repo != null && token.isNotBlank()
        )
    }

    fun submitRemoteFileSave(file: ProjectGitHubRemoteFileUi) {
        scope.launch {
            isGitHubActionRunning = true
            val result = withContext(Dispatchers.IO) {
                saveProjectGitHubRemoteFileEditorAction(
                    currentRepo = githubActionsState.repo,
                    gitRemoteUrl = gitState.remoteUrl,
                    token = config.githubToken.trim(),
                    currentRemoteRef = remoteRepoState.currentRef,
                    defaultBranch = githubActionsState.defaultBranch,
                    file = file,
                    contentDraft = remoteFileContentDraft,
                    commitMessageDraft = remoteFileCommitMessageDraft,
                    apiBaseUrl = config.getGitHubApiBaseUrl(),
                    refreshTargetPath = remoteRepoState.currentPath
                )
            }
            isGitHubActionRunning = false
            feedbackMessage = result.feedbackMessage
            if (result.shouldCloseDialog) {
                remoteFileDialogState = null
                remoteFileContentDraft = ""
                remoteFileCommitMessageDraft = ""
                pendingRemoteFileSaveConfirmation = false
            }
            if (result.shouldRefreshBrowser) {
                if (activeProjectPath.isNullOrBlank()) {
                    refreshStandaloneRemoteRepositoryBrowser(targetPath = result.refreshTargetPath)
                    selectedViewerRepository?.let(::refreshSelectedViewerRepositoryDetail)
                } else {
                    refreshRemoteRepositoryBrowser(targetPath = result.refreshTargetPath)
                }
            }
        }
    }

    remoteFileDialogState?.let { file ->
        val canSubmit = remoteFileCommitMessageDraft.trim().isNotBlank() && !isGitHubActionRunning
        val closeRemoteFileDialog = {
            remoteFileDialogState = null
            remoteFileContentDraft = ""
            remoteFileCommitMessageDraft = ""
            pendingRemoteFileSaveConfirmation = false
        }
        ProjectGitHubRemoteFileDialog(
            file = file,
            contentDraft = remoteFileContentDraft,
            onContentDraftChange = { remoteFileContentDraft = it },
            commitMessageDraft = remoteFileCommitMessageDraft,
            onCommitMessageDraftChange = { remoteFileCommitMessageDraft = it },
            canSubmit = canSubmit,
            onSubmit = {
                val isStandaloneViewerEdit = activeProjectPath.isNullOrBlank()
                val currentRepo = githubActionsState.repo
                if (
                    isStandaloneViewerEdit &&
                    currentRepo != null &&
                    selectedViewerTaskRepository != currentRepo
                ) {
                    pendingRemoteFileSaveConfirmation = true
                } else {
                    submitRemoteFileSave(file)
                }
            },
            onDismiss = closeRemoteFileDialog
        )
    }

    if (pendingRemoteFileSaveConfirmation) {
        val currentRepo = githubActionsState.repo
        val currentFile = remoteFileDialogState
        if (currentRepo != null && currentFile != null) {
            ProjectScreenPopupDialog(
                title = "确认修改其他仓库",
                subtitle = "${currentRepo.owner}/${currentRepo.repo}",
                onDismissRequest = { pendingRemoteFileSaveConfirmation = false },
                actions = {
                    TextButton(onClick = { pendingRemoteFileSaveConfirmation = false }) {
                        Text("取消")
                    }
                    Button(
                        onClick = {
                            pendingRemoteFileSaveConfirmation = false
                            submitRemoteFileSave(currentFile)
                        },
                        enabled = !isGitHubActionRunning
                    ) {
                        Text("确认保存")
                    }
                }
            ) {
                Text(
                    text = "当前仓库不是任务仓库。按照你的规则，这次保存需要先手动确认。",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "文件: ${currentFile.path}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            pendingRemoteFileSaveConfirmation = false
        }
    }

}

private fun normalizeProjectToolPreferences(
    preferences: ProjectToolPreferences
): ProjectToolPreferences? {
    return if (
        preferences.workflowExecutionMode == null &&
        preferences.autoRouteBeforeExecution == null &&
        preferences.failureFallbackMode == null &&
        preferences.approvalMode == null &&
        preferences.enabledBuiltinTools == null &&
        preferences.enabledFileToolOperations == null &&
        preferences.allowAllMcpTools == null &&
        preferences.allowedMcpTools == null &&
        preferences.allowedShellCommandPrefixes == null &&
        preferences.allowedPathPrefixes == null &&
        preferences.subagentTemplates == null
    ) {
        null
    } else {
        preferences
    }
}

private fun mergeProjectGitHubWorkflowDispatchInputs(
    current: List<ProjectGitHubWorkflowDispatchInputUi>,
    parsed: List<ProjectGitHubWorkflowDispatchInputUi>
): List<ProjectGitHubWorkflowDispatchInputUi> {
    val currentByKey = current.associateBy { it.key }
    val mergedParsed = parsed.map { input ->
        val existing = currentByKey[input.key]
        if (existing == null) {
            input
        } else {
            input.copy(
                value = existing.value.ifBlank { input.value }
            )
        }
    }
    val manualInputs = current.filterNot { it.autoDetected }
    return mergedParsed + manualInputs
}

internal enum class ProjectPrimaryTab(val label: String) {
    EDITOR("项目"),
    CONFIG("项目配置"),
    TERMINAL("终端"),
    GIT("Git")
}

private enum class ProjectEditorMode(val label: String) {
    PREVIEW("高亮预览"),
    EDIT("编辑"),
    RAW_JSON("原始 JSON")
}

private data class ProjectGitDiscardTargetUi(
    val repoRoot: String,
    val repoLabel: String,
    val change: ProjectGitFileChangeUi?
)

private data class ProjectRepoWorkingFileItemUi(
    val change: ProjectGitFileChangeUi,
    val canStage: Boolean,
    val canUnstage: Boolean = false
)

@Composable
private fun ProjectGitRepoWorkCard(
    summary: ProjectRepoStatusSummaryUi,
    isSelected: Boolean,
    isExpanded: Boolean,
    mutedTextColor: Color,
    surfaceColor: Color,
    chromeColor: Color,
    isBusy: Boolean,
    onToggleExpanded: () -> Unit,
    onSelectRepo: () -> Unit,
    onOpenDiff: (ProjectGitFileChangeUi) -> Unit,
    onStageFile: (ProjectGitFileChangeUi) -> Unit,
    onUnstageFile: (ProjectGitFileChangeUi) -> Unit,
    onDiscardFile: (ProjectGitFileChangeUi) -> Unit,
    onOpenCommit: () -> Unit,
    onOpenBranches: () -> Unit,
    onStageAll: () -> Unit,
    onPushAll: () -> Unit,
    onDiscardAll: () -> Unit
) {
    val repo = summary.repo
    val status = summary.status
    val workingFiles = remember(status) { buildProjectRepoWorkingFileItems(status) }
    ProjectInsetCard(
        shape = RoundedCornerShape(12.dp),
        surfaceColorOverride = if (isSelected) {
            surfaceColor.copy(alpha = 0.78f)
        } else {
            chromeColor.copy(alpha = 0.30f)
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = if (repo.isWorkspaceRoot) {
                            "${repo.displayName}（工作区根）"
                        } else {
                            repo.relativePath
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = if (status.branchSummary.isNotBlank()) {
                            "分支: ${status.branchSummary}"
                        } else {
                            "分支: 未知"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = mutedTextColor
                    )
                    status.remoteUrl?.takeIf { it.isNotBlank() }?.let { remote ->
                        Text(
                            text = remote,
                            style = MaterialTheme.typography.bodySmall,
                            color = mutedTextColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = "暂存 ${status.stagedFiles.size} · 修改 ${status.modifiedFiles.size} · 未跟踪 ${status.untrackedFiles.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!isSelected) {
                        TextButton(onClick = onSelectRepo, enabled = !isBusy) {
                            Text("切换")
                        }
                    }
                    TextButton(onClick = onToggleExpanded) {
                        Text(if (isExpanded) "收起" else "展开")
                    }
                }
            }
            if (isExpanded) {
                if (workingFiles.isEmpty()) {
                    Text(
                        text = "当前仓库没有待处理改动。",
                        style = MaterialTheme.typography.bodySmall,
                        color = mutedTextColor
                    )
                } else {
                    workingFiles.forEach { item ->
                        ProjectGitRepoWorkFileRow(
                            item = item,
                            mutedTextColor = mutedTextColor,
                            isBusy = isBusy,
                            onOpenDiff = { onOpenDiff(item.change) },
                            onStage = if (item.canStage) {
                                { onStageFile(item.change) }
                            } else {
                                null
                            },
                            onUnstage = if (item.canUnstage) {
                                { onUnstageFile(item.change) }
                            } else {
                                null
                            },
                            onDiscard = { onDiscardFile(item.change) }
                        )
                    }
                }
                HorizontalDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onOpenCommit,
                        enabled = isSelected && status.canCommit && !isBusy
                    ) {
                        Text("提交")
                    }
                    OutlinedButton(
                        onClick = onOpenBranches,
                        enabled = isSelected && !isBusy
                    ) {
                        Text("分支")
                    }
                    OutlinedButton(
                        onClick = onStageAll,
                        enabled = status.canStageAll && !isBusy
                    ) {
                        Text("全部暂存")
                    }
                    OutlinedButton(
                        onClick = onPushAll,
                        enabled = status.canPush && !isBusy
                    ) {
                        Text("全部推送")
                    }
                    OutlinedButton(
                        onClick = onDiscardAll,
                        enabled = status.hasWorkingTreeChanges && !isBusy
                    ) {
                        Text("全部撤回同步云端")
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectGitRepoWorkFileRow(
    item: ProjectRepoWorkingFileItemUi,
    mutedTextColor: Color,
    isBusy: Boolean,
    onOpenDiff: () -> Unit,
    onStage: (() -> Unit)?,
    onUnstage: (() -> Unit)?,
    onDiscard: () -> Unit
) {
    val fileName = projectGitDisplayFileName(item.change.displayPath)
    val filePath = projectGitDisplayFilePath(item.change.displayPath)
    ProjectInsetCard(
        shape = RoundedCornerShape(10.dp),
        surfaceColorOverride = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isBusy, onClick = onOpenDiff)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = fileName,
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = filePath,
                    style = MaterialTheme.typography.labelSmall,
                    color = mutedTextColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = item.change.statusLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onOpenDiff, enabled = !isBusy) {
                    Text("差异")
                }
                onStage?.let {
                    TextButton(onClick = it, enabled = !isBusy) {
                        Text("暂存")
                    }
                }
                onUnstage?.let {
                    TextButton(onClick = it, enabled = !isBusy) {
                        Text("取消暂存")
                    }
                }
                TextButton(onClick = onDiscard, enabled = !isBusy) {
                    Text("撤回")
                }
            }
        }
    }
}

private fun buildProjectRepoWorkingFileItems(
    status: ProjectGitStatusUi
): List<ProjectRepoWorkingFileItemUi> {
    return buildList {
        status.conflictedFiles.forEach {
            add(ProjectRepoWorkingFileItemUi(it, canStage = false, canUnstage = false))
        }
        status.modifiedFiles.forEach {
            add(ProjectRepoWorkingFileItemUi(it, canStage = true, canUnstage = false))
        }
        status.untrackedFiles.forEach {
            add(ProjectRepoWorkingFileItemUi(it, canStage = true, canUnstage = false))
        }
        status.stagedFiles.forEach {
            add(ProjectRepoWorkingFileItemUi(it, canStage = false, canUnstage = true))
        }
    }
}

private fun projectGitDisplayFileName(path: String): String {
    val normalized = path.replace('\\', '/')
    return normalized.substringAfterLast('/', normalized)
}

private fun projectGitDisplayFilePath(path: String): String {
    val normalized = path.replace('\\', '/')
    return normalized.substringBeforeLast('/', "").ifBlank { "." }
}

private enum class ProjectGitRenderedDiffLineType {
    FILE_HEADER,
    META,
    HUNK,
    ADDED,
    REMOVED,
    NOTE,
    CONTEXT
}

private data class ProjectGitRenderedDiffLineUi(
    val text: String,
    val type: ProjectGitRenderedDiffLineType
)

private fun projectGitBuildRenderedDiffLine(line: String): ProjectGitRenderedDiffLineUi {
    val type = when {
        line.startsWith("diff --git ") -> ProjectGitRenderedDiffLineType.FILE_HEADER
        line.startsWith("@@") -> ProjectGitRenderedDiffLineType.HUNK
        line.startsWith("+++") || line.startsWith("---") || line.startsWith("index ") -> ProjectGitRenderedDiffLineType.META
        line.startsWith("+") -> ProjectGitRenderedDiffLineType.ADDED
        line.startsWith("-") -> ProjectGitRenderedDiffLineType.REMOVED
        line.startsWith("\\") -> ProjectGitRenderedDiffLineType.NOTE
        else -> ProjectGitRenderedDiffLineType.CONTEXT
    }
    return ProjectGitRenderedDiffLineUi(text = line, type = type)
}

@Composable
private fun ProjectGitRenderedDiffLine(
    line: ProjectGitRenderedDiffLineUi,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val (textColor, backgroundColor, typography) = when (line.type) {
        ProjectGitRenderedDiffLineType.FILE_HEADER -> Triple(
            colorScheme.primary,
            colorScheme.primary.copy(alpha = 0.10f),
            MaterialTheme.typography.labelMedium
        )
        ProjectGitRenderedDiffLineType.META -> Triple(
            colorScheme.onSurfaceVariant,
            colorScheme.surfaceVariant.copy(alpha = 0.35f),
            MaterialTheme.typography.labelSmall
        )
        ProjectGitRenderedDiffLineType.HUNK -> Triple(
            colorScheme.tertiary,
            colorScheme.tertiary.copy(alpha = 0.12f),
            MaterialTheme.typography.labelSmall
        )
        ProjectGitRenderedDiffLineType.ADDED -> Triple(
            Color(0xFF1B5E20),
            Color(0xFF1B5E20).copy(alpha = 0.10f),
            MaterialTheme.typography.bodySmall
        )
        ProjectGitRenderedDiffLineType.REMOVED -> Triple(
            colorScheme.error,
            colorScheme.error.copy(alpha = 0.10f),
            MaterialTheme.typography.bodySmall
        )
        ProjectGitRenderedDiffLineType.NOTE -> Triple(
            colorScheme.onSurfaceVariant,
            colorScheme.surfaceVariant.copy(alpha = 0.25f),
            MaterialTheme.typography.labelSmall
        )
        ProjectGitRenderedDiffLineType.CONTEXT -> Triple(
            colorScheme.onSurface,
            Color.Transparent,
            MaterialTheme.typography.bodySmall
        )
    }
    Text(
        text = line.text.ifEmpty { " " },
        style = typography.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
        color = textColor,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(backgroundColor)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

private fun isJsonLikeProjectFile(path: String?, language: String? = null): Boolean {
    val normalizedPath = path?.lowercase().orEmpty()
    val normalizedLanguage = language?.lowercase().orEmpty()
    return normalizedPath.endsWith(".json") ||
        normalizedPath.endsWith(".jsonc") ||
        normalizedPath.endsWith(".geojson") ||
        normalizedPath.endsWith(".webmanifest") ||
        normalizedLanguage == "json"
}

private data class ProjectTreeEntry(
    val absolutePath: String,
    val name: String,
    val isDirectory: Boolean
)

private data class VisibleProjectTreeEntry(
    val entry: ProjectTreeEntry,
    val depth: Int
)


private data class ProjectRepoStatusSummaryUi(
    val repo: ProjectDetectedRepoUi,
    val status: ProjectGitStatusUi
)

private fun flattenVisibleEntries(
    rootPath: String,
    entriesByDir: Map<String, List<ProjectTreeEntry>>,
    expandedDirs: Set<String>,
    depth: Int = 1
): List<VisibleProjectTreeEntry> {
    val rootEntries = entriesByDir[rootPath].orEmpty()
    return rootEntries.flatMap { entry ->
        val current = VisibleProjectTreeEntry(entry = entry, depth = depth)
        if (!entry.isDirectory || !expandedDirs.contains(entry.absolutePath)) {
            listOf(current)
        } else {
            listOf(current) + flattenVisibleEntries(
                rootPath = entry.absolutePath,
                entriesByDir = entriesByDir,
                expandedDirs = expandedDirs,
                depth = depth + 1
            )
        }
    }
}

private fun detectProjectGitRepositories(root: File): List<ProjectDetectedRepoUi> {
    val rootPath = root.absolutePath
    val results = linkedMapOf<String, ProjectDetectedRepoMarkerSummary>()
    val pendingDirs = ArrayDeque<File>().apply { add(root) }
    while (pendingDirs.isNotEmpty()) {
        val dir = pendingDirs.removeFirst()
        val dirPath = dir.absolutePath
        val children = runCatching { listProjectEntries(dir) }.getOrElse { emptyList() }
        val markerSummary = detectProjectRepoMarkers(dir, children)
        val hasGitMetadata = hasProjectGitMetadata(dir)
        val isProjectRoot = hasGitMetadata || (dirPath != rootPath && markerSummary.hasProjectRootMarker)
        if (isProjectRoot) {
            results[dirPath] = (results[dirPath] ?: ProjectDetectedRepoMarkerSummary()).merge(
                other = markerSummary,
                hasGitMetadata = hasGitMetadata
            )
        }
        if (hasGitMetadata && dirPath != rootPath) {
            continue
        }
        children.forEach { entry ->
            if (!entry.isDirectory) return@forEach
            val childDir = File(entry.absolutePath)
            if (childDir.name == ".git") {
                childDir.parentFile?.absolutePath?.let { repoRoot ->
                    results[repoRoot] = (results[repoRoot] ?: ProjectDetectedRepoMarkerSummary()).copy(
                        hasGitMetadata = true
                    )
                }
                return@forEach
            }
            if (shouldSkipSearchDir(childDir, root)) return@forEach
            pendingDirs += childDir
        }
    }
    return results
        .map { (repoRoot, markerSummary) ->
            ProjectDetectedRepoUi(
                rootPath = repoRoot,
                displayName = File(repoRoot).name.ifBlank { repoRoot },
                relativePath = if (repoRoot == rootPath) "." else relativeProjectPath(rootPath, repoRoot),
                isWorkspaceRoot = repoRoot == rootPath,
                hasGitMetadata = markerSummary.hasGitMetadata,
                hasReadme = markerSummary.hasReadme,
                hasGradleBuild = markerSummary.hasGradleBuild,
                hasPackageJson = markerSummary.hasPackageJson,
                hasGitHubWorkflows = markerSummary.hasGitHubWorkflows
            )
        }
        .sortedWith(
            compareBy<ProjectDetectedRepoUi> { !it.hasGitMetadata }
                .thenBy { !it.isWorkspaceRoot }
                .thenBy { it.relativePath.lowercase(Locale.getDefault()) }
        )
}

private fun hasProjectGitMetadata(dir: File): Boolean {
    val gitDir = File(dir, ".git")
    return gitDir.exists() || RootFile.exists(gitDir.absolutePath)
}

private data class ProjectDetectedRepoMarkerSummary(
    val hasGitMetadata: Boolean = false,
    val hasReadme: Boolean = false,
    val hasGradleBuild: Boolean = false,
    val hasPackageJson: Boolean = false,
    val hasGitHubWorkflows: Boolean = false
) {
    val hasProjectRootMarker: Boolean
        get() = hasReadme || hasGradleBuild || hasPackageJson || hasGitHubWorkflows

    fun merge(
        other: ProjectDetectedRepoMarkerSummary,
        hasGitMetadata: Boolean = this.hasGitMetadata
    ): ProjectDetectedRepoMarkerSummary {
        return ProjectDetectedRepoMarkerSummary(
            hasGitMetadata = this.hasGitMetadata || hasGitMetadata || other.hasGitMetadata,
            hasReadme = this.hasReadme || other.hasReadme,
            hasGradleBuild = this.hasGradleBuild || other.hasGradleBuild,
            hasPackageJson = this.hasPackageJson || other.hasPackageJson,
            hasGitHubWorkflows = this.hasGitHubWorkflows || other.hasGitHubWorkflows
        )
    }
}

private fun detectProjectRepoMarkers(
    dir: File,
    entries: List<ProjectTreeEntry>
): ProjectDetectedRepoMarkerSummary {
    val names = entries.map { it.name.lowercase(Locale.getDefault()) }.toSet()
    val hasWorkflows = names.contains(".github") && (
        File(dir, ".github/workflows").exists() ||
            RootFile.dirExists(File(dir, ".github/workflows").absolutePath)
        )
    return ProjectDetectedRepoMarkerSummary(
        hasReadme = names.any { it == "readme.md" || it == "readme" },
        hasGradleBuild = names.contains("build.gradle") || names.contains("build.gradle.kts"),
        hasPackageJson = names.contains("package.json"),
        hasGitHubWorkflows = hasWorkflows
    )
}

private fun buildProjectDetectedRepoFeatureLabels(
    repo: ProjectDetectedRepoUi
): List<String> {
    return buildList {
        if (repo.hasGitMetadata) add("Git")
        if (repo.hasReadme) add("README")
        if (repo.hasGradleBuild) add("Gradle")
        if (repo.hasPackageJson) add("package.json")
        if (repo.hasGitHubWorkflows) add("工作流")
    }
}

private fun suggestProjectGitHubRepoDescription(repo: ProjectDetectedRepoUi?): String {
    if (repo == null) return ""
    val featureLabels = buildProjectDetectedRepoFeatureLabels(repo)
        .filterNot { it == "Git" }
    val featureSummary = featureLabels
        .takeIf { it.isNotEmpty() }
        ?.joinToString("、")
        ?.let { "，包含 $it" }
        .orEmpty()
    val scopeSummary = if (repo.relativePath == ".") {
        "，来自当前工作区根目录"
    } else {
        "，目录 ${repo.relativePath}"
    }
    return "${repo.displayName} 项目$featureSummary$scopeSummary"
}

private fun listProjectEntries(dir: File): List<ProjectTreeEntry> {
    val mergedEntries = linkedMapOf<String, ProjectTreeEntry>()
    dir.listFiles()
        ?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase(Locale.getDefault()) }))
        ?.forEach { child ->
            mergedEntries[child.absolutePath] = ProjectTreeEntry(
                absolutePath = child.absolutePath,
                name = child.name,
                isDirectory = child.isDirectory
            )
        }
    if (!dir.isDirectory && !RootFile.dirExists(dir.absolutePath)) {
        error("目录不存在: ${dir.absolutePath}")
    }
    RootFile.ls(dir.absolutePath)
        .mapNotNull { name ->
            val childPath = File(dir, name).absolutePath
            when {
                RootFile.dirExists(childPath) -> ProjectTreeEntry(
                    absolutePath = childPath,
                    name = name,
                    isDirectory = true
                )
                RootFile.fileExists(childPath) -> ProjectTreeEntry(
                    absolutePath = childPath,
                    name = name,
                    isDirectory = false
                )
                else -> null
            }
        }
        .forEach { entry ->
            mergedEntries.putIfAbsent(entry.absolutePath, entry)
        }
    return mergedEntries.values
        .sortedWith(compareBy<ProjectTreeEntry>({ !it.isDirectory }, { it.name.lowercase(Locale.getDefault()) }))
}

private fun readProjectFile(file: File): String {
    if (!shouldUseRootProjectFileAccess(file.absolutePath) && file.exists() && file.isFile) {
        require(file.length() <= MAX_PROJECT_EDITOR_BYTES) {
            "文件过大，暂不在项目页直接打开: ${formatProjectFileSize(file.length())}"
        }
        try {
            return file.readText()
        } catch (_: SecurityException) {
            // Shared storage files may exist but still reject direct app reads.
        } catch (_: java.io.IOException) {
            // Fall back to RootFile for paths that need elevated or alternate access.
        } catch (_: MalformedInputException) {
            error("当前文件不是 UTF-8 文本，暂不支持直接编辑")
        }
    }
    require(RootFile.fileExists(file.absolutePath)) { "文件不存在: ${file.absolutePath}" }
    val content = RootFile.readFile(file.absolutePath)
    if (content.startsWith("error:")) {
        error(content.removePrefix("error:").trim().ifBlank { "文件读取失败: ${file.absolutePath}" })
    }
    val contentBytes = content.toByteArray().size.toLong()
    require(contentBytes <= MAX_PROJECT_EDITOR_BYTES) {
        "文件过大，暂不在项目页直接打开: ${formatProjectFileSize(contentBytes)}"
    }
    return try {
        content
    } catch (_: MalformedInputException) {
        error("当前文件不是 UTF-8 文本，暂不支持直接编辑")
    }
}

private fun saveProjectFile(file: File, content: String) {
    if (!shouldUseRootProjectFileAccess(file.absolutePath) && file.exists() && file.isFile) {
        try {
            file.writeText(content)
            return
        } catch (_: SecurityException) {
            // Fall through to RootFile when app sandbox blocks direct writes.
        } catch (_: java.io.IOException) {
            // Fall through to RootFile when direct writes fail with EACCES.
        }
    }
    val result = RootFile.writeFileChecked(file.absolutePath, content)
    require(result.success) { result.error ?: "文件写入失败: ${file.absolutePath}" }
}

private fun shouldUseRootProjectFileAccess(path: String): Boolean {
    val normalized = path.replace('\\', '/')
    return normalized.startsWith("/storage/")
        || normalized.startsWith("/sdcard/")
        || normalized.startsWith("/mnt/")
}

private fun buildProjectAiCompletionPrompt(
    filePath: String,
    language: String,
    content: String,
    selection: TextRange
): String {
    val before = completionContextBefore(content, selection.min, AI_COMPLETION_CONTEXT_CHARS)
    val after = completionContextAfter(content, selection.max, AI_COMPLETION_CONTEXT_CHARS / 2)
    val selectedText = content.substring(selection.min, selection.max)
    val selectionMode = if (selection.collapsed) {
        "请根据上下文生成应该插入到 <CURSOR> 位置的代码续写。"
    } else {
        "请根据上下文生成应该替换 <SELECTION>...</SELECTION> 区域的更合理代码。"
    }
    return buildString {
        appendLine("文件路径: $filePath")
        appendLine("语言: $language")
        appendLine("要求:")
        appendLine("1. 只返回最终要插入或替换的代码文本。")
        appendLine("2. 不要解释，不要 Markdown，不要代码围栏。")
        appendLine("3. 保持与现有代码风格、缩进、命名一致。")
        appendLine("4. 如果信息不足，就返回尽量短且安全的补全。")
        appendLine(selectionMode)
        appendLine()
        appendLine("<BEFORE>")
        appendLine(before)
        appendLine("</BEFORE>")
        if (!selection.collapsed) {
            appendLine("<SELECTION>")
            appendLine(selectedText)
            appendLine("</SELECTION>")
        }
        appendLine("<AFTER>")
        appendLine(after)
        appendLine("</AFTER>")
        appendLine()
        appendLine("请直接输出补全结果：")
    }
}

private fun completionContextBefore(content: String, cursor: Int, maxChars: Int): String {
    val safeCursor = cursor.coerceIn(0, content.length)
    val start = (safeCursor - maxChars).coerceAtLeast(0)
    return content.substring(start, safeCursor)
}

private fun completionContextAfter(content: String, cursor: Int, maxChars: Int): String {
    val safeCursor = cursor.coerceIn(0, content.length)
    val end = (safeCursor + maxChars).coerceAtMost(content.length)
    return content.substring(safeCursor, end)
}

private fun normalizeAiCompletionText(raw: String?): String {
    val text = raw?.trim().orEmpty()
    if (text.isBlank()) return ""
    if (!text.startsWith("```")) {
        return text.trim('\n', '\r')
    }
    val firstLineBreak = text.indexOf('\n')
    val lastFence = text.lastIndexOf("```")
    if (firstLineBreak < 0 || lastFence <= firstLineBreak) {
        return text.removePrefix("```").removeSuffix("```").trim()
    }
    return text.substring(firstLineBreak + 1, lastFence).trim('\n', '\r')
}

private fun createInitialEditorValue(content: String, focusLine: Int?, focusQuery: String?): TextFieldValue {
    val query = focusQuery?.trim().orEmpty()
    val startOffset = focusLine?.let { lineStartOffset(content, it) } ?: 0
    val match = if (query.isNotBlank()) {
        findNextTextMatch(content, query, startOffset) ?: findNextTextMatch(content, query, 0)
    } else {
        null
    }
    val selection = match?.let { TextRange(it.startIndex, it.endIndex) } ?: TextRange(startOffset)
    return TextFieldValue(text = content, selection = selection)
}

internal fun currentLineNumber(content: String, offset: Int): Int {
    val safeOffset = offset.coerceIn(0, content.length)
    return content.take(safeOffset).count { it == '\n' } + 1
}

internal fun lineStartOffset(content: String, lineNumber: Int): Int {
    if (lineNumber <= 1) return 0
    var currentLine = 1
    content.forEachIndexed { index, char ->
        if (char == '\n') {
            currentLine++
            if (currentLine == lineNumber) {
                return index + 1
            }
        }
    }
    return content.length
}

internal fun projectLineCount(content: String): Int = content.lines().size.coerceAtLeast(1)

private fun lineSelectionForRange(content: String, startLine: Int, endLine: Int): TextRange {
    val lines = content.lines()
    if (lines.isEmpty()) return TextRange(0)
    val safeStart = startLine.coerceIn(1, lines.size)
    val safeEnd = endLine.coerceIn(safeStart, lines.size)
    val startOffset = lineStartOffset(content, safeStart)
    val endOffsetExclusive = if (safeEnd >= lines.size) {
        content.length
    } else {
        lineStartOffset(content, safeEnd + 1) - 1
    }
    return TextRange(startOffset, endOffsetExclusive.coerceAtLeast(startOffset))
}

private fun selectedLineBlock(content: String, selection: TextRange): ProjectSelectedLineBlock {
    val lines = content.lines()
    if (lines.isEmpty()) {
        return ProjectSelectedLineBlock(1, 1, "")
    }
    val startLine = currentLineNumber(content, selection.min)
    val endLookupOffset = if (selection.collapsed) {
        selection.max
    } else {
        (selection.max - 1).coerceAtLeast(selection.min)
    }
    val endLine = currentLineNumber(content, endLookupOffset)
    val selectedLines = lines.subList(
        (startLine - 1).coerceAtLeast(0),
        endLine.coerceAtMost(lines.size)
    )
    return ProjectSelectedLineBlock(
        startLine = startLine,
        endLine = endLine,
        text = selectedLines.joinToString("\n")
    )
}

private fun replaceLines(content: String, lineRange: IntRange, replacementLines: List<String>): String {
    val lines = content.lines().toMutableList()
    if (lines.isEmpty()) {
        return replacementLines.joinToString("\n")
    }
    val safeStart = lineRange.first.coerceIn(1, lines.size)
    val safeEnd = lineRange.last.coerceIn(safeStart, lines.size)
    repeat(safeEnd - safeStart + 1) {
        lines.removeAt(safeStart - 1)
    }
    lines.addAll(safeStart - 1, replacementLines)
    return lines.joinToString("\n")
}

private fun conflictResolutionLines(
    block: ProjectGitConflictBlock,
    resolution: ProjectGitConflictResolution
): List<String> {
    return when (resolution) {
        ProjectGitConflictResolution.KEEP_CURRENT -> block.currentLines
        ProjectGitConflictResolution.KEEP_INCOMING -> block.incomingLines
        ProjectGitConflictResolution.KEEP_BOTH -> block.currentLines + block.incomingLines
    }
}

private fun resolveGitConflictBlock(
    content: String,
    block: ProjectGitConflictBlock,
    resolution: ProjectGitConflictResolution
): String {
    return replaceLines(
        content = content,
        lineRange = block.startLine..block.endLine,
        replacementLines = conflictResolutionLines(block, resolution)
    )
}

private fun insertLinesAfter(content: String, lineNumber: Int, newLines: List<String>): String {
    val lines = content.lines().toMutableList()
    if (lines.isEmpty()) {
        return newLines.joinToString("\n")
    }
    val insertIndex = lineNumber.coerceIn(0, lines.size)
    lines.addAll(insertIndex, newLines)
    return lines.joinToString("\n")
}

private fun toggleLineComment(line: String, language: String?): String {
    val trimmed = line.trimStart()
    val indent = line.take(line.length - trimmed.length)
    val normalizedLanguage = language?.lowercase(Locale.getDefault())
    val (prefix, suffix) = when (normalizedLanguage) {
        "python", "bash", "shell", "sh", "yaml", "toml", "ini", "conf", "properties" -> "# " to ""
        "lua", "sql" -> "-- " to ""
        "html", "xml", "markdown" -> "<!-- " to " -->"
        else -> "// " to ""
    }
    return if (trimmed.startsWith(prefix.trim())) {
        indent + trimmed.removePrefix(prefix).removeSuffix(suffix).trimStart()
    } else {
        indent + prefix + trimmed + suffix
    }
}

private fun findNextTextMatch(content: String, query: String, startIndex: Int): ProjectTextMatchUi? {
    val normalized = query.trim()
    if (normalized.isBlank()) return null
    val safeStart = startIndex.coerceIn(0, content.length)
    val matchIndex = content.indexOf(normalized, safeStart, ignoreCase = true)
    if (matchIndex < 0) return null
    return ProjectTextMatchUi(
        lineNumber = currentLineNumber(content, matchIndex),
        column = matchIndex - lineStartOffset(content, currentLineNumber(content, matchIndex)),
        startIndex = matchIndex,
        endIndex = matchIndex + normalized.length
    )
}

private fun matchesQueryAtRange(content: String, range: TextRange, query: String): Boolean {
    val normalized = query.trim()
    if (normalized.isBlank()) return false
    if (range.min < 0 || range.max > content.length) return false
    if (range.length != normalized.length) return false
    return content.substring(range.min, range.max).equals(normalized, ignoreCase = true)
}

private fun replaceAllMatchesIgnoreCase(content: String, query: String, replacement: String): ProjectReplaceAllResult {
    val normalized = query.trim()
    if (normalized.isBlank()) return ProjectReplaceAllResult(content, 0)
    val builder = StringBuilder()
    var searchIndex = 0
    var count = 0
    while (searchIndex < content.length) {
        val matchIndex = content.indexOf(normalized, searchIndex, ignoreCase = true)
        if (matchIndex < 0) {
            builder.append(content.substring(searchIndex))
            break
        }
        builder.append(content.substring(searchIndex, matchIndex))
        builder.append(replacement)
        count++
        searchIndex = matchIndex + normalized.length
    }
    if (searchIndex == content.length && count > 0) {
        return ProjectReplaceAllResult(builder.toString(), count)
    }
    if (count == 0) {
        return ProjectReplaceAllResult(content, 0)
    }
    return ProjectReplaceAllResult(builder.toString(), count)
}

private fun copyTextToClipboard(context: android.content.Context, text: String) {
    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(null, text))
}

private fun quickInsertCursorOffset(insertText: String): Int {
    return when (insertText) {
        "()", "[]", "{}", "\"\"", "''", "<>" -> 1
        else -> insertText.length
    }
}

private const val MAX_PROJECT_EDITOR_BYTES = 512 * 1024L
internal const val MAX_PROJECT_SEARCH_RESULTS = 60
internal const val MAX_PROJECT_SEARCH_FILE_BYTES = 256 * 1024L
private const val MAX_PROJECT_EDITOR_HISTORY = 80
private const val PROJECT_PREVIEW_FOCUS_RADIUS = 12
private const val AI_COMPLETION_CONTEXT_CHARS = 2400
internal val SEARCH_SKIPPED_DIR_NAMES = setOf(".git", ".gradle", "build", "node_modules", ".idea")
private val PROJECT_ROOT_MARKERS = setOf(
    "settings.gradle",
    "settings.gradle.kts",
    "build.gradle",
    "build.gradle.kts",
    "go.mod",
    "package.json",
    "cargo.toml",
    "pyproject.toml",
    "pom.xml"
)
private val PROJECT_EDITOR_SYMBOL_ACTIONS = listOf(
    ProjectQuickInsertAction(":", ":"),
    ProjectQuickInsertAction("()", "()"),
    ProjectQuickInsertAction("{}", "{}"),
    ProjectQuickInsertAction("[]", "[]"),
    ProjectQuickInsertAction("\"", "\"\""),
    ProjectQuickInsertAction("'", "''"),
    ProjectQuickInsertAction(",", ","),
    ProjectQuickInsertAction(".", "."),
    ProjectQuickInsertAction("=", "="),
    ProjectQuickInsertAction(";", ";"),
    ProjectQuickInsertAction("->", "->"),
    ProjectQuickInsertAction("#", "#"),
    ProjectQuickInsertAction("&", "&"),
    ProjectQuickInsertAction("|", "|")
)
private val PROJECT_EDITOR_JSON_SYMBOL_ACTIONS = listOf(
    ProjectQuickInsertAction("{ }", "{\n  \n}"),
    ProjectQuickInsertAction("[ ]", "[\n  \n]"),
    ProjectQuickInsertAction("\"key\"", "\"key\": "),
    ProjectQuickInsertAction(",", ","),
    ProjectQuickInsertAction("true", "true"),
    ProjectQuickInsertAction("false", "false"),
    ProjectQuickInsertAction("null", "null")
)
private val PROJECT_LANGUAGE_CHOICES = listOf(
    ProjectLanguageOption("自动", null),
    ProjectLanguageOption("Kotlin", "kotlin"),
    ProjectLanguageOption("Java", "java"),
    ProjectLanguageOption("JavaScript", "javascript"),
    ProjectLanguageOption("TypeScript", "typescript"),
    ProjectLanguageOption("Python", "python"),
    ProjectLanguageOption("Shell", "bash"),
    ProjectLanguageOption("Rust", "rust"),
    ProjectLanguageOption("C/C++", "cpp"),
    ProjectLanguageOption("JSON", "json"),
    ProjectLanguageOption("HTML", "html"),
    ProjectLanguageOption("CSS", "css"),
    ProjectLanguageOption("SQL", "sql"),
    ProjectLanguageOption("Markdown", "markdown")
)
private val PROJECT_GIT_COMMIT_TEMPLATES = listOf(
    ProjectGitCommitTemplateUi("功能", "feat: ", "补充本次新增能力、入口和使用范围"),
    ProjectGitCommitTemplateUi("修复", "fix: ", "说明修复的问题、触发场景和回归风险"),
    ProjectGitCommitTemplateUi("重构", "refactor: ", "说明结构调整点和行为是否保持一致"),
    ProjectGitCommitTemplateUi("文档", "docs: ", "说明更新的文档、提示或注释范围")
)

internal data class ProjectSearchHitUi(val filePath: String, val relativePath: String, val lineNumber: Int? = null, val preview: String, val fileType: String)
internal data class ProjectSearchResultUi(val query: String, val scope: ProjectSearchScope, val hits: List<ProjectSearchHitUi>, val totalCount: Int, val truncated: Boolean = false)
internal data class ProjectFocusedPreviewRange(val startLine: Int, val endLine: Int, val excerpt: String)
private data class ProjectTextMatchUi(val lineNumber: Int, val column: Int, val startIndex: Int, val endIndex: Int)
private data class ProjectSelectedLineBlock(val startLine: Int, val endLine: Int, val text: String)
private data class ProjectReplaceAllResult(val content: String, val count: Int)
internal data class ProjectEditorDiagnostic(val lineNumber: Int, val startIndex: Int, val endIndex: Int, val message: String)
internal data class ProjectGitConflictBlock(
    val startLine: Int,
    val dividerLine: Int,
    val endLine: Int,
    val currentLabel: String,
    val incomingLabel: String,
    val currentLines: List<String>,
    val incomingLines: List<String>
)
private data class ProjectGitCommitTemplateUi(
    val label: String,
    val prefix: String,
    val detailHint: String
)
internal data class ProjectCppClassLikeBlock(val kind: String, val startLine: Int, val expectedBraceDepth: Int)
internal data class ProjectFoldRegion(val startLine: Int, val endLine: Int, val headerLine: String)
internal data class ProjectOutlineEntry(val startLine: Int, val lineNumber: Int, val depth: Int, val label: String, val kind: String)
private data class ProjectLanguageOption(val label: String, val value: String?)
internal enum class ProjectSearchScope { ALL, FILE_NAME, TEXT }
internal enum class ProjectLineAction(val label: String) {
    COPY("复制行"),
    CUT("剪切行"),
    DELETE("删除行"),
    CLEAR("清空行"),
    REPLACE("替换行"),
    DUPLICATE("重复行"),
    UPPERCASE("转为大写"),
    LOWERCASE("转为小写"),
    INDENT_MORE("增加缩进"),
    INDENT_LESS("减小缩进"),
    TOGGLE_COMMENT("切换注释")
}
private enum class ProjectGitConflictResolution {
    KEEP_CURRENT,
    KEEP_INCOMING,
    KEEP_BOTH
}

enum class ProjectEditorMenuAction(val label: String) {
    SEARCH_REPLACE("搜索与替换"),
    TOGGLE_WORD_WRAP("自动换行"),
    LANGUAGE("切换语法高亮"),
    DIAGNOSTICS("错误列表"),
    CONFLICTS("处理冲突"),
    OUTLINE("代码大纲"),
    AI_COMPLETION("AI 补全"),
    LINE_COPY(ProjectLineAction.COPY.label),
    LINE_CUT(ProjectLineAction.CUT.label),
    LINE_DELETE(ProjectLineAction.DELETE.label),
    LINE_CLEAR(ProjectLineAction.CLEAR.label),
    LINE_REPLACE(ProjectLineAction.REPLACE.label),
    LINE_DUPLICATE(ProjectLineAction.DUPLICATE.label),
    LINE_UPPERCASE(ProjectLineAction.UPPERCASE.label),
    LINE_LOWERCASE(ProjectLineAction.LOWERCASE.label),
    LINE_INDENT_MORE(ProjectLineAction.INDENT_MORE.label),
    LINE_INDENT_LESS(ProjectLineAction.INDENT_LESS.label),
    LINE_TOGGLE_COMMENT(ProjectLineAction.TOGGLE_COMMENT.label);

    internal fun toLineAction(): ProjectLineAction? {
        return when (this) {
            LINE_COPY -> ProjectLineAction.COPY
            LINE_CUT -> ProjectLineAction.CUT
            LINE_DELETE -> ProjectLineAction.DELETE
            LINE_CLEAR -> ProjectLineAction.CLEAR
            LINE_REPLACE -> ProjectLineAction.REPLACE
            LINE_DUPLICATE -> ProjectLineAction.DUPLICATE
            LINE_UPPERCASE -> ProjectLineAction.UPPERCASE
            LINE_LOWERCASE -> ProjectLineAction.LOWERCASE
            LINE_INDENT_MORE -> ProjectLineAction.INDENT_MORE
            LINE_INDENT_LESS -> ProjectLineAction.INDENT_LESS
            LINE_TOGGLE_COMMENT -> ProjectLineAction.TOGGLE_COMMENT
            else -> null
        }
    }
}



private enum class ProjectSearchResultFilter { ALL, FILE_NAME, TEXT }
private data class ProjectAiAssistResultUi(val summary: String, val details: String)
private data class ProjectAiAssistHistoryEntryUi(val id: String, val timestamp: Long, val summary: String)
private data class ProjectAiPinnedSummaryUi(val id: String, val content: String, val pinnedAt: Long)
private data class ProjectAiActivityGroupUi(val sessionId: String, val title: String, val entries: List<ProjectAiActivityEntryUi>)
private data class ProjectAiActivityEntryUi(val id: String, val action: String, val timestamp: Long)
data class ProjectAiActivityChainUi(val steps: List<String>)
data class ProjectAiActivityChainResultUi(val summary: String, val success: Boolean)
data class ProjectAiRefactorPlanUi(val description: String, val changes: List<String>)
data class ProjectQuickInsertAction(val label: String, val insertText: String)
data class ProjectAiCompletionCandidateUi(val text: String, val score: Float)
data class ProjectSyntaxProfile(val language: String, val keywords: List<String>)
data class ProjectWorkflowTypePreferenceEntry(val type: ProjectWorkflowType, val risk: ProjectWorkflowRiskLevel)
data class ProjectWorkflowFailureTurnEntry(val turn: Int, val fallback: WorkflowFailureFallbackMode)
data class ProjectToolEntry(val name: String, val description: String)
data class ProjectWorkspaceSnapshot(val status: String, val summary: String)
enum class ProjectWorkspaceStatus { READY, LOADING, ERROR }
data class ProjectFileBrowserSnapshot(val currentPath: String?, val entries: List<String>)
enum class ProjectFileBrowserStatus { READY, LOADING, ERROR }
data class ProjectTextReplaceSummaryUi(val id: String)
data class ProjectAiActivityGroupMetricEntry(val label: String, val value: String)
