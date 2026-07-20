package com.murong.agent.core.automation

import com.murong.agent.core.config.ToolPermissionCategory
import kotlinx.serialization.Serializable
import java.util.UUID

/** Persisted, explainable automation. Execution engines must validate this model again at run time. */
@Serializable
data class SavedWorkflowDefinition(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val template: SavedWorkflowTemplate,
    val projectPath: String? = null,
    /** Required only by the GitHub Actions status template; never stores a token. */
    val githubRepository: String? = null,
    val nodes: List<SavedWorkflowNode> = template.defaultNodes(),
    val intervalMinutes: Long = DEFAULT_WORKFLOW_INTERVAL_MINUTES,
    val enabled: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val lastRun: SavedWorkflowRunRecord? = null
)

@Serializable
enum class SavedWorkflowTemplate(val label: String) {
    PROJECT_READ_DIAGNOSTIC("项目只读诊断"),
    GITHUB_ACTIONS_STATUS("GitHub Actions 状态检查"),
    SESSION_SUMMARY_EXPORT("会话摘要导出"),
    DIRECTORY_CHANGE_SUMMARY("目录变更摘要")
}

@Serializable
data class SavedWorkflowNode(
    val id: String,
    val label: String,
    val dependsOn: List<String> = emptyList(),
    val requiredPermission: ToolPermissionCategory = ToolPermissionCategory.PROJECT_READ,
    val timeoutSeconds: Long = 60,
    val maxRetries: Int = 0
)

@Serializable
enum class SavedWorkflowRunStatus { NEVER, QUEUED, RUNNING, SUCCEEDED, FAILED, BLOCKED, CANCELLED }

@Serializable
data class SavedWorkflowRunRecord(
    val status: SavedWorkflowRunStatus = SavedWorkflowRunStatus.NEVER,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    /** Must already be redacted by the platform executor. */
    val summary: String = "",
    val failureReason: String? = null
)

data class SavedWorkflowValidationResult(
    val isValid: Boolean,
    val errors: List<String> = emptyList(),
    val topologicalOrder: List<String> = emptyList()
)

enum class SavedWorkflowBackgroundEligibility {
    ALLOWED_READ_ONLY,
    NEEDS_FOREGROUND_CONFIRMATION,
    INVALID
}

fun SavedWorkflowDefinition.validate(): SavedWorkflowValidationResult {
    val errors = mutableListOf<String>()
    if (name.isBlank()) errors += "工作流名称不能为空。"
    if (
        template == SavedWorkflowTemplate.PROJECT_READ_DIAGNOSTIC ||
        template == SavedWorkflowTemplate.DIRECTORY_CHANGE_SUMMARY
    ) {
        if (projectPath.isNullOrBlank()) errors += "只读目录模板必须选择项目范围。"
    }
    if (template == SavedWorkflowTemplate.GITHUB_ACTIONS_STATUS) {
        val normalizedRepository = githubRepository?.trim().orEmpty()
        if (!GITHUB_REPOSITORY_PATTERN.matches(normalizedRepository)) {
            errors += "GitHub Actions 模板需要填写 owner/repository。"
        }
    }
    if (nodes.isEmpty()) errors += "工作流至少需要一个步骤。"
    if (intervalMinutes < MIN_WORKFLOW_INTERVAL_MINUTES) {
        errors += "周期不能小于 $MIN_WORKFLOW_INTERVAL_MINUTES 分钟。"
    }
    val nodeIds = nodes.map { it.id.trim() }
    if (nodeIds.any { it.isBlank() }) errors += "步骤 ID 不能为空。"
    if (nodeIds.distinct().size != nodeIds.size) errors += "步骤 ID 不能重复。"
    val nodeIdSet = nodeIds.toSet()
    nodes.forEach { node ->
        node.dependsOn.filterNot(nodeIdSet::contains).forEach { missing ->
            errors += "步骤「${node.label}」依赖不存在的步骤 $missing。"
        }
        if (node.timeoutSeconds !in 1..MAX_WORKFLOW_STEP_TIMEOUT_SECONDS) {
            errors += "步骤「${node.label}」的超时必须在 1 到 $MAX_WORKFLOW_STEP_TIMEOUT_SECONDS 秒之间。"
        }
        if (node.maxRetries !in 0..MAX_WORKFLOW_RETRIES) {
            errors += "步骤「${node.label}」的重试次数不能超过 $MAX_WORKFLOW_RETRIES。"
        }
    }
    if (errors.isNotEmpty()) return SavedWorkflowValidationResult(false, errors)

    val dependencies = nodes.associate { node -> node.id to node.dependsOn.toMutableSet() }.toMutableMap()
    val ordered = mutableListOf<String>()
    val ready = dependencies.filterValues { it.isEmpty() }.keys.sorted().toMutableList()
    while (ready.isNotEmpty()) {
        val next = ready.removeAt(0)
        ordered += next
        dependencies.remove(next)
        dependencies.forEach { (id, pending) ->
            if (pending.remove(next) && pending.isEmpty() && id !in ready) ready += id
        }
        ready.sort()
    }
    if (dependencies.isNotEmpty()) {
        return SavedWorkflowValidationResult(
            isValid = false,
            errors = listOf("工作流存在循环依赖，不能保存或调度。")
        )
    }
    return SavedWorkflowValidationResult(true, topologicalOrder = ordered)
}

fun SavedWorkflowDefinition.backgroundEligibility(): SavedWorkflowBackgroundEligibility {
    if (!validate().isValid) return SavedWorkflowBackgroundEligibility.INVALID
    // Never infer that a template is safe merely because a malformed or migrated definition
    // labels its nodes as read-only. The executor allow-list is template-based as well.
    val allowedPermission = when (template) {
        SavedWorkflowTemplate.PROJECT_READ_DIAGNOSTIC,
        SavedWorkflowTemplate.DIRECTORY_CHANGE_SUMMARY -> ToolPermissionCategory.PROJECT_READ
        SavedWorkflowTemplate.GITHUB_ACTIONS_STATUS -> ToolPermissionCategory.NETWORK_READ
        SavedWorkflowTemplate.SESSION_SUMMARY_EXPORT -> null
    }
    return if (allowedPermission != null && nodes.all { it.requiredPermission == allowedPermission }) {
        SavedWorkflowBackgroundEligibility.ALLOWED_READ_ONLY
    } else {
        SavedWorkflowBackgroundEligibility.NEEDS_FOREGROUND_CONFIRMATION
    }
}

fun SavedWorkflowTemplate.defaultNodes(): List<SavedWorkflowNode> {
    return when (this) {
        SavedWorkflowTemplate.PROJECT_READ_DIAGNOSTIC -> listOf(
            SavedWorkflowNode("inspect_project", "读取项目目录与可访问性")
        )
        SavedWorkflowTemplate.DIRECTORY_CHANGE_SUMMARY -> listOf(
            SavedWorkflowNode("summarize_directory", "汇总目录快照")
        )
        SavedWorkflowTemplate.GITHUB_ACTIONS_STATUS -> listOf(
            SavedWorkflowNode(
                id = "query_github_actions",
                label = "查询 GitHub Actions 状态",
                requiredPermission = ToolPermissionCategory.NETWORK_READ
            )
        )
        SavedWorkflowTemplate.SESSION_SUMMARY_EXPORT -> listOf(
            SavedWorkflowNode(
                id = "export_session_summary",
                label = "导出会话摘要",
                requiredPermission = ToolPermissionCategory.FILE_WRITE
            )
        )
    }
}

const val MIN_WORKFLOW_INTERVAL_MINUTES = 15L
const val DEFAULT_WORKFLOW_INTERVAL_MINUTES = 60L
const val MAX_WORKFLOW_STEP_TIMEOUT_SECONDS = 15 * 60L
const val MAX_WORKFLOW_RETRIES = 3
private val GITHUB_REPOSITORY_PATTERN = Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")
