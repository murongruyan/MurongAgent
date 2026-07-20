package desktopbridge

import (
	"context"
	"encoding/json"
	"net/http"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/coder/websocket"
)

const (
	cloudRelayPeerQueueSize    = 32
	cloudRelayPendingQueueSize = 4
	cloudRelayMaxPendingBytes  = 16 * 1024 * 1024
)

type CloudRelayServer struct {
	mu             sync.Mutex
	rooms          map[string]*cloudRelayRoom
	active         atomic.Int64
	maxConnections int64
	pendingBytes   int
}

type cloudRelayRoom struct {
	phone     *cloudRelayPeer
	desktop   *cloudRelayPeer
	toPhone   [][]byte
	toDesktop [][]byte
}

type cloudRelayPeer struct {
	roomID string
	role   string
	conn   *websocket.Conn
	send   chan []byte
	cancel context.CancelFunc
}

func NewCloudRelayServer(maxConnections int) *CloudRelayServer {
	if maxConnections <= 0 {
		maxConnections = 1024
	}
	return &CloudRelayServer{
		rooms:          make(map[string]*cloudRelayRoom),
		maxConnections: int64(maxConnections),
	}
}

func (server *CloudRelayServer) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", func(writer http.ResponseWriter, _ *http.Request) {
		writer.Header().Set("Content-Type", "application/json; charset=utf-8")
		writer.Header().Set("Cache-Control", "no-store")
		_ = json.NewEncoder(writer).Encode(map[string]any{
			"service":     "murong-cloud-relay",
			"version":     CloudRelayProtocolVersion,
			"connections": server.active.Load(),
		})
	})
	mux.HandleFunc("GET /v1/connect", server.handleConnect)
	return securityHeaders(mux)
}

func securityHeaders(next http.Handler) http.Handler {
	return http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		writer.Header().Set("Cache-Control", "no-store")
		writer.Header().Set("Content-Security-Policy", "default-src 'none'")
		writer.Header().Set("Referrer-Policy", "no-referrer")
		writer.Header().Set("X-Content-Type-Options", "nosniff")
		next.ServeHTTP(writer, request)
	})
}

func (server *CloudRelayServer) handleConnect(writer http.ResponseWriter, request *http.Request) {
	roomID := strings.TrimSpace(request.URL.Query().Get("room"))
	role := strings.TrimSpace(request.URL.Query().Get("role"))
	version := strings.TrimSpace(request.URL.Query().Get("v"))
	if validateCloudRelayRoomID(roomID) != nil || role != CloudRelayRolePhone && role != CloudRelayRoleDesktop || version != "1" {
		http.Error(writer, "invalid relay connection metadata", http.StatusBadRequest)
		return
	}
	if !server.reserveConnection() {
		http.Error(writer, "relay capacity reached", http.StatusServiceUnavailable)
		return
	}
	defer server.active.Add(-1)
	connection, err := websocket.Accept(writer, request, &websocket.AcceptOptions{
		Subprotocols:       []string{CloudRelaySubprotocol},
		CompressionMode:    websocket.CompressionDisabled,
		OriginPatterns:     []string{},
		InsecureSkipVerify: false,
	})
	if err != nil {
		return
	}
	if connection.Subprotocol() != CloudRelaySubprotocol {
		_ = connection.Close(websocket.StatusPolicyViolation, "relay subprotocol required")
		return
	}
	connection.SetReadLimit(cloudRelayMaxFrameBytes)

	peerContext, cancel := context.WithCancel(context.Background())
	peer := &cloudRelayPeer{
		roomID: roomID,
		role:   role,
		conn:   connection,
		send:   make(chan []byte, cloudRelayPeerQueueSize),
		cancel: cancel,
	}
	previous := server.attach(peer)
	if previous != nil {
		previous.cancel()
		_ = previous.conn.Close(websocket.StatusNormalClosure, "replaced by a newer connection")
	}
	defer func() {
		cancel()
		server.detach(peer)
		_ = connection.Close(websocket.StatusNormalClosure, "")
	}()

	writerDone := make(chan struct{})
	go func() {
		defer close(writerDone)
		for {
			select {
			case <-peerContext.Done():
				return
			case payload := <-peer.send:
				writeContext, writeCancel := context.WithTimeout(peerContext, 15*time.Second)
				err := connection.Write(writeContext, websocket.MessageBinary, payload)
				writeCancel()
				if err != nil {
					cancel()
					return
				}
			}
		}
	}()

	for {
		messageType, payload, err := connection.Read(peerContext)
		if err != nil {
			break
		}
		if messageType != websocket.MessageBinary || len(payload) == 0 || len(payload) > cloudRelayMaxFrameBytes {
			_ = connection.Close(websocket.StatusUnsupportedData, "binary encrypted frames only")
			break
		}
		if !server.forward(peer, payload) {
			_ = connection.Close(websocket.StatusPolicyViolation, "peer queue is full")
			break
		}
	}
	cancel()
	select {
	case <-writerDone:
	case <-time.After(time.Second):
	}
}

func (server *CloudRelayServer) reserveConnection() bool {
	for {
		active := server.active.Load()
		if active >= server.maxConnections {
			return false
		}
		if server.active.CompareAndSwap(active, active+1) {
			return true
		}
	}
}

func (server *CloudRelayServer) attach(peer *cloudRelayPeer) *cloudRelayPeer {
	server.mu.Lock()
	defer server.mu.Unlock()
	room := server.rooms[peer.roomID]
	if room == nil {
		room = &cloudRelayRoom{}
		server.rooms[peer.roomID] = room
	}
	var previous *cloudRelayPeer
	if peer.role == CloudRelayRolePhone {
		previous = room.phone
		if previous != nil {
			server.dropPendingLocked(&room.toDesktop)
		}
		room.phone = peer
		server.flushPendingLocked(peer, &room.toPhone)
	} else {
		previous = room.desktop
		if previous != nil {
			server.dropPendingLocked(&room.toPhone)
		}
		room.desktop = peer
		server.flushPendingLocked(peer, &room.toDesktop)
	}
	return previous
}

func (server *CloudRelayServer) detach(peer *cloudRelayPeer) {
	server.mu.Lock()
	defer server.mu.Unlock()
	room := server.rooms[peer.roomID]
	if room == nil {
		return
	}
	if peer.role == CloudRelayRolePhone && room.phone == peer {
		room.phone = nil
		server.dropPendingLocked(&room.toDesktop)
	}
	if peer.role == CloudRelayRoleDesktop && room.desktop == peer {
		room.desktop = nil
		server.dropPendingLocked(&room.toPhone)
	}
	if room.phone == nil && room.desktop == nil {
		delete(server.rooms, peer.roomID)
	}
}

func (server *CloudRelayServer) forward(sender *cloudRelayPeer, payload []byte) bool {
	server.mu.Lock()
	defer server.mu.Unlock()
	room := server.rooms[sender.roomID]
	if room == nil {
		return true
	}
	target := room.phone
	pending := &room.toPhone
	if sender.role == CloudRelayRolePhone {
		target = room.desktop
		pending = &room.toDesktop
	}
	if target == nil {
		if len(*pending) >= cloudRelayPendingQueueSize || server.pendingBytes+len(payload) > cloudRelayMaxPendingBytes {
			return false
		}
		copyOfPayload := append([]byte(nil), payload...)
		*pending = append(*pending, copyOfPayload)
		server.pendingBytes += len(copyOfPayload)
		return true
	}
	copyOfPayload := append([]byte(nil), payload...)
	select {
	case target.send <- copyOfPayload:
		return true
	default:
		return false
	}
}

func (server *CloudRelayServer) flushPendingLocked(target *cloudRelayPeer, pending *[][]byte) {
	for _, payload := range *pending {
		target.send <- payload
		server.pendingBytes -= len(payload)
	}
	*pending = nil
}

func (server *CloudRelayServer) dropPendingLocked(pending *[][]byte) {
	for _, payload := range *pending {
		server.pendingBytes -= len(payload)
	}
	*pending = nil
}
