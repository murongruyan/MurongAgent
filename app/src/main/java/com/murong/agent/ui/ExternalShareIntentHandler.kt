package com.murong.agent.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.murong.agent.ui.chat.ExternalShareCachedFile
import com.murong.agent.ui.chat.ExternalShareDraft
import com.murong.agent.ui.chat.ExternalShareLimits
import com.murong.agent.ui.chat.ExternalShareSizeViolation
import com.murong.agent.ui.chat.buildExternalShareText
import com.murong.agent.ui.chat.externalShareSizeViolation
import com.murong.agent.ui.chat.sanitizeExternalShareFileName
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object ExternalShareIntentHandler {
    private const val CACHE_DIRECTORY_NAME = "shared_input"
    private const val CACHE_RETENTION_MILLIS = 24L * 60L * 60L * 1000L
    private const val COPY_BUFFER_BYTES = 64 * 1024

    val limits = ExternalShareLimits()

    fun isShareIntent(intent: Intent?): Boolean {
        return intent?.action == Intent.ACTION_SEND || intent?.action == Intent.ACTION_SEND_MULTIPLE
    }

    suspend fun prepare(context: Context, intent: Intent): ExternalShareDraft? = withContext(Dispatchers.IO) {
        if (!isShareIntent(intent)) return@withContext null

        val requestId = UUID.randomUUID().toString()
        val notices = mutableListOf<String>()
        val shareUris = collectSharedUris(intent)
        val acceptedUris = shareUris.take(limits.maxFiles)
        if (shareUris.size > acceptedUris.size) {
            notices += "一次最多接收 ${limits.maxFiles} 个文件，已忽略 ${shareUris.size - acceptedUris.size} 个。"
        }

        val cacheRoot = File(context.cacheDir, CACHE_DIRECTORY_NAME)
        cleanupExpiredCacheDirectories(cacheRoot)
        val requestDirectory = File(cacheRoot, requestId)
        if (acceptedUris.isNotEmpty() && !requestDirectory.mkdirs() && !requestDirectory.isDirectory) {
            return@withContext ExternalShareDraft(
                requestId = requestId,
                text = buildExternalShareText(
                    intent.getStringExtra(Intent.EXTRA_SUBJECT),
                    intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                ),
                files = emptyList(),
                notices = notices + "无法创建分享缓存目录，文件没有加入草稿。"
            )
        }

        val cachedFiles = mutableListOf<ExternalShareCachedFile>()
        var copiedTotalBytes = 0L
        acceptedUris.forEachIndexed { index, uri ->
            val metadata = queryMetadata(context, uri, index + 1, intent.type)
            when (externalShareSizeViolation(metadata.declaredSize, copiedTotalBytes, limits)) {
                ExternalShareSizeViolation.FILE_TOO_LARGE -> {
                    notices += "${metadata.displayName} 超过单文件 128 MiB 限制，未加入草稿。"
                    return@forEachIndexed
                }
                ExternalShareSizeViolation.TOTAL_TOO_LARGE -> {
                    notices += "${metadata.displayName} 会超过本次 256 MiB 总量限制，未加入草稿。"
                    return@forEachIndexed
                }
                null -> Unit
            }

            val fileName = "${index + 1}-${sanitizeExternalShareFileName(metadata.displayName, index + 1)}"
            val target = File(requestDirectory, fileName)
            val remainingTotalBytes = limits.maxTotalBytes - copiedTotalBytes
            val copyLimit = min(limits.maxFileBytes, remainingTotalBytes)
            val copiedBytes = runCatching {
                copyUriToPrivateCache(
                    context = context,
                    uri = uri,
                    target = target,
                    maxBytes = copyLimit
                )
            }.onFailure { error ->
                val reason = when (error) {
                    is ExternalShareCopyLimitException -> "文件超过允许大小"
                    else -> error.message?.trim().orEmpty().ifBlank { "系统未授予可读权限" }
                }
                notices += "无法读取 ${metadata.displayName}：$reason。"
            }.getOrNull() ?: return@forEachIndexed

            copiedTotalBytes += copiedBytes
            cachedFiles += ExternalShareCachedFile(
                cachePath = target.absolutePath,
                displayName = sanitizeExternalShareFileName(metadata.displayName, index + 1),
                mimeType = metadata.mimeType,
                sizeBytes = copiedBytes,
                modifiedAtMillis = target.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()
            )
        }

        val sharedText = buildExternalShareText(
            intent.getStringExtra(Intent.EXTRA_SUBJECT),
            intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        )
        if (sharedText.isBlank() && cachedFiles.isEmpty() && notices.isEmpty()) {
            notices += "分享内容为空，没有可加入聊天的文字或文件。"
        }
        if (cachedFiles.isEmpty()) requestDirectory.delete()

        ExternalShareDraft(
            requestId = requestId,
            text = sharedText,
            files = cachedFiles,
            notices = notices
        )
    }

    private fun collectSharedUris(intent: Intent): List<Uri> {
        val uris = buildList {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let(::add)
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let(::addAll)
            intent.clipData?.let { clipData ->
                repeat(clipData.itemCount) { index ->
                    clipData.getItemAt(index).uri?.let(::add)
                }
            }
        }
        return uris.distinctBy(Uri::toString)
    }

    private fun queryMetadata(
        context: Context,
        uri: Uri,
        fallbackIndex: Int,
        intentMimeType: String?
    ): SharedUriMetadata {
        var displayName: String? = null
        var declaredSize: Long? = null
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use
                val displayNameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (displayNameIndex >= 0 && !cursor.isNull(displayNameIndex)) {
                    displayName = cursor.getString(displayNameIndex)
                }
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    declaredSize = cursor.getLong(sizeIndex).takeIf { it >= 0L }
                }
            }
        }
        val fallbackName = uri.lastPathSegment?.substringAfterLast('/').orEmpty()
        return SharedUriMetadata(
            displayName = sanitizeExternalShareFileName(
                displayName?.takeIf { it.isNotBlank() } ?: fallbackName,
                fallbackIndex
            ),
            declaredSize = declaredSize,
            mimeType = context.contentResolver.getType(uri)
                ?: intentMimeType?.takeUnless { it == "*/*" }
        )
    }

    private fun copyUriToPrivateCache(
        context: Context,
        uri: Uri,
        target: File,
        maxBytes: Long
    ): Long {
        if (maxBytes <= 0L) throw ExternalShareCopyLimitException()
        val partial = File(target.parentFile, "${target.name}.part")
        partial.delete()
        target.delete()
        var totalBytes = 0L
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("无法打开分享文件")
            input.use { source ->
                FileOutputStream(partial).buffered(COPY_BUFFER_BYTES).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        if (totalBytes + count > maxBytes) throw ExternalShareCopyLimitException()
                        output.write(buffer, 0, count)
                        totalBytes += count
                    }
                }
            }
            if (!partial.renameTo(target)) {
                throw IllegalStateException("无法完成缓存文件写入")
            }
            return totalBytes
        } catch (error: Throwable) {
            partial.delete()
            target.delete()
            throw error
        }
    }

    private fun cleanupExpiredCacheDirectories(cacheRoot: File) {
        if (!cacheRoot.exists()) return
        val cutoff = System.currentTimeMillis() - CACHE_RETENTION_MILLIS
        cacheRoot.listFiles()
            ?.filter { it.isDirectory && it.lastModified() in 1 until cutoff }
            ?.forEach { it.deleteRecursively() }
    }
}

private data class SharedUriMetadata(
    val displayName: String,
    val declaredSize: Long?,
    val mimeType: String?
)

private class ExternalShareCopyLimitException : Exception()
