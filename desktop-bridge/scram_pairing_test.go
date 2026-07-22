package desktopbridge

import "testing"

func TestSCRAMUnicodePasswordMatchesAndroidVector(t *testing.T) {
	request := pairChallengeRequest{
		RequestID:          "connect-vector",
		ClientName:         "Murong Desktop",
		DeviceID:           "DMB77YSEX4BLAFRU",
		DevicePublicKey:    scramVectorPublicKey,
		DeviceFingerprint:  "XNJS-wzokyQ2-vjM0QQJgbie5K1rn-niorfnGqyyfNM",
		EphemeralPublicKey: scramVectorPublicKey,
		Platform:           "windows",
		IssuedAt:           1_784_700_000_000,
		AuthMethod:         "security_password",
		ClientNonce:        "AAECAwQFBgcICQoLDA0ODxAR",
	}
	response := pairChallengeResponse{
		Version:     scramPairingVersion,
		SessionID:   "AQIDBAUGBwgJCgsMDQ4PEA",
		ServerNonce: "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwd",
		Salt:        "AAECAwQFBgcICQoLDA0ODw",
		Iterations:  210_000,
		ExpiresAt:   1_784_700_060_000,
	}
	result, err := scramClientProof("安全密码-Secret123", request, response)
	if err != nil {
		t.Fatal(err)
	}
	if result.authProof != "AQIDBAUGBwgJCgsMDQ4PEA.K_STU2wDd0G4YjGThfQNElLzre25QTcoz7Qgf1fyrJI" {
		t.Fatalf("client proof = %q", result.authProof)
	}
	if result.expectedServerProof != "U45HJI_kHrwMNN5cuYao0dBersJCWLopSb6bcTExlN0" {
		t.Fatalf("server proof = %q", result.expectedServerProof)
	}
	if err := verifySCRAMServerProof(result.expectedServerProof, result.expectedServerProof); err != nil {
		t.Fatal(err)
	}
	if err := verifySCRAMServerProof(result.expectedServerProof, "K45HJI_kHrwMNN5cuYao0dBersJCWLopSb6bcTExlN0"); err == nil {
		t.Fatal("tampered server proof was accepted")
	}
}

const scramVectorPublicKey = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEaxfR8uEsQkf4vOblY6RA8ncDfYEt6zOg9KE5RdiYwpZP40Li_hp_m47n60p8D54WK84zV2sxXs7LtkBoN79R9Q"
