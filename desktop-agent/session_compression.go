package main

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"time"
)

const (
	minSessionMessagesForCompression = 12
	minSessionMessagesToCompress     = 6
	recentSessionMessagesToKeep      = 8
	maxSessionCompressionSummary     = 60_000
	maxSessionCompressionSource      = 28_000
)

type SetSessionCompressionRequest struct {
	SessionID string `json:"sessionId"`
	Active    bool   `json:"active"`
}

type sessionCompressionPlan struct {
	Session            *ChatSession
	CompressedMessages []ChatMessage
	SourceEndMessageID string
}

func buildSessionCompressionPlan(session *ChatSession) (sessionCompressionPlan, error) {
	if session == nil {
		return sessionCompressionPlan{}, errors.New("会话不存在")
	}
	eligible := make([]ChatMessage, 0, len(session.Messages))
	for _, message := range session.Messages {
		if (message.Role == "user" || message.Role == "assistant" || message.Role == "tool") && strings.TrimSpace(message.Content) != "" {
			eligible = append(eligible, message)
		}
	}
	if len(eligible) < minSessionMessagesForCompression {
		return sessionCompressionPlan{}, errors.New("消息还不够多，至少需要 12 条才能压缩")
	}
	keep := min(recentSessionMessagesToKeep, len(eligible)-minSessionMessagesToCompress)
	compressed := eligible[:len(eligible)-keep]
	if len(compressed) < minSessionMessagesToCompress {
		return sessionCompressionPlan{}, errors.New("可压缩的历史消息少于 6 条")
	}
	return sessionCompressionPlan{
		Session: cloneSession(session), CompressedMessages: append([]ChatMessage(nil), compressed...),
		SourceEndMessageID: compressed[len(compressed)-1].ID,
	}, nil
}

func buildLocalSessionCompressionSummary(session *ChatSession, messages []ChatMessage) string {
	userNeeds := make([]string, 0, 6)
	findings := make([]string, 0, 8)
	tools := make([]string, 0, 6)
	for _, message := range messages {
		content := compactCompressionText(messageContentForCompression(message), 520)
		if content == "" {
			continue
		}
		switch message.Role {
		case "user":
			userNeeds = appendBoundedDistinct(userNeeds, content, 6)
		case "assistant":
			findings = appendBoundedDistinct(findings, content, 8)
		case "tool":
			label := strings.TrimSpace(message.ToolName)
			if label == "" {
				label = "工具"
			}
			tools = appendBoundedDistinct(tools, label+": "+compactCompressionText(content, 280), 6)
		}
	}
	var output strings.Builder
	output.WriteString("【上下文压缩摘要】\n")
	output.WriteString("会话标题: ")
	output.WriteString(session.Title)
	output.WriteString("\n当前阶段: 已压缩较早历史，继续结合最近未压缩消息推进\n")
	output.WriteString(fmt.Sprintf("压缩消息数: %d\n", len(messages)))
	if goal := strings.TrimSpace(session.Goal); goal != "" {
		output.WriteString("长期目标: ")
		output.WriteString(compactCompressionText(goal, 700))
		output.WriteString("\n")
	}
	writeCompressionList(&output, "关键用户需求", userNeeds)
	writeCompressionList(&output, "已完成工作与重要结论", findings)
	writeCompressionList(&output, "工具活动与证据", tools)
	output.WriteString("待继续事项:\n- 优先处理最近未压缩消息中的最新要求。\n")
	output.WriteString("续聊要求:\n- 继续遵守现有规则、记忆、Skills 和审批限制。\n- 如果摘要缺少关键细节，先询问用户，不要编造。")
	return truncateRunes(output.String(), maxSessionCompressionSummary)
}

func buildSessionCompressionPrompt(session *ChatSession, messages []ChatMessage, localSummary string) []modelMessage {
	var transcript strings.Builder
	for _, message := range messages {
		label := map[string]string{"user": "用户", "assistant": "Murong", "tool": "工具"}[message.Role]
		if label == "" {
			continue
		}
		transcript.WriteString("[")
		transcript.WriteString(label)
		if message.ToolName != "" {
			transcript.WriteString("/")
			transcript.WriteString(message.ToolName)
		}
		transcript.WriteString("] ")
		transcript.WriteString(compactCompressionText(messageContentForCompression(message), 2_000))
		transcript.WriteString("\n\n")
		if len([]rune(transcript.String())) >= maxSessionCompressionSource {
			break
		}
	}
	source := fmt.Sprintf("会话标题: %s\n长期目标: %s\n本地摘要草稿:\n%s\n\n待压缩历史摘录:\n%s",
		session.Title, compactCompressionText(session.Goal, 1_000), localSummary, truncateRunes(transcript.String(), maxSessionCompressionSource))
	if previous := strings.TrimSpace(session.Compression.Summary); previous != "" {
		source += "\n\n上一版摘要（合并仍有效内容并去掉过时重复项）:\n" + truncateRunes(previous, 8_000)
	}
	return []modelMessage{
		{Role: "system", Content: `你是会话压缩摘要助手。只根据给定材料生成中文续聊摘要，不得编造。保留长期目标、用户要求、已完成工作、关键文件或命令、风险、审批边界和待办。输出结构清晰、简洁，供编码 Agent 直接继续工作。`},
		{Role: "user", Content: source},
	}
}

func messageContentForCompression(message ChatMessage) string {
	content := message.Content
	if summary := workspaceChangeSummary(message.WorkspaceChanges, message.WorkspaceChangesOmitted); summary != "" {
		content += "\n\n" + summary
	}
	return content
}

func (store *desktopStore) sessionCompressionPlan(id string) (sessionCompressionPlan, error) {
	session := store.getSession(strings.TrimSpace(id))
	return buildSessionCompressionPlan(session)
}

func (store *desktopStore) saveSessionCompression(id, expectedEndID, summary, method string) (*ChatSession, error) {
	store.mu.Lock()
	defer store.mu.Unlock()
	session := store.sessions[strings.TrimSpace(id)]
	if session == nil {
		return nil, errors.New("会话不存在")
	}
	if err := requireDesktopExecutionAuthority(session); err != nil {
		return nil, err
	}
	plan, err := buildSessionCompressionPlan(session)
	if err != nil {
		return nil, err
	}
	if plan.SourceEndMessageID != strings.TrimSpace(expectedEndID) {
		return nil, errors.New("压缩期间会话历史发生变化，请重试")
	}
	summary = strings.TrimSpace(summary)
	if len([]rune(summary)) < 40 || len([]rune(summary)) > maxSessionCompressionSummary {
		return nil, errors.New("生成的上下文摘要为空、过短或过长")
	}
	method = strings.ToLower(strings.TrimSpace(method))
	if method != "provider" {
		method = "local"
	}
	previous := session.Compression
	version := previous.Version + 1
	if version < 1 {
		version = 1
	}
	session.Compression = SessionCompression{
		Version: version, Summary: summary, SourceMessageCount: len(plan.CompressedMessages),
		SourceEndMessageID: plan.SourceEndMessageID, CreatedAt: time.Now().UnixMilli(), Active: true, Method: method,
	}
	if err := store.saveSessionsLocked(); err != nil {
		session.Compression = previous
		return nil, err
	}
	return cloneSession(session), nil
}

func (store *desktopStore) setSessionCompressionActive(id string, active bool) (*ChatSession, error) {
	store.mu.Lock()
	defer store.mu.Unlock()
	session := store.sessions[strings.TrimSpace(id)]
	if session == nil {
		return nil, errors.New("会话不存在")
	}
	if err := requireDesktopExecutionAuthority(session); err != nil {
		return nil, err
	}
	if strings.TrimSpace(session.Compression.Summary) == "" {
		return nil, errors.New("当前任务还没有上下文摘要")
	}
	previous := session.Compression.Active
	session.Compression.Active = active
	if err := store.saveSessionsLocked(); err != nil {
		session.Compression.Active = previous
		return nil, err
	}
	return cloneSession(session), nil
}

func sessionHistoryForModel(session *ChatSession) ([]ChatMessage, string) {
	if session == nil || !session.Compression.Active || strings.TrimSpace(session.Compression.Summary) == "" {
		if session == nil {
			return nil, ""
		}
		return session.Messages, ""
	}
	for index := range session.Messages {
		if session.Messages[index].ID == session.Compression.SourceEndMessageID {
			context := "上下文压缩摘要：\n" + session.Compression.Summary +
				"\n\n请结合这份摘要和最近未压缩消息继续工作；缺少历史细节时先询问用户，不要编造。"
			return session.Messages[index+1:], context
		}
	}
	return session.Messages, ""
}

func normalizeSessionCompression(compression SessionCompression, messages []ChatMessage) SessionCompression {
	compression.Summary = strings.TrimSpace(compression.Summary)
	compression.SourceEndMessageID = strings.TrimSpace(compression.SourceEndMessageID)
	compression.Method = strings.ToLower(strings.TrimSpace(compression.Method))
	if validateSessionCompression(compression, messages) != nil {
		return SessionCompression{}
	}
	return compression
}

func remapSessionCompression(compression SessionCompression, sourceMessages, targetMessages []ChatMessage) SessionCompression {
	if compression == (SessionCompression{}) {
		return compression
	}
	cutoff := -1
	for index := range sourceMessages {
		if sourceMessages[index].ID == compression.SourceEndMessageID {
			cutoff = index
			break
		}
	}
	if cutoff < 0 || cutoff >= len(targetMessages) {
		return SessionCompression{}
	}
	compression.SourceEndMessageID = targetMessages[cutoff].ID
	return normalizeSessionCompression(compression, targetMessages)
}

func validateSessionCompression(compression SessionCompression, messages []ChatMessage) error {
	if compression == (SessionCompression{}) {
		return nil
	}
	if compression.Version < 1 || compression.Version > 1_000_000 || compression.SourceMessageCount < minSessionMessagesToCompress || compression.SourceMessageCount > len(messages) {
		return errors.New("会话上下文摘要版本或消息范围无效")
	}
	if len([]rune(strings.TrimSpace(compression.Summary))) < 40 || len([]rune(compression.Summary)) > maxSessionCompressionSummary || strings.TrimSpace(compression.SourceEndMessageID) == "" {
		return errors.New("会话上下文摘要内容无效")
	}
	if compression.Method != "local" && compression.Method != "provider" {
		return errors.New("会话上下文摘要来源无效")
	}
	found := false
	for _, message := range messages {
		if message.ID == compression.SourceEndMessageID {
			found = true
			break
		}
	}
	if !found {
		return errors.New("会话上下文摘要截止消息不存在")
	}
	return nil
}

func (app *DesktopAgentApp) CompressSession(id string) (*ChatSession, error) {
	id = strings.TrimSpace(id)
	ctx, cancel, err := app.beginSessionMaintenance(id)
	if err != nil {
		return nil, err
	}
	defer cancel()
	defer app.finishRun(id)
	app.emit("agent:status", map[string]any{"sessionId": id, "state": "running", "text": "正在整理历史摘要"})
	completed := false
	defer func() {
		if !completed {
			app.emit("agent:status", map[string]any{"sessionId": id, "state": "idle", "text": "摘要未更新"})
		}
	}()

	plan, err := app.store.sessionCompressionPlan(id)
	if err != nil {
		return nil, err
	}
	localSummary := buildLocalSessionCompressionSummary(plan.Session, plan.CompressedMessages)
	summary, method := localSummary, "local"
	config := app.store.rawConfig()
	if profile := findProviderProfile(config.ProviderProfiles, config.ActiveProviderProfileID); profile != nil && profile.ProtectedAPIKey != "" {
		if plain, decryptErr := unprotectSecret(profile.ProtectedAPIKey); decryptErr == nil {
			result, requestErr := newModelClientWithGeneration(config.Temperature, config.MaxTokens).streamChat(
				ctx,
				*profile,
				string(plain),
				buildSessionCompressionPrompt(plan.Session, plan.CompressedMessages, localSummary),
				nil,
				nil,
			)
			if requestErr == nil {
				if usageErr := app.store.recordModelUsage(id, *profile, result.Usage); usageErr != nil {
					return nil, usageErr
				}
				if candidate := strings.TrimSpace(result.Content); len([]rune(candidate)) >= 120 {
					summary, method = truncateRunes(candidate, maxSessionCompressionSummary), "provider"
				}
			}
		}
	}
	updated, err := app.store.saveSessionCompression(id, plan.SourceEndMessageID, summary, method)
	if err != nil {
		return nil, err
	}
	completed = true
	app.emitSessionsChanged(updated)
	app.emit("agent:status", map[string]any{"sessionId": id, "state": "idle", "text": "已生成上下文摘要"})
	return updated, nil
}

func (app *DesktopAgentApp) SetSessionCompressionActive(request SetSessionCompressionRequest) (*ChatSession, error) {
	id := strings.TrimSpace(request.SessionID)
	if app.sessionRunActive(id) {
		return nil, errors.New("当前任务仍在运行，停止后才能调整上下文摘要")
	}
	updated, err := app.store.setSessionCompressionActive(id, request.Active)
	if err == nil {
		app.emitSessionsChanged(updated)
	}
	return updated, err
}

func (app *DesktopAgentApp) beginSessionMaintenance(id string) (context.Context, context.CancelFunc, error) {
	if app.store.getSession(id) == nil {
		return nil, nil, errors.New("会话不存在")
	}
	parent := app.ctx
	if parent == nil {
		parent = context.Background()
	}
	ctx, cancel := context.WithTimeout(parent, 15*time.Minute)
	app.mu.Lock()
	defer app.mu.Unlock()
	if _, running := app.runs[id]; running {
		cancel()
		return nil, nil, errors.New("当前任务仍在运行")
	}
	app.runs[id] = cancel
	return ctx, cancel, nil
}

func appendBoundedDistinct(values []string, value string, limit int) []string {
	for _, existing := range values {
		if existing == value {
			return values
		}
	}
	values = append(values, value)
	if len(values) > limit {
		values = values[len(values)-limit:]
	}
	return values
}

func compactCompressionText(value string, limit int) string {
	value = strings.Join(strings.Fields(strings.TrimSpace(value)), " ")
	return truncateRunes(value, limit)
}

func writeCompressionList(output *strings.Builder, title string, values []string) {
	if len(values) == 0 {
		return
	}
	output.WriteString(title)
	output.WriteString(":\n")
	for _, value := range values {
		output.WriteString("- ")
		output.WriteString(value)
		output.WriteString("\n")
	}
}
