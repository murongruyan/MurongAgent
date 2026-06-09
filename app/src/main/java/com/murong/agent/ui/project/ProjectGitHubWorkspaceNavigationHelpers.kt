package com.murong.agent.ui.project

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal data class ProjectGitHubWorkspaceNavigationState(
    val isVisible: Boolean = false,
    val isDownloadCenterVisible: Boolean = false,
    val workbenchRepoRoot: String? = null,
    val workbenchSelectedTab: ProjectGitHubWorkspaceRepoWorkbenchTab =
        ProjectGitHubWorkspaceRepoWorkbenchTab.OVERVIEW,
    val pendingDetailTarget: ProjectGitHubWorkspaceDetailTarget? = null
)

internal data class ProjectGitHubWorkspaceDetailTarget(
    val rootPath: String,
    val selectedTab: ProjectGitHubWorkspaceRepoWorkbenchTab,
    val workflowRun: ProjectGitHubWorkflowRunUi? = null,
    val issue: ProjectGitHubIssueUi? = null,
    val pullRequest: ProjectGitHubPullRequestUi? = null
)

internal sealed interface ProjectGitHubWorkspaceNavigationAction {
    data class OpenRepoWorkbench(
        val rootPath: String,
        val selectedTab: ProjectGitHubWorkspaceRepoWorkbenchTab =
            ProjectGitHubWorkspaceRepoWorkbenchTab.OVERVIEW
    ) : ProjectGitHubWorkspaceNavigationAction

    data class OpenDetailTarget(
        val target: ProjectGitHubWorkspaceDetailTarget
    ) : ProjectGitHubWorkspaceNavigationAction

    data object OpenDownloadCenter : ProjectGitHubWorkspaceNavigationAction

    data object OpenOverview : ProjectGitHubWorkspaceNavigationAction

    data class SelectWorkbenchTab(
        val selectedTab: ProjectGitHubWorkspaceRepoWorkbenchTab
    ) : ProjectGitHubWorkspaceNavigationAction

    data object ClearPendingDetailTarget : ProjectGitHubWorkspaceNavigationAction

    data object CloseRepoWorkbench : ProjectGitHubWorkspaceNavigationAction

    data object CloseNavigationLayer : ProjectGitHubWorkspaceNavigationAction

    data class NormalizeDetectedRepos(
        val detectedRepos: List<ProjectDetectedRepoUi>
    ) : ProjectGitHubWorkspaceNavigationAction
}

internal fun clearProjectGitHubWorkspaceNavigationState():
    ProjectGitHubWorkspaceNavigationState {
    return ProjectGitHubWorkspaceNavigationState()
}

internal fun reduceProjectGitHubWorkspaceNavigationState(
    currentState: ProjectGitHubWorkspaceNavigationState,
    action: ProjectGitHubWorkspaceNavigationAction
): ProjectGitHubWorkspaceNavigationState {
    return when (action) {
        is ProjectGitHubWorkspaceNavigationAction.OpenRepoWorkbench -> {
            currentState.copy(
                isVisible = true,
                isDownloadCenterVisible = false,
                workbenchRepoRoot = action.rootPath,
                workbenchSelectedTab = action.selectedTab,
                pendingDetailTarget = null
            )
        }

        is ProjectGitHubWorkspaceNavigationAction.OpenDetailTarget -> {
            currentState.copy(
                isVisible = true,
                isDownloadCenterVisible = false,
                workbenchRepoRoot = action.target.rootPath,
                workbenchSelectedTab = action.target.selectedTab,
                pendingDetailTarget = action.target
            )
        }

        ProjectGitHubWorkspaceNavigationAction.OpenDownloadCenter -> {
            currentState.copy(
                isVisible = true,
                isDownloadCenterVisible = true,
                workbenchRepoRoot = null,
                workbenchSelectedTab = ProjectGitHubWorkspaceRepoWorkbenchTab.OVERVIEW,
                pendingDetailTarget = null
            )
        }

        ProjectGitHubWorkspaceNavigationAction.OpenOverview -> {
            currentState.copy(
                isVisible = true,
                isDownloadCenterVisible = false,
                workbenchRepoRoot = null,
                workbenchSelectedTab = ProjectGitHubWorkspaceRepoWorkbenchTab.OVERVIEW,
                pendingDetailTarget = null
            )
        }

        is ProjectGitHubWorkspaceNavigationAction.SelectWorkbenchTab -> {
            currentState.copy(
                isVisible = true,
                isDownloadCenterVisible = false,
                workbenchSelectedTab = action.selectedTab,
                pendingDetailTarget = null
            )
        }

        ProjectGitHubWorkspaceNavigationAction.ClearPendingDetailTarget -> {
            currentState.copy(pendingDetailTarget = null)
        }

        ProjectGitHubWorkspaceNavigationAction.CloseRepoWorkbench -> {
            currentState.copy(
                workbenchRepoRoot = null,
                workbenchSelectedTab = ProjectGitHubWorkspaceRepoWorkbenchTab.OVERVIEW,
                pendingDetailTarget = null
            )
        }

        ProjectGitHubWorkspaceNavigationAction.CloseNavigationLayer -> {
            // #region debug-point A:git-helper-entry
            reportGitBackChatFlashWorkspaceNavDebug(
                hypothesisId = "A",
                location = "ProjectGitHubWorkspaceNavigationHelpers.kt:closeLayer",
                msg = "[DEBUG] workspace navigation helper invoked",
                data = JSONObject()
                    .put("isVisible", currentState.isVisible)
                    .put("isDownloadCenterVisible", currentState.isDownloadCenterVisible)
                    .put("workbenchRepoRoot", currentState.workbenchRepoRoot ?: JSONObject.NULL)
                    .put("workbenchSelectedTab", currentState.workbenchSelectedTab.name)
            )
            // #endregion
            when {
                currentState.workbenchRepoRoot != null &&
                    currentState.workbenchSelectedTab != ProjectGitHubWorkspaceRepoWorkbenchTab.OVERVIEW -> {
                    reduceProjectGitHubWorkspaceNavigationState(
                        currentState = currentState,
                        action = ProjectGitHubWorkspaceNavigationAction.SelectWorkbenchTab(
                            selectedTab = ProjectGitHubWorkspaceRepoWorkbenchTab.OVERVIEW
                        )
                    )
                }

                currentState.workbenchRepoRoot != null -> {
                    reduceProjectGitHubWorkspaceNavigationState(
                        currentState = currentState,
                        action = ProjectGitHubWorkspaceNavigationAction.CloseRepoWorkbench
                    )
                }

                currentState.isDownloadCenterVisible -> {
                    reduceProjectGitHubWorkspaceNavigationState(
                        currentState = currentState,
                        action = ProjectGitHubWorkspaceNavigationAction.OpenOverview
                    )
                }

                else -> {
                    clearProjectGitHubWorkspaceNavigationState()
                }
            }
        }

        is ProjectGitHubWorkspaceNavigationAction.NormalizeDetectedRepos -> {
            val workbenchRepoRoot = currentState.workbenchRepoRoot ?: return currentState
            if (action.detectedRepos.any { it.rootPath == workbenchRepoRoot }) {
                currentState
            } else {
                reduceProjectGitHubWorkspaceNavigationState(
                    currentState = currentState,
                    action = ProjectGitHubWorkspaceNavigationAction.CloseRepoWorkbench
                )
            }
        }
    }
}

internal fun openProjectGitHubWorkspaceRepoWorkbench(
    currentState: ProjectGitHubWorkspaceNavigationState,
    rootPath: String,
    selectedTab: ProjectGitHubWorkspaceRepoWorkbenchTab =
        ProjectGitHubWorkspaceRepoWorkbenchTab.OVERVIEW
): ProjectGitHubWorkspaceNavigationState {
    return reduceProjectGitHubWorkspaceNavigationState(
        currentState = currentState,
        action = ProjectGitHubWorkspaceNavigationAction.OpenRepoWorkbench(
            rootPath = rootPath,
            selectedTab = selectedTab
        )
    )
}

internal fun openProjectGitHubWorkspaceDetailTarget(
    currentState: ProjectGitHubWorkspaceNavigationState,
    target: ProjectGitHubWorkspaceDetailTarget
): ProjectGitHubWorkspaceNavigationState {
    return reduceProjectGitHubWorkspaceNavigationState(
        currentState = currentState,
        action = ProjectGitHubWorkspaceNavigationAction.OpenDetailTarget(target)
    )
}

internal fun openProjectGitHubWorkspaceDownloadCenter(
    currentState: ProjectGitHubWorkspaceNavigationState
): ProjectGitHubWorkspaceNavigationState {
    return reduceProjectGitHubWorkspaceNavigationState(
        currentState = currentState,
        action = ProjectGitHubWorkspaceNavigationAction.OpenDownloadCenter
    )
}

internal fun openProjectGitHubWorkspaceOverview(
    currentState: ProjectGitHubWorkspaceNavigationState
): ProjectGitHubWorkspaceNavigationState {
    return reduceProjectGitHubWorkspaceNavigationState(
        currentState = currentState,
        action = ProjectGitHubWorkspaceNavigationAction.OpenOverview
    )
}

internal fun selectProjectGitHubWorkspaceWorkbenchTab(
    currentState: ProjectGitHubWorkspaceNavigationState,
    selectedTab: ProjectGitHubWorkspaceRepoWorkbenchTab
): ProjectGitHubWorkspaceNavigationState {
    return reduceProjectGitHubWorkspaceNavigationState(
        currentState = currentState,
        action = ProjectGitHubWorkspaceNavigationAction.SelectWorkbenchTab(selectedTab)
    )
}

internal fun clearProjectGitHubWorkspacePendingDetailTarget(
    currentState: ProjectGitHubWorkspaceNavigationState
): ProjectGitHubWorkspaceNavigationState {
    return reduceProjectGitHubWorkspaceNavigationState(
        currentState = currentState,
        action = ProjectGitHubWorkspaceNavigationAction.ClearPendingDetailTarget
    )
}

internal fun closeProjectGitHubWorkspaceRepoWorkbench(
    currentState: ProjectGitHubWorkspaceNavigationState
): ProjectGitHubWorkspaceNavigationState {
    return reduceProjectGitHubWorkspaceNavigationState(
        currentState = currentState,
        action = ProjectGitHubWorkspaceNavigationAction.CloseRepoWorkbench
    )
}

internal fun closeProjectGitHubWorkspaceNavigationLayer(
    currentState: ProjectGitHubWorkspaceNavigationState
): ProjectGitHubWorkspaceNavigationState {
    return reduceProjectGitHubWorkspaceNavigationState(
        currentState = currentState,
        action = ProjectGitHubWorkspaceNavigationAction.CloseNavigationLayer
    )
}

// #region debug-point A:git-helper-reporter
private const val ENABLE_REASONIX_BACK_DEBUG_REPORTS = false

private fun reportGitBackChatFlashWorkspaceNavDebug(
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
                .put("sessionId", "chat-entry-back-animation")
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

internal fun normalizeProjectGitHubWorkspaceNavigationState(
    currentState: ProjectGitHubWorkspaceNavigationState,
    detectedRepos: List<ProjectDetectedRepoUi>
): ProjectGitHubWorkspaceNavigationState {
    return reduceProjectGitHubWorkspaceNavigationState(
        currentState = currentState,
        action = ProjectGitHubWorkspaceNavigationAction.NormalizeDetectedRepos(
            detectedRepos = detectedRepos
        )
    )
}

internal fun isProjectGitHubWorkspaceSecondaryPage(
    currentState: ProjectGitHubWorkspaceNavigationState
): Boolean {
    return currentState.isVisible
}
