package desktopbridge

import (
	"bytes"
	"context"
	"encoding/base64"
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

func TestCloudRelayShareCodeCipherAndReplayProtection(t *testing.T) {
	code, roomID, secret, err := generateCloudRelayShareCode()
	if err != nil {
		t.Fatal(err)
	}
	defer clearBytes(secret)
	parsedRoom, parsedSecret, err := parseCloudRelayShareCode(code)
	if err != nil {
		t.Fatal(err)
	}
	defer clearBytes(parsedSecret)
	if parsedRoom != roomID || !bytes.Equal(secret, parsedSecret) {
		t.Fatal("cloud relay share code did not round trip")
	}

	message := newCloudRelayTunnelMessage("relayreq-12345678", "request_start")
	message.Method = http.MethodPost
	message.Path = "/api/v1/workspace/result"
	message.Headers = map[string][]string{"Authorization": {"Bearer top-secret-token"}}
	encoded, err := encryptCloudRelayMessage(secret, roomID, CloudRelayRoleDesktop, message)
	if err != nil {
		t.Fatal(err)
	}
	if bytes.Contains(encoded, []byte("top-secret-token")) || bytes.Contains(encoded, []byte("workspace/result")) {
		t.Fatal("relay frame exposed protected HTTP metadata")
	}
	decoded, err := decryptCloudRelayMessage(secret, roomID, CloudRelayRoleDesktop, encoded, time.Now())
	if err != nil || decoded.Path != message.Path || decoded.Headers["Authorization"][0] != "Bearer top-secret-token" {
		t.Fatalf("relay ciphertext did not round trip: decoded=%#v err=%v", decoded, err)
	}
	if _, err := decryptCloudRelayMessage(secret, roomID, CloudRelayRolePhone, encoded, time.Now()); err == nil {
		t.Fatal("direction-bound ciphertext was accepted for the wrong sender")
	}
	tampered := append([]byte(nil), encoded...)
	tampered[len(tampered)/2] ^= 1
	if _, err := decryptCloudRelayMessage(secret, roomID, CloudRelayRoleDesktop, tampered, time.Now()); err == nil {
		t.Fatal("tampered relay ciphertext was accepted")
	}
	cache := cloudRelayReplayCache{}
	if !cache.claim(decoded.MessageID, decoded.IssuedAt, time.Now()) || cache.claim(decoded.MessageID, decoded.IssuedAt, time.Now()) {
		t.Fatal("relay replay cache did not reject a repeated message")
	}
}

func TestCloudRelayURLRequiresTLSOutsideLoopback(t *testing.T) {
	if OfficialCloudRelayURL != "wss://murongagent.rl1.cc/relay/v1/connect" {
		t.Fatalf("official relay URL = %q", OfficialCloudRelayURL)
	}
	for _, raw := range []string{"wss://relay.example.test/v1/connect", "ws://127.0.0.1:8787/v1/connect", "ws://localhost:8787/v1/connect"} {
		if _, err := parseCloudRelayURL(raw); err != nil {
			t.Fatalf("expected %q to be accepted: %v", raw, err)
		}
	}
	for _, raw := range []string{"http://relay.example.test", "ws://relay.example.test/v1/connect", "wss://user@relay.example.test", "wss://relay.example.test/v1/connect?token=x"} {
		if _, err := parseCloudRelayURL(raw); err == nil {
			t.Fatalf("expected %q to be rejected", raw)
		}
	}
}

func TestCloudRelayServerBuffersFirstFrameUntilPeerAttaches(t *testing.T) {
	relayServer := httptest.NewServer(NewCloudRelayServer(8).Handler())
	defer relayServer.Close()
	_, roomID, secret, err := generateCloudRelayShareCode()
	if err != nil {
		t.Fatal(err)
	}
	defer clearBytes(secret)
	baseURL := "ws" + strings.TrimPrefix(relayServer.URL, "http") + "/v1/connect"
	dial := func(role string) *websocket.Conn {
		target, err := url.Parse(baseURL)
		if err != nil {
			t.Fatal(err)
		}
		query := target.Query()
		query.Set("room", roomID)
		query.Set("role", role)
		query.Set("v", "1")
		target.RawQuery = query.Encode()
		ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		defer cancel()
		connection, _, err := websocket.Dial(ctx, target.String(), &websocket.DialOptions{Subprotocols: []string{CloudRelaySubprotocol}})
		if err != nil {
			t.Fatal(err)
		}
		return connection
	}

	phone := dial(CloudRelayRolePhone)
	defer phone.Close(websocket.StatusNormalClosure, "")
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	payload := []byte("encrypted-first-frame")
	if err := phone.Write(ctx, websocket.MessageBinary, payload); err != nil {
		t.Fatal(err)
	}
	desktop := dial(CloudRelayRoleDesktop)
	defer desktop.Close(websocket.StatusNormalClosure, "")
	messageType, received, err := desktop.Read(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if messageType != websocket.MessageBinary || !bytes.Equal(received, payload) {
		t.Fatalf("buffered frame = %q (%v)", received, messageType)
	}
}

func TestCloudRelayEnvelopeRejectsTrailingJSON(t *testing.T) {
	_, roomID, secret, err := generateCloudRelayShareCode()
	if err != nil {
		t.Fatal(err)
	}
	defer clearBytes(secret)
	message := newCloudRelayTunnelMessage("request-trailing-json", "request_end")
	encoded, err := encryptCloudRelayMessage(secret, roomID, CloudRelayRoleDesktop, message)
	if err != nil {
		t.Fatal(err)
	}
	encoded = append(encoded, []byte(` {}`)...)
	if _, err := decryptCloudRelayMessage(secret, roomID, CloudRelayRoleDesktop, encoded, time.Now()); err == nil {
		t.Fatal("trailing JSON was accepted")
	}
}

func TestCloudRelayServerAndEncryptedTransportCarryHTTPAndSSE(t *testing.T) {
	relayServer := httptest.NewServer(NewCloudRelayServer(8).Handler())
	defer relayServer.Close()
	relayURL, err := parseCloudRelayURL("ws" + strings.TrimPrefix(relayServer.URL, "http") + "/v1/connect")
	if err != nil {
		t.Fatal(err)
	}
	_, roomID, secret, err := generateCloudRelayShareCode()
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
		phoneErrors <- runTestCloudRelayPhoneProxy(phoneContext, relayURL, roomID, secret, phoneHTTP.URL)
	}()

	transport, err := newCloudRelayTransport(relayURL, roomID, secret)
	if err != nil {
		t.Fatal(err)
	}
	defer transport.Close()
	client := &http.Client{Transport: transport, Timeout: 10 * time.Second}
	request, _ := http.NewRequest(http.MethodPost, "http://murong-cloud-relay.invalid/api/v1/workspace/result", strings.NewReader(`{"ok":true}`))
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

	eventsRequest, _ := http.NewRequest(http.MethodGet, "http://murong-cloud-relay.invalid/api/v1/events", nil)
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

func runTestCloudRelayPhoneProxy(ctx context.Context, relayURL *url.URL, roomID string, secret []byte, localURL string) error {
	target := *relayURL
	query := target.Query()
	query.Set("room", roomID)
	query.Set("role", CloudRelayRolePhone)
	query.Set("v", "1")
	target.RawQuery = query.Encode()
	connection, _, err := websocket.Dial(ctx, target.String(), &websocket.DialOptions{Subprotocols: []string{CloudRelaySubprotocol}})
	if err != nil {
		return err
	}
	defer connection.Close(websocket.StatusNormalClosure, "")
	connection.SetReadLimit(cloudRelayMaxFrameBytes)
	requests := map[string]*testRelayRequest{}
	var writeMu sync.Mutex
	send := func(message cloudRelayTunnelMessage) error {
		payload, err := encryptCloudRelayMessage(secret, roomID, CloudRelayRolePhone, message)
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
		message, err := decryptCloudRelayMessage(secret, roomID, CloudRelayRoleDesktop, payload, time.Now())
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
				failure := newCloudRelayTunnelMessage(message.RequestID, "error")
				failure.Error = err.Error()
				if err := send(failure); err != nil {
					return err
				}
				continue
			}
			start := newCloudRelayTunnelMessage(message.RequestID, "response_start")
			start.Status = response.StatusCode
			start.Headers = filterCloudRelayHeaders(response.Header, false)
			if err := send(start); err != nil {
				response.Body.Close()
				return err
			}
			buffer := make([]byte, cloudRelayChunkBytes)
			for {
				count, readErr := response.Body.Read(buffer)
				if count > 0 {
					chunk := newCloudRelayTunnelMessage(message.RequestID, "response_chunk")
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
			if err := send(newCloudRelayTunnelMessage(message.RequestID, "response_end")); err != nil {
				return err
			}
		}
	}
}
