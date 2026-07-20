package com.murong.agent.ui.chat

import android.net.Uri
import com.murong.agent.core.loop.FileMentionSource
import com.murong.agent.core.loop.FileMentionUi
import com.murong.agent.core.loop.PendingImageAttachmentUi

internal data class ExternalShareDraft(
    val requestId: String,
    val text: String,
    val files: List<ExternalShareCachedFile>,
    val notices: List<String> = emptyList()
)

internal data class ExternalShareCachedFile(
    val cachePath: String,
    val displayName: String,
    val mimeType: String?,
    val sizeBytes: Long,
    val modifiedAtMillis: Long
) {
    fun isImage(): Boolean {
        if (mimeType?.startsWith("image/", ignoreCase = true) == true) return true
        return displayName.substringAfterLast('.', missingDelimiterValue = "").lowercase() in
            setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "heic")
    }

    fun toFileMention(): FileMentionUi = FileMentionUi(
        path = cachePath,
        displayPath = displayName,
        byteSize = sizeBytes,
        modifiedAtMillis = modifiedAtMillis,
        source = FileMentionSource.MANUAL
    )

    fun toPendingImage(): PendingImageAttachmentUi = PendingImageAttachmentUi(
        uri = Uri.fromFile(java.io.File(cachePath)).toString(),
        fileName = displayName,
        mimeType = mimeType
    )
}

internal data class ExternalShareLimits(
    val maxFiles: Int = 4,
    val maxFileBytes: Long = 128L * 1024L * 1024L,
    val maxTotalBytes: Long = 256L * 1024L * 1024L
)

internal enum class ExternalShareSizeViolation {
    FILE_TOO_LARGE,
    TOTAL_TOO_LARGE
}

internal fun externalShareSizeViolation(
    declaredSize: Long?,
    copiedTotalBytes: Long,
    limits: ExternalShareLimits
): ExternalShareSizeViolation? {
    if (declaredSize == null || declaredSize < 0L) return null
    if (declaredSize > limits.maxFileBytes) return ExternalShareSizeViolation.FILE_TOO_LARGE
    if (copiedTotalBytes + declaredSize > limits.maxTotalBytes) {
        return ExternalShareSizeViolation.TOTAL_TOO_LARGE
    }
    return null
}

internal fun buildExternalShareText(subject: String?, text: String?): String {
    val normalizedSubject = subject?.trim().orEmpty()
    val normalizedText = text?.trim().orEmpty()
    return when {
        normalizedSubject.isBlank() -> normalizedText
        normalizedText.isBlank() -> normalizedSubject
        normalizedSubject == normalizedText -> normalizedText
        else -> "$normalizedSubject\n$normalizedText"
    }
}

internal fun mergeExternalShareDraftText(
    currentText: String,
    sharedText: String,
    fileCount: Int
): String {
    val existing = currentText.trimEnd()
    val incoming = sharedText.trim().ifBlank {
        when {
            fileCount == 1 -> "请查看分享的文件。"
            fileCount > 1 -> "请查看分享的这些文件。"
            else -> ""
        }
    }
    return listOf(existing, incoming)
        .filter { it.isNotBlank() }
        .joinToString("\n")
}

internal fun shouldDispatchExternalShare(
    intentAlreadyMarked: Boolean,
    activityStateAlreadyDispatched: Boolean,
    deliveredViaOnNewIntent: Boolean
): Boolean {
    if (intentAlreadyMarked) return false
    return deliveredViaOnNewIntent || !activityStateAlreadyDispatched
}

internal fun sanitizeExternalShareFileName(rawName: String, fallbackIndex: Int): String {
    val leafName = rawName
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .trim()
    val sanitized = leafName
        .replace(Regex("[\\u0000-\\u001f<>:\"/\\\\|?*]"), "_")
        .trim('.', ' ')
        .take(160)
    return sanitized.ifBlank { "shared-file-$fallbackIndex" }
}
