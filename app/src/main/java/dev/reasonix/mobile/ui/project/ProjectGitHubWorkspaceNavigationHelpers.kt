package dev.reasonix.mobile.ui.project

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal data class ProjectGitHubWorkspaceNavigationState(
    val isVisible: Boolean = false,
    val isDownloadCenterVisible: Boolean = false,
    val workbenchRepoRoot: String? = null,
    val workbenchSelectedTab: ProjectGitHubWorkspaceRepoWorkbenchTab =
        ProjectGitHubWorkspaceRepoWorkbenchTab.OVERVIEW
)

internal fun clearProjectGitHubWorkspaceNavigationState():
    ProjectGitHubWorkspaceNavigationState {
    return ProjectGitHubWorkspaceNavigationState()
}

internal fun openProjectGitHubWorkspaceRepoWorkbench(
    currentState: ProjectGitHubWorkspaceNavigationState,
    rootPath: String,
    selectedTab: ProjectGitHubWorkspaceRepoWorkbenchTab =
        ProjectGitHubWorkspaceRepoWorkbenchTab.OVERVIEW
): ProjectGitHubWorkspaceNavigationState {
    return currentState.copy(
        isVisible = true,
        isDownloadCenterVisible = false,
        workbenchRepoRoot = rootPath,
        workbenchSelectedTab = selectedTab
    )
}

internal fun openProjectGitHubWorkspaceDownloadCenter(
    currentState: ProjectGitHubWorkspaceNavigationState
): ProjectGitHubWorkspaceNavigationState {
    return currentState.copy(
        isVisible = true,
        isDownloadCenterVisible = true,
        workbenchRepoRoot = null,
        workbenchSelectedTab = ProjectGitHubWorkspaceRepoWorkbenchTab.OVERVIEW
    )
}

internal fun closeProjectGitHubWorkspaceRepoWorkbench(
    currentState: ProjectGitHubWorkspaceNavigationState
): ProjectGitHubWorkspaceNavigationState {
    return currentState.copy(
        workbenchRepoRoot = null,
        workbenchSelectedTab = ProjectGitHubWorkspaceRepoWorkbenchTab.OVERVIEW
    )
}

internal fun closeProjectGitHubWorkspaceNavigationLayer(
    currentState: ProjectGitHubWorkspaceNavigationState
): ProjectGitHubWorkspaceNavigationState {
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
    return when {
        currentState.workbenchRepoRoot != null &&
            currentState.workbenchSelectedTab != ProjectGitHubWorkspaceRepoWorkbenchTab.OVERVIEW -> {
            currentState.copy(
                workbenchSelectedTab = ProjectGitHubWorkspaceRepoWorkbenchTab.OVERVIEW
            )
        }

        currentState.workbenchRepoRoot != null -> {
            currentState.copy(
                workbenchRepoRoot = null,
                workbenchSelectedTab = ProjectGitHubWorkspaceRepoWorkbenchTab.OVERVIEW
            )
        }

        currentState.isDownloadCenterVisible -> {
            currentState.copy(isDownloadCenterVisible = false)
        }

        else -> {
            clearProjectGitHubWorkspaceNavigationState()
        }
    }
}

// #region debug-point A:git-helper-reporter
private fun reportGitBackChatFlashWorkspaceNavDebug(
    hypothesisId: String,
    location: String,
    msg: String,
    data: JSONObject
) {
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
                .put("sessionId", "git-back-chat-flash")
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
    val workbenchRepoRoot = currentState.workbenchRepoRoot ?: return currentState
    if (detectedRepos.any { it.rootPath == workbenchRepoRoot }) return currentState
    return currentState.copy(
        workbenchRepoRoot = null,
        workbenchSelectedTab = ProjectGitHubWorkspaceRepoWorkbenchTab.OVERVIEW
    )
}
