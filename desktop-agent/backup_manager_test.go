package main

import (
	"archive/zip"
	"io"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestDesktopBackupExcludesSecretsAndRestorePreservesLocalCredentials(t *testing.T) {
	store, workflowStore, manager := newDesktopBackupTestManager(t)
	backupPath := filepath.Join(t.TempDir(), "portable.zip")
	result, err := manager.CreateManualBackup(backupPath)
	if err != nil {
		t.Fatal(err)
	}
	if result.Manifest == nil || len(result.Manifest.Entries) != 8 {
		t.Fatalf("unexpected backup result: %#v", result)
	}
	payloadText := readDesktopBackupPayloadText(t, backupPath)
	for _, secret := range []string{"PROTECTED_PROVIDER_ORIGINAL", "PROTECTED_MCP_ENV_ORIGINAL", "PROTECTED_MCP_HEADER_ORIGINAL", "PROTECTED_GITHUB_ORIGINAL", "protectedApiKey", "protectedToken", "protectedEnvironment"} {
		if strings.Contains(payloadText, secret) {
			t.Fatalf("backup leaked secret marker %q", secret)
		}
	}

	current := store.backupSnapshot()
	current.Config.Model = "mutated-model"
	current.Config.ProviderProfiles[0].Model = "mutated-model"
	current.Config.ProviderProfiles[0].ProtectedAPIKey = "LOCAL_PROVIDER_KEEP"
	current.Config.ProtectedAPIKey = "LOCAL_PROVIDER_KEEP"
	current.Config.Temperature = 1.5
	current.Config.MaxTokens = 9000
	current.Config.EnableMultimodalMessages = true
	current.Config.GlobalMemories = []GlobalMemory{{ID: "memory-mutated", Title: "mutated", Content: "mutated", Enabled: true}}
	current.Config.MCPServers[0].ProtectedEnvironmentJSON = "LOCAL_MCP_ENV_KEEP"
	current.Config.MCPServers[0].ProtectedHeadersJSON = "LOCAL_MCP_HEADER_KEEP"
	current.Sessions = []*ChatSession{{
		ID: "session-mutated", Title: "mutated", CreatedAt: 2, UpdatedAt: 2,
		Messages: []ChatMessage{{ID: "message-mutated", Role: "user", Content: "mutated", CreatedAt: 2}},
	}}
	if err := store.restoreBackupSnapshot(current); err != nil {
		t.Fatal(err)
	}
	workflowCurrent := workflowStore.backupSnapshot()
	workflowCurrent.Document.GitHub.ProtectedToken = "LOCAL_GITHUB_KEEP"
	workflowCurrent.Document.Workflows = []SavedWorkflowDefinition{}
	if err := workflowStore.restoreBackupSnapshot(workflowCurrent); err != nil {
		t.Fatal(err)
	}

	restored, err := manager.Restore(backupPath)
	if err != nil {
		t.Fatal(err)
	}
	if restored.PreRestoreSnapshotName == "" || restored.RestoredEntryCount != 8 {
		t.Fatalf("restore did not create a pre-restore snapshot: %#v", restored)
	}
	got := store.backupSnapshot()
	if got.Config.Model != "backup-model" || got.Config.Temperature != 0.25 || got.Config.MaxTokens != 4321 || got.Config.EnableMultimodalMessages || !got.Config.PlannerProfileEnabled || got.Config.PlannerModel != "backup-planner" || got.Config.PlannerReasoningEffort != "xhigh" || !got.Config.SubagentProfileEnabled || got.Config.SubagentModel != "backup-child" || got.Config.SubagentReasoningEffort != "medium" || len(got.Config.GlobalMemories) != 1 || got.Config.GlobalMemories[0].ID != "memory-backup" {
		t.Fatalf("portable settings were not restored: %#v", got.Config)
	}
	if got.Config.ProviderProfiles[0].ProtectedAPIKey != "LOCAL_PROVIDER_KEEP" || got.Config.MCPServers[0].ProtectedEnvironmentJSON != "LOCAL_MCP_ENV_KEEP" || got.Config.MCPServers[0].ProtectedHeadersJSON != "LOCAL_MCP_HEADER_KEEP" {
		t.Fatalf("local provider/MCP credentials were not preserved: %#v %#v", got.Config.ProviderProfiles, got.Config.MCPServers)
	}
	if len(got.Sessions) != 1 || got.Sessions[0].ID != "session-backup" {
		t.Fatalf("sessions were not restored: %#v", got.Sessions)
	}
	workflowGot := workflowStore.backupSnapshot()
	if workflowGot.Document.GitHub.ProtectedToken != "LOCAL_GITHUB_KEEP" || len(workflowGot.Document.Workflows) != 1 || workflowGot.Document.Workflows[0].ID != "workflow-backup" {
		t.Fatalf("workflow restore or GitHub token preservation failed: %#v", workflowGot.Document)
	}
	if manager.Status().PreRestoreSnapshotCount != 1 {
		t.Fatalf("pre-restore snapshot was not retained: %#v", manager.Status())
	}
}

func TestDesktopBackupRejectsTamperingBeforePreRestoreSnapshot(t *testing.T) {
	_, _, manager := newDesktopBackupTestManager(t)
	original := filepath.Join(t.TempDir(), "original.zip")
	if _, err := manager.CreateManualBackup(original); err != nil {
		t.Fatal(err)
	}
	tampered := filepath.Join(t.TempDir(), "tampered.zip")
	rewriteDesktopBackupZip(t, original, tampered, desktopBackupSettingsPath, func(data []byte) []byte {
		return append(data, ' ')
	})
	if _, err := manager.Restore(tampered); err == nil || (!strings.Contains(err.Error(), "大小不匹配") && !strings.Contains(err.Error(), "哈希不匹配")) {
		t.Fatalf("tampered backup was not rejected: %v", err)
	}
	if manager.Status().PreRestoreSnapshotCount != 0 {
		t.Fatalf("snapshot was created before full validation: %#v", manager.Status())
	}
}

func TestDesktopAutomaticBackupScheduleAndRetention(t *testing.T) {
	_, _, manager := newDesktopBackupTestManager(t)
	if _, err := manager.UpdateSettings(DesktopBackupSettings{DailyBackupEnabled: true, MaxBackupCount: 2}); err != nil {
		t.Fatal(err)
	}
	dayOne := time.Date(2026, 7, 19, 2, 59, 0, 0, time.Local)
	if result, err := manager.createAutomaticBackupIfNeeded(dayOne); err != nil || !result.Skipped {
		t.Fatalf("backup before 03:00 should be skipped: %#v, %v", result, err)
	}
	for index := 0; index < 3; index++ {
		now := time.Date(2026, 7, 19+index, 3, 5, 0, index, time.Local)
		result, err := manager.createAutomaticBackupIfNeeded(now)
		if err != nil || result.Skipped {
			t.Fatalf("daily backup %d failed: %#v, %v", index, result, err)
		}
		if duplicate, err := manager.createAutomaticBackupIfNeeded(now.Add(time.Hour)); err != nil || !duplicate.Skipped {
			t.Fatalf("duplicate daily backup was not skipped: %#v, %v", duplicate, err)
		}
	}
	status := manager.Status()
	if status.AutomaticBackupCount != 2 || !status.Settings.DailyBackupEnabled || status.Settings.MaxBackupCount != 2 {
		t.Fatalf("automatic retention failed: %#v", status)
	}
}

func TestDesktopRestoreRollsBackWhenWorkflowWriteFails(t *testing.T) {
	store, workflowStore, manager := newDesktopBackupTestManager(t)
	backupPath := filepath.Join(t.TempDir(), "rollback.zip")
	if _, err := manager.CreateManualBackup(backupPath); err != nil {
		t.Fatal(err)
	}
	current := store.backupSnapshot()
	current.Config.Model = "current-model"
	current.Config.ProviderProfiles[0].Model = "current-model"
	if err := store.restoreBackupSnapshot(current); err != nil {
		t.Fatal(err)
	}
	if err := os.Remove(workflowStore.path); err != nil && !os.IsNotExist(err) {
		t.Fatal(err)
	}
	if err := os.Mkdir(workflowStore.path, 0o700); err != nil {
		t.Fatal(err)
	}
	if _, err := manager.Restore(backupPath); err == nil {
		t.Fatal("restore unexpectedly succeeded with an unwritable workflow target")
	}
	if got := store.backupSnapshot().Config.Model; got != "current-model" {
		t.Fatalf("desktop store was not rolled back, got model %q", got)
	}
	if manager.Status().PreRestoreSnapshotCount != 1 {
		t.Fatalf("validated restore did not create its safety snapshot: %#v", manager.Status())
	}
}

func TestDesktopRestoreDisablesWorkflowWhenProjectNoLongerExists(t *testing.T) {
	_, workflowStore, manager := newDesktopBackupTestManager(t)
	project := filepath.Join(t.TempDir(), "project")
	if err := os.Mkdir(project, 0o700); err != nil {
		t.Fatal(err)
	}
	document := workflowStore.backupSnapshot()
	document.Document.Workflows = []SavedWorkflowDefinition{{
		ID: "workflow-project", Name: "project", Template: workflowProjectReadDiagnostic,
		ProjectPath: project, Nodes: defaultSavedWorkflowNodes(workflowProjectReadDiagnostic),
		IntervalMinutes: 60, Enabled: true, CreatedAt: 1, UpdatedAt: 1,
	}}
	if err := workflowStore.restoreBackupSnapshot(document); err != nil {
		t.Fatal(err)
	}
	backupPath := filepath.Join(t.TempDir(), "missing-project.zip")
	if _, err := manager.CreateManualBackup(backupPath); err != nil {
		t.Fatal(err)
	}
	if err := os.RemoveAll(project); err != nil {
		t.Fatal(err)
	}
	if _, err := manager.Restore(backupPath); err != nil {
		t.Fatal(err)
	}
	workflow := workflowStore.backupSnapshot().Document.Workflows[0]
	if workflow.Enabled || workflow.LastRun == nil || workflow.LastRun.Status != workflowRunBlocked {
		t.Fatalf("missing project workflow was not safely disabled: %#v", workflow)
	}
}

func TestDesktopBackupRestoresProjectAuditAndOldArchivesPreserveCurrentAudit(t *testing.T) {
	_, _, manager := newDesktopBackupTestManager(t)
	project := t.TempDir()
	oldArchive := filepath.Join(t.TempDir(), "without-audit.zip")
	if result, err := manager.CreateManualBackup(oldArchive); err != nil || result.Manifest == nil || len(result.Manifest.Entries) != 8 {
		t.Fatalf("failed to create backward-compatible archive: %#v, %v", result, err)
	}
	audit, err := newProjectAuditStore(filepath.Join(t.TempDir(), "project-audit.json"))
	if err != nil {
		t.Fatal(err)
	}
	defer audit.Close()
	manager.audit = audit
	initial := ProjectAuditEntry{
		ID: "audit-original", ProjectPath: project, SessionID: "session-backup", SessionTitle: "backup session",
		Source: projectAuditSourceAgent, Action: "code_edit", Outcome: projectAuditOutcomeSuccess,
		ToolName: "code_edit", Summary: "Agent 修改项目代码", Paths: []string{"src/main.go"}, CreatedAt: 10,
	}
	if err := audit.Record(initial); err != nil {
		t.Fatal(err)
	}
	if _, err := manager.Restore(oldArchive); err != nil {
		t.Fatal(err)
	}
	if page := audit.Query(project, ProjectAuditQuery{Limit: 100}); page.TotalCount != 1 || page.Entries[0].ID != initial.ID {
		t.Fatalf("old archive erased current audit: %#v", page)
	}

	withAudit := filepath.Join(t.TempDir(), "with-audit.zip")
	result, err := manager.CreateManualBackup(withAudit)
	if err != nil {
		t.Fatal(err)
	}
	if result.Manifest == nil || len(result.Manifest.Entries) != 9 {
		t.Fatalf("project audit was not added to full backup: %#v", result)
	}
	if _, err := audit.ClearProject(project); err != nil {
		t.Fatal(err)
	}
	if err := audit.Record(ProjectAuditEntry{
		ID: "audit-mutated", ProjectPath: project, Source: projectAuditSourceExternal, Action: "deleted",
		Outcome: projectAuditOutcomeObserved, Summary: "mutated", Paths: []string{"old.txt"}, CreatedAt: 20,
	}); err != nil {
		t.Fatal(err)
	}
	restored, err := manager.Restore(withAudit)
	if err != nil {
		t.Fatal(err)
	}
	if restored.RestoredEntryCount != 9 {
		t.Fatalf("restore did not account for project audit payload: %#v", restored)
	}
	page := audit.Query(project, ProjectAuditQuery{Limit: 100})
	if page.TotalCount != 1 || page.Entries[0].ID != initial.ID || page.Entries[0].Paths[0] != "src/main.go" {
		t.Fatalf("project audit was not transactionally restored: %#v", page)
	}
}

func newDesktopBackupTestManager(t *testing.T) (*desktopStore, *savedWorkflowStore, *desktopBackupManager) {
	t.Helper()
	dataRoot := t.TempDir()
	t.Setenv("MURONG_DESKTOP_BACKUP_DIR", filepath.Join(dataRoot, "backups"))
	config := defaultDesktopConfig()
	config.Model = "backup-model"
	config.BaseURL = "https://example.test/v1"
	config.Temperature = 0.25
	config.MaxTokens = 4321
	config.EnableMultimodalMessages = false
	config.PlannerProfileEnabled = true
	config.PlannerModel = "backup-planner"
	config.PlannerReasoningEffort = "xhigh"
	config.SubagentProfileEnabled = true
	config.SubagentModel = "backup-child"
	config.SubagentReasoningEffort = "medium"
	config.ProviderProfiles = []ProviderProfile{{
		ID: "provider-backup", ProviderID: providerOpenAI, Name: "Backup Provider",
		BaseURL: "https://example.test/v1", Model: "backup-model", ReasoningEffort: "high",
		APIMode: "chat-completions", ProtectedAPIKey: "PROTECTED_PROVIDER_ORIGINAL",
	}}
	config.ActiveProviderProfileID = "provider-backup"
	config.ProtectedAPIKey = "PROTECTED_PROVIDER_ORIGINAL"
	config.GlobalMemories = []GlobalMemory{{ID: "memory-backup", Title: "backup", Content: "remember this", Enabled: true}}
	config.MCPServers = []MCPServerConfig{{
		ID: "mcp-backup", Name: "Backup MCP", Transport: mcpTransportStdio,
		RequestTimeoutSeconds: 60, Enabled: false, AutoStart: false,
		ProtectedEnvironmentJSON: "PROTECTED_MCP_ENV_ORIGINAL", ProtectedHeadersJSON: "PROTECTED_MCP_HEADER_ORIGINAL",
	}}
	store := &desktopStore{
		configPath: filepath.Join(dataRoot, "desktop-agent.json"), sessionsPath: filepath.Join(dataRoot, "desktop-agent-sessions.json"),
		config: normalizeDesktopConfig(config), sessions: map[string]*ChatSession{},
	}
	store.sessions["session-backup"] = &ChatSession{
		ID: "session-backup", Title: "backup session", CreatedAt: 1, UpdatedAt: 1,
		Messages: []ChatMessage{{ID: "message-backup", Role: "user", Content: "hello backup", CreatedAt: 1}},
	}
	if err := writeJSONAtomic(store.configPath, store.config); err != nil {
		t.Fatal(err)
	}
	if err := store.saveSessionsLocked(); err != nil {
		t.Fatal(err)
	}
	workflowStore, err := newSavedWorkflowStore(filepath.Join(dataRoot, "desktop-agent-workflows.json"))
	if err != nil {
		t.Fatal(err)
	}
	workflowDocument := savedWorkflowStoreBackupSnapshot{Document: savedWorkflowDocument{
		SchemaVersion: savedWorkflowSchemaVersion,
		GitHub:        savedGitHubConfig{APIBaseURL: "https://api.github.com", ProtectedToken: "PROTECTED_GITHUB_ORIGINAL"},
		Workflows: []SavedWorkflowDefinition{{
			ID: "workflow-backup", Name: "GitHub backup", Template: workflowGitHubActionsStatus,
			GitHubRepository: "owner/repository", Nodes: defaultSavedWorkflowNodes(workflowGitHubActionsStatus),
			IntervalMinutes: 60, Enabled: true, CreatedAt: 1, UpdatedAt: 1,
		}},
	}}
	if err := workflowStore.restoreBackupSnapshot(workflowDocument); err != nil {
		t.Fatal(err)
	}
	manager, err := newDesktopBackupManager(store, workflowStore)
	if err != nil {
		t.Fatal(err)
	}
	return store, workflowStore, manager
}

func readDesktopBackupPayloadText(t *testing.T, path string) string {
	t.Helper()
	reader, err := zip.OpenReader(path)
	if err != nil {
		t.Fatal(err)
	}
	defer reader.Close()
	var text strings.Builder
	for _, file := range reader.File {
		input, err := file.Open()
		if err != nil {
			t.Fatal(err)
		}
		data, err := io.ReadAll(input)
		if err != nil {
			input.Close()
			t.Fatal(err)
		}
		input.Close()
		text.Write(data)
	}
	return text.String()
}

func rewriteDesktopBackupZip(t *testing.T, source, target, mutatePath string, mutate func([]byte) []byte) {
	t.Helper()
	reader, err := zip.OpenReader(source)
	if err != nil {
		t.Fatal(err)
	}
	defer reader.Close()
	entries := make([]rawBackupZipEntry, 0, len(reader.File))
	for _, file := range reader.File {
		input, err := file.Open()
		if err != nil {
			t.Fatal(err)
		}
		data, err := io.ReadAll(input)
		if err != nil {
			input.Close()
			t.Fatal(err)
		}
		input.Close()
		if file.Name == mutatePath {
			data = mutate(data)
		}
		entries = append(entries, rawBackupZipEntry{name: file.Name, data: data})
	}
	writeRawDesktopBackupZip(t, target, entries)
}
