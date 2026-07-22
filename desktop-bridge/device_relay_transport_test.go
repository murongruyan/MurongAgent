package desktopbridge

import (
	"context"
	"crypto/subtle"
	"encoding/base64"
	"errors"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"
)

func TestNegotiateDeviceRelayUsesSignedECDHInvitation(t *testing.T) {
	desktop := mustEphemeralIdentity(t)
	phone := mustEphemeralIdentity(t)
	type observedInvitation struct {
		invitation deviceRelayInvitation
		secret     []byte
	}
	observed := make(chan observedInvitation, 1)
	serverErrors := make(chan error, 1)
	mux := http.NewServeMux()
	mux.HandleFunc("/relay/v2/device", func(writer http.ResponseWriter, request *http.Request) {
		connection, err := websocket.Accept(writer, request, &websocket.AcceptOptions{
			Subprotocols: []string{deviceRelaySubprotocol},
		})
		if err != nil {
			serverErrors <- err
			return
		}
		defer connection.Close(websocket.StatusNormalClosure, "")
		ctx, cancel := context.WithTimeout(context.Background(), 4*time.Second)
		defer cancel()
		registration, err := readDeviceRelayMessage(ctx, connection)
		if err != nil || registration.Kind != "register" || registration.DeviceID != desktop.deviceID ||
			!verifyDeviceSignature(desktop.publicKey(), deviceRelayRegistrationPayload(registration), registration.Signature) {
			serverErrors <- errors.New("invalid desktop registration")
			return
		}
		if err := writeDeviceRelayMessage(ctx, connection, deviceRelayMessage{
			Version: deviceRelayProtocolVersion, Kind: "registered", DeviceID: desktop.deviceID,
			DevicePublicKey: desktop.publicKey(), Platform: "desktop", IssuedAt: time.Now().UnixMilli(),
		}); err != nil {
			serverErrors <- err
			return
		}
		lookup, err := readDeviceRelayMessage(ctx, connection)
		if err != nil || lookup.Kind != "lookup" || lookup.TargetDeviceID != phone.deviceID ||
			!verifyDeviceSignature(desktop.publicKey(), deviceRelayLookupPayload(lookup), lookup.Signature) {
			serverErrors <- errors.New("invalid signed lookup")
			return
		}
		if err := writeDeviceRelayMessage(ctx, connection, deviceRelayMessage{
			Version: deviceRelayProtocolVersion, Kind: "peer", RequestID: lookup.RequestID,
			DeviceID: phone.deviceID, DevicePublicKey: phone.publicKey(), Platform: "android",
			IssuedAt: time.Now().UnixMilli(),
		}); err != nil {
			serverErrors <- err
			return
		}
		invite, err := readDeviceRelayMessage(ctx, connection)
		if err != nil || invite.Kind != "invite" || invite.TargetDeviceID != phone.deviceID ||
			!verifyDeviceSignature(desktop.publicKey(), deviceRelayInvitePayload(invite), invite.Signature) {
			serverErrors <- errors.New("invalid signed invite")
			return
		}
		linkSecret, err := phone.deriveLinkSecret(
			invite.EphemeralPublicKey,
			deviceRelayInviteContext(invite.RequestID, invite.SourceDeviceID, invite.TargetDeviceID),
		)
		if err != nil {
			serverErrors <- err
			return
		}
		defer clearBytes(linkSecret)
		invitation, err := decryptDeviceRelayInvitation(
			linkSecret,
			deviceRelayInviteAAD(invite.RequestID, invite.SourceDeviceID, invite.TargetDeviceID, invite.EphemeralPublicKey),
			invite.Nonce,
			invite.Ciphertext,
		)
		if err != nil {
			serverErrors <- err
			return
		}
		secret, err := decodeBase64URL(invitation.Secret, deviceTunnelSecretBytes)
		if err != nil || len(secret) != deviceTunnelSecretBytes || validateDeviceTunnelRoomID(invitation.RoomID) != nil {
			serverErrors <- errors.New("invalid encrypted room credentials")
			return
		}
		observed <- observedInvitation{invitation: invitation, secret: secret}
		acknowledgement := deviceRelayMessage{
			Version: deviceRelayProtocolVersion, Kind: "invite_ack", RequestID: invite.RequestID,
			SourceDeviceID: phone.deviceID, TargetDeviceID: desktop.deviceID,
			Status: "accepted", IssuedAt: time.Now().UnixMilli(),
		}
		acknowledgement.Signature, err = phone.sign(deviceRelayInviteAckPayload(acknowledgement))
		if err != nil {
			serverErrors <- err
			return
		}
		if err := writeDeviceRelayMessage(ctx, connection, acknowledgement); err != nil {
			serverErrors <- err
		}
	})
	server := httptest.NewServer(mux)
	t.Cleanup(server.Close)
	relayURL, err := url.Parse("ws" + strings.TrimPrefix(server.URL, "http") + "/relay/v2/device")
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	result, err := negotiateDeviceRelay(ctx, relayURL, desktop, phone.deviceID)
	if err != nil {
		t.Fatal(err)
	}
	defer clearBytes(result.Secret)
	select {
	case serverErr := <-serverErrors:
		t.Fatal(serverErr)
	case item := <-observed:
		defer clearBytes(item.secret)
		if result.RoomID != item.invitation.RoomID || subtle.ConstantTimeCompare(result.Secret, item.secret) != 1 {
			t.Fatal("desktop did not return the ECDH-encrypted room credentials")
		}
		if item.invitation.ExpiresAt <= time.Now().UnixMilli() {
			t.Fatal("invitation was already expired")
		}
	default:
		t.Fatal("relay did not observe an encrypted invitation")
	}
	if result.PeerDeviceID != phone.deviceID || result.PeerFingerprint != phone.fingerprint ||
		result.RelayURL.Path != "/relay/v2/tunnel" {
		t.Fatalf("unexpected negotiation result: %#v", result)
	}
}

func TestDeviceRelayInvitationRejectsTampering(t *testing.T) {
	secret := make([]byte, 32)
	aad := []byte("bound invitation")
	invitation := deviceRelayInvitation{
		Version: 2, RoomID: "AAAAAAAAAAAAAAAAAAAAAA", Secret: "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB",
		ExpiresAt: time.Now().Add(time.Minute).UnixMilli(),
	}
	nonce, ciphertext, err := encryptDeviceRelayInvitation(secret, aad, invitation)
	if err != nil {
		t.Fatal(err)
	}
	decoded, err := decryptDeviceRelayInvitation(secret, aad, nonce, ciphertext)
	if err != nil || decoded.RoomID != invitation.RoomID {
		t.Fatalf("valid invitation failed: %#v, %v", decoded, err)
	}
	if _, err := decryptDeviceRelayInvitation(secret, []byte("different binding"), nonce, ciphertext); err == nil {
		t.Fatal("tampered AAD was accepted")
	}
	rawCiphertext, err := base64.RawURLEncoding.DecodeString(ciphertext)
	if err != nil {
		t.Fatal(err)
	}
	rawCiphertext[len(rawCiphertext)-1] ^= 1
	if _, err := decryptDeviceRelayInvitation(secret, aad, nonce, base64.RawURLEncoding.EncodeToString(rawCiphertext)); err == nil {
		t.Fatal("tampered ciphertext was accepted")
	}
}

func mustEphemeralIdentity(t *testing.T) *deviceIdentity {
	t.Helper()
	identity, err := newEphemeralDeviceIdentity()
	if err != nil {
		t.Fatal(err)
	}
	return identity
}
