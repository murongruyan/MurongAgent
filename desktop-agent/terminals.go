package main

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

const (
	terminalPowerShell7       = "powershell7"
	terminalWindowsPowerShell = "windows-powershell"
	terminalCMD               = "cmd"
	terminalWSLPrefix         = "wsl:"
	terminalPOSIXPrefix       = "shell:"
)

var windowsVersionPattern = regexp.MustCompile(`[0-9]+(?:\.[0-9]+){2,3}`)

type TerminalBackend struct {
	ID           string `json:"id"`
	Label        string `json:"label"`
	Version      string `json:"version,omitempty"`
	Kind         string `json:"-"`
	Executable   string `json:"-"`
	Distribution string `json:"-"`
}

func discoverTerminalBackends(ctx context.Context) []TerminalBackend {
	if runtime.GOOS == "windows" {
		return discoverWindowsTerminalBackends(ctx)
	}
	return discoverUnixTerminalBackends(ctx)
}

func discoverWindowsTerminalBackends(ctx context.Context) []TerminalBackend {
	backends := make([]TerminalBackend, 0, 8)
	if path, err := exec.LookPath("pwsh.exe"); err == nil {
		backends = append(backends, TerminalBackend{
			ID: terminalPowerShell7, Label: "PowerShell 7", Version: probePowerShellVersion(ctx, path), Kind: terminalPowerShell7, Executable: path,
		})
	}
	if path, err := exec.LookPath("powershell.exe"); err == nil {
		backends = append(backends, TerminalBackend{
			ID: terminalWindowsPowerShell, Label: "Windows PowerShell", Version: probePowerShellVersion(ctx, path), Kind: terminalWindowsPowerShell, Executable: path,
		})
	}
	if path, err := exec.LookPath("cmd.exe"); err == nil {
		versionOutput := strings.TrimSpace(probeCommand(ctx, path, "/u", "/d", "/c", "ver"))
		version := windowsVersionPattern.FindString(versionOutput)
		if version == "" {
			version = versionOutput
		}
		backends = append(backends, TerminalBackend{ID: terminalCMD, Label: "命令提示符 (CMD)", Version: version, Kind: terminalCMD, Executable: path})
	}
	if path, err := exec.LookPath("wsl.exe"); err == nil {
		backends = append(backends, discoverWSLBackends(ctx, path)...)
	}
	return backends
}

func discoverUnixTerminalBackends(ctx context.Context) []TerminalBackend {
	backends := make([]TerminalBackend, 0, 6)
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
		backends = append(backends, TerminalBackend{
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
		backends = append(backends, TerminalBackend{
			ID: terminalPowerShell7, Label: "PowerShell 7", Version: probePowerShellVersion(ctx, path), Kind: terminalPowerShell7, Executable: path,
		})
	}
	return backends
}

func probePowerShellVersion(ctx context.Context, executable string) string {
	return strings.TrimSpace(probeCommand(ctx, executable, "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", "$PSVersionTable.PSVersion.ToString()"))
}

func probePOSIXShellVersion(ctx context.Context, executable string) string {
	output := strings.TrimSpace(probeCommand(ctx, executable, "--version"))
	if newline := strings.IndexByte(output, '\n'); newline >= 0 {
		output = output[:newline]
	}
	return truncateRunes(output, 120)
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

func discoverWSLBackends(ctx context.Context, executable string) []TerminalBackend {
	probeContext, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	command := exec.CommandContext(probeContext, executable, "--list", "--verbose")
	prepareHiddenCommand(command)
	output, err := command.Output()
	if err != nil {
		return nil
	}
	lines := strings.Split(strings.ReplaceAll(decodeCommandBytes(output), "\r\n", "\n"), "\n")
	backends := make([]TerminalBackend, 0, len(lines))
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
		backends = append(backends, TerminalBackend{
			ID: terminalWSLPrefix + name, Label: "WSL · " + name, Version: "WSL " + version, Kind: "wsl", Executable: executable, Distribution: name,
		})
	}
	return backends
}

func terminalByID(backends []TerminalBackend, id string) (TerminalBackend, bool) {
	if id == "" && len(backends) > 0 {
		return backends[0], true
	}
	for _, backend := range backends {
		if backend.ID == id {
			return backend, true
		}
	}
	return TerminalBackend{}, false
}

func buildTerminalCommand(ctx context.Context, backend TerminalBackend, directory, commandText string) (*exec.Cmd, error) {
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
