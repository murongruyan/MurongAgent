package desktopbridge

import (
	"context"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"testing"
	"time"
)

const adbIntegrationClientName = "Murong ADB Integration Test"

func TestParseADBDevicesIncludesAuthorizationAndMetadata(t *testing.T) {
	devices := parseADBDevices("List of devices attached\r\n192.168.2.4:5555\tdevice product:RE6030L1 model:RMX5200 device:RE6030L1 transport_id:12\r\nUSB123\tunauthorized usb:1-2\r\n")
	if len(devices) != 2 {
		t.Fatalf("unexpected devices: %#v", devices)
	}
	if devices[0].Serial != "192.168.2.4:5555" || !devices[0].Authorized || devices[0].Model != "RMX5200" || devices[0].TransportID != "12" {
		t.Fatalf("authorized device was not parsed: %#v", devices[0])
	}
	if devices[1].Serial != "USB123" || devices[1].Authorized || devices[1].State != "unauthorized" {
		t.Fatalf("unauthorized device was not preserved: %#v", devices[1])
	}
}

func TestSelectADBDeviceRequiresAnUnambiguousAuthorizedDevice(t *testing.T) {
	devices := []RemoteADBDevice{
		{Serial: "A", State: "device", Authorized: true},
		{Serial: "B", State: "device", Authorized: true},
		{Serial: "C", State: "unauthorized"},
	}
	if _, err := selectADBDevice(devices, ""); err == nil {
		t.Fatal("multiple authorized devices should require an explicit selection")
	}
	selected, err := selectADBDevice(devices, "B")
	if err != nil || selected.Serial != "B" {
		t.Fatalf("explicit ADB device selection failed: %#v, %v", selected, err)
	}
	if _, err := selectADBDevice(devices, "C"); err == nil {
		t.Fatal("unauthorized device should be rejected")
	}
}

func TestIsRootUIDRequiresAnExactZeroUID(t *testing.T) {
	for _, testCase := range []struct {
		name   string
		output string
		root   bool
	}{
		{name: "direct root", output: "0\r\n", root: true},
		{name: "su banner then root", output: "KernelSU\n0\n", root: true},
		{name: "shell user", output: "2000\n", root: false},
		{name: "zero embedded in text", output: "uid=0(root)", root: false},
		{name: "empty", output: "", root: false},
	} {
		t.Run(testCase.name, func(t *testing.T) {
			if actual := isRootUID(testCase.output); actual != testCase.root {
				t.Fatalf("isRootUID(%q) = %t, want %t", testCase.output, actual, testCase.root)
			}
		})
	}
}

func TestADBProofHasStableCrossPlatformVector(t *testing.T) {
	challenge, err := hex.DecodeString("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
	if err != nil {
		t.Fatal(err)
	}
	proof, err := adbProofForRequest(challenge, connectionRequest{
		RequestID:          "connect-test",
		DeviceID:           "DMB77YSEX4BLAFRU",
		EphemeralPublicKey: "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEaxfR8uEsQkf4vOblY6RA8ncDfYEt6zOg9KE5RdiYwpZP40Li_hp_m47n60p8D54WK84zV2sxXs7LtkBoN79R9Q",
	})
	if err != nil {
		t.Fatal(err)
	}
	if proof != "5_YI_6D-HJLBm7sJ1ysDfz0lm2dk_t6RNrbkPJetvgk" {
		t.Fatalf("unexpected ADB proof: %s", proof)
	}
}

func TestADBTransportIntegration(t *testing.T) {
	serial := os.Getenv("MURONG_ADB_INTEGRATION_SERIAL")
	if serial == "" {
		t.Skip("set MURONG_ADB_INTEGRATION_SERIAL to run the real-device ADB test")
	}
	holdSeconds, _ := strconv.Atoi(os.Getenv("MURONG_ADB_INTEGRATION_HOLD_SECONDS"))
	ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second+time.Duration(holdSeconds)*time.Second)
	defer cancel()
	removed, err := cleanupStaleADBIntegrationClients(ctx, serial)
	if err != nil {
		t.Fatalf("failed to clean stale ADB integration clients: %v", err)
	}
	if removed > 0 {
		t.Logf("removed %d stale ADB integration clients", removed)
	}
	target, challenge, cleanup, selectedSerial, err := prepareADBTransport(ctx, serial)
	if err != nil {
		t.Fatal(err)
	}
	defer cleanup()
	defer clearBytes(challenge)
	if selectedSerial != serial {
		t.Fatalf("selected the wrong ADB device: %s", selectedSerial)
	}
	config := nodeConfig{SchemaVersion: nodeConfigSchemaVersion}
	identity, err := ensureNodeDeviceIdentity(&config)
	if err != nil {
		t.Fatal(err)
	}
	api := newAPIClient(target)
	defer api.Close()
	credentials, err := api.connectByRequestWithAuth(
		ctx, identity, adbIntegrationClientName, "", "", connectionADBAuth,
		func(request connectionRequest) (string, error) { return adbProofForRequest(challenge, request) },
	)
	if err != nil {
		t.Fatal(err)
	}
	defer clearBytes(credentials.syncKey)
	if credentials.token == "" || len(credentials.syncKey) != 32 || credentials.peerDeviceID == "" || credentials.peerFingerprint == "" {
		t.Fatalf("incomplete ADB credentials: token=%t sync=%d peer=%q fingerprint=%q", credentials.token != "", len(credentials.syncKey), credentials.peerDeviceID, credentials.peerFingerprint)
	}
	api.token = credentials.token
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, api.endpoint("/api/v1/sessions"), nil)
	if err != nil {
		t.Fatal(err)
	}
	request.Header.Set("Authorization", "Bearer "+credentials.token)
	response, err := api.httpClient.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	_ = response.Body.Close()
	if response.StatusCode != http.StatusOK {
		t.Fatalf("ADB-issued token was rejected: HTTP %d", response.StatusCode)
	}
	if holdSeconds > 0 {
		deadline := time.Now().Add(time.Duration(holdSeconds) * time.Second)
		for time.Now().Before(deadline) {
			time.Sleep(5 * time.Second)
			heartbeat, requestErr := http.NewRequestWithContext(ctx, http.MethodGet, api.endpoint("/api/v1/sessions"), nil)
			if requestErr != nil {
				t.Fatal(requestErr)
			}
			heartbeat.Header.Set("Authorization", "Bearer "+credentials.token)
			heartbeatResponse, heartbeatErr := api.httpClient.Do(heartbeat)
			if heartbeatErr != nil {
				t.Fatalf("ADB connection did not remain alive: %v", heartbeatErr)
			}
			_ = heartbeatResponse.Body.Close()
			if heartbeatResponse.StatusCode != http.StatusOK {
				t.Fatalf("ADB heartbeat was rejected: HTTP %d", heartbeatResponse.StatusCode)
			}
		}
	}
	if err := api.postJSON(ctx, "/api/v1/unpair", map[string]string{}, nil); err != nil {
		t.Fatalf("failed to revoke integration client: %v", err)
	}
}

func cleanupStaleADBIntegrationClients(ctx context.Context, serial string) (int, error) {
	adbPath, err := findADBExecutable()
	if err != nil {
		return 0, err
	}
	rootMode := detectADBRoot(ctx, adbPath, serial)
	if rootMode == adbRootUnavailable {
		return 0, errors.New("清理测试配对需要 Root ADB；不会删除普通用户设备")
	}
	if _, err := runADBRootCommand(ctx, adbPath, serial, rootMode, "am force-stop com.murong.agent >/dev/null 2>&1 || true"); err != nil {
		return 0, err
	}
	const statePath = "/data/user/0/com.murong.agent/no_backup/lan_web_access.json"
	remoteTemporary := fmt.Sprintf("/data/local/tmp/murong-adb-integration-%d.json", time.Now().UnixNano())
	defer func() {
		cleanupContext, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		defer cancel()
		_, _ = runADBRootCommand(cleanupContext, adbPath, serial, rootMode, "rm -f "+adbShellQuote(remoteTemporary))
	}()
	copyCommand := "test -f " + adbShellQuote(statePath) + " || exit 0; cp " + adbShellQuote(statePath) + " " + adbShellQuote(remoteTemporary) + " && chmod 0644 " + adbShellQuote(remoteTemporary)
	if _, err := runADBRootCommand(ctx, adbPath, serial, rootMode, copyCommand); err != nil {
		return 0, err
	}
	localDirectory, err := os.MkdirTemp("", "murong-adb-integration-")
	if err != nil {
		return 0, err
	}
	defer os.RemoveAll(localDirectory)
	localPath := filepath.Join(localDirectory, "lan_web_access.json")
	if _, err := runADB(ctx, adbPath, "-s", serial, "pull", remoteTemporary, localPath); err != nil {
		return 0, err
	}
	content, err := os.ReadFile(localPath)
	if err != nil {
		return 0, err
	}
	var state map[string]json.RawMessage
	if err := json.Unmarshal(content, &state); err != nil {
		return 0, err
	}
	var clients []map[string]json.RawMessage
	if err := json.Unmarshal(state["clients"], &clients); err != nil {
		return 0, err
	}
	remaining := clients[:0]
	removed := 0
	for _, client := range clients {
		var name string
		_ = json.Unmarshal(client["name"], &name)
		if name == adbIntegrationClientName {
			removed++
			continue
		}
		remaining = append(remaining, client)
	}
	if removed == 0 {
		return 0, nil
	}
	state["clients"], err = json.Marshal(remaining)
	if err != nil {
		return 0, err
	}
	updated, err := json.MarshalIndent(state, "", "    ")
	if err != nil {
		return 0, err
	}
	updated = append(updated, '\n')
	if err := os.WriteFile(localPath, updated, 0o600); err != nil {
		return 0, err
	}
	if _, err := runADB(ctx, adbPath, "-s", serial, "push", localPath, remoteTemporary); err != nil {
		return 0, err
	}
	writeCommand := "mkdir -p /data/adb/murongagent-backups; " +
		"test -f /data/adb/murongagent-backups/lan_web_access.before-adb-integration.json || cp -p " + adbShellQuote(statePath) + " /data/adb/murongagent-backups/lan_web_access.before-adb-integration.json; " +
		"cat " + adbShellQuote(remoteTemporary) + " > " + adbShellQuote(statePath)
	if _, err := runADBRootCommand(ctx, adbPath, serial, rootMode, writeCommand); err != nil {
		return 0, err
	}
	return removed, nil
}
