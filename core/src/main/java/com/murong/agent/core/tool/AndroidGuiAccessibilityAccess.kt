package com.murong.agent.core.tool

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import androidx.work.workDataOf
import com.murong.agent.common.toolchain.ToolchainManager
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class RootAccessibilityEnableResult(
    val success: Boolean,
    val serviceConnected: Boolean,
    val message: String
)

/**
 * Explicit, Root-assisted accessibility authorization for sideloaded builds.
 *
 * The caller must be handling an explicit user phone-control request or show a confirmation before
 * invoking [enableWithRoot]. Existing enabled services are merged and preserved; this object never
 * silently enables the service at startup.
 */
object AndroidGuiAccessibilityAccess {
    const val DETAILS_SETTINGS_ACTION =
        "android.settings.ACCESSIBILITY_DETAILS_SETTINGS"

    private const val ROOT_RESULT_MARKER = "__MURONG_ACCESSIBILITY_EXIT__"
    private const val ROOT_ERROR_PREFIX = "error:"
    private const val ROOT_ENABLE_TIMEOUT_MILLIS = 20_000L
    private const val ROOT_COMMAND_TIMEOUT_SECONDS = 5L
    private const val ROOT_COMMAND_MAX_OUTPUT_CHARS = 64 * 1024
    private const val CONNECTION_ATTEMPTS = 25
    private const val CONNECTION_POLL_MILLIS = 200L
    private const val WORK_SCHEDULE_TIMEOUT_MILLIS = 5_000L
    private const val OPLUS_GUIDE_GUARD_SECONDS = 45
    private const val OPLUS_GUIDE_RESTORE_WORK =
        "murong_oplus_accessibility_guide_restore"
    private const val OPLUS_GUIDE_USER_ID_INPUT = "user_id"
    private const val OPLUS_GUIDE_ACTION =
        "com.oplus.safecenter.ACTION_ACCESSIBILITY_GLOBAL_CLOSE_GUIDE"
    private const val OPLUS_GUIDE_COMPONENT =
        "com.oplus.safecenter/com.oplus.safecenter.accessibility.AccessibilityGuideCloseActivity"
    private const val OPLUS_GUIDE_SHORT_COMPONENT =
        "com.oplus.safecenter/.accessibility.AccessibilityGuideCloseActivity"

    fun serviceComponentName(context: Context): ComponentName =
        ComponentName(context, AndroidGuiAccessibilityService::class.java)

    fun serviceComponent(context: Context): String =
        serviceComponentName(context).flattenToString()

    fun isEnabledInSettings(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        return parseEnabledAccessibilityServices(enabled)
            .contains(serviceComponent(context))
    }

    suspend fun enableWithRoot(context: Context): RootAccessibilityEnableResult =
        withTimeoutOrNull(ROOT_ENABLE_TIMEOUT_MILLIS) {
            enableWithRootWithinDeadline(context)
        } ?: RootAccessibilityEnableResult(
            success = false,
            serviceConnected = false,
            message = "Root 启用超过 20 秒，已停止等待；ColorOS 保护组件会由后台任务自动恢复。"
        )

    private suspend fun enableWithRootWithinDeadline(
        context: Context
    ): RootAccessibilityEnableResult =
        withContext(Dispatchers.IO) {
            val rootCheck = executeRootCommand("id -u")
            if (
                !rootCheck.succeeded ||
                rootCheck.output.lineSequence().none { it.trim() == "0" }
            ) {
                return@withContext RootAccessibilityEnableResult(
                    success = false,
                    serviceConnected = false,
                    message = "Root 不可用，请在系统无障碍设置中手动启用。"
                )
            }

            val component = serviceComponent(context)
            val previousServicesResult = executeRootCommand(
                "settings get --user current secure enabled_accessibility_services 2>/dev/null"
            )
            val previousGlobalResult = executeRootCommand(
                "settings get --user current secure accessibility_enabled 2>/dev/null"
            )
            val previousServicesRaw = previousServicesResult.output.trim()
            val previousGlobalRaw = previousGlobalResult.output.trim()
            if (
                !previousServicesResult.succeeded ||
                !previousGlobalResult.succeeded ||
                previousServicesRaw.startsWith(ROOT_ERROR_PREFIX, ignoreCase = true) ||
                previousGlobalRaw.startsWith(ROOT_ERROR_PREFIX, ignoreCase = true) ||
                !isValidServiceSetting(previousServicesRaw) ||
                !isValidGlobalSetting(previousGlobalRaw)
            ) {
                return@withContext RootAccessibilityEnableResult(
                    success = false,
                    serviceConnected = false,
                    message = "无法读取现有无障碍服务，未修改系统设置。"
                )
            }
            val mergedServices = mergeEnabledAccessibilityServices(
                previousServicesRaw,
                component
            )
            val oplusPreparation = prepareOplusGuideSuppression(context)
            if (oplusPreparation.errorMessage != null) {
                return@withContext RootAccessibilityEnableResult(
                    success = false,
                    serviceConnected = false,
                    message = oplusPreparation.errorMessage
                )
            }
            val oplusGuard = oplusPreparation.guard
            val command = buildRootAccessibilityEnableCommand(mergedServices)
            val enableCommandResult = executeRootCommand(command)
            val exitCode = parseRootExitCode(enableCommandResult.output)
            if (!enableCommandResult.succeeded || exitCode != 0) {
                val settingsRestored = restorePreviousSettings(
                    previousServicesRaw,
                    previousGlobalRaw
                )
                if (settingsRestored) {
                    restoreOplusGuideImmediately(context, oplusGuard)
                }
                return@withContext RootAccessibilityEnableResult(
                    success = false,
                    serviceConnected = false,
                    message = if (settingsRestored) {
                        "Root 写入无障碍设置失败（exit=${exitCode ?: "unknown"}），已恢复原设置。"
                    } else {
                        "Root 写入无障碍设置失败且无法确认回滚；ColorOS 保护组件将由后台任务恢复。"
                    }
                )
            }

            val verifiedServicesResult = executeRootCommand(
                "settings get --user current secure enabled_accessibility_services 2>/dev/null"
            )
            val verifiedGlobalResult = executeRootCommand(
                "settings get --user current secure accessibility_enabled 2>/dev/null"
            )
            val verifiedServices = verifiedServicesResult.output.trim()
            val verifiedGlobal = verifiedGlobalResult.output.trim()
            val settingsEnabled =
                verifiedServicesResult.succeeded &&
                    verifiedGlobalResult.succeeded &&
                    component in parseEnabledAccessibilityServices(verifiedServices) &&
                    verifiedGlobal == "1"
            if (!settingsEnabled) {
                val settingsRestored = restorePreviousSettings(
                    previousServicesRaw,
                    previousGlobalRaw
                )
                if (settingsRestored) {
                    restoreOplusGuideImmediately(context, oplusGuard)
                }
                return@withContext RootAccessibilityEnableResult(
                    success = false,
                    serviceConnected = false,
                    message = if (settingsRestored) {
                        "系统没有接受 Root 无障碍设置，已恢复原设置。"
                    } else {
                        "系统没有确认无障碍设置且无法确认回滚；ColorOS 保护组件将由后台任务恢复。"
                    }
                )
            }

            repeat(CONNECTION_ATTEMPTS) {
                if (AndroidGuiAccessibilityService.isConnected()) {
                    return@withContext RootAccessibilityEnableResult(
                        success = true,
                        serviceConnected = true,
                        message = if (oplusGuard != null) {
                            "Murong 界面操作已通过 Root 无感启用并连接。"
                        } else {
                            "Murong 界面操作已通过 Root 启用并连接。"
                        }
                    )
                }
                delay(CONNECTION_POLL_MILLIS)
            }
            RootAccessibilityEnableResult(
                success = true,
                serviceConnected = false,
                message = "Root 设置已写入，但系统尚未绑定服务；请打开系统详情页确认。"
            )
        }

    internal fun parseEnabledAccessibilityServices(raw: String?): List<String> =
        raw
            ?.trim()
            ?.takeUnless { it.isBlank() || it.equals("null", ignoreCase = true) }
            ?.split(':')
            ?.map(String::trim)
            ?.filter(String::isNotBlank)
            ?.distinct()
            .orEmpty()

    internal fun mergeEnabledAccessibilityServices(
        existing: String?,
        component: String
    ): String = (parseEnabledAccessibilityServices(existing) + component.trim())
        .filter(String::isNotBlank)
        .distinct()
        .joinToString(":")

    internal fun buildRootAccessibilityEnableCommand(mergedServices: String): String {
        return "murong_status=0; " +
            "settings put --user current secure enabled_accessibility_services " +
            "${shellQuote(mergedServices)} 2>&1 || murong_status=\$?; " +
            "if [ \"\$murong_status\" -eq 0 ]; then " +
            "settings put --user current secure accessibility_enabled 1 2>&1 || " +
            "murong_status=\$?; fi; " +
            "printf '\\n${ROOT_RESULT_MARKER}%s\\n' \"\$murong_status\""
    }

    internal fun parseRootExitCode(output: String): Int? =
        output.lineSequence()
            .map(String::trim)
            .lastOrNull { it.startsWith(ROOT_RESULT_MARKER) }
            ?.removePrefix(ROOT_RESULT_MARKER)
            ?.trim()
            ?.toIntOrNull()

    internal fun isKnownOplusGuideComponent(component: String): Boolean =
        component.trim() == OPLUS_GUIDE_COMPONENT ||
            component.trim() == OPLUS_GUIDE_SHORT_COMPONENT

    internal fun buildOplusGuideDisableCommand(userId: Int): String =
        "pm disable --user $userId ${shellQuote(OPLUS_GUIDE_COMPONENT)} " +
            ">/dev/null 2>&1"

    internal fun buildOplusGuideRestoreCommand(userId: Int): String =
        "pm default-state --user $userId ${shellQuote(OPLUS_GUIDE_COMPONENT)} " +
            ">/dev/null 2>&1"

    private data class OplusGuideGuard(val userId: Int)

    private data class OplusGuidePreparation(
        val guard: OplusGuideGuard? = null,
        val errorMessage: String? = null
    )

    private suspend fun prepareOplusGuideSuppression(
        context: Context
    ): OplusGuidePreparation {
        val userResult = executeRootCommand("am get-current-user 2>/dev/null")
        if (!userResult.succeeded) {
            return OplusGuidePreparation(
                errorMessage = "无法确认 Android 当前用户，未修改系统设置。"
            )
        }
        val userId = userResult.output.trim().toIntOrNull()
            ?: return OplusGuidePreparation(
                errorMessage = "Android 当前用户编号无效，未修改系统设置。"
            )
        val resolvedResult = resolveOplusGuide(userId)
        if (!resolvedResult.succeeded) {
            return OplusGuidePreparation(
                errorMessage = "无法核对 ColorOS 二次提示组件，未修改系统设置。"
            )
        }
        val resolved = parseResolvedActivity(resolvedResult.output)
        if (!isKnownOplusGuideComponent(resolved)) return OplusGuidePreparation()

        val workManager = WorkManager.getInstance(context.applicationContext)
        val request =
            OneTimeWorkRequestBuilder<OplusAccessibilityGuideRestoreWorker>()
                .setInitialDelay(OPLUS_GUIDE_GUARD_SECONDS.toLong(), TimeUnit.SECONDS)
                .setInputData(workDataOf(OPLUS_GUIDE_USER_ID_INPUT to userId))
                .build()
        val workScheduled = withTimeoutOrNull(WORK_SCHEDULE_TIMEOUT_MILLIS) {
            try {
                workManager.enqueueUniqueWork(
                    OPLUS_GUIDE_RESTORE_WORK,
                    ExistingWorkPolicy.REPLACE,
                    request
                ).await()
                true
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                false
            }
        } ?: false
        if (!workScheduled) {
            workManager.cancelUniqueWork(OPLUS_GUIDE_RESTORE_WORK)
            return OplusGuidePreparation(
                errorMessage = "ColorOS 无感启用保护任务创建失败，未修改系统设置。"
            )
        }

        val guard = OplusGuideGuard(userId)
        val disableResult = executeRootCommand(buildOplusGuideDisableCommand(userId))
        val disabledCheck = resolveOplusGuide(userId)
        if (
            !disableResult.succeeded ||
            !disabledCheck.succeeded ||
            !parseResolvedActivity(disabledCheck.output)
                .equals("No activity found", ignoreCase = true)
        ) {
            restoreOplusGuideImmediately(context, guard)
            return OplusGuidePreparation(
                errorMessage = "ColorOS 二次提示组件未能安全停用，未修改无障碍设置。"
            )
        }
        return OplusGuidePreparation(guard = guard)
    }

    private suspend fun restoreOplusGuideImmediately(
        context: Context,
        guard: OplusGuideGuard?
    ) {
        if (guard == null) return
        if (restoreOplusGuideForUser(guard.userId)) {
            WorkManager.getInstance(context.applicationContext)
                .cancelUniqueWork(OPLUS_GUIDE_RESTORE_WORK)
        }
    }

    internal suspend fun restoreOplusGuideForUser(userId: Int): Boolean {
        if (userId < 0) return false
        val rootCheck = executeRootCommand("id -u")
        if (
            !rootCheck.succeeded ||
            rootCheck.output.lineSequence().none { it.trim() == "0" }
        ) {
            return false
        }
        val restoreResult = executeRootCommand(buildOplusGuideRestoreCommand(userId))
        if (!restoreResult.succeeded) return false
        val resolved = resolveOplusGuide(userId)
        return resolved.succeeded &&
            isKnownOplusGuideComponent(parseResolvedActivity(resolved.output))
    }

    private suspend fun resolveOplusGuide(userId: Int): BoundedRootCommandResult =
        executeRootCommand(
            "cmd package resolve-activity --brief --user $userId " +
                "-a ${shellQuote(OPLUS_GUIDE_ACTION)} 2>/dev/null"
        )

    internal fun parseResolvedActivity(output: String): String =
        output.lineSequence()
            .map(String::trim)
            .lastOrNull(String::isNotBlank)
            .orEmpty()

    private fun isValidServiceSetting(raw: String): Boolean {
        if (raw.isBlank() || raw.equals("null", ignoreCase = true)) return true
        return parseEnabledAccessibilityServices(raw).all {
            ComponentName.unflattenFromString(it) != null
        }
    }

    private fun isValidGlobalSetting(raw: String): Boolean =
        raw.isBlank() || raw.equals("null", ignoreCase = true) || raw == "0" || raw == "1"

    private suspend fun restorePreviousSettings(
        previousServicesRaw: String,
        previousGlobalRaw: String
    ): Boolean {
        val restoreServices = if (
            previousServicesRaw.isBlank() ||
            previousServicesRaw.equals("null", ignoreCase = true)
        ) {
            "settings delete --user current secure enabled_accessibility_services >/dev/null 2>&1"
        } else {
            "settings put --user current secure enabled_accessibility_services " +
                "${shellQuote(previousServicesRaw)} >/dev/null 2>&1"
        }
        val restoreGlobal = if (
            previousGlobalRaw.isBlank() ||
            previousGlobalRaw.equals("null", ignoreCase = true)
        ) {
            "settings delete --user current secure accessibility_enabled >/dev/null 2>&1"
        } else {
            "settings put --user current secure accessibility_enabled " +
                "${shellQuote(previousGlobalRaw)} >/dev/null 2>&1"
        }
        return executeRootCommand(
            "murong_restore_status=0; " +
                "$restoreServices || murong_restore_status=\$?; " +
                "$restoreGlobal || murong_restore_status=\$?; " +
                "exit \$murong_restore_status"
        ).succeeded
    }

    private data class BoundedRootCommandResult(
        val output: String,
        val exitCode: Int? = null,
        val timedOut: Boolean = false,
        val error: String? = null
    ) {
        val succeeded: Boolean
            get() = !timedOut && error == null && exitCode == 0
    }

    /**
     * Accessibility authorization must never depend on the shared persistent Root shell.
     * A dedicated one-shot process avoids marker/protocol interference from concurrent tools,
     * and every invocation has a hard deadline.
     */
    private suspend fun executeRootCommand(command: String): BoundedRootCommandResult =
        withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            val result = runCatching {
                val suPath = ToolchainManager.resolveSystemCommandPath("su")
                val process = ProcessBuilder(suPath, "-c", command)
                    .redirectErrorStream(true)
                    .start()
                val output = StringBuilder(4096)
                val outputLock = Any()
                var truncated = false
                val readerThread = Thread {
                    runCatching {
                        InputStreamReader(process.inputStream, Charsets.UTF_8).use { reader ->
                            val buffer = CharArray(4 * 1024)
                            while (true) {
                                val count = reader.read(buffer)
                                if (count < 0) break
                                synchronized(outputLock) {
                                    val remaining =
                                        ROOT_COMMAND_MAX_OUTPUT_CHARS - output.length
                                    if (remaining > 0) {
                                        output.append(
                                            buffer,
                                            0,
                                            count.coerceAtMost(remaining)
                                        )
                                    }
                                    if (count > remaining) truncated = true
                                }
                            }
                        }
                    }
                }.apply {
                    name = "murong-accessibility-root-output"
                    isDaemon = true
                    start()
                }

                val finished = process.waitFor(
                    ROOT_COMMAND_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS
                )
                if (!finished) {
                    process.destroy()
                    if (!process.waitFor(600, TimeUnit.MILLISECONDS)) {
                        process.destroyForcibly()
                        process.waitFor(600, TimeUnit.MILLISECONDS)
                    }
                }
                readerThread.join(1_000)
                val captured = synchronized(outputLock) {
                    buildString {
                        append(output)
                        if (truncated) {
                            if (isNotEmpty() && last() != '\n') append('\n')
                            append("...(Root 输出已截断)")
                        }
                    }
                }
                BoundedRootCommandResult(
                    output = captured,
                    exitCode = if (finished) process.exitValue() else null,
                    timedOut = !finished
                )
            }.getOrElse { error ->
                BoundedRootCommandResult(
                    output = "",
                    error = error.message ?: error.javaClass.simpleName
                )
            }
            currentCoroutineContext().ensureActive()
            result
        }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}

class OplusAccessibilityGuideRestoreWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val userId = inputData.getInt("user_id", -1)
        if (userId < 0) {
            Result.failure()
        } else if (AndroidGuiAccessibilityAccess.restoreOplusGuideForUser(userId)) {
            Result.success()
        } else {
            Result.retry()
        }
    }
}
