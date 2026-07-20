package main

import (
	"context"
	"encoding/json"
	"strings"
	"testing"
)

func TestSessionHistorySearchFiltersProjectAndReturnsStableReference(t *testing.T) {
	t.Setenv("MURONG_DESKTOP_DATA_DIR", t.TempDir())
	projectA, projectB := t.TempDir(), t.TempDir()
	store, err := newDesktopStore()
	if err != nil {
		t.Fatal(err)
	}
	if _, err := store.activateProject(projectA); err != nil {
		t.Fatal(err)
	}
	current, err := store.createSession("当前任务")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := store.appendMessage(current.ID, ChatMessage{Role: "user", Content: "当前也提到了架构决策"}); err != nil {
		t.Fatal(err)
	}
	previous, err := store.createSession("桌面架构记录")
	if err != nil {
		t.Fatal(err)
	}
	previous, err = store.appendMessage(previous.ID, ChatMessage{Role: "assistant", Content: "架构决策：会话必须绑定自己的项目目录。"})
	if err != nil {
		t.Fatal(err)
	}
	anchorID := previous.Messages[0].ID
	if _, err := store.activateProject(projectB); err != nil {
		t.Fatal(err)
	}
	other, err := store.createSession("另一个项目")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := store.appendMessage(other.ID, ChatMessage{Role: "assistant", Content: "架构决策在另一个项目"}); err != nil {
		t.Fatal(err)
	}

	app := &DesktopAgentApp{store: store}
	config := defaultDesktopConfig()
	config.ProjectPath = projectA
	config.ApprovalMode = approvalReadOnly
	arguments, _ := json.Marshal(map[string]any{"query": "架构决策", "project_only": true})
	result, err := app.executeTool(context.Background(), current.ID, config, nil, modelToolCall{
		Function: modelToolFunction{Name: "session_history_search", Arguments: string(arguments)},
	})
	if err != nil {
		t.Fatal(err)
	}
	var payload struct {
		Success bool                  `json:"success"`
		Kind    string                `json:"kind"`
		Matches []sessionHistoryMatch `json:"matches"`
	}
	if err := json.Unmarshal([]byte(result), &payload); err != nil {
		t.Fatal(err)
	}
	if !payload.Success || payload.Kind != "search" || len(payload.Matches) != 1 {
		t.Fatalf("unexpected search payload: %s", result)
	}
	match := payload.Matches[0]
	if match.SessionID != previous.ID || match.AnchorMessageID != anchorID || match.MessageReference != previous.ID+"#"+anchorID {
		t.Fatalf("search did not return the expected stable reference: %#v", match)
	}
	if strings.Contains(result, current.ID) || strings.Contains(result, other.ID) {
		t.Fatalf("search ignored current/project filters: %s", result)
	}
}

func TestSessionHistoryExcerptUsesStringMessageIDsAndBoundedWindow(t *testing.T) {
	t.Setenv("MURONG_DESKTOP_DATA_DIR", t.TempDir())
	project := t.TempDir()
	store, err := newDesktopStore()
	if err != nil {
		t.Fatal(err)
	}
	if _, err := store.activateProject(project); err != nil {
		t.Fatal(err)
	}
	current, _ := store.createSession("当前")
	history, _ := store.createSession("历史")
	for index := 0; index < 9; index++ {
		history, err = store.appendMessage(history.ID, ChatMessage{Role: "assistant", Content: strings.Repeat("内容", 700)})
		if err != nil {
			t.Fatal(err)
		}
	}
	anchor := history.Messages[4].ID
	app := &DesktopAgentApp{store: store}
	config := defaultDesktopConfig()
	config.ProjectPath = project
	config.ApprovalMode = approvalReadOnly
	arguments, _ := json.Marshal(map[string]any{
		"message_reference":     history.ID + "#" + anchor,
		"excerpt_message_limit": 3,
	})
	result, err := app.executeSessionHistoryTool(context.Background(), current.ID, config, modelToolCall{
		Function: modelToolFunction{Name: "session_history_search", Arguments: string(arguments)},
	})
	if err != nil {
		t.Fatal(err)
	}
	var payload struct {
		Success bool                  `json:"success"`
		Kind    string                `json:"kind"`
		Excerpt sessionHistoryExcerpt `json:"excerpt"`
	}
	if err := json.Unmarshal([]byte(result), &payload); err != nil {
		t.Fatal(err)
	}
	if !payload.Success || payload.Kind != "excerpt" || payload.Excerpt.AnchorMessageID != anchor || len(payload.Excerpt.Messages) != 3 {
		t.Fatalf("unexpected excerpt payload: %s", result)
	}
	for _, message := range payload.Excerpt.Messages {
		if len([]rune(message.Content)) > maxHistoryMessageRunes || message.Reference != history.ID+"#"+message.ID {
			t.Fatalf("excerpt was not bounded or referenced: %#v", message)
		}
	}
	if _, err := app.executeSessionHistoryTool(context.Background(), current.ID, config, modelToolCall{
		Function: modelToolFunction{Name: "session_history_search", Arguments: `{"session_id":"` + current.ID + `"}`},
	}); err == nil || !strings.Contains(err.Error(), "未找到允许读取") {
		t.Fatalf("current-session exclusion was not enforced: %v", err)
	}
}

func TestSessionHistoryToolIsAlwaysAvailableInPlanModeAndValidatesArguments(t *testing.T) {
	app := &DesktopAgentApp{}
	config := defaultDesktopConfig()
	config.EnabledBuiltinTools = nil
	definitions := app.planModeToolDefinitions(app.toolDefinitions(config))
	found := false
	for _, definition := range definitions {
		found = found || functionToolName(definition) == "session_history_search"
	}
	if !found || !isDesktopToolEnabled(config, "session_history_search") {
		t.Fatal("history search was not available as an internal read-only plan tool")
	}
	if _, err := parseSessionHistoryRequest(`{"limit":21}`); err == nil {
		t.Fatal("history search accepted an out-of-range limit")
	}
	if _, err := parseSessionHistoryRequest(`{"unknown":true}`); err == nil {
		t.Fatal("history search accepted an unknown argument")
	}
	if _, err := parseSessionHistoryRequest(`{} {}`); err == nil {
		t.Fatal("history search accepted trailing JSON")
	}
}
