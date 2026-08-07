package main

import (
	"bytes"
	"os"
	"path/filepath"
	"testing"
)

func TestCodexAccountStoreIsolatesHomesAndEncryptsInactiveAuth(t *testing.T) {
	dataRoot := t.TempDir()
	t.Setenv("MURONG_DESKTOP_DATA_DIR", dataRoot)
	runtimeRoot := filepath.Join(dataRoot, "runtime-root")
	legacyHome := filepath.Join(runtimeRoot, "codex-home")
	firstAuth := []byte(`{"auth_mode":"chatgpt","tokens":{"access_token":"first"}}`)
	if err := writeCodexAuthJSON(legacyHome, firstAuth); err != nil {
		t.Fatal(err)
	}

	store, err := newCodexAccountStore(runtimeRoot)
	if err != nil {
		t.Fatal(err)
	}
	created, err := store.Create("备用 Pro")
	if err != nil {
		t.Fatal(err)
	}
	if err := store.Activate(created.ID); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(filepath.Join(legacyHome, "auth.json")); !os.IsNotExist(err) {
		t.Fatalf("inactive account auth.json should be removed, got %v", err)
	}
	secondHome := store.ActiveHome()
	if secondHome == legacyHome {
		t.Fatal("second account reused the legacy CODEX_HOME")
	}
	secondAuth := []byte(`{"auth_mode":"chatgpt","tokens":{"access_token":"second"}}`)
	if err := writeCodexAuthJSON(secondHome, secondAuth); err != nil {
		t.Fatal(err)
	}
	if err := store.CaptureStatus(CodexRuntimeStatus{
		LoggedIn: true,
		Email:    "second@example.com",
		PlanType: "pro",
		RateLimits: []CodexRateLimitInfo{{
			Primary: &CodexRateLimitWindow{UsedPercent: 96},
		}},
	}); err != nil {
		t.Fatal(err)
	}
	if err := store.Activate(defaultCodexAccountID); err != nil {
		t.Fatal(err)
	}
	loaded, err := readCodexAuthJSON(legacyHome)
	if err != nil {
		t.Fatal(err)
	}
	defer clearBytes(loaded)
	if string(loaded) != string(firstAuth) {
		t.Fatal("first account credential was not restored exactly")
	}
	if _, err := os.Stat(filepath.Join(secondHome, "auth.json")); !os.IsNotExist(err) {
		t.Fatalf("second inactive account auth.json should be removed, got %v", err)
	}
	document, err := os.ReadFile(filepath.Join(runtimeRoot, "codex-accounts-v1.json"))
	if err != nil {
		t.Fatal(err)
	}
	if bytes.Contains(document, []byte("access_token")) {
		t.Fatal("account document contains plaintext Codex token fields")
	}
	state := store.State()
	if len(state.Accounts) != 2 {
		t.Fatalf("expected two accounts, got %d", len(state.Accounts))
	}
	var second CodexAccountProfile
	for _, account := range state.Accounts {
		if account.ID == created.ID {
			second = account
		}
	}
	if !second.LowQuota || second.Email != "second@example.com" || !second.LoggedIn {
		t.Fatalf("unexpected cached second account state: %+v", second)
	}
}

func TestCodexAccountPoolSelectionHonoursReserveCooldownAndPinCandidate(t *testing.T) {
	dataRoot := t.TempDir()
	t.Setenv("MURONG_DESKTOP_DATA_DIR", dataRoot)
	runtimeRoot := filepath.Join(dataRoot, "runtime-root")
	if err := writeCodexAuthJSON(
		filepath.Join(runtimeRoot, "codex-home"),
		[]byte(`{"auth_mode":"chatgpt","tokens":{"access_token":"first"}}`),
	); err != nil {
		t.Fatal(err)
	}
	store, err := newCodexAccountStore(runtimeRoot)
	if err != nil {
		t.Fatal(err)
	}
	backup, err := store.Create("备用")
	if err != nil {
		t.Fatal(err)
	}
	if err := store.Activate(backup.ID); err != nil {
		t.Fatal(err)
	}
	if err := writeCodexAuthJSON(
		store.ActiveHome(),
		[]byte(`{"auth_mode":"chatgpt","tokens":{"access_token":"backup"}}`),
	); err != nil {
		t.Fatal(err)
	}
	if err := store.CaptureStatus(CodexRuntimeStatus{
		LoggedIn: true,
		RateLimits: []CodexRateLimitInfo{{
			Primary: &CodexRateLimitWindow{UsedPercent: 30},
		}},
	}); err != nil {
		t.Fatal(err)
	}
	if err := store.Activate(defaultCodexAccountID); err != nil {
		t.Fatal(err)
	}
	if err := store.CaptureStatus(CodexRuntimeStatus{
		LoggedIn: true,
		RateLimits: []CodexRateLimitInfo{{
			Primary: &CodexRateLimitWindow{UsedPercent: 95},
		}},
	}); err != nil {
		t.Fatal(err)
	}
	if got := store.BestAccount("", defaultCodexAccountID, false); got != backup.ID {
		t.Fatalf("expected healthy backup, got %q", got)
	}
	if got := store.BestAccount(defaultCodexAccountID, backup.ID, true); got != defaultCodexAccountID {
		t.Fatalf("explicit pinned candidate should allow low quota, got %q", got)
	}
	store.MarkFailure(backup.ID, os.ErrDeadlineExceeded)
	if got := store.BestAccount("", defaultCodexAccountID, false); got != "" {
		t.Fatalf("cooling backup should not be selected, got %q", got)
	}
}

func TestCodexAccountStoreKeepsVerifiedSnapshotWithoutAuthFile(t *testing.T) {
	dataRoot := t.TempDir()
	t.Setenv("MURONG_DESKTOP_DATA_DIR", dataRoot)
	runtimeRoot := filepath.Join(dataRoot, "runtime-root")
	store, err := newCodexAccountStore(runtimeRoot)
	if err != nil {
		t.Fatal(err)
	}

	if err := store.CaptureStatus(CodexRuntimeStatus{
		LoggedIn: true,
		Email:    "snapshot@example.com",
		PlanType: "plus",
	}); err != nil {
		t.Fatalf("account snapshot without auth.json should remain usable: %v", err)
	}
	state := store.State()
	if len(state.Accounts) != 1 || !state.Accounts[0].LoggedIn || state.Accounts[0].Email != "snapshot@example.com" {
		t.Fatalf("verified snapshot was not persisted: %+v", state)
	}

	// A transient empty account/read must not erase the durable snapshot.
	if err := store.CaptureStatus(CodexRuntimeStatus{}); err != nil {
		t.Fatal(err)
	}
	state = store.State()
	if !state.Accounts[0].LoggedIn || state.Accounts[0].Email != "snapshot@example.com" {
		t.Fatalf("transient empty account erased the snapshot: %+v", state)
	}

	reloaded, err := newCodexAccountStore(runtimeRoot)
	if err != nil {
		t.Fatal(err)
	}
	if got := reloaded.State().Accounts[0]; !got.LoggedIn || got.Email != "snapshot@example.com" {
		t.Fatalf("snapshot did not survive store reload: %+v", got)
	}
	if got := reloaded.BestAccount("", "", false); got != defaultCodexAccountID {
		t.Fatalf("verified snapshot should remain eligible for failover, got %q", got)
	}
}

func TestCodexAccountStoreImportsCompletePoolWithLocalCredentialProtection(t *testing.T) {
	dataRoot := t.TempDir()
	t.Setenv("MURONG_DESKTOP_DATA_DIR", dataRoot)
	runtimeRoot := filepath.Join(dataRoot, "runtime-root")
	if err := writeCodexAuthJSON(
		filepath.Join(runtimeRoot, "codex-home"),
		[]byte(`{"auth_mode":"chatgpt","tokens":{"access_token":"local-token"}}`),
	); err != nil {
		t.Fatal(err)
	}
	store, err := newCodexAccountStore(runtimeRoot)
	if err != nil {
		t.Fatal(err)
	}
	firstAuth := `{"auth_mode":"chatgpt","tokens":{"access_token":"synced-one"}}`
	secondAuth := `{"auth_mode":"chatgpt","tokens":{"access_token":"synced-two"}}`
	settings := CodexAccountPoolSettings{AutoSwitch: true, ReservePercent: 17, CooldownMinutes: 33}
	imported, err := store.ImportAccounts([]codexAccountTransfer{
		{ID: "codex-sync-one", Label: "同步一号", Email: "one@example.com", Enabled: true, AuthJSON: &firstAuth},
		{ID: "codex-sync-two", Label: "同步二号", Email: "two@example.com", Enabled: true, AuthJSON: &secondAuth},
	}, "codex-sync-two", &settings)
	if err != nil || imported != 2 {
		t.Fatalf("Codex account pool import failed: imported=%d err=%v", imported, err)
	}
	state := store.State()
	if len(state.Accounts) != 3 || state.ActiveAccountID != "codex-sync-two" || state.Settings.ReservePercent != 17 || state.Settings.CooldownMinutes != 33 {
		t.Fatalf("Codex account pool was not merged: %#v", state)
	}
	active, err := readCodexAuthJSON(store.ActiveHome())
	if err != nil {
		t.Fatal(err)
	}
	defer clearBytes(active)
	if string(active) != secondAuth {
		t.Fatalf("imported active Codex login was not materialized: %s", active)
	}
	if _, err := os.Stat(filepath.Join(runtimeRoot, "codex-home", "auth.json")); !os.IsNotExist(err) {
		t.Fatalf("previous active Codex login remained plaintext: %v", err)
	}
	document, err := os.ReadFile(filepath.Join(runtimeRoot, "codex-accounts-v1.json"))
	if err != nil {
		t.Fatal(err)
	}
	if bytes.Contains(document, []byte("synced-one")) || bytes.Contains(document, []byte("synced-two")) || bytes.Contains(document, []byte("local-token")) {
		t.Fatal("Codex account document contains plaintext synchronized credentials")
	}
	exported, activeID, exportedSettings, err := store.AccountCredentials()
	if err != nil || len(exported) != 3 || activeID != "codex-sync-two" || exportedSettings.ReservePercent != 17 {
		t.Fatalf("Codex account pool could not be exported again: %#v %q %#v %v", exported, activeID, exportedSettings, err)
	}
	for _, account := range exported {
		if account.AuthJSON == nil {
			t.Fatalf("account %q lost its synchronized login", account.ID)
		}
		*account.AuthJSON = ""
	}
}
