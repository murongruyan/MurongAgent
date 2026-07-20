package main

import (
	"bytes"
	"path/filepath"
	"strings"
	"testing"
)

func TestSessionRenameForkRollbackAndStats(t *testing.T) {
	t.Setenv("MURONG_DESKTOP_DATA_DIR", t.TempDir())
	store, err := newDesktopStore()
	if err != nil {
		t.Fatal(err)
	}
	session, err := store.createSession("原任务")
	if err != nil {
		t.Fatal(err)
	}
	first, err := store.appendMessage(session.ID, ChatMessage{Role: "user", Content: "目标：修复会话", Mode: "goal"})
	if err != nil {
		t.Fatal(err)
	}
	second, err := store.appendMessage(session.ID, ChatMessage{Role: "assistant", Content: "先检查持久化"})
	if err != nil {
		t.Fatal(err)
	}
	third, err := store.appendMessage(session.ID, ChatMessage{Role: "tool", Content: "ok", ToolName: "read_file"})
	if err != nil {
		t.Fatal(err)
	}
	renamed, err := store.renameSession(session.ID, "会话高级操作")
	if err != nil || renamed.Title != "会话高级操作" {
		t.Fatalf("rename failed: %#v, %v", renamed, err)
	}
	forked, err := store.forkSession(session.ID, second.Messages[1].ID)
	if err != nil {
		t.Fatal(err)
	}
	if forked.ID == session.ID || len(forked.Messages) != 2 || forked.Messages[0].ID == first.Messages[0].ID || !strings.Contains(forked.Title, "分叉") {
		t.Fatalf("unexpected fork: %#v", forked)
	}
	rolledBack, err := store.rollbackSession(session.ID, first.Messages[0].ID)
	if err != nil {
		t.Fatal(err)
	}
	if len(rolledBack.Messages) != 1 || rolledBack.Goal != "目标：修复会话" {
		t.Fatalf("unexpected rollback: %#v", rolledBack)
	}
	if len(third.Messages) != 3 {
		t.Fatal("test fixture unexpectedly changed")
	}
	stats := sessionStats(forked)
	if stats.MessageCount != 2 || stats.UserMessages != 1 || stats.AssistantMessages != 1 || stats.ToolMessages != 0 || stats.CharacterCount == 0 || stats.EstimatedTokens == 0 {
		t.Fatalf("unexpected stats: %#v", stats)
	}
	if forked.Usage != (SessionUsage{}) {
		t.Fatalf("empty source unexpectedly produced fork usage: %#v", forked.Usage)
	}
}

func TestPortableSessionRoundTripAndImportRekeys(t *testing.T) {
	projectPath := filepath.Join(t.TempDir(), "project")
	source := &ChatSession{
		ID: "session-source", Title: "可移植任务", CreatedAt: 10, UpdatedAt: 20, ProjectPath: projectPath,
		Goal: "完成导入导出", PlanModeEnabled: true,
		Usage: SessionUsage{ModelRequests: 1, ReportedUsageRequests: 1, InputTokens: 20, OutputTokens: 5, TotalTokens: 25, LastProviderID: providerOpenAI, LastModel: "test-model"},
		Messages: []ChatMessage{
			{ID: "message-user", Role: "user", Content: "请检查", CreatedAt: 11, Mode: "goal", Context: []ComposerContextItem{{Kind: "file", Path: "README.md", Label: "README.md"}}, WorkspaceChanges: []WorkspaceFileChange{{Path: "src/main.go", Kind: "modified", ChangedAt: 10, Size: 42}}},
			{ID: "message-assistant", Role: "assistant", Content: "已经检查", CreatedAt: 12},
		},
	}
	data, err := encodePortableSession(source)
	if err != nil {
		t.Fatal(err)
	}
	decoded, err := decodePortableSession(data)
	if err != nil {
		t.Fatal(err)
	}
	if decoded.ID != source.ID || decoded.ProjectPath != projectPath || decoded.Messages[0].Context[0].Path != "README.md" || decoded.Messages[0].WorkspaceChanges[0].Path != "src/main.go" || decoded.PlanModeEnabled != true || decoded.Usage.TotalTokens != 25 {
		t.Fatalf("unexpected decoded session: %#v", decoded)
	}
	t.Setenv("MURONG_DESKTOP_DATA_DIR", t.TempDir())
	store, err := newDesktopStore()
	if err != nil {
		t.Fatal(err)
	}
	imported, err := store.importSession(decoded)
	if err != nil {
		t.Fatal(err)
	}
	if imported.ID == source.ID || imported.Messages[0].ID == source.Messages[0].ID || !strings.Contains(imported.Title, "导入") || imported.Goal != source.Messages[0].Content {
		t.Fatalf("import did not rekey or normalize: %#v", imported)
	}
	if decoded.Messages[0].ID != "message-user" {
		t.Fatal("import mutated the decoded source")
	}
}

func TestPortableSessionRejectsUnknownFieldsTrailingDataAndInvalidMessages(t *testing.T) {
	valid := &ChatSession{ID: "session", Title: "task", Messages: []ChatMessage{{ID: "message", Role: "user", Content: "hello"}}}
	data, err := encodePortableSession(valid)
	if err != nil {
		t.Fatal(err)
	}
	unknown := bytes.Replace(data, []byte(`"formatVersion": 1`), []byte(`"formatVersion": 1, "unknown": true`), 1)
	for name, input := range map[string][]byte{
		"unknown":      unknown,
		"trailing":     append(append([]byte{}, data...), []byte(` {}`)...),
		"wrong-format": bytes.Replace(data, []byte(`"murong-session"`), []byte(`"not-murong"`), 1),
	} {
		t.Run(name, func(t *testing.T) {
			if _, err := decodePortableSession(input); err == nil {
				t.Fatalf("expected %s input to fail", name)
			}
		})
	}
	invalid := cloneSession(valid)
	invalid.Messages[0].Role = "system"
	if _, err := encodePortableSession(invalid); err == nil {
		t.Fatal("expected invalid role to fail")
	}
}

func TestCrossPlatformSessionRoundTripDropsDesktopOnlyPaths(t *testing.T) {
	projectPath := filepath.Join(t.TempDir(), "private", "project")
	source := &ChatSession{
		ID: "session-source", Title: "跨端任务", CreatedAt: 10, UpdatedAt: 20, ProjectPath: projectPath, Goal: "手机与电脑继续同一任务",
		Usage: SessionUsage{
			ModelRequests: 2, ReportedUsageRequests: 2, InputTokens: 100, OutputTokens: 40,
			TotalTokens: 140, CachedInputTokens: 25, ReasoningOutputTokens: 8,
			LastProviderID: providerOpenAI, LastModel: "shared-model",
		},
		Messages: []ChatMessage{
			{
				ID: "user", Role: "user", Content: "检查项目", CreatedAt: 11, Mode: "goal",
				Context:          []ComposerContextItem{{Kind: "file", Path: `private/project/secret.go`, Label: "secret.go"}},
				WorkspaceChanges: []WorkspaceFileChange{{Path: `private/project/secret.go`, Kind: "modified", ChangedAt: 10}},
			},
			{ID: "tool", Role: "tool", Content: "测试通过", CreatedAt: 12, ToolName: "run_terminal"},
			{ID: "assistant", Role: "assistant", Content: "已经处理", CreatedAt: 13, Kind: "final"},
			{ID: "user-2", Role: "user", Content: "继续检查", CreatedAt: 14},
			{ID: "assistant-2", Role: "assistant", Content: "正在继续", CreatedAt: 15},
			{ID: "tool-2", Role: "tool", Content: "第二轮测试通过", CreatedAt: 16, ToolName: "run_terminal"},
		},
		Compression: SessionCompression{
			Version: 2, Summary: "已确认跨端需求，完成两轮项目检查和测试，并记录了仍需在手机端继续完成的具体实现步骤。", SourceMessageCount: 6,
			SourceEndMessageID: "tool-2", CreatedAt: 17, Active: true, Method: "provider",
		},
	}
	data, err := encodeCrossPlatformSession(source)
	if err != nil {
		t.Fatal(err)
	}
	text := string(data)
	if strings.Contains(text, projectPath) || strings.Contains(text, `workspaceChanges`) || strings.Contains(text, `"context"`) || strings.Contains(text, `"method"`) || strings.Contains(text, `"projectPath"`) {
		t.Fatalf("cross-platform export leaked desktop-only state: %s", text)
	}
	for _, marker := range []string{crossPlatformSessionFormat, `"sourcePlatform": "` + desktopSourcePlatform() + `"`, `"toolName": "run_terminal"`, `"cachedInputTokens": 25`} {
		if !strings.Contains(text, marker) {
			t.Fatalf("cross-platform export is missing %q: %s", marker, text)
		}
	}
	decoded, err := decodeCrossPlatformSession(data)
	if err != nil {
		t.Fatal(err)
	}
	if decoded.Title != source.Title || decoded.ProjectPath != "" || decoded.Goal != source.Goal || len(decoded.Messages) != 6 || decoded.Messages[1].ToolName != "run_terminal" || decoded.Usage.TotalTokens != 140 {
		t.Fatalf("unexpected cross-platform round trip: %#v", decoded)
	}
	if decoded.Compression.SourceEndMessageID != decoded.Messages[5].ID || decoded.Compression.Method != "local" {
		t.Fatalf("compression was not safely remapped: %#v", decoded.Compression)
	}
}

func TestCrossPlatformSessionImportsAndroidDocumentAndRejectsUnknownFields(t *testing.T) {
	androidDocument := []byte(`{
  "format":"murong-portable-session",
  "formatVersion":1,
  "exportedAtEpochMillis":100,
  "sourcePlatform":"android",
  "session":{
    "title":"手机任务",
    "createdAtEpochMillis":10,
    "updatedAtEpochMillis":20,
    "providerId":"openai",
    "modelName":"mobile-model",
    "goal":"继续实现跨端",
    "messages":[
      {"role":"user","content":"开始","createdAtEpochMillis":11},
      {"role":"assistant","content":"子代理结果","createdAtEpochMillis":12,"kind":"subagent"},
      {"role":"tool","content":"ok","createdAtEpochMillis":13,"toolName":"computer_terminal"}
    ],
    "usage":{"inputTokens":12,"outputTokens":4,"totalTokens":16},
    "compression":{"version":1,"summary":"已开始","sourceMessageCount":1,"createdAtEpochMillis":14,"active":false}
  }
}`)
	decoded, err := decodeImportableSession(androidDocument)
	if err != nil {
		t.Fatal(err)
	}
	if decoded.Title != "手机任务" || decoded.Messages[1].Kind != "subagent" || decoded.Messages[2].Role != "tool" || decoded.Usage.LastModel != "mobile-model" || decoded.Compression != (SessionCompression{}) {
		t.Fatalf("unexpected Android mapping: %#v", decoded)
	}
	unknown := bytes.Replace(androidDocument, []byte(`"sourcePlatform":"android"`), []byte(`"sourcePlatform":"android","projectPath":"C:\\\\secret"`), 1)
	if _, err := decodeImportableSession(unknown); err == nil {
		t.Fatal("cross-platform import accepted an unknown platform-specific field")
	}
	trailing := append(append([]byte{}, androidDocument...), []byte(` {}`)...)
	if _, err := decodeImportableSession(trailing); err == nil {
		t.Fatal("cross-platform import accepted trailing JSON")
	}
}

func TestSessionMarkdownIsReadableAndExplicitlyNotImportFormat(t *testing.T) {
	session := &ChatSession{ID: "session", Title: "阅读副本", Messages: []ChatMessage{
		{ID: "user", Role: "user", Content: "你好", WorkspaceChanges: []WorkspaceFileChange{{Path: "src/main.go", Kind: "modified", ChangedAt: 1}}},
		{ID: "tool", Role: "tool", ToolName: "run_terminal", Content: "line\n````\nline"},
	}}
	data, err := exportSessionMarkdown(session)
	if err != nil {
		t.Fatal(err)
	}
	text := string(data)
	for _, marker := range []string{"# 阅读副本", "若要重新导入，请另存 JSON 格式", "## 用户", "### 项目外部变化", "修改：src/main.go", "## 工具 · run_terminal", "`````text"} {
		if !strings.Contains(text, marker) {
			t.Fatalf("markdown is missing %q: %s", marker, text)
		}
	}
}
