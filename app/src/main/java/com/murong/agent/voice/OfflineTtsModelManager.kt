package com.murong.agent.voice

import android.content.Context
import android.os.StatFs
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

data class OfflineTtsModelUiState(
    val status: OfflineVoiceModelInstallStatus = OfflineVoiceModelInstallStatus.NOT_INSTALLED,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = OFFLINE_TTS_MODEL_ARCHIVE_BYTES,
    val message: String? = null,
) {
    val isInstalled: Boolean get() = status == OfflineVoiceModelInstallStatus.READY
    val isBusy: Boolean get() = status in setOf(
        OfflineVoiceModelInstallStatus.DOWNLOADING,
        OfflineVoiceModelInstallStatus.VERIFYING,
        OfflineVoiceModelInstallStatus.EXTRACTING,
    )
    val progress: Float get() = if (totalBytes <= 0) 0f else (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
}

internal data class OfflineTtsModelFiles(
    val modelFile: File,
    val tokensFile: File,
    val dataDir: File?,
    val lexiconFile: File?,
    val dictDir: File?,
)

internal const val OFFLINE_TTS_MODEL_ARCHIVE_BYTES = 167_006_755L

internal object OfflineTtsModelDescriptor {
    const val ID = "melo-tts-zh-en"
    const val VERSION = "2026-08-03"
    const val DISPLAY_NAME = "离线中文朗读模型（MeloTTS zh-en）"
    const val SOURCE_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/" +
        "vits-melo-tts-zh_en.tar.bz2"
    const val ARCHIVE_SHA256 = "e58351ed7149f290a54534538badd4077cdbe6fddc964b24d0bee870415d1514"
    const val ARCHIVE_FILE_NAME = "vits-melo-tts-zh_en.tar.bz2"
    const val OFFICIAL_CHECKSUM_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/checksum.txt"
    const val LICENSE_LABEL = "Apache-2.0（上游 Sherpa-onnx 官方 TTS 模型发布包）"
    const val MODEL_FILE_NAME = "model.onnx"
    const val TOKENS_FILE_NAME = "tokens.txt"
    const val LEXICON_FILE_NAME = "lexicon.txt"
    const val DICT_DIR_NAME = "dict"
    const val DATA_DIR_NAME = "espeak-ng-data"
    const val MIN_MODEL_BYTES = 100L * 1024 * 1024
}

/**
 * User-triggered offline TTS model installer. The APK already bundles the Sherpa-onnx runtime
 * (the same native library used by offline recognition); only the Chinese voice model is
 * downloaded on demand so the read-aloud feature no longer depends on a working system TTS
 * engine (many vendor ROMs ship a broken one for third-party apps).
 */
internal class OfflineTtsModelManager(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val modelRoot = File(appContext.filesDir, "tts-models")
    private val archiveFile = File(
        File(modelRoot, ".downloads"),
        "${OfflineTtsModelDescriptor.ID}-${OfflineTtsModelDescriptor.VERSION}.tar.bz2.part",
    )
    private val installationDirectory = File(
        File(modelRoot, OfflineTtsModelDescriptor.ID),
        OfflineTtsModelDescriptor.VERSION,
    )
    private val _state = MutableStateFlow(readInstalledState())
    val state: StateFlow<OfflineTtsModelUiState> = _state.asStateFlow()
    private var installJob: Job? = null

    fun installedFiles(): OfflineTtsModelFiles? {
        if (!_state.value.isInstalled) return null
        val model = File(installationDirectory, OfflineTtsModelDescriptor.MODEL_FILE_NAME)
        val tokens = File(installationDirectory, OfflineTtsModelDescriptor.TOKENS_FILE_NAME)
        val lexicon = File(installationDirectory, OfflineTtsModelDescriptor.LEXICON_FILE_NAME)
        val dict = File(installationDirectory, OfflineTtsModelDescriptor.DICT_DIR_NAME)
        val dataDir = File(installationDirectory, OfflineTtsModelDescriptor.DATA_DIR_NAME)
        if (
            !model.isFile ||
            model.length() < OfflineTtsModelDescriptor.MIN_MODEL_BYTES ||
            !tokens.isFile ||
            !(lexicon.isFile || dataDir.isDirectory) ||
            !(dict.isDirectory || dataDir.isDirectory)
        ) {
            _state.value = OfflineTtsModelUiState()
            return null
        }
        return OfflineTtsModelFiles(
            modelFile = model,
            tokensFile = tokens,
            dataDir = dataDir.takeIf { it.isDirectory },
            lexiconFile = lexicon.takeIf { it.isFile },
            dictDir = dict.takeIf { it.isDirectory },
        )
    }

    fun install() {
        if (_state.value.isBusy) return
        installJob?.cancel()
        installJob = scope.launch {
            runCatching { installInternal() }.onFailure { error ->
                if (error is CancellationException) throw error
                _state.value = OfflineTtsModelUiState(
                    status = OfflineVoiceModelInstallStatus.FAILED,
                    message = "离线朗读模型安装失败：${safeErrorMessage(error)}",
                )
            }
        }
    }

    fun delete() {
        val jobToCancel = installJob
        installJob = null
        scope.launch {
            jobToCancel?.cancelAndJoin()
            archiveFile.delete()
            installationDirectory.deleteRecursively()
            File(modelRoot, ".extract-${OfflineTtsModelDescriptor.ID}").listFiles()?.forEach { it.deleteRecursively() }
            _state.value = OfflineTtsModelUiState()
        }
    }

    private suspend fun installInternal() {
        ensureSufficientSpace()
        File(modelRoot, ".downloads").mkdirs()
        installationDirectory.parentFile?.mkdirs()
        cleanupLegacyModels()
        _state.value = OfflineTtsModelUiState(status = OfflineVoiceModelInstallStatus.DOWNLOADING)
        downloadArchive()
        _state.value = OfflineTtsModelUiState(status = OfflineVoiceModelInstallStatus.VERIFYING)
        verifyArchive()
        _state.value = OfflineTtsModelUiState(status = OfflineVoiceModelInstallStatus.EXTRACTING)
        extractArchive()
        _state.value = OfflineTtsModelUiState(status = OfflineVoiceModelInstallStatus.READY)
    }

    private fun cleanupLegacyModels() {
        // Remove earlier voice packages once the new model replaces them so the
        // app does not keep hundreds of MB of dead files.
        listOf("vits-zh-ll", "piper-zh-huayan").forEach { id ->
            val legacy = File(modelRoot, id)
            if (legacy.isDirectory) legacy.deleteRecursively()
        }
    }

    private suspend fun ensureSufficientSpace() {
        val requiredBytes = (OFFLINE_TTS_MODEL_ARCHIVE_BYTES * 2L).coerceAtLeast(380L * 1024 * 1024)
        val available = runCatching {
            StatFs(appContext.filesDir.absolutePath).availableBytes
        }.getOrDefault(Long.MAX_VALUE)
        check(available >= requiredBytes) {
            "可用空间不足（需要约 ${requiredBytes / (1024 * 1024)} MB，当前仅 ${available / (1024 * 1024)} MB）"
        }
    }

    private suspend fun downloadArchive() {
        archiveFile.parentFile?.mkdirs()
        val request = Request.Builder().url(OfflineTtsModelDescriptor.SOURCE_URL).build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "下载失败（HTTP ${response.code}）" }
            val body = checkNotNull(response.body) { "下载响应为空" }
            val total = body.contentLength().coerceAtLeast(OFFLINE_TTS_MODEL_ARCHIVE_BYTES)
            FileOutputStream(archiveFile).use { output ->
                val buffer = ByteArray(64 * 1024)
                var downloaded = 0L
                body.byteStream().use { input ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        _state.value = OfflineTtsModelUiState(
                            status = OfflineVoiceModelInstallStatus.DOWNLOADING,
                            downloadedBytes = downloaded,
                            totalBytes = total,
                        )
                    }
                }
            }
        }
    }

    private suspend fun verifyArchive() {
        val digest = MessageDigest.getInstance("SHA-256")
        archiveFile.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            var hashed = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
                hashed += read
                _state.value = OfflineTtsModelUiState(
                    status = OfflineVoiceModelInstallStatus.VERIFYING,
                    downloadedBytes = hashed,
                    totalBytes = archiveFile.length(),
                )
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        val baseline = OfflineTtsModelDescriptor.ARCHIVE_SHA256
        if (actual.equals(baseline, ignoreCase = true)) return
        // The baseline pins the version this release was tested with; if upstream
        // re-packaged the model, fall back to the official checksum so users are
        // not stuck on a stale hash.
        val official = fetchOfficialChecksum()
        check(official != null && actual.equals(official, ignoreCase = true)) {
            "SHA-256 校验失败（基线 $baseline，官方 ${official ?: "未知"}，实际 $actual）"
        }
    }

    private suspend fun fetchOfficialChecksum(): String? {
        val request = Request.Builder().url(OfflineTtsModelDescriptor.OFFICIAL_CHECKSUM_URL).build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = checkNotNull(response.body).string()
                body.lineSequence()
                    .map { it.trim() }
                    .firstOrNull { line ->
                        line.startsWith(OfflineTtsModelDescriptor.ARCHIVE_FILE_NAME + "\t") ||
                            line.startsWith(OfflineTtsModelDescriptor.ARCHIVE_FILE_NAME + " ")
                    }
                    ?.substringAfter('\t')
                    ?.substringAfter(' ')
                    ?.takeIf { it.length == 64 }
            }
        }.getOrNull()
    }

    private suspend fun extractArchive() {
        val extractRoot = File(modelRoot, ".extract-${OfflineTtsModelDescriptor.ID}").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            TarArchiveInputStream(
                BufferedInputStream(
                    BZip2CompressorInputStream(BufferedInputStream(archiveFile.inputStream()))
                )
            ).use { tar ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val entry = tar.nextEntry ?: break
                    val entryName = entry.name.replace('\\', '/')
                    if (!isSafeOfflineVoiceArchiveEntryName(entryName)) {
                        error("归档包含非法路径: $entryName")
                    }
                    val target = File(extractRoot, entryName)
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = tar.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                }
            }
            val packagedDir = extractRoot.listFiles()
                ?.firstOrNull { it.isDirectory }
                ?: extractRoot
            if (installationDirectory.exists()) installationDirectory.deleteRecursively()
            packagedDir.copyRecursively(installationDirectory, overwrite = true)
        } finally {
            extractRoot.deleteRecursively()
            archiveFile.delete()
        }
    }

    private fun readInstalledState(): OfflineTtsModelUiState {
        val model = File(installationDirectory, OfflineTtsModelDescriptor.MODEL_FILE_NAME)
        val tokens = File(installationDirectory, OfflineTtsModelDescriptor.TOKENS_FILE_NAME)
        val lexicon = File(installationDirectory, OfflineTtsModelDescriptor.LEXICON_FILE_NAME)
        val dict = File(installationDirectory, OfflineTtsModelDescriptor.DICT_DIR_NAME)
        val dataDir = File(installationDirectory, OfflineTtsModelDescriptor.DATA_DIR_NAME)
        return if (
            model.isFile &&
            model.length() >= OfflineTtsModelDescriptor.MIN_MODEL_BYTES &&
            tokens.isFile &&
            (lexicon.isFile || dataDir.isDirectory) &&
            (dict.isDirectory || dataDir.isDirectory)
        ) {
            OfflineTtsModelUiState(status = OfflineVoiceModelInstallStatus.READY)
        } else {
            OfflineTtsModelUiState()
        }
    }

    override fun close() {
        scope.cancel()
    }
}

private fun safeErrorMessage(error: Throwable): String {
    val raw = error.message?.trim().orEmpty()
    if (raw.isBlank()) return error.javaClass.simpleName
    return raw.take(400)
}
