package com.murong.agent.analytics

import android.app.Activity
import android.app.Application
import android.os.Build
import com.murong.agent.core.config.ConfigRepository
import com.murong.agent.core.doctor.SensitiveDataSanitizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

class UsageAnalyticsTracker(
    private val application: Application
) : Application.ActivityLifecycleCallbacks {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val configRepository = ConfigRepository(application)
    private val preferences = application.getSharedPreferences(PREFERENCES_NAME, Application.MODE_PRIVATE)
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var startedActivityCount = 0

    @Volatile
    private var currentSessionId: String? = null

    @Volatile
    private var sessionStartedAtMs: Long = 0L

    private var heartbeatJob: Job? = null

    private val installId: String by lazy {
        preferences.getString(KEY_INSTALL_ID, null)
            ?: UUID.randomUUID().toString().also { generated ->
                preferences.edit().putString(KEY_INSTALL_ID, generated).apply()
            }
    }

    fun dispose() {
        heartbeatJob?.cancel()
        scope.cancel()
        application.unregisterActivityLifecycleCallbacks(this)
    }

    override fun onActivityStarted(activity: Activity) {
        val shouldStart = synchronized(this) {
            startedActivityCount += 1
            startedActivityCount == 1
        }
        if (shouldStart) {
            startForegroundSession()
        }
    }

    override fun onActivityStopped(activity: Activity) {
        val shouldStop = synchronized(this) {
            startedActivityCount = (startedActivityCount - 1).coerceAtLeast(0)
            startedActivityCount == 0 && !activity.isChangingConfigurations
        }
        if (shouldStop) {
            endForegroundSession()
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    @Synchronized
    private fun startForegroundSession() {
        if (currentSessionId != null) return
        currentSessionId = UUID.randomUUID().toString()
        sessionStartedAtMs = System.currentTimeMillis()
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            sendHeartbeat(sessionIdSnapshot = synchronized(this@UsageAnalyticsTracker) { currentSessionId } ?: return@launch, ended = false)
            while (currentSessionId != null) {
                delay(HEARTBEAT_INTERVAL_MS)
                val snapshot = synchronized(this@UsageAnalyticsTracker) { currentSessionId } ?: break
                sendHeartbeat(sessionIdSnapshot = snapshot, ended = false)
            }
        }
    }

    @Synchronized
    private fun endForegroundSession() {
        val sessionIdSnapshot = currentSessionId
        val startedAtSnapshot = sessionStartedAtMs
        heartbeatJob?.cancel()
        heartbeatJob = null
        currentSessionId = null
        sessionStartedAtMs = 0L
        if (sessionIdSnapshot == null) return
        scope.launch {
            sendHeartbeat(
                sessionIdSnapshot = sessionIdSnapshot,
                startedAtMsSnapshot = startedAtSnapshot,
                ended = true
            )
        }
    }

    private suspend fun sendHeartbeat(
        sessionIdSnapshot: String,
        ended: Boolean,
        startedAtMsSnapshot: Long = synchronized(this) { sessionStartedAtMs }
    ) {
        val durationSeconds = ((System.currentTimeMillis() - startedAtMsSnapshot).coerceAtLeast(0L) / 1000L).toInt()
        val config = runCatching { configRepository.getConfig() }.getOrNull() ?: return
        val apiUrl = SensitiveDataSanitizer.sanitizeTransportHeaderValue(
            config.getMurongUsageApiUrl().trim()
        )
        if (apiUrl.isBlank()) return

        val packageInfo = runCatching {
            application.packageManager.getPackageInfo(application.packageName, 0)
        }.getOrNull()
        val versionName = packageInfo?.versionName.orEmpty()
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo?.longVersionCode?.toInt() ?: 0
        } else {
            @Suppress("DEPRECATION")
            packageInfo?.versionCode ?: 0
        }

        val payload = JSONObject()
            .put("install_id", installId)
            .put("session_id", sessionIdSnapshot)
            .put("app_version_name", versionName)
            .put("app_version_code", versionCode)
            .put("platform", "android")
            .put("os_version", Build.VERSION.RELEASE ?: "")
            .put(
                "device_model",
                SensitiveDataSanitizer.sanitizeText(
                    listOfNotNull(Build.MANUFACTURER, Build.MODEL).joinToString(" ").trim()
                )
            )
            .put("duration_seconds", durationSeconds)
            .put("ended", ended)

        val sanitizedPayload = SensitiveDataSanitizer.sanitizeText(payload.toString())
        val requestBuilder = Request.Builder()
            .url(
                if ("?" in apiUrl) "$apiUrl&action=heartbeat" else "$apiUrl?action=heartbeat"
            )
            .post(sanitizedPayload.toRequestBody(JSON_MEDIA_TYPE))
            .addHeader("Accept", "application/json")
            .addHeader(
                "User-Agent",
                SensitiveDataSanitizer.sanitizeTransportHeaderValue(
                    "MurongAgent/${versionName.ifBlank { "1.0" }}"
                )
            )

        if (config.githubBackendSessionToken.isNotBlank()) {
            requestBuilder.addHeader(
                "Authorization",
                "Bearer ${
                    SensitiveDataSanitizer.sanitizeTransportHeaderValue(
                        config.githubBackendSessionToken
                    )
                }"
            )
        }

        runCatching {
            client.newCall(requestBuilder.build()).execute().use { response ->
                response.body?.close()
            }
        }
    }

    private companion object {
        private const val PREFERENCES_NAME = "murong_usage_analytics"
        private const val KEY_INSTALL_ID = "install_id"
        private const val HEARTBEAT_INTERVAL_MS = 60_000L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
