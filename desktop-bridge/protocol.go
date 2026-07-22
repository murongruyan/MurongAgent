package desktopbridge

import (
	"bufio"
	"bytes"
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/url"
	"runtime"
	"strings"
	"sync"
	"time"
)

const (
	pairPath                   = "/api/v1/pair"
	eventsPath                 = "/api/v1/events"
	workspaceRegisterPath      = "/api/v1/workspace/register"
	workspaceHeartbeatPath     = "/api/v1/workspace/heartbeat"
	workspaceResultPath        = "/api/v1/workspace/result"
	workspaceChangesPath       = "/api/v1/workspace/changes"
	workspaceDisconnectPath    = "/api/v1/workspace/disconnect"
	desktopAgentRegisterPath   = "/api/v1/desktop-agent/register"
	desktopAgentSnapshotPath   = "/api/v1/desktop-agent/snapshot"
	desktopAgentResultPath     = "/api/v1/desktop-agent/result"
	desktopAgentDisconnectPath = "/api/v1/desktop-agent/disconnect"
	unpairPath                 = "/api/v1/unpair"
	sessionCookieName          = "murong_lan_session"
	maxSSELineBytes            = 8 * 1024 * 1024
)

type apiClient struct {
	base         *url.URL
	token        string
	httpClient   *http.Client
	close        func()
	deviceTunnel bool
}

type apiError struct {
	Status  int
	Code    string
	Message string
}

type securePairingEnvelope struct {
	Version    string `json:"version"`
	Salt       string `json:"salt"`
	Nonce      string `json:"nonce"`
	Ciphertext string `json:"ciphertext"`
}

type pairResponse struct {
	ClientID      string                 `json:"clientId"`
	ClientName    string                 `json:"clientName"`
	CreatedAt     int64                  `json:"createdAt"`
	SecureChannel *securePairingEnvelope `json:"secureChannel,omitempty"`
}

func (err *apiError) Error() string {
	if err.Code != "" {
		return fmt.Sprintf("HTTP %d [%s]：%s", err.Status, err.Code, err.Message)
	}
	return fmt.Sprintf("HTTP %d：%s", err.Status, err.Message)
}

type workspaceRegisterRequest struct {
	WorkspaceSessionID string            `json:"workspaceSessionId"`
	Label              string            `json:"label"`
	Platform           string            `json:"platform,omitempty"`
	Architecture       string            `json:"architecture,omitempty"`
	Readable           bool              `json:"readable"`
	Writable           bool              `json:"writable"`
	Terminal           bool              `json:"terminal"`
	Terminals          []terminalBackend `json:"terminals,omitempty"`
	RequestID          string            `json:"requestId"`
}

type workspaceStatus struct {
	Connected               bool   `json:"connected"`
	WorkspaceSessionID      string `json:"workspaceSessionId"`
	HeartbeatIntervalMillis int64  `json:"heartbeatIntervalMillis"`
}

type workspaceRPCRequest struct {
	WorkspaceSessionID string  `json:"workspaceSessionId"`
	RequestID          string  `json:"requestId"`
	Operation          string  `json:"operation"`
	Path               string  `json:"path"`
	Content            *string `json:"content"`
	ExpectedSHA256     *string `json:"expectedSha256"`
	Command            *string `json:"command"`
	TerminalID         string  `json:"terminalId,omitempty"`
	TimeoutMillis      *int64  `json:"timeoutMillis"`
	MaxBytes           int     `json:"maxBytes"`
	MaxEntries         int     `json:"maxEntries"`
	ExpiresAt          int64   `json:"expiresAt"`
}

type workspaceEntry struct {
	Name         string `json:"name"`
	Path         string `json:"path"`
	Directory    bool   `json:"directory"`
	Size         *int64 `json:"size,omitempty"`
	LastModified *int64 `json:"lastModified,omitempty"`
}

type workspaceRPCResult struct {
	WorkspaceSessionID string           `json:"workspaceSessionId"`
	RequestID          string           `json:"requestId"`
	Success            bool             `json:"success"`
	ErrorCode          string           `json:"errorCode,omitempty"`
	ErrorMessage       string           `json:"errorMessage,omitempty"`
	Entries            []workspaceEntry `json:"entries,omitempty"`
	Content            *string          `json:"content,omitempty"`
	SHA256             string           `json:"sha256,omitempty"`
	Size               *int64           `json:"size,omitempty"`
	LastModified       *int64           `json:"lastModified,omitempty"`
	Directory          *bool            `json:"directory,omitempty"`
	Created            bool             `json:"created,omitempty"`
	DiffPreview        string           `json:"diffPreview,omitempty"`
	Stdout             string           `json:"stdout,omitempty"`
	Stderr             string           `json:"stderr,omitempty"`
	ExitCode           *int             `json:"exitCode,omitempty"`
	TimedOut           bool             `json:"timedOut,omitempty"`
}

type observedChange struct {
	Path      string `json:"path"`
	Kind      string `json:"kind"`
	Directory bool   `json:"directory"`
}

type changeReport struct {
	WorkspaceSessionID string           `json:"workspaceSessionId"`
	ReportID           string           `json:"reportId"`
	Changes            []observedChange `json:"changes"`
	PartialScan        bool             `json:"partialScan"`
}

type nodeConnectionPhase string

const (
	nodePhaseStopped      nodeConnectionPhase = "stopped"
	nodePhaseConnecting   nodeConnectionPhase = "connecting"
	nodePhaseConnected    nodeConnectionPhase = "connected"
	nodePhaseReconnecting nodeConnectionPhase = "reconnecting"
	nodePhaseError        nodeConnectionPhase = "error"
)

type nodeRuntimeStatus struct {
	Phase   nodeConnectionPhase
	Message string
}

type computerNode struct {
	api                   *apiClient
	workspace             *workspace
	config                nodeConfig
	terminals             map[string]terminalBackend
	sessionID             string
	sem                   chan struct{}
	processedMu           sync.Mutex
	processed             map[string]struct{}
	processedQ            []string
	requests              sync.WaitGroup
	onStatus              func(nodeRuntimeStatus)
	agentSnapshotProvider func() DesktopAgentSnapshot
	agentCommandHandler   func(DesktopAgentCommand) DesktopAgentCommandResult
	agentSequence         int64
	agentMu               sync.Mutex
	agentSnapshotHash     [sha256.Size]byte
	agentSnapshotHashSet  bool
	agentProtocolVersion  int
	agentLastPublishedAt  time.Time
}

func newAPIClient(base *url.URL) *apiClient {
	return &apiClient{
		base: base,
		httpClient: &http.Client{
			Transport: &http.Transport{
				Proxy:                 http.ProxyFromEnvironment,
				MaxIdleConns:          8,
				IdleConnTimeout:       60 * time.Second,
				ResponseHeaderTimeout: 20 * time.Second,
			},
		},
	}
}

func (api *apiClient) Close() {
	if api == nil {
		return
	}
	if api.close != nil {
		api.close()
		return
	}
	api.httpClient.CloseIdleConnections()
}

func newComputerNode(api *apiClient, workspace *workspace, config nodeConfig, terminals map[string]terminalBackend) (*computerNode, error) {
	sessionID, err := randomID("node")
	if err != nil {
		return nil, err
	}
	return &computerNode{
		api:       api,
		workspace: workspace,
		config:    config,
		terminals: terminals,
		sessionID: sessionID,
		sem:       make(chan struct{}, 4),
		processed: make(map[string]struct{}),
	}, nil
}

func (api *apiClient) pair(ctx context.Context, code, clientName string) (string, []byte, error) {
	pairingCode := strings.TrimSpace(code)
	proof, err := pairingCodeProof(pairingCode)
	if err != nil {
		return "", nil, err
	}
	body, err := json.Marshal(map[string]string{
		"codeProof": proof, "clientName": clientName, "secureChannelVersion": securePairingVersion,
	})
	if err != nil {
		return "", nil, err
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodPost, api.endpoint(pairPath), bytes.NewReader(body))
	if err != nil {
		return "", nil, err
	}
	request.Header.Set("Content-Type", "application/json")
	response, err := api.httpClient.Do(request)
	if err != nil {
		return "", nil, api.translateRequestError(err)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		decoded := decodeAPIError(response)
		return "", nil, decoded
	}
	var payload pairResponse
	if err := json.NewDecoder(io.LimitReader(response.Body, 64*1024)).Decode(&payload); err != nil {
		return "", nil, fmt.Errorf("配对响应无法解析：%w", err)
	}
	if payload.SecureChannel != nil {
		token, syncKey, err := decryptPairingBootstrap(pairingCode, payload)
		if err != nil {
			return "", nil, fmt.Errorf("安全配对响应无法解密：%w", err)
		}
		return token, syncKey, nil
	}
	// Compatibility with an older phone build. It can still expose workspace
	// and desktop tasks, but no device-sync key exists until the user re-pairs.
	for _, cookie := range response.Cookies() {
		if cookie.Name == sessionCookieName && cookie.Value != "" {
			return cookie.Value, nil, nil
		}
	}
	return "", nil, errors.New("配对响应没有返回访问凭据")
}

func (api *apiClient) postJSON(ctx context.Context, path string, input, output any) error {
	return api.postJSONWithTimeout(ctx, path, input, output, 20*time.Second)
}

func (api *apiClient) postJSONWithTimeout(ctx context.Context, path string, input, output any, timeout time.Duration) error {
	return api.postJSONWithTimeoutAndLimit(ctx, path, input, output, timeout, 2*1024*1024)
}

func (api *apiClient) postJSONWithTimeoutAndLimit(
	ctx context.Context,
	path string,
	input,
	output any,
	timeout time.Duration,
	maxResponseBytes int64,
) error {
	if maxResponseBytes <= 0 {
		return errors.New("HTTP JSON 响应大小上限无效")
	}
	body, err := json.Marshal(input)
	if err != nil {
		return err
	}
	requestCtx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	request, err := http.NewRequestWithContext(requestCtx, http.MethodPost, api.endpoint(path), bytes.NewReader(body))
	if err != nil {
		return err
	}
	request.Header.Set("Authorization", "Bearer "+api.token)
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
	if output == nil {
		_, _ = io.Copy(io.Discard, response.Body)
		return nil
	}
	responseBody, err := io.ReadAll(io.LimitReader(response.Body, maxResponseBytes+1))
	if err != nil {
		return err
	}
	if int64(len(responseBody)) > maxResponseBytes {
		return fmt.Errorf("HTTP JSON 响应超过 %d 字节上限", maxResponseBytes)
	}
	if err := json.Unmarshal(responseBody, output); err != nil {
		return err
	}
	return nil
}

func (api *apiClient) endpoint(path string) string {
	copy := *api.base
	copy.Path = path
	copy.RawQuery = ""
	copy.Fragment = ""
	return copy.String()
}

func (api *apiClient) translateRequestError(err error) error {
	if err == nil || !api.deviceTunnel {
		return err
	}
	var requestError *url.Error
	if errors.As(err, &requestError) && requestError.Err != nil {
		err = requestError.Err
	}
	if errors.Is(err, errDeviceTunnelPhoneResponseTimeout) {
		return errors.New("公网加密隧道已建立，但手机端未在 30 秒内响应。请确认手机“电脑节点”服务已启动且公网本机 ID 在线，然后重试")
	}
	return err
}

func decodeAPIError(response *http.Response) error {
	body, _ := io.ReadAll(io.LimitReader(response.Body, 64*1024))
	payload := struct {
		Code  string `json:"code"`
		Error string `json:"error"`
	}{}
	_ = json.Unmarshal(body, &payload)
	if payload.Error == "" {
		payload.Error = strings.TrimSpace(string(body))
	}
	if payload.Error == "" {
		payload.Error = response.Status
	}
	return &apiError{Status: response.StatusCode, Code: payload.Code, Message: payload.Error}
}

func (node *computerNode) run(ctx context.Context) error {
	backoff := time.Second
	for {
		if err := ctx.Err(); err != nil {
			node.notifyStatus(nodePhaseStopped, "节点已停止")
			node.disconnect()
			node.requests.Wait()
			return err
		}
		node.notifyStatus(nodePhaseConnecting, "正在连接手机 Murong…")
		status, err := node.register(ctx)
		if err != nil {
			if isUnauthorized(err) {
				node.notifyStatus(nodePhaseError, "配对凭据已失效，请清除本机配对后重新连接")
				return fmt.Errorf("手机已撤销或拒绝节点凭据；请重新生成配对码并使用 --pair-code 启动：%w", err)
			}
			node.notifyStatus(nodePhaseReconnecting, fmt.Sprintf("连接失败，%s 后重试", backoff))
			log.Printf("连接手机失败，%s 后重试：%v", backoff, err)
			if err := waitContext(ctx, backoff); err != nil {
				continue
			}
			backoff = minDuration(backoff*2, 20*time.Second)
			continue
		}
		backoff = time.Second
		log.Printf("电脑节点已连接手机，工作区会话：%s", node.sessionID)
		node.notifyStatus(nodePhaseConnected, "已连接，等待手机 Agent 调用")
		desktopAgentEnabled := node.registerDesktopAgent(ctx)
		streamCtx, cancel := context.WithCancel(ctx)
		errorsChannel := make(chan error, 4)
		go func() {
			errorsChannel <- node.api.consumeEvents(streamCtx, node.acceptRequest, node.acceptDesktopAgentCommand)
		}()
		go func() { errorsChannel <- node.heartbeatLoop(streamCtx, status.HeartbeatIntervalMillis) }()
		go func() { errorsChannel <- node.changeLoop(streamCtx) }()
		if desktopAgentEnabled {
			go func() { errorsChannel <- node.desktopAgentLoop(streamCtx) }()
		}
		runErr := <-errorsChannel
		cancel()
		if ctx.Err() != nil {
			node.notifyStatus(nodePhaseStopped, "节点已停止")
			node.disconnect()
			node.requests.Wait()
			return ctx.Err()
		}
		log.Printf("与手机的连接中断：%v", runErr)
		node.notifyStatus(nodePhaseReconnecting, "连接中断，正在自动重连…")
		_ = waitContext(ctx, backoff)
		backoff = minDuration(backoff*2, 20*time.Second)
	}
}

func (node *computerNode) notifyStatus(phase nodeConnectionPhase, message string) {
	if node.onStatus != nil {
		node.onStatus(nodeRuntimeStatus{Phase: phase, Message: message})
	}
}

func (node *computerNode) register(ctx context.Context) (workspaceStatus, error) {
	var status workspaceStatus
	request := workspaceRegisterRequest{
		WorkspaceSessionID: node.sessionID,
		Label:              node.config.Label,
		Platform:           runtime.GOOS,
		Architecture:       runtime.GOARCH,
		Readable:           true,
		Writable:           node.config.AllowWrite,
		Terminal:           node.config.AllowTerminal,
		Terminals:          node.terminalDescriptors(),
		RequestID:          mustRandomID("register"),
	}
	err := node.api.postJSON(ctx, workspaceRegisterPath, request, &status)
	if err != nil {
		request.Platform = ""
		request.Architecture = ""
		if legacyErr := node.api.postJSON(ctx, workspaceRegisterPath, request, &status); legacyErr == nil {
			log.Printf("手机端使用旧版工作区协议，已省略电脑系统与架构字段")
			return status, nil
		}
	}
	return status, err
}

func (node *computerNode) heartbeatLoop(ctx context.Context, intervalMillis int64) error {
	interval := time.Duration(intervalMillis) * time.Millisecond
	if interval < 5*time.Second || interval > 30*time.Second {
		interval = 10 * time.Second
	}
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-ticker.C:
			if err := node.api.postJSON(ctx, workspaceHeartbeatPath, map[string]string{
				"workspaceSessionId": node.sessionID,
			}, nil); err != nil {
				return err
			}
		}
	}
}

func (node *computerNode) changeLoop(ctx context.Context) error {
	if _, _, err := node.workspace.scanChanges(); err != nil {
		return err
	}
	ticker := time.NewTicker(8 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-ticker.C:
			changes, partial, err := node.workspace.scanChanges()
			if err != nil {
				return err
			}
			if len(changes) == 0 && !partial {
				continue
			}
			if len(changes) > 200 {
				changes = changes[:200]
				partial = true
			}
			if err := node.api.postJSON(ctx, workspaceChangesPath, changeReport{
				WorkspaceSessionID: node.sessionID,
				ReportID:           mustRandomID("changes"),
				Changes:            changes,
				PartialScan:        partial,
			}, nil); err != nil {
				return err
			}
		}
	}
}

func (api *apiClient) consumeEvents(
	ctx context.Context,
	onRequest func(workspaceRPCRequest),
	onDesktopAgentCommand func(DesktopAgentCommand),
) error {
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, api.endpoint(eventsPath), nil)
	if err != nil {
		return err
	}
	request.Header.Set("Authorization", "Bearer "+api.token)
	request.Header.Set("Accept", "text/event-stream")
	response, err := api.httpClient.Do(request)
	if err != nil {
		return api.translateRequestError(err)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return decodeAPIError(response)
	}

	scanner := bufio.NewScanner(response.Body)
	scanner.Buffer(make([]byte, 64*1024), maxSSELineBytes)
	eventType := ""
	dataLines := make([]string, 0, 1)
	dispatch := func() {
		if eventType == "workspace_request" && len(dataLines) > 0 {
			var rpc workspaceRPCRequest
			if err := json.Unmarshal([]byte(strings.Join(dataLines, "\n")), &rpc); err != nil {
				log.Printf("忽略损坏的工作区事件：%v", err)
			} else {
				onRequest(rpc)
			}
		} else if eventType == "desktop_agent_command" && len(dataLines) > 0 {
			var command DesktopAgentCommand
			if err := json.Unmarshal([]byte(strings.Join(dataLines, "\n")), &command); err != nil {
				log.Printf("忽略损坏的桌面任务命令：%v", err)
			} else if onDesktopAgentCommand != nil {
				onDesktopAgentCommand(command)
			}
		}
		eventType = ""
		dataLines = dataLines[:0]
	}
	for scanner.Scan() {
		line := scanner.Text()
		if line == "" {
			dispatch()
			continue
		}
		if strings.HasPrefix(line, ":") {
			continue
		}
		if strings.HasPrefix(line, "event:") {
			eventType = strings.TrimSpace(strings.TrimPrefix(line, "event:"))
		} else if strings.HasPrefix(line, "data:") {
			dataLines = append(dataLines, strings.TrimPrefix(strings.TrimPrefix(line, "data:"), " "))
		}
	}
	if err := scanner.Err(); err != nil {
		return err
	}
	return io.EOF
}

func (node *computerNode) acceptRequest(request workspaceRPCRequest) {
	if !node.markProcessed(request.RequestID) {
		return
	}
	select {
	case node.sem <- struct{}{}:
		node.requests.Add(1)
		go func() {
			defer func() {
				<-node.sem
				node.requests.Done()
			}()
			node.executeAndReturn(request)
		}()
	default:
		go node.returnResult(workspaceRPCResult{
			WorkspaceSessionID: request.WorkspaceSessionID,
			RequestID:          request.RequestID,
			Success:            false,
			ErrorCode:          "node_busy",
			ErrorMessage:       "Desktop Node 正在处理过多请求",
		})
	}
}

func (node *computerNode) executeAndReturn(request workspaceRPCRequest) {
	log.Printf("收到手机工具请求：%s %s", strings.ToLower(request.Operation), request.Path)
	result := workspaceRPCResult{
		WorkspaceSessionID: request.WorkspaceSessionID,
		RequestID:          request.RequestID,
	}
	if request.WorkspaceSessionID != node.sessionID {
		result.ErrorCode = "workspace_session_stale"
		result.ErrorMessage = "工作区会话已失效"
		node.returnResult(result)
		return
	}
	if request.ExpiresAt <= time.Now().UnixMilli() {
		result.ErrorCode = "request_expired"
		result.ErrorMessage = "电脑工具请求已经过期"
		node.returnResult(result)
		return
	}

	var opResult workspaceRPCResult
	var err error
	switch strings.ToLower(request.Operation) {
	case "list":
		opResult, err = node.workspace.list(request)
	case "read":
		opResult, err = node.workspace.read(request)
	case "stat":
		opResult, err = node.workspace.stat(request)
	case "write":
		if !node.config.AllowWrite {
			err = fail("write_permission_missing", "Desktop Node 未启用写入能力")
		} else {
			opResult, err = node.workspace.write(request)
		}
	case "mkdir":
		if !node.config.AllowWrite {
			err = fail("write_permission_missing", "Desktop Node 未启用写入能力")
		} else {
			opResult, err = node.workspace.mkdir(request)
		}
	case "run":
		if !node.config.AllowTerminal {
			err = fail("terminal_permission_missing", "Desktop Node 未启用电脑终端能力")
		} else {
			terminalID := strings.TrimSpace(request.TerminalID)
			if terminalID == "" && len(node.config.TerminalBackends) > 0 {
				terminalID = node.config.TerminalBackends[0]
			}
			backend, ok := node.terminals[terminalID]
			if !ok {
				err = fail("terminal_unavailable", "请求的电脑终端未启用或已经不可用")
			} else {
				opResult, err = node.workspace.run(request, backend)
			}
		}
	default:
		err = fail("unsupported_operation", "Desktop Node 不支持这个操作")
	}
	if err != nil {
		code, message := failureDetails(err)
		result.ErrorCode = code
		result.ErrorMessage = message
	} else {
		result = opResult
		result.WorkspaceSessionID = request.WorkspaceSessionID
		result.RequestID = request.RequestID
		result.Success = true
	}
	if result.Success {
		log.Printf("电脑工具请求已完成：%s %s", strings.ToLower(request.Operation), request.Path)
	} else {
		log.Printf("电脑工具请求失败：%s %s [%s]", strings.ToLower(request.Operation), request.Path, result.ErrorCode)
	}
	node.returnResult(result)
}

func (node *computerNode) terminalDescriptors() []terminalBackend {
	result := make([]terminalBackend, 0, len(node.config.TerminalBackends))
	for _, id := range node.config.TerminalBackends {
		if backend, ok := node.terminals[id]; ok {
			result = append(result, backend)
		}
	}
	return result
}

func (node *computerNode) returnResult(result workspaceRPCResult) {
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	if err := node.api.postJSON(ctx, workspaceResultPath, result, nil); err != nil {
		log.Printf("无法提交请求 %s 的结果：%v", result.RequestID, err)
	}
}

func (node *computerNode) disconnect() {
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	_ = node.api.postJSON(ctx, workspaceDisconnectPath, map[string]string{
		"workspaceSessionId": node.sessionID,
	}, nil)
	if node.config.ShareDesktopTasks {
		_ = node.api.postJSON(ctx, desktopAgentDisconnectPath, map[string]string{
			"nodeSessionId": node.sessionID,
		}, nil)
	}
}

func (node *computerNode) registerDesktopAgent(ctx context.Context) bool {
	if !node.config.ShareDesktopTasks || node.agentSnapshotProvider == nil {
		return false
	}
	request := desktopAgentRegisterRequest{
		NodeSessionID:      node.sessionID,
		SourcePlatform:     runtime.GOOS,
		SourceArchitecture: runtime.GOARCH,
		ControlAllowed:     node.config.AllowAgentControl,
	}
	negotiatedVersion := 0
	var newestProtocolError error
	for version := DesktopAgentProtocolVersion; version >= 2; version-- {
		request.ProtocolVersion = version
		request.RequestID = mustRandomID("desktop-register")
		if err := node.api.postJSON(ctx, desktopAgentRegisterPath, request, nil); err == nil {
			negotiatedVersion = version
			break
		} else if newestProtocolError == nil {
			newestProtocolError = err
		}
	}
	if negotiatedVersion == 0 {
		request.ProtocolVersion = 0
		request.SourcePlatform = ""
		request.SourceArchitecture = ""
		request.RequestID = mustRandomID("desktop-register")
		if legacyErr := node.api.postJSON(ctx, desktopAgentRegisterPath, request, nil); legacyErr != nil {
			log.Printf("手机端暂未启用桌面任务同步，文件与终端节点继续运行：%v", newestProtocolError)
			return false
		}
		negotiatedVersion = 1
		log.Printf("手机端使用旧版桌面任务协议，已省略电脑系统与架构字段")
	} else if negotiatedVersion < DesktopAgentProtocolVersion {
		log.Printf("手机端使用桌面任务协议 v%d，已省略较新字段", negotiatedVersion)
	}
	node.agentMu.Lock()
	node.agentProtocolVersion = negotiatedVersion
	node.agentMu.Unlock()
	if err := node.publishDesktopAgentSnapshot(ctx, true); err != nil {
		log.Printf("桌面任务初始快照提交失败：%v", err)
		return false
	}
	log.Printf("桌面任务同步已启用，手机控制=%s", onOff(node.config.AllowAgentControl))
	return true
}

func (node *computerNode) desktopAgentLoop(ctx context.Context) error {
	ticker := time.NewTicker(3 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-ticker.C:
			node.agentMu.Lock()
			forceHeartbeat := time.Since(node.agentLastPublishedAt) >= 10*time.Second
			node.agentMu.Unlock()
			if err := node.publishDesktopAgentSnapshot(ctx, forceHeartbeat); err != nil {
				return err
			}
		}
	}
}

func (node *computerNode) publishDesktopAgentSnapshot(ctx context.Context, force bool) error {
	node.agentMu.Lock()
	defer node.agentMu.Unlock()
	snapshot, err := node.readDesktopAgentSnapshot()
	if err != nil {
		return err
	}
	prepareDesktopAgentSnapshotProtocol(&snapshot, node.agentProtocolVersion)
	comparison := snapshot
	comparison.NodeSessionID = ""
	comparison.Sequence = 0
	comparison.GeneratedAt = 0
	encoded, err := json.Marshal(comparison)
	if err != nil {
		return err
	}
	hash := sha256.Sum256(encoded)
	if !force && node.agentSnapshotHashSet && hash == node.agentSnapshotHash {
		return nil
	}
	node.agentSequence++
	snapshot.NodeSessionID = node.sessionID
	snapshot.Sequence = node.agentSequence
	snapshot.GeneratedAt = time.Now().UnixMilli()
	if err := node.api.postJSON(ctx, desktopAgentSnapshotPath, snapshot, nil); err != nil {
		return err
	}
	node.agentSnapshotHash = hash
	node.agentSnapshotHashSet = true
	node.agentLastPublishedAt = time.Now()
	return nil
}

func (node *computerNode) acceptDesktopAgentCommand(command DesktopAgentCommand) {
	if !node.markProcessed(command.RequestID) {
		return
	}
	go node.executeDesktopAgentCommand(command)
}

func (node *computerNode) executeDesktopAgentCommand(command DesktopAgentCommand) {
	result := DesktopAgentCommandResult{
		NodeSessionID: command.NodeSessionID,
		RequestID:     command.RequestID,
	}
	failResult := func(code, message string) {
		result.ErrorCode = code
		result.ErrorMessage = message
	}
	operation := strings.ToLower(strings.TrimSpace(command.Operation))
	var handoffKey []byte
	if isDesktopHandoffOperation(operation) {
		var err error
		handoffKey, err = node.prepareDesktopHandoffCommand(&command)
		if err != nil {
			failResult("desktop_handoff_security_error", err.Error())
		}
		defer clearBytes(handoffKey)
	}
	if result.ErrorCode != "" {
		// Security validation failed before the command reached the desktop Agent.
	} else if command.NodeSessionID != node.sessionID {
		failResult("desktop_agent_session_stale", "桌面 Agent 会话已失效")
	} else if command.ExpiresAt <= time.Now().UnixMilli() {
		failResult("request_expired", "桌面任务命令已经过期")
	} else if !node.config.ShareDesktopTasks || node.agentCommandHandler == nil {
		failResult("desktop_agent_unavailable", "电脑端未启用桌面任务共享")
	} else if (operation == "send_message" || operation == "cancel" || operation == "approval" || operation == "ask" ||
		operation == "begin_handoff" || operation == "return_handoff" || operation == "abort_handoff") && !node.config.AllowAgentControl {
		failResult("desktop_agent_control_disabled", "电脑端未允许手机控制桌面任务")
	} else {
		result = node.invokeDesktopAgentCommand(command)
		result.NodeSessionID = node.sessionID
		result.RequestID = command.RequestID
		if result.Snapshot != nil {
			node.prepareDesktopAgentSnapshot(result.Snapshot)
		}
		if result.Session != nil {
			node.agentMu.Lock()
			prepareDesktopAgentTaskDetailProtocol(result.Session, node.agentProtocolVersion)
			node.agentMu.Unlock()
		}
		if err := protectDesktopHandoffResult(command, &result, handoffKey); err != nil {
			if operation == "begin_handoff" && result.Success && result.HandoffToken != "" {
				_ = node.invokeDesktopAgentCommand(DesktopAgentCommand{
					NodeSessionID: node.sessionID,
					Operation:     "abort_handoff",
					SessionID:     command.SessionID,
					HandoffToken:  result.HandoffToken,
				})
			}
			result = DesktopAgentCommandResult{NodeSessionID: node.sessionID, RequestID: command.RequestID}
			failResult("desktop_handoff_security_error", "无法加密跨端接管内容："+err.Error())
		}
	}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	if err := node.api.postJSON(ctx, desktopAgentResultPath, result, nil); err != nil {
		log.Printf("无法提交桌面任务命令 %s 的结果：%v", command.RequestID, err)
	}
}

func (node *computerNode) readDesktopAgentSnapshot() (snapshot DesktopAgentSnapshot, err error) {
	defer func() {
		if recovered := recover(); recovered != nil {
			err = fmt.Errorf("桌面任务快照生成失败：%v", recovered)
		}
	}()
	return node.agentSnapshotProvider(), nil
}

func (node *computerNode) invokeDesktopAgentCommand(command DesktopAgentCommand) (result DesktopAgentCommandResult) {
	defer func() {
		if recovered := recover(); recovered != nil {
			result = DesktopAgentCommandResult{
				Success: false, ErrorCode: "desktop_agent_internal_error", ErrorMessage: fmt.Sprintf("桌面任务命令处理失败：%v", recovered),
			}
		}
	}()
	return node.agentCommandHandler(command)
}

func (node *computerNode) prepareDesktopAgentSnapshot(snapshot *DesktopAgentSnapshot) {
	node.agentMu.Lock()
	defer node.agentMu.Unlock()
	prepareDesktopAgentSnapshotProtocol(snapshot, node.agentProtocolVersion)
	comparison := *snapshot
	comparison.NodeSessionID = ""
	comparison.Sequence = 0
	comparison.GeneratedAt = 0
	encoded, err := json.Marshal(comparison)
	if err == nil {
		node.agentSnapshotHash = sha256.Sum256(encoded)
		node.agentSnapshotHashSet = true
	}
	node.agentSequence++
	snapshot.NodeSessionID = node.sessionID
	snapshot.Sequence = node.agentSequence
	snapshot.GeneratedAt = time.Now().UnixMilli()
	node.agentLastPublishedAt = time.Now()
}

func prepareDesktopAgentSnapshotProtocol(snapshot *DesktopAgentSnapshot, protocolVersion int) {
	if snapshot == nil {
		return
	}
	if protocolVersion >= 2 {
		snapshot.SourcePlatform = runtime.GOOS
		snapshot.SourceArchitecture = runtime.GOARCH
	} else {
		snapshot.SourcePlatform = ""
		snapshot.SourceArchitecture = ""
	}
	for index := range snapshot.Sessions {
		if protocolVersion < 2 {
			snapshot.Sessions[index].PendingQuestion = false
			snapshot.Sessions[index].ExecutionOwner = ""
			snapshot.Sessions[index].HandoffStartedAt = 0
		}
	}
	if snapshot.ActiveSession != nil {
		prepareDesktopAgentTaskDetailProtocol(snapshot.ActiveSession, protocolVersion)
	}
	for index := range snapshot.SessionUpdates {
		prepareDesktopAgentTaskDetailProtocol(&snapshot.SessionUpdates[index], protocolVersion)
	}
}

func prepareDesktopAgentTaskDetailProtocol(detail *DesktopAgentTaskDetail, protocolVersion int) {
	if detail == nil {
		return
	}
	if protocolVersion < 2 {
		detail.PendingAsk = nil
		detail.ExecutionOwner = ""
		detail.HandoffStartedAt = 0
	}
	if protocolVersion < 3 {
		detail.WorkflowPlan = nil
	}
}

func (node *computerNode) markProcessed(requestID string) bool {
	if requestID == "" {
		return false
	}
	node.processedMu.Lock()
	defer node.processedMu.Unlock()
	if _, exists := node.processed[requestID]; exists {
		return false
	}
	node.processed[requestID] = struct{}{}
	node.processedQ = append(node.processedQ, requestID)
	if len(node.processedQ) > 256 {
		delete(node.processed, node.processedQ[0])
		node.processedQ = node.processedQ[1:]
	}
	return true
}

func randomID(prefix string) (string, error) {
	buffer := make([]byte, 16)
	if _, err := rand.Read(buffer); err != nil {
		return "", err
	}
	return prefix + "-" + hex.EncodeToString(buffer), nil
}

func mustRandomID(prefix string) string {
	value, err := randomID(prefix)
	if err != nil {
		panic(err)
	}
	return value
}

func isUnauthorized(err error) bool {
	var apiErr *apiError
	return errors.As(err, &apiErr) && apiErr.Status == http.StatusUnauthorized
}

func waitContext(ctx context.Context, duration time.Duration) error {
	timer := time.NewTimer(duration)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-timer.C:
		return nil
	}
}

func minDuration(left, right time.Duration) time.Duration {
	if left < right {
		return left
	}
	return right
}
