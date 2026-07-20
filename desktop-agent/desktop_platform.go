package main

import "runtime"

func currentDesktopPlatformInfo() DesktopPlatformInfo {
	info := DesktopPlatformInfo{
		OS:                   runtime.GOOS,
		Architecture:         runtime.GOARCH,
		Label:                runtime.GOOS,
		CredentialProtection: "应用私有密钥 + 系统文件权限",
		PackageKind:          "portable",
	}
	switch runtime.GOOS {
	case "windows":
		info.Label = "Windows"
		info.CredentialProtection = "Windows DPAPI"
		info.PackageKind = "exe"
	case "darwin":
		info.Label = "macOS"
		info.PackageKind = "app"
	case "linux":
		info.Label = "Linux"
		info.PackageKind = "tar.gz"
	}
	return info
}

func desktopSourcePlatform() string {
	switch runtime.GOOS {
	case "windows", "darwin", "linux":
		return runtime.GOOS
	default:
		return "desktop"
	}
}

func isDesktopSourcePlatform(value string) bool {
	switch value {
	case "windows", "darwin", "linux", "desktop":
		return true
	default:
		return false
	}
}
