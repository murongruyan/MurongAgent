package desktopbridge

import (
	"crypto/ecdh"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"errors"
	"fmt"
	"strings"
)

const (
	deviceIDBytes        = 10
	deviceLinkKeyBytes   = 32
	maxDevicePublicDER   = 512
	maxDeviceSignature   = 256
	maxDeviceSignPayload = 64 * 1024
)

const deviceIDAlphabet = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"

var deviceLinkInfo = []byte("murong-device-link-v1")

type deviceIdentity struct {
	privateKey  *ecdsa.PrivateKey
	publicDER   []byte
	deviceID    string
	fingerprint string
}

func newEphemeralDeviceIdentity() (*deviceIdentity, error) {
	privateKey, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return nil, err
	}
	publicDER, err := x509.MarshalPKIXPublicKey(&privateKey.PublicKey)
	if err != nil {
		return nil, err
	}
	digest := sha256.Sum256(publicDER)
	return &deviceIdentity{
		privateKey:  privateKey,
		publicDER:   publicDER,
		deviceID:    deviceIDFromPublicDER(publicDER),
		fingerprint: base64.RawURLEncoding.EncodeToString(digest[:]),
	}, nil
}

func ensureNodeDeviceIdentity(config *nodeConfig) (*deviceIdentity, error) {
	if config == nil {
		return nil, errors.New("设备配置不能为空")
	}
	var privateKey *ecdsa.PrivateKey
	if strings.TrimSpace(config.ProtectedDevicePrivateKey) != "" {
		plain, err := unprotectSecret(config.ProtectedDevicePrivateKey)
		if err != nil {
			return nil, fmt.Errorf("无法解密本机设备身份，请清除损坏配置后重试：%w", err)
		}
		parsed, parseErr := x509.ParsePKCS8PrivateKey(plain)
		clearBytes(plain)
		if parseErr != nil {
			return nil, fmt.Errorf("本机设备身份格式损坏：%w", parseErr)
		}
		var ok bool
		privateKey, ok = parsed.(*ecdsa.PrivateKey)
		if !ok || privateKey.Curve != elliptic.P256() {
			return nil, errors.New("本机设备身份不是 P-256 密钥")
		}
	} else {
		generated, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
		if err != nil {
			return nil, fmt.Errorf("无法生成本机设备身份：%w", err)
		}
		plain, err := x509.MarshalPKCS8PrivateKey(generated)
		if err != nil {
			return nil, err
		}
		protected, err := protectSecret(plain)
		clearBytes(plain)
		if err != nil {
			return nil, fmt.Errorf("无法使用本机凭据保护设备身份：%w", err)
		}
		config.ProtectedDevicePrivateKey = protected
		privateKey = generated
	}
	publicDER, err := x509.MarshalPKIXPublicKey(&privateKey.PublicKey)
	if err != nil {
		return nil, err
	}
	id := deviceIDFromPublicDER(publicDER)
	digest := sha256.Sum256(publicDER)
	return &deviceIdentity{
		privateKey:  privateKey,
		publicDER:   append([]byte(nil), publicDER...),
		deviceID:    id,
		fingerprint: base64.RawURLEncoding.EncodeToString(digest[:]),
	}, nil
}

func (identity *deviceIdentity) displayID() string {
	if identity == nil {
		return ""
	}
	parts := make([]string, 0, 4)
	for index := 0; index < len(identity.deviceID); index += 4 {
		end := index + 4
		if end > len(identity.deviceID) {
			end = len(identity.deviceID)
		}
		parts = append(parts, identity.deviceID[index:end])
	}
	return strings.Join(parts, "-")
}

func formatDeviceID(raw string) string {
	normalized, err := normalizeDeviceID(raw)
	if err != nil {
		return ""
	}
	parts := make([]string, 0, 4)
	for index := 0; index < len(normalized); index += 4 {
		parts = append(parts, normalized[index:index+4])
	}
	return strings.Join(parts, "-")
}

func (identity *deviceIdentity) publicKey() string {
	if identity == nil {
		return ""
	}
	return base64.RawURLEncoding.EncodeToString(identity.publicDER)
}

func (identity *deviceIdentity) sign(payload []byte) (string, error) {
	if identity == nil || identity.privateKey == nil || len(payload) == 0 || len(payload) > maxDeviceSignPayload {
		return "", errors.New("设备签名内容大小无效")
	}
	digest := sha256.Sum256(payload)
	signature, err := ecdsa.SignASN1(rand.Reader, identity.privateKey, digest[:])
	if err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(signature), nil
}

func verifyDeviceSignature(publicKey string, payload []byte, encodedSignature string) bool {
	if len(payload) == 0 || len(payload) > maxDeviceSignPayload {
		return false
	}
	publicDER, err := decodeBase64URL(publicKey, maxDevicePublicDER)
	if err != nil {
		return false
	}
	parsed, err := x509.ParsePKIXPublicKey(publicDER)
	if err != nil {
		return false
	}
	key, ok := parsed.(*ecdsa.PublicKey)
	if !ok || key.Curve != elliptic.P256() {
		return false
	}
	signature, err := decodeBase64URL(encodedSignature, maxDeviceSignature)
	if err != nil {
		return false
	}
	digest := sha256.Sum256(payload)
	return ecdsa.VerifyASN1(key, digest[:], signature)
}

func deviceIDForPublicKey(publicKey string) (string, error) {
	der, err := decodeBase64URL(publicKey, maxDevicePublicDER)
	if err != nil {
		return "", err
	}
	parsed, err := x509.ParsePKIXPublicKey(der)
	if err != nil {
		return "", err
	}
	key, ok := parsed.(*ecdsa.PublicKey)
	if !ok || key.Curve != elliptic.P256() {
		return "", errors.New("设备公钥不是 P-256")
	}
	canonical, err := x509.MarshalPKIXPublicKey(key)
	if err != nil {
		return "", err
	}
	return deviceIDFromPublicDER(canonical), nil
}

func normalizeDeviceID(raw string) (string, error) {
	clean := strings.ToUpper(strings.NewReplacer("-", "", " ", "", "\t", "", "\r", "", "\n", "").Replace(raw))
	if len(clean) != deviceIDBytes*8/5 {
		return "", errors.New("本机 ID 长度无效")
	}
	for _, char := range clean {
		if !strings.ContainsRune(deviceIDAlphabet, char) {
			return "", errors.New("本机 ID 格式无效")
		}
	}
	return clean, nil
}

func (identity *deviceIdentity) deriveLinkSecret(peerPublicKey string, context []byte) ([]byte, error) {
	if identity == nil || identity.privateKey == nil || len(context) == 0 || len(context) > 1024 {
		return nil, errors.New("设备链路上下文无效")
	}
	peerDER, err := decodeBase64URL(peerPublicKey, maxDevicePublicDER)
	if err != nil {
		return nil, err
	}
	parsed, err := x509.ParsePKIXPublicKey(peerDER)
	if err != nil {
		return nil, err
	}
	peerECDSA, ok := parsed.(*ecdsa.PublicKey)
	if !ok || peerECDSA.Curve != elliptic.P256() {
		return nil, errors.New("对端设备公钥不是 P-256")
	}
	privateBytes := identity.privateKey.D.FillBytes(make([]byte, 32))
	privateECDH, err := ecdh.P256().NewPrivateKey(privateBytes)
	clearBytes(privateBytes)
	if err != nil {
		return nil, err
	}
	peerBytes := elliptic.Marshal(elliptic.P256(), peerECDSA.X, peerECDSA.Y)
	peerECDH, err := ecdh.P256().NewPublicKey(peerBytes)
	if err != nil {
		return nil, err
	}
	shared, err := privateECDH.ECDH(peerECDH)
	if err != nil {
		return nil, err
	}
	defer clearBytes(shared)
	return hkdfSHA256(shared, context, deviceLinkInfo, deviceLinkKeyBytes), nil
}

func deviceIDFromPublicDER(publicDER []byte) string {
	digest := sha256.Sum256(publicDER)
	return encodeDeviceBase32(digest[:deviceIDBytes])
}

func encodeDeviceBase32(value []byte) string {
	var output strings.Builder
	buffer := uint64(0)
	bits := uint(0)
	for _, valueByte := range value {
		buffer = (buffer << 8) | uint64(valueByte)
		bits += 8
		for bits >= 5 {
			bits -= 5
			output.WriteByte(deviceIDAlphabet[(buffer>>bits)&31])
		}
	}
	if bits > 0 {
		output.WriteByte(deviceIDAlphabet[(buffer<<(5-bits))&31])
	}
	return output.String()
}

func hkdfSHA256(inputKeyMaterial, salt, info []byte, size int) []byte {
	extract := hmac.New(sha256.New, salt)
	_, _ = extract.Write(inputKeyMaterial)
	pseudoRandomKey := extract.Sum(nil)
	defer clearBytes(pseudoRandomKey)
	result := make([]byte, 0, size)
	previous := []byte(nil)
	for counter := byte(1); len(result) < size; counter++ {
		expand := hmac.New(sha256.New, pseudoRandomKey)
		_, _ = expand.Write(previous)
		_, _ = expand.Write(info)
		_, _ = expand.Write([]byte{counter})
		previous = expand.Sum(nil)
		result = append(result, previous...)
	}
	clearBytes(previous)
	return result[:size]
}

func decodeBase64URL(value string, maxBytes int) ([]byte, error) {
	if strings.TrimSpace(value) == "" || len(value) > maxBytes*2 {
		return nil, errors.New("设备密钥编码无效")
	}
	decoded, err := base64.RawURLEncoding.DecodeString(value)
	if err != nil || len(decoded) == 0 || len(decoded) > maxBytes {
		return nil, errors.New("设备密钥编码无效")
	}
	return decoded, nil
}
