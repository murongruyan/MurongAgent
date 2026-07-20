package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"strings"

	"github.com/wailsapp/wails/v2/pkg/runtime"
)

func (app *DesktopAgentApp) SendMessage(request SendMessageRequest) error {
	request.SessionID = strings.TrimSpace(request.SessionID)
	request.Content = strings.TrimSpace(request.Content)
	request.Mode = normalizeComposerMode(request.Mode)
	if request.SessionID == "" || (request.Content == "" && len(request.Images) == 0) {
		return errors.New("会话和消息不能为空")
	}
	if len([]rune(request.Content)) > 20_000 {
		return errors.New("单条消息不能超过 20000 字符")
	}
	app.selectRemoteSession(request.SessionID)
	config := app.store.rawConfig()
	if len(request.Images) > 0 && !config.EnableMultimodalMessages {
		return errors.New("多模态消息已在设置中关闭")
	}
	images, err := app.store.validateMessageImages(request.Images)
	if err != nil {
		return err
	}
	config, _, err = app.sessionExecutionConfig(request.SessionID, true)
	if err != nil {
		return err
	}
	contextItems, err := app.validateComposerContext(config, request.Context)
	if err != nil {
		return err
	}
	app.mu.Lock()
	if _, running := app.runs[request.SessionID]; running {
		app.mu.Unlock()
		return errors.New("当前会话仍在运行")
	}
	parent := app.ctx
	if parent == nil {
		parent = context.Background()
	}
	runContext, cancel := context.WithCancel(parent)
	app.runs[request.SessionID] = cancel
	app.mu.Unlock()

	workspaceSnapshot := WorkspaceChangeSnapshot{}
	if app.workspace != nil {
		workspaceSnapshot = app.workspace.Consume(config.ProjectPath)
	}
	session, err := app.store.appendMessage(request.SessionID, ChatMessage{
		Role: "user", Content: request.Content, ImageAttachments: images, Context: contextItems, Mode: request.Mode,
		WorkspaceChanges: workspaceSnapshot.Changes, WorkspaceChangesOmitted: workspaceSnapshot.OmittedCount,
	})
	if err != nil {
		if app.workspace != nil {
			app.workspace.Restore(workspaceSnapshot)
		}
		app.finishRun(request.SessionID)
		return err
	}
	app.emitSessionsChanged(session)
	app.emit("agent:status", map[string]any{"sessionId": request.SessionID, "state": "running", "text": "正在思考"})
	go app.runAgent(runContext, request.SessionID)
	return nil
}

func (app *DesktopAgentApp) runAgent(ctx context.Context, sessionID string) {
	defer app.finishRun(sessionID)
	rawConfig, session, err := app.sessionExecutionConfig(sessionID, true)
	if err != nil {
		app.failRun(sessionID, err)
		return
	}
	config := resolvedToolConfig(rawConfig)
	runMode := latestUserMessageMode(session)
	planMode := runMode == "plan" || runMode == "goal_plan"
	activeProfile := findProviderProfile(config.ProviderProfiles, config.ActiveProviderProfileID)
	if activeProfile == nil {
		app.failRun(sessionID, errors.New("当前模型连接不存在"))
		return
	}
	profile := plannerProviderProfile(config, *activeProfile, planMode)
	if profile.ProviderID == providerCodex {
		app.runCodexAgent(ctx, sessionID, config, profile)
		return
	}
	if profile.ProtectedAPIKey == "" {
		app.failRun(sessionID, errors.New("尚未配置 API Key"))
		return
	}
	plainAPIKey, err := unprotectSecret(profile.ProtectedAPIKey)
	if err != nil {
		app.failRun(sessionID, fmt.Errorf("无法解密 API Key：%w", err))
		return
	}
	apiKey := string(plainAPIKey)
	workspace, err := newLocalWorkspace(config.ProjectPath)
	if err != nil {
		app.failRun(sessionID, err)
		return
	}
	systemPrompt := app.systemPrompt(config)
	if goal := strings.TrimSpace(session.Goal); goal != "" {
		systemPrompt += "\n\n当前会话长期目标（后续回复必须围绕它推进，除非用户更新或清除）：\n" + goal
	}
	if planPrompt := workflowExecutionPrompt(session.WorkflowPlan); planPrompt != "" {
		systemPrompt += "\n\n" + planPrompt
	}
	if planMode {
		systemPrompt += "\n\n当前为计划模式：只调查和制定一份简短、可执行、可验证的步骤计划。不得修改文件、执行终端命令或进行其他写入；最终回复只给计划，等待用户明确要求执行。"
	}
	historyMessages, compressionContext := sessionHistoryForModel(session)
	if compressionContext != "" {
		systemPrompt += "\n\n" + compressionContext
	}
	messages := []modelMessage{{Role: "system", Content: systemPrompt}}
	for _, message := range historyMessages {
		if message.Role == "user" || message.Role == "assistant" {
			content := materializeUserMessage(config, message)
			var images []modelImageAttachment
			if message.Role == "user" && len(message.ImageAttachments) > 0 {
				images, err = app.store.modelImages(message.ImageAttachments)
				if err != nil {
					app.failRun(sessionID, fmt.Errorf("无法读取会话图片：%w", err))
					return
				}
				if strings.TrimSpace(content) == "" {
					content = "请分析这些图片，并提取与当前任务相关的关键信息。"
				}
			}
			messages = append(messages, modelMessage{Role: message.Role, Content: content, Images: images})
		}
	}
	client := newModelClientWithGeneration(config.Temperature, config.MaxTokens)
	runConfig := config
	if planMode {
		runConfig = planModeToolConfig(runConfig)
	}
	tools := app.toolDefinitions(runConfig)
	if planMode {
		tools = app.planModeToolDefinitions(tools)
	}
	for iteration := 1; iteration <= config.MaxToolIterations; iteration++ {
		if err := ctx.Err(); err != nil {
			settled, _ := app.store.settleWorkflowPlan(sessionID, "用户停止了本轮计划执行。")
			app.emitSessionsChanged(settled)
			app.emit("agent:status", map[string]any{"sessionId": sessionID, "state": "cancelled", "text": "已停止"})
			return
		}
		streamID := newID("stream")
		app.emit("agent:stream-start", map[string]any{"sessionId": sessionID, "streamId": streamID})
		result, err := client.streamChat(ctx, profile, apiKey, messages, tools, func(delta string) {
			app.emit("agent:delta", map[string]any{"sessionId": sessionID, "streamId": streamID, "delta": delta})
		})
		app.emit("agent:stream-end", map[string]any{"sessionId": sessionID, "streamId": streamID})
		if err != nil {
			if ctx.Err() != nil {
				settled, _ := app.store.settleWorkflowPlan(sessionID, "用户停止了本轮计划执行。")
				app.emitSessionsChanged(settled)
				app.emit("agent:status", map[string]any{"sessionId": sessionID, "state": "cancelled", "text": "已停止"})
				return
			}
			app.failRun(sessionID, err)
			return
		}
		if err := app.store.recordModelUsage(sessionID, profile, result.Usage); err != nil {
			app.failRun(sessionID, err)
			return
		}
		assistantMessage := modelMessage{Role: "assistant", Content: result.Content, ToolCalls: result.ToolCalls}
		messages = append(messages, assistantMessage)
		if strings.TrimSpace(result.Content) != "" {
			kind := ""
			if len(result.ToolCalls) > 0 {
				kind = "progress"
			} else if planMode {
				kind = "plan"
			}
			updated, saveErr := app.store.appendMessage(sessionID, ChatMessage{Role: "assistant", Content: result.Content, Kind: kind})
			if saveErr != nil {
				app.failRun(sessionID, saveErr)
				return
			}
			if planMode && len(result.ToolCalls) == 0 {
				sourceMessageID := updated.Messages[len(updated.Messages)-1].ID
				updated, saveErr = app.store.captureWorkflowPlan(sessionID, sourceMessageID, result.Content)
				if saveErr != nil {
					app.failRun(sessionID, saveErr)
					return
				}
			}
			app.emitSessionsChanged(updated)
		}
		if len(result.ToolCalls) == 0 {
			settled, settleErr := app.store.settleWorkflowPlan(sessionID, "")
			if settleErr != nil {
				app.failRun(sessionID, settleErr)
				return
			}
			statusText := "完成"
			if settled != nil && settled.WorkflowPlan != nil && settled.WorkflowPlan.Status == workflowPlanBlocked {
				statusText = "计划待继续"
				app.emitSessionsChanged(settled)
			}
			app.emit("agent:status", map[string]any{"sessionId": sessionID, "state": "idle", "text": statusText})
			return
		}

		for _, call := range result.ToolCalls {
			app.emit("agent:tool", map[string]any{
				"sessionId": sessionID, "toolCallId": call.ID, "toolName": call.Function.Name, "state": "running", "text": "正在执行",
			})
			output, toolErr := app.executeTool(ctx, sessionID, runConfig, workspace, call)
			if toolErr != nil {
				output = marshalToolResult(map[string]any{"success": false, "error": toolErr.Error()})
			}
			messages = append(messages, modelMessage{Role: "tool", ToolCallID: call.ID, Name: call.Function.Name, Content: output})
			activity := truncateRunes(output, 6000)
			updated, saveErr := app.store.appendMessage(sessionID, ChatMessage{
				Role: "tool", Content: activity, Kind: "tool", ToolName: call.Function.Name,
				ToolCallID: call.ID, ToolArguments: truncateRunes(call.Function.Arguments, 64*1024),
				ToolStatus: map[bool]string{true: "failed", false: "success"}[toolErr != nil],
			})
			if saveErr == nil {
				app.emitSessionsChanged(updated)
			}
			state := "completed"
			if toolErr != nil {
				state = "failed"
			}
			app.emit("agent:tool", map[string]any{
				"sessionId": sessionID, "toolCallId": call.ID, "toolName": call.Function.Name, "state": state, "text": truncateRunes(output, 240),
			})
		}
	}
	app.failRun(sessionID, fmt.Errorf("达到最大工具迭代次数 %d；可在设置中调整，最大 999", config.MaxToolIterations))
}

func latestUserMessageMode(session *ChatSession) string {
	if session == nil {
		return ""
	}
	for index := len(session.Messages) - 1; index >= 0; index-- {
		if session.Messages[index].Role == "user" {
			return normalizeComposerMode(session.Messages[index].Mode)
		}
	}
	return ""
}

func planModeToolConfig(config desktopConfig) desktopConfig {
	config.ApprovalMode = approvalReadOnly
	config.EnabledFileOperations = intersectStrings(config.EnabledFileOperations, []string{"read", "list", "exists"})
	blocked := map[string]bool{"shell": true, "code_edit": true, "subagent_launch": true}
	enabled := make([]string, 0, len(config.EnabledBuiltinTools))
	for _, name := range config.EnabledBuiltinTools {
		if !blocked[name] {
			enabled = append(enabled, name)
		}
	}
	config.EnabledBuiltinTools = enabled
	return config
}

func filterPlanModeToolDefinitions(tools []any) []any {
	return filterPlanModeToolDefinitionsWithMCP(tools, nil)
}

func (app *DesktopAgentApp) planModeToolDefinitions(tools []any) []any {
	return filterPlanModeToolDefinitionsWithMCP(tools, func(name string) bool {
		return app.mcp != nil && app.mcp.IsTrustedReadOnly(name)
	})
}

func filterPlanModeToolDefinitionsWithMCP(tools []any, trustedMCP func(string) bool) []any {
	safe := map[string]bool{
		"list_files": true, "read_file": true, "file_exists": true, "code_search": true,
		"workspace_diff":         true,
		"ask_user":               true,
		"session_history_search": true,
		"web_search":             true, "web_fetch": true, "memory_list": true, "memory_search": true,
		"memory_read": true, "read_skill": true, "explore": true, "research": true,
		"review": true, "security_review": true, "github_repository": true, "github_read_file": true,
		"github_list_branches": true, "github_list_issues": true,
	}
	result := make([]any, 0, len(tools))
	for _, tool := range tools {
		name := functionToolName(tool)
		if safe[name] || (trustedMCP != nil && trustedMCP(name)) {
			result = append(result, tool)
		}
	}
	return result
}

func (app *DesktopAgentApp) systemPrompt(config desktopConfig) string {
	platform := currentDesktopPlatformInfo()
	terminalLines := make([]string, 0, len(app.terminals))
	for _, terminal := range app.terminals {
		terminalLines = append(terminalLines, fmt.Sprintf("- %s: %s %s", terminal.ID, terminal.Label, terminal.Version))
	}
	verbosity := map[string]string{
		"CONCISE":  "回答简洁，只保留结果、必要证据和下一步。",
		"DETAILED": "回答详细，解释关键判断、验证结果、风险和后续建议。",
	}[config.ResponseVerbosity]
	if verbosity == "" {
		verbosity = "回答保持均衡，先给结果，再给必要说明。"
	}
	sections := []string{
		strings.TrimSpace(config.SystemPrompt),
		platform.Label + " 桌面宿主约束：\n" +
			"- 你直接在用户选择的电脑项目中协作。先读取再修改；覆盖已有文件必须把 read_file 返回的 SHA-256 作为 expected_sha256。\n" +
			"- 所有文件路径使用项目内相对路径。不要声称执行成功，必须根据工具结果验证。\n" +
			"- 不要自动把普通聊天写入长期记忆、规则或 Skill；只有用户明确要求保存、记住、忘记或创建时才调用对应写入工具。\n" +
			"- 全局与项目长期记忆不会整库注入上下文。需要时先用 memory_search 或 memory_list，再用 memory_read 获取完整内容。\n" +
			"- 用户在输入框显式选择的文件、文件夹、Skill、子代理或 MCP 是本轮强意图：按标注读取对应路径，并遵守指定 Skill 或执行方式。\n" +
			verbosity,
		"可用终端 ID：\n" + strings.Join(terminalLines, "\n"),
		fmt.Sprintf("当前审批模式：%s；最大工具迭代：%d。", config.ApprovalMode, config.MaxToolIterations),
	}
	if profile := findProviderProfile(config.ProviderProfiles, config.ActiveProviderProfileID); profile != nil {
		sections = append(sections, fmt.Sprintf("当前运行时模型连接：provider=%s；profile=%s；model=%s；reasoning_effort=%s。被问到模型身份时以此配置为准，不要猜测。", profile.ProviderID, profile.Name, profile.Model, profile.ReasoningEffort))
	}
	activeRules := []string{}
	for _, rule := range config.GlobalRules {
		if rule.Enabled {
			activeRules = append(activeRules, fmt.Sprintf("### %s\n%s", rule.Title, rule.Content))
		}
	}
	if len(activeRules) > 0 {
		sections = append(sections, "已启用的全局规则（必须遵守）：\n"+strings.Join(activeRules, "\n\n"))
	}
	project := projectKnowledgeFromConfig(config)
	projectRules := []string{}
	for _, rule := range project.Library.Rules {
		if rule.Enabled {
			projectRules = append(projectRules, fmt.Sprintf("### %s\n%s", rule.Title, rule.Content))
		}
	}
	if len(projectRules) > 0 {
		sections = append(sections, "当前项目已启用的规则（必须遵守）：\n"+strings.Join(projectRules, "\n\n"))
	}
	activeSkills := []string{}
	for _, skill := range config.GlobalSkills {
		if skill.Enabled {
			activeSkills = append(activeSkills, fmt.Sprintf("- %s | %s | ID: %s", skill.Title, skill.Description, skill.ID))
		}
	}
	if len(activeSkills) > 0 {
		sections = append(sections, "可用的全局 Skills：\n"+strings.Join(activeSkills, "\n")+"\n需要使用某个 Skill 时先调用 read_skill 读取完整说明。")
	}
	projectSkills := []string{}
	for _, skill := range project.Library.Skills {
		if skill.Enabled {
			projectSkills = append(projectSkills, fmt.Sprintf("- %s | %s | ID: %s", skill.Title, skill.Description, skill.ID))
		}
	}
	if len(projectSkills) > 0 {
		sections = append(sections, "当前项目可用的 Skills：\n"+strings.Join(projectSkills, "\n")+"\n需要使用某个 Skill 时先调用 read_skill 读取完整说明。")
	}
	return strings.Join(sections, "\n\n")
}

func (app *DesktopAgentApp) toolDefinitions(config desktopConfig) []any {
	terminalIDs := make([]string, 0, len(app.terminals))
	for _, terminal := range app.terminals {
		terminalIDs = append(terminalIDs, terminal.ID)
	}
	tools := []any{sessionHistoryToolDefinition(), askUserToolDefinition()}
	if isBuiltinToolEnabled(config, "file") && isFileOperationEnabled(config, "list") {
		tools = append(tools, functionTool("list_files", "列出项目目录内容，可递归 1 到 4 层", map[string]any{
			"path":  map[string]any{"type": "string", "description": "项目内相对目录，根目录使用 ."},
			"depth": map[string]any{"type": "integer", "minimum": 1, "maximum": 4},
		}, []string{}))
	}
	if isBuiltinToolEnabled(config, "file") && isFileOperationEnabled(config, "read") {
		tools = append(tools, functionTool("read_file", "读取不超过 1 MiB 的 UTF-8 文本，并返回 SHA-256", map[string]any{
			"path": map[string]any{"type": "string", "description": "项目内相对文件路径"},
		}, []string{"path"}))
	}
	if isBuiltinToolEnabled(config, "file") && isFileOperationEnabled(config, "write") {
		tools = append(tools,
			functionTool("write_file", "原子创建或修改 UTF-8 文本；覆盖已有文件必须提供最近读取的 SHA-256", map[string]any{
				"path":            map[string]any{"type": "string"},
				"content":         map[string]any{"type": "string"},
				"expected_sha256": map[string]any{"type": "string", "description": "覆盖已有文件必填；新文件省略"},
			}, []string{"path", "content"}), functionTool("create_directory", "在项目内创建一个目录", map[string]any{
				"path": map[string]any{"type": "string"},
			}, []string{"path"}))
	}
	if isBuiltinToolEnabled(config, "file") && isFileOperationEnabled(config, "exists") {
		tools = append(tools, functionTool("file_exists", "检查项目内文件、目录或链接是否存在", map[string]any{
			"path": map[string]any{"type": "string"},
		}, []string{"path"}))
	}
	if isBuiltinToolEnabled(config, "file") && isFileOperationEnabled(config, "delete") {
		tools = append(tools, functionTool("delete_path", "删除项目内文件；目录必须显式 recursive=true", map[string]any{
			"path": map[string]any{"type": "string"}, "recursive": map[string]any{"type": "boolean"},
		}, []string{"path"}))
	}
	if isBuiltinToolEnabled(config, "file") && isFileOperationEnabled(config, "chmod") {
		tools = append(tools, functionTool("chmod_path", "修改项目内文件权限；Windows 主要体现为只读属性，macOS/Linux 使用八进制权限", map[string]any{
			"path": map[string]any{"type": "string"}, "mode": map[string]any{"type": "string", "description": "八进制权限，如 644、755"},
		}, []string{"path", "mode"}))
	}
	if isBuiltinToolEnabled(config, "shell") {
		tools = append(tools, functionTool("run_terminal", "在选定的电脑 Shell 中运行命令并返回 stdout、stderr 和退出码", map[string]any{
			"command":         map[string]any{"type": "string"},
			"terminal":        map[string]any{"type": "string", "enum": terminalIDs},
			"path":            map[string]any{"type": "string", "description": "项目内相对工作目录，默认 ."},
			"timeout_seconds": map[string]any{"type": "integer", "minimum": 1, "maximum": 600},
		}, []string{"command"}))
	}
	if isBuiltinToolEnabled(config, "code_search") {
		tools = append(tools, functionTool("code_search", "按关键字或正则搜索项目代码，返回精确相对路径、行号和上下文；默认排除生成目录", map[string]any{
			"pattern":                   map[string]any{"type": "string", "description": "关键字或 Go 正则表达式"},
			"path":                      map[string]any{"type": "string", "description": "项目内相对文件或目录，默认 ."},
			"maxResults":                map[string]any{"type": "integer", "minimum": 1, "maximum": 100},
			"contextLines":              map[string]any{"type": "integer", "minimum": 0, "maximum": 6},
			"fileGlob":                  map[string]any{"type": "string", "description": "可选文件过滤，如 *.go"},
			"excludeGlob":               map[string]any{"type": "string", "description": "逗号、分号或换行分隔的排除模式"},
			"caseSensitive":             map[string]any{"type": "boolean"},
			"includeGeneratedArtifacts": map[string]any{"type": "boolean"},
		}, []string{"pattern"}))
	}
	if (isBuiltinToolEnabled(config, "file") && isFileOperationEnabled(config, "read")) || isBuiltinToolEnabled(config, "code_search") {
		tools = append(tools, functionTool("workspace_diff", "读取指定项目文件当前的 Git 工作树 Diff；非 Git 项目返回当前文本预览", map[string]any{
			"path": map[string]any{"type": "string", "description": "项目内相对文件路径"},
		}, []string{"path"}))
	}
	if isBuiltinToolEnabled(config, "code_edit") {
		tools = append(tools, functionTool("code_edit", "查看、精确 SEARCH/REPLACE、应用单文件多段补丁或创建代码文件", map[string]any{
			"operation":  map[string]any{"type": "string", "enum": []string{"view", "search_replace", "apply_patch", "create"}},
			"path":       map[string]any{"type": "string", "description": "项目内相对文件路径"},
			"search":     map[string]any{"type": "string"},
			"replace":    map[string]any{"type": "string"},
			"content":    map[string]any{"type": "string"},
			"patch_text": map[string]any{"type": "string", "description": "含一个 *** Update File 区块和一个或多个 @@ hunk"},
			"startLine":  map[string]any{"type": "integer", "minimum": 1},
			"endLine":    map[string]any{"type": "integer", "minimum": 1},
		}, []string{"operation", "path"}))
	}
	if isBuiltinToolEnabled(config, "web_search") {
		tools = append(tools, functionTool("web_search", "使用 Bing RSS 在互联网上搜索信息", map[string]any{
			"query": map[string]any{"type": "string"}, "maxResults": map[string]any{"type": "integer", "minimum": 1, "maximum": 10},
		}, []string{"query"}))
	}
	if isBuiltinToolEnabled(config, "web_fetch") {
		tools = append(tools, functionTool("web_fetch", "抓取单个公开网页并提取标题、摘要和正文文本", map[string]any{
			"url": map[string]any{"type": "string"}, "maxChars": map[string]any{"type": "integer", "minimum": 500, "maximum": 12000},
		}, []string{"url"}))
	}
	if isBuiltinToolEnabled(config, "subagent_launch") {
		tools = append(tools, functionTool("subagent_launch", subagentLaunchToolDescription(config), map[string]any{
			"goal": map[string]any{"type": "string"},
			"templateId": map[string]any{
				"type": "string", "description": "可选项目子代理模板 ID；省略时根据 goal 的匹配词自动选择。显式参数仍可覆盖模板默认值。",
			},
			"batchGoals": map[string]any{
				"type": "array", "minItems": 2, "maxItems": maxSubagentBatchTasks,
				"description": fmt.Sprintf("可选。2 到 %d 个彼此独立的子目标；桌面端会立即并行执行全部目标并等待全部结果。", maxSubagentBatchTasks),
				"items":       map[string]any{"type": "string"},
			},
			"parallelTasks": map[string]any{
				"type": "array", "minItems": 2, "maxItems": maxSubagentBatchTasks,
				"description": "可选。结构化并行任务；dependsOn 使用 1-based 任务序号，只有全部前置任务成功才会启动。",
				"items": map[string]any{
					"type": "object", "additionalProperties": false,
					"properties": map[string]any{
						"label":     map[string]any{"type": "string"},
						"goal":      map[string]any{"type": "string"},
						"dependsOn": map[string]any{"type": "array", "items": map[string]any{"type": "integer", "minimum": 1, "maximum": maxSubagentBatchTasks}},
					},
					"required": []string{"goal"},
				},
			},
			"batchLabel":       map[string]any{"type": "string", "description": "可选批次名称"},
			"model":            map[string]any{"type": "string"},
			"reasoningEffort":  map[string]any{"type": "string", "enum": []string{"low", "medium", "high", "xhigh", "max"}},
			"allowedTools":     map[string]any{"type": "array", "items": map[string]any{"type": "string"}},
			"enableWebSearch":  map[string]any{"type": "boolean"},
			"allowWriteAccess": map[string]any{"type": "boolean"},
			"allowCodeEdits":   map[string]any{"type": "boolean"},
			"allowShell":       map[string]any{"type": "boolean"},
			"background":       map[string]any{"type": "boolean", "description": "为 true 时立即返回后台任务 ID；任务继续运行，并在当前会话显示持久状态和完成通知。"},
		}, []string{"goal"}))
		tools = append(tools, functionTool("subagent_jobs", "列出当前会话的后台子代理摘要、按 jobId 读取完整结果，或取消仍在运行的任务", map[string]any{
			"action": map[string]any{"type": "string", "enum": []string{"list", "get", "cancel"}},
			"jobId":  map[string]any{"type": "string", "description": "action=get 或 cancel 时必填"},
		}, []string{"action"}))
	}
	if isBuiltinToolEnabled(config, "github") {
		tools = append(tools, gitHubToolDefinitions()...)
	}
	presetDescriptions := map[string]string{
		"explore":         "派发只读探索子代理，摸清代码结构、关键文件、调用链和上下文",
		"research":        "派发研究子代理，检索项目与网页资料并整理方案、差异和建议",
		"review":          "派发审查子代理，寻找 bug、回归风险、缺失测试和实现缺口",
		"security_review": "派发安全审查子代理，检查权限边界、输入校验、敏感信息和命令执行风险",
	}
	for _, preset := range []string{"explore", "research", "review", "security_review"} {
		if !isSubagentPresetEnabled(config, preset) {
			continue
		}
		tools = append(tools, functionTool(preset, presetDescriptions[preset], map[string]any{
			"goal": map[string]any{"type": "string"}, "model": map[string]any{"type": "string"},
			"reasoningEffort": map[string]any{"type": "string", "enum": []string{"low", "medium", "high", "xhigh", "max"}},
			"background":      map[string]any{"type": "boolean", "description": "为 true 时在后台运行并立即返回任务 ID。"},
		}, []string{"goal"}))
	}
	tools = append(tools, completeStepToolDefinition())
	tools = append(tools, app.knowledgeToolDefinitions()...)
	if isBuiltinToolEnabled(config, "mcp") && app.mcp != nil {
		for _, tool := range app.mcp.ToolDefinitions() {
			tools = append(tools, map[string]any{
				"type": "function",
				"function": map[string]any{
					"name":        tool.CanonicalName,
					"description": fmt.Sprintf("[MCP/%s] %s", tool.ServerName, tool.Description),
					"parameters":  cloneAnyMap(tool.InputSchema),
				},
			})
		}
	}
	return tools
}

func subagentLaunchToolDescription(config desktopConfig) string {
	description := fmt.Sprintf("派发一个或最多 %d 个隔离子代理；桌面端会并行启动所有满足依赖的任务，默认只读，可显式申请联网、写入、代码编辑或 Shell", maxSubagentBatchTasks)
	labels := []string{}
	for _, template := range projectSubagentTemplates(config) {
		if template.Enabled {
			labels = append(labels, template.ID+"="+template.Title)
		}
	}
	if len(labels) > 0 {
		description += "。当前项目模板：" + strings.Join(labels, "；") + "；可传 templateId，省略时按目标匹配词自动选择"
	}
	return description
}

func isSubagentPresetEnabled(config desktopConfig, name string) bool {
	hasPresetOverrides := false
	for _, preset := range []string{"explore", "research", "review", "security_review"} {
		if isBuiltinToolEnabled(config, preset) {
			hasPresetOverrides = true
			break
		}
	}
	return isBuiltinToolEnabled(config, name) || (!hasPresetOverrides && isBuiltinToolEnabled(config, "subagent_launch"))
}

func isBuiltinToolEnabled(config desktopConfig, name string) bool {
	for _, enabled := range config.EnabledBuiltinTools {
		if enabled == name {
			return true
		}
	}
	return false
}

func isFileOperationEnabled(config desktopConfig, operation string) bool {
	for _, enabled := range config.EnabledFileOperations {
		if enabled == operation {
			return true
		}
	}
	return false
}

func isDesktopToolEnabled(config desktopConfig, name string) bool {
	switch name {
	case "session_history_search", "ask_user":
		return true
	case "list_files":
		return isBuiltinToolEnabled(config, "file") && isFileOperationEnabled(config, "list")
	case "read_file":
		return isBuiltinToolEnabled(config, "file") && isFileOperationEnabled(config, "read")
	case "write_file", "create_directory":
		return isBuiltinToolEnabled(config, "file") && isFileOperationEnabled(config, "write")
	case "file_exists":
		return isBuiltinToolEnabled(config, "file") && isFileOperationEnabled(config, "exists")
	case "delete_path":
		return isBuiltinToolEnabled(config, "file") && isFileOperationEnabled(config, "delete")
	case "chmod_path":
		return isBuiltinToolEnabled(config, "file") && isFileOperationEnabled(config, "chmod")
	case "run_terminal":
		return isBuiltinToolEnabled(config, "shell")
	case "code_search":
		return isBuiltinToolEnabled(config, "code_search")
	case "workspace_diff":
		return (isBuiltinToolEnabled(config, "file") && isFileOperationEnabled(config, "read")) || isBuiltinToolEnabled(config, "code_search")
	case "code_edit":
		return isBuiltinToolEnabled(config, "code_edit")
	case "web_search":
		return isBuiltinToolEnabled(config, "web_search")
	case "web_fetch":
		return isBuiltinToolEnabled(config, "web_fetch")
	case "subagent_launch", "subagent_jobs":
		return isBuiltinToolEnabled(config, "subagent_launch")
	case "github_repository", "github_read_file", "github_list_branches", "github_list_issues",
		"github_create_branch", "github_put_file", "github_create_issue", "github_create_pull_request":
		return isBuiltinToolEnabled(config, "github")
	case "explore", "research", "review", "security_review":
		return isSubagentPresetEnabled(config, name)
	default:
		return true
	}
}

func functionTool(name, description string, properties map[string]any, required []string) any {
	return map[string]any{
		"type": "function",
		"function": map[string]any{
			"name":        name,
			"description": description,
			"parameters": map[string]any{
				"type": "object", "properties": properties, "required": required, "additionalProperties": false,
			},
		},
	}
}

func (app *DesktopAgentApp) executeTool(
	ctx context.Context,
	sessionID string,
	config desktopConfig,
	workspace *localWorkspace,
	call modelToolCall,
) (output string, executionErr error) {
	defer func() {
		app.recordProjectToolAudit(
			sessionID, config.ProjectPath, call.ID, call.Function.Name, call.Function.Arguments,
			projectAuditSourceAgent, executionErr == nil,
		)
	}()
	isMCPTool := strings.HasPrefix(call.Function.Name, "mcp__")
	if (isMCPTool && (!isBuiltinToolEnabled(config, "mcp") || app.mcp == nil || !app.mcp.HasTool(call.Function.Name))) ||
		(!isMCPTool && !isDesktopToolEnabled(config, call.Function.Name)) {
		return "", fmt.Errorf("工具 %s 已在当前项目的工具权限中关闭", call.Function.Name)
	}
	var raw map[string]json.RawMessage
	if err := json.Unmarshal([]byte(call.Function.Arguments), &raw); err != nil {
		return "", fmt.Errorf("工具参数不是有效 JSON：%w", err)
	}
	stringArg := func(name, fallback string) string {
		var value string
		if data := raw[name]; len(data) > 0 {
			_ = json.Unmarshal(data, &value)
		}
		if strings.TrimSpace(value) == "" {
			return fallback
		}
		return strings.TrimSpace(value)
	}
	intArg := func(name string, fallback int) int {
		value := fallback
		if data := raw[name]; len(data) > 0 {
			_ = json.Unmarshal(data, &value)
		}
		return value
	}
	if isMCPTool {
		return app.executeMCPTool(ctx, sessionID, config, call)
	}

	switch call.Function.Name {
	case "session_history_search":
		return app.executeSessionHistoryTool(ctx, sessionID, config, call)
	case "ask_user":
		return app.executeAskUserTool(ctx, sessionID, call)
	case "list_files":
		path := stringArg("path", ".")
		if err := app.authorizeTool(ctx, config, ApprovalRequest{
			ID: newID("approval"), SessionID: sessionID, ToolName: call.Function.Name, Summary: "列出项目目录", Detail: path, Arguments: call.Function.Arguments, Risk: "low",
		}, "path:"+path); err != nil {
			return "", err
		}
		entries, err := workspace.list(path, intArg("depth", 2))
		return marshalToolResult(map[string]any{"success": err == nil, "entries": entries}), err
	case "read_file":
		path := stringArg("path", "")
		if err := app.authorizeTool(ctx, config, ApprovalRequest{
			ID: newID("approval"), SessionID: sessionID, ToolName: call.Function.Name, Summary: "读取项目文件", Detail: path, Arguments: call.Function.Arguments, Risk: "low",
		}, "path:"+path); err != nil {
			return "", err
		}
		result, err := workspace.read(path)
		return marshalToolResult(map[string]any{"success": err == nil, "file": result}), err
	case "write_file":
		path := stringArg("path", "")
		var content string
		if data := raw["content"]; len(data) > 0 {
			_ = json.Unmarshal(data, &content)
		}
		expected := stringArg("expected_sha256", "")
		if err := app.authorizeTool(ctx, config, ApprovalRequest{
			ID: newID("approval"), SessionID: sessionID, ToolName: call.Function.Name, Summary: "写入项目文件", Detail: path + "\n\n" + truncateRunes(content, 1200), Arguments: call.Function.Arguments, Risk: "high",
		}, "path:"+path); err != nil {
			return "", err
		}
		app.ignoreDirectWorkspaceChange(config.ProjectPath, path)
		result, created, err := workspace.write(path, content, expected)
		return marshalToolResult(map[string]any{"success": err == nil, "created": created, "file": result}), err
	case "create_directory":
		path := stringArg("path", "")
		if err := app.authorizeTool(ctx, config, ApprovalRequest{
			ID: newID("approval"), SessionID: sessionID, ToolName: call.Function.Name, Summary: "创建项目目录", Detail: path, Arguments: call.Function.Arguments, Risk: "high",
		}, "path:"+path); err != nil {
			return "", err
		}
		app.ignoreDirectWorkspaceChange(config.ProjectPath, path)
		created, err := workspace.mkdir(path)
		return marshalToolResult(map[string]any{"success": err == nil, "created": created, "path": path}), err
	case "file_exists":
		path := stringArg("path", "")
		if err := app.authorizeTool(ctx, config, ApprovalRequest{
			ID: newID("approval"), SessionID: sessionID, ToolName: call.Function.Name, Summary: "检查项目路径", Detail: path, Arguments: call.Function.Arguments, Risk: "low",
		}, "path:"+path); err != nil {
			return "", err
		}
		exists, err := workspace.exists(path)
		return marshalToolResult(map[string]any{"success": err == nil, "exists": exists, "path": path}), err
	case "delete_path":
		path := stringArg("path", "")
		recursive := rawBool(raw, "recursive", false)
		if err := app.authorizeTool(ctx, config, ApprovalRequest{
			ID: newID("approval"), SessionID: sessionID, ToolName: call.Function.Name, Summary: "删除项目路径", Detail: path, Arguments: call.Function.Arguments, Risk: "high",
		}, "path:"+path); err != nil {
			return "", err
		}
		app.ignoreDirectWorkspaceChange(config.ProjectPath, path)
		err := workspace.delete(path, recursive)
		return marshalToolResult(map[string]any{"success": err == nil, "deleted": err == nil, "path": path, "recursive": recursive}), err
	case "chmod_path":
		path, mode := stringArg("path", ""), stringArg("mode", "644")
		if err := app.authorizeTool(ctx, config, ApprovalRequest{
			ID: newID("approval"), SessionID: sessionID, ToolName: call.Function.Name, Summary: "修改项目文件权限", Detail: path + " → " + mode, Arguments: call.Function.Arguments, Risk: "high",
		}, "path:"+path); err != nil {
			return "", err
		}
		app.ignoreDirectWorkspaceChange(config.ProjectPath, path)
		err := workspace.chmod(path, mode)
		return marshalToolResult(map[string]any{"success": err == nil, "path": path, "mode": mode}), err
	case "run_terminal":
		commandText := stringArg("command", "")
		if commandText == "" || len([]rune(commandText)) > 16_384 {
			return "", errors.New("命令为空或超过 16384 字符")
		}
		terminalID := stringArg("terminal", "")
		backend, ok := terminalByID(app.terminals, terminalID)
		if !ok {
			return "", errors.New("请求的终端不可用")
		}
		path := stringArg("path", ".")
		if err := app.authorizeTool(ctx, config, ApprovalRequest{
			ID: newID("approval"), SessionID: sessionID, ToolName: call.Function.Name, Summary: "在 " + backend.Label + " 执行命令", Detail: path + "\n\n" + commandText, Arguments: call.Function.Arguments, Risk: "high",
		}, "command:"+commandText, "terminal:"+backend.ID); err != nil {
			return "", err
		}
		result, err := runLocalTerminal(ctx, workspace, backend, path, commandText, intArg("timeout_seconds", 120))
		return marshalToolResult(map[string]any{"success": err == nil, "result": result}), err
	case "code_search":
		return app.executeCodeSearchTool(ctx, sessionID, config, workspace, call, raw)
	case "workspace_diff":
		path := stringArg("path", "")
		if err := app.authorizeTool(ctx, config, ApprovalRequest{
			ID: newID("approval"), SessionID: sessionID, ToolName: call.Function.Name,
			Summary: "查看项目文件 Diff", Detail: path, Arguments: call.Function.Arguments, Risk: "low",
		}, "path:"+path); err != nil {
			return "", err
		}
		diff, err := workspaceDiffForProject(config.ProjectPath, path)
		return marshalToolResult(map[string]any{"success": err == nil, "diff": diff}), err
	case "code_edit":
		return app.executeCodeEditTool(ctx, sessionID, config, workspace, call, raw)
	case "web_search", "web_fetch":
		return app.executeWebTool(ctx, sessionID, config, call, raw)
	case "subagent_launch", "explore", "research", "review", "security_review":
		return app.executeSubagentTool(ctx, sessionID, config, workspace, call, raw)
	case "subagent_jobs":
		return app.executeSubagentJobsTool(ctx, sessionID, config, call, raw)
	case completeStepToolName:
		return app.executeCompleteStep(ctx, sessionID, call)
	case "run_skill":
		return app.executeRunSkill(ctx, sessionID, config, workspace, call)
	case "github_repository", "github_read_file", "github_list_branches", "github_list_issues",
		"github_create_branch", "github_put_file", "github_create_issue", "github_create_pull_request":
		return app.executeGitHubTool(ctx, sessionID, config, call, raw)
	default:
		return app.executeKnowledgeTool(ctx, sessionID, config, call)
	}
}

func (app *DesktopAgentApp) ignoreDirectWorkspaceChange(projectPath string, paths ...string) {
	if app.workspace != nil {
		app.workspace.IgnoreAgentPaths(projectPath, paths...)
	}
}

func (app *DesktopAgentApp) executeMCPTool(ctx context.Context, sessionID string, config desktopConfig, call modelToolCall) (string, error) {
	tool, ok := app.mcp.Tool(call.Function.Name)
	if !ok {
		return "", fmt.Errorf("MCP 工具 %q 不存在或服务器未连接", call.Function.Name)
	}
	var arguments map[string]any
	if err := json.Unmarshal([]byte(call.Function.Arguments), &arguments); err != nil {
		return "", fmt.Errorf("MCP 工具参数不是有效 JSON：%w", err)
	}
	if arguments == nil {
		arguments = map[string]any{}
	}
	risk := "high"
	if tool.TrustedReadOnly {
		risk = "low"
	}
	if err := app.authorizeTool(ctx, config, ApprovalRequest{
		ID: newID("approval"), SessionID: sessionID, ToolName: tool.CanonicalName,
		Summary: "调用 MCP · " + tool.ServerName + " / " + tool.Name,
		Detail:  truncateRunes(call.Function.Arguments, 2_000), Arguments: call.Function.Arguments, Risk: risk,
	}, "mcp:"+tool.ServerID, "mcp:"+tool.ServerName+":"+tool.Name); err != nil {
		return "", err
	}
	return app.mcp.CallTool(ctx, tool.CanonicalName, arguments)
}

func (app *DesktopAgentApp) authorizeTool(ctx context.Context, config desktopConfig, request ApprovalRequest, allowlistKeys ...string) error {
	switch config.ApprovalMode {
	case approvalYolo:
		return nil
	case approvalReadOnly:
		if request.Risk == "low" {
			return nil
		}
		return errors.New("只读模式拒绝写入和终端工具")
	case approvalAllowlist:
		keys := append([]string{"tool:" + request.ToolName}, allowlistKeys...)
		for _, pattern := range config.Allowlist {
			for _, key := range keys {
				if key == pattern || strings.HasPrefix(key, pattern) {
					return nil
				}
			}
		}
	}
	channel := make(chan bool, 1)
	app.mu.Lock()
	if app.approvals == nil {
		app.approvals = map[string]chan bool{}
	}
	if app.pendingApprovals == nil {
		app.pendingApprovals = map[string]ApprovalRequest{}
	}
	app.approvals[request.ID] = channel
	app.pendingApprovals[request.ID] = request
	app.mu.Unlock()
	app.emit("agent:approval", request)
	select {
	case approved := <-channel:
		if !approved {
			return errors.New("用户拒绝了工具调用")
		}
		return nil
	case <-ctx.Done():
		app.mu.Lock()
		delete(app.approvals, request.ID)
		delete(app.pendingApprovals, request.ID)
		app.mu.Unlock()
		return ctx.Err()
	}
}

func (app *DesktopAgentApp) failRun(sessionID string, err error) {
	message := err.Error()
	_, _ = app.store.settleWorkflowPlan(sessionID, "执行错误："+message)
	updated, saveErr := app.store.appendMessage(sessionID, ChatMessage{Role: "assistant", Content: message, Kind: "error"})
	if saveErr == nil {
		app.emitSessionsChanged(updated)
	}
	app.emit("agent:status", map[string]any{"sessionId": sessionID, "state": "error", "text": message})
}

func (app *DesktopAgentApp) finishRun(sessionID string) {
	app.mu.Lock()
	delete(app.runs, sessionID)
	app.mu.Unlock()
}

func (app *DesktopAgentApp) emit(name string, payload any) {
	if app.ctx != nil {
		runtime.EventsEmit(app.ctx, name, payload)
	}
}
