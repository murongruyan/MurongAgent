package dev.reasonix.mobile.ui.project

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun ProjectCodeEditorPane(
    editorValue: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    language: String?,
    searchQuery: String,
    currentMatch: TextRange?,
    diagnostics: List<ProjectEditorDiagnostic>,
    verticalScrollState: androidx.compose.foundation.ScrollState,
    backgroundColor: Color,
    surfaceColor: Color,
    mutedTextColor: Color,
    modifier: Modifier = Modifier
) {
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface
    val visibleTextStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        lineHeight = 20.sp,
        color = onSurfaceColor
    )
    val annotatedText = remember(editorValue.text, language, searchQuery, currentMatch, diagnostics) {
        buildHighlightedEditorText(
            code = editorValue.text,
            language = language.orEmpty(),
            query = searchQuery,
            currentMatch = currentMatch,
            diagnostics = diagnostics
        )
    }
    val lineNumbersText = remember(editorValue.text, diagnostics) {
        buildEditorLineNumbersText(editorValue.text, diagnostics)
    }

    Row(modifier = modifier) {
        Box(
            modifier = Modifier
                .width(56.dp)
                .fillMaxHeight()
                .background(surfaceColor.copy(alpha = 0.18f))
        ) {
            Text(
                text = lineNumbersText,
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(verticalScrollState)
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                style = visibleTextStyle.copy(
                    color = mutedTextColor
                )
            )
        }
        Box(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(mutedTextColor.copy(alpha = 0.25f))
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        ) {
            Box(
                modifier = Modifier
                    .verticalScroll(verticalScrollState)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .defaultMinSize(minHeight = 420.dp)
            ) {
                Text(
                    text = annotatedText,
                    style = visibleTextStyle,
                    modifier = Modifier.fillMaxWidth()
                )
                BasicTextField(
                    value = editorValue,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .matchParentSize()
                        .heightIn(min = 420.dp),
                    textStyle = visibleTextStyle.copy(color = Color.Transparent),
                    cursorBrush = SolidColor(onSurfaceColor),
                    decorationBox = { innerTextField ->
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(backgroundColor.copy(alpha = 0.08f))
                        ) {
                            innerTextField()
                        }
                    }
                )
            }
        }
    }
}

private fun buildHighlightedEditorText(
    code: String,
    language: String,
    query: String,
    currentMatch: TextRange?,
    diagnostics: List<ProjectEditorDiagnostic>
): AnnotatedString {
    val safeCode = code.ifEmpty { " " }
    val builder = AnnotatedString.Builder(highlightSyntaxWithSearchQuery(safeCode, language, query))
    diagnostics.forEach { diagnostic ->
        val safeStart = diagnostic.startIndex.coerceIn(0, safeCode.length)
        val safeEnd = diagnostic.endIndex.coerceIn(safeStart, safeCode.length)
        if (safeEnd > safeStart) {
            builder.addStyle(
                SpanStyle(
                    background = Color(0x55FF5252),
                    textDecoration = TextDecoration.Underline
                ),
                start = safeStart,
                end = safeEnd
            )
        }
    }
    currentMatch?.let { range ->
        if (range.min >= 0 && range.max <= safeCode.length && range.max > range.min) {
            builder.addStyle(
                SpanStyle(
                    background = Color(0x88FFB300),
                    color = Color(0xFF111111),
                    fontWeight = FontWeight.Bold
                ),
                start = range.min,
                end = range.max
            )
        }
    }
    return builder.toAnnotatedString()
}

private fun buildEditorLineNumbersText(
    content: String,
    diagnostics: List<ProjectEditorDiagnostic>
): AnnotatedString {
    val lineCount = projectLineCount(content)
    val lineWidth = lineCount.toString().length.coerceAtLeast(2)
    val errorLines = diagnostics.map { it.lineNumber }.toSet()
    return buildAnnotatedString {
        for (lineNumber in 1..lineCount) {
            pushStyle(
                SpanStyle(
                    color = if (errorLines.contains(lineNumber)) Color(0xFFFF6B6B) else Color(0xFF6A9955),
                    fontWeight = if (errorLines.contains(lineNumber)) FontWeight.Bold else FontWeight.Normal
                )
            )
            append(lineNumber.toString().padStart(lineWidth, ' '))
            pop()
            if (lineNumber < lineCount) append('\n')
        }
    }
}
