package dev.reasonix.mobile.ui.project

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
internal data class ProjectGitHubDownloadStore(
    val records: List<ProjectGitHubDownloadRecordUi> = emptyList()
)

private val PROJECT_GITHUB_DOWNLOAD_JSON = Json { ignoreUnknownKeys = true; isLenient = true; prettyPrint = true }

internal fun loadProjectGitHubDownloadStore(contextPath: String): ProjectGitHubDownloadStore {
    return try {
        val file = File(contextPath, "github_downloads.json")
        if (file.exists()) {
            val json = file.readText()
            PROJECT_GITHUB_DOWNLOAD_JSON.decodeFromString<ProjectGitHubDownloadStore>(json)
        } else {
            ProjectGitHubDownloadStore()
        }
    } catch (e: Exception) {
        ProjectGitHubDownloadStore()
    }
}

internal fun saveProjectGitHubDownloadStore(contextPath: String, store: ProjectGitHubDownloadStore) {
    try {
        val file = File(contextPath, "github_downloads.json")
        val json = PROJECT_GITHUB_DOWNLOAD_JSON.encodeToString(ProjectGitHubDownloadStore.serializer(), store)
        file.writeText(json)
    } catch (e: Exception) {
        // Ignore save errors
    }
}

internal fun deleteProjectGitHubDownloadRecord(
    currentStore: ProjectGitHubDownloadStore,
    recordId: String
): ProjectGitHubDownloadStore {
    return currentStore.copy(records = currentStore.records.filter { it.id != recordId })
}

internal fun clearProjectGitHubDownloadHistory(
    currentStore: ProjectGitHubDownloadStore
): ProjectGitHubDownloadStore {
    return ProjectGitHubDownloadStore()
}

internal fun recordProjectGitHubDownload(
    currentStore: ProjectGitHubDownloadStore,
    typeLabel: String,
    title: String,
    fileName: String,
    downloadId: Long,
    sourceUrl: String?,
    repo: ProjectGitHubRepoRef? = null,
    repoLabel: String? = null,
    maxHistorySize: Int = 12
): ProjectGitHubDownloadStore {
    val newRecord = ProjectGitHubDownloadRecordUi(
        id = UUID.randomUUID().toString(),
        typeLabel = typeLabel,
        title = title,
        fileName = fileName,
        createdAtMillis = System.currentTimeMillis(),
        downloadId = downloadId,
        repoOwner = repo?.owner,
        repoName = repo?.repo,
        repoLabel = repoLabel,
        sourceUrl = sourceUrl
    )
    val nextRecords = (listOf(newRecord) + currentStore.records).take(maxHistorySize)
    return currentStore.copy(records = nextRecords)
}
