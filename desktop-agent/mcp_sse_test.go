package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"
)

func TestLegacySSEMCPClientInitializesListsAndCallsTools(t *testing.T) {
	messages := make(chan string, 16)
	streamClosed := make(chan struct{})
	var closeOnce sync.Once
	var server *httptest.Server
	server = httptest.NewServer(http.HandlerFunc(func(response http.ResponseWriter, request *http.Request) {
		if request.Header.Get("Authorization") != "Bearer legacy-secret" {
			t.Errorf("legacy SSE request lost configured header: %#v", request.Header)
		}
		switch request.URL.Path {
		case "/sse":
			if request.Method != http.MethodGet || !strings.Contains(request.Header.Get("Accept"), "text/event-stream") {
				t.Errorf("unexpected SSE handshake: %s %#v", request.Method, request.Header)
			}
			flusher, ok := response.(http.Flusher)
			if !ok {
				t.Fatal("test server does not support flushing")
			}
			response.Header().Set("Content-Type", "text/event-stream")
			response.WriteHeader(http.StatusOK)
			fmt.Fprint(response, "event: endpoint\ndata: /message?sessionId=legacy-session\n\n")
			flusher.Flush()
			for {
				select {
				case message := <-messages:
					fmt.Fprintf(response, "event: message\ndata: %s\n\n", message)
					flusher.Flush()
				case <-request.Context().Done():
					closeOnce.Do(func() { close(streamClosed) })
					return
				}
			}
		case "/message":
			if request.Method != http.MethodPost || request.URL.Query().Get("sessionId") != "legacy-session" || request.Header.Get("Content-Type") != "application/json" {
				t.Errorf("unexpected legacy message request: %s %s %#v", request.Method, request.URL.String(), request.Header)
			}
			var rpc struct {
				JSONRPC string          `json:"jsonrpc"`
				ID      json.RawMessage `json:"id"`
				Method  string          `json:"method"`
			}
			if err := json.NewDecoder(request.Body).Decode(&rpc); err != nil || rpc.JSONRPC != "2.0" {
				t.Errorf("invalid JSON-RPC request: %#v, %v", rpc, err)
			}
			var result string
			switch rpc.Method {
			case "initialize":
				result = `{"protocolVersion":"2025-11-25","serverInfo":{"name":"legacy-test","version":"1"}}`
			case "notifications/initialized":
			case "tools/list":
				result = `{"tools":[{"name":"echo","description":"Echo text","inputSchema":{"type":"object","properties":{"text":{"type":"string"}}}}]}`
			case "tools/call":
				result = `{"content":[{"type":"text","text":"legacy works"}]}`
			default:
				t.Errorf("unexpected MCP method: %s", rpc.Method)
			}
			response.WriteHeader(http.StatusAccepted)
			if len(rpc.ID) > 0 && result != "" {
				messages <- fmt.Sprintf(`{"jsonrpc":"2.0","id":%s,"result":%s}`, rpc.ID, result)
			}
		default:
			http.NotFound(response, request)
		}
	}))
	defer server.Close()

	client, err := newLegacySSEMCPClient(context.Background(), mcpRuntimeConfig{MCPServerConfig: MCPServerConfig{
		URL: server.URL + "/sse", RequestTimeoutSeconds: 3,
	}, Headers: map[string]string{"Authorization": "Bearer legacy-secret"}})
	if err != nil {
		t.Fatal(err)
	}
	tools, err := initializeMCP(context.Background(), client)
	if err != nil || len(tools) != 1 || tools[0].Name != "echo" {
		t.Fatalf("legacy MCP initialize failed: %#v, %v", tools, err)
	}
	result, err := callMCPRemoteTool(context.Background(), client, "echo", map[string]any{"text": "hello"})
	if err != nil || !strings.Contains(string(result), "legacy works") {
		t.Fatalf("legacy MCP tool call failed: %s, %v", result, err)
	}
	if err := client.Close(); err != nil {
		t.Fatal(err)
	}
	select {
	case <-streamClosed:
	case <-time.After(3 * time.Second):
		t.Fatal("legacy SSE stream did not close")
	}
}

func TestLegacySSEMCPRejectsCrossOriginMessageEndpoint(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(response http.ResponseWriter, request *http.Request) {
		response.Header().Set("Content-Type", "text/event-stream")
		fmt.Fprint(response, "event: endpoint\ndata: https://example.invalid/message?sessionId=secret\n\n")
		response.(http.Flusher).Flush()
	}))
	defer server.Close()
	_, err := newLegacySSEMCPClient(context.Background(), mcpRuntimeConfig{MCPServerConfig: MCPServerConfig{
		URL: server.URL + "/sse", RequestTimeoutSeconds: 2,
	}})
	if err == nil || !strings.Contains(err.Error(), "必须与 SSE 地址同源") {
		t.Fatalf("cross-origin legacy endpoint was not rejected: %v", err)
	}
}

func TestLegacySSEConfigNormalizesAndValidates(t *testing.T) {
	config := normalizeMCPServerConfig(MCPServerConfig{ID: "legacy", Name: "Legacy", Transport: mcpTransportLegacySSE, URL: "http://127.0.0.1/sse", Enabled: true})
	if config.Transport != mcpTransportLegacySSE {
		t.Fatalf("legacy SSE transport was normalized away: %#v", config)
	}
	if err := validateMCPServerConfig(config); err != nil {
		t.Fatal(err)
	}
}
