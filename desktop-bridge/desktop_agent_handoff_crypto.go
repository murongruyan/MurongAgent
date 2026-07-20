package desktopbridge

import (
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"
)

type desktopAgentHandoffPayload struct {
	HandoffToken    string `json:"handoffToken"`
	PortableSession string `json:"portableSession,omitempty"`
}

func isDesktopHandoffOperation(operation string) bool {
	switch strings.ToLower(strings.TrimSpace(operation)) {
	case "begin_handoff", "return_handoff", "abort_handoff":
		return true
	default:
		return false
	}
}

func (node *computerNode) prepareDesktopHandoffCommand(command *DesktopAgentCommand) ([]byte, error) {
	if command == nil || !isDesktopHandoffOperation(command.Operation) {
		return nil, nil
	}
	if strings.TrimSpace(node.config.ProtectedSyncKey) == "" {
		return nil, errors.New("当前电脑配对没有安全同步密钥，请清除配对后重新连接")
	}
	key, err := unprotectSecret(node.config.ProtectedSyncKey)
	if err != nil || len(key) != 32 {
		clearBytes(key)
		return nil, errors.New("无法解密设备同步密钥，请重新配对")
	}
	operation := strings.ToLower(strings.TrimSpace(command.Operation))
	if command.HandoffToken != "" || command.PortableSession != "" {
		clearBytes(key)
		return nil, errors.New("跨端接管能力不得通过明文命令传输")
	}
	if operation == "begin_handoff" {
		if command.HandoffEnvelope != nil {
			clearBytes(key)
			return nil, errors.New("开始接管命令包含了未请求的密文")
		}
		return key, nil
	}
	envelope := command.HandoffEnvelope
	if envelope == nil || envelope.RequestID != command.RequestID || envelope.Direction != deviceSyncHandoffToDesktop {
		clearBytes(key)
		return nil, errors.New("跨端接管命令缺少有效的加密内容")
	}
	now := time.Now().UnixMilli()
	if envelope.IssuedAt < now-deviceSyncClockWindow.Milliseconds() || envelope.IssuedAt > now+30_000 {
		clearBytes(key)
		return nil, errors.New("跨端接管命令已过期")
	}
	plain, err := decryptDeviceSync(key, *envelope)
	if err != nil {
		clearBytes(key)
		return nil, fmt.Errorf("无法解密跨端接管命令：%w", err)
	}
	defer clearBytes(plain)
	var payload desktopAgentHandoffPayload
	if err := strictUnmarshalDeviceSync(plain, &payload); err != nil {
		clearBytes(key)
		return nil, fmt.Errorf("跨端接管命令内容无效：%w", err)
	}
	command.HandoffToken = payload.HandoffToken
	command.PortableSession = payload.PortableSession
	command.HandoffEnvelope = nil
	return key, nil
}

func protectDesktopHandoffResult(command DesktopAgentCommand, result *DesktopAgentCommandResult, key []byte) error {
	if result == nil {
		return errors.New("桌面任务结果为空")
	}
	operation := strings.ToLower(strings.TrimSpace(command.Operation))
	result.HandoffEnvelope = nil
	if operation != "begin_handoff" || !result.Success {
		result.HandoffToken = ""
		result.PortableSession = ""
		return nil
	}
	payload := desktopAgentHandoffPayload{
		HandoffToken:    result.HandoffToken,
		PortableSession: result.PortableSession,
	}
	plain, err := json.Marshal(payload)
	if err != nil {
		return err
	}
	defer clearBytes(plain)
	envelope, err := encryptDeviceSync(
		key,
		command.RequestID,
		time.Now().UnixMilli(),
		deviceSyncHandoffToPhone,
		plain,
	)
	if err != nil {
		return err
	}
	result.HandoffToken = ""
	result.PortableSession = ""
	result.HandoffEnvelope = &envelope
	return nil
}
