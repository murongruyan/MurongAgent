package desktopbridge

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/xml"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"time"
)

const (
	colorOSElsaConfigPath = "/data/oplus/os/bpm/sys_elsa_config_list.xml"
	colorOSElsaPackage    = "com.murong.agent"
	colorOSElsaEntry      = `<whitePkg name="com.murong.agent" category="001" />`
)

var (
	colorOSElsaMarkerPattern  = regexp.MustCompile(`(?i)<!--\s*third\s+white\s+app\s*-->`)
	colorOSElsaPackagePattern = regexp.MustCompile(`(?i)<whitePkg\b[^>]*\bname\s*=\s*["']com\.murong\.agent["'][^>]*/?>`)
	colorOSElsaModulePath     = regexp.MustCompile(`^/data/adb/modules/[A-Za-z0-9._+-]+(?:/[A-Za-z0-9._+/@=-]+)*/sys_elsa_config_list\.xml$`)
)

func prepareADBPowerPolicy(ctx context.Context, adbPath, serial string, rootMode adbRootMode) error {
	if rootMode == adbRootUnavailable {
		return nil
	}
	// Apply Android's public restriction controls as well as ColorOS' Elsa
	// whitelist. The latter is the policy ColorOS actually consults before its
	// proprietary freezer suspends a third-party foreground service.
	standardPolicy := strings.Join([]string{
		"cmd activity set-bg-restriction-level --user 0 com.murong.agent unrestricted >/dev/null 2>&1 || true",
		"cmd appops set com.murong.agent RUN_IN_BACKGROUND allow >/dev/null 2>&1 || true",
		"cmd appops set com.murong.agent RUN_ANY_IN_BACKGROUND allow >/dev/null 2>&1 || true",
		"dumpsys deviceidle whitelist +com.murong.agent >/dev/null 2>&1 || true",
		"cmd package unstop --user 0 com.murong.agent",
		"am unfreeze --sticky com.murong.agent >/dev/null 2>&1 || true",
	}, "; ")
	if _, err := runADBRootCommand(ctx, adbPath, serial, rootMode, standardPolicy); err != nil {
		return fmt.Errorf("无法自动允许 Murong 后台运行：%w", err)
	}
	if err := ensureColorOSElsaWhitelist(ctx, adbPath, serial, rootMode); err != nil {
		return fmt.Errorf("无法写入 ColorOS 后台白名单：%w", err)
	}
	return nil
}

func ensureColorOSElsaWhitelist(ctx context.Context, adbPath, serial string, rootMode adbRootMode) error {
	listCommand := "test -f " + adbShellQuote(colorOSElsaConfigPath) + " && printf '%s\\n' " + adbShellQuote(colorOSElsaConfigPath) + "; " +
		"find /data/adb/modules -type f -name sys_elsa_config_list.xml -print 2>/dev/null || true"
	output, err := runADBRootCommand(ctx, adbPath, serial, rootMode, listCommand)
	if err != nil {
		return err
	}
	paths, err := parseColorOSElsaPaths(output)
	if err != nil {
		return err
	}
	if len(paths) == 0 {
		return nil
	}
	// Module sources are patched before the live path so a Magisk/KernelSU
	// magic-mount remains correct after reboot. The live path is still checked
	// afterwards so the current boot takes effect immediately.
	sort.SliceStable(paths, func(left, right int) bool {
		return paths[left] != colorOSElsaConfigPath && paths[right] == colorOSElsaConfigPath
	})
	for _, path := range paths {
		if err := patchColorOSElsaFile(ctx, adbPath, serial, rootMode, path); err != nil {
			return fmt.Errorf("%s：%w", path, err)
		}
	}
	return nil
}

func parseColorOSElsaPaths(output string) ([]string, error) {
	seen := make(map[string]struct{})
	paths := make([]string, 0)
	for _, raw := range strings.Split(strings.ReplaceAll(output, "\r\n", "\n"), "\n") {
		path := strings.TrimSpace(raw)
		if path == "" {
			continue
		}
		if path != colorOSElsaConfigPath && !colorOSElsaModulePath.MatchString(path) {
			return nil, fmt.Errorf("发现不安全的 ColorOS 配置路径 %q", path)
		}
		if _, exists := seen[path]; exists {
			continue
		}
		seen[path] = struct{}{}
		paths = append(paths, path)
	}
	return paths, nil
}

func patchColorOSElsaFile(ctx context.Context, adbPath, serial string, rootMode adbRootMode, remotePath string) error {
	digest := sha256.Sum256([]byte(remotePath))
	remoteTemporary := fmt.Sprintf(
		"/data/local/tmp/murong-elsa-%d-%s.xml",
		time.Now().UnixNano(),
		hex.EncodeToString(digest[:4]),
	)
	localDirectory, err := os.MkdirTemp("", "murong-elsa-")
	if err != nil {
		return err
	}
	defer os.RemoveAll(localDirectory)
	localPath := filepath.Join(localDirectory, "sys_elsa_config_list.xml")
	defer func() {
		cleanupContext, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		defer cancel()
		_, _ = runADBRootCommand(cleanupContext, adbPath, serial, rootMode, "rm -f "+adbShellQuote(remoteTemporary))
	}()
	copyForRead := "cp " + adbShellQuote(remotePath) + " " + adbShellQuote(remoteTemporary) +
		" && chmod 0644 " + adbShellQuote(remoteTemporary)
	if _, err := runADBRootCommand(ctx, adbPath, serial, rootMode, copyForRead); err != nil {
		return fmt.Errorf("无法读取配置：%w", err)
	}
	if _, err := runADB(ctx, adbPath, "-s", serial, "pull", remoteTemporary, localPath); err != nil {
		return fmt.Errorf("无法拉取配置：%w", err)
	}
	original, err := os.ReadFile(localPath)
	if err != nil {
		return err
	}
	updated, changed, err := addColorOSElsaWhitelist(original)
	if err != nil {
		return err
	}
	if !changed {
		return nil
	}
	if err := os.WriteFile(localPath, updated, 0o600); err != nil {
		return err
	}
	if _, err := runADB(ctx, adbPath, "-s", serial, "push", localPath, remoteTemporary); err != nil {
		return fmt.Errorf("无法上传新配置：%w", err)
	}
	originalDigest := sha256.Sum256(original)
	pathDigest := sha256.Sum256([]byte(remotePath))
	backupPath := fmt.Sprintf(
		"/data/adb/murongagent-backups/sys_elsa_config_list.%s.%s.xml",
		hex.EncodeToString(originalDigest[:8]),
		hex.EncodeToString(pathDigest[:4]),
	)
	quotedPath := adbShellQuote(remotePath)
	quotedTemporary := adbShellQuote(remoteTemporary)
	quotedBackup := adbShellQuote(backupPath)
	writeCommand := strings.Join([]string{
		"set -eu",
		"mkdir -p /data/adb/murongagent-backups",
		"if [ ! -f " + quotedBackup + " ]; then cp -p " + quotedPath + " " + quotedBackup + "; fi",
		"immutable=0",
		"attrs=$(lsattr -d " + quotedPath + " 2>/dev/null | awk '{print $1}' || true)",
		"case \"$attrs\" in *i*) chattr -i " + quotedPath + "; immutable=1 ;; esac",
		"restore_immutable() { if [ \"$immutable\" = 1 ]; then chattr +i " + quotedPath + " 2>/dev/null || true; fi; }",
		"trap restore_immutable EXIT INT TERM",
		"cat " + quotedTemporary + " > " + quotedPath,
		"grep -Fq " + adbShellQuote(colorOSElsaEntry) + " " + quotedPath,
		"sync " + quotedPath + " 2>/dev/null || sync",
	}, "; ")
	if _, err := runADBRootCommand(ctx, adbPath, serial, rootMode, writeCommand); err != nil {
		return fmt.Errorf("无法原位更新配置：%w", err)
	}
	return nil
}

func addColorOSElsaWhitelist(original []byte) ([]byte, bool, error) {
	if colorOSElsaPackagePattern.Match(original) {
		return append([]byte(nil), original...), false, nil
	}
	marker := colorOSElsaMarkerPattern.FindIndex(original)
	if marker == nil {
		return nil, false, errors.New("未找到 <!--third white app --> 白名单段")
	}
	newline := []byte("\n")
	if bytes.Contains(original, []byte("\r\n")) {
		newline = []byte("\r\n")
	}
	lineStart := bytes.LastIndex(original[:marker[0]], []byte("\n")) + 1
	indent := bytes.TrimRight(original[lineStart:marker[0]], " \t\r")
	if len(indent) != 0 {
		indent = nil
	} else {
		indent = bytes.TrimSuffix(original[lineStart:marker[0]], []byte("\r"))
	}
	insertion := make([]byte, 0, len(newline)+len(indent)+len(colorOSElsaEntry))
	insertion = append(insertion, newline...)
	insertion = append(insertion, indent...)
	insertion = append(insertion, colorOSElsaEntry...)
	updated := make([]byte, 0, len(original)+len(insertion))
	updated = append(updated, original[:marker[1]]...)
	updated = append(updated, insertion...)
	updated = append(updated, original[marker[1]:]...)
	if err := validateXML(updated); err != nil {
		return nil, false, fmt.Errorf("写入后 XML 无效：%w", err)
	}
	return updated, true, nil
}

func validateXML(content []byte) error {
	decoder := xml.NewDecoder(bytes.NewReader(content))
	for {
		if _, err := decoder.Token(); err != nil {
			if errors.Is(err, io.EOF) {
				return nil
			}
			return err
		}
	}
}

func adbShellQuote(value string) string {
	return "'" + strings.ReplaceAll(value, "'", `'"'"'`) + "'"
}
