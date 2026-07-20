package main

import (
	"context"
	"path/filepath"
	"strings"
	"testing"
)

func TestParseExternalWorkflowLaunchAcceptsOneExplicitWorkflow(t *testing.T) {
	for _, args := range [][]string{
		{`C:\Program Files\Murong\murong-desktop-agent-amd64.exe`, "--run-workflow", "workflow-12345678"},
		{"--run-workflow=workflow-12345678"},
	} {
		launch, err := parseExternalWorkflowLaunch(args)
		if err != nil || launch == nil || launch.WorkflowID != "workflow-12345678" {
			t.Fatalf("unexpected parsed launch %#v, %v", launch, err)
		}
	}
	if launch, err := parseExternalWorkflowLaunch([]string{"--ordinary-launch"}); err != nil || launch != nil {
		t.Fatalf("ordinary launch was treated as a workflow: %#v, %v", launch, err)
	}
	for _, args := range [][]string{
		{"--run-workflow"},
		{"--run-workflow", ""},
		{"--run-workflow", "workflow;remove-everything"},
		{"--run-workflow=a", "--run-workflow=b"},
	} {
		if _, err := parseExternalWorkflowLaunch(args); err == nil {
			t.Fatalf("invalid launch passed: %#v", args)
		}
	}
}

func TestExternalWorkflowLaunchRunsOnlyBackgroundSafeTemplates(t *testing.T) {
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
	app := &DesktopAgentApp{workflows: manager, runs: map[string]context.CancelFunc{}, approvals: map[string]chan bool{}}

	state, err := manager.SaveWorkflow(SaveSavedWorkflowRequest{
		Name: "external read", Template: workflowProjectReadDiagnostic, ProjectPath: t.TempDir(), IntervalMinutes: 60,
	})
	if err != nil {
		t.Fatal(err)
	}
	readID := state.Workflows[0].ID
	if err := app.runExternalWorkflow(readID); err != nil {
		t.Fatal(err)
	}
	waitForWorkflowStatus(t, manager, readID, workflowRunSucceeded)
	command, err := app.GetSavedWorkflowExternalCommand(readID)
	if err != nil || !strings.Contains(command, "--run-workflow "+readID) || (!strings.HasPrefix(command, "\"") && !strings.HasPrefix(command, "'")) {
		t.Fatalf("unexpected external command %q, %v", command, err)
	}

	state, err = manager.SaveWorkflow(SaveSavedWorkflowRequest{
		Name: "foreground export", Template: workflowSessionSummaryExport, IntervalMinutes: 60,
	})
	if err != nil {
		t.Fatal(err)
	}
	var exportID string
	for _, workflow := range state.Workflows {
		if workflow.Template == workflowSessionSummaryExport {
			exportID = workflow.ID
		}
	}
	if err := app.runExternalWorkflow(exportID); err == nil || !strings.Contains(err.Error(), "前台逐次确认") {
		t.Fatalf("foreground workflow was callable externally: %v", err)
	}
	if _, err := app.GetSavedWorkflowExternalCommand(exportID); err == nil {
		t.Fatal("foreground workflow received an external command")
	}
}

func TestExternalWorkflowUIExposesCopyCommandOnlyForSafeCards(t *testing.T) {
	index, err := frontendAssets.ReadFile("frontend/dist/index.html")
	if err != nil {
		t.Fatal(err)
	}
	script, err := frontendAssets.ReadFile("frontend/dist/app.js")
	if err != nil {
		t.Fatal(err)
	}
	for _, marker := range []string{"系统调度器", "写入型工作流不会生成命令"} {
		if !strings.Contains(string(index), marker) {
			t.Fatalf("external workflow guidance is missing %q", marker)
		}
	}
	for _, marker := range []string{"workflow-copy-command", "CopySavedWorkflowExternalCommand", "external-workflow:status"} {
		if !strings.Contains(string(script), marker) {
			t.Fatalf("external workflow UI is missing %q", marker)
		}
	}
}
