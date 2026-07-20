package main

import (
	"encoding/json"
	"path/filepath"
	"strings"
	"testing"
	"time"

	desktopbridge "github.com/murong-agent/desktop-bridge"
)

func TestDesktopV2BackupContainsStrictSecretFreePortableState(t *testing.T) {
	_, _, manager := newDesktopBackupTestManager(t)
	path := filepath.Join(t.TempDir(), "portable-v2.zip")
	result, err := manager.CreateManualBackup(path)
	if err != nil {
		t.Fatal(err)
	}
	if result.Manifest == nil || result.Manifest.FormatVersion != 2 {
		t.Fatalf("backup did not use v2 manifest: %#v", result.Manifest)
	}
	found := false
	for _, entry := range result.Manifest.Entries {
		if entry.Path == desktopBackupPortableStatePath && entry.Category == backupCategoryPortableState {
			found = true
		}
	}
	if !found {
		t.Fatal("v2 backup omitted portable state")
	}
	payload := readDesktopBackupPayloadText(t, path)
	for _, forbidden := range []string{"PROTECTED_PROVIDER_ORIGINAL", "PROTECTED_MCP_ENV_ORIGINAL", "PROTECTED_GITHUB_ORIGINAL", `"apiKey":`, `"codexAuthJson":`} {
		if strings.Contains(payload, forbidden) {
			t.Fatalf("portable backup leaked %q", forbidden)
		}
	}
}

func TestDesktopRestoresAndroidPortableBackupByMergeAndKeepsConflictCopiesStable(t *testing.T) {
	store, _, manager := newDesktopBackupTestManager(t)
	current := store.backupSnapshot()
	current.Config.ProjectPath = t.TempDir()
	current.Config.ProviderProfiles[0].ProtectedAPIKey = "LOCAL_SECRET_KEEP"
	if err := store.restoreBackupSnapshot(current); err != nil {
		t.Fatal(err)
	}
	archive := writeAndroidPortableBackupForDesktop(t, manager, nil)

	first, err := manager.Restore(archive)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(first.Message, "跨系统恢复完成") {
		t.Fatalf("restore was not reported as cross-platform: %#v", first)
	}
	afterFirst := store.backupSnapshot()
	if !sameWorkspacePath(afterFirst.Config.ProjectPath, current.Config.ProjectPath) || afterFirst.Config.ProviderProfiles[0].ProtectedAPIKey != "LOCAL_SECRET_KEEP" {
		t.Fatalf("cross restore replaced local paths or credentials: %#v", afterFirst.Config)
	}
	if len(afterFirst.Sessions) != 2 {
		t.Fatalf("android session was not merged: %#v", afterFirst.Sessions)
	}
	primaryID := portableBackupSessionID("android", "android-session-1")
	mutated := afterFirst
	for _, session := range mutated.Sessions {
		if session.ID == primaryID {
			session.Messages = []ChatMessage{{ID: "local-edit", Role: "user", Content: "手机导入后在电脑继续编辑", CreatedAt: 300}}
			session.UpdatedAt = 300
		}
	}
	if err := store.restoreBackupSnapshot(mutated); err != nil {
		t.Fatal(err)
	}
	if _, err := manager.Restore(archive); err != nil {
		t.Fatal(err)
	}
	if got := len(store.backupSnapshot().Sessions); got != 3 {
		t.Fatalf("cross restore did not preserve a conflict copy, got %d sessions", got)
	}
	if _, err := manager.Restore(archive); err != nil {
		t.Fatal(err)
	}
	if got := len(store.backupSnapshot().Sessions); got != 3 {
		t.Fatalf("repeated restore duplicated the same conflict copy, got %d sessions", got)
	}
}

func TestDesktopRejectsPortableBackupSecretsBeforeSnapshot(t *testing.T) {
	_, _, manager := newDesktopBackupTestManager(t)
	secret := "SHOULD_NOT_BE_ACCEPTED"
	archive := writeAndroidPortableBackupForDesktop(t, manager, &secret)
	if _, err := manager.Restore(archive); err == nil || !strings.Contains(err.Error(), "API Key") {
		t.Fatalf("portable secret was accepted: %v", err)
	}
	if manager.Status().PreRestoreSnapshotCount != 0 {
		t.Fatalf("pre-restore snapshot was created before portable validation: %#v", manager.Status())
	}
}

func writeAndroidPortableBackupForDesktop(t *testing.T, manager *desktopBackupManager, apiKey *string) string {
	t.Helper()
	now := time.Now().UnixMilli()
	provider := desktopbridge.SyncedProviderCredential{
		ProfileID: "android-provider", ProviderID: providerOpenAI, Name: "Android Provider",
		BaseURL: "https://example.test/v1", Model: "android-model", ReasoningEffort: "high",
		APIKey: apiKey,
	}
	state := desktopCrossPlatformBackupState{
		SchemaVersion:  desktopPortableBackupSchemaVersion,
		SourcePlatform: "android",
		GeneratedAt:    now,
		DeviceState: desktopbridge.CredentialSyncBundle{
			SchemaVersion: 4, SourcePlatform: "android", GeneratedAt: now,
			ActiveProviderID: stringPointer(providerOpenAI), ActiveProfileID: stringPointer(provider.ProfileID),
			Providers: []desktopbridge.SyncedProviderCredential{provider},
			AgentSettings: &desktopbridge.SyncedAgentSettings{
				ApprovalMode: approvalAskAll, SystemPrompt: "Android portable prompt", ResponseVerbosity: "balanced",
			},
			Knowledge: &desktopbridge.SyncedKnowledge{
				Rules: []desktopbridge.SyncedRule{}, Memories: []desktopbridge.SyncedMemory{}, Skills: []desktopbridge.SyncedSkill{},
			},
		},
		Sessions: []desktopPortableBackupSession{{
			SourceSessionID: "android-session-1",
			Document: mustPortableDocument(t, CrossPlatformSessionEnvelope{
				Format: crossPlatformSessionFormat, FormatVersion: crossPlatformSessionVersion,
				ExportedAt: now, SourcePlatform: "android",
				Session: CrossPlatformSession{
					Title: "Android 任务", CreatedAt: 100, UpdatedAt: 200,
					ProviderID: providerOpenAI, ModelName: "android-model",
					Messages: []CrossPlatformMessage{{Role: "user", Content: "从手机备份恢复", CreatedAt: 150}},
				},
			}),
		}},
	}
	data, err := json.Marshal(state)
	if err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(t.TempDir(), "android-portable.zip")
	if _, err := writeDesktopBackupArchive(path, backupKindManual, []desktopBackupPayload{{
		Path: desktopBackupPortableStatePath, Category: backupCategoryPortableState, Data: data,
	}}, time.Now()); err != nil {
		t.Fatal(err)
	}
	return path
}

func mustPortableDocument(t *testing.T, value CrossPlatformSessionEnvelope) json.RawMessage {
	t.Helper()
	data, err := json.Marshal(value)
	if err != nil {
		t.Fatal(err)
	}
	return json.RawMessage(data)
}
