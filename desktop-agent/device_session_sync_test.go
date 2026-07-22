package main

import (
	"encoding/json"
	"fmt"
	"strings"
	"testing"
	"time"

	desktopbridge "github.com/murong-agent/desktop-bridge"
)

func TestDeviceSessionSyncImportsSkipsAndPreservesConflicts(t *testing.T) {
	t.Setenv("MURONG_DESKTOP_DATA_DIR", t.TempDir())
	store, err := newDesktopStore()
	if err != nil {
		t.Fatal(err)
	}
	record := androidSyncedSessionRecord(t, "phone-session-1", "手机任务", "第一次同步")

	first, err := store.mergeSyncedSessions("android", []desktopbridge.SyncedSession{record})
	if err != nil {
		t.Fatal(err)
	}
	if first.Imported != 1 || first.Conflicts != 0 || first.Skipped != 0 {
		t.Fatalf("unexpected first merge result: %#v", first)
	}
	primaryID := syncedSessionID("android", record.SourceSessionID)
	if imported := store.sessions[primaryID]; imported == nil || imported.Title != "手机任务" || len(imported.Messages) != 1 || imported.Messages[0].Content != "第一次同步" {
		t.Fatalf("phone session was not imported: %#v", imported)
	}

	repeated, err := store.mergeSyncedSessions("android", []desktopbridge.SyncedSession{record})
	if err != nil {
		t.Fatal(err)
	}
	if repeated.Imported != 0 || repeated.Conflicts != 0 || repeated.Skipped != 1 || len(store.sessions) != 1 {
		t.Fatalf("identical session was not skipped: result=%#v sessions=%d", repeated, len(store.sessions))
	}

	changed := androidSyncedSessionRecord(t, record.SourceSessionID, "手机任务", "手机后来继续聊天")
	conflicted, err := store.mergeSyncedSessions("android", []desktopbridge.SyncedSession{changed})
	if err != nil {
		t.Fatal(err)
	}
	if conflicted.Imported != 1 || conflicted.Conflicts != 1 || conflicted.Skipped != 0 || len(store.sessions) != 2 {
		t.Fatalf("changed phone session did not create a conflict copy: result=%#v sessions=%d", conflicted, len(store.sessions))
	}
	if store.sessions[primaryID].Messages[0].Content != "第一次同步" {
		t.Fatal("conflict merge overwrote the original imported conversation")
	}
	var conflict *ChatSession
	for id, session := range store.sessions {
		if id != primaryID {
			conflict = session
		}
	}
	if conflict == nil || !strings.Contains(conflict.ID, primaryID+"-") || conflict.Messages[0].Content != "手机后来继续聊天" {
		t.Fatalf("conflict copy is missing or malformed: %#v", conflict)
	}

	repeatedConflict, err := store.mergeSyncedSessions("android", []desktopbridge.SyncedSession{changed})
	if err != nil {
		t.Fatal(err)
	}
	if repeatedConflict.Imported != 0 || repeatedConflict.Conflicts != 0 || repeatedConflict.Skipped != 1 || len(store.sessions) != 2 {
		t.Fatalf("identical conflict copy was not skipped: result=%#v sessions=%d", repeatedConflict, len(store.sessions))
	}
}

func TestDeviceSessionSyncRejectsSourceMismatchBeforeChangingStore(t *testing.T) {
	t.Setenv("MURONG_DESKTOP_DATA_DIR", t.TempDir())
	store, err := newDesktopStore()
	if err != nil {
		t.Fatal(err)
	}
	record := androidSyncedSessionRecord(t, "phone-session-1", "手机任务", "来源不匹配")
	if _, err := store.mergeSyncedSessions("windows", []desktopbridge.SyncedSession{record}); err == nil {
		t.Fatal("session source mismatch was accepted")
	}
	if len(store.sessions) != 0 {
		t.Fatalf("invalid sync changed the desktop session store: %d", len(store.sessions))
	}
}

func TestDeviceSessionSyncNormalizesLegacyTitleWithoutCreatingRetryConflict(t *testing.T) {
	t.Setenv("MURONG_DESKTOP_DATA_DIR", t.TempDir())
	store, err := newDesktopStore()
	if err != nil {
		t.Fatal(err)
	}
	record := androidSyncedSessionRecord(t, "phone-session-spaced-title", "  手机任务  ", "标题需要规范化")
	first, err := store.mergeSyncedSessions("android", []desktopbridge.SyncedSession{record})
	if err != nil {
		t.Fatal(err)
	}
	second, err := store.mergeSyncedSessions("android", []desktopbridge.SyncedSession{record})
	if err != nil {
		t.Fatal(err)
	}
	primaryID := syncedSessionID("android", record.SourceSessionID)
	if first.Imported != 1 || second.Skipped != 1 || second.Imported != 0 || second.Conflicts != 0 || len(store.sessions) != 1 {
		t.Fatalf("normalized retry created a conflict: first=%#v second=%#v sessions=%d", first, second, len(store.sessions))
	}
	if store.sessions[primaryID] == nil || store.sessions[primaryID].Title != "手机任务" {
		t.Fatalf("legacy title was not normalized: %#v", store.sessions[primaryID])
	}
}

func TestDeviceSessionSyncDoesNotRejectTotalHistoryOverTwentyFourMiB(t *testing.T) {
	records := make([]desktopbridge.SyncedSession, 0, 25)
	content := strings.Repeat("x", 1024*1024)
	for index := 0; index < 25; index++ {
		records = append(records, androidSyncedSessionRecord(t, fmt.Sprintf("phone-session-%02d", index), "历史任务", content))
	}
	if err := validateSyncedSessions("android", records); err != nil {
		t.Fatalf("total chat history should be paged instead of rejected: %v", err)
	}
}

func TestDeviceSessionSyncExportsDesktopSessions(t *testing.T) {
	t.Setenv("MURONG_DESKTOP_DATA_DIR", t.TempDir())
	projectPath := t.TempDir()
	store, err := newDesktopStore()
	if err != nil {
		t.Fatal(err)
	}
	now := time.Now().UnixMilli()
	store.mu.Lock()
	store.sessions["desktop-session-1"] = &ChatSession{
		ID: "desktop-session-1", Title: "电脑任务", CreatedAt: now - 1, UpdatedAt: now,
		ProjectPath: projectPath, CodexThreadID: "private-thread",
		Messages: []ChatMessage{{ID: "message-1", Role: "user", Content: "同步到手机", CreatedAt: now}},
	}
	err = store.saveSessionsLocked()
	store.mu.Unlock()
	if err != nil {
		t.Fatal(err)
	}

	records, err := store.exportSyncedSessions()
	if err != nil {
		t.Fatal(err)
	}
	if len(records) != 1 || records[0].SourceSessionID != "desktop-session-1" {
		t.Fatalf("unexpected exported session records: %#v", records)
	}
	if records[0].OriginPlatform != desktopSourcePlatform() || records[0].OriginSessionID != "desktop-session-1" {
		t.Fatalf("desktop session origin was not exported: %#v", records[0])
	}
	var envelope CrossPlatformSessionEnvelope
	if err := json.Unmarshal(records[0].Document, &envelope); err != nil {
		t.Fatal(err)
	}
	if envelope.SourcePlatform != desktopSourcePlatform() || envelope.Session.Title != "电脑任务" || len(envelope.Session.Messages) != 1 {
		t.Fatalf("unexpected portable session: %#v", envelope)
	}
	if strings.Contains(string(records[0].Document), `"projectPath"`) || strings.Contains(string(records[0].Document), "private-thread") {
		t.Fatal("desktop-only project or Codex state leaked into the sync document")
	}
}

func TestDeviceSessionSyncDoesNotEchoOriginalSessionBackAsDuplicate(t *testing.T) {
	t.Setenv("MURONG_DESKTOP_DATA_DIR", t.TempDir())
	store, err := newDesktopStore()
	if err != nil {
		t.Fatal(err)
	}
	now := time.Now().UnixMilli()
	original := &ChatSession{
		ID: "desktop-origin", Title: "原始电脑任务", CreatedAt: now - 1, UpdatedAt: now,
		Messages: []ChatMessage{{ID: "message-1", Role: "user", Content: "相同内容", CreatedAt: now}},
	}
	store.mu.Lock()
	store.sessions[original.ID] = original
	err = store.saveSessionsLocked()
	store.mu.Unlock()
	if err != nil {
		t.Fatal(err)
	}
	document, err := encodeCrossPlatformSession(original)
	if err != nil {
		t.Fatal(err)
	}
	var envelope CrossPlatformSessionEnvelope
	if err := json.Unmarshal(document, &envelope); err != nil {
		t.Fatal(err)
	}
	envelope.SourcePlatform = "android"
	document, err = json.Marshal(envelope)
	if err != nil {
		t.Fatal(err)
	}
	echo := desktopbridge.SyncedSession{
		SourceSessionID: "portable-windows-copy", OriginPlatform: desktopSourcePlatform(),
		OriginSessionID: original.ID, Document: document,
	}

	result, err := store.mergeSyncedSessions("android", []desktopbridge.SyncedSession{echo})
	if err != nil {
		t.Fatal(err)
	}
	if result.Imported != 0 || result.Skipped != 1 || len(store.sessions) != 1 {
		t.Fatalf("round-trip echo created a duplicate: result=%#v sessions=%d", result, len(store.sessions))
	}
}

func androidSyncedSessionRecord(t *testing.T, sourceID, title, content string) desktopbridge.SyncedSession {
	t.Helper()
	now := time.Now().UnixMilli()
	document, err := json.Marshal(CrossPlatformSessionEnvelope{
		Format: crossPlatformSessionFormat, FormatVersion: crossPlatformSessionVersion,
		ExportedAt: now, SourcePlatform: "android",
		Session: CrossPlatformSession{
			Title: title, CreatedAt: now - 1, UpdatedAt: now,
			ProviderID: providerOpenAI, ModelName: "gpt-test",
			Messages: []CrossPlatformMessage{{Role: "user", Content: content, CreatedAt: now}},
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	return desktopbridge.SyncedSession{SourceSessionID: sourceID, Document: document}
}
