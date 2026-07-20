package main

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestCodeSearchPrefersSourceAndExcludesGeneratedArtifacts(t *testing.T) {
	root := t.TempDir()
	for _, directory := range []string{filepath.Join(root, "src", "main"), filepath.Join(root, "src", "test"), filepath.Join(root, "build")} {
		if err := os.MkdirAll(directory, 0o755); err != nil {
			t.Fatal(err)
		}
	}
	for path, content := range map[string]string{
		filepath.Join(root, "src", "main", "main.go"):      "package main\nfunc TargetSymbol() {}\n",
		filepath.Join(root, "src", "test", "main_test.go"): "package test\n// TargetSymbol test\n",
		filepath.Join(root, "build", "generated.go"):       "package generated\n// TargetSymbol generated\n",
	} {
		if err := os.WriteFile(path, []byte(content), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	workspace, err := newLocalWorkspace(root)
	if err != nil {
		t.Fatal(err)
	}
	matches, err := workspace.searchCode(context.Background(), "TargetSymbol", ".", 20, 1, "*.go", "", true, false)
	if err != nil {
		t.Fatal(err)
	}
	if len(matches) != 2 || matches[0].Path != "src/main/main.go" || matches[1].Path != "src/test/main_test.go" || !strings.Contains(matches[0].Context, ">    2") {
		t.Fatalf("unexpected source-first matches: %#v", matches)
	}
}

func TestCodeEditSearchReplaceAndApplyPatch(t *testing.T) {
	root := t.TempDir()
	file := filepath.Join(root, "main.go")
	if err := os.WriteFile(file, []byte("package main\n\nfunc value() string {\n\treturn \"old\"\n}\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	workspace, err := newLocalWorkspace(root)
	if err != nil {
		t.Fatal(err)
	}
	app := &DesktopAgentApp{approvals: map[string]chan bool{}, runs: map[string]context.CancelFunc{}}
	config := defaultDesktopConfig()
	config.ApprovalMode = approvalYolo
	args, _ := json.Marshal(map[string]any{"operation": "search_replace", "path": "main.go", "search": `return "old"`, "replace": `return "new"`})
	if _, err := app.executeTool(context.Background(), "session", config, workspace, modelToolCall{Function: modelToolFunction{Name: "code_edit", Arguments: string(args)}}); err != nil {
		t.Fatal(err)
	}
	patch := "*** Begin Patch\n*** Update File: main.go\n@@ -3,3 +3,3 @@\n func value() string {\n-\treturn \"new\"\n+\treturn \"patched\"\n }\n*** End Patch"
	args, _ = json.Marshal(map[string]any{"operation": "apply_patch", "path": "main.go", "patch_text": patch})
	if _, err := app.executeTool(context.Background(), "session", config, workspace, modelToolCall{Function: modelToolFunction{Name: "code_edit", Arguments: string(args)}}); err != nil {
		t.Fatal(err)
	}
	data, err := os.ReadFile(file)
	if err != nil || !strings.Contains(string(data), `return "patched"`) {
		t.Fatalf("code edit did not apply: %v, %s", err, data)
	}
}

func TestApplySingleFileUpdatePatchRejectsMismatchedPath(t *testing.T) {
	_, err := applySingleFileUpdatePatch("main.go", "old\n", "*** Begin Patch\n*** Update File: other.go\n@@\n-old\n+new\n*** End Patch")
	if err == nil {
		t.Fatal("expected mismatched patch path to be rejected")
	}
}
