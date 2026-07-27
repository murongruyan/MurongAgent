package com.murong.agent.ui.tools

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.murong.agent.R
import com.murong.agent.core.tool.BuiltinVisionModelManager
import com.murong.agent.core.tool.BuiltinVisionModelState
import com.murong.agent.core.tool.BuiltinVisionModels
import com.murong.agent.core.tool.BuiltinVisionTier
import com.murong.agent.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Keeps multi-hundred-megabyte model downloads alive outside the settings screen and mirrors the
 * shared manager's exact byte progress into a low-priority foreground notification.
 */
class BuiltinVisionModelDownloadService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val manager by lazy { BuiltinVisionModelManager.shared(applicationContext) }
    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }
    private var observerJob: Job? = null
    private var activeTier: BuiltinVisionTier? = null
    private var activeStartId: Int = 0
    private var completionHandled = false

    override fun onCreate() {
        super.onCreate()
        notificationManager?.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "本地模型下载",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "显示本地模型文件清单、下载、断点续传与校验进度"
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            manager.cancelInstall()
            return START_NOT_STICKY
        }
        val tier = intent?.getStringExtra(EXTRA_TIER)
            ?.let { raw -> runCatching { BuiltinVisionTier.valueOf(raw) }.getOrNull() }
            ?: run {
                stopSelf(startId)
                return START_NOT_STICKY
            }
        val descriptor = BuiltinVisionModels.descriptor(tier)
        activeTier = tier
        activeStartId = startId
        completionHandled = false
        val notificationId = notificationId(tier)
        ServiceCompat.startForeground(
            this,
            notificationId,
            buildNotification(
                tier = tier,
                title = "正在准备 ${descriptor.displayName}",
                detail = "读取官方文件清单…",
                progress = 0,
                indeterminate = true,
                ongoing = true,
            ),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )

        manager.install(tier)
        observerJob?.cancel()
        observerJob = serviceScope.launch {
            var seenThisDownload = manager.state.value.installingTier == tier
            manager.state.collect { state ->
                if (completionHandled || activeTier != tier) return@collect
                when {
                    state.installingTier == tier -> {
                        seenThisDownload = true
                        notificationManager?.notify(
                            notificationId,
                            progressNotification(tier, state),
                        )
                    }

                    state.installingTier != null -> {
                        finishDownload(
                            tier = tier,
                            success = false,
                            detail = "已有其他模型正在下载，请稍后重试",
                        )
                    }

                    seenThisDownload -> {
                        val installed = tier in state.installedTiers ||
                            BuiltinVisionModels.isInstalled(applicationContext, tier)
                        finishDownload(
                            tier = tier,
                            success = installed,
                            detail = when {
                                installed -> "${descriptor.displayName} 已安装并完成 SHA-256 校验"
                                !state.error.isNullOrBlank() ->
                                    "下载失败：${state.error}"
                                else -> state.message.ifBlank { "下载已暂停，断点文件已保留" }
                            },
                        )
                    }
                }
            }
        }
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        manager.cancelInstall()
        activeTier?.let { tier ->
            finishDownload(
                tier = tier,
                success = false,
                detail = "系统结束了长时间下载；断点已保留，重新点击即可继续",
            )
        } ?: stopSelf(startId)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun progressNotification(
        tier: BuiltinVisionTier,
        state: BuiltinVisionModelState,
    ): Notification {
        val descriptor = BuiltinVisionModels.descriptor(tier)
        val percent = (state.progress * 100).toInt().coerceIn(0, 100)
        val readingManifest = state.downloadedBytes == 0L &&
            state.message.contains("文件清单")
        val detail = if (readingManifest) {
            state.message
        } else {
            "$percent% · ${formatBytes(state.downloadedBytes)} / " +
                "${formatBytes(state.totalBytes)} · ${state.message}"
        }
        return buildNotification(
            tier = tier,
            title = "正在下载 ${descriptor.displayName}",
            detail = detail,
            progress = percent,
            indeterminate = readingManifest,
            ongoing = true,
        )
    }

    private fun finishDownload(
        tier: BuiltinVisionTier,
        success: Boolean,
        detail: String,
    ) {
        if (completionHandled) return
        completionHandled = true
        val descriptor = BuiltinVisionModels.descriptor(tier)
        stopForeground(STOP_FOREGROUND_DETACH)
        notificationManager?.notify(
            notificationId(tier),
            buildNotification(
                tier = tier,
                title = if (success) {
                    "${descriptor.displayName} 安装完成"
                } else {
                    "${descriptor.displayName} 未安装完成"
                },
                detail = detail,
                progress = if (success) 100 else 0,
                indeterminate = false,
                ongoing = false,
            ),
        )
        stopSelfResult(activeStartId)
    }

    private fun buildNotification(
        tier: BuiltinVisionTier,
        title: String,
        detail: String,
        progress: Int,
        indeterminate: Boolean,
        ongoing: Boolean,
    ): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            tier.ordinal,
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.app_icon)
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setContentIntent(openIntent)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setProgress(100, progress, indeterminate)
        if (ongoing) {
            val cancelIntent = Intent(this, BuiltinVisionModelDownloadService::class.java).apply {
                action = ACTION_CANCEL
                putExtra(EXTRA_TIER, tier.name)
            }
            builder.addAction(
                0,
                "暂停",
                PendingIntent.getService(
                    this,
                    CANCEL_REQUEST_CODE_BASE + tier.ordinal,
                    cancelIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }
        return builder.build()
    }

    companion object {
        private const val CHANNEL_ID = "builtin_model_downloads"
        private const val EXTRA_TIER = "model_tier"
        private const val ACTION_CANCEL = "com.murong.agent.action.CANCEL_MODEL_DOWNLOAD"
        private const val NOTIFICATION_ID_BASE = 9_560
        private const val CANCEL_REQUEST_CODE_BASE = 10_560

        fun enqueue(context: Context, tier: BuiltinVisionTier) {
            val intent = Intent(context, BuiltinVisionModelDownloadService::class.java)
                .putExtra(EXTRA_TIER, tier.name)
            ContextCompat.startForegroundService(context, intent)
        }

        private fun notificationId(tier: BuiltinVisionTier): Int =
            NOTIFICATION_ID_BASE + tier.ordinal

        private fun formatBytes(bytes: Long): String = when {
            bytes >= 1024L * 1024L * 1024L ->
                "${"%.2f".format(bytes / (1024.0 * 1024.0 * 1024.0))} GB"
            bytes >= 1024L * 1024L ->
                "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
            bytes >= 1024L -> "${bytes / 1024L} KB"
            else -> "$bytes B"
        }
    }
}
