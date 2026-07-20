package main

import (
	"context"
	"encoding/json"
	"testing"
)

func TestApprovalModes(t *testing.T) {
	app := &DesktopAgentApp{approvals: map[string]chan bool{}, runs: map[string]context.CancelFunc{}}
	read := ApprovalRequest{ID: "read", ToolName: "read_file", Risk: "low"}
	write := ApprovalRequest{ID: "write", ToolName: "write_file", Risk: "high"}

	if err := app.authorizeTool(context.Background(), desktopConfig{ApprovalMode: approvalReadOnly}, read); err != nil {
		t.Fatal(err)
	}
	if err := app.authorizeTool(context.Background(), desktopConfig{ApprovalMode: approvalReadOnly}, write); err == nil {
		t.Fatal("read-only mode allowed a write")
	}
	if err := app.authorizeTool(context.Background(), desktopConfig{ApprovalMode: approvalYolo}, write); err != nil {
		t.Fatal(err)
	}
	if err := app.authorizeTool(context.Background(), desktopConfig{ApprovalMode: approvalAllowlist, Allowlist: []string{"path:src/"}}, write, "path:src/main.go"); err != nil {
		t.Fatal(err)
	}
}

func TestDesktopAgentExposesExpectedTools(t *testing.T) {
	app := &DesktopAgentApp{terminals: []TerminalBackend{{ID: terminalCMD, Label: "CMD"}}}
	tools := app.toolDefinitions(defaultDesktopConfig())
	if len(tools) != 43 {
		t.Fatalf("expected 43 tools, got %d", len(tools))
	}
	want := map[string]bool{
		"session_history_search": true,
		"ask_user":               true,
		"complete_step":           true,
		"list_files":             true, "read_file": true, "write_file": true, "create_directory": true, "run_terminal": true,
		"file_exists": true, "delete_path": true, "chmod_path": true,
		"code_search": true, "workspace_diff": true, "code_edit": true, "web_search": true, "web_fetch": true,
		"subagent_launch": true, "subagent_jobs": true, "explore": true, "research": true, "review": true, "security_review": true,
		"github_repository": true, "github_read_file": true, "github_list_branches": true, "github_list_issues": true,
		"github_create_branch": true, "github_put_file": true, "github_create_issue": true, "github_create_pull_request": true,
		"memory_list": true, "memory_search": true, "memory_read": true, "remember_memory": true,
		"forget_memory": true, "read_skill": true, "run_skill": true, "create_global_rule": true, "create_global_memory": true, "create_global_skill": true,
		"create_project_rule": true, "create_project_memory": true, "create_project_skill": true,
	}
	for _, raw := range tools {
		definition := raw.(map[string]any)["function"].(map[string]any)
		delete(want, definition["name"].(string))
	}
	if len(want) != 0 {
		t.Fatalf("missing tools: %#v", want)
	}
}

func TestDesktopAgentFiltersDisabledToolsAndFileOperations(t *testing.T) {
	app := &DesktopAgentApp{terminals: []TerminalBackend{{ID: terminalCMD, Label: "CMD"}}}
	config := defaultDesktopConfig()
	config.EnabledBuiltinTools = []string{"file"}
	config.EnabledFileOperations = []string{"read"}
	tools := app.toolDefinitions(config)
	seen := map[string]bool{}
	for _, raw := range tools {
		definition := raw.(map[string]any)["function"].(map[string]any)
		seen[definition["name"].(string)] = true
	}
	if !seen["read_file"] || !seen["workspace_diff"] || seen["list_files"] || seen["write_file"] || seen["create_directory"] || seen["run_terminal"] {
		t.Fatalf("unexpected filtered tools: %#v", seen)
	}
	if isDesktopToolEnabled(config, "write_file") || isDesktopToolEnabled(config, "run_terminal") || !isDesktopToolEnabled(config, "workspace_diff") {
		t.Fatal("disabled tools passed the execution guard")
	}
}

func TestKnowledgeToolsPersistAndSearchMemory(t *testing.T) {
	t.Setenv("MURONG_DESKTOP_DATA_DIR", t.TempDir())
	store, err := newDesktopStore()
	if err != nil {
		t.Fatal(err)
	}
	app := &DesktopAgentApp{store: store, approvals: map[string]chan bool{}, runs: map[string]context.CancelFunc{}}
	config := desktopConfig{ApprovalMode: approvalYolo}

	rememberArgs, _ := json.Marshal(map[string]any{"title": "构建机器", "content": "本机完整构建通常需要八分钟"})
	result, err := app.executeKnowledgeTool(context.Background(), "session-test", config, modelToolCall{
		Function: modelToolFunction{Name: "remember_memory", Arguments: string(rememberArgs)},
	})
	if err != nil || result == "" {
		t.Fatalf("remember_memory failed: %v, %s", err, result)
	}
	library := store.knowledgeLibrary()
	if len(library.Memories) != 1 || !library.Memories[0].Enabled {
		t.Fatalf("unexpected library: %#v", library)
	}

	searchArgs, _ := json.Marshal(map[string]any{"query": "八分钟"})
	result, err = app.executeKnowledgeTool(context.Background(), "session-test", config, modelToolCall{
		Function: modelToolFunction{Name: "memory_search", Arguments: string(searchArgs)},
	})
	if err != nil || !containsBytes([]byte(result), []byte(library.Memories[0].ID)) {
		t.Fatalf("memory_search failed: %v, %s", err, result)
	}

	forgetArgs, _ := json.Marshal(map[string]any{"memory_id": library.Memories[0].ID})
	if _, err := app.executeKnowledgeTool(context.Background(), "session-test", config, modelToolCall{
		Function: modelToolFunction{Name: "forget_memory", Arguments: string(forgetArgs)},
	}); err != nil {
		t.Fatal(err)
	}
	if len(store.knowledgeLibrary().Memories) != 0 {
		t.Fatal("forget_memory did not remove the memory")
	}
}

func TestProjectKnowledgeToolsUseActiveProjectScope(t *testing.T) {
	t.Setenv("MURONG_DESKTOP_DATA_DIR", t.TempDir())
	project := t.TempDir()
	store, err := newDesktopStore()
	if err != nil {
		t.Fatal(err)
	}
	if _, err := store.saveSettings(SaveSettingsRequest{
		ProjectPath: project, BaseURL: "https://example.test/v1", Model: "test-model",
		ApprovalMode: approvalYolo, MaxToolIterations: 999, SystemPrompt: "project test", ResponseVerbosity: "BALANCED",
	}); err != nil {
		t.Fatal(err)
	}
	app := &DesktopAgentApp{store: store, approvals: map[string]chan bool{}, runs: map[string]context.CancelFunc{}}
	config := store.rawConfig()
	ruleArgs, _ := json.Marshal(map[string]any{"title": "仓库约束", "content": "修改后运行项目测试", "enabled": true})
	if _, err := app.executeKnowledgeTool(context.Background(), "session-test", config, modelToolCall{
		Function: modelToolFunction{Name: "create_project_rule", Arguments: string(ruleArgs)},
	}); err != nil {
		t.Fatal(err)
	}
	memoryArgs, _ := json.Marshal(map[string]any{"title": "构建", "content": "项目构建需要八分钟"})
	if _, err := app.executeKnowledgeTool(context.Background(), "session-test", config, modelToolCall{
		Function: modelToolFunction{Name: "remember_memory", Arguments: string(memoryArgs)},
	}); err != nil {
		t.Fatal(err)
	}
	projectKnowledge := store.projectKnowledge()
	if len(projectKnowledge.Library.Rules) != 1 || len(projectKnowledge.Library.Memories) != 1 {
		t.Fatalf("project tools wrote outside active scope: %#v", projectKnowledge)
	}
	if len(store.knowledgeLibrary().Memories) != 0 {
		t.Fatal("remember_memory should prefer the active project")
	}
	prompt := app.systemPrompt(store.rawConfig())
	if !containsBytes([]byte(prompt), []byte("修改后运行项目测试")) {
		t.Fatalf("project rule missing from prompt: %s", prompt)
	}
}
