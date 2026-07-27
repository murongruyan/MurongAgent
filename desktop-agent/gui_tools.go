package main

import (
	"context"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"
)

func guiToolDefinition() any {
	return functionTool("gui",
		"操作 Windows 图形界面。优先 observe 获取 UI Automation 语义树，再按最近 observation 的 nodeId 点击或输入；只有自绘控件、游戏或语义树失效时才使用 vision_query。界面变化后必须重新 observe。",
		map[string]any{
			"target":      map[string]any{"type": "string", "enum": []string{"windows"}},
			"action":      map[string]any{"type": "string", "enum": []string{"observe", "click", "long_click", "input", "scroll", "tap", "swipe", "key", "launch", "wait", "screenshot", "vision_query"}},
			"nodeId":      map[string]any{"type": "string", "description": "最近一次 observe 返回的临时节点 ID"},
			"text":        map[string]any{"type": "string"},
			"x":           map[string]any{"type": "integer"},
			"y":           map[string]any{"type": "integer"},
			"startX":      map[string]any{"type": "integer"},
			"startY":      map[string]any{"type": "integer"},
			"endX":        map[string]any{"type": "integer"},
			"endY":        map[string]any{"type": "integer"},
			"durationMs":  map[string]any{"type": "integer", "minimum": 1, "maximum": 5000},
			"direction":   map[string]any{"type": "string", "enum": []string{"forward", "backward", "up", "down", "left", "right"}},
			"key":         map[string]any{"type": "string", "description": "如 enter、ctrl+l、alt+f4、f5"},
			"application": map[string]any{"type": "string", "description": "launch 的可执行文件路径或已注册应用名"},
			"waitMs":      map[string]any{"type": "integer", "minimum": 0, "maximum": 60000},
			"maxNodes":    map[string]any{"type": "integer", "minimum": 1, "maximum": 500},
			"prompt":      map[string]any{"type": "string"},
			"cropLeft":    map[string]any{"type": "integer", "minimum": 0},
			"cropTop":     map[string]any{"type": "integer", "minimum": 0},
			"cropRight":   map[string]any{"type": "integer", "minimum": 1},
			"cropBottom":  map[string]any{"type": "integer", "minimum": 1},
		},
		[]string{"action"},
	)
}

func (app *DesktopAgentApp) executeGUITool(
	ctx context.Context,
	sessionID string,
	config desktopConfig,
	call modelToolCall,
	raw map[string]json.RawMessage,
) (string, error) {
	action := strings.ToLower(rawJSONString(raw, "action"))
	if action == "" {
		return "", errors.New("gui 缺少 action")
	}
	if action == "open" {
		action = "launch"
	}
	target := strings.ToLower(rawJSONString(raw, "target"))
	if target == "null" {
		target = ""
	}
	if target != "" && target != "windows" {
		return "", errors.New("桌面端 gui 只支持 target=windows")
	}
	switch action {
	case "click", "long_click":
		if rawJSONString(raw, "nodeId") == "" && !(rawJSONHas(raw, "x") && rawJSONHas(raw, "y")) {
			return "", fmt.Errorf("%s 需要 nodeId 或 x/y", action)
		}
	case "tap":
		if !rawJSONHas(raw, "x") || !rawJSONHas(raw, "y") {
			return "", errors.New("tap 需要 x/y")
		}
	case "swipe":
		for _, key := range []string{"startX", "startY", "endX", "endY"} {
			if !rawJSONHas(raw, key) {
				return "", fmt.Errorf("swipe 缺少 %s", key)
			}
		}
	case "key":
		if rawJSONString(raw, "key") == "" {
			return "", errors.New("key 缺少按键")
		}
	case "launch":
		if rawJSONString(raw, "application") == "" {
			return "", errors.New("launch 缺少 application")
		}
	}
	risk := "high"
	if action == "observe" || action == "wait" {
		risk = "low"
	}
	summary := map[string]string{
		"observe": "读取前台窗口精简语义树", "wait": "等待界面并重新观察",
		"screenshot": "在内存中截取 Windows 屏幕", "vision_query": "使用视觉模型识别 Windows 屏幕",
		"input": "向 Windows 界面输入文本", "launch": "启动 Windows 应用",
	}[action]
	if summary == "" {
		summary = "执行 Windows GUI 操作：" + action
	}
	if err := app.authorizeTool(ctx, config, ApprovalRequest{
		ID: newID("approval"), SessionID: sessionID, ToolName: call.Function.Name,
		Summary: summary, Detail: guiApprovalDetail(raw, action), Arguments: call.Function.Arguments, Risk: risk,
	}, "gui:"+action); err != nil {
		return "", err
	}

	switch action {
	case "observe":
		observation, err := guiPlatformObserve(
			ctx,
			rawJSONInt(raw, "maxNodes", 180),
			activeDesktopProviderIsLocal(config) || config.GuiAllowRemoteSemantic,
		)
		if err != nil {
			return "", err
		}
		return marshalToolResult(guiToolResponse{
			Success: observation.Success, Target: "windows", Action: action, Source: observation.Source, Observation: &observation, Error: observation.Error,
		}), nil
	case "wait":
		wait := time.Duration(clampInt(rawJSONInt(raw, "waitMs", 500), 0, 60000)) * time.Millisecond
		timer := time.NewTimer(wait)
		defer timer.Stop()
		select {
		case <-ctx.Done():
			return "", ctx.Err()
		case <-timer.C:
		}
		observation, err := guiPlatformObserve(
			ctx,
			rawJSONInt(raw, "maxNodes", 180),
			activeDesktopProviderIsLocal(config) || config.GuiAllowRemoteSemantic,
		)
		if err != nil {
			return "", err
		}
		return marshalToolResult(guiToolResponse{
			Success: observation.Success, Target: "windows", Action: action, Source: observation.Source, Observation: &observation, Error: observation.Error,
		}), nil
	case "screenshot":
		screenshot, err := guiPlatformScreenshot(ctx, raw)
		if err != nil {
			return "", err
		}
		return marshalToolResult(guiScreenshotResponse(action, screenshot, "截图仅在内存中生成，未写入磁盘，也未把像素写入工具日志。")), nil
	case "vision_query":
		return app.executeGUIVisionQuery(ctx, config, raw)
	case "click", "long_click", "input", "scroll", "tap", "swipe", "key", "launch":
		output, err := guiPlatformAction(ctx, action, raw)
		if err != nil {
			return "", err
		}
		var platformResult struct {
			Success bool   `json:"success"`
			Source  string `json:"source"`
		}
		if err := json.Unmarshal([]byte(output), &platformResult); err != nil {
			return "", fmt.Errorf("无法解析 Windows GUI 操作结果：%w", err)
		}
		return marshalToolResult(guiToolResponse{
			Success: platformResult.Success, Target: "windows", Action: action, Source: platformResult.Source,
			Message: "操作已提交；请重新 observe 验证界面变化。",
		}), nil
	default:
		return "", fmt.Errorf("不支持的 Windows GUI action：%s", action)
	}
}

func (app *DesktopAgentApp) executeGUIVisionQuery(
	ctx context.Context,
	config desktopConfig,
	raw map[string]json.RawMessage,
) (string, error) {
	prompt := rawJSONString(raw, "prompt")
	if prompt == "" {
		return "", errors.New("vision_query 缺少 prompt")
	}
	if len([]rune(prompt)) > 4000 {
		prompt = string([]rune(prompt)[:4000])
	}
	screenshot, err := guiPlatformScreenshot(ctx, raw)
	if err != nil {
		return "", err
	}
	messages := []modelMessage{
		{Role: "system", Content: `你是 GUI 视觉定位器。只分析当前截图，不推测屏幕外内容。优先返回严格 JSON：{"summary":"界面摘要","targetFound":true,"x":123,"y":456,"confidence":0.92,"reason":"简短依据"}。目标不存在时 targetFound=false，坐标为 null。`},
		{Role: "user", Content: prompt, Images: []modelImageAttachment{{MimeType: screenshot.MimeType, Base64Data: screenshot.Base64}}},
	}
	run := func(profile ProviderProfile, apiKey string) (string, error) {
		client := newModelClientWithGeneration(0.1, 800)
		result, err := client.streamChat(ctx, profile, apiKey, messages, nil, nil)
		if err != nil {
			return "", err
		}
		if strings.TrimSpace(result.Content) == "" {
			return "", errors.New("视觉模型没有返回内容")
		}
		return strings.TrimSpace(result.Content), nil
	}
	localAttempt := func() (string, string, error) {
		descriptor, modelDir, builtInErr := app.vision.ActiveVisionDescriptor()
		if builtInErr == nil {
			result, err := app.visionRuntime.Infer(ctx, descriptor, modelDir, prompt, screenshot)
			if err == nil {
				return "builtin_vlm", result, nil
			}
			builtInErr = err
		}
		if config.GuiLocalBaseURL != "" || config.GuiLocalModel != "" {
			if config.GuiLocalBaseURL == "" || !isLocalModelBaseURL(config.GuiLocalBaseURL) {
				return "", "", errors.New("高级本地视觉服务缺少合法的 Base URL")
			}
			if config.GuiLocalModel == "" {
				return "", "", errors.New("高级本地视觉服务缺少模型名")
			}
			result, err := run(ProviderProfile{
				ProviderID: providerOpenAI, Name: "高级本地 GUI 视觉服务", BaseURL: config.GuiLocalBaseURL,
				Model: config.GuiLocalModel, APIMode: "chat-completions",
			}, "")
			return "external_local", result, err
		}
		return "", "", builtInErr
	}
	apiAttempt := func() (string, error) {
		profile := findProviderProfile(config.ProviderProfiles, config.ActiveProviderProfileID)
		if profile == nil {
			return "", errors.New("当前模型连接不存在")
		}
		if profile.ProviderID == providerBuiltinLocal {
			profile = nil
			for index := range config.ProviderProfiles {
				candidate := &config.ProviderProfiles[index]
				if candidate.ProviderID == providerBuiltinLocal ||
					candidate.ProviderID == providerCodex {
					continue
				}
				if candidate.ProtectedAPIKey != "" || isLocalModelBaseURL(candidate.BaseURL) {
					profile = candidate
					break
				}
			}
			if profile == nil {
				return "", errors.New("没有另外配置可用的用户 API 模型")
			}
		}
		if profile.ProviderID == providerCodex {
			return "", errors.New("GUI API 回退暂不支持 Codex 订阅连接，请选择用户自定义 API 模型")
		}
		remote := !isLocalModelBaseURL(profile.BaseURL)
		if remote && !config.GuiAllowRemoteScreenshots {
			return "", errors.New("远程截图识别未授权")
		}
		if remote && !guiHasCrop(raw) && !config.GuiAllowRemoteFullScreen {
			return "", errors.New("远程完整屏幕上传未授权；请裁剪截图或单独开启完整屏幕权限")
		}
		apiKey := ""
		if profile.ProtectedAPIKey != "" {
			plain, err := unprotectSecret(profile.ProtectedAPIKey)
			if err != nil {
				return "", fmt.Errorf("无法解密 API Key：%w", err)
			}
			apiKey = string(plain)
		}
		if apiKey == "" && !isLocalModelBaseURL(profile.BaseURL) {
			return "", errors.New("当前 API 模型没有可用凭据")
		}
		return run(*profile, apiKey)
	}

	var source, result string
	switch normalizeGUIInferenceMode(config.GuiInferenceMode) {
	case guiInferenceLocalOnly:
		source, result, err = localAttempt()
	case guiInferenceUserAPI:
		source = "api"
		result, err = apiAttempt()
	default:
		source, result, err = localAttempt()
		if err != nil {
			localErr := err
			source = "api_fallback"
			result, err = apiAttempt()
			if err != nil {
				return "", fmt.Errorf("本地视觉失败：%v；API 回退失败：%w", localErr, err)
			}
		}
	}
	if err != nil {
		return "", err
	}
	result = adjustGUIVisionCoordinates(result, screenshot)
	response := guiScreenshotResponse("vision_query", screenshot, "截图在推理完成后已从工具作用域释放，未写入磁盘。")
	response.Source = source
	if source != "api" && source != "api_fallback" &&
		!activeDesktopProviderIsLocal(config) && !config.GuiAllowRemoteSemantic {
		response.ModelResult = sanitizeLocalGUIVisionResult(result)
	} else {
		response.ModelResult = result
	}
	return marshalToolResult(response), nil
}

func activeDesktopProviderIsLocal(config desktopConfig) bool {
	profile := findProviderProfile(config.ProviderProfiles, config.ActiveProviderProfileID)
	return profile != nil && (profile.ProviderID == providerBuiltinLocal ||
		(profile.ProviderID != providerCodex && isLocalModelBaseURL(profile.BaseURL)))
}

func guiScreenshotResponse(action string, screenshot guiScreenshot, message string) guiToolResponse {
	decoded, _ := base64.StdEncoding.DecodeString(screenshot.Base64)
	digest := sha256.Sum256(decoded)
	return guiToolResponse{
		Success: true, Target: "windows", Action: action, Source: "windows_screen_capture", Message: message,
		ImageWidth: screenshot.Width, ImageHeight: screenshot.Height, ImageSHA256: hex.EncodeToString(digest[:]),
	}
}

func guiApprovalDetail(raw map[string]json.RawMessage, fallback string) string {
	for _, key := range []string{"prompt", "application", "nodeId", "key"} {
		if value := rawJSONString(raw, key); value != "" {
			return truncateRunes(value, 2000)
		}
	}
	return fallback
}

func guiHasCrop(raw map[string]json.RawMessage) bool {
	for _, key := range []string{"cropLeft", "cropTop", "cropRight", "cropBottom"} {
		if !rawJSONHas(raw, key) {
			return false
		}
	}
	return true
}

func sanitizeLocalGUIVisionResult(result string) string {
	start, end := strings.Index(result, "{"), strings.LastIndex(result, "}")
	filtered := map[string]any{
		"privacyRedacted": true,
		"message":         "本地视觉结果中的文字已按隐私设置隐藏；只返回定位坐标和置信度。",
	}
	if start >= 0 && end > start {
		var parsed map[string]json.RawMessage
		if json.Unmarshal([]byte(result[start:end+1]), &parsed) == nil {
			for _, key := range []string{"targetFound", "x", "y", "confidence", "coordinates"} {
				if value, ok := parsed[key]; ok {
					filtered[key] = value
				}
			}
		}
	}
	data, _ := json.Marshal(filtered)
	return string(data)
}

func adjustGUIVisionCoordinates(result string, screenshot guiScreenshot) string {
	start, end := strings.Index(result, "{"), strings.LastIndex(result, "}")
	if start < 0 || end <= start {
		return result
	}
	var parsed map[string]json.RawMessage
	if json.Unmarshal([]byte(result[start:end+1]), &parsed) != nil {
		return result
	}
	for _, coordinate := range []struct {
		key    string
		offset int
	}{
		{key: "x", offset: screenshot.OriginX},
		{key: "y", offset: screenshot.OriginY},
	} {
		var value int
		if data, ok := parsed[coordinate.key]; ok && json.Unmarshal(data, &value) == nil {
			parsed[coordinate.key], _ = json.Marshal(value + coordinate.offset)
		}
	}
	parsed["coordinates"] = json.RawMessage(`"screen"`)
	data, err := json.Marshal(parsed)
	if err != nil {
		return result
	}
	return string(data)
}

func rawJSONString(raw map[string]json.RawMessage, key string) string {
	return strings.TrimSpace(rawJSONStringUntrimmed(raw, key))
}

func rawJSONStringUntrimmed(raw map[string]json.RawMessage, key string) string {
	var value string
	if data := raw[key]; len(data) > 0 {
		_ = json.Unmarshal(data, &value)
	}
	return value
}

func rawJSONInt(raw map[string]json.RawMessage, key string, fallback int) int {
	value := fallback
	if data := raw[key]; len(data) > 0 {
		_ = json.Unmarshal(data, &value)
	}
	return value
}

func rawJSONHas(raw map[string]json.RawMessage, key string) bool {
	data, ok := raw[key]
	return ok && len(data) > 0 && string(data) != "null"
}
