package desktopbridge

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"sort"
	"strings"
	"time"
)

const (
	lanDiscoveryVersion   = 1
	lanDiscoveryPort      = 18765
	lanDiscoveryMaxPacket = 2048
	lanDiscoveryPrefix    = "MURONG_DISCOVER_V1\n"
)

type RemoteDiscoveredDevice struct {
	DeviceID        string `json:"deviceId"`
	DeviceDisplayID string `json:"deviceDisplayId"`
	Name            string `json:"name"`
	Platform        string `json:"platform"`
	URL             string `json:"url"`
	PublicKey       string `json:"publicKey"`
	Fingerprint     string `json:"fingerprint"`
	LastSeenAt      int64  `json:"lastSeenAt"`
}

type lanDiscoveryAnnouncement struct {
	Version           int    `json:"version"`
	Nonce             string `json:"nonce"`
	DeviceID          string `json:"deviceId"`
	DeviceDisplayID   string `json:"deviceDisplayId"`
	DevicePublicKey   string `json:"devicePublicKey"`
	DeviceFingerprint string `json:"deviceFingerprint"`
	Name              string `json:"name"`
	Platform          string `json:"platform"`
	Port              int    `json:"port"`
	IssuedAt          int64  `json:"issuedAt"`
	Signature         string `json:"signature"`
}

func discoverLANDevices(ctx context.Context, duration time.Duration) ([]RemoteDiscoveredDevice, error) {
	if duration <= 0 || duration > 10*time.Second {
		duration = 1800 * time.Millisecond
	}
	connection, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4zero, Port: 0})
	if err != nil {
		return nil, err
	}
	defer connection.Close()
	nonceBytes := make([]byte, 16)
	if _, err := rand.Read(nonceBytes); err != nil {
		return nil, err
	}
	nonce := base64.RawURLEncoding.EncodeToString(nonceBytes)
	clearBytes(nonceBytes)
	payload := []byte(lanDiscoveryPrefix + nonce)
	for _, address := range lanBroadcastAddresses() {
		_, _ = connection.WriteToUDP(payload, &net.UDPAddr{IP: address, Port: lanDiscoveryPort})
	}
	deadline := time.Now().Add(duration)
	_ = connection.SetReadDeadline(deadline)
	devices := make(map[string]RemoteDiscoveredDevice)
	buffer := make([]byte, lanDiscoveryMaxPacket)
	for {
		if err := ctx.Err(); err != nil {
			return nil, err
		}
		length, source, err := connection.ReadFromUDP(buffer)
		if err != nil {
			var networkError net.Error
			if errors.As(err, &networkError) && networkError.Timeout() {
				break
			}
			return nil, err
		}
		if source == nil || !allowedPrivateIP(source.IP) || length <= 0 || length > lanDiscoveryMaxPacket {
			continue
		}
		var announcement lanDiscoveryAnnouncement
		decoder := json.NewDecoder(strings.NewReader(string(buffer[:length])))
		decoder.DisallowUnknownFields()
		if decoder.Decode(&announcement) != nil {
			continue
		}
		if !validateLANAnnouncement(announcement, nonce, time.Now()) {
			continue
		}
		if !strings.EqualFold(announcement.Platform, "android") {
			continue
		}
		deviceURL := "http://" + net.JoinHostPort(source.IP.String(), fmt.Sprintf("%d", announcement.Port))
		devices[announcement.DeviceID] = RemoteDiscoveredDevice{
			DeviceID: announcement.DeviceID, DeviceDisplayID: formatDeviceID(announcement.DeviceID),
			Name: strings.TrimSpace(announcement.Name), Platform: announcement.Platform, URL: deviceURL,
			PublicKey: announcement.DevicePublicKey, Fingerprint: announcement.DeviceFingerprint,
			LastSeenAt: announcement.IssuedAt,
		}
	}
	result := make([]RemoteDiscoveredDevice, 0, len(devices))
	for _, device := range devices {
		result = append(result, device)
	}
	sort.Slice(result, func(i, j int) bool {
		if result[i].Name == result[j].Name {
			return result[i].DeviceID < result[j].DeviceID
		}
		return result[i].Name < result[j].Name
	})
	return result, nil
}

func validateLANAnnouncement(value lanDiscoveryAnnouncement, expectedNonce string, now time.Time) bool {
	if value.Version != lanDiscoveryVersion || value.Nonce != expectedNonce || value.Port < 1 || value.Port > 65535 {
		return false
	}
	if value.IssuedAt < now.Add(-2*time.Minute).UnixMilli() || value.IssuedAt > now.Add(30*time.Second).UnixMilli() {
		return false
	}
	if len(value.Name) == 0 || len(value.Name) > 80 || strings.ContainsAny(value.Name, "\x00\r\n") ||
		len(value.Platform) == 0 || len(value.Platform) > 24 || strings.ContainsAny(value.Platform, "\x00\r\n") {
		return false
	}
	derivedID, err := deviceIDForPublicKey(value.DevicePublicKey)
	if err != nil || derivedID != value.DeviceID || formatDeviceID(value.DeviceID) != value.DeviceDisplayID {
		return false
	}
	der, err := decodeBase64URL(value.DevicePublicKey, maxDevicePublicDER)
	if err != nil {
		return false
	}
	digest := sha256.Sum256(der)
	if base64.RawURLEncoding.EncodeToString(digest[:]) != value.DeviceFingerprint {
		return false
	}
	return verifyDeviceSignature(value.DevicePublicKey, lanDiscoverySignaturePayload(value), value.Signature)
}

func lanDiscoverySignaturePayload(value lanDiscoveryAnnouncement) []byte {
	return []byte(fmt.Sprintf("murong-lan-discovery-v1\n%s\n%s\n%s\n%s\n%s\n%s\n%d\n%d",
		value.Nonce, value.DeviceID, value.DevicePublicKey, value.DeviceFingerprint,
		value.Name, value.Platform, value.Port, value.IssuedAt))
}

func lanBroadcastAddresses() []net.IP {
	seen := map[string]bool{}
	result := []net.IP{net.IPv4bcast}
	seen[net.IPv4bcast.String()] = true
	interfaces, _ := net.Interfaces()
	for _, networkInterface := range interfaces {
		if networkInterface.Flags&net.FlagUp == 0 || networkInterface.Flags&net.FlagLoopback != 0 {
			continue
		}
		addresses, _ := networkInterface.Addrs()
		for _, address := range addresses {
			ipNetwork, ok := address.(*net.IPNet)
			if !ok || ipNetwork.IP.To4() == nil || len(ipNetwork.Mask) != net.IPv4len {
				continue
			}
			ip := ipNetwork.IP.To4()
			broadcast := net.IPv4(ip[0]|^ipNetwork.Mask[0], ip[1]|^ipNetwork.Mask[1], ip[2]|^ipNetwork.Mask[2], ip[3]|^ipNetwork.Mask[3])
			if !seen[broadcast.String()] {
				seen[broadcast.String()] = true
				result = append(result, broadcast)
			}
		}
	}
	return result
}
