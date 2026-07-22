package desktopbridge

import (
	"context"
	"encoding/json"
	"net"
	"runtime"
	"strings"
	"sync"
	"time"
)

type lanDiscoveryResponder struct {
	identity *deviceIdentity
	name     string

	mu     sync.Mutex
	cancel context.CancelFunc
	done   chan struct{}
	socket *net.UDPConn
}

func newLANDiscoveryResponder(identity *deviceIdentity) *lanDiscoveryResponder {
	return &lanDiscoveryResponder{identity: identity, name: desktopDeviceName()}
}

func (responder *lanDiscoveryResponder) Start() {
	responder.mu.Lock()
	if responder.cancel != nil || responder.identity == nil {
		responder.mu.Unlock()
		return
	}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	responder.cancel = cancel
	responder.done = done
	responder.mu.Unlock()
	go responder.run(ctx, done)
}

func (responder *lanDiscoveryResponder) Close(timeout time.Duration) {
	responder.mu.Lock()
	cancel := responder.cancel
	done := responder.done
	socket := responder.socket
	responder.mu.Unlock()
	if cancel != nil {
		cancel()
	}
	if socket != nil {
		_ = socket.Close()
	}
	if timeout > 0 && done != nil {
		select {
		case <-done:
		case <-time.After(timeout):
		}
	}
}

func (responder *lanDiscoveryResponder) run(ctx context.Context, done chan struct{}) {
	defer close(done)
	defer func() {
		responder.mu.Lock()
		responder.cancel = nil
		responder.done = nil
		responder.socket = nil
		responder.mu.Unlock()
	}()
	connection, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4zero, Port: lanDiscoveryPort})
	if err != nil {
		return
	}
	defer connection.Close()
	responder.mu.Lock()
	responder.socket = connection
	responder.mu.Unlock()
	buffer := make([]byte, lanDiscoveryMaxPacket)
	for ctx.Err() == nil {
		_ = connection.SetReadDeadline(time.Now().Add(time.Second))
		length, source, readErr := connection.ReadFromUDP(buffer)
		if readErr != nil {
			if networkErr, ok := readErr.(net.Error); ok && networkErr.Timeout() {
				continue
			}
			return
		}
		if source == nil || !allowedPrivateIP(source.IP) || length <= len(lanDiscoveryPrefix) || length > 128 {
			continue
		}
		text := string(buffer[:length])
		if !strings.HasPrefix(text, lanDiscoveryPrefix) {
			continue
		}
		nonce := strings.TrimPrefix(text, lanDiscoveryPrefix)
		decoded, decodeErr := decodeBase64URL(nonce, 16)
		if decodeErr != nil || len(decoded) != 16 {
			continue
		}
		announcement := lanDiscoveryAnnouncement{
			Version: lanDiscoveryVersion, Nonce: nonce,
			DeviceID: responder.identity.deviceID, DeviceDisplayID: responder.identity.displayID(),
			DevicePublicKey: responder.identity.publicKey(), DeviceFingerprint: responder.identity.fingerprint,
			Name: responder.name, Platform: runtime.GOOS, Port: 1, IssuedAt: time.Now().UnixMilli(),
		}
		announcement.Signature, err = responder.identity.sign(lanDiscoverySignaturePayload(announcement))
		if err != nil {
			continue
		}
		encoded, encodeErr := json.Marshal(announcement)
		if encodeErr == nil && len(encoded) <= lanDiscoveryMaxPacket {
			_, _ = connection.WriteToUDP(encoded, source)
		}
	}
}
