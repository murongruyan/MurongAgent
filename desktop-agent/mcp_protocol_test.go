package main

import (
	"bufio"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"sync"
	"testing"
	"time"
)

func TestStreamableHTTPMCPConnectListsAndCallsTools(t *testing.T) {
	var mu sync.Mutex
	methods := []string{}
	sawSession := false
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		if request.Method == http.MethodDelete {
			writer.WriteHeader(http.StatusNoContent)
			return
		}
		if request.Header.Get("Authorization") != "Bearer test-token" {
			t.Errorf("missing protected authorization header")
		}
		if request.Header.Get("MCP-Protocol-Version") != mcpProtocolVersion {
			t.Errorf("unexpected MCP protocol header: %q", request.Header.Get("MCP-Protocol-Version"))
		}
		var message map[string]json.RawMessage
		if err := json.NewDecoder(request.Body).Decode(&message); err != nil {
			t.Errorf("decode request: %v", err)
			writer.WriteHeader(http.StatusBadRequest)
			return
		}
		var method string
		_ = json.Unmarshal(message["method"], &method)
		mu.Lock()
		methods = append(methods, method)
		if method != "initialize" && request.Header.Get("Mcp-Session-Id") == "session-test" {
			sawSession = true
		}
		mu.Unlock()
		if method == "notifications/initialized" {
			writer.WriteHeader(http.StatusAccepted)
			return
		}
		writer.Header().Set("Content-Type", "application/json")
		writer.Header().Set("Mcp-Session-Id", "session-test")
		var result any
		switch method {
		case "initialize":
			result = map[string]any{
				"protocolVersion": mcpProtocolVersion,
				"capabilities":    map[string]any{"tools": map[string]any{}},
				"serverInfo":      map[string]any{"name": "test", "version": "1"},
			}
		case "tools/list":
			result = map[string]any{"tools": []any{map[string]any{
				"name": "search", "description": "Search test records",
				"inputSchema": map[string]any{"type": "object", "properties": map[string]any{"query": map[string]any{"type": "string"}}, "required": []string{"query"}},
			}}}
		case "tools/call":
			result = map[string]any{"content": []any{map[string]any{"type": "text", "text": "found"}}, "isError": false}
		default:
			writer.WriteHeader(http.StatusBadRequest)
			return
		}
		_ = json.NewEncoder(writer).Encode(map[string]any{"jsonrpc": "2.0", "id": json.RawMessage(message["id"]), "result": result})
	}))
	defer server.Close()

	manager := newMCPManager()
	config := mcpRuntimeConfig{MCPServerConfig: MCPServerConfig{
		ID: "http_test", Name: "Test Search", Transport: mcpTransportStreamableHTTP, URL: server.URL,
		RequestTimeoutSeconds: 5, Enabled: true, AutoStart: true, TrustedReadOnlyTools: []string{"search"},
	}, Headers: map[string]string{"Authorization": "Bearer test-token"}}
	manager.ConnectAll(context.Background(), []mcpRuntimeConfig{config}, false)
	defer manager.Close()
	definitions := manager.ToolDefinitions()
	if len(definitions) != 1 || definitions[0].CanonicalName != "mcp__test_search__search" || !definitions[0].TrustedReadOnly {
		t.Fatalf("unexpected MCP tools: %#v", definitions)
	}
	result, err := manager.CallTool(context.Background(), definitions[0].CanonicalName, map[string]any{"query": "hello"})
	if err != nil || !strings.Contains(result, "found") {
		t.Fatalf("unexpected MCP call result %q: %v", result, err)
	}
	mu.Lock()
	defer mu.Unlock()
	if !sawSession || strings.Join(methods, ",") != "initialize,notifications/initialized,tools/list,tools/call" {
		t.Fatalf("unexpected HTTP MCP sequence %v, session=%v", methods, sawSession)
	}
}

func TestMCPStreamableHTTPSSEBodySelectsMatchingResponse(t *testing.T) {
	body := []byte("event: message\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\"}\n\n" +
		"event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{\"tools\":[]}}\n\n")
	response, err := parseMCPHTTPResponse(body, "text/event-stream", "7")
	if err != nil || !strings.Contains(string(response.Result), "tools") {
		t.Fatalf("unexpected SSE parse result %#v: %v", response, err)
	}
}

func TestStdioMCPConnectListsAndCallsTools(t *testing.T) {
	config := mcpRuntimeConfig{MCPServerConfig: MCPServerConfig{
		ID: "stdio_test", Name: "Local Test", Transport: mcpTransportStdio,
		Command: os.Args[0], Args: []string{"-test.run=^TestMCPStdioHelperProcess$"},
		RequestTimeoutSeconds: 5, Enabled: true,
	}, Environment: map[string]string{"GO_WANT_MCP_HELPER_PROCESS": "1"}}
	client, err := newStdioMCPClient(config)
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	tools, err := initializeMCP(ctx, client)
	if err != nil {
		t.Fatal(err)
	}
	if len(tools) != 1 || tools[0].Name != "echo" {
		t.Fatalf("unexpected stdio tools: %#v", tools)
	}
	result, err := callMCPRemoteTool(ctx, client, "echo", map[string]any{"text": "hello"})
	if err != nil || !strings.Contains(string(result), "hello") {
		t.Fatalf("unexpected stdio call result %s: %v", result, err)
	}
}

func TestMCPStdioHelperProcess(t *testing.T) {
	if os.Getenv("GO_WANT_MCP_HELPER_PROCESS") != "1" {
		return
	}
	scanner := bufio.NewScanner(os.Stdin)
	scanner.Buffer(make([]byte, 64<<10), maxMCPMessageBytes)
	writer := bufio.NewWriter(os.Stdout)
	for scanner.Scan() {
		var request map[string]json.RawMessage
		if json.Unmarshal(scanner.Bytes(), &request) != nil {
			continue
		}
		if len(request["id"]) == 0 {
			continue
		}
		var method string
		_ = json.Unmarshal(request["method"], &method)
		var result any
		switch method {
		case "initialize":
			result = map[string]any{"protocolVersion": mcpProtocolVersion, "capabilities": map[string]any{"tools": map[string]any{}}, "serverInfo": map[string]any{"name": "helper", "version": "1"}}
		case "tools/list":
			result = map[string]any{
				"tools": []any{map[string]any{
					"name": "echo", "description": "Echo text",
					"inputSchema": map[string]any{
						"type":       "object",
						"properties": map[string]any{"text": map[string]any{"type": "string"}},
					},
				}},
			}
		case "tools/call":
			var params struct {
				Arguments map[string]any `json:"arguments"`
			}
			_ = json.Unmarshal(request["params"], &params)
			result = map[string]any{"content": []any{map[string]any{"type": "text", "text": fmt.Sprint(params.Arguments["text"])}}}
		default:
			continue
		}
		response := map[string]any{"jsonrpc": "2.0", "id": json.RawMessage(request["id"]), "result": result}
		encoded, _ := json.Marshal(response)
		_, _ = writer.Write(append(encoded, '\n'))
		_ = writer.Flush()
	}
}

func TestMCPToolsJoinAgentPlanModeAndComposer(t *testing.T) {
	t.Setenv("MURONG_DESKTOP_DATA_DIR", t.TempDir())
	store, err := newDesktopStore()
	if err != nil {
		t.Fatal(err)
	}
	manager := newMCPManager()
	manager.tools["mcp__docs__lookup"] = mcpToolDefinition{
		CanonicalName: "mcp__docs__lookup", Name: "lookup", ServerID: "docs", ServerName: "Docs",
		Description: "Look up docs", InputSchema: map[string]any{"type": "object", "properties": map[string]any{}}, TrustedReadOnly: true,
	}
	app := &DesktopAgentApp{store: store, mcp: manager}
	definitions := app.toolDefinitions(defaultDesktopConfig())
	if !toolListContains(definitions, "mcp__docs__lookup") {
		t.Fatal("connected MCP tool was not registered with the agent")
	}
	if !toolListContains(app.planModeToolDefinitions(definitions), "mcp__docs__lookup") {
		t.Fatal("trusted read-only MCP tool should remain available in plan mode")
	}
	catalog := app.GetComposerCatalog()
	if len(catalog.MCPTools) != 1 || catalog.MCPTools[0].ID != "mcp__docs__lookup" {
		t.Fatalf("unexpected composer MCP catalog: %#v", catalog.MCPTools)
	}
	validated, err := app.validateComposerContext(defaultDesktopConfig(), []ComposerContextItem{{Kind: "mcp", ID: "mcp__docs__lookup"}})
	if err != nil || len(validated) != 1 || validated[0].Label != "lookup" {
		t.Fatalf("MCP composer context was not validated: %#v, %v", validated, err)
	}
}

type staticMCPTransport struct{}

func (staticMCPTransport) Request(_ context.Context, method string, _ any, result any) error {
	if method != "tools/call" {
		return fmt.Errorf("unexpected method %s", method)
	}
	if raw, ok := result.(*json.RawMessage); ok {
		*raw = json.RawMessage(`{"content":[{"type":"text","text":"ok"}]}`)
	}
	return nil
}

func (staticMCPTransport) Notify(context.Context, string, any) error { return nil }
func (staticMCPTransport) Close() error                              { return nil }

func TestMCPToolApprovalUsesTrustedReadOnlyAllowlistAndYoloSemantics(t *testing.T) {
	manager := newMCPManager()
	tool := mcpToolDefinition{
		CanonicalName: "mcp__write__mutate", Name: "mutate", ServerID: "write", ServerName: "Write",
		InputSchema: map[string]any{"type": "object", "properties": map[string]any{}},
	}
	manager.tools[tool.CanonicalName] = tool
	manager.connections[tool.ServerID] = &mcpConnection{transport: staticMCPTransport{}}
	app := &DesktopAgentApp{mcp: manager, approvals: map[string]chan bool{}}
	call := modelToolCall{Function: modelToolFunction{Name: tool.CanonicalName, Arguments: `{}`}}
	config := defaultDesktopConfig()
	config.ApprovalMode = approvalReadOnly
	if _, err := app.executeTool(context.Background(), "session", config, nil, call); err == nil || !strings.Contains(err.Error(), "只读模式") {
		t.Fatalf("untrusted MCP tool should be blocked in readonly mode, got %v", err)
	}

	trusted := tool
	trusted.TrustedReadOnly = true
	manager.tools[tool.CanonicalName] = trusted
	if result, err := app.executeTool(context.Background(), "session", config, nil, call); err != nil || !strings.Contains(result, "ok") {
		t.Fatalf("trusted MCP tool should run in readonly mode: %q, %v", result, err)
	}

	manager.tools[tool.CanonicalName] = tool
	config.ApprovalMode = approvalAllowlist
	config.Allowlist = []string{"tool:" + tool.CanonicalName}
	if _, err := app.executeTool(context.Background(), "session", config, nil, call); err != nil {
		t.Fatalf("allowlisted MCP tool should run without approval: %v", err)
	}

	config.ApprovalMode = approvalYolo
	config.Allowlist = nil
	if _, err := app.executeTool(context.Background(), "session", config, nil, call); err != nil {
		t.Fatalf("YOLO MCP tool should run without hidden approval: %v", err)
	}
}

func toolListContains(tools []any, name string) bool {
	for _, tool := range tools {
		if functionToolName(tool) == name {
			return true
		}
	}
	return false
}
