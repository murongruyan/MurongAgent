@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.murong.agent.ui.chat

import android.content.ClipData
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.murong.agent.core.mcp.McpServerConfig
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.murong.agent.core.loop.ChatMessageUi
import com.murong.agent.core.loop.SubagentTimelineEntryUi
import com.murong.agent.core.loop.AutoRouteAction
import com.murong.agent.core.loop.AutoRouteDecisionUi
import com.murong.agent.core.loop.AskAnswerUi
import com.murong.agent.core.loop.ArchivedMemoryCandidateMutationResult
import com.murong.agent.core.loop.ArchivedMemoryCandidateScope
import com.murong.agent.core.loop.ConversationCheckpointScope
import com.murong.agent.core.loop.ConversationCheckpointUi
import com.murong.agent.core.loop.FileChangeRecordUi
import com.murong.agent.core.loop.FileMentionUi
import com.murong.agent.core.loop.FinalReadinessReceipt
import com.murong.agent.core.loop.ContextCompressionUi
import com.murong.agent.core.loop.ContextCompressionPreviewUi
import com.murong.agent.core.loop.MessageImageAttachmentUi
import com.murong.agent.core.loop.PendingImageAttachmentUi
import com.murong.agent.core.loop.ProjectKnowledgeSnapshotUi
import com.murong.agent.core.loop.BackgroundActivityFocusKind
import com.murong.agent.core.loop.RuntimeStatusKind
import com.murong.agent.core.loop.RuntimeApprovalPostureDisplayMode
import com.murong.agent.core.loop.SessionState
import com.murong.agent.core.loop.SessionSummary
import com.murong.agent.core.loop.SubagentBatchUi
import com.murong.agent.core.loop.SubagentRunUi
import com.murong.agent.core.loop.UsageSummarySnapshot
import com.murong.agent.core.loop.formatCurrencyAmount
import com.murong.agent.core.loop.estimateContextCompressionPreview
import com.murong.agent.core.loop.MIN_MESSAGES_FOR_COMPRESSION
import com.murong.agent.core.loop.MIN_MESSAGES_TO_COMPRESS
import com.murong.agent.core.loop.RECENT_MESSAGES_TO_KEEP
import com.murong.agent.core.loop.WEB_FETCH_RESULT_PREFIX
import com.murong.agent.core.config.GlobalSkill
import com.murong.agent.core.config.ProviderConfig
import com.murong.agent.core.config.ProjectToolPreferences
import com.murong.agent.core.config.ToolApprovalMode
import com.murong.agent.core.config.WorkflowExecutionMode
import com.murong.agent.core.config.approvalModeDescription
import com.murong.agent.core.config.approvalModeLabel
import com.murong.agent.core.provider.ProviderRegistry
import com.murong.agent.ui.MurongDialog
import com.murong.agent.ui.MurongGlassSurface
import com.murong.agent.ui.MurongInteractionPerformanceHint
import com.murong.agent.ui.MurongOutlinedActionButton
import com.murong.agent.ui.MurongPopupSurface
import com.murong.agent.ui.MurongReadOnlyCodeBlock
import com.murong.agent.ui.tools.formatCheckpointRollbackActionLabel
import com.murong.agent.ui.tools.formatCheckpointRollbackImpactCopy
import com.murong.agent.ui.MurongTagButton
import com.murong.agent.ui.MarkdownText
import com.murong.agent.ui.ProjectKnowledgeOutlineUi
import com.murong.agent.ui.ApprovalRuntimePosturePresentation
import com.murong.agent.ui.buildApprovalModeFollowGlobalOptionPresentation
import com.murong.agent.ui.buildApprovalModeOptionPresentations
import com.murong.agent.ui.buildProjectKnowledgeOutlines
import com.murong.agent.ui.rememberMurongAccentColor
import com.murong.agent.ui.rememberMurongBottomBarScrollPadding
import com.murong.agent.ui.rememberMurongChromeColor
import com.murong.agent.ui.rememberMurongMutedTextColor
import com.murong.agent.ui.rememberMurongSurfaceColor
import com.murong.agent.ui.toSessionReadinessPresentation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.max

@Composable
internal fun ChatScreen(
    state: SessionState,
    isScreenActive: Boolean = true,
    chatRuntimeHostPresentation: ChatRuntimeHostPresentation,
    onPendingWorkflowPlanInteractionStateChange: (WorkflowPlanInteractionState) -> Unit = {},
    onPendingClarificationInteractionStateChange: (ClarificationInteractionState) -> Unit = {},
    onPendingAskInteractionStateChange: (AskPromptInteractionState) -> Unit = {},
    bottomReservedPadding: Dp = 0.dp,
    multimodalEnabled: Boolean = true,
    executionProfileConfig: ProviderConfig = ProviderConfig(),
    globalApprovalMode: ToolApprovalMode = executionProfileConfig.approvalMode,
    projectKnowledgePaths: List<String> = emptyList(),
    onSend: (String, List<FileMentionUi>, List<PendingImageAttachmentUi>, List<GlobalSkill>) -> Unit,
    onSetSessionGoal: (String) -> Unit = {},
    onClearSessionGoal: () -> Unit = {},
    onStopSending: () -> Unit = {},
    onClear: () -> Unit,
    onNewSession: () -> Unit = {},
    title: String = "新对话",
    hasApiKey: Boolean = false,
    workflowExecutionMode: WorkflowExecutionMode = WorkflowExecutionMode.SINGLE_PASS,
    autoRouteBeforeExecution: Boolean = true,
    projectToolPreferences: ProjectToolPreferences? = null,
    onNavigateToSettings: () -> Unit = {},
    onUpdateProjectToolPreferences: (ProjectToolPreferences?) -> Unit = {},
    onEditMessage: (Long) -> Boolean = { false },
    onCompressContext: suspend () -> Result<ContextCompressionUi> = {
        Result.failure(IllegalStateException("未配置上下文压缩动作。"))
    },
    onRollbackCheckpoint: (String, ConversationCheckpointScope) -> Unit = { _, _ -> },
    onGeneratePlan: (String, List<FileMentionUi>) -> Unit = { _, _ -> },
    onExecutePlan: () -> Unit = {},
    onDismissPlan: () -> Unit = {},
    onSubmitClarificationAnswer: (String) -> Unit = {},
    onDismissClarification: () -> Unit = {},
    onSubmitAskAnswers: (List<AskAnswerUi>) -> Unit = {},
    onDismissAsk: () -> Unit = {},
    onAcceptArchivedMemoryCandidate: suspend (String, ArchivedMemoryCandidateScope) -> ArchivedMemoryCandidateMutationResult =
        { _, _ -> ArchivedMemoryCandidateMutationResult(success = false, message = "未配置归档候选接受动作。") },
    onDismissArchivedMemoryCandidate: suspend (String) -> ArchivedMemoryCandidateMutationResult =
        { ArchivedMemoryCandidateMutationResult(success = false, message = "未配置归档候选关闭动作。") },
    onConsumeArchivedMemoryCandidate: suspend (String) -> ArchivedMemoryCandidateMutationResult =
        { ArchivedMemoryCandidateMutationResult(success = false, message = "未配置归档候选处理动作。") },
    onScreenAttached: () -> Unit = {},
    onScreenActiveStateChanged: (Boolean) -> Unit = {},
    onSearchFiles: suspend (String) -> List<FileMentionUi> = { emptyList() },
    onRetrySubagent: (String) -> Unit = {},
    onCancelSubagent: (String) -> Unit = {}
) {
    var inputText by remember { mutableStateOf("") }
    val inputHistory = remember(state.sessionId) { mutableStateListOf<String>() }
    var inputHistoryIndex by remember(state.sessionId) { mutableIntStateOf(-1) }
    var inputDraftBeforeHistory by remember(state.sessionId) { mutableStateOf("") }
    var editingMessageId by remember { mutableStateOf<Long?>(null) }
    var messageActionTarget by remember { mutableStateOf<ChatMessageUi?>(null) }
    var selectedSubagentRun by remember { mutableStateOf<SubagentRunUi?>(null) }
    var selectedSubagentBatch by remember { mutableStateOf<SubagentBatchUi?>(null) }
    var showSubagentHistory by remember(state.sessionId) { mutableStateOf(false) }
    var showCompressionHistory by remember(state.sessionId) { mutableStateOf(false) }
    var showFileChangeHistory by remember(state.sessionId) { mutableStateOf(false) }
    var showRecentHistorySurface by remember(state.sessionId) { mutableStateOf(false) }
    var showArchivedMemorySurface by remember(state.sessionId) { mutableStateOf(false) }
    var selectedCheckpoint by remember(state.sessionId) { mutableStateOf<ConversationCheckpointUi?>(null) }
    var selectedRecoveryId by remember(state.sessionId) { mutableStateOf<String?>(null) }
    var selectedFileChange by remember(state.sessionId) { mutableStateOf<FileChangeRecordUi?>(null) }
    val selectedMentions = remember(state.sessionId) { mutableStateListOf<FileMentionUi>() }
    val selectedImages = remember(state.sessionId) { mutableStateListOf<PendingImageAttachmentUi>() }
    val selectedSkills = remember(state.sessionId) { mutableStateListOf<GlobalSkill>() }
    val recentMentions = remember(state.sessionId) { mutableStateListOf<FileMentionUi>() }
    var showMentionPicker by remember(state.sessionId) { mutableStateOf(false) }
    var mentionQuery by remember(state.sessionId) { mutableStateOf("") }
    var previewImages by remember(state.sessionId) { mutableStateOf<List<ImagePreviewItemUi>>(emptyList()) }
    var previewImageIndex by remember(state.sessionId) { mutableIntStateOf(0) }
    var inputHasFocus by remember(state.sessionId) { mutableStateOf(false) }
    var planModeEnabled by remember(state.sessionId) { mutableStateOf(false) }
    var goalModeEnabled by remember(state.sessionId) { mutableStateOf(false) }
    var showSubagentHint by remember(state.sessionId) { mutableStateOf(true) }
    var showWorkflowPreferenceHint by remember(state.sessionId) { mutableStateOf(true) }
    var showCompressionHint by remember(state.sessionId) { mutableStateOf(true) }
    var showCurrentWorkspaceHint by remember(state.sessionId) { mutableStateOf(true) }
    var showFileChangeHint by remember(state.sessionId) { mutableStateOf(true) }
    var showRecentHistoryHint by remember(state.sessionId) { mutableStateOf(true) }
    var showArchivedMemoryHint by remember(state.sessionId) { mutableStateOf(true) }
    var showApprovalPostureHint by remember(state.sessionId) { mutableStateOf(true) }
    var showApprovalModeDialog by remember(state.sessionId) { mutableStateOf(false) }
    var showSkillPicker by remember(state.sessionId) { mutableStateOf(false) }
    val approvalModeOverride = projectToolPreferences?.approvalMode
    val pendingPromptHostPresentation = remember(chatRuntimeHostPresentation) {
        chatRuntimeHostPresentation.pendingPromptHostPresentation
    }
    val recentHistorySurfacePresentation = remember(chatRuntimeHostPresentation) {
        chatRuntimeHostPresentation.recentHistorySurfacePresentation
    }
    val archivedMemorySurfacePresentation = remember(chatRuntimeHostPresentation) {
        chatRuntimeHostPresentation.archivedMemorySurfacePresentation
    }
    val topStatusStripPresentation = remember(chatRuntimeHostPresentation) {
        chatRuntimeHostPresentation.topStatusStrip
    }
    var lastAutoScrolledSessionId by remember { mutableStateOf<String?>(null) }
    var shouldAutoFollowMessages by remember(state.sessionId) { mutableStateOf(true) }
    val projectKnowledgeMentions = remember(state.projectPath, projectKnowledgePaths) {
        buildProjectKnowledgeMentions(state.projectPath, projectKnowledgePaths)
    }
    val projectKnowledgeOutlines = remember(state.projectPath, projectKnowledgePaths) {
        buildProjectKnowledgeOutlines(state.projectPath, projectKnowledgePaths)
    }
    val projectKnowledgeOutlineMap = remember(projectKnowledgeOutlines) {
        projectKnowledgeOutlines.associateBy { it.path }
    }
    val checkpointActivityHintPresentation = remember(
        state.checkpoints,
        state.fileChanges,
        state.recentRecoveryRecords
    ) {
        buildChatCheckpointActivityHintPresentation(
            checkpoints = state.checkpoints,
            fileChanges = state.fileChanges,
            recentRecoveryRecords = state.recentRecoveryRecords
        )
    }
    val checkpointHistoryPresentation = remember(
        state.checkpoints,
        state.fileChanges,
        state.recentRecoveryRecords
    ) {
        buildChatCheckpointHistoryPresentation(
            checkpoints = state.checkpoints,
            fileChanges = state.fileChanges,
            recentRecoveryRecords = state.recentRecoveryRecords
        )
    }
    val projectKnowledgeSnapshotNamesByPath = remember(state.projectKnowledgeSnapshots) {
        buildProjectKnowledgeSnapshotNamesByPath(state.projectKnowledgeSnapshots)
    }
    val projectKnowledgePathSet = remember(projectKnowledgeMentions) {
        projectKnowledgeMentions.map { it.path }.toSet()
    }
    val availableSkills = remember(executionProfileConfig.globalSkills, state.projectSkills) {
        (executionProfileConfig.globalSkills + state.projectSkills)
            .filter { it.enabled && (it.title.isNotBlank() || it.content.isNotBlank()) }
            .distinctBy { listOf(it.title.trim(), it.description.trim(), it.content.trim(), it.runAs.name).joinToString("|") }
    }
    val messages = state.messages
    val subagentRunsById = remember(state.subagentRuns) {
        state.subagentRuns.associateBy { it.runId }
    }
    val subagentBatchesById = remember(state.subagentBatches) {
        state.subagentBatches.associateBy { it.batchId }
    }
    val latestMessageId = remember(messages) {
        messages.lastOrNull()?.id
    }
    val latestMessageImageCount = remember(messages) {
        messages.lastOrNull()?.imageAttachments?.size ?: 0
    }
    val activeInlineMentionQuery = remember(inputText, showMentionPicker) {
        if (showMentionPicker) null else extractActiveMentionQuery(inputText)
    }
    val inlineMentionResults by produceState(
        initialValue = emptyList(),
        key1 = activeInlineMentionQuery,
        key2 = state.sessionId,
        key3 = projectKnowledgeMentions
    ) {
        value = when {
            activeInlineMentionQuery == null -> emptyList()
            activeInlineMentionQuery.isBlank() -> emptyList()
            else -> mergeProjectKnowledgeMentions(
                projectKnowledgeMentions = projectKnowledgeMentions,
                searchResults = onSearchFiles(activeInlineMentionQuery),
                query = activeInlineMentionQuery
            )
        }
    }
    val recentImageAttachments = remember(
        state.sessionId,
        messages.size,
        latestMessageId,
        latestMessageImageCount
    ) {
        messages
            .asReversed()
            .asSequence()
            .flatMap { it.imageAttachments.asSequence() }
            .filter { it.localCachePath.isNotBlank() }
            .distinctBy { it.localCachePath }
            .take(8)
            .toList()
    }
    val listState = rememberSaveable(state.sessionId, saver = LazyListState.Saver) {
        LazyListState()
    }
    val bottomBarScrollPadding = rememberMurongBottomBarScrollPadding()
    val messageListBottomPadding = remember(bottomReservedPadding, bottomBarScrollPadding) {
        (bottomReservedPadding + 16.dp).coerceAtLeast(bottomBarScrollPadding + 12.dp)
    }
    MurongInteractionPerformanceHint(
        active = isScreenActive && listState.isScrollInProgress
    )
    val currentProjectLabel = state.projectPath?.takeIf { it.isNotBlank() }
        ?: state.remoteTaskRepositoryLabel?.takeIf { it.isNotBlank() }
    val currentWorkspaceTitle = if (state.projectPath.isNullOrBlank()) "当前远端任务仓库" else "当前项目"
    val currentWorkspaceHelperText = when {
        state.projectPath.isNullOrBlank() && state.remoteTaskRepositoryEditable ->
            "已设为任务仓库，可在项目页直接搜索和编辑。让模型处理远端仓库时会优先参考 GitHub/MCP 远端能力。"
        state.projectPath.isNullOrBlank() ->
            "当前没有本地项目路径，模型处理这个仓库时会优先走 GitHub/MCP 远端能力。"
        projectKnowledgeMentions.isNotEmpty() ->
            "已接入知识文件 ${projectKnowledgeMentions.size} 个，可在 @文件 中直接选择。"
        else -> null
    }
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val canSubmitDraft = remember(
        planModeEnabled,
        goalModeEnabled,
        inputText,
        selectedImages.size,
        multimodalEnabled
    ) {
        if (planModeEnabled || goalModeEnabled) {
            inputText.isNotBlank() && selectedImages.isEmpty()
        } else {
            inputText.isNotBlank() || (multimodalEnabled && selectedImages.isNotEmpty())
        }
    }
    val compressionSuggestion = remember(
        state.messages.size,
        state.usageSummary.totalTokens,
        state.compressionSnapshot?.id,
        state.compressionSnapshot?.active
    ) {
        buildCompressionSuggestion(state)
    }
    val executionProfileHint = remember(executionProfileConfig) {
        buildWorkflowExecutionProfileHint(executionProfileConfig)
    }
    var dismissedCompressionSuggestionAtCount by remember(state.sessionId) {
        mutableStateOf<Int?>(null)
    }
    var compressionActionInProgress by remember(state.sessionId) { mutableStateOf(false) }
    var compressionActionError by remember(state.sessionId) { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        uris.forEach { uri ->
            val attachment = PendingImageAttachmentUi(
                uri = uri.toString(),
                fileName = resolveAttachmentDisplayName(context, uri).orEmpty(),
                mimeType = context.contentResolver.getType(uri)
            )
            if (selectedImages.none { it.uri == attachment.uri }) {
                selectedImages.add(attachment)
            }
        }
    }
    val cameraPreviewLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        val attachment = bitmap?.let { saveCapturedBitmapAsAttachment(context, it) }
        if (attachment != null && selectedImages.none { it.uri == attachment.uri }) {
            selectedImages.add(attachment)
        }
    }
    fun dismissTransientOverlays() {
        messageActionTarget = null
        selectedSubagentRun = null
        selectedSubagentBatch = null
        showSubagentHistory = false
        showSubagentHint = false
        showCompressionHistory = false
        showFileChangeHistory = false
        showRecentHistorySurface = false
        showArchivedMemorySurface = false
        selectedCheckpoint = null
        selectedRecoveryId = null
        selectedFileChange = null
        previewImages = emptyList()
        previewImageIndex = 0
        showMentionPicker = false
    }

    val itemCount = messages.size
    val lastMessage = messages.lastOrNull()
    val lastMessageContentLength = lastMessage?.content?.length ?: 0
    val lastMessageReasoningLength = lastMessage?.reasoning?.length ?: 0
    val lastMessageStreaming = lastMessage?.isStreaming == true
    suspend fun scrollMessagesToBottom() {
        if (itemCount > 0) {
            listState.scrollToItem(itemCount - 1)
        }
    }

    LaunchedEffect(listState, state.sessionId) {
        snapshotFlow { isMessageListNearBottom(listState) }
            .collect { nearBottom ->
                shouldAutoFollowMessages = nearBottom
            }
    }

    // 只在切会话或本来就接近底部时自动跟随，避免手动上滑时被强行拉回。
    LaunchedEffect(
        state.sessionId,
        itemCount,
        lastMessage?.id,
        lastMessageContentLength,
        lastMessageReasoningLength,
        lastMessageStreaming,
        state.isProcessing
    ) {
        if (!isScreenActive) return@LaunchedEffect
        val sessionChanged = lastAutoScrolledSessionId != state.sessionId
        if (sessionChanged || shouldAutoFollowMessages) {
            scrollMessagesToBottom()
        }
        lastAutoScrolledSessionId = state.sessionId
    }

    LaunchedEffect(state.sessionId, workflowExecutionMode, autoRouteBeforeExecution) {
        showWorkflowPreferenceHint = true
        delay(2200)
        showWorkflowPreferenceHint = false
    }

    LaunchedEffect(
        state.sessionId,
        state.subagentRuns.size,
        state.subagentRuns.maxOfOrNull { it.startedAt },
        state.subagentRuns.maxOfOrNull { it.finishedAt ?: 0L }
    ) {
        if (state.subagentRuns.isEmpty()) return@LaunchedEffect
        showSubagentHint = true
        delay(2600)
        showSubagentHint = false
    }

    LaunchedEffect(
        state.sessionId,
        state.compressionSnapshot?.id,
        state.compressionSnapshot?.active,
        compressionSuggestion?.reason
    ) {
        if (state.compressionSnapshot != null) {
            compressionActionInProgress = false
            compressionActionError = null
        }
        if (state.compressionSnapshot == null && compressionSuggestion == null) return@LaunchedEffect
        showCompressionHint = true
        delay(2600)
        showCompressionHint = false
    }

    LaunchedEffect(
        state.sessionId,
        currentProjectLabel,
        currentWorkspaceTitle,
        currentWorkspaceHelperText
    ) {
        if (currentProjectLabel == null) return@LaunchedEffect
        showCurrentWorkspaceHint = true
        delay(2600)
        showCurrentWorkspaceHint = false
    }

    LaunchedEffect(
        state.sessionId,
        state.fileChanges.size,
        state.checkpoints.size,
        state.checkpoints.firstOrNull()?.createdAt,
        state.recentRecoveryRecords.size,
        state.recentRecoveryRecords.firstOrNull()?.timestamp
    ) {
        if (checkpointActivityHintPresentation == null) return@LaunchedEffect
        showFileChangeHint = true
        delay(2600)
        showFileChangeHint = false
    }

    LaunchedEffect(
        state.sessionId,
        topStatusStripPresentation.compact,
        chatRuntimeHostPresentation.approvalRuntimePosturePresentation.shortcutLabel,
        chatRuntimeHostPresentation.approvalRuntimePosturePresentation.message,
        chatRuntimeHostPresentation.approvalRuntimePosturePresentation.secondary.pendingSummary?.text
    ) {
        if (!topStatusStripPresentation.compact) return@LaunchedEffect
        showApprovalPostureHint = true
        delay(2600)
        showApprovalPostureHint = false
    }

    LaunchedEffect(isScreenActive, state.sessionId) {
        if (!isScreenActive) {
            dismissTransientOverlays()
        }
        onScreenActiveStateChanged(isScreenActive)
    }

    DisposableEffect(state.sessionId) {
        onScreenAttached()
        onDispose { }
    }

    LaunchedEffect(state.sessionId, state.projectPath, projectKnowledgeMentions) {
        if (projectKnowledgeMentions.isNotEmpty()) {
            val merged = (projectKnowledgeMentions + recentMentions.toList())
                .distinctBy { it.path }
                .take(8)
            recentMentions.clear()
            recentMentions.addAll(merged)
        }
    }

    val handleMentionSelect: (FileMentionUi) -> Unit = { mention ->
        if (selectedMentions.none { it.path == mention.path }) {
            selectedMentions.add(mention)
        }
        recentMentions.removeAll { it.path == mention.path }
        recentMentions.add(0, mention)
        while (recentMentions.size > 6) {
            recentMentions.removeLast()
        }
        inputText = replaceTrailingMentionQuery(inputText, mention.displayPath)
        mentionQuery = ""
        showMentionPicker = false
    }

    fun launchCompressionAction() {
        if (compressionActionInProgress) return
        compressionActionError = null
        compressionActionInProgress = true
        showCompressionHint = true
        coroutineScope.launch {
            val result = onCompressContext()
            compressionActionInProgress = false
            compressionActionError = result.exceptionOrNull()?.message ?: "上下文压缩失败"
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (!topStatusStripPresentation.compact) {
                WorkflowStatusStrip(
                    title = topStatusStripPresentation.title,
                    message = topStatusStripPresentation.message,
                    badge = topStatusStripPresentation.badge
                )
            }
            val supplementalRuntimeStatuses = chatRuntimeHostPresentation.supplementalRuntimeStatuses
            if (supplementalRuntimeStatuses.isEmpty() && workflowExecutionMode == WorkflowExecutionMode.SINGLE_PASS) {
                if (autoRouteBeforeExecution) {
                    state.lastAutoRouteDecision?.let { decision ->
                        AutoRouteDecisionStatusBar(decision = decision)
                    }
                }
            }
            supplementalRuntimeStatuses.forEach { presentation ->
                WorkflowStatusStrip(
                    title = presentation.title,
                    message = presentation.message,
                    badge = presentation.badge
                )
            }
            state.lastWorkflowFallback?.let { fallback ->
                WorkflowFallbackStatusBar(message = fallback.message)
            }
            pendingPromptHostPresentation.workflowPlanHostSurface
                .takeIf { it.kind == WorkflowPlanHostSurfaceKind.CHAT_INLINE }
                ?.workflowPlanPresentation
                ?.let { presentation ->
                WorkflowPlanPromptCard(
                    presentation = presentation,
                    interactionState = pendingPromptHostPresentation.workflowPlanHostSurface.interactionState,
                    onInteractionStateChange = onPendingWorkflowPlanInteractionStateChange,
                    isProcessing = state.isProcessing,
                    onExecute = onExecutePlan,
                    onDismiss = onDismissPlan
                )
            }
            pendingPromptHostPresentation.clarificationHostSurface
                .takeIf { it.kind == ClarificationHostSurfaceKind.CHAT_INLINE }
                ?.clarificationPresentation
                ?.let { presentation ->
                ClarificationPromptCard(
                    presentation = presentation,
                    interactionState = pendingPromptHostPresentation.clarificationHostSurface.interactionState,
                    onInteractionStateChange = onPendingClarificationInteractionStateChange,
                    isProcessing = state.isProcessing,
                    onSubmit = onSubmitClarificationAnswer,
                    onDismiss = onDismissClarification
                )
            }
            pendingPromptHostPresentation.askHostSurface
                .takeIf { it.kind == AskHostSurfaceKind.CHAT_INLINE }
                ?.askPresentation
                ?.let { presentation ->
                AskUserPromptCard(
                    presentation = presentation,
                    interactionState = pendingPromptHostPresentation.askHostSurface.interactionState,
                    onInteractionStateChange = onPendingAskInteractionStateChange,
                    onSubmit = onSubmitAskAnswers,
                    onDismiss = onDismissAsk
                )
            }
            state.sessionGoal?.takeIf { it.isNotBlank() }?.let { sessionGoal ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AssistChip(
                        onClick = {
                            inputText = sessionGoal
                            goalModeEnabled = true
                            inputHistoryIndex = -1
                            inputDraftBeforeHistory = sessionGoal
                            selectedImages.clear()
                            keyboardController?.show()
                        },
                        label = {
                            Text("目标: ${sessionGoal.take(40)}${if (sessionGoal.length > 40) "..." else ""}")
                        }
                    )
                    MurongTagButton(
                        text = "清除目标",
                        onClick = onClearSessionGoal
                    )
                }
            }
            // 消息列表
            if (messages.isEmpty() && !state.isProcessing) {
                WelcomeView(
                    hasApiKey = hasApiKey,
                    onNavigateToSettings = onNavigateToSettings,
                    compactForInput = inputHasFocus,
                    onSizeChanged = {},
                    modifier = Modifier.weight(1f)
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    state = listState,
                    contentPadding = PaddingValues(top = 6.dp, bottom = messageListBottomPadding),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    itemsIndexed(
                        items = messages,
                        key = { _, it -> it.id },
                        contentType = { _, it -> it.role }
                    ) { index, msg ->
                        val subagentRun = msg.subagentRunId?.let(subagentRunsById::get)
                        val subagentBatch = msg.subagentBatchId?.let(subagentBatchesById::get)
                        val previousMessage = messages.getOrNull(index - 1)
                        val nextMessage = messages.getOrNull(index + 1)
                        MessageBubble(
                            msg = msg,
                            isScreenActive = isScreenActive,
                            previousMessage = previousMessage,
                            nextMessage = nextMessage,
                            subagentRun = subagentRun,
                            subagentBatch = subagentBatch,
                            onLongPress = { messageActionTarget = msg },
                            onApplyPrompt = { prompt ->
                                inputText = listOf(inputText, prompt)
                                    .filter { it.isNotBlank() }
                                    .joinToString("\n")
                            },
                            onOpenImagePreview = { items, index ->
                                previewImages = items
                                previewImageIndex = index
                            },
                            onClick = {
                                if (subagentRun != null) {
                                    selectedSubagentRun = subagentRun
                                } else if (subagentBatch != null) {
                                    selectedSubagentBatch = subagentBatch
                                }
                            }
                        )
                    }

                    if (state.isProcessing && !hasStreamingMessage(messages)) {
                        item {
                            LoadingIndicator()
                        }
                    }
                }
            }
            // 错误提示
            val err = state.error
            if (err != null) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f),
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = err,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        fontSize = 13.sp
                    )
                }
            }

            editingMessageId?.let {
                EditHintBar(
                    onCancel = {
                        editingMessageId = null
                        inputText = ""
                    }
                )
            }
            if (selectedMentions.isNotEmpty()) {
                MentionedFilesBar(
                    mentions = selectedMentions,
                    knowledgePaths = projectKnowledgePathSet,
                    snapshotNamesByPath = projectKnowledgeSnapshotNamesByPath,
                    onRemove = { mention -> selectedMentions.removeAll { it.path == mention.path } }
                )
            }
            if (selectedSkills.isNotEmpty()) {
                SelectedSkillsBar(
                    skills = selectedSkills.toList(),
                    onRemove = { skill ->
                        selectedSkills.removeAll {
                            it.title == skill.title && it.content == skill.content
                        }
                    }
                )
            }
            if (selectedImages.isNotEmpty()) {
                PendingImageAttachmentsBar(
                    attachments = selectedImages.toList(),
                    onOpenPreview = { attachments, index ->
                        previewImages = attachments.map { attachment ->
                            ImagePreviewItemUi(
                                title = attachment.fileName.ifBlank { "未命名图片" },
                                uri = attachment.uri,
                                mimeType = attachment.mimeType
                            )
                        }
                        previewImageIndex = index
                    },
                    onRemove = { attachment ->
                        selectedImages.removeAll { it.uri == attachment.uri }
                    }
                )
            }
            if (recentImageAttachments.isNotEmpty()) {
                RecentImageAttachmentsBar(
                    attachments = recentImageAttachments,
                    selectedUris = selectedImages.map { it.uri }.toSet(),
                    onReuse = { attachment ->
                        val pending = PendingImageAttachmentUi(
                            uri = Uri.fromFile(File(attachment.localCachePath)).toString(),
                            fileName = attachment.fileName,
                            mimeType = attachment.mimeType
                        )
                        if (selectedImages.none { it.uri == pending.uri }) {
                            selectedImages.add(pending)
                        }
                    },
                    onOpenPreview = { attachments, index ->
                        previewImages = attachments.map { attachment ->
                            ImagePreviewItemUi(
                                title = attachment.fileName.ifBlank { "最近图片" },
                                localCachePath = attachment.localCachePath,
                                mimeType = attachment.mimeType
                            )
                        }
                        previewImageIndex = index
                    }
                )
            }
            activeInlineMentionQuery?.let { query ->
                MentionInputHintBar(
                    query = query,
                    matchCount = inlineMentionResults.size,
                    onOpenPicker = {
                        mentionQuery = query
                        showMentionPicker = true
                    }
                )
                InlineMentionSuggestionsCard(
                    query = query,
                    recentMentions = recentMentions.toList(),
                    results = inlineMentionResults,
                    knowledgePaths = projectKnowledgePathSet,
                    knowledgeOutlines = projectKnowledgeOutlineMap,
                    snapshotNamesByPath = projectKnowledgeSnapshotNamesByPath,
                    onSelect = { mention -> handleMentionSelect(mention) }
                )
            }

            // 输入栏
            InputBar(
                text = inputText,
                planModeEnabled = planModeEnabled,
                goalModeEnabled = goalModeEnabled,
                hasSessionGoal = !state.sessionGoal.isNullOrBlank(),
                onTextChange = { updatedText ->
                    if (inputHistoryIndex >= 0) {
                        val recalledText = inputHistory.getOrNull(inputHistoryIndex)
                        if (updatedText != recalledText) {
                            inputHistoryIndex = -1
                            inputDraftBeforeHistory = updatedText
                        }
                    } else {
                        inputDraftBeforeHistory = updatedText
                    }
                    inputText = updatedText
                    val activeMentionQuery = extractActiveMentionQuery(updatedText)
                    if (activeMentionQuery != null && !state.isProcessing) {
                        mentionQuery = activeMentionQuery
                    } else {
                        mentionQuery = ""
                    }
                    if (showMentionPicker && updatedText.isBlank()) {
                        showMentionPicker = false
                    }
                },
                onInputFocusChanged = { focused ->
                    inputHasFocus = focused
                },
                currentApprovalModeLabel = chatRuntimeHostPresentation.currentApprovalModeLabel,
                onPlanModeChange = { enabled ->
                    planModeEnabled = enabled
                },
                onGoalModeChange = { enabled ->
                    goalModeEnabled = enabled
                },
                onOpenApprovalMode = {
                    showApprovalModeDialog = true
                },
                onSend = {
                    if (canSubmitDraft && !state.isProcessing) {
                        keyboardController?.hide()
                        focusManager.clearFocus(force = true)
                        inputHasFocus = false
                        val sentText = inputText.trim()
                        if (sentText.isNotBlank()) {
                            inputHistory.removeAll { it == sentText }
                            inputHistory.add(sentText)
                            while (inputHistory.size > 50) {
                                inputHistory.removeAt(0)
                            }
                        }
                        when {
                            planModeEnabled -> {
                                if (goalModeEnabled && sentText.isNotBlank()) {
                                    onSetSessionGoal(sentText)
                                }
                                onGeneratePlan(
                                    if (goalModeEnabled) buildGoalPlanModeInput(sentText) else sentText,
                                    selectedMentions.toList()
                                )
                            }
                            goalModeEnabled -> {
                                onSetSessionGoal(sentText)
                                onSend(
                                    buildGoalModeMessage(sentText),
                                    selectedMentions.toList(),
                                    emptyList(),
                                    selectedSkills.toList()
                                )
                            }
                            else -> onSend(
                                sentText,
                                selectedMentions.toList(),
                                selectedImages.toList(),
                                selectedSkills.toList()
                            )
                        }
                        if (goalModeEnabled) {
                            goalModeEnabled = false
                        }
                        inputText = ""
                        inputHistoryIndex = -1
                        inputDraftBeforeHistory = ""
                        editingMessageId = null
                        selectedMentions.clear()
                        selectedImages.clear()
                        selectedSkills.clear()
                    }
                },
                onPreviousInput = {
                    if (inputHistory.isEmpty()) return@InputBar
                    if (inputHistoryIndex == -1) {
                        inputDraftBeforeHistory = inputText
                        inputHistoryIndex = inputHistory.lastIndex
                    } else if (inputHistoryIndex > 0) {
                        inputHistoryIndex -= 1
                    }
                    inputText = inputHistory[inputHistoryIndex]
                },
                onNextInput = {
                    if (inputHistoryIndex == -1) return@InputBar
                    if (inputHistoryIndex < inputHistory.lastIndex) {
                        inputHistoryIndex += 1
                        inputText = inputHistory[inputHistoryIndex]
                    } else {
                        inputHistoryIndex = -1
                        inputText = inputDraftBeforeHistory
                    }
                },
                onCaptureImage = {
                    if (multimodalEnabled && !state.isProcessing) {
                        cameraPreviewLauncher.launch(null)
                    }
                },
                onPickImages = {
                    if (multimodalEnabled && !state.isProcessing) {
                        imagePickerLauncher.launch("image/*")
                    }
                },
                onMention = {
                    mentionQuery = extractActiveMentionQuery(inputText) ?: ""
                    showMentionPicker = true
                },
                availableSkills = availableSkills,
                selectedSkillCount = selectedSkills.size,
                onOpenSkillPicker = {
                    if (availableSkills.isNotEmpty()) {
                        showSkillPicker = true
                    }
                },
                canRecallPrevious = inputHistory.isNotEmpty(),
                canRecallNext = inputHistoryIndex != -1,
                canSend = canSubmitDraft,
                allowStructuredActions = selectedImages.isEmpty(),
                canUseMultimodal = multimodalEnabled,
                hasPendingImages = selectedImages.isNotEmpty(),
                enabled = true,
                isSending = state.isProcessing,
                bottomReservedPadding = bottomReservedPadding,
                onStopSending = onStopSending
            )

            if (showSkillPicker) {
                SkillPickerDialog(
                    skills = availableSkills,
                    selectedSkills = selectedSkills.toList(),
                    onDismiss = { showSkillPicker = false },
                    onToggleSkill = { skill ->
                        val existingIndex = selectedSkills.indexOfFirst {
                            it.title == skill.title && it.content == skill.content
                        }
                        if (existingIndex >= 0) {
                            selectedSkills.removeAt(existingIndex)
                        } else {
                            selectedSkills.add(skill)
                        }
                    },
                    onClear = { selectedSkills.clear() }
                )
            }

            if (showApprovalModeDialog) {
                ChatApprovalModeDialog(
                    globalMode = globalApprovalMode,
                    overrideMode = approvalModeOverride,
                    onDismiss = { showApprovalModeDialog = false },
                    onApply = { overrideMode ->
                        showApprovalModeDialog = false
                        onUpdateProjectToolPreferences(
                            normalizeChatProjectToolPreferences(
                                (projectToolPreferences ?: ProjectToolPreferences()).copy(
                                    approvalMode = overrideMode
                                )
                            )
                        )
                    }
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.End
        ) {
            if (currentProjectLabel != null) {
                CurrentWorkspaceHint(
                    title = currentWorkspaceTitle,
                    workspaceLabel = currentProjectLabel,
                    helperText = currentWorkspaceHelperText,
                    expanded = showCurrentWorkspaceHint,
                    onToggleExpanded = { showCurrentWorkspaceHint = !showCurrentWorkspaceHint }
                )
            }
            checkpointActivityHintPresentation?.let { hintPresentation ->
                FileChangeStatusHint(
                    presentation = hintPresentation,
                    expanded = showFileChangeHint,
                    onToggleExpanded = { showFileChangeHint = !showFileChangeHint },
                    onClick = { showFileChangeHistory = true }
                )
            }
            recentHistorySurfacePresentation?.let { presentation ->
                RecentHistoryStatusHint(
                    presentation = presentation,
                    expanded = showRecentHistoryHint,
                    onToggleExpanded = { showRecentHistoryHint = !showRecentHistoryHint },
                    onClick = { showRecentHistorySurface = true }
                )
            }
            archivedMemorySurfacePresentation?.let { presentation ->
                ArchivedMemoryStatusHint(
                    presentation = presentation,
                    expanded = showArchivedMemoryHint,
                    onToggleExpanded = { showArchivedMemoryHint = !showArchivedMemoryHint },
                    onClick = { showArchivedMemorySurface = true }
                )
            }
            if (state.subagentRuns.isNotEmpty()) {
                SubagentStatusHint(
                    runs = state.subagentRuns,
                    expanded = showSubagentHint,
                    onToggleExpanded = { showSubagentHint = !showSubagentHint },
                    onClick = { showSubagentHistory = true }
                )
            }
            if (workflowExecutionMode == WorkflowExecutionMode.SINGLE_PASS) {
                WorkflowExecutionPreferenceHint(
                    title = executionProfileHint.title,
                    message = executionProfileHint.message,
                    expanded = showWorkflowPreferenceHint,
                    onToggleExpanded = { showWorkflowPreferenceHint = !showWorkflowPreferenceHint }
                )
            }
            if (topStatusStripPresentation.compact) {
                ApprovalPostureStatusHint(
                    presentation = chatRuntimeHostPresentation.approvalRuntimePosturePresentation,
                    expanded = showApprovalPostureHint,
                    onToggleExpanded = { showApprovalPostureHint = !showApprovalPostureHint }
                )
            }
            when {
                compressionActionInProgress -> CompressionActionHint(
                    title = "正在压缩上下文",
                    message = "首次压缩需要生成摘要并刷新会话上下文，请稍等片刻。",
                    expanded = showCompressionHint,
                    onToggleExpanded = { showCompressionHint = !showCompressionHint }
                )
                compressionActionError != null -> CompressionActionHint(
                    title = "压缩失败",
                    message = compressionActionError ?: "上下文压缩失败",
                    expanded = showCompressionHint,
                    onToggleExpanded = { showCompressionHint = !showCompressionHint },
                    actionLabel = "重试压缩",
                    onAction = { launchCompressionAction() },
                    secondaryActionLabel = "稍后提醒",
                    onSecondaryAction = {
                        compressionActionError = null
                        showCompressionHint = false
                    }
                )
            }
            state.compressionSnapshot?.let { snapshot ->
                CompressionStatusHint(
                    version = snapshot.version,
                    totalVersions = state.compressionSnapshots.size,
                    summary = snapshot.summary,
                    sourceMessageCount = snapshot.sourceMessageCount,
                    active = snapshot.active,
                    createdAt = snapshot.createdAt,
                    expanded = showCompressionHint,
                    onToggleExpanded = { showCompressionHint = !showCompressionHint },
                    onClick = { showCompressionHistory = true }
                )
            }
            if (
                !compressionActionInProgress &&
                compressionActionError == null &&
                compressionSuggestion != null &&
                !state.isProcessing &&
                (dismissedCompressionSuggestionAtCount == null ||
                    state.messages.size > dismissedCompressionSuggestionAtCount!!)
            ) {
                CompressionSuggestionHint(
                    suggestion = compressionSuggestion,
                    expanded = showCompressionHint,
                    onToggleExpanded = { showCompressionHint = !showCompressionHint },
                    compressing = false,
                    onCompress = { launchCompressionAction() },
                    onDismiss = {
                        dismissedCompressionSuggestionAtCount = state.messages.size
                        showCompressionHint = false
                    }
                )
            }
        }
    }

    if (previewImages.isNotEmpty()) {
        ImagePreviewDialog(
            items = previewImages,
            initialIndex = previewImageIndex,
            onDismiss = {
                previewImages = emptyList()
                previewImageIndex = 0
            }
        )
    }

    if (showMentionPicker) {
        MentionFilePickerDialog(
            query = mentionQuery,
            recentMentions = recentMentions.toList(),
            knowledgePaths = projectKnowledgePathSet,
            knowledgeOutlines = projectKnowledgeOutlineMap,
            snapshotNamesByPath = projectKnowledgeSnapshotNamesByPath,
            onQueryChange = { mentionQuery = it },
            onSearch = onSearchFiles,
            projectKnowledgeMentions = projectKnowledgeMentions,
            onDismiss = { showMentionPicker = false },
            onSelect = { mention -> handleMentionSelect(mention) }
        )
    }

    messageActionTarget?.let { target ->
        MessageActionSheet(
            msg = target,
            onDismiss = { messageActionTarget = null },
            onCopyMessage = {
                copyTextToClipboard(context, target.content.ifBlank { target.reasoning ?: "" })
                messageActionTarget = null
            },
            onCopyRound = {
                val roundText = buildRoundText(state.messages, target.id)
                copyTextToClipboard(context, roundText)
                messageActionTarget = null
            },
            onEditMessage = if (target.role == "user") {
                {
                    if (onEditMessage(target.id)) {
                        inputText = target.content
                        editingMessageId = target.id
                    }
                    messageActionTarget = null
                }
            } else {
                null
            }
        )
    }

    selectedSubagentRun?.let { run ->
        SubagentDetailSheet(
            run = run,
            onRetry = { onRetrySubagent(run.runId) },
            onCancel = { onCancelSubagent(run.runId) },
            onDismiss = { selectedSubagentRun = null }
        )
    }
    selectedSubagentBatch?.let { batch ->
        SubagentBatchDetailSheet(
            batch = batch,
            onDismiss = { selectedSubagentBatch = null }
        )
    }
    if (showSubagentHistory && state.subagentRuns.isNotEmpty()) {
        SubagentRunHistorySheet(
            runs = state.subagentRuns,
            onDismiss = { showSubagentHistory = false },
            onOpenRun = { run ->
                selectedSubagentRun = run
                showSubagentHistory = false
            }
        )
    }

    if (showCompressionHistory && state.compressionSnapshots.isNotEmpty()) {
        CompressionHistorySheet(
            snapshots = state.compressionSnapshots,
            onDismiss = { showCompressionHistory = false }
        )
    }
    if (showFileChangeHistory && hasChatCheckpointActivity(checkpointHistoryPresentation)) {
        FileChangeBatchSheet(
            presentation = checkpointHistoryPresentation,
            onDismiss = { showFileChangeHistory = false },
            onOpenCheckpoint = { checkpointId ->
                selectedCheckpoint = state.checkpoints.firstOrNull { it.id == checkpointId }
                showFileChangeHistory = false
            },
            onOpenRecovery = { recoveryId ->
                selectedRecoveryId = recoveryId
                showFileChangeHistory = false
            }
        )
    }
    if (showRecentHistorySurface && recentHistorySurfacePresentation != null) {
        RecentHistoryDetailSheet(
            presentation = recentHistorySurfacePresentation,
            onDismiss = { showRecentHistorySurface = false }
        )
    }
    if (showArchivedMemorySurface && archivedMemorySurfacePresentation != null) {
        ArchivedMemoryDetailSheet(
            presentation = archivedMemorySurfacePresentation,
            onAcceptCandidate = onAcceptArchivedMemoryCandidate,
            onDismissCandidate = onDismissArchivedMemoryCandidate,
            onConsumeCandidate = onConsumeArchivedMemoryCandidate,
            onDismiss = { showArchivedMemorySurface = false }
        )
    }
    selectedCheckpoint?.let { checkpoint ->
        FileChangeBatchDetailSheet(
            checkpoint = checkpoint,
            records = state.fileChanges.filter { it.checkpointId == checkpoint.id },
            onDismiss = { selectedCheckpoint = null },
            onRollbackCheckpoint = {
                selectedCheckpoint = null
                onRollbackCheckpoint(checkpoint.id, checkpoint.scope)
            },
            onOpenRecord = { record ->
                selectedFileChange = record
                selectedCheckpoint = null
            }
        )
    }
    selectedFileChange?.let { record ->
        FileChangeDetailSheet(
            record = record,
            onDismiss = { selectedFileChange = null }
        )
    }
    selectedRecoveryId
        ?.let { recoveryId -> findChatCheckpointRecoveryPresentation(checkpointHistoryPresentation, recoveryId) }
        ?.let { recovery ->
            CheckpointRecoveryDetailSheet(
                record = recovery,
                onDismiss = { selectedRecoveryId = null }
            )
        }
}


@Composable
private fun WorkflowExecutionPreferenceHint(
    title: String,
    message: String,
    expanded: Boolean,
    onToggleExpanded: () -> Unit
) {
    CompactTopHint(
        badge = "档",
        title = title,
        message = message,
        expanded = expanded,
        onToggleExpanded = onToggleExpanded
    )
}

private data class WorkflowExecutionProfileHintUi(
    val title: String,
    val message: String
)

private fun buildWorkflowExecutionProfileHint(
    config: ProviderConfig
): WorkflowExecutionProfileHintUi {
    val provider = ProviderRegistry.getActiveProvider(config.activeProviderId)
    val mainSummary = buildMainExecutionProfileHintSummary(config, provider)
    val plannerSummary = buildOverrideExecutionProfileHintSummary(
        provider = provider,
        enabled = config.plannerProfileEnabled,
        inheritedModel = config.getActiveModel(),
        inheritedReasoningEffort = config.getActiveReasoningEffort(),
        overrideModel = config.plannerModel,
        overrideReasoningEffort = config.plannerReasoningEffort
    )
    val subagentSummary = buildOverrideExecutionProfileHintSummary(
        provider = provider,
        enabled = config.subagentDefaultProfileEnabled,
        inheritedModel = config.getActiveModel(),
        inheritedReasoningEffort = config.getActiveReasoningEffort(),
        overrideModel = config.subagentDefaultModel,
        overrideReasoningEffort = config.subagentDefaultReasoningEffort
    )
    return WorkflowExecutionProfileHintUi(
        title = "执行 Profile · ${provider.name}",
        message = buildString {
            append(
                if (config.autoRouteBeforeExecution) {
                    "单次工作流开启，发送前会先做自动分流。"
                } else {
                    "单次工作流开启，发送后直接进入执行。"
                }
            )
            append("\n主聊天: ").append(mainSummary)
            append("\n计划 / 分流: ").append(plannerSummary)
            append("\n子代理默认: ").append(subagentSummary)
        }
    )
}

@Composable
private fun RecentHistoryStatusHint(
    presentation: ChatRecentHistorySurfacePresentation,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onClick: () -> Unit
) {
    CompactTopHint(
        badge = "史",
        title = presentation.title,
        message = presentation.message,
        expanded = expanded,
        onToggleExpanded = onToggleExpanded,
        onBubbleClick = onClick,
        actionLabel = "查看",
        onAction = onClick
    )
}

@Composable
private fun RecentHistoryDetailSheet(
    presentation: ChatRecentHistorySurfacePresentation,
    onDismiss: () -> Unit
) {
    val surfaceColor = rememberMurongSurfaceColor()
    val mutedTextColor = rememberMurongMutedTextColor()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = presentation.detailTitle,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = presentation.detailSubtitle,
                style = MaterialTheme.typography.bodySmall,
                color = mutedTextColor
            )
            MurongGlassSurface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                contentPadding = PaddingValues(14.dp),
                surfaceColorOverride = surfaceColor.copy(alpha = 0.72f)
            ) {
                Text(
                    text = presentation.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            if (presentation.detailRows.isEmpty()) {
                Text(
                    text = "当前还没有可展开的历史细项。",
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedTextColor
                )
            } else {
                presentation.detailRows.forEach { row ->
                    MurongGlassSurface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        contentPadding = PaddingValues(12.dp),
                        surfaceColorOverride = surfaceColor.copy(alpha = 0.66f)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = row.label,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = row.value,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun ArchivedMemoryStatusHint(
    presentation: ChatArchivedMemorySurfacePresentation,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onClick: () -> Unit
) {
    CompactTopHint(
        badge = "忆",
        title = presentation.title,
        message = presentation.message,
        expanded = expanded,
        onToggleExpanded = onToggleExpanded,
        onBubbleClick = onClick,
        actionLabel = "查看",
        onAction = onClick
    )
}

@Composable
private fun ArchivedMemoryDetailSheet(
    presentation: ChatArchivedMemorySurfacePresentation,
    onAcceptCandidate: suspend (String, ArchivedMemoryCandidateScope) -> ArchivedMemoryCandidateMutationResult,
    onDismissCandidate: suspend (String) -> ArchivedMemoryCandidateMutationResult,
    onConsumeCandidate: suspend (String) -> ArchivedMemoryCandidateMutationResult,
    onDismiss: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val surfaceColor = rememberMurongSurfaceColor()
    val mutedTextColor = rememberMurongMutedTextColor()
    var activeAction by remember(presentation.detailSubtitle) {
        mutableStateOf<ArchivedMemoryCandidatePendingActionUi?>(null)
    }
    var actionFeedback by remember(presentation.detailSubtitle) {
        mutableStateOf<ArchivedMemoryCandidateActionFeedbackUi?>(null)
    }
    var pendingConfirmation by remember(presentation.detailSubtitle) {
        mutableStateOf<ArchivedMemoryCandidateConfirmationUi?>(null)
    }
    val visibleCandidateIds = remember(presentation.candidates) {
        presentation.candidates.map { it.sessionId }.toSet()
    }
    val batchCandidateActions = remember(presentation.batchCandidates) {
        presentation.batchCandidates.map { candidate ->
            ArchivedMemoryCandidateOperationUi.Single(
                sessionId = candidate.sessionId,
                kind = ArchivedMemoryCandidateActionKind.ACCEPT,
                scope = candidate.suggestedScope
            )
        }
    }

    suspend fun executeArchivedMemoryCandidateOperation(
        operation: ArchivedMemoryCandidateOperationUi
    ): ArchivedMemoryCandidateActionFeedbackUi {
        return when (operation) {
            is ArchivedMemoryCandidateOperationUi.Single -> {
                val result = when (operation.kind) {
                    ArchivedMemoryCandidateActionKind.ACCEPT -> onAcceptCandidate(
                        operation.sessionId,
                        operation.scope ?: ArchivedMemoryCandidateScope.GLOBAL
                    )
                    ArchivedMemoryCandidateActionKind.CONSUME -> onConsumeCandidate(operation.sessionId)
                    ArchivedMemoryCandidateActionKind.DISMISS -> onDismissCandidate(operation.sessionId)
                }
                ArchivedMemoryCandidateActionFeedbackUi(
                    sessionIds = listOf(operation.sessionId),
                    success = result.success,
                    message = result.message,
                    retryOperation = operation.takeIf { !result.success }
                )
            }
            is ArchivedMemoryCandidateOperationUi.Batch -> {
                val failures = mutableListOf<ArchivedMemoryCandidateOperationUi.Single>()
                var successCount = 0
                operation.actions.forEach { action ->
                    when (action.kind) {
                        ArchivedMemoryCandidateActionKind.ACCEPT -> {
                            val result = onAcceptCandidate(
                                action.sessionId,
                                action.scope ?: ArchivedMemoryCandidateScope.GLOBAL
                            )
                            if (result.success) successCount += 1 else failures += action
                        }
                        ArchivedMemoryCandidateActionKind.CONSUME -> {
                            val result = onConsumeCandidate(action.sessionId)
                            if (result.success) successCount += 1 else failures += action
                        }
                        ArchivedMemoryCandidateActionKind.DISMISS -> {
                            val result = onDismissCandidate(action.sessionId)
                            if (result.success) successCount += 1 else failures += action
                        }
                    }
                }
                ArchivedMemoryCandidateActionFeedbackUi(
                    sessionIds = operation.actions.map { it.sessionId },
                    success = failures.isEmpty(),
                    message = buildArchivedMemoryBatchActionMessage(
                        kind = operation.kind,
                        successCount = successCount,
                        failureCount = failures.size
                    ),
                    retryOperation = failures.takeIf { it.isNotEmpty() }?.let { failedActions ->
                        ArchivedMemoryCandidateOperationUi.Batch(
                            kind = operation.kind,
                            actions = failedActions
                        )
                    }
                )
            }
        }
    }

    fun launchArchivedMemoryCandidateAction(operation: ArchivedMemoryCandidateOperationUi) {
        actionFeedback = null
        pendingConfirmation = null
        activeAction = ArchivedMemoryCandidatePendingActionUi(
            sessionIds = operation.sessionIds,
            label = formatArchivedMemoryActionPendingLabel(
                action = operation.kind,
                scope = operation.singleScope
            )
        )
        coroutineScope.launch {
            activeAction = null
            actionFeedback = executeArchivedMemoryCandidateOperation(operation)
        }
    }
    LaunchedEffect(
        visibleCandidateIds,
        actionFeedback?.sessionIds,
        actionFeedback?.success
    ) {
        val feedback = actionFeedback ?: return@LaunchedEffect
        if (!feedback.success) return@LaunchedEffect
        if (feedback.sessionIds.any { it in visibleCandidateIds }) return@LaunchedEffect
        delay(if (visibleCandidateIds.isEmpty()) 900 else 1200)
        if (visibleCandidateIds.isEmpty()) {
            onDismiss()
        } else if (actionFeedback?.sessionIds == feedback.sessionIds && actionFeedback?.success == true) {
            actionFeedback = null
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = presentation.detailTitle,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = presentation.detailSubtitle,
                style = MaterialTheme.typography.bodySmall,
                color = mutedTextColor
            )
            MurongGlassSurface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                contentPadding = PaddingValues(14.dp),
                surfaceColorOverride = surfaceColor.copy(alpha = 0.72f)
            ) {
                Text(
                    text = presentation.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            if (presentation.totalCandidateCount > 1) {
                MurongGlassSurface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    contentPadding = PaddingValues(12.dp),
                    surfaceColorOverride = surfaceColor.copy(alpha = 0.62f)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = buildArchivedMemoryBatchActionSummary(
                                visibleCount = presentation.candidates.size,
                                totalCount = presentation.totalCandidateCount
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = mutedTextColor
                        )
                        FilledTonalButton(
                            modifier = Modifier.fillMaxWidth(),
                            enabled = activeAction == null,
                            onClick = {
                                launchArchivedMemoryCandidateAction(
                                    ArchivedMemoryCandidateOperationUi.Batch(
                                        kind = ArchivedMemoryCandidateActionKind.ACCEPT,
                                        actions = batchCandidateActions
                                    )
                                )
                            }
                        ) {
                            Text("全部待处理项按建议保存")
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                modifier = Modifier.weight(1f),
                                enabled = activeAction == null,
                                onClick = {
                                    pendingConfirmation = buildArchivedMemoryBatchConfirmationUi(
                                        candidates = presentation.batchCandidates,
                                        kind = ArchivedMemoryCandidateActionKind.CONSUME
                                    )
                                }
                            ) {
                                Text("全部待处理项标记已处理")
                            }
                            OutlinedButton(
                                modifier = Modifier.weight(1f),
                                enabled = activeAction == null,
                                onClick = {
                                    pendingConfirmation = buildArchivedMemoryBatchConfirmationUi(
                                        candidates = presentation.batchCandidates,
                                        kind = ArchivedMemoryCandidateActionKind.DISMISS
                                    )
                                }
                            ) {
                                Text("全部待处理项关闭")
                            }
                        }
                    }
                }
            }
            actionFeedback?.let { feedback ->
                MurongGlassSurface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    contentPadding = PaddingValues(12.dp),
                    surfaceColorOverride = surfaceColor.copy(alpha = if (feedback.success) 0.58f else 0.74f)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = if (feedback.success) "处理结果" else "处理失败",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (feedback.success) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            }
                        )
                        Text(
                            text = feedback.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        feedback.retryOperation?.takeIf { activeAction == null }?.let { retryAction ->
                            TextButton(
                                onClick = { launchArchivedMemoryCandidateAction(retryAction) }
                            ) {
                                Text("重试")
                            }
                        }
                    }
                }
            }
            if (presentation.candidates.isEmpty()) {
                Text(
                    text = "当前还没有可展开的归档记忆候选细项。",
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedTextColor
                )
            } else {
                presentation.candidates.forEachIndexed { index, candidate ->
                    val alternateScope = alternateArchivedMemoryCandidateScope(candidate.suggestedScope)
                    val candidateAction = activeAction?.takeIf { candidate.sessionId in it.sessionIds }
                    val candidateBusy = candidateAction != null
                    MurongGlassSurface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        contentPadding = PaddingValues(12.dp),
                        surfaceColorOverride = surfaceColor.copy(alpha = 0.66f)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "候选 ${index + 1} · ${formatArchivedMemoryCandidateScopeChip(candidate.suggestedScope)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "会话：${candidate.sourceSessionTitle}",
                                style = MaterialTheme.typography.bodySmall,
                                color = mutedTextColor
                            )
                            Text(
                                text = candidate.suggestedTitle,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = candidate.suggestedContentPreview,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            candidate.sourceAnchorMessageReference?.let { anchor ->
                                Text(
                                    text = "历史锚点：$anchor",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = mutedTextColor
                                )
                            }
                            candidate.sourceFinalReadinessSummary?.let { summary ->
                                Text(
                                    text = "最终收口：$summary",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = mutedTextColor
                                )
                            }
                            if (candidateAction != null) {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                Text(
                                    text = candidateAction.label,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = mutedTextColor
                                )
                            }
                            FilledTonalButton(
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !candidateBusy,
                                onClick = {
                                    launchArchivedMemoryCandidateAction(
                                        ArchivedMemoryCandidateOperationUi.Single(
                                            sessionId = candidate.sessionId,
                                            kind = ArchivedMemoryCandidateActionKind.ACCEPT,
                                            scope = candidate.suggestedScope
                                        )
                                    )
                                }
                            ) {
                                Text(text = formatArchivedMemoryAcceptLabel(candidate.suggestedScope, primary = true))
                            }
                            alternateScope?.let { scope ->
                                OutlinedButton(
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = !candidateBusy,
                                    onClick = {
                                        launchArchivedMemoryCandidateAction(
                                            ArchivedMemoryCandidateOperationUi.Single(
                                                sessionId = candidate.sessionId,
                                                kind = ArchivedMemoryCandidateActionKind.ACCEPT,
                                                scope = scope
                                            )
                                        )
                                    }
                                ) {
                                    Text(text = formatArchivedMemoryAcceptLabel(scope, primary = false))
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                TextButton(
                                    modifier = Modifier.weight(1f),
                                    enabled = !candidateBusy,
                                    onClick = {
                                        pendingConfirmation = buildArchivedMemoryCandidateConfirmationUi(
                                            candidate = candidate,
                                            kind = ArchivedMemoryCandidateActionKind.CONSUME
                                        )
                                    }
                                ) {
                                    Text("标记已处理")
                                }
                                TextButton(
                                    modifier = Modifier.weight(1f),
                                    enabled = !candidateBusy,
                                    onClick = {
                                        pendingConfirmation = buildArchivedMemoryCandidateConfirmationUi(
                                            candidate = candidate,
                                            kind = ArchivedMemoryCandidateActionKind.DISMISS
                                        )
                                    }
                                ) {
                                    Text("关闭候选")
                                }
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
    pendingConfirmation?.let { confirmation ->
        AlertDialog(
            onDismissRequest = { pendingConfirmation = null },
            title = { Text(confirmation.title) },
            text = { Text(confirmation.message) },
            confirmButton = {
                TextButton(
                    onClick = { launchArchivedMemoryCandidateAction(confirmation.action) }
                ) {
                    Text(confirmation.confirmLabel)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingConfirmation = null }) {
                    Text("取消")
                }
            }
        )
    }
}

private data class ArchivedMemoryCandidatePendingActionUi(
    val sessionIds: List<String>,
    val label: String
)

private data class ArchivedMemoryCandidateActionFeedbackUi(
    val sessionIds: List<String> = emptyList(),
    val success: Boolean,
    val message: String,
    val retryOperation: ArchivedMemoryCandidateOperationUi? = null
)

private data class ArchivedMemoryCandidateConfirmationUi(
    val title: String,
    val message: String,
    val confirmLabel: String,
    val action: ArchivedMemoryCandidateOperationUi
)

private sealed interface ArchivedMemoryCandidateOperationUi {
    val kind: ArchivedMemoryCandidateActionKind
    val sessionIds: List<String>
    val singleScope: ArchivedMemoryCandidateScope?

    data class Single(
        val sessionId: String,
        override val kind: ArchivedMemoryCandidateActionKind,
        val scope: ArchivedMemoryCandidateScope? = null
    ) : ArchivedMemoryCandidateOperationUi {
        override val sessionIds: List<String> = listOf(sessionId)
        override val singleScope: ArchivedMemoryCandidateScope? = scope
    }

    data class Batch(
        override val kind: ArchivedMemoryCandidateActionKind,
        val actions: List<Single>
    ) : ArchivedMemoryCandidateOperationUi {
        override val sessionIds: List<String> = actions.map { it.sessionId }
        override val singleScope: ArchivedMemoryCandidateScope? = null
    }
}

private enum class ArchivedMemoryCandidateActionKind {
    ACCEPT,
    CONSUME,
    DISMISS
}

private fun alternateArchivedMemoryCandidateScope(
    scope: ArchivedMemoryCandidateScope
): ArchivedMemoryCandidateScope? {
    return when (scope) {
        ArchivedMemoryCandidateScope.PROJECT -> ArchivedMemoryCandidateScope.GLOBAL
        ArchivedMemoryCandidateScope.GLOBAL -> ArchivedMemoryCandidateScope.PROJECT
    }
}

private fun formatArchivedMemoryCandidateScopeChip(scope: ArchivedMemoryCandidateScope): String {
    return when (scope) {
        ArchivedMemoryCandidateScope.PROJECT -> "项目记忆建议"
        ArchivedMemoryCandidateScope.GLOBAL -> "全局记忆建议"
    }
}

private fun formatArchivedMemoryAcceptLabel(
    scope: ArchivedMemoryCandidateScope,
    primary: Boolean
): String {
    return when (scope) {
        ArchivedMemoryCandidateScope.PROJECT ->
            if (primary) "按建议保存为项目记忆" else "改存为项目记忆"
        ArchivedMemoryCandidateScope.GLOBAL ->
            if (primary) "按建议保存为全局记忆" else "改存为全局记忆"
    }
}

private fun formatArchivedMemoryActionPendingLabel(
    action: ArchivedMemoryCandidateActionKind,
    scope: ArchivedMemoryCandidateScope? = null
): String {
    return when (action) {
        ArchivedMemoryCandidateActionKind.ACCEPT -> when (scope) {
            ArchivedMemoryCandidateScope.PROJECT -> "正在保存为项目记忆..."
            ArchivedMemoryCandidateScope.GLOBAL -> "正在保存为全局记忆..."
            null -> "正在处理归档记忆候选..."
        }
        ArchivedMemoryCandidateActionKind.CONSUME -> "正在标记为已处理..."
        ArchivedMemoryCandidateActionKind.DISMISS -> "正在关闭归档候选..."
    }
}

private fun buildArchivedMemoryBatchActionSummary(
    visibleCount: Int,
    totalCount: Int
): String {
    return if (visibleCount >= totalCount) {
        "可对当前待处理的 $visibleCount 条归档候选执行批量处理。"
    } else {
        "当前先对展示中的 $visibleCount 条归档候选提供批量处理，共待处理 $totalCount 条。"
    }
}

private fun buildArchivedMemoryBatchActionMessage(
    kind: ArchivedMemoryCandidateActionKind,
    successCount: Int,
    failureCount: Int
): String {
    val successLabel = when (kind) {
        ArchivedMemoryCandidateActionKind.ACCEPT -> "按建议保存"
        ArchivedMemoryCandidateActionKind.CONSUME -> "标记已处理"
        ArchivedMemoryCandidateActionKind.DISMISS -> "关闭"
    }
    return when {
        failureCount == 0 -> "已${successLabel} $successCount 条归档候选。"
        successCount == 0 -> "批量${successLabel}失败，$failureCount 条候选未处理。"
        else -> "已${successLabel} $successCount 条，仍有 $failureCount 条处理失败。"
    }
}

private fun buildArchivedMemoryCandidateConfirmationUi(
    candidate: ChatArchivedMemoryCandidateItemPresentation,
    kind: ArchivedMemoryCandidateActionKind
): ArchivedMemoryCandidateConfirmationUi {
    return when (kind) {
        ArchivedMemoryCandidateActionKind.CONSUME -> ArchivedMemoryCandidateConfirmationUi(
            title = "确认标记已处理",
            message = "将“${candidate.suggestedTitle}”标记为已处理后，它会从待处理归档候选里移除，但不会保存成记忆。",
            confirmLabel = "确认处理",
            action = ArchivedMemoryCandidateOperationUi.Single(
                sessionId = candidate.sessionId,
                kind = ArchivedMemoryCandidateActionKind.CONSUME
            )
        )
        ArchivedMemoryCandidateActionKind.DISMISS -> ArchivedMemoryCandidateConfirmationUi(
            title = "确认关闭候选",
            message = "关闭“${candidate.suggestedTitle}”后，这条归档候选会从待处理列表移除，后续不再提示。",
            confirmLabel = "确认关闭",
            action = ArchivedMemoryCandidateOperationUi.Single(
                sessionId = candidate.sessionId,
                kind = ArchivedMemoryCandidateActionKind.DISMISS
            )
        )
        ArchivedMemoryCandidateActionKind.ACCEPT -> ArchivedMemoryCandidateConfirmationUi(
            title = "确认保存候选",
            message = "即将保存“${candidate.suggestedTitle}”到记忆。",
            confirmLabel = "确认保存",
            action = ArchivedMemoryCandidateOperationUi.Single(
                sessionId = candidate.sessionId,
                kind = ArchivedMemoryCandidateActionKind.ACCEPT,
                scope = candidate.suggestedScope
            )
        )
    }
}

private fun buildArchivedMemoryBatchConfirmationUi(
    candidates: List<ChatArchivedMemoryCandidateBatchTargetPresentation>,
    kind: ArchivedMemoryCandidateActionKind
): ArchivedMemoryCandidateConfirmationUi {
    val actionLabel = when (kind) {
        ArchivedMemoryCandidateActionKind.CONSUME -> "标记已处理"
        ArchivedMemoryCandidateActionKind.DISMISS -> "关闭"
        ArchivedMemoryCandidateActionKind.ACCEPT -> "保存"
    }
    return ArchivedMemoryCandidateConfirmationUi(
        title = "确认批量$actionLabel",
        message = "即将对全部待处理的 ${candidates.size} 条归档候选执行“$actionLabel”。",
        confirmLabel = "确认$actionLabel",
        action = ArchivedMemoryCandidateOperationUi.Batch(
            kind = kind,
            actions = candidates.map { candidate ->
                ArchivedMemoryCandidateOperationUi.Single(
                    sessionId = candidate.sessionId,
                    kind = kind,
                    scope = candidate.suggestedScope
                )
            }
        )
    )
}

private fun buildMainExecutionProfileHintSummary(
    config: ProviderConfig,
    provider: com.murong.agent.core.provider.ModelProvider
): String {
    val resolvedModel = config.getActiveModel()
    val reasoningEffort = config.getActiveReasoningEffort()
    val supportsAutoModelSelection = config.activeProviderId == "deepseek"
    val modelPart = if (supportsAutoModelSelection && config.isModelAutoSelectionEnabled()) {
        "模型自动（${provider.formatModelDisplayName(resolvedModel)}）"
    } else {
        "模型固定（${provider.formatModelDisplayName(resolvedModel)}）"
    }
    val reasoningPart = if (provider.supportsReasoning) {
        val reasoningLabel = provider.formatReasoningDisplayName(reasoningEffort) ?: reasoningEffort.orEmpty()
        if (config.isReasoningAutoSelectionEnabled()) {
            "推理自动（$reasoningLabel）"
        } else {
            "推理固定（$reasoningLabel）"
        }
    } else {
        "无独立推理档位"
    }
    return "$modelPart · $reasoningPart"
}

private fun buildOverrideExecutionProfileHintSummary(
    provider: com.murong.agent.core.provider.ModelProvider,
    enabled: Boolean,
    inheritedModel: String,
    inheritedReasoningEffort: String?,
    overrideModel: String,
    overrideReasoningEffort: String
): String {
    if (!enabled) return "继承主聊天"
    val resolvedModel = overrideModel.trim().ifBlank { inheritedModel }
    val resolvedReasoning = overrideReasoningEffort.trim().ifBlank {
        inheritedReasoningEffort.orEmpty()
    }
    val profileLabel = provider.buildExecutionProfileLabel(
        modelId = resolvedModel,
        reasoningEffort = resolvedReasoning.ifBlank { null }
    )
    val overrideParts = buildList {
        if (overrideModel.isNotBlank()) add("模型")
        if (overrideReasoningEffort.isNotBlank()) add("推理")
    }
    return if (overrideParts.isEmpty()) {
        "已开启，但当前仍继承主聊天 · $profileLabel"
    } else {
        "独立${overrideParts.joinToString("+")} · $profileLabel"
    }
}

@Composable
private fun AutoRouteDecisionStatusBar(decision: AutoRouteDecisionUi) {
    WorkflowStatusStrip(
        title = "自动选择",
        message = "${formatAutoRouteAction(decision.action)}。${decision.reason}"
    )
}

@Composable
private fun WorkflowFallbackStatusBar(message: String) {
    WorkflowStatusStrip(
        title = "兜底策略",
        message = "本次已触发兜底策略: $message"
    )
}

@Composable
private fun FinalReadinessStatusBar(receipt: FinalReadinessReceipt) {
    val presentation = remember(receipt) { receipt.toFinalReadinessPresentation() }
    WorkflowStatusStrip(
        title = presentation.title,
        message = presentation.message
    )
}

@Composable
private fun WorkflowStatusStrip(
    title: String,
    message: String,
    badge: String? = null
) {
    val accent = rememberMurongAccentColor()
    val mutedTextColor = rememberMurongMutedTextColor()
    val chromeColor = rememberMurongChromeColor()
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(20.dp),
        color = chromeColor,
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    color = accent
                )
                badge?.let { badgeLabel ->
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = accent.copy(alpha = 0.14f)
                    ) {
                        Text(
                            text = badgeLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = accent,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
            }
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = mutedTextColor
            )
        }
    }
}

@Composable
private fun ApprovalPostureStatusHint(
    presentation: ApprovalRuntimePosturePresentation,
    expanded: Boolean,
    onToggleExpanded: () -> Unit
) {
    CompactTopHint(
        badge = "审",
        title = presentation.shortcutLabel,
        message = presentation.message,
        expanded = expanded,
        onToggleExpanded = onToggleExpanded
    )
}

@Composable
private fun CompactTopHint(
    badge: String,
    title: String,
    message: String,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onBubbleClick: (() -> Unit)? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null
) {
    val chromeColor = rememberMurongChromeColor()
    val mutedTextColor = rememberMurongMutedTextColor()
    Row(
        modifier = Modifier
            .padding(end = 12.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.Top
    ) {
        AnimatedVisibility(visible = expanded) {
            Surface(
                color = chromeColor,
                tonalElevation = 2.dp,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .then(if (onBubbleClick != null) Modifier.clickable(onClick = onBubbleClick) else Modifier)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(message, style = MaterialTheme.typography.bodySmall, color = mutedTextColor)
                    if (actionLabel != null && onAction != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = onAction) { Text(actionLabel) }
                            if (secondaryActionLabel != null && onSecondaryAction != null) {
                                TextButton(onClick = onSecondaryAction) { Text(secondaryActionLabel) }
                            }
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Surface(
            shape = RoundedCornerShape(999.dp),
            color = chromeColor,
            tonalElevation = 2.dp,
            modifier = Modifier
                .size(28.dp)
                .clickable(onClick = onToggleExpanded)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun MentionedFilesBar(
    mentions: List<FileMentionUi>,
    knowledgePaths: Set<String>,
    snapshotNamesByPath: Map<String, List<String>>,
    onRemove: (FileMentionUi) -> Unit
) {
    val accent = rememberMurongAccentColor()
    MurongGlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.large,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "已引用文件",
                style = MaterialTheme.typography.labelMedium,
                color = accent
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                mentions.forEach { mention ->
                    val snapshotNames = snapshotNamesByPath[mention.path].orEmpty()
                    InputChip(
                        selected = true,
                        onClick = { onRemove(mention) },
                        label = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (mention.path in knowledgePaths) {
                                    MentionKnowledgeBadge(compact = true)
                                }
                                snapshotNames.take(1).forEach { snapshotName ->
                                    MentionSnapshotBadge(label = snapshotName, compact = true)
                                }
                                Text(
                                    text = mention.displayPath,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        },
                        trailingIcon = { Text("x", fontSize = 11.sp) }
                    )
                }
            }
        }
    }
}

@Composable
private fun InlineMentionSuggestionsCard(
    query: String,
    recentMentions: List<FileMentionUi>,
    results: List<FileMentionUi>,
    knowledgePaths: Set<String>,
    knowledgeOutlines: Map<String, ProjectKnowledgeOutlineUi>,
    snapshotNamesByPath: Map<String, List<String>>,
    onSelect: (FileMentionUi) -> Unit
) {
    val visibleResults = remember(query, recentMentions, results) {
        if (query.isBlank()) {
            recentMentions.take(6)
        } else {
            results.take(6)
        }
    }
    val visibleKnowledgeResults = remember(visibleResults, knowledgePaths, query) {
        if (query.isBlank()) emptyList() else visibleResults.filter { it.path in knowledgePaths }
    }
    val visibleRegularResults = remember(visibleResults, knowledgePaths, query) {
        if (query.isBlank()) visibleResults else visibleResults.filterNot { it.path in knowledgePaths }
    }
    val quickConfirmMention = remember(query, results) {
        resolveQuickConfirmMention(query, results)
    }
    val accent = rememberMurongAccentColor()
    MurongGlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.large,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = if (query.isBlank()) "最近引用文件" else "@文件候选",
                style = MaterialTheme.typography.labelMedium,
                color = accent
            )
            if (quickConfirmMention != null) {
                val quickConfirmReason = remember(quickConfirmMention, query) {
                    mentionMatchReasonLabel(quickConfirmMention, query)
                }
                val quickConfirmOutline = remember(quickConfirmMention, knowledgeOutlines) {
                    knowledgeOutlines[quickConfirmMention.path]
                }
                val quickConfirmSnapshots = remember(quickConfirmMention, snapshotNamesByPath) {
                    snapshotNamesByPath[quickConfirmMention.path].orEmpty()
                }
                MurongGlassSurface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = "唯一命中",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (quickConfirmMention.path in knowledgePaths) {
                                    MentionKnowledgeBadge()
                                }
                                quickConfirmSnapshots.take(2).forEach { snapshotName ->
                                    MentionSnapshotBadge(label = snapshotName)
                                }
                                quickConfirmReason?.let { MentionReasonBadge(label = it) }
                            }
                            Text(
                                text = highlightMentionText(
                                    text = mentionPrimaryTitle(quickConfirmMention.displayPath),
                                    query = query,
                                    highlightColor = MaterialTheme.colorScheme.primary,
                                    highlightWeight = FontWeight.SemiBold
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            mentionSecondaryPath(quickConfirmMention.displayPath)?.let { secondary ->
                                Text(
                                    text = highlightMentionText(
                                        text = secondary,
                                        query = query,
                                        highlightColor = MaterialTheme.colorScheme.primary,
                                        highlightWeight = FontWeight.Medium
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Text(
                                text = if (quickConfirmMention.path in knowledgePaths) {
                                    "来自当前项目知识挂载，且这次已经唯一命中。"
                                } else {
                                    "当前关键字下已唯一命中，可直接引用。"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            quickConfirmOutline?.let { outline ->
                                Text(
                                    text = outline.summary,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (quickConfirmSnapshots.isNotEmpty()) {
                                Text(
                                    text = "已收录于 ${quickConfirmSnapshots.joinToString(" / ")}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        FilledTonalButton(onClick = { onSelect(quickConfirmMention) }) {
                            Text("快速引用")
                        }
                    }
                }
            }
            if (visibleResults.isEmpty()) {
                Text(
                    text = if (query.isBlank()) {
                        "当前还没有最近引用，继续输入文件名或点击右侧 @ 按钮搜索。"
                    } else {
                        "没有匹配文件，继续输入更完整的文件名或路径。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val surfaceColor = rememberMurongSurfaceColor()
                    val chromeColor = rememberMurongChromeColor()
                    val mutedTextColor = rememberMurongMutedTextColor()
                    if (query.isBlank()) {
                        visibleResults.forEach { mention ->
                            MentionCandidateRow(
                                mention = mention,
                                query = query,
                                containerColor = surfaceColor.copy(alpha = 0.82f),
                                isKnowledge = mention.path in knowledgePaths,
                                knowledgeOutline = knowledgeOutlines[mention.path],
                                snapshotNames = snapshotNamesByPath[mention.path].orEmpty(),
                                onClick = { onSelect(mention) }
                            )
                        }
                    } else {
                        MentionCandidateSection(
                            title = "知识文件",
                            results = visibleKnowledgeResults,
                            query = query,
                            knowledgePaths = knowledgePaths,
                            knowledgeOutlines = knowledgeOutlines,
                            snapshotNamesByPath = snapshotNamesByPath,
                            containerColor = chromeColor.copy(alpha = 0.54f),
                            onSelect = onSelect
                        )
                        MentionCandidateSection(
                            title = "其他文件",
                            results = visibleRegularResults,
                            query = query,
                            knowledgePaths = knowledgePaths,
                            knowledgeOutlines = knowledgeOutlines,
                            snapshotNamesByPath = snapshotNamesByPath,
                            containerColor = surfaceColor.copy(alpha = 0.82f),
                            onSelect = onSelect
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MentionInputHintBar(
    query: String,
    matchCount: Int,
    onOpenPicker: () -> Unit
) {
    val accent = rememberMurongAccentColor()
    val mutedTextColor = rememberMurongMutedTextColor()
    MurongGlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.large,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
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
                    text = if (query.isBlank()) "正在准备引用文件" else "正在识别 @${query}",
                    style = MaterialTheme.typography.labelMedium,
                    color = accent
                )
                Text(
                    text = if (query.isBlank()) {
                        "继续输入文件名，或直接从最近引用里选择。"
                    } else {
                        "当前匹配 $matchCount 项，可继续输入缩小范围或打开完整搜索。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedTextColor
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            MurongOutlinedActionButton(text = "完整搜索", onClick = onOpenPicker)
        }
    }
}

@Composable
private fun MentionFilePickerDialog(
    query: String,
    recentMentions: List<FileMentionUi>,
    knowledgePaths: Set<String>,
    knowledgeOutlines: Map<String, ProjectKnowledgeOutlineUi>,
    snapshotNamesByPath: Map<String, List<String>>,
    projectKnowledgeMentions: List<FileMentionUi>,
    onQueryChange: (String) -> Unit,
    onSearch: suspend (String) -> List<FileMentionUi>,
    onDismiss: () -> Unit,
    onSelect: (FileMentionUi) -> Unit
) {
    var localQuery by remember(query) { mutableStateOf(query) }
    val surfaceColor = rememberMurongSurfaceColor()
    val chromeColor = rememberMurongChromeColor()
    val mutedTextColor = rememberMurongMutedTextColor()
    val results by produceState(
        initialValue = emptyList(),
        key1 = localQuery,
        key2 = query,
        key3 = projectKnowledgeMentions
    ) {
        value = mergeProjectKnowledgeMentions(
            projectKnowledgeMentions = projectKnowledgeMentions,
            searchResults = onSearch(localQuery),
            query = localQuery
        )
    }
    val knowledgeResults = remember(localQuery, results, knowledgePaths) {
        if (localQuery.isBlank()) emptyList() else results.filter { it.path in knowledgePaths }
    }
    val regularResults = remember(localQuery, results, knowledgePaths) {
        if (localQuery.isBlank()) results else results.filterNot { it.path in knowledgePaths }
    }
    MurongDialog(onDismissRequest = onDismiss) {
        MurongPopupSurface(
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "@文件",
                        style = MaterialTheme.typography.titleMedium
                    )
                    MurongOutlinedActionButton(text = "关闭", onClick = onDismiss)
                }
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = localQuery,
                        onValueChange = {
                            localQuery = it
                            onQueryChange(it)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("输入文件名或相对路径") }
                    )
                    if (recentMentions.isNotEmpty() && localQuery.isBlank()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "最近引用",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                recentMentions.forEach { mention ->
                                    InputChip(
                                        selected = false,
                                        onClick = { onSelect(mention) },
                                        label = {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                if (mention.path in knowledgePaths) {
                                                    MentionKnowledgeBadge(compact = true)
                                                }
                                                if (mention.path.startsWith("github://")) {
                                                    MentionRemoteBadge(compact = true)
                                                }
                                                snapshotNamesByPath[mention.path]
                                                    .orEmpty()
                                                    .take(1)
                                                    .forEach { snapshotName ->
                                                        MentionSnapshotBadge(label = snapshotName, compact = true)
                                                    }
                                                Text(
                                                    text = mention.displayPath,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                    if (results.isEmpty()) {
                        Text(
                            text = "当前没有匹配文件；本地项目会搜工作区，远端任务仓库会按文件名搜索 GitHub 文件。",
                            style = MaterialTheme.typography.bodySmall,
                            color = mutedTextColor
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (localQuery.isBlank()) {
                                results.take(8).forEach { mention ->
                                    MentionCandidateRow(
                                        mention = mention,
                                        query = localQuery,
                                        containerColor = surfaceColor.copy(alpha = 0.66f),
                                        isKnowledge = mention.path in knowledgePaths,
                                        knowledgeOutline = knowledgeOutlines[mention.path],
                                        snapshotNames = snapshotNamesByPath[mention.path].orEmpty(),
                                        onClick = { onSelect(mention) }
                                    )
                                }
                            } else {
                                MentionCandidateSection(
                                    title = "知识文件",
                                    results = knowledgeResults.take(8),
                                    query = localQuery,
                                    knowledgePaths = knowledgePaths,
                                    knowledgeOutlines = knowledgeOutlines,
                                    snapshotNamesByPath = snapshotNamesByPath,
                                    containerColor = chromeColor.copy(alpha = 0.52f),
                                    onSelect = onSelect
                                )
                                MentionCandidateSection(
                                    title = "其他文件",
                                    results = regularResults.take(8),
                                    query = localQuery,
                                    knowledgePaths = knowledgePaths,
                                    knowledgeOutlines = knowledgeOutlines,
                                    snapshotNamesByPath = snapshotNamesByPath,
                                    containerColor = surfaceColor.copy(alpha = 0.66f),
                                    onSelect = onSelect
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MentionCandidateSection(
    title: String,
    results: List<FileMentionUi>,
    query: String,
    knowledgePaths: Set<String>,
    knowledgeOutlines: Map<String, ProjectKnowledgeOutlineUi>,
    snapshotNamesByPath: Map<String, List<String>>,
    containerColor: Color,
    onSelect: (FileMentionUi) -> Unit
) {
    if (results.isEmpty()) return
    val description = remember(title) {
        when (title) {
            "知识文件" -> "来自当前项目知识挂载，会优先展示。"
            "其他文件" -> "来自项目搜索结果，按当前关键字匹配度排序。"
            else -> null
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
        description?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        results.forEach { mention ->
            MentionCandidateRow(
                mention = mention,
                query = query,
                containerColor = containerColor,
                isKnowledge = mention.path in knowledgePaths,
                knowledgeOutline = knowledgeOutlines[mention.path],
                snapshotNames = snapshotNamesByPath[mention.path].orEmpty(),
                onClick = { onSelect(mention) }
            )
        }
    }
}

@Composable
private fun MentionKnowledgeBadge(
    compact: Boolean = false
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    ) {
        Text(
            text = "知识",
            modifier = Modifier.padding(
                horizontal = if (compact) 4.dp else 6.dp,
                vertical = if (compact) 1.dp else 2.dp
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun MentionReasonBadge(
    label: String,
    compact: Boolean = false
) {
    val surfaceColor = rememberMurongSurfaceColor()
    val mutedTextColor = rememberMurongMutedTextColor()
    Surface(
        shape = MaterialTheme.shapes.small,
        color = surfaceColor.copy(alpha = 0.72f)
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(
                horizontal = if (compact) 4.dp else 6.dp,
                vertical = if (compact) 1.dp else 2.dp
            ),
            style = MaterialTheme.typography.labelSmall,
            color = mutedTextColor
        )
    }
}

@Composable
private fun MentionSnapshotBadge(
    label: String,
    compact: Boolean = false
) {
    val chromeColor = rememberMurongChromeColor()
    val mutedTextColor = rememberMurongMutedTextColor()
    Surface(
        shape = MaterialTheme.shapes.small,
        color = chromeColor.copy(alpha = 0.70f)
    ) {
        Text(
            text = "快照 $label",
            modifier = Modifier.padding(
                horizontal = if (compact) 4.dp else 6.dp,
                vertical = if (compact) 1.dp else 2.dp
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun MentionRemoteBadge(
    compact: Boolean = false
) {
    val chromeColor = rememberMurongChromeColor()
    Surface(
        shape = MaterialTheme.shapes.small,
        color = chromeColor.copy(alpha = 0.68f)
    ) {
        Text(
            text = "远端",
            modifier = Modifier.padding(
                horizontal = if (compact) 4.dp else 6.dp,
                vertical = if (compact) 1.dp else 2.dp
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun MentionCandidateRow(
    mention: FileMentionUi,
    query: String,
    containerColor: Color,
    isKnowledge: Boolean = false,
    knowledgeOutline: ProjectKnowledgeOutlineUi? = null,
    snapshotNames: List<String> = emptyList(),
    onClick: () -> Unit
) {
    val matchReason = remember(mention, query) {
        mentionMatchReasonLabel(mention, query)
    }
    val isRemoteMention = remember(mention.path) {
        mention.path.startsWith("github://")
    }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = containerColor,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            if (isKnowledge || matchReason != null || isRemoteMention) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (isKnowledge) {
                        MentionKnowledgeBadge()
                    }
                    if (isRemoteMention) {
                        MentionRemoteBadge()
                    }
                    snapshotNames.take(2).forEach { snapshotName ->
                        MentionSnapshotBadge(label = snapshotName)
                    }
                    matchReason?.let { MentionReasonBadge(label = it) }
                }
            }
            Text(
                text = highlightMentionText(
                    text = mentionPrimaryTitle(mention.displayPath),
                    query = query,
                    highlightColor = MaterialTheme.colorScheme.primary,
                    highlightWeight = FontWeight.SemiBold
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            mentionSecondaryPath(mention.displayPath)?.let { secondary ->
                Text(
                    text = highlightMentionText(
                        text = secondary,
                        query = query,
                        highlightColor = MaterialTheme.colorScheme.primary,
                        highlightWeight = FontWeight.Medium
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            knowledgeOutline?.let { outline ->
                Text(
                    text = outline.summary,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (snapshotNames.isNotEmpty()) {
                Text(
                    text = "快照来源：${snapshotNames.joinToString(" / ")}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private fun buildProjectKnowledgeSnapshotNamesByPath(
    snapshots: List<ProjectKnowledgeSnapshotUi>
): Map<String, List<String>> {
    return buildMap {
        snapshots.forEach { snapshot ->
            snapshot.paths.forEach { path ->
                val current = get(path).orEmpty()
                put(path, (current + snapshot.name).distinct())
            }
        }
    }
}

@Composable
private fun WebFetchResultCard(
    result: WebFetchResultUi,
    isScreenActive: Boolean,
    stateKey: String
) {
    val canExpand = result.excerpt.isNotBlank() || result.content.isNotBlank()
    var expanded by rememberSaveable(stateKey) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ToolCardHeader(
            toolName = result.title.ifBlank { "网页抓取" },
            statusText = "已抓取",
            statusColor = MaterialTheme.colorScheme.primary,
            summary = truncateInlineText(
                result.excerpt.ifBlank {
                    "正文长度 ${result.totalChars} 字" +
                        if (result.truncated) "，当前展示为截断内容" else ""
                }
            ),
            expanded = expanded,
            expandable = canExpand,
            quiet = true,
            onToggle = { if (canExpand) expanded = !expanded }
        )
        SelectionContainer {
            Text(
                text = result.url,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        AnimatedVisibility(visible = expanded && isScreenActive) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (result.excerpt.isNotBlank()) {
                    DetailBlock("摘要", result.excerpt)
                }
                if (result.content.isNotBlank()) {
                    DetailBlock(
                        label = if (result.truncated) "正文提取（已截断）" else "正文提取",
                        value = result.content
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageImageAttachmentGallery(
    attachments: List<MessageImageAttachmentUi>,
    onOpenPreview: (List<ImagePreviewItemUi>, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val previewItems = remember(attachments) {
            attachments.map { attachment ->
                ImagePreviewItemUi(
                    title = attachment.fileName,
                    localCachePath = attachment.localCachePath,
                    mimeType = attachment.mimeType
                )
            }
        }
        attachments.forEachIndexed { index, attachment ->
            val bitmap = remember(attachment.localCachePath) {
                BitmapFactory.decodeFile(attachment.localCachePath)
            }
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.3f),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenPreview(previewItems, index) }
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    bitmap?.let {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = attachment.fileName,
                            contentScale = ContentScale.FillWidth,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 220.dp)
                                .clip(RoundedCornerShape(10.dp))
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                    Text(
                        text = attachment.fileName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val meta = buildList {
                        attachment.width?.let { width ->
                            attachment.height?.let { height ->
                                add("${width}x$height")
                            }
                        }
                        attachment.sizeBytes?.let { add(formatAttachmentSize(it)) }
                    }.joinToString(" · ")
                    if (meta.isNotBlank()) {
                        Text(
                            text = meta,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(
    msg: ChatMessageUi,
    isScreenActive: Boolean = true,
    previousMessage: ChatMessageUi? = null,
    nextMessage: ChatMessageUi? = null,
    subagentRun: SubagentRunUi? = null,
    subagentBatch: SubagentBatchUi? = null,
    onLongPress: () -> Unit = {},
    onApplyPrompt: (String) -> Unit = {},
    onOpenImagePreview: (List<ImagePreviewItemUi>, Int) -> Unit = { _, _ -> },
    onClick: () -> Unit = {}
) {
    val isUser = msg.role == "user"
    val isToolExec = msg.role == "tool_exec"
    val isSubagent = msg.role == "subagent"
    val isSystem = msg.role == "system"
    val accent = rememberMurongAccentColor()
    val surfaceColor = rememberMurongSurfaceColor()
    val chromeColor = rememberMurongChromeColor()
    val mutedTextColor = rememberMurongMutedTextColor()
    val userBubbleColor = lerp(surfaceColor, accent, 0.18f).copy(alpha = 0.82f)
    val assistantBubbleColor = surfaceColor.copy(alpha = 0.72f)
    val toolBubbleColor = chromeColor.copy(alpha = 0.74f)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        if (isUser) {
            MurongGlassSurface(
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .combinedClickable(
                        onClick = {},
                        onLongClick = onLongPress
                    ),
                shape = RoundedCornerShape(
                    topStart = 20.dp,
                    topEnd = 8.dp,
                    bottomStart = 20.dp,
                    bottomEnd = 20.dp
                ),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                surfaceColorOverride = userBubbleColor
            ) {
                Text(
                    text = "你",
                    style = MaterialTheme.typography.labelMedium,
                    color = accent
                )
                if (msg.imageAttachments.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    MessageImageAttachmentGallery(
                        attachments = msg.imageAttachments,
                        onOpenPreview = onOpenImagePreview
                    )
                    if (msg.content.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
                if (msg.content.isNotBlank()) {
                    if (shouldUseNativeScrollableSelectableText(msg.content)) {
                        NativeSelectableScrollableText(
                            text = msg.content,
                            modifier = Modifier.fillMaxWidth(),
                            fontSizeSp = 14f,
                            maxHeight = 280.dp
                        )
                    } else {
                        SelectionContainer {
                            Text(
                                text = msg.content,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }
        } else if (isToolExec) {
            val previousToolExecution = remember(previousMessage?.content) {
                previousMessage
                    ?.takeIf { it.role == "tool_exec" }
                    ?.let { parseToolExecutionMessage(it.content) }
            }
            val toolExecution = remember(msg.content) {
                parseToolExecutionMessage(msg.content)
            }
            val toolResult = remember(msg.content, previousToolExecution) {
                parseToolResultMessage(
                    content = msg.content,
                    previousExecution = previousToolExecution
                )
            }
            val webFetchResult = remember(msg.content) {
                parseWebFetchToolMessage(msg.content)
            }
            val quietToolCard = toolExecution?.isQuiet == true ||
                toolResult?.isQuiet == true ||
                webFetchResult != null
            val messageStateKey = remember(msg.id, msg.content) {
                msg.id.toString()
            }
            MurongGlassSurface(
                modifier = Modifier
                    .fillMaxWidth()
                    .let { baseModifier ->
                        if (quietToolCard || toolResult?.renderResultAsCode == true) {
                            baseModifier
                        } else {
                            baseModifier.combinedClickable(
                                onClick = {},
                                onLongClick = onLongPress
                            )
                        }
                    },
                shape = if (quietToolCard) RoundedCornerShape(16.dp) else MaterialTheme.shapes.large,
                contentPadding = PaddingValues(
                    horizontal = 12.dp,
                    vertical = if (quietToolCard) 10.dp else 12.dp
                ),
                surfaceColorOverride = toolBubbleColor
            ) {
                Text(
                    text = if (quietToolCard) "工具结果" else "工具输出",
                    style = MaterialTheme.typography.labelMedium,
                    color = accent
                )
                Spacer(modifier = Modifier.height(6.dp))
                if (toolExecution != null) {
                    ToolExecutionCard(
                        tool = toolExecution,
                        isScreenActive = isScreenActive,
                        stateKey = "exec:$messageStateKey"
                    )
                } else if (toolResult != null) {
                    ToolResultCard(
                        tool = toolResult,
                        isScreenActive = isScreenActive,
                        stateKey = "result:$messageStateKey"
                    )
                } else if (webFetchResult != null) {
                    WebFetchResultCard(
                        result = webFetchResult,
                        isScreenActive = isScreenActive,
                        stateKey = "web:$messageStateKey"
                    )
                } else {
                    SelectableMarkdownContent(
                        text = msg.content,
                        fontSize = 12,
                        maxHeight = 320.dp
                    )
                }
            }
        } else if (isSubagent) {
            if (subagentRun != null) {
                SubagentCard(
                    run = subagentRun,
                    onClick = onClick,
                    onLongPress = onLongPress
                )
            } else if (subagentBatch != null) {
                SubagentBatchCard(
                    batch = subagentBatch,
                    onClick = onClick,
                    onLongPress = onLongPress
                )
            } else {
                MurongGlassSurface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = onClick,
                            onLongClick = onLongPress
                        ),
                    shape = MaterialTheme.shapes.large,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    surfaceColorOverride = toolBubbleColor
                ) {
                    Text(
                        text = "子代理",
                        style = MaterialTheme.typography.labelMedium,
                        color = accent
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    SelectableMarkdownContent(
                        text = msg.content,
                        fontSize = 13,
                        maxHeight = 320.dp
                    )
                }
            }
        } else if (isSystem) {
            MurongGlassSurface(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = {},
                        onLongClick = onLongPress
                    ),
                shape = MaterialTheme.shapes.large,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                surfaceColorOverride = chromeColor.copy(alpha = 0.70f)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "系统状态",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    if (shouldUseNativeScrollableSelectableText(msg.content)) {
                        NativeSelectableScrollableText(
                            text = msg.content,
                            modifier = Modifier.fillMaxWidth(),
                            fontSizeSp = 12f,
                            maxHeight = 240.dp
                        )
                    } else {
                        SelectionContainer {
                            Text(
                                text = msg.content,
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        } else {
            val multimodalAnalysis = remember(msg.content) {
                parseMultimodalAssistantMessage(msg.content)
            }
            val reasoningContent = msg.reasoning
            val nextIsToolExec = nextMessage?.role == "tool_exec"
            val shouldShowAssistantBubble =
                msg.content.isNotBlank() ||
                    (!msg.isStreaming && reasoningContent.isNullOrBlank() && !nextIsToolExec)
            Column(
                modifier = Modifier
                    .widthIn(max = 400.dp)
                    .combinedClickable(
                        onClick = {},
                        onLongClick = onLongPress
                    )
            ) {
                var expanded by remember(msg.id) { mutableStateOf(msg.isStreaming) }
                LaunchedEffect(msg.id, msg.isStreaming, reasoningContent) {
                    if (msg.isStreaming && !reasoningContent.isNullOrBlank()) {
                        expanded = true
                    }
                }

                if (reasoningContent != null && reasoningContent.isNotBlank()) {
                    ReasoningCard(
                        content = reasoningContent,
                        expanded = expanded,
                        isScreenActive = isScreenActive,
                        isStreaming = msg.isStreaming,
                        onToggle = { expanded = !expanded }
                    )
                }

                if (shouldShowAssistantBubble) {
                    if (reasoningContent != null && reasoningContent.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    MurongGlassSurface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                        surfaceColorOverride = assistantBubbleColor
                    ) {
                        Text(
                            text = "助手",
                            style = MaterialTheme.typography.labelMedium,
                            color = accent
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        if (msg.content.isNotBlank()) {
                            if (multimodalAnalysis != null) {
                                MultimodalAnalysisCard(
                                    analysis = multimodalAnalysis,
                                    onApplyPrompt = onApplyPrompt
                                )
                                multimodalAnalysis.detail.takeIf { it.isNotBlank() }?.let { detail ->
                                    Spacer(modifier = Modifier.height(8.dp))
                                    SelectionContainer {
                                        MarkdownText(
                                            text = detail,
                                            modifier = Modifier.fillMaxWidth(),
                                            fontSize = 14
                                        )
                                    }
                                }
                            } else {
                                SelectionContainer {
                                    MarkdownText(
                                        text = msg.content,
                                        modifier = Modifier.fillMaxWidth(),
                                        fontSize = 14
                                    )
                                }
                            }
                            if (msg.isStreaming) {
                                Text(
                                    text = "思考中…",
                                    color = mutedTextColor,
                                    fontSize = 14.sp
                                )
                            }
                        } else if (!msg.isStreaming && reasoningContent.isNullOrBlank()) {
                            Text(
                                text = "(空回复)",
                                color = mutedTextColor,
                                fontSize = 14.sp
                            )
                        }
                    }
                }

                if (msg.isStreaming) {
                    Text(
                        text = "▊",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(start = 8.dp, top = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun MultimodalAnalysisCard(
    analysis: MultimodalAnalysisUi,
    onApplyPrompt: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        MultimodalSection(
            title = "图片摘要",
            body = analysis.summary
        )
        MultimodalSection(
            title = "OCR 文本",
            body = analysis.ocrText,
            actionLabel = "引用 OCR",
            onAction = {
                onApplyPrompt(
                    buildString {
                        appendLine("基于这段 OCR 文本继续分析：")
                        appendLine()
                        appendLine(analysis.ocrText)
                    }.trim()
                )
            }
        )
        MultimodalSection(
            title = "风险提示",
            body = if (analysis.risks.isEmpty()) {
                "暂无明显风险"
            } else {
                analysis.risks.joinToString("\n") { "- $it" }
            }
        )
        if (analysis.followUps.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "继续追问",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    analysis.followUps.forEach { prompt ->
                        AssistChip(
                            onClick = { onApplyPrompt(prompt) },
                            label = {
                                Text(
                                    text = prompt,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MultimodalSection(
    title: String,
    body: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    val useNativeScrollableText = remember(body) {
        shouldUseNativeScrollableSelectableText(body)
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction, contentPadding = PaddingValues(0.dp)) {
                    Text(actionLabel)
                }
            }
        }
        if (useNativeScrollableText) {
            NativeSelectableScrollableText(
                text = body.ifBlank { "-" },
                modifier = Modifier.fillMaxWidth(),
                fontSizeSp = 12f,
                maxHeight = 280.dp
            )
        } else {
            SelectionContainer {
                Text(
                    text = body.ifBlank { "-" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    lineHeight = 20.sp
                )
            }
        }
    }
}

@Composable
private fun SubagentCard(
    run: SubagentRunUi,
    onClick: () -> Unit,
    onLongPress: () -> Unit
) {
    val chromeColor = rememberMurongChromeColor()
    val mutedTextColor = rememberMurongMutedTextColor()
    Surface(
        shape = MaterialTheme.shapes.large,
        color = chromeColor.copy(alpha = 0.70f),
        tonalElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongPress
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "子代理",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
                Text(
                    text = subagentStatusLabel(run.status),
                    style = MaterialTheme.typography.labelSmall,
                    color = subagentStatusColor(run.status)
                )
            }
            Text(
                text = run.goal,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "模型 ${run.model} · 推理 ${run.reasoningEffort ?: "默认"}",
                style = MaterialTheme.typography.bodySmall,
                color = mutedTextColor
            )
            run.templateTitle?.takeIf { it.isNotBlank() }?.let { templateTitle ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "模板 $templateTitle",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        fontWeight = FontWeight.Medium
                    )
                    if (run.templateId != null) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "#${run.templateId}",
                            style = MaterialTheme.typography.labelSmall,
                            color = mutedTextColor.copy(alpha = 0.72f)
                        )
                    }
                }
            }
            run.batchLabel?.takeIf { it.isNotBlank() }?.let { batchLabel ->
                Text(
                    text = buildString {
                        append("编排 ")
                        append(batchLabel)
                        run.batchIndex?.let { index ->
                            append(" · 子任务 ")
                            append(index)
                            run.batchSize?.let { size ->
                                append("/")
                                append(size)
                            }
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
            if (run.statusMessage.isNotBlank()) {
                Text(
                    text = run.statusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedTextColor
                )
            }
            if (run.queuePosition != null || run.assignedSlot != null) {
                Text(
                    text = buildString {
                        run.queuePosition?.let {
                            append("队列第 $it 位")
                        }
                        run.assignedSlot?.let { slot ->
                            if (isNotEmpty()) append(" · ")
                            append(
                                run.concurrencyLimit?.let { limit -> "槽位 $slot/$limit" }
                                    ?: "槽位 $slot"
                            )
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
            if (run.retryCount > 0) {
                Text(
                    text = "重试次数 ${run.retryCount}" +
                        (run.sourceRunId?.let { " · 来源 $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
            if (run.summary.isNotBlank()) {
                Text(
                    text = run.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis
                )
            }
            run.error?.takeIf { it.isNotBlank() }?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = "工具 ${run.allowedTools.joinToString(", ").ifBlank { "无" }}",
                style = MaterialTheme.typography.bodySmall,
                color = mutedTextColor
            )
            if (run.usageSummary.totalTokens > 0) {
                Text(
                    text = "Token ${run.usageSummary.totalTokens} · 预估 ${
                        formatCurrencyAmount(
                            run.usageSummary.resolvedEstimatedCostAmount(),
                            run.usageSummary.resolvedEstimatedCostCurrency()
                        )
                    }",
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedTextColor
                )
            }
            Text(
                text = "点击查看详情",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary
            )
        }
    }
}

@Composable
private fun SubagentBatchCard(
    batch: SubagentBatchUi,
    onClick: () -> Unit,
    onLongPress: () -> Unit
) {
    val chromeColor = rememberMurongChromeColor()
    val mutedTextColor = rememberMurongMutedTextColor()
    Surface(
        shape = MaterialTheme.shapes.large,
        color = chromeColor.copy(alpha = 0.64f),
        tonalElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongPress
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "子代理编排",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
                Text(
                    text = subagentBatchStatusLabel(batch.status),
                    style = MaterialTheme.typography.labelSmall,
                    color = subagentBatchStatusColor(batch.status)
                )
            }
            Text(
                text = batch.label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = batch.parentGoal,
                style = MaterialTheme.typography.bodySmall,
                color = mutedTextColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = buildString {
                    append("子任务 ${batch.runIds.size}")
                    batch.queuePosition?.let { append(" · 队列第 $it 位") }
                    if (batch.activeSlots.isNotEmpty()) {
                        append(" · 槽位 ")
                        append(
                            batch.activeSlots.joinToString(", ") { slot ->
                                batch.concurrencyLimit?.let { limit -> "$slot/$limit" } ?: slot.toString()
                            }
                        )
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )
            if (batch.statusMessage.isNotBlank()) {
                Text(
                    text = batch.statusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedTextColor
                )
            }
            buildBatchSchedulingMetricLine(batch)?.let { metricLine ->
                Text(
                    text = "调度指标: $metricLine",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
            batch.timeline.takeLast(2).forEach { entry ->
                Text(
                    text = "${formatTimestamp(entry.timestamp)} · ${entry.title}",
                    style = MaterialTheme.typography.labelSmall,
                    color = mutedTextColor
                )
            }
            Text(
                text = "点击查看批次时间线",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary
            )
        }
    }
}

@Composable
private fun SubagentDetailSheet(
    run: SubagentRunUi,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit
) {
    val surfaceColor = rememberMurongSurfaceColor()
    val mutedTextColor = rememberMurongMutedTextColor()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── 标题栏 + 状态 ──
            Text(
                text = "子代理详情",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            // ── 阶段时间线（核心视觉） ──
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = surfaceColor.copy(alpha = 0.58f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "执行阶段",
                        style = MaterialTheme.typography.labelSmall,
                        color = mutedTextColor,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    SubagentRunStageTimeline(run = run)
                }
            }

            // ── 基本信息 ──
            SectionHeader("基本信息")
            DetailRow("状态", subagentStatusLabel(run.status))
            DetailRow("目标", run.goal)
            DetailRow("模型", run.model)
            run.templateTitle?.takeIf { it.isNotBlank() }?.let { templateTitle ->
                DetailRow(
                    "匹配模板",
                    run.templateId?.let { "$templateTitle (#$it)" } ?: templateTitle
                )
            }
            DetailRow("推理深度", run.reasoningEffort ?: "默认")
            DetailRow("允许工具", run.allowedTools.joinToString(", ").ifBlank { "无" })
            if (run.statusMessage.isNotBlank()) {
                DetailRow("状态说明", run.statusMessage)
            }
            if (run.retryCount > 0) {
                DetailRow("重试次数", run.retryCount.toString())
            }
            run.sourceRunId?.takeIf { it.isNotBlank() }?.let {
                DetailRow("来源任务", it)
            }

            // ── 批次 / 调度信息 ──
            if (run.batchLabel != null || run.queuePosition != null || run.assignedSlot != null) {
                SectionHeader("调度信息")
                run.batchLabel?.takeIf { it.isNotBlank() }?.let {
                    DetailRow("编排批次", it)
                }
                run.batchIndex?.let { index ->
                    DetailRow(
                        "批次序号",
                        run.batchSize?.let { size -> "$index/$size" } ?: index.toString()
                    )
                }
                run.queuePosition?.let {
                    DetailRow("队列位置", "第 $it 位")
                }
                run.assignedSlot?.let { slot ->
                    DetailRow(
                        "并发槽位",
                        run.concurrencyLimit?.let { limit -> "$slot/$limit" } ?: slot.toString()
                    )
                }
            }

            // ── 时间轴 ──
            SectionHeader("时间轴")
            DetailRow("开始时间", formatTimestamp(run.startedAt))
            DetailRow("结束时间", run.finishedAt?.let(::formatTimestamp) ?: "运行中")
            buildRunSchedulingMetrics(run).forEach { (label, value) ->
                DetailRow(label, value)
            }

            // ── 操作按钮 ──
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (canCancelSubagent(run.status)) {
                    FilledTonalButton(onClick = onCancel) {
                        Text("终止运行")
                    }
                }
                if (canRetrySubagent(run.status)) {
                    FilledTonalButton(onClick = onRetry) {
                        Text(if (run.retryCount > 0) "再次重试" else "重新运行")
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("关闭")
                }
            }

            // ── 摘要 ──
            if (run.summary.isNotBlank()) {
                DetailBlock("摘要", run.summary)
            }

            // ── 错误 ──
            run.error?.takeIf { it.isNotBlank() }?.let { error ->
                DetailBlock("错误", error)
            }

            // ── 消耗 ──
            DetailBlock(
                "消耗",
                "总 Token ${run.usageSummary.totalTokens}\n输入 ${run.usageSummary.promptTokens} · 输出 ${run.usageSummary.completionTokens}\n预估成本 ${
                    formatCurrencyAmount(
                        run.usageSummary.resolvedEstimatedCostAmount(),
                        run.usageSummary.resolvedEstimatedCostCurrency()
                    )
                }"
            )
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp)
    )
}

@Composable
private fun SubagentBatchDetailSheet(
    batch: SubagentBatchUi,
    onDismiss: () -> Unit
) {
    val surfaceColor = rememberMurongSurfaceColor()
    val mutedTextColor = rememberMurongMutedTextColor()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "子代理编排详情",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            // ── 批次时间线 ──
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = surfaceColor.copy(alpha = 0.58f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "事件时间线",
                        style = MaterialTheme.typography.labelSmall,
                        color = mutedTextColor,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    SubagentBatchTimeline(timeline = batch.timeline)
                }
            }

            // ── 基本信息 ──
            SectionHeader("基本信息")
            DetailRow("状态", subagentBatchStatusLabel(batch.status))
            DetailRow("标签", batch.label)
            DetailRow("主目标", batch.parentGoal)
            if (batch.splitStrategyLabel.isNotBlank()) {
                DetailRow("拆分策略", batch.splitStrategyLabel)
            }
            DetailRow("子任务数", batch.runIds.size.toString())
            if (batch.statusMessage.isNotBlank()) {
                DetailRow("状态说明", batch.statusMessage)
            }

            // ── 调度信息 ──
            if (batch.queuePosition != null || batch.activeSlots.isNotEmpty()) {
                SectionHeader("调度信息")
                batch.queuePosition?.let {
                    DetailRow("最前队列位置", "第 $it 位")
                }
                if (batch.activeSlots.isNotEmpty()) {
                    DetailRow(
                        "并发槽位",
                        batch.activeSlots.joinToString(", ") { slot ->
                            batch.concurrencyLimit?.let { limit -> "$slot/$limit" } ?: slot.toString()
                        }
                    )
                }
            }

            // ── 时间轴 ──
            SectionHeader("时间轴")
            DetailRow("开始时间", formatTimestamp(batch.startedAt))
            DetailRow("结束时间", batch.finishedAt?.let(::formatTimestamp) ?: "运行中")
            buildBatchSchedulingMetrics(batch).forEach { (label, value) ->
                DetailRow(label, value)
            }

            // ── 摘要 ──
            if (batch.summary.isNotBlank()) {
                DetailBlock("批次摘要", batch.summary)
            }

            // ── 关闭按钮 ──
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun EditHintBar(onCancel: () -> Unit) {
    val chromeColor = rememberMurongChromeColor()
    Surface(
        color = chromeColor.copy(alpha = 0.68f),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "正在重新编辑历史消息，发送后会从该节点继续。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onCancel) {
                Text("取消")
            }
        }
    }
}

@Composable
private fun MessageActionSheet(
    msg: ChatMessageUi,
    onDismiss: () -> Unit,
    onCopyMessage: () -> Unit,
    onCopyRound: () -> Unit,
    onEditMessage: (() -> Unit)? = null
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(
                text = "消息操作",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(12.dp))
            SheetActionItem(label = "复制消息", onClick = onCopyMessage)
            SheetActionItem(label = "复制本轮", onClick = onCopyRound)
            if (onEditMessage != null) {
                SheetActionItem(
                    label = "重新编辑这条消息",
                    onClick = onEditMessage
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SheetActionItem(
    label: String,
    onClick: () -> Unit
) {
    val surfaceColor = rememberMurongSurfaceColor()
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = surfaceColor.copy(alpha = 0.68f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 16.dp)
        )
    }
}

private fun buildGoalModeMessage(goal: String): String {
    val normalizedGoal = goal.trim()
    return """
        当前目标：
        $normalizedGoal

        请围绕这个目标继续推进；如果目标还不够清晰，请先提一个必要的澄清问题，不要偏离这个目标。
    """.trimIndent()
}

private fun buildGoalPlanModeInput(goal: String): String {
    val normalizedGoal = goal.trim()
    return """
        当前目标：
        $normalizedGoal

        请先围绕这个目标生成一份简短、可执行的计划。
    """.trimIndent()
}

@Composable
private fun SelectedSkillsBar(
    skills: List<GlobalSkill>,
    onRemove: (GlobalSkill) -> Unit
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        skills.forEach { skill ->
            FilterChip(
                selected = true,
                onClick = { onRemove(skill) },
                label = {
                    Text(
                        "Skill: ${skill.title.ifBlank { "未命名" }}",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                trailingIcon = {
                    Text("移除", fontSize = 10.sp)
                }
            )
        }
    }
}

@Composable
private fun SkillPickerDialog(
    skills: List<GlobalSkill>,
    selectedSkills: List<GlobalSkill>,
    onDismiss: () -> Unit,
    onToggleSkill: (GlobalSkill) -> Unit,
    onClear: () -> Unit
) {
    val selectedKeys = remember(selectedSkills) {
        selectedSkills.map { it.title.trim() to it.content.trim() }.toSet()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("完成")
            }
        },
        dismissButton = {
            TextButton(onClick = onClear, enabled = selectedSkills.isNotEmpty()) {
                Text("清空")
            }
        },
        title = {
            Text("选择 Skills")
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (skills.isEmpty()) {
                    Text(
                        text = "当前没有可选 Skills。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    skills.forEach { skill ->
                        val isSelected = (skill.title.trim() to skill.content.trim()) in selectedKeys
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onToggleSkill(skill) }
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = skill.title.ifBlank { "未命名 Skill" },
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    Checkbox(
                                        checked = isSelected,
                                        onCheckedChange = { onToggleSkill(skill) }
                                    )
                                }
                                skill.description.takeIf { it.isNotBlank() }?.let {
                                    Text(
                                        text = it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    text = if (skill.runAs.name == "SUBAGENT") "子代理执行" else "行内执行",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }
    )
}

@Composable
private fun InputBar(
    text: String,
    planModeEnabled: Boolean,
    goalModeEnabled: Boolean,
    hasSessionGoal: Boolean,
    onTextChange: (String) -> Unit,
    onInputFocusChanged: (Boolean) -> Unit,
    currentApprovalModeLabel: String,
    onPlanModeChange: (Boolean) -> Unit,
    onGoalModeChange: (Boolean) -> Unit,
    onOpenApprovalMode: () -> Unit,
    onSend: () -> Unit,
    onPreviousInput: () -> Unit,
    onNextInput: () -> Unit,
    onCaptureImage: () -> Unit,
    onPickImages: () -> Unit,
    onMention: () -> Unit,
    availableSkills: List<GlobalSkill>,
    selectedSkillCount: Int,
    onOpenSkillPicker: () -> Unit,
    canRecallPrevious: Boolean,
    canRecallNext: Boolean,
    canSend: Boolean,
    allowStructuredActions: Boolean,
    canUseMultimodal: Boolean,
    hasPendingImages: Boolean,
    enabled: Boolean,
    isSending: Boolean,
    bottomReservedPadding: Dp,
    onStopSending: () -> Unit
) {
    var showMoreActions by remember { mutableStateOf(false) }
    var inputBarHeightPx by remember { mutableIntStateOf(0) }
    var textFieldHeightPx by remember { mutableIntStateOf(0) }
    var actionRowHeightPx by remember { mutableIntStateOf(0) }
    var textFieldFocused by remember { mutableStateOf(false) }
    val actionsEnabled = enabled && !isSending
    val accent = rememberMurongAccentColor()
    val surfaceColor = rememberMurongSurfaceColor()
    val mutedTextColor = rememberMurongMutedTextColor()
    val density = LocalDensity.current
    val imeBottomInsetPx = WindowInsets.ime.getBottom(density)
    val imeVisible = imeBottomInsetPx > 0
    val baseBottomGapPx = with(density) { (10.dp + bottomReservedPadding).toPx() }
    val appliedBottomGap = with(density) { max(imeBottomInsetPx.toFloat(), baseBottomGapPx).toDp() }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = 12.dp,
                top = if (imeVisible) 6.dp else 10.dp,
                end = 12.dp,
                bottom = appliedBottomGap
            )
    ) {
        MurongGlassSurface(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { inputBarHeightPx = it.height },
            shape = MaterialTheme.shapes.extraLarge,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            surfaceColorOverride = surfaceColor.copy(alpha = 0.78f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onSizeChanged { textFieldHeightPx = it.height }
                        .onFocusChanged { focusState ->
                            textFieldFocused = focusState.isFocused
                            onInputFocusChanged(focusState.isFocused)
                        }
                        .onPreviewKeyEvent { keyEvent ->
                            if (keyEvent.type == KeyEventType.KeyDown &&
                                keyEvent.key == Key.Enter &&
                                (keyEvent.isCtrlPressed || keyEvent.isMetaPressed)
                            ) {
                                when {
                                    isSending -> onStopSending()
                                    enabled && canSend -> onSend()
                                }
                                true
                            } else {
                                false
                            }
                        },
                    placeholder = {
                        Text(
                            composerPlaceholder(
                                planModeEnabled = planModeEnabled,
                                goalModeEnabled = goalModeEnabled,
                                hasSessionGoal = hasSessionGoal
                            ),
                            color = mutedTextColor
                        )
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                        cursorColor = MaterialTheme.colorScheme.primary,
                        focusedContainerColor = surfaceColor.copy(alpha = 0.34f),
                        unfocusedContainerColor = surfaceColor.copy(alpha = 0.22f),
                        disabledContainerColor = surfaceColor.copy(alpha = 0.14f)
                    ),
                    shape = MaterialTheme.shapes.large,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                    supportingText = {
                        if (textFieldFocused) {
                            Text(
                                "回车换行，发送按钮发送；硬件键盘 Ctrl+Enter 发送",
                                style = MaterialTheme.typography.bodySmall,
                                color = mutedTextColor
                            )
                        }
                    },
                    singleLine = false,
                    maxLines = 4,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    enabled = enabled
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .onSizeChanged { actionRowHeightPx = it.height },
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box {
                        MurongTagButton(
                            text = if (text.isBlank()) "更多" else "操作",
                            onClick = {
                                if (actionsEnabled) {
                                    showMoreActions = true
                                }
                            }
                        )
                        DropdownMenu(
                            expanded = showMoreActions,
                            onDismissRequest = { showMoreActions = false }
                        ) {
                            if (canUseMultimodal) {
                                DropdownMenuItem(
                                    text = { Text("拍照") },
                                    onClick = {
                                        showMoreActions = false
                                        onCaptureImage()
                                    },
                                    enabled = actionsEnabled
                                )
                                DropdownMenuItem(
                                    text = { Text("选图") },
                                    onClick = {
                                        showMoreActions = false
                                        onPickImages()
                                    },
                                    enabled = actionsEnabled
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("@文件") },
                                onClick = {
                                    showMoreActions = false
                                    onMention()
                                },
                                enabled = actionsEnabled
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (selectedSkillCount > 0) {
                                            "Skills ($selectedSkillCount)"
                                        } else {
                                            "选择 Skills"
                                        }
                                    )
                                },
                                onClick = {
                                    showMoreActions = false
                                    onOpenSkillPicker()
                                },
                                enabled = actionsEnabled && availableSkills.isNotEmpty()
                            )
                            DropdownMenuItem(
                                text = { Text(currentApprovalModeLabel) },
                                onClick = {
                                    showMoreActions = false
                                    onOpenApprovalMode()
                                },
                                enabled = enabled
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(if (planModeEnabled) "计划: 开" else "计划: 关")
                                },
                                onClick = {
                                    onPlanModeChange(!planModeEnabled)
                                },
                                enabled = actionsEnabled && allowStructuredActions
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (goalModeEnabled) {
                                            if (hasSessionGoal) "更新目标: 开" else "设置目标: 开"
                                        } else {
                                            if (hasSessionGoal) "更新目标: 关" else "设置目标: 关"
                                        }
                                    )
                                },
                                onClick = {
                                    onGoalModeChange(!goalModeEnabled)
                                },
                                enabled = actionsEnabled && allowStructuredActions
                            )
                            if (hasPendingImages && (planModeEnabled || goalModeEnabled)) {
                                DropdownMenuItem(
                                    text = { Text("当前模式下图片不会发送") },
                                    onClick = { showMoreActions = false },
                                    enabled = false
                                )
                            }
                        }
                    }
                    MurongTagButton(
                        text = "上一条",
                        onClick = {
                            if (actionsEnabled && canRecallPrevious) {
                                onPreviousInput()
                            }
                        }
                    )
                    MurongTagButton(
                        text = "下一条",
                        onClick = {
                            if (actionsEnabled && canRecallNext) {
                                onNextInput()
                            }
                        }
                    )
                    Button(
                        onClick = {
                            if (isSending) {
                                onStopSending()
                            } else {
                                onSend()
                            }
                        },
                        enabled = if (isSending) true else enabled && canSend,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isSending) {
                                MaterialTheme.colorScheme.errorContainer
                            } else {
                                accent
                            },
                            contentColor = if (isSending) {
                                MaterialTheme.colorScheme.onErrorContainer
                            } else {
                                MaterialTheme.colorScheme.onPrimary
                            }
                        )
                    ) {
                        Text(
                            text = if (isSending) "终止" else composerSubmitLabel(
                                planModeEnabled = planModeEnabled,
                                goalModeEnabled = goalModeEnabled,
                                hasSessionGoal = hasSessionGoal
                            )
                        )
                    }
                }
            }
        }
    }
}

private fun composerPlaceholder(
    planModeEnabled: Boolean,
    goalModeEnabled: Boolean,
    hasSessionGoal: Boolean
): String {
    return when {
        planModeEnabled && goalModeEnabled -> "描述当前目标，先给计划并按目标推进…"
        planModeEnabled -> "描述你要达成的目标，先生成计划…"
        goalModeEnabled && hasSessionGoal -> "输入新的会话目标，发送后将覆盖当前目标…"
        goalModeEnabled -> "描述当前目标，后续回复会围绕它推进…"
        else -> "输入你的问题…"
    }
}

private fun composerSubmitLabel(
    planModeEnabled: Boolean,
    goalModeEnabled: Boolean,
    hasSessionGoal: Boolean
): String {
    return when {
        planModeEnabled && goalModeEnabled -> "目标+计划"
        planModeEnabled -> "计划"
        goalModeEnabled && hasSessionGoal -> "更新目标"
        goalModeEnabled -> "设置目标"
        else -> "发送"
    }
}

@Composable
private fun PendingImageAttachmentsBar(
    attachments: List<PendingImageAttachmentUi>,
    onOpenPreview: (List<PendingImageAttachmentUi>, Int) -> Unit,
    onRemove: (PendingImageAttachmentUi) -> Unit
) {
    val accent = rememberMurongAccentColor()
    MurongGlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.large,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "待发送图片 ${attachments.size} 张",
                style = MaterialTheme.typography.labelMedium,
                color = accent
            )
            attachments.forEachIndexed { index, attachment ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = attachment.fileName.ifBlank { "未命名图片" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onOpenPreview(attachments, index) },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    MurongTagButton(text = "预览", onClick = { onOpenPreview(attachments, index) })
                    MurongTagButton(text = "移除", onClick = { onRemove(attachment) })
                }
            }
        }
    }
}

@Composable
private fun RecentImageAttachmentsBar(
    attachments: List<MessageImageAttachmentUi>,
    selectedUris: Set<String>,
    onReuse: (MessageImageAttachmentUi) -> Unit,
    onOpenPreview: (List<MessageImageAttachmentUi>, Int) -> Unit
) {
    val accent = rememberMurongAccentColor()
    MurongGlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.large,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "最近图片",
                style = MaterialTheme.typography.labelMedium,
                color = accent
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                attachments.forEachIndexed { index, attachment ->
                    val pendingUri = Uri.fromFile(File(attachment.localCachePath)).toString()
                    AssistChip(
                        onClick = { onReuse(attachment) },
                        label = {
                            Text(
                                text = attachment.fileName.ifBlank { "最近图片" },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        leadingIcon = {
                            Text(
                                text = if (pendingUri in selectedUris) "已" else "图",
                                fontSize = 10.sp
                            )
                        },
                        trailingIcon = {
                            TextButton(
                                onClick = { onOpenPreview(attachments, index) },
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text("看")
                            }
                        }
                    )
                }
            }
        }
    }
}

private fun resolveAttachmentDisplayName(context: android.content.Context, uri: Uri): String? {
    val cursor = context.contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null
    ) ?: return uri.lastPathSegment
    cursor.use {
        if (it.moveToFirst()) {
            val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) {
                return it.getString(index)
            }
        }
    }
    return uri.lastPathSegment
}

private fun formatAttachmentSize(sizeBytes: Long): String {
    return when {
        sizeBytes >= 1024 * 1024 -> String.format("%.1f MB", sizeBytes / 1024f / 1024f)
        sizeBytes >= 1024 -> String.format("%.1f KB", sizeBytes / 1024f)
        else -> "$sizeBytes B"
    }
}

private data class MultimodalAnalysisUi(
    val summary: String,
    val ocrText: String,
    val risks: List<String>,
    val followUps: List<String>,
    val detail: String = ""
)

private data class ImagePreviewItemUi(
    val title: String,
    val uri: String? = null,
    val localCachePath: String? = null,
    val mimeType: String? = null
)

private fun parseMultimodalAssistantMessage(content: String): MultimodalAnalysisUi? {
    val normalized = content.trim()
    if ("[图片摘要]" !in normalized || "[OCR文本]" !in normalized || "[继续追问]" !in normalized) {
        return null
    }
    val sections = extractBracketSections(normalized)
    val summary = sections["图片摘要"]?.trim().orEmpty()
    val ocrText = sections["OCR文本"]?.trim().orEmpty()
    if (summary.isBlank() || ocrText.isBlank()) {
        return null
    }
    val risks = sections["风险提示"]
        .orEmpty()
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .map { it.removePrefix("-").trim() }
        .filter { it.isNotBlank() && it != "暂无明显风险" }
        .toList()
    val followUps = sections["继续追问"]
        .orEmpty()
        .lineSequence()
        .map { it.trim() }
        .filter { it.startsWith("- ") }
        .map { it.removePrefix("- ").trim() }
        .filter { it.isNotBlank() }
        .toList()
    return MultimodalAnalysisUi(
        summary = summary,
        ocrText = ocrText,
        risks = risks,
        followUps = followUps,
        detail = sections["详细分析"]?.trim().orEmpty()
    )
}

private fun extractBracketSections(content: String): Map<String, String> {
    val headerRegex = Regex("""^\[(.+)]\s*$""", RegexOption.MULTILINE)
    val matches = headerRegex.findAll(content).toList()
    if (matches.isEmpty()) return emptyMap()
    val result = linkedMapOf<String, String>()
    matches.forEachIndexed { index, match ->
        val sectionName = match.groupValues[1]
        val start = match.range.last + 1
        val end = matches.getOrNull(index + 1)?.range?.first ?: content.length
        result[sectionName] = content.substring(start, end).trim()
    }
    return result
}

@Composable
private fun ImagePreviewDialog(
    items: List<ImagePreviewItemUi>,
    initialIndex: Int,
    onDismiss: () -> Unit
) {
    var currentIndex by remember(items, initialIndex) {
        mutableIntStateOf(initialIndex.coerceIn(0, items.lastIndex.coerceAtLeast(0)))
    }
    val context = LocalContext.current
    val currentItem = items.getOrNull(currentIndex) ?: return
    val bitmap = remember(currentItem.uri, currentItem.localCachePath) {
        decodePreviewBitmap(context, currentItem)
    }
    val surfaceColor = rememberMurongSurfaceColor()
    val mutedTextColor = rememberMurongMutedTextColor()
    MurongDialog(onDismissRequest = onDismiss) {
        MurongPopupSurface(
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = currentItem.title.ifBlank { "图片预览" },
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    TextButton(onClick = onDismiss) {
                        Text("关闭")
                    }
                }
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = surfaceColor.copy(alpha = 0.58f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 220.dp, max = 520.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = currentItem.title,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp)
                            )
                        } else {
                            Text(
                                text = "图片预览不可用",
                                style = MaterialTheme.typography.bodyMedium,
                                color = mutedTextColor
                            )
                        }
                    }
                }
                if (items.size > 1) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = { currentIndex = (currentIndex - 1).coerceAtLeast(0) },
                            enabled = currentIndex > 0
                        ) {
                            Text("上一张")
                        }
                        Text(
                            text = "${currentIndex + 1} / ${items.size}",
                            style = MaterialTheme.typography.bodySmall,
                            color = mutedTextColor
                        )
                        OutlinedButton(
                            onClick = { currentIndex = (currentIndex + 1).coerceAtMost(items.lastIndex) },
                            enabled = currentIndex < items.lastIndex
                        ) {
                            Text("下一张")
                        }
                    }
                }
            }
        }
    }
}

private fun decodePreviewBitmap(
    context: android.content.Context,
    item: ImagePreviewItemUi
): Bitmap? {
    item.localCachePath?.let { path ->
        BitmapFactory.decodeFile(path)?.let { return it }
    }
    val uriString = item.uri ?: return null
    val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return null
    return when (uri.scheme?.lowercase()) {
        "file" -> uri.path?.let { path ->
            runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
        }
        else -> context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input)
        }
    }
}

private fun saveCapturedBitmapAsAttachment(
    context: android.content.Context,
    bitmap: Bitmap
): PendingImageAttachmentUi? {
    val file = File(context.cacheDir, "captured_${UUID.randomUUID()}.png")
    return runCatching {
        FileOutputStream(file).use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        }
        PendingImageAttachmentUi(
            uri = Uri.fromFile(file).toString(),
            fileName = file.name,
            mimeType = "image/png"
        )
    }.getOrNull()
}

@Composable
fun LoadingIndicator() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(24.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun WelcomeView(
    hasApiKey: Boolean,
    onNavigateToSettings: () -> Unit,
    compactForInput: Boolean = false,
    onSizeChanged: (Int) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val chromeColor = rememberMurongChromeColor()
    val surfaceColor = rememberMurongSurfaceColor()
    val mutedTextColor = rememberMurongMutedTextColor()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { onSizeChanged(it.height) }
            .padding(horizontal = 24.dp, vertical = if (compactForInput) 12.dp else 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = if (compactForInput) Arrangement.Top else Arrangement.Center
    ) {
        Text(
            text = "🤖 Murong Agent",
            fontSize = 28.sp,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "AI 编程助手 · 支持多 Provider",
            fontSize = 14.sp,
            color = mutedTextColor
        )
        Spacer(modifier = Modifier.height(32.dp))

        if (!hasApiKey) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = chromeColor.copy(alpha = 0.64f),
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "⚙️ 开始使用",
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "请先配置 AI 提供商和 API Key，\n支持 DeepSeek、OpenAI 兼容中转站、Claude",
                        fontSize = 13.sp,
                        color = mutedTextColor,
                        lineHeight = 20.sp
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = onNavigateToSettings,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text("前往设置", fontSize = 14.sp)
                    }
                }
            }
        } else {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = surfaceColor.copy(alpha = 0.82f),
                tonalElevation = 3.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "💬 可以开始了",
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "在下方输入你的问题，\n我可以帮你写代码、执行命令、查看文件",
                        fontSize = 13.sp,
                        color = mutedTextColor,
                        lineHeight = 20.sp
                    )
                }
            }
        }
    }
}

private fun hasStreamingMessage(messages: List<ChatMessageUi>): Boolean {
    return messages.any { it.isStreaming }
}

private fun formatAutoRouteAction(action: AutoRouteAction): String {
    return when (action) {
        AutoRouteAction.DIRECT -> "直接执行"
        AutoRouteAction.PLAN -> "先出计划"
        AutoRouteAction.CLARIFY -> "先澄清"
    }
}

private fun buildRoundText(messages: List<ChatMessageUi>, targetMessageId: Long): String {
    if (messages.isEmpty()) return ""
    val targetIndex = messages.indexOfFirst { it.id == targetMessageId }
    if (targetIndex == -1) return ""

    val startIndex = messages.subList(0, targetIndex + 1)
        .indexOfLast { it.role == "user" }
        .let { if (it == -1) 0 else it }
    val nextUserOffset = messages.drop(targetIndex + 1).indexOfFirst { it.role == "user" }
    val endExclusive = if (nextUserOffset == -1) {
        messages.size
    } else {
        targetIndex + 1 + nextUserOffset
    }

    return messages.subList(startIndex, endExclusive)
        .joinToString("\n\n") { formatMessageForCopy(it) }
}

private fun extractActiveMentionQuery(text: String): String? {
    val atIndex = text.lastIndexOf('@')
    if (atIndex == -1) return null
    val trailing = text.substring(atIndex + 1)
    if (trailing.contains('\n') || trailing.contains(' ')) return null
    val prefix = text.getOrNull(atIndex - 1)
    if (prefix != null && !prefix.isWhitespace() && prefix !in listOf('(', '[', '{', '，', '。', ',', ':', '：')) {
        return null
    }
    return trailing
}

private fun replaceTrailingMentionQuery(text: String, displayPath: String): String {
    val atIndex = text.lastIndexOf('@')
    if (atIndex == -1) return text
    val trailing = text.substring(atIndex + 1)
    if (trailing.contains('\n') || trailing.contains(' ')) return text
    return text.substring(0, atIndex) + "@$displayPath "
}

private fun buildProjectKnowledgeMentions(
    projectPath: String?,
    filePaths: List<String>
): List<FileMentionUi> {
    val normalizedProjectPath = projectPath?.trim()?.takeIf { it.isNotBlank() } ?: return emptyList()
    val root = runCatching { File(normalizedProjectPath).canonicalFile }.getOrNull() ?: return emptyList()
    return filePaths.mapNotNull { rawPath ->
        val safeFile = runCatching { File(rawPath).canonicalFile }.getOrNull() ?: return@mapNotNull null
        val displayPath = runCatching {
            safeFile.relativeTo(root).invariantSeparatorsPath
        }.getOrNull() ?: safeFile.name
        FileMentionUi(
            path = safeFile.absolutePath,
            displayPath = displayPath
        )
    }.distinctBy { it.path }
}

private fun mergeProjectKnowledgeMentions(
    projectKnowledgeMentions: List<FileMentionUi>,
    searchResults: List<FileMentionUi>,
    query: String
): List<FileMentionUi> {
    val normalizedQuery = query.trim()
    val knowledgePaths = projectKnowledgeMentions.map { it.path }.toSet()
    val matchedKnowledge = projectKnowledgeMentions.filter { mention ->
        normalizedQuery.isBlank() ||
            mention.displayPath.contains(normalizedQuery, ignoreCase = true)
    }
    return (matchedKnowledge + searchResults)
        .distinctBy { it.path }
        .sortedWith(
            compareByDescending<FileMentionUi> { it.path in knowledgePaths }
                .thenByDescending { projectKnowledgeMentionScore(it, normalizedQuery) }
                .thenBy { it.displayPath.length }
                .thenBy { it.displayPath.lowercase() }
        )
        .take(20)
}

private fun projectKnowledgeMentionScore(
    mention: FileMentionUi,
    query: String
): Int {
    val normalizedQuery = query.trim().lowercase()
    if (normalizedQuery.isBlank()) return 0
    val normalizedPath = mention.displayPath.replace('\\', '/').lowercase()
    val normalizedName = mentionPrimaryTitle(mention.displayPath).lowercase()
    return when {
        normalizedName == normalizedQuery -> 5
        normalizedPath == normalizedQuery -> 4
        normalizedName.startsWith(normalizedQuery) -> 3
        normalizedPath.startsWith(normalizedQuery) -> 2
        normalizedName.contains(normalizedQuery) -> 1
        else -> 0
    }
}

private fun mentionPrimaryTitle(displayPath: String): String {
    val normalized = displayPath.replace('\\', '/')
    return normalized.substringAfterLast('/').ifBlank { displayPath }
}

private fun mentionSecondaryPath(displayPath: String): String? {
    val normalized = displayPath.replace('\\', '/')
    val separatorIndex = normalized.lastIndexOf('/')
    if (separatorIndex <= 0) return null
    return normalized.substring(0, separatorIndex)
}

private fun mentionMatchReasonLabel(
    mention: FileMentionUi,
    query: String
): String? {
    val normalizedQuery = query.trim().lowercase()
    if (normalizedQuery.isBlank()) return null
    val normalizedPath = mention.displayPath.replace('\\', '/').lowercase()
    val normalizedName = mentionPrimaryTitle(mention.displayPath).lowercase()
    return when {
        normalizedName == normalizedQuery -> "文件名精确"
        normalizedPath == normalizedQuery -> "路径精确"
        normalizedName.startsWith(normalizedQuery) -> "文件名前缀"
        normalizedPath.startsWith(normalizedQuery) -> "路径前缀"
        normalizedName.contains(normalizedQuery) -> "文件名包含"
        normalizedPath.contains(normalizedQuery) -> "路径包含"
        else -> null
    }
}

private fun resolveQuickConfirmMention(
    query: String,
    results: List<FileMentionUi>
): FileMentionUi? {
    val normalizedQuery = query.trim().lowercase()
    if (normalizedQuery.isBlank()) return null
    if (results.size == 1) return results.first()
    return results.firstOrNull { mention ->
        val normalizedPath = mention.displayPath.replace('\\', '/').lowercase()
        val normalizedName = mentionPrimaryTitle(mention.displayPath).lowercase()
        normalizedPath == normalizedQuery || normalizedName == normalizedQuery
    }
}

private fun highlightMentionText(
    text: String,
    query: String,
    highlightColor: Color,
    highlightWeight: FontWeight
): AnnotatedString {
    val normalizedQuery = query.trim()
    if (normalizedQuery.isBlank()) return AnnotatedString(text)
    val matchRange = text.indexOf(normalizedQuery, ignoreCase = true)
    if (matchRange == -1) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        addStyle(
            style = SpanStyle(
                color = highlightColor,
                fontWeight = highlightWeight
            ),
            start = matchRange,
            end = matchRange + normalizedQuery.length
        )
    }
}

private data class WebFetchResultUi(
    val url: String,
    val title: String,
    val excerpt: String,
    val content: String,
    val truncated: Boolean,
    val totalChars: Int
)

private data class ToolExecutionMessageUi(
    val toolName: String,
    val summary: String?,
    val args: String,
    val callId: String?,
    val waitingForArgs: Boolean,
    val isQuiet: Boolean
)

private data class ToolResultMessageUi(
    val toolName: String,
    val summary: String?,
    val result: String,
    val resultLanguage: String,
    val renderResultAsCode: Boolean,
    val fileChanges: List<String>,
    val truncated: Boolean,
    val isQuiet: Boolean
)

@Composable
private fun ReasoningCard(
    content: String,
    expanded: Boolean,
    isScreenActive: Boolean,
    isStreaming: Boolean,
    onToggle: () -> Unit
) {
    val chromeColor = rememberMurongChromeColor()
    val surfaceColor = rememberMurongSurfaceColor()
    val mutedTextColor = rememberMurongMutedTextColor()
    MurongGlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
        shape = RoundedCornerShape(18.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        surfaceColorOverride = chromeColor.copy(alpha = 0.56f)
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (expanded) Icons.Outlined.KeyboardArrowDown else Icons.Outlined.KeyboardArrowUp,
                        contentDescription = null,
                        tint = mutedTextColor
                    )
                    Text(
                        text = "思考过程",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (isStreaming) {
                        Text(
                            text = "生成中",
                            style = MaterialTheme.typography.labelSmall,
                            color = mutedTextColor
                        )
                    }
                }
                Text(
                    text = if (expanded) "收起" else "展开",
                    style = MaterialTheme.typography.labelSmall,
                    color = mutedTextColor
                )
            }
            AnimatedVisibility(visible = expanded && isScreenActive) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = surfaceColor.copy(alpha = 0.82f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val normalizedContent = remember(content) {
                        normalizeReasoningMarkdown(content)
                    }
                    SelectableMarkdownContent(
                        text = normalizedContent,
                        fontSize = 12,
                        modifier = Modifier.padding(10.dp),
                        maxHeight = 320.dp
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolExecutionCard(
    tool: ToolExecutionMessageUi,
    isScreenActive: Boolean,
    stateKey: String
) {
    val mutedTextColor = rememberMurongMutedTextColor()
    val canExpand = tool.args.isNotBlank()
    val expandableArgs = canExpand && !tool.isQuiet
    var showArgs by rememberSaveable(stateKey) { mutableStateOf(canExpand) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ToolCardHeader(
            toolName = tool.toolName,
            statusText = "运行中",
            statusColor = MaterialTheme.colorScheme.primary,
            summary = tool.summary,
            expanded = showArgs,
            expandable = expandableArgs,
            quiet = tool.isQuiet,
            onToggle = {
                if (expandableArgs) {
                    showArgs = !showArgs
                }
            }
        )
        tool.callId?.takeIf { it.isNotBlank() }?.let { callId ->
            DetailRow("调用 ID", callId)
        }
        when {
            tool.waitingForArgs -> {
                Text(
                    text = "正在等待工具参数返回…",
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedTextColor
                )
            }
            canExpand -> {
                AnimatedVisibility(visible = showArgs && isScreenActive) {
                    DetailBlock("参数", tool.args)
                }
            }
        }
    }
}

@Composable
private fun ToolResultCard(
    tool: ToolResultMessageUi,
    isScreenActive: Boolean,
    stateKey: String
) {
    val canExpand = tool.result.isNotBlank() || tool.fileChanges.isNotEmpty()
    val expandableResult = canExpand && !tool.isQuiet
    var showResult by rememberSaveable(stateKey) {
        mutableStateOf(!tool.isQuiet && tool.fileChanges.isNotEmpty())
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ToolCardHeader(
            toolName = tool.toolName,
            statusText = "已完成",
            statusColor = MaterialTheme.colorScheme.tertiary,
            summary = tool.summary,
            expanded = showResult,
            expandable = expandableResult,
            quiet = tool.isQuiet,
            onToggle = {
                if (expandableResult) {
                    showResult = !showResult
                }
            }
        )
        AnimatedVisibility(visible = showResult && isScreenActive) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (tool.result.isNotBlank()) {
                    DetailBlock(
                        label = if (tool.truncated) "输出（已截断）" else "输出",
                        value = tool.result,
                        renderMarkdown = tool.renderResultAsCode,
                        codeLanguage = tool.resultLanguage
                    )
                }
                if (tool.fileChanges.isNotEmpty()) {
                    DetailBlock(
                        label = "本次文件变更",
                        value = tool.fileChanges.joinToString("\n")
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolCardHeader(
    toolName: String,
    statusText: String,
    statusColor: Color,
    summary: String?,
    expanded: Boolean,
    expandable: Boolean,
    quiet: Boolean,
    onToggle: () -> Unit
) {
    val mutedTextColor = rememberMurongMutedTextColor()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let { modifier ->
                if (expandable) {
                    modifier.clickable(onClick = onToggle)
                } else {
                    modifier
                }
            },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (expandable) {
                Icon(
                    imageVector = if (expanded) Icons.Outlined.KeyboardArrowDown else Icons.Outlined.KeyboardArrowUp,
                    contentDescription = null,
                    tint = mutedTextColor
                )
            } else {
                Spacer(modifier = Modifier.width(24.dp))
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(if (summary.isNullOrBlank()) 0.dp else 2.dp)
            ) {
                Text(
                    text = displayToolName(toolName),
                    style = if (quiet) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                summary?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = mutedTextColor,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        Surface(
            shape = RoundedCornerShape(999.dp),
            color = statusColor.copy(alpha = if (quiet) 0.1f else 0.14f)
        ) {
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelSmall,
                color = statusColor,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
            )
        }
    }
}

private fun parseWebFetchToolMessage(content: String): WebFetchResultUi? {
    if (!content.startsWith(WEB_FETCH_RESULT_PREFIX)) return null
    return runCatching {
        val payload = content.removePrefix(WEB_FETCH_RESULT_PREFIX)
        val root = WEB_FETCH_RESULT_JSON.parseToJsonElement(payload).jsonObject
        if (jsonStringOrNull(root, "type") != "web_fetch_result") return null
        WebFetchResultUi(
            url = jsonStringOrNull(root, "url").orEmpty(),
            title = jsonStringOrNull(root, "title").orEmpty(),
            excerpt = jsonStringOrNull(root, "excerpt").orEmpty(),
            content = jsonStringOrNull(root, "content").orEmpty(),
            truncated = jsonBooleanOrNull(root, "truncated") ?: false,
            totalChars = jsonIntOrNull(root, "totalChars") ?: 0
        )
    }.getOrNull()
}

private fun parseToolExecutionMessage(content: String): ToolExecutionMessageUi? {
    val headerMatch = Regex("""^🔧 正在执行: \*\*(.+?)\*\*""").find(content) ?: return null
    val toolName = headerMatch.groupValues.getOrNull(1).orEmpty()
    val callId = Regex("""调用 ID: `([^`]+)`""")
        .find(content)
        ?.groupValues
        ?.getOrNull(1)
    val args = Regex("""```(?:json)?\n([\s\S]*?)\n```""")
        .find(content)
        ?.groupValues
        ?.getOrNull(1)
        .orEmpty()
    return ToolExecutionMessageUi(
        toolName = toolName,
        summary = buildToolSummary(toolName = toolName, args = args),
        args = args,
        callId = callId,
        waitingForArgs = content.contains("等待工具参数返回"),
        isQuiet = isQuietTool(toolName)
    )
}

private fun parseToolResultMessage(
    content: String,
    previousExecution: ToolExecutionMessageUi? = null
): ToolResultMessageUi? {
    val headerMatch = Regex("""^📦 \*\*(.+?)\*\* 执行结果:""").find(content) ?: return null
    val toolName = headerMatch.groupValues.getOrNull(1).orEmpty()
    val sourcePath = previousExecution
        ?.takeIf { it.toolName.equals(toolName, ignoreCase = true) }
        ?.args
        ?.let(::extractToolPathFromArgs)
    val fencedMatch = Regex("""```([\w#+.-]+)?\n([\s\S]*?)\n```""")
        .find(content)
    val rawPayload = fencedMatch?.groupValues?.getOrNull(2)
        ?: extractPlainToolResultPayload(content, headerMatch.value)
    val codePayload = normalizeToolResultCodePayload(rawPayload)
    val truncated = rawPayload.endsWith("\n...(截断)") || rawPayload == "...(截断)"
    val normalizedPayload = codePayload.removeSuffix("\n...(截断)").removeSuffix("...(截断)")
    val renderResultAsCode = fencedMatch != null || shouldRenderToolResultAsCode(
        toolName = toolName,
        payload = normalizedPayload
    )
    val resultLanguage = fencedMatch?.groupValues?.getOrNull(1)
        ?.takeIf { it.isNotBlank() }
        ?: sourcePath?.let(::inferLanguageFromPath)
        ?: inferToolResultLanguage(
            toolName = toolName,
            payload = normalizedPayload
        ).orEmpty()
    val fileChanges = content.substringAfter("本次文件变更:\n", "")
        .lineSequence()
        .map { it.trim() }
        .filter { it.startsWith("- ") }
        .map { it.removePrefix("- ").trim() }
        .toList()
    return ToolResultMessageUi(
        toolName = toolName,
        summary = buildToolResultSummary(
            toolName = toolName,
            result = normalizedPayload,
            fileChanges = fileChanges
        ),
        result = normalizedPayload,
        resultLanguage = resultLanguage,
        renderResultAsCode = renderResultAsCode,
        fileChanges = fileChanges,
        truncated = truncated,
        isQuiet = isQuietTool(toolName)
    )
}

private fun extractPlainToolResultPayload(content: String, headerText: String): String {
    return content
        .substringAfter(headerText, "")
        .substringBefore("\n本次文件变更:\n")
        .trim('\r', '\n')
}

private fun normalizeToolResultCodePayload(payload: String): String {
    val lines = payload.lines()
    val lineNumberPrefix = Regex("""^\s*\d+→""")
    val numberedLineCount = lines.count { lineNumberPrefix.containsMatchIn(it) }
    if (numberedLineCount == 0) return payload
    if (numberedLineCount < minOf(2, lines.size)) return payload
    return lines.joinToString("\n") { line ->
        line.replaceFirst(lineNumberPrefix, "")
    }
}

private fun extractToolPathFromArgs(args: String): String? {
    if (args.isBlank()) return null
    val json = runCatching { WEB_FETCH_RESULT_JSON.parseToJsonElement(args).jsonObject }.getOrNull()
    return listOf("file_path", "path", "uri")
        .firstNotNullOfOrNull { key -> json?.let { jsonStringOrNull(it, key) } }
        ?.trim()
        ?.takeIf { it.isNotBlank() }
}

private fun inferLanguageFromPath(path: String): String? {
    val fileName = path.substringAfterLast('/').substringAfterLast('\\').lowercase()
    if (fileName.isBlank()) return null
    val ext = fileName.substringAfterLast('.', "")
    return when {
        fileName == "cmakelists.txt" -> "sh"
        fileName.endsWith(".d.ts") -> "typescript"
        ext in setOf("kt", "kts") -> "kotlin"
        ext == "java" -> "java"
        ext in setOf("js", "mjs", "cjs", "jsx") -> "javascript"
        ext in setOf("ts", "mts", "cts", "tsx") -> "typescript"
        ext == "go" -> "go"
        ext == "rs" -> "rust"
        ext == "c" -> "c"
        ext in setOf("cc", "cpp", "cxx") -> "cpp"
        ext == "h" -> "h"
        ext in setOf("hh", "hpp", "hxx") -> "hpp"
        ext in setOf("sh", "bash", "zsh") -> "sh"
        ext in setOf("xml", "xaml", "svg") -> "xml"
        ext in setOf("json", "jsonc", "geojson", "webmanifest") -> "json"
        ext in setOf("yml", "yaml") -> "yaml"
        ext in setOf("properties", "prop", "ini", "conf", "cfg", "pro", "toml") -> "properties"
        ext in setOf("py", "pyw") -> "python"
        ext in setOf("md", "mdown", "markdown") -> "markdown"
        else -> null
    }
}

private fun shouldRenderToolResultAsCode(toolName: String, payload: String): Boolean {
    if (payload.isBlank()) return false
    return when (toolName.lowercase()) {
        "read" -> !payload.equals("The file has 0 lines.", ignoreCase = true)
        else -> false
    }
}

private fun inferToolResultLanguage(toolName: String, payload: String): String? {
    if (!toolName.equals("read", ignoreCase = true)) return null
    val trimmed = payload.trim()
    if (trimmed.isBlank()) return null
    val firstLine = trimmed.lineSequence().firstOrNull()?.trim().orEmpty()
    val hasPreprocessor = Regex("""^\s*#\s*(include|define|if|ifdef|ifndef|endif|pragma)\b""", RegexOption.MULTILINE)
        .containsMatchIn(trimmed)
    val hasCppMarkers = Regex("""\b(std::|namespace\s+\w+|template\s*<|constexpr\b|nullptr\b)\b""", RegexOption.MULTILINE)
        .containsMatchIn(trimmed)
    val hasHeaderMarkers = Regex("""^\s*#\s*(pragma\s+once|ifndef\s+\w+|define\s+\w+)""", RegexOption.MULTILINE)
        .containsMatchIn(trimmed)
    val hasCMarkers = Regex("""\b(typedef\s+struct|#include\s*<stdio\.h>|#include\s*<stdlib\.h>|NULL\b)\b""", RegexOption.MULTILINE)
        .containsMatchIn(trimmed)
    val hasGoMarkers = Regex("""^\s*package\s+\w+""", RegexOption.MULTILINE).containsMatchIn(trimmed) &&
        Regex("""^\s*(func|import|type|var|const)\b""", RegexOption.MULTILINE).containsMatchIn(trimmed)
    val hasRustMarkers = Regex("""^\s*(fn|use|impl|trait|struct|enum)\b""", RegexOption.MULTILINE).containsMatchIn(trimmed) &&
        Regex("""\b(let\s+mut|String::|Vec<|Option<|Result<)\b""", RegexOption.MULTILINE).containsMatchIn(trimmed)
    val hasTypeScriptMarkers = Regex("""^\s*(interface|type)\s+\w+""", RegexOption.MULTILINE).containsMatchIn(trimmed) ||
        Regex("""\b(const|let|function)\s+\w+\s*:\s*[\w<>{}\[\]|?]+""", RegexOption.MULTILINE).containsMatchIn(trimmed) ||
        Regex("""\)\s*:\s*[\w<>{}\[\]|?]+\s*\{""", RegexOption.MULTILINE).containsMatchIn(trimmed)
    val hasJavaScriptMarkers = Regex("""^\s*(const|let|var|function|export|import)\b""", RegexOption.MULTILINE)
        .containsMatchIn(trimmed)
    return when {
        firstLine.startsWith("<?xml") ||
            firstLine.startsWith("<resources") ||
            firstLine.startsWith("<manifest") ||
            firstLine.startsWith("<") && trimmed.contains("</") -> "xml"
        firstLine.startsWith("{") || firstLine.startsWith("[") -> "json"
        hasHeaderMarkers && hasCppMarkers -> "hpp"
        hasHeaderMarkers && hasCMarkers -> "h"
        hasCppMarkers -> "cpp"
        hasPreprocessor && hasCMarkers -> "c"
        hasPreprocessor -> "cpp"
        hasGoMarkers -> "go"
        hasRustMarkers -> "rust"
        hasTypeScriptMarkers -> "typescript"
        hasJavaScriptMarkers -> "javascript"
        Regex("""^\s*plugins\s*\{""").containsMatchIn(trimmed) ||
            Regex("""^\s*(package|import)\s+[\w.]+""", RegexOption.MULTILINE).containsMatchIn(trimmed) &&
            Regex("""^\s*(fun|val|var|object|class|interface)\b""", RegexOption.MULTILINE).containsMatchIn(trimmed) -> "kotlin"
        Regex("""^\s*package\s+[\w.]+;""", RegexOption.MULTILINE).containsMatchIn(trimmed) ||
            Regex("""^\s*(public|private|protected)?\s*(class|interface|enum|record)\b""", RegexOption.MULTILINE).containsMatchIn(trimmed) -> "java"
        Regex("""^\s*def\s+\w+\(""", RegexOption.MULTILINE).containsMatchIn(trimmed) ||
            firstLine.startsWith("#!/usr/bin/env python") -> "python"
        firstLine.startsWith("#!/bin/bash") ||
            firstLine.startsWith("#!/usr/bin/env bash") ||
            Regex("""^\s*(if|for|while|case|function)\b""", RegexOption.MULTILINE).containsMatchIn(trimmed) -> "sh"
        Regex("""^\s*[\w.-]+\s*=\s*.+$""", RegexOption.MULTILINE).containsMatchIn(trimmed) -> "properties"
        Regex("""^\s*[\w.-]+\s*:\s*.+$""", RegexOption.MULTILINE).containsMatchIn(trimmed) -> "yaml"
        else -> null
    }
}

private fun buildToolSummary(toolName: String, args: String): String? {
    val subject = extractToolSubject(args)
    return when {
        !subject.isNullOrBlank() -> subject
        isQuietTool(toolName) -> "读取上下文"
        else -> null
    }
}

private fun buildToolResultSummary(
    toolName: String,
    result: String,
    fileChanges: List<String>
): String? {
    if (toolName.equals("shell", ignoreCase = true)) {
        return null
    }
    if (fileChanges.isNotEmpty()) {
        val firstChange = fileChanges.firstOrNull().orEmpty()
        return if (fileChanges.size == 1) {
            "已更新 $firstChange"
        } else {
            "已变更 ${fileChanges.size} 个文件"
        }
    }
    val firstLine = result
        .lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotBlank() }
        ?.let(::truncateInlineText)
    return when {
        !firstLine.isNullOrBlank() -> firstLine
        isQuietTool(toolName) -> "工具已返回结果"
        else -> null
    }
}

private fun extractToolSubject(args: String): String? {
    if (args.isBlank()) return null
    val json = runCatching { WEB_FETCH_RESULT_JSON.parseToJsonElement(args).jsonObject }.getOrNull()
    val preferredKeys = listOf(
        "file_path",
        "path",
        "url",
        "pattern",
        "information_request",
        "command",
        "cwd",
        "uri",
        "preview_url"
    )
    preferredKeys.forEach { key ->
        val value = json?.let { jsonStringOrNull(it, key) }?.trim()
        if (!value.isNullOrBlank()) {
            return when (key) {
                "file_path", "path", "cwd" -> shortenPath(value)
                else -> truncateInlineText(value)
            }
        }
    }
    return args.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotBlank() }
        ?.let(::truncateInlineText)
}

private fun isQuietTool(toolName: String): Boolean {
    return when (toolName.lowercase()) {
        "read",
        "grep",
        "glob",
        "ls",
        "searchcodebase",
        "webfetch",
        "web_fetch" -> true
        else -> false
    }
}

private fun shortenPath(path: String): String {
    val normalized = path.replace('\\', '/').trimEnd('/')
    if (normalized.isBlank()) return path
    val parts = normalized.split('/').filter { it.isNotBlank() }
    return when {
        parts.isEmpty() -> path
        parts.size == 1 -> parts.first()
        else -> "${parts[parts.size - 2]}/${parts.last()}"
    }
}

private fun truncateInlineText(value: String, maxLength: Int = 56): String {
    val singleLine = value.replace(Regex("""\s+"""), " ").trim()
    if (singleLine.length <= maxLength) return singleLine
    return singleLine.take(maxLength - 1).trimEnd() + "…"
}

private fun displayToolName(toolName: String): String {
    return when (toolName.lowercase()) {
        "file" -> "文件"
        "shell", "command" -> "命令"
        "read" -> "读取"
        "grep" -> "搜索"
        "glob" -> "匹配"
        "searchcodebase" -> "代码库搜索"
        "webfetch", "web_fetch" -> "网页抓取"
        "websearch", "web_search" -> "联网搜索"
        "code_edit" -> "代码编辑"
        "subagent" -> "子代理"
        "explore" -> "探索代理"
        "research" -> "研究代理"
        "review" -> "审查代理"
        "security_review" -> "安全审查代理"
        "ask_user" -> "提问"
        else -> toolName
    }
}

private fun copyTextToClipboard(context: android.content.Context, text: String) {
    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(null, text))
}

private fun jsonStringOrNull(
    root: kotlinx.serialization.json.JsonObject,
    key: String
): String? {
    return runCatching { root[key]?.jsonPrimitive?.content }.getOrNull()
}

private fun jsonBooleanOrNull(
    root: kotlinx.serialization.json.JsonObject,
    key: String
): Boolean? {
    return jsonStringOrNull(root, key)?.toBooleanStrictOrNull()
}

private fun jsonIntOrNull(
    root: kotlinx.serialization.json.JsonObject,
    key: String
): Int? {
    return jsonStringOrNull(root, key)?.toIntOrNull()
}

private val WEB_FETCH_RESULT_JSON = Json { ignoreUnknownKeys = true }

fun buildConversationText(messages: List<ChatMessageUi>): String {
    return messages
        .filter { msg ->
            msg.role != "system" || shouldIncludeSystemMessageInCopiedConversation(msg.content)
        }
        .joinToString("\n\n") { formatMessageForCopy(it) }
}

private fun formatMessageForCopy(msg: ChatMessageUi): String {
    val roleLabel = when (msg.role) {
        "user" -> "用户"
        "assistant" -> "助手"
        "tool_exec" -> "工具"
        "subagent" -> "子代理"
        "system" -> "系统状态"
        else -> msg.role
    }
    val parts = buildList {
        add("[$roleLabel]")
        msg.reasoning
            ?.takeIf { it.isNotBlank() }
            ?.let { reasoning ->
                add("[思考过程]")
                add(normalizeReasoningMarkdown(reasoning))
            }
        if (msg.content.isNotBlank()) {
            add(msg.content)
        }
    }
    return parts.joinToString("\n")
}

private fun normalizeReasoningMarkdown(text: String): String {
    val bulletPattern = Regex("""^(\s*)[•·▪●◦]\s+""")
    return text
        .replace("\r\n", "\n")
        .lineSequence()
        .joinToString("\n") { line ->
            bulletPattern.replace(line) { match ->
                "${match.groupValues[1]}- "
            }
        }
}

private fun shouldIncludeSystemMessageInCopiedConversation(content: String): Boolean {
    val normalized = content.trim()
    if (normalized.isBlank()) return false
    return normalized.startsWith("⚠️") || normalized.startsWith("⏹️")
}

@Composable
private fun DetailRow(label: String, value: String) {
    val mutedTextColor = rememberMurongMutedTextColor()
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = mutedTextColor
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun DetailBlock(
    label: String,
    value: String,
    renderMarkdown: Boolean = false,
    codeLanguage: String = ""
) {
    val surfaceColor = rememberMurongSurfaceColor()
    val mutedTextColor = rememberMurongMutedTextColor()
    val useNativeScrollableText = remember(value, renderMarkdown) {
        !renderMarkdown && shouldUseNativeScrollableSelectableText(value)
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = mutedTextColor
        )
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = surfaceColor.copy(alpha = 0.60f),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (useNativeScrollableText) {
                NativeSelectableScrollableText(
                    text = value,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    monospace = false,
                    fontSizeSp = 12f,
                    maxHeight = 320.dp
                )
            } else if (renderMarkdown) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp)
                ) {
                    MurongReadOnlyCodeBlock(
                        code = value,
                        language = codeLanguage.ifBlank { null },
                        backgroundColor = surfaceColor.copy(alpha = 0.92f),
                        modifier = Modifier.fillMaxWidth(),
                        textSizeSp = 12f,
                        maxVisibleLines = 16
                    )
                }
            } else {
                SelectionContainer {
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }
    }
}

// ── 子代理运行阶段定义 ──

private data class SubagentStage(
    val key: String,
    val label: String,
    val status: StageStatus,
    val startedAt: Long?,
    val durationLabel: String?,
    val timestampLabel: String?
)

private enum class StageStatus { COMPLETED, CURRENT, PENDING, SKIPPED }

private fun buildSubagentRunStages(run: SubagentRunUi): List<SubagentStage> {
    val now = System.currentTimeMillis()
    val isTerminal = run.status in setOf("completed", "failed", "cancelled", "rejected")

    fun isActive(stageKey: String): Boolean {
        if (isTerminal) return false
        return when (stageKey) {
            "approval" -> run.status == "pending_approval"
            "queue" -> run.status == "queued"
            "execution" -> run.status == "running"
            "summarizing" -> run.status == "summarizing"
            else -> false
        }
    }

    fun isComplete(stageKey: String): Boolean {
        return when (stageKey) {
            "approval" -> run.queuedAt != null || run.status in setOf("queued", "running", "summarizing", "completed", "failed", "cancelled", "rejected")
            "queue" -> run.executionStartedAt != null || run.status in setOf("running", "summarizing", "completed", "failed", "cancelled", "rejected")
            "execution" -> run.summarizingAt != null || run.finishedAt != null || (run.status in setOf("completed", "failed", "cancelled", "rejected") && !setOf("summarizing", "cancelling").contains(run.status))
            "summarizing" -> run.status in setOf("completed", "failed", "cancelled", "rejected") && !setOf("cancelling").contains(run.status)
            "finished" -> run.status in setOf("completed", "failed", "cancelled", "rejected")
            else -> false
        }
    }

    fun stageStatus(stageKey: String): StageStatus {
        return when {
            isComplete(stageKey) -> StageStatus.COMPLETED
            isActive(stageKey) -> StageStatus.CURRENT
            run.status == "failed" && stageKey == "execution" -> StageStatus.CURRENT
            else -> StageStatus.PENDING
        }
    }

    return listOfNotNull(
        SubagentStage(
            key = "approval",
            label = "待审批",
            status = if (run.approvalRequestedAt != null) stageStatus("approval") else StageStatus.SKIPPED,
            startedAt = run.approvalRequestedAt,
            durationLabel = calculateRunApprovalWaitMillis(run)?.let { formatDurationMillis(it) },
            timestampLabel = run.approvalRequestedAt?.let { formatTimestamp(it) }
        ).takeIf { run.approvalRequestedAt != null },
        SubagentStage(
            key = "queue",
            label = "排队中",
            status = if (run.queuedAt != null) stageStatus("queue") else StageStatus.SKIPPED,
            startedAt = run.queuedAt,
            durationLabel = calculateRunQueueWaitMillis(run)?.let { formatDurationMillis(it) },
            timestampLabel = run.queuedAt?.let { formatTimestamp(it) }
        ).takeIf { run.queuedAt != null },
        SubagentStage(
            key = "execution",
            label = "运行中",
            status = stageStatus("execution"),
            startedAt = run.executionStartedAt,
            durationLabel = calculateRunExecutionMillis(run)?.let { formatDurationMillis(it) },
            timestampLabel = run.executionStartedAt?.let { formatTimestamp(it) }
        ),
        SubagentStage(
            key = "summarizing",
            label = "整理摘要",
            status = if (run.summarizingAt != null) stageStatus("summarizing") else StageStatus.SKIPPED,
            startedAt = run.summarizingAt,
            durationLabel = (run.finishedAt?.let { finished ->
                run.summarizingAt?.let { sum -> formatDurationMillis((finished - sum).coerceAtLeast(0L)) }
            }),
            timestampLabel = run.summarizingAt?.let { formatTimestamp(it) }
        ).takeIf { run.summarizingAt != null },
        SubagentStage(
            key = "finished",
            label = when {
                run.status == "failed" -> "失败"
                run.status == "cancelled" -> "已终止"
                run.status == "rejected" -> "已拒绝"
                else -> "完成"
            },
            status = stageStatus("finished"),
            startedAt = run.finishedAt,
            durationLabel = null,
            timestampLabel = run.finishedAt?.let { formatTimestamp(it) }
        )
    )
}

private fun stageStatusColor(status: StageStatus, themeColor: Color): Color = when (status) {
    StageStatus.COMPLETED -> Color(0xFF2E7D32)
    StageStatus.CURRENT -> themeColor
    StageStatus.PENDING -> Color(0xFFBDBDBD)
    StageStatus.SKIPPED -> Color(0xFFE0E0E0)
}

// ── 子代理运行阶段时间线可视化 ──

@Composable
private fun SubagentRunStageTimeline(run: SubagentRunUi) {
    val stages = remember(run) { buildSubagentRunStages(run) }
    val mutedTextColor = rememberMurongMutedTextColor()
    if (stages.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        stages.forEachIndexed { index, stage ->
            val isLast = index == stages.lastIndex
            val dotColor = stageStatusColor(stage.status, MaterialTheme.colorScheme.primary)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 左列：圆点 + 连接线
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.width(40.dp)
                ) {
                    // 圆点
                    Box(
                        modifier = Modifier
                            .size(if (stage.status == StageStatus.CURRENT) 14.dp else 10.dp)
                            .clip(RoundedCornerShape(50))
                            .background(dotColor)
                    )
                    // 连接线（非最后一个，且非跳过状态）
                    if (!isLast && stages[index + 1].status != StageStatus.SKIPPED) {
                        Box(
                            modifier = Modifier
                                .width(2.dp)
                                .height(24.dp)
                                .background(
                                    if (stage.status == StageStatus.PENDING) Color(0xFFE0E0E0)
                                    else dotColor.copy(alpha = if (stage.status == StageStatus.COMPLETED) 1f else 0.4f)
                                )
                        )
                    } else if (!isLast) {
                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }

                // 右列：标签 + 耗时 + 时间
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 4.dp, top = 2.dp, bottom = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stage.label,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (stage.status == StageStatus.CURRENT) FontWeight.SemiBold else FontWeight.Normal,
                            color = when (stage.status) {
                                StageStatus.COMPLETED -> MaterialTheme.colorScheme.onSurface
                                StageStatus.CURRENT -> MaterialTheme.colorScheme.primary
                                StageStatus.PENDING -> mutedTextColor.copy(alpha = 0.72f)
                                StageStatus.SKIPPED -> mutedTextColor.copy(alpha = 0.48f)
                            }
                        )
                        stage.durationLabel?.let { duration ->
                            Text(
                                text = duration,
                                style = MaterialTheme.typography.bodySmall,
                                color = when (stage.status) {
                                    StageStatus.COMPLETED -> Color(0xFF2E7D32)
                                    StageStatus.CURRENT -> MaterialTheme.colorScheme.primary
                                    else -> mutedTextColor.copy(alpha = 0.72f)
                                }
                            )
                        }
                    }
                    stage.timestampLabel?.let { ts ->
                        Text(
                            text = ts,
                            style = MaterialTheme.typography.labelSmall,
                            color = mutedTextColor.copy(alpha = 0.8f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SubagentBatchTimeline(timeline: List<SubagentTimelineEntryUi>) {
    val mutedTextColor = rememberMurongMutedTextColor()
    if (timeline.isEmpty()) {
        Text(
            text = "当前还没有可展示的批次时间线。",
            style = MaterialTheme.typography.bodySmall,
            color = mutedTextColor.copy(alpha = 0.72f)
        )
        return
    }

    val sortedTimeline = remember(timeline) { timeline.sortedBy { it.timestamp } }

    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        sortedTimeline.forEachIndexed { index, entry ->
            val isLast = index == sortedTimeline.lastIndex
            val dotColor = batchTimelineEntryColor(entry.type, MaterialTheme.colorScheme)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 左列：圆点 + 连接线
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.width(40.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(RoundedCornerShape(50))
                            .background(dotColor)
                    )
                    if (!isLast) {
                        Box(
                            modifier = Modifier
                                .width(2.dp)
                                .height(28.dp)
                                .background(dotColor.copy(alpha = 0.3f))
                        )
                    }
                }

                // 右列：标题 + 详情 + 时间
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = entry.title,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = formatTimestamp(entry.timestamp),
                            style = MaterialTheme.typography.labelSmall,
                            color = mutedTextColor.copy(alpha = 0.8f),
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                    if (entry.detail.isNotBlank()) {
                        Text(
                            text = entry.detail,
                            style = MaterialTheme.typography.labelSmall,
                            color = mutedTextColor,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

private fun batchTimelineEntryColor(type: String, scheme: ColorScheme): Color = when (type) {
    "approval" -> scheme.tertiary
    "queue", "queued" -> scheme.secondary
    "execution", "executing", "started" -> scheme.primary
    "summarizing", "summary" -> scheme.tertiary
    "completed", "success" -> Color(0xFF2E7D32)
    "failed", "error", "rejected" -> scheme.error
    "cancelled", "cancelling" -> Color(0xFFE65100)
    "split", "batch", "batch_approval" -> Color(0xFF7B1FA2)
    else -> scheme.onSurfaceVariant
}

private fun subagentStatusLabel(status: String): String = when (status) {
    "pending_approval" -> "待审批"
    "queued" -> "排队中"
    "running" -> "运行中"
    "summarizing" -> "整理摘要"
    "cancelling" -> "终止中"
    "completed" -> "已完成"
    "failed" -> "失败"
    "cancelled" -> "已终止"
    "rejected" -> "已拒绝"
    else -> status
}

@Composable
private fun subagentStatusColor(status: String): Color = when (status) {
    "pending_approval" -> MaterialTheme.colorScheme.tertiary
    "queued" -> MaterialTheme.colorScheme.secondary
    "running" -> MaterialTheme.colorScheme.primary
    "summarizing" -> MaterialTheme.colorScheme.tertiary
    "cancelling" -> MaterialTheme.colorScheme.tertiary
    "completed" -> Color(0xFF2E7D32)
    "failed" -> MaterialTheme.colorScheme.error
    "cancelled" -> MaterialTheme.colorScheme.error
    "rejected" -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun subagentBatchStatusColor(status: String): Color = when (status) {
    "pending_approval" -> MaterialTheme.colorScheme.tertiary
    "queued" -> MaterialTheme.colorScheme.secondary
    "running" -> MaterialTheme.colorScheme.primary
    "completed" -> Color(0xFF2E7D32)
    "completed_with_failures" -> Color(0xFFE65100)
    "failed", "rejected" -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun subagentBatchStatusLabel(status: String): String = when (status) {
    "pending_approval" -> "待审批"
    "queued" -> "排队中"
    "running" -> "进行中"
    "completed" -> "已完成"
    "completed_with_failures" -> "部分失败"
    "failed" -> "失败"
    "rejected" -> "已拒绝"
    else -> status
}

private fun canRetrySubagent(status: String): Boolean {
    return status in setOf("completed", "failed", "cancelled", "rejected")
}

private fun canCancelSubagent(status: String): Boolean {
    return status in setOf("pending_approval", "queued", "running", "summarizing")
}

private fun formatSubagentTimeRange(run: SubagentRunUi): String {
    val finishedAt = run.finishedAt
    return if (finishedAt != null) {
        "${formatTimestamp(run.startedAt)} - ${formatTimestamp(finishedAt)}"
    } else {
        "${formatTimestamp(run.startedAt)} 开始"
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val formatter = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
    return formatter.format(java.util.Date(timestamp))
}

private fun buildRunSchedulingMetrics(run: SubagentRunUi): List<Pair<String, String>> {
    return listOfNotNull(
        calculateRunApprovalWaitMillis(run)?.let { "审批等待" to formatDurationMillis(it) },
        calculateRunQueueWaitMillis(run)?.let { "排队等待" to formatDurationMillis(it) },
        calculateRunExecutionMillis(run)?.let { "执行耗时" to formatDurationMillis(it) },
        calculateRunTotalMillis(run)?.let { "总耗时" to formatDurationMillis(it) }
    )
}

private fun buildBatchSchedulingMetrics(batch: SubagentBatchUi): List<Pair<String, String>> {
    return listOfNotNull(
        calculateBatchApprovalWaitMillis(batch)?.let { "审批等待" to formatDurationMillis(it) },
        calculateBatchQueueWaitMillis(batch)?.let { "首轮排队" to formatDurationMillis(it) },
        calculateBatchExecutionMillis(batch)?.let { "批次执行" to formatDurationMillis(it) },
        calculateBatchTotalMillis(batch)?.let { "总耗时" to formatDurationMillis(it) }
    )
}

private fun buildBatchSchedulingMetricLine(batch: SubagentBatchUi): String? {
    return buildBatchSchedulingMetrics(batch)
        .joinToString(" · ") { (label, value) -> "$label $value" }
        .ifBlank { null }
}

private fun calculateRunApprovalWaitMillis(run: SubagentRunUi): Long? {
    val startedAt = run.approvalRequestedAt ?: return null
    val endAt = run.queuedAt
        ?: if (run.status == "pending_approval") System.currentTimeMillis() else run.finishedAt
        ?: return null
    return (endAt - startedAt).coerceAtLeast(0L)
}

private fun calculateRunQueueWaitMillis(run: SubagentRunUi): Long? {
    val queuedAt = run.queuedAt ?: return null
    val endAt = run.executionStartedAt ?: if (run.status == "queued") System.currentTimeMillis() else return null
    return (endAt - queuedAt).coerceAtLeast(0L)
}

private fun calculateRunExecutionMillis(run: SubagentRunUi): Long? {
    val startedAt = run.executionStartedAt ?: return null
    val endAt = run.finishedAt ?: System.currentTimeMillis()
    return (endAt - startedAt).coerceAtLeast(0L)
}

private fun calculateRunTotalMillis(run: SubagentRunUi): Long? {
    val endAt = run.finishedAt ?: System.currentTimeMillis()
    return (endAt - run.startedAt).coerceAtLeast(0L)
}

private fun calculateBatchApprovalWaitMillis(batch: SubagentBatchUi): Long? {
    val startedAt = batch.approvalRequestedAt ?: return null
    val endAt = batch.queuedAt
        ?: if (batch.status == "pending_approval") System.currentTimeMillis() else batch.finishedAt
        ?: return null
    return (endAt - startedAt).coerceAtLeast(0L)
}

private fun calculateBatchQueueWaitMillis(batch: SubagentBatchUi): Long? {
    val queuedAt = batch.queuedAt ?: return null
    val endAt = batch.firstRunStartedAt ?: if (batch.status == "queued") System.currentTimeMillis() else return null
    return (endAt - queuedAt).coerceAtLeast(0L)
}

private fun calculateBatchExecutionMillis(batch: SubagentBatchUi): Long? {
    val startedAt = batch.firstRunStartedAt ?: return null
    val endAt = batch.finishedAt ?: System.currentTimeMillis()
    return (endAt - startedAt).coerceAtLeast(0L)
}

private fun calculateBatchTotalMillis(batch: SubagentBatchUi): Long? {
    val endAt = batch.finishedAt ?: System.currentTimeMillis()
    return (endAt - batch.startedAt).coerceAtLeast(0L)
}

private fun formatDurationMillis(durationMs: Long): String {
    val totalSeconds = (durationMs / 1000).coerceAtLeast(0L)
    return when {
        totalSeconds < 60 -> "${totalSeconds}s"
        totalSeconds < 3600 -> {
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            if (seconds == 0L) "${minutes}m" else "${minutes}m ${seconds}s"
        }
        else -> {
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            if (minutes == 0L) "${hours}h" else "${hours}h ${minutes}m"
        }
    }
}

@Composable
fun SessionDrawerContent(
    currentSessionId: String,
    sessions: List<SessionSummary>,
    onNewSession: () -> Unit,
    onNewTask: () -> Unit,
    onLoadSession: (String) -> Unit,
    onRenameSession: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val surfaceColor = rememberMurongSurfaceColor()
    val mutedTextColor = rememberMurongMutedTextColor()
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f, fill = false)) {
                Text(
                    text = "对话",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "新建、切换和管理历史会话",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilledTonalButton(
                onClick = onNewSession,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Add,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("新对话")
            }
            FilledTonalButton(
                onClick = onNewTask,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Add,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("新任务")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (sessions.isEmpty()) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = surfaceColor.copy(alpha = 0.58f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "还没有保存的历史对话",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "发送第一条消息后，这里会自动出现会话记录。",
                        style = MaterialTheme.typography.bodySmall,
                        color = mutedTextColor
                    )
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(sessions, key = { it.id }) { session ->
                    SessionDrawerItem(
                        session = session,
                        selected = session.id == currentSessionId,
                        onClick = { onLoadSession(session.id) },
                        onRename = { onRenameSession(session.id) },
                        onDelete = { onDeleteSession(session.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionDrawerItem(
    session: SessionSummary,
    selected: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    val surfaceColor = rememberMurongSurfaceColor()
    val mutedTextColor = rememberMurongMutedTextColor()
    val accent = rememberMurongAccentColor()

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (selected) {
            accent.copy(alpha = 0.16f)
        } else {
            surfaceColor.copy(alpha = 0.54f)
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.title.ifBlank { "新对话" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${session.messageCount} 条消息 · ${session.providerId}",
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedTextColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                session.toSessionReadinessPresentation()?.let { readiness ->
                    Text(
                        text = "收口: ${readiness.summary}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (readiness.blocked) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (session.modelName.isNotBlank()) {
                    Text(
                        text = session.modelName,
                        style = MaterialTheme.typography.bodySmall,
                        color = mutedTextColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                session.projectPath?.takeIf { it.isNotBlank() }?.let { projectPath ->
                    Text(
                        text = projectPath,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        imageVector = Icons.Outlined.MoreVert,
                        contentDescription = "会话操作",
                        tint = mutedTextColor
                    )
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("重命名") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Outlined.Edit,
                                contentDescription = null
                            )
                        },
                        onClick = {
                            showMenu = false
                            onRename()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("删除会话") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Outlined.Delete,
                                contentDescription = null
                            )
                        },
                        onClick = {
                            showMenu = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatApprovalModeDialog(
    globalMode: ToolApprovalMode,
    overrideMode: ToolApprovalMode?,
    onDismiss: () -> Unit,
    onApply: (ToolApprovalMode?) -> Unit
) {
    var selectedMode by remember(globalMode, overrideMode) { mutableStateOf(overrideMode) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onApply(selectedMode) }) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
        title = { Text("审批模式") },
        text = {
            val followGlobalOptionPresentation = remember(globalMode) {
                buildApprovalModeFollowGlobalOptionPresentation(globalMode)
            }
            val modeOptionPresentations = remember { buildApprovalModeOptionPresentations() }
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ApprovalModeOptionRow(
                    title = followGlobalOptionPresentation.title,
                    subtitle = followGlobalOptionPresentation.subtitle,
                    selected = selectedMode == null,
                    onClick = { selectedMode = null }
                )
                modeOptionPresentations.forEach { optionPresentation ->
                    ApprovalModeOptionRow(
                        title = optionPresentation.title,
                        subtitle = optionPresentation.subtitle,
                        selected = selectedMode == optionPresentation.mode,
                        onClick = { selectedMode = optionPresentation.mode }
                    )
                }
            }
        }
    )
}

@Composable
private fun ApprovalModeOptionRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(modifier = Modifier.width(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun normalizeChatProjectToolPreferences(
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

@Composable
private fun CurrentWorkspaceHint(
    title: String,
    workspaceLabel: String,
    helperText: String? = null,
    expanded: Boolean,
    onToggleExpanded: () -> Unit
) {
    CompactTopHint(
        badge = if (title.contains("仓库")) "仓" else "项",
        title = title,
        message = buildString {
            append(workspaceLabel)
            helperText?.takeIf { it.isNotBlank() }?.let {
                append("\n")
                append(it)
            }
        },
        expanded = expanded,
        onToggleExpanded = onToggleExpanded
    )
}

@Composable
private fun SessionUsageBar(usageSummary: UsageSummarySnapshot) {
    val chromeColor = rememberMurongChromeColor()
    val mutedTextColor = rememberMurongMutedTextColor()
    Surface(
        color = chromeColor.copy(alpha = 0.60f),
        tonalElevation = 2.dp,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = "当前会话消耗",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary
            )
            Text(
                text = "总 Token ${usageSummary.totalTokens} · 输入 ${usageSummary.promptTokens} · 输出 ${usageSummary.completionTokens}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            val cacheParts = buildList {
                if (usageSummary.promptCacheHitTokens > 0) add("缓存命中 ${usageSummary.promptCacheHitTokens}")
                if (usageSummary.promptCacheMissTokens > 0) add("缓存未命中 ${usageSummary.promptCacheMissTokens}")
            }
            if (cacheParts.isNotEmpty()) {
                Text(
                    text = cacheParts.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedTextColor
                )
            }
            val lastTurnCacheParts = buildList {
                if (usageSummary.lastTurnPromptCacheHitTokens > 0) {
                    add("最近一轮命中 ${usageSummary.lastTurnPromptCacheHitTokens}")
                }
                if (usageSummary.lastTurnPromptCacheMissTokens > 0) {
                    add("最近一轮未命中 ${usageSummary.lastTurnPromptCacheMissTokens}")
                }
            }
            if (lastTurnCacheParts.isNotEmpty()) {
                Text(
                    text = lastTurnCacheParts.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedTextColor
                )
            }
            if (usageSummary.lastCachePrefixChanged &&
                usageSummary.lastCachePrefixChangeReasons.isNotEmpty()
            ) {
                Text(
                    text = "最近一轮前缀变化: " + usageSummary.lastCachePrefixChangeReasons
                        .joinToString("、", transform = ::cacheReasonLabel),
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedTextColor
                )
            }
            Text(
                text = "预估成本 ${
                    formatCurrencyAmount(
                        usageSummary.resolvedEstimatedCostAmount(),
                        usageSummary.resolvedEstimatedCostCurrency()
                    )
                }",
                style = MaterialTheme.typography.bodySmall,
                color = mutedTextColor
            )
        }
    }
}

private fun cacheReasonLabel(reason: String): String = when (reason) {
    "tools" -> "工具集"
    "compression" -> "压缩上下文"
    "project_context" -> "项目上下文"
    "session_goal" -> "会话目标"
    "project_skills" -> "项目技能"
    "mcp_tools" -> "MCP 工具"
    "plan_mode" -> "规划模式"
    "stable_system" -> "系统前缀"
    else -> reason
}

@Composable
private fun FileChangeStatusHint(
    presentation: ChatCheckpointActivityHintPresentation,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onClick: () -> Unit
) {
    CompactTopHint(
        badge = "改",
        title = presentation.title,
        message = presentation.message,
        expanded = expanded,
        onToggleExpanded = onToggleExpanded,
        onBubbleClick = onClick,
        actionLabel = "查看",
        onAction = onClick
    )
}

@Composable
private fun CompressionStatusHint(
    version: Int,
    totalVersions: Int,
    summary: String,
    sourceMessageCount: Int,
    active: Boolean,
    createdAt: Long,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onClick: () -> Unit
) {
    CompactTopHint(
        badge = "压",
        title = buildString {
            append(if (active) "压缩已启用" else "压缩已停用")
            append(" · V")
            append(version)
            if (totalVersions > 1) append("/$totalVersions")
        },
        message = buildString {
            append("覆盖 $sourceMessageCount 条历史消息 · ${formatTimestamp(createdAt)}")
            append("\n")
            append(summary.lineSequence().take(3).joinToString(" ").take(180))
            if (totalVersions > 1) {
                append("\n点击查看历史摘要版本")
            }
        },
        expanded = expanded,
        onToggleExpanded = onToggleExpanded,
        onBubbleClick = onClick
    )
}

@Composable
private fun SubagentStatusHint(
    runs: List<SubagentRunUi>,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onClick: () -> Unit
) {
    val latestRun = runs.maxByOrNull { it.startedAt }
    val runningCount = runs.count { it.status in setOf("pending_approval", "queued", "running", "summarizing", "cancelling") }
    val failedCount = runs.count { it.status in setOf("failed", "rejected") }
    val completedCount = runs.count { it.status == "completed" }
    CompactTopHint(
        badge = "子",
        title = "子代理总览 · ${runs.size} 次运行",
        message = buildString {
            append("运行中 $runningCount · 已完成 $completedCount")
            if (failedCount > 0) {
                append(" · 失败 $failedCount")
            }
            latestRun?.let { run ->
                append("\n最新任务: ")
                append(run.goal.take(36))
                if (run.goal.length > 36) append("...")
                append(" · ")
                append(subagentStatusLabel(run.status))
            }
        },
        expanded = expanded,
        onToggleExpanded = onToggleExpanded,
        onBubbleClick = onClick,
        actionLabel = "查看全部",
        onAction = onClick
    )
}

@Composable
private fun FileChangeBatchSheet(
    presentation: ChatCheckpointHistoryPresentation,
    onDismiss: () -> Unit,
    onOpenCheckpoint: (String) -> Unit,
    onOpenRecovery: (String) -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        val surfaceColor = rememberMurongSurfaceColor()
        val mutedTextColor = rememberMurongMutedTextColor()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = presentation.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (!hasChatCheckpointActivity(presentation)) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = surfaceColor.copy(alpha = 0.56f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = presentation.emptyState,
                        modifier = Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = mutedTextColor
                    )
                }
            } else {
                if (presentation.recoveries.isNotEmpty()) {
                    Text(
                        text = "最近恢复",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    presentation.recoveries.forEach { recovery ->
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = surfaceColor.copy(alpha = 0.56f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenRecovery(recovery.id) }
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = recovery.title,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = recovery.subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = mutedTextColor
                                )
                                Text(
                                    text = recovery.detailContent,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
                if (presentation.checkpoints.isNotEmpty()) {
                    Text(
                        text = "修改批次",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    presentation.checkpoints.forEach { checkpoint ->
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = surfaceColor.copy(alpha = 0.56f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenCheckpoint(checkpoint.id) }
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = checkpoint.title,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = checkpoint.subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = mutedTextColor
                                )
                                Text(
                                    text = checkpoint.changedFilesPreview,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun CheckpointRecoveryDetailSheet(
    record: ChatCheckpointRecoveryPresentation,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        val chromeColor = rememberMurongChromeColor()
        val mutedTextColor = rememberMurongMutedTextColor()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = record.detailTitle,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = record.detailSubtitle,
                style = MaterialTheme.typography.bodySmall,
                color = mutedTextColor
            )
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = chromeColor.copy(alpha = 0.58f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = record.detailContent,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun FileChangeBatchDetailSheet(
    checkpoint: ConversationCheckpointUi,
    records: List<FileChangeRecordUi>,
    onDismiss: () -> Unit,
    onRollbackCheckpoint: () -> Unit,
    onOpenRecord: (FileChangeRecordUi) -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        val surfaceColor = rememberMurongSurfaceColor()
        val chromeColor = rememberMurongChromeColor()
        val mutedTextColor = rememberMurongMutedTextColor()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "修改批次详情",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = checkpoint.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "${records.size} 个文件 · ${formatTimestamp(checkpoint.createdAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = mutedTextColor
            )
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = chromeColor.copy(alpha = 0.58f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "回滚影响",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.error
                    )
                    Text(
                        text = formatCheckpointRollbackImpactCopy(checkpoint.scope),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = onRollbackCheckpoint,
                    enabled = checkpoint.scope != ConversationCheckpointScope.CODE || records.isNotEmpty()
                ) {
                    Text(formatCheckpointRollbackActionLabel(checkpoint.scope))
                }
                TextButton(onClick = onDismiss) {
                    Text("稍后处理")
                }
            }
            records.forEach { record ->
                Surface(
                    shape = RoundedCornerShape(16.dp),
                        color = surfaceColor.copy(alpha = 0.56f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenRecord(record) }
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = record.path,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = formatFileChangeOperation(record.operation),
                            style = MaterialTheme.typography.bodySmall,
                            color = mutedTextColor
                        )
                        Text(
                            text = record.diffPreview.lineSequence().take(4).joinToString("\n"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SubagentRunHistorySheet(
    runs: List<SubagentRunUi>,
    onDismiss: () -> Unit,
    onOpenRun: (SubagentRunUi) -> Unit
) {
    var selectedFilter by remember(runs) { mutableStateOf(SubagentHistoryFilter.ALL) }
    var selectedSort by remember(runs) { mutableStateOf(SubagentHistorySortMode.LATEST) }
    val surfaceColor = rememberMurongSurfaceColor()
    val mutedTextColor = rememberMurongMutedTextColor()
    val filteredRuns = remember(runs, selectedFilter) {
        runs.filter { matchesSubagentHistoryFilter(it, selectedFilter) }
    }
    val sortedRuns = remember(filteredRuns, selectedSort) {
        sortSubagentRuns(filteredRuns, selectedSort)
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "子代理任务",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SubagentMetricChip(
                    label = "总数 ${runs.size}",
                    color = MaterialTheme.colorScheme.secondary
                )
                SubagentMetricChip(
                    label = "运行中 ${runs.count { it.status in setOf("pending_approval", "queued", "running", "summarizing", "cancelling") }}",
                    color = MaterialTheme.colorScheme.primary
                )
                SubagentMetricChip(
                    label = "已完成 ${runs.count { it.status == "completed" }}",
                    color = Color(0xFF2E7D32)
                )
                if (runs.any { it.status in setOf("failed", "rejected") }) {
                    SubagentMetricChip(
                        label = "失败 ${runs.count { it.status in setOf("failed", "rejected") }}",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            Text(
                text = "按状态筛选",
                style = MaterialTheme.typography.labelMedium,
                color = mutedTextColor
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SubagentHistoryFilter.entries.forEach { filter ->
                    FilterChip(
                        selected = selectedFilter == filter,
                        onClick = { selectedFilter = filter },
                        label = { Text(filter.label) }
                    )
                }
            }
            Text(
                text = "排序方式",
                style = MaterialTheme.typography.labelMedium,
                color = mutedTextColor
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SubagentHistorySortMode.entries.forEach { sort ->
                    FilterChip(
                        selected = selectedSort == sort,
                        onClick = { selectedSort = sort },
                        label = { Text(sort.label) }
                    )
                }
            }
            Text(
                text = "筛选后 ${sortedRuns.size} 条",
                style = MaterialTheme.typography.labelSmall,
                color = mutedTextColor
            )
            if (runs.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = surfaceColor.copy(alpha = 0.56f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "当前会话还没有子代理任务。",
                        modifier = Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = mutedTextColor
                    )
                }
            } else if (sortedRuns.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = surfaceColor.copy(alpha = 0.56f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "当前筛选条件下没有匹配的子代理任务。",
                        modifier = Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = mutedTextColor
                    )
                }
            } else {
                sortedRuns.forEach { run ->
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = surfaceColor.copy(alpha = 0.56f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenRun(run) }
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = run.goal,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = subagentStatusLabel(run.status),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = subagentStatusColor(run.status)
                                )
                            }
                            Text(
                                text = "模型 ${run.model} · ${formatSubagentTimeRange(run)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = mutedTextColor
                            )
                            run.batchLabel?.takeIf { it.isNotBlank() }?.let { batchLabel ->
                                Text(
                                    text = buildString {
                                        append("编排 ")
                                        append(batchLabel)
                                        run.batchIndex?.let { index ->
                                            append(" · ")
                                            append(index)
                                            run.batchSize?.let { size ->
                                                append("/")
                                                append(size)
                                            }
                                        }
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                            run.statusMessage.takeIf { it.isNotBlank() }?.let { message ->
                                Text(
                                    text = message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            if (run.queuePosition != null || run.assignedSlot != null) {
                                Text(
                                    text = buildString {
                                        run.queuePosition?.let {
                                            append("队列第 $it 位")
                                        }
                                        run.assignedSlot?.let { slot ->
                                            if (isNotEmpty()) append(" · ")
                                            append(
                                                run.concurrencyLimit?.let { limit -> "槽位 $slot/$limit" }
                                                    ?: "槽位 $slot"
                                            )
                                        }
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                            Text(
                                text = buildString {
                                    append("工具 ${run.allowedTools.size}")
                                    if (run.retryCount > 0) {
                                        append(" · 重试 ${run.retryCount}")
                                    }
                                    if ((run.finishedAt ?: run.startedAt) >= run.startedAt) {
                                        append(" · ${formatSubagentDurationLabel(run)}")
                                    }
                                    if (run.usageSummary.totalTokens > 0) {
                                        append(" · Token ${run.usageSummary.totalTokens}")
                                    }
                                    if (run.usageSummary.resolvedEstimatedCostAmount() > 0) {
                                        append(
                                            " · ${
                                                formatCurrencyAmount(
                                                    run.usageSummary.resolvedEstimatedCostAmount(),
                                                    run.usageSummary.resolvedEstimatedCostCurrency()
                                                )
                                            }"
                                        )
                                    }
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = mutedTextColor
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

private enum class SubagentHistoryFilter(val label: String) {
    ALL("全部"),
    ACTIVE("运行中"),
    FAILED("失败/终止"),
    COMPLETED("已完成"),
    RETRIED("已重试")
}

private enum class SubagentHistorySortMode(val label: String) {
    LATEST("最近更新"),
    STATUS("状态优先"),
    TOKEN("Token"),
    COST("成本")
}

private fun matchesSubagentHistoryFilter(
    run: SubagentRunUi,
    filter: SubagentHistoryFilter
): Boolean {
    return when (filter) {
        SubagentHistoryFilter.ALL -> true
        SubagentHistoryFilter.ACTIVE -> run.status in setOf("pending_approval", "queued", "running", "summarizing", "cancelling")
        SubagentHistoryFilter.FAILED -> run.status in setOf("failed", "cancelled", "cancelling", "rejected")
        SubagentHistoryFilter.COMPLETED -> run.status == "completed"
        SubagentHistoryFilter.RETRIED -> run.retryCount > 0
    }
}

private fun sortSubagentRuns(
    runs: List<SubagentRunUi>,
    sortMode: SubagentHistorySortMode
): List<SubagentRunUi> {
    return when (sortMode) {
        SubagentHistorySortMode.LATEST -> runs.sortedByDescending { maxOf(it.finishedAt ?: 0L, it.startedAt) }
        SubagentHistorySortMode.STATUS -> runs.sortedWith(
            compareByDescending<SubagentRunUi> { subagentStatusPriority(it.status) }
                .thenByDescending { maxOf(it.finishedAt ?: 0L, it.startedAt) }
        )
        SubagentHistorySortMode.TOKEN -> runs.sortedWith(
            compareByDescending<SubagentRunUi> { it.usageSummary.totalTokens }
                .thenByDescending { maxOf(it.finishedAt ?: 0L, it.startedAt) }
        )
        SubagentHistorySortMode.COST -> runs.sortedWith(
            compareByDescending<SubagentRunUi> { it.usageSummary.resolvedEstimatedCostAmount() }
                .thenByDescending { maxOf(it.finishedAt ?: 0L, it.startedAt) }
        )
    }
}

private fun subagentStatusPriority(status: String): Int {
    return when (status) {
        "pending_approval" -> 7
        "failed" -> 6
        "cancelling" -> 5
        "cancelled" -> 4
        "rejected" -> 4
        "running" -> 3
        "summarizing" -> 2
        "queued" -> 1
        "completed" -> 0
        else -> -1
    }
}

private fun formatSubagentDurationLabel(run: SubagentRunUi): String {
    val end = run.finishedAt ?: System.currentTimeMillis()
    val durationSeconds = ((end - run.startedAt).coerceAtLeast(0L)) / 1000
    return when {
        durationSeconds < 60 -> "耗时 ${durationSeconds}s"
        durationSeconds < 3600 -> "耗时 ${durationSeconds / 60}m"
        else -> "耗时 ${durationSeconds / 3600}h"
    }
}

@Composable
private fun FileChangeDetailSheet(
    record: FileChangeRecordUi,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "文件差异",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = record.path,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "${formatFileChangeOperation(record.operation)} · ${formatTimestamp(record.changedAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FileChangeDetailSection(
                title = "Diff 预览",
                content = record.diffPreview.ifBlank { "(暂无 diff)" }
            )
            FileChangeDetailSection(
                title = "改前内容",
                content = record.beforeContent ?: "(文件原本不存在或无法读取)"
            )
            FileChangeDetailSection(
                title = "改后内容",
                content = record.afterContent ?: "(文件已删除或没有新内容)"
            )
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun FileChangeDetailSection(
    title: String,
    content: String
) {
    val surfaceColor = rememberMurongSurfaceColor()
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = surfaceColor.copy(alpha = 0.52f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            NativeSelectableScrollableText(
                text = content,
                modifier = Modifier.fillMaxWidth(),
                monospace = true,
                fontSizeSp = 12f,
                maxHeight = 360.dp
            )
        }
    }
}

@Composable
private fun NativeSelectableScrollableText(
    text: String,
    modifier: Modifier = Modifier,
    monospace: Boolean = false,
    fontSizeSp: Float = 12f,
    maxHeight: androidx.compose.ui.unit.Dp = 320.dp
) {
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    AndroidView(
        modifier = modifier.heightIn(max = maxHeight),
        factory = { context ->
            ScrollView(context).apply {
                overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                isVerticalScrollBarEnabled = true
                clipToPadding = false
                addView(
                    TextView(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                        setTextIsSelectable(true)
                        setPadding(0, 0, 0, 0)
                        includeFontPadding = false
                        setLineSpacing(0f, 1.15f)
                        textSize = fontSizeSp
                        if (monospace) {
                            typeface = android.graphics.Typeface.MONOSPACE
                        }
                    }
                )
                setOnTouchListener { view, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN,
                        MotionEvent.ACTION_MOVE -> view.parent?.requestDisallowInterceptTouchEvent(true)

                        MotionEvent.ACTION_UP,
                        MotionEvent.ACTION_CANCEL -> view.parent?.requestDisallowInterceptTouchEvent(false)
                    }
                    false
                }
            }
        },
        update = { scrollView ->
            val textView = scrollView.getChildAt(0) as TextView
            textView.text = text
            textView.setTextColor(textColor)
            textView.textSize = fontSizeSp
            textView.typeface = if (monospace) {
                android.graphics.Typeface.MONOSPACE
            } else {
                android.graphics.Typeface.DEFAULT
            }
        }
    )
}

@Composable
private fun SelectableMarkdownContent(
    text: String,
    fontSize: Int,
    modifier: Modifier = Modifier,
    maxHeight: androidx.compose.ui.unit.Dp = 320.dp
) {
    val scrollState = rememberScrollState()
    SelectionContainer {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .verticalScroll(scrollState)
        ) {
            MarkdownText(
                text = text,
                modifier = Modifier.fillMaxWidth(),
                fontSize = fontSize
            )
        }
    }
}

private fun shouldUseNativeScrollableSelectableText(text: String): Boolean {
    if (text.length >= 1200) return true
    return text.lineSequence().take(20).count() >= 12
}

@Composable
private fun CompressionHistorySheet(
    snapshots: List<ContextCompressionUi>,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "压缩摘要历史",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            snapshots.sortedByDescending { it.version }.forEach { snapshot ->
                CompressionHistoryItem(snapshot = snapshot)
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun CompressionHistoryItem(snapshot: ContextCompressionUi) {
    val surfaceColor = rememberMurongSurfaceColor()
    val mutedTextColor = rememberMurongMutedTextColor()
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = surfaceColor.copy(alpha = 0.56f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "摘要 V${snapshot.version}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = if (snapshot.active) "当前启用" else "历史版本",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (snapshot.active) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        mutedTextColor
                    }
                )
            }
            Text(
                text = "覆盖 ${snapshot.sourceMessageCount} 条历史消息 · ${formatTimestamp(snapshot.createdAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = mutedTextColor
            )
            Text(
                text = snapshot.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 8,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SubagentMetricChip(
    label: String,
    color: Color
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = color.copy(alpha = 0.12f)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

private data class CompressionSuggestionState(
    val reason: String,
    val preview: ContextCompressionPreviewUi
)

@Composable
private fun CompressionSuggestionHint(
    suggestion: CompressionSuggestionState,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    compressing: Boolean,
    onCompress: () -> Unit,
    onDismiss: () -> Unit
) {
    CompactTopHint(
        badge = "压",
        title = "建议压缩上下文",
        message = buildString {
            append(suggestion.reason)
            append("\n")
            append("预计可压缩 ${suggestion.preview.compressibleMessageCount} 条历史消息，保留最近 ${suggestion.preview.recentMessageCount} 条原文继续对话。")
            append("\n")
            append("预计从 ${formatTokenCount(suggestion.preview.estimatedCurrentContextTokens)} Token 降到 ${formatTokenCount(suggestion.preview.estimatedCompressedContextTokens)} Token，约减少 ${formatTokenCount(suggestion.preview.estimatedTokensSaved)} Token（-${suggestion.preview.estimatedReductionPercent}%）。")
        },
        expanded = expanded,
        onToggleExpanded = onToggleExpanded,
        actionLabel = if (compressing) null else "立即压缩",
        onAction = if (compressing) null else onCompress,
        secondaryActionLabel = if (compressing) null else "稍后提醒",
        onSecondaryAction = if (compressing) null else onDismiss
    )
}

@Composable
private fun CompressionActionHint(
    title: String,
    message: String,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null
) {
    CompactTopHint(
        badge = "压",
        title = title,
        message = message,
        expanded = expanded,
        onToggleExpanded = onToggleExpanded,
        actionLabel = actionLabel,
        onAction = onAction,
        secondaryActionLabel = secondaryActionLabel,
        onSecondaryAction = onSecondaryAction
    )
}

private fun buildCompressionSuggestion(state: SessionState): CompressionSuggestionState? {
    if (state.compressionSnapshot?.active == true) return null
    val preview = estimateContextCompressionPreview(state) ?: return null
    val conversationMessageCount = state.messages.count { message ->
        (message.role == "user" || message.role == "assistant") &&
            (message.content.isNotBlank() || !message.reasoning.isNullOrBlank())
    }

    val shouldSuggest = conversationMessageCount >= COMPRESSION_SUGGESTION_MESSAGE_THRESHOLD ||
        state.usageSummary.totalTokens >= COMPRESSION_SUGGESTION_TOKEN_THRESHOLD
    if (!shouldSuggest) return null

    val reason = when {
        conversationMessageCount >= COMPRESSION_SUGGESTION_MESSAGE_THRESHOLD &&
            state.usageSummary.totalTokens >= COMPRESSION_SUGGESTION_TOKEN_THRESHOLD ->
            "当前会话已经较长，消息轮次和累计 Token 都在持续增长。"
        conversationMessageCount >= COMPRESSION_SUGGESTION_MESSAGE_THRESHOLD ->
            "当前会话消息已经较多，继续累积会让后续上下文越来越重。"
        else ->
            "当前会话累计 Token 已较高，建议提前压缩摘要来降低后续上下文负担。"
    }

    return CompressionSuggestionState(
        reason = reason,
        preview = preview
    )
}
private const val COMPRESSION_SUGGESTION_MESSAGE_THRESHOLD = 16
private const val COMPRESSION_SUGGESTION_TOKEN_THRESHOLD = 12000

private fun formatTokenCount(value: Int): String {
    return when {
        value >= 10000 -> "${"%.1f".format(value / 1000f)}k"
        else -> value.toString()
    }
}

private fun formatFileChangeOperation(operation: String): String {
    return when (operation) {
        "create" -> "新建"
        "delete" -> "删除"
        "rollback" -> "回滚"
        "search_replace" -> "搜索替换"
        "write" -> "写入"
        else -> operation
    }
}

private fun isMessageListNearBottom(listState: LazyListState, threshold: Int = 2): Boolean {
    val layoutInfo = listState.layoutInfo
    val totalItems = layoutInfo.totalItemsCount
    if (totalItems == 0) return true
    val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return true
    return lastVisibleIndex >= totalItems - threshold
}
