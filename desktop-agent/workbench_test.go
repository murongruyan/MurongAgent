package main

import (
	"bytes"
	"encoding/base64"
	"image"
	"image/color"
	"image/png"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
	"time"
)

func TestWorkbenchEditorScopesFilesAndPreventsStaleOverwrite(t *testing.T) {
	t.Setenv("MURONG_DESKTOP_DATA_DIR", t.TempDir())
	project := t.TempDir()
	path := filepath.Join(project, "main.txt")
	if err := os.WriteFile(path, []byte("first"), 0o644); err != nil {
		t.Fatal(err)
	}
	store, err := newDesktopStore()
	if err != nil {
		t.Fatal(err)
	}
	if _, err := store.activateProject(project); err != nil {
		t.Fatal(err)
	}
	app := &DesktopAgentApp{store: store}
	entries, err := app.ListWorkbenchFiles(".")
	if err != nil || len(entries) != 1 || entries[0].Path != "main.txt" || entries[0].Directory {
		t.Fatalf("unexpected workbench entries: %#v, %v", entries, err)
	}
	document, err := app.ReadWorkbenchFile("main.txt")
	if err != nil || document.Content != "first" || document.SHA256 == "" {
		t.Fatalf("unexpected workbench document: %#v, %v", document, err)
	}
	written, err := app.SaveWorkbenchFile(WorkbenchSaveFileRequest{Path: "main.txt", Content: "second", ExpectedSHA256: document.SHA256})
	if err != nil || written.Content != "second" || written.SHA256 == document.SHA256 {
		t.Fatalf("unexpected workbench save: %#v, %v", written, err)
	}
	if _, err := app.SaveWorkbenchFile(WorkbenchSaveFileRequest{Path: "main.txt", Content: "stale", ExpectedSHA256: document.SHA256}); err == nil {
		t.Fatal("workbench editor accepted a stale overwrite")
	}
	if _, err := app.ReadWorkbenchFile("../outside.txt"); err == nil {
		t.Fatal("workbench editor escaped the selected project")
	}
	var encoded bytes.Buffer
	picture := image.NewRGBA(image.Rect(0, 0, 2, 3))
	picture.Set(0, 0, color.RGBA{R: 220, G: 40, B: 120, A: 255})
	if err := png.Encode(&encoded, picture); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(project, "preview.png"), encoded.Bytes(), 0o644); err != nil {
		t.Fatal(err)
	}
	asset, err := app.ReadWorkbenchAsset("preview.png")
	if err != nil || asset.MIMEType != "image/png" || asset.Width != 2 || asset.Height != 3 || asset.Base64 == "" || asset.SHA256 == "" {
		t.Fatalf("unexpected image preview: %#v, %v", asset, err)
	}
	if err := os.WriteFile(filepath.Join(project, "fake.jpg"), []byte("not an image"), 0o644); err != nil {
		t.Fatal(err)
	}
	if _, err := app.ReadWorkbenchAsset("fake.jpg"); err == nil {
		t.Fatal("workbench accepted an invalid image preview")
	}
	entries, err = app.ListWorkbenchFiles(".")
	if err != nil {
		t.Fatal(err)
	}
	foundPreview := false
	for _, entry := range entries {
		if entry.Path == "preview.png" && entry.Preview == "image" {
			foundPreview = true
		}
	}
	if !foundPreview {
		t.Fatal("workbench file list did not mark the image preview")
	}
}

func TestWorkbenchTerminalUsesSelectedRealShell(t *testing.T) {
	t.Setenv("MURONG_DESKTOP_DATA_DIR", t.TempDir())
	project := t.TempDir()
	store, err := newDesktopStore()
	if err != nil {
		t.Fatal(err)
	}
	if _, err := store.activateProject(project); err != nil {
		t.Fatal(err)
	}
	var terminal TerminalBackend
	if runtime.GOOS == "windows" {
		path, err := exec.LookPath("cmd.exe")
		if err != nil {
			t.Skip("cmd.exe is unavailable")
		}
		terminal = TerminalBackend{ID: terminalCMD, Label: "CMD", Kind: terminalCMD, Executable: path}
	} else {
		path, err := exec.LookPath("sh")
		if err != nil {
			t.Skip("sh is unavailable")
		}
		terminal = TerminalBackend{ID: "shell:sh", Label: "sh", Kind: "posix-shell", Executable: path}
	}
	output := make(chan string, 32)
	exited := make(chan workbenchTerminalExit, 1)
	app := &DesktopAgentApp{store: store, terminals: []TerminalBackend{terminal}}
	app.workbenchTerminals = newWorkbenchTerminalManager(func(name string, payload any) {
		switch value := payload.(type) {
		case workbenchTerminalOutput:
			decoded, _ := base64.StdEncoding.DecodeString(value.Base64)
			output <- string(decoded)
		case workbenchTerminalExit:
			exited <- value
		}
	})
	info, err := app.StartWorkbenchTerminalSession(WorkbenchTerminalStartRequest{
		ClientID: "test-terminal", TerminalID: terminal.ID, Directory: ".", Columns: 100, Rows: 30,
	})
	if err != nil {
		t.Fatal(err)
	}
	defer app.CloseWorkbenchTerminalSession(info.SessionID)
	lineEnding := "\n"
	if runtime.GOOS == "windows" {
		// xterm.js sends carriage return for Enter; ConPTY performs line input
		// processing and does not need an additional line feed.
		lineEnding = "\r"
	}
	if err := app.WriteWorkbenchTerminalSession(WorkbenchTerminalWriteRequest{
		SessionID: info.SessionID, Data: "echo murong-workbench" + lineEnding + "echo murong-second-line" + lineEnding,
	}); err != nil {
		t.Fatal(err)
	}
	deadline := time.NewTimer(15 * time.Second)
	defer deadline.Stop()
	combined := ""
	for !strings.Contains(combined, "murong-workbench") || !strings.Contains(combined, "murong-second-line") {
		select {
		case chunk := <-output:
			combined += chunk
		case result := <-exited:
			if result.ExitCode != 0 {
				t.Fatalf("terminal exited early with %d: %q", result.ExitCode, combined)
			}
		case <-deadline.C:
			t.Fatalf("interactive terminal produced no marker: %q", combined)
		}
	}
	if err := app.ResizeWorkbenchTerminalSession(WorkbenchTerminalResizeRequest{SessionID: info.SessionID, Columns: 120, Rows: 40}); err != nil {
		t.Fatal(err)
	}
	if err := app.WriteWorkbenchTerminalSession(WorkbenchTerminalWriteRequest{SessionID: info.SessionID, Data: "exit" + lineEnding}); err != nil {
		t.Fatal(err)
	}
}
