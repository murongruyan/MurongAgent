package com.murong.agent.ui.assistant

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
import com.murong.agent.core.tool.AndroidGuiAccessibilityService
import com.murong.agent.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps accepted assistant work alive after the voice popup closes and exposes progress through a
 * notification. Public web comparison and code work can stay fully in the background. GUI tasks
 * are explicitly marked as foreground take-over because Android cannot click another app on a
 * separate invisible display.
 */
@AndroidEntryPoint
class AssistantTaskForegroundService : Service() {
    @Inject lateinit var conversationRunner: AssistantConversationRunner

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }
    private var running = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val startIntent = intent ?: run {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val taskText = startIntent.getStringExtra(EXTRA_TASK_TEXT)?.trim().orEmpty()
        val routeKind = startIntent.getStringExtra(EXTRA_ROUTE_KIND)
            ?.let { raw -> runCatching { AssistantTaskKind.valueOf(raw) }.getOrNull() }
            ?: run {
                stopSelf(startId)
                return START_NOT_STICKY
            }
        if (taskText.isBlank() || routeKind !in SUPPORTED_KINDS) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val notificationId = startIntent.getIntExtra(
            EXTRA_NOTIFICATION_ID,
            nextNotificationId(),
        )
        if (running) {
            notificationManager?.notify(
                notificationId,
                buildNotification(
                    title = taskTitle(routeKind),
                    detail = "已有助手任务正在运行，本次没有重复执行",
                    ongoing = false,
                ),
            )
            return START_NOT_STICKY
        }
        val route = AssistantRequestRoute(
            kind = routeKind,
            runWithNotification = true,
            mayTakeOverScreen = routeKind == AssistantTaskKind.PHONE_FOREGROUND,
            label = taskTitle(routeKind),
        )
        ServiceCompat.startForeground(
            this,
            notificationId,
            buildNotification(
                title = taskTitle(routeKind),
                detail = startingDetail(routeKind),
                ongoing = true,
            ),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        val overlayTaskId = "assistant-$notificationId"
        val progressHistory = ArrayDeque<String>()
        publishProgress(
            notificationId = notificationId,
            overlayTaskId = overlayTaskId,
            title = taskTitle(routeKind),
            detail = startingDetail(routeKind),
            ongoing = true,
            history = progressHistory,
        )
        running = true
        serviceScope.launch {
            if (routeKind == AssistantTaskKind.PHONE_FOREGROUND) {
                // Give the assistant popup enough time to leave the foreground before GUI tools run.
                delay(850L)
            }
            publishProgress(
                notificationId = notificationId,
                overlayTaskId = overlayTaskId,
                title = taskTitle(routeKind),
                detail = runningDetail(routeKind),
                ongoing = true,
                history = progressHistory,
            )
            val modelInput = AssistantRequestRouter.backgroundModelInput(taskText, route)
            val result = when (routeKind) {
                AssistantTaskKind.PHONE_FOREGROUND ->
                    conversationRunner.runPhoneAndAwait(taskText) { detail ->
                        publishProgress(
                            notificationId = notificationId,
                            overlayTaskId = overlayTaskId,
                            title = taskTitle(routeKind),
                            detail = detail,
                            ongoing = true,
                            history = progressHistory,
                        )
                    }
                AssistantTaskKind.BACKGROUND_CODE ->
                    conversationRunner.runCodeAndAwait(modelInput)
                else -> conversationRunner.runMainAndAwait(modelInput)
            }
            val detail = when {
                !result.accepted -> result.error ?: "任务未被接受"
                result.needsAttention -> "任务需要你的确认，点此打开对话继续"
                !result.error.isNullOrBlank() -> "任务失败：${result.error}"
                result.responseText.isNotBlank() -> result.responseText.take(NOTIFICATION_TEXT_LIMIT)
                else -> "任务已完成，点此查看对话"
            }
            finishNotification(notificationId, taskTitle(routeKind), detail)
            publishProgress(
                notificationId = notificationId,
                overlayTaskId = overlayTaskId,
                title = taskTitle(routeKind),
                detail = detail,
                ongoing = false,
                history = progressHistory,
            )
            running = false
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun finishNotification(notificationId: Int, title: String, detail: String) {
        stopForeground(STOP_FOREGROUND_DETACH)
        notificationManager?.notify(
            notificationId,
            buildNotification(title = title, detail = detail, ongoing = false),
        )
    }

    private fun publishProgress(
        notificationId: Int,
        overlayTaskId: String,
        title: String,
        detail: String,
        ongoing: Boolean,
        history: ArrayDeque<String>,
    ) {
        val normalized = detail.trim().takeIf { it.isNotBlank() } ?: return
        if (history.lastOrNull() != normalized) {
            history.addLast(normalized)
            while (history.size > MAX_OVERLAY_HISTORY_LINES) history.removeFirst()
        }
        notificationManager?.notify(
            notificationId,
            buildNotification(title = title, detail = normalized, ongoing = ongoing),
        )
        AndroidGuiAccessibilityService.showTaskProgressOverlay(
            taskId = overlayTaskId,
            title = title,
            detail = history.joinToString("\n\n"),
            completed = !ongoing,
        )
    }

    private fun buildNotification(
        title: String,
        detail: String,
        ongoing: Boolean,
    ): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.app_icon)
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setContentIntent(openIntent)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(ongoing)
            .setAutoCancel(!ongoing)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        notificationManager?.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "助手后台任务",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "显示外卖公开信息比价、代码任务和手机操作的执行进度"
            },
        )
    }

    private fun taskTitle(kind: AssistantTaskKind): String = when (kind) {
        AssistantTaskKind.BACKGROUND_WEB_RESEARCH -> "Murong 后台比价"
        AssistantTaskKind.BACKGROUND_CODE -> "Murong 后台代码任务"
        AssistantTaskKind.PHONE_FOREGROUND -> "Murong 手机操作"
        else -> "Murong 助手任务"
    }

    private fun startingDetail(kind: AssistantTaskKind): String = when (kind) {
        AssistantTaskKind.PHONE_FOREGROUND -> "即将接管前台屏幕，完成后会在这里通知"
        else -> "任务已接收，正在准备…"
    }

    private fun runningDetail(kind: AssistantTaskKind): String = when (kind) {
        AssistantTaskKind.BACKGROUND_WEB_RESEARCH ->
            "正在后台检索公开网页/API，不会打开外卖 App 或影响你使用手机"
        AssistantTaskKind.BACKGROUND_CODE -> "正在后台调用主模型处理代码或系统任务"
        AssistantTaskKind.PHONE_FOREGROUND -> "正在操作前台手机界面，请暂时不要手动触碰屏幕"
        else -> "任务正在运行"
    }

    companion object {
        private const val CHANNEL_ID = "assistant_background_tasks"
        private const val EXTRA_TASK_TEXT = "task_text"
        private const val EXTRA_ROUTE_KIND = "route_kind"
        private const val EXTRA_NOTIFICATION_ID = "notification_id"
        private const val NOTIFICATION_TEXT_LIMIT = 900
        private const val MAX_OVERLAY_HISTORY_LINES = 30
        private val notificationIds = AtomicInteger(8_400)
        private val SUPPORTED_KINDS = setOf(
            AssistantTaskKind.BACKGROUND_WEB_RESEARCH,
            AssistantTaskKind.BACKGROUND_CODE,
            AssistantTaskKind.PHONE_FOREGROUND,
        )

        internal fun enqueue(context: Context, taskText: String, kind: AssistantTaskKind) {
            require(kind in SUPPORTED_KINDS) { "Unsupported background assistant task: $kind" }
            val intent = Intent(context, AssistantTaskForegroundService::class.java).apply {
                putExtra(EXTRA_TASK_TEXT, taskText.trim())
                putExtra(EXTRA_ROUTE_KIND, kind.name)
                putExtra(EXTRA_NOTIFICATION_ID, nextNotificationId())
            }
            ContextCompat.startForegroundService(context, intent)
        }

        private fun nextNotificationId(): Int = notificationIds.incrementAndGet()
    }
}
