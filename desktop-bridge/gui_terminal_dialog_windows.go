//go:build windowsgui && windows

package desktopbridge

import (
	"fmt"
	"strings"
	"syscall"
	"unsafe"
)

const (
	terminalDialogClassName = "MurongTerminalDialog"
	terminalDialogRowBase   = 5100
	terminalDialogSave      = 5201
	terminalDialogCancel    = 5202
)

type terminalDialog struct {
	gui      *nodeGUI
	hwnd     uintptr
	rows     []uintptr
	title    uintptr
	subtitle uintptr
	host     uintptr
	warning  uintptr
	save     uintptr
	cancel   uintptr
	selected map[string]bool
	accepted bool
	closed   bool
}

var (
	activeTerminalDialog          *terminalDialog
	terminalDialogClassRegistered bool
	terminalDialogProcedure       = syscall.NewCallback(func(hwnd uintptr, message uint32, wParam, lParam uintptr) uintptr {
		if activeTerminalDialog == nil {
			result, _, _ := procDefWindowProcW.Call(hwnd, uintptr(message), wParam, lParam)
			return result
		}
		return activeTerminalDialog.windowProc(hwnd, message, wParam, lParam)
	})
)

func (gui *nodeGUI) configureTerminals() {
	dialog := &terminalDialog{
		gui:      gui,
		selected: make(map[string]bool, len(gui.selectedTerminalIDs)),
	}
	for id, selected := range gui.selectedTerminalIDs {
		dialog.selected[id] = selected
	}
	if !dialog.run() {
		return
	}
	gui.selectedTerminalIDs = dialog.selected
	gui.loadedConfig.TerminalBackends = gui.selectedTerminalIDsInOrder()
	gui.loadedConfig.AllowTerminal = len(gui.loadedConfig.TerminalBackends) > 0
}

func (dialog *terminalDialog) run() bool {
	activeTerminalDialog = dialog
	defer func() { activeTerminalDialog = nil }()
	if !terminalDialogClassRegistered {
		cursor, _, _ := procLoadCursorW.Call(0, idcArrow)
		icon := loadApplicationIcon(dialog.gui.instance)
		class := wndClassEx{
			cbSize:        uint32(unsafe.Sizeof(wndClassEx{})),
			style:         0x0003,
			lpfnWndProc:   terminalDialogProcedure,
			hInstance:     dialog.gui.instance,
			hIcon:         icon,
			hCursor:       cursor,
			hbrBackground: dialog.gui.backgroundBrush,
			lpszClassName: utf16Ptr(terminalDialogClassName),
			hIconSm:       icon,
		}
		registered, _, registerErr := procRegisterClassExW.Call(uintptr(unsafe.Pointer(&class)))
		if registered == 0 {
			messageBox(dialog.gui.hwnd, guiTitle, "无法创建终端配置窗口："+registerErr.Error(), mbOK|mbIconError)
			return false
		}
		terminalDialogClassRegistered = true
	}
	rowCount := len(dialog.gui.terminalInventory.Backends)
	width := dialog.gui.scale(620)
	height := dialog.gui.scale(250 + rowCount*58)
	var ownerBounds rect
	procGetWindowRect.Call(dialog.gui.hwnd, uintptr(unsafe.Pointer(&ownerBounds)))
	x := int(ownerBounds.left) + (int(ownerBounds.right-ownerBounds.left)-width)/2
	y := int(ownerBounds.top) + (int(ownerBounds.bottom-ownerBounds.top)-height)/2
	style := uint32(wsOverlappedWindow &^ wsThickFrame &^ wsMinimizeBox &^ wsMaximizeBox)
	dialog.hwnd = createWindow(0, terminalDialogClassName, "配置电脑终端", style, int32(x), int32(y), int32(width), int32(height), dialog.gui.hwnd, 0, dialog.gui.instance)
	if dialog.hwnd == 0 {
		return false
	}
	procEnableWindow.Call(dialog.gui.hwnd, 0)
	procShowWindow.Call(dialog.hwnd, swShow)
	procUpdateWindow.Call(dialog.hwnd)
	var message msg
	for !dialog.closed {
		result, _, _ := procGetMessageW.Call(uintptr(unsafe.Pointer(&message)), 0, 0, 0)
		if result == 0 || int32(result) == -1 {
			break
		}
		procTranslateMessage.Call(uintptr(unsafe.Pointer(&message)))
		procDispatchMessageW.Call(uintptr(unsafe.Pointer(&message)))
	}
	return dialog.accepted
}

func (dialog *terminalDialog) windowProc(hwnd uintptr, message uint32, wParam, lParam uintptr) uintptr {
	switch message {
	case wmCreate:
		dialog.hwnd = hwnd
		dialog.createControls()
		return 0
	case wmPaint:
		dialog.paint()
		return 0
	case wmDrawItem:
		if dialog.drawItem((*drawItemStruct)(unsafe.Pointer(lParam))) {
			return 1
		}
	case wmCtlColorStatic:
		procSetBkMode.Call(wParam, transparent)
		if lParam == dialog.subtitle || lParam == dialog.host || lParam == dialog.warning {
			procSetTextColor.Call(wParam, uintptr(rgb(106, 115, 138)))
		} else {
			procSetTextColor.Call(wParam, uintptr(rgb(23, 27, 36)))
		}
		return dialog.gui.backgroundBrush
	case wmCommand:
		if highWord(wParam) == bnClicked {
			dialog.handleCommand(int(lowWord(wParam)))
		}
		return 0
	case wmClose:
		dialog.accepted = false
		procDestroyWindow.Call(hwnd)
		return 0
	case wmDestroy:
		dialog.closed = true
		procEnableWindow.Call(dialog.gui.hwnd, 1)
		procSetForegroundWindow.Call(dialog.gui.hwnd)
		return 0
	}
	result, _, _ := procDefWindowProcW.Call(hwnd, uintptr(message), wParam, lParam)
	return result
}

func (dialog *terminalDialog) createControls() {
	create := func(className, text string, style uint32, id int, font uintptr) uintptr {
		handle := createWindow(0, className, text, wsChild|wsVisible|style, 0, 0, 10, 10, dialog.hwnd, id, dialog.gui.instance)
		sendMessage(handle, wmSetFont, font, 1)
		return handle
	}
	dialog.title = create("STATIC", "选择允许手机调用的终端", ssLeft|ssCenterImage, 0, dialog.gui.titleFont)
	dialog.subtitle = create("STATIC", "可以同时启用多个 Shell，Agent 每次调用会明确选择其中一个。", ssLeft|ssCenterImage, 0, dialog.gui.font)
	hostText := dialog.gui.terminalInventory.WindowsTerminalHost
	if hostText == "" {
		hostText = "Windows Terminal 未检测到"
	}
	dialog.host = create("STATIC", hostText+" · 它是窗口宿主，不是可捕获输出的 Shell", ssLeft|ssCenterImage, 0, dialog.gui.smallFont)
	for index, backend := range dialog.gui.terminalInventory.Backends {
		row := create("BUTTON", backend.Label, bsOwnerDraw|wsTabStop, terminalDialogRowBase+index, dialog.gui.font)
		dialog.rows = append(dialog.rows, row)
	}
	dialog.warning = create("STATIC", "高风险：命令以当前 Windows 用户或所选 WSL 用户权限执行，可能访问工作区之外。每条调用仍由手机审批策略处理。", ssLeft, 0, dialog.gui.smallFont)
	dialog.cancel = create("BUTTON", "取消", bsOwnerDraw|wsTabStop, terminalDialogCancel, dialog.gui.font)
	dialog.save = create("BUTTON", "保存选择", bsOwnerDraw|wsTabStop, terminalDialogSave, dialog.gui.font)
	dialog.layout()
}

func (dialog *terminalDialog) layout() {
	s := dialog.gui.scale
	var client rect
	procGetClientRect.Call(dialog.hwnd, uintptr(unsafe.Pointer(&client)))
	width := int(client.right - client.left)
	margin := s(24)
	moveWindow(dialog.title, int32(margin), int32(s(18)), int32(width-2*margin), int32(s(36)))
	moveWindow(dialog.subtitle, int32(margin), int32(s(54)), int32(width-2*margin), int32(s(24)))
	moveWindow(dialog.host, int32(margin), int32(s(80)), int32(width-2*margin), int32(s(22)))
	y := s(114)
	for _, row := range dialog.rows {
		moveWindow(row, int32(margin), int32(y), int32(width-2*margin), int32(s(48)))
		y += s(58)
	}
	moveWindow(dialog.warning, int32(margin), int32(y+s(4)), int32(width-2*margin), int32(s(44)))
	buttonY := y + s(58)
	buttonWidth := s(118)
	moveWindow(dialog.cancel, int32(width-margin-buttonWidth*2-s(10)), int32(buttonY), int32(buttonWidth), int32(s(42)))
	moveWindow(dialog.save, int32(width-margin-buttonWidth), int32(buttonY), int32(buttonWidth), int32(s(42)))
}

func (dialog *terminalDialog) paint() {
	var paint paintStruct
	hdc, _, _ := procBeginPaint.Call(dialog.hwnd, uintptr(unsafe.Pointer(&paint)))
	if hdc == 0 {
		return
	}
	defer procEndPaint.Call(dialog.hwnd, uintptr(unsafe.Pointer(&paint)))
	var client rect
	procGetClientRect.Call(dialog.hwnd, uintptr(unsafe.Pointer(&client)))
	procFillRect.Call(hdc, uintptr(unsafe.Pointer(&client)), dialog.gui.backgroundBrush)
}

func (dialog *terminalDialog) drawItem(item *drawItemStruct) bool {
	if item == nil {
		return false
	}
	id := int(item.controlID)
	if id >= terminalDialogRowBase && id < terminalDialogRowBase+len(dialog.rows) {
		index := id - terminalDialogRowBase
		backend := dialog.gui.terminalInventory.Backends[index]
		selected := dialog.selected[backend.ID]
		fill, border := rgb(255, 255, 255), rgb(231, 234, 242)
		if selected {
			fill, border = rgb(255, 241, 248), rgb(255, 210, 232)
		}
		dialog.gui.fillRounded(item.hdc, item.itemRect, dialog.gui.scale(14), fill, border)
		labelBounds := item.itemRect
		labelBounds.left += int32(dialog.gui.scale(14))
		labelBounds.right -= int32(dialog.gui.scale(64))
		labelBounds.top += int32(dialog.gui.scale(6))
		labelBounds.bottom = labelBounds.top + int32(dialog.gui.scale(19))
		detailBounds := labelBounds
		detailBounds.top += int32(dialog.gui.scale(19))
		detailBounds.bottom += int32(dialog.gui.scale(19))
		detail := backend.ID
		if strings.TrimSpace(backend.Version) != "" {
			detail += " · " + backend.Version
		}
		dialog.gui.drawText(item.hdc, backend.Label, labelBounds, dialog.gui.font, rgb(23, 27, 36), dtLeft|dtVCenter|dtSingleLine|dtEndEllipsis)
		dialog.gui.drawText(item.hdc, detail, detailBounds, dialog.gui.smallFont, rgb(106, 115, 138), dtLeft|dtVCenter|dtSingleLine|dtEndEllipsis)
		dialog.drawSwitch(item.hdc, item.itemRect, selected)
		return true
	}
	if id == terminalDialogSave || id == terminalDialogCancel {
		primary := id == terminalDialogSave
		fill, border, textColor := rgb(255, 255, 255), rgb(225, 230, 240), rgb(69, 77, 96)
		if primary {
			fill, border, textColor = rgb(246, 99, 166), rgb(246, 99, 166), rgb(255, 255, 255)
		}
		if item.itemState&odsSelected != 0 {
			if primary {
				fill, border = rgb(224, 73, 142), rgb(224, 73, 142)
			} else {
				fill = rgb(245, 247, 252)
			}
		}
		dialog.gui.fillRounded(item.hdc, item.itemRect, dialog.gui.scale(14), fill, border)
		dialog.gui.drawText(item.hdc, windowText(item.hwndItem), item.itemRect, dialog.gui.font, textColor, dtCenter|dtVCenter|dtSingleLine)
		return true
	}
	return false
}

func (dialog *terminalDialog) drawSwitch(hdc uintptr, bounds rect, selected bool) {
	switchBounds := rect{
		left:   bounds.right - int32(dialog.gui.scale(50)),
		top:    bounds.top + int32(dialog.gui.scale(13)),
		right:  bounds.right - int32(dialog.gui.scale(12)),
		bottom: bounds.top + int32(dialog.gui.scale(35)),
	}
	color := rgb(205, 211, 224)
	if selected {
		color = rgb(246, 99, 166)
	}
	dialog.gui.fillRounded(hdc, switchBounds, dialog.gui.scale(18), color, color)
	dotSize := int32(dialog.gui.scale(16))
	dotLeft := switchBounds.left + int32(dialog.gui.scale(3))
	if selected {
		dotLeft = switchBounds.right - dotSize - int32(dialog.gui.scale(3))
	}
	dotBrush, _, _ := procCreateSolidBrush.Call(uintptr(rgb(255, 255, 255)))
	oldBrush, _, _ := procSelectObject.Call(hdc, dotBrush)
	oldPen, _, _ := procSelectObject.Call(hdc, mustStockObject(nullPen))
	procEllipse.Call(hdc, uintptr(dotLeft), uintptr(switchBounds.top+int32(dialog.gui.scale(3))), uintptr(dotLeft+dotSize), uintptr(switchBounds.top+int32(dialog.gui.scale(3))+dotSize))
	procSelectObject.Call(hdc, oldBrush)
	procSelectObject.Call(hdc, oldPen)
	procDeleteObject.Call(dotBrush)
}

func (dialog *terminalDialog) handleCommand(id int) {
	if id >= terminalDialogRowBase && id < terminalDialogRowBase+len(dialog.rows) {
		index := id - terminalDialogRowBase
		backend := dialog.gui.terminalInventory.Backends[index]
		dialog.selected[backend.ID] = !dialog.selected[backend.ID]
		procInvalidateRect.Call(dialog.rows[index], 0, 1)
		return
	}
	switch id {
	case terminalDialogSave:
		dialog.accepted = true
		procDestroyWindow.Call(dialog.hwnd)
	case terminalDialogCancel:
		dialog.accepted = false
		procDestroyWindow.Call(dialog.hwnd)
	default:
		_ = fmt.Sprintf("unknown terminal dialog command %d", id)
	}
}
