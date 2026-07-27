package main

import (
	"context"
	"sync/atomic"
	"testing"
	"time"
)

func TestSendMessageDuringRunPersistsGuidanceAndExplicitStopPreventsRestart(t *testing.T) {
	t.Setenv("MURONG_DESKTOP_DATA_DIR", t.TempDir())
	store, err := newDesktopStore()
	if err != nil {
		t.Fatal(err)
	}
	project := t.TempDir()
	if _, err := store.saveSettings(SaveSettingsRequest{
		ProjectPath: project, BaseURL: "https://api.example/v1", Model: "test-model", APIKey: "secret",
		ApprovalMode: approvalYolo, MaxToolIterations: 10, Temperature: 0.2, MaxTokens: 1024,
	}); err != nil {
		t.Fatal(err)
	}
	session, err := store.createSession("steering")
	if err != nil {
		t.Fatal(err)
	}
	var cancels atomic.Int32
	app := &DesktopAgentApp{
		store: store, runs: map[string]context.CancelFunc{session.ID: func() { cancels.Add(1) }},
		restartRuns: map[string]bool{}, approvals: map[string]chan bool{},
	}
	if err := app.SendMessage(SendMessageRequest{SessionID: session.ID, Content: "改用新的方向继续"}); err != nil {
		t.Fatal(err)
	}
	if cancels.Load() != 1 || !app.restartRuns[session.ID] {
		t.Fatalf("guidance did not cancel and arm a continuation: cancels=%d restart=%v", cancels.Load(), app.restartRuns[session.ID])
	}
	updated := store.getSession(session.ID)
	if len(updated.Messages) != 1 || updated.Messages[0].Role != "user" || updated.Messages[0].Content != "改用新的方向继续" {
		t.Fatalf("guidance was not persisted exactly once: %#v", updated.Messages)
	}
	if !app.CancelRun(session.ID) {
		t.Fatal("explicit stop did not find the active run")
	}
	app.finishRun(session.ID)
	time.Sleep(60 * time.Millisecond)
	app.mu.Lock()
	_, running := app.runs[session.ID]
	_, restarting := app.restartRuns[session.ID]
	app.mu.Unlock()
	if running || restarting {
		t.Fatalf("explicit stop unexpectedly restarted the run: running=%v restarting=%v", running, restarting)
	}
}

func TestFreshSendClearsStaleSteeringRestart(t *testing.T) {
	t.Setenv("MURONG_DESKTOP_DATA_DIR", t.TempDir())
	store, err := newDesktopStore()
	if err != nil {
		t.Fatal(err)
	}
	if _, err := store.saveSettings(SaveSettingsRequest{
		ProjectPath: t.TempDir(), BaseURL: "https://api.example/v1", Model: "test-model", APIKey: "secret",
		ApprovalMode: approvalYolo, MaxToolIterations: 10, Temperature: 0.2, MaxTokens: 1024,
	}); err != nil {
		t.Fatal(err)
	}
	session, err := store.createSession("stale steering restart")
	if err != nil {
		t.Fatal(err)
	}
	app := &DesktopAgentApp{
		store: store, runs: map[string]context.CancelFunc{},
		restartRuns: map[string]bool{session.ID: true}, approvals: map[string]chan bool{},
	}
	if err := app.SendMessage(SendMessageRequest{SessionID: session.ID, Content: "直接开始新一轮"}); err != nil {
		t.Fatal(err)
	}
	app.mu.Lock()
	_, staleRestart := app.restartRuns[session.ID]
	app.mu.Unlock()
	if staleRestart {
		t.Fatal("fresh send retained a stale steering restart and could launch a duplicate run")
	}
	app.CancelRun(session.ID)
	deadline := time.Now().Add(time.Second)
	for time.Now().Before(deadline) {
		app.mu.Lock()
		_, running := app.runs[session.ID]
		app.mu.Unlock()
		if !running {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatal("fresh run did not stop during test cleanup")
}
