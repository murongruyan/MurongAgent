package com.murong.agent.core.doctor

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class PendingCrashReport(
    val timestamp: Long,
    val threadName: String,
    val exceptionType: String,
    val message: String = "",
    val stackTrace: String = "",
    val fatal: Boolean = true
)

class PendingCrashStore internal constructor(
    private val pendingCrashFile: File
) {
    constructor(context: Context) : this(
        File(File(context.filesDir, "doctor"), "pending-crash.json")
    )

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    fun persistPendingCrash(
        throwable: Throwable,
        threadName: String,
        fatal: Boolean = true,
        timestamp: Long = System.currentTimeMillis()
    ) {
        val report = PendingCrashReport(
            timestamp = timestamp,
            threadName = SensitiveDataSanitizer.sanitizeText(threadName),
            exceptionType = throwable::class.java.name,
            message = SensitiveDataSanitizer.sanitizeText(throwable.message.orEmpty()),
            stackTrace = SensitiveDataSanitizer.sanitizeText(throwable.stackTraceToString()),
            fatal = fatal
        )
        pendingCrashFile.parentFile?.mkdirs()
        pendingCrashFile.writeText(json.encodeToString(report))
    }

    fun loadPendingCrash(): PendingCrashReport? {
        if (!pendingCrashFile.exists()) return null
        return runCatching {
            json.decodeFromString<PendingCrashReport>(pendingCrashFile.readText())
        }.getOrNull()
    }

    fun consumePendingCrash(): PendingCrashReport? {
        val report = loadPendingCrash()
        clearPendingCrash()
        return report
    }

    fun clearPendingCrash() {
        if (pendingCrashFile.exists()) {
            pendingCrashFile.delete()
        }
    }
}

fun installPendingCrashHandler(context: Context) {
    val store = PendingCrashStore(context)
    val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        runCatching {
            store.persistPendingCrash(
                throwable = throwable,
                threadName = thread.name
            )
        }
        if (previousHandler != null) {
            previousHandler.uncaughtException(thread, throwable)
        } else {
            thread.threadGroup?.uncaughtException(thread, throwable)
        }
    }
}
