package com.murong.agent.ui.assistant

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.murong.agent.R
import kotlin.math.max

internal enum class TimekeepingCommand {
    START,
    PAUSE,
    RESUME,
    STOP,
    RESET,
    STATUS,
}

internal fun parseTimekeepingCommand(text: String): TimekeepingCommand {
    val normalized = text.trim().lowercase()
    return when {
        listOf("清零", "重置", "归零").any(normalized::contains) -> TimekeepingCommand.RESET
        "暂停" in normalized -> TimekeepingCommand.PAUSE
        listOf("继续", "恢复").any(normalized::contains) -> TimekeepingCommand.RESUME
        listOf("关闭", "取消", "停止", "结束", "终止", "关掉", "清除")
            .any(normalized::contains) -> TimekeepingCommand.STOP
        listOf("多久", "多少", "剩余", "还有", "状态", "查看", "查询")
            .any(normalized::contains) -> TimekeepingCommand.STATUS
        else -> TimekeepingCommand.START
    }
}

internal fun formatStopwatchDuration(durationMillis: Long): String {
    val value = max(0L, durationMillis)
    val totalTenths = value / 100L
    val tenths = totalTenths % 10L
    val totalSeconds = totalTenths / 10L
    val seconds = totalSeconds % 60L
    val totalMinutes = totalSeconds / 60L
    val minutes = totalMinutes % 60L
    val hours = totalMinutes / 60L
    return if (hours > 0L) {
        "%d:%02d:%02d.%d".format(hours, minutes, seconds, tenths)
    } else {
        "%02d:%02d.%d".format(minutes, seconds, tenths)
    }
}

internal object AssistantTimekeepingActions {
    fun handleCountdown(context: Context, text: String): String {
        val appContext = context.applicationContext
        return when (parseTimekeepingCommand(text)) {
            TimekeepingCommand.START -> {
                val seconds = parseDurationSeconds(text)
                    ?: return "请告诉我要计时多久，例如“计时 10 分钟”。"
                startCountdown(appContext, seconds)
                "好，已在后台开始${formatWholeSeconds(seconds.toLong())}倒计时，不会打开或占用主屏。"
            }

            TimekeepingCommand.PAUSE -> pauseCountdown(appContext)
            TimekeepingCommand.RESUME -> resumeCountdown(appContext)
            TimekeepingCommand.STOP,
            TimekeepingCommand.RESET -> stopCountdown(appContext)
            TimekeepingCommand.STATUS -> countdownStatus(appContext)
        }
    }

    fun handleStopwatch(context: Context, text: String): String {
        val appContext = context.applicationContext
        val prefs = prefs(appContext)
        val now = SystemClock.elapsedRealtime()
        return when (parseTimekeepingCommand(text)) {
            TimekeepingCommand.START -> {
                prefs.edit()
                    .putString(KEY_STOPWATCH_STATUS, STATUS_RUNNING)
                    .putLong(KEY_STOPWATCH_STARTED, now)
                    .putLong(KEY_STOPWATCH_ACCUMULATED, 0L)
                    .apply()
                showStopwatchNotification(appContext, now, 0L, running = true)
                "秒表已在后台开始，不会打开或占用主屏。"
            }

            TimekeepingCommand.PAUSE -> {
                if (prefs.getString(KEY_STOPWATCH_STATUS, STATUS_STOPPED) != STATUS_RUNNING) {
                    "秒表当前没有运行。"
                } else {
                    val elapsed = stopwatchElapsed(prefs, now)
                    prefs.edit()
                        .putString(KEY_STOPWATCH_STATUS, STATUS_PAUSED)
                        .putLong(KEY_STOPWATCH_ACCUMULATED, elapsed)
                        .remove(KEY_STOPWATCH_STARTED)
                        .apply()
                    showStopwatchNotification(appContext, now, elapsed, running = false)
                    "秒表已暂停在 ${formatStopwatchDuration(elapsed)}。"
                }
            }

            TimekeepingCommand.RESUME -> {
                if (prefs.getString(KEY_STOPWATCH_STATUS, STATUS_STOPPED) != STATUS_PAUSED) {
                    "没有已暂停的秒表可继续。"
                } else {
                    val accumulated = prefs.getLong(KEY_STOPWATCH_ACCUMULATED, 0L)
                    prefs.edit()
                        .putString(KEY_STOPWATCH_STATUS, STATUS_RUNNING)
                        .putLong(KEY_STOPWATCH_STARTED, now)
                        .apply()
                    showStopwatchNotification(appContext, now, accumulated, running = true)
                    "秒表已从 ${formatStopwatchDuration(accumulated)} 继续。"
                }
            }

            TimekeepingCommand.STOP -> {
                val elapsed = stopwatchElapsed(prefs, now)
                clearStopwatch(appContext)
                if (elapsed > 0L) {
                    "秒表已停止，本次计时 ${formatStopwatchDuration(elapsed)}。"
                } else {
                    "秒表当前没有运行。"
                }
            }

            TimekeepingCommand.RESET -> {
                clearStopwatch(appContext)
                "秒表已清零。"
            }

            TimekeepingCommand.STATUS -> {
                val status = prefs.getString(KEY_STOPWATCH_STATUS, STATUS_STOPPED)
                val elapsed = stopwatchElapsed(prefs, now)
                when (status) {
                    STATUS_RUNNING -> "秒表正在运行：${formatStopwatchDuration(elapsed)}。"
                    STATUS_PAUSED -> "秒表已暂停：${formatStopwatchDuration(elapsed)}。"
                    else -> "秒表当前没有运行。"
                }
            }
        }
    }

    fun onReceiverAction(context: Context, action: String?) {
        when (action) {
            ACTION_COUNTDOWN_EXPIRED -> finishCountdownIfDue(context.applicationContext)
            ACTION_COUNTDOWN_STOP -> stopCountdown(context.applicationContext)
            ACTION_STOPWATCH_STOP -> clearStopwatch(context.applicationContext)
        }
    }

    private fun startCountdown(context: Context, seconds: Int) {
        val now = SystemClock.elapsedRealtime()
        val duration = seconds.coerceIn(1, 86_400) * 1_000L
        prefs(context).edit()
            .putString(KEY_COUNTDOWN_STATUS, STATUS_RUNNING)
            .putLong(KEY_COUNTDOWN_END, now + duration)
            .putLong(KEY_COUNTDOWN_REMAINING, duration)
            .apply()
        scheduleCountdown(context, now + duration)
        showCountdownNotification(context, duration, running = true)
    }

    private fun pauseCountdown(context: Context): String {
        val prefs = prefs(context)
        if (prefs.getString(KEY_COUNTDOWN_STATUS, STATUS_STOPPED) != STATUS_RUNNING) {
            return "当前没有正在运行的倒计时。"
        }
        val remaining = (prefs.getLong(KEY_COUNTDOWN_END, 0L) - SystemClock.elapsedRealtime())
            .coerceAtLeast(0L)
        cancelCountdownAlarm(context)
        prefs.edit()
            .putString(KEY_COUNTDOWN_STATUS, STATUS_PAUSED)
            .putLong(KEY_COUNTDOWN_REMAINING, remaining)
            .remove(KEY_COUNTDOWN_END)
            .apply()
        showCountdownNotification(context, remaining, running = false)
        return "倒计时已暂停，还剩 ${formatWholeMillis(remaining)}。"
    }

    private fun resumeCountdown(context: Context): String {
        val prefs = prefs(context)
        if (prefs.getString(KEY_COUNTDOWN_STATUS, STATUS_STOPPED) != STATUS_PAUSED) {
            return "没有已暂停的倒计时可继续。"
        }
        val remaining = prefs.getLong(KEY_COUNTDOWN_REMAINING, 0L)
        if (remaining <= 0L) {
            clearCountdown(context)
            return "这个倒计时已经结束。"
        }
        val end = SystemClock.elapsedRealtime() + remaining
        prefs.edit()
            .putString(KEY_COUNTDOWN_STATUS, STATUS_RUNNING)
            .putLong(KEY_COUNTDOWN_END, end)
            .apply()
        scheduleCountdown(context, end)
        showCountdownNotification(context, remaining, running = true)
        return "倒计时已继续，还剩 ${formatWholeMillis(remaining)}。"
    }

    private fun stopCountdown(context: Context): String {
        val existed = prefs(context).getString(KEY_COUNTDOWN_STATUS, STATUS_STOPPED) != STATUS_STOPPED
        clearCountdown(context)
        return if (existed) "倒计时已关闭并清除。" else "当前没有倒计时。"
    }

    private fun countdownStatus(context: Context): String {
        val prefs = prefs(context)
        val status = prefs.getString(KEY_COUNTDOWN_STATUS, STATUS_STOPPED)
        val remaining = when (status) {
            STATUS_RUNNING -> prefs.getLong(KEY_COUNTDOWN_END, 0L) - SystemClock.elapsedRealtime()
            STATUS_PAUSED -> prefs.getLong(KEY_COUNTDOWN_REMAINING, 0L)
            else -> 0L
        }.coerceAtLeast(0L)
        return when {
            status == STATUS_RUNNING && remaining > 0L ->
                "倒计时正在运行，还剩 ${formatWholeMillis(remaining)}。"
            status == STATUS_PAUSED && remaining > 0L ->
                "倒计时已暂停，还剩 ${formatWholeMillis(remaining)}。"
            else -> {
                if (status != STATUS_STOPPED) clearCountdown(context)
                "当前没有倒计时。"
            }
        }
    }

    private fun finishCountdownIfDue(context: Context) {
        val prefs = prefs(context)
        if (prefs.getString(KEY_COUNTDOWN_STATUS, STATUS_STOPPED) != STATUS_RUNNING) return
        val remaining = prefs.getLong(KEY_COUNTDOWN_END, 0L) - SystemClock.elapsedRealtime()
        if (remaining > 1_000L) {
            scheduleCountdown(context, SystemClock.elapsedRealtime() + remaining)
            return
        }
        clearCountdown(context, cancelDoneNotification = false)
        createChannels(context)
        notificationManager(context).notify(
            COUNTDOWN_DONE_NOTIFICATION_ID,
            NotificationCompat.Builder(context, COUNTDOWN_DONE_CHANNEL)
                .setSmallIcon(R.drawable.app_icon)
                .setContentTitle("Murong 倒计时结束")
                .setContentText("设定的倒计时已经结束。")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun scheduleCountdown(context: Context, triggerElapsed: Long) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val pending = countdownPendingIntent(context)
        if (Build.VERSION.SDK_INT >= 31 && alarmManager.canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerElapsed,
                pending,
            )
        } else {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerElapsed,
                pending,
            )
        }
    }

    private fun cancelCountdownAlarm(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(countdownPendingIntent(context))
    }

    private fun clearCountdown(
        context: Context,
        cancelDoneNotification: Boolean = true,
    ) {
        cancelCountdownAlarm(context)
        prefs(context).edit()
            .putString(KEY_COUNTDOWN_STATUS, STATUS_STOPPED)
            .remove(KEY_COUNTDOWN_END)
            .remove(KEY_COUNTDOWN_REMAINING)
            .apply()
        notificationManager(context).cancel(COUNTDOWN_RUNNING_NOTIFICATION_ID)
        if (cancelDoneNotification) {
            notificationManager(context).cancel(COUNTDOWN_DONE_NOTIFICATION_ID)
        }
    }

    private fun clearStopwatch(context: Context) {
        prefs(context).edit()
            .putString(KEY_STOPWATCH_STATUS, STATUS_STOPPED)
            .remove(KEY_STOPWATCH_STARTED)
            .remove(KEY_STOPWATCH_ACCUMULATED)
            .apply()
        notificationManager(context).cancel(STOPWATCH_NOTIFICATION_ID)
    }

    private fun stopwatchElapsed(
        prefs: android.content.SharedPreferences,
        now: Long,
    ): Long {
        val accumulated = prefs.getLong(KEY_STOPWATCH_ACCUMULATED, 0L)
        return if (prefs.getString(KEY_STOPWATCH_STATUS, STATUS_STOPPED) == STATUS_RUNNING) {
            accumulated + (now - prefs.getLong(KEY_STOPWATCH_STARTED, now)).coerceAtLeast(0L)
        } else {
            accumulated
        }
    }

    private fun showCountdownNotification(context: Context, remaining: Long, running: Boolean) {
        if (!canNotify(context)) return
        createChannels(context)
        val stopIntent = actionPendingIntent(context, ACTION_COUNTDOWN_STOP, 2_002)
        val builder = NotificationCompat.Builder(context, TIMEKEEPING_CHANNEL)
            .setSmallIcon(R.drawable.app_icon)
            .setContentTitle(if (running) "Murong 倒计时" else "Murong 倒计时已暂停")
            .setContentText(if (running) "后台计时中" else "还剩 ${formatWholeMillis(remaining)}")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .addAction(0, "关闭", stopIntent)
        if (running) {
            builder
                .setWhen(System.currentTimeMillis() + remaining)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
        }
        notificationManager(context).notify(COUNTDOWN_RUNNING_NOTIFICATION_ID, builder.build())
    }

    private fun showStopwatchNotification(
        context: Context,
        now: Long,
        accumulated: Long,
        running: Boolean,
    ) {
        if (!canNotify(context)) return
        createChannels(context)
        val stopIntent = actionPendingIntent(context, ACTION_STOPWATCH_STOP, 2_003)
        val builder = NotificationCompat.Builder(context, TIMEKEEPING_CHANNEL)
            .setSmallIcon(R.drawable.app_icon)
            .setContentTitle(if (running) "Murong 秒表" else "Murong 秒表已暂停")
            .setContentText(if (running) "后台计时中" else formatStopwatchDuration(accumulated))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .addAction(0, "停止", stopIntent)
        if (running) {
            builder
                .setWhen(System.currentTimeMillis() - accumulated)
                .setUsesChronometer(true)
        }
        notificationManager(context).notify(STOPWATCH_NOTIFICATION_ID, builder.build())
    }

    private fun createChannels(context: Context) {
        notificationManager(context).createNotificationChannels(
            listOf(
                NotificationChannel(
                    TIMEKEEPING_CHANNEL,
                    "后台计时",
                    NotificationManager.IMPORTANCE_LOW,
                ),
                NotificationChannel(
                    COUNTDOWN_DONE_CHANNEL,
                    "倒计时结束提醒",
                    NotificationManager.IMPORTANCE_HIGH,
                ),
            ),
        )
    }

    private fun countdownPendingIntent(context: Context): PendingIntent = actionPendingIntent(
        context,
        ACTION_COUNTDOWN_EXPIRED,
        2_001,
    )

    private fun actionPendingIntent(context: Context, action: String, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, AssistantCountdownReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun notificationManager(context: Context) =
        context.getSystemService(NotificationManager::class.java)

    private fun canNotify(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun formatWholeMillis(millis: Long): String =
        formatWholeSeconds(((millis.coerceAtLeast(0L) + 999L) / 1_000L).coerceAtLeast(1L))

    private fun formatWholeSeconds(seconds: Long): String = buildString {
        val value = seconds.coerceAtLeast(0L)
        val hours = value / 3_600L
        val minutes = value % 3_600L / 60L
        val remainingSeconds = value % 60L
        if (hours > 0L) append(hours).append("小时")
        if (minutes > 0L) append(minutes).append("分钟")
        if (remainingSeconds > 0L || isEmpty()) append(remainingSeconds).append("秒")
    }

    private const val PREFS = "assistant_timekeeping"
    private const val KEY_COUNTDOWN_STATUS = "countdown_status"
    private const val KEY_COUNTDOWN_END = "countdown_end_elapsed"
    private const val KEY_COUNTDOWN_REMAINING = "countdown_remaining"
    private const val KEY_STOPWATCH_STATUS = "stopwatch_status"
    private const val KEY_STOPWATCH_STARTED = "stopwatch_started_elapsed"
    private const val KEY_STOPWATCH_ACCUMULATED = "stopwatch_accumulated"
    private const val STATUS_RUNNING = "running"
    private const val STATUS_PAUSED = "paused"
    private const val STATUS_STOPPED = "stopped"
    private const val TIMEKEEPING_CHANNEL = "assistant_timekeeping"
    private const val COUNTDOWN_DONE_CHANNEL = "assistant_countdown_done"
    private const val COUNTDOWN_RUNNING_NOTIFICATION_ID = 8_410
    private const val COUNTDOWN_DONE_NOTIFICATION_ID = 8_411
    private const val STOPWATCH_NOTIFICATION_ID = 8_412
    internal const val ACTION_COUNTDOWN_EXPIRED =
        "com.murong.agent.action.COUNTDOWN_EXPIRED"
    internal const val ACTION_COUNTDOWN_STOP =
        "com.murong.agent.action.COUNTDOWN_STOP"
    internal const val ACTION_STOPWATCH_STOP =
        "com.murong.agent.action.STOPWATCH_STOP"
}

class AssistantCountdownReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AssistantTimekeepingActions.onReceiverAction(context, intent.action)
    }
}
