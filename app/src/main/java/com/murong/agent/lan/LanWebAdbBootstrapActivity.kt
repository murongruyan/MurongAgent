package com.murong.agent.lan

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.murong.agent.R

class LanWebAdbBootstrapActivity : Activity() {
    private var pendingChallenge: ByteArray? = null
    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildConnectingView())
        pendingChallenge = if (intent?.action == LanWebContract.ACTION_ADB_PAIR_CHALLENGE) {
            runCatching {
                Base64.decode(
                    intent.getStringExtra(LanWebContract.EXTRA_ADB_CHALLENGE).orEmpty(),
                    Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
                )
            }.getOrNull()
        } else {
            null
        }
    }

    override fun onPostResume() {
        super.onPostResume()
        if (started) return
        started = true
        val challenge = pendingChallenge
        pendingChallenge = null
        if (challenge?.size == CHALLENGE_BYTES) {
            try {
                LanWebAdbPairingStore(this).putChallenge(challenge)
                ContextCompat.startForegroundService(
                    this,
                    Intent(this, LanWebForegroundService::class.java)
                        .setAction(LanWebContract.ACTION_START),
                )
                setResult(RESULT_OK)
            } finally {
                challenge.fill(0)
            }
        } else {
            challenge?.fill(0)
            setResult(RESULT_CANCELED)
        }
        Handler(Looper.getMainLooper()).postDelayed(::finish, FINISH_DELAY_MILLIS)
    }

    override fun onDestroy() {
        pendingChallenge?.fill(0)
        pendingChallenge = null
        super.onDestroy()
    }

    private fun buildConnectingView(): LinearLayout {
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(48), dp(32), dp(48))
            setBackgroundColor(Color.rgb(11, 11, 11))
            addView(
                ImageView(context).apply {
                    setImageResource(R.drawable.app_icon)
                    contentDescription = getString(R.string.app_name)
                },
                LinearLayout.LayoutParams(dp(76), dp(76)).apply { bottomMargin = dp(24) },
            )
            addView(
                TextView(context).apply {
                    text = "正在连接电脑…"
                    setTextColor(Color.WHITE)
                    textSize = 22f
                    gravity = Gravity.CENTER
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
            addView(
                TextView(context).apply {
                    text = "正在建立安全 ADB 连接"
                    setTextColor(Color.argb(179, 255, 255, 255))
                    textSize = 14f
                    gravity = Gravity.CENTER
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(8)
                    bottomMargin = dp(24)
                },
            )
            addView(ProgressBar(context))
        }
    }

    private companion object {
        const val CHALLENGE_BYTES = 32
        const val FINISH_DELAY_MILLIS = 1_200L
    }
}
