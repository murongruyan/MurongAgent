//go:build windows

package desktopbridge

import (
	"encoding/base64"
	"fmt"
	"os"
	"os/exec"
	"syscall"
	"unsafe"
)

const (
	cryptProtectUIForbidden = 0x1
	moveFileReplaceExisting = 0x1
	moveFileWriteThrough    = 0x8
	createNoWindow          = 0x08000000
)

var (
	crypt32               = syscall.NewLazyDLL("crypt32.dll")
	kernel32              = syscall.NewLazyDLL("kernel32.dll")
	cryptProtectData      = crypt32.NewProc("CryptProtectData")
	cryptUnprotectData    = crypt32.NewProc("CryptUnprotectData")
	localFree             = kernel32.NewProc("LocalFree")
	moveFileEx            = kernel32.NewProc("MoveFileExW")
	nodeProtectionEntropy = []byte("Murong Windows Node credential v1")
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
	entropy := blobFromBytes(nodeProtectionEntropy)
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
	entropy := blobFromBytes(nodeProtectionEntropy)
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

func removeIfExists(path string) {
	_ = os.Remove(path)
}

func prepareHiddenCommand(command *exec.Cmd) {
	command.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: createNoWindow}
}
