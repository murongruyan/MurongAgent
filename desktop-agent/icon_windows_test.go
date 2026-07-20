//go:build windows

package main

import (
	"fmt"
	"os"
	"syscall"
	"testing"
	"unsafe"
)

func TestWindowsApplicationIdentity(t *testing.T) {
	if applicationUserModelID != "Murong.DesktopAgent" {
		t.Fatalf("unexpected Windows application identity: %q", applicationUserModelID)
	}
}

func TestBuiltWindowsApplicationResources(t *testing.T) {
	executable := os.Getenv("MURONG_BUILT_EXE")
	if executable == "" {
		t.Skip("set MURONG_BUILT_EXE to validate the packaged Wails executable")
	}
	executablePointer, err := syscall.UTF16PtrFromString(executable)
	if err != nil {
		t.Fatal(err)
	}
	kernel := syscall.NewLazyDLL("kernel32.dll")
	module, _, callErr := kernel.NewProc("LoadLibraryExW").Call(
		uintptr(unsafe.Pointer(executablePointer)),
		0,
		0x00000002|0x00000020, // LOAD_LIBRARY_AS_DATAFILE | LOAD_LIBRARY_AS_IMAGE_RESOURCE
	)
	if module == 0 {
		t.Fatalf("LoadLibraryExW could not inspect %s: %v", executable, callErr)
	}
	defer kernel.NewProc("FreeLibrary").Call(module)
	if icon := loadApplicationIcon(module, smCXIcon, smCYIcon); icon == 0 {
		t.Fatal("packaged application big icon resource ID 3 is missing")
	}
	if icon := loadApplicationIcon(module, smCXSmallIcon, smCYSmallIcon); icon == 0 {
		t.Fatal("packaged application small icon resource ID 3 is missing")
	}
	assertPackagedVersionMetadata(t, executable)
}

func assertPackagedVersionMetadata(t *testing.T, executable string) {
	t.Helper()
	executablePointer, err := syscall.UTF16PtrFromString(executable)
	if err != nil {
		t.Fatal(err)
	}
	version := syscall.NewLazyDLL("version.dll")
	var ignored uint32
	size, _, callErr := version.NewProc("GetFileVersionInfoSizeW").Call(
		uintptr(unsafe.Pointer(executablePointer)),
		uintptr(unsafe.Pointer(&ignored)),
	)
	if size == 0 {
		t.Fatalf("packaged application version resource is missing: %v", callErr)
	}
	data := make([]byte, size)
	result, _, callErr := version.NewProc("GetFileVersionInfoW").Call(
		uintptr(unsafe.Pointer(executablePointer)),
		0,
		size,
		uintptr(unsafe.Pointer(&data[0])),
	)
	if result == 0 {
		t.Fatalf("packaged application version resource cannot be read: %v", callErr)
	}

	translations := queryVersionValue(t, version, data, `\VarFileInfo\Translation`)
	if translations.length < 4 || translations.length%4 != 0 {
		t.Fatalf("packaged application has no valid version translation: %d bytes", translations.length)
	}
	words := unsafe.Slice((*uint16)(translations.pointer), translations.length/2)
	for index := 0; index+1 < len(words); index += 2 {
		prefix := fmt.Sprintf(`\StringFileInfo\%04x%04x\`, words[index], words[index+1])
		productName := queryVersionString(version, data, prefix+"ProductName")
		description := queryVersionString(version, data, prefix+"FileDescription")
		if productName == "Murong" && description == "Murong" {
			return
		}
	}
	t.Fatal("packaged application version metadata does not contain the Murong product identity")
}

type versionValue struct {
	pointer unsafe.Pointer
	length  int
}

func queryVersionValue(t *testing.T, version *syscall.LazyDLL, data []byte, path string) versionValue {
	t.Helper()
	pathPointer, err := syscall.UTF16PtrFromString(path)
	if err != nil {
		t.Fatal(err)
	}
	var valuePointer unsafe.Pointer
	var valueLength uint32
	result, _, _ := version.NewProc("VerQueryValueW").Call(
		uintptr(unsafe.Pointer(&data[0])),
		uintptr(unsafe.Pointer(pathPointer)),
		uintptr(unsafe.Pointer(&valuePointer)),
		uintptr(unsafe.Pointer(&valueLength)),
	)
	if result == 0 {
		t.Fatalf("packaged application version value is missing: %s", path)
	}
	return versionValue{pointer: valuePointer, length: int(valueLength)}
}

func queryVersionString(version *syscall.LazyDLL, data []byte, path string) string {
	pathPointer, err := syscall.UTF16PtrFromString(path)
	if err != nil {
		return ""
	}
	var valuePointer unsafe.Pointer
	var valueLength uint32
	result, _, _ := version.NewProc("VerQueryValueW").Call(
		uintptr(unsafe.Pointer(&data[0])),
		uintptr(unsafe.Pointer(pathPointer)),
		uintptr(unsafe.Pointer(&valuePointer)),
		uintptr(unsafe.Pointer(&valueLength)),
	)
	if result == 0 || valuePointer == nil || valueLength == 0 {
		return ""
	}
	return syscall.UTF16ToString(unsafe.Slice((*uint16)(valuePointer), valueLength))
}
