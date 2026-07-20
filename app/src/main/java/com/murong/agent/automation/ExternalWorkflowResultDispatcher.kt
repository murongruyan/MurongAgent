package com.murong.agent.automation

import android.content.Context
import android.content.Intent
import com.murong.agent.core.doctor.SensitiveDataSanitizer

object ExternalWorkflowResultDispatcher {
    fun dispatch(
        context: Context,
        requestId: String,
        workflowId: String,
        status: String,
        message: String,
        callbackPackage: String?
    ) {
        val safeMessage = SensitiveDataSanitizer.sanitizeText(message, redactPaths = true).take(1_000)
        runCatching {
            ExternalWorkflowAccessStore(context).recordResult(requestId, status, safeMessage)
        }
        if (callbackPackage.isNullOrBlank()) return
        val intent = Intent(ExternalWorkflowContract.RESULT_ACTION)
            .setPackage(callbackPackage)
            .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            .putExtra(ExternalWorkflowContract.EXTRA_REQUEST_ID, requestId)
            .putExtra(ExternalWorkflowContract.EXTRA_WORKFLOW_ID, workflowId)
            .putExtra(ExternalWorkflowContract.EXTRA_STATUS, status)
            .putExtra(ExternalWorkflowContract.EXTRA_MESSAGE, safeMessage)
        context.sendBroadcast(intent)
    }
}
