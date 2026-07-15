package com.murong.agent.ui.project

import android.content.Context
import com.murong.agent.common.toolchain.ToolchainManager
import java.io.File
import java.util.concurrent.TimeUnit

data class ProjectRunPlan(
    val language: String,
    val command: List<String>,
    val requiredPackages: List<String>,
    val requiredCommands: List<String>,
    val workingDirectory: File,
    val file: File
)

data class ProjectRunResult(
    val command: List<String>,
    val output: String,
    val exitCode: Int?,
    val cancelled: Boolean = false,
    val error: String? = null
)

internal object ProjectRunExecutor {
    private const val BUILD_DIR_NAME = ".murong-run"
    private const val MAX_OUTPUT_CHARS = 512 * 1024

    fun plan(projectRoot: File, file: File, content: String): Result<ProjectRunPlan> = runCatching {
        val root = projectRoot.canonicalFile
        val target = file.canonicalFile
        require(target.path.startsWith(root.path + File.separator)) { "只能运行当前项目内的文件" }
        require(target.isFile) { "运行目标不是普通文件" }

        val shebang = content.lineSequence().firstOrNull()?.takeIf { it.startsWith("#!") }
        val extension = target.extension.lowercase()
        val relativeFile = target.relativeTo(root).path
        val buildDir = File(root, BUILD_DIR_NAME).apply { mkdirs() }
        when {
            shebang?.contains("python") == true || extension == "py" -> plan("Python", listOf("python", relativeFile), listOf("python"), listOf("python"), root, target)
            shebang?.contains("bash") == true || extension in setOf("sh", "bash") -> plan("Shell", listOf("bash", relativeFile), listOf("bash"), listOf("bash"), root, target)
            shebang?.contains("sh") == true -> plan("Shell", listOf("sh", relativeFile), emptyList(), listOf("sh"), root, target)
            shebang?.contains("node") == true || extension in setOf("js", "mjs", "cjs") -> plan("JavaScript", listOf("node", relativeFile), listOf("nodejs"), listOf("node"), root, target)
            extension == "ts" -> plan("TypeScript", listOf("tsx", relativeFile), listOf("nodejs", "typescript", "tsx"), listOf("tsx"), root, target)
            extension == "rb" -> plan("Ruby", listOf("ruby", relativeFile), listOf("ruby"), listOf("ruby"), root, target)
            extension == "php" -> plan("PHP", listOf("php", relativeFile), listOf("php"), listOf("php"), root, target)
            extension in setOf("pl", "pm") -> plan("Perl", listOf("perl", relativeFile), listOf("perl"), listOf("perl"), root, target)
            extension == "lua" -> plan("Lua", listOf("lua", relativeFile), listOf("lua"), listOf("lua"), root, target)
            extension == "r" -> plan("R", listOf("Rscript", relativeFile), listOf("r-base"), listOf("Rscript"), root, target)
            extension == "tcl" -> plan("Tcl", listOf("tclsh", relativeFile), listOf("tcl"), listOf("tclsh"), root, target)
            extension == "c" -> compilePlan("C", listOf("clang", relativeFile, "-o", File(buildDir, target.nameWithoutExtension).path), listOf("clang"), listOf("clang"), File(buildDir, target.nameWithoutExtension), root, target)
            extension in setOf("cc", "cpp", "cxx") -> compilePlan("C++", listOf("clang++", relativeFile, "-o", File(buildDir, target.nameWithoutExtension).path), listOf("clang"), listOf("clang++"), File(buildDir, target.nameWithoutExtension), root, target)
            extension == "go" -> compilePlan("Go", listOf("go", "build", "-o", File(buildDir, target.nameWithoutExtension).path, relativeFile), listOf("golang"), listOf("go"), File(buildDir, target.nameWithoutExtension), root, target)
            extension == "rs" -> compilePlan("Rust", listOf("rustc", relativeFile, "-o", File(buildDir, target.nameWithoutExtension).path), listOf("rust"), listOf("rustc"), File(buildDir, target.nameWithoutExtension), root, target)
            extension == "java" -> javaPlan(relativeFile, target, content, buildDir, root)
            extension in setOf("kt", "kts") -> plan("Kotlin", listOf("kotlinc", "-script", relativeFile), listOf("kotlin"), listOf("kotlinc"), root, target)
            else -> error("无法识别 ${target.name} 的运行方式。请使用 shebang 或受支持的文件扩展名。")
        }
    }

    fun areToolsAvailable(context: Context, commands: List<String>): Boolean =
        commands.all { ToolchainManager.findCommandPath(it, context) != null }

    fun installPackages(context: Context, packages: List<String>, onOutput: (String) -> Unit): ProjectRunResult {
        if (!ToolchainManager.hasRelocatablePackageManager(context)) {
            return ProjectRunResult(emptyList(), "", null, error = "当前终端扩展包的 APT/dpkg 未提供可重定位运行时，无法自动安装。请更新到包含可重定位 package manager 的扩展包。")
        }
        val pkg = ToolchainManager.findCommandPath("pkg", context)
            ?: return ProjectRunResult(emptyList(), "", null, error = "pkg 不可用；请安装或更新终端扩展包")
        return execute(
            context = context,
            command = listOf(pkg, "install", "-y") + packages.distinct(),
            workingDirectory = File(context.filesDir, "toolchain"),
            onOutput = onOutput
        )
    }

    fun execute(
        context: Context,
        command: List<String>,
        workingDirectory: File,
        onProcessStarted: (Process) -> Unit = {},
        onOutput: (String) -> Unit
    ): ProjectRunResult {
        val safeDirectory = workingDirectory.takeIf(File::exists) ?: return ProjectRunResult(command, "", null, error = "工作目录不存在")
        return runCatching {
            val process = ProcessBuilder(command)
                .directory(safeDirectory)
                .redirectErrorStream(true)
                .apply { ToolchainManager.applyProcessEnvironment(environment(), context) }
                .start()
            onProcessStarted(process)
            val output = StringBuilder()
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (output.length < MAX_OUTPUT_CHARS) output.appendLine(line)
                    onOutput(line + "\n")
                }
            }
            val exitCode = process.waitFor()
            if (output.length >= MAX_OUTPUT_CHARS) output.appendLine("[输出已截断到 512 KiB]")
            ProjectRunResult(command, output.toString(), exitCode)
        }.getOrElse { error ->
            ProjectRunResult(command, "", null, error = error.message ?: "启动运行进程失败")
        }
    }

    fun stop(process: Process?) {
        process?.destroy()
        process?.waitFor(800, TimeUnit.MILLISECONDS)
        if (process?.isAlive == true) process.destroyForcibly()
    }

    private fun plan(language: String, command: List<String>, packages: List<String>, commands: List<String>, root: File, file: File) =
        ProjectRunPlan(language, command, packages, commands, root, file)

    private fun compilePlan(language: String, compile: List<String>, packages: List<String>, commands: List<String>, binary: File, root: File, file: File): ProjectRunPlan {
        val shell = ToolchainManager.findCommandPath("sh") ?: "sh"
        val compileQuoted = compile.joinToString(" ") { shellQuote(it) }
        return ProjectRunPlan(language, listOf(shell, "-c", "$compileQuoted && ${shellQuote(binary.path)}"), packages, commands, root, file)
    }

    private fun javaPlan(relativeFile: String, file: File, content: String, buildDir: File, root: File): ProjectRunPlan {
        val packageName = Regex("(?m)^\\s*package\\s+([A-Za-z_][A-Za-z0-9_.]*)\\s*;").find(content)?.groupValues?.get(1)
        val className = file.nameWithoutExtension
        val mainClass = listOfNotNull(packageName, className).joinToString(".")
        val shell = ToolchainManager.findCommandPath("sh") ?: "sh"
        val compile = listOf("javac", "-d", buildDir.path, relativeFile).joinToString(" ") { shellQuote(it) }
        val run = listOf("java", "-cp", buildDir.path, mainClass).joinToString(" ") { shellQuote(it) }
        return ProjectRunPlan("Java", listOf(shell, "-c", "$compile && $run"), listOf("openjdk-17"), listOf("javac", "java"), root, file)
    }

    private fun shellQuote(value: String) = "'${value.replace("'", "'\\\"'\\\"'")}'"
}
