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
	"net/url"
	"sort"
	"strings"
)

type openAIResponsesRequest struct {
	Model             string         `json:"model"`
	Input             []any          `json:"input"`
	Tools             []any          `json:"tools,omitempty"`
	ToolChoice        string         `json:"tool_choice,omitempty"`
	ParallelToolCalls bool           `json:"parallel_tool_calls"`
	Reasoning         map[string]any `json:"reasoning,omitempty"`
	Temperature       float64        `json:"temperature"`
	MaxOutputTokens   int            `json:"max_output_tokens"`
	Stream            bool           `json:"stream"`
}

func shouldUseOpenAIResponses(profile ProviderProfile) bool {
	if profile.ProviderID != providerOpenAI {
		return false
	}
	switch profile.APIMode {
	case "responses":
		return true
	case "chat-completions":
		return false
	}
	parsed, err := url.Parse(profile.BaseURL)
	if err != nil {
		return false
	}
	host := strings.ToLower(parsed.Hostname())
	path := strings.ToLower(strings.TrimRight(parsed.Path, "/"))
	return host == "api.openai.com" && !strings.HasSuffix(path, "/chat/completions")
}

func (client *modelClient) streamOpenAIResponses(
	ctx context.Context,
	profile ProviderProfile,
	apiKey string,
	messages []modelMessage,
	tools []any,
	onDelta func(string),
) (modelStreamResult, error) {
	input, err := convertOpenAIResponsesInput(messages)
	if err != nil {
		return modelStreamResult{}, err
	}
	convertedTools, err := convertOpenAIResponsesTools(tools)
	if err != nil {
		return modelStreamResult{}, err
	}
	temperature, maxTokens := normalizeGenerationSettings(client.temperature, client.maxTokens)
	body := openAIResponsesRequest{
		Model: profile.Model, Input: input, Tools: convertedTools,
		ParallelToolCalls: true, Temperature: temperature,
		MaxOutputTokens: maxTokens, Stream: true,
	}
	if len(convertedTools) > 0 {
		body.ToolChoice = "auto"
	}
	if effort := strings.TrimSpace(profile.ReasoningEffort); effort != "" {
		body.Reasoning = map[string]any{"effort": effort}
	}
	data, err := json.Marshal(body)
	if err != nil {
		return modelStreamResult{}, err
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodPost, openAIResponsesEndpoint(profile.BaseURL), bytes.NewReader(data))
	if err != nil {
		return modelStreamResult{}, err
	}
	request.Header.Set("Content-Type", "application/json")
	request.Header.Set("Accept", "text/event-stream")
	request.Header.Set("Authorization", "Bearer "+apiKey)
	request.Header.Set("User-Agent", "Murong-Desktop-Agent/0.2")
	response, err := client.httpClient.Do(request)
	if err != nil {
		return modelStreamResult{}, err
	}
	defer response.Body.Close()
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		responseBody, _ := io.ReadAll(io.LimitReader(response.Body, 64*1024))
		return modelStreamResult{}, fmt.Errorf("OpenAI Responses 请求失败 HTTP %d：%s", response.StatusCode, strings.TrimSpace(string(responseBody)))
	}
	return parseOpenAIResponsesStream(response.Body, onDelta)
}

func convertOpenAIResponsesInput(messages []modelMessage) ([]any, error) {
	result := []any{}
	for _, message := range messages {
		switch message.Role {
		case "system", "developer", "user":
			content := []any{}
			if message.Content != "" {
				content = append(content, map[string]any{"type": "input_text", "text": message.Content})
			}
			if message.Role == "user" {
				for _, image := range message.Images {
					content = append(content, map[string]any{
						"type": "input_image", "image_url": "data:" + image.MimeType + ";base64," + image.Base64Data,
					})
				}
			}
			if len(content) > 0 {
				result = append(result, map[string]any{"role": message.Role, "content": content})
			}
		case "assistant":
			if message.Content != "" {
				result = append(result, map[string]any{
					"type": "message", "role": "assistant", "content": []any{map[string]any{"type": "output_text", "text": message.Content}},
				})
			}
			for _, call := range message.ToolCalls {
				arguments := strings.TrimSpace(call.Function.Arguments)
				if arguments == "" {
					arguments = "{}"
				}
				if !json.Valid([]byte(arguments)) {
					return nil, fmt.Errorf("Responses 工具参数无效：%s", call.Function.Name)
				}
				result = append(result, map[string]any{
					"type": "function_call", "call_id": call.ID, "name": call.Function.Name, "arguments": arguments,
				})
			}
		case "tool":
			result = append(result, map[string]any{
				"type": "function_call_output", "call_id": message.ToolCallID, "output": message.Content,
			})
		}
	}
	return result, nil
}

func convertOpenAIResponsesTools(tools []any) ([]any, error) {
	result := make([]any, 0, len(tools))
	for _, raw := range tools {
		wrapper, ok := raw.(map[string]any)
		if !ok {
			return nil, errors.New("Responses 工具定义格式无效")
		}
		function, ok := wrapper["function"].(map[string]any)
		if !ok {
			return nil, errors.New("Responses 工具定义缺少 function")
		}
		name, _ := function["name"].(string)
		parameters, ok := function["parameters"].(map[string]any)
		if name == "" || !ok {
			return nil, errors.New("Responses 工具定义缺少名称或参数")
		}
		converted := map[string]any{"type": "function", "name": name, "parameters": parameters, "strict": true}
		if description, _ := function["description"].(string); description != "" {
			converted["description"] = description
		}
		result = append(result, converted)
	}
	return result, nil
}

func parseOpenAIResponsesStream(reader io.Reader, onDelta func(string)) (modelStreamResult, error) {
	var content strings.Builder
	var usage *modelTokenUsage
	toolCalls := map[int]*modelToolCall{}
	arguments := map[int]*strings.Builder{}
	completedArguments := map[int]string{}
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
		if json.Unmarshal([]byte(payload), &event) != nil {
			continue
		}
		typeName, _ := event["type"].(string)
		switch typeName {
		case "response.output_text.delta":
			delta, _ := event["delta"].(string)
			content.WriteString(delta)
			if onDelta != nil && delta != "" {
				onDelta(delta)
			}
		case "response.output_item.added", "response.output_item.done":
			index := intFromJSON(event["output_index"])
			item, _ := event["item"].(map[string]any)
			if itemType, _ := item["type"].(string); itemType != "function_call" {
				continue
			}
			call := toolCalls[index]
			if call == nil {
				call = &modelToolCall{Type: "function"}
				toolCalls[index] = call
				arguments[index] = &strings.Builder{}
			}
			if callID, _ := item["call_id"].(string); callID != "" {
				call.ID = callID
			}
			if name, _ := item["name"].(string); name != "" {
				call.Function.Name = name
			}
			if complete, _ := item["arguments"].(string); complete != "" {
				completedArguments[index] = complete
			}
		case "response.function_call_arguments.delta":
			index := intFromJSON(event["output_index"])
			if arguments[index] == nil {
				arguments[index] = &strings.Builder{}
			}
			delta, _ := event["delta"].(string)
			arguments[index].WriteString(delta)
		case "response.completed":
			if response, ok := event["response"].(map[string]any); ok {
				usage = modelUsageFromJSON(response["usage"])
			}
		case "error", "response.failed":
			message := openAIResponsesErrorMessage(event)
			if message == "" {
				message = "OpenAI Responses 流返回错误"
			}
			return modelStreamResult{}, errors.New(message)
		}
	}
	if err := scanner.Err(); err != nil {
		return modelStreamResult{}, err
	}
	result := modelStreamResult{Content: content.String(), Usage: usage}
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
		value := completedArguments[index]
		if builder := arguments[index]; builder != nil && strings.TrimSpace(builder.String()) != "" {
			value = builder.String()
		}
		if strings.TrimSpace(value) == "" {
			value = "{}"
		}
		call.Function.Arguments = value
		result.ToolCalls = append(result.ToolCalls, *call)
	}
	if result.Content == "" && len(result.ToolCalls) == 0 {
		return result, errors.New("OpenAI Responses 流结束但没有返回文本或工具调用")
	}
	return result, nil
}

func openAIResponsesErrorMessage(event map[string]any) string {
	if errorBody, ok := event["error"].(map[string]any); ok {
		if message, _ := errorBody["message"].(string); message != "" {
			return message
		}
	}
	if response, ok := event["response"].(map[string]any); ok {
		if errorBody, ok := response["error"].(map[string]any); ok {
			message, _ := errorBody["message"].(string)
			return message
		}
	}
	return ""
}

func openAIResponsesEndpoint(baseURL string) string {
	baseURL = strings.TrimRight(strings.TrimSpace(baseURL), "/")
	lower := strings.ToLower(baseURL)
	if strings.HasSuffix(lower, "/responses") {
		return baseURL
	}
	if strings.HasSuffix(lower, "/chat/completions") {
		baseURL = baseURL[:len(baseURL)-len("/chat/completions")]
	}
	return strings.TrimRight(baseURL, "/") + "/responses"
}
