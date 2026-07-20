package main

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

const maxLegacySSEConcurrentRequests = 16

type legacySSEResult struct {
	response mcpRPCResponse
	err      error
}

type legacySSEMCPClient struct {
	streamURL      string
	headers        map[string]string
	client         *http.Client
	nextID         atomic.Uint64
	semaphore      chan struct{}
	requestTimeout time.Duration

	mu            sync.Mutex
	messageURL    string
	pending       map[string]chan legacySSEResult
	closed        bool
	terminalError error
	cancel        context.CancelFunc
	secrets       []string
}

func newLegacySSEMCPClient(parent context.Context, config mcpRuntimeConfig) (*legacySSEMCPClient, error) {
	if strings.TrimSpace(config.URL) == "" {
		return nil, errors.New("MCP 旧版 SSE 地址为空")
	}
	parsed, err := url.Parse(config.URL)
	if err != nil || parsed.Host == "" || (parsed.Scheme != "http" && parsed.Scheme != "https") || parsed.User != nil || parsed.Fragment != "" {
		return nil, errors.New("MCP 旧版 SSE 地址必须是有效 HTTP/HTTPS URL")
	}
	if parent == nil {
		parent = context.Background()
	}
	streamContext, cancel := context.WithCancel(parent)
	timeout := time.Duration(config.RequestTimeoutSeconds) * time.Second
	if timeout <= 0 {
		timeout = 60 * time.Second
	}
	client := &legacySSEMCPClient{
		streamURL: config.URL,
		headers:   cloneStringMap(config.Headers),
		client: &http.Client{
			CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
		},
		semaphore:      make(chan struct{}, maxLegacySSEConcurrentRequests),
		requestTimeout: timeout,
		pending:        map[string]chan legacySSEResult{},
		cancel:         cancel,
		secrets:        mcpRuntimeSecretValues(config),
	}
	ready := make(chan error, 1)
	go client.readStream(streamContext, ready)
	timer := time.NewTimer(timeout)
	defer timer.Stop()
	select {
	case err := <-ready:
		if err != nil {
			_ = client.Close()
			return nil, err
		}
		return client, nil
	case <-timer.C:
		_ = client.Close()
		return nil, errors.New("等待 MCP 旧版 SSE endpoint 事件超时")
	case <-parent.Done():
		_ = client.Close()
		return nil, parent.Err()
	}
}

func (client *legacySSEMCPClient) Request(ctx context.Context, method string, params any, result any) error {
	ctx, cancel := context.WithTimeout(ctx, client.requestTimeout)
	defer cancel()
	select {
	case client.semaphore <- struct{}{}:
		defer func() { <-client.semaphore }()
	case <-ctx.Done():
		return ctx.Err()
	}
	id := strconv.FormatUint(client.nextID.Add(1), 10)
	responseChannel := make(chan legacySSEResult, 1)
	client.mu.Lock()
	if client.closed {
		client.mu.Unlock()
		return errors.New("MCP 旧版 SSE 连接已关闭")
	}
	if client.terminalError != nil {
		err := client.terminalError
		client.mu.Unlock()
		return err
	}
	client.pending[id] = responseChannel
	client.mu.Unlock()
	payload := map[string]any{"jsonrpc": "2.0", "id": json.Number(id), "method": method, "params": params}
	if err := client.post(ctx, payload); err != nil {
		client.removePending(id)
		return err
	}
	select {
	case received := <-responseChannel:
		if received.err != nil {
			return received.err
		}
		if err := mcpRPCErrorValue(received.response); err != nil {
			return err
		}
		if result == nil || len(received.response.Result) == 0 || string(received.response.Result) == "null" {
			return nil
		}
		if raw, ok := result.(*json.RawMessage); ok {
			*raw = append((*raw)[:0], received.response.Result...)
			return nil
		}
		if err := json.Unmarshal(received.response.Result, result); err != nil {
			return fmt.Errorf("MCP 旧版 SSE 响应无法解析：%w", err)
		}
		return nil
	case <-ctx.Done():
		client.removePending(id)
		return ctx.Err()
	}
}

func (client *legacySSEMCPClient) Notify(ctx context.Context, method string, params any) error {
	ctx, cancel := context.WithTimeout(ctx, client.requestTimeout)
	defer cancel()
	return client.post(ctx, map[string]any{"jsonrpc": "2.0", "method": method, "params": params})
}

func (client *legacySSEMCPClient) Close() error {
	client.mu.Lock()
	if client.closed {
		client.mu.Unlock()
		return nil
	}
	client.closed = true
	cancel := client.cancel
	pending := client.pending
	client.pending = map[string]chan legacySSEResult{}
	client.mu.Unlock()
	if cancel != nil {
		cancel()
	}
	for _, waiter := range pending {
		waiter <- legacySSEResult{err: errors.New("MCP 旧版 SSE 连接已关闭")}
	}
	return nil
}

func (client *legacySSEMCPClient) readStream(ctx context.Context, ready chan<- error) {
	readyOnce := sync.Once{}
	signalReady := func(err error) { readyOnce.Do(func() { ready <- err }) }
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, client.streamURL, nil)
	if err != nil {
		signalReady(client.redactError(err))
		return
	}
	for key, value := range client.headers {
		request.Header.Set(key, value)
	}
	request.Header.Set("Accept", "text/event-stream")
	request.Header.Set("User-Agent", "Murong-Desktop-Agent/1.0")
	response, err := client.client.Do(request)
	if err != nil {
		signalReady(client.redactError(err))
		return
	}
	defer response.Body.Close()
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		data, _ := io.ReadAll(io.LimitReader(response.Body, 64<<10))
		err = fmt.Errorf("MCP 旧版 SSE 返回 %d：%s", response.StatusCode, truncateRunes(strings.TrimSpace(string(data)), 1_000))
		signalReady(err)
		return
	}
	if !strings.Contains(strings.ToLower(response.Header.Get("Content-Type")), "text/event-stream") {
		signalReady(errors.New("MCP 旧版 SSE 响应不是 text/event-stream"))
		return
	}

	scanner := bufio.NewScanner(response.Body)
	scanner.Buffer(make([]byte, 64<<10), maxMCPMessageBytes)
	eventName := ""
	dataLines := []string{}
	hadEndpoint := false
	flush := func() error {
		if len(dataLines) == 0 {
			eventName = ""
			return nil
		}
		data := strings.Join(dataLines, "\n")
		name := eventName
		eventName, dataLines = "", nil
		switch name {
		case "endpoint":
			if hadEndpoint {
				return nil
			}
			resolved, resolveErr := resolveLegacySSEMessageEndpoint(client.streamURL, strings.TrimSpace(data))
			if resolveErr != nil {
				return resolveErr
			}
			client.mu.Lock()
			client.messageURL = resolved
			if parsed, parseErr := url.Parse(resolved); parseErr == nil {
				for _, values := range parsed.Query() {
					for _, value := range values {
						if len(value) >= 4 {
							client.secrets = append(client.secrets, value)
						}
					}
				}
			}
			client.mu.Unlock()
			hadEndpoint = true
			signalReady(nil)
		case "", "message":
			response, parseErr := selectMCPRPCResponse([]byte(data), "")
			if parseErr == nil {
				client.deliver(response)
			}
		}
		return nil
	}
	for scanner.Scan() {
		line := scanner.Text()
		if line == "" {
			if err := flush(); err != nil {
				signalReady(err)
				client.failStream(err)
				return
			}
			continue
		}
		if strings.HasPrefix(line, ":") {
			continue
		}
		if strings.HasPrefix(line, "event:") {
			eventName = strings.TrimSpace(strings.TrimPrefix(line, "event:"))
		}
		if strings.HasPrefix(line, "data:") {
			dataLines = append(dataLines, strings.TrimSpace(strings.TrimPrefix(line, "data:")))
		}
	}
	if err := scanner.Err(); err != nil && ctx.Err() == nil {
		err = fmt.Errorf("读取 MCP 旧版 SSE 失败：%w", err)
	} else if ctx.Err() == nil {
		err = errors.New("MCP 旧版 SSE 连接已断开")
	}
	if !hadEndpoint && err == nil {
		err = errors.New("MCP 旧版 SSE 未返回 endpoint 事件")
	}
	if err != nil {
		signalReady(err)
		client.failStream(err)
	}
}

func (client *legacySSEMCPClient) post(ctx context.Context, payload any) error {
	encoded, err := json.Marshal(payload)
	if err != nil {
		return err
	}
	if len(encoded) > maxMCPMessageBytes {
		return errors.New("MCP 旧版 SSE 请求超过大小上限")
	}
	client.mu.Lock()
	endpoint, closed, terminalErr := client.messageURL, client.closed, client.terminalError
	client.mu.Unlock()
	if closed {
		return errors.New("MCP 旧版 SSE 连接已关闭")
	}
	if terminalErr != nil {
		return terminalErr
	}
	if endpoint == "" {
		return errors.New("MCP 旧版 SSE 尚未提供消息端点")
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, bytes.NewReader(encoded))
	if err != nil {
		return client.redactError(err)
	}
	for key, value := range client.headers {
		request.Header.Set(key, value)
	}
	request.Header.Set("Accept", "application/json")
	request.Header.Set("Content-Type", "application/json")
	request.Header.Set("MCP-Protocol-Version", mcpProtocolVersion)
	request.Header.Set("User-Agent", "Murong-Desktop-Agent/1.0")
	response, err := client.client.Do(request)
	if err != nil {
		return client.redactError(err)
	}
	defer response.Body.Close()
	data, err := io.ReadAll(io.LimitReader(response.Body, maxMCPMessageBytes+1))
	if err != nil {
		return fmt.Errorf("读取 MCP 旧版 SSE POST 响应失败：%w", err)
	}
	if len(data) > maxMCPMessageBytes {
		return errors.New("MCP 旧版 SSE POST 响应超过大小上限")
	}
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		message := strings.TrimSpace(string(data))
		if message == "" {
			message = response.Status
		}
		return fmt.Errorf("MCP 旧版 SSE POST 返回 %d：%s", response.StatusCode, truncateRunes(message, 1_000))
	}
	if len(bytes.TrimSpace(data)) > 0 {
		if parsed, parseErr := selectMCPRPCResponse(data, ""); parseErr == nil {
			client.deliver(parsed)
		}
	}
	return nil
}

func (client *legacySSEMCPClient) deliver(response mcpRPCResponse) {
	id := normalizedMCPResponseID(response.ID)
	if id == "" {
		return
	}
	client.mu.Lock()
	waiter := client.pending[id]
	delete(client.pending, id)
	client.mu.Unlock()
	if waiter != nil {
		waiter <- legacySSEResult{response: response}
	}
}

func (client *legacySSEMCPClient) removePending(id string) {
	client.mu.Lock()
	delete(client.pending, id)
	client.mu.Unlock()
}

func (client *legacySSEMCPClient) failStream(err error) {
	client.mu.Lock()
	if client.closed || client.terminalError != nil {
		client.mu.Unlock()
		return
	}
	client.terminalError = err
	pending := client.pending
	client.pending = map[string]chan legacySSEResult{}
	client.mu.Unlock()
	for _, waiter := range pending {
		waiter <- legacySSEResult{err: err}
	}
}

func (client *legacySSEMCPClient) redactError(err error) error {
	message := err.Error()
	client.mu.Lock()
	secrets := append([]string{}, client.secrets...)
	client.mu.Unlock()
	for _, secret := range secrets {
		if secret != "" {
			message = strings.ReplaceAll(message, secret, "[REDACTED]")
		}
	}
	return errors.New("MCP 旧版 SSE 请求失败：" + truncateRunes(message, 1_500))
}

func resolveLegacySSEMessageEndpoint(streamURL, endpoint string) (string, error) {
	base, err := url.Parse(streamURL)
	if err != nil {
		return "", errors.New("MCP 旧版 SSE 基础地址无效")
	}
	reference, err := url.Parse(endpoint)
	if err != nil || endpoint == "" {
		return "", errors.New("MCP 旧版 SSE endpoint 事件无效")
	}
	resolved := base.ResolveReference(reference)
	if resolved.Scheme != base.Scheme || !strings.EqualFold(resolved.Host, base.Host) || resolved.User != nil || resolved.Fragment != "" ||
		(resolved.Scheme != "http" && resolved.Scheme != "https") {
		return "", errors.New("MCP 旧版 SSE 消息端点必须与 SSE 地址同源")
	}
	return resolved.String(), nil
}

func normalizedMCPResponseID(raw json.RawMessage) string {
	value := strings.TrimSpace(string(raw))
	if value == "" || value == "null" {
		return ""
	}
	if strings.HasPrefix(value, `"`) {
		var decoded string
		if json.Unmarshal(raw, &decoded) == nil {
			return decoded
		}
	}
	return value
}
