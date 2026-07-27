package com.murong.agent.ui.assistant

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/** Android's active global voice interactor for Murong. */
class MurongVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        activeService = this
        if (Build.VERSION.SDK_INT >= 36) {
            // Android owns the invocation/disclosure animation. This is the system-level
            // "闪烁屏幕" behavior exposed by current assistant settings. Some Android 16 OEM
            // builds report API 36 while omitting this late-added framework method, so never
            // link the optional call directly.
            runCatching {
                VoiceInteractionService::class.java
                    .getMethod(
                        "setInvocationEffectEnabled",
                        Boolean::class.javaPrimitiveType,
                    )
                    .invoke(this, true)
            }
        }
    }

    override fun onShutdown() {
        if (activeService === this) activeService = null
        super.onShutdown()
    }

    companion object {
        const val EXTRA_INVOCATION_SOURCE = "com.murong.agent.extra.ASSISTANT_SOURCE"

        @Volatile
        private var activeService: MurongVoiceInteractionService? = null

        fun requestShow(context: Context, source: String): Boolean {
            val component = ComponentName(context, MurongVoiceInteractionService::class.java)
            val service = activeService
            if (service != null && VoiceInteractionService.isActiveService(context, component)) {
                service.showSession(
                    Bundle().apply { putString(EXTRA_INVOCATION_SOURCE, source) },
                    VoiceInteractionSession.SHOW_WITH_ASSIST or
                        VoiceInteractionSession.SHOW_WITH_SCREENSHOT,
                )
                return true
            }

            // Explicit user actions (desktop shortcut, accessibility shortcut, settings test)
            // still get the same translucent popup if the OEM has not bound the role yet.
            runCatching {
                context.startActivity(
                    MurongAssistActivity.createIntent(context, source)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            return false
        }

        fun dismissCurrentSession() {
            MurongVoiceInteractionSession.dismissCurrent()
        }
    }
}

class MurongVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession =
        MurongVoiceInteractionSession(this)
}

private class MurongVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {
    override fun onCreate() {
        super.onCreate()
        currentSession = this
        // Murong renders its own translucent bottom panel Activity. Suppress the otherwise empty
        // framework session window while retaining native assist/screenshot delivery.
        setUiEnabled(false)
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        currentSession = this
        VoiceAssistantScreenContext.begin(showFlags, getUserDisabledShowContext())
        val source = args?.getString(MurongVoiceInteractionService.EXTRA_INVOCATION_SOURCE)
            ?: "system_assist"
        runCatching {
            startAssistantActivity(MurongAssistActivity.createIntent(context, source))
        }.onFailure {
            context.startActivity(
                MurongAssistActivity.createIntent(context, source)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    override fun onHandleAssist(state: VoiceInteractionSession.AssistState) {
        super.onHandleAssist(state)
        if (state.isFocused || state.index == 0) {
            VoiceAssistantScreenContext.updateStructure(state.assistStructure)
        }
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        super.onHandleScreenshot(screenshot)
        VoiceAssistantScreenContext.updateScreenshot(screenshot)
    }

    override fun onHide() {
        super.onHide()
    }

    override fun onDestroy() {
        if (currentSession === this) currentSession = null
        VoiceAssistantScreenContext.clear()
        super.onDestroy()
    }

    companion object {
        @Volatile
        private var currentSession: MurongVoiceInteractionSession? = null

        fun dismissCurrent() {
            currentSession?.finish()
            currentSession = null
        }
    }
}

internal fun isMurongVoiceInteractionServiceActive(context: Context): Boolean =
    VoiceInteractionService.isActiveService(
        context,
        ComponentName(context, MurongVoiceInteractionService::class.java),
    )
