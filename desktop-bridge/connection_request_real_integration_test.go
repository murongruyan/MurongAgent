package desktopbridge

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"runtime"
	"strings"
	"testing"
	"time"
)

// TestRealPhoneConnectionRequestNotificationAndDecision is opt-in because it
// talks to an installed phone app and invokes the same foreground-service
// actions used by the notification buttons. It never touches the user's saved
// desktop configuration and revokes an approved test client before returning.
func TestRealPhoneConnectionRequestNotificationAndDecision(t *testing.T) {
	phoneURL := strings.TrimSpace(os.Getenv("MURONG_CONNECTION_INTEGRATION_PHONE_URL"))
	serial := strings.TrimSpace(os.Getenv("MURONG_CONNECTION_INTEGRATION_ADB_SERIAL"))
	decision := strings.ToLower(strings.TrimSpace(os.Getenv("MURONG_CONNECTION_INTEGRATION_DECISION")))
	if phoneURL == "" || serial == "" {
		t.Skip("set MURONG_CONNECTION_INTEGRATION_PHONE_URL and MURONG_CONNECTION_INTEGRATION_ADB_SERIAL to run")
	}
	if decision != "approve" && decision != "reject" && decision != "block" && decision != "dnd" {
		t.Fatalf("MURONG_CONNECTION_INTEGRATION_DECISION must be approve, reject, block, or dnd, got %q", decision)
	}

	ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
	defer cancel()
	adbPath, err := findADBExecutable()
	if err != nil {
		t.Fatal(err)
	}
	rootMode := detectADBRoot(ctx, adbPath, serial)
	if rootMode == adbRootUnavailable {
		t.Skip("real notification decision requires Root ADB so the test can invoke the app-internal service action")
	}
	target, err := validatePhoneURL(phoneURL)
	if err != nil {
		t.Fatal(err)
	}
	api := newAPIClient(target)
	defer api.Close()
	peer, err := api.fetchPublicDeviceStatus(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if err := validatePublicDeviceStatus(peer, "", ""); err != nil {
		t.Fatal(err)
	}

	config := nodeConfig{SchemaVersion: nodeConfigSchemaVersion}
	identity, err := ensureNodeDeviceIdentity(&config)
	if err != nil {
		t.Fatal(err)
	}
	if decision == "block" {
		defer removeRealPhoneBlockedPeer(t, serial, adbPath, rootMode, identity.deviceID)
	}
	if decision == "dnd" {
		originalDND, err := setRealPhoneDoNotDisturb(ctx, serial, adbPath, rootMode, true)
		if err != nil {
			t.Fatal(err)
		}
		defer func() {
			cleanupContext, cleanupCancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer cleanupCancel()
			if _, restoreErr := setRealPhoneDoNotDisturb(cleanupContext, serial, adbPath, rootMode, originalDND); restoreErr != nil {
				t.Errorf("failed to restore phone DND state: %v", restoreErr)
			}
		}()
	}
	clientName := "Murong Real Approve Test"
	switch decision {
	case "reject":
		clientName = "Murong Real Reject Test"
	case "block":
		clientName = "Murong Real Block Test"
	case "dnd":
		clientName = "Murong Real DND Test"
	}
	request, ephemeral := newRealConnectionRequest(t, identity, clientName)
	var acknowledgement connectionRequestAck
	if err := api.postPublicJSON(ctx, connectionRequestPath, request, &acknowledgement, 20*time.Second); err != nil {
		t.Fatal(err)
	}
	expectedAcknowledgement := connectionStatusPending
	if decision == "dnd" {
		expectedAcknowledgement = connectionStatusRejected
	}
	if acknowledgement.RequestID != request.RequestID || acknowledgement.Status != expectedAcknowledgement {
		t.Fatalf("unexpected connection acknowledgement: %#v", acknowledgement)
	}

	notificationDump, err := runADB(ctx, adbPath, "-s", serial, "shell", "dumpsys", "notification", "--noredact")
	if err != nil {
		t.Fatal(err)
	}
	if decision == "dnd" {
		if strings.Contains(notificationDump, clientName) {
			t.Fatal("DND-rejected connection unexpectedly produced a notification")
		}
		response := waitForRealConnectionDecision(t, ctx, api, identity, request.RequestID)
		if response.Status != connectionStatusRejected || response.SecureChannel != nil {
			t.Fatalf("DND did not safely reject the unknown device: %#v", response)
		}
		return
	}
	if !strings.Contains(notificationDump, clientName) || !strings.Contains(notificationDump, "murong_device_connection_request") {
		t.Fatal("phone did not expose the expected connection-request notification")
	}

	action := "com.murong.agent.lan.APPROVE_CONNECTION"
	if decision == "reject" {
		action = "com.murong.agent.lan.REJECT_CONNECTION"
	} else if decision == "block" {
		action = "com.murong.agent.lan.BLOCK_CONNECTION"
	}
	command := fmt.Sprintf(
		"am start-foreground-service -n com.murong.agent/.lan.LanWebForegroundService -a %s --es com.murong.agent.lan.extra.CONNECTION_REQUEST_ID %s",
		adbShellQuote(action), adbShellQuote(request.RequestID),
	)
	if _, err := runADBRootCommand(ctx, adbPath, serial, rootMode, command); err != nil {
		t.Fatal(err)
	}

	response := waitForRealConnectionDecision(t, ctx, api, identity, request.RequestID)
	if decision == "reject" {
		if response.Status != connectionStatusRejected || response.SecureChannel != nil {
			t.Fatalf("connection request was not safely rejected: %#v", response)
		}
		return
	}
	if decision == "block" {
		if response.Status != connectionStatusBlocked || response.SecureChannel != nil {
			t.Fatalf("connection request was not safely blocked: %#v", response)
		}
		secondRequest, _ := newRealConnectionRequest(t, identity, clientName)
		var secondAcknowledgement connectionRequestAck
		if err := api.postPublicJSON(ctx, connectionRequestPath, secondRequest, &secondAcknowledgement, 20*time.Second); err != nil {
			t.Fatal(err)
		}
		if secondAcknowledgement.Status != connectionStatusBlocked {
			t.Fatalf("blocked identity was allowed to request again: %#v", secondAcknowledgement)
		}
		secondResponse := waitForRealConnectionDecision(t, ctx, api, identity, secondRequest.RequestID)
		if secondResponse.Status != connectionStatusBlocked || secondResponse.SecureChannel != nil {
			t.Fatalf("blocked identity received an unsafe response: %#v", secondResponse)
		}
		return
	}
	if response.Status != connectionStatusApproved {
		t.Fatalf("connection request was not approved: %#v", response)
	}
	credentials, err := decodeApprovedDeviceLink(identity, ephemeral, peer, request, response)
	if err != nil {
		t.Fatal(err)
	}
	defer clearBytes(credentials.syncKey)
	api.token = credentials.token
	httpRequest, err := http.NewRequestWithContext(ctx, http.MethodGet, api.endpoint("/api/v1/sessions"), nil)
	if err != nil {
		t.Fatal(err)
	}
	httpRequest.Header.Set("Authorization", "Bearer "+credentials.token)
	httpResponse, err := api.httpClient.Do(httpRequest)
	if err != nil {
		t.Fatal(err)
	}
	_ = httpResponse.Body.Close()
	if httpResponse.StatusCode != http.StatusOK {
		t.Fatalf("approved request token was rejected: HTTP %d", httpResponse.StatusCode)
	}
	if err := api.postJSON(ctx, "/api/v1/unpair", map[string]string{}, nil); err != nil {
		t.Fatalf("failed to revoke approved integration client: %v", err)
	}
}

func newRealConnectionRequest(t *testing.T, identity *deviceIdentity, clientName string) (connectionRequest, *deviceIdentity) {
	t.Helper()
	ephemeral, err := newEphemeralDeviceIdentity()
	if err != nil {
		t.Fatal(err)
	}
	requestID, err := randomID("real-connect")
	if err != nil {
		t.Fatal(err)
	}
	request := connectionRequest{
		RequestID: requestID, ClientName: clientName,
		DeviceID: identity.deviceID, DevicePublicKey: identity.publicKey(), DeviceFingerprint: identity.fingerprint,
		EphemeralPublicKey: ephemeral.publicKey(), Platform: runtime.GOOS,
		IssuedAt: time.Now().UnixMilli(), AuthMethod: connectionApprovalAuth,
	}
	request.Signature, err = identity.sign(connectionRequestSignaturePayload(request))
	if err != nil {
		t.Fatal(err)
	}
	return request, ephemeral
}

func waitForRealConnectionDecision(
	t *testing.T,
	ctx context.Context,
	api *apiClient,
	identity *deviceIdentity,
	requestID string,
) connectionStatusResponse {
	t.Helper()
	for {
		statusRequest := connectionStatusRequest{
			RequestID: requestID,
			DeviceID:  identity.deviceID,
			IssuedAt:  time.Now().UnixMilli(),
		}
		var err error
		statusRequest.Signature, err = identity.sign(connectionStatusSignaturePayload(statusRequest))
		if err != nil {
			t.Fatal(err)
		}
		var response connectionStatusResponse
		if err := api.postPublicJSON(ctx, connectionStatusPath, statusRequest, &response, 10*time.Second); err != nil {
			t.Fatal(err)
		}
		if response.Status != connectionStatusPending {
			return response
		}
		select {
		case <-ctx.Done():
			t.Fatal(ctx.Err())
		case <-time.After(250 * time.Millisecond):
		}
	}
}

const realPhoneAccessStatePath = "/data/user/0/com.murong.agent/no_backup/lan_web_access.json"

func setRealPhoneDoNotDisturb(
	ctx context.Context,
	serial, adbPath string,
	rootMode adbRootMode,
	enabled bool,
) (bool, error) {
	original := false
	err := mutateRealPhoneAccessState(ctx, serial, adbPath, rootMode, func(state map[string]json.RawMessage) error {
		if raw := state["doNotDisturb"]; len(raw) > 0 {
			if err := json.Unmarshal(raw, &original); err != nil {
				return err
			}
		}
		encoded, err := json.Marshal(enabled)
		if err != nil {
			return err
		}
		state["doNotDisturb"] = encoded
		return nil
	})
	return original, err
}

func removeRealPhoneBlockedPeer(t *testing.T, serial, adbPath string, rootMode adbRootMode, deviceID string) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	err := mutateRealPhoneAccessState(ctx, serial, adbPath, rootMode, func(state map[string]json.RawMessage) error {
		var blocked []map[string]json.RawMessage
		if raw := state["blockedPeers"]; len(raw) > 0 {
			if err := json.Unmarshal(raw, &blocked); err != nil {
				return err
			}
		}
		kept := blocked[:0]
		for _, peer := range blocked {
			var candidate string
			_ = json.Unmarshal(peer["deviceId"], &candidate)
			if candidate != deviceID {
				kept = append(kept, peer)
			}
		}
		encoded, err := json.Marshal(kept)
		if err != nil {
			return err
		}
		state["blockedPeers"] = encoded
		return nil
	})
	if err != nil {
		t.Errorf("failed to remove integration-test blocked peer: %v", err)
	}
}

func mutateRealPhoneAccessState(
	ctx context.Context,
	serial, adbPath string,
	rootMode adbRootMode,
	mutate func(map[string]json.RawMessage) error,
) error {
	raw, err := runADBRootCommand(ctx, adbPath, serial, rootMode, "cat "+adbShellQuote(realPhoneAccessStatePath))
	if err != nil {
		return err
	}
	var state map[string]json.RawMessage
	if err := json.Unmarshal([]byte(raw), &state); err != nil {
		return err
	}
	if err := mutate(state); err != nil {
		return err
	}
	updated, err := json.MarshalIndent(state, "", "    ")
	if err != nil {
		return err
	}
	updated = append(updated, '\n')
	encoded := base64.StdEncoding.EncodeToString(updated)
	temporary := realPhoneAccessStatePath + ".murong-integration.tmp"
	command := fmt.Sprintf(
		"cp -p %s %s && printf %%s %s | base64 -d > %s && mv -f %s %s",
		adbShellQuote(realPhoneAccessStatePath), adbShellQuote(temporary), adbShellQuote(encoded),
		adbShellQuote(temporary), adbShellQuote(temporary), adbShellQuote(realPhoneAccessStatePath),
	)
	_, err = runADBRootCommand(ctx, adbPath, serial, rootMode, command)
	return err
}
