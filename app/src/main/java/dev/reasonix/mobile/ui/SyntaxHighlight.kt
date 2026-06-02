package dev.reasonix.mobile.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle

internal const val KEYWORD_BLUE = 0xFF569CD6
internal const val STRING_ORANGE = 0xFFCE9178
internal const val COMMENT_GREEN = 0xFF6A9955
internal const val TYPE_CYAN = 0xFF4EC9B0
internal const val NUMBER_LIGHT = 0xFFB5CEA8
internal const val CODE_BG = 0xFF1E1E1E
internal const val CODE_LANG_BG = 0xFF2D2D2D

fun highlightSyntax(code: String, language: String): AnnotatedString {
    val builder = AnnotatedString.Builder()

    val keywords = when (language.lowercase()) {
        "kotlin", "kt" -> listOf(
            "fun", "val", "var", "class", "object", "interface", "enum", "sealed",
            "data", "open", "abstract", "override", "private", "public", "protected",
            "internal", "import", "package", "if", "else", "when", "for", "while",
            "do", "return", "try", "catch", "finally", "throw", "suspend", "inline",
            "as", "is", "in", "typealias", "init", "companion", "null", "true", "false",
            "by", "lazy", "lateinit", "this", "super", "Lambda", "it"
        )
        "java" -> listOf(
            "public", "private", "protected", "class", "interface", "enum", "extends",
            "implements", "static", "final", "abstract", "synchronized", "volatile",
            "transient", "native", "strictfp", "if", "else", "for", "while", "do",
            "switch", "case", "break", "continue", "return", "try", "catch", "finally",
            "throw", "throws", "new", "this", "super", "import", "package", "null",
            "true", "false", "void", "int", "long", "double", "float", "boolean",
            "char", "byte", "short", "String"
        )
        "python", "py" -> listOf(
            "def", "class", "if", "elif", "else", "for", "while", "try", "except",
            "finally", "with", "as", "import", "from", "return", "yield", "lambda",
            "pass", "break", "continue", "raise", "and", "or", "not", "in", "is",
            "None", "True", "False", "self", "async", "await", "print"
        )
        "groovy", "gradle" -> listOf(
            "def", "class", "interface", "enum", "trait", "if", "else", "for", "while",
            "switch", "case", "break", "continue", "return", "try", "catch", "finally",
            "throw", "new", "this", "super", "import", "package", "null", "true", "false",
            "static", "final", "public", "private", "protected", "void", "as", "in"
        )
        "typescript", "ts", "javascript", "js", "tsx", "jsx" -> listOf(
            "function", "const", "let", "var", "class", "interface", "type", "enum",
            "extends", "implements", "import", "export", "from", "default", "async",
            "await", "if", "else", "for", "while", "do", "switch", "case", "break",
            "continue", "return", "try", "catch", "finally", "throw", "new", "this",
            "super", "null", "undefined", "true", "false", "typeof", "instanceof",
            "void", "any", "never", "unknown", "string", "number", "boolean"
        )
        "rust", "rs" -> listOf(
            "fn", "let", "mut", "const", "static", "struct", "enum", "trait", "impl",
            "pub", "use", "mod", "crate", "self", "super", "if", "else", "for", "while",
            "loop", "match", "return", "move", "async", "await", "unsafe", "ref",
            "true", "false", "Some", "None", "Ok", "Err"
        )
        "c", "cpp", "c++", "cxx", "h", "hpp" -> listOf(
            "int", "long", "double", "float", "char", "void", "bool", "auto", "const",
            "static", "extern", "volatile", "register", "signed", "unsigned", "short",
            "struct", "union", "enum", "typedef", "class", "namespace", "template",
            "public", "private", "protected", "virtual", "override", "friend",
            "if", "else", "for", "while", "do", "switch", "case", "break",
            "continue", "return", "goto", "try", "catch", "throw", "new", "delete",
            "this", "true", "false", "nullptr", "include", "define", "ifdef",
            "ifndef", "endif", "pragma"
        )
        "shell", "bash", "sh" -> listOf(
            "if", "then", "else", "elif", "fi", "for", "while", "do", "done",
            "case", "esac", "in", "function", "return", "exit", "export", "local",
            "source", ".", "echo", "read", "set", "unset", "trap", "exec"
        )
        "toml" -> listOf(
            "true", "false"
        )
        "ini", "conf", "cfg" -> emptyList()
        "sql" -> listOf(
            "select", "from", "where", "insert", "into", "update", "delete", "join",
            "left", "right", "inner", "outer", "on", "group", "by", "order", "having",
            "limit", "offset", "create", "alter", "drop", "table", "index", "view",
            "distinct", "as", "and", "or", "not", "null", "is", "in", "exists",
            "like", "between", "case", "when", "then", "else", "end", "union", "all",
            "values", "set", "primary", "key", "foreign", "references"
        )
        "css" -> listOf(
            "color", "background", "display", "position", "margin", "padding", "border",
            "width", "height", "font", "font-size", "font-weight", "line-height",
            "justify-content", "align-items", "flex", "grid", "absolute", "relative",
            "fixed", "block", "inline", "none", "auto", "solid", "important"
        )
        "html", "htm" -> listOf(
            "html", "head", "body", "div", "span", "script", "style", "link", "meta",
            "title", "main", "section", "article", "header", "footer", "nav", "aside",
            "button", "input", "form", "label", "img", "a", "ul", "ol", "li", "table",
            "tr", "td", "th", "h1", "h2", "h3", "h4", "h5", "h6", "class", "id", "href",
            "src", "alt", "type", "name", "content"
        )
        "json" -> emptyList()
        else -> emptyList()
    }

    var j = 0
    val len = code.length

    while (j < len) {
        when {
            (language.equals("html", ignoreCase = true) || language.equals("htm", ignoreCase = true)) &&
                j + 3 < len && code.startsWith("<!--", j) -> {
                val commentEnd = code.indexOf("-->", j + 4).let { if (it == -1) len else it + 3 }
                builder.pushStyle(SpanStyle(color = Color(COMMENT_GREEN)))
                builder.append(code.substring(j, commentEnd))
                builder.pop()
                j = commentEnd
            }
            language.equals("sql", ignoreCase = true) &&
                j + 1 < len && code[j] == '-' && code[j + 1] == '-' -> {
                val commentEnd = code.indexOf('\n', j).let { if (it == -1) len else it + 1 }
                builder.pushStyle(SpanStyle(color = Color(COMMENT_GREEN)))
                builder.append(code.substring(j, commentEnd))
                builder.pop()
                j = commentEnd
            }
            j + 1 < len && code[j] == '/' && code[j + 1] == '/' -> {
                val commentEnd = code.indexOf('\n', j).let { if (it == -1) len else it + 1 }
                builder.pushStyle(SpanStyle(color = Color(COMMENT_GREEN)))
                builder.append(code.substring(j, commentEnd))
                builder.pop()
                j = commentEnd
            }
            j + 1 < len && code[j] == '/' && code[j + 1] == '*' -> {
                val commentEnd = code.indexOf("*/", j + 2).let { if (it == -1) len else it + 2 }
                builder.pushStyle(SpanStyle(color = Color(COMMENT_GREEN)))
                builder.append(code.substring(j, commentEnd))
                builder.pop()
                j = commentEnd
            }
            (code[j] == '#') && (j == 0 || code[j - 1] == '\n') -> {
                val commentEnd = code.indexOf('\n', j).let { if (it == -1) len else it + 1 }
                builder.pushStyle(SpanStyle(color = Color(COMMENT_GREEN)))
                builder.append(code.substring(j, commentEnd))
                builder.pop()
                j = commentEnd
            }
            (language.equals("ini", ignoreCase = true) ||
                language.equals("conf", ignoreCase = true) ||
                language.equals("cfg", ignoreCase = true) ||
                language.equals("toml", ignoreCase = true)) &&
                code[j] == ';' && (j == 0 || code[j - 1] == '\n') -> {
                val commentEnd = code.indexOf('\n', j).let { if (it == -1) len else it + 1 }
                builder.pushStyle(SpanStyle(color = Color(COMMENT_GREEN)))
                builder.append(code.substring(j, commentEnd))
                builder.pop()
                j = commentEnd
            }
            code[j] == '"' -> {
                val strStart = j
                j++
                while (j < len && code[j] != '"') {
                    if (code[j] == '\\') j++
                    j++
                }
                if (j < len) j++
                builder.pushStyle(SpanStyle(color = Color(STRING_ORANGE)))
                builder.append(code.substring(strStart, j))
                builder.pop()
            }
            code[j] == '\'' -> {
                val strStart = j
                j++
                while (j < len && code[j] != '\'') {
                    if (code[j] == '\\') j++
                    j++
                }
                if (j < len) j++
                builder.pushStyle(SpanStyle(color = Color(STRING_ORANGE)))
                builder.append(code.substring(strStart, j))
                builder.pop()
            }
            code[j] == '`' -> {
                val strStart = j
                j++
                while (j < len && code[j] != '`') {
                    if (code[j] == '\\') j++
                    j++
                }
                if (j < len) j++
                builder.pushStyle(SpanStyle(color = Color(STRING_ORANGE)))
                builder.append(code.substring(strStart, j))
                builder.pop()
            }
            code[j].isDigit() && (j == 0 || !code[j - 1].isLetterOrDigit()) -> {
                val numStart = j
                while (
                    j < len && (code[j].isDigit() || code[j] == '.' || code[j] == 'x' || code[j] == 'X' ||
                        (code[j] in 'a'..'f') || (code[j] in 'A'..'F'))
                ) {
                    j++
                }
                builder.pushStyle(SpanStyle(color = Color(NUMBER_LIGHT)))
                builder.append(code.substring(numStart, j))
                builder.pop()
            }
            code[j].isLetter() || code[j] == '_' -> {
                val wordStart = j
                while (j < len && (code[j].isLetterOrDigit() || code[j] == '_')) j++
                val word = code.substring(wordStart, j)
                if (word in keywords) {
                    builder.pushStyle(SpanStyle(color = Color(KEYWORD_BLUE)))
                    builder.append(word)
                    builder.pop()
                } else {
                    builder.append(word)
                }
            }
            code[j] == '"' && (j == 0 || code[j - 1] == '{' || code[j - 1] == ',' || code[j - 1] == ' ') -> {
                val keyStart = j
                j++
                while (j < len && code[j] != '"') {
                    if (code[j] == '\\') j++
                    j++
                }
                if (j < len) j++
                var k = j
                while (k < len && code[k].isWhitespace()) k++
                if (k < len && code[k] == ':') {
                    builder.pushStyle(SpanStyle(color = Color(TYPE_CYAN)))
                    builder.append(code.substring(keyStart, j))
                    builder.pop()
                } else {
                    builder.pushStyle(SpanStyle(color = Color(STRING_ORANGE)))
                    builder.append(code.substring(keyStart, j))
                    builder.pop()
                }
            }
            else -> {
                builder.append(code[j])
                j++
            }
        }
    }

    return builder.toAnnotatedString()
}
