package desktopbridge

import (
	"context"
	"crypto/aes"
	"crypto/cipher"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"net/url"
	"testing"
	"time"
)

func TestSecurePairingBootstrapRoundTripAndTamperDetection(t *testing.T) {
	code := "2345-6789-ABCD-EFGH"
	response := pairResponse{ClientID: "12345678-1234-1234-1234-123456789abc", ClientName: "Murong Windows", CreatedAt: 123456789}
	salt := make([]byte, 16)
	nonce := make([]byte, 12)
	for index := range salt {
		salt[index] = byte(index)
	}
	for index := range nonce {
		nonce[index] = byte(16 + index)
	}
	token := "pairing-token-that-is-never-sent-in-plaintext"
	syncKey := make([]byte, pairingSyncKeyBytes)
	for index := range syncKey {
		syncKey[index] = byte(index + 1)
	}
	response.SecureChannel = encryptPairingBootstrapForTest(t, code, response, salt, nonce, token, syncKey)

	actualToken, actualKey, err := decryptPairingBootstrap(code, response)
	if err != nil {
		t.Fatal(err)
	}
	if actualToken != token || !equalBytes(actualKey, syncKey) {
		t.Fatal("secure pairing bootstrap did not round-trip")
	}

	tampered := response
	tamperedEnvelope := *response.SecureChannel
	ciphertext, _ := base64.RawURLEncoding.DecodeString(tamperedEnvelope.Ciphertext)
	ciphertext[len(ciphertext)-1] ^= 0x01
	tamperedEnvelope.Ciphertext = base64.RawURLEncoding.EncodeToString(ciphertext)
	tampered.SecureChannel = &tamperedEnvelope
	if _, _, err := decryptPairingBootstrap(code, tampered); err == nil {
		t.Fatal("tampered pairing bootstrap was accepted")
	}
}

func TestSecurePairRequestSendsProofNotRawPairingCode(t *testing.T) {
	code := "2345-6789-ABCD-EFGH"
	requests := make(chan map[string]string, 1)
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		var body map[string]string
		_ = json.NewDecoder(request.Body).Decode(&body)
		requests <- body
		response := pairResponse{ClientID: "12345678-1234-1234-1234-123456789abc", ClientName: "Murong Windows", CreatedAt: 123456789}
		syncKey := make([]byte, pairingSyncKeyBytes)
		response.SecureChannel = encryptPairingBootstrapForTest(
			t, code, response, make([]byte, 16), make([]byte, 12), "access-token", syncKey,
		)
		writer.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(writer).Encode(response)
	}))
	defer server.Close()
	base, err := url.Parse(server.URL)
	if err != nil {
		t.Fatal(err)
	}
	api := newAPIClient(base)
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if _, _, err := api.pair(ctx, code, "Murong Windows"); err != nil {
		t.Fatal(err)
	}
	body := <-requests
	if _, exposed := body["code"]; exposed {
		t.Fatal("secure pairing request exposed the raw pairing code")
	}
	expectedProof, err := pairingCodeProof(code)
	if err != nil {
		t.Fatal(err)
	}
	if body["codeProof"] != expectedProof || body["codeProof"] == normalizePairingCode(code) {
		t.Fatalf("unexpected pairing proof: %#v", body)
	}
}

func encryptPairingBootstrapForTest(
	t *testing.T,
	code string,
	response pairResponse,
	salt, nonce []byte,
	token string,
	syncKey []byte,
) *securePairingEnvelope {
	t.Helper()
	plain := make([]byte, 2+len(token)+len(syncKey))
	binary.BigEndian.PutUint16(plain[:2], uint16(len(token)))
	copy(plain[2:], token)
	copy(plain[2+len(token):], syncKey)
	key := pbkdf2SHA256([]byte(normalizePairingCode(code)), salt, pairingPBKDF2Iterations, 32)
	block, err := aes.NewCipher(key)
	if err != nil {
		t.Fatal(err)
	}
	aead, err := cipher.NewGCM(block)
	if err != nil {
		t.Fatal(err)
	}
	ciphertext := aead.Seal(nil, nonce, plain, pairingAAD(response))
	return &securePairingEnvelope{
		Version: securePairingVersion, Salt: base64.RawURLEncoding.EncodeToString(salt),
		Nonce: base64.RawURLEncoding.EncodeToString(nonce), Ciphertext: base64.RawURLEncoding.EncodeToString(ciphertext),
	}
}

func equalBytes(left, right []byte) bool {
	if len(left) != len(right) {
		return false
	}
	var difference byte
	for index := range left {
		difference |= left[index] ^ right[index]
	}
	return difference == 0
}
