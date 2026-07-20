package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestNewAndForkedSessionsKeepTheirProjectBinding(t *testing.T) {
	t.Setenv("MURONG_DESKTOP_DATA_DIR", t.TempDir())
	project := t.TempDir()
	store, err := newDesktopStore()
	if err != nil {
		t.Fatal(err)
	}
	if _, err := store.activateProject(project); err != nil {
		t.Fatal(err)
	}
	session, err := store.createSession("绑定项目")
	if err != nil {
		t.Fatal(err)
	}
	if !sameWorkspacePath(session.ProjectPath, project) {
		t.Fatalf("new session did not inherit the current project: %#v", session)
	}
	if _, err := store.appendMessage(session.ID, ChatMessage{Role: "user", Content: "开始"}); err != nil {
		t.Fatal(err)
	}
	forked, err := store.forkSession(session.ID, "")
	if err != nil {
		t.Fatal(err)
	}
	if !sameWorkspacePath(forked.ProjectPath, project) || sessionStats(forked).ProjectPath != forked.ProjectPath {
		t.Fatalf("fork or stats lost the project binding: %#v", forked)
	}
}

func TestRebindingSessionProjectInvalidatesCodexThread(t *testing.T) {
	t.Setenv("MURONG_DESKTOP_DATA_DIR", t.TempDir())
	store, err := newDesktopStore()
	if err != nil {
		t.Fatal(err)
	}
	first, second := t.TempDir(), t.TempDir()
	if _, err := store.activateProject(first); err != nil {
		t.Fatal(err)
	}
	session, err := store.createSession("切换项目")
	if err != nil {
		t.Fatal(err)
	}
	if err := store.setCodexThreadState(session.ID, "thread-1", "message-1", codexDynamicToolsVersion); err != nil {
		t.Fatal(err)
	}
	updated, err := store.bindSessionProject(session.ID, second)
	if err != nil {
		t.Fatal(err)
	}
	if !sameWorkspacePath(updated.ProjectPath, second) || updated.CodexThreadID != "" || updated.CodexSyncedID != "" || updated.CodexToolsVersion != 0 {
		t.Fatalf("project rebind kept stale Codex state: %#v", updated)
	}
}

func TestSessionExecutionUsesBindingInsteadOfGlobalProject(t *testing.T) {
	t.Setenv("MURONG_DESKTOP_DATA_DIR", t.TempDir())
	first, second := t.TempDir(), t.TempDir()
	store, err := newDesktopStore()
	if err != nil {
		t.Fatal(err)
	}
	if _, err := store.activateProject(first); err != nil {
		t.Fatal(err)
	}
	session, err := store.createSession("第一个项目")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := store.activateProject(second); err != nil {
		t.Fatal(err)
	}
	app := &DesktopAgentApp{store: store}
	config, resolvedSession, err := app.sessionExecutionConfig(session.ID, true)
	if err != nil {
		t.Fatal(err)
	}
	if !sameWorkspacePath(config.ProjectPath, first) || !sameWorkspacePath(resolvedSession.ProjectPath, first) {
		t.Fatalf("session followed the mutable global project: config=%q session=%q", config.ProjectPath, resolvedSession.ProjectPath)
	}
}

func TestLegacySessionBindsOnFirstExecutionAndRejectsMissingBinding(t *testing.T) {
	t.Setenv("MURONG_DESKTOP_DATA_DIR", t.TempDir())
	store, err := newDesktopStore()
	if err != nil {
		t.Fatal(err)
	}
	legacy, err := store.createSession("旧任务")
	if err != nil {
		t.Fatal(err)
	}
	projectParent := t.TempDir()
	project := filepath.Join(projectParent, "project")
	if err := os.MkdirAll(project, 0o755); err != nil {
		t.Fatal(err)
	}
	if _, err := store.activateProject(project); err != nil {
		t.Fatal(err)
	}
	app := &DesktopAgentApp{store: store}
	if _, _, err := app.sessionExecutionConfig(legacy.ID, true); err != nil {
		t.Fatal(err)
	}
	if persisted := store.getSession(legacy.ID); !sameWorkspacePath(persisted.ProjectPath, project) {
		t.Fatalf("legacy session binding was not persisted: %#v", persisted)
	}
	if err := os.RemoveAll(project); err != nil {
		t.Fatal(err)
	}
	if _, _, err := app.sessionExecutionConfig(legacy.ID, true); err == nil || !strings.Contains(err.Error(), "绑定的项目不可用") {
		t.Fatalf("missing bound project was not rejected clearly: %v", err)
	}
}

func TestSelectSessionRestoresItsProjectConfiguration(t *testing.T) {
	t.Setenv("MURONG_DESKTOP_DATA_DIR", t.TempDir())
	first, second := t.TempDir(), t.TempDir()
	store, err := newDesktopStore()
	if err != nil {
		t.Fatal(err)
	}
	if _, err := store.activateProject(first); err != nil {
		t.Fatal(err)
	}
	session, err := store.createSession("恢复项目")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := store.activateProject(second); err != nil {
		t.Fatal(err)
	}
	app := &DesktopAgentApp{store: store}
	selection, err := app.SelectSession(session.ID)
	if err != nil {
		t.Fatal(err)
	}
	if !selection.ProjectAvailable || !sameWorkspacePath(selection.Config.ProjectPath, first) || !sameWorkspacePath(store.rawConfig().ProjectPath, first) {
		t.Fatalf("selection did not restore the bound project: %#v", selection)
	}
}
