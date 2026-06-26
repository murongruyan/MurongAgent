package com.murong.agent.core.loop

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SessionBackgroundJobsManagerTest {

    @Test
    fun schedule_promotesQueuedJobAfterRunningJobCompletes() = runBlocking {
        var jobs = emptyList<BackgroundJobUi>()
        val completedJobs = mutableListOf<BackgroundJobUi>()
        val firstCompletion = CompletableDeferred<BackgroundJobCompletion>()
        val secondCompletion = CompletableDeferred<BackgroundJobCompletion>()
        val manager = SessionBackgroundJobsManager(
            scope = this,
            maxConcurrentJobs = 1,
            currentJobsProvider = { jobs },
            onJobsUpdated = { jobs = it },
            onPersistRequested = {},
            onJobCompleted = { completedJobs += it }
        )

        val firstResult = manager.schedule(
            request = BackgroundJobRequest(
                toolName = "shell",
                title = "Shell 后台任务",
                summary = "sleep 1"
            )
        ) {
            firstCompletion.await()
        }
        val secondResult = manager.schedule(
            request = BackgroundJobRequest(
                toolName = "shell",
                title = "Shell 后台任务",
                summary = "echo done"
            )
        ) {
            secondCompletion.await()
        }

        assertTrue(firstResult.startsWith("Background job started:"))
        assertTrue(secondResult.startsWith("Background job queued:"))
        assertEquals("running", jobs.first { it.summary == "sleep 1" }.status)
        assertEquals("queued", jobs.first { it.summary == "echo done" }.status)
        assertEquals(1, jobs.first { it.summary == "echo done" }.queuePosition)

        firstCompletion.complete(
            BackgroundJobCompletion(
                status = "completed",
                statusMessage = "后台 shell 命令执行完成。"
            )
        )

        withTimeout(1_000) {
            while (jobs.first { it.summary == "echo done" }.status != "running") {
                delay(10)
            }
        }

        assertEquals("completed", jobs.first { it.summary == "sleep 1" }.status)
        assertEquals("running", jobs.first { it.summary == "echo done" }.status)

        secondCompletion.complete(
            BackgroundJobCompletion(
                status = "completed",
                statusMessage = "后台 shell 命令执行完成。"
            )
        )

        withTimeout(1_000) {
            while (jobs.any { it.status in ACTIVE_BACKGROUND_JOB_STATUSES }) {
                delay(10)
            }
        }

        assertEquals(2, completedJobs.size)
        assertTrue(completedJobs.all { it.status == "completed" })
    }

    @Test
    fun cancelQueued_removesQueuedJobAndReindexesRemainingQueue() {
        runBlocking {
            var jobs = emptyList<BackgroundJobUi>()
            val firstCompletion = CompletableDeferred<BackgroundJobCompletion>()
            val manager = SessionBackgroundJobsManager(
                scope = this,
                maxConcurrentJobs = 1,
                currentJobsProvider = { jobs },
                onJobsUpdated = { jobs = it },
                onPersistRequested = {}
            )

            manager.schedule(
                request = BackgroundJobRequest(
                    jobIdOverride = "run-1",
                    toolName = "subagent",
                    title = "子代理后台任务",
                    summary = "run-1"
                )
            ) {
                firstCompletion.await()
            }
            manager.schedule(
                request = BackgroundJobRequest(
                    jobIdOverride = "run-2",
                    toolName = "subagent",
                    title = "子代理后台任务",
                    summary = "run-2"
                )
            ) {
                BackgroundJobCompletion(status = "completed", statusMessage = "done")
            }
            manager.schedule(
                request = BackgroundJobRequest(
                    jobIdOverride = "run-3",
                    toolName = "subagent",
                    title = "子代理后台任务",
                    summary = "run-3"
                )
            ) {
                BackgroundJobCompletion(status = "completed", statusMessage = "done")
            }

            assertTrue(manager.cancelQueued("run-2"))
            assertEquals(null, jobs.firstOrNull { it.jobId == "run-2" })
            assertEquals(1, jobs.first { it.jobId == "run-3" }.queuePosition)

            firstCompletion.complete(
                BackgroundJobCompletion(
                    status = "completed",
                    statusMessage = "后台任务完成。"
                )
            )
        }
    }
}
