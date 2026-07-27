package main

import (
	"encoding/json"
	"strings"
	"testing"
)

func TestBuiltinLocalProviderParsesTextAndNestedToolArguments(t *testing.T) {
	content, reasoning, calls := parseDesktopLocalResponse(`我先读取文件。
<tool_call>{"name":"read_file","arguments":{"path":"src/main.go","options":{"lines":[1,20]}}}</tool_call>`)
	if content != "我先读取文件。" || reasoning != "" {
		t.Fatalf("unexpected local content: %q", content)
	}
	if len(calls) != 1 || calls[0].Function.Name != "read_file" {
		t.Fatalf("unexpected local tool calls: %#v", calls)
	}
	var arguments map[string]any
	if json.Unmarshal([]byte(calls[0].Function.Arguments), &arguments) != nil {
		t.Fatalf("local tool arguments are not JSON: %s", calls[0].Function.Arguments)
	}
	if calls[0].ID == "" || calls[0].Type != "function" ||
		arguments["path"] != "src/main.go" {
		t.Fatalf("local tool call was not normalized: %#v", calls[0])
	}
}

func TestBuiltinLocalProviderRejectsMalformedToolArgumentsWithoutLosingText(t *testing.T) {
	content, _, calls := parseDesktopLocalResponse(
		`answer <tool_call>{"name":"","arguments":"not-json"}</tool_call>`,
	)
	if content != "answer" || len(calls) != 0 {
		t.Fatalf("malformed call should be removed safely: content=%q calls=%#v", content, calls)
	}
}

func TestBuiltinLocalProviderNormalizesNullSentinelsAndAskOptions(t *testing.T) {
	_, _, calls := parseDesktopLocalResponse(
		`<tool_call>{"name":"ask_user","arguments":{"target":"null","questions":[{"header":"方式","question":"怎么继续？","options":["自动处理","停止"]}]}}</tool_call>`,
	)
	if len(calls) != 1 {
		t.Fatalf("expected one normalized ask_user call, got %#v", calls)
	}
	arguments := calls[0].Function.Arguments
	if strings.Contains(arguments, `"target"`) ||
		!strings.Contains(arguments, `"label":"自动处理"`) ||
		!strings.Contains(arguments, `"label":"停止"`) {
		t.Fatalf("unexpected normalized arguments: %s", arguments)
	}
}

func TestBuiltinLocalVisibleStreamEmitsTextButHidesSplitToolCalls(t *testing.T) {
	var output strings.Builder
	stream := desktopLocalVisibleStream{onDelta: func(delta string) {
		output.WriteString(delta)
	}}
	for _, chunk := range []string{
		"先处理。<tool", `_call>{"name":"gui","arguments":{`,
		`"action":"launch"}}</tool_`, "call>完成。",
	} {
		stream.accept(visionStreamContent, chunk)
	}
	stream.finish()
	if got := output.String(); got != "先处理。完成。" {
		t.Fatalf("unexpected visible stream: %q", got)
	}
	if !stream.receivedContent {
		t.Fatal("stream should record runtime chunks")
	}
}

func TestBuiltinLocalVisibleStreamSeparatesThinkingAndContent(t *testing.T) {
	var content, reasoning strings.Builder
	stream := desktopLocalVisibleStream{
		mode:        desktopLocalStreamReasoning,
		onDelta:     func(delta string) { content.WriteString(delta) },
		onReasoning: func(delta string) { reasoning.WriteString(delta) },
	}
	for _, chunk := range []string{"先分析", "界面</thi", "nk>最终答案"} {
		stream.accept(visionStreamContent, chunk)
	}
	stream.finish()
	if content.String() != "最终答案" || reasoning.String() != "先分析界面" {
		t.Fatalf("unexpected split: reasoning=%q content=%q", reasoning.String(), content.String())
	}
}

func TestBuiltinLocalResponseSeparatesThinkingFromVisibleText(t *testing.T) {
	content, reasoning, calls := parseDesktopLocalResponse(
		"<think>检查参数</think>开始操作<tool_call>{\"name\":\"gui\",\"arguments\":{\"action\":\"launch\"}}</tool_call>",
	)
	if content != "开始操作" || reasoning != "检查参数" || len(calls) != 1 {
		t.Fatalf("unexpected parsed response: content=%q reasoning=%q calls=%#v", content, reasoning, calls)
	}
}

func TestBuiltinLocalResponseMovesUnclosedThinkingOutOfVisibleHistory(t *testing.T) {
	content, reasoning, calls := parseDesktopLocalResponse("<think>还在分析，尚未结束")
	if content != "" || reasoning != "还在分析，尚未结束" || len(calls) != 0 {
		t.Fatalf("unfinished thinking must not become answer history: content=%q reasoning=%q calls=%#v", content, reasoning, calls)
	}
	prompt := buildDesktopLocalPrompt([]modelMessage{
		{Role: "assistant", Content: "<think>还在分析，尚未结束"},
		{Role: "user", Content: "继续"},
	}, nil)
	if strings.Contains(prompt, "还在分析，尚未结束") {
		t.Fatalf("unfinished prior thinking leaked into the next local prompt: %q", prompt)
	}
}

func TestBuiltinLocalPromptKeepsProtocolLatestHistoryAndCompactTools(t *testing.T) {
	longHistory := strings.Repeat("旧内容", 4000)
	prompt := buildDesktopLocalPrompt(
		[]modelMessage{
			{Role: "system", Content: strings.Repeat("系统", 1000)},
			{Role: "user", Content: longHistory},
			{Role: "user", Content: "LATEST_USER_MESSAGE"},
		},
		[]any{functionTool(
			"read_file",
			"读取文件",
			map[string]any{"path": map[string]any{"type": "string"}},
			[]string{"path"},
		)},
	)
	for _, marker := range []string{
		"<tool_call>", "[AVAILABLE TOOLS]", "read_file", "LATEST_USER_MESSAGE", "[ASSISTANT]",
		`action="launch"`, `"options":[{"label"`,
		"所有可见的思考过程和最终回答都必须使用中文",
	} {
		if !strings.Contains(prompt, marker) {
			t.Fatalf("local prompt is missing %q", marker)
		}
	}
	if len([]rune(prompt)) > 8_500 {
		t.Fatalf("local prompt was not bounded: %d runes", len([]rune(prompt)))
	}
}

func TestBuiltinLocalProviderProfileNormalizesWithoutURLOrSecret(t *testing.T) {
	profile := defaultProviderProfile(providerBuiltinLocal)
	profile.BaseURL = "https://should-not-leave-device.example/v1"
	profile.ProtectedAPIKey = "secret"
	profile.APIMode = "responses"
	normalized := normalizeProviderProfiles([]ProviderProfile{profile}, "", "", "")[0]
	if normalized.ProviderID != providerBuiltinLocal ||
		normalized.BaseURL != "" ||
		normalized.ProtectedAPIKey != "" ||
		normalized.Model != "builtin-selected" ||
		normalized.APIMode != "builtin" ||
		normalized.ContextWindowTokens != 4096 {
		t.Fatalf("unexpected built-in local profile: %#v", normalized)
	}
	if normalized.ReasoningEffort != "off" {
		t.Fatalf("built-in local reasoning should default to off: %#v", normalized)
	}
}
