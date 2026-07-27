package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestWorkspaceReviewCapturesExactRunDiffAndUndoPreservesEarlierDirtyState(t *testing.T) {
	git, err := workspaceGitExecutable()
	if err != nil {
		t.Skip("git is not installed")
	}
	root := t.TempDir()
	runGitForTest(t, git, root, "init", "--quiet")
	runGitForTest(t, git, root, "config", "user.email", "murong@example.invalid")
	runGitForTest(t, git, root, "config", "user.name", "Murong Test")
	trackedPath := filepath.Join(root, "tracked.txt")
	if err := os.WriteFile(trackedPath, []byte("committed\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	runGitForTest(t, git, root, "add", "tracked.txt")
	runGitForTest(t, git, root, "commit", "--quiet", "-m", "initial")

	if err := os.WriteFile(trackedPath, []byte("user dirty state\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	checkpoint, err := captureRunWorkspaceReviewCheckpoint(root)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(trackedPath, []byte("agent result\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(root, "新建.txt"), []byte("created by agent\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	afterTree, err := captureGitWorktreeTree(checkpoint)
	if err != nil {
		t.Fatal(err)
	}
	review, err := buildWorkspaceReview(checkpoint, afterTree)
	if err != nil {
		t.Fatal(err)
	}
	if len(review.Files) != 2 || review.Additions != 2 || review.Deletions != 1 {
		t.Fatalf("unexpected review stats: %#v", review)
	}

	t.Setenv("MURONG_DESKTOP_DATA_DIR", t.TempDir())
	store, err := newDesktopStore()
	if err != nil {
		t.Fatal(err)
	}
	store.mu.Lock()
	store.config.ProjectPath = root
	store.mu.Unlock()
	session, err := store.createSession("review")
	if err != nil {
		t.Fatal(err)
	}
	app := &DesktopAgentApp{store: store, workspace: newWorkspaceChangeTracker()}
	if err := app.writeWorkspaceReviewPatch(review, checkpoint.GitRoot, checkpoint.GitExecutable); err != nil {
		t.Fatal(err)
	}
	review.UndoAvailable = true
	if _, err := store.appendMessage(session.ID, ChatMessage{
		Role: "assistant", Kind: workspaceReviewKind, Content: "本轮文件变更", WorkspaceReview: review,
	}); err != nil {
		t.Fatal(err)
	}
	diff, err := app.GetWorkspaceReviewDiff(review.ID, "tracked.txt")
	if err != nil || !diff.Available || !strings.Contains(diff.Diff, "-user dirty state") || !strings.Contains(diff.Diff, "+agent result") {
		t.Fatalf("unexpected run diff: %#v, %v", diff, err)
	}
	updated, err := app.UndoWorkspaceReview(review.ID)
	if err != nil {
		t.Fatal(err)
	}
	content, err := os.ReadFile(trackedPath)
	if err != nil || strings.ReplaceAll(string(content), "\r\n", "\n") != "user dirty state\n" {
		t.Fatalf("undo did not preserve pre-run dirty state: %q, %v", content, err)
	}
	if _, err := os.Stat(filepath.Join(root, "新建.txt")); !os.IsNotExist(err) {
		t.Fatalf("undo did not remove the file created by the run: %v", err)
	}
	storedReview := updated.Messages[len(updated.Messages)-1].WorkspaceReview
	if storedReview == nil || !storedReview.Undone || storedReview.UndoAvailable || storedReview.StatusMessage == "" {
		t.Fatalf("undo state was not persisted: %#v", storedReview)
	}
}

func TestWorkspaceReviewRejectsUnsafeUndoAfterLaterEdit(t *testing.T) {
	git, err := workspaceGitExecutable()
	if err != nil {
		t.Skip("git is not installed")
	}
	root := t.TempDir()
	runGitForTest(t, git, root, "init", "--quiet")
	runGitForTest(t, git, root, "config", "user.email", "murong@example.invalid")
	runGitForTest(t, git, root, "config", "user.name", "Murong Test")
	path := filepath.Join(root, "main.txt")
	if err := os.WriteFile(path, []byte("before\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	runGitForTest(t, git, root, "add", "main.txt")
	runGitForTest(t, git, root, "commit", "--quiet", "-m", "initial")
	checkpoint, err := captureRunWorkspaceReviewCheckpoint(root)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte("agent\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	afterTree, err := captureGitWorktreeTree(checkpoint)
	if err != nil {
		t.Fatal(err)
	}
	review, err := buildWorkspaceReview(checkpoint, afterTree)
	if err != nil {
		t.Fatal(err)
	}
	t.Setenv("MURONG_DESKTOP_DATA_DIR", t.TempDir())
	store, err := newDesktopStore()
	if err != nil {
		t.Fatal(err)
	}
	store.mu.Lock()
	store.config.ProjectPath = root
	store.mu.Unlock()
	session, err := store.createSession("review")
	if err != nil {
		t.Fatal(err)
	}
	app := &DesktopAgentApp{store: store}
	if err := app.writeWorkspaceReviewPatch(review, checkpoint.GitRoot, checkpoint.GitExecutable); err != nil {
		t.Fatal(err)
	}
	review.UndoAvailable = true
	if _, err := store.appendMessage(session.ID, ChatMessage{
		Role: "assistant", Kind: workspaceReviewKind, Content: "本轮文件变更", WorkspaceReview: review,
	}); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte("later user edit\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	if _, err := app.UndoWorkspaceReview(review.ID); err == nil || !strings.Contains(err.Error(), "无法安全撤销") {
		t.Fatalf("later edit should block undo safely: %v", err)
	}
	content, err := os.ReadFile(path)
	if err != nil || string(content) != "later user edit\n" {
		t.Fatalf("failed undo changed the user's later edit: %q, %v", content, err)
	}
}

func TestWorkspaceReviewValidationAndCloneAreIndependent(t *testing.T) {
	review := &WorkspaceReview{
		ID: "review-0123456789abcdef01234567", ProjectPath: filepath.Clean(t.TempDir()),
		BeforeTree: strings.Repeat("a", 40), AfterTree: strings.Repeat("b", 40),
		Files:     []WorkspaceReviewFile{{Path: "src/main.go", Kind: "modified", Additions: 3, Deletions: 2}},
		Additions: 3, Deletions: 2, CreatedAt: time.Now().UnixMilli(), UndoAvailable: true,
	}
	message := ChatMessage{Kind: workspaceReviewKind, WorkspaceReview: review}
	if err := validateMessageWorkspaceChanges(message); err != nil {
		t.Fatal(err)
	}
	session := &ChatSession{Messages: []ChatMessage{message}}
	cloned := cloneSession(session)
	cloned.Messages[0].WorkspaceReview.Files[0].Path = "changed.go"
	if session.Messages[0].WorkspaceReview.Files[0].Path != "src/main.go" {
		t.Fatal("cloneSession shared workspace review files with the source")
	}
	review.Files[0].Path = "../unsafe"
	if err := validateMessageWorkspaceChanges(message); err == nil {
		t.Fatal("unsafe workspace review path unexpectedly passed validation")
	}
}
