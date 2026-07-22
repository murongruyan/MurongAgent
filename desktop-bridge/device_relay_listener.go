package desktopbridge

import (
	"context"
	"errors"
	"fmt"
	"net/url"
	"os"
	"strings"
	"sync"
	"time"

	"github.com/coder/websocket"
)

type deviceRelayIncomingRequest struct {
	message     deviceRelayMessage
	fingerprint string
}

type deviceRelayListener struct {
	identity  *deviceIdentity
	relayURL  *url.URL
	onRequest func(deviceRelayIncomingRequest)
	onState   func(bool)
	auth      *desktopPairingAuthenticator

	mu       sync.Mutex
	sendMu   sync.Mutex
	socket   *websocket.Conn
	cancel   context.CancelFunc
	done     chan struct{}
	starting bool
}

func newDeviceRelayListener(
	identity *deviceIdentity,
	relayURL string,
	auth *desktopPairingAuthenticator,
	onRequest func(deviceRelayIncomingRequest),
	onState func(bool),
) (*deviceRelayListener, error) {
	if identity == nil {
		return nil, errors.New("电脑设备身份不可用")
	}
	parsed, err := parseDeviceRelayURL(relayURL)
	if err != nil {
		return nil, err
	}
	return &deviceRelayListener{identity: identity, relayURL: parsed, auth: auth, onRequest: onRequest, onState: onState}, nil
}

func (listener *deviceRelayListener) Start() {
	listener.mu.Lock()
	if listener.cancel != nil || listener.starting {
		listener.mu.Unlock()
		return
	}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	listener.cancel = cancel
	listener.done = done
	listener.starting = true
	listener.mu.Unlock()
	go listener.run(ctx, done)
}

func (listener *deviceRelayListener) Close(timeout time.Duration) {
	listener.mu.Lock()
	cancel := listener.cancel
	done := listener.done
	socket := listener.socket
	listener.mu.Unlock()
	if cancel != nil {
		cancel()
	}
	if socket != nil {
		_ = socket.Close(websocket.StatusNormalClosure, "desktop listener stopped")
	}
	if timeout > 0 && done != nil {
		select {
		case <-done:
		case <-time.After(timeout):
		}
	}
}

func (listener *deviceRelayListener) run(ctx context.Context, done chan struct{}) {
	defer close(done)
	listener.mu.Lock()
	listener.starting = false
	listener.mu.Unlock()
	backoff := time.Second
	for ctx.Err() == nil {
		err := listener.connectOnce(ctx)
		listener.setOnline(false)
		if ctx.Err() != nil {
			break
		}
		if err == nil {
			backoff = time.Second
		}
		timer := time.NewTimer(backoff)
		select {
		case <-ctx.Done():
			timer.Stop()
		case <-timer.C:
		}
		if backoff < 20*time.Second {
			backoff *= 2
		}
	}
	listener.mu.Lock()
	listener.cancel = nil
	listener.done = nil
	listener.socket = nil
	listener.mu.Unlock()
}

func (listener *deviceRelayListener) connectOnce(ctx context.Context) error {
	dialCtx, cancel := context.WithTimeout(ctx, 20*time.Second)
	connection, response, err := websocket.Dial(dialCtx, listener.relayURL.String(), &websocket.DialOptions{
		Subprotocols: []string{deviceRelaySubprotocol},
	})
	cancel()
	if err != nil {
		if response != nil {
			return fmt.Errorf("设备服务返回 HTTP %d：%w", response.StatusCode, err)
		}
		return err
	}
	defer connection.Close(websocket.StatusNormalClosure, "")
	connection.SetReadLimit(deviceRelayMaxFrameBytes)
	listener.mu.Lock()
	listener.socket = connection
	listener.mu.Unlock()
	defer func() {
		listener.mu.Lock()
		if listener.socket == connection {
			listener.socket = nil
		}
		listener.mu.Unlock()
	}()

	nonce, err := randomBase64URL(16)
	if err != nil {
		return err
	}
	registration := deviceRelayMessage{
		Version: deviceRelayProtocolVersion, Kind: "register", DeviceID: listener.identity.deviceID,
		DevicePublicKey: listener.identity.publicKey(), Platform: deviceRelayPlatformDesktop,
		Role: "listener", IssuedAt: time.Now().UnixMilli(), Nonce: nonce,
	}
	registration.Signature, err = listener.identity.sign(deviceRelayRegistrationPayload(registration))
	if err != nil {
		return err
	}
	registerCtx, registerCancel := context.WithTimeout(ctx, 15*time.Second)
	err = writeDeviceRelayMessage(registerCtx, connection, registration)
	if err == nil {
		var registered deviceRelayMessage
		registered, err = readDeviceRelayMessage(registerCtx, connection)
		if err == nil && (registered.Kind != "registered" || registered.DeviceID != listener.identity.deviceID ||
			registered.DevicePublicKey != listener.identity.publicKey() || registered.Role != "listener" ||
			!validDeviceRelayClock(registered.IssuedAt, time.Now())) {
			err = errors.New("电脑本机 ID 登记响应无效")
		}
	}
	registerCancel()
	if err != nil {
		return err
	}
	listener.setOnline(true)
	for ctx.Err() == nil {
		message, readErr := readDeviceRelayMessage(ctx, connection)
		if readErr != nil {
			return readErr
		}
		switch message.Kind {
		case "auth_begin":
			if authErr := listener.handleAuthBegin(message); authErr != nil {
				_ = listener.Respond(message, false, authErr.Error(), "")
			}
		case "connect_request":
			request, validateErr := listener.validateRequest(message)
			if validateErr == nil && listener.onRequest != nil {
				go listener.onRequest(request)
			}
		}
	}
	return ctx.Err()
}

func (listener *deviceRelayListener) validateRequest(message deviceRelayMessage) (deviceRelayIncomingRequest, error) {
	if message.Version != deviceRelayProtocolVersion || message.Kind != "connect_request" ||
		!requestIDPattern(message.RequestID) || message.TargetDeviceID != listener.identity.deviceID ||
		!validDeviceRelayClock(message.IssuedAt, time.Now()) || len(message.DeviceName) == 0 || len(message.DeviceName) > 80 ||
		strings.ContainsAny(message.DeviceName, "\x00\r\n") || !validDeviceRelayEphemeralPublicKey(message.EphemeralPublicKey) ||
		!validIncomingRelayAuthentication(message) {
		return deviceRelayIncomingRequest{}, errors.New("连接申请格式无效")
	}
	deviceID, err := deviceIDForPublicKey(message.SourcePublicKey)
	if err != nil || deviceID != message.SourceDeviceID || deviceID == listener.identity.deviceID ||
		!verifyDeviceSignature(message.SourcePublicKey, deviceRelayConnectRequestPayload(message), message.Signature) {
		return deviceRelayIncomingRequest{}, errors.New("连接申请签名无效")
	}
	der, err := decodeBase64URL(message.SourcePublicKey, maxDevicePublicDER)
	if err != nil {
		return deviceRelayIncomingRequest{}, err
	}
	return deviceRelayIncomingRequest{message: message, fingerprint: fingerprintForPublicDER(der)}, nil
}

func validIncomingRelayAuthentication(message deviceRelayMessage) bool {
	switch message.AuthMethod {
	case "":
		return message.ClientNonce == "" && message.AuthProof == ""
	case connectionGitHubAccountAuth:
		return message.ClientNonce == "" && validAccountProofTicket(message.AuthProof)
	case connectionTemporaryCodeAuth, connectionSecurityPasswordAuth:
		parts := strings.Split(message.AuthProof, ".")
		if len(parts) != 2 {
			return false
		}
		clientNonce, nonceErr := decodeBase64URL(message.ClientNonce, 48)
		sessionID, sessionErr := decodeBase64URL(parts[0], 18)
		proof, proofErr := decodeBase64URL(parts[1], 32)
		return nonceErr == nil && len(clientNonce) >= 18 && sessionErr == nil && len(sessionID) == 18 &&
			proofErr == nil && len(proof) == 32
	default:
		return false
	}
}

func validDeviceRelayEphemeralPublicKey(publicKey string) bool {
	_, err := deviceIDForPublicKey(publicKey)
	return err == nil
}

func (listener *deviceRelayListener) handleAuthBegin(message deviceRelayMessage) error {
	if listener.auth == nil || message.Version != deviceRelayProtocolVersion || message.Kind != "auth_begin" ||
		!requestIDPattern(message.RequestID) || message.TargetDeviceID != listener.identity.deviceID ||
		!validDeviceRelayClock(message.IssuedAt, time.Now()) || len(message.DeviceName) == 0 || len(message.DeviceName) > 80 ||
		strings.ContainsAny(message.DeviceName, "\x00\r\n") ||
		(message.AuthMethod != connectionTemporaryCodeAuth && message.AuthMethod != connectionSecurityPasswordAuth) {
		return errors.New("密码认证申请格式无效")
	}
	deviceID, err := deviceIDForPublicKey(message.SourcePublicKey)
	if err != nil || deviceID != message.SourceDeviceID || deviceID == listener.identity.deviceID ||
		!verifyDeviceSignature(message.SourcePublicKey, deviceRelayAuthBeginPayload(message), message.Signature) {
		return errors.New("密码认证申请签名无效")
	}
	der, err := decodeBase64URL(message.SourcePublicKey, maxDevicePublicDER)
	if err != nil {
		return err
	}
	response, err := listener.auth.Begin(message, fingerprintForPublicDER(der), time.Now())
	if err != nil {
		return err
	}
	challenge := deviceRelayMessage{
		Version: deviceRelayProtocolVersion, Kind: "auth_challenge", RequestID: message.RequestID,
		SourceDeviceID: listener.identity.deviceID, TargetDeviceID: message.SourceDeviceID,
		AuthMethod: message.AuthMethod, ClientNonce: message.ClientNonce,
		SessionID: response.SessionID, ServerNonce: response.ServerNonce, Salt: response.Salt,
		Iterations: response.Iterations, ExpiresAt: response.ExpiresAt, IssuedAt: time.Now().UnixMilli(),
	}
	challenge.Signature, err = listener.identity.sign(deviceRelayAuthChallengePayload(challenge))
	if err != nil {
		return err
	}
	return listener.send(challenge)
}

func (listener *deviceRelayListener) Respond(request deviceRelayMessage, accepted bool, errorMessage, serverProof string) error {
	listener.mu.Lock()
	connection := listener.socket
	listener.mu.Unlock()
	if connection == nil {
		return errors.New("电脑设备服务当前离线")
	}
	safeError := truncateRunes(strings.NewReplacer("\r", " ", "\n", " ", "\x00", " ").Replace(errorMessage), 300)
	acknowledgement := deviceRelayMessage{
		Version: deviceRelayProtocolVersion, Kind: "connect_ack", RequestID: request.RequestID,
		SourceDeviceID: listener.identity.deviceID, TargetDeviceID: request.SourceDeviceID,
		Status: "rejected", Error: safeError, ServerProof: serverProof, IssuedAt: time.Now().UnixMilli(),
	}
	if accepted {
		acknowledgement.Status = "accepted"
		acknowledgement.Error = ""
	}
	var err error
	acknowledgement.Signature, err = listener.identity.sign(deviceRelayConnectAckPayload(acknowledgement))
	if err != nil {
		return err
	}
	return listener.send(acknowledgement)
}

func (listener *deviceRelayListener) send(message deviceRelayMessage) error {
	listener.mu.Lock()
	connection := listener.socket
	listener.mu.Unlock()
	if connection == nil {
		return errors.New("电脑设备服务当前离线")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	listener.sendMu.Lock()
	defer listener.sendMu.Unlock()
	return writeDeviceRelayMessage(ctx, connection, message)
}

func (listener *deviceRelayListener) setOnline(online bool) {
	if listener.onState != nil {
		listener.onState(online)
	}
}

func desktopDeviceName() string {
	name, _ := os.Hostname()
	name = strings.TrimSpace(name)
	if name == "" {
		return "Murong Desktop"
	}
	return truncateRunes(name, 80)
}
