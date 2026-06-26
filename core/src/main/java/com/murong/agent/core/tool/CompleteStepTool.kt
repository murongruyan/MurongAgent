package com.murong.agent.core.tool

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.max

data class WorkflowExecutionSnapshot(
    val steps: List<String> = emptyList(),
    val currentStepIndex: Int = 0,
    val status: String = ""
)

data class WorkflowStepCompletion(
    val matchedStepIndex: Int,
    val matchedStep: String,
    val reportedStep: String,
    val resultSummary: String,
    val matchedEvidenceCount: Int,
    val totalEvidenceCount: Int,
    val matchedToolNames: List<String> = emptyList(),
    val matchedSessionHistorySessionIds: List<String> = emptyList(),
    val matchedSessionHistoryMessageReferences: List<String> = emptyList(),
    val signedOffAt: Long = System.currentTimeMillis()
)

class CompleteStepTool(
    private val recentToolReceiptsProvider: () -> List<ToolExecutionReceipt> = { emptyList() },
    private val workflowSnapshotProvider: () -> WorkflowExecutionSnapshot? = { null },
    private val onWorkflowStepCompleted: ((WorkflowStepCompletion) -> Unit)? = null
) : Tool {

    override val name: String = "complete_step"
    override val description: String =
        "在声称某个执行步骤已经完成前，基于本轮或最近的真实工具收据做证据签收。适合在完成计划步骤、跑完命令或完成关键修改后调用。"
    override val parameters: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "step" to mapOf(
                "type" to "string",
                "description" to "刚刚完成的步骤名称或简要描述。若当前存在执行计划，建议与计划步骤文本保持一致。"
            ),
            "result" to mapOf(
                "type" to "string",
                "description" to "这一步完成后的结果总结。"
            ),
            "evidence" to mapOf(
                "type" to "array",
                "description" to "支撑这一步完成的证据列表。每条证据至少填写 summary，并尽量带上 toolName/command/path/message_reference/session_id 之一。",
                "items" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "summary" to mapOf(
                            "type" to "string",
                            "description" to "证据摘要，例如“已运行 go test ./... 并通过”。"
                        ),
                        "toolName" to mapOf(
                            "type" to "string",
                            "description" to "可选。对应的工具名，例如 shell、task_repo_search_replace、file。"
                        ),
                        "command" to mapOf(
                            "type" to "string",
                            "description" to "可选。若证据来自 shell，可填写关键命令片段。"
                        ),
                        "path" to mapOf(
                            "type" to "string",
                            "description" to "可选。若证据关联文件路径，可填写关键路径片段。"
                        ),
                        "message_reference" to mapOf(
                            "type" to "string",
                            "description" to "可选。若证据来自 session_history_search，可填写稳定历史消息引用 session_id#message_id。"
                        ),
                        "session_id" to mapOf(
                            "type" to "string",
                            "description" to "可选。若证据来自 session_history_search，可填写命中的历史 session_id。"
                        ),
                        "anchor_message_id" to mapOf(
                            "type" to "integer",
                            "description" to "可选。若证据来自 session_history_search，可填写命中的 anchor_message_id。"
                        ),
                        "mustSucceed" to mapOf(
                            "type" to "boolean",
                            "description" to "可选。默认 true，要求匹配到的工具收据是成功结果。"
                        )
                    ),
                    "required" to listOf("summary")
                )
            )
        ),
        "required" to listOf("step", "result", "evidence")
    )

    override suspend fun execute(args: String): String {
        return executeWithContext(args, ToolRuntimeContext()).output
    }

    override suspend fun executeWithContext(
        args: String,
        runtimeContext: ToolRuntimeContext
    ): ToolExecutionResult {
        return try {
            val payload = json.parseToJsonElement(args).jsonObject
            val step = payload.string("step")
            val result = payload.string("result")
            val evidence = parseEvidence(payload["evidence"])

            if (step.isBlank()) {
                return ToolExecutionResult("Error: `step` 不能为空。")
            }
            if (result.isBlank()) {
                return ToolExecutionResult("Error: `result` 不能为空。")
            }
            if (evidence.isEmpty()) {
                return ToolExecutionResult("Error: `evidence` 至少要提供一条真实工具证据。")
            }

            val receipts = (runtimeContext.currentTurnToolReceipts + recentToolReceiptsProvider())
                .distinctBy { listOf(it.toolName, it.args, it.result, it.timestamp) }
            if (receipts.isEmpty()) {
                return ToolExecutionResult("Error: 当前没有可供签收的工具收据。请先执行实际工具，再调用 `complete_step`。")
            }

            val remainingReceipts = receipts.toMutableList()
            val matchedReceipts = mutableListOf<ToolExecutionReceipt>()
            val failedEvidence = mutableListOf<String>()
            evidence.forEachIndexed { index, item ->
                val matchIndex = remainingReceipts.indices
                    .filter { candidateIndex ->
                        evidenceMatchesReceipt(item, remainingReceipts[candidateIndex])
                    }
                    .maxWithOrNull(compareBy<Int> { remainingReceipts[it].timestamp }.thenBy { it })
                if (matchIndex != null) {
                    matchedReceipts += remainingReceipts.removeAt(matchIndex)
                } else {
                    failedEvidence += buildEvidenceFailureLabel(index, item)
                }
            }

            if (failedEvidence.isNotEmpty()) {
                return ToolExecutionResult(
                    buildString {
                        append("Error: 以下证据没有匹配到真实工具收据：")
                        append('\n')
                        failedEvidence.forEach { append("- ").append(it).append('\n') }
                        append('\n')
                        append("最近可用工具收据：")
                        append('\n')
                        receipts.takeLast(6).asReversed().forEach { receipt ->
                            append("- ")
                            append(receipt.toolName)
                            append(": ")
                            append(buildReceiptPreview(receipt))
                            append('\n')
                        }
                    }.trimEnd()
                )
            }

            val signOffTimestamp = matchedReceipts.maxOfOrNull(ToolExecutionReceipt::timestamp)
                ?: System.currentTimeMillis()
            val workflowMatch = resolveWorkflowMatch(step, workflowSnapshotProvider())
            val matchedToolNames = matchedReceipts.map(ToolExecutionReceipt::toolName).distinct()
            val matchedSessionHistorySessionIds = matchedReceipts
                .mapNotNull { it.structuredPayload?.sessionHistory }
                .flatMap { it.sessionIds }
                .distinct()
            val matchedSessionHistoryMessageReferences = matchedReceipts
                .mapNotNull { it.structuredPayload?.sessionHistory }
                .flatMap { it.messageReferences }
                .distinct()
            workflowMatch?.let { match ->
                onWorkflowStepCompleted?.invoke(
                    WorkflowStepCompletion(
                        matchedStepIndex = match.index,
                        matchedStep = match.step,
                        reportedStep = step,
                        resultSummary = result,
                        matchedEvidenceCount = matchedReceipts.size,
                        totalEvidenceCount = evidence.size,
                        matchedToolNames = matchedToolNames,
                        matchedSessionHistorySessionIds = matchedSessionHistorySessionIds,
                        matchedSessionHistoryMessageReferences = matchedSessionHistoryMessageReferences,
                        signedOffAt = signOffTimestamp
                    )
                )
            }

            return ToolExecutionResult(
                output = buildString {
                    append("Step signed off.")
                    append('\n')
                    append("step=")
                    append(step)
                    append('\n')
                    append("result=")
                    append(result)
                    append('\n')
                    append("matched_evidence=")
                    append(matchedReceipts.size)
                    append('/')
                    append(evidence.size)
                    append('\n')
                    append("matched_tools=")
                    append(matchedToolNames.joinToString(","))
                    append('\n')
                    append("signoff_timestamp=")
                    append(signOffTimestamp)
                    workflowMatch?.let { match ->
                        append('\n')
                        append("workflow_step=")
                        append(match.index + 1)
                        append('/')
                        append(match.totalSteps)
                        append(" -> ")
                        append(match.step)
                    }
                },
                stepSignOffReceipt = StepSignOffReceipt(
                    reportedStep = step,
                    resultSummary = result,
                    matchedEvidenceCount = matchedReceipts.size,
                    totalEvidenceCount = evidence.size,
                    matchedToolNames = matchedToolNames,
                    matchedSessionHistorySessionIds = matchedSessionHistorySessionIds,
                    matchedSessionHistoryMessageReferences = matchedSessionHistoryMessageReferences,
                    signOffTimestamp = signOffTimestamp,
                    workflowStepIndex = workflowMatch?.index,
                    workflowStep = workflowMatch?.step,
                    workflowTotalSteps = workflowMatch?.totalSteps
                )
            )
        } catch (e: Exception) {
            ToolExecutionResult("Error executing `complete_step`: ${e.message}")
        }
    }

    private fun evidenceMatchesReceipt(
        evidence: CompleteStepEvidence,
        receipt: ToolExecutionReceipt
    ): Boolean {
        if (evidence.mustSucceed && !receipt.isSuccess) return false
        if (evidence.toolName != null && !receipt.toolName.equals(evidence.toolName, ignoreCase = true)) {
            return false
        }
        if (evidence.command != null && !receipt.args.contains(evidence.command, ignoreCase = true) &&
            !receipt.result.orEmpty().contains(evidence.command, ignoreCase = true)
        ) {
            return false
        }
        if (evidence.path != null && !receipt.args.contains(evidence.path, ignoreCase = true) &&
            !receipt.result.orEmpty().contains(evidence.path, ignoreCase = true)
        ) {
            return false
        }
        val sessionHistoryPayload = receipt.structuredPayload?.sessionHistory
        if (evidence.messageReference != null &&
            sessionHistoryPayload?.messageReferences.orEmpty().none {
                it.equals(evidence.messageReference, ignoreCase = true)
            }
        ) {
            return false
        }
        if (evidence.sessionId != null &&
            sessionHistoryPayload?.sessionIds.orEmpty().none {
                it.equals(evidence.sessionId, ignoreCase = true)
            }
        ) {
            return false
        }
        if (evidence.anchorMessageId != null &&
            !sessionHistoryPayload?.anchorMessageIds.orEmpty().contains(evidence.anchorMessageId)
        ) {
            return false
        }
        return evidence.toolName != null ||
            evidence.command != null ||
            evidence.path != null ||
            evidence.messageReference != null ||
            evidence.sessionId != null ||
            evidence.anchorMessageId != null
    }

    private fun buildEvidenceFailureLabel(index: Int, evidence: CompleteStepEvidence): String {
        val parts = buildList {
            add("evidence#${index + 1}")
            add(evidence.summary)
            evidence.toolName?.let { add("tool=$it") }
            evidence.command?.let { add("command=$it") }
            evidence.path?.let { add("path=$it") }
            evidence.messageReference?.let { add("message_reference=$it") }
            evidence.sessionId?.let { add("session_id=$it") }
            evidence.anchorMessageId?.let { add("anchor_message_id=$it") }
        }
        return parts.joinToString(" | ")
    }

    private fun buildReceiptPreview(receipt: ToolExecutionReceipt): String {
        val commandPreview = receipt.args.lineSequence().firstOrNull().orEmpty().take(120)
        val resultPreview = receipt.result.orEmpty().lineSequence().firstOrNull().orEmpty().take(120)
        return buildString {
            append(commandPreview.ifBlank { "(no args)" })
            if (resultPreview.isNotBlank()) {
                append(" => ")
                append(resultPreview)
            }
        }
    }

    private fun resolveWorkflowMatch(
        step: String,
        snapshot: WorkflowExecutionSnapshot?
    ): WorkflowMatch? {
        snapshot ?: return null
        val normalizedSteps = snapshot.steps.filter { it.isNotBlank() }
        if (normalizedSteps.isEmpty()) return null
        val normalizedReported = normalize(step)
        val startIndex = snapshot.currentStepIndex.coerceIn(0, max(normalizedSteps.size - 1, 0))
        val candidateIndices = (startIndex until normalizedSteps.size) + (0 until startIndex)
        val exactMatchIndex = candidateIndices.firstOrNull { index ->
            normalize(normalizedSteps[index]) == normalizedReported
        }
        val matchedIndex = exactMatchIndex ?: candidateIndices
            .filter { index ->
                val candidate = normalize(normalizedSteps[index])
                candidate.contains(normalizedReported) || normalizedReported.contains(candidate)
            }
            .singleOrNull()
            ?: return null
        return WorkflowMatch(
            index = matchedIndex,
            step = normalizedSteps[matchedIndex],
            totalSteps = normalizedSteps.size
        )
    }

    private fun normalize(value: String): String {
        return value
            .lowercase()
            .replace(Regex("[\\s`*_#>:\\-.,，。；;（）()\\[\\]{}]+"), "")
    }

    private fun parseEvidence(element: kotlinx.serialization.json.JsonElement?): List<CompleteStepEvidence> {
        val array = element as? JsonArray ?: return emptyList()
        return array.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            CompleteStepEvidence(
                summary = obj.string("summary"),
                toolName = obj.optionalString("toolName", "tool_name"),
                command = obj.optionalString("command"),
                path = obj.optionalString("path"),
                messageReference = obj.optionalString("messageReference", "message_reference"),
                sessionId = obj.optionalString("sessionId", "session_id"),
                anchorMessageId = obj.optionalLong("anchorMessageId", "anchor_message_id"),
                mustSucceed = obj.optionalBoolean("mustSucceed", "must_succeed") ?: true
            )
        }
    }

    private fun JsonObject.string(key: String): String {
        return this[key]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    }

    private fun JsonObject.optionalString(vararg keys: String): String? {
        return keys.asSequence()
            .mapNotNull { key ->
                this[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
            }
            .firstOrNull()
    }

    private fun JsonObject.optionalLong(vararg keys: String): Long? {
        return keys.asSequence()
            .mapNotNull { key ->
                this[key]?.jsonPrimitive?.contentOrNull?.trim()?.toLongOrNull()
            }
            .firstOrNull()
    }

    private fun JsonObject.optionalBoolean(vararg keys: String): Boolean? {
        return keys.asSequence()
            .mapNotNull { key ->
                this[key]?.jsonPrimitive?.booleanOrNull
            }
            .firstOrNull()
    }

    private data class CompleteStepEvidence(
        val summary: String,
        val toolName: String?,
        val command: String?,
        val path: String?,
        val messageReference: String?,
        val sessionId: String?,
        val anchorMessageId: Long?,
        val mustSucceed: Boolean
    )

    private data class WorkflowMatch(
        val index: Int,
        val step: String,
        val totalSteps: Int
    )

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
