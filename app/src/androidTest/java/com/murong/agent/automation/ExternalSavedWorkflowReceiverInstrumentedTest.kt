package com.murong.agent.automation

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.murong.agent.core.automation.SavedWorkflowDefinition
import com.murong.agent.core.automation.SavedWorkflowRunStatus
import com.murong.agent.core.automation.SavedWorkflowTemplate
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExternalSavedWorkflowReceiverInstrumentedTest {
    @Test
    fun authenticatedReadOnlyRequestRunsWhileReplayEscapeAndWriteAreBlocked() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val senderContext = instrumentation.context
        val targetContext = instrumentation.targetContext
        val workflowsFile = File(targetContext.filesDir, "saved_workflows.json")
        val accessFile = File(targetContext.noBackupFilesDir, "external_workflow_access.json")
        val workflowsBackup = workflowsFile.takeIf(File::isFile)?.readBytes()
        val accessBackup = accessFile.takeIf(File::isFile)?.readBytes()
        val root = File(targetContext.cacheDir, "external-workflow-${UUID.randomUUID()}").apply { mkdirs() }
        File(root, "sample.txt").writeText("unchanged")
        val scheduler = SavedWorkflowScheduler(targetContext)
        val readWorkflow = SavedWorkflowDefinition(
            id = "instrumented-read-${UUID.randomUUID()}",
            name = "instrumented read",
            template = SavedWorkflowTemplate.PROJECT_READ_DIAGNOSTIC,
            projectPath = root.path
        )
        val writeWorkflow = SavedWorkflowDefinition(
            id = "instrumented-write-${UUID.randomUUID()}",
            name = "instrumented write",
            template = SavedWorkflowTemplate.SESSION_SUMMARY_EXPORT
        )
        val requestId = "instrumented-${UUID.randomUUID()}"
        val callbackLatch = CountDownLatch(1)
        var callbackStatus: String? = null
        val callbackReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                callbackStatus = intent?.getStringExtra(ExternalWorkflowContract.EXTRA_STATUS)
                callbackLatch.countDown()
            }
        }

        try {
            senderContext.registerReceiver(
                callbackReceiver,
                IntentFilter(ExternalWorkflowContract.RESULT_ACTION),
                Context.RECEIVER_EXPORTED
            )
            SavedWorkflowStore(targetContext).upsert(readWorkflow)
            SavedWorkflowStore(targetContext).upsert(writeWorkflow)
            val token = ExternalWorkflowAccessStore(targetContext).enableWithNewToken().token

            val accepted = sendOrdered(
                senderContext,
                readWorkflow.id,
                token,
                requestId,
                projectPath = root.path,
                callbackPackage = senderContext.packageName
            )
            assertEquals(ExternalWorkflowContract.RESULT_ACCEPTED, accepted.code)
            assertEquals(ExternalWorkflowContract.STATUS_QUEUED, accepted.status)
            waitForRun(targetContext, readWorkflow.id, SavedWorkflowRunStatus.SUCCEEDED)
            assertTrue("等待最终结果回调超时", callbackLatch.await(10, TimeUnit.SECONDS))
            assertEquals(ExternalWorkflowContract.STATUS_SUCCEEDED, callbackStatus)
            assertEquals("unchanged", File(root, "sample.txt").readText())

            val replay = sendOrdered(senderContext, readWorkflow.id, token, requestId)
            assertEquals(ExternalWorkflowContract.RESULT_REJECTED, replay.code)
            assertTrue(replay.message.contains("request_id"))

            val escape = sendOrdered(
                senderContext,
                readWorkflow.id,
                token,
                "instrumented-${UUID.randomUUID()}",
                projectPath = root.parentFile!!.path
            )
            assertEquals(ExternalWorkflowContract.RESULT_REJECTED, escape.code)

            val write = sendOrdered(
                senderContext,
                writeWorkflow.id,
                token,
                "instrumented-${UUID.randomUUID()}"
            )
            assertEquals(ExternalWorkflowContract.RESULT_REJECTED, write.code)
            assertEquals(
                SavedWorkflowRunStatus.BLOCKED,
                SavedWorkflowStore(targetContext).list().first { it.id == writeWorkflow.id }.lastRun?.status
            )

            val wrongToken = sendOrdered(
                senderContext,
                readWorkflow.id,
                "x".repeat(43),
                "instrumented-${UUID.randomUUID()}"
            )
            assertEquals(ExternalWorkflowContract.RESULT_REJECTED, wrongToken.code)
        } finally {
            runCatching { senderContext.unregisterReceiver(callbackReceiver) }
            scheduler.delete(readWorkflow.id)
            scheduler.delete(writeWorkflow.id)
            restoreExact(workflowsFile, workflowsBackup)
            restoreExact(accessFile, accessBackup)
            root.deleteRecursively()
        }
    }

    private fun sendOrdered(
        context: Context,
        workflowId: String,
        token: String,
        requestId: String,
        projectPath: String? = null,
        callbackPackage: String? = null
    ): BroadcastResult {
        val latch = CountDownLatch(1)
        var result = BroadcastResult(Activity.RESULT_CANCELED, null, "没有收到结果")
        val finalReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                result = BroadcastResult(
                    code = resultCode,
                    status = getResultExtras(false)?.getString(ExternalWorkflowContract.EXTRA_STATUS),
                    message = getResultExtras(false)?.getString(ExternalWorkflowContract.EXTRA_MESSAGE).orEmpty()
                )
                latch.countDown()
            }
        }
        val intent = Intent(ExternalWorkflowContract.RUN_ACTION)
            .setComponent(ComponentName("com.murong.agent", ExternalSavedWorkflowReceiver::class.java.name))
            .putExtra(ExternalWorkflowContract.EXTRA_WORKFLOW_ID, workflowId)
            .putExtra(ExternalWorkflowContract.EXTRA_ACCESS_TOKEN, token)
            .putExtra(ExternalWorkflowContract.EXTRA_REQUEST_ID, requestId)
        projectPath?.let { intent.putExtra(ExternalWorkflowContract.EXTRA_PROJECT_PATH, it) }
        callbackPackage?.let { intent.putExtra(ExternalWorkflowContract.EXTRA_CALLBACK_PACKAGE, it) }
        context.sendOrderedBroadcast(
            intent,
            null,
            finalReceiver,
            null,
            Activity.RESULT_CANCELED,
            null,
            null
        )
        assertTrue("等待有序广播结果超时", latch.await(10, TimeUnit.SECONDS))
        return result
    }

    private fun waitForRun(context: Context, workflowId: String, status: SavedWorkflowRunStatus) {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            val current = SavedWorkflowStore(context).list().firstOrNull { it.id == workflowId }?.lastRun?.status
            if (current == status) return
            Thread.sleep(100)
        }
        error("工作流 $workflowId 未在超时前进入 $status")
    }

    private fun restoreExact(file: File, bytes: ByteArray?) {
        if (bytes == null) {
            file.delete()
        } else {
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
        }
    }

    private data class BroadcastResult(val code: Int, val status: String?, val message: String)
}
