package com.murong.agent

import android.app.Application
import com.murong.agent.analytics.UsageAnalyticsTracker
import com.murong.agent.common.toolchain.ToolchainManager
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class MurongApp : Application() {
    private var usageAnalyticsTracker: UsageAnalyticsTracker? = null

    override fun onCreate() {
        super.onCreate()
        ToolchainManager.initialize(this)
        ToolchainManager.warmUpAsync()
        usageAnalyticsTracker = UsageAnalyticsTracker(this).also {
            registerActivityLifecycleCallbacks(it)
        }
    }
}
