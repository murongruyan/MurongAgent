//go:build windowsgui && windows

package desktopbridge

import (
	"context"
	"errors"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"syscall"
	"time"
	"unicode/utf8"
	"unsafe"
)

const (
	guiClassName = "MurongWindowsNodeWindow"
	guiTitle     = "Murong Windows Node"

	idPhone         = 101
	idWorkspace     = 102
	idBrowse        = 103
	idLabel         = 104
	idClientName    = 105
	idPairCode      = 106
	idAllowWrite    = 107
	idAllowTerminal = 108
	idAutoStart     = 109
	idStartStop     = 110
	idClearPairing  = 111
	idStatus        = 112
	idPairingHint   = 113
	idLog           = 114

	idTrayOpen      = 4001
	idTrayStartStop = 4002
	idTrayExit      = 4003

	wmAppStatus         = wmApp + 1
	wmAppLog            = wmApp + 2
	wmAppRunnerFinished = wmApp + 3
	wmAppAutoStart      = wmApp + 4
	wmAppExitReady      = wmApp + 5
	wmAppTray           = wmApp + 10
)

type guiControls struct {
	header           uintptr
	subtitle         uintptr
	connectionGroup  uintptr
	phoneLabel       uintptr
	phone            uintptr
	workspaceLabel   uintptr
	workspace        uintptr
	browse           uintptr
	labelLabel       uintptr
	label            uintptr
	clientNameLabel  uintptr
	clientName       uintptr
	pairCodeLabel    uintptr
	pairCode         uintptr
	pairingHint      uintptr
	allowWrite       uintptr
	allowTerminal    uintptr
	autoStart        uintptr
	warning          uintptr
	permissionsTitle uintptr
	statusGroup      uintptr
	status           uintptr
	startStop        uintptr
	clearPairing     uintptr
	logGroup         uintptr
	log              uintptr
	footer           uintptr
}

type nodeGUI struct {
	hwnd                uintptr
	instance            uintptr
	dpi                 int
	font                uintptr
	titleFont           uintptr
	sectionFont         uintptr
	smallFont           uintptr
	logFont             uintptr
	backgroundBrush     uintptr
	surfaceBrush        uintptr
	inputBrush          uintptr
	logBrush            uintptr
	controls            guiControls
	configPath          string
	loadedConfig        nodeConfig
	terminalInventory   terminalInventory
	selectedTerminalIDs map[string]bool
	startupError        error
	autoStartLaunch     bool
	taskbarCreated      uint32
	trayAdded           bool
	phase               nodeConnectionPhase

	mu                sync.Mutex
	busy              bool
	exiting           bool
	cancel            context.CancelFunc
	runnerDone        chan struct{}
	pendingStatus     *nodeRuntimeStatus
	pendingOutcome    error
	logText           string
	allowWriteEnabled bool
	autoStartEnabled  bool
}

type guiLogWriter struct{ gui *nodeGUI }

var (
	activeGUI       *nodeGUI
	windowProcedure = syscall.NewCallback(func(hwnd uintptr, message uint32, wParam, lParam uintptr) uintptr {
		if activeGUI == nil {
			result, _, _ := procDefWindowProcW.Call(hwnd, uintptr(message), wParam, lParam)
			return result
		}
		return activeGUI.windowProc(hwnd, message, wParam, lParam)
	})
)

func RunGUI() {
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	_ = initializeCOM()
	defer procCoUninitialize.Call()
	procSetProcessDpiAwarenessContext.Call(^uintptr(3))

	configPath, err := defaultNodeConfigPath()
	if err != nil {
		messageBox(0, guiTitle, "无法确定配置目录："+err.Error(), mbOK|mbIconError)
		return
	}
	config, loadErr := loadNodeConfig(configPath)
	if loadErr != nil {
		config = nodeConfig{SchemaVersion: nodeConfigSchemaVersion}
	}
	inventoryContext, inventoryCancel := context.WithTimeout(context.Background(), 12*time.Second)
	inventory := discoverTerminalInventory(inventoryContext)
	inventoryCancel()
	resolvedConfig, _, terminalConfigErr := resolveConfiguredTerminals(config, inventory)
	if terminalConfigErr == nil {
		config = resolvedConfig
	}
	selectedTerminalIDs := make(map[string]bool, len(config.TerminalBackends))
	for _, id := range config.TerminalBackends {
		selectedTerminalIDs[id] = true
	}
	gui := &nodeGUI{
		configPath:          configPath,
		loadedConfig:        config,
		startupError:        errors.Join(loadErr, terminalConfigErr),
		terminalInventory:   inventory,
		selectedTerminalIDs: selectedTerminalIDs,
		autoStartLaunch:     hasArgument("--autostart"),
		phase:               nodePhaseStopped,
		allowWriteEnabled:   config.AllowWrite,
		autoStartEnabled:    autoStartEnabled(),
	}
	activeGUI = gui
	log.SetFlags(log.Ldate | log.Ltime)
	log.SetOutput(guiLogWriter{gui: gui})
	if err := gui.runMessageLoop(); err != nil {
		messageBox(0, guiTitle, err.Error(), mbOK|mbIconError)
	}
}

func hasArgument(expected string) bool {
	for _, argument := range os.Args[1:] {
		if strings.EqualFold(strings.TrimSpace(argument), expected) {
			return true
		}
	}
	return false
}

func (writer guiLogWriter) Write(data []byte) (int, error) {
	writer.gui.enqueueLog(string(data))
	return len(data), nil
}

func (gui *nodeGUI) runMessageLoop() error {
	instance, _, _ := procGetModuleHandleW.Call(0)
	gui.instance = instance
	dpi, _, _ := procGetDpiForSystem.Call()
	gui.dpi = int(dpi)
	if gui.dpi <= 0 {
		gui.dpi = 96
	}
	icon := loadApplicationIcon(instance)
	cursor, _, _ := procLoadCursorW.Call(0, idcArrow)
	gui.backgroundBrush, _, _ = procCreateSolidBrush.Call(uintptr(rgb(245, 247, 253)))
	className := utf16Ptr(guiClassName)
	class := wndClassEx{
		cbSize:        uint32(unsafe.Sizeof(wndClassEx{})),
		style:         0x0003,
		lpfnWndProc:   windowProcedure,
		hInstance:     instance,
		hIcon:         icon,
		hCursor:       cursor,
		hbrBackground: gui.backgroundBrush,
		lpszClassName: className,
		hIconSm:       icon,
	}
	registered, _, registerErr := procRegisterClassExW.Call(uintptr(unsafe.Pointer(&class)))
	if registered == 0 {
		return fmt.Errorf("无法注册 Windows 窗口：%w", registerErr)
	}
	width := int32(gui.scale(980))
	height := int32(gui.scale(800))
	hwnd := createWindow(
		0,
		guiClassName,
		guiTitle,
		wsOverlappedWindow|wsClipChildren,
		-2147483648,
		-2147483648,
		width,
		height,
		0,
		0,
		instance,
	)
	if hwnd == 0 {
		return errors.New("无法创建 Windows Node 主窗口")
	}
	gui.hwnd = hwnd
	gui.taskbarCreated = uint32(registerWindowMessage("TaskbarCreated"))
	gui.trayAdded = addOrUpdateTrayIcon(hwnd, wmAppTray, "Murong Windows Node · 未运行", true)
	if gui.autoStartLaunch {
		procShowWindow.Call(hwnd, swHide)
		procPostMessageW.Call(hwnd, wmAppAutoStart, 0, 0)
	} else {
		procShowWindow.Call(hwnd, swShow)
		procUpdateWindow.Call(hwnd)
	}
	if gui.startupError != nil {
		gui.enqueueLog("配置文件读取失败，将使用空配置：" + gui.startupError.Error() + "\r\n")
		gui.showMainWindow()
	}

	var message msg
	for {
		result, _, getErr := procGetMessageW.Call(uintptr(unsafe.Pointer(&message)), 0, 0, 0)
		if int32(result) == -1 {
			return fmt.Errorf("Windows 消息循环失败：%w", getErr)
		}
		if result == 0 {
			return nil
		}
		procTranslateMessage.Call(uintptr(unsafe.Pointer(&message)))
		procDispatchMessageW.Call(uintptr(unsafe.Pointer(&message)))
	}
}

func registerWindowMessage(name string) uintptr {
	result, _, _ := procRegisterWindowMessageW.Call(uintptr(unsafe.Pointer(utf16Ptr(name))))
	return result
}

func (gui *nodeGUI) windowProc(hwnd uintptr, message uint32, wParam, lParam uintptr) uintptr {
	if gui.taskbarCreated != 0 && message == gui.taskbarCreated {
		gui.trayAdded = addOrUpdateTrayIcon(hwnd, wmAppTray, gui.trayTooltip(), true)
		return 0
	}
	switch message {
	case wmCreate:
		gui.hwnd = hwnd
		gui.createControls()
		return 0
	case wmSize:
		if wParam == sizeMinimized {
			procShowWindow.Call(hwnd, swHide)
			return 0
		}
		gui.layoutControls()
		invalidateWindow(hwnd)
		return 0
	case wmPaint:
		gui.paintWindow()
		return 0
	case wmDpiChanged:
		gui.dpi = int(highWord(wParam))
		if gui.dpi <= 0 {
			gui.dpi = 96
		}
		suggested := (*rect)(unsafe.Pointer(lParam))
		procSetWindowPos.Call(
			hwnd, 0,
			uintptr(suggested.left), uintptr(suggested.top),
			uintptr(suggested.right-suggested.left), uintptr(suggested.bottom-suggested.top),
			0x0004,
		)
		gui.layoutControls()
		return 0
	case wmGetMinMaxInfo:
		info := (*minMaxInfo)(unsafe.Pointer(lParam))
		info.minTrackSize = point{x: int32(gui.scale(860)), y: int32(gui.scale(720))}
		return 0
	case wmDrawItem:
		if gui.drawControl((*drawItemStruct)(unsafe.Pointer(lParam))) {
			return 1
		}
	case wmCommand:
		if highWord(wParam) == bnClicked {
			gui.handleCommand(int(lowWord(wParam)))
		}
		return 0
	case wmCtlColorEdit:
		procSetBkMode.Call(wParam, opaque)
		if lParam == gui.controls.log {
			procSetTextColor.Call(wParam, uintptr(rgb(221, 228, 242)))
			procSetBkColor.Call(wParam, uintptr(rgb(21, 27, 41)))
			return gui.logBrush
		}
		procSetTextColor.Call(wParam, uintptr(rgb(23, 27, 36)))
		procSetBkColor.Call(wParam, uintptr(rgb(248, 249, 253)))
		return gui.inputBrush
	case wmCtlColorStatic:
		if lParam == gui.controls.log {
			procSetBkMode.Call(wParam, opaque)
			procSetTextColor.Call(wParam, uintptr(rgb(221, 228, 242)))
			procSetBkColor.Call(wParam, uintptr(rgb(21, 27, 41)))
			return gui.logBrush
		}
		procSetBkMode.Call(wParam, transparent)
		if lParam == gui.controls.status {
			procSetTextColor.Call(wParam, uintptr(gui.statusColor()))
		} else if lParam == gui.controls.subtitle || lParam == gui.controls.pairingHint || lParam == gui.controls.warning || lParam == gui.controls.footer {
			procSetTextColor.Call(wParam, uintptr(rgb(106, 115, 138)))
		} else {
			procSetTextColor.Call(wParam, uintptr(rgb(23, 27, 36)))
		}
		if lParam == gui.controls.header || lParam == gui.controls.subtitle || lParam == gui.controls.footer {
			return gui.backgroundBrush
		}
		return gui.surfaceBrush
	case wmClose:
		gui.hideMainWindow()
		return 0
	case wmDestroy:
		if gui.trayAdded {
			deleteTrayIcon(hwnd)
		}
		if gui.font != 0 {
			procDeleteObject.Call(gui.font)
		}
		if gui.titleFont != 0 {
			procDeleteObject.Call(gui.titleFont)
		}
		for _, object := range []uintptr{gui.sectionFont, gui.smallFont, gui.logFont, gui.surfaceBrush, gui.inputBrush, gui.logBrush, gui.backgroundBrush} {
			if object != 0 {
				procDeleteObject.Call(object)
			}
		}
		procPostQuitMessage.Call(0)
		return 0
	case wmAppStatus:
		gui.applyPendingStatus()
		return 0
	case wmAppLog:
		gui.applyLogText()
		return 0
	case wmAppRunnerFinished:
		gui.handleRunnerFinished()
		return 0
	case wmAppAutoStart:
		gui.startNode(true)
		return 0
	case wmAppExitReady:
		procDestroyWindow.Call(hwnd)
		return 0
	case wmAppTray:
		gui.handleTrayMessage(uint32(lParam))
		return 0
	}
	result, _, _ := procDefWindowProcW.Call(hwnd, uintptr(message), wParam, lParam)
	return result
}

func (gui *nodeGUI) createControls() {
	gui.font = createUIFont(10, false, gui.dpi)
	gui.titleFont = createUIFont(22, true, gui.dpi)
	gui.sectionFont = createUIFont(13, true, gui.dpi)
	gui.smallFont = createUIFont(9, false, gui.dpi)
	gui.logFont = createFont(9, false, gui.dpi, "Cascadia Mono")
	gui.surfaceBrush, _, _ = procCreateSolidBrush.Call(uintptr(rgb(255, 255, 255)))
	gui.inputBrush, _, _ = procCreateSolidBrush.Call(uintptr(rgb(248, 249, 253)))
	gui.logBrush, _, _ = procCreateSolidBrush.Call(uintptr(rgb(21, 27, 41)))
	create := func(exStyle uint32, className, text string, style uint32, id int) uintptr {
		handle := createWindow(exStyle, className, text, wsChild|wsVisible|style, 0, 0, 10, 10, gui.hwnd, id, gui.instance)
		sendMessage(handle, wmSetFont, gui.font, 1)
		return handle
	}
	controls := &gui.controls
	controls.header = create(0, "STATIC", "Murong Windows Node", ssLeft|ssCenterImage, 0)
	sendMessage(controls.header, wmSetFont, gui.titleFont, 1)
	controls.subtitle = create(0, "STATIC", "手机是唯一 Agent · Windows 只提供授权的文件与终端能力", ssLeft|ssCenterImage, 0)
	controls.connectionGroup = create(0, "STATIC", "连接设置", ssLeft|ssCenterImage, 0)
	sendMessage(controls.connectionGroup, wmSetFont, gui.sectionFont, 1)
	controls.phoneLabel = create(0, "STATIC", "手机地址", ssLeft|ssCenterImage, 0)
	controls.phone = create(0, "EDIT", gui.loadedConfig.PhoneURL, esAutoHScroll|wsTabStop, idPhone)
	sendMessage(controls.phone, emLimitText, 512, 0)
	controls.workspaceLabel = create(0, "STATIC", "电脑工作区", ssLeft|ssCenterImage, 0)
	controls.workspace = create(0, "EDIT", gui.loadedConfig.Workspace, esAutoHScroll|wsTabStop, idWorkspace)
	sendMessage(controls.workspace, emLimitText, 32767, 0)
	controls.browse = create(0, "BUTTON", "选择目录", bsOwnerDraw|wsTabStop, idBrowse)
	controls.labelLabel = create(0, "STATIC", "工作区名称", ssLeft|ssCenterImage, 0)
	controls.label = create(0, "EDIT", gui.loadedConfig.Label, esAutoHScroll|wsTabStop, idLabel)
	sendMessage(controls.label, emLimitText, 80, 0)
	controls.clientNameLabel = create(0, "STATIC", "电脑名称", ssLeft|ssCenterImage, 0)
	controls.clientName = create(0, "EDIT", gui.loadedConfig.ClientName, esAutoHScroll|wsTabStop, idClientName)
	sendMessage(controls.clientName, emLimitText, 40, 0)
	controls.pairCodeLabel = create(0, "STATIC", "一次性配对码", ssLeft|ssCenterImage, 0)
	controls.pairCode = create(0, "EDIT", "", esAutoHScroll|esUppercase|wsTabStop, idPairCode)
	sendMessage(controls.pairCode, emLimitText, 16, 0)
	controls.pairingHint = create(0, "STATIC", gui.pairingHintText(), ssLeft|ssCenterImage, idPairingHint)
	controls.warning = create(0, "STATIC", "访问令牌使用当前 Windows 用户的 DPAPI 加密保存。", ssLeft|ssCenterImage, 0)
	controls.permissionsTitle = create(0, "STATIC", "授权能力", ssLeft|ssCenterImage, 0)
	sendMessage(controls.permissionsTitle, wmSetFont, gui.sectionFont, 1)
	controls.allowWrite = create(0, "BUTTON", "文件写入", bsOwnerDraw|wsTabStop, idAllowWrite)
	controls.allowTerminal = create(0, "BUTTON", "电脑终端", bsOwnerDraw|wsTabStop, idAllowTerminal)
	controls.autoStart = create(0, "BUTTON", "登录后自动启动", bsOwnerDraw|wsTabStop, idAutoStart)

	controls.statusGroup = create(0, "STATIC", "节点状态", ssLeft|ssCenterImage, 0)
	sendMessage(controls.statusGroup, wmSetFont, gui.sectionFont, 1)
	controls.status = create(0, "STATIC", "● 未运行 · 配置完成后点击启动节点", ssLeft, idStatus)
	controls.startStop = create(0, "BUTTON", "启动节点", bsOwnerDraw|wsTabStop, idStartStop)
	controls.clearPairing = create(0, "BUTTON", "清除本机配对", bsOwnerDraw|wsTabStop, idClearPairing)

	controls.logGroup = create(0, "STATIC", "运行日志", ssLeft|ssCenterImage, 0)
	sendMessage(controls.logGroup, wmSetFont, gui.sectionFont, 1)
	controls.log = create(0, "EDIT", "", esMultiLine|esAutoVScroll|esReadOnly|wsVScroll, idLog)
	sendMessage(controls.log, wmSetFont, gui.logFont, 1)
	controls.footer = create(0, "STATIC", "关闭或最小化窗口会驻留托盘 · 手机仍是唯一 Agent", ssLeft|ssCenterImage, 0)
	gui.layoutControls()
	gui.enqueueLog("Murong Windows Node 已就绪。选择工作区并与手机配对后即可启动。\r\n")
}

func (gui *nodeGUI) layoutControls() {
	if gui.controls.header == 0 {
		return
	}
	var client rect
	procGetClientRect.Call(gui.hwnd, uintptr(unsafe.Pointer(&client)))
	w := int(client.right - client.left)
	h := int(client.bottom - client.top)
	s := gui.scale
	margin := s(24)
	contentWidth := w - 2*margin
	moveWindow(gui.controls.header, int32(margin+s(58)), int32(s(17)), int32(contentWidth-s(58)), int32(s(34)))
	moveWindow(gui.controls.subtitle, int32(margin+s(58)), int32(s(50)), int32(contentWidth-s(58)), int32(s(24)))

	cardsTop := s(92)
	cardHeight := s(420)
	rightWidth := s(304)
	gap := s(16)
	leftWidth := contentWidth - rightWidth - gap
	leftX := margin
	rightX := leftX + leftWidth + gap
	inner := s(24)
	moveWindow(gui.controls.connectionGroup, int32(leftX+inner), int32(cardsTop+s(16)), int32(leftWidth-2*inner), int32(s(30)))

	fieldLabelH := s(20)
	editH := s(24)
	editInset := s(10)
	placeField := func(label, edit uintptr, x, y, width int) {
		moveWindow(label, int32(x), int32(y), int32(width), int32(fieldLabelH))
		moveWindow(edit, int32(x+editInset), int32(y+s(27)), int32(width-2*editInset), int32(editH))
	}
	fullX := leftX + inner
	fullWidth := leftWidth - 2*inner
	placeField(gui.controls.phoneLabel, gui.controls.phone, fullX, cardsTop+s(58), fullWidth)
	browseWidth := s(102)
	placeField(gui.controls.workspaceLabel, gui.controls.workspace, fullX, cardsTop+s(124), fullWidth-browseWidth-s(10))
	moveWindow(gui.controls.browse, int32(fullX+fullWidth-browseWidth), int32(cardsTop+s(150)), int32(browseWidth), int32(s(36)))
	columnGap := s(16)
	columnWidth := (fullWidth - columnGap) / 2
	placeField(gui.controls.labelLabel, gui.controls.label, fullX, cardsTop+s(190), columnWidth)
	placeField(gui.controls.clientNameLabel, gui.controls.clientName, fullX+columnWidth+columnGap, cardsTop+s(190), columnWidth)
	placeField(gui.controls.pairCodeLabel, gui.controls.pairCode, fullX, cardsTop+s(256), fullWidth)
	moveWindow(gui.controls.pairingHint, int32(fullX), int32(cardsTop+s(317)), int32(fullWidth), int32(s(40)))
	moveWindow(gui.controls.warning, int32(fullX), int32(cardsTop+s(370)), int32(fullWidth), int32(s(28)))

	moveWindow(gui.controls.statusGroup, int32(rightX+inner), int32(cardsTop+s(16)), int32(rightWidth-2*inner), int32(s(30)))
	moveWindow(gui.controls.status, int32(rightX+inner), int32(cardsTop+s(51)), int32(rightWidth-2*inner), int32(s(38)))
	moveWindow(gui.controls.startStop, int32(rightX+inner), int32(cardsTop+s(94)), int32(rightWidth-2*inner), int32(s(46)))
	moveWindow(gui.controls.clearPairing, int32(rightX+inner), int32(cardsTop+s(150)), int32(rightWidth-2*inner), int32(s(38)))
	moveWindow(gui.controls.permissionsTitle, int32(rightX+inner), int32(cardsTop+s(209)), int32(rightWidth-2*inner), int32(s(30)))
	moveWindow(gui.controls.allowWrite, int32(rightX+inner), int32(cardsTop+s(246)), int32(rightWidth-2*inner), int32(s(48)))
	moveWindow(gui.controls.allowTerminal, int32(rightX+inner), int32(cardsTop+s(302)), int32(rightWidth-2*inner), int32(s(48)))
	moveWindow(gui.controls.autoStart, int32(rightX+inner), int32(cardsTop+s(358)), int32(rightWidth-2*inner), int32(s(48)))

	logY := cardsTop + cardHeight + gap
	footerH := s(30)
	logH := h - logY - footerH - s(10)
	if logH < s(124) {
		logH = s(124)
	}
	moveWindow(gui.controls.logGroup, int32(margin+inner), int32(logY+s(13)), int32(contentWidth-2*inner), int32(s(30)))
	moveWindow(gui.controls.log, int32(margin+inner+s(10)), int32(logY+s(52)), int32(contentWidth-2*inner-s(20)), int32(logH-s(68)))
	moveWindow(gui.controls.footer, int32(margin), int32(logY+logH+s(2)), int32(contentWidth), int32(footerH))
}

func (gui *nodeGUI) paintWindow() {
	var paint paintStruct
	hdc, _, _ := procBeginPaint.Call(gui.hwnd, uintptr(unsafe.Pointer(&paint)))
	if hdc == 0 {
		return
	}
	defer procEndPaint.Call(gui.hwnd, uintptr(unsafe.Pointer(&paint)))
	var client rect
	procGetClientRect.Call(gui.hwnd, uintptr(unsafe.Pointer(&client)))
	procFillRect.Call(hdc, uintptr(unsafe.Pointer(&client)), gui.backgroundBrush)

	s := gui.scale
	margin := int32(s(24))
	contentWidth := client.right - 2*margin
	cardsTop := int32(s(92))
	cardHeight := int32(s(420))
	rightWidth := int32(s(304))
	gap := int32(s(16))
	leftWidth := contentWidth - rightWidth - gap
	rightX := margin + leftWidth + gap
	logY := cardsTop + cardHeight + gap
	footerH := int32(s(30))
	logH := client.bottom - logY - footerH - int32(s(10))
	if logH < int32(s(124)) {
		logH = int32(s(124))
	}

	gui.fillRounded(hdc, rect{margin, cardsTop, margin + leftWidth, cardsTop + cardHeight}, s(24), rgb(255, 255, 255), rgb(231, 234, 242))
	gui.fillRounded(hdc, rect{rightX, cardsTop, rightX + rightWidth, cardsTop + cardHeight}, s(24), rgb(255, 255, 255), rgb(231, 234, 242))
	gui.fillRounded(hdc, rect{margin, logY, margin + contentWidth, logY + logH}, s(24), rgb(255, 255, 255), rgb(231, 234, 242))

	logo := rect{margin, int32(s(20)), margin + int32(s(44)), int32(s(64))}
	gui.fillRounded(hdc, logo, s(15), rgb(246, 99, 166), rgb(246, 99, 166))
	gui.drawText(hdc, "M", logo, gui.sectionFont, rgb(255, 255, 255), dtCenter|dtVCenter|dtSingleLine)

	for _, edit := range []uintptr{gui.controls.phone, gui.controls.workspace, gui.controls.label, gui.controls.clientName, gui.controls.pairCode} {
		bounds := childRect(gui.hwnd, edit)
		bounds.left -= int32(s(10))
		bounds.right += int32(s(10))
		bounds.top -= int32(s(6))
		bounds.bottom += int32(s(6))
		gui.fillRounded(hdc, bounds, s(12), rgb(248, 249, 253), rgb(225, 230, 240))
	}
	logBounds := childRect(gui.hwnd, gui.controls.log)
	logBounds.left -= int32(s(10))
	logBounds.right += int32(s(10))
	logBounds.top -= int32(s(8))
	logBounds.bottom += int32(s(8))
	gui.fillRounded(hdc, logBounds, s(16), rgb(21, 27, 41), rgb(21, 27, 41))
}

func (gui *nodeGUI) fillRounded(hdc uintptr, bounds rect, radius int, fill, border uint32) {
	brush, _, _ := procCreateSolidBrush.Call(uintptr(fill))
	pen, _, _ := procCreatePen.Call(psSolid, 1, uintptr(border))
	oldBrush, _, _ := procSelectObject.Call(hdc, brush)
	oldPen, _, _ := procSelectObject.Call(hdc, pen)
	procRoundRect.Call(hdc, uintptr(bounds.left), uintptr(bounds.top), uintptr(bounds.right), uintptr(bounds.bottom), uintptr(radius), uintptr(radius))
	procSelectObject.Call(hdc, oldBrush)
	procSelectObject.Call(hdc, oldPen)
	procDeleteObject.Call(brush)
	procDeleteObject.Call(pen)
}

func (gui *nodeGUI) drawText(hdc uintptr, text string, bounds rect, font uintptr, color uint32, format uint32) {
	oldFont, _, _ := procSelectObject.Call(hdc, font)
	procSetBkMode.Call(hdc, transparent)
	procSetTextColor.Call(hdc, uintptr(color))
	value, _ := syscall.UTF16FromString(text)
	procDrawTextW.Call(hdc, uintptr(unsafe.Pointer(&value[0])), uintptr(len(value)-1), uintptr(unsafe.Pointer(&bounds)), uintptr(format))
	procSelectObject.Call(hdc, oldFont)
}

func (gui *nodeGUI) drawControl(item *drawItemStruct) bool {
	if item == nil {
		return false
	}
	id := int(item.controlID)
	switch id {
	case idBrowse, idStartStop, idClearPairing:
		disabled := item.itemState&odsDisabled != 0
		pressed := item.itemState&odsSelected != 0
		fill := rgb(255, 255, 255)
		border := rgb(225, 230, 240)
		textColor := rgb(69, 77, 96)
		if id == idStartStop {
			fill = rgb(246, 99, 166)
			border = fill
			textColor = rgb(255, 255, 255)
			if pressed {
				fill, border = rgb(224, 73, 142), rgb(224, 73, 142)
			}
		} else if id == idBrowse {
			fill = rgb(255, 239, 247)
			border = rgb(255, 218, 236)
			textColor = rgb(202, 59, 124)
			if pressed {
				fill = rgb(255, 224, 239)
			}
		} else if pressed {
			fill = rgb(245, 247, 252)
		}
		if disabled {
			fill, border, textColor = rgb(239, 241, 246), rgb(231, 234, 241), rgb(163, 170, 187)
		}
		gui.fillRounded(item.hdc, item.itemRect, gui.scale(14), fill, border)
		gui.drawText(item.hdc, windowText(item.hwndItem), item.itemRect, gui.font, textColor, dtCenter|dtVCenter|dtSingleLine|dtEndEllipsis)
		return true
	case idAllowWrite, idAllowTerminal, idAutoStart:
		if id == idAllowTerminal {
			gui.drawTerminalSelector(item)
			return true
		}
		checked := gui.toggleValue(id)
		disabled := item.itemState&odsDisabled != 0
		fill := rgb(248, 249, 253)
		border := rgb(231, 234, 242)
		if checked {
			fill, border = rgb(255, 241, 248), rgb(255, 210, 232)
		}
		if disabled {
			fill, border = rgb(243, 244, 248), rgb(235, 237, 242)
		}
		gui.fillRounded(item.hdc, item.itemRect, gui.scale(14), fill, border)
		label := windowText(item.hwndItem)
		detail := map[int]string{
			idAllowWrite: "修改文件与创建目录",
			idAutoStart:  "启动后驻留系统托盘",
		}[id]
		labelBounds := item.itemRect
		labelBounds.left += int32(gui.scale(14))
		labelBounds.right -= int32(gui.scale(58))
		labelBounds.top += int32(gui.scale(6))
		labelBounds.bottom = labelBounds.top + int32(gui.scale(19))
		detailBounds := labelBounds
		detailBounds.top += int32(gui.scale(19))
		detailBounds.bottom += int32(gui.scale(19))
		labelColor := rgb(23, 27, 36)
		detailColor := rgb(106, 115, 138)
		if disabled {
			labelColor, detailColor = rgb(154, 160, 174), rgb(179, 184, 195)
		}
		gui.drawText(item.hdc, label, labelBounds, gui.font, labelColor, dtLeft|dtVCenter|dtSingleLine|dtEndEllipsis)
		gui.drawText(item.hdc, detail, detailBounds, gui.smallFont, detailColor, dtLeft|dtVCenter|dtSingleLine|dtEndEllipsis)
		switchBounds := rect{
			left:   item.itemRect.right - int32(gui.scale(48)),
			top:    item.itemRect.top + int32(gui.scale(13)),
			right:  item.itemRect.right - int32(gui.scale(12)),
			bottom: item.itemRect.top + int32(gui.scale(35)),
		}
		switchColor := rgb(205, 211, 224)
		if checked {
			switchColor = rgb(246, 99, 166)
		}
		if disabled {
			switchColor = rgb(218, 221, 229)
		}
		gui.fillRounded(item.hdc, switchBounds, gui.scale(18), switchColor, switchColor)
		dotSize := int32(gui.scale(16))
		dotLeft := switchBounds.left + int32(gui.scale(3))
		if checked {
			dotLeft = switchBounds.right - dotSize - int32(gui.scale(3))
		}
		dotBrush, _, _ := procCreateSolidBrush.Call(uintptr(rgb(255, 255, 255)))
		oldBrush, _, _ := procSelectObject.Call(item.hdc, dotBrush)
		oldPen, _, _ := procSelectObject.Call(item.hdc, mustStockObject(nullPen))
		procEllipse.Call(item.hdc, uintptr(dotLeft), uintptr(switchBounds.top+int32(gui.scale(3))), uintptr(dotLeft+dotSize), uintptr(switchBounds.top+int32(gui.scale(3))+dotSize))
		procSelectObject.Call(item.hdc, oldBrush)
		procSelectObject.Call(item.hdc, oldPen)
		procDeleteObject.Call(dotBrush)
		return true
	}
	return false
}

func (gui *nodeGUI) drawTerminalSelector(item *drawItemStruct) {
	selectedCount := len(gui.selectedTerminalIDsInOrder())
	disabled := item.itemState&odsDisabled != 0
	fill, border := rgb(248, 249, 253), rgb(231, 234, 242)
	if selectedCount > 0 {
		fill, border = rgb(255, 241, 248), rgb(255, 210, 232)
	}
	if disabled {
		fill, border = rgb(243, 244, 248), rgb(235, 237, 242)
	}
	gui.fillRounded(item.hdc, item.itemRect, gui.scale(14), fill, border)
	labelBounds := item.itemRect
	labelBounds.left += int32(gui.scale(14))
	labelBounds.right -= int32(gui.scale(64))
	labelBounds.top += int32(gui.scale(6))
	labelBounds.bottom = labelBounds.top + int32(gui.scale(19))
	detailBounds := labelBounds
	detailBounds.top += int32(gui.scale(19))
	detailBounds.bottom += int32(gui.scale(19))
	labelColor, detailColor := rgb(23, 27, 36), rgb(106, 115, 138)
	if disabled {
		labelColor, detailColor = rgb(154, 160, 174), rgb(179, 184, 195)
	}
	gui.drawText(item.hdc, "电脑终端", labelBounds, gui.font, labelColor, dtLeft|dtVCenter|dtSingleLine|dtEndEllipsis)
	gui.drawText(item.hdc, gui.terminalSummary(), detailBounds, gui.smallFont, detailColor, dtLeft|dtVCenter|dtSingleLine|dtEndEllipsis)
	pill := rect{
		left:   item.itemRect.right - int32(gui.scale(57)),
		top:    item.itemRect.top + int32(gui.scale(11)),
		right:  item.itemRect.right - int32(gui.scale(10)),
		bottom: item.itemRect.bottom - int32(gui.scale(11)),
	}
	pillFill, pillBorder, pillText := rgb(255, 255, 255), rgb(225, 230, 240), rgb(202, 59, 124)
	if disabled {
		pillFill, pillBorder, pillText = rgb(239, 241, 246), rgb(231, 234, 241), rgb(163, 170, 187)
	}
	gui.fillRounded(item.hdc, pill, gui.scale(12), pillFill, pillBorder)
	gui.drawText(item.hdc, "配置", pill, gui.smallFont, pillText, dtCenter|dtVCenter|dtSingleLine)
}

func (gui *nodeGUI) selectedTerminalIDsInOrder() []string {
	result := make([]string, 0, len(gui.selectedTerminalIDs))
	for _, backend := range gui.terminalInventory.Backends {
		if gui.selectedTerminalIDs[backend.ID] {
			result = append(result, backend.ID)
		}
	}
	return result
}

func (gui *nodeGUI) terminalSummary() string {
	selected := gui.selectedTerminalIDsInOrder()
	if len(selected) == 0 {
		return "未启用 · 点击配置"
	}
	labels := make([]string, 0, len(selected))
	for _, id := range selected {
		for _, backend := range gui.terminalInventory.Backends {
			if backend.ID == id {
				labels = append(labels, backend.Label)
				break
			}
		}
	}
	return strings.Join(labels, " · ")
}

func mustStockObject(id int) uintptr {
	object, _, _ := procGetStockObject.Call(uintptr(id))
	return object
}

func (gui *nodeGUI) toggleValue(id int) bool {
	switch id {
	case idAllowWrite:
		return gui.allowWriteEnabled
	case idAutoStart:
		return gui.autoStartEnabled
	default:
		return false
	}
}

func (gui *nodeGUI) invalidateControl(handle uintptr) {
	if handle != 0 {
		procInvalidateRect.Call(handle, 0, 1)
	}
}

func (gui *nodeGUI) scale(value int) int {
	return value * gui.dpi / 96
}

func (gui *nodeGUI) handleCommand(id int) {
	switch id {
	case idBrowse:
		if selected, ok := browseForFolder(gui.hwnd, "选择允许手机 Murong 访问的电脑工作区", windowText(gui.controls.workspace)); ok {
			setWindowText(gui.controls.workspace, selected)
			if strings.TrimSpace(windowText(gui.controls.label)) == "" {
				setWindowText(gui.controls.label, filepath.Base(selected))
			}
		}
	case idStartStop:
		if gui.isBusy() {
			gui.stopNode()
		} else {
			gui.startNode(false)
		}
	case idClearPairing:
		gui.clearPairing()
	case idAllowWrite:
		gui.allowWriteEnabled = !gui.allowWriteEnabled
		gui.invalidateControl(gui.controls.allowWrite)
	case idAllowTerminal:
		gui.configureTerminals()
		gui.invalidateControl(gui.controls.allowTerminal)
	case idAutoStart:
		gui.autoStartEnabled = !gui.autoStartEnabled
		if err := setAutoStart(gui.autoStartEnabled); err != nil {
			gui.autoStartEnabled = !gui.autoStartEnabled
			messageBox(gui.hwnd, guiTitle, "无法更新开机启动："+err.Error(), mbOK|mbIconError)
		}
		gui.invalidateControl(gui.controls.autoStart)
	}
}

func (gui *nodeGUI) startNode(fromAutoStart bool) {
	if gui.isBusy() {
		return
	}
	config := gui.loadedConfig
	phone := strings.TrimSpace(windowText(gui.controls.phone))
	if config.PhoneURL != "" && phone != config.PhoneURL {
		config.ProtectedToken = ""
	}
	config.PhoneURL = phone
	config.Workspace = strings.TrimSpace(windowText(gui.controls.workspace))
	config.Label = strings.TrimSpace(windowText(gui.controls.label))
	config.ClientName = strings.TrimSpace(windowText(gui.controls.clientName))
	config.AllowWrite = gui.allowWriteEnabled
	config.TerminalBackends = gui.selectedTerminalIDsInOrder()
	config.AllowTerminal = len(config.TerminalBackends) > 0
	pairCode := strings.TrimSpace(windowText(gui.controls.pairCode))
	if err := setAutoStart(gui.autoStartEnabled); err != nil {
		messageBox(gui.hwnd, guiTitle, "无法更新开机启动："+err.Error(), mbOK|mbIconError)
		return
	}

	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	gui.mu.Lock()
	gui.busy = true
	gui.cancel = cancel
	gui.runnerDone = done
	gui.pendingOutcome = nil
	gui.mu.Unlock()
	gui.setConfigurationEnabled(false)
	setWindowText(gui.controls.startStop, "停止节点")
	gui.postStatus(nodeRuntimeStatus{Phase: nodePhaseConnecting, Message: "正在验证配置并连接手机…"})
	gui.enqueueLog("正在启动 Windows Node…\r\n")

	go func() {
		defer close(done)
		prepareContext, prepareCancel := context.WithTimeout(ctx, 30*time.Second)
		launch, err := prepareNode(prepareContext, gui.configPath, config, pairCode)
		prepareCancel()
		if err == nil {
			launch.node.onStatus = gui.postStatus
			gui.mu.Lock()
			gui.loadedConfig = launch.config
			gui.mu.Unlock()
			log.Printf("Murong Windows Node 已启动")
			log.Printf("手机节点：%s", launch.target.Redacted())
			log.Printf("电脑工作区：%s", launch.workspace.root)
			log.Printf("能力：读取=开启 写入=%s 电脑终端=%s", onOff(launch.config.AllowWrite), strings.Join(launch.config.TerminalBackends, ", "))
			err = launch.node.run(ctx)
		}
		gui.mu.Lock()
		gui.pendingOutcome = err
		gui.mu.Unlock()
		procPostMessageW.Call(gui.hwnd, wmAppRunnerFinished, 0, 0)
	}()
}

func (gui *nodeGUI) stopNode() {
	gui.mu.Lock()
	cancel := gui.cancel
	gui.mu.Unlock()
	if cancel != nil {
		gui.postStatus(nodeRuntimeStatus{Phase: nodePhaseConnecting, Message: "正在安全停止节点…"})
		cancel()
	}
}

func (gui *nodeGUI) handleRunnerFinished() {
	gui.mu.Lock()
	err := gui.pendingOutcome
	gui.pendingOutcome = nil
	gui.cancel = nil
	gui.runnerDone = nil
	gui.busy = false
	exiting := gui.exiting
	config := gui.loadedConfig
	gui.mu.Unlock()
	if exiting {
		procDestroyWindow.Call(gui.hwnd)
		return
	}
	gui.setConfigurationEnabled(true)
	setWindowText(gui.controls.startStop, "启动节点")
	setWindowText(gui.controls.pairCode, "")
	setWindowText(gui.controls.pairingHint, gui.pairingHintForConfig(config))
	if err == nil || errors.Is(err, context.Canceled) {
		gui.applyStatus(nodeRuntimeStatus{Phase: nodePhaseStopped, Message: "节点已停止"})
		return
	}
	gui.applyStatus(nodeRuntimeStatus{Phase: nodePhaseError, Message: err.Error()})
	gui.enqueueLog("节点停止：" + err.Error() + "\r\n")
	gui.showMainWindow()
	messageBox(gui.hwnd, "Windows Node 启动失败", err.Error(), mbOK|mbIconError)
}

func (gui *nodeGUI) clearPairing() {
	if gui.isBusy() {
		messageBox(gui.hwnd, guiTitle, "请先停止 Windows Node，再清除本机配对。", mbOK|mbIconInfo)
		return
	}
	gui.mu.Lock()
	config := gui.loadedConfig
	gui.mu.Unlock()
	if config.ProtectedToken == "" {
		messageBox(gui.hwnd, guiTitle, "本机没有已保存的配对凭据。", mbOK|mbIconInfo)
		return
	}
	if messageBox(gui.hwnd, "清除本机配对？", "这只会删除本机 DPAPI 凭据。手机端的已配对客户端可在 Murong 设置中撤销。", mbYesNo|mbIconWarning) != idYes {
		return
	}
	updated, err := clearNodePairing(gui.configPath, config)
	if err != nil {
		messageBox(gui.hwnd, guiTitle, "无法清除本机配对："+err.Error(), mbOK|mbIconError)
		return
	}
	gui.mu.Lock()
	gui.loadedConfig = updated
	gui.mu.Unlock()
	setWindowText(gui.controls.pairCode, "")
	setWindowText(gui.controls.pairingHint, gui.pairingHintForConfig(updated))
	gui.enqueueLog("已清除本机配对凭据。\r\n")
}

func (gui *nodeGUI) setConfigurationEnabled(enabled bool) {
	for _, handle := range []uintptr{
		gui.controls.phone,
		gui.controls.workspace,
		gui.controls.browse,
		gui.controls.label,
		gui.controls.clientName,
		gui.controls.pairCode,
		gui.controls.allowWrite,
		gui.controls.allowTerminal,
		gui.controls.clearPairing,
	} {
		enableWindow(handle, enabled)
	}
}

func (gui *nodeGUI) pairingHintText() string {
	return gui.pairingHintForConfig(gui.loadedConfig)
}

func (gui *nodeGUI) pairingHintForConfig(config nodeConfig) string {
	if config.ProtectedToken != "" {
		return "已保存配对凭据（Windows DPAPI 加密）；配对码留空即可继续使用。"
	}
	return "尚未配对：先在手机生成一次性配对码，再填写并启动。"
}

func (gui *nodeGUI) isBusy() bool {
	gui.mu.Lock()
	defer gui.mu.Unlock()
	return gui.busy
}

func (gui *nodeGUI) postStatus(status nodeRuntimeStatus) {
	gui.mu.Lock()
	gui.pendingStatus = &status
	gui.mu.Unlock()
	if gui.hwnd != 0 {
		procPostMessageW.Call(gui.hwnd, wmAppStatus, 0, 0)
	}
}

func (gui *nodeGUI) applyPendingStatus() {
	gui.mu.Lock()
	status := gui.pendingStatus
	gui.pendingStatus = nil
	gui.mu.Unlock()
	if status != nil {
		gui.applyStatus(*status)
	}
}

func (gui *nodeGUI) applyStatus(status nodeRuntimeStatus) {
	gui.phase = status.Phase
	prefix := "● 未运行"
	switch status.Phase {
	case nodePhaseConnecting:
		prefix = "● 正在连接"
	case nodePhaseConnected:
		prefix = "● 已连接"
	case nodePhaseReconnecting:
		prefix = "● 正在重连"
	case nodePhaseError:
		prefix = "● 连接错误"
	}
	text := prefix
	if strings.TrimSpace(status.Message) != "" {
		text += " · " + status.Message
	}
	setWindowText(gui.controls.status, text)
	if gui.trayAdded {
		addOrUpdateTrayIcon(gui.hwnd, wmAppTray, gui.trayTooltip(), false)
	}
}

func (gui *nodeGUI) statusColor() uint32 {
	switch gui.phase {
	case nodePhaseConnected:
		return 0x004F7A1C
	case nodePhaseConnecting, nodePhaseReconnecting:
		return 0x00008AD9
	case nodePhaseError:
		return 0x002929D9
	default:
		return 0x00606060
	}
}

func (gui *nodeGUI) trayTooltip() string {
	switch gui.phase {
	case nodePhaseConnected:
		return "Murong Windows Node · 已连接"
	case nodePhaseConnecting, nodePhaseReconnecting:
		return "Murong Windows Node · 正在连接"
	case nodePhaseError:
		return "Murong Windows Node · 连接错误"
	default:
		return "Murong Windows Node · 未运行"
	}
}

func (gui *nodeGUI) enqueueLog(text string) {
	text = strings.ReplaceAll(text, "\r\n", "\n")
	text = strings.ReplaceAll(text, "\r", "\n")
	text = strings.ReplaceAll(text, "\n", "\r\n")
	gui.mu.Lock()
	gui.logText += text
	if len(gui.logText) > 100_000 {
		gui.logText = truncateLogPrefix(gui.logText, 80_000)
	}
	gui.mu.Unlock()
	if gui.hwnd != 0 {
		procPostMessageW.Call(gui.hwnd, wmAppLog, 0, 0)
	}
}

func truncateLogPrefix(value string, keep int) string {
	if len(value) <= keep {
		return value
	}
	data := []byte(value[len(value)-keep:])
	for len(data) > 0 && !utf8.Valid(data) {
		data = data[1:]
	}
	return "…（更早日志已截断）\r\n" + string(data)
}

func (gui *nodeGUI) applyLogText() {
	gui.mu.Lock()
	text := gui.logText
	gui.mu.Unlock()
	setWindowText(gui.controls.log, text)
	sendMessage(gui.controls.log, emSetSel, ^uintptr(0), ^uintptr(0))
}

func (gui *nodeGUI) handleTrayMessage(mouseMessage uint32) {
	switch mouseMessage {
	case wmLButtonDblClk:
		gui.showMainWindow()
	case wmRButtonUp:
		gui.showTrayMenu()
	}
}

func (gui *nodeGUI) showTrayMenu() {
	menu, _, _ := procCreatePopupMenu.Call()
	if menu == 0 {
		return
	}
	defer procDestroyMenu.Call(menu)
	appendMenu(menu, mfString, idTrayOpen, "打开 Murong Windows Node")
	appendMenu(menu, mfSeparator, 0, "")
	if gui.isBusy() {
		appendMenu(menu, mfString, idTrayStartStop, "停止节点")
	} else {
		appendMenu(menu, mfString, idTrayStartStop, "启动节点")
	}
	appendMenu(menu, mfSeparator, 0, "")
	appendMenu(menu, mfString, idTrayExit, "退出")
	var cursor point
	procGetCursorPos.Call(uintptr(unsafe.Pointer(&cursor)))
	procSetForegroundWindow.Call(gui.hwnd)
	command, _, _ := procTrackPopupMenu.Call(
		menu,
		tpmRightButton|tpmReturnCmd,
		uintptr(cursor.x), uintptr(cursor.y), 0,
		gui.hwnd, 0,
	)
	procPostMessageW.Call(gui.hwnd, wmNull, 0, 0)
	switch int(command) {
	case idTrayOpen:
		gui.showMainWindow()
	case idTrayStartStop:
		if gui.isBusy() {
			gui.stopNode()
		} else {
			gui.startNode(false)
		}
	case idTrayExit:
		gui.requestExit()
	}
}

func appendMenu(menu uintptr, flags uint32, id int, text string) {
	var pointer uintptr
	if text != "" {
		pointer = uintptr(unsafe.Pointer(utf16Ptr(text)))
	}
	procAppendMenuW.Call(menu, uintptr(flags), uintptr(id), pointer)
}

func (gui *nodeGUI) showMainWindow() {
	procShowWindow.Call(gui.hwnd, swRestore)
	procSetForegroundWindow.Call(gui.hwnd)
	procUpdateWindow.Call(gui.hwnd)
}

func (gui *nodeGUI) hideMainWindow() {
	procShowWindow.Call(gui.hwnd, swHide)
}

func (gui *nodeGUI) requestExit() {
	gui.mu.Lock()
	if gui.exiting {
		gui.mu.Unlock()
		return
	}
	gui.exiting = true
	cancel := gui.cancel
	done := gui.runnerDone
	gui.mu.Unlock()
	if cancel != nil {
		cancel()
	}
	if done == nil {
		procDestroyWindow.Call(gui.hwnd)
		return
	}
	go func() {
		select {
		case <-done:
		case <-time.After(5 * time.Second):
		}
		procPostMessageW.Call(gui.hwnd, wmAppExitReady, 0, 0)
	}()
}
