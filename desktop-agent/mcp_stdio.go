package main

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
)

type stdioMCPClient struct {
	command *exec.Cmd
	stdin   io.WriteCloser

	writeMu sync.Mutex
	mu      sync.Mutex
	pending map[string]chan mcpRPCResponse
	nextID  atomic.Uint64
	done    chan struct{}
	finish  sync.Once
	err     error
	stderr  *boundedMCPLog
	secrets []string
}

type boundedMCPLog struct {
	mu    sync.Mutex
	value []byte
	limit int
}

func newStdioMCPClient(config mcpRuntimeConfig) (*stdioMCPClient, error) {
	if config.Command == "" {
		return nil, errors.New("MCP stdio 启动命令为空")
	}
	command := exec.Command(config.Command, config.Args...)
	command.Dir = config.Cwd
	command.Env = append([]string{}, os.Environ()...)
	for key, value := range config.Environment {
		command.Env = append(command.Env, key+"="+value)
	}
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
	client := &stdioMCPClient{
		command: command, stdin: stdin, pending: map[string]chan mcpRPCResponse{},
		done: make(chan struct{}), stderr: &boundedMCPLog{limit: 32 << 10},
		secrets: mcpRuntimeSecretValues(config),
	}
	if err := command.Start(); err != nil {
		return nil, fmt.Errorf("启动 MCP stdio 进程失败：%w", err)
	}
	go func() {
		_, _ = io.Copy(client.stderr, stderr)
	}()
	go client.readLoop(stdout)
	go func() {
		err := command.Wait()
		if err == nil {
			err = errors.New("MCP stdio 进程已退出")
		}
		client.complete(fmt.Errorf("%w%s", err, client.stderrDiagnostic()))
	}()
	return client, nil
}

func (client *stdioMCPClient) Request(ctx context.Context, method string, params any, result any) error {
	id := client.nextID.Add(1)
	idKey := strconv.FormatUint(id, 10)
	responseChannel := make(chan mcpRPCResponse, 1)
	client.mu.Lock()
	select {
	case <-client.done:
		err := client.err
		client.mu.Unlock()
		if err == nil {
			err = errors.New("MCP stdio 连接已关闭")
		}
		return err
	default:
		client.pending[idKey] = responseChannel
		client.mu.Unlock()
	}
	if err := client.writeMessage(map[string]any{"jsonrpc": "2.0", "id": id, "method": method, "params": params}); err != nil {
		client.removePending(idKey)
		return err
	}
	select {
	case response := <-responseChannel:
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
			return fmt.Errorf("MCP 响应无法解析：%w", err)
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
			err = errors.New("MCP stdio 连接已关闭")
		}
		return err
	}
}

func (client *stdioMCPClient) Notify(ctx context.Context, method string, params any) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	return client.writeMessage(map[string]any{"jsonrpc": "2.0", "method": method, "params": params})
}

func (client *stdioMCPClient) Close() error {
	client.complete(errors.New("MCP stdio 连接已关闭"))
	_ = client.stdin.Close()
	if client.command.Process != nil {
		_ = client.command.Process.Kill()
	}
	return nil
}

func (client *stdioMCPClient) readLoop(stdout io.Reader) {
	reader := bufio.NewReaderSize(stdout, 64<<10)
	for {
		line, err := reader.ReadBytes('\n')
		if len(line) > 0 {
			if len(line) > maxMCPMessageBytes {
				client.complete(errors.New("MCP stdio 消息超过大小上限"))
				return
			}
			if parseErr := client.handleLine(bytes.TrimSpace(line)); parseErr != nil {
				client.complete(parseErr)
				return
			}
		}
		if err != nil {
			if !errors.Is(err, io.EOF) {
				client.complete(fmt.Errorf("读取 MCP stdio 输出失败：%w", err))
			}
			return
		}
	}
}

func (client *stdioMCPClient) handleLine(line []byte) error {
	if len(line) == 0 {
		return nil
	}
	var envelope struct {
		JSONRPC string          `json:"jsonrpc"`
		ID      json.RawMessage `json:"id,omitempty"`
		Method  string          `json:"method,omitempty"`
		Result  json.RawMessage `json:"result,omitempty"`
		Error   *mcpRPCError    `json:"error,omitempty"`
	}
	if err := json.Unmarshal(line, &envelope); err != nil {
		return fmt.Errorf("MCP stdio 输出不是有效 JSON-RPC：%w", err)
	}
	if envelope.JSONRPC != "2.0" {
		return errors.New("MCP stdio 输出缺少 jsonrpc=2.0")
	}
	if envelope.Method != "" {
		if len(envelope.ID) > 0 && string(envelope.ID) != "null" {
			return client.writeMessage(map[string]any{
				"jsonrpc": "2.0", "id": json.RawMessage(envelope.ID),
				"error": map[string]any{"code": -32601, "message": "Murong client does not support server requests"},
			})
		}
		return nil
	}
	idKey := strings.TrimSpace(string(envelope.ID))
	if idKey == "" || idKey == "null" {
		return nil
	}
	client.mu.Lock()
	channel := client.pending[idKey]
	delete(client.pending, idKey)
	client.mu.Unlock()
	if channel != nil {
		channel <- mcpRPCResponse{JSONRPC: envelope.JSONRPC, ID: envelope.ID, Result: envelope.Result, Error: envelope.Error}
	}
	return nil
}

func (client *stdioMCPClient) writeMessage(message any) error {
	data, err := json.Marshal(message)
	if err != nil {
		return err
	}
	if len(data) > maxMCPMessageBytes {
		return errors.New("MCP stdio 请求超过大小上限")
	}
	data = append(data, '\n')
	client.writeMu.Lock()
	defer client.writeMu.Unlock()
	select {
	case <-client.done:
		return errors.New("MCP stdio 连接已关闭")
	default:
	}
	if _, err := client.stdin.Write(data); err != nil {
		return fmt.Errorf("写入 MCP stdio 失败：%w", err)
	}
	return nil
}

func (client *stdioMCPClient) removePending(id string) {
	client.mu.Lock()
	delete(client.pending, id)
	client.mu.Unlock()
}

func (client *stdioMCPClient) complete(err error) {
	client.finish.Do(func() {
		client.mu.Lock()
		client.err = err
		client.pending = map[string]chan mcpRPCResponse{}
		close(client.done)
		client.mu.Unlock()
	})
}

func (client *stdioMCPClient) stderrDiagnostic() string {
	text := strings.TrimSpace(client.stderr.String())
	if text == "" {
		return ""
	}
	for _, secret := range client.secrets {
		if secret != "" {
			text = strings.ReplaceAll(text, secret, "[REDACTED]")
		}
	}
	return "; stderr: " + truncateRunes(text, 2_000)
}

func (log *boundedMCPLog) Write(value []byte) (int, error) {
	log.mu.Lock()
	defer log.mu.Unlock()
	originalLength := len(value)
	log.value = append(log.value, value...)
	if len(log.value) > log.limit {
		log.value = append([]byte{}, log.value[len(log.value)-log.limit:]...)
	}
	return originalLength, nil
}

func (log *boundedMCPLog) String() string {
	log.mu.Lock()
	defer log.mu.Unlock()
	return string(append([]byte{}, log.value...))
}
