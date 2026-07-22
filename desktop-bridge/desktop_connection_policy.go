package desktopbridge

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"
)

const (
	desktopConnectionPolicyVersion = 1
	maxDesktopBlockedPeers         = 256
)

type RemoteBlockedPeer struct {
	DeviceID        string `json:"deviceId"`
	DeviceDisplayID string `json:"deviceDisplayId"`
	DeviceName      string `json:"deviceName"`
	Fingerprint     string `json:"fingerprint"`
	BlockedAt       int64  `json:"blockedAt"`
}

type desktopConnectionPolicyState struct {
	Version      int                 `json:"version"`
	DoNotDisturb bool                `json:"doNotDisturb"`
	BlockedPeers []RemoteBlockedPeer `json:"blockedPeers,omitempty"`
}

type desktopConnectionPolicyStore struct {
	mu    sync.Mutex
	path  string
	state desktopConnectionPolicyState
}

func newDesktopConnectionPolicyStore(path string) (*desktopConnectionPolicyStore, error) {
	store := &desktopConnectionPolicyStore{
		path:  strings.TrimSpace(path),
		state: desktopConnectionPolicyState{Version: desktopConnectionPolicyVersion},
	}
	if store.path == "" {
		return nil, errors.New("电脑连接策略文件路径为空")
	}
	data, err := os.ReadFile(store.path)
	if errors.Is(err, os.ErrNotExist) {
		return store, nil
	}
	if err != nil {
		return nil, err
	}
	var state desktopConnectionPolicyState
	if err := json.Unmarshal(data, &state); err != nil {
		return nil, fmt.Errorf("电脑连接策略 JSON 损坏：%w", err)
	}
	if state.Version != desktopConnectionPolicyVersion {
		return nil, fmt.Errorf("不支持的电脑连接策略版本 %d", state.Version)
	}
	if len(state.BlockedPeers) > maxDesktopBlockedPeers {
		return nil, errors.New("电脑连接黑名单数量异常")
	}
	seen := make(map[string]bool, len(state.BlockedPeers))
	for index := range state.BlockedPeers {
		peer := &state.BlockedPeers[index]
		deviceID, normalizeErr := normalizeDeviceID(peer.DeviceID)
		if normalizeErr != nil || seen[deviceID] {
			return nil, errors.New("电脑连接黑名单包含无效设备 ID")
		}
		if raw, decodeErr := decodeBase64URL(peer.Fingerprint, 32); decodeErr != nil || len(raw) != 32 {
			return nil, errors.New("电脑连接黑名单包含无效设备指纹")
		}
		seen[deviceID] = true
		peer.DeviceID = deviceID
		peer.DeviceDisplayID = formatDeviceID(deviceID)
		peer.DeviceName = truncateRunes(strings.TrimSpace(peer.DeviceName), 80)
	}
	store.state = state
	return store, nil
}

func (store *desktopConnectionPolicyStore) Snapshot() (bool, []RemoteBlockedPeer) {
	if store == nil {
		return false, nil
	}
	store.mu.Lock()
	defer store.mu.Unlock()
	peers := append([]RemoteBlockedPeer(nil), store.state.BlockedPeers...)
	sort.Slice(peers, func(left, right int) bool { return peers[left].BlockedAt > peers[right].BlockedAt })
	return store.state.DoNotDisturb, peers
}

func (store *desktopConnectionPolicyStore) SetDoNotDisturb(enabled bool) error {
	store.mu.Lock()
	defer store.mu.Unlock()
	if store.state.DoNotDisturb == enabled {
		return nil
	}
	next := store.state
	next.DoNotDisturb = enabled
	return store.saveLocked(next)
}

func (store *desktopConnectionPolicyStore) IsBlocked(deviceID, fingerprint string) bool {
	if store == nil {
		return false
	}
	deviceID, err := normalizeDeviceID(deviceID)
	if err != nil {
		return true
	}
	fingerprint = strings.TrimSpace(fingerprint)
	store.mu.Lock()
	defer store.mu.Unlock()
	for _, peer := range store.state.BlockedPeers {
		if peer.DeviceID == deviceID || fingerprint != "" && peer.Fingerprint == fingerprint {
			return true
		}
	}
	return false
}

func (store *desktopConnectionPolicyStore) Block(peer RemoteBlockedPeer) error {
	deviceID, err := normalizeDeviceID(peer.DeviceID)
	if err != nil {
		return err
	}
	fingerprint := strings.TrimSpace(peer.Fingerprint)
	if raw, decodeErr := decodeBase64URL(fingerprint, 32); decodeErr != nil || len(raw) != 32 {
		return errors.New("设备指纹无效")
	}
	peer.DeviceID = deviceID
	peer.DeviceDisplayID = formatDeviceID(deviceID)
	peer.DeviceName = truncateRunes(strings.TrimSpace(peer.DeviceName), 80)
	peer.Fingerprint = fingerprint
	if peer.BlockedAt <= 0 {
		peer.BlockedAt = time.Now().UnixMilli()
	}
	store.mu.Lock()
	defer store.mu.Unlock()
	peers := make([]RemoteBlockedPeer, 0, len(store.state.BlockedPeers)+1)
	for _, existing := range store.state.BlockedPeers {
		if existing.DeviceID != peer.DeviceID && existing.Fingerprint != peer.Fingerprint {
			peers = append(peers, existing)
		}
	}
	peers = append(peers, peer)
	if len(peers) > maxDesktopBlockedPeers {
		peers = peers[len(peers)-maxDesktopBlockedPeers:]
	}
	next := store.state
	next.BlockedPeers = peers
	return store.saveLocked(next)
}

func (store *desktopConnectionPolicyStore) Unblock(deviceID string) (bool, error) {
	deviceID, err := normalizeDeviceID(deviceID)
	if err != nil {
		return false, err
	}
	store.mu.Lock()
	defer store.mu.Unlock()
	peers := make([]RemoteBlockedPeer, 0, len(store.state.BlockedPeers))
	removed := false
	for _, peer := range store.state.BlockedPeers {
		if peer.DeviceID == deviceID {
			removed = true
			continue
		}
		peers = append(peers, peer)
	}
	if !removed {
		return false, nil
	}
	next := store.state
	next.BlockedPeers = peers
	return true, store.saveLocked(next)
}

func (store *desktopConnectionPolicyStore) saveLocked(next desktopConnectionPolicyState) error {
	next.Version = desktopConnectionPolicyVersion
	data, err := json.MarshalIndent(next, "", "  ")
	if err != nil {
		return err
	}
	directory := filepath.Dir(store.path)
	if err := os.MkdirAll(directory, 0o700); err != nil {
		return err
	}
	temp, err := os.CreateTemp(directory, ".desktop-connection-policy-*.tmp")
	if err != nil {
		return err
	}
	tempName := temp.Name()
	defer os.Remove(tempName)
	if err := temp.Chmod(0o600); err != nil {
		temp.Close()
		return err
	}
	if _, err := temp.Write(data); err != nil {
		temp.Close()
		return err
	}
	if err := temp.Sync(); err != nil {
		temp.Close()
		return err
	}
	if err := temp.Close(); err != nil {
		return err
	}
	if err := replaceFile(tempName, store.path); err != nil {
		return err
	}
	store.state = next
	return nil
}
