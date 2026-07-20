package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestModelClientParsesContentAndIncrementalToolCalls(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(response http.ResponseWriter, request *http.Request) {
		if request.URL.Path != "/v1/chat/completions" || request.Header.Get("Authorization") != "Bearer test-key" {
			t.Errorf("unexpected request: %s %s", request.URL.Path, request.Header.Get("Authorization"))
		}
		var body chatCompletionRequest
		if err := json.NewDecoder(request.Body).Decode(&body); err != nil {
			t.Error(err)
		}
		if !body.Stream || body.Model != "test-model" || body.ToolChoice != "auto" {
			t.Errorf("unexpected body: %#v", body)
		}
		if body.Temperature != 0.2 || body.MaxTokens != 1234 {
			t.Errorf("generation settings were not sent to Chat Completions: %#v", body)
		}
		if body.StreamOptions == nil || !body.StreamOptions.IncludeUsage {
			t.Errorf("DeepSeek streaming usage was not requested: %#v", body.StreamOptions)
		}
		response.Header().Set("Content-Type", "text/event-stream")
		fmt.Fprintln(response, `data: {"choices":[{"delta":{"content":"正在"}}]}`)
		fmt.Fprintln(response, `data: {"choices":[{"delta":{"content":"处理","tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"read_","arguments":"{\"pa"}}]}}]}`)
		fmt.Fprintln(response, `data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"name":"file","arguments":"th\":\"a.txt\"}"}}]}}]}`)
		fmt.Fprintln(response, `data: {"choices":[],"usage":{"prompt_tokens":120,"completion_tokens":30,"total_tokens":150,"prompt_cache_hit_tokens":40,"completion_tokens_details":{"reasoning_tokens":12}}}`)
		fmt.Fprintln(response, "data: [DONE]")
	}))
	defer server.Close()

	client := newModelClientWithGeneration(0.2, 1234)
	client.httpClient = server.Client()
	var deltas strings.Builder
	profile := ProviderProfile{ProviderID: providerDeepSeek, BaseURL: server.URL + "/v1", Model: "test-model", ReasoningEffort: "high"}
	result, err := client.streamChat(context.Background(), profile, "test-key", []modelMessage{{Role: "user", Content: "test"}}, []any{functionTool("read_file", "", map[string]any{}, nil)}, func(delta string) { deltas.WriteString(delta) })
	if err != nil {
		t.Fatal(err)
	}
	if result.Content != "正在处理" || deltas.String() != result.Content || len(result.ToolCalls) != 1 {
		t.Fatalf("unexpected result: %#v, deltas=%q", result, deltas.String())
	}
	call := result.ToolCalls[0]
	if call.ID != "call_1" || call.Function.Name != "read_file" || call.Function.Arguments != `{"path":"a.txt"}` {
		t.Fatalf("unexpected tool call: %#v", call)
	}
	if result.Usage == nil || result.Usage.InputTokens != 120 || result.Usage.OutputTokens != 30 || result.Usage.TotalTokens != 150 || result.Usage.CachedInputTokens != 40 || result.Usage.ReasoningOutputTokens != 12 {
		t.Fatalf("unexpected DeepSeek usage: %#v", result.Usage)
	}
}

func TestModelClientUsesAnthropicMessagesAndParsesToolCalls(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(response http.ResponseWriter, request *http.Request) {
		if request.URL.Path != "/v1/messages" || request.Header.Get("x-api-key") != "claude-key" || request.Header.Get("anthropic-version") == "" {
			t.Errorf("unexpected request: %s %s", request.URL.Path, request.Header.Get("x-api-key"))
		}
		var body anthropicRequest
		if err := json.NewDecoder(request.Body).Decode(&body); err != nil {
			t.Error(err)
		}
		if !body.Stream || body.Model != "claude-test" || len(body.System) != 1 || len(body.Tools) != 1 || body.Thinking["type"] != "adaptive" || body.OutputConfig["effort"] != "high" {
			t.Errorf("unexpected body: %#v", body)
		}
		if body.Temperature != 0.3 || body.MaxTokens != 2345 {
			t.Errorf("generation settings were not sent to Claude: %#v", body)
		}
		if len(body.Messages) != 3 || body.Messages[0].Role != "user" || body.Messages[1].Role != "assistant" || body.Messages[2].Role != "user" {
			t.Errorf("unexpected converted messages: %#v", body.Messages)
		}
		response.Header().Set("Content-Type", "text/event-stream")
		fmt.Fprintln(response, `data: {"type":"message_start","message":{"usage":{"input_tokens":80,"cache_creation_input_tokens":10,"cache_read_input_tokens":20,"output_tokens":1}}}`)
		fmt.Fprintln(response, `event: content_block_start`)
		fmt.Fprintln(response, `data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":"开始"}}`)
		fmt.Fprintln(response, `data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"处理"}}`)
		fmt.Fprintln(response, `data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"tool_1","name":"read_file","input":{}}}`)
		fmt.Fprintln(response, `data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"path\":"}}`)
		fmt.Fprintln(response, `data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"\"main.go\"}"}}`)
		fmt.Fprintln(response, `data: {"type":"message_delta","usage":{"output_tokens":18,"output_tokens_details":{"thinking_tokens":7}}}`)
		fmt.Fprintln(response, `data: {"type":"message_stop"}`)
	}))
	defer server.Close()

	client := newModelClientWithGeneration(0.3, 2345)
	client.httpClient = server.Client()
	messages := []modelMessage{
		{Role: "system", Content: "system rules"},
		{Role: "user", Content: "inspect"},
		{
			Role: "assistant",
			ToolCalls: []modelToolCall{{
				ID: "prior_call", Type: "function",
				Function: modelToolFunction{Name: "read_file", Arguments: `{"path":"old.go"}`},
			}},
		},
		{Role: "tool", ToolCallID: "prior_call", Content: "old content"},
	}
	var deltas strings.Builder
	profile := ProviderProfile{ProviderID: providerClaude, BaseURL: server.URL, Model: "claude-test", ReasoningEffort: "high"}
	result, err := client.streamChat(context.Background(), profile, "claude-key", messages, []any{functionTool("read_file", "read", map[string]any{"type": "object"}, nil)}, func(delta string) { deltas.WriteString(delta) })
	if err != nil {
		t.Fatal(err)
	}
	if result.Content != "开始处理" || deltas.String() != result.Content || len(result.ToolCalls) != 1 {
		t.Fatalf("unexpected result: %#v, deltas=%q", result, deltas.String())
	}
	if call := result.ToolCalls[0]; call.ID != "tool_1" || call.Function.Name != "read_file" || call.Function.Arguments != `{"path":"main.go"}` {
		t.Fatalf("unexpected tool call: %#v", call)
	}
	if result.Usage == nil || result.Usage.InputTokens != 110 || result.Usage.OutputTokens != 18 || result.Usage.TotalTokens != 128 || result.Usage.CachedInputTokens != 20 || result.Usage.ReasoningOutputTokens != 7 {
		t.Fatalf("unexpected Claude usage: %#v", result.Usage)
	}
}

func TestModelClientUsesOpenAIResponsesAndParsesToolCalls(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(response http.ResponseWriter, request *http.Request) {
		if request.URL.Path != "/v1/responses" || request.Header.Get("Authorization") != "Bearer responses-key" {
			t.Errorf("unexpected request: %s %s", request.URL.Path, request.Header.Get("Authorization"))
		}
		var body openAIResponsesRequest
		if err := json.NewDecoder(request.Body).Decode(&body); err != nil {
			t.Error(err)
		}
		if !body.Stream || body.Model != "responses-model" || len(body.Input) != 2 || len(body.Tools) != 1 || body.Reasoning["effort"] != "high" {
			t.Errorf("unexpected body: %#v", body)
		}
		if body.Temperature != 0.4 || body.MaxOutputTokens != 3456 {
			t.Errorf("generation settings were not sent to Responses API: %#v", body)
		}
		response.Header().Set("Content-Type", "text/event-stream")
		fmt.Fprintln(response, `data: {"type":"response.output_text.delta","delta":"正在"}`)
		fmt.Fprintln(response, `data: {"type":"response.output_text.delta","delta":"处理"}`)
		fmt.Fprintln(response, `data: {"type":"response.output_item.added","output_index":1,"item":{"type":"function_call","call_id":"call_resp","name":"read_file","arguments":""}}`)
		fmt.Fprintln(response, `data: {"type":"response.function_call_arguments.delta","output_index":1,"delta":"{\"path\":"}`)
		fmt.Fprintln(response, `data: {"type":"response.function_call_arguments.delta","output_index":1,"delta":"\"app.go\"}"}`)
		fmt.Fprintln(response, `data: {"type":"response.output_item.done","output_index":1,"item":{"type":"function_call","call_id":"call_resp","name":"read_file","arguments":"{\"path\":\"app.go\"}"}}`)
		fmt.Fprintln(response, `data: {"type":"response.completed","response":{"usage":{"input_tokens":210,"output_tokens":50,"total_tokens":260,"input_tokens_details":{"cached_tokens":90},"output_tokens_details":{"reasoning_tokens":25}}}}`)
	}))
	defer server.Close()

	profile := ProviderProfile{ProviderID: providerOpenAI, BaseURL: server.URL + "/v1", Model: "responses-model", ReasoningEffort: "high", APIMode: "responses"}
	client := newModelClientWithGeneration(0.4, 3456)
	client.httpClient = server.Client()
	var deltas strings.Builder
	result, err := client.streamChat(context.Background(), profile, "responses-key", []modelMessage{{Role: "system", Content: "system"}, {Role: "user", Content: "inspect"}}, []any{functionTool("read_file", "read", map[string]any{"type": "object"}, nil)}, func(delta string) { deltas.WriteString(delta) })
	if err != nil {
		t.Fatal(err)
	}
	if result.Content != "正在处理" || result.Content != deltas.String() || len(result.ToolCalls) != 1 {
		t.Fatalf("unexpected responses result: %#v, deltas=%q", result, deltas.String())
	}
	if call := result.ToolCalls[0]; call.ID != "call_resp" || call.Function.Name != "read_file" || call.Function.Arguments != `{"path":"app.go"}` {
		t.Fatalf("unexpected responses tool call: %#v", call)
	}
	if result.Usage == nil || result.Usage.InputTokens != 210 || result.Usage.OutputTokens != 50 || result.Usage.TotalTokens != 260 || result.Usage.CachedInputTokens != 90 || result.Usage.ReasoningOutputTokens != 25 {
		t.Fatalf("unexpected Responses usage: %#v", result.Usage)
	}
}

func TestChatCompletionsEndpoint(t *testing.T) {
	if got := chatCompletionsEndpoint("https://example.test/v1/"); got != "https://example.test/v1/chat/completions" {
		t.Fatal(got)
	}
	if got := chatCompletionsEndpoint("https://example.test/v1/chat/completions"); got != "https://example.test/v1/chat/completions" {
		t.Fatal(got)
	}
}

func TestProviderEndpoints(t *testing.T) {
	if got := anthropicMessagesEndpoint("https://example.test/v1/"); got != "https://example.test/v1/messages" {
		t.Fatal(got)
	}
	if got := anthropicMessagesEndpoint("https://example.test/v1/messages"); got != "https://example.test/v1/messages" {
		t.Fatal(got)
	}
	if got := openAIResponsesEndpoint("https://example.test/v1/chat/completions"); got != "https://example.test/v1/responses" {
		t.Fatal(got)
	}
	if !shouldUseOpenAIResponses(ProviderProfile{ProviderID: providerOpenAI, BaseURL: "https://api.openai.com/v1", APIMode: "auto"}) {
		t.Fatal("official OpenAI auto mode should use Responses API")
	}
	if shouldUseOpenAIResponses(ProviderProfile{ProviderID: providerOpenAI, BaseURL: "https://relay.example/v1", APIMode: "auto"}) {
		t.Fatal("custom OpenAI-compatible auto mode should use Chat Completions")
	}
	if shouldRequestChatStreamUsage(ProviderProfile{ProviderID: providerOpenAI, BaseURL: "https://relay.example/v1"}) {
		t.Fatal("custom OpenAI-compatible endpoints must not be forced to accept stream_options")
	}
	if !shouldRequestChatStreamUsage(ProviderProfile{ProviderID: providerDeepSeek, BaseURL: "https://api.deepseek.com/v1"}) {
		t.Fatal("DeepSeek supports streamed usage and should request it")
	}
}
