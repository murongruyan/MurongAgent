package main

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	desktopbridge "github.com/murong-agent/desktop-bridge"
	"github.com/wailsapp/wails/v2/pkg/runtime"
)

type DesktopAgentApp struct {
	ctx                        context.Context
	store                      *desktopStore
	terminals                  []TerminalBackend
	remote                     *desktopbridge.RemoteNodeService
	mcp                        *mcpManager
	workflows                  *savedWorkflowManager
	backups                    *desktopBackupManager
	workspace                  *workspaceChangeTracker
	workbenchTerminals         *workbenchTerminalManager
	audit                      *projectAuditStore
	codex                      *codexAppServer
	mu                         sync.Mutex
	runs                       map[string]context.CancelFunc
	activeSubagentJobs         map[string]activeSubagentJob
	shuttingDown               bool
	approvals                  map[string]chan bool
	pendingApprovals           map[string]ApprovalRequest
	asks                       map[string]chan askResult
	pendingAsks                map[string]AskRequest
	remoteSelectedSessionID    string
	remoteConnectionRequestIDs map[string]bool
	initialLaunchArgs          []string
	applicationTray            applicationTray
	allowApplicationExit       bool
}

func newDesktopAgentApp() (*DesktopAgentApp, error) {
	store, err := newDesktopStore()
	if err != nil {
		return nil, err
	}
	discoveryContext, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	var terminals []TerminalBackend
	var remote *desktopbridge.RemoteNodeService
	var remoteError error
	var wait sync.WaitGroup
	wait.Add(2)
	go func() {
		defer wait.Done()
		terminals = discoverTerminalBackends(discoveryContext)
	}()
	go func() {
		defer wait.Done()
		remote, remoteError = desktopbridge.NewRemoteNodeService(discoveryContext)
	}()
	wait.Wait()
	if remoteError != nil {
		return nil, remoteError
	}
	workflowStore, err := newSavedWorkflowStore(filepath.Join(filepath.Dir(store.configPath), "desktop-agent-workflows.json"))
	if err != nil {
		return nil, err
	}
	workflowManager := newSavedWorkflowManager(workflowStore, store)
	auditStore, err := newProjectAuditStore(filepath.Join(filepath.Dir(store.configPath), "desktop-project-audit-v1.json"))
	if err != nil {
		return nil, err
	}
	backupManager, err := newDesktopBackupManager(store, workflowStore, auditStore)
	if err != nil {
		_ = auditStore.Close()
		return nil, err
	}
	app := &DesktopAgentApp{
		store:                      store,
		terminals:                  terminals,
		remote:                     remote,
		mcp:                        newMCPManager(),
		workflows:                  workflowManager,
		backups:                    backupManager,
		workspace:                  newWorkspaceChangeTracker(),
		audit:                      auditStore,
		runs:                       map[string]context.CancelFunc{},
		activeSubagentJobs:         map[string]activeSubagentJob{},
		approvals:                  map[string]chan bool{},
		pendingApprovals:           map[string]ApprovalRequest{},
		asks:                       map[string]chan askResult{},
		pendingAsks:                map[string]AskRequest{},
		remoteConnectionRequestIDs: map[string]bool{},
	}
	app.workbenchTerminals = newWorkbenchTerminalManager(app.emit)
	app.codex = newCodexAppServer(codexRuntimeRootFromStore(store))
	return app, nil
}

func (app *DesktopAgentApp) startup(ctx context.Context) {
	app.ctx = ctx
	tray, trayErr := startApplicationTray(
		func() {
			runtime.WindowUnminimise(ctx)
			runtime.WindowShow(ctx)
		},
		func() {
			app.mu.Lock()
			app.allowApplicationExit = true
			app.mu.Unlock()
			runtime.Quit(ctx)
		},
	)
	if trayErr != nil {
		runtime.LogErrorf(ctx, "无法创建系统托盘图标：%v", trayErr)
	} else {
		app.mu.Lock()
		app.applicationTray = tray
		app.mu.Unlock()
	}
	go app.store.cleanupOrphanedChatImages()
	ensureApplicationWindowIcon(ctx)
	_ = runtime.InitializeNotifications(ctx)
	_, _ = runtime.RequestNotificationAuthorization(ctx)
	_ = runtime.RegisterNotificationCategory(ctx, runtime.NotificationCategory{
		ID: "remote_connection",
		Actions: []runtime.NotificationAction{
			{ID: "approve", Title: "同意连接"},
			{ID: "reject", Title: "拒绝"},
			{ID: "block", Title: "拉黑"},
		},
	})
	runtime.OnNotificationResponse(ctx, app.handleNotificationResponse)
	app.remote.SetListener(func(snapshot desktopbridge.RemoteNodeSnapshot) {
		app.handleRemoteNodeSnapshot(snapshot)
	})
	app.remote.SetDesktopAgentBridge(app.desktopAgentSnapshot, app.handleDesktopAgentCommand)
	if github, err := app.workflows.store.runtimeGitHub(); err == nil {
		app.remote.SetGitHubToken(github.Token)
	}
	app.mcp.SetListener(func() {
		app.emit("mcp:changed", app.mcpState())
	})
	app.workflows.SetListener(func() {
		app.emit("saved-workflows:changed", app.workflows.State())
	})
	app.backups.SetListener(func() {
		app.emit("backup:changed", app.backups.Status())
	})
	app.codex.SetListener(func(status CodexRuntimeStatus) {
		app.emit("codex:changed", status)
	})
	app.codex.SetServerRequestHandler(app.handleCodexServerRequest)
	if app.workspace != nil {
		app.workspace.SetListener(func(state WorkspaceChangeState) {
			app.emit("workspace:changed", state)
		})
		app.workspace.SetEventListener(app.recordExternalWorkspaceAudit)
		app.workspace.SetProject(app.store.rawConfig().ProjectPath)
	}
	if app.audit != nil {
		app.audit.SetListener(func(page ProjectAuditPage) {
			if strings.TrimSpace(page.ProjectPath) == "" {
				page = app.audit.Query(app.store.rawConfig().ProjectPath, ProjectAuditQuery{Limit: 100})
			}
			app.emit("project-audit:changed", page)
		})
	}
	app.workflows.Start(ctx)
	app.backups.Start(ctx)
	app.mu.Lock()
	initialLaunchArgs := append([]string(nil), app.initialLaunchArgs...)
	app.initialLaunchArgs = nil
	app.mu.Unlock()
	if len(initialLaunchArgs) > 0 {
		go app.handleExternalLaunch(initialLaunchArgs, false)
	}
	go func() {
		configs, err := app.store.mcpRuntimeConfigs()
		if err != nil {
			app.emit("mcp:changed", app.mcpStateWithError(err))
			return
		}
		app.mcp.ConnectAll(ctx, configs, true)
	}()
}

func (app *DesktopAgentApp) shutdown(context.Context) {
	if app.ctx != nil {
		runtime.CleanupNotifications(app.ctx)
	}
	app.mu.Lock()
	app.shuttingDown = true
	tray := app.applicationTray
	app.applicationTray = nil
	for _, cancel := range app.runs {
		cancel()
	}
	for _, active := range app.activeSubagentJobs {
		active.Cancel()
	}
	app.mu.Unlock()
	if tray != nil {
		tray.Close()
	}
	if app.workbenchTerminals != nil {
		app.workbenchTerminals.CloseAll()
	}
	app.mcp.Close()
	app.workflows.Close()
	app.backups.Close()
	if app.workspace != nil {
		app.workspace.Close()
	}
	if app.audit != nil {
		_ = app.audit.Close()
	}
	app.remote.Close(5 * time.Second)
	app.codex.Close()
}

// HideMainWindow keeps the desktop Agent and its schedulers alive in the
// notification area. If the current platform cannot create a tray icon, the
// close button retains the safe fallback of exiting instead of hiding an
// unreachable process.
func (app *DesktopAgentApp) HideMainWindow() {
	app.mu.Lock()
	trayReady := app.applicationTray != nil
	ctx := app.ctx
	app.mu.Unlock()
	if ctx == nil {
		return
	}
	if trayReady {
		runtime.WindowHide(ctx)
		return
	}
	app.mu.Lock()
	app.allowApplicationExit = true
	app.mu.Unlock()
	runtime.Quit(ctx)
}

func (app *DesktopAgentApp) beforeClose(ctx context.Context) bool {
	app.mu.Lock()
	prevent := app.applicationTray != nil && !app.allowApplicationExit
	app.mu.Unlock()
	if prevent {
		runtime.WindowHide(ctx)
	}
	return prevent
}

func (app *DesktopAgentApp) handleRemoteNodeSnapshot(snapshot desktopbridge.RemoteNodeSnapshot) {
	app.mu.Lock()
	newRequests := make([]desktopbridge.RemoteConnectionRequest, 0)
	current := make(map[string]bool, len(snapshot.ConnectionRequests))
	for _, request := range snapshot.ConnectionRequests {
		current[request.RequestID] = true
		if !app.remoteConnectionRequestIDs[request.RequestID] {
			newRequests = append(newRequests, request)
		}
	}
	app.remoteConnectionRequestIDs = current
	app.mu.Unlock()
	app.emit("remote-node:changed", snapshot)
	for _, request := range newRequests {
		if app.ctx == nil {
			continue
		}
		_ = runtime.SendNotificationWithActions(app.ctx, runtime.NotificationOptions{
			ID:         "remote-connect:" + request.RequestID,
			Title:      "设备请求连接 Murong",
			Body:       request.DeviceName + " · " + request.DeviceDisplayID,
			CategoryID: "remote_connection",
		})
	}
}

func (app *DesktopAgentApp) handleNotificationResponse(result runtime.NotificationResult) {
	const prefix = "remote-connect:"
	if !strings.HasPrefix(result.Response.ID, prefix) {
		return
	}
	requestID := strings.TrimPrefix(result.Response.ID, prefix)
	switch result.Response.ActionIdentifier {
	case "approve":
		_, _ = app.remote.ApproveConnectionRequest(requestID)
	case "reject":
		_, _ = app.remote.RejectConnectionRequest(requestID)
	case "block":
		_, _ = app.remote.BlockConnectionRequest(requestID)
	default:
		if app.ctx != nil {
			runtime.WindowShow(app.ctx)
		}
	}
}

func (app *DesktopAgentApp) Bootstrap() BootstrapState {
	summaries := app.store.listSessions()
	var active *ChatSession
	activeProjectAvailable := false
	activeProjectError := ""
	if len(summaries) > 0 {
		active = app.store.getSession(summaries[0].ID)
		projectPath := ""
		if active != nil {
			projectPath = strings.TrimSpace(active.ProjectPath)
		}
		if projectPath == "" {
			projectPath = strings.TrimSpace(app.store.rawConfig().ProjectPath)
		}
		if projectPath != "" {
			if resolved, err := normalizeExistingProjectPath(projectPath); err != nil {
				activeProjectError = err.Error()
				if config, closeErr := app.store.closeProject(); closeErr == nil && app.workspace != nil {
					app.workspace.SetProject(config.ProjectPath)
				}
			} else {
				activeProjectAvailable = true
				if active != nil && strings.TrimSpace(active.ProjectPath) != "" {
					if config, activateErr := app.store.activateProject(resolved); activateErr != nil {
						activeProjectAvailable = false
						activeProjectError = activateErr.Error()
					} else if app.workspace != nil {
						app.workspace.SetProject(config.ProjectPath)
					}
				}
			}
		}
	}
	return BootstrapState{
		Platform:               currentDesktopPlatformInfo(),
		Config:                 app.store.publicConfig(),
		Sessions:               summaries,
		Active:                 active,
		ActiveProjectAvailable: activeProjectAvailable,
		ActiveProjectError:     activeProjectError,
		RemoteNode:             app.remote.Snapshot(),
		Terminals:              append([]TerminalBackend(nil), app.terminals...),
		Knowledge:              app.store.knowledgeLibrary(),
		ProjectKnowledge:       app.store.projectKnowledge(),
		ProjectTools:           app.store.projectToolPreferences(),
		MCP:                    app.mcpState(),
		SavedWorkflows:         app.workflows.State(),
		Backup:                 app.backups.Status(),
		WorkspaceChanges:       app.workspace.State(),
		Codex:                  app.codex.Status(),
	}
}

func (app *DesktopAgentApp) RefreshCodexStatus(request CodexRuntimeRequest) (CodexRuntimeStatus, error) {
	parent := app.ctx
	if parent == nil {
		parent = context.Background()
	}
	return app.codex.Refresh(parent, request.ExecutablePath)
}

func (app *DesktopAgentApp) StartCodexDeviceLogin(request CodexRuntimeRequest) (CodexRuntimeStatus, error) {
	parent := app.ctx
	if parent == nil {
		parent = context.Background()
	}
	status, err := app.codex.StartDeviceLogin(parent, request.ExecutablePath)
	if err != nil {
		return status, err
	}
	if app.ctx != nil && status.Login.VerificationURL != "" {
		runtime.BrowserOpenURL(app.ctx, status.Login.VerificationURL)
	}
	return status, nil
}

func (app *DesktopAgentApp) GetBackupStatus() DesktopBackupStatus {
	return app.backups.Status()
}

func (app *DesktopAgentApp) UpdateBackupSettings(settings DesktopBackupSettings) (DesktopBackupStatus, error) {
	return app.backups.UpdateSettings(settings)
}

func (app *DesktopAgentApp) CreateManualBackup() (DesktopBackupOperationResult, error) {
	if app.ctx == nil {
		return DesktopBackupOperationResult{}, errors.New("窗口尚未就绪")
	}
	path, err := runtime.SaveFileDialog(app.ctx, runtime.SaveDialogOptions{
		Title: "创建 Murong 完整备份", DefaultDirectory: app.backups.root,
		DefaultFilename:      app.backups.SuggestedManualFileName(time.Now()),
		Filters:              []runtime.FileFilter{{DisplayName: "Murong 备份包 (*.zip)", Pattern: "*.zip"}},
		CanCreateDirectories: true,
	})
	if err != nil {
		return DesktopBackupOperationResult{}, err
	}
	return app.backups.CreateManualBackup(path)
}

func (app *DesktopAgentApp) SelectBackupForRestore() (DesktopRestoreSelection, error) {
	if app.ctx == nil {
		return DesktopRestoreSelection{}, errors.New("窗口尚未就绪")
	}
	path, err := runtime.OpenFileDialog(app.ctx, runtime.OpenDialogOptions{
		Title: "选择 Murong 备份包", DefaultDirectory: app.backups.root,
		Filters: []runtime.FileFilter{{DisplayName: "Murong 备份包 (*.zip)", Pattern: "*.zip"}},
	})
	if err != nil || strings.TrimSpace(path) == "" {
		return DesktopRestoreSelection{}, err
	}
	info, err := os.Stat(path)
	if err != nil {
		return DesktopRestoreSelection{}, err
	}
	if !info.Mode().IsRegular() || info.Size() <= 0 || info.Size() > desktopBackupMaxCompressedBytes {
		return DesktopRestoreSelection{}, errors.New("所选备份包不是有效大小的普通文件")
	}
	return DesktopRestoreSelection{Path: path, FileName: filepath.Base(path), SizeBytes: info.Size()}, nil
}

func (app *DesktopAgentApp) RestoreBackup(request RestoreDesktopBackupRequest) (DesktopBackupOperationResult, error) {
	if !request.Confirmed {
		return DesktopBackupOperationResult{}, errors.New("恢复前必须明确确认")
	}
	app.mu.Lock()
	busy := len(app.runs) > 0 || len(app.activeSubagentJobs) > 0 || len(app.approvals) > 0
	app.mu.Unlock()
	if busy || app.workflows.Busy() {
		return DesktopBackupOperationResult{}, errors.New("当前仍有 Agent、审批或保存工作流在运行，请结束后再恢复")
	}
	if app.store.hasActiveSessionHandoff() {
		return DesktopBackupOperationResult{}, errors.New("仍有任务由手机接管；请先归还或强制收回执行权，再恢复备份")
	}
	app.workflows.Close()
	app.mcp.Close()
	result, restoreErr := app.backups.Restore(request.Path)
	ctx := app.ctx
	if ctx == nil {
		ctx = context.Background()
	}
	app.workflows.Start(ctx)
	go func() {
		configs, err := app.store.mcpRuntimeConfigs()
		if err != nil {
			app.emit("mcp:changed", app.mcpStateWithError(err))
			return
		}
		app.mcp.ConnectAll(ctx, configs, true)
	}()
	if restoreErr != nil {
		return result, restoreErr
	}
	if app.workspace != nil {
		app.workspace.SetProject(app.store.rawConfig().ProjectPath)
	}
	app.emit("settings:changed", app.store.publicConfig())
	app.emitSessionsChanged(nil)
	app.emit("knowledge:changed", app.store.knowledgeLibrary())
	app.emit("project-knowledge:changed", app.store.projectKnowledge())
	app.emit("project-tools:changed", app.store.projectToolPreferences())
	app.emit("saved-workflows:changed", app.workflows.State())
	app.emit("backup:changed", app.backups.Status())
	return result, nil
}

func (app *DesktopAgentApp) GetSavedWorkflowState() SavedWorkflowState {
	return app.workflows.State()
}

func (app *DesktopAgentApp) SaveGitHubConfig(request SaveGitHubConfigRequest) (SavedWorkflowState, error) {
	return app.workflows.SaveGitHubConfig(request)
}

func (app *DesktopAgentApp) TestGitHubConnection() (SavedWorkflowState, error) {
	return app.workflows.TestGitHubConnection()
}

func (app *DesktopAgentApp) SaveSavedWorkflow(request SaveSavedWorkflowRequest) (SavedWorkflowState, error) {
	return app.workflows.SaveWorkflow(request)
}

func (app *DesktopAgentApp) DeleteSavedWorkflow(id string) (SavedWorkflowState, error) {
	return app.workflows.DeleteWorkflow(id)
}

func (app *DesktopAgentApp) RunSavedWorkflowNow(request RunSavedWorkflowRequest) (SavedWorkflowState, error) {
	return app.workflows.RunNow(request)
}

func (app *DesktopAgentApp) GetMCPState() MCPState {
	return app.mcpState()
}

func (app *DesktopAgentApp) SaveMCPServers(request SaveMCPServersRequest) (MCPState, error) {
	if _, err := app.store.saveMCPServers(request); err != nil {
		return app.mcpState(), err
	}
	configs, err := app.store.mcpRuntimeConfigs()
	if err != nil {
		return app.mcpStateWithError(err), err
	}
	ctx := app.ctx
	if ctx == nil {
		ctx = context.Background()
	}
	app.mcp.ConnectAll(ctx, configs, true)
	state := app.mcpState()
	app.emit("mcp:changed", state)
	return state, nil
}

func (app *DesktopAgentApp) ConnectMCPServers() (MCPState, error) {
	configs, err := app.store.mcpRuntimeConfigs()
	if err != nil {
		return app.mcpStateWithError(err), err
	}
	ctx := app.ctx
	if ctx == nil {
		ctx = context.Background()
	}
	app.mcp.ConnectAll(ctx, configs, false)
	state := app.mcpState()
	app.emit("mcp:changed", state)
	return state, nil
}

func (app *DesktopAgentApp) mcpState() MCPState {
	return app.mcpStateWithError(nil)
}

func (app *DesktopAgentApp) mcpStateWithError(runtimeError error) MCPState {
	servers, err := app.store.publicMCPServers()
	if runtimeError == nil {
		runtimeError = err
	}
	if app.mcp == nil {
		return MCPState{Servers: servers, Statuses: []MCPServerStatus{}, Tools: []MCPToolInfo{}}
	}
	return app.mcp.State(servers, runtimeError)
}

func (app *DesktopAgentApp) SaveKnowledgeLibrary(library KnowledgeLibrary) (KnowledgeLibrary, error) {
	saved, err := app.store.saveKnowledgeLibrary(library)
	if err == nil {
		app.emit("knowledge:changed", saved)
	}
	return saved, err
}

func (app *DesktopAgentApp) GetProjectKnowledge() ProjectKnowledgeSnapshot {
	return app.store.projectKnowledge()
}

func (app *DesktopAgentApp) GetProjectToolPreferences() ProjectToolPreferencesSnapshot {
	return app.store.projectToolPreferences()
}

func (app *DesktopAgentApp) SaveProjectKnowledgeLibrary(library KnowledgeLibrary) (ProjectKnowledgeSnapshot, error) {
	saved, err := app.store.saveProjectKnowledgeLibrary(library)
	if err == nil {
		app.emit("project-knowledge:changed", saved)
	}
	return saved, err
}

func (app *DesktopAgentApp) SaveSettings(request SaveSettingsRequest) (PublicDesktopConfig, error) {
	previousProject := app.store.publicConfig().ProjectPath
	config, err := app.store.saveSettings(request)
	if err == nil && app.workspace != nil && !sameWorkspacePath(previousProject, config.ProjectPath) {
		app.workspace.SetProject(config.ProjectPath)
	}
	if err == nil && app.ctx != nil {
		runtime.EventsEmit(app.ctx, "settings:changed", config)
		runtime.EventsEmit(app.ctx, "project-knowledge:changed", app.store.projectKnowledge())
		runtime.EventsEmit(app.ctx, "project-tools:changed", app.store.projectToolPreferences())
	}
	return config, err
}

func (app *DesktopAgentApp) SelectProject() (string, error) {
	if app.ctx == nil {
		return "", errors.New("窗口尚未就绪")
	}
	current := app.store.publicConfig().ProjectPath
	return runtime.OpenDirectoryDialog(app.ctx, runtime.OpenDialogOptions{
		Title:                "选择 Murong Desktop Agent 项目目录",
		DefaultDirectory:     current,
		CanCreateDirectories: true,
	})
}

func (app *DesktopAgentApp) CreateSession() (*ChatSession, error) {
	session, err := app.store.createSession("新会话")
	if err == nil {
		app.selectRemoteSession(session.ID)
		app.emitSessionsChanged(session)
	}
	return session, err
}

func (app *DesktopAgentApp) SetSessionPlanMode(id string, enabled bool) (*ChatSession, error) {
	session, err := app.store.setSessionPlanMode(strings.TrimSpace(id), enabled)
	if err == nil {
		app.emitSessionsChanged(session)
	}
	return session, err
}

func (app *DesktopAgentApp) ExecuteWorkflowPlan(id string) error {
	id = strings.TrimSpace(id)
	app.mu.Lock()
	_, running := app.runs[id]
	app.mu.Unlock()
	if running {
		return errors.New("当前任务仍在运行")
	}
	before := app.store.getSession(id)
	if before == nil {
		return errors.New("会话不存在")
	}
	started, previousPlan, err := app.store.startWorkflowPlan(id)
	if err != nil {
		return err
	}
	app.emitSessionsChanged(started)
	content := "按已确认计划执行：" + started.WorkflowPlan.Goal
	if err := app.SendMessage(SendMessageRequest{SessionID: id, Content: truncateRunes(content, 20_000)}); err != nil {
		app.store.restoreWorkflowPlan(id, previousPlan, before.PlanModeEnabled)
		app.emitSessionsChanged(app.store.getSession(id))
		return err
	}
	return nil
}

func (app *DesktopAgentApp) DismissWorkflowPlan(id string) (*ChatSession, error) {
	id = strings.TrimSpace(id)
	app.mu.Lock()
	_, running := app.runs[id]
	app.mu.Unlock()
	if running {
		return nil, errors.New("当前任务仍在运行，停止后才能清除计划")
	}
	session, err := app.store.dismissWorkflowPlan(id)
	if err == nil {
		app.emitSessionsChanged(session)
	}
	return session, err
}

func (app *DesktopAgentApp) ClearSessionGoal(id string) (*ChatSession, error) {
	session, err := app.store.clearSessionGoal(strings.TrimSpace(id))
	if err == nil {
		app.emitSessionsChanged(session)
	}
	return session, err
}

func (app *DesktopAgentApp) ActivateProviderProfile(id string) (PublicDesktopConfig, error) {
	config, err := app.store.activateProviderProfile(id)
	if err == nil {
		app.emit("settings:changed", config)
	}
	return config, err
}

func (app *DesktopAgentApp) SetProviderReasoningEffort(request SetProviderReasoningEffortRequest) (PublicDesktopConfig, error) {
	config, err := app.store.setProviderReasoningEffort(request.ProviderProfileID, request.ReasoningEffort)
	if err == nil {
		app.emit("settings:changed", config)
	}
	return config, err
}

func (app *DesktopAgentApp) GetSession(id string) (*ChatSession, error) {
	session := app.store.getSession(strings.TrimSpace(id))
	if session == nil {
		return nil, errors.New("会话不存在")
	}
	app.selectRemoteSession(session.ID)
	return session, nil
}

func (app *DesktopAgentApp) selectRemoteSession(sessionID string) {
	app.mu.Lock()
	app.remoteSelectedSessionID = strings.TrimSpace(sessionID)
	app.mu.Unlock()
}

func (app *DesktopAgentApp) DeleteSession(id string) error {
	app.CancelRun(id)
	app.cancelSessionSubagentJobs(id)
	if err := app.store.deleteSession(strings.TrimSpace(id)); err != nil {
		return err
	}
	app.emitSessionsChanged(nil)
	return nil
}

func (app *DesktopAgentApp) ReclaimSessionExecution(id string) (*ChatSession, error) {
	session, err := app.store.forceReclaimSessionHandoff(strings.TrimSpace(id))
	if err == nil {
		app.emitSessionsChanged(session)
	}
	return session, err
}

func (app *DesktopAgentApp) ResolveApproval(decision ApprovalDecision) bool {
	app.mu.Lock()
	channel := app.approvals[decision.ID]
	if channel != nil {
		delete(app.approvals, decision.ID)
		delete(app.pendingApprovals, decision.ID)
	}
	app.mu.Unlock()
	if channel == nil {
		return false
	}
	select {
	case channel <- decision.Approve:
	default:
	}
	return true
}

func (app *DesktopAgentApp) CancelRun(sessionID string) bool {
	app.mu.Lock()
	cancel := app.runs[sessionID]
	if cancel != nil {
		delete(app.runs, sessionID)
	}
	app.mu.Unlock()
	if cancel != nil {
		cancel()
		return true
	}
	return false
}

func (app *DesktopAgentApp) SaveRemoteNodeConfig(config desktopbridge.RemoteNodeConfig) (desktopbridge.RemoteNodeSnapshot, error) {
	return app.remote.SaveConfig(config)
}

func (app *DesktopAgentApp) StartRemoteNode(request StartRemoteNodeRequest) (desktopbridge.RemoteNodeSnapshot, error) {
	github, err := app.workflows.store.runtimeGitHub()
	if err != nil {
		return app.remote.Snapshot(), err
	}
	app.remote.SetGitHubToken(github.Token)
	return app.remote.StartWithGitHubToken(request.Config, request.PairCode, github.Token)
}

func (app *DesktopAgentApp) StopRemoteNode() desktopbridge.RemoteNodeSnapshot {
	return app.remote.Stop()
}

func (app *DesktopAgentApp) ClearRemoteNodePairing() (desktopbridge.RemoteNodeSnapshot, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
	defer cancel()
	return app.remote.RevokePairing(ctx)
}

func (app *DesktopAgentApp) RotateRemoteTemporaryCode() (desktopbridge.RemoteNodeSnapshot, error) {
	return app.remote.RotateTemporaryCode()
}

func (app *DesktopAgentApp) SetRemoteSecurityPassword(password string) (desktopbridge.RemoteNodeSnapshot, error) {
	return app.remote.SetSecurityPassword(password)
}

func (app *DesktopAgentApp) ClearRemoteSecurityPassword() (desktopbridge.RemoteNodeSnapshot, error) {
	return app.remote.ClearSecurityPassword()
}

func (app *DesktopAgentApp) ApproveRemoteConnectionRequest(requestID string) (desktopbridge.RemoteNodeSnapshot, error) {
	return app.remote.ApproveConnectionRequest(requestID)
}

func (app *DesktopAgentApp) RejectRemoteConnectionRequest(requestID string) (desktopbridge.RemoteNodeSnapshot, error) {
	return app.remote.RejectConnectionRequest(requestID)
}

func (app *DesktopAgentApp) BlockRemoteConnectionRequest(requestID string) (desktopbridge.RemoteNodeSnapshot, error) {
	return app.remote.BlockConnectionRequest(requestID)
}

func (app *DesktopAgentApp) SetRemoteDoNotDisturb(enabled bool) (desktopbridge.RemoteNodeSnapshot, error) {
	return app.remote.SetDoNotDisturb(enabled)
}

func (app *DesktopAgentApp) UnblockRemotePeer(deviceID string) (desktopbridge.RemoteNodeSnapshot, error) {
	return app.remote.UnblockPeer(deviceID)
}

func (app *DesktopAgentApp) DiscoverRemoteDevices() ([]desktopbridge.RemoteDiscoveredDevice, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	return app.remote.DiscoverDevices(ctx)
}

func (app *DesktopAgentApp) DiscoverRemoteADBDevices() ([]desktopbridge.RemoteADBDevice, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()
	return app.remote.DiscoverADBDevices(ctx)
}

func (app *DesktopAgentApp) SelectRemoteWorkspace(current string) (string, error) {
	if app.ctx == nil {
		return "", errors.New("窗口尚未就绪")
	}
	return runtime.OpenDirectoryDialog(app.ctx, runtime.OpenDialogOptions{
		Title: "选择手机远程节点可访问的电脑目录", DefaultDirectory: strings.TrimSpace(current), CanCreateDirectories: true,
	})
}

func (app *DesktopAgentApp) emitSessionsChanged(active *ChatSession) {
	if app.ctx == nil {
		return
	}
	runtime.EventsEmit(app.ctx, "sessions:changed", map[string]any{
		"sessions":      app.store.listSessions(),
		"activeSession": active,
	})
}
