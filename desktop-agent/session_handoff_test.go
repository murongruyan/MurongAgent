package main

import (
	"context"
	"encoding/json"
	"strings"
	"testing"

	desktopbridge "github.com/murong-agent/desktop-bridge"
)

func TestSessionHandoffFreezesPersistsAndMergesOnlyPhoneAppend(t *testing.T) {
	t.Setenv("MURONG_DESKTOP_DATA_DIR", t.TempDir())
	store, err := newDesktopStore()
	if err != nil {
		t.Fatal(err)
	}
	session, err := store.createSession("跨端任务")
	if err != nil {
		t.Fatal(err)
	}
	base, err := store.appendMessage(session.ID, ChatMessage{
		Role: "user", Content: "从电脑开始", CreatedAt: 1_000,
	})
	if err != nil {
		t.Fatal(err)
	}
	pkg, frozen, err := store.beginSessionHandoff(session.ID)
	if err != nil {
		t.Fatal(err)
	}
	if sessionExecutionOwner(frozen) != sessionExecutionOwnerAndroid || pkg.Token == "" || pkg.BaseMessageCount != 1 {
		t.Fatalf("unexpected handoff package: %#v %#v", pkg, frozen.ExecutionHandoff)
	}
	for name, mutation := range map[string]func() error{
		"append": func() error {
			_, err := store.appendMessage(session.ID, ChatMessage{Role: "user", Content: "电脑误写"})
			return err
		},
		"rename":   func() error { _, err := store.renameSession(session.ID, "电脑误改"); return err },
		"rollback": func() error { _, err := store.rollbackSession(session.ID, base.Messages[0].ID); return err },
		"delete":   func() error { return store.deleteSession(session.ID) },
	} {
		t.Run(name+" blocked", func(t *testing.T) {
			if err := mutation(); err == nil || !strings.Contains(err.Error(), "手机接管") {
				t.Fatalf("frozen session mutation was not rejected: %v", err)
			}
		})
	}

	reloaded, err := newDesktopStore()
	if err != nil {
		t.Fatal(err)
	}
	if sessionExecutionOwner(reloaded.getSession(session.ID)) != sessionExecutionOwnerAndroid {
		t.Fatal("handoff authority did not survive desktop restart")
	}
	returnedDocument := androidReturnDocument(t, pkg.PortableSession, CrossPlatformMessage{
		Role: "assistant", Content: "在手机完成", CreatedAt: 2_000,
	})
	if _, err := reloaded.returnSessionHandoff(session.ID, "handoff-"+strings.Repeat("0", 64), returnedDocument); err == nil {
		t.Fatal("wrong handoff token was accepted")
	}
	merged, err := reloaded.returnSessionHandoff(session.ID, pkg.Token, returnedDocument)
	if err != nil {
		t.Fatal(err)
	}
	if sessionExecutionOwner(merged) != sessionExecutionOwnerDesktop || len(merged.Messages) != 2 || merged.Messages[0].Content != "从电脑开始" || merged.Messages[1].Content != "在手机完成" {
		t.Fatalf("phone append was not safely merged: %#v", merged)
	}
	if merged.CodexThreadID != "" || merged.CodexSyncedID != "" {
		t.Fatal("cross-device merge retained stale Codex continuation linkage")
	}
}

func TestSessionHandoffRejectsEditedPrefixAndSupportsAbortOrForceReclaim(t *testing.T) {
	t.Setenv("MURONG_DESKTOP_DATA_DIR", t.TempDir())
	store, err := newDesktopStore()
	if err != nil {
		t.Fatal(err)
	}
	session, _ := store.createSession("冲突任务")
	_, _ = store.appendMessage(session.ID, ChatMessage{Role: "user", Content: "原始历史", CreatedAt: 100})
	pkg, _, err := store.beginSessionHandoff(session.ID)
	if err != nil {
		t.Fatal(err)
	}
	var envelope CrossPlatformSessionEnvelope
	if err := json.Unmarshal([]byte(pkg.PortableSession), &envelope); err != nil {
		t.Fatal(err)
	}
	envelope.SourcePlatform = crossPlatformSourceAndroid
	envelope.Session.Messages[0].Content = "手机改写了旧历史"
	edited, _ := json.Marshal(envelope)
	if _, err := store.returnSessionHandoff(session.ID, pkg.Token, string(edited)); err == nil || !strings.Contains(err.Error(), "接管前历史") {
		t.Fatalf("edited prefix was not rejected: %v", err)
	}
	if _, err := store.abortSessionHandoff(session.ID, pkg.Token); err != nil {
		t.Fatal(err)
	}
	if sessionExecutionOwner(store.getSession(session.ID)) != sessionExecutionOwnerDesktop {
		t.Fatal("abort did not restore desktop authority")
	}
	pkg, _, err = store.beginSessionHandoff(session.ID)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := store.forceReclaimSessionHandoff(session.ID); err != nil {
		t.Fatal(err)
	}
	if _, err := store.returnSessionHandoff(session.ID, pkg.Token, androidReturnDocument(t, pkg.PortableSession)); err == nil {
		t.Fatal("force reclaim did not invalidate the phone token")
	}
}

func TestRemoteHandoffRejectsBusyRuntimeAndNeverLeaksCapabilityInSnapshot(t *testing.T) {
	t.Setenv("MURONG_DESKTOP_DATA_DIR", t.TempDir())
	store, err := newDesktopStore()
	if err != nil {
		t.Fatal(err)
	}
	session, _ := store.createSession("远程接管")
	_, _ = store.appendMessage(session.ID, ChatMessage{Role: "user", Content: "开始", CreatedAt: 100})
	app := &DesktopAgentApp{
		store: store, runs: map[string]context.CancelFunc{session.ID: func() {}},
		approvals: map[string]chan bool{}, pendingApprovals: map[string]ApprovalRequest{},
		activeSubagentJobs: map[string]activeSubagentJob{},
	}
	busy := app.handleDesktopAgentCommand(desktopbridge.DesktopAgentCommand{Operation: "begin_handoff", SessionID: session.ID})
	if busy.Success || busy.ErrorCode != "handoff_conflict" {
		t.Fatalf("busy session was handed off: %#v", busy)
	}
	delete(app.runs, session.ID)
	result := app.handleDesktopAgentCommand(desktopbridge.DesktopAgentCommand{Operation: "begin_handoff", SessionID: session.ID})
	if !result.Success || result.HandoffToken == "" || result.PortableSession == "" {
		t.Fatalf("remote handoff failed: %#v", result)
	}
	handedOff := store.getSession(session.ID)
	if sessionExecutionOwner(handedOff) != sessionExecutionOwnerAndroid {
		t.Fatalf("remote handoff did not freeze the persisted session: %#v", handedOff.ExecutionHandoff)
	}
	snapshot, _ := json.Marshal(app.desktopAgentSnapshot())
	if strings.Contains(string(snapshot), result.HandoffToken) || strings.Contains(string(snapshot), "portableSession") {
		t.Fatalf("snapshot leaked handoff capability: %s", snapshot)
	}
}

func TestRemoteHandoffTransportBudgetsRawAndEscapedDocuments(t *testing.T) {
	if err := validateRemoteHandoffDocument([]byte(strings.Repeat("x", remoteSessionHandoffMaxRawBytes+1))); err == nil {
		t.Fatal("oversized raw handoff document was accepted")
	}
	// JSON encodes NUL as six ASCII bytes, so this remains below the raw limit but exceeds the SSE/result budget.
	if err := validateRemoteHandoffDocument([]byte(strings.Repeat("\x00", 600_000))); err == nil || !strings.Contains(err.Error(), "转义后") {
		t.Fatalf("escaped transport expansion was not rejected: %v", err)
	}
}

func androidReturnDocument(t *testing.T, raw string, appended ...CrossPlatformMessage) string {
	t.Helper()
	var envelope CrossPlatformSessionEnvelope
	if err := json.Unmarshal([]byte(raw), &envelope); err != nil {
		t.Fatal(err)
	}
	envelope.SourcePlatform = crossPlatformSourceAndroid
	envelope.Session.Messages = append(envelope.Session.Messages, appended...)
	if len(appended) > 0 {
		envelope.Session.UpdatedAt = appended[len(appended)-1].CreatedAt
	}
	encoded, err := json.Marshal(envelope)
	if err != nil {
		t.Fatal(err)
	}
	return string(encoded)
}
