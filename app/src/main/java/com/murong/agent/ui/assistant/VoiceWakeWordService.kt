package com.murong.agent.ui.assistant

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.murong.agent.R
import com.murong.agent.core.voice.VoiceRecognitionRequest
import com.murong.agent.voice.OfflineSherpaSpeechRecognitionService
import com.murong.agent.voice.OfflineVoiceModelManager
import com.murong.agent.voice.SPEAKER_ENROLLMENT_SAMPLE_COUNT
import com.murong.agent.voice.SpeakerVerificationManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal enum class WakeWordRuntimeStatus {
    STOPPED,
    STARTING,
    LISTENING,
    PAUSED,
    MODEL_MISSING,
    PERMISSION_MISSING,
    VOICEPRINT_MISSING,
    VERIFYING_SPEAKER,
    SPEAKER_REJECTED,
    ERROR,
}

internal data class WakeWordRuntimeState(
    val status: WakeWordRuntimeStatus = WakeWordRuntimeStatus.STOPPED,
    val detail: String = "未启用",
)

/**
 * Optional continuous offline wake-word listener. It deliberately uses a visible microphone
 * foreground service: Android does not permit a hidden third-party always-listening microphone.
 */
class VoiceWakeWordService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var modelManager: OfflineVoiceModelManager
    private lateinit var recognizer: OfflineSherpaSpeechRecognitionService
    private lateinit var speakerVerification: SpeakerVerificationManager
    private var listenJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        modelManager = OfflineVoiceModelManager(this)
        recognizer = OfflineSherpaSpeechRecognitionService(this, modelManager)
        speakerVerification = SpeakerVerificationManager(this)
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_START) {
            ACTION_STOP -> {
                AssistantInvocationPreferences.setWakeWordEnabled(this, false)
                stopListeningAndSelf()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> pauseListening(
                intent?.getLongExtra(EXTRA_PAUSE_REQUEST_ID, NO_PAUSE_REQUEST_ID)
                    ?: NO_PAUSE_REQUEST_ID,
            )
            ACTION_RESUME -> if (AssistantInvocationPreferences.read(this).wakeWordEnabled) {
                startListening()
            }
            else -> startListening()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        listenJob?.cancel()
        recognizer.close()
        speakerVerification.close()
        modelManager.close()
        scope.cancel()
        runtimeMutable.value = WakeWordRuntimeState()
        super.onDestroy()
    }

    private fun startListening() {
        if (listenJob?.isActive == true) return
        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            runtimeMutable.value = WakeWordRuntimeState(
                WakeWordRuntimeStatus.PERMISSION_MISSING,
                "缺少麦克风权限",
            )
            stopSelf()
            return
        }
        if (!recognizer.isAvailable()) {
            runtimeMutable.value = WakeWordRuntimeState(
                WakeWordRuntimeStatus.MODEL_MISSING,
                "请先下载离线语音模型",
            )
            AssistantInvocationPreferences.setWakeWordEnabled(this, false)
            stopSelf()
            return
        }
        if (!speakerVerification.isReadyForVerification()) {
            runtimeMutable.value = WakeWordRuntimeState(
                WakeWordRuntimeStatus.VOICEPRINT_MISSING,
                "请先安装声纹模型并录入本人的 " +
                    "$SPEAKER_ENROLLMENT_SAMPLE_COUNT 段唤醒词",
            )
            AssistantInvocationPreferences.setWakeWordEnabled(this, false)
            stopSelf()
            return
        }
        startForeground(
            NOTIFICATION_ID,
            buildNotification("正在准备离线唤醒…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
        listenJob = scope.launch {
            runtimeMutable.value = WakeWordRuntimeState(
                WakeWordRuntimeStatus.STARTING,
                "正在加载离线唤醒模型",
            )
            while (AssistantInvocationPreferences.read(this@VoiceWakeWordService).wakeWordEnabled) {
                val invocationSettings = AssistantInvocationPreferences.read(
                    this@VoiceWakeWordService,
                )
                val phrase = invocationSettings.wakePhrase
                val result = runCatching {
                    recognizer.start(VoiceRecognitionRequest("zh-CN"))
                    runtimeMutable.value = WakeWordRuntimeState(
                        WakeWordRuntimeStatus.LISTENING,
                        "正在本地监听“$phrase”",
                    )
                    updateNotification("正在本地监听“$phrase”")
                    val partialMatch = withTimeoutOrNull(LISTENING_WINDOW_MILLIS) {
                        recognizer.partialText.first { containsWakePhrase(it, phrase) }
                    }
                    val finalTranscript = recognizer.stop()
                    val phraseMatched = partialMatch != null ||
                        containsWakePhrase(finalTranscript, phrase)
                    val recordedAudio = recognizer.takeLastRecordedAudio()
                    if (!phraseMatched || recordedAudio == null) {
                        false
                    } else {
                        runtimeMutable.value = WakeWordRuntimeState(
                            WakeWordRuntimeStatus.VERIFYING_SPEAKER,
                            "唤醒词已命中，正在本机核验声纹",
                        )
                        updateNotification("正在本机核验声纹…")
                        val accepted = speakerVerification.verify(
                            samples = recordedAudio,
                            threshold = invocationSettings.speakerVerificationThreshold,
                        )
                        if (!accepted) {
                            runtimeMutable.value = WakeWordRuntimeState(
                                WakeWordRuntimeStatus.SPEAKER_REJECTED,
                                "唤醒词正确，但不是已录入的声纹",
                            )
                            updateNotification("已忽略非本人唤醒")
                        }
                        accepted
                    }
                }.getOrElse { error ->
                    if (error is CancellationException) throw error
                    runtimeMutable.value = WakeWordRuntimeState(
                        WakeWordRuntimeStatus.ERROR,
                        error.message?.take(120) ?: "离线唤醒监听失败",
                    )
                    runCatching { recognizer.cancel() }
                    delay(1_000L)
                    false
                }
                if (result) {
                    runtimeMutable.value = WakeWordRuntimeState(
                        WakeWordRuntimeStatus.PAUSED,
                        "已听到“$phrase”，正在打开助手",
                    )
                    updateNotification("已唤醒 Murong 助手")
                    MurongVoiceInteractionService.requestShow(
                        this@VoiceWakeWordService,
                        "wake_word",
                    )
                    delay(ASSISTANT_COOLDOWN_MILLIS)
                } else {
                    delay(120L)
                }
            }
            stopListeningAndSelf()
        }
    }

    private fun pauseListening(requestId: Long) {
        listenJob?.cancel()
        listenJob = null
        scope.launch {
            // Do not acknowledge the pause until AudioRecord has actually been released. The
            // interactive recognizer otherwise races the hotword recorder and reports "busy".
            runCatching { recognizer.cancel() }
            runtimeMutable.value = WakeWordRuntimeState(
                WakeWordRuntimeStatus.PAUSED,
                "交互式语音期间暂停唤醒监听",
            )
            updateNotification("语音输入使用中，唤醒监听已暂停")
            acknowledgePause(requestId)
        }
    }

    private fun stopListeningAndSelf() {
        listenJob?.cancel()
        listenJob = null
        scope.launch { runCatching { recognizer.cancel() } }
        runtimeMutable.value = WakeWordRuntimeState()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(status: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            1,
            MurongAssistActivity.createIntent(this, "notification"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, VoiceWakeWordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.app_icon)
            .setContentTitle("Murong 离线语音唤醒")
            .setContentText(status)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "停止监听", stopIntent).build())
            .build()
    }

    private fun updateNotification(status: String) {
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, buildNotification(status))
    }

    private fun ensureChannel() {
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "离线语音唤醒",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "显示“慕容慕容”本地唤醒词的持续监听状态"
                setSound(null, null)
            },
        )
    }

    companion object {
        private const val ACTION_START = "com.murong.agent.action.START_WAKE_WORD"
        private const val ACTION_STOP = "com.murong.agent.action.STOP_WAKE_WORD"
        private const val ACTION_PAUSE = "com.murong.agent.action.PAUSE_WAKE_WORD"
        private const val ACTION_RESUME = "com.murong.agent.action.RESUME_WAKE_WORD"
        private const val EXTRA_PAUSE_REQUEST_ID = "pause_request_id"
        private const val NO_PAUSE_REQUEST_ID = -1L
        private const val CHANNEL_ID = "murong_wake_word"
        private const val NOTIFICATION_ID = 0x4D57
        private const val LISTENING_WINDOW_MILLIS = 12_000L
        private const val ASSISTANT_COOLDOWN_MILLIS = 12_000L
        private const val MICROPHONE_RELEASE_TIMEOUT_MILLIS = 2_500L

        private val runtimeMutable = MutableStateFlow(WakeWordRuntimeState())
        internal val runtime: StateFlow<WakeWordRuntimeState> = runtimeMutable.asStateFlow()
        private val pauseRequestIds = AtomicLong()
        private val pauseAcknowledgements =
            ConcurrentHashMap<Long, CompletableDeferred<Unit>>()

        fun setEnabled(context: Context, enabled: Boolean) {
            AssistantInvocationPreferences.setWakeWordEnabled(context, enabled)
            val intent = Intent(context, VoiceWakeWordService::class.java)
                .setAction(if (enabled) ACTION_START else ACTION_STOP)
            if (enabled) ContextCompat.startForegroundService(context, intent)
            else context.startService(intent)
        }

        fun pauseForAssistant(context: Context) {
            if (!AssistantInvocationPreferences.read(context).wakeWordEnabled) return
            context.startService(
                Intent(context, VoiceWakeWordService::class.java).setAction(ACTION_PAUSE),
            )
        }

        /**
         * Suspends until the wake-word AudioRecord is released, so another recognizer can acquire
         * the microphone without relying on an arbitrary delay.
         */
        suspend fun pauseAndAwaitMicrophoneRelease(context: Context) {
            if (!AssistantInvocationPreferences.read(context).wakeWordEnabled) return
            val requestId = pauseRequestIds.incrementAndGet()
            val acknowledgement = CompletableDeferred<Unit>()
            pauseAcknowledgements[requestId] = acknowledgement
            val started = runCatching {
                context.startService(
                    Intent(context, VoiceWakeWordService::class.java)
                        .setAction(ACTION_PAUSE)
                        .putExtra(EXTRA_PAUSE_REQUEST_ID, requestId),
                )
            }.isSuccess
            if (started) {
                withTimeoutOrNull(MICROPHONE_RELEASE_TIMEOUT_MILLIS) {
                    acknowledgement.await()
                }
            }
            pauseAcknowledgements.remove(requestId)
        }

        fun resumeAfterAssistant(context: Context) {
            if (!AssistantInvocationPreferences.read(context).wakeWordEnabled) return
            runCatching {
                context.startService(
                    Intent(context, VoiceWakeWordService::class.java).setAction(ACTION_RESUME),
                )
            }
        }

        private fun acknowledgePause(requestId: Long) {
            if (requestId == NO_PAUSE_REQUEST_ID) return
            pauseAcknowledgements.remove(requestId)?.complete(Unit)
        }
    }
}
