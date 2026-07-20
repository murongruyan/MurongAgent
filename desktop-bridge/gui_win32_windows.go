//go:build windowsgui && windows

package desktopbridge

import (
	"errors"
	"fmt"
	"os"
	"strings"
	"syscall"
	"unsafe"
)

const (
	wsOverlappedWindow = 0x00CF0000
	wsThickFrame       = 0x00040000
	wsMinimizeBox      = 0x00020000
	wsMaximizeBox      = 0x00010000
	wsChild            = 0x40000000
	wsVisible          = 0x10000000
	wsTabStop          = 0x00010000
	wsVScroll          = 0x00200000
	wsClipChildren     = 0x02000000

	wsExClientEdge = 0x00000200

	bsPushButton    = 0x00000000
	bsDefPushButton = 0x00000001
	bsAutoCheckBox  = 0x00000003
	bsGroupBox      = 0x00000007
	bsOwnerDraw     = 0x0000000B

	esAutoHScroll = 0x00000080
	esMultiLine   = 0x00000004
	esAutoVScroll = 0x00000040
	esReadOnly    = 0x00000800
	esUppercase   = 0x00000008

	ssLeft        = 0x00000000
	ssCenterImage = 0x00000200

	wmCreate         = 0x0001
	wmDestroy        = 0x0002
	wmPaint          = 0x000F
	wmSize           = 0x0005
	wmSetFocus       = 0x0007
	wmClose          = 0x0010
	wmGetMinMaxInfo  = 0x0024
	wmDrawItem       = 0x002B
	wmCommand        = 0x0111
	wmSysCommand     = 0x0112
	wmCtlColorEdit   = 0x0133
	wmCtlColorStatic = 0x0138
	wmDpiChanged     = 0x02E0
	wmNull           = 0x0000
	wmSetFont        = 0x0030
	wmApp            = 0x8000
	wmLButtonDblClk  = 0x0203
	wmRButtonUp      = 0x0205

	sizeMinimized = 1

	swHide    = 0
	swShow    = 5
	swRestore = 9

	bnClicked = 0

	bmGetCheck = 0x00F0
	bmSetCheck = 0x00F1
	bstChecked = 1

	emSetSel     = 0x00B1
	emReplaceSel = 0x00C2
	emLimitText  = 0x00C5

	colorBtnFace = 15
	colorWindow  = 5

	idcArrow                  = 32512
	idiApplication            = 32512
	applicationIconResourceID = 1

	cwUseDefault = ^uintptr(0x7fffffff)

	tpmRightButton = 0x0002
	tpmReturnCmd   = 0x0100
	mfString       = 0x0000
	mfSeparator    = 0x0800
	mfGrayed       = 0x0001

	nimAdd     = 0x00000000
	nimModify  = 0x00000001
	nimDelete  = 0x00000002
	nifMessage = 0x00000001
	nifIcon    = 0x00000002
	nifTip     = 0x00000004

	mbOK          = 0x00000000
	mbIconError   = 0x00000010
	mbIconWarning = 0x00000030
	mbIconInfo    = 0x00000040
	mbYesNo       = 0x00000004
	idYes         = 6

	bifReturnOnlyFSDirs = 0x00000001
	bifEditBox          = 0x00000010
	bifNewDialogStyle   = 0x00000040
	bifUseNewUI         = bifEditBox | bifNewDialogStyle
	bffmInitialized     = 1
	bffmSetSelectionW   = 0x467

	coinitApartmentThreaded = 0x2
	clsctxInprocServer      = 0x1
	fosPickFolders          = 0x00000020
	fosForceFileSystem      = 0x00000040
	fosPathMustExist        = 0x00000800
	sigdnFileSystemPath     = 0x80058000

	keyQueryValue = 0x0001
	keySetValue   = 0x0002
	regSZ         = 1

	defaultGUIFont = 17
	transparent    = 1
	opaque         = 2
	dtLeft         = 0x00000000
	dtCenter       = 0x00000001
	dtVCenter      = 0x00000004
	dtSingleLine   = 0x00000020
	dtEndEllipsis  = 0x00008000
	psSolid        = 0
	odsSelected    = 0x0001
	odsDisabled    = 0x0004
	hollowBrush    = 5
	nullPen        = 8
)

const hkeyCurrentUser = uintptr(0x80000001)

var (
	user32   = syscall.NewLazyDLL("user32.dll")
	gdi32    = syscall.NewLazyDLL("gdi32.dll")
	shell32  = syscall.NewLazyDLL("shell32.dll")
	ole32    = syscall.NewLazyDLL("ole32.dll")
	advapi32 = syscall.NewLazyDLL("advapi32.dll")

	procRegisterClassExW              = user32.NewProc("RegisterClassExW")
	procCreateWindowExW               = user32.NewProc("CreateWindowExW")
	procDefWindowProcW                = user32.NewProc("DefWindowProcW")
	procShowWindow                    = user32.NewProc("ShowWindow")
	procUpdateWindow                  = user32.NewProc("UpdateWindow")
	procGetMessageW                   = user32.NewProc("GetMessageW")
	procTranslateMessage              = user32.NewProc("TranslateMessage")
	procDispatchMessageW              = user32.NewProc("DispatchMessageW")
	procPostQuitMessage               = user32.NewProc("PostQuitMessage")
	procPostMessageW                  = user32.NewProc("PostMessageW")
	procSendMessageW                  = user32.NewProc("SendMessageW")
	procDestroyWindow                 = user32.NewProc("DestroyWindow")
	procSetWindowTextW                = user32.NewProc("SetWindowTextW")
	procGetWindowTextW                = user32.NewProc("GetWindowTextW")
	procGetWindowTextLengthW          = user32.NewProc("GetWindowTextLengthW")
	procEnableWindow                  = user32.NewProc("EnableWindow")
	procMoveWindow                    = user32.NewProc("MoveWindow")
	procGetClientRect                 = user32.NewProc("GetClientRect")
	procGetWindowRect                 = user32.NewProc("GetWindowRect")
	procMapWindowPoints               = user32.NewProc("MapWindowPoints")
	procSetForegroundWindow           = user32.NewProc("SetForegroundWindow")
	procIsWindowVisible               = user32.NewProc("IsWindowVisible")
	procMessageBoxW                   = user32.NewProc("MessageBoxW")
	procLoadCursorW                   = user32.NewProc("LoadCursorW")
	procLoadIconW                     = user32.NewProc("LoadIconW")
	procGetModuleHandleW              = syscall.NewLazyDLL("kernel32.dll").NewProc("GetModuleHandleW")
	procGetStockObject                = gdi32.NewProc("GetStockObject")
	procCreateSolidBrush              = gdi32.NewProc("CreateSolidBrush")
	procCreatePen                     = gdi32.NewProc("CreatePen")
	procCreateFontW                   = gdi32.NewProc("CreateFontW")
	procDeleteObject                  = gdi32.NewProc("DeleteObject")
	procSelectObject                  = gdi32.NewProc("SelectObject")
	procRoundRect                     = gdi32.NewProc("RoundRect")
	procEllipse                       = gdi32.NewProc("Ellipse")
	procFillRect                      = user32.NewProc("FillRect")
	procDrawTextW                     = user32.NewProc("DrawTextW")
	procBeginPaint                    = user32.NewProc("BeginPaint")
	procEndPaint                      = user32.NewProc("EndPaint")
	procInvalidateRect                = user32.NewProc("InvalidateRect")
	procSetTextColor                  = gdi32.NewProc("SetTextColor")
	procSetBkColor                    = gdi32.NewProc("SetBkColor")
	procSetBkMode                     = gdi32.NewProc("SetBkMode")
	procGetSysColorBrush              = user32.NewProc("GetSysColorBrush")
	procGetDpiForSystem               = user32.NewProc("GetDpiForSystem")
	procGetDpiForWindow               = user32.NewProc("GetDpiForWindow")
	procSetProcessDpiAwarenessContext = user32.NewProc("SetProcessDpiAwarenessContext")
	procSetWindowPos                  = user32.NewProc("SetWindowPos")
	procRegisterWindowMessageW        = user32.NewProc("RegisterWindowMessageW")
	procCreatePopupMenu               = user32.NewProc("CreatePopupMenu")
	procAppendMenuW                   = user32.NewProc("AppendMenuW")
	procTrackPopupMenu                = user32.NewProc("TrackPopupMenu")
	procDestroyMenu                   = user32.NewProc("DestroyMenu")
	procGetCursorPos                  = user32.NewProc("GetCursorPos")

	procShellNotifyIconW            = shell32.NewProc("Shell_NotifyIconW")
	procSHBrowseForFolderW          = shell32.NewProc("SHBrowseForFolderW")
	procSHGetPathFromIDListW        = shell32.NewProc("SHGetPathFromIDListW")
	procSHCreateItemFromParsingName = shell32.NewProc("SHCreateItemFromParsingName")
	procCoTaskMemFree               = ole32.NewProc("CoTaskMemFree")
	procCoInitializeEx              = ole32.NewProc("CoInitializeEx")
	procCoUninitialize              = ole32.NewProc("CoUninitialize")
	procCoCreateInstance            = ole32.NewProc("CoCreateInstance")

	procRegOpenKeyExW   = advapi32.NewProc("RegOpenKeyExW")
	procRegCreateKeyExW = advapi32.NewProc("RegCreateKeyExW")
	procRegSetValueExW  = advapi32.NewProc("RegSetValueExW")
	procRegDeleteValueW = advapi32.NewProc("RegDeleteValueW")
	procRegCloseKey     = advapi32.NewProc("RegCloseKey")
)

type point struct {
	x int32
	y int32
}

type rect struct {
	left   int32
	top    int32
	right  int32
	bottom int32
}

type msg struct {
	hwnd     uintptr
	message  uint32
	wParam   uintptr
	lParam   uintptr
	time     uint32
	pt       point
	lPrivate uint32
}

type wndClassEx struct {
	cbSize        uint32
	style         uint32
	lpfnWndProc   uintptr
	cbClsExtra    int32
	cbWndExtra    int32
	hInstance     uintptr
	hIcon         uintptr
	hCursor       uintptr
	hbrBackground uintptr
	lpszMenuName  *uint16
	lpszClassName *uint16
	hIconSm       uintptr
}

type minMaxInfo struct {
	reserved     point
	maxSize      point
	maxPosition  point
	minTrackSize point
	maxTrackSize point
}

type paintStruct struct {
	hdc       uintptr
	erase     int32
	paint     rect
	restore   int32
	incUpdate int32
	reserved  [32]byte
}

type drawItemStruct struct {
	controlType uint32
	controlID   uint32
	itemID      uint32
	itemAction  uint32
	itemState   uint32
	hwndItem    uintptr
	hdc         uintptr
	itemRect    rect
	itemData    uintptr
}

type browseInfo struct {
	owner       uintptr
	root        uintptr
	displayName *uint16
	title       *uint16
	flags       uint32
	callback    uintptr
	param       uintptr
	image       int32
}

type winGUID struct {
	data1 uint32
	data2 uint16
	data3 uint16
	data4 [8]byte
}

var (
	clsidFileOpenDialog = winGUID{0xDC1C5A9C, 0xE88A, 0x4DDE, [8]byte{0xA5, 0xA1, 0x60, 0xF8, 0x2A, 0x20, 0xAE, 0xF7}}
	iidFileOpenDialog   = winGUID{0xD57C7288, 0xD4AD, 0x4768, [8]byte{0xBE, 0x02, 0x9D, 0x96, 0x95, 0x32, 0xD9, 0x60}}
	iidShellItem        = winGUID{0x43826D1E, 0xE718, 0x42EE, [8]byte{0xBC, 0x55, 0xA1, 0xE2, 0x61, 0xC3, 0x7B, 0xFE}}
)

type notifyIconData struct {
	cbSize           uint32
	hwnd             uintptr
	id               uint32
	flags            uint32
	callbackMessage  uint32
	icon             uintptr
	tip              [128]uint16
	state            uint32
	stateMask        uint32
	info             [256]uint16
	timeoutOrVersion uint32
	infoTitle        [64]uint16
	infoFlags        uint32
	guid             [16]byte
	balloonIcon      uintptr
}

func utf16Ptr(value string) *uint16 {
	pointer, _ := syscall.UTF16PtrFromString(value)
	return pointer
}

func lowWord(value uintptr) uint16  { return uint16(value & 0xffff) }
func highWord(value uintptr) uint16 { return uint16((value >> 16) & 0xffff) }

func createWindow(exStyle uint32, className, text string, style uint32, x, y, width, height int32, parent uintptr, id int, instance uintptr) uintptr {
	handle, _, _ := procCreateWindowExW.Call(
		uintptr(exStyle),
		uintptr(unsafe.Pointer(utf16Ptr(className))),
		uintptr(unsafe.Pointer(utf16Ptr(text))),
		uintptr(style),
		uintptr(x), uintptr(y), uintptr(width), uintptr(height),
		parent, uintptr(id), instance, 0,
	)
	return handle
}

func setWindowText(handle uintptr, value string) {
	procSetWindowTextW.Call(handle, uintptr(unsafe.Pointer(utf16Ptr(value))))
}

func windowText(handle uintptr) string {
	length, _, _ := procGetWindowTextLengthW.Call(handle)
	buffer := make([]uint16, int(length)+1)
	procGetWindowTextW.Call(handle, uintptr(unsafe.Pointer(&buffer[0])), uintptr(len(buffer)))
	return syscall.UTF16ToString(buffer)
}

func sendMessage(handle uintptr, message uint32, wParam, lParam uintptr) uintptr {
	result, _, _ := procSendMessageW.Call(handle, uintptr(message), wParam, lParam)
	return result
}

func setChecked(handle uintptr, checked bool) {
	value := uintptr(0)
	if checked {
		value = bstChecked
	}
	sendMessage(handle, bmSetCheck, value, 0)
}

func isChecked(handle uintptr) bool {
	return sendMessage(handle, bmGetCheck, 0, 0) == bstChecked
}

func enableWindow(handle uintptr, enabled bool) {
	value := uintptr(0)
	if enabled {
		value = 1
	}
	procEnableWindow.Call(handle, value)
}

func moveWindow(handle uintptr, x, y, width, height int32) {
	procMoveWindow.Call(handle, uintptr(x), uintptr(y), uintptr(width), uintptr(height), 1)
}

func messageBox(owner uintptr, title, text string, flags uint32) int {
	result, _, _ := procMessageBoxW.Call(
		owner,
		uintptr(unsafe.Pointer(utf16Ptr(text))),
		uintptr(unsafe.Pointer(utf16Ptr(title))),
		uintptr(flags),
	)
	return int(result)
}

func createUIFont(pointSize int, bold bool, dpi int) uintptr {
	return createFont(pointSize, bold, dpi, "Segoe UI")
}

func createFont(pointSize int, bold bool, dpi int, family string) uintptr {
	weight := 400
	if bold {
		weight = 600
	}
	height := -pointSize * dpi / 72
	font, _, _ := procCreateFontW.Call(
		uintptr(height), 0, 0, 0, uintptr(weight), 0, 0, 0,
		1, 0, 0, 5, 0,
		uintptr(unsafe.Pointer(utf16Ptr(family))),
	)
	return font
}

func rgb(red, green, blue byte) uint32 {
	return uint32(red) | uint32(green)<<8 | uint32(blue)<<16
}

func childRect(parent, child uintptr) rect {
	var bounds rect
	procGetWindowRect.Call(child, uintptr(unsafe.Pointer(&bounds)))
	procMapWindowPoints.Call(0, parent, uintptr(unsafe.Pointer(&bounds)), 2)
	return bounds
}

func invalidateWindow(hwnd uintptr) {
	procInvalidateRect.Call(hwnd, 0, 1)
}

func loadApplicationIcon(instance uintptr) uintptr {
	if instance == 0 {
		instance, _, _ = procGetModuleHandleW.Call(0)
	}
	icon, _, _ := procLoadIconW.Call(instance, applicationIconResourceID)
	if icon == 0 {
		icon, _, _ = procLoadIconW.Call(0, idiApplication)
	}
	return icon
}

func browseForFolder(owner uintptr, title, initial string) (string, bool) {
	var dialog uintptr
	hr, _, _ := procCoCreateInstance.Call(
		uintptr(unsafe.Pointer(&clsidFileOpenDialog)),
		0,
		clsctxInprocServer,
		uintptr(unsafe.Pointer(&iidFileOpenDialog)),
		uintptr(unsafe.Pointer(&dialog)),
	)
	if !hresultSucceeded(hr) || dialog == 0 {
		return "", false
	}
	defer comRelease(dialog)
	var options uint32
	if result := comInvoke(dialog, 10, uintptr(unsafe.Pointer(&options))); !hresultSucceeded(result) {
		return "", false
	}
	options |= fosPickFolders | fosForceFileSystem | fosPathMustExist
	if result := comInvoke(dialog, 9, uintptr(options)); !hresultSucceeded(result) {
		return "", false
	}
	comInvoke(dialog, 17, uintptr(unsafe.Pointer(utf16Ptr(title))))
	if initial = strings.TrimSpace(initial); initial != "" {
		var folder uintptr
		result, _, _ := procSHCreateItemFromParsingName.Call(
			uintptr(unsafe.Pointer(utf16Ptr(initial))),
			0,
			uintptr(unsafe.Pointer(&iidShellItem)),
			uintptr(unsafe.Pointer(&folder)),
		)
		if hresultSucceeded(result) && folder != 0 {
			comInvoke(dialog, 12, folder)
			comRelease(folder)
		}
	}
	if result := comInvoke(dialog, 3, owner); !hresultSucceeded(result) {
		return "", false
	}
	var selected uintptr
	if result := comInvoke(dialog, 20, uintptr(unsafe.Pointer(&selected))); !hresultSucceeded(result) || selected == 0 {
		return "", false
	}
	defer comRelease(selected)
	var pathPointer uintptr
	if result := comInvoke(selected, 5, sigdnFileSystemPath, uintptr(unsafe.Pointer(&pathPointer))); !hresultSucceeded(result) || pathPointer == 0 {
		return "", false
	}
	defer procCoTaskMemFree.Call(pathPointer)
	return utf16PointerString(pathPointer), true
}

func comInvoke(object uintptr, method int, arguments ...uintptr) uintptr {
	if object == 0 {
		return ^uintptr(0)
	}
	virtualTable := *(*uintptr)(unsafe.Pointer(object))
	function := *(*uintptr)(unsafe.Pointer(virtualTable + uintptr(method)*unsafe.Sizeof(uintptr(0))))
	callArguments := make([]uintptr, 0, len(arguments)+1)
	callArguments = append(callArguments, object)
	callArguments = append(callArguments, arguments...)
	result, _, _ := syscall.SyscallN(function, callArguments...)
	return result
}

func comRelease(object uintptr) {
	if object != 0 {
		comInvoke(object, 2)
	}
}

func hresultSucceeded(result uintptr) bool {
	return int32(uint32(result)) >= 0
}

func utf16PointerString(pointer uintptr) string {
	if pointer == 0 {
		return ""
	}
	value := make([]uint16, 0, 260)
	for index := uintptr(0); index < 32768; index++ {
		character := *(*uint16)(unsafe.Pointer(pointer + index*2))
		if character == 0 {
			break
		}
		value = append(value, character)
	}
	return syscall.UTF16ToString(value)
}

func addOrUpdateTrayIcon(hwnd uintptr, callbackMessage uint32, tooltip string, add bool) bool {
	icon := loadApplicationIcon(0)
	data := notifyIconData{
		cbSize:          uint32(unsafe.Sizeof(notifyIconData{})),
		hwnd:            hwnd,
		id:              1,
		flags:           nifMessage | nifIcon | nifTip,
		callbackMessage: callbackMessage,
		icon:            icon,
	}
	copy(data.tip[:], syscall.StringToUTF16(tooltip))
	action := uintptr(nimModify)
	if add {
		action = nimAdd
	}
	result, _, _ := procShellNotifyIconW.Call(action, uintptr(unsafe.Pointer(&data)))
	return result != 0
}

func deleteTrayIcon(hwnd uintptr) {
	data := notifyIconData{cbSize: uint32(unsafe.Sizeof(notifyIconData{})), hwnd: hwnd, id: 1}
	procShellNotifyIconW.Call(nimDelete, uintptr(unsafe.Pointer(&data)))
}

func autoStartEnabled() bool {
	key, err := openRunRegistryKey(keyQueryValue, false)
	if err != nil {
		return false
	}
	defer procRegCloseKey.Call(key)
	name := utf16Ptr("MurongWindowsNode")
	result, _, _ := advapi32.NewProc("RegQueryValueExW").Call(key, uintptr(unsafe.Pointer(name)), 0, 0, 0, 0)
	return result == 0
}

func setAutoStart(enabled bool) error {
	key, err := openRunRegistryKey(keySetValue, enabled)
	if err != nil {
		return err
	}
	defer procRegCloseKey.Call(key)
	name := utf16Ptr("MurongWindowsNode")
	if !enabled {
		result, _, callErr := procRegDeleteValueW.Call(key, uintptr(unsafe.Pointer(name)))
		if result != 0 && result != 2 {
			return fmt.Errorf("RegDeleteValueW: %w", callErr)
		}
		return nil
	}
	executable, err := os.Executable()
	if err != nil {
		return err
	}
	command := `"` + executable + `" --autostart`
	data := syscall.StringToUTF16(command)
	result, _, callErr := procRegSetValueExW.Call(
		key,
		uintptr(unsafe.Pointer(name)),
		0,
		regSZ,
		uintptr(unsafe.Pointer(&data[0])),
		uintptr(len(data)*2),
	)
	if result != 0 {
		return fmt.Errorf("RegSetValueExW: %w", callErr)
	}
	return nil
}

func openRunRegistryKey(access uint32, create bool) (uintptr, error) {
	path := utf16Ptr(`Software\Microsoft\Windows\CurrentVersion\Run`)
	var key uintptr
	if create {
		var disposition uint32
		result, _, callErr := procRegCreateKeyExW.Call(
			hkeyCurrentUser,
			uintptr(unsafe.Pointer(path)),
			0, 0, 0,
			uintptr(access),
			0,
			uintptr(unsafe.Pointer(&key)),
			uintptr(unsafe.Pointer(&disposition)),
		)
		if result != 0 {
			return 0, fmt.Errorf("RegCreateKeyExW: %w", callErr)
		}
		return key, nil
	}
	result, _, callErr := procRegOpenKeyExW.Call(
		hkeyCurrentUser,
		uintptr(unsafe.Pointer(path)),
		0,
		uintptr(access),
		uintptr(unsafe.Pointer(&key)),
	)
	if result != 0 {
		return 0, fmt.Errorf("RegOpenKeyExW: %w", callErr)
	}
	return key, nil
}

func initializeCOM() error {
	result, _, _ := procCoInitializeEx.Call(0, coinitApartmentThreaded)
	if result != 0 && result != 1 {
		return errors.New("CoInitializeEx failed")
	}
	return nil
}
