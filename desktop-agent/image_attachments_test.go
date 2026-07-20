package main

import (
	"encoding/json"
	"image"
	"image/color"
	"image/png"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestChatImageImportPersistsPrivateResizedAttachment(t *testing.T) {
	t.Setenv("MURONG_DESKTOP_DATA_DIR", t.TempDir())
	store, err := newDesktopStore()
	if err != nil {
		t.Fatal(err)
	}
	source := writeDesktopTestImage(t, 3000, 1200)
	selected, err := importChatImage(store.conversationMediaRoot(), source)
	if err != nil {
		t.Fatal(err)
	}
	attachment := selected.Attachment
	if attachment.MimeType != "image/png" || attachment.Width != 2048 || attachment.Height != 819 || attachment.SizeBytes <= 0 {
		t.Fatalf("unexpected imported attachment: %#v", attachment)
	}
	if !strings.HasPrefix(selected.PreviewDataURL, "data:image/png;base64,") {
		t.Fatal("import did not return an in-memory preview")
	}
	path, err := validateChatImageAttachment(store.conversationMediaRoot(), attachment)
	if err != nil {
		t.Fatal(err)
	}
	if filepath.Dir(path) != store.conversationMediaRoot() {
		t.Fatalf("image escaped the private media directory: %s", path)
	}
	session, err := store.createSession("")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := store.appendMessage(session.ID, ChatMessage{Role: "user", ImageAttachments: []MessageImageAttachment{attachment}}); err != nil {
		t.Fatal(err)
	}
	reloaded, err := newDesktopStore()
	if err != nil {
		t.Fatal(err)
	}
	got := reloaded.getSession(session.ID)
	if got == nil || got.Title != attachment.FileName || len(got.Messages) != 1 || len(got.Messages[0].ImageAttachments) != 1 || got.Messages[0].ImageAttachments[0] != attachment {
		t.Fatalf("image attachment was not persisted: %#v", got)
	}
}

func TestDisabledMultimodalSettingRejectsPickerAndImageMessage(t *testing.T) {
	t.Setenv("MURONG_DESKTOP_DATA_DIR", t.TempDir())
	store, err := newDesktopStore()
	if err != nil {
		t.Fatal(err)
	}
	selected, err := importChatImage(store.conversationMediaRoot(), writeDesktopTestImage(t, 320, 180))
	if err != nil {
		t.Fatal(err)
	}
	session, err := store.createSession("")
	if err != nil {
		t.Fatal(err)
	}
	store.mu.Lock()
	store.config.EnableMultimodalMessages = false
	store.mu.Unlock()
	app := &DesktopAgentApp{store: store}
	if _, err := app.SelectChatImages(); err == nil || !strings.Contains(err.Error(), "多模态消息已在设置中关闭") {
		t.Fatalf("disabled picker was not rejected: %v", err)
	}
	if err := app.SendMessage(SendMessageRequest{SessionID: session.ID, Images: []MessageImageAttachment{selected.Attachment}}); err == nil || !strings.Contains(err.Error(), "多模态消息已在设置中关闭") {
		t.Fatalf("disabled image message was not rejected: %v", err)
	}
}

func TestModelProtocolsEncodeMultimodalUserMessages(t *testing.T) {
	imageAttachment := modelImageAttachment{MimeType: "image/png", Base64Data: "YWJj"}
	messages := []modelMessage{{Role: "user", Content: "inspect", Images: []modelImageAttachment{imageAttachment}}}

	chatJSON, err := json.Marshal(convertChatCompletionMessages(messages))
	if err != nil {
		t.Fatal(err)
	}
	if text := string(chatJSON); !strings.Contains(text, `"type":"image_url"`) || !strings.Contains(text, `data:image/png;base64,YWJj`) {
		t.Fatalf("Chat Completions image was not encoded: %s", text)
	}
	responses, err := convertOpenAIResponsesInput(messages)
	if err != nil {
		t.Fatal(err)
	}
	responsesJSON, _ := json.Marshal(responses)
	if text := string(responsesJSON); !strings.Contains(text, `"type":"input_image"`) || !strings.Contains(text, `data:image/png;base64,YWJj`) {
		t.Fatalf("Responses image was not encoded: %s", text)
	}
	claude, err := buildAnthropicRequest(ProviderProfile{Model: "claude-test"}, messages, nil, 0.7, 8192)
	if err != nil {
		t.Fatal(err)
	}
	claudeJSON, _ := json.Marshal(claude)
	if text := string(claudeJSON); !strings.Contains(text, `"type":"image"`) || !strings.Contains(text, `"media_type":"image/png"`) || !strings.Contains(text, `"data":"YWJj"`) {
		t.Fatalf("Claude image was not encoded: %s", text)
	}
	codexJSON, _ := json.Marshal(buildCodexTurnInputs("inspect", []string{`C:\private\image.png`}))
	if text := string(codexJSON); !strings.Contains(text, `"type":"localImage"`) || !strings.Contains(text, `image.png`) {
		t.Fatalf("Codex local image was not encoded: %s", text)
	}
}

func TestDesktopBackupRestoresConversationMedia(t *testing.T) {
	store, _, manager := newDesktopBackupTestManager(t)
	selected, err := importChatImage(store.conversationMediaRoot(), writeDesktopTestImage(t, 640, 360))
	if err != nil {
		t.Fatal(err)
	}
	if _, err := store.appendMessage("session-backup", ChatMessage{
		Role: "user", Content: "analyse", ImageAttachments: []MessageImageAttachment{selected.Attachment},
	}); err != nil {
		t.Fatal(err)
	}
	backupPath := filepath.Join(t.TempDir(), "with-media.zip")
	created, err := manager.CreateManualBackup(backupPath)
	if err != nil {
		t.Fatal(err)
	}
	if created.Manifest == nil || len(created.Manifest.Entries) != 9 {
		t.Fatalf("conversation media was not added to the manifest: %#v", created.Manifest)
	}
	foundMedia := false
	for _, entry := range created.Manifest.Entries {
		foundMedia = foundMedia || entry.Category == backupCategoryMedia
	}
	if !foundMedia {
		t.Fatal("backup manifest has no conversation media category")
	}

	current := store.backupSnapshot()
	for _, session := range current.Sessions {
		for index := range session.Messages {
			session.Messages[index].ImageAttachments = nil
		}
	}
	if err := store.restoreBackupSnapshot(current); err != nil {
		t.Fatal(err)
	}
	if err := os.RemoveAll(store.conversationMediaRoot()); err != nil {
		t.Fatal(err)
	}
	restored, err := manager.Restore(backupPath)
	if err != nil {
		t.Fatal(err)
	}
	if restored.RestoredEntryCount != 9 {
		t.Fatalf("unexpected restored entry count: %#v", restored)
	}
	got := store.getSession("session-backup")
	var restoredAttachment *MessageImageAttachment
	for _, message := range got.Messages {
		if len(message.ImageAttachments) > 0 {
			value := message.ImageAttachments[0]
			restoredAttachment = &value
		}
	}
	if restoredAttachment == nil {
		t.Fatal("restored session lost its image metadata")
	}
	if _, err := chatImageDataURL(store.conversationMediaRoot(), *restoredAttachment); err != nil {
		t.Fatalf("restored image payload is unavailable: %v", err)
	}
}

func writeDesktopTestImage(t *testing.T, width, height int) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "desktop-test.png")
	file, err := os.Create(path)
	if err != nil {
		t.Fatal(err)
	}
	value := image.NewRGBA(image.Rect(0, 0, width, height))
	for y := 0; y < height; y++ {
		for x := 0; x < width; x++ {
			value.SetRGBA(x, y, color.RGBA{R: uint8(x % 255), G: uint8(y % 255), B: 180, A: 255})
		}
	}
	if err := png.Encode(file, value); err != nil {
		file.Close()
		t.Fatal(err)
	}
	if err := file.Close(); err != nil {
		t.Fatal(err)
	}
	return path
}
