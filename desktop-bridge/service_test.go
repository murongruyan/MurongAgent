package desktopbridge

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

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

func TestRemoteNodeServiceRejectsUnavailableTerminalAndMissingPairCode(t *testing.T) {
	service := &RemoteNodeService{
		configPath: filepath.Join(t.TempDir(), "computer-node.json"),
		config:     nodeConfig{SchemaVersion: nodeConfigSchemaVersion},
		inventory:  terminalInventory{},
		status:     RemoteNodeStatus{Phase: string(nodePhaseStopped)},
	}
	input := RemoteNodeConfig{PhoneURL: "http://127.0.0.1:8765", Workspace: t.TempDir(), TerminalBackends: []string{"missing"}}
	if _, err := service.SaveConfig(input); err == nil {
		t.Fatal("expected unavailable terminal to be rejected")
	}
	input.TerminalBackends = nil
	if _, err := service.Start(input, ""); err == nil {
		t.Fatal("expected unpaired start without a pair code to be rejected")
	}
}

func TestRemoteNodeServiceProtectsCloudRelaySecretAndNeverReturnsCode(t *testing.T) {
	configPath := filepath.Join(t.TempDir(), "computer-node.json")
	service := &RemoteNodeService{
		configPath: configPath,
		config:     nodeConfig{SchemaVersion: nodeConfigSchemaVersion},
		inventory:  terminalInventory{},
		status:     RemoteNodeStatus{Phase: string(nodePhaseStopped)},
	}
	code, roomID, secret, err := generateCloudRelayShareCode()
	if err != nil {
		t.Fatal(err)
	}
	defer clearBytes(secret)

	snapshot, err := service.SaveConfig(RemoteNodeConfig{
		ConnectionMode: CloudRelayConnectionCloud,
		CloudRelayURL:  "ws://127.0.0.1:8787",
		CloudRelayCode: code,
		Workspace:      t.TempDir(),
		ClientName:     "Desktop",
	})
	if err != nil {
		t.Fatal(err)
	}
	if !snapshot.Config.CloudRelayConfigured || snapshot.Config.CloudRelayRoomID != roomID {
		t.Fatalf("cloud relay configuration was not exposed safely: %#v", snapshot.Config)
	}
	if snapshot.Config.CloudRelayCode != "" {
		t.Fatal("cloud relay connection code was returned to the frontend")
	}
	data, err := os.ReadFile(configPath)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(data), code) || strings.Contains(string(data), formatCloudRelayShareCode(roomID, secret)) {
		t.Fatal("raw cloud relay code was persisted")
	}
	loaded, err := loadNodeConfig(configPath)
	if err != nil {
		t.Fatal(err)
	}
	if loaded.ProtectedCloudRelaySecret == "" || loaded.ProtectedCloudRelaySecret == string(secret) {
		t.Fatal("cloud relay secret was not protected at rest")
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
	if config.SchemaVersion != nodeConfigSchemaVersion || config.ConnectionMode != CloudRelayConnectionDirect || config.CloudRelayURL != OfficialCloudRelayURL {
		t.Fatalf("unexpected migrated config: %#v", config)
	}
}
