package desktopbridge

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"math/big"
	"testing"
)

func deterministicDeviceIdentity(t *testing.T, scalar int64) *deviceIdentity {
	t.Helper()
	curve := elliptic.P256()
	private := &ecdsa.PrivateKey{PublicKey: ecdsa.PublicKey{Curve: curve}, D: big.NewInt(scalar)}
	private.PublicKey.X, private.PublicKey.Y = curve.ScalarBaseMult(private.D.FillBytes(make([]byte, 32)))
	der, err := x509.MarshalPKIXPublicKey(&private.PublicKey)
	if err != nil {
		t.Fatal(err)
	}
	digest := sha256.Sum256(der)
	return &deviceIdentity{
		privateKey:  private,
		publicDER:   der,
		deviceID:    deviceIDFromPublicDER(der),
		fingerprint: base64.RawURLEncoding.EncodeToString(digest[:]),
	}
}

func TestDeviceIdentityStableVectorAndFormatting(t *testing.T) {
	identity := deterministicDeviceIdentity(t, 1)
	const expectedPublicKey = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEaxfR8uEsQkf4vOblY6RA8ncDfYEt6zOg9KE5RdiYwpZP40Li_hp_m47n60p8D54WK84zV2sxXs7LtkBoN79R9Q"
	if identity.publicKey() != expectedPublicKey || identity.deviceID != "DMB77YSEX4BLAFRU" || identity.fingerprint != "XNJS-wzokyQ2-vjM0QQJgbie5K1rn-niorfnGqyyfNM" {
		t.Fatalf("stable identity vector changed: key=%s id=%s fingerprint=%s", identity.publicKey(), identity.deviceID, identity.fingerprint)
	}
	if len(identity.deviceID) != 16 || identity.displayID() != identity.deviceID[:4]+"-"+identity.deviceID[4:8]+"-"+identity.deviceID[8:12]+"-"+identity.deviceID[12:] {
		t.Fatalf("unexpected device identity formatting: %#v", identity)
	}
	if normalized, err := normalizeDeviceID(identity.displayID()); err != nil || normalized != identity.deviceID {
		t.Fatalf("normalize device id = %q, %v", normalized, err)
	}
}

func TestDeviceIdentitySignsAndDerivesSymmetricLinkSecret(t *testing.T) {
	left := deterministicDeviceIdentity(t, 1)
	right := deterministicDeviceIdentity(t, 2)
	payload := []byte("murong-device-link-register-v1")
	signature, err := left.sign(payload)
	if err != nil || !verifyDeviceSignature(left.publicKey(), payload, signature) {
		t.Fatalf("device signature verification failed: %v", err)
	}
	leftSecret, err := left.deriveLinkSecret(right.publicKey(), []byte("request-12345678"))
	if err != nil {
		t.Fatal(err)
	}
	rightSecret, err := right.deriveLinkSecret(left.publicKey(), []byte("request-12345678"))
	if err != nil {
		t.Fatal(err)
	}
	if string(leftSecret) != string(rightSecret) {
		t.Fatal("ECDH link secrets differ")
	}
	clearBytes(leftSecret)
	clearBytes(rightSecret)
}
