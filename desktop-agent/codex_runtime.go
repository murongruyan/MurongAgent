package main

import (
	"archive/tar"
	"bytes"
	"compress/gzip"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
)

const (
	embeddedCodexVersion  = "0.144.6"
	embeddedCodexMaxBytes = int64(450 << 20)
)

type codexPlatformSpec struct {
	NPMPlatform   string
	Target        string
	Executable    string
	ArchiveSHA256 string
}

var codexPlatformSpecs = map[string]codexPlatformSpec{
	"windows/amd64": {NPMPlatform: "win32-x64", Target: "x86_64-pc-windows-msvc", Executable: "codex.exe", ArchiveSHA256: "E04AFBE9841BE306455D075AD414993A946C94A399E55D7F9EC223F734CD4101"},
	"windows/arm64": {NPMPlatform: "win32-arm64", Target: "aarch64-pc-windows-msvc", Executable: "codex.exe", ArchiveSHA256: "92774896D0D293DB1C1808E77085B5A068E5AA70825D0D656E29BD4CA276E651"},
	"darwin/amd64":  {NPMPlatform: "darwin-x64", Target: "x86_64-apple-darwin", Executable: "codex", ArchiveSHA256: "6F1CDAB2DD23BEB5BFDB82A7D4FF5BB8C33F29AF5D1019778B18584EA7C53165"},
	"darwin/arm64":  {NPMPlatform: "darwin-arm64", Target: "aarch64-apple-darwin", Executable: "codex", ArchiveSHA256: "671D58A58CD2058345B9D9E4A969BB69937E50C7C1CD57C6061ED674DC92F94B"},
	"linux/amd64":   {NPMPlatform: "linux-x64", Target: "x86_64-unknown-linux-musl", Executable: "codex", ArchiveSHA256: "B6752EB2E8C10E6FCC96AC5C1C8AD8342CDB9A74504FB84686ADDF081A7D2868"},
	"linux/arm64":   {NPMPlatform: "linux-arm64", Target: "aarch64-unknown-linux-musl", Executable: "codex", ArchiveSHA256: "19F0B01B33F273DF94191670B2E0E5D0F624B0354E765BFDEA5763920B713800"},
}

var embeddedCodexExtractMu sync.Mutex

func currentCodexPlatformSpec() (codexPlatformSpec, error) {
	key := runtime.GOOS + "/" + runtime.GOARCH
	spec, ok := codexPlatformSpecs[key]
	if !ok {
		return codexPlatformSpec{}, fmt.Errorf("当前平台尚无内置 Codex 运行时：%s", key)
	}
	return spec, nil
}

func hasEmbeddedCodexRuntime() bool {
	return len(embeddedCodexArchiveBytes()) > 0
}

func resolveCodexExecutable(runtimeRoot, preferred string) (string, bool, error) {
	preferred = strings.TrimSpace(preferred)
	if preferred != "" {
		path, err := validateCodexExecutablePath(preferred)
		return path, false, err
	}
	if hasEmbeddedCodexRuntime() {
		path, err := ensureEmbeddedCodexRuntime(runtimeRoot)
		return path, true, err
	}
	path, err := discoverExternalCodexExecutable()
	return path, false, err
}

func discoverExternalCodexExecutable() (string, error) {
	spec, _ := currentCodexPlatformSpec()
	candidates := make([]string, 0, 12)
	if value := strings.TrimSpace(os.Getenv("MURONG_CODEX_EXECUTABLE")); value != "" {
		candidates = append(candidates, value)
	}
	if home, err := os.UserHomeDir(); err == nil {
		candidates = append(candidates,
			filepath.Join(home, ".codex", "plugins", ".plugin-appserver", spec.Executable),
			filepath.Join(home, ".local", "bin", "codex"),
			filepath.Join(home, "AppData", "Roaming", "npm", "node_modules", "@openai", "codex", "vendor", spec.Target, "bin", spec.Executable),
		)
	}
	if runtime.GOOS != "windows" {
		candidates = append(candidates, "/opt/homebrew/bin/codex", "/usr/local/bin/codex", "/usr/bin/codex")
	}
	for _, name := range []string{"codex", "codex.exe"} {
		if path, err := exec.LookPath(name); err == nil {
			candidates = append(candidates, path)
		}
	}
	seen := map[string]bool{}
	for _, candidate := range candidates {
		key := filepath.Clean(strings.TrimSpace(candidate))
		if runtime.GOOS == "windows" {
			key = strings.ToLower(key)
		}
		if key == "" || seen[key] {
			continue
		}
		seen[key] = true
		if path, err := validateCodexExecutablePath(candidate); err == nil {
			return path, nil
		}
	}
	return "", errors.New("没有找到可运行的 Codex CLI；正式版应包含当前平台的内置运行时，也可以在高级设置中指定 Codex 可执行文件")
}

func validateCodexExecutablePath(value string) (string, error) {
	path, err := filepath.Abs(strings.TrimSpace(value))
	if err != nil {
		return "", errors.New("Codex CLI 路径无效")
	}
	info, err := os.Stat(path)
	if err != nil || !info.Mode().IsRegular() {
		return "", errors.New("Codex CLI 文件不存在")
	}
	if runtime.GOOS == "windows" {
		if !strings.EqualFold(filepath.Ext(path), ".exe") {
			return "", errors.New("Windows 端仅接受原生 codex.exe")
		}
	} else if info.Mode().Perm()&0o111 == 0 {
		return "", errors.New("Codex CLI 文件没有执行权限")
	}
	return path, nil
}

func ensureEmbeddedCodexRuntime(runtimeRoot string) (string, error) {
	embeddedCodexExtractMu.Lock()
	defer embeddedCodexExtractMu.Unlock()
	spec, err := currentCodexPlatformSpec()
	if err != nil {
		return "", err
	}
	archive := embeddedCodexArchiveBytes()
	if len(archive) == 0 {
		return "", errors.New("当前构建没有内置 Codex 运行时")
	}
	digest := sha256.Sum256(archive)
	if !strings.EqualFold(hex.EncodeToString(digest[:]), spec.ArchiveSHA256) {
		return "", errors.New("内置 Codex 运行时哈希校验失败")
	}
	base, err := filepath.Abs(filepath.Join(runtimeRoot, "runtime"))
	if err != nil {
		return "", err
	}
	target := filepath.Join(base, "codex-"+embeddedCodexVersion+"-"+spec.NPMPlatform)
	executable := codexExecutableInRoot(target, spec)
	marker := filepath.Join(target, ".archive-sha256")
	if markerBytes, readErr := os.ReadFile(marker); readErr == nil && strings.EqualFold(strings.TrimSpace(string(markerBytes)), spec.ArchiveSHA256) {
		if path, validateErr := validateCodexExecutablePath(executable); validateErr == nil {
			return path, nil
		}
	}
	if err := os.MkdirAll(base, 0o700); err != nil {
		return "", fmt.Errorf("无法创建 Codex 运行目录：%w", err)
	}
	temp, err := os.MkdirTemp(base, ".codex-extract-*")
	if err != nil {
		return "", err
	}
	defer os.RemoveAll(temp)
	if err := extractEmbeddedCodexArchive(archive, temp); err != nil {
		return "", err
	}
	if err := os.WriteFile(filepath.Join(temp, ".archive-sha256"), []byte(spec.ArchiveSHA256+"\n"), 0o600); err != nil {
		return "", err
	}
	if err := safeRemoveCodexRuntimeDirectory(base, target); err != nil {
		return "", err
	}
	if err := os.Rename(temp, target); err != nil {
		return "", fmt.Errorf("无法启用内置 Codex 运行时：%w", err)
	}
	return validateCodexExecutablePath(executable)
}

func codexExecutableInRoot(root string, spec codexPlatformSpec) string {
	return filepath.Join(root, "vendor", spec.Target, "bin", spec.Executable)
}

func extractEmbeddedCodexArchive(archive []byte, destination string) error {
	spec, err := currentCodexPlatformSpec()
	if err != nil {
		return err
	}
	gzipReader, err := gzip.NewReader(bytes.NewReader(archive))
	if err != nil {
		return fmt.Errorf("内置 Codex 压缩包无效：%w", err)
	}
	defer gzipReader.Close()
	tarReader := tar.NewReader(gzipReader)
	var total int64
	files := 0
	for {
		header, nextErr := tarReader.Next()
		if errors.Is(nextErr, io.EOF) {
			break
		}
		if nextErr != nil {
			return fmt.Errorf("读取内置 Codex 压缩包失败：%w", nextErr)
		}
		name := strings.TrimPrefix(filepath.ToSlash(header.Name), "package/")
		if name == "" || name == "." {
			continue
		}
		clean := filepath.Clean(filepath.FromSlash(name))
		if clean == "." || filepath.IsAbs(clean) || clean == ".." || strings.HasPrefix(clean, ".."+string(filepath.Separator)) {
			return errors.New("内置 Codex 压缩包包含越界路径")
		}
		target := filepath.Join(destination, clean)
		switch header.Typeflag {
		case tar.TypeDir:
			if err := os.MkdirAll(target, 0o700); err != nil {
				return err
			}
		case tar.TypeReg, tar.TypeRegA:
			files++
			total += header.Size
			if files > 64 || header.Size < 0 || header.Size > 380<<20 || total > embeddedCodexMaxBytes {
				return errors.New("内置 Codex 运行时超过安全大小限制")
			}
			if err := os.MkdirAll(filepath.Dir(target), 0o700); err != nil {
				return err
			}
			mode := os.FileMode(header.Mode).Perm()
			if mode == 0 {
				mode = 0o600
			}
			file, createErr := os.OpenFile(target, os.O_CREATE|os.O_EXCL|os.O_WRONLY, mode)
			if createErr != nil {
				return createErr
			}
			written, copyErr := io.CopyN(file, tarReader, header.Size)
			closeErr := file.Close()
			if copyErr != nil || written != header.Size {
				return fmt.Errorf("解压 Codex 文件失败：%w", copyErr)
			}
			if closeErr != nil {
				return closeErr
			}
		default:
			return errors.New("内置 Codex 压缩包包含不支持的链接或设备文件")
		}
	}
	if _, err := os.Stat(codexExecutableInRoot(destination, spec)); err != nil {
		return errors.New("内置 Codex 压缩包缺少当前平台主程序")
	}
	return nil
}

func safeRemoveCodexRuntimeDirectory(base, target string) error {
	relative, err := filepath.Rel(base, target)
	if err != nil || relative == "." || relative == ".." || strings.HasPrefix(relative, ".."+string(filepath.Separator)) {
		return errors.New("拒绝清理越界的 Codex 运行目录")
	}
	if _, err := os.Stat(target); errors.Is(err, os.ErrNotExist) {
		return nil
	} else if err != nil {
		return err
	}
	return os.RemoveAll(target)
}
