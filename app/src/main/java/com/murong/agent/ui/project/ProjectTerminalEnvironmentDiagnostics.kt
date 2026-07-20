package com.murong.agent.ui.project

/**
 * A read-only health report for the shell that backs one project-terminal tab.
 *
 * The command produced here deliberately avoids package installation, repository changes,
 * and filesystem writes. It is safe to run against both the system shell and the extension
 * package environment.
 */
internal enum class ProjectTerminalDiagnosticLevel {
    PASS,
    WARNING,
    ERROR
}

internal data class ProjectTerminalEnvironmentCheck(
    val id: String,
    val label: String,
    val level: ProjectTerminalDiagnosticLevel,
    val detail: String
)

internal data class ProjectTerminalEnvironmentDiagnostic(
    val environment: String,
    val shell: String,
    val path: String,
    val prefix: String?,
    val architecture: String,
    val workingDirectory: String,
    val environmentVersion: String?,
    val checks: List<ProjectTerminalEnvironmentCheck>,
    val failureMessage: String? = null
) {
    val errorCount: Int
        get() = checks.count { it.level == ProjectTerminalDiagnosticLevel.ERROR }

    val warningCount: Int
        get() = checks.count { it.level == ProjectTerminalDiagnosticLevel.WARNING }

    val isHealthy: Boolean
        get() = failureMessage == null && errorCount == 0

    val headline: String
        get() = when {
            failureMessage != null -> "自检未完成：$failureMessage"
            errorCount > 0 -> "发现 $errorCount 项不可用"
            warningCount > 0 -> "检查完成，有 $warningCount 项需要留意"
            else -> "检查完成：当前环境可用"
        }

    fun toClipboardText(): String {
        return buildString {
            appendLine("MurongAgent 终端环境自检")
            appendLine("环境: $environment")
            environmentVersion?.takeIf { it.isNotBlank() }?.let { appendLine("环境版本: $it") }
            architecture.takeIf { it.isNotBlank() }?.let { appendLine("架构: $it") }
            workingDirectory.takeIf { it.isNotBlank() }?.let { appendLine("工作目录: $it") }
            appendLine("Shell: $shell")
            prefix?.takeIf { it.isNotBlank() }?.let { appendLine("PREFIX: $it") }
            path.takeIf { it.isNotBlank() }?.let { appendLine("PATH: $it") }
            failureMessage?.let { appendLine("结果: $it") }
            appendLine()
            checks.forEach { check ->
                append("[")
                append(check.level.clipboardLabel)
                append("] ")
                append(check.label)
                append(": ")
                appendLine(check.detail)
            }
        }.trimEnd()
    }
}

private val ProjectTerminalDiagnosticLevel.clipboardLabel: String
    get() = when (this) {
        ProjectTerminalDiagnosticLevel.PASS -> "通过"
        ProjectTerminalDiagnosticLevel.WARNING -> "注意"
        ProjectTerminalDiagnosticLevel.ERROR -> "不可用"
    }

internal fun buildProjectTerminalEnvironmentDiagnosticCommand(
    runId: String,
    environmentMode: ProjectTerminalEnvironmentMode,
    environmentVersion: String? = null
): String {
    val startMarker = projectTerminalEnvironmentDiagnosticStartMarker(runId)
    val endMarker = projectTerminalEnvironmentDiagnosticEndMarker(runId)
    val environmentLabel = when (environmentMode) {
        ProjectTerminalEnvironmentMode.TOOLCHAIN -> "扩展包环境"
        ProjectTerminalEnvironmentMode.SYSTEM -> "系统环境"
        ProjectTerminalEnvironmentMode.ROOT -> "Root 扩展包环境"
    }
    val resolvedEnvironmentVersion = environmentVersion
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.replace("|", "/")
        ?.replace("'", "’")
        ?: "未读取到版本"

    return """
        {
          __murong_diag_emit() { printf '%s|%s|%s|%s\n' "${'$'}1" "${'$'}2" "${'$'}3" "${'$'}4"; }
          __murong_diag_command() {
            __murong_diag_name="${'$'}1"
            __murong_diag_path="${'$'}(command -v "${'$'}__murong_diag_name" 2>/dev/null)"
            "${'$'}__murong_diag_name" --version >/dev/null 2>&1
            __murong_diag_result="${'$'}?"
            if [ "${'$'}__murong_diag_result" -eq 0 ]; then
              if [ -n "${'$'}__murong_diag_path" ]; then
                __murong_diag_emit command "${'$'}__murong_diag_name" ok "${'$'}__murong_diag_path"
              else
                __murong_diag_emit command "${'$'}__murong_diag_name" ok "由当前 shell 解析"
              fi
            elif [ -n "${'$'}__murong_diag_path" ]; then
              __murong_diag_emit command "${'$'}__murong_diag_name" warn "${'$'}__murong_diag_path（版本探测失败）"
            else
              __murong_diag_emit command "${'$'}__murong_diag_name" missing "无法解析"
            fi
          }
          printf '%s\n' '$startMarker'
          __murong_diag_emit meta environment ok '$environmentLabel'
          __murong_diag_emit meta version ok '$resolvedEnvironmentVersion'
          __murong_diag_emit meta shell ok "${'$'}SHELL"
          __murong_diag_emit meta path ok "${'$'}PATH"
          __murong_diag_emit meta prefix ok "${'$'}PREFIX"
          __murong_diag_emit meta architecture ok "${'$'}(uname -m 2>/dev/null || getprop ro.product.cpu.abi 2>/dev/null)"
          __murong_diag_emit meta working-directory ok "${'$'}(pwd 2>/dev/null)"
          __murong_diag_shell_path="${'$'}(command -v sh 2>/dev/null)"
          if [ -n "${'$'}__murong_diag_shell_path" ] && sh -c ':' >/dev/null 2>&1; then
            __murong_diag_emit command sh ok "${'$'}__murong_diag_shell_path"
          else
            __murong_diag_emit command sh missing "无法启动 sh"
          fi
          __murong_diag_command bash
          __murong_diag_command apt
          __murong_diag_command pkg
          __murong_diag_command python
          __murong_diag_command pip
          __murong_diag_command git
          if command -v apt-get >/dev/null 2>&1; then
            if apt-get check >/dev/null 2>&1; then
              __murong_diag_emit package apt-get-check ok "软件包依赖完整"
            else
              __murong_diag_emit package apt-get-check warn "检测到未满足依赖，可在终端查看 apt --fix-broken install"
            fi
          else
            __murong_diag_emit package apt-get-check warn "当前环境没有 apt-get，已跳过"
          fi
          if [ -d /storage/emulated/0 ] && [ -r /storage/emulated/0 ]; then
            __murong_diag_emit storage shared-storage ok "/storage/emulated/0 可读取"
          else
            __murong_diag_emit storage shared-storage warn "无法读取 /storage/emulated/0，请检查存储权限"
          fi
          printf '%s\n' '$endMarker'
          unset -f __murong_diag_emit __murong_diag_command 2>/dev/null || true
          unset __murong_diag_name __murong_diag_path __murong_diag_result __murong_diag_shell_path
        }
    """.trimIndent()
}

internal fun parseProjectTerminalEnvironmentDiagnosticTranscript(
    transcript: String,
    runId: String
): ProjectTerminalEnvironmentDiagnostic? {
    val startMarker = projectTerminalEnvironmentDiagnosticStartMarker(runId)
    val endMarker = projectTerminalEnvironmentDiagnosticEndMarker(runId)
    var activeBlock: MutableList<String>? = null
    var latestCompleteBlock: List<String>? = null

    transcript.replace("\r", "").lineSequence().forEach { rawLine ->
        val line = rawLine.trim()
        when {
            line == startMarker -> activeBlock = mutableListOf()
            line == endMarker && activeBlock != null -> {
                latestCompleteBlock = activeBlock.orEmpty()
                activeBlock = null
            }
            activeBlock != null -> activeBlock.add(line)
        }
    }

    val records = latestCompleteBlock ?: return null
    var environment = "未知环境"
    var shell = "未报告"
    var path = ""
    var prefix: String? = null
    var architecture = ""
    var workingDirectory = ""
    var environmentVersion: String? = null
    val checks = mutableListOf<ProjectTerminalEnvironmentCheck>()

    records.forEach { record ->
        val fields = record.split('|', limit = 4)
        if (fields.size != 4) return@forEach
        val category = fields[0].trim()
        val key = fields[1].trim()
        val state = fields[2].trim()
        val detail = fields[3].trim().ifBlank { "未提供详情" }
        if (category == "meta") {
            when (key) {
                "environment" -> environment = detail
                "shell" -> shell = detail.ifBlank { "未设置" }
                "path" -> path = detail
                "prefix" -> prefix = detail.takeIf { it.isNotBlank() }
                "architecture" -> architecture = detail
                "working-directory" -> workingDirectory = detail
                "version" -> environmentVersion = detail.takeIf { it.isNotBlank() }
            }
            return@forEach
        }
        checks += ProjectTerminalEnvironmentCheck(
            id = "$category:$key",
            label = projectTerminalDiagnosticLabel(category, key),
            level = projectTerminalDiagnosticLevel(state),
            detail = detail
        )
    }

    return ProjectTerminalEnvironmentDiagnostic(
        environment = environment,
        shell = shell,
        path = path,
        prefix = prefix,
        architecture = architecture,
        workingDirectory = workingDirectory,
        environmentVersion = environmentVersion,
        checks = checks
    )
}

internal fun projectTerminalEnvironmentDiagnosticUnavailable(
    environmentMode: ProjectTerminalEnvironmentMode,
    message: String
): ProjectTerminalEnvironmentDiagnostic {
    val environment = when (environmentMode) {
        ProjectTerminalEnvironmentMode.TOOLCHAIN -> "扩展包环境"
        ProjectTerminalEnvironmentMode.SYSTEM -> "系统环境"
        ProjectTerminalEnvironmentMode.ROOT -> "Root 扩展包环境"
    }
    return ProjectTerminalEnvironmentDiagnostic(
        environment = environment,
        shell = "未报告",
        path = "",
        prefix = null,
        architecture = "",
        workingDirectory = "",
        environmentVersion = null,
        checks = emptyList(),
        failureMessage = message
    )
}

private fun projectTerminalEnvironmentDiagnosticStartMarker(runId: String): String {
    return "__MURONG_ENV_DIAG_START__$runId"
}

private fun projectTerminalEnvironmentDiagnosticEndMarker(runId: String): String {
    return "__MURONG_ENV_DIAG_END__$runId"
}

private fun projectTerminalDiagnosticLevel(state: String): ProjectTerminalDiagnosticLevel {
    return when (state.lowercase()) {
        "ok" -> ProjectTerminalDiagnosticLevel.PASS
        "warn", "warning", "skipped" -> ProjectTerminalDiagnosticLevel.WARNING
        else -> ProjectTerminalDiagnosticLevel.ERROR
    }
}

private fun projectTerminalDiagnosticLabel(category: String, key: String): String {
    return when (category) {
        "command" -> "命令 $key"
        "package" -> "软件包依赖"
        "storage" -> "共享存储"
        else -> key
    }
}
