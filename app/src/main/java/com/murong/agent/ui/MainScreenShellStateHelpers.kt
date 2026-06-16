package com.murong.agent.ui

import com.murong.agent.core.loop.ConversationExportData
import com.murong.agent.core.loop.ConversationExportFormat
import com.murong.agent.core.loop.UsageSummarySnapshot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.murong.agent.ui.project.ProjectEditorMenuAction
import com.murong.agent.ui.project.ProjectSecondaryHostBridgeState

internal data class MainScreenShellState(
    val shellOwnerTopLevelPage: Int,
    val chromeScreen: Screen,
    val isTopLevelNavigationSettled: Boolean,
    val hasTopLevelHistory: Boolean,
    val isSettingsSecondaryPage: Boolean,
    val isProjectSecondaryPage: Boolean,
    val hasSecondaryPage: Boolean,
    val canHandleSettingsBack: Boolean,
    val canHandleTopLevelBack: Boolean,
    val showBottomBar: Boolean,
    val isChatChromeVisible: Boolean,
    val topLevelPredictivePreviewTargetPage: Int?,
    val showTopLevelPredictivePreview: Boolean
)

internal enum class MainScreenTopBarLeadingAction {
    BRAND,
    SETTINGS_BACK,
    PROJECT_BACK,
    CHAT_DRAWER,
    TOP_LEVEL_BACK
}

internal sealed interface MainScreenHostAction {
    data object None : MainScreenHostAction
    data object CloseSettingsSecondary : MainScreenHostAction
    data object CloseProjectSecondary : MainScreenHostAction
    data object OpenChatDrawer : MainScreenHostAction
    data object CloseChatDrawer : MainScreenHostAction
    data object NavigateTopLevelBack : MainScreenHostAction
    data class NavigateToTopLevelPage(val page: Int) : MainScreenHostAction
}

internal data class MainScreenTopBarState(
    val title: String,
    val subtitle: String,
    val tag: String,
    val leadingAction: MainScreenTopBarLeadingAction,
    val leadingHostAction: MainScreenHostAction,
    val showProjectEditorMenu: Boolean,
    val showChatUsageSummary: Boolean,
    val showChatOverflowMenu: Boolean,
    val showTag: Boolean
)

internal data class MainScreenPageLayoutState(
    val bottomPadding: Dp
)

internal data class MainScreenTopLevelPredictivePreviewState(
    val isVisible: Boolean,
    val currentPage: Int,
    val targetPage: Int?,
    val directionMultiplier: Float
)

internal enum class MainScreenSecondaryHostKind {
    NONE,
    SETTINGS,
    PROJECT
}

internal enum class MainScreenDrawerCommand {
    NONE,
    OPEN,
    CLOSE
}

internal enum class MainScreenProjectSecondaryCommand {
    NONE,
    CLOSE
}

internal data class MainScreenChatDrawerState(
    val mountDrawerContent: Boolean,
    val gesturesEnabled: Boolean
)

internal data class MainScreenSecondaryHostState(
    val activeKind: MainScreenSecondaryHostKind,
    val isSettingsSecondaryPage: Boolean,
    val isProjectSecondaryPage: Boolean,
    val hasSecondaryPage: Boolean,
    val canHandleHostBackGesture: Boolean,
    val hostBackProgress: Float,
    val hostBackAction: MainScreenHostAction,
    val shouldResetProjectSecondaryBridge: Boolean
)

internal data class MainScreenTopLevelNavigationState(
    val selectedPage: Int = 0,
    val lastSettledPage: Int = 0,
    val navigationTargetPage: Int? = null,
    val history: List<Int> = emptyList(),
    val consumingBackNavigation: Boolean = false,
    val drawerCommand: MainScreenDrawerCommand = MainScreenDrawerCommand.NONE,
    val backProgress: Float = 0f
)

internal data class MainScreenOverlayState(
    val shouldCloseChatDrawer: Boolean,
    val shouldDismissChatMenu: Boolean,
    val shouldDismissProjectEditorMenu: Boolean,
    val canHandleTopLevelBack: Boolean
)

internal data class MainScreenOverlayVisibilityState(
    val showChatMenu: Boolean = false,
    val showProjectEditorMenu: Boolean = false
)

internal data class MainScreenDialogState(
    val showTaskDialog: Boolean = false,
    val taskProjectPath: String = "",
    val taskDialogAppliesToCurrentSession: Boolean = false,
    val renameSessionTargetId: String? = null,
    val renameSessionDraft: String = "",
    val showExportDialog: Boolean = false,
    val exportFormat: ConversationExportFormat = ConversationExportFormat.MARKDOWN,
    val pendingExportData: ConversationExportData? = null
)

internal data class MainScreenSettingsState(
    val subpage: SettingsSubpage = SettingsSubpage.Main,
    val backProgress: Float = 0f
)

internal data class MainScreenProjectSecondaryHostState(
    val command: MainScreenProjectSecondaryCommand = MainScreenProjectSecondaryCommand.NONE,
    val bridgeState: ProjectSecondaryHostBridgeState = ProjectSecondaryHostBridgeState(),
    val editorMenuAction: ProjectEditorMenuAction? = null,
    val editorMenuActionSignal: Int = 0
)

internal data class MainScreenSecondaryHostRuntimeState(
    val bridgeState: ProjectSecondaryHostBridgeState,
    val isSettingsSecondaryPage: Boolean,
    val isProjectSecondaryPage: Boolean,
    val hasSecondaryPage: Boolean,
    val canHandleHostBackGesture: Boolean,
    val hostBackProgress: Float,
    val hostBackAction: MainScreenHostAction,
    val shouldResetProjectSecondaryCarrier: Boolean
)

internal enum class MainScreenOverlayVisibilityAction {
    SHOW_CHAT_MENU,
    HIDE_CHAT_MENU,
    SHOW_PROJECT_EDITOR_MENU,
    HIDE_PROJECT_EDITOR_MENU,
    DISMISS_MENUS
}

internal sealed interface MainScreenDialogAction {
    data class ShowTaskDialog(
        val projectPath: String,
        val applyToCurrentSession: Boolean = false
    ) : MainScreenDialogAction
    data object HideTaskDialog : MainScreenDialogAction
    data class UpdateTaskProjectPath(val projectPath: String) : MainScreenDialogAction
    data class OpenRenameDialog(val sessionId: String, val title: String) : MainScreenDialogAction
    data class UpdateRenameDraft(val draft: String) : MainScreenDialogAction
    data object CloseRenameDialog : MainScreenDialogAction
    data class ShowExportDialog(
        val format: ConversationExportFormat = ConversationExportFormat.MARKDOWN
    ) : MainScreenDialogAction
    data object HideExportDialog : MainScreenDialogAction
    data class SelectExportFormat(val format: ConversationExportFormat) : MainScreenDialogAction
    data class PreparePendingExport(val exportData: ConversationExportData) : MainScreenDialogAction
    data object ClearPendingExport : MainScreenDialogAction
}

internal sealed interface MainScreenChatAction {
    data class StartNewSession(
        val closeDrawer: Boolean = false,
        val dismissMenu: Boolean = false
    ) : MainScreenChatAction
    data class LoadSession(
        val sessionId: String,
        val closeDrawer: Boolean = true,
        val dismissMenu: Boolean = false
    ) : MainScreenChatAction
    data class DeleteSession(
        val sessionId: String,
        val closeDrawer: Boolean = false,
        val dismissMenu: Boolean = false
    ) : MainScreenChatAction
    data class OpenTaskDialog(
        val projectPath: String,
        val applyToCurrentSession: Boolean = false,
        val dismissMenu: Boolean = false
    ) : MainScreenChatAction
    data class OpenRenameDialog(
        val sessionId: String,
        val title: String,
        val dismissMenu: Boolean = false
    ) : MainScreenChatAction
    data class OpenExportDialog(
        val format: ConversationExportFormat = ConversationExportFormat.MARKDOWN,
        val dismissMenu: Boolean = false
    ) : MainScreenChatAction
    data class ImportConversationSucceeded(val importedMessageCount: Int) : MainScreenChatAction
}

internal sealed interface MainScreenTopLevelNavigationAction {
    data class NavigateToPage(val page: Int) : MainScreenTopLevelNavigationAction
    data object NavigateBack : MainScreenTopLevelNavigationAction
    data class SyncSettledPage(val page: Int) : MainScreenTopLevelNavigationAction
    data class RequestDrawerCommand(
        val command: MainScreenDrawerCommand
    ) : MainScreenTopLevelNavigationAction
    data object ConsumeDrawerCommand : MainScreenTopLevelNavigationAction
    data class UpdateBackProgress(val progress: Float) : MainScreenTopLevelNavigationAction
}

internal sealed interface MainScreenSettingsAction {
    data object OpenThemePage : MainScreenSettingsAction
    data object OpenAboutPage : MainScreenSettingsAction
    data object CloseSecondaryPage : MainScreenSettingsAction
    data class UpdateBackProgress(val progress: Float) : MainScreenSettingsAction
}

internal sealed interface MainScreenProjectSecondaryHostAction {
    data object RequestCloseSecondary : MainScreenProjectSecondaryHostAction
    data object ConsumeCommand : MainScreenProjectSecondaryHostAction
    data class UpdateBridgeState(
        val bridgeState: ProjectSecondaryHostBridgeState
    ) : MainScreenProjectSecondaryHostAction
    data class DispatchEditorMenuAction(
        val action: ProjectEditorMenuAction
    ) : MainScreenProjectSecondaryHostAction
    data object Reset : MainScreenProjectSecondaryHostAction
}

internal fun resolveMainScreenShellState(
    shellScreens: List<Screen>,
    selectedTopLevelPage: Int,
    visibleTopLevelPage: Int,
    topLevelNavigationTargetPage: Int?,
    pagerIsScrollInProgress: Boolean,
    pagerCurrentPage: Int,
    pagerSettledPage: Int,
    settingsSubpage: SettingsSubpage,
    projectSecondaryChromeState: ProjectSecondaryChromeState,
    chatPageIndex: Int,
    topLevelHistoryLastPage: Int?,
    topLevelBackProgress: Float
): MainScreenShellState {
    val hasTopLevelHistory = topLevelHistoryLastPage != null
    val isTopLevelNavigationSettled =
        topLevelNavigationTargetPage == null &&
            !pagerIsScrollInProgress &&
            pagerCurrentPage == selectedTopLevelPage &&
            pagerSettledPage == selectedTopLevelPage
    val shellOwnerTopLevelPage = if (isTopLevelNavigationSettled) {
        selectedTopLevelPage
    } else {
        visibleTopLevelPage
    }
    val chromeScreen = shellScreens[selectedTopLevelPage]
    val isSettingsSecondaryPage =
        chromeScreen is Screen.Settings && settingsSubpage != SettingsSubpage.Main
    val isProjectSecondaryPage =
        chromeScreen is Screen.Projects && projectSecondaryChromeState.active
    val hasSecondaryPage = isSettingsSecondaryPage || isProjectSecondaryPage
    val canHandleSettingsBack = isSettingsSecondaryPage
    val canHandleTopLevelBack =
        settingsSubpage == SettingsSubpage.Main &&
            hasTopLevelHistory &&
            !hasSecondaryPage &&
            isTopLevelNavigationSettled
    val showBottomBar = !hasSecondaryPage
    val isChatChromeVisible =
        settingsSubpage == SettingsSubpage.Main &&
            !hasSecondaryPage &&
            chromeScreen is Screen.Chat &&
            shellOwnerTopLevelPage == chatPageIndex &&
            isTopLevelNavigationSettled
    val showTopLevelPredictivePreview =
        settingsSubpage == SettingsSubpage.Main &&
            !hasSecondaryPage &&
            hasTopLevelHistory &&
            topLevelBackProgress > 0.001f
    return MainScreenShellState(
        shellOwnerTopLevelPage = shellOwnerTopLevelPage,
        chromeScreen = chromeScreen,
        isTopLevelNavigationSettled = isTopLevelNavigationSettled,
        hasTopLevelHistory = hasTopLevelHistory,
        isSettingsSecondaryPage = isSettingsSecondaryPage,
        isProjectSecondaryPage = isProjectSecondaryPage,
        hasSecondaryPage = hasSecondaryPage,
        canHandleSettingsBack = canHandleSettingsBack,
        canHandleTopLevelBack = canHandleTopLevelBack,
        showBottomBar = showBottomBar,
        isChatChromeVisible = isChatChromeVisible,
        topLevelPredictivePreviewTargetPage = topLevelHistoryLastPage,
        showTopLevelPredictivePreview = showTopLevelPredictivePreview
    )
}

internal fun ownsMainScreenShellState(
    page: Int,
    previewMode: Boolean,
    shellState: MainScreenShellState
): Boolean {
    return !previewMode && page == shellState.shellOwnerTopLevelPage
}

internal fun resolveMainScreenTopBarState(
    settingsSubpage: SettingsSubpage,
    chromeScreen: Screen,
    chatSessionTitle: String,
    usageSummary: UsageSummarySnapshot,
    projectSecondaryChromeState: ProjectSecondaryChromeState,
    isProjectSecondaryPage: Boolean,
    isChatChromeVisible: Boolean,
    hasTopLevelHistory: Boolean,
    isTopLevelNavigationSettled: Boolean
): MainScreenTopBarState {
    val title = when (settingsSubpage) {
        SettingsSubpage.Theme -> "主题界面"
        SettingsSubpage.About -> "关于"
        SettingsSubpage.Main -> when (chromeScreen) {
            is Screen.Chat -> chatSessionTitle.ifBlank { "新对话" }
            is Screen.Projects -> projectSecondaryChromeState.title.ifBlank { Screen.Projects.title }
            is Screen.Tools -> Screen.Tools.title
            is Screen.Settings -> Screen.Settings.title
        }
    }
    val subtitle = when (settingsSubpage) {
        SettingsSubpage.Theme -> "风格、模式与强调色"
        SettingsSubpage.About -> "应用信息与产品方向"
        SettingsSubpage.Main -> when (chromeScreen) {
            is Screen.Chat -> ""
            is Screen.Projects -> if (isProjectSecondaryPage) {
                projectSecondaryChromeState.subtitle
            } else {
                "项目浏览、文件编辑与 Git 工作流"
            }
            is Screen.Tools -> "工具状态、审批与执行记录"
            is Screen.Settings -> "账号、模型与全局偏好"
        }
    }
    val tag = when (settingsSubpage) {
        SettingsSubpage.Theme -> "外观"
        SettingsSubpage.About -> "信息"
        SettingsSubpage.Main -> when (chromeScreen) {
            is Screen.Chat -> "对话"
            is Screen.Projects -> "项目"
            is Screen.Tools -> "工具"
            is Screen.Settings -> "设置"
        }
    }
    val leadingAction = when {
        settingsSubpage != SettingsSubpage.Main -> MainScreenTopBarLeadingAction.SETTINGS_BACK
        isProjectSecondaryPage -> MainScreenTopBarLeadingAction.PROJECT_BACK
        chromeScreen is Screen.Chat -> MainScreenTopBarLeadingAction.CHAT_DRAWER
        hasTopLevelHistory -> MainScreenTopBarLeadingAction.TOP_LEVEL_BACK
        else -> MainScreenTopBarLeadingAction.BRAND
    }
    val leadingHostAction = when (leadingAction) {
        MainScreenTopBarLeadingAction.SETTINGS_BACK -> MainScreenHostAction.CloseSettingsSecondary
        MainScreenTopBarLeadingAction.PROJECT_BACK -> MainScreenHostAction.CloseProjectSecondary
        MainScreenTopBarLeadingAction.CHAT_DRAWER -> MainScreenHostAction.OpenChatDrawer
        MainScreenTopBarLeadingAction.TOP_LEVEL_BACK -> MainScreenHostAction.NavigateTopLevelBack
        MainScreenTopBarLeadingAction.BRAND -> MainScreenHostAction.None
    }
    val showProjectEditorMenu = isProjectSecondaryPage && projectSecondaryChromeState.supportsEditorMenu
    val showChatUsageSummary =
        chromeScreen is Screen.Chat &&
            (usageSummary.totalTokens > 0 || usageSummary.promptTokens > 0)
    val showChatOverflowMenu = chromeScreen is Screen.Chat
    return MainScreenTopBarState(
        title = title,
        subtitle = subtitle,
        tag = tag,
        leadingAction = leadingAction,
        leadingHostAction = leadingHostAction,
        showProjectEditorMenu = showProjectEditorMenu,
        showChatUsageSummary = showChatUsageSummary,
        showChatOverflowMenu = showChatOverflowMenu,
        showTag =
            !showProjectEditorMenu &&
                !showChatOverflowMenu
    )
}

internal fun resolveMainScreenPageLayoutState(
    pageScreen: Screen,
    shellState: MainScreenShellState
): MainScreenPageLayoutState {
    val bottomPadding = when {
        !shellState.showBottomBar -> 12.dp
        pageScreen is Screen.Chat -> 82.dp
        else -> 24.dp
    }
    return MainScreenPageLayoutState(bottomPadding = bottomPadding)
}

internal fun resolveMainScreenTopLevelPredictivePreviewState(
    selectedTopLevelPage: Int,
    shellState: MainScreenShellState
): MainScreenTopLevelPredictivePreviewState {
    val targetPage = shellState.topLevelPredictivePreviewTargetPage
    val directionMultiplier = when {
        targetPage == null -> 1f
        selectedTopLevelPage > targetPage -> 1f
        selectedTopLevelPage < targetPage -> -1f
        else -> 1f
    }
    return MainScreenTopLevelPredictivePreviewState(
        isVisible = shellState.showTopLevelPredictivePreview,
        currentPage = selectedTopLevelPage,
        targetPage = targetPage,
        directionMultiplier = directionMultiplier
    )
}

internal fun resolveMainScreenChatDrawerState(
    shellState: MainScreenShellState
): MainScreenChatDrawerState {
    return MainScreenChatDrawerState(
        mountDrawerContent = shellState.isChatChromeVisible,
        gesturesEnabled = shellState.isChatChromeVisible && shellState.showBottomBar
    )
}

internal fun resolveMainScreenSecondaryHostState(
    shellState: MainScreenShellState,
    settingsBackProgress: Float,
    projectSecondaryBackProgress: Float
): MainScreenSecondaryHostState {
    val activeKind = when {
        shellState.isSettingsSecondaryPage -> MainScreenSecondaryHostKind.SETTINGS
        shellState.isProjectSecondaryPage -> MainScreenSecondaryHostKind.PROJECT
        else -> MainScreenSecondaryHostKind.NONE
    }
    return MainScreenSecondaryHostState(
        activeKind = activeKind,
        isSettingsSecondaryPage = activeKind == MainScreenSecondaryHostKind.SETTINGS,
        isProjectSecondaryPage = activeKind == MainScreenSecondaryHostKind.PROJECT,
        hasSecondaryPage = activeKind != MainScreenSecondaryHostKind.NONE,
        canHandleHostBackGesture = activeKind == MainScreenSecondaryHostKind.SETTINGS,
        hostBackProgress = when (activeKind) {
            MainScreenSecondaryHostKind.SETTINGS -> settingsBackProgress
            MainScreenSecondaryHostKind.PROJECT -> projectSecondaryBackProgress
            MainScreenSecondaryHostKind.NONE -> 0f
        },
        hostBackAction = when (activeKind) {
            MainScreenSecondaryHostKind.SETTINGS -> MainScreenHostAction.CloseSettingsSecondary
            MainScreenSecondaryHostKind.PROJECT -> MainScreenHostAction.CloseProjectSecondary
            MainScreenSecondaryHostKind.NONE -> MainScreenHostAction.None
        },
        shouldResetProjectSecondaryBridge = shellState.chromeScreen !is Screen.Projects
    )
}

internal fun resolveMainScreenSecondaryHostRuntimeState(
    secondaryHostState: MainScreenSecondaryHostState,
    projectSecondaryHostState: MainScreenProjectSecondaryHostState
): MainScreenSecondaryHostRuntimeState {
    return MainScreenSecondaryHostRuntimeState(
        bridgeState = projectSecondaryHostState.bridgeState,
        isSettingsSecondaryPage = secondaryHostState.isSettingsSecondaryPage,
        isProjectSecondaryPage = secondaryHostState.isProjectSecondaryPage,
        hasSecondaryPage = secondaryHostState.hasSecondaryPage,
        canHandleHostBackGesture = secondaryHostState.canHandleHostBackGesture,
        hostBackProgress = secondaryHostState.hostBackProgress,
        hostBackAction = secondaryHostState.hostBackAction,
        shouldResetProjectSecondaryCarrier = secondaryHostState.shouldResetProjectSecondaryBridge
    )
}

internal fun resolveMainScreenOverlayState(
    shellState: MainScreenShellState,
    topBarState: MainScreenTopBarState,
    isChatDrawerOpen: Boolean
): MainScreenOverlayState {
    return MainScreenOverlayState(
        shouldCloseChatDrawer = !shellState.isChatChromeVisible,
        shouldDismissChatMenu = !topBarState.showChatOverflowMenu,
        shouldDismissProjectEditorMenu = !topBarState.showProjectEditorMenu,
        canHandleTopLevelBack = shellState.canHandleTopLevelBack && !isChatDrawerOpen
    )
}

internal fun reduceMainScreenOverlayVisibilityState(
    state: MainScreenOverlayVisibilityState,
    action: MainScreenOverlayVisibilityAction
): MainScreenOverlayVisibilityState {
    return when (action) {
        MainScreenOverlayVisibilityAction.SHOW_CHAT_MENU -> {
            state.copy(showChatMenu = true, showProjectEditorMenu = false)
        }
        MainScreenOverlayVisibilityAction.HIDE_CHAT_MENU -> {
            state.copy(showChatMenu = false)
        }
        MainScreenOverlayVisibilityAction.SHOW_PROJECT_EDITOR_MENU -> {
            state.copy(showChatMenu = false, showProjectEditorMenu = true)
        }
        MainScreenOverlayVisibilityAction.HIDE_PROJECT_EDITOR_MENU -> {
            state.copy(showProjectEditorMenu = false)
        }
        MainScreenOverlayVisibilityAction.DISMISS_MENUS -> {
            MainScreenOverlayVisibilityState()
        }
    }
}

internal fun reduceMainScreenDialogState(
    state: MainScreenDialogState,
    action: MainScreenDialogAction
): MainScreenDialogState {
    return when (action) {
        is MainScreenDialogAction.ShowTaskDialog -> {
            state.copy(
                showTaskDialog = true,
                taskProjectPath = action.projectPath,
                taskDialogAppliesToCurrentSession = action.applyToCurrentSession
            )
        }
        MainScreenDialogAction.HideTaskDialog -> {
            state.copy(
                showTaskDialog = false,
                taskProjectPath = "",
                taskDialogAppliesToCurrentSession = false
            )
        }
        is MainScreenDialogAction.UpdateTaskProjectPath -> {
            state.copy(taskProjectPath = action.projectPath)
        }
        is MainScreenDialogAction.OpenRenameDialog -> {
            state.copy(
                renameSessionTargetId = action.sessionId,
                renameSessionDraft = action.title.ifBlank { "新对话" }
            )
        }
        is MainScreenDialogAction.UpdateRenameDraft -> {
            state.copy(renameSessionDraft = action.draft)
        }
        MainScreenDialogAction.CloseRenameDialog -> {
            state.copy(renameSessionTargetId = null)
        }
        is MainScreenDialogAction.ShowExportDialog -> {
            state.copy(showExportDialog = true, exportFormat = action.format)
        }
        MainScreenDialogAction.HideExportDialog -> {
            state.copy(showExportDialog = false)
        }
        is MainScreenDialogAction.SelectExportFormat -> {
            state.copy(exportFormat = action.format)
        }
        is MainScreenDialogAction.PreparePendingExport -> {
            state.copy(pendingExportData = action.exportData)
        }
        MainScreenDialogAction.ClearPendingExport -> {
            state.copy(pendingExportData = null)
        }
    }
}

internal fun reduceMainScreenSettingsState(
    state: MainScreenSettingsState,
    action: MainScreenSettingsAction
): MainScreenSettingsState {
    return when (action) {
        MainScreenSettingsAction.OpenThemePage -> {
            state.copy(subpage = SettingsSubpage.Theme, backProgress = 0f)
        }
        MainScreenSettingsAction.OpenAboutPage -> {
            state.copy(subpage = SettingsSubpage.About, backProgress = 0f)
        }
        MainScreenSettingsAction.CloseSecondaryPage -> {
            state.copy(subpage = SettingsSubpage.Main, backProgress = 0f)
        }
        is MainScreenSettingsAction.UpdateBackProgress -> {
            state.copy(backProgress = action.progress)
        }
    }
}

internal fun reduceMainScreenProjectSecondaryHostState(
    state: MainScreenProjectSecondaryHostState,
    action: MainScreenProjectSecondaryHostAction
): MainScreenProjectSecondaryHostState {
    return when (action) {
        MainScreenProjectSecondaryHostAction.RequestCloseSecondary -> {
            state.copy(command = MainScreenProjectSecondaryCommand.CLOSE)
        }
        MainScreenProjectSecondaryHostAction.ConsumeCommand -> {
            state.copy(command = MainScreenProjectSecondaryCommand.NONE)
        }
        is MainScreenProjectSecondaryHostAction.UpdateBridgeState -> {
            state.copy(bridgeState = action.bridgeState)
        }
        is MainScreenProjectSecondaryHostAction.DispatchEditorMenuAction -> {
            state.copy(
                editorMenuAction = action.action,
                editorMenuActionSignal = state.editorMenuActionSignal + 1
            )
        }
        MainScreenProjectSecondaryHostAction.Reset -> {
            MainScreenProjectSecondaryHostState()
        }
    }
}

internal fun reduceMainScreenTopLevelNavigationState(
    state: MainScreenTopLevelNavigationState,
    action: MainScreenTopLevelNavigationAction
): MainScreenTopLevelNavigationState {
    return when (action) {
        is MainScreenTopLevelNavigationAction.NavigateToPage -> {
            state.copy(
                selectedPage = action.page,
                navigationTargetPage = if (action.page == state.lastSettledPage) {
                    null
                } else {
                    action.page
                },
                consumingBackNavigation = false,
                drawerCommand = MainScreenDrawerCommand.CLOSE
            )
        }
        MainScreenTopLevelNavigationAction.NavigateBack -> {
            val targetPage = state.history.lastOrNull() ?: return state
            state.copy(
                selectedPage = targetPage,
                navigationTargetPage = if (targetPage == state.lastSettledPage) {
                    null
                } else {
                    targetPage
                },
                history = state.history.dropLast(1),
                consumingBackNavigation = true,
                drawerCommand = MainScreenDrawerCommand.CLOSE
            )
        }
        is MainScreenTopLevelNavigationAction.SyncSettledPage -> {
            var history = state.history
            var consumingBackNavigation = state.consumingBackNavigation
            var lastSettledPage = state.lastSettledPage
            if (action.page != state.lastSettledPage) {
                if (state.consumingBackNavigation) {
                    consumingBackNavigation = false
                } else if (state.history.lastOrNull() != state.lastSettledPage) {
                    history = state.history + state.lastSettledPage
                }
                lastSettledPage = action.page
            }
            state.copy(
                selectedPage = action.page,
                lastSettledPage = lastSettledPage,
                navigationTargetPage = if (state.navigationTargetPage == action.page) {
                    null
                } else {
                    state.navigationTargetPage
                },
                history = history,
                consumingBackNavigation = consumingBackNavigation,
                drawerCommand = MainScreenDrawerCommand.CLOSE
            )
        }
        is MainScreenTopLevelNavigationAction.RequestDrawerCommand -> {
            state.copy(drawerCommand = action.command)
        }
        MainScreenTopLevelNavigationAction.ConsumeDrawerCommand -> {
            state.copy(drawerCommand = MainScreenDrawerCommand.NONE)
        }
        is MainScreenTopLevelNavigationAction.UpdateBackProgress -> {
            state.copy(backProgress = action.progress)
        }
    }
}
