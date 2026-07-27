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
	"strings"
)

// Gemini is intentionally not routed through OpenAI compatibility.  Gemini
// 3.6 Flash uses GenerateContent and rejects several generic sampling fields,
// so a native payload avoids a valid key being turned into a protocol 400.
func (client *modelClient) streamGemini(
	ctx context.Context,
	profile ProviderProfile,
	apiKey string,
	messages []modelMessage,
	tools []any,
	onDelta func(string),
) (modelStreamResult, error) {
	body, err := buildGeminiRequest(messages, tools, client.maxTokens)
	if err != nil {
		return modelStreamResult{}, err
	}
	data, err := json.Marshal(body)
	if err != nil {
		return modelStreamResult{}, err
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodPost, geminiStreamEndpoint(profile.BaseURL, profile.Model), bytes.NewReader(data))
	if err != nil {
		return modelStreamResult{}, err
	}
	request.Header.Set("Content-Type", "application/json")
	request.Header.Set("Accept", "text/event-stream")
	if key := strings.TrimSpace(apiKey); key != "" {
		request.Header.Set("x-goog-api-key", key)
	}
	request.Header.Set("User-Agent", "Murong-Desktop-Agent/0.2")
	response, err := client.httpClient.Do(request)
	if err != nil {
		return modelStreamResult{}, err
	}
	defer response.Body.Close()
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		body, _ := io.ReadAll(io.LimitReader(response.Body, 64*1024))
		return modelStreamResult{}, fmt.Errorf("Gemini 请求失败 HTTP %d：%s", response.StatusCode, strings.TrimSpace(string(body)))
	}
	return parseGeminiStream(response.Body, onDelta)
}

func geminiStreamEndpoint(baseURL, model string) string {
	baseURL = strings.TrimRight(strings.TrimSpace(baseURL), "/")
	model = strings.TrimSpace(model)
	if baseURL == "" {
		baseURL = "https://generativelanguage.googleapis.com/v1beta"
	}
	if model == "" {
		model = "gemini-3.6-flash"
	}
	if strings.Contains(baseURL, ":streamGenerateContent") || strings.Contains(baseURL, ":generateContent") {
		if strings.Contains(baseURL, "?") {
			return baseURL
		}
		return baseURL + "?alt=sse"
	}
	return baseURL + "/models/" + url.PathEscape(model) + ":streamGenerateContent?alt=sse"
}

func buildGeminiRequest(messages []modelMessage, tools []any, maxTokens int) (map[string]any, error) {
	_, maxTokens = normalizeGenerationSettings(0.7, maxTokens)
	systemParts := []string{}
	contents := []any{}
	callNames := map[string]string{}
	for _, message := range messages {
		switch message.Role {
		case "system", "developer":
			if text := strings.TrimSpace(message.Content); text != "" {
				systemParts = append(systemParts, text)
			}
		case "tool":
			name := callNames[message.ToolCallID]
			if name == "" {
				name = message.Name
			}
			if name == "" {
				return nil, errors.New("Gemini 工具结果缺少函数名称")
			}
			contents = append(contents, map[string]any{"role": "user", "parts": []any{map[string]any{
				"functionResponse": map[string]any{"name": name, "response": map[string]any{"result": message.Content}},
			}}})
		default:
			role := "user"
			if message.Role == "assistant" {
				role = "model"
			}
			parts := []any{}
			if message.Content != "" {
				parts = append(parts, map[string]any{"text": message.Content})
			}
			for _, image := range message.Images {
				parts = append(parts, map[string]any{"inlineData": map[string]any{"mimeType": image.MimeType, "data": image.Base64Data}})
			}
			for _, call := range message.ToolCalls {
				args := map[string]any{}
				if raw := strings.TrimSpace(call.Function.Arguments); raw != "" {
					if err := json.Unmarshal([]byte(raw), &args); err != nil {
						return nil, fmt.Errorf("Gemini 工具参数无效：%w", err)
					}
				}
				callNames[call.ID] = call.Function.Name
				parts = append(parts, map[string]any{"functionCall": map[string]any{"name": call.Function.Name, "args": args}})
			}
			if len(parts) == 0 {
				parts = append(parts, map[string]any{"text": ""})
			}
			contents = append(contents, map[string]any{"role": role, "parts": parts})
		}
	}
	request := map[string]any{
		"contents":         contents,
		"generationConfig": map[string]any{"maxOutputTokens": maxTokens},
	}
	if len(systemParts) > 0 {
		request["system_instruction"] = map[string]any{"parts": []any{map[string]any{"text": strings.Join(systemParts, "\n\n")}}}
	}
	if declarations, err := convertGeminiTools(tools); err != nil {
		return nil, err
	} else if len(declarations) > 0 {
		request["tools"] = []any{map[string]any{"function_declarations": declarations}}
	}
	return request, nil
}

func convertGeminiTools(tools []any) ([]any, error) {
	result := make([]any, 0, len(tools))
	for _, raw := range tools {
		wrapper, ok := raw.(map[string]any)
		if !ok {
			return nil, errors.New("Gemini 工具定义格式无效")
		}
		function, ok := wrapper["function"].(map[string]any)
		if !ok {
			return nil, errors.New("Gemini 工具定义缺少 function")
		}
		name, _ := function["name"].(string)
		parameters, ok := function["parameters"].(map[string]any)
		if name == "" || !ok {
			return nil, errors.New("Gemini 工具定义缺少名称或参数")
		}
		declaration := map[string]any{"name": name, "parametersJsonSchema": parameters}
		if description, _ := function["description"].(string); description != "" {
			declaration["description"] = description
		}
		result = append(result, declaration)
	}
	return result, nil
}

func parseGeminiStream(reader io.Reader, onDelta func(string)) (modelStreamResult, error) {
	var content, reasoning strings.Builder
	toolCalls := map[string]modelToolCall{}
	order := []string{}
	var usage *modelTokenUsage
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
		if promptFeedback, ok := event["promptFeedback"].(map[string]any); ok {
			if reason, _ := promptFeedback["blockReasonMessage"].(string); reason != "" {
				return modelStreamResult{}, errors.New(reason)
			}
		}
		if metadata, ok := event["usageMetadata"].(map[string]any); ok {
			usage = &modelTokenUsage{
				InputTokens:  tokenCountFromJSON(metadata["promptTokenCount"]),
				OutputTokens: tokenCountFromJSON(metadata["candidatesTokenCount"]),
				TotalTokens:  tokenCountFromJSON(metadata["totalTokenCount"]),
			}
		}
		candidates, _ := event["candidates"].([]any)
		for _, candidateRaw := range candidates {
			candidate, _ := candidateRaw.(map[string]any)
			message, _ := candidate["content"].(map[string]any)
			parts, _ := message["parts"].([]any)
			for index, partRaw := range parts {
				part, _ := partRaw.(map[string]any)
				text, _ := part["text"].(string)
				thought, _ := part["thought"].(bool)
				if thought {
					reasoning.WriteString(text)
				} else if text != "" {
					content.WriteString(text)
					if onDelta != nil {
						onDelta(text)
					}
				}
				function, _ := part["functionCall"].(map[string]any)
				if function == nil {
					continue
				}
				name, _ := function["name"].(string)
				if name == "" {
					continue
				}
				callID := fmt.Sprintf("gemini-%s-%d", name, index)
				arguments, err := json.Marshal(function["args"])
				if err != nil || string(arguments) == "null" {
					arguments = []byte("{}")
				}
				if _, exists := toolCalls[callID]; !exists {
					order = append(order, callID)
				}
				toolCalls[callID] = modelToolCall{ID: callID, Type: "function", Function: modelToolFunction{Name: name, Arguments: string(arguments)}}
			}
		}
	}
	if err := scanner.Err(); err != nil {
		return modelStreamResult{}, err
	}
	result := modelStreamResult{Content: content.String(), Reasoning: reasoning.String(), Usage: usage}
	for _, id := range order {
		result.ToolCalls = append(result.ToolCalls, toolCalls[id])
	}
	if result.Content == "" && len(result.ToolCalls) == 0 {
		return result, errors.New("Gemini 流结束但没有返回文本或工具调用")
	}
	return result, nil
}
