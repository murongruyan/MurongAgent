//go:build windows

package main

import (
	"errors"
	"fmt"
	goruntime "runtime"
	"sync"
	"syscall"
	"time"
	"unsafe"
)

const (
	trayWindowClass = "MurongDesktopAgentTrayWindowV1"
	trayTooltip     = "Murong Desktop Agent"

	trayWMClose          = 0x0010
	trayWMDestroy        = 0x0002
	trayWMNull           = 0x0000
	trayWMApp            = 0x8000
	trayWMCallback       = trayWMApp + 71
	trayWMLButtonUp      = 0x0202
	trayWMLButtonDblClk  = 0x0203
	trayWMRButtonUp      = 0x0205
	trayNIMAdd           = 0x00000000
	trayNIMDelete        = 0x00000002
	trayNIFMessage       = 0x00000001
	trayNIFIcon          = 0x00000002
	trayNIFTip           = 0x00000004
	trayTPMRightButton   = 0x0002
	trayTPMReturnCommand = 0x0100
	trayMFString         = 0x0000
	trayMFSeparator      = 0x0800
	trayIDOpen           = 5101
	trayIDExit           = 5102
	trayIDIApplication   = 32512
	trayErrorClassExists = syscall.Errno(1410)
)

var (
	trayUser32                 = syscall.NewLazyDLL("user32.dll")
	trayShell32                = syscall.NewLazyDLL("shell32.dll")
	trayRegisterClassEx        = trayUser32.NewProc("RegisterClassExW")
	trayCreateWindowEx         = trayUser32.NewProc("CreateWindowExW")
	trayDefWindowProc          = trayUser32.NewProc("DefWindowProcW")
	trayDestroyWindow          = trayUser32.NewProc("DestroyWindow")
	trayGetMessage             = trayUser32.NewProc("GetMessageW")
	trayTranslateMessage       = trayUser32.NewProc("TranslateMessage")
	trayDispatchMessage        = trayUser32.NewProc("DispatchMessageW")
	trayPostQuitMessage        = trayUser32.NewProc("PostQuitMessage")
	trayPostMessage            = trayUser32.NewProc("PostMessageW")
	trayRegisterWindowMessage  = trayUser32.NewProc("RegisterWindowMessageW")
	trayCreatePopupMenu        = trayUser32.NewProc("CreatePopupMenu")
	trayAppendMenu             = trayUser32.NewProc("AppendMenuW")
	trayTrackPopupMenu         = trayUser32.NewProc("TrackPopupMenu")
	trayDestroyMenu            = trayUser32.NewProc("DestroyMenu")
	trayGetCursorPos           = trayUser32.NewProc("GetCursorPos")
	traySetForegroundWindow    = trayUser32.NewProc("SetForegroundWindow")
	trayLoadIcon               = trayUser32.NewProc("LoadIconW")
	trayShellNotifyIcon        = trayShell32.NewProc("Shell_NotifyIconW")
	trayWindowProcedurePointer = syscall.NewCallback(applicationTrayWindowProcedure)
	trayWindows                sync.Map
)

type windowsApplicationTray struct {
	mu             sync.Mutex
	hwnd           uintptr
	iconAdded      bool
	taskbarCreated uint32
	onOpen         func()
	onExit         func()
	ready          chan error
	done           chan struct{}
	closeOnce      sync.Once
}

type trayWindowClassEx struct {
	cbSize      uint32
	style       uint32
	wndProc     uintptr
	classExtra  int32
	windowExtra int32
	instance    uintptr
	icon        uintptr
	cursor      uintptr
	background  uintptr
	menuName    *uint16
	className   *uint16
	iconSmall   uintptr
}

type trayNotifyIconData struct {
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

type trayPoint struct {
	x int32
	y int32
}

type trayMessage struct {
	hwnd    uintptr
	message uint32
	wParam  uintptr
	lParam  uintptr
	time    uint32
	point   trayPoint
	private uint32
}

func startApplicationTray(onOpen, onExit func()) (applicationTray, error) {
	tray := &windowsApplicationTray{
		onOpen: onOpen,
		onExit: onExit,
		ready:  make(chan error, 1),
		done:   make(chan struct{}),
	}
	go tray.run()
	select {
	case err := <-tray.ready:
		if err != nil {
			return nil, err
		}
		return tray, nil
	case <-time.After(5 * time.Second):
		tray.Close()
		return nil, errors.New("创建 Windows 系统托盘超时")
	}
}

func (tray *windowsApplicationTray) run() {
	goruntime.LockOSThread()
	defer goruntime.UnlockOSThread()
	defer close(tray.done)

	instance, _, _ := getModuleHandle.Call(0)
	if instance == 0 {
		tray.ready <- errors.New("无法取得应用实例")
		return
	}
	className, err := syscall.UTF16PtrFromString(trayWindowClass)
	if err != nil {
		tray.ready <- err
		return
	}
	class := trayWindowClassEx{
		cbSize:    uint32(unsafe.Sizeof(trayWindowClassEx{})),
		wndProc:   trayWindowProcedurePointer,
		instance:  instance,
		className: className,
	}
	registered, _, registerErr := trayRegisterClassEx.Call(uintptr(unsafe.Pointer(&class)))
	if registered == 0 && !errors.Is(registerErr, trayErrorClassExists) {
		tray.ready <- fmt.Errorf("注册托盘窗口失败：%w", registerErr)
		return
	}
	hwnd, _, createErr := trayCreateWindowEx.Call(
		0,
		uintptr(unsafe.Pointer(className)),
		uintptr(unsafe.Pointer(className)),
		0,
		0, 0, 0, 0,
		0, 0, instance, 0,
	)
	if hwnd == 0 {
		tray.ready <- fmt.Errorf("创建托盘窗口失败：%w", createErr)
		return
	}
	tray.mu.Lock()
	tray.hwnd = hwnd
	tray.mu.Unlock()
	trayWindows.Store(hwnd, tray)
	defer trayWindows.Delete(hwnd)

	taskbarName, _ := syscall.UTF16PtrFromString("TaskbarCreated")
	message, _, _ := trayRegisterWindowMessage.Call(uintptr(unsafe.Pointer(taskbarName)))
	tray.taskbarCreated = uint32(message)
	if !tray.addIcon() {
		trayDestroyWindow.Call(hwnd)
		tray.ready <- errors.New("Windows 拒绝添加托盘图标")
		return
	}
	tray.ready <- nil

	var messageData trayMessage
	for {
		result, _, _ := trayGetMessage.Call(uintptr(unsafe.Pointer(&messageData)), 0, 0, 0)
		if int32(result) <= 0 {
			break
		}
		trayTranslateMessage.Call(uintptr(unsafe.Pointer(&messageData)))
		trayDispatchMessage.Call(uintptr(unsafe.Pointer(&messageData)))
	}
	tray.removeIcon()
	tray.mu.Lock()
	tray.hwnd = 0
	tray.mu.Unlock()
}

func (tray *windowsApplicationTray) Close() {
	tray.closeOnce.Do(func() {
		tray.mu.Lock()
		hwnd := tray.hwnd
		tray.mu.Unlock()
		if hwnd != 0 {
			trayPostMessage.Call(hwnd, trayWMClose, 0, 0)
		}
	})
	select {
	case <-tray.done:
	case <-time.After(2 * time.Second):
	}
}

func applicationTrayWindowProcedure(hwnd uintptr, message uint32, wParam, lParam uintptr) uintptr {
	value, ok := trayWindows.Load(hwnd)
	if !ok {
		result, _, _ := trayDefWindowProc.Call(hwnd, uintptr(message), wParam, lParam)
		return result
	}
	tray := value.(*windowsApplicationTray)
	if tray.taskbarCreated != 0 && message == tray.taskbarCreated {
		tray.addIcon()
		return 0
	}
	switch message {
	case trayWMCallback:
		switch uint32(lParam) {
		case trayWMLButtonUp, trayWMLButtonDblClk:
			if tray.onOpen != nil {
				go tray.onOpen()
			}
		case trayWMRButtonUp:
			tray.showMenu()
		}
		return 0
	case trayWMClose:
		tray.removeIcon()
		trayDestroyWindow.Call(hwnd)
		return 0
	case trayWMDestroy:
		trayPostQuitMessage.Call(0)
		return 0
	}
	result, _, _ := trayDefWindowProc.Call(hwnd, uintptr(message), wParam, lParam)
	return result
}

func (tray *windowsApplicationTray) addIcon() bool {
	tray.mu.Lock()
	defer tray.mu.Unlock()
	if tray.hwnd == 0 {
		return false
	}
	instance, _, _ := getModuleHandle.Call(0)
	icon := loadApplicationIcon(instance, smCXSmallIcon, smCYSmallIcon)
	if icon == 0 {
		icon, _, _ = trayLoadIcon.Call(0, trayIDIApplication)
	}
	data := trayNotifyIconData{
		cbSize:          uint32(unsafe.Sizeof(trayNotifyIconData{})),
		hwnd:            tray.hwnd,
		id:              1,
		flags:           trayNIFMessage | trayNIFIcon | trayNIFTip,
		callbackMessage: trayWMCallback,
		icon:            icon,
	}
	copy(data.tip[:], syscall.StringToUTF16(trayTooltip))
	result, _, _ := trayShellNotifyIcon.Call(trayNIMAdd, uintptr(unsafe.Pointer(&data)))
	tray.iconAdded = result != 0
	return tray.iconAdded
}

func (tray *windowsApplicationTray) removeIcon() {
	tray.mu.Lock()
	defer tray.mu.Unlock()
	if !tray.iconAdded || tray.hwnd == 0 {
		return
	}
	data := trayNotifyIconData{
		cbSize: uint32(unsafe.Sizeof(trayNotifyIconData{})),
		hwnd:   tray.hwnd,
		id:     1,
	}
	trayShellNotifyIcon.Call(trayNIMDelete, uintptr(unsafe.Pointer(&data)))
	tray.iconAdded = false
}

func (tray *windowsApplicationTray) showMenu() {
	menu, _, _ := trayCreatePopupMenu.Call()
	if menu == 0 {
		return
	}
	defer trayDestroyMenu.Call(menu)
	trayAppendMenuItem(menu, trayMFString, trayIDOpen, "打开 Murong")
	trayAppendMenuItem(menu, trayMFSeparator, 0, "")
	trayAppendMenuItem(menu, trayMFString, trayIDExit, "退出 Murong")
	var cursor trayPoint
	trayGetCursorPos.Call(uintptr(unsafe.Pointer(&cursor)))
	tray.mu.Lock()
	hwnd := tray.hwnd
	tray.mu.Unlock()
	traySetForegroundWindow.Call(hwnd)
	command, _, _ := trayTrackPopupMenu.Call(
		menu,
		trayTPMRightButton|trayTPMReturnCommand,
		uintptr(cursor.x), uintptr(cursor.y), 0,
		hwnd, 0,
	)
	trayPostMessage.Call(hwnd, trayWMNull, 0, 0)
	switch int(command) {
	case trayIDOpen:
		if tray.onOpen != nil {
			go tray.onOpen()
		}
	case trayIDExit:
		if tray.onExit != nil {
			go tray.onExit()
		}
	}
}

func trayAppendMenuItem(menu uintptr, flags uint32, id int, label string) {
	value, _ := syscall.UTF16PtrFromString(label)
	trayAppendMenu.Call(menu, uintptr(flags), uintptr(id), uintptr(unsafe.Pointer(value)))
}
