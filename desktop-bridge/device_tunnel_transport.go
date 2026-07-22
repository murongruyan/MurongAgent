package desktopbridge

import (
	"context"
	"encoding/base64"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"

	"github.com/coder/websocket"
)

type deviceTunnelTransport struct {
	relayURL *url.URL
	roomID   string
	secret   []byte

	context  context.Context
	cancel   context.CancelFunc
	mu       sync.Mutex
	conn     *websocket.Conn
	dialing  chan struct{}
	dialErr  error
	closed   bool
	pending  map[string]*deviceTunnelPending
	writeMu  sync.Mutex
	replayMu sync.Mutex
	replay   deviceTunnelReplayCache
}

var errDeviceTunnelPhoneResponseTimeout = errors.New("加密隧道已连接但手机端没有响应")

type deviceTunnelPending struct {
	requestID  string
	start      chan deviceTunnelResponseStart
	frames     chan deviceTunnelMessage
	done       chan struct{}
	reader     *io.PipeReader
	writer     *io.PipeWriter
	startOnce  sync.Once
	finishOnce sync.Once
	onFinish   func()
}

type deviceTunnelResponseStart struct {
	status  int
	headers http.Header
	err     error
}

type deviceTunnelResponseBody struct {
	reader    *io.PipeReader
	transport *deviceTunnelTransport
	requestID string
	once      sync.Once
}

func newDeviceTunnelAPIClient(relayURL *url.URL, roomID string, secret []byte) (*apiClient, error) {
	transport, err := newDeviceTunnelTransport(relayURL, roomID, secret)
	if err != nil {
		return nil, err
	}
	base, _ := url.Parse("http://murong-device-tunnel.invalid")
	return &apiClient{
		base:         base,
		httpClient:   &http.Client{Transport: transport},
		close:        transport.Close,
		deviceTunnel: true,
	}, nil
}

func newDeviceTunnelTransport(relayURL *url.URL, roomID string, secret []byte) (*deviceTunnelTransport, error) {
	if relayURL == nil {
		return nil, errors.New("加密隧道地址不能为空")
	}
	if err := validateDeviceTunnelRoomID(roomID); err != nil {
		return nil, err
	}
	if len(secret) != deviceTunnelSecretBytes {
		return nil, errors.New("加密隧道端到端密钥长度无效")
	}
	ctx, cancel := context.WithCancel(context.Background())
	return &deviceTunnelTransport{
		relayURL: relayURL,
		roomID:   roomID,
		secret:   append([]byte(nil), secret...),
		context:  ctx,
		cancel:   cancel,
		pending:  make(map[string]*deviceTunnelPending),
	}, nil
}

func (transport *deviceTunnelTransport) RoundTrip(request *http.Request) (*http.Response, error) {
	if request == nil || request.URL == nil {
		return nil, errors.New("加密隧道请求无效")
	}
	if request.Method != http.MethodGet && request.Method != http.MethodPost {
		return nil, errors.New("加密隧道只允许 GET 和 POST")
	}
	if !strings.HasPrefix(request.URL.Path, "/api/v1/") {
		return nil, errors.New("加密隧道只转发 Murong API")
	}
	if err := transport.ensureConnected(request.Context()); err != nil {
		return nil, err
	}
	requestID := mustRandomID("relayreq")
	reader, writer := io.Pipe()
	pending := &deviceTunnelPending{
		requestID: requestID,
		start:     make(chan deviceTunnelResponseStart, 1),
		frames:    make(chan deviceTunnelMessage, 64),
		done:      make(chan struct{}),
		reader:    reader,
		writer:    writer,
	}
	pending.onFinish = func() { transport.removePending(requestID, pending) }
	transport.mu.Lock()
	if transport.closed {
		transport.mu.Unlock()
		return nil, errors.New("加密隧道连接已关闭")
	}
	transport.pending[requestID] = pending
	transport.mu.Unlock()
	go pending.run()

	start := newDeviceTunnelMessage(requestID, "request_start")
	start.Method = request.Method
	start.Path = request.URL.EscapedPath()
	if start.Path == "" {
		start.Path = request.URL.Path
	}
	start.Headers = filterDeviceTunnelHeaders(request.Header, true)
	if err := transport.send(request.Context(), start); err != nil {
		pending.finish(err)
		return nil, err
	}
	if request.Body != nil {
		defer request.Body.Close()
		buffer := make([]byte, deviceTunnelChunkBytes)
		total := 0
		for {
			count, readErr := request.Body.Read(buffer)
			if count > 0 {
				total += count
				if total > deviceTunnelMaxBodyBytes {
					err := errors.New("加密隧道请求正文超过大小限制")
					pending.finish(err)
					return nil, err
				}
				chunk := newDeviceTunnelMessage(requestID, "request_chunk")
				chunk.Chunk = base64.RawURLEncoding.EncodeToString(buffer[:count])
				if err := transport.send(request.Context(), chunk); err != nil {
					pending.finish(err)
					return nil, err
				}
			}
			if readErr != nil {
				if !errors.Is(readErr, io.EOF) {
					pending.finish(readErr)
					return nil, readErr
				}
				break
			}
		}
	}
	if err := transport.send(request.Context(), newDeviceTunnelMessage(requestID, "request_end")); err != nil {
		pending.finish(err)
		return nil, err
	}

	select {
	case result := <-pending.start:
		if result.err != nil {
			return nil, result.err
		}
		response := &http.Response{
			StatusCode: result.status,
			Status:     fmt.Sprintf("%d %s", result.status, http.StatusText(result.status)),
			Header:     result.headers,
			Body: &deviceTunnelResponseBody{
				reader: reader, transport: transport, requestID: requestID,
			},
			Request: request,
		}
		return response, nil
	case <-request.Context().Done():
		cause := request.Context().Err()
		if errors.Is(cause, context.DeadlineExceeded) {
			cause = errors.Join(errDeviceTunnelPhoneResponseTimeout, cause)
		}
		transport.cancelRequest(requestID, cause)
		return nil, cause
	case <-transport.context.Done():
		transport.cancelRequest(requestID, errors.New("加密隧道连接已关闭"))
		return nil, errors.New("加密隧道连接已关闭")
	}
}

func (transport *deviceTunnelTransport) ensureConnected(ctx context.Context) error {
	for {
		transport.mu.Lock()
		if transport.closed {
			transport.mu.Unlock()
			return errors.New("加密隧道连接已关闭")
		}
		if transport.conn != nil {
			transport.mu.Unlock()
			return nil
		}
		if transport.dialing != nil {
			wait := transport.dialing
			transport.mu.Unlock()
			select {
			case <-wait:
				transport.mu.Lock()
				err := transport.dialErr
				transport.mu.Unlock()
				if err != nil {
					return err
				}
				continue
			case <-ctx.Done():
				return ctx.Err()
			}
		}
		wait := make(chan struct{})
		transport.dialing = wait
		transport.mu.Unlock()

		dialContext, cancel := context.WithTimeout(ctx, 20*time.Second)
		connection, err := transport.dial(dialContext)
		cancel()
		transport.mu.Lock()
		if err == nil && !transport.closed {
			transport.conn = connection
			transport.dialErr = nil
			go transport.readLoop(connection)
		} else {
			if connection != nil {
				_ = connection.Close(websocket.StatusNormalClosure, "")
			}
			if err == nil {
				err = errors.New("加密隧道连接已关闭")
			}
			transport.dialErr = err
		}
		transport.dialing = nil
		close(wait)
		transport.mu.Unlock()
		return err
	}
}

func (transport *deviceTunnelTransport) dial(ctx context.Context) (*websocket.Conn, error) {
	target := *transport.relayURL
	query := target.Query()
	query.Set("room", transport.roomID)
	query.Set("role", DeviceTunnelRoleDesktop)
	query.Set("v", "2")
	target.RawQuery = query.Encode()
	connection, response, err := websocket.Dial(ctx, target.String(), &websocket.DialOptions{
		Subprotocols:    []string{DeviceTunnelSubprotocol},
		CompressionMode: websocket.CompressionDisabled,
		HTTPClient: &http.Client{Transport: &http.Transport{
			Proxy:                 http.ProxyFromEnvironment,
			MaxIdleConns:          4,
			IdleConnTimeout:       60 * time.Second,
			ResponseHeaderTimeout: 20 * time.Second,
		}},
	})
	if err != nil {
		if response != nil {
			return nil, fmt.Errorf("加密隧道握手失败（HTTP %d）：%w", response.StatusCode, err)
		}
		return nil, fmt.Errorf("无法连接加密隧道：%w", err)
	}
	if connection.Subprotocol() != DeviceTunnelSubprotocol {
		_ = connection.Close(websocket.StatusPolicyViolation, "subprotocol mismatch")
		return nil, errors.New("加密隧道协议协商失败")
	}
	connection.SetReadLimit(deviceTunnelMaxFrameBytes)
	return connection, nil
}

func (transport *deviceTunnelTransport) send(ctx context.Context, message deviceTunnelMessage) error {
	payload, err := encryptDeviceTunnelMessage(transport.secret, transport.roomID, DeviceTunnelRoleDesktop, message)
	if err != nil {
		return err
	}
	if err := transport.ensureConnected(ctx); err != nil {
		return err
	}
	transport.mu.Lock()
	connection := transport.conn
	transport.mu.Unlock()
	if connection == nil {
		return errors.New("加密隧道尚未连接")
	}
	transport.writeMu.Lock()
	defer transport.writeMu.Unlock()
	writeContext, cancel := context.WithTimeout(ctx, 20*time.Second)
	err = connection.Write(writeContext, websocket.MessageBinary, payload)
	cancel()
	if err != nil {
		transport.failConnection(connection, err)
		return fmt.Errorf("加密隧道发送失败：%w", err)
	}
	return nil
}

func (transport *deviceTunnelTransport) readLoop(connection *websocket.Conn) {
	for {
		messageType, payload, err := connection.Read(transport.context)
		if err != nil {
			transport.failConnection(connection, err)
			return
		}
		if messageType != websocket.MessageBinary {
			transport.failConnection(connection, errors.New("加密隧道返回了非加密数据"))
			return
		}
		message, err := decryptDeviceTunnelMessage(
			transport.secret, transport.roomID, DeviceTunnelRolePhone, payload, time.Now(),
		)
		if err != nil {
			transport.failConnection(connection, err)
			return
		}
		transport.replayMu.Lock()
		claimed := transport.replay.claim(message.MessageID, message.IssuedAt, time.Now())
		transport.replayMu.Unlock()
		if !claimed {
			continue
		}
		transport.mu.Lock()
		pending := transport.pending[message.RequestID]
		transport.mu.Unlock()
		if pending == nil {
			continue
		}
		select {
		case pending.frames <- message:
		case <-pending.done:
		case <-transport.context.Done():
			return
		}
	}
}

func (transport *deviceTunnelTransport) failConnection(connection *websocket.Conn, cause error) {
	transport.mu.Lock()
	if transport.conn != connection {
		transport.mu.Unlock()
		return
	}
	transport.conn = nil
	pending := make([]*deviceTunnelPending, 0, len(transport.pending))
	for _, item := range transport.pending {
		pending = append(pending, item)
	}
	transport.pending = make(map[string]*deviceTunnelPending)
	transport.mu.Unlock()
	_ = connection.Close(websocket.StatusInternalError, "relay connection lost")
	for _, item := range pending {
		item.finish(fmt.Errorf("加密隧道连接中断：%w", cause))
	}
}

func (transport *deviceTunnelTransport) removePending(requestID string, pending *deviceTunnelPending) {
	transport.mu.Lock()
	if transport.pending[requestID] == pending {
		delete(transport.pending, requestID)
	}
	transport.mu.Unlock()
}

func (transport *deviceTunnelTransport) cancelRequest(requestID string, cause error) {
	transport.mu.Lock()
	pending := transport.pending[requestID]
	transport.mu.Unlock()
	if pending == nil {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	_ = transport.send(ctx, newDeviceTunnelMessage(requestID, "cancel"))
	cancel()
	pending.finish(cause)
}

func (transport *deviceTunnelTransport) Close() {
	transport.mu.Lock()
	if transport.closed {
		transport.mu.Unlock()
		return
	}
	transport.closed = true
	connection := transport.conn
	transport.conn = nil
	pending := make([]*deviceTunnelPending, 0, len(transport.pending))
	for _, item := range transport.pending {
		pending = append(pending, item)
	}
	transport.pending = make(map[string]*deviceTunnelPending)
	transport.mu.Unlock()
	transport.cancel()
	if connection != nil {
		_ = connection.Close(websocket.StatusNormalClosure, "desktop node stopped")
	}
	for _, item := range pending {
		item.finish(errors.New("加密隧道连接已关闭"))
	}
	clearBytes(transport.secret)
}

func (transport *deviceTunnelTransport) CloseIdleConnections() {
	transport.Close()
}

func (pending *deviceTunnelPending) run() {
	for {
		select {
		case message := <-pending.frames:
			switch message.Kind {
			case "response_start":
				pending.publishStart(deviceTunnelResponseStart{
					status: message.Status, headers: http.Header(message.Headers),
				})
			case "response_chunk":
				chunk, err := base64.RawURLEncoding.DecodeString(message.Chunk)
				if err != nil {
					pending.finish(err)
					return
				}
				if _, err := pending.writer.Write(chunk); err != nil {
					pending.finish(err)
					return
				}
			case "response_end":
				pending.finish(nil)
				return
			case "error":
				pending.finish(errors.New(message.Error))
				return
			}
		case <-pending.done:
			return
		}
	}
}

func (pending *deviceTunnelPending) publishStart(result deviceTunnelResponseStart) {
	pending.startOnce.Do(func() { pending.start <- result })
}

func (pending *deviceTunnelPending) finish(err error) {
	pending.finishOnce.Do(func() {
		if err != nil {
			pending.publishStart(deviceTunnelResponseStart{err: err})
			_ = pending.writer.CloseWithError(err)
		} else {
			pending.publishStart(deviceTunnelResponseStart{err: errors.New("加密隧道响应缺少状态")})
			_ = pending.writer.Close()
		}
		close(pending.done)
		if pending.onFinish != nil {
			pending.onFinish()
		}
	})
}

func (body *deviceTunnelResponseBody) Read(buffer []byte) (int, error) {
	return body.reader.Read(buffer)
}

func (body *deviceTunnelResponseBody) Close() error {
	body.once.Do(func() {
		body.transport.cancelRequest(body.requestID, context.Canceled)
	})
	return body.reader.Close()
}
