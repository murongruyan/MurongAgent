//go:build embedded_codex

package main

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestEmbeddedCodexReleaseArchive(t *testing.T) {
	spec, err := currentCodexPlatformSpec()
	if err != nil {
		t.Fatal(err)
	}
	archive := embeddedCodexArchiveBytes()
	if len(archive) < 100<<20 {
		t.Fatalf("embedded Codex archive is unexpectedly small: %d bytes", len(archive))
	}
	digest := sha256.Sum256(archive)
	if actual := strings.ToUpper(hex.EncodeToString(digest[:])); actual != spec.ArchiveSHA256 {
		t.Fatalf("embedded Codex archive hash mismatch: %s", actual)
	}
	destination := t.TempDir()
	if err := extractEmbeddedCodexArchive(archive, destination); err != nil {
		t.Fatal(err)
	}
	executable := codexExecutableInRoot(destination, spec)
	info, err := os.Stat(executable)
	if err != nil || info.Size() < 100<<20 {
		t.Fatalf("embedded Codex executable is missing or too small: %#v, %v", info, err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	output, err := exec.CommandContext(ctx, executable, "--version").CombinedOutput()
	if err != nil || !strings.Contains(string(output), embeddedCodexVersion) {
		t.Fatalf("extracted Codex executable did not start: %q, %v", output, err)
	}
	packageMetadata := filepath.Join(destination, "vendor", spec.Target, "codex-package.json")
	data, err := os.ReadFile(packageMetadata)
	if err != nil || !strings.Contains(string(data), `"version": "`+embeddedCodexVersion+`"`) {
		t.Fatalf("embedded Codex package metadata is invalid: %v", err)
	}
}
