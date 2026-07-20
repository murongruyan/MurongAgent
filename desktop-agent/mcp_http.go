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
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

type httpMCPClient struct {
	endpoint string
	headers  map[string]string
	client   *http.Client
	nextID   atomic.Uint64
	mu       sync.Mutex
	session  string
	closed   bool
	secrets  []string
}

func newHTTPMCPClient(config mcpRuntimeConfig) (*httpMCPClient, error) {
	if config.URL == "" {
		return nil, errors.New("MCP Streamable HTTP 地址为空")
	}
	timeout := time.Duration(config.RequestTimeoutSeconds) * time.Second
	if timeout <= 0 {
		timeout = 60 * time.Second
	}
	return &httpMCPClient{
		endpoint: config.URL,
		headers:  cloneStringMap(config.Headers),
		client: &http.Client{
			Timeout:       timeout,
			CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
		},
		secrets: mcpRuntimeSecretValues(config),
	}, nil
}

func (client *httpMCPClient) Request(ctx context.Context, method string, params any, result any) error {
	id := client.nextID.Add(1)
	response, err := client.send(ctx, http.MethodPost, map[string]any{
		"jsonrpc": "2.0", "id": id, "method": method, "params": params,
	}, strconv.FormatUint(id, 10), false)
	if err != nil {
		return err
	}
	if err := mcpRPCErrorValue(response); err != nil {
		return err
	}
	if result == nil || len(response.Result) == 0 || string(response.Result) == "null" {
		return nil
	}
	if raw, ok := result.(*json.RawMessage); ok {
		*raw = append((*raw)[:0], response.Result...)
		return nil
	}
	if err := json.Unmarshal(response.Result, result); err != nil {
		return fmt.Errorf("MCP HTTP 响应无法解析：%w", err)
	}
	return nil
}

func (client *httpMCPClient) Notify(ctx context.Context, method string, params any) error {
	_, err := client.send(ctx, http.MethodPost, map[string]any{
		"jsonrpc": "2.0", "method": method, "params": params,
	}, "", true)
	return err
}

func (client *httpMCPClient) Close() error {
	client.mu.Lock()
	if client.closed {
		client.mu.Unlock()
		return nil
	}
	client.closed = true
	session := client.session
	client.mu.Unlock()
	if session == "" {
		return nil
	}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	request, err := http.NewRequestWithContext(ctx, http.MethodDelete, client.endpoint, nil)
	if err != nil {
		return nil
	}
	client.applyHeaders(request)
	response, err := client.client.Do(request)
	if err == nil {
		_ = response.Body.Close()
	}
	return nil
}

func (client *httpMCPClient) send(ctx context.Context, method string, payload any, expectedID string, notification bool) (mcpRPCResponse, error) {
	client.mu.Lock()
	closed := client.closed
	client.mu.Unlock()
	if closed {
		return mcpRPCResponse{}, errors.New("MCP HTTP 连接已关闭")
	}
	var body io.Reader
	if payload != nil {
		encoded, err := json.Marshal(payload)
		if err != nil {
			return mcpRPCResponse{}, err
		}
		if len(encoded) > maxMCPMessageBytes {
			return mcpRPCResponse{}, errors.New("MCP HTTP 请求超过大小上限")
		}
		body = bytes.NewReader(encoded)
	}
	request, err := http.NewRequestWithContext(ctx, method, client.endpoint, body)
	if err != nil {
		return mcpRPCResponse{}, client.redactError(err)
	}
	client.applyHeaders(request)
	response, err := client.client.Do(request)
	if err != nil {
		return mcpRPCResponse{}, client.redactError(err)
	}
	defer response.Body.Close()
	if session := strings.TrimSpace(response.Header.Get("Mcp-Session-Id")); session != "" {
		client.mu.Lock()
		client.session = session
		client.mu.Unlock()
	}
	data, err := io.ReadAll(io.LimitReader(response.Body, maxMCPMessageBytes+1))
	if err != nil {
		return mcpRPCResponse{}, fmt.Errorf("读取 MCP HTTP 响应失败：%w", err)
	}
	if len(data) > maxMCPMessageBytes {
		return mcpRPCResponse{}, errors.New("MCP HTTP 响应超过大小上限")
	}
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		message := strings.TrimSpace(string(data))
		if message == "" {
			message = response.Status
		}
		return mcpRPCResponse{}, fmt.Errorf("MCP HTTP 返回 %d：%s", response.StatusCode, truncateRunes(message, 1_000))
	}
	if notification && len(bytes.TrimSpace(data)) == 0 {
		return mcpRPCResponse{}, nil
	}
	if notification && response.StatusCode == http.StatusAccepted {
		return mcpRPCResponse{}, nil
	}
	parsed, err := parseMCPHTTPResponse(data, response.Header.Get("Content-Type"), expectedID)
	if err != nil {
		if notification && len(bytes.TrimSpace(data)) == 0 {
			return mcpRPCResponse{}, nil
		}
		return mcpRPCResponse{}, err
	}
	return parsed, nil
}

func (client *httpMCPClient) applyHeaders(request *http.Request) {
	for key, value := range client.headers {
		request.Header.Set(key, value)
	}
	request.Header.Set("Accept", "application/json, text/event-stream")
	request.Header.Set("Content-Type", "application/json")
	request.Header.Set("MCP-Protocol-Version", mcpProtocolVersion)
	request.Header.Set("User-Agent", "Murong-Desktop-Agent/1.0")
	client.mu.Lock()
	session := client.session
	client.mu.Unlock()
	if session != "" {
		request.Header.Set("Mcp-Session-Id", session)
	}
}

func (client *httpMCPClient) redactError(err error) error {
	message := err.Error()
	for _, secret := range client.secrets {
		if secret != "" {
			message = strings.ReplaceAll(message, secret, "[REDACTED]")
		}
	}
	return errors.New("MCP HTTP 请求失败：" + truncateRunes(message, 1_500))
}

func parseMCPHTTPResponse(data []byte, contentType, expectedID string) (mcpRPCResponse, error) {
	data = bytes.TrimSpace(data)
	if len(data) == 0 {
		return mcpRPCResponse{}, errors.New("MCP HTTP 响应为空")
	}
	if strings.Contains(strings.ToLower(contentType), "text/event-stream") || bytes.HasPrefix(data, []byte("event:")) || bytes.HasPrefix(data, []byte("data:")) {
		return parseMCPSSEBody(data, expectedID)
	}
	return selectMCPRPCResponse(data, expectedID)
}

func parseMCPSSEBody(data []byte, expectedID string) (mcpRPCResponse, error) {
	scanner := bufio.NewScanner(bytes.NewReader(data))
	scanner.Buffer(make([]byte, 64<<10), maxMCPMessageBytes)
	dataLines := []string{}
	flush := func() (mcpRPCResponse, bool) {
		if len(dataLines) == 0 {
			return mcpRPCResponse{}, false
		}
		payload := []byte(strings.Join(dataLines, "\n"))
		dataLines = nil
		response, err := selectMCPRPCResponse(payload, expectedID)
		return response, err == nil
	}
	for scanner.Scan() {
		line := scanner.Text()
		if line == "" {
			if response, ok := flush(); ok {
				return response, nil
			}
			continue
		}
		if strings.HasPrefix(line, "data:") {
			dataLines = append(dataLines, strings.TrimSpace(strings.TrimPrefix(line, "data:")))
		}
	}
	if err := scanner.Err(); err != nil {
		return mcpRPCResponse{}, fmt.Errorf("解析 MCP SSE 响应失败：%w", err)
	}
	if response, ok := flush(); ok {
		return response, nil
	}
	return mcpRPCResponse{}, errors.New("MCP SSE 响应中没有匹配的 JSON-RPC 结果")
}

func selectMCPRPCResponse(data []byte, expectedID string) (mcpRPCResponse, error) {
	var messages []json.RawMessage
	if len(data) > 0 && data[0] == '[' {
		if err := json.Unmarshal(data, &messages); err != nil {
			return mcpRPCResponse{}, fmt.Errorf("MCP HTTP 响应不是有效 JSON：%w", err)
		}
	} else {
		messages = []json.RawMessage{append([]byte{}, data...)}
	}
	for _, message := range messages {
		var response mcpRPCResponse
		if err := json.Unmarshal(message, &response); err != nil {
			continue
		}
		id := strings.TrimSpace(string(response.ID))
		if response.JSONRPC == "2.0" && (expectedID == "" || id == expectedID || id == strconv.Quote(expectedID)) {
			return response, nil
		}
	}
	return mcpRPCResponse{}, errors.New("MCP HTTP 响应中没有匹配的 JSON-RPC 结果")
}

func cloneStringMap(values map[string]string) map[string]string {
	result := make(map[string]string, len(values))
	for key, value := range values {
		result[key] = value
	}
	return result
}
