package desktopbridge

import (
	"context"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestRemoteNodeServiceRevokesPhoneTrustBeforeClearingLocalPairing(t *testing.T) {
	protected, err := protectSecret([]byte("pairing-token"))
	if err != nil {
		t.Fatal(err)
	}
	received := false
	server := httptest.NewServer(http.HandlerFunc(func(response http.ResponseWriter, request *http.Request) {
		if request.Method != http.MethodPost || request.URL.Path != unpairPath {
			t.Fatalf("unexpected revoke request: %s %s", request.Method, request.URL.Path)
		}
		if request.Header.Get("Authorization") != "Bearer pairing-token" {
			t.Fatalf("unexpected authorization header: %q", request.Header.Get("Authorization"))
		}
		received = true
		response.WriteHeader(http.StatusNoContent)
	}))
	defer server.Close()
	service := &RemoteNodeService{
		configPath: filepath.Join(t.TempDir(), "computer-node.json"),
		config: nodeConfig{
			SchemaVersion: nodeConfigSchemaVersion, ConnectionMode: ConnectionModeDirect,
			PhoneURL: server.URL, ProtectedToken: protected, ProtectedSyncKey: protected,
		},
		status: RemoteNodeStatus{Phase: string(nodePhaseStopped)},
	}

	snapshot, err := service.RevokePairing(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if !received {
		t.Fatal("phone-side unpair endpoint was not called")
	}
	if snapshot.Config.Paired || snapshot.Config.SecureSyncReady || service.config.ProtectedToken != "" {
		t.Fatalf("local pairing was not cleared after remote revoke: %#v", snapshot.Config)
	}
}

func TestRemoteNodeServiceKeepsLocalPairingWhenRemoteRevokeCannotConnect(t *testing.T) {
	protected, err := protectSecret([]byte("pairing-token"))
	if err != nil {
		t.Fatal(err)
	}
	server := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {}))
	phoneURL := server.URL
	server.Close()
	service := &RemoteNodeService{
		configPath: filepath.Join(t.TempDir(), "computer-node.json"),
		config: nodeConfig{
			SchemaVersion: nodeConfigSchemaVersion, ConnectionMode: ConnectionModeDirect,
			PhoneURL: phoneURL, ProtectedToken: protected, ProtectedSyncKey: protected,
		},
		status: RemoteNodeStatus{Phase: string(nodePhaseStopped)},
	}

	snapshot, err := service.RevokePairing(context.Background())
	if err == nil || !strings.Contains(err.Error(), "未清除本机凭据") {
		t.Fatalf("expected a remote revoke failure that preserves local trust, got %v", err)
	}
	if !snapshot.Config.Paired || !snapshot.Config.SecureSyncReady || service.config.ProtectedToken == "" {
		t.Fatal("local pairing was cleared even though the phone could not be reached")
	}
}

func TestRemoteNodeServiceClearsLocalPairingWhenPhoneAlreadyRevokedToken(t *testing.T) {
	protected, err := protectSecret([]byte("expired-token"))
	if err != nil {
		t.Fatal(err)
	}
	server := httptest.NewServer(http.HandlerFunc(func(response http.ResponseWriter, request *http.Request) {
		response.Header().Set("Content-Type", "application/json")
		response.WriteHeader(http.StatusUnauthorized)
		_, _ = response.Write([]byte(`{"error":"token revoked"}`))
	}))
	defer server.Close()
	service := &RemoteNodeService{
		configPath: filepath.Join(t.TempDir(), "computer-node.json"),
		config: nodeConfig{
			SchemaVersion: nodeConfigSchemaVersion, ConnectionMode: ConnectionModeDirect,
			PhoneURL: server.URL, ProtectedToken: protected, ProtectedSyncKey: protected,
		},
		status: RemoteNodeStatus{Phase: string(nodePhaseStopped)},
	}

	snapshot, err := service.RevokePairing(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if snapshot.Config.Paired || service.config.ProtectedToken != "" {
		t.Fatal("locally cached pairing survived a confirmed remote revocation")
	}
}

func TestRemoteNodeServiceSavesConfigWithoutExposingCredential(t *testing.T) {
	directory := t.TempDir()
	protected, err := protectSecret([]byte("pairing-token"))
	if err != nil {
		t.Fatal(err)
	}
	service := &RemoteNodeService{
		configPath: filepath.Join(t.TempDir(), "computer-node.json"),
		config:     nodeConfig{SchemaVersion: nodeConfigSchemaVersion, PhoneURL: "http://127.0.0.1:8765", ProtectedToken: protected, ProtectedSyncKey: protected},
		inventory:  terminalInventory{Backends: []terminalBackend{{ID: terminalWindowsPowerShell, Label: "Windows PowerShell"}}},
		status:     RemoteNodeStatus{Phase: string(nodePhaseStopped), Message: "stopped"},
	}

	snapshot, err := service.SaveConfig(RemoteNodeConfig{
		PhoneURL: "http://127.0.0.1:8765", Workspace: directory, Label: "Project", ClientName: "Desktop",
		AllowWrite: true, ShareDesktopTasks: true, AllowAgentControl: true, TerminalBackends: []string{terminalWindowsPowerShell},
	})
	if err != nil {
		t.Fatal(err)
	}
	if !snapshot.Config.Paired || !snapshot.Config.SecureSyncReady || snapshot.Config.Workspace != filepath.Clean(directory) || !snapshot.Config.AllowWrite {
		t.Fatalf("unexpected snapshot: %#v", snapshot)
	}
	if !snapshot.Config.ShareDesktopTasks || !snapshot.Config.AllowAgentControl {
		t.Fatalf("desktop task permissions were not persisted: %#v", snapshot.Config)
	}
	if snapshot.Status.Running {
		t.Fatal("saved service unexpectedly running")
	}

	cleared, err := service.ClearPairing()
	if err != nil {
		t.Fatal(err)
	}
	if cleared.Config.Paired {
		t.Fatal("pairing credential was not cleared")
	}
	if cleared.Config.SecureSyncReady {
		t.Fatal("device sync key was not cleared")
	}
}

func TestRemoteNodeServiceRejectsUnavailableTerminalAndUsesConnectionRequestWithoutPairCode(t *testing.T) {
	service := &RemoteNodeService{
		configPath: filepath.Join(t.TempDir(), "computer-node.json"),
		config:     nodeConfig{SchemaVersion: nodeConfigSchemaVersion},
		inventory:  terminalInventory{},
		status:     RemoteNodeStatus{Phase: string(nodePhaseStopped)},
	}
	input := RemoteNodeConfig{PhoneURL: "http://127.0.0.1:1", Workspace: t.TempDir(), TerminalBackends: []string{"missing"}}
	if _, err := service.SaveConfig(input); err == nil {
		t.Fatal("expected unavailable terminal to be rejected")
	}
	input.TerminalBackends = nil
	if _, err := service.Start(input, ""); err != nil {
		t.Fatalf("connection-request start should not require a pair code: %v", err)
	}
	// Terminal inventory discovery can take several seconds on low-end Windows
	// hosts with multiple WSL distributions even though the refused HTTP
	// connection itself is immediate.
	deadline := time.Now().Add(20 * time.Second)
	for time.Now().Before(deadline) {
		snapshot := service.Snapshot()
		if !snapshot.Status.Running {
			if snapshot.Status.Phase != string(nodePhaseError) || !strings.Contains(snapshot.Status.Message, "连接申请失败") {
				t.Fatalf("unexpected connection request failure: %#v", snapshot.Status)
			}
			return
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatal("connection request did not finish after the unavailable phone refused the connection")
}

func TestRemoteNodeServiceValidatesPublicDeviceIDModeAndPreservesMatchingTrust(t *testing.T) {
	protected, err := protectSecret([]byte("saved-token"))
	if err != nil {
		t.Fatal(err)
	}
	peer, err := newEphemeralDeviceIdentity()
	if err != nil {
		t.Fatal(err)
	}
	service := &RemoteNodeService{
		configPath: filepath.Join(t.TempDir(), "computer-node.json"),
		config: nodeConfig{
			SchemaVersion: nodeConfigSchemaVersion, ConnectionMode: ConnectionModeDeviceID,
			PairedDeviceID: peer.deviceID, PairedDeviceFingerprint: peer.fingerprint,
			ProtectedToken: protected,
		},
		identity:  peer,
		inventory: terminalInventory{},
		status:    RemoteNodeStatus{Phase: string(nodePhaseStopped)},
	}
	input := RemoteNodeConfig{
		ConnectionMode: ConnectionModeDeviceID,
		PeerDeviceID:   formatDeviceID(peer.deviceID), PeerFingerprint: peer.fingerprint,
		Workspace: t.TempDir(),
	}
	snapshot, err := service.SaveConfig(input)
	if err != nil {
		t.Fatal(err)
	}
	if snapshot.Config.ConnectionMode != ConnectionModeDeviceID || !snapshot.Config.Paired ||
		snapshot.Config.PeerDeviceID != peer.deviceID || snapshot.Config.PeerDeviceDisplayID != formatDeviceID(peer.deviceID) {
		t.Fatalf("unexpected public device ID configuration: %#v", snapshot.Config)
	}

	input.PeerDeviceID = ""
	if _, err := service.SaveConfig(input); err == nil || !strings.Contains(err.Error(), "16 位本机 ID") {
		t.Fatalf("missing public device ID was not rejected: %v", err)
	}
}

func TestLoadNodeConfigMigratesVersionOneToDirectMode(t *testing.T) {
	path := filepath.Join(t.TempDir(), "computer-node.json")
	if err := os.WriteFile(path, []byte(`{"schemaVersion":1,"phoneUrl":"http://127.0.0.1:8765"}`), 0o600); err != nil {
		t.Fatal(err)
	}
	config, err := loadNodeConfig(path)
	if err != nil {
		t.Fatal(err)
	}
	if config.SchemaVersion != nodeConfigSchemaVersion || config.ConnectionMode != ConnectionModeDirect {
		t.Fatalf("unexpected migrated config: %#v", config)
	}
}

func TestLoadNodeConfigMigratesVersionFiveAndKeepsProtectedGitHubSession(t *testing.T) {
	path := filepath.Join(t.TempDir(), "computer-node.json")
	if err := os.WriteFile(path, []byte(`{"schemaVersion":5,"protectedGitHubSession":"protected-session"}`), 0o600); err != nil {
		t.Fatal(err)
	}
	config, err := loadNodeConfig(path)
	if err != nil {
		t.Fatal(err)
	}
	if config.SchemaVersion != nodeConfigSchemaVersion || config.ProtectedGitHubSession != "protected-session" {
		t.Fatalf("unexpected migrated config: %#v", config)
	}
}
