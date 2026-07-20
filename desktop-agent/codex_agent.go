package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"path/filepath"
	"strings"
	"time"
)

const codexDynamicToolsVersion = 4

type codexDynamicToolCallParams struct {
	Arguments json.RawMessage `json:"arguments"`
	CallID    string          `json:"callId"`
	Namespace *string         `json:"namespace"`
	ThreadID  string          `json:"threadId"`
	Tool      string          `json:"tool"`
	TurnID    string          `json:"turnId"`
}

type codexDynamicToolCallOutput struct {
	Type string `json:"type"`
	Text string `json:"text"`
}

type codexDynamicToolCallResponse struct {
	ContentItems []codexDynamicToolCallOutput `json:"contentItems"`
	Success      bool                         `json:"success"`
}

type codexThreadItem struct {
	Type             string          `json:"type"`
	ID               string          `json:"id"`
	Text             string          `json:"text"`
	Command          string          `json:"command"`
	Cwd              string          `json:"cwd"`
	Status           string          `json:"status"`
	AggregatedOutput *string         `json:"aggregatedOutput"`
	ExitCode         *int            `json:"exitCode"`
	Changes          json.RawMessage `json:"changes"`
	Server           string          `json:"server"`
	Tool             string          `json:"tool"`
	Arguments        json.RawMessage `json:"arguments"`
	Result           json.RawMessage `json:"result"`
	Error            json.RawMessage `json:"error"`
}

type codexThreadItemNotification struct {
	ThreadID string          `json:"threadId"`
	TurnID   string          `json:"turnId"`
	Item     codexThreadItem `json:"item"`
}

func (app *DesktopAgentApp) runCodexAgent(ctx context.Context, sessionID string, config desktopConfig, profile ProviderProfile) {
	if app.codex == nil {
		app.failRun(sessionID, errors.New("Codex 运行时尚未初始化"))
		return
	}
	app.emit("agent:status", map[string]any{"sessionId": sessionID, "state": "running", "text": "正在启动内置 Codex"})
	status, err := app.codex.Refresh(ctx, profile.ExecutablePath)
	if err != nil {
		app.failRun(sessionID, err)
		return
	}
	if !status.LoggedIn {
		app.failRun(sessionID, errors.New("Codex 尚未登录 ChatGPT；请在“模型连接”中完成一次设备登录"))
		return
	}
	session := app.store.getSession(sessionID)
	if session == nil {
		app.failRun(sessionID, errors.New("会话不存在"))
		return
	}
	runMode := latestUserMessageMode(session)
	planMode := runMode == "plan" || runMode == "goal_plan"
	baseInstructions := app.systemPrompt(config)
	if goal := strings.TrimSpace(session.Goal); goal != "" {
		baseInstructions += "\n\n当前会话长期目标（后续回复必须围绕它推进，除非用户更新或清除）：\n" + goal
	}
	if planPrompt := workflowExecutionPrompt(session.WorkflowPlan); planPrompt != "" {
		baseInstructions += "\n\n" + planPrompt
	}
	if planMode {
		baseInstructions += "\n\n当前为计划模式：只调查和制定一份简短、可执行、可验证的步骤计划。不得修改文件、执行会产生写入的终端命令或进行其他写入；最终回复只给计划，等待用户明确要求执行。"
	}
	if _, compressionContext := sessionHistoryForModel(session); compressionContext != "" {
		baseInstructions += "\n\n" + compressionContext
	}
	approvalPolicy, sandbox := codexApprovalSettings(config.ApprovalMode, planMode)
	cwd := strings.TrimSpace(config.ProjectPath)
	if cwd == "" {
		cwd = filepath.Dir(app.store.configPath)
	}
	model := codexSelectedModel(profile, status)
	threadParams := map[string]any{
		"cwd": cwd, "approvalPolicy": approvalPolicy, "approvalsReviewer": "user", "sandbox": sandbox,
		"baseInstructions": baseInstructions, "serviceName": "Murong", "ephemeral": false,
		"dynamicTools": app.codexDynamicTools(planMode),
	}
	if model != "" {
		threadParams["model"] = model
	}

	threadID := strings.TrimSpace(session.CodexThreadID)
	freshThread := threadID == "" || session.CodexToolsVersion < codexDynamicToolsVersion
	if freshThread {
		threadID = ""
	}
	requestContext, requestCancel := context.WithTimeout(ctx, 2*time.Minute)
	defer requestCancel()
	if threadID != "" {
		resumeParams := cloneStringAnyMap(threadParams)
		delete(resumeParams, "ephemeral")
		delete(resumeParams, "serviceName")
		delete(resumeParams, "dynamicTools")
		if resumeErr := app.codex.ResumeThread(requestContext, profile.ExecutablePath, threadID, resumeParams); resumeErr != nil {
			threadID = ""
			freshThread = true
		}
	}
	if threadID == "" {
		threadID, err = app.codex.StartThread(requestContext, profile.ExecutablePath, threadParams)
		if err != nil {
			app.failRun(sessionID, err)
			return
		}
		if err := app.store.setCodexThreadState(sessionID, threadID, "", codexDynamicToolsVersion); err != nil {
			app.failRun(sessionID, err)
			return
		}
		session = app.store.getSession(sessionID)
	}

	payload, err := buildCodexTurnPayload(config, session, freshThread)
	if err != nil {
		app.failRun(sessionID, err)
		return
	}
	imagePaths, err := app.store.imagePaths(payload.Images)
	if err != nil {
		app.failRun(sessionID, fmt.Errorf("无法读取会话图片：%w", err))
		return
	}
	events, unsubscribe := app.codex.SubscribeForRun(ctx, threadID, sessionID, config, planMode)
	defer unsubscribe()
	turnID, err := app.codex.StartTurnWithImages(requestContext, profile.ExecutablePath, threadID, payload.Text, model, profile.ReasoningEffort, cwd, imagePaths)
	if err != nil {
		app.failRun(sessionID, err)
		return
	}
	streamID := newID("stream")
	app.emit("agent:stream-start", map[string]any{"sessionId": sessionID, "streamId": streamID})
	defer app.emit("agent:stream-end", map[string]any{"sessionId": sessionID, "streamId": streamID})
	app.emit("agent:status", map[string]any{"sessionId": sessionID, "state": "running", "text": "Codex 正在处理"})

	var latestUsage *modelTokenUsage
	completedItems := map[string]bool{}
	for {
		select {
		case <-ctx.Done():
			interruptContext, cancel := context.WithTimeout(context.Background(), 10*time.Second)
			_ = app.codex.InterruptTurn(interruptContext, threadID, turnID)
			cancel()
			settled, _ := app.store.settleWorkflowPlan(sessionID, "用户停止了本轮计划执行。")
			app.emitSessionsChanged(settled)
			app.emit("agent:status", map[string]any{"sessionId": sessionID, "state": "cancelled", "text": "已停止"})
			return
		case event := <-events:
			switch event.Method {
			case "item/agentMessage/delta", "item/plan/delta":
				var delta struct {
					TurnID string `json:"turnId"`
					Delta  string `json:"delta"`
				}
				if json.Unmarshal(event.Params, &delta) == nil && delta.TurnID == turnID && delta.Delta != "" {
					app.emit("agent:delta", map[string]any{"sessionId": sessionID, "streamId": streamID, "delta": delta.Delta})
				}
			case "item/started":
				if itemEvent, ok := decodeCodexThreadItemEvent(event.Params, turnID); ok {
					app.emitCodexToolItem(sessionID, itemEvent.Item, "running")
				}
			case "item/completed":
				itemEvent, ok := decodeCodexThreadItemEvent(event.Params, turnID)
				if !ok || completedItems[itemEvent.Item.ID] {
					continue
				}
				completedItems[itemEvent.Item.ID] = true
				if err := app.persistCodexCompletedItem(sessionID, itemEvent.Item, planMode); err != nil {
					app.failRun(sessionID, err)
					return
				}
			case "thread/tokenUsage/updated":
				if usage, eventTurnID, ok := decodeCodexUsage(event.Params); ok && eventTurnID == turnID {
					latestUsage = usage
				}
			case "error":
				var failure struct {
					TurnID    string `json:"turnId"`
					WillRetry bool   `json:"willRetry"`
					Error     struct {
						Message string `json:"message"`
					} `json:"error"`
				}
				if json.Unmarshal(event.Params, &failure) == nil && failure.TurnID == turnID && !failure.WillRetry {
					message := strings.TrimSpace(failure.Error.Message)
					if message == "" {
						message = "Codex 处理失败"
					}
					app.failRun(sessionID, errors.New(message))
					return
				}
			case "turn/completed":
				status, message, completedTurnID := decodeCodexTurnCompletion(event.Params)
				if completedTurnID != turnID {
					continue
				}
				if status == "failed" {
					if message == "" {
						message = "Codex 处理失败"
					}
					app.failRun(sessionID, errors.New(message))
					return
				}
				if status == "interrupted" {
					settled, _ := app.store.settleWorkflowPlan(sessionID, "Codex 本轮执行被中断。")
					app.emitSessionsChanged(settled)
					app.emit("agent:status", map[string]any{"sessionId": sessionID, "state": "cancelled", "text": "已停止"})
					return
				}
				usageProfile := profile
				usageProfile.Model = model
				if err := app.store.recordModelUsage(sessionID, usageProfile, latestUsage); err != nil {
					app.failRun(sessionID, err)
					return
				}
				if err := app.store.setCodexThreadState(sessionID, threadID, payload.SyncedMessageID, codexDynamicToolsVersion); err != nil {
					app.failRun(sessionID, err)
					return
				}
				settled, settleErr := app.store.settleWorkflowPlan(sessionID, "")
				if settleErr != nil {
					app.failRun(sessionID, settleErr)
					return
				}
				app.emitSessionsChanged(settled)
				statusText := "完成"
				if settled != nil && settled.WorkflowPlan != nil && settled.WorkflowPlan.Status == workflowPlanBlocked {
					statusText = "计划待继续"
				}
				app.emit("agent:status", map[string]any{"sessionId": sessionID, "state": "idle", "text": statusText})
				return
			case "codex/process-exited":
				var exited struct {
					Message string `json:"message"`
				}
				_ = json.Unmarshal(event.Params, &exited)
				if strings.TrimSpace(exited.Message) == "" {
					exited.Message = "Codex app-server 意外退出"
				}
				app.failRun(sessionID, errors.New(exited.Message))
				return
			}
		}
	}
}

func codexApprovalSettings(mode string, planMode bool) (string, string) {
	if planMode || mode == approvalReadOnly {
		return "never", "read-only"
	}
	if mode == approvalYolo {
		return "never", "danger-full-access"
	}
	return "on-request", "danger-full-access"
}

func codexSelectedModel(profile ProviderProfile, status CodexRuntimeStatus) string {
	if model := strings.TrimSpace(profile.Model); model != "" {
		return model
	}
	for _, model := range status.Models {
		if model.IsDefault {
			if value := strings.TrimSpace(model.Model); value != "" {
				return value
			}
			return strings.TrimSpace(model.ID)
		}
	}
	return ""
}

func cloneStringAnyMap(source map[string]any) map[string]any {
	result := make(map[string]any, len(source))
	for key, value := range source {
		result[key] = value
	}
	return result
}

func buildCodexTurnInput(config desktopConfig, session *ChatSession, freshThread bool) (string, string, error) {
	payload, err := buildCodexTurnPayload(config, session, freshThread)
	return payload.Text, payload.SyncedMessageID, err
}

type codexTurnPayload struct {
	Text            string
	SyncedMessageID string
	Images          []MessageImageAttachment
}

func buildCodexTurnPayload(config desktopConfig, session *ChatSession, freshThread bool) (codexTurnPayload, error) {
	if session == nil {
		return codexTurnPayload{}, errors.New("会话不存在")
	}
	latestUserIndex := -1
	for index := len(session.Messages) - 1; index >= 0; index-- {
		if session.Messages[index].Role == "user" {
			latestUserIndex = index
			break
		}
	}
	if latestUserIndex < 0 {
		return codexTurnPayload{}, errors.New("会话中没有待处理的用户消息")
	}
	start := latestUserIndex
	if freshThread {
		start = 0
	} else if synced := strings.TrimSpace(session.CodexSyncedID); synced != "" {
		for index := range session.Messages {
			if session.Messages[index].ID == synced {
				start = index + 1
				break
			}
		}
	}
	parts := make([]string, 0, latestUserIndex-start+2)
	images := []MessageImageAttachment{}
	if freshThread && start == 0 && latestUserIndex > 0 {
		parts = append(parts, "以下是 Murong 会话中已存在的上下文。它可能来自另一模型连接；请把它当作历史记录，不要重复回答已完成的内容：")
	}
	for index := start; index <= latestUserIndex; index++ {
		message := session.Messages[index]
		switch message.Role {
		case "user":
			content := materializeUserMessage(config, message)
			if len(message.ImageAttachments) > 0 {
				if index == latestUserIndex {
					images = append(images, cloneImageAttachments(message.ImageAttachments)...)
					if strings.TrimSpace(content) == "" {
						content = "请分析这些图片，并提取与当前任务相关的关键信息。"
					}
				} else {
					names := make([]string, 0, len(message.ImageAttachments))
					for _, attachment := range message.ImageAttachments {
						names = append(names, attachment.FileName)
					}
					content = strings.TrimSpace(content + "\n[历史图片附件未在本轮重复发送：" + strings.Join(names, "、") + "]")
				}
			}
			if index == latestUserIndex {
				parts = append(parts, "当前用户请求：\n"+content)
			} else if strings.TrimSpace(content) != "" {
				parts = append(parts, "用户：\n"+content)
			}
		case "assistant":
			if freshThread && strings.TrimSpace(message.Content) != "" {
				parts = append(parts, "助手：\n"+truncateTailRunes(message.Content, 20_000))
			}
		}
	}
	input := strings.TrimSpace(strings.Join(parts, "\n\n"))
	if input == "" {
		return codexTurnPayload{}, errors.New("待处理的用户消息为空")
	}
	if len([]rune(input)) > 200_000 {
		input = "较早历史已截断；优先处理末尾的当前用户请求。\n\n" + truncateTailRunes(input, 200_000)
	}
	return codexTurnPayload{Text: input, SyncedMessageID: session.Messages[latestUserIndex].ID, Images: images}, nil
}

func truncateTailRunes(value string, limit int) string {
	runes := []rune(strings.TrimSpace(value))
	if len(runes) <= limit {
		return string(runes)
	}
	return string(runes[len(runes)-limit:])
}

func decodeCodexThreadItemEvent(data json.RawMessage, expectedTurnID string) (codexThreadItemNotification, bool) {
	var event codexThreadItemNotification
	if json.Unmarshal(data, &event) != nil || event.TurnID != expectedTurnID || strings.TrimSpace(event.Item.ID) == "" {
		return codexThreadItemNotification{}, false
	}
	return event, true
}

func (app *DesktopAgentApp) emitCodexToolItem(sessionID string, item codexThreadItem, state string) {
	name, summary := codexToolPresentation(item)
	if name == "" {
		return
	}
	app.emit("agent:tool", map[string]any{
		"sessionId": sessionID, "toolCallId": item.ID, "toolName": name, "state": state, "text": summary,
	})
}

func codexToolPresentation(item codexThreadItem) (string, string) {
	switch item.Type {
	case "commandExecution":
		return "computer_terminal", truncateRunes(item.Command, 240)
	case "fileChange":
		return "computer_workspace", "修改项目文件"
	case "mcpToolCall":
		return "mcp:" + item.Server + ":" + item.Tool, "调用 MCP · " + item.Server + " / " + item.Tool
	case "dynamicToolCall":
		return item.Tool, "调用工具 · " + item.Tool
	case "collabAgentToolCall", "subAgentActivity":
		return "subagent", "运行子代理"
	case "webSearch":
		return "web_search", "搜索网页"
	case "imageView":
		return "image_view", "查看图片"
	default:
		return "", ""
	}
}

func codexSessionHistoryDynamicTool() map[string]any {
	return map[string]any{
		"type":        "function",
		"name":        sessionHistoryToolName,
		"description": sessionHistoryToolDescription,
		"inputSchema": map[string]any{
			"type":                 "object",
			"properties":           sessionHistoryToolProperties(),
			"required":             []string{},
			"additionalProperties": false,
		},
	}
}

func codexAskUserDynamicTool() map[string]any {
	return map[string]any{
		"type":        "function",
		"name":        askUserToolName,
		"description": askUserToolDescription,
		"inputSchema": map[string]any{
			"type":                 "object",
			"properties":           askUserToolProperties(),
			"required":             []string{"questions"},
			"additionalProperties": false,
		},
	}
}

func (app *DesktopAgentApp) codexDynamicTools(planMode bool) []any {
	tools := []any{codexSessionHistoryDynamicTool(), codexAskUserDynamicTool()}
	if !planMode {
		if tool, ok := codexDynamicToolFromFunction(completeStepToolDefinition()); ok {
			tools = append(tools, tool)
		}
	}
	knowledge := app.knowledgeToolDefinitions()
	if planMode {
		knowledge = filterPlanModeToolDefinitions(knowledge)
	}
	for _, definition := range knowledge {
		if tool, ok := codexDynamicToolFromFunction(definition); ok {
			tools = append(tools, tool)
		}
	}
	return tools
}

func codexDynamicToolFromFunction(definition any) (map[string]any, bool) {
	wrapper, ok := definition.(map[string]any)
	if !ok {
		return nil, false
	}
	function, ok := wrapper["function"].(map[string]any)
	if !ok {
		return nil, false
	}
	name, _ := function["name"].(string)
	description, _ := function["description"].(string)
	schema, ok := function["parameters"].(map[string]any)
	if strings.TrimSpace(name) == "" || !ok {
		return nil, false
	}
	return map[string]any{
		"type": "function", "name": name, "description": description, "inputSchema": schema,
	}, true
}

func (app *DesktopAgentApp) persistCodexCompletedItem(sessionID string, item codexThreadItem, planMode bool) error {
	switch item.Type {
	case "agentMessage", "plan":
		content := strings.TrimSpace(item.Text)
		if content == "" {
			return nil
		}
		kind := ""
		if planMode || item.Type == "plan" {
			kind = "plan"
		}
		updated, err := app.store.appendMessage(sessionID, ChatMessage{Role: "assistant", Content: content, Kind: kind})
		if err == nil && planMode {
			sourceMessageID := updated.Messages[len(updated.Messages)-1].ID
			updated, err = app.store.captureWorkflowPlan(sessionID, sourceMessageID, content)
		}
		if err == nil {
			app.emitSessionsChanged(updated)
		}
		return err
	default:
		name, _ := codexToolPresentation(item)
		if name == "" {
			return nil
		}
		detailBytes, _ := json.Marshal(item)
		detail := truncateRunes(string(detailBytes), 6000)
		if item.AggregatedOutput != nil && strings.TrimSpace(*item.AggregatedOutput) != "" {
			detail = truncateRunes(*item.AggregatedOutput, 6000)
		}
		toolStatus := "success"
		if item.Status == "failed" || item.Status == "declined" {
			toolStatus = "failed"
		}
		receiptArguments := truncateRunes(codexToolReceiptArguments(item), 64*1024)
		updated, err := app.store.appendMessage(sessionID, ChatMessage{
			Role: "tool", Content: detail, Kind: "tool", ToolName: name, ToolCallID: item.ID,
			ToolArguments: receiptArguments, ToolStatus: toolStatus,
		})
		if err == nil {
			if item.Type == "commandExecution" || item.Type == "fileChange" {
				app.recordProjectToolAudit(
					sessionID, "", item.ID, name, receiptArguments,
					projectAuditSourceCodex, toolStatus == "success",
				)
			}
			app.emitSessionsChanged(updated)
		}
		state := "completed"
		if item.Status == "failed" || item.Status == "declined" {
			state = "failed"
		}
		app.emitCodexToolItem(sessionID, item, state)
		return err
	}
}

func codexToolReceiptArguments(item codexThreadItem) string {
	switch item.Type {
	case "commandExecution":
		return marshalToolResult(map[string]any{"command": item.Command, "cwd": item.Cwd})
	case "fileChange":
		return strings.TrimSpace(string(item.Changes))
	default:
		return strings.TrimSpace(string(item.Arguments))
	}
}

func decodeCodexUsage(data json.RawMessage) (*modelTokenUsage, string, bool) {
	var event struct {
		TurnID     string `json:"turnId"`
		TokenUsage struct {
			Last struct {
				TotalTokens           int64 `json:"totalTokens"`
				InputTokens           int64 `json:"inputTokens"`
				CachedInputTokens     int64 `json:"cachedInputTokens"`
				OutputTokens          int64 `json:"outputTokens"`
				ReasoningOutputTokens int64 `json:"reasoningOutputTokens"`
			} `json:"last"`
		} `json:"tokenUsage"`
	}
	if json.Unmarshal(data, &event) != nil || strings.TrimSpace(event.TurnID) == "" {
		return nil, "", false
	}
	usage := &modelTokenUsage{
		InputTokens: event.TokenUsage.Last.InputTokens, OutputTokens: event.TokenUsage.Last.OutputTokens,
		TotalTokens: event.TokenUsage.Last.TotalTokens, CachedInputTokens: event.TokenUsage.Last.CachedInputTokens,
		ReasoningOutputTokens: event.TokenUsage.Last.ReasoningOutputTokens,
	}
	if !validModelTokenUsage(usage) {
		return nil, event.TurnID, false
	}
	return usage, event.TurnID, true
}

func decodeCodexTurnCompletion(data json.RawMessage) (string, string, string) {
	var event struct {
		Turn struct {
			ID     string `json:"id"`
			Status string `json:"status"`
			Error  *struct {
				Message string `json:"message"`
			} `json:"error"`
		} `json:"turn"`
	}
	if json.Unmarshal(data, &event) != nil {
		return "", "", ""
	}
	message := ""
	if event.Turn.Error != nil {
		message = strings.TrimSpace(event.Turn.Error.Message)
	}
	return event.Turn.Status, message, event.Turn.ID
}

func (app *DesktopAgentApp) handleCodexServerRequest(request codexServerRequest) {
	var routing struct {
		ThreadID string `json:"threadId"`
		TurnID   string `json:"turnId"`
		ItemID   string `json:"itemId"`
	}
	if json.Unmarshal(request.Params, &routing) != nil || strings.TrimSpace(routing.ThreadID) == "" {
		app.respondCodexRequestError(request.ID, -32602, "Codex 审批请求缺少线程信息")
		return
	}
	subscription := app.codex.subscriptionForThread(routing.ThreadID)
	if strings.TrimSpace(subscription.sessionID) == "" || subscription.context == nil {
		app.respondCodexRequestError(request.ID, -32001, "找不到对应的 Murong 会话")
		return
	}
	if request.Method == "item/tool/call" {
		response := app.executeCodexDynamicToolRequestForMode(subscription.context, subscription.sessionID, subscription.config, subscription.planMode, request.Params)
		ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
		defer cancel()
		_ = app.codex.RespondToServerRequest(ctx, request.ID, response, nil)
		return
	}
	config := resolvedToolConfig(app.store.rawConfig())
	approval := ApprovalRequest{ID: newID("approval"), SessionID: subscription.sessionID, Risk: "high"}
	allowlistKeys := []string{}
	response := map[string]any{"decision": "decline"}
	switch request.Method {
	case "item/commandExecution/requestApproval":
		var params struct {
			Command string `json:"command"`
			Cwd     string `json:"cwd"`
			Reason  string `json:"reason"`
		}
		_ = json.Unmarshal(request.Params, &params)
		approval.ToolName = "computer_terminal"
		approval.Summary = "Codex 请求执行电脑命令"
		approval.Detail = strings.TrimSpace(params.Cwd + "\n\n" + params.Command + "\n\n" + params.Reason)
		approval.Arguments = string(request.Params)
		allowlistKeys = append(allowlistKeys, "command:"+params.Command, "path:"+params.Cwd)
	case "item/fileChange/requestApproval":
		var params struct {
			Reason    string `json:"reason"`
			GrantRoot string `json:"grantRoot"`
		}
		_ = json.Unmarshal(request.Params, &params)
		approval.ToolName = "computer_workspace"
		approval.Summary = "Codex 请求修改电脑文件"
		approval.Detail = strings.TrimSpace(params.GrantRoot + "\n\n" + params.Reason)
		approval.Arguments = string(request.Params)
		allowlistKeys = append(allowlistKeys, "path:"+params.GrantRoot)
	default:
		app.respondCodexRequestError(request.ID, -32601, "Murong 当前不支持此 Codex 交互请求："+request.Method)
		return
	}
	err := app.authorizeTool(subscription.context, config, approval, allowlistKeys...)
	if err == nil {
		response["decision"] = "accept"
	} else if errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) {
		response["decision"] = "cancel"
	}
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	_ = app.codex.RespondToServerRequest(ctx, request.ID, response, nil)
}

func (app *DesktopAgentApp) executeCodexDynamicToolRequest(ctx context.Context, sessionID string, config desktopConfig, raw json.RawMessage) codexDynamicToolCallResponse {
	return app.executeCodexDynamicToolRequestForMode(ctx, sessionID, config, false, raw)
}

func (app *DesktopAgentApp) executeCodexDynamicToolRequestForMode(ctx context.Context, sessionID string, config desktopConfig, planMode bool, raw json.RawMessage) codexDynamicToolCallResponse {
	failure := func(err error) codexDynamicToolCallResponse {
		message := "动态工具执行失败"
		if err != nil && strings.TrimSpace(err.Error()) != "" {
			message = err.Error()
		}
		return codexDynamicToolCallResponse{ContentItems: []codexDynamicToolCallOutput{{Type: "inputText", Text: message}}, Success: false}
	}
	params := codexDynamicToolCallParams{}
	if err := json.Unmarshal(raw, &params); err != nil {
		return failure(fmt.Errorf("Codex 动态工具参数无效：%w", err))
	}
	if strings.TrimSpace(params.ThreadID) == "" || strings.TrimSpace(params.TurnID) == "" || strings.TrimSpace(params.CallID) == "" {
		return failure(errors.New("Codex 动态工具请求缺少线程、轮次或调用 ID"))
	}
	if params.Namespace != nil && strings.TrimSpace(*params.Namespace) != "" {
		return failure(errors.New("Murong 不接受带命名空间的 Codex 动态工具调用"))
	}
	toolName := strings.TrimSpace(params.Tool)
	arguments := strings.TrimSpace(string(params.Arguments))
	if arguments == "" || arguments == "null" {
		arguments = "{}"
	}
	call := modelToolCall{ID: params.CallID, Type: "function", Function: modelToolFunction{Name: toolName, Arguments: arguments}}
	var result string
	var err error
	switch toolName {
	case sessionHistoryToolName:
		result, err = app.executeSessionHistoryTool(ctx, sessionID, resolvedToolConfig(config), call)
	case askUserToolName:
		result, err = app.executeAskUserTool(ctx, sessionID, call)
	case completeStepToolName:
		if planMode {
			return failure(errors.New("计划生成模式不允许调用 complete_step"))
		}
		result, err = app.executeCompleteStep(ctx, sessionID, call)
		if err == nil {
			app.emitSessionsChanged(app.store.getSession(sessionID))
		}
	default:
		definitions := app.knowledgeToolDefinitions()
		if planMode {
			definitions = filterPlanModeToolDefinitions(definitions)
		}
		allowed := false
		for _, definition := range definitions {
			if functionToolName(definition) == toolName {
				allowed = true
				break
			}
		}
		if !allowed {
			return failure(fmt.Errorf("Murong 不支持 Codex 动态工具：%s", toolName))
		}
		resolved := resolvedToolConfig(config)
		if planMode {
			resolved = planModeToolConfig(resolved)
		}
		if toolName == "run_skill" {
			result, err = app.executeRunSkill(ctx, sessionID, resolved, nil, call)
		} else {
			result, err = app.executeKnowledgeTool(ctx, sessionID, resolved, call)
		}
	}
	if err != nil {
		return failure(err)
	}
	return codexDynamicToolCallResponse{ContentItems: []codexDynamicToolCallOutput{{Type: "inputText", Text: result}}, Success: true}
}

func (app *DesktopAgentApp) respondCodexRequestError(id json.RawMessage, code int, message string) {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	_ = app.codex.RespondToServerRequest(ctx, id, nil, &codexRPCError{Code: code, Message: message})
}

func (store *desktopStore) setCodexThreadState(sessionID, threadID, syncedMessageID string, toolsVersion int) error {
	store.mu.Lock()
	defer store.mu.Unlock()
	session := store.sessions[strings.TrimSpace(sessionID)]
	if session == nil {
		return errors.New("会话不存在")
	}
	previousThreadID, previousSyncedID, previousToolsVersion := session.CodexThreadID, session.CodexSyncedID, session.CodexToolsVersion
	session.CodexThreadID = strings.TrimSpace(threadID)
	session.CodexSyncedID = strings.TrimSpace(syncedMessageID)
	session.CodexToolsVersion = toolsVersion
	if err := store.saveSessionsLocked(); err != nil {
		session.CodexThreadID, session.CodexSyncedID, session.CodexToolsVersion = previousThreadID, previousSyncedID, previousToolsVersion
		return err
	}
	return nil
}
