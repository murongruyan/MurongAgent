package desktopbridge

import (
	"bytes"
	"context"
	"encoding/base64"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/coder/websocket"
)

func TestDeviceTunnelTimeoutExplainsMissingPhoneWithoutVirtualURL(t *testing.T) {
	relayServer := httptest.NewServer(testDeviceTunnelServerHandler())
	defer relayServer.Close()
	relayURL, err := parseDeviceTunnelURL("ws" + strings.TrimPrefix(relayServer.URL, "http") + "/v2/tunnel")
	if err != nil {
		t.Fatal(err)
	}
	roomID, secret, err := generateDeviceTunnelRoom()
	if err != nil {
		t.Fatal(err)
	}
	defer clearBytes(secret)
	api, err := newDeviceTunnelAPIClient(relayURL, roomID, secret)
	if err != nil {
		t.Fatal(err)
	}
	defer api.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 150*time.Millisecond)
	defer cancel()
	_, pairErr := api.fetchPublicDeviceStatus(ctx)
	if pairErr == nil {
		t.Fatal("pairing unexpectedly succeeded without a phone peer")
	}
	message := pairErr.Error()
	if strings.Contains(message, "murong-device-tunnel.invalid") || !strings.Contains(message, "手机端未在 30 秒内响应") {
		t.Fatalf("unexpected cloud relay timeout: %q", message)
	}

	dialErr := api.translateRequestError(&url.Error{
		Op: "Post", URL: "http://murong-device-tunnel.invalid/api/v1/pair",
		Err: errors.New("无法连接加密隧道：WebSocket 握手失败"),
	})
	if strings.Contains(dialErr.Error(), "murong-device-tunnel.invalid") ||
		!strings.Contains(dialErr.Error(), "无法连接加密隧道") ||
		strings.Contains(dialErr.Error(), "手机端未在 30 秒内响应") {
		t.Fatalf("dial failure was not kept distinct: %q", dialErr)
	}
}

func TestDeviceTunnelCipherAndReplayProtection(t *testing.T) {
	roomID, secret, err := generateDeviceTunnelRoom()
	if err != nil {
		t.Fatal(err)
	}
	defer clearBytes(secret)
	message := newDeviceTunnelMessage("relayreq-12345678", "request_start")
	message.Method = http.MethodPost
	message.Path = "/api/v1/workspace/result"
	message.Headers = map[string][]string{"Authorization": {"Bearer top-secret-token"}}
	encoded, err := encryptDeviceTunnelMessage(secret, roomID, DeviceTunnelRoleDesktop, message)
	if err != nil {
		t.Fatal(err)
	}
	if bytes.Contains(encoded, []byte("top-secret-token")) || bytes.Contains(encoded, []byte("workspace/result")) {
		t.Fatal("relay frame exposed protected HTTP metadata")
	}
	decoded, err := decryptDeviceTunnelMessage(secret, roomID, DeviceTunnelRoleDesktop, encoded, time.Now())
	if err != nil || decoded.Path != message.Path || decoded.Headers["Authorization"][0] != "Bearer top-secret-token" {
		t.Fatalf("relay ciphertext did not round trip: decoded=%#v err=%v", decoded, err)
	}
	if _, err := decryptDeviceTunnelMessage(secret, roomID, DeviceTunnelRolePhone, encoded, time.Now()); err == nil {
		t.Fatal("direction-bound ciphertext was accepted for the wrong sender")
	}
	tampered := append([]byte(nil), encoded...)
	tampered[len(tampered)/2] ^= 1
	if _, err := decryptDeviceTunnelMessage(secret, roomID, DeviceTunnelRoleDesktop, tampered, time.Now()); err == nil {
		t.Fatal("tampered relay ciphertext was accepted")
	}
	cache := deviceTunnelReplayCache{}
	if !cache.claim(decoded.MessageID, decoded.IssuedAt, time.Now()) || cache.claim(decoded.MessageID, decoded.IssuedAt, time.Now()) {
		t.Fatal("relay replay cache did not reject a repeated message")
	}
}

func TestDeviceTunnelURLRequiresTLSOutsideLoopback(t *testing.T) {
	if OfficialDeviceTunnelURL != "wss://murongagent.rl1.cc/relay/v2/tunnel" {
		t.Fatalf("official relay URL = %q", OfficialDeviceTunnelURL)
	}
	for _, raw := range []string{"wss://relay.example.test/v2/tunnel", "ws://127.0.0.1:8787/v2/tunnel", "ws://localhost:8787/v2/tunnel"} {
		if _, err := parseDeviceTunnelURL(raw); err != nil {
			t.Fatalf("expected %q to be accepted: %v", raw, err)
		}
	}
	for _, raw := range []string{"http://relay.example.test", "ws://relay.example.test/v2/tunnel", "wss://user@relay.example.test", "wss://relay.example.test/v2/tunnel?token=x"} {
		if _, err := parseDeviceTunnelURL(raw); err == nil {
			t.Fatalf("expected %q to be rejected", raw)
		}
	}
}

func TestDeviceTunnelServerBuffersFirstFrameUntilPeerAttaches(t *testing.T) {
	relayServer := httptest.NewServer(testDeviceTunnelServerHandler())
	defer relayServer.Close()
	roomID, secret, err := generateDeviceTunnelRoom()
	if err != nil {
		t.Fatal(err)
	}
	defer clearBytes(secret)
	baseURL := "ws" + strings.TrimPrefix(relayServer.URL, "http") + "/v2/tunnel"
	dial := func(role string) *websocket.Conn {
		target, err := url.Parse(baseURL)
		if err != nil {
			t.Fatal(err)
		}
		query := target.Query()
		query.Set("room", roomID)
		query.Set("role", role)
		query.Set("v", "2")
		target.RawQuery = query.Encode()
		ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		defer cancel()
		connection, _, err := websocket.Dial(ctx, target.String(), &websocket.DialOptions{Subprotocols: []string{DeviceTunnelSubprotocol}})
		if err != nil {
			t.Fatal(err)
		}
		return connection
	}

	phone := dial(DeviceTunnelRolePhone)
	defer phone.Close(websocket.StatusNormalClosure, "")
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	payload := []byte("encrypted-first-frame")
	if err := phone.Write(ctx, websocket.MessageBinary, payload); err != nil {
		t.Fatal(err)
	}
	desktop := dial(DeviceTunnelRoleDesktop)
	defer desktop.Close(websocket.StatusNormalClosure, "")
	messageType, received, err := desktop.Read(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if messageType != websocket.MessageBinary || !bytes.Equal(received, payload) {
		t.Fatalf("buffered frame = %q (%v)", received, messageType)
	}
}

func TestDeviceTunnelEnvelopeRejectsTrailingJSON(t *testing.T) {
	roomID, secret, err := generateDeviceTunnelRoom()
	if err != nil {
		t.Fatal(err)
	}
	defer clearBytes(secret)
	message := newDeviceTunnelMessage("request-trailing-json", "request_end")
	encoded, err := encryptDeviceTunnelMessage(secret, roomID, DeviceTunnelRoleDesktop, message)
	if err != nil {
		t.Fatal(err)
	}
	encoded = append(encoded, []byte(` {}`)...)
	if _, err := decryptDeviceTunnelMessage(secret, roomID, DeviceTunnelRoleDesktop, encoded, time.Now()); err == nil {
		t.Fatal("trailing JSON was accepted")
	}
}

func TestDeviceTunnelServerAndEncryptedTransportCarryHTTPAndSSE(t *testing.T) {
	relayServer := httptest.NewServer(testDeviceTunnelServerHandler())
	defer relayServer.Close()
	relayURL, err := parseDeviceTunnelURL("ws" + strings.TrimPrefix(relayServer.URL, "http") + "/v2/tunnel")
	if err != nil {
		t.Fatal(err)
	}
	roomID, secret, err := generateDeviceTunnelRoom()
	if err != nil {
		t.Fatal(err)
	}
	defer clearBytes(secret)

	phoneHTTP := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		switch request.URL.Path {
		case "/api/v1/workspace/result":
			body, _ := io.ReadAll(request.Body)
			if request.Header.Get("Authorization") != "Bearer paired-token" {
				http.Error(writer, "unauthorized", http.StatusUnauthorized)
				return
			}
			writer.Header().Set("Content-Type", "application/json")
			writer.WriteHeader(http.StatusCreated)
			_, _ = writer.Write(append([]byte(`{"echo":`), append(body, '}')...))
		case "/api/v1/events":
			writer.Header().Set("Content-Type", "text/event-stream")
			writer.WriteHeader(http.StatusOK)
			flusher := writer.(http.Flusher)
			_, _ = io.WriteString(writer, "event: first\ndata: one\n\n")
			flusher.Flush()
			_, _ = io.WriteString(writer, "event: second\ndata: two\n\n")
		default:
			http.NotFound(writer, request)
		}
	}))
	defer phoneHTTP.Close()

	phoneContext, phoneCancel := context.WithCancel(context.Background())
	defer phoneCancel()
	phoneErrors := make(chan error, 1)
	go func() {
		phoneErrors <- runTestDeviceTunnelPhoneProxy(phoneContext, relayURL, roomID, secret, phoneHTTP.URL)
	}()

	transport, err := newDeviceTunnelTransport(relayURL, roomID, secret)
	if err != nil {
		t.Fatal(err)
	}
	defer transport.Close()
	client := &http.Client{Transport: transport, Timeout: 10 * time.Second}
	request, _ := http.NewRequest(http.MethodPost, "http://murong-device-tunnel.invalid/api/v1/workspace/result", strings.NewReader(`{"ok":true}`))
	request.Header.Set("Authorization", "Bearer paired-token")
	request.Header.Set("Content-Type", "application/json")
	response, err := client.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	body, err := io.ReadAll(response.Body)
	response.Body.Close()
	if err != nil || response.StatusCode != http.StatusCreated || string(body) != `{"echo":{"ok":true}}` {
		t.Fatalf("unexpected relayed response: status=%d body=%q err=%v", response.StatusCode, body, err)
	}

	eventsRequest, _ := http.NewRequest(http.MethodGet, "http://murong-device-tunnel.invalid/api/v1/events", nil)
	eventsRequest.Header.Set("Accept", "text/event-stream")
	eventsResponse, err := client.Do(eventsRequest)
	if err != nil {
		t.Fatal(err)
	}
	events, err := io.ReadAll(eventsResponse.Body)
	eventsResponse.Body.Close()
	if err != nil || string(events) != "event: first\ndata: one\n\nevent: second\ndata: two\n\n" {
		t.Fatalf("SSE was not streamed through relay: %q err=%v", events, err)
	}

	phoneCancel()
	select {
	case err := <-phoneErrors:
		if err != nil && !strings.Contains(err.Error(), "context canceled") {
			t.Fatal(err)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("test phone relay did not stop")
	}
}

type testRelayRequest struct {
	method  string
	path    string
	headers http.Header
	body    bytes.Buffer
}

func runTestDeviceTunnelPhoneProxy(ctx context.Context, relayURL *url.URL, roomID string, secret []byte, localURL string) error {
	target := *relayURL
	query := target.Query()
	query.Set("room", roomID)
	query.Set("role", DeviceTunnelRolePhone)
	query.Set("v", "2")
	target.RawQuery = query.Encode()
	connection, _, err := websocket.Dial(ctx, target.String(), &websocket.DialOptions{Subprotocols: []string{DeviceTunnelSubprotocol}})
	if err != nil {
		return err
	}
	defer connection.Close(websocket.StatusNormalClosure, "")
	connection.SetReadLimit(deviceTunnelMaxFrameBytes)
	requests := map[string]*testRelayRequest{}
	var writeMu sync.Mutex
	send := func(message deviceTunnelMessage) error {
		payload, err := encryptDeviceTunnelMessage(secret, roomID, DeviceTunnelRolePhone, message)
		if err != nil {
			return err
		}
		writeMu.Lock()
		defer writeMu.Unlock()
		return connection.Write(ctx, websocket.MessageBinary, payload)
	}
	for {
		messageType, payload, err := connection.Read(ctx)
		if err != nil {
			return err
		}
		if messageType != websocket.MessageBinary {
			continue
		}
		message, err := decryptDeviceTunnelMessage(secret, roomID, DeviceTunnelRoleDesktop, payload, time.Now())
		if err != nil {
			return err
		}
		switch message.Kind {
		case "request_start":
			requests[message.RequestID] = &testRelayRequest{method: message.Method, path: message.Path, headers: http.Header(message.Headers)}
		case "request_chunk":
			chunk, _ := base64.RawURLEncoding.DecodeString(message.Chunk)
			requests[message.RequestID].body.Write(chunk)
		case "request_end":
			incoming := requests[message.RequestID]
			delete(requests, message.RequestID)
			request, _ := http.NewRequestWithContext(ctx, incoming.method, localURL+incoming.path, bytes.NewReader(incoming.body.Bytes()))
			request.Header = incoming.headers
			response, err := http.DefaultClient.Do(request)
			if err != nil {
				failure := newDeviceTunnelMessage(message.RequestID, "error")
				failure.Error = err.Error()
				if err := send(failure); err != nil {
					return err
				}
				continue
			}
			start := newDeviceTunnelMessage(message.RequestID, "response_start")
			start.Status = response.StatusCode
			start.Headers = filterDeviceTunnelHeaders(response.Header, false)
			if err := send(start); err != nil {
				response.Body.Close()
				return err
			}
			buffer := make([]byte, deviceTunnelChunkBytes)
			for {
				count, readErr := response.Body.Read(buffer)
				if count > 0 {
					chunk := newDeviceTunnelMessage(message.RequestID, "response_chunk")
					chunk.Chunk = base64.RawURLEncoding.EncodeToString(buffer[:count])
					if err := send(chunk); err != nil {
						response.Body.Close()
						return err
					}
				}
				if readErr != nil {
					response.Body.Close()
					if readErr != io.EOF {
						return readErr
					}
					break
				}
			}
			if err := send(newDeviceTunnelMessage(message.RequestID, "response_end")); err != nil {
				return err
			}
		}
	}
}

// testDeviceTunnelServerHandler models the backend's opaque v2 frame forwarding for
// transport tests without embedding a second production server in the desktop client.
func testDeviceTunnelServerHandler() http.Handler {
	type peer struct {
		role string
		conn *websocket.Conn
		send chan []byte
	}
	type room struct {
		phone     *peer
		desktop   *peer
		toPhone   [][]byte
		toDesktop [][]byte
	}
	var mu sync.Mutex
	rooms := map[string]*room{}

	return http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		roomID := strings.TrimSpace(request.URL.Query().Get("room"))
		role := strings.TrimSpace(request.URL.Query().Get("role"))
		if request.URL.Path != "/v2/tunnel" || validateDeviceTunnelRoomID(roomID) != nil ||
			(role != DeviceTunnelRolePhone && role != DeviceTunnelRoleDesktop) || request.URL.Query().Get("v") != "2" {
			http.NotFound(writer, request)
			return
		}
		connection, err := websocket.Accept(writer, request, &websocket.AcceptOptions{
			Subprotocols:    []string{DeviceTunnelSubprotocol},
			CompressionMode: websocket.CompressionDisabled,
		})
		if err != nil {
			return
		}
		connection.SetReadLimit(deviceTunnelMaxFrameBytes)
		current := &peer{role: role, conn: connection, send: make(chan []byte, 32)}

		mu.Lock()
		currentRoom := rooms[roomID]
		if currentRoom == nil {
			currentRoom = &room{}
			rooms[roomID] = currentRoom
		}
		var pending *[][]byte
		if role == DeviceTunnelRolePhone {
			currentRoom.phone = current
			pending = &currentRoom.toPhone
		} else {
			currentRoom.desktop = current
			pending = &currentRoom.toDesktop
		}
		buffered := append([][]byte(nil), (*pending)...)
		*pending = nil
		mu.Unlock()

		ctx, cancel := context.WithCancel(request.Context())
		defer cancel()
		go func() {
			for _, payload := range buffered {
				if err := connection.Write(ctx, websocket.MessageBinary, payload); err != nil {
					cancel()
					return
				}
			}
			for {
				select {
				case <-ctx.Done():
					return
				case payload := <-current.send:
					if err := connection.Write(ctx, websocket.MessageBinary, payload); err != nil {
						cancel()
						return
					}
				}
			}
		}()

		defer func() {
			mu.Lock()
			if current.role == DeviceTunnelRolePhone && currentRoom.phone == current {
				currentRoom.phone = nil
			} else if current.role == DeviceTunnelRoleDesktop && currentRoom.desktop == current {
				currentRoom.desktop = nil
			}
			if currentRoom.phone == nil && currentRoom.desktop == nil {
				delete(rooms, roomID)
			}
			mu.Unlock()
			_ = connection.Close(websocket.StatusNormalClosure, "")
		}()

		for {
			messageType, payload, err := connection.Read(ctx)
			if err != nil {
				return
			}
			if messageType != websocket.MessageBinary || len(payload) == 0 || len(payload) > deviceTunnelMaxFrameBytes {
				return
			}
			copyOfPayload := append([]byte(nil), payload...)
			mu.Lock()
			target := currentRoom.phone
			waiting := &currentRoom.toPhone
			if current.role == DeviceTunnelRolePhone {
				target = currentRoom.desktop
				waiting = &currentRoom.toDesktop
			}
			if target == nil {
				*waiting = append(*waiting, copyOfPayload)
				mu.Unlock()
				continue
			}
			select {
			case target.send <- copyOfPayload:
				mu.Unlock()
			default:
				mu.Unlock()
				return
			}
		}
	})
}
