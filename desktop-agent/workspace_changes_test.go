package main

import (
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestWorkspaceChangeTrackerCoalescesConsumesRestoresAndIgnoresAgentWrites(t *testing.T) {
	root := t.TempDir()
	if err := os.WriteFile(filepath.Join(root, "main.go"), []byte("package main\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	tracker := newWorkspaceChangeTracker()
	defer tracker.Close()
	tracker.mu.Lock()
	tracker.projectPath = root
	tracker.generation = 1
	tracker.watching = true
	tracker.mu.Unlock()

	tracker.recordNativeChange(1, "main.go", workspaceChangeAdded)
	tracker.recordNativeChange(1, "main.go", workspaceChangeModified)
	state := tracker.State()
	if len(state.PendingChanges) != 1 || state.PendingChanges[0].Kind != "created" || state.PendingChanges[0].Path != "main.go" {
		t.Fatalf("unexpected coalesced state: %#v", state)
	}
	snapshot := tracker.Consume(root)
	if len(snapshot.Changes) != 1 || len(tracker.State().PendingChanges) != 0 {
		t.Fatalf("consume failed: %#v, %#v", snapshot, tracker.State())
	}
	tracker.Restore(snapshot)
	beforeAgentWrite := tracker.State()
	if len(beforeAgentWrite.PendingChanges) != 1 {
		t.Fatal("restore did not put consumed changes back")
	}

	tracker.IgnoreAgentPaths(root, "main.go")
	tracker.recordNativeChange(1, "main.go", workspaceChangeModified)
	afterAgentWrite := tracker.State()
	if len(afterAgentWrite.PendingChanges) != 1 || afterAgentWrite.PendingChanges[0] != beforeAgentWrite.PendingChanges[0] || len(afterAgentWrite.RecentChanges) != len(beforeAgentWrite.RecentChanges) {
		t.Fatalf("direct Agent event should be ignored without erasing prior external changes: before=%#v, after=%#v", beforeAgentWrite, afterAgentWrite)
	}
	tracker.Consume(root)
	tracker.recordNativeChange(1, "build/generated.txt", workspaceChangeAdded)
	if len(tracker.State().PendingChanges) != 0 {
		t.Fatal("generated directory change should be ignored")
	}
}

func TestNativeWorkspaceWatcherDetectsExternalChange(t *testing.T) {
	root := t.TempDir()
	tracker := newWorkspaceChangeTracker()
	tracker.SetProject(root)
	defer tracker.Close()
	if state := tracker.State(); !state.Watching || state.Error != "" {
		t.Fatalf("native watcher did not start: %#v", state)
	}
	if err := os.WriteFile(filepath.Join(root, "external.txt"), []byte("changed outside Murong"), 0o644); err != nil {
		t.Fatal(err)
	}
	waitForWorkspaceChange(t, tracker, 5*time.Second, func(state WorkspaceChangeState) bool {
		for _, change := range state.PendingChanges {
			if change.Path == "external.txt" && (change.Kind == "created" || change.Kind == "modified") {
				return true
			}
		}
		return false
	})
	tracker.Consume(root)
	tracker.IgnoreAgentPaths(root, "agent.txt")
	if err := os.WriteFile(filepath.Join(root, "agent.txt"), []byte("direct tool write"), 0o644); err != nil {
		t.Fatal(err)
	}
	time.Sleep(350 * time.Millisecond)
	for _, change := range tracker.State().PendingChanges {
		if change.Path == "agent.txt" {
			t.Fatalf("ignored Agent path leaked into pending changes: %#v", change)
		}
	}
}

func TestWorkspaceChangesEnterModelContextAndCloneIndependently(t *testing.T) {
	message := ChatMessage{
		ID: "message", Role: "user", Content: "继续处理", WorkspaceChangesOmitted: 2,
		WorkspaceChanges: []WorkspaceFileChange{{Path: "src/main.go", Kind: "modified", ChangedAt: 10}},
	}
	materialized := materializeUserMessage(defaultDesktopConfig(), message)
	for _, marker := range []string{"继续处理", "项目外部变化", "修改：src/main.go", "另有 2 个变化"} {
		if !strings.Contains(materialized, marker) {
			t.Fatalf("materialized message is missing %q: %s", marker, materialized)
		}
	}
	session := &ChatSession{ID: "session", Title: "task", Messages: []ChatMessage{message}}
	cloned := cloneSession(session)
	cloned.Messages[0].WorkspaceChanges[0].Path = "changed.go"
	if session.Messages[0].WorkspaceChanges[0].Path != "src/main.go" {
		t.Fatal("cloneSession shared workspace changes with the source")
	}
	summary := buildLocalSessionCompressionSummary(session, session.Messages)
	if !strings.Contains(summary, "src/main.go") {
		t.Fatalf("compression summary lost workspace changes: %s", summary)
	}
}

func TestWorkspaceDiffUsesGitAndFallsBackForPlainFolders(t *testing.T) {
	git, err := exec.LookPath("git.exe")
	if err != nil {
		git, err = exec.LookPath("git")
	}
	if err != nil {
		t.Skip("git is not installed")
	}
	root := t.TempDir()
	runGitForTest(t, git, root, "init", "--quiet")
	runGitForTest(t, git, root, "config", "user.email", "murong@example.invalid")
	runGitForTest(t, git, root, "config", "user.name", "Murong Test")
	path := filepath.Join(root, "sample.txt")
	if err := os.WriteFile(path, []byte("before\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	runGitForTest(t, git, root, "add", "sample.txt")
	runGitForTest(t, git, root, "commit", "--quiet", "-m", "initial")
	if err := os.WriteFile(path, []byte("after\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	diff, err := workspaceDiffForProject(root, "sample.txt")
	if err != nil || !diff.Git || !diff.Available || !strings.Contains(diff.Diff, "-before") || !strings.Contains(diff.Diff, "+after") {
		t.Fatalf("unexpected Git diff: %#v, %v", diff, err)
	}
	if err := os.WriteFile(filepath.Join(root, "new.txt"), []byte("new file\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	untracked, err := workspaceDiffForProject(root, "new.txt")
	if err != nil || !untracked.Available || !strings.Contains(untracked.Diff, "new file mode") {
		t.Fatalf("unexpected untracked diff: %#v, %v", untracked, err)
	}

	unborn := t.TempDir()
	runGitForTest(t, git, unborn, "init", "--quiet")
	if err := os.WriteFile(filepath.Join(unborn, "staged.txt"), []byte("staged before first commit\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	runGitForTest(t, git, unborn, "add", "staged.txt")
	staged, err := workspaceDiffForProject(unborn, "staged.txt")
	if err != nil || !staged.Git || !staged.Available || !strings.Contains(staged.Diff, "+staged before first commit") {
		t.Fatalf("unexpected unborn repository diff: %#v, %v", staged, err)
	}

	plain := t.TempDir()
	if err := os.WriteFile(filepath.Join(plain, "plain.txt"), []byte("plain content"), 0o644); err != nil {
		t.Fatal(err)
	}
	preview, err := workspaceDiffForProject(plain, "plain.txt")
	if err != nil || preview.Git || !preview.Available || !strings.Contains(preview.Diff, "plain content") {
		t.Fatalf("unexpected plain folder preview: %#v, %v", preview, err)
	}
}

func TestWorkspaceDiffTruncationPreservesUTF8(t *testing.T) {
	diff, truncated := truncateWorkspaceDiff(strings.Repeat("你", maxWorkspaceDiffBytes))
	if !truncated || !strings.Contains(diff, "Diff 已按 256 KiB 上限截断") || strings.ContainsRune(diff, '�') {
		t.Fatalf("UTF-8 diff truncation was invalid: truncated=%v, tail=%q", truncated, diff[len(diff)-64:])
	}
}

func TestWorkspaceChangeValidationRejectsUnsafeOrDuplicateEntries(t *testing.T) {
	valid := ChatMessage{WorkspaceChanges: []WorkspaceFileChange{{Path: "src/main.go", Kind: "modified", ChangedAt: 1}}}
	if err := validateMessageWorkspaceChanges(valid); err != nil {
		t.Fatal(err)
	}
	invalid := []ChatMessage{
		{WorkspaceChanges: []WorkspaceFileChange{{Path: "../outside", Kind: "modified", ChangedAt: 1}}},
		{WorkspaceChanges: []WorkspaceFileChange{{Path: "src/main.go", Kind: "unknown", ChangedAt: 1}}},
		{WorkspaceChanges: []WorkspaceFileChange{{Path: "src/main.go", Kind: "modified", ChangedAt: 1}, {Path: "SRC/MAIN.GO", Kind: "modified", ChangedAt: 2}}},
	}
	for index, message := range invalid {
		if err := validateMessageWorkspaceChanges(message); err == nil {
			t.Fatalf("invalid workspace changes %d unexpectedly passed", index)
		}
	}
}

func waitForWorkspaceChange(t *testing.T, tracker *workspaceChangeTracker, timeout time.Duration, predicate func(WorkspaceChangeState) bool) {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		if predicate(tracker.State()) {
			return
		}
		time.Sleep(40 * time.Millisecond)
	}
	t.Fatalf("timed out waiting for workspace change: %#v", tracker.State())
}

func runGitForTest(t *testing.T, executable, directory string, arguments ...string) {
	t.Helper()
	command := exec.Command(executable, arguments...)
	command.Dir = directory
	prepareHiddenCommand(command)
	if output, err := command.CombinedOutput(); err != nil {
		t.Fatalf("git %v failed: %v\n%s", arguments, err, output)
	}
}
