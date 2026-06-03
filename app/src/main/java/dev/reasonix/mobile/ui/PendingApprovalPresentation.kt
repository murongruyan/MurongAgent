package dev.reasonix.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.reasonix.mobile.core.loop.PendingApprovalUi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class PendingApprovalPresentation(
    val headline: String,
    val supportText: String?,
    val rows: List<Pair<String, String>>,
    val rawArgsLabel: String
)

@Composable
internal fun PendingApprovalSummaryCard(
    presentation: PendingApprovalPresentation,
    modifier: Modifier = Modifier
) {
    if (presentation.rows.isEmpty() && presentation.supportText.isNullOrBlank()) return
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            presentation.rows.forEach { (label, value) ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            presentation.supportText?.takeIf { it.isNotBlank() }?.let { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

internal fun PendingApprovalUi.toPendingApprovalPresentation(): PendingApprovalPresentation {
    if (!isGitHubPendingApproval()) {
        return PendingApprovalPresentation(
            headline = summary,
            supportText = detail.takeIf { it.isNotBlank() },
            rows = emptyList(),
            rawArgsLabel = "参数"
        )
    }

    val normalizedToolName = toolName.removePrefix("mcp_")
    val argsObject = parsePendingApprovalArgs(rawArgs)
    val actionLabel = gitHubActionLabel(normalizedToolName)
    val owner = argsObject?.get("owner")?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    val repo = argsObject?.get("repo")?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    val repoName = argsObject?.get("name")?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    val branch = argsObject?.get("branch")?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    val path = argsObject?.get("path")?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    val issueNumber = argsObject?.get("issue_number")?.jsonPrimitive?.intOrNull
    val pullNumber = argsObject?.get("pull_number")?.jsonPrimitive?.intOrNull

    val rows = buildList {
        add("操作" to actionLabel)
        when {
            owner.isNotBlank() && repo.isNotBlank() -> add("仓库" to "$owner/$repo")
            owner.isNotBlank() -> add("所有者" to owner)
        }
        if (repoName.isNotBlank() && repo.isBlank()) {
            add("仓库名" to repoName)
        }
        if (branch.isNotBlank()) {
            add("分支" to branch)
        }
        if (path.isNotBlank()) {
            add("文件" to path)
        }
        if (pullNumber != null) {
            add("Pull Request" to "#$pullNumber")
        }
        if (issueNumber != null) {
            add("Issue" to "#$issueNumber")
        }
    }

    return PendingApprovalPresentation(
        headline = "GitHub 远端写操作",
        supportText = "批准后才会真正修改远端 GitHub 资源；当前展示的是本次要操作的目标。",
        rows = rows,
        rawArgsLabel = "原始参数 (JSON)"
    )
}

private fun PendingApprovalUi.isGitHubPendingApproval(): Boolean {
    return toolName.contains("github", ignoreCase = true) ||
        summary.contains("GitHub", ignoreCase = true) ||
        detail.contains("MCP/GitHub", ignoreCase = true)
}

private fun parsePendingApprovalArgs(rawArgs: String) = runCatching {
    Json.parseToJsonElement(rawArgs).jsonObject
}.getOrNull()

private fun gitHubActionLabel(toolName: String): String {
    return when (toolName.lowercase()) {
        "create_repository" -> "创建仓库"
        "create_branch" -> "创建分支"
        "create_issue" -> "创建 Issue"
        "create_pull_request" -> "创建 Pull Request"
        "create_pull_request_review" -> "提交 Pull Request Review"
        "create_or_update_file" -> "写入远端文件"
        "push_files" -> "批量推送文件"
        "update_issue" -> "更新 Issue"
        "add_issue_comment" -> "添加 Issue 评论"
        "merge_pull_request" -> "合并 Pull Request"
        "update_pull_request_branch" -> "更新 Pull Request 分支"
        "fork_repository" -> "Fork 仓库"
        else -> "执行 GitHub 写操作"
    }
}
