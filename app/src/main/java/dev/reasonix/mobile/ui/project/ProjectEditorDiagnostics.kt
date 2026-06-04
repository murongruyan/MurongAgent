package dev.reasonix.mobile.ui.project

import kotlinx.serialization.json.Json
import java.util.Locale

private val PROJECT_EDITOR_JSON = Json { ignoreUnknownKeys = true }

internal fun buildProjectEditorDiagnostics(
    content: String,
    language: String?
): List<ProjectEditorDiagnostic> {
    if (content.isEmpty()) return emptyList()
    val normalizedLanguage = normalizeDiagnosticLanguage(language)
    return buildList {
        addAll(detectGitConflictDiagnostics(content))
        if (shouldUseBracketDiagnostics(normalizedLanguage)) {
            addAll(detectBracketDiagnostics(content))
        }
        when (normalizedLanguage) {
            "json" -> addAll(detectJsonDiagnostics(content))
            "python" -> addAll(detectPythonDiagnostics(content))
            "bash" -> addAll(detectShellDiagnostics(content))
            "c", "cpp" -> addAll(detectCppDiagnostics(content))
            "toml" -> addAll(detectTomlDiagnostics(content))
            "ini", "conf", "properties" -> addAll(detectIniLikeDiagnostics(content))
            "yaml" -> addAll(detectYamlDiagnostics(content))
            "html", "xml" -> addAll(detectMarkupDiagnostics(content))
            "css" -> addAll(detectCssDiagnostics(content))
            "sql" -> addAll(detectSqlDiagnostics(content))
            "markdown" -> addAll(detectMarkdownDiagnostics(content))
        }
    }
        .distinctBy { Triple(it.startIndex, it.endIndex, it.message) }
        .sortedWith(compareBy<ProjectEditorDiagnostic> { it.lineNumber }.thenBy { it.startIndex })
}

internal fun detectGitConflictBlocks(content: String): List<ProjectGitConflictBlock> {
    if (content.isBlank()) return emptyList()
    val lines = content.lines()
    val blocks = mutableListOf<ProjectGitConflictBlock>()
    var index = 0
    while (index < lines.size) {
        val startMarker = lines[index].trimStart()
        if (!startMarker.startsWith("<<<<<<<")) {
            index++
            continue
        }
        val startLine = index + 1
        val currentLabel = startMarker.removePrefix("<<<<<<<").trim().ifBlank { "当前分支" }
        val currentLines = mutableListOf<String>()
        index++
        while (index < lines.size && !lines[index].trimStart().startsWith("=======")) {
            currentLines += lines[index]
            index++
        }
        if (index >= lines.size) break
        val dividerLine = index + 1
        index++
        val incomingLines = mutableListOf<String>()
        while (index < lines.size && !lines[index].trimStart().startsWith(">>>>>>>")) {
            incomingLines += lines[index]
            index++
        }
        if (index >= lines.size) break
        val endLine = index + 1
        val incomingLabel = lines[index].trimStart().removePrefix(">>>>>>>").trim().ifBlank { "合并目标" }
        blocks += ProjectGitConflictBlock(
            startLine = startLine,
            dividerLine = dividerLine,
            endLine = endLine,
            currentLabel = currentLabel,
            incomingLabel = incomingLabel,
            currentLines = currentLines,
            incomingLines = incomingLines
        )
        index++
    }
    return blocks
}

private fun detectGitConflictDiagnostics(content: String): List<ProjectEditorDiagnostic> {
    val diagnostics = mutableListOf<ProjectEditorDiagnostic>()
    content.lines().forEachIndexed { index, rawLine ->
        val lineNumber = index + 1
        val trimmed = rawLine.trimStart()
        when {
            trimmed.startsWith("<<<<<<<") ->
                diagnostics += diagnosticAtLine(content, lineNumber, "发现 Git 冲突起始标记 `<<<<<<<`，请手动选择保留内容")

            trimmed.startsWith("=======") ->
                diagnostics += diagnosticAtLine(content, lineNumber, "发现 Git 冲突分隔标记 `=======`")

            trimmed.startsWith(">>>>>>>") ->
                diagnostics += diagnosticAtLine(content, lineNumber, "发现 Git 冲突结束标记 `>>>>>>>`")
        }
    }
    return diagnostics
}

private fun normalizeDiagnosticLanguage(language: String?): String {
    return when (language?.lowercase(Locale.getDefault())) {
        "kt" -> "kotlin"
        "js", "jsx" -> "javascript"
        "ts", "tsx" -> "typescript"
        "sh", "shell" -> "bash"
        "gradle" -> "groovy"
        "c++", "cxx", "hpp", "h", "hh", "hxx" -> "cpp"
        "htm" -> "html"
        "cfg" -> "conf"
        else -> language?.lowercase(Locale.getDefault()).orEmpty()
    }
}

private fun shouldUseBracketDiagnostics(language: String): Boolean {
    return language in setOf(
        "kotlin", "java", "groovy", "javascript", "typescript",
        "rust", "c", "cpp", "bash", "python", "sql", "css", "json"
    )
}

private fun detectBracketDiagnostics(content: String): List<ProjectEditorDiagnostic> {
    val diagnostics = mutableListOf<ProjectEditorDiagnostic>()
    val stack = ArrayDeque<Pair<Char, Int>>()
    var inSingleQuote = false
    var inDoubleQuote = false
    var singleQuoteStart = -1
    var doubleQuoteStart = -1
    var escaped = false
    content.forEachIndexed { index, char ->
        if (escaped) {
            escaped = false
            return@forEachIndexed
        }
        if (char == '\\') {
            if (inSingleQuote || inDoubleQuote) {
                escaped = true
            }
            return@forEachIndexed
        }
        if (inSingleQuote) {
            if (char == '\'') {
                inSingleQuote = false
                singleQuoteStart = -1
            }
            return@forEachIndexed
        }
        if (inDoubleQuote) {
            if (char == '"') {
                inDoubleQuote = false
                doubleQuoteStart = -1
            }
            return@forEachIndexed
        }
        when (char) {
            '\'' -> {
                inSingleQuote = true
                singleQuoteStart = index
            }
            '"' -> {
                inDoubleQuote = true
                doubleQuoteStart = index
            }
            '(', '[', '{' -> stack.addLast(char to index)
            ')', ']', '}' -> {
                val expectedOpen = matchingOpenBracket(char)
                val top = stack.removeLastOrNull()
                if (top == null) {
                    diagnostics += diagnosticAtOffset(content, index, "多余的关闭括号 `$char`")
                } else if (top.first != expectedOpen) {
                    diagnostics += diagnosticAtOffset(
                        content = content,
                        offset = index,
                        message = "括号不匹配，遇到 `$char`，对应开始是 `${top.first}`"
                    )
                }
            }
        }
    }
    if (inSingleQuote && singleQuoteStart >= 0) {
        diagnostics += diagnosticAtOffset(content, singleQuoteStart, "单引号字符串未闭合")
    }
    if (inDoubleQuote && doubleQuoteStart >= 0) {
        diagnostics += diagnosticAtOffset(content, doubleQuoteStart, "双引号字符串未闭合")
    }
    stack.forEach { (bracket, offset) ->
        diagnostics += diagnosticAtOffset(content, offset, "括号 `$bracket` 没有闭合")
    }
    return diagnostics
}

private fun detectJsonDiagnostics(content: String): List<ProjectEditorDiagnostic> {
    val normalized = content.trim()
    if (normalized.isEmpty()) return emptyList()
    val error = runCatching { PROJECT_EDITOR_JSON.parseToJsonElement(content) }.exceptionOrNull() ?: return emptyList()
    val offset = extractJsonDiagnosticOffset(error.message).coerceIn(0, (content.length - 1).coerceAtLeast(0))
    val message = error.message
        ?.substringBefore('\n')
        ?.replace("Unexpected JSON token at offset", "JSON 错误，位置")
        ?.ifBlank { "JSON 结构错误" }
        ?: "JSON 结构错误"
    return listOf(diagnosticAtOffset(content, offset, message))
}

private fun detectPythonDiagnostics(content: String): List<ProjectEditorDiagnostic> {
    val diagnostics = mutableListOf<ProjectEditorDiagnostic>()
    val lines = content.lines()
    val headerRegex = Regex("""^\s*(if|elif|else|for|while|try|except|finally|with|def|class|async def|match|case)\b""")
    lines.forEachIndexed { index, line ->
        val trimmed = line.trim()
        if (trimmed.isBlank() || trimmed.startsWith("#")) return@forEachIndexed
        val leading = line.takeWhile { it == ' ' || it == '\t' }
        if (leading.contains(' ') && leading.contains('\t')) {
            diagnostics += diagnosticAtLine(content, index + 1, "Python 缩进不要混用空格和 Tab")
        }
        if (headerRegex.containsMatchIn(line) && !trimmed.endsWith(":")) {
            diagnostics += diagnosticAtLine(content, index + 1, "Python 代码块声明通常需要以冒号结尾")
        }
        if (trimmed.endsWith(":")) {
            val currentIndent = leadingIndentWidth(line)
            val nextLineIndex = nextNonEmptyLineIndex(lines, index + 1) ?: return@forEachIndexed
            val nextIndent = leadingIndentWidth(lines[nextLineIndex])
            if (nextIndent <= currentIndent) {
                diagnostics += diagnosticAtLine(content, index + 1, "Python 代码块后面缺少更深一级的缩进")
            }
        }
    }
    return diagnostics
}

private fun detectShellDiagnostics(content: String): List<ProjectEditorDiagnostic> {
    val diagnostics = mutableListOf<ProjectEditorDiagnostic>()
    val stack = ArrayDeque<Pair<String, Int>>()
    val lines = content.lines()
    lines.forEachIndexed { index, rawLine ->
        val lineNumber = index + 1
        val line = rawLine.substringBefore('#').trim()
        if (line.isBlank()) return@forEachIndexed
        val token = line.substringBefore(' ')
        when {
            "[[" in line || "]]" in line ->
                diagnostics += diagnosticAtLine(content, lineNumber, "手机常见的 /system/bin/sh 是 POSIX shell，不支持 `[[ ... ]]`，请改用 `[ ... ]`")
            Regex("""^\s*function\b""").containsMatchIn(rawLine) ->
                diagnostics += diagnosticAtLine(content, lineNumber, "POSIX shell 不支持 `function` 关键字，请直接写 `name()`")
            Regex("""(^|[\s;])source\s+""").containsMatchIn(line) ->
                diagnostics += diagnosticAtLine(content, lineNumber, "POSIX shell 不支持 `source`，请改用 `.`")
            "<<<" in line ->
                diagnostics += diagnosticAtLine(content, lineNumber, "POSIX shell 不支持 here-string `<<<`")
            "<(" in line || ">(" in line ->
                diagnostics += diagnosticAtLine(content, lineNumber, "POSIX shell 不支持进程替换 `<(...)` / `>(...)`")
            Regex("""(^|[\s;])(local|declare|typeset|select|coproc|mapfile|readarray|shopt|bind)\b""").containsMatchIn(line) ->
                diagnostics += diagnosticAtLine(content, lineNumber, "当前语法更像 Bash 扩展，基础 POSIX shell 里通常不可用")
            Regex("""\[[^]]*==[^]]*]""").containsMatchIn(line) ->
                diagnostics += diagnosticAtLine(content, lineNumber, "POSIX `[` 测试里建议使用单个 `=`，不要用 `==`")
            "=~" in line ->
                diagnostics += diagnosticAtLine(content, lineNumber, "POSIX shell 不支持 `=~` 正则比较")
            Regex("""\b[A-Za-z_][A-Za-z0-9_]*\s*=\s*\(""").containsMatchIn(line) ->
                diagnostics += diagnosticAtLine(content, lineNumber, "POSIX shell 不支持 Bash 数组字面量 `name=(...)`")
            Regex("""\{[^{}]*\.\.[^{}]*\}""").containsMatchIn(line) ->
                diagnostics += diagnosticAtLine(content, lineNumber, "POSIX shell 不支持 Bash 花括号展开 `{1..3}`")
        }
        when {
            line.startsWith("if ") && !Regex("""(^|[;\s])then($|[\s;])""").containsMatchIn(line) -> {
                val nextLine = nextNonEmptyLineIndex(lines, index + 1)?.let { lines[it].trim() }
                if (nextLine != "then") {
                    diagnostics += diagnosticAtLine(content, lineNumber, "POSIX if 语句需要 `then`")
                }
            }
            (line.startsWith("for ") || line.startsWith("while ") || line.startsWith("until ")) &&
                !Regex("""(^|[;\s])do($|[\s;])""").containsMatchIn(line) -> {
                val nextLine = nextNonEmptyLineIndex(lines, index + 1)?.let { lines[it].trim() }
                if (nextLine != "do") {
                    diagnostics += diagnosticAtLine(content, lineNumber, "POSIX 循环语句需要 `do`")
                }
            }
            line.startsWith("case ") && !Regex("""\bin\b""").containsMatchIn(line) -> {
                diagnostics += diagnosticAtLine(content, lineNumber, "POSIX case 语句通常需要 `in`")
            }
        }
        when {
            line.startsWith("if ") -> stack.addLast("if" to lineNumber)
            line.startsWith("for ") || line.startsWith("while ") || line.startsWith("until ") -> stack.addLast("do" to lineNumber)
            line.startsWith("case ") -> stack.addLast("case" to lineNumber)
            token == "fi" -> consumeShellCloser(content, stack, diagnostics, "if", "fi", lineNumber)
            token == "done" -> consumeShellCloser(content, stack, diagnostics, "do", "done", lineNumber)
            token == "esac" -> consumeShellCloser(content, stack, diagnostics, "case", "esac", lineNumber)
        }
    }
    stack.forEach { (block, lineNumber) ->
        val closer = when (block) {
            "if" -> "fi"
            "do" -> "done"
            else -> "esac"
        }
        diagnostics += diagnosticAtLine(content, lineNumber, "Shell 代码块缺少结束标记 `$closer`")
    }
    return diagnostics
}

private fun detectCppDiagnostics(content: String): List<ProjectEditorDiagnostic> {
    val diagnostics = mutableListOf<ProjectEditorDiagnostic>()
    val lines = content.lines()
    val preprocessorStack = ArrayDeque<Pair<String, Int>>()
    val classLikeStack = ArrayDeque<ProjectCppClassLikeBlock>()
    var braceDepth = 0

    lines.forEachIndexed { index, rawLine ->
        val lineNumber = index + 1
        val trimmed = rawLine.trim()
        if (trimmed.isBlank()) {
            braceDepth += rawLine.count { it == '{' } - rawLine.count { it == '}' }
            return@forEachIndexed
        }

        handleCppPreprocessorLine(
            content = content,
            rawLine = rawLine,
            lineNumber = lineNumber,
            stack = preprocessorStack,
            diagnostics = diagnostics
        )

        if (Regex("""^\s*(public|private|protected)\s*$""").matches(rawLine)) {
            diagnostics += diagnosticAtLine(content, lineNumber, "C++ 访问控制关键字后面通常需要冒号")
        }

        if (looksLikeCppMissingSemicolon(trimmed)) {
            diagnostics += diagnosticAtLine(content, lineNumber, "这行看起来缺少分号 `;`")
        }

        Regex("""\b(class|struct|enum)\b[^;{]*\{""")
            .findAll(rawLine)
            .forEach { match ->
                classLikeStack.addLast(
                    ProjectCppClassLikeBlock(
                        kind = match.groupValues[1],
                        startLine = lineNumber,
                        expectedBraceDepth = braceDepth + 1
                    )
                )
            }

        var closeCount = rawLine.count { it == '}' }
        while (closeCount > 0 && classLikeStack.isNotEmpty()) {
            val top = classLikeStack.lastOrNull() ?: break
            if (top.expectedBraceDepth == braceDepth) {
                val trailing = trimmed.substringAfterLast('}', "")
                if (!trailing.trimStart().startsWith(";")) {
                    diagnostics += diagnosticAtLine(content, lineNumber, "${top.kind} 定义结束后的 `}` 后面通常需要分号 `;`")
                }
                classLikeStack.removeLast()
            }
            closeCount--
        }

        braceDepth += rawLine.count { it == '{' } - rawLine.count { it == '}' }
    }

    preprocessorStack.forEach { (token, lineNumber) ->
        diagnostics += diagnosticAtLine(content, lineNumber, "预处理块 `$token` 缺少对应的 `#endif`")
    }
    return diagnostics
}

private fun handleCppPreprocessorLine(
    content: String,
    rawLine: String,
    lineNumber: Int,
    stack: ArrayDeque<Pair<String, Int>>,
    diagnostics: MutableList<ProjectEditorDiagnostic>
) {
    val trimmed = rawLine.trim()
    if (!trimmed.startsWith("#")) return

    if (Regex("""^\s*#\s*include\b""").containsMatchIn(rawLine) &&
        !Regex("""^\s*#\s*include\s*(<[^>]+>|"[^"]+")\s*$""").matches(rawLine)
    ) {
        diagnostics += diagnosticAtLine(content, lineNumber, "#include 后面应使用 `<...>` 或 `\"...\"`")
    }

    when {
        Regex("""^\s*#\s*(if|ifdef|ifndef)\b""").containsMatchIn(rawLine) -> {
            val token = Regex("""^\s*#\s*(if|ifdef|ifndef)\b""")
                .find(rawLine)
                ?.groupValues
                ?.getOrNull(1)
                .orEmpty()
            stack.addLast(token to lineNumber)
        }
        Regex("""^\s*#\s*endif\b""").containsMatchIn(rawLine) -> {
            if (stack.isEmpty()) {
                diagnostics += diagnosticAtLine(content, lineNumber, "#endif 没有匹配的 #if / #ifdef / #ifndef")
            } else {
                stack.removeLast()
            }
        }
        Regex("""^\s*#\s*(else|elif)\b""").containsMatchIn(rawLine) -> {
            if (stack.isEmpty()) {
                diagnostics += diagnosticAtLine(content, lineNumber, "#else / #elif 没有匹配的条件编译开始指令")
            }
        }
    }
}

private fun looksLikeCppMissingSemicolon(line: String): Boolean {
    if (line.startsWith("#")) return false
    if (line.endsWith(";") || line.endsWith("{") || line.endsWith("}") || line.endsWith(":") ||
        line.endsWith(",") || line.endsWith("\\")
    ) return false
    if (line.startsWith("//") || line.startsWith("/*") || line.startsWith("*")) return false
    if (Regex("""^(if|else|for|while|switch|case|default|do|try|catch|namespace|class|struct|enum|template)\b""").containsMatchIn(line)) {
        return false
    }
    if (Regex("""^(return|break|continue|throw|using\s+namespace|typedef)\b""").containsMatchIn(line)) {
        return true
    }
    if (Regex("""^[A-Za-z_][\w:<>~*&\s]*\([^;{}]*\)$""").matches(line) &&
        !Regex("""^(if|for|while|switch|catch)\b""").containsMatchIn(line)
    ) {
        return true
    }
    if (Regex("""^[A-Za-z_][\w:<>~*&\s]+\s+[A-Za-z_]\w*(\s*=\s*.+)?$""").matches(line) &&
        !line.contains(" operator ")
    ) {
        return true
    }
    return false
}

private fun consumeShellCloser(
    content: String,
    stack: ArrayDeque<Pair<String, Int>>,
    diagnostics: MutableList<ProjectEditorDiagnostic>,
    expected: String,
    closer: String,
    lineNumber: Int
) {
    val top = stack.removeLastOrNull()
    if (top == null || top.first != expected) {
        diagnostics += diagnosticAtLine(content, lineNumber, "Shell 结束标记 `$closer` 没有对应的开始块")
    }
}

private fun detectTomlDiagnostics(content: String): List<ProjectEditorDiagnostic> {
    val diagnostics = mutableListOf<ProjectEditorDiagnostic>()
    content.lines().forEachIndexed { index, rawLine ->
        val line = rawLine.trim()
        if (line.isBlank() || line.startsWith("#")) return@forEachIndexed
        if (line.startsWith("[") || line.startsWith("[[")) {
            if (!(line.endsWith("]") || line.endsWith("]]"))) {
                diagnostics += diagnosticAtLine(content, index + 1, "TOML 节名缺少闭合方括号")
            }
            return@forEachIndexed
        }
        if (!line.contains('=')) {
            diagnostics += diagnosticAtLine(content, index + 1, "TOML 键值行缺少 `=`")
        }
    }
    return diagnostics
}

private fun detectIniLikeDiagnostics(content: String): List<ProjectEditorDiagnostic> {
    val diagnostics = mutableListOf<ProjectEditorDiagnostic>()
    content.lines().forEachIndexed { index, rawLine ->
        val line = rawLine.trim()
        if (line.isBlank() || line.startsWith("#") || line.startsWith(";")) return@forEachIndexed
        if (line.startsWith("[") || line.endsWith("]")) {
            if (!(line.startsWith("[") && line.endsWith("]"))) {
                diagnostics += diagnosticAtLine(content, index + 1, "配置节标题需要成对的方括号")
            }
            return@forEachIndexed
        }
        if (!line.contains('=') && !line.contains(':')) {
            diagnostics += diagnosticAtLine(content, index + 1, "配置项缺少 `=` 或 `:`")
        }
    }
    return diagnostics
}

private fun detectYamlDiagnostics(content: String): List<ProjectEditorDiagnostic> {
    val diagnostics = mutableListOf<ProjectEditorDiagnostic>()
    content.lines().forEachIndexed { index, rawLine ->
        val line = rawLine.trimEnd()
        val trimmed = line.trim()
        if (trimmed.isBlank() || trimmed.startsWith("#")) return@forEachIndexed
        val leading = rawLine.takeWhile { it == ' ' || it == '\t' }
        if (leading.contains('\t')) {
            diagnostics += diagnosticAtLine(content, index + 1, "YAML 缩进不要使用 Tab")
        }
        val isListItem = trimmed.startsWith("- ")
        val looksLikeScalar = trimmed.startsWith("|") || trimmed.startsWith(">") || trimmed == "---" || trimmed == "..."
        if (!isListItem && !looksLikeScalar && !trimmed.contains(":")) {
            diagnostics += diagnosticAtLine(content, index + 1, "YAML 映射项通常需要 `key:` 形式")
        }
    }
    return diagnostics
}

private fun detectMarkupDiagnostics(content: String): List<ProjectEditorDiagnostic> {
    val diagnostics = mutableListOf<ProjectEditorDiagnostic>()
    val tagRegex = Regex("""<\s*(/)?\s*([A-Za-z][A-Za-z0-9:_-]*)([^>]*)>""")
    val stack = ArrayDeque<Pair<String, Int>>()
    val voidTags = setOf("br", "hr", "img", "input", "meta", "link", "area", "base", "col", "embed", "source", "track", "wbr")
    if (content.contains("<!--") && !content.contains("-->")) {
        diagnostics += diagnosticAtOffset(content, content.indexOf("<!--"), "HTML/XML 注释没有闭合")
    }
    tagRegex.findAll(content).forEach { match ->
        val isClosing = !match.groupValues[1].isNullOrBlank()
        val tag = match.groupValues[2].lowercase(Locale.getDefault())
        val fullText = match.value
        val offset = match.range.first
        val isSelfClosing = fullText.endsWith("/>") || tag in voidTags
        when {
            isClosing -> {
                val top = stack.removeLastOrNull()
                if (top == null || top.first != tag) {
                    diagnostics += diagnosticAtOffset(content, offset, "标签 </$tag> 没有匹配的开始标签")
                }
            }
            !isSelfClosing -> stack.addLast(tag to offset)
        }
    }
    stack.forEach { (tag, offset) ->
        diagnostics += diagnosticAtOffset(content, offset, "标签 <$tag> 没有闭合")
    }
    return diagnostics
}

private fun detectCssDiagnostics(content: String): List<ProjectEditorDiagnostic> {
    val diagnostics = mutableListOf<ProjectEditorDiagnostic>()
    var braceDepth = 0
    content.lines().forEachIndexed { index, rawLine ->
        val line = rawLine.trim()
        val lineNumber = index + 1
        if (line.isBlank() || line.startsWith("/*") || line.startsWith("*") || line.startsWith("//")) {
            braceDepth += rawLine.count { it == '{' } - rawLine.count { it == '}' }
            return@forEachIndexed
        }
        val insideBlock = braceDepth > 0
        if (insideBlock && !line.endsWith("{") && !line.endsWith("}") && !line.startsWith("@")) {
            if (!line.contains(':')) {
                diagnostics += diagnosticAtLine(content, lineNumber, "CSS 属性行缺少冒号")
            }
        }
        braceDepth += rawLine.count { it == '{' } - rawLine.count { it == '}' }
    }
    return diagnostics
}

private fun detectSqlDiagnostics(content: String): List<ProjectEditorDiagnostic> {
    val diagnostics = mutableListOf<ProjectEditorDiagnostic>()
    val tokenRegex = Regex("""\b(case|end)\b""", RegexOption.IGNORE_CASE)
    val stack = ArrayDeque<Int>()
    content.lines().forEachIndexed { index, line ->
        tokenRegex.findAll(line).forEach { match ->
            when (match.value.lowercase(Locale.getDefault())) {
                "case" -> stack.addLast(index + 1)
                "end" -> if (stack.isEmpty()) {
                    diagnostics += diagnosticAtLine(content, index + 1, "SQL 的 END 没有匹配到 CASE")
                } else {
                    stack.removeLast()
                }
            }
        }
    }
    stack.forEach { lineNumber ->
        diagnostics += diagnosticAtLine(content, lineNumber, "SQL 的 CASE 缺少对应的 END")
    }
    return diagnostics
}

private fun detectMarkdownDiagnostics(content: String): List<ProjectEditorDiagnostic> {
    val diagnostics = mutableListOf<ProjectEditorDiagnostic>()
    val lines = content.lines()
    val fenceLines = lines.mapIndexedNotNull { index, line ->
        if (line.trimStart().startsWith("```")) index + 1 else null
    }
    if (fenceLines.size % 2 == 1) {
        diagnostics += diagnosticAtLine(content, fenceLines.last(), "Markdown 代码块围栏 ``` 没有闭合")
    }
    return diagnostics
}

private fun nextNonEmptyLineIndex(lines: List<String>, startIndex: Int): Int? {
    for (index in startIndex until lines.size) {
        if (lines[index].trim().isNotBlank()) return index
    }
    return null
}

private fun diagnosticAtOffset(
    content: String,
    offset: Int,
    message: String
): ProjectEditorDiagnostic {
    val safeOffset = offset.coerceIn(0, (content.length - 1).coerceAtLeast(0))
    val lineNumber = currentLineNumber(content, safeOffset)
    val endIndex = (safeOffset + 1).coerceAtMost(content.length)
    return ProjectEditorDiagnostic(
        lineNumber = lineNumber,
        startIndex = safeOffset,
        endIndex = endIndex.coerceAtLeast(safeOffset),
        message = message
    )
}

private fun diagnosticAtLine(
    content: String,
    lineNumber: Int,
    message: String
): ProjectEditorDiagnostic {
    return diagnosticAtOffset(content, lineStartOffset(content, lineNumber), message)
}

private fun matchingOpenBracket(close: Char): Char {
    return when (close) {
        ')' -> '('
        ']' -> '['
        '}' -> '{'
        else -> close
    }
}

private fun extractJsonDiagnosticOffset(message: String?): Int {
    val offsetText = Regex("""offset\s+(\d+)""")
        .find(message.orEmpty())
        ?.groupValues
        ?.getOrNull(1)
    return offsetText?.toIntOrNull() ?: 0
}
