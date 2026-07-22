package desktopbridge

import (
	"context"
	"encoding/base64"
	"os"
	"testing"
	"time"
)

func TestLANDiscoveryAnnouncementRequiresNonceIdentityFingerprintAndSignature(t *testing.T) {
	identity := deterministicDeviceIdentity(t, 1)
	nonce := base64.RawURLEncoding.EncodeToString([]byte("0123456789abcdef"))
	announcement := lanDiscoveryAnnouncement{
		Version: lanDiscoveryVersion, Nonce: nonce,
		DeviceID: identity.deviceID, DeviceDisplayID: identity.displayID(),
		DevicePublicKey: identity.publicKey(), DeviceFingerprint: identity.fingerprint,
		Name: "Murong Android", Platform: "android", Port: 8765, IssuedAt: time.Now().UnixMilli(),
	}
	var err error
	announcement.Signature, err = identity.sign(lanDiscoverySignaturePayload(announcement))
	if err != nil {
		t.Fatal(err)
	}
	if !validateLANAnnouncement(announcement, nonce, time.Now()) {
		t.Fatal("valid signed LAN discovery announcement was rejected")
	}
	tampered := announcement
	tampered.Port++
	if validateLANAnnouncement(tampered, nonce, time.Now()) {
		t.Fatal("tampered LAN discovery announcement was accepted")
	}
	if validateLANAnnouncement(announcement, nonce+"x", time.Now()) {
		t.Fatal("announcement with the wrong discovery nonce was accepted")
	}
}

func TestLANBroadcastAddressesAreDeduplicatedIPv4(t *testing.T) {
	seen := map[string]bool{}
	for _, address := range lanBroadcastAddresses() {
		if address.To4() == nil {
			t.Fatalf("non-IPv4 broadcast address: %v", address)
		}
		if seen[address.String()] {
			t.Fatalf("duplicate broadcast address: %v", address)
		}
		seen[address.String()] = true
	}
	if !seen["255.255.255.255"] {
		t.Fatal("global IPv4 broadcast address is missing")
	}
}

func TestLANDiscoveryIntegration(t *testing.T) {
	expectedRaw := os.Getenv("MURONG_LAN_INTEGRATION_DEVICE_ID")
	if expectedRaw == "" {
		t.Skip("set MURONG_LAN_INTEGRATION_DEVICE_ID to run the real-device LAN discovery test")
	}
	expected := ""
	if expectedRaw != "*" {
		var err error
		expected, err = normalizeDeviceID(expectedRaw)
		if err != nil {
			t.Fatalf("invalid expected device ID: %v", err)
		}
	}
	ctx, cancel := context.WithTimeout(context.Background(), 6*time.Second)
	defer cancel()
	devices, err := discoverLANDevices(ctx, 4*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	for _, device := range devices {
		if expected == "" || device.DeviceID == expected {
			if device.URL == "" || device.PublicKey == "" || device.Fingerprint == "" {
				t.Fatalf("discovered device is incomplete: %#v", device)
			}
			t.Logf("discovered signed Android device %s at %s", device.DeviceDisplayID, device.URL)
			return
		}
	}
	if expected == "" {
		t.Fatal("no signed Android device was discovered")
	}
	t.Fatalf("expected Android device %s was not discovered; got %#v", formatDeviceID(expected), devices)
}
