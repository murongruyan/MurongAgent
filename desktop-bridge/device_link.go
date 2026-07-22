package desktopbridge

import (
	"bytes"
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/sha256"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"runtime"
	"strings"
	"time"
)

const (
	connectionRequestPath      = "/api/v1/connection/request"
	connectionStatusPath       = "/api/v1/connection/status"
	deviceLinkEnvelopeVersion  = "ecdh-p256-aesgcm-v1"
	connectionApprovalAuth     = "connection_approval"
	connectionADBAuth          = "adb"
	connectionStatusPending    = "pending"
	connectionStatusApproved   = "approved"
	connectionStatusRejected   = "rejected"
	connectionStatusBlocked    = "blocked"
	deviceLinkPollInterval     = 750 * time.Millisecond
	deviceLinkResponseMaxBytes = 128 * 1024
)

type publicDeviceStatus struct {
	Service           string   `json:"service"`
	PairingAvailable  bool     `json:"pairingAvailable"`
	PairingMethods    []string `json:"pairingMethods"`
	ProtocolVersion   int      `json:"protocolVersion"`
	DeviceID          string   `json:"deviceId"`
	DeviceDisplayID   string   `json:"deviceDisplayId"`
	DevicePublicKey   string   `json:"devicePublicKey"`
	DeviceFingerprint string   `json:"deviceFingerprint"`
	Platform          string   `json:"platform"`
}

type connectionRequest struct {
	RequestID          string `json:"requestId"`
	ClientName         string `json:"clientName"`
	DeviceID           string `json:"deviceId"`
	DevicePublicKey    string `json:"devicePublicKey"`
	DeviceFingerprint  string `json:"deviceFingerprint"`
	EphemeralPublicKey string `json:"ephemeralPublicKey"`
	Platform           string `json:"platform"`
	IssuedAt           int64  `json:"issuedAt"`
	AuthMethod         string `json:"authMethod"`
	AuthProof          string `json:"authProof,omitempty"`
	Signature          string `json:"signature"`
}

type connectionRequestAck struct {
	RequestID string `json:"requestId"`
	Status    string `json:"status"`
	ExpiresAt int64  `json:"expiresAt"`
	Message   string `json:"message"`
}

type connectionStatusRequest struct {
	RequestID string `json:"requestId"`
	DeviceID  string `json:"deviceId"`
	IssuedAt  int64  `json:"issuedAt"`
	Signature string `json:"signature"`
}

type deviceLinkEnvelope struct {
	Version    string `json:"version"`
	Nonce      string `json:"nonce"`
	Ciphertext string `json:"ciphertext"`
}

type connectionStatusResponse struct {
	RequestID                   string              `json:"requestId"`
	Status                      string              `json:"status"`
	Message                     string              `json:"message"`
	ResponderDeviceID           string              `json:"responderDeviceId"`
	ResponderPublicKey          string              `json:"responderPublicKey"`
	ResponderEphemeralPublicKey string              `json:"responderEphemeralPublicKey"`
	ResponderSignature          string              `json:"responderSignature"`
	ClientID                    string              `json:"clientId"`
	ClientName                  string              `json:"clientName"`
	CreatedAt                   int64               `json:"createdAt"`
	AuthServerProof             string              `json:"authServerProof"`
	SecureChannel               *deviceLinkEnvelope `json:"secureChannel"`
}

type deviceLinkCredentials struct {
	token           string
	syncKey         []byte
	peerDeviceID    string
	peerFingerprint string
}

func (api *apiClient) connectByRequest(
	ctx context.Context,
	identity *deviceIdentity,
	clientName string,
	expectedPeerDeviceID string,
	expectedPeerFingerprint string,
) (deviceLinkCredentials, error) {
	return api.connectByRequestWithAuth(
		ctx, identity, clientName, expectedPeerDeviceID, expectedPeerFingerprint,
		connectionApprovalAuth, nil,
	)
}

func (api *apiClient) connectByRequestWithAuth(
	ctx context.Context,
	identity *deviceIdentity,
	clientName string,
	expectedPeerDeviceID string,
	expectedPeerFingerprint string,
	authMethod string,
	proofBuilder func(connectionRequest) (string, error),
) (deviceLinkCredentials, error) {
	return api.connectByRequestWithAuthProvider(
		ctx, identity, clientName, expectedPeerDeviceID, expectedPeerFingerprint, authMethod,
		func(_ context.Context, request connectionRequest, _ publicDeviceStatus) (string, func(connectionStatusResponse) error, error) {
			if proofBuilder == nil {
				return "", nil, nil
			}
			proof, err := proofBuilder(request)
			return proof, nil, err
		},
	)
}

func (api *apiClient) connectByRequestWithSCRAM(
	ctx context.Context,
	identity *deviceIdentity,
	clientName string,
	expectedPeerDeviceID string,
	expectedPeerFingerprint string,
	authMethod string,
	secret string,
) (deviceLinkCredentials, error) {
	return api.connectByRequestWithAuthProvider(
		ctx, identity, clientName, expectedPeerDeviceID, expectedPeerFingerprint, authMethod,
		func(ctx context.Context, request connectionRequest, _ publicDeviceStatus) (string, func(connectionStatusResponse) error, error) {
			clientNonce, err := newSCRAMClientNonce()
			if err != nil {
				return "", nil, err
			}
			challengeRequest := pairChallengeRequest{
				RequestID: request.RequestID, ClientName: request.ClientName,
				DeviceID: request.DeviceID, DevicePublicKey: request.DevicePublicKey,
				DeviceFingerprint: request.DeviceFingerprint, EphemeralPublicKey: request.EphemeralPublicKey,
				Platform: request.Platform, IssuedAt: request.IssuedAt, AuthMethod: authMethod,
				ClientNonce: clientNonce,
			}
			challengeRequest.Signature, err = identity.sign(pairChallengeSignaturePayload(challengeRequest))
			if err != nil {
				return "", nil, err
			}
			var challengeResponse pairChallengeResponse
			if err := api.postPublicJSON(ctx, scramPairChallengePath, challengeRequest, &challengeResponse, 20*time.Second); err != nil {
				return "", nil, err
			}
			if challengeResponse.ExpiresAt <= time.Now().UnixMilli() {
				return "", nil, errors.New("手机返回的密码认证会话已过期")
			}
			proof, err := scramClientProof(secret, challengeRequest, challengeResponse)
			if err != nil {
				return "", nil, err
			}
			return proof.authProof, func(response connectionStatusResponse) error {
				return verifySCRAMServerProof(proof.expectedServerProof, response.AuthServerProof)
			}, nil
		},
	)
}

func (api *apiClient) connectByRequestWithGitHubAccount(
	ctx context.Context,
	identity *deviceIdentity,
	clientName string,
	expectedPeerDeviceID string,
	expectedPeerFingerprint string,
	trustClient *githubAccountTrustClient,
	backendSession string,
	githubToken string,
) (deviceLinkCredentials, string, error) {
	activeSession := strings.TrimSpace(backendSession)
	credentials, err := api.connectByRequestWithAuthProvider(
		ctx, identity, clientName, expectedPeerDeviceID, expectedPeerFingerprint, connectionGitHubAccountAuth,
		func(ctx context.Context, request connectionRequest, target publicDeviceStatus) (string, func(connectionStatusResponse) error, error) {
			login := func() error {
				if strings.TrimSpace(githubToken) == "" {
					return errors.New("电脑端没有可用于刷新账号会话的 GitHub Token")
				}
				session, loginErr := trustClient.loginWithGitHubToken(ctx, githubToken)
				if loginErr != nil {
					return loginErr
				}
				activeSession = session.SessionToken
				return nil
			}
			if activeSession == "" {
				if loginErr := login(); loginErr != nil {
					return "", nil, &githubAccountProofUnavailableError{cause: loginErr}
				}
			}
			proof, proofErr := trustClient.issueProof(ctx, activeSession, identity, request, target)
			if proofErr != nil && isGitHubAccountBackendUnauthorized(proofErr) && strings.TrimSpace(githubToken) != "" {
				if loginErr := login(); loginErr == nil {
					proof, proofErr = trustClient.issueProof(ctx, activeSession, identity, request, target)
				} else {
					proofErr = loginErr
				}
			}
			if proofErr != nil {
				return "", nil, &githubAccountProofUnavailableError{cause: proofErr}
			}
			return proof.Ticket, nil, nil
		},
	)
	return credentials, activeSession, err
}

func (api *apiClient) connectByRequestWithAuthProvider(
	ctx context.Context,
	identity *deviceIdentity,
	clientName string,
	expectedPeerDeviceID string,
	expectedPeerFingerprint string,
	authMethod string,
	proofBuilder func(context.Context, connectionRequest, publicDeviceStatus) (string, func(connectionStatusResponse) error, error),
) (deviceLinkCredentials, error) {
	if api == nil || identity == nil {
		return deviceLinkCredentials{}, errors.New("设备连接参数不完整")
	}
	status, err := api.fetchPublicDeviceStatus(ctx)
	if err != nil {
		return deviceLinkCredentials{}, fmt.Errorf("手机端暂不支持本机 ID 连接，请升级手机或使用一次性配对码：%w", err)
	}
	if err := validatePublicDeviceStatus(status, expectedPeerDeviceID, expectedPeerFingerprint); err != nil {
		return deviceLinkCredentials{}, err
	}
	ephemeral, err := newEphemeralDeviceIdentity()
	if err != nil {
		return deviceLinkCredentials{}, err
	}
	requestID, err := randomID("connect")
	if err != nil {
		return deviceLinkCredentials{}, err
	}
	request := connectionRequest{
		RequestID: requestID, ClientName: strings.TrimSpace(clientName),
		DeviceID: identity.deviceID, DevicePublicKey: identity.publicKey(), DeviceFingerprint: identity.fingerprint,
		EphemeralPublicKey: ephemeral.publicKey(), Platform: runtime.GOOS,
		IssuedAt: time.Now().UnixMilli(), AuthMethod: authMethod,
	}
	var responseVerifier func(connectionStatusResponse) error
	if proofBuilder != nil {
		request.AuthProof, responseVerifier, err = proofBuilder(ctx, request, status)
		if err != nil {
			return deviceLinkCredentials{}, err
		}
	}
	request.Signature, err = identity.sign(connectionRequestSignaturePayload(request))
	if err != nil {
		return deviceLinkCredentials{}, err
	}
	var acknowledgement connectionRequestAck
	if err := api.postPublicJSON(ctx, connectionRequestPath, request, &acknowledgement, 20*time.Second); err != nil {
		return deviceLinkCredentials{}, err
	}
	if acknowledgement.RequestID != requestID {
		return deviceLinkCredentials{}, errors.New("手机返回了错误的连接申请 ID")
	}
	for {
		if err := ctx.Err(); err != nil {
			return deviceLinkCredentials{}, err
		}
		statusRequest := connectionStatusRequest{RequestID: requestID, DeviceID: identity.deviceID, IssuedAt: time.Now().UnixMilli()}
		statusRequest.Signature, err = identity.sign(connectionStatusSignaturePayload(statusRequest))
		if err != nil {
			return deviceLinkCredentials{}, err
		}
		var response connectionStatusResponse
		if err := api.postPublicJSON(ctx, connectionStatusPath, statusRequest, &response, 20*time.Second); err != nil {
			return deviceLinkCredentials{}, err
		}
		switch response.Status {
		case connectionStatusPending:
			timer := time.NewTimer(deviceLinkPollInterval)
			select {
			case <-ctx.Done():
				timer.Stop()
				return deviceLinkCredentials{}, ctx.Err()
			case <-timer.C:
			}
			continue
		case connectionStatusRejected, connectionStatusBlocked:
			if strings.TrimSpace(response.Message) == "" {
				response.Message = "接收端拒绝了连接申请"
			}
			return deviceLinkCredentials{}, errors.New(response.Message)
		case connectionStatusApproved:
			credentials, decodeErr := decodeApprovedDeviceLink(identity, ephemeral, status, request, response)
			if decodeErr != nil {
				return deviceLinkCredentials{}, decodeErr
			}
			if responseVerifier != nil {
				if verifyErr := responseVerifier(response); verifyErr != nil {
					clearBytes(credentials.syncKey)
					return deviceLinkCredentials{}, verifyErr
				}
			}
			return credentials, nil
		default:
			return deviceLinkCredentials{}, errors.New("手机返回了未知的连接申请状态")
		}
	}
}

func (api *apiClient) fetchPublicDeviceStatus(ctx context.Context) (publicDeviceStatus, error) {
	requestContext, cancel := context.WithTimeout(ctx, 15*time.Second)
	defer cancel()
	request, err := http.NewRequestWithContext(requestContext, http.MethodGet, api.endpoint("/api/v1/public/status"), nil)
	if err != nil {
		return publicDeviceStatus{}, err
	}
	request.Header.Set("Accept", "application/json")
	response, err := api.httpClient.Do(request)
	if err != nil {
		return publicDeviceStatus{}, api.translateRequestError(err)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return publicDeviceStatus{}, decodeAPIError(response)
	}
	var status publicDeviceStatus
	if err := decodeStrictJSON(response.Body, deviceLinkResponseMaxBytes, &status); err != nil {
		return publicDeviceStatus{}, err
	}
	return status, nil
}

func (api *apiClient) postPublicJSON(ctx context.Context, path string, input, output any, timeout time.Duration) error {
	body, err := json.Marshal(input)
	if err != nil {
		return err
	}
	requestContext, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	request, err := http.NewRequestWithContext(requestContext, http.MethodPost, api.endpoint(path), bytes.NewReader(body))
	if err != nil {
		return err
	}
	request.Header.Set("Content-Type", "application/json")
	request.Header.Set("Accept", "application/json")
	response, err := api.httpClient.Do(request)
	if err != nil {
		return api.translateRequestError(err)
	}
	defer response.Body.Close()
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		return decodeAPIError(response)
	}
	return decodeStrictJSON(response.Body, deviceLinkResponseMaxBytes, output)
}

func decodeApprovedDeviceLink(
	identity, ephemeral *deviceIdentity,
	peer publicDeviceStatus,
	request connectionRequest,
	response connectionStatusResponse,
) (deviceLinkCredentials, error) {
	if response.RequestID != request.RequestID || response.ResponderDeviceID != peer.DeviceID ||
		response.ResponderPublicKey != peer.DevicePublicKey || response.SecureChannel == nil {
		return deviceLinkCredentials{}, errors.New("手机连接响应与目标设备身份不匹配")
	}
	if !verifyDeviceSignature(
		response.ResponderPublicKey,
		connectionResponseSignaturePayload(response),
		response.ResponderSignature,
	) {
		return deviceLinkCredentials{}, errors.New("手机连接响应签名无效")
	}
	contextValue := []byte("murong-device-link-context-v1\n" + request.RequestID + "\n" + identity.deviceID + "\n" + peer.DeviceID)
	linkKey, err := ephemeral.deriveLinkSecret(response.ResponderEphemeralPublicKey, contextValue)
	if err != nil {
		return deviceLinkCredentials{}, err
	}
	defer clearBytes(linkKey)
	token, syncKey, err := decryptDeviceLinkBootstrap(linkKey, request, response)
	if err != nil {
		return deviceLinkCredentials{}, err
	}
	return deviceLinkCredentials{
		token: token, syncKey: syncKey, peerDeviceID: peer.DeviceID, peerFingerprint: peer.DeviceFingerprint,
	}, nil
}

func validatePublicDeviceStatus(status publicDeviceStatus, expectedID, expectedFingerprint string) error {
	if status.ProtocolVersion < 2 || strings.TrimSpace(status.DevicePublicKey) == "" {
		return errors.New("手机端设备连接协议版本过旧")
	}
	derivedID, err := deviceIDForPublicKey(status.DevicePublicKey)
	if err != nil || derivedID != status.DeviceID {
		return errors.New("手机本机 ID 与设备公钥不匹配")
	}
	publicDER, err := decodeBase64URL(status.DevicePublicKey, maxDevicePublicDER)
	if err != nil {
		return err
	}
	digest := sha256.Sum256(publicDER)
	fingerprint := base64.RawURLEncoding.EncodeToString(digest[:])
	if fingerprint != status.DeviceFingerprint {
		return errors.New("手机设备指纹与公钥不匹配")
	}
	if expectedID != "" && expectedID != status.DeviceID || expectedFingerprint != "" && expectedFingerprint != fingerprint {
		return errors.New("手机设备身份已变化；为防止中间人攻击，请确认后清除旧配对")
	}
	return nil
}

func decryptDeviceLinkBootstrap(linkKey []byte, request connectionRequest, response connectionStatusResponse) (string, []byte, error) {
	envelope := response.SecureChannel
	if len(linkKey) != 32 || envelope == nil || envelope.Version != deviceLinkEnvelopeVersion {
		return "", nil, errors.New("设备连接加密响应版本无效")
	}
	nonce, err := decodeBase64URL(envelope.Nonce, 12)
	if err != nil || len(nonce) != 12 {
		return "", nil, errors.New("设备连接随机数无效")
	}
	ciphertext, err := decodeBase64URL(envelope.Ciphertext, deviceLinkResponseMaxBytes)
	if err != nil || len(ciphertext) < 16 {
		return "", nil, errors.New("设备连接密文无效")
	}
	block, err := aes.NewCipher(linkKey)
	if err != nil {
		return "", nil, err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return "", nil, err
	}
	aad := deviceLinkAAD(request, response)
	plain, err := gcm.Open(nil, nonce, ciphertext, aad)
	if err != nil {
		return "", nil, errors.New("设备连接密文认证失败")
	}
	defer clearBytes(plain)
	if len(plain) < 2+32 {
		return "", nil, errors.New("设备连接凭据长度无效")
	}
	tokenLength := int(binary.BigEndian.Uint16(plain[:2]))
	if tokenLength <= 0 || tokenLength > 128 || len(plain) != 2+tokenLength+32 {
		return "", nil, errors.New("设备连接凭据格式无效")
	}
	token := string(plain[2 : 2+tokenLength])
	syncKey := append([]byte(nil), plain[2+tokenLength:]...)
	return token, syncKey, nil
}

func deviceLinkAAD(request connectionRequest, response connectionStatusResponse) []byte {
	return []byte(fmt.Sprintf("%s\n%s\n%s\n%s\n%s\n%s\n%d",
		deviceLinkEnvelopeVersion, request.RequestID, request.DeviceID, response.ResponderDeviceID,
		response.ClientID, response.ClientName, response.CreatedAt))
}

func connectionRequestSignaturePayload(request connectionRequest) []byte {
	payload := fmt.Sprintf("murong-device-connect-request-v1\n%s\n%s\n%s\n%s\n%s\n%s\n%s\n%d\n%s",
		request.RequestID, strings.TrimSpace(request.ClientName), request.DeviceID, request.DevicePublicKey,
		request.DeviceFingerprint, request.EphemeralPublicKey, request.Platform, request.IssuedAt, request.AuthMethod)
	if request.AuthProof != "" {
		payload += "\n" + request.AuthProof
	}
	return []byte(payload)
}

func connectionStatusSignaturePayload(request connectionStatusRequest) []byte {
	return []byte(fmt.Sprintf("murong-device-connect-status-v1\n%s\n%s\n%d", request.RequestID, request.DeviceID, request.IssuedAt))
}

func connectionResponseSignaturePayload(response connectionStatusResponse) []byte {
	version, nonce, ciphertext := "", "", ""
	if response.SecureChannel != nil {
		version, nonce, ciphertext = response.SecureChannel.Version, response.SecureChannel.Nonce, response.SecureChannel.Ciphertext
	}
	payload := fmt.Sprintf("murong-device-connect-response-v1\n%s\n%s\n%s\n%s\n%s\n%s\n%s\n%d\n%s\n%s\n%s",
		response.RequestID, response.Status, response.ResponderDeviceID, response.ResponderPublicKey,
		response.ResponderEphemeralPublicKey, response.ClientID, response.ClientName, response.CreatedAt,
		version, nonce, ciphertext)
	if response.AuthServerProof != "" {
		payload += "\n" + response.AuthServerProof
	}
	return []byte(payload)
}

func decodeStrictJSON(reader io.Reader, maxBytes int64, output any) error {
	decoder := json.NewDecoder(io.LimitReader(reader, maxBytes))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(output); err != nil {
		return err
	}
	if err := decoder.Decode(&struct{}{}); !errors.Is(err, io.EOF) {
		return errors.New("JSON 响应包含多余内容")
	}
	return nil
}
