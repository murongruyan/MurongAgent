package main

import (
	"bufio"
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strconv"
	"strings"
	"time"
)

const maxImageGenerationPromptRunes = 12_000

type openAIImageGenerationRequest struct {
	Model             string `json:"model"`
	Prompt            string `json:"prompt"`
	Size              string `json:"size,omitempty"`
	Quality           string `json:"quality,omitempty"`
	OutputFormat      string `json:"output_format,omitempty"`
	OutputCompression int    `json:"output_compression,omitempty"`
	PartialImages     int    `json:"partial_images,omitempty"`
	Stream            bool   `json:"stream,omitempty"`
}

type openAIImageGenerationResponse struct {
	Data []struct {
		B64JSON string `json:"b64_json"`
	} `json:"data"`
}

func (app *DesktopAgentApp) GenerateImage(request GenerateImageRequest) error {
	request.SessionID = strings.TrimSpace(request.SessionID)
	request.Prompt = strings.TrimSpace(request.Prompt)
	if request.SessionID == "" || request.Prompt == "" {
		return errors.New("会话和图片描述不能为空")
	}
	if len([]rune(request.Prompt)) > maxImageGenerationPromptRunes {
		return fmt.Errorf("图片描述不能超过 %d 字符", maxImageGenerationPromptRunes)
	}
	if app.store.getSession(request.SessionID) == nil {
		return errors.New("会话不存在")
	}
	config := app.store.rawConfig()
	profile, apiKey, err := imageGenerationConnection(config)
	if err != nil {
		return err
	}

	app.mu.Lock()
	if _, running := app.runs[request.SessionID]; running {
		app.mu.Unlock()
		return errors.New("当前会话正在回复，完成或取消后才能生成图片")
	}
	if _, running := app.imageRuns[request.SessionID]; running {
		app.mu.Unlock()
		return errors.New("当前会话已经在生成图片")
	}
	parent := app.ctx
	if parent == nil {
		parent = context.Background()
	}
	runContext, cancel := context.WithCancel(parent)
	app.imageRuns[request.SessionID] = cancel
	app.mu.Unlock()

	generation := ImageGenerationMessage{
		Prompt: request.Prompt, ProviderProfileID: profile.ID, Model: profile.Model,
		Status: "generating", Stage: "正在提交图片生成请求", CreatedAt: time.Now().UnixMilli(),
	}
	updated, err := app.store.appendMessage(request.SessionID, ChatMessage{
		Role: "assistant", Kind: "image_generation", Content: "正在根据描述生成图片…", ImageGeneration: &generation,
	})
	if err != nil {
		app.finishImageGeneration(request.SessionID, "error", err.Error())
		return err
	}
	messageID := updated.Messages[len(updated.Messages)-1].ID
	app.emitSessionsChanged(updated)
	app.emit("agent:status", map[string]any{"sessionId": request.SessionID, "state": "generating_image", "text": "正在生成图片"})
	go app.runImageGeneration(runContext, request.SessionID, messageID, config, profile, apiKey, generation)
	return nil
}

func (app *DesktopAgentApp) CancelImageGeneration(sessionID string) bool {
	app.mu.Lock()
	cancel := app.imageRuns[strings.TrimSpace(sessionID)]
	app.mu.Unlock()
	if cancel == nil {
		return false
	}
	cancel()
	return true
}

func (app *DesktopAgentApp) runImageGeneration(ctx context.Context, sessionID, messageID string, config desktopConfig, profile ProviderProfile, apiKey string, generation ImageGenerationMessage) {
	defer func() {
		if ctx.Err() != nil {
			generation.Status = "cancelled"
			generation.Stage = "已取消"
			generation.Error = ""
			if updated, err := app.store.updateImageGenerationMessage(sessionID, messageID, generation, nil, "已取消图片生成"); err == nil {
				app.emitSessionsChanged(updated)
			}
			app.finishImageGeneration(sessionID, "cancelled", "已取消图片生成")
		}
	}()

	client := &http.Client{Timeout: 20 * time.Minute}
	attachments := []MessageImageAttachment{}
	preview := func(data []byte) {
		attachment, err := importGeneratedChatImage(app.store.conversationMediaRoot(), data, 1)
		if err != nil {
			return
		}
		generation.Stage = "正在生成预览"
		if updated, err := app.store.updateImageGenerationMessage(sessionID, messageID, generation, []MessageImageAttachment{attachment}, "正在生成图片预览…"); err == nil {
			app.emitSessionsChanged(updated)
		}
	}
	images, err := generateOpenAIImage(ctx, client, config, profile, apiKey, generation.Prompt, preview)
	if err != nil {
		if ctx.Err() != nil {
			return
		}
		generation.Status = "failed"
		generation.Stage = "生成失败"
		generation.Error = strings.TrimSpace(err.Error())
		if updated, saveErr := app.store.updateImageGenerationMessage(sessionID, messageID, generation, nil, "图片生成失败："+generation.Error); saveErr == nil {
			app.emitSessionsChanged(updated)
		}
		app.finishImageGeneration(sessionID, "error", "图片生成失败")
		return
	}
	for index, data := range images {
		attachment, importErr := importGeneratedChatImage(app.store.conversationMediaRoot(), data, index+1)
		if importErr != nil {
			generation.Status = "failed"
			generation.Stage = "保存生成结果失败"
			generation.Error = importErr.Error()
			if updated, saveErr := app.store.updateImageGenerationMessage(sessionID, messageID, generation, attachments, "图片生成失败："+generation.Error); saveErr == nil {
				app.emitSessionsChanged(updated)
			}
			app.finishImageGeneration(sessionID, "error", "图片生成失败")
			return
		}
		attachments = append(attachments, attachment)
	}
	generation.Status = "completed"
	generation.Stage = "已完成"
	generation.Error = ""
	if updated, saveErr := app.store.updateImageGenerationMessage(sessionID, messageID, generation, attachments, "图片已生成"); saveErr == nil {
		app.emitSessionsChanged(updated)
	} else {
		app.finishImageGeneration(sessionID, "error", "图片生成结果保存失败")
		return
	}
	app.finishImageGeneration(sessionID, "completed", "图片已生成")
}

func (app *DesktopAgentApp) finishImageGeneration(sessionID, state, text string) {
	app.mu.Lock()
	delete(app.imageRuns, strings.TrimSpace(sessionID))
	app.mu.Unlock()
	app.emit("agent:status", map[string]any{"sessionId": sessionID, "state": state, "text": text})
}

func imageGenerationConnection(config desktopConfig) (ProviderProfile, string, error) {
	profileID := strings.TrimSpace(config.ImageGenerationProviderProfileID)
	if profileID == "" {
		profileID = config.ActiveProviderProfileID
	}
	profile := findProviderProfile(config.ProviderProfiles, profileID)
	if profile == nil {
		return ProviderProfile{}, "", errors.New("未选择可用的图片生成连接")
	}
	if profile.ProviderID != providerOpenAI {
		return ProviderProfile{}, "", errors.New("图片生成仅支持 OpenAI Images 兼容连接；请在设置中选择 OpenAI-compatible")
	}
	resolved := *profile
	if model := strings.TrimSpace(config.ImageGenerationModel); model != "" {
		resolved.Model = model
	}
	if baseURL := strings.TrimSpace(config.ImageGenerationCustomBaseURL); baseURL != "" {
		resolved.BaseURL = baseURL
	}
	apiKey := ""
	protected := resolved.ProtectedAPIKey
	if config.ProtectedImageGenerationAPIKey != "" {
		protected = config.ProtectedImageGenerationAPIKey
	}
	if protected == "" && !isLocalModelBaseURL(resolved.BaseURL) {
		return ProviderProfile{}, "", errors.New("图片生成连接尚未配置 API Key")
	}
	if protected != "" {
		value, err := unprotectSecret(protected)
		if err != nil {
			return ProviderProfile{}, "", fmt.Errorf("无法解密图片生成 API Key：%w", err)
		}
		apiKey = string(value)
	}
	return resolved, apiKey, nil
}

func generateOpenAIImage(ctx context.Context, client *http.Client, config desktopConfig, profile ProviderProfile, apiKey, prompt string, onPreview func([]byte)) ([][]byte, error) {
	requestBody := openAIImageGenerationRequest{Model: profile.Model, Prompt: prompt, Size: config.ImageGenerationSize}
	if usesGPTImageOptions(profile.Model) {
		requestBody.Quality = normalizeImageGenerationQuality(config.ImageGenerationQuality)
		requestBody.OutputFormat = normalizeImageGenerationFormat(config.ImageGenerationFormat)
		if requestBody.OutputFormat != "png" {
			requestBody.OutputCompression = clampImageGenerationCompression(config.ImageGenerationCompression)
		}
		requestBody.PartialImages = config.ImageGenerationPartialImages
		requestBody.Stream = requestBody.PartialImages > 0
	}
	requestBody.Size = normalizeImageGenerationSize(requestBody.Size)
	payload, err := json.Marshal(requestBody)
	if err != nil {
		return nil, err
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodPost, imageGenerationsEndpoint(profile.BaseURL), bytes.NewReader(payload))
	if err != nil {
		return nil, err
	}
	request.Header.Set("Content-Type", "application/json")
	request.Header.Set("Accept", "application/json, text/event-stream")
	if apiKey != "" {
		if strings.Contains(strings.ToLower(profile.BaseURL), ".openai.azure.com") {
			request.Header.Set("api-key", apiKey)
		} else {
			request.Header.Set("Authorization", "Bearer "+apiKey)
		}
	}
	request.Header.Set("User-Agent", "Murong-Desktop-Agent/1.37")
	response, err := client.Do(request)
	if err != nil {
		return nil, err
	}
	defer response.Body.Close()
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		body, _ := io.ReadAll(io.LimitReader(response.Body, 64*1024))
		return nil, fmt.Errorf("图片生成请求失败 HTTP %d：%s", response.StatusCode, strings.TrimSpace(string(body)))
	}
	if strings.Contains(strings.ToLower(response.Header.Get("Content-Type")), "text/event-stream") {
		return parseOpenAIImageGenerationStream(response.Body, onPreview)
	}
	return parseOpenAIImageGenerationResponse(response.Body)
}

func parseOpenAIImageGenerationResponse(reader io.Reader) ([][]byte, error) {
	var response openAIImageGenerationResponse
	decoder := json.NewDecoder(io.LimitReader(reader, 32*1024*1024))
	if err := decoder.Decode(&response); err != nil {
		return nil, errors.New("图片生成服务返回了无效响应")
	}
	return decodeImageGenerationPayloads(response.Data)
}

func parseOpenAIImageGenerationStream(reader io.Reader, onPreview func([]byte)) ([][]byte, error) {
	scanner := bufio.NewScanner(reader)
	scanner.Buffer(make([]byte, 32*1024), 32*1024*1024)
	var latest []byte
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if !strings.HasPrefix(line, "data:") {
			continue
		}
		payload := strings.TrimSpace(strings.TrimPrefix(line, "data:"))
		if payload == "" || payload == "[DONE]" {
			continue
		}
		var envelope map[string]json.RawMessage
		if json.Unmarshal([]byte(payload), &envelope) != nil {
			continue
		}
		if strings.EqualFold(jsonString(envelope["type"]), "error") {
			return nil, errors.New("图片生成流返回错误")
		}
		for _, encoded := range imageGenerationBase64Values(envelope) {
			data, err := base64.StdEncoding.DecodeString(encoded)
			if err != nil || len(data) == 0 {
				continue
			}
			latest = data
			if onPreview != nil {
				onPreview(data)
			}
		}
	}
	if err := scanner.Err(); err != nil {
		return nil, err
	}
	if len(latest) == 0 {
		return nil, errors.New("图片生成流未返回最终图片")
	}
	return [][]byte{latest}, nil
}

func imageGenerationBase64Values(values map[string]json.RawMessage) []string {
	result := []string{}
	for _, key := range []string{"b64_json", "b64Json", "partial_image_b64"} {
		if value := strings.TrimSpace(jsonString(values[key])); value != "" {
			result = append(result, value)
		}
	}
	for _, parent := range []string{"image", "data", "output"} {
		var nested map[string]json.RawMessage
		if json.Unmarshal(values[parent], &nested) == nil {
			result = append(result, imageGenerationBase64Values(nested)...)
		}
	}
	return result
}

func jsonString(raw json.RawMessage) string {
	var value string
	_ = json.Unmarshal(raw, &value)
	return value
}

func decodeImageGenerationPayloads(values []struct {
	B64JSON string `json:"b64_json"`
}) ([][]byte, error) {
	result := make([][]byte, 0, len(values))
	for _, value := range values {
		data, err := base64.StdEncoding.DecodeString(strings.TrimSpace(value.B64JSON))
		if err != nil || len(data) == 0 {
			continue
		}
		result = append(result, data)
	}
	if len(result) == 0 {
		return nil, errors.New("图片生成服务未返回可保存的 Base64 图片")
	}
	return result, nil
}

func usesGPTImageOptions(model string) bool {
	return strings.HasPrefix(strings.ToLower(strings.TrimSpace(model)), "gpt-image")
}

func imageGenerationsEndpoint(baseURL string) string {
	baseURL = strings.TrimRight(strings.TrimSpace(baseURL), "/")
	lower := strings.ToLower(baseURL)
	if strings.HasSuffix(lower, "/images/generations") {
		return baseURL
	}
	for _, suffix := range []string{"/chat/completions", "/responses"} {
		if strings.HasSuffix(lower, suffix) {
			baseURL = strings.TrimRight(baseURL[:len(baseURL)-len(suffix)], "/")
			break
		}
	}
	if !strings.Contains(strings.ToLower(baseURL), "/v1") {
		baseURL += "/v1"
	}
	return baseURL + "/images/generations"
}

func normalizeImageGenerationSize(value string) string {
	value = strings.ToLower(strings.TrimSpace(value))
	if value == "auto" {
		return value
	}
	parts := strings.Split(value, "x")
	if len(parts) != 2 {
		return "1024x1024"
	}
	width, widthErr := strconv.Atoi(parts[0])
	height, heightErr := strconv.Atoi(parts[1])
	if widthErr != nil || heightErr != nil || width < 256 || width > 3840 || height < 256 || height > 3840 {
		return "1024x1024"
	}
	if width%16 != 0 || height%16 != 0 {
		return "1024x1024"
	}
	pixels := int64(width) * int64(height)
	if pixels < 655360 || pixels > 8294400 {
		return "1024x1024"
	}
	longEdge, shortEdge := width, height
	if shortEdge > longEdge {
		longEdge, shortEdge = shortEdge, longEdge
	}
	if float64(longEdge)/float64(shortEdge) > 3.0 {
		return "1024x1024"
	}
	return fmt.Sprintf("%dx%d", width, height)
}

func normalizeImageGenerationQuality(value string) string {
	switch strings.ToLower(strings.TrimSpace(value)) {
	case "low", "medium", "high", "auto":
		return strings.ToLower(strings.TrimSpace(value))
	default:
		return "auto"
	}
}

func normalizeImageGenerationFormat(value string) string {
	switch strings.ToLower(strings.TrimSpace(value)) {
	case "png", "jpeg", "webp":
		return strings.ToLower(strings.TrimSpace(value))
	default:
		return "png"
	}
}

func clampImageGenerationCompression(value int) int {
	if value < 0 || value > 100 {
		return 90
	}
	return value
}
