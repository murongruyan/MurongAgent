package com.murong.agent.core.loop

import java.nio.charset.StandardCharsets
import java.util.UUID

enum class FileMentionKind(val label: String) {
    TEXT("文本"),
    SCRIPT("脚本"),
    ARCHIVE("归档"),
    IMAGE("图片"),
    BINARY("二进制"),
    DIRECTORY("目录"),
    UNKNOWN("未知")
}

/** Whether the selected path was readable at the time the context snapshot was made. */
enum class FileMentionAccessState(val label: String) {
    READABLE("可读"),
    UNREADABLE("不可读"),
    MISSING("已移除"),
    PERMISSION_REQUIRED("需要文件权限")
}

/** Explicitly describes what is sent to the model, rather than implying it from a path. */
enum class FileMentionInclusionMode(val label: String) {
    NAME_ONLY("仅名称"),
    METADATA("元数据"),
    DIRECTORY_PATH("目录路径"),
    /** A bounded, non-recursive listing; children are never read or traversed automatically. */
    DIRECTORY_MANIFEST("目录清单"),
    TEXT_EXCERPT("文本摘录")
}

enum class FileMentionSource(val label: String) {
    MANUAL("本轮添加"),
    SEARCH_RESULT("文件搜索"),
    PROJECT_KNOWLEDGE("项目知识"),
    KNOWLEDGE_SNAPSHOT("知识快照"),
    TOOL_RESULT("工具结果")
}

internal fun detectFileMentionKind(path: String): FileMentionKind {
    val name = path.trim().substringAfterLast('/').substringAfterLast('\\').lowercase()
    val extension = name.substringAfterLast('.', missingDelimiterValue = "")
    return when {
        name in setOf("dockerfile", "makefile", "cmakelists.txt", ".gitignore", ".editorconfig") ->
            FileMentionKind.TEXT
        extension in setOf("sh", "bash", "zsh", "fish", "py", "rb", "pl", "lua", "js", "ts", "kts") ->
            FileMentionKind.SCRIPT
        extension in setOf("zip", "apk", "jar", "aar", "tar", "gz", "tgz", "bz2", "xz", "7z", "rar") ->
            FileMentionKind.ARCHIVE
        extension in setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "heic", "svg") ->
            FileMentionKind.IMAGE
        extension in setOf("img", "iso", "bin", "elf", "so", "dll", "exe", "dat") ->
            FileMentionKind.BINARY
        extension in setOf(
            "txt", "md", "json", "yaml", "yml", "xml", "toml", "ini", "conf", "properties",
            "kt", "java", "c", "cc", "cpp", "h", "hpp", "rs", "go", "swift", "cs", "php",
            "html", "css", "scss", "sql", "gradle", "pro", "csv", "log", "diff", "patch"
        ) -> FileMentionKind.TEXT
        else -> FileMentionKind.UNKNOWN
    }
}

internal fun restoreFileMentionKind(
    persistedName: String,
    path: String
): FileMentionKind {
    return FileMentionKind.entries.firstOrNull { it.name == persistedName }
        ?: detectFileMentionKind(path)
}

internal fun fileMentionContextPolicy(kind: FileMentionKind): String {
    return when (kind) {
        FileMentionKind.TEXT -> "文本文件：将提供受预算限制的内容摘录。"
        FileMentionKind.SCRIPT -> "脚本文件：将提供受预算限制的文本内容；不会执行脚本。"
        FileMentionKind.ARCHIVE -> "归档文件：当前只提供文件元数据，不会自动解压或读取二进制内容。"
        FileMentionKind.IMAGE -> "图片文件：当前只提供文件元数据；需要视觉分析时应作为图片附件单独发送。"
        FileMentionKind.BINARY -> "二进制文件：当前只提供文件元数据，不会把二进制内容当作文本读取。"
        FileMentionKind.DIRECTORY -> "目录对象：当前只提供受限的第一层清单，不会隐式递归扫描或读取子文件内容。"
        FileMentionKind.UNKNOWN -> "未知类型文件：当前只提供文件元数据，不会猜测或读取内容。"
    }
}

internal fun defaultFileMentionInclusionMode(kind: FileMentionKind): FileMentionInclusionMode {
    return when (kind) {
        FileMentionKind.TEXT,
        FileMentionKind.SCRIPT -> FileMentionInclusionMode.TEXT_EXCERPT
        FileMentionKind.DIRECTORY -> FileMentionInclusionMode.DIRECTORY_MANIFEST
        FileMentionKind.ARCHIVE,
        FileMentionKind.IMAGE,
        FileMentionKind.BINARY,
        FileMentionKind.UNKNOWN -> FileMentionInclusionMode.METADATA
    }
}

internal fun restoreFileMentionAccessState(name: String): FileMentionAccessState {
    return FileMentionAccessState.entries.firstOrNull { it.name == name }
        ?: FileMentionAccessState.READABLE
}

internal fun restoreFileMentionInclusionMode(
    name: String,
    kind: FileMentionKind
): FileMentionInclusionMode {
    return FileMentionInclusionMode.entries.firstOrNull { it.name == name }
        ?: defaultFileMentionInclusionMode(kind)
}

internal fun restoreFileMentionSource(name: String): FileMentionSource {
    return FileMentionSource.entries.firstOrNull { it.name == name }
        ?: FileMentionSource.MANUAL
}

internal fun stableFileMentionId(path: String, modifiedAtMillis: Long?): String {
    val normalized = path.trim().replace('\\', '/')
    val raw = "$normalized@${modifiedAtMillis ?: "unknown"}"
    return UUID.nameUUIDFromBytes(raw.toByteArray(StandardCharsets.UTF_8)).toString()
}
