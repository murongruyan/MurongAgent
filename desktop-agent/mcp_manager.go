package main

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"sort"
	"strings"
	"sync"
	"time"
)

type mcpConnection struct {
	config    mcpRuntimeConfig
	transport mcpRPCTransport
	toolNames []string
}

type mcpConnectResult struct {
	config    mcpRuntimeConfig
	transport mcpRPCTransport
	tools     []mcpToolDefinition
	err       error
}

type mcpManager struct {
	mu          sync.Mutex
	connections map[string]*mcpConnection
	statuses    map[string]MCPServerStatus
	tools       map[string]mcpToolDefinition
	generation  uint64
	listener    func()
}

func newMCPManager() *mcpManager {
	return &mcpManager{
		connections: map[string]*mcpConnection{}, statuses: map[string]MCPServerStatus{}, tools: map[string]mcpToolDefinition{},
	}
}

func (manager *mcpManager) SetListener(listener func()) {
	manager.mu.Lock()
	manager.listener = listener
	manager.mu.Unlock()
}

func (manager *mcpManager) ConnectAll(ctx context.Context, configs []mcpRuntimeConfig, autoStartOnly bool) {
	clients, generation := manager.beginConnect(configs, autoStartOnly)
	for _, client := range clients {
		_ = client.Close()
	}
	manager.notify()
	var wait sync.WaitGroup
	for _, config := range configs {
		if !config.Enabled || (autoStartOnly && !config.AutoStart) {
			continue
		}
		config := config
		wait.Add(1)
		go func() {
			defer wait.Done()
			manager.applyConnectResult(generation, manager.connectOne(ctx, config))
		}()
	}
	wait.Wait()
}

func (manager *mcpManager) beginConnect(configs []mcpRuntimeConfig, autoStartOnly bool) ([]mcpRPCTransport, uint64) {
	manager.mu.Lock()
	defer manager.mu.Unlock()
	clients := make([]mcpRPCTransport, 0, len(manager.connections))
	for _, connection := range manager.connections {
		clients = append(clients, connection.transport)
	}
	manager.generation++
	manager.connections = map[string]*mcpConnection{}
	manager.tools = map[string]mcpToolDefinition{}
	manager.statuses = map[string]MCPServerStatus{}
	for _, config := range configs {
		manager.statuses[config.ID] = MCPServerStatus{
			ID: config.ID, Name: config.Name,
			Connecting: config.Enabled && (!autoStartOnly || config.AutoStart),
			ToolNames:  []string{},
		}
	}
	return clients, manager.generation
}

func (manager *mcpManager) connectOne(parent context.Context, config mcpRuntimeConfig) mcpConnectResult {
	timeout := time.Duration(config.RequestTimeoutSeconds) * time.Second
	if timeout <= 0 {
		timeout = 60 * time.Second
	}
	ctx, cancel := context.WithTimeout(parent, timeout)
	defer cancel()
	var transport mcpRPCTransport
	var err error
	switch config.Transport {
	case mcpTransportStdio:
		transport, err = newStdioMCPClient(config)
	case mcpTransportStreamableHTTP:
		transport, err = newHTTPMCPClient(config)
	case mcpTransportLegacySSE:
		transport, err = newLegacySSEMCPClient(parent, config)
	default:
		err = fmt.Errorf("未知 MCP 传输：%s", config.Transport)
	}
	if err != nil {
		return mcpConnectResult{config: config, err: err}
	}
	remoteTools, err := initializeMCP(ctx, transport)
	if err != nil {
		_ = transport.Close()
		return mcpConnectResult{config: config, err: err}
	}
	tools, err := buildMCPToolDefinitions(config, remoteTools)
	if err != nil {
		_ = transport.Close()
		return mcpConnectResult{config: config, err: err}
	}
	return mcpConnectResult{config: config, transport: transport, tools: tools}
}

func (manager *mcpManager) applyConnectResult(generation uint64, result mcpConnectResult) {
	manager.mu.Lock()
	if generation != manager.generation {
		manager.mu.Unlock()
		if result.transport != nil {
			_ = result.transport.Close()
		}
		return
	}
	status := manager.statuses[result.config.ID]
	status.Connecting = false
	if result.err != nil {
		status.Connected = false
		status.Error = truncateRunes(result.err.Error(), 2_000)
		manager.statuses[result.config.ID] = status
		manager.mu.Unlock()
		manager.notify()
		return
	}
	toolNames := make([]string, 0, len(result.tools))
	for _, tool := range result.tools {
		if existing, collision := manager.tools[tool.CanonicalName]; collision {
			status.Error = fmt.Sprintf("工具名称冲突：%s 同时来自 %s 与 %s", tool.CanonicalName, existing.ServerName, tool.ServerName)
			continue
		}
		manager.tools[tool.CanonicalName] = tool
		toolNames = append(toolNames, tool.CanonicalName)
	}
	sort.Strings(toolNames)
	status.Connected = true
	status.ToolCount = len(toolNames)
	status.ToolNames = toolNames
	status.LastConnectedAt = time.Now().UnixMilli()
	manager.statuses[result.config.ID] = status
	manager.connections[result.config.ID] = &mcpConnection{config: result.config, transport: result.transport, toolNames: toolNames}
	manager.mu.Unlock()
	manager.notify()
}

func buildMCPToolDefinitions(config mcpRuntimeConfig, remote []mcpRemoteTool) ([]mcpToolDefinition, error) {
	sort.SliceStable(remote, func(i, j int) bool { return strings.ToLower(remote[i].Name) < strings.ToLower(remote[j].Name) })
	baseCounts := map[string]int{}
	for _, tool := range remote {
		baseCounts[canonicalMCPToolName(config.Name, tool.Name)]++
	}
	result := make([]mcpToolDefinition, 0, len(remote))
	for _, tool := range remote {
		canonical := canonicalMCPToolName(config.Name, tool.Name)
		if baseCounts[canonical] > 1 {
			canonical = disambiguateMCPToolName(canonical, tool.Name)
		}
		schema, err := json.Marshal(tool.InputSchema)
		if err != nil || len(schema) > 64<<10 {
			return nil, fmt.Errorf("MCP 工具 %q 的输入 schema 无效或超过 64 KiB", tool.Name)
		}
		description := strings.TrimSpace(tool.Description)
		if description == "" {
			description = strings.TrimSpace(tool.Title)
		}
		if description == "" {
			description = "由 MCP 服务器提供的工具"
		}
		result = append(result, mcpToolDefinition{
			CanonicalName: canonical, Name: tool.Name, ServerID: config.ID, ServerName: config.Name,
			Description: truncateRunes(description, maxMCPToolDescriptionRun), InputSchema: cloneAnyMap(tool.InputSchema),
			TrustedReadOnly: trustedMCPTool(config, tool.Name, canonical),
		})
	}
	return result, nil
}

func disambiguateMCPToolName(base, raw string) string {
	digest := sha256.Sum256([]byte(raw))
	suffix := "_" + hex.EncodeToString(digest[:3])
	if len(base)+len(suffix) <= 64 {
		return base + suffix
	}
	return strings.TrimRight(base[:64-len(suffix)], "_") + suffix
}

func trustedMCPTool(config mcpRuntimeConfig, raw, canonical string) bool {
	for _, allowed := range config.TrustedReadOnlyTools {
		if allowed == "*" || strings.EqualFold(allowed, raw) || strings.EqualFold(allowed, canonical) {
			return true
		}
	}
	return false
}

func (manager *mcpManager) State(servers []PublicMCPServerConfig, configError error) MCPState {
	manager.mu.Lock()
	defer manager.mu.Unlock()
	statuses := make([]MCPServerStatus, 0, len(servers)+1)
	for _, server := range servers {
		status, ok := manager.statuses[server.ID]
		if !ok {
			status = MCPServerStatus{ID: server.ID, Name: server.Name, ToolNames: []string{}}
		}
		status.ToolNames = append([]string{}, status.ToolNames...)
		statuses = append(statuses, status)
	}
	if configError != nil {
		statuses = append(statuses, MCPServerStatus{ID: "config", Name: "MCP 配置", Error: configError.Error(), ToolNames: []string{}})
	}
	tools := make([]MCPToolInfo, 0, len(manager.tools))
	for _, tool := range manager.tools {
		tools = append(tools, MCPToolInfo{
			ID: tool.CanonicalName, Name: tool.Name, ServerID: tool.ServerID, ServerName: tool.ServerName,
			Description: tool.Description, TrustedReadOnly: tool.TrustedReadOnly,
		})
	}
	sort.Slice(tools, func(i, j int) bool {
		if tools[i].ServerName != tools[j].ServerName {
			return strings.ToLower(tools[i].ServerName) < strings.ToLower(tools[j].ServerName)
		}
		return strings.ToLower(tools[i].Name) < strings.ToLower(tools[j].Name)
	})
	return MCPState{Servers: servers, Statuses: statuses, Tools: tools}
}

func (manager *mcpManager) ToolDefinitions() []mcpToolDefinition {
	manager.mu.Lock()
	defer manager.mu.Unlock()
	result := make([]mcpToolDefinition, 0, len(manager.tools))
	for _, tool := range manager.tools {
		tool.InputSchema = cloneAnyMap(tool.InputSchema)
		result = append(result, tool)
	}
	sort.Slice(result, func(i, j int) bool { return result[i].CanonicalName < result[j].CanonicalName })
	return result
}

func (manager *mcpManager) HasTool(name string) bool {
	manager.mu.Lock()
	defer manager.mu.Unlock()
	_, ok := manager.tools[name]
	return ok
}

func (manager *mcpManager) Tool(name string) (mcpToolDefinition, bool) {
	manager.mu.Lock()
	defer manager.mu.Unlock()
	tool, ok := manager.tools[name]
	if ok {
		tool.InputSchema = cloneAnyMap(tool.InputSchema)
	}
	return tool, ok
}

func (manager *mcpManager) IsTrustedReadOnly(name string) bool {
	manager.mu.Lock()
	defer manager.mu.Unlock()
	tool, ok := manager.tools[name]
	return ok && tool.TrustedReadOnly
}

func (manager *mcpManager) CallTool(ctx context.Context, name string, arguments map[string]any) (string, error) {
	manager.mu.Lock()
	tool, ok := manager.tools[name]
	connection := manager.connections[tool.ServerID]
	manager.mu.Unlock()
	if !ok || connection == nil {
		return "", fmt.Errorf("MCP 工具 %q 不存在或服务器未连接", name)
	}
	result, err := callMCPRemoteTool(ctx, connection.transport, tool.Name, arguments)
	if err != nil {
		return "", fmt.Errorf("调用 MCP 工具 %s/%s 失败：%w", tool.ServerName, tool.Name, err)
	}
	wrapped, err := json.Marshal(map[string]any{
		"success": true, "server": tool.ServerName, "tool": tool.Name, "result": json.RawMessage(result),
	})
	if err != nil {
		return "", err
	}
	if len(wrapped) > maxMCPToolResultBytes {
		return "", errors.New("MCP 工具结果超过大小上限")
	}
	return string(wrapped), nil
}

func (manager *mcpManager) Close() {
	manager.mu.Lock()
	manager.generation++
	clients := make([]mcpRPCTransport, 0, len(manager.connections))
	for _, connection := range manager.connections {
		clients = append(clients, connection.transport)
	}
	manager.connections = map[string]*mcpConnection{}
	manager.tools = map[string]mcpToolDefinition{}
	manager.mu.Unlock()
	for _, client := range clients {
		_ = client.Close()
	}
}

func (manager *mcpManager) notify() {
	manager.mu.Lock()
	listener := manager.listener
	manager.mu.Unlock()
	if listener != nil {
		listener()
	}
}

func cloneAnyMap(values map[string]any) map[string]any {
	if values == nil {
		return map[string]any{}
	}
	data, err := json.Marshal(values)
	if err != nil {
		return map[string]any{}
	}
	var result map[string]any
	if json.Unmarshal(data, &result) != nil || result == nil {
		return map[string]any{}
	}
	return result
}
