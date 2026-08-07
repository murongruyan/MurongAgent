package main

import (
	"context"
	"errors"
	"fmt"
	"strings"
)

const auxiliaryVisionPrompt = "你是独立的图片理解模块。请准确描述图片中与用户请求相关的文字、对象、界面状态、结构与可操作线索。不要臆测；只输出供另一个助手使用的简洁中文观察结果。"

func (app *DesktopAgentApp) analyzeImagesWithAuxiliaryVision(ctx context.Context, config desktopConfig, attachments []MessageImageAttachment, userContent string) (string, error) {
	profile, apiKey, err := auxiliaryVisionConnection(config)
	if err != nil {
		return "", err
	}
	images, err := app.store.modelImages(attachments)
	if err != nil {
		return "", err
	}
	if len(images) == 0 {
		return "", errors.New("图片附件为空")
	}
	question := strings.TrimSpace(userContent)
	if question == "" {
		question = "请分析这些图片，并提取关键内容。"
	} else {
		question = "结合用户请求分析这些图片：\n" + question
	}
	client := newModelClientWithGeneration(0.2, 2_000)
	result, err := app.streamConfiguredChat(ctx, client, profile, apiKey, []modelMessage{
		{Role: "system", Content: auxiliaryVisionPrompt},
		{Role: "user", Content: question, Images: images},
	}, nil, nil, nil)
	if err != nil {
		return "", fmt.Errorf("独立看图失败：%w", err)
	}
	analysis := strings.TrimSpace(result.Content)
	if analysis == "" {
		return "", errors.New("独立看图模型没有返回观察结果")
	}
	return truncateRunes(analysis, 20_000), nil
}

func auxiliaryVisionConnection(config desktopConfig) (ProviderProfile, string, error) {
	profileID := strings.TrimSpace(config.VisionProviderProfileID)
	if profileID == "" {
		profileID = config.ActiveProviderProfileID
	}
	profile := findProviderProfile(config.ProviderProfiles, profileID)
	if profile == nil {
		return ProviderProfile{}, "", errors.New("未选择可用的独立看图连接")
	}
	if profile.ProviderID == providerCodex {
		return ProviderProfile{}, "", errors.New("独立看图不能使用 ChatGPT 登录连接，请选择 API 或本地视觉模型")
	}
	resolved := *profile
	if model := strings.TrimSpace(config.VisionModel); model != "" {
		resolved.Model = model
	}
	if baseURL := strings.TrimSpace(config.VisionCustomBaseURL); baseURL != "" {
		resolved.BaseURL = baseURL
	}
	protected := resolved.ProtectedAPIKey
	if config.ProtectedVisionAPIKey != "" {
		protected = config.ProtectedVisionAPIKey
	}
	if protected == "" && resolved.ProviderID != providerBuiltinLocal && !isLocalModelBaseURL(resolved.BaseURL) {
		return ProviderProfile{}, "", errors.New("独立看图连接尚未配置 API Key")
	}
	apiKey := ""
	if protected != "" {
		value, err := unprotectSecret(protected)
		if err != nil {
			return ProviderProfile{}, "", fmt.Errorf("无法解密独立看图 API Key：%w", err)
		}
		apiKey = string(value)
	}
	return resolved, apiKey, nil
}
