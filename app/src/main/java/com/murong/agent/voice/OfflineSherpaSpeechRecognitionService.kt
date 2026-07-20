package com.murong.agent.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.murong.agent.core.voice.VoiceRecognitionEvent
import com.murong.agent.core.voice.VoiceRecognitionRequest
import com.murong.agent.core.voice.VoiceRecognitionService
import com.murong.agent.core.voice.VoiceRecognitionState
import com.murong.agent.core.voice.normalizeVoiceRecognitionText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.sqrt

/**
 * User-initiated, on-device speech recognition. The current streaming model consumes PCM as it
 * is recorded and publishes partial text; the previous SenseVoice package remains a whole-press
 * fallback. PCM is memory-only and is never written to a file, log, or chat record.
 */
internal class OfflineSherpaSpeechRecognitionService(
    context: Context,
    private val modelManager: OfflineVoiceModelManager,
) : VoiceRecognitionService {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sessionLock = Any()
    private val streamingRecognizerLock = Any()
    private var activeSession: RecordingSession? = null
    private var cachedStreamingRecognizer: CachedStreamingRecognizer? = null
    @Volatile private var closed = false
    private val _state = MutableStateFlow(VoiceRecognitionState.IDLE)
    private val _partialText = MutableStateFlow("")
    private val _volume = MutableStateFlow(0f)
    private val _events = MutableSharedFlow<VoiceRecognitionEvent>(extraBufferCapacity = 2)

    override val state: StateFlow<VoiceRecognitionState> = _state.asStateFlow()
    override val partialText: StateFlow<String> = _partialText.asStateFlow()
    override val volume: StateFlow<Float> = _volume.asStateFlow()
    override val events: SharedFlow<VoiceRecognitionEvent> = _events.asSharedFlow()

    fun isAvailable(): Boolean = modelManager.installedFiles() != null

    /** Load the streaming graph before the user presses the microphone. */
    fun prepareStreamingModel() {
        val files = modelManager.installedFiles() as? StreamingVoiceModelFiles ?: return
        scope.launch {
            runCatching { getOrCreateStreamingRecognizer(files) }
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun start(request: VoiceRecognitionRequest) = withContext(Dispatchers.Default) {
        cancelInternal()
        val files = checkNotNull(modelManager.installedFiles()) { "离线中英模型尚未安装" }
        _state.value = VoiceRecognitionState.PREPARING
        val recorder = createRecorder()
        val session = RecordingSession(
            recorder = recorder,
            languageTag = request.languageTag,
            modelFiles = files,
        )
        synchronized(sessionLock) { activeSession = session }
        try {
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "无法启动麦克风录音"
            }
            _partialText.value = ""
            _volume.value = 0f
            _state.value = VoiceRecognitionState.LISTENING
            session.recordingJob = scope.launch { collectPcm(session) }
            if (files is StreamingVoiceModelFiles) {
                session.decoderJob = scope.launch { decodeStreaming(session, files) }
            }
        } catch (error: Throwable) {
            releaseRecorder(recorder)
            synchronized(sessionLock) {
                if (activeSession === session) activeSession = null
            }
            _state.value = VoiceRecognitionState.ERROR
            _events.tryEmit(VoiceRecognitionEvent.Failure("无法打开麦克风，请检查录音权限后重试"))
            throw error
        }
    }

    override suspend fun stop(): String = withContext(Dispatchers.Default) {
        val session = synchronized(sessionLock) { activeSession } ?: return@withContext ""
        _state.value = VoiceRecognitionState.FINALIZING
        // AudioRecord.read() is blocking. Mark this as an expected shutdown before stop() wakes
        // the reader; otherwise several ROMs report ERROR_INVALID_OPERATION and the collector
        // mistakes a normal finger release for a broken microphone session.
        session.stopping = true
        session.stopRecording()
        session.recordingJob?.join()
        session.audioChunks.close()
        session.decoderJob?.join()
        releaseRecorder(session.recorder)
        if (session.cancelled) return@withContext ""
        if (session.failed) {
            fail(
                session,
                if (session.modelFiles is StreamingVoiceModelFiles) {
                    "实时语音识别中断，请重试"
                } else {
                    "录音中断，请重试"
                },
            )
            return@withContext ""
        }
        if (session.recordedSampleCount <= 0) {
            fail(session, "没有录到声音，请靠近麦克风后重试")
            return@withContext ""
        }
        val recognizedText = when (val modelFiles = session.modelFiles) {
            is StreamingVoiceModelFiles -> session.latestText
            is SenseVoiceModelFiles -> {
                val samples = session.samples.takeAsFloatArrayAndClear()
                runCatching { recognizeSenseVoice(session, modelFiles, samples) }
                    .getOrElse {
                        if (!session.cancelled && isCurrent(session)) {
                            fail(session, "离线语音识别失败，请重试")
                        }
                        return@withContext ""
                }
            }
        }.trim()
        val text = if (session.modelFiles is StreamingVoiceModelFiles) {
            normalizeVoiceRecognitionText(recognizedText)
        } else recognizedText
        val finalText = text.trim()
        if (!session.cancelled && isCurrent(session)) {
            if (finalText.isBlank()) {
                fail(session, "没有识别到清晰语音，请重试")
            } else {
                finish(session, finalText)
            }
        }
        finalText
    }

    override suspend fun cancel() = withContext(Dispatchers.Default) {
        cancelInternal()
        Unit
    }

    private fun collectPcm(session: RecordingSession) {
        val buffer = ShortArray(PCM_BUFFER_SAMPLES)
        try {
            while (!session.cancelled && !session.stopping) {
                val count = session.recorder.read(
                    buffer,
                    0,
                    buffer.size,
                    AudioRecord.READ_BLOCKING,
                )
                if (count <= 0) {
                    if (!session.cancelled && !session.stopping) {
                        throw IllegalStateException("麦克风读取失败：$count")
                    }
                    break
                }
                session.recordedSampleCount += count
                check(session.recordedSampleCount <= MAX_PCM_SAMPLES) { "单次语音最长支持 90 秒" }
                when (session.modelFiles) {
                    is StreamingVoiceModelFiles -> {
                        val samples = FloatArray(count) { index -> buffer[index] / 32768f }
                        check(session.audioChunks.trySend(samples).isSuccess) { "实时识别缓冲区已关闭" }
                    }
                    is SenseVoiceModelFiles -> session.samples.append(buffer, count)
                }
                _volume.value = calculateVolume(buffer, count)
            }
        } catch (error: Throwable) {
            if (
                error !is CancellationException &&
                !session.cancelled &&
                !session.stopping &&
                isCurrent(session)
            ) {
                session.failed = true
                session.stopRecording()
            }
        }
    }

    private suspend fun decodeStreaming(session: RecordingSession, files: StreamingVoiceModelFiles) {
        var recognizer: OnlineRecognizer? = null
        var stream: OnlineStream? = null
        try {
            recognizer = getOrCreateStreamingRecognizer(files)
            stream = recognizer.createStream()
            for (samples in session.audioChunks) {
                if (session.cancelled) break
                stream.acceptWaveform(samples, SAMPLE_RATE)
                publishReadyStreamingText(session, recognizer, stream)
            }
            if (!session.cancelled) {
                stream.inputFinished()
                publishReadyStreamingText(session, recognizer, stream)
            }
        } catch (error: Throwable) {
            if (error !is CancellationException && !session.cancelled && isCurrent(session)) {
                session.failed = true
                session.stopping = true
                session.stopRecording()
            }
        } finally {
            stream?.release()
            // close() avoids releasing a cached recognizer while a native decode call is active;
            // the owning decoder performs that final release after its stream is gone.
            if (closed) recognizer?.release()
        }
    }

    private fun getOrCreateStreamingRecognizer(files: StreamingVoiceModelFiles): OnlineRecognizer =
        synchronized(streamingRecognizerLock) {
            check(!closed) { "语音识别服务已关闭" }
            val key = listOf(
                files.encoderFile.absolutePath,
                files.decoderFile.absolutePath,
                files.joinerFile.absolutePath,
                files.tokensFile.absolutePath,
            ).joinToString("\u0000")
            cachedStreamingRecognizer?.takeIf { it.modelKey == key }?.recognizer
                ?: createStreamingRecognizer(files).also { created ->
                    cachedStreamingRecognizer?.recognizer?.release()
                    cachedStreamingRecognizer = CachedStreamingRecognizer(key, created)
                }
        }

    private fun createStreamingRecognizer(files: StreamingVoiceModelFiles): OnlineRecognizer =
        OnlineRecognizer(
            assetManager = null,
            config = OnlineRecognizerConfig(
                featConfig = FeatureConfig(
                    sampleRate = SAMPLE_RATE,
                    featureDim = 80,
                    dither = 0f,
                ),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = files.encoderFile.absolutePath,
                        decoder = files.decoderFile.absolutePath,
                        joiner = files.joinerFile.absolutePath,
                    ),
                    tokens = files.tokensFile.absolutePath,
                    numThreads = 2,
                    provider = "cpu",
                ),
                enableEndpoint = false,
                decodingMethod = "greedy_search",
                maxActivePaths = 4,
            ),
        )

    private fun publishReadyStreamingText(
        session: RecordingSession,
        recognizer: OnlineRecognizer,
        stream: OnlineStream,
    ) {
        while (!session.cancelled && recognizer.isReady(stream)) {
            recognizer.decode(stream)
        }
        if (session.cancelled) return
        val text = recognizer.getResult(stream).text.trim()
        if (text != session.latestText) {
            session.latestText = text
            if (isCurrent(session)) _partialText.value = text
        }
    }

    private fun recognizeSenseVoice(
        session: RecordingSession,
        files: SenseVoiceModelFiles,
        samples: FloatArray,
    ): String {
        val recognizer = OfflineRecognizer(
            assetManager = null,
            config = OfflineRecognizerConfig(
                modelConfig = OfflineModelConfig(
                    senseVoice = OfflineSenseVoiceModelConfig(
                        model = files.modelFile.absolutePath,
                        language = sherpaLanguage(session.languageTag),
                        useInverseTextNormalization = true,
                    ),
                    tokens = files.tokensFile.absolutePath,
                    numThreads = 2,
                    provider = "cpu",
                ),
            ),
        )
        return try {
            val stream = recognizer.createStream()
            try {
                stream.acceptWaveform(samples, SAMPLE_RATE)
                recognizer.decode(stream)
                recognizer.getResult(stream).text.trim()
            } finally {
                stream.release()
            }
        } finally {
            recognizer.release()
        }
    }

    private fun finish(session: RecordingSession, text: String) {
        synchronized(sessionLock) {
            if (activeSession === session) activeSession = null
        }
        _partialText.value = ""
        _volume.value = 0f
        _events.tryEmit(VoiceRecognitionEvent.FinalText(text))
        _state.value = VoiceRecognitionState.IDLE
    }

    private fun fail(session: RecordingSession, message: String) {
        synchronized(sessionLock) {
            if (activeSession === session) activeSession = null
        }
        session.audioChunks.close()
        session.samples.clear()
        _partialText.value = ""
        _volume.value = 0f
        _events.tryEmit(VoiceRecognitionEvent.Failure(message))
        _state.value = VoiceRecognitionState.ERROR
    }

    private fun cancelInternal(): RecordingSession? {
        val session = synchronized(sessionLock) {
            activeSession.also { activeSession = null }
        }
        session?.cancelled = true
        session?.stopping = true
        session?.stopRecording()
        session?.audioChunks?.close()
        session?.recordingJob?.cancel()
        session?.decoderJob?.cancel()
        releaseRecorder(session?.recorder)
        session?.samples?.clear()
        _partialText.value = ""
        _volume.value = 0f
        _state.value = VoiceRecognitionState.IDLE
        return session
    }

    @SuppressLint("MissingPermission")
    private fun createRecorder(): AudioRecord {
        val minimum = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minimum > 0) { "设备不支持 16 kHz 单声道录音" }
        val bufferSize = maxOf(minimum * 2, PCM_BUFFER_SAMPLES * 2)
        // Some ColorOS/OPlus builds expose VOICE_RECOGNITION to third-party apps but feed its
        // vendor processing path near-silent PCM. A plain MIC source is the most portable input;
        // retain VOICE_RECOGNITION only as a fallback for devices that reject MIC.
        return listOf(MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .asSequence()
            .map { source ->
                AudioRecord(
                    source,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize,
                )
            }
            .firstOrNull { recorder ->
                if (recorder.state == AudioRecord.STATE_INITIALIZED) true else {
                    recorder.release()
                    false
                }
            }
            ?: error("设备没有可用的录音输入")
    }

    private fun releaseRecorder(recorder: AudioRecord?) {
        recorder ?: return
        runCatching { recorder.stop() }
        runCatching { recorder.release() }
    }

    private fun isCurrent(session: RecordingSession): Boolean = synchronized(sessionLock) {
        activeSession === session
    }

    override fun close() {
        val session = cancelInternal()
        scope.cancel()
        synchronized(streamingRecognizerLock) {
            closed = true
            val cached = cachedStreamingRecognizer
            cachedStreamingRecognizer = null
            if (session?.decoderJob?.isCompleted != false) cached?.recognizer?.release()
        }
    }

    private data class CachedStreamingRecognizer(
        val modelKey: String,
        val recognizer: OnlineRecognizer,
    )

    private class RecordingSession(
        val recorder: AudioRecord,
        val languageTag: String?,
        val modelFiles: OfflineVoiceModelFiles,
        val samples: ShortSampleAccumulator = ShortSampleAccumulator(),
        val audioChunks: Channel<FloatArray> = Channel(Channel.UNLIMITED),
        var recordingJob: Job? = null,
        var decoderJob: Job? = null,
        @Volatile var latestText: String = "",
        @Volatile var recordedSampleCount: Int = 0,
        @Volatile var cancelled: Boolean = false,
        @Volatile var stopping: Boolean = false,
        @Volatile var failed: Boolean = false,
    ) {
        fun stopRecording() {
            runCatching { recorder.stop() }
        }
    }

    private class ShortSampleAccumulator {
        private var values = ShortArray(INITIAL_PCM_CAPACITY)
        private var size = 0

        fun append(source: ShortArray, count: Int) {
            val boundedCount = count.coerceIn(0, source.size)
            check(size + boundedCount <= MAX_PCM_SAMPLES) { "单次语音最长支持 90 秒" }
            ensureCapacity(size + boundedCount)
            source.copyInto(values, destinationOffset = size, endIndex = boundedCount)
            size += boundedCount
        }

        fun takeAsFloatArrayAndClear(): FloatArray {
            val result = FloatArray(size) { index -> values[index] / 32768f }
            clear()
            return result
        }

        fun clear() {
            values.fill(0)
            size = 0
        }

        private fun ensureCapacity(required: Int) {
            if (required <= values.size) return
            var next = values.size
            while (next < required) next = (next * 2).coerceAtMost(MAX_PCM_SAMPLES)
            values = values.copyOf(next)
        }
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val PCM_BUFFER_SAMPLES = 1_024
        const val INITIAL_PCM_CAPACITY = SAMPLE_RATE * 2
        const val MAX_PCM_SAMPLES = SAMPLE_RATE * 90

        fun sherpaLanguage(languageTag: String?): String = when (
            languageTag?.let(Locale::forLanguageTag)?.language?.lowercase(Locale.ROOT)
        ) {
            "zh" -> "zh"
            "en" -> "en"
            else -> "auto"
        }

        fun calculateVolume(samples: ShortArray, count: Int): Float {
            if (count <= 0) return 0f
            var sum = 0.0
            repeat(count) { index ->
                val normalized = samples[index] / 32768.0
                sum += normalized * normalized
            }
            return (sqrt(sum / count) * 4).toFloat().coerceIn(0f, 1f)
        }
    }
}
