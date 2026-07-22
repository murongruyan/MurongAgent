package desktopbridge

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/url"
	"strings"
	"time"
)

const (
	DeviceTunnelProtocolVersion = 2
	DeviceTunnelSubprotocol     = "murong-device-tunnel-v2"
	DeviceTunnelRolePhone       = "phone"
	DeviceTunnelRoleDesktop     = "desktop"
	ConnectionModeDirect        = "direct"
	ConnectionModeDeviceID      = "device_id"
	OfficialDeviceTunnelURL     = "wss://murongagent.rl1.cc/relay/v2/tunnel"
	OfficialDeviceRelayURL      = "wss://murongagent.rl1.cc/relay/v2/device"

	deviceTunnelRoomBytes         = 16
	deviceTunnelSecretBytes       = 32
	deviceTunnelNonceBytes        = 12
	deviceTunnelChunkBytes        = 48 * 1024
	deviceTunnelMaxBodyBytes      = 9 * 1024 * 1024
	deviceTunnelMaxFrameBytes     = 256 * 1024
	deviceTunnelClockSkew         = 5 * time.Minute
	deviceTunnelReplayWindow      = 10 * time.Minute
	deviceTunnelMaxReplayMessages = 4096
)

type deviceTunnelCipherEnvelope struct {
	Version    int    `json:"version"`
	Nonce      string `json:"nonce"`
	Ciphertext string `json:"ciphertext"`
}

type deviceTunnelMessage struct {
	Version   int                 `json:"version"`
	MessageID string              `json:"messageId"`
	RequestID string              `json:"requestId"`
	Kind      string              `json:"kind"`
	IssuedAt  int64               `json:"issuedAt"`
	Method    string              `json:"method,omitempty"`
	Path      string              `json:"path,omitempty"`
	Headers   map[string][]string `json:"headers,omitempty"`
	Status    int                 `json:"status,omitempty"`
	Chunk     string              `json:"chunk,omitempty"`
	Error     string              `json:"error,omitempty"`
}

type deviceTunnelReplayCache struct {
	entries map[string]int64
	order   []string
}

func generateDeviceTunnelRoom() (roomID string, secret []byte, err error) {
	room := make([]byte, deviceTunnelRoomBytes)
	secret = make([]byte, deviceTunnelSecretBytes)
	if _, err = rand.Read(room); err != nil {
		return "", nil, err
	}
	if _, err = rand.Read(secret); err != nil {
		clearBytes(secret)
		return "", nil, err
	}
	roomID = base64.RawURLEncoding.EncodeToString(room)
	return roomID, secret, nil
}

func validateDeviceTunnelRoomID(roomID string) error {
	decoded, err := base64.RawURLEncoding.DecodeString(strings.TrimSpace(roomID))
	if err != nil || len(decoded) != deviceTunnelRoomBytes {
		return errors.New("加密隧道房间 ID 无效")
	}
	return nil
}

func parseDeviceTunnelURL(raw string) (*url.URL, error) {
	target, err := url.Parse(strings.TrimSpace(raw))
	if err != nil {
		return nil, err
	}
	if target.User != nil || target.Hostname() == "" || target.RawQuery != "" || target.Fragment != "" {
		return nil, errors.New("加密隧道地址不能包含账号、查询参数或片段")
	}
	switch strings.ToLower(target.Scheme) {
	case "wss":
	case "ws":
		host := target.Hostname()
		ip := net.ParseIP(host)
		if !strings.EqualFold(host, "localhost") && (ip == nil || !ip.IsLoopback()) {
			return nil, errors.New("公网加密隧道必须使用 wss://；ws:// 仅允许本机测试")
		}
	default:
		return nil, errors.New("加密隧道地址必须使用 wss://")
	}
	if target.Path == "" {
		target.Path = "/v2/tunnel"
	}
	return target, nil
}

func deviceTunnelAAD(roomID, senderRole string) ([]byte, error) {
	if err := validateDeviceTunnelRoomID(roomID); err != nil {
		return nil, err
	}
	if senderRole != DeviceTunnelRolePhone && senderRole != DeviceTunnelRoleDesktop {
		return nil, errors.New("加密隧道发送角色无效")
	}
	return []byte(fmt.Sprintf("%s|%d|%s|%s", DeviceTunnelSubprotocol, DeviceTunnelProtocolVersion, roomID, senderRole)), nil
}

func encryptDeviceTunnelMessage(secret []byte, roomID, senderRole string, message deviceTunnelMessage) ([]byte, error) {
	if len(secret) != deviceTunnelSecretBytes {
		return nil, errors.New("加密隧道端到端密钥长度无效")
	}
	if err := validateDeviceTunnelMessage(message, time.Now()); err != nil {
		return nil, err
	}
	plain, err := json.Marshal(message)
	if err != nil {
		return nil, err
	}
	block, err := aes.NewCipher(secret)
	if err != nil {
		return nil, err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}
	nonce := make([]byte, deviceTunnelNonceBytes)
	if _, err := rand.Read(nonce); err != nil {
		return nil, err
	}
	aad, err := deviceTunnelAAD(roomID, senderRole)
	if err != nil {
		return nil, err
	}
	envelope := deviceTunnelCipherEnvelope{
		Version:    DeviceTunnelProtocolVersion,
		Nonce:      base64.RawURLEncoding.EncodeToString(nonce),
		Ciphertext: base64.RawURLEncoding.EncodeToString(gcm.Seal(nil, nonce, plain, aad)),
	}
	encoded, err := json.Marshal(envelope)
	if err != nil {
		return nil, err
	}
	if len(encoded) > deviceTunnelMaxFrameBytes {
		return nil, errors.New("加密隧道帧超过大小限制")
	}
	return encoded, nil
}

func decryptDeviceTunnelMessage(secret []byte, roomID, senderRole string, encoded []byte, now time.Time) (deviceTunnelMessage, error) {
	var message deviceTunnelMessage
	if len(secret) != deviceTunnelSecretBytes {
		return message, errors.New("加密隧道端到端密钥长度无效")
	}
	if len(encoded) == 0 || len(encoded) > deviceTunnelMaxFrameBytes {
		return message, errors.New("加密隧道帧大小无效")
	}
	var envelope deviceTunnelCipherEnvelope
	decoder := json.NewDecoder(strings.NewReader(string(encoded)))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&envelope); err != nil {
		return message, errors.New("加密隧道帧格式无效")
	}
	if err := requireDeviceTunnelJSONEOF(decoder); err != nil {
		return message, errors.New("加密隧道帧格式无效")
	}
	if envelope.Version != DeviceTunnelProtocolVersion {
		return message, errors.New("加密隧道帧版本不受支持")
	}
	nonce, err := base64.RawURLEncoding.DecodeString(envelope.Nonce)
	if err != nil || len(nonce) != deviceTunnelNonceBytes {
		return message, errors.New("加密隧道随机数无效")
	}
	ciphertext, err := base64.RawURLEncoding.DecodeString(envelope.Ciphertext)
	if err != nil || len(ciphertext) < 16 {
		return message, errors.New("加密隧道密文无效")
	}
	block, err := aes.NewCipher(secret)
	if err != nil {
		return message, err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return message, err
	}
	aad, err := deviceTunnelAAD(roomID, senderRole)
	if err != nil {
		return message, err
	}
	plain, err := gcm.Open(nil, nonce, ciphertext, aad)
	if err != nil {
		return message, errors.New("加密隧道密文认证失败")
	}
	messageDecoder := json.NewDecoder(strings.NewReader(string(plain)))
	messageDecoder.DisallowUnknownFields()
	if err := messageDecoder.Decode(&message); err != nil {
		return deviceTunnelMessage{}, errors.New("加密隧道消息格式无效")
	}
	if err := requireDeviceTunnelJSONEOF(messageDecoder); err != nil {
		return deviceTunnelMessage{}, errors.New("加密隧道消息格式无效")
	}
	if err := validateDeviceTunnelMessage(message, now); err != nil {
		return deviceTunnelMessage{}, err
	}
	return message, nil
}

func requireDeviceTunnelJSONEOF(decoder *json.Decoder) error {
	var trailing any
	if err := decoder.Decode(&trailing); !errors.Is(err, io.EOF) {
		return errors.New("unexpected trailing JSON data")
	}
	return nil
}

func validateDeviceTunnelMessage(message deviceTunnelMessage, now time.Time) error {
	if message.Version != DeviceTunnelProtocolVersion {
		return errors.New("加密隧道消息版本不受支持")
	}
	if !requestIDPattern(message.MessageID) || !requestIDPattern(message.RequestID) {
		return errors.New("加密隧道消息 ID 无效")
	}
	issuedAt := time.UnixMilli(message.IssuedAt)
	if issuedAt.Before(now.Add(-deviceTunnelClockSkew)) || issuedAt.After(now.Add(deviceTunnelClockSkew)) {
		return errors.New("加密隧道消息时间无效")
	}
	switch message.Kind {
	case "request_start":
		if message.Method != "GET" && message.Method != "POST" {
			return errors.New("加密隧道 HTTP 方法无效")
		}
		if !strings.HasPrefix(message.Path, "/api/v1/") || len(message.Path) > 256 || strings.ContainsAny(message.Path, "?#\x00") {
			return errors.New("加密隧道请求路径无效")
		}
		if err := validateDeviceTunnelHeaders(message.Headers, true); err != nil {
			return err
		}
	case "request_chunk", "response_chunk":
		chunk, err := base64.RawURLEncoding.DecodeString(message.Chunk)
		if err != nil || len(chunk) == 0 || len(chunk) > deviceTunnelChunkBytes {
			return errors.New("加密隧道数据块无效")
		}
	case "request_end", "response_end", "cancel":
	case "response_start":
		if message.Status < 100 || message.Status > 599 {
			return errors.New("加密隧道响应状态无效")
		}
		if err := validateDeviceTunnelHeaders(message.Headers, false); err != nil {
			return err
		}
	case "error":
		if strings.TrimSpace(message.Error) == "" || len(message.Error) > 500 {
			return errors.New("加密隧道错误消息无效")
		}
	default:
		return errors.New("加密隧道消息类型无效")
	}
	return nil
}

func validateDeviceTunnelHeaders(headers map[string][]string, request bool) error {
	if len(headers) > 12 {
		return errors.New("加密隧道请求头数量过多")
	}
	allowed := map[string]bool{
		"accept": true, "authorization": true, "content-type": true, "last-event-id": true,
	}
	if !request {
		allowed = map[string]bool{
			"cache-control": true, "content-type": true, "set-cookie": true,
		}
	}
	for name, values := range headers {
		lower := strings.ToLower(strings.TrimSpace(name))
		if !allowed[lower] || len(values) == 0 || len(values) > 4 {
			return errors.New("加密隧道请求头无效")
		}
		for _, value := range values {
			if len(value) > 8192 || strings.ContainsAny(value, "\r\n\x00") {
				return errors.New("加密隧道请求头值无效")
			}
		}
	}
	return nil
}

func filterDeviceTunnelHeaders(headers map[string][]string, request bool) map[string][]string {
	filtered := make(map[string][]string)
	for name, values := range headers {
		candidate := map[string][]string{name: append([]string(nil), values...)}
		if validateDeviceTunnelHeaders(candidate, request) == nil {
			filtered[name] = append([]string(nil), values...)
		}
	}
	return filtered
}

func newDeviceTunnelMessage(requestID, kind string) deviceTunnelMessage {
	return deviceTunnelMessage{
		Version:   DeviceTunnelProtocolVersion,
		MessageID: mustRandomID("relaymsg"),
		RequestID: requestID,
		Kind:      kind,
		IssuedAt:  time.Now().UnixMilli(),
	}
}

func (cache *deviceTunnelReplayCache) claim(messageID string, issuedAt int64, now time.Time) bool {
	if cache.entries == nil {
		cache.entries = make(map[string]int64)
	}
	cutoff := now.Add(-deviceTunnelReplayWindow).UnixMilli()
	for len(cache.order) > 0 {
		oldest := cache.order[0]
		if timestamp, ok := cache.entries[oldest]; ok && timestamp > cutoff && len(cache.entries) < deviceTunnelMaxReplayMessages {
			break
		}
		cache.order = cache.order[1:]
		delete(cache.entries, oldest)
	}
	if _, exists := cache.entries[messageID]; exists {
		return false
	}
	cache.entries[messageID] = issuedAt
	cache.order = append(cache.order, messageID)
	return true
}

func requestIDPattern(value string) bool {
	if len(value) < 8 || len(value) > 96 {
		return false
	}
	for index, char := range value {
		allowed := char >= 'A' && char <= 'Z' || char >= 'a' && char <= 'z' || char >= '0' && char <= '9' || strings.ContainsRune("._:-", char)
		if !allowed || index == 0 && !(char >= 'A' && char <= 'Z' || char >= 'a' && char <= 'z' || char >= '0' && char <= '9') {
			return false
		}
	}
	return true
}
