package main

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"unicode"
)

const (
	mcpProtocolVersion       = "2025-11-25"
	maxMCPMessageBytes       = 8 << 20
	maxMCPToolResultBytes    = 1 << 20
	maxMCPToolsPerServer     = 200
	maxMCPToolDescriptionRun = 2_000
)

type mcpRPCError struct {
	Code    int             `json:"code"`
	Message string          `json:"message"`
	Data    json.RawMessage `json:"data,omitempty"`
}

type mcpRPCResponse struct {
	JSONRPC string          `json:"jsonrpc"`
	ID      json.RawMessage `json:"id,omitempty"`
	Result  json.RawMessage `json:"result,omitempty"`
	Error   *mcpRPCError    `json:"error,omitempty"`
}

type mcpRPCTransport interface {
	Request(ctx context.Context, method string, params any, result any) error
	Notify(ctx context.Context, method string, params any) error
	Close() error
}

type mcpRemoteTool struct {
	Name        string         `json:"name"`
	Title       string         `json:"title,omitempty"`
	Description string         `json:"description,omitempty"`
	InputSchema map[string]any `json:"inputSchema"`
}

type mcpToolDefinition struct {
	CanonicalName   string
	Name            string
	ServerID        string
	ServerName      string
	Description     string
	InputSchema     map[string]any
	TrustedReadOnly bool
}

func initializeMCP(ctx context.Context, transport mcpRPCTransport) ([]mcpRemoteTool, error) {
	var initialized struct {
		ProtocolVersion string `json:"protocolVersion"`
		ServerInfo      struct {
			Name    string `json:"name"`
			Version string `json:"version"`
		} `json:"serverInfo"`
	}
	err := transport.Request(ctx, "initialize", map[string]any{
		"protocolVersion": mcpProtocolVersion,
		"capabilities":    map[string]any{},
		"clientInfo": map[string]any{
			"name": "Murong Desktop Agent", "version": "1.0.0",
		},
	}, &initialized)
	if err != nil {
		return nil, fmt.Errorf("MCP initialize 失败：%w", err)
	}
	if strings.TrimSpace(initialized.ProtocolVersion) == "" {
		return nil, errors.New("MCP initialize 响应缺少 protocolVersion")
	}
	if err := transport.Notify(ctx, "notifications/initialized", map[string]any{}); err != nil {
		return nil, fmt.Errorf("MCP initialized 通知失败：%w", err)
	}
	return listMCPTools(ctx, transport)
}

func listMCPTools(ctx context.Context, transport mcpRPCTransport) ([]mcpRemoteTool, error) {
	result := make([]mcpRemoteTool, 0)
	cursor := ""
	for page := 0; page < 20; page++ {
		params := map[string]any{}
		if cursor != "" {
			params["cursor"] = cursor
		}
		var response struct {
			Tools      []mcpRemoteTool `json:"tools"`
			NextCursor string          `json:"nextCursor,omitempty"`
		}
		if err := transport.Request(ctx, "tools/list", params, &response); err != nil {
			return nil, fmt.Errorf("MCP tools/list 失败：%w", err)
		}
		for _, tool := range response.Tools {
			tool.Name = strings.TrimSpace(tool.Name)
			if tool.Name == "" {
				continue
			}
			if tool.InputSchema == nil {
				tool.InputSchema = map[string]any{"type": "object", "properties": map[string]any{}}
			}
			if schemaType, _ := tool.InputSchema["type"].(string); schemaType == "" {
				tool.InputSchema["type"] = "object"
			}
			result = append(result, tool)
			if len(result) >= maxMCPToolsPerServer {
				return result, nil
			}
		}
		cursor = strings.TrimSpace(response.NextCursor)
		if cursor == "" {
			return result, nil
		}
	}
	return nil, errors.New("MCP tools/list 分页超过 20 页")
}

func callMCPRemoteTool(ctx context.Context, transport mcpRPCTransport, name string, arguments map[string]any) (json.RawMessage, error) {
	var result json.RawMessage
	if err := transport.Request(ctx, "tools/call", map[string]any{"name": name, "arguments": arguments}, &result); err != nil {
		return nil, err
	}
	if len(result) > maxMCPToolResultBytes {
		return nil, fmt.Errorf("MCP 工具结果超过 %d 字节上限", maxMCPToolResultBytes)
	}
	return result, nil
}

func canonicalMCPToolName(serverName, rawToolName string) string {
	server := canonicalMCPIdentifierPart(serverName)
	tool := canonicalMCPIdentifierPart(rawToolName)
	name := "mcp__" + server + "__" + tool
	if len(name) <= 64 {
		return name
	}
	digest := sha256.Sum256([]byte(serverName + "\x00" + rawToolName))
	suffix := "_" + hex.EncodeToString(digest[:4])
	return strings.TrimRight(name[:64-len(suffix)], "_") + suffix
}

func canonicalMCPIdentifierPart(value string) string {
	value = strings.ToLower(strings.TrimSpace(value))
	var result strings.Builder
	underscore := false
	for _, character := range value {
		if (character >= 'a' && character <= 'z') || (character >= '0' && character <= '9') {
			result.WriteRune(character)
			underscore = false
			continue
		}
		if unicode.IsLetter(character) || unicode.IsDigit(character) || !underscore {
			result.WriteByte('_')
			underscore = true
		}
	}
	part := strings.Trim(result.String(), "_")
	if part == "" {
		return "unnamed"
	}
	return part
}

func mcpRPCErrorValue(response mcpRPCResponse) error {
	if response.Error == nil {
		return nil
	}
	message := strings.TrimSpace(response.Error.Message)
	if message == "" {
		message = "未知 JSON-RPC 错误"
	}
	return fmt.Errorf("JSON-RPC %d：%s", response.Error.Code, message)
}
