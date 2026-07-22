package desktopbridge

import (
	"context"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	ConnectionModeADB       = "adb"
	adbRemotePort           = 8766
	adbChallengeBytes       = 32
	adbProofContext         = "murong-adb-device-link-v1"
	adbPairAction           = "com.murong.agent.lan.ADB_PAIR_CHALLENGE"
	adbBootstrapActivity    = "com.murong.agent/.lan.LanWebAdbBootstrapActivity"
	adbPairChallengeExtra   = "com.murong.agent.lan.extra.ADB_CHALLENGE"
	adbInitialEndpointWait  = 12 * time.Second
	adbFallbackEndpointWait = 23 * time.Second
	adbRootProbeTimeout     = 3 * time.Second
)

type adbRootMode uint8

const (
	adbRootUnavailable adbRootMode = iota
	adbRootShell
	adbRootSU
)

type RemoteADBDevice struct {
	Serial      string `json:"serial"`
	State       string `json:"state"`
	Model       string `json:"model,omitempty"`
	Product     string `json:"product,omitempty"`
	Device      string `json:"device,omitempty"`
	TransportID string `json:"transportId,omitempty"`
	Authorized  bool   `json:"authorized"`
}

func discoverADBDevices(ctx context.Context) ([]RemoteADBDevice, error) {
	adbPath, err := findADBExecutable()
	if err != nil {
		return nil, err
	}
	output, err := runADB(ctx, adbPath, "devices", "-l")
	if err != nil {
		return nil, fmt.Errorf("无法读取 ADB 设备：%w", err)
	}
	return parseADBDevices(output), nil
}

func prepareADBTransport(ctx context.Context, requestedSerial string) (*url.URL, []byte, func(), string, error) {
	adbPath, err := findADBExecutable()
	if err != nil {
		return nil, nil, nil, "", err
	}
	output, err := runADB(ctx, adbPath, "devices", "-l")
	if err != nil {
		return nil, nil, nil, "", fmt.Errorf("无法读取 ADB 设备：%w", err)
	}
	device, err := selectADBDevice(parseADBDevices(output), requestedSerial)
	if err != nil {
		return nil, nil, nil, "", err
	}
	challenge := make([]byte, adbChallengeBytes)
	if _, err := rand.Read(challenge); err != nil {
		return nil, nil, nil, "", fmt.Errorf("无法生成 ADB 一次性挑战：%w", err)
	}
	challengeText := base64.RawURLEncoding.EncodeToString(challenge)
	rootMode := detectADBRoot(ctx, adbPath, device.Serial)
	if rootMode == adbRootUnavailable {
		_, _ = runADB(ctx, adbPath, "-s", device.Serial, "shell", "cmd", "package", "unstop", "--user", "0", "com.murong.agent")
	} else {
		if err := prepareADBPowerPolicy(ctx, adbPath, device.Serial, rootMode); err != nil {
			clearBytes(challenge)
			return nil, nil, nil, "", err
		}
	}
	if err := ensureADBNotificationPermission(ctx, adbPath, device.Serial, rootMode); err != nil {
		clearBytes(challenge)
		return nil, nil, nil, "", err
	}
	if err := startADBNode(ctx, adbPath, device.Serial, rootMode, challengeText); err != nil {
		clearBytes(challenge)
		return nil, nil, nil, "", err
	}
	forwardOutput, err := runADB(ctx, adbPath, "-s", device.Serial, "forward", "tcp:0", fmt.Sprintf("tcp:%d", adbRemotePort))
	if err != nil {
		clearBytes(challenge)
		return nil, nil, nil, "", fmt.Errorf("无法建立 ADB 端口转发：%w", err)
	}
	localPort, err := strconv.Atoi(strings.TrimSpace(forwardOutput))
	if err != nil || localPort < 1 || localPort > 65535 {
		clearBytes(challenge)
		return nil, nil, nil, "", errors.New("ADB 未返回有效的本机转发端口")
	}
	var cleanupOnce sync.Once
	cleanup := func() {
		cleanupOnce.Do(func() {
			cleanupContext, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer cancel()
			_, _ = runADB(cleanupContext, adbPath, "-s", device.Serial, "forward", "--remove", fmt.Sprintf("tcp:%d", localPort))
		})
	}
	target, _ := url.Parse(fmt.Sprintf("http://127.0.0.1:%d", localPort))
	if err := waitForADBEndpoint(ctx, target, adbInitialEndpointWait); err != nil {
		_ = startADBNode(ctx, adbPath, device.Serial, rootMode, challengeText)
		if retryErr := waitForADBEndpoint(ctx, target, adbFallbackEndpointWait); retryErr != nil {
			cleanup()
			clearBytes(challenge)
			if rootMode != adbRootUnavailable {
				return nil, nil, nil, "", errors.New("手机上的 Murong ADB 节点未在 35 秒内启动；已使用 Root 启动仍失败，请确认应用版本")
			}
			return nil, nil, nil, "", errors.New("手机上的 Murong ADB 节点未在 35 秒内启动；请确认应用版本和后台启动权限")
		}
	}
	return target, challenge, cleanup, device.Serial, nil
}

func detectADBRoot(ctx context.Context, adbPath, serial string) adbRootMode {
	if output, err := runADB(ctx, adbPath, "-s", serial, "shell", "id", "-u"); err == nil && isRootUID(output) {
		return adbRootShell
	}
	probeContext, cancel := context.WithTimeout(ctx, adbRootProbeTimeout)
	defer cancel()
	if output, err := runADB(probeContext, adbPath, "-s", serial, "shell", "su", "-c", "id -u"); err == nil && isRootUID(output) {
		return adbRootSU
	}
	return adbRootUnavailable
}

func isRootUID(output string) bool {
	lines := strings.Fields(strings.TrimSpace(output))
	return len(lines) > 0 && lines[len(lines)-1] == "0"
}

func runADBRootCommand(ctx context.Context, adbPath, serial string, mode adbRootMode, command string) (string, error) {
	encoded := base64.StdEncoding.EncodeToString([]byte(command))
	decodePipeline := "printf %s " + encoded + " | base64 -d | "
	switch mode {
	case adbRootShell:
		return runADB(ctx, adbPath, "-s", serial, "shell", decodePipeline+"sh")
	case adbRootSU:
		return runADB(ctx, adbPath, "-s", serial, "shell", decodePipeline+"su -c sh")
	default:
		return "", errors.New("ADB Shell 没有 Root 权限")
	}
}

func startADBNode(ctx context.Context, adbPath, serial string, rootMode adbRootMode, challengeText string) error {
	var rootErr error
	if rootMode != adbRootUnavailable {
		rootCommand := "cmd package unstop --user 0 com.murong.agent; cmd deviceidle tempwhitelist -d 120000 com.murong.agent >/dev/null 2>&1 || true; am unfreeze --sticky com.murong.agent >/dev/null 2>&1 || true"
		if _, err := runADBRootCommand(ctx, adbPath, serial, rootMode, rootCommand); err != nil {
			rootErr = err
		}
	}
	// Android 12+ only permits the app to promote its service while it has a
	// user-visible activity (unless a separate companion-device association
	// exists). Root can unstop the package, but it must not bypass that rule by
	// starting the service directly: ColorOS accepts the command and then
	// freezes the non-foreground process a few seconds later.
	if _, err := runADB(
		ctx, adbPath, "-s", serial, "shell", "am", "start", "-W",
		"-a", adbPairAction, "-n", adbBootstrapActivity, "--es", adbPairChallengeExtra, challengeText,
	); err != nil {
		if rootErr != nil {
			return fmt.Errorf("无法让手机启动 Murong ADB 节点（Root 启动：%v；兼容启动：%w）", rootErr, err)
		}
		return fmt.Errorf("无法让手机启动 Murong ADB 节点，请确认已安装新版应用：%w", err)
	}
	return nil
}

func ensureADBNotificationPermission(ctx context.Context, adbPath, serial string, rootMode adbRootMode) error {
	versionText, err := runADB(ctx, adbPath, "-s", serial, "shell", "getprop", "ro.build.version.sdk")
	if err != nil {
		return fmt.Errorf("无法读取 Android 版本：%w", err)
	}
	sdk, err := strconv.Atoi(strings.TrimSpace(versionText))
	if err != nil {
		return errors.New("手机返回了无效的 Android SDK 版本")
	}
	if sdk < 33 {
		return nil
	}
	if rootMode != adbRootUnavailable {
		if _, err := runADBRootCommand(
			ctx, adbPath, serial, rootMode,
			"pm grant com.murong.agent android.permission.POST_NOTIFICATIONS",
		); err == nil {
			return nil
		}
	}
	arguments := []string{"-s", serial, "shell", "pm", "grant", "com.murong.agent", "android.permission.POST_NOTIFICATIONS"}
	if _, err := runADB(ctx, adbPath, arguments...); err == nil {
		return nil
	}
	if _, err := runADB(
		ctx, adbPath, "-s", serial, "shell", "su", "-c",
		"pm grant com.murong.agent android.permission.POST_NOTIFICATIONS",
	); err != nil {
		return errors.New("无法授予电脑节点通知权限；请在手机系统设置中允许 Murong 通知后重试")
	}
	return nil
}

func adbProofForRequest(challenge []byte, request connectionRequest) (string, error) {
	if len(challenge) != adbChallengeBytes {
		return "", errors.New("ADB 一次性挑战长度无效")
	}
	payload := []byte(adbProofContext + "\n" + request.RequestID + "\n" + request.DeviceID + "\n" + request.EphemeralPublicKey)
	mac := hmac.New(sha256.New, challenge)
	_, _ = mac.Write(payload)
	return base64.RawURLEncoding.EncodeToString(mac.Sum(nil)), nil
}

func parseADBDevices(output string) []RemoteADBDevice {
	devices := make([]RemoteADBDevice, 0)
	for _, rawLine := range strings.Split(strings.ReplaceAll(output, "\r\n", "\n"), "\n") {
		line := strings.TrimSpace(rawLine)
		if line == "" || strings.HasPrefix(line, "List of devices attached") || strings.HasPrefix(line, "*") {
			continue
		}
		fields := strings.Fields(line)
		if len(fields) < 2 {
			continue
		}
		device := RemoteADBDevice{Serial: fields[0], State: fields[1], Authorized: fields[1] == "device"}
		for _, field := range fields[2:] {
			key, value, ok := strings.Cut(field, ":")
			if !ok {
				continue
			}
			switch key {
			case "model":
				device.Model = strings.ReplaceAll(value, "_", " ")
			case "product":
				device.Product = value
			case "device":
				device.Device = value
			case "transport_id":
				device.TransportID = value
			}
		}
		devices = append(devices, device)
	}
	sort.Slice(devices, func(left, right int) bool {
		if devices[left].Authorized != devices[right].Authorized {
			return devices[left].Authorized
		}
		return devices[left].Serial < devices[right].Serial
	})
	return devices
}

func selectADBDevice(devices []RemoteADBDevice, requestedSerial string) (RemoteADBDevice, error) {
	requestedSerial = strings.TrimSpace(requestedSerial)
	if requestedSerial != "" {
		for _, device := range devices {
			if device.Serial != requestedSerial {
				continue
			}
			if !device.Authorized {
				return RemoteADBDevice{}, fmt.Errorf("ADB 设备 %s 尚未授权（状态：%s），请在手机上允许这台电脑", requestedSerial, device.State)
			}
			return device, nil
		}
		return RemoteADBDevice{}, fmt.Errorf("未找到 ADB 设备 %s", requestedSerial)
	}
	authorized := make([]RemoteADBDevice, 0, len(devices))
	for _, device := range devices {
		if device.Authorized {
			authorized = append(authorized, device)
		}
	}
	switch len(authorized) {
	case 0:
		return RemoteADBDevice{}, errors.New("没有已授权的 ADB 设备；请连接手机并在手机上允许 USB 或无线调试")
	case 1:
		return authorized[0], nil
	default:
		return RemoteADBDevice{}, errors.New("检测到多台已授权的 ADB 设备，请先选择要连接的手机")
	}
}

func findADBExecutable() (string, error) {
	names := []string{"adb"}
	if runtime.GOOS == "windows" {
		names = []string{"adb.exe", "adb"}
	}
	for _, name := range names {
		if path, err := exec.LookPath(name); err == nil {
			return path, nil
		}
	}
	executable := "adb"
	if runtime.GOOS == "windows" {
		executable = "adb.exe"
	}
	candidates := []string{
		filepath.Join(strings.TrimSpace(os.Getenv("ANDROID_SDK_ROOT")), "platform-tools", executable),
		filepath.Join(strings.TrimSpace(os.Getenv("ANDROID_HOME")), "platform-tools", executable),
	}
	if localAppData := strings.TrimSpace(os.Getenv("LOCALAPPDATA")); localAppData != "" {
		candidates = append(candidates, filepath.Join(localAppData, "Android", "Sdk", "platform-tools", executable))
	}
	for _, candidate := range candidates {
		if candidate == "" {
			continue
		}
		if info, err := os.Stat(candidate); err == nil && !info.IsDir() {
			return candidate, nil
		}
	}
	return "", errors.New("未找到 adb；请安装 Android Platform Tools，或把 adb 加入 PATH")
}

func runADB(ctx context.Context, executable string, arguments ...string) (string, error) {
	command := exec.CommandContext(ctx, executable, arguments...)
	prepareHiddenCommand(command)
	output, err := command.CombinedOutput()
	text := strings.TrimSpace(string(output))
	if err != nil {
		if text != "" {
			return text, fmt.Errorf("%w：%s", err, truncateRunes(text, 500))
		}
		return text, err
	}
	return text, nil
}

func waitForADBEndpoint(ctx context.Context, target *url.URL, timeout time.Duration) error {
	deadline := time.Now().Add(timeout)
	client := &http.Client{Timeout: 750 * time.Millisecond}
	endpoint := target.ResolveReference(&url.URL{Path: "/api/v1/public/status"}).String()
	for {
		request, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint, nil)
		if err != nil {
			return err
		}
		response, requestErr := client.Do(request)
		if requestErr == nil {
			_ = response.Body.Close()
			if response.StatusCode == http.StatusOK {
				return nil
			}
		}
		if time.Now().After(deadline) {
			return errors.New("手机上的 Murong ADB 节点尚未启动")
		}
		timer := time.NewTimer(250 * time.Millisecond)
		select {
		case <-ctx.Done():
			timer.Stop()
			return ctx.Err()
		case <-timer.C:
		}
	}
}
