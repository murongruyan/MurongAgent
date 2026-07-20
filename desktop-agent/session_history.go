package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"sort"
	"strings"
)

const (
	sessionHistoryToolName        = "session_history_search"
	sessionHistoryToolDescription = "检索本机历史任务，或用稳定引用 session_id#message_id 读取一段有界上下文；只返回标题、目标、项目绑定和消息文本，不返回凭据或本地图片路径"
	defaultHistorySearchLimit     = 5
	maxHistorySearchLimit         = 20
	defaultHistoryExcerptLimit    = 6
	maxHistoryExcerptLimit        = 12
	maxHistoryReferences          = 10
	maxHistorySnippetRunes        = 480
	maxHistoryMessageRunes        = 1_200
)

type sessionHistoryRequest struct {
	SessionID             string   `json:"session_id"`
	MessageReference      string   `json:"message_reference"`
	MessageReferences     []string `json:"message_references"`
	Query                 string   `json:"query"`
	Limit                 int      `json:"limit"`
	ExcerptMessageLimit   int      `json:"excerpt_message_limit"`
	AnchorMessageID       string   `json:"anchor_message_id"`
	ProjectOnly           bool     `json:"project_only"`
	IncludeCurrentSession bool     `json:"include_current_session"`
}

type sessionHistoryMatch struct {
	SessionID        string `json:"sessionId"`
	Title            string `json:"title"`
	ProjectPath      string `json:"projectPath,omitempty"`
	Goal             string `json:"goal,omitempty"`
	UpdatedAt        int64  `json:"updatedAt"`
	MessageCount     int    `json:"messageCount"`
	MatchedField     string `json:"matchedField"`
	Snippet          string `json:"snippet"`
	AnchorMessageID  string `json:"anchorMessageId,omitempty"`
	MessageReference string `json:"messageReference,omitempty"`
	Score            int    `json:"score"`
}

type sessionHistoryMessage struct {
	ID        string `json:"id"`
	Reference string `json:"reference"`
	Role      string `json:"role"`
	Content   string `json:"content"`
	Kind      string `json:"kind,omitempty"`
	ToolName  string `json:"toolName,omitempty"`
	CreatedAt int64  `json:"createdAt"`
}

type sessionHistoryExcerpt struct {
	SessionID        string                  `json:"sessionId"`
	Title            string                  `json:"title"`
	ProjectPath      string                  `json:"projectPath,omitempty"`
	Goal             string                  `json:"goal,omitempty"`
	UpdatedAt        int64                   `json:"updatedAt"`
	MessageCount     int                     `json:"messageCount"`
	ExcerptStart     int                     `json:"excerptStart"`
	ExcerptEnd       int                     `json:"excerptEnd"`
	Strategy         string                  `json:"strategy"`
	AnchorMessageID  string                  `json:"anchorMessageId,omitempty"`
	MessageReference string                  `json:"messageReference,omitempty"`
	Messages         []sessionHistoryMessage `json:"messages"`
}

type parsedHistoryReference struct {
	SessionID string
	MessageID string
}

func sessionHistoryToolDefinition() any {
	return functionTool(
		sessionHistoryToolName,
		sessionHistoryToolDescription,
		sessionHistoryToolProperties(),
		[]string{},
	)
}

func sessionHistoryToolProperties() map[string]any {
	return map[string]any{
		"session_id":        map[string]any{"type": "string", "description": "读取指定历史任务的摘录"},
		"message_reference": map[string]any{"type": "string", "description": "稳定消息引用，格式 session_id#message_id"},
		"message_references": map[string]any{
			"type": "array", "maxItems": maxHistoryReferences,
			"items":       map[string]any{"type": "string"},
			"description": "一次读取多段稳定消息引用",
		},
		"query":                   map[string]any{"type": "string", "description": "匹配标题、目标、项目路径、消息正文和工具名"},
		"limit":                   map[string]any{"type": "integer", "minimum": 1, "maximum": maxHistorySearchLimit},
		"excerpt_message_limit":   map[string]any{"type": "integer", "minimum": 1, "maximum": maxHistoryExcerptLimit},
		"anchor_message_id":       map[string]any{"type": "string", "description": "围绕指定桌面消息 ID 读取摘录"},
		"project_only":            map[string]any{"type": "boolean", "description": "只检索当前任务绑定的项目"},
		"include_current_session": map[string]any{"type": "boolean", "description": "是否包含当前任务，默认 false"},
	}
}

func parseSessionHistoryRequest(arguments string) (sessionHistoryRequest, error) {
	request := sessionHistoryRequest{}
	decoder := json.NewDecoder(strings.NewReader(arguments))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&request); err != nil {
		return request, fmt.Errorf("历史检索参数无效：%w", err)
	}
	if err := ensureJSONEOF(decoder); err != nil {
		return request, fmt.Errorf("历史检索参数无效：%w", err)
	}
	request.SessionID = strings.TrimSpace(request.SessionID)
	request.MessageReference = strings.TrimSpace(request.MessageReference)
	request.Query = truncateRunes(request.Query, 2_000)
	request.AnchorMessageID = strings.TrimSpace(request.AnchorMessageID)
	if request.Limit == 0 {
		request.Limit = defaultHistorySearchLimit
	}
	if request.Limit < 1 || request.Limit > maxHistorySearchLimit {
		return request, fmt.Errorf("limit 必须在 1 到 %d 之间", maxHistorySearchLimit)
	}
	if request.ExcerptMessageLimit == 0 {
		request.ExcerptMessageLimit = defaultHistoryExcerptLimit
	}
	if request.ExcerptMessageLimit < 1 || request.ExcerptMessageLimit > maxHistoryExcerptLimit {
		return request, fmt.Errorf("excerpt_message_limit 必须在 1 到 %d 之间", maxHistoryExcerptLimit)
	}
	if len(request.MessageReferences) > maxHistoryReferences {
		return request, fmt.Errorf("message_references 最多包含 %d 条", maxHistoryReferences)
	}
	seen := map[string]bool{}
	cleaned := make([]string, 0, len(request.MessageReferences))
	for _, reference := range request.MessageReferences {
		reference = strings.TrimSpace(reference)
		if reference != "" && !seen[reference] {
			seen[reference] = true
			cleaned = append(cleaned, reference)
		}
	}
	request.MessageReferences = cleaned
	return request, nil
}

func parseHistoryReference(value string) (parsedHistoryReference, error) {
	value = strings.TrimSpace(value)
	separator := strings.LastIndex(value, "#")
	if separator <= 0 || separator == len(value)-1 {
		return parsedHistoryReference{}, errors.New("消息引用必须使用 session_id#message_id 格式")
	}
	reference := parsedHistoryReference{
		SessionID: strings.TrimSpace(value[:separator]),
		MessageID: strings.TrimSpace(value[separator+1:]),
	}
	if reference.SessionID == "" || reference.MessageID == "" {
		return parsedHistoryReference{}, errors.New("消息引用缺少 session_id 或 message_id")
	}
	return reference, nil
}

func (app *DesktopAgentApp) executeSessionHistoryTool(
	ctx context.Context,
	currentSessionID string,
	config desktopConfig,
	call modelToolCall,
) (string, error) {
	request, err := parseSessionHistoryRequest(call.Function.Arguments)
	if err != nil {
		return "", err
	}
	detail := request.Query
	if request.MessageReference != "" {
		detail = request.MessageReference
	} else if request.SessionID != "" {
		detail = request.SessionID
	} else if len(request.MessageReferences) > 0 {
		detail = strings.Join(request.MessageReferences, "\n")
	}
	if err := app.authorizeTool(ctx, config, ApprovalRequest{
		ID: newID("approval"), SessionID: currentSessionID, ToolName: call.Function.Name,
		Summary: "检索本机历史任务", Detail: truncateRunes(detail, 2_000), Arguments: call.Function.Arguments, Risk: "low",
	}, "history:read"); err != nil {
		return "", err
	}

	sessions := app.store.snapshotSessions()
	if len(request.MessageReferences) > 0 {
		results := make([]map[string]any, 0, len(request.MessageReferences))
		for _, rawReference := range request.MessageReferences {
			reference, parseErr := parseHistoryReference(rawReference)
			if parseErr != nil {
				return "", fmt.Errorf("无效消息引用 %q：%w", rawReference, parseErr)
			}
			excerpt, excerptErr := buildSessionHistoryExcerpt(
				sessions, currentSessionID, config.ProjectPath, request, reference.SessionID, reference.MessageID,
			)
			entry := map[string]any{"requestedReference": rawReference, "found": excerptErr == nil}
			if excerptErr != nil {
				entry["error"] = excerptErr.Error()
			} else {
				entry["excerpt"] = excerpt
			}
			results = append(results, entry)
		}
		return marshalToolResult(map[string]any{"success": true, "kind": "multi_excerpt", "results": results}), nil
	}

	sessionID, anchorID := request.SessionID, request.AnchorMessageID
	if request.MessageReference != "" {
		reference, parseErr := parseHistoryReference(request.MessageReference)
		if parseErr != nil {
			return "", parseErr
		}
		if sessionID != "" && sessionID != reference.SessionID {
			return "", errors.New("session_id 与 message_reference 指向不同任务")
		}
		sessionID, anchorID = reference.SessionID, reference.MessageID
	}
	if sessionID != "" {
		excerpt, excerptErr := buildSessionHistoryExcerpt(sessions, currentSessionID, config.ProjectPath, request, sessionID, anchorID)
		if excerptErr != nil {
			return "", excerptErr
		}
		return marshalToolResult(map[string]any{"success": true, "kind": "excerpt", "excerpt": excerpt}), nil
	}
	matches := searchDesktopSessionHistory(sessions, currentSessionID, config.ProjectPath, request)
	return marshalToolResult(map[string]any{
		"success": true, "kind": "search", "query": request.Query, "projectOnly": request.ProjectOnly,
		"matches": matches,
	}), nil
}

func searchDesktopSessionHistory(
	sessions []*ChatSession,
	currentSessionID string,
	currentProjectPath string,
	request sessionHistoryRequest,
) []sessionHistoryMatch {
	terms := historySearchTerms(request.Query)
	matches := make([]sessionHistoryMatch, 0, len(sessions))
	for _, session := range sessions {
		if !historySessionAllowed(session, currentSessionID, currentProjectPath, request) {
			continue
		}
		match, ok := scoreHistorySession(session, terms)
		if !ok {
			continue
		}
		matches = append(matches, match)
	}
	sort.SliceStable(matches, func(i, j int) bool {
		if matches[i].Score != matches[j].Score {
			return matches[i].Score > matches[j].Score
		}
		if matches[i].UpdatedAt != matches[j].UpdatedAt {
			return matches[i].UpdatedAt > matches[j].UpdatedAt
		}
		return matches[i].SessionID < matches[j].SessionID
	})
	if len(matches) > request.Limit {
		matches = matches[:request.Limit]
	}
	return matches
}

func historySessionAllowed(session *ChatSession, currentSessionID, currentProjectPath string, request sessionHistoryRequest) bool {
	if session == nil || (!request.IncludeCurrentSession && session.ID == currentSessionID) {
		return false
	}
	if !request.ProjectOnly {
		return true
	}
	return strings.TrimSpace(currentProjectPath) != "" && strings.TrimSpace(session.ProjectPath) != "" && sameWorkspacePath(session.ProjectPath, currentProjectPath)
}

func historySearchTerms(query string) []string {
	query = strings.ToLower(strings.TrimSpace(query))
	if query == "" {
		return nil
	}
	terms := []string{query}
	for _, term := range strings.Fields(query) {
		if term != query {
			terms = append(terms, term)
		}
	}
	return terms
}

func scoreHistorySession(session *ChatSession, terms []string) (sessionHistoryMatch, bool) {
	match := sessionHistoryMatch{
		SessionID: session.ID, Title: session.Title, ProjectPath: session.ProjectPath, Goal: truncateRunes(session.Goal, maxHistorySnippetRunes),
		UpdatedAt: session.UpdatedAt, MessageCount: len(session.Messages), MatchedField: "recent", Score: 1,
	}
	if len(terms) == 0 {
		match.Snippet = latestHistorySnippet(session)
		return match, true
	}
	type fieldCandidate struct {
		name, text string
		weight     int
		messageID  string
	}
	fields := []fieldCandidate{
		{name: "title", text: session.Title, weight: 120},
		{name: "goal", text: session.Goal, weight: 90},
		{name: "project", text: session.ProjectPath, weight: 60},
	}
	for _, message := range session.Messages {
		fields = append(fields, fieldCandidate{name: "message", text: message.Content, weight: 45, messageID: message.ID})
		if message.ToolName != "" {
			fields = append(fields, fieldCandidate{name: "tool", text: message.ToolName, weight: 35, messageID: message.ID})
		}
	}
	found := false
	bestScore := 0
	for _, field := range fields {
		lower := strings.ToLower(field.text)
		fieldScore := 0
		for index, term := range terms {
			if strings.Contains(lower, term) {
				fieldScore += field.weight
				if index == 0 {
					fieldScore += field.weight
				}
			}
		}
		if fieldScore == 0 {
			continue
		}
		found = true
		if fieldScore > bestScore {
			bestScore = fieldScore
			match.MatchedField = field.name
			match.Snippet = truncateRunes(field.text, maxHistorySnippetRunes)
			match.AnchorMessageID = field.messageID
			if field.messageID != "" {
				match.MessageReference = session.ID + "#" + field.messageID
			}
		}
	}
	match.Score = bestScore
	return match, found
}

func latestHistorySnippet(session *ChatSession) string {
	for index := len(session.Messages) - 1; index >= 0; index-- {
		if content := strings.TrimSpace(session.Messages[index].Content); content != "" {
			return truncateRunes(content, maxHistorySnippetRunes)
		}
	}
	if goal := strings.TrimSpace(session.Goal); goal != "" {
		return truncateRunes(goal, maxHistorySnippetRunes)
	}
	return "暂无消息"
}

func buildSessionHistoryExcerpt(
	sessions []*ChatSession,
	currentSessionID string,
	currentProjectPath string,
	request sessionHistoryRequest,
	sessionID string,
	anchorMessageID string,
) (sessionHistoryExcerpt, error) {
	var session *ChatSession
	for _, candidate := range sessions {
		if candidate != nil && candidate.ID == strings.TrimSpace(sessionID) {
			session = candidate
			break
		}
	}
	if session == nil || !historySessionAllowed(session, currentSessionID, currentProjectPath, request) {
		return sessionHistoryExcerpt{}, errors.New("未找到允许读取的历史任务")
	}
	center, strategy := len(session.Messages)-1, "recent"
	anchorMessageID = strings.TrimSpace(anchorMessageID)
	if anchorMessageID != "" {
		center = -1
		for index := range session.Messages {
			if session.Messages[index].ID == anchorMessageID {
				center = index
				break
			}
		}
		if center < 0 {
			return sessionHistoryExcerpt{}, errors.New("历史消息引用不存在或已被回退")
		}
		strategy = "anchor"
	} else if terms := historySearchTerms(request.Query); len(terms) > 0 {
		for index, message := range session.Messages {
			lower := strings.ToLower(message.Content + " " + message.ToolName)
			for _, term := range terms {
				if strings.Contains(lower, term) {
					center, anchorMessageID, strategy = index, message.ID, "query"
					break
				}
			}
			if strategy == "query" {
				break
			}
		}
	}
	limit := request.ExcerptMessageLimit
	start, end := 0, 0
	if len(session.Messages) > 0 {
		start = center - (limit-1)/2
		if start < 0 {
			start = 0
		}
		end = start + limit
		if end > len(session.Messages) {
			end = len(session.Messages)
			start = end - limit
			if start < 0 {
				start = 0
			}
		}
	}
	messages := make([]sessionHistoryMessage, 0, end-start)
	for _, message := range session.Messages[start:end] {
		messages = append(messages, sessionHistoryMessage{
			ID: message.ID, Reference: session.ID + "#" + message.ID, Role: message.Role,
			Content: truncateRunes(message.Content, maxHistoryMessageRunes), Kind: message.Kind,
			ToolName: message.ToolName, CreatedAt: message.CreatedAt,
		})
	}
	excerpt := sessionHistoryExcerpt{
		SessionID: session.ID, Title: session.Title, ProjectPath: session.ProjectPath,
		Goal: truncateRunes(session.Goal, maxHistorySnippetRunes), UpdatedAt: session.UpdatedAt,
		MessageCount: len(session.Messages), ExcerptStart: start + 1, ExcerptEnd: end,
		Strategy: strategy, AnchorMessageID: anchorMessageID, Messages: messages,
	}
	if anchorMessageID != "" {
		excerpt.MessageReference = session.ID + "#" + anchorMessageID
	}
	return excerpt, nil
}
