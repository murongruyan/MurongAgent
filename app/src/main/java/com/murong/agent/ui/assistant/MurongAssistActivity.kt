package com.murong.agent.ui.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import androidx.core.content.ContextCompat
import com.murong.agent.core.voice.VoicePlaybackState
import androidx.lifecycle.lifecycleScope
import com.murong.agent.core.voice.VoiceRecognitionState
import com.murong.agent.core.voice.sanitizeVoicePlaybackText
import com.murong.agent.ui.MurongTheme
import com.murong.agent.voice.VoiceChatController
import com.murong.agent.voice.SpeechEndpointDetector
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * A translucent assistant panel that remains over the app the user was viewing. It intentionally
 * does not route through MainActivity, so invoking the default assistant no longer replaces the
 * foreground task with Murong's full-screen chat.
 */
@AndroidEntryPoint
class MurongAssistActivity : ComponentActivity() {
    @Inject lateinit var conversationRunner: AssistantConversationRunner

    private lateinit var voiceController: VoiceChatController
    private var pendingListeningJob: Job? = null
    private var stopWhenListeningStarts = false
    private var pendingNotificationTask: Pair<String, AssistantTaskKind>? = null
    private var pendingCalendarAction: Pair<String, AssistantRequestRoute>? = null
    private var backgroundHandoffJob: Job? = null
    private val microphonePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) startListeningWithPermission()
        else voiceController.reportInputError("需要麦克风权限才能使用语音助手")
    }
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            pendingNotificationTask?.let { (text, kind) ->
                AssistantTaskForegroundService.enqueue(this, text, kind)
            }
            pendingNotificationTask = null
            dismissAssistant()
        } else {
            pendingNotificationTask = null
            voiceController.reportInputError("需要通知权限才能持续显示后台任务进度")
        }
    }
    private val calendarPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val pending = pendingCalendarAction
        pendingCalendarAction = null
        if (
            results[Manifest.permission.READ_CALENDAR] == true &&
            results[Manifest.permission.WRITE_CALENDAR] == true
        ) {
            pending?.let { (text, route) -> conversationRunner.dispatchFast(text, route) }
        } else if (pending != null) {
            conversationRunner.postLocalStatus(
                message = "未获得日历读写权限，日程没有创建。",
                routeLabel = pending.second.label,
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = AndroidColor.TRANSPARENT
        window.navigationBarColor = AndroidColor.TRANSPARENT
        window.setDimAmount(0f)
        // The Activity owns the wake-word pause for its whole lifetime. The controller must not
        // resume the hotword recorder between turns while the assistant is still visible.
        voiceController = VoiceChatController(this, manageWakeWordMicrophone = false)
        VoiceWakeWordService.pauseForAssistant(this)
        setContent {
            MurongTheme {
                AssistantPopup(
                    invocationSource = intent.getStringExtra(
                        MurongVoiceInteractionService.EXTRA_INVOCATION_SOURCE,
                    ).orEmpty(),
                    voiceController = voiceController,
                    conversationRunner = conversationRunner,
                    onStartListening = ::startListeningWithPermission,
                    onStopListening = ::stopListening,
                    onCancelListening = ::cancelListening,
                    onDispatchFast = ::dispatchFastWithCalendarPermission,
                    onAnnounceAndEnqueueTask = ::announceAndEnqueueAssistantTask,
                    onDismiss = ::dismissAssistant,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        startListeningWithPermission()
    }

    override fun onDestroy() {
        cancelListening()
        voiceController.close()
        VoiceWakeWordService.resumeAfterAssistant(this)
        if (!isChangingConfigurations) {
            MurongVoiceInteractionService.dismissCurrentSession()
        }
        super.onDestroy()
    }

    private fun startListeningWithPermission() {
        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            if (pendingListeningJob?.isActive == true) return
            stopWhenListeningStarts = false
            pendingListeningJob = lifecycleScope.launch {
                VoiceWakeWordService.pauseAndAwaitMicrophoneRelease(this@MurongAssistActivity)
                voiceController.startInput()
                val shouldStop = stopWhenListeningStarts
                stopWhenListeningStarts = false
                pendingListeningJob = null
                if (shouldStop) voiceController.stopInput()
            }
        } else {
            microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun stopListening() {
        if (pendingListeningJob?.isActive == true) {
            stopWhenListeningStarts = true
            return
        }
        voiceController.stopInput()
    }

    private fun cancelListening() {
        pendingListeningJob?.cancel()
        pendingListeningJob = null
        stopWhenListeningStarts = false
        voiceController.cancelInput()
    }

    private fun dismissAssistant() {
        finish()
    }

    private fun enqueueAssistantTask(text: String, kind: AssistantTaskKind): Boolean {
        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingNotificationTask = text to kind
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return false
        }
        AssistantTaskForegroundService.enqueue(this, text, kind)
        return true
    }

    private fun dispatchFastWithCalendarPermission(
        text: String,
        route: AssistantRequestRoute,
    ) {
        val calendarAction = route.kind == AssistantTaskKind.INSTANT_LOCAL &&
            AssistantLocalActions.isCalendarRequest(text)
        val hasCalendarPermissions = listOf(
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR,
        ).all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
        if (calendarAction && !hasCalendarPermissions) {
            pendingCalendarAction = text to route
            calendarPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_CALENDAR,
                    Manifest.permission.WRITE_CALENDAR,
                ),
            )
            return
        }
        conversationRunner.dispatchFast(text, route)
    }

    private fun announceAndEnqueueAssistantTask(
        text: String,
        kind: AssistantTaskKind,
        acknowledgement: String,
        shouldSpeak: Boolean,
    ) {
        if (backgroundHandoffJob?.isActive == true) return
        backgroundHandoffJob = lifecycleScope.launch {
            if (shouldSpeak) {
                voiceController.speakAndAwait(
                    messageId = System.nanoTime(),
                    messageText = acknowledgement,
                )
            }
            val enqueuedImmediately = enqueueAssistantTask(text, kind)
            if (enqueuedImmediately) dismissAssistant()
        }
    }

    companion object {
        fun createIntent(context: Context, source: String): Intent =
            Intent(context, MurongAssistActivity::class.java).apply {
                putExtra(MurongVoiceInteractionService.EXTRA_INVOCATION_SOURCE, source)
                // The assistant must live in its own translucent task. CLEAR_TOP would locate
                // Murong's normal task and expose MainActivity below this transparent window.
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION,
                )
            }
    }
}

@Composable
private fun AssistantPopup(
    invocationSource: String,
    voiceController: VoiceChatController,
    conversationRunner: AssistantConversationRunner,
    onStartListening: () -> Unit,
    onStopListening: () -> Unit,
    onCancelListening: () -> Unit,
    onDispatchFast: (String, AssistantRequestRoute) -> Unit,
    onAnnounceAndEnqueueTask: (String, AssistantTaskKind, String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val voiceState by voiceController.inputState.collectAsState()
    val voiceSettings by voiceController.settings.collectAsState()
    val playbackState by voiceController.playbackState.collectAsState()
    val sessionState by conversationRunner.state.collectAsState()
    val runnerError by conversationRunner.lastError.collectAsState()
    val fastTurnState by conversationRunner.fastTurnState.collectAsState()
    val screenContext by VoiceAssistantScreenContext.state.collectAsState()
    val currentVoiceState by rememberUpdatedState(voiceState)
    val entries = remember { mutableStateListOf<AssistantOverlayEntry>() }
    val listState = rememberLazyListState()
    val sessionBaselineMessageId = remember {
        sessionState.messages.maxOfOrNull { it.id } ?: 0L
    }
    val fastBaselineResponseId = remember { fastTurnState.responseId }
    var expanded by remember { mutableStateOf(false) }
    var draftText by remember { mutableStateOf("") }
    var draftRevision by remember { mutableLongStateOf(0L) }
    var suppressNextAutoSubmit by remember { mutableStateOf(false) }
    var submittedText by remember { mutableStateOf<String?>(null) }
    var activeRoute by remember { mutableStateOf<AssistantRequestRoute?>(null) }
    var spokenMessageId by remember { mutableLongStateOf(-1L) }
    var spokenFastResponseId by remember { mutableLongStateOf(-1L) }
    var handoffInProgress by remember { mutableStateOf(false) }
    var showScreenSelector by remember { mutableStateOf(false) }
    val newAssistantMessages = sessionState.messages.filter {
        it.id > sessionBaselineMessageId && it.role == "assistant"
    }
    val assistantMessage = newAssistantMessages.lastOrNull()
    val pendingConfirmation = sessionState.pendingApproval != null ||
        sessionState.pendingAskRequest != null ||
        sessionState.pendingClarificationRequest != null
    val capturing = voiceState.recognitionState in setOf(
        VoiceRecognitionState.PREPARING,
        VoiceRecognitionState.LISTENING,
    )
    val finalizing = voiceState.recognitionState == VoiceRecognitionState.FINALIZING
    val processing = sessionState.isProcessing || fastTurnState.isProcessing || handoffInProgress
    val runExplicitScreenAnalysis: (String, ScreenSelection?) -> Unit = { prompt, selection ->
        if (!processing) {
            activeRoute = AssistantRequestRoute(
                kind = AssistantTaskKind.SCREEN_AWARE,
                requiresScreenContext = true,
                label = if (selection == null) "按需读取屏幕" else "圈选屏幕区域",
            )
            submittedText = prompt
            entries += AssistantOverlayEntry(
                key = "screen-${System.nanoTime()}",
                role = AssistantOverlayRole.USER,
                text = if (selection == null) "识别当前屏幕" else "识别我圈选的屏幕区域",
            )
            val screenshot = VoiceAssistantScreenContext.createScreenshotAttachment(
                context = context,
                selection = selection,
            )
            if (screenshot == null) {
                conversationRunner.postLocalStatus(
                    message = "本次系统没有提供当前屏幕截图，未发送任何屏幕内容。请确认 Murong 已设为默认助理后再试。",
                    routeLabel = activeRoute?.label ?: "屏幕理解",
                )
            } else {
                conversationRunner.sendVisualAnalysis(prompt, listOf(screenshot))
            }
        }
    }
    val submitText: (String) -> Unit = submit@ { raw ->
        val text = raw.trim()
        if (text.isBlank() || processing) return@submit
        onCancelListening()
        voiceController.consumeFinalText()
        draftRevision += 1
        draftText = ""
        submittedText = text
        entries += AssistantOverlayEntry(
            key = "user-${System.nanoTime()}",
            role = AssistantOverlayRole.USER,
            text = text,
        )
        val route = AssistantRequestRouter.classify(text)
        activeRoute = route
        when (route.kind) {
            AssistantTaskKind.INSTANT_LOCAL,
            AssistantTaskKind.FAST_LOCAL_CHAT,
            AssistantTaskKind.FAST_LOCAL_WEB,
            -> onDispatchFast(text, route)

            AssistantTaskKind.BACKGROUND_WEB_RESEARCH,
            AssistantTaskKind.BACKGROUND_CODE,
            AssistantTaskKind.PHONE_FOREGROUND,
            -> {
                val acknowledgement = when (route.kind) {
                    AssistantTaskKind.PHONE_FOREGROUND ->
                        "好的。朗读完成后开始操作手机，当前步骤会显示在悬浮窗和通知中。"
                    AssistantTaskKind.BACKGROUND_CODE ->
                        "好的。朗读完成后转入后台代码任务，执行进度会持续显示。"
                    else ->
                        "好的。朗读完成后开始后台检索，执行进度会持续显示。"
                }
                entries += AssistantOverlayEntry(
                    key = "handoff-${System.nanoTime()}",
                    role = AssistantOverlayRole.ASSISTANT,
                    text = acknowledgement,
                )
                handoffInProgress = true
                onAnnounceAndEnqueueTask(
                    text,
                    route.kind,
                    acknowledgement,
                    voiceSettings.autoReadFinalAnswers,
                )
            }

            AssistantTaskKind.SCREEN_AWARE -> {
                val prompt = VoiceAssistantScreenContext.buildModelPrompt(text)
                val screenshot = VoiceAssistantScreenContext.createScreenshotAttachment(context)
                if (screenshot == null) {
                    conversationRunner.postLocalStatus(
                        message = "本次系统没有提供当前屏幕截图，未发送任何屏幕内容。请确认 Murong 已设为默认助理后再试。",
                        routeLabel = route.label,
                    )
                } else {
                    conversationRunner.sendVisualAnalysis(prompt, listOf(screenshot))
                }
            }

            AssistantTaskKind.MAIN_MODEL -> conversationRunner.sendMain(text)
        }
    }

    BackHandler {
        if (expanded) expanded = false else onDismiss()
    }

    LaunchedEffect(Unit) {
        onStartListening()
    }

    // The system recognizer endpoints itself. The offline streaming recognizer is stopped after
    // speech followed by a short silence, or after a bounded listening window.
    LaunchedEffect(voiceState.recognitionState) {
        if (voiceState.recognitionState != VoiceRecognitionState.LISTENING) return@LaunchedEffect
        val endpointDetector = SpeechEndpointDetector()
        endpointDetector.reset(System.currentTimeMillis())
        while (currentVoiceState.recognitionState == VoiceRecognitionState.LISTENING) {
            val now = System.currentTimeMillis()
            val current = currentVoiceState
            if (
                endpointDetector.shouldStop(
                    nowMillis = now,
                    volume = current.volume,
                    partialText = current.partialText,
                )
            ) {
                onStopListening()
                break
            }
            delay(120L)
        }
    }

    LaunchedEffect(voiceState.partialText, voiceState.recognitionState) {
        if (capturing && voiceState.partialText.isNotBlank()) {
            draftText = voiceState.partialText
        }
    }

    LaunchedEffect(voiceState.finalText) {
        val finalText = voiceState.finalText?.trim()?.takeIf(String::isNotBlank)
            ?: return@LaunchedEffect
        val skipAutoSubmit = suppressNextAutoSubmit
        suppressNextAutoSubmit = false
        draftText = finalText
        draftRevision += 1
        val capturedRevision = draftRevision
        voiceController.consumeFinalText()
        if (!skipAutoSubmit) {
            delay(AUTO_SUBMIT_DELAY_MILLIS)
            if (
                draftRevision == capturedRevision &&
                draftText.trim() == finalText &&
                !sessionState.isProcessing &&
                !fastTurnState.isProcessing
            ) {
                submitText(finalText)
            }
        }
    }

    LaunchedEffect(sessionState.messages) {
        newAssistantMessages.forEach { message ->
            entries.upsert(
                AssistantOverlayEntry(
                    key = "main-${message.id}",
                    role = AssistantOverlayRole.ASSISTANT,
                    text = message.content,
                ),
            )
        }
    }

    LaunchedEffect(
        fastTurnState.responseId,
        fastTurnState.responseText,
        fastTurnState.error,
    ) {
        if (fastTurnState.responseId <= fastBaselineResponseId) return@LaunchedEffect
        val text = fastTurnState.responseText.ifBlank { fastTurnState.error.orEmpty() }
        if (text.isNotBlank()) {
            entries.upsert(
                AssistantOverlayEntry(
                    key = "fast-${fastTurnState.responseId}",
                    role = if (fastTurnState.responseText.isNotBlank()) {
                        AssistantOverlayRole.ASSISTANT
                    } else {
                        AssistantOverlayRole.STATUS
                    },
                    text = text,
                ),
            )
        }
    }

    LaunchedEffect(assistantMessage?.id, sessionState.isProcessing) {
        val message = assistantMessage ?: return@LaunchedEffect
        if (
            !sessionState.isProcessing &&
            voiceSettings.autoReadFinalAnswers &&
            spokenMessageId != message.id &&
            sanitizeVoicePlaybackText(message.content).isNotBlank()
        ) {
            spokenMessageId = message.id
            voiceController.speak(message.id, message.content)
        }
    }

    LaunchedEffect(fastTurnState.responseId, fastTurnState.isProcessing) {
        if (
            fastTurnState.responseId > 0L &&
            !fastTurnState.isProcessing &&
            fastTurnState.responseText.isNotBlank() &&
            voiceSettings.autoReadFinalAnswers &&
            spokenFastResponseId != fastTurnState.responseId &&
            sanitizeVoicePlaybackText(fastTurnState.responseText).isNotBlank()
        ) {
            spokenFastResponseId = fastTurnState.responseId
            voiceController.speak(fastTurnState.responseId, fastTurnState.responseText)
        }
    }

    val transientStatus = when {
        capturing -> if (voiceState.partialText.isBlank()) "正在听…" else "正在识别…"
        finalizing -> "正在整理语音，可编辑后发送…"
        fastTurnState.isProcessing -> "正在处理（${fastTurnState.routeLabel}）"
        sessionState.isProcessing -> "正在处理（${activeRoute?.label ?: "主模型"}）"
        else -> null
    }
    val errorText = voiceState.errorMessage ?: runnerError
    val screenPrivacyText = when {
        activeRoute?.requiresScreenContext == true && screenContext.visibleText.isNotBlank() ->
            "本次已按你的明确要求读取屏幕文字"
        activeRoute?.requiresScreenContext == true && screenContext.screenshotAvailable ->
            "本次已按你的明确要求读取屏幕截图"
        activeRoute?.requiresScreenContext == true -> "本次屏幕内容不可用"
        else -> "未读取当前屏幕内容"
    }

    LaunchedEffect(entries.size, entries.lastOrNull()?.text, transientStatus) {
        if (entries.isNotEmpty()) listState.animateScrollToItem(entries.lastIndex)
    }

    val selectorScreenshot = if (showScreenSelector) {
        VoiceAssistantScreenContext.currentScreenshot()
    } else {
        null
    }
    LaunchedEffect(showScreenSelector, selectorScreenshot) {
        if (showScreenSelector && selectorScreenshot == null) {
            showScreenSelector = false
            conversationRunner.postLocalStatus(
                message = "当前没有可供圈选的系统截图，未读取屏幕内容。请确认 Murong 是默认助理后重新唤醒。",
                routeLabel = "圈选屏幕区域",
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.16f))
            .clickable {
                if (!expanded) onDismiss()
            },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            modifier = (if (expanded) {
                Modifier.fillMaxSize()
            } else {
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 360.dp, max = 620.dp)
            }).clickable(onClick = {}),
            shape = if (expanded) {
                RectangleShape
            } else {
                RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)
            },
            tonalElevation = 8.dp,
            shadowElevation = 18.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (expanded) Modifier.statusBarsPadding() else Modifier)
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Murong 助手", style = MaterialTheme.typography.titleLarge)
                        Text(
                            invocationLabel(invocationSource),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (processing || finalizing) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(horizontal = 10.dp)
                                .size(22.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                    IconButton(onClick = { expanded = !expanded }) {
                        Icon(
                            if (expanded) Icons.Outlined.FullscreenExit
                            else Icons.Outlined.Fullscreen,
                            contentDescription = if (expanded) "收起当前对话" else "全屏显示当前对话",
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Outlined.Close, contentDescription = "关闭")
                    }
                }

                Text(
                    text = screenPrivacyText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    TextButton(
                        enabled = !processing,
                        onClick = {
                            runExplicitScreenAnalysis(
                                "请识别当前屏幕中的文字、图片和关键信息。",
                                null,
                            )
                        },
                    ) {
                        Text("识别屏幕")
                    }
                    TextButton(
                        enabled = !processing,
                        onClick = { showScreenSelector = true },
                    ) {
                        Text("圈选识别")
                    }
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (entries.isEmpty()) {
                        item {
                            Text(
                                "可以说“打开抖音”“比较外卖价格”或直接提问",
                                modifier = Modifier.padding(vertical = 18.dp),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(entries, key = { it.key }) { entry ->
                        AssistantMessageBubble(entry)
                    }
                    transientStatus?.let { status ->
                        item(key = "transient-status") {
                            Text(
                                buildString {
                                    append(status)
                                    submittedText?.takeIf { it.isNotBlank() }?.let {
                                        append("：")
                                        append(it)
                                    }
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }

                errorText?.takeIf(String::isNotBlank)?.let { message ->
                    Text(
                        message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                if (pendingConfirmation) {
                    Text(
                        "这个操作需要确认；当前助手会话已保留，可稍后在主程序中处理。",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                OutlinedTextField(
                    value = draftText,
                    onValueChange = {
                        draftText = it
                        draftRevision += 1
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(if (capturing) "正在把语音写入这里…" else "输入或点按麦克风")
                    },
                    maxLines = if (expanded) 5 else 3,
                    trailingIcon = {
                        IconButton(
                            enabled = draftText.isNotBlank() && !processing,
                            onClick = {
                                if (capturing) onCancelListening()
                                submitText(draftText)
                            },
                        ) {
                            Icon(Icons.Outlined.Send, contentDescription = "发送")
                        }
                    },
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        enabled = capturing ||
                            playbackState == VoicePlaybackState.SPEAKING ||
                            playbackState == VoicePlaybackState.PAUSED,
                        onClick = {
                            when (playbackState) {
                                VoicePlaybackState.SPEAKING -> voiceController.pauseSpeaking()
                                VoicePlaybackState.PAUSED -> voiceController.resumeSpeaking()
                                else -> if (capturing) {
                                    suppressNextAutoSubmit = true
                                    onStopListening()
                                }
                            }
                        },
                    ) {
                        Icon(
                            if (playbackState == VoicePlaybackState.PAUSED) {
                                Icons.Outlined.PlayArrow
                            } else {
                                Icons.Outlined.Pause
                            },
                            contentDescription = null,
                        )
                        Spacer(Modifier.size(4.dp))
                        Text(
                            when (playbackState) {
                                VoicePlaybackState.SPEAKING -> "暂停朗读"
                                VoicePlaybackState.PAUSED -> "继续朗读"
                                else -> "暂停录音"
                            },
                        )
                    }

                    AssistantMicrophoneButton(
                        listening = capturing,
                        enabled = !processing && !finalizing,
                        busy = finalizing,
                        onStart = {
                            submittedText = null
                            draftRevision += 1
                            draftText = ""
                            voiceController.stopSpeaking()
                            onStartListening()
                        },
                        onStop = onStopListening,
                        onCancel = onCancelListening,
                    )

                    TextButton(
                        enabled = capturing || processing || draftText.isNotBlank() ||
                            playbackState != VoicePlaybackState.IDLE,
                        onClick = {
                            draftRevision += 1
                            draftText = ""
                            suppressNextAutoSubmit = false
                            onCancelListening()
                            voiceController.stopSpeaking()
                            conversationRunner.cancelCurrent()
                        },
                    ) {
                        Icon(Icons.Outlined.Cancel, contentDescription = null)
                        Spacer(Modifier.size(4.dp))
                        Text("取消")
                    }
                }
                Text(
                    "点一下持续录音；再次点按停止。按住说话，松手后自动整理并发送；点“暂停录音”可先编辑。",
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (showScreenSelector && selectorScreenshot != null) {
            ScreenSelectionOverlay(
                screenshot = selectorScreenshot,
                onDismiss = { showScreenSelector = false },
                onConfirm = { selection ->
                    showScreenSelector = false
                    runExplicitScreenAnalysis("请识别我圈选区域中的文字、图片和关键信息。", selection)
                },
            )
        }
    }
}

@Composable
private fun ScreenSelectionOverlay(
    screenshot: Bitmap,
    onDismiss: () -> Unit,
    onConfirm: (ScreenSelection) -> Unit,
) {
    var start by remember(screenshot) { mutableStateOf<Offset?>(null) }
    var end by remember(screenshot) { mutableStateOf<Offset?>(null) }
    val density = LocalDensity.current
    val accent = MaterialTheme.colorScheme.primary
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        val availableWidth = with(density) { maxWidth.toPx() }
        val availableHeight = with(density) { maxHeight.toPx() }
        val scale = min(
            availableWidth / screenshot.width.coerceAtLeast(1),
            availableHeight / screenshot.height.coerceAtLeast(1),
        )
        val imageWidth = screenshot.width * scale
        val imageHeight = screenshot.height * scale
        val offsetX = (availableWidth - imageWidth) / 2f
        val offsetY = (availableHeight - imageHeight) / 2f
        fun clampToImage(point: Offset): Offset = Offset(
            x = point.x.coerceIn(offsetX, offsetX + imageWidth),
            y = point.y.coerceIn(offsetY, offsetY + imageHeight),
        )
        val selection = start?.let { first ->
            end?.let { second ->
                val left = min(first.x, second.x)
                val top = min(first.y, second.y)
                val right = max(first.x, second.x)
                val bottom = max(first.y, second.y)
                ScreenSelection(
                    left = ((left - offsetX) / imageWidth).coerceIn(0f, 1f),
                    top = ((top - offsetY) / imageHeight).coerceIn(0f, 1f),
                    right = ((right - offsetX) / imageWidth).coerceIn(0f, 1f),
                    bottom = ((bottom - offsetY) / imageHeight).coerceIn(0f, 1f),
                )
            }
        }
        val selectionIsLargeEnough = start != null && end != null &&
            abs(end!!.x - start!!.x) >= with(density) { 24.dp.toPx() } &&
            abs(end!!.y - start!!.y) >= with(density) { 24.dp.toPx() }

        Image(
            bitmap = screenshot.asImageBitmap(),
            contentDescription = "当前系统截图，拖动以圈选识别区域",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .align(Alignment.Center)
                .size(
                    width = with(density) { imageWidth.toDp() },
                    height = with(density) { imageHeight.toDp() },
                ),
        )
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(screenshot, imageWidth, imageHeight, offsetX, offsetY) {
                    detectDragGestures(
                        onDragStart = { point ->
                            start = clampToImage(point)
                            end = start
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            end = clampToImage(change.position)
                        },
                    )
                },
        ) {
            val first = start
            val second = end
            if (first != null && second != null) {
                val left = min(first.x, second.x)
                val top = min(first.y, second.y)
                val width = abs(second.x - first.x)
                val height = abs(second.y - first.y)
                val imageRight = offsetX + imageWidth
                val imageBottom = offsetY + imageHeight
                drawRect(
                    color = Color.Black.copy(alpha = 0.45f),
                    topLeft = Offset(offsetX, offsetY),
                    size = androidx.compose.ui.geometry.Size(imageWidth, (top - offsetY).coerceAtLeast(0f)),
                )
                drawRect(
                    color = Color.Black.copy(alpha = 0.45f),
                    topLeft = Offset(offsetX, top + height),
                    size = androidx.compose.ui.geometry.Size(
                        imageWidth,
                        (imageBottom - (top + height)).coerceAtLeast(0f),
                    ),
                )
                drawRect(
                    color = Color.Black.copy(alpha = 0.45f),
                    topLeft = Offset(offsetX, top),
                    size = androidx.compose.ui.geometry.Size(
                        (left - offsetX).coerceAtLeast(0f),
                        height,
                    ),
                )
                drawRect(
                    color = Color.Black.copy(alpha = 0.45f),
                    topLeft = Offset(left + width, top),
                    size = androidx.compose.ui.geometry.Size(
                        (imageRight - (left + width)).coerceAtLeast(0f),
                        height,
                    ),
                )
                drawRect(
                    color = accent,
                    topLeft = Offset(left, top),
                    size = androidx.compose.ui.geometry.Size(width, height),
                    style = Stroke(width = 3.dp.toPx()),
                )
            }
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.72f))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "拖动框选要识别的区域；只会把圈选部分交给屏幕理解模型。",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("取消", color = Color.White)
                }
                Button(
                    enabled = selectionIsLargeEnough && selection != null,
                    onClick = { selection?.let(onConfirm) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("识别圈选内容")
                }
            }
        }
    }
}

private enum class AssistantOverlayRole {
    USER,
    ASSISTANT,
    STATUS,
}

private data class AssistantOverlayEntry(
    val key: String,
    val role: AssistantOverlayRole,
    val text: String,
)

private fun MutableList<AssistantOverlayEntry>.upsert(entry: AssistantOverlayEntry) {
    val index = indexOfFirst { it.key == entry.key }
    if (index >= 0) {
        this[index] = entry
    } else {
        add(entry)
    }
}

@Composable
private fun AssistantMessageBubble(entry: AssistantOverlayEntry) {
    val isUser = entry.role == AssistantOverlayRole.USER
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 520.dp),
            shape = RoundedCornerShape(18.dp),
            color = when (entry.role) {
                AssistantOverlayRole.USER -> MaterialTheme.colorScheme.primaryContainer
                AssistantOverlayRole.ASSISTANT -> MaterialTheme.colorScheme.surfaceVariant
                AssistantOverlayRole.STATUS -> MaterialTheme.colorScheme.errorContainer
            },
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(
                    when (entry.role) {
                        AssistantOverlayRole.USER -> "你"
                        AssistantOverlayRole.ASSISTANT -> "Murong"
                        AssistantOverlayRole.STATUS -> "状态"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(entry.text, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun AssistantMicrophoneButton(
    listening: Boolean,
    enabled: Boolean,
    busy: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCancel: () -> Unit,
) {
    val currentListening by rememberUpdatedState(listening)
    val currentEnabled by rememberUpdatedState(enabled)
    val currentStart by rememberUpdatedState(onStart)
    val currentStop by rememberUpdatedState(onStop)
    val currentCancel by rememberUpdatedState(onCancel)
    val description = if (listening) "停止录音" else "点按或长按说话"
    val gestureModifier = if (enabled) {
        Modifier.pointerInput(Unit) {
            awaitEachGesture {
                val wasListening = currentListening
                val down = awaitFirstDown(
                    requireUnconsumed = false,
                    pass = PointerEventPass.Initial,
                )
                down.consume()
                if (!wasListening) currentStart()
                var releaseTime = down.uptimeMillis
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val heldPointer = event.changes.firstOrNull { it.id == down.id }
                    event.changes.forEach { it.consume() }
                    if (heldPointer == null) {
                        currentCancel()
                        break
                    }
                    releaseTime = heldPointer.uptimeMillis
                    if (!heldPointer.pressed) {
                        val wasLongPress =
                            releaseTime - down.uptimeMillis >= HOLD_TO_TALK_MILLIS
                        if (wasListening || wasLongPress) currentStop()
                        break
                    }
                }
            }
        }
    } else {
        Modifier
    }
    Surface(
        modifier = Modifier
            .size(58.dp)
            .semantics {
                role = Role.Button
                contentDescription = description
                onClick {
                    if (currentEnabled) {
                        if (currentListening) currentStop() else currentStart()
                    }
                    currentEnabled
                }
            }
            .then(gestureModifier),
        shape = CircleShape,
        color = if (enabled) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        contentColor = if (enabled) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        shadowElevation = 4.dp,
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                Icon(
                    if (listening) Icons.Outlined.Stop else Icons.Outlined.Mic,
                    contentDescription = null,
                )
            }
        }
    }
}

private const val AUTO_SUBMIT_DELAY_MILLIS = 900L
private const val HOLD_TO_TALK_MILLIS = 450L

private fun invocationLabel(source: String): String = when (source) {
    "wake_word" -> "由“慕容慕容”+ 本机声纹验证唤醒"
    "volume_shortcut" -> "由音量加减键组合三连按唤醒"
    "pinned_shortcut" -> "由桌面快捷方式唤醒"
    "settings_test" -> "弹窗测试"
    else -> "系统默认助理"
}
