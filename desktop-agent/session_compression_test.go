package main

import (
	"fmt"
	"strings"
	"testing"
)

func TestSessionCompressionPreservesHistoryAndControlsModelContext(t *testing.T) {
	t.Setenv("MURONG_DESKTOP_DATA_DIR", t.TempDir())
	store, err := newDesktopStore()
	if err != nil {
		t.Fatal(err)
	}
	session, err := store.createSession("长任务")
	if err != nil {
		t.Fatal(err)
	}
	for index := 0; index < 14; index++ {
		role := "user"
		if index%2 == 1 {
			role = "assistant"
		}
		session, err = store.appendMessage(session.ID, ChatMessage{Role: role, Content: fmt.Sprintf("第 %d 条足够明确的历史消息", index+1)})
		if err != nil {
			t.Fatal(err)
		}
	}

	plan, err := store.sessionCompressionPlan(session.ID)
	if err != nil {
		t.Fatal(err)
	}
	if len(plan.CompressedMessages) != 6 || plan.SourceEndMessageID != session.Messages[5].ID {
		t.Fatalf("unexpected compression plan: %#v", plan)
	}
	summary := buildLocalSessionCompressionSummary(plan.Session, plan.CompressedMessages)
	if !strings.Contains(summary, "上下文压缩摘要") || !strings.Contains(summary, "压缩消息数: 6") {
		t.Fatalf("unexpected local summary: %s", summary)
	}
	compressed, err := store.saveSessionCompression(session.ID, plan.SourceEndMessageID, summary, "local")
	if err != nil {
		t.Fatal(err)
	}
	if len(compressed.Messages) != 14 {
		t.Fatalf("compression deleted history: %d messages remain", len(compressed.Messages))
	}
	history, context := sessionHistoryForModel(compressed)
	if len(history) != 8 || !strings.Contains(context, summary) {
		t.Fatalf("unexpected model history: %d messages, context=%q", len(history), context)
	}
	stats := sessionStats(compressed)
	if !stats.CompressionAvailable || !stats.CompressionActive || stats.CompressionMessages != 6 || stats.CompressionMethod != "local" {
		t.Fatalf("unexpected compression stats: %#v", stats)
	}

	disabled, err := store.setSessionCompressionActive(session.ID, false)
	if err != nil {
		t.Fatal(err)
	}
	history, context = sessionHistoryForModel(disabled)
	if len(history) != 14 || context != "" {
		t.Fatalf("disabled compression still changed model history: %d, %q", len(history), context)
	}
}

func TestSessionCompressionRemapsAcrossForkImportAndClearsOnEarlierRollback(t *testing.T) {
	t.Setenv("MURONG_DESKTOP_DATA_DIR", t.TempDir())
	store, err := newDesktopStore()
	if err != nil {
		t.Fatal(err)
	}
	session, err := store.createSession("压缩迁移")
	if err != nil {
		t.Fatal(err)
	}
	for index := 0; index < 14; index++ {
		role := "user"
		if index%2 == 1 {
			role = "assistant"
		}
		session, err = store.appendMessage(session.ID, ChatMessage{Role: role, Content: fmt.Sprintf("迁移测试消息 %d", index)})
		if err != nil {
			t.Fatal(err)
		}
	}
	plan, err := store.sessionCompressionPlan(session.ID)
	if err != nil {
		t.Fatal(err)
	}
	compressed, err := store.saveSessionCompression(session.ID, plan.SourceEndMessageID, buildLocalSessionCompressionSummary(plan.Session, plan.CompressedMessages), "local")
	if err != nil {
		t.Fatal(err)
	}

	fullFork, err := store.forkSession(session.ID, "")
	if err != nil {
		t.Fatal(err)
	}
	if !fullFork.Compression.Active || fullFork.Compression.SourceEndMessageID != fullFork.Messages[5].ID || fullFork.Compression.SourceEndMessageID == compressed.Compression.SourceEndMessageID {
		t.Fatalf("full fork did not remap compression cutoff: %#v", fullFork.Compression)
	}
	earlyFork, err := store.forkSession(session.ID, compressed.Messages[3].ID)
	if err != nil {
		t.Fatal(err)
	}
	if earlyFork.Compression != (SessionCompression{}) {
		t.Fatalf("early fork retained an invalid summary: %#v", earlyFork.Compression)
	}
	imported, err := store.importSession(compressed)
	if err != nil {
		t.Fatal(err)
	}
	if !imported.Compression.Active || imported.Compression.SourceEndMessageID != imported.Messages[5].ID || imported.Compression.SourceEndMessageID == compressed.Compression.SourceEndMessageID {
		t.Fatalf("import did not remap compression cutoff: %#v", imported.Compression)
	}
	rolledBack, err := store.rollbackSession(session.ID, compressed.Messages[3].ID)
	if err != nil {
		t.Fatal(err)
	}
	if rolledBack.Compression != (SessionCompression{}) {
		t.Fatalf("rollback retained a summary whose cutoff was removed: %#v", rolledBack.Compression)
	}
}

func TestSessionCompressionValidationRejectsMissingCutoff(t *testing.T) {
	messages := []ChatMessage{{ID: "message-1", Role: "user", Content: "hello"}}
	compression := SessionCompression{
		Version: 1, Summary: strings.Repeat("摘要", 24), SourceMessageCount: 6,
		SourceEndMessageID: "missing", CreatedAt: 1, Active: true, Method: "local",
	}
	if err := validateSessionCompression(compression, messages); err == nil {
		t.Fatal("expected missing cutoff to fail validation")
	}
}
