package com.murong.agent

import android.app.Application
import com.murong.agent.analytics.UsageAnalyticsTracker
import com.murong.agent.common.toolchain.ToolchainManager
import com.murong.agent.core.doctor.installPendingCrashHandler
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class MurongApp : Application() {
    private var usageAnalyticsTracker: UsageAnalyticsTracker? = null

    override fun onCreate() {
        super.onCreate()
        installPendingCrashHandler(this)
        ToolchainManager.initialize(this)
        ToolchainManager.warmUpAsync()
        usageAnalyticsTracker = UsageAnalyticsTracker(this).also {
            registerActivityLifecycleCallbacks(it)
        }
    }
}
