//go:build !windows

package main

import (
	"fmt"
	"syscall"
)

func desktopVisionRecommendation() string {
	return "16GB 以上建议 Qwen3.5-9B，32GB 以上可尝试 Gemma 4 12B；低内存设备选 2B Lite 或 Gemma 4 E2B。"
}

func requireDesktopFreeSpace(path string, required int64) error {
	var stat syscall.Statfs_t
	if err := syscall.Statfs(path, &stat); err != nil {
		return err
	}
	available := int64(stat.Bavail) * int64(stat.Bsize)
	if available < required {
		return fmt.Errorf(
			"存储空间不足：至少需要 %.1f GB，当前可用 %.1f GB",
			float64(required)/(1024*1024*1024),
			float64(available)/(1024*1024*1024),
		)
	}
	return nil
}
