package com.murong.agent.ui

import android.content.Context
import android.graphics.Typeface
import android.view.View
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.dingyi222666.monarch.languages.CppLanguage
import io.github.dingyi222666.monarch.languages.GoLanguage
import io.github.dingyi222666.monarch.languages.RustLanguage
import io.github.dingyi222666.monarch.languages.ShellLanguage
import io.github.dingyi222666.monarch.languages.SqlLanguage
import io.github.dingyi222666.monarch.languages.TypescriptLanguage
import io.github.dingyi222666.monarch.languages.YamlLanguage
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.langs.monarch.MonarchColorScheme
import io.github.rosemoe.sora.langs.monarch.MonarchLanguage
import io.github.rosemoe.sora.langs.monarch.registry.MonarchGrammarRegistry
import io.github.rosemoe.sora.langs.monarch.registry.dsl.monarchLanguages
import io.github.rosemoe.sora.langs.monarch.registry.model.ThemeSource
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.EditorSearcher
import org.eclipse.tm4e.core.registry.IThemeSource
import java.util.concurrent.atomic.AtomicBoolean

private object MurongSoraTextMateRegistry {
    private val initialized = AtomicBoolean(false)

    fun ensureInitialized(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        FileProviderRegistry.getInstance().addFileProvider(AssetsFileResolver(context.assets))
        val themeRegistry = ThemeRegistry.getInstance()
        arrayOf("darcula", "ayu-dark", "quietlight", "solarized_dark").forEach { name ->
            val path = "textmate/$name.json"
            themeRegistry.loadTheme(
                ThemeModel(
                    IThemeSource.fromInputStream(
                        FileProviderRegistry.getInstance().tryGetInputStream(path),
                        path,
                        null
                    ),
                    name
                ).apply {
                    isDark = name != "quietlight"
                }
            )
        }
        GrammarRegistry.getInstance().loadGrammars("textmate/languages.json")
    }

    fun applyTheme(editor: CodeEditor, darkTheme: Boolean) {
        val themeName = if (darkTheme) "darcula" else "quietlight"
        val themeRegistry = ThemeRegistry.getInstance()
        themeRegistry.setTheme(themeName)
        editor.colorScheme = TextMateColorScheme.create(themeRegistry)
    }
}

private object MurongSoraMonarchRegistry {
    private val initialized = AtomicBoolean(false)
    private val available = AtomicBoolean(true)

    fun ensureInitialized(context: Context): Boolean {
        if (!available.get()) return false
        if (!initialized.compareAndSet(false, true)) return available.get()
        runCatching {
            io.github.rosemoe.sora.langs.monarch.registry.FileProviderRegistry.addProvider(
                io.github.rosemoe.sora.langs.monarch.registry.provider.AssetsFileResolver(context.assets)
            )
            arrayOf("darcula", "ayu-dark", "quietlight", "solarized_dark").forEach { name ->
                val path = "textmate/$name.json"
                io.github.rosemoe.sora.langs.monarch.registry.ThemeRegistry.loadTheme(
                    io.github.rosemoe.sora.langs.monarch.registry.model.ThemeModel(
                        ThemeSource(path, name)
                    ).apply {
                        isDark = name != "quietlight"
                    },
                    false
                )
            }
            io.github.rosemoe.sora.langs.monarch.registry.ThemeRegistry.setTheme("quietlight")
            MonarchGrammarRegistry.INSTANCE.loadGrammars(
                monarchLanguages {
                    language("cpp") {
                        monarchLanguage = CppLanguage
                        defaultScopeName()
                    }
                    language("go") {
                        monarchLanguage = GoLanguage
                        defaultScopeName()
                    }
                    language("rust") {
                        monarchLanguage = RustLanguage
                        defaultScopeName()
                    }
                    language("shell") {
                        monarchLanguage = ShellLanguage
                        defaultScopeName()
                    }
                    language("sql") {
                        monarchLanguage = SqlLanguage
                        defaultScopeName()
                    }
                    language("typescript") {
                        monarchLanguage = TypescriptLanguage
                        defaultScopeName()
                        languageConfiguration = "textmate/javascript/language-configuration.json"
                    }
                    language("yaml") {
                        monarchLanguage = YamlLanguage
                        defaultScopeName()
                    }
                }
            )
        }.onFailure {
            available.set(false)
        }
        return available.get()
    }

    fun applyTheme(editor: CodeEditor, darkTheme: Boolean) {
        val themeName = if (darkTheme) "darcula" else "quietlight"
        io.github.rosemoe.sora.langs.monarch.registry.ThemeRegistry.setTheme(themeName)
        editor.colorScheme =
            MonarchColorScheme.create(
                io.github.rosemoe.sora.langs.monarch.registry.ThemeRegistry.currentTheme
            )
    }
}

internal fun normalizeReasonixHighlightLanguage(language: String?): String? {
    val normalized = language?.trim()?.lowercase().orEmpty()
    if (normalized.isBlank()) return null
    return when (normalized) {
        "kt", "kts" -> "kotlin"
        "gradle", "groovy", "gvy" -> "java"
        "mjs", "cjs" -> "javascript"
        "ts", "mts", "cts" -> "typescript"
        "cc", "cxx", "c++" -> "cpp"
        "hh", "hxx", "h" -> "hpp"
        "c" -> "c"
        "rs" -> "rust"
        "shell", "bash", "zsh" -> "sh"
        "cmake" -> "sh"
        "yml" -> "yaml"
        "md", "mdown" -> "markdown"
        "jsonc", "geojson", "webmanifest" -> "json"
        "luau" -> "lua"
        "plist", "xaml", "svg" -> "xml"
        "ini", "conf", "cfg", "properties", "prop", "pro", "toml" -> "properties"
        else -> normalized
    }
}

internal fun CodeEditor.applyReasonixEditorLanguage(
    context: Context,
    language: String?,
    darkTheme: Boolean
) {
    val normalized = normalizeReasonixHighlightLanguage(language)
    val monarchScopeName = when (normalized) {
        "c", "cpp", "hpp" -> "source.cpp"
        "go" -> "source.go"
        "rust" -> "source.rust"
        "properties" -> "source.shell"
        "sh" -> "source.shell"
        "sql" -> "source.sql"
        "typescript", "tsx" -> "source.typescript"
        "yaml" -> "source.yaml"
        else -> null
    }
    if (monarchScopeName != null) {
        if (!MurongSoraMonarchRegistry.ensureInitialized(context)) {
            setEditorLanguage(EmptyLanguage())
            return
        }
        MurongSoraMonarchRegistry.applyTheme(editor = this, darkTheme = darkTheme)
        runCatching {
            val existingLanguage = editorLanguage
            val nextLanguage = if (existingLanguage is MonarchLanguage) {
                existingLanguage.updateLanguage(monarchScopeName)
                existingLanguage
            } else {
                MonarchLanguage.create(monarchScopeName, true)
            }
            setEditorLanguage(nextLanguage)
        }.onFailure {
            setEditorLanguage(EmptyLanguage())
        }
        return
    }

    val textMateScopeName = when (normalized) {
        "json" -> "source.json"
        "kotlin" -> "source.kotlin"
        "java" -> "source.java"
        "python" -> "source.python"
        "javascript", "jsx" -> "source.js"
        "lua" -> "source.lua"
        "html" -> "text.html.basic"
        "xml" -> "text.xml"
        "markdown" -> "text.html.markdown"
        else -> null
    }
    if (textMateScopeName == null) {
        if (editorLanguage !is EmptyLanguage) {
            setEditorLanguage(EmptyLanguage())
        }
        return
    }
    MurongSoraTextMateRegistry.ensureInitialized(context)
    MurongSoraTextMateRegistry.applyTheme(editor = this, darkTheme = darkTheme)
    runCatching {
        val existingLanguage = editorLanguage
        val nextLanguage = if (existingLanguage is TextMateLanguage) {
            existingLanguage.updateLanguage(textMateScopeName)
            existingLanguage
        } else {
            TextMateLanguage.create(textMateScopeName, true)
        }
        setEditorLanguage(nextLanguage)
    }.onFailure {
        setEditorLanguage(EmptyLanguage())
    }
}

@Composable
internal fun MurongReadOnlyCodeBlock(
    code: String,
    language: String?,
    backgroundColor: Color,
    modifier: Modifier = Modifier,
    searchQuery: String = "",
    showLineNumbers: Boolean = false,
    textSizeSp: Float = 12f,
    maxVisibleLines: Int = 18
) {
    val context = LocalContext.current
    val visibleLineCount = remember(code, maxVisibleLines) {
        code.lineSequence().count().coerceIn(1, maxVisibleLines)
    }
    val blockHeight = (visibleLineCount * 22 + 16).dp
    AndroidView(
        modifier = modifier.then(Modifier.height(blockHeight)),
        factory = {
            CodeEditor(it).apply {
                setText(Content(code))
                setBackgroundColor(backgroundColor.toArgb())
                setTextSize(textSizeSp)
                setTabWidth(4)
                typefaceText = Typeface.MONOSPACE
                typefaceLineNumber = Typeface.MONOSPACE
                isWordwrap = false
                props.overScrollEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                setHighlightCurrentLine(false)
                setHighlightCurrentBlock(false)
                setCursorAnimationEnabled(false)
                setEditable(false)
                setLineNumberEnabled(showLineNumbers)
                setDividerWidth(if (showLineNumbers) 1f else 0f)
                setDividerMargin(12f)
                setLineSpacing(2f, 1.1f)
                applyReasonixEditorLanguage(
                    context = context.applicationContext,
                    language = language,
                    darkTheme = backgroundColor.luminance() < 0.5f
                )
            }
        },
        update = { editor ->
            if (editor.text.toString() != code) {
                editor.setText(Content(code))
            }
            editor.setBackgroundColor(backgroundColor.toArgb())
            editor.setLineNumberEnabled(showLineNumbers)
            editor.setDividerWidth(if (showLineNumbers) 1f else 0f)
            editor.applyReasonixEditorLanguage(
                context = context.applicationContext,
                language = language,
                darkTheme = backgroundColor.luminance() < 0.5f
            )
            val query = searchQuery.trim()
            if (query.isBlank()) {
                if (editor.searcher.hasQuery()) {
                    editor.searcher.stopSearch()
                }
            } else {
                editor.searcher.search(query, EditorSearcher.SearchOptions(false, false))
            }
        }
    )
}
