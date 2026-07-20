package com.murong.agent.automation

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.murong.agent.R
import com.murong.agent.core.automation.SavedWorkflowDefinition
import com.murong.agent.ui.MainActivity

/** Brings a blocked high-risk external request back to the normal foreground confirmation UI. */
object ExternalWorkflowApprovalNotifier {
    fun notify(context: Context, workflow: SavedWorkflowDefinition) {
        val appContext = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val manager = NotificationManagerCompat.from(appContext)
        if (!manager.areNotificationsEnabled()) return
        ensureChannel(appContext)
        val openIntent = Intent(appContext, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_SAVED_WORKFLOWS, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pendingIntent = PendingIntent.getActivity(
            appContext,
            workflow.id.hashCode(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        runCatching {
            manager.notify(
                (workflow.id.hashCode() xor APPROVAL_NOTIFICATION_SALT) and Int.MAX_VALUE,
                NotificationCompat.Builder(appContext, CHANNEL_ID)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle("外部工作流等待确认")
                    .setContentText("${workflow.name.take(48)} 涉及非后台只读能力，请在 Murong 中逐次确认。")
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .setCategory(NotificationCompat.CATEGORY_EVENT)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .build()
            )
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "外部工作流审批",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "外部自动化请求需要回到 Murong 前台确认时提醒" }
        )
    }

    private const val CHANNEL_ID = "murong_external_workflow_approval"
    private const val APPROVAL_NOTIFICATION_SALT = 0x3417A2
}
