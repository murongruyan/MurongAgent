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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import dev.reasonix.mobile.core.loop.SessionSummary
import dev.reasonix.mobile.core.provider.ChatMessage
import dev.reasonix.mobile.core.provider.ChatRequest
import dev.reasonix.mobile.core.provider.ProviderRegistry
import dev.reasonix.mobile.common.shell.KeepShellPublic
import dev.reasonix.mobile.common.utils.RootFile
import dev.reasonix.mobile.ui.ReasonixAlertDialog
import dev.reasonix.mobile.ui.ReasonixGlassSurface
import dev.reasonix.mobile.ui.ReasonixInfoCard
import dev.reasonix.mobile.ui.ReasonixOutlinedActionButton
import dev.reasonix.mobile.ui.ReasonixTagButton
import dev.reasonix.mobile.ui.SkillDraftImportCard
import dev.reasonix.mobile.ui.highlightSyntax
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.nio.charset.MalformedInputException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

@Composable
fun ProjectScreen(
    config: ProviderConfig,
    currentProjectPath: String?,
    projectKnowledgeDraftPaths: List<String>,
    projectKnowledgeSnapshots: List<ProjectKnowledgeSnapshotUi>,
    projectRules: List<GlobalRule>,
    projectMemories: List<GlobalMemory>,
    projectSkills: List<GlobalSkill>,
    projectToolPreferences: ProjectToolPreferences?,
    mcpToolNames: List<String>,
    sessions: List<SessionSummary>,
    onNewTask: () -> Unit,
    onOpenChat: () -> Unit,
    onUpdateProjectConfig: (List<GlobalRule>?, List<GlobalMemory>?, List<GlobalSkill>?) -> Unit,
    onUpdateProjectToolPreferences: (ProjectToolPreferences?) -> Unit,
    onUpdateProjectKnowledgeDraftPaths: (List<String>) -> Unit,
    onSaveProjectKnowledgeSnapshot: (String, List<String>) -> Unit,
    onRenameProjectKnowledgeSnapshot: (String, String) -> Unit,
    onApplyProjectKnowledgeSnapshot: (String) -> Unit,
    onDeleteProjectKnowledgeSnapshot: (String) -> Unit
) {
    var selectedTab by remember(currentProjectPath) { mutableStateOf(ProjectPrimaryTab.EDITOR) }
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            PrimaryScrollableTabRow(
                selectedTabIndex = selectedTab.ordinal
            ) {
                ProjectPrimaryTab.entries.forEach { tab ->
                    Tab(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        text = { Text(tab.label) }
                    )
                }
            }
            when (selectedTab) {
                ProjectPrimaryTab.EDITOR -> ProjectEditorSection(
                    config = config,
                    currentProjectPath = currentProjectPath,
                    sessions = sessions,
                    onOpenChat = onOpenChat,
                    onNewTask = onNewTask
                )

                ProjectPrimaryTab.CONFIG -> ProjectConfigSection(
                    config = config,
                    projectRules = projectRules,
                    projectMemories = projectMemories,
                    projectSkills = projectSkills,
                    projectToolPreferences = projectToolPreferences,
                    onUpdateProjectConfig = onUpdateProjectConfig,
                    onUpdateProjectToolPreferences = onUpdateProjectToolPreferences
                )

                ProjectPrimaryTab.GIT -> ProjectGitSection(
                    config = config,
                    currentProjectPath = currentProjectPath,
                    draftPathCount = projectKnowledgeDraftPaths.size,
                    snapshotCount = projectKnowledgeSnapshots.size,
                    mcpToolCount = mcpToolNames.size
                )
            }
        }
    }
}

@Composable
private fun ProjectEditorSection(
    config: ProviderConfig,
    currentProjectPath: String?,
    sessions: List<SessionSummary>,
    onOpenChat: () -> Unit,
    onNewTask: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val projectRoot = remember(currentProjectPath) {
        currentProjectPath
            ?.takeIf { File(it).isDirectory }
            ?.let(::File)
    }
    val entriesByDir = remember(currentProjectPath) { mutableStateMapOf<String, List<ProjectTreeEntry>>() }
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
    var showEditorMenu by remember(currentProjectPath) { mutableStateOf(false) }
    var showMoreMenu by remember(currentProjectPath) { mutableStateOf(false) }
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
        val rootPath = projectRoot?.absolutePath ?: return
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
        val root = projectRoot ?: return
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

    LaunchedEffect(projectRoot?.absolutePath, reloadVersion) {
        entriesByDir.clear()
        if (projectRoot != null) {
            loadDir(projectRoot.absolutePath)
        }
    }

    LaunchedEffect(searchQuery, projectRoot?.absolutePath) {
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
            } ?: "先选择一个项目文件夹，项目页才会显示文件树和编辑器。"
        )
        return
    }

    val visibleEntries = flattenVisibleEntries(
        rootPath = projectRoot.absolutePath,
        entriesByDir = entriesByDir,
        expandedDirs = expandedDirs
    )
    val pageScrollState = rememberScrollState()
    val currentFileName = selectedFilePath?.substringAfterLast(File.separatorChar).orEmpty()
    val currentRelativePath = selectedFilePath
        ?.removePrefix(projectRoot.absolutePath)
        ?.removePrefix(File.separator)
        .orEmpty()

    fun exitEditorPage() {
        selectedFilePath = null
        focusedSearchLine = null
        focusedSearchQuery = null
        aiCompletionCandidate = null
        showAiCompletionDialog = false
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
                .verticalScroll(pageScrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ReasonixInfoCard(title = "", titleVisible = false) {
                Text("项目文件", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "路径: ${projectRoot.absolutePath}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "上面搜文件名，下面像文件管理器一样浏览文件夹和文件；点开文件后会进入单独的编辑二级页。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ReasonixOutlinedActionButton(
                        text = "刷新",
                        onClick = { reloadVersion++ },
                        modifier = Modifier.weight(1f)
                    )
                    ReasonixTagButton(
                        text = "${visibleEntries.size} 项",
                        onClick = {}
                    )
                }
            }

            ReasonixGlassSurface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)
            ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("搜索文件名") },
                        placeholder = { Text("输入文件名关键词") },
                        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) }
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("文件和文件夹", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.weight(1f))
                        if (isSearching) {
                            Text("搜索中", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        } else if (isTreeLoading) {
                            Text("读取中", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    HorizontalDivider()
                    if (searchQuery.isNotBlank()) {
                        val result = searchResult
                        if (result == null || result.hits.isEmpty()) {
                            Text(
                                "没有匹配的文件名",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Text(
                                "命中 ${result.totalCount} 个文件",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            result.hits.forEach { hit ->
                                ProjectSearchResultRow(
                                    hit = hit,
                                    query = searchQuery,
                                    onOpen = { openFile(hit.filePath) }
                                )
                            }
                        }
                    } else {
                        ProjectTreeRow(
                            entry = ProjectTreeEntry(
                                absolutePath = projectRoot.absolutePath,
                                name = projectRoot.name.ifBlank { projectRoot.absolutePath },
                                isDirectory = true
                            ),
                            rootPath = projectRoot.absolutePath,
                            depth = 0,
                            isExpanded = true,
                            isSelected = false,
                            onToggleDir = {},
                            onSelectFile = {}
                        )
                        visibleEntries.forEach { item ->
                            ProjectTreeRow(
                                entry = item.entry,
                                rootPath = projectRoot.absolutePath,
                                depth = item.depth,
                                isExpanded = item.entry.isDirectory && expandedDirs.contains(item.entry.absolutePath),
                                isSelected = false,
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
    } else {
        val editorVerticalScroll = rememberScrollState()
        val editorHorizontalScroll = rememberScrollState()
        val isRawJsonFile = remember(selectedFilePath, language) {
            isJsonLikeProjectFile(selectedFilePath, language)
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

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ReasonixInfoCard(title = "", titleVisible = false) {
                Text(
                    text = currentFileName,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = if (currentRelativePath.isBlank()) projectRoot.name else currentRelativePath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ReasonixOutlinedActionButton(
                        text = "返回文件列表",
                        onClick = { exitEditorPage() },
                        modifier = Modifier.weight(1f)
                    )
                    ReasonixTagButton(
                        text = if (dirty) "有未保存修改" else "已同步",
                        onClick = {}
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ReasonixTagButton(
                        text = if (isRawJsonFile) "原始页" else "代码页",
                        onClick = {}
                    )
                    ReasonixTagButton(
                        text = if (currentRelativePath.isBlank()) "项目根目录" else projectRoot.name,
                        onClick = {}
                    )
                }
            }

            ReasonixGlassSurface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ReasonixTagButton(text = if (isRawJsonFile) "JSON 文件" else (language ?: "自动识别"), onClick = {})
                    ReasonixTagButton(text = "第 $currentLine 行", onClick = {})
                    if (isRawJsonFile) {
                        ProjectEditorMode.entries.forEach { mode ->
                            if (mode == ProjectEditorMode.RAW_JSON || mode != ProjectEditorMode.RAW_JSON) {
                                FilterChip(
                                    selected = editorMode == mode,
                                    onClick = { editorMode = mode },
                                    label = { Text(mode.label) },
                                    enabled = mode != ProjectEditorMode.RAW_JSON || isRawJsonFile
                                )
                            }
                        }
                    } else {
                        ProjectEditorMode.entries
                            .filterNot { it == ProjectEditorMode.RAW_JSON }
                            .forEach { mode ->
                                FilterChip(
                                    selected = editorMode == mode,
                                    onClick = { editorMode = mode },
                                    label = { Text(mode.label) }
                                )
                            }
                    }
                    ReasonixTagButton(text = "搜索", onClick = { showSearchReplaceDialog = true })
                    Box {
                        ReasonixTagButton(text = "行操作", onClick = { showEditorMenu = true })
                        DropdownMenu(
                            expanded = showEditorMenu,
                            onDismissRequest = { showEditorMenu = false }
                        ) {
                            ProjectLineAction.entries.forEach { action ->
                                DropdownMenuItem(
                                    text = { Text(action.label) },
                                    onClick = {
                                        showEditorMenu = false
                                        applyLineAction(action)
                                    }
                                )
                            }
                        }
                    }
                    Box {
                        ReasonixTagButton(text = "更多", onClick = { showMoreMenu = true })
                        DropdownMenu(
                            expanded = showMoreMenu,
                            onDismissRequest = { showMoreMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("切换语法高亮") },
                                onClick = {
                                    showMoreMenu = false
                                    showLanguageDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("错误列表") },
                                onClick = {
                                    showMoreMenu = false
                                    showDiagnosticsDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("处理冲突") },
                                onClick = {
                                    showMoreMenu = false
                                    showConflictResolverDialog = true
                                }
                            )
                        }
                    }
                    ReasonixOutlinedActionButton(
                        text = "后退",
                        onClick = { navigateEditorHistory(backward = true) },
                        enabled = undoStack.isNotEmpty()
                    )
                    ReasonixOutlinedActionButton(
                        text = "前进",
                        onClick = { navigateEditorHistory(backward = false) },
                        enabled = redoStack.isNotEmpty()
                    )
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
                }
            }

            editorError?.let {
                ReasonixGlassSurface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                    shape = RoundedCornerShape(18.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)
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
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "语法高亮: ${language ?: "自动"}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = if (isRawJsonFile) {
                                    "当前文件以原始文本为主，适合整体校正和快速替换。"
                                } else {
                                    "当前编辑区保持独立二级页结构，文件列表返回和状态信息都在这一页内收口。"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (editorDiagnostics.isNotEmpty()) {
                            Spacer(Modifier.width(8.dp))
                            ReasonixTagButton(
                                text = "错误 ${editorDiagnostics.size}",
                                onClick = { showDiagnosticsDialog = true }
                            )
                        }
                        if (conflictBlocks.isNotEmpty()) {
                            Spacer(Modifier.width(6.dp))
                            ReasonixTagButton(
                                text = "冲突 ${conflictBlocks.size}",
                                onClick = { showConflictResolverDialog = true }
                            )
                        }
                    }
                    HorizontalDivider()
                    currentLineDiagnostic?.let { diagnostic ->
                        ReasonixGlassSurface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)
                        ) {
                            Text(
                                text = "第 ${diagnostic.lineNumber} 行: ${diagnostic.message}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        if (isFileLoading) {
                            EmptyEditorState("正在读取文件", "请稍候，正在加载当前文件内容。")
                        } else if (editorMode == ProjectEditorMode.RAW_JSON && isRawJsonFile) {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    text = "原始 JSON",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "参考 fas.json 原始页，保持简单直接的文本编辑体验，适合快速粘贴、校正和整体替换。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                ) {
                                    OutlinedTextField(
                                        value = editorValue,
                                        onValueChange = { updateEditorValue(it) },
                                        modifier = Modifier.fillMaxSize(),
                                        label = { Text("JSON 原文") },
                                        textStyle = MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                            lineHeight = 18.sp
                                        )
                                    )
                                }
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color(0xFF1E1E1E), RoundedCornerShape(18.dp))
                            ) {
                                ProjectCodeEditorPane(
                                    editorValue = editorValue,
                                    onValueChange = { updateEditorValue(it) },
                                    language = language,
                                    searchQuery = editorSearchQuery,
                                    currentMatch = currentSearchMatch,
                                    diagnostics = editorDiagnostics,
                                    verticalScrollState = editorVerticalScroll,
                                    horizontalScrollState = editorHorizontalScroll,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }
                    HorizontalDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .navigationBarsPadding()
                            .padding(bottom = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ReasonixTagButton(
                            text = "快捷插入",
                            onClick = {}
                        )
                        quickInsertActions.forEach { action ->
                            ReasonixTagButton(
                                text = action.label,
                                onClick = { insertQuickAction(action) }
                            )
                        }
                        if (dirty) {
                            ReasonixOutlinedActionButton(
                                text = "保存修改",
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
                                enabled = !isSaving
                            )
                        } else {
                            ReasonixTagButton(
                                text = "已同步",
                                onClick = {}
                            )
                        }
                        Text(
                            text = "第 $currentLine 行",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
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
                            .background(Color(0xFF1E1E1E))
                            .horizontalScroll(rememberScrollState())
                            .padding(12.dp)
                    ) {
                        Text(
                            text = candidate?.text ?: "",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            color = Color(0xFFD4D4D4)
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
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        editorDiagnostics.forEach { diagnostic ->
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { jumpToDiagnostic(diagnostic) },
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.65f)
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
                                        color = MaterialTheme.colorScheme.onErrorContainer
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
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        conflictBlocks.forEachIndexed { index, block ->
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                tonalElevation = 2.dp,
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
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
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    if (block.currentLines.isNotEmpty()) {
                                        Text(
                                            text = "当前片段: ${block.currentLines.joinToString(" ").trim().take(80)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (block.incomingLines.isNotEmpty()) {
                                        Text(
                                            text = "对方片段: ${block.incomingLines.joinToString(" ").trim().take(80)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
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
private fun EmptyEditorState(title: String, message: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ProjectSearchResultRow(
    hit: ProjectSearchHitUi,
    query: String,
    onOpen: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = highlightSearchKeyword(hit.relativePath, query),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = buildString {
                append(projectSearchTypeLabel(hit.fileType))
                hit.lineNumber?.let { append(" · 第 $it 行") }
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = highlightSearchKeyword(hit.preview.ifBlank { "文件名匹配" }, query),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ProjectFoldableCodePreview(
    content: String,
    language: String,
    query: String?,
    foldRegions: List<ProjectFoldRegion>,
    collapsedStartLines: Set<Int>,
    onToggleFold: (Int) -> Unit
) {
    val lines = remember(content) { content.lines() }
    val lineNumberWidth = lines.size.toString().length.coerceAtLeast(2)
    val regionsByStart = remember(foldRegions) { foldRegions.associateBy { it.startLine } }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1E1E1E))
            .horizontalScroll(rememberScrollState())
            .padding(12.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            var lineNumber = 1
            while (lineNumber <= lines.size) {
                val region = regionsByStart[lineNumber]
                if (region != null && collapsedStartLines.contains(region.startLine)) {
                    FoldedPreviewRow(
                        region = region,
                        lineNumberWidth = lineNumberWidth,
                        language = language,
                        query = query,
                        onToggle = { onToggleFold(region.startLine) }
                    )
                    lineNumber = region.endLine + 1
                } else {
                    PreviewCodeLine(
                        lineNumber = lineNumber,
                        lineText = lines[lineNumber - 1],
                        lineNumberWidth = lineNumberWidth,
                        language = language,
                        query = query,
                        onClick = region?.let { { onToggleFold(it.startLine) } }
                    )
                    lineNumber++
                }
            }
        }
    }
}

@Composable
private fun PreviewCodeLine(
    lineNumber: Int,
    lineText: String,
    lineNumberWidth: Int,
    language: String,
    query: String?,
    onClick: (() -> Unit)?
) {
    val annotated = remember(lineNumber, lineText, lineNumberWidth, language, query) {
        buildPreviewCodeLine(
            lineNumber = lineNumber,
            lineText = lineText,
            lineNumberWidth = lineNumberWidth,
            language = language,
            query = query
        )
    }
    Text(
        text = annotated,
        modifier = Modifier
            .fillMaxWidth()
            .let { base ->
                if (onClick != null) {
                    base.clickable(onClick = onClick)
                } else {
                    base
                }
            }
            .padding(vertical = 1.dp),
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        color = Color(0xFFD4D4D4)
    )
}

@Composable
private fun FoldedPreviewRow(
    region: ProjectFoldRegion,
    lineNumberWidth: Int,
    language: String,
    query: String?,
    onToggle: () -> Unit
) {
    val annotated = remember(region, lineNumberWidth, language, query) {
        buildPreviewCodeLine(
            lineNumber = region.startLine,
            lineText = region.headerLine,
            lineNumberWidth = lineNumberWidth,
            language = language,
            query = query
        )
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 1.dp)
    ) {
        Text(
            text = annotated,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = Color(0xFFD4D4D4)
        )
        Text(
            text = " ".repeat(lineNumberWidth + 3) + "... 已折叠 ${region.endLine - region.startLine} 行，点击展开",
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = Color(0xFF9CDCFE)
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
    onToggleDir: (String) -> Unit,
    onSelectFile: (String) -> Unit
) {
    val background = if (isSelected) {
        MaterialTheme.colorScheme.secondaryContainer
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
            .padding(start = 12.dp + (depth * 14).dp, top = 8.dp, end = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (entry.isDirectory) {
            Text(
                text = if (isExpanded) "v" else ">",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "[DIR]",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        } else {
            Spacer(Modifier.width(24.dp))
            Text(
                text = "[FILE]",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                text = if (depth == 0) entry.name else entry.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!entry.isDirectory) {
                Text(
                    text = relativeProjectPath(rootPath, entry.absolutePath),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("项目配置", style = MaterialTheme.typography.titleMedium)

        Text("审批模式", style = MaterialTheme.typography.labelMedium)
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
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth()
            ) {
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
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth()
            ) {
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
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth()
            ) {
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
private fun ProjectGitSection(
    config: ProviderConfig,
    currentProjectPath: String?,
    draftPathCount: Int,
    snapshotCount: Int,
    mcpToolCount: Int
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val downloadHistory = remember(currentProjectPath) { mutableStateListOf<ProjectGitHubDownloadRecordUi>() }
    var gitState by remember(currentProjectPath) { mutableStateOf(ProjectGitStatusUi.empty(currentProjectPath)) }
    var isGitLoading by remember(currentProjectPath) { mutableStateOf(false) }
    var isGitActionRunning by remember(currentProjectPath) { mutableStateOf(false) }
    var feedbackMessage by remember(currentProjectPath) { mutableStateOf<String?>(null) }
    var showCommitDialog by remember(currentProjectPath) { mutableStateOf(false) }
    var commitTitleDraft by remember(currentProjectPath) { mutableStateOf("") }
    var commitDetailDraft by remember(currentProjectPath) { mutableStateOf("") }
    var diffPreview by remember(currentProjectPath) { mutableStateOf<ProjectGitDiffPreviewUi?>(null) }
    var showBranchDialog by remember(currentProjectPath) { mutableStateOf(false) }
    var newBranchName by remember(currentProjectPath) { mutableStateOf("") }
    var githubActionsState by remember(currentProjectPath) { mutableStateOf(ProjectGitHubActionsState.empty()) }
    var isGitHubLoading by remember(currentProjectPath) { mutableStateOf(false) }
    var isGitHubActionRunning by remember(currentProjectPath) { mutableStateOf(false) }
    var workflowDispatchTarget by remember(currentProjectPath) { mutableStateOf<ProjectGitHubWorkflowUi?>(null) }
    var workflowDispatchRefDraft by remember(currentProjectPath) { mutableStateOf("") }
    var artifactDialogState by remember(currentProjectPath) { mutableStateOf<ProjectGitHubArtifactDialogUi?>(null) }
    var workflowRunDetailDialogState by remember(currentProjectPath) { mutableStateOf<ProjectGitHubWorkflowRunDetailUi?>(null) }
    var showInitGitDialog by remember(currentProjectPath) { mutableStateOf(false) }
    var initBranchDraft by remember(currentProjectPath) { mutableStateOf("main") }
    var showCreateGitHubRepoDialog by remember(currentProjectPath) { mutableStateOf(false) }
    var createGitHubRepoNameDraft by remember(currentProjectPath) {
        mutableStateOf(suggestProjectGitHubRepoName(currentProjectPath))
    }
    var createGitHubRepoDescriptionDraft by remember(currentProjectPath) { mutableStateOf("") }
    var createGitHubRepoPrivateFlag by remember(currentProjectPath) { mutableStateOf(false) }
    var createGitHubRepoBindOriginFlag by remember(currentProjectPath) { mutableStateOf(true) }
    var showCreateReleaseDialog by remember(currentProjectPath) { mutableStateOf(false) }
    var releaseEditTarget by remember(currentProjectPath) { mutableStateOf<ProjectGitHubReleaseUi?>(null) }
    var releaseNameDraft by remember(currentProjectPath) { mutableStateOf("") }
    var releaseTagDraft by remember(currentProjectPath) { mutableStateOf("") }
    var releaseBodyDraft by remember(currentProjectPath) { mutableStateOf("") }
    var releaseDraftFlag by remember(currentProjectPath) { mutableStateOf(false) }
    var releasePrereleaseFlag by remember(currentProjectPath) { mutableStateOf(false) }
    var releaseAssetDialogState by remember(currentProjectPath) { mutableStateOf<ProjectGitHubReleaseAssetDialogUi?>(null) }

    fun resetCommitDraft() {
        commitTitleDraft = ""
        commitDetailDraft = ""
    }

    fun resetReleaseDraft() {
        releaseNameDraft = ""
        releaseTagDraft = ""
        releaseBodyDraft = ""
        releaseDraftFlag = false
        releasePrereleaseFlag = false
    }

    fun resetCreateGitHubRepoDraft() {
        createGitHubRepoNameDraft = suggestProjectGitHubRepoName(currentProjectPath)
        createGitHubRepoDescriptionDraft = ""
        createGitHubRepoPrivateFlag = false
        createGitHubRepoBindOriginFlag = true
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
        sourceUrl: String?
    ) {
        downloadHistory.add(
            0,
            ProjectGitHubDownloadRecordUi(
                id = UUID.randomUUID().toString(),
                typeLabel = typeLabel,
                title = title,
                fileName = fileName,
                createdAtMillis = System.currentTimeMillis(),
                downloadId = downloadId,
                sourceUrl = sourceUrl
            )
        )
        while (downloadHistory.size > 12) {
            downloadHistory.removeAt(downloadHistory.lastIndex)
        }
    }

    fun refreshGitState() {
        val projectPath = currentProjectPath
        if (projectPath.isNullOrBlank()) {
            gitState = ProjectGitStatusUi.empty(projectPath)
            return
        }
        scope.launch {
            isGitLoading = true
            gitState = withContext(Dispatchers.IO) { loadProjectGitStatus(projectPath) }
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
            return
        }
        if (repo == null) {
            githubActionsState = ProjectGitHubActionsState.empty().copy(
                errorMessage = "当前 origin 远端还不能识别成 GitHub 仓库地址。"
            )
            return
        }
        val token = config.githubToken.trim()
        if (token.isBlank()) {
            githubActionsState = ProjectGitHubActionsState.empty(repo).copy(
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

    LaunchedEffect(currentProjectPath) {
        refreshGitState()
    }

    LaunchedEffect(gitState.remoteUrl, gitState.isRepository, config.githubToken, config.githubApiBaseUrl) {
        if (!gitState.isRepository) {
            githubActionsState = ProjectGitHubActionsState.empty()
        } else {
            refreshGitHubActions()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Git", style = MaterialTheme.typography.titleMedium)
        if (currentProjectPath.isNullOrBlank()) {
            Text(
                "先选择一个项目目录，Git 页才能识别仓库状态。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        gitState.repoRoot?.let { "仓库根目录: $it" } ?: "当前目录未识别到 Git 仓库",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        if (gitState.branchSummary.isNotBlank()) "当前分支: ${gitState.branchSummary}" else "当前分支: 未知",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    gitState.lastCommitSummary?.takeIf { it.isNotBlank() }?.let { summary ->
                        Text(
                            "最近提交: $summary",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        "已暂存 ${gitState.stagedFiles.size} · 已修改 ${gitState.modifiedFiles.size} · 未跟踪 ${gitState.untrackedFiles.size} · 冲突 ${gitState.conflictedFiles.size}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
                            runGitCommand(repoRoot, "fetch --all --prune")
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
                            runGitCommand(repoRoot, "pull --ff-only")
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
                            runGitCommand(repoRoot, "push")
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
                            runGitCommand(repoRoot, "add -A")
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
                    enabled = gitState.isRepository && !isGitLoading && !isGitActionRunning
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
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            feedbackMessage?.takeIf { it.isNotBlank() }?.let { message ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                    )
                }
            }

            gitState.errorMessage?.takeIf { it.isNotBlank() }?.let { error ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                    )
                }
            }

            if (!gitState.isRepository || !gitState.hasRemote) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = if (!gitState.isRepository) "仓库初始化" else "GitHub 仓库绑定",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = when {
                                !gitState.isRepository ->
                                    "当前项目还没有 `.git`。可以先初始化本地 Git，再一键创建 GitHub 仓库并绑定 origin。"
                                else ->
                                    "当前本地仓库还没有 origin 远端。可以直接新建 GitHub 仓库并绑定。"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!gitState.isRepository) {
                                Button(
                                    onClick = { showInitGitDialog = true },
                                    enabled = !isGitLoading && !isGitActionRunning
                                ) {
                                    Text("初始化 Git")
                                }
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
            }

            if (!gitState.isRepository) {
                Text(
                    "当前项目目录下没有发现 `.git`，或设备环境里还不能执行 Git 命令。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                ProjectGitRemoteSummaryCard(
                    remoteUrl = gitState.remoteUrl,
                    upstreamBranch = gitState.upstreamBranch,
                    aheadCount = gitState.aheadCount,
                    behindCount = gitState.behindCount,
                    remoteBranchCount = gitState.remoteBranches.size
                )
                ProjectGitHubActionsSection(
                    state = githubActionsState,
                    isLoading = isGitHubLoading,
                    isActionRunning = isGitHubActionRunning,
                    tokenConfigured = config.githubToken.isNotBlank(),
                    onRefresh = { refreshGitHubActions() },
                    onOpenRepo = {
                        openGitHubPage(
                            githubActionsState.repoHtmlUrl,
                            "当前仓库还没有可打开的 GitHub 页面地址。"
                        )
                    },
                    onRunWorkflow = { workflow ->
                        workflowDispatchTarget = workflow
                        workflowDispatchRefDraft = gitState.currentBranch
                            ?: githubActionsState.defaultBranch
                            ?: "main"
                    },
                    onOpenWorkflowPage = { workflow ->
                        openGitHubPage(
                            workflow.htmlUrl,
                            "当前工作流还没有可打开的 GitHub 页面地址。"
                        )
                    },
                    onOpenArtifacts = { run ->
                        val repo = githubActionsState.repo ?: return@ProjectGitHubActionsSection
                        val token = config.githubToken.trim()
                        if (token.isBlank()) {
                            feedbackMessage = "请先在设置页填写 GitHub Token。"
                            return@ProjectGitHubActionsSection
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
                    },
                    onOpenRunPage = { run ->
                        openGitHubPage(
                            run.htmlUrl,
                            "当前运行记录还没有可打开的 GitHub 页面地址。"
                        )
                    },
                    onDownloadRunLogs = { run ->
                        val repo = githubActionsState.repo ?: return@ProjectGitHubActionsSection
                        val token = config.githubToken.trim()
                        if (token.isBlank()) {
                            feedbackMessage = "请先在设置页填写 GitHub Token。"
                            return@ProjectGitHubActionsSection
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
                            sourceUrl = run.htmlUrl
                        )
                        feedbackMessage = "已开始下载 ${result.fileName}"
                    },
                    onOpenRunDetail = { run ->
                        val repo = githubActionsState.repo ?: return@ProjectGitHubActionsSection
                        val token = config.githubToken.trim()
                        if (token.isBlank()) {
                            feedbackMessage = "请先在设置页填写 GitHub Token。"
                            return@ProjectGitHubActionsSection
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
                )
                ProjectGitHubReleaseSection(
                    releases = githubActionsState.releases,
                    isActionRunning = isGitHubActionRunning,
                    onCreateRelease = {
                        resetReleaseDraft()
                        releaseTagDraft = gitState.currentBranch
                            ?.substringAfterLast('/')
                            ?.takeIf { it.isNotBlank() }
                            ?.let { "v-$it" }
                            .orEmpty()
                        showCreateReleaseDialog = true
                    },
                    onEditRelease = { release ->
                        releaseEditTarget = release
                        releaseNameDraft = release.name
                        releaseTagDraft = release.tagName
                        releaseBodyDraft = release.body
                        releaseDraftFlag = release.isDraft
                        releasePrereleaseFlag = release.isPrerelease
                    },
                    onToggleReleaseMode = { release, makeDraft ->
                        val repo = githubActionsState.repo ?: return@ProjectGitHubReleaseSection
                        val token = config.githubToken.trim()
                        if (token.isBlank()) {
                            feedbackMessage = "请先在设置页填写 GitHub Token。"
                            return@ProjectGitHubReleaseSection
                        }
                        runGitHubAction(if (makeDraft) "已切回草稿 Release" else "已发布为正式 Release") {
                            updateProjectGitHubRelease(
                                repo = repo,
                                releaseId = release.id,
                                tagName = release.tagName,
                                releaseName = release.name,
                                body = release.body,
                                isDraft = makeDraft,
                                isPrerelease = if (makeDraft) release.isPrerelease else false,
                                token = token,
                                apiBaseUrl = config.getGitHubApiBaseUrl()
                            )
                        }
                    },
                    onTogglePrerelease = { release, makePrerelease ->
                        val repo = githubActionsState.repo ?: return@ProjectGitHubReleaseSection
                        val token = config.githubToken.trim()
                        if (token.isBlank()) {
                            feedbackMessage = "请先在设置页填写 GitHub Token。"
                            return@ProjectGitHubReleaseSection
                        }
                        runGitHubAction(if (makePrerelease) "已标记为预发布 Release" else "已取消预发布标记") {
                            updateProjectGitHubRelease(
                                repo = repo,
                                releaseId = release.id,
                                tagName = release.tagName,
                                releaseName = release.name,
                                body = release.body,
                                isDraft = release.isDraft,
                                isPrerelease = makePrerelease,
                                token = token,
                                apiBaseUrl = config.getGitHubApiBaseUrl()
                            )
                        }
                    },
                    onDeleteRelease = { release ->
                        val repo = githubActionsState.repo ?: return@ProjectGitHubReleaseSection
                        val token = config.githubToken.trim()
                        if (token.isBlank()) {
                            feedbackMessage = "请先在设置页填写 GitHub Token。"
                            return@ProjectGitHubReleaseSection
                        }
                        runGitHubAction("已删除 Release ${release.name.ifBlank { release.tagName }}") {
                            deleteProjectGitHubRelease(
                                repo = repo,
                                releaseId = release.id,
                                token = token,
                                apiBaseUrl = config.getGitHubApiBaseUrl()
                            )
                        }
                    },
                    onOpenReleasePage = { release ->
                        openGitHubPage(
                            release.htmlUrl,
                            "当前 Release 还没有可打开的 GitHub 页面地址。"
                        )
                    },
                    onOpenAssets = { release ->
                        releaseAssetDialogState = ProjectGitHubReleaseAssetDialogUi(
                            releaseTitle = release.name.ifBlank { release.tagName },
                            assets = release.assets,
                            releaseHtmlUrl = release.htmlUrl
                        )
                    }
                )
                ProjectGitHubDownloadHistorySection(
                    downloads = downloadHistory,
                    onOpenSystemDownloads = { openSystemDownloads() },
                    onOpenSource = { item ->
                        openGitHubPage(
                            item.sourceUrl,
                            "这条下载记录暂时没有可打开的来源页面。"
                        )
                    }
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
                            runGitCommand(repoRoot, "restore --staged -- ${shellQuote(change.actionPath)}")
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
                            runGitCommand(repoRoot, "add -- ${shellQuote(change.actionPath)}")
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
                            runGitCommand(repoRoot, "add -- ${shellQuote(change.actionPath)}")
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
                        val projectPath = currentProjectPath ?: return@Button
                        val branchName = initBranchDraft.trim().ifBlank { "main" }
                        showInitGitDialog = false
                        runGitAction("已初始化 Git 仓库") {
                            initializeProjectGitRepository(projectPath, branchName)
                        }
                    },
                    enabled = currentProjectPath != null && !isGitLoading && !isGitActionRunning
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
                        val projectPath = currentProjectPath ?: return@Button
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
                                val cloneUrl = createResult.cloneUrl
                                if (cloneUrl.isNullOrBlank()) {
                                    isGitHubActionRunning = false
                                    feedbackMessage = "GitHub 已创建仓库，但没有返回可绑定的远端地址"
                                    return@launch
                                }
                                val bindResult = withContext(Dispatchers.IO) {
                                    bindProjectGitHubRemote(
                                        projectPath = projectPath,
                                        remoteUrl = cloneUrl,
                                        preferredBranch = gitState.currentBranch ?: initBranchDraft.trim().ifBlank { "main" }
                                    )
                                }
                                message = if (bindResult.success) {
                                    "$message，并已绑定 origin"
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
                        text = "会使用当前设置页里的 GitHub Token 创建仓库。绑定 origin 后，项目页里的 GitHub 工作流和 Release 能直接识别这个仓库。",
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
                            runGitCommand(repoRoot, "commit -m ${shellQuote(message)}")
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
                    Text(
                        text = "已暂存 ${gitState.stagedFiles.size} 个文件",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (gitState.stagedFiles.isNotEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                gitState.stagedFiles.take(6).forEach { file ->
                                    Text(
                                        text = "${file.statusLabel} · ${file.displayPath}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (gitState.stagedFiles.size > 6) {
                                    Text(
                                        text = "还有 ${gitState.stagedFiles.size - 6} 个文件未展开",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f)
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "最终提交信息预览",
                                    style = MaterialTheme.typography.labelMedium
                                )
                                Text(
                                    text = finalCommitMessage,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
                            runGitCommand(repoRoot, "checkout -b ${shellQuote(targetBranch)}")
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
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 260.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        gitState.localBranches.forEach { branch ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
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
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    if (!branch.isCurrent) {
                                        OutlinedButton(
                                            onClick = {
                                                val repoRoot = gitState.repoRoot ?: return@OutlinedButton
                                                showBranchDialog = false
                                                runGitAction("已切换到 ${branch.name}") {
                                                    runGitCommand(repoRoot, "checkout ${shellQuote(branch.name)}")
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
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            gitState.remoteBranches.forEach { remoteBranch ->
                                val suggestedLocalName = remoteBranch.substringAfter('/', remoteBranch)
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
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
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        OutlinedButton(
                                            onClick = {
                                                val repoRoot = gitState.repoRoot ?: return@OutlinedButton
                                                showBranchDialog = false
                                                runGitAction("已跟踪并切换到 $suggestedLocalName") {
                                                    runGitCommand(repoRoot, "checkout --track ${shellQuote(remoteBranch)}")
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
                    Text(
                        text = "通常填当前分支或默认分支，例如 `${gitState.currentBranch ?: githubActionsState.defaultBranch ?: "main"}`。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        )
    }

    artifactDialogState?.let { dialog ->
        ReasonixAlertDialog(
            onDismissRequest = { artifactDialogState = null },
            confirmButton = {
                TextButton(onClick = { artifactDialogState = null }) {
                    Text("关闭")
                }
            },
            title = { Text("工作流产物") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = dialog.runTitle,
                        style = MaterialTheme.typography.titleSmall
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        dialog.runHtmlUrl?.takeIf { it.isNotBlank() }?.let {
                            TextButton(
                                onClick = {
                                    openGitHubPage(
                                        dialog.runHtmlUrl,
                                        "当前运行记录还没有可打开的 GitHub 页面地址。"
                                    )
                                }
                            ) {
                                Text("运行页")
                            }
                        }
                        OutlinedButton(
                            onClick = {
                                val repo = githubActionsState.repo ?: return@OutlinedButton
                                val token = config.githubToken.trim()
                                if (token.isBlank()) {
                                    feedbackMessage = "请先在设置页填写 GitHub Token。"
                                    return@OutlinedButton
                                }
                                val result = enqueueProjectGitHubWorkflowLogsDownload(
                                    context = context,
                                    repo = repo,
                                    runId = dialog.runId,
                                    runDisplayTitle = dialog.runTitle,
                                    token = token,
                                    apiBaseUrl = config.getGitHubApiBaseUrl()
                                )
                                recordGitHubDownload(
                                    typeLabel = "工作流日志",
                                    title = dialog.runTitle,
                                    fileName = result.fileName,
                                    downloadId = result.downloadId,
                                    sourceUrl = dialog.runHtmlUrl
                                )
                                feedbackMessage = "已开始下载 ${result.fileName}"
                            }
                        ) {
                            Text("日志 ZIP")
                        }
                    }
                    if (dialog.artifacts.isEmpty()) {
                        Text(
                            text = "这次运行暂时没有可下载的产物。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        dialog.artifacts.forEach { artifact ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = artifact.name,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        text = "大小 ${artifact.sizeLabel} · 更新 ${artifact.updatedAt}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    if (artifact.expired) {
                                        Text(
                                            text = "该产物已过期，GitHub 不再提供下载。",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    } else {
                                        OutlinedButton(
                                            onClick = {
                                                val repo = githubActionsState.repo ?: return@OutlinedButton
                                                val token = config.githubToken.trim()
                                                if (token.isBlank()) {
                                                    feedbackMessage = "请先在设置页填写 GitHub Token。"
                                                    return@OutlinedButton
                                                }
                                                val result = enqueueProjectGitHubArtifactDownload(
                                                    context = context,
                                                    repo = repo,
                                                    artifact = artifact,
                                                    token = token
                                                )
                                                recordGitHubDownload(
                                                    typeLabel = "工作流产物",
                                                    title = artifact.name,
                                                    fileName = result.fileName,
                                                    downloadId = result.downloadId,
                                                    sourceUrl = dialog.runHtmlUrl
                                                )
                                                feedbackMessage = "已开始下载 ${result.fileName}"
                                            }
                                        ) {
                                            Text("下载")
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

    workflowRunDetailDialogState?.let { detail ->
        ReasonixAlertDialog(
            onDismissRequest = { workflowRunDetailDialogState = null },
            confirmButton = {
                TextButton(onClick = { workflowRunDetailDialogState = null }) {
                    Text("关闭")
                }
            },
            dismissButton = {
                detail.htmlUrl?.takeIf { it.isNotBlank() }?.let {
                    TextButton(
                        onClick = {
                            openGitHubPage(
                                detail.htmlUrl,
                                "当前运行详情还没有可打开的 GitHub 页面地址。"
                            )
                        }
                    ) {
                        Text("网页")
                    }
                }
            },
            title = { Text("运行详情") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 460.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = detail.title,
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = "${detail.statusLabel} · ${detail.headBranch} · ${detail.event}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "更新于 ${detail.updatedAt}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        detail.htmlUrl?.takeIf { it.isNotBlank() }?.let {
                            TextButton(
                                onClick = {
                                    openGitHubPage(
                                        detail.htmlUrl,
                                        "当前运行详情还没有可打开的 GitHub 页面地址。"
                                    )
                                }
                            ) {
                                Text("网页")
                            }
                        }
                        OutlinedButton(
                            onClick = {
                                val repo = githubActionsState.repo ?: return@OutlinedButton
                                val token = config.githubToken.trim()
                                if (token.isBlank()) {
                                    feedbackMessage = "请先在设置页填写 GitHub Token。"
                                    return@OutlinedButton
                                }
                                val result = enqueueProjectGitHubWorkflowLogsDownload(
                                    context = context,
                                    repo = repo,
                                    runId = detail.id,
                                    runDisplayTitle = detail.title,
                                    token = token,
                                    apiBaseUrl = config.getGitHubApiBaseUrl()
                                )
                                recordGitHubDownload(
                                    typeLabel = "工作流日志",
                                    title = detail.title,
                                    fileName = result.fileName,
                                    downloadId = result.downloadId,
                                    sourceUrl = detail.htmlUrl
                                )
                                feedbackMessage = "已开始下载 ${result.fileName}"
                            }
                        ) {
                            Text("日志 ZIP")
                        }
                    }
                    detail.issueSummaries.takeIf { it.isNotEmpty() }?.let { issues ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.28f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "日志摘要",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                issues.forEach { issue ->
                                    Text(
                                        text = issue,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }
                        }
                    }
                    if (detail.jobs.isEmpty()) {
                        Text(
                            text = "这次运行还没有返回 job 详情，可能仍在初始化。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        detail.jobs.forEach { job ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(job.name, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        text = job.statusLabel,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    if (job.startedAt.isNotBlank() || job.completedAt.isNotBlank()) {
                                        Text(
                                            text = buildString {
                                                if (job.startedAt.isNotBlank()) {
                                                    append("开始 ${job.startedAt}")
                                                }
                                                if (job.completedAt.isNotBlank()) {
                                                    if (isNotEmpty()) append(" · ")
                                                    append("结束 ${job.completedAt}")
                                                }
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    job.steps.forEach { step ->
                                        Text(
                                            text = buildString {
                                                append("• ")
                                                if (step.number > 0) {
                                                    append("#${step.number} ")
                                                }
                                                append(step.name)
                                                append(" · ")
                                                append(step.statusLabel)
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        )
    }

    releaseEditTarget?.let { release ->
        ReasonixAlertDialog(
            onDismissRequest = { releaseEditTarget = null },
            confirmButton = {
                Button(
                    onClick = {
                        val repo = githubActionsState.repo ?: return@Button
                        val token = config.githubToken.trim()
                        val tag = releaseTagDraft.trim()
                        if (token.isBlank() || tag.isBlank()) return@Button
                        releaseEditTarget = null
                        runGitHubAction("Release 已更新") {
                            updateProjectGitHubRelease(
                                repo = repo,
                                releaseId = release.id,
                                tagName = tag,
                                releaseName = releaseNameDraft.trim(),
                                body = releaseBodyDraft,
                                isDraft = releaseDraftFlag,
                                isPrerelease = releasePrereleaseFlag,
                                token = token,
                                apiBaseUrl = config.getGitHubApiBaseUrl()
                            )
                        }
                    },
                    enabled = releaseTagDraft.isNotBlank() && !isGitHubActionRunning
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
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = releaseTagDraft,
                        onValueChange = { releaseTagDraft = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Tag") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = releaseNameDraft,
                        onValueChange = { releaseNameDraft = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("标题") },
                        placeholder = { Text("可留空，GitHub 会显示 Tag") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = releaseBodyDraft,
                        onValueChange = { releaseBodyDraft = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("发布说明") },
                        minLines = 6,
                        maxLines = 12
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = releaseDraftFlag,
                            onClick = {
                                releaseDraftFlag = !releaseDraftFlag
                                if (releaseDraftFlag) {
                                    releasePrereleaseFlag = false
                                }
                            },
                            label = { Text("草稿") }
                        )
                        FilterChip(
                            selected = releasePrereleaseFlag,
                            onClick = {
                                val next = !releasePrereleaseFlag
                                releasePrereleaseFlag = next
                                if (next) {
                                    releaseDraftFlag = false
                                }
                            },
                            label = { Text("预发布") }
                        )
                    }
                    Text(
                        text = if (releaseDraftFlag) "当前将保存为草稿 Release" else if (releasePrereleaseFlag) "当前将保存为预发布 Release" else "当前将保存为正式 Release",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
                        val repo = githubActionsState.repo ?: return@Button
                        val token = config.githubToken.trim()
                        val tag = releaseTagDraft.trim()
                        if (token.isBlank() || tag.isBlank()) return@Button
                        showCreateReleaseDialog = false
                        runGitHubAction("已创建 Release ${releaseNameDraft.ifBlank { tag }}") {
                            createProjectGitHubRelease(
                                repo = repo,
                                tagName = tag,
                                releaseName = releaseNameDraft.trim(),
                                body = releaseBodyDraft,
                                isDraft = releaseDraftFlag,
                                isPrerelease = releasePrereleaseFlag,
                                token = token,
                                apiBaseUrl = config.getGitHubApiBaseUrl()
                            )
                        }
                        resetReleaseDraft()
                    },
                    enabled = releaseTagDraft.isNotBlank() && !isGitHubActionRunning
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
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = releaseTagDraft,
                        onValueChange = { releaseTagDraft = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Tag") },
                        placeholder = { Text("例如：v1.2.0") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = releaseNameDraft,
                        onValueChange = { releaseNameDraft = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("标题") },
                        placeholder = { Text("例如：六月版本更新") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = releaseBodyDraft,
                        onValueChange = { releaseBodyDraft = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("发布说明") },
                        minLines = 6,
                        maxLines = 12
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = releaseDraftFlag,
                            onClick = {
                                releaseDraftFlag = !releaseDraftFlag
                                if (releaseDraftFlag) {
                                    releasePrereleaseFlag = false
                                }
                            },
                            label = { Text("草稿") }
                        )
                        FilterChip(
                            selected = releasePrereleaseFlag,
                            onClick = {
                                val next = !releasePrereleaseFlag
                                releasePrereleaseFlag = next
                                if (next) {
                                    releaseDraftFlag = false
                                }
                            },
                            label = { Text("预发布") }
                        )
                    }
                    Text(
                        text = if (releaseDraftFlag) "创建后先保留为草稿" else if (releasePrereleaseFlag) "创建后会标记为预发布" else "创建后会作为正式 Release 展示",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        )
    }

    releaseAssetDialogState?.let { dialog ->
        ReasonixAlertDialog(
            onDismissRequest = { releaseAssetDialogState = null },
            confirmButton = {
                TextButton(onClick = { releaseAssetDialogState = null }) {
                    Text("关闭")
                }
            },
            title = { Text("Release 资产") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = dialog.releaseTitle,
                        style = MaterialTheme.typography.titleSmall
                    )
                    dialog.releaseHtmlUrl?.takeIf { it.isNotBlank() }?.let {
                        TextButton(
                            onClick = {
                                openGitHubPage(
                                    dialog.releaseHtmlUrl,
                                    "当前 Release 还没有可打开的 GitHub 页面地址。"
                                )
                            }
                        ) {
                            Text("Release 页面")
                        }
                    }
                    if (dialog.assets.isEmpty()) {
                        Text(
                            text = "这个 Release 还没有可下载资产。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        dialog.assets.forEach { asset ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(asset.name, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        text = "大小 ${asset.sizeLabel} · 更新 ${asset.updatedAt}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        asset.browserDownloadUrl?.takeIf { it.isNotBlank() }?.let {
                                            TextButton(
                                                onClick = {
                                                    openGitHubPage(
                                                        asset.browserDownloadUrl,
                                                        "当前资产还没有可打开的网页下载地址。"
                                                    )
                                                }
                                            ) {
                                                Text("网页")
                                            }
                                        }
                                        OutlinedButton(
                                            onClick = {
                                                val repo = githubActionsState.repo ?: return@OutlinedButton
                                                val token = config.githubToken.trim()
                                                if (token.isBlank()) {
                                                    feedbackMessage = "请先在设置页填写 GitHub Token。"
                                                    return@OutlinedButton
                                                }
                                                val result = enqueueProjectGitHubReleaseAssetDownload(
                                                    context = context,
                                                    repo = repo,
                                                    asset = asset,
                                                    token = token
                                                )
                                                recordGitHubDownload(
                                                    typeLabel = "Release 资产",
                                                    title = asset.name,
                                                    fileName = result.fileName,
                                                    downloadId = result.downloadId,
                                                    sourceUrl = asset.browserDownloadUrl ?: dialog.releaseHtmlUrl
                                                )
                                                feedbackMessage = "已开始下载 ${result.fileName}"
                                            }
                                        ) {
                                            Text("下载")
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
        )
    }
}

@Composable
private fun ProjectGitFileGroup(
    title: String,
    items: List<ProjectGitFileChangeUi>,
    actionLabel: String,
    emptyText: String,
    onAction: (ProjectGitFileChangeUi) -> Unit,
    onOpenDiff: (ProjectGitFileChangeUi) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        if (items.isEmpty()) {
            Text(
                emptyText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            items.forEach { change ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(change.displayPath, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            change.statusLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { onOpenDiff(change) }) {
                                Text("差异")
                            }
                            OutlinedButton(onClick = { onAction(change) }) {
                                Text(actionLabel)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectGitBranchSummaryCard(
    currentBranch: String?,
    branchCount: Int,
    onManageBranches: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("分支", style = MaterialTheme.typography.titleSmall)
                Text(
                    "当前: ${currentBranch ?: "未知"} · 本地分支: $branchCount",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedButton(onClick = onManageBranches) {
                Text("管理")
            }
        }
    }
}

@Composable
private fun ProjectGitRemoteSummaryCard(
    remoteUrl: String?,
    upstreamBranch: String?,
    aheadCount: Int,
    behindCount: Int,
    remoteBranchCount: Int
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("远端", style = MaterialTheme.typography.titleSmall)
            Text(
                remoteUrl ?: "未配置 origin 远端",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "上游: ${upstreamBranch ?: "未设置"} · ahead $aheadCount / behind $behindCount · 远端分支 $remoteBranchCount",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ProjectGitHubActionsSection(
    state: ProjectGitHubActionsState,
    isLoading: Boolean,
    isActionRunning: Boolean,
    tokenConfigured: Boolean,
    onRefresh: () -> Unit,
    onOpenRepo: () -> Unit,
    onRunWorkflow: (ProjectGitHubWorkflowUi) -> Unit,
    onOpenWorkflowPage: (ProjectGitHubWorkflowUi) -> Unit,
    onOpenArtifacts: (ProjectGitHubWorkflowRunUi) -> Unit,
    onOpenRunPage: (ProjectGitHubWorkflowRunUi) -> Unit,
    onDownloadRunLogs: (ProjectGitHubWorkflowRunUi) -> Unit,
    onOpenRunDetail: (ProjectGitHubWorkflowRunUi) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.28f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
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
                        Text("GitHub 工作流", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = state.repo?.let { "${it.owner}/${it.repo}" } ?: "当前远端还没有解析到 owner/repo",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        state.viewerLogin?.let { login ->
                            Text(
                                text = "已登录 Token: @$login",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        state.defaultBranch?.let { branch ->
                            Text(
                                text = "默认分支: $branch · 工作流 ${state.workflows.size} · 最近运行 ${state.recentRuns.size}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    OutlinedButton(
                        onClick = onRefresh,
                        enabled = !isLoading && !isActionRunning
                    ) {
                        Text("刷新")
                    }
                }
                state.repoHtmlUrl?.takeIf { it.isNotBlank() }?.let {
                    TextButton(
                        onClick = onOpenRepo,
                        enabled = !isLoading && !isActionRunning
                    ) {
                        Text("打开仓库")
                    }
                }
                when {
                    isLoading -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.width(16.dp), strokeWidth = 2.dp)
                            Text(
                                text = "正在读取 GitHub 工作流...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    state.errorMessage?.isNotBlank() == true -> {
                        Text(
                            text = state.errorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    !tokenConfigured -> {
                        Text(
                            text = "先去设置页填 GitHub Token，工作流、Release 和产物下载才能真正联动。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        if (state.workflows.isNotEmpty()) {
            Text("可执行工作流", style = MaterialTheme.typography.titleSmall)
            state.workflows.forEach { workflow ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = workflow.name.ifBlank { workflow.path },
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "${workflow.path} · ${workflow.stateLabel}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            workflow.htmlUrl?.takeIf { it.isNotBlank() }?.let {
                                TextButton(
                                    onClick = { onOpenWorkflowPage(workflow) },
                                    enabled = !isActionRunning
                                ) {
                                    Text("网页")
                                }
                            }
                            Button(
                                onClick = { onRunWorkflow(workflow) },
                                enabled = workflow.canDispatch && !isActionRunning
                            ) {
                                Text("运行")
                            }
                        }
                    }
                }
            }
        }
        if (state.recentRuns.isNotEmpty()) {
            Text("最近运行", style = MaterialTheme.typography.titleSmall)
            state.recentRuns.forEach { run ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = run.displayTitle.ifBlank { run.name.ifBlank { "运行 #${run.runNumber}" } },
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "${run.statusLabel} · ${run.headBranch} · ${run.updatedAt}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "事件 ${run.event} · #${run.runNumber}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            run.htmlUrl?.takeIf { it.isNotBlank() }?.let {
                                TextButton(
                                    onClick = { onOpenRunPage(run) },
                                    enabled = !isActionRunning
                                ) {
                                    Text("网页")
                                }
                            }
                            OutlinedButton(
                                onClick = { onDownloadRunLogs(run) },
                                enabled = !isActionRunning
                            ) {
                                Text("日志")
                            }
                            TextButton(
                                onClick = { onOpenRunDetail(run) },
                                enabled = !isActionRunning
                            ) {
                                Text("详情")
                            }
                            OutlinedButton(
                                onClick = { onOpenArtifacts(run) },
                                enabled = !isActionRunning
                            ) {
                                Text("产物")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectGitHubDownloadHistorySection(
    downloads: List<ProjectGitHubDownloadRecordUi>,
    onOpenSystemDownloads: () -> Unit,
    onOpenSource: (ProjectGitHubDownloadRecordUi) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("下载记录", style = MaterialTheme.typography.titleSmall)
            OutlinedButton(onClick = onOpenSystemDownloads) {
                Text("系统下载")
            }
        }
        if (downloads.isEmpty()) {
            Text(
                text = "这里会显示本页触发的工作流日志、产物和 Release 资产下载记录。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            downloads.forEach { item ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "${item.typeLabel} · ${item.title}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "${item.fileName} · ${item.createdAtLabel}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "下载 ID ${item.downloadId}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = onOpenSystemDownloads) {
                                Text("查看下载")
                            }
                            item.sourceUrl?.takeIf { it.isNotBlank() }?.let {
                                TextButton(onClick = { onOpenSource(item) }) {
                                    Text("来源")
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
private fun ProjectGitHubReleaseSection(
    releases: List<ProjectGitHubReleaseUi>,
    isActionRunning: Boolean,
    onCreateRelease: () -> Unit,
    onEditRelease: (ProjectGitHubReleaseUi) -> Unit,
    onToggleReleaseMode: (ProjectGitHubReleaseUi, Boolean) -> Unit,
    onTogglePrerelease: (ProjectGitHubReleaseUi, Boolean) -> Unit,
    onDeleteRelease: (ProjectGitHubReleaseUi) -> Unit,
    onOpenReleasePage: (ProjectGitHubReleaseUi) -> Unit,
    onOpenAssets: (ProjectGitHubReleaseUi) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Releases", style = MaterialTheme.typography.titleSmall)
            OutlinedButton(
                onClick = onCreateRelease,
                enabled = !isActionRunning
            ) {
                Text("新建")
            }
        }
        if (releases.isEmpty()) {
            Text(
                text = "当前仓库还没有读取到 Release。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            releases.forEach { release ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = release.name.ifBlank { release.tagName },
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = buildString {
                                append(release.tagName)
                                append(" · ")
                                append(
                                    when {
                                        release.isDraft -> "草稿"
                                        release.isPrerelease -> "预发布"
                                        else -> "正式版"
                                    }
                                )
                                release.publishedAt.takeIf { it.isNotBlank() }?.let {
                                    append(" · ")
                                    append(it)
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (release.body.isNotBlank()) {
                            Text(
                                text = release.body.take(140),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            release.htmlUrl?.takeIf { it.isNotBlank() }?.let {
                                TextButton(
                                    onClick = { onOpenReleasePage(release) },
                                    enabled = !isActionRunning
                                ) {
                                    Text("网页")
                                }
                            }
                            OutlinedButton(
                                onClick = { onEditRelease(release) },
                                enabled = !isActionRunning
                            ) {
                                Text("编辑")
                            }
                            OutlinedButton(
                                onClick = { onToggleReleaseMode(release, !release.isDraft) },
                                enabled = !isActionRunning
                            ) {
                                Text(if (release.isDraft) "发布" else "草稿")
                            }
                            OutlinedButton(
                                onClick = { onTogglePrerelease(release, !release.isPrerelease) },
                                enabled = !isActionRunning && !release.isDraft
                            ) {
                                Text(if (release.isPrerelease) "转正式" else "预发布")
                            }
                            OutlinedButton(
                                onClick = { onOpenAssets(release) },
                                enabled = !isActionRunning
                            ) {
                                Text("资产 ${release.assets.size}")
                            }
                            TextButton(
                                onClick = { onDeleteRelease(release) },
                                enabled = !isActionRunning
                            ) {
                                Text("删除")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectGitHistorySection(
    commits: List<ProjectGitCommitUi>,
    onOpenCommit: (ProjectGitCommitUi) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("最近提交", style = MaterialTheme.typography.titleSmall)
        if (commits.isEmpty()) {
            Text(
                "当前仓库还没有可显示的提交记录。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            commits.forEach { commit ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(commit.subject, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${commit.shortHash} · ${commit.relativeTime}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        commit.author.takeIf { it.isNotBlank() }?.let { author ->
                            Text(
                                author,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(onClick = { onOpenCommit(commit) }) {
                            Text("查看详情")
                        }
                    }
                }
            }
        }
    }
}

private fun approvalModeLabel(mode: ToolApprovalMode): String = when (mode) {
    ToolApprovalMode.READ_ONLY -> "只读模式"
    ToolApprovalMode.ALL_APPROVAL -> "全部审批"
    ToolApprovalMode.WHITELIST_AUTO -> "白名单自动通过"
    ToolApprovalMode.ALL_AUTO -> "全部自动通过"
}

private data class ProjectGitStatusUi(
    val projectPath: String?,
    val repoRoot: String?,
    val branchSummary: String,
    val currentBranch: String?,
    val remoteUrl: String?,
    val upstreamBranch: String?,
    val aheadCount: Int,
    val behindCount: Int,
    val lastCommitSummary: String?,
    val localBranches: List<ProjectGitBranchUi>,
    val remoteBranches: List<String>,
    val recentCommits: List<ProjectGitCommitUi>,
    val conflictedFiles: List<ProjectGitFileChangeUi>,
    val stagedFiles: List<ProjectGitFileChangeUi>,
    val modifiedFiles: List<ProjectGitFileChangeUi>,
    val untrackedFiles: List<ProjectGitFileChangeUi>,
    val errorMessage: String?
) {
    val isRepository: Boolean get() = !repoRoot.isNullOrBlank()
    val hasRemote: Boolean get() = !remoteUrl.isNullOrBlank()
    val canPull: Boolean get() = hasRemote && !upstreamBranch.isNullOrBlank()
    val canPush: Boolean get() = hasRemote && !currentBranch.isNullOrBlank()
    val canStageAll: Boolean get() = modifiedFiles.isNotEmpty() || untrackedFiles.isNotEmpty()
    val canCommit: Boolean get() = stagedFiles.isNotEmpty()

    companion object {
        fun empty(projectPath: String?) = ProjectGitStatusUi(
            projectPath = projectPath,
            repoRoot = null,
            branchSummary = "",
            currentBranch = null,
            remoteUrl = null,
            upstreamBranch = null,
            aheadCount = 0,
            behindCount = 0,
            lastCommitSummary = null,
            localBranches = emptyList(),
            remoteBranches = emptyList(),
            recentCommits = emptyList(),
            conflictedFiles = emptyList(),
            stagedFiles = emptyList(),
            modifiedFiles = emptyList(),
            untrackedFiles = emptyList(),
            errorMessage = null
        )
    }
}

private data class ProjectGitFileChangeUi(
    val displayPath: String,
    val actionPath: String,
    val statusLabel: String,
    val diffMode: ProjectGitDiffMode
)

private enum class ProjectGitDiffMode {
    STAGED,
    WORKTREE,
    UNTRACKED
}

private data class ProjectGitDiffPreviewUi(
    val title: String,
    val content: String
)

private data class ProjectGitBranchUi(
    val name: String,
    val isCurrent: Boolean,
    val trackInfo: String?
)

private data class ProjectGitCommitUi(
    val commitHash: String,
    val shortHash: String,
    val subject: String,
    val relativeTime: String,
    val author: String
)

private data class ProjectGitCommandResult(
    val success: Boolean,
    val output: String,
    val error: String? = null,
    val exitCode: Int = 0
)

private fun loadProjectGitStatus(projectPath: String): ProjectGitStatusUi {
    val repoRoot = findProjectGitRoot(projectPath)
        ?: return ProjectGitStatusUi.empty(projectPath).copy(
            errorMessage = "当前目录下没有识别到 `.git` 仓库。"
        )
    if (!runCheckedShellCommand("command -v git", allowedExitCodes = setOf(0)).success) {
        return ProjectGitStatusUi.empty(projectPath).copy(
            repoRoot = repoRoot,
            errorMessage = "当前设备环境里没有可用的 `git` 命令。"
        )
    }
    val statusResult = runGitCommand(
        repoRoot,
        "status --short --branch --porcelain=v1 -uall"
    )
    if (!statusResult.success) {
        return ProjectGitStatusUi.empty(projectPath).copy(
            repoRoot = repoRoot,
            errorMessage = statusResult.error ?: statusResult.output.ifBlank { "读取 Git 状态失败" }
        )
    }
    val branchSummary = parseGitBranchSummary(statusResult.output)
    val parsed = parseProjectGitStatusLines(repoRoot, statusResult.output)
    val branches = loadProjectGitBranches(repoRoot)
    val remoteInfo = loadProjectGitRemoteInfo(repoRoot)
    val upstreamStatus = remoteInfo.upstreamBranch?.let { loadProjectGitUpstreamStatus(repoRoot) }
        ?: ProjectGitUpstreamStatus(0, 0)
    val remoteBranches = loadProjectGitRemoteBranches(repoRoot)
    val recentCommits = loadProjectGitHistory(repoRoot)
    val lastCommitSummary = runGitCommand(
        repoRoot,
        "log -1 --pretty=format:%h%x09%s%x09%cr"
    ).output.trim().ifBlank { null }
    return ProjectGitStatusUi(
        projectPath = projectPath,
        repoRoot = repoRoot,
        branchSummary = branchSummary,
        currentBranch = branches.firstOrNull { it.isCurrent }?.name,
        remoteUrl = remoteInfo.remoteUrl,
        upstreamBranch = remoteInfo.upstreamBranch,
        aheadCount = upstreamStatus.aheadCount,
        behindCount = upstreamStatus.behindCount,
        lastCommitSummary = lastCommitSummary,
        localBranches = branches,
        remoteBranches = remoteBranches,
        recentCommits = recentCommits,
        conflictedFiles = parsed.conflicted,
        stagedFiles = parsed.staged,
        modifiedFiles = parsed.modified,
        untrackedFiles = parsed.untracked,
        errorMessage = null
    )
}

private fun loadProjectGitBranches(repoRoot: String): List<ProjectGitBranchUi> {
    val result = runGitCommand(repoRoot, "branch --list --no-color -vv")
    if (!result.success) return emptyList()
    return result.output.lineSequence()
        .mapNotNull { line ->
            val trimmed = line.trimEnd()
            if (trimmed.isBlank()) return@mapNotNull null
            val isCurrent = trimmed.startsWith("*")
            val content = trimmed.removePrefix("*").removePrefix(" ").trim()
            val tokens = content.split(Regex("\\s+"))
            val name = tokens.firstOrNull().orEmpty()
            if (name.isBlank()) return@mapNotNull null
            val trackInfo = Regex("""\[(.+?)\]""")
                .find(content)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()
            ProjectGitBranchUi(
                name = name,
                isCurrent = isCurrent,
                trackInfo = trackInfo
            )
        }
        .toList()
}

private fun loadProjectGitHistory(repoRoot: String): List<ProjectGitCommitUi> {
    val result = runGitCommand(
        repoRoot,
        "log -12 --date=relative --pretty=format:%H%x09%h%x09%s%x09%cr%x09%an"
    )
    if (!result.success) return emptyList()
    return result.output.lineSequence()
        .mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size < 5) return@mapNotNull null
            ProjectGitCommitUi(
                commitHash = parts[0],
                shortHash = parts[1],
                subject = parts[2],
                relativeTime = parts[3],
                author = parts[4]
            )
        }
        .toList()
}

private fun loadProjectGitCommitPreview(
    repoRoot: String,
    commitHash: String
): ProjectGitDiffPreviewUi? {
    val result = runGitCommand(
        repoRoot,
        "show --stat --patch --format=fuller ${shellQuote(commitHash)}"
    )
    if (!result.success) return null
    return ProjectGitDiffPreviewUi(
        title = "提交 ${commitHash.take(8)}",
        content = result.output.trimEnd().ifBlank { "该提交暂无可显示详情。" }
    )
}

private fun buildGitCommitMessage(title: String, detail: String): String {
    val normalizedTitle = title.trim()
    val normalizedDetail = detail.trim()
    if (normalizedTitle.isBlank()) return ""
    return if (normalizedDetail.isBlank()) {
        normalizedTitle
    } else {
        normalizedTitle + "\n\n" + normalizedDetail
    }
}

private fun loadProjectGitHubActions(
    repo: ProjectGitHubRepoRef,
    token: String,
    apiBaseUrl: String
): ProjectGitHubActionsState {
    var state = ProjectGitHubActionsState.empty(repo)
    val viewerResult = runProjectGitHubApiRequest(
        apiBaseUrl = apiBaseUrl,
        token = token,
        path = "/user"
    )
    if (!viewerResult.success) {
        return state.copy(errorMessage = viewerResult.error ?: "GitHub 登录状态校验失败")
    }
    parseProjectGitHubJsonObject(viewerResult.body)?.let { viewer ->
        state = state.copy(
            viewerLogin = viewer.string("login"),
            viewerName = viewer.string("name")
        )
    }
    val repoResult = runProjectGitHubApiRequest(
        apiBaseUrl = apiBaseUrl,
        token = token,
        path = "/repos/${repo.owner}/${repo.repo}"
    )
    if (!repoResult.success) {
        return state.copy(errorMessage = repoResult.error ?: "读取 GitHub 仓库信息失败")
    }
    parseProjectGitHubJsonObject(repoResult.body)?.let { repoObject ->
        state = state.copy(
            defaultBranch = repoObject.string("default_branch"),
            repoHtmlUrl = repoObject.string("html_url")
        )
    }
    val workflowsResult = runProjectGitHubApiRequest(
        apiBaseUrl = apiBaseUrl,
        token = token,
        path = "/repos/${repo.owner}/${repo.repo}/actions/workflows?per_page=50"
    )
    if (!workflowsResult.success) {
        return state.copy(errorMessage = workflowsResult.error ?: "读取工作流列表失败")
    }
    val workflows = parseProjectGitHubJsonObject(workflowsResult.body)
        ?.jsonArrayOrEmpty("workflows")
        ?.mapNotNull { item ->
            val obj = item.jsonObjectOrNull() ?: return@mapNotNull null
            ProjectGitHubWorkflowUi(
                id = obj.long("id") ?: return@mapNotNull null,
                name = obj.string("name").orEmpty(),
                path = obj.string("path").orEmpty(),
                state = obj.string("state").orEmpty(),
                htmlUrl = obj.string("html_url")
            )
        }
        .orEmpty()
    val runsResult = runProjectGitHubApiRequest(
        apiBaseUrl = apiBaseUrl,
        token = token,
        path = "/repos/${repo.owner}/${repo.repo}/actions/runs?per_page=12"
    )
    if (!runsResult.success) {
        return state.copy(
            workflows = workflows,
            errorMessage = runsResult.error ?: "读取工作流运行记录失败"
        )
    }
    val runs = parseProjectGitHubJsonObject(runsResult.body)
        ?.jsonArrayOrEmpty("workflow_runs")
        ?.mapNotNull { item ->
            val obj = item.jsonObjectOrNull() ?: return@mapNotNull null
            ProjectGitHubWorkflowRunUi(
                id = obj.long("id") ?: return@mapNotNull null,
                name = obj.string("name").orEmpty(),
                displayTitle = obj.string("display_title").orEmpty(),
                headBranch = obj.string("head_branch").orEmpty(),
                status = obj.string("status").orEmpty(),
                conclusion = obj.string("conclusion"),
                event = obj.string("event").orEmpty(),
                runNumber = obj.long("run_number") ?: 0L,
                updatedAt = obj.string("updated_at").orEmpty(),
                htmlUrl = obj.string("html_url")
            )
        }
        .orEmpty()
    val releasesResult = runProjectGitHubApiRequest(
        apiBaseUrl = apiBaseUrl,
        token = token,
        path = "/repos/${repo.owner}/${repo.repo}/releases?per_page=10"
    )
    if (!releasesResult.success) {
        return state.copy(
            workflows = workflows,
            recentRuns = runs,
            errorMessage = releasesResult.error ?: "读取 Release 列表失败"
        )
    }
    val releases = parseProjectGitHubJsonArray(releasesResult.body)
        ?.mapNotNull { item ->
            val obj = item.jsonObjectOrNull() ?: return@mapNotNull null
            ProjectGitHubReleaseUi(
                id = obj.long("id") ?: return@mapNotNull null,
                tagName = obj.string("tag_name").orEmpty(),
                name = obj.string("name").orEmpty(),
                body = obj.string("body").orEmpty(),
                isDraft = obj.boolean("draft") ?: false,
                isPrerelease = obj.boolean("prerelease") ?: false,
                publishedAt = obj.string("published_at").orEmpty(),
                htmlUrl = obj.string("html_url"),
                assets = obj.jsonArrayOrEmpty("assets").mapNotNull { assetItem ->
                    val assetObj = assetItem.jsonObjectOrNull() ?: return@mapNotNull null
                    ProjectGitHubReleaseAssetUi(
                        id = assetObj.long("id") ?: return@mapNotNull null,
                        name = assetObj.string("name").orEmpty(),
                        sizeInBytes = assetObj.long("size") ?: 0L,
                        apiUrl = assetObj.string("url").orEmpty(),
                        browserDownloadUrl = assetObj.string("browser_download_url"),
                        updatedAt = assetObj.string("updated_at").orEmpty()
                    )
                }
            )
        }
        .orEmpty()
    return state.copy(
        workflows = workflows,
        recentRuns = runs,
        releases = releases,
        errorMessage = null
    )
}

private fun dispatchProjectGitHubWorkflow(
    repo: ProjectGitHubRepoRef,
    workflowId: Long,
    ref: String,
    token: String,
    apiBaseUrl: String
): ProjectGitHubCommandResult {
    val body = buildJsonObject {
        put("ref", ref)
    }.toString()
    val result = runProjectGitHubApiRequest(
        apiBaseUrl = apiBaseUrl,
        token = token,
        path = "/repos/${repo.owner}/${repo.repo}/actions/workflows/$workflowId/dispatches",
        method = "POST",
        jsonBody = body,
        allowedCodes = setOf(204)
    )
    return if (result.success) {
        ProjectGitHubCommandResult(
            success = true,
            message = "已触发工作流，GitHub 会开始排队执行。"
        )
    } else {
        ProjectGitHubCommandResult(
            success = false,
            message = "",
            error = result.error ?: "触发工作流失败"
        )
    }
}

private fun loadProjectGitHubArtifacts(
    repo: ProjectGitHubRepoRef,
    runId: Long,
    token: String,
    apiBaseUrl: String
): ProjectGitHubArtifactLoadResult {
    val result = runProjectGitHubApiRequest(
        apiBaseUrl = apiBaseUrl,
        token = token,
        path = "/repos/${repo.owner}/${repo.repo}/actions/runs/$runId/artifacts?per_page=30"
    )
    if (!result.success) {
        return ProjectGitHubArtifactLoadResult(
            success = false,
            artifacts = emptyList(),
            error = result.error ?: "读取工作流产物失败"
        )
    }
    val artifacts = parseProjectGitHubJsonObject(result.body)
        ?.jsonArrayOrEmpty("artifacts")
        ?.mapNotNull { item ->
            val obj = item.jsonObjectOrNull() ?: return@mapNotNull null
            ProjectGitHubArtifactUi(
                id = obj.long("id") ?: return@mapNotNull null,
                name = obj.string("name").orEmpty(),
                sizeInBytes = obj.long("size_in_bytes") ?: 0L,
                archiveDownloadUrl = obj.string("archive_download_url").orEmpty(),
                expired = obj.boolean("expired") ?: false,
                updatedAt = obj.string("updated_at").orEmpty()
            )
        }
        .orEmpty()
    return ProjectGitHubArtifactLoadResult(
        success = true,
        artifacts = artifacts,
        error = null
    )
}

private fun loadProjectGitHubWorkflowRunDetail(
    repo: ProjectGitHubRepoRef,
    runId: Long,
    token: String,
    apiBaseUrl: String
): ProjectGitHubWorkflowRunDetailLoadResult {
    val runResult = runProjectGitHubApiRequest(
        apiBaseUrl = apiBaseUrl,
        token = token,
        path = "/repos/${repo.owner}/${repo.repo}/actions/runs/$runId"
    )
    if (!runResult.success) {
        return ProjectGitHubWorkflowRunDetailLoadResult(
            success = false,
            detail = null,
            error = runResult.error ?: "读取工作流运行详情失败"
        )
    }
    val jobsResult = runProjectGitHubApiRequest(
        apiBaseUrl = apiBaseUrl,
        token = token,
        path = "/repos/${repo.owner}/${repo.repo}/actions/runs/$runId/jobs?per_page=100"
    )
    if (!jobsResult.success) {
        return ProjectGitHubWorkflowRunDetailLoadResult(
            success = false,
            detail = null,
            error = jobsResult.error ?: "读取工作流 job 详情失败"
        )
    }
    val runObject = parseProjectGitHubJsonObject(runResult.body)
        ?: return ProjectGitHubWorkflowRunDetailLoadResult(
            success = false,
            detail = null,
            error = "GitHub 返回的运行详情无法解析"
        )
    val jobs = parseProjectGitHubJsonObject(jobsResult.body)
        ?.jsonArrayOrEmpty("jobs")
        ?.mapNotNull { item ->
            val obj = item.jsonObjectOrNull() ?: return@mapNotNull null
            ProjectGitHubWorkflowJobUi(
                id = obj.long("id") ?: return@mapNotNull null,
                name = obj.string("name").orEmpty().ifBlank { "未命名 Job" },
                status = obj.string("status").orEmpty(),
                conclusion = obj.string("conclusion"),
                startedAt = obj.string("started_at").orEmpty(),
                completedAt = obj.string("completed_at").orEmpty(),
                steps = obj.jsonArrayOrEmpty("steps").mapNotNull { stepItem ->
                    val stepObj = stepItem.jsonObjectOrNull() ?: return@mapNotNull null
                    ProjectGitHubWorkflowStepUi(
                        number = (stepObj.long("number") ?: 0L).toInt(),
                        name = stepObj.string("name").orEmpty().ifBlank { "未命名步骤" },
                        status = stepObj.string("status").orEmpty(),
                        conclusion = stepObj.string("conclusion"),
                        startedAt = stepObj.string("started_at").orEmpty(),
                        completedAt = stepObj.string("completed_at").orEmpty()
                    )
                }
            )
        }
        .orEmpty()
    val detail = ProjectGitHubWorkflowRunDetailUi(
        id = runId,
        title = runObject.string("display_title")
            .orEmpty()
            .ifBlank { runObject.string("name").orEmpty().ifBlank { "运行 #${runObject.long("run_number") ?: runId}" } },
        workflowName = runObject.string("name").orEmpty(),
        headBranch = runObject.string("head_branch").orEmpty(),
        status = runObject.string("status").orEmpty(),
        conclusion = runObject.string("conclusion"),
        event = runObject.string("event").orEmpty(),
        runNumber = runObject.long("run_number") ?: 0L,
        createdAt = runObject.string("created_at").orEmpty(),
        updatedAt = runObject.string("updated_at").orEmpty(),
        htmlUrl = runObject.string("html_url"),
        jobs = jobs
    )
    return ProjectGitHubWorkflowRunDetailLoadResult(
        success = true,
        detail = detail,
        error = null
    )
}

private fun updateProjectGitHubRelease(
    repo: ProjectGitHubRepoRef,
    releaseId: Long,
    tagName: String,
    releaseName: String,
    body: String,
    isDraft: Boolean,
    isPrerelease: Boolean,
    token: String,
    apiBaseUrl: String
): ProjectGitHubCommandResult {
    val requestBody = buildJsonObject {
        put("tag_name", tagName)
        put("name", releaseName)
        put("body", body)
        put("draft", isDraft)
        put("prerelease", isPrerelease)
    }.toString()
    val result = runProjectGitHubApiRequest(
        apiBaseUrl = apiBaseUrl,
        token = token,
        path = "/repos/${repo.owner}/${repo.repo}/releases/$releaseId",
        method = "PATCH",
        jsonBody = requestBody,
        allowedCodes = setOf(200)
    )
    return if (result.success) {
        ProjectGitHubCommandResult(success = true, message = "Release 已更新。")
    } else {
        ProjectGitHubCommandResult(
            success = false,
            message = "",
            error = result.error ?: "更新 Release 失败"
        )
    }
}

private fun createProjectGitHubRelease(
    repo: ProjectGitHubRepoRef,
    tagName: String,
    releaseName: String,
    body: String,
    isDraft: Boolean,
    isPrerelease: Boolean,
    token: String,
    apiBaseUrl: String
): ProjectGitHubCommandResult {
    val requestBody = buildJsonObject {
        put("tag_name", tagName)
        put("name", releaseName)
        put("body", body)
        put("draft", isDraft)
        put("prerelease", isPrerelease)
    }.toString()
    val result = runProjectGitHubApiRequest(
        apiBaseUrl = apiBaseUrl,
        token = token,
        path = "/repos/${repo.owner}/${repo.repo}/releases",
        method = "POST",
        jsonBody = requestBody,
        allowedCodes = setOf(201)
    )
    return if (result.success) {
        ProjectGitHubCommandResult(success = true, message = "Release 已创建。")
    } else {
        ProjectGitHubCommandResult(
            success = false,
            message = "",
            error = result.error ?: "创建 Release 失败"
        )
    }
}

private fun deleteProjectGitHubRelease(
    repo: ProjectGitHubRepoRef,
    releaseId: Long,
    token: String,
    apiBaseUrl: String
): ProjectGitHubCommandResult {
    val result = runProjectGitHubApiRequest(
        apiBaseUrl = apiBaseUrl,
        token = token,
        path = "/repos/${repo.owner}/${repo.repo}/releases/$releaseId",
        method = "DELETE",
        jsonBody = "",
        allowedCodes = setOf(204)
    )
    return if (result.success) {
        ProjectGitHubCommandResult(success = true, message = "Release 已删除。")
    } else {
        ProjectGitHubCommandResult(
            success = false,
            message = "",
            error = result.error ?: "删除 Release 失败"
        )
    }
}

private fun createProjectGitHubRepository(
    repoName: String,
    description: String,
    isPrivate: Boolean,
    token: String,
    apiBaseUrl: String
): ProjectGitHubCreateRepoResult {
    val requestBody = buildJsonObject {
        put("name", repoName)
        if (description.isNotBlank()) {
            put("description", description)
        }
        put("private", isPrivate)
        put("auto_init", false)
    }.toString()
    val result = runProjectGitHubApiRequest(
        apiBaseUrl = apiBaseUrl,
        token = token,
        path = "/user/repos",
        method = "POST",
        jsonBody = requestBody,
        allowedCodes = setOf(201)
    )
    if (!result.success) {
        return ProjectGitHubCreateRepoResult(
            success = false,
            repo = null,
            cloneUrl = null,
            htmlUrl = null,
            error = result.error ?: "创建 GitHub 仓库失败"
        )
    }
    val obj = parseProjectGitHubJsonObject(result.body)
        ?: return ProjectGitHubCreateRepoResult(
            success = false,
            repo = null,
            cloneUrl = null,
            htmlUrl = null,
            error = "GitHub 返回的仓库信息无法解析"
        )
    val owner = obj.jsonObjectOrNull("owner")?.string("login")
    val repo = obj.string("name")
    val cloneUrl = obj.string("clone_url")
    return if (owner.isNullOrBlank() || repo.isNullOrBlank() || cloneUrl.isNullOrBlank()) {
        ProjectGitHubCreateRepoResult(
            success = false,
            repo = null,
            cloneUrl = null,
            htmlUrl = null,
            error = "GitHub 返回的仓库信息不完整"
        )
    } else {
        ProjectGitHubCreateRepoResult(
            success = true,
            repo = ProjectGitHubRepoRef(owner = owner, repo = repo),
            cloneUrl = cloneUrl,
            htmlUrl = obj.string("html_url"),
            error = null
        )
    }
}

private fun initializeProjectGitRepository(
    projectPath: String,
    preferredBranch: String
): ProjectGitCommandResult {
    val branchName = preferredBranch.trim().ifBlank { "main" }
    val directInit = runGitCommand(projectPath, "init -b ${shellQuote(branchName)}")
    if (directInit.success) {
        return ProjectGitCommandResult(
            success = true,
            output = directInit.output.ifBlank { "Git 仓库初始化完成" },
            exitCode = directInit.exitCode
        )
    }
    val fallbackInit = runGitCommand(projectPath, "init")
    if (!fallbackInit.success) {
        return fallbackInit
    }
    val setHeadResult = runCheckedShellCommand(
        "git -C ${shellQuote(projectPath)} symbolic-ref HEAD ${shellQuote("refs/heads/$branchName")}"
    )
    return if (setHeadResult.success) {
        ProjectGitCommandResult(
            success = true,
            output = listOf(fallbackInit.output, setHeadResult.output)
                .filter { it.isNotBlank() }
                .joinToString("\n")
                .ifBlank { "Git 仓库初始化完成" }
        )
    } else {
        fallbackInit
    }
}

private fun bindProjectGitHubRemote(
    projectPath: String,
    remoteUrl: String,
    preferredBranch: String
): ProjectGitCommandResult {
    val repoRoot = findProjectGitRoot(projectPath) ?: run {
        val initResult = initializeProjectGitRepository(projectPath, preferredBranch)
        if (!initResult.success) return initResult
        findProjectGitRoot(projectPath) ?: projectPath
    }
    val currentOrigin = runGitCommand(
        repoRoot,
        "remote get-url origin",
        allowedExitCodes = setOf(0, 2, 128)
    )
    val remoteResult = if (currentOrigin.success && currentOrigin.output.isNotBlank()) {
        runGitCommand(repoRoot, "remote set-url origin ${shellQuote(remoteUrl)}")
    } else {
        runGitCommand(repoRoot, "remote add origin ${shellQuote(remoteUrl)}")
    }
    return if (remoteResult.success) {
        ProjectGitCommandResult(
            success = true,
            output = remoteResult.output.ifBlank { "已绑定 GitHub origin" }
        )
    } else {
        remoteResult
    }
}

private fun enqueueProjectGitHubArtifactDownload(
    context: android.content.Context,
    repo: ProjectGitHubRepoRef,
    artifact: ProjectGitHubArtifactUi,
    token: String
): ProjectDownloadEnqueueResult {
    val fileName = sanitizeProjectDownloadFileName("${repo.repo}-${artifact.name}.zip")
    val request = DownloadManager.Request(Uri.parse(artifact.archiveDownloadUrl))
        .setTitle(artifact.name)
        .setDescription("${repo.owner}/${repo.repo}")
        .addRequestHeader("Authorization", "Bearer $token")
        .addRequestHeader("Accept", "application/vnd.github+json")
        .setMimeType("application/zip")
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        .setAllowedOverMetered(true)
        .setAllowedOverRoaming(true)
    runCatching {
        request.setDestinationInExternalPublicDir(
            Environment.DIRECTORY_DOWNLOADS,
            "Reasonix/$fileName"
        )
    }.getOrElse {
        request.setDestinationInExternalFilesDir(
            context,
            Environment.DIRECTORY_DOWNLOADS,
            fileName
        )
    }
    val manager = context.getSystemService(DownloadManager::class.java)
        ?: error("系统下载服务不可用")
    val downloadId = manager.enqueue(request)
    return ProjectDownloadEnqueueResult(downloadId = downloadId, fileName = fileName)
}

private fun enqueueProjectGitHubReleaseAssetDownload(
    context: android.content.Context,
    repo: ProjectGitHubRepoRef,
    asset: ProjectGitHubReleaseAssetUi,
    token: String
): ProjectDownloadEnqueueResult {
    val fileName = sanitizeProjectDownloadFileName(asset.name.ifBlank { "${repo.repo}-${asset.id}.bin" })
    val request = DownloadManager.Request(Uri.parse(asset.apiUrl))
        .setTitle(asset.name)
        .setDescription("${repo.owner}/${repo.repo}")
        .addRequestHeader("Authorization", "Bearer $token")
        .addRequestHeader("Accept", "application/octet-stream")
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        .setAllowedOverMetered(true)
        .setAllowedOverRoaming(true)
    runCatching {
        request.setDestinationInExternalPublicDir(
            Environment.DIRECTORY_DOWNLOADS,
            "Reasonix/$fileName"
        )
    }.getOrElse {
        request.setDestinationInExternalFilesDir(
            context,
            Environment.DIRECTORY_DOWNLOADS,
            fileName
        )
    }
    val manager = context.getSystemService(DownloadManager::class.java)
        ?: error("系统下载服务不可用")
    val downloadId = manager.enqueue(request)
    return ProjectDownloadEnqueueResult(downloadId = downloadId, fileName = fileName)
}

private fun enqueueProjectGitHubWorkflowLogsDownload(
    context: android.content.Context,
    repo: ProjectGitHubRepoRef,
    runId: Long,
    runDisplayTitle: String,
    token: String,
    apiBaseUrl: String
): ProjectDownloadEnqueueResult {
    val fileName = sanitizeProjectDownloadFileName("${repo.repo}-run-$runId-logs.zip")
    val request = DownloadManager.Request(
        Uri.parse(
            buildProjectGitHubApiUrl(
                apiBaseUrl,
                "/repos/${repo.owner}/${repo.repo}/actions/runs/$runId/logs"
            )
        )
    )
        .setTitle("$runDisplayTitle 日志")
        .setDescription("${repo.owner}/${repo.repo}")
        .addRequestHeader("Authorization", "Bearer $token")
        .addRequestHeader("Accept", "application/vnd.github+json")
        .setMimeType("application/zip")
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        .setAllowedOverMetered(true)
        .setAllowedOverRoaming(true)
    runCatching {
        request.setDestinationInExternalPublicDir(
            Environment.DIRECTORY_DOWNLOADS,
            "Reasonix/$fileName"
        )
    }.getOrElse {
        request.setDestinationInExternalFilesDir(
            context,
            Environment.DIRECTORY_DOWNLOADS,
            fileName
        )
    }
    val manager = context.getSystemService(DownloadManager::class.java)
        ?: error("系统下载服务不可用")
    val downloadId = manager.enqueue(request)
    return ProjectDownloadEnqueueResult(downloadId = downloadId, fileName = fileName)
}

private fun parseProjectGitHubRepoRef(remoteUrl: String?): ProjectGitHubRepoRef? {
    val raw = remoteUrl?.trim()?.removeSuffix(".git").orEmpty()
    if (raw.isBlank()) return null
    val path = when {
        raw.startsWith("git@") -> raw.substringAfter(':', "")
        raw.startsWith("ssh://", ignoreCase = true) ||
            raw.startsWith("http://", ignoreCase = true) ||
            raw.startsWith("https://", ignoreCase = true) -> {
            Uri.parse(raw).path.orEmpty().trim('/')
        }
        else -> raw.substringAfter(':', raw).trim('/')
    }
    val segments = path.split('/').filter { it.isNotBlank() }
    if (segments.size < 2) return null
    return ProjectGitHubRepoRef(
        owner = segments[segments.lastIndex - 1],
        repo = segments.last()
    )
}

private fun runProjectGitHubApiRequest(
    apiBaseUrl: String,
    token: String,
    path: String,
    method: String = "GET",
    jsonBody: String? = null,
    allowedCodes: Set<Int> = setOf(200)
): ProjectGitHubHttpResult {
    return runCatching {
        val url = buildProjectGitHubApiUrl(apiBaseUrl, path)
        val requestBuilder = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Accept", "application/vnd.github+json")
            .addHeader("X-GitHub-Api-Version", "2022-11-28")
            .addHeader("User-Agent", "Reasonix-Mobile/1.0")
        if (method.equals("GET", ignoreCase = true)) {
            requestBuilder.get()
        } else {
            requestBuilder.method(
                method,
                (jsonBody ?: "{}").toRequestBody("application/json; charset=utf-8".toMediaType())
            )
        }
        PROJECT_GITHUB_HTTP.newCall(requestBuilder.build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (response.code in allowedCodes) {
                ProjectGitHubHttpResult(
                    success = true,
                    code = response.code,
                    body = body,
                    error = null
                )
            } else {
                ProjectGitHubHttpResult(
                    success = false,
                    code = response.code,
                    body = body,
                    error = parseProjectGitHubApiError(body, response.code)
                )
            }
        }
    }.getOrElse { error ->
        ProjectGitHubHttpResult(
            success = false,
            code = -1,
            body = "",
            error = error.message ?: "GitHub API 请求失败"
        )
    }
}

private fun buildProjectGitHubApiUrl(apiBaseUrl: String, path: String): String {
    val base = normalizeProjectGitHubApiBaseUrl(apiBaseUrl)
    return base.trimEnd('/') + "/" + path.trimStart('/')
}

private fun normalizeProjectGitHubApiBaseUrl(raw: String?): String {
    val trimmed = raw?.trim().orEmpty().ifBlank { "https://api.github.com" }
    return when {
        trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed
        else -> "https://$trimmed"
    }
}

private fun parseProjectGitHubApiError(body: String, code: Int): String {
    val obj = parseProjectGitHubJsonObject(body)
    val message = obj?.string("message")
    return message?.ifBlank { null } ?: "GitHub API 请求失败，HTTP $code"
}

private fun parseProjectGitHubJsonObject(body: String): JsonObject? {
    return runCatching {
        PROJECT_GITHUB_JSON.parseToJsonElement(body).jsonObject
    }.getOrNull()
}

private fun parseProjectGitHubJsonArray(body: String) = runCatching {
    PROJECT_GITHUB_JSON.parseToJsonElement(body).jsonArray
}.getOrNull()

private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

private fun JsonObject.long(key: String): Long? = this[key]?.jsonPrimitive?.longOrNull

private fun JsonObject.boolean(key: String): Boolean? = this[key]?.jsonPrimitive?.booleanOrNull

private fun JsonObject.jsonArrayOrEmpty(key: String) = this[key]?.jsonArray ?: emptyList()

private fun JsonObject.jsonObjectOrNull(key: String): JsonObject? = this[key]?.jsonObjectOrNull()

private fun kotlinx.serialization.json.JsonElement.jsonObjectOrNull(): JsonObject? = runCatching { jsonObject }.getOrNull()

private fun sanitizeProjectDownloadFileName(value: String): String {
    return value.replace(Regex("""[\\/:*?"<>|]"""), "_")
}

private fun formatProjectByteSize(bytes: Long): String {
    if (bytes < 1024) return "${bytes} B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
    val gb = mb / 1024.0
    return String.format(Locale.US, "%.1f GB", gb)
}

private fun formatProjectDateTime(timeMillis: Long): String {
    return runCatching {
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(timeMillis))
    }.getOrElse {
        timeMillis.toString()
    }
}

private data class ProjectGitRemoteInfo(
    val remoteUrl: String?,
    val upstreamBranch: String?
)

private data class ProjectGitUpstreamStatus(
    val aheadCount: Int,
    val behindCount: Int
)

private fun loadProjectGitRemoteInfo(repoRoot: String): ProjectGitRemoteInfo {
    val remoteUrl = runGitCommand(repoRoot, "remote get-url origin")
        .output
        .trim()
        .ifBlank { null }
    val upstreamBranch = runGitCommand(repoRoot, "rev-parse --abbrev-ref --symbolic-full-name @{upstream}")
        .output
        .trim()
        .ifBlank { null }
        ?.takeUnless { it == "@{upstream}" }
    return ProjectGitRemoteInfo(
        remoteUrl = remoteUrl,
        upstreamBranch = upstreamBranch
    )
}

private fun loadProjectGitUpstreamStatus(repoRoot: String): ProjectGitUpstreamStatus {
    val result = runGitCommand(
        repoRoot,
        "rev-list --left-right --count HEAD...@{upstream}"
    )
    if (!result.success) return ProjectGitUpstreamStatus(aheadCount = 0, behindCount = 0)
    val parts = result.output.trim().split(Regex("\\s+"))
    return ProjectGitUpstreamStatus(
        aheadCount = parts.getOrNull(0)?.toIntOrNull() ?: 0,
        behindCount = parts.getOrNull(1)?.toIntOrNull() ?: 0
    )
}

private fun loadProjectGitRemoteBranches(repoRoot: String): List<String> {
    val result = runGitCommand(repoRoot, "branch -r --no-color")
    if (!result.success) return emptyList()
    return result.output.lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() && !it.contains("->") }
        .toList()
}

private fun loadProjectGitDiffPreview(
    repoRoot: String,
    change: ProjectGitFileChangeUi
): ProjectGitDiffPreviewUi? {
    val command = when (change.diffMode) {
        ProjectGitDiffMode.STAGED -> "diff --cached -- ${shellQuote(change.actionPath)}"
        ProjectGitDiffMode.WORKTREE -> "diff -- ${shellQuote(change.actionPath)}"
        ProjectGitDiffMode.UNTRACKED -> "diff --no-index -- /dev/null ${shellQuote(change.actionPath)}"
    }
    val result = runGitCommand(
        repoRoot,
        command,
        allowedExitCodes = if (change.diffMode == ProjectGitDiffMode.UNTRACKED) setOf(0, 1) else setOf(0)
    )
    if (!result.success) return null
    val content = result.output.trimEnd().ifBlank { "该文件当前没有可显示的差异。" }
    return ProjectGitDiffPreviewUi(
        title = "${change.displayPath} · 差异",
        content = content
    )
}

private data class ProjectGitStatusParseResult(
    val conflicted: List<ProjectGitFileChangeUi>,
    val staged: List<ProjectGitFileChangeUi>,
    val modified: List<ProjectGitFileChangeUi>,
    val untracked: List<ProjectGitFileChangeUi>
)

private fun parseProjectGitStatusLines(
    repoRoot: String,
    raw: String
): ProjectGitStatusParseResult {
    val conflicted = mutableListOf<ProjectGitFileChangeUi>()
    val staged = mutableListOf<ProjectGitFileChangeUi>()
    val modified = mutableListOf<ProjectGitFileChangeUi>()
    val untracked = mutableListOf<ProjectGitFileChangeUi>()
    raw.lineSequence().forEach { line ->
        if (line.isBlank() || line.startsWith("## ")) return@forEach
        if (line.length < 3) return@forEach
        val indexStatus = line[0]
        val workTreeStatus = line[1]
        val rawPath = line.substring(3).trim()
        val actionPath = normalizeGitActionPath(repoRoot, rawPath)
        val displayPath = rawPath.ifBlank { actionPath }
        if (indexStatus == '?' && workTreeStatus == '?') {
            untracked += ProjectGitFileChangeUi(
                displayPath = displayPath,
                actionPath = actionPath,
                statusLabel = "未跟踪文件",
                diffMode = ProjectGitDiffMode.UNTRACKED
            )
            return@forEach
        }
        if (indexStatus == 'U' || workTreeStatus == 'U' || (indexStatus == 'A' && workTreeStatus == 'A') || (indexStatus == 'D' && workTreeStatus == 'D')) {
            conflicted += ProjectGitFileChangeUi(
                displayPath = displayPath,
                actionPath = actionPath,
                statusLabel = "冲突: ${gitConflictLabel(indexStatus, workTreeStatus)}",
                diffMode = ProjectGitDiffMode.WORKTREE
            )
        }
        if (indexStatus != ' ') {
            staged += ProjectGitFileChangeUi(
                displayPath = displayPath,
                actionPath = actionPath,
                statusLabel = "暂存区: ${gitStatusLabel(indexStatus)}",
                diffMode = ProjectGitDiffMode.STAGED
            )
        }
        if (workTreeStatus != ' ') {
            modified += ProjectGitFileChangeUi(
                displayPath = displayPath,
                actionPath = actionPath,
                statusLabel = "工作区: ${gitStatusLabel(workTreeStatus)}",
                diffMode = ProjectGitDiffMode.WORKTREE
            )
        }
    }
    return ProjectGitStatusParseResult(
        conflicted = conflicted.distinctBy { it.actionPath },
        staged = staged.distinctBy { "${it.actionPath}:${it.diffMode}" },
        modified = modified.distinctBy { "${it.actionPath}:${it.diffMode}" },
        untracked = untracked.distinctBy { it.actionPath }
    )
}

private fun parseGitBranchSummary(raw: String): String {
    val branchLine = raw.lineSequence().firstOrNull { it.startsWith("## ") }?.removePrefix("## ")?.trim().orEmpty()
    if (branchLine.isBlank()) return ""
    return branchLine.replace("...", " -> ")
}

private fun gitStatusLabel(code: Char): String = when (code) {
    'M' -> "已修改"
    'A' -> "新增"
    'D' -> "删除"
    'R' -> "重命名"
    'C' -> "复制"
    'U' -> "冲突"
    'T' -> "类型变更"
    else -> code.toString()
}

private fun gitConflictLabel(indexStatus: Char, workTreeStatus: Char): String {
    return when {
        indexStatus == 'U' && workTreeStatus == 'U' -> "双方都修改"
        indexStatus == 'A' && workTreeStatus == 'A' -> "双方都新增"
        indexStatus == 'D' && workTreeStatus == 'D' -> "双方都删除"
        indexStatus == 'U' -> "当前分支冲突"
        workTreeStatus == 'U' -> "合并目标冲突"
        else -> "需要手动处理"
    }
}

private fun findProjectGitRoot(projectPath: String): String? {
    var current: File? = File(projectPath)
    while (current != null) {
        val dotGit = File(current, ".git")
        if (dotGit.exists() || RootFile.exists(dotGit.absolutePath)) {
            return current.absolutePath
        }
        current = current.parentFile
    }
    return null
}

private fun normalizeGitActionPath(repoRoot: String, rawPath: String): String {
    val normalized = rawPath.substringAfter(" -> ", rawPath).trim()
    if (normalized.startsWith("/")) return normalized
    return normalized.removePrefix("${repoRoot.trimEnd('/')}/")
}

private fun runGitCommand(
    repoRoot: String,
    args: String,
    allowedExitCodes: Set<Int> = setOf(0)
): ProjectGitCommandResult {
    return runCheckedShellCommand(
        "git -C ${shellQuote(repoRoot)} -c color.ui=false -c core.quotepath=false $args",
        allowedExitCodes = allowedExitCodes
    )
}

private fun runCheckedShellCommand(
    command: String,
    allowedExitCodes: Set<Int> = setOf(0)
): ProjectGitCommandResult {
    return runCatching {
        val marker = "__PROJECT_GIT_STATUS__"
        val raw = KeepShellPublic.doCmdSync("($command) 2>&1; printf '\\n$marker%s' \$?")
        val markerIndex = raw.lastIndexOf(marker)
        if (markerIndex < 0) {
            return ProjectGitCommandResult(
                success = false,
                output = raw,
                error = "无法读取 Git 命令执行状态"
            )
        }
        val output = raw.substring(0, markerIndex).trimEnd()
        val exitCode = raw.substring(markerIndex + marker.length).trim().toIntOrNull() ?: -1
        if (exitCode in allowedExitCodes) {
            ProjectGitCommandResult(success = true, output = output, exitCode = exitCode)
        } else {
            ProjectGitCommandResult(
                success = false,
                output = output,
                error = output.ifBlank { "Git 命令执行失败，退出码 $exitCode" },
                exitCode = exitCode
            )
        }
    }.getOrElse { error ->
        ProjectGitCommandResult(
            success = false,
            output = "",
            error = error.message ?: "Git 命令执行异常"
        )
    }
}

private fun shellQuote(value: String): String {
    return "'" + value.replace("'", "'\"'\"'") + "'"
}

private fun suggestProjectGitHubRepoName(projectPath: String?): String {
    return projectPath
        ?.takeIf { it.isNotBlank() }
        ?.let { File(it).name }
        ?.ifBlank { null }
        ?: "new-repo"
}

private enum class ProjectPrimaryTab(val label: String) {
    EDITOR("编辑器"),
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

private fun searchProjectEntries(
    root: File,
    query: String,
    scope: ProjectSearchScope,
    limit: Int = MAX_PROJECT_SEARCH_RESULTS
): ProjectSearchResultUi {
    val normalizedQuery = query.trim()
    if (normalizedQuery.isBlank()) {
        return ProjectSearchResultUi(
            query = query,
            scope = scope,
            hits = emptyList(),
            totalCount = 0,
            truncated = false
        )
    }
    val lowerQuery = normalizedQuery.lowercase(Locale.getDefault())
    val hits = mutableListOf<ProjectSearchHitUi>()
    var totalCount = 0
    var truncated = false

    root.walkTopDown()
        .onEnter { dir -> !shouldSkipSearchDir(dir, root) }
        .filter { it.isFile }
        .forEach { file ->
            val fileName = file.name.lowercase(Locale.getDefault())
            val relativePath = relativeProjectPath(root.absolutePath, file.absolutePath)
            val fileNameMatched = (scope == ProjectSearchScope.ALL || scope == ProjectSearchScope.FILE_NAME) &&
                fileName.contains(lowerQuery)
            if (fileNameMatched) {
                totalCount++
                if (hits.size < limit) {
                    hits += ProjectSearchHitUi(
                        filePath = file.absolutePath,
                        relativePath = relativePath,
                        preview = "文件名匹配",
                        fileType = "文件名"
                    )
                } else {
                    truncated = true
                }
                if (scope == ProjectSearchScope.FILE_NAME) return@forEach
            }

            if (scope == ProjectSearchScope.FILE_NAME || file.length() > MAX_PROJECT_SEARCH_FILE_BYTES) {
                return@forEach
            }

            val contentResult = runCatching { file.readText() }.getOrNull() ?: return@forEach
            val matchLine = findMatchingLine(contentResult, lowerQuery) ?: return@forEach
            totalCount++
            if (hits.size < limit) {
                hits += ProjectSearchHitUi(
                    filePath = file.absolutePath,
                    relativePath = relativePath,
                    lineNumber = matchLine.first,
                    preview = matchLine.second,
                    fileType = "内容"
                )
            } else {
                truncated = true
            }
        }

    return ProjectSearchResultUi(
        query = query,
        scope = scope,
        hits = hits,
        totalCount = totalCount,
        truncated = truncated
    )
}

private fun relativeProjectPath(rootPath: String, filePath: String): String {
    return filePath.removePrefix(rootPath).trimStart(File.separatorChar).ifBlank {
        File(filePath).name.ifBlank { filePath }
    }
}

private fun formatProjectFileSize(size: Long): String {
    return when {
        size >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", size / 1024f / 1024f)
        size >= 1024 -> String.format(Locale.US, "%.1f KB", size / 1024f)
        else -> "$size B"
    }
}

private fun findMatchingLine(content: String, lowerQuery: String): Pair<Int, String>? {
    content.lineSequence().forEachIndexed { index, line ->
        if (line.lowercase(Locale.getDefault()).contains(lowerQuery)) {
            return (index + 1) to line.trim().ifBlank { "(空行命中)" }
        }
    }
    return null
}

private fun shouldSkipSearchDir(dir: File, root: File): Boolean {
    if (dir.absolutePath == root.absolutePath) return false
    return dir.name in SEARCH_SKIPPED_DIR_NAMES
}

private fun projectLanguageForPath(path: String?): String? {
    val name = path?.substringAfterLast(File.separatorChar)?.lowercase(Locale.getDefault()) ?: return null
    val ext = name.substringAfterLast('.', name)
    return when (ext) {
        "kt", "kts" -> "kotlin"
        "java" -> "java"
        "gradle", "groovy", "gvy" -> "groovy"
        "js", "mjs", "cjs" -> "javascript"
        "jsx" -> "jsx"
        "ts" -> "typescript"
        "tsx" -> "tsx"
        "rs" -> "rust"
        "c" -> "c"
        "h" -> "h"
        "cpp", "cc", "cxx" -> "cpp"
        "hpp", "hh", "hxx" -> "hpp"
        "json" -> "json"
        "toml" -> "toml"
        "ini" -> "ini"
        "conf", "cfg" -> "conf"
        "sql" -> "sql"
        "css" -> "css"
        "html", "htm" -> "html"
        "xml" -> "xml"
        "md" -> "markdown"
        "py" -> "python"
        "sh", "bash" -> "bash"
        "yml", "yaml" -> "yaml"
        "properties" -> "properties"
        else -> null
    }
}

private fun projectSearchScopeLabel(scope: ProjectSearchScope): String = when (scope) {
    ProjectSearchScope.ALL -> "全部"
    ProjectSearchScope.FILE_NAME -> "文件名"
    ProjectSearchScope.TEXT -> "内容"
}

private fun projectSearchTypeLabel(type: String): String = when (type) {
    "文件名" -> "文件名命中"
    "内容" -> "内容命中"
    else -> type
}

private fun highlightSearchKeyword(text: String, query: String) = buildAnnotatedString {
    val normalizedQuery = query.trim()
    if (normalizedQuery.isBlank()) {
        append(text)
        return@buildAnnotatedString
    }
    val lowerText = text.lowercase(Locale.getDefault())
    val lowerQuery = normalizedQuery.lowercase(Locale.getDefault())
    var startIndex = 0
    while (startIndex < text.length) {
        val matchIndex = lowerText.indexOf(lowerQuery, startIndex)
        if (matchIndex < 0) {
            append(text.substring(startIndex))
            break
        }
        append(text.substring(startIndex, matchIndex))
        pushStyle(
            SpanStyle(
                background = Color(0x55FFD54F),
                color = Color(0xFF111111),
                fontWeight = FontWeight.SemiBold
            )
        )
        append(text.substring(matchIndex, matchIndex + normalizedQuery.length))
        pop()
        startIndex = matchIndex + normalizedQuery.length
    }
}

private fun highlightSyntaxWithSearchQuery(
    code: String,
    language: String,
    query: String?
): AnnotatedString {
    val base = highlightSyntax(code, language)
    val normalizedQuery = query?.trim().orEmpty()
    if (normalizedQuery.isBlank()) return base

    val builder = AnnotatedString.Builder(base)
    val lowerText = code.lowercase(Locale.getDefault())
    val lowerQuery = normalizedQuery.lowercase(Locale.getDefault())
    var startIndex = 0
    while (startIndex < code.length) {
        val matchIndex = lowerText.indexOf(lowerQuery, startIndex)
        if (matchIndex < 0) break
        builder.addStyle(
            SpanStyle(
                background = Color(0x55FFD54F),
                fontWeight = FontWeight.Bold
            ),
            start = matchIndex,
            end = matchIndex + normalizedQuery.length
        )
        startIndex = matchIndex + normalizedQuery.length
    }
    return builder.toAnnotatedString()
}

private fun buildPreviewCodeLine(
    lineNumber: Int,
    lineText: String,
    lineNumberWidth: Int,
    language: String,
    query: String?
): AnnotatedString {
    val builder = AnnotatedString.Builder()
    builder.pushStyle(
        SpanStyle(
            color = Color(0xFF6A9955)
        )
    )
    builder.append(lineNumber.toString().padStart(lineNumberWidth, ' '))
    builder.append(" | ")
    builder.pop()
    builder.append(highlightSyntaxWithSearchQuery(lineText, language, query))
    return builder.toAnnotatedString()
}

@Composable
private fun ProjectCodeEditorPane(
    editorValue: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    language: String?,
    searchQuery: String,
    currentMatch: TextRange?,
    diagnostics: List<ProjectEditorDiagnostic>,
    verticalScrollState: androidx.compose.foundation.ScrollState,
    horizontalScrollState: androidx.compose.foundation.ScrollState,
    modifier: Modifier = Modifier
) {
    val visibleTextStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        lineHeight = 20.sp,
        color = Color(0xFFD4D4D4)
    )
    val annotatedText = remember(editorValue.text, language, searchQuery, currentMatch, diagnostics) {
        buildHighlightedEditorText(
            code = editorValue.text,
            language = language.orEmpty(),
            query = searchQuery,
            currentMatch = currentMatch,
            diagnostics = diagnostics
        )
    }
    val lineNumbersText = remember(editorValue.text, diagnostics) {
        buildEditorLineNumbersText(editorValue.text, diagnostics)
    }

    Row(modifier = modifier) {
        Box(
            modifier = Modifier
                .width(56.dp)
                .fillMaxHeight()
                .background(Color(0xFF252526))
        ) {
            Text(
                text = lineNumbersText,
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(verticalScrollState)
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                style = visibleTextStyle.copy(
                    color = Color(0xFF6A9955)
                )
            )
        }
        Box(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(Color(0xFF333333))
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .horizontalScroll(horizontalScrollState)
        ) {
            Box(
                modifier = Modifier
                    .verticalScroll(verticalScrollState)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .defaultMinSize(minWidth = 320.dp, minHeight = 420.dp)
            ) {
                Text(
                    text = annotatedText,
                    style = visibleTextStyle
                )
                BasicTextField(
                    value = editorValue,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .matchParentSize()
                        .widthIn(min = 320.dp)
                        .heightIn(min = 420.dp),
                    textStyle = visibleTextStyle.copy(color = Color.Transparent),
                    cursorBrush = SolidColor(Color(0xFFD4D4D4))
                )
            }
        }
    }
}

private fun buildHighlightedEditorText(
    code: String,
    language: String,
    query: String,
    currentMatch: TextRange?,
    diagnostics: List<ProjectEditorDiagnostic>
): AnnotatedString {
    val safeCode = code.ifEmpty { " " }
    val builder = AnnotatedString.Builder(highlightSyntaxWithSearchQuery(safeCode, language, query))
    diagnostics.forEach { diagnostic ->
        val safeStart = diagnostic.startIndex.coerceIn(0, safeCode.length)
        val safeEnd = diagnostic.endIndex.coerceIn(safeStart, safeCode.length)
        if (safeEnd > safeStart) {
            builder.addStyle(
                SpanStyle(
                    background = Color(0x55FF5252),
                    textDecoration = TextDecoration.Underline
                ),
                start = safeStart,
                end = safeEnd
            )
        }
    }
    currentMatch?.let { range ->
        if (range.min >= 0 && range.max <= safeCode.length && range.max > range.min) {
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
    return builder.toAnnotatedString()
}

private fun buildEditorLineNumbersText(
    content: String,
    diagnostics: List<ProjectEditorDiagnostic>
): AnnotatedString {
    val lineCount = projectLineCount(content)
    val lineWidth = lineCount.toString().length.coerceAtLeast(2)
    val errorLines = diagnostics.map { it.lineNumber }.toSet()
    return buildAnnotatedString {
        for (lineNumber in 1..lineCount) {
            pushStyle(
                SpanStyle(
                    color = if (errorLines.contains(lineNumber)) Color(0xFFFF6B6B) else Color(0xFF6A9955),
                    fontWeight = if (errorLines.contains(lineNumber)) FontWeight.Bold else FontWeight.Normal
                )
            )
            append(lineNumber.toString().padStart(lineWidth, ' '))
            pop()
            if (lineNumber < lineCount) append('\n')
        }
    }
}

private fun buildProjectEditorDiagnostics(
    content: String,
    language: String?
): List<ProjectEditorDiagnostic> {
    if (content.isEmpty()) return emptyList()
    val normalizedLanguage = normalizeDiagnosticLanguage(language)
    return buildList {
        addAll(detectGitConflictDiagnostics(content))
        if (shouldUseBracketDiagnostics(normalizedLanguage)) {
            addAll(detectBracketDiagnostics(content))
        }
        when (normalizedLanguage) {
            "json" -> addAll(detectJsonDiagnostics(content))
            "python" -> addAll(detectPythonDiagnostics(content))
            "bash" -> addAll(detectShellDiagnostics(content))
            "c", "cpp" -> addAll(detectCppDiagnostics(content))
            "toml" -> addAll(detectTomlDiagnostics(content))
            "ini", "conf", "properties" -> addAll(detectIniLikeDiagnostics(content))
            "yaml" -> addAll(detectYamlDiagnostics(content))
            "html", "xml" -> addAll(detectMarkupDiagnostics(content))
            "css" -> addAll(detectCssDiagnostics(content))
            "sql" -> addAll(detectSqlDiagnostics(content))
            "markdown" -> addAll(detectMarkdownDiagnostics(content))
        }
    }
        .distinctBy { Triple(it.startIndex, it.endIndex, it.message) }
        .sortedWith(compareBy<ProjectEditorDiagnostic> { it.lineNumber }.thenBy { it.startIndex })
}

private fun detectGitConflictBlocks(content: String): List<ProjectGitConflictBlock> {
    if (content.isBlank()) return emptyList()
    val lines = content.lines()
    val blocks = mutableListOf<ProjectGitConflictBlock>()
    var index = 0
    while (index < lines.size) {
        val startMarker = lines[index].trimStart()
        if (!startMarker.startsWith("<<<<<<<")) {
            index++
            continue
        }
        val startLine = index + 1
        val currentLabel = startMarker.removePrefix("<<<<<<<").trim().ifBlank { "当前分支" }
        val currentLines = mutableListOf<String>()
        index++
        while (index < lines.size && !lines[index].trimStart().startsWith("=======")) {
            currentLines += lines[index]
            index++
        }
        if (index >= lines.size) break
        val dividerLine = index + 1
        index++
        val incomingLines = mutableListOf<String>()
        while (index < lines.size && !lines[index].trimStart().startsWith(">>>>>>>")) {
            incomingLines += lines[index]
            index++
        }
        if (index >= lines.size) break
        val endLine = index + 1
        val incomingLabel = lines[index].trimStart().removePrefix(">>>>>>>").trim().ifBlank { "合并目标" }
        blocks += ProjectGitConflictBlock(
            startLine = startLine,
            dividerLine = dividerLine,
            endLine = endLine,
            currentLabel = currentLabel,
            incomingLabel = incomingLabel,
            currentLines = currentLines,
            incomingLines = incomingLines
        )
        index++
    }
    return blocks
}

private fun detectGitConflictDiagnostics(content: String): List<ProjectEditorDiagnostic> {
    val diagnostics = mutableListOf<ProjectEditorDiagnostic>()
    content.lines().forEachIndexed { index, rawLine ->
        val lineNumber = index + 1
        val trimmed = rawLine.trimStart()
        when {
            trimmed.startsWith("<<<<<<<") ->
                diagnostics += diagnosticAtLine(content, lineNumber, "发现 Git 冲突起始标记 `<<<<<<<`，请手动选择保留内容")

            trimmed.startsWith("=======") ->
                diagnostics += diagnosticAtLine(content, lineNumber, "发现 Git 冲突分隔标记 `=======`")

            trimmed.startsWith(">>>>>>>") ->
                diagnostics += diagnosticAtLine(content, lineNumber, "发现 Git 冲突结束标记 `>>>>>>>`")
        }
    }
    return diagnostics
}

private fun normalizeDiagnosticLanguage(language: String?): String {
    return when (language?.lowercase(Locale.getDefault())) {
        "kt" -> "kotlin"
        "js", "jsx" -> "javascript"
        "ts", "tsx" -> "typescript"
        "sh", "shell" -> "bash"
        "gradle" -> "groovy"
        "c++", "cxx", "hpp", "h", "hh", "hxx" -> "cpp"
        "htm" -> "html"
        "cfg" -> "conf"
        else -> language?.lowercase(Locale.getDefault()).orEmpty()
    }
}

private fun shouldUseBracketDiagnostics(language: String): Boolean {
    return language in setOf(
        "kotlin", "java", "groovy", "javascript", "typescript",
        "rust", "c", "cpp", "bash", "python", "sql", "css", "json"
    )
}

private fun detectBracketDiagnostics(content: String): List<ProjectEditorDiagnostic> {
    val diagnostics = mutableListOf<ProjectEditorDiagnostic>()
    val stack = ArrayDeque<Pair<Char, Int>>()
    var inSingleQuote = false
    var inDoubleQuote = false
    var singleQuoteStart = -1
    var doubleQuoteStart = -1
    var escaped = false
    content.forEachIndexed { index, char ->
        if (escaped) {
            escaped = false
            return@forEachIndexed
        }
        if (char == '\\') {
            if (inSingleQuote || inDoubleQuote) {
                escaped = true
            }
            return@forEachIndexed
        }
        if (inSingleQuote) {
            if (char == '\'') {
                inSingleQuote = false
                singleQuoteStart = -1
            }
            return@forEachIndexed
        }
        if (inDoubleQuote) {
            if (char == '"') {
                inDoubleQuote = false
                doubleQuoteStart = -1
            }
            return@forEachIndexed
        }
        when (char) {
            '\'' -> {
                inSingleQuote = true
                singleQuoteStart = index
            }
            '"' -> {
                inDoubleQuote = true
                doubleQuoteStart = index
            }
            '(', '[', '{' -> stack.addLast(char to index)
            ')', ']', '}' -> {
                val expectedOpen = matchingOpenBracket(char)
                val top = stack.removeLastOrNull()
                if (top == null) {
                    diagnostics += diagnosticAtOffset(content, index, "多余的关闭括号 `$char`")
                } else if (top.first != expectedOpen) {
                    diagnostics += diagnosticAtOffset(
                        content = content,
                        offset = index,
                        message = "括号不匹配，遇到 `$char`，对应开始是 `${top.first}`"
                    )
                }
            }
        }
    }
    if (inSingleQuote && singleQuoteStart >= 0) {
        diagnostics += diagnosticAtOffset(content, singleQuoteStart, "单引号字符串未闭合")
    }
    if (inDoubleQuote && doubleQuoteStart >= 0) {
        diagnostics += diagnosticAtOffset(content, doubleQuoteStart, "双引号字符串未闭合")
    }
    stack.forEach { (bracket, offset) ->
        diagnostics += diagnosticAtOffset(content, offset, "括号 `$bracket` 没有闭合")
    }
    return diagnostics
}

private fun detectJsonDiagnostics(content: String): List<ProjectEditorDiagnostic> {
    val normalized = content.trim()
    if (normalized.isEmpty()) return emptyList()
    val error = runCatching { PROJECT_EDITOR_JSON.parseToJsonElement(content) }.exceptionOrNull() ?: return emptyList()
    val offset = extractJsonDiagnosticOffset(error.message).coerceIn(0, (content.length - 1).coerceAtLeast(0))
    val message = error.message
        ?.substringBefore('\n')
        ?.replace("Unexpected JSON token at offset", "JSON 错误，位置")
        ?.ifBlank { "JSON 结构错误" }
        ?: "JSON 结构错误"
    return listOf(diagnosticAtOffset(content, offset, message))
}

private fun detectPythonDiagnostics(content: String): List<ProjectEditorDiagnostic> {
    val diagnostics = mutableListOf<ProjectEditorDiagnostic>()
    val lines = content.lines()
    val headerRegex = Regex("""^\s*(if|elif|else|for|while|try|except|finally|with|def|class|async def|match|case)\b""")
    lines.forEachIndexed { index, line ->
        val trimmed = line.trim()
        if (trimmed.isBlank() || trimmed.startsWith("#")) return@forEachIndexed
        val leading = line.takeWhile { it == ' ' || it == '\t' }
        if (leading.contains(' ') && leading.contains('\t')) {
            diagnostics += diagnosticAtLine(content, index + 1, "Python 缩进不要混用空格和 Tab")
        }
        if (headerRegex.containsMatchIn(line) && !trimmed.endsWith(":")) {
            diagnostics += diagnosticAtLine(content, index + 1, "Python 代码块声明通常需要以冒号结尾")
        }
        if (trimmed.endsWith(":")) {
            val currentIndent = leadingIndentWidth(line)
            val nextLineIndex = nextNonEmptyLineIndex(lines, index + 1) ?: return@forEachIndexed
            val nextIndent = leadingIndentWidth(lines[nextLineIndex])
            if (nextIndent <= currentIndent) {
                diagnostics += diagnosticAtLine(content, index + 1, "Python 代码块后面缺少更深一级的缩进")
            }
        }
    }
    return diagnostics
}

private fun detectShellDiagnostics(content: String): List<ProjectEditorDiagnostic> {
    val diagnostics = mutableListOf<ProjectEditorDiagnostic>()
    val stack = ArrayDeque<Pair<String, Int>>()
    val lines = content.lines()
    lines.forEachIndexed { index, rawLine ->
        val lineNumber = index + 1
        val line = rawLine.substringBefore('#').trim()
        if (line.isBlank()) return@forEachIndexed
        val token = line.substringBefore(' ')
        when {
            "[[" in line || "]]" in line ->
                diagnostics += diagnosticAtLine(content, lineNumber, "手机常见的 /system/bin/sh 是 POSIX shell，不支持 `[[ ... ]]`，请改用 `[ ... ]`")
            Regex("""^\s*function\b""").containsMatchIn(rawLine) ->
                diagnostics += diagnosticAtLine(content, lineNumber, "POSIX shell 不支持 `function` 关键字，请直接写 `name()`")
            Regex("""(^|[\s;])source\s+""").containsMatchIn(line) ->
                diagnostics += diagnosticAtLine(content, lineNumber, "POSIX shell 不支持 `source`，请改用 `.`")
            "<<<" in line ->
                diagnostics += diagnosticAtLine(content, lineNumber, "POSIX shell 不支持 here-string `<<<`")
            "<(" in line || ">(" in line ->
                diagnostics += diagnosticAtLine(content, lineNumber, "POSIX shell 不支持进程替换 `<(...)` / `>(...)`")
            Regex("""(^|[\s;])(local|declare|typeset|select|coproc|mapfile|readarray|shopt|bind)\b""").containsMatchIn(line) ->
                diagnostics += diagnosticAtLine(content, lineNumber, "当前语法更像 Bash 扩展，基础 POSIX shell 里通常不可用")
            Regex("""\[[^]]*==[^]]*]""").containsMatchIn(line) ->
                diagnostics += diagnosticAtLine(content, lineNumber, "POSIX `[` 测试里建议使用单个 `=`，不要用 `==`")
            "=~" in line ->
                diagnostics += diagnosticAtLine(content, lineNumber, "POSIX shell 不支持 `=~` 正则比较")
            Regex("""\b[A-Za-z_][A-Za-z0-9_]*\s*=\s*\(""").containsMatchIn(line) ->
                diagnostics += diagnosticAtLine(content, lineNumber, "POSIX shell 不支持 Bash 数组字面量 `name=(...)`")
            Regex("""\{[^{}]*\.\.[^{}]*\}""").containsMatchIn(line) ->
                diagnostics += diagnosticAtLine(content, lineNumber, "POSIX shell 不支持 Bash 花括号展开 `{1..3}`")
        }
        when {
            line.startsWith("if ") && !Regex("""(^|[;\s])then($|[\s;])""").containsMatchIn(line) -> {
                val nextLine = nextNonEmptyLineIndex(lines, index + 1)?.let { lines[it].trim() }
                if (nextLine != "then") {
                    diagnostics += diagnosticAtLine(content, lineNumber, "POSIX if 语句需要 `then`")
                }
            }
            (line.startsWith("for ") || line.startsWith("while ") || line.startsWith("until ")) &&
                !Regex("""(^|[;\s])do($|[\s;])""").containsMatchIn(line) -> {
                val nextLine = nextNonEmptyLineIndex(lines, index + 1)?.let { lines[it].trim() }
                if (nextLine != "do") {
                    diagnostics += diagnosticAtLine(content, lineNumber, "POSIX 循环语句需要 `do`")
                }
            }
            line.startsWith("case ") && !Regex("""\bin\b""").containsMatchIn(line) -> {
                diagnostics += diagnosticAtLine(content, lineNumber, "POSIX case 语句通常需要 `in`")
            }
        }
        when {
            line.startsWith("if ") -> stack.addLast("if" to lineNumber)
            line.startsWith("for ") || line.startsWith("while ") || line.startsWith("until ") -> stack.addLast("do" to lineNumber)
            line.startsWith("case ") -> stack.addLast("case" to lineNumber)
            token == "fi" -> consumeShellCloser(content, stack, diagnostics, "if", "fi", lineNumber)
            token == "done" -> consumeShellCloser(content, stack, diagnostics, "do", "done", lineNumber)
            token == "esac" -> consumeShellCloser(content, stack, diagnostics, "case", "esac", lineNumber)
        }
    }
    stack.forEach { (block, lineNumber) ->
        val closer = when (block) {
            "if" -> "fi"
            "do" -> "done"
            else -> "esac"
        }
        diagnostics += diagnosticAtLine(content, lineNumber, "Shell 代码块缺少结束标记 `$closer`")
    }
    return diagnostics
}

private fun detectCppDiagnostics(content: String): List<ProjectEditorDiagnostic> {
    val diagnostics = mutableListOf<ProjectEditorDiagnostic>()
    val lines = content.lines()
    val preprocessorStack = ArrayDeque<Pair<String, Int>>()
    val classLikeStack = ArrayDeque<ProjectCppClassLikeBlock>()
    var braceDepth = 0

    lines.forEachIndexed { index, rawLine ->
        val lineNumber = index + 1
        val trimmed = rawLine.trim()
        if (trimmed.isBlank()) {
            braceDepth += rawLine.count { it == '{' } - rawLine.count { it == '}' }
            return@forEachIndexed
        }

        handleCppPreprocessorLine(
            content = content,
            rawLine = rawLine,
            lineNumber = lineNumber,
            stack = preprocessorStack,
            diagnostics = diagnostics
        )

        if (Regex("""^\s*(public|private|protected)\s*$""").matches(rawLine)) {
            diagnostics += diagnosticAtLine(content, lineNumber, "C++ 访问控制关键字后面通常需要冒号")
        }

        if (looksLikeCppMissingSemicolon(trimmed)) {
            diagnostics += diagnosticAtLine(content, lineNumber, "这行看起来缺少分号 `;`")
        }

        Regex("""\b(class|struct|enum)\b[^;{]*\{""")
            .findAll(rawLine)
            .forEach { match ->
                classLikeStack.addLast(
                    ProjectCppClassLikeBlock(
                        kind = match.groupValues[1],
                        startLine = lineNumber,
                        expectedBraceDepth = braceDepth + 1
                    )
                )
            }

        var closeCount = rawLine.count { it == '}' }
        while (closeCount > 0 && classLikeStack.isNotEmpty()) {
            val top = classLikeStack.lastOrNull() ?: break
            if (top.expectedBraceDepth == braceDepth) {
                val trailing = trimmed.substringAfterLast('}', "")
                if (!trailing.trimStart().startsWith(";")) {
                    diagnostics += diagnosticAtLine(content, lineNumber, "${top.kind} 定义结束后的 `}` 后面通常需要分号 `;`")
                }
                classLikeStack.removeLast()
            }
            closeCount--
        }

        braceDepth += rawLine.count { it == '{' } - rawLine.count { it == '}' }
    }

    preprocessorStack.forEach { (token, lineNumber) ->
        diagnostics += diagnosticAtLine(content, lineNumber, "预处理块 `$token` 缺少对应的 `#endif`")
    }
    return diagnostics
}

private fun handleCppPreprocessorLine(
    content: String,
    rawLine: String,
    lineNumber: Int,
    stack: ArrayDeque<Pair<String, Int>>,
    diagnostics: MutableList<ProjectEditorDiagnostic>
) {
    val trimmed = rawLine.trim()
    if (!trimmed.startsWith("#")) return

    if (Regex("""^\s*#\s*include\b""").containsMatchIn(rawLine) &&
        !Regex("""^\s*#\s*include\s*(<[^>]+>|"[^"]+")\s*$""").matches(rawLine)
    ) {
        diagnostics += diagnosticAtLine(content, lineNumber, "#include 后面应使用 `<...>` 或 `\"...\"`")
    }

    when {
        Regex("""^\s*#\s*(if|ifdef|ifndef)\b""").containsMatchIn(rawLine) -> {
            val token = Regex("""^\s*#\s*(if|ifdef|ifndef)\b""")
                .find(rawLine)
                ?.groupValues
                ?.getOrNull(1)
                .orEmpty()
            stack.addLast(token to lineNumber)
        }
        Regex("""^\s*#\s*endif\b""").containsMatchIn(rawLine) -> {
            if (stack.isEmpty()) {
                diagnostics += diagnosticAtLine(content, lineNumber, "#endif 没有匹配的 #if / #ifdef / #ifndef")
            } else {
                stack.removeLast()
            }
        }
        Regex("""^\s*#\s*(else|elif)\b""").containsMatchIn(rawLine) -> {
            if (stack.isEmpty()) {
                diagnostics += diagnosticAtLine(content, lineNumber, "#else / #elif 没有匹配的条件编译开始指令")
            }
        }
    }
}

private fun looksLikeCppMissingSemicolon(line: String): Boolean {
    if (line.startsWith("#")) return false
    if (line.endsWith(";") || line.endsWith("{") || line.endsWith("}") || line.endsWith(":") ||
        line.endsWith(",") || line.endsWith("\\")
    ) return false
    if (line.startsWith("//") || line.startsWith("/*") || line.startsWith("*")) return false
    if (Regex("""^(if|else|for|while|switch|case|default|do|try|catch|namespace|class|struct|enum|template)\b""").containsMatchIn(line)) {
        return false
    }
    if (Regex("""^(return|break|continue|throw|using\s+namespace|typedef)\b""").containsMatchIn(line)) {
        return true
    }
    if (Regex("""^[A-Za-z_][\w:<>~*&\s]*\([^;{}]*\)$""").matches(line) &&
        !Regex("""^(if|for|while|switch|catch)\b""").containsMatchIn(line)
    ) {
        return true
    }
    if (Regex("""^[A-Za-z_][\w:<>~*&\s]+\s+[A-Za-z_]\w*(\s*=\s*.+)?$""").matches(line) &&
        !line.contains(" operator ")
    ) {
        return true
    }
    return false
}

private fun consumeShellCloser(
    content: String,
    stack: ArrayDeque<Pair<String, Int>>,
    diagnostics: MutableList<ProjectEditorDiagnostic>,
    expected: String,
    closer: String,
    lineNumber: Int
) {
    val top = stack.removeLastOrNull()
    if (top == null || top.first != expected) {
        diagnostics += diagnosticAtLine(content, lineNumber, "Shell 结束标记 `$closer` 没有对应的开始块")
    }
}

private fun detectTomlDiagnostics(content: String): List<ProjectEditorDiagnostic> {
    val diagnostics = mutableListOf<ProjectEditorDiagnostic>()
    content.lines().forEachIndexed { index, rawLine ->
        val line = rawLine.trim()
        if (line.isBlank() || line.startsWith("#")) return@forEachIndexed
        if (line.startsWith("[") || line.startsWith("[[")) {
            if (!(line.endsWith("]") || line.endsWith("]]"))) {
                diagnostics += diagnosticAtLine(content, index + 1, "TOML 节名缺少闭合方括号")
            }
            return@forEachIndexed
        }
        if (!line.contains('=')) {
            diagnostics += diagnosticAtLine(content, index + 1, "TOML 键值行缺少 `=`")
        }
    }
    return diagnostics
}

private fun detectIniLikeDiagnostics(content: String): List<ProjectEditorDiagnostic> {
    val diagnostics = mutableListOf<ProjectEditorDiagnostic>()
    content.lines().forEachIndexed { index, rawLine ->
        val line = rawLine.trim()
        if (line.isBlank() || line.startsWith("#") || line.startsWith(";")) return@forEachIndexed
        if (line.startsWith("[") || line.endsWith("]")) {
            if (!(line.startsWith("[") && line.endsWith("]"))) {
                diagnostics += diagnosticAtLine(content, index + 1, "配置节标题需要成对的方括号")
            }
            return@forEachIndexed
        }
        if (!line.contains('=') && !line.contains(':')) {
            diagnostics += diagnosticAtLine(content, index + 1, "配置项缺少 `=` 或 `:`")
        }
    }
    return diagnostics
}

private fun detectYamlDiagnostics(content: String): List<ProjectEditorDiagnostic> {
    val diagnostics = mutableListOf<ProjectEditorDiagnostic>()
    content.lines().forEachIndexed { index, rawLine ->
        val line = rawLine.trimEnd()
        val trimmed = line.trim()
        if (trimmed.isBlank() || trimmed.startsWith("#")) return@forEachIndexed
        val leading = rawLine.takeWhile { it == ' ' || it == '\t' }
        if (leading.contains('\t')) {
            diagnostics += diagnosticAtLine(content, index + 1, "YAML 缩进不要使用 Tab")
        }
        val isListItem = trimmed.startsWith("- ")
        val looksLikeScalar = trimmed.startsWith("|") || trimmed.startsWith(">") || trimmed == "---" || trimmed == "..."
        if (!isListItem && !looksLikeScalar && !trimmed.contains(":")) {
            diagnostics += diagnosticAtLine(content, index + 1, "YAML 映射项通常需要 `key:` 形式")
        }
    }
    return diagnostics
}

private fun detectMarkupDiagnostics(content: String): List<ProjectEditorDiagnostic> {
    val diagnostics = mutableListOf<ProjectEditorDiagnostic>()
    val tagRegex = Regex("""<\s*(/)?\s*([A-Za-z][A-Za-z0-9:_-]*)([^>]*)>""")
    val stack = ArrayDeque<Pair<String, Int>>()
    val voidTags = setOf("br", "hr", "img", "input", "meta", "link", "area", "base", "col", "embed", "source", "track", "wbr")
    if (content.contains("<!--") && !content.contains("-->")) {
        diagnostics += diagnosticAtOffset(content, content.indexOf("<!--"), "HTML/XML 注释没有闭合")
    }
    tagRegex.findAll(content).forEach { match ->
        val isClosing = !match.groupValues[1].isNullOrBlank()
        val tag = match.groupValues[2].lowercase(Locale.getDefault())
        val fullText = match.value
        val offset = match.range.first
        val isSelfClosing = fullText.endsWith("/>") || tag in voidTags
        when {
            isClosing -> {
                val top = stack.removeLastOrNull()
                if (top == null || top.first != tag) {
                    diagnostics += diagnosticAtOffset(content, offset, "标签 </$tag> 没有匹配的开始标签")
                }
            }
            !isSelfClosing -> stack.addLast(tag to offset)
        }
    }
    stack.forEach { (tag, offset) ->
        diagnostics += diagnosticAtOffset(content, offset, "标签 <$tag> 没有闭合")
    }
    return diagnostics
}

private fun detectCssDiagnostics(content: String): List<ProjectEditorDiagnostic> {
    val diagnostics = mutableListOf<ProjectEditorDiagnostic>()
    var braceDepth = 0
    content.lines().forEachIndexed { index, rawLine ->
        val line = rawLine.trim()
        val lineNumber = index + 1
        if (line.isBlank() || line.startsWith("/*") || line.startsWith("*") || line.startsWith("//")) {
            braceDepth += rawLine.count { it == '{' } - rawLine.count { it == '}' }
            return@forEachIndexed
        }
        val insideBlock = braceDepth > 0
        if (insideBlock && !line.endsWith("{") && !line.endsWith("}") && !line.startsWith("@")) {
            if (!line.contains(':')) {
                diagnostics += diagnosticAtLine(content, lineNumber, "CSS 属性行缺少冒号")
            }
        }
        braceDepth += rawLine.count { it == '{' } - rawLine.count { it == '}' }
    }
    return diagnostics
}

private fun detectSqlDiagnostics(content: String): List<ProjectEditorDiagnostic> {
    val diagnostics = mutableListOf<ProjectEditorDiagnostic>()
    val tokenRegex = Regex("""\b(case|end)\b""", RegexOption.IGNORE_CASE)
    val stack = ArrayDeque<Int>()
    content.lines().forEachIndexed { index, line ->
        tokenRegex.findAll(line).forEach { match ->
            when (match.value.lowercase(Locale.getDefault())) {
                "case" -> stack.addLast(index + 1)
                "end" -> if (stack.isEmpty()) {
                    diagnostics += diagnosticAtLine(content, index + 1, "SQL 的 END 没有匹配到 CASE")
                } else {
                    stack.removeLast()
                }
            }
        }
    }
    stack.forEach { lineNumber ->
        diagnostics += diagnosticAtLine(content, lineNumber, "SQL 的 CASE 缺少对应的 END")
    }
    return diagnostics
}

private fun detectMarkdownDiagnostics(content: String): List<ProjectEditorDiagnostic> {
    val diagnostics = mutableListOf<ProjectEditorDiagnostic>()
    val lines = content.lines()
    val fenceLines = lines.mapIndexedNotNull { index, line ->
        if (line.trimStart().startsWith("```")) index + 1 else null
    }
    if (fenceLines.size % 2 == 1) {
        diagnostics += diagnosticAtLine(content, fenceLines.last(), "Markdown 代码块围栏 ``` 没有闭合")
    }
    return diagnostics
}

private fun nextNonEmptyLineIndex(lines: List<String>, startIndex: Int): Int? {
    for (index in startIndex until lines.size) {
        if (lines[index].trim().isNotBlank()) return index
    }
    return null
}

private fun diagnosticAtOffset(
    content: String,
    offset: Int,
    message: String
): ProjectEditorDiagnostic {
    val safeOffset = offset.coerceIn(0, (content.length - 1).coerceAtLeast(0))
    val lineNumber = currentLineNumber(content, safeOffset)
    val endIndex = (safeOffset + 1).coerceAtMost(content.length)
    return ProjectEditorDiagnostic(
        lineNumber = lineNumber,
        startIndex = safeOffset,
        endIndex = endIndex.coerceAtLeast(safeOffset),
        message = message
    )
}

private fun diagnosticAtLine(
    content: String,
    lineNumber: Int,
    message: String
): ProjectEditorDiagnostic {
    return diagnosticAtOffset(content, lineStartOffset(content, lineNumber), message)
}

private fun matchingOpenBracket(close: Char): Char {
    return when (close) {
        ')' -> '('
        ']' -> '['
        '}' -> '{'
        else -> close
    }
}

private fun extractJsonDiagnosticOffset(message: String?): Int {
    val offsetText = Regex("""offset\s+(\d+)""")
        .find(message.orEmpty())
        ?.groupValues
        ?.getOrNull(1)
    return offsetText?.toIntOrNull() ?: 0
}

private fun buildProjectOutlineEntries(
    foldRegions: List<ProjectFoldRegion>,
    language: String?
): List<ProjectOutlineEntry> {
    val sorted = foldRegions.sortedBy { it.startLine }
    return sorted.map { region ->
        ProjectOutlineEntry(
            startLine = region.startLine,
            lineNumber = region.startLine,
            depth = outlineDepthForRegion(region, sorted),
            label = normalizeOutlineLabel(region.headerLine, language),
            kind = outlineKindForHeader(region.headerLine, language)
        )
    }
}

private fun outlineDepthForRegion(
    region: ProjectFoldRegion,
    allRegions: List<ProjectFoldRegion>
): Int {
    return allRegions.count { candidate ->
        candidate.startLine < region.startLine && candidate.endLine >= region.endLine
    }
}

private fun normalizeOutlineLabel(headerLine: String, language: String?): String {
    val trimmed = headerLine.trim()
        .removeSuffix("{")
        .removeSuffix(":")
        .trim()
    val compact = trimmed.replace(Regex("\\s+"), " ")
    val kind = outlineKindForHeader(headerLine, language)
    return when (kind) {
        "类" -> compact.ifBlank { "未命名类" }
        "函数" -> compact.ifBlank { "未命名函数" }
        else -> compact.ifBlank { "代码块" }
    }
}

private fun outlineKindForHeader(headerLine: String, language: String?): String {
    val normalized = headerLine.trim().lowercase(Locale.getDefault())
    return when {
        normalized.startsWith("class ") || normalized.startsWith("interface ") ||
            normalized.startsWith("object ") || normalized.startsWith("enum ") ||
            normalized.startsWith("data class ") || normalized.startsWith("sealed class ") -> "类"
        normalized.startsWith("fun ") || normalized.contains(" fun ") ||
            normalized.startsWith("def ") || normalized.startsWith("async def ") ||
            normalized.contains("=>") -> "函数"
        language?.lowercase(Locale.getDefault()) == "python" && normalized.endsWith(":") -> "代码块"
        else -> "代码块"
    }
}

private fun detectProjectFoldRegions(content: String, language: String?): List<ProjectFoldRegion> {
    val lines = content.lines()
    if (lines.size < 3) return emptyList()
    return when (language?.lowercase(Locale.getDefault())) {
        "python" -> detectIndentFoldRegions(lines)
        else -> detectBraceFoldRegions(lines)
    }
}

private fun detectBraceFoldRegions(lines: List<String>): List<ProjectFoldRegion> {
    val stack = ArrayDeque<Int>()
    val regions = mutableListOf<ProjectFoldRegion>()
    lines.forEachIndexed { index, line ->
        val lineNumber = index + 1
        line.forEach { ch ->
            when (ch) {
                '{' -> stack.addLast(lineNumber)
                '}' -> {
                    val startLine = stack.removeLastOrNull() ?: return@forEach
                    if (lineNumber - startLine >= 2) {
                        val headerLine = lines.getOrNull(startLine - 1)?.trimEnd().orEmpty()
                        if (isFoldableHeaderLine(headerLine)) {
                            regions += ProjectFoldRegion(
                                startLine = startLine,
                                endLine = lineNumber,
                                headerLine = headerLine
                            )
                        }
                    }
                }
            }
        }
    }
    return regions
        .distinctBy { it.startLine to it.endLine }
        .sortedBy { it.startLine }
}

private fun detectIndentFoldRegions(lines: List<String>): List<ProjectFoldRegion> {
    val regions = mutableListOf<ProjectFoldRegion>()
    var index = 0
    while (index < lines.size - 1) {
        val line = lines[index]
        val trimmed = line.trim()
        if (trimmed.isBlank() || !isPythonFoldableHeaderLine(trimmed)) {
            index++
            continue
        }
        val currentIndent = leadingIndentWidth(line)
        var nextIndex = index + 1
        while (nextIndex < lines.size && lines[nextIndex].trim().isBlank()) {
            nextIndex++
        }
        if (nextIndex >= lines.size) break
        val childIndent = leadingIndentWidth(lines[nextIndex])
        if (childIndent <= currentIndent) {
            index++
            continue
        }
        var endIndex = nextIndex
        var scanIndex = nextIndex + 1
        while (scanIndex < lines.size) {
            val candidate = lines[scanIndex]
            if (candidate.trim().isNotBlank() && leadingIndentWidth(candidate) <= currentIndent) {
                break
            }
            if (candidate.trim().isNotBlank()) {
                endIndex = scanIndex
            }
            scanIndex++
        }
        if (endIndex - index >= 2) {
            regions += ProjectFoldRegion(
                startLine = index + 1,
                endLine = endIndex + 1,
                headerLine = line.trimEnd()
            )
        }
        index++
    }
    return regions
}

private fun isFoldableHeaderLine(line: String): Boolean {
    val trimmed = line.trim()
    if (trimmed.isBlank()) return false
    if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) return false
    if (!trimmed.contains('{')) return false
    return true
}

private fun isPythonFoldableHeaderLine(line: String): Boolean {
    return line.startsWith("def ") ||
        line.startsWith("class ") ||
        line.startsWith("async def ") ||
        line.endsWith(":")
}

private fun leadingIndentWidth(line: String): Int {
    var width = 0
    line.forEach { ch ->
        when (ch) {
            ' ' -> width++
            '\t' -> width += 4
            else -> return width
        }
    }
    return width
}

private fun focusedPreviewRange(
    content: String,
    centerLine: Int,
    radius: Int
): ProjectFocusedPreviewRange? {
    if (content.isBlank()) return null
    val lines = content.lines()
    if (lines.isEmpty()) return null
    val safeCenter = centerLine.coerceIn(1, lines.size)
    val start = (safeCenter - radius).coerceAtLeast(1)
    val end = (safeCenter + radius).coerceAtMost(lines.size)
    val lineNumberWidth = end.toString().length.coerceAtLeast(2)
    val excerpt = buildString {
        for (lineNumber in start..end) {
            append(lineNumber.toString().padStart(lineNumberWidth, ' '))
            append(" | ")
            append(lines[lineNumber - 1])
            if (lineNumber < end) append('\n')
        }
    }
    return ProjectFocusedPreviewRange(
        startLine = start,
        endLine = end,
        excerpt = excerpt
    )
}

private fun projectAncestorDirs(rootPath: String, filePath: String): Set<String> {
    val root = File(rootPath)
    val target = File(filePath).parentFile ?: return emptySet()
    val dirs = linkedSetOf<String>()
    var current: File? = target
    while (current != null && current.absolutePath.startsWith(root.absolutePath)) {
        if (current.absolutePath != root.absolutePath) {
            dirs += current.absolutePath
        }
        current = current.parentFile
    }
    return dirs
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

private fun currentLineNumber(content: String, offset: Int): Int {
    val safeOffset = offset.coerceIn(0, content.length)
    return content.take(safeOffset).count { it == '\n' } + 1
}

private fun lineStartOffset(content: String, lineNumber: Int): Int {
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

private fun projectLineCount(content: String): Int = content.lines().size.coerceAtLeast(1)

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
private const val MAX_PROJECT_SEARCH_RESULTS = 60
private const val MAX_PROJECT_SEARCH_FILE_BYTES = 256 * 1024L
private const val MAX_PROJECT_EDITOR_HISTORY = 80
private const val PROJECT_PREVIEW_FOCUS_RADIUS = 12
private const val AI_COMPLETION_CONTEXT_CHARS = 2400
private val PROJECT_EDITOR_JSON = Json { ignoreUnknownKeys = true }
private val PROJECT_GITHUB_JSON = Json { ignoreUnknownKeys = true; isLenient = true }
private val PROJECT_GITHUB_HTTP = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .build()
private val SEARCH_SKIPPED_DIR_NAMES = setOf(".git", ".gradle", "build", "node_modules", ".idea")
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

private data class ProjectSearchHitUi(val filePath: String, val relativePath: String, val lineNumber: Int? = null, val preview: String, val fileType: String)
private data class ProjectSearchResultUi(val query: String, val scope: ProjectSearchScope, val hits: List<ProjectSearchHitUi>, val totalCount: Int, val truncated: Boolean = false)
private data class ProjectFocusedPreviewRange(val startLine: Int, val endLine: Int, val excerpt: String)
private data class ProjectTextMatchUi(val lineNumber: Int, val column: Int, val startIndex: Int, val endIndex: Int)
private data class ProjectSelectedLineBlock(val startLine: Int, val endLine: Int, val text: String)
private data class ProjectReplaceAllResult(val content: String, val count: Int)
private data class ProjectEditorDiagnostic(val lineNumber: Int, val startIndex: Int, val endIndex: Int, val message: String)
private data class ProjectGitConflictBlock(
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
private data class ProjectGitHubRepoRef(
    val owner: String,
    val repo: String
)
private data class ProjectGitHubWorkflowUi(
    val id: Long,
    val name: String,
    val path: String,
    val state: String,
    val htmlUrl: String?
) {
    val canDispatch: Boolean get() = state.equals("active", ignoreCase = true)
    val stateLabel: String get() = if (state.isBlank()) "状态未知" else "状态 ${state.lowercase(Locale.getDefault())}"
}
private data class ProjectGitHubWorkflowRunUi(
    val id: Long,
    val name: String,
    val displayTitle: String,
    val headBranch: String,
    val status: String,
    val conclusion: String?,
    val event: String,
    val runNumber: Long,
    val updatedAt: String,
    val htmlUrl: String?
) {
    val statusLabel: String
        get() = buildProjectGitHubStatusLabel(status, conclusion)
}
private data class ProjectGitHubWorkflowStepUi(
    val number: Int,
    val name: String,
    val status: String,
    val conclusion: String?,
    val startedAt: String,
    val completedAt: String
) {
    val statusLabel: String
        get() = buildProjectGitHubStatusLabel(status, conclusion)
    val hasIssue: Boolean
        get() = isProjectGitHubIssueStatus(status, conclusion)
}
private data class ProjectGitHubWorkflowJobUi(
    val id: Long,
    val name: String,
    val status: String,
    val conclusion: String?,
    val startedAt: String,
    val completedAt: String,
    val steps: List<ProjectGitHubWorkflowStepUi>
) {
    val statusLabel: String
        get() = buildProjectGitHubStatusLabel(status, conclusion)
    val issueSummaries: List<String>
        get() = steps
            .filter { it.hasIssue }
            .map { step -> "• $name / ${step.name} · ${step.statusLabel}" }
}
private data class ProjectGitHubWorkflowRunDetailUi(
    val id: Long,
    val title: String,
    val workflowName: String,
    val headBranch: String,
    val status: String,
    val conclusion: String?,
    val event: String,
    val runNumber: Long,
    val createdAt: String,
    val updatedAt: String,
    val htmlUrl: String?,
    val jobs: List<ProjectGitHubWorkflowJobUi>
) {
    val statusLabel: String
        get() = buildProjectGitHubStatusLabel(status, conclusion)
    val issueSummaries: List<String>
        get() = jobs.flatMap { it.issueSummaries }.take(8)
}
private data class ProjectGitHubArtifactUi(
    val id: Long,
    val name: String,
    val sizeInBytes: Long,
    val archiveDownloadUrl: String,
    val expired: Boolean,
    val updatedAt: String
) {
    val sizeLabel: String get() = formatProjectByteSize(sizeInBytes)
}
private data class ProjectGitHubReleaseAssetUi(
    val id: Long,
    val name: String,
    val sizeInBytes: Long,
    val apiUrl: String,
    val browserDownloadUrl: String?,
    val updatedAt: String
) {
    val sizeLabel: String get() = formatProjectByteSize(sizeInBytes)
}
private data class ProjectGitHubReleaseUi(
    val id: Long,
    val tagName: String,
    val name: String,
    val body: String,
    val isDraft: Boolean,
    val isPrerelease: Boolean,
    val publishedAt: String,
    val htmlUrl: String?,
    val assets: List<ProjectGitHubReleaseAssetUi>
)
private data class ProjectGitHubActionsState(
    val repo: ProjectGitHubRepoRef?,
    val viewerLogin: String?,
    val viewerName: String?,
    val defaultBranch: String?,
    val repoHtmlUrl: String?,
    val workflows: List<ProjectGitHubWorkflowUi>,
    val recentRuns: List<ProjectGitHubWorkflowRunUi>,
    val releases: List<ProjectGitHubReleaseUi>,
    val errorMessage: String?
) {
    companion object {
        fun empty(repo: ProjectGitHubRepoRef? = null) = ProjectGitHubActionsState(
            repo = repo,
            viewerLogin = null,
            viewerName = null,
            defaultBranch = null,
            repoHtmlUrl = null,
            workflows = emptyList(),
            recentRuns = emptyList(),
            releases = emptyList(),
            errorMessage = null
        )
    }
}
private data class ProjectGitHubArtifactDialogUi(
    val runTitle: String,
    val artifacts: List<ProjectGitHubArtifactUi>,
    val runId: Long,
    val runHtmlUrl: String?
)
private data class ProjectGitHubReleaseAssetDialogUi(
    val releaseTitle: String,
    val assets: List<ProjectGitHubReleaseAssetUi>,
    val releaseHtmlUrl: String?
)
private data class ProjectDownloadEnqueueResult(
    val downloadId: Long,
    val fileName: String
)
private data class ProjectGitHubDownloadRecordUi(
    val id: String,
    val typeLabel: String,
    val title: String,
    val fileName: String,
    val createdAtMillis: Long,
    val downloadId: Long,
    val sourceUrl: String?
) {
    val createdAtLabel: String
        get() = formatProjectDateTime(createdAtMillis)
}
private data class ProjectGitHubHttpResult(
    val success: Boolean,
    val code: Int,
    val body: String,
    val error: String?
)
private data class ProjectGitHubCommandResult(
    val success: Boolean,
    val message: String,
    val error: String? = null
)
private data class ProjectGitHubCreateRepoResult(
    val success: Boolean,
    val repo: ProjectGitHubRepoRef?,
    val cloneUrl: String?,
    val htmlUrl: String?,
    val error: String?
)
private data class ProjectGitHubArtifactLoadResult(
    val success: Boolean,
    val artifacts: List<ProjectGitHubArtifactUi>,
    val error: String?
)
private data class ProjectGitHubWorkflowRunDetailLoadResult(
    val success: Boolean,
    val detail: ProjectGitHubWorkflowRunDetailUi?,
    val error: String?
)
private data class ProjectCppClassLikeBlock(val kind: String, val startLine: Int, val expectedBraceDepth: Int)
private data class ProjectFoldRegion(val startLine: Int, val endLine: Int, val headerLine: String)
private data class ProjectOutlineEntry(val startLine: Int, val lineNumber: Int, val depth: Int, val label: String, val kind: String)
private data class ProjectLanguageOption(val label: String, val value: String?)
private enum class ProjectSearchScope { ALL, FILE_NAME, TEXT }
private enum class ProjectLineAction(val label: String) {
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

private fun buildProjectGitHubStatusLabel(status: String, conclusion: String?): String = buildString {
    append(status.ifBlank { "未知状态" })
    conclusion?.takeIf { it.isNotBlank() }?.let {
        append(" / ")
        append(it)
    }
}

private fun isProjectGitHubIssueStatus(status: String, conclusion: String?): Boolean {
    if (!status.equals("completed", ignoreCase = true)) return false
    return when (conclusion?.trim()?.lowercase(Locale.getDefault())) {
        null, "", "success", "skipped", "neutral" -> false
        else -> true
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
