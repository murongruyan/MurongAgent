package dev.reasonix.mobile.ui

import android.content.Context
import android.content.Intent
import android.content.ClipData
import android.os.Environment
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.DocumentsContract
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dagger.hilt.android.AndroidEntryPoint
import dev.reasonix.mobile.core.config.WorkflowExecutionMode
import dev.reasonix.mobile.core.loop.ConversationExportData
import dev.reasonix.mobile.core.loop.ConversationExportFormat
import dev.reasonix.mobile.core.loop.PendingImageAttachmentUi
import dev.reasonix.mobile.core.loop.PendingApprovalUi
import dev.reasonix.mobile.core.loop.UsageSummarySnapshot
import dev.reasonix.mobile.core.tool.ApprovalRiskLevel
import dev.reasonix.mobile.ui.chat.ChatViewModel
import dev.reasonix.mobile.ui.chat.ChatScreen
import dev.reasonix.mobile.ui.chat.SessionDrawerContent
import dev.reasonix.mobile.ui.chat.buildConversationText
import dev.reasonix.mobile.ui.auth.AuthViewModel
import dev.reasonix.mobile.ui.auth.GitHubLoginScreen
import dev.reasonix.mobile.ui.project.ProjectScreen
import dev.reasonix.mobile.ui.settings.SettingsScreen
import dev.reasonix.mobile.ui.settings.SettingsViewModel
import dev.reasonix.mobile.ui.tools.ToolsScreen
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    companion object {
        val gitHubOAuthCallbackFlow = MutableSharedFlow<String>(
            replay = 1,
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dispatchGitHubOAuthCallback(intent)
        enableEdgeToEdge()
        setContent {
            ReasonixTheme {
                MainScreen()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        dispatchGitHubOAuthCallback(intent)
    }

    private fun dispatchGitHubOAuthCallback(intent: Intent?) {
        val callbackUri = intent?.data ?: return
        val isLegacyGitHubCallback =
            callbackUri.scheme == "reasonix" && callbackUri.host == "github"
        val isBackendGitHubCallback =
            callbackUri.scheme == "reasonix" &&
                callbackUri.host == "auth" &&
                callbackUri.path?.startsWith("/github") == true
        if (!isLegacyGitHubCallback && !isBackendGitHubCallback) return
        gitHubOAuthCallbackFlow.tryEmit(callbackUri.toString())
    }
}

sealed class Screen(val route: String, val title: String) {
    data object Chat : Screen("chat", "聊天")
    data object Projects : Screen("projects", "项目")
    data object Tools : Screen("tools", "工具")
    data object Settings : Screen("settings", "设置")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    var currentScreen: Screen by remember { mutableStateOf(Screen.Chat) }
    var showTaskDialog by remember { mutableStateOf(false) }
    var taskProjectPath by remember { mutableStateOf("") }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val authVm: AuthViewModel = hiltViewModel()
    val chatVm: ChatViewModel = hiltViewModel()
    val settingsVm: SettingsViewModel = hiltViewModel()
    val authState by authVm.uiState.collectAsState()
    val chatState by chatVm.state.collectAsState()
    val chatConfig by chatVm.config.collectAsState()
    val chatSessions by chatVm.sessions.collectAsState()
    val settingsConfig by settingsVm.config.collectAsState()
    val rootStatus by settingsVm.rootStatus.collectAsState()
    val isCheckingRoot by settingsVm.isCheckingRoot.collectAsState()
    val settingsSessions by settingsVm.sessions.collectAsState()
    val balanceSyncStates by settingsVm.balanceSyncStates.collectAsState()
    val mcpServers by settingsVm.mcpServers.collectAsState()
    val mcpStatuses by settingsVm.mcpStatuses.collectAsState()
    val mcpConnectError by settingsVm.mcpConnectError.collectAsState()
    val gitHubAuthState by settingsVm.gitHubAuthState.collectAsState()
    val effectiveChatConfig = remember(settingsConfig, chatState.projectToolPreferences) {
        settingsConfig.applyProjectToolPreferences(chatState.projectToolPreferences)
    }
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var showChatMenu by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    var renameSessionTargetId by remember { mutableStateOf<String?>(null) }
    var renameSessionDraft by remember { mutableStateOf("") }
    var showExportDialog by remember { mutableStateOf(false) }
    var exportFormat by remember { mutableStateOf(ConversationExportFormat.MARKDOWN) }
    var pendingExportData by remember { mutableStateOf<ConversationExportData?>(null) }

    LaunchedEffect(Unit) {
        MainActivity.gitHubOAuthCallbackFlow.collect { callbackUri ->
            authVm.handleGitHubCallback(callbackUri)
            settingsVm.handleGitHubOAuthCallback(callbackUri)
        }
    }

    LaunchedEffect(authState.authorizationUrl) {
        val authorizationUrl = authState.authorizationUrl?.trim().orEmpty()
        if (authorizationUrl.isBlank()) return@LaunchedEffect
        runCatching { uriHandler.openUri(authorizationUrl) }
    }

    LaunchedEffect(currentScreen) {
        if (currentScreen !is Screen.Chat && drawerState.isOpen) {
            drawerState.close()
        }
    }

    if (!authState.isAuthenticated) {
        GitHubLoginScreen(
            uiState = authState,
            onStartGitHubLogin = { authVm.startGitHubLogin() }
        )
        return
    }

    val importConversationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val fileContent = runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader().use { reader ->
                reader?.readText() ?: error("无法读取导入文件")
            }
        }

        fileContent
            .onFailure { error ->
                scope.launch {
                    snackbarHostState.showSnackbar(error.message ?: "导入文件读取失败")
                }
            }
            .onSuccess { content ->
                val sourceName = resolveDisplayName(context, uri)
                chatVm.importConversation(content, sourceName)
                    .onSuccess { count ->
                        currentScreen = Screen.Chat
                        scope.launch {
                            snackbarHostState.showSnackbar("已导入 $count 条消息")
                        }
                    }
                    .onFailure { error ->
                        scope.launch {
                            snackbarHostState.showSnackbar(error.message ?: "导入对话失败")
                        }
                    }
            }
    }

    val exportConversationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        val exportData = pendingExportData
        if (uri == null || exportData == null) {
            pendingExportData = null
            return@rememberLauncherForActivityResult
        }

        runCatching {
            context.contentResolver.openOutputStream(uri)?.bufferedWriter().use { writer ->
                requireNotNull(writer) { "无法创建导出文件" }
                writer.write(exportData.content)
            }
        }.onSuccess {
            scope.launch {
                snackbarHostState.showSnackbar("已导出为 ${exportData.fileName}")
            }
        }.onFailure { error ->
            scope.launch {
                snackbarHostState.showSnackbar(error.message ?: "导出失败")
            }
        }

        pendingExportData = null
    }

    fun openRenameDialog(sessionId: String, title: String) {
        renameSessionTargetId = sessionId
        renameSessionDraft = title.ifBlank { "新对话" }
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult

        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }

        val resolvedPath = resolveTreeUriToPath(uri)
        taskProjectPath = resolvedPath ?: uri.toString()

        if (resolvedPath == null) {
            scope.launch {
                snackbarHostState.showSnackbar("已选择文件夹，但系统未能解析出绝对路径，可手动调整")
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = currentScreen is Screen.Chat,
        drawerContent = {
            if (currentScreen is Screen.Chat) {
                ModalDrawerSheet {
                    SessionDrawerContent(
                        currentSessionId = chatState.sessionId,
                        sessions = chatSessions,
                        onNewSession = {
                            chatVm.newSession()
                            scope.launch { drawerState.close() }
                        },
                        onNewTask = {
                            taskProjectPath = ""
                            showTaskDialog = true
                        },
                        onLoadSession = { sessionId ->
                            chatVm.loadSession(sessionId)
                            scope.launch { drawerState.close() }
                        },
                        onRenameSession = { sessionId ->
                            val sessionTitle = chatSessions
                                .firstOrNull { it.id == sessionId }
                                ?.title
                                .orEmpty()
                            openRenameDialog(sessionId, sessionTitle)
                        },
                        onDeleteSession = { sessionId ->
                            chatVm.deleteSession(sessionId)
                        }
                    )
                }
            }
        },
    ) {
        Scaffold(
            snackbarHost = {
                SnackbarHost(hostState = snackbarHostState)
            },
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        if (currentScreen is Screen.Chat) {
                            IconButton(
                                onClick = {
                                    scope.launch {
                                        drawerState.open()
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Menu,
                                    contentDescription = "打开会话列表"
                                )
                            }
                        }
                    },
                    title = {
                        Text(
                            text = when (currentScreen) {
                                is Screen.Chat -> chatState.sessionTitle.ifBlank { "新对话" }
                                is Screen.Projects -> Screen.Projects.title
                                is Screen.Tools -> Screen.Tools.title
                                is Screen.Settings -> Screen.Settings.title
                            },
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    },
                    actions = {
                        if (currentScreen is Screen.Chat) {
                            buildPromptCacheSummary(chatState.usageSummary)?.let { cacheSummary ->
                                Surface(
                                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                                    shape = MaterialTheme.shapes.large
                                ) {
                                    Text(
                                        text = cacheSummary,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Box {
                                IconButton(onClick = { showChatMenu = true }) {
                                    Icon(
                                        imageVector = Icons.Outlined.MoreVert,
                                        contentDescription = "聊天操作"
                                    )
                                }
                                DropdownMenu(
                                    expanded = showChatMenu,
                                    onDismissRequest = { showChatMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("新对话") },
                                        onClick = {
                                            chatVm.newSession()
                                            showChatMenu = false
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("新任务") },
                                        onClick = {
                                            taskProjectPath = ""
                                            showTaskDialog = true
                                            showChatMenu = false
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("重命名会话") },
                                        onClick = {
                                            openRenameDialog(
                                                chatState.sessionId,
                                                chatState.sessionTitle
                                            )
                                            showChatMenu = false
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("导出会话") },
                                        onClick = {
                                            exportFormat = ConversationExportFormat.MARKDOWN
                                            showExportDialog = true
                                            showChatMenu = false
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                if (chatState.compressionSnapshot == null) {
                                                    "压缩上下文"
                                                } else if (chatState.compressionSnapshot?.active == true) {
                                                    "停用压缩"
                                                } else {
                                                    "启用压缩"
                                                }
                                            )
                                        },
                                        onClick = {
                                            scope.launch {
                                                val message = when {
                                                    chatState.compressionSnapshot == null -> {
                                                        chatVm.compressCurrentContext()
                                                            .fold(
                                                                onSuccess = {
                                                                    "已压缩 ${it.sourceMessageCount} 条历史消息，生成摘要 V${it.version} 后续将基于摘要续聊"
                                                                },
                                                                onFailure = { error ->
                                                                    error.message ?: "上下文压缩失败"
                                                                }
                                                            )
                                                    }

                                                    chatState.compressionSnapshot?.active == true -> {
                                                        if (chatVm.disableContextCompression()) {
                                                            "已停用上下文压缩，将恢复使用完整历史"
                                                        } else {
                                                            "停用上下文压缩失败"
                                                        }
                                                    }

                                                    else -> {
                                                        if (chatVm.enableContextCompression()) {
                                                            "已重新启用上下文压缩"
                                                        } else {
                                                            "启用上下文压缩失败"
                                                        }
                                                    }
                                                }
                                                snackbarHostState.showSnackbar(message)
                                            }
                                            showChatMenu = false
                                        }
                                    )
                                    if (chatState.compressionSnapshot != null) {
                                        DropdownMenuItem(
                                            text = { Text("重新压缩摘要") },
                                            onClick = {
                                                scope.launch {
                                                    val message = chatVm.compressCurrentContext()
                                                        .fold(
                                                            onSuccess = {
                                                                "已生成摘要 V${it.version}，压缩 ${it.sourceMessageCount} 条历史消息"
                                                            },
                                                            onFailure = { error ->
                                                                error.message ?: "重新压缩摘要失败"
                                                            }
                                                        )
                                                    snackbarHostState.showSnackbar(message)
                                                }
                                                showChatMenu = false
                                            }
                                        )
                                    }
                                    DropdownMenuItem(
                                        text = { Text("导入对话") },
                                        onClick = {
                                            importConversationLauncher.launch(
                                                arrayOf("text/*", "application/json")
                                            )
                                            showChatMenu = false
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("复制全部") },
                                        onClick = {
                                            copyTextToClipboard(
                                                context = context,
                                                text = buildConversationText(chatState.messages)
                                            )
                                            showChatMenu = false
                                        }
                                    )
                                    if (chatState.messages.any { it.role == "user" }) {
                                        DropdownMenuItem(
                                            text = { Text("回退最近一轮") },
                                            onClick = {
                                                chatVm.rollbackLastTurn()
                                                showChatMenu = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground,
                        actionIconContentColor = MaterialTheme.colorScheme.primary,
                        navigationIconContentColor = MaterialTheme.colorScheme.primary
                    )
                )
            },
            bottomBar = {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                    tonalElevation = 6.dp,
                    shadowElevation = 2.dp
                ) {
                    NavigationBar(
                        containerColor = Color.Transparent,
                        tonalElevation = 0.dp
                    ) {
                        NavigationBarItem(
                            selected = currentScreen is Screen.Chat,
                            onClick = { currentScreen = Screen.Chat },
                            icon = { Text("💬", style = MaterialTheme.typography.titleLarge) },
                            label = { Text("聊天") },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        )
                        NavigationBarItem(
                            selected = currentScreen is Screen.Projects,
                            onClick = { currentScreen = Screen.Projects },
                            icon = { Text("📁", style = MaterialTheme.typography.titleLarge) },
                            label = { Text("项目") },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        )
                        NavigationBarItem(
                            selected = currentScreen is Screen.Tools,
                            onClick = { currentScreen = Screen.Tools },
                            icon = { Text("🧰", style = MaterialTheme.typography.titleLarge) },
                            label = { Text("工具") },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        )
                        NavigationBarItem(
                            selected = currentScreen is Screen.Settings,
                            onClick = { currentScreen = Screen.Settings },
                            icon = { Text("⚙️", style = MaterialTheme.typography.titleLarge) },
                            label = { Text("设置") },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        )
                    }
                }
            }
        ) { innerPadding ->
            Box(modifier = Modifier.padding(innerPadding)) {
                when (currentScreen) {
                    is Screen.Chat -> {
                        ChatScreen(
                            state = chatState,
                            projectKnowledgePaths = chatState.projectKnowledgePaths,
                            onSend = { text, mentions, images ->
                                chatVm.sendMessage(text, mentions, images)
                            },
                            onStopSending = {
                                val message = if (chatVm.stopSending()) {
                                    "已终止当前处理"
                                } else {
                                    "当前没有可终止的处理"
                                }
                                scope.launch {
                                    snackbarHostState.showSnackbar(message)
                                }
                            },
                            onClear = { chatVm.clear() },
                            onNewSession = { chatVm.newSession() },
                            title = chatState.sessionTitle,
                            hasApiKey = chatVm.hasActiveApiKey(chatConfig),
                            workflowExecutionMode = effectiveChatConfig.workflowExecutionMode,
                            autoRouteBeforeExecution = false,
                            onNavigateToSettings = { currentScreen = Screen.Settings },
                            onEditMessage = { messageId ->
                                chatVm.rollbackToUserMessage(messageId)
                            },
                            onCompressContext = {
                                scope.launch {
                                    val message = chatVm.compressCurrentContext()
                                        .fold(
                                            onSuccess = {
                                                "已压缩 ${it.sourceMessageCount} 条历史消息，生成摘要 V${it.version} 后续将基于摘要续聊"
                                            },
                                            onFailure = { error ->
                                                error.message ?: "上下文压缩失败"
                                            }
                                        )
                                    snackbarHostState.showSnackbar(message)
                                }
                            },
                            onGeneratePlan = { input, mentions ->
                                chatVm.generateWorkflowPlan(input, mentions)
                            },
                            onExecutePlan = {
                                chatVm.executePendingWorkflowPlan()
                            },
                            onDismissPlan = {
                                chatVm.dismissPendingWorkflowPlan()
                            },
                            onSubmitClarificationAnswer = { answer ->
                                chatVm.submitClarificationAnswer(answer)
                            },
                            onDismissClarification = {
                                chatVm.dismissPendingClarification()
                            },
                            onSearchFiles = { query ->
                                chatVm.searchProjectFiles(query)
                            },
                            onRetrySubagent = { runId ->
                                chatVm.retrySubagentRun(runId)
                                scope.launch {
                                    snackbarHostState.showSnackbar("已重新发起子代理")
                                }
                            },
                            onCancelSubagent = { runId ->
                                val message = if (chatVm.cancelSubagentRun(runId)) {
                                    "已请求终止子代理"
                                } else {
                                    "当前子代理无法终止"
                                }
                                scope.launch {
                                    snackbarHostState.showSnackbar(message)
                                }
                            },
                            onRollbackFileCheckpoint = { checkpointId ->
                                val message = chatVm.rollbackFileCheckpoint(checkpointId)
                                    .fold(
                                        onSuccess = { count ->
                                            "已按该对话前状态回滚这一批修改，恢复 $count 个文件"
                                        },
                                        onFailure = { error ->
                                            error.message ?: "回滚文件修改失败"
                                        }
                                    )
                                scope.launch {
                                    snackbarHostState.showSnackbar(message)
                                }
                            }
                        )
                    }
                    is Screen.Projects -> {
                        ProjectScreen(
                            config = settingsConfig,
                            currentProjectPath = chatState.projectPath,
                            projectKnowledgeDraftPaths = chatState.projectKnowledgePaths,
                            projectKnowledgeSnapshots = chatState.projectKnowledgeSnapshots,
                            projectRules = chatState.projectRules,
                            projectMemories = chatState.projectMemories,
                            projectSkills = chatState.projectSkills,
                            projectToolPreferences = chatState.projectToolPreferences,
                            mcpToolNames = mcpStatuses.flatMap { status ->
                                status.toolNames.map { "mcp_$it" }
                            }.distinct().sorted(),
                            sessions = chatSessions,
                            onNewTask = {
                                taskProjectPath = chatState.projectPath.orEmpty()
                                showTaskDialog = true
                            },
                            onOpenChat = { currentScreen = Screen.Chat },
                            onUpdateProjectConfig = { rules, memories, skills ->
                                chatVm.updateProjectConfig(rules, memories, skills)
                            },
                            onUpdateProjectToolPreferences = { preferences ->
                                chatVm.updateProjectToolPreferences(preferences)
                            },
                            onUpdateProjectKnowledgeDraftPaths = { paths ->
                                chatVm.updateProjectKnowledgePaths(paths)
                            },
                            onSaveProjectKnowledgeSnapshot = { name, paths ->
                                chatVm.saveProjectKnowledgeSnapshot(name, paths)
                            },
                            onRenameProjectKnowledgeSnapshot = { snapshotId, newName ->
                                chatVm.renameProjectKnowledgeSnapshot(snapshotId, newName)
                            },
                            onApplyProjectKnowledgeSnapshot = { snapshotId ->
                                chatVm.applyProjectKnowledgeSnapshot(snapshotId)
                            },
                            onDeleteProjectKnowledgeSnapshot = { snapshotId ->
                                chatVm.deleteProjectKnowledgeSnapshot(snapshotId)
                            }
                        )
                    }
                    is Screen.Tools -> {
                        ToolsScreen(
                            config = settingsConfig,
                            currentProjectPath = chatState.projectPath,
                            projectRuleCount = chatState.projectRules.count { it.enabled },
                            projectMemoryCount = chatState.projectMemories.count { it.enabled },
                            projectSkillCount = chatState.projectSkills.count { it.enabled },
                            rootStatus = rootStatus,
                            isCheckingRoot = isCheckingRoot,
                            onCheckRoot = { settingsVm.checkRoot() },
                            pendingApproval = chatState.pendingApproval,
                            recentApprovals = chatState.recentApprovals,
                            recentErrors = chatState.recentErrors,
                            recentToolCalls = chatState.recentToolCalls,
                            checkpoints = chatState.checkpoints,
                            fileChanges = chatState.fileChanges,
                            onOpenChat = { currentScreen = Screen.Chat },
                            onApprovePendingTool = {
                                chatVm.approvePendingTool()
                            },
                            onRejectPendingTool = {
                                chatVm.rejectPendingTool()
                            },
                            onRollbackFileCheckpoint = { checkpointId ->
                                val message = chatVm.rollbackFileCheckpoint(checkpointId)
                                    .fold(
                                        onSuccess = { count ->
                                            "已按该对话前状态回滚这一批修改，恢复 $count 个文件"
                                        },
                                        onFailure = { error ->
                                            error.message ?: "回滚文件修改失败"
                                        }
                                    )
                                scope.launch {
                                    snackbarHostState.showSnackbar(message)
                                }
                            },
                            mcpStatuses = mcpStatuses,
                            mcpConnectError = mcpConnectError,
                            onConnectMcpServers = { settingsVm.connectMcpServers() },
                            onRefreshMcpStatus = { settingsVm.refreshMcpStatus() },
                            onUpdateConfig = { settingsVm.updateConfig(it) }
                        )
                    }
                    is Screen.Settings -> {
                        SettingsScreen(
                            config = settingsConfig,
                            onConfigChanged = { settingsVm.updateConfig(it) },
                            gitHubAuthState = gitHubAuthState,
                            rootStatus = rootStatus,
                            isCheckingRoot = isCheckingRoot,
                            onCheckRoot = { settingsVm.checkRoot() },
                            sessions = settingsSessions,
                            balanceSyncStates = balanceSyncStates,
                            mcpServers = mcpServers,
                            mcpStatuses = mcpStatuses,
                            mcpConnectError = mcpConnectError,
                            onRefreshProviderBalance = { settingsVm.refreshProviderBalance(it) },
                            supportsBalanceFetch = { settingsVm.supportsBalanceFetch(it) },
                            onAddMcpServer = { settingsVm.addMcpServer(it) },
                            onRemoveMcpServer = { settingsVm.removeMcpServer(it) },
                            onConnectMcpServers = { settingsVm.connectMcpServers() },
                            onRefreshMcpStatus = { settingsVm.refreshMcpStatus() },
                            onRefreshGitHubAuthStatus = { settingsVm.refreshGitHubAuthStatus() },
                            onStartGitHubOAuthLogin = { settingsVm.startGitHubOAuthLogin() },
                            onClearGitHubToken = { settingsVm.clearGitHubToken() }
                        )
                    }
                }
            }
        }

        if (showTaskDialog) {
            CreateTaskDialog(
                onDismiss = { showTaskDialog = false },
                projectPath = taskProjectPath,
                onProjectPathChange = { taskProjectPath = it },
                onPickFolder = {
                    folderPickerLauncher.launch(null)
                },
                onCreateTask = { projectPath ->
                    chatVm.startTask(projectPath)
                    taskProjectPath = ""
                    showTaskDialog = false
                    scope.launch { drawerState.close() }
                    currentScreen = Screen.Chat
                }
            )
        }

        renameSessionTargetId?.let { sessionId ->
            RenameSessionDialog(
                value = renameSessionDraft,
                onValueChange = { renameSessionDraft = it },
                onDismiss = { renameSessionTargetId = null },
                onConfirm = {
                    val renamed = chatVm.renameSession(sessionId, renameSessionDraft)
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            if (renamed) "会话已重命名" else "重命名失败，请检查标题"
                        )
                    }
                    if (renamed) {
                        renameSessionTargetId = null
                    }
                }
            )
        }

        if (showExportDialog) {
            ExportConversationDialog(
                selectedFormat = exportFormat,
                onSelectFormat = { exportFormat = it },
                onDismiss = { showExportDialog = false },
                onConfirm = {
                    chatVm.exportCurrentConversation(exportFormat)
                        .onSuccess { exportData ->
                            pendingExportData = exportData
                            exportConversationLauncher.launch(exportData.fileName)
                        }
                        .onFailure { error ->
                            scope.launch {
                                snackbarHostState.showSnackbar(error.message ?: "导出失败")
                            }
                        }
                    showExportDialog = false
                }
            )
        }

        if (currentScreen !is Screen.Tools) {
            chatState.pendingApproval?.let { pendingApproval ->
            ApprovalDialog(
                approval = pendingApproval,
                onApprove = { chatVm.approvePendingTool() },
                onReject = { chatVm.rejectPendingTool() }
            )
            }
        }
    }
}

private fun copyTextToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(null, text))
}

private fun buildPromptCacheSummary(usage: UsageSummarySnapshot): String? {
    val cacheTotal = usage.promptCacheHitTokens + usage.promptCacheMissTokens
    if (cacheTotal <= 0) return null
    val hitRate = usage.promptCacheHitTokens * 100.0 / cacheTotal.toDouble()
    return "缓存 ${"%.0f".format(hitRate)}% · 命中 ${usage.promptCacheHitTokens}"
}

@Composable
private fun RenameSessionDialog(
    value: String,
    onValueChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名会话") },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("会话标题") },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = value.trim().isNotBlank()
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun ExportConversationDialog(
    selectedFormat: ConversationExportFormat,
    onSelectFormat: (ConversationExportFormat) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导出会话") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "选择导出格式，首版支持 Markdown 和 JSON。",
                    style = MaterialTheme.typography.bodySmall
                )
                ConversationExportFormat.entries.forEach { format ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectFormat(format) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedFormat == format,
                            onClick = { onSelectFormat(format) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(format.label)
                            Text(
                                text = format.mimeType,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun CreateTaskDialog(
    onDismiss: () -> Unit,
    projectPath: String,
    onProjectPathChange: (String) -> Unit,
    onPickFolder: () -> Unit,
    onCreateTask: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建任务") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "可以直接手动输入项目目录，也可以用系统文件夹选择器选择项目。",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedButton(
                    onClick = onPickFolder,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("选择文件夹")
                }
                OutlinedTextField(
                    value = projectPath,
                    onValueChange = onProjectPathChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("项目目录") },
                    placeholder = { Text("/data/local/tmp/project 或 /sdcard/项目 或内容 Uri") },
                    singleLine = true
                )
                Text(
                    text = "优先使用可解析成绝对路径的目录；如果系统只返回 Uri，也会先保留该值作为项目上下文。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreateTask(projectPath.trim()) },
                enabled = projectPath.isNotBlank()
            ) {
                Text("创建")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

private fun resolveDisplayName(context: Context, uri: Uri): String? {
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

private fun resolveTreeUriToPath(uri: Uri): String? {
    val documentId = runCatching {
        DocumentsContract.getTreeDocumentId(uri)
    }.getOrNull() ?: return null

    if (documentId.startsWith("raw:")) {
        return documentId.removePrefix("raw:")
    }

    val parts = documentId.split(":", limit = 2)
    val volume = parts.getOrNull(0)?.lowercase() ?: return null
    val relativePath = parts.getOrNull(1).orEmpty().trim('/')

    fun withRelative(basePath: String): String {
        return if (relativePath.isBlank()) basePath else "$basePath/$relativePath"
    }

    return when (volume) {
        "primary" -> withRelative(Environment.getExternalStorageDirectory().absolutePath)
        "home" -> withRelative("${Environment.getExternalStorageDirectory().absolutePath}/Documents")
        else -> {
            if (volume.matches(Regex("^[0-9a-f]{4}-[0-9a-f]{4}$", RegexOption.IGNORE_CASE))) {
                withRelative("/storage/${parts[0]}")
            } else {
                null
            }
        }
    }
}

@Composable
private fun ApprovalDialog(
    approval: PendingApprovalUi,
    onApprove: () -> Unit,
    onReject: () -> Unit
) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("审批请求") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                RiskBadge(approval.riskLevel)
                Text(
                    text = approval.summary,
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = approval.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                approval.explanationLabel?.let { label ->
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                            approval.explanationDetail?.takeIf { it.isNotBlank() }?.let { detail ->
                                Text(
                                    text = detail,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = approval.rawArgs,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onApprove) {
                Text("允许")
            }
        },
        dismissButton = {
            TextButton(onClick = onReject) {
                Text("拒绝")
            }
        }
    )
}

@Composable
private fun RiskBadge(riskLevel: ApprovalRiskLevel) {
    val (label, color) = when (riskLevel) {
        ApprovalRiskLevel.LOW -> "低风险" to Color(0xFF4CAF50)
        ApprovalRiskLevel.MEDIUM -> "中风险" to Color(0xFFFF9800)
        ApprovalRiskLevel.HIGH -> "高风险" to Color(0xFFE53935)
    }

    Surface(
        color = color.copy(alpha = 0.12f),
        contentColor = color,
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}
