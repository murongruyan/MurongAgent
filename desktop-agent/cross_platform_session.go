package main

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"
)

const (
	crossPlatformSessionFormat  = "murong-portable-session"
	crossPlatformSessionVersion = 1
	crossPlatformSourceWindows  = "windows"
	crossPlatformSourceMacOS    = "darwin"
	crossPlatformSourceLinux    = "linux"
	crossPlatformSourceAndroid  = "android"
	maxCrossPlatformSummary     = 1024 * 1024
)

type CrossPlatformSessionEnvelope struct {
	Format         string               `json:"format"`
	FormatVersion  int                  `json:"formatVersion"`
	ExportedAt     int64                `json:"exportedAtEpochMillis"`
	SourcePlatform string               `json:"sourcePlatform"`
	Session        CrossPlatformSession `json:"session"`
}

type CrossPlatformSession struct {
	Title       string                    `json:"title"`
	CreatedAt   int64                     `json:"createdAtEpochMillis"`
	UpdatedAt   int64                     `json:"updatedAtEpochMillis"`
	ProviderID  string                    `json:"providerId,omitempty"`
	ModelName   string                    `json:"modelName,omitempty"`
	Goal        string                    `json:"goal,omitempty"`
	Messages    []CrossPlatformMessage    `json:"messages"`
	Usage       CrossPlatformUsage        `json:"usage,omitempty"`
	Compression *CrossPlatformCompression `json:"compression,omitempty"`
}

type CrossPlatformMessage struct {
	Role      string `json:"role"`
	Content   string `json:"content"`
	CreatedAt int64  `json:"createdAtEpochMillis"`
	Kind      string `json:"kind,omitempty"`
	ToolName  string `json:"toolName,omitempty"`
}

type CrossPlatformUsage struct {
	ModelRequests         int   `json:"modelRequests,omitempty"`
	ReportedUsageRequests int   `json:"reportedUsageRequests,omitempty"`
	InputTokens           int64 `json:"inputTokens,omitempty"`
	OutputTokens          int64 `json:"outputTokens,omitempty"`
	TotalTokens           int64 `json:"totalTokens,omitempty"`
	CachedInputTokens     int64 `json:"cachedInputTokens,omitempty"`
	ReasoningOutputTokens int64 `json:"reasoningOutputTokens,omitempty"`
}

type CrossPlatformCompression struct {
	Version            int    `json:"version"`
	Summary            string `json:"summary"`
	SourceMessageCount int    `json:"sourceMessageCount"`
	CreatedAt          int64  `json:"createdAtEpochMillis"`
	Active             bool   `json:"active"`
}

func encodeCrossPlatformSession(session *ChatSession) ([]byte, error) {
	if err := validatePortableSession(session); err != nil {
		return nil, err
	}
	messages := portableCrossPlatformMessages(session.Messages)
	usage := normalizeSessionUsage(session.Usage)
	portable := CrossPlatformSession{
		Title: session.Title, CreatedAt: session.CreatedAt, UpdatedAt: session.UpdatedAt,
		ProviderID: usage.LastProviderID, ModelName: usage.LastModel, Goal: session.Goal,
		Messages: messages,
		Usage: CrossPlatformUsage{
			ModelRequests: usage.ModelRequests, ReportedUsageRequests: usage.ReportedUsageRequests,
			InputTokens: usage.InputTokens, OutputTokens: usage.OutputTokens, TotalTokens: usage.TotalTokens,
			CachedInputTokens: usage.CachedInputTokens, ReasoningOutputTokens: usage.ReasoningOutputTokens,
		},
	}
	if strings.TrimSpace(session.Compression.Summary) != "" {
		portable.Compression = &CrossPlatformCompression{
			Version: session.Compression.Version, Summary: session.Compression.Summary,
			SourceMessageCount: session.Compression.SourceMessageCount,
			CreatedAt:          session.Compression.CreatedAt, Active: session.Compression.Active,
		}
	}
	envelope := CrossPlatformSessionEnvelope{
		Format: crossPlatformSessionFormat, FormatVersion: crossPlatformSessionVersion,
		ExportedAt: time.Now().UnixMilli(), SourcePlatform: desktopSourcePlatform(), Session: portable,
	}
	if err := validateCrossPlatformEnvelope(&envelope); err != nil {
		return nil, err
	}
	return json.MarshalIndent(envelope, "", "  ")
}

func portableCrossPlatformMessages(source []ChatMessage) []CrossPlatformMessage {
	messages := make([]CrossPlatformMessage, 0, len(source))
	for _, message := range source {
		content := message.Content
		if names := desktopMessageAttachmentNames(message.ImageAttachments); len(names) > 0 {
			if strings.TrimSpace(content) != "" {
				content += "\n\n"
			}
			content += "[跨端图片附件：" + strings.Join(names, "、") + "。图片文件未随会话传输。]"
		}
		messages = append(messages, CrossPlatformMessage{
			Role: message.Role, Content: content, CreatedAt: message.CreatedAt,
			Kind: message.Kind, ToolName: message.ToolName,
		})
	}
	return messages
}

func decodeCrossPlatformSession(data []byte) (*ChatSession, error) {
	if len(data) == 0 || len(data) > maxPortableSessionBytes {
		return nil, errors.New("跨端会话文件为空或超过 32 MiB")
	}
	decoder := json.NewDecoder(bytes.NewReader(data))
	decoder.DisallowUnknownFields()
	var envelope CrossPlatformSessionEnvelope
	if err := decoder.Decode(&envelope); err != nil {
		return nil, fmt.Errorf("跨端会话 JSON 无效：%w", err)
	}
	if err := ensureJSONEOF(decoder); err != nil {
		return nil, err
	}
	if err := validateCrossPlatformEnvelope(&envelope); err != nil {
		return nil, err
	}
	session := &ChatSession{
		ID: "portable-session", Title: truncateRunes(strings.TrimSpace(envelope.Session.Title), 500),
		CreatedAt: envelope.Session.CreatedAt, UpdatedAt: envelope.Session.UpdatedAt,
		Goal: truncateRunes(envelope.Session.Goal, 20_000),
		Usage: SessionUsage{
			ModelRequests:         envelope.Session.Usage.ModelRequests,
			ReportedUsageRequests: envelope.Session.Usage.ReportedUsageRequests,
			InputTokens:           envelope.Session.Usage.InputTokens, OutputTokens: envelope.Session.Usage.OutputTokens,
			TotalTokens: envelope.Session.Usage.TotalTokens, CachedInputTokens: envelope.Session.Usage.CachedInputTokens,
			ReasoningOutputTokens: envelope.Session.Usage.ReasoningOutputTokens,
			LastProviderID:        envelope.Session.ProviderID, LastModel: envelope.Session.ModelName,
		},
		Messages: make([]ChatMessage, 0, len(envelope.Session.Messages)),
	}
	for index, message := range envelope.Session.Messages {
		session.Messages = append(session.Messages, ChatMessage{
			ID: fmt.Sprintf("portable-message-%d", index+1), Role: message.Role,
			Content: message.Content, CreatedAt: message.CreatedAt, Kind: message.Kind, ToolName: message.ToolName,
		})
	}
	if compression := envelope.Session.Compression; compression != nil {
		candidate := SessionCompression{
			Version: compression.Version, Summary: compression.Summary,
			SourceMessageCount: compression.SourceMessageCount,
			SourceEndMessageID: session.Messages[compression.SourceMessageCount-1].ID,
			CreatedAt:          compression.CreatedAt, Active: compression.Active, Method: "local",
		}
		if validateSessionCompression(candidate, session.Messages) == nil {
			session.Compression = candidate
		}
	}
	if err := validatePortableSession(session); err != nil {
		return nil, fmt.Errorf("跨端会话映射无效：%w", err)
	}
	return session, nil
}

func decodeImportableSession(data []byte) (*ChatSession, error) {
	var header struct {
		Format string `json:"format"`
	}
	if err := json.Unmarshal(data, &header); err != nil {
		return nil, fmt.Errorf("会话 JSON 无效：%w", err)
	}
	switch header.Format {
	case portableSessionFormat:
		return decodePortableSession(data)
	case crossPlatformSessionFormat:
		return decodeCrossPlatformSession(data)
	default:
		return nil, errors.New("不是受支持的 Murong 完整会话或跨端会话文件")
	}
}

func validateCrossPlatformEnvelope(envelope *CrossPlatformSessionEnvelope) error {
	if envelope == nil || envelope.Format != crossPlatformSessionFormat || envelope.FormatVersion != crossPlatformSessionVersion {
		return errors.New("不是受支持的 Murong 跨端会话文件")
	}
	if envelope.SourcePlatform != crossPlatformSourceWindows && envelope.SourcePlatform != crossPlatformSourceMacOS &&
		envelope.SourcePlatform != crossPlatformSourceLinux && envelope.SourcePlatform != crossPlatformSourceAndroid {
		return errors.New("跨端会话来源平台无效")
	}
	if envelope.ExportedAt <= 0 {
		return errors.New("跨端会话导出时间无效")
	}
	session := &envelope.Session
	if strings.TrimSpace(session.Title) == "" || len([]rune(session.Title)) > 500 || len([]rune(session.Goal)) > 20_000 {
		return errors.New("跨端会话标题或目标无效")
	}
	if session.CreatedAt < 0 || session.UpdatedAt < 0 || len(session.ProviderID) > 1_000 || len(session.ModelName) > 1_000 {
		return errors.New("跨端会话元数据无效")
	}
	if len(session.Messages) > maxPortableSessionMessage {
		return errors.New("跨端会话消息数量超过 50000 条")
	}
	for _, message := range session.Messages {
		if message.Role != "user" && message.Role != "assistant" && message.Role != "tool" {
			return errors.New("跨端会话包含未知消息角色")
		}
		if len(message.Content) > 4*1024*1024 || message.CreatedAt < 0 || len(message.Kind) > 1_000 || len(message.ToolName) > 1_000 {
			return errors.New("跨端会话包含过大或无效消息")
		}
	}
	usage := session.Usage
	if usage.ModelRequests < 0 || usage.ReportedUsageRequests < 0 || usage.ReportedUsageRequests > usage.ModelRequests ||
		usage.InputTokens < 0 || usage.OutputTokens < 0 || usage.TotalTokens < 0 ||
		usage.CachedInputTokens < 0 || usage.ReasoningOutputTokens < 0 {
		return errors.New("跨端会话用量无效")
	}
	if compression := session.Compression; compression != nil {
		if compression.Version <= 0 || strings.TrimSpace(compression.Summary) == "" || len(compression.Summary) > maxCrossPlatformSummary ||
			compression.SourceMessageCount <= 0 || compression.SourceMessageCount > len(session.Messages) || compression.CreatedAt < 0 {
			return errors.New("跨端会话摘要无效")
		}
	}
	return nil
}
