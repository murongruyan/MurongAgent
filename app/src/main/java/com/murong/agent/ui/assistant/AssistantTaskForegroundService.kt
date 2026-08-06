package com.murong.agent.ui.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.murong.agent.R
import com.murong.agent.core.tool.AndroidGuiAccessibilityService
import com.murong.agent.shizuku.ShizukuSystemAccess
import com.murong.agent.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal data class AssistantLiveUpdateProgress(
    val current: Int,
    val maximum: Int,
)

private val ASSISTANT_STEP_PATTERN = Regex("第\\s*(\\d+)\\s*/\\s*(\\d+)\\s*步")

internal fun assistantLiveUpdateProgress(detail: String): AssistantLiveUpdateProgress? {
    val match = ASSISTANT_STEP_PATTERN.find(detail) ?: return null
    val current = match.groupValues[1].toIntOrNull() ?: return null
    val maximum = match.groupValues[2].toIntOrNull()?.takeIf { it > 0 } ?: return null
    return AssistantLiveUpdateProgress(
        current = current.coerceIn(0, maximum),
        maximum = maximum,
    )
}

internal fun assistantLiveUpdateChipText(detail: String): String =
    assistantLiveUpdateProgress(detail)?.let { "${it.current}/${it.maximum}" } ?: "执行中"

/**
 * Keeps accepted assistant work alive after the voice popup closes and exposes progress through a
 * notification. Public web comparison and code work can stay fully in the background. GUI tasks
 * prefer an isolated Root/Shizuku display and transparently fall back to the physical display for
 * camera/call flows or when the device rejects virtual-display execution.
 */
@AndroidEntryPoint
class AssistantTaskForegroundService : Service() {
    @Inject lateinit var conversationRunner: AssistantConversationRunner

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }
    private val mainHandler = Handler(Looper.getMainLooper())
    private var running = false
    private var runningJob: Job? = null
    private var activeNotificationId: Int? = null
    private var activeTaskTitle: String = "Murong 助手任务"
    private var activeTaskKind: AssistantTaskKind? = null
    private var fallbackOverlayView: View? = null
    private var fallbackOverlayTaskId: String? = null
    private var fallbackOverlayHiddenByUser = false
    private var fallbackOverlayExpanded = false
    private var fallbackOverlayCompleted = false
    private var cpuWakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val startIntent = intent ?: run {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (startIntent.action == ACTION_HANDOFF_DISPLAY) {
            handoffDisplayToMain(
                startId = startId,
                notificationId = startIntent.getIntExtra(EXTRA_NOTIFICATION_ID, -1),
            )
            return START_NOT_STICKY
        }
        if (startIntent.action == ACTION_CANCEL_TASK) {
            cancelRunningTask(
                startId = startId,
                requestedNotificationId = startIntent.getIntExtra(EXTRA_NOTIFICATION_ID, -1),
            )
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
                    notificationId = notificationId,
                    title = taskTitle(routeKind),
                    detail = "已有助手任务正在运行，本次没有重复执行",
                    ongoing = false,
                ),
            )
            return START_NOT_STICKY
        }
        clearRetainedReviewNotification(exceptNotificationId = notificationId)
        val route = AssistantRequestRoute(
            kind = routeKind,
            runWithNotification = true,
            mayTakeOverScreen = routeKind == AssistantTaskKind.PHONE_FOREGROUND,
            label = taskTitle(routeKind),
        )
        activeTaskKind = routeKind
        if (routeKind == AssistantTaskKind.PHONE_FOREGROUND) {
            AssistantOffscreenTaskState.begin(
                request = taskText,
                title = taskTitle(routeKind),
                detail = startingDetail(routeKind),
            )
        }
        ServiceCompat.startForeground(
            this,
            notificationId,
            buildNotification(
                notificationId = notificationId,
                title = taskTitle(routeKind),
                detail = startingDetail(routeKind),
                ongoing = true,
            ),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        acquireTaskWakeLock()
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
        activeNotificationId = notificationId
        activeTaskTitle = taskTitle(routeKind)
        runningJob = serviceScope.launch {
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
                        if (running) {
                            publishProgress(
                                notificationId = notificationId,
                                overlayTaskId = overlayTaskId,
                                title = taskTitle(routeKind),
                                detail = detail,
                                ongoing = true,
                                history = progressHistory,
                            )
                        }
                    }
                AssistantTaskKind.BACKGROUND_CODE ->
                    conversationRunner.runCodeAndAwait(modelInput)
                else -> conversationRunner.runMainAndAwait(modelInput)
            }
            if (!running) return@launch
            val detail = when {
                !result.accepted -> result.error ?: "任务未被接受"
                result.needsAttention -> result.responseText.trim()
                    .takeIf(String::isNotBlank)
                    ?.take(NOTIFICATION_TEXT_LIMIT)
                    ?: "任务需要你的确认，点此打开对话继续"
                !result.error.isNullOrBlank() -> "任务失败：${result.error}"
                result.responseText.isNotBlank() -> result.responseText.take(NOTIFICATION_TEXT_LIMIT)
                else -> "任务已完成，点此查看对话"
            }
            val retainedDisplay = routeKind == AssistantTaskKind.PHONE_FOREGROUND &&
                conversationRunner.hasRetainedPhoneDisplay()
            if (routeKind == AssistantTaskKind.PHONE_FOREGROUND) {
                AssistantOffscreenTaskState.update(
                    title = taskTitle(routeKind),
                    detail = detail,
                    active = false,
                    displayAvailable = retainedDisplay,
                )
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
            if (
                routeKind == AssistantTaskKind.PHONE_FOREGROUND &&
                result.needsAttention &&
                listOf("起送", "凑单", "优惠券").any(detail::contains)
            ) {
                mainHandler.post {
                    runCatching {
                        startActivity(
                            AssistantVoicePopupEntry.launchIntent(
                                this@AssistantTaskForegroundService,
                                followUpPrompt = detail,
                                followUpTaskContext = taskText,
                            ),
                        )
                    }.onFailure { error ->
                        Log.w(TAG, "Unable to open voice follow-up: ${error.message}")
                    }
                }
            }
            running = false
            runningJob = null
            activeNotificationId = null
            activeTaskKind = null
            releaseTaskWakeLock()
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // A process-manager/service teardown can bypass the notification's explicit Stop action.
        // Do not leave a Root-owned virtual display or native inference lease behind in that case.
        if (running) {
            running = false
            conversationRunner.cancelCurrent()
            runningJob?.cancel()
            runningJob = null
            activeNotificationId = null
            activeTaskKind = null
        }
        removeFallbackProgressOverlay()
        releaseTaskWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun finishNotification(notificationId: Int, title: String, detail: String) {
        stopForeground(STOP_FOREGROUND_DETACH)
        notificationManager?.notify(
            notificationId,
            buildNotification(
                notificationId = notificationId,
                title = title,
                detail = detail,
                ongoing = false,
                retainForReview = true,
            ),
        )
        rememberRetainedReviewNotification(notificationId)
    }

    private fun rememberRetainedReviewNotification(notificationId: Int) {
        getSharedPreferences(RETAINED_REVIEW_PREFERENCES, MODE_PRIVATE)
            .edit()
            .putInt(KEY_RETAINED_REVIEW_NOTIFICATION_ID, notificationId)
            .apply()
    }

    private fun clearRetainedReviewNotification(exceptNotificationId: Int? = null) {
        // Old builds did not persist the retained id, so also sweep this app's own assistant
        // channel when a new task starts. This leaves unrelated Murong notifications untouched.
        notificationManager?.activeNotifications
            ?.filter { statusBarNotification ->
                statusBarNotification.id != exceptNotificationId &&
                    statusBarNotification.notification.channelId == CHANNEL_ID
            }
            ?.forEach { statusBarNotification ->
                notificationManager?.cancel(statusBarNotification.id)
            }
        val preferences = getSharedPreferences(RETAINED_REVIEW_PREFERENCES, MODE_PRIVATE)
        val retainedId = preferences.getInt(KEY_RETAINED_REVIEW_NOTIFICATION_ID, -1)
        if (retainedId < 0 || retainedId == exceptNotificationId) return
        notificationManager?.cancel(retainedId)
        preferences.edit().remove(KEY_RETAINED_REVIEW_NOTIFICATION_ID).apply()
    }

    private fun forgetRetainedReviewNotification(notificationId: Int) {
        val preferences = getSharedPreferences(RETAINED_REVIEW_PREFERENCES, MODE_PRIVATE)
        if (preferences.getInt(KEY_RETAINED_REVIEW_NOTIFICATION_ID, -1) == notificationId) {
            preferences.edit().remove(KEY_RETAINED_REVIEW_NOTIFICATION_ID).apply()
        }
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
        if (activeTaskKind == AssistantTaskKind.PHONE_FOREGROUND) {
            AssistantOffscreenTaskState.update(
                title = title,
                detail = normalized,
                active = ongoing,
                displayAvailable = conversationRunner.hasRetainedPhoneDisplay(),
                progressEntry = normalized,
            )
        }
        if (history.lastOrNull() != normalized) {
            history.lastOrNull()?.takeIf { previous ->
                shouldReplaceAssistantProgress(previous, normalized)
            }?.let { history.removeLast() }
            history.addLast(normalized)
            while (history.size > MAX_OVERLAY_HISTORY_LINES) history.removeFirst()
        }
        notificationManager?.notify(
            notificationId,
            buildNotification(
                notificationId = notificationId,
                title = title,
                detail = normalized,
                ongoing = ongoing,
                retainForReview = !ongoing,
            ),
        )
        // Overlays have limited height. Put the current step first so it cannot be clipped by
        // earlier entries while the full chronological trace remains available in the chat.
        val overlayDetail = history.asReversed().joinToString("\n\n")
        val shownByAccessibility = AndroidGuiAccessibilityService.showTaskProgressOverlay(
            taskId = overlayTaskId,
            title = title,
            detail = overlayDetail,
            completed = !ongoing,
        )
        if (shownByAccessibility) {
            removeFallbackProgressOverlay()
        } else {
            showFallbackProgressOverlay(
                taskId = overlayTaskId,
                title = title,
                detail = overlayDetail,
                completed = !ongoing,
            )
        }
    }

    private fun showFallbackProgressOverlay(
        taskId: String,
        title: String,
        detail: String,
        completed: Boolean,
    ) {
        if (!Settings.canDrawOverlays(this)) return
        mainHandler.post {
            if (fallbackOverlayTaskId != taskId) {
                removeFallbackProgressOverlayNow()
                fallbackOverlayTaskId = taskId
                fallbackOverlayHiddenByUser = false
            }
            if (fallbackOverlayHiddenByUser) return@post
            val root = fallbackOverlayView as? LinearLayout ?: createFallbackOverlay()
            root.findViewWithTag<TextView>(OVERLAY_TITLE_TAG)?.text =
                if (completed) "$title · 已结束" else title
            root.findViewWithTag<TextView>(OVERLAY_DETAIL_TAG)?.text = detail
            root.findViewWithTag<TextView>(OVERLAY_CAPSULE_TEXT_TAG)?.text =
                com.murong.agent.core.tool.taskOverlayCapsuleLabel(detail, completed)
            fallbackOverlayCompleted = completed
            root.background = if (fallbackOverlayExpanded) {
                overlayBackground(completed)
            } else {
                overlayCapsuleBackground(completed)
            }
        }
    }

    private fun createFallbackOverlay(): LinearLayout {
        val windowManager = getSystemService(WindowManager::class.java)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(10), dp(10))
            background = overlayBackground(completed = false)
            elevation = dp(10).toFloat()
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            tag = OVERLAY_TITLE_TAG
            setTextColor(Color.WHITE)
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val minimize = TextView(this).apply {
            text = "—"
            contentDescription = "最小化为小圆点"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 20f
            setPadding(dp(10), 0, dp(10), 0)
        }
        val close = TextView(this).apply {
            text = "×"
            contentDescription = "关闭手机操作进度"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 24f
            setPadding(dp(12), 0, dp(4), 0)
            setOnClickListener {
                fallbackOverlayHiddenByUser = true
                removeFallbackProgressOverlayNow(keepTaskIdentity = true)
            }
        }
        val detail = TextView(this).apply {
            tag = OVERLAY_DETAIL_TAG
            setTextColor(Color.rgb(235, 238, 245))
            textSize = 13f
            maxLines = 12
            setPadding(0, dp(6), dp(4), dp(2))
        }
        val fullContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val minimizedCapsule = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            contentDescription = "展开手机操作进度"
            setPadding(dp(12), 0, dp(10), 0)
        }
        val capsuleDot = TextView(this).apply {
            text = "●"
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(255, 107, 190))
            textSize = 18f
        }
        val capsuleText = TextView(this).apply {
            tag = OVERLAY_CAPSULE_TEXT_TAG
            text = "Murong · 执行中"
            setTextColor(Color.WHITE)
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 1
            gravity = Gravity.CENTER_VERTICAL
        }
        val capsuleExpand = TextView(this).apply {
            text = "›"
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(255, 174, 218))
            textSize = 22f
        }
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(minimize, LinearLayout.LayoutParams(dp(44), ViewGroup.LayoutParams.WRAP_CONTENT))
        header.addView(close, LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.WRAP_CONTENT))
        fullContent.addView(header, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        fullContent.addView(detail, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        minimizedCapsule.addView(
            capsuleDot,
            LinearLayout.LayoutParams(dp(24), ViewGroup.LayoutParams.MATCH_PARENT),
        )
        minimizedCapsule.addView(
            capsuleText,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f),
        )
        minimizedCapsule.addView(
            capsuleExpand,
            LinearLayout.LayoutParams(dp(20), ViewGroup.LayoutParams.MATCH_PARENT),
        )
        fullContent.visibility = View.GONE
        root.setPadding(0, 0, 0, 0)
        root.addView(fullContent)
        root.addView(
            minimizedCapsule,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)),
        )
        val params = WindowManager.LayoutParams(
            dp(184),
            dp(48),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(14)
            y = dp(70)
        }
        minimize.contentDescription = "收起为悬浮胶囊"
        minimize.setOnClickListener {
            fullContent.visibility = View.GONE
            minimizedCapsule.visibility = View.VISIBLE
            fallbackOverlayExpanded = false
            root.setPadding(0, 0, 0, 0)
            params.width = dp(184)
            params.height = dp(48)
            root.background = overlayCapsuleBackground(fallbackOverlayCompleted)
            runCatching { windowManager.updateViewLayout(root, params) }
        }
        minimizedCapsule.setOnClickListener {
            minimizedCapsule.visibility = View.GONE
            fullContent.visibility = View.VISIBLE
            fallbackOverlayExpanded = true
            root.setPadding(dp(14), dp(10), dp(10), dp(10))
            params.width = dp(330)
            params.height = WindowManager.LayoutParams.WRAP_CONTENT
            root.background = overlayBackground(fallbackOverlayCompleted)
            runCatching { windowManager.updateViewLayout(root, params) }
        }
        if (runCatching { windowManager.addView(root, params) }.isSuccess) {
            fallbackOverlayView = root
        }
        return root
    }

    private fun removeFallbackProgressOverlay() {
        mainHandler.post { removeFallbackProgressOverlayNow() }
    }

    private fun removeFallbackProgressOverlayNow(keepTaskIdentity: Boolean = false) {
        fallbackOverlayView?.let { view ->
            runCatching { getSystemService(WindowManager::class.java).removeViewImmediate(view) }
        }
        fallbackOverlayView = null
        fallbackOverlayExpanded = false
        fallbackOverlayCompleted = false
        if (!keepTaskIdentity) fallbackOverlayTaskId = null
    }

    private fun overlayBackground(completed: Boolean): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(if (completed) Color.rgb(31, 94, 72) else Color.rgb(31, 35, 48))
            setStroke(dp(1), Color.argb(110, 255, 255, 255))
        }

    private fun overlayCapsuleBackground(completed: Boolean): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(24).toFloat()
            setColor(if (completed) Color.rgb(31, 94, 72) else Color.rgb(31, 35, 48))
            setStroke(dp(2), Color.rgb(255, 107, 190))
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt().coerceAtLeast(1)

    private fun buildNotification(
        notificationId: Int,
        title: String,
        detail: String,
        ongoing: Boolean,
        retainForReview: Boolean = false,
    ): Notification {
        val openChatIntent = PendingIntent.getActivity(
            this,
            notificationId,
            AssistantTaskEntry.launchIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val openOffscreenIntent = PendingIntent.getActivity(
            this,
            notificationId + OFFSCREEN_REQUEST_CODE_OFFSET,
            AssistantOffscreenEntry.launchIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val openVoiceAssistantIntent = PendingIntent.getActivity(
            this,
            notificationId + VOICE_ASSISTANT_REQUEST_CODE_OFFSET,
            AssistantVoicePopupEntry.launchIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val isPhoneTask = activeTaskKind == AssistantTaskKind.PHONE_FOREGROUND
        val offscreenAvailable = isPhoneTask &&
            AssistantOffscreenTaskState.state.value?.displayAvailable == true
        val promotedOngoing = ongoing || retainForReview
        val cancelIntent = PendingIntent.getService(
            this,
            notificationId,
            Intent(this, AssistantTaskForegroundService::class.java).apply {
                action = ACTION_CANCEL_TASK
                putExtra(EXTRA_NOTIFICATION_ID, notificationId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.app_icon)
            .setContentTitle(title)
            .setContentText(detail)
            .setContentIntent(
                if (offscreenAvailable) {
                    openOffscreenIntent
                } else {
                    openChatIntent
                },
            )
            .setOngoing(promotedOngoing)
            .setOnlyAlertOnce(promotedOngoing)
            .setAutoCancel(!promotedOngoing)
            .setCategory(
                if (promotedOngoing) NotificationCompat.CATEGORY_PROGRESS
                else NotificationCompat.CATEGORY_STATUS,
            )

        if (promotedOngoing) {
            val progress = assistantLiveUpdateProgress(detail)
            val style = NotificationCompat.ProgressStyle()
            if (retainForReview) {
                style.setProgress(100)
            } else if (progress == null) {
                style.setProgressIndeterminate(true)
            } else {
                style.setProgress(
                    (progress.current * 100 / progress.maximum).coerceIn(0, 100),
                )
            }
            builder
                .setStyle(style)
                .setRequestPromotedOngoing(true)
                .setShortCriticalText(
                    if (retainForReview) "待确认" else assistantLiveUpdateChipText(detail),
                )
                // Android exposes an accent color for the promoted chip, but no independent
                // short-critical-text color. Keep colorized=false so the expanded notification
                // keeps the system/OEM background while ColorOS can tint its compact text/icon.
                .setColor(LIVE_UPDATE_ACCENT_COLOR)
                .setColorized(false)
            if (isPhoneTask) {
                val thirdAction = assistantPhoneLiveThirdAction(
                    offscreenAvailable = offscreenAvailable,
                    retainForReview = retainForReview,
                )
                builder
                    .addAction(0, AssistantOffscreenActionLabels.RETURN_CHAT, openChatIntent)
                    .addAction(
                        0,
                        AssistantOffscreenActionLabels.RETURN_VOICE_ASSISTANT,
                        openVoiceAssistantIntent,
                    )
                    .addAction(
                        0,
                        thirdAction.label,
                        when (thirdAction) {
                            AssistantPhoneLiveThirdAction.OPEN_OFFSCREEN -> openOffscreenIntent
                            AssistantPhoneLiveThirdAction.CONFIRM_AND_CLOSE,
                            AssistantPhoneLiveThirdAction.STOP,
                            -> cancelIntent
                        },
                    )
            } else {
                builder
                    .addAction(0, AssistantOffscreenActionLabels.RETURN_CHAT, openChatIntent)
                    .addAction(0, if (retainForReview) "确认并关闭" else "停止", cancelIntent)
            }
        } else {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            if (offscreenAvailable) {
                builder
                    .addAction(0, AssistantOffscreenActionLabels.RETURN_CHAT, openChatIntent)
                    .addAction(
                        0,
                        AssistantOffscreenActionLabels.RETURN_VOICE_ASSISTANT,
                        openVoiceAssistantIntent,
                    )
                    .addAction(
                        0,
                        AssistantOffscreenActionLabels.OFFSCREEN_CONTROLS,
                        openOffscreenIntent,
                    )
            }
        }
        return builder.build().also { notification ->
            if (promotedOngoing && Build.VERSION.SDK_INT >= 36) {
                Log.i(
                    TAG,
                    "LiveUpdate promotable=${notification.hasPromotableCharacteristics()} " +
                        "allowed=${notificationManager?.canPostPromotedNotifications()} " +
                        "flags=0x${notification.flags.toString(16)}",
                )
            }
        }
    }

    private fun cancelRunningTask(startId: Int, requestedNotificationId: Int) {
        val notificationId = activeNotificationId
        if (!running || notificationId == null) {
            conversationRunner.cancelCurrent()
            AssistantOffscreenTaskState.state.value?.let { snapshot ->
                AssistantOffscreenTaskState.update(
                    title = snapshot.title,
                    detail = "离屏已关闭",
                    active = false,
                    displayAvailable = false,
                )
            }
            if (requestedNotificationId >= 0) {
                notificationManager?.cancel(requestedNotificationId)
                forgetRetainedReviewNotification(requestedNotificationId)
            }
            releaseTaskWakeLock()
            stopSelf(startId)
            return
        }
        running = false
        conversationRunner.cancelCurrent()
        runningJob?.cancel()
        runningJob = null
        if (activeTaskKind == AssistantTaskKind.PHONE_FOREGROUND) {
            AssistantOffscreenTaskState.update(
                title = activeTaskTitle,
                detail = "任务已停止",
                active = false,
                displayAvailable = false,
            )
        }
        // "关闭离屏" is an explicit dismissal, so remove the promoted live update instead of
        // trying to mutate the OEM live card in place. ColorOS can otherwise retain the old
        // ongoing/actions payload even after the foreground-service flag has been cleared.
        stopForeground(STOP_FOREGROUND_REMOVE)
        notificationManager?.cancel(notificationId)
        forgetRetainedReviewNotification(notificationId)
        AndroidGuiAccessibilityService.dismissTaskProgressOverlay("assistant-$notificationId")
        removeFallbackProgressOverlay()
        activeNotificationId = null
        activeTaskKind = null
        releaseTaskWakeLock()
        stopSelf(startId)
    }

    private fun handoffDisplayToMain(startId: Int, notificationId: Int) {
        serviceScope.launch {
            val service = ShizukuSystemAccess.agentDisplayService()
            val displayId = service?.currentAgentDisplayId() ?: -1
            val handedOff = displayId >= 0 && runCatching {
                service?.handoffAgentDisplayToMain(displayId) == true
            }.getOrDefault(false)
            if (handedOff) {
                val offscreenTitle = AssistantOffscreenTaskState.state.value?.title
                    ?.takeIf(String::isNotBlank)
                    ?: activeTaskTitle
                running = false
                conversationRunner.cancelCurrent()
                runningJob?.cancel()
                runningJob = null
                AssistantOffscreenTaskState.state.value?.let { snapshot ->
                    AssistantOffscreenTaskState.update(
                        title = snapshot.title,
                        detail = "已切换到主屏原生接管",
                        active = false,
                        displayAvailable = false,
                    )
                }
                if (notificationId >= 0) {
                    notificationManager?.notify(
                        notificationId,
                        buildNotification(
                            notificationId = notificationId,
                            title = offscreenTitle,
                            detail = "已切换到主屏原生接管",
                            ongoing = false,
                            retainForReview = true,
                        ),
                    )
                    rememberRetainedReviewNotification(notificationId)
                }
                mainHandler.post {
                    Toast.makeText(this@AssistantTaskForegroundService, "已进入主屏原生接管", Toast.LENGTH_SHORT).show()
                }
            } else {
                mainHandler.post {
                    Toast.makeText(this@AssistantTaskForegroundService, "当前没有可接管的离屏任务", Toast.LENGTH_SHORT).show()
                    runCatching {
                        startActivity(AssistantOffscreenEntry.launchIntent(this@AssistantTaskForegroundService))
                    }
                }
            }
            activeNotificationId = null
            activeTaskKind = null
            releaseTaskWakeLock()
            stopSelf(startId)
        }
    }

    private fun acquireTaskWakeLock() {
        if (cpuWakeLock?.isHeld == true) return
        val powerManager = getSystemService(PowerManager::class.java) ?: return
        cpuWakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            TASK_WAKE_LOCK_TAG,
        ).apply {
            setReferenceCounted(false)
            acquire(TASK_WAKE_LOCK_TIMEOUT_MILLIS)
        }
    }

    private fun releaseTaskWakeLock() {
        val wakeLock = cpuWakeLock
        cpuWakeLock = null
        if (wakeLock?.isHeld == true) runCatching { wakeLock.release() }
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
        AssistantTaskKind.PHONE_FOREGROUND -> "即将开始手机操作；优先使用隔离屏，必要时回退主屏"
        else -> "任务已接收，正在准备…"
    }

    private fun runningDetail(kind: AssistantTaskKind): String = when (kind) {
        AssistantTaskKind.BACKGROUND_WEB_RESEARCH ->
            "正在后台检索公开网页/API，不会打开外卖 App 或影响你使用手机"
        AssistantTaskKind.BACKGROUND_CODE -> "正在后台调用主模型处理代码或系统任务"
        AssistantTaskKind.PHONE_FOREGROUND -> "正在执行手机操作；悬浮窗会持续显示当前步骤"
        else -> "任务正在运行"
    }

    companion object {
        private const val CHANNEL_ID = "assistant_background_tasks"
        private const val EXTRA_TASK_TEXT = "task_text"
        private const val EXTRA_ROUTE_KIND = "route_kind"
        private const val EXTRA_NOTIFICATION_ID = "notification_id"
        internal const val ACTION_CANCEL_TASK = "com.murong.agent.action.CANCEL_ASSISTANT_TASK"
        internal const val ACTION_HANDOFF_DISPLAY = "com.murong.agent.action.HANDOFF_ASSISTANT_DISPLAY"
        private const val TAG = "AssistantTaskService"
        private const val NOTIFICATION_TEXT_LIMIT = 900
        private const val MAX_OVERLAY_HISTORY_LINES = 30
        private const val OFFSCREEN_REQUEST_CODE_OFFSET = 100_000
        private const val VOICE_ASSISTANT_REQUEST_CODE_OFFSET = 300_000
        private const val TASK_WAKE_LOCK_TAG = "Murong:AssistantTask"
        private const val TASK_WAKE_LOCK_TIMEOUT_MILLIS = 45 * 60 * 1_000L
        private const val RETAINED_REVIEW_PREFERENCES = "assistant_retained_review"
        private const val KEY_RETAINED_REVIEW_NOTIFICATION_ID = "notification_id"
        private val LIVE_UPDATE_ACCENT_COLOR = Color.rgb(255, 91, 177)
        private const val OVERLAY_TITLE_TAG = "assistant_overlay_title"
        private const val OVERLAY_DETAIL_TAG = "assistant_overlay_detail"
        private const val OVERLAY_CAPSULE_TEXT_TAG = "assistant_overlay_capsule_text"
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
