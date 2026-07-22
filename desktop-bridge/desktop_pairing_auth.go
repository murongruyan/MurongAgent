package desktopbridge

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

const (
	desktopPairingStateVersion = 1
	desktopTemporaryCodeTTL    = 5 * time.Minute
	desktopAuthSessionTTL      = time.Minute
	desktopAuthCooldown        = time.Minute
	desktopMaxAuthFailures     = 5
	desktopPairingAlphabet     = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
)

type desktopPairingVerifierRecord struct {
	Salt       string `json:"salt"`
	Iterations int    `json:"iterations"`
	StoredKey  string `json:"storedKey"`
	ServerKey  string `json:"serverKey"`
	UpdatedAt  int64  `json:"updatedAt"`
}

type desktopPairingState struct {
	SchemaVersion    int                           `json:"schemaVersion"`
	SecurityPassword *desktopPairingVerifierRecord `json:"securityPassword,omitempty"`
}

type desktopSCRAMVerifier struct {
	salt       []byte
	iterations int
	storedKey  []byte
	serverKey  []byte
}

type desktopTemporaryCode struct {
	code      string
	verifier  desktopSCRAMVerifier
	expiresAt time.Time
	failures  int
}

type desktopAuthSession struct {
	request  pairChallengeRequest
	response pairChallengeResponse
	verifier desktopSCRAMVerifier
}

type desktopPairingAuthSnapshot struct {
	TemporaryCode              string
	TemporaryCodeExpiresAt     int64
	SecurityPasswordConfigured bool
	CooldownUntil              int64
}

type desktopPairingAuthenticator struct {
	mu            sync.Mutex
	statePath     string
	temporary     *desktopTemporaryCode
	cooldownUntil time.Time
	sessions      map[string]desktopAuthSession
}

func newDesktopPairingAuthenticator(statePath string) (*desktopPairingAuthenticator, error) {
	auth := &desktopPairingAuthenticator{statePath: statePath, sessions: make(map[string]desktopAuthSession)}
	if _, err := auth.loadState(); err != nil {
		return nil, err
	}
	if _, err := auth.beginTemporaryCodeLocked(time.Now()); err != nil {
		return nil, err
	}
	return auth, nil
}

func (auth *desktopPairingAuthenticator) Snapshot(now time.Time) desktopPairingAuthSnapshot {
	auth.mu.Lock()
	defer auth.mu.Unlock()
	auth.cleanupLocked(now)
	state, _ := auth.loadState()
	snapshot := desktopPairingAuthSnapshot{SecurityPasswordConfigured: state.SecurityPassword != nil}
	if auth.temporary != nil {
		snapshot.TemporaryCode = formatPairingCode(auth.temporary.code)
		snapshot.TemporaryCodeExpiresAt = auth.temporary.expiresAt.UnixMilli()
	}
	if auth.cooldownUntil.After(now) {
		snapshot.CooldownUntil = auth.cooldownUntil.UnixMilli()
	}
	return snapshot
}

func (auth *desktopPairingAuthenticator) RotateTemporaryCode() (desktopPairingAuthSnapshot, error) {
	auth.mu.Lock()
	defer auth.mu.Unlock()
	now := time.Now()
	auth.cleanupLocked(now)
	if auth.cooldownUntil.After(now) {
		return desktopPairingAuthSnapshot{}, errors.New("临时验证码因连续失败正在冷却")
	}
	if _, err := auth.beginTemporaryCodeLocked(now); err != nil {
		return desktopPairingAuthSnapshot{}, err
	}
	state, _ := auth.loadState()
	return desktopPairingAuthSnapshot{
		TemporaryCode:              formatPairingCode(auth.temporary.code),
		TemporaryCodeExpiresAt:     auth.temporary.expiresAt.UnixMilli(),
		SecurityPasswordConfigured: state.SecurityPassword != nil,
	}, nil
}

func (auth *desktopPairingAuthenticator) SetSecurityPassword(password string) error {
	normalized, err := normalizeSCRAMSecret(password, connectionSecurityPasswordAuth)
	if err != nil {
		return err
	}
	verifier, err := newDesktopSCRAMVerifier(normalized)
	if err != nil {
		return err
	}
	defer verifier.clear()
	auth.mu.Lock()
	defer auth.mu.Unlock()
	state, err := auth.loadState()
	if err != nil {
		return err
	}
	state.SecurityPassword = &desktopPairingVerifierRecord{
		Salt: base64.RawURLEncoding.EncodeToString(verifier.salt), Iterations: verifier.iterations,
		StoredKey: base64.RawURLEncoding.EncodeToString(verifier.storedKey),
		ServerKey: base64.RawURLEncoding.EncodeToString(verifier.serverKey), UpdatedAt: time.Now().UnixMilli(),
	}
	return auth.writeState(state)
}

func (auth *desktopPairingAuthenticator) ClearSecurityPassword() error {
	auth.mu.Lock()
	defer auth.mu.Unlock()
	state, err := auth.loadState()
	if err != nil {
		return err
	}
	state.SecurityPassword = nil
	for id, session := range auth.sessions {
		if session.request.AuthMethod == connectionSecurityPasswordAuth {
			session.verifier.clear()
			delete(auth.sessions, id)
		}
	}
	return auth.writeState(state)
}

func (auth *desktopPairingAuthenticator) Begin(message deviceRelayMessage, fingerprint string, now time.Time) (pairChallengeResponse, error) {
	auth.mu.Lock()
	defer auth.mu.Unlock()
	auth.cleanupLocked(now)
	request := relayPairChallengeRequest(message, fingerprint)
	var verifier desktopSCRAMVerifier
	switch request.AuthMethod {
	case connectionTemporaryCodeAuth:
		if auth.cooldownUntil.After(now) || auth.temporary == nil || !auth.temporary.expiresAt.After(now) {
			return pairChallengeResponse{}, errors.New("当前没有可用临时验证码")
		}
		verifier = auth.temporary.verifier.copySecure()
	case connectionSecurityPasswordAuth:
		state, err := auth.loadState()
		if err != nil || state.SecurityPassword == nil {
			return pairChallengeResponse{}, errors.New("电脑尚未设置安全密码")
		}
		verifier, err = state.SecurityPassword.verifier()
		if err != nil {
			return pairChallengeResponse{}, err
		}
	default:
		return pairChallengeResponse{}, errors.New("密码认证方式无效")
	}
	sessionID, err := randomBase64URL(scramNonceBytes)
	if err != nil {
		verifier.clear()
		return pairChallengeResponse{}, err
	}
	serverNonceSuffix, err := randomBase64URL(scramNonceBytes)
	if err != nil {
		verifier.clear()
		return pairChallengeResponse{}, err
	}
	response := pairChallengeResponse{
		Version: scramPairingVersion, SessionID: sessionID,
		ServerNonce: request.ClientNonce + serverNonceSuffix,
		Salt:        base64.RawURLEncoding.EncodeToString(verifier.salt), Iterations: verifier.iterations,
		ExpiresAt: now.Add(desktopAuthSessionTTL).UnixMilli(),
	}
	auth.sessions[sessionID] = desktopAuthSession{request: request, response: response, verifier: verifier}
	for len(auth.sessions) > 64 {
		for id, old := range auth.sessions {
			old.verifier.clear()
			delete(auth.sessions, id)
			break
		}
	}
	return response, nil
}

func (auth *desktopPairingAuthenticator) Authenticate(message deviceRelayMessage, fingerprint string, now time.Time) (string, bool) {
	auth.mu.Lock()
	defer auth.mu.Unlock()
	auth.cleanupLocked(now)
	separator := strings.IndexByte(message.AuthProof, '.')
	if separator <= 0 {
		return "", false
	}
	sessionID := message.AuthProof[:separator]
	proofText := message.AuthProof[separator+1:]
	session, ok := auth.sessions[sessionID]
	if !ok {
		return "", false
	}
	delete(auth.sessions, sessionID)
	defer session.verifier.clear()
	request := relayPairChallengeRequest(message, fingerprint)
	if session.response.ExpiresAt <= now.UnixMilli() || !samePairChallengeRequest(session.request, request) {
		auth.recordFailureLocked(session.request.AuthMethod, now)
		return "", false
	}
	authMessage := scramAuthMessage(session.request, session.response)
	serverProof, valid := verifyDesktopSCRAMProof(session.verifier, authMessage, proofText)
	if !valid {
		auth.recordFailureLocked(session.request.AuthMethod, now)
		return "", false
	}
	if session.request.AuthMethod == connectionTemporaryCodeAuth {
		auth.clearTemporaryLocked()
		auth.cooldownUntil = time.Time{}
		_, _ = auth.beginTemporaryCodeLocked(now)
	}
	return serverProof, true
}

func relayPairChallengeRequest(message deviceRelayMessage, fingerprint string) pairChallengeRequest {
	return pairChallengeRequest{
		RequestID: message.RequestID, ClientName: strings.TrimSpace(message.DeviceName),
		DeviceID: message.SourceDeviceID, DevicePublicKey: message.SourcePublicKey,
		DeviceFingerprint: fingerprint, EphemeralPublicKey: message.SourcePublicKey,
		Platform: message.Platform, IssuedAt: message.IssuedAt, AuthMethod: message.AuthMethod,
		ClientNonce: message.ClientNonce,
	}
}

func samePairChallengeRequest(left, right pairChallengeRequest) bool {
	return left.RequestID == right.RequestID && left.ClientName == right.ClientName &&
		left.DeviceID == right.DeviceID && left.DevicePublicKey == right.DevicePublicKey &&
		left.DeviceFingerprint == right.DeviceFingerprint && left.EphemeralPublicKey == right.EphemeralPublicKey &&
		left.Platform == right.Platform && left.IssuedAt == right.IssuedAt &&
		left.AuthMethod == right.AuthMethod && left.ClientNonce == right.ClientNonce
}

func newDesktopSCRAMVerifier(secret string) (desktopSCRAMVerifier, error) {
	salt := make([]byte, scramSaltBytes)
	if _, err := rand.Read(salt); err != nil {
		return desktopSCRAMVerifier{}, err
	}
	password := []byte(secret)
	salted := pbkdf2SHA256(password, salt, scramIterations, scramProofBytes)
	clearBytes(password)
	defer clearBytes(salted)
	clientKey := scramHMAC(salted, []byte("Client Key"))
	stored := sha256.Sum256(clientKey)
	clearBytes(clientKey)
	return desktopSCRAMVerifier{
		salt: salt, iterations: scramIterations, storedKey: append([]byte(nil), stored[:]...),
		serverKey: scramHMAC(salted, []byte("Server Key")),
	}, nil
}

func verifyDesktopSCRAMProof(verifier desktopSCRAMVerifier, authMessage []byte, rawProof string) (string, bool) {
	proof, err := decodeBase64URL(rawProof, scramProofBytes)
	if err != nil || len(proof) != scramProofBytes {
		return "", false
	}
	defer clearBytes(proof)
	clientSignature := scramHMAC(verifier.storedKey, authMessage)
	clientKey := make([]byte, len(proof))
	for i := range proof {
		clientKey[i] = proof[i] ^ clientSignature[i]
	}
	clearBytes(clientSignature)
	recovered := sha256.Sum256(clientKey)
	clearBytes(clientKey)
	if !hmac.Equal(recovered[:], verifier.storedKey) {
		return "", false
	}
	serverProof := scramHMAC(verifier.serverKey, authMessage)
	defer clearBytes(serverProof)
	return base64.RawURLEncoding.EncodeToString(serverProof), true
}

func (auth *desktopPairingAuthenticator) beginTemporaryCodeLocked(now time.Time) (string, error) {
	auth.clearTemporaryLocked()
	code, err := randomPairingCode()
	if err != nil {
		return "", err
	}
	verifier, err := newDesktopSCRAMVerifier(code)
	if err != nil {
		return "", err
	}
	auth.temporary = &desktopTemporaryCode{code: code, verifier: verifier, expiresAt: now.Add(desktopTemporaryCodeTTL)}
	return code, nil
}

func (auth *desktopPairingAuthenticator) clearTemporaryLocked() {
	if auth.temporary != nil {
		auth.temporary.verifier.clear()
	}
	auth.temporary = nil
	for id, session := range auth.sessions {
		if session.request.AuthMethod == connectionTemporaryCodeAuth {
			session.verifier.clear()
			delete(auth.sessions, id)
		}
	}
}

func (auth *desktopPairingAuthenticator) recordFailureLocked(method string, now time.Time) {
	if method != connectionTemporaryCodeAuth || auth.temporary == nil {
		return
	}
	auth.temporary.failures++
	if auth.temporary.failures >= desktopMaxAuthFailures {
		auth.clearTemporaryLocked()
		auth.cooldownUntil = now.Add(desktopAuthCooldown)
	}
}

func (auth *desktopPairingAuthenticator) cleanupLocked(now time.Time) {
	if auth.temporary != nil && !auth.temporary.expiresAt.After(now) {
		auth.clearTemporaryLocked()
	}
	if !auth.cooldownUntil.After(now) {
		auth.cooldownUntil = time.Time{}
	}
	for id, session := range auth.sessions {
		if session.response.ExpiresAt <= now.UnixMilli() {
			session.verifier.clear()
			delete(auth.sessions, id)
		}
	}
	if auth.temporary == nil && auth.cooldownUntil.IsZero() {
		_, _ = auth.beginTemporaryCodeLocked(now)
	}
}

func randomPairingCode() (string, error) {
	value := make([]byte, pairingCodeLength)
	random := make([]byte, pairingCodeLength)
	if _, err := rand.Read(random); err != nil {
		return "", err
	}
	for i := range value {
		value[i] = desktopPairingAlphabet[int(random[i])%len(desktopPairingAlphabet)]
	}
	clearBytes(random)
	return string(value), nil
}

func formatPairingCode(code string) string {
	if len(code) != pairingCodeLength {
		return code
	}
	parts := make([]string, 0, (len(code)+3)/4)
	for start := 0; start < len(code); start += 4 {
		end := start + 4
		if end > len(code) {
			end = len(code)
		}
		parts = append(parts, code[start:end])
	}
	return strings.Join(parts, "-")
}

func (verifier desktopSCRAMVerifier) copySecure() desktopSCRAMVerifier {
	return desktopSCRAMVerifier{salt: append([]byte(nil), verifier.salt...), iterations: verifier.iterations,
		storedKey: append([]byte(nil), verifier.storedKey...), serverKey: append([]byte(nil), verifier.serverKey...)}
}

func (verifier *desktopSCRAMVerifier) clear() {
	clearBytes(verifier.salt)
	clearBytes(verifier.storedKey)
	clearBytes(verifier.serverKey)
}

func (record desktopPairingVerifierRecord) verifier() (desktopSCRAMVerifier, error) {
	salt, err := decodeBase64URL(record.Salt, scramSaltBytes)
	if err != nil || len(salt) != scramSaltBytes || record.Iterations < 100_000 || record.Iterations > 1_000_000 {
		return desktopSCRAMVerifier{}, errors.New("安全密码验证记录损坏")
	}
	stored, err := decodeBase64URL(record.StoredKey, scramProofBytes)
	if err != nil || len(stored) != scramProofBytes {
		clearBytes(salt)
		return desktopSCRAMVerifier{}, errors.New("安全密码验证记录损坏")
	}
	server, err := decodeBase64URL(record.ServerKey, scramProofBytes)
	if err != nil || len(server) != scramProofBytes {
		clearBytes(salt)
		clearBytes(stored)
		return desktopSCRAMVerifier{}, errors.New("安全密码验证记录损坏")
	}
	return desktopSCRAMVerifier{salt: salt, iterations: record.Iterations, storedKey: stored, serverKey: server}, nil
}

func (auth *desktopPairingAuthenticator) loadState() (desktopPairingState, error) {
	state := desktopPairingState{SchemaVersion: desktopPairingStateVersion}
	raw, err := os.ReadFile(auth.statePath)
	if errors.Is(err, os.ErrNotExist) {
		return state, nil
	}
	if err != nil {
		return state, err
	}
	if err := json.Unmarshal(raw, &state); err != nil || state.SchemaVersion != desktopPairingStateVersion {
		return desktopPairingState{}, errors.New("电脑安全密码配置损坏")
	}
	if state.SecurityPassword != nil {
		verifier, verifyErr := state.SecurityPassword.verifier()
		if verifyErr != nil {
			return desktopPairingState{}, verifyErr
		}
		verifier.clear()
	}
	return state, nil
}

func (auth *desktopPairingAuthenticator) writeState(state desktopPairingState) error {
	state.SchemaVersion = desktopPairingStateVersion
	if err := os.MkdirAll(filepath.Dir(auth.statePath), 0o700); err != nil {
		return err
	}
	raw, err := json.MarshalIndent(state, "", "  ")
	if err != nil {
		return err
	}
	temporary := auth.statePath + ".tmp"
	if err := os.WriteFile(temporary, raw, 0o600); err != nil {
		return err
	}
	_ = os.Chmod(temporary, 0o600)
	if err := os.Rename(temporary, auth.statePath); err != nil {
		_ = os.Remove(temporary)
		return err
	}
	return nil
}
