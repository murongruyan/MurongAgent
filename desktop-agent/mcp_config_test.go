package main

import (
	"os"
	"strings"
	"testing"
)

func TestMCPServerSecretsAreProtectedPreservedAndClearable(t *testing.T) {
	t.Setenv("MURONG_DESKTOP_DATA_DIR", t.TempDir())
	store, err := newDesktopStore()
	if err != nil {
		t.Fatal(err)
	}
	public, err := store.saveMCPServers(SaveMCPServersRequest{Servers: []SaveMCPServerConfig{{
		ID: "mcp_test", Name: "测试 MCP", Transport: mcpTransportStreamableHTTP,
		URL: "https://mcp.example.test/rpc", RequestTimeoutSeconds: 45, Enabled: true, AutoStart: true,
		TrustedReadOnlyTools: []string{"search"},
		Environment:          map[string]string{"MCP_TOKEN": "environment-secret"},
		Headers:              map[string]string{"Authorization": "Bearer header-secret"},
	}}})
	if err != nil {
		t.Fatal(err)
	}
	if len(public) != 1 || len(public[0].EnvironmentKeys) != 1 || public[0].EnvironmentKeys[0] != "MCP_TOKEN" || len(public[0].HeaderKeys) != 1 {
		t.Fatalf("unexpected public MCP config: %#v", public)
	}
	data, err := os.ReadFile(store.configPath)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(data), "environment-secret") || strings.Contains(string(data), "header-secret") {
		t.Fatal("MCP secret values were persisted in plaintext")
	}
	runtimeConfigs, err := store.mcpRuntimeConfigs()
	if err != nil {
		t.Fatal(err)
	}
	if runtimeConfigs[0].Environment["MCP_TOKEN"] != "environment-secret" || runtimeConfigs[0].Headers["Authorization"] != "Bearer header-secret" {
		t.Fatalf("MCP secrets did not round-trip: %#v", runtimeConfigs[0])
	}

	_, err = store.saveMCPServers(SaveMCPServersRequest{Servers: []SaveMCPServerConfig{{
		ID: "mcp_test", Name: "重命名 MCP", Transport: mcpTransportStreamableHTTP,
		URL: "https://mcp.example.test/rpc", RequestTimeoutSeconds: 60, Enabled: true, AutoStart: false,
	}}})
	if err != nil {
		t.Fatal(err)
	}
	runtimeConfigs, err = store.mcpRuntimeConfigs()
	if err != nil || runtimeConfigs[0].Headers["Authorization"] != "Bearer header-secret" || runtimeConfigs[0].Environment["MCP_TOKEN"] != "environment-secret" {
		t.Fatalf("blank secret editor should preserve values: %#v, %v", runtimeConfigs, err)
	}

	_, err = store.saveMCPServers(SaveMCPServersRequest{Servers: []SaveMCPServerConfig{{
		ID: "mcp_test", Name: "重命名 MCP", Transport: mcpTransportStreamableHTTP,
		URL: "https://mcp.example.test/rpc", RequestTimeoutSeconds: 60, Enabled: true,
		ClearEnvironment: true, ClearHeaders: true,
	}}})
	if err != nil {
		t.Fatal(err)
	}
	runtimeConfigs, err = store.mcpRuntimeConfigs()
	if err != nil || len(runtimeConfigs[0].Headers) != 0 || len(runtimeConfigs[0].Environment) != 0 {
		t.Fatalf("MCP secrets were not cleared: %#v, %v", runtimeConfigs, err)
	}
}

func TestMCPServerValidationRejectsDuplicateNamesAndInvalidHTTP(t *testing.T) {
	t.Setenv("MURONG_DESKTOP_DATA_DIR", t.TempDir())
	store, err := newDesktopStore()
	if err != nil {
		t.Fatal(err)
	}
	_, err = store.saveMCPServers(SaveMCPServersRequest{Servers: []SaveMCPServerConfig{
		{ID: "one", Name: "GitHub", Transport: mcpTransportStdio, Command: "node", Enabled: true},
		{ID: "two", Name: "github", Transport: mcpTransportStdio, Command: "node", Enabled: true},
	}})
	if err == nil || !strings.Contains(err.Error(), "名称重复") {
		t.Fatalf("expected duplicate-name rejection, got %v", err)
	}
	_, err = store.saveMCPServers(SaveMCPServersRequest{Servers: []SaveMCPServerConfig{{
		ID: "bad", Name: "Bad HTTP", Transport: mcpTransportStreamableHTTP, URL: "file:///tmp/mcp", Enabled: true,
	}}})
	if err == nil || !strings.Contains(err.Error(), "HTTP/HTTPS") {
		t.Fatalf("expected invalid URL rejection, got %v", err)
	}
}
