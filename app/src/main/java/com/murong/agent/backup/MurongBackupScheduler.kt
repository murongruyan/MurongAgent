package com.murong.agent.backup

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.murong.agent.core.config.ConfigRepository
import com.murong.agent.core.mcp.McpRegistry
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

object MurongBackupScheduler {
    private const val UNIQUE_WORK_NAME = "murong_daily_complete_backup"

    fun applySettings(context: Context, enabled: Boolean) {
        if (!enabled) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE_WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<MurongDailyBackupWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(delayToNextHour(3), TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiresStorageNotLow(true).build())
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    private fun delayToNextHour(hour: Int): Long {
        val now = LocalDateTime.now()
        var next = now.withHour(hour).withMinute(0).withSecond(0).withNano(0)
        if (!next.isAfter(now)) next = next.plusDays(1)
        return Duration.between(now, next).toMillis().coerceAtLeast(0L)
    }
}

class MurongDailyBackupWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val manager = MurongBackupManager(
            applicationContext,
            ConfigRepository(applicationContext),
            McpRegistry(applicationContext)
        )
        return runCatching { manager.createAutomaticBackupIfNeeded() }
            .fold(
                onSuccess = { Result.success() },
                onFailure = { error ->
                    MurongBackupPreferences(applicationContext).markResult(
                        timestamp = System.currentTimeMillis(),
                        message = error.message ?: "每日备份失败",
                        failed = true
                    )
                    Result.retry()
                }
            )
    }
}
