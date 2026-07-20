package com.murong.agent.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import com.murong.agent.core.doctor.SensitiveDataSanitizer

/** Explicit, token-authenticated system automation entry point. It never executes tools itself. */
class ExternalSavedWorkflowReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ExternalWorkflowContract.RUN_ACTION) return
        val accessStore = ExternalWorkflowAccessStore(context)
        val senderPackage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            sentFromPackage
        } else {
            null
        }
        val parsed = ExternalWorkflowRequestPolicy.parse(
            ExternalWorkflowRawRequest(
                workflowId = intent.getStringExtra(ExternalWorkflowContract.EXTRA_WORKFLOW_ID),
                accessToken = intent.getStringExtra(ExternalWorkflowContract.EXTRA_ACCESS_TOKEN),
                requestId = intent.getStringExtra(ExternalWorkflowContract.EXTRA_REQUEST_ID),
                projectPath = intent.getStringExtra(ExternalWorkflowContract.EXTRA_PROJECT_PATH),
                taskText = intent.getStringExtra(ExternalWorkflowContract.EXTRA_TASK_TEXT),
                callbackPackage = intent.getStringExtra(ExternalWorkflowContract.EXTRA_CALLBACK_PACKAGE),
                sentFromPackage = senderPackage
            )
        )
        if (parsed.isFailure) {
            replyRejected(parsed.exceptionOrNull()?.message ?: "外部工作流请求格式无效")
            return
        }
        val request = parsed.getOrThrow()
        if (!accessStore.authenticate(request.accessToken)) {
            replyRejected("外部自动化已关闭或访问令牌无效")
            return
        }

        val scheduler = runCatching { SavedWorkflowScheduler(context) }.getOrElse {
            replyRejected("工作流调度器暂时不可用")
            return
        }
        val workflow = scheduler.list().firstOrNull { it.id == request.workflowId }
        if (workflow == null) {
            replyRejected("找不到指定的保存工作流", request.requestId, request.workflowId)
            return
        }
        val scopedWorkflow = ExternalWorkflowRequestPolicy
            .applyProjectOverride(workflow, request.projectPath)
            .getOrElse {
                replyRejected(it.message ?: "项目范围无效", request.requestId, request.workflowId)
                return
            }
        val safePolicy = ExternalWorkflowRequestPolicy.requireBackgroundSafe(scopedWorkflow)
        if (safePolicy.isFailure) {
            if (accessStore.claimRequest(request.requestId)) {
                val outcome = scheduler.rejectExternal(
                    workflowId = request.workflowId,
                    reason = safePolicy.exceptionOrNull()?.message ?: "需要前台确认"
                )
                ExternalWorkflowApprovalNotifier.notify(context, workflow)
                ExternalWorkflowResultDispatcher.dispatch(
                    context = context,
                    requestId = request.requestId,
                    workflowId = request.workflowId,
                    status = ExternalWorkflowContract.STATUS_BLOCKED,
                    message = outcome?.message ?: "外部请求已被安全策略拦截",
                    callbackPackage = request.callbackPackage
                )
            }
            replyRejected("该工作流需要回到 Murong 前台逐次确认", request.requestId, request.workflowId)
            return
        }
        if (!accessStore.claimRequest(request.requestId)) {
            replyRejected("request_id 已使用，请为新调用生成新的 ID", request.requestId, request.workflowId)
            return
        }
        val outcome = runCatching {
            scheduler.runExternal(
                ExternalSavedWorkflowInvocation(
                    workflowId = request.workflowId,
                    requestId = request.requestId,
                    projectPathOverride = request.projectPath,
                    taskText = request.taskText,
                    callbackPackage = request.callbackPackage
                )
            )
        }.getOrElse { error ->
            ExternalWorkflowResultDispatcher.dispatch(
                context,
                request.requestId,
                request.workflowId,
                ExternalWorkflowContract.STATUS_FAILED,
                error.message ?: "无法排队外部工作流",
                request.callbackPackage
            )
            replyRejected("无法排队外部工作流", request.requestId, request.workflowId)
            return
        }
        if (outcome?.scheduled == true) {
            accessStore.recordResult(request.requestId, ExternalWorkflowContract.STATUS_QUEUED, outcome.message)
            replyAccepted(outcome.message, request.requestId, request.workflowId)
        } else {
            val message = outcome?.message ?: "找不到指定的保存工作流"
            ExternalWorkflowResultDispatcher.dispatch(
                context,
                request.requestId,
                request.workflowId,
                ExternalWorkflowContract.STATUS_BLOCKED,
                message,
                request.callbackPackage
            )
            replyRejected(message, request.requestId, request.workflowId)
        }
    }

    private fun replyAccepted(message: String, requestId: String, workflowId: String) {
        reply(
            code = ExternalWorkflowContract.RESULT_ACCEPTED,
            status = ExternalWorkflowContract.STATUS_QUEUED,
            message = message,
            requestId = requestId,
            workflowId = workflowId
        )
    }

    private fun replyRejected(message: String, requestId: String? = null, workflowId: String? = null) {
        reply(
            code = ExternalWorkflowContract.RESULT_REJECTED,
            status = ExternalWorkflowContract.STATUS_REJECTED,
            message = message,
            requestId = requestId,
            workflowId = workflowId
        )
    }

    private fun reply(
        code: Int,
        status: String,
        message: String,
        requestId: String?,
        workflowId: String?
    ) {
        if (!isOrderedBroadcast) return
        val safeMessage = SensitiveDataSanitizer.sanitizeText(message, redactPaths = true).take(1_000)
        resultCode = code
        resultData = status
        setResultExtras(Bundle().apply {
            putString(ExternalWorkflowContract.EXTRA_STATUS, status)
            putString(ExternalWorkflowContract.EXTRA_MESSAGE, safeMessage)
            requestId?.let { putString(ExternalWorkflowContract.EXTRA_REQUEST_ID, it) }
            workflowId?.let { putString(ExternalWorkflowContract.EXTRA_WORKFLOW_ID, it) }
        })
    }
}
