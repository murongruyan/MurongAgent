package com.murong.agent.core.loop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.UUID

internal const val RESTORED_BACKGROUND_JOB_INTERRUPTED_MESSAGE =
    "会话恢复后，原后台任务执行现场已丢失，已标记为中断。"

internal val ACTIVE_BACKGROUND_JOB_STATUSES = setOf(
    "queued",
    "running"
)

data class BackgroundJobUi(
    val jobId: String = UUID.randomUUID().toString(),
    val toolName: String,
    val title: String,
    val summary: String,
    val detail: String = "",
    val status: String,
    val statusMessage: String = "",
    val resultPreview: String? = null,
    val queuePosition: Int? = null,
    val assignedSlot: Int? = null,
    val concurrencyLimit: Int? = null,
    val timeoutSeconds: Int? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val startedAt: Long? = null,
    val finishedAt: Long? = null
)

data class BackgroundJobRequest(
    val jobIdOverride: String? = null,
    val toolName: String,
    val title: String,
    val summary: String,
    val detail: String = "",
    val timeoutSeconds: Int? = null
)

data class BackgroundJobCompletion(
    val status: String,
    val statusMessage: String,
    val resultPreview: String? = null
)

private data class PendingBackgroundJobExecution(
    val jobId: String,
    val execute: suspend () -> BackgroundJobCompletion
)

internal class SessionBackgroundJobsManager(
    private val scope: CoroutineScope,
    private val maxConcurrentJobs: Int,
    private val currentJobsProvider: () -> List<BackgroundJobUi>,
    private val onJobsUpdated: (List<BackgroundJobUi>) -> Unit,
    private val onPersistRequested: () -> Unit,
    private val onJobCompleted: (BackgroundJobUi) -> Unit = {}
) {
    private val executionLock = Any()
    private val runningExecutionSlots = mutableMapOf<Int, String>()
    private val pendingExecutions = mutableListOf<PendingBackgroundJobExecution>()

    suspend fun schedule(
        request: BackgroundJobRequest,
        executeNow: suspend () -> BackgroundJobCompletion
    ): String {
        val createdAt = System.currentTimeMillis()
        val jobId = request.jobIdOverride?.trim()?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        val execution = PendingBackgroundJobExecution(
            jobId = jobId,
            execute = executeNow
        )
        val (startNow, queuePosition, assignedSlot) = synchronized(executionLock) {
            if (runningExecutionSlots.size < maxConcurrentJobs) {
                val slot = findNextAvailableSlotLocked()
                runningExecutionSlots[slot] = jobId
                Triple(true, null, slot)
            } else {
                pendingExecutions += execution
                Triple(false, pendingExecutions.size, null)
            }
        }
        val initialStatusMessage = if (startNow) {
            "后台任务已启动，正在执行。"
        } else {
            "后台任务已入队，等待可用执行槽位。"
        }
        upsertJob(
            BackgroundJobUi(
                jobId = jobId,
                toolName = request.toolName,
                title = request.title,
                summary = request.summary,
                detail = request.detail,
                status = if (startNow) "running" else "queued",
                statusMessage = initialStatusMessage,
                queuePosition = queuePosition,
                assignedSlot = assignedSlot,
                concurrencyLimit = maxConcurrentJobs,
                timeoutSeconds = request.timeoutSeconds,
                createdAt = createdAt,
                startedAt = if (startNow) createdAt else null
            )
        )
        if (startNow) {
            launchExecution(execution)
            return "Background job started: $jobId (slot $assignedSlot/$maxConcurrentJobs)"
        }
        refreshQueuedPositions()
        return "Background job queued: $jobId (position $queuePosition)"
    }

    fun cancelQueued(jobId: String): Boolean {
        val removed = synchronized(executionLock) {
            val index = pendingExecutions.indexOfFirst { it.jobId == jobId }
            if (index == -1) {
                false
            } else {
                pendingExecutions.removeAt(index)
                true
            }
        }
        if (!removed) return false
        updateJobs { current ->
            current.filterNot { job -> job.jobId == jobId }
        }
        refreshQueuedPositions()
        return true
    }

    private fun launchExecution(execution: PendingBackgroundJobExecution) {
        scope.launch {
            val completion = try {
                execution.execute()
            } catch (t: Throwable) {
                BackgroundJobCompletion(
                    status = "failed",
                    statusMessage = t.message?.takeIf { it.isNotBlank() } ?: "后台任务执行失败。"
                )
            }
            completeJob(execution.jobId, completion)
        }
    }

    private fun completeJob(
        jobId: String,
        completion: BackgroundJobCompletion
    ) {
        val finishedAt = System.currentTimeMillis()
        val completedJob = updateJob(jobId) { job ->
            job.copy(
                status = completion.status,
                statusMessage = completion.statusMessage,
                resultPreview = completion.resultPreview,
                queuePosition = null,
                assignedSlot = null,
                finishedAt = finishedAt
            )
        } ?: return
        val next = synchronized(executionLock) {
            val freedSlot = runningExecutionSlots.entries.firstOrNull { it.value == jobId }?.key
            if (freedSlot != null) {
                runningExecutionSlots.remove(freedSlot)
            }
            if (pendingExecutions.isEmpty()) {
                null
            } else {
                val pending = pendingExecutions.removeAt(0)
                val slot = freedSlot ?: findNextAvailableSlotLocked()
                runningExecutionSlots[slot] = pending.jobId
                pending to slot
            }
        }
        onJobCompleted(completedJob)
        if (next == null) {
            refreshQueuedPositions()
            return
        }
        val (pendingExecution, assignedSlot) = next
        markJobRunning(
            jobId = pendingExecution.jobId,
            assignedSlot = assignedSlot,
            startedAt = System.currentTimeMillis()
        )
        refreshQueuedPositions()
        launchExecution(pendingExecution)
    }

    private fun markJobRunning(jobId: String, assignedSlot: Int, startedAt: Long) {
        updateJob(jobId) { job ->
            job.copy(
                status = "running",
                statusMessage = "后台任务已启动，正在执行。",
                queuePosition = null,
                assignedSlot = assignedSlot,
                startedAt = startedAt
            )
        }
    }

    private fun refreshQueuedPositions() {
        val pendingIds = synchronized(executionLock) {
            pendingExecutions.map { it.jobId }
        }
        val positions = pendingIds.withIndex().associate { (index, id) -> id to (index + 1) }
        updateJobs { current ->
            current.map { job ->
                when {
                    job.status == "queued" -> job.copy(queuePosition = positions[job.jobId])
                    job.status in ACTIVE_BACKGROUND_JOB_STATUSES -> job.copy(concurrencyLimit = maxConcurrentJobs)
                    else -> job
                }
            }
        }
    }

    private fun upsertJob(job: BackgroundJobUi) {
        updateJobs { current ->
            val existingIndex = current.indexOfFirst { it.jobId == job.jobId }
            if (existingIndex == -1) {
                current + job
            } else {
                current.toMutableList().apply { this[existingIndex] = job }
            }
        }
    }

    private fun updateJob(
        jobId: String,
        transform: (BackgroundJobUi) -> BackgroundJobUi
    ): BackgroundJobUi? {
        var updatedJob: BackgroundJobUi? = null
        updateJobs { current ->
            current.map { job ->
                if (job.jobId == jobId) {
                    transform(job).also { updatedJob = it }
                } else {
                    job
                }
            }
        }
        return updatedJob
    }

    private fun updateJobs(transform: (List<BackgroundJobUi>) -> List<BackgroundJobUi>) {
        val updated = transform(currentJobsProvider())
        onJobsUpdated(updated)
        onPersistRequested()
    }

    private fun findNextAvailableSlotLocked(): Int {
        for (slot in 1..maxConcurrentJobs) {
            if (slot !in runningExecutionSlots) {
                return slot
            }
        }
        return maxConcurrentJobs
    }
}

internal fun hasActiveBackgroundJobWork(state: SessionState): Boolean {
    return state.backgroundJobs.any { job -> job.status in ACTIVE_BACKGROUND_JOB_STATUSES }
}

internal fun normalizeRestoredBackgroundJob(job: BackgroundJobUi, restoredAt: Long): BackgroundJobUi {
    if (job.status !in ACTIVE_BACKGROUND_JOB_STATUSES) return job
    return job.copy(
        status = "interrupted",
        statusMessage = RESTORED_BACKGROUND_JOB_INTERRUPTED_MESSAGE,
        queuePosition = null,
        assignedSlot = null,
        finishedAt = job.finishedAt ?: restoredAt
    )
}
