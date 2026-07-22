package desktopbridge

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"io"
	"net/url"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"
)

func runCLI(args []string, stdin io.Reader, output io.Writer) error {
	defaultConfigPath, err := defaultNodeConfigPath()
	if err != nil {
		return fmt.Errorf("无法确定配置目录：%w", err)
	}
	flags := flag.NewFlagSet("murong-windows-node-cli", flag.ContinueOnError)
	flags.SetOutput(output)
	configPath := flags.String("config", defaultConfigPath, "节点配置文件路径")
	phone := flags.String("phone", "", "手机 Murong 地址，例如 http://192.168.1.20:8765")
	workspace := flags.String("workspace", "", "允许手机访问的电脑项目根目录")
	label := flags.String("label", "", "手机端显示的工作区名称")
	name := flags.String("name", "", "配对客户端名称")
	pairCode := flags.String("pair-code", "", "可选：手机临时验证码或安全密码；留空则发送连接申请")
	pairAuth := flags.String("pair-auth", "", "密码认证方式：temporary_code 或 security_password")
	allowWrite := flags.Bool("allow-write", false, "允许手机审批后写文件和创建目录")
	allowTerminal := flags.Bool("allow-terminal", false, "兼容开关：启用当前系统推荐 Shell（建议改用 --terminals）")
	terminals := flags.String("terminals", "", "允许的终端 ID，逗号分隔，例如 powershell7,wsl:Ubuntu")
	if err := flags.Parse(args); err != nil {
		if errors.Is(err, flag.ErrHelp) {
			return nil
		}
		return err
	}

	visited := map[string]bool{}
	flags.Visit(func(value *flag.Flag) { visited[value.Name] = true })
	config, err := loadNodeConfig(*configPath)
	if err != nil {
		return fmt.Errorf("无法读取节点配置：%w", err)
	}
	previousPhone := config.PhoneURL
	if strings.TrimSpace(*phone) != "" {
		config.PhoneURL = strings.TrimSpace(*phone)
	}
	if strings.TrimSpace(*workspace) != "" {
		config.Workspace = strings.TrimSpace(*workspace)
	}
	if strings.TrimSpace(*label) != "" {
		config.Label = strings.TrimSpace(*label)
	}
	if strings.TrimSpace(*name) != "" {
		config.ClientName = strings.TrimSpace(*name)
	}
	if strings.TrimSpace(*pairAuth) != "" {
		config.PairingAuthMethod = strings.TrimSpace(*pairAuth)
	}
	if visited["allow-write"] {
		config.AllowWrite = *allowWrite
	}
	if visited["allow-terminal"] {
		config.AllowTerminal = *allowTerminal
		if !*allowTerminal {
			config.TerminalBackends = nil
		}
	}
	if visited["terminals"] {
		config.TerminalBackends = splitTerminalIDs(*terminals)
		config.AllowTerminal = len(config.TerminalBackends) > 0
	}
	if previousPhone != "" && config.PhoneURL != previousPhone {
		config.ProtectedToken = ""
	}

	code := strings.TrimSpace(*pairCode)
	_ = stdin
	prepareContext, prepareCancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer prepareCancel()
	launch, err := prepareNode(prepareContext, *configPath, config, code)
	if err != nil {
		return err
	}
	defer launch.api.Close()
	_, _ = fmt.Fprintf(output, "Murong Desktop Node 已启动\n手机节点：%s\n电脑工作区：%s\n能力：读取=开启 写入=%s 电脑终端=%s\n终端后端：%s\n", launch.target.Redacted(), launch.workspace.root, onOff(launch.config.AllowWrite), onOff(launch.config.AllowTerminal), strings.Join(launch.config.TerminalBackends, ", "))

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	if err := launch.node.run(ctx); err != nil && !errors.Is(err, context.Canceled) {
		return fmt.Errorf("节点已停止：%w", err)
	}
	return nil
}

func splitTerminalIDs(value string) []string {
	result := make([]string, 0)
	seen := map[string]bool{}
	for _, item := range strings.Split(value, ",") {
		item = strings.TrimSpace(item)
		if item != "" && !seen[item] {
			seen[item] = true
			result = append(result, item)
		}
	}
	return result
}

func onOff(value bool) string {
	if value {
		return "开启"
	}
	return "关闭"
}

func truncateRunes(value string, limit int) string {
	runes := []rune(strings.TrimSpace(value))
	if len(runes) > limit {
		runes = runes[:limit]
	}
	return string(runes)
}

func validatePhoneURL(raw string) (*url.URL, error) {
	return parsePrivatePhoneURL(raw)
}
