package main

import (
	"context"
	"encoding/json"
	"strings"
	"testing"
	"time"

	desktopbridge "github.com/murong-agent/desktop-bridge"
)

func TestDesktopAgentSnapshotExcludesSecretsPathsContextAndRawApprovalArguments(t *testing.T) {
	t.Setenv("MURONG_DESKTOP_DATA_DIR", t.TempDir())
	store, err := newDesktopStore()
	if err != nil {
		t.Fatal(err)
	}
	session, err := store.createSession("Remote task")
	if err != nil {
		t.Fatal(err)
	}
	_, err = store.appendMessage(session.ID, ChatMessage{
		Role: "user", Content: "inspect the project",
		Context: []ComposerContextItem{{Kind: "file", Path: `C:\secret-project\private.txt`, Label: "private.txt"}},
	})
	if err != nil {
		t.Fatal(err)
	}
	approval := ApprovalRequest{
		ID: "approval-request-0001", SessionID: session.ID, ToolName: "run_terminal",
		Summary: "Run tests", Detail: "safe summary", Arguments: `{"token":"raw-secret"}`, Risk: "high",
	}
	app := &DesktopAgentApp{
		store: store, runs: map[string]context.CancelFunc{}, approvals: map[string]chan bool{},
		pendingApprovals: map[string]ApprovalRequest{approval.ID: approval},
	}
	snapshot := app.desktopAgentSnapshot()
	encoded, err := json.Marshal(snapshot)
	if err != nil {
		t.Fatal(err)
	}
	text := string(encoded)
	for _, forbidden := range []string{"secret-project", "private.txt", "raw-secret", "arguments"} {
		if strings.Contains(text, forbidden) {
			t.Fatalf("desktop snapshot leaked %q: %s", forbidden, text)
		}
	}
	if !strings.Contains(text, "Run tests") || snapshot.ActiveSession == nil || snapshot.ActiveSession.PendingApproval == nil {
		t.Fatalf("sanitized approval was not included: %#v", snapshot.ActiveSession)
	}
}

func TestDesktopAgentSnapshotAndCommandExposeOnlyStructuredAskUserData(t *testing.T) {
	t.Setenv("MURONG_DESKTOP_DATA_DIR", t.TempDir())
	store, err := newDesktopStore()
	if err != nil {
		t.Fatal(err)
	}
	session, err := store.createSession("Question task")
	if err != nil {
		t.Fatal(err)
	}
	request := AskRequest{
		ID: "ask-request-0001", SessionID: session.ID, CreatedAt: time.Now().UnixMilli(),
		Questions: []AskQuestion{{
			ID: "q1", Header: "范围", Question: "先处理哪个端？",
			Options: []AskOption{{Label: "桌面", Description: "继续桌面端"}, {Label: "手机"}},
		}},
	}
	resultChannel := make(chan askResult, 1)
	app := &DesktopAgentApp{
		store: store, runs: map[string]context.CancelFunc{}, approvals: map[string]chan bool{},
		pendingApprovals: map[string]ApprovalRequest{}, asks: map[string]chan askResult{request.ID: resultChannel},
		pendingAsks: map[string]AskRequest{request.ID: request},
	}
	snapshot := app.desktopAgentSnapshot()
	if len(snapshot.Sessions) != 1 || !snapshot.Sessions[0].PendingQuestion || snapshot.ActiveSession == nil || snapshot.ActiveSession.PendingAsk == nil {
		t.Fatalf("pending ask_user was not mirrored: %#v", snapshot)
	}
	encoded, _ := json.Marshal(snapshot)
	if strings.Contains(string(encoded), "arguments") || strings.Contains(string(encoded), "toolCall") {
		t.Fatalf("ask_user mirror leaked internal tool payload: %s", encoded)
	}
	result := app.handleDesktopAgentCommand(desktopbridge.DesktopAgentCommand{
		Operation: "ask", SessionID: session.ID, AskID: request.ID,
		AskAnswers: []desktopbridge.DesktopAgentAskAnswer{{QuestionID: "q1", SelectedOptions: []string{"桌面"}}},
	})
	if !result.Success || result.Session == nil || result.Session.PendingAsk != nil {
		t.Fatalf("remote ask_user command failed: %#v", result)
	}
	select {
	case answer := <-resultChannel:
		if !strings.Contains(answer.Text, "范围：桌面") {
			t.Fatalf("unexpected remote answer: %s", answer.Text)
		}
	default:
		t.Fatal("remote answer did not resume ask_user")
	}
}

func TestDesktopAgentSnapshotStaysInsideTransportEnvelopeAndKeepsNewestMessages(t *testing.T) {
	t.Setenv("MURONG_DESKTOP_DATA_DIR", t.TempDir())
	store, err := newDesktopStore()
	if err != nil {
		t.Fatal(err)
	}
	session, err := store.createSession("Large remote task")
	if err != nil {
		t.Fatal(err)
	}
	messages := make([]ChatMessage, 200)
	for index := range messages {
		content := strings.Repeat("界", 20_000)
		if index%2 == 0 {
			content = strings.Repeat("\x00\n", 10_000)
		}
		messages[index] = ChatMessage{
			ID: newID("message"), Role: "assistant", Content: content, CreatedAt: int64(index + 1),
		}
	}
	messages[len(messages)-1].Content = "newest-message"
	store.mu.Lock()
	store.sessions[session.ID].Messages = messages
	store.sessions[session.ID].UpdatedAt = 500
	store.mu.Unlock()
	app := &DesktopAgentApp{
		store: store, runs: map[string]context.CancelFunc{}, approvals: map[string]chan bool{},
		pendingApprovals: map[string]ApprovalRequest{},
	}

	encoded, err := json.Marshal(app.desktopAgentSnapshot())
	if err != nil {
		t.Fatal(err)
	}
	if len(encoded) >= 4*1024*1024 {
		t.Fatalf("desktop snapshot exceeded the 4 MiB transport envelope: %d", len(encoded))
	}
	if !strings.Contains(string(encoded), "newest-message") {
		t.Fatal("desktop snapshot discarded the newest message")
	}
	if !json.Valid(encoded) {
		t.Fatal("byte truncation produced invalid UTF-8 JSON")
	}
}

func TestDesktopAgentSnapshotSharesImageNamesWithoutCachePathsOrBytes(t *testing.T) {
	t.Setenv("MURONG_DESKTOP_DATA_DIR", t.TempDir())
	store, err := newDesktopStore()
	if err != nil {
		t.Fatal(err)
	}
	selected, err := importChatImage(store.conversationMediaRoot(), writeDesktopTestImage(t, 320, 180))
	if err != nil {
		t.Fatal(err)
	}
	session, err := store.createSession("image task")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := store.appendMessage(session.ID, ChatMessage{Role: "user", Content: "inspect", ImageAttachments: []MessageImageAttachment{selected.Attachment}}); err != nil {
		t.Fatal(err)
	}
	app := &DesktopAgentApp{store: store, runs: map[string]context.CancelFunc{}, approvals: map[string]chan bool{}, pendingApprovals: map[string]ApprovalRequest{}}
	snapshot := app.desktopAgentSnapshot()
	message := snapshot.ActiveSession.Messages[0]
	if len(message.AttachmentNames) != 1 || message.AttachmentNames[0] != selected.Attachment.FileName {
		t.Fatalf("remote snapshot lost attachment names: %#v", message)
	}
	encoded, _ := json.Marshal(snapshot)
	if strings.Contains(string(encoded), selected.Attachment.CacheFile) || strings.Contains(string(encoded), selected.PreviewDataURL) {
		t.Fatalf("remote snapshot leaked image cache linkage or bytes: %s", encoded)
	}
}

func TestDesktopAgentSnapshotKeepsPhoneOnLatestExplicitSelectionAndPublishesOtherUpdates(t *testing.T) {
	t.Setenv("MURONG_DESKTOP_DATA_DIR", t.TempDir())
	store, err := newDesktopStore()
	if err != nil {
		t.Fatal(err)
	}
	first, err := store.createSession("First task")
	if err != nil {
		t.Fatal(err)
	}
	second, err := store.createSession("Second task")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := store.appendMessage(second.ID, ChatMessage{Role: "assistant", Content: "new desktop result"}); err != nil {
		t.Fatal(err)
	}
	app := &DesktopAgentApp{
		store: store, runs: map[string]context.CancelFunc{}, approvals: map[string]chan bool{},
		pendingApprovals: map[string]ApprovalRequest{},
	}

	if _, err := app.GetSession(first.ID); err != nil {
		t.Fatal(err)
	}
	snapshot := app.desktopAgentSnapshot()
	if snapshot.ActiveSession == nil || snapshot.ActiveSession.ID != first.ID {
		t.Fatalf("explicit desktop selection was not mirrored: %#v", snapshot.ActiveSession)
	}
	if len(snapshot.SessionUpdates) != 1 || snapshot.SessionUpdates[0].ID != second.ID {
		t.Fatalf("changed non-active session was not published: %#v", snapshot.SessionUpdates)
	}
	if snapshot.SessionUpdates[0].MessageCount != 1 || len(snapshot.SessionUpdates[0].Messages) != 1 {
		t.Fatalf("session update lost its authoritative message count: %#v", snapshot.SessionUpdates[0])
	}
}
