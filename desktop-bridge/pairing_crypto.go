package desktopbridge

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/binary"
	"errors"
	"fmt"
	"strings"
)

const (
	securePairingVersion    = "pbkdf2-sha256-aesgcm-v1"
	pairingCodeLength       = 16
	pairingPBKDF2Iterations = 210_000
	pairingSyncKeyBytes     = 32
	maxPairingTokenBytes    = 128
)

func decryptPairingBootstrap(rawCode string, response pairResponse) (string, []byte, error) {
	envelope := response.SecureChannel
	if envelope == nil || envelope.Version != securePairingVersion {
		return "", nil, errors.New("安全配对协议版本不受支持")
	}
	code := normalizePairingCode(rawCode)
	if len(code) != pairingCodeLength {
		return "", nil, errors.New("配对码长度无效")
	}
	salt, err := decodePairingBase64(envelope.Salt, 16, 16)
	if err != nil {
		return "", nil, fmt.Errorf("salt：%w", err)
	}
	nonce, err := decodePairingBase64(envelope.Nonce, 12, 12)
	if err != nil {
		return "", nil, fmt.Errorf("nonce：%w", err)
	}
	ciphertext, err := decodePairingBase64(envelope.Ciphertext, 2+1+pairingSyncKeyBytes+16, 512)
	if err != nil {
		return "", nil, fmt.Errorf("ciphertext：%w", err)
	}
	key := pbkdf2SHA256([]byte(code), salt, pairingPBKDF2Iterations, 32)
	defer clearBytes(key)
	block, err := aes.NewCipher(key)
	if err != nil {
		return "", nil, err
	}
	aead, err := cipher.NewGCM(block)
	if err != nil {
		return "", nil, err
	}
	plain, err := aead.Open(nil, nonce, ciphertext, pairingAAD(response))
	if err != nil {
		return "", nil, errors.New("配对码或加密响应校验失败")
	}
	defer clearBytes(plain)
	if len(plain) < 2+pairingSyncKeyBytes {
		return "", nil, errors.New("安全配对载荷过短")
	}
	tokenLength := int(binary.BigEndian.Uint16(plain[:2]))
	if tokenLength <= 0 || tokenLength > maxPairingTokenBytes || len(plain) != 2+tokenLength+pairingSyncKeyBytes {
		return "", nil, errors.New("安全配对载荷长度无效")
	}
	token := string(plain[2 : 2+tokenLength])
	if strings.TrimSpace(token) == "" || strings.ContainsAny(token, "\r\n\x00") {
		return "", nil, errors.New("访问凭据无效")
	}
	syncKey := append([]byte(nil), plain[2+tokenLength:]...)
	return token, syncKey, nil
}

func pairingAAD(response pairResponse) []byte {
	return []byte(fmt.Sprintf("%s\n%s\n%s\n%d", securePairingVersion, response.ClientID, response.ClientName, response.CreatedAt))
}

func normalizePairingCode(value string) string {
	return strings.Map(func(r rune) rune {
		if r == '-' || r == ' ' || r == '\t' || r == '\r' || r == '\n' {
			return -1
		}
		if r >= 'a' && r <= 'z' {
			return r - ('a' - 'A')
		}
		return r
	}, value)
}

func pairingCodeProof(rawCode string) (string, error) {
	code := normalizePairingCode(rawCode)
	if len(code) != pairingCodeLength {
		return "", errors.New("配对码长度无效")
	}
	for _, character := range code {
		if !strings.ContainsRune("23456789ABCDEFGHJKLMNPQRSTUVWXYZ", character) {
			return "", errors.New("配对码包含无效字符")
		}
	}
	digest := sha256.Sum256([]byte(code))
	return base64.RawURLEncoding.EncodeToString(digest[:]), nil
}

func decodePairingBase64(value string, minimum, maximum int) ([]byte, error) {
	if value == "" || len(value) > maximum*2 {
		return nil, errors.New("编码长度无效")
	}
	decoded, err := base64.RawURLEncoding.DecodeString(value)
	if err != nil || len(decoded) < minimum || len(decoded) > maximum {
		return nil, errors.New("编码内容无效")
	}
	return decoded, nil
}

// pbkdf2SHA256 is kept local so the single-node binary does not need another
// dependency merely for the pairing bootstrap.
func pbkdf2SHA256(password, salt []byte, iterations, length int) []byte {
	result := make([]byte, 0, length)
	blockIndex := uint32(1)
	for len(result) < length {
		mac := hmac.New(sha256.New, password)
		_, _ = mac.Write(salt)
		var counter [4]byte
		binary.BigEndian.PutUint32(counter[:], blockIndex)
		_, _ = mac.Write(counter[:])
		u := mac.Sum(nil)
		t := append([]byte(nil), u...)
		for i := 1; i < iterations; i++ {
			mac = hmac.New(sha256.New, password)
			_, _ = mac.Write(u)
			u = mac.Sum(nil)
			for j := range t {
				t[j] ^= u[j]
			}
		}
		result = append(result, t...)
		clearBytes(u)
		clearBytes(t)
		blockIndex++
	}
	return result[:length]
}

func clearBytes(value []byte) {
	for index := range value {
		value[index] = 0
	}
}
