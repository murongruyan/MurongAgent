package main

import (
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestProjectAuditPersistsFiltersPaginatesAndCoalescesExternalChanges(t *testing.T) {
	path := filepath.Join(t.TempDir(), "project-audit.json")
	project := t.TempDir()
	otherProject := t.TempDir()
	store, err := newProjectAuditStore(path)
	if err != nil {
		t.Fatal(err)
	}
	for _, entry := range []ProjectAuditEntry{
		{ID: "external-1", ProjectPath: project, Source: projectAuditSourceExternal, Action: "modified", Outcome: projectAuditOutcomeObserved, Summary: "外部修改", Paths: []string{"src/main.go"}, CreatedAt: 1_000},
		{ID: "external-2", ProjectPath: project, Source: projectAuditSourceExternal, Action: "modified", Outcome: projectAuditOutcomeObserved, Summary: "外部修改", Paths: []string{"src/main.go"}, CreatedAt: 1_100},
		{ID: "agent-1", ProjectPath: project, SessionID: "session-1", SessionTitle: "发布任务", Source: projectAuditSourceAgent, Action: "git", Outcome: projectAuditOutcomeSuccess, ToolName: "run_terminal", Summary: "Agent 执行 Git 命令", CreatedAt: 2_000},
		{ID: "other-1", ProjectPath: otherProject, Source: projectAuditSourceExternal, Action: "created", Outcome: projectAuditOutcomeObserved, Summary: "其他项目", Paths: []string{"other.txt"}, CreatedAt: 3_000},
	} {
		if err := store.Record(entry); err != nil {
			t.Fatal(err)
		}
	}
	if err := store.Flush(); err != nil {
		t.Fatal(err)
	}
	reloaded, err := newProjectAuditStore(path)
	if err != nil {
		t.Fatal(err)
	}
	defer reloaded.Close()
	page := reloaded.Query(project, ProjectAuditQuery{Limit: 1})
	if page.TotalCount != 2 || page.FilteredCount != 2 || len(page.Entries) != 1 || !page.HasMore || page.Entries[0].ID != "agent-1" {
		t.Fatalf("unexpected first audit page: %#v", page)
	}
	next := reloaded.Query(project, ProjectAuditQuery{Limit: 1, BeforeAt: page.NextBeforeAt, BeforeID: page.NextBeforeID})
	if len(next.Entries) != 1 || next.Entries[0].ID != "external-1" || next.Entries[0].Occurrences != 2 || next.HasMore {
		t.Fatalf("unexpected second audit page: %#v", next)
	}
	filtered := reloaded.Query(project, ProjectAuditQuery{Search: "发布", Source: projectAuditSourceAgent, Limit: 100})
	if filtered.FilteredCount != 1 || len(filtered.Entries) != 1 || filtered.Entries[0].ToolName != "run_terminal" {
		t.Fatalf("audit filter did not match stable fields: %#v", filtered)
	}
}

func TestProjectAuditToolReceiptsAreIdempotentAndDoNotPersistArgumentsOrContent(t *testing.T) {
	dataRoot := t.TempDir()
	t.Setenv("MURONG_DESKTOP_DATA_DIR", dataRoot)
	project := t.TempDir()
	store, err := newDesktopStore()
	if err != nil {
		t.Fatal(err)
	}
	if _, err := store.activateProject(project); err != nil {
		t.Fatal(err)
	}
	session, err := store.createSession("安全审计")
	if err != nil {
		t.Fatal(err)
	}
	auditPath := filepath.Join(dataRoot, "audit.json")
	audit, err := newProjectAuditStore(auditPath)
	if err != nil {
		t.Fatal(err)
	}
	defer audit.Close()
	app := &DesktopAgentApp{store: store, audit: audit}
	terminalArguments := `{"command":"git push https://token-user:SUPER_SECRET@example.invalid/repo","path":"."}`
	app.recordProjectToolAudit(session.ID, project, "call-terminal", "run_terminal", terminalArguments, projectAuditSourceAgent, true)
	app.recordProjectToolAudit(session.ID, project, "call-terminal", "run_terminal", terminalArguments, projectAuditSourceAgent, true)
	writeArguments := `{"path":"src/secret.txt","content":"FILE_CONTENT_SECRET"}`
	app.recordProjectToolAudit(session.ID, project, "call-write", "write_file", writeArguments, projectAuditSourceAgent, true)
	app.recordProjectToolAudit(session.ID, project, "call-read", "read_file", `{"path":"src/secret.txt"}`, projectAuditSourceAgent, true)
	if err := audit.Flush(); err != nil {
		t.Fatal(err)
	}
	page := audit.Query(project, ProjectAuditQuery{Limit: 100})
	if page.TotalCount != 2 || len(page.Entries) != 2 {
		t.Fatalf("duplicate/read-only tool receipt changed audit cardinality: %#v", page)
	}
	actions := map[string]ProjectAuditEntry{}
	for _, entry := range page.Entries {
		actions[entry.Action] = entry
	}
	if len(actions["file_write"].Paths) != 1 || actions["file_write"].Paths[0] != "src/secret.txt" || actions["git"].ToolName != "run_terminal" {
		t.Fatalf("unexpected redacted audit entries: %#v", page.Entries)
	}
	data, err := os.ReadFile(auditPath)
	if err != nil {
		t.Fatal(err)
	}
	for _, secret := range []string{"SUPER_SECRET", "FILE_CONTENT_SECRET", "git push", "token-user"} {
		if strings.Contains(string(data), secret) {
			t.Fatalf("audit persisted sensitive tool argument %q: %s", secret, data)
		}
	}
}

func TestExecuteToolAndCodexReceiptsEnterProjectAuditAfterRealExecution(t *testing.T) {
	dataRoot := t.TempDir()
	t.Setenv("MURONG_DESKTOP_DATA_DIR", dataRoot)
	project := t.TempDir()
	store, err := newDesktopStore()
	if err != nil {
		t.Fatal(err)
	}
	if _, err := store.activateProject(project); err != nil {
		t.Fatal(err)
	}
	session, err := store.createSession("工具事实")
	if err != nil {
		t.Fatal(err)
	}
	audit, err := newProjectAuditStore(filepath.Join(dataRoot, "audit.json"))
	if err != nil {
		t.Fatal(err)
	}
	defer audit.Close()
	app := &DesktopAgentApp{store: store, audit: audit, approvals: map[string]chan bool{}, runs: map[string]context.CancelFunc{}}
	workspace, err := newLocalWorkspace(project)
	if err != nil {
		t.Fatal(err)
	}
	config := defaultDesktopConfig()
	config.ProjectPath = project
	config.ApprovalMode = approvalYolo
	call := modelToolCall{ID: "call-write-real", Function: modelToolFunction{Name: "write_file", Arguments: `{"path":"created.txt","content":"real"}`}}
	if _, err := app.executeTool(context.Background(), session.ID, config, workspace, call); err != nil {
		t.Fatal(err)
	}
	item := codexThreadItem{Type: "commandExecution", ID: "codex-command-real", Command: "git status --short", Cwd: project, Status: "completed"}
	if err := app.persistCodexCompletedItem(session.ID, item, false); err != nil {
		t.Fatal(err)
	}
	page := audit.Query(project, ProjectAuditQuery{Limit: 100})
	var codexEntry, writeEntry *ProjectAuditEntry
	for index := range page.Entries {
		entry := &page.Entries[index]
		if entry.Source == projectAuditSourceCodex {
			codexEntry = entry
		}
		if entry.Action == "file_write" {
			writeEntry = entry
		}
	}
	if page.TotalCount != 2 || codexEntry == nil || codexEntry.Action != "git" || writeEntry == nil || len(writeEntry.Paths) != 1 || writeEntry.Paths[0] != "created.txt" {
		t.Fatalf("real tool execution did not produce both audit receipts: %#v", page)
	}
}

func TestWorkspaceWatcherEmitsAuditEventButStillSuppressesDirectAgentWrite(t *testing.T) {
	project := t.TempDir()
	tracker := newWorkspaceChangeTracker()
	defer tracker.Close()
	tracker.mu.Lock()
	tracker.projectPath = project
	tracker.generation = 1
	tracker.watching = true
	tracker.mu.Unlock()
	var observed []WorkspaceFileChange
	tracker.SetEventListener(func(receivedProject string, change WorkspaceFileChange) {
		if !sameWorkspacePath(receivedProject, project) {
			t.Fatalf("event crossed project boundary: %q", receivedProject)
		}
		observed = append(observed, change)
	})
	if err := os.WriteFile(filepath.Join(project, "external.txt"), []byte("outside"), 0o600); err != nil {
		t.Fatal(err)
	}
	tracker.recordNativeChange(1, "external.txt", workspaceChangeModified)
	tracker.IgnoreAgentPaths(project, "agent.txt")
	tracker.recordNativeChange(1, "agent.txt", workspaceChangeModified)
	if len(observed) != 1 || observed[0].Path != "external.txt" {
		t.Fatalf("unexpected audit events: %#v", observed)
	}
}

func TestProjectAuditValidationRejectsUnsafePathsAndUnknownEnums(t *testing.T) {
	project := t.TempDir()
	valid := ProjectAuditEntry{
		ID: "valid", ProjectPath: project, Source: projectAuditSourceExternal, Action: "modified",
		Outcome: projectAuditOutcomeObserved, Summary: "ok", Paths: []string{"src/main.go"}, CreatedAt: 1, Occurrences: 1,
	}
	if err := validateProjectAuditEntry(valid); err != nil {
		t.Fatal(err)
	}
	for _, invalid := range []ProjectAuditEntry{
		func() ProjectAuditEntry { value := valid; value.Paths = []string{"../outside"}; return value }(),
		func() ProjectAuditEntry { value := valid; value.Source = "remote"; return value }(),
		func() ProjectAuditEntry { value := valid; value.Action = "command_text"; return value }(),
		func() ProjectAuditEntry { value := valid; value.Summary = "bad\ncontrol"; return value }(),
	} {
		if err := validateProjectAuditEntry(invalid); err == nil {
			t.Fatalf("invalid audit entry unexpectedly passed: %#v", invalid)
		}
	}
	for _, query := range []ProjectAuditQuery{
		{Source: "unknown"}, {Limit: maxProjectAuditQueryLimit + 1}, {BeforeID: "orphan-cursor"}, {Search: "bad\nquery"},
	} {
		if err := validateProjectAuditQuery(query); err == nil {
			t.Fatalf("invalid audit query unexpectedly passed: %#v", query)
		}
	}
}
