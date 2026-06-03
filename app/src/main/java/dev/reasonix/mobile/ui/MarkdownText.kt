package dev.reasonix.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.regex.Pattern

/**
 * 轻量 Markdown 渲染器——专�?AI 助手聊天优化
 *
 * 支持:
 * - 代码�?(```lang ... ```)
 * - 行内代码 (`code`)
 * - 粗体 **text**
 * - 斜体 *text*
 * - 标题 # ~ ######
 * - 列表 - / * / 1.
 * - 引用 >
 * - 分隔�?---
 * - 链接 [text](url)
 */

private data class CodeBlock(
    val language: String,
    val code: String
)

/**
 * Markdown 文本渲染�? */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    fontSize: Int = 14
) {
    if (text.isBlank()) return

    val segments = parseMarkdown(text)
    val textColor = MaterialTheme.colorScheme.onSurface
    val surfaceColor = rememberReasonixSurfaceColor()
    val chromeColor = rememberReasonixChromeColor()
    val mutedTextColor = rememberReasonixMutedTextColor()
    val inlineCodeBackground = lerp(Color(0xFF2E2E3E), surfaceColor, 0.22f)
    val inlineCodeTextColor = Color(0xFFE4E6F6)
    val codeBlockBackground = lerp(Color(CODE_BG), surfaceColor, 0.18f)
    val codeBlockHeaderBackground = lerp(Color(CODE_LANG_BG), chromeColor, 0.24f)
    val codeBlockLanguageColor = lerp(Color(0xFF8A8AA0), mutedTextColor, 0.18f)
    val codeBlockTextColor = Color(0xFFD4D4D4)

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        for (segment in segments) {
            when (segment) {
                is MarkdownSegment.Paragraph -> {
                    val annotatedString = buildParagraphAnnotatedString(
                        text = segment.text,
                        baseColor = textColor,
                        baseFontSize = fontSize,
                        inlineCodeBackground = inlineCodeBackground,
                        inlineCodeTextColor = inlineCodeTextColor
                    )
                    Text(
                        text = annotatedString,
                        fontSize = fontSize.sp,
                        lineHeight = (fontSize + 6).sp,
                        color = textColor
                    )
                }
                is MarkdownSegment.Heading -> {
                    val headingSize = when (segment.level) {
                        1 -> (fontSize + 12).sp
                        2 -> (fontSize + 8).sp
                        3 -> (fontSize + 4).sp
                        else -> (fontSize + 2).sp
                    }
                    val annotatedString = buildParagraphAnnotatedString(
                        text = segment.text,
                        baseColor = textColor,
                        baseFontSize = fontSize + (4 - segment.level) * 2,
                        inlineCodeBackground = inlineCodeBackground,
                        inlineCodeTextColor = inlineCodeTextColor
                    )
                    Text(
                        text = annotatedString,
                        fontSize = headingSize,
                        fontWeight = FontWeight.Bold,
                        color = textColor,
                        lineHeight = headingSize * 1.3f
                    )
                }
                is MarkdownSegment.CodeBlock -> {
                    CodeBlockView(
                        block = segment,
                        backgroundColor = codeBlockBackground,
                        languageBarColor = codeBlockHeaderBackground,
                        languageTextColor = codeBlockLanguageColor,
                        codeTextColor = codeBlockTextColor
                    )
                }
                is MarkdownSegment.UnorderedListItem -> {
                    Row(modifier = Modifier.padding(start = 8.dp)) {
                        Text("- ", fontSize = fontSize.sp, color = textColor)
                        val annotatedString = buildParagraphAnnotatedString(
                            text = segment.text,
                            baseColor = textColor,
                            baseFontSize = fontSize,
                            inlineCodeBackground = inlineCodeBackground,
                            inlineCodeTextColor = inlineCodeTextColor
                        )
                        Text(
                            text = annotatedString,
                            fontSize = fontSize.sp,
                            lineHeight = (fontSize + 6).sp,
                            color = textColor
                        )
                    }
                }
                is MarkdownSegment.OrderedListItem -> {
                    Row(modifier = Modifier.padding(start = 8.dp)) {
                        Text("${segment.number}.  ", fontSize = fontSize.sp, color = textColor)
                        val annotatedString = buildParagraphAnnotatedString(
                            text = segment.text,
                            baseColor = textColor,
                            baseFontSize = fontSize,
                            inlineCodeBackground = inlineCodeBackground,
                            inlineCodeTextColor = inlineCodeTextColor
                        )
                        Text(
                            text = annotatedString,
                            fontSize = fontSize.sp,
                            lineHeight = (fontSize + 6).sp,
                            color = textColor
                        )
                    }
                }
                is MarkdownSegment.Blockquote -> {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(30.dp)  // will be overridden by content
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        val annotatedString = buildParagraphAnnotatedString(
                            text = segment.text,
                            baseColor = textColor.copy(alpha = 0.8f),
                            baseFontSize = fontSize,
                            inlineCodeBackground = inlineCodeBackground,
                            inlineCodeTextColor = inlineCodeTextColor
                        )
                        Text(
                            text = annotatedString,
                            fontSize = fontSize.sp,
                            color = textColor.copy(alpha = 0.8f),
                            lineHeight = (fontSize + 6).sp
                        )
                    }
                }
                is MarkdownSegment.HorizontalRule -> {
                    HorizontalDividerView()
                }
                is MarkdownSegment.Empty -> {
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
        }
    }
}

// ─── Code Block View ────────────────────────────────────────────

@Composable
private fun CodeBlockView(
    block: MarkdownSegment.CodeBlock,
    backgroundColor: Color,
    languageBarColor: Color,
    languageTextColor: Color,
    codeTextColor: Color
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor)
    ) {
        // Language tag bar
        if (block.language.isNotBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(languageBarColor)
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text(
                    text = block.language,
                    color = languageTextColor,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // Code content with syntax highlighting
        val highlighted = highlightSyntax(block.code, block.language)
        val scrollState = rememberScrollState()
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scrollState)
                .padding(12.dp)
        ) {
            Text(
                text = highlighted,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = codeTextColor
            )
        }
    }
}

@Composable
private fun HorizontalDividerView() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
    )
}

// ─── Inline Markdown Parser ─────────────────────────────────────

private fun buildParagraphAnnotatedString(
    text: String,
    baseColor: Color,
    baseFontSize: Int,
    inlineCodeBackground: Color,
    inlineCodeTextColor: Color
): AnnotatedString {
    val builder = AnnotatedString.Builder()

    // Parse inline elements: `code`, **bold**, *italic*, [links](url)
    val pattern = Pattern.compile(
        "(`[^`]+`)|" +          // inline code
        "(\\*\\*[^*]+\\*\\*)|" +  // bold **text**
        "(?<!\\*)\\*[^*]+\\*(?!\\*)|" + // italic *text* (not **)
        "(\\[([^]]+)]\\(([^)]+)\\))"   // [text](url)
    )
    val matcher = pattern.matcher(text)

    var lastEnd = 0
    while (matcher.find()) {
        // Text before this match
        if (matcher.start() > lastEnd) {
            builder.append(text.substring(lastEnd, matcher.start()))
        }

        when {
            // Inline code
            matcher.group(1) != null -> {
                val code = matcher.group(1)?.removeSurrounding("`").orEmpty()
                builder.pushStyle(SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    background = inlineCodeBackground,
                    color = inlineCodeTextColor,
                    fontSize = (baseFontSize - 1).sp
                ))
                builder.append(code)
                builder.pop()
            }
            // Bold
            matcher.group(2) != null -> {
                val boldText = matcher.group(2)?.removeSurrounding("**").orEmpty()
                builder.pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                builder.append(boldText)
                builder.pop()
            }
            // Italic
            matcher.group(3) != null -> {
                val italicText = matcher.group(3)?.removeSurrounding("*").orEmpty()
                builder.pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                builder.append(italicText)
                builder.pop()
            }
            // Link
            matcher.group(4) != null -> {
                val linkText = matcher.group(5).orEmpty()
                builder.pushStyle(SpanStyle(
                    color = Color(0xFF7C8BFF),
                    textDecoration = TextDecoration.Underline
                ))
                builder.append(linkText)
                builder.pop()
            }
        }
        lastEnd = matcher.end()
    }

    // Remaining text
    if (lastEnd < text.length) {
        builder.append(text.substring(lastEnd))
    }

    return builder.toAnnotatedString()
}

// ─── Markdown Segment Tree ─────────────────────────────────────

private sealed class MarkdownSegment {
    data class Paragraph(val text: String) : MarkdownSegment()
    data class Heading(val level: Int, val text: String) : MarkdownSegment()
    data class CodeBlock(val language: String, val code: String) : MarkdownSegment()
    data class UnorderedListItem(val text: String) : MarkdownSegment()
    data class OrderedListItem(val number: Int, val text: String) : MarkdownSegment()
    data class Blockquote(val text: String) : MarkdownSegment()
    data object HorizontalRule : MarkdownSegment()
    data object Empty : MarkdownSegment()
}

/**
 * �?Markdown 文本解析�?Segment 列表
 */
private fun parseMarkdown(text: String): List<MarkdownSegment> {
    val segments = mutableListOf<MarkdownSegment>()
    val lines = text.split("\n")
    var i = 0

    while (i < lines.size) {
        val line = lines[i]

        when {
            // Code block
            line.trimStart().startsWith("```") -> {
                val language = line.trimStart().removePrefix("```").trim()
                val codeLines = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    codeLines.add(lines[i])
                    i++
                }
                // Skip closing ```
                if (i < lines.size) i++
                segments.add(MarkdownSegment.CodeBlock(
                    language = language,
                    code = codeLines.joinToString("\n")
                ))
            }
            // Heading
            line.matches(Regex("^#{1,6} .+")) -> {
                val level = line.takeWhile { it == '#' }.length
                val content = line.drop(level).trim()
                segments.add(MarkdownSegment.Heading(level, content))
                i++
            }
            // Horizontal rule
            line.matches(Regex("^[-*_]{3,}$")) && line.all { it == '-' || it == '*' || it == '_' } -> {
                segments.add(MarkdownSegment.HorizontalRule)
                i++
            }
            // Blockquote
            line.trimStart().startsWith("> ") -> {
                val content = line.trimStart().removePrefix("> ").trim()
                segments.add(MarkdownSegment.Blockquote(content))
                i++
            }
            // Unordered list
            line.trimStart().matches(Regex("^[-*+] .+")) -> {
                val content = line.trimStart().removePrefix("- ")
                    .removePrefix("* ").removePrefix("+ ").trim()
                segments.add(MarkdownSegment.UnorderedListItem(content))
                i++
            }
            // Ordered list
            line.trimStart().matches(Regex("^\\d+\\. .+")) -> {
                val match = Regex("^(\\d+)\\. (.+)$").find(line.trimStart())
                if (match != null) {
                    segments.add(MarkdownSegment.OrderedListItem(
                        number = match.groupValues[1].toInt(),
                        text = match.groupValues[2].trim()
                    ))
                } else {
                    segments.add(MarkdownSegment.Paragraph(line))
                }
                i++
            }
            // Empty line
            line.isBlank() -> {
                // 合并多个空行
                segments.add(MarkdownSegment.Empty)
                while (i < lines.size && lines[i].isBlank()) i++
            }
            // Regular paragraph
            else -> {
                val paraLines = mutableListOf(line)
                i++
                // Collect consecutive non-blank, non-special lines
                while (i < lines.size) {
                    val next = lines[i]
                    if (next.isBlank() ||
                        next.trimStart().startsWith("```") ||
                        next.trimStart().startsWith("-#") ||
                        next.trimStart().matches(Regex("^#{1,6} ")) ||
                        next.trimStart().matches(Regex("^[-*+] ")) ||
                        next.trimStart().matches(Regex("^\\d+\\. ")) ||
                        next.trimStart().startsWith("> ")
                    ) break
                    paraLines.add(next)
                    i++
                }
                segments.add(MarkdownSegment.Paragraph(paraLines.joinToString("\n")))
            }
        }
    }

    return segments
}

// ─── Syntax Highlighting ────────────────────────────────────────

/**
 * 简单的语法高亮——将代码文本转为 AnnotatedString
 */
@Composable
/**
 * 对外暴露�?Markdown 解析——用于估算文本显示区�? */
fun splitMarkdownForEstimate(text: String): Int {
    return parseMarkdown(text).size
}
