package desktopbridge

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestDesktopPairingAuthenticatorVerifiesTemporaryCodeAndRotates(t *testing.T) {
	auth, err := newDesktopPairingAuthenticator(filepath.Join(t.TempDir(), "pairing.json"))
	if err != nil {
		t.Fatal(err)
	}
	before := auth.Snapshot(time.Now())
	if normalizePairingCode(before.TemporaryCode) == "" {
		t.Fatal("temporary code was not generated")
	}
	phone := deterministicDeviceIdentity(t, 17)
	begin := deviceRelayMessage{
		Version: deviceRelayProtocolVersion, Kind: "auth_begin", RequestID: "auth-request-0001",
		SourceDeviceID: phone.deviceID, SourcePublicKey: phone.publicKey(), TargetDeviceID: deterministicDeviceIdentity(t, 19).deviceID,
		DeviceName: "Test phone", Platform: "android", IssuedAt: time.Now().UnixMilli(),
		AuthMethod: connectionTemporaryCodeAuth, ClientNonce: mustRandomID("clientnonce")[:24],
	}
	// SCRAM nonces are base64url; generate one using the production helper.
	begin.ClientNonce, err = newSCRAMClientNonce()
	if err != nil {
		t.Fatal(err)
	}
	response, err := auth.Begin(begin, phone.fingerprint, time.Now())
	if err != nil {
		t.Fatal(err)
	}
	request := relayPairChallengeRequest(begin, phone.fingerprint)
	proof, err := scramClientProof(before.TemporaryCode, request, response)
	if err != nil {
		t.Fatal(err)
	}
	connect := begin
	connect.Kind = "connect_request"
	connect.AuthProof = proof.authProof
	serverProof, ok := auth.Authenticate(connect, phone.fingerprint, time.Now())
	if !ok {
		t.Fatal("temporary code proof was rejected")
	}
	if err := verifySCRAMServerProof(proof.expectedServerProof, serverProof); err != nil {
		t.Fatal(err)
	}
	after := auth.Snapshot(time.Now())
	if normalizePairingCode(after.TemporaryCode) == normalizePairingCode(before.TemporaryCode) {
		t.Fatal("temporary code did not rotate after successful authentication")
	}
}

func TestDesktopPairingAuthenticatorPersistsOnlySecurityPasswordVerifier(t *testing.T) {
	path := filepath.Join(t.TempDir(), "pairing.json")
	auth, err := newDesktopPairingAuthenticator(path)
	if err != nil {
		t.Fatal(err)
	}
	const password = "Desktop-安全密码-123"
	if err := auth.SetSecurityPassword(password); err != nil {
		t.Fatal(err)
	}
	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(raw), password) {
		t.Fatal("security password plaintext was persisted")
	}
	reloaded, err := newDesktopPairingAuthenticator(path)
	if err != nil {
		t.Fatal(err)
	}
	if !reloaded.Snapshot(time.Now()).SecurityPasswordConfigured {
		t.Fatal("persisted security password verifier was not loaded")
	}
}
