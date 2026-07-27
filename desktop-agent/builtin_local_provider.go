package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"regexp"
	"sort"
	"strings"
	"unicode/utf8"
)

const builtinLocalVisibleLanguagePolicy = "当用户主要使用中文时，所有可见的思考过程和最终回答都必须使用中文；代码、日志、错误、协议字段、专有名词及用户要求原样输出的文本除外。"
const desktopLocalMaxOutputTokens = 1536

var localToolCallPattern = regexp.MustCompile(`(?is)<tool_call>\s*(.*?)\s*</tool_call>`)
var localThinkPattern = regexp.MustCompile(`(?is)<think>\s*(.*?)\s*</think>`)
var localThinkStartPattern = regexp.MustCompile(`(?is)<think>\s*`)

func (app *DesktopAgentApp) streamConfiguredChat(
	ctx context.Context,
	client *modelClient,
	profile ProviderProfile,
	apiKey string,
	messages []modelMessage,
	tools []any,
	onDelta func(string),
	onReasoning func(string),
) (modelStreamResult, error) {
	if profile.ProviderID != providerBuiltinLocal {
		result, err := client.streamChat(ctx, profile, apiKey, messages, tools, onDelta)
		if err == nil && result.Reasoning != "" && onReasoning != nil {
			onReasoning(result.Reasoning)
		}
		return result, err
	}
	if app.vision == nil || app.visionRuntime == nil {
		return modelStreamResult{}, errors.New("内置本地模型运行时尚未初始化")
	}
	descriptor, modelDir, err := app.vision.ActiveDescriptor()
	if err != nil {
		return modelStreamResult{}, errors.New("尚未安装可用的内置本地模型，请先在本地模型中心安装并选择")
	}
	prompt := buildDesktopLocalPrompt(messages, tools)
	var image *modelImageAttachment
	for messageIndex := len(messages) - 1; messageIndex >= 0; messageIndex-- {
		if !strings.EqualFold(messages[messageIndex].Role, "user") {
			continue
		}
		attachments := messages[messageIndex].Images
		if len(attachments) > 0 {
			candidate := attachments[len(attachments)-1]
			image = &candidate
		}
		break
	}
	if image != nil && !descriptor.SupportsVision {
		return modelStreamResult{}, fmt.Errorf("%s 是纯文本模型，不能读取图片；请切换到带视觉能力的本地模型", descriptor.DisplayName)
	}
	reasoningMode := descriptor.resolveReasoningMode(profile.ReasoningEffort)
	reasoningEnabled := reasoningMode == "on"
	if reasoningEnabled && onReasoning != nil {
		onReasoning("")
	}
	startInReasoning := descriptor.Engine == "mnn" && reasoningEnabled
	visible := desktopLocalVisibleStream{
		mode:        map[bool]desktopLocalStreamMode{true: desktopLocalStreamReasoning, false: desktopLocalStreamContent}[startInReasoning],
		onDelta:     onDelta,
		onReasoning: onReasoning,
	}
	raw, err := app.visionRuntime.Chat(
		ctx,
		descriptor,
		modelDir,
		prompt,
		image,
		min(client.maxTokens, desktopLocalMaxOutputTokens),
		reasoningMode,
		func(kind string, chunk string) {
			visible.accept(kind, chunk)
		},
	)
	if err != nil {
		return modelStreamResult{}, err
	}
	normalizedRaw := raw
	if startInReasoning && !strings.Contains(strings.ToLower(normalizedRaw), "<think>") {
		normalizedRaw = "<think>" + normalizedRaw
	}
	content, reasoning, calls := parseDesktopLocalResponse(normalizedRaw)
	visible.finish()
	if content == "" && len(calls) == 0 && reasoning != "" {
		// Reasoning models occasionally consume their complete output allowance
		// before closing <think>.  The old path persisted that partial chain as
		// the assistant message, which both looked like a disconnect and erased
		// useful conversation memory on the next turn.  Keep it as reasoning and
		// perform one short final-answer pass with thinking explicitly disabled.
		retryVisible := desktopLocalVisibleStream{onDelta: onDelta, onReasoning: onReasoning}
		retryPrompt := prompt + "\n[FINAL ANSWER RETRY]\n上一次思考未在输出上限内结束。现在禁止输出 <think>，直接给出完整、简洁的最终答案或工具调用。"
		retryRaw, retryErr := app.visionRuntime.Chat(
			ctx,
			descriptor,
			modelDir,
			retryPrompt,
			image,
			min(client.maxTokens, desktopLocalMaxOutputTokens),
			"off",
			func(kind string, chunk string) { retryVisible.accept(kind, chunk) },
		)
		if retryErr == nil {
			retryContent, retryReasoning, retryCalls := parseDesktopLocalResponse(retryRaw)
			retryVisible.finish()
			if retryContent != "" || len(retryCalls) > 0 {
				content, calls = retryContent, retryCalls
				if retryReasoning != "" {
					reasoning = strings.TrimSpace(reasoning + "\n\n" + retryReasoning)
				}
				raw += retryRaw
			}
		}
	}
	if content == "" && len(calls) == 0 {
		return modelStreamResult{}, errors.New("内置本地模型的思考在输出上限前结束，未能形成最终回答；请关闭思考后重试，或切换更大模型")
	}
	if content != "" && onDelta != nil && !visible.receivedContent {
		onDelta(content)
	}
	if reasoning != "" && onReasoning != nil && !visible.receivedReasoning {
		onReasoning(reasoning)
	}
	inputTokens := int64(max(1, utf8.RuneCountInString(prompt)/2))
	outputTokens := int64(max(1, utf8.RuneCountInString(raw)/2))
	return modelStreamResult{
		Content:   content,
		Reasoning: reasoning,
		ToolCalls: calls,
		Usage: &modelTokenUsage{
			InputTokens:  inputTokens,
			OutputTokens: outputTokens,
			TotalTokens:  inputTokens + outputTokens,
		},
	}, nil
}

func buildDesktopLocalPrompt(messages []modelMessage, tools []any) string {
	const protocolPrefix = `你是 Murong 内置的离线多模态助手。你可以聊天、看图、写代码，也可以调用工具完成任务。
必须遵守系统指令。需要工具时，只输出以下格式，不要把工具调用写进普通解释：
`
	const protocolSuffix = `
<tool_call>{"name":"工具名","arguments":{"参数":"值"}}</tool_call>
一次可输出多个 tool_call。arguments 必须是 JSON 对象。工具结果会在下一轮以 [TOOL] 提供。
不需要工具时直接回答，不要输出 tool_call 标签。若本轮附带图片，应结合图片回答。
只填写本次动作需要的参数；禁止用字符串 "null" 代替空值，禁止猜测不存在的参数。
Windows 的 gui 启动应用必须用 action="launch"、application=可执行文件或应用名，target 可省略。
不要重复执行已经返回参数错误的相同调用。
ask_user 只在确实无法自行继续时使用，格式必须是：
{"questions":[{"header":"短标题","question":"问题","options":[{"label":"推荐项","description":"说明"},{"label":"另一项","description":"说明"}]}]}`
	protocol := protocolPrefix + builtinLocalVisibleLanguagePolicy + protocolSuffix

	systemParts := make([]string, 0)
	historyParts := make([]string, 0, len(messages))
	for _, message := range messages {
		if strings.EqualFold(message.Role, "system") {
			if value := strings.TrimSpace(message.Content); value != "" {
				systemParts = append(systemParts, value)
			}
			continue
		}
		historyParts = append(historyParts, formatDesktopLocalMessage(message))
	}
	system := truncateRunes(strings.Join(systemParts, "\n"), 1000)
	toolText := truncateRunes(compactDesktopLocalTools(tools), 3000)
	historyBudget := 2400
	if toolText == "" {
		historyBudget = 5000
	}
	history := takeLastRunes(strings.Join(historyParts, "\n\n"), historyBudget)
	var prompt strings.Builder
	prompt.WriteString(protocol)
	if system != "" {
		prompt.WriteString("\n\n[SYSTEM]\n")
		prompt.WriteString(system)
	}
	if toolText != "" {
		prompt.WriteString("\n\n[AVAILABLE TOOLS]\n")
		prompt.WriteString(toolText)
	}
	if history != "" {
		prompt.WriteString("\n\n[CONVERSATION]\n")
		prompt.WriteString(history)
	}
	prompt.WriteString("\n\n[ASSISTANT]\n")
	return prompt.String()
}

func formatDesktopLocalMessage(message modelMessage) string {
	var output strings.Builder
	output.WriteString("[")
	output.WriteString(strings.ToUpper(message.Role))
	if message.Name != "" {
		output.WriteString(" ")
		output.WriteString(message.Name)
	}
	if message.ToolCallID != "" {
		output.WriteString(" id=")
		output.WriteString(message.ToolCallID)
	}
	output.WriteString("]\n")
	content := message.Content
	if strings.EqualFold(message.Role, "assistant") && strings.Contains(strings.ToLower(content), "<think>") {
		// Repair histories produced by older versions that saved an unfinished
		// <think> block as visible assistant content.
		visible, _, _ := parseDesktopLocalResponse(content)
		content = visible
	}
	output.WriteString(content)
	if len(message.Images) > 0 {
		fmt.Fprintf(&output, "\n[本消息附带图片 %d 张]", len(message.Images))
	}
	for _, call := range message.ToolCalls {
		output.WriteString("\n已请求工具 ")
		output.WriteString(call.Function.Name)
		output.WriteString(" ")
		output.WriteString(call.Function.Arguments)
	}
	return output.String()
}

func compactDesktopLocalTools(tools []any) string {
	type toolSummary struct {
		name        string
		description string
		parameters  string
		required    string
		priority    int
	}
	summaries := make([]toolSummary, 0, len(tools))
	for _, definition := range tools {
		data, err := json.Marshal(definition)
		if err != nil {
			continue
		}
		var root map[string]any
		if json.Unmarshal(data, &root) != nil {
			continue
		}
		function, _ := root["function"].(map[string]any)
		if function == nil {
			function = root
		}
		name, _ := function["name"].(string)
		description, _ := function["description"].(string)
		if strings.TrimSpace(name) == "" {
			continue
		}
		parameters, _ := function["parameters"].(map[string]any)
		properties, _ := parameters["properties"].(map[string]any)
		parameterNames := make([]string, 0, len(properties))
		for parameterName := range properties {
			parameterNames = append(parameterNames, parameterName)
		}
		sort.Strings(parameterNames)
		requiredNames := make([]string, 0)
		if required, ok := parameters["required"].([]any); ok {
			for _, value := range required {
				if requiredName, ok := value.(string); ok {
					requiredNames = append(requiredNames, requiredName)
				}
			}
		}
		summaries = append(summaries, toolSummary{
			name:        name,
			description: truncateRunes(strings.Join(strings.Fields(description), " "), 100),
			parameters:  strings.Join(parameterNames, ","),
			required:    strings.Join(requiredNames, ","),
			priority:    desktopLocalToolPriority(name),
		})
	}
	allNames := make([]string, 0, len(summaries))
	for _, summary := range summaries {
		allNames = append(allNames, summary.name)
	}
	sort.SliceStable(summaries, func(left, right int) bool {
		return summaries[left].priority < summaries[right].priority
	})
	lines := []string{"全部工具名：" + strings.Join(allNames, ",")}
	for _, summary := range summaries {
		line := "- " + summary.name + "：" + summary.description
		if summary.parameters != "" {
			line += "；参数=" + summary.parameters
		}
		if summary.required != "" {
			line += "；必填=" + summary.required
		}
		lines = append(lines, line)
	}
	return strings.Join(lines, "\n")
}

func desktopLocalToolPriority(name string) int {
	name = strings.ToLower(name)
	for index, keyword := range []string{
		"gui", "ask_user", "run_terminal", "read_file", "list_files", "write_file",
		"code_search", "code_edit", "workspace_diff", "file_exists", "web_search",
		"web_fetch", "session_history", "complete_step", "github", "mcp",
	} {
		if name == keyword || strings.Contains(name, keyword) {
			return index
		}
	}
	return int(^uint(0) >> 1)
}

func parseDesktopLocalResponse(raw string) (string, string, []modelToolCall) {
	matches := localToolCallPattern.FindAllStringSubmatch(raw, -1)
	calls := make([]modelToolCall, 0, len(matches))
	for _, match := range matches {
		if len(match) < 2 {
			continue
		}
		call, ok := parseDesktopLocalToolCall(match[1])
		if ok {
			calls = append(calls, call)
		}
	}
	reasoningParts := make([]string, 0)
	for _, match := range localThinkPattern.FindAllStringSubmatch(raw, -1) {
		if len(match) > 1 && strings.TrimSpace(match[1]) != "" {
			reasoningParts = append(reasoningParts, strings.TrimSpace(match[1]))
		}
	}
	content := strings.TrimSpace(localThinkPattern.ReplaceAllString(raw, ""))
	content = strings.TrimSpace(localToolCallPattern.ReplaceAllString(content, ""))
	// A common local-model failure mode is an output cap in the middle of a
	// thinking block.  Closed-block regex replacement deliberately leaves that
	// tail alone, so strip and retain it as reasoning instead of saving it in
	// the visible conversation history.
	if starts := localThinkStartPattern.FindAllStringIndex(content, -1); len(starts) > 0 {
		start := starts[len(starts)-1]
		if partial := strings.TrimSpace(content[start[1]:]); partial != "" {
			reasoningParts = append(reasoningParts, partial)
		}
		content = content[:start[0]]
	}
	content = strings.TrimSpace(strings.TrimSuffix(
		strings.TrimPrefix(strings.TrimPrefix(content, "```json"), "```"),
		"```",
	))
	return content, strings.Join(reasoningParts, "\n\n"), calls
}

func parseDesktopLocalToolCall(payload string) (modelToolCall, bool) {
	payload = strings.TrimSpace(strings.TrimSuffix(
		strings.TrimPrefix(strings.TrimPrefix(strings.TrimSpace(payload), "```json"), "```"),
		"```",
	))
	var decoded struct {
		Name      string          `json:"name"`
		Arguments json.RawMessage `json:"arguments"`
	}
	if json.Unmarshal([]byte(payload), &decoded) != nil || strings.TrimSpace(decoded.Name) == "" {
		return modelToolCall{}, false
	}
	argumentsText := strings.TrimSpace(string(decoded.Arguments))
	if strings.HasPrefix(argumentsText, `"`) {
		var text string
		if json.Unmarshal(decoded.Arguments, &text) == nil {
			argumentsText = strings.TrimSpace(text)
		}
	}
	var arguments map[string]any
	if json.Unmarshal([]byte(argumentsText), &arguments) != nil || arguments == nil {
		arguments = map[string]any{}
	}
	arguments = normalizeDesktopLocalArguments(decoded.Name, arguments)
	encodedArguments, err := json.Marshal(arguments)
	if err != nil {
		encodedArguments = []byte("{}")
	}
	return modelToolCall{
		ID:   newID("local-tool"),
		Type: "function",
		Function: modelToolFunction{
			Name:      strings.TrimSpace(decoded.Name),
			Arguments: string(encodedArguments),
		},
	}, true
}

func normalizeDesktopLocalArguments(name string, arguments map[string]any) map[string]any {
	cleaned, _ := cleanDesktopLocalValue(arguments).(map[string]any)
	if cleaned == nil {
		cleaned = map[string]any{}
	}
	if !strings.EqualFold(strings.TrimSpace(name), askUserToolName) {
		return cleaned
	}
	rawQuestions, _ := cleaned["questions"].([]any)
	if len(rawQuestions) > askUserMaxQuestions {
		rawQuestions = rawQuestions[:askUserMaxQuestions]
	}
	questions := make([]any, 0, len(rawQuestions))
	for _, rawQuestion := range rawQuestions {
		question, ok := rawQuestion.(map[string]any)
		if !ok {
			continue
		}
		rawOptions, _ := question["options"].([]any)
		if len(rawOptions) > askUserMaxOptions {
			rawOptions = rawOptions[:askUserMaxOptions]
		}
		options := make([]any, 0, len(rawOptions))
		for _, rawOption := range rawOptions {
			switch option := rawOption.(type) {
			case map[string]any:
				options = append(options, option)
			case string:
				if label := strings.TrimSpace(option); label != "" {
					options = append(options, map[string]any{"label": label})
				}
			}
		}
		question["options"] = options
		questions = append(questions, question)
	}
	cleaned["questions"] = questions
	return cleaned
}

func cleanDesktopLocalValue(value any) any {
	switch typed := value.(type) {
	case map[string]any:
		output := make(map[string]any, len(typed))
		for key, child := range typed {
			cleaned := cleanDesktopLocalValue(child)
			if cleaned != nil {
				output[key] = cleaned
			}
		}
		return output
	case []any:
		output := make([]any, 0, len(typed))
		for _, child := range typed {
			cleaned := cleanDesktopLocalValue(child)
			if cleaned != nil {
				output = append(output, cleaned)
			}
		}
		return output
	case string:
		if strings.EqualFold(strings.TrimSpace(typed), "null") {
			return nil
		}
		return typed
	default:
		return value
	}
}

type desktopLocalStreamMode int

const (
	desktopLocalStreamContent desktopLocalStreamMode = iota
	desktopLocalStreamReasoning
	desktopLocalStreamTool
)

type desktopLocalVisibleStream struct {
	pending           string
	mode              desktopLocalStreamMode
	receivedContent   bool
	receivedReasoning bool
	onDelta           func(string)
	onReasoning       func(string)
}

func (stream *desktopLocalVisibleStream) accept(kind string, chunk string) {
	if chunk == "" {
		return
	}
	if kind == visionStreamReasoning {
		stream.receivedReasoning = true
		if stream.onReasoning != nil {
			stream.onReasoning(chunk)
		}
		return
	}
	stream.pending += chunk
	stream.drain(false)
}

func (stream *desktopLocalVisibleStream) finish() {
	stream.drain(true)
}

func (stream *desktopLocalVisibleStream) drain(final bool) {
	for stream.pending != "" {
		markers := []string{"<tool_call>", "<think>"}
		nextModes := []desktopLocalStreamMode{desktopLocalStreamTool, desktopLocalStreamReasoning}
		if stream.mode == desktopLocalStreamTool {
			markers = []string{"</tool_call>"}
			nextModes = []desktopLocalStreamMode{desktopLocalStreamContent}
		} else if stream.mode == desktopLocalStreamReasoning {
			markers = []string{"</think>"}
			nextModes = []desktopLocalStreamMode{desktopLocalStreamContent}
		}
		index, markerIndex := earliestDesktopLocalMarker(stream.pending, markers)
		if index >= 0 {
			if index > 0 {
				stream.emit(stream.pending[:index])
			}
			stream.pending = stream.pending[index+len(markers[markerIndex]):]
			stream.mode = nextModes[markerIndex]
			continue
		}
		keep := 0
		if !final {
			for _, marker := range markers {
				keep = max(keep, markerPrefixSuffixLength(stream.pending, marker))
			}
		}
		emitLength := len(stream.pending) - keep
		if emitLength > 0 {
			stream.emit(stream.pending[:emitLength])
			stream.pending = stream.pending[emitLength:]
		}
		if final {
			stream.pending = ""
		}
		return
	}
}

func (stream *desktopLocalVisibleStream) emit(value string) {
	if value == "" || stream.mode == desktopLocalStreamTool {
		return
	}
	if stream.mode == desktopLocalStreamReasoning {
		stream.receivedReasoning = true
		if stream.onReasoning != nil {
			stream.onReasoning(value)
		}
		return
	}
	stream.receivedContent = true
	if stream.onDelta != nil {
		stream.onDelta(value)
	}
}

func earliestDesktopLocalMarker(value string, markers []string) (int, int) {
	bestIndex, bestMarker := -1, -1
	for markerIndex, marker := range markers {
		index := indexEqualFold(value, marker)
		if index >= 0 && (bestIndex < 0 || index < bestIndex) {
			bestIndex, bestMarker = index, markerIndex
		}
	}
	return bestIndex, bestMarker
}

func indexEqualFold(value, marker string) int {
	lowerValue := strings.ToLower(value)
	return strings.Index(lowerValue, strings.ToLower(marker))
}

func markerPrefixSuffixLength(value, marker string) int {
	limit := min(len(value), len(marker)-1)
	for size := limit; size > 0; size-- {
		if strings.EqualFold(value[len(value)-size:], marker[:size]) {
			return size
		}
	}
	return 0
}

func takeLastRunes(value string, limit int) string {
	runes := []rune(value)
	if len(runes) <= limit {
		return value
	}
	return string(runes[len(runes)-limit:])
}
