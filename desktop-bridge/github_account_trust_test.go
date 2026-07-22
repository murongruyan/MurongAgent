package desktopbridge

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"testing"
	"time"
)

func TestGitHubAccountTrustLogsInAndIssuesDeviceBoundProof(t *testing.T) {
	issuer, err := newEphemeralDeviceIdentity()
	if err != nil {
		t.Fatal(err)
	}
	targetIdentity, err := newEphemeralDeviceIdentity()
	if err != nil {
		t.Fatal(err)
	}
	ephemeral, err := newEphemeralDeviceIdentity()
	if err != nil {
		t.Fatal(err)
	}
	target := publicDeviceStatus{
		DeviceID:          targetIdentity.deviceID,
		DevicePublicKey:   targetIdentity.publicKey(),
		DeviceFingerprint: targetIdentity.fingerprint,
	}
	connection := connectionRequest{
		RequestID:          "connect-0123456789abcdef0123456789abcdef",
		ClientName:         "Murong Desktop",
		DeviceID:           issuer.deviceID,
		DevicePublicKey:    issuer.publicKey(),
		DeviceFingerprint:  issuer.fingerprint,
		EphemeralPublicKey: ephemeral.publicKey(),
		Platform:           "windows",
		IssuedAt:           time.Now().UnixMilli(),
		AuthMethod:         connectionGitHubAccountAuth,
	}
	ticket := base64.RawURLEncoding.EncodeToString(make([]byte, 32))
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		writer.Header().Set("Content-Type", "application/json")
		switch request.URL.Path {
		case "/api/github_auth.php":
			var input map[string]string
			if err := json.NewDecoder(request.Body).Decode(&input); err != nil {
				t.Fatal(err)
			}
			if request.URL.Query().Get("action") != "token_login" || input["github_token"] != "github-token" {
				t.Fatalf("unexpected token login request: %s %#v", request.URL.String(), input)
			}
			_, _ = writer.Write([]byte(`{"success":true,"data":{"session_token":"backend-session","session_expires_at":"2026-08-01 00:00:00","github_login":"murong"}}`))
		case "/api/device_trust.php":
			if request.Header.Get("Authorization") != "Bearer backend-session" {
				t.Fatalf("authorization = %q", request.Header.Get("Authorization"))
			}
			var input struct {
				RequestID             string `json:"request_id"`
				ClientName            string `json:"client_name"`
				DeviceID              string `json:"device_id"`
				DevicePublicKey       string `json:"device_public_key"`
				DeviceFingerprint     string `json:"device_fingerprint"`
				EphemeralPublicKey    string `json:"ephemeral_public_key"`
				Platform              string `json:"platform"`
				IssuedAt              int64  `json:"issued_at"`
				TargetDeviceID        string `json:"target_device_id"`
				TargetDevicePublicKey string `json:"target_device_public_key"`
				TargetFingerprint     string `json:"target_device_fingerprint"`
				Nonce                 string `json:"proof_nonce"`
				Signature             string `json:"device_signature"`
			}
			if err := json.NewDecoder(request.Body).Decode(&input); err != nil {
				t.Fatal(err)
			}
			if input.RequestID != connection.RequestID || input.TargetDeviceID != target.DeviceID || input.TargetFingerprint != target.DeviceFingerprint {
				t.Fatalf("proof binding mismatch: %#v", input)
			}
			payload := append(append([]byte("murong-github-device-proof-issue-v1\n"), githubAccountConnectionPayload(connection, target)...), []byte("\n"+input.Nonce)...)
			if !verifyDeviceSignature(input.DevicePublicKey, payload, input.Signature) {
				t.Fatal("device account proof signature was invalid")
			}
			_ = json.NewEncoder(writer).Encode(map[string]any{
				"success": true,
				"data": map[string]any{
					"version":    githubAccountProofVersion,
					"ticket":     ticket,
					"expires_at": time.Now().Add(time.Minute).UnixMilli(),
				},
			})
		default:
			http.NotFound(writer, request)
		}
	}))
	t.Cleanup(server.Close)
	client, err := newGitHubAccountTrustClient(server.URL)
	if err != nil {
		t.Fatal(err)
	}
	session, err := client.loginWithGitHubToken(context.Background(), "github-token")
	if err != nil {
		t.Fatal(err)
	}
	proof, err := client.issueProof(context.Background(), session.SessionToken, issuer, connection, target)
	if err != nil {
		t.Fatal(err)
	}
	if proof.Ticket != ticket || proof.Version != githubAccountProofVersion {
		t.Fatalf("unexpected proof: %#v", proof)
	}
}

func TestGitHubAccountConnectionPayloadIsLanguageNeutral(t *testing.T) {
	request := connectionRequest{
		RequestID: "connect-request-0001", ClientName: " Murong Desktop ",
		DeviceID: "ISSUER", DevicePublicKey: "issuer-public", DeviceFingerprint: "issuer-fingerprint",
		EphemeralPublicKey: "ephemeral-public", Platform: "windows", IssuedAt: 123456789,
	}
	target := publicDeviceStatus{DeviceID: "TARGET", DevicePublicKey: "target-public", DeviceFingerprint: "target-fingerprint"}
	want := "murong-github-device-proof-connection-v1\nconnect-request-0001\nMurong Desktop\nISSUER\nissuer-public\nissuer-fingerprint\nephemeral-public\nwindows\n123456789\nTARGET\ntarget-public\ntarget-fingerprint"
	if got := string(githubAccountConnectionPayload(request, target)); got != want {
		t.Fatalf("payload = %q\nwant    = %q", got, want)
	}
}

func TestGitHubAccountTrustVerifiesPhoneIssuedProof(t *testing.T) {
	phone := mustEphemeralIdentity(t)
	desktop := mustEphemeralIdentity(t)
	ephemeral := mustEphemeralIdentity(t)
	ticket := base64.RawURLEncoding.EncodeToString(make([]byte, 32))
	request := connectionRequest{
		RequestID: "connect-phone-proof-0001", ClientName: "Murong Phone",
		DeviceID: phone.deviceID, DevicePublicKey: phone.publicKey(), DeviceFingerprint: phone.fingerprint,
		EphemeralPublicKey: ephemeral.publicKey(), Platform: "android", IssuedAt: time.Now().UnixMilli(),
		AuthMethod: connectionGitHubAccountAuth, AuthProof: ticket,
	}
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, httpRequest *http.Request) {
		if httpRequest.URL.Query().Get("action") != "verify" || httpRequest.Header.Get("Authorization") != "Bearer desktop-session" {
			t.Fatalf("unexpected verify request: %s auth=%q", httpRequest.URL.String(), httpRequest.Header.Get("Authorization"))
		}
		var input map[string]any
		if err := json.NewDecoder(httpRequest.Body).Decode(&input); err != nil {
			t.Fatal(err)
		}
		receiverIssuedAt := int64(input["receiver_issued_at"].(float64))
		receiverSignature, _ := input["receiver_signature"].(string)
		if !verifyDeviceSignature(desktop.publicKey(), githubAccountVerificationSignaturePayload(
			ticket, request.RequestID, desktop.deviceID, desktop.publicKey(), desktop.fingerprint, receiverIssuedAt,
		), receiverSignature) {
			t.Fatal("desktop verification signature was invalid")
		}
		writer.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(writer).Encode(map[string]any{"success": true, "data": map[string]any{
			"trusted": true, "version": githubAccountProofVersion, "trust_source": connectionGitHubAccountAuth,
			"issuer_device_id": phone.deviceID, "issuer_fingerprint": phone.fingerprint,
		}})
	}))
	t.Cleanup(server.Close)
	client, err := newGitHubAccountTrustClient(server.URL)
	if err != nil {
		t.Fatal(err)
	}
	trusted, err := client.verifyProof(context.Background(), "desktop-session", desktop, request, ticket)
	if err != nil || !trusted {
		t.Fatalf("phone account proof was not trusted: trusted=%v err=%v", trusted, err)
	}
}

func TestRemoteNodeServiceVerifiesPhoneAccountProofWithProtectedSession(t *testing.T) {
	phone := mustEphemeralIdentity(t)
	desktop := mustEphemeralIdentity(t)
	ephemeral := mustEphemeralIdentity(t)
	ticket := base64.RawURLEncoding.EncodeToString(make([]byte, 32))
	protectedSession, err := protectSecret([]byte("desktop-session"))
	if err != nil {
		t.Fatal(err)
	}
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		if request.Header.Get("Authorization") != "Bearer desktop-session" || request.URL.Query().Get("action") != "verify" {
			t.Fatalf("unexpected service verification request: %s auth=%q", request.URL.String(), request.Header.Get("Authorization"))
		}
		writer.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(writer).Encode(map[string]any{"success": true, "data": map[string]any{
			"trusted": true, "version": githubAccountProofVersion, "trust_source": connectionGitHubAccountAuth,
			"issuer_device_id": phone.deviceID, "issuer_fingerprint": phone.fingerprint,
		}})
	}))
	t.Cleanup(server.Close)
	client, err := newGitHubAccountTrustClient(server.URL)
	if err != nil {
		t.Fatal(err)
	}
	message := deviceRelayMessage{
		Version: deviceRelayProtocolVersion, Kind: "connect_request", RequestID: "connect-phone-service-0001",
		SourceDeviceID: phone.deviceID, SourcePublicKey: phone.publicKey(), TargetDeviceID: desktop.deviceID,
		EphemeralPublicKey: ephemeral.publicKey(), DeviceName: "Murong Phone", Platform: "android",
		IssuedAt: time.Now().UnixMilli(), AuthMethod: connectionGitHubAccountAuth, AuthProof: ticket,
	}
	service := &RemoteNodeService{
		configPath: filepath.Join(t.TempDir(), "computer-node.json"),
		config: nodeConfig{
			SchemaVersion: nodeConfigSchemaVersion, ProtectedGitHubSession: protectedSession,
		},
		identity: desktop, githubTrustClient: client,
	}
	if !service.verifyGitHubRelayRequest(deviceRelayIncomingRequest{message: message, fingerprint: phone.fingerprint}) {
		t.Fatal("service did not trust the phone's same-account proof")
	}
}
