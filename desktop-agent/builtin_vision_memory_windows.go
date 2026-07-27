//go:build windows

package main

import (
	"fmt"
	"path/filepath"
	"unsafe"

	"golang.org/x/sys/windows"
)

type memoryStatusEx struct {
	Length            uint32
	MemoryLoad        uint32
	TotalPhysical     uint64
	AvailablePhysical uint64
	TotalPageFile     uint64
	AvailablePageFile uint64
	TotalVirtual      uint64
	AvailableVirtual  uint64
	AvailableExtended uint64
}

var globalMemoryStatusEx = windows.NewLazySystemDLL("kernel32.dll").
	NewProc("GlobalMemoryStatusEx")

func desktopMemoryInfo() (uint64, uint64) {
	status := memoryStatusEx{Length: uint32(unsafe.Sizeof(memoryStatusEx{}))}
	result, _, _ := globalMemoryStatusEx.Call(uintptr(unsafe.Pointer(&status)))
	if result == 0 {
		return 0, 0
	}
	return status.TotalPhysical, status.AvailablePhysical
}

func desktopVisionRecommendation() string {
	total, available := desktopMemoryInfo()
	gib := uint64(1024 * 1024 * 1024)
	switch {
	case total >= 32*gib && available >= 10*gib:
		return "建议 Gemma 4 12B 桌面实验档；高内存电脑可获得更强的代码与 Agent 能力。"
	case total >= 16*gib && available >= 6*gib:
		return "建议 Qwen3.5-9B Ultra；代码、视觉和 Agent 质量最高。"
	case total >= 12*gib && available >= 4*gib:
		return "编程优先建议 Qwen2.5-Coder-7B；需要看图则选 Qwen3.5-4B Pro。"
	case total >= 8*gib && available >= 4*gib:
		return "建议 Qwen3.5-4B Pro；当前内存适合更稳的 GUI 与代码能力。"
	case total >= 6*gib && available >= 2*gib:
		return "建议 Qwen3.5-2B Lite；4B 可能影响其他程序。"
	case total >= 4*gib:
		return "建议 Gemma 4 E2B；它的端侧内存占用更低。"
	default:
		return fmt.Sprintf("当前可用内存约 %.1f GB，建议使用用户 API。", float64(available)/float64(gib))
	}
}

func requireDesktopFreeSpace(path string, required int64) error {
	root, err := windows.UTF16PtrFromString(filepathVolumeRoot(path))
	if err != nil {
		return err
	}
	var available uint64
	if err := windows.GetDiskFreeSpaceEx(root, &available, nil, nil); err != nil {
		return err
	}
	if available < uint64(required) {
		return fmt.Errorf(
			"存储空间不足：至少需要 %.1f GB，当前可用 %.1f GB",
			float64(required)/(1024*1024*1024),
			float64(available)/(1024*1024*1024),
		)
	}
	return nil
}

func filepathVolumeRoot(path string) string {
	volume := filepath.VolumeName(path)
	if volume != "" {
		return volume + `\`
	}
	return path
}
