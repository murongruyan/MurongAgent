package desktopbridge

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"net/url"
	"runtime"
	"sync/atomic"
	"testing"
)

func TestComputerNodePublishesNativePlatformIdentity(t *testing.T) {
	var workspaceRegistration workspaceRegisterRequest
	var agentRegistration desktopAgentRegisterRequest
	var snapshot DesktopAgentSnapshot
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		writer.Header().Set("Content-Type", "application/json")
		switch request.URL.Path {
		case workspaceRegisterPath:
			if err := json.NewDecoder(request.Body).Decode(&workspaceRegistration); err != nil {
				t.Fatalf("decode workspace registration: %v", err)
			}
			_, _ = writer.Write([]byte(`{"connected":true,"workspaceSessionId":"node-session-0001","heartbeatIntervalMillis":10000}`))
		case desktopAgentRegisterPath:
			if err := json.NewDecoder(request.Body).Decode(&agentRegistration); err != nil {
				t.Fatalf("decode desktop registration: %v", err)
			}
			writer.WriteHeader(http.StatusNoContent)
		case desktopAgentSnapshotPath:
			if err := json.NewDecoder(request.Body).Decode(&snapshot); err != nil {
				t.Fatalf("decode desktop snapshot: %v", err)
			}
			writer.WriteHeader(http.StatusNoContent)
		default:
			http.NotFound(writer, request)
		}
	}))
	defer server.Close()

	base, err := url.Parse(server.URL)
	if err != nil {
		t.Fatal(err)
	}
	node := &computerNode{
		api:       newAPIClient(base),
		config:    nodeConfig{Label: "Project", ShareDesktopTasks: true},
		sessionID: "node-session-0001",
		agentSnapshotProvider: func() DesktopAgentSnapshot {
			return DesktopAgentSnapshot{
				Sessions: []DesktopAgentTaskSummary{{
					ID: "session-0001", Title: "Legacy task", ExecutionOwner: "desktop",
				}},
				ActiveSession: &DesktopAgentTaskDetail{
					ID: "session-0001", Title: "Legacy task", ExecutionOwner: "desktop",
				},
			}
		},
	}
	if _, err := node.register(context.Background()); err != nil {
		t.Fatalf("register workspace: %v", err)
	}
	if !node.registerDesktopAgent(context.Background()) {
		t.Fatal("desktop agent registration failed")
	}

	if workspaceRegistration.Platform != runtime.GOOS || workspaceRegistration.Architecture != runtime.GOARCH {
		t.Fatalf("workspace identity = %s/%s, want %s/%s", workspaceRegistration.Platform, workspaceRegistration.Architecture, runtime.GOOS, runtime.GOARCH)
	}
	if agentRegistration.SourcePlatform != runtime.GOOS || agentRegistration.SourceArchitecture != runtime.GOARCH {
		t.Fatalf("agent identity = %s/%s, want %s/%s", agentRegistration.SourcePlatform, agentRegistration.SourceArchitecture, runtime.GOOS, runtime.GOARCH)
	}
	if agentRegistration.ProtocolVersion != DesktopAgentProtocolVersion {
		t.Fatalf("agent protocol = %d, want %d", agentRegistration.ProtocolVersion, DesktopAgentProtocolVersion)
	}
	if snapshot.SourcePlatform != runtime.GOOS || snapshot.SourceArchitecture != runtime.GOARCH {
		t.Fatalf("snapshot identity = %s/%s, want %s/%s", snapshot.SourcePlatform, snapshot.SourceArchitecture, runtime.GOOS, runtime.GOARCH)
	}
}

func TestComputerNodeFallsBackForStrictLegacyPhone(t *testing.T) {
	var workspaceAttempts atomic.Int32
	var agentAttempts atomic.Int32
	var snapshot DesktopAgentSnapshot
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		writer.Header().Set("Content-Type", "application/json")
		var raw map[string]json.RawMessage
		if err := json.NewDecoder(request.Body).Decode(&raw); err != nil {
			t.Errorf("decode %s: %v", request.URL.Path, err)
			writer.WriteHeader(http.StatusBadRequest)
			return
		}
		switch request.URL.Path {
		case workspaceRegisterPath:
			workspaceAttempts.Add(1)
			if _, exists := raw["platform"]; exists {
				writer.WriteHeader(http.StatusBadRequest)
				_, _ = writer.Write([]byte(`{"code":"invalid_json","error":"unknown field"}`))
				return
			}
			_, _ = writer.Write([]byte(`{"connected":true,"workspaceSessionId":"node-session-0001","heartbeatIntervalMillis":10000}`))
		case desktopAgentRegisterPath:
			agentAttempts.Add(1)
			if _, exists := raw["sourcePlatform"]; exists {
				writer.WriteHeader(http.StatusBadRequest)
				_, _ = writer.Write([]byte(`{"code":"invalid_json","error":"unknown field"}`))
				return
			}
			writer.WriteHeader(http.StatusNoContent)
		case desktopAgentSnapshotPath:
			encoded, err := json.Marshal(raw)
			if err != nil {
				t.Errorf("encode snapshot: %v", err)
				writer.WriteHeader(http.StatusBadRequest)
				return
			}
			if err := json.Unmarshal(encoded, &snapshot); err != nil {
				t.Errorf("decode snapshot: %v", err)
				writer.WriteHeader(http.StatusBadRequest)
				return
			}
			if _, exists := raw["sourcePlatform"]; exists {
				t.Error("legacy snapshot unexpectedly contains sourcePlatform")
			}
			writer.WriteHeader(http.StatusNoContent)
		default:
			http.NotFound(writer, request)
		}
	}))
	defer server.Close()

	base, err := url.Parse(server.URL)
	if err != nil {
		t.Fatal(err)
	}
	node := &computerNode{
		api:       newAPIClient(base),
		config:    nodeConfig{Label: "Project", ShareDesktopTasks: true},
		sessionID: "node-session-0001",
		agentSnapshotProvider: func() DesktopAgentSnapshot {
			return DesktopAgentSnapshot{
				Sessions: []DesktopAgentTaskSummary{{
					ID: "session-0001", Title: "Phone task", PendingQuestion: true, ExecutionOwner: "android", HandoffStartedAt: 1_000,
				}},
				ActiveSession: &DesktopAgentTaskDetail{
					ID: "session-0001", Title: "Phone task", ExecutionOwner: "android", HandoffStartedAt: 1_000,
					PendingAsk:   &DesktopAgentAskRequest{ID: "ask-0001", SessionID: "session-0001", CreatedAt: 1_000},
					WorkflowPlan: &DesktopAgentWorkflowPlan{ID: "plan-0001", Summary: "Plan", Steps: []string{"Step"}, Status: "ready", CreatedAt: 1_000, UpdatedAt: 1_000},
				},
			}
		},
	}
	if _, err := node.register(context.Background()); err != nil {
		t.Fatalf("legacy workspace fallback failed: %v", err)
	}
	if !node.registerDesktopAgent(context.Background()) {
		t.Fatal("legacy desktop fallback failed")
	}

	if workspaceAttempts.Load() != 2 || agentAttempts.Load() != 3 {
		t.Fatalf("fallback attempts workspace=%d agent=%d", workspaceAttempts.Load(), agentAttempts.Load())
	}
	if snapshot.SourcePlatform != "" || snapshot.SourceArchitecture != "" {
		t.Fatalf("legacy snapshot leaked new identity fields: %#v", snapshot)
	}
	if snapshot.Sessions[0].ExecutionOwner != "" || snapshot.ActiveSession.ExecutionOwner != "" {
		t.Fatalf("legacy snapshot leaked execution handoff fields: %#v", snapshot)
	}
	if snapshot.Sessions[0].PendingQuestion || snapshot.ActiveSession.PendingAsk != nil {
		t.Fatalf("legacy snapshot leaked ask_user protocol fields: %#v", snapshot)
	}
	if snapshot.ActiveSession.WorkflowPlan != nil {
		t.Fatalf("legacy snapshot leaked workflow plan fields: %#v", snapshot)
	}
}

func TestComputerNodeNegotiatesProtocolV2WithoutLosingV2Capabilities(t *testing.T) {
	var attempts atomic.Int32
	var snapshot DesktopAgentSnapshot
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		writer.Header().Set("Content-Type", "application/json")
		switch request.URL.Path {
		case desktopAgentRegisterPath:
			var registration desktopAgentRegisterRequest
			if err := json.NewDecoder(request.Body).Decode(&registration); err != nil {
				t.Fatal(err)
			}
			attempts.Add(1)
			if registration.ProtocolVersion > 2 {
				http.Error(writer, "unsupported protocol", http.StatusBadRequest)
				return
			}
			writer.WriteHeader(http.StatusNoContent)
		case desktopAgentSnapshotPath:
			if err := json.NewDecoder(request.Body).Decode(&snapshot); err != nil {
				t.Fatal(err)
			}
			writer.WriteHeader(http.StatusNoContent)
		default:
			http.NotFound(writer, request)
		}
	}))
	defer server.Close()
	base, err := url.Parse(server.URL)
	if err != nil {
		t.Fatal(err)
	}
	node := &computerNode{
		api: newAPIClient(base), config: nodeConfig{ShareDesktopTasks: true}, sessionID: "node-session-0001",
		agentSnapshotProvider: func() DesktopAgentSnapshot {
			return DesktopAgentSnapshot{
				Sessions: []DesktopAgentTaskSummary{{ID: "session-0001", Title: "Task", PendingQuestion: true, ExecutionOwner: "desktop"}},
				ActiveSession: &DesktopAgentTaskDetail{
					ID: "session-0001", Title: "Task", ExecutionOwner: "desktop",
					PendingAsk:   &DesktopAgentAskRequest{ID: "ask-0001", SessionID: "session-0001", CreatedAt: 1_000},
					WorkflowPlan: &DesktopAgentWorkflowPlan{ID: "plan-0001", Summary: "Plan", Steps: []string{"Step"}, Status: "ready", CreatedAt: 1_000, UpdatedAt: 1_000},
				},
			}
		},
	}
	if !node.registerDesktopAgent(context.Background()) {
		t.Fatal("protocol v2 fallback failed")
	}
	if attempts.Load() != 2 || node.agentProtocolVersion != 2 {
		t.Fatalf("unexpected negotiation attempts=%d version=%d", attempts.Load(), node.agentProtocolVersion)
	}
	if snapshot.SourcePlatform != runtime.GOOS || snapshot.ActiveSession.PendingAsk == nil || snapshot.Sessions[0].ExecutionOwner != "desktop" {
		t.Fatalf("v2 capabilities were unnecessarily stripped: %#v", snapshot)
	}
	if snapshot.ActiveSession.WorkflowPlan != nil {
		t.Fatalf("v3 workflow plan leaked into v2 snapshot: %#v", snapshot.ActiveSession.WorkflowPlan)
	}
}
