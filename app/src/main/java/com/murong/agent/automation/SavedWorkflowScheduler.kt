package com.murong.agent.automation

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.murong.agent.core.automation.SavedWorkflowBackgroundEligibility
import com.murong.agent.core.automation.SavedWorkflowDefinition
import com.murong.agent.core.automation.SavedWorkflowRunRecord
import com.murong.agent.core.automation.SavedWorkflowRunStatus
import com.murong.agent.core.automation.SavedWorkflowTemplate
import com.murong.agent.core.automation.backgroundEligibility
import com.murong.agent.core.automation.validate
import com.murong.agent.core.config.ConfigRepository
import com.murong.agent.core.doctor.SensitiveDataSanitizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.TimeUnit

data class SavedWorkflowScheduleOutcome(
    val workflow: SavedWorkflowDefinition,
    val message: String,
    val scheduled: Boolean
)

/** Small file-backed store. The domain definition is serialized, but execution output is redacted. */
class SavedWorkflowStore internal constructor(private val file: File) {
    constructor(context: Context) : this(File(context.applicationContext.filesDir, "saved_workflows.json"))

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    fun list(): List<SavedWorkflowDefinition> = synchronized(FILE_LOCK) {
        if (!file.exists()) {
            emptyList()
        } else {
            runCatching {
                json.decodeFromString(ListSerializer(SavedWorkflowDefinition.serializer()), file.readText())
            }.getOrDefault(emptyList()).sortedByDescending { it.updatedAt }
        }
    }

    fun upsert(workflow: SavedWorkflowDefinition): SavedWorkflowDefinition = synchronized(FILE_LOCK) {
        val saved = workflow.copy(updatedAt = System.currentTimeMillis())
        val updated = list().filterNot { it.id == saved.id } + saved
        write(updated)
        saved
    }

    fun delete(id: String) = synchronized(FILE_LOCK) {
        write(list().filterNot { it.id == id })
    }

    fun replaceAll(workflows: List<SavedWorkflowDefinition>) = synchronized(FILE_LOCK) {
        require(workflows.map { it.id }.distinct().size == workflows.size) {
            "恢复的工作流包含重复 ID"
        }
        write(workflows)
    }

    fun updateRun(id: String, record: SavedWorkflowRunRecord): SavedWorkflowDefinition? = synchronized(FILE_LOCK) {
        val current = list()
        val target = current.firstOrNull { it.id == id } ?: return@synchronized null
        val updated = target.copy(lastRun = record, updatedAt = System.currentTimeMillis())
        write(current.filterNot { it.id == id } + updated)
        updated
    }

    /** A process death cannot leave a persisted run looking active forever. */
    fun reconcileInterruptedRuns(nowMillis: Long = System.currentTimeMillis()): List<SavedWorkflowDefinition> =
        synchronized(FILE_LOCK) {
        val current = list()
        var changed = false
        val reconciled = current.map { workflow ->
            val run = workflow.lastRun
            if (run?.status == SavedWorkflowRunStatus.RUNNING) {
                changed = true
                workflow.copy(
                    lastRun = run.copy(
                        status = SavedWorkflowRunStatus.CANCELLED,
                        finishedAt = nowMillis,
                        summary = "应用或系统中断了本次只读工作流。",
                        failureReason = "运行未完成，已在恢复时标记为取消"
                    ),
                    updatedAt = nowMillis
                )
            } else {
                workflow
            }
        }
        if (changed) write(reconciled)
        reconciled.sortedByDescending { it.updatedAt }
    }

    private fun write(workflows: List<SavedWorkflowDefinition>) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(json.encodeToString(ListSerializer(SavedWorkflowDefinition.serializer()), workflows))
        check(temporary.renameTo(file) || runCatching {
            temporary.copyTo(file, overwrite = true)
            temporary.delete()
            true
        }.getOrDefault(false)) { "无法原子保存工作流定义" }
    }

    private companion object {
        val FILE_LOCK = Any()
    }
}

class SavedWorkflowScheduler(
    context: Context,
    reconcileInterruptedRuns: Boolean = true
) {
    private val appContext = context.applicationContext
    private val store = SavedWorkflowStore(appContext)
    private val workManager = WorkManager.getInstance(appContext)

    init {
        if (reconcileInterruptedRuns) store.reconcileInterruptedRuns()
    }

    fun list(): List<SavedWorkflowDefinition> = store.list()

    /**
     * Marks a non-background-safe template as running only after the foreground UI has shown its
     * scope and the user has explicitly confirmed this individual execution.
     */
    fun beginForegroundRun(id: String): SavedWorkflowDefinition? {
        val workflow = store.list().firstOrNull { it.id == id } ?: return null
        val validation = workflow.validate()
        if (!validation.isValid) {
            store.updateRun(
                id,
                SavedWorkflowRunRecord(
                    status = SavedWorkflowRunStatus.BLOCKED,
                    finishedAt = System.currentTimeMillis(),
                    summary = "前台工作流定义无效。",
                    failureReason = validation.errors.joinToString("；")
                )
            )
            return null
        }
        if (workflow.backgroundEligibility() == SavedWorkflowBackgroundEligibility.ALLOWED_READ_ONLY) return null
        val startedAt = System.currentTimeMillis()
        return store.updateRun(
            id,
            SavedWorkflowRunRecord(
                status = SavedWorkflowRunStatus.RUNNING,
                startedAt = startedAt,
                summary = "已在前台确认，正在执行。"
            )
        )
    }

    fun finishForegroundRun(
        id: String,
        startedAt: Long,
        result: Result<String>
    ): SavedWorkflowDefinition? {
        val record = result.fold(
            onSuccess = { summary ->
                SavedWorkflowRunRecord(
                    status = SavedWorkflowRunStatus.SUCCEEDED,
                    startedAt = startedAt,
                    finishedAt = System.currentTimeMillis(),
                    summary = SensitiveDataSanitizer.sanitizeText(summary, redactPaths = true)
                )
            },
            onFailure = { error ->
                SavedWorkflowRunRecord(
                    status = SavedWorkflowRunStatus.FAILED,
                    startedAt = startedAt,
                    finishedAt = System.currentTimeMillis(),
                    summary = "前台工作流执行失败。",
                    failureReason = SensitiveDataSanitizer.sanitizeText(
                        error.message ?: "未知错误",
                        redactPaths = true
                    )
                )
            }
        )
        val updated = store.updateRun(id, record)
        if (record.status == SavedWorkflowRunStatus.FAILED && updated != null) {
            SavedWorkflowFailureNotifier.notify(appContext, updated, record)
        }
        return updated
    }

    fun cancelForegroundRun(id: String, reason: String = "用户取消了前台确认。") {
        store.updateRun(
            id,
            SavedWorkflowRunRecord(
                status = SavedWorkflowRunStatus.CANCELLED,
                finishedAt = System.currentTimeMillis(),
                summary = reason
            )
        )
    }

    fun save(workflow: SavedWorkflowDefinition): SavedWorkflowScheduleOutcome {
        val validation = workflow.validate()
        val saved = store.upsert(workflow)
        if (!validation.isValid) {
            cancel(saved.id)
            return SavedWorkflowScheduleOutcome(saved, validation.errors.joinToString("\n"), false)
        }
        if (!saved.enabled) {
            val cancelled = cancelAndRecord(
                workflow = saved,
                summary = "已停用并取消后续调度。"
            )
            return SavedWorkflowScheduleOutcome(cancelled, cancelled.lastRun?.summary.orEmpty(), false)
        }
        if (saved.backgroundEligibility() != SavedWorkflowBackgroundEligibility.ALLOWED_READ_ONLY) {
            cancel(saved.id)
            val blocked = SavedWorkflowRunRecord(
                status = SavedWorkflowRunStatus.BLOCKED,
                finishedAt = System.currentTimeMillis(),
                summary = "后台只允许固定的项目只读或 GitHub Actions 状态模板。此模板需要在前台单独确认。",
                failureReason = "需要前台确认"
            )
            store.updateRun(saved.id, blocked)
            return SavedWorkflowScheduleOutcome(saved, blocked.summary, false)
        }
        schedulePeriodic(saved)
        return SavedWorkflowScheduleOutcome(saved, "已安排每 ${saved.intervalMinutes} 分钟执行一次只读工作流。", true)
    }

    fun delete(id: String) {
        cancel(id)
        store.delete(id)
    }

    /** Replaces persisted definitions and rebuilds only background-safe periodic schedules. */
    fun restoreAll(workflows: List<SavedWorkflowDefinition>) {
        store.list().forEach { cancel(it.id) }
        store.replaceAll(workflows)
        workflows
            .filter { it.enabled }
            .filter { it.validate().isValid }
            .filter { it.backgroundEligibility() == SavedWorkflowBackgroundEligibility.ALLOWED_READ_ONLY }
            .forEach(::schedulePeriodic)
    }

    fun runNow(id: String): SavedWorkflowScheduleOutcome? {
        val workflow = store.list().firstOrNull { it.id == id } ?: return null
        if (workflow.backgroundEligibility() != SavedWorkflowBackgroundEligibility.ALLOWED_READ_ONLY) {
            val record = SavedWorkflowRunRecord(
                status = SavedWorkflowRunStatus.BLOCKED,
                finishedAt = System.currentTimeMillis(),
                summary = "该工作流需要前台确认，未从后台执行。",
                failureReason = "需要前台确认"
            )
            val updated = store.updateRun(id, record) ?: workflow
            return SavedWorkflowScheduleOutcome(updated, record.summary, false)
        }
        val requestId = UUID.randomUUID().toString()
        enqueueReadOnly(workflow, requestId, null)
        val queued = SavedWorkflowRunRecord(
            status = SavedWorkflowRunStatus.QUEUED,
            summary = "已加入只读执行队列。"
        )
        val updated = store.updateRun(workflow.id, queued) ?: workflow
        return SavedWorkflowScheduleOutcome(updated, queued.summary, true)
    }

    /** Queues an already authenticated external invocation without ever widening the saved scope. */
    fun runExternal(invocation: ExternalSavedWorkflowInvocation): SavedWorkflowScheduleOutcome? {
        val workflow = store.list().firstOrNull { it.id == invocation.workflowId } ?: return null
        val effectiveWorkflow = ExternalWorkflowRequestPolicy
            .applyProjectOverride(workflow, invocation.projectPathOverride)
            .getOrElse { return rejectExternal(workflow.id, it.message ?: "项目范围无效") }
        if (ExternalWorkflowRequestPolicy.requireBackgroundSafe(effectiveWorkflow).isFailure) {
            return rejectExternal(workflow.id, "该工作流需要回到 Murong 前台逐次确认")
        }
        enqueueReadOnly(effectiveWorkflow, invocation.requestId, invocation)
        val record = SavedWorkflowRunRecord(
            status = SavedWorkflowRunStatus.QUEUED,
            summary = "外部只读请求已加入执行队列。"
        )
        val updated = store.updateRun(workflow.id, record) ?: workflow
        return SavedWorkflowScheduleOutcome(updated, record.summary, true)
    }

    fun rejectExternal(workflowId: String, reason: String): SavedWorkflowScheduleOutcome? {
        val workflow = store.list().firstOrNull { it.id == workflowId } ?: return null
        val record = SavedWorkflowRunRecord(
            status = SavedWorkflowRunStatus.BLOCKED,
            finishedAt = System.currentTimeMillis(),
            summary = "外部请求已被安全策略拦截，未执行。",
            failureReason = SensitiveDataSanitizer.sanitizeText(reason, redactPaths = true)
        )
        val updated = store.updateRun(workflowId, record) ?: workflow
        return SavedWorkflowScheduleOutcome(updated, record.summary, false)
    }

    private fun cancel(id: String) {
        workManager.cancelUniqueWork(periodicWorkName(id))
        workManager.cancelAllWorkByTag(workTag(id))
    }

    private fun enqueueReadOnly(
        workflow: SavedWorkflowDefinition,
        requestId: String,
        externalInvocation: ExternalSavedWorkflowInvocation?
    ) {
        val request = androidx.work.OneTimeWorkRequestBuilder<ReadOnlySavedWorkflowWorker>()
            .setInputData(
                workDataOf(
                    WORKFLOW_ID_KEY to workflow.id,
                    REQUEST_ID_KEY to requestId,
                    EXTERNAL_REQUEST_KEY to (externalInvocation != null),
                    PROJECT_PATH_OVERRIDE_KEY to externalInvocation?.projectPathOverride,
                    TASK_TEXT_KEY to externalInvocation?.taskText,
                    CALLBACK_PACKAGE_KEY to externalInvocation?.callbackPackage
                )
            )
            .setConstraints(readOnlyConstraints(workflow))
            .addTag(workTag(workflow.id))
            .build()
        workManager.enqueueUniqueWork(
            oneTimeWorkName(workflow.id, requestId),
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    private fun schedulePeriodic(workflow: SavedWorkflowDefinition) {
        val request = PeriodicWorkRequestBuilder<ReadOnlySavedWorkflowWorker>(
            workflow.intervalMinutes,
            TimeUnit.MINUTES
        )
            .setInputData(workDataOf(WORKFLOW_ID_KEY to workflow.id))
            .setConstraints(readOnlyConstraints(workflow))
            .addTag(workTag(workflow.id))
            .build()
        workManager.enqueueUniquePeriodicWork(
            periodicWorkName(workflow.id),
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    private fun cancelAndRecord(
        workflow: SavedWorkflowDefinition,
        summary: String
    ): SavedWorkflowDefinition {
        cancel(workflow.id)
        val record = SavedWorkflowRunRecord(
            status = SavedWorkflowRunStatus.CANCELLED,
            finishedAt = System.currentTimeMillis(),
            summary = summary,
            failureReason = null
        )
        return store.updateRun(workflow.id, record) ?: workflow.copy(lastRun = record)
    }
}

class ReadOnlySavedWorkflowWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val workflowId = inputData.getString(WORKFLOW_ID_KEY).orEmpty()
        val requestId = inputData.getString(REQUEST_ID_KEY).orEmpty()
        val isExternalRequest = inputData.getBoolean(EXTERNAL_REQUEST_KEY, false)
        val callbackPackage = inputData.getString(CALLBACK_PACKAGE_KEY)
        val projectPathOverride = inputData.getString(PROJECT_PATH_OVERRIDE_KEY)
        val hasTaskText = !inputData.getString(TASK_TEXT_KEY).isNullOrBlank()
        val store = SavedWorkflowStore(applicationContext)
        val savedWorkflow = store.list().firstOrNull { it.id == workflowId }
        if (savedWorkflow == null) {
            dispatchExternalResult(
                isExternalRequest,
                requestId,
                workflowId,
                ExternalWorkflowContract.STATUS_FAILED,
                "保存工作流已不存在。",
                callbackPackage
            )
            return Result.failure()
        }
        val workflow = ExternalWorkflowRequestPolicy.applyProjectOverride(savedWorkflow, projectPathOverride)
            .getOrElse { error ->
                val message = error.message ?: "项目范围无效"
                store.updateRun(
                    savedWorkflow.id,
                    SavedWorkflowRunRecord(
                        status = SavedWorkflowRunStatus.BLOCKED,
                        finishedAt = System.currentTimeMillis(),
                        summary = "后台执行被项目范围策略阻止。",
                        failureReason = SensitiveDataSanitizer.sanitizeText(message, redactPaths = true)
                    )
                )
                dispatchExternalResult(
                    isExternalRequest,
                    requestId,
                    workflowId,
                    ExternalWorkflowContract.STATUS_BLOCKED,
                    message,
                    callbackPackage
                )
                return Result.failure()
            }
        val validation = workflow.validate()
        if (!validation.isValid || workflow.backgroundEligibility() != SavedWorkflowBackgroundEligibility.ALLOWED_READ_ONLY) {
            store.updateRun(
                workflow.id,
                SavedWorkflowRunRecord(
                    status = SavedWorkflowRunStatus.BLOCKED,
                    finishedAt = System.currentTimeMillis(),
                    summary = "后台执行被安全策略阻止。",
                    failureReason = validation.errors.joinToString("；").ifBlank { "需要前台确认" }
                )
            )
            dispatchExternalResult(
                isExternalRequest,
                requestId,
                workflowId,
                ExternalWorkflowContract.STATUS_BLOCKED,
                "后台执行被安全策略阻止。",
                callbackPackage
            )
            return Result.failure()
        }
        val startedAt = System.currentTimeMillis()
        store.updateRun(workflow.id, SavedWorkflowRunRecord(SavedWorkflowRunStatus.RUNNING, startedAt = startedAt))
        return try {
            val summary = executeReadOnlyTemplate(applicationContext, workflow) +
                if (isExternalRequest && hasTaskText) "；外部任务备注已接收（不作为 Agent 指令）" else ""
            store.updateRun(
                workflow.id,
                SavedWorkflowRunRecord(
                    status = SavedWorkflowRunStatus.SUCCEEDED,
                    startedAt = startedAt,
                    finishedAt = System.currentTimeMillis(),
                    summary = summary
                )
            )
            dispatchExternalResult(
                isExternalRequest,
                requestId,
                workflowId,
                ExternalWorkflowContract.STATUS_SUCCEEDED,
                summary,
                callbackPackage
            )
            Result.success(
                workDataOf(
                    ExternalWorkflowContract.EXTRA_REQUEST_ID to requestId,
                    ExternalWorkflowContract.EXTRA_STATUS to ExternalWorkflowContract.STATUS_SUCCEEDED,
                    ExternalWorkflowContract.EXTRA_MESSAGE to summary.take(1_000)
                )
            )
        } catch (error: CancellationException) {
            val activeRun = store.list().firstOrNull { it.id == workflow.id }?.lastRun
            if (activeRun?.status == SavedWorkflowRunStatus.RUNNING) {
                store.updateRun(
                    workflow.id,
                    activeRun.copy(
                        status = SavedWorkflowRunStatus.CANCELLED,
                        finishedAt = System.currentTimeMillis(),
                        summary = "只读工作流已取消。",
                        failureReason = "WorkManager 停止了本次执行"
                    )
                )
            }
            dispatchExternalResult(
                isExternalRequest,
                requestId,
                workflowId,
                ExternalWorkflowContract.STATUS_CANCELLED,
                "只读工作流已取消。",
                callbackPackage
            )
            throw error
        } catch (error: Throwable) {
            val record = SavedWorkflowRunRecord(
                status = SavedWorkflowRunStatus.FAILED,
                startedAt = startedAt,
                finishedAt = System.currentTimeMillis(),
                summary = "只读工作流执行失败。",
                failureReason = SensitiveDataSanitizer.sanitizeText(
                    error.message ?: "未知错误",
                    redactPaths = true
                )
            )
            val updated = store.updateRun(
                workflow.id,
                record
            )
            if (updated != null) {
                SavedWorkflowFailureNotifier.notify(applicationContext, updated, record)
            }
            dispatchExternalResult(
                isExternalRequest,
                requestId,
                workflowId,
                ExternalWorkflowContract.STATUS_FAILED,
                record.failureReason ?: record.summary,
                callbackPackage
            )
            Result.failure()
        }
    }

    private fun dispatchExternalResult(
        enabled: Boolean,
        requestId: String,
        workflowId: String,
        status: String,
        message: String,
        callbackPackage: String?
    ) {
        if (!enabled || requestId.isBlank()) return
        ExternalWorkflowResultDispatcher.dispatch(
            applicationContext,
            requestId,
            workflowId,
            status,
            message,
            callbackPackage
        )
    }
}

private suspend fun executeReadOnlyTemplate(context: Context, workflow: SavedWorkflowDefinition): String =
    withContext(Dispatchers.IO) {
        when (workflow.template) {
        SavedWorkflowTemplate.PROJECT_READ_DIAGNOSTIC,
        SavedWorkflowTemplate.DIRECTORY_CHANGE_SUMMARY -> buildDirectoryReadOnlySummary(workflow.projectPath)
        SavedWorkflowTemplate.GITHUB_ACTIONS_STATUS ->
            GitHubActionsStatusReader().execute(workflow, ConfigRepository(context).getConfig())
        SavedWorkflowTemplate.SESSION_SUMMARY_EXPORT -> error("This workflow template requires foreground confirmation")
        }
    }

private fun buildDirectoryReadOnlySummary(projectPath: String?): String {
    val rawPath = projectPath?.trim().orEmpty()
    require(rawPath.isNotBlank()) { "未选择项目目录" }
    val root = File(rawPath)
    require(root.isDirectory) { "项目目录不可访问" }
    val canonicalRoot = root.canonicalFile
    val canonicalRootPrefix = canonicalRoot.path.trimEnd(File.separatorChar) + File.separator
    var fileCount = 0
    var directoryCount = 0
    var totalBytes = 0L
    val examples = mutableListOf<String>()
    val queue = ArrayDeque<Pair<File, Int>>()
    queue.add(canonicalRoot to 0)
    while (queue.isNotEmpty() && fileCount + directoryCount < MAX_WORKFLOW_SCAN_ENTRIES) {
        val (current, depth) = queue.removeFirst()
        val children = current.listFiles()?.sortedBy { it.name.lowercase() }.orEmpty()
        children.forEach { child ->
            if (fileCount + directoryCount >= MAX_WORKFLOW_SCAN_ENTRIES) return@forEach
            val canonical = runCatching { child.canonicalFile }.getOrNull() ?: return@forEach
            if (canonical.path != canonicalRoot.path && !canonical.path.startsWith(canonicalRootPrefix)) return@forEach
            if (canonical.isDirectory) {
                directoryCount += 1
                if (depth < MAX_WORKFLOW_SCAN_DEPTH && canonical.name !in WORKFLOW_IGNORED_DIRECTORIES) {
                    queue.add(canonical to depth + 1)
                }
            } else if (canonical.isFile) {
                fileCount += 1
                totalBytes += canonical.length().coerceAtLeast(0L)
                if (examples.size < 8) examples += canonical.name
            }
        }
    }
    return buildString {
        append("只读目录摘要：文件 ")
        append(fileCount)
        append("，目录 ")
        append(directoryCount)
        append("，已扫描约 ")
        append(totalBytes)
        append(" B")
        if (examples.isNotEmpty()) append("；示例：${examples.joinToString("、")}")
        if (fileCount + directoryCount >= MAX_WORKFLOW_SCAN_ENTRIES) append("；已达到扫描上限")
    }
}

private const val WORKFLOW_ID_KEY = "saved_workflow_id"
private const val REQUEST_ID_KEY = "saved_workflow_request_id"
private const val EXTERNAL_REQUEST_KEY = "saved_workflow_external_request"
private const val PROJECT_PATH_OVERRIDE_KEY = "saved_workflow_project_path_override"
private const val TASK_TEXT_KEY = "saved_workflow_task_text"
private const val CALLBACK_PACKAGE_KEY = "saved_workflow_callback_package"
private const val MAX_WORKFLOW_SCAN_DEPTH = 3
private const val MAX_WORKFLOW_SCAN_ENTRIES = 400
private val WORKFLOW_IGNORED_DIRECTORIES = setOf(".git", ".gradle", "build", "node_modules", ".cache", "__pycache__")
private fun readOnlyConstraints(workflow: SavedWorkflowDefinition): Constraints = Constraints.Builder()
    .apply {
        if (workflow.template == SavedWorkflowTemplate.GITHUB_ACTIONS_STATUS) {
            setRequiredNetworkType(NetworkType.CONNECTED)
        }
    }
    .build()
private fun workTag(id: String) = "murong_saved_workflow_$id"
private fun periodicWorkName(id: String) = "murong_saved_workflow_periodic_$id"
private fun oneTimeWorkName(id: String, requestId: String) = "murong_saved_workflow_once_${id}_$requestId"
