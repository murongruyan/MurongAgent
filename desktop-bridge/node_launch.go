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
	config.PhoneURL = strings.TrimSpace(config.PhoneURL)
	config.Workspace = strings.TrimSpace(config.Workspace)
	config.Label = strings.TrimSpace(config.Label)
	config.ClientName = strings.TrimSpace(config.ClientName)
	config.ConnectionMode = strings.TrimSpace(config.ConnectionMode)
	if config.ConnectionMode == "" {
		config.ConnectionMode = CloudRelayConnectionDirect
	}
	var target *url.URL
	var api *apiClient
	var err error
	switch config.ConnectionMode {
	case CloudRelayConnectionDirect:
		target, err = validatePhoneURL(config.PhoneURL)
		if err != nil {
			return nil, fmt.Errorf("无效的手机地址：%w", err)
		}
		api = newAPIClient(target)
	case CloudRelayConnectionCloud:
		target, err = parseCloudRelayURL(config.CloudRelayURL)
		if err != nil {
			return nil, fmt.Errorf("无效的云中继地址：%w", err)
		}
		if err := validateCloudRelayRoomID(config.CloudRelayRoomID); err != nil {
			return nil, err
		}
		secret, unprotectErr := unprotectSecret(config.ProtectedCloudRelaySecret)
		if unprotectErr != nil || len(secret) != cloudRelaySecretBytes {
			clearBytes(secret)
			return nil, errors.New("无法解密云中继密钥，请从手机重新复制连接码")
		}
		api, err = newCloudRelayAPIClient(target, config.CloudRelayRoomID, secret)
		clearBytes(secret)
		if err != nil {
			return nil, err
		}
	default:
		return nil, errors.New("手机连接方式无效")
	}
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
	if pairCode != "" {
		config.ProtectedToken = ""
		config.ProtectedSyncKey = ""
	}
	if config.ProtectedToken == "" {
		if pairCode == "" {
			return nil, errors.New("尚未配对：请在手机生成一次性配对码并填写")
		}
		token, syncKey, pairErr := api.pair(ctx, pairCode, config.ClientName)
		if pairErr != nil {
			return nil, fmt.Errorf("配对失败：%w", pairErr)
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
		configPath: configPath,
		target:     target,
		workspace:  root,
		api:        api,
		node:       node,
	}, nil
}

func clearNodePairing(configPath string, config nodeConfig) (nodeConfig, error) {
	config.ProtectedToken = ""
	config.ProtectedSyncKey = ""
	if err := saveNodeConfig(configPath, config); err != nil {
		return config, err
	}
	return config, nil
}
