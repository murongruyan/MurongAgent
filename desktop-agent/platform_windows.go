//go:build windows

package main

import (
	"context"
	"encoding/base64"
	"fmt"
	"os"
	"os/exec"
	"syscall"
	"time"
	"unsafe"
)

const (
	cryptProtectUIForbidden = 0x1
	moveFileReplaceExisting = 0x1
	moveFileWriteThrough    = 0x8
	createNoWindow          = 0x08000000
	applicationIconID       = 3
	imageIcon               = 1
	wmSetIcon               = 0x0080
	iconSmall               = 0
	iconBig                 = 1
	iconSmall2              = 2
	smCXIcon                = 11
	smCYIcon                = 12
	smCXSmallIcon           = 49
	smCYSmallIcon           = 50
	lrShared                = 0x8000
	applicationUserModelID  = "Murong.DesktopAgent"
)

var (
	crypt32                  = syscall.NewLazyDLL("crypt32.dll")
	kernel32                 = syscall.NewLazyDLL("kernel32.dll")
	cryptProtectData         = crypt32.NewProc("CryptProtectData")
	cryptUnprotectData       = crypt32.NewProc("CryptUnprotectData")
	localFree                = kernel32.NewProc("LocalFree")
	moveFileEx               = kernel32.NewProc("MoveFileExW")
	getModuleHandle          = kernel32.NewProc("GetModuleHandleW")
	enumWindows              = syscall.NewLazyDLL("user32.dll").NewProc("EnumWindows")
	isWindowVisible          = syscall.NewLazyDLL("user32.dll").NewProc("IsWindowVisible")
	getWindowThreadProcessID = syscall.NewLazyDLL("user32.dll").NewProc("GetWindowThreadProcessId")
	loadImage                = syscall.NewLazyDLL("user32.dll").NewProc("LoadImageW")
	getSystemMetrics         = syscall.NewLazyDLL("user32.dll").NewProc("GetSystemMetrics")
	sendMessage              = syscall.NewLazyDLL("user32.dll").NewProc("SendMessageW")
	setClassLongPtr          = syscall.NewLazyDLL("user32.dll").NewProc("SetClassLongPtrW")
	showWindow               = syscall.NewLazyDLL("user32.dll").NewProc("ShowWindow")
	setForegroundWindow      = syscall.NewLazyDLL("user32.dll").NewProc("SetForegroundWindow")
	setCurrentProcessAppID   = syscall.NewLazyDLL("shell32.dll").NewProc("SetCurrentProcessExplicitAppUserModelID")
	desktopProtectionEntropy = []byte("Murong Desktop Agent credential v1")
)

type dataBlob struct {
	size uint32
	data *byte
}

func blobFromBytes(value []byte) dataBlob {
	if len(value) == 0 {
		return dataBlob{}
	}
	return dataBlob{size: uint32(len(value)), data: &value[0]}
}

func protectSecret(plain []byte) (string, error) {
	input := blobFromBytes(plain)
	entropy := blobFromBytes(desktopProtectionEntropy)
	var output dataBlob
	result, _, callErr := cryptProtectData.Call(
		uintptr(unsafe.Pointer(&input)), 0, uintptr(unsafe.Pointer(&entropy)), 0, 0,
		cryptProtectUIForbidden, uintptr(unsafe.Pointer(&output)),
	)
	if result == 0 {
		return "", fmt.Errorf("CryptProtectData: %w", callErr)
	}
	defer localFree.Call(uintptr(unsafe.Pointer(output.data)))
	protected := append([]byte(nil), unsafe.Slice(output.data, output.size)...)
	return base64.RawStdEncoding.EncodeToString(protected), nil
}

func unprotectSecret(encoded string) ([]byte, error) {
	protected, err := base64.RawStdEncoding.DecodeString(encoded)
	if err != nil {
		return nil, err
	}
	input := blobFromBytes(protected)
	entropy := blobFromBytes(desktopProtectionEntropy)
	var output dataBlob
	result, _, callErr := cryptUnprotectData.Call(
		uintptr(unsafe.Pointer(&input)), 0, uintptr(unsafe.Pointer(&entropy)), 0, 0,
		cryptProtectUIForbidden, uintptr(unsafe.Pointer(&output)),
	)
	if result == 0 {
		return nil, fmt.Errorf("CryptUnprotectData: %w", callErr)
	}
	defer localFree.Call(uintptr(unsafe.Pointer(output.data)))
	return append([]byte(nil), unsafe.Slice(output.data, output.size)...), nil
}

func replaceFile(source, target string) error {
	sourcePtr, err := syscall.UTF16PtrFromString(source)
	if err != nil {
		return err
	}
	targetPtr, err := syscall.UTF16PtrFromString(target)
	if err != nil {
		return err
	}
	result, _, callErr := moveFileEx.Call(
		uintptr(unsafe.Pointer(sourcePtr)),
		uintptr(unsafe.Pointer(targetPtr)),
		moveFileReplaceExisting|moveFileWriteThrough,
	)
	if result == 0 {
		return fmt.Errorf("MoveFileExW: %w", callErr)
	}
	return nil
}

func executableProcessID(name string) int {
	return tasklistProcessID(name)
}

func prepareHiddenCommand(command *exec.Cmd) {
	command.SysProcAttr = &syscall.SysProcAttr{
		HideWindow:    true,
		CreationFlags: createNoWindow,
	}
}

func prepareApplicationIdentity() {
	appID, err := syscall.UTF16PtrFromString(applicationUserModelID)
	if err != nil {
		return
	}
	setCurrentProcessAppID.Call(uintptr(unsafe.Pointer(appID)))
}

func ensureApplicationWindowIcon(ctx context.Context) {
	go func() {
		ticker := time.NewTicker(175 * time.Millisecond)
		defer ticker.Stop()
		consecutive := 0
		for attempt := 0; attempt < 40; attempt++ {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				if applyApplicationWindowIcon() > 0 {
					consecutive++
					if consecutive >= 4 {
						return
					}
				} else {
					consecutive = 0
				}
			}
		}
	}()
}

// applyApplicationWindowIcon explicitly publishes both Win32 icon sizes.
// Wails sets ICON_SMALL from resource 3, while the taskbar, Alt+Tab and hover
// preview may request ICON_BIG or the window-class icon instead.
func applyApplicationWindowIcon() int {
	instance, _, _ := getModuleHandle.Call(0)
	if instance == 0 {
		return 0
	}
	big := loadApplicationIcon(instance, smCXIcon, smCYIcon)
	small := loadApplicationIcon(instance, smCXSmallIcon, smCYSmallIcon)
	if big == 0 && small == 0 {
		return 0
	}

	processID := uint32(os.Getpid())
	applied := 0
	callback := syscall.NewCallback(func(window, _ uintptr) uintptr {
		var candidate uint32
		getWindowThreadProcessID.Call(window, uintptr(unsafe.Pointer(&candidate)))
		if candidate != processID {
			return 1
		}
		visible, _, _ := isWindowVisible.Call(window)
		if visible == 0 {
			return 1
		}
		if big != 0 {
			sendMessage.Call(window, wmSetIcon, iconBig, big)
			setClassLongPtr.Call(window, ^uintptr(13), big) // GCLP_HICON (-14)
		}
		if small != 0 {
			sendMessage.Call(window, wmSetIcon, iconSmall, small)
			sendMessage.Call(window, wmSetIcon, iconSmall2, small)
			setClassLongPtr.Call(window, ^uintptr(33), small) // GCLP_HICONSM (-34)
		}
		applied++
		return 1
	})
	enumWindows.Call(callback, 0)
	return applied
}

func loadApplicationIcon(instance uintptr, widthMetric, heightMetric uintptr) uintptr {
	width, _, _ := getSystemMetrics.Call(widthMetric)
	height, _, _ := getSystemMetrics.Call(heightMetric)
	icon, _, _ := loadImage.Call(instance, applicationIconID, imageIcon, width, height, lrShared)
	return icon
}

func activateProcessWindow(processID int) bool {
	found := false
	callback := syscall.NewCallback(func(window, _ uintptr) uintptr {
		if visible, _, _ := isWindowVisible.Call(window); visible == 0 {
			return 1
		}
		var candidate uint32
		getWindowThreadProcessID.Call(window, uintptr(unsafe.Pointer(&candidate)))
		if int(candidate) != processID {
			return 1
		}
		found = true
		showWindow.Call(window, 9)
		setForegroundWindow.Call(window)
		return 0
	})
	enumWindows.Call(callback, 0)
	return found
}

func terminateProcess(processID int) error {
	process, err := os.FindProcess(processID)
	if err != nil {
		return err
	}
	return process.Kill()
}
