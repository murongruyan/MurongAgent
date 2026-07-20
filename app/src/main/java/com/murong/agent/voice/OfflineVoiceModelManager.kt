package com.murong.agent.voice

import android.content.Context
import android.os.StatFs
import com.murong.agent.core.doctor.SensitiveDataSanitizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

data class OfflineVoiceModelUiState(
    val status: OfflineVoiceModelInstallStatus = OfflineVoiceModelInstallStatus.NOT_INSTALLED,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = OFFLINE_VOICE_MODEL_ARCHIVE_BYTES,
    val message: String? = null,
) {
    val isInstalled: Boolean get() = status == OfflineVoiceModelInstallStatus.READY
    val hasLegacyModel: Boolean get() = status == OfflineVoiceModelInstallStatus.LEGACY_READY
    val isBusy: Boolean get() = status in setOf(
        OfflineVoiceModelInstallStatus.DOWNLOADING,
        OfflineVoiceModelInstallStatus.VERIFYING,
        OfflineVoiceModelInstallStatus.EXTRACTING,
    )
    val progress: Float get() = if (totalBytes <= 0) 0f else (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
}

enum class OfflineVoiceModelInstallStatus {
    NOT_INSTALLED,
    DOWNLOADING,
    VERIFYING,
    EXTRACTING,
    READY,
    LEGACY_READY,
    FAILED,
}

sealed interface OfflineVoiceModelFiles {
    val tokensFile: File
}

data class StreamingVoiceModelFiles(
    val encoderFile: File,
    val decoderFile: File,
    val joinerFile: File,
    override val tokensFile: File,
    val bpeModelFile: File,
) : OfflineVoiceModelFiles

data class SenseVoiceModelFiles(
    val modelFile: File,
    override val tokensFile: File,
) : OfflineVoiceModelFiles

internal const val OFFLINE_VOICE_MODEL_ARCHIVE_BYTES = 133_898_007L

internal object OfflineVoiceModelDescriptor {
    const val ID = "streaming-zipformer-zh-en-int8"
    const val VERSION = "2026-06-05-160ms-punct"
    const val DISPLAY_NAME = "实时中英识别模型（原生标点，Zipformer int8，160 ms）"
    const val SOURCE_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/" +
        "sherpa-onnx-x-asr-160ms-streaming-zipformer-transducer-zh-en-punct-int8-2026-06-05.tar.bz2"
    const val ARCHIVE_SHA256 = "8a6fca056e1a342546edd78be4d50274e2c01898e7b8ae8fc336f6410319c399"
    const val LICENSE_LABEL = "Apache-2.0（上游 Sherpa-onnx 发布包）"
    const val ENCODER_FILE_NAME = "encoder.int8.onnx"
    const val DECODER_FILE_NAME = "decoder.onnx"
    const val JOINER_FILE_NAME = "joiner.int8.onnx"
    const val TOKENS_FILE_NAME = "tokens.txt"
    const val BPE_MODEL_FILE_NAME = "bpe.model"
}

private object LegacyPunct480StreamingModelDescriptor {
    const val ID = "streaming-zipformer-zh-en-int8"
    const val VERSION = "2026-06-05-480ms-punct"
}

private object LegacyPlainStreamingModelDescriptor {
    const val ID = "streaming-zipformer-zh-en-int8"
    const val VERSION = "2026-06-05-480ms"
}

private object LegacyPunctuationModelDescriptor {
    const val ID = "punctuation-ct-transformer-zh-en-int8"
    const val VERSION = "2024-04-12"
}

private object LegacySenseVoiceDescriptor {
    const val ID = "sensevoice-zh-en-int8"
    const val VERSION = "2024-07-17"
    const val MODEL_FILE_NAME = "model.int8.onnx"
    const val TOKENS_FILE_NAME = "tokens.txt"
}

internal fun isSafeOfflineVoiceArchiveEntryName(entryName: String): Boolean {
    val normalized = entryName.replace('\\', '/').trim()
    return normalized.isNotBlank() && !normalized.startsWith('/') && !normalized.contains(':') &&
        // GNU tar archives commonly begin with `./`.  It is a relative directory marker, not
        // traversal, and must not reject an otherwise verified official model archive.
        normalized.split('/').filter(String::isNotBlank).none { it == ".." }
}

/** User-triggered model installer. The APK contains the Apache runtime, not the downloadable models. */
class OfflineVoiceModelManager(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val modelRoot = File(appContext.filesDir, "voice-models")
    private val recognitionArchiveFile = File(
        File(modelRoot, ".downloads"),
        "${OfflineVoiceModelDescriptor.ID}-${OfflineVoiceModelDescriptor.VERSION}.tar.bz2.part",
    )
    private val recognitionInstallationDirectory =
        File(File(modelRoot, OfflineVoiceModelDescriptor.ID), OfflineVoiceModelDescriptor.VERSION)
    private val legacyPunct480StreamingInstallationDirectory = File(
        File(modelRoot, LegacyPunct480StreamingModelDescriptor.ID),
        LegacyPunct480StreamingModelDescriptor.VERSION,
    )
    private val legacyPlainStreamingInstallationDirectory = File(
        File(modelRoot, LegacyPlainStreamingModelDescriptor.ID),
        LegacyPlainStreamingModelDescriptor.VERSION,
    )
    private val legacyPunctuationInstallationDirectory = File(
        File(modelRoot, LegacyPunctuationModelDescriptor.ID),
        LegacyPunctuationModelDescriptor.VERSION,
    )
    private val legacyInstallationDirectory = File(
        File(modelRoot, LegacySenseVoiceDescriptor.ID),
        LegacySenseVoiceDescriptor.VERSION,
    )
    private val _state = MutableStateFlow(readInstalledState())
    val state: StateFlow<OfflineVoiceModelUiState> = _state.asStateFlow()
    private var installJob: Job? = null

    init {
        if (_state.value.isInstalled) scope.launch { cleanupSupersededModelFiles() }
    }

    fun install() {
        if (state.value.isBusy) return
        installJob?.cancel()
        installJob = scope.launch {
            runCatching { installInternal() }.onFailure { error ->
                if (error is CancellationException) throw error
                _state.value = OfflineVoiceModelUiState(
                    status = OfflineVoiceModelInstallStatus.FAILED,
                    message = "离线模型安装失败：${safeErrorMessage(error)}",
                )
            }
        }
    }

    fun delete() {
        val jobToCancel = installJob
        installJob = null
        scope.launch {
            jobToCancel?.cancelAndJoin()
            recognitionArchiveFile.delete()
            recognitionInstallationDirectory.deleteRecursively()
            File(modelRoot, ".extract-${OfflineVoiceModelDescriptor.ID}").listFiles()?.forEach { it.deleteRecursively() }
            cleanupSupersededModelFiles()
            _state.value = OfflineVoiceModelUiState()
        }
    }

    fun installedFiles(): OfflineVoiceModelFiles? =
        findStreamingModelFiles(recognitionInstallationDirectory)
            ?: findStreamingModelFiles(legacyPunct480StreamingInstallationDirectory)
            ?: findStreamingModelFiles(legacyPlainStreamingInstallationDirectory)
            ?: findLegacyModelFiles(legacyInstallationDirectory)

    private suspend fun installInternal() {
        check(StatFs(appContext.filesDir.absolutePath).availableBytes >= REQUIRED_AVAILABLE_BYTES) {
            "应用可用存储不足，离线模型安装至少需要 500 MB"
        }
        if (findStreamingModelFiles(recognitionInstallationDirectory) == null) {
            installPackage(
                archiveFile = recognitionArchiveFile,
                installationDirectory = recognitionInstallationDirectory,
                packageId = OfflineVoiceModelDescriptor.ID,
                sourceUrl = OfflineVoiceModelDescriptor.SOURCE_URL,
                expectedBytes = OFFLINE_VOICE_MODEL_ARCHIVE_BYTES,
                expectedSha256 = OfflineVoiceModelDescriptor.ARCHIVE_SHA256,
                componentLabel = "实时识别模型",
                missingFilesMessage = "模型包缺少实时识别所需文件",
                validator = { findStreamingModelFiles(it) != null },
            )
        }
        check(findStreamingModelFiles(recognitionInstallationDirectory) != null) { "实时模型文件不完整，未启用" }
        cleanupSupersededModelFiles()
        _state.value = OfflineVoiceModelUiState(status = OfflineVoiceModelInstallStatus.READY)
    }

    private suspend fun installPackage(
        archiveFile: File,
        installationDirectory: File,
        packageId: String,
        sourceUrl: String,
        expectedBytes: Long,
        expectedSha256: String,
        componentLabel: String,
        missingFilesMessage: String,
        validator: (File) -> Boolean,
    ) {
        var existing = archiveFile.takeIf { it.isFile }?.length() ?: 0L
        if (existing > expectedBytes) {
            archiveFile.delete()
            existing = 0L
        }
        _state.value = OfflineVoiceModelUiState(
            status = OfflineVoiceModelInstallStatus.DOWNLOADING,
            downloadedBytes = existing,
            totalBytes = expectedBytes,
            message = componentLabel,
        )
        downloadArchive(archiveFile, sourceUrl, existing, expectedBytes, componentLabel)
        _state.value = _state.value.copy(
            status = OfflineVoiceModelInstallStatus.VERIFYING,
            message = componentLabel,
        )
        if (!sha256(archiveFile).equals(expectedSha256, ignoreCase = true)) {
            archiveFile.delete()
            error("$componentLabel 下载文件校验失败，已删除。请重新下载")
        }
        _state.value = _state.value.copy(
            status = OfflineVoiceModelInstallStatus.EXTRACTING,
            message = componentLabel,
        )
        extractAndActivate(
            archiveFile = archiveFile,
            installationDirectory = installationDirectory,
            packageId = packageId,
            missingFilesMessage = missingFilesMessage,
            validator = validator,
        )
        archiveFile.delete()
        check(validator(installationDirectory)) { "$componentLabel 文件不完整，未启用" }
    }

    private suspend fun downloadArchive(
        archiveFile: File,
        sourceUrl: String,
        existingBytes: Long,
        expectedBytes: Long,
        componentLabel: String,
    ) {
        if (existingBytes == expectedBytes) return
        archiveFile.parentFile?.mkdirs()
        val request = Request.Builder().url(sourceUrl).apply {
            if (existingBytes > 0) header("Range", "bytes=$existingBytes-")
        }.build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "下载服务器返回 HTTP ${response.code}" }
            val append = existingBytes > 0 && response.code == 206
            if (!append && existingBytes > 0) archiveFile.delete()
            val start = if (append) existingBytes else 0L
            val announcedLength = response.body?.contentLength()?.takeIf { it >= 0 } ?: -1L
            val total = if (announcedLength >= 0) start + announcedLength else expectedBytes
            _state.value = OfflineVoiceModelUiState(
                status = OfflineVoiceModelInstallStatus.DOWNLOADING,
                downloadedBytes = start,
                totalBytes = total,
                message = componentLabel,
            )
            checkNotNull(response.body).byteStream().use { input ->
                FileOutputStream(archiveFile, append).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var downloaded = start
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count <= 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        _state.value = _state.value.copy(downloadedBytes = downloaded, totalBytes = total)
                    }
                    output.fd.sync()
                }
            }
        }
        check(archiveFile.length() == expectedBytes) { "$componentLabel 下载不完整，请重试" }
    }

    private suspend fun extractAndActivate(
        archiveFile: File,
        installationDirectory: File,
        packageId: String,
        missingFilesMessage: String,
        validator: (File) -> Boolean,
    ) {
        val staging = File(File(modelRoot, ".extract-$packageId"), UUID.randomUUID().toString())
        staging.mkdirs()
        try {
            var entries = 0
            var extracted = 0L
            BufferedInputStream(archiveFile.inputStream()).use { raw ->
                BZip2CompressorInputStream(raw, true).use { bzip ->
                    TarArchiveInputStream(bzip).use { tar ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val entry = tar.nextTarEntry ?: break
                            check(++entries <= MAX_ARCHIVE_ENTRIES) { "模型包条目数量异常" }
                            val output = safeArchiveDestination(staging, entry.name)
                            when {
                                entry.isDirectory -> output.mkdirs()
                                entry.isSymbolicLink || entry.isLink -> error("模型包包含不允许的链接文件")
                                else -> {
                                    check(entry.size in 0..MAX_SINGLE_FILE_BYTES) { "模型包文件大小异常" }
                                    output.parentFile?.mkdirs()
                                    output.outputStream().use { destination ->
                                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                        var entryBytes = 0L
                                        while (true) {
                                            val count = tar.read(buffer)
                                            if (count <= 0) break
                                            entryBytes += count
                                            extracted += count
                                            check(entryBytes <= MAX_SINGLE_FILE_BYTES && extracted <= MAX_EXTRACTED_BYTES) { "模型包解包大小异常" }
                                            destination.write(buffer, 0, count)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            check(validator(staging)) { missingFilesMessage }
            installationDirectory.parentFile?.mkdirs()
            val backup = File(installationDirectory.parentFile, "${installationDirectory.name}.previous")
            backup.deleteRecursively()
            if (installationDirectory.exists()) check(installationDirectory.renameTo(backup)) { "无法保留旧模型以完成切换" }
            try {
                check(staging.renameTo(installationDirectory)) { "无法启用已校验模型" }
                backup.deleteRecursively()
            } catch (error: Throwable) {
                installationDirectory.deleteRecursively()
                if (backup.exists()) backup.renameTo(installationDirectory)
                throw error
            }
        } catch (error: Throwable) {
            staging.deleteRecursively()
            throw error
        }
    }

    private fun readInstalledState() = when {
        findStreamingModelFiles(recognitionInstallationDirectory) != null ->
            OfflineVoiceModelUiState(status = OfflineVoiceModelInstallStatus.READY)
        findStreamingModelFiles(legacyPunct480StreamingInstallationDirectory) != null ||
            findStreamingModelFiles(legacyPlainStreamingInstallationDirectory) != null ||
            findLegacyModelFiles(legacyInstallationDirectory) != null ->
            OfflineVoiceModelUiState(status = OfflineVoiceModelInstallStatus.LEGACY_READY)
        else -> OfflineVoiceModelUiState()
    }

    private fun findStreamingModelFiles(root: File): StreamingVoiceModelFiles? {
        if (!root.isDirectory) return null
        val filesByName = root.walkTopDown()
            .maxDepth(MAX_MODEL_DIRECTORY_DEPTH)
            .filter(File::isFile)
            .associateBy(File::getName)
        return StreamingVoiceModelFiles(
            encoderFile = filesByName[OfflineVoiceModelDescriptor.ENCODER_FILE_NAME] ?: return null,
            decoderFile = filesByName[OfflineVoiceModelDescriptor.DECODER_FILE_NAME] ?: return null,
            joinerFile = filesByName[OfflineVoiceModelDescriptor.JOINER_FILE_NAME] ?: return null,
            tokensFile = filesByName[OfflineVoiceModelDescriptor.TOKENS_FILE_NAME] ?: return null,
            bpeModelFile = filesByName[OfflineVoiceModelDescriptor.BPE_MODEL_FILE_NAME] ?: return null,
        )
    }

    private fun findLegacyModelFiles(root: File): SenseVoiceModelFiles? {
        if (!root.isDirectory) return null
        val filesByName = root.walkTopDown()
            .maxDepth(MAX_MODEL_DIRECTORY_DEPTH)
            .filter(File::isFile)
            .associateBy(File::getName)
        return SenseVoiceModelFiles(
            modelFile = filesByName[LegacySenseVoiceDescriptor.MODEL_FILE_NAME] ?: return null,
            tokensFile = filesByName[LegacySenseVoiceDescriptor.TOKENS_FILE_NAME] ?: return null,
        )
    }

    private fun cleanupSupersededModelFiles() {
        File(
            File(modelRoot, ".downloads"),
            "${LegacyPunct480StreamingModelDescriptor.ID}-${LegacyPunct480StreamingModelDescriptor.VERSION}.tar.bz2.part",
        ).delete()
        File(
            File(modelRoot, ".downloads"),
            "${LegacyPlainStreamingModelDescriptor.ID}-${LegacyPlainStreamingModelDescriptor.VERSION}.tar.bz2.part",
        ).delete()
        File(
            File(modelRoot, ".downloads"),
            "${LegacyPunctuationModelDescriptor.ID}-${LegacyPunctuationModelDescriptor.VERSION}.tar.bz2.part",
        ).delete()
        legacyPunct480StreamingInstallationDirectory.deleteRecursively()
        legacyPlainStreamingInstallationDirectory.deleteRecursively()
        legacyPunctuationInstallationDirectory.deleteRecursively()
        legacyInstallationDirectory.deleteRecursively()
        File(modelRoot, ".extract-${LegacyPunctuationModelDescriptor.ID}")
            .listFiles()
            ?.forEach { it.deleteRecursively() }
    }

    private fun safeArchiveDestination(root: File, entryName: String): File {
        check(isSafeOfflineVoiceArchiveEntryName(entryName)) { "模型包包含不安全的路径" }
        val destination = File(root, entryName.replace('\\', '/'))
        val canonicalRoot = root.canonicalFile.path
        val canonicalDestination = destination.canonicalFile.path
        check(
            canonicalDestination == canonicalRoot ||
                canonicalDestination.startsWith(canonicalRoot + File.separator)
        ) { "模型包包含不安全的路径" }
        return destination
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").also { digest ->
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count <= 0) break
                digest.update(buffer, 0, count)
            }
        }
    }.digest().joinToString("") { "%02x".format(it) }

    private fun safeErrorMessage(error: Throwable): String = SensitiveDataSanitizer.sanitizeText(error.message ?: "未知错误", redactPaths = true).take(160)

    override fun close() {
        installJob?.cancel()
        scope.cancel()
        client.dispatcher.executorService.shutdown()
    }

    private companion object {
        const val MAX_ARCHIVE_ENTRIES = 1_000
        const val MAX_MODEL_DIRECTORY_DEPTH = 6
        const val MAX_SINGLE_FILE_BYTES = 350L * 1024L * 1024L
        const val MAX_EXTRACTED_BYTES = 500L * 1024L * 1024L
        const val REQUIRED_AVAILABLE_BYTES = 500L * 1024L * 1024L
    }
}
