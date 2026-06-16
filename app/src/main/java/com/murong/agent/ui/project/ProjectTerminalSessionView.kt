package com.murong.agent.ui.project

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.murong.agent.common.shell.KeepShellPublic
import com.murong.agent.common.toolchain.ToolchainManager
import com.murong.agent.ui.MurongUiController
import com.murong.agent.ui.parseMurongColor
import com.termux.terminal.TerminalColorScheme
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.terminal.TextStyle
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Properties
import kotlin.math.roundToInt

private const val PROJECT_TERMINAL_SYSTEM_BASH = "/system/bin/bash"
private const val PROJECT_TERMINAL_SYSTEM_SH = "/system/bin/sh"

internal fun isSystemBashEnabled(context: Context): Boolean {
    return File(PROJECT_TERMINAL_SYSTEM_BASH).exists()
}

internal enum class ProjectTerminalThemePreset(
    val label: String,
    val summary: String,
    private val foreground: String,
    private val background: String,
    private val overrides: Map<String, String> = emptyMap()
) {
    MIDNIGHT(
        label = "夜色",
        summary = "深色默认",
        foreground = "#F5F7FA",
        background = "#000000",
        overrides = mapOf(
            "color0" to "#000000",
            "color1" to "#EF4444",
            "color2" to "#22C55E",
            "color3" to "#EAB308",
            "color4" to "#60A5FA",
            "color5" to "#C084FC",
            "color6" to "#22D3EE",
            "color7" to "#E5E7EB",
            "color8" to "#6B7280",
            "color9" to "#F87171",
            "color10" to "#4ADE80",
            "color11" to "#FACC15",
            "color12" to "#93C5FD",
            "color13" to "#D8B4FE",
            "color14" to "#67E8F9",
            "color15" to "#FFFFFF"
        )
    ),
    PAPER(
        label = "纸白",
        summary = "浅色阅读",
        foreground = "#1F2937",
        background = "#F8FAFC",
        overrides = mapOf(
            "color0" to "#374151",
            "color1" to "#DC2626",
            "color2" to "#16A34A",
            "color3" to "#CA8A04",
            "color4" to "#2563EB",
            "color5" to "#9333EA",
            "color6" to "#0891B2",
            "color7" to "#E5E7EB",
            "color8" to "#9CA3AF",
            "color9" to "#EF4444",
            "color10" to "#22C55E",
            "color11" to "#EAB308",
            "color12" to "#3B82F6",
            "color13" to "#A855F7",
            "color14" to "#06B6D4",
            "color15" to "#111827"
        )
    ),
    MATRIX(
        label = "墨绿",
        summary = "终端感更强",
        foreground = "#B7F7CF",
        background = "#07130D",
        overrides = mapOf(
            "color0" to "#0F1F17",
            "color1" to "#3DD68C",
            "color2" to "#4ADE80",
            "color3" to "#A3E635",
            "color4" to "#2DD4BF",
            "color5" to "#34D399",
            "color6" to "#5EEAD4",
            "color7" to "#D1FAE5",
            "color8" to "#3F6B57",
            "color9" to "#86EFAC",
            "color10" to "#86EFAC",
            "color11" to "#BEF264",
            "color12" to "#5EEAD4",
            "color13" to "#6EE7B7",
            "color14" to "#99F6E4",
            "color15" to "#ECFDF5"
        )
    );

    fun toProperties(): Properties {
        return Properties().apply {
            setProperty("foreground", foreground)
            setProperty("background", background)
            overrides.forEach { (key, value) -> setProperty(key, value) }
        }
    }
}

internal enum class ProjectTerminalEnvironmentMode {
    TOOLCHAIN,
    SYSTEM
}

@Composable
internal fun rememberProjectTerminalSessionController(
    ownerKey: String,
    sessionId: String,
    workingDirectory: String,
    sessionKey: Int,
    environmentMode: ProjectTerminalEnvironmentMode,
    rootAvailable: Boolean,
    initialTheme: ProjectTerminalThemePreset,
    initialFontSize: Int
): ProjectTerminalSessionController {
    val context = LocalContext.current.applicationContext
    val controller = remember(ownerKey, sessionId, sessionKey, workingDirectory, environmentMode, rootAvailable) {
        ProjectTerminalSessionRegistry.acquire(
            ownerKey = ownerKey,
            sessionId = sessionId,
            sessionGeneration = sessionKey,
            factory = {
                ProjectTerminalSessionController(
                    context = context,
                    workingDirectory = workingDirectory,
                    environmentMode = environmentMode,
                    rootAvailable = rootAvailable,
                    initialTheme = initialTheme,
                    initialFontSize = initialFontSize
                )
            }
        )
    }
    LaunchedEffect(controller, initialTheme) {
        controller.applyTheme(initialTheme, announce = false)
    }
    LaunchedEffect(controller, initialFontSize) {
        controller.applyFontSize(initialFontSize, announce = false)
    }
    DisposableEffect(controller, ownerKey, sessionId, sessionKey) {
        onDispose {
        }
    }
    return controller
}

@Composable
internal fun ProjectTerminalSessionSurface(
    controller: ProjectTerminalSessionController,
    modifier: Modifier = Modifier
) {
    val terminalShape = RoundedCornerShape(18.dp)
    var refreshTick by remember(controller) { mutableIntStateOf(0) }
    LaunchedEffect(controller) {
        controller.onInvalidate = { refreshTick++ }
    }
    AndroidView(
        factory = { context ->
            FrameLayout(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(controller.viewBackgroundColor)
                mountTerminalView(context, controller)
            }
        },
        modifier = modifier
            .clip(terminalShape)
            .background(controller.surfaceColor),
        update = { host ->
            host.setBackgroundColor(controller.viewBackgroundColor)
            host.updateMountedTerminalView(controller)
            refreshTick
        }
    )
}

private fun FrameLayout.mountTerminalView(
    context: Context,
    controller: ProjectTerminalSessionController
) {
    removeAllViews()
    val terminalView = TerminalView(context, null).apply {
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        setBackgroundColor(controller.viewBackgroundColor)
        controller.bind(this)
    }
    addView(terminalView)
    tag = controller.viewGeneration
}

private fun FrameLayout.updateMountedTerminalView(controller: ProjectTerminalSessionController) {
    val currentGeneration = tag as? Int
    if (currentGeneration != controller.viewGeneration || childCount == 0) {
        mountTerminalView(context, controller)
        return
    }
    (getChildAt(0) as? TerminalView)?.let { terminalView ->
        terminalView.setBackgroundColor(controller.viewBackgroundColor)
        controller.bind(terminalView)
    }
}

internal object ProjectTerminalSessionRegistry {
    private val controllers = LinkedHashMap<String, ProjectTerminalSessionController>()

    private fun key(ownerKey: String, sessionId: String, sessionGeneration: Int): String {
        return "$ownerKey::$sessionId::$sessionGeneration"
    }

    fun acquire(
        ownerKey: String,
        sessionId: String,
        sessionGeneration: Int,
        factory: () -> ProjectTerminalSessionController
    ): ProjectTerminalSessionController {
        val registryKey = key(ownerKey, sessionId, sessionGeneration)
        return synchronized(this) {
            val existing = controllers[registryKey]
            existing ?: factory().also { controllers[registryKey] = it }
        }
    }

    fun release(ownerKey: String, sessionId: String, sessionGeneration: Int) {
        val registryKey = key(ownerKey, sessionId, sessionGeneration)
        val controller = synchronized(this) {
            val removed = controllers.remove(registryKey)
            removed
        }
        controller?.dispose()
    }
}

internal class ProjectTerminalSessionController(
    private val context: Context,
    private val workingDirectory: String,
    private val environmentMode: ProjectTerminalEnvironmentMode,
    private val rootAvailable: Boolean,
    initialTheme: ProjectTerminalThemePreset,
    initialFontSize: Int
) {
    var fontSize by mutableStateOf(initialFontSize.coerceIn(14, 30))
        private set
    var onFontSizeChanged: ((Int) -> Unit)? = null
    var onSessionExit: ((Int) -> Unit)? = null
    private val activeEnvironmentLabel =
        ToolchainManager.describeActiveEnvironment(
            context = context,
            preferSystem = environmentMode == ProjectTerminalEnvironmentMode.SYSTEM
        )
    val isRootSession: Boolean
        get() = rootAvailable
    var title by mutableStateOf("终端会话")
    var subtitle by mutableStateOf(buildInitialSubtitle(rootAvailable, activeEnvironmentLabel))
    var isFinished by mutableStateOf(false)
        private set
    var lastExitCode by mutableStateOf<Int?>(null)
        private set
    var selectedTheme by mutableStateOf(initialTheme)
        private set
    var ctrlLocked by mutableStateOf(false)
        private set
    var altLocked by mutableStateOf(false)
        private set
    var shiftLocked by mutableStateOf(false)
        private set
    var fnLocked by mutableStateOf(false)
        private set
    val lockedModifiersLabel: String
        get() = buildLockedModifiersLabel()
    val oneShotModifiersLabel: String
        get() = buildOneShotModifiersLabel()
    val persistentModifiersLabel: String
        get() = if (fnLocked) "Fn" else "无"
    val hasLockedModifiers: Boolean
        get() = ctrlLocked || altLocked || shiftLocked || fnLocked
    val surfaceColor: Color
        get() = Color(viewBackgroundColor)
    val viewBackgroundColor: Int
        get() = currentBackgroundColor()
    var onInvalidate: (() -> Unit)? = null
    var viewGeneration by mutableIntStateOf(0)
        private set
    private var uiBackgroundHex: String? = null
    private var uiForegroundHex: String? = null
    private val sessionClient = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {
            terminalView?.post {
                terminalView?.onScreenUpdated()
                onInvalidate?.invoke()
            }
        }

        override fun onTitleChanged(changedSession: TerminalSession) {
            title = changedSession.title ?: "终端会话"
        }

        override fun onSessionFinished(finishedSession: TerminalSession) {
            isFinished = true
            lastExitCode = finishedSession.exitStatus
            subtitle = "会话已结束，退出码 ${finishedSession.exitStatus}"
            onInvalidate?.invoke()
            if (!suppressSessionExitCallback) {
                onSessionExit?.invoke(finishedSession.exitStatus)
            }
        }

        override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            clipboard.setPrimaryClip(ClipData.newPlainText("MurongTerminal", text))
        }

        override fun onPasteTextFromClipboard(session: TerminalSession?) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
            if (text.isNotEmpty()) {
                session?.emulator?.paste(text)
                updateLiveSubtitle(action = "已粘贴剪贴板")
            }
        }

        override fun onBell(session: TerminalSession) = Unit
        override fun onColorsChanged(session: TerminalSession) {
            terminalView?.post { terminalView?.invalidate() }
        }

        override fun onTerminalCursorStateChange(state: Boolean) {
            terminalView?.post { terminalView?.invalidate() }
        }

        override fun setTerminalShellPid(session: TerminalSession, pid: Int) {
            shellPid = pid
            updateLiveSubtitle()
        }

        override fun getTerminalCursorStyle(): Int? = TerminalEmulator.TERMINAL_CURSOR_STYLE_UNDERLINE
        override fun logError(tag: String, message: String) {
            Log.e(tag, message)
        }

        override fun logWarn(tag: String, message: String) {
            Log.w(tag, message)
        }

        override fun logInfo(tag: String, message: String) {
            Unit
        }

        override fun logDebug(tag: String, message: String) {
            Unit
        }

        override fun logVerbose(tag: String, message: String) {
            Unit
        }

        override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
            Log.e(tag, message, e)
        }

        override fun logStackTrace(tag: String, e: Exception) {
            Log.e(tag, e.message, e)
        }
    }

    private var terminalView: TerminalView? = null
    private var shellPid: Int? = null
    private var suppressSessionExitCallback = false
    private var pinchScaleRemainder = 1f
    private var pinchInProgress = false
    private var pinchStartFontSize = fontSize
    private val terminalColorScheme = TerminalColorScheme()
    private var resolvedShellPath: String = PROJECT_TERMINAL_SYSTEM_SH
    private val session = createSession()

    init {
        applyTheme(initialTheme, announce = false)
        applyFontSize(fontSize, announce = false)
    }

    private val viewClient = object : TerminalViewClient {
        override fun onScale(scale: Float): Float = handleScaleGesture(scale)
        override fun onScaleEnd(scale: Float): Float = finalizeScaleGesture(scale)

        override fun onSingleTapUp(e: MotionEvent) {
            terminalView?.requestFocus()
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            terminalView?.let { imm?.showSoftInput(it, 0) }
        }

        override fun shouldBackButtonBeMappedToEscape(): Boolean = false
        override fun shouldEnforceCharBasedInput(): Boolean = false
        override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
        override fun isTerminalViewSelected(): Boolean = terminalView?.hasFocus() == true
        override fun copyModeChanged(copyMode: Boolean) = Unit
        override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false
        override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean {
            maybeConsumeOneShotModifiersFromKeyEvent(keyCode, e)
            return false
        }
        override fun onLongPress(event: MotionEvent): Boolean = false
        override fun readControlKey(): Boolean = ctrlLocked
        override fun readAltKey(): Boolean = altLocked
        override fun readShiftKey(): Boolean = shiftLocked
        override fun readFnKey(): Boolean = fnLocked
        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean {
            consumeOneShotModifiers()
            return false
        }
        override fun onEmulatorSet() {
            applyThemeColorsToSession()
            terminalView?.invalidate()
        }

        override fun logError(tag: String, message: String) {
            Log.e(tag, message)
        }

        override fun logWarn(tag: String, message: String) {
            Log.w(tag, message)
        }

        override fun logInfo(tag: String, message: String) {
            Unit
        }

        override fun logDebug(tag: String, message: String) {
            Unit
        }

        override fun logVerbose(tag: String, message: String) {
            Unit
        }

        override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
            Log.e(tag, message, e)
        }

        override fun logStackTrace(tag: String, e: Exception) {
            Log.e(tag, e.message, e)
        }
    }

    fun bind(view: TerminalView) {
        if (terminalView === view && view.currentSession === session) return
        terminalView = view
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        view.keepScreenOn = true
        view.setTerminalViewClient(viewClient)
        view.setTextSize(resolveTerminalTextSizePx())
        view.attachSession(session)
        view.post {
            if (terminalView === view) {
                view.updateSize()
                view.onScreenUpdated()
                view.invalidate()
            }
        }
    }

    fun setKeepTerminalPinnedToBottom(enable: Boolean) {
        // IME handling now relies on terminal view resize instead of forcing topRow.
    }

    fun sendCommand(command: String) {
        if (command.isBlank()) return
        if (isFinished) return
        val normalized = if (command.endsWith("\n")) command else "$command\n"
        sendBytes(normalized.toByteArray(StandardCharsets.UTF_8))
    }

    fun sendCtrlC() {
        if (isFinished) return
        sendBytes(byteArrayOf(3))
    }

    fun sendTab() {
        if (isFinished) return
        sendBytes(byteArrayOf('\t'.code.toByte()))
    }

    fun sendEscape() {
        if (isFinished) return
        sendBytes(byteArrayOf(27))
    }

    fun sendEnter() {
        if (isFinished) return
        sendBytes(byteArrayOf('\n'.code.toByte()))
    }

    fun sendArrowUp() = sendEscapeSequence("[A")
    fun sendArrowDown() = sendEscapeSequence("[B")
    fun sendArrowRight() = sendEscapeSequence("[C")
    fun sendArrowLeft() = sendEscapeSequence("[D")

    fun clearScreen() {
        if (isFinished) return
        sendCommand("clear")
    }

    fun pasteClipboard() {
        if (isFinished) return
        sessionClient.onPasteTextFromClipboard(session)
    }

    fun copyTranscript(): Boolean {
        val text = getTranscriptText() ?: return false
        sessionClient.onCopyTextToClipboard(session, text)
        updateLiveSubtitle(action = "已复制终端输出")
        return true
    }

    fun getTranscriptText(): String? {
        return session.emulator?.screen?.transcriptText?.takeIf { it.isNotBlank() }
    }

    fun toggleCtrlLock() {
        ctrlLocked = !ctrlLocked
        notifyModifierStateChanged()
    }

    fun toggleAltLock() {
        altLocked = !altLocked
        notifyModifierStateChanged()
    }

    fun toggleShiftLock() {
        shiftLocked = !shiftLocked
        notifyModifierStateChanged()
    }

    fun toggleFnLock() {
        fnLocked = !fnLocked
        notifyModifierStateChanged()
    }

    fun clearModifierLocks() {
        if (!ctrlLocked && !altLocked && !shiftLocked && !fnLocked) return
        ctrlLocked = false
        altLocked = false
        shiftLocked = false
        fnLocked = false
        notifyModifierStateChanged()
    }

    fun applyTheme(
        theme: ProjectTerminalThemePreset,
        announce: Boolean = true
    ) {
        selectedTheme = theme
        refreshThemeColors()
        if (announce && !isFinished) {
            updateLiveSubtitle(action = "已切换到${theme.label}主题")
        } else {
            updateLiveSubtitle()
        }
        terminalView?.post {
            terminalView?.setBackgroundColor(viewBackgroundColor)
            terminalView?.invalidate()
        }
        onInvalidate?.invoke()
    }

    fun applyUiPalette(
        background: Color,
        foreground: Color
    ) {
        val nextBackground = background.toTerminalHex()
        val nextForeground = foreground.toTerminalHex()
        if (uiBackgroundHex == nextBackground && uiForegroundHex == nextForeground) return
        uiBackgroundHex = nextBackground
        uiForegroundHex = nextForeground
        refreshThemeColors()
        terminalView?.post {
            terminalView?.setBackgroundColor(viewBackgroundColor)
            terminalView?.invalidate()
        }
        onInvalidate?.invoke()
    }

    fun increaseFontSize() {
        applyFontSize(fontSize + 1)
    }

    fun decreaseFontSize() {
        applyFontSize(fontSize - 1)
    }

    fun applyFontSize(
        newSize: Int,
        announce: Boolean = true
    ) {
        updateFontSize(newSize)
        if (announce && !isFinished) {
            updateLiveSubtitle(action = "已调整字号")
        } else {
            updateLiveSubtitle()
        }
    }

    fun terminate() {
        if (isFinished) return
        suppressSessionExitCallback = true
        session.finishIfRunning()
    }

    fun dispose() {
        terminalView = null
        suppressSessionExitCallback = true
        session.finishIfRunning()
    }

    private fun createSession(): TerminalSession {
        val installedToolchain = ToolchainManager.ensureInstalled(context)
        val home = File("/storage/emulated/0").takeIf { it.exists() }?.absolutePath
            ?: context.filesDir.absolutePath
        val preferSystemEnvironment = environmentMode == ProjectTerminalEnvironmentMode.SYSTEM
        val preferredBash = if (preferSystemEnvironment) {
            if (isSystemBashEnabled(context)) {
                PROJECT_TERMINAL_SYSTEM_BASH
            } else {
                null
            }
        } else {
            ToolchainManager.getBundledCommandPath("bash", context)
        }
        val shell = preferredBash ?: PROJECT_TERMINAL_SYSTEM_SH
        resolvedShellPath = shell
        val isBashShell = shell.substringAfterLast('/').equals("bash", ignoreCase = true)
        val usingRcFileShell = isBashShell && !preferSystemEnvironment
        val sessionPath = if (preferSystemEnvironment) {
            ToolchainManager.buildSystemPath()
        } else {
            ToolchainManager.buildPreferredPath(context)
        }
        val libraryPath = if (preferSystemEnvironment) {
            ToolchainManager.buildSystemLibraryPath()
        } else {
            ToolchainManager.buildPreferredLibraryPath(context)
        }
        val prefix = if (preferSystemEnvironment || !installedToolchain.available) {
            ""
        } else {
            installedToolchain.rootDir.absolutePath
        }
        val shellRcPath = ensureShellRcFile(
            sessionPath = sessionPath,
            sessionLibraryPath = libraryPath,
            sessionShell = shell,
            useRcFileArgument = usingRcFileShell,
            prefix = prefix,
            aliasCommands = if (preferSystemEnvironment) "" else ToolchainManager.buildAliasCommands(context)
        ).takeIf { it.isNotBlank() }
        val args = when {
            usingRcFileShell && !shellRcPath.isNullOrBlank() -> arrayOf(shell, "--rcfile", shellRcPath, "-i")
            preferSystemEnvironment && isBashShell -> arrayOf(shell, "--noprofile", "--norc", "--noediting")
            usingRcFileShell -> arrayOf(shell, "-i")
            else -> arrayOf(shell, "-i")
        }
        val env = buildList {
            add("TERM=xterm-256color")
            add("HOME=$home")
            add("TMPDIR=${context.cacheDir.absolutePath}")
            add("PATH=$sessionPath")
            add("SHELL=$shell")
            if (prefix.isNotBlank()) {
                add("PREFIX=$prefix")
            }
            if (libraryPath.isNotBlank()) {
                add("LD_LIBRARY_PATH=$libraryPath")
            }
            if (!usingRcFileShell && !shellRcPath.isNullOrBlank()) {
                add("ENV=$shellRcPath")
            }
        }.toTypedArray()
        val cwd = File(workingDirectory).takeIf { it.exists() }?.absolutePath ?: home
        if (preferSystemEnvironment && shell == PROJECT_TERMINAL_SYSTEM_BASH) {
            val helperHome = home
            val helperCwd = cwd
            val helperSession = SystemBashHelperBridge.open(
                context = context,
                cwd = helperCwd,
                path = sessionPath,
                libraryPath = libraryPath,
                home = helperHome,
                rcFilePath = shellRcPath.orEmpty(),
                tmpDir = context.cacheDir.absolutePath
            )
            if (helperSession != null) {
                return TerminalSession(
                    shell,
                    helperCwd,
                    args,
                    env,
                    5000,
                    helperSession.terminalFd,
                    helperSession.shellPid,
                    helperSession.processMonitor,
                    sessionClient
                )
            }
            Log.w(
                "MurongTerminalShell",
                "System bash helper unavailable, falling back to direct bash launch"
            )
        }
        return TerminalSession(
            shell,
            cwd,
            args,
            env,
            5000,
            sessionClient
        )
    }

    private fun sendEscapeSequence(sequence: String) {
        if (isFinished) return
        sendBytes("\u001B$sequence".toByteArray(StandardCharsets.UTF_8))
    }

    private fun sendBytes(data: ByteArray) {
        session.write(data, 0, data.size)
    }

    private fun updateFontSize(newSize: Int) {
        val normalized = newSize.coerceIn(14, 30)
        if (normalized == fontSize) return
        fontSize = normalized
        val boundView = terminalView
        boundView?.post {
            if (boundView !== terminalView) return@post
            boundView.setTextSize(resolveTerminalTextSizePx())
            boundView.onScreenUpdated()
        }
        onFontSizeChanged?.invoke(fontSize)
        onInvalidate?.invoke()
    }

    private fun handleScaleGesture(scale: Float): Float {
        if (!pinchInProgress) {
            pinchInProgress = true
            pinchStartFontSize = fontSize
            pinchScaleRemainder = 1f
        }
        val previousRemainder = pinchScaleRemainder
        pinchScaleRemainder = (if (scale.isFinite()) scale else 1f).coerceIn(0.7f, 1.6f)
        return pinchScaleRemainder
    }

    private fun finalizeScaleGesture(scale: Float): Float {
        if (!pinchInProgress) return 1f
        val finalScale = (if (scale.isFinite()) scale else pinchScaleRemainder).coerceIn(0.7f, 1.6f)
        var adjusted = finalScale
        var targetFontSize = pinchStartFontSize
        var increaseSteps = 0
        var decreaseSteps = 0
        while (adjusted >= 1.12f) {
            targetFontSize++
            adjusted /= 1.12f
            increaseSteps++
        }
        while (adjusted <= 0.89f) {
            targetFontSize--
            adjusted /= 0.89f
            decreaseSteps++
        }
        val normalizedTarget = targetFontSize.coerceIn(14, 30)
        pinchInProgress = false
        pinchScaleRemainder = 1f
        pinchStartFontSize = fontSize
        if (normalizedTarget != fontSize) {
            applyFontSize(normalizedTarget, announce = false)
        }
        return 1f
    }

    private fun resolveTerminalTextSizePx(): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            fontSize.toFloat(),
            context.resources.displayMetrics
        ).roundToInt()
    }

    private fun ensureShellRcFile(
        sessionPath: String,
        sessionLibraryPath: String,
        sessionShell: String,
        useRcFileArgument: Boolean,
        prefix: String,
        aliasCommands: String
    ): String {
        val rcFile = File(
            context.filesDir,
            "terminal_shellrc_${environmentMode.name.lowercase()}.sh"
        )
        val esc = "\u001B"
        val uiController = MurongUiController(context)
        val iconColor = parseMurongColor(uiController.terminalIconColorHex, Color(0xFF22C55E))
        val pathColor = parseMurongColor(uiController.terminalPathColorHex, Color(0xFF86EFAC))
        val errorColor = parseMurongColor(uiController.terminalErrorColorHex, Color(0xFFEF4444))
        val iconAnsi = iconColor.toPromptAnsi()
        val pathAnsi = pathColor.toPromptAnsi()
        val errorAnsi = errorColor.toPromptAnsi()
        val home = File("/storage/emulated/0").takeIf { it.exists() }?.absolutePath
            ?: context.filesDir.absolutePath
        val tmpDir = context.cacheDir.absolutePath
        val systemSuPath = ToolchainManager.resolveSystemCommandPath("su")
        val desired = if (environmentMode == ProjectTerminalEnvironmentMode.SYSTEM) {
            """
                __murong_session_path=${shellQuoteForRc(sessionPath)}
                __murong_system_su=${shellQuoteForRc(systemSuPath)}
                __murong_session_shell=${shellQuoteForRc(sessionShell)}
                __murong_home_path=${shellQuoteForRc(home)}
                __murong_tmpdir=${shellQuoteForRc(tmpDir)}
                __murong_rc_path=${shellQuoteForRc(rcFile.absolutePath)}
                __murong_session_shell_is_bash=${if (sessionShell.substringAfterLast('/').equals("bash", ignoreCase = true)) 1 else 0}
                murong_refresh_runtime_env() {
                  export PATH="${'$'}__murong_session_path"
                  export HOME="${'$'}__murong_home_path"
                  export TMPDIR="${'$'}__murong_tmpdir"
                  export SHELL="${'$'}__murong_session_shell"
                  unset PREFIX
                  unset LD_LIBRARY_PATH
                }
                su() {
                  if [ "${'$'}#" -eq 0 ] && [ -n "${'$'}__murong_session_shell" ]; then
                    __murong_su_cmd="export HOME=${'$'}__murong_home_path; export TMPDIR=${'$'}__murong_tmpdir; export PATH=${'$'}__murong_session_path; export SHELL=${'$'}__murong_session_shell; unset PREFIX; unset LD_LIBRARY_PATH; "
                    if [ "${'$'}__murong_session_shell_is_bash" = "1" ]; then
                      __murong_su_cmd="${'$'}__murong_su_cmd exec ${'$'}__murong_session_shell --noprofile --rcfile ${'$'}__murong_rc_path -i"
                    else
                      __murong_su_cmd="${'$'}__murong_su_cmd ENV=${'$'}__murong_rc_path exec ${'$'}__murong_session_shell -i"
                    fi
                    "${'$'}__murong_system_su" -c "${'$'}__murong_su_cmd"
                  else
                    "${'$'}__murong_system_su" "${'$'}@"
                  fi
                }
                murong_prompt() {
                  murong_refresh_runtime_env
                  __murong_home="${'$'}__murong_home_path"
                  __murong_sdcard="/storage/emulated/0"
                  __murong_sdcard_link="/sdcard"
                  __murong_sdcard_primary="/storage/self/primary"
                  __murong_np_start=${'$'}'\001'
                  __murong_np_end=${'$'}'\002'
                  __murong_status=${'$'}?
                  __murong_path="${'$'}{PWD##*/}"
                  if [ "${'$'}PWD" = "${'$'}__murong_sdcard" ] || [ "${'$'}PWD" = "${'$'}__murong_sdcard_link" ] || [ "${'$'}PWD" = "${'$'}__murong_sdcard_primary" ]; then
                    __murong_path="sdcard"
                  elif [ "${'$'}PWD" = "${'$'}__murong_home" ]; then
                    __murong_path="${'$'}{__murong_home##*/}"
                  elif [ "${'$'}PWD" = "/" ]; then
                    __murong_path="/"
                  fi
                  __murong_uid="${'$'}{USER_ID:-}"
                  if [ -z "${'$'}__murong_uid" ]; then
                    __murong_uid=$(id -u 2>/dev/null)
                  fi
                  if [ "${'$'}__murong_status" -eq 0 ]; then
                    __murong_symbol_color="${esc}${iconAnsi}"
                  else
                    __murong_symbol_color="${esc}${errorAnsi}"
                  fi
                  __murong_path_color="${esc}${pathAnsi}"
                  __murong_reset="${esc}[0m"
                  if [ "${'$'}__murong_uid" = "0" ]; then
                    __murong_symbol="#"
                  else
                    __murong_symbol="${'$'}"
                  fi
                  printf '%s' "${'$'}{__murong_np_start}${'$'}{__murong_symbol_color}${'$'}{__murong_np_end}${'$'}__murong_symbol${'$'}{__murong_np_start}${'$'}{__murong_reset}${'$'}{__murong_np_end} ${'$'}{__murong_np_start}${'$'}{__murong_path_color}${'$'}{__murong_np_end}${'$'}__murong_path >${'$'}{__murong_np_start}${'$'}{__murong_reset}${'$'}{__murong_np_end} "
                }
                PROMPT_COMMAND=
                PS1='$(murong_prompt)'
            """.trimIndent() + "\n"
        } else {
            """
                __murong_session_path=${shellQuoteForRc(sessionPath)}
                __murong_session_ld_library_path=${shellQuoteForRc(sessionLibraryPath)}
                __murong_system_su=${shellQuoteForRc(systemSuPath)}
                __murong_session_shell=${shellQuoteForRc(sessionShell)}
                __murong_session_shell_uses_rcfile=${if (useRcFileArgument) 1 else 0}
                __murong_prefix=${shellQuoteForRc(prefix)}
                __murong_home_path=${shellQuoteForRc(home)}
                __murong_tmpdir=${shellQuoteForRc(tmpDir)}
                __murong_rc_path=${shellQuoteForRc(rcFile.absolutePath)}
                murong_refresh_runtime_env() {
                  __murong_uid=$(id -u 2>/dev/null)
                  export PATH="${'$'}__murong_session_path"
                  export HOME="${'$'}__murong_home_path"
                  export TMPDIR="${'$'}__murong_tmpdir"
                  export SHELL="${'$'}__murong_session_shell"
                  if [ -n "${'$'}__murong_prefix" ]; then
                    export PREFIX="${'$'}__murong_prefix"
                  else
                    unset PREFIX
                  fi
                  if [ -n "${'$'}__murong_session_ld_library_path" ]; then
                    export LD_LIBRARY_PATH="${'$'}__murong_session_ld_library_path"
                  else
                    unset LD_LIBRARY_PATH
                  fi
                }
                su() {
                  if [ "${'$'}#" -eq 0 ] && [ -n "${'$'}__murong_session_shell" ]; then
                    __murong_su_cmd="export HOME=${'$'}__murong_home_path; export TMPDIR=${'$'}__murong_tmpdir; export PATH=${'$'}__murong_session_path; export SHELL=${'$'}__murong_session_shell; "
                    if [ -n "${'$'}__murong_prefix" ]; then
                      __murong_su_cmd="${'$'}__murong_su_cmd export PREFIX=${'$'}__murong_prefix; "
                    else
                      __murong_su_cmd="${'$'}__murong_su_cmd unset PREFIX; "
                    fi
                    if [ -n "${'$'}__murong_session_ld_library_path" ]; then
                      __murong_su_cmd="${'$'}__murong_su_cmd export LD_LIBRARY_PATH=${'$'}__murong_session_ld_library_path; "
                    else
                      __murong_su_cmd="${'$'}__murong_su_cmd unset LD_LIBRARY_PATH; "
                    fi
                    if [ "${'$'}__murong_session_shell_uses_rcfile" = "1" ]; then
                      __murong_su_cmd="${'$'}__murong_su_cmd exec ${'$'}__murong_session_shell --rcfile ${'$'}__murong_rc_path -i"
                    else
                      __murong_su_cmd="${'$'}__murong_su_cmd ENV=${'$'}__murong_rc_path exec ${'$'}__murong_session_shell -i"
                    fi
                    "${'$'}__murong_system_su" -c "${'$'}__murong_su_cmd"
                  else
                    "${'$'}__murong_system_su" "${'$'}@"
                  fi
                }
                ${aliasCommands}
                murong_prompt() {
                  murong_refresh_runtime_env
                  __murong_home="/storage/emulated/0"
                  __murong_sdcard="/storage/emulated/0"
                  __murong_sdcard_link="/sdcard"
                  __murong_sdcard_primary="/storage/self/primary"
                  __murong_np_start=${'$'}'\001'
                  __murong_np_end=${'$'}'\002'
                  __murong_status=${'$'}?
                  __murong_path="${'$'}{PWD##*/}"
                  if [ "${'$'}PWD" = "${'$'}__murong_home" ] || [ "${'$'}PWD" = "${'$'}__murong_sdcard" ] || [ "${'$'}PWD" = "${'$'}__murong_sdcard_link" ] || [ "${'$'}PWD" = "${'$'}__murong_sdcard_primary" ]; then
                    __murong_path="sdcard"
                  elif [ "${'$'}PWD" = "/" ]; then
                    __murong_path="/"
                  fi
                  __murong_uid="${'$'}{USER_ID:-}"
                  if [ -z "${'$'}__murong_uid" ]; then
                    __murong_uid=$(id -u 2>/dev/null)
                  fi
                  if [ "${'$'}__murong_status" -eq 0 ]; then
                    __murong_symbol_color="${esc}${iconAnsi}"
                  else
                    __murong_symbol_color="${esc}${errorAnsi}"
                  fi
                  __murong_path_color="${esc}${pathAnsi}"
                  __murong_reset="${esc}[0m"
                  if [ "${'$'}__murong_uid" = "0" ]; then
                    __murong_symbol="#"
                  else
                    __murong_symbol="${'$'}"
                  fi
                  printf '%s' "${'$'}{__murong_np_start}${'$'}{__murong_symbol_color}${'$'}{__murong_np_end}${'$'}__murong_symbol${'$'}{__murong_np_start}${'$'}{__murong_reset}${'$'}{__murong_np_end} ${'$'}{__murong_np_start}${'$'}{__murong_path_color}${'$'}{__murong_np_end}${'$'}__murong_path >${'$'}{__murong_np_start}${'$'}{__murong_reset}${'$'}{__murong_np_end} "
                }
                PROMPT_COMMAND=
                PS1='$(murong_prompt)'
            """.trimIndent() + "\n"
        }
        if (!rcFile.exists() || rcFile.readText() != desired) {
            rcFile.writeText(desired)
            rcFile.setReadable(true, false)
        }
        return rcFile.absolutePath
    }

    private fun shellQuoteForRc(value: String): String {
        return "'${value.replace("'", "'\"'\"'")}'"
    }

    private fun Color.toPromptAnsi(): String {
        val argb = toArgb()
        val red = (argb shr 16) and 0xFF
        val green = (argb shr 8) and 0xFF
        val blue = argb and 0xFF
        return "[38;2;${red};${green};${blue}m"
    }

    private fun notifyModifierStateChanged() {
        updateLiveSubtitle()
        terminalView?.post { terminalView?.invalidate() }
        onInvalidate?.invoke()
    }

    private fun maybeConsumeOneShotModifiersFromKeyEvent(keyCode: Int, event: KeyEvent) {
        if (event.isSystem) return
        when (keyCode) {
            KeyEvent.KEYCODE_CTRL_LEFT,
            KeyEvent.KEYCODE_CTRL_RIGHT,
            KeyEvent.KEYCODE_ALT_LEFT,
            KeyEvent.KEYCODE_ALT_RIGHT,
            KeyEvent.KEYCODE_SHIFT_LEFT,
            KeyEvent.KEYCODE_SHIFT_RIGHT,
            KeyEvent.KEYCODE_FUNCTION,
            KeyEvent.KEYCODE_UNKNOWN,
            KeyEvent.KEYCODE_BACK -> return
        }
        consumeOneShotModifiers()
    }

    private fun consumeOneShotModifiers() {
        if (!ctrlLocked && !altLocked && !shiftLocked) return
        ctrlLocked = false
        altLocked = false
        shiftLocked = false
        notifyModifierStateChanged()
    }

    private fun updateLiveSubtitle(action: String? = null) {
        if (isFinished) return
        subtitle = buildString {
            if (!action.isNullOrBlank()) {
                append(action)
                append(" · ")
            }
            shellPid?.let {
                append("PID ")
                append(it)
                append(" · ")
            }
            append(if (rootAvailable) "Root shell" else "普通 shell")
            append(" · 环境 ")
            append(activeEnvironmentLabel)
            append(" · 字号 ")
            append(fontSize)
            append(" · 主题 ")
            append(selectedTheme.label)
            val oneShot = buildOneShotModifiersLabel()
            if (oneShot != "无") {
                append(" · 待触发 ")
                append(oneShot)
            }
            val persistent = if (fnLocked) "Fn" else "无"
            if (persistent != "无") {
                append(" · 持续 ")
                append(persistent)
            }
        }
    }

    private fun buildOneShotModifiersLabel(): String {
        return buildList {
            if (ctrlLocked) add("Ctrl")
            if (altLocked) add("Alt")
            if (shiftLocked) add("Shift")
        }.joinToString(" ").ifBlank { "无" }
    }

    private fun buildLockedModifiersLabel(): String {
        return buildList {
            if (ctrlLocked) add("Ctrl")
            if (altLocked) add("Alt")
            if (shiftLocked) add("Shift")
            if (fnLocked) add("Fn")
        }.joinToString(" ").ifBlank { "无" }
    }

    private fun currentBackgroundColor(): Int {
        return session.emulator
            ?.mColors
            ?.mCurrentColors
            ?.get(TextStyle.COLOR_INDEX_BACKGROUND)
            ?: terminalColorScheme.mDefaultColors[TextStyle.COLOR_INDEX_BACKGROUND]
    }

    private fun refreshThemeColors() {
        terminalColorScheme.updateWith(buildEffectiveThemeProperties())
        applyThemeColorsToSession()
    }

    private fun applyThemeColorsToSession() {
        val colors = session.emulator?.mColors ?: return
        System.arraycopy(
            terminalColorScheme.mDefaultColors,
            0,
            colors.mCurrentColors,
            0,
            TextStyle.NUM_INDEXED_COLORS
        )
        session.onColorsChanged()
    }

    private fun buildEffectiveThemeProperties(): Properties {
        return selectedTheme.toProperties().apply {
            uiForegroundHex?.let { setProperty("foreground", it) }
            uiBackgroundHex?.let { setProperty("background", it) }
        }
    }

    companion object {
        private fun buildInitialSubtitle(rootAvailable: Boolean, environmentLabel: String): String {
            return if (rootAvailable) {
                "准备启动 Root 终端... · 环境 $environmentLabel"
            } else {
                "准备启动普通 shell... · 环境 $environmentLabel"
            }
        }
    }
}

private fun Color.toTerminalHex(): String {
    val red = (red * 255).roundToInt().coerceIn(0, 255)
    val green = (green * 255).roundToInt().coerceIn(0, 255)
    val blue = (blue * 255).roundToInt().coerceIn(0, 255)
    return String.format("#%02X%02X%02X", red, green, blue)
}
