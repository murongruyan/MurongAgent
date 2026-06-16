package com.murong.agent

import android.app.Application
import com.murong.agent.common.toolchain.ToolchainManager
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class MurongApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ToolchainManager.initialize(this)
        ToolchainManager.warmUpAsync()
    }
}
