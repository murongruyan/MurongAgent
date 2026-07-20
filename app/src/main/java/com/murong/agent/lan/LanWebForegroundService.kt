package com.murong.agent.lan

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.murong.agent.R
import com.murong.agent.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

@AndroidEntryPoint
class LanWebForegroundService : Service() {
    @Inject lateinit var runtime: LanWebRuntime

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var stateObserverStarted = false
    private var wifiLock: WifiManager.WifiLock? = null
    private var cpuWakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == LanWebContract.ACTION_STOP) {
            serviceScope.launch {
                runtime.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification(runtime.state.value))
        if (!acquireNetworkWakeLocks()) {
            runtime.reportServiceError("无法保持局域网服务唤醒，服务未启动")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!stateObserverStarted) {
            stateObserverStarted = true
            serviceScope.launch {
                runtime.state.collect { state ->
                    getSystemService(NotificationManager::class.java)
                        ?.notify(NOTIFICATION_ID, buildNotification(state))
                    if (!state.running && !state.starting && state.error != null) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
        }
        serviceScope.launch { runtime.start() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        val preserveError = runtime.state.value.error != null
        runBlocking(Dispatchers.IO) { runtime.stop(clearError = !preserveError) }
        releaseNetworkWakeLocks()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(state: LanWebServiceState): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, LanWebForegroundService::class.java).setAction(LanWebContract.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = when {
            state.running && state.workspaceConnected ->
                "${desktopPlatformLabel(state.workspacePlatform, state.workspaceArchitecture)} 已连接：${state.workspaceLabel ?: "电脑工作区"}"
            state.running && state.cloudRelayEnabled -> state.cloudRelayStatus
            state.running -> state.nodeUrl ?: "电脑节点服务正在运行"
            state.starting -> "正在启动电脑节点与可选云中继…"
            else -> state.error ?: "电脑节点服务已停止"
        }
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.app_icon)
            .setContentTitle("Murong 电脑节点服务")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openIntent)
            .addAction(0, "停止", stopIntent)
            .setOngoing(state.running || state.starting)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "电脑节点服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持手机与 Murong Desktop 的局域网或端到端加密云中继连接并显示停止入口"
                setShowBadge(false)
            }
        )
    }

    private fun acquireNetworkWakeLocks(): Boolean = runCatching {
        val existingWifiLock = wifiLock
        val existingCpuLock = cpuWakeLock
        if (existingCpuLock?.isHeld == true && (runtime.state.value.cloudRelayEnabled || existingWifiLock?.isHeld == true)) {
            return@runCatching true
        }
        val wifiAcquired = runCatching {
            val manager = getSystemService(WifiManager::class.java)
                ?: error("系统 Wi-Fi 服务不可用")
            wifiLock = manager.createWifiLock(
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY,
                WIFI_LOCK_TAG
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
            true
        }.getOrDefault(false)
        if (!runtime.state.value.cloudRelayEnabled && !wifiAcquired) {
            error("无法保持局域网 Wi-Fi 连接")
        }
        val powerManager = getSystemService(PowerManager::class.java)
            ?: error("系统电源服务不可用")
        cpuWakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            CPU_WAKE_LOCK_TAG
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
        true
    }.getOrElse {
        releaseNetworkWakeLocks()
        false
    }

    private fun releaseNetworkWakeLocks() {
        val currentWifiLock = wifiLock
        val currentCpuLock = cpuWakeLock
        wifiLock = null
        cpuWakeLock = null
        if (currentCpuLock?.isHeld == true) runCatching { currentCpuLock.release() }
        if (currentWifiLock?.isHeld == true) runCatching { currentWifiLock.release() }
    }

    companion object {
        private const val NOTIFICATION_ID = 3127
        private const val NOTIFICATION_CHANNEL_ID = "murong_lan_web"
        private const val WIFI_LOCK_TAG = "Murong:LanWeb"
        private const val CPU_WAKE_LOCK_TAG = "Murong:LanWebCpu"

        fun requestStart(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, LanWebForegroundService::class.java)
                    .setAction(LanWebContract.ACTION_START)
            )
        }

        fun requestStop(context: Context) {
            context.startService(
                Intent(context, LanWebForegroundService::class.java)
                    .setAction(LanWebContract.ACTION_STOP)
            )
        }
    }
}
