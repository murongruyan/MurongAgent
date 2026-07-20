package com.murong.agent.automation

import com.murong.agent.core.automation.SavedWorkflowDefinition
import com.murong.agent.core.config.ProviderConfig
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/** Fixed, GET-only GitHub Actions query shared by foreground and background workflow execution. */
class GitHubActionsStatusReader(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun execute(workflow: SavedWorkflowDefinition, config: ProviderConfig): String {
        require(config.isGitHubSignedIn()) { "当前还没有完成 GitHub 登录。" }
        val repository = workflow.githubRepository?.trim().orEmpty()
        require(GITHUB_REPOSITORY_PATTERN.matches(repository)) { "GitHub 仓库格式应为 owner/repository。" }
        val request = Request.Builder()
            .url(config.getGitHubApiBaseUrl().trimEnd('/') + "/repos/$repository/actions/runs?per_page=5")
            .addHeader("Authorization", "Bearer ${config.githubToken}")
            .addHeader("Accept", "application/vnd.github+json")
            .addHeader("X-GitHub-Api-Version", "2022-11-28")
            .addHeader("User-Agent", "MurongAgent/1.0")
            .get()
            .build()
        return httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            require(response.isSuccessful) { "GitHub Actions 状态查询失败，HTTP ${response.code}" }
            val runs = json.parseToJsonElement(body).jsonObject["workflow_runs"]?.jsonArray.orEmpty()
            val compactRuns = runs.take(5).mapNotNull { item ->
                val objectValue = item.jsonObject
                val name = objectValue["name"]?.jsonPrimitive?.content.orEmpty().trim()
                val status = objectValue["status"]?.jsonPrimitive?.content.orEmpty().trim()
                val conclusion = objectValue["conclusion"]?.jsonPrimitive?.content.orEmpty().trim()
                if (name.isBlank() && status.isBlank()) null else "$name：${conclusion.ifBlank { status }}"
            }
            "GitHub Actions 已查询 $repository：" +
                (compactRuns.takeIf { it.isNotEmpty() }?.joinToString("；") ?: "暂未返回工作流运行记录")
        }
    }
}

private val GITHUB_REPOSITORY_PATTERN = Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")
