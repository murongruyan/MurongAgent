package main

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"image"
	"io"
	"net/http"
	"net/url"
	"os"
	"strings"
	"time"
)

const (
	defaultImageUpscaleBaseURL = "https://api.replicate.com/v1"
	defaultImageUpscaleModel   = "nightmareai/real-esrgan"
	maxImageUpscaleInputBytes  = 32 * 1024 * 1024
	maxImageUpscaleOutputBytes = 64 * 1024 * 1024
)

type replicatePrediction struct {
	ID     string          `json:"id"`
	Status string          `json:"status"`
	Output json.RawMessage `json:"output"`
	Error  string          `json:"error"`
	URLs   struct {
		Get string `json:"get"`
	} `json:"urls"`
}

func (app *DesktopAgentApp) UpscaleImage4K(request UpscaleImageRequest) error {
	request.SessionID = strings.TrimSpace(request.SessionID)
	request.MessageID = strings.TrimSpace(request.MessageID)
	if request.SessionID == "" || request.MessageID == "" {
		return errors.New("会话和原图消息不能为空")
	}
	session := app.store.getSession(request.SessionID)
	if session == nil {
		return errors.New("会话不存在")
	}
	var source *ChatMessage
	for index := range session.Messages {
		if session.Messages[index].ID == request.MessageID {
			source = &session.Messages[index]
			break
		}
	}
	if source == nil || source.ImageGeneration == nil || source.ImageGeneration.Status != "completed" || len(source.ImageAttachments) == 0 {
		return errors.New("只有已完成且有图片缓存的生成结果才能进行 4K 超分")
	}
	attachment := source.ImageAttachments[len(source.ImageAttachments)-1]
	path, err := validateChatImageAttachment(app.store.conversationMediaRoot(), attachment)
	if err != nil {
		return err
	}
	input, err := readFileLimited(path, maxImageUpscaleInputBytes)
	if err != nil {
		return fmt.Errorf("读取原图失败：%w", err)
	}
	config := app.store.rawConfig()
	apiKey, err := imageUpscaleAPIKey(config)
	if err != nil {
		return err
	}

	app.mu.Lock()
	if _, running := app.runs[request.SessionID]; running {
		app.mu.Unlock()
		return errors.New("当前会话正在回复，完成或取消后才能进行 4K 超分")
	}
	if _, running := app.imageRuns[request.SessionID]; running {
		app.mu.Unlock()
		return errors.New("当前会话已有图片任务在运行")
	}
	parent := app.ctx
	if parent == nil {
		parent = context.Background()
	}
	runContext, cancel := context.WithCancel(parent)
	app.imageRuns[request.SessionID] = cancel
	app.mu.Unlock()

	generation := ImageGenerationMessage{
		Prompt: source.ImageGeneration.Prompt, ProviderProfileID: "replicate", Model: config.ImageUpscaleModel,
		Status: "generating", Stage: "正在提交真实 4K 超分任务", Operation: "upscale_4k",
		SourceMessageID: request.MessageID, CreatedAt: time.Now().UnixMilli(),
	}
	updated, err := app.store.appendMessage(request.SessionID, ChatMessage{
		Role: "assistant", Kind: "image_generation", Content: "正在准备真实 4K 超分…", ImageGeneration: &generation,
	})
	if err != nil {
		app.finishImageGeneration(request.SessionID, "error", err.Error())
		return err
	}
	messageID := updated.Messages[len(updated.Messages)-1].ID
	app.emitSessionsChanged(updated)
	app.emit("agent:status", map[string]any{"sessionId": request.SessionID, "state": "upscaling_image", "text": "正在进行真实 4K 超分"})
	go app.runImageUpscale(runContext, request.SessionID, messageID, config, apiKey, attachment.MimeType, input, generation)
	return nil
}

func (app *DesktopAgentApp) runImageUpscale(ctx context.Context, sessionID, messageID string, config desktopConfig, apiKey, mimeType string, input []byte, generation ImageGenerationMessage) {
	defer func() {
		if ctx.Err() != nil {
			generation.Status, generation.Stage, generation.Error = "cancelled", "已取消 4K 超分", ""
			if updated, err := app.store.updateImageGenerationMessage(sessionID, messageID, generation, nil, "已取消 4K 超分"); err == nil {
				app.emitSessionsChanged(updated)
			}
			app.finishImageGeneration(sessionID, "cancelled", "已取消 4K 超分")
		}
	}()
	client := &http.Client{Timeout: 90 * time.Second}
	progress := func(stage string) {
		generation.Stage = stage
		if updated, err := app.store.updateImageGenerationMessage(sessionID, messageID, generation, nil, stage); err == nil {
			app.emitSessionsChanged(updated)
		}
	}
	output, width, height, err := replicateUpscaleImage(ctx, client, config, apiKey, mimeType, input, progress)
	if err != nil {
		if ctx.Err() != nil {
			return
		}
		generation.Status, generation.Stage, generation.Error = "failed", "真实 4K 超分失败", strings.TrimSpace(err.Error())
		if updated, saveErr := app.store.updateImageGenerationMessage(sessionID, messageID, generation, nil, "真实 4K 超分失败："+generation.Error); saveErr == nil {
			app.emitSessionsChanged(updated)
		}
		app.finishImageGeneration(sessionID, "error", "真实 4K 超分失败")
		return
	}
	attachment, err := importUpscaledChatImage(app.store.conversationMediaRoot(), output)
	if err != nil {
		generation.Status, generation.Stage, generation.Error = "failed", "保存 4K 超分结果失败", err.Error()
		if updated, saveErr := app.store.updateImageGenerationMessage(sessionID, messageID, generation, nil, generation.Stage+"："+generation.Error); saveErr == nil {
			app.emitSessionsChanged(updated)
		}
		app.finishImageGeneration(sessionID, "error", "保存 4K 超分结果失败")
		return
	}
	generation.Status, generation.Stage, generation.Error = "completed", fmt.Sprintf("真实 4K 超分完成：%d×%d", width, height), ""
	if updated, saveErr := app.store.updateImageGenerationMessage(sessionID, messageID, generation, []MessageImageAttachment{attachment}, generation.Stage); saveErr == nil {
		app.emitSessionsChanged(updated)
	} else {
		app.finishImageGeneration(sessionID, "error", "4K 超分结果保存失败")
		return
	}
	app.finishImageGeneration(sessionID, "completed", generation.Stage)
}

func imageUpscaleAPIKey(config desktopConfig) (string, error) {
	if strings.TrimSpace(config.ProtectedImageUpscaleAPIKey) == "" {
		return "", errors.New("尚未配置真实 4K 超分 Replicate Token")
	}
	plain, err := unprotectSecret(config.ProtectedImageUpscaleAPIKey)
	if err != nil {
		return "", fmt.Errorf("无法解密真实 4K 超分 Token：%w", err)
	}
	key := strings.TrimSpace(string(plain))
	clearBytes(plain)
	if key == "" {
		return "", errors.New("真实 4K 超分 Replicate Token 为空")
	}
	return key, nil
}

func replicateUpscaleImage(ctx context.Context, client *http.Client, config desktopConfig, apiKey, mimeType string, input []byte, onProgress func(string)) ([]byte, int, int, error) {
	if len(input) == 0 || len(input) > maxImageUpscaleInputBytes {
		return nil, 0, 0, errors.New("待超分图片为空或超过 32 MiB")
	}
	baseURL := strings.TrimRight(strings.TrimSpace(config.ImageUpscaleBaseURL), "/")
	if baseURL == "" {
		baseURL = defaultImageUpscaleBaseURL
	}
	model := strings.Trim(strings.TrimSpace(config.ImageUpscaleModel), "/")
	if model == "" {
		model = defaultImageUpscaleModel
	}
	endpoint, err := replicatePredictionsEndpoint(baseURL, model)
	if err != nil {
		return nil, 0, 0, err
	}
	payload, err := json.Marshal(map[string]any{"input": map[string]any{
		"image": "data:" + normalizeUpscaleMimeType(mimeType) + ";base64," + base64.StdEncoding.EncodeToString(input),
		"scale": max(2, min(4, config.ImageUpscaleScale)),
	}})
	if err != nil {
		return nil, 0, 0, err
	}
	prediction, err := executeReplicatePrediction(ctx, client, http.MethodPost, endpoint, apiKey, payload)
	if err != nil {
		return nil, 0, 0, err
	}
	if prediction.ID == "" || prediction.URLs.Get == "" {
		return nil, 0, 0, errors.New("超分服务没有返回任务 ID 或轮询地址")
	}
	if err := validateReplicatePollURL(prediction.URLs.Get, baseURL); err != nil {
		return nil, 0, 0, err
	}
	deadline := time.Now().Add(15 * time.Minute)
	lastStatus := ""
	for {
		if err := ctx.Err(); err != nil {
			return nil, 0, 0, err
		}
		if time.Now().After(deadline) {
			return nil, 0, 0, errors.New("真实 4K 超分任务超时")
		}
		prediction, err = executeReplicatePrediction(ctx, client, http.MethodGet, prediction.URLs.Get, apiKey, nil)
		if err != nil {
			return nil, 0, 0, err
		}
		status := strings.ToLower(strings.TrimSpace(prediction.Status))
		if status != lastStatus && onProgress != nil {
			lastStatus = status
			onProgress(replicateUpscaleStatusLabel(status))
		}
		switch status {
		case "succeeded":
			outputURL := replicateOutputURL(prediction.Output)
			if outputURL == "" {
				return nil, 0, 0, errors.New("超分服务没有返回图片地址")
			}
			data, err := downloadReplicateOutput(ctx, client, outputURL, baseURL)
			if err != nil {
				return nil, 0, 0, err
			}
			cfg, _, err := image.DecodeConfig(bytes.NewReader(data))
			if err != nil || cfg.Width < 1 || cfg.Height < 1 {
				return nil, 0, 0, errors.New("超分服务返回了无效图片")
			}
			if cfg.Width < 3840 && cfg.Height < 3840 {
				return nil, 0, 0, fmt.Errorf("超分结果未达到 4K：%d×%d", cfg.Width, cfg.Height)
			}
			return data, cfg.Width, cfg.Height, nil
		case "failed":
			if strings.TrimSpace(prediction.Error) != "" {
				return nil, 0, 0, errors.New(truncateRunes(prediction.Error, 500))
			}
			return nil, 0, 0, errors.New("Real-ESRGAN 超分失败")
		case "canceled", "cancelled":
			return nil, 0, 0, context.Canceled
		}
		select {
		case <-ctx.Done():
			return nil, 0, 0, ctx.Err()
		case <-time.After(1500 * time.Millisecond):
		}
	}
}

func executeReplicatePrediction(ctx context.Context, client *http.Client, method, endpoint, apiKey string, payload []byte) (replicatePrediction, error) {
	var body io.Reader
	if len(payload) > 0 {
		body = bytes.NewReader(payload)
	}
	request, err := http.NewRequestWithContext(ctx, method, endpoint, body)
	if err != nil {
		return replicatePrediction{}, err
	}
	request.Header.Set("Authorization", "Token "+strings.TrimSpace(apiKey))
	request.Header.Set("Accept", "application/json")
	request.Header.Set("User-Agent", "Murong-Desktop-Agent/1.37")
	if body != nil {
		request.Header.Set("Content-Type", "application/json")
	}
	response, err := client.Do(request)
	if err != nil {
		return replicatePrediction{}, err
	}
	defer response.Body.Close()
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		return replicatePrediction{}, fmt.Errorf("超分服务请求失败（HTTP %d）", response.StatusCode)
	}
	var result replicatePrediction
	if err := json.NewDecoder(io.LimitReader(response.Body, 2*1024*1024)).Decode(&result); err != nil {
		return replicatePrediction{}, errors.New("超分服务返回了无法解析的数据")
	}
	return result, nil
}

func replicatePredictionsEndpoint(baseURL, model string) (string, error) {
	parsed, err := url.Parse(strings.TrimRight(strings.TrimSpace(baseURL), "/"))
	if err != nil || (parsed.Scheme != "https" && parsed.Scheme != "http") || parsed.Hostname() == "" {
		return "", errors.New("超分 Base URL 无效")
	}
	parts := strings.Split(strings.Trim(model, "/"), "/")
	if len(parts) != 2 || !safeReplicateModelPart(parts[0]) || !safeReplicateModelPart(parts[1]) {
		return "", errors.New("超分模型必须是 owner/model")
	}
	root := strings.TrimRight(parsed.String(), "/")
	if !strings.HasSuffix(strings.ToLower(root), "/v1") {
		root += "/v1"
	}
	return root + "/models/" + parts[0] + "/" + parts[1] + "/predictions", nil
}

func safeReplicateModelPart(value string) bool {
	if value == "" || len(value) > 100 {
		return false
	}
	for _, char := range value {
		if (char >= 'a' && char <= 'z') || (char >= 'A' && char <= 'Z') || (char >= '0' && char <= '9') || strings.ContainsRune("_.-", char) {
			continue
		}
		return false
	}
	return true
}

func validateReplicatePollURL(value, baseURL string) error {
	target, err := url.Parse(value)
	base, baseErr := url.Parse(baseURL)
	if err != nil || baseErr != nil || target.Scheme != base.Scheme || !strings.EqualFold(target.Host, base.Host) {
		return errors.New("超分服务返回了不受信任的轮询地址")
	}
	return nil
}

func replicateOutputURL(raw json.RawMessage) string {
	var text string
	if json.Unmarshal(raw, &text) == nil && (strings.HasPrefix(text, "https://") || strings.HasPrefix(text, "http://")) {
		return text
	}
	var values []json.RawMessage
	if json.Unmarshal(raw, &values) == nil {
		for _, value := range values {
			if candidate := replicateOutputURL(value); candidate != "" {
				return candidate
			}
		}
	}
	return ""
}

func downloadReplicateOutput(ctx context.Context, client *http.Client, value, baseURL string) ([]byte, error) {
	target, err := url.Parse(value)
	base, baseErr := url.Parse(baseURL)
	if err != nil || baseErr != nil {
		return nil, errors.New("超分服务返回了无效图片地址")
	}
	host := strings.ToLower(target.Hostname())
	isReplicateDelivery := target.Scheme == "https" && (host == "replicate.delivery" || strings.HasSuffix(host, ".replicate.delivery"))
	isLoopbackTest := (base.Hostname() == "127.0.0.1" || strings.EqualFold(base.Hostname(), "localhost")) && target.Scheme == base.Scheme && strings.EqualFold(target.Host, base.Host)
	if !isReplicateDelivery && !isLoopbackTest {
		return nil, errors.New("超分服务返回了不受信任的图片地址")
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, value, nil)
	if err != nil {
		return nil, err
	}
	request.Header.Set("Accept", "image/*")
	response, err := client.Do(request)
	if err != nil {
		return nil, err
	}
	defer response.Body.Close()
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		return nil, fmt.Errorf("下载超分结果失败（HTTP %d）", response.StatusCode)
	}
	data, err := io.ReadAll(io.LimitReader(response.Body, maxImageUpscaleOutputBytes+1))
	if err != nil {
		return nil, err
	}
	if len(data) == 0 || len(data) > maxImageUpscaleOutputBytes {
		return nil, errors.New("超分结果为空或超过 64 MiB")
	}
	return data, nil
}

func readFileLimited(path string, limit int) ([]byte, error) {
	file, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer file.Close()
	data, err := io.ReadAll(io.LimitReader(file, int64(limit)+1))
	if err != nil {
		return nil, err
	}
	if len(data) > limit {
		return nil, errors.New("文件超过读取上限")
	}
	return data, nil
}

func normalizeUpscaleMimeType(value string) string {
	switch strings.ToLower(strings.TrimSpace(value)) {
	case "image/jpeg", "image/png", "image/webp":
		return strings.ToLower(strings.TrimSpace(value))
	default:
		return "image/png"
	}
}

func replicateUpscaleStatusLabel(status string) string {
	switch status {
	case "starting":
		return "正在启动真实 4K 超分"
	case "processing":
		return "Real-ESRGAN 正在处理图片"
	case "succeeded":
		return "正在下载 4K 超分结果"
	default:
		return "超分任务状态：" + status
	}
}
