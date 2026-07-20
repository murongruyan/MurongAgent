package com.murong.agent.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.murong.agent.core.voice.VoicePlaybackRequest
import com.murong.agent.core.voice.VoicePlaybackEvent
import com.murong.agent.core.voice.VoicePlaybackService
import com.murong.agent.core.voice.VoicePlaybackState
import com.murong.agent.core.voice.VoiceRecognitionEvent
import com.murong.agent.core.voice.VoiceRecognitionRequest
import com.murong.agent.core.voice.VoiceRecognitionService
import com.murong.agent.core.voice.VoiceRecognitionState
import com.murong.agent.core.voice.VoiceRecognitionProvider
import com.murong.agent.core.voice.normalizeVoiceRecognitionText
import com.murong.agent.core.voice.selectAvailableVoiceRecognitionProvider
import com.murong.agent.core.voice.VoiceSettings
import com.murong.agent.core.voice.splitVoicePlaybackText
import com.murong.agent.core.voice.voicePlaybackTextChunk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.io.Closeable
import java.util.Locale
import java.util.UUID

private val Context.voiceDataStore: DataStore<Preferences> by preferencesDataStore(name = "murong_voice")

data class VoiceInputUiState(
    val recognitionState: VoiceRecognitionState = VoiceRecognitionState.IDLE,
    val partialText: String = "",
    val volume: Float = 0f,
    val finalText: String? = null,
    val errorMessage: String? = null,
    val recognitionServiceUnavailable: Boolean = false,
    val offlineModelUnavailable: Boolean = false,
)

class VoiceSettingsRepository(context: Context) : Closeable {
    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val legacyPreferences = appContext.getSharedPreferences("murong_voice", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(VoiceSettings())
    val settings: StateFlow<VoiceSettings> = _settings.asStateFlow()

    init {
        scope.launch {
            val legacyRaw = legacyPreferences.getString(SETTINGS_KEY, null)
                ?.takeIf { it.isNotBlank() }
            appContext.voiceDataStore.edit { preferences ->
                if (preferences[SETTINGS_KEY_PREFERENCE] == null && legacyRaw != null) {
                    preferences[SETTINGS_KEY_PREFERENCE] = legacyRaw
                }
            }
            appContext.voiceDataStore.data
                .catch { emit(emptyPreferences()) }
                .collect { preferences ->
                    _settings.value = decode(preferences[SETTINGS_KEY_PREFERENCE]).normalized()
                }
        }
    }

    fun update(transform: (VoiceSettings) -> VoiceSettings) {
        val updated = transform(_settings.value).normalized()
        _settings.value = updated
        scope.launch {
            appContext.voiceDataStore.edit { preferences ->
                preferences[SETTINGS_KEY_PREFERENCE] = json.encodeToString(VoiceSettings.serializer(), updated)
            }
        }
    }

    suspend fun exportBackupSnapshot(): VoiceSettings {
        val preferences = appContext.voiceDataStore.data
            .catch { emit(emptyPreferences()) }
            .first()
        val raw = preferences[SETTINGS_KEY_PREFERENCE]
            ?: legacyPreferences.getString(SETTINGS_KEY, null)
        return decode(raw).normalized()
    }

    suspend fun restoreBackupSnapshot(settings: VoiceSettings) {
        val restored = settings.normalized()
        appContext.voiceDataStore.edit { preferences ->
            preferences[SETTINGS_KEY_PREFERENCE] = json.encodeToString(VoiceSettings.serializer(), restored)
        }
        _settings.value = restored
    }

    private fun decode(raw: String?): VoiceSettings = runCatching {
        val value = raw.orEmpty()
        if (value.isBlank()) VoiceSettings() else json.decodeFromString(VoiceSettings.serializer(), value)
    }.getOrDefault(VoiceSettings())

    private fun VoiceSettings.normalized(): VoiceSettings = copy(
        speechRate = speechRate.coerceIn(0.5f, 2f),
        pitch = pitch.coerceIn(0.5f, 2f),
        languageTag = languageTag?.trim()?.takeIf { it.isNotBlank() }
    )

    override fun close() {
        scope.cancel()
    }

    private companion object {
        const val SETTINGS_KEY = "voice_settings"
        val SETTINGS_KEY_PREFERENCE = stringPreferencesKey(SETTINGS_KEY)
    }
}

class VoiceChatController(context: Context) : Closeable {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val settingsRepository = VoiceSettingsRepository(appContext)
    private val offlineModelManager = OfflineVoiceModelManager(appContext)
    private val systemRecognition = AndroidSpeechRecognitionService(appContext)
    private val offlineRecognition = OfflineSherpaSpeechRecognitionService(appContext, offlineModelManager)
    private val playback = AndroidTextToSpeechService(appContext)
    private val _inputState = MutableStateFlow(VoiceInputUiState())
    /** The engine currently visible in the composer; late callbacks from a previous engine are ignored. */
    private var displayedRecognition: VoiceRecognitionService? = null
    private var activeRecognition: VoiceRecognitionService? = null
    /** Continuations become visible only after a whole bounded batch finishes naturally. */
    private val playbackContinuationOffsets = mutableMapOf<Long, Int>()
    private val pendingPlaybackContinuationOffsets = mutableMapOf<Long, Int?>()
    private val _continuablePlaybackMessageIds = MutableStateFlow<Set<Long>>(emptySet())

    val inputState: StateFlow<VoiceInputUiState> = _inputState.asStateFlow()
    val settings: StateFlow<VoiceSettings> = settingsRepository.settings
    val offlineModelState: StateFlow<OfflineVoiceModelUiState> = offlineModelManager.state
    val playbackState: StateFlow<VoicePlaybackState> = playback.state
    /** The sole message currently entitled to control the stop button. */
    val activePlaybackMessageId: StateFlow<Long?> = playback.activeMessageId
    val continuablePlaybackMessageIds: StateFlow<Set<Long>> = _continuablePlaybackMessageIds.asStateFlow()

    init {
        bindRecognition(systemRecognition)
        bindRecognition(offlineRecognition)
        scope.launch {
            offlineModelManager.state.collect { state ->
                if (
                    state.status == OfflineVoiceModelInstallStatus.READY ||
                    state.status == OfflineVoiceModelInstallStatus.LEGACY_READY
                ) {
                    offlineRecognition.prepareStreamingModel()
                }
            }
        }
        scope.launch {
            playback.events.collect { event ->
                when (event) {
                    is VoicePlaybackEvent.Completed -> {
                        val nextOffset = pendingPlaybackContinuationOffsets.remove(event.messageId)
                        if (nextOffset != null) {
                            playbackContinuationOffsets[event.messageId] = nextOffset
                            _continuablePlaybackMessageIds.value = playbackContinuationOffsets.keys.toSet()
                        } else {
                            playbackContinuationOffsets.remove(event.messageId)
                            _continuablePlaybackMessageIds.value = playbackContinuationOffsets.keys.toSet()
                        }
                    }
                    is VoicePlaybackEvent.Failure -> {
                        pendingPlaybackContinuationOffsets.remove(event.messageId)
                        playbackContinuationOffsets.remove(event.messageId)
                        _continuablePlaybackMessageIds.value = playbackContinuationOffsets.keys.toSet()
                        _inputState.value = _inputState.value.copy(errorMessage = event.userMessage)
                    }
                }
            }
        }
    }

    fun startInput() {
        if (!settings.value.inputEnabled) {
            _inputState.value = _inputState.value.copy(errorMessage = "语音输入已在设置中关闭")
            return
        }
        _inputState.value = _inputState.value.copy(
            finalText = null,
            errorMessage = null,
            recognitionServiceUnavailable = false,
            offlineModelUnavailable = false,
        )
        if (activeRecognition != null) return
        val selected = selectRecognition(settings.value)
        if (selected == null) {
            showRecognitionUnavailable(settings.value)
            return
        }
        activeRecognition = selected
        displayedRecognition = selected
        scope.launch {
            runCatching {
                selected.start(VoiceRecognitionRequest(settings.value.languageTag))
            }.onFailure {
                if (displayedRecognition === selected) {
                    _inputState.value = _inputState.value.copy(
                        errorMessage = if (selected === offlineRecognition) {
                            "离线语音识别启动失败，请确认模型安装完整后重试。"
                        } else {
                            "语音识别服务启动失败，请检查系统语音输入设置后重试。"
                        },
                        recognitionServiceUnavailable = selected === systemRecognition,
                        offlineModelUnavailable = selected === offlineRecognition && !offlineRecognition.isAvailable(),
                    )
                }
                if (activeRecognition === selected) activeRecognition = null
            }
        }
    }

    fun stopInput() {
        val selected = activeRecognition ?: return
        scope.launch {
            runCatching { selected.stop() }
                .onFailure {
                    if (displayedRecognition === selected) {
                        _inputState.value = _inputState.value.copy(
                            recognitionState = VoiceRecognitionState.ERROR,
                            partialText = "",
                            errorMessage = "语音识别处理失败，请重试",
                        )
                    }
                }
            if (activeRecognition === selected) activeRecognition = null
        }
    }

    fun cancelInput() {
        val selected = activeRecognition
        scope.launch {
            selected?.cancel()
            if (activeRecognition === selected) activeRecognition = null
            _inputState.value = _inputState.value.copy(
                finalText = null,
                partialText = "",
                errorMessage = null,
                recognitionServiceUnavailable = false,
                offlineModelUnavailable = false,
            )
        }
    }

    fun consumeFinalText() {
        _inputState.value = _inputState.value.copy(finalText = null)
    }

    fun reportInputError(message: String) {
        _inputState.value = _inputState.value.copy(
            errorMessage = message,
            recognitionServiceUnavailable = false,
            offlineModelUnavailable = false,
        )
    }

    /** Opens the system-owned recognition configuration; Murong never installs a provider silently. */
    fun openRecognitionSettings() {
        val recognitionSettings = Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val fallbackSettings = Intent(Settings.ACTION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { appContext.startActivity(recognitionSettings) }
            .recoverCatching { appContext.startActivity(fallbackSettings) }
            .onFailure {
                _inputState.value = _inputState.value.copy(
                    errorMessage = "无法打开系统语音输入设置，请在系统设置中启用语音识别服务。",
                    recognitionServiceUnavailable = true,
                )
            }
    }

    fun updateSettings(transform: (VoiceSettings) -> VoiceSettings) = settingsRepository.update(transform)

    fun installOfflineVoiceModel() = offlineModelManager.install()

    fun deleteOfflineVoiceModel() {
        scope.launch {
            if (activeRecognition === offlineRecognition) {
                offlineRecognition.cancel()
                activeRecognition = null
            }
            offlineModelManager.delete()
        }
    }

    fun speak(messageId: Long, messageText: String) {
        val continuationOffset = playbackContinuationOffsets.remove(messageId) ?: 0
        val chunk = voicePlaybackTextChunk(messageText, startCharacterOffset = continuationOffset)
        if (chunk.text.isBlank()) {
            _inputState.value = _inputState.value.copy(errorMessage = "这条回复没有可朗读的自然语言内容")
            return
        }
        // A new target replaces every previous queue; only a completed queue earns a continue
        // action, so tapping stop cannot skip the remainder of an answer.
        pendingPlaybackContinuationOffsets.clear()
        pendingPlaybackContinuationOffsets[messageId] = chunk.nextCharacterOffset
        _continuablePlaybackMessageIds.value = playbackContinuationOffsets.keys.toSet()
        scope.launch {
            runCatching {
                playback.speak(
                    VoicePlaybackRequest(
                        messageId = messageId,
                        text = chunk.text,
                        languageTag = settings.value.languageTag,
                        rate = settings.value.speechRate,
                        pitch = settings.value.pitch
                    )
                )
            }.onFailure {
                pendingPlaybackContinuationOffsets.remove(messageId)
                _inputState.value = _inputState.value.copy(errorMessage = "当前设备没有可用的文字朗读服务")
            }
        }
    }

    fun stopSpeaking() {
        pendingPlaybackContinuationOffsets.clear()
        scope.launch { playback.stop() }
    }

    fun pauseSpeaking() {
        scope.launch { playback.pause() }
    }

    fun resumeSpeaking() {
        scope.launch { playback.resume() }
    }

    override fun close() {
        scope.cancel()
        systemRecognition.close()
        offlineRecognition.close()
        offlineModelManager.close()
        playback.close()
        settingsRepository.close()
    }

    private fun bindRecognition(recognition: VoiceRecognitionService) {
        scope.launch {
            combine(recognition.state, recognition.partialText, recognition.volume) { state, partial, volume ->
                Triple(state, partial, volume)
            }.collect { (state, partial, volume) ->
                if (displayedRecognition === recognition) {
                    _inputState.value = _inputState.value.copy(
                        recognitionState = state,
                        partialText = normalizeVoiceRecognitionText(partial),
                        volume = volume,
                    )
                }
            }
        }
        scope.launch {
            recognition.events.collect { event ->
                if (displayedRecognition !== recognition) return@collect
                when (event) {
                    is VoiceRecognitionEvent.FinalText -> {
                        val normalized = normalizeVoiceRecognitionText(event.value)
                        _inputState.value = _inputState.value.copy(
                            finalText = normalized.takeIf { it.isNotBlank() },
                            partialText = "",
                            errorMessage = if (normalized.isBlank()) "没有识别到清晰语音，请重试" else null,
                            recognitionServiceUnavailable = false,
                            offlineModelUnavailable = false,
                        )
                        if (activeRecognition === recognition) activeRecognition = null
                    }
                    is VoiceRecognitionEvent.Failure -> {
                        _inputState.value = _inputState.value.copy(
                            errorMessage = event.userMessage,
                            partialText = "",
                            recognitionServiceUnavailable = false,
                            offlineModelUnavailable = false,
                        )
                        if (activeRecognition === recognition) activeRecognition = null
                    }
                }
            }
        }
    }

    private fun selectRecognition(settings: VoiceSettings): VoiceRecognitionService? {
        val systemAvailable = systemRecognition.isAvailable()
        val offlineAvailable = offlineRecognition.isAvailable()
        return when (
            selectAvailableVoiceRecognitionProvider(
                preferred = settings.recognitionProvider,
                systemAvailable = systemAvailable,
                offlineAvailable = offlineAvailable,
            )
        ) {
            VoiceRecognitionProvider.SYSTEM -> systemRecognition
            VoiceRecognitionProvider.OFFLINE -> offlineRecognition
            VoiceRecognitionProvider.AUTOMATIC, null -> null
        }
    }

    private fun showRecognitionUnavailable(settings: VoiceSettings) {
        val systemAvailable = systemRecognition.isAvailable()
        val offlineAvailable = offlineRecognition.isAvailable()
        val message = when (settings.recognitionProvider) {
            VoiceRecognitionProvider.SYSTEM -> "当前设备未注册语音识别服务，请在系统设置安装或启用后重试。"
            VoiceRecognitionProvider.OFFLINE -> "离线中英模型尚未安装，请先在语音设置主动下载。"
            VoiceRecognitionProvider.AUTOMATIC ->
                "未检测到系统语音识别服务，离线中英模型也尚未安装。请在语音设置下载模型，或安装系统识别服务。"
        }
        _inputState.value = _inputState.value.copy(
            errorMessage = message,
            recognitionServiceUnavailable = !systemAvailable && settings.recognitionProvider != VoiceRecognitionProvider.OFFLINE,
            offlineModelUnavailable = !offlineAvailable && settings.recognitionProvider != VoiceRecognitionProvider.SYSTEM,
        )
    }
}

private class AndroidSpeechRecognitionService(context: Context) : VoiceRecognitionService {
    private val appContext = context.applicationContext
    private val _state = MutableStateFlow(VoiceRecognitionState.IDLE)
    private val _partialText = MutableStateFlow("")
    private val _volume = MutableStateFlow(0f)
    private val _events = MutableSharedFlow<VoiceRecognitionEvent>(extraBufferCapacity = 2)
    private var recognizer: SpeechRecognizer? = null
    private var finalResult: CompletableDeferred<String>? = null

    override val state: StateFlow<VoiceRecognitionState> = _state.asStateFlow()
    override val partialText: StateFlow<String> = _partialText.asStateFlow()
    override val volume: StateFlow<Float> = _volume.asStateFlow()
    override val events: SharedFlow<VoiceRecognitionEvent> = _events.asSharedFlow()

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(appContext)

    override suspend fun start(request: VoiceRecognitionRequest) = withContext(Dispatchers.Main.immediate) {
        cancelInternal()
        check(isAvailable()) { "speech recognizer unavailable" }
        _state.value = VoiceRecognitionState.PREPARING
        val engine = recognizer ?: SpeechRecognizer.createSpeechRecognizer(appContext).also { created ->
            created.setRecognitionListener(listener)
            recognizer = created
        }
        finalResult = CompletableDeferred()
        _partialText.value = ""
        _volume.value = 0f
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, request.languageTag ?: Locale.getDefault().toLanguageTag())
        engine.startListening(intent)
    }

    override suspend fun stop(): String = withContext(Dispatchers.Main.immediate) {
        if (_state.value !in setOf(VoiceRecognitionState.PREPARING, VoiceRecognitionState.LISTENING)) return@withContext ""
        _state.value = VoiceRecognitionState.FINALIZING
        recognizer?.stopListening()
        val text = withTimeoutOrNull(2_500) { finalResult?.await().orEmpty() }.orEmpty()
        if (_state.value != VoiceRecognitionState.ERROR) _state.value = VoiceRecognitionState.IDLE
        text
    }

    override suspend fun cancel() = withContext(Dispatchers.Main.immediate) { cancelInternal() }

    private fun cancelInternal() {
        recognizer?.cancel()
        finalResult?.complete("")
        finalResult = null
        _partialText.value = ""
        _volume.value = 0f
        _state.value = VoiceRecognitionState.IDLE
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { _state.value = VoiceRecognitionState.LISTENING }
        override fun onBeginningOfSpeech() { _state.value = VoiceRecognitionState.LISTENING }
        override fun onRmsChanged(rmsdB: Float) { _volume.value = (rmsdB / 12f).coerceIn(0f, 1f) }
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() { if (_state.value == VoiceRecognitionState.LISTENING) _state.value = VoiceRecognitionState.FINALIZING }
        override fun onError(error: Int) {
            val message = when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "需要麦克风权限才能语音输入"
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "语音识别网络不可用"
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没有识别到清晰语音，请重试"
                else -> "语音识别暂时不可用，请重试"
            }
            finalResult?.complete("")
            _events.tryEmit(VoiceRecognitionEvent.Failure(message))
            _state.value = VoiceRecognitionState.ERROR
        }
        override fun onResults(results: Bundle?) { finishWithResults(results) }
        override fun onPartialResults(partialResults: Bundle?) {
            _partialText.value = partialResults.extractRecognitionText()
        }
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun finishWithResults(results: Bundle?) {
        val text = results.extractRecognitionText()
        finalResult?.complete(text)
        _events.tryEmit(VoiceRecognitionEvent.FinalText(text))
        _state.value = VoiceRecognitionState.IDLE
    }

    override fun close() {
        recognizer?.destroy()
        recognizer = null
    }
}

private class AndroidTextToSpeechService(context: Context) : VoicePlaybackService {
    private val appContext = context.applicationContext
    private val _state = MutableStateFlow(VoicePlaybackState.IDLE)
    private val _activeMessageId = MutableStateFlow<Long?>(null)
    private val _events = MutableSharedFlow<VoicePlaybackEvent>(extraBufferCapacity = 2)
    private var engine: TextToSpeech? = null
    private var initialization: CompletableDeferred<Boolean>? = null
    private var activePlaybackSession: PlaybackSession? = null
    private var activeUtteranceId: String? = null
    private var activeUtteranceBaseOffset: Int = 0

    override val state: StateFlow<VoicePlaybackState> = _state.asStateFlow()
    override val activeMessageId: StateFlow<Long?> = _activeMessageId.asStateFlow()
    override val events: SharedFlow<VoicePlaybackEvent> = _events.asSharedFlow()

    override suspend fun speak(request: VoicePlaybackRequest) = withContext(Dispatchers.Main.immediate) {
        // Selecting another answer is an explicit stop of the prior queue.  Do it before an
        // engine lookup so the old answer cannot continue while the new request is preparing.
        activePlaybackSession = null
        activeUtteranceId = null
        _activeMessageId.value = null
        engine?.stop()
        _state.value = VoicePlaybackState.PREPARING
        val textToSpeech = ensureEngine()
        val segments = splitVoicePlaybackText(request.text)
        check(segments.isNotEmpty()) { "text to speech received no readable text" }
        val locale = request.languageTag?.let(Locale::forLanguageTag) ?: Locale.getDefault()
        textToSpeech.language = locale
        textToSpeech.setSpeechRate(request.rate.coerceIn(0.5f, 2f))
        textToSpeech.setPitch(request.pitch.coerceIn(0.5f, 2f))
        val playbackSession = PlaybackSession(request = request, segments = segments)
        activePlaybackSession = playbackSession
        _activeMessageId.value = request.messageId
        textToSpeech.stop()
        textToSpeech.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                if (utteranceId != null && utteranceId == activeUtteranceId) {
                    _state.value = VoicePlaybackState.SPEAKING
                }
            }

            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                val session = activePlaybackSession ?: return
                if (utteranceId == null || utteranceId != activeUtteranceId) return
                session.segmentOffset = (activeUtteranceBaseOffset + start)
                    .coerceIn(0, session.segments[session.segmentIndex].length)
            }

            override fun onDone(utteranceId: String?) {
                val session = activePlaybackSession ?: return
                if (utteranceId == null || utteranceId != activeUtteranceId) return
                activeUtteranceId = null
                session.segmentIndex += 1
                session.segmentOffset = 0
                continuePlayback(textToSpeech, session)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = handlePlaybackError(utteranceId)

            override fun onError(utteranceId: String?, errorCode: Int) = handlePlaybackError(utteranceId)

            private fun handlePlaybackError(utteranceId: String?) {
                if (utteranceId == null || utteranceId != activeUtteranceId) return
                failPlayback(request.messageId, "文字朗读服务播放失败")
            }
        })
        continuePlayback(textToSpeech, playbackSession)
    }

    override suspend fun pause() = withContext(Dispatchers.Main.immediate) {
        if (_state.value !in setOf(VoicePlaybackState.PREPARING, VoicePlaybackState.SPEAKING)) {
            return@withContext
        }
        if (activePlaybackSession == null) return@withContext
        // Invalidate the current utterance before stop(), because some engines report a late
        // completion callback for an interrupted utterance.
        activeUtteranceId = null
        engine?.stop()
        _state.value = VoicePlaybackState.PAUSED
    }

    override suspend fun resume() = withContext(Dispatchers.Main.immediate) {
        val session = activePlaybackSession ?: return@withContext
        if (_state.value != VoicePlaybackState.PAUSED) return@withContext
        val textToSpeech = ensureEngine()
        continuePlayback(textToSpeech, session)
    }

    override suspend fun stop() = withContext(Dispatchers.Main.immediate) {
        activePlaybackSession = null
        activeUtteranceId = null
        _activeMessageId.value = null
        engine?.stop()
        _state.value = VoicePlaybackState.IDLE
    }

    private fun continuePlayback(textToSpeech: TextToSpeech, session: PlaybackSession) {
        if (activePlaybackSession !== session) return
        while (
            session.segmentIndex < session.segments.size &&
            session.segmentOffset >= session.segments[session.segmentIndex].length
        ) {
            session.segmentIndex += 1
            session.segmentOffset = 0
        }
        if (session.segmentIndex >= session.segments.size) {
            activePlaybackSession = null
            activeUtteranceId = null
            _activeMessageId.value = null
            _state.value = VoicePlaybackState.IDLE
            _events.tryEmit(VoicePlaybackEvent.Completed(session.request.messageId))
            return
        }

        val segment = session.segments[session.segmentIndex]
        activeUtteranceBaseOffset = session.segmentOffset.coerceIn(0, segment.length)
        val remainingText = segment.substring(activeUtteranceBaseOffset)
        val utteranceId = "murong-voice-${session.request.messageId}-${UUID.randomUUID()}"
        activeUtteranceId = utteranceId
        _state.value = VoicePlaybackState.PREPARING
        if (textToSpeech.speak(remainingText, TextToSpeech.QUEUE_FLUSH, null, utteranceId) == TextToSpeech.ERROR) {
            failPlayback(session.request.messageId, "文字朗读服务拒绝了本次内容")
        }
    }

    private fun failPlayback(messageId: Long, userMessage: String) {
        activePlaybackSession = null
        activeUtteranceId = null
        _activeMessageId.value = null
        _state.value = VoicePlaybackState.ERROR
        _events.tryEmit(VoicePlaybackEvent.Failure(messageId, userMessage))
    }

    private suspend fun ensureEngine(): TextToSpeech {
        engine?.let { return it }
        val defaultEngine = runCatching { createEngine(null) }.getOrNull()
        if (defaultEngine != null) {
            engine = defaultEngine
            return defaultEngine
        }
        availableTextToSpeechEngines().forEach { packageName ->
            runCatching { createEngine(packageName) }.getOrNull()?.let { discoveredEngine ->
                engine = discoveredEngine
                return discoveredEngine
            }
        }
        error("text to speech unavailable")
    }

    private suspend fun createEngine(enginePackage: String?): TextToSpeech {
        val deferred = CompletableDeferred<Boolean>()
        initialization = deferred
        val created = if (enginePackage.isNullOrBlank()) {
            TextToSpeech(appContext) { result -> deferred.complete(result == TextToSpeech.SUCCESS) }
        } else {
            TextToSpeech(appContext, { result -> deferred.complete(result == TextToSpeech.SUCCESS) }, enginePackage)
        }
        if (withTimeoutOrNull(3_000) { deferred.await() } == true) return created
        created.shutdown()
        error("text to speech engine unavailable${enginePackage?.let { ": $it" }.orEmpty()}")
    }

    /** Some vendor ROMs leave tts_default_synth empty while shipping usable engines. */
    private fun availableTextToSpeechEngines(): List<String> {
        return appContext.packageManager.queryIntentServices(
            Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE),
            0
        )
            .asSequence()
            .filter { it.serviceInfo?.enabled != false }
            .mapNotNull { it.serviceInfo?.packageName?.takeIf(String::isNotBlank) }
            .distinct()
            .toList()
    }

    override fun close() {
        activePlaybackSession = null
        activeUtteranceId = null
        _activeMessageId.value = null
        engine?.stop()
        engine?.shutdown()
        engine = null
        initialization?.cancel()
    }

    private data class PlaybackSession(
        val request: VoicePlaybackRequest,
        val segments: List<String>,
        var segmentIndex: Int = 0,
        var segmentOffset: Int = 0,
    )
}

private fun Bundle?.extractRecognitionText(): String {
    return this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        ?.firstOrNull()
        ?.trim()
        .orEmpty()
}
