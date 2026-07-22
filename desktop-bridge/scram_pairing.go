package desktopbridge

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"errors"
	"fmt"
	"strings"
)

const (
	scramPairingVersion            = "scram-sha-256-v1"
	scramPairChallengePath         = "/api/v1/pair/challenge"
	scramIterations                = 210_000
	scramSaltBytes                 = 16
	scramProofBytes                = 32
	scramNonceBytes                = 18
	connectionTemporaryCodeAuth    = "temporary_code"
	connectionSecurityPasswordAuth = "security_password"
)

type pairChallengeRequest struct {
	RequestID          string `json:"requestId"`
	ClientName         string `json:"clientName"`
	DeviceID           string `json:"deviceId"`
	DevicePublicKey    string `json:"devicePublicKey"`
	DeviceFingerprint  string `json:"deviceFingerprint"`
	EphemeralPublicKey string `json:"ephemeralPublicKey"`
	Platform           string `json:"platform"`
	IssuedAt           int64  `json:"issuedAt"`
	AuthMethod         string `json:"authMethod"`
	ClientNonce        string `json:"clientNonce"`
	Signature          string `json:"signature"`
}

type pairChallengeResponse struct {
	Version     string `json:"version"`
	SessionID   string `json:"sessionId"`
	ServerNonce string `json:"serverNonce"`
	Salt        string `json:"salt"`
	Iterations  int    `json:"iterations"`
	ExpiresAt   int64  `json:"expiresAt"`
}

type scramClientResult struct {
	authProof           string
	expectedServerProof string
}

func newSCRAMClientNonce() (string, error) {
	value := make([]byte, scramNonceBytes)
	if _, err := rand.Read(value); err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(value), nil
}

func scramClientProof(secret string, request pairChallengeRequest, response pairChallengeResponse) (scramClientResult, error) {
	if response.Version != scramPairingVersion || response.Iterations < 100_000 || response.Iterations > 1_000_000 {
		return scramClientResult{}, errors.New("手机返回了不受支持的密码认证参数")
	}
	if !strings.HasPrefix(response.ServerNonce, request.ClientNonce) || len(response.ServerNonce) <= len(request.ClientNonce) {
		return scramClientResult{}, errors.New("手机返回的 SCRAM 随机数无效")
	}
	salt, err := decodeBase64URL(response.Salt, scramSaltBytes)
	if err != nil || len(salt) != scramSaltBytes {
		return scramClientResult{}, errors.New("手机返回的 SCRAM 盐无效")
	}
	normalizedSecret, err := normalizeSCRAMSecret(secret, request.AuthMethod)
	if err != nil {
		return scramClientResult{}, err
	}
	password := []byte(normalizedSecret)
	saltedPassword := pbkdf2SHA256(password, salt, response.Iterations, scramProofBytes)
	clearBytes(password)
	clearBytes(salt)
	defer clearBytes(saltedPassword)
	clientKey := scramHMAC(saltedPassword, []byte("Client Key"))
	storedKey := sha256.Sum256(clientKey)
	authMessage := scramAuthMessage(request, response)
	clientSignature := scramHMAC(storedKey[:], authMessage)
	proof := make([]byte, len(clientKey))
	for index := range proof {
		proof[index] = clientKey[index] ^ clientSignature[index]
	}
	clearBytes(clientKey)
	clearBytes(clientSignature)
	serverKey := scramHMAC(saltedPassword, []byte("Server Key"))
	serverProof := scramHMAC(serverKey, authMessage)
	clearBytes(serverKey)
	defer clearBytes(proof)
	defer clearBytes(serverProof)
	return scramClientResult{
		authProof:           response.SessionID + "." + base64.RawURLEncoding.EncodeToString(proof),
		expectedServerProof: base64.RawURLEncoding.EncodeToString(serverProof),
	}, nil
}

func normalizeSCRAMSecret(secret, authMethod string) (string, error) {
	if authMethod == connectionTemporaryCodeAuth {
		code := normalizePairingCode(secret)
		if len(code) != pairingCodeLength {
			return "", errors.New("临时验证码格式无效")
		}
		return code, nil
	}
	password := strings.TrimSpace(secret)
	length := len([]rune(password))
	if authMethod != connectionSecurityPasswordAuth || length < 8 || length > 128 {
		return "", errors.New("安全密码需为 8–128 个字符")
	}
	for _, value := range password {
		if value < 0x20 || value == 0x7f {
			return "", errors.New("安全密码不能包含控制字符")
		}
	}
	return password, nil
}

func verifySCRAMServerProof(expected, actual string) error {
	expectedBytes, err := decodeBase64URL(expected, scramProofBytes)
	if err != nil || len(expectedBytes) != scramProofBytes {
		return errors.New("本机 SCRAM 服务端证明无效")
	}
	defer clearBytes(expectedBytes)
	actualBytes, err := decodeBase64URL(actual, scramProofBytes)
	if err != nil || len(actualBytes) != scramProofBytes {
		return errors.New("手机未返回有效的密码认证证明")
	}
	defer clearBytes(actualBytes)
	if !hmac.Equal(expectedBytes, actualBytes) {
		return errors.New("手机密码认证证明不匹配")
	}
	return nil
}

func scramAuthMessage(request pairChallengeRequest, response pairChallengeResponse) []byte {
	return []byte(fmt.Sprintf("%s\n%s\n%s\n%s\n%s\n%s\n%s\n%s\n%s\n%s\n%d\n%s\n%s\n%s\n%d",
		scramPairingVersion, response.SessionID, request.RequestID, request.AuthMethod,
		strings.TrimSpace(request.ClientName), request.DeviceID, request.DevicePublicKey,
		request.DeviceFingerprint, request.EphemeralPublicKey, request.Platform, request.IssuedAt,
		request.ClientNonce, response.ServerNonce, response.Salt, response.Iterations))
}

func pairChallengeSignaturePayload(request pairChallengeRequest) []byte {
	return []byte(fmt.Sprintf("murong-pair-challenge-v1\n%s\n%s\n%s\n%s\n%s\n%s\n%s\n%d\n%s\n%s",
		request.RequestID, strings.TrimSpace(request.ClientName), request.DeviceID, request.DevicePublicKey,
		request.DeviceFingerprint, request.EphemeralPublicKey, request.Platform, request.IssuedAt,
		request.AuthMethod, request.ClientNonce))
}

func scramHMAC(key, value []byte) []byte {
	mac := hmac.New(sha256.New, key)
	_, _ = mac.Write(value)
	return mac.Sum(nil)
}
