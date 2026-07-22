package desktopbridge

import (
	"path/filepath"
	"testing"
	"time"
)

func TestDesktopConnectionPolicyPersistsDoNotDisturbAndBlocklist(t *testing.T) {
	path := filepath.Join(t.TempDir(), "connection-policy.json")
	store, err := newDesktopConnectionPolicyStore(path)
	if err != nil {
		t.Fatal(err)
	}
	peer := deterministicDeviceIdentity(t, 37)
	if err := store.SetDoNotDisturb(true); err != nil {
		t.Fatal(err)
	}
	if err := store.Block(RemoteBlockedPeer{
		DeviceID: peer.deviceID, DeviceName: "Test phone", Fingerprint: peer.fingerprint,
		BlockedAt: time.Now().UnixMilli(),
	}); err != nil {
		t.Fatal(err)
	}
	if !store.IsBlocked(peer.deviceID, peer.fingerprint) {
		t.Fatal("blocked peer was not rejected")
	}
	reloaded, err := newDesktopConnectionPolicyStore(path)
	if err != nil {
		t.Fatal(err)
	}
	doNotDisturb, peers := reloaded.Snapshot()
	if !doNotDisturb || len(peers) != 1 || peers[0].DeviceID != peer.deviceID ||
		peers[0].DeviceDisplayID != peer.displayID() || peers[0].DeviceName != "Test phone" {
		t.Fatalf("unexpected persisted policy: dnd=%v peers=%#v", doNotDisturb, peers)
	}
	removed, err := reloaded.Unblock(peer.displayID())
	if err != nil || !removed || reloaded.IsBlocked(peer.deviceID, peer.fingerprint) {
		t.Fatalf("peer was not removed from blocklist: removed=%v err=%v", removed, err)
	}
}

func TestRemoteNodeServiceAppliesDesktopDNDAndBlocklist(t *testing.T) {
	policy, err := newDesktopConnectionPolicyStore(filepath.Join(t.TempDir(), "connection-policy.json"))
	if err != nil {
		t.Fatal(err)
	}
	phone := deterministicDeviceIdentity(t, 41)
	service := &RemoteNodeService{
		configPath:         filepath.Join(t.TempDir(), "computer-node.json"),
		config:             nodeConfig{SchemaVersion: nodeConfigSchemaVersion},
		status:             RemoteNodeStatus{Phase: string(nodePhaseStopped)},
		connectionPolicy:   policy,
		connectionRequests: make(map[string]pendingRemoteConnection),
	}
	message := deviceRelayMessage{
		Version: deviceRelayProtocolVersion, Kind: "connect_request", RequestID: "request-dnd-0001",
		SourceDeviceID: phone.deviceID, SourcePublicKey: phone.publicKey(), DeviceName: "Phone", Platform: "android",
	}
	if err := policy.SetDoNotDisturb(true); err != nil {
		t.Fatal(err)
	}
	service.handleRelayConnectionRequest(deviceRelayIncomingRequest{message: message, fingerprint: phone.fingerprint})
	if len(service.Snapshot().ConnectionRequests) != 0 {
		t.Fatal("DND accepted an unfamiliar passwordless request")
	}
	if err := policy.SetDoNotDisturb(false); err != nil {
		t.Fatal(err)
	}
	service.handleRelayConnectionRequest(deviceRelayIncomingRequest{message: message, fingerprint: phone.fingerprint})
	if len(service.Snapshot().ConnectionRequests) != 1 {
		t.Fatal("ordinary request was not shown for confirmation")
	}
	if _, err := service.BlockConnectionRequest(message.RequestID); err != nil {
		t.Fatal(err)
	}
	snapshot := service.Snapshot()
	if len(snapshot.ConnectionRequests) != 0 || len(snapshot.BlockedPeers) != 1 ||
		snapshot.BlockedPeers[0].DeviceID != phone.deviceID {
		t.Fatalf("unexpected state after blocking: %#v", snapshot)
	}
	message.RequestID = "request-dnd-0002"
	service.handleRelayConnectionRequest(deviceRelayIncomingRequest{message: message, fingerprint: phone.fingerprint})
	if len(service.Snapshot().ConnectionRequests) != 0 {
		t.Fatal("blocked peer was allowed to request again")
	}
}
