package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
)

const providerModelCatalogMaxBytes = 2 << 20

func (app *DesktopAgentApp) FetchProviderModels(request ProviderModelCatalogRequest) (ProviderModelCatalogResult, error) {
	providerID := strings.TrimSpace(request.ProviderID)
	baseURL := strings.TrimRight(strings.TrimSpace(request.BaseURL), "/")
	if providerID == "" || baseURL == "" {
		return ProviderModelCatalogResult{}, errors.New("请先选择供应商并填写 Base URL")
	}
	apiKey := strings.TrimSpace(request.APIKey)
	if apiKey == "" && !request.ClearAPIKey {
		config := app.store.rawConfig()
		profile := findProviderProfile(config.ProviderProfiles, strings.TrimSpace(request.ProviderProfileID))
		if profile != nil && profile.ProtectedAPIKey != "" {
			plain, err := unprotectSecret(profile.ProtectedAPIKey)
			if err != nil {
				return ProviderModelCatalogResult{}, fmt.Errorf("无法解密 API Key：%w", err)
			}
			apiKey = strings.TrimSpace(string(plain))
		}
	}
	if apiKey == "" && !isLocalModelBaseURL(baseURL) {
		return ProviderModelCatalogResult{}, errors.New("请先填写当前连接的 API Key")
	}

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	client := &http.Client{Timeout: 30 * time.Second}
	var lastErr error
	for _, endpoint := range providerModelEndpointCandidates(providerID, baseURL) {
		models, err := fetchProviderModelEndpoint(ctx, client, endpoint, providerID, apiKey)
		if err != nil {
			lastErr = err
			continue
		}
		models = mergeProviderModels(request.CurrentModel, models)
		if len(models) > 500 {
			models = models[:500]
		}
		return ProviderModelCatalogResult{Models: models, SourceLabel: "上游接口", SyncedAt: time.Now().UnixMilli()}, nil
	}
	if lastErr == nil {
		lastErr = errors.New("没有可用的模型目录地址")
	}
	return ProviderModelCatalogResult{}, fmt.Errorf("读取上游模型失败：%w", lastErr)
}

func providerModelEndpointCandidates(providerID, baseURL string) []string {
	baseURL = strings.TrimRight(strings.TrimSpace(baseURL), "/")
	if baseURL == "" {
		return nil
	}
	values := []string{}
	switch providerID {
	case "claude":
		values = append(values, baseURL+"/v1/models", baseURL+"/models")
	case "gemini":
		values = append(values, baseURL+"/models")
		if !strings.HasSuffix(strings.ToLower(baseURL), "/v1beta") {
			values = append(values, baseURL+"/v1beta/models")
		}
	default:
		values = append(values, baseURL+"/models")
		if strings.HasSuffix(strings.ToLower(baseURL), "/v1") {
			values = append(values, baseURL[:len(baseURL)-3]+"/models")
		} else {
			values = append(values, baseURL+"/v1/models")
		}
	}
	return uniqueNonEmptyStrings(values)
}

func fetchProviderModelEndpoint(ctx context.Context, client *http.Client, endpoint, providerID, apiKey string) ([]string, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Accept", "application/json")
	switch providerID {
	case "claude":
		req.Header.Set("x-api-key", apiKey)
		req.Header.Set("anthropic-version", "2023-06-01")
	case "gemini":
		req.Header.Set("x-goog-api-key", apiKey)
	default:
		if apiKey != "" {
			req.Header.Set("Authorization", "Bearer "+apiKey)
		}
	}
	response, err := client.Do(req)
	if err != nil {
		return nil, err
	}
	defer response.Body.Close()
	body, err := io.ReadAll(io.LimitReader(response.Body, providerModelCatalogMaxBytes+1))
	if err != nil {
		return nil, err
	}
	if len(body) > providerModelCatalogMaxBytes {
		return nil, errors.New("模型列表响应超过 2 MiB")
	}
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		return nil, fmt.Errorf("HTTP %d", response.StatusCode)
	}
	models, err := parseProviderModelIDs(body)
	if err != nil {
		return nil, err
	}
	if providerID == "gemini" {
		for index := range models {
			models[index] = strings.TrimPrefix(models[index], "models/")
		}
	}
	if len(models) == 0 {
		return nil, errors.New("接口返回成功，但没有解析到模型列表")
	}
	return models, nil
}

func parseProviderModelIDs(body []byte) ([]string, error) {
	var root any
	if err := json.Unmarshal(body, &root); err != nil {
		return nil, errors.New("模型列表不是有效 JSON")
	}
	arrays := []any{}
	switch value := root.(type) {
	case []any:
		arrays = value
	case map[string]any:
		for _, key := range []string{"data", "models", "items"} {
			if list, ok := value[key].([]any); ok {
				arrays = append(arrays, list...)
			}
		}
		if len(arrays) == 0 {
			arrays = append(arrays, value)
		}
	default:
		arrays = append(arrays, value)
	}
	models := []string{}
	for _, item := range arrays {
		switch value := item.(type) {
		case string:
			models = append(models, value)
		case map[string]any:
			for _, key := range []string{"id", "name", "model"} {
				if model, ok := value[key].(string); ok {
					models = append(models, model)
					break
				}
			}
		}
	}
	return uniqueNonEmptyStrings(models), nil
}

func mergeProviderModels(current string, fetched []string) []string {
	return uniqueNonEmptyStrings(append([]string{current}, fetched...))
}

func uniqueNonEmptyStrings(values []string) []string {
	seen := map[string]bool{}
	result := make([]string, 0, len(values))
	for _, value := range values {
		value = strings.TrimSpace(value)
		if value == "" || seen[value] {
			continue
		}
		seen[value] = true
		result = append(result, value)
	}
	return result
}
