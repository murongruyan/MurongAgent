package com.murong.agent.ui.settings

import android.content.Context
import com.murong.agent.core.tool.AndroidExecutionMode
import com.murong.agent.core.tool.AndroidSystemExecution

/** Persists the explicit execution lane without storing it alongside provider credentials. */
object DeviceExecutionProfilePreferences {
    private const val FILE_NAME = "murong_device_execution"
    private const val KEY_MODE = "preferred_execution_mode"

    fun initialize(context: Context) {
        AndroidSystemExecution.setPreferredMode(selected(context))
    }

    fun selected(context: Context): AndroidExecutionMode {
        val raw = context.applicationContext
            .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .getString(KEY_MODE, AndroidExecutionMode.AUTO.name)
        return runCatching { AndroidExecutionMode.valueOf(raw.orEmpty()) }
            .getOrDefault(AndroidExecutionMode.AUTO)
    }

    fun select(context: Context, mode: AndroidExecutionMode) {
        context.applicationContext
            .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MODE, mode.name)
            .apply()
        AndroidSystemExecution.setPreferredMode(mode)
    }
}
