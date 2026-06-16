package com.murong.agent.ui.project

import android.graphics.Typeface
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.viewinterop.AndroidView
import com.murong.agent.ui.applyMurongEditorLanguage
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.EditorFocusChangeEvent
import io.github.rosemoe.sora.event.PublishSearchResultEvent
import io.github.rosemoe.sora.event.ScrollEvent
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticDetail
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticRegion
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticsContainer
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.EditorSearcher
import io.github.rosemoe.sora.widget.component.EditorAutoCompletion
import io.github.rosemoe.sora.widget.component.Magnifier
import io.github.rosemoe.sora.widget.getComponent
import io.github.rosemoe.sora.widget.subscribeEvent
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

internal enum class ProjectEditorSearchAction {
    NEXT,
    PREVIOUS,
    REPLACE_CURRENT,
    REPLACE_ALL
}

internal data class ProjectEditorSearchRequest(
    val token: Int = 0,
    val action: ProjectEditorSearchAction? = null
)

internal data class ProjectEditorSearchState(
    val currentMatch: TextRange? = null
)

@Composable
internal fun ProjectCodeEditorPane(
    editorValue: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    language: String?,
    wordWrapEnabled: Boolean,
    searchQuery: String,
    searchRequest: ProjectEditorSearchRequest = ProjectEditorSearchRequest(),
    replaceQuery: String = "",
    onSearchStateChange: (ProjectEditorSearchState) -> Unit = {},
    diagnostics: List<ProjectEditorDiagnostic>,
    initialScrollOffset: Int,
    onScrollOffsetChange: (Int) -> Unit,
    backgroundColor: Color,
    surfaceColor: Color,
    focusClearSignal: Int = 0,
    onEditorFocusChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val latestValueChange by rememberUpdatedState(onValueChange)
    val latestFocusChange by rememberUpdatedState(onEditorFocusChanged)
    val latestScrollOffsetChange by rememberUpdatedState(onScrollOffsetChange)
    val latestEditorValue by rememberUpdatedState(editorValue)
    val latestSearchStateChange by rememberUpdatedState(onSearchStateChange)
    val suppressExternalSync = remember { AtomicBoolean(false) }
    val editor = remember {
        CodeEditor(context).apply {
            setText(Content(editorValue.text))
            setBackgroundColor(backgroundColor.copy(alpha = 0.08f).toArgb())
            setTextSize(13f)
            setTabWidth(4)
            typefaceText = Typeface.MONOSPACE
            typefaceLineNumber = Typeface.MONOSPACE
            isWordwrap = wordWrapEnabled
            props.overScrollEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setDividerWidth(1f)
            setDividerMargin(12f)
            setLineSpacing(2f, 1.1f)
            setHighlightCurrentLine(true)
            setHighlightCurrentBlock(true)
            props.stickyScroll = true
            props.stickyScrollMaxLines = 3
            props.symbolPairAutoCompletion = true
            props.autoIndent = true
            props.deleteMultiSpaces = -1
            props.highlightMatchingDelimiters = true
            props.boldMatchingDelimiters = true
            props.awareScrollbarWhenAdjust = true
            nonPrintablePaintingFlags =
                CodeEditor.FLAG_DRAW_WHITESPACE_LEADING or
                    CodeEditor.FLAG_DRAW_LINE_SEPARATOR or
                    CodeEditor.FLAG_DRAW_WHITESPACE_IN_SELECTION or
                    CodeEditor.FLAG_DRAW_SOFT_WRAP
            searcher.replaceOptions = EditorSearcher.ReplaceOptions(true)
            searcher.setEnsureOccurrenceVisible(true)
            getComponent<EditorAutoCompletion>().setEnabledAnimation(true)
            getComponent(Magnifier::class.java).isEnabled = true
        }
    }

    DisposableEffect(editor) {
        val contentReceipt = editor.subscribeEvent<ContentChangeEvent> { _, _ ->
            if (suppressExternalSync.get()) return@subscribeEvent
            val newText = editor.text.toString()
            val currentValue = latestEditorValue
            val nextValue = currentValue.copy(
                text = newText,
                selection = TextRange(editor.cursor.left, editor.cursor.right)
            )
            if (nextValue != currentValue) {
                latestValueChange(nextValue)
            }
        }
        val selectionReceipt = editor.subscribeEvent<SelectionChangeEvent> { event, _ ->
            if (suppressExternalSync.get()) return@subscribeEvent
            val currentText = editor.text.toString()
            val currentValue = latestEditorValue
            val selection = TextRange(event.left.index, event.right.index)
            val nextValue = currentValue.copy(
                text = currentText,
                selection = selection
            )
            if (nextValue != currentValue) {
                latestValueChange(nextValue)
            }
        }
        val scrollReceipt = editor.subscribeEvent<ScrollEvent> { event, _ ->
            if (suppressExternalSync.get()) return@subscribeEvent
            latestScrollOffsetChange(event.endY.coerceAtLeast(0))
        }
        val searchReceipt = editor.subscribeEvent<PublishSearchResultEvent> { _, _ ->
            latestSearchStateChange(editor.readSearchState())
        }
        val focusReceipt = editor.subscribeEvent<EditorFocusChangeEvent> { event, _ ->
            latestFocusChange(event.isGainFocus)
        }
        editor.setOnFocusChangeListener { _, hasFocus ->
            latestFocusChange(hasFocus)
        }
        onDispose {
            contentReceipt.unsubscribe()
            selectionReceipt.unsubscribe()
            scrollReceipt.unsubscribe()
            searchReceipt.unsubscribe()
            focusReceipt.unsubscribe()
            editor.onFocusChangeListener = null
        }
    }

    LaunchedEffect(editorValue.text) {
        val currentText = editor.text.toString()
        if (currentText != editorValue.text) {
            suppressExternalSync.set(true)
            editor.setText(Content(editorValue.text))
            editor.syncSelection(editorValue.selection)
            suppressExternalSync.set(false)
        }
    }

    LaunchedEffect(editorValue.selection, editorValue.text) {
        val currentSelection = TextRange(editor.cursor.left, editor.cursor.right)
        if (currentSelection != editorValue.selection) {
            suppressExternalSync.set(true)
            editor.syncSelection(editorValue.selection)
            suppressExternalSync.set(false)
        }
    }

    LaunchedEffect(initialScrollOffset) {
        val targetOffset = initialScrollOffset.coerceAtLeast(0)
        if (abs(editor.offsetY - targetOffset) > 1) {
            suppressExternalSync.set(true)
            editor.scroller.startScroll(
                editor.offsetX,
                editor.offsetY,
                0,
                targetOffset - editor.offsetY,
                0
            )
            editor.scroller.abortAnimation()
            editor.postInvalidate()
            suppressExternalSync.set(false)
        }
    }

    LaunchedEffect(language, backgroundColor) {
        editor.applyMurongEditorLanguage(
            context = context.applicationContext,
            language = language,
            darkTheme = backgroundColor.luminance() < 0.5f
        )
    }

    LaunchedEffect(wordWrapEnabled) {
        editor.isWordwrap = wordWrapEnabled
    }

    LaunchedEffect(diagnostics, editorValue.text) {
        editor.applyDiagnostics(diagnostics, editorValue.text.length)
    }

    LaunchedEffect(searchQuery) {
        val query = searchQuery.trim()
        if (query.isBlank()) {
            if (editor.searcher.hasQuery()) {
                editor.searcher.stopSearch()
            } else {
                latestSearchStateChange(ProjectEditorSearchState())
            }
            return@LaunchedEffect
        }
        runCatching {
            editor.searcher.search(query, EditorSearcher.SearchOptions(false, false))
        }.onFailure {
            latestSearchStateChange(ProjectEditorSearchState())
        }
    }

    LaunchedEffect(searchRequest, replaceQuery, searchQuery) {
        val action = searchRequest.action ?: return@LaunchedEffect
        if (searchRequest.token <= 0) return@LaunchedEffect
        val query = searchQuery.trim()
        if (query.isBlank()) return@LaunchedEffect
        runCatching {
            when (action) {
                ProjectEditorSearchAction.NEXT -> editor.searcher.gotoNext()
                ProjectEditorSearchAction.PREVIOUS -> editor.searcher.gotoPrevious()
                ProjectEditorSearchAction.REPLACE_CURRENT -> editor.searcher.replaceCurrentMatch(replaceQuery)
                ProjectEditorSearchAction.REPLACE_ALL -> editor.searcher.replaceAll(replaceQuery)
            }
        }
    }

    LaunchedEffect(focusClearSignal) {
        if (focusClearSignal > 0) {
            editor.hideSoftInput()
            editor.clearFocus()
            latestFocusChange(false)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(surfaceColor.copy(alpha = 0.06f))
    ) {
        AndroidView(
            factory = { editor },
            modifier = Modifier.fillMaxSize(),
            update = {
                it.setBackgroundColor(backgroundColor.copy(alpha = 0.08f).toArgb())
                it.isWordwrap = wordWrapEnabled
            },
            onRelease = {
                it.release()
            }
        )
    }
}

private fun CodeEditor.readSearchState(): ProjectEditorSearchState {
    val searcher = searcher
    if (!searcher.hasQuery()) {
        return ProjectEditorSearchState()
    }
    val currentMatchIndex = searcher.currentMatchedPositionIndex
    val currentMatch = if (cursor.isSelected && currentMatchIndex >= 0) {
        TextRange(cursor.left, cursor.right)
    } else {
        null
    }
    return ProjectEditorSearchState(currentMatch = currentMatch)
}

private fun CodeEditor.applyDiagnostics(
    diagnostics: List<ProjectEditorDiagnostic>,
    textLength: Int
) {
    val container = DiagnosticsContainer()
    diagnostics.forEachIndexed { index, diagnostic ->
        val start = diagnostic.startIndex.coerceIn(0, textLength)
        val end = diagnostic.endIndex.coerceIn(start, textLength)
        if (end > start) {
            container.addDiagnostic(
                DiagnosticRegion(
                    start,
                    end,
                    DiagnosticRegion.SEVERITY_ERROR,
                    index.toLong(),
                    DiagnosticDetail(diagnostic.message, diagnostic.message)
                )
            )
        }
    }
    this.diagnostics = container
}

private fun CodeEditor.syncSelection(selection: TextRange) {
    val textLength = text.length
    val leftIndex = selection.min.coerceIn(0, textLength)
    val rightIndex = selection.max.coerceIn(0, textLength)
    val leftPosition = text.indexer.getCharPosition(leftIndex)
    val rightPosition = text.indexer.getCharPosition(rightIndex)
    if (leftIndex == rightIndex) {
        setSelection(
            leftPosition.line,
            leftPosition.column,
            false,
            SelectionChangeEvent.CAUSE_KEYBOARD_OR_CODE
        )
        ensurePositionVisible(leftPosition.line, leftPosition.column, true)
    } else {
        setSelectionRegion(
            leftPosition.line,
            leftPosition.column,
            rightPosition.line,
            rightPosition.column,
            false,
            SelectionChangeEvent.CAUSE_KEYBOARD_OR_CODE
        )
        ensurePositionVisible(leftPosition.line, leftPosition.column, true)
        ensurePositionVisible(rightPosition.line, rightPosition.column, true)
    }
}
