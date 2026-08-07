package main

import (
	"crypto/sha256"
	"encoding/base64"
	"errors"
	"fmt"
	"net"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"time"
)

const (
	maxProviderImportLinkBytes   = 64 * 1024
	maxProviderImportScriptBytes = 32 * 1024
	maxProviderImportKeyBytes    = 8 * 1024
)

var (
	providerUsageURLPattern    = regexp.MustCompile(`(?is)\burl\s*:\s*["'](.+?)["']`)
	providerUsageMethodPattern = regexp.MustCompile(`(?is)\bmethod\s*:\s*["']([A-Za-z]+)["']`)
	providerUsageBearerPattern = regexp.MustCompile(`(?is)Authorization["']?\s*:\s*["']Bearer\s+\{\{(?:apiKey|usageApiKey)\}\}["']`)
)

type providerImportUsageRule struct {
	Endpoint     string `json:"endpoint"`
	SourceLabel  string `json:"sourceLabel"`
	IntervalMins int    `json:"intervalMinutes"`
}

type providerImportPayload struct {
	SourceScheme          string
	App                   string
	Name                  string
	Homepage              string
	Endpoints             []string
	APIKey                string
	Model                 string
	Notes                 string
	RequestedActive       bool
	UsageScript           string
	RequestedUsageEnabled bool
	UsageRule             *providerImportUsageRule
}

type ProviderImportPreview struct {
	RequestID             string                   `json:"requestId"`
	SourceScheme          string                   `json:"sourceScheme"`
	AppLabel              string                   `json:"appLabel"`
	Name                  string                   `json:"name"`
	Homepage              string                   `json:"homepage,omitempty"`
	Endpoints             []string                 `json:"endpoints"`
	MaskedAPIKey          string                   `json:"maskedApiKey"`
	Model                 string                   `json:"model,omitempty"`
	Notes                 string                   `json:"notes,omitempty"`
	RequestedActive       bool                     `json:"requestedActive"`
	UsageScript           string                   `json:"usageScript,omitempty"`
	RequestedUsageEnabled bool                     `json:"requestedUsageEnabled"`
	UsageRule             *providerImportUsageRule `json:"usageRule,omitempty"`
}

type ConfirmProviderImportRequest struct {
	RequestID   string `json:"requestId"`
	Activate    bool   `json:"activate"`
	EnableUsage bool   `json:"enableUsage"`
}

type pendingProviderImport struct {
	Payload   providerImportPayload
	CreatedAt time.Time
}

func parseProviderImportDeepLink(raw string) (providerImportPayload, error) {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return providerImportPayload{}, errors.New("导入链接为空")
	}
	if len(raw) > maxProviderImportLinkBytes {
		return providerImportPayload{}, errors.New("导入链接过长")
	}
	for _, char := range raw {
		if char < 0x20 || char == 0x7f {
			return providerImportPayload{}, errors.New("导入链接包含控制字符")
		}
	}
	parsed, err := url.Parse(raw)
	if err != nil {
		return providerImportPayload{}, fmt.Errorf("导入链接格式无效：%w", err)
	}
	scheme := strings.ToLower(parsed.Scheme)
	host := strings.ToLower(parsed.Hostname())
	validRoute := scheme == "ccswitch" && host == "v1" && parsed.EscapedPath() == "/import" ||
		scheme == "murongagent" && host == "provider" && parsed.EscapedPath() == "/import"
	if !validRoute || parsed.User != nil || parsed.Fragment != "" {
		return providerImportPayload{}, errors.New("不支持的导入协议或版本")
	}
	params, err := uniqueProviderImportQuery(parsed.Query())
	if err != nil {
		return providerImportPayload{}, err
	}
	if params["resource"] != "provider" {
		return providerImportPayload{}, errors.New("目前只支持导入供应商配置")
	}
	app, err := requiredProviderImportValue(params, "app", 32)
	if err != nil {
		return providerImportPayload{}, err
	}
	app = strings.ToLower(app)
	if !stringInSet(app, "claude", "codex", "gemini", "grokbuild", "opencode", "openclaw", "hermes") {
		return providerImportPayload{}, fmt.Errorf("不支持的应用类型：%s", app)
	}
	name, err := requiredProviderImportValue(params, "name", 100)
	if err != nil {
		return providerImportPayload{}, err
	}
	endpointValue, err := requiredProviderImportValue(params, "endpoint", 8192)
	if err != nil {
		return providerImportPayload{}, err
	}
	endpoints := make([]string, 0, 4)
	for _, item := range strings.Split(endpointValue, ",") {
		item = strings.TrimSpace(item)
		if item == "" {
			continue
		}
		validated, validateErr := validateProviderImportURL(item, "API 端点")
		if validateErr != nil {
			return providerImportPayload{}, validateErr
		}
		endpoints = append(endpoints, validated)
	}
	if len(endpoints) == 0 || len(endpoints) > 4 {
		return providerImportPayload{}, errors.New("API 端点数量无效")
	}
	apiKey, err := requiredProviderImportValue(params, "apiKey", maxProviderImportKeyBytes)
	if err != nil {
		return providerImportPayload{}, err
	}
	if strings.IndexFunc(apiKey, func(r rune) bool { return r == 0 || r == '\r' || r == '\n' }) >= 0 {
		return providerImportPayload{}, errors.New("API Key 包含非法字符")
	}
	homepage := strings.TrimSpace(params["homepage"])
	if homepage != "" {
		homepage, err = validateProviderImportURL(homepage, "官网地址")
		if err != nil {
			return providerImportPayload{}, err
		}
	}
	model, err := optionalProviderImportValue(params, "model", 200)
	if err != nil {
		return providerImportPayload{}, err
	}
	notes, err := optionalProviderImportValue(params, "notes", 2000)
	if err != nil {
		return providerImportPayload{}, err
	}
	usageScript, err := optionalProviderImportValue(params, "usageScript", maxProviderImportScriptBytes)
	if err != nil {
		return providerImportPayload{}, err
	}
	usageScript = decodeProviderUsageScript(usageScript)
	usageBaseURL, err := optionalProviderImportValue(params, "usageBaseUrl", 2048)
	if err != nil {
		return providerImportPayload{}, err
	}
	interval := 30
	if rawInterval := strings.TrimSpace(params["usageAutoInterval"]); rawInterval != "" {
		interval, err = strconv.Atoi(rawInterval)
		if err != nil || interval < 5 || interval > 10080 {
			return providerImportPayload{}, errors.New("自动查询间隔必须在 5 到 10080 分钟之间")
		}
	}
	var usageRule *providerImportUsageRule
	if usageScript != "" {
		usageRule = extractProviderUsageRule(usageScript, endpoints[0], usageBaseURL, interval)
	}
	return providerImportPayload{
		SourceScheme:          scheme,
		App:                   app,
		Name:                  name,
		Homepage:              homepage,
		Endpoints:             endpoints,
		APIKey:                apiKey,
		Model:                 model,
		Notes:                 notes,
		RequestedActive:       strings.EqualFold(params["enabled"], "true"),
		UsageScript:           usageScript,
		RequestedUsageEnabled: strings.EqualFold(params["usageEnabled"], "true"),
		UsageRule:             usageRule,
	}, nil
}

func uniqueProviderImportQuery(values url.Values) (map[string]string, error) {
	result := make(map[string]string, len(values))
	for key, list := range values {
		if strings.TrimSpace(key) == "" || len(key) > 64 || len(list) != 1 {
			return nil, fmt.Errorf("导入参数重复或无效：%s", key)
		}
		result[key] = list[0]
	}
	return result, nil
}

func requiredProviderImportValue(params map[string]string, key string, limit int) (string, error) {
	value, err := optionalProviderImportValue(params, key, limit)
	if err != nil {
		return "", err
	}
	if value == "" {
		return "", fmt.Errorf("导入链接缺少 %s", key)
	}
	return value, nil
}

func optionalProviderImportValue(params map[string]string, key string, limit int) (string, error) {
	value := strings.TrimSpace(params[key])
	if len(value) > limit {
		return "", fmt.Errorf("导入参数 %s 过长", key)
	}
	if strings.ContainsRune(value, 0) {
		return "", fmt.Errorf("导入参数 %s 包含空字符", key)
	}
	return value, nil
}

func validateProviderImportURL(raw, label string) (string, error) {
	if len(raw) > 2048 {
		return "", fmt.Errorf("%s 过长", label)
	}
	parsed, err := url.Parse(raw)
	if err != nil || parsed.Hostname() == "" || parsed.User != nil || parsed.RawQuery != "" || parsed.Fragment != "" {
		return "", fmt.Errorf("%s 必须是无账户信息、查询参数和片段的有效 URL", label)
	}
	loopback := strings.EqualFold(parsed.Hostname(), "localhost") || net.ParseIP(parsed.Hostname()) != nil && net.ParseIP(parsed.Hostname()).IsLoopback()
	if !strings.EqualFold(parsed.Scheme, "https") && !(loopback && strings.EqualFold(parsed.Scheme, "http")) {
		return "", fmt.Errorf("%s 必须使用 HTTPS（本机回环地址除外）", label)
	}
	return strings.TrimRight(raw, "/"), nil
}

func decodeProviderUsageScript(value string) string {
	if value == "" || strings.ContainsAny(value, "{}();?") {
		return value
	}
	decoders := []*base64.Encoding{base64.StdEncoding, base64.RawStdEncoding, base64.URLEncoding, base64.RawURLEncoding}
	for _, encoding := range decoders {
		decoded, err := encoding.DecodeString(value)
		if err == nil && len(decoded) <= maxProviderImportScriptBytes && strings.ContainsAny(string(decoded), "{}();") {
			return string(decoded)
		}
	}
	return value
}

func extractProviderUsageRule(script, providerBaseURL, usageBaseURL string, interval int) *providerImportUsageRule {
	if len(script) > maxProviderImportScriptBytes {
		return nil
	}
	method := "GET"
	if match := providerUsageMethodPattern.FindStringSubmatch(script); len(match) == 2 {
		method = strings.ToUpper(match[1])
	}
	if method != "GET" || !providerUsageBearerPattern.MatchString(script) {
		return nil
	}
	hasRemaining := false
	for _, marker := range []string{
		"response?.remaining", "response.remaining", "response?.quota?.remaining",
		"response.quota.remaining", "response?.balance", "response.balance",
	} {
		if strings.Contains(script, marker) {
			hasRemaining = true
			break
		}
	}
	if !hasRemaining || !strings.Contains(script, "unit") {
		return nil
	}
	match := providerUsageURLPattern.FindStringSubmatch(script)
	if len(match) != 2 {
		return nil
	}
	base := providerBaseURL
	if strings.TrimSpace(usageBaseURL) != "" {
		validated, err := validateProviderImportURL(usageBaseURL, "用量查询地址")
		if err != nil {
			return nil
		}
		base = validated
	}
	rawEndpoint := strings.TrimSpace(match[1])
	if len(rawEndpoint) > 2048 {
		return nil
	}
	endpoint := ""
	switch {
	case strings.HasPrefix(rawEndpoint, "{{baseUrl}}"):
		endpoint = combineProviderUsageURL(base, strings.TrimPrefix(rawEndpoint, "{{baseUrl}}"))
	case strings.HasPrefix(rawEndpoint, "{{usageBaseUrl}}"):
		endpoint = combineProviderUsageURL(base, strings.TrimPrefix(rawEndpoint, "{{usageBaseUrl}}"))
	default:
		endpoint, _ = validateProviderImportURL(rawEndpoint, "用量查询请求地址")
	}
	if endpoint == "" || !sameProviderImportOrigin(base, endpoint) {
		return nil
	}
	return &providerImportUsageRule{Endpoint: endpoint, SourceLabel: "受限 GET + Bearer 余额规则", IntervalMins: interval}
}

func combineProviderUsageURL(base, suffix string) string {
	if suffix == "" {
		return base
	}
	if !strings.HasPrefix(suffix, "/") || strings.Contains(suffix, "//") {
		return ""
	}
	return strings.TrimRight(base, "/") + suffix
}

func sameProviderImportOrigin(left, right string) bool {
	first, errFirst := url.Parse(left)
	second, errSecond := url.Parse(right)
	if errFirst != nil || errSecond != nil {
		return false
	}
	port := func(value *url.URL) string {
		if value.Port() != "" {
			return value.Port()
		}
		if strings.EqualFold(value.Scheme, "https") {
			return "443"
		}
		return "80"
	}
	return strings.EqualFold(first.Scheme, second.Scheme) && strings.EqualFold(first.Hostname(), second.Hostname()) && port(first) == port(second)
}

func providerImportPreview(requestID string, payload providerImportPayload) ProviderImportPreview {
	return ProviderImportPreview{
		RequestID: requestID, SourceScheme: payload.SourceScheme, AppLabel: providerImportAppLabel(payload.App),
		Name: payload.Name, Homepage: payload.Homepage, Endpoints: append([]string(nil), payload.Endpoints...),
		MaskedAPIKey: maskProviderImportSecret(payload.APIKey), Model: payload.Model, Notes: payload.Notes,
		RequestedActive: payload.RequestedActive, UsageScript: payload.UsageScript,
		RequestedUsageEnabled: payload.RequestedUsageEnabled, UsageRule: payload.UsageRule,
	}
}

func providerImportAppLabel(app string) string {
	switch app {
	case "codex":
		return "Codex / OpenAI-compatible"
	case "claude":
		return "Claude / Anthropic Messages"
	case "gemini":
		return "Google Gemini"
	default:
		return app + " / OpenAI-compatible"
	}
}

func providerIDForImportedApp(app string) string {
	switch app {
	case "claude":
		return providerClaude
	case "gemini":
		return providerGemini
	default:
		return providerOpenAI
	}
}

func importedProviderAPIMode(providerID string) string {
	switch providerID {
	case providerClaude:
		return "messages"
	case providerGemini:
		return "gemini"
	default:
		return "auto"
	}
}

func importedProviderProfileID(providerID, name, endpoint string) string {
	digest := sha256.Sum256([]byte(providerID + "\x00" + name + "\x00" + strings.ToLower(endpoint)))
	return fmt.Sprintf("provider_import_%x", digest[:10])
}

func maskProviderImportSecret(value string) string {
	characters := []rune(value)
	if len(characters) <= 8 {
		return strings.Repeat("*", max(4, len(characters)))
	}
	return string(characters[:4]) + strings.Repeat("*", 12) + string(characters[len(characters)-4:])
}

func stringInSet(value string, candidates ...string) bool {
	for _, candidate := range candidates {
		if value == candidate {
			return true
		}
	}
	return false
}
