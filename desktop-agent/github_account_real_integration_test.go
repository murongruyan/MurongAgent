package main

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	desktopbridge "github.com/murong-agent/desktop-bridge"
)

// TestRealPhoneGitHubSameAccountConnectsWithoutCode is opt-in because it uses
// the current Windows user's protected GitHub token and a real phone. The test
// creates an isolated desktop identity, supplies no pairing code and therefore
// proves that a fresh device is trusted through the shared GitHub account.
func TestRealPhoneGitHubSameAccountConnectsWithoutCode(t *testing.T) {
	phoneURL := strings.TrimSpace(os.Getenv("MURONG_GITHUB_ACCOUNT_INTEGRATION_PHONE_URL"))
	if phoneURL == "" {
		t.Skip("set MURONG_GITHUB_ACCOUNT_INTEGRATION_PHONE_URL to run the real same-account test")
	}
	realLocalAppData := strings.TrimSpace(os.Getenv("LOCALAPPDATA"))
	if realLocalAppData == "" {
		t.Fatal("LOCALAPPDATA is required for the real same-account test")
	}
	workflowStore, err := newSavedWorkflowStore(filepath.Join(realLocalAppData, "Murong", "desktop-agent-workflows.json"))
	if err != nil {
		t.Fatal(err)
	}
	github, err := workflowStore.runtimeGitHub()
	if err != nil {
		t.Fatal(err)
	}
	if strings.TrimSpace(github.Token) == "" {
		t.Fatal("the desktop app has no saved GitHub token to prove the shared account")
	}

	isolatedLocalAppData := t.TempDir()
	t.Setenv("LOCALAPPDATA", isolatedLocalAppData)
	service, err := desktopbridge.NewRemoteNodeService(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	defer service.Close(5 * time.Second)
	workspace := t.TempDir()
	request := desktopbridge.RemoteNodeConfig{
		ConnectionMode: desktopbridge.ConnectionModeDirect,
		PhoneURL:       phoneURL,
		Workspace:      workspace,
		Label:          "GitHub same-account integration",
		ClientName:     "Murong GitHub trust integration",
	}
	if _, err := service.StartWithGitHubToken(request, "", github.Token); err != nil {
		t.Fatal(err)
	}

	connected := waitForRealRemoteNodePhase(t, service, "connected", 75*time.Second)
	if !connected.Config.Paired || !connected.Config.SecureSyncReady || connected.Config.PeerDeviceID == "" {
		t.Fatalf("same-account connection did not persist secure pairing metadata: %#v", connected.Config)
	}
	configPath := filepath.Join(isolatedLocalAppData, "Murong", "computer-node.json")
	data, err := os.ReadFile(configPath)
	if err != nil {
		t.Fatal(err)
	}
	stored := map[string]any{}
	if err := json.Unmarshal(data, &stored); err != nil {
		t.Fatal(err)
	}
	if session, _ := stored["protectedGitHubSession"].(string); strings.TrimSpace(session) == "" {
		t.Fatal("same-account connection did not cache a protected backend session")
	}

	service.Stop()
	waitForRealRemoteNodePhase(t, service, "stopped", 30*time.Second)
	revokeContext, revokeCancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer revokeCancel()
	revoked, err := service.RevokePairing(revokeContext)
	if err != nil {
		t.Fatalf("same-account test connected but could not revoke its phone-side trust record: %v", err)
	}
	if revoked.Config.Paired || revoked.Config.SecureSyncReady {
		t.Fatal("same-account test pairing survived explicit bilateral revocation")
	}
}

func waitForRealRemoteNodePhase(
	t *testing.T,
	service *desktopbridge.RemoteNodeService,
	wanted string,
	timeout time.Duration,
) desktopbridge.RemoteNodeSnapshot {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		snapshot := service.Snapshot()
		if snapshot.Status.Phase == wanted {
			return snapshot
		}
		if snapshot.Status.Phase == "error" {
			t.Fatalf("remote node entered error state while waiting for %s: %s", wanted, snapshot.Status.Message)
		}
		time.Sleep(100 * time.Millisecond)
	}
	snapshot := service.Snapshot()
	t.Fatalf("remote node did not reach %s within %s; current state: %#v", wanted, timeout, snapshot.Status)
	return snapshot
}
