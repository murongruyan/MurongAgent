package desktopbridge

import (
	"context"
	"net/http"
	"os"
	"strings"
	"testing"
	"time"
)

// TestRealPhoneSCRAMPairing is opt-in because it consumes a real temporary
// code or uses a real security password. It always revokes the test identity
// after proving the returned token and encrypted sync key are usable.
func TestRealPhoneSCRAMPairing(t *testing.T) {
	phoneURL := strings.TrimSpace(os.Getenv("MURONG_SCRAM_INTEGRATION_PHONE_URL"))
	method := strings.TrimSpace(os.Getenv("MURONG_SCRAM_INTEGRATION_METHOD"))
	secret := strings.TrimSpace(os.Getenv("MURONG_SCRAM_INTEGRATION_SECRET"))
	if phoneURL == "" || method == "" || secret == "" {
		t.Skip("set MURONG_SCRAM_INTEGRATION_PHONE_URL, METHOD and SECRET to run")
	}
	if method != connectionTemporaryCodeAuth && method != connectionSecurityPasswordAuth {
		t.Fatalf("unsupported real SCRAM method %q", method)
	}
	target, err := validatePhoneURL(phoneURL)
	if err != nil {
		t.Fatal(err)
	}
	api := newAPIClient(target)
	defer api.Close()
	identity, err := newEphemeralDeviceIdentity()
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
	defer cancel()
	credentials, err := api.connectByRequestWithSCRAM(
		ctx,
		identity,
		"Murong Real SCRAM Test",
		"",
		"",
		method,
		secret,
	)
	if err != nil {
		t.Fatal(err)
	}
	defer clearBytes(credentials.syncKey)
	if credentials.token == "" || len(credentials.syncKey) != 32 || credentials.peerDeviceID == "" || credentials.peerFingerprint == "" {
		t.Fatalf("SCRAM pairing returned incomplete secure credentials: token=%t sync=%d peer=%t fingerprint=%t",
			credentials.token != "", len(credentials.syncKey), credentials.peerDeviceID != "", credentials.peerFingerprint != "")
	}
	api.token = credentials.token
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, api.endpoint("/api/v1/sessions"), nil)
	if err != nil {
		t.Fatal(err)
	}
	request.Header.Set("Authorization", "Bearer "+credentials.token)
	response, err := api.httpClient.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	_ = response.Body.Close()
	if response.StatusCode != http.StatusOK {
		t.Fatalf("SCRAM token was rejected: HTTP %d", response.StatusCode)
	}
	if err := api.postJSON(ctx, unpairPath, map[string]string{}, nil); err != nil {
		t.Fatalf("failed to revoke SCRAM integration client: %v", err)
	}
}
