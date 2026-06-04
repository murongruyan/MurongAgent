package dev.reasonix.mobile.ui.project

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment

internal data class ProjectGitHubDownloadActionRecord(
    val typeLabel: String,
    val title: String,
    val fileName: String,
    val downloadId: Long,
    val sourceUrl: String?,
    val repo: ProjectGitHubRepoRef
)

internal data class ProjectGitHubReleaseAssetActionResult(
    val feedbackMessage: String? = null,
    val downloadRecord: ProjectGitHubDownloadActionRecord? = null
)

internal data class ProjectGitHubRemoteFileEditorActionResult(
    val success: Boolean,
    val feedbackMessage: String,
    val shouldCloseDialog: Boolean = false,
    val shouldRefreshBrowser: Boolean = false,
    val refreshTargetPath: String = ""
)

internal fun enqueueProjectGitHubReleaseAssetDownloadAction(
    context: Context,
    repo: ProjectGitHubRepoRef?,
    asset: ProjectGitHubReleaseAssetUi,
    sourceUrl: String?,
    token: String
): ProjectGitHubReleaseAssetActionResult {
    if (repo == null) return ProjectGitHubReleaseAssetActionResult()
    if (token.isBlank()) {
        return ProjectGitHubReleaseAssetActionResult(
            feedbackMessage = "请先在设置页填写 GitHub Token。"
        )
    }
    val result = enqueueProjectGitHubReleaseAssetDownload(
        context = context,
        repo = repo,
        asset = asset,
        token = token
    )
    return ProjectGitHubReleaseAssetActionResult(
        feedbackMessage = "已开始下载 ${result.fileName}",
        downloadRecord = ProjectGitHubDownloadActionRecord(
            typeLabel = "Release 资产",
            title = asset.name,
            fileName = result.fileName,
            downloadId = result.downloadId,
            sourceUrl = sourceUrl,
            repo = repo
        )
    )
}

internal suspend fun saveProjectGitHubRemoteFileEditorAction(
    currentRepo: ProjectGitHubRepoRef?,
    gitRemoteUrl: String?,
    token: String,
    currentRemoteRef: String,
    defaultBranch: String?,
    file: ProjectGitHubRemoteFileUi,
    contentDraft: String,
    commitMessageDraft: String,
    apiBaseUrl: String,
    refreshTargetPath: String
): ProjectGitHubRemoteFileEditorActionResult {
    val result = saveProjectGitHubRemoteFileEditor(
        currentRepo = currentRepo,
        gitRemoteUrl = gitRemoteUrl,
        token = token,
        currentRemoteRef = currentRemoteRef,
        defaultBranch = defaultBranch,
        file = file,
        contentDraft = contentDraft,
        commitMessageDraft = commitMessageDraft,
        apiBaseUrl = apiBaseUrl
    )
    return ProjectGitHubRemoteFileEditorActionResult(
        success = result.success,
        feedbackMessage = result.feedbackMessage,
        shouldCloseDialog = result.success,
        shouldRefreshBrowser = result.success,
        refreshTargetPath = if (result.success) refreshTargetPath else ""
    )
}

internal fun enqueueProjectGitHubReleaseAssetDownload(
    context: Context,
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
