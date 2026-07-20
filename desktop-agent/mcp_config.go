package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"net/url"
	"sort"
	"strings"
)

const (
	maxMCPServers       = 32
	maxMCPSecretEntries = 128
)

type mcpRuntimeConfig struct {
	MCPServerConfig
	Environment map[string]string
	Headers     map[string]string
}

func normalizeMCPServerConfigs(values []MCPServerConfig) []MCPServerConfig {
	result := make([]MCPServerConfig, 0, min(len(values), maxMCPServers))
	seenIDs := map[string]bool{}
	seenNames := map[string]int{}
	for _, value := range values {
		if len(result) >= maxMCPServers {
			break
		}
		value = normalizeMCPServerConfig(value)
		if value.ID == "" {
			value.ID = newID("mcp")
		}
		if seenIDs[value.ID] {
			continue
		}
		seenIDs[value.ID] = true
		baseName := value.Name
		key := strings.ToLower(baseName)
		seenNames[key]++
		if seenNames[key] > 1 {
			value.Name = truncateRunes(fmt.Sprintf("%s (%d)", baseName, seenNames[key]), 80)
		}
		result = append(result, value)
	}
	return result
}

func normalizeMCPServerConfig(value MCPServerConfig) MCPServerConfig {
	value.ID = strings.TrimSpace(value.ID)
	value.Name = truncateRunes(strings.TrimSpace(value.Name), 80)
	if value.Name == "" {
		value.Name = "MCP Server"
	}
	value.Transport = strings.ToLower(strings.TrimSpace(value.Transport))
	if value.Transport != mcpTransportStreamableHTTP && value.Transport != mcpTransportLegacySSE {
		value.Transport = mcpTransportStdio
	}
	value.Command = strings.TrimSpace(value.Command)
	value.Cwd = strings.TrimSpace(value.Cwd)
	value.URL = strings.TrimSpace(value.URL)
	if value.RequestTimeoutSeconds < 1 || value.RequestTimeoutSeconds > 600 {
		value.RequestTimeoutSeconds = 60
	}
	args := make([]string, 0, min(len(value.Args), 64))
	for _, argument := range value.Args {
		if len(args) >= 64 {
			break
		}
		argument = strings.TrimSpace(argument)
		if argument != "" {
			args = append(args, truncateRunes(argument, 4096))
		}
	}
	value.Args = args
	value.TrustedReadOnlyTools = normalizeMCPToolNames(value.TrustedReadOnlyTools)
	return value
}

func normalizeMCPToolNames(values []string) []string {
	result := make([]string, 0, min(len(values), 200))
	seen := map[string]bool{}
	for _, value := range values {
		value = strings.TrimSpace(value)
		key := strings.ToLower(value)
		if value == "" || seen[key] || len(result) >= 200 {
			continue
		}
		seen[key] = true
		result = append(result, truncateRunes(value, 256))
	}
	return result
}

func validateMCPServerConfig(value MCPServerConfig) error {
	if value.ID == "" || value.Name == "" {
		return errors.New("MCP 服务器缺少 ID 或名称")
	}
	switch value.Transport {
	case mcpTransportStdio:
		if value.Enabled && value.Command == "" {
			return fmt.Errorf("MCP 服务器 %q 缺少启动命令", value.Name)
		}
	case mcpTransportStreamableHTTP, mcpTransportLegacySSE:
		if value.Enabled && value.URL == "" {
			return fmt.Errorf("MCP 服务器 %q 缺少 HTTP/SSE 地址", value.Name)
		}
		if value.URL != "" {
			parsed, err := url.Parse(value.URL)
			if err != nil || parsed.Host == "" || (parsed.Scheme != "http" && parsed.Scheme != "https") {
				return fmt.Errorf("MCP 服务器 %q 的地址必须是有效 HTTP/HTTPS URL", value.Name)
			}
			if parsed.User != nil || parsed.Fragment != "" {
				return fmt.Errorf("MCP 服务器 %q 的地址不能包含账户信息或片段", value.Name)
			}
		}
	default:
		return fmt.Errorf("MCP 服务器 %q 使用了未知传输类型", value.Name)
	}
	return nil
}

func cloneMCPServerConfigs(values []MCPServerConfig) []MCPServerConfig {
	result := append([]MCPServerConfig{}, values...)
	for index := range result {
		result[index].Args = append([]string{}, result[index].Args...)
		result[index].TrustedReadOnlyTools = append([]string{}, result[index].TrustedReadOnlyTools...)
	}
	return result
}

func (store *desktopStore) saveMCPServers(request SaveMCPServersRequest) ([]PublicMCPServerConfig, error) {
	store.mu.Lock()
	defer store.mu.Unlock()
	if len(request.Servers) > maxMCPServers {
		return nil, fmt.Errorf("最多保存 %d 个 MCP 服务器", maxMCPServers)
	}
	existing := make(map[string]MCPServerConfig, len(store.config.MCPServers))
	for _, value := range store.config.MCPServers {
		existing[value.ID] = value
	}
	servers := make([]MCPServerConfig, 0, len(request.Servers))
	seenIDs := map[string]bool{}
	seenNames := map[string]bool{}
	for _, input := range request.Servers {
		input.ID = strings.TrimSpace(input.ID)
		if input.ID == "" {
			input.ID = newID("mcp")
		}
		if seenIDs[input.ID] {
			return nil, fmt.Errorf("MCP 服务器 ID 重复：%s", input.ID)
		}
		seenIDs[input.ID] = true
		previous := existing[input.ID]
		server := MCPServerConfig{
			ID: input.ID, Name: input.Name, Transport: input.Transport, Command: input.Command,
			Args: append([]string{}, input.Args...), Cwd: input.Cwd, URL: input.URL,
			RequestTimeoutSeconds: input.RequestTimeoutSeconds,
			TrustedReadOnlyTools:  append([]string{}, input.TrustedReadOnlyTools...),
			Enabled:               input.Enabled, AutoStart: input.AutoStart,
			ProtectedEnvironmentJSON: previous.ProtectedEnvironmentJSON,
			ProtectedHeadersJSON:     previous.ProtectedHeadersJSON,
		}
		server = normalizeMCPServerConfig(server)
		nameKey := strings.ToLower(server.Name)
		if seenNames[nameKey] {
			return nil, fmt.Errorf("MCP 服务器名称重复：%s", server.Name)
		}
		seenNames[nameKey] = true
		if err := validateMCPServerConfig(server); err != nil {
			return nil, err
		}
		var err error
		switch {
		case input.ClearEnvironment:
			server.ProtectedEnvironmentJSON = ""
		case input.Environment != nil:
			server.ProtectedEnvironmentJSON, err = protectMCPSecretMap(input.Environment, false)
		}
		if err != nil {
			return nil, fmt.Errorf("MCP 服务器 %q 环境变量保存失败：%w", server.Name, err)
		}
		switch {
		case input.ClearHeaders:
			server.ProtectedHeadersJSON = ""
		case input.Headers != nil:
			server.ProtectedHeadersJSON, err = protectMCPSecretMap(input.Headers, true)
		}
		if err != nil {
			return nil, fmt.Errorf("MCP 服务器 %q 请求头保存失败：%w", server.Name, err)
		}
		servers = append(servers, server)
	}
	updated := store.config
	updated.MCPServers = servers
	updated.SchemaVersion = desktopConfigSchemaVersion
	if err := writeJSONAtomic(store.configPath, updated); err != nil {
		return nil, err
	}
	store.config = updated
	return publicMCPServerConfigs(servers)
}

func (store *desktopStore) mcpRuntimeConfigs() ([]mcpRuntimeConfig, error) {
	store.mu.Lock()
	servers := cloneMCPServerConfigs(store.config.MCPServers)
	store.mu.Unlock()
	result := make([]mcpRuntimeConfig, 0, len(servers))
	for _, server := range servers {
		environment, err := unprotectMCPSecretMap(server.ProtectedEnvironmentJSON)
		if err != nil {
			return nil, fmt.Errorf("无法解密 MCP 服务器 %q 的环境变量：%w", server.Name, err)
		}
		headers, err := unprotectMCPSecretMap(server.ProtectedHeadersJSON)
		if err != nil {
			return nil, fmt.Errorf("无法解密 MCP 服务器 %q 的请求头：%w", server.Name, err)
		}
		result = append(result, mcpRuntimeConfig{MCPServerConfig: server, Environment: environment, Headers: headers})
	}
	return result, nil
}

func (store *desktopStore) publicMCPServers() ([]PublicMCPServerConfig, error) {
	store.mu.Lock()
	servers := cloneMCPServerConfigs(store.config.MCPServers)
	store.mu.Unlock()
	return publicMCPServerConfigs(servers)
}

func publicMCPServerConfigs(servers []MCPServerConfig) ([]PublicMCPServerConfig, error) {
	result := make([]PublicMCPServerConfig, 0, len(servers))
	for _, server := range servers {
		environment, err := unprotectMCPSecretMap(server.ProtectedEnvironmentJSON)
		if err != nil {
			return nil, err
		}
		headers, err := unprotectMCPSecretMap(server.ProtectedHeadersJSON)
		if err != nil {
			return nil, err
		}
		result = append(result, PublicMCPServerConfig{
			ID: server.ID, Name: server.Name, Transport: server.Transport, Command: server.Command,
			Args: append([]string{}, server.Args...), Cwd: server.Cwd, URL: server.URL,
			RequestTimeoutSeconds: server.RequestTimeoutSeconds,
			TrustedReadOnlyTools:  append([]string{}, server.TrustedReadOnlyTools...),
			Enabled:               server.Enabled, AutoStart: server.AutoStart,
			EnvironmentKeys: sortedMCPSecretKeys(environment), HeaderKeys: sortedMCPSecretKeys(headers),
		})
	}
	return result, nil
}

func protectMCPSecretMap(values map[string]string, headers bool) (string, error) {
	normalized, err := normalizeMCPSecretMap(values, headers)
	if err != nil {
		return "", err
	}
	if len(normalized) == 0 {
		return "", nil
	}
	plain, err := json.Marshal(normalized)
	if err != nil {
		return "", err
	}
	return protectSecret(plain)
}

func unprotectMCPSecretMap(protected string) (map[string]string, error) {
	if protected == "" {
		return map[string]string{}, nil
	}
	plain, err := unprotectSecret(protected)
	if err != nil {
		return nil, err
	}
	var result map[string]string
	if err := json.Unmarshal(plain, &result); err != nil {
		return nil, err
	}
	if result == nil {
		result = map[string]string{}
	}
	return result, nil
}

func normalizeMCPSecretMap(values map[string]string, headers bool) (map[string]string, error) {
	if len(values) > maxMCPSecretEntries {
		return nil, fmt.Errorf("最多允许 %d 个键值", maxMCPSecretEntries)
	}
	result := make(map[string]string, len(values))
	for rawKey, value := range values {
		key := strings.TrimSpace(rawKey)
		if key == "" || len(key) > 256 || strings.ContainsAny(key, "\x00\r\n") {
			return nil, fmt.Errorf("键名 %q 无效", rawKey)
		}
		if !headers && strings.Contains(key, "=") {
			return nil, fmt.Errorf("环境变量名 %q 无效", key)
		}
		if headers {
			for _, character := range key {
				if character <= 32 || character >= 127 || strings.ContainsRune("()<>@,;:\\\"/[]?={} \t", character) {
					return nil, fmt.Errorf("请求头名称 %q 无效", key)
				}
			}
			if strings.ContainsAny(value, "\r\n") {
				return nil, fmt.Errorf("请求头 %q 的值不能换行", key)
			}
		}
		if len(value) > 65_536 {
			return nil, fmt.Errorf("%q 的值过长", key)
		}
		result[key] = value
	}
	return result, nil
}

func sortedMCPSecretKeys(values map[string]string) []string {
	result := make([]string, 0, len(values))
	for key := range values {
		result = append(result, key)
	}
	sort.Strings(result)
	return result
}

func mcpRuntimeSecretValues(config mcpRuntimeConfig) []string {
	values := make([]string, 0, len(config.Environment)+len(config.Headers)+4)
	seen := map[string]bool{}
	appendValue := func(value string) {
		if len(value) < 4 || seen[value] {
			return
		}
		seen[value] = true
		values = append(values, value)
	}
	for _, value := range config.Environment {
		appendValue(value)
	}
	for _, value := range config.Headers {
		appendValue(value)
	}
	if parsed, err := url.Parse(config.URL); err == nil {
		for _, entries := range parsed.Query() {
			for _, value := range entries {
				appendValue(value)
			}
		}
	}
	return values
}
