package com.murong.agent.backup

import android.content.Context

internal class MurongBackupPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun settings(): MurongBackupSettingsSnapshot = MurongBackupSettingsSnapshot(
        dailyBackupEnabled = preferences.getBoolean(KEY_DAILY_ENABLED, false),
        maxBackupCount = preferences.getInt(KEY_MAX_COUNT, DEFAULT_MAX_COUNT).coerceIn(1, MAX_COUNT)
    )

    fun restoreSettings(settings: MurongBackupSettingsSnapshot) {
        preferences.edit()
            .putBoolean(KEY_DAILY_ENABLED, settings.dailyBackupEnabled)
            .putInt(KEY_MAX_COUNT, settings.maxBackupCount.coerceIn(1, MAX_COUNT))
            .commit()
    }

    fun lastAutomaticBackupDay(): String? = preferences.getString(KEY_LAST_AUTOMATIC_DAY, null)

    fun markAutomaticBackupDay(day: String) {
        preferences.edit().putString(KEY_LAST_AUTOMATIC_DAY, day).commit()
    }

    fun markResult(timestamp: Long, message: String, failed: Boolean) {
        preferences.edit()
            .putLong(KEY_LAST_RESULT_AT, timestamp)
            .putString(KEY_LAST_RESULT_MESSAGE, message.take(500))
            .putBoolean(KEY_LAST_RESULT_FAILED, failed)
            .commit()
    }

    fun lastResultAt(): Long? = preferences.getLong(KEY_LAST_RESULT_AT, 0L).takeIf { it > 0L }
    fun lastResultMessage(): String? = preferences.getString(KEY_LAST_RESULT_MESSAGE, null)
    fun lastResultFailed(): Boolean = preferences.getBoolean(KEY_LAST_RESULT_FAILED, false)

    private companion object {
        const val PREFERENCES_NAME = "murong_backup"
        const val KEY_DAILY_ENABLED = "daily_enabled"
        const val KEY_MAX_COUNT = "max_count"
        const val KEY_LAST_AUTOMATIC_DAY = "last_automatic_day"
        const val KEY_LAST_RESULT_AT = "last_result_at"
        const val KEY_LAST_RESULT_MESSAGE = "last_result_message"
        const val KEY_LAST_RESULT_FAILED = "last_result_failed"
        const val DEFAULT_MAX_COUNT = 7
        const val MAX_COUNT = 100
    }
}
