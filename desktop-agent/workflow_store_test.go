package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestSavedWorkflowStoreProtectsGitHubTokenAndPreservesBlankUpdate(t *testing.T) {
	path := filepath.Join(t.TempDir(), "workflows.json")
	store, err := newSavedWorkflowStore(path)
	if err != nil {
		t.Fatal(err)
	}
	if err := store.saveGitHub(SaveGitHubConfigRequest{APIBaseURL: "https://github.example/api/v3/", Token: "github-secret-token"}); err != nil {
		t.Fatal(err)
	}
	project := t.TempDir()
	workflow, err := store.saveWorkflow(SaveSavedWorkflowRequest{
		Name: "项目检查", Template: workflowProjectReadDiagnostic, ProjectPath: project,
		IntervalMinutes: 30, Enabled: true,
	})
	if err != nil {
		t.Fatal(err)
	}
	if workflow.ID == "" || !savedWorkflowBackgroundSafe(workflow) {
		t.Fatalf("unexpected workflow: %#v", workflow)
	}
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(data), "github-secret-token") {
		t.Fatal("GitHub token was persisted as plaintext")
	}
	if err := store.saveGitHub(SaveGitHubConfigRequest{APIBaseURL: "https://github.example/api/v3", Token: ""}); err != nil {
		t.Fatal(err)
	}
	runtimeConfig, err := store.runtimeGitHub()
	if err != nil || runtimeConfig.Token != "github-secret-token" || runtimeConfig.APIBaseURL != "https://github.example/api/v3" {
		t.Fatalf("blank update did not preserve protected token: %#v, %v", runtimeConfig, err)
	}
	reloaded, err := newSavedWorkflowStore(path)
	if err != nil {
		t.Fatal(err)
	}
	if state := reloaded.state(""); len(state.Workflows) != 1 || !state.GitHub.HasToken {
		t.Fatalf("unexpected reloaded state: %#v", state)
	}
	if err := reloaded.saveGitHub(SaveGitHubConfigRequest{APIBaseURL: "https://api.github.com", ClearToken: true}); err != nil {
		t.Fatal(err)
	}
	if config, err := reloaded.runtimeGitHub(); err != nil || config.Token != "" {
		t.Fatalf("token was not cleared: %#v, %v", config, err)
	}
}

func TestSavedWorkflowStoreReconcilesInterruptedRunAndRejectsUnsafeDefinitions(t *testing.T) {
	path := filepath.Join(t.TempDir(), "workflows.json")
	project := t.TempDir()
	workflow := SavedWorkflowDefinition{
		ID: "workflow-interrupted", Name: "中断检查", Template: workflowProjectReadDiagnostic,
		ProjectPath: project, Nodes: defaultSavedWorkflowNodes(workflowProjectReadDiagnostic),
		IntervalMinutes: 60, Enabled: true, CreatedAt: 1, UpdatedAt: 1,
		LastRun: &SavedWorkflowRunRecord{Status: workflowRunRunning, StartedAt: 1, Summary: "running"},
	}
	document := savedWorkflowDocument{
		SchemaVersion: savedWorkflowSchemaVersion,
		GitHub:        savedGitHubConfig{APIBaseURL: "https://api.github.com"},
		Workflows:     []SavedWorkflowDefinition{workflow},
	}
	if err := writeJSONAtomic(path, document); err != nil {
		t.Fatal(err)
	}
	store, err := newSavedWorkflowStore(path)
	if err != nil {
		t.Fatal(err)
	}
	loaded, ok := store.get(workflow.ID)
	if !ok || loaded.LastRun == nil || loaded.LastRun.Status != workflowRunCancelled || loaded.LastRun.FinishedAt == 0 {
		t.Fatalf("interrupted run was not reconciled: %#v", loaded)
	}
	tampered := loaded
	tampered.Nodes[0].RequiredPermission = workflowPermissionFileWrite
	if savedWorkflowBackgroundSafe(tampered) {
		t.Fatal("tampered project workflow became background safe")
	}
	cycle := loaded
	cycle.Nodes = []SavedWorkflowNode{
		{ID: "a", Label: "A", DependsOn: []string{"b"}, RequiredPermission: workflowPermissionProjectRead, TimeoutSeconds: 60},
		{ID: "b", Label: "B", DependsOn: []string{"a"}, RequiredPermission: workflowPermissionProjectRead, TimeoutSeconds: 60},
	}
	if errors := validateSavedWorkflow(cycle); len(errors) == 0 {
		t.Fatal("cyclic workflow passed validation")
	}
	if time.Since(time.UnixMilli(loaded.UpdatedAt)) > time.Minute {
		t.Fatal("reconciled timestamp was not refreshed")
	}
}

func TestSavedWorkflowStoreValidatesTargetsAndIntervals(t *testing.T) {
	store, err := newSavedWorkflowStore(filepath.Join(t.TempDir(), "workflows.json"))
	if err != nil {
		t.Fatal(err)
	}
	if _, err := store.saveWorkflow(SaveSavedWorkflowRequest{
		Name: "bad", Template: workflowGitHubActionsStatus, GitHubRepository: "not-a-repository", IntervalMinutes: 60,
	}); err == nil {
		t.Fatal("invalid GitHub repository was accepted")
	}
	if _, err := store.saveWorkflow(SaveSavedWorkflowRequest{
		Name: "bad", Template: workflowProjectReadDiagnostic, ProjectPath: t.TempDir(), IntervalMinutes: 14,
	}); err == nil {
		t.Fatal("too-small interval was accepted")
	}
	if err := store.saveGitHub(SaveGitHubConfigRequest{APIBaseURL: "http://api.github.com"}); err == nil {
		t.Fatal("non-HTTPS GitHub API URL was accepted")
	}
}
