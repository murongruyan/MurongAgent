package desktopbridge

import (
	"context"
	"errors"
	"fmt"
	"net/url"
	"os"
	"path/filepath"
	"strings"
)

type nodeLaunch struct {
	config     nodeConfig
	identity   *deviceIdentity
	configPath string
	target     *url.URL
	workspace  *workspace
	api        *apiClient
	node       *computerNode
}

func prepareNode(
	ctx context.Context,
	configPath string,
	config nodeConfig,
	pairCode string,
) (*nodeLaunch, error) {
	return prepareNodeWithGitHubToken(ctx, configPath, config, pairCode, "")
}

func prepareNodeWithGitHubToken(
	ctx context.Context,
	configPath string,
	config nodeConfig,
	pairCode string,
	githubToken string,
) (*nodeLaunch, error) {
	config.PhoneURL = strings.TrimSpace(config.PhoneURL)
	config.Workspace = strings.TrimSpace(config.Workspace)
	config.Label = strings.TrimSpace(config.Label)
	config.ClientName = strings.TrimSpace(config.ClientName)
	config.ConnectionMode = strings.TrimSpace(config.ConnectionMode)
	if config.ConnectionMode == "" {
		config.ConnectionMode = ConnectionModeDirect
	}
	config.PairingAuthMethod = strings.TrimSpace(config.PairingAuthMethod)
	if config.PairingAuthMethod == "" {
		config.PairingAuthMethod = connectionTemporaryCodeAuth
	}
	if config.PairingAuthMethod != connectionTemporaryCodeAuth && config.PairingAuthMethod != connectionSecurityPasswordAuth {
		return nil, errors.New("密码连接认证方式无效")
	}
	identity, err := ensureNodeDeviceIdentity(&config)
	if err != nil {
		return nil, err
	}
	var target *url.URL
	var api *apiClient
	var adbChallenge []byte
	switch config.ConnectionMode {
	case ConnectionModeDirect:
		target, err = validatePhoneURL(config.PhoneURL)
		if err != nil {
			return nil, fmt.Errorf("无效的手机地址：%w", err)
		}
		api = newAPIClient(target)
	case ConnectionModeDeviceID:
		deviceRelayURL, parseErr := parseDeviceRelayURL(OfficialDeviceRelayURL)
		if parseErr != nil {
			return nil, fmt.Errorf("无效的公网设备中继地址：%w", parseErr)
		}
		negotiation, negotiationErr := negotiateDeviceRelay(ctx, deviceRelayURL, identity, config.PairedDeviceID)
		if negotiationErr != nil {
			return nil, negotiationErr
		}
		if config.PairedDeviceFingerprint != "" && config.PairedDeviceFingerprint != negotiation.PeerFingerprint {
			clearBytes(negotiation.Secret)
			return nil, errors.New("手机稳定身份指纹与已保存记录不一致")
		}
		target = negotiation.RelayURL
		config.PairedDeviceID = negotiation.PeerDeviceID
		config.PairedDeviceFingerprint = negotiation.PeerFingerprint
		api, err = newDeviceTunnelAPIClient(target, negotiation.RoomID, negotiation.Secret)
		clearBytes(negotiation.Secret)
		if err != nil {
			return nil, err
		}
	case ConnectionModeADB:
		var cleanup func()
		var serial string
		target, adbChallenge, cleanup, serial, err = prepareADBTransport(ctx, config.ADBSerial)
		if err != nil {
			return nil, err
		}
		config.ADBSerial = serial
		adbAPI := newAPIClient(target)
		adbAPI.close = func() {
			adbAPI.httpClient.CloseIdleConnections()
			cleanup()
		}
		api = adbAPI
	default:
		return nil, errors.New("手机连接方式无效")
	}
	defer clearBytes(adbChallenge)
	succeeded := false
	defer func() {
		if !succeeded && api != nil {
			api.Close()
		}
	}()
	root, err := newWorkspace(config.Workspace)
	if err != nil {
		return nil, fmt.Errorf("无效的工作区：%w", err)
	}
	if config.Label == "" {
		config.Label = filepath.Base(root.root)
		if config.Label == "." || config.Label == string(filepath.Separator) || config.Label == "" {
			config.Label = "Desktop Workspace"
		}
	}
	if config.ClientName == "" {
		hostname, _ := os.Hostname()
		config.ClientName = strings.TrimSpace(hostname + " Desktop Node")
	}
	config.ClientName = truncateRunes(config.ClientName, 40)
	config.Label = truncateRunes(config.Label, 80)
	inventory := discoverTerminalInventory(ctx)
	config, terminals, err := resolveConfiguredTerminals(config, inventory)
	if err != nil {
		return nil, fmt.Errorf("无效的终端配置：%w", err)
	}

	pairCode = strings.TrimSpace(pairCode)
	if config.ConnectionMode == ConnectionModeADB && pairCode != "" {
		return nil, errors.New("ADB 连接会使用已授权调试密钥自动认证，不需要临时验证码")
	}
	if pairCode != "" {
		config.ProtectedToken = ""
		config.ProtectedSyncKey = ""
		config.PairedDeviceID = ""
		config.PairedDeviceFingerprint = ""
	}
	if config.ProtectedToken == "" {
		var token string
		var syncKey []byte
		if config.ConnectionMode == ConnectionModeADB {
			credentials, connectErr := api.connectByRequestWithAuth(
				ctx,
				identity,
				config.ClientName,
				config.PairedDeviceID,
				config.PairedDeviceFingerprint,
				connectionADBAuth,
				func(request connectionRequest) (string, error) {
					return adbProofForRequest(adbChallenge, request)
				},
			)
			if connectErr != nil {
				return nil, fmt.Errorf("ADB 安全连接失败：%w", connectErr)
			}
			token, syncKey = credentials.token, credentials.syncKey
			config.PairedDeviceID = credentials.peerDeviceID
			config.PairedDeviceFingerprint = credentials.peerFingerprint
		} else if pairCode == "" && (config.ConnectionMode == ConnectionModeDirect || config.ConnectionMode == ConnectionModeDeviceID) {
			credentials, githubSession, connectErr := connectDirectWithoutCode(
				ctx, api, identity, config, githubToken,
			)
			if connectErr != nil {
				return nil, fmt.Errorf("连接申请失败：%w", connectErr)
			}
			token, syncKey = credentials.token, credentials.syncKey
			config.PairedDeviceID = credentials.peerDeviceID
			config.PairedDeviceFingerprint = credentials.peerFingerprint
			if githubSession != "" {
				protectedSession, protectSessionErr := protectSecret([]byte(githubSession))
				if protectSessionErr != nil {
					clearBytes(syncKey)
					return nil, fmt.Errorf("无法保护 GitHub 账号会话：%w", protectSessionErr)
				}
				config.ProtectedGitHubSession = protectedSession
			}
		} else if config.ConnectionMode == ConnectionModeDirect || config.ConnectionMode == ConnectionModeDeviceID {
			credentials, connectErr := api.connectByRequestWithSCRAM(
				ctx,
				identity,
				config.ClientName,
				config.PairedDeviceID,
				config.PairedDeviceFingerprint,
				config.PairingAuthMethod,
				pairCode,
			)
			if connectErr != nil {
				return nil, fmt.Errorf("密码安全连接失败：%w", connectErr)
			}
			token, syncKey = credentials.token, credentials.syncKey
			config.PairedDeviceID = credentials.peerDeviceID
			config.PairedDeviceFingerprint = credentials.peerFingerprint
		} else {
			var pairErr error
			token, syncKey, pairErr = api.pair(ctx, pairCode, config.ClientName)
			if pairErr != nil {
				return nil, fmt.Errorf("配对失败：%w", pairErr)
			}
			if config.ConnectionMode == ConnectionModeDirect {
				if peerStatus, statusErr := api.fetchPublicDeviceStatus(ctx); statusErr == nil && validatePublicDeviceStatus(peerStatus, "", "") == nil {
					config.PairedDeviceID = peerStatus.DeviceID
					config.PairedDeviceFingerprint = peerStatus.DeviceFingerprint
				}
			}
		}
		protected, protectErr := protectSecret([]byte(token))
		if protectErr != nil {
			return nil, fmt.Errorf("无法使用本机凭据保护机制保存配对凭据：%w", protectErr)
		}
		config.ProtectedToken = protected
		if len(syncKey) > 0 {
			protectedSyncKey, protectSyncErr := protectSecret(syncKey)
			clearBytes(syncKey)
			if protectSyncErr != nil {
				return nil, fmt.Errorf("无法使用本机凭据保护机制保存设备同步密钥：%w", protectSyncErr)
			}
			config.ProtectedSyncKey = protectedSyncKey
		}
	}
	tokenBytes, err := unprotectSecret(config.ProtectedToken)
	if err != nil {
		return nil, fmt.Errorf("无法解密配对凭据，请清除本机配对后重试：%w", err)
	}
	api.token = string(tokenBytes)
	if err := saveNodeConfig(configPath, config); err != nil {
		return nil, fmt.Errorf("无法保存节点配置：%w", err)
	}
	node, err := newComputerNode(api, root, config, terminals)
	if err != nil {
		return nil, fmt.Errorf("无法初始化 Desktop Node：%w", err)
	}
	succeeded = true
	return &nodeLaunch{
		config:     config,
		identity:   identity,
		configPath: configPath,
		target:     target,
		workspace:  root,
		api:        api,
		node:       node,
	}, nil
}

func connectDirectWithoutCode(
	ctx context.Context,
	api *apiClient,
	identity *deviceIdentity,
	config nodeConfig,
	githubToken string,
) (deviceLinkCredentials, string, error) {
	backendSession := ""
	if config.ProtectedGitHubSession != "" {
		plain, err := unprotectSecret(config.ProtectedGitHubSession)
		if err == nil {
			backendSession = string(plain)
		}
		clearBytes(plain)
	}
	if backendSession != "" || strings.TrimSpace(githubToken) != "" {
		trustClient, err := newGitHubAccountTrustClient(OfficialAccountBackendURL)
		if err == nil {
			credentials, activeSession, connectErr := api.connectByRequestWithGitHubAccount(
				ctx,
				identity,
				config.ClientName,
				config.PairedDeviceID,
				config.PairedDeviceFingerprint,
				trustClient,
				backendSession,
				githubToken,
			)
			if connectErr == nil {
				return credentials, activeSession, nil
			}
			var unavailable *githubAccountProofUnavailableError
			if !errors.As(connectErr, &unavailable) {
				return deviceLinkCredentials{}, "", connectErr
			}
		}
	}
	credentials, err := api.connectByRequest(
		ctx,
		identity,
		config.ClientName,
		config.PairedDeviceID,
		config.PairedDeviceFingerprint,
	)
	return credentials, "", err
}

func clearNodePairing(configPath string, config nodeConfig) (nodeConfig, error) {
	config.ProtectedToken = ""
	config.ProtectedSyncKey = ""
	config.PairedDeviceID = ""
	config.PairedDeviceFingerprint = ""
	if err := saveNodeConfig(configPath, config); err != nil {
		return config, err
	}
	return config, nil
}

// openPairedAPI restores an authenticated transport without creating a new
// pairing. It is used when the user revokes a paired device so the phone can
// remove the matching trust record before the desktop forgets its token.
func openPairedAPI(
	ctx context.Context,
	config nodeConfig,
	identity *deviceIdentity,
) (*apiClient, error) {
	if strings.TrimSpace(config.ProtectedToken) == "" {
		return nil, errors.New("当前电脑没有可撤销的配对凭据")
	}
	tokenBytes, err := unprotectSecret(config.ProtectedToken)
	if err != nil {
		return nil, fmt.Errorf("无法解密配对凭据：%w", err)
	}
	defer clearBytes(tokenBytes)
	if len(tokenBytes) == 0 {
		return nil, errors.New("配对凭据为空")
	}

	connectionMode := strings.TrimSpace(config.ConnectionMode)
	if connectionMode == "" {
		connectionMode = ConnectionModeDirect
	}
	var api *apiClient
	switch connectionMode {
	case ConnectionModeDirect:
		target, parseErr := validatePhoneURL(config.PhoneURL)
		if parseErr != nil {
			return nil, fmt.Errorf("无效的手机地址：%w", parseErr)
		}
		api = newAPIClient(target)
	case ConnectionModeADB:
		target, challenge, cleanup, _, transportErr := prepareADBTransport(ctx, config.ADBSerial)
		clearBytes(challenge)
		if transportErr != nil {
			return nil, transportErr
		}
		api = newAPIClient(target)
		api.close = func() {
			api.httpClient.CloseIdleConnections()
			cleanup()
		}
	case ConnectionModeDeviceID:
		if identity == nil {
			return nil, errors.New("电脑稳定设备身份尚未初始化")
		}
		relayURL, parseErr := parseDeviceRelayURL(OfficialDeviceRelayURL)
		if parseErr != nil {
			return nil, fmt.Errorf("无效的公网设备中继地址：%w", parseErr)
		}
		negotiation, negotiationErr := negotiateDeviceRelay(ctx, relayURL, identity, config.PairedDeviceID)
		if negotiationErr != nil {
			return nil, negotiationErr
		}
		if config.PairedDeviceFingerprint != "" && config.PairedDeviceFingerprint != negotiation.PeerFingerprint {
			clearBytes(negotiation.Secret)
			return nil, errors.New("手机稳定身份指纹与已保存记录不一致")
		}
		api, err = newDeviceTunnelAPIClient(negotiation.RelayURL, negotiation.RoomID, negotiation.Secret)
		clearBytes(negotiation.Secret)
		if err != nil {
			return nil, err
		}
	default:
		return nil, errors.New("手机连接方式无效")
	}
	api.token = string(tokenBytes)
	return api, nil
}
