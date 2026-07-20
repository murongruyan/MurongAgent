package main

import (
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestLocalWorkspaceReadWriteAndBoundaries(t *testing.T) {
	root := t.TempDir()
	if err := os.Mkdir(filepath.Join(root, "src"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(root, "src", "hello.txt"), []byte("before"), 0o644); err != nil {
		t.Fatal(err)
	}
	workspace, err := newLocalWorkspace(root)
	if err != nil {
		t.Fatal(err)
	}

	before, err := workspace.read("src/hello.txt")
	if err != nil || before.Content != "before" || before.SHA256 == "" {
		t.Fatalf("unexpected read: %#v, %v", before, err)
	}
	if _, _, err := workspace.write("src/hello.txt", "after", "wrong-sha"); err == nil {
		t.Fatal("expected stale SHA to reject overwrite")
	}
	after, created, err := workspace.write("src/hello.txt", "after", before.SHA256)
	if err != nil || created || after.Content != "after" {
		t.Fatalf("unexpected overwrite: %#v created=%v err=%v", after, created, err)
	}
	createdFile, created, err := workspace.write("src/new.txt", "new", "")
	if err != nil || !created || createdFile.Content != "new" {
		t.Fatalf("unexpected create: %#v created=%v err=%v", createdFile, created, err)
	}
	if _, err := workspace.read("../outside.txt"); err == nil {
		t.Fatal("expected parent traversal to be rejected")
	}
	if _, _, err := workspace.write("C:/outside.txt", "bad", ""); err == nil {
		t.Fatal("expected absolute path to be rejected")
	}
}

func TestLocalWorkspaceExistsDeleteAndChmod(t *testing.T) {
	root := t.TempDir()
	if err := os.Mkdir(filepath.Join(root, "nested"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(root, "nested", "file.txt"), []byte("content"), 0o644); err != nil {
		t.Fatal(err)
	}
	workspace, err := newLocalWorkspace(root)
	if err != nil {
		t.Fatal(err)
	}
	if exists, err := workspace.exists("nested/file.txt"); err != nil || !exists {
		t.Fatalf("expected file to exist: %v, %v", exists, err)
	}
	if err := workspace.chmod("nested/file.txt", "644"); err != nil {
		t.Fatal(err)
	}
	if err := workspace.delete("nested", false); err == nil {
		t.Fatal("expected directory deletion to require recursive=true")
	}
	if err := workspace.delete("nested", true); err != nil {
		t.Fatal(err)
	}
	if exists, err := workspace.exists("nested"); err != nil || exists {
		t.Fatalf("expected directory to be deleted: %v, %v", exists, err)
	}
	if err := workspace.delete("../outside", true); err == nil {
		t.Fatal("expected traversal deletion to be rejected")
	}
}

func TestLocalWorkspaceListAndTerminal(t *testing.T) {
	root := t.TempDir()
	if err := os.WriteFile(filepath.Join(root, "one.txt"), []byte("1"), 0o644); err != nil {
		t.Fatal(err)
	}
	workspace, err := newLocalWorkspace(root)
	if err != nil {
		t.Fatal(err)
	}
	entries, err := workspace.list(".", 2)
	if err != nil || len(entries) != 1 || entries[0].Path != "one.txt" {
		t.Fatalf("unexpected list: %#v, %v", entries, err)
	}

	ctx, cancel := context.WithTimeout(context.Background(), 90*time.Second)
	defer cancel()
	backends := discoverTerminalBackends(ctx)
	if len(backends) == 0 {
		t.Fatal("no supported terminal backends were discovered")
	}
	for _, backend := range backends {
		backend := backend
		t.Run(backend.ID, func(t *testing.T) {
			result, err := runLocalTerminal(ctx, workspace, backend, ".", "echo murong-terminal-ok", 25)
			if err != nil || result.ExitCode != 0 || !strings.Contains(result.Stdout, "murong-terminal-ok") {
				t.Fatalf("unexpected terminal result: %#v, %v", result, err)
			}
		})
	}
}
