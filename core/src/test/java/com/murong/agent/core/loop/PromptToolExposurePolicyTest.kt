package com.murong.agent.core.loop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PromptToolExposurePolicyTest {

    @Test
    fun genericLocalTurn_reducesDefaultPromptToolsFrom43To7() {
        val context = context(requestText = "解释这段代码为什么会失败")

        val visible = FULL_DEFAULT_REGISTRY_TOOL_NAMES.filter { toolName ->
            shouldExposeToolForPrompt(toolName, context)
        }

        assertEquals(43, FULL_DEFAULT_REGISTRY_TOOL_NAMES.size)
        assertEquals(
            setOf(
                "ask_user",
                "shell",
                "file",
                "code_edit",
                "code_search",
                "web_search",
                "web_fetch"
            ),
            visible.toSet()
        )
        assertEquals(7, visible.size)
        assertFalse(shouldExposeToolForPrompt("complete_step", context))
        assertTrue(shouldExposeToolForPrompt("complete_step", context.copy(hasActiveWorkflow = true)))
    }

    @Test
    fun taskRepositoryTools_areNotVisibleWithoutBoundRepository() {
        val context = context(
            requestText = "请修复远端代码并创建分支和 PR",
            remoteRepository = false,
            remoteEditable = true
        )

        assertTrue(FULL_DEFAULT_REGISTRY_TOOL_NAMES.filter { it.startsWith("task_repo_") }.isNotEmpty())
        assertTrue(
            FULL_DEFAULT_REGISTRY_TOOL_NAMES
                .filter { it.startsWith("task_repo_") }
                .none { shouldExposeToolForPrompt(it, context) }
        )
    }

    @Test
    fun boundReadOnlyRepository_exposesOnlyReadTools() {
        val context = context(
            requestText = "请修复远端代码并创建分支",
            remoteRepository = true,
            remoteEditable = false
        )

        val visibleRemoteTools = FULL_DEFAULT_REGISTRY_TOOL_NAMES
            .filter { it.startsWith("task_repo_") }
            .filter { shouldExposeToolForPrompt(it, context) }

        assertEquals(
            setOf(
                "task_repo_search_code",
                "task_repo_list_dir",
                "task_repo_list_branches",
                "task_repo_read_file"
            ),
            visibleRemoteTools.toSet()
        )
    }

    @Test
    fun editableRepository_exposesContentWritesOnlyForMutationIntent() {
        val explanationContext = context(
            requestText = "解释远端项目的架构",
            remoteRepository = true,
            remoteEditable = true
        )
        val repairContext = explanationContext.copy(requestText = "开始修复代码")

        assertFalse(shouldExposeToolForPrompt("task_repo_apply_patch", explanationContext))
        assertTrue(shouldExposeToolForPrompt("task_repo_apply_patch", repairContext))
        assertFalse(shouldExposeToolForPrompt("task_repo_create_pr", repairContext))
        assertTrue(
            shouldExposeToolForPrompt(
                "task_repo_create_pr",
                repairContext.copy(requestText = "修好后创建 PR")
            )
        )
    }

    @Test
    fun highNoiseTools_requireApplicableIntent() {
        val generic = context(requestText = "帮我解释代码")

        assertFalse(shouldExposeToolForPrompt("android", generic))
        assertFalse(shouldExposeToolForPrompt("subagent", generic))
        assertFalse(shouldExposeToolForPrompt("create_global_rule", generic))
        assertFalse(shouldExposeToolForPrompt("remember_memory", generic))
        assertFalse(shouldExposeToolForPrompt("create_global_skill", generic))
        assertFalse(shouldExposeToolForPrompt("create_mcp_server", generic))
        assertFalse(shouldExposeToolForPrompt("session_history_search", generic))

        assertTrue(shouldExposeToolForPrompt("android", generic.copy(requestText = "用 adb 检查手机")))
        assertTrue(shouldExposeToolForPrompt("subagent", generic.copy(requestText = "让子代理并行检查")))
        assertTrue(shouldExposeToolForPrompt("remember_memory", generic.copy(requestText = "记住这个结论")))
        assertTrue(shouldExposeToolForPrompt("read_skill", generic.copy(requestText = "读取这个 skill")))
        assertTrue(shouldExposeToolForPrompt("create_mcp_server", generic.copy(requestText = "配置 MCP 服务器")))
        assertTrue(shouldExposeToolForPrompt("session_history_search", generic.copy(requestText = "搜索会话历史")))
    }

    @Test
    fun readOnlyTurn_doesNotExposeMutatingToolsEvenWhenExplicitlyRequested() {
        val context = context(
            requestText = "修复代码、安装 APK、让子代理执行、保存记忆并修改远端仓库",
            allowWriteTools = false,
            remoteRepository = true,
            remoteEditable = true
        )

        val mutatingTools = setOf(
            "shell",
            "code_edit",
            "android",
            "subagent",
            "create_global_rule",
            "remember_memory",
            "run_skill",
            "task_repo_apply_patch"
        )

        assertTrue(mutatingTools.none { shouldExposeToolForPrompt(it, context) })
        assertTrue(shouldExposeToolForPrompt("file", context))
        assertTrue(shouldExposeToolForPrompt("memory_read", context))
        assertTrue(shouldExposeToolForPrompt("task_repo_read_file", context))
    }

    @Test
    fun continuationTurn_keepsToolsRelevantToSessionGoal() {
        val context = buildPromptToolExposureContext(
            state = SessionState(
                sessionGoal = "用 adb 检查手机并修复 Android 界面"
            ),
            requestText = "继续",
            allowWriteTools = true
        )

        assertTrue(shouldExposeToolForPrompt("android", context))
    }

    private fun context(
        requestText: String,
        allowWriteTools: Boolean = true,
        remoteRepository: Boolean = false,
        remoteEditable: Boolean = false,
        localProject: Boolean = true
    ) = PromptToolExposureContext(
        requestText = requestText.lowercase(),
        hasRemoteTaskRepository = remoteRepository,
        remoteTaskRepositoryEditable = remoteEditable,
        allowWriteTools = allowWriteTools,
        hasLocalProject = localProject,
        hasActiveWorkflow = false,
        hasActiveSubagentWork = false,
        hasPendingMemorySuggestion = false
    )

    private companion object {
        val FULL_DEFAULT_REGISTRY_TOOL_NAMES = listOf(
            "ask_user",
            "create_global_rule",
            "create_global_memory",
            "memory_list",
            "migrate_legacy_memories",
            "remember_memory",
            "memory_search",
            "memory_read",
            "forget_memory",
            "create_global_skill",
            "read_skill",
            "run_skill",
            "create_mcp_server",
            "create_project_rule",
            "create_project_memory",
            "create_project_skill",
            "session_history_search",
            "task_repo_search_code",
            "task_repo_list_dir",
            "task_repo_list_branches",
            "task_repo_create_branch",
            "task_repo_create_pr",
            "task_repo_close_pr",
            "task_repo_delete_branch",
            "task_repo_read_file",
            "task_repo_search_replace",
            "task_repo_apply_patch",
            "task_repo_update_file",
            "task_repo_delete_file",
            "task_repo_commit_files",
            "complete_step",
            "shell",
            "android",
            "file",
            "code_edit",
            "code_search",
            "web_search",
            "web_fetch",
            "subagent",
            "explore",
            "research",
            "review",
            "security_review"
        )
    }
}
