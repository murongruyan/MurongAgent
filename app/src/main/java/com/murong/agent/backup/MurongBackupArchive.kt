package com.murong.agent.backup

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json

internal data class MurongBackupPayload(
    val relativePath: String,
    val category: MurongBackupCategory,
    val file: File
)

internal data class ValidatedMurongBackup(
    val manifest: MurongBackupManifest,
    val payloadRoot: File
)

internal object MurongBackupArchive {
    const val MANIFEST_PATH = "manifest.json"
    const val MAX_ENTRY_COUNT = 10_000
    const val MAX_MANIFEST_BYTES = 1024 * 1024
    const val MAX_SINGLE_ENTRY_BYTES = 128L * 1024L * 1024L
    const val MAX_TOTAL_PAYLOAD_BYTES = 512L * 1024L * 1024L

    private val json = Json {
        ignoreUnknownKeys = false
        prettyPrint = true
        encodeDefaults = true
    }

    fun write(
        output: OutputStream,
        createdAtEpochMillis: Long,
        appVersionName: String,
        appVersionCode: Int,
        kind: MurongBackupKind,
        payloads: List<MurongBackupPayload>
    ): MurongBackupManifest {
        require(payloads.size <= MAX_ENTRY_COUNT) { "备份条目数量超过上限" }
        val normalized = payloads
            .map { payload -> payload.copy(relativePath = validateRelativePath(payload.relativePath)) }
            .sortedBy(MurongBackupPayload::relativePath)
        require(normalized.map { it.relativePath }.distinct().size == normalized.size) {
            "备份条目路径重复"
        }
        var totalBytes = 0L
        val entries = normalized.map { payload ->
            require(payload.file.isFile) { "备份源文件不存在：${payload.relativePath}" }
            val size = payload.file.length()
            require(size in 0..MAX_SINGLE_ENTRY_BYTES) { "备份条目大小超过上限：${payload.relativePath}" }
            totalBytes = Math.addExact(totalBytes, size)
            require(totalBytes <= MAX_TOTAL_PAYLOAD_BYTES) { "备份总大小超过上限" }
            MurongBackupEntry(
                path = payload.relativePath,
                category = payload.category,
                sizeBytes = size,
                sha256 = sha256(payload.file)
            )
        }
        val manifest = MurongBackupManifest(
            createdAtEpochMillis = createdAtEpochMillis,
            appVersionName = appVersionName,
            appVersionCode = appVersionCode,
            kind = kind,
            entries = entries
        )
        ZipOutputStream(BufferedOutputStream(output)).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST_PATH).apply { time = createdAtEpochMillis })
            zip.write(json.encodeToString(MurongBackupManifest.serializer(), manifest).toByteArray())
            zip.closeEntry()
            normalized.forEach { payload ->
                zip.putNextEntry(ZipEntry(payload.relativePath).apply { time = createdAtEpochMillis })
                BufferedInputStream(payload.file.inputStream()).use { input -> input.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return manifest
    }

    fun extractAndValidate(input: InputStream, destination: File): ValidatedMurongBackup {
        destination.mkdirs()
        require(destination.isDirectory) { "无法创建备份校验目录" }
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            val first = zip.nextEntry ?: error("备份包为空")
            require(!first.isDirectory && first.name == MANIFEST_PATH) { "备份包首条目必须是 manifest.json" }
            val manifestBytes = readLimited(zip, MAX_MANIFEST_BYTES.toLong(), "版本清单过大")
            zip.closeEntry()
            val manifest = runCatching {
                json.decodeFromString(MurongBackupManifest.serializer(), manifestBytes.decodeToString())
            }.getOrElse { throw IllegalArgumentException("版本清单无法解析：${it.message}", it) }
            validateManifest(manifest)
            val expected = manifest.entries.associateBy(MurongBackupEntry::path)
            val seen = linkedSetOf<String>()
            var totalActualBytes = 0L
            while (true) {
                val entry = zip.nextEntry ?: break
                require(!entry.isDirectory) { "备份包不允许目录条目：${entry.name}" }
                val path = validateRelativePath(entry.name)
                val expectedEntry = expected[path] ?: error("备份包含清单外条目：$path")
                require(seen.add(path)) { "备份包含重复条目：$path" }
                val outputFile = safeOutputFile(destination, path)
                outputFile.parentFile?.mkdirs()
                val digest = MessageDigest.getInstance("SHA-256")
                var actualSize = 0L
                BufferedOutputStream(outputFile.outputStream()).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = zip.read(buffer)
                        if (count <= 0) break
                        actualSize = Math.addExact(actualSize, count.toLong())
                        require(actualSize <= MAX_SINGLE_ENTRY_BYTES) { "备份条目解压后超过上限：$path" }
                        totalActualBytes = Math.addExact(totalActualBytes, count.toLong())
                        require(totalActualBytes <= MAX_TOTAL_PAYLOAD_BYTES) { "备份解压总大小超过上限" }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                }
                zip.closeEntry()
                require(actualSize == expectedEntry.sizeBytes) { "备份条目大小不匹配：$path" }
                val actualHash = digest.digest().toHex()
                require(actualHash.equals(expectedEntry.sha256, ignoreCase = true)) {
                    "备份条目哈希不匹配：$path"
                }
            }
            require(seen.size == expected.size) {
                val missing = expected.keys.firstOrNull { it !in seen }.orEmpty()
                "备份缺少清单条目：$missing"
            }
            return ValidatedMurongBackup(manifest, destination)
        }
    }

    internal fun validateRelativePath(rawPath: String): String {
        require(rawPath.isNotBlank() && rawPath.length <= 512) { "备份条目路径无效" }
        require('\\' !in rawPath && '\u0000' !in rawPath) { "备份条目路径包含非法字符" }
        require(!rawPath.startsWith('/') && ':' !in rawPath.substringBefore('/')) { "备份条目必须使用相对路径" }
        val segments = rawPath.split('/')
        require(segments.none { it.isBlank() || it == "." || it == ".." }) { "备份条目存在路径穿越" }
        require(rawPath.startsWith("data/") || rawPath.startsWith("state/")) {
            "备份条目不在允许的命名空间"
        }
        return segments.joinToString("/")
    }

    private fun validateManifest(manifest: MurongBackupManifest) {
        require(manifest.format == MURONG_BACKUP_FORMAT) { "不是 Murong 备份包" }
        require(manifest.formatVersion in MURONG_BACKUP_MIN_SUPPORTED_VERSION..MURONG_BACKUP_FORMAT_VERSION) {
            "不支持的备份格式版本：${manifest.formatVersion}"
        }
        require(manifest.appVersionCode > 0 && manifest.appVersionName.isNotBlank()) { "备份应用版本无效" }
        require(manifest.createdAtEpochMillis > 0L) { "备份创建时间无效" }
        require(manifest.entries.size <= MAX_ENTRY_COUNT) { "备份条目数量超过上限" }
        require(manifest.entries.map { it.path }.distinct().size == manifest.entries.size) { "清单包含重复路径" }
        var total = 0L
        manifest.entries.forEach { entry ->
            validateRelativePath(entry.path)
            require(entry.category == categoryForPath(entry.path)) {
                "清单条目类别与路径不匹配：${entry.path}"
            }
            require(entry.sizeBytes in 0..MAX_SINGLE_ENTRY_BYTES) { "清单条目大小无效：${entry.path}" }
            require(entry.sha256.length == 64 && entry.sha256.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
                "清单 SHA-256 无效：${entry.path}"
            }
            total = Math.addExact(total, entry.sizeBytes)
            require(total <= MAX_TOTAL_PAYLOAD_BYTES) { "清单总大小超过上限" }
        }
    }

    private fun categoryForPath(path: String): MurongBackupCategory = when {
        path.startsWith("data/conversations/") -> MurongBackupCategory.CONVERSATIONS
        path.startsWith("data/conversation_media/") -> MurongBackupCategory.CONVERSATION_MEDIA
        path.startsWith("data/memories/") -> MurongBackupCategory.MEMORIES
        path == "state/provider-settings.json" -> MurongBackupCategory.PROVIDER_SETTINGS
        path == "state/mcp-config.json" -> MurongBackupCategory.MCP_CONFIG
        path == "state/saved-workflows.json" -> MurongBackupCategory.SAVED_WORKFLOWS
        path == "state/voice-settings.json" -> MurongBackupCategory.VOICE_SETTINGS
        path == "state/ui-settings.json" -> MurongBackupCategory.UI_SETTINGS
        path == "state/backup-settings.json" -> MurongBackupCategory.BACKUP_SETTINGS
        path == "state/portable-state.json" -> MurongBackupCategory.PORTABLE_STATE
        path.startsWith("data/project_audit/") -> MurongBackupCategory.PROJECT_AUDIT
        else -> throw IllegalArgumentException("清单包含未知条目路径：$path")
    }

    private fun safeOutputFile(root: File, relativePath: String): File {
        val canonicalRoot = root.canonicalFile
        val output = File(canonicalRoot, relativePath).canonicalFile
        val prefix = canonicalRoot.path.trimEnd(File.separatorChar) + File.separator
        require(output.path.startsWith(prefix)) { "备份条目越过恢复目录" }
        return output
    }

    private fun readLimited(input: InputStream, maxBytes: Long, message: String): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count <= 0) break
            total += count
            require(total <= maxBytes) { message }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        BufferedInputStream(file.inputStream()).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count <= 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte ->
        String.format(Locale.ROOT, "%02x", byte.toInt() and 0xff)
    }
}
