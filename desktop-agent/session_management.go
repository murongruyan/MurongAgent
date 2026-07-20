package main

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"

	"github.com/wailsapp/wails/v2/pkg/runtime"
)

const (
	portableSessionFormat     = "murong-session"
	portableSessionVersion    = 1
	maxPortableSessionBytes   = 32 * 1024 * 1024
	maxPortableSessionMessage = 50_000
)

type RenameSessionRequest struct {
	SessionID string `json:"sessionId"`
	Title     string `json:"title"`
}

type SessionPointRequest struct {
	SessionID string `json:"sessionId"`
	MessageID string `json:"messageId,omitempty"`
	Confirmed bool   `json:"confirmed,omitempty"`
}

type ExportSessionRequest struct {
	SessionID string `json:"sessionId"`
	Format    string `json:"format"`
}

type PortableSessionEnvelope struct {
	Format        string       `json:"format"`
	FormatVersion int          `json:"formatVersion"`
	ExportedAt    int64        `json:"exportedAtEpochMillis"`
	Session       *ChatSession `json:"session"`
}

type SessionStats struct {
	ProjectPath            string `json:"projectPath,omitempty"`
	MessageCount           int    `json:"messageCount"`
	UserMessages           int    `json:"userMessages"`
	AssistantMessages      int    `json:"assistantMessages"`
	ToolMessages           int    `json:"toolMessages"`
	CharacterCount         int    `json:"characterCount"`
	EstimatedTokens        int    `json:"estimatedTokens"`
	ModelRequests          int    `json:"modelRequests"`
	ReportedUsageRequests  int    `json:"reportedUsageRequests"`
	ProviderInputTokens    int64  `json:"providerInputTokens"`
	ProviderOutputTokens   int64  `json:"providerOutputTokens"`
	ProviderTotalTokens    int64  `json:"providerTotalTokens"`
	CachedInputTokens      int64  `json:"cachedInputTokens"`
	ReasoningOutputTokens  int64  `json:"reasoningOutputTokens"`
	ProviderUsageAvailable bool   `json:"providerUsageAvailable"`
	ProviderUsageComplete  bool   `json:"providerUsageComplete"`
	LastProviderID         string `json:"lastProviderId,omitempty"`
	LastModel              string `json:"lastModel,omitempty"`
	CompressionAvailable   bool   `json:"compressionAvailable"`
	CompressionActive      bool   `json:"compressionActive"`
	CompressionVersion     int    `json:"compressionVersion,omitempty"`
	CompressionMessages    int    `json:"compressionMessages,omitempty"`
	CompressionMethod      string `json:"compressionMethod,omitempty"`
	CompressionCreatedAt   int64  `json:"compressionCreatedAt,omitempty"`
	CreatedAt              int64  `json:"createdAt"`
	UpdatedAt              int64  `json:"updatedAt"`
}

func (store *desktopStore) renameSession(id, title string) (*ChatSession, error) {
	store.mu.Lock()
	defer store.mu.Unlock()
	session := store.sessions[strings.TrimSpace(id)]
	if session == nil {
		return nil, errors.New("会话不存在")
	}
	if err := requireDesktopExecutionAuthority(session); err != nil {
		return nil, err
	}
	title = strings.TrimSpace(title)
	if title == "" {
		return nil, errors.New("任务名称不能为空")
	}
	if len([]rune(title)) > 60 {
		return nil, errors.New("任务名称不能超过 60 个字符")
	}
	previousTitle, previousUpdatedAt := session.Title, session.UpdatedAt
	session.Title = title
	session.UpdatedAt = time.Now().UnixMilli()
	if err := store.saveSessionsLocked(); err != nil {
		session.Title, session.UpdatedAt = previousTitle, previousUpdatedAt
		return nil, err
	}
	return cloneSession(session), nil
}

func (store *desktopStore) forkSession(id, messageID string) (*ChatSession, error) {
	store.mu.Lock()
	defer store.mu.Unlock()
	source := store.sessions[strings.TrimSpace(id)]
	if source == nil {
		return nil, errors.New("会话不存在")
	}
	end, err := sessionPointIndex(source, strings.TrimSpace(messageID), true)
	if err != nil {
		return nil, err
	}
	now := time.Now().UnixMilli()
	forked := &ChatSession{
		ID: newID("session"), Title: forkedSessionTitle(source.Title), CreatedAt: now, UpdatedAt: now,
		ProjectPath: source.ProjectPath, Goal: source.Goal, PlanModeEnabled: source.PlanModeEnabled,
		Usage: source.Usage, Messages: make([]ChatMessage, 0, end),
	}
	messageIDs := make(map[string]string, end)
	for index := 0; index < end; index++ {
		message := source.Messages[index]
		originalID := message.ID
		message.ID = newID("message")
		messageIDs[originalID] = message.ID
		message.Context = cloneComposerContext(message.Context)
		message.ImageAttachments = cloneImageAttachments(message.ImageAttachments)
		forked.Messages = append(forked.Messages, message)
	}
	forked.Compression = remapSessionCompression(source.Compression, source.Messages, forked.Messages)
	forked.Goal = sessionGoalFromMessages(forked.Messages)
	forked.WorkflowPlan = reconcileWorkflowPlanWithMessages(remapWorkflowPlanMessageIDs(source.WorkflowPlan, messageIDs), forked.Messages)
	store.sessions[forked.ID] = forked
	if err := store.saveSessionsLocked(); err != nil {
		delete(store.sessions, forked.ID)
		return nil, err
	}
	return cloneSession(forked), nil
}

func (store *desktopStore) rollbackSession(id, messageID string) (*ChatSession, error) {
	store.mu.Lock()
	defer store.mu.Unlock()
	id = strings.TrimSpace(id)
	session := store.sessions[id]
	if session == nil {
		return nil, errors.New("会话不存在")
	}
	if err := requireDesktopExecutionAuthority(session); err != nil {
		return nil, err
	}
	end, err := sessionPointIndex(session, strings.TrimSpace(messageID), false)
	if err != nil {
		return nil, err
	}
	previous := cloneSession(session)
	session.Messages = append([]ChatMessage(nil), session.Messages[:end]...)
	for index := range session.Messages {
		session.Messages[index].Context = cloneComposerContext(session.Messages[index].Context)
		session.Messages[index].ImageAttachments = cloneImageAttachments(session.Messages[index].ImageAttachments)
	}
	session.Goal = sessionGoalFromMessages(session.Messages)
	session.WorkflowPlan = reconcileWorkflowPlanWithMessages(session.WorkflowPlan, session.Messages)
	session.Compression = normalizeSessionCompression(session.Compression, session.Messages)
	session.CodexThreadID = ""
	session.CodexSyncedID = ""
	session.CodexToolsVersion = 0
	session.UpdatedAt = time.Now().UnixMilli()
	if err := store.saveSessionsLocked(); err != nil {
		store.sessions[id] = previous
		return nil, err
	}
	return cloneSession(session), nil
}

func (store *desktopStore) importSession(session *ChatSession) (*ChatSession, error) {
	if session == nil {
		return nil, errors.New("导入文件没有会话")
	}
	store.mu.Lock()
	defer store.mu.Unlock()
	now := time.Now().UnixMilli()
	imported := cloneSession(session)
	imported.ID = newID("session")
	imported.Title = importedSessionTitle(imported.Title)
	imported.CreatedAt = now
	imported.UpdatedAt = now
	imported.CodexThreadID = ""
	imported.CodexSyncedID = ""
	imported.CodexToolsVersion = 0
	imported.ExecutionHandoff = SessionExecutionHandoff{}
	imported.BackgroundSubagentJobs, _ = normalizeRestoredSubagentJobs(imported.BackgroundSubagentJobs, now)
	sourceMessages := append([]ChatMessage(nil), imported.Messages...)
	for index := range imported.Messages {
		imported.Messages[index].ID = newID("message")
		if imported.Messages[index].CreatedAt <= 0 {
			imported.Messages[index].CreatedAt = now + int64(index)
		}
	}
	imported.Compression = remapSessionCompression(imported.Compression, sourceMessages, imported.Messages)
	if messageGoal := sessionGoalFromMessages(imported.Messages); messageGoal != "" {
		imported.Goal = messageGoal
	} else {
		imported.Goal = truncateRunes(imported.Goal, 20_000)
	}
	store.sessions[imported.ID] = imported
	if err := store.saveSessionsLocked(); err != nil {
		delete(store.sessions, imported.ID)
		return nil, err
	}
	return cloneSession(imported), nil
}

func sessionPointIndex(session *ChatSession, messageID string, allowEnd bool) (int, error) {
	if session == nil {
		return 0, errors.New("会话不存在")
	}
	if messageID == "" {
		if allowEnd {
			return len(session.Messages), nil
		}
		return 0, errors.New("请选择要回退到的消息")
	}
	for index := range session.Messages {
		if session.Messages[index].ID == messageID {
			return index + 1, nil
		}
	}
	return 0, errors.New("消息不存在或已被移除")
}

func sessionGoalFromMessages(messages []ChatMessage) string {
	for index := len(messages) - 1; index >= 0; index-- {
		message := messages[index]
		if message.Role == "user" && (message.Mode == "goal" || message.Mode == "goal_plan") {
			return truncateRunes(message.Content, 20_000)
		}
	}
	return ""
}

func forkedSessionTitle(title string) string {
	return truncateRunes(strings.TrimSpace(title)+"（分叉）", 60)
}

func importedSessionTitle(title string) string {
	title = strings.TrimSpace(title)
	if title == "" {
		title = "导入任务"
	}
	return truncateRunes(title+"（导入）", 60)
}

func sessionStats(session *ChatSession) SessionStats {
	if session == nil {
		return SessionStats{}
	}
	usage := normalizeSessionUsage(session.Usage)
	result := SessionStats{
		ProjectPath: session.ProjectPath, MessageCount: len(session.Messages), CreatedAt: session.CreatedAt, UpdatedAt: session.UpdatedAt,
		ModelRequests: usage.ModelRequests, ReportedUsageRequests: usage.ReportedUsageRequests,
		ProviderInputTokens: usage.InputTokens, ProviderOutputTokens: usage.OutputTokens, ProviderTotalTokens: usage.TotalTokens,
		CachedInputTokens: usage.CachedInputTokens, ReasoningOutputTokens: usage.ReasoningOutputTokens,
		ProviderUsageAvailable: usage.ReportedUsageRequests > 0,
		ProviderUsageComplete:  usage.ModelRequests > 0 && usage.ReportedUsageRequests == usage.ModelRequests,
		LastProviderID:         usage.LastProviderID, LastModel: usage.LastModel,
		CompressionAvailable: strings.TrimSpace(session.Compression.Summary) != "",
		CompressionActive:    session.Compression.Active,
		CompressionVersion:   session.Compression.Version,
		CompressionMessages:  session.Compression.SourceMessageCount,
		CompressionMethod:    session.Compression.Method,
		CompressionCreatedAt: session.Compression.CreatedAt,
	}
	for _, message := range session.Messages {
		switch message.Role {
		case "user":
			result.UserMessages++
		case "assistant":
			result.AssistantMessages++
		case "tool":
			result.ToolMessages++
		}
		result.CharacterCount += utf8.RuneCountInString(message.Content)
		result.EstimatedTokens += estimateTextTokens(message.Content)
	}
	return result
}

func estimateTextTokens(value string) int {
	asciiRunes := 0
	tokens := 0
	flushASCII := func() {
		if asciiRunes > 0 {
			tokens += (asciiRunes + 3) / 4
			asciiRunes = 0
		}
	}
	for _, character := range value {
		if character <= unicode.MaxASCII {
			asciiRunes++
			continue
		}
		flushASCII()
		if unicode.Is(unicode.Han, character) || unicode.Is(unicode.Hiragana, character) || unicode.Is(unicode.Katakana, character) || unicode.Is(unicode.Hangul, character) {
			tokens++
		} else if !unicode.IsSpace(character) {
			tokens++
		}
	}
	flushASCII()
	return tokens
}

func encodePortableSession(session *ChatSession) ([]byte, error) {
	if err := validatePortableSession(session); err != nil {
		return nil, err
	}
	portable := clonePortableSession(session)
	envelope := PortableSessionEnvelope{
		Format: portableSessionFormat, FormatVersion: portableSessionVersion,
		ExportedAt: time.Now().UnixMilli(), Session: portable,
	}
	return json.MarshalIndent(envelope, "", "  ")
}

func clonePortableSession(session *ChatSession) *ChatSession {
	portable := cloneSession(session)
	if portable != nil {
		portable.CodexThreadID = ""
		portable.CodexSyncedID = ""
		portable.CodexToolsVersion = 0
		portable.ExecutionHandoff = SessionExecutionHandoff{}
		portable.WorkflowPlan = nil
		for index := range portable.Messages {
			message := &portable.Messages[index]
			message.ToolCallID = ""
			message.ToolArguments = ""
			message.ToolStatus = ""
			if len(message.ImageAttachments) > 0 {
				names := make([]string, 0, len(message.ImageAttachments))
				for _, attachment := range message.ImageAttachments {
					names = append(names, attachment.FileName)
				}
				note := "[本地图片附件未包含在可移植会话文件中：" + strings.Join(names, "、") + "]"
				message.Content = strings.TrimSpace(strings.TrimSpace(message.Content) + "\n\n" + note)
				message.ImageAttachments = nil
			}
		}
	}
	return portable
}

func decodePortableSession(data []byte) (*ChatSession, error) {
	if len(data) == 0 || len(data) > maxPortableSessionBytes {
		return nil, errors.New("会话文件为空或超过 32 MiB")
	}
	decoder := json.NewDecoder(bytes.NewReader(data))
	decoder.DisallowUnknownFields()
	var envelope PortableSessionEnvelope
	if err := decoder.Decode(&envelope); err != nil {
		return nil, fmt.Errorf("会话 JSON 无效：%w", err)
	}
	if err := ensureJSONEOF(decoder); err != nil {
		return nil, err
	}
	if envelope.Format != portableSessionFormat || envelope.FormatVersion != portableSessionVersion {
		return nil, errors.New("不是受支持的 Murong 会话文件")
	}
	if err := validatePortableSession(envelope.Session); err != nil {
		return nil, err
	}
	return cloneSession(envelope.Session), nil
}

func ensureJSONEOF(decoder *json.Decoder) error {
	var extra any
	if err := decoder.Decode(&extra); err != io.EOF {
		if err == nil {
			return errors.New("会话 JSON 包含多余数据")
		}
		return fmt.Errorf("会话 JSON 尾部无效：%w", err)
	}
	return nil
}

func validatePortableSession(session *ChatSession) error {
	if session == nil {
		return errors.New("会话文件没有 session")
	}
	if len(session.Messages) > maxPortableSessionMessage {
		return errors.New("会话消息数量超过 50000 条")
	}
	if err := validateDesktopSessions([]*ChatSession{session}); err != nil {
		return fmt.Errorf("会话数据无效：%w", err)
	}
	return nil
}

func exportSessionMarkdown(session *ChatSession) ([]byte, error) {
	if err := validatePortableSession(session); err != nil {
		return nil, err
	}
	var output strings.Builder
	output.WriteString("# ")
	output.WriteString(session.Title)
	output.WriteString("\n\n")
	output.WriteString("> Murong 会话阅读副本。若要重新导入，请另存 JSON 格式。\n\n")
	if goal := strings.TrimSpace(session.Goal); goal != "" {
		output.WriteString("## 会话目标\n\n")
		output.WriteString(goal)
		output.WriteString("\n\n")
	}
	roleLabels := map[string]string{"user": "用户", "assistant": "Murong", "tool": "工具"}
	for _, message := range session.Messages {
		output.WriteString("## ")
		output.WriteString(roleLabels[message.Role])
		if message.ToolName != "" {
			output.WriteString(" · ")
			output.WriteString(message.ToolName)
		}
		if message.CreatedAt > 0 {
			output.WriteString(" · ")
			output.WriteString(time.UnixMilli(message.CreatedAt).Format("2006-01-02 15:04:05"))
		}
		output.WriteString("\n\n")
		if summary := workspaceChangeSummary(message.WorkspaceChanges, message.WorkspaceChangesOmitted); summary != "" {
			output.WriteString("### 项目外部变化\n\n")
			output.WriteString(summary)
			output.WriteString("\n\n")
		}
		if len(message.ImageAttachments) > 0 {
			output.WriteString("### 图片附件\n\n")
			for _, attachment := range message.ImageAttachments {
				output.WriteString("- ")
				output.WriteString(attachment.FileName)
				output.WriteString("\n")
			}
			output.WriteString("\n")
		}
		if message.Role == "tool" {
			fence := markdownCodeFence(message.Content)
			output.WriteString(fence)
			output.WriteString("text\n")
			output.WriteString(message.Content)
			output.WriteString("\n")
			output.WriteString(fence)
			output.WriteString("\n\n")
		} else {
			output.WriteString(message.Content)
			output.WriteString("\n\n")
		}
	}
	return []byte(output.String()), nil
}

func (app *DesktopAgentApp) RenameSession(request RenameSessionRequest) (*ChatSession, error) {
	session, err := app.store.renameSession(request.SessionID, request.Title)
	if err == nil {
		app.emitSessionsChanged(session)
	}
	return session, err
}

func (app *DesktopAgentApp) ForkSession(request SessionPointRequest) (*ChatSession, error) {
	id := strings.TrimSpace(request.SessionID)
	if app.sessionRunActive(id) {
		return nil, errors.New("当前任务仍在运行，停止后才能分叉")
	}
	session, err := app.store.forkSession(id, request.MessageID)
	if err == nil {
		app.emitSessionsChanged(session)
	}
	return session, err
}

func (app *DesktopAgentApp) RollbackSession(request SessionPointRequest) (*ChatSession, error) {
	if !request.Confirmed {
		return nil, errors.New("回退需要明确确认")
	}
	id := strings.TrimSpace(request.SessionID)
	if app.sessionRunActive(id) {
		return nil, errors.New("当前任务仍在运行，停止后才能回退")
	}
	session, err := app.store.rollbackSession(id, request.MessageID)
	if err == nil {
		app.emitSessionsChanged(session)
	}
	return session, err
}

func (app *DesktopAgentApp) GetSessionStats(id string) (SessionStats, error) {
	session := app.store.getSession(strings.TrimSpace(id))
	if session == nil {
		return SessionStats{}, errors.New("会话不存在")
	}
	return sessionStats(session), nil
}

func (app *DesktopAgentApp) ExportSession(request ExportSessionRequest) (string, error) {
	if app.ctx == nil {
		return "", errors.New("窗口尚未就绪")
	}
	session := app.store.getSession(strings.TrimSpace(request.SessionID))
	if session == nil {
		return "", errors.New("会话不存在")
	}
	format := strings.ToLower(strings.TrimSpace(request.Format))
	var data []byte
	var err error
	extension := ".json"
	displayName := "Murong 可导入会话 (*.json)"
	pattern := "*.json"
	if format == "markdown" || format == "md" {
		data, err = exportSessionMarkdown(session)
		extension, displayName, pattern = ".md", "Markdown 阅读副本 (*.md)", "*.md"
	} else if format == "json" {
		data, err = encodePortableSession(session)
		displayName = "Murong 完整会话 (*.json)"
	} else if format == "portable" || format == "cross-platform" {
		data, err = encodeCrossPlatformSession(session)
		displayName = "Murong 跨端会话 (*.json)"
	} else {
		return "", errors.New("只支持完整 JSON、跨端 JSON 或 Markdown 导出")
	}
	if err != nil {
		return "", err
	}
	path, err := runtime.SaveFileDialog(app.ctx, runtime.SaveDialogOptions{
		Title: "导出 Murong 会话", DefaultFilename: safeSessionFilename(session.Title) + extension,
		Filters: []runtime.FileFilter{{DisplayName: displayName, Pattern: pattern}},
	})
	if err != nil || strings.TrimSpace(path) == "" {
		return "", err
	}
	if err := writeSessionFileAtomic(path, data); err != nil {
		return "", err
	}
	return path, nil
}

func (app *DesktopAgentApp) ImportSession() (*ChatSession, error) {
	if app.ctx == nil {
		return nil, errors.New("窗口尚未就绪")
	}
	path, err := runtime.OpenFileDialog(app.ctx, runtime.OpenDialogOptions{
		Title: "导入 Murong 会话", Filters: []runtime.FileFilter{{DisplayName: "Murong 可导入会话 (*.json)", Pattern: "*.json"}},
	})
	if err != nil || strings.TrimSpace(path) == "" {
		return nil, err
	}
	info, err := os.Stat(path)
	if err != nil {
		return nil, err
	}
	if !info.Mode().IsRegular() || info.Size() <= 0 || info.Size() > maxPortableSessionBytes {
		return nil, errors.New("会话文件为空、不是普通文件或超过 32 MiB")
	}
	data, err := readPortableSessionFile(path)
	if err != nil {
		return nil, err
	}
	portable, err := decodeImportableSession(data)
	if err != nil {
		return nil, err
	}
	imported, err := app.store.importSession(portable)
	if err == nil {
		app.emitSessionsChanged(imported)
	}
	return imported, err
}

func (app *DesktopAgentApp) sessionRunActive(id string) bool {
	app.mu.Lock()
	defer app.mu.Unlock()
	_, running := app.runs[strings.TrimSpace(id)]
	return running
}

var invalidSessionFilenameCharacters = regexp.MustCompile(`[<>:"/\\|?*\x00-\x1f]+`)

func safeSessionFilename(title string) string {
	title = invalidSessionFilenameCharacters.ReplaceAllString(strings.TrimSpace(title), "_")
	title = strings.Trim(title, " .")
	if title == "" {
		title = "murong-session"
	}
	return "murong-" + truncateRunes(title, 73)
}

func markdownCodeFence(content string) string {
	longest, current := 0, 0
	for _, character := range content {
		if character == '`' {
			current++
			if current > longest {
				longest = current
			}
		} else {
			current = 0
		}
	}
	if longest < 3 {
		longest = 3
	}
	return strings.Repeat("`", longest+1)
}

func readPortableSessionFile(path string) ([]byte, error) {
	file, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer file.Close()
	data, err := io.ReadAll(io.LimitReader(file, maxPortableSessionBytes+1))
	if err != nil {
		return nil, err
	}
	if len(data) > maxPortableSessionBytes {
		return nil, errors.New("会话文件超过 32 MiB")
	}
	return data, nil
}

func writeSessionFileAtomic(path string, data []byte) error {
	path = strings.TrimSpace(path)
	if path == "" {
		return errors.New("导出路径不能为空")
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return err
	}
	temp, err := os.CreateTemp(filepath.Dir(path), ".murong-session-*.tmp")
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
	return replaceFile(tempName, path)
}
