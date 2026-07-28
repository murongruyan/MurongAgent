package main

import (
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
)

func TestDesktopRuntimePackageNameIsPlatformAndVersionSpecific(t *testing.T) {
	name := desktopRuntimePackageName("LiteRT", "builtin-vlm-2026-07-v1")
	expected := "murong-runtime-litert-" + runtime.GOOS + "-" + runtime.GOARCH +
		"-builtin-vlm-2026-07-v1.tgz"
	if name != expected {
		t.Fatalf("unexpected runtime package name: %s", name)
	}
}

func TestExtractDesktopRuntimeArchiveValidatesPathsAndRequiredFiles(t *testing.T) {
	archiveBytes := codexTestArchive(t, map[string][]byte{
		"runtime/bin/engine.exe": []byte("verified runtime"),
	})
	archivePath := filepath.Join(t.TempDir(), "runtime.tgz")
	if err := os.WriteFile(archivePath, archiveBytes, 0o600); err != nil {
		t.Fatal(err)
	}
	destination := t.TempDir()
	if err := extractDesktopRuntimeArchive(archivePath, destination, "", 8, 1<<20); err != nil {
		t.Fatal(err)
	}
	if !desktopRuntimeTargetReady(destination, []string{filepath.Join("runtime", "bin", "engine.exe")}) {
		t.Fatal("extracted runtime was not recognized as ready")
	}
}

func TestExtractDesktopRuntimeArchiveRejectsTraversal(t *testing.T) {
	archiveBytes := codexTestArchive(t, map[string][]byte{
		"../outside.exe": []byte("bad"),
	})
	archivePath := filepath.Join(t.TempDir(), "runtime.tgz")
	if err := os.WriteFile(archivePath, archiveBytes, 0o600); err != nil {
		t.Fatal(err)
	}
	err := extractDesktopRuntimeArchive(archivePath, t.TempDir(), "", 8, 1<<20)
	if err == nil || !strings.Contains(err.Error(), "越界路径") {
		t.Fatalf("expected traversal rejection, got %v", err)
	}
}
