@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.reasonix.mobile.ui.project

import android.app.DownloadManager
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.reasonix.mobile.core.config.GlobalMemory
import dev.reasonix.mobile.core.config.GlobalRule
import dev.reasonix.mobile.core.config.GlobalSkill
import dev.reasonix.mobile.core.config.ProjectToolPreferences
import dev.reasonix.mobile.core.config.ProjectWorkflowRiskLevel
import dev.reasonix.mobile.core.config.ProjectWorkflowType
import dev.reasonix.mobile.core.config.ProviderConfig
import dev.reasonix.mobile.core.config.ToolApprovalMode
import dev.reasonix.mobile.core.config.WorkflowFailureFallbackMode
import dev.reasonix.mobile.core.loop.ProjectKnowledgeSnapshotUi
import dev.reasonix.mobile.core.loop.RepoScopedProjectConfigUi
import dev.reasonix.mobile.core.loop.SessionSummary
import dev.reasonix.mobile.core.provider.ChatMessage
import dev.reasonix.mobile.core.provider.ChatRequest
import dev.reasonix.mobile.core.provider.ProviderRegistry
import dev.reasonix.mobile.common.shell.KeepShellPublic
import dev.reasonix.mobile.common.utils.RootFile
import dev.reasonix.mobile.ui.ProjectSecondaryChromeState
import dev.reasonix.mobile.ui.ReasonixAlertDialog
import dev.reasonix.mobile.ui.ReasonixGlassSurface
import dev.reasonix.mobile.ui.ReasonixInfoCard
import dev.reasonix.mobile.ui.ReasonixSecondaryPageFrame
import dev.reasonix.mobile.ui.ReasonixSecondaryPageSurface
import dev.reasonix.mobile.ui.ReasonixTagButton
import dev.reasonix.mobile.ui.SkillDraftImportCard
import dev.reasonix.mobile.ui.highlightSyntax
import dev.reasonix.mobile.ui.rememberReasonixAccentColor
import dev.reasonix.mobile.ui.rememberReasonixChromeColor
import dev.reasonix.mobile.ui.rememberReasonixMutedTextColor
import dev.reasonix.mobile.ui.rememberReasonixSurfaceColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.put
import java.io.File
import java.nio.charset.MalformedInputException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

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
    repoScopedConfigs: Map<String, RepoScopedProjectConfigUi>,
    mcpToolNames: List<String>,
    sessions: List<SessionSummary>,
    onNewTask: () -> Unit,
    onOpenChat: () -> Unit,
    onProjectScopeChanged: (String?) -> Unit,
    onUpdateProjectConfig: (String?, List<GlobalRule>?, List<GlobalMemory>?, List<GlobalSkill>?) -> Unit,
    onUpdateProjectToolPreferences: (String?, ProjectToolPreferences?) -> Unit,
    onUpdateProjectKnowledgeDraftPaths: (List<String>) -> Unit,
    onSaveProjectKnowledgeSnapshot: (String, List<String>) -> Unit,
    onRenameProjectKnowledgeSnapshot: (String, String) -> Unit,
    onApplyProjectKnowledgeSnapshot: (String) -> Unit,
    onDeleteProjectKnowledgeSnapshot: (String) -> Unit,
    onProjectSecondaryPageChanged: (ProjectSecondaryChromeState) -> Unit = {},
    closeProjectSecondaryPageRequestSignal: Int = 0,
    projectSecondaryBackProgress: Float = 0f,
    editorMenuActionSignal: Int = 0,
    editorMenuAction: ProjectEditorMenuAction? = null
) {
    val workspaceRoot = remember(currentProjectPath) {
        currentProjectPath
            ?.takeIf { File(it).isDirectory || RootFile.dirExists(it) }
            ?.let(::File)
    }
    val detectedRepos = remember(workspaceRoot?.absolutePath) {
        workspaceRoot?.let(::detectProjectGitRepositories).orEmpty()
    }
    var selectedRepoRoot by remember(currentProjectPath) { mutableStateOf<String?>(null) }
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
    var selectedTab by remember(currentProjectPath) { mutableStateOf(ProjectPrimaryTab.EDITOR) }
    var editorPageChromeState by remember(currentProjectPath) { mutableStateOf(ProjectSecondaryChromeState()) }
    var gitPageChromeState by remember(currentProjectPath) { mutableStateOf(ProjectSecondaryChromeState()) }
    val activeProjectSecondaryState = when {
        selectedTab == ProjectPrimaryTab.EDITOR && editorPageChromeState.active -> editorPageChromeState
        selectedTab == ProjectPrimaryTab.GIT && gitPageChromeState.active -> gitPageChromeState
        else -> ProjectSecondaryChromeState()
    }
    val projectPagerState = rememberPagerState(
        initialPage = selectedTab.ordinal,
        pageCount = { ProjectPrimaryTab.entries.size }
    )
    val chromeColor = rememberReasonixChromeColor()
    val mutedTextColor = rememberReasonixMutedTextColor()
    val activeConfigScopePath = selectedRepoRoot ?: currentProjectPath
    val activeRepoScopedConfig = remember(
        activeConfigScopePath,
        repoScopedConfigs,
        projectRules,
        projectMemories,
        projectSkills,
        projectToolPreferences
    ) {
        activeConfigScopePath?.let(repoScopedConfigs::get)?.let { scoped ->
            RepoScopedProjectConfigUi(
                projectRules = scoped.projectRules,
                projectMemories = scoped.projectMemories,
                projectSkills = scoped.projectSkills,
                projectToolPreferences = scoped.projectToolPreferences
            )
        } ?: RepoScopedProjectConfigUi(
            projectRules = projectRules,
            projectMemories = projectMemories,
            projectSkills = projectSkills,
            projectToolPreferences = projectToolPreferences
        )
    }
    LaunchedEffect(selectedTab) {
        if (projectPagerState.currentPage != selectedTab.ordinal) {
            projectPagerState.animateScrollToPage(selectedTab.ordinal)
        }
    }
    LaunchedEffect(projectPagerState.settledPage) {
        val settledTab = ProjectPrimaryTab.entries[projectPagerState.settledPage]
        if (selectedTab != settledTab) {
            selectedTab = settledTab
        }
    }
    LaunchedEffect(activeProjectSecondaryState) {
        onProjectSecondaryPageChanged(activeProjectSecondaryState)
    }
    ReasonixSecondaryPageSurface(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            ReasonixGlassSurface(
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
                        if (detectedRepos.isNotEmpty()) {
                            val gitRepoCount = detectedRepos.count { it.hasGitMetadata }
                            Text(
                                text = "已识别 ${detectedRepos.size} 个项目根目录，其中 $gitRepoCount 个是 Git 仓库",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    OutlinedButton(onClick = onNewTask) {
                        Text("选择文件夹")
                    }
                }
            }
            if (!activeProjectSecondaryState.active) {
                PrimaryScrollableTabRow(
                    selectedTabIndex = selectedTab.ordinal,
                    containerColor = chromeColor.copy(alpha = 0.32f),
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    divider = {}
                ) {
                    ProjectPrimaryTab.entries.forEach { tab ->
                        Tab(
                            selected = selectedTab == tab,
                            onClick = { selectedTab = tab },
                            selectedContentColor = MaterialTheme.colorScheme.onSurface,
                            unselectedContentColor = mutedTextColor,
                            text = { Text(tab.label) }
                        )
                    }
                }
            }
            HorizontalPager(
                state = projectPagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                userScrollEnabled = !activeProjectSecondaryState.active
            ) { page ->
                when (ProjectPrimaryTab.entries[page]) {
                    ProjectPrimaryTab.EDITOR -> ProjectEditorSection(
                        config = config,
                        currentProjectPath = currentProjectPath,
                        detectedRepos = detectedRepos,
                        selectedRepoRoot = selectedRepoRoot,
                        onSelectRepoRoot = ::selectRepoRoot,
                        sessions = sessions,
                        onOpenChat = onOpenChat,
                        onNewTask = onNewTask,
                        onProjectSecondaryPageChanged = { editorPageChromeState = it },
                        closeProjectSecondaryPageRequestSignal = closeProjectSecondaryPageRequestSignal,
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
                        projectRules = activeRepoScopedConfig.projectRules,
                        projectMemories = activeRepoScopedConfig.projectMemories,
                        projectSkills = activeRepoScopedConfig.projectSkills,
                        projectToolPreferences = activeRepoScopedConfig.projectToolPreferences,
                        onUpdateProjectConfig = { rules, memories, skills ->
                            onUpdateProjectConfig(activeConfigScopePath, rules, memories, skills)
                        },
                        onUpdateProjectToolPreferences = { preferences ->
                            onUpdateProjectToolPreferences(activeConfigScopePath, preferences)
                        }
                    )

                    ProjectPrimaryTab.GIT -> ProjectGitSection(
                        config = config,
                        currentProjectPath = currentProjectPath,
                        detectedRepos = detectedRepos,
                        selectedRepoRoot = selectedRepoRoot,
                        onSelectRepoRoot = ::selectRepoRoot,
                        draftPathCount = projectKnowledgeDraftPaths.size,
                        snapshotCount = projectKnowledgeSnapshots.size,
                        mcpToolCount = mcpToolNames.size,
                        onProjectSecondaryPageChanged = { gitPageChromeState = it },
                        closeProjectSecondaryPageRequestSignal = closeProjectSecondaryPageRequestSignal,
                        projectSecondaryBackProgress = projectSecondaryBackProgress
                    )
                }
            }
        }
    }
}

@Composable
private fun ProjectEditorSection(
    config: ProviderConfig,
    currentProjectPath: String?,
    detectedRepos: List<ProjectDetectedRepoUi>,
    selectedRepoRoot: String?,
    onSelectRepoRoot: (String) -> Unit,
    sessions: List<SessionSummary>,
    onOpenChat: () -> Unit,
    onNewTask: () -> Unit,
    onProjectSecondaryPageChanged: (ProjectSecondaryChromeState) -> Unit,
    closeProjectSecondaryPageRequestSignal: Int,
    editorMenuActionSignal: Int,
    editorMenuAction: ProjectEditorMenuAction?
) {
    val scope = rememberCoroutineScope()
    val editorSurfaceColor = rememberReasonixSurfaceColor()
    val editorChromeColor = rememberReasonixChromeColor()
    val editorMutedTextColor = rememberReasonixMutedTextColor()
    val editorBackgroundColor = MaterialTheme.colorScheme.background.copy(alpha = 0.96f)
    val projectRoot = remember(currentProjectPath) {
        currentProjectPath
            ?.takeIf { File(it).isDirectory || RootFile.dirExists(it) }
            ?.let(::File)
    }
    val detectedRepoRoots = remember(detectedRepos) { detectedRepos.map { it.rootPath }.toSet() }
    val entriesByDir = remember(currentProjectPath) { mutableStateMapOf<String, List<ProjectTreeEntry>>() }
    var currentRepoViewOnly by remember(currentProjectPath) { mutableStateOf(false) }
    var expandedDirs by remember(currentProjectPath) {
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
    var reloadVersion by remember(currentProjectPath) { mutableStateOf(0) }
    var searchQuery by remember(currentProjectPath) { mutableStateOf("") }
    var searchResult by remember(currentProjectPath) { mutableStateOf<ProjectSearchResultUi?>(null) }
    var isSearching by remember(currentProjectPath) { mutableStateOf(false) }
    var focusedSearchLine by remember(currentProjectPath) { mutableStateOf<Int?>(null) }
    var focusedSearchQuery by remember(currentProjectPath) { mutableStateOf<String?>(null) }
    var currentSearchMatch by remember(currentProjectPath) { mutableStateOf<TextRange?>(null) }
    var editorSearchQuery by remember(currentProjectPath) { mutableStateOf("") }
    var editorReplaceQuery by remember(currentProjectPath) { mutableStateOf("") }
    var showSearchReplaceDialog by remember(currentProjectPath) { mutableStateOf(false) }
    var showLineReplaceDialog by remember(currentProjectPath) { mutableStateOf(false) }
    var lineReplaceDraft by remember(currentProjectPath) { mutableStateOf("") }
    var showLanguageDialog by remember(currentProjectPath) { mutableStateOf(false) }
    var showOutlineDialog by remember(currentProjectPath) { mutableStateOf(false) }
    var showDiagnosticsDialog by remember(currentProjectPath) { mutableStateOf(false) }
    var showConflictResolverDialog by remember(currentProjectPath) { mutableStateOf(false) }
    var languageOverride by remember(currentProjectPath) { mutableStateOf<String?>(null) }
    var isAiCompleting by remember(currentProjectPath) { mutableStateOf(false) }
    var showAiCompletionDialog by remember(currentProjectPath) { mutableStateOf(false) }
    var aiCompletionCandidate by remember(currentProjectPath) { mutableStateOf<ProjectAiCompletionCandidateUi?>(null) }
    val collapsedFoldRegions = remember(currentProjectPath, selectedFilePath) { mutableStateMapOf<Int, Boolean>() }
    val undoStack = remember(currentProjectPath) { mutableStateListOf<TextFieldValue>() }
    val redoStack = remember(currentProjectPath) { mutableStateListOf<TextFieldValue>() }
    val context = LocalContext.current
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
            ensurePathExpanded(path)
            val result = withContext(Dispatchers.IO) {
                runCatching { readProjectFile(File(path)) }
            }
            result
                .onSuccess { content ->
                    val initialValue = createInitialEditorValue(content, focusLine, focusQuery)
                    selectedFilePath = path
                    loadedContent = content
                    editorValue = initialValue
                    aiCompletionCandidate = null
                    showAiCompletionDialog = false
                    editorMode = if (isJsonLikeProjectFile(path)) {
                        ProjectEditorMode.RAW_JSON
                    } else {
                        ProjectEditorMode.EDIT
                    }
                    focusedSearchLine = focusLine ?: currentLineNumber(content, initialValue.selection.start)
                    focusedSearchQuery = focusQuery?.takeIf { it.isNotBlank() }
                    currentSearchMatch = initialValue.selection.takeIf { !it.collapsed }
                    undoStack.clear()
                    redoStack.clear()
                }
                .onFailure { error ->
                    selectedFilePath = path
                    loadedContent = ""
                    editorValue = TextFieldValue("")
                    aiCompletionCandidate = null
                    showAiCompletionDialog = false
                    focusedSearchLine = null
                    focusedSearchQuery = null
                    currentSearchMatch = null
                    undoStack.clear()
                    redoStack.clear()
                    editorError = error.message ?: "文件读取失败"
                }
            isFileLoading = false
        }
    }

    fun updateEditorValue(newValue: TextFieldValue, recordUndo: Boolean = true) {
        if (newValue == editorValue) return
        val previousValue = editorValue
        val textChanged = newValue.text != previousValue.text
        if (recordUndo && textChanged) {
            if (undoStack.lastOrNull() != previousValue) {
                undoStack.add(previousValue)
                if (undoStack.size > MAX_PROJECT_EDITOR_HISTORY) {
                    undoStack.removeAt(0)
                }
            }
            redoStack.clear()
        }
        if (textChanged) {
            currentSearchMatch = null
        } else if (currentSearchMatch != null && newValue.selection != currentSearchMatch) {
            currentSearchMatch = null
        }
        editorValue = newValue
        focusedSearchLine = currentLineNumber(newValue.text, newValue.selection.start)
    }

    fun jumpToDiagnostic(diagnostic: ProjectEditorDiagnostic) {
        val safeRange = TextRange(
            diagnostic.startIndex.coerceIn(0, editorValue.text.length),
            diagnostic.endIndex.coerceIn(0, editorValue.text.length).coerceAtLeast(diagnostic.startIndex.coerceIn(0, editorValue.text.length))
        )
        focusedSearchLine = diagnostic.lineNumber
        currentSearchMatch = safeRange.takeIf { !it.collapsed }
        updateEditorValue(
            editorValue.copy(selection = if (safeRange.collapsed) TextRange(safeRange.start) else safeRange),
            recordUndo = false
        )
        showDiagnosticsDialog = false
    }

    fun jumpToConflictBlock(block: ProjectGitConflictBlock) {
        val safeRange = lineSelectionForRange(editorValue.text, block.startLine, block.endLine)
        focusedSearchLine = block.startLine
        currentSearchMatch = safeRange.takeIf { !it.collapsed }
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
        currentSearchMatch = null
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
        currentSearchMatch = null
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
        currentSearchMatch = null
        focusedSearchLine = lineRange.first
    }

    fun findNextInEditor() {
        val query = editorSearchQuery.trim()
        if (query.isBlank()) {
            editorError = "请输入搜索内容"
            return
        }
        val searchStart = if (currentSearchMatch != null) {
            currentSearchMatch!!.end.coerceAtMost(editorValue.text.length)
        } else {
            editorValue.selection.max.coerceAtMost(editorValue.text.length)
        }
        val match = findNextTextMatch(editorValue.text, query, searchStart)
            ?: findNextTextMatch(editorValue.text, query, 0)
        if (match == null) {
            editorError = "没有找到匹配内容"
            return
        }
        focusedSearchLine = match.lineNumber
        focusedSearchQuery = query
        currentSearchMatch = TextRange(match.startIndex, match.endIndex)
        editorMode = ProjectEditorMode.EDIT
        updateEditorValue(
            editorValue.copy(selection = currentSearchMatch!!),
            recordUndo = false
        )
    }

    fun replaceCurrentMatch() {
        val query = editorSearchQuery.trim()
        if (query.isBlank()) {
            editorError = "请输入搜索内容"
            return
        }
        val range = currentSearchMatch?.takeIf {
            matchesQueryAtRange(editorValue.text, it, query)
        } ?: findNextTextMatch(editorValue.text, query, editorValue.selection.min)?.let {
            TextRange(it.startIndex, it.endIndex)
        }
        if (range == null) {
            editorError = "没有可替换的匹配"
            return
        }
        val newContent = editorValue.text.replaceRange(range.start, range.end, editorReplaceQuery)
        val nextSearchStart = (range.start + editorReplaceQuery.length).coerceAtMost(newContent.length)
        val nextMatch = findNextTextMatch(newContent, query, nextSearchStart)
            ?: findNextTextMatch(newContent, query, 0)
        currentSearchMatch = nextMatch?.let { TextRange(it.startIndex, it.endIndex) }
        updateEditorValue(
            editorValue.copy(
                text = newContent,
                selection = currentSearchMatch ?: TextRange(nextSearchStart)
            )
        )
        focusedSearchQuery = query
        editorMode = ProjectEditorMode.EDIT
    }

    fun jumpToOutlineEntry(entry: ProjectOutlineEntry) {
        val offset = lineStartOffset(editorValue.text, entry.lineNumber)
        collapsedFoldRegions.remove(entry.startLine)
        focusedSearchLine = entry.lineNumber
        focusedSearchQuery = null
        currentSearchMatch = null
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
        currentSearchMatch = null
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
            currentSearchMatch = null
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
        val result = replaceAllMatchesIgnoreCase(editorValue.text, query, editorReplaceQuery)
        if (result.count == 0) {
            editorError = "没有可替换的匹配"
            return
        }
        currentSearchMatch = null
        updateEditorValue(editorValue.copy(text = result.content, selection = TextRange(0)))
        focusedSearchQuery = query
        editorMode = ProjectEditorMode.EDIT
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
        val root = activeTreeRoot ?: return
        if (query.isBlank()) {
            searchResult = null
            return
        }
        scope.launch {
            isSearching = true
            editorError = null
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    searchProjectEntries(
                        root = root,
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
    val editorDiagnostics = remember(editedContent, language) {
        buildProjectEditorDiagnostics(editedContent, language)
    }
    val conflictBlocks = remember(editedContent) {
        detectGitConflictBlocks(editedContent)
    }
    val foldRegions = remember(editedContent, language) {
        detectProjectFoldRegions(editedContent, language)
    }
    val outlineEntries = remember(foldRegions, language) {
        buildProjectOutlineEntries(foldRegions, language)
    }
    val currentLine = currentLineNumber(editedContent, editorValue.selection.start)
    val currentLineDiagnostic = remember(editorDiagnostics, currentLine) {
        editorDiagnostics.firstOrNull { it.lineNumber == currentLine }
    }
    val currentFoldRegion = remember(foldRegions, currentLine) {
        foldRegions.lastOrNull { currentLine in it.startLine..it.endLine }
    }
    val currentOutlineEntry = remember(currentFoldRegion, outlineEntries) {
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

    if (projectRoot == null) {
        EmptyEditorState(
            title = "未选择可编辑项目",
            message = currentProjectPath?.let {
                "当前项目路径不是可访问的文件夹：$it"
            } ?: "先选择一个项目文件夹，项目页才会显示文件树和编辑器。",
            actionLabel = "选择项目文件夹",
            onAction = onNewTask
        )
        return
    }

    val treeRootPath = activeTreeRootPath ?: projectRoot.absolutePath
    val treeRoot = activeTreeRoot ?: projectRoot
    val visibleEntries = flattenVisibleEntries(
        rootPath = treeRootPath,
        entriesByDir = entriesByDir,
        expandedDirs = expandedDirs
    )
    val currentFileName = selectedFilePath?.substringAfterLast(File.separatorChar).orEmpty()
    val currentRelativePath = selectedFilePath
        ?.removePrefix(projectRoot.absolutePath)
        ?.removePrefix(File.separator)
        .orEmpty()

    LaunchedEffect(selectedFilePath, currentFileName, currentRelativePath) {
        onProjectSecondaryPageChanged(
            if (selectedFilePath != null) {
                ProjectSecondaryChromeState(
                    active = true,
                    title = currentFileName,
                    subtitle = currentRelativePath,
                    supportsEditorMenu = true
                )
            } else {
                ProjectSecondaryChromeState()
            }
        )
    }

    fun exitEditorPage() {
        selectedFilePath = null
        focusedSearchLine = null
        focusedSearchQuery = null
        aiCompletionCandidate = null
        showAiCompletionDialog = false
    }

    LaunchedEffect(closeProjectSecondaryPageRequestSignal) {
        if (closeProjectSecondaryPageRequestSignal != 0 && selectedFilePath != null) {
            exitEditorPage()
        }
    }

    BackHandler(enabled = selectedFilePath != null) {
        exitEditorPage()
    }

    AnimatedContent(
        targetState = selectedFilePath != null,
        transitionSpec = {
            if (targetState) {
                slideInHorizontally { fullWidth -> fullWidth } + fadeIn() togetherWith
                    slideOutHorizontally { fullWidth -> -fullWidth / 4 } + fadeOut()
            } else {
                slideInHorizontally { fullWidth -> -fullWidth / 4 } + fadeIn() togetherWith
                    slideOutHorizontally { fullWidth -> fullWidth } + fadeOut()
            }
        },
        label = "projectEditorSecondaryPage"
    ) { showEditorPage ->
    if (!showEditorPage) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 132.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ReasonixGlassSurface(
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
                        onValueChange = { searchQuery = it },
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
                                        text = if (currentRepoViewOnly) "搜索当前仓库文件名" else "搜索工作区文件名",
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
                if (detectedRepos.isNotEmpty()) {
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

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    item(key = "status") {
                        when {
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
                    if (searchQuery.isNotBlank()) {
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
                                    absolutePath = treeRoot.absolutePath,
                                    name = treeRoot.name.ifBlank { treeRoot.absolutePath },
                                    isDirectory = true
                                ),
                                rootPath = treeRootPath,
                                depth = 0,
                                isExpanded = true,
                                isSelected = false,
                                repoBadge = if (treeRoot.absolutePath in detectedRepoRoots) {
                                    if (treeRoot.absolutePath == selectedRepoRoot) "Git 仓库 · 当前" else "Git 仓库"
                                } else null,
                                onToggleDir = {},
                                onSelectFile = {}
                            )
                        }
                        if (visibleEntries.isEmpty() && !isTreeLoading) {
                            item(key = "tree-empty") {
                                Text(
                                    "当前目录没有可显示的文件或文件夹。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        items(visibleEntries, key = { it.entry.absolutePath }) { item ->
                            ProjectTreeRow(
                                entry = item.entry,
                                rootPath = treeRootPath,
                                depth = item.depth,
                                isExpanded = item.entry.isDirectory && expandedDirs.contains(item.entry.absolutePath),
                                isSelected = false,
                                repoBadge = if (item.entry.absolutePath in detectedRepoRoots) {
                                    if (item.entry.absolutePath == selectedRepoRoot) "Git 仓库 · 当前" else "Git 仓库"
                                } else null,
                                onToggleDir = { path ->
                                    expandedDirs = if (expandedDirs.contains(path)) {
                                        expandedDirs - path
                                    } else {
                                        if (!entriesByDir.containsKey(path)) loadDir(path)
                                        expandedDirs + path
                                    }
                                },
                                onSelectFile = { path -> openFile(path) }
                            )
                        }
                    }
                }
            }
        }
    } else {
        val editorVerticalScroll = rememberScrollState()
        val isRawJsonFile = remember(selectedFilePath, language) {
            isJsonLikeProjectFile(selectedFilePath, language)
        }
        val editorLanguageLabel = remember(language, languageOverride, isRawJsonFile) {
            when {
                isRawJsonFile -> "json"
                !languageOverride.isNullOrBlank() -> languageOverride!!
                !language.isNullOrBlank() -> language!!
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
            if (selectedFilePath != null && editorMenuActionSignal != 0) {
                editorMenuAction?.let(::handleEditorMenuAction)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ReasonixGlassSurface(
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
                    ReasonixTagButton(text = editorLanguageLabel, onClick = {})
                    IconButton(
                        onClick = { navigateEditorHistory(backward = true) },
                        enabled = undoStack.isNotEmpty()
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ArrowBack,
                            contentDescription = "后退"
                        )
                    }
                    IconButton(
                        onClick = { navigateEditorHistory(backward = false) },
                        enabled = redoStack.isNotEmpty()
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ArrowForward,
                            contentDescription = "前进"
                        )
                    }
                    Button(
                        onClick = {
                            scope.launch {
                                isSaving = true
                                editorError = null
                                val result = withContext(Dispatchers.IO) {
                                    runCatching { saveProjectFile(File(selectedFilePath!!), editedContent) }
                                }
                                result
                                    .onSuccess { loadedContent = editedContent }
                                    .onFailure { error -> editorError = error.message ?: "保存失败" }
                                isSaving = false
                            }
                        },
                        enabled = dirty && !isSaving
                    ) {
                        Text(if (isSaving) "保存中" else "保存")
                    }
                    Spacer(modifier = Modifier.weight(1f))
                }
            }

            editorError?.let {
                val chromeColor = rememberReasonixChromeColor()
                ReasonixGlassSurface(
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

            ReasonixGlassSurface(
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
                    } else if (editorMode == ProjectEditorMode.RAW_JSON && isRawJsonFile) {
                        OutlinedTextField(
                            value = editorValue,
                            onValueChange = { updateEditorValue(it) },
                            modifier = Modifier.fillMaxSize(),
                            textStyle = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 18.sp
                            ),
                            shape = RoundedCornerShape(18.dp)
                        )
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
                                searchQuery = editorSearchQuery,
                                currentMatch = currentSearchMatch,
                                diagnostics = editorDiagnostics,
                                verticalScrollState = editorVerticalScroll,
                                backgroundColor = editorBackgroundColor,
                                surfaceColor = editorSurfaceColor,
                                mutedTextColor = editorMutedTextColor,
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
                    ReasonixTagButton(
                        text = action.label,
                        onClick = { insertQuickAction(action) }
                    )
                }
            }
        }
    }
    }

    if (showSearchReplaceDialog) {
        ReasonixAlertDialog(
            onDismissRequest = { showSearchReplaceDialog = false },
            title = { Text("搜索与替换") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            },
            confirmButton = {
                TextButton(onClick = { replaceAllMatches() }) { Text("全部替换") }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { replaceCurrentMatch() }) { Text("替换当前") }
                    TextButton(onClick = { findNextInEditor() }) { Text("查找下一处") }
                    TextButton(onClick = { showSearchReplaceDialog = false }) { Text("关闭") }
                }
            }
        )
    }

    if (showLineReplaceDialog) {
        ReasonixAlertDialog(
            onDismissRequest = { showLineReplaceDialog = false },
            title = { Text("替换当前行") },
            text = {
                OutlinedTextField(
                    value = lineReplaceDraft,
                    onValueChange = { lineReplaceDraft = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("新行内容") }
                )
            },
            confirmButton = {
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
            },
            dismissButton = {
                TextButton(onClick = { showLineReplaceDialog = false }) { Text("取消") }
            }
        )
    }

    if (showAiCompletionDialog) {
        val candidate = aiCompletionCandidate
        ReasonixAlertDialog(
            onDismissRequest = { showAiCompletionDialog = false },
            title = { Text("AI 自动补全") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            },
            confirmButton = {
                TextButton(
                    onClick = { applyAiCompletionCandidate() },
                    enabled = candidate != null
                ) {
                    Text(if (editorValue.selection.collapsed) "插入" else "替换")
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = { requestAiCompletion() },
                        enabled = !isAiCompleting
                    ) {
                        Text(if (isAiCompleting) "生成中" else "重新生成")
                    }
                    TextButton(onClick = { showAiCompletionDialog = false }) {
                        Text("关闭")
                    }
                }
            }
        )
    }

    if (showOutlineDialog) {
        ReasonixAlertDialog(
            onDismissRequest = { showOutlineDialog = false },
            title = { Text("代码大纲") },
            text = {
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
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showOutlineDialog = false }) { Text("关闭") }
            }
        )
    }

    if (showLanguageDialog) {
        ReasonixAlertDialog(
            onDismissRequest = { showLanguageDialog = false },
            title = { Text("切换语法高亮") },
            text = {
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
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showLanguageDialog = false }) { Text("关闭") }
            }
        )
    }

    if (showDiagnosticsDialog) {
        ReasonixAlertDialog(
            onDismissRequest = { showDiagnosticsDialog = false },
            title = { Text("错误列表") },
            text = {
                val chromeColor = rememberReasonixChromeColor()
                val mutedTextColor = rememberReasonixMutedTextColor()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
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
            },
            confirmButton = {
                TextButton(onClick = { showDiagnosticsDialog = false }) { Text("关闭") }
            }
        )
    }

    if (showConflictResolverDialog) {
        ReasonixAlertDialog(
            onDismissRequest = { showConflictResolverDialog = false },
            title = {
                Text(
                    if (currentFileName.isBlank()) {
                        "处理 Git 冲突"
                    } else {
                        "处理 Git 冲突: $currentFileName"
                    }
                )
            },
            text = {
                val surfaceColor = rememberReasonixSurfaceColor()
                val mutedTextColor = rememberReasonixMutedTextColor()
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
            },
            confirmButton = {
                TextButton(onClick = { showConflictResolverDialog = false }) { Text("关闭") }
            }
        )
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
    val accent = rememberReasonixAccentColor()
    val surfaceColor = rememberReasonixSurfaceColor()
    val mutedTextColor = rememberReasonixMutedTextColor()
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
    var approvalMode by remember(projectToolPreferences) {
        mutableStateOf(projectToolPreferences?.approvalMode ?: config.approvalMode)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
            .padding(bottom = 168.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        ReasonixInfoCard(title = "", titleVisible = false) {
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
            ToolApprovalMode.entries.forEach { mode ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            approvalMode = mode
                            onUpdateProjectToolPreferences(
                                (projectToolPreferences ?: ProjectToolPreferences()).copy(approvalMode = mode)
                            )
                        }
                        .padding(vertical = 4.dp)
                ) {
                    RadioButton(
                        selected = approvalMode == mode,
                        onClick = {
                            approvalMode = mode
                            onUpdateProjectToolPreferences(
                                (projectToolPreferences ?: ProjectToolPreferences()).copy(approvalMode = mode)
                            )
                        }
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(approvalModeLabel(mode), style = MaterialTheme.typography.bodyMedium)
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
    val items = remember(rules) { rules.toMutableList() }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEach { rule ->
            ProjectInsetCard {
                Column(Modifier.padding(8.dp)) {
                    Text(
                        rule.title.ifBlank { "未命名规则" },
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        rule.content.take(80),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        TextButton(onClick = {
            val updated = items + GlobalRule(
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
    val items = remember(memories) { memories.toMutableList() }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEach { memory ->
            ProjectInsetCard {
                Column(Modifier.padding(8.dp)) {
                    Text(
                        memory.title.ifBlank { "未命名记忆" },
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        memory.content.take(80),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        TextButton(onClick = {
            val updated = items + GlobalMemory(
                id = UUID.randomUUID().toString().take(8),
                title = "新记忆",
                content = ""
            )
            onMemoriesChanged(updated)
        }) { Text("+ 添加记忆") }
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
    shape: RoundedCornerShape = RoundedCornerShape(18.dp),
    surfaceColorOverride: Color? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    ReasonixGlassSurface(
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
    shape: RoundedCornerShape = RoundedCornerShape(12.dp),
    surfaceColorOverride: Color? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val fallback = rememberReasonixSurfaceColor().copy(alpha = 0.42f)
    ReasonixGlassSurface(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        contentPadding = PaddingValues(8.dp),
        surfaceColorOverride = surfaceColorOverride ?: fallback,
        content = content
    )
}

@Composable
private fun ProjectGitSection(
    config: ProviderConfig,
    currentProjectPath: String?,
    detectedRepos: List<ProjectDetectedRepoUi>,
    selectedRepoRoot: String?,
    onSelectRepoRoot: (String) -> Unit,
    draftPathCount: Int,
    snapshotCount: Int,
    mcpToolCount: Int,
    onProjectSecondaryPageChanged: (ProjectSecondaryChromeState) -> Unit,
    closeProjectSecondaryPageRequestSignal: Int,
    projectSecondaryBackProgress: Float
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
    var workspaceRemoteSummaryStore by remember(currentProjectPath) {
        val cache = loadProjectGitHubWorkspaceRemoteSummaryCache(projectStoragePath)
        mutableStateOf(
            ProjectGitHubWorkspaceRemoteSummaryStore(
                summaries = cache.summaries,
                lastUpdatedMillis = cache.lastUpdatedMillis
            )
        )
    }
    val activeProjectPath = selectedRepoRoot ?: currentProjectPath
    var gitState by remember(activeProjectPath) { mutableStateOf(ProjectGitStatusUi.empty(activeProjectPath)) }
    var isGitLoading by remember(activeProjectPath) { mutableStateOf(false) }
    var isGitActionRunning by remember(activeProjectPath) { mutableStateOf(false) }
    var workspaceRemoteSummaryLastResumeCheckAt by remember(currentProjectPath) { mutableStateOf(0L) }
    var workspaceRemoteSummaryResumeSignal by remember(currentProjectPath) { mutableStateOf(0) }
    var hasObservedWorkspaceResume by remember(currentProjectPath) { mutableStateOf(false) }
    var feedbackMessage by remember(activeProjectPath) { mutableStateOf<String?>(null) }
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
    var artifactDialogState by remember(activeProjectPath) { mutableStateOf<ProjectGitHubArtifactDialogUi?>(null) }
    var workflowRunDetailDialogState by remember(activeProjectPath) { mutableStateOf<ProjectGitHubWorkflowRunDetailUi?>(null) }
    var showInitGitDialog by remember(activeProjectPath) { mutableStateOf(false) }
    var initBranchDraft by remember(activeProjectPath) { mutableStateOf("main") }
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
    var pendingWorkspaceDetailTarget by remember(currentProjectPath) {
        mutableStateOf<ProjectGitHubWorkspaceDetailTarget?>(null)
    }
    var workspaceFilterType by remember(currentProjectPath) {
        mutableStateOf(ProjectGitHubWorkspaceFilterType.ALL)
    }
    var workspaceSearchQuery by remember(currentProjectPath) {
        mutableStateOf("")
    }
    var workspaceNavigationState by remember(currentProjectPath) {
        mutableStateOf(clearProjectGitHubWorkspaceNavigationState())
    }
    var globalSearchStore by remember(currentProjectPath) {
        mutableStateOf(ProjectGitHubGlobalSearchStore())
    }
    var globalTaskCenter by remember(currentProjectPath) {
        mutableStateOf(ProjectGitHubGlobalTaskCenterUi())
    }
    var workspaceSelectedRepoPaths by remember(currentProjectPath) {
        mutableStateOf(setOf<String>())
    }
    var isWorkspaceSelectionMode by remember(currentProjectPath) {
        mutableStateOf(false)
    }
    val showGitHubWorkspacePage = workspaceNavigationState.isVisible
    val showGitHubWorkspaceDownloadCenterPage = workspaceNavigationState.isDownloadCenterVisible
    val workspaceWorkbenchRepoRoot = workspaceNavigationState.workbenchRepoRoot
    val workspaceWorkbenchSelectedTab = workspaceNavigationState.workbenchSelectedTab

    fun resetCommitDraft() {
        commitTitleDraft = ""
        commitDetailDraft = ""
    }

    fun resetReleaseDraft() {
        releaseDraftState = clearProjectGitHubReleaseDraftState()
    }

    fun resetCreateGitHubRepoDraft() {
        createGitHubRepoNameDraft = suggestProjectGitHubRepoName(activeProjectPath)
        createGitHubRepoDescriptionDraft = ""
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

    fun openSystemDownloads() {
        runCatching {
            context.startActivity(
                Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { error ->
            feedbackMessage = error.message ?: "无法打开系统下载列表"
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

    fun runGitAction(
        successFallback: String,
        block: suspend () -> ProjectGitCommandResult
    ) {
        scope.launch {
            isGitActionRunning = true
            val result = withContext(Dispatchers.IO) { block() }
            feedbackMessage = when {
                result.success -> result.output.ifBlank { successFallback }
                else -> result.error ?: result.output.ifBlank { "Git 操作失败" }
            }
            isGitActionRunning = false
            if (result.success) {
                refreshGitState()
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

    fun refreshGitHubWorkspaceRemoteSummaries(
        refreshPlan: ProjectGitHubWorkspaceRemoteSummaryRefreshPlan? = null
    ) {
        val token = config.githubToken.trim()
        val refreshRequestedAt = System.currentTimeMillis()
        if (token.isBlank()) {
            workspaceRemoteSummaryStore = clearProjectGitHubWorkspaceRemoteSummaryStore()
            workspaceRemoteSummaryLastResumeCheckAt = refreshRequestedAt
            return
        }
        val repoSnapshot = detectedRepos.toList()
        val repoStatusSnapshot = repoStatusSummaries.toMap()
        val apiBaseUrl = config.getGitHubApiBaseUrl()
        val effectiveRefreshPlan = refreshPlan ?: planProjectGitHubWorkspaceRemoteSummaryRefresh(
            detectedRepos = repoSnapshot,
            repoStatusSummaries = repoStatusSnapshot,
            selectedRepoRoot = selectedRepoRoot,
            token = token,
            apiBaseUrl = apiBaseUrl,
            currentStore = workspaceRemoteSummaryStore
        )
        if (!effectiveRefreshPlan.shouldRefresh) {
            workspaceRemoteSummaryLastResumeCheckAt = refreshRequestedAt
            return
        }
        if (effectiveRefreshPlan.targetsToRefresh.isEmpty()) {
            workspaceRemoteSummaryStore = applyProjectGitHubWorkspaceRemoteSummaryRefreshResult(
                currentStore = workspaceRemoteSummaryStore,
                refreshPlan = effectiveRefreshPlan,
                refreshedSummaries = emptyMap(),
                fetchedAtMillis = null
            )
            workspaceRemoteSummaryLastResumeCheckAt = refreshRequestedAt
            return
        }
        workspaceRemoteSummaryLastResumeCheckAt = refreshRequestedAt
        scope.launch {
            workspaceRemoteSummaryStore = beginProjectGitHubWorkspaceRemoteSummaryRefresh(
                workspaceRemoteSummaryStore
            )
            runCatching {
                withContext(Dispatchers.IO) {
                    loadProjectGitHubWorkspaceRemoteSummaries(
                        targets = effectiveRefreshPlan.targetsToRefresh,
                        token = token,
                        apiBaseUrl = apiBaseUrl
                    )
                }
            }.onSuccess { summaries ->
                val fetchedAtMillis = System.currentTimeMillis()
                val newStore = applyProjectGitHubWorkspaceRemoteSummaryRefreshResult(
                    currentStore = workspaceRemoteSummaryStore,
                    refreshPlan = effectiveRefreshPlan,
                    refreshedSummaries = summaries,
                    fetchedAtMillis = fetchedAtMillis
                )
                workspaceRemoteSummaryStore = newStore
                saveProjectGitHubWorkspaceRemoteSummaryCache(projectStoragePath, newStore.summaries)
            }.onFailure { error ->
                workspaceRemoteSummaryStore = failProjectGitHubWorkspaceRemoteSummaryRefresh(
                    currentStore = workspaceRemoteSummaryStore,
                    errorMessage = error.message ?: "刷新工作区远端摘要失败"
                )
            }
        }
    }

    fun refreshGitHubWorkspaceRemoteSummariesForRoots(
        rootPaths: List<String>
    ) {
        val token = config.githubToken.trim()
        if (token.isBlank()) return

        val repoSnapshot = detectedRepos.filter { it.rootPath in rootPaths }
        val repoStatusSnapshot = repoStatusSummaries.filterKeys { it in rootPaths }
        val apiBaseUrl = config.getGitHubApiBaseUrl()

        val plan = planProjectGitHubWorkspaceRemoteSummaryRefresh(
            detectedRepos = repoSnapshot,
            repoStatusSummaries = repoStatusSnapshot,
            selectedRepoRoot = null,
            token = token,
            apiBaseUrl = apiBaseUrl,
            currentStore = workspaceRemoteSummaryStore,
            forceRefreshTargets = true
        )
        refreshGitHubWorkspaceRemoteSummaries(plan)
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
            githubActionsState.repoHtmlUrl ?: workspaceRemoteSummaryStore.summaries[activeProjectPath]?.repoHtmlUrl,
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

    fun openGitHubWorkspaceRepoWorkbench(
        rootPath: String,
        selectedTab: ProjectGitHubWorkspaceRepoWorkbenchTab =
            ProjectGitHubWorkspaceRepoWorkbenchTab.OVERVIEW
    ) {
        onSelectRepoRoot(rootPath)
        workspaceNavigationState = openProjectGitHubWorkspaceRepoWorkbench(
            currentState = workspaceNavigationState,
            rootPath = rootPath,
            selectedTab = selectedTab
        )
    }

    fun openWorkflowRunDetailForRepo(
        rootPath: String,
        run: ProjectGitHubWorkflowRunUi
    ) {
        openGitHubWorkspaceRepoWorkbench(
            rootPath = rootPath,
            selectedTab = ProjectGitHubWorkspaceRepoWorkbenchTab.WORKFLOW
        )
        pendingWorkspaceDetailTarget = ProjectGitHubWorkspaceDetailTarget(
            rootPath = rootPath,
            selectedTab = ProjectGitHubWorkspaceRepoWorkbenchTab.WORKFLOW,
            workflowRun = run
        )
    }

    fun openIssueDetailForRepo(
        rootPath: String,
        issue: ProjectGitHubIssueUi
    ) {
        openGitHubWorkspaceRepoWorkbench(
            rootPath = rootPath,
            selectedTab = ProjectGitHubWorkspaceRepoWorkbenchTab.ISSUES
        )
        pendingWorkspaceDetailTarget = ProjectGitHubWorkspaceDetailTarget(
            rootPath = rootPath,
            selectedTab = ProjectGitHubWorkspaceRepoWorkbenchTab.ISSUES,
            issue = issue
        )
    }

    fun openPullRequestDetailForRepo(
        rootPath: String,
        pullRequest: ProjectGitHubPullRequestUi
    ) {
        openGitHubWorkspaceRepoWorkbench(
            rootPath = rootPath,
            selectedTab = ProjectGitHubWorkspaceRepoWorkbenchTab.PULL_REQUESTS
        )
        pendingWorkspaceDetailTarget = ProjectGitHubWorkspaceDetailTarget(
            rootPath = rootPath,
            selectedTab = ProjectGitHubWorkspaceRepoWorkbenchTab.PULL_REQUESTS,
            pullRequest = pullRequest
        )
    }

    fun searchGitHubGlobal() {
        val query = globalSearchStore.query
        if (query.isBlank()) return

        val token = config.githubToken.trim()
        if (token.isBlank()) {
            globalSearchStore = globalSearchStore.copy(errorMessage = "请先在设置中配置 GitHub Token")
            return
        }

        val repos = detectedRepos.mapNotNull { repo ->
            val status = repoStatusSummaries[repo.rootPath] ?: return@mapNotNull null
            parseProjectGitHubRepoRef(status.remoteUrl)
        }

        if (repos.isEmpty()) {
            globalSearchStore = globalSearchStore.copy(errorMessage = "未找到已配置 GitHub 远端的仓库")
            return
        }

        val localRepoMap = detectedRepos.mapNotNull { repo ->
            val status = repoStatusSummaries[repo.rootPath] ?: return@mapNotNull null
            val ref = parseProjectGitHubRepoRef(status.remoteUrl) ?: return@mapNotNull null
            "${ref.owner}/${ref.repo}" to repo.rootPath
        }.toMap()

        globalSearchStore = globalSearchStore.copy(isLoading = true, errorMessage = null)
        scope.launch {
            val results = withContext(Dispatchers.IO) {
                searchProjectGitHubGlobal(
                    query = query,
                    repos = repos,
                    token = token,
                    apiBaseUrl = config.getGitHubApiBaseUrl(),
                    localRepoMap = localRepoMap
                )
            }
            globalSearchStore = globalSearchStore.copy(
                results = results,
                isLoading = false
            )
        }
    }

    fun onGlobalSearchResultClick(result: ProjectGitHubGlobalSearchResultUi) {
        globalSearchStore = globalSearchStore.copy(isVisible = false)
        when (result.type) {
            ProjectGitHubGlobalSearchResultType.ISSUE -> {
                if (result.rootPath != null && result.number != null) {
                    val issue = ProjectGitHubIssueUi(
                        number = result.number,
                        title = result.title,
                        body = "",
                        state = "open",
                        authorLogin = null,
                        updatedAt = result.updatedAt.orEmpty(),
                        htmlUrl = result.url,
                        labels = emptyList()
                    )
                    openIssueDetailForRepo(result.rootPath, issue)
                } else {
                    openGitHubPage(result.url, "无法打开 Issue 页面")
                }
            }
            ProjectGitHubGlobalSearchResultType.PULL_REQUEST -> {
                if (result.rootPath != null && result.number != null) {
                    val pr = ProjectGitHubPullRequestUi(
                        number = result.number,
                        title = result.title,
                        body = "",
                        state = "open",
                        isDraft = false,
                        isMerged = false,
                        authorLogin = "",
                        updatedAt = result.updatedAt.orEmpty(),
                        htmlUrl = result.url,
                        labels = emptyList(),
                        headSha = "",
                        headBranch = "",
                        baseBranch = ""
                    )
                    openPullRequestDetailForRepo(result.rootPath, pr)
                } else {
                    openGitHubPage(result.url, "无法打开 PR 页面")
                }
            }
            ProjectGitHubGlobalSearchResultType.FILE -> {
                if (result.rootPath != null && result.filePath != null) {
                    openGitHubWorkspaceRepoWorkbench(result.rootPath, ProjectGitHubWorkspaceRepoWorkbenchTab.REMOTE)
                } else {
                    openGitHubPage(result.url, "无法打开文件页面")
                }
            }
        }
    }

    fun onGlobalTaskClick(task: ProjectGitHubWorkspaceTaskUi) {
        globalTaskCenter = globalTaskCenter.copy(isVisible = false)
        when {
            task.targetWorkflowRun != null -> {
                openWorkflowRunDetailForRepo(task.repoRoot, task.targetWorkflowRun)
            }
            task.targetIssue != null -> {
                openIssueDetailForRepo(task.repoRoot, task.targetIssue)
            }
            task.targetPullRequest != null -> {
                openPullRequestDetailForRepo(task.repoRoot, task.targetPullRequest)
            }
            task.targetTab != null -> {
                openGitHubWorkspaceRepoWorkbench(task.repoRoot, task.targetTab)
            }
            else -> {
                openGitHubWorkspaceRepoWorkbench(task.repoRoot, ProjectGitHubWorkspaceRepoWorkbenchTab.OVERVIEW)
            }
        }
    }

    fun runGitHubWorkspaceBatchAction(action: ProjectGitHubWorkspaceBatchAction) {
        val selectedPaths = workspaceSelectedRepoPaths.toList()
        if (selectedPaths.isEmpty()) {
            feedbackMessage = "请先选择要操作的仓库"
            return
        }

        isWorkspaceSelectionMode = false
        workspaceSelectedRepoPaths = emptySet()

        scope.launch {
            when (action) {
                ProjectGitHubWorkspaceBatchAction.REFRESH_STATUS -> {
                    feedbackMessage = "正在批量刷新 ${selectedPaths.size} 个仓库的状态..."
                    selectedPaths.forEach { path ->
                        val status = withContext(Dispatchers.IO) { loadProjectGitStatus(path) }
                        repoStatusSummaries[path] = status
                    }
                    feedbackMessage = "批量刷新状态完成"
                }
                ProjectGitHubWorkspaceBatchAction.FETCH -> {
                    feedbackMessage = "正在对 ${selectedPaths.size} 个仓库执行 Fetch..."
                    selectedPaths.forEach { path ->
                        withContext(Dispatchers.IO) {
                            embeddedGitFetch(path, config.githubToken.trim())
                        }
                        val status = withContext(Dispatchers.IO) { loadProjectGitStatus(path) }
                        repoStatusSummaries[path] = status
                    }
                    feedbackMessage = "批量 Fetch 完成"
                }
                ProjectGitHubWorkspaceBatchAction.PULL -> {
                    feedbackMessage = "正在对 ${selectedPaths.size} 个仓库执行 Pull..."
                    var successCount = 0
                    var failCount = 0
                    selectedPaths.forEach { path ->
                        val success = withContext(Dispatchers.IO) {
                            runCatching {
                                embeddedGitPull(path, config.githubToken.trim())
                            }.isSuccess
                        }
                        if (success) successCount++ else failCount++
                        val status = withContext(Dispatchers.IO) { loadProjectGitStatus(path) }
                        repoStatusSummaries[path] = status
                    }
                    feedbackMessage = "批量 Pull 完成: 成功 $successCount, 失败 $failCount"
                }
                ProjectGitHubWorkspaceBatchAction.REFRESH_REMOTE -> {
                    feedbackMessage = "正在批量刷新 ${selectedPaths.size} 个仓库的 GitHub 摘要..."
                    refreshGitHubWorkspaceRemoteSummariesForRoots(selectedPaths)
                    feedbackMessage = "批量刷新 GitHub 摘要已排队"
                }
            }
        }
    }

    fun openGitHubWorkspaceDownloadCenter() {
        workspaceNavigationState = openProjectGitHubWorkspaceDownloadCenter(
            currentState = workspaceNavigationState
        )
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

    LaunchedEffect(activeProjectPath, workspaceWorkbenchRepoRoot, pendingWorkspaceDetailTarget) {
        val target = pendingWorkspaceDetailTarget ?: return@LaunchedEffect
        if (activeProjectPath != target.rootPath || workspaceWorkbenchRepoRoot != target.rootPath) {
            return@LaunchedEffect
        }
        workspaceNavigationState = workspaceNavigationState.copy(
            workbenchSelectedTab = target.selectedTab
        )
        when {
            target.workflowRun != null -> {
                val token = config.githubToken.trim()
                if (token.isBlank()) {
                    feedbackMessage = "请先在设置页填写 GitHub Token。"
                } else {
                    val repo = workspaceRemoteSummaryStore.summaries[target.rootPath]?.repo
                        ?: parseProjectGitHubRepoRef(repoStatusSummaries[target.rootPath]?.remoteUrl)
                    if (repo == null) {
                        feedbackMessage = "当前仓库还没有可用的 GitHub 远端信息。"
                    } else {
                        isGitHubActionRunning = true
                        val result = withContext(Dispatchers.IO) {
                            loadProjectGitHubWorkflowRunDetail(
                                repo = repo,
                                runId = target.workflowRun.id,
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
            }
            target.issue != null -> openIssueDetail(target.issue)
            target.pullRequest != null -> openPullRequestDetail(target.pullRequest)
        }
        pendingWorkspaceDetailTarget = null
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

    LaunchedEffect(detectedRepos, workspaceWorkbenchRepoRoot) {
        workspaceNavigationState = normalizeProjectGitHubWorkspaceNavigationState(
            currentState = workspaceNavigationState,
            detectedRepos = detectedRepos
        )
    }

    LaunchedEffect(gitState.remoteUrl, gitState.isRepository, config.githubToken, config.githubApiBaseUrl) {
        if (!gitState.isRepository) {
            githubActionsState = ProjectGitHubActionsState.empty()
        } else {
            refreshGitHubActions()
        }
    }

    DisposableEffect(lifecycleOwner, showGitHubWorkspacePage) {
        val observer = LifecycleEventObserver { _, event ->
            if (event != Lifecycle.Event.ON_RESUME) return@LifecycleEventObserver
            if (!hasObservedWorkspaceResume) {
                hasObservedWorkspaceResume = true
                return@LifecycleEventObserver
            }
            val nowMillis = System.currentTimeMillis()
            if (
                shouldCheckProjectGitHubWorkspaceRemoteSummaryOnResume(
                    showGitHubWorkspacePage = showGitHubWorkspacePage,
                    lastResumeCheckAtMillis = workspaceRemoteSummaryLastResumeCheckAt,
                    nowMillis = nowMillis
                )
            ) {
                workspaceRemoteSummaryLastResumeCheckAt = nowMillis
                workspaceRemoteSummaryResumeSignal += 1
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val workspaceRemoteSummaryInputs = remember(detectedRepos, repoStatusSummaries.toMap()) {
        detectedRepos.map { repo ->
            repo.rootPath to parseProjectGitHubRepoRef(repoStatusSummaries[repo.rootPath]?.remoteUrl)
        }
    }
    val workspaceRemoteSummaryRefreshPlan = remember(
        detectedRepos,
        repoStatusSummaries.toMap(),
        config.githubToken,
        config.githubApiBaseUrl,
        workspaceRemoteSummaryStore
    ) {
        planProjectGitHubWorkspaceRemoteSummaryRefresh(
            detectedRepos = detectedRepos,
            repoStatusSummaries = repoStatusSummaries.toMap(),
            selectedRepoRoot = selectedRepoRoot,
            token = config.githubToken.trim(),
            apiBaseUrl = config.getGitHubApiBaseUrl(),
            currentStore = workspaceRemoteSummaryStore
        )
    }

    LaunchedEffect(
        showGitHubWorkspacePage,
        workspaceRemoteSummaryInputs,
        workspaceRemoteSummaryRefreshPlan,
        config.githubToken,
        config.githubApiBaseUrl
    ) {
        if (showGitHubWorkspacePage && workspaceRemoteSummaryRefreshPlan.shouldRefresh) {
            refreshGitHubWorkspaceRemoteSummaries(workspaceRemoteSummaryRefreshPlan)
        }
    }
    LaunchedEffect(
        showGitHubWorkspacePage,
        workspaceRemoteSummaryResumeSignal,
        workspaceRemoteSummaryRefreshPlan
    ) {
        if (
            showGitHubWorkspacePage &&
            workspaceRemoteSummaryResumeSignal > 0 &&
            workspaceRemoteSummaryRefreshPlan.shouldRefresh
        ) {
            refreshGitHubWorkspaceRemoteSummaries(workspaceRemoteSummaryRefreshPlan)
        }
    }

    val workspaceWorkbenchRepo = remember(workspaceWorkbenchRepoRoot, detectedRepos) {
        detectedRepos.firstOrNull { it.rootPath == workspaceWorkbenchRepoRoot }
    }
    val workspaceWorkbenchRemoteSummary = workspaceWorkbenchRepoRoot?.let(workspaceRemoteSummaryStore.summaries::get)
    val workspaceWorkbenchSubtitle = remember(
        workspaceWorkbenchRepo,
        workspaceWorkbenchRemoteSummary,
        showGitHubWorkspaceDownloadCenterPage
    ) {
        if (showGitHubWorkspaceDownloadCenterPage) {
            "工作区级日志、产物与 Release 下载记录"
        } else if (workspaceWorkbenchRepo == null) {
            "工作区总览、仓库摘要与异常聚合"
        } else {
            buildString {
                append(
                    if (workspaceWorkbenchRepo.isWorkspaceRoot) {
                        "工作区根目录"
                    } else {
                        workspaceWorkbenchRepo.relativePath
                    }
                )
                workspaceWorkbenchRemoteSummary?.repo?.let { repo ->
                    append(" · ")
                    append(repo.owner)
                    append("/")
                    append(repo.repo)
                }
            }
        }
    }

    LaunchedEffect(
        showGitHubWorkspacePage,
        workspaceWorkbenchRepo,
        workspaceWorkbenchSubtitle,
        showGitHubWorkspaceDownloadCenterPage
    ) {
        onProjectSecondaryPageChanged(
            if (showGitHubWorkspacePage) {
                ProjectSecondaryChromeState(
                    active = true,
                    title = when {
                        showGitHubWorkspaceDownloadCenterPage -> "下载中心"
                        workspaceWorkbenchRepo != null -> workspaceWorkbenchRepo.displayName.ifBlank { "仓库工作台" }
                        else -> "GitHub 工作区"
                    },
                    subtitle = workspaceWorkbenchSubtitle,
                    supportsEditorMenu = false
                )
            } else {
                ProjectSecondaryChromeState()
            }
        )
    }

    LaunchedEffect(closeProjectSecondaryPageRequestSignal) {
        if (closeProjectSecondaryPageRequestSignal != 0 && showGitHubWorkspacePage) {
            workspaceNavigationState = closeProjectGitHubWorkspaceNavigationLayer(
                currentState = workspaceNavigationState
            )
        }
    }

    if (showGitHubWorkspacePage) {
        if (showGitHubWorkspaceDownloadCenterPage) {
            ProjectGitHubWorkspaceDownloadCenterPage(
                downloads = downloadStore.records,
                onOpenSystemDownloads = ::openSystemDownloads,
                onOpenDownloadSource = { item ->
                    openGitHubPage(
                        item.sourceUrl,
                        "这条下载记录暂时没有可打开的来源页面。"
                    )
                },
                onDeleteRecord = ::deleteGitHubDownloadRecord,
                onClearHistory = ::clearGitHubDownloadHistory,
                backProgress = projectSecondaryBackProgress
            )
        } else if (workspaceWorkbenchRepo != null) {
            val workbenchStatus = repoStatusSummaries[workspaceWorkbenchRepo.rootPath]
                ?: ProjectGitStatusUi.empty(workspaceWorkbenchRepo.rootPath)
            val workbenchSummary = ProjectGitHubWorkspaceRepoSummaryUi(
                repo = workspaceWorkbenchRepo,
                status = workbenchStatus,
                githubRepo = parseProjectGitHubRepoRef(workbenchStatus.remoteUrl),
                isSelected = workspaceWorkbenchRepo.rootPath == selectedRepoRoot
            )
            ProjectGitHubWorkspaceRepoWorkbenchPage(
                summary = workbenchSummary,
                remoteSummary = workspaceWorkbenchRemoteSummary,
                gitState = gitState,
                githubActionsState = githubActionsState,
                remoteRepoState = remoteRepoState,
                isGitLoading = isGitLoading,
                isGitHubLoading = isGitHubLoading,
                isGitHubActionRunning = isGitHubActionRunning,
                isRemoteRepoLoading = isRemoteRepoLoading,
                tokenConfigured = config.githubToken.isNotBlank(),
                remoteRepoRefDraft = remoteRepoRefDraft,
                onRemoteRepoRefDraftChange = { remoteRepoRefDraft = it },
                onRefreshGitState = ::refreshGitState,
                onRefreshGitHubActions = ::refreshGitHubActions,
                onRefreshRemoteRepository = { refreshRemoteRepositoryBrowser(resetRefIfBlank = true) },
                onApplyRemoteRef = { refreshRemoteRepositoryBrowser(targetPath = remoteRepoState.currentPath) },
                onOpenRepoPage = ::openCurrentGitHubRepoPage,
                onOpenWorkflowPage = { workflow ->
                    openGitHubPage(
                        workflow.htmlUrl,
                        "当前工作流还没有可打开的 GitHub 页面地址。"
                    )
                },
                onRunWorkflow = { workflow ->
                    workflowDispatchTarget = workflow
                    workflowDispatchRefDraft = gitState.currentBranch
                        ?: githubActionsState.defaultBranch
                        ?: "main"
                },
                onOpenArtifacts = ::openWorkflowArtifacts,
                onOpenRunPage = { run ->
                    openGitHubPage(
                        run.htmlUrl,
                        "当前运行记录还没有可打开的 GitHub 页面地址。"
                    )
                },
                onDownloadRunLogs = { run ->
                    val repo = githubActionsState.repo ?: return@ProjectGitHubWorkspaceRepoWorkbenchPage
                    val token = config.githubToken.trim()
                    if (token.isBlank()) {
                        feedbackMessage = "请先在设置页填写 GitHub Token。"
                        return@ProjectGitHubWorkspaceRepoWorkbenchPage
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
                onOpenRemoteParent = {
                    refreshRemoteRepositoryBrowser(
                        targetPath = parentProjectGitHubRepoPath(remoteRepoState.currentPath).orEmpty()
                    )
                },
                onOpenRemoteRoot = { refreshRemoteRepositoryBrowser(targetPath = "") },
                onOpenRemoteEntry = ::openRemoteRepositoryEntry,
                onOpenRemoteEntryPage = { entry ->
                    openGitHubPage(
                        entry.htmlUrl,
                        "当前条目还没有可打开的 GitHub 页面地址。"
                    )
                },
                onCreateIssue = {
                    resetCreateIssueDraft()
                    showCreateIssueDialog = true
                },
                onOpenIssueDetail = ::openIssueDetail,
                onToggleIssueState = { issue, shouldClose ->
                    val repo = githubActionsState.repo ?: return@ProjectGitHubWorkspaceRepoWorkbenchPage
                    val token = config.githubToken.trim()
                    if (token.isBlank()) {
                        feedbackMessage = "请先在设置页填写 GitHub Token。"
                        return@ProjectGitHubWorkspaceRepoWorkbenchPage
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
                    val repo = githubActionsState.repo ?: return@ProjectGitHubWorkspaceRepoWorkbenchPage
                    val token = config.githubToken.trim()
                    if (token.isBlank()) {
                        feedbackMessage = "请先在设置页填写 GitHub Token。"
                        return@ProjectGitHubWorkspaceRepoWorkbenchPage
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
                    val repo = githubActionsState.repo ?: return@ProjectGitHubWorkspaceRepoWorkbenchPage
                    val token = config.githubToken.trim()
                    if (token.isBlank()) {
                        feedbackMessage = "请先在设置页填写 GitHub Token。"
                        return@ProjectGitHubWorkspaceRepoWorkbenchPage
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
                onCreateRelease = {
                    releaseDraftState = createProjectGitHubReleaseDraftState(gitState.currentBranch)
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
                downloads = downloadStore.records,
                onOpenSystemDownloads = ::openSystemDownloads,
                onOpenDownloadSource = { item ->
                    openGitHubPage(
                        item.sourceUrl,
                        "这条下载记录暂时没有可打开的来源页面。"
                    )
                },
                selectedTab = workspaceWorkbenchSelectedTab,
                onSelectTab = { tab ->
                    workspaceNavigationState = workspaceNavigationState.copy(
                        workbenchSelectedTab = tab
                    )
                },
                onExitWorkbench = {
                    workspaceNavigationState = closeProjectGitHubWorkspaceRepoWorkbench(
                        currentState = workspaceNavigationState
                    )
                },
                backProgress = projectSecondaryBackProgress
            )
        } else {
            val overviewRepoCards = remember(
                detectedRepos,
                repoStatusSummaries,
                selectedRepoRoot,
                workspaceRemoteSummaryStore.summaries,
                workspaceFilterType,
                workspaceSearchQuery
            ) {
                buildProjectGitHubWorkspaceRepoCards(
                    repos = detectedRepos,
                    repoStatusSummaries = repoStatusSummaries,
                    selectedRepoRoot = selectedRepoRoot,
                    remoteSummaries = workspaceRemoteSummaryStore.summaries,
                    filterType = workspaceFilterType,
                    searchQuery = workspaceSearchQuery
                )
            }
            val overview = remember(
                overviewRepoCards,
                workspaceRemoteSummaryStore.summaries.size,
                workspaceRemoteSummaryStore.lastUpdatedMillis,
                gitState.remoteUrl,
                githubActionsState
            ) {
                buildProjectGitHubWorkspaceOverview(
                    repoCards = overviewRepoCards,
                    remoteSummaryCount = workspaceRemoteSummaryStore.summaries.size,
                    activeRepo = parseProjectGitHubRepoRef(gitState.remoteUrl),
                    activeGitHubState = githubActionsState,
                    lastUpdatedMillis = workspaceRemoteSummaryStore.lastUpdatedMillis
                )
            }
            val remoteSummaryStatusLabel = remember(
                config.githubToken,
                overviewRepoCards,
                workspaceRemoteSummaryStore
            ) {
                buildProjectGitHubWorkspaceRemoteSummaryStatusLabel(
                    tokenConfigured = config.githubToken.isNotBlank(),
                    targetRepoCount = overviewRepoCards.count { it.hasGitHubRepo },
                    currentStore = workspaceRemoteSummaryStore
                )
            }
            ProjectGitHubWorkspaceOverviewPage(
                overview = overview,
                repoCards = overviewRepoCards,
                tokenConfigured = config.githubToken.isNotBlank(),
                remoteSummaryStatusLabel = remoteSummaryStatusLabel,
                isRemoteSummaryLoading = workspaceRemoteSummaryStore.isLoading,
                remoteSummaryErrorMessage = workspaceRemoteSummaryStore.globalErrorMessage,
                onRefreshRemoteSummaries = ::refreshGitHubWorkspaceRemoteSummaries,
                downloads = downloadStore.records,
                onOpenDownloadCenter = ::openGitHubWorkspaceDownloadCenter,
                onOpenSystemDownloads = ::openSystemDownloads,
                onOpenRepoWorkbench = ::openGitHubWorkspaceRepoWorkbench,
                onOpenWorkflowRunDetailTarget = ::openWorkflowRunDetailForRepo,
                onOpenIssueDetailTarget = ::openIssueDetailForRepo,
                onOpenPullRequestDetailTarget = ::openPullRequestDetailForRepo,
                currentFilter = workspaceFilterType,
                onFilterChange = { workspaceFilterType = it },
                searchQuery = workspaceSearchQuery,
                onSearchQueryChange = { workspaceSearchQuery = it },
                onOpenGlobalSearch = { globalSearchStore = globalSearchStore.copy(isVisible = true) },
                onOpenGlobalTaskCenter = {
                    globalTaskCenter = buildProjectGitHubGlobalTaskCenter(overviewRepoCards).copy(isVisible = true)
                },
                isSelectionMode = isWorkspaceSelectionMode,
                onToggleSelectionMode = {
                    isWorkspaceSelectionMode = !isWorkspaceSelectionMode
                    if (!isWorkspaceSelectionMode) workspaceSelectedRepoPaths = emptySet()
                },
                selectedRepoPaths = workspaceSelectedRepoPaths,
                onToggleRepoSelection = { path ->
                    workspaceSelectedRepoPaths = if (workspaceSelectedRepoPaths.contains(path)) {
                        workspaceSelectedRepoPaths - path
                    } else {
                        workspaceSelectedRepoPaths + path
                    }
                },
                onBatchAction = ::runGitHubWorkspaceBatchAction,
                backProgress = projectSecondaryBackProgress
            )
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Git", style = MaterialTheme.typography.titleMedium)
        if (activeProjectPath.isNullOrBlank()) {
            Text(
                "先选择一个项目目录，Git 页才能识别仓库状态。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            val surfaceColor = rememberReasonixSurfaceColor()
            val chromeColor = rememberReasonixChromeColor()
            val mutedTextColor = rememberReasonixMutedTextColor()
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
                            ProjectInsetCard(
                                shape = RoundedCornerShape(12.dp),
                                surfaceColorOverride = if (summary.repo.rootPath == selectedRepoRoot) {
                                    surfaceColor.copy(alpha = 0.78f)
                                } else {
                                    chromeColor.copy(alpha = 0.30f)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelectRepoRoot(summary.repo.rootPath) }
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = if (summary.repo.isWorkspaceRoot) {
                                            "${summary.repo.displayName}（工作区根）"
                                        } else if (!summary.repo.hasGitMetadata) {
                                            "${summary.repo.relativePath}（独立项目）"
                                        } else {
                                            summary.repo.relativePath
                                        },
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        text = if (summary.status.branchSummary.isNotBlank()) {
                                            "分支: ${summary.status.branchSummary}"
                                        } else {
                                            "分支: 未知"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = mutedTextColor
                                    )
                                    summary.status.remoteUrl?.takeIf { it.isNotBlank() }?.let { remote ->
                                        Text(
                                            text = "远端: $remote",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = mutedTextColor,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Text(
                                        text = "暂存 ${summary.status.stagedFiles.size} · 修改 ${summary.status.modifiedFiles.size} · 未跟踪 ${summary.status.untrackedFiles.size}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            }
            ProjectSectionCard(
                shape = RoundedCornerShape(14.dp),
                surfaceColorOverride = chromeColor.copy(alpha = 0.38f)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "GitHub 工作区",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = "用工作区视角查看仓库健康度、GitHub 绑定状态和待处理事项，风格与设置页二级页面统一。",
                        style = MaterialTheme.typography.bodySmall,
                        color = mutedTextColor
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                workspaceNavigationState = workspaceNavigationState.copy(isVisible = true)
                            },
                            enabled = detectedRepos.isNotEmpty()
                        ) {
                            Text("打开工作区")
                        }
                        OutlinedButton(
                            onClick = { refreshGitState() },
                            enabled = !isGitLoading && !isGitActionRunning
                        ) {
                            Text("刷新当前仓库")
                        }
                    }
                }
            }
            ProjectSectionCard(
                shape = RoundedCornerShape(14.dp),
                surfaceColorOverride = surfaceColor.copy(alpha = 0.60f)
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        gitState.repoRoot?.let { "仓库根目录: $it" } ?: "当前目录未识别到 Git 仓库",
                        style = MaterialTheme.typography.bodySmall,
                        color = mutedTextColor
                    )
                    Text(
                        if (gitState.branchSummary.isNotBlank()) "当前分支: ${gitState.branchSummary}" else "当前分支: 未知",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    gitState.lastCommitSummary?.takeIf { it.isNotBlank() }?.let { summary ->
                        Text(
                            "最近提交: $summary",
                            style = MaterialTheme.typography.bodySmall,
                            color = mutedTextColor
                        )
                    }
                    Text(
                        "已暂存 ${gitState.stagedFiles.size} · 已修改 ${gitState.modifiedFiles.size} · 未跟踪 ${gitState.untrackedFiles.size} · 冲突 ${gitState.conflictedFiles.size}",
                        style = MaterialTheme.typography.bodySmall,
                        color = mutedTextColor
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        feedbackMessage = null
                        refreshGitState()
                    },
                    enabled = !isGitLoading && !isGitActionRunning
                ) {
                    Text("刷新")
                }
                OutlinedButton(
                    onClick = {
                        val repoRoot = gitState.repoRoot ?: return@OutlinedButton
                        runGitAction("已完成抓取") {
                            runEmbeddedGitAction {
                                embeddedGitFetch(repoRoot, config.githubToken)
                            }
                        }
                    },
                    enabled = gitState.hasRemote && !isGitLoading && !isGitActionRunning
                ) {
                    Text("抓取")
                }
                OutlinedButton(
                    onClick = {
                        val repoRoot = gitState.repoRoot ?: return@OutlinedButton
                        runGitAction("已完成拉取") {
                            runEmbeddedGitAction {
                                embeddedGitPull(repoRoot, config.githubToken)
                            }
                        }
                    },
                    enabled = gitState.canPull && !isGitLoading && !isGitActionRunning
                ) {
                    Text("拉取")
                }
                OutlinedButton(
                    onClick = {
                        val repoRoot = gitState.repoRoot ?: return@OutlinedButton
                        runGitAction("已完成推送") {
                            runEmbeddedGitAction {
                                embeddedGitPush(repoRoot, config.githubToken)
                            }
                        }
                    },
                    enabled = gitState.canPush && !isGitLoading && !isGitActionRunning
                ) {
                    Text("推送")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        val repoRoot = gitState.repoRoot ?: return@OutlinedButton
                        runGitAction("已全部暂存") {
                            runEmbeddedGitAction {
                                embeddedGitStageAll(repoRoot)
                            }
                        }
                    },
                    enabled = gitState.canStageAll && !isGitLoading && !isGitActionRunning
                ) {
                    Text("全部暂存")
                }
                Button(
                    onClick = { showCommitDialog = true },
                    enabled = gitState.canCommit && !isGitLoading && !isGitActionRunning
                ) {
                    Text("提交")
                }
                OutlinedButton(
                    onClick = { showBranchDialog = true },
                    enabled = gitState.isRepository && gitState.hasGitCommand && !isGitLoading && !isGitActionRunning
                ) {
                    Text("分支")
                }
            }

            if (isGitLoading || isGitActionRunning) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.width(18.dp), strokeWidth = 2.dp)
                    Text(
                        if (isGitLoading) "正在读取 Git 状态..." else "正在执行 Git 操作...",
                        style = MaterialTheme.typography.bodySmall,
                        color = mutedTextColor
                    )
                }
            }

            feedbackMessage?.takeIf { it.isNotBlank() }?.let { message ->
                ProjectSectionCard(
                    shape = RoundedCornerShape(12.dp),
                    surfaceColorOverride = chromeColor.copy(alpha = 0.58f)
                ) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            gitState.errorMessage?.takeIf { it.isNotBlank() }?.let { error ->
                ProjectSectionCard(
                    shape = RoundedCornerShape(12.dp),
                    surfaceColorOverride = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f)
                ) {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            if (!gitState.isRepository) {
                ProjectSectionCard(
                    shape = RoundedCornerShape(12.dp),
                    surfaceColorOverride = chromeColor.copy(alpha = 0.46f)
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(text = "仓库初始化", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = "当前项目还没有 `.git`。可以先初始化本地 Git，再一键创建 GitHub 仓库并绑定 origin。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { showInitGitDialog = true },
                                enabled = !isGitLoading && !isGitActionRunning
                            ) {
                                Text("初始化 Git")
                            }
                            OutlinedButton(
                                onClick = {
                                    resetCreateGitHubRepoDraft()
                                    showCreateGitHubRepoDialog = true
                                },
                                enabled = config.githubToken.isNotBlank() && !isGitLoading && !isGitActionRunning && !isGitHubActionRunning
                            ) {
                                Text("新建 GitHub 仓库")
                            }
                        }
                        if (config.githubToken.isBlank()) {
                            Text(
                                text = "要新建 GitHub 仓库，请先在设置页完成 GitHub 登录或填写 Token。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Text(
                    "当前项目目录下没有发现 `.git` 仓库。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                val gitHubWorkbenchRepo = parseProjectGitHubRepoRef(gitState.remoteUrl)
                if (!gitState.hasRemote) {
                    ProjectSectionCard(
                        shape = RoundedCornerShape(12.dp),
                        surfaceColorOverride = chromeColor.copy(alpha = 0.46f)
                    ) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(text = "GitHub 仓库绑定", style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = "当前本地仓库还没有 origin 远端。可以直接新建 GitHub 仓库并绑定。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        resetCreateGitHubRepoDraft()
                                        showCreateGitHubRepoDialog = true
                                    },
                                    enabled = config.githubToken.isNotBlank() && !isGitLoading && !isGitActionRunning && !isGitHubActionRunning
                                ) {
                                    Text("新建 GitHub 仓库")
                                }
                            }
                            if (config.githubToken.isBlank()) {
                                Text(
                                    text = "要新建 GitHub 仓库，请先在设置页完成 GitHub 登录或填写 Token。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                ProjectGitRemoteSummaryCard(
                    remoteUrl = gitState.remoteUrl,
                    upstreamBranch = gitState.upstreamBranch,
                    aheadCount = gitState.aheadCount,
                    behindCount = gitState.behindCount,
                    remoteBranchCount = gitState.remoteBranches.size,
                    tokenConfigured = config.githubToken.isNotBlank()
                )
                ProjectSectionCard(
                    shape = RoundedCornerShape(14.dp),
                    surfaceColorOverride = chromeColor.copy(alpha = 0.34f)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("GitHub 主操作台", style = MaterialTheme.typography.titleSmall)
                        when {
                            !gitState.hasRemote -> {
                                Text(
                                    text = "当前仓库还没有 origin 远端，先完成远端绑定后才能进入 GitHub 工作台。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = mutedTextColor
                                )
                            }
                            gitHubWorkbenchRepo == null -> {
                                Text(
                                    text = "当前 origin 远端还不能识别为 GitHub 仓库地址，工作台暂时只保留本地 Git 能力。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = mutedTextColor
                                )
                            }
                            else -> {
                                Text(
                                    text = buildString {
                                        append("${gitHubWorkbenchRepo.owner}/${gitHubWorkbenchRepo.repo}")
                                        append(" · 工作流 ${githubActionsState.workflows.size}")
                                        append(" · 运行 ${githubActionsState.recentRuns.size}")
                                        append(" · Issue ${githubActionsState.issues.size}")
                                        append(" · PR ${githubActionsState.pullRequests.size}")
                                        append(" · Release ${githubActionsState.releases.size}")
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = mutedTextColor
                                )
                                githubActionsState.errorMessage?.takeIf { it.isNotBlank() }?.let { error ->
                                    Text(
                                        text = error,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                } ?: Text(
                                    text = "工作流、远端仓库、Issue、PR、Release 已迁入仓库工作台，主 Git 页只保留入口与本地仓库控制。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = mutedTextColor
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    activeProjectPath?.let(::openGitHubWorkspaceRepoWorkbench)
                                },
                                enabled = activeProjectPath != null && gitHubWorkbenchRepo != null
                            ) {
                                Text("进入仓库工作台")
                            }
                            if (gitHubWorkbenchRepo != null) {
                                OutlinedButton(
                                    onClick = { refreshGitHubActions() },
                                    enabled = !isGitHubLoading && !isGitHubActionRunning
                                ) {
                                    Text("刷新 GitHub")
                                }
                                TextButton(
                                    onClick = ::openCurrentGitHubRepoPage,
                                    enabled = !isGitHubLoading && !isGitHubActionRunning
                                ) {
                                    Text("仓库页")
                                }
                            }
                        }
                    }
                }
                ProjectGitHubDownloadCenterSummaryCard(
                    downloads = downloadStore.records,
                    onOpenDownloadCenter = ::openGitHubWorkspaceDownloadCenter,
                    onOpenSystemDownloads = ::openSystemDownloads
                )
                ProjectGitBranchSummaryCard(
                    currentBranch = gitState.currentBranch,
                    branchCount = gitState.localBranches.size,
                    onManageBranches = { showBranchDialog = true }
                )
                if (gitState.conflictedFiles.isNotEmpty()) {
                    ProjectGitFileGroup(
                        title = "冲突文件",
                        items = gitState.conflictedFiles,
                        actionLabel = "差异",
                        emptyText = "",
                        onAction = { change -> openDiff(change) },
                        onOpenDiff = ::openDiff
                    )
                }
                ProjectGitHistorySection(
                    commits = gitState.recentCommits,
                    onOpenCommit = { commit ->
                        val repoRoot = gitState.repoRoot ?: return@ProjectGitHistorySection
                        scope.launch {
                            isGitActionRunning = true
                            val preview = withContext(Dispatchers.IO) {
                                loadProjectGitCommitPreview(repoRoot, commit.commitHash)
                            }
                            isGitActionRunning = false
                            if (preview == null) {
                                feedbackMessage = "无法读取提交 ${commit.shortHash} 的详情"
                            } else {
                                diffPreview = preview
                            }
                        }
                    }
                )
                ProjectGitFileGroup(
                    title = "已暂存",
                    items = gitState.stagedFiles,
                    actionLabel = "取消暂存",
                    emptyText = "暂存区目前为空。",
                    onAction = { change ->
                        val repoRoot = gitState.repoRoot ?: return@ProjectGitFileGroup
                        runGitAction("已取消暂存 ${change.displayPath}") {
                            runEmbeddedGitAction {
                                embeddedGitUnstagePath(repoRoot, change.actionPath)
                            }
                        }
                    },
                    onOpenDiff = ::openDiff
                )
                ProjectGitFileGroup(
                    title = "已修改",
                    items = gitState.modifiedFiles,
                    actionLabel = "暂存",
                    emptyText = "工作区没有未暂存修改。",
                    onAction = { change ->
                        val repoRoot = gitState.repoRoot ?: return@ProjectGitFileGroup
                        runGitAction("已暂存 ${change.displayPath}") {
                            runEmbeddedGitAction {
                                embeddedGitStagePath(repoRoot, change.actionPath)
                            }
                        }
                    },
                    onOpenDiff = ::openDiff
                )
                ProjectGitFileGroup(
                    title = "未跟踪",
                    items = gitState.untrackedFiles,
                    actionLabel = "暂存",
                    emptyText = "没有未跟踪文件。",
                    onAction = { change ->
                        val repoRoot = gitState.repoRoot ?: return@ProjectGitFileGroup
                        runGitAction("已暂存 ${change.displayPath}") {
                            runEmbeddedGitAction {
                                embeddedGitStagePath(repoRoot, change.actionPath)
                            }
                        }
                    },
                    onOpenDiff = ::openDiff
                )
            }

            Text(
                "知识草稿路径: $draftPathCount · 快照: $snapshotCount · MCP 工具: $mcpToolCount",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (showInitGitDialog) {
        ReasonixAlertDialog(
            onDismissRequest = { showInitGitDialog = false },
            confirmButton = {
                Button(
                    onClick = {
                        val projectPath = activeProjectPath ?: return@Button
                        val branchName = initBranchDraft.trim().ifBlank { "main" }
                        showInitGitDialog = false
                        runGitAction("已初始化 Git 仓库") {
                            initializeProjectGitRepository(projectPath, branchName)
                        }
                    },
                    enabled = activeProjectPath != null && !isGitLoading && !isGitActionRunning
                ) {
                    Text("初始化")
                }
            },
            dismissButton = {
                TextButton(onClick = { showInitGitDialog = false }) {
                    Text("取消")
                }
            },
            title = { Text("初始化 Git") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = initBranchDraft,
                        onValueChange = { initBranchDraft = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("默认分支") },
                        placeholder = { Text("main") },
                        singleLine = true
                    )
                    Text(
                        text = "会在当前项目目录创建 `.git`，并尽量把默认分支设为你输入的名称。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        )
    }

    if (showCreateGitHubRepoDialog) {
        ReasonixAlertDialog(
            onDismissRequest = { showCreateGitHubRepoDialog = false },
            confirmButton = {
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
                                feedbackMessage = createResult.error ?: "创建 GitHub 仓库失败"
                                return@launch
                            }
                            var message = "已创建 GitHub 仓库 ${createResult.repo.owner}/${createResult.repo.repo}"
                            if (createGitHubRepoBindOriginFlag) {
                                val remoteUrl = createResult.recommendedRemoteUrl
                                if (remoteUrl.isNullOrBlank()) {
                                    isGitHubActionRunning = false
                                    feedbackMessage = "GitHub 已创建仓库，但没有返回可绑定的远端地址"
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
                                    "$message，但绑定 origin 失败: ${bindResult.error ?: bindResult.output}"
                                }
                            }
                            isGitHubActionRunning = false
                            feedbackMessage = message
                            refreshGitState()
                        }
                    },
                    enabled = createGitHubRepoNameDraft.isNotBlank() && config.githubToken.isNotBlank() && !isGitHubActionRunning
                ) {
                    Text("创建")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateGitHubRepoDialog = false }) {
                    Text("取消")
                }
            },
            title = { Text("新建 GitHub 仓库") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = createGitHubRepoNameDraft,
                        onValueChange = { createGitHubRepoNameDraft = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("仓库名") },
                        placeholder = { Text("例如：reasonix-mobile") },
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
        )
    }

    if (showCommitDialog) {
        val finalCommitMessage = buildGitCommitMessage(commitTitleDraft, commitDetailDraft)
        ReasonixAlertDialog(
            onDismissRequest = { showCommitDialog = false },
            confirmButton = {
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
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showCommitDialog = false
                        resetCommitDraft()
                    }
                ) {
                    Text("取消")
                }
            },
            title = { Text("提交更改") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val surfaceColor = rememberReasonixSurfaceColor()
                    val mutedTextColor = rememberReasonixMutedTextColor()
                    val chromeColor = rememberReasonixChromeColor()
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
                }
            }
        )
    }

    if (showBranchDialog) {
        ReasonixAlertDialog(
            onDismissRequest = { showBranchDialog = false },
            confirmButton = {
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
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showBranchDialog = false
                        newBranchName = ""
                    }
                ) {
                    Text("关闭")
                }
            },
            title = { Text("分支管理") },
            text = {
                val surfaceColor = rememberReasonixSurfaceColor()
                val chromeColor = rememberReasonixChromeColor()
                val mutedTextColor = rememberReasonixMutedTextColor()
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
        )
    }

    workflowDispatchTarget?.let { workflow ->
        ReasonixAlertDialog(
            onDismissRequest = { workflowDispatchTarget = null },
            confirmButton = {
                Button(
                    onClick = {
                        val repo = githubActionsState.repo ?: return@Button
                        val token = config.githubToken.trim()
                        val ref = workflowDispatchRefDraft.trim()
                        if (token.isBlank() || ref.isBlank()) return@Button
                        workflowDispatchTarget = null
                        runGitHubAction("已触发工作流 ${workflow.name}") {
                            dispatchProjectGitHubWorkflow(
                                repo = repo,
                                workflowId = workflow.id,
                                ref = ref,
                                token = token,
                                apiBaseUrl = config.getGitHubApiBaseUrl()
                            )
                        }
                    },
                    enabled = workflowDispatchRefDraft.isNotBlank() && !isGitHubActionRunning
                ) {
                    Text("运行")
                }
            },
            dismissButton = {
                TextButton(onClick = { workflowDispatchTarget = null }) {
                    Text("取消")
                }
            },
            title = { Text("运行工作流") },
            text = {
                val quickRefs = listOfNotNull(
                    gitState.currentBranch?.takeIf { it.isNotBlank() },
                    githubActionsState.defaultBranch?.takeIf { it.isNotBlank() },
                    gitState.upstreamBranch?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                ).distinct()
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = workflow.name.ifBlank { workflow.path },
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = workflow.path,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = workflowDispatchRefDraft,
                        onValueChange = { workflowDispatchRefDraft = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Ref / 分支") },
                        placeholder = {
                            Text(githubActionsState.defaultBranch ?: "main")
                        },
                        singleLine = true
                    )
                    if (quickRefs.isNotEmpty()) {
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            quickRefs.forEach { ref ->
                                FilterChip(
                                    selected = workflowDispatchRefDraft == ref,
                                    onClick = { workflowDispatchRefDraft = ref },
                                    label = { Text(ref) }
                                )
                            }
                        }
                    }
                    Text(
                        text = "会对你填写的 ref 执行 `workflow_dispatch`。通常填当前分支或默认分支，例如 `${gitState.currentBranch ?: githubActionsState.defaultBranch ?: "main"}`。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
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
        ReasonixAlertDialog(
            onDismissRequest = { releaseEditTarget = null },
            confirmButton = {
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
            },
            dismissButton = {
                TextButton(onClick = { releaseEditTarget = null }) {
                    Text("取消")
                }
            },
            title = { Text("编辑 Release") },
            text = {
                val mutedTextColor = rememberReasonixMutedTextColor()
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
        )
    }

    if (showCreateReleaseDialog) {
        ReasonixAlertDialog(
            onDismissRequest = {
                showCreateReleaseDialog = false
                resetReleaseDraft()
            },
            confirmButton = {
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
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showCreateReleaseDialog = false
                        resetReleaseDraft()
                    }
                ) {
                    Text("取消")
                }
            },
            title = { Text("新建 Release") },
            text = {
                val mutedTextColor = rememberReasonixMutedTextColor()
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
                        placeholder = { Text("例如：v1.2.0") },
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
        )
    }

    releaseAssetDialogState?.let { dialog ->
        ProjectGitHubReleaseAssetDialog(
            dialog = dialog,
            onDismiss = { releaseAssetDialogState = null },
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
            }
        )
    }

    if (showCreateIssueDialog) {
        ReasonixAlertDialog(
            onDismissRequest = {
                showCreateIssueDialog = false
                resetCreateIssueDraft()
            },
            confirmButton = {
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
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showCreateIssueDialog = false
                        resetCreateIssueDraft()
                    }
                ) {
                    Text("取消")
                }
            },
            title = { Text("新建 Issue") },
            text = {
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
        )
    }

    if (showCreatePullRequestDialog) {
        ReasonixAlertDialog(
            onDismissRequest = {
                showCreatePullRequestDialog = false
                resetCreatePullRequestDraft()
            },
            confirmButton = {
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
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showCreatePullRequestDialog = false
                        resetCreatePullRequestDraft()
                    }
                ) {
                    Text("取消")
                }
            },
            title = { Text("新建 Pull Request") },
            text = {
                val mutedTextColor = rememberReasonixMutedTextColor()
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
        )
    }

    diffPreview?.let { preview ->
        ReasonixAlertDialog(
            onDismissRequest = { diffPreview = null },
            confirmButton = {
                TextButton(onClick = { diffPreview = null }) {
                    Text("关闭")
                }
            },
            title = { Text(preview.title) },
            text = {
                ProjectSectionCard(shape = RoundedCornerShape(12.dp)) {
                    SelectionContainer {
                        Text(
                            text = preview.content,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 420.dp)
                                .verticalScroll(rememberScrollState())
                        )
                    }
                }
            }
        )
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

    remoteFileDialogState?.let { file ->
        val canSubmit = remoteFileCommitMessageDraft.trim().isNotBlank() && !isGitHubActionRunning
        val closeRemoteFileDialog = {
            remoteFileDialogState = null
            remoteFileContentDraft = ""
            remoteFileCommitMessageDraft = ""
        }
        ProjectGitHubRemoteFileDialog(
            file = file,
            contentDraft = remoteFileContentDraft,
            onContentDraftChange = { remoteFileContentDraft = it },
            commitMessageDraft = remoteFileCommitMessageDraft,
            onCommitMessageDraftChange = { remoteFileCommitMessageDraft = it },
            canSubmit = canSubmit,
            onSubmit = {
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
                        closeRemoteFileDialog()
                    }
                    if (result.shouldRefreshBrowser) {
                        refreshRemoteRepositoryBrowser(targetPath = result.refreshTargetPath)
                    }
                }
            },
            onDismiss = closeRemoteFileDialog
        )
    }

    if (globalSearchStore.isVisible) {
        ProjectGitHubGlobalSearchDialog(
            store = globalSearchStore,
            onQueryChange = { globalSearchStore = globalSearchStore.copy(query = it) },
            onSearch = ::searchGitHubGlobal,
            onClose = { globalSearchStore = globalSearchStore.copy(isVisible = false) },
            onResultClick = ::onGlobalSearchResultClick,
            chromeColor = rememberReasonixChromeColor()
        )
    }

    if (globalTaskCenter.isVisible) {
        ProjectGitHubGlobalTaskCenterDialog(
            taskCenter = globalTaskCenter,
            onClose = { globalTaskCenter = globalTaskCenter.copy(isVisible = false) },
            onTaskClick = ::onGlobalTaskClick,
            chromeColor = rememberReasonixChromeColor()
        )
    }
}

private fun approvalModeLabel(mode: ToolApprovalMode): String = when (mode) {
    ToolApprovalMode.READ_ONLY -> "只读模式"
    ToolApprovalMode.ALL_APPROVAL -> "全部审批"
    ToolApprovalMode.WHITELIST_AUTO -> "白名单自动通过"
    ToolApprovalMode.ALL_AUTO -> "全部自动通过"
}

private enum class ProjectPrimaryTab(val label: String) {
    EDITOR("项目"),
    CONFIG("项目配置"),
    GIT("Git")
}

private enum class ProjectEditorMode(val label: String) {
    PREVIEW("高亮预览"),
    EDIT("编辑"),
    RAW_JSON("原始 JSON")
}

private fun isJsonLikeProjectFile(path: String?, language: String? = null): Boolean {
    val normalizedPath = path?.lowercase().orEmpty()
    val normalizedLanguage = language?.lowercase().orEmpty()
    return normalizedPath.endsWith(".json") ||
        normalizedPath.endsWith(".jsonc") ||
        normalizedPath.endsWith(".geojson") ||
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

private data class ProjectGitHubWorkspaceDetailTarget(
    val rootPath: String,
    val selectedTab: ProjectGitHubWorkspaceRepoWorkbenchTab,
    val workflowRun: ProjectGitHubWorkflowRunUi? = null,
    val issue: ProjectGitHubIssueUi? = null,
    val pullRequest: ProjectGitHubPullRequestUi? = null
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
    val results = linkedMapOf<String, Boolean>()
    val pendingDirs = ArrayDeque<File>().apply { add(root) }
    while (pendingDirs.isNotEmpty()) {
        val dir = pendingDirs.removeFirst()
        val dirPath = dir.absolutePath
        val children = runCatching { listProjectEntries(dir) }.getOrElse { emptyList() }
        val hasGitMetadata = hasProjectGitMetadata(dir)
        val isProjectRoot = hasGitMetadata || (dirPath != rootPath && hasProjectRootMarkers(children))
        if (isProjectRoot) {
            results[dirPath] = results[dirPath] == true || hasGitMetadata
        }
        if (isProjectRoot && dirPath != rootPath) {
            continue
        }
        children.forEach { entry ->
            if (!entry.isDirectory) return@forEach
            val childDir = File(entry.absolutePath)
            if (childDir.name == ".git") {
                childDir.parentFile?.absolutePath?.let { repoRoot ->
                    results[repoRoot] = true
                }
                return@forEach
            }
            if (shouldSkipSearchDir(childDir, root)) return@forEach
            pendingDirs += childDir
        }
    }
    return results
        .map { (repoRoot, hasGitMetadata) ->
            ProjectDetectedRepoUi(
                rootPath = repoRoot,
                displayName = File(repoRoot).name.ifBlank { repoRoot },
                relativePath = if (repoRoot == rootPath) "." else relativeProjectPath(rootPath, repoRoot),
                isWorkspaceRoot = repoRoot == rootPath,
                hasGitMetadata = hasGitMetadata
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

private fun hasProjectRootMarkers(entries: List<ProjectTreeEntry>): Boolean {
    if (entries.isEmpty()) return false
    val names = entries.map { it.name.lowercase(Locale.getDefault()) }.toSet()
    return PROJECT_ROOT_MARKERS.any(names::contains)
}

private fun listProjectEntries(dir: File): List<ProjectTreeEntry> {
    val children = dir.listFiles()
    if (!children.isNullOrEmpty()) {
        return children
            .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase(Locale.getDefault()) }))
            .map { child ->
                ProjectTreeEntry(
                    absolutePath = child.absolutePath,
                    name = child.name,
                    isDirectory = child.isDirectory
                )
            }
    }
    if (!dir.isDirectory && !RootFile.dirExists(dir.absolutePath)) {
        error("目录不存在: ${dir.absolutePath}")
    }
    return RootFile.ls(dir.absolutePath)
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
        .sortedWith(compareBy<ProjectTreeEntry>({ !it.isDirectory }, { it.name.lowercase(Locale.getDefault()) }))
}

private fun readProjectFile(file: File): String {
    if (file.exists() && file.isFile) {
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
    if (file.exists() && file.isFile) {
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
    val prefix = when (language?.lowercase(Locale.getDefault())) {
        "python", "bash", "shell", "sh", "yaml", "toml", "ini", "conf", "properties" -> "# "
        "html" -> "<!-- "
        else -> "// "
    }
    val suffix = if (language?.lowercase(Locale.getDefault()) == "html") " -->" else ""
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

private class ProjectEditorVisualTransformation(
    query: String,
    private val currentMatch: TextRange?
) : VisualTransformation {
    private val normalizedQuery = query.trim()

    override fun filter(text: AnnotatedString): TransformedText {
        if (text.isEmpty()) {
            return TransformedText(text, OffsetMapping.Identity)
        }
        if (normalizedQuery.isBlank() && currentMatch == null) {
            return TransformedText(text, OffsetMapping.Identity)
        }
        val builder = AnnotatedString.Builder(text)
        if (normalizedQuery.isNotBlank()) {
            val lowerText = text.text.lowercase(Locale.getDefault())
            val lowerQuery = normalizedQuery.lowercase(Locale.getDefault())
            var startIndex = 0
            while (startIndex < text.length) {
                val matchIndex = lowerText.indexOf(lowerQuery, startIndex)
                if (matchIndex < 0) break
                builder.addStyle(
                    SpanStyle(background = Color(0x33FFD54F)),
                    start = matchIndex,
                    end = matchIndex + normalizedQuery.length
                )
                startIndex = matchIndex + normalizedQuery.length
            }
        }
        currentMatch?.let { range ->
            if (range.min >= 0 && range.max <= text.length && range.max > range.min) {
                builder.addStyle(
                    SpanStyle(
                        background = Color(0x88FFB300),
                        color = Color(0xFF111111),
                        fontWeight = FontWeight.Bold
                    ),
                    start = range.min,
                    end = range.max
                )
            }
        }
        return TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
    }
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
internal data class ProjectGitHubWorkspaceRepoSummaryUi(
    val repo: ProjectDetectedRepoUi,
    val status: ProjectGitStatusUi,
    val githubRepo: ProjectGitHubRepoRef?,
    val isSelected: Boolean
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
