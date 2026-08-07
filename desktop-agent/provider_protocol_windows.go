//go:build windows

package main

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"golang.org/x/sys/windows/registry"
)

const (
	protocolClassesPrefix = `Software\Classes\`
	protocolBackupKey     = `Software\Murong\ProviderImportProtocol`
)

type ProviderImportProtocolState struct {
	Supported                   bool   `json:"supported"`
	MurongRegistered            bool   `json:"murongRegistered"`
	CCSwitchCompatibility       bool   `json:"ccSwitchCompatibilityEnabled"`
	CCSwitchCurrentHandlerLabel string `json:"ccSwitchCurrentHandlerLabel"`
}

func ensureNativeProviderImportProtocol() error {
	executable, err := os.Executable()
	if err != nil {
		return err
	}
	return writeURLProtocol("murongagent", executable, "URL:Murong Provider Import")
}

func getProviderImportProtocolState() ProviderImportProtocolState {
	executable, _ := os.Executable()
	murongCommand, _ := readProtocolCommand("murongagent")
	ccSwitchCommand, _ := readProtocolCommand("ccswitch")
	return ProviderImportProtocolState{
		Supported:                   true,
		MurongRegistered:            protocolCommandTargetsExecutable(murongCommand, executable),
		CCSwitchCompatibility:       protocolCommandTargetsExecutable(ccSwitchCommand, executable),
		CCSwitchCurrentHandlerLabel: protocolCommandLabel(ccSwitchCommand, executable),
	}
}

func setCCSwitchProtocolCompatibility(enabled bool) (ProviderImportProtocolState, error) {
	executable, err := os.Executable()
	if err != nil {
		return getProviderImportProtocolState(), err
	}
	current, currentExists := readProtocolCommand("ccswitch")
	owned := protocolCommandTargetsExecutable(current, executable)
	if enabled {
		if !owned {
			backup, _, createErr := registry.CreateKey(registry.CURRENT_USER, protocolBackupKey, registry.SET_VALUE)
			if createErr != nil {
				return getProviderImportProtocolState(), createErr
			}
			if currentExists {
				_ = backup.SetDWordValue("HadPreviousCommand", 1)
				_ = backup.SetStringValue("PreviousCommand", current)
			} else {
				_ = backup.SetDWordValue("HadPreviousCommand", 0)
				_ = backup.DeleteValue("PreviousCommand")
			}
			backup.Close()
		}
		if err := writeURLProtocol("ccswitch", executable, "URL:CC Switch Protocol (Murong compatibility)"); err != nil {
			return getProviderImportProtocolState(), err
		}
		return getProviderImportProtocolState(), nil
	}
	if !owned {
		return getProviderImportProtocolState(), nil
	}
	hadPrevious, previous := readPreviousProtocolCommand()
	if hadPrevious {
		if err := writeProtocolCommand("ccswitch", previous); err != nil {
			return getProviderImportProtocolState(), err
		}
	} else if err := removeOwnedProtocol("ccswitch"); err != nil {
		return getProviderImportProtocolState(), err
	}
	_ = registry.DeleteKey(registry.CURRENT_USER, protocolBackupKey)
	return getProviderImportProtocolState(), nil
}

func writeURLProtocol(scheme, executable, label string) error {
	rootPath := protocolClassesPrefix + scheme
	root, _, err := registry.CreateKey(registry.CURRENT_USER, rootPath, registry.SET_VALUE)
	if err != nil {
		return err
	}
	if err := root.SetStringValue("", label); err != nil {
		root.Close()
		return err
	}
	if err := root.SetStringValue("URL Protocol", ""); err != nil {
		root.Close()
		return err
	}
	root.Close()
	return writeProtocolCommand(scheme, fmt.Sprintf(`"%s" "%%1"`, executable))
}

func writeProtocolCommand(scheme, command string) error {
	key, _, err := registry.CreateKey(
		registry.CURRENT_USER,
		protocolClassesPrefix+scheme+`\shell\open\command`,
		registry.SET_VALUE,
	)
	if err != nil {
		return err
	}
	defer key.Close()
	return key.SetStringValue("", command)
}

func readProtocolCommand(scheme string) (string, bool) {
	key, err := registry.OpenKey(
		registry.CURRENT_USER,
		protocolClassesPrefix+scheme+`\shell\open\command`,
		registry.QUERY_VALUE,
	)
	if err != nil {
		return "", false
	}
	defer key.Close()
	value, _, err := key.GetStringValue("")
	return strings.TrimSpace(value), err == nil && strings.TrimSpace(value) != ""
}

func readPreviousProtocolCommand() (bool, string) {
	key, err := registry.OpenKey(registry.CURRENT_USER, protocolBackupKey, registry.QUERY_VALUE)
	if err != nil {
		return false, ""
	}
	defer key.Close()
	had, _, _ := key.GetIntegerValue("HadPreviousCommand")
	previous, _, _ := key.GetStringValue("PreviousCommand")
	return had == 1 && strings.TrimSpace(previous) != "", previous
}

func removeOwnedProtocol(scheme string) error {
	paths := []string{
		protocolClassesPrefix + scheme + `\shell\open\command`,
		protocolClassesPrefix + scheme + `\shell\open`,
		protocolClassesPrefix + scheme + `\shell`,
		protocolClassesPrefix + scheme,
	}
	for _, path := range paths {
		if err := registry.DeleteKey(registry.CURRENT_USER, path); err != nil && !errors.Is(err, registry.ErrNotExist) {
			return err
		}
	}
	return nil
}

func protocolCommandTargetsExecutable(command, executable string) bool {
	if command == "" || executable == "" {
		return false
	}
	normalize := func(value string) string {
		value = strings.Trim(strings.TrimSpace(value), `"`)
		absolute, err := filepath.Abs(value)
		if err == nil {
			value = absolute
		}
		return strings.ToLower(filepath.Clean(value))
	}
	commandExecutable := command
	if strings.HasPrefix(commandExecutable, `"`) {
		if end := strings.Index(commandExecutable[1:], `"`); end >= 0 {
			commandExecutable = commandExecutable[1 : end+1]
		}
	} else if space := strings.IndexAny(commandExecutable, " \t"); space >= 0 {
		commandExecutable = commandExecutable[:space]
	}
	return normalize(commandExecutable) == normalize(executable)
}

func protocolCommandLabel(command, executable string) string {
	if command == "" {
		return "尚未注册"
	}
	if protocolCommandTargetsExecutable(command, executable) {
		return "Murong"
	}
	command = strings.TrimSpace(command)
	if len(command) > 160 {
		command = command[:160] + "..."
	}
	return command
}
