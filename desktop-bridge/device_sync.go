package desktopbridge

import (
	"bytes"
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"strings"
	"time"
)

const (
	deviceSyncPushPath         = "/api/v1/device-sync/push"
	deviceSyncPullPath         = "/api/v1/device-sync/pull"
	deviceSyncVersion          = "aes256-gcm-v1"
	deviceSyncWindowsToPhone   = "windows_to_android"
	deviceSyncPhoneToWindows   = "android_to_windows"
	deviceSyncHandoffToPhone   = "desktop_handoff_to_android"
	deviceSyncHandoffToDesktop = "desktop_handoff_to_desktop"
	deviceSyncMaxPlainBytes    = 32 << 20
	deviceSyncMaxEnvelopeBytes = 48 << 20
	deviceSyncSessionPageBytes = 6 << 20
	deviceSyncMaxPages         = 10_000
	deviceSyncClockWindow      = 2 * time.Minute
)

type DeviceSyncOptions struct {
	IncludeSessions            bool `json:"includeSessions"`
	IncludeProviderCredentials bool `json:"includeProviderCredentials"`
	IncludeCodexLogin          bool `json:"includeCodexLogin"`
	IncludeGitHubCredentials   bool `json:"includeGitHubCredentials"`
	IncludeAgentSettings       bool `json:"includeAgentSettings"`
	IncludeKnowledge           bool `json:"includeKnowledge"`
	IncludeMCP                 bool `json:"includeMcp"`
	IncludeMCPCredentials      bool `json:"includeMcpCredentials"`
	IncludeSavedWorkflows      bool `json:"includeSavedWorkflows"`
	SessionCursor              int  `json:"sessionCursor,omitempty"`
}

type SyncedSession struct {
	SourceSessionID string          `json:"sourceSessionId"`
	OriginPlatform  string          `json:"originPlatform,omitempty"`
	OriginSessionID string          `json:"originSessionId,omitempty"`
	Document        json.RawMessage `json:"document"`
}

type SyncedGitHubCredential struct {
	APIBaseURL  string  `json:"apiBaseUrl"`
	Token       *string `json:"token,omitempty"`
	ViewerLogin string  `json:"viewerLogin,omitempty"`
}

type SyncedGitHubAccount struct {
	ID         string  `json:"id"`
	Label      string  `json:"label"`
	Login      string  `json:"login,omitempty"`
	Name       string  `json:"name,omitempty"`
	AvatarURL  string  `json:"avatarUrl,omitempty"`
	APIBaseURL string  `json:"apiBaseUrl"`
	Token      *string `json:"token,omitempty"`
	LastUsedAt int64   `json:"lastUsedAt,omitempty"`
}

type SyncedCodexAccountSettings struct {
	AutoSwitch      bool    `json:"autoSwitch"`
	ReservePercent  float64 `json:"reservePercent"`
	CooldownMinutes int     `json:"cooldownMinutes"`
}

type SyncedCodexAccount struct {
	ID         string  `json:"id"`
	Label      string  `json:"label"`
	Email      string  `json:"email,omitempty"`
	PlanType   string  `json:"planType,omitempty"`
	Enabled    bool    `json:"enabled"`
	AuthJSON   *string `json:"authJson,omitempty"`
	LastUsedAt int64   `json:"lastUsedAt,omitempty"`
}

type SyncedProviderCredential struct {
	ProfileID           string  `json:"profileId"`
	ProviderID          string  `json:"providerId"`
	Name                string  `json:"name"`
	BaseURL             string  `json:"baseUrl"`
	Model               string  `json:"model"`
	ReasoningEffort     string  `json:"reasoningEffort,omitempty"`
	APIMode             string  `json:"apiMode,omitempty"`
	ContextWindowTokens *int    `json:"contextWindowTokens,omitempty"`
	APIKey              *string `json:"apiKey,omitempty"`
}

type SyncedAgentSettings struct {
	ApprovalMode             string   `json:"approvalMode"`
	SystemPrompt             string   `json:"systemPrompt"`
	ResponseVerbosity        string   `json:"responseVerbosity"`
	Temperature              *float64 `json:"temperature,omitempty"`
	MaxTokens                *int     `json:"maxTokens,omitempty"`
	EnableMultimodalMessages *bool    `json:"enableMultimodalMessages,omitempty"`
	PlannerProfileEnabled    *bool    `json:"plannerProfileEnabled,omitempty"`
	PlannerModel             *string  `json:"plannerModel,omitempty"`
	PlannerReasoningEffort   *string  `json:"plannerReasoningEffort,omitempty"`
	SubagentProfileEnabled   *bool    `json:"subagentDefaultProfileEnabled,omitempty"`
	SubagentModel            *string  `json:"subagentDefaultModel,omitempty"`
	SubagentReasoningEffort  *string  `json:"subagentDefaultReasoningEffort,omitempty"`
}

// SyncedMediaSettings deliberately carries routing only. Secret overrides are
// transferred separately, and only through an explicitly credential-inclusive
// device-sync operation.
type SyncedMediaSettings struct {
	VisionRoutingEnabled         bool   `json:"visionRoutingEnabled"`
	VisionProviderID             string `json:"visionProviderId,omitempty"`
	VisionProfileID              string `json:"visionProfileId,omitempty"`
	VisionModel                  string `json:"visionModel,omitempty"`
	VisionCustomBaseURL          string `json:"visionCustomBaseUrl,omitempty"`
	ImageGenerationProviderID    string `json:"imageGenerationProviderId,omitempty"`
	ImageGenerationProfileID     string `json:"imageGenerationProfileId,omitempty"`
	ImageGenerationModel         string `json:"imageGenerationModel,omitempty"`
	ImageGenerationCustomBaseURL string `json:"imageGenerationCustomBaseUrl,omitempty"`
	ImageGenerationSize          string `json:"imageGenerationSize,omitempty"`
	ImageGenerationQuality       string `json:"imageGenerationQuality,omitempty"`
	ImageGenerationFormat        string `json:"imageGenerationFormat,omitempty"`
	ImageGenerationCompression   int    `json:"imageGenerationCompression,omitempty"`
	ImageGenerationPartialImages int    `json:"imageGenerationPartialImages,omitempty"`
	ImageUpscaleBaseURL          string `json:"imageUpscaleBaseUrl,omitempty"`
	ImageUpscaleModel            string `json:"imageUpscaleModel,omitempty"`
	ImageUpscaleScale            int    `json:"imageUpscaleScale,omitempty"`
}

// SyncedMediaCredentials are always inside the existing authenticated and
// encrypted device-sync envelope. They are absent from portable backups.
type SyncedMediaCredentials struct {
	VisionCustomAPIKey          *string `json:"visionCustomApiKey,omitempty"`
	ImageGenerationCustomAPIKey *string `json:"imageGenerationCustomApiKey,omitempty"`
	ImageUpscaleAPIKey          *string `json:"imageUpscaleApiKey,omitempty"`
}

type SyncedRule struct {
	ID      string `json:"id"`
	Title   string `json:"title"`
	Content string `json:"content"`
	Enabled bool   `json:"enabled"`
}

type SyncedMemory struct {
	ID      string `json:"id"`
	Title   string `json:"title"`
	Content string `json:"content"`
	Enabled bool   `json:"enabled"`
}

type SyncedSkill struct {
	ID             string   `json:"id"`
	Title          string   `json:"title"`
	Description    string   `json:"description"`
	Content        string   `json:"content"`
	RunAs          string   `json:"runAs"`
	AllowedTools   []string `json:"allowedTools"`
	PreferredModel string   `json:"preferredModel"`
	Enabled        bool     `json:"enabled"`
}

type SyncedKnowledge struct {
	Rules    []SyncedRule   `json:"rules"`
	Memories []SyncedMemory `json:"memories"`
	Skills   []SyncedSkill  `json:"skills"`
}

type SyncedMCPServer struct {
	ID                    string            `json:"id"`
	Name                  string            `json:"name"`
	Transport             string            `json:"transport"`
	Command               string            `json:"command,omitempty"`
	Args                  []string          `json:"args"`
	URL                   string            `json:"url,omitempty"`
	RequestTimeoutSeconds int               `json:"requestTimeoutSeconds"`
	TrustedReadOnlyTools  []string          `json:"trustedReadOnlyTools"`
	Enabled               bool              `json:"enabled"`
	AutoStart             bool              `json:"autoStart"`
	Environment           map[string]string `json:"environment,omitempty"`
	Headers               map[string]string `json:"headers,omitempty"`
}

type SyncedWorkflowNode struct {
	ID                 string   `json:"id"`
	Label              string   `json:"label"`
	DependsOn          []string `json:"dependsOn"`
	RequiredPermission string   `json:"requiredPermission"`
	TimeoutSeconds     int      `json:"timeoutSeconds"`
	MaxRetries         int      `json:"maxRetries"`
}

type SyncedSavedWorkflow struct {
	ID               string               `json:"id"`
	Name             string               `json:"name"`
	Template         string               `json:"template"`
	GitHubRepository string               `json:"githubRepository,omitempty"`
	Nodes            []SyncedWorkflowNode `json:"nodes"`
	IntervalMinutes  int                  `json:"intervalMinutes"`
	CreatedAt        int64                `json:"createdAt"`
	UpdatedAt        int64                `json:"updatedAt"`
}

type CredentialSyncBundle struct {
	SchemaVersion          int                         `json:"schemaVersion"`
	SourcePlatform         string                      `json:"sourcePlatform"`
	GeneratedAt            int64                       `json:"generatedAt"`
	ActiveProviderID       *string                     `json:"activeProviderId,omitempty"`
	ActiveProfileID        *string                     `json:"activeProfileId,omitempty"`
	Providers              []SyncedProviderCredential  `json:"providers"`
	CodexAuthJSON          *string                     `json:"codexAuthJson,omitempty"`
	CodexAccounts          []SyncedCodexAccount        `json:"codexAccounts,omitempty"`
	ActiveCodexAccountID   string                      `json:"activeCodexAccountId,omitempty"`
	CodexAccountSettings   *SyncedCodexAccountSettings `json:"codexAccountSettings,omitempty"`
	GitHub                 *SyncedGitHubCredential     `json:"github,omitempty"`
	GitHubAccounts         []SyncedGitHubAccount       `json:"githubAccounts,omitempty"`
	ActiveGitHubAccountID  string                      `json:"activeGitHubAccountId,omitempty"`
	AgentSettings          *SyncedAgentSettings        `json:"agentSettings,omitempty"`
	MediaSettings          *SyncedMediaSettings        `json:"mediaSettings,omitempty"`
	MediaCredentials       *SyncedMediaCredentials     `json:"mediaCredentials,omitempty"`
	Knowledge              *SyncedKnowledge            `json:"knowledge,omitempty"`
	MCPServers             []SyncedMCPServer           `json:"mcpServers,omitempty"`
	MCPCredentialsIncluded bool                        `json:"mcpCredentialsIncluded"`
	SavedWorkflows         []SyncedSavedWorkflow       `json:"savedWorkflows,omitempty"`
	Sessions               []SyncedSession             `json:"sessions,omitempty"`
	SessionNextCursor      *int                        `json:"sessionNextCursor,omitempty"`
}

type CredentialSyncResult struct {
	ImportedSessions       int     `json:"importedSessions"`
	ConflictSessions       int     `json:"conflictSessions"`
	SkippedSessions        int     `json:"skippedSessions"`
	ImportedProviders      int     `json:"importedProviders"`
	ImportedAPIKeys        int     `json:"importedApiKeys"`
	ImportedCodexLogin     bool    `json:"importedCodexLogin"`
	ImportedCodexAccounts  int     `json:"importedCodexAccounts"`
	ImportedGitHubToken    bool    `json:"importedGitHubToken"`
	ImportedGitHubAccounts int     `json:"importedGitHubAccounts"`
	AccountEmail           *string `json:"accountEmail,omitempty"`
	ImportedSettings       bool    `json:"importedSettings"`
	ImportedRules          int     `json:"importedRules"`
	ImportedMemories       int     `json:"importedMemories"`
	ImportedSkills         int     `json:"importedSkills"`
	ImportedMCPServers     int     `json:"importedMcpServers"`
	ImportedWorkflows      int     `json:"importedWorkflows"`
	DisabledMCPServers     int     `json:"disabledMcpServers"`
	SkippedWorkflows       int     `json:"skippedWorkflows"`
}

type deviceSyncEnvelope struct {
	Version    string `json:"version"`
	RequestID  string `json:"requestId"`
	IssuedAt   int64  `json:"issuedAt"`
	Direction  string `json:"direction"`
	Nonce      string `json:"nonce"`
	Ciphertext string `json:"ciphertext"`
}

func (node *computerNode) pushCredentials(ctx context.Context, bundle CredentialSyncBundle) (CredentialSyncResult, error) {
	pages, err := splitCredentialSyncBundle(bundle)
	if err != nil {
		return CredentialSyncResult{}, err
	}
	var combined CredentialSyncResult
	for index := range pages {
		result, pushErr := node.pushCredentialPage(ctx, pages[index])
		if pushErr != nil {
			return CredentialSyncResult{}, fmt.Errorf("发送聊天记录分片 %d/%d 失败：%w", index+1, len(pages), pushErr)
		}
		mergeCredentialSyncResult(&combined, result)
	}
	return combined, nil
}

func (node *computerNode) pushCredentialPage(ctx context.Context, bundle CredentialSyncBundle) (CredentialSyncResult, error) {
	plain, err := json.Marshal(bundle)
	if err != nil {
		return CredentialSyncResult{}, err
	}
	defer clearBytes(plain)
	responsePlain, err := node.exchangeCredentials(ctx, deviceSyncPushPath, plain)
	if err != nil {
		return CredentialSyncResult{}, err
	}
	defer clearBytes(responsePlain)
	var result CredentialSyncResult
	if err := strictUnmarshalDeviceSync(responsePlain, &result); err != nil {
		return CredentialSyncResult{}, fmt.Errorf("手机凭据同步结果无效：%w", err)
	}
	return result, nil
}

func (node *computerNode) pullCredentials(ctx context.Context, options DeviceSyncOptions) (CredentialSyncBundle, error) {
	options.SessionCursor = 0
	first, err := node.pullCredentialPage(ctx, options)
	if err != nil {
		return CredentialSyncBundle{}, err
	}
	cursor := first.SessionNextCursor
	combined := first
	combined.SessionNextCursor = nil
	seen := make(map[string]struct{}, len(first.Sessions))
	for _, session := range first.Sessions {
		seen[session.SourceSessionID] = struct{}{}
	}
	for page := 1; cursor != nil; page++ {
		if page >= deviceSyncMaxPages || *cursor <= options.SessionCursor {
			return CredentialSyncBundle{}, errors.New("手机返回了无效的聊天记录分页游标")
		}
		options = DeviceSyncOptions{IncludeSessions: true, SessionCursor: *cursor}
		next, pageErr := node.pullCredentialPage(ctx, options)
		if pageErr != nil {
			return CredentialSyncBundle{}, fmt.Errorf("接收聊天记录分片 %d 失败：%w", page+1, pageErr)
		}
		if next.SchemaVersion != combined.SchemaVersion || next.SourcePlatform != combined.SourcePlatform {
			return CredentialSyncBundle{}, errors.New("手机聊天记录分页来源不一致")
		}
		for _, session := range next.Sessions {
			if _, duplicate := seen[session.SourceSessionID]; duplicate {
				return CredentialSyncBundle{}, errors.New("手机聊天记录分页包含重复会话")
			}
			seen[session.SourceSessionID] = struct{}{}
			combined.Sessions = append(combined.Sessions, session)
		}
		options.SessionCursor = *cursor
		cursor = next.SessionNextCursor
	}
	return combined, nil
}

func (node *computerNode) pullCredentialPage(ctx context.Context, options DeviceSyncOptions) (CredentialSyncBundle, error) {
	plain, err := json.Marshal(options)
	if err != nil {
		return CredentialSyncBundle{}, err
	}
	responsePlain, err := node.exchangeCredentials(ctx, deviceSyncPullPath, plain)
	clearBytes(plain)
	if err != nil {
		return CredentialSyncBundle{}, err
	}
	defer clearBytes(responsePlain)
	var bundle CredentialSyncBundle
	if err := strictUnmarshalDeviceSync(responsePlain, &bundle); err != nil {
		return CredentialSyncBundle{}, fmt.Errorf("手机凭据同步包无效：%w", err)
	}
	return bundle, nil
}

func splitCredentialSyncBundle(bundle CredentialSyncBundle) ([]CredentialSyncBundle, error) {
	base := bundle
	base.Sessions = nil
	base.SessionNextCursor = nil
	if len(bundle.Sessions) == 0 {
		plain, err := json.Marshal(base)
		if err != nil {
			return nil, err
		}
		if len(plain) > deviceSyncMaxPlainBytes {
			return nil, errors.New("设备同步设置分片超过安全传输上限")
		}
		return []CredentialSyncBundle{base}, nil
	}

	continuation := CredentialSyncBundle{
		SchemaVersion:  bundle.SchemaVersion,
		SourcePlatform: bundle.SourcePlatform,
		GeneratedAt:    bundle.GeneratedAt,
		Providers:      []SyncedProviderCredential{},
	}
	pages := make([]CredentialSyncBundle, 0, 1)
	current := base
	for _, session := range bundle.Sessions {
		candidate := current
		candidate.Sessions = append(append([]SyncedSession(nil), current.Sessions...), session)
		plain, err := json.Marshal(candidate)
		if err != nil {
			return nil, err
		}
		if len(plain) > deviceSyncSessionPageBytes && len(current.Sessions) > 0 {
			pages = append(pages, current)
			current = continuation
			candidate = current
			candidate.Sessions = []SyncedSession{session}
			plain, err = json.Marshal(candidate)
			if err != nil {
				return nil, err
			}
		}
		if len(plain) > deviceSyncMaxPlainBytes {
			return nil, fmt.Errorf("聊天记录 %q 单条超过安全传输上限", session.SourceSessionID)
		}
		current = candidate
	}
	if len(current.Sessions) > 0 || len(pages) == 0 {
		pages = append(pages, current)
	}
	return pages, nil
}

func mergeCredentialSyncResult(target *CredentialSyncResult, page CredentialSyncResult) {
	target.ImportedSessions += page.ImportedSessions
	target.ConflictSessions += page.ConflictSessions
	target.SkippedSessions += page.SkippedSessions
	target.ImportedProviders += page.ImportedProviders
	target.ImportedAPIKeys += page.ImportedAPIKeys
	target.ImportedCodexLogin = target.ImportedCodexLogin || page.ImportedCodexLogin
	target.ImportedCodexAccounts += page.ImportedCodexAccounts
	target.ImportedGitHubToken = target.ImportedGitHubToken || page.ImportedGitHubToken
	target.ImportedGitHubAccounts += page.ImportedGitHubAccounts
	if target.AccountEmail == nil && page.AccountEmail != nil {
		target.AccountEmail = page.AccountEmail
	}
	target.ImportedSettings = target.ImportedSettings || page.ImportedSettings
	target.ImportedRules += page.ImportedRules
	target.ImportedMemories += page.ImportedMemories
	target.ImportedSkills += page.ImportedSkills
	target.ImportedMCPServers += page.ImportedMCPServers
	target.ImportedWorkflows += page.ImportedWorkflows
	target.DisabledMCPServers += page.DisabledMCPServers
	target.SkippedWorkflows += page.SkippedWorkflows
}

func (node *computerNode) exchangeCredentials(ctx context.Context, path string, plain []byte) ([]byte, error) {
	if len(plain) == 0 || len(plain) > deviceSyncMaxPlainBytes {
		return nil, errors.New("凭据同步内容大小无效")
	}
	if strings.TrimSpace(node.config.ProtectedSyncKey) == "" {
		return nil, errors.New("当前配对没有安全同步密钥，请清除配对并使用新版 Murong 重新配对")
	}
	key, err := unprotectSecret(node.config.ProtectedSyncKey)
	if err != nil || len(key) != 32 {
		clearBytes(key)
		return nil, errors.New("无法解密设备同步密钥，请重新配对")
	}
	defer clearBytes(key)
	requestID := mustRandomID("device-sync")
	envelope, err := encryptDeviceSync(key, requestID, time.Now().UnixMilli(), deviceSyncWindowsToPhone, plain)
	if err != nil {
		return nil, err
	}
	var response deviceSyncEnvelope
	if err := node.api.postJSONWithTimeoutAndLimit(
		ctx,
		path,
		envelope,
		&response,
		120*time.Second,
		deviceSyncMaxEnvelopeBytes,
	); err != nil {
		return nil, err
	}
	if response.RequestID != requestID || response.Direction != deviceSyncPhoneToWindows {
		return nil, errors.New("手机返回了不属于本次请求的凭据同步响应")
	}
	now := time.Now().UnixMilli()
	if response.IssuedAt < now-deviceSyncClockWindow.Milliseconds() || response.IssuedAt > now+30_000 {
		return nil, errors.New("手机凭据同步响应已过期")
	}
	return decryptDeviceSync(key, response)
}

func encryptDeviceSync(key []byte, requestID string, issuedAt int64, direction string, plain []byte) (deviceSyncEnvelope, error) {
	if len(key) != 32 || len(plain) == 0 || len(plain) > deviceSyncMaxPlainBytes {
		return deviceSyncEnvelope{}, errors.New("设备同步加密参数无效")
	}
	if direction != deviceSyncWindowsToPhone && direction != deviceSyncPhoneToWindows &&
		direction != deviceSyncHandoffToPhone && direction != deviceSyncHandoffToDesktop {
		return deviceSyncEnvelope{}, errors.New("设备同步方向无效")
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return deviceSyncEnvelope{}, err
	}
	aead, err := cipher.NewGCM(block)
	if err != nil {
		return deviceSyncEnvelope{}, err
	}
	nonce := make([]byte, aead.NonceSize())
	if _, err := io.ReadFull(rand.Reader, nonce); err != nil {
		return deviceSyncEnvelope{}, err
	}
	ciphertext := aead.Seal(nil, nonce, plain, deviceSyncAAD(requestID, issuedAt, direction))
	return deviceSyncEnvelope{
		Version: deviceSyncVersion, RequestID: requestID, IssuedAt: issuedAt, Direction: direction,
		Nonce: base64.RawURLEncoding.EncodeToString(nonce), Ciphertext: base64.RawURLEncoding.EncodeToString(ciphertext),
	}, nil
}

func decryptDeviceSync(key []byte, envelope deviceSyncEnvelope) ([]byte, error) {
	if len(key) != 32 || envelope.Version != deviceSyncVersion {
		return nil, errors.New("设备同步协议版本或密钥无效")
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	aead, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}
	nonce, err := decodeDeviceSyncBase64(envelope.Nonce, aead.NonceSize(), aead.NonceSize())
	if err != nil {
		return nil, err
	}
	ciphertext, err := decodeDeviceSyncBase64(envelope.Ciphertext, aead.Overhead(), deviceSyncMaxPlainBytes+aead.Overhead())
	if err != nil {
		return nil, err
	}
	plain, err := aead.Open(nil, nonce, ciphertext, deviceSyncAAD(envelope.RequestID, envelope.IssuedAt, envelope.Direction))
	if err != nil {
		return nil, errors.New("设备同步密文校验失败")
	}
	return plain, nil
}

func deviceSyncAAD(requestID string, issuedAt int64, direction string) []byte {
	return []byte(fmt.Sprintf("%s\n%s\n%d\n%s", deviceSyncVersion, requestID, issuedAt, direction))
}

func decodeDeviceSyncBase64(value string, minimum, maximum int) ([]byte, error) {
	if value == "" || len(value) > maximum*2 {
		return nil, errors.New("设备同步密文编码长度无效")
	}
	decoded, err := base64.RawURLEncoding.DecodeString(value)
	if err != nil || len(decoded) < minimum || len(decoded) > maximum {
		return nil, errors.New("设备同步密文编码无效")
	}
	return decoded, nil
}

func strictUnmarshalDeviceSync(data []byte, output any) error {
	decoder := json.NewDecoder(bytes.NewReader(data))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(output); err != nil {
		return err
	}
	if decoder.Decode(&struct{}{}) != io.EOF {
		return errors.New("设备同步 JSON 包含尾随内容")
	}
	return nil
}
