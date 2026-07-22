//go:build windows

package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"runtime"
	"syscall"
	"time"
	"unsafe"

	"github.com/wailsapp/wails/v2/pkg/options"
	"golang.org/x/sys/windows"
)

const (
	applicationInstanceMutexName  = `Local\MurongDesktopAgent.SingleInstance.V1`
	wailsSingleInstanceClass      = "wails-app-murong-desktop-agent-v1-sic"
	wailsSingleInstanceWindow     = "wails-app-murong-desktop-agent-v1-siw"
	wailsSingleInstanceCopyDataID = 1542
	applicationMainWindowClass    = "MurongDesktopAgentWindow"
	applicationWMCopyData         = 0x004A
	applicationSWRestore          = 9
)

var applicationFindWindow = syscall.NewLazyDLL("user32.dll").NewProc("FindWindowW")

type windowsApplicationInstanceLock struct {
	handle windows.Handle
}

type applicationCopyData struct {
	kind    uintptr
	length  uint32
	payload uintptr
}

func acquireApplicationInstance(args []string) (applicationInstanceLock, bool, error) {
	name, err := windows.UTF16PtrFromString(applicationInstanceMutexName)
	if err != nil {
		return nil, false, err
	}
	handle, createErr := windows.CreateMutex(nil, false, name)
	if createErr == nil {
		return &windowsApplicationInstanceLock{handle: handle}, true, nil
	}
	if !errors.Is(createErr, windows.ERROR_ALREADY_EXISTS) {
		if handle != 0 {
			_ = windows.CloseHandle(handle)
		}
		return nil, false, fmt.Errorf("创建应用单实例锁失败：%w", createErr)
	}
	if handle != 0 {
		_ = windows.CloseHandle(handle)
	}
	if err := notifyExistingApplicationInstance(args, 8*time.Second); err != nil {
		// A second process must never continue into Wails even if the first
		// process is still constructing its WebView. This closes the startup
		// race in Wails' own mutex/event-window sequence.
		return nil, false, err
	}
	return nil, false, nil
}

func (lock *windowsApplicationInstanceLock) Close() {
	if lock == nil || lock.handle == 0 {
		return
	}
	_ = windows.CloseHandle(lock.handle)
	lock.handle = 0
}

func notifyExistingApplicationInstance(args []string, timeout time.Duration) error {
	workingDirectory, err := os.Getwd()
	if err != nil {
		workingDirectory = ""
	}
	payload, err := json.Marshal(options.SecondInstanceData{Args: args, WorkingDirectory: workingDirectory})
	if err != nil {
		return err
	}
	encoded, err := syscall.UTF16FromString(string(payload))
	if err != nil {
		return err
	}
	className, _ := syscall.UTF16PtrFromString(wailsSingleInstanceClass)
	windowName, _ := syscall.UTF16PtrFromString(wailsSingleInstanceWindow)
	deadline := time.Now().Add(timeout)
	for {
		hwnd, _, _ := applicationFindWindow.Call(
			uintptr(unsafe.Pointer(className)),
			uintptr(unsafe.Pointer(windowName)),
		)
		if hwnd != 0 {
			message := applicationCopyData{
				kind:    wailsSingleInstanceCopyDataID,
				length:  uint32(len(encoded)*2 + 1),
				payload: uintptr(unsafe.Pointer(&encoded[0])),
			}
			sendMessage.Call(hwnd, applicationWMCopyData, 0, uintptr(unsafe.Pointer(&message)))
			runtime.KeepAlive(encoded)
			activateExistingApplicationWindow()
			return nil
		}
		if time.Now().After(deadline) {
			return errors.New("已有 Murong 正在启动，未能在限定时间内唤醒窗口")
		}
		time.Sleep(50 * time.Millisecond)
	}
}

func activateExistingApplicationWindow() {
	className, _ := syscall.UTF16PtrFromString(applicationMainWindowClass)
	hwnd, _, _ := applicationFindWindow.Call(uintptr(unsafe.Pointer(className)), 0)
	if hwnd == 0 {
		return
	}
	showWindow.Call(hwnd, applicationSWRestore)
	setForegroundWindow.Call(hwnd)
}
