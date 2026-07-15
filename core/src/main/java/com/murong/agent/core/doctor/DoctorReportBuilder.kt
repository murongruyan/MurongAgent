package com.murong.agent.core.doctor

import com.murong.agent.core.config.ProviderConfig
import com.murong.agent.core.loop.FinalReadinessAuditRecord
import com.murong.agent.core.loop.FinalReadinessAuditResult
import com.murong.agent.core.loop.FinalReadinessReceiptKind
import com.murong.agent.core.loop.FinalReadinessRequiredAction
import com.murong.agent.core.loop.PersistedSession
import com.murong.agent.core.loop.PersistedFinalReadinessAuditRecord
import com.murong.agent.core.loop.buildFinalReadinessAuditOverview
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun buildDoctorReport(
    session: PersistedSession,
    config: ProviderConfig,
    pendingCrash: PendingCrashReport? = null,
    generatedAt: Long = System.currentTimeMillis()
): String {
    val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        .format(Date(generatedAt))
    val recentErrorPreview = session.recentErrors
        .takeLast(5)
        .map { error -> "- [${error.kind}] ${error.message}" }
        .ifEmpty { listOf("- none") }
    val enabledGlobalRuleCount = config.globalRules.count { it.enabled }
    val enabledGlobalMemoryCount = config.globalMemories.count { it.enabled }
    val enabledGlobalSkillCount = config.globalSkills.count { it.enabled }
    val finalReadinessAudits = restoreDoctorFinalReadinessAudits(session.recentFinalReadinessAudits)
    val finalReadinessOverview = buildFinalReadinessAuditOverview(finalReadinessAudits)
    val latestRecovery = session.recentRecoveryRecords.firstOrNull()
    val latestCrashStackPreview = pendingCrash?.stackTrace
        ?.lineSequence()
        ?.map(String::trim)
        ?.filter(String::isNotBlank)
        ?.take(3)
        ?.joinToString(" | ")
        ?.ifBlank { "none" }
        ?: "none"

    val report = buildString {
        appendLine("# Doctor Report")
        appendLine()
        appendLine("## Export")
        appendLine("- generated_at: $timestamp")
        appendLine("- session_title: ${session.title.ifBlank { "新对话" }}")
        appendLine("- session_created_at: ${formatDoctorTimestamp(session.createdAt)}")
        appendLine("- session_updated_at: ${formatDoctorTimestamp(session.updatedAt)}")
        appendLine()
        appendLine("## Runtime Summary")
        appendLine("- provider_id: ${session.providerId}")
        appendLine("- model_name: ${session.modelName}")
        appendLine("- session_goal_present: ${session.sessionGoal?.isNotBlank() == true}")
        appendLine("- project_attached: ${session.projectPath?.isNotBlank() == true}")
        appendLine("- active_project_scope_present: ${session.activeProjectScopePath?.isNotBlank() == true}")
        appendLine("- message_count: ${session.messages.size}")
        appendLine("- tool_call_count: ${session.recentToolCalls.size}")
        appendLine("- recent_error_count: ${session.recentErrors.size}")
        appendLine("- checkpoint_count: ${session.checkpoints.size}")
        appendLine("- file_change_count: ${session.fileChanges.size}")
        appendLine("- compression_snapshot_count: ${session.compressionSnapshots.size}")
        appendLine("- background_job_count: ${session.backgroundJobs.size}")
        appendLine("- subagent_run_count: ${session.subagentRuns.size}")
        appendLine("- subagent_batch_count: ${session.subagentBatches.size}")
        appendLine()
        appendLine("## Runtime Diagnostics")
        appendLine("- recent_memory_update_suggestion_count: ${session.recentMemoryUpdateSuggestions.size}")
        appendLine("- recent_approval_count: ${session.recentApprovals.size}")
        appendLine("- recent_approval_invalidation_count: ${session.recentApprovalInvalidations.size}")
        appendLine("- approved_scope_entry_count: ${session.approvedApprovalScopeEntries.size}")
        appendLine("- final_readiness_audit_count: ${session.recentFinalReadinessAudits.size}")
        appendLine("- recovery_record_count: ${session.recentRecoveryRecords.size}")
        appendLine("- latest_final_readiness_status: ${finalReadinessOverview?.latestStatusSummary ?: "none"}")
        appendLine("- latest_final_readiness_reason: ${finalReadinessOverview?.latestReasonSummary ?: "none"}")
        appendLine("- latest_final_readiness_currently_blocked: ${finalReadinessOverview?.currentlyBlocked ?: false}")
        appendLine("- latest_final_readiness_history_reference_count: ${finalReadinessAudits.firstOrNull()?.latestSignedOffSessionHistoryMessageReferences?.size ?: 0}")
        appendLine("- latest_final_readiness_session_reference_count: ${finalReadinessAudits.firstOrNull()?.latestSignedOffSessionHistorySessionIds?.size ?: 0}")
        appendLine()
        appendLine("## Restart Context")
        appendLine("- latest_recovery_present: ${latestRecovery != null}")
        latestRecovery?.let { recovery ->
            appendLine("- latest_recovery_timestamp: ${formatDoctorTimestamp(recovery.timestamp)}")
            appendLine("- latest_recovery_scope: ${recovery.scope}")
            appendLine("- latest_recovery_checkpoint: ${recovery.checkpointId}")
            appendLine("- latest_recovery_summary: ${recovery.checkpointSummary}")
            appendLine("- latest_recovery_restored_file_count: ${recovery.restoredFileCount}")
            appendLine("- latest_recovery_target_message_index: ${recovery.targetMessageIndex}")
        }
        appendLine("- recent_recovery_preview_count: ${session.recentRecoveryRecords.take(3).size}")
        session.recentRecoveryRecords.take(3).forEachIndexed { index, recovery ->
            appendLine(
                "- recovery_preview_${index + 1}: ${recovery.scope} · ${recovery.checkpointSummary} · files=${recovery.restoredFileCount}"
            )
        }
        appendLine()
        appendLine("## Project Summary")
        appendLine("- project_rule_count: ${session.projectRules.size}")
        appendLine("- project_memory_count: ${session.projectMemories.size}")
        appendLine("- project_skill_count: ${session.projectSkills.size}")
        appendLine("- repo_scoped_config_count: ${session.repoScopedConfigs.size}")
        appendLine("- knowledge_path_count: ${session.projectKnowledgePaths.size}")
        appendLine("- knowledge_snapshot_count: ${session.projectKnowledgeSnapshots.size}")
        appendLine()
        appendLine("## Config Summary")
        appendLine("- active_provider_id: ${config.activeProviderId}")
        appendLine("- active_model: ${config.getActiveModel()}")
        appendLine("- active_reasoning_effort: ${config.getActiveReasoningEffort().orEmpty()}")
        appendLine("- approval_mode: ${config.approvalMode.name}")
        appendLine("- workflow_execution_mode: ${config.workflowExecutionMode.name}")
        appendLine("- auto_route_before_execution: ${config.autoRouteBeforeExecution}")
        appendLine("- streaming_enabled: ${config.isStreamingResponsesEnabled()}")
        appendLine("- multimodal_enabled: ${config.isMultimodalEnabled()}")
        appendLine("- global_rule_count_enabled: $enabledGlobalRuleCount")
        appendLine("- global_memory_count_enabled: $enabledGlobalMemoryCount")
        appendLine("- global_skill_count_enabled: $enabledGlobalSkillCount")
        appendLine("- project_tool_preferences_present: ${config.projectToolPreferences != null}")
        appendLine("- builtin_tool_count: ${config.enabledBuiltinTools.size}")
        appendLine("- file_tool_operation_count: ${config.enabledFileToolOperations.size}")
        appendLine("- allow_all_mcp_tools: ${config.allowAllMcpTools}")
        appendLine("- allowed_mcp_tool_count: ${config.allowedMcpTools.size}")
        appendLine("- allowed_shell_prefix_count: ${config.allowedShellCommandPrefixes.size}")
        appendLine("- allowed_path_prefix_count: ${config.allowedPathPrefixes.size}")
        appendLine("- github_signed_in: ${config.isGitHubSignedIn()}")
        appendLine("- github_backend_session_present: ${config.githubBackendSessionToken.isNotBlank()}")
        appendLine("- github_token_present: ${config.githubToken.isNotBlank()}")
        appendLine("- usage_api_configured: ${config.getMurongUsageApiUrl().isNotBlank()}")
        appendLine()
        appendLine("## Pending State")
        appendLine("- pending_approval: ${session.pendingApproval != null}")
        appendLine("- pending_ask_request: ${session.pendingAskRequest != null}")
        appendLine("- pending_workflow_plan: ${session.pendingWorkflowPlan != null}")
        appendLine("- canonical_workflow_plan: ${session.canonicalWorkflowPlan != null}")
        appendLine("- pending_clarification_request: ${session.pendingClarificationRequest != null}")
        appendLine("- last_auto_route_decision: ${session.lastAutoRouteDecision != null}")
        appendLine("- last_workflow_fallback: ${session.lastWorkflowFallback != null}")
        appendLine("- final_readiness_receipt: ${session.lastFinalReadinessReceipt != null}")
        appendLine()
        appendLine("## Pending Crash")
        appendLine("- pending_crash_present: ${pendingCrash != null}")
        pendingCrash?.let { crash ->
            appendLine("- pending_crash_timestamp: ${formatDoctorTimestamp(crash.timestamp)}")
            appendLine("- pending_crash_thread: ${crash.threadName}")
            appendLine("- pending_crash_exception: ${crash.exceptionType}")
            appendLine("- pending_crash_message: ${crash.message.ifBlank { "none" }}")
            appendLine("- pending_crash_fatal: ${crash.fatal}")
            appendLine("- pending_crash_stack_preview: $latestCrashStackPreview")
        }
        appendLine()
        appendLine("## Recent Errors")
        recentErrorPreview.forEach(::appendLine)
    }

    return SensitiveDataSanitizer.sanitizeText(report.trim())
}

private fun formatDoctorTimestamp(timestamp: Long): String {
    return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        .format(Date(timestamp))
}

private fun restoreDoctorFinalReadinessAudits(
    persisted: List<PersistedFinalReadinessAuditRecord>
): List<FinalReadinessAuditRecord> {
    return persisted.map { record ->
        FinalReadinessAuditRecord(
            result = FinalReadinessAuditResult.valueOf(record.result),
            recovered = record.recovered,
            receiptKind = FinalReadinessReceiptKind.valueOf(record.receiptKind),
            requiredAction = FinalReadinessRequiredAction.valueOf(record.requiredAction),
            latestSuccessfulWriteToolName = record.latestSuccessfulWriteToolName,
            remainingUnsignedSteps = record.remainingUnsignedSteps,
            nextRequiredStep = record.nextRequiredStep,
            latestSignedOffStep = record.latestSignedOffStep,
            latestSignedOffMatchedTools = record.latestSignedOffMatchedTools,
            latestSignedOffSessionHistorySessionIds = record.latestSignedOffSessionHistorySessionIds,
            latestSignedOffSessionHistoryMessageReferences =
                record.latestSignedOffSessionHistoryMessageReferences
        )
    }
}
