package com.murong.agent.automation

import android.content.Context
import android.os.Environment
import com.murong.agent.core.automation.SavedWorkflowDefinition
import com.murong.agent.core.automation.SavedWorkflowTemplate
import com.murong.agent.core.config.ProviderConfig
import com.murong.agent.core.doctor.SensitiveDataSanitizer
import com.murong.agent.core.loop.ChatSessionManager
import com.murong.agent.core.loop.ConversationExportFormat
import java.io.File

/**
 * Executes only a workflow that the foreground UI has just described and the user has confirmed.
 * WorkManager never uses this executor.
 */
class ForegroundSavedWorkflowExecutor(
    context: Context,
    private val chatSessionManager: ChatSessionManager,
    private val gitHubActionsStatusReader: GitHubActionsStatusReader = GitHubActionsStatusReader()
) {
    private val appContext = context.applicationContext

    fun execute(workflow: SavedWorkflowDefinition, config: ProviderConfig): Result<String> = runCatching {
        when (workflow.template) {
            SavedWorkflowTemplate.GITHUB_ACTIONS_STATUS -> gitHubActionsStatusReader.execute(workflow, config)
            SavedWorkflowTemplate.SESSION_SUMMARY_EXPORT -> executeSessionSummaryExport(config)
            SavedWorkflowTemplate.PROJECT_READ_DIAGNOSTIC,
            SavedWorkflowTemplate.DIRECTORY_CHANGE_SUMMARY ->
                error("只读模板应由后台安全调度器执行")
        }
    }

    private fun executeSessionSummaryExport(config: ProviderConfig): String {
        val export = chatSessionManager.exportCurrentSession(ConversationExportFormat.MARKDOWN, config).getOrThrow()
        val directory = appContext.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            ?: File(appContext.filesDir, "exports")
        if (!directory.exists()) require(directory.mkdirs()) { "无法创建工作流导出目录" }
        val output = File(directory, File(export.fileName).name)
        output.writeText(export.content)
        return "会话摘要已导出到应用文档目录：${output.name}"
    }

}
