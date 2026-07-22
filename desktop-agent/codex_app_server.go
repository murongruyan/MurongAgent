package main

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

const codexRPCMaxMessageBytes = 32 << 20

type codexRPCError struct {
	Code    int             `json:"code"`
	Message string          `json:"message"`
	Data    json.RawMessage `json:"data,omitempty"`
}

type codexRPCEnvelope struct {
	ID     json.RawMessage `json:"id,omitempty"`
	Method string          `json:"method,omitempty"`
	Params json.RawMessage `json:"params,omitempty"`
	Result json.RawMessage `json:"result,omitempty"`
	Error  *codexRPCError  `json:"error,omitempty"`
}

type codexRPCResponse struct {
	Result json.RawMessage
	Error  *codexRPCError
}

type codexNotification struct {
	Method string
	Params json.RawMessage
}

type codexServerRequest struct {
	ID     json.RawMessage
	Method string
	Params json.RawMessage
}

type codexThreadSubscription struct {
	sessionID string
	context   context.Context
	events    chan codexNotification
	config    desktopConfig
	planMode  bool
}

type codexRawRateLimitWindow struct {
	UsedPercent        float64 `json:"usedPercent"`
	WindowDurationMins *int64  `json:"windowDurationMins"`
	ResetsAt           *int64  `json:"resetsAt"`
}

type codexRawRateLimit struct {
	LimitID   *string                  `json:"limitId"`
	LimitName *string                  `json:"limitName"`
	PlanType  *string                  `json:"planType"`
	Primary   *codexRawRateLimitWindow `json:"primary"`
	Secondary *codexRawRateLimitWindow `json:"secondary"`
	Credits   *struct {
		HasCredits bool    `json:"hasCredits"`
		Unlimited  bool    `json:"unlimited"`
		Balance    *string `json:"balance"`
	} `json:"credits"`
}

type codexRPCClient struct {
	command *exec.Cmd
	stdin   io.WriteCloser

	writeMu sync.Mutex
	mu      sync.Mutex
	pending map[string]chan codexRPCResponse
	nextID  atomic.Uint64
	done    chan struct{}
	finish  sync.Once
	err     error
	stderr  *boundedMCPLog

	onNotification  func(codexNotification)
	onServerRequest func(codexServerRequest)
	onExit          func(error)
}

func newCodexRPCClient(executable, codexHome string, onNotification func(codexNotification), onServerRequest func(codexServerRequest), onExit func(error)) (*codexRPCClient, error) {
	command := exec.Command(executable, "app-server", "--stdio")
	command.Env = environmentWithValue(os.Environ(), "CODEX_HOME", codexHome)
	prepareHiddenCommand(command)
	stdin, err := command.StdinPipe()
	if err != nil {
		return nil, err
	}
	stdout, err := command.StdoutPipe()
	if err != nil {
		return nil, err
	}
	stderr, err := command.StderrPipe()
	if err != nil {
		return nil, err
	}
	client := &codexRPCClient{
		command: command, stdin: stdin, pending: map[string]chan codexRPCResponse{}, done: make(chan struct{}),
		stderr: &boundedMCPLog{limit: 32 << 10}, onNotification: onNotification, onServerRequest: onServerRequest, onExit: onExit,
	}
	if err := command.Start(); err != nil {
		return nil, fmt.Errorf("启动 Codex app-server 失败：%w", err)
	}
	go func() { _, _ = io.Copy(client.stderr, stderr) }()
	go client.readLoop(stdout)
	go func() {
		err := command.Wait()
		if err == nil {
			err = errors.New("Codex app-server 已退出")
		}
		client.complete(fmt.Errorf("%w%s", err, client.stderrDiagnostic()))
	}()
	return client, nil
}

func (client *codexRPCClient) Request(ctx context.Context, method string, params any, result any) error {
	id := client.nextID.Add(1)
	idKey := strconv.FormatUint(id, 10)
	responseChannel := make(chan codexRPCResponse, 1)
	client.mu.Lock()
	select {
	case <-client.done:
		err := client.err
		client.mu.Unlock()
		if err == nil {
			err = errors.New("Codex app-server 已关闭")
		}
		return err
	default:
		client.pending[idKey] = responseChannel
		client.mu.Unlock()
	}
	message := map[string]any{"id": id, "method": method}
	if params != nil {
		message["params"] = params
	}
	if err := client.writeMessage(message); err != nil {
		client.removePending(idKey)
		return err
	}
	select {
	case response := <-responseChannel:
		if response.Error != nil {
			return fmt.Errorf("Codex 请求 %s 失败（%d）：%s", method, response.Error.Code, truncateRunes(response.Error.Message, 1200))
		}
		if result == nil || len(response.Result) == 0 || string(response.Result) == "null" {
			return nil
		}
		if err := json.Unmarshal(response.Result, result); err != nil {
			return fmt.Errorf("Codex 响应 %s 无法解析：%w", method, err)
		}
		return nil
	case <-ctx.Done():
		client.removePending(idKey)
		return ctx.Err()
	case <-client.done:
		client.removePending(idKey)
		client.mu.Lock()
		err := client.err
		client.mu.Unlock()
		if err == nil {
			err = errors.New("Codex app-server 已关闭")
		}
		return err
	}
}

func (client *codexRPCClient) Notify(ctx context.Context, method string, params any) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	message := map[string]any{"method": method}
	if params != nil {
		message["params"] = params
	}
	return client.writeMessage(message)
}

func (client *codexRPCClient) Respond(ctx context.Context, id json.RawMessage, result any, rpcError *codexRPCError) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	if len(id) == 0 {
		return errors.New("Codex 服务端请求缺少 ID")
	}
	message := map[string]any{"id": json.RawMessage(id)}
	if rpcError != nil {
		message["error"] = rpcError
	} else {
		message["result"] = result
	}
	return client.writeMessage(message)
}

func (client *codexRPCClient) writeMessage(value any) error {
	data, err := json.Marshal(value)
	if err != nil {
		return err
	}
	if len(data) > codexRPCMaxMessageBytes {
		return errors.New("Codex 协议消息超过 32 MiB")
	}
	client.writeMu.Lock()
	defer client.writeMu.Unlock()
	select {
	case <-client.done:
		return errors.New("Codex app-server 已关闭")
	default:
	}
	data = append(data, '\n')
	_, err = client.stdin.Write(data)
	return err
}

func (client *codexRPCClient) readLoop(reader io.Reader) {
	scanner := bufio.NewScanner(reader)
	scanner.Buffer(make([]byte, 64<<10), codexRPCMaxMessageBytes)
	for scanner.Scan() {
		line := append([]byte(nil), scanner.Bytes()...)
		var envelope codexRPCEnvelope
		if err := json.Unmarshal(line, &envelope); err != nil {
			client.complete(fmt.Errorf("Codex app-server 输出了无效 JSON：%w", err))
			return
		}
		if envelope.Method != "" {
			if len(envelope.ID) > 0 && string(envelope.ID) != "null" {
				if client.onServerRequest != nil {
					request := codexServerRequest{ID: append(json.RawMessage(nil), envelope.ID...), Method: envelope.Method, Params: append(json.RawMessage(nil), envelope.Params...)}
					go client.onServerRequest(request)
				}
			} else if client.onNotification != nil {
				client.onNotification(codexNotification{Method: envelope.Method, Params: append(json.RawMessage(nil), envelope.Params...)})
			}
			continue
		}
		if len(envelope.ID) == 0 {
			continue
		}
		idKey := normalizeCodexRPCID(envelope.ID)
		client.mu.Lock()
		channel := client.pending[idKey]
		delete(client.pending, idKey)
		client.mu.Unlock()
		if channel != nil {
			channel <- codexRPCResponse{Result: append(json.RawMessage(nil), envelope.Result...), Error: envelope.Error}
		}
	}
	if err := scanner.Err(); err != nil {
		client.complete(fmt.Errorf("读取 Codex app-server 失败：%w", err))
	}
}

func normalizeCodexRPCID(id json.RawMessage) string {
	var number json.Number
	if err := json.Unmarshal(id, &number); err == nil {
		return number.String()
	}
	var text string
	if err := json.Unmarshal(id, &text); err == nil {
		return text
	}
	return string(id)
}

func (client *codexRPCClient) removePending(id string) {
	client.mu.Lock()
	delete(client.pending, id)
	client.mu.Unlock()
}

func (client *codexRPCClient) complete(err error) {
	client.finish.Do(func() {
		client.mu.Lock()
		client.err = err
		pending := client.pending
		client.pending = map[string]chan codexRPCResponse{}
		close(client.done)
		client.mu.Unlock()
		for _, channel := range pending {
			channel <- codexRPCResponse{Error: &codexRPCError{Code: -32000, Message: err.Error()}}
		}
		if client.onExit != nil {
			client.onExit(err)
		}
	})
}

func (client *codexRPCClient) stderrDiagnostic() string {
	value := strings.TrimSpace(client.stderr.String())
	if value == "" {
		return ""
	}
	return "；stderr：" + truncateRunes(value, 2000)
}

func (client *codexRPCClient) Close() {
	client.complete(errors.New("Codex app-server 已关闭"))
	_ = client.stdin.Close()
	if client.command != nil && client.command.Process != nil {
		_ = client.command.Process.Kill()
	}
}

type codexAppServer struct {
	runtimeRoot string
	codexHome   string

	lifecycleMu sync.Mutex
	mu          sync.Mutex
	client      *codexRPCClient
	executable  string
	builtin     bool
	version     string
	status      CodexRuntimeStatus
	listener    func(CodexRuntimeStatus)
	requester   func(codexServerRequest)
	subscribers map[string]codexThreadSubscription
}

func newCodexAppServer(runtimeRoot string) *codexAppServer {
	server := &codexAppServer{
		runtimeRoot: runtimeRoot,
		codexHome:   filepath.Join(runtimeRoot, "codex-home"),
		subscribers: map[string]codexThreadSubscription{},
	}
	server.status = server.staticStatus()
	return server
}

func (server *codexAppServer) staticStatus() CodexRuntimeStatus {
	status := CodexRuntimeStatus{Models: []CodexModelInfo{}, RateLimits: []CodexRateLimitInfo{}, RequiresAuth: true}
	if hasEmbeddedCodexRuntime() {
		status.Available = true
		status.Builtin = true
		status.Version = "codex-cli " + embeddedCodexVersion + "（内置）"
		return status
	}
	if path, err := discoverExternalCodexExecutable(); err == nil {
		status.Available = true
		status.ExecutablePath = path
	}
	return status
}

func (server *codexAppServer) SetListener(listener func(CodexRuntimeStatus)) {
	server.mu.Lock()
	server.listener = listener
	server.mu.Unlock()
}

func (server *codexAppServer) SetServerRequestHandler(handler func(codexServerRequest)) {
	server.mu.Lock()
	server.requester = handler
	server.mu.Unlock()
}

func (server *codexAppServer) Status() CodexRuntimeStatus {
	server.mu.Lock()
	defer server.mu.Unlock()
	return cloneCodexRuntimeStatus(server.status)
}

func cloneCodexRuntimeStatus(status CodexRuntimeStatus) CodexRuntimeStatus {
	status.Models = append([]CodexModelInfo(nil), status.Models...)
	for index := range status.Models {
		status.Models[index].SupportedReasoningEfforts = append([]string(nil), status.Models[index].SupportedReasoningEfforts...)
	}
	status.RateLimits = append([]CodexRateLimitInfo(nil), status.RateLimits...)
	for index := range status.RateLimits {
		if status.RateLimits[index].Primary != nil {
			primary := *status.RateLimits[index].Primary
			status.RateLimits[index].Primary = &primary
		}
		if status.RateLimits[index].Secondary != nil {
			secondary := *status.RateLimits[index].Secondary
			status.RateLimits[index].Secondary = &secondary
		}
		if status.RateLimits[index].Credits != nil {
			credits := *status.RateLimits[index].Credits
			status.RateLimits[index].Credits = &credits
		}
	}
	return status
}

func (server *codexAppServer) updateStatus(update func(*CodexRuntimeStatus)) CodexRuntimeStatus {
	server.mu.Lock()
	update(&server.status)
	snapshot := cloneCodexRuntimeStatus(server.status)
	listener := server.listener
	server.mu.Unlock()
	if listener != nil {
		listener(snapshot)
	}
	return snapshot
}

func (server *codexAppServer) ensureStarted(ctx context.Context, preferred string) (*codexRPCClient, error) {
	server.lifecycleMu.Lock()
	defer server.lifecycleMu.Unlock()
	executable, builtin, err := resolveCodexExecutable(server.runtimeRoot, preferred)
	if err != nil {
		server.updateStatus(func(status *CodexRuntimeStatus) {
			status.Error = err.Error()
			status.Available = false
			status.Running = false
		})
		return nil, err
	}
	server.mu.Lock()
	current := server.client
	currentPath := server.executable
	server.mu.Unlock()
	if current != nil && strings.EqualFold(currentPath, executable) {
		select {
		case <-current.done:
		default:
			return current, nil
		}
	}
	if current != nil {
		current.Close()
	}
	version := probeCodexVersion(ctx, executable)
	if err := ensurePrivateCodexHome(server.codexHome); err != nil {
		return nil, err
	}
	client, err := newCodexRPCClient(executable, server.codexHome, server.handleNotification, server.handleServerRequest, server.handleExit)
	if err != nil {
		server.updateStatus(func(status *CodexRuntimeStatus) {
			status.Error = err.Error()
			status.Available = true
			status.Running = false
			status.ExecutablePath = executable
			status.Builtin = builtin
		})
		return nil, err
	}
	server.mu.Lock()
	server.client = client
	server.executable = executable
	server.builtin = builtin
	server.version = version
	server.mu.Unlock()
	initializeContext, cancel := context.WithTimeout(ctx, 20*time.Second)
	defer cancel()
	var initialized struct {
		CodexHome string `json:"codexHome"`
		UserAgent string `json:"userAgent"`
	}
	err = client.Request(initializeContext, "initialize", codexClientInitializeParams(), &initialized)
	if err == nil {
		err = client.Notify(initializeContext, "initialized", nil)
	}
	if err != nil {
		client.Close()
		return nil, err
	}
	server.updateStatus(func(status *CodexRuntimeStatus) {
		status.Available = true
		status.Builtin = builtin
		status.Running = true
		status.ExecutablePath = executable
		status.Version = version
		status.Error = ""
	})
	return client, nil
}

func codexClientInitializeParams() map[string]any {
	return map[string]any{
		"clientInfo":   map[string]any{"name": "murong-desktop-agent", "title": "Murong Desktop Agent", "version": desktopAgentVersion},
		"capabilities": map[string]any{"experimentalApi": true},
	}
}

func probeCodexVersion(parent context.Context, executable string) string {
	ctx, cancel := context.WithTimeout(parent, 10*time.Second)
	defer cancel()
	command := exec.CommandContext(ctx, executable, "--version")
	prepareHiddenCommand(command)
	output, err := command.Output()
	if err != nil {
		return ""
	}
	return truncateRunes(string(output), 200)
}

func (server *codexAppServer) Refresh(ctx context.Context, preferred string) (CodexRuntimeStatus, error) {
	client, err := server.ensureStarted(ctx, preferred)
	if err != nil {
		return server.Status(), err
	}
	requestContext, cancel := context.WithTimeout(ctx, 30*time.Second)
	defer cancel()
	var account struct {
		Account *struct {
			Type     string `json:"type"`
			Email    string `json:"email"`
			PlanType string `json:"planType"`
		} `json:"account"`
		RequiresOpenAIAuth bool `json:"requiresOpenaiAuth"`
	}
	if err := client.Request(requestContext, "account/read", map[string]any{"refreshToken": false}, &account); err != nil {
		return server.Status(), err
	}
	models := []CodexModelInfo{}
	rateLimits := []CodexRateLimitInfo{}
	var rateLimitErr error
	if account.Account != nil {
		models, err = server.listModels(requestContext, client)
		if err != nil {
			return server.Status(), err
		}
		rateLimits, rateLimitErr = server.readRateLimits(requestContext, client)
	}
	snapshot := server.updateStatus(func(status *CodexRuntimeStatus) {
		status.LoggedIn = account.Account != nil
		status.RequiresAuth = account.RequiresOpenAIAuth
		status.AccountType, status.Email, status.PlanType = "", "", ""
		if account.Account != nil {
			status.AccountType = account.Account.Type
			status.Email = account.Account.Email
			status.PlanType = account.Account.PlanType
		}
		status.Models = models
		status.RateLimits = rateLimits
		status.RateLimitError = ""
		if rateLimitErr != nil {
			status.RateLimitError = truncateRunes(rateLimitErr.Error(), 800)
		}
		status.Error = ""
		if status.LoggedIn {
			status.Login = CodexLoginInfo{}
		}
	})
	return snapshot, nil
}

func (server *codexAppServer) readRateLimits(ctx context.Context, client *codexRPCClient) ([]CodexRateLimitInfo, error) {
	var response struct {
		RateLimits          codexRawRateLimit            `json:"rateLimits"`
		RateLimitsByLimitID map[string]codexRawRateLimit `json:"rateLimitsByLimitId"`
	}
	if err := client.Request(ctx, "account/rateLimits/read", nil, &response); err != nil {
		return nil, err
	}
	result := make([]CodexRateLimitInfo, 0, len(response.RateLimitsByLimitID)+1)
	if len(response.RateLimitsByLimitID) > 0 {
		keys := make([]string, 0, len(response.RateLimitsByLimitID))
		for key := range response.RateLimitsByLimitID {
			keys = append(keys, key)
		}
		sort.Strings(keys)
		for _, key := range keys {
			value := normalizeCodexRateLimit(response.RateLimitsByLimitID[key])
			if value.LimitID == "" {
				value.LimitID = truncateRunes(key, 128)
			}
			result = append(result, value)
		}
	} else {
		result = append(result, normalizeCodexRateLimit(response.RateLimits))
	}
	return result, nil
}

func normalizeCodexRateLimit(raw codexRawRateLimit) CodexRateLimitInfo {
	value := CodexRateLimitInfo{}
	if raw.LimitID != nil {
		value.LimitID = truncateRunes(*raw.LimitID, 128)
	}
	if raw.LimitName != nil {
		value.LimitName = truncateRunes(*raw.LimitName, 160)
	}
	if raw.PlanType != nil {
		value.PlanType = truncateRunes(*raw.PlanType, 80)
	}
	value.Primary = normalizeCodexRateLimitWindow(raw.Primary)
	value.Secondary = normalizeCodexRateLimitWindow(raw.Secondary)
	if raw.Credits != nil {
		credits := &CodexCreditsInfo{HasCredits: raw.Credits.HasCredits, Unlimited: raw.Credits.Unlimited}
		if raw.Credits.Balance != nil {
			credits.Balance = truncateRunes(*raw.Credits.Balance, 80)
		}
		value.Credits = credits
	}
	return value
}

func normalizeCodexRateLimitWindow(raw *codexRawRateLimitWindow) *CodexRateLimitWindow {
	if raw == nil {
		return nil
	}
	used := raw.UsedPercent
	if used < 0 {
		used = 0
	} else if used > 100 {
		used = 100
	}
	window := &CodexRateLimitWindow{UsedPercent: used}
	if raw.WindowDurationMins != nil && *raw.WindowDurationMins > 0 {
		window.WindowDurationMins = *raw.WindowDurationMins
	}
	if raw.ResetsAt != nil && *raw.ResetsAt > 0 {
		window.ResetsAt = *raw.ResetsAt
	}
	return window
}

func (server *codexAppServer) listModels(ctx context.Context, client *codexRPCClient) ([]CodexModelInfo, error) {
	result := make([]CodexModelInfo, 0, 32)
	cursor := ""
	for page := 0; page < 10; page++ {
		params := map[string]any{"limit": 100}
		if cursor != "" {
			params["cursor"] = cursor
		}
		var response struct {
			Data []struct {
				ID                     string `json:"id"`
				Model                  string `json:"model"`
				DisplayName            string `json:"displayName"`
				Description            string `json:"description"`
				Hidden                 bool   `json:"hidden"`
				DefaultReasoningEffort string `json:"defaultReasoningEffort"`
				SupportedEfforts       []struct {
					ReasoningEffort string `json:"reasoningEffort"`
				} `json:"supportedReasoningEfforts"`
				IsDefault bool `json:"isDefault"`
			} `json:"data"`
			NextCursor string `json:"nextCursor"`
		}
		if err := client.Request(ctx, "model/list", params, &response); err != nil {
			return nil, err
		}
		for _, model := range response.Data {
			if model.Hidden {
				continue
			}
			efforts := make([]string, 0, len(model.SupportedEfforts))
			for _, effort := range model.SupportedEfforts {
				efforts = append(efforts, effort.ReasoningEffort)
			}
			result = append(result, CodexModelInfo{ID: model.ID, Model: model.Model, DisplayName: model.DisplayName,
				Description: truncateRunes(model.Description, 1000), DefaultReasoningEffort: model.DefaultReasoningEffort,
				SupportedReasoningEfforts: efforts, IsDefault: model.IsDefault})
		}
		cursor = strings.TrimSpace(response.NextCursor)
		if cursor == "" {
			break
		}
	}
	return result, nil
}

func (server *codexAppServer) StartDeviceLogin(ctx context.Context, preferred string) (CodexRuntimeStatus, error) {
	client, err := server.ensureStarted(ctx, preferred)
	if err != nil {
		return server.Status(), err
	}
	requestContext, cancel := context.WithTimeout(ctx, 30*time.Second)
	defer cancel()
	var response struct {
		Type            string `json:"type"`
		LoginID         string `json:"loginId"`
		VerificationURL string `json:"verificationUrl"`
		UserCode        string `json:"userCode"`
	}
	if err := client.Request(requestContext, "account/login/start", map[string]any{"type": "chatgptDeviceCode"}, &response); err != nil {
		return server.Status(), err
	}
	if response.Type != "chatgptDeviceCode" || response.LoginID == "" || response.VerificationURL == "" || response.UserCode == "" {
		return server.Status(), errors.New("Codex 没有返回完整的 ChatGPT 设备登录信息")
	}
	return server.updateStatus(func(status *CodexRuntimeStatus) {
		status.Login = CodexLoginInfo{LoginID: response.LoginID, VerificationURL: response.VerificationURL, UserCode: response.UserCode, Waiting: true}
		status.Error = ""
	}), nil
}

func (server *codexAppServer) StartThread(ctx context.Context, preferred string, params map[string]any) (string, error) {
	client, err := server.ensureStarted(ctx, preferred)
	if err != nil {
		return "", err
	}
	var response struct {
		Thread struct {
			ID string `json:"id"`
		} `json:"thread"`
	}
	if err := client.Request(ctx, "thread/start", params, &response); err != nil {
		return "", err
	}
	if strings.TrimSpace(response.Thread.ID) == "" {
		return "", errors.New("Codex thread/start 没有返回线程 ID")
	}
	return response.Thread.ID, nil
}

func (server *codexAppServer) ResumeThread(ctx context.Context, preferred, threadID string, params map[string]any) error {
	client, err := server.ensureStarted(ctx, preferred)
	if err != nil {
		return err
	}
	params["threadId"] = threadID
	return client.Request(ctx, "thread/resume", params, nil)
}

func (server *codexAppServer) StartTurn(ctx context.Context, preferred, threadID, text, model, effort, cwd string) (string, error) {
	return server.StartTurnWithImages(ctx, preferred, threadID, text, model, effort, cwd, nil)
}

func (server *codexAppServer) StartTurnWithImages(ctx context.Context, preferred, threadID, text, model, effort, cwd string, imagePaths []string) (string, error) {
	client, err := server.ensureStarted(ctx, preferred)
	if err != nil {
		return "", err
	}
	input := buildCodexTurnInputs(text, imagePaths)
	params := map[string]any{
		"threadId": threadID,
		"input":    input,
		"cwd":      cwd,
	}
	if strings.TrimSpace(model) != "" {
		params["model"] = strings.TrimSpace(model)
	}
	if strings.TrimSpace(effort) != "" {
		params["effort"] = strings.TrimSpace(effort)
	}
	var response struct {
		Turn struct {
			ID string `json:"id"`
		} `json:"turn"`
	}
	if err := client.Request(ctx, "turn/start", params, &response); err != nil {
		return "", err
	}
	if strings.TrimSpace(response.Turn.ID) == "" {
		return "", errors.New("Codex turn/start 没有返回 turn ID")
	}
	return response.Turn.ID, nil
}

func buildCodexTurnInputs(text string, imagePaths []string) []any {
	input := []any{map[string]any{"type": "text", "text": text, "text_elements": []any{}}}
	for _, path := range imagePaths {
		if value := strings.TrimSpace(path); value != "" {
			input = append(input, map[string]any{"type": "localImage", "path": value})
		}
	}
	return input
}

func (server *codexAppServer) InterruptTurn(ctx context.Context, threadID, turnID string) error {
	server.mu.Lock()
	client := server.client
	server.mu.Unlock()
	if client == nil {
		return errors.New("Codex app-server 尚未运行")
	}
	return client.Request(ctx, "turn/interrupt", map[string]any{"threadId": threadID, "turnId": turnID}, nil)
}

func (server *codexAppServer) Subscribe(ctx context.Context, threadID, sessionID string, configs ...desktopConfig) (<-chan codexNotification, func()) {
	config := desktopConfig{}
	if len(configs) > 0 {
		config = configs[0]
	}
	return server.subscribe(ctx, threadID, sessionID, config, false)
}

func (server *codexAppServer) SubscribeForRun(ctx context.Context, threadID, sessionID string, config desktopConfig, planMode bool) (<-chan codexNotification, func()) {
	return server.subscribe(ctx, threadID, sessionID, config, planMode)
}

func (server *codexAppServer) subscribe(ctx context.Context, threadID, sessionID string, config desktopConfig, planMode bool) (<-chan codexNotification, func()) {
	channel := make(chan codexNotification, 8192)
	server.mu.Lock()
	server.subscribers[threadID] = codexThreadSubscription{sessionID: sessionID, context: ctx, events: channel, config: config, planMode: planMode}
	server.mu.Unlock()
	return channel, func() {
		server.mu.Lock()
		if current, ok := server.subscribers[threadID]; ok && current.events == channel {
			delete(server.subscribers, threadID)
		}
		server.mu.Unlock()
	}
}

func (server *codexAppServer) subscriptionForThread(threadID string) codexThreadSubscription {
	server.mu.Lock()
	defer server.mu.Unlock()
	return server.subscribers[threadID]
}

func (server *codexAppServer) RespondToServerRequest(ctx context.Context, id json.RawMessage, result any, rpcError *codexRPCError) error {
	server.mu.Lock()
	client := server.client
	server.mu.Unlock()
	if client == nil {
		return errors.New("Codex app-server 尚未运行")
	}
	return client.Respond(ctx, id, result, rpcError)
}

func (server *codexAppServer) handleNotification(notification codexNotification) {
	if notification.Method == "account/rateLimits/updated" {
		var update struct {
			RateLimits codexRawRateLimit `json:"rateLimits"`
		}
		if json.Unmarshal(notification.Params, &update) == nil {
			server.updateStatus(func(status *CodexRuntimeStatus) {
				target := -1
				limitID := ""
				if update.RateLimits.LimitID != nil {
					limitID = strings.TrimSpace(*update.RateLimits.LimitID)
				}
				for index := range status.RateLimits {
					if limitID != "" && status.RateLimits[index].LimitID == limitID {
						target = index
						break
					}
				}
				if target < 0 && limitID == "" && len(status.RateLimits) == 1 {
					target = 0
				}
				if target >= 0 {
					status.RateLimits[target] = mergeCodexRateLimit(status.RateLimits[target], update.RateLimits)
				} else {
					status.RateLimits = append(status.RateLimits, normalizeCodexRateLimit(update.RateLimits))
				}
			})
		}
	}
	if notification.Method == "account/login/completed" {
		var completed struct {
			LoginID string `json:"loginId"`
			Success bool   `json:"success"`
			Error   string `json:"error"`
		}
		if json.Unmarshal(notification.Params, &completed) == nil {
			server.updateStatus(func(status *CodexRuntimeStatus) {
				status.Login.Waiting = false
				if completed.Success {
					status.Error = ""
				} else {
					status.Error = truncateRunes(completed.Error, 1200)
				}
			})
			if completed.Success {
				go func() {
					ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
					defer cancel()
					server.mu.Lock()
					path := server.executable
					server.mu.Unlock()
					_, _ = server.Refresh(ctx, path)
				}()
			}
		}
	}
	threadID := codexNotificationThreadID(notification.Params)
	if threadID == "" {
		return
	}
	server.mu.Lock()
	subscriber, ok := server.subscribers[threadID]
	server.mu.Unlock()
	if !ok {
		return
	}
	select {
	case subscriber.events <- notification:
	case <-time.After(30 * time.Second):
		server.updateStatus(func(status *CodexRuntimeStatus) { status.Error = "Codex 事件队列阻塞，已丢弃一个事件" })
	}
}

func mergeCodexRateLimit(current CodexRateLimitInfo, raw codexRawRateLimit) CodexRateLimitInfo {
	if raw.LimitID != nil {
		current.LimitID = truncateRunes(*raw.LimitID, 128)
	}
	if raw.LimitName != nil {
		current.LimitName = truncateRunes(*raw.LimitName, 160)
	}
	if raw.PlanType != nil {
		current.PlanType = truncateRunes(*raw.PlanType, 80)
	}
	if raw.Primary != nil {
		current.Primary = normalizeCodexRateLimitWindow(raw.Primary)
	}
	if raw.Secondary != nil {
		current.Secondary = normalizeCodexRateLimitWindow(raw.Secondary)
	}
	if raw.Credits != nil {
		credits := &CodexCreditsInfo{HasCredits: raw.Credits.HasCredits, Unlimited: raw.Credits.Unlimited}
		if raw.Credits.Balance != nil {
			credits.Balance = truncateRunes(*raw.Credits.Balance, 80)
		}
		current.Credits = credits
	}
	return current
}

func codexNotificationThreadID(params json.RawMessage) string {
	var envelope struct {
		ThreadID string `json:"threadId"`
	}
	_ = json.Unmarshal(params, &envelope)
	return strings.TrimSpace(envelope.ThreadID)
}

func (server *codexAppServer) handleServerRequest(request codexServerRequest) {
	server.mu.Lock()
	handler := server.requester
	server.mu.Unlock()
	if handler != nil {
		handler(request)
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	_ = server.RespondToServerRequest(ctx, request.ID, nil, &codexRPCError{Code: -32601, Message: "Murong 尚未绑定 Codex 审批处理器"})
}

func (server *codexAppServer) handleExit(err error) {
	server.mu.Lock()
	current := server.client
	if current != nil {
		select {
		case <-current.done:
			server.client = nil
		default:
		}
	}
	subscribers := make([]codexThreadSubscription, 0, len(server.subscribers))
	for _, subscriber := range server.subscribers {
		subscribers = append(subscribers, subscriber)
	}
	server.mu.Unlock()
	message, _ := json.Marshal(map[string]any{"message": truncateRunes(err.Error(), 1500)})
	for _, subscriber := range subscribers {
		select {
		case subscriber.events <- codexNotification{Method: "codex/process-exited", Params: message}:
		default:
		}
	}
	server.updateStatus(func(status *CodexRuntimeStatus) {
		status.Running = false
		if err != nil && !strings.Contains(err.Error(), "已关闭") {
			status.Error = truncateRunes(err.Error(), 1500)
		}
	})
}

func (server *codexAppServer) Close() {
	server.lifecycleMu.Lock()
	server.mu.Lock()
	client := server.client
	server.client = nil
	server.subscribers = map[string]codexThreadSubscription{}
	server.mu.Unlock()
	if client != nil {
		client.Close()
	}
	server.lifecycleMu.Unlock()
	server.updateStatus(func(status *CodexRuntimeStatus) { status.Running = false })
}

func codexRuntimeRootFromStore(store *desktopStore) string {
	if store == nil {
		return filepath.Join(os.TempDir(), "Murong")
	}
	return filepath.Dir(store.configPath)
}
