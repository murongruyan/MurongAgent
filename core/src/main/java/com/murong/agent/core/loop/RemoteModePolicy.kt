package com.murong.agent.core.loop

import kotlinx.serialization.Serializable

@Serializable
enum class WorkspaceMode {
    REMOTE_PREFERRED,
    HYBRID,
    LOCAL_ONLY
}

/**
 * 单轮请求的模型工具暴露上下文。
 *
 * 工具仍可注册到 [com.murong.agent.core.tool.ToolRegistry] 供 UI 与内部入口使用，
 * 这里只决定哪些定义值得占用当前模型请求的 prompt。
 */
internal data class PromptToolExposureContext(
    val requestText: String,
    val hasRemoteTaskRepository: Boolean,
    val remoteTaskRepositoryEditable: Boolean,
    val allowWriteTools: Boolean,
    val hasLocalProject: Boolean,
    val hasActiveWorkflow: Boolean,
    val hasActiveSubagentWork: Boolean,
    val hasPendingMemorySuggestion: Boolean
)

internal fun buildPromptToolExposureContext(
    state: SessionState,
    requestText: String,
    allowWriteTools: Boolean
): PromptToolExposureContext {
    val activeWorkflow = listOfNotNull(state.pendingWorkflowPlan, state.canonicalWorkflowPlan)
        .any { plan -> plan.status == WorkflowPlanStatusUi.EXECUTING || plan.status == WorkflowPlanStatusUi.BLOCKED }
    val activeSubagentWork = state.subagentRuns.any { it.finishedAt == null } ||
        state.subagentBatches.any { it.finishedAt == null }
    val durableIntentContext = listOfNotNull(
        requestText.trim().takeIf { it.isNotBlank() },
        state.sessionGoal?.trim()?.takeIf { it.isNotBlank() },
        state.recentToolCalls
            .takeLast(4)
            .joinToString(" ") { it.toolName }
            .takeIf { it.isNotBlank() }
    ).joinToString(" ").lowercase()
    return PromptToolExposureContext(
        requestText = durableIntentContext,
        hasRemoteTaskRepository = hasRemoteTaskRepositoryContext(state),
        remoteTaskRepositoryEditable = state.remoteTaskRepositoryEditable,
        allowWriteTools = allowWriteTools,
        hasLocalProject = hasLocalProjectContext(state),
        hasActiveWorkflow = activeWorkflow,
        hasActiveSubagentWork = activeSubagentWork,
        hasPendingMemorySuggestion = state.recentMemoryUpdateSuggestions.any {
            it.status == MemoryUpdateSuggestionStatusUi.PENDING
        }
    )
}

/**
 * 裁剪模型工具清单中的高噪声工具。
 *
 * 这是相关性判断而不是权限判断；最终工具注册中心还会强制检查工具的
 * `isEnabled` 状态，防止“不可执行但仍展示”的注册配置绕过执行边界。
 */
internal fun shouldExposeToolForPrompt(
    toolName: String,
    context: PromptToolExposureContext
): Boolean {
    val normalizedName = toolName.trim().lowercase()
    val explicitlyRequested = context.requestText.contains(normalizedName, ignoreCase = true)

    if (normalizedName.startsWith("task_repo_")) {
        if (!context.hasRemoteTaskRepository) return false
        if (normalizedName in TASK_REPOSITORY_WRITE_TOOLS) {
            if (!context.allowWriteTools || !context.remoteTaskRepositoryEditable) return false
            if (normalizedName in TASK_REPOSITORY_LIFECYCLE_TOOLS) {
                return explicitlyRequested || context.requestText.containsAny(REMOTE_LIFECYCLE_INTENT_KEYWORDS)
            }
            return explicitlyRequested || context.hasActiveWorkflow ||
                context.requestText.containsAny(MUTATION_INTENT_KEYWORDS) ||
                ENGLISH_MUTATION_INTENT.containsMatchIn(context.requestText)
        }
        return true
    }

    if (normalizedName == "shell" || normalizedName == "code_edit") {
        return context.allowWriteTools
    }

    if (normalizedName == "complete_step") {
        return context.hasActiveWorkflow
    }

    if (normalizedName == "android") {
        return context.allowWriteTools &&
            (explicitlyRequested || context.requestText.containsAny(ANDROID_INTENT_KEYWORDS))
    }

    if (normalizedName == "subagent") {
        return context.allowWriteTools &&
            (context.hasActiveSubagentWork || explicitlyRequested ||
                context.requestText.containsAny(SUBAGENT_INTENT_KEYWORDS))
    }
    if (normalizedName in SUBAGENT_PRESET_TOOLS) {
        if (!context.allowWriteTools && normalizedName !in READ_ONLY_SUBAGENT_PRESET_TOOLS) return false
        return context.hasActiveSubagentWork || when (normalizedName) {
            "explore" -> context.requestText.containsAny(EXPLORE_INTENT_KEYWORDS)
            "research" -> context.requestText.containsAny(RESEARCH_INTENT_KEYWORDS)
            "review" -> context.requestText.containsAny(REVIEW_INTENT_KEYWORDS)
            "security_review" -> context.requestText.containsAny(SECURITY_REVIEW_INTENT_KEYWORDS)
            else -> false
        }
    }

    if (normalizedName in RULE_MANAGEMENT_TOOLS) {
        return context.allowWriteTools &&
            (explicitlyRequested || context.requestText.containsAny(RULE_INTENT_KEYWORDS)) &&
            (normalizedName != "create_project_rule" || context.hasLocalProject)
    }
    if (normalizedName in MEMORY_MANAGEMENT_TOOLS) {
        val readOnlyTool = normalizedName in READ_ONLY_MEMORY_TOOLS
        if (!readOnlyTool && !context.allowWriteTools) return false
        val relevant = explicitlyRequested || context.hasPendingMemorySuggestion ||
            context.requestText.containsAny(MEMORY_INTENT_KEYWORDS)
        return relevant && (!normalizedName.startsWith("create_project_") || context.hasLocalProject)
    }
    if (normalizedName in SKILL_MANAGEMENT_TOOLS) {
        val readOnlyTool = normalizedName == "read_skill"
        if (!readOnlyTool && !context.allowWriteTools) return false
        val relevant = explicitlyRequested || context.requestText.containsAny(SKILL_INTENT_KEYWORDS)
        return relevant && (normalizedName != "create_project_skill" || context.hasLocalProject)
    }
    if (normalizedName == "create_mcp_server") {
        return context.allowWriteTools &&
            (explicitlyRequested || context.requestText.containsAny(MCP_INTENT_KEYWORDS))
    }
    if (normalizedName == "session_history_search") {
        return explicitlyRequested || context.requestText.containsAny(SESSION_HISTORY_INTENT_KEYWORDS)
    }

    return true
}

private fun String.containsAny(keywords: Set<String>): Boolean = keywords.any { keyword ->
    contains(keyword, ignoreCase = true)
}

private val TASK_REPOSITORY_LIFECYCLE_TOOLS = setOf(
    "task_repo_create_branch",
    "task_repo_create_pr",
    "task_repo_close_pr",
    "task_repo_delete_branch"
)

private val TASK_REPOSITORY_WRITE_TOOLS = TASK_REPOSITORY_LIFECYCLE_TOOLS + setOf(
    "task_repo_search_replace",
    "task_repo_apply_patch",
    "task_repo_update_file",
    "task_repo_delete_file",
    "task_repo_commit_files"
)

private val SUBAGENT_PRESET_TOOLS = setOf("explore", "research", "review", "security_review")
private val READ_ONLY_SUBAGENT_PRESET_TOOLS = SUBAGENT_PRESET_TOOLS
private val RULE_MANAGEMENT_TOOLS = setOf("create_global_rule", "create_project_rule")
private val MEMORY_MANAGEMENT_TOOLS = setOf(
    "create_global_memory",
    "create_project_memory",
    "memory_list",
    "migrate_legacy_memories",
    "remember_memory",
    "memory_search",
    "memory_read",
    "forget_memory"
)
private val READ_ONLY_MEMORY_TOOLS = setOf("memory_list", "memory_search", "memory_read")
private val SKILL_MANAGEMENT_TOOLS = setOf(
    "create_global_skill",
    "create_project_skill",
    "read_skill",
    "run_skill"
)

private val MUTATION_INTENT_KEYWORDS = setOf(
    "开始修", "修复", "修改", "帮我改", "改一下", "改成", "实现", "新增", "添加",
    "删除", "移除", "重构", "更新代码", "写入", "提交", "推送", "落地", "动手", "开始做"
)
private val ENGLISH_MUTATION_INTENT = Regex(
    "\\b(fix|implement|add|edit|modify|update|delete|remove|refactor|commit|push|write)\\b",
    RegexOption.IGNORE_CASE
)
private val REMOTE_LIFECYCLE_INTENT_KEYWORDS = setOf(
    "branch", "分支", "pull request", "merge request", "创建 pr", "关闭 pr", "创建pr", "关闭pr", "合并请求"
)
private val ANDROID_INTENT_KEYWORDS = setOf(
    "安卓", "手机", "设备", "adb", "apk", "包名", "安装应用", "应用安装", "截图",
    "无障碍", "界面元素", "uiautomator", "logcat"
)
private val SUBAGENT_INTENT_KEYWORDS = setOf(
    "subagent", "sub-agent", "子代理", "子 agent", "子agent", "并行", "parallel", "委派", "分工", "delegate"
)
private val EXPLORE_INTENT_KEYWORDS = setOf("explore", "探索代码", "摸清代码", "调用链")
private val RESEARCH_INTENT_KEYWORDS = setOf("research", "调研", "资料研究", "方案研究")
private val REVIEW_INTENT_KEYWORDS = setOf("code review", "代码审查", "审查代码", "review")
private val SECURITY_REVIEW_INTENT_KEYWORDS = setOf(
    "security_review", "security review", "安全审查", "安全检查", "漏洞审查"
)
private val RULE_INTENT_KEYWORDS = setOf("全局规则", "项目规则", "保存规则", "创建规则", "global rule", "project rule")
private val MEMORY_INTENT_KEYWORDS = setOf(
    "记忆", "记住", "忘记", "回忆", "memory", "remember", "forget", "长期保存"
)
private val SKILL_INTENT_KEYWORDS = setOf("技能", "skill", "工作流技能")
private val MCP_INTENT_KEYWORDS = setOf("mcp", "工具服务器", "tool server")
private val SESSION_HISTORY_INTENT_KEYWORDS = setOf(
    "会话历史", "历史会话", "之前的对话", "以前的对话", "上次任务", "过去任务",
    "session history", "previous session", "past conversation"
)

internal fun hasRemoteTaskRepositoryContext(state: SessionState): Boolean {
    val owner = state.remoteTaskRepositoryOwner?.trim().orEmpty()
    val repo = state.remoteTaskRepositoryName?.trim().orEmpty()
    return owner.isNotBlank() && repo.isNotBlank()
}

internal fun hasLocalProjectContext(state: SessionState): Boolean {
    return !state.projectPath.isNullOrBlank()
}

fun resolveWorkspaceMode(state: SessionState): WorkspaceMode {
    if (!hasRemoteTaskRepositoryContext(state)) return WorkspaceMode.LOCAL_ONLY
    return when (state.workspaceMode) {
        WorkspaceMode.REMOTE_PREFERRED -> WorkspaceMode.REMOTE_PREFERRED
        WorkspaceMode.HYBRID -> if (hasLocalProjectContext(state)) WorkspaceMode.HYBRID else WorkspaceMode.REMOTE_PREFERRED
        WorkspaceMode.LOCAL_ONLY -> if (hasLocalProjectContext(state)) WorkspaceMode.LOCAL_ONLY else WorkspaceMode.REMOTE_PREFERRED
    }
}

/**
 * 远端模式下是否应暴露本地 [shell] 工具。
 * shell 在远端模式下仍然保留（可通过审批系统管控风险），
 * 因为用户可能需要执行本地 shell 命令做环境检查、构建或诊断。
 */
internal fun shouldExposeLocalShellTool(state: SessionState): Boolean {
    // shell 保留，通过 isEnabled + allowWriteTools + 审批系统来控制风险
    return true
}

/**
 * 远端模式下是否应暴露本地 [file] 工具的读取操作（read/list/exists）。
 * 读取本地文件在远端模式下仍然有用，例如查看本地项目配置、对比远端代码。
 */
internal fun shouldExposeLocalFileReadTool(state: SessionState): Boolean {
    return true
}

/**
 * 远端模式下是否应暴露本地 [file] 工具的写入操作（write/delete/chmod）。
 * 远端模式下应避免写入本地文件，除非用户显式切回本地项目。
 */
internal fun shouldExposeLocalFileWriteTool(state: SessionState): Boolean {
    return !hasRemoteTaskRepositoryContext(state) ||
        resolveWorkspaceMode(state) != WorkspaceMode.REMOTE_PREFERRED
}

/**
 * 远端模式下是否应暴露本地 [code_search] 工具。
 * 代码搜索仅读取本地索引，在远端模式下仍有助理解本地代码结构。
 */
internal fun shouldExposeLocalCodeSearchTool(state: SessionState): Boolean {
    return true
}

/**
 * 远端模式下是否应暴露本地 [code_edit] 工具。
 * 远端模式下应避免直接修改本地文件，优先使用 task_repo_* 编辑远端仓库。
 */
internal fun shouldExposeLocalCodeEditTool(state: SessionState): Boolean {
    return !hasRemoteTaskRepositoryContext(state) ||
        resolveWorkspaceMode(state) != WorkspaceMode.REMOTE_PREFERRED
}

/**
 * 兼容旧函数——判断是否完全隐藏所有本地工具（旧行为）。
 */
internal fun shouldExposeLocalProjectTools(state: SessionState): Boolean {
    return !hasRemoteTaskRepositoryContext(state) ||
        resolveWorkspaceMode(state) != WorkspaceMode.REMOTE_PREFERRED
}
