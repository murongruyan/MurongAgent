package desktopbridge

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
	"time"
)

func TestDeviceSyncEnvelopeRoundTripAndMetadataTamperDetection(t *testing.T) {
	key := bytes.Repeat([]byte{0x5a}, 32)
	plain := []byte(`{"schemaVersion":1,"sourcePlatform":"windows"}`)
	issuedAt := time.Now().UnixMilli()
	envelope, err := encryptDeviceSync(key, "device-sync-12345678", issuedAt, deviceSyncWindowsToPhone, plain)
	if err != nil {
		t.Fatal(err)
	}
	actual, err := decryptDeviceSync(key, envelope)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(actual, plain) {
		t.Fatalf("device sync plaintext mismatch: %q", actual)
	}

	tampered := envelope
	tampered.Direction = deviceSyncPhoneToWindows
	if _, err := decryptDeviceSync(key, tampered); err == nil {
		t.Fatal("device sync envelope with tampered authenticated metadata was accepted")
	}
}

func TestDeviceSyncVersionThreeKeepsGitHubCredentialInsideEncryptedPayload(t *testing.T) {
	token := "github-token-secret"
	bundle := CredentialSyncBundle{
		SchemaVersion: 3, SourcePlatform: "windows", GeneratedAt: time.Now().UnixMilli(),
		GitHub: &SyncedGitHubCredential{APIBaseURL: "https://api.github.com", Token: &token, ViewerLogin: "murong-user"},
	}
	plain, err := json.Marshal(bundle)
	if err != nil {
		t.Fatal(err)
	}
	var restored CredentialSyncBundle
	if err := strictUnmarshalDeviceSync(plain, &restored); err != nil {
		t.Fatal(err)
	}
	if restored.GitHub == nil || restored.GitHub.Token == nil || *restored.GitHub.Token != token || restored.GitHub.ViewerLogin != "murong-user" {
		t.Fatalf("GitHub credential did not round-trip: %#v", restored.GitHub)
	}
}

func TestStrictUnmarshalDeviceSyncRejectsTrailingJSON(t *testing.T) {
	var value DeviceSyncOptions
	if err := strictUnmarshalDeviceSync([]byte(`{"includeCodexLogin":true}{}`), &value); err == nil {
		t.Fatal("device sync decoder accepted trailing JSON")
	}
}

func TestPostJSONWithTimeoutAllowsTheLargerDeviceSyncEnvelope(t *testing.T) {
	payload := strings.Repeat("x", 3*1024*1024)
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		writer.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(writer).Encode(map[string]string{"payload": payload})
	}))
	defer server.Close()
	target, err := url.Parse(server.URL)
	if err != nil {
		t.Fatal(err)
	}
	api := newAPIClient(target)
	defer api.Close()
	var response struct {
		Payload string `json:"payload"`
	}
	if err := api.postJSONWithTimeoutAndLimit(
		context.Background(),
		"/device-sync",
		map[string]bool{"ok": true},
		&response,
		5*time.Second,
		4*1024*1024,
	); err != nil {
		t.Fatalf("large device-sync response was truncated: %v", err)
	}
	if response.Payload != payload {
		t.Fatalf("large response payload mismatch: got %d bytes", len(response.Payload))
	}
	if err := api.postJSONWithTimeoutAndLimit(
		context.Background(),
		"/device-sync",
		map[string]bool{"ok": true},
		&response,
		5*time.Second,
		2*1024*1024,
	); err == nil || !strings.Contains(err.Error(), "超过") {
		t.Fatalf("oversized response should fail clearly, got %v", err)
	}
}

func TestCredentialSyncSplitsAllSessionsWithoutATotalHistoryLimit(t *testing.T) {
	settings := &SyncedAgentSettings{ApprovalMode: "ask", SystemPrompt: "test", ResponseVerbosity: "MEDIUM"}
	bundle := CredentialSyncBundle{
		SchemaVersion: 6, SourcePlatform: "windows", GeneratedAt: time.Now().UnixMilli(),
		Providers: []SyncedProviderCredential{}, AgentSettings: settings,
	}
	for index := 0; index < 7; index++ {
		document := json.RawMessage(`"` + strings.Repeat("x", 4*1024*1024) + `"`)
		bundle.Sessions = append(bundle.Sessions, SyncedSession{
			SourceSessionID: "session-" + string(rune('a'+index)),
			Document:        document,
		})
	}
	pages, err := splitCredentialSyncBundle(bundle)
	if err != nil {
		t.Fatal(err)
	}
	if len(pages) < 2 {
		t.Fatalf("28 MiB of chat history was not split: %d page(s)", len(pages))
	}
	seen := map[string]bool{}
	for pageIndex, page := range pages {
		plain, marshalErr := json.Marshal(page)
		if marshalErr != nil {
			t.Fatal(marshalErr)
		}
		if len(plain) > deviceSyncMaxPlainBytes {
			t.Fatalf("page %d exceeds the encrypted transport boundary: %d", pageIndex, len(plain))
		}
		if pageIndex > 0 && page.AgentSettings != nil {
			t.Fatal("non-session settings were repeated in a continuation page")
		}
		for _, session := range page.Sessions {
			if seen[session.SourceSessionID] {
				t.Fatalf("session %q was repeated", session.SourceSessionID)
			}
			seen[session.SourceSessionID] = true
		}
	}
	if len(seen) != len(bundle.Sessions) {
		t.Fatalf("session pagination lost records: got %d want %d", len(seen), len(bundle.Sessions))
	}
}

func TestCredentialSyncPullFollowsEverySessionCursor(t *testing.T) {
	key := bytes.Repeat([]byte{0x73}, 32)
	protected, err := protectSecret(key)
	if err != nil {
		t.Fatal(err)
	}
	requests := 0
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		requests++
		var incoming deviceSyncEnvelope
		if decodeErr := json.NewDecoder(request.Body).Decode(&incoming); decodeErr != nil {
			t.Error(decodeErr)
			writer.WriteHeader(http.StatusBadRequest)
			return
		}
		plain, decryptErr := decryptDeviceSync(key, incoming)
		if decryptErr != nil {
			t.Error(decryptErr)
			writer.WriteHeader(http.StatusBadRequest)
			return
		}
		var options DeviceSyncOptions
		if unmarshalErr := json.Unmarshal(plain, &options); unmarshalErr != nil {
			t.Error(unmarshalErr)
			writer.WriteHeader(http.StatusBadRequest)
			return
		}
		bundle := CredentialSyncBundle{SchemaVersion: 6, SourcePlatform: "android", Providers: []SyncedProviderCredential{}}
		if options.SessionCursor == 0 {
			next := 2
			bundle.Sessions = []SyncedSession{{SourceSessionID: "a", Document: json.RawMessage(`{}`)}, {SourceSessionID: "b", Document: json.RawMessage(`{}`)}}
			bundle.SessionNextCursor = &next
		} else if options.SessionCursor == 2 {
			bundle.Sessions = []SyncedSession{{SourceSessionID: "c", Document: json.RawMessage(`{}`)}}
		} else {
			t.Errorf("unexpected session cursor %d", options.SessionCursor)
			writer.WriteHeader(http.StatusBadRequest)
			return
		}
		responsePlain, marshalErr := json.Marshal(bundle)
		if marshalErr != nil {
			t.Error(marshalErr)
			writer.WriteHeader(http.StatusInternalServerError)
			return
		}
		response, encryptErr := encryptDeviceSync(key, incoming.RequestID, time.Now().UnixMilli(), deviceSyncPhoneToWindows, responsePlain)
		if encryptErr != nil {
			t.Error(encryptErr)
			writer.WriteHeader(http.StatusInternalServerError)
			return
		}
		writer.Header().Set("Content-Type", "application/json")
		if encodeErr := json.NewEncoder(writer).Encode(response); encodeErr != nil {
			t.Error(encodeErr)
		}
	}))
	defer server.Close()
	target, err := url.Parse(server.URL)
	if err != nil {
		t.Fatal(err)
	}
	api := newAPIClient(target)
	defer api.Close()
	node := &computerNode{api: api, config: nodeConfig{ProtectedSyncKey: protected}}
	bundle, err := node.pullCredentials(context.Background(), DeviceSyncOptions{IncludeSessions: true})
	if err != nil {
		t.Fatal(err)
	}
	if requests != 2 || len(bundle.Sessions) != 3 || bundle.Sessions[2].SourceSessionID != "c" {
		t.Fatalf("pull pagination stopped early: requests=%d sessions=%#v", requests, bundle.Sessions)
	}
}
