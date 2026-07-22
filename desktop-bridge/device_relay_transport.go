package desktopbridge

import (
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/url"
	"strings"
	"time"

	"github.com/coder/websocket"
)

const (
	deviceRelayProtocolVersion = 2
	deviceRelaySubprotocol     = "murong-device-relay-v2"
	deviceRelayMaxFrameBytes   = 32 * 1024
	deviceRelayClockWindow     = 2 * time.Minute
	deviceRelayInviteLifetime  = 90 * time.Second
	deviceRelayPlatformDesktop = "desktop"
)

type deviceRelayMessage struct {
	Version            int    `json:"version"`
	Kind               string `json:"kind"`
	RequestID          string `json:"requestId,omitempty"`
	DeviceID           string `json:"deviceId,omitempty"`
	DevicePublicKey    string `json:"devicePublicKey,omitempty"`
	Platform           string `json:"platform,omitempty"`
	Role               string `json:"role,omitempty"`
	DeviceName         string `json:"deviceName,omitempty"`
	AuthMethod         string `json:"authMethod,omitempty"`
	ClientNonce        string `json:"clientNonce,omitempty"`
	SessionID          string `json:"sessionId,omitempty"`
	ServerNonce        string `json:"serverNonce,omitempty"`
	Salt               string `json:"salt,omitempty"`
	Iterations         int    `json:"iterations,omitempty"`
	ExpiresAt          int64  `json:"expiresAt,omitempty"`
	AuthProof          string `json:"authProof,omitempty"`
	ServerProof        string `json:"serverProof,omitempty"`
	SourceDeviceID     string `json:"sourceDeviceId,omitempty"`
	SourcePublicKey    string `json:"sourcePublicKey,omitempty"`
	TargetDeviceID     string `json:"targetDeviceId,omitempty"`
	EphemeralPublicKey string `json:"ephemeralPublicKey,omitempty"`
	IssuedAt           int64  `json:"issuedAt"`
	Nonce              string `json:"nonce,omitempty"`
	Ciphertext         string `json:"ciphertext,omitempty"`
	Status             string `json:"status,omitempty"`
	Error              string `json:"error,omitempty"`
	Signature          string `json:"signature,omitempty"`
}

type deviceRelayInvitation struct {
	Version   int    `json:"version"`
	RoomID    string `json:"roomId"`
	Secret    string `json:"secret"`
	ExpiresAt int64  `json:"expiresAt"`
}

type deviceRelayNegotiation struct {
	RelayURL        *url.URL
	RoomID          string
	Secret          []byte
	PeerDeviceID    string
	PeerPublicKey   string
	PeerFingerprint string
}

func negotiateDeviceRelay(
	ctx context.Context,
	relayURL *url.URL,
	identity *deviceIdentity,
	targetDeviceID string,
) (deviceRelayNegotiation, error) {
	var result deviceRelayNegotiation
	if relayURL == nil || identity == nil {
		return result, errors.New("公网本机 ID 连接参数无效")
	}
	targetDeviceID, err := normalizeDeviceID(targetDeviceID)
	if err != nil {
		return result, fmt.Errorf("目标本机 ID 无效：%w", err)
	}
	if targetDeviceID == identity.deviceID {
		return result, errors.New("不能连接当前电脑自己的本机 ID")
	}
	dialContext, cancel := context.WithTimeout(ctx, 20*time.Second)
	defer cancel()
	connection, response, err := websocket.Dial(dialContext, relayURL.String(), &websocket.DialOptions{
		Subprotocols: []string{deviceRelaySubprotocol},
	})
	if err != nil {
		if response != nil {
			return result, fmt.Errorf("公网设备中继返回 HTTP %d：%w", response.StatusCode, err)
		}
		return result, fmt.Errorf("无法连接公网设备中继：%w", err)
	}
	defer connection.Close(websocket.StatusNormalClosure, "")
	connection.SetReadLimit(deviceRelayMaxFrameBytes)

	registrationNonce, err := randomBase64URL(16)
	if err != nil {
		return result, err
	}
	registration := deviceRelayMessage{
		Version: deviceRelayProtocolVersion, Kind: "register",
		DeviceID: identity.deviceID, DevicePublicKey: identity.publicKey(), Platform: deviceRelayPlatformDesktop,
		Role: "session", IssuedAt: time.Now().UnixMilli(), Nonce: registrationNonce,
	}
	registration.Signature, err = identity.sign(deviceRelayRegistrationPayload(registration))
	if err != nil {
		return result, err
	}
	if err := writeDeviceRelayMessage(dialContext, connection, registration); err != nil {
		return result, fmt.Errorf("无法注册电脑本机 ID：%w", err)
	}
	registered, err := readDeviceRelayMessage(dialContext, connection)
	if err != nil || registered.Kind != "registered" || registered.DeviceID != identity.deviceID ||
		registered.DevicePublicKey != identity.publicKey() || !validDeviceRelayClock(registered.IssuedAt, time.Now()) {
		if err == nil {
			err = errors.New("设备中继注册响应无效")
		}
		return result, err
	}

	lookup := deviceRelayMessage{
		Version: deviceRelayProtocolVersion, Kind: "lookup", RequestID: mustRandomID("lookup"),
		SourceDeviceID: identity.deviceID, TargetDeviceID: targetDeviceID, IssuedAt: time.Now().UnixMilli(),
	}
	lookup.Signature, err = identity.sign(deviceRelayLookupPayload(lookup))
	if err != nil {
		return result, err
	}
	if err := writeDeviceRelayMessage(dialContext, connection, lookup); err != nil {
		return result, err
	}
	peer, err := waitForDeviceRelayMessage(dialContext, connection, lookup.RequestID, "peer")
	if err != nil {
		return result, fmt.Errorf("目标设备不在线或本机 ID 不正确：%w", err)
	}
	peerID, err := deviceIDForPublicKey(peer.DevicePublicKey)
	if err != nil || peer.DeviceID != targetDeviceID || peerID != targetDeviceID ||
		!validDeviceRelayClock(peer.IssuedAt, time.Now()) {
		return result, errors.New("设备中继返回的目标身份无效")
	}
	peerDER, err := decodeBase64URL(peer.DevicePublicKey, maxDevicePublicDER)
	if err != nil {
		return result, errors.New("设备中继返回的目标公钥无效")
	}
	peerFingerprint := fingerprintForPublicDER(peerDER)

	ephemeral, err := newEphemeralDeviceIdentity()
	if err != nil {
		return result, err
	}
	roomID, secret, err := generateDeviceTunnelRoom()
	if err != nil {
		return result, err
	}
	keepSecret := false
	defer func() {
		if !keepSecret {
			clearBytes(secret)
		}
	}()
	requestID := mustRandomID("invite")
	contextBytes := deviceRelayInviteContext(requestID, identity.deviceID, targetDeviceID)
	linkSecret, err := ephemeral.deriveLinkSecret(peer.DevicePublicKey, contextBytes)
	if err != nil {
		return result, fmt.Errorf("无法建立设备邀请密钥：%w", err)
	}
	defer clearBytes(linkSecret)
	invitation := deviceRelayInvitation{
		Version: 2, RoomID: roomID,
		Secret:    base64.RawURLEncoding.EncodeToString(secret),
		ExpiresAt: time.Now().Add(deviceRelayInviteLifetime).UnixMilli(),
	}
	nonce, ciphertext, err := encryptDeviceRelayInvitation(
		linkSecret,
		deviceRelayInviteAAD(requestID, identity.deviceID, targetDeviceID, ephemeral.publicKey()),
		invitation,
	)
	if err != nil {
		return result, err
	}
	invite := deviceRelayMessage{
		Version: deviceRelayProtocolVersion, Kind: "invite", RequestID: requestID,
		SourceDeviceID: identity.deviceID, SourcePublicKey: identity.publicKey(),
		TargetDeviceID: targetDeviceID, EphemeralPublicKey: ephemeral.publicKey(),
		IssuedAt: time.Now().UnixMilli(), Nonce: nonce, Ciphertext: ciphertext,
	}
	invite.Signature, err = identity.sign(deviceRelayInvitePayload(invite))
	if err != nil {
		return result, err
	}
	if err := writeDeviceRelayMessage(dialContext, connection, invite); err != nil {
		return result, err
	}
	acknowledgement, err := waitForDeviceRelayMessage(dialContext, connection, requestID, "invite_ack")
	if err != nil {
		return result, fmt.Errorf("手机没有接受公网连接邀请：%w", err)
	}
	if acknowledgement.SourceDeviceID != targetDeviceID || acknowledgement.TargetDeviceID != identity.deviceID ||
		acknowledgement.Status != "accepted" || !validDeviceRelayClock(acknowledgement.IssuedAt, time.Now()) ||
		!verifyDeviceSignature(peer.DevicePublicKey, deviceRelayInviteAckPayload(acknowledgement), acknowledgement.Signature) {
		if acknowledgement.Status == "rejected" && strings.TrimSpace(acknowledgement.Error) != "" {
			return result, errors.New("手机拒绝公网连接邀请：" + truncateRunes(acknowledgement.Error, 120))
		}
		return result, errors.New("手机返回的公网连接确认无效")
	}
	cloudURL, err := deviceTunnelURLForDeviceRelay(relayURL)
	if err != nil {
		return result, err
	}
	keepSecret = true
	return deviceRelayNegotiation{
		RelayURL: cloudURL, RoomID: roomID, Secret: secret,
		PeerDeviceID: targetDeviceID, PeerPublicKey: peer.DevicePublicKey, PeerFingerprint: peerFingerprint,
	}, nil
}

func waitForDeviceRelayMessage(ctx context.Context, connection *websocket.Conn, requestID, kind string) (deviceRelayMessage, error) {
	for {
		message, err := readDeviceRelayMessage(ctx, connection)
		if err != nil {
			return deviceRelayMessage{}, err
		}
		if message.RequestID != requestID {
			continue
		}
		if message.Kind == "error" {
			return deviceRelayMessage{}, errors.New("目标设备不可用")
		}
		if message.Kind == kind {
			return message, nil
		}
	}
}

func parseDeviceRelayURL(raw string) (*url.URL, error) {
	target, err := parseDeviceTunnelURL(raw)
	if err != nil {
		return nil, err
	}
	if target.Path == "" || target.Path == "/" || target.Path == "/v2/tunnel" {
		target.Path = "/relay/v2/device"
	}
	return target, nil
}

func deviceTunnelURLForDeviceRelay(deviceURL *url.URL) (*url.URL, error) {
	if deviceURL == nil {
		return nil, errors.New("公网设备中继地址为空")
	}
	result := *deviceURL
	result.RawQuery = ""
	result.Fragment = ""
	if strings.HasSuffix(result.Path, "/relay/v2/device") {
		result.Path = strings.TrimSuffix(result.Path, "/relay/v2/device") + "/relay/v2/tunnel"
	} else if strings.HasSuffix(result.Path, "/v2/device") {
		result.Path = strings.TrimSuffix(result.Path, "/v2/device") + "/v2/tunnel"
	} else {
		return nil, errors.New("公网设备中继路径必须以 /relay/v2/device 结尾")
	}
	return parseDeviceTunnelURL(result.String())
}

func encryptDeviceRelayInvitation(secret, aad []byte, invitation deviceRelayInvitation) (string, string, error) {
	if len(secret) != 32 || len(aad) == 0 || invitation.Version != 2 {
		return "", "", errors.New("设备邀请加密参数无效")
	}
	plain, err := json.Marshal(invitation)
	if err != nil {
		return "", "", err
	}
	block, err := aes.NewCipher(secret)
	if err != nil {
		return "", "", err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return "", "", err
	}
	nonce := make([]byte, gcm.NonceSize())
	if _, err := rand.Read(nonce); err != nil {
		return "", "", err
	}
	ciphertext := gcm.Seal(nil, nonce, plain, aad)
	return base64.RawURLEncoding.EncodeToString(nonce), base64.RawURLEncoding.EncodeToString(ciphertext), nil
}

func decryptDeviceRelayInvitation(secret, aad []byte, nonceText, ciphertextText string) (deviceRelayInvitation, error) {
	var invitation deviceRelayInvitation
	if len(secret) != 32 || len(aad) == 0 {
		return invitation, errors.New("设备邀请解密参数无效")
	}
	nonce, err := decodeBase64URL(nonceText, 12)
	if err != nil || len(nonce) != 12 {
		return invitation, errors.New("设备邀请随机数无效")
	}
	ciphertext, err := decodeBase64URL(ciphertextText, 4096)
	if err != nil || len(ciphertext) < 17 {
		return invitation, errors.New("设备邀请密文无效")
	}
	block, err := aes.NewCipher(secret)
	if err != nil {
		return invitation, err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return invitation, err
	}
	plain, err := gcm.Open(nil, nonce, ciphertext, aad)
	if err != nil {
		return invitation, errors.New("设备邀请认证失败")
	}
	decoder := json.NewDecoder(strings.NewReader(string(plain)))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&invitation); err != nil {
		return deviceRelayInvitation{}, errors.New("设备邀请格式无效")
	}
	if err := decoder.Decode(&struct{}{}); !errors.Is(err, io.EOF) {
		return deviceRelayInvitation{}, errors.New("设备邀请格式无效")
	}
	return invitation, nil
}

func writeDeviceRelayMessage(ctx context.Context, connection *websocket.Conn, message deviceRelayMessage) error {
	encoded, err := json.Marshal(message)
	if err != nil || len(encoded) > deviceRelayMaxFrameBytes {
		return errors.New("公网设备中继消息过大")
	}
	return connection.Write(ctx, websocket.MessageText, encoded)
}

func readDeviceRelayMessage(ctx context.Context, connection *websocket.Conn) (deviceRelayMessage, error) {
	messageType, encoded, err := connection.Read(ctx)
	if err != nil {
		return deviceRelayMessage{}, err
	}
	if messageType != websocket.MessageText || len(encoded) == 0 || len(encoded) > deviceRelayMaxFrameBytes {
		return deviceRelayMessage{}, errors.New("公网设备中继消息格式无效")
	}
	decoder := json.NewDecoder(strings.NewReader(string(encoded)))
	decoder.DisallowUnknownFields()
	var message deviceRelayMessage
	if err := decoder.Decode(&message); err != nil {
		return deviceRelayMessage{}, errors.New("公网设备中继消息格式无效")
	}
	if err := decoder.Decode(&struct{}{}); !errors.Is(err, io.EOF) {
		return deviceRelayMessage{}, errors.New("公网设备中继消息格式无效")
	}
	if message.Version != deviceRelayProtocolVersion {
		return deviceRelayMessage{}, errors.New("公网设备中继协议版本不受支持")
	}
	return message, nil
}

func deviceRelayRegistrationPayload(message deviceRelayMessage) []byte {
	return []byte(fmt.Sprintf("murong-relay-device-register-v2\n%s\n%s\n%s\n%s\n%d\n%s",
		message.DeviceID, message.DevicePublicKey, message.Platform, message.Role, message.IssuedAt, message.Nonce))
}

func deviceRelayLookupPayload(message deviceRelayMessage) []byte {
	return []byte(fmt.Sprintf("murong-relay-device-lookup-v2\n%s\n%s\n%s\n%d",
		message.RequestID, message.SourceDeviceID, message.TargetDeviceID, message.IssuedAt))
}

func deviceRelayInvitePayload(message deviceRelayMessage) []byte {
	return []byte(fmt.Sprintf("murong-relay-device-invite-v2\n%s\n%s\n%s\n%s\n%s\n%d\n%s\n%s",
		message.RequestID, message.SourceDeviceID, message.SourcePublicKey, message.TargetDeviceID,
		message.EphemeralPublicKey, message.IssuedAt, message.Nonce, message.Ciphertext))
}

func deviceRelayInviteAckPayload(message deviceRelayMessage) []byte {
	return []byte(fmt.Sprintf("murong-relay-device-invite-ack-v2\n%s\n%s\n%s\n%s\n%d\n%s",
		message.RequestID, message.SourceDeviceID, message.TargetDeviceID, message.Status, message.IssuedAt, message.Error))
}

func deviceRelayConnectRequestPayload(message deviceRelayMessage) []byte {
	return []byte(fmt.Sprintf("murong-relay-device-connect-request-v2\n%s\n%s\n%s\n%s\n%s\n%s\n%s\n%d\n%s\n%s\n%s",
		message.RequestID, message.SourceDeviceID, message.SourcePublicKey, message.TargetDeviceID,
		message.EphemeralPublicKey, message.DeviceName, message.Platform, message.IssuedAt,
		message.AuthMethod, message.ClientNonce, message.AuthProof))
}

func deviceRelayConnectAckPayload(message deviceRelayMessage) []byte {
	return []byte(fmt.Sprintf("murong-relay-device-connect-ack-v2\n%s\n%s\n%s\n%s\n%d\n%s\n%s",
		message.RequestID, message.SourceDeviceID, message.TargetDeviceID, message.Status,
		message.IssuedAt, message.Error, message.ServerProof))
}

func deviceRelayAuthBeginPayload(message deviceRelayMessage) []byte {
	return []byte(fmt.Sprintf("murong-relay-device-auth-begin-v2\n%s\n%s\n%s\n%s\n%s\n%s\n%d\n%s\n%s",
		message.RequestID, message.SourceDeviceID, message.SourcePublicKey, message.TargetDeviceID,
		message.DeviceName, message.Platform, message.IssuedAt, message.AuthMethod, message.ClientNonce))
}

func deviceRelayAuthChallengePayload(message deviceRelayMessage) []byte {
	return []byte(fmt.Sprintf("murong-relay-device-auth-challenge-v2\n%s\n%s\n%s\n%s\n%s\n%s\n%s\n%d\n%d\n%s",
		message.RequestID, message.SourceDeviceID, message.TargetDeviceID, message.AuthMethod,
		message.ClientNonce, message.SessionID, message.ServerNonce, message.Iterations, message.ExpiresAt, message.Salt))
}

func deviceRelayInviteContext(requestID, sourceID, targetID string) []byte {
	return []byte(fmt.Sprintf("murong-relay-v2-invite-context-v2\n%s\n%s\n%s", requestID, sourceID, targetID))
}

func deviceRelayInviteAAD(requestID, sourceID, targetID, ephemeralPublicKey string) []byte {
	return []byte(fmt.Sprintf("murong-relay-v2-invite-aad-v2\n%s\n%s\n%s\n%s",
		requestID, sourceID, targetID, ephemeralPublicKey))
}

func validDeviceRelayClock(value int64, now time.Time) bool {
	timestamp := time.UnixMilli(value)
	return !timestamp.Before(now.Add(-deviceRelayClockWindow)) && !timestamp.After(now.Add(30*time.Second))
}

func randomBase64URL(size int) (string, error) {
	if size < 1 || size > 1024 {
		return "", errors.New("随机数长度无效")
	}
	value := make([]byte, size)
	if _, err := rand.Read(value); err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(value), nil
}

func fingerprintForPublicDER(publicDER []byte) string {
	digest := sha256Sum(publicDER)
	return base64.RawURLEncoding.EncodeToString(digest)
}

func sha256Sum(value []byte) []byte {
	digest := sha256.Sum256(value)
	return digest[:]
}
