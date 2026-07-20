package desktopbridge

import (
	"bytes"
	"context"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
	"time"
)

func TestRunCLIHelpReturnsSuccess(t *testing.T) {
	var output bytes.Buffer
	if err := runCLI([]string{"--help"}, &output, &output); err != nil {
		t.Fatalf("help returned an error: %v", err)
	}
	if !strings.Contains(output.String(), "-phone") || !strings.Contains(output.String(), "-workspace") {
		t.Fatalf("help output missed required flags: %s", output.String())
	}
}

func TestValidatePhoneURLAllowsLANAndTailnetOnly(t *testing.T) {
	allowed := []string{
		"http://192.168.1.20:8765",
		"http://10.0.0.2:8765",
		"http://100.100.12.34:8765",
		"http://[fd7a:115c:a1e0::1]:8765",
	}
	for _, raw := range allowed {
		if _, err := validatePhoneURL(raw); err != nil {
			t.Fatalf("expected %s to be allowed: %v", raw, err)
		}
	}
	denied := []string{
		"https://example.com",
		"http://8.8.8.8:8765",
		"http://phone.example:8765",
		"http://user:pass@192.168.1.20:8765",
	}
	for _, raw := range denied {
		if _, err := validatePhoneURL(raw); err == nil {
			t.Fatalf("expected %s to be denied", raw)
		}
	}
}

func TestWorkspacePathBoundaryRejectsTraversalAndWindowsForms(t *testing.T) {
	invalid := []string{
		"../secret.txt", "src/../secret.txt", "/etc/passwd", "C:/Windows/win.ini",
		"src\\main.go", "src//main.go", " src/main.go", "src/./main.go",
	}
	for _, path := range invalid {
		if _, err := validateWorkspacePath(path, false); err == nil {
			t.Fatalf("expected %q to be rejected", path)
		}
	}
	if path, err := validateWorkspacePath("src/main.go", false); err != nil || path != "src/main.go" {
		t.Fatalf("valid path rejected: %q %v", path, err)
	}
	if _, err := validateWorkspacePath(".", false); err == nil {
		t.Fatal("root must be rejected for file operations")
	}
	if path, err := validateWorkspacePath(".", true); err != nil || path != "." {
		t.Fatalf("root list path rejected: %q %v", path, err)
	}
}

func TestWorkspaceReadWriteMkdirAndConflict(t *testing.T) {
	root := t.TempDir()
	if err := os.WriteFile(filepath.Join(root, "README.md"), []byte("old\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	workspace, err := newWorkspace(root)
	if err != nil {
		t.Fatal(err)
	}

	read, err := workspace.read(workspaceRPCRequest{Path: "README.md", MaxBytes: maxTextBytes})
	if err != nil || read.Content == nil || *read.Content != "old\n" || len(read.SHA256) != 64 {
		t.Fatalf("unexpected read: %#v %v", read, err)
	}
	newContent := "new\n"
	expected := read.SHA256
	written, err := workspace.write(workspaceRPCRequest{
		Path: "README.md", Content: &newContent, ExpectedSHA256: &expected, MaxBytes: maxTextBytes,
	})
	if err != nil || written.Created || written.SHA256 == read.SHA256 || !strings.Contains(written.DiffPreview, "+ new") {
		t.Fatalf("unexpected write: %#v %v", written, err)
	}
	if _, err := workspace.write(workspaceRPCRequest{
		Path: "README.md", Content: &newContent, ExpectedSHA256: &expected, MaxBytes: maxTextBytes,
	}); err == nil {
		t.Fatal("stale hash must be rejected")
	}

	directory, err := workspace.mkdir(workspaceRPCRequest{Path: "generated"})
	if err != nil || !directory.Created || directory.Directory == nil || !*directory.Directory {
		t.Fatalf("unexpected mkdir: %#v %v", directory, err)
	}
	createdContent := "created"
	created, err := workspace.write(workspaceRPCRequest{
		Path: "generated/file.txt", Content: &createdContent, MaxBytes: maxTextBytes,
	})
	if err != nil || !created.Created {
		t.Fatalf("unexpected create: %#v %v", created, err)
	}
	listed, err := workspace.list(workspaceRPCRequest{Path: "generated", MaxEntries: maxDirectoryItems})
	if err != nil || len(listed.Entries) != 1 || listed.Entries[0].Path != "generated/file.txt" {
		t.Fatalf("unexpected list: %#v %v", listed, err)
	}
}

func TestWorkspaceChangeScanSuppressesNodeWritesButReportsExternalWrites(t *testing.T) {
	root := t.TempDir()
	path := filepath.Join(root, "a.txt")
	if err := os.WriteFile(path, []byte("one"), 0o644); err != nil {
		t.Fatal(err)
	}
	workspace, err := newWorkspace(root)
	if err != nil {
		t.Fatal(err)
	}
	if changes, _, err := workspace.scanChanges(); err != nil || len(changes) != 0 {
		t.Fatalf("initial scan: %#v %v", changes, err)
	}
	read, err := workspace.read(workspaceRPCRequest{Path: "a.txt", MaxBytes: maxTextBytes})
	if err != nil {
		t.Fatal(err)
	}
	two := "two"
	if _, err := workspace.write(workspaceRPCRequest{
		Path: "a.txt", Content: &two, ExpectedSHA256: &read.SHA256, MaxBytes: maxTextBytes,
	}); err != nil {
		t.Fatal(err)
	}
	if changes, _, err := workspace.scanChanges(); err != nil || len(changes) != 0 {
		t.Fatalf("node write should be suppressed: %#v %v", changes, err)
	}
	if err := os.WriteFile(path, []byte("external change"), 0o644); err != nil {
		t.Fatal(err)
	}
	changes, _, err := workspace.scanChanges()
	if err != nil || len(changes) != 1 || changes[0].Kind != "modified" || changes[0].Path != "a.txt" {
		t.Fatalf("external change missing: %#v %v", changes, err)
	}
}

func TestProtectedSecretRoundTrip(t *testing.T) {
	protected, err := protectSecret([]byte("pair-token-value"))
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(protected, "pair-token-value") {
		t.Fatal("protected secret contains plaintext")
	}
	plain, err := unprotectSecret(protected)
	if err != nil || string(plain) != "pair-token-value" {
		t.Fatalf("secret round trip failed: %q %v", plain, err)
	}
}

func TestDiffAndCommandOutputAreBounded(t *testing.T) {
	large := strings.Repeat("line\n", 40_000)
	if diff := buildWorkspaceDiff("", large, true); len(diff) > maxDiffBytes {
		t.Fatalf("diff exceeded bound: %d", len(diff))
	}
	output := newCappedCommandOutput(16)
	_, _ = output.writer(false).Write([]byte(strings.Repeat("x", 100)))
	stdout, stderr := output.strings()
	if len([]byte(stdout))+len([]byte(stderr)) > 16 || !strings.Contains(stderr, "truncated") {
		t.Fatalf("unexpected capped output: %d %q", len([]byte(stdout))+len([]byte(stderr)), stderr)
	}
}

func TestSplitTerminalIDsTrimsAndDeduplicates(t *testing.T) {
	got := splitTerminalIDs(" powershell7, wsl:Ubuntu, powershell7, ,cmd ")
	want := []string{"powershell7", "wsl:Ubuntu", "cmd"}
	if strings.Join(got, "|") != strings.Join(want, "|") {
		t.Fatalf("unexpected terminal ids: %#v", got)
	}
}

func TestResolveConfiguredTerminalsSupportsMultipleAndLegacy(t *testing.T) {
	inventory := terminalInventory{Backends: []terminalBackend{
		{ID: terminalPowerShell7, Label: "PowerShell 7"},
		{ID: terminalWindowsPowerShell, Label: "Windows PowerShell"},
		{ID: terminalWSLPrefix + "Ubuntu", Label: "WSL · Ubuntu"},
	}}
	config, resolved, err := resolveConfiguredTerminals(nodeConfig{
		TerminalBackends: []string{terminalPowerShell7, terminalWSLPrefix + "Ubuntu"},
	}, inventory)
	if err != nil || len(resolved) != 2 || !config.AllowTerminal {
		t.Fatalf("multiple terminals were not resolved: %#v %#v %v", config, resolved, err)
	}
	legacy, legacyResolved, err := resolveConfiguredTerminals(nodeConfig{AllowTerminal: true}, inventory)
	expectedLegacy := terminalWindowsPowerShell
	if runtime.GOOS != "windows" {
		expectedLegacy = terminalPowerShell7
	}
	if err != nil || len(legacyResolved) != 1 || legacy.TerminalBackends[0] != expectedLegacy {
		t.Fatalf("legacy terminal config was not migrated: %#v %#v %v", legacy, legacyResolved, err)
	}
}

func TestDecodeCommandBytesSupportsUTF16LE(t *testing.T) {
	data := []byte{'O', 0, 'K', 0, '\r', 0, '\n', 0}
	if got := decodeCommandBytes(data); got != "OK\r\n" {
		t.Fatalf("unexpected UTF-16LE decode: %q", got)
	}
}

func TestDiscoveredTerminalBackendsCanExecute(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	inventory := discoverTerminalInventory(ctx)
	if len(inventory.Backends) == 0 {
		t.Skip("no terminal backend available")
	}
	workspaceRoot := t.TempDir()
	computerWorkspace, err := newWorkspace(workspaceRoot)
	if err != nil {
		t.Fatal(err)
	}
	for _, backend := range inventory.Backends {
		backend := backend
		t.Run(backend.ID, func(t *testing.T) {
			commandText := "Write-Output murong-terminal-ok"
			switch backend.Kind {
			case terminalCMD:
				commandText = "echo murong-terminal-ok"
			case "wsl", "posix-shell":
				commandText = "printf 'murong-terminal-ok\\n'"
			}
			timeoutMillis := int64(20_000)
			maxBytes := 64 * 1024
			result, runErr := computerWorkspace.run(workspaceRPCRequest{
				Path: ".", Command: &commandText, TimeoutMillis: &timeoutMillis, MaxBytes: maxBytes,
			}, backend)
			if runErr != nil || result.ExitCode == nil || *result.ExitCode != 0 || !strings.Contains(result.Stdout, "murong-terminal-ok") {
				t.Fatalf("backend failed: result=%#v err=%v", result, runErr)
			}
		})
	}
}
