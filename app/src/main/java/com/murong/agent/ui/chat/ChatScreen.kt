@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.murong.agent.ui.chat

import android.content.ClipData
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.murong.agent.core.mcp.McpServerConfig
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.murong.agent.core.loop.ChatMessageUi
import com.murong.agent.core.loop.SubagentTimelineEntryUi
import com.murong.agent.core.loop.AutoRouteAction
import com.murong.agent.core.loop.AutoRouteDecisionUi
import com.murong.agent.core.loop.ClarificationRequestUi
import com.murong.agent.core.loop.ClarificationSource
import com.murong.agent.core.loop.ConversationCheckpointUi
import com.murong.agent.core.loop.FileChangeRecordUi
import com.murong.agent.core.loop.FileMentionUi
import com.murong.agent.core.loop.ContextCompressionUi
import com.murong.agent.core.loop.ContextCompressionPreviewUi
import com.murong.agent.core.loop.MessageImageAttachmentUi
import com.murong.agent.core.loop.PendingImageAttachmentUi
import com.murong.agent.core.loop.ProjectKnowledgeSnapshotUi
import com.murong.agent.core.loop.SessionState
import com.murong.agent.core.loop.SessionSummary
import com.murong.agent.core.loop.SubagentBatchUi
import com.murong.agent.core.loop.SubagentRunUi
import com.murong.agent.core.loop.WorkflowPlanStatusUi
import com.murong.agent.core.loop.WorkflowPlanUi
import com.murong.agent.core.loop.UsageSummarySnapshot
import com.murong.agent.core.loop.estimateContextCompressionPreview
import com.murong.agent.core.loop.MIN_MESSAGES_FOR_COMPRESSION
import com.murong.agent.core.loop.MIN_MESSAGES_TO_COMPRESS
import com.murong.agent.core.loop.RECENT_MESSAGES_TO_KEEP
import com.murong.agent.core.loop.WEB_FETCH_RESULT_PREFIX
import com.murong.agent.core.config.WorkflowExecutionMode
import com.murong.agent.ui.MurongDialog
import com.murong.agent.ui.MurongGlassSurface
import com.murong.agent.ui.MurongOutlinedActionButton
import com.murong.agent.ui.MurongPopupSurface
import com.murong.agent.ui.MurongTagButton
import com.murong.agent.ui.MarkdownText
import com.murong.agent.ui.ProjectKnowledgeOutlineUi
import com.murong.agent.ui.buildProjectKnowledgeOutlines
import com.murong.agent.ui.rememberMurongAccentColor
import com.murong.agent.ui.rememberMurongChromeColor
import com.murong.agent.ui.rememberMurongMutedTextColor
import com.murong.agent.ui.rememberMurongSurfaceColor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import org.json.JSONObject

@Composable
fun ChatScreen(
    state: SessionState,
    isScreenActive: Boolean = true,
    projectKnowledgePaths: List<String> = emptyList(),
    onSend: (String, List<FileMentionUi>, List<PendingImageAttachmentUi>) -> Unit,
    onStopSending: () -> Unit = {},
    onClear: () -> Unit,
    onNewSession: () -> Unit = {},
    title: String = "新对话",
    hasApiKey: Boolean = false,
    workflowExecutionMode: WorkflowExecutionMode = WorkflowExecutionMode.SINGLE_PASS,
    autoRouteBeforeExecution: Boolean = true,
    onNavigateToSettings: () -> Unit = {},
    onEditMessage: (Long) -> Boolean = { false },
    onCompressContext: () -> Unit = {},
    onRollbackFileCheckpoint: (String) -> Unit = {},
    onGeneratePlan: (String, List<FileMentionUi>) -> Unit = { _, _ -> },
    onExecutePlan: () -> Unit = {},
    onDismissPlan: () -> Unit = {},
    onSubmitClarificationAnswer: (String) -> Unit = {},
    onDismissClarification: () -> Unit = {},
    onSearchFiles: (String) -> List<FileMentionUi> = { emptyList() },
    onRetrySubagent: (String) -> Unit = {},
    onCancelSubagent: (String) -> Unit = {},
    onDismissProposal: (msgId: Long) -> Unit = {}
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
    var selectedCheckpoint by remember(state.sessionId) { mutableStateOf<ConversationCheckpointUi?>(null) }
    var selectedFileChange by remember(state.sessionId) { mutableStateOf<FileChangeRecordUi?>(null) }
    val selectedMentions = remember(state.sessionId) { mutableStateListOf<FileMentionUi>() }
    val selectedImages = remember(state.sessionId) { mutableStateListOf<PendingImageAttachmentUi>() }
    val recentMentions = remember(state.sessionId) { mutableStateListOf<FileMentionUi>() }
    var showMentionPicker by remember(state.sessionId) { mutableStateOf(false) }
    var mentionQuery by remember(state.sessionId) { mutableStateOf("") }
    var previewImages by remember(state.sessionId) { mutableStateOf<List<ImagePreviewItemUi>>(emptyList()) }
    var previewImageIndex by remember(state.sessionId) { mutableIntStateOf(0) }
    var inputHasFocus by remember(state.sessionId) { mutableStateOf(false) }
    val projectKnowledgeMentions = remember(state.projectPath, projectKnowledgePaths) {
        buildProjectKnowledgeMentions(state.projectPath, projectKnowledgePaths)
    }
    val projectKnowledgeOutlines = remember(state.projectPath, projectKnowledgePaths) {
        buildProjectKnowledgeOutlines(state.projectPath, projectKnowledgePaths)
    }
    val projectKnowledgeOutlineMap = remember(projectKnowledgeOutlines) {
        projectKnowledgeOutlines.associateBy { it.path }
    }
    val projectKnowledgeSnapshotNamesByPath = remember(state.projectKnowledgeSnapshots) {
        buildProjectKnowledgeSnapshotNamesByPath(state.projectKnowledgeSnapshots)
    }
    val projectKnowledgePathSet = remember(projectKnowledgeMentions) {
        projectKnowledgeMentions.map { it.path }.toSet()
    }
    val activeInlineMentionQuery = remember(inputText, showMentionPicker) {
        if (showMentionPicker) null else extractActiveMentionQuery(inputText)
    }
    val inlineMentionResults = remember(activeInlineMentionQuery, state.sessionId, projectKnowledgeMentions) {
        when {
            activeInlineMentionQuery == null -> emptyList()
            activeInlineMentionQuery.isBlank() -> emptyList()
            else -> mergeProjectKnowledgeMentions(
                projectKnowledgeMentions = projectKnowledgeMentions,
                searchResults = onSearchFiles(activeInlineMentionQuery),
                query = activeInlineMentionQuery
            )
        }
    }
    val recentImageAttachments = remember(state.messages, state.sessionId) {
        state.messages
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
    val context = LocalContext.current
    val compressionSuggestion = remember(
        state.messages.size,
        state.usageSummary.totalTokens,
        state.compressionSnapshot?.id,
        state.compressionSnapshot?.active
    ) {
        buildCompressionSuggestion(state)
    }
    var dismissedCompressionSuggestionAtCount by remember(state.sessionId) {
        mutableStateOf<Int?>(null)
    }
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
        showCompressionHistory = false
        showFileChangeHistory = false
        selectedCheckpoint = null
        selectedFileChange = null
        previewImages = emptyList()
        previewImageIndex = 0
        showMentionPicker = false
    }

    val itemCount = state.messages.size
    suspend fun scrollMessagesToBottom() {
        if (itemCount > 0) {
            listState.scrollToItem(itemCount - 1)
        }
    }

    // 新消息时自动滚动到底部
    LaunchedEffect(itemCount) {
        if (!isScreenActive) return@LaunchedEffect
        // #region debug-point U:chat-auto-scroll
        reportGitBackChatFlashChatDebug(
            hypothesisId = "U1",
            location = "ChatScreen.kt:autoScrollOnItemCount",
            msg = "[DEBUG] chat auto scroll triggered by itemCount",
            data = JSONObject()
                .put("isScreenActive", isScreenActive)
                .put("sessionId", state.sessionId)
                .put("itemCount", itemCount)
                .put("firstVisibleItemIndex", listState.firstVisibleItemIndex)
                .put("firstVisibleItemScrollOffset", listState.firstVisibleItemScrollOffset)
        )
        // #endregion
        scrollMessagesToBottom()
    }

    LaunchedEffect(
        inputHasFocus,
        selectedImages.size,
        selectedMentions.size
    ) {
        if (isScreenActive && inputHasFocus) {
            // #region debug-point U:chat-focus-scroll
            reportGitBackChatFlashChatDebug(
                hypothesisId = "U1",
                location = "ChatScreen.kt:autoScrollOnFocus",
                msg = "[DEBUG] chat auto scroll triggered by focus",
                data = JSONObject()
                    .put("isScreenActive", isScreenActive)
                    .put("sessionId", state.sessionId)
                    .put("inputHasFocus", inputHasFocus)
                    .put("selectedImages", selectedImages.size)
                    .put("selectedMentions", selectedMentions.size)
                    .put("firstVisibleItemIndex", listState.firstVisibleItemIndex)
                    .put("firstVisibleItemScrollOffset", listState.firstVisibleItemScrollOffset)
            )
            // #endregion
            scrollMessagesToBottom()
        }
    }

    LaunchedEffect(isScreenActive) {
        // #region debug-point D:chat-screen-active
        reportGitBackChatFlashChatDebug(
            hypothesisId = "D",
            location = "ChatScreen.kt:isScreenActive",
            msg = "[DEBUG] chat screen active changed",
            data = JSONObject()
                .put("isScreenActive", isScreenActive)
                .put("sessionId", state.sessionId)
                .put("messageCount", state.messages.size)
                .put("showMentionPicker", showMentionPicker)
                .put("showSubagentHistory", showSubagentHistory)
                .put("showCompressionHistory", showCompressionHistory)
                .put("showFileChangeHistory", showFileChangeHistory)
        )
        // #endregion
        if (!isScreenActive) {
            dismissTransientOverlays()
        }
    }

    LaunchedEffect(
        isScreenActive,
        state.sessionId,
        state.messages.size,
        state.isProcessing,
        state.sessionTitle,
        state.projectPath
    ) {
        // #region debug-point D:chat-render-snapshot
        reportGitBackChatFlashChatDebug(
            hypothesisId = "D",
            location = "ChatScreen.kt:renderSnapshot",
            msg = "[DEBUG] chat render snapshot",
            data = JSONObject()
                .put("isScreenActive", isScreenActive)
                .put("sessionId", state.sessionId)
                .put("sessionTitle", state.sessionTitle)
                .put("messageCount", state.messages.size)
                .put("isProcessing", state.isProcessing)
                .put("projectPath", state.projectPath ?: JSONObject.NULL)
                .put("showMentionPicker", showMentionPicker)
                .put("showSubagentHistory", showSubagentHistory)
                .put("showCompressionHistory", showCompressionHistory)
                .put("showFileChangeHistory", showFileChangeHistory)
        )
        // #endregion
    }

    LaunchedEffect(
        isScreenActive,
        state.sessionId,
        listState.firstVisibleItemIndex,
        listState.firstVisibleItemScrollOffset
    ) {
        // #region debug-point U:chat-list-position
        reportGitBackChatFlashChatDebug(
            hypothesisId = "U1",
            location = "ChatScreen.kt:listPosition",
            msg = "[DEBUG] chat list position snapshot",
            data = JSONObject()
                .put("isScreenActive", isScreenActive)
                .put("sessionId", state.sessionId)
                .put("itemCount", itemCount)
                .put("firstVisibleItemIndex", listState.firstVisibleItemIndex)
                .put("firstVisibleItemScrollOffset", listState.firstVisibleItemScrollOffset)
                .put("showMentionPicker", showMentionPicker)
                .put("showSubagentHistory", showSubagentHistory)
        )
        // #endregion
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .imePadding()
    ) {
        state.projectPath?.takeIf { it.isNotBlank() }?.let { projectPath ->
            CurrentProjectBar(
                projectPath = projectPath,
                knowledgeFileCount = projectKnowledgeMentions.size
            )
        }
        if (workflowExecutionMode == WorkflowExecutionMode.SINGLE_PASS) {
            WorkflowExecutionStatusBar(autoRouteBeforeExecution = autoRouteBeforeExecution)
            if (autoRouteBeforeExecution && state.autoRoutingInProgress) {
                AutoRoutingStatusBar()
            } else if (autoRouteBeforeExecution) {
                state.lastAutoRouteDecision?.let { decision ->
                    AutoRouteDecisionStatusBar(decision = decision)
                }
            }
        }
        state.lastWorkflowFallback?.let { fallback ->
            WorkflowFallbackStatusBar(message = fallback.message)
        }
        if (state.workflowPlanningInProgress) {
            WorkflowPlanningStatusBar()
        }
        if (state.clarificationInProgress) {
            ClarificationStatusBar()
        }
        state.pendingWorkflowPlan?.let { plan ->
            WorkflowPlanCard(
                plan = plan,
                isProcessing = state.isProcessing,
                onExecute = onExecutePlan,
                onDismiss = onDismissPlan
            )
        }
        state.pendingClarificationRequest?.let { request ->
            ClarificationCard(
                request = request,
                isProcessing = state.isProcessing,
                onSubmit = onSubmitClarificationAnswer,
                onDismiss = onDismissClarification
            )
        }
        state.compressionSnapshot?.let { snapshot ->
            CompressionStatusBar(
                version = snapshot.version,
                totalVersions = state.compressionSnapshots.size,
                summary = snapshot.summary,
                sourceMessageCount = snapshot.sourceMessageCount,
                active = snapshot.active,
                createdAt = snapshot.createdAt,
                onClick = { showCompressionHistory = true }
            )
        }
        if (state.fileChanges.isNotEmpty()) {
            FileChangeStatusBar(
                latestCheckpoint = state.checkpoints.firstOrNull(),
                totalCount = state.fileChanges.size,
                batchCount = state.checkpoints.size,
                onClick = { showFileChangeHistory = true }
            )
        }
        if (
            compressionSuggestion != null &&
            !state.isProcessing &&
            (dismissedCompressionSuggestionAtCount == null ||
                state.messages.size > dismissedCompressionSuggestionAtCount!!)
        ) {
            CompressionSuggestionBar(
                suggestion = compressionSuggestion,
                onCompress = onCompressContext,
                onDismiss = {
                    dismissedCompressionSuggestionAtCount = state.messages.size
                }
            )
        }
        if (state.subagentRuns.isNotEmpty()) {
            SubagentStatusBar(
                runs = state.subagentRuns,
                onClick = { showSubagentHistory = true }
            )
        }

        // 消息列表
        if (state.messages.isEmpty() && !state.isProcessing) {
            // 空状态 — 显示欢迎/引导
            WelcomeView(
                hasApiKey = hasApiKey,
                onNavigateToSettings = onNavigateToSettings,
                modifier = Modifier.weight(1f)
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                state = listState,
                contentPadding = PaddingValues(top = 6.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(state.messages, key = { it.id }) { msg ->
                    val subagentRun = msg.subagentRunId?.let { runId ->
                        state.subagentRuns.firstOrNull { it.runId == runId }
                    }
                    val subagentBatch = msg.subagentBatchId?.let { batchId ->
                        state.subagentBatches.firstOrNull { it.batchId == batchId }
                    }
                    MessageBubble(
                        msg = msg,
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

                if (state.isProcessing && !hasStreamingMessage(state.messages)) {
                    item {
                        LoadingIndicator()
                    }
                }
            }
        } // end else

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
            onSend = {
                if ((inputText.isNotBlank() || selectedImages.isNotEmpty()) && !state.isProcessing) {
                    val sentText = inputText.trim()
                    if (sentText.isNotBlank()) {
                        inputHistory.removeAll { it == sentText }
                        inputHistory.add(sentText)
                        while (inputHistory.size > 50) {
                            inputHistory.removeAt(0)
                        }
                    }
                    onSend(sentText, selectedMentions.toList(), selectedImages.toList())
                    inputText = ""
                    inputHistoryIndex = -1
                    inputDraftBeforeHistory = ""
                    editingMessageId = null
                    selectedMentions.clear()
                    selectedImages.clear()
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
            onPlan = {
                if (inputText.isNotBlank() && selectedImages.isEmpty() && !state.isProcessing) {
                    onGeneratePlan(inputText.trim(), selectedMentions.toList())
                }
            },
            onCaptureImage = {
                if (!state.isProcessing) {
                    cameraPreviewLauncher.launch(null)
                }
            },
            onPickImages = {
                if (!state.isProcessing) {
                    imagePickerLauncher.launch("image/*")
                }
            },
            onMention = {
                mentionQuery = extractActiveMentionQuery(inputText) ?: ""
                showMentionPicker = true
            },
            canRecallPrevious = inputHistory.isNotEmpty(),
            canRecallNext = inputHistoryIndex != -1,
            canSend = inputText.isNotBlank() || selectedImages.isNotEmpty(),
            allowStructuredActions = selectedImages.isEmpty(),
            enabled = true,
            isSending = state.isProcessing,
            onStopSending = onStopSending
        )
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
            initialResults = mergeProjectKnowledgeMentions(
                projectKnowledgeMentions = projectKnowledgeMentions,
                searchResults = onSearchFiles(mentionQuery),
                query = mentionQuery
            ),
            recentMentions = recentMentions.toList(),
            knowledgePaths = projectKnowledgePathSet,
            knowledgeOutlines = projectKnowledgeOutlineMap,
            snapshotNamesByPath = projectKnowledgeSnapshotNamesByPath,
            onQueryChange = { mentionQuery = it },
            onSearch = { query ->
                mergeProjectKnowledgeMentions(
                    projectKnowledgeMentions = projectKnowledgeMentions,
                    searchResults = onSearchFiles(query),
                    query = query
                )
            },
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
    if (showFileChangeHistory && state.fileChanges.isNotEmpty()) {
        FileChangeBatchSheet(
            checkpoints = state.checkpoints,
            records = state.fileChanges,
            onDismiss = { showFileChangeHistory = false },
            onOpenCheckpoint = { checkpoint ->
                selectedCheckpoint = checkpoint
                showFileChangeHistory = false
            }
        )
    }
    selectedCheckpoint?.let { checkpoint ->
        FileChangeBatchDetailSheet(
            checkpoint = checkpoint,
            records = state.fileChanges.filter { it.checkpointId == checkpoint.id },
            onDismiss = { selectedCheckpoint = null },
            onRollbackCheckpoint = {
                selectedCheckpoint = null
                onRollbackFileCheckpoint(checkpoint.id)
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
}

// #region debug-point D:chat-debug-reporter
private const val ENABLE_REASONIX_BACK_DEBUG_REPORTS = true

private fun reportGitBackChatFlashChatDebug(
    hypothesisId: String,
    location: String,
    msg: String,
    data: JSONObject
) {
    if (!ENABLE_REASONIX_BACK_DEBUG_REPORTS) return
    Thread {
        runCatching {
            val connection = (URL("http://192.168.2.3:7777/event").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 1200
                readTimeout = 1200
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            val payload = JSONObject()
                .put("sessionId", "app-launch-crash")
                .put("runId", "pre-fix")
                .put("hypothesisId", hypothesisId)
                .put("location", location)
                .put("msg", msg)
                .put("data", data)
                .put("ts", System.currentTimeMillis())
                .toString()
            connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            runCatching { connection.inputStream.use { input -> while (input.read() != -1) {} } }
            connection.disconnect()
        }
    }.start()
}
// #endregion

@Composable
private fun WorkflowExecutionStatusBar(autoRouteBeforeExecution: Boolean) {
    WorkflowStatusStrip(
        title = "执行偏好",
        message = if (autoRouteBeforeExecution) {
            "单次工作流优先，发送前会自动判断直执、计划或澄清，尽量减少上下文污染。"
        } else {
            "单次工作流优先，但已关闭发送前自动分流，发送后会直接执行。"
        }
    )
}

@Composable
private fun WorkflowPlanningStatusBar() {
    WorkflowStatusStrip(
        title = "执行计划",
        message = "正在生成执行计划，稍后可确认后一次性执行。"
    )
}

@Composable
private fun AutoRoutingStatusBar() {
    WorkflowStatusStrip(
        title = "自动分流",
        message = "正在自动判断本次输入更适合直接执行、先出计划，还是先澄清。"
    )
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
private fun ClarificationStatusBar() {
    WorkflowStatusStrip(
        title = "澄清问题",
        message = "正在生成澄清问题，回答后会继续执行。"
    )
}

@Composable
private fun WorkflowStatusStrip(
    title: String,
    message: String
) {
    val accent = rememberMurongAccentColor()
    val mutedTextColor = rememberMurongMutedTextColor()
    val chromeColor = rememberMurongChromeColor()
    MurongGlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(20.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        surfaceColorOverride = chromeColor.copy(alpha = 0.66f)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = accent
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = mutedTextColor
        )
    }
}

@Composable
private fun WorkflowPlanCard(
    plan: WorkflowPlanUi,
    isProcessing: Boolean,
    onExecute: () -> Unit,
    onDismiss: () -> Unit
) {
    var showRawPlan by remember(plan.id) { mutableStateOf(false) }
    val accent = rememberMurongAccentColor()
    val surfaceColor = rememberMurongSurfaceColor()
    val chromeColor = rememberMurongChromeColor()
    val mutedTextColor = rememberMurongMutedTextColor()
    val progress = remember(plan.steps.size, plan.currentStepIndex) {
        if (plan.steps.isEmpty()) 0f else plan.currentStepIndex.toFloat() / plan.steps.size.toFloat()
    }
    MurongGlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        shape = MaterialTheme.shapes.large,
        contentPadding = PaddingValues(14.dp),
        surfaceColorOverride = chromeColor.copy(alpha = 0.72f)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                        text = "执行计划",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = plan.goal,
                        style = MaterialTheme.typography.bodySmall,
                        color = mutedTextColor,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                WorkflowPlanStatusBadge(status = plan.status)
            }
            if (plan.stageLabels.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "阶段状态",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        plan.stageLabels.forEachIndexed { index, stage ->
                            WorkflowPlanStageChip(
                                label = stage,
                                isCurrent = index == plan.currentStageIndex,
                                isCompleted = index < plan.currentStageIndex ||
                                    plan.status == WorkflowPlanStatusUi.COMPLETED
                            )
                        }
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "步骤进度",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "${plan.currentStepIndex}/${plan.steps.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = mutedTextColor
                    )
                }
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Text(
                text = plan.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            MurongGlassSurface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                surfaceColorOverride = surfaceColor.copy(alpha = 0.70f)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "下一步提示",
                        style = MaterialTheme.typography.labelMedium,
                        color = accent
                    )
                    Text(
                        text = plan.nextStepHint.ifBlank { "先确认目标与边界，再继续执行。" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                plan.steps.forEachIndexed { index, step ->
                    WorkflowPlanStepRow(
                        index = index,
                        step = step,
                        status = workflowPlanStepStatus(plan = plan, stepIndex = index)
                    )
                }
            }
            if (plan.mentionedFiles.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "关联文件",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        plan.mentionedFiles.forEach { mention ->
                            Surface(
                                shape = MaterialTheme.shapes.medium,
                                color = surfaceColor.copy(alpha = 0.68f)
                            ) {
                                Text(
                                    text = mention.displayPath,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }
            }
            if (plan.rawPlan.isNotBlank()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    MurongTagButton(
                        text = if (showRawPlan) "收起原始计划" else "展开原始计划",
                        onClick = { showRawPlan = !showRawPlan }
                    )
                    AnimatedVisibility(visible = showRawPlan) {
                        MurongGlassSurface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                            contentPadding = PaddingValues(12.dp),
                            surfaceColorOverride = surfaceColor.copy(alpha = 0.68f)
                        ) {
                            SelectionContainer {
                                Text(
                                    text = plan.rawPlan,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MurongOutlinedActionButton(
                    text = "关闭",
                    onClick = onDismiss,
                    enabled = !isProcessing,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = onExecute,
                    enabled = !isProcessing,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        when (plan.status) {
                            WorkflowPlanStatusUi.READY -> "按计划执行"
                            WorkflowPlanStatusUi.EXECUTING -> "继续观察"
                            WorkflowPlanStatusUi.BLOCKED -> "继续执行"
                            WorkflowPlanStatusUi.COMPLETED -> "再次执行"
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun WorkflowPlanStatusBadge(status: WorkflowPlanStatusUi) {
    val accent = rememberMurongAccentColor()
    val chromeColor = rememberMurongChromeColor()
    val containerColor = when (status) {
        WorkflowPlanStatusUi.READY -> accent.copy(alpha = 0.18f)
        WorkflowPlanStatusUi.EXECUTING -> chromeColor.copy(alpha = 0.72f)
        WorkflowPlanStatusUi.BLOCKED -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.18f)
        WorkflowPlanStatusUi.COMPLETED -> accent.copy(alpha = 0.14f)
    }
    val contentColor = when (status) {
        WorkflowPlanStatusUi.READY -> MaterialTheme.colorScheme.primary
        WorkflowPlanStatusUi.EXECUTING -> MaterialTheme.colorScheme.secondary
        WorkflowPlanStatusUi.BLOCKED -> MaterialTheme.colorScheme.tertiary
        WorkflowPlanStatusUi.COMPLETED -> accent
    }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = containerColor
    ) {
        Text(
            text = workflowPlanStatusText(status),
            style = MaterialTheme.typography.labelMedium,
            color = contentColor,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun WorkflowPlanStageChip(
    label: String,
    isCurrent: Boolean,
    isCompleted: Boolean
) {
    val accent = rememberMurongAccentColor()
    val surfaceColor = rememberMurongSurfaceColor()
    val mutedTextColor = rememberMurongMutedTextColor()
    val containerColor = when {
        isCurrent -> accent.copy(alpha = 0.18f)
        isCompleted -> surfaceColor.copy(alpha = 0.72f)
        else -> surfaceColor.copy(alpha = 0.48f)
    }
    val contentColor = when {
        isCurrent -> MaterialTheme.colorScheme.primary
        isCompleted -> MaterialTheme.colorScheme.secondary
        else -> mutedTextColor
    }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = containerColor
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = contentColor,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun WorkflowPlanStepRow(
    index: Int,
    step: String,
    status: WorkflowPlanStepStatus
) {
    val surfaceColor = rememberMurongSurfaceColor()
    val mutedTextColor = rememberMurongMutedTextColor()
    val badgeColor = when (status) {
        WorkflowPlanStepStatus.COMPLETED -> Color(0xFF2E7D32)
        WorkflowPlanStepStatus.CURRENT -> MaterialTheme.colorScheme.primary
        WorkflowPlanStepStatus.PENDING -> mutedTextColor
    }
    val containerColor = when (status) {
        WorkflowPlanStepStatus.COMPLETED -> Color(0x142E7D32)
        WorkflowPlanStepStatus.CURRENT -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f)
        WorkflowPlanStepStatus.PENDING -> surfaceColor.copy(alpha = 0.46f)
    }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = containerColor,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top
        ) {
            Text(
                text = when (status) {
                    WorkflowPlanStepStatus.COMPLETED -> "已完成"
                    WorkflowPlanStepStatus.CURRENT -> "当前"
                    WorkflowPlanStepStatus.PENDING -> "${index + 1}"
                },
                style = MaterialTheme.typography.labelSmall,
                color = badgeColor
            )
            Text(
                text = step,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

private enum class WorkflowPlanStepStatus {
    COMPLETED,
    CURRENT,
    PENDING
}

private fun workflowPlanStepStatus(
    plan: WorkflowPlanUi,
    stepIndex: Int
): WorkflowPlanStepStatus {
    return when (plan.status) {
        WorkflowPlanStatusUi.COMPLETED -> WorkflowPlanStepStatus.COMPLETED
        WorkflowPlanStatusUi.READY -> if (stepIndex == 0) {
            WorkflowPlanStepStatus.CURRENT
        } else {
            WorkflowPlanStepStatus.PENDING
        }
        WorkflowPlanStatusUi.EXECUTING,
        WorkflowPlanStatusUi.BLOCKED -> {
            val activeIndex = (plan.currentStepIndex - 1).coerceAtLeast(0)
            when {
                stepIndex < activeIndex -> WorkflowPlanStepStatus.COMPLETED
                stepIndex == activeIndex -> WorkflowPlanStepStatus.CURRENT
                else -> WorkflowPlanStepStatus.PENDING
            }
        }
    }
}

private fun workflowPlanStatusText(status: WorkflowPlanStatusUi): String {
    return when (status) {
        WorkflowPlanStatusUi.READY -> "待执行"
        WorkflowPlanStatusUi.EXECUTING -> "执行中"
        WorkflowPlanStatusUi.BLOCKED -> "已阻塞"
        WorkflowPlanStatusUi.COMPLETED -> "已完成"
    }
}

@Composable
private fun ClarificationCard(
    request: ClarificationRequestUi,
    isProcessing: Boolean,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var answer by remember(request.id) { mutableStateOf("") }
    val accent = rememberMurongAccentColor()
    val surfaceColor = rememberMurongSurfaceColor()
    val chromeColor = rememberMurongChromeColor()
    val mutedTextColor = rememberMurongMutedTextColor()
    MurongGlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        shape = MaterialTheme.shapes.large,
        contentPadding = PaddingValues(14.dp),
        surfaceColorOverride = chromeColor.copy(alpha = 0.70f)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = clarificationTitle(request.source),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            clarificationSubtitle(request.source)?.let { subtitle ->
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedTextColor
                )
            }
            Text(
                text = "第 ${request.turnIndex} 轮 / 最多 ${request.maxTurns} 轮",
                style = MaterialTheme.typography.labelMedium,
                color = accent
            )
            if (request.previousAnswers.isNotEmpty()) {
                MurongGlassSurface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    surfaceColorOverride = surfaceColor.copy(alpha = 0.70f)
                ) {
                    Text(
                        text = "已确认 ${request.previousAnswers.size} 条澄清信息，本轮会在此基础上继续追问最关键的缺口。",
                        style = MaterialTheme.typography.bodySmall,
                        color = mutedTextColor
                    )
                }
            }
            Text(
                text = request.question,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            OutlinedTextField(
                value = answer,
                onValueChange = { answer = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("输入你的补充回答") },
                enabled = !isProcessing,
                maxLines = 4
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MurongOutlinedActionButton(
                    text = "关闭",
                    onClick = onDismiss,
                    enabled = !isProcessing,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = { onSubmit(answer.trim()) },
                    enabled = !isProcessing && answer.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (request.turnIndex >= request.maxTurns) "继续执行" else "继续判断")
                }
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
    initialResults: List<FileMentionUi>,
    recentMentions: List<FileMentionUi>,
    knowledgePaths: Set<String>,
    knowledgeOutlines: Map<String, ProjectKnowledgeOutlineUi>,
    snapshotNamesByPath: Map<String, List<String>>,
    onQueryChange: (String) -> Unit,
    onSearch: (String) -> List<FileMentionUi>,
    onDismiss: () -> Unit,
    onSelect: (FileMentionUi) -> Unit
) {
    var localQuery by remember(query) { mutableStateOf(query) }
    val surfaceColor = rememberMurongSurfaceColor()
    val chromeColor = rememberMurongChromeColor()
    val mutedTextColor = rememberMurongMutedTextColor()
    val results = remember(localQuery, initialResults) {
        if (localQuery == query) initialResults else onSearch(localQuery)
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
                            text = "当前没有匹配文件，先进入项目任务并确保项目目录已绑定。",
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
            if (isKnowledge || matchReason != null) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (isKnowledge) {
                        MentionKnowledgeBadge()
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
private fun WebFetchResultCard(result: WebFetchResultUi) {
    val canExpand = result.excerpt.isNotBlank() || result.content.isNotBlank()
    var expanded by remember(result.url, result.content, result.excerpt) { mutableStateOf(false) }
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
        AnimatedVisibility(visible = expanded) {
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
                    SelectionContainer {
                        Text(
                            text = msg.content,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        } else if (isToolExec) {
            val toolExecution = remember(msg.content) {
                parseToolExecutionMessage(msg.content)
            }
            val toolResult = remember(msg.content) {
                parseToolResultMessage(msg.content)
            }
            val webFetchResult = remember(msg.content) {
                parseWebFetchToolMessage(msg.content)
            }
            val quietToolCard = toolExecution?.isQuiet == true ||
                toolResult?.isQuiet == true ||
                webFetchResult != null
            MurongGlassSurface(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = {},
                        onLongClick = onLongPress
                    ),
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
                    ToolExecutionCard(tool = toolExecution)
                } else if (toolResult != null) {
                    ToolResultCard(tool = toolResult)
                } else if (webFetchResult != null) {
                    WebFetchResultCard(result = webFetchResult)
                } else {
                    SelectionContainer {
                        MarkdownText(
                            text = msg.content,
                            modifier = Modifier.fillMaxWidth(),
                            fontSize = 12
                        )
                    }
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
                    SelectionContainer {
                        MarkdownText(
                            text = msg.content,
                            modifier = Modifier.fillMaxWidth(),
                            fontSize = 13
                        )
                    }
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
                SelectionContainer {
                    Text(
                        text = "系统状态",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = msg.content,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp
                    )
                }
            }
        } else {
            val multimodalAnalysis = remember(msg.content) {
                parseMultimodalAssistantMessage(msg.content)
            }
            Column(
                modifier = Modifier
                    .widthIn(max = 400.dp)
                    .combinedClickable(
                        onClick = {},
                        onLongClick = onLongPress
                    )
            ) {
                val reasoningContent = msg.reasoning
                if (reasoningContent != null && reasoningContent.isNotBlank()) {
                    var expanded by remember(msg.id) { mutableStateOf(msg.isStreaming) }
                    LaunchedEffect(msg.id, msg.isStreaming, reasoningContent) {
                        if (msg.isStreaming) {
                            expanded = true
                        }
                    }
                    ReasoningCard(
                        content = reasoningContent,
                        expanded = expanded,
                        isStreaming = msg.isStreaming,
                        onToggle = { expanded = !expanded }
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }

                MurongGlassSurface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(
                        topStart = 8.dp,
                        topEnd = 20.dp,
                        bottomStart = 20.dp,
                        bottomEnd = 20.dp
                    ),
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
                        if (!msg.isStreaming) {
                            val proposals = remember(msg.content) {
                                detectDraftProposals(msg.content)
                            }
                            proposals.forEach { proposal ->
                                Spacer(modifier = Modifier.height(8.dp))
                                DraftProposalCard(
                                    proposal = proposal,
                                    onDismiss = {}
                                )
                            }
                        } else if (msg.isStreaming) {
                            Text(
                                text = "思考中…",
                                color = mutedTextColor,
                                fontSize = 14.sp
                            )
                        } else {
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
                    text = "Token ${run.usageSummary.totalTokens} · 预估 \$${"%.6f".format(run.usageSummary.estimatedCostUsd)}",
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
                "总 Token ${run.usageSummary.totalTokens}\n输入 ${run.usageSummary.promptTokens} · 输出 ${run.usageSummary.completionTokens}\n预估成本 \$${"%.6f".format(run.usageSummary.estimatedCostUsd)}"
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

@Composable
private fun InputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onInputFocusChanged: (Boolean) -> Unit,
    onSend: () -> Unit,
    onPreviousInput: () -> Unit,
    onNextInput: () -> Unit,
    onPlan: () -> Unit,
    onCaptureImage: () -> Unit,
    onPickImages: () -> Unit,
    onMention: () -> Unit,
    canRecallPrevious: Boolean,
    canRecallNext: Boolean,
    canSend: Boolean,
    allowStructuredActions: Boolean,
    enabled: Boolean,
    isSending: Boolean,
    onStopSending: () -> Unit
) {
    var showMoreActions by remember { mutableStateOf(false) }
    val actionsEnabled = enabled && !isSending
    val accent = rememberMurongAccentColor()
    val surfaceColor = rememberMurongSurfaceColor()
    val mutedTextColor = rememberMurongMutedTextColor()

    MurongGlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 10.dp),
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
                    .onFocusChanged { focusState ->
                        onInputFocusChanged(focusState.isFocused)
                    },
                placeholder = {
                    Text(
                        "输入你的问题…",
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
                keyboardOptions = KeyboardOptions(imeAction = if (isSending) ImeAction.Default else ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = {
                        when {
                            isSending -> onStopSending()
                            enabled && canSend -> onSend()
                        }
                    }
                ),
                maxLines = 4,
                textStyle = MaterialTheme.typography.bodyMedium,
                enabled = enabled
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
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
                        DropdownMenuItem(
                            text = { Text("@文件") },
                            onClick = {
                                showMoreActions = false
                                onMention()
                            },
                            enabled = actionsEnabled
                        )
                        DropdownMenuItem(
                            text = { Text("计划") },
                            onClick = {
                                showMoreActions = false
                                onPlan()
                            },
                            enabled = actionsEnabled && allowStructuredActions && text.isNotBlank()
                        )
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
                    Text(text = if (isSending) "终止" else "发送")
                }
            }
        }
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
    modifier: Modifier = Modifier
) {
    val chromeColor = rememberMurongChromeColor()
    val surfaceColor = rememberMurongSurfaceColor()
    val mutedTextColor = rememberMurongMutedTextColor()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "🤖 慕容AI",
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

private fun clarificationTitle(source: ClarificationSource): String {
    return when (source) {
        ClarificationSource.MANUAL -> "澄清问题"
        ClarificationSource.AUTO_ROUTE -> "自动分流澄清"
        ClarificationSource.AUTO_INTERRUPT -> "执行中自动打断"
    }
}

private fun clarificationSubtitle(source: ClarificationSource): String? {
    return when (source) {
        ClarificationSource.MANUAL -> null
        ClarificationSource.AUTO_ROUTE ->
            "发送前自动判断到当前信息还不够完整，先补一个关键条件再继续。"
        ClarificationSource.AUTO_INTERRUPT ->
            "执行过程中识别到继续猜测风险较高，先补充一个关键信息再往下执行。"
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

// ── MCP / Skills 提案检测 ──

private data class DraftProposalUi(
    val type: DraftProposalType,
    val rawText: String,
    val previewLabel: String
)

private enum class DraftProposalType { MCP_SERVER, SKILL }

private fun detectDraftProposals(content: String): List<DraftProposalUi> {
    if (content.isBlank()) return emptyList()
    val results = mutableListOf<DraftProposalUi>()
    extractProposalCandidates(content).forEach { candidate ->
        detectSkillProposalLabel(candidate)?.let { label ->
            if (results.none { it.type == DraftProposalType.SKILL }) {
                results += DraftProposalUi(
                    type = DraftProposalType.SKILL,
                    rawText = candidate.take(800),
                    previewLabel = label
                )
            }
        }
        detectMcpProposalLabel(candidate)?.let { label ->
            if (results.none { it.type == DraftProposalType.MCP_SERVER }) {
                results += DraftProposalUi(
                    type = DraftProposalType.MCP_SERVER,
                    rawText = candidate.take(800),
                    previewLabel = label
                )
            }
        }
    }
    return results
}

private fun extractProposalCandidates(content: String): List<String> {
    val trimmed = content.trim()
    if (trimmed.isBlank()) return emptyList()
    val fenced = Regex(
        """```(?:murong-skill-draft|murong-skill|skill-draft|murong-mcp-draft|murong-mcp|mcp-draft)\s*([\s\S]*?)```""",
        RegexOption.IGNORE_CASE
    )
        .findAll(trimmed)
        .mapNotNull { it.groupValues.getOrNull(1)?.trim()?.takeIf(String::isNotBlank) }
        .toList()
    return fenced.distinct()
}

private fun detectSkillProposalLabel(candidate: String): String? {
    val trimmed = candidate.trimStart('\uFEFF').trim()
    if (trimmed.isBlank()) return null
    if (trimmed.startsWith("---")) {
        val title = Regex("(?im)^\\s*(title|name)\\s*:\\s*(.+)$")
            .find(trimmed)
            ?.groupValues
            ?.getOrNull(2)
            ?.trim()
            ?.trim('"', '\'')
        return if (title.isNullOrBlank()) "Skill 配置" else "Skill: $title"
    }
    if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
        val title = Regex(""""(?:title|name)"\s*:\s*"([^"]+)"""")
            .find(trimmed)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
        val hasSkillShape = Regex(""""(?:content|prompt|template|allowedTools|runAs)"\s*:""")
            .containsMatchIn(trimmed)
        return if (hasSkillShape) {
            if (title.isNullOrBlank()) "Skill 配置" else "Skill: $title"
        } else {
            null
        }
    }

    val fieldRegex = Regex(
        """(?im)^(title|name|description|model|runAs|context|agent|allowed-tools|allowedTools|allowed_tools|prompt|content|instruction|body|标题|名称|描述|模型|运行方式|模式|允许工具|工具|提示词|内容|正文|指令)\s*[:：]\s*(.+)?$"""
    )
    val matches = fieldRegex.findAll(trimmed).toList()
    val contentLike = matches.any {
        it.groupValues[1].lowercase() in setOf("prompt", "content", "instruction", "body", "提示词", "内容", "正文", "指令")
    }
    val title = matches.firstOrNull {
        it.groupValues[1].lowercase() in setOf("title", "name", "标题", "名称")
    }?.groupValues?.getOrNull(2)?.trim().orEmpty()
    val heading = Regex("""^(?:#+\s*)?Skill\s*[:：-]\s*(.+)$""", RegexOption.IGNORE_CASE)
        .find(trimmed.lineSequence().firstOrNull().orEmpty())
        ?.groupValues
        ?.getOrNull(1)
        ?.trim()
        .orEmpty()
    return if (matches.size >= 2 && (contentLike || title.isNotBlank() || heading.isNotBlank())) {
        "Skill: ${title.ifBlank { heading.ifBlank { "导入 Skill" } }}"
    } else {
        null
    }
}

private fun detectMcpProposalLabel(candidate: String): String? {
    val trimmed = candidate.trim()
    if (trimmed.isBlank()) return null
    if (Regex(""""mcpServers"\s*:\s*\{""").containsMatchIn(trimmed)) {
        val name = Regex(""""([a-zA-Z0-9._-]+)"\s*:\s*\{""")
            .find(trimmed.substringAfter("\"mcpServers\"", trimmed))
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
        return if (name.isNullOrBlank()) "MCP 服务器配置" else "MCP: $name"
    }
    val normalizedLine = trimmed
        .lineSequence()
        .map { line ->
            line.trim()
                .removePrefix("-")
                .removePrefix("*")
                .trim()
                .removeSurrounding("`")
        }
        .firstOrNull { line ->
            line.contains("streamable+", ignoreCase = true) ||
                Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(line) ||
                Regex("""(^|=)\s*(npx|bunx|uvx|python3?|node)\b""", RegexOption.IGNORE_CASE).containsMatchIn(line)
        }
        ?: return null
    val name = when {
        "=" in normalizedLine -> normalizedLine.substringBefore("=").trim()
        "streamable+" in normalizedLine.lowercase() || normalizedLine.startsWith("http", ignoreCase = true) ->
            normalizedLine.substringAfter("://", "")
                .substringBefore("/")
                .substringBefore("?")
                .substringBefore(":")
                .trim()
        else -> Regex("""\b(@?[a-zA-Z0-9._/-]+)\b""")
            .find(normalizedLine.substringAfter(' ', normalizedLine))
            ?.groupValues
            ?.getOrNull(1)
            ?.substringAfterLast('/')
            ?.substringAfterLast('@')
            ?.trim()
    }
    return if (name.isNullOrBlank()) "MCP 服务器配置" else "MCP: $name"
}

@Composable
private fun DraftProposalCard(
    proposal: DraftProposalUi,
    onDismiss: () -> Unit
) {
    val chromeColor = rememberMurongChromeColor()
    val mutedTextColor = rememberMurongMutedTextColor()
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = chromeColor.copy(alpha = 0.68f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (proposal.type == DraftProposalType.MCP_SERVER) "🔌 MCP 配置提案" else "📋 Skill 提案",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) {
                    Text("忽略", style = MaterialTheme.typography.labelSmall)
                }
            }
            Text(
                text = proposal.previewLabel,
                style = MaterialTheme.typography.bodySmall,
                color = mutedTextColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "检测到兼容草案块，不会自动导入或接入，请在设置页或项目页手动处理。",
                style = MaterialTheme.typography.bodySmall,
                color = mutedTextColor
            )
        }
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
    val resultWasFenced: Boolean,
    val fileChanges: List<String>,
    val truncated: Boolean,
    val isQuiet: Boolean
)

@Composable
private fun ReasoningCard(
    content: String,
    expanded: Boolean,
    isStreaming: Boolean,
    onToggle: () -> Unit
) {
    val chromeColor = rememberMurongChromeColor()
    val surfaceColor = rememberMurongSurfaceColor()
    val mutedTextColor = rememberMurongMutedTextColor()
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = chromeColor.copy(alpha = 0.60f),
        tonalElevation = 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
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
            AnimatedVisibility(visible = expanded) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = surfaceColor.copy(alpha = 0.82f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val normalizedContent = remember(content) {
                        normalizeReasoningMarkdown(content)
                    }
                    SelectionContainer {
                        MarkdownText(
                            text = normalizedContent,
                            fontSize = 12,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolExecutionCard(tool: ToolExecutionMessageUi) {
    val mutedTextColor = rememberMurongMutedTextColor()
    val canExpand = tool.args.isNotBlank()
    var showArgs by remember(tool) { mutableStateOf(canExpand && !tool.isQuiet) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ToolCardHeader(
            toolName = tool.toolName,
            statusText = "运行中",
            statusColor = MaterialTheme.colorScheme.primary,
            summary = tool.summary,
            expanded = showArgs,
            expandable = canExpand,
            quiet = tool.isQuiet,
            onToggle = { if (canExpand) showArgs = !showArgs }
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
                AnimatedVisibility(visible = showArgs) {
                    DetailBlock("参数", tool.args)
                }
            }
        }
    }
}

@Composable
private fun ToolResultCard(tool: ToolResultMessageUi) {
    val canExpand = tool.result.isNotBlank() || tool.fileChanges.isNotEmpty()
    var showResult by remember(tool) { mutableStateOf(!tool.isQuiet && tool.fileChanges.isNotEmpty()) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ToolCardHeader(
            toolName = tool.toolName,
            statusText = "已完成",
            statusColor = MaterialTheme.colorScheme.tertiary,
            summary = tool.summary,
            expanded = showResult,
            expandable = canExpand,
            quiet = tool.isQuiet,
            onToggle = { if (canExpand) showResult = !showResult }
        )
        AnimatedVisibility(visible = showResult) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (tool.result.isNotBlank()) {
                    DetailBlock(
                        label = if (tool.truncated) "输出（已截断）" else "输出",
                        value = tool.result,
                        renderMarkdown = tool.resultWasFenced,
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

private fun parseToolResultMessage(content: String): ToolResultMessageUi? {
    val headerMatch = Regex("""^📦 \*\*(.+?)\*\* 执行结果:""").find(content) ?: return null
    val toolName = headerMatch.groupValues.getOrNull(1).orEmpty()
    val fencedMatch = Regex("""```([\w#+.-]+)?\n([\s\S]*?)\n```""")
        .find(content)
    val resultLanguage = fencedMatch?.groupValues?.getOrNull(1).orEmpty()
    val rawPayload = fencedMatch?.groupValues?.getOrNull(2).orEmpty()
    val truncated = rawPayload.endsWith("\n...(截断)") || rawPayload == "...(截断)"
    val normalizedPayload = rawPayload.removeSuffix("\n...(截断)").removeSuffix("...(截断)")
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
        resultWasFenced = fencedMatch != null,
        fileChanges = fileChanges,
        truncated = truncated,
        isQuiet = isQuietTool(toolName)
    )
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
            SelectionContainer {
                if (renderMarkdown) {
                    MarkdownText(
                        text = buildString {
                            append("```")
                            append(codeLanguage)
                            append('\n')
                            append(value)
                            append("\n```")
                        },
                        modifier = Modifier.padding(12.dp).fillMaxWidth(),
                        fontSize = 12
                    )
                } else {
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
private fun CurrentProjectBar(
    projectPath: String,
    knowledgeFileCount: Int
) {
    val accent = rememberMurongAccentColor()
    val surfaceColor = rememberMurongSurfaceColor()
    Surface(
        color = surfaceColor.copy(alpha = 0.74f),
        tonalElevation = 2.dp,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.Add,
                contentDescription = null,
                tint = accent
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "当前项目",
                    style = MaterialTheme.typography.labelMedium,
                    color = accent
                )
                Text(
                    text = projectPath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (knowledgeFileCount > 0) {
                    Text(
                        text = "已接入知识文件 $knowledgeFileCount 个，可在 @文件 中直接选择",
                        style = MaterialTheme.typography.labelSmall,
                        color = accent
                    )
                }
            }
        }
    }
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
            Text(
                text = "预估成本 \$${"%.6f".format(usageSummary.estimatedCostUsd)}",
                style = MaterialTheme.typography.bodySmall,
                color = mutedTextColor
            )
        }
    }
}

@Composable
private fun FileChangeStatusBar(
    latestCheckpoint: ConversationCheckpointUi?,
    totalCount: Int,
    batchCount: Int,
    onClick: () -> Unit
) {
    val chromeColor = rememberMurongChromeColor()
    Surface(
        color = chromeColor.copy(alpha = 0.62f),
        tonalElevation = 2.dp,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = "最近文件修改 · $batchCount 批 / $totalCount 文件",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary
            )
            Text(
                text = latestCheckpoint?.let {
                    "${it.summary} · ${formatTimestamp(it.createdAt)}"
                } ?: "最近有文件发生修改",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "点击查看本轮修改汇总，再进入单文件差异详情",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary
            )
        }
    }
}

@Composable
private fun CompressionStatusBar(
    version: Int,
    totalVersions: Int,
    summary: String,
    sourceMessageCount: Int,
    active: Boolean,
    createdAt: Long,
    onClick: () -> Unit
) {
    val chromeColor = rememberMurongChromeColor()
    val mutedTextColor = rememberMurongMutedTextColor()
    Surface(
        color = chromeColor.copy(alpha = 0.58f),
        tonalElevation = 2.dp,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = buildString {
                    append(if (active) "上下文压缩已启用" else "上下文压缩已停用")
                    append(" · V")
                    append(version)
                    if (totalVersions > 1) {
                        append(" / 共 ")
                        append(totalVersions)
                        append(" 版")
                    }
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.tertiary
            )
            Text(
                text = "覆盖 $sourceMessageCount 条历史消息 · ${formatTimestamp(createdAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = summary.lineSequence().take(3).joinToString(" ").take(180),
                style = MaterialTheme.typography.bodySmall,
                color = mutedTextColor,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            if (totalVersions > 1) {
                Text(
                    text = "点击查看历史摘要版本",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
        }
    }
}

@Composable
private fun SubagentStatusBar(
    runs: List<SubagentRunUi>,
    onClick: () -> Unit
) {
    val latestRun = runs.maxByOrNull { it.startedAt }
    val runningCount = runs.count { it.status in setOf("pending_approval", "queued", "running", "summarizing", "cancelling") }
    val failedCount = runs.count { it.status in setOf("failed", "rejected") }
    val completedCount = runs.count { it.status == "completed" }
    val chromeColor = rememberMurongChromeColor()
    val mutedTextColor = rememberMurongMutedTextColor()
    Surface(
        color = chromeColor.copy(alpha = 0.60f),
        tonalElevation = 2.dp,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = "子代理总览 · ${runs.size} 次运行",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary
            )
            Text(
                text = buildString {
                    append("运行中 $runningCount")
                    append(" · 已完成 $completedCount")
                    if (failedCount > 0) {
                        append(" · 失败 $failedCount")
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            latestRun?.let { run ->
                Text(
                    text = "最新任务: ${run.goal.take(36)} · ${subagentStatusLabel(run.status)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedTextColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = "点击查看全部子代理任务和状态",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary
            )
        }
    }
}

@Composable
private fun FileChangeBatchSheet(
    checkpoints: List<ConversationCheckpointUi>,
    records: List<FileChangeRecordUi>,
    onDismiss: () -> Unit,
    onOpenCheckpoint: (ConversationCheckpointUi) -> Unit
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
                text = "本轮文件修改汇总",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (checkpoints.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = surfaceColor.copy(alpha = 0.56f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "还没有可汇总的修改批次",
                        modifier = Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = mutedTextColor
                    )
                }
            } else {
                checkpoints.forEach { checkpoint ->
                    val batchRecords = records.filter { it.checkpointId == checkpoint.id }
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = surfaceColor.copy(alpha = 0.56f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenCheckpoint(checkpoint) }
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = checkpoint.summary,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "${batchRecords.size} 个文件 · ${formatTimestamp(checkpoint.createdAt)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = mutedTextColor
                            )
                            Text(
                                text = checkpoint.changedFiles.take(3).joinToString("\n"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
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
                        text = "点击“回滚这一批”后，这些文件会直接恢复到该对话发生前的状态，之后基于这些文件产生的后续对话内容也可能失效。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = onRollbackCheckpoint,
                    enabled = records.isNotEmpty()
                ) {
                    Text("回滚这一批")
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
                                    if (run.usageSummary.estimatedCostUsd > 0) {
                                        append(" · $${"%.6f".format(run.usageSummary.estimatedCostUsd)}")
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
            compareByDescending<SubagentRunUi> { it.usageSummary.estimatedCostUsd }
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
            SelectionContainer {
                Text(
                    text = content,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
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
private fun CompressionSuggestionBar(
    suggestion: CompressionSuggestionState,
    onCompress: () -> Unit,
    onDismiss: () -> Unit
) {
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
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "建议压缩上下文",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = suggestion.reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "预计可压缩 ${suggestion.preview.compressibleMessageCount} 条历史消息，保留最近 ${suggestion.preview.recentMessageCount} 条原文继续对话。",
                style = MaterialTheme.typography.bodySmall,
                color = mutedTextColor
            )
            Text(
                text = "后续注入上下文预计从 ${formatTokenCount(suggestion.preview.estimatedCurrentContextTokens)} Token 降到 ${formatTokenCount(suggestion.preview.estimatedCompressedContextTokens)} Token，约减少 ${formatTokenCount(suggestion.preview.estimatedTokensSaved)} Token（-${suggestion.preview.estimatedReductionPercent}%）。",
                style = MaterialTheme.typography.bodySmall,
                color = mutedTextColor
            )
            Text(
                text = "摘要本身预计占用 ${formatTokenCount(suggestion.preview.estimatedSummaryTokens)} Token，适合先压历史再继续当前问题。",
                style = MaterialTheme.typography.bodySmall,
                color = mutedTextColor
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onCompress) {
                    Text("立即压缩")
                }
                TextButton(onClick = onDismiss) {
                    Text("稍后提醒")
                }
            }
        }
    }
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
