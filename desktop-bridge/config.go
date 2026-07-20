package desktopbridge

import (
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/url"
	"os"
	"path/filepath"
	"strings"
)

const nodeConfigSchemaVersion = 2

type nodeConfig struct {
	SchemaVersion             int      `json:"schemaVersion"`
	ConnectionMode            string   `json:"connectionMode,omitempty"`
	PhoneURL                  string   `json:"phoneUrl"`
	CloudRelayURL             string   `json:"cloudRelayUrl,omitempty"`
	CloudRelayRoomID          string   `json:"cloudRelayRoomId,omitempty"`
	ProtectedCloudRelaySecret string   `json:"protectedCloudRelaySecret,omitempty"`
	Workspace                 string   `json:"workspace"`
	Label                     string   `json:"label"`
	ClientName                string   `json:"clientName"`
	AllowWrite                bool     `json:"allowWrite"`
	AllowTerminal             bool     `json:"allowTerminal"`
	ShareDesktopTasks         bool     `json:"shareDesktopTasks,omitempty"`
	AllowAgentControl         bool     `json:"allowAgentControl,omitempty"`
	TerminalBackends          []string `json:"terminalBackends,omitempty"`
	ProtectedToken            string   `json:"protectedToken"`
	ProtectedSyncKey          string   `json:"protectedSyncKey,omitempty"`
}

func defaultNodeConfigPath() (string, error) {
	base := strings.TrimSpace(os.Getenv("LOCALAPPDATA"))
	if base == "" {
		var err error
		base, err = os.UserConfigDir()
		if err != nil {
			return "", err
		}
	}
	return filepath.Join(base, "Murong", "computer-node.json"), nil
}

func loadNodeConfig(path string) (nodeConfig, error) {
	config := nodeConfig{SchemaVersion: nodeConfigSchemaVersion, CloudRelayURL: OfficialCloudRelayURL}
	data, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return config, nil
	}
	if err != nil {
		return config, err
	}
	if err := json.Unmarshal(data, &config); err != nil {
		return config, fmt.Errorf("JSON 损坏：%w", err)
	}
	if config.SchemaVersion == 1 {
		config.SchemaVersion = nodeConfigSchemaVersion
		config.ConnectionMode = CloudRelayConnectionDirect
	}
	if config.SchemaVersion != nodeConfigSchemaVersion {
		return config, fmt.Errorf("不支持的配置版本 %d", config.SchemaVersion)
	}
	if strings.TrimSpace(config.ConnectionMode) == "" {
		config.ConnectionMode = CloudRelayConnectionDirect
	}
	if strings.TrimSpace(config.CloudRelayURL) == "" {
		config.CloudRelayURL = OfficialCloudRelayURL
	}
	return config, nil
}

func saveNodeConfig(path string, config nodeConfig) error {
	config.SchemaVersion = nodeConfigSchemaVersion
	data, err := json.MarshalIndent(config, "", "  ")
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return err
	}
	temp, err := os.CreateTemp(filepath.Dir(path), ".computer-node-*.tmp")
	if err != nil {
		return err
	}
	tempName := temp.Name()
	defer os.Remove(tempName)
	if err := temp.Chmod(0o600); err != nil {
		temp.Close()
		return err
	}
	if _, err := temp.Write(data); err != nil {
		temp.Close()
		return err
	}
	if err := temp.Sync(); err != nil {
		temp.Close()
		return err
	}
	if err := temp.Close(); err != nil {
		return err
	}
	return replaceFile(tempName, path)
}

func parsePrivatePhoneURL(raw string) (*url.URL, error) {
	if strings.TrimSpace(raw) == "" {
		return nil, errors.New("手机地址不能为空")
	}
	target, err := url.Parse(strings.TrimSpace(raw))
	if err != nil {
		return nil, err
	}
	if target.Scheme != "http" || target.User != nil {
		return nil, errors.New("首版只接受没有账号信息的 http:// 私网地址")
	}
	if target.Hostname() == "" || target.RawQuery != "" || target.Fragment != "" {
		return nil, errors.New("地址不能包含查询参数或片段")
	}
	if target.Path != "" && target.Path != "/" {
		return nil, errors.New("地址不能包含额外路径")
	}
	if target.Port() == "" {
		target.Host = net.JoinHostPort(target.Hostname(), "8765")
	}
	ip := net.ParseIP(target.Hostname())
	if ip == nil {
		return nil, errors.New("请使用手机的局域网或 Tailscale IP，避免 DNS 重绑定")
	}
	if !allowedPrivateIP(ip) {
		return nil, errors.New("只允许回环、RFC1918/ULA 或 Tailscale 100.64.0.0/10 私网地址")
	}
	target.Path = ""
	return target, nil
}

func allowedPrivateIP(ip net.IP) bool {
	if ip.IsLoopback() || ip.IsPrivate() || ip.IsLinkLocalUnicast() {
		return true
	}
	v4 := ip.To4()
	return v4 != nil && v4[0] == 100 && v4[1] >= 64 && v4[1] <= 127
}
