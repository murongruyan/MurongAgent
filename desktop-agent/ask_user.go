package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"strings"
	"time"
)

const (
	askUserToolName            = "ask_user"
	askUserToolDescription     = "仅在继续任务确实需要用户选择、偏好或缺失信息时提问；一次可提交 1 到 4 个问题，每题提供 2 到 4 个清晰且互斥的选项，不要用于普通确认或工具审批"
	askUserMaxQuestions        = 4
	askUserMaxOptions          = 4
	askUserMaxHeaderRunes      = 32
	askUserMaxQuestionRunes    = 500
	askUserMaxLabelRunes       = 120
	askUserMaxDescriptionRunes = 300
	askUserMaxCustomRunes      = 500
)

type askUserArguments struct {
	Questions []struct {
		Header      string      `json:"header"`
		Question    string      `json:"question"`
		Options     []AskOption `json:"options"`
		MultiSelect bool        `json:"multiSelect"`
	} `json:"questions"`
}

type askResult struct {
	Text string
}

func askUserToolDefinition() any {
	return functionTool(askUserToolName, askUserToolDescription, askUserToolProperties(), []string{"questions"})
}

func askUserToolProperties() map[string]any {
	return map[string]any{
		"questions": map[string]any{
			"type": "array", "minItems": 1, "maxItems": askUserMaxQuestions,
			"items": map[string]any{
				"type": "object", "additionalProperties": false,
				"properties": map[string]any{
					"header":   map[string]any{"type": "string", "maxLength": askUserMaxHeaderRunes},
					"question": map[string]any{"type": "string", "minLength": 1, "maxLength": askUserMaxQuestionRunes},
					"options": map[string]any{
						"type": "array", "minItems": 2, "maxItems": askUserMaxOptions,
						"items": map[string]any{
							"type": "object", "additionalProperties": false,
							"properties": map[string]any{
								"label":       map[string]any{"type": "string", "minLength": 1, "maxLength": askUserMaxLabelRunes},
								"description": map[string]any{"type": "string", "maxLength": askUserMaxDescriptionRunes},
							},
							"required": []string{"label"},
						},
					},
					"multiSelect": map[string]any{"type": "boolean"},
				},
				"required": []string{"question", "options"},
			},
		},
	}
}

func parseAskUserRequest(arguments string, sessionID string) (AskRequest, error) {
	decoder := json.NewDecoder(bytes.NewBufferString(arguments))
	decoder.DisallowUnknownFields()
	var payload askUserArguments
	if err := decoder.Decode(&payload); err != nil {
		return AskRequest{}, fmt.Errorf("ask_user 参数无效：%w", err)
	}
	if err := ensureAskUserJSONEOF(decoder); err != nil {
		return AskRequest{}, err
	}
	if len(payload.Questions) < 1 || len(payload.Questions) > askUserMaxQuestions {
		return AskRequest{}, fmt.Errorf("ask_user 必须包含 1 到 %d 个问题", askUserMaxQuestions)
	}
	request := AskRequest{ID: newID("ask"), SessionID: strings.TrimSpace(sessionID), CreatedAt: time.Now().UnixMilli()}
	request.Questions = make([]AskQuestion, 0, len(payload.Questions))
	for index, candidate := range payload.Questions {
		header := strings.TrimSpace(candidate.Header)
		question := strings.TrimSpace(candidate.Question)
		if question == "" || len([]rune(question)) > askUserMaxQuestionRunes {
			return AskRequest{}, fmt.Errorf("第 %d 个问题为空或超过 %d 字符", index+1, askUserMaxQuestionRunes)
		}
		if len([]rune(header)) > askUserMaxHeaderRunes {
			return AskRequest{}, fmt.Errorf("第 %d 个问题的标题超过 %d 字符", index+1, askUserMaxHeaderRunes)
		}
		if len(candidate.Options) < 2 || len(candidate.Options) > askUserMaxOptions {
			return AskRequest{}, fmt.Errorf("第 %d 个问题必须包含 2 到 %d 个选项", index+1, askUserMaxOptions)
		}
		seen := map[string]bool{}
		options := make([]AskOption, 0, len(candidate.Options))
		for optionIndex, option := range candidate.Options {
			label := strings.TrimSpace(option.Label)
			description := strings.TrimSpace(option.Description)
			if label == "" || len([]rune(label)) > askUserMaxLabelRunes {
				return AskRequest{}, fmt.Errorf("第 %d 个问题的第 %d 个选项为空或过长", index+1, optionIndex+1)
			}
			key := strings.ToLower(label)
			if seen[key] {
				return AskRequest{}, fmt.Errorf("第 %d 个问题包含重复选项 %q", index+1, label)
			}
			if len([]rune(description)) > askUserMaxDescriptionRunes {
				return AskRequest{}, fmt.Errorf("第 %d 个问题的第 %d 个选项说明过长", index+1, optionIndex+1)
			}
			seen[key] = true
			options = append(options, AskOption{Label: label, Description: description})
		}
		request.Questions = append(request.Questions, AskQuestion{
			ID: fmt.Sprintf("q%d", index+1), Header: header, Question: question,
			Options: options, MultiSelect: candidate.MultiSelect,
		})
	}
	return request, nil
}

func ensureAskUserJSONEOF(decoder *json.Decoder) error {
	var extra any
	if err := decoder.Decode(&extra); err == io.EOF {
		return nil
	} else if err != nil {
		return fmt.Errorf("ask_user 参数尾部无效：%w", err)
	}
	return errors.New("ask_user 参数只能包含一个 JSON 对象")
}

func (app *DesktopAgentApp) executeAskUserTool(ctx context.Context, sessionID string, call modelToolCall) (string, error) {
	request, err := parseAskUserRequest(call.Function.Arguments, sessionID)
	if err != nil {
		return "", err
	}
	channel := make(chan askResult, 1)
	app.mu.Lock()
	if app.asks == nil {
		app.asks = map[string]chan askResult{}
	}
	if app.pendingAsks == nil {
		app.pendingAsks = map[string]AskRequest{}
	}
	for _, pending := range app.pendingAsks {
		if pending.SessionID == request.SessionID {
			app.mu.Unlock()
			return "", errors.New("当前会话已有待回答问题")
		}
	}
	for _, pending := range app.pendingApprovals {
		if pending.SessionID == request.SessionID {
			app.mu.Unlock()
			return "", errors.New("当前会话仍有待审批工具，不能同时提问")
		}
	}
	app.asks[request.ID] = channel
	app.pendingAsks[request.ID] = request
	app.mu.Unlock()
	app.emit("agent:ask-user", request)
	select {
	case result := <-channel:
		return result.Text, nil
	case <-ctx.Done():
		app.mu.Lock()
		delete(app.asks, request.ID)
		delete(app.pendingAsks, request.ID)
		app.mu.Unlock()
		app.emit("agent:ask-user-cleared", map[string]any{"id": request.ID, "sessionId": request.SessionID})
		return "", ctx.Err()
	}
}

func (app *DesktopAgentApp) PendingAsk(sessionID string) *AskRequest {
	sessionID = strings.TrimSpace(sessionID)
	app.mu.Lock()
	defer app.mu.Unlock()
	for _, request := range app.pendingAsks {
		if request.SessionID == sessionID {
			copy := cloneAskRequest(request)
			return &copy
		}
	}
	return nil
}

func (app *DesktopAgentApp) ResolveAsk(decision AskDecision) (bool, error) {
	decision.ID = strings.TrimSpace(decision.ID)
	app.mu.Lock()
	request, exists := app.pendingAsks[decision.ID]
	channel := app.asks[decision.ID]
	if !exists || channel == nil {
		app.mu.Unlock()
		return false, errors.New("问题已经处理或不存在")
	}
	text, err := validateAndFormatAskDecision(request, decision)
	if err != nil {
		app.mu.Unlock()
		return false, err
	}
	delete(app.asks, decision.ID)
	delete(app.pendingAsks, decision.ID)
	app.mu.Unlock()
	channel <- askResult{Text: text}
	app.emit("agent:ask-user-cleared", map[string]any{"id": request.ID, "sessionId": request.SessionID})
	return true, nil
}

func validateAndFormatAskDecision(request AskRequest, decision AskDecision) (string, error) {
	if decision.Dismiss {
		if len(decision.Answers) > 0 {
			return "", errors.New("跳过问题时不能同时提交答案")
		}
		return "用户没有给出明确选择。请基于现有上下文继续，并明确说明你采用的默认假设。", nil
	}
	if len(decision.Answers) != len(request.Questions) {
		return "", errors.New("必须回答全部问题，或选择跳过")
	}
	questions := make(map[string]AskQuestion, len(request.Questions))
	for _, question := range request.Questions {
		questions[question.ID] = question
	}
	seenQuestions := map[string]bool{}
	lines := []string{"用户已回答："}
	for _, answer := range decision.Answers {
		question, exists := questions[strings.TrimSpace(answer.QuestionID)]
		if !exists || seenQuestions[question.ID] {
			return "", errors.New("答案包含未知或重复的问题")
		}
		seenQuestions[question.ID] = true
		if len(answer.SelectedOptions) < 1 || (!question.MultiSelect && len(answer.SelectedOptions) != 1) {
			return "", fmt.Errorf("%s 的选择数量无效", askQuestionLabel(question))
		}
		known := map[string]string{}
		for _, option := range question.Options {
			known[strings.ToLower(option.Label)] = option.Label
		}
		values := make([]string, 0, len(answer.SelectedOptions))
		seenValues := map[string]bool{}
		customCount := 0
		for _, raw := range answer.SelectedOptions {
			value := strings.TrimSpace(raw)
			if value == "" || len([]rune(value)) > askUserMaxCustomRunes {
				return "", fmt.Errorf("%s 的答案为空或过长", askQuestionLabel(question))
			}
			key := strings.ToLower(value)
			if seenValues[key] {
				return "", fmt.Errorf("%s 包含重复答案", askQuestionLabel(question))
			}
			seenValues[key] = true
			if canonical, ok := known[key]; ok {
				values = append(values, canonical)
			} else {
				customCount++
				values = append(values, value)
			}
		}
		if customCount > 0 && len(values) > 1 {
			return "", fmt.Errorf("%s 的自定义答案不能和预设选项混用", askQuestionLabel(question))
		}
		lines = append(lines, "- "+askQuestionLabel(question)+"："+strings.Join(values, "、"))
	}
	return strings.Join(lines, "\n"), nil
}

func askQuestionLabel(question AskQuestion) string {
	if strings.TrimSpace(question.Header) != "" {
		return question.Header
	}
	return question.Question
}

func cloneAskRequest(request AskRequest) AskRequest {
	copy := request
	copy.Questions = make([]AskQuestion, len(request.Questions))
	for index, question := range request.Questions {
		copy.Questions[index] = question
		copy.Questions[index].Options = append([]AskOption(nil), question.Options...)
	}
	return copy
}
