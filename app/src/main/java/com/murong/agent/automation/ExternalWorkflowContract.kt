package com.murong.agent.automation

import com.murong.agent.core.automation.SavedWorkflowBackgroundEligibility
import com.murong.agent.core.automation.SavedWorkflowDefinition
import com.murong.agent.core.automation.SavedWorkflowTemplate
import com.murong.agent.core.automation.backgroundEligibility
import java.io.File
import java.util.UUID

object ExternalWorkflowContract {
    const val RUN_ACTION = "com.murong.agent.action.RUN_SAVED_WORKFLOW"
    const val RESULT_ACTION = "com.murong.agent.action.SAVED_WORKFLOW_RESULT"

    const val EXTRA_WORKFLOW_ID = "workflow_id"
    const val EXTRA_ACCESS_TOKEN = "access_token"
    const val EXTRA_REQUEST_ID = "request_id"
    const val EXTRA_PROJECT_PATH = "project_path"
    const val EXTRA_TASK_TEXT = "task_text"
    const val EXTRA_CALLBACK_PACKAGE = "callback_package"
    const val EXTRA_STATUS = "status"
    const val EXTRA_MESSAGE = "message"

    const val STATUS_QUEUED = "queued"
    const val STATUS_REJECTED = "rejected"
    const val STATUS_BLOCKED = "blocked"
    const val STATUS_SUCCEEDED = "succeeded"
    const val STATUS_FAILED = "failed"
    const val STATUS_CANCELLED = "cancelled"

    const val RESULT_ACCEPTED = 1
    const val RESULT_REJECTED = 0
}

data class ExternalWorkflowRawRequest(
    val workflowId: String?,
    val accessToken: String?,
    val requestId: String?,
    val projectPath: String?,
    val taskText: String?,
    val callbackPackage: String?,
    val sentFromPackage: String?
)

data class ExternalWorkflowRequest(
    val workflowId: String,
    val accessToken: String,
    val requestId: String,
    val projectPath: String?,
    val taskText: String?,
    val callbackPackage: String?
)

data class ExternalSavedWorkflowInvocation(
    val workflowId: String,
    val requestId: String,
    val projectPathOverride: String? = null,
    val taskText: String? = null,
    val callbackPackage: String? = null
)

object ExternalWorkflowRequestPolicy {
    private val safeIdentifier = Regex("[A-Za-z0-9._:-]+")
    private val safePackage = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+")

    fun parse(raw: ExternalWorkflowRawRequest): Result<ExternalWorkflowRequest> = runCatching {
        val workflowId = raw.workflowId?.trim().orEmpty()
        require(workflowId.length in 1..128 && safeIdentifier.matches(workflowId)) {
            "workflow_id 缺失或格式无效"
        }
        val token = raw.accessToken?.trim().orEmpty()
        require(token.length in 32..256 && token.none(Char::isWhitespace)) {
            "access_token 缺失或格式无效"
        }
        val requestId = raw.requestId?.trim()?.takeIf(String::isNotEmpty)
            ?: UUID.randomUUID().toString()
        require(requestId.length in 8..96 && safeIdentifier.matches(requestId)) {
            "request_id 格式无效"
        }
        val projectPath = raw.projectPath?.trim()?.takeIf(String::isNotEmpty)
        require(projectPath == null || (projectPath.length <= 1024 && '\u0000' !in projectPath)) {
            "project_path 过长或格式无效"
        }
        val taskText = raw.taskText?.trim()?.takeIf(String::isNotEmpty)
        require(taskText == null || taskText.length <= 2_000) { "task_text 不能超过 2000 个字符" }
        require(taskText == null || taskText.all { !it.isISOControl() || it == '\n' || it == '\r' || it == '\t' }) {
            "task_text 含有不允许的控制字符"
        }
        val callbackPackage = raw.callbackPackage?.trim()?.takeIf(String::isNotEmpty)
        if (callbackPackage != null) {
            require(callbackPackage.length <= 255 && safePackage.matches(callbackPackage)) {
                "callback_package 格式无效"
            }
            require(raw.sentFromPackage != null && callbackPackage == raw.sentFromPackage) {
                "callback_package 必须与真实发送应用一致"
            }
        }
        ExternalWorkflowRequest(
            workflowId = workflowId,
            accessToken = token,
            requestId = requestId,
            projectPath = projectPath,
            taskText = taskText,
            callbackPackage = callbackPackage
        )
    }

    /** Applies only a narrower project scope. The returned definition must still be revalidated by the worker. */
    fun applyProjectOverride(
        workflow: SavedWorkflowDefinition,
        projectPathOverride: String?
    ): Result<SavedWorkflowDefinition> = runCatching {
        val override = projectPathOverride?.trim()?.takeIf(String::isNotEmpty) ?: return@runCatching workflow
        require(
            workflow.template == SavedWorkflowTemplate.PROJECT_READ_DIAGNOSTIC ||
                workflow.template == SavedWorkflowTemplate.DIRECTORY_CHANGE_SUMMARY
        ) { "此工作流模板不接受 project_path" }
        val savedPath = workflow.projectPath?.trim().orEmpty()
        require(savedPath.isNotEmpty()) { "保存的工作流没有项目范围" }
        val allowedRoot = File(savedPath).canonicalFile
        val requested = File(override).canonicalFile
        val allowedPrefix = allowedRoot.path.trimEnd(File.separatorChar) + File.separator
        require(requested.path == allowedRoot.path || requested.path.startsWith(allowedPrefix)) {
            "project_path 超出保存工作流的项目范围"
        }
        workflow.copy(projectPath = requested.path)
    }

    fun requireBackgroundSafe(workflow: SavedWorkflowDefinition): Result<Unit> = runCatching {
        require(workflow.backgroundEligibility() == SavedWorkflowBackgroundEligibility.ALLOWED_READ_ONLY) {
            "该工作流需要回到 Murong 前台逐次确认"
        }
    }
}
