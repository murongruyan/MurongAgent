package com.murong.agent.ui.project

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.HorizontalScrollView
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.murong.agent.R
import com.murong.agent.ui.MainActivity
import com.murong.agent.ui.MurongUiController
import com.murong.agent.ui.defaultMurongBackgroundColor
import com.murong.agent.ui.defaultMurongMutedTextColor
import com.murong.agent.ui.defaultMurongSurfaceColor
import com.murong.agent.ui.murongIsDarkColor
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.toArgb
import com.termux.view.TerminalView
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt

internal class ProjectTerminalOverlayService : Service() {
    private data class OverlaySessionTab(
        val id: String = UUID.randomUUID().toString(),
        val label: String,
        val workingDirectory: String
    )

    private data class OverlayPalette(
        val backgroundColor: Int,
        val borderColor: Int,
        val contentColor: Int,
        val mutedTextColor: Int,
        val buttonColor: Int,
        val buttonHighlightColor: Int,
        val tabSelectedColor: Int,
        val tabIdleColor: Int,
        val keyColor: Int
    )

    private enum class OverlayState {
        NOT_CREATED,
        CREATED,
        ATTACHED
    }

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var overlayContainer: LinearLayout? = null
    private var overlayLayoutParams: WindowManager.LayoutParams? = null
    private var terminalHostView: FrameLayout? = null
    private var tabStrip: LinearLayout? = null
    private var titleView: TextView? = null
    private var terminalView: TerminalView? = null
    private var currentWorkingDirectory: String = "/storage/emulated/0"
    private var currentFontSize: Int = 18
    private var currentThemeName: String = ProjectTerminalThemePreset.MIDNIGHT.name
    private var currentEnvironmentMode: ProjectTerminalEnvironmentMode =
        ProjectTerminalEnvironmentMode.TOOLCHAIN
    private var overlayState = OverlayState.NOT_CREATED
    private val overlayTabs = mutableListOf<OverlaySessionTab>()
    private val sessionControllers = LinkedHashMap<String, ProjectTerminalSessionController>()
    private val headerButtons = mutableListOf<TextView>()
    private val quickKeyButtons = mutableListOf<TextView>()
    private var activeSessionId: String? = null
    private var syncingFontSize = false
    private val uiController by lazy { MurongUiController(applicationContext) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                persistOverlayEnabled(this, false)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START,
            ACTION_UPDATE -> {
                currentWorkingDirectory = intent.getStringExtra(EXTRA_WORKING_DIRECTORY)
                    ?.takeIf { it.isNotBlank() }
                    ?: currentWorkingDirectory
                currentFontSize = intent.getIntExtra(EXTRA_FONT_SIZE, currentFontSize).coerceIn(14, 30)
                currentThemeName = intent.getStringExtra(EXTRA_THEME_NAME)
                    ?.takeIf { it.isNotBlank() }
                    ?: currentThemeName
                currentEnvironmentMode = intent.getStringExtra(EXTRA_ENVIRONMENT_MODE)
                    ?.let { raw ->
                        ProjectTerminalEnvironmentMode.entries.firstOrNull { it.name == raw }
                    }
                    ?: currentEnvironmentMode
                if (!canShowOverlay()) {
                    persistOverlayEnabled(this, false)
                    Toast.makeText(this, "请先开启悬浮窗权限", Toast.LENGTH_LONG).show()
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForeground(
                    NOTIFICATION_ID,
                    NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                        .setSmallIcon(R.mipmap.ic_launcher)
                        .setContentTitle("Murong 悬浮终端")
                        .setContentText("点击返回应用，悬浮窗会继续保留")
                        .setOngoing(true)
                        .setContentIntent(buildOpenAppPendingIntent())
                        .setSilent(true)
                        .setPriority(NotificationCompat.PRIORITY_LOW)
                        .build()
                )
                val attached = showOrUpdateOverlay()
                if (!attached) {
                    persistOverlayEnabled(this, false)
                    stopSelf()
                    return START_NOT_STICKY
                }
                persistOverlayEnabled(this, true)
                isRunning = true
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        removeOverlay()
        isRunning = false
        persistOverlayEnabled(this, false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun showOrUpdateOverlay(): Boolean {
        if (overlayView == null) {
            return createOverlay()
        } else {
            refreshControllersFromCurrentSettings()
            updateOverlayChrome()
            bindActiveSession()
            return true
        }
    }

    private fun createOverlay(): Boolean {
        val metrics = resources.displayMetrics
        val minWidth = (metrics.widthPixels * 0.46f).roundToInt()
        val maxWidth = (metrics.widthPixels * 0.88f).roundToInt()
        val minHeight = (metrics.heightPixels * 0.18f).roundToInt()
        val maxHeight = (metrics.heightPixels * 0.46f).roundToInt()
        val initialWidth = (metrics.widthPixels * 0.72f).roundToInt().coerceIn(minWidth, maxWidth)
        val initialHeight = (metrics.heightPixels * 0.24f).roundToInt().coerceIn(minHeight, maxHeight)

        val params = WindowManager.LayoutParams(
            initialWidth,
            initialHeight,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dpToPx(16)
            y = dpToPx(96)
            title = "MurongTerminalOverlay"
        }
        overlayLayoutParams = params

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        overlayContainer = container

        val titleView = TextView(this).apply {
            text = "Murong 终端"
            textSize = 14f
        }
        this.titleView = titleView

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(10), dpToPx(10), dpToPx(8), dpToPx(6))
        }
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        actionRow.addView(
            titleView,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )

        val tabsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val tabsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        tabsScroll.addView(
            tabsRow,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )
        tabStrip = tabsRow
        val tabsRowContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dpToPx(8), 0, 0)
        }
        tabsRowContainer.addView(
            tabsScroll,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )

        val openButton = buildHeaderButton("App") {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                }
            )
        }
        val newTabButton = buildHeaderButton("新建") {
            openNewSession()
        }
        val shrinkButton = buildHeaderButton("-") {
            resizeOverlay(
                deltaWidth = -dpToPx(56),
                deltaHeight = -dpToPx(20),
                minWidth,
                maxWidth,
                minHeight,
                maxHeight
            )
        }
        val growButton = buildHeaderButton("+") {
            resizeOverlay(
                deltaWidth = dpToPx(56),
                deltaHeight = dpToPx(20),
                minWidth,
                maxWidth,
                minHeight,
                maxHeight
            )
        }
        val closeButton = buildHeaderButton("X") {
            stopSelf()
        }
        actionRow.addView(openButton)
        actionRow.addView(shrinkButton)
        actionRow.addView(growButton)
        actionRow.addView(closeButton)
        tabsRowContainer.addView(newTabButton)
        header.addView(actionRow)
        header.addView(tabsRowContainer)

        val dragTouchListener = createDragTouchListener()
        actionRow.setOnTouchListener(dragTouchListener)
        tabsRowContainer.setOnTouchListener(dragTouchListener)

        val terminalHost = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            setPadding(dpToPx(8), 0, dpToPx(8), dpToPx(8))
        }
        terminalHostView = terminalHost

        val quickKeys = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(10))
            addView(buildQuickKey("Ctrl") { activeController()?.toggleCtrlLock() })
            addView(buildQuickKey("Shift") { activeController()?.toggleShiftLock() })
            addView(buildQuickKey("Esc") { activeController()?.sendEscape() })
            addView(buildQuickKey("Tab") { activeController()?.sendTab() })
            addView(buildQuickKey("Enter") { activeController()?.sendEnter() })
            addView(buildQuickKey("↑") { activeController()?.sendArrowUp() })
            addView(buildQuickKey("↓") { activeController()?.sendArrowDown() })
            addView(buildQuickKey("←") { activeController()?.sendArrowLeft() })
            addView(buildQuickKey("→") { activeController()?.sendArrowRight() })
        }

        container.addView(header)
        container.addView(terminalHost)
        container.addView(quickKeys)

        overlayView = container
        overlayState = OverlayState.CREATED
        val attached = runCatching {
            windowManager.addView(container, params)
            true
        }.getOrElse {
            removeOverlay()
            false
        }
        if (!attached) return false
        overlayState = OverlayState.ATTACHED
        ensureInitialSession()
        refreshControllersFromCurrentSettings()
        updateOverlayChrome()
        bindActiveSession(existingHost = terminalHost)
        return true
    }

    private fun ensureInitialSession() {
        if (overlayTabs.isNotEmpty()) return
        openNewSession(currentWorkingDirectory, switchToNew = true)
    }

    private fun openNewSession(
        workingDirectory: String = currentWorkingDirectory,
        switchToNew: Boolean = true
    ) {
        val tab = OverlaySessionTab(
            label = buildOverlaySessionLabel(workingDirectory),
            workingDirectory = workingDirectory
        )
        overlayTabs += tab
        sessionControllers[tab.id] = createControllerForTab(tab)
        if (switchToNew || activeSessionId == null) {
            activeSessionId = tab.id
        }
        updateOverlayChrome()
        bindActiveSession()
    }

    private fun closeSession(sessionId: String) {
        val index = overlayTabs.indexOfFirst { it.id == sessionId }
        if (index < 0) return
        val removed = overlayTabs.removeAt(index)
        sessionControllers.remove(removed.id)?.dispose()
        if (overlayTabs.isEmpty()) {
            activeSessionId = null
            openNewSession(currentWorkingDirectory, switchToNew = true)
            return
        }
        if (activeSessionId == sessionId) {
            val nextIndex = index.coerceAtMost(overlayTabs.lastIndex)
            activeSessionId = overlayTabs[nextIndex].id
        }
        updateOverlayChrome()
        bindActiveSession()
    }

    private fun selectSession(sessionId: String) {
        if (activeSessionId == sessionId) return
        activeSessionId = sessionId
        updateOverlayChrome()
        bindActiveSession()
    }

    private fun bindActiveSession(existingHost: FrameLayout? = null) {
        val host = existingHost ?: terminalHostView ?: return
        val tab = overlayTabs.firstOrNull { it.id == activeSessionId } ?: overlayTabs.firstOrNull() ?: return
        val controller = sessionControllers[tab.id] ?: createControllerForTab(tab).also {
            sessionControllers[tab.id] = it
        }
        activeSessionId = tab.id
        titleView?.text = "Murong 终端 · ${tab.label}"
        val existingView = host.getChildAt(0) as? TerminalView
        val boundSessionId = host.tag as? String
        if (existingView != null && boundSessionId == tab.id) {
            terminalView = existingView
            existingView.setBackgroundColor(controller.viewBackgroundColor)
            controller.bind(existingView)
            return
        }
        host.removeAllViews()
        terminalView = TerminalView(this, null).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(controller.viewBackgroundColor)
            controller.bind(this)
        }
        host.addView(terminalView)
        host.tag = tab.id
    }

    private fun createControllerForTab(tab: OverlaySessionTab): ProjectTerminalSessionController {
        val controller = ProjectTerminalSessionController(
            context = applicationContext,
            workingDirectory = tab.workingDirectory,
            environmentMode = currentEnvironmentMode,
            rootAvailable = false,
            initialTheme = currentThemePreset(),
            initialFontSize = currentFontSize
        )
        controller.onFontSizeChanged = { newSize ->
            syncOverlayFontSize(newSize, sourceId = tab.id)
        }
        applyThemeToController(controller)
        return controller
    }

    private fun syncOverlayFontSize(newSize: Int, sourceId: String) {
        if (syncingFontSize) return
        syncingFontSize = true
        try {
            currentFontSize = newSize
            sessionControllers.forEach { (id, controller) ->
                if (id != sourceId) {
                    controller.applyFontSize(newSize, announce = false)
                }
            }
        } finally {
            syncingFontSize = false
        }
    }

    private fun refreshControllersFromCurrentSettings() {
        if (overlayTabs.isEmpty()) return
        sessionControllers.values.forEach(::applyThemeToController)
    }

    private fun applyThemeToController(controller: ProjectTerminalSessionController) {
        val palette = currentOverlayPalette()
        controller.applyTheme(currentThemePreset(), announce = false)
        controller.applyFontSize(currentFontSize, announce = false)
        controller.applyUiPalette(
            background = ComposeColor(palette.backgroundColor),
            foreground = ComposeColor(palette.contentColor)
        )
    }

    private fun updateOverlayChrome() {
        val palette = currentOverlayPalette()
        overlayContainer?.background = GradientDrawable().apply {
            cornerRadius = dpToPx(18).toFloat()
            setColor(palette.backgroundColor)
            setStroke(dpToPx(1), palette.borderColor)
        }
        titleView?.setTextColor(palette.contentColor)
        terminalHostView?.setBackgroundColor(palette.backgroundColor)
        headerButtons.forEachIndexed { index, button ->
            val highlighted = index == 1
            styleActionButton(button, palette, highlighted)
        }
        quickKeyButtons.forEach { button ->
            styleQuickKey(button, palette)
        }
        rebuildTabStrip(palette)
    }

    private fun rebuildTabStrip(palette: OverlayPalette) {
        val strip = tabStrip ?: return
        strip.removeAllViews()
        overlayTabs.forEach { tab ->
            strip.addView(buildTabView(tab, palette))
        }
    }

    private fun buildTabView(tab: OverlaySessionTab, palette: OverlayPalette): View {
        val selected = tab.id == activeSessionId
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(12).toFloat()
                setColor(if (selected) palette.tabSelectedColor else palette.tabIdleColor)
                setStroke(dpToPx(1), palette.borderColor)
            }
            setPadding(dpToPx(10), dpToPx(6), dpToPx(8), dpToPx(6))
            setOnClickListener { selectSession(tab.id) }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = dpToPx(6)
            }
            addView(
                TextView(this@ProjectTerminalOverlayService).apply {
                    text = tab.label
                    setTextColor(if (selected) palette.contentColor else withAlpha(palette.contentColor, 176))
                    textSize = 12f
                    maxLines = 1
                }
            )
            addView(
                TextView(this@ProjectTerminalOverlayService).apply {
                    text = " x"
                    setTextColor(if (selected) palette.contentColor else withAlpha(palette.contentColor, 144))
                    textSize = 12f
                    setPadding(dpToPx(2), 0, 0, 0)
                    setOnClickListener { closeSession(tab.id) }
                }
            )
        }
    }

    private fun buildOverlaySessionLabel(workingDirectory: String): String {
        val base = File(workingDirectory).name.takeIf { it.isNotBlank() } ?: "终端"
        val existingCount = overlayTabs.count {
            it.label == base || it.label.startsWith("$base ")
        }
        return if (existingCount == 0) base else "$base ${existingCount + 1}"
    }

    private fun removeOverlay() {
        sessionControllers.values.forEach { it.dispose() }
        sessionControllers.clear()
        overlayTabs.clear()
        activeSessionId = null
        terminalView = null
        terminalHostView = null
        tabStrip = null
        titleView = null
        overlayContainer = null
        headerButtons.clear()
        quickKeyButtons.clear()
        overlayView?.let { view ->
            if (view.parent != null || view.isAttachedToWindow) {
                runCatching { windowManager.removeView(view) }
            }
        }
        overlayView = null
        overlayLayoutParams = null
        overlayState = OverlayState.NOT_CREATED
    }

    private fun resizeOverlay(
        deltaWidth: Int,
        deltaHeight: Int,
        minWidth: Int,
        maxWidth: Int,
        minHeight: Int,
        maxHeight: Int
    ) {
        val params = overlayLayoutParams ?: return
        params.width = (params.width + deltaWidth).coerceIn(minWidth, maxWidth)
        params.height = (params.height + deltaHeight).coerceIn(minHeight, maxHeight)
        overlayView?.let { windowManager.updateViewLayout(it, params) }
    }

    private fun createDragTouchListener(): View.OnTouchListener {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        return View.OnTouchListener { _, event ->
            val params = overlayLayoutParams ?: return@OnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (event.rawX - downX).roundToInt()
                    params.y = startY + (event.rawY - downY).roundToInt()
                    overlayView?.let { windowManager.updateViewLayout(it, params) }
                    true
                }
                else -> false
            }
        }
    }

    private fun buildHeaderButton(label: String, onClick: () -> Unit): View {
        return TextView(this).apply {
            text = label
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dpToPx(10), dpToPx(6), dpToPx(10), dpToPx(6))
            setOnClickListener { onClick() }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.marginStart = dpToPx(6)
            layoutParams = lp
            headerButtons += this
        }
    }

    private fun buildQuickKey(label: String, onClick: () -> Unit): View {
        return TextView(this).apply {
            text = label
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(dpToPx(10), dpToPx(6), dpToPx(10), dpToPx(6))
            setOnClickListener { onClick() }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.marginEnd = dpToPx(6)
            layoutParams = lp
            quickKeyButtons += this
        }
    }

    private fun styleActionButton(
        button: TextView,
        palette: OverlayPalette,
        highlighted: Boolean = false
    ) {
        button.setTextColor(if (highlighted) palette.contentColor else withAlpha(palette.contentColor, 236))
        button.background = GradientDrawable().apply {
            cornerRadius = dpToPx(10).toFloat()
            setColor(if (highlighted) palette.buttonHighlightColor else palette.buttonColor)
            setStroke(dpToPx(1), palette.borderColor)
        }
    }

    private fun styleQuickKey(button: TextView, palette: OverlayPalette) {
        button.setTextColor(withAlpha(palette.contentColor, 236))
        button.background = GradientDrawable().apply {
            cornerRadius = dpToPx(10).toFloat()
            setColor(palette.keyColor)
            setStroke(dpToPx(1), palette.borderColor)
        }
    }

    private fun activeController(): ProjectTerminalSessionController? {
        val currentId = activeSessionId ?: return null
        return sessionControllers[currentId]
    }

    private fun currentThemePreset(): ProjectTerminalThemePreset {
        return ProjectTerminalThemePreset.entries.firstOrNull { it.name == currentThemeName }
            ?: ProjectTerminalThemePreset.MIDNIGHT
    }

    private fun currentOverlayPalette(): OverlayPalette {
        val darkMode = when (uiController.themeMode) {
            com.murong.agent.ui.MurongThemeMode.DARK -> true
            com.murong.agent.ui.MurongThemeMode.LIGHT -> false
            com.murong.agent.ui.MurongThemeMode.SYSTEM -> {
                (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
            }
        }
        val backgroundCompose = com.murong.agent.ui.parseMurongColor(
            uiController.backgroundColorHex,
            defaultMurongBackgroundColor(darkMode)
        )
        val surfaceCompose = com.murong.agent.ui.parseMurongColor(
            uiController.surfaceColorHex,
            defaultMurongSurfaceColor(darkMode)
        )
        val mutedCompose = com.murong.agent.ui.parseMurongColor(
            uiController.mutedTextColorHex,
            defaultMurongMutedTextColor(darkMode)
        )
        val contentColor = if (murongIsDarkColor(backgroundCompose)) {
            Color.WHITE
        } else {
            Color.parseColor("#171B24")
        }
        val backgroundColor = withAlpha(backgroundCompose.toArgb(), if (darkMode) 245 else 242)
        val surfaceColor = surfaceCompose.toArgb()
        return OverlayPalette(
            backgroundColor = backgroundColor,
            borderColor = withAlpha(surfaceColor, 232),
            contentColor = contentColor,
            mutedTextColor = mutedCompose.toArgb(),
            buttonColor = withAlpha(surfaceColor, if (darkMode) 102 else 140),
            buttonHighlightColor = withAlpha(surfaceColor, if (darkMode) 150 else 186),
            tabSelectedColor = withAlpha(surfaceColor, if (darkMode) 160 else 176),
            tabIdleColor = withAlpha(backgroundCompose.toArgb(), if (darkMode) 214 else 224),
            keyColor = withAlpha(surfaceColor, if (darkMode) 118 else 150)
        )
    }

    private fun withAlpha(color: Int, alpha: Int): Int {
        return Color.argb(
            alpha.coerceIn(0, 255),
            Color.red(color),
            Color.green(color),
            Color.blue(color)
        )
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Murong 悬浮终端",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "保持系统悬浮终端在软件外继续显示"
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildOpenAppPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        return PendingIntent.getActivity(
            this,
            1001,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun dpToPx(value: Int): Int {
        return (value * resources.displayMetrics.density).roundToInt()
    }

    @Suppress("DEPRECATION")
    private fun overlayWindowType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    private fun canShowOverlay(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
    }

    companion object {
        private const val PREFS_NAME = "murong_terminal_overlay"
        private const val PREF_OVERLAY_ENABLED = "overlay_enabled"
        private const val ACTION_START = "com.murong.agent.overlay.START"
        private const val ACTION_UPDATE = "com.murong.agent.overlay.UPDATE"
        private const val ACTION_STOP = "com.murong.agent.overlay.STOP"
        private const val EXTRA_WORKING_DIRECTORY = "working_directory"
        private const val EXTRA_FONT_SIZE = "font_size"
        private const val EXTRA_THEME_NAME = "theme_name"
        private const val EXTRA_ENVIRONMENT_MODE = "environment_mode"
        private const val NOTIFICATION_CHANNEL_ID = "murong_terminal_overlay"
        private const val NOTIFICATION_ID = 2207

        @Volatile
        var isRunning: Boolean = false
            private set

        fun canDrawOverlays(context: Context): Boolean {
            return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)
        }

        fun isOverlayEnabled(context: Context): Boolean {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(PREF_OVERLAY_ENABLED, false)
        }

        fun createOverlayPermissionIntent(context: Context): Intent {
            return Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        fun startOverlay(
            context: Context,
            workingDirectory: String,
            fontSize: Int,
            themeName: String,
            environmentMode: ProjectTerminalEnvironmentMode
        ) {
            val intent = Intent(context, ProjectTerminalOverlayService::class.java).apply {
                action = if (isRunning) ACTION_UPDATE else ACTION_START
                putExtra(EXTRA_WORKING_DIRECTORY, workingDirectory)
                putExtra(EXTRA_FONT_SIZE, fontSize)
                putExtra(EXTRA_THEME_NAME, themeName)
                putExtra(EXTRA_ENVIRONMENT_MODE, environmentMode.name)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stopOverlay(context: Context) {
            persistOverlayEnabled(context, false)
            context.startService(
                Intent(context, ProjectTerminalOverlayService::class.java).apply {
                    action = ACTION_STOP
                }
            )
        }

        private fun persistOverlayEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_OVERLAY_ENABLED, enabled)
                .apply()
        }
    }
}
