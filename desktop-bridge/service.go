package desktopbridge

import (
	"context"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

type RemoteNodeConfig struct {
	ConnectionMode      string   `json:"connectionMode"`
	PairingAuthMethod   string   `json:"pairingAuthMethod"`
	PhoneURL            string   `json:"phoneUrl"`
	ADBSerial           string   `json:"adbSerial,omitempty"`
	Workspace           string   `json:"workspace"`
	Label               string   `json:"label"`
	ClientName          string   `json:"clientName"`
	AllowWrite          bool     `json:"allowWrite"`
	ShareDesktopTasks   bool     `json:"shareDesktopTasks"`
	AllowAgentControl   bool     `json:"allowAgentControl"`
	TerminalBackends    []string `json:"terminalBackends"`
	Paired              bool     `json:"paired"`
	SecureSyncReady     bool     `json:"secureSyncReady"`
	DeviceID            string   `json:"deviceId,omitempty"`
	DeviceDisplayID     string   `json:"deviceDisplayId,omitempty"`
	DeviceFingerprint   string   `json:"deviceFingerprint,omitempty"`
	PeerDeviceID        string   `json:"peerDeviceId,omitempty"`
	PeerDeviceDisplayID string   `json:"peerDeviceDisplayId,omitempty"`
	PeerFingerprint     string   `json:"peerFingerprint,omitempty"`
}

type RemoteTerminal struct {
	ID      string `json:"id"`
	Label   string `json:"label"`
	Version string `json:"version,omitempty"`
}

type RemoteNodeStatus struct {
	Phase   string `json:"phase"`
	Message string `json:"message"`
	Running bool   `json:"running"`
}

type RemoteNodeSnapshot struct {
	Config                     RemoteNodeConfig          `json:"config"`
	Status                     RemoteNodeStatus          `json:"status"`
	Terminals                  []RemoteTerminal          `json:"terminals"`
	ConnectionRequests         []RemoteConnectionRequest `json:"connectionRequests"`
	DeviceServiceOnline        bool                      `json:"deviceServiceOnline"`
	TemporaryCode              string                    `json:"temporaryCode,omitempty"`
	TemporaryCodeExpiresAt     int64                     `json:"temporaryCodeExpiresAt,omitempty"`
	SecurityPasswordConfigured bool                      `json:"securityPasswordConfigured"`
	DoNotDisturb               bool                      `json:"doNotDisturb"`
	BlockedPeers               []RemoteBlockedPeer       `json:"blockedPeers"`
	ConfigPath                 string                    `json:"configPath"`
}

type RemoteConnectionRequest struct {
	RequestID       string `json:"requestId"`
	DeviceID        string `json:"deviceId"`
	DeviceDisplayID string `json:"deviceDisplayId"`
	DeviceName      string `json:"deviceName"`
	Platform        string `json:"platform"`
	Fingerprint     string `json:"fingerprint"`
	CreatedAt       int64  `json:"createdAt"`
}

type pendingRemoteConnection struct {
	request       deviceRelayMessage
	summary       RemoteConnectionRequest
	serverProof   string
	authenticated bool
	phoneURL      string
}

type RemoteNodeService struct {
	mu                    sync.Mutex
	configPath            string
	config                nodeConfig
	identity              *deviceIdentity
	inventory             terminalInventory
	status                RemoteNodeStatus
	cancel                context.CancelFunc
	done                  chan struct{}
	node                  *computerNode
	listener              func(RemoteNodeSnapshot)
	agentSnapshotProvider func() DesktopAgentSnapshot
	agentCommandHandler   func(DesktopAgentCommand) DesktopAgentCommandResult
	relayListener         *deviceRelayListener
	discoveryResponder    *lanDiscoveryResponder
	deviceServiceOnline   bool
	connectionRequests    map[string]pendingRemoteConnection
	connectionAttempts    map[string][]time.Time
	pairingAuthenticator  *desktopPairingAuthenticator
	connectionPolicy      *desktopConnectionPolicyStore
	githubTrustClient     *githubAccountTrustClient
	githubToken           string
	pairingRefreshCancel  context.CancelFunc
}

func NewRemoteNodeService(ctx context.Context) (*RemoteNodeService, error) {
	configPath, err := defaultNodeConfigPath()
	if err != nil {
		return nil, err
	}
	config, err := loadNodeConfig(configPath)
	if err != nil {
		return nil, err
	}
	identity, err := ensureNodeDeviceIdentity(&config)
	if err != nil {
		return nil, err
	}
	if err := saveNodeConfig(configPath, config); err != nil {
		return nil, fmt.Errorf("无法保存本机设备身份：%w", err)
	}
	service := &RemoteNodeService{
		configPath:         configPath,
		config:             config,
		identity:           identity,
		inventory:          discoverTerminalInventory(ctx),
		status:             RemoteNodeStatus{Phase: string(nodePhaseStopped), Message: "内置远程节点已停止"},
		connectionRequests: make(map[string]pendingRemoteConnection),
		connectionAttempts: make(map[string][]time.Time),
	}
	pairingAuthenticator, err := newDesktopPairingAuthenticator(
		filepath.Join(filepath.Dir(configPath), "desktop-pairing-auth-v1.json"),
	)
	if err != nil {
		return nil, err
	}
	service.pairingAuthenticator = pairingAuthenticator
	connectionPolicy, err := newDesktopConnectionPolicyStore(
		filepath.Join(filepath.Dir(configPath), "desktop-connection-policy-v1.json"),
	)
	if err != nil {
		return nil, err
	}
	service.connectionPolicy = connectionPolicy
	githubTrustClient, err := newGitHubAccountTrustClient(OfficialAccountBackendURL)
	if err != nil {
		return nil, err
	}
	service.githubTrustClient = githubTrustClient
	pairingRefreshContext, pairingRefreshCancel := context.WithCancel(context.Background())
	service.pairingRefreshCancel = pairingRefreshCancel
	go service.refreshTemporaryCodes(pairingRefreshContext)
	relayListener, err := newDeviceRelayListener(
		identity,
		OfficialDeviceRelayURL,
		pairingAuthenticator,
		service.handleRelayConnectionRequest,
		service.handleDeviceServiceState,
	)
	if err != nil {
		return nil, err
	}
	service.relayListener = relayListener
	relayListener.Start()
	service.discoveryResponder = newLANDiscoveryResponder(identity)
	service.discoveryResponder.Start()
	return service, nil
}

func (service *RemoteNodeService) SetListener(listener func(RemoteNodeSnapshot)) {
	service.mu.Lock()
	service.listener = listener
	service.mu.Unlock()
}

func (service *RemoteNodeService) SetDesktopAgentBridge(
	snapshotProvider func() DesktopAgentSnapshot,
	commandHandler func(DesktopAgentCommand) DesktopAgentCommandResult,
) {
	service.mu.Lock()
	service.agentSnapshotProvider = snapshotProvider
	service.agentCommandHandler = commandHandler
	service.mu.Unlock()
}

func (service *RemoteNodeService) SetGitHubToken(token string) {
	service.mu.Lock()
	service.githubToken = strings.TrimSpace(token)
	service.mu.Unlock()
}

func (service *RemoteNodeService) Snapshot() RemoteNodeSnapshot {
	service.mu.Lock()
	defer service.mu.Unlock()
	return service.snapshotLocked()
}

func (service *RemoteNodeService) DiscoverDevices(ctx context.Context) ([]RemoteDiscoveredDevice, error) {
	return discoverLANDevices(ctx, 1800*time.Millisecond)
}

func (service *RemoteNodeService) DiscoverADBDevices(ctx context.Context) ([]RemoteADBDevice, error) {
	return discoverADBDevices(ctx)
}

func (service *RemoteNodeService) SaveConfig(input RemoteNodeConfig) (RemoteNodeSnapshot, error) {
	service.mu.Lock()
	defer service.mu.Unlock()
	if service.cancel != nil {
		return service.snapshotLocked(), errors.New("请先停止内置远程节点再修改配置")
	}
	config, err := service.normalizedConfigLocked(input)
	if err != nil {
		return service.snapshotLocked(), err
	}
	if err := saveNodeConfig(service.configPath, config); err != nil {
		return service.snapshotLocked(), err
	}
	service.config = config
	return service.snapshotLocked(), nil
}

func (service *RemoteNodeService) Start(input RemoteNodeConfig, pairCode string) (RemoteNodeSnapshot, error) {
	return service.start(input, pairCode, "")
}

func (service *RemoteNodeService) StartWithGitHubToken(input RemoteNodeConfig, pairCode, githubToken string) (RemoteNodeSnapshot, error) {
	return service.start(input, pairCode, githubToken)
}

func (service *RemoteNodeService) start(input RemoteNodeConfig, pairCode, githubToken string) (RemoteNodeSnapshot, error) {
	snapshot, err := service.SaveConfig(input)
	if err != nil {
		return snapshot, err
	}
	pairCode = strings.TrimSpace(pairCode)
	service.mu.Lock()
	if service.cancel != nil {
		snapshot = service.snapshotLocked()
		service.mu.Unlock()
		return snapshot, errors.New("内置远程节点已经在运行")
	}
	runContext, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	config := service.config
	service.cancel = cancel
	service.done = done
	service.status = RemoteNodeStatus{Phase: string(nodePhaseConnecting), Message: "正在验证配置并连接手机…", Running: true}
	snapshot = service.snapshotLocked()
	service.mu.Unlock()
	service.notify(snapshot)
	go service.run(runContext, done, config, pairCode, strings.TrimSpace(githubToken))
	return snapshot, nil
}

func (service *RemoteNodeService) Stop() RemoteNodeSnapshot {
	service.mu.Lock()
	cancel := service.cancel
	if cancel != nil {
		service.status = RemoteNodeStatus{Phase: string(nodePhaseConnecting), Message: "正在安全停止内置远程节点…", Running: true}
	}
	snapshot := service.snapshotLocked()
	service.mu.Unlock()
	if cancel != nil {
		cancel()
		service.notify(snapshot)
	}
	return snapshot
}

func (service *RemoteNodeService) ClearPairing() (RemoteNodeSnapshot, error) {
	service.mu.Lock()
	defer service.mu.Unlock()
	if service.cancel != nil {
		return service.snapshotLocked(), errors.New("请先停止内置远程节点再清除配对")
	}
	config, err := clearNodePairing(service.configPath, service.config)
	if err != nil {
		return service.snapshotLocked(), err
	}
	service.config = config
	return service.snapshotLocked(), nil
}

// RevokePairing removes the phone-side trust record before clearing the local
// credential. A transport failure keeps the local credential intact so the UI
// cannot report a successful revocation that never reached the other device.
func (service *RemoteNodeService) RevokePairing(ctx context.Context) (RemoteNodeSnapshot, error) {
	service.mu.Lock()
	if service.cancel != nil {
		snapshot := service.snapshotLocked()
		service.mu.Unlock()
		return snapshot, errors.New("请先停止内置远程节点再撤销配对")
	}
	config := service.config
	identity := service.identity
	snapshot := service.snapshotLocked()
	service.mu.Unlock()
	if strings.TrimSpace(config.ProtectedToken) == "" {
		return snapshot, nil
	}

	api, err := openPairedAPI(ctx, config, identity)
	if err != nil {
		return service.Snapshot(), fmt.Errorf("无法连接已配对设备，未清除本机凭据：%w", err)
	}
	revokeErr := api.postJSON(ctx, unpairPath, map[string]string{}, nil)
	api.Close()
	if revokeErr != nil && !remotePairingAlreadyRevoked(revokeErr) {
		return service.Snapshot(), fmt.Errorf("手机端撤销失败，未清除本机凭据：%w", revokeErr)
	}

	service.mu.Lock()
	if service.cancel != nil || service.config.ProtectedToken != config.ProtectedToken {
		snapshot = service.snapshotLocked()
		service.mu.Unlock()
		return snapshot, errors.New("远程节点状态已变化，请刷新后重试")
	}
	cleared, clearErr := clearNodePairing(service.configPath, service.config)
	if clearErr == nil {
		service.config = cleared
	}
	snapshot = service.snapshotLocked()
	service.mu.Unlock()
	if clearErr != nil {
		return snapshot, clearErr
	}
	service.notify(snapshot)
	return snapshot, nil
}

func remotePairingAlreadyRevoked(err error) bool {
	var apiErr *apiError
	return errors.As(err, &apiErr) && (apiErr.Status == 401 || apiErr.Status == 403)
}

func (service *RemoteNodeService) RotateTemporaryCode() (RemoteNodeSnapshot, error) {
	if _, err := service.pairingAuthenticator.RotateTemporaryCode(); err != nil {
		return service.Snapshot(), err
	}
	snapshot := service.Snapshot()
	service.notify(snapshot)
	return snapshot, nil
}

func (service *RemoteNodeService) SetSecurityPassword(password string) (RemoteNodeSnapshot, error) {
	if err := service.pairingAuthenticator.SetSecurityPassword(password); err != nil {
		return service.Snapshot(), err
	}
	snapshot := service.Snapshot()
	service.notify(snapshot)
	return snapshot, nil
}

func (service *RemoteNodeService) ClearSecurityPassword() (RemoteNodeSnapshot, error) {
	if err := service.pairingAuthenticator.ClearSecurityPassword(); err != nil {
		return service.Snapshot(), err
	}
	snapshot := service.Snapshot()
	service.notify(snapshot)
	return snapshot, nil
}

func (service *RemoteNodeService) SetDoNotDisturb(enabled bool) (RemoteNodeSnapshot, error) {
	if service.connectionPolicy == nil {
		return service.Snapshot(), errors.New("电脑连接策略尚未初始化")
	}
	if err := service.connectionPolicy.SetDoNotDisturb(enabled); err != nil {
		return service.Snapshot(), err
	}
	snapshot := service.Snapshot()
	service.notify(snapshot)
	return snapshot, nil
}

func (service *RemoteNodeService) UnblockPeer(deviceID string) (RemoteNodeSnapshot, error) {
	if service.connectionPolicy == nil {
		return service.Snapshot(), errors.New("电脑连接策略尚未初始化")
	}
	if _, err := service.connectionPolicy.Unblock(deviceID); err != nil {
		return service.Snapshot(), err
	}
	snapshot := service.Snapshot()
	service.notify(snapshot)
	return snapshot, nil
}

func (service *RemoteNodeService) ApproveConnectionRequest(requestID string) (RemoteNodeSnapshot, error) {
	service.mu.Lock()
	pending, exists := service.connectionRequests[strings.TrimSpace(requestID)]
	if !exists {
		snapshot := service.snapshotLocked()
		service.mu.Unlock()
		return snapshot, errors.New("连接申请不存在或已过期")
	}
	if service.cancel != nil {
		snapshot := service.snapshotLocked()
		service.mu.Unlock()
		return snapshot, errors.New("请先停止当前远程连接")
	}
	input := service.snapshotLocked().Config
	input.ConnectionMode = ConnectionModeDeviceID
	input.PairingAuthMethod = connectionTemporaryCodeAuth
	input.PhoneURL = ""
	input.ADBSerial = ""
	input.PeerDeviceID = pending.summary.DeviceID
	input.PeerDeviceDisplayID = pending.summary.DeviceDisplayID
	input.PeerFingerprint = pending.summary.Fingerprint
	if pending.phoneURL != "" {
		input.ConnectionMode = ConnectionModeDirect
		input.PhoneURL = pending.phoneURL
	}
	service.mu.Unlock()
	if _, err := service.SaveConfig(input); err != nil {
		_ = service.relayListener.Respond(pending.request, false, err.Error(), "")
		return service.Snapshot(), err
	}
	if err := service.relayListener.Respond(pending.request, true, "", pending.serverProof); err != nil {
		return service.Snapshot(), err
	}
	service.mu.Lock()
	delete(service.connectionRequests, requestID)
	service.mu.Unlock()
	snapshot, err := service.Start(input, "")
	service.notify(snapshot)
	return snapshot, err
}

func (service *RemoteNodeService) RejectConnectionRequest(requestID string) (RemoteNodeSnapshot, error) {
	service.mu.Lock()
	pending, exists := service.connectionRequests[strings.TrimSpace(requestID)]
	if !exists {
		snapshot := service.snapshotLocked()
		service.mu.Unlock()
		return snapshot, errors.New("连接申请不存在或已过期")
	}
	delete(service.connectionRequests, requestID)
	snapshot := service.snapshotLocked()
	service.mu.Unlock()
	if err := service.relayListener.Respond(pending.request, false, "对方拒绝了连接申请", ""); err != nil {
		return snapshot, err
	}
	service.notify(snapshot)
	return snapshot, nil
}

func (service *RemoteNodeService) BlockConnectionRequest(requestID string) (RemoteNodeSnapshot, error) {
	service.mu.Lock()
	pending, exists := service.connectionRequests[strings.TrimSpace(requestID)]
	if !exists {
		snapshot := service.snapshotLocked()
		service.mu.Unlock()
		return snapshot, errors.New("连接申请不存在或已过期")
	}
	service.mu.Unlock()
	if service.connectionPolicy == nil {
		return service.Snapshot(), errors.New("电脑连接策略尚未初始化")
	}
	if err := service.connectionPolicy.Block(RemoteBlockedPeer{
		DeviceID: pending.summary.DeviceID, DeviceDisplayID: pending.summary.DeviceDisplayID,
		DeviceName: pending.summary.DeviceName, Fingerprint: pending.summary.Fingerprint,
	}); err != nil {
		return service.Snapshot(), err
	}
	service.mu.Lock()
	delete(service.connectionRequests, pending.summary.RequestID)
	var cancel context.CancelFunc
	if service.config.PairedDeviceID == pending.summary.DeviceID ||
		service.config.PairedDeviceFingerprint == pending.summary.Fingerprint {
		nextConfig := service.config
		nextConfig.ProtectedToken = ""
		nextConfig.ProtectedSyncKey = ""
		nextConfig.PairedDeviceID = ""
		nextConfig.PairedDeviceFingerprint = ""
		if err := saveNodeConfig(service.configPath, nextConfig); err != nil {
			service.mu.Unlock()
			return service.Snapshot(), err
		}
		service.config = nextConfig
		cancel = service.cancel
	}
	snapshot := service.snapshotLocked()
	service.mu.Unlock()
	if cancel != nil {
		cancel()
	}
	if service.relayListener != nil {
		if err := service.relayListener.Respond(pending.request, false, "对方已将该设备拉黑", ""); err != nil {
			return snapshot, err
		}
	}
	service.notify(snapshot)
	return snapshot, nil
}

func (service *RemoteNodeService) Close(timeout time.Duration) {
	if service.pairingRefreshCancel != nil {
		service.pairingRefreshCancel()
	}
	if service.relayListener != nil {
		service.relayListener.Close(timeout)
	}
	if service.discoveryResponder != nil {
		service.discoveryResponder.Close(timeout)
	}
	service.mu.Lock()
	cancel := service.cancel
	done := service.done
	service.mu.Unlock()
	if cancel == nil {
		return
	}
	cancel()
	if timeout <= 0 || done == nil {
		return
	}
	select {
	case <-done:
	case <-time.After(timeout):
	}
}

func (service *RemoteNodeService) refreshTemporaryCodes(ctx context.Context) {
	previous := service.pairingAuthenticator.Snapshot(time.Now()).TemporaryCode
	ticker := time.NewTicker(15 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case now := <-ticker.C:
			current := service.pairingAuthenticator.Snapshot(now).TemporaryCode
			expired := service.expireConnectionRequests(now)
			if current != previous || expired {
				previous = current
				service.notify(service.Snapshot())
			}
		}
	}
}

func (service *RemoteNodeService) expireConnectionRequests(now time.Time) bool {
	service.mu.Lock()
	defer service.mu.Unlock()
	removed := false
	cutoff := now.Add(-2 * time.Minute).UnixMilli()
	for requestID, pending := range service.connectionRequests {
		if pending.summary.CreatedAt <= cutoff {
			delete(service.connectionRequests, requestID)
			removed = true
		}
	}
	for deviceID, attempts := range service.connectionAttempts {
		filtered := attempts[:0]
		for _, attempt := range attempts {
			if attempt.After(now.Add(-time.Minute)) {
				filtered = append(filtered, attempt)
			}
		}
		if len(filtered) == 0 {
			delete(service.connectionAttempts, deviceID)
		} else {
			service.connectionAttempts[deviceID] = filtered
		}
	}
	return removed
}

func (service *RemoteNodeService) handleRelayConnectionRequest(incoming deviceRelayIncomingRequest) {
	now := time.Now().UnixMilli()
	summary := RemoteConnectionRequest{
		RequestID:       incoming.message.RequestID,
		DeviceID:        incoming.message.SourceDeviceID,
		DeviceDisplayID: formatDeviceID(incoming.message.SourceDeviceID),
		DeviceName:      strings.TrimSpace(incoming.message.DeviceName),
		Platform:        incoming.message.Platform,
		Fingerprint:     incoming.fingerprint,
		CreatedAt:       now,
	}
	if summary.DeviceName == "" {
		summary.DeviceName = "Murong 手机"
	}
	if service.connectionPolicy != nil && service.connectionPolicy.IsBlocked(summary.DeviceID, summary.Fingerprint) {
		if service.relayListener != nil {
			_ = service.relayListener.Respond(incoming.message, false, "该设备已被拉黑", "")
		}
		return
	}
	serverProof := ""
	authenticated := false
	switch incoming.message.AuthMethod {
	case "":
	case connectionGitHubAccountAuth:
		authenticated = service.verifyGitHubRelayRequest(incoming)
	case connectionTemporaryCodeAuth, connectionSecurityPasswordAuth:
		serverProof, authenticated = service.pairingAuthenticator.Authenticate(
			incoming.message, incoming.fingerprint, time.Now(),
		)
		if !authenticated {
			if service.relayListener != nil {
				_ = service.relayListener.Respond(incoming.message, false, "临时验证码或安全密码无效", "")
			}
			return
		}
	default:
		if service.relayListener != nil {
			_ = service.relayListener.Respond(incoming.message, false, "连接认证方式无效", "")
		}
		return
	}
	doNotDisturb := false
	if service.connectionPolicy != nil {
		doNotDisturb, _ = service.connectionPolicy.Snapshot()
	}
	phoneURL := service.discoverRequestPhoneURL(summary.DeviceID, summary.Fingerprint)
	service.mu.Lock()
	trusted := service.config.PairedDeviceID == summary.DeviceID &&
		service.config.PairedDeviceFingerprint != "" && service.config.PairedDeviceFingerprint == summary.Fingerprint
	if doNotDisturb && !trusted && !authenticated {
		service.mu.Unlock()
		if service.relayListener != nil {
			_ = service.relayListener.Respond(incoming.message, false, "对方已开启免打扰", "")
		}
		return
	}
	if !trusted && !authenticated && !service.allowConnectionRequestLocked(summary.DeviceID, time.Now()) {
		service.mu.Unlock()
		if service.relayListener != nil {
			_ = service.relayListener.Respond(incoming.message, false, "连接申请过于频繁，请稍后再试", "")
		}
		return
	}
	for _, existing := range service.connectionRequests {
		if existing.summary.DeviceID == summary.DeviceID {
			service.mu.Unlock()
			if service.relayListener != nil {
				_ = service.relayListener.Respond(incoming.message, false, "该设备已有待确认的连接申请", "")
			}
			return
		}
	}
	if len(service.connectionRequests) >= 16 {
		service.mu.Unlock()
		if service.relayListener != nil {
			_ = service.relayListener.Respond(incoming.message, false, "待确认连接申请已满", "")
		}
		return
	}
	if _, exists := service.connectionRequests[summary.RequestID]; exists {
		service.mu.Unlock()
		return
	}
	service.connectionRequests[summary.RequestID] = pendingRemoteConnection{
		request: incoming.message, summary: summary, serverProof: serverProof,
		authenticated: authenticated, phoneURL: phoneURL,
	}
	autoApprove := trusted && service.cancel == nil
	autoApprove = autoApprove || authenticated && service.cancel == nil
	snapshot := service.snapshotLocked()
	service.mu.Unlock()
	if autoApprove {
		go func() { _, _ = service.ApproveConnectionRequest(summary.RequestID) }()
		return
	}
	service.notify(snapshot)
}

func (service *RemoteNodeService) discoverRequestPhoneURL(deviceID, fingerprint string) string {
	ctx, cancel := context.WithTimeout(context.Background(), 1500*time.Millisecond)
	defer cancel()
	devices, err := discoverLANDevices(ctx, 1200*time.Millisecond)
	if err != nil {
		return ""
	}
	for _, device := range devices {
		if device.DeviceID == deviceID && device.Fingerprint == fingerprint {
			return device.URL
		}
	}
	return ""
}

func (service *RemoteNodeService) allowConnectionRequestLocked(deviceID string, now time.Time) bool {
	if service.connectionAttempts == nil {
		service.connectionAttempts = make(map[string][]time.Time)
	}
	cutoff := now.Add(-time.Minute)
	attempts := service.connectionAttempts[deviceID][:0]
	for _, attempt := range service.connectionAttempts[deviceID] {
		if attempt.After(cutoff) {
			attempts = append(attempts, attempt)
		}
	}
	if len(attempts) >= 3 {
		service.connectionAttempts[deviceID] = attempts
		return false
	}
	service.connectionAttempts[deviceID] = append(attempts, now)
	return true
}

func (service *RemoteNodeService) verifyGitHubRelayRequest(incoming deviceRelayIncomingRequest) bool {
	if service.githubTrustClient == nil || !validAccountProofTicket(incoming.message.AuthProof) {
		return false
	}
	service.mu.Lock()
	protectedSession := service.config.ProtectedGitHubSession
	githubToken := service.githubToken
	identity := service.identity
	service.mu.Unlock()
	if identity == nil {
		return false
	}
	session := ""
	if protectedSession != "" {
		plain, err := unprotectSecret(protectedSession)
		if err == nil {
			session = string(plain)
		}
		clearBytes(plain)
	}
	request := connectionRequest{
		RequestID: incoming.message.RequestID, ClientName: incoming.message.DeviceName,
		DeviceID: incoming.message.SourceDeviceID, DevicePublicKey: incoming.message.SourcePublicKey,
		DeviceFingerprint: incoming.fingerprint, EphemeralPublicKey: incoming.message.EphemeralPublicKey,
		Platform: incoming.message.Platform, IssuedAt: incoming.message.IssuedAt,
		AuthMethod: connectionGitHubAccountAuth, AuthProof: incoming.message.AuthProof,
	}
	ctx, cancel := context.WithTimeout(context.Background(), 35*time.Second)
	defer cancel()
	refreshSession := func() bool {
		if githubToken == "" {
			return false
		}
		active, err := service.githubTrustClient.loginWithGitHubToken(ctx, githubToken)
		if err != nil {
			return false
		}
		session = active.SessionToken
		_ = service.cacheGitHubSession(session)
		return true
	}
	if session == "" && !refreshSession() {
		return false
	}
	trusted, err := service.githubTrustClient.verifyProof(
		ctx, session, identity, request, incoming.message.AuthProof,
	)
	if err != nil && isGitHubAccountBackendUnauthorized(err) && refreshSession() {
		trusted, err = service.githubTrustClient.verifyProof(
			ctx, session, identity, request, incoming.message.AuthProof,
		)
	}
	return err == nil && trusted
}

func (service *RemoteNodeService) cacheGitHubSession(session string) error {
	if strings.TrimSpace(session) == "" {
		return errors.New("账号证明会话为空")
	}
	protected, err := protectSecret([]byte(session))
	if err != nil {
		return err
	}
	service.mu.Lock()
	defer service.mu.Unlock()
	next := service.config
	next.ProtectedGitHubSession = protected
	if err := saveNodeConfig(service.configPath, next); err != nil {
		return err
	}
	service.config = next
	return nil
}

func (service *RemoteNodeService) handleDeviceServiceState(online bool) {
	service.mu.Lock()
	if service.deviceServiceOnline == online {
		service.mu.Unlock()
		return
	}
	service.deviceServiceOnline = online
	snapshot := service.snapshotLocked()
	service.mu.Unlock()
	service.notify(snapshot)
}

func (service *RemoteNodeService) PushCredentials(
	ctx context.Context,
	bundle CredentialSyncBundle,
) (CredentialSyncResult, error) {
	service.mu.Lock()
	node := service.node
	connected := service.status.Phase == string(nodePhaseConnected)
	service.mu.Unlock()
	if node == nil || !connected {
		return CredentialSyncResult{}, errors.New("请先连接手机远程节点")
	}
	return node.pushCredentials(ctx, bundle)
}

func (service *RemoteNodeService) PullCredentials(
	ctx context.Context,
	options DeviceSyncOptions,
) (CredentialSyncBundle, error) {
	service.mu.Lock()
	node := service.node
	connected := service.status.Phase == string(nodePhaseConnected)
	service.mu.Unlock()
	if node == nil || !connected {
		return CredentialSyncBundle{}, errors.New("请先连接手机远程节点")
	}
	return node.pullCredentials(ctx, options)
}

func (service *RemoteNodeService) run(ctx context.Context, done chan struct{}, config nodeConfig, pairCode, githubToken string) {
	defer close(done)
	prepareContext, prepareCancel := context.WithTimeout(ctx, 3*time.Minute)
	launch, err := prepareNodeWithGitHubToken(prepareContext, service.configPath, config, pairCode, githubToken)
	prepareCancel()
	if err == nil {
		service.mu.Lock()
		service.config = launch.config
		service.identity = launch.identity
		service.node = launch.node
		launch.node.agentSnapshotProvider = service.agentSnapshotProvider
		launch.node.agentCommandHandler = service.agentCommandHandler
		service.mu.Unlock()
		launch.node.onStatus = func(status nodeRuntimeStatus) {
			service.updateStatus(RemoteNodeStatus{Phase: string(status.Phase), Message: status.Message, Running: status.Phase != nodePhaseStopped && status.Phase != nodePhaseError})
		}
		defer launch.api.Close()
		err = launch.node.run(ctx)
	}
	service.mu.Lock()
	service.node = nil
	service.cancel = nil
	service.done = nil
	if err == nil || errors.Is(err, context.Canceled) {
		service.status = RemoteNodeStatus{Phase: string(nodePhaseStopped), Message: "内置远程节点已停止"}
	} else {
		service.status = RemoteNodeStatus{Phase: string(nodePhaseError), Message: err.Error()}
	}
	snapshot := service.snapshotLocked()
	service.mu.Unlock()
	service.notify(snapshot)
}

func (service *RemoteNodeService) updateStatus(status RemoteNodeStatus) {
	service.mu.Lock()
	service.status = status
	snapshot := service.snapshotLocked()
	service.mu.Unlock()
	service.notify(snapshot)
}

func (service *RemoteNodeService) notify(snapshot RemoteNodeSnapshot) {
	service.mu.Lock()
	listener := service.listener
	service.mu.Unlock()
	if listener != nil {
		listener(snapshot)
	}
}

func (service *RemoteNodeService) normalizedConfigLocked(input RemoteNodeConfig) (nodeConfig, error) {
	config := service.config
	connectionMode := strings.TrimSpace(input.ConnectionMode)
	if connectionMode == "" {
		connectionMode = ConnectionModeDirect
	}
	if connectionMode != ConnectionModeDirect && connectionMode != ConnectionModeDeviceID && connectionMode != ConnectionModeADB {
		return config, errors.New("手机连接方式无效")
	}
	pairingAuthMethod := strings.TrimSpace(input.PairingAuthMethod)
	if pairingAuthMethod == "" {
		pairingAuthMethod = connectionTemporaryCodeAuth
	}
	if pairingAuthMethod != connectionTemporaryCodeAuth && pairingAuthMethod != connectionSecurityPasswordAuth {
		return config, errors.New("密码连接认证方式无效")
	}
	previousConnectionMode := strings.TrimSpace(config.ConnectionMode)
	if previousConnectionMode == "" {
		previousConnectionMode = ConnectionModeDirect
	}
	if connectionMode != previousConnectionMode {
		config.ProtectedToken = ""
		config.ProtectedSyncKey = ""
		config.PairedDeviceID = ""
		config.PairedDeviceFingerprint = ""
	}
	phone := strings.TrimSpace(input.PhoneURL)
	if phone != config.PhoneURL {
		config.ProtectedToken = ""
		config.ProtectedSyncKey = ""
		config.PairedDeviceID = ""
		config.PairedDeviceFingerprint = ""
	}
	if phone != "" {
		if _, err := validatePhoneURL(phone); err != nil {
			return config, err
		}
	}
	adbSerial := strings.TrimSpace(input.ADBSerial)
	if len(adbSerial) > 128 || strings.ContainsAny(adbSerial, "\r\n\x00") {
		return config, errors.New("ADB 设备序列号无效")
	}
	if adbSerial != config.ADBSerial {
		config.ProtectedToken = ""
		config.ProtectedSyncKey = ""
		config.PairedDeviceID = ""
		config.PairedDeviceFingerprint = ""
	}
	peerDeviceID := strings.TrimSpace(input.PeerDeviceID)
	if connectionMode == ConnectionModeADB {
		if connectionMode == previousConnectionMode {
			peerDeviceID = config.PairedDeviceID
		} else {
			peerDeviceID = ""
		}
	}
	if peerDeviceID != "" {
		normalized, err := normalizeDeviceID(peerDeviceID)
		if err != nil {
			return config, err
		}
		config.PairedDeviceID = normalized
		peerFingerprint := strings.TrimSpace(input.PeerFingerprint)
		if connectionMode == ConnectionModeADB && connectionMode == previousConnectionMode {
			peerFingerprint = config.PairedDeviceFingerprint
		}
		if peerFingerprint != "" {
			decoded, decodeErr := decodeBase64URL(peerFingerprint, 32)
			if decodeErr != nil || len(decoded) != 32 {
				return config, errors.New("手机设备指纹无效")
			}
			config.PairedDeviceFingerprint = peerFingerprint
		} else {
			config.PairedDeviceFingerprint = ""
		}
	} else {
		config.PairedDeviceID = ""
		config.PairedDeviceFingerprint = ""
	}
	if connectionMode == ConnectionModeDeviceID && config.PairedDeviceID == "" {
		return config, errors.New("请输入手机的 16 位本机 ID")
	}
	workspace := strings.TrimSpace(input.Workspace)
	if workspace != "" {
		absolute, err := filepath.Abs(workspace)
		if err != nil {
			return config, err
		}
		info, err := os.Stat(absolute)
		if err != nil || !info.IsDir() {
			return config, errors.New("远程节点工作区不存在或不是目录")
		}
		workspace = filepath.Clean(absolute)
	}
	available := make(map[string]bool, len(service.inventory.Backends))
	for _, backend := range service.inventory.Backends {
		available[backend.ID] = true
	}
	terminals := make([]string, 0, len(input.TerminalBackends))
	seen := map[string]bool{}
	for _, id := range input.TerminalBackends {
		id = strings.TrimSpace(id)
		if id == "" || seen[id] {
			continue
		}
		if !available[id] {
			return config, errors.New("选择了当前电脑不可用的终端：" + id)
		}
		seen[id] = true
		terminals = append(terminals, id)
	}
	config.ConnectionMode = connectionMode
	config.PairingAuthMethod = pairingAuthMethod
	config.PhoneURL = phone
	config.ADBSerial = adbSerial
	config.Workspace = workspace
	config.Label = truncateRunes(input.Label, 80)
	config.ClientName = truncateRunes(input.ClientName, 40)
	config.AllowWrite = input.AllowWrite
	config.ShareDesktopTasks = input.ShareDesktopTasks
	config.AllowAgentControl = input.ShareDesktopTasks && input.AllowAgentControl
	config.TerminalBackends = terminals
	config.AllowTerminal = len(terminals) > 0
	return config, nil
}

func (service *RemoteNodeService) snapshotLocked() RemoteNodeSnapshot {
	terminals := make([]RemoteTerminal, 0, len(service.inventory.Backends))
	for _, backend := range service.inventory.Backends {
		terminals = append(terminals, RemoteTerminal{ID: backend.ID, Label: backend.Label, Version: backend.Version})
	}
	status := service.status
	status.Running = service.cancel != nil
	deviceID, deviceDisplayID, deviceFingerprint := "", "", ""
	if service.identity != nil {
		deviceID = service.identity.deviceID
		deviceDisplayID = service.identity.displayID()
		deviceFingerprint = service.identity.fingerprint
	}
	requests := make([]RemoteConnectionRequest, 0, len(service.connectionRequests))
	for _, pending := range service.connectionRequests {
		requests = append(requests, pending.summary)
	}
	authSnapshot := desktopPairingAuthSnapshot{}
	if service.pairingAuthenticator != nil {
		authSnapshot = service.pairingAuthenticator.Snapshot(time.Now())
	}
	doNotDisturb, blockedPeers := false, []RemoteBlockedPeer(nil)
	if service.connectionPolicy != nil {
		doNotDisturb, blockedPeers = service.connectionPolicy.Snapshot()
	}
	return RemoteNodeSnapshot{
		Config: RemoteNodeConfig{
			ConnectionMode: service.config.ConnectionMode, PairingAuthMethod: service.config.PairingAuthMethod,
			PhoneURL: service.config.PhoneURL, ADBSerial: service.config.ADBSerial,
			Workspace: service.config.Workspace, Label: service.config.Label,
			ClientName: service.config.ClientName, AllowWrite: service.config.AllowWrite,
			ShareDesktopTasks: service.config.ShareDesktopTasks, AllowAgentControl: service.config.AllowAgentControl,
			TerminalBackends: append([]string(nil), service.config.TerminalBackends...), Paired: service.config.ProtectedToken != "",
			SecureSyncReady: service.config.ProtectedSyncKey != "",
			DeviceID:        deviceID, DeviceDisplayID: deviceDisplayID, DeviceFingerprint: deviceFingerprint,
			PeerDeviceID: service.config.PairedDeviceID, PeerDeviceDisplayID: formatDeviceID(service.config.PairedDeviceID),
			PeerFingerprint: service.config.PairedDeviceFingerprint,
		},
		Status: status, Terminals: terminals, ConnectionRequests: requests,
		DeviceServiceOnline: service.deviceServiceOnline, ConfigPath: service.configPath,
		TemporaryCode:              authSnapshot.TemporaryCode,
		TemporaryCodeExpiresAt:     authSnapshot.TemporaryCodeExpiresAt,
		SecurityPasswordConfigured: authSnapshot.SecurityPasswordConfigured,
		DoNotDisturb:               doNotDisturb,
		BlockedPeers:               blockedPeers,
	}
}
