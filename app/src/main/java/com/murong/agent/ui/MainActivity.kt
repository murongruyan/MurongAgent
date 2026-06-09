package com.murong.agent.ui

import android.content.Context
import android.content.Intent
import android.content.ClipData
import android.os.Environment
import android.os.Bundle
import android.widget.Toast
import android.provider.OpenableColumns
import android.provider.DocumentsContract
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import dagger.hilt.android.AndroidEntryPoint
import com.murong.agent.core.config.WorkflowExecutionMode
import com.murong.agent.core.loop.ConversationExportData
import com.murong.agent.core.loop.ConversationExportFormat
import com.murong.agent.core.loop.PendingImageAttachmentUi
import com.murong.agent.core.loop.PendingApprovalUi
import com.murong.agent.core.loop.UsageSummarySnapshot
import com.murong.agent.core.tool.ApprovalRiskLevel
import com.murong.agent.ui.chat.ChatViewModel
import com.murong.agent.ui.chat.ChatScreen
import com.murong.agent.ui.chat.SessionDrawerContent
import com.murong.agent.ui.chat.buildConversationText
import com.murong.agent.ui.auth.AuthViewModel
import com.murong.agent.ui.auth.GitHubAuthFlow
import com.murong.agent.ui.auth.GitHubLoginScreen
import com.murong.agent.ui.project.ProjectEditorMenuAction
import com.murong.agent.ui.project.ProjectSecondaryHostBridgeState
import com.murong.agent.ui.project.ProjectScreen
import com.murong.agent.ui.settings.AboutPage
import com.murong.agent.ui.settings.SettingsScreen
import com.murong.agent.ui.settings.ThemeSettingsPage
import com.murong.agent.ui.settings.SettingsViewModel
import com.murong.agent.ui.tools.ToolsScreen
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

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
            MurongTheme {
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
        val callbackUri = intent?.data?.toString().orEmpty()
        if (!GitHubAuthFlow.isGitHubOAuthCallback(callbackUri)) return
        gitHubOAuthCallbackFlow.tryEmit(callbackUri)
    }
}

sealed class Screen(val route: String, val title: String) {
    data object Chat : Screen("chat", "聊天")
    data object Projects : Screen("projects", "项目")
    data object Tools : Screen("tools", "工具")
    data object Settings : Screen("settings", "设置")
}

internal sealed interface SettingsSubpage {
    data object Main : SettingsSubpage
    data object Theme : SettingsSubpage
    data object About : SettingsSubpage
}

internal data class ProjectSecondaryChromeState(
    val active: Boolean = false,
    val title: String = "",
    val subtitle: String = "",
    val supportsEditorMenu: Boolean = false
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val shellScreens = remember {
        listOf(Screen.Chat, Screen.Projects, Screen.Tools, Screen.Settings)
    }
    var topLevelNavigationState by remember { mutableStateOf(MainScreenTopLevelNavigationState()) }
    val selectedTopLevelPage = topLevelNavigationState.selectedPage
    val settledTopLevelPage = topLevelNavigationState.lastSettledPage
    val topLevelNavigationTargetPage = topLevelNavigationState.navigationTargetPage
    val topLevelHistory = topLevelNavigationState.history
    val topLevelBackProgress = topLevelNavigationState.backProgress
    var settingsState by remember { mutableStateOf(MainScreenSettingsState()) }
    var projectSecondaryHostState by remember { mutableStateOf(MainScreenProjectSecondaryHostState()) }
    val settingsSubpage = settingsState.subpage
    val settingsBackProgress = settingsState.backProgress
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(
        initialPage = selectedTopLevelPage,
        pageCount = { shellScreens.size }
    )
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
    var overlayVisibilityState by remember { mutableStateOf(MainScreenOverlayVisibilityState()) }
    var isChatSessionPanelVisible by rememberSaveable { mutableStateOf(false) }
    var chatSessionPanelBackProgress by remember { mutableFloatStateOf(0f) }
    var dialogState by remember { mutableStateOf(MainScreenDialogState()) }
    val snackbarHostState = remember { SnackbarHostState() }
    val uiController = LocalMurongUiController.current
    val visibleTopLevelPage = pagerState.currentPage.coerceIn(0, shellScreens.lastIndex)
    val visibleScreen = shellScreens[visibleTopLevelPage]
    val chatPageIndex = remember(shellScreens) {
        shellScreens.indexOfFirst { it.route == Screen.Chat.route }.coerceAtLeast(0)
    }
    val darkMode = uiController.themeMode == MurongThemeMode.DARK ||
        (uiController.themeMode == MurongThemeMode.SYSTEM &&
            murongIsDarkColor(MaterialTheme.colorScheme.background))
    val chromeColor = rememberMurongChromeColor()
    val projectEditorMenuAction = projectSecondaryHostState.editorMenuAction
    val projectEditorMenuActionSignal = projectSecondaryHostState.editorMenuActionSignal
    val showChatMenu = overlayVisibilityState.showChatMenu
    val showProjectEditorMenu = overlayVisibilityState.showProjectEditorMenu
    val showTaskDialog = dialogState.showTaskDialog
    val taskProjectPath = dialogState.taskProjectPath
    val renameSessionTargetId = dialogState.renameSessionTargetId
    val renameSessionDraft = dialogState.renameSessionDraft
    val showExportDialog = dialogState.showExportDialog
    val exportFormat = dialogState.exportFormat
    val pendingExportData = dialogState.pendingExportData

    fun dispatchOverlayVisibilityAction(action: MainScreenOverlayVisibilityAction) {
        overlayVisibilityState = reduceMainScreenOverlayVisibilityState(
            state = overlayVisibilityState,
            action = action
        )
    }

    fun dispatchDialogAction(action: MainScreenDialogAction) {
        dialogState = reduceMainScreenDialogState(
            state = dialogState,
            action = action
        )
    }

    fun dispatchTopLevelNavigationAction(action: MainScreenTopLevelNavigationAction) {
        topLevelNavigationState = reduceMainScreenTopLevelNavigationState(
            state = topLevelNavigationState,
            action = action
        )
    }

    fun dispatchSettingsAction(action: MainScreenSettingsAction) {
        settingsState = reduceMainScreenSettingsState(
            state = settingsState,
            action = action
        )
    }

    fun dispatchProjectSecondaryHostAction(action: MainScreenProjectSecondaryHostAction) {
        projectSecondaryHostState = reduceMainScreenProjectSecondaryHostState(
            state = projectSecondaryHostState,
            action = action
        )
    }

    fun dispatchHostAction(action: MainScreenHostAction) {
        when (action) {
            MainScreenHostAction.None -> Unit
            MainScreenHostAction.CloseSettingsSecondary -> {
                dispatchSettingsAction(MainScreenSettingsAction.CloseSecondaryPage)
            }
            MainScreenHostAction.CloseProjectSecondary -> {
                dispatchProjectSecondaryHostAction(
                    MainScreenProjectSecondaryHostAction.RequestCloseSecondary
                )
            }
            MainScreenHostAction.OpenChatDrawer -> {
                dispatchTopLevelNavigationAction(
                    MainScreenTopLevelNavigationAction.RequestDrawerCommand(
                        MainScreenDrawerCommand.OPEN
                    )
                )
            }
            MainScreenHostAction.CloseChatDrawer -> {
                dispatchTopLevelNavigationAction(
                    MainScreenTopLevelNavigationAction.RequestDrawerCommand(
                        MainScreenDrawerCommand.CLOSE
                    )
                )
            }
            MainScreenHostAction.NavigateTopLevelBack -> {
                dispatchSettingsAction(MainScreenSettingsAction.CloseSecondaryPage)
                dispatchTopLevelNavigationAction(MainScreenTopLevelNavigationAction.NavigateBack)
            }
            is MainScreenHostAction.NavigateToTopLevelPage -> {
                dispatchSettingsAction(MainScreenSettingsAction.CloseSecondaryPage)
                dispatchOverlayVisibilityAction(MainScreenOverlayVisibilityAction.DISMISS_MENUS)
                dispatchTopLevelNavigationAction(
                    MainScreenTopLevelNavigationAction.NavigateToPage(action.page)
                )
            }
        }
    }

    fun navigateToTopLevel(target: Screen) {
        val targetIndex = shellScreens.indexOfFirst { it.route == target.route }
        if (targetIndex < 0) return
        // #region debug-point C:chat-nav-request
        reportGitBackChatFlashMainDebug(
            hypothesisId = "C",
            location = "MainActivity.kt:navigateToTopLevel",
            msg = "[DEBUG] request top-level navigation",
            data = JSONObject()
                .put("targetRoute", target.route)
                .put("targetIndex", targetIndex)
                .put("selectedTopLevelPage", selectedTopLevelPage)
                .put("visibleTopLevelPage", visibleTopLevelPage)
                .put("visibleRoute", visibleScreen.route)
                .put("drawerValue", if (isChatSessionPanelVisible) "Open" else "Closed")
        )
        // #endregion
        dispatchHostAction(MainScreenHostAction.NavigateToTopLevelPage(targetIndex))
    }

    fun dispatchChatAction(action: MainScreenChatAction) {
        when (action) {
            is MainScreenChatAction.StartNewSession -> {
                chatVm.newSession()
                if (action.closeDrawer) {
                    dispatchHostAction(MainScreenHostAction.CloseChatDrawer)
                }
                if (action.dismissMenu) {
                    dispatchOverlayVisibilityAction(MainScreenOverlayVisibilityAction.HIDE_CHAT_MENU)
                }
            }
            is MainScreenChatAction.LoadSession -> {
                // #region debug-point F:load-local-project-session
                reportGitBackChatFlashMainDebug(
                    hypothesisId = "F",
                    location = "MainActivity.kt:dispatchChatAction:loadSession:before",
                    msg = "[DEBUG] loading chat session requested",
                    data = JSONObject()
                        .put("sessionId", action.sessionId)
                        .put("selectedTopLevelPage", selectedTopLevelPage)
                        .put("visibleTopLevelPage", visibleTopLevelPage)
                        .put("visibleRoute", visibleScreen.route)
                        .put("currentSessionId", chatState.sessionId)
                        .put("currentProjectPath", chatState.projectPath ?: JSONObject.NULL)
                )
                // #endregion
                chatVm.loadSession(action.sessionId)
                // #region debug-point F:load-local-project-session
                reportGitBackChatFlashMainDebug(
                    hypothesisId = "F",
                    location = "MainActivity.kt:dispatchChatAction:loadSession:after",
                    msg = "[DEBUG] loading chat session returned",
                    data = JSONObject()
                        .put("sessionId", action.sessionId)
                        .put("selectedTopLevelPage", selectedTopLevelPage)
                        .put("visibleTopLevelPage", visibleTopLevelPage)
                        .put("visibleRoute", visibleScreen.route)
                        .put("currentSessionId", chatState.sessionId)
                        .put("currentProjectPath", chatState.projectPath ?: JSONObject.NULL)
                )
                // #endregion
                if (action.closeDrawer) {
                    dispatchHostAction(MainScreenHostAction.CloseChatDrawer)
                }
                if (action.dismissMenu) {
                    dispatchOverlayVisibilityAction(MainScreenOverlayVisibilityAction.HIDE_CHAT_MENU)
                }
            }
            is MainScreenChatAction.DeleteSession -> {
                chatVm.deleteSession(action.sessionId)
                if (action.closeDrawer) {
                    dispatchHostAction(MainScreenHostAction.CloseChatDrawer)
                }
                if (action.dismissMenu) {
                    dispatchOverlayVisibilityAction(MainScreenOverlayVisibilityAction.HIDE_CHAT_MENU)
                }
            }
            is MainScreenChatAction.OpenTaskDialog -> {
                dispatchDialogAction(MainScreenDialogAction.ShowTaskDialog(action.projectPath))
                if (action.dismissMenu) {
                    dispatchOverlayVisibilityAction(MainScreenOverlayVisibilityAction.HIDE_CHAT_MENU)
                }
            }
            is MainScreenChatAction.OpenRenameDialog -> {
                dispatchDialogAction(
                    MainScreenDialogAction.OpenRenameDialog(action.sessionId, action.title)
                )
                if (action.dismissMenu) {
                    dispatchOverlayVisibilityAction(MainScreenOverlayVisibilityAction.HIDE_CHAT_MENU)
                }
            }
            is MainScreenChatAction.OpenExportDialog -> {
                dispatchDialogAction(MainScreenDialogAction.ShowExportDialog(action.format))
                if (action.dismissMenu) {
                    dispatchOverlayVisibilityAction(MainScreenOverlayVisibilityAction.HIDE_CHAT_MENU)
                }
            }
            is MainScreenChatAction.ImportConversationSucceeded -> {
                navigateToTopLevel(Screen.Chat)
                scope.launch {
                    snackbarHostState.showSnackbar("已导入 ${action.importedMessageCount} 条消息")
                }
            }
        }
    }

    fun dispatchProjectEditorMenuAction(action: ProjectEditorMenuAction) {
        dispatchProjectSecondaryHostAction(
            MainScreenProjectSecondaryHostAction.DispatchEditorMenuAction(action)
        )
        dispatchOverlayVisibilityAction(MainScreenOverlayVisibilityAction.HIDE_PROJECT_EDITOR_MENU)
    }

    LaunchedEffect(Unit) {
        MainActivity.gitHubOAuthCallbackFlow.collect { callbackUri ->
            authVm.handleGitHubCallback(callbackUri)
            settingsVm.handleGitHubOAuthCallback(callbackUri)
        }
    }

    LaunchedEffect(pagerState.settledPage) {
        val settledPage = pagerState.settledPage
        val navigationTargetPage = topLevelNavigationTargetPage
        // #region debug-point C:chat-settled
        reportGitBackChatFlashMainDebug(
            hypothesisId = "C",
            location = "MainActivity.kt:settledPage",
            msg = "[DEBUG] pager settled page observed",
            data = JSONObject()
                .put("settledPage", settledPage)
                .put("selectedTopLevelPage", selectedTopLevelPage)
                .put("visibleTopLevelPage", visibleTopLevelPage)
                .put("visibleRoute", visibleScreen.route)
                .put("navigationTargetPage", navigationTargetPage ?: JSONObject.NULL)
                .put("drawerValue", if (isChatSessionPanelVisible) "Open" else "Closed")
        )
        // #endregion
        if (navigationTargetPage != null && settledPage != navigationTargetPage) {
            return@LaunchedEffect
        }
        dispatchTopLevelNavigationAction(
            MainScreenTopLevelNavigationAction.SyncSettledPage(settledPage)
        )
    }

    LaunchedEffect(topLevelNavigationTargetPage) {
        val navigationTargetPage = topLevelNavigationTargetPage ?: return@LaunchedEffect
        // #region debug-point C:chat-selected
        reportGitBackChatFlashMainDebug(
            hypothesisId = "C",
            location = "MainActivity.kt:selectedTopLevelPage",
            msg = "[DEBUG] selected top-level page changed",
            data = JSONObject()
                .put("selectedTopLevelPage", selectedTopLevelPage)
                .put("visibleTopLevelPage", visibleTopLevelPage)
                .put("visibleRoute", visibleScreen.route)
                .put("pagerCurrentPage", pagerState.currentPage)
                .put("navigationTargetPage", navigationTargetPage)
                .put("drawerValue", if (isChatSessionPanelVisible) "Open" else "Closed")
        )
        // #endregion
        if (navigationTargetPage != pagerState.currentPage) {
            pagerState.scrollToPage(navigationTargetPage)
        }
    }

    LaunchedEffect(topLevelNavigationState.drawerCommand) {
        when (topLevelNavigationState.drawerCommand) {
            MainScreenDrawerCommand.NONE -> Unit
            MainScreenDrawerCommand.OPEN -> {
                isChatSessionPanelVisible = true
                dispatchTopLevelNavigationAction(
                    MainScreenTopLevelNavigationAction.ConsumeDrawerCommand
                )
            }
            MainScreenDrawerCommand.CLOSE -> {
                isChatSessionPanelVisible = false
                chatSessionPanelBackProgress = 0f
                dispatchTopLevelNavigationAction(
                    MainScreenTopLevelNavigationAction.ConsumeDrawerCommand
                )
            }
        }
    }

    LaunchedEffect(
        projectSecondaryHostState.command,
        projectSecondaryHostState.bridgeState.backRequest
    ) {
        if (projectSecondaryHostState.command != MainScreenProjectSecondaryCommand.CLOSE) {
            return@LaunchedEffect
        }
        projectSecondaryHostState.bridgeState.backRequest?.invoke()
        dispatchProjectSecondaryHostAction(MainScreenProjectSecondaryHostAction.ConsumeCommand)
    }

    LaunchedEffect(authState.authorizationUrl) {
        val authorizationUrl = authState.authorizationUrl?.trim().orEmpty()
        if (authorizationUrl.isBlank()) return@LaunchedEffect
        runCatching { uriHandler.openUri(authorizationUrl) }
    }

    LaunchedEffect(Unit) {
        chatVm.toastMessages.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    BackHandler(enabled = isChatSessionPanelVisible) {
        dispatchHostAction(MainScreenHostAction.CloseChatDrawer)
    }

    PredictiveBackHandler(enabled = isChatSessionPanelVisible) { progress ->
        try {
            progress.collect { backEvent ->
                chatSessionPanelBackProgress = backEvent.progress
            }
            chatSessionPanelBackProgress = 1f
            dispatchHostAction(MainScreenHostAction.CloseChatDrawer)
        } catch (_: CancellationException) {
            chatSessionPanelBackProgress = 0f
        } finally {
            chatSessionPanelBackProgress = 0f
        }
    }

    val projectSecondaryHostBridgeState = projectSecondaryHostState.bridgeState
    val projectSecondaryChromeState = projectSecondaryHostBridgeState.chromeState
    val projectSecondaryBackRequest = projectSecondaryHostBridgeState.backRequest
    val projectSecondaryBackProgress = projectSecondaryHostBridgeState.backProgress
    val shellState = resolveMainScreenShellState(
        shellScreens = shellScreens,
        selectedTopLevelPage = settledTopLevelPage,
        visibleTopLevelPage = visibleTopLevelPage,
        topLevelNavigationTargetPage = topLevelNavigationTargetPage,
        pagerIsScrollInProgress = pagerState.isScrollInProgress,
        pagerCurrentPage = pagerState.currentPage,
        pagerSettledPage = pagerState.settledPage,
        settingsSubpage = settingsSubpage,
        projectSecondaryChromeState = projectSecondaryChromeState,
        chatPageIndex = chatPageIndex,
        topLevelHistoryLastPage = topLevelHistory.lastOrNull(),
        topLevelBackProgress = topLevelBackProgress
    )
    val chromeScreen = shellState.chromeScreen
    val secondaryHostState = resolveMainScreenSecondaryHostState(
        shellState = shellState,
        settingsBackProgress = settingsBackProgress,
        projectSecondaryBackProgress = projectSecondaryHostState.bridgeState.backProgress
    )
    val secondaryHostRuntimeState = resolveMainScreenSecondaryHostRuntimeState(
        secondaryHostState = secondaryHostState,
        projectSecondaryHostState = projectSecondaryHostState
    )
    val isSettingsSecondaryPage = secondaryHostRuntimeState.isSettingsSecondaryPage
    val isProjectSecondaryPage = secondaryHostRuntimeState.isProjectSecondaryPage
    val hasSecondaryPage = secondaryHostRuntimeState.hasSecondaryPage
    val showBottomBar = shellState.showBottomBar
    val isChatChromeVisible = shellState.isChatChromeVisible
    val topBarState = resolveMainScreenTopBarState(
        settingsSubpage = settingsSubpage,
        chromeScreen = chromeScreen,
        chatSessionTitle = chatState.sessionTitle,
        usageSummary = chatState.usageSummary,
        projectSecondaryChromeState = projectSecondaryChromeState,
        isProjectSecondaryPage = isProjectSecondaryPage,
        isChatChromeVisible = isChatChromeVisible,
        hasTopLevelHistory = shellState.hasTopLevelHistory,
        isTopLevelNavigationSettled = shellState.isTopLevelNavigationSettled
    )
    val overlayState = resolveMainScreenOverlayState(
        shellState = shellState,
        topBarState = topBarState,
        isChatDrawerOpen = isChatSessionPanelVisible
    )
    val topLevelBackProgressBucket = if (topLevelBackProgress > 0f) {
        (topLevelBackProgress * 10).toInt().coerceIn(0, 10)
    } else {
        -1
    }
    val secondaryHostBackProgressBucket = if (secondaryHostRuntimeState.hostBackProgress > 0f) {
        (secondaryHostRuntimeState.hostBackProgress * 10).toInt().coerceIn(0, 10)
    } else {
        -1
    }
    val pagerVisualIndex = (
        pagerState.currentPage.toFloat() + pagerState.currentPageOffsetFraction
        ).coerceIn(0f, shellScreens.lastIndex.toFloat())
    val topLevelPreviewState = resolveMainScreenTopLevelPredictivePreviewState(
        selectedTopLevelPage = selectedTopLevelPage,
        shellState = shellState
    )

    BackHandler(enabled = secondaryHostRuntimeState.canHandleHostBackGesture) {
        dispatchHostAction(secondaryHostRuntimeState.hostBackAction)
    }

    LaunchedEffect(
        shellState.chromeScreen.route,
        visibleScreen.route,
        selectedTopLevelPage,
        visibleTopLevelPage,
        isChatChromeVisible,
        isChatSessionPanelVisible,
        showChatMenu,
        chatState.sessionId,
        chatState.messages.size
    ) {
        // #region debug-point E:chat-host-snapshot
        reportGitBackChatFlashMainDebug(
            hypothesisId = "E",
            location = "MainActivity.kt:chatHostSnapshot",
            msg = "[DEBUG] chat host snapshot",
            data = JSONObject()
                .put("chromeRoute", shellState.chromeScreen.route)
                .put("visibleRoute", visibleScreen.route)
                .put("selectedTopLevelPage", selectedTopLevelPage)
                .put("visibleTopLevelPage", visibleTopLevelPage)
                .put("isChatChromeVisible", isChatChromeVisible)
                .put("drawerOpen", isChatSessionPanelVisible)
                .put("showChatMenu", showChatMenu)
                .put("chatSessionId", chatState.sessionId)
                .put("chatMessageCount", chatState.messages.size)
        )
        // #endregion
    }

    LaunchedEffect(
        topLevelBackProgressBucket,
        topLevelPreviewState.currentPage,
        topLevelPreviewState.targetPage,
        topLevelPreviewState.isVisible
    ) {
        if (topLevelBackProgressBucket < 0) return@LaunchedEffect
        // #region debug-point E:top-level-progress-bucket
        reportGitBackChatFlashMainDebug(
            hypothesisId = "E",
            location = "MainActivity.kt:topLevelBackProgressBucket",
            msg = "[DEBUG] top-level back progress bucket",
            data = JSONObject()
                .put("bucket", topLevelBackProgressBucket)
                .put("progress", topLevelBackProgress)
                .put("previewVisible", topLevelPreviewState.isVisible)
                .put("previewCurrentPage", topLevelPreviewState.currentPage)
                .put("previewTargetPage", topLevelPreviewState.targetPage ?: JSONObject.NULL)
                .put("selectedTopLevelPage", selectedTopLevelPage)
                .put("visibleTopLevelPage", visibleTopLevelPage)
        )
        // #endregion
    }

    LaunchedEffect(
        secondaryHostBackProgressBucket,
        secondaryHostRuntimeState.isSettingsSecondaryPage,
        secondaryHostRuntimeState.isProjectSecondaryPage,
        secondaryHostRuntimeState.hostBackAction::class.simpleName
    ) {
        if (secondaryHostBackProgressBucket < 0) return@LaunchedEffect
        // #region debug-point E:secondary-host-progress-bucket
        reportGitBackChatFlashMainDebug(
            hypothesisId = "E",
            location = "MainActivity.kt:secondaryHostBackProgressBucket",
            msg = "[DEBUG] secondary host back progress bucket",
            data = JSONObject()
                .put("bucket", secondaryHostBackProgressBucket)
                .put("progress", secondaryHostRuntimeState.hostBackProgress)
                .put("isSettingsSecondaryPage", secondaryHostRuntimeState.isSettingsSecondaryPage)
                .put("isProjectSecondaryPage", secondaryHostRuntimeState.isProjectSecondaryPage)
                .put(
                    "hostBackAction",
                    secondaryHostRuntimeState.hostBackAction::class.simpleName ?: "unknown"
                )
        )
        // #endregion
    }

    LaunchedEffect(
        shellState.shellOwnerTopLevelPage,
        visibleTopLevelPage,
        visibleScreen.route,
        projectSecondaryChromeState.active,
        projectSecondaryChromeState.title,
        projectSecondaryBackRequest
    ) {
        // #region debug-point B:project-secondary-state
        reportGitBackChatFlashMainDebug(
            hypothesisId = "B",
            location = "MainActivity.kt:projectSecondaryState",
            msg = "[DEBUG] project secondary state snapshot",
            data = JSONObject()
                .put("shellOwnerTopLevelPage", shellState.shellOwnerTopLevelPage)
                .put("visibleTopLevelPage", visibleTopLevelPage)
                .put("visibleRoute", visibleScreen.route)
                .put("selectedTopLevelPage", selectedTopLevelPage)
                .put("projectSecondaryActive", projectSecondaryChromeState.active)
                .put("projectSecondaryTitle", projectSecondaryChromeState.title)
                .put("isProjectSecondaryPage", isProjectSecondaryPage)
                .put("hasBackRequest", projectSecondaryBackRequest != null)
        )
        // #endregion
    }

    LaunchedEffect(secondaryHostRuntimeState.shouldResetProjectSecondaryCarrier) {
        if (secondaryHostRuntimeState.shouldResetProjectSecondaryCarrier) {
            dispatchProjectSecondaryHostAction(MainScreenProjectSecondaryHostAction.Reset)
        }
    }

    LaunchedEffect(
        selectedTopLevelPage,
        visibleTopLevelPage,
        visibleScreen.route,
        settingsSubpage,
        showChatMenu,
        isChatSessionPanelVisible,
        topLevelNavigationTargetPage,
        topLevelHistory.size
    ) {
        // #region debug-point C:shell-visibility-snapshot
        reportGitBackChatFlashMainDebug(
            hypothesisId = "C",
            location = "MainActivity.kt:shellVisibilitySnapshot",
            msg = "[DEBUG] shell visibility snapshot",
            data = JSONObject()
                .put("selectedTopLevelPage", selectedTopLevelPage)
                .put("visibleTopLevelPage", visibleTopLevelPage)
                .put("visibleRoute", visibleScreen.route)
                .put("settingsSubpage", settingsSubpage.toString())
                .put("showChatMenu", showChatMenu)
                .put("drawerCurrentValue", if (isChatSessionPanelVisible) "Open" else "Closed")
                .put("drawerTargetValue", if (isChatSessionPanelVisible) "Open" else "Closed")
                .put("topLevelNavigationTargetPage", topLevelNavigationTargetPage ?: JSONObject.NULL)
                .put("topLevelHistorySize", topLevelHistory.size)
                .put("projectSecondaryActive", projectSecondaryChromeState.active)
                .put("projectSecondaryTitle", projectSecondaryChromeState.title)
        )
        // #endregion
    }

    BackHandler(enabled = overlayState.canHandleTopLevelBack) {
        // #region debug-point C:top-level-back
        reportGitBackChatFlashMainDebug(
            hypothesisId = "C",
            location = "MainActivity.kt:topLevelBackHandler",
            msg = "[DEBUG] top-level back handler fired",
            data = JSONObject()
                .put("selectedTopLevelPage", selectedTopLevelPage)
                .put("visibleTopLevelPage", visibleTopLevelPage)
                .put("visibleRoute", visibleScreen.route)
                .put("topLevelHistorySize", topLevelHistory.size)
                .put("projectSecondaryActive", projectSecondaryChromeState.active)
        )
        // #endregion
        dispatchHostAction(MainScreenHostAction.NavigateTopLevelBack)
    }

    PredictiveBackHandler(enabled = overlayState.canHandleTopLevelBack) { progress ->
        try {
            progress.collect { backEvent ->
                dispatchTopLevelNavigationAction(
                    MainScreenTopLevelNavigationAction.UpdateBackProgress(backEvent.progress)
                )
            }
            dispatchTopLevelNavigationAction(MainScreenTopLevelNavigationAction.UpdateBackProgress(1f))
            // #region debug-point C:top-level-predictive
            reportGitBackChatFlashMainDebug(
                hypothesisId = "C",
                location = "MainActivity.kt:topLevelPredictiveBack",
                msg = "[DEBUG] top-level predictive back committed",
                data = JSONObject()
                    .put("selectedTopLevelPage", selectedTopLevelPage)
                    .put("visibleTopLevelPage", visibleTopLevelPage)
                    .put("visibleRoute", visibleScreen.route)
                    .put("topLevelHistorySize", topLevelHistory.size)
                    .put("projectSecondaryActive", projectSecondaryChromeState.active)
            )
            // #endregion
            dispatchHostAction(MainScreenHostAction.NavigateTopLevelBack)
        } catch (_: CancellationException) {
            dispatchTopLevelNavigationAction(MainScreenTopLevelNavigationAction.UpdateBackProgress(0f))
        } finally {
            dispatchTopLevelNavigationAction(MainScreenTopLevelNavigationAction.UpdateBackProgress(0f))
        }
    }

    PredictiveBackHandler(enabled = secondaryHostRuntimeState.canHandleHostBackGesture) { progress ->
        try {
            progress.collect { backEvent ->
                dispatchSettingsAction(
                    MainScreenSettingsAction.UpdateBackProgress(backEvent.progress)
                )
            }
            dispatchSettingsAction(MainScreenSettingsAction.UpdateBackProgress(1f))
            dispatchHostAction(secondaryHostRuntimeState.hostBackAction)
        } catch (_: CancellationException) {
            dispatchSettingsAction(MainScreenSettingsAction.UpdateBackProgress(0f))
        } finally {
            dispatchSettingsAction(MainScreenSettingsAction.UpdateBackProgress(0f))
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
                        dispatchChatAction(
                            MainScreenChatAction.ImportConversationSucceeded(
                                importedMessageCount = count
                            )
                        )
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
            dispatchDialogAction(MainScreenDialogAction.ClearPendingExport)
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

        dispatchDialogAction(MainScreenDialogAction.ClearPendingExport)
    }

    fun openRenameDialog(sessionId: String, title: String) {
        dispatchChatAction(MainScreenChatAction.OpenRenameDialog(sessionId, title))
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
        dispatchDialogAction(
            MainScreenDialogAction.UpdateTaskProjectPath(resolvedPath ?: uri.toString())
        )

        if (resolvedPath == null) {
            scope.launch {
                snackbarHostState.showSnackbar("已选择文件夹，但系统未能解析出绝对路径，可手动调整")
            }
        }
    }

    @Composable
    fun TopLevelPageContent(
        page: Int,
        modifier: Modifier = Modifier,
        previewMode: Boolean = false
    ) {
        val pageScreen = shellScreens[page]
        val pageOwnsShellState = ownsMainScreenShellState(
            page = page,
            previewMode = previewMode,
            shellState = shellState
        )
        val pageLayoutState = resolveMainScreenPageLayoutState(
            pageScreen = pageScreen,
            shellState = shellState
        )
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(bottom = pageLayoutState.bottomPadding)
        ) {
            when (pageScreen) {
                is Screen.Chat -> {
                    LaunchedEffect(
                        page,
                        previewMode,
                        pageOwnsShellState,
                        isChatChromeVisible,
                        shellState.chromeScreen.route,
                        visibleScreen.route,
                        chatState.sessionId,
                        chatState.messages.size
                    ) {
                        // #region debug-point E:chat-page-composition
                        reportGitBackChatFlashMainDebug(
                            hypothesisId = "E",
                            location = "MainActivity.kt:chatPageComposition",
                            msg = "[DEBUG] chat page composition snapshot",
                            data = JSONObject()
                                .put("page", page)
                                .put("previewMode", previewMode)
                                .put("pageOwnsShellState", pageOwnsShellState)
                                .put("isChatChromeVisible", isChatChromeVisible)
                                .put("computedIsScreenActive", pageOwnsShellState &&
                                    isChatChromeVisible &&
                                    page == chatPageIndex)
                                .put("chromeRoute", shellState.chromeScreen.route)
                                .put("visibleRoute", visibleScreen.route)
                                .put("chatSessionId", chatState.sessionId)
                                .put("chatMessageCount", chatState.messages.size)
                        )
                        // #endregion
                    }
                    val shouldRenderFullChat = !previewMode
                    val chatSecondaryVisible = shouldRenderFullChat &&
                        pageOwnsShellState &&
                        page == chatPageIndex &&
                        isChatSessionPanelVisible

                    @Composable
                    fun ChatMainPage() {
                        if (shouldRenderFullChat) {
                            ChatScreen(
                                state = chatState,
                                isScreenActive = pageOwnsShellState &&
                                    isChatChromeVisible &&
                                    page == chatPageIndex,
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
                                onNewSession = {
                                    dispatchChatAction(MainScreenChatAction.StartNewSession())
                                },
                                title = chatState.sessionTitle,
                                hasApiKey = chatVm.hasActiveApiKey(chatConfig),
                                workflowExecutionMode = effectiveChatConfig.workflowExecutionMode,
                                autoRouteBeforeExecution = false,
                                onNavigateToSettings = {
                                    navigateToTopLevel(Screen.Settings)
                                },
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
                        } else {
                            ChatPagePreviewPlaceholder(
                                title = chatState.sessionTitle,
                                messageCount = chatState.messages.size
                            )
                        }
                    }

                    if (previewMode) {
                        ChatMainPage()
                    } else {
                        val panelWidth = 320.dp
                        val panelWidthPx = with(LocalDensity.current) { panelWidth.toPx() }
                        val animatedPanelProgress by animateFloatAsState(
                            targetValue = if (chatSecondaryVisible) 1f else 0f,
                            animationSpec = tween(
                                durationMillis = 320,
                                easing = FastOutSlowInEasing
                            ),
                            label = "chatSessionPanelProgress"
                        )
                        val panelProgress = if (
                            chatSecondaryVisible && chatSessionPanelBackProgress > 0f
                        ) {
                            1f - chatSessionPanelBackProgress
                        } else {
                            animatedPanelProgress
                        }.coerceIn(0f, 1f)

                        Box(modifier = Modifier.fillMaxSize()) {
                            ChatMainPage()

                            if (panelProgress > 0.001f) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.Black.copy(alpha = 0.12f * panelProgress))
                                        .clickable(
                                            enabled = chatSecondaryVisible,
                                            indication = null,
                                            interactionSource = remember { MutableInteractionSource() }
                                        ) {
                                            dispatchHostAction(MainScreenHostAction.CloseChatDrawer)
                                        }
                                )
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    SessionDrawerContent(
                                        currentSessionId = chatState.sessionId,
                                        sessions = chatSessions,
                                        onNewSession = {
                                            dispatchChatAction(
                                                MainScreenChatAction.StartNewSession(closeDrawer = true)
                                            )
                                        },
                                        onNewTask = {
                                            dispatchChatAction(
                                                MainScreenChatAction.OpenTaskDialog(projectPath = "")
                                            )
                                        },
                                        onLoadSession = { sessionId ->
                                            dispatchChatAction(
                                                MainScreenChatAction.LoadSession(sessionId = sessionId)
                                            )
                                        },
                                        onRenameSession = { sessionId ->
                                            val sessionTitle = chatSessions
                                                .firstOrNull { it.id == sessionId }
                                                ?.title
                                                .orEmpty()
                                            dispatchChatAction(
                                                MainScreenChatAction.OpenRenameDialog(
                                                    sessionId = sessionId,
                                                    title = sessionTitle
                                                )
                                            )
                                        },
                                        onDeleteSession = { sessionId ->
                                            dispatchChatAction(
                                                MainScreenChatAction.DeleteSession(sessionId = sessionId)
                                            )
                                        },
                                        modifier = Modifier
                                            .fillMaxHeight()
                                            .width(panelWidth)
                                            .graphicsLayer {
                                                translationX = -(1f - panelProgress) * panelWidthPx
                                            }
                                    )
                                }
                            }
                        }
                    }
                }

                is Screen.Projects -> {
                    ProjectScreen(
                        config = settingsConfig,
                        currentProjectPath = chatState.projectPath,
                        currentProjectScopePath = chatState.activeProjectScopePath,
                        projectKnowledgeDraftPaths = chatState.projectKnowledgePaths,
                        projectKnowledgeSnapshots = chatState.projectKnowledgeSnapshots,
                        projectRules = chatState.projectRules,
                        projectMemories = chatState.projectMemories,
                        projectSkills = chatState.projectSkills,
                        projectToolPreferences = chatState.projectToolPreferences,
                        repoScopedConfigs = chatState.repoScopedConfigs,
                        mcpToolNames = mcpStatuses.flatMap { status ->
                            status.toolNames.map { "mcp_$it" }
                        }.distinct().sorted(),
                        sessions = chatSessions,
                        onNewTask = {
                            dispatchDialogAction(
                                MainScreenDialogAction.ShowTaskDialog(
                                    chatState.projectPath.orEmpty()
                                )
                            )
                        },
                        onOpenChat = {
                            navigateToTopLevel(Screen.Chat)
                        },
                        onProjectScopeChanged = { scopePath ->
                            chatVm.switchProjectScope(scopePath)
                        },
                        onUpdateProjectConfig = { scopePath, rules, memories, skills ->
                            chatVm.updateProjectConfig(scopePath, rules, memories, skills)
                        },
                        onUpdateProjectToolPreferences = { scopePath, preferences ->
                            chatVm.updateProjectToolPreferences(scopePath, preferences)
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
                        },
                        onProjectSecondaryHostBridgeStateChanged = { state ->
                            if (pageOwnsShellState) {
                                dispatchProjectSecondaryHostAction(
                                    MainScreenProjectSecondaryHostAction.UpdateBridgeState(state)
                                )
                            }
                        },
                        projectSecondaryHostBackProgress = if (pageOwnsShellState) {
                            projectSecondaryBackProgress
                        } else {
                            0f
                        },
                        editorMenuActionSignal = if (pageOwnsShellState) {
                            projectEditorMenuActionSignal
                        } else {
                            0
                        },
                        editorMenuAction = if (pageOwnsShellState) {
                            projectEditorMenuAction
                        } else {
                            null
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
                        onOpenChat = {
                            navigateToTopLevel(Screen.Chat)
                        },
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
                    @Composable
                    fun SettingsMainPage() {
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
                            onClearGitHubToken = { settingsVm.clearGitHubToken() },
                            onOpenThemePage = {
                                dispatchSettingsAction(MainScreenSettingsAction.OpenThemePage)
                            },
                            onOpenAboutPage = {
                                dispatchSettingsAction(MainScreenSettingsAction.OpenAboutPage)
                            }
                        )
                    }
                    @Composable
                    fun SettingsDetailPage() {
                        when (settingsSubpage) {
                            SettingsSubpage.Main -> SettingsMainPage()
                            SettingsSubpage.Theme -> ThemeSettingsPage()
                            SettingsSubpage.About -> AboutPage()
                        }
                    }

                    LaunchedEffect(
                        previewMode,
                        settingsSubpage,
                        secondaryHostRuntimeState.hostBackProgress
                    ) {
                        // #region debug-point U:settings-host-state
                        reportGitBackChatFlashMainDebug(
                            hypothesisId = "U2",
                            location = "MainActivity.kt:settingsHostState",
                            msg = "[DEBUG] settings host state snapshot",
                            data = JSONObject()
                                .put("previewMode", previewMode)
                                .put("settingsSubpage", settingsSubpage.toString())
                                .put("hostBackProgress", secondaryHostRuntimeState.hostBackProgress)
                                .put("isSettingsSecondaryPage", isSettingsSecondaryPage)
                        )
                        // #endregion
                    }

                    if (previewMode) {
                        SettingsDetailPage()
                    } else {
                        MurongNestedPredictiveBackHost(
                            detailVisible = isSettingsSecondaryPage,
                            backProgress = secondaryHostRuntimeState.hostBackProgress,
                            modifier = Modifier.fillMaxSize(),
                            wrapDetailInSecondarySurface = false,
                            wrapListInSecondarySurface = false,
                            detailContent = { SettingsDetailPage() },
                            listContent = { SettingsMainPage() }
                        )
                    }
                }
            }
        }
    }

    @Composable
    fun MainTopBarChrome(
        topBarStateValue: MainScreenTopBarState,
        modifier: Modifier = Modifier
    ) {
        MurongGlassSurface(
            modifier = modifier
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            shape = RoundedCornerShape(26.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            surfaceColorOverride = chromeColor.copy(alpha = if (darkMode) 0.60f else 0.68f)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.width(92.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    when (topBarStateValue.leadingAction) {
                        MainScreenTopBarLeadingAction.SETTINGS_BACK -> {
                            MurongOutlinedActionButton(
                                text = "返回",
                                onClick = {
                                    dispatchHostAction(topBarStateValue.leadingHostAction)
                                }
                            )
                        }
                        MainScreenTopBarLeadingAction.PROJECT_BACK -> {
                            MurongOutlinedActionButton(
                                text = "返回",
                                onClick = {
                                    dispatchHostAction(topBarStateValue.leadingHostAction)
                                }
                            )
                        }
                        MainScreenTopBarLeadingAction.CHAT_DRAWER -> {
                            IconButton(
                                onClick = {
                                    dispatchHostAction(
                                        if (isChatSessionPanelVisible) {
                                            MainScreenHostAction.CloseChatDrawer
                                        } else {
                                            topBarStateValue.leadingHostAction
                                        }
                                    )
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Menu,
                                    contentDescription = if (isChatSessionPanelVisible) {
                                        "关闭会话列表"
                                    } else {
                                        "打开会话列表"
                                    }
                                )
                            }
                        }
                        MainScreenTopBarLeadingAction.TOP_LEVEL_BACK -> {
                            MurongOutlinedActionButton(
                                text = "返回",
                                onClick = {
                                    dispatchHostAction(topBarStateValue.leadingHostAction)
                                }
                            )
                        }
                        MainScreenTopBarLeadingAction.BRAND -> {
                            Text(
                                text = "慕容AI",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = topBarStateValue.title,
                        modifier = if (
                            topBarStateValue.showProjectEditorMenu &&
                            projectSecondaryChromeState.title.isNotBlank()
                        ) {
                            Modifier.combinedClickable(
                                onClick = {},
                                onLongClick = {
                                    copyTextToClipboard(context, projectSecondaryChromeState.title)
                                    scope.launch {
                                        snackbarHostState.showSnackbar("已复制文件名")
                                    }
                                }
                            )
                        } else {
                            Modifier
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (topBarStateValue.subtitle.isNotBlank()) {
                        Text(
                            text = topBarStateValue.subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Row(
                    modifier = Modifier.widthIn(min = 92.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End
                ) {
                    if (topBarStateValue.showProjectEditorMenu) {
                        Box {
                            IconButton(
                                onClick = {
                                    dispatchOverlayVisibilityAction(
                                        MainScreenOverlayVisibilityAction.SHOW_PROJECT_EDITOR_MENU
                                    )
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.MoreVert,
                                    contentDescription = "文件操作"
                                )
                            }
                            DropdownMenu(
                                expanded = showProjectEditorMenu,
                                onDismissRequest = {
                                    dispatchOverlayVisibilityAction(
                                        MainScreenOverlayVisibilityAction.HIDE_PROJECT_EDITOR_MENU
                                    )
                                }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("复制文件名") },
                                    onClick = {
                                        copyTextToClipboard(context, projectSecondaryChromeState.title)
                                        dispatchOverlayVisibilityAction(
                                            MainScreenOverlayVisibilityAction.HIDE_PROJECT_EDITOR_MENU
                                        )
                                    }
                                )
                                projectSecondaryChromeState.subtitle
                                    .takeIf { it.isNotBlank() }
                                    ?.let { relativePath ->
                                        DropdownMenuItem(
                                            text = { Text("复制相对路径") },
                                            onClick = {
                                                copyTextToClipboard(context, relativePath)
                                                dispatchOverlayVisibilityAction(
                                                    MainScreenOverlayVisibilityAction.HIDE_PROJECT_EDITOR_MENU
                                                )
                                            }
                                        )
                                    }
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text(ProjectEditorMenuAction.SEARCH_REPLACE.label) },
                                    onClick = {
                                        dispatchProjectEditorMenuAction(ProjectEditorMenuAction.SEARCH_REPLACE)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(ProjectEditorMenuAction.LANGUAGE.label) },
                                    onClick = {
                                        dispatchProjectEditorMenuAction(ProjectEditorMenuAction.LANGUAGE)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(ProjectEditorMenuAction.DIAGNOSTICS.label) },
                                    onClick = {
                                        dispatchProjectEditorMenuAction(ProjectEditorMenuAction.DIAGNOSTICS)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(ProjectEditorMenuAction.CONFLICTS.label) },
                                    onClick = {
                                        dispatchProjectEditorMenuAction(ProjectEditorMenuAction.CONFLICTS)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(ProjectEditorMenuAction.OUTLINE.label) },
                                    onClick = {
                                        dispatchProjectEditorMenuAction(ProjectEditorMenuAction.OUTLINE)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(ProjectEditorMenuAction.AI_COMPLETION.label) },
                                    onClick = {
                                        dispatchProjectEditorMenuAction(ProjectEditorMenuAction.AI_COMPLETION)
                                    }
                                )
                                HorizontalDivider()
                                listOf(
                                    ProjectEditorMenuAction.LINE_COPY,
                                    ProjectEditorMenuAction.LINE_CUT,
                                    ProjectEditorMenuAction.LINE_DELETE,
                                    ProjectEditorMenuAction.LINE_CLEAR,
                                    ProjectEditorMenuAction.LINE_REPLACE,
                                    ProjectEditorMenuAction.LINE_DUPLICATE,
                                    ProjectEditorMenuAction.LINE_UPPERCASE,
                                    ProjectEditorMenuAction.LINE_LOWERCASE,
                                    ProjectEditorMenuAction.LINE_INDENT_MORE,
                                    ProjectEditorMenuAction.LINE_INDENT_LESS,
                                    ProjectEditorMenuAction.LINE_TOGGLE_COMMENT
                                ).forEach { action ->
                                    DropdownMenuItem(
                                        text = { Text(action.label) },
                                        onClick = {
                                            dispatchProjectEditorMenuAction(action)
                                        }
                                    )
                                }
                            }
                        }
                    } else {
                        if (topBarStateValue.showChatUsageSummary) {
                            ChatUsageSummaryBadge(usageSummary = chatState.usageSummary)
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                    }
                    if (topBarStateValue.showTag) {
                        MurongTagButton(text = topBarStateValue.tag, onClick = {})
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    if (topBarStateValue.showChatOverflowMenu) {
                        Box {
                            IconButton(
                                onClick = {
                                    dispatchOverlayVisibilityAction(
                                        MainScreenOverlayVisibilityAction.SHOW_CHAT_MENU
                                    )
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.MoreVert,
                                    contentDescription = "聊天操作"
                                )
                            }
                            DropdownMenu(
                                expanded = showChatMenu,
                                onDismissRequest = {
                                    dispatchOverlayVisibilityAction(
                                        MainScreenOverlayVisibilityAction.HIDE_CHAT_MENU
                                    )
                                }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("新对话") },
                                    onClick = {
                                        dispatchChatAction(
                                            MainScreenChatAction.StartNewSession(
                                                dismissMenu = true
                                            )
                                        )
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("新任务") },
                                    onClick = {
                                        dispatchChatAction(
                                            MainScreenChatAction.OpenTaskDialog(
                                                projectPath = "",
                                                dismissMenu = true
                                            )
                                        )
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("重命名会话") },
                                    onClick = {
                                        dispatchChatAction(
                                            MainScreenChatAction.OpenRenameDialog(
                                                sessionId = chatState.sessionId,
                                                title = chatState.sessionTitle,
                                                dismissMenu = true
                                            )
                                        )
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("导出会话") },
                                    onClick = {
                                        dispatchChatAction(
                                            MainScreenChatAction.OpenExportDialog(
                                                dismissMenu = true
                                            )
                                        )
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
                                        dispatchOverlayVisibilityAction(
                                            MainScreenOverlayVisibilityAction.HIDE_CHAT_MENU
                                        )
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
                                            dispatchOverlayVisibilityAction(
                                                MainScreenOverlayVisibilityAction.HIDE_CHAT_MENU
                                            )
                                        }
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("导入对话") },
                                    onClick = {
                                        importConversationLauncher.launch(
                                            arrayOf("text/*", "application/json")
                                        )
                                        dispatchOverlayVisibilityAction(
                                            MainScreenOverlayVisibilityAction.HIDE_CHAT_MENU
                                        )
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("复制全部") },
                                    onClick = {
                                        copyTextToClipboard(
                                            context = context,
                                            text = buildConversationText(chatState.messages)
                                        )
                                        dispatchOverlayVisibilityAction(
                                            MainScreenOverlayVisibilityAction.HIDE_CHAT_MENU
                                        )
                                    }
                                )
                                if (chatState.messages.any { it.role == "user" }) {
                                    DropdownMenuItem(
                                        text = { Text("回退最近一轮") },
                                        onClick = {
                                            chatVm.rollbackLastTurn()
                                            dispatchOverlayVisibilityAction(
                                                MainScreenOverlayVisibilityAction.HIDE_CHAT_MENU
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(overlayState.shouldCloseChatDrawer) {
        if (overlayState.shouldCloseChatDrawer) {
            dispatchHostAction(MainScreenHostAction.CloseChatDrawer)
        }
    }
    LaunchedEffect(overlayState.shouldDismissChatMenu) {
        if (overlayState.shouldDismissChatMenu) {
            dispatchOverlayVisibilityAction(MainScreenOverlayVisibilityAction.HIDE_CHAT_MENU)
        }
    }
    LaunchedEffect(overlayState.shouldDismissProjectEditorMenu) {
        if (overlayState.shouldDismissProjectEditorMenu) {
            dispatchOverlayVisibilityAction(MainScreenOverlayVisibilityAction.HIDE_PROJECT_EDITOR_MENU)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .murongGlassSource(LocalMurongHazeState.current)
            ) {
                MurongBackgroundLayer(
                    modifier = Modifier.fillMaxSize(),
                    darkMode = darkMode
                )
            }
            Scaffold(
                snackbarHost = {
                    SnackbarHost(hostState = snackbarHostState)
                },
                containerColor = Color.Transparent,
                topBar = {
                    if (showBottomBar && !hasSecondaryPage) {
                        BoxWithConstraints(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clipToBounds()
                        ) {
                            val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
                            val lowerPage = floor(pagerVisualIndex).toInt()
                                .coerceIn(0, shellScreens.lastIndex)
                            val upperPage = ceil(pagerVisualIndex).toInt()
                                .coerceIn(0, shellScreens.lastIndex)
                            val renderedPages = listOf(lowerPage, upperPage).distinct()
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clipToBounds()
                            ) {
                                renderedPages.forEach { page ->
                                    val pageTopBarState = resolveMainScreenTopBarState(
                                        settingsSubpage = SettingsSubpage.Main,
                                        chromeScreen = shellScreens[page],
                                        chatSessionTitle = chatState.sessionTitle,
                                        usageSummary = chatState.usageSummary,
                                        projectSecondaryChromeState = ProjectSecondaryChromeState(),
                                        isProjectSecondaryPage = false,
                                        isChatChromeVisible = page == chatPageIndex,
                                        hasTopLevelHistory = shellState.hasTopLevelHistory,
                                        isTopLevelNavigationSettled = page == settledTopLevelPage
                                    )
                                    MainTopBarChrome(
                                        topBarStateValue = pageTopBarState,
                                        modifier = Modifier.graphicsLayer {
                                            translationX = (page - pagerVisualIndex) * widthPx
                                        }
                                    )
                                }
                            }
                        }
                    } else {
                        MainTopBarChrome(topBarState)
                    }
                }
            ) { innerPadding ->
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .clipToBounds()
                ) {
                    val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .murongGlassSource(LocalMurongHazeState.current)
                    ) {
                        topLevelPreviewState.targetPage?.takeIf { topLevelPreviewState.isVisible }?.let { targetPage ->
                            val directionMultiplier = topLevelPreviewState.directionMultiplier
                            val currentTranslationX =
                                widthPx * topLevelBackProgress * directionMultiplier
                            val previousTranslationX =
                                currentTranslationX - (widthPx * directionMultiplier)
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        translationX = previousTranslationX
                                        alpha = 1f
                                    }
                            ) {
                                TopLevelPageContent(
                                    page = targetPage,
                                    previewMode = true
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        translationX = currentTranslationX
                                        alpha = 1f
                                    }
                            ) {
                                TopLevelPageContent(
                                    page = topLevelPreviewState.currentPage,
                                    previewMode = true
                                )
                            }
                        } ?: HorizontalPager(
                            state = pagerState,
                            modifier = Modifier
                                .fillMaxSize()
                                .clipToBounds(),
                            userScrollEnabled = showBottomBar
                        ) { page ->
                            TopLevelPageContent(page = page)
                        }
                    }
                }
            }

            if (showBottomBar) {
                MurongFloatingBottomBar(
                    items = listOf(
                        MurongBottomBarItem("聊天", Icons.Outlined.MoreVert),
                        MurongBottomBarItem("项目", Icons.Outlined.Edit),
                        MurongBottomBarItem("工具", Icons.Outlined.Search),
                        MurongBottomBarItem("设置", Icons.Outlined.Settings)
                    ),
                    selectedIndex = selectedTopLevelPage,
                    visualIndex = pagerVisualIndex,
                    onSelect = { index ->
                        dispatchHostAction(MainScreenHostAction.NavigateToTopLevelPage(index))
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }

        if (showTaskDialog) {
            CreateTaskDialog(
                onDismiss = { dispatchDialogAction(MainScreenDialogAction.HideTaskDialog) },
                projectPath = taskProjectPath,
                onProjectPathChange = {
                    dispatchDialogAction(MainScreenDialogAction.UpdateTaskProjectPath(it))
                },
                onPickFolder = {
                    folderPickerLauncher.launch(null)
                },
                onCreateTask = { projectPath ->
                    // #region debug-point F:create-local-project-task
                    reportGitBackChatFlashMainDebug(
                        hypothesisId = "F",
                        location = "MainActivity.kt:CreateTaskDialog:onCreateTask:before",
                        msg = "[DEBUG] create task requested",
                        data = JSONObject()
                            .put("projectPath", projectPath)
                            .put("trimmedProjectPath", projectPath.trim())
                            .put("selectedTopLevelPage", selectedTopLevelPage)
                            .put("visibleTopLevelPage", visibleTopLevelPage)
                            .put("visibleRoute", visibleScreen.route)
                    )
                    // #endregion
                    chatVm.startTask(projectPath)
                    // #region debug-point F:create-local-project-task
                    reportGitBackChatFlashMainDebug(
                        hypothesisId = "F",
                        location = "MainActivity.kt:CreateTaskDialog:onCreateTask:after",
                        msg = "[DEBUG] create task returned",
                        data = JSONObject()
                            .put("projectPath", projectPath)
                            .put("trimmedProjectPath", projectPath.trim())
                            .put("currentSessionId", chatState.sessionId)
                            .put("currentProjectPath", chatState.projectPath ?: JSONObject.NULL)
                    )
                    // #endregion
                    dispatchDialogAction(MainScreenDialogAction.HideTaskDialog)
                    dispatchHostAction(MainScreenHostAction.CloseChatDrawer)
                    navigateToTopLevel(Screen.Chat)
                }
            )
        }

        renameSessionTargetId?.let { sessionId ->
            RenameSessionDialog(
                value = renameSessionDraft,
                onValueChange = {
                    dispatchDialogAction(MainScreenDialogAction.UpdateRenameDraft(it))
                },
                onDismiss = { dispatchDialogAction(MainScreenDialogAction.CloseRenameDialog) },
                onConfirm = {
                    val renamed = chatVm.renameSession(sessionId, renameSessionDraft)
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            if (renamed) "会话已重命名" else "重命名失败，请检查标题"
                        )
                    }
                    if (renamed) {
                        dispatchDialogAction(MainScreenDialogAction.CloseRenameDialog)
                    }
                }
            )
        }

        if (showExportDialog) {
            ExportConversationDialog(
                selectedFormat = exportFormat,
                onSelectFormat = {
                    dispatchDialogAction(MainScreenDialogAction.SelectExportFormat(it))
                },
                onDismiss = { dispatchDialogAction(MainScreenDialogAction.HideExportDialog) },
                onConfirm = {
                    chatVm.exportCurrentConversation(exportFormat)
                        .onSuccess { exportData ->
                            dispatchDialogAction(
                                MainScreenDialogAction.PreparePendingExport(exportData)
                            )
                            exportConversationLauncher.launch(exportData.fileName)
                        }
                        .onFailure { error ->
                            scope.launch {
                                snackbarHostState.showSnackbar(error.message ?: "导出失败")
                            }
                        }
                    dispatchDialogAction(MainScreenDialogAction.HideExportDialog)
                }
            )
        }

        if (visibleScreen !is Screen.Tools) {
            chatState.pendingApproval?.let { pendingApproval ->
            ApprovalDialog(
                approval = pendingApproval,
                onApprove = { chatVm.approvePendingTool() },
                onReject = { chatVm.rejectPendingTool() }
            )
            }
    }
}

private fun copyTextToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(null, text))
}

@Composable
private fun ChatPagePreviewPlaceholder(
    title: String,
    messageCount: Int
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        MurongGlassSurface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp)
        ) {
            Text(
                text = title.ifBlank { "新对话" },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = if (messageCount > 0) {
                    "保留聊天预览壳层，避免切页时提前直显完整对话。"
                } else {
                    "准备进入聊天页。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        repeat(2) {
            MurongGlassSurface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Text(
                    text = if (it == 0) "聊天预览占位" else "切页时不再挂载完整消息列表",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.weight(1f))
    }
}

// #region debug-point C:chat-debug-reporter
private const val ENABLE_REASONIX_BACK_DEBUG_REPORTS = true

private fun reportGitBackChatFlashMainDebug(
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

private data class PromptCacheMetrics(
    val hitRatePercent: Int
)

private fun buildPromptCacheMetrics(usage: UsageSummarySnapshot): PromptCacheMetrics? {
    val explicitTotal = usage.promptCacheHitTokens + usage.promptCacheMissTokens
    val promptTotal = usage.promptTokens
    val denominator = maxOf(explicitTotal, promptTotal)
    if (denominator <= 0) return null
    val hitRatePercent = ((usage.promptCacheHitTokens * 100.0) / denominator.toDouble()).toInt()
        .coerceIn(0, 100)
    return PromptCacheMetrics(hitRatePercent = hitRatePercent)
}

private fun formatCompactTokenCount(tokens: Int): String {
    return when {
        tokens >= 1_000_000 -> {
            val value = tokens / 1_000_000.0
            "${"%.1f".format(value).removeSuffix(".0")}M"
        }
        tokens >= 1_000 -> {
            val value = tokens / 1_000.0
            "${"%.1f".format(value).removeSuffix(".0")}k"
        }
        else -> tokens.toString()
    }
}

@Composable
private fun ChatUsageSummaryBadge(usageSummary: UsageSummarySnapshot) {
    val accent = rememberMurongAccentColor()
    val mutedTextColor = rememberMurongMutedTextColor()
    val metrics = remember(usageSummary) { buildPromptCacheMetrics(usageSummary) }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = accent.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.38f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Text(
                text = metrics?.let { "缓存 ${it.hitRatePercent}%" } ?: "缓存 --",
                color = accent,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "消耗 ${formatCompactTokenCount(usageSummary.totalTokens)} token",
                color = mutedTextColor,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun MainScreenPopupDialog(
    title: String,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    MurongDialog(onDismissRequest = onDismissRequest) {
        MurongPopupSurface(
            shape = RoundedCornerShape(24.dp),
            forceOpaque = true,
            modifier = modifier
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
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        content = actions
                    )
                }
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    content = content
                )
            }
        }
    }
}

@Composable
private fun MainScreenLargeDialog(
    title: String,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    MurongLargeDialogScaffold(
        onDismissRequest = onDismissRequest,
        modifier = modifier
    ) {
        MurongGlassSurface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            contentPadding = PaddingValues(16.dp)
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
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    content = actions
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content
        )
    }
}

@Composable
private fun RenameSessionDialog(
    value: String,
    onValueChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    MainScreenPopupDialog(
        title = "重命名会话",
        onDismissRequest = onDismiss,
        actions = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
            TextButton(
                onClick = onConfirm,
                enabled = value.trim().isNotBlank()
            ) {
                Text("保存")
            }
        }
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("会话标题") },
            singleLine = true
        )
    }
}

@Composable
private fun ExportConversationDialog(
    selectedFormat: ConversationExportFormat,
    onSelectFormat: (ConversationExportFormat) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    MainScreenPopupDialog(
        title = "导出会话",
        onDismissRequest = onDismiss,
        actions = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
            TextButton(onClick = onConfirm) {
                Text("保存")
            }
        }
    ) {
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
}

@Composable
private fun CreateTaskDialog(
    onDismiss: () -> Unit,
    projectPath: String,
    onProjectPathChange: (String) -> Unit,
    onPickFolder: () -> Unit,
    onCreateTask: (String) -> Unit
) {
    MainScreenPopupDialog(
        title = "新建任务",
        onDismissRequest = onDismiss,
        actions = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
            TextButton(
                onClick = { onCreateTask(projectPath.trim()) },
                enabled = projectPath.isNotBlank()
            ) {
                Text("创建")
            }
        }
    ) {
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
    val presentation = remember(approval.toolName, approval.summary, approval.detail, approval.rawArgs) {
        approval.toPendingApprovalPresentation()
    }
    MainScreenLargeDialog(
        title = "审批请求",
        subtitle = approval.toolName,
        onDismissRequest = {},
        actions = {
            TextButton(onClick = onReject) {
                Text(presentation.rejectLabel)
            }
            Button(onClick = onApprove) {
                Text(presentation.approveLabel)
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            RiskBadge(approval.riskLevel)
            Text(
                text = presentation.headline,
                style = MaterialTheme.typography.titleSmall
            )
            PendingApprovalSummaryCard(presentation = presentation)
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
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = presentation.rawArgsLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = approval.rawArgs,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
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
