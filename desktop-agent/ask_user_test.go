package main

import (
	"context"
	"strings"
	"testing"
	"time"
)

func TestParseAskUserRequestRejectsMalformedOrAmbiguousQuestions(t *testing.T) {
	valid := `{"questions":[{"header":"平台","question":"优先处理哪个平台？","options":[{"label":"桌面","description":"先补齐桌面端"},{"label":"手机"}],"multiSelect":false}]}`
	request, err := parseAskUserRequest(valid, "session-1")
	if err != nil || request.SessionID != "session-1" || len(request.Questions) != 1 || request.Questions[0].ID != "q1" {
		t.Fatalf("unexpected valid request: %#v, %v", request, err)
	}
	tests := []string{
		`{"questions":[]}`,
		`{"questions":[{"question":"选择","options":[{"label":"重复"},{"label":"重复"}]}]}`,
		`{"questions":[{"question":"选择","options":[{"label":"只有一个"}]}]}`,
		`{"questions":[{"question":"选择","options":[{"label":"一"},{"label":"二"}],"unknown":true}]}`,
		valid + `{}`,
	}
	for _, arguments := range tests {
		if _, err := parseAskUserRequest(arguments, "session-1"); err == nil {
			t.Fatalf("invalid ask_user arguments were accepted: %s", arguments)
		}
	}
}

func TestAskUserBlocksAndResumesWithValidatedAnswer(t *testing.T) {
	app := &DesktopAgentApp{
		asks: map[string]chan askResult{}, pendingAsks: map[string]AskRequest{}, pendingApprovals: map[string]ApprovalRequest{},
	}
	call := modelToolCall{Function: modelToolFunction{Name: askUserToolName, Arguments: `{"questions":[{"header":"范围","question":"选择范围","options":[{"label":"桌面"},{"label":"手机"}]}]}`}}
	resultChannel := make(chan string, 1)
	errorChannel := make(chan error, 1)
	go func() {
		result, err := app.executeAskUserTool(context.Background(), "session-1", call)
		if err != nil {
			errorChannel <- err
			return
		}
		resultChannel <- result
	}()
	pending := waitForPendingAsk(t, app, "session-1")
	if _, err := app.ResolveAsk(AskDecision{ID: pending.ID, Answers: []AskAnswer{{QuestionID: "q1", SelectedOptions: []string{"未知", "桌面"}}}}); err == nil {
		t.Fatal("mixed custom and predefined answer was accepted")
	}
	if app.PendingAsk("session-1") == nil {
		t.Fatal("invalid answer removed the pending question")
	}
	resolved, err := app.ResolveAsk(AskDecision{ID: pending.ID, Answers: []AskAnswer{{QuestionID: "q1", SelectedOptions: []string{"桌面"}}}})
	if err != nil || !resolved {
		t.Fatalf("failed to resolve ask_user: %v", err)
	}
	select {
	case result := <-resultChannel:
		if !strings.Contains(result, "范围：桌面") {
			t.Fatalf("unexpected formatted answer: %s", result)
		}
	case err := <-errorChannel:
		t.Fatal(err)
	case <-time.After(time.Second):
		t.Fatal("ask_user did not resume")
	}
}

func TestAskUserCancellationClearsPendingRequest(t *testing.T) {
	app := &DesktopAgentApp{
		asks: map[string]chan askResult{}, pendingAsks: map[string]AskRequest{}, pendingApprovals: map[string]ApprovalRequest{},
	}
	ctx, cancel := context.WithCancel(context.Background())
	result := make(chan error, 1)
	go func() {
		_, err := app.executeAskUserTool(ctx, "session-1", modelToolCall{Function: modelToolFunction{
			Name: askUserToolName, Arguments: `{"questions":[{"question":"继续吗？","options":[{"label":"继续"},{"label":"停止"}]}]}`,
		}})
		result <- err
	}()
	waitForPendingAsk(t, app, "session-1")
	cancel()
	select {
	case err := <-result:
		if err == nil || err != context.Canceled {
			t.Fatalf("unexpected cancellation result: %v", err)
		}
	case <-time.After(time.Second):
		t.Fatal("cancelled ask_user did not return")
	}
	if app.PendingAsk("session-1") != nil {
		t.Fatal("cancelled ask_user remained pending")
	}
}

func TestAskUserIsPlanSafeButUnavailableToSubagents(t *testing.T) {
	app := &DesktopAgentApp{}
	if !toolListContains(app.planModeToolDefinitions(app.toolDefinitions(defaultDesktopConfig())), askUserToolName) {
		t.Fatal("plan mode removed ask_user")
	}
	if isSubagentDirectToolAllowed(askUserToolName) {
		t.Fatal("subagents were allowed to call ask_user")
	}
}

func waitForPendingAsk(t *testing.T, app *DesktopAgentApp, sessionID string) *AskRequest {
	t.Helper()
	deadline := time.Now().Add(time.Second)
	for time.Now().Before(deadline) {
		if request := app.PendingAsk(sessionID); request != nil {
			return request
		}
		time.Sleep(5 * time.Millisecond)
	}
	t.Fatal("ask_user request did not become pending")
	return nil
}
