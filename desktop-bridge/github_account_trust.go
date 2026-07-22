package desktopbridge

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

const (
	OfficialAccountBackendURL    = "https://murongagent.rl1.cc"
	connectionGitHubAccountAuth  = "github_account"
	githubAccountProofVersion    = "github-account-proof-v1"
	githubAccountBackendMaxBytes = 128 * 1024
)

type githubAccountTrustClient struct {
	baseURL    *url.URL
	httpClient *http.Client
}

type githubAccountSession struct {
	SessionToken string `json:"session_token"`
	ExpiresAt    string `json:"session_expires_at"`
	GitHubLogin  string `json:"github_login"`
}

type githubAccountProof struct {
	Version   string `json:"version"`
	Ticket    string `json:"ticket"`
	ExpiresAt int64  `json:"expires_at"`
}

type githubAccountVerification struct {
	Trusted           bool   `json:"trusted"`
	Version           string `json:"version"`
	TrustSource       string `json:"trust_source"`
	IssuerDeviceID    string `json:"issuer_device_id"`
	IssuerFingerprint string `json:"issuer_fingerprint"`
}

type githubAccountBackendEnvelope struct {
	Success bool            `json:"success"`
	Message string          `json:"message"`
	Data    json.RawMessage `json:"data"`
}

type githubAccountBackendError struct {
	Status  int
	Message string
}

func (err *githubAccountBackendError) Error() string {
	if strings.TrimSpace(err.Message) != "" {
		return err.Message
	}
	return fmt.Sprintf("账号证明服务返回 HTTP %d", err.Status)
}

type githubAccountProofUnavailableError struct{ cause error }

func (err *githubAccountProofUnavailableError) Error() string {
	return "同 GitHub 账号免码证明暂不可用：" + err.cause.Error()
}

func (err *githubAccountProofUnavailableError) Unwrap() error { return err.cause }

func newGitHubAccountTrustClient(rawBaseURL string) (*githubAccountTrustClient, error) {
	parsed, err := url.Parse(strings.TrimRight(strings.TrimSpace(rawBaseURL), "/"))
	if err != nil || parsed.Host == "" || parsed.User != nil || parsed.RawQuery != "" || parsed.Fragment != "" {
		return nil, errors.New("账号证明后端地址无效")
	}
	if parsed.Scheme != "https" && !(parsed.Scheme == "http" && strings.EqualFold(parsed.Hostname(), "127.0.0.1")) {
		return nil, errors.New("账号证明后端必须使用 HTTPS")
	}
	parsed.Path = strings.TrimRight(parsed.Path, "/")
	return &githubAccountTrustClient{
		baseURL:    parsed,
		httpClient: &http.Client{Timeout: 30 * time.Second},
	}, nil
}

func (client *githubAccountTrustClient) loginWithGitHubToken(ctx context.Context, token string) (githubAccountSession, error) {
	if client == nil || strings.TrimSpace(token) == "" {
		return githubAccountSession{}, errors.New("电脑端未配置 GitHub Token")
	}
	var session githubAccountSession
	err := client.post(ctx, "/api/github_auth.php?action=token_login", "", map[string]string{
		"github_token": strings.TrimSpace(token),
	}, &session)
	if err != nil {
		return githubAccountSession{}, err
	}
	if strings.TrimSpace(session.SessionToken) == "" {
		return githubAccountSession{}, errors.New("账号证明后端没有返回登录会话")
	}
	return session, nil
}

func (client *githubAccountTrustClient) issueProof(
	ctx context.Context,
	sessionToken string,
	identity *deviceIdentity,
	request connectionRequest,
	target publicDeviceStatus,
) (githubAccountProof, error) {
	if client == nil || identity == nil || strings.TrimSpace(sessionToken) == "" {
		return githubAccountProof{}, errors.New("账号证明会话不可用")
	}
	nonceBytes := make([]byte, 16)
	if _, err := rand.Read(nonceBytes); err != nil {
		return githubAccountProof{}, err
	}
	nonce := base64.RawURLEncoding.EncodeToString(nonceBytes)
	payload := githubAccountConnectionPayload(request, target)
	signaturePayload := append(append([]byte("murong-github-device-proof-issue-v1\n"), payload...), []byte("\n"+nonce)...)
	signature, err := identity.sign(signaturePayload)
	if err != nil {
		return githubAccountProof{}, err
	}
	input := map[string]any{
		"request_id":                request.RequestID,
		"client_name":               strings.TrimSpace(request.ClientName),
		"device_id":                 request.DeviceID,
		"device_public_key":         request.DevicePublicKey,
		"device_fingerprint":        request.DeviceFingerprint,
		"ephemeral_public_key":      request.EphemeralPublicKey,
		"platform":                  request.Platform,
		"issued_at":                 request.IssuedAt,
		"target_device_id":          target.DeviceID,
		"target_device_public_key":  target.DevicePublicKey,
		"target_device_fingerprint": target.DeviceFingerprint,
		"proof_nonce":               nonce,
		"device_signature":          signature,
	}
	var proof githubAccountProof
	if err := client.post(ctx, "/api/device_trust.php?action=issue", sessionToken, input, &proof); err != nil {
		return githubAccountProof{}, err
	}
	if proof.Version != githubAccountProofVersion || !validAccountProofTicket(proof.Ticket) || proof.ExpiresAt <= time.Now().UnixMilli() {
		return githubAccountProof{}, errors.New("账号证明后端返回了无效票据")
	}
	return proof, nil
}

func (client *githubAccountTrustClient) verifyProof(
	ctx context.Context,
	sessionToken string,
	identity *deviceIdentity,
	request connectionRequest,
	ticket string,
) (bool, error) {
	if client == nil || identity == nil || strings.TrimSpace(sessionToken) == "" || !validAccountProofTicket(ticket) {
		return false, errors.New("账号证明验证参数不完整")
	}
	target := publicDeviceStatus{
		DeviceID: identity.deviceID, DevicePublicKey: identity.publicKey(), DeviceFingerprint: identity.fingerprint,
	}
	receiverIssuedAt := time.Now().UnixMilli()
	receiverSignature, err := identity.sign(githubAccountVerificationSignaturePayload(
		ticket, request.RequestID, target.DeviceID, target.DevicePublicKey, target.DeviceFingerprint, receiverIssuedAt,
	))
	if err != nil {
		return false, err
	}
	input := map[string]any{
		"request_id":                request.RequestID,
		"client_name":               strings.TrimSpace(request.ClientName),
		"device_id":                 request.DeviceID,
		"device_public_key":         request.DevicePublicKey,
		"device_fingerprint":        request.DeviceFingerprint,
		"ephemeral_public_key":      request.EphemeralPublicKey,
		"platform":                  request.Platform,
		"issued_at":                 request.IssuedAt,
		"target_device_id":          target.DeviceID,
		"target_device_public_key":  target.DevicePublicKey,
		"target_device_fingerprint": target.DeviceFingerprint,
		"ticket":                    ticket,
		"receiver_issued_at":        receiverIssuedAt,
		"receiver_signature":        receiverSignature,
	}
	var result githubAccountVerification
	if err := client.post(ctx, "/api/device_trust.php?action=verify", sessionToken, input, &result); err != nil {
		return false, err
	}
	return result.Trusted && result.Version == githubAccountProofVersion &&
		result.TrustSource == connectionGitHubAccountAuth && result.IssuerDeviceID == request.DeviceID &&
		result.IssuerFingerprint == request.DeviceFingerprint, nil
}

func (client *githubAccountTrustClient) post(ctx context.Context, path, bearer string, input, output any) error {
	body, err := json.Marshal(input)
	if err != nil {
		return err
	}
	target := *client.baseURL
	target.Path = strings.TrimRight(target.Path, "/") + strings.SplitN(path, "?", 2)[0]
	if pieces := strings.SplitN(path, "?", 2); len(pieces) == 2 {
		target.RawQuery = pieces[1]
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodPost, target.String(), bytes.NewReader(body))
	if err != nil {
		return err
	}
	request.Header.Set("Accept", "application/json")
	request.Header.Set("Content-Type", "application/json")
	request.Header.Set("User-Agent", "Murong-Desktop-Agent/1.0")
	if strings.TrimSpace(bearer) != "" {
		request.Header.Set("Authorization", "Bearer "+strings.TrimSpace(bearer))
	}
	response, err := client.httpClient.Do(request)
	if err != nil {
		return err
	}
	defer response.Body.Close()
	raw, err := io.ReadAll(io.LimitReader(response.Body, githubAccountBackendMaxBytes))
	if err != nil {
		return err
	}
	var envelope githubAccountBackendEnvelope
	if err := json.Unmarshal(raw, &envelope); err != nil {
		return errors.New("账号证明后端响应格式无效")
	}
	if response.StatusCode < 200 || response.StatusCode >= 300 || !envelope.Success {
		return &githubAccountBackendError{Status: response.StatusCode, Message: strings.TrimSpace(envelope.Message)}
	}
	if len(envelope.Data) == 0 || string(envelope.Data) == "null" || json.Unmarshal(envelope.Data, output) != nil {
		return errors.New("账号证明后端没有返回有效数据")
	}
	return nil
}

func githubAccountConnectionPayload(request connectionRequest, target publicDeviceStatus) []byte {
	return []byte(fmt.Sprintf("murong-github-device-proof-connection-v1\n%s\n%s\n%s\n%s\n%s\n%s\n%s\n%d\n%s\n%s\n%s",
		request.RequestID, strings.TrimSpace(request.ClientName), request.DeviceID, request.DevicePublicKey,
		request.DeviceFingerprint, request.EphemeralPublicKey, request.Platform, request.IssuedAt,
		target.DeviceID, target.DevicePublicKey, target.DeviceFingerprint))
}

func githubAccountVerificationSignaturePayload(
	ticket, requestID, deviceID, publicKey, fingerprint string,
	issuedAt int64,
) []byte {
	return []byte(fmt.Sprintf("murong-github-device-proof-verify-v1\n%s\n%s\n%s\n%s\n%s\n%d",
		ticket, requestID, deviceID, publicKey, fingerprint, issuedAt))
}

func validAccountProofTicket(ticket string) bool {
	decoded, err := base64.RawURLEncoding.DecodeString(strings.TrimSpace(ticket))
	return err == nil && len(decoded) == 32
}

func isGitHubAccountBackendUnauthorized(err error) bool {
	var backendErr *githubAccountBackendError
	return errors.As(err, &backendErr) && (backendErr.Status == http.StatusUnauthorized || backendErr.Status == http.StatusNotFound)
}
