package com.murong.agent.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.murong.agent.core.voice.VoicePlaybackEvent
import com.murong.agent.core.voice.VoicePlaybackRequest
import com.murong.agent.core.voice.VoicePlaybackService
import com.murong.agent.core.voice.VoicePlaybackState
import com.murong.agent.core.voice.splitVoicePlaybackText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

/**
 * On-device TTS using the already-bundled Sherpa-onnx runtime and the user-installed
 * VITS Chinese voice model. It works without any system TTS engine, which several vendor
 * ROMs ship broken for third-party apps (e.g. the OPPO/Realme offline engine loops on
 * error 1920 and never synthesizes).
 */
internal class SherpaOfflineTtsService(
    context: Context,
    private val modelManager: OfflineTtsModelManager,
) : VoicePlaybackService {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(VoicePlaybackState.IDLE)
    private val _activeMessageId = MutableStateFlow<Long?>(null)
    private val _events = MutableSharedFlow<VoicePlaybackEvent>(extraBufferCapacity = 2)
    private val ttsLock = Any()
    private var tts: OfflineTts? = null
    private var playbackJob: Job? = null
    @Volatile
    private var currentTrack: AudioTrack? = null
    private val generationExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var paused = false
    @Volatile private var stopRequested = false
    @Volatile private var generation = 0L
    private var lastRequest: VoicePlaybackRequest? = null

    override val state: StateFlow<VoicePlaybackState> = _state.asStateFlow()
    override val activeMessageId: StateFlow<Long?> = _activeMessageId.asStateFlow()
    override val events: SharedFlow<VoicePlaybackEvent> = _events.asSharedFlow()

    private fun buildPlaybackSegments(text: String): List<String> {
        // Streaming synthesis hands the first audio chunks to the player as soon as they are
        // ready, so there is no need to keep the opening segment artificially short. A ~70
        // character cap bounds how long a single native call can run before the next stop
        // boundary, while sentence boundaries keep the prosody natural.
        return splitVoicePlaybackText(text, maxCharactersPerSegment = 70)
    }

    override suspend fun speak(request: VoicePlaybackRequest) = withContext(Dispatchers.Main.immediate) {
        stopRequested = true
        paused = false
        generation += 1
        playbackJob?.cancel()
        // Only stop the track here; release happens on the generation worker so
        // it can never race a native callback that is still writing samples.
        runCatching { currentTrack?.stop() }
        stopRequested = false
        lastRequest = request
        _activeMessageId.value = request.messageId
        _state.value = VoicePlaybackState.PREPARING
        playbackJob = scope.launch {
            runCatching { speakInternal(request) }.onFailure { error ->
                if (error is CancellationException) throw error
                _state.value = VoicePlaybackState.ERROR
                _activeMessageId.value = null
                _events.tryEmit(
                    VoicePlaybackEvent.Failure(
                        request.messageId,
                        "离线朗读失败：${error.message ?: "未知错误"}",
                    )
                )
            }
        }
    }

    private suspend fun speakInternal(request: VoicePlaybackRequest) {
        val files = checkNotNull(modelManager.installedFiles()) {
            "离线朗读模型尚未安装"
        }
        val engine = ensureTts(files)
        val speed = request.rate.coerceIn(0.5f, 2f)
        val myGeneration = generation
        val segments = buildPlaybackSegments(request.text)
        check(segments.isNotEmpty()) { "没有可朗读的文本" }
        val sampleRate = engine.sampleRate()
        val track = createSpeechTrack(sampleRate)
        currentTrack = track
        val writtenShorts = AtomicLong(0)
        var completed = true
        try {
            for ((index, segment) in segments.withIndex()) {
                if (stopRequested || paused || generation != myGeneration) {
                    completed = false
                    break
                }
                _state.value = VoicePlaybackState.SPEAKING
                val startedAt = System.currentTimeMillis()
                val audio = streamSegment(engine, segment, speed, track, myGeneration, writtenShorts)
                val elapsed = System.currentTimeMillis() - startedAt
                Log.i(
                    "MurongOfflineTts",
                    "segment $index/${segments.size} streamed in ${elapsed}ms chars=${segment.length}"
                )
                if (audio == null || stopRequested || paused || generation != myGeneration) {
                    completed = false
                    break
                }
            }
            // Let the tail that is already queued in the AudioTrack finish playing.
            while (
                completed &&
                !stopRequested &&
                !paused &&
                generation == myGeneration &&
                track.playState == AudioTrack.PLAYSTATE_PLAYING &&
                track.playbackHeadPosition < writtenShorts.get()
            ) {
                Thread.sleep(20)
            }
        } finally {
            runCatching { track.stop() }
            runCatching { track.release() }
            if (currentTrack === track) currentTrack = null
        }
        if (completed && !stopRequested && !paused && generation == myGeneration) {
            _state.value = VoicePlaybackState.IDLE
            _activeMessageId.value = null
            _events.tryEmit(VoicePlaybackEvent.Completed(request.messageId))
        }
    }

    /**
     * sherpa-onnx streaming generation is a blocking native call that cannot be cancelled.
     * It runs on a dedicated single worker so stop() can return immediately and stale
     * generations are discarded instead of fighting the next request. Audio chunks arrive
     * through the callback as they are synthesized, so the first sound starts within
     * milliseconds and consecutive segments play back-to-back. The lambda's JVM method is
     * invoke([F)Ljava/lang/Integer;, which the sherpa-onnx JNI bridge resolves reflectively;
     * the package-wide keep rule in proguard-rules.pro preserves it in release builds.
     */
    private suspend fun streamSegment(
        engine: OfflineTts,
        text: String,
        speed: Float,
        track: AudioTrack,
        myGeneration: Long,
        writtenShorts: AtomicLong,
    ): com.k2fsa.sherpa.onnx.GeneratedAudio? = suspendCancellableCoroutine { continuation ->
        generationExecutor.execute {
            val result = runCatching {
                engine.generateWithCallback(text, 0, speed) { samples ->
                    if (
                        !stopRequested &&
                        !paused &&
                        generation == myGeneration &&
                        currentTrack === track
                    ) {
                        val written = runCatching { writeShortsBlocking(track, samples) }.getOrDefault(0)
                        writtenShorts.addAndGet(written.toLong())
                    }
                    samples.size
                }
            }.getOrNull()
            if (continuation.isActive) {
                continuation.resume(result)
            }
        }
    }

    private suspend fun ensureTts(files: OfflineTtsModelFiles): OfflineTts {
        synchronized(ttsLock) {
            tts?.let { return it }
            val vits = OfflineTtsVitsModelConfig(
                model = files.modelFile.absolutePath,
                lexicon = files.lexiconFile?.absolutePath.orEmpty(),
                tokens = files.tokensFile.absolutePath,
                dataDir = files.dataDir?.absolutePath.orEmpty(),
                dictDir = files.dictDir?.absolutePath.orEmpty(),
                noiseScale = 0.667f,
                noiseScaleW = 0.8f,
                lengthScale = 1.0f,
            )
            // Four threads keep sentence synthesis close to real time on phones.
            val model = OfflineTtsModelConfig(vits = vits, numThreads = 4)
            val config = OfflineTtsConfig(model = model)
            // A non-null AssetManager makes sherpa-onnx resolve the model through
            // assets/ and abort on a filesystem path; the recognizer passes null for
            // the same reason, which routes to the file-based native loader.
            val created = OfflineTts(assetManager = null, config = config)
            tts = created
            return created
        }
    }

    private fun createSpeechTrack(sampleRate: Int): AudioTrack {
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuffer, 32768))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private fun writeShortsBlocking(track: AudioTrack, samples: FloatArray): Int {
        val shorts = ShortArray(samples.size)
        for (index in samples.indices) {
            val value = samples[index]
            shorts[index] = when {
                value >= 1f -> Short.MAX_VALUE
                value <= -1f -> Short.MIN_VALUE
                else -> (value * Short.MAX_VALUE).toInt().toShort()
            }
        }
        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
            track.play()
        }
        var offset = 0
        while (offset < shorts.size && !stopRequested && !paused) {
            val written = track.write(shorts, offset, shorts.size - offset, AudioTrack.WRITE_NON_BLOCKING)
            if (written > 0) {
                offset += written
            } else {
                // The output buffer is momentarily full (or the track was just stopped).
                // Poll briefly so pause()/stop() stay responsive from another thread.
                try {
                    Thread.sleep(5)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
        return offset
    }

    override suspend fun pause() = withContext(Dispatchers.Main.immediate) {
        if (_state.value !in setOf(VoicePlaybackState.PREPARING, VoicePlaybackState.SPEAKING)) {
            return@withContext
        }
        paused = true
        runCatching { currentTrack?.pause() }
        _state.value = VoicePlaybackState.PAUSED
    }

    override suspend fun resume() = withContext(Dispatchers.Main.immediate) {
        val request = lastRequest ?: return@withContext
        if (_state.value != VoicePlaybackState.PAUSED) return@withContext
        paused = false
        speak(request)
    }

    override suspend fun stop() = withContext(Dispatchers.Main.immediate) {
        stopRequested = true
        paused = false
        generation += 1
        playbackJob?.cancel()
        runCatching { currentTrack?.stop() }
        _state.value = VoicePlaybackState.IDLE
        _activeMessageId.value = null
    }

    override fun close() {
        stopRequested = true
        playbackJob?.cancel()
        runCatching { currentTrack?.stop() }
        synchronized(ttsLock) {
            runCatching { tts?.release() }
            tts = null
        }
        generationExecutor.shutdownNow()
        scope.cancel()
    }
}

/**
 * Prefers the on-device Sherpa TTS when its model is installed and transparently falls
 * back to the system TTS engine when the offline engine fails or is not installed yet.
 * This makes read-aloud robust on vendor ROMs whose default system engine rejects
 * third-party synthesis requests.
 */
internal class ResilientVoicePlaybackService(
    context: Context,
    private val modelManager: OfflineTtsModelManager,
) : VoicePlaybackService {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val systemService = AndroidTextToSpeechService(appContext)
    private val offlineService = SherpaOfflineTtsService(appContext, modelManager)
    private val _state = MutableStateFlow(VoicePlaybackState.IDLE)
    private val _activeMessageId = MutableStateFlow<Long?>(null)
    private val _events = MutableSharedFlow<VoicePlaybackEvent>(extraBufferCapacity = 2)
    private var active: VoicePlaybackService = systemService
    private var pendingRequest: VoicePlaybackRequest? = null
    private var offlineFallbackUsed = false

    override val state: StateFlow<VoicePlaybackState> = _state.asStateFlow()
    override val activeMessageId: StateFlow<Long?> = _activeMessageId.asStateFlow()
    override val events: SharedFlow<VoicePlaybackEvent> = _events.asSharedFlow()

    init {
        bind(systemService)
        bind(offlineService)
    }

    private fun bind(service: VoicePlaybackService) {
        scope.launch {
            service.events.collect { event ->
                if (service !== active) return@collect
                when (event) {
                    is VoicePlaybackEvent.Completed -> {
                        _activeMessageId.value = null
                        _state.value = VoicePlaybackState.IDLE
                        _events.tryEmit(event)
                    }

                    is VoicePlaybackEvent.Failure -> {
                        if (service === offlineService && !offlineFallbackUsed) {
                            offlineFallbackUsed = true
                            val request = pendingRequest ?: return@collect
                            _state.value = VoicePlaybackState.PREPARING
                            active = systemService
                            scope.launch {
                                runCatching { systemService.speak(request) }
                            }
                        } else {
                            _activeMessageId.value = null
                            _state.value = VoicePlaybackState.ERROR
                            _events.tryEmit(event)
                        }
                    }
                }
            }
        }
        scope.launch {
            service.state.collect { next -> if (service === active) _state.value = next }
        }
        scope.launch {
            service.activeMessageId.collect { next -> if (service === active) _activeMessageId.value = next }
        }
    }

    override suspend fun speak(request: VoicePlaybackRequest) {
        pendingRequest = request
        offlineFallbackUsed = false
        active = if (modelManager.state.value.isInstalled) offlineService else systemService
        active.speak(request)
    }

    override suspend fun pause() = active.pause()

    override suspend fun resume() = active.resume()

    override suspend fun stop() = active.stop()

    override fun close() {
        scope.cancel()
        systemService.close()
        offlineService.close()
    }
}
