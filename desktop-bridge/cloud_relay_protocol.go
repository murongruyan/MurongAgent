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
	CloudRelayProtocolVersion  = 1
	CloudRelaySubprotocol      = "murong-cloud-relay-v1"
	CloudRelayRolePhone        = "phone"
	CloudRelayRoleDesktop      = "desktop"
	CloudRelayConnectionDirect = "direct"
	CloudRelayConnectionCloud  = "cloud_relay"
	OfficialCloudRelayURL      = "wss://murongagent.rl1.cc/relay/v1/connect"

	cloudRelaySharePrefix       = "MR1"
	cloudRelayRoomBytes         = 16
	cloudRelaySecretBytes       = 32
	cloudRelayNonceBytes        = 12
	cloudRelayChunkBytes        = 48 * 1024
	cloudRelayMaxBodyBytes      = 9 * 1024 * 1024
	cloudRelayMaxFrameBytes     = 256 * 1024
	cloudRelayClockSkew         = 5 * time.Minute
	cloudRelayReplayWindow      = 10 * time.Minute
	cloudRelayMaxReplayMessages = 4096
)

type cloudRelayCipherEnvelope struct {
	Version    int    `json:"version"`
	Nonce      string `json:"nonce"`
	Ciphertext string `json:"ciphertext"`
}

type cloudRelayTunnelMessage struct {
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

type cloudRelayReplayCache struct {
	entries map[string]int64
	order   []string
}

func generateCloudRelayShareCode() (code string, roomID string, secret []byte, err error) {
	room := make([]byte, cloudRelayRoomBytes)
	secret = make([]byte, cloudRelaySecretBytes)
	if _, err = rand.Read(room); err != nil {
		return "", "", nil, err
	}
	if _, err = rand.Read(secret); err != nil {
		clearBytes(secret)
		return "", "", nil, err
	}
	roomID = base64.RawURLEncoding.EncodeToString(room)
	return formatCloudRelayShareCode(roomID, secret), roomID, secret, nil
}

func formatCloudRelayShareCode(roomID string, secret []byte) string {
	return strings.Join([]string{
		cloudRelaySharePrefix,
		roomID,
		base64.RawURLEncoding.EncodeToString(secret),
	}, ".")
}

func parseCloudRelayShareCode(raw string) (roomID string, secret []byte, err error) {
	parts := strings.Split(strings.TrimSpace(raw), ".")
	if len(parts) != 3 || parts[0] != cloudRelaySharePrefix {
		return "", nil, errors.New("云中继连接码格式无效")
	}
	room, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil || len(room) != cloudRelayRoomBytes {
		return "", nil, errors.New("云中继房间 ID 无效")
	}
	secret, err = base64.RawURLEncoding.DecodeString(parts[2])
	if err != nil || len(secret) != cloudRelaySecretBytes {
		clearBytes(secret)
		return "", nil, errors.New("云中继端到端密钥无效")
	}
	return parts[1], secret, nil
}

func validateCloudRelayRoomID(roomID string) error {
	decoded, err := base64.RawURLEncoding.DecodeString(strings.TrimSpace(roomID))
	if err != nil || len(decoded) != cloudRelayRoomBytes {
		return errors.New("云中继房间 ID 无效")
	}
	return nil
}

func parseCloudRelayURL(raw string) (*url.URL, error) {
	target, err := url.Parse(strings.TrimSpace(raw))
	if err != nil {
		return nil, err
	}
	if target.User != nil || target.Hostname() == "" || target.RawQuery != "" || target.Fragment != "" {
		return nil, errors.New("云中继地址不能包含账号、查询参数或片段")
	}
	switch strings.ToLower(target.Scheme) {
	case "wss":
	case "ws":
		host := target.Hostname()
		ip := net.ParseIP(host)
		if !strings.EqualFold(host, "localhost") && (ip == nil || !ip.IsLoopback()) {
			return nil, errors.New("公网云中继必须使用 wss://；ws:// 仅允许本机测试")
		}
	default:
		return nil, errors.New("云中继地址必须使用 wss://")
	}
	if target.Path == "" {
		target.Path = "/v1/connect"
	}
	return target, nil
}

func cloudRelayAAD(roomID, senderRole string) ([]byte, error) {
	if err := validateCloudRelayRoomID(roomID); err != nil {
		return nil, err
	}
	if senderRole != CloudRelayRolePhone && senderRole != CloudRelayRoleDesktop {
		return nil, errors.New("云中继发送角色无效")
	}
	return []byte(fmt.Sprintf("%s|%d|%s|%s", CloudRelaySubprotocol, CloudRelayProtocolVersion, roomID, senderRole)), nil
}

func encryptCloudRelayMessage(secret []byte, roomID, senderRole string, message cloudRelayTunnelMessage) ([]byte, error) {
	if len(secret) != cloudRelaySecretBytes {
		return nil, errors.New("云中继端到端密钥长度无效")
	}
	if err := validateCloudRelayMessage(message, time.Now()); err != nil {
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
	nonce := make([]byte, cloudRelayNonceBytes)
	if _, err := rand.Read(nonce); err != nil {
		return nil, err
	}
	aad, err := cloudRelayAAD(roomID, senderRole)
	if err != nil {
		return nil, err
	}
	envelope := cloudRelayCipherEnvelope{
		Version:    CloudRelayProtocolVersion,
		Nonce:      base64.RawURLEncoding.EncodeToString(nonce),
		Ciphertext: base64.RawURLEncoding.EncodeToString(gcm.Seal(nil, nonce, plain, aad)),
	}
	encoded, err := json.Marshal(envelope)
	if err != nil {
		return nil, err
	}
	if len(encoded) > cloudRelayMaxFrameBytes {
		return nil, errors.New("云中继加密帧超过大小限制")
	}
	return encoded, nil
}

func decryptCloudRelayMessage(secret []byte, roomID, senderRole string, encoded []byte, now time.Time) (cloudRelayTunnelMessage, error) {
	var message cloudRelayTunnelMessage
	if len(secret) != cloudRelaySecretBytes {
		return message, errors.New("云中继端到端密钥长度无效")
	}
	if len(encoded) == 0 || len(encoded) > cloudRelayMaxFrameBytes {
		return message, errors.New("云中继加密帧大小无效")
	}
	var envelope cloudRelayCipherEnvelope
	decoder := json.NewDecoder(strings.NewReader(string(encoded)))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&envelope); err != nil {
		return message, errors.New("云中继加密帧格式无效")
	}
	if err := requireCloudRelayJSONEOF(decoder); err != nil {
		return message, errors.New("云中继加密帧格式无效")
	}
	if envelope.Version != CloudRelayProtocolVersion {
		return message, errors.New("云中继加密帧版本不受支持")
	}
	nonce, err := base64.RawURLEncoding.DecodeString(envelope.Nonce)
	if err != nil || len(nonce) != cloudRelayNonceBytes {
		return message, errors.New("云中继随机数无效")
	}
	ciphertext, err := base64.RawURLEncoding.DecodeString(envelope.Ciphertext)
	if err != nil || len(ciphertext) < 16 {
		return message, errors.New("云中继密文无效")
	}
	block, err := aes.NewCipher(secret)
	if err != nil {
		return message, err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return message, err
	}
	aad, err := cloudRelayAAD(roomID, senderRole)
	if err != nil {
		return message, err
	}
	plain, err := gcm.Open(nil, nonce, ciphertext, aad)
	if err != nil {
		return message, errors.New("云中继密文认证失败")
	}
	messageDecoder := json.NewDecoder(strings.NewReader(string(plain)))
	messageDecoder.DisallowUnknownFields()
	if err := messageDecoder.Decode(&message); err != nil {
		return cloudRelayTunnelMessage{}, errors.New("云中继消息格式无效")
	}
	if err := requireCloudRelayJSONEOF(messageDecoder); err != nil {
		return cloudRelayTunnelMessage{}, errors.New("云中继消息格式无效")
	}
	if err := validateCloudRelayMessage(message, now); err != nil {
		return cloudRelayTunnelMessage{}, err
	}
	return message, nil
}

func requireCloudRelayJSONEOF(decoder *json.Decoder) error {
	var trailing any
	if err := decoder.Decode(&trailing); !errors.Is(err, io.EOF) {
		return errors.New("unexpected trailing JSON data")
	}
	return nil
}

func validateCloudRelayMessage(message cloudRelayTunnelMessage, now time.Time) error {
	if message.Version != CloudRelayProtocolVersion {
		return errors.New("云中继消息版本不受支持")
	}
	if !requestIDPattern(message.MessageID) || !requestIDPattern(message.RequestID) {
		return errors.New("云中继消息 ID 无效")
	}
	issuedAt := time.UnixMilli(message.IssuedAt)
	if issuedAt.Before(now.Add(-cloudRelayClockSkew)) || issuedAt.After(now.Add(cloudRelayClockSkew)) {
		return errors.New("云中继消息时间无效")
	}
	switch message.Kind {
	case "request_start":
		if message.Method != "GET" && message.Method != "POST" {
			return errors.New("云中继 HTTP 方法无效")
		}
		if !strings.HasPrefix(message.Path, "/api/v1/") || len(message.Path) > 256 || strings.ContainsAny(message.Path, "?#\x00") {
			return errors.New("云中继请求路径无效")
		}
		if err := validateCloudRelayHeaders(message.Headers, true); err != nil {
			return err
		}
	case "request_chunk", "response_chunk":
		chunk, err := base64.RawURLEncoding.DecodeString(message.Chunk)
		if err != nil || len(chunk) == 0 || len(chunk) > cloudRelayChunkBytes {
			return errors.New("云中继数据块无效")
		}
	case "request_end", "response_end", "cancel":
	case "response_start":
		if message.Status < 100 || message.Status > 599 {
			return errors.New("云中继响应状态无效")
		}
		if err := validateCloudRelayHeaders(message.Headers, false); err != nil {
			return err
		}
	case "error":
		if strings.TrimSpace(message.Error) == "" || len(message.Error) > 500 {
			return errors.New("云中继错误消息无效")
		}
	default:
		return errors.New("云中继消息类型无效")
	}
	return nil
}

func validateCloudRelayHeaders(headers map[string][]string, request bool) error {
	if len(headers) > 12 {
		return errors.New("云中继请求头数量过多")
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
			return errors.New("云中继请求头无效")
		}
		for _, value := range values {
			if len(value) > 8192 || strings.ContainsAny(value, "\r\n\x00") {
				return errors.New("云中继请求头值无效")
			}
		}
	}
	return nil
}

func filterCloudRelayHeaders(headers map[string][]string, request bool) map[string][]string {
	filtered := make(map[string][]string)
	for name, values := range headers {
		candidate := map[string][]string{name: append([]string(nil), values...)}
		if validateCloudRelayHeaders(candidate, request) == nil {
			filtered[name] = append([]string(nil), values...)
		}
	}
	return filtered
}

func newCloudRelayTunnelMessage(requestID, kind string) cloudRelayTunnelMessage {
	return cloudRelayTunnelMessage{
		Version:   CloudRelayProtocolVersion,
		MessageID: mustRandomID("relaymsg"),
		RequestID: requestID,
		Kind:      kind,
		IssuedAt:  time.Now().UnixMilli(),
	}
}

func (cache *cloudRelayReplayCache) claim(messageID string, issuedAt int64, now time.Time) bool {
	if cache.entries == nil {
		cache.entries = make(map[string]int64)
	}
	cutoff := now.Add(-cloudRelayReplayWindow).UnixMilli()
	for len(cache.order) > 0 {
		oldest := cache.order[0]
		if timestamp, ok := cache.entries[oldest]; ok && timestamp > cutoff && len(cache.entries) < cloudRelayMaxReplayMessages {
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
