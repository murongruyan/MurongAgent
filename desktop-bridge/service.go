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
	ConnectionMode       string   `json:"connectionMode"`
	PhoneURL             string   `json:"phoneUrl"`
	CloudRelayURL        string   `json:"cloudRelayUrl"`
	CloudRelayCode       string   `json:"cloudRelayCode,omitempty"`
	CloudRelayRoomID     string   `json:"cloudRelayRoomId,omitempty"`
	CloudRelayConfigured bool     `json:"cloudRelayConfigured"`
	Workspace            string   `json:"workspace"`
	Label                string   `json:"label"`
	ClientName           string   `json:"clientName"`
	AllowWrite           bool     `json:"allowWrite"`
	ShareDesktopTasks    bool     `json:"shareDesktopTasks"`
	AllowAgentControl    bool     `json:"allowAgentControl"`
	TerminalBackends     []string `json:"terminalBackends"`
	Paired               bool     `json:"paired"`
	SecureSyncReady      bool     `json:"secureSyncReady"`
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
	Config     RemoteNodeConfig `json:"config"`
	Status     RemoteNodeStatus `json:"status"`
	Terminals  []RemoteTerminal `json:"terminals"`
	ConfigPath string           `json:"configPath"`
}

type RemoteNodeService struct {
	mu                    sync.Mutex
	configPath            string
	config                nodeConfig
	inventory             terminalInventory
	status                RemoteNodeStatus
	cancel                context.CancelFunc
	done                  chan struct{}
	node                  *computerNode
	listener              func(RemoteNodeSnapshot)
	agentSnapshotProvider func() DesktopAgentSnapshot
	agentCommandHandler   func(DesktopAgentCommand) DesktopAgentCommandResult
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
	service := &RemoteNodeService{
		configPath: configPath,
		config:     config,
		inventory:  discoverTerminalInventory(ctx),
		status:     RemoteNodeStatus{Phase: string(nodePhaseStopped), Message: "内置远程节点已停止"},
	}
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

func (service *RemoteNodeService) Snapshot() RemoteNodeSnapshot {
	service.mu.Lock()
	defer service.mu.Unlock()
	return service.snapshotLocked()
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
	if service.config.ProtectedToken == "" && pairCode == "" {
		snapshot = service.snapshotLocked()
		service.mu.Unlock()
		return snapshot, errors.New("尚未配对：请在手机生成一次性配对码并填写")
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
	go service.run(runContext, done, config, pairCode)
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

func (service *RemoteNodeService) Close(timeout time.Duration) {
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

func (service *RemoteNodeService) run(ctx context.Context, done chan struct{}, config nodeConfig, pairCode string) {
	defer close(done)
	prepareContext, prepareCancel := context.WithTimeout(ctx, 30*time.Second)
	launch, err := prepareNode(prepareContext, service.configPath, config, pairCode)
	prepareCancel()
	if err == nil {
		service.mu.Lock()
		service.config = launch.config
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
		connectionMode = CloudRelayConnectionDirect
	}
	if connectionMode != CloudRelayConnectionDirect && connectionMode != CloudRelayConnectionCloud {
		return config, errors.New("手机连接方式无效")
	}
	phone := strings.TrimSpace(input.PhoneURL)
	if phone != config.PhoneURL {
		config.ProtectedToken = ""
		config.ProtectedSyncKey = ""
	}
	if phone != "" {
		if _, err := validatePhoneURL(phone); err != nil {
			return config, err
		}
	}
	cloudRelayURL := strings.TrimSpace(input.CloudRelayURL)
	if cloudRelayURL == "" {
		cloudRelayURL = OfficialCloudRelayURL
	}
	if cloudRelayURL != "" {
		parsed, err := parseCloudRelayURL(cloudRelayURL)
		if err != nil {
			return config, fmt.Errorf("无效的云中继地址：%w", err)
		}
		cloudRelayURL = parsed.String()
	}
	if code := strings.TrimSpace(input.CloudRelayCode); code != "" {
		roomID, secret, err := parseCloudRelayShareCode(code)
		if err != nil {
			return config, err
		}
		protected, protectErr := protectSecret(secret)
		clearBytes(secret)
		if protectErr != nil {
			return config, fmt.Errorf("无法使用本机凭据保护机制保存云中继密钥：%w", protectErr)
		}
		config.CloudRelayRoomID = roomID
		config.ProtectedCloudRelaySecret = protected
		config.ProtectedToken = ""
		config.ProtectedSyncKey = ""
	}
	if connectionMode == CloudRelayConnectionCloud {
		if cloudRelayURL == "" {
			return config, errors.New("云中继地址不能为空")
		}
		if config.CloudRelayRoomID == "" || config.ProtectedCloudRelaySecret == "" {
			return config, errors.New("请粘贴手机生成的云中继连接码")
		}
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
	config.PhoneURL = phone
	config.CloudRelayURL = cloudRelayURL
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
	return RemoteNodeSnapshot{
		Config: RemoteNodeConfig{
			ConnectionMode: service.config.ConnectionMode, PhoneURL: service.config.PhoneURL,
			CloudRelayURL: service.config.CloudRelayURL, CloudRelayRoomID: service.config.CloudRelayRoomID,
			CloudRelayConfigured: service.config.CloudRelayRoomID != "" && service.config.ProtectedCloudRelaySecret != "",
			Workspace:            service.config.Workspace, Label: service.config.Label,
			ClientName: service.config.ClientName, AllowWrite: service.config.AllowWrite,
			ShareDesktopTasks: service.config.ShareDesktopTasks, AllowAgentControl: service.config.AllowAgentControl,
			TerminalBackends: append([]string(nil), service.config.TerminalBackends...), Paired: service.config.ProtectedToken != "",
			SecureSyncReady: service.config.ProtectedSyncKey != "",
		},
		Status: status, Terminals: terminals, ConfigPath: service.configPath,
	}
}
