package dev.reasonix.mobile.ui.project

import dev.reasonix.mobile.ui.ProjectSecondaryChromeState

internal enum class ProjectSecondaryOwner {
    NONE,
    EDITOR,
    GIT
}

internal enum class ProjectSecondaryBackProgressMode {
    NONE,
    HOST_GESTURE,
    DELEGATED
}

internal data class ProjectSecondaryHostState(
    val owner: ProjectSecondaryOwner,
    val isActive: Boolean,
    val activeChromeState: ProjectSecondaryChromeState,
    val activeCloseRequest: (() -> Unit)?,
    val backProgressMode: ProjectSecondaryBackProgressMode
)

internal data class ProjectSecondaryHostBridgeState(
    val chromeState: ProjectSecondaryChromeState = ProjectSecondaryChromeState(),
    val backRequest: (() -> Unit)? = null,
    val backProgress: Float = 0f
)

internal data class ProjectSecondaryRegistryState(
    val editorChromeState: ProjectSecondaryChromeState = ProjectSecondaryChromeState(),
    val gitChromeState: ProjectSecondaryChromeState = ProjectSecondaryChromeState(),
    val editorCloseRequest: (() -> Unit)? = null,
    val gitCloseRequest: (() -> Unit)? = null,
    val backProgress: Float = 0f,
    val command: ProjectSecondaryHostCommand = ProjectSecondaryHostCommand.NONE
)

internal data class ProjectPrimaryNavigationState(
    val selectedTab: ProjectPrimaryTab = ProjectPrimaryTab.EDITOR,
    val navigationTargetTab: ProjectPrimaryTab? = null
)

internal data class ProjectPrimaryNavigationHostState(
    val showPrimaryChrome: Boolean,
    val userScrollEnabled: Boolean,
    val scrollTargetTab: ProjectPrimaryTab?,
    val shouldConsumeNavigationTarget: Boolean,
    val settledTabToSync: ProjectPrimaryTab?
)

internal data class ProjectSecondaryHostRuntimeState(
    val bridgeState: ProjectSecondaryHostBridgeState,
    val shouldResetBackProgress: Boolean,
    val canHandleHostBackGesture: Boolean
)

internal enum class ProjectSecondaryHostCommand {
    NONE,
    CLOSE_ACTIVE_SECONDARY
}

internal sealed interface ProjectSecondaryRegistryAction {
    data class UpdateEditorChromeState(
        val chromeState: ProjectSecondaryChromeState
    ) : ProjectSecondaryRegistryAction
    data class UpdateGitChromeState(
        val chromeState: ProjectSecondaryChromeState
    ) : ProjectSecondaryRegistryAction
    data class RegisterEditorCloseRequest(
        val closeRequest: (() -> Unit)?
    ) : ProjectSecondaryRegistryAction
    data class RegisterGitCloseRequest(
        val closeRequest: (() -> Unit)?
    ) : ProjectSecondaryRegistryAction
    data class UpdateBackProgress(val progress: Float) : ProjectSecondaryRegistryAction
    data object RequestCloseActiveSecondary : ProjectSecondaryRegistryAction
    data object ConsumeCommand : ProjectSecondaryRegistryAction
}

internal sealed interface ProjectPrimaryNavigationAction {
    data class SelectTab(val tab: ProjectPrimaryTab) : ProjectPrimaryNavigationAction
    data class SyncSettledTab(val tab: ProjectPrimaryTab) : ProjectPrimaryNavigationAction
    data object ConsumeNavigationTarget : ProjectPrimaryNavigationAction
}

private fun projectTabOwnsSecondary(
    candidateTab: ProjectPrimaryTab,
    selectedTab: ProjectPrimaryTab,
    visibleTab: ProjectPrimaryTab,
    settledTab: ProjectPrimaryTab
): Boolean {
    return selectedTab == candidateTab ||
        visibleTab == candidateTab ||
        settledTab == candidateTab
}

internal fun resolveProjectSecondaryHostState(
    selectedTab: ProjectPrimaryTab,
    visibleTab: ProjectPrimaryTab,
    settledTab: ProjectPrimaryTab,
    editorChromeState: ProjectSecondaryChromeState,
    gitChromeState: ProjectSecondaryChromeState,
    editorCloseRequest: (() -> Unit)?,
    gitCloseRequest: (() -> Unit)?
): ProjectSecondaryHostState {
    val gitOwnsSecondary =
        gitChromeState.active &&
            projectTabOwnsSecondary(
                candidateTab = ProjectPrimaryTab.GIT,
                selectedTab = selectedTab,
                visibleTab = visibleTab,
                settledTab = settledTab
            )
    val editorOwnsSecondary =
        editorChromeState.active &&
            projectTabOwnsSecondary(
                candidateTab = ProjectPrimaryTab.EDITOR,
                selectedTab = selectedTab,
                visibleTab = visibleTab,
                settledTab = settledTab
            )
    return when {
        gitOwnsSecondary -> ProjectSecondaryHostState(
            owner = ProjectSecondaryOwner.GIT,
            isActive = true,
            activeChromeState = gitChromeState,
            activeCloseRequest = gitCloseRequest,
            backProgressMode = ProjectSecondaryBackProgressMode.DELEGATED
        )
        editorOwnsSecondary -> ProjectSecondaryHostState(
            owner = ProjectSecondaryOwner.EDITOR,
            isActive = true,
            activeChromeState = editorChromeState,
            activeCloseRequest = editorCloseRequest,
            backProgressMode = ProjectSecondaryBackProgressMode.HOST_GESTURE
        )
        else -> ProjectSecondaryHostState(
            owner = ProjectSecondaryOwner.NONE,
            isActive = false,
            activeChromeState = ProjectSecondaryChromeState(),
            activeCloseRequest = null,
            backProgressMode = ProjectSecondaryBackProgressMode.NONE
        )
    }
}

internal fun resolveProjectSecondaryHostBridgeState(
    hostState: ProjectSecondaryHostState,
    backProgress: Float
): ProjectSecondaryHostBridgeState {
    return ProjectSecondaryHostBridgeState(
        chromeState = hostState.activeChromeState,
        backRequest = hostState.activeCloseRequest,
        backProgress = backProgress
    )
}

internal fun resolveProjectSecondaryHostRuntimeState(
    hostState: ProjectSecondaryHostState,
    backProgress: Float
): ProjectSecondaryHostRuntimeState {
    val bridgeState = resolveProjectSecondaryHostBridgeState(
        hostState = hostState,
        backProgress = backProgress
    )
    return ProjectSecondaryHostRuntimeState(
        bridgeState = bridgeState,
        shouldResetBackProgress =
            backProgress != 0f &&
                (
                    hostState.activeCloseRequest == null ||
                        hostState.backProgressMode == ProjectSecondaryBackProgressMode.NONE
                    ),
        canHandleHostBackGesture =
            hostState.backProgressMode == ProjectSecondaryBackProgressMode.HOST_GESTURE &&
                hostState.activeCloseRequest != null
    )
}

internal fun reduceProjectSecondaryRegistryState(
    state: ProjectSecondaryRegistryState,
    action: ProjectSecondaryRegistryAction
): ProjectSecondaryRegistryState {
    return when (action) {
        is ProjectSecondaryRegistryAction.UpdateEditorChromeState -> {
            state.copy(editorChromeState = action.chromeState)
        }
        is ProjectSecondaryRegistryAction.UpdateGitChromeState -> {
            state.copy(gitChromeState = action.chromeState)
        }
        is ProjectSecondaryRegistryAction.RegisterEditorCloseRequest -> {
            state.copy(editorCloseRequest = action.closeRequest)
        }
        is ProjectSecondaryRegistryAction.RegisterGitCloseRequest -> {
            state.copy(gitCloseRequest = action.closeRequest)
        }
        is ProjectSecondaryRegistryAction.UpdateBackProgress -> {
            state.copy(backProgress = action.progress)
        }
        ProjectSecondaryRegistryAction.RequestCloseActiveSecondary -> {
            state.copy(command = ProjectSecondaryHostCommand.CLOSE_ACTIVE_SECONDARY)
        }
        ProjectSecondaryRegistryAction.ConsumeCommand -> {
            state.copy(command = ProjectSecondaryHostCommand.NONE)
        }
    }
}

internal fun resolveProjectPrimaryNavigationHostState(
    navigationState: ProjectPrimaryNavigationState,
    pagerCurrentTab: ProjectPrimaryTab,
    pagerSettledTab: ProjectPrimaryTab,
    secondaryHostState: ProjectSecondaryHostState
): ProjectPrimaryNavigationHostState {
    val showPrimaryChrome = !secondaryHostState.isActive
    val scrollTargetTab = if (showPrimaryChrome) {
        navigationState.navigationTargetTab?.takeIf { it != pagerCurrentTab }
    } else {
        null
    }
    val shouldConsumeNavigationTarget =
        showPrimaryChrome &&
            navigationState.navigationTargetTab != null &&
            navigationState.navigationTargetTab == pagerCurrentTab
    val settledTabToSync = if (showPrimaryChrome) {
        pagerSettledTab.takeIf { it != navigationState.selectedTab }
    } else {
        null
    }
    return ProjectPrimaryNavigationHostState(
        showPrimaryChrome = showPrimaryChrome,
        userScrollEnabled = showPrimaryChrome,
        scrollTargetTab = scrollTargetTab,
        shouldConsumeNavigationTarget = shouldConsumeNavigationTarget,
        settledTabToSync = settledTabToSync
    )
}

internal fun reduceProjectPrimaryNavigationState(
    state: ProjectPrimaryNavigationState,
    action: ProjectPrimaryNavigationAction
): ProjectPrimaryNavigationState {
    return when (action) {
        is ProjectPrimaryNavigationAction.SelectTab -> {
            state.copy(
                selectedTab = action.tab,
                navigationTargetTab = if (state.selectedTab == action.tab) {
                    state.navigationTargetTab
                } else {
                    action.tab
                }
            )
        }
        is ProjectPrimaryNavigationAction.SyncSettledTab -> {
            state.copy(
                selectedTab = action.tab,
                navigationTargetTab = if (state.navigationTargetTab == action.tab) {
                    null
                } else {
                    state.navigationTargetTab
                }
            )
        }
        ProjectPrimaryNavigationAction.ConsumeNavigationTarget -> {
            state.copy(navigationTargetTab = null)
        }
    }
}
