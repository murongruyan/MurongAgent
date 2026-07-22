package main

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"reflect"
	"sort"
	"testing"
	"time"

	desktopbridge "github.com/murong-agent/desktop-bridge"
)

// This test is intentionally opt-in because it connects to a real paired phone.
// It clones the node config and imports into a temporary Desktop data directory,
// so a verification run cannot overwrite the user's live settings or sessions.
func TestRealPhoneChatSyncImportsAndRetriesWithoutDuplicates(t *testing.T) {
	if os.Getenv("MURONG_REAL_DEVICE_SYNC") != "1" {
		t.Skip("set MURONG_REAL_DEVICE_SYNC=1 with MURONG_REAL_NODE_CONFIG to run")
	}
	sourceConfig := os.Getenv("MURONG_REAL_NODE_CONFIG")
	if sourceConfig == "" {
		t.Fatal("MURONG_REAL_NODE_CONFIG is required")
	}
	raw, err := os.ReadFile(sourceConfig)
	if err != nil {
		t.Fatal(err)
	}
	var cloned map[string]any
	if err := json.Unmarshal(raw, &cloned); err != nil {
		t.Fatal(err)
	}
	phoneURL := os.Getenv("MURONG_REAL_PHONE_URL")
	if phoneURL == "" {
		phoneURL = "http://192.168.2.4:8765"
	}
	cloned["connectionMode"] = desktopbridge.ConnectionModeDirect
	cloned["phoneUrl"] = phoneURL
	cloned["adbSerial"] = ""

	root := t.TempDir()
	localAppData := filepath.Join(root, "local")
	dataDirectory := filepath.Join(root, "desktop-data")
	t.Setenv("LOCALAPPDATA", localAppData)
	t.Setenv("MURONG_DESKTOP_DATA_DIR", dataDirectory)
	targetConfig := filepath.Join(localAppData, "Murong", "computer-node.json")
	if err := os.MkdirAll(filepath.Dir(targetConfig), 0o700); err != nil {
		t.Fatal(err)
	}
	encoded, err := json.MarshalIndent(cloned, "", "  ")
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(targetConfig, encoded, 0o600); err != nil {
		t.Fatal(err)
	}

	app, err := newDesktopAgentApp()
	if err != nil {
		t.Fatal(err)
	}
	defer app.shutdown(context.Background())
	snapshot := app.remote.Snapshot()
	if _, err := app.remote.Start(snapshot.Config, ""); err != nil {
		t.Fatal(err)
	}
	deadline := time.Now().Add(90 * time.Second)
	for time.Now().Before(deadline) {
		snapshot = app.remote.Snapshot()
		if snapshot.Status.Phase == "connected" {
			break
		}
		if snapshot.Status.Phase == "error" {
			t.Fatalf("real phone connection failed: %s", snapshot.Status.Message)
		}
		time.Sleep(250 * time.Millisecond)
	}
	if snapshot.Status.Phase != "connected" {
		t.Fatalf("real phone connection timed out: %#v", snapshot.Status)
	}

	options := desktopbridge.DeviceSyncOptions{IncludeSessions: true}
	firstBundle, err := app.remote.PullCredentials(context.Background(), options)
	if err != nil {
		t.Fatal(err)
	}
	defer clearCredentialBundle(&firstBundle)
	first, err := app.importCredentialBundle(context.Background(), firstBundle)
	if err != nil {
		t.Fatal(err)
	}
	if first.ImportedSessions == 0 {
		t.Fatalf("real phone returned no importable sessions: %#v", first)
	}
	countAfterFirst := len(app.store.sessions)
	secondBundle, err := app.remote.PullCredentials(context.Background(), options)
	if err != nil {
		t.Fatal(err)
	}
	defer clearCredentialBundle(&secondBundle)
	changed := changedSyncedSessionFields(firstBundle.Sessions, secondBundle.Sessions)
	roundTripDifferences := importedSessionRoundTripDifferences(app.store, secondBundle.SourcePlatform, secondBundle.Sessions)
	second, err := app.importCredentialBundle(context.Background(), secondBundle)
	if err != nil {
		t.Fatal(err)
	}
	if second.ImportedSessions != 0 || len(app.store.sessions) != countAfterFirst {
		t.Fatalf("retry created duplicate sessions: first=%#v second=%#v count=%d changedSessionFields=%v importedRoundTripFields=%v", first, second, len(app.store.sessions), changed, roundTripDifferences)
	}
	t.Logf(
		"real phone sync imported=%d conflicts=%d retrySkipped=%d total=%d",
		first.ImportedSessions,
		first.ConflictSessions,
		second.SkippedSessions,
		countAfterFirst,
	)
}

func importedSessionRoundTripDifferences(store *desktopStore, sourcePlatform string, sessions []desktopbridge.SyncedSession) map[string][]string {
	store.mu.Lock()
	defer store.mu.Unlock()
	differences := make(map[string][]string)
	for _, record := range sessions {
		originPlatform, originSessionID := syncedSessionOrigin(sourcePlatform, record.SourceSessionID, record.OriginPlatform, record.OriginSessionID)
		primaryID := syncedSessionID(originPlatform, originSessionID)
		if originPlatform == desktopSourcePlatform() {
			primaryID = originSessionID
		}
		existing := store.sessions[primaryID]
		if existing == nil {
			differences[record.SourceSessionID] = []string{"missing_import"}
			continue
		}
		roundTrip, err := encodeCrossPlatformSession(existing)
		if err != nil {
			differences[record.SourceSessionID] = []string{"encode_error"}
			continue
		}
		if fields := changedPortableSessionFields(record.Document, roundTrip); len(fields) > 0 {
			differences[record.SourceSessionID] = fields
		}
	}
	return differences
}

func changedSyncedSessionFields(first, second []desktopbridge.SyncedSession) map[string][]string {
	baseline := make(map[string]json.RawMessage, len(first))
	for _, session := range first {
		baseline[session.SourceSessionID] = session.Document
	}
	changed := make(map[string][]string)
	for _, session := range second {
		previous, ok := baseline[session.SourceSessionID]
		if !ok {
			changed[session.SourceSessionID] = []string{"added"}
			continue
		}
		delete(baseline, session.SourceSessionID)
		if fields := changedPortableSessionFields(previous, session.Document); len(fields) > 0 {
			changed[session.SourceSessionID] = fields
		}
	}
	for id := range baseline {
		changed[id] = []string{"removed"}
	}
	return changed
}

// changedPortableSessionFields intentionally reports only field names. Real
// verification must never print message bodies, summaries, or other chat data.
func changedPortableSessionFields(first, second json.RawMessage) []string {
	var left, right CrossPlatformSessionEnvelope
	if json.Unmarshal(first, &left) != nil || json.Unmarshal(second, &right) != nil {
		return []string{"invalid_document"}
	}
	leftJSON, _ := json.Marshal(left.Session)
	rightJSON, _ := json.Marshal(right.Session)
	var leftFields, rightFields map[string]any
	if json.Unmarshal(leftJSON, &leftFields) != nil || json.Unmarshal(rightJSON, &rightFields) != nil {
		return []string{"invalid_session"}
	}
	keys := make(map[string]struct{}, len(leftFields)+len(rightFields))
	for key := range leftFields {
		keys[key] = struct{}{}
	}
	for key := range rightFields {
		keys[key] = struct{}{}
	}
	changed := make([]string, 0)
	for key := range keys {
		if !reflect.DeepEqual(leftFields[key], rightFields[key]) {
			changed = append(changed, key)
		}
	}
	sort.Strings(changed)
	return changed
}
