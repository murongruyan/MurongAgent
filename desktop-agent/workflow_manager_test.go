package main

import (
	"context"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestSavedWorkflowManagerSchedulesOnlyFixedReadOnlyTemplates(t *testing.T) {
	dataDirectory := t.TempDir()
	t.Setenv("MURONG_DESKTOP_DATA_DIR", dataDirectory)
	desktopStore, err := newDesktopStore()
	if err != nil {
		t.Fatal(err)
	}
	workflowStore, err := newSavedWorkflowStore(filepath.Join(dataDirectory, "workflows.json"))
	if err != nil {
		t.Fatal(err)
	}
	manager := newSavedWorkflowManager(workflowStore, desktopStore)
	manager.Start(context.Background())
	defer manager.Close()

	project := t.TempDir()
	state, err := manager.SaveWorkflow(SaveSavedWorkflowRequest{
		Name: "scheduled read", Template: workflowProjectReadDiagnostic, ProjectPath: project,
		IntervalMinutes: 15, Enabled: true,
	})
	if err != nil || len(state.Workflows) != 1 {
		t.Fatalf("failed to save scheduled read workflow: %#v, %v", state, err)
	}
	readID := state.Workflows[0].ID
	manager.mu.Lock()
	readScheduled := manager.schedules[readID] != nil
	manager.mu.Unlock()
	if !readScheduled {
		t.Fatal("fixed read-only workflow was not scheduled")
	}

	state, err = manager.SaveWorkflow(SaveSavedWorkflowRequest{
		Name: "foreground export", Template: workflowSessionSummaryExport,
		IntervalMinutes: 15, Enabled: true,
	})
	if err != nil {
		t.Fatal(err)
	}
	var export SavedWorkflowDefinition
	for _, workflow := range state.Workflows {
		if workflow.Template == workflowSessionSummaryExport {
			export = workflow
		}
	}
	if export.LastRun == nil || export.LastRun.Status != workflowRunBlocked {
		t.Fatalf("foreground workflow was not marked blocked for background execution: %#v", export)
	}
	manager.mu.Lock()
	exportScheduled := manager.schedules[export.ID] != nil
	manager.mu.Unlock()
	if exportScheduled {
		t.Fatal("file-writing workflow received a background schedule")
	}
	if _, err := manager.RunNow(RunSavedWorkflowRequest{ID: export.ID, Confirmed: false}); err == nil {
		t.Fatal("foreground workflow ran without per-run confirmation")
	}
}

func TestSavedWorkflowManagerRunsGitHubQueryAndEmitsCompletion(t *testing.T) {
	server := httptest.NewTLSServer(http.HandlerFunc(func(response http.ResponseWriter, request *http.Request) {
		fmt.Fprint(response, `{"workflow_runs":[{"name":"CI","status":"completed","conclusion":"success","head_branch":"main"}]}`)
	}))
	defer server.Close()
	dataDirectory := t.TempDir()
	t.Setenv("MURONG_DESKTOP_DATA_DIR", dataDirectory)
	desktopStore, err := newDesktopStore()
	if err != nil {
		t.Fatal(err)
	}
	workflowStore, err := newSavedWorkflowStore(filepath.Join(dataDirectory, "workflows.json"))
	if err != nil {
		t.Fatal(err)
	}
	if err := workflowStore.saveGitHub(SaveGitHubConfigRequest{APIBaseURL: server.URL, Token: "token"}); err != nil {
		t.Fatal(err)
	}
	manager := newSavedWorkflowManager(workflowStore, desktopStore)
	manager.githubHTTP = server.Client()
	changes := make(chan struct{}, 16)
	manager.SetListener(func() { changes <- struct{}{} })
	manager.Start(context.Background())
	defer manager.Close()
	state, err := manager.SaveWorkflow(SaveSavedWorkflowRequest{
		Name: "Actions", Template: workflowGitHubActionsStatus, GitHubRepository: "murong/example",
		IntervalMinutes: 60, Enabled: false,
	})
	if err != nil {
		t.Fatal(err)
	}
	id := state.Workflows[0].ID
	if _, err := manager.RunNow(RunSavedWorkflowRequest{ID: id}); err != nil {
		t.Fatal(err)
	}
	completed := waitForWorkflowStatus(t, manager, id, workflowRunSucceeded)
	if !strings.Contains(completed.LastRun.Summary, "CI（main）：success") {
		t.Fatalf("unexpected GitHub workflow summary: %#v", completed.LastRun)
	}
	if len(changes) == 0 {
		t.Fatal("workflow manager did not emit state changes")
	}
}

func TestSavedWorkflowManagerExportsSelectedSessionAfterConfirmation(t *testing.T) {
	dataDirectory := t.TempDir()
	home := t.TempDir()
	t.Setenv("MURONG_DESKTOP_DATA_DIR", dataDirectory)
	t.Setenv("USERPROFILE", home)
	desktopStore, err := newDesktopStore()
	if err != nil {
		t.Fatal(err)
	}
	session, err := desktopStore.createSession("Export test")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := desktopStore.appendMessage(session.ID, ChatMessage{Role: "user", Content: "hello workflow"}); err != nil {
		t.Fatal(err)
	}
	workflowStore, err := newSavedWorkflowStore(filepath.Join(dataDirectory, "workflows.json"))
	if err != nil {
		t.Fatal(err)
	}
	manager := newSavedWorkflowManager(workflowStore, desktopStore)
	manager.Start(context.Background())
	defer manager.Close()
	state, err := manager.SaveWorkflow(SaveSavedWorkflowRequest{
		Name: "Export", Template: workflowSessionSummaryExport, IntervalMinutes: 60,
	})
	if err != nil {
		t.Fatal(err)
	}
	id := state.Workflows[0].ID
	if _, err := manager.RunNow(RunSavedWorkflowRequest{ID: id, SessionID: session.ID, Confirmed: true}); err != nil {
		t.Fatal(err)
	}
	completed := waitForWorkflowStatus(t, manager, id, workflowRunSucceeded)
	if !strings.Contains(completed.LastRun.Summary, "Murong Exports") {
		t.Fatalf("unexpected export summary: %#v", completed.LastRun)
	}
	files, err := filepath.Glob(filepath.Join(home, "Documents", "Murong Exports", "*.md"))
	if err != nil || len(files) != 1 {
		t.Fatalf("expected one exported markdown file, got %v: %v", files, err)
	}
	data, err := os.ReadFile(files[0])
	if err != nil || !strings.Contains(string(data), "hello workflow") {
		t.Fatalf("unexpected exported markdown: %q, %v", data, err)
	}
}

func TestDeletingRunningWorkflowCancelsItsExecution(t *testing.T) {
	requestStarted := make(chan struct{})
	requestCancelled := make(chan struct{})
	server := httptest.NewTLSServer(http.HandlerFunc(func(response http.ResponseWriter, request *http.Request) {
		close(requestStarted)
		<-request.Context().Done()
		close(requestCancelled)
	}))
	defer server.Close()
	dataDirectory := t.TempDir()
	t.Setenv("MURONG_DESKTOP_DATA_DIR", dataDirectory)
	desktopStore, err := newDesktopStore()
	if err != nil {
		t.Fatal(err)
	}
	workflowStore, err := newSavedWorkflowStore(filepath.Join(dataDirectory, "workflows.json"))
	if err != nil {
		t.Fatal(err)
	}
	if err := workflowStore.saveGitHub(SaveGitHubConfigRequest{APIBaseURL: server.URL}); err != nil {
		t.Fatal(err)
	}
	manager := newSavedWorkflowManager(workflowStore, desktopStore)
	manager.githubHTTP = server.Client()
	manager.Start(context.Background())
	defer manager.Close()
	state, err := manager.SaveWorkflow(SaveSavedWorkflowRequest{
		Name: "slow Actions", Template: workflowGitHubActionsStatus, GitHubRepository: "murong/example",
		IntervalMinutes: 60,
	})
	if err != nil {
		t.Fatal(err)
	}
	id := state.Workflows[0].ID
	if _, err := manager.RunNow(RunSavedWorkflowRequest{ID: id}); err != nil {
		t.Fatal(err)
	}
	select {
	case <-requestStarted:
	case <-time.After(3 * time.Second):
		t.Fatal("GitHub workflow request did not start")
	}
	state, err = manager.DeleteWorkflow(id)
	if err != nil || len(state.Workflows) != 0 {
		t.Fatalf("workflow was not deleted: %#v, %v", state, err)
	}
	select {
	case <-requestCancelled:
	case <-time.After(3 * time.Second):
		t.Fatal("deleting workflow did not cancel its active request")
	}
}

func waitForWorkflowStatus(t *testing.T, manager *savedWorkflowManager, id, status string) SavedWorkflowDefinition {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		workflow, ok := manager.store.get(id)
		if ok && workflow.LastRun != nil && workflow.LastRun.Status == status {
			return workflow
		}
		time.Sleep(10 * time.Millisecond)
	}
	workflow, _ := manager.store.get(id)
	t.Fatalf("workflow %s did not reach %s: %#v", id, status, workflow.LastRun)
	return SavedWorkflowDefinition{}
}
