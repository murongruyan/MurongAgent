package com.murong.agent

import android.app.Application
import com.murong.agent.analytics.UsageAnalyticsTracker
import com.murong.agent.common.toolchain.ToolchainManager
import com.murong.agent.backup.MurongBackupPreferences
import com.murong.agent.backup.MurongBackupScheduler
import com.murong.agent.core.tool.BuiltinVisionModels
import com.murong.agent.core.doctor.installPendingCrashHandler
import com.murong.agent.core.tool.BuiltinVisionRuntime
import com.murong.agent.shizuku.ShizukuSystemAccess
import com.murong.agent.ui.settings.DeviceExecutionProfilePreferences
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class MurongApp : Application() {
    private var usageAnalyticsTracker: UsageAnalyticsTracker? = null

    override fun onCreate() {
        super.onCreate()
        installPendingCrashHandler(this)
        BuiltinVisionModels.removeRetiredModelArtifacts(this)
        BuiltinVisionRuntime.initialize(this)
        DeviceExecutionProfilePreferences.initialize(this)
        ShizukuSystemAccess.initialize(this)
        ToolchainManager.initialize(this)
        ToolchainManager.warmUpAsync()
        MurongBackupScheduler.applySettings(
            context = this,
            enabled = MurongBackupPreferences(this).settings().dailyBackupEnabled
        )
        usageAnalyticsTracker = UsageAnalyticsTracker(this).also {
            registerActivityLifecycleCallbacks(it)
        }
    }
}
