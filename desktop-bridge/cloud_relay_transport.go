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

type cloudRelayTransport struct {
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
	pending  map[string]*cloudRelayPending
	writeMu  sync.Mutex
	replayMu sync.Mutex
	replay   cloudRelayReplayCache
}

type cloudRelayPending struct {
	requestID  string
	start      chan cloudRelayResponseStart
	frames     chan cloudRelayTunnelMessage
	done       chan struct{}
	reader     *io.PipeReader
	writer     *io.PipeWriter
	startOnce  sync.Once
	finishOnce sync.Once
	onFinish   func()
}

type cloudRelayResponseStart struct {
	status  int
	headers http.Header
	err     error
}

type cloudRelayResponseBody struct {
	reader    *io.PipeReader
	transport *cloudRelayTransport
	requestID string
	once      sync.Once
}

func newCloudRelayAPIClient(relayURL *url.URL, roomID string, secret []byte) (*apiClient, error) {
	transport, err := newCloudRelayTransport(relayURL, roomID, secret)
	if err != nil {
		return nil, err
	}
	base, _ := url.Parse("http://murong-cloud-relay.invalid")
	return &apiClient{
		base:       base,
		httpClient: &http.Client{Transport: transport},
		close:      transport.Close,
	}, nil
}

func newCloudRelayTransport(relayURL *url.URL, roomID string, secret []byte) (*cloudRelayTransport, error) {
	if relayURL == nil {
		return nil, errors.New("云中继地址不能为空")
	}
	if err := validateCloudRelayRoomID(roomID); err != nil {
		return nil, err
	}
	if len(secret) != cloudRelaySecretBytes {
		return nil, errors.New("云中继端到端密钥长度无效")
	}
	ctx, cancel := context.WithCancel(context.Background())
	return &cloudRelayTransport{
		relayURL: relayURL,
		roomID:   roomID,
		secret:   append([]byte(nil), secret...),
		context:  ctx,
		cancel:   cancel,
		pending:  make(map[string]*cloudRelayPending),
	}, nil
}

func (transport *cloudRelayTransport) RoundTrip(request *http.Request) (*http.Response, error) {
	if request == nil || request.URL == nil {
		return nil, errors.New("云中继请求无效")
	}
	if request.Method != http.MethodGet && request.Method != http.MethodPost {
		return nil, errors.New("云中继只允许 GET 和 POST")
	}
	if !strings.HasPrefix(request.URL.Path, "/api/v1/") {
		return nil, errors.New("云中继只转发 Murong API")
	}
	if err := transport.ensureConnected(request.Context()); err != nil {
		return nil, err
	}
	requestID := mustRandomID("relayreq")
	reader, writer := io.Pipe()
	pending := &cloudRelayPending{
		requestID: requestID,
		start:     make(chan cloudRelayResponseStart, 1),
		frames:    make(chan cloudRelayTunnelMessage, 64),
		done:      make(chan struct{}),
		reader:    reader,
		writer:    writer,
	}
	pending.onFinish = func() { transport.removePending(requestID, pending) }
	transport.mu.Lock()
	if transport.closed {
		transport.mu.Unlock()
		return nil, errors.New("云中继连接已关闭")
	}
	transport.pending[requestID] = pending
	transport.mu.Unlock()
	go pending.run()

	start := newCloudRelayTunnelMessage(requestID, "request_start")
	start.Method = request.Method
	start.Path = request.URL.EscapedPath()
	if start.Path == "" {
		start.Path = request.URL.Path
	}
	start.Headers = filterCloudRelayHeaders(request.Header, true)
	if err := transport.send(request.Context(), start); err != nil {
		pending.finish(err)
		return nil, err
	}
	if request.Body != nil {
		defer request.Body.Close()
		buffer := make([]byte, cloudRelayChunkBytes)
		total := 0
		for {
			count, readErr := request.Body.Read(buffer)
			if count > 0 {
				total += count
				if total > cloudRelayMaxBodyBytes {
					err := errors.New("云中继请求正文超过大小限制")
					pending.finish(err)
					return nil, err
				}
				chunk := newCloudRelayTunnelMessage(requestID, "request_chunk")
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
	if err := transport.send(request.Context(), newCloudRelayTunnelMessage(requestID, "request_end")); err != nil {
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
			Body: &cloudRelayResponseBody{
				reader: reader, transport: transport, requestID: requestID,
			},
			Request: request,
		}
		return response, nil
	case <-request.Context().Done():
		transport.cancelRequest(requestID, request.Context().Err())
		return nil, request.Context().Err()
	case <-transport.context.Done():
		transport.cancelRequest(requestID, errors.New("云中继连接已关闭"))
		return nil, errors.New("云中继连接已关闭")
	}
}

func (transport *cloudRelayTransport) ensureConnected(ctx context.Context) error {
	for {
		transport.mu.Lock()
		if transport.closed {
			transport.mu.Unlock()
			return errors.New("云中继连接已关闭")
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
				err = errors.New("云中继连接已关闭")
			}
			transport.dialErr = err
		}
		transport.dialing = nil
		close(wait)
		transport.mu.Unlock()
		return err
	}
}

func (transport *cloudRelayTransport) dial(ctx context.Context) (*websocket.Conn, error) {
	target := *transport.relayURL
	query := target.Query()
	query.Set("room", transport.roomID)
	query.Set("role", CloudRelayRoleDesktop)
	query.Set("v", "1")
	target.RawQuery = query.Encode()
	connection, response, err := websocket.Dial(ctx, target.String(), &websocket.DialOptions{
		Subprotocols:    []string{CloudRelaySubprotocol},
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
			return nil, fmt.Errorf("云中继握手失败（HTTP %d）：%w", response.StatusCode, err)
		}
		return nil, fmt.Errorf("无法连接云中继：%w", err)
	}
	if connection.Subprotocol() != CloudRelaySubprotocol {
		_ = connection.Close(websocket.StatusPolicyViolation, "subprotocol mismatch")
		return nil, errors.New("云中继协议协商失败")
	}
	connection.SetReadLimit(cloudRelayMaxFrameBytes)
	return connection, nil
}

func (transport *cloudRelayTransport) send(ctx context.Context, message cloudRelayTunnelMessage) error {
	payload, err := encryptCloudRelayMessage(transport.secret, transport.roomID, CloudRelayRoleDesktop, message)
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
		return errors.New("云中继尚未连接")
	}
	transport.writeMu.Lock()
	defer transport.writeMu.Unlock()
	writeContext, cancel := context.WithTimeout(ctx, 20*time.Second)
	err = connection.Write(writeContext, websocket.MessageBinary, payload)
	cancel()
	if err != nil {
		transport.failConnection(connection, err)
		return fmt.Errorf("云中继发送失败：%w", err)
	}
	return nil
}

func (transport *cloudRelayTransport) readLoop(connection *websocket.Conn) {
	for {
		messageType, payload, err := connection.Read(transport.context)
		if err != nil {
			transport.failConnection(connection, err)
			return
		}
		if messageType != websocket.MessageBinary {
			transport.failConnection(connection, errors.New("云中继返回了非加密数据"))
			return
		}
		message, err := decryptCloudRelayMessage(
			transport.secret, transport.roomID, CloudRelayRolePhone, payload, time.Now(),
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

func (transport *cloudRelayTransport) failConnection(connection *websocket.Conn, cause error) {
	transport.mu.Lock()
	if transport.conn != connection {
		transport.mu.Unlock()
		return
	}
	transport.conn = nil
	pending := make([]*cloudRelayPending, 0, len(transport.pending))
	for _, item := range transport.pending {
		pending = append(pending, item)
	}
	transport.pending = make(map[string]*cloudRelayPending)
	transport.mu.Unlock()
	_ = connection.Close(websocket.StatusInternalError, "relay connection lost")
	for _, item := range pending {
		item.finish(fmt.Errorf("云中继连接中断：%w", cause))
	}
}

func (transport *cloudRelayTransport) removePending(requestID string, pending *cloudRelayPending) {
	transport.mu.Lock()
	if transport.pending[requestID] == pending {
		delete(transport.pending, requestID)
	}
	transport.mu.Unlock()
}

func (transport *cloudRelayTransport) cancelRequest(requestID string, cause error) {
	transport.mu.Lock()
	pending := transport.pending[requestID]
	transport.mu.Unlock()
	if pending == nil {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	_ = transport.send(ctx, newCloudRelayTunnelMessage(requestID, "cancel"))
	cancel()
	pending.finish(cause)
}

func (transport *cloudRelayTransport) Close() {
	transport.mu.Lock()
	if transport.closed {
		transport.mu.Unlock()
		return
	}
	transport.closed = true
	connection := transport.conn
	transport.conn = nil
	pending := make([]*cloudRelayPending, 0, len(transport.pending))
	for _, item := range transport.pending {
		pending = append(pending, item)
	}
	transport.pending = make(map[string]*cloudRelayPending)
	transport.mu.Unlock()
	transport.cancel()
	if connection != nil {
		_ = connection.Close(websocket.StatusNormalClosure, "desktop node stopped")
	}
	for _, item := range pending {
		item.finish(errors.New("云中继连接已关闭"))
	}
	clearBytes(transport.secret)
}

func (transport *cloudRelayTransport) CloseIdleConnections() {
	transport.Close()
}

func (pending *cloudRelayPending) run() {
	for {
		select {
		case message := <-pending.frames:
			switch message.Kind {
			case "response_start":
				pending.publishStart(cloudRelayResponseStart{
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

func (pending *cloudRelayPending) publishStart(result cloudRelayResponseStart) {
	pending.startOnce.Do(func() { pending.start <- result })
}

func (pending *cloudRelayPending) finish(err error) {
	pending.finishOnce.Do(func() {
		if err != nil {
			pending.publishStart(cloudRelayResponseStart{err: err})
			_ = pending.writer.CloseWithError(err)
		} else {
			pending.publishStart(cloudRelayResponseStart{err: errors.New("云中继响应缺少状态")})
			_ = pending.writer.Close()
		}
		close(pending.done)
		if pending.onFinish != nil {
			pending.onFinish()
		}
	})
}

func (body *cloudRelayResponseBody) Read(buffer []byte) (int, error) {
	return body.reader.Read(buffer)
}

func (body *cloudRelayResponseBody) Close() error {
	body.once.Do(func() {
		body.transport.cancelRequest(body.requestID, context.Canceled)
	})
	return body.reader.Close()
}
