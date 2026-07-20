package com.murong.agent.automation

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.murong.agent.R
import com.murong.agent.core.automation.SavedWorkflowDefinition
import com.murong.agent.core.automation.SavedWorkflowRunRecord
import com.murong.agent.core.doctor.SensitiveDataSanitizer

/**
 * Best-effort failure visibility for background work.  The durable, copyable in-app run record
 * remains the fallback when Android notification permission has been denied by the user.
 */
object SavedWorkflowFailureNotifier {
    fun notify(context: Context, workflow: SavedWorkflowDefinition, record: SavedWorkflowRunRecord) {
        val appContext = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = NotificationManagerCompat.from(appContext)
        if (!manager.areNotificationsEnabled()) return
        ensureChannel(appContext)
        val detail = SensitiveDataSanitizer.sanitizeText(
            record.failureReason ?: record.summary.ifBlank { "请在保存的自动化中查看去敏运行记录。" },
            redactPaths = true
        ).replace(Regex("\\s+"), " ").take(160)
        runCatching {
            manager.notify(
                notificationId(workflow.id),
                NotificationCompat.Builder(appContext, CHANNEL_ID)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle("工作流执行失败：${workflow.name.take(48)}")
                    .setContentText(detail)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
                    .setAutoCancel(true)
                    .setCategory(NotificationCompat.CATEGORY_ERROR)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .build()
            )
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Murong 工作流失败",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "保存的自动化未能完成时的去敏提醒"
            }
        )
    }

    private fun notificationId(workflowId: String): Int = workflowId.hashCode() and Int.MAX_VALUE

    private const val CHANNEL_ID = "murong_saved_workflow_failures"
}
