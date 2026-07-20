package main

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math"
	"net/http"
	"net/url"
	"strings"
	"time"
)

type modelMessage struct {
	Role       string `json:"role"`
	Content    string `json:"content,omitempty"`
	Images     []modelImageAttachment
	ToolCalls  []modelToolCall `json:"tool_calls,omitempty"`
	ToolCallID string          `json:"tool_call_id,omitempty"`
	Name       string          `json:"name,omitempty"`
}

type modelToolCall struct {
	ID       string            `json:"id"`
	Type     string            `json:"type"`
	Function modelToolFunction `json:"function"`
}

type modelToolFunction struct {
	Name      string `json:"name"`
	Arguments string `json:"arguments"`
}

type chatCompletionRequest struct {
	Model           string             `json:"model"`
	Messages        []any              `json:"messages"`
	Tools           []any              `json:"tools,omitempty"`
	ToolChoice      string             `json:"tool_choice,omitempty"`
	ReasoningEffort string             `json:"reasoning_effort,omitempty"`
	Temperature     float64            `json:"temperature"`
	MaxTokens       int                `json:"max_tokens"`
	StreamOptions   *chatStreamOptions `json:"stream_options,omitempty"`
	Stream          bool               `json:"stream"`
}

type chatStreamOptions struct {
	IncludeUsage bool `json:"include_usage"`
}

type openAIUsagePayload struct {
	PromptTokens         int64 `json:"prompt_tokens"`
	CompletionTokens     int64 `json:"completion_tokens"`
	InputTokens          int64 `json:"input_tokens"`
	OutputTokens         int64 `json:"output_tokens"`
	TotalTokens          int64 `json:"total_tokens"`
	PromptCacheHitTokens int64 `json:"prompt_cache_hit_tokens"`
	PromptTokensDetails  struct {
		CachedTokens int64 `json:"cached_tokens"`
	} `json:"prompt_tokens_details"`
	InputTokensDetails struct {
		CachedTokens int64 `json:"cached_tokens"`
	} `json:"input_tokens_details"`
	CompletionTokensDetails struct {
		ReasoningTokens int64 `json:"reasoning_tokens"`
	} `json:"completion_tokens_details"`
	OutputTokensDetails struct {
		ReasoningTokens int64 `json:"reasoning_tokens"`
	} `json:"output_tokens_details"`
}

type streamToolCallDelta struct {
	Index    int    `json:"index"`
	ID       string `json:"id"`
	Type     string `json:"type"`
	Function struct {
		Name      string `json:"name"`
		Arguments string `json:"arguments"`
	} `json:"function"`
}

type streamResponseChunk struct {
	Choices []struct {
		Delta struct {
			Content   string                `json:"content"`
			ToolCalls []streamToolCallDelta `json:"tool_calls"`
		} `json:"delta"`
		FinishReason *string `json:"finish_reason"`
	} `json:"choices"`
	Usage *openAIUsagePayload `json:"usage,omitempty"`
	Error *struct {
		Message string `json:"message"`
	} `json:"error,omitempty"`
}

type modelStreamResult struct {
	Content   string
	ToolCalls []modelToolCall
	Usage     *modelTokenUsage
}

type modelTokenUsage struct {
	InputTokens           int64
	OutputTokens          int64
	TotalTokens           int64
	CachedInputTokens     int64
	ReasoningOutputTokens int64
}

type modelClient struct {
	httpClient  *http.Client
	temperature float64
	maxTokens   int
}

func newModelClient() *modelClient {
	return newModelClientWithGeneration(0.7, 8192)
}

func newModelClientWithGeneration(temperature float64, maxTokens int) *modelClient {
	temperature, maxTokens = normalizeGenerationSettings(temperature, maxTokens)
	return &modelClient{
		httpClient:  &http.Client{Timeout: 20 * time.Minute},
		temperature: temperature,
		maxTokens:   maxTokens,
	}
}

func normalizeGenerationSettings(temperature float64, maxTokens int) (float64, int) {
	if math.IsNaN(temperature) || math.IsInf(temperature, 0) || temperature < 0 || temperature > 2 {
		temperature = 0.7
	}
	if maxTokens < 1 || maxTokens > 128_000 {
		maxTokens = 8192
	}
	return temperature, maxTokens
}

func (client *modelClient) streamChat(
	ctx context.Context,
	profile ProviderProfile,
	apiKey string,
	messages []modelMessage,
	tools []any,
	onDelta func(string),
) (modelStreamResult, error) {
	if profile.ProviderID == providerClaude {
		return client.streamAnthropicMessages(ctx, profile, apiKey, messages, tools, onDelta)
	}
	if shouldUseOpenAIResponses(profile) {
		return client.streamOpenAIResponses(ctx, profile, apiKey, messages, tools, onDelta)
	}
	return client.streamOpenAIChat(ctx, profile, apiKey, messages, tools, onDelta)
}

func (client *modelClient) streamOpenAIChat(
	ctx context.Context,
	profile ProviderProfile,
	apiKey string,
	messages []modelMessage,
	tools []any,
	onDelta func(string),
) (modelStreamResult, error) {
	temperature, maxTokens := normalizeGenerationSettings(client.temperature, client.maxTokens)
	requestBody := chatCompletionRequest{
		Model: profile.Model, Messages: convertChatCompletionMessages(messages), Tools: tools,
		ReasoningEffort: profile.ReasoningEffort,
		Temperature:     temperature, MaxTokens: maxTokens, Stream: true,
	}
	if len(tools) > 0 {
		requestBody.ToolChoice = "auto"
	}
	if shouldRequestChatStreamUsage(profile) {
		requestBody.StreamOptions = &chatStreamOptions{IncludeUsage: true}
	}
	data, err := json.Marshal(requestBody)
	if err != nil {
		return modelStreamResult{}, err
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodPost, chatCompletionsEndpoint(profile.BaseURL), bytes.NewReader(data))
	if err != nil {
		return modelStreamResult{}, err
	}
	request.Header.Set("Content-Type", "application/json")
	request.Header.Set("Accept", "text/event-stream")
	request.Header.Set("Authorization", "Bearer "+apiKey)
	request.Header.Set("User-Agent", "Murong-Desktop-Agent/0.1")
	response, err := client.httpClient.Do(request)
	if err != nil {
		return modelStreamResult{}, err
	}
	defer response.Body.Close()
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		body, _ := io.ReadAll(io.LimitReader(response.Body, 64*1024))
		return modelStreamResult{}, fmt.Errorf("模型请求失败 HTTP %d：%s", response.StatusCode, strings.TrimSpace(string(body)))
	}

	var content strings.Builder
	toolCalls := map[int]*modelToolCall{}
	order := make([]int, 0)
	var usage *modelTokenUsage
	scanner := bufio.NewScanner(response.Body)
	scanner.Buffer(make([]byte, 32*1024), 4*1024*1024)
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if !strings.HasPrefix(line, "data:") {
			continue
		}
		payload := strings.TrimSpace(strings.TrimPrefix(line, "data:"))
		if payload == "" || payload == "[DONE]" {
			continue
		}
		var chunk streamResponseChunk
		if err := json.Unmarshal([]byte(payload), &chunk); err != nil {
			continue
		}
		if chunk.Error != nil && chunk.Error.Message != "" {
			return modelStreamResult{}, errors.New(chunk.Error.Message)
		}
		if chunk.Usage != nil {
			usage = modelUsageFromOpenAI(chunk.Usage)
		}
		for _, choice := range chunk.Choices {
			if choice.Delta.Content != "" {
				content.WriteString(choice.Delta.Content)
				if onDelta != nil {
					onDelta(choice.Delta.Content)
				}
			}
			for _, delta := range choice.Delta.ToolCalls {
				call := toolCalls[delta.Index]
				if call == nil {
					call = &modelToolCall{Type: "function"}
					toolCalls[delta.Index] = call
					order = append(order, delta.Index)
				}
				if delta.ID != "" {
					call.ID = delta.ID
				}
				if delta.Type != "" {
					call.Type = delta.Type
				}
				call.Function.Name += delta.Function.Name
				call.Function.Arguments += delta.Function.Arguments
			}
		}
	}
	if err := scanner.Err(); err != nil {
		return modelStreamResult{}, err
	}
	result := modelStreamResult{Content: content.String(), Usage: usage}
	for _, index := range order {
		call := toolCalls[index]
		if call == nil {
			continue
		}
		if call.ID == "" {
			call.ID = newID("tool-call")
		}
		result.ToolCalls = append(result.ToolCalls, *call)
	}
	if result.Content == "" && len(result.ToolCalls) == 0 {
		return result, errors.New("模型流结束但没有返回文本或工具调用")
	}
	return result, nil
}

func convertChatCompletionMessages(messages []modelMessage) []any {
	result := make([]any, 0, len(messages))
	for _, message := range messages {
		converted := map[string]any{"role": message.Role}
		if len(message.Images) > 0 {
			content := make([]any, 0, len(message.Images)+1)
			if strings.TrimSpace(message.Content) != "" {
				content = append(content, map[string]any{"type": "text", "text": message.Content})
			}
			for _, image := range message.Images {
				content = append(content, map[string]any{
					"type":      "image_url",
					"image_url": map[string]any{"url": "data:" + image.MimeType + ";base64," + image.Base64Data},
				})
			}
			converted["content"] = content
		} else if message.Content != "" || message.Role == "user" || message.Role == "tool" {
			converted["content"] = message.Content
		}
		if len(message.ToolCalls) > 0 {
			converted["tool_calls"] = message.ToolCalls
		}
		if message.ToolCallID != "" {
			converted["tool_call_id"] = message.ToolCallID
		}
		if message.Name != "" {
			converted["name"] = message.Name
		}
		result = append(result, converted)
	}
	return result
}

func shouldRequestChatStreamUsage(profile ProviderProfile) bool {
	if profile.ProviderID == providerDeepSeek {
		return true
	}
	parsed, err := url.Parse(strings.TrimSpace(profile.BaseURL))
	return err == nil && strings.EqualFold(parsed.Hostname(), "api.openai.com")
}

func modelUsageFromOpenAI(value *openAIUsagePayload) *modelTokenUsage {
	if value == nil {
		return nil
	}
	input := value.InputTokens
	if input == 0 {
		input = value.PromptTokens
	}
	output := value.OutputTokens
	if output == 0 {
		output = value.CompletionTokens
	}
	total := value.TotalTokens
	if total == 0 {
		total = input + output
	}
	cached := value.InputTokensDetails.CachedTokens
	if cached == 0 {
		cached = value.PromptTokensDetails.CachedTokens
	}
	if cached == 0 {
		cached = value.PromptCacheHitTokens
	}
	reasoning := value.OutputTokensDetails.ReasoningTokens
	if reasoning == 0 {
		reasoning = value.CompletionTokensDetails.ReasoningTokens
	}
	usage := &modelTokenUsage{
		InputTokens: input, OutputTokens: output, TotalTokens: total,
		CachedInputTokens: cached, ReasoningOutputTokens: reasoning,
	}
	if !validModelTokenUsage(usage) {
		return nil
	}
	return usage
}

func modelUsageFromJSON(value any) *modelTokenUsage {
	data, err := json.Marshal(value)
	if err != nil {
		return nil
	}
	var payload openAIUsagePayload
	if err := json.Unmarshal(data, &payload); err != nil {
		return nil
	}
	return modelUsageFromOpenAI(&payload)
}

func validModelTokenUsage(usage *modelTokenUsage) bool {
	if usage == nil {
		return false
	}
	for _, value := range []int64{usage.InputTokens, usage.OutputTokens, usage.TotalTokens, usage.CachedInputTokens, usage.ReasoningOutputTokens} {
		if value < 0 || value > 1_000_000_000_000 {
			return false
		}
	}
	return usage.TotalTokens >= usage.InputTokens+usage.OutputTokens || usage.TotalTokens == 0
}

func tokenCountFromJSON(value any) int64 {
	number, ok := value.(float64)
	if !ok || math.IsNaN(number) || math.IsInf(number, 0) || number < 0 || number > 1_000_000_000_000 || math.Trunc(number) != number {
		return 0
	}
	return int64(number)
}

func chatCompletionsEndpoint(baseURL string) string {
	baseURL = strings.TrimRight(strings.TrimSpace(baseURL), "/")
	if strings.HasSuffix(strings.ToLower(baseURL), "/chat/completions") {
		return baseURL
	}
	return baseURL + "/chat/completions"
}
