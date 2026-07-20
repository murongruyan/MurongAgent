package main

import (
	"context"
	"os"
	"path/filepath"
	"testing"
)

func TestProjectActivationPersistsRecentProjectsAndCloseKeepsHistory(t *testing.T) {
	t.Setenv("MURONG_DESKTOP_DATA_DIR", t.TempDir())
	first := t.TempDir()
	second := t.TempDir()
	store, err := newDesktopStore()
	if err != nil {
		t.Fatal(err)
	}
	if _, err := store.activateProject(first); err != nil {
		t.Fatal(err)
	}
	config, err := store.activateProject(second)
	if err != nil {
		t.Fatal(err)
	}
	if !sameWorkspacePath(config.ProjectPath, second) || len(config.RecentProjects) != 2 || !sameWorkspacePath(config.RecentProjects[0].Path, second) || !config.RecentProjects[0].Exists {
		t.Fatalf("unexpected activated project config: %#v", config)
	}
	closed, err := store.closeProject()
	if err != nil {
		t.Fatal(err)
	}
	if closed.ProjectPath != "" || len(closed.RecentProjects) != 2 {
		t.Fatalf("closing a project should retain history: %#v", closed)
	}

	reloaded, err := newDesktopStore()
	if err != nil {
		t.Fatal(err)
	}
	if got := reloaded.publicConfig(); got.ProjectPath != "" || len(got.RecentProjects) != 2 || !sameWorkspacePath(got.RecentProjects[0].Path, second) {
		t.Fatalf("recent projects were not persisted: %#v", got)
	}
	forgotten, err := reloaded.forgetRecentProject(second)
	if err != nil {
		t.Fatal(err)
	}
	if len(forgotten.RecentProjects) != 1 || !sameWorkspacePath(forgotten.RecentProjects[0].Path, first) {
		t.Fatalf("recent project was not removed: %#v", forgotten)
	}
}

func TestWorkspaceIdentityResolvesDirectorySymlinkAliases(t *testing.T) {
	parent := t.TempDir()
	realProject := filepath.Join(parent, "real-project")
	aliasProject := filepath.Join(parent, "alias-project")
	if err := os.MkdirAll(realProject, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(realProject, aliasProject); err != nil {
		t.Skipf("directory symlinks are unavailable: %v", err)
	}
	if !sameWorkspacePath(realProject, aliasProject) {
		t.Fatalf("workspace aliases were treated as different: real=%q alias=%q", realProject, aliasProject)
	}
	if projectKnowledgeKey(realProject) != projectKnowledgeKey(aliasProject) {
		t.Fatalf("workspace aliases produced different project keys: real=%q alias=%q", projectKnowledgeKey(realProject), projectKnowledgeKey(aliasProject))
	}
}

func TestValidateProjectNameRejectsWindowsUnsafeNames(t *testing.T) {
	invalid := []string{"", ".", "..", "CON", "con.txt", "bad/name", "bad*name", "trailing."}
	for _, value := range invalid {
		if _, err := validateProjectName(value); err == nil {
			t.Fatalf("expected %q to be rejected", value)
		}
	}
	if got, err := validateProjectName("murong-desktop"); err != nil || got != "murong-desktop" {
		t.Fatalf("valid project name rejected: %q, %v", got, err)
	}
}

func TestSearchProjectEntriesUsesCurrentProjectAndSkipsGeneratedDirectories(t *testing.T) {
	root := t.TempDir()
	if err := os.MkdirAll(filepath.Join(root, "src", "feature"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(root, "README.md"), []byte("readme"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(root, "src", "feature", "Target.kt"), []byte("target"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(filepath.Join(root, "build", "generated"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(root, "build", "generated", "Target.kt"), []byte("generated"), 0o644); err != nil {
		t.Fatal(err)
	}
	store := &desktopStore{
		configPath: filepath.Join(t.TempDir(), "config.json"), sessionsPath: filepath.Join(t.TempDir(), "sessions.json"),
		config: defaultDesktopConfig(), sessions: map[string]*ChatSession{},
	}
	store.config.ProjectPath = root
	app := &DesktopAgentApp{store: store, runs: map[string]context.CancelFunc{}, approvals: map[string]chan bool{}}

	rootEntries, err := app.SearchProjectEntries("")
	if err != nil {
		t.Fatal(err)
	}
	if len(rootEntries) != 2 || rootEntries[0].Path != "src" || rootEntries[1].Path != "README.md" {
		t.Fatalf("unexpected top-level entries: %#v", rootEntries)
	}
	matching, err := app.SearchProjectEntries("target")
	if err != nil {
		t.Fatal(err)
	}
	if len(matching) != 1 || matching[0].Path != "src/feature/Target.kt" {
		t.Fatalf("generated entries were not excluded: %#v", matching)
	}
}
