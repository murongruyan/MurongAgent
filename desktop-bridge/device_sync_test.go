package desktopbridge

import (
	"bytes"
	"encoding/json"
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
