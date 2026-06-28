package com.murong.agent.core.loop

import com.murong.agent.core.tool.StepSignOffReceipt
import kotlinx.serialization.Serializable

@Serializable
enum class FinalReadinessSessionStatusKind {
    NONE,
    BLOCKED,
    RECOVERED
}

data class FinalReadinessSessionTelemetry(
    val statusKind: FinalReadinessSessionStatusKind,
    val statusSummary: String,
    val reasonSummary: String
)

internal val FINAL_READINESS_WRITE_TOOL_NAMES = setOf(
    "task_repo_search_replace",
    "task_repo_apply_patch",
    "task_repo_update_file",
    "task_repo_commit_files",
    "task_repo_delete_file",
    "task_repo_create_branch",
    "task_repo_delete_branch",
    "task_repo_create_pr",
    "task_repo_close_pr",
    "code_edit"
)

internal val FINAL_READINESS_WRITE_TOOL_KEYWORDS = listOf(
    "write",
    "edit",
    "update",
    "patch",
    "replace",
    "delete",
    "commit",
    "mutating"
)

internal fun isFinalReadinessWriteToolName(toolName: String): Boolean {
    if (toolName in FINAL_READINESS_WRITE_TOOL_NAMES) return true
    val normalized = toolName.lowercase()
    return FINAL_READINESS_WRITE_TOOL_KEYWORDS.any { keyword ->
        normalized.contains(keyword)
    }
}

enum class FinalReadinessReceiptKind {
    MISSING_COMPLETE_STEP_AFTER_WRITE,
    INCOMPLETE_CANONICAL_WORKFLOW
}

enum class FinalReadinessRequiredAction {
    SIGN_OFF_WITH_EVIDENCE,
    COMPLETE_REMAINING_PLAN
}

enum class FinalReadinessAuditResult {
    BLOCKED,
    ALLOWED
}

enum class FinalReadinessLanguage {
    CHINESE,
    ENGLISH
}

data class FinalReadinessReceipt(
    val kind: FinalReadinessReceiptKind,
    val requiredAction: FinalReadinessRequiredAction,
    val message: String,
    val latestSuccessfulWriteToolName: String? = null,
    val remainingUnsignedSteps: Int? = null,
    val nextRequiredStep: String? = null,
    val latestSignedOffStep: String? = null,
    val latestSignedOffResultSummary: String? = null,
    val latestSignedOffMatchedTools: List<String> = emptyList(),
    val latestSignedOffSessionHistorySessionIds: List<String> = emptyList(),
    val latestSignedOffSessionHistoryMessageReferences: List<String> = emptyList()
)

data class FinalReadinessAuditRecord(
    val result: FinalReadinessAuditResult,
    val recovered: Boolean,
    val receiptKind: FinalReadinessReceiptKind,
    val requiredAction: FinalReadinessRequiredAction,
    val latestSuccessfulWriteToolName: String? = null,
    val remainingUnsignedSteps: Int? = null,
    val nextRequiredStep: String? = null,
    val latestSignedOffStep: String? = null,
    val latestSignedOffMatchedTools: List<String> = emptyList(),
    val latestSignedOffSessionHistorySessionIds: List<String> = emptyList(),
    val latestSignedOffSessionHistoryMessageReferences: List<String> = emptyList()
)

data class FinalReadinessAuditOverview(
    val totalCount: Int,
    val blockedCount: Int,
    val allowedCount: Int,
    val recoveredCount: Int,
    val writeSignOffBlockCount: Int,
    val canonicalWorkflowBlockCount: Int,
    val latestStatusSummary: String,
    val latestReasonSummary: String,
    val currentlyBlocked: Boolean
)

internal data class FinalReadinessToolRunSnapshot(
    val toolName: String,
    val result: String,
    val isSuccess: Boolean,
    val stepSignOffReceipt: StepSignOffReceipt? = null
)

internal fun buildWriteSignOffReadinessReceipt(
    completedToolRuns: List<FinalReadinessToolRunSnapshot>,
    language: FinalReadinessLanguage,
    isWriteTool: (String) -> Boolean
): FinalReadinessReceipt? {
    val latestSuccessfulWriteIndex = completedToolRuns.indexOfLast { toolRun ->
        toolRun.isSuccess && isWriteTool(toolRun.toolName)
    }
    if (latestSuccessfulWriteIndex < 0) return null
    val latestSuccessfulWrite = completedToolRuns[latestSuccessfulWriteIndex]
    val hasSuccessfulCompleteStepAfterWrite = completedToolRuns
        .drop(latestSuccessfulWriteIndex + 1)
        .any { toolRun ->
            toolRun.isSuccess &&
                toolRun.toolName == "complete_step" &&
                completeStepMentionsMatchedTool(
                    receipt = toolRun.stepSignOffReceipt,
                    toolName = latestSuccessfulWrite.toolName
                )
        }
    if (hasSuccessfulCompleteStepAfterWrite) return null
    val message = when (language) {
        FinalReadinessLanguage.CHINESE ->
            "最终收口校验未通过：写工具 `${latestSuccessfulWrite.toolName}` 已成功执行，但之后还没有成功且关联到该写工具的 `complete_step` 签收记录。"
        FinalReadinessLanguage.ENGLISH ->
            "Final readiness check failed: write tool `${latestSuccessfulWrite.toolName}` succeeded, " +
                "but there is no successful `complete_step` sign-off that is linked to that write tool."
    }
    return FinalReadinessReceipt(
        kind = FinalReadinessReceiptKind.MISSING_COMPLETE_STEP_AFTER_WRITE,
        requiredAction = FinalReadinessRequiredAction.SIGN_OFF_WITH_EVIDENCE,
        message = message,
        latestSuccessfulWriteToolName = latestSuccessfulWrite.toolName
    )
}

private fun completeStepMentionsMatchedTool(
    receipt: StepSignOffReceipt?,
    toolName: String
): Boolean {
    return receipt?.matchedToolNames?.any { matchedTool ->
        matchedTool.equals(toolName, ignoreCase = true)
    } == true
}

internal fun buildCanonicalWorkflowReadinessReceipt(
    canonicalPlan: WorkflowPlanUi?,
    executionGoal: String
): FinalReadinessReceipt? {
    val plan = canonicalPlan ?: return null
    if (!shouldApplyCanonicalWorkflowReadiness(plan, executionGoal)) return null
    val unsignedStepIndices = resolveCanonicalWorkflowUnsignedStepIndices(plan)
    val remainingStep = resolveCanonicalWorkflowNextRequiredStep(plan, unsignedStepIndices)
    val remainingCount = unsignedStepIndices.size.coerceAtLeast(1)
    val latestSignedOff = plan.stepSignOffs.maxByOrNull(WorkflowStepSignOffUi::signedOffAt)
    val lastSignedOffSummary = latestSignedOff?.let { signOff ->
        "最近已签收步骤：${signOff.step}（证据 ${signOff.matchedEvidenceCount}/${signOff.totalEvidenceCount}，结果：${signOff.resultSummary}）。"
    }.orEmpty()
    return FinalReadinessReceipt(
        kind = FinalReadinessReceiptKind.INCOMPLETE_CANONICAL_WORKFLOW,
        requiredAction = FinalReadinessRequiredAction.COMPLETE_REMAINING_PLAN,
        message = "跨轮次计划仍未完成：当前目标仍绑定到计划 `${plan.goal}`，" +
            "还剩 $remainingCount 个未签收步骤，下一步应为：$remainingStep。" +
            lastSignedOffSummary,
        remainingUnsignedSteps = remainingCount,
        nextRequiredStep = remainingStep,
        latestSignedOffStep = latestSignedOff?.step,
        latestSignedOffResultSummary = latestSignedOff?.resultSummary,
        latestSignedOffMatchedTools = latestSignedOff?.matchedToolNames.orEmpty(),
        latestSignedOffSessionHistorySessionIds = latestSignedOff?.matchedSessionHistorySessionIds.orEmpty(),
        latestSignedOffSessionHistoryMessageReferences =
            latestSignedOff?.matchedSessionHistoryMessageReferences.orEmpty()
    )
}

internal fun buildFinalReadinessRetryReminder(
    receipt: FinalReadinessReceipt,
    language: FinalReadinessLanguage
): String {
    return when (language) {
        FinalReadinessLanguage.CHINESE -> buildString {
            append("Final Readiness Gate:\n")
            append(receipt.message)
            append('\n')
            append(
                when (receipt.requiredAction) {
                    FinalReadinessRequiredAction.SIGN_OFF_WITH_EVIDENCE ->
                        "不要直接结束或只给自然语言结论。 如果这次修改已经完成，请先调用 `complete_step`，并引用真实工具证据后再继续。 如果还缺验证或检查，请先调用相关工具，再决定是否签收。"
                    FinalReadinessRequiredAction.COMPLETE_REMAINING_PLAN ->
                        "不要直接结束或只给自然语言结论。 请先继续完成剩余计划步骤；如果其中某一步已经实际完成，请调用 `complete_step` 做证据签收后再继续。"
                }
            )
        }
        FinalReadinessLanguage.ENGLISH -> buildString {
            append("Final Readiness Gate:\n")
            append(receipt.message)
            append('\n')
            append(
                when (receipt.requiredAction) {
                    FinalReadinessRequiredAction.SIGN_OFF_WITH_EVIDENCE ->
                        "Do not finish with prose only. If the change is complete, call `complete_step` with real tool evidence before concluding. If verification is still missing, run the necessary tools first and then decide whether to sign off."
                    FinalReadinessRequiredAction.COMPLETE_REMAINING_PLAN ->
                        "Do not finish with prose only. Continue the remaining plan steps first; if a step is already complete, call `complete_step` with real evidence before concluding."
                }
            )
        }
    }
}

internal fun buildFinalReadinessBlockedMessage(
    receipt: FinalReadinessReceipt,
    language: FinalReadinessLanguage
): String {
    return when (language) {
        FinalReadinessLanguage.CHINESE ->
            "${receipt.message} 已经提醒过一次，但模型仍试图在未满足最终收口条件时结束。本轮已停止，请先补齐对应签收或推进剩余计划。"
        FinalReadinessLanguage.ENGLISH ->
            "${receipt.message} The model was reminded once but still tried to finish before final readiness passed. " +
                "This run has been stopped; complete the missing sign-off or remaining plan work first."
    }
}

internal fun buildFinalReadinessBlockedUserVisibleMessage(
    language: FinalReadinessLanguage
): String {
    return when (language) {
        FinalReadinessLanguage.CHINESE ->
            "当前执行已暂停：内部收口校验未通过，模型需要先补齐证据签收或继续剩余步骤后再结束。详细原因已记录到工具审计。"
        FinalReadinessLanguage.ENGLISH ->
            "Execution paused: internal final-readiness checks did not pass. The model must complete sign-off with evidence or finish the remaining steps before concluding. Detailed diagnostics were recorded in the audit view."
    }
}

internal fun buildFinalReadinessAuditRecord(
    receipt: FinalReadinessReceipt,
    result: FinalReadinessAuditResult,
    recovered: Boolean
): FinalReadinessAuditRecord {
    return FinalReadinessAuditRecord(
        result = result,
        recovered = recovered,
        receiptKind = receipt.kind,
        requiredAction = receipt.requiredAction,
        latestSuccessfulWriteToolName = receipt.latestSuccessfulWriteToolName,
        remainingUnsignedSteps = receipt.remainingUnsignedSteps,
        nextRequiredStep = receipt.nextRequiredStep,
        latestSignedOffStep = receipt.latestSignedOffStep,
        latestSignedOffMatchedTools = receipt.latestSignedOffMatchedTools,
        latestSignedOffSessionHistorySessionIds = receipt.latestSignedOffSessionHistorySessionIds,
        latestSignedOffSessionHistoryMessageReferences =
            receipt.latestSignedOffSessionHistoryMessageReferences
    )
}

fun FinalReadinessAuditRecord.toReasonSummary(): String {
    return when (receiptKind) {
        FinalReadinessReceiptKind.MISSING_COMPLETE_STEP_AFTER_WRITE -> {
            latestSuccessfulWriteToolName?.let { "缺少 `$it` 对应的 complete_step 签收" }
                ?: "缺少 complete_step 签收"
        }
        FinalReadinessReceiptKind.INCOMPLETE_CANONICAL_WORKFLOW -> {
            remainingUnsignedSteps?.let { "计划仍有 $it 个未签收步骤" }
                ?: "计划仍有未签收步骤"
        }
    }
}

fun FinalReadinessAuditRecord.toStatusSummary(): String {
    val reason = toReasonSummary()
    return when (result) {
        FinalReadinessAuditResult.BLOCKED -> "仍阻塞（$reason）"
        FinalReadinessAuditResult.ALLOWED ->
            if (recovered) {
                "提醒后已恢复放行（$reason）"
            } else {
                "允许结束（$reason）"
            }
    }
}

fun buildFinalReadinessAuditOverview(
    audits: List<FinalReadinessAuditRecord>
): FinalReadinessAuditOverview? {
    val latestAudit = audits.firstOrNull() ?: return null
    return FinalReadinessAuditOverview(
        totalCount = audits.size,
        blockedCount = audits.count { it.result == FinalReadinessAuditResult.BLOCKED },
        allowedCount = audits.count { it.result == FinalReadinessAuditResult.ALLOWED },
        recoveredCount = audits.count { it.recovered },
        writeSignOffBlockCount = audits.count {
            it.receiptKind == FinalReadinessReceiptKind.MISSING_COMPLETE_STEP_AFTER_WRITE
        },
        canonicalWorkflowBlockCount = audits.count {
            it.receiptKind == FinalReadinessReceiptKind.INCOMPLETE_CANONICAL_WORKFLOW
        },
        latestStatusSummary = latestAudit.toStatusSummary(),
        latestReasonSummary = latestAudit.toReasonSummary(),
        currentlyBlocked = latestAudit.result == FinalReadinessAuditResult.BLOCKED
    )
}

internal fun buildLatestFinalReadinessHistoryReferenceSummary(
    audits: List<FinalReadinessAuditRecord>
): String? {
    val latestAudit = audits.firstOrNull()
        ?.takeIf { audit -> audit.result == FinalReadinessAuditResult.BLOCKED || audit.recovered }
        ?: return null
    val sessionIds = latestAudit.latestSignedOffSessionHistorySessionIds.distinct()
    val messageReferences = latestAudit.latestSignedOffSessionHistoryMessageReferences.distinct()
    if (sessionIds.isEmpty() && messageReferences.isEmpty()) return null
    return buildString {
        if (sessionIds.isNotEmpty()) {
            append("最近签收引用过历史会话：")
            append(sessionIds.toPreviewList())
        }
        if (messageReferences.isNotEmpty()) {
            if (isNotEmpty()) append("；")
            append("最近签收引用过历史消息：")
            append(messageReferences.toPreviewList())
        }
        append("。")
    }
}

private fun List<String>.toPreviewList(limit: Int = 3): String {
    val preview = take(limit)
    val remainingCount = (size - preview.size).coerceAtLeast(0)
    return buildString {
        append(preview.joinToString("、"))
        if (remainingCount > 0) {
            append(" 等 ")
            append(size)
            append(" 条")
        }
    }
}

internal fun buildFinalReadinessAuditExportLines(
    audits: List<FinalReadinessAuditRecord>
): List<String> {
    val overview = buildFinalReadinessAuditOverview(audits) ?: return emptyList()
    return buildList {
        add("- 最终收口审计数: ${overview.totalCount}")
        add("- 最终收口拦截数: ${overview.blockedCount}")
        if (overview.allowedCount > 0) {
            add("- 最终收口允许结束数: ${overview.allowedCount}")
        }
        if (overview.recoveredCount > 0) {
            add("- 最终收口恢复数: ${overview.recoveredCount}")
        }
        if (overview.writeSignOffBlockCount > 0) {
            add("- 写后待签收阻塞数: ${overview.writeSignOffBlockCount}")
        }
        if (overview.canonicalWorkflowBlockCount > 0) {
            add("- 计划未收口阻塞数: ${overview.canonicalWorkflowBlockCount}")
        }
        add("- 最近收口状态: ${overview.latestStatusSummary}")
    }
}

internal fun buildLatestFinalReadinessAuditSummary(
    audits: List<FinalReadinessAuditRecord>
): String? {
    return buildLatestFinalReadinessSessionTelemetry(audits)?.statusSummary
}

internal fun buildLatestFinalReadinessSessionTelemetry(
    audits: List<FinalReadinessAuditRecord>
): FinalReadinessSessionTelemetry? {
    val latestAudit = audits.firstOrNull()
        ?.takeIf { audit -> audit.result == FinalReadinessAuditResult.BLOCKED || audit.recovered }
        ?: return null
    return FinalReadinessSessionTelemetry(
        statusKind = when {
            latestAudit.result == FinalReadinessAuditResult.BLOCKED ->
                FinalReadinessSessionStatusKind.BLOCKED
            latestAudit.recovered ->
                FinalReadinessSessionStatusKind.RECOVERED
            else -> FinalReadinessSessionStatusKind.NONE
        },
        statusSummary = latestAudit.toStatusSummary(),
        reasonSummary = latestAudit.toReasonSummary()
    )
}
