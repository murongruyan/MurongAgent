package com.murong.agent.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingManager
import com.murong.agent.ui.assistant.VoiceWakeWordService
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

internal enum class SpeakerProfileStatus {
    NOT_INSTALLED,
    DOWNLOADING,
    READY_TO_ENROLL,
    RECORDING,
    PROCESSING,
    ENROLLED,
    ERROR,
}

internal const val SPEAKER_ENROLLMENT_SAMPLE_COUNT = 5

internal data class SpeakerProfileUiState(
    val status: SpeakerProfileStatus = SpeakerProfileStatus.NOT_INSTALLED,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = SpeakerVerificationModel.MODEL_BYTES,
    val enrollmentSamples: Int = 0,
    val message: String = "声纹模型尚未安装",
) {
    val modelInstalled: Boolean get() = downloadedBytes == totalBytes
    val enrolled: Boolean get() = status == SpeakerProfileStatus.ENROLLED
    val busy: Boolean get() = status in setOf(
        SpeakerProfileStatus.DOWNLOADING,
        SpeakerProfileStatus.RECORDING,
        SpeakerProfileStatus.PROCESSING,
    )
    val progress: Float
        get() = if (totalBytes <= 0L) 0f
        else (downloadedBytes.toDouble() / totalBytes).coerceIn(0.0, 1.0).toFloat()
}

/**
 * Downloads the small speaker-embedding model, enrolls five local samples, and verifies a
 * wake-word utterance. Raw PCM never leaves memory. Only encrypted embeddings are persisted.
 */
internal class SpeakerVerificationManager(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val profileStore = EncryptedSpeakerProfileStore(appContext)
    private val modelDirectory = File(appContext.filesDir, "voice-models/speaker-verification")
    private val modelFile = File(modelDirectory, SpeakerVerificationModel.FILE_NAME)
    private val partialModelFile = File(modelDirectory, "${SpeakerVerificationModel.FILE_NAME}.part")
    private val extractorLock = Any()
    private var extractor: SpeakerEmbeddingExtractor? = null
    private var operationJob: Job? = null
    private val pendingEnrollment = mutableListOf<FloatArray>()
    private val mutableState = MutableStateFlow(snapshot())

    val state: StateFlow<SpeakerProfileUiState> = mutableState.asStateFlow()

    fun refresh() {
        mutableState.value = snapshot()
    }

    fun installModel() {
        if (operationJob?.isActive == true || isModelInstalled()) return
        operationJob = scope.launch {
            try {
                modelDirectory.mkdirs()
                var offset = partialModelFile.length()
                if (offset > SpeakerVerificationModel.MODEL_BYTES) {
                    partialModelFile.delete()
                    offset = 0L
                }
                mutableState.value = SpeakerProfileUiState(
                    status = SpeakerProfileStatus.DOWNLOADING,
                    downloadedBytes = offset,
                    message = "正在下载本地声纹模型…",
                )
                val request = Request.Builder()
                    .url(SpeakerVerificationModel.SOURCE_URL)
                    .apply { if (offset > 0L) header("Range", "bytes=$offset-") }
                    .build()
                client.newCall(request).execute().use { response ->
                    check(response.isSuccessful) { "下载服务器返回 HTTP ${response.code}" }
                    val append = offset > 0L && response.code == 206
                    if (!append) {
                        offset = 0L
                        partialModelFile.delete()
                    }
                    val body = response.body ?: error("下载服务器没有返回模型内容")
                    RandomAccessFile(partialModelFile, "rw").use { output ->
                        if (append) output.seek(offset) else output.setLength(0L)
                        body.byteStream().use { input ->
                            val buffer = ByteArray(1024 * 1024)
                            var written = offset
                            while (true) {
                                ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                written += count
                                mutableState.value = mutableState.value.copy(
                                    downloadedBytes = written,
                                )
                            }
                            output.fd.sync()
                        }
                    }
                }
                check(partialModelFile.length() == SpeakerVerificationModel.MODEL_BYTES) {
                    "声纹模型大小不正确：${partialModelFile.length()} / " +
                        SpeakerVerificationModel.MODEL_BYTES
                }
                check(
                    sha256(partialModelFile).equals(
                        SpeakerVerificationModel.MODEL_SHA256,
                        ignoreCase = true,
                    ),
                ) { "声纹模型校验失败，已删除，请重新下载" }
                modelFile.delete()
                check(partialModelFile.renameTo(modelFile)) { "无法启用已校验的声纹模型" }
                mutableState.value = snapshot(
                    message = "模型已安装，请录入 $SPEAKER_ENROLLMENT_SAMPLE_COUNT 段“慕容慕容”",
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (
                    partialModelFile.isFile &&
                    partialModelFile.length() == SpeakerVerificationModel.MODEL_BYTES
                ) {
                    partialModelFile.delete()
                }
                mutableState.value = snapshot(
                    statusOverride = SpeakerProfileStatus.ERROR,
                    message = error.message?.take(160) ?: "声纹模型安装失败",
                )
            } finally {
                operationJob = null
            }
        }
    }

    fun captureEnrollmentSample() {
        if (operationJob?.isActive == true) return
        if (!isModelInstalled()) {
            mutableState.value = snapshot(
                statusOverride = SpeakerProfileStatus.ERROR,
                message = "请先安装声纹模型",
            )
            return
        }
        if (
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            mutableState.value = snapshot(
                statusOverride = SpeakerProfileStatus.ERROR,
                message = "需要麦克风权限才能录入声纹",
            )
            return
        }
        operationJob = scope.launch {
            VoiceWakeWordService.pauseForAssistant(appContext)
            try {
                if (pendingEnrollment.isEmpty()) {
                    val stored = profileStore.read()
                    if (stored.size in 1 until SPEAKER_ENROLLMENT_SAMPLE_COUNT) {
                        // Keep existing profiles usable as an upgrade path: a former three-sample
                        // profile only needs two additional samples instead of being discarded.
                        pendingEnrollment += stored
                    }
                }
                mutableState.value = snapshot(
                    statusOverride = SpeakerProfileStatus.RECORDING,
                    message = "请清晰说一遍“慕容慕容”",
                )
                val samples = recordSinglePhrase()
                mutableState.value = snapshot(
                    statusOverride = SpeakerProfileStatus.PROCESSING,
                    message = "正在本机提取声纹…",
                )
                val embedding = computeEmbedding(samples)
                currentCoroutineContext().ensureActive()
                if (pendingEnrollment.isNotEmpty()) {
                    val consistency = pendingEnrollment.maxOf { previous ->
                        cosineSimilarity(previous, embedding)
                    }
                    check(consistency >= ENROLLMENT_CONSISTENCY_THRESHOLD) {
                        "这段声音与前面的样本差异较大，请由同一人重新录入"
                    }
                }
                pendingEnrollment += embedding
                if (pendingEnrollment.size >= SPEAKER_ENROLLMENT_SAMPLE_COUNT) {
                    profileStore.write(
                        pendingEnrollment.take(SPEAKER_ENROLLMENT_SAMPLE_COUNT),
                    )
                    pendingEnrollment.clear()
                    mutableState.value = snapshot(message = "声纹已录入，只保存本机加密特征")
                } else {
                    mutableState.value = snapshot(
                        enrollmentSamplesOverride = pendingEnrollment.size,
                        message = "已录入 ${pendingEnrollment.size}/" +
                            "$SPEAKER_ENROLLMENT_SAMPLE_COUNT，请稍候继续",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.value = snapshot(
                    statusOverride = SpeakerProfileStatus.ERROR,
                    enrollmentSamplesOverride = pendingEnrollment.size,
                    message = error.message?.take(160) ?: "声纹录入失败",
                )
            } finally {
                VoiceWakeWordService.resumeAfterAssistant(appContext)
                operationJob = null
            }
        }
    }

    fun cancelEnrollmentCapture() {
        if (
            mutableState.value.status !in setOf(
                SpeakerProfileStatus.RECORDING,
                SpeakerProfileStatus.PROCESSING,
            )
        ) {
            return
        }
        val job = operationJob ?: return
        job.cancel()
        scope.launch {
            job.join()
            val completed = maxOf(profileStore.read().size, pendingEnrollment.size)
            mutableState.value = snapshot(
                statusOverride = SpeakerProfileStatus.READY_TO_ENROLL,
                enrollmentSamplesOverride = completed,
                message = "录入已暂停，已完成 $completed/" +
                    SPEAKER_ENROLLMENT_SAMPLE_COUNT,
            )
        }
    }

    fun resetEnrollment() {
        if (operationJob?.isActive == true) return
        pendingEnrollment.clear()
        profileStore.delete()
        mutableState.value = snapshot(
            message = "旧声纹已删除，请重新录入 $SPEAKER_ENROLLMENT_SAMPLE_COUNT 段",
        )
    }

    suspend fun verify(samples: FloatArray, threshold: Float): Boolean =
        withContext(Dispatchers.Default) {
            val enrolled = profileStore.read()
            if (!isModelInstalled() || enrolled.isEmpty()) return@withContext false
            val recentSamples = if (samples.size > MAX_VERIFICATION_WINDOW_SAMPLES) {
                samples.copyOfRange(
                    samples.size - MAX_VERIFICATION_WINDOW_SAMPLES,
                    samples.size,
                )
            } else {
                samples
            }
            val trimmed = trimSilence(recentSamples)
            if (trimmed.size < MIN_VERIFICATION_SAMPLES) return@withContext false
            val query = computeEmbedding(trimmed)
            val manager = SpeakerEmbeddingManager(query.size)
            try {
                check(manager.add(OWNER_PROFILE_NAME, enrolled.toTypedArray())) {
                    "无法加载本机声纹"
                }
                manager.verify(
                    OWNER_PROFILE_NAME,
                    query,
                    threshold.coerceIn(0.45f, 0.82f),
                )
            } finally {
                manager.release()
            }
        }

    fun isReadyForVerification(): Boolean =
        isModelInstalled() &&
            profileStore.read().size >= SPEAKER_ENROLLMENT_SAMPLE_COUNT

    override fun close() {
        operationJob?.cancel()
        scope.cancel()
        synchronized(extractorLock) {
            extractor?.release()
            extractor = null
        }
        client.dispatcher.executorService.shutdown()
    }

    private fun snapshot(
        statusOverride: SpeakerProfileStatus? = null,
        enrollmentSamplesOverride: Int? = null,
        message: String? = null,
    ): SpeakerProfileUiState {
        val modelInstalled = isModelInstalled()
        val storedSamples = if (modelInstalled) profileStore.read().size else 0
        val enrollmentSamples = enrollmentSamplesOverride
            ?: maxOf(storedSamples, pendingEnrollment.size)
        val status = statusOverride ?: when {
            !modelInstalled -> SpeakerProfileStatus.NOT_INSTALLED
            storedSamples >= SPEAKER_ENROLLMENT_SAMPLE_COUNT -> SpeakerProfileStatus.ENROLLED
            else -> SpeakerProfileStatus.READY_TO_ENROLL
        }
        return SpeakerProfileUiState(
            status = status,
            downloadedBytes = when {
                modelInstalled -> SpeakerVerificationModel.MODEL_BYTES
                partialModelFile.isFile -> partialModelFile.length()
                else -> 0L
            },
            enrollmentSamples = enrollmentSamples,
            message = message ?: when (status) {
                SpeakerProfileStatus.NOT_INSTALLED -> "声纹模型尚未安装（约 27 MB）"
                SpeakerProfileStatus.READY_TO_ENROLL ->
                    "请录入 $SPEAKER_ENROLLMENT_SAMPLE_COUNT 段唤醒词，只保存加密声纹特征"
                SpeakerProfileStatus.ENROLLED -> "已录入本机声纹"
                else -> mutableState.value.message
            },
        )
    }

    private fun isModelInstalled(): Boolean =
        modelFile.isFile && modelFile.length() == SpeakerVerificationModel.MODEL_BYTES

    private fun computeEmbedding(samples: FloatArray): FloatArray =
        synchronized(extractorLock) {
            val engine = extractor ?: SpeakerEmbeddingExtractor(
                assetManager = null,
                config = SpeakerEmbeddingExtractorConfig(
                    model = modelFile.absolutePath,
                    numThreads = 2,
                    debug = false,
                    provider = "cpu",
                ),
            ).also { extractor = it }
            val stream = engine.createStream()
            try {
                stream.acceptWaveform(samples, SAMPLE_RATE)
                stream.inputFinished()
                check(engine.isReady(stream)) { "录音过短，无法提取稳定声纹" }
                engine.compute(stream)
            } finally {
                stream.release()
            }
        }

    @SuppressLint("MissingPermission")
    private suspend fun recordSinglePhrase(): FloatArray {
        val minimum = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minimum > 0) { "设备不支持 16 kHz 单声道录音" }
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minimum * 2, CAPTURE_BUFFER_SAMPLES * 2),
        )
        check(recorder.state == AudioRecord.STATE_INITIALIZED) { "无法初始化麦克风" }
        val captured = ShortArray(MAX_CAPTURE_SAMPLES)
        val buffer = ShortArray(CAPTURE_BUFFER_SAMPLES)
        var size = 0
        var firstSpeechSample = -1
        var lastSpeechSample = -1
        try {
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "无法启动麦克风"
            }
            while (size < captured.size) {
                currentCoroutineContext().ensureActive()
                val count = recorder.read(
                    buffer,
                    0,
                    minOf(buffer.size, captured.size - size),
                    AudioRecord.READ_BLOCKING,
                )
                check(count > 0) { "麦克风读取失败：$count" }
                buffer.copyInto(captured, destinationOffset = size, endIndex = count)
                val rms = calculateRms(buffer, count)
                if (rms >= SPEECH_RMS_THRESHOLD) {
                    if (firstSpeechSample < 0) firstSpeechSample = size
                    lastSpeechSample = size + count
                }
                size += count
                if (
                    firstSpeechSample >= 0 &&
                    lastSpeechSample >= 0 &&
                    size - lastSpeechSample >= END_SILENCE_SAMPLES &&
                    lastSpeechSample - firstSpeechSample >= MIN_ENROLLMENT_SPEECH_SAMPLES
                ) {
                    break
                }
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }
        check(firstSpeechSample >= 0 && lastSpeechSample > firstSpeechSample) {
            "没有检测到清晰语音，请靠近麦克风重试"
        }
        check(lastSpeechSample - firstSpeechSample >= MIN_ENROLLMENT_SPEECH_SAMPLES) {
            "录音太短，请完整说出“慕容慕容”"
        }
        val from = (firstSpeechSample - PRE_ROLL_SAMPLES).coerceAtLeast(0)
        val to = (lastSpeechSample + POST_ROLL_SAMPLES).coerceAtMost(size)
        return FloatArray(to - from) { index -> captured[from + index] / 32768f }
    }

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").also { digest ->
            file.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count <= 0) break
                    digest.update(buffer, 0, count)
                }
            }
        }.digest().joinToString("") { "%02x".format(it) }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val CAPTURE_BUFFER_SAMPLES = 1_024
        const val MAX_CAPTURE_SAMPLES = SAMPLE_RATE * 7
        const val MIN_ENROLLMENT_SPEECH_SAMPLES = SAMPLE_RATE * 3 / 5
        const val MIN_VERIFICATION_SAMPLES = SAMPLE_RATE * 3 / 4
        const val MAX_VERIFICATION_WINDOW_SAMPLES = SAMPLE_RATE * 4
        const val END_SILENCE_SAMPLES = SAMPLE_RATE
        const val PRE_ROLL_SAMPLES = SAMPLE_RATE / 4
        const val POST_ROLL_SAMPLES = SAMPLE_RATE / 4
        const val SPEECH_RMS_THRESHOLD = 0.012f
        const val ENROLLMENT_CONSISTENCY_THRESHOLD = 0.42f
        const val OWNER_PROFILE_NAME = "owner"
    }
}

internal object SpeakerVerificationModel {
    const val FILE_NAME = "3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx"
    const val SOURCE_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/" +
            "speaker-recongition-models/$FILE_NAME"
    const val MODEL_BYTES = 28_281_138L
    const val MODEL_SHA256 =
        "f682b514c05d947ee3fa91cd6ec6c5c7543479a128373fa29b1faedccd21fd11"
    const val LICENSE = "Apache-2.0（3D-Speaker；由 sherpa-onnx 发布）"
}

internal fun trimSilence(samples: FloatArray): FloatArray {
    if (samples.isEmpty()) return samples
    val frameSize = 320
    var firstFrame = -1
    var lastFrame = -1
    var offset = 0
    var frame = 0
    while (offset < samples.size) {
        val end = minOf(offset + frameSize, samples.size)
        var sum = 0.0
        for (index in offset until end) {
            val value = samples[index]
            sum += value * value
        }
        val rms = sqrt(sum / (end - offset).coerceAtLeast(1)).toFloat()
        if (rms >= 0.009f) {
            if (firstFrame < 0) firstFrame = frame
            lastFrame = frame
        }
        frame++
        offset = end
    }
    if (firstFrame < 0 || lastFrame < firstFrame) return FloatArray(0)
    val preRollFrames = 12
    val postRollFrames = 15
    val from = ((firstFrame - preRollFrames).coerceAtLeast(0) * frameSize)
        .coerceAtMost(samples.size)
    val to = ((lastFrame + 1 + postRollFrames) * frameSize).coerceAtMost(samples.size)
    return samples.copyOfRange(from, to)
}

internal fun cosineSimilarity(left: FloatArray, right: FloatArray): Float {
    if (left.isEmpty() || left.size != right.size) return -1f
    var dot = 0.0
    var leftNorm = 0.0
    var rightNorm = 0.0
    left.indices.forEach { index ->
        dot += left[index] * right[index]
        leftNorm += left[index] * left[index]
        rightNorm += right[index] * right[index]
    }
    if (leftNorm <= 0.0 || rightNorm <= 0.0) return -1f
    return (dot / sqrt(leftNorm * rightNorm)).toFloat()
}

private fun calculateRms(samples: ShortArray, count: Int): Float {
    if (count <= 0) return 0f
    var sum = 0.0
    repeat(count.coerceAtMost(samples.size)) { index ->
        val normalized = samples[index] / 32768.0
        sum += normalized * normalized
    }
    return sqrt(sum / count).toFloat()
}

/** Android-Keystore encrypted persistence for biometric-like speaker embeddings. */
private class EncryptedSpeakerProfileStore(context: Context) {
    private val target = File(context.filesDir, "voice-models/speaker-verification/profile.bin")

    fun write(embeddings: List<FloatArray>) {
        require(embeddings.size >= SPEAKER_ENROLLMENT_SAMPLE_COUNT) {
            "至少需要 $SPEAKER_ENROLLMENT_SAMPLE_COUNT 段声纹样本"
        }
        val dimension = embeddings.first().size
        require(dimension > 0 && embeddings.all { it.size == dimension }) { "声纹维度不一致" }
        val plaintext = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(PROFILE_VERSION)
                output.writeInt(embeddings.size)
                output.writeInt(dimension)
                embeddings.forEach { embedding ->
                    embedding.forEach(output::writeFloat)
                }
            }
            bytes.toByteArray()
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, loadOrCreateKey())
        val encrypted = cipher.doFinal(plaintext)
        plaintext.fill(0)
        val payload = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(FILE_VERSION)
                output.writeInt(cipher.iv.size)
                output.write(cipher.iv)
                output.writeInt(encrypted.size)
                output.write(encrypted)
            }
            bytes.toByteArray()
        }
        target.parentFile?.mkdirs()
        val partial = File(target.parentFile, "${target.name}.part")
        partial.writeBytes(payload)
        target.delete()
        check(partial.renameTo(target)) { "无法保存加密声纹" }
    }

    fun read(): List<FloatArray> = runCatching {
        if (!target.isFile) return emptyList()
        val plaintext = DataInputStream(target.inputStream()).use { input ->
            require(input.readInt() == FILE_VERSION)
            val ivSize = input.readInt()
            require(ivSize in 12..32)
            val iv = ByteArray(ivSize).also(input::readFully)
            val encryptedSize = input.readInt()
            require(encryptedSize in 1..MAX_ENCRYPTED_BYTES)
            val encrypted = ByteArray(encryptedSize).also(input::readFully)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, loadOrCreateKey(), GCMParameterSpec(128, iv))
            cipher.doFinal(encrypted)
        }
        try {
            DataInputStream(ByteArrayInputStream(plaintext)).use { input ->
                require(input.readInt() == PROFILE_VERSION)
                val count = input.readInt()
                val dimension = input.readInt()
                require(count in 1..8 && dimension in 16..4096)
                List(count) {
                    FloatArray(dimension) { input.readFloat() }
                }
            }
        } finally {
            plaintext.fill(0)
        }
    }.getOrDefault(emptyList())

    fun delete() {
        target.delete()
        File(target.parentFile, "${target.name}.part").delete()
    }

    private fun loadOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            .apply {
                init(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setRandomizedEncryptionRequired(true)
                        .build(),
                )
            }
            .generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "murong_voiceprint_profile_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val FILE_VERSION = 1
        const val PROFILE_VERSION = 1
        const val MAX_ENCRYPTED_BYTES = 256 * 1024
    }
}
