package main

import (
	"archive/tar"
	"bytes"
	"compress/gzip"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func codexTestArchive(t *testing.T, entries map[string][]byte) []byte {
	t.Helper()
	var buffer bytes.Buffer
	gzipWriter := gzip.NewWriter(&buffer)
	tarWriter := tar.NewWriter(gzipWriter)
	for name, data := range entries {
		if err := tarWriter.WriteHeader(&tar.Header{Name: name, Mode: 0o700, Size: int64(len(data)), Typeflag: tar.TypeReg}); err != nil {
			t.Fatal(err)
		}
		if _, err := tarWriter.Write(data); err != nil {
			t.Fatal(err)
		}
	}
	if err := tarWriter.Close(); err != nil {
		t.Fatal(err)
	}
	if err := gzipWriter.Close(); err != nil {
		t.Fatal(err)
	}
	return buffer.Bytes()
}

func TestExtractEmbeddedCodexArchiveKeepsPackageLayout(t *testing.T) {
	spec, err := currentCodexPlatformSpec()
	if err != nil {
		t.Fatal(err)
	}
	archive := codexTestArchive(t, map[string][]byte{
		"package/vendor/" + spec.Target + "/bin/" + spec.Executable:     []byte("native"),
		"package/vendor/" + spec.Target + "/codex-resources/helper.bin": []byte("helper"),
	})
	destination := t.TempDir()
	if err := extractEmbeddedCodexArchive(archive, destination); err != nil {
		t.Fatal(err)
	}
	path := codexExecutableInRoot(destination, spec)
	if data, err := os.ReadFile(path); err != nil || string(data) != "native" {
		t.Fatalf("unexpected extracted executable: %q, %v", data, err)
	}
}

func TestExtractEmbeddedCodexArchiveRejectsTraversal(t *testing.T) {
	archive := codexTestArchive(t, map[string][]byte{"package/../outside.exe": []byte("bad")})
	err := extractEmbeddedCodexArchive(archive, t.TempDir())
	if err == nil || !strings.Contains(err.Error(), "越界路径") {
		t.Fatalf("expected traversal rejection, got %v", err)
	}
}

func TestResolveCodexExecutableHonoursAdvancedOverride(t *testing.T) {
	spec, err := currentCodexPlatformSpec()
	if err != nil {
		t.Fatal(err)
	}
	executable := filepath.Join(t.TempDir(), spec.Executable)
	if err := os.WriteFile(executable, []byte("test"), 0o700); err != nil {
		t.Fatal(err)
	}
	resolved, builtin, err := resolveCodexExecutable(t.TempDir(), executable)
	if err != nil || builtin || !strings.EqualFold(resolved, executable) {
		t.Fatalf("unexpected override resolution: %q %v %v", resolved, builtin, err)
	}
}
