package desktopbridge

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"net/url"
	"testing"
	"time"
)

func TestConsumeEventsDispatchesDesktopAgentCommand(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		if request.URL.Path != eventsPath || request.Header.Get("Authorization") != "Bearer token" {
			http.Error(writer, "unauthorized", http.StatusUnauthorized)
			return
		}
		writer.Header().Set("Content-Type", "text/event-stream")
		_, _ = fmt.Fprint(writer, "event: desktop_agent_command\ndata: {\"nodeSessionId\":\"node-session-0001\",\"requestId\":\"command-request-0001\",\"operation\":\"refresh\",\"expiresAt\":9999999999999}\n\n")
	}))
	defer server.Close()
	base, err := url.Parse(server.URL)
	if err != nil {
		t.Fatal(err)
	}
	api := newAPIClient(base)
	api.token = "token"
	received := make(chan DesktopAgentCommand, 1)
	err = api.consumeEvents(context.Background(), func(workspaceRPCRequest) {}, func(command DesktopAgentCommand) {
		received <- command
	})
	if err == nil {
		t.Fatal("closed SSE stream should return EOF")
	}
	select {
	case command := <-received:
		if command.Operation != "refresh" || command.RequestID != "command-request-0001" {
			t.Fatalf("unexpected command: %#v", command)
		}
	case <-time.After(time.Second):
		t.Fatal("desktop task command was not dispatched")
	}
}

func TestDesktopAgentHandoffEnvelopeRoundTripWithoutEnteringSnapshot(t *testing.T) {
	key := make([]byte, 32)
	for index := range key {
		key[index] = byte(index + 1)
	}
	token := "handoff-" + "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
	portable := `{"format":"murong-portable-session"}`
	payload, err := json.Marshal(desktopAgentHandoffPayload{HandoffToken: token, PortableSession: portable})
	if err != nil {
		t.Fatal(err)
	}
	envelope, err := encryptDeviceSync(key, "command-request-0002", time.Now().UnixMilli(), deviceSyncHandoffToDesktop, payload)
	if err != nil {
		t.Fatal(err)
	}
	command := DesktopAgentCommand{
		NodeSessionID: "node-session-0001", RequestID: "command-request-0002",
		Operation: "return_handoff", SessionID: "session-0001",
		HandoffEnvelope: &envelope, ExpiresAt: 9_999_999_999_999,
	}
	encoded, err := json.Marshal(command)
	if err != nil {
		t.Fatal(err)
	}
	if containsJSONText(encoded, token) || containsJSONText(encoded, portable) {
		t.Fatalf("handoff command leaked plaintext capability: %s", encoded)
	}
	var decoded DesktopAgentCommand
	if err := json.Unmarshal(encoded, &decoded); err != nil {
		t.Fatal(err)
	}
	plain, err := decryptDeviceSync(key, *decoded.HandoffEnvelope)
	if err != nil {
		t.Fatal(err)
	}
	var decodedPayload desktopAgentHandoffPayload
	if err := strictUnmarshalDeviceSync(plain, &decodedPayload); err != nil {
		t.Fatal(err)
	}
	if decodedPayload.HandoffToken != token || decodedPayload.PortableSession != portable {
		t.Fatalf("handoff encrypted fields were lost: %#v", decodedPayload)
	}
	result := DesktopAgentCommandResult{Success: true, HandoffToken: token, PortableSession: portable}
	begin := DesktopAgentCommand{RequestID: "command-request-0003", Operation: "begin_handoff"}
	if err := protectDesktopHandoffResult(begin, &result, key); err != nil {
		t.Fatal(err)
	}
	resultJSON, err := json.Marshal(result)
	if err != nil {
		t.Fatal(err)
	}
	if result.HandoffEnvelope == nil || containsJSONText(resultJSON, token) || containsJSONText(resultJSON, portable) {
		t.Fatalf("handoff result leaked plaintext capability: %s", resultJSON)
	}
	snapshot, err := json.Marshal(DesktopAgentSnapshot{
		Sessions: []DesktopAgentTaskSummary{{
			ID: "session-0001", Title: "Phone task", ExecutionOwner: "android", HandoffStartedAt: 1_000,
		}},
	})
	if err != nil {
		t.Fatal(err)
	}
	if string(snapshot) == "" || containsJSONText(snapshot, token) || containsJSONText(snapshot, "portableSession") {
		t.Fatalf("snapshot leaked handoff capability: %s", snapshot)
	}
}

func containsJSONText(data []byte, value string) bool {
	for index := 0; index+len(value) <= len(data); index++ {
		if string(data[index:index+len(value)]) == value {
			return true
		}
	}
	return false
}
