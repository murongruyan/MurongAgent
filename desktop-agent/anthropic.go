package main

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"sort"
	"strings"
)

type anthropicRequest struct {
	Model        string                    `json:"model"`
	MaxTokens    int                       `json:"max_tokens"`
	System       []map[string]any          `json:"system,omitempty"`
	Messages     []anthropicRequestMessage `json:"messages"`
	Tools        []anthropicTool           `json:"tools,omitempty"`
	Thinking     map[string]any            `json:"thinking,omitempty"`
	OutputConfig map[string]any            `json:"output_config,omitempty"`
	Temperature  float64                   `json:"temperature"`
	Stream       bool                      `json:"stream"`
}

type anthropicRequestMessage struct {
	Role    string `json:"role"`
	Content []any  `json:"content"`
}

type anthropicTool struct {
	Name        string         `json:"name"`
	Description string         `json:"description,omitempty"`
	InputSchema map[string]any `json:"input_schema"`
}

func (client *modelClient) streamAnthropicMessages(
	ctx context.Context,
	profile ProviderProfile,
	apiKey string,
	messages []modelMessage,
	tools []any,
	onDelta func(string),
) (modelStreamResult, error) {
	body, err := buildAnthropicRequest(profile, messages, tools, client.temperature, client.maxTokens)
	if err != nil {
		return modelStreamResult{}, err
	}
	data, err := json.Marshal(body)
	if err != nil {
		return modelStreamResult{}, err
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodPost, anthropicMessagesEndpoint(profile.BaseURL), bytes.NewReader(data))
	if err != nil {
		return modelStreamResult{}, err
	}
	request.Header.Set("Content-Type", "application/json")
	request.Header.Set("Accept", "text/event-stream")
	request.Header.Set("x-api-key", apiKey)
	request.Header.Set("anthropic-version", "2023-06-01")
	request.Header.Set("User-Agent", "Murong-Desktop-Agent/0.2")
	response, err := client.httpClient.Do(request)
	if err != nil {
		return modelStreamResult{}, err
	}
	defer response.Body.Close()
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		body, _ := io.ReadAll(io.LimitReader(response.Body, 64*1024))
		return modelStreamResult{}, fmt.Errorf("Claude 请求失败 HTTP %d：%s", response.StatusCode, strings.TrimSpace(string(body)))
	}
	return parseAnthropicStream(response.Body, onDelta)
}

func buildAnthropicRequest(
	profile ProviderProfile,
	messages []modelMessage,
	tools []any,
	temperature float64,
	maxTokens int,
) (anthropicRequest, error) {
	temperature, maxTokens = normalizeGenerationSettings(temperature, maxTokens)
	systemParts := []string{}
	converted := []anthropicRequestMessage{}
	appendMessage := func(role string, blocks ...any) {
		if len(blocks) == 0 {
			return
		}
		if len(converted) > 0 && converted[len(converted)-1].Role == role {
			converted[len(converted)-1].Content = append(converted[len(converted)-1].Content, blocks...)
			return
		}
		converted = append(converted, anthropicRequestMessage{Role: role, Content: blocks})
	}
	for _, message := range messages {
		switch message.Role {
		case "system", "developer":
			if content := strings.TrimSpace(message.Content); content != "" {
				systemParts = append(systemParts, content)
			}
		case "user":
			blocks := []any{}
			if message.Content != "" {
				blocks = append(blocks, map[string]any{"type": "text", "text": message.Content})
			}
			for _, image := range message.Images {
				blocks = append(blocks, map[string]any{
					"type":   "image",
					"source": map[string]any{"type": "base64", "media_type": image.MimeType, "data": image.Base64Data},
				})
			}
			appendMessage("user", blocks...)
		case "assistant":
			blocks := []any{}
			if message.Content != "" {
				blocks = append(blocks, map[string]any{"type": "text", "text": message.Content})
			}
			for _, call := range message.ToolCalls {
				var input any = map[string]any{}
				if strings.TrimSpace(call.Function.Arguments) != "" {
					if err := json.Unmarshal([]byte(call.Function.Arguments), &input); err != nil {
						return anthropicRequest{}, fmt.Errorf("Claude 工具参数无效：%w", err)
					}
				}
				blocks = append(blocks, map[string]any{"type": "tool_use", "id": call.ID, "name": call.Function.Name, "input": input})
			}
			appendMessage("assistant", blocks...)
		case "tool":
			appendMessage("user", map[string]any{
				"type": "tool_result", "tool_use_id": message.ToolCallID, "content": message.Content,
			})
		}
	}
	convertedTools, err := convertAnthropicTools(tools)
	if err != nil {
		return anthropicRequest{}, err
	}
	request := anthropicRequest{
		Model: profile.Model, MaxTokens: maxTokens, Messages: converted, Tools: convertedTools,
		Temperature: temperature, Stream: true,
	}
	if len(systemParts) > 0 {
		request.System = []map[string]any{{"type": "text", "text": strings.Join(systemParts, "\n\n")}}
	}
	if effort := strings.ToLower(strings.TrimSpace(profile.ReasoningEffort)); effort != "" {
		request.Thinking = map[string]any{"type": "adaptive"}
		request.OutputConfig = map[string]any{"effort": effort}
	}
	return request, nil
}

func convertAnthropicTools(tools []any) ([]anthropicTool, error) {
	result := make([]anthropicTool, 0, len(tools))
	for _, raw := range tools {
		wrapper, ok := raw.(map[string]any)
		if !ok {
			return nil, errors.New("工具定义格式无效")
		}
		function, ok := wrapper["function"].(map[string]any)
		if !ok {
			return nil, errors.New("工具定义缺少 function")
		}
		name, _ := function["name"].(string)
		description, _ := function["description"].(string)
		parameters, ok := function["parameters"].(map[string]any)
		if name == "" || !ok {
			return nil, errors.New("工具定义缺少名称或参数")
		}
		result = append(result, anthropicTool{Name: name, Description: description, InputSchema: parameters})
	}
	return result, nil
}

func parseAnthropicStream(reader io.Reader, onDelta func(string)) (modelStreamResult, error) {
	var content strings.Builder
	var usage anthropicUsageAccumulator
	toolCalls := map[int]*modelToolCall{}
	toolArguments := map[int]*strings.Builder{}
	toolInitialArguments := map[int]string{}
	scanner := bufio.NewScanner(reader)
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
		var event map[string]any
		if err := json.Unmarshal([]byte(payload), &event); err != nil {
			continue
		}
		typeName, _ := event["type"].(string)
		if typeName == "error" {
			if body, ok := event["error"].(map[string]any); ok {
				if message, _ := body["message"].(string); message != "" {
					return modelStreamResult{}, errors.New(message)
				}
			}
			return modelStreamResult{}, errors.New("Claude 流返回错误")
		}
		index := intFromJSON(event["index"])
		switch typeName {
		case "message_start":
			if message, ok := event["message"].(map[string]any); ok {
				usage.merge(message["usage"])
			}
		case "message_delta":
			usage.merge(event["usage"])
		case "content_block_start":
			block, _ := event["content_block"].(map[string]any)
			blockType, _ := block["type"].(string)
			if blockType == "text" {
				if text, _ := block["text"].(string); text != "" {
					content.WriteString(text)
					if onDelta != nil {
						onDelta(text)
					}
				}
			} else if blockType == "tool_use" {
				id, _ := block["id"].(string)
				name, _ := block["name"].(string)
				toolCalls[index] = &modelToolCall{ID: id, Type: "function", Function: modelToolFunction{Name: name}}
				toolArguments[index] = &strings.Builder{}
				if input, ok := block["input"]; ok {
					if encoded, err := json.Marshal(input); err == nil && string(encoded) != "{}" {
						toolInitialArguments[index] = string(encoded)
					}
				}
			}
		case "content_block_delta":
			delta, _ := event["delta"].(map[string]any)
			deltaType, _ := delta["type"].(string)
			if deltaType == "text_delta" {
				text, _ := delta["text"].(string)
				content.WriteString(text)
				if onDelta != nil && text != "" {
					onDelta(text)
				}
			} else if deltaType == "input_json_delta" {
				partial, _ := delta["partial_json"].(string)
				if builder := toolArguments[index]; builder != nil {
					builder.WriteString(partial)
				}
			}
		}
	}
	if err := scanner.Err(); err != nil {
		return modelStreamResult{}, err
	}
	result := modelStreamResult{Content: content.String(), Usage: usage.result()}
	indexes := make([]int, 0, len(toolCalls))
	for index := range toolCalls {
		indexes = append(indexes, index)
	}
	sort.Ints(indexes)
	for _, index := range indexes {
		call := toolCalls[index]
		if call.ID == "" {
			call.ID = newID("tool-call")
		}
		arguments := "{}"
		if builder := toolArguments[index]; builder != nil && strings.TrimSpace(builder.String()) != "" {
			arguments = builder.String()
		} else if initial := strings.TrimSpace(toolInitialArguments[index]); initial != "" {
			arguments = initial
		}
		call.Function.Arguments = arguments
		result.ToolCalls = append(result.ToolCalls, *call)
	}
	if result.Content == "" && len(result.ToolCalls) == 0 {
		return result, errors.New("Claude 流结束但没有返回文本或工具调用")
	}
	return result, nil
}

type anthropicUsageAccumulator struct {
	seen                     bool
	inputTokens              int64
	cacheCreationInputTokens int64
	cacheReadInputTokens     int64
	outputTokens             int64
	reasoningOutputTokens    int64
}

func (usage *anthropicUsageAccumulator) merge(value any) {
	body, ok := value.(map[string]any)
	if !ok {
		return
	}
	usage.seen = true
	usage.inputTokens = max(usage.inputTokens, tokenCountFromJSON(body["input_tokens"]))
	usage.cacheCreationInputTokens = max(usage.cacheCreationInputTokens, tokenCountFromJSON(body["cache_creation_input_tokens"]))
	usage.cacheReadInputTokens = max(usage.cacheReadInputTokens, tokenCountFromJSON(body["cache_read_input_tokens"]))
	usage.outputTokens = max(usage.outputTokens, tokenCountFromJSON(body["output_tokens"]))
	if details, ok := body["output_tokens_details"].(map[string]any); ok {
		usage.reasoningOutputTokens = max(usage.reasoningOutputTokens, tokenCountFromJSON(details["thinking_tokens"]))
	}
}

func (usage anthropicUsageAccumulator) result() *modelTokenUsage {
	if !usage.seen {
		return nil
	}
	input := usage.inputTokens + usage.cacheCreationInputTokens + usage.cacheReadInputTokens
	result := &modelTokenUsage{
		InputTokens: input, OutputTokens: usage.outputTokens, TotalTokens: input + usage.outputTokens,
		CachedInputTokens: usage.cacheReadInputTokens, ReasoningOutputTokens: usage.reasoningOutputTokens,
	}
	if !validModelTokenUsage(result) {
		return nil
	}
	return result
}

func anthropicMessagesEndpoint(baseURL string) string {
	baseURL = strings.TrimRight(strings.TrimSpace(baseURL), "/")
	lower := strings.ToLower(baseURL)
	if strings.HasSuffix(lower, "/v1/messages") {
		return baseURL
	}
	if strings.HasSuffix(lower, "/v1") {
		return baseURL + "/messages"
	}
	return baseURL + "/v1/messages"
}

func intFromJSON(value any) int {
	switch typed := value.(type) {
	case float64:
		return int(typed)
	case int:
		return typed
	default:
		return 0
	}
}
