package com.murong.agent.lan

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
import com.murong.agent.ui.MainActivity

internal object LanWebConnectionRequestNotifier {
    fun notify(context: Context, request: LanWebConnectionRequestSummary) {
        val appContext = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val manager = NotificationManagerCompat.from(appContext)
        if (!manager.areNotificationsEnabled()) return
        ensureChannel(appContext)
        val open = PendingIntent.getActivity(
            appContext,
            requestCode(request.requestId, 0),
            Intent(appContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val approve = action(appContext, request.requestId, LanWebContract.ACTION_APPROVE_CONNECTION, 1)
        val reject = action(appContext, request.requestId, LanWebContract.ACTION_REJECT_CONNECTION, 2)
        val block = action(appContext, request.requestId, LanWebContract.ACTION_BLOCK_CONNECTION, 3)
        runCatching {
            manager.notify(
                notificationId(request.requestId),
                NotificationCompat.Builder(appContext, CHANNEL_ID)
                    .setSmallIcon(R.drawable.app_icon)
                    .setContentTitle("${request.clientName.take(40)} 请求连接")
                    .setContentText("本机 ID ${request.deviceDisplayId} · ${request.platform}")
                    .setStyle(
                        NotificationCompat.BigTextStyle().bigText(
                            "${request.clientName.take(80)}（${request.deviceDisplayId}）请求连接 Murong。允许后仍受文件、终端、Root 与 GitHub 审批限制。",
                        )
                    )
                    .setContentIntent(open)
                    .addAction(0, "同意", approve)
                    .addAction(0, "拒绝", reject)
                    .addAction(0, "拉黑", block)
                    .setAutoCancel(true)
                    .setCategory(NotificationCompat.CATEGORY_CALL)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .build(),
            )
        }
    }

    fun cancel(context: Context, requestId: String) {
        NotificationManagerCompat.from(context.applicationContext).cancel(notificationId(requestId))
    }

    private fun action(context: Context, requestId: String, action: String, salt: Int) = PendingIntent.getService(
        context,
        requestCode(requestId, salt),
        Intent(context, LanWebForegroundService::class.java)
            .setAction(action)
            .putExtra(LanWebContract.EXTRA_CONNECTION_REQUEST_ID, requestId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "电脑连接申请",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "陌生电脑请求连接 Murong 时提醒并提供同意、拒绝和拉黑操作"
            }
        )
    }

    private fun requestCode(requestId: String, salt: Int) = (requestId.hashCode() xor salt xor NOTIFICATION_SALT) and Int.MAX_VALUE
    private fun notificationId(requestId: String) = requestCode(requestId, 17)

    private const val CHANNEL_ID = "murong_device_connection_request"
    private const val NOTIFICATION_SALT = 0x27A451
}
