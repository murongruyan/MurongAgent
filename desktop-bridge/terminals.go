package desktopbridge

import (
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"runtime"
	"strings"
	"time"
	"unicode/utf16"
)

var windowsVersionPattern = regexp.MustCompile(`[0-9]+(?:\.[0-9]+){2,3}`)

const (
	terminalPowerShell7       = "powershell7"
	terminalWindowsPowerShell = "windows-powershell"
	terminalCMD               = "cmd"
	terminalWSLPrefix         = "wsl:"
	terminalPOSIXPrefix       = "shell:"
)

type terminalBackend struct {
	ID           string `json:"id"`
	Label        string `json:"label"`
	Version      string `json:"version,omitempty"`
	Kind         string `json:"-"`
	Executable   string `json:"-"`
	Distribution string `json:"-"`
}

type terminalInventory struct {
	Backends            []terminalBackend
	WindowsTerminalHost string
}

func discoverTerminalInventory(ctx context.Context) terminalInventory {
	if runtime.GOOS == "windows" {
		return discoverWindowsTerminalInventory(ctx)
	}
	return discoverUnixTerminalInventory(ctx)
}

func discoverWindowsTerminalInventory(ctx context.Context) terminalInventory {
	inventory := terminalInventory{}
	if path, err := exec.LookPath("pwsh.exe"); err == nil {
		inventory.Backends = append(inventory.Backends, terminalBackend{
			ID: terminalPowerShell7, Label: "PowerShell 7", Version: probeShellVersion(ctx, path, "$PSVersionTable.PSVersion.ToString()"), Kind: terminalPowerShell7, Executable: path,
		})
	}
	if path, err := exec.LookPath("powershell.exe"); err == nil {
		inventory.Backends = append(inventory.Backends, terminalBackend{
			ID: terminalWindowsPowerShell, Label: "Windows PowerShell", Version: probeShellVersion(ctx, path, "$PSVersionTable.PSVersion.ToString()"), Kind: terminalWindowsPowerShell, Executable: path,
		})
	}
	if path, err := exec.LookPath("cmd.exe"); err == nil {
		versionOutput := strings.TrimSpace(probeCommand(ctx, path, "/u", "/d", "/c", "ver"))
		version := windowsVersionPattern.FindString(versionOutput)
		if version == "" {
			version = versionOutput
		}
		inventory.Backends = append(inventory.Backends, terminalBackend{
			ID: terminalCMD, Label: "命令提示符 (CMD)", Version: version, Kind: terminalCMD, Executable: path,
		})
	}
	if path, err := exec.LookPath("wsl.exe"); err == nil {
		inventory.Backends = append(inventory.Backends, discoverWSLBackends(ctx, path)...)
	}
	if path, err := exec.LookPath("powershell.exe"); err == nil {
		version := probeShellVersion(ctx, path, "(Get-AppxPackage Microsoft.WindowsTerminal -ErrorAction SilentlyContinue).Version.ToString()")
		if version != "" {
			inventory.WindowsTerminalHost = "Windows Terminal " + version
		}
	}
	return inventory
}

func discoverUnixTerminalInventory(ctx context.Context) terminalInventory {
	inventory := terminalInventory{}
	seen := map[string]bool{}
	appendShell := func(id, label, executable string) {
		path, err := exec.LookPath(executable)
		if filepath.IsAbs(executable) {
			path = executable
			_, err = os.Stat(path)
		}
		if err != nil {
			return
		}
		key := strings.ToLower(filepath.Clean(path))
		if seen[key] {
			return
		}
		seen[key] = true
		inventory.Backends = append(inventory.Backends, terminalBackend{
			ID: id, Label: label, Version: probePOSIXShellVersion(ctx, path), Kind: "posix-shell", Executable: path,
		})
	}
	if loginShell := strings.TrimSpace(os.Getenv("SHELL")); loginShell != "" {
		appendShell(terminalPOSIXPrefix+"login", "登录 Shell · "+filepath.Base(loginShell), loginShell)
	}
	for _, shell := range []struct{ name, label string }{
		{"bash", "Bash"}, {"zsh", "Zsh"}, {"fish", "Fish"}, {"sh", "POSIX sh"},
	} {
		appendShell(terminalPOSIXPrefix+shell.name, shell.label, shell.name)
	}
	if path, err := exec.LookPath("pwsh"); err == nil {
		inventory.Backends = append(inventory.Backends, terminalBackend{
			ID: terminalPowerShell7, Label: "PowerShell 7", Version: probeShellVersion(ctx, path, "$PSVersionTable.PSVersion.ToString()"), Kind: terminalPowerShell7, Executable: path,
		})
	}
	return inventory
}

func probePOSIXShellVersion(ctx context.Context, executable string) string {
	output := strings.TrimSpace(probeCommand(ctx, executable, "--version"))
	if newline := strings.IndexByte(output, '\n'); newline >= 0 {
		output = output[:newline]
	}
	return truncateRunes(output, 120)
}

func probeShellVersion(ctx context.Context, executable, expression string) string {
	return strings.TrimSpace(probeCommand(ctx, executable, "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", expression))
}

func probeCommand(ctx context.Context, executable string, arguments ...string) string {
	probeContext, cancel := context.WithTimeout(ctx, 4*time.Second)
	defer cancel()
	command := exec.CommandContext(probeContext, executable, arguments...)
	prepareHiddenCommand(command)
	output, err := command.Output()
	if err != nil {
		return ""
	}
	return decodeCommandBytes(output)
}

func discoverWSLBackends(ctx context.Context, executable string) []terminalBackend {
	probeContext, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	command := exec.CommandContext(probeContext, executable, "--list", "--verbose")
	prepareHiddenCommand(command)
	output, err := command.Output()
	if err != nil {
		return nil
	}
	lines := strings.Split(strings.ReplaceAll(decodeCommandBytes(output), "\r\n", "\n"), "\n")
	backends := make([]terminalBackend, 0, len(lines))
	for _, line := range lines {
		line = strings.TrimSpace(strings.TrimPrefix(strings.TrimSpace(line), "*"))
		fields := strings.Fields(line)
		if len(fields) < 3 || strings.EqualFold(fields[0], "NAME") {
			continue
		}
		version := fields[len(fields)-1]
		name := strings.Join(fields[:len(fields)-2], " ")
		if name == "" || (version != "1" && version != "2") {
			continue
		}
		backend, available := probeWSLDistribution(ctx, executable, name, version)
		if available {
			backends = append(backends, backend)
		}
	}
	return backends
}

func probeWSLDistribution(ctx context.Context, executable, distribution, wslVersion string) (terminalBackend, bool) {
	probeContext, cancel := context.WithTimeout(ctx, 12*time.Second)
	defer cancel()
	command := exec.CommandContext(
		probeContext,
		executable,
		"-d", distribution,
		"--exec", "sh", "-lc", "printf 'MURONG_WSL_READY\\n'; uname -sr 2>/dev/null || true",
	)
	prepareHiddenCommand(command)
	output, err := command.Output()
	if err != nil || probeContext.Err() != nil {
		return terminalBackend{}, false
	}
	lines := strings.Split(strings.ReplaceAll(decodeCommandBytes(output), "\r\n", "\n"), "\n")
	if len(lines) == 0 || strings.TrimSpace(lines[0]) != "MURONG_WSL_READY" {
		return terminalBackend{}, false
	}
	version := "WSL " + wslVersion
	if len(lines) > 1 {
		if guestVersion := truncateRunes(strings.TrimSpace(lines[1]), 80); guestVersion != "" {
			version += " · " + guestVersion
		}
	}
	return terminalBackend{
		ID: terminalWSLPrefix + distribution, Label: "WSL · " + distribution,
		Version: version, Kind: "wsl", Executable: executable, Distribution: distribution,
	}, true
}

func resolveConfiguredTerminals(config nodeConfig, inventory terminalInventory) (nodeConfig, map[string]terminalBackend, error) {
	selected := append([]string(nil), config.TerminalBackends...)
	if len(selected) == 0 && config.AllowTerminal {
		if runtime.GOOS == "windows" {
			selected = []string{terminalWindowsPowerShell}
		} else if recommended := recommendedTerminalID(inventory); recommended != "" {
			selected = []string{recommended}
		}
	}
	available := make(map[string]terminalBackend, len(inventory.Backends))
	for _, backend := range inventory.Backends {
		available[backend.ID] = backend
	}
	resolved := make(map[string]terminalBackend, len(selected))
	normalized := make([]string, 0, len(selected))
	seen := map[string]bool{}
	for _, id := range selected {
		id = strings.TrimSpace(id)
		if id == "" || seen[id] {
			continue
		}
		backend, ok := available[id]
		if !ok {
			return config, nil, fmt.Errorf("已配置的终端不可用：%s", id)
		}
		seen[id] = true
		normalized = append(normalized, id)
		resolved[id] = backend
	}
	config.TerminalBackends = normalized
	config.AllowTerminal = len(normalized) > 0
	return config, resolved, nil
}

func recommendedTerminalID(inventory terminalInventory) string {
	preferredIDs := []string{terminalPowerShell7, terminalWindowsPowerShell}
	if runtime.GOOS != "windows" {
		preferredIDs = []string{terminalPOSIXPrefix + "login", terminalPOSIXPrefix + "bash", terminalPOSIXPrefix + "zsh", terminalPOSIXPrefix + "sh", terminalPowerShell7}
	}
	for _, preferred := range preferredIDs {
		for _, backend := range inventory.Backends {
			if backend.ID == preferred {
				return preferred
			}
		}
	}
	return ""
}

func buildTerminalCommand(ctx context.Context, backend terminalBackend, directory, commandText string) (*exec.Cmd, error) {
	switch backend.Kind {
	case terminalPowerShell7, terminalWindowsPowerShell:
		prefix := "$ProgressPreference='SilentlyContinue';" +
			"[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false);" +
			"$OutputEncoding=[Console]::OutputEncoding;"
		arguments := []string{"-NoLogo", "-NoProfile", "-NonInteractive"}
		if runtime.GOOS == "windows" {
			arguments = append(arguments, "-ExecutionPolicy", "Bypass")
		}
		arguments = append(arguments, "-Command", prefix+commandText)
		command := exec.CommandContext(ctx, backend.Executable, arguments...)
		command.Dir = directory
		return command, nil
	case terminalCMD:
		command := exec.CommandContext(ctx, backend.Executable, "/d", "/s", "/c", "chcp 65001>nul & "+commandText)
		command.Dir = directory
		return command, nil
	case "wsl":
		if strings.TrimSpace(backend.Distribution) == "" {
			return nil, errors.New("WSL 发行版为空")
		}
		command := exec.CommandContext(ctx, backend.Executable, "-d", backend.Distribution, "--cd", directory, "--exec", "sh", "-lc", commandText)
		command.Dir = directory
		return command, nil
	case "posix-shell":
		command := exec.CommandContext(ctx, backend.Executable, "-lc", commandText)
		command.Dir = directory
		return command, nil
	default:
		return nil, fmt.Errorf("不支持的终端类型：%s", backend.Kind)
	}
}

func decodeCommandBytes(data []byte) string {
	if len(data) >= 2 && looksLikeUTF16LE(data) {
		if len(data)%2 != 0 {
			data = data[:len(data)-1]
		}
		words := make([]uint16, len(data)/2)
		for index := range words {
			words[index] = binary.LittleEndian.Uint16(data[index*2:])
		}
		return strings.TrimPrefix(string(utf16.Decode(words)), "\ufeff")
	}
	return strings.ToValidUTF8(string(data), "�")
}

func looksLikeUTF16LE(data []byte) bool {
	if len(data) >= 2 && data[0] == 0xff && data[1] == 0xfe {
		return true
	}
	if len(data) < 4 {
		return false
	}
	zeroes := 0
	samples := 0
	for index := 1; index < len(data) && samples < 128; index += 2 {
		samples++
		if data[index] == 0 {
			zeroes++
		}
	}
	return samples > 0 && zeroes*100/samples >= 60
}
