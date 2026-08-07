package main

import (
	"context"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"time"

	desktopbridge "github.com/murong-agent/desktop-bridge"
)

type SyncCredentialsRequest struct {
	IncludeSessions            bool `json:"includeSessions"`
	IncludeProviderCredentials bool `json:"includeProviderCredentials"`
	IncludeCodexLogin          bool `json:"includeCodexLogin"`
	IncludeGitHubCredentials   bool `json:"includeGitHubCredentials"`
	IncludeAgentSettings       bool `json:"includeAgentSettings"`
	IncludeKnowledge           bool `json:"includeKnowledge"`
	IncludeMCP                 bool `json:"includeMcp"`
	IncludeMCPCredentials      bool `json:"includeMcpCredentials"`
	IncludeSavedWorkflows      bool `json:"includeSavedWorkflows"`
}

type SyncCredentialsOperationResult struct {
	Direction              string              `json:"direction"`
	ImportedSessions       int                 `json:"importedSessions"`
	ConflictSessions       int                 `json:"conflictSessions"`
	SkippedSessions        int                 `json:"skippedSessions"`
	ImportedProviders      int                 `json:"importedProviders"`
	ImportedAPIKeys        int                 `json:"importedApiKeys"`
	ImportedCodexLogin     bool                `json:"importedCodexLogin"`
	ImportedCodexAccounts  int                 `json:"importedCodexAccounts"`
	ImportedGitHubToken    bool                `json:"importedGitHubToken"`
	ImportedGitHubAccounts int                 `json:"importedGitHubAccounts"`
	AccountEmail           string              `json:"accountEmail,omitempty"`
	ImportedSettings       bool                `json:"importedSettings"`
	ImportedRules          int                 `json:"importedRules"`
	ImportedMemories       int                 `json:"importedMemories"`
	ImportedSkills         int                 `json:"importedSkills"`
	ImportedMCPServers     int                 `json:"importedMcpServers"`
	ImportedWorkflows      int                 `json:"importedWorkflows"`
	DisabledMCPServers     int                 `json:"disabledMcpServers"`
	SkippedWorkflows       int                 `json:"skippedWorkflows"`
	Config                 PublicDesktopConfig `json:"config"`
	Codex                  CodexRuntimeStatus  `json:"codex"`
}

func (app *DesktopAgentApp) PushCredentialsToPhone(request SyncCredentialsRequest) (SyncCredentialsOperationResult, error) {
	if err := app.validateCredentialSyncRequest(request); err != nil {
		return SyncCredentialsOperationResult{}, err
	}
	bundle, err := app.exportCredentialBundle(request)
	if err != nil {
		return SyncCredentialsOperationResult{}, err
	}
	defer clearCredentialBundle(&bundle)
	// Each encrypted page has its own network timeout in desktop-bridge. Do not put
	// one fixed deadline around the complete history or large archives would fail
	// merely because they contain more pages.
	ctx := context.Background()
	result, err := app.remote.PushCredentials(ctx, bundle)
	if err != nil {
		return SyncCredentialsOperationResult{}, err
	}
	return SyncCredentialsOperationResult{
		Direction: "to_phone", ImportedSessions: result.ImportedSessions,
		ConflictSessions: result.ConflictSessions, SkippedSessions: result.SkippedSessions,
		ImportedProviders: result.ImportedProviders,
		ImportedAPIKeys:   result.ImportedAPIKeys, ImportedCodexLogin: result.ImportedCodexLogin,
		ImportedCodexAccounts:  result.ImportedCodexAccounts,
		ImportedGitHubToken:    result.ImportedGitHubToken,
		ImportedGitHubAccounts: result.ImportedGitHubAccounts,
		AccountEmail:           pointerString(result.AccountEmail), ImportedSettings: result.ImportedSettings,
		ImportedRules: result.ImportedRules, ImportedMemories: result.ImportedMemories, ImportedSkills: result.ImportedSkills,
		ImportedMCPServers: result.ImportedMCPServers, ImportedWorkflows: result.ImportedWorkflows,
		DisabledMCPServers: result.DisabledMCPServers, SkippedWorkflows: result.SkippedWorkflows,
		Config: app.store.publicConfig(), Codex: app.codex.Status(),
	}, nil
}

func (app *DesktopAgentApp) PullCredentialsFromPhone(request SyncCredentialsRequest) (SyncCredentialsOperationResult, error) {
	if err := app.validateCredentialSyncRequest(request); err != nil {
		return SyncCredentialsOperationResult{}, err
	}
	ctx := context.Background()
	bundle, err := app.remote.PullCredentials(ctx, desktopbridge.DeviceSyncOptions{
		IncludeSessions:            request.IncludeSessions,
		IncludeProviderCredentials: request.IncludeProviderCredentials,
		IncludeCodexLogin:          request.IncludeCodexLogin,
		IncludeGitHubCredentials:   request.IncludeGitHubCredentials,
		IncludeAgentSettings:       request.IncludeAgentSettings,
		IncludeKnowledge:           request.IncludeKnowledge,
		IncludeMCP:                 request.IncludeMCP,
		IncludeMCPCredentials:      request.IncludeMCPCredentials,
		IncludeSavedWorkflows:      request.IncludeSavedWorkflows,
	})
	if err != nil {
		return SyncCredentialsOperationResult{}, err
	}
	defer clearCredentialBundle(&bundle)
	result, err := app.importCredentialBundle(ctx, bundle)
	if err != nil {
		return SyncCredentialsOperationResult{}, err
	}
	config := app.store.publicConfig()
	app.emit("settings:changed", config)
	if result.ImportedSessions > 0 {
		app.emitSessionsChanged(nil)
	}
	return SyncCredentialsOperationResult{
		Direction: "from_phone", ImportedSessions: result.ImportedSessions,
		ConflictSessions: result.ConflictSessions, SkippedSessions: result.SkippedSessions,
		ImportedProviders: result.ImportedProviders,
		ImportedAPIKeys:   result.ImportedAPIKeys, ImportedCodexLogin: result.ImportedCodexLogin,
		ImportedCodexAccounts:  result.ImportedCodexAccounts,
		ImportedGitHubToken:    result.ImportedGitHubToken,
		ImportedGitHubAccounts: result.ImportedGitHubAccounts,
		AccountEmail:           pointerString(result.AccountEmail), ImportedSettings: result.ImportedSettings,
		ImportedRules: result.ImportedRules, ImportedMemories: result.ImportedMemories, ImportedSkills: result.ImportedSkills,
		ImportedMCPServers: result.ImportedMCPServers, ImportedWorkflows: result.ImportedWorkflows,
		DisabledMCPServers: result.DisabledMCPServers, SkippedWorkflows: result.SkippedWorkflows,
		Config: config, Codex: app.codex.Status(),
	}, nil
}

func (app *DesktopAgentApp) validateCredentialSyncRequest(request SyncCredentialsRequest) error {
	if !request.IncludeSessions && !request.IncludeProviderCredentials && !request.IncludeCodexLogin && !request.IncludeGitHubCredentials && !request.IncludeAgentSettings &&
		!request.IncludeKnowledge && !request.IncludeMCP && !request.IncludeSavedWorkflows {
		return errors.New("至少选择一种设备同步内容")
	}
	if request.IncludeMCPCredentials && !request.IncludeMCP {
		return errors.New("同步 MCP 凭据前必须同时选择 MCP 配置")
	}
	app.mu.Lock()
	running := len(app.runs) > 0
	app.mu.Unlock()
	if running {
		return errors.New("请等待当前 Agent 任务完成或先停止任务，再同步账号凭据")
	}
	if app.remote == nil || app.codex == nil || app.store == nil || app.workflows == nil {
		return errors.New("凭据同步服务尚未初始化")
	}
	return nil
}

func (app *DesktopAgentApp) exportCredentialBundle(request SyncCredentialsRequest) (desktopbridge.CredentialSyncBundle, error) {
	config := app.store.rawConfig()
	bundle := desktopbridge.CredentialSyncBundle{
		SchemaVersion: 8, SourcePlatform: desktopSourcePlatform(), GeneratedAt: time.Now().UnixMilli(),
		Providers: []desktopbridge.SyncedProviderCredential{},
	}
	if request.IncludeSessions {
		var err error
		bundle.Sessions, err = app.store.exportSyncedSessions()
		if err != nil {
			return bundle, err
		}
	}
	active := findProviderProfile(config.ProviderProfiles, config.ActiveProviderProfileID)
	if active != nil {
		if active.ProviderID == providerBuiltinLocal {
			active = nil
		}
	}
	if active != nil {
		providerID := active.ProviderID
		if providerID == providerCodex {
			providerID = "codex"
		}
		bundle.ActiveProviderID = stringPointer(providerID)
		bundle.ActiveProfileID = stringPointer(active.ID)
	}
	if request.IncludeProviderCredentials {
		for _, profile := range config.ProviderProfiles {
			if profile.ProviderID == providerCodex || profile.ProviderID == providerBuiltinLocal {
				continue
			}
			credential := desktopbridge.SyncedProviderCredential{
				ProfileID: profile.ID, ProviderID: profile.ProviderID, Name: profile.Name,
				BaseURL: profile.BaseURL, Model: profile.Model, ReasoningEffort: profile.ReasoningEffort,
				APIMode: profile.APIMode,
			}
			if profile.ContextWindowTokens > 0 {
				value := profile.ContextWindowTokens
				credential.ContextWindowTokens = &value
			}
			if profile.ProtectedAPIKey != "" {
				plain, err := unprotectSecret(profile.ProtectedAPIKey)
				if err != nil {
					return bundle, fmt.Errorf("无法解密模型连接 %q 的 API Key：%w", profile.Name, err)
				}
				value := string(plain)
				clearBytes(plain)
				credential.APIKey = &value
			}
			bundle.Providers = append(bundle.Providers, credential)
		}
	}
	if request.IncludeCodexLogin {
		if app.codex.accounts == nil {
			return bundle, errors.New("Codex 账号库不可用")
		}
		accounts, activeID, settings, err := app.codex.accounts.AccountCredentials()
		if err != nil {
			return bundle, err
		}
		bundle.ActiveCodexAccountID = activeID
		bundle.CodexAccountSettings = &desktopbridge.SyncedCodexAccountSettings{
			AutoSwitch: settings.AutoSwitch, ReservePercent: settings.ReservePercent, CooldownMinutes: settings.CooldownMinutes,
		}
		for _, account := range accounts {
			synced := desktopbridge.SyncedCodexAccount{
				ID: account.ID, Label: account.Label, Email: account.Email, PlanType: account.PlanType,
				Enabled: account.Enabled, AuthJSON: account.AuthJSON, LastUsedAt: account.LastUsedAt,
			}
			bundle.CodexAccounts = append(bundle.CodexAccounts, synced)
			if account.ID == activeID && account.AuthJSON != nil {
				value := *account.AuthJSON
				bundle.CodexAuthJSON = &value
			}
		}
	}
	if request.IncludeGitHubCredentials {
		accounts, activeID, err := app.workflows.store.githubAccountCredentials()
		if err != nil {
			return bundle, err
		}
		bundle.ActiveGitHubAccountID = activeID
		for _, account := range accounts {
			synced := desktopbridge.SyncedGitHubAccount{
				ID: account.ID, Label: account.Label, Login: account.Login,
				APIBaseURL: account.APIBaseURL, Token: account.Token, LastUsedAt: account.LastUsedAt,
			}
			bundle.GitHubAccounts = append(bundle.GitHubAccounts, synced)
			if account.ID == activeID {
				legacy := desktopbridge.SyncedGitHubCredential{
					APIBaseURL: account.APIBaseURL, Token: account.Token, ViewerLogin: account.Login,
				}
				bundle.GitHub = &legacy
			}
		}
		if bundle.GitHub == nil {
			bundle.GitHub = &desktopbridge.SyncedGitHubCredential{APIBaseURL: "https://api.github.com"}
		}
	}
	if request.IncludeAgentSettings {
		temperature := config.Temperature
		maxTokens := config.MaxTokens
		enableMultimodal := config.EnableMultimodalMessages
		plannerEnabled := config.PlannerProfileEnabled
		plannerModel := config.PlannerModel
		plannerReasoning := config.PlannerReasoningEffort
		subagentEnabled := config.SubagentProfileEnabled
		subagentModel := config.SubagentModel
		subagentReasoning := config.SubagentReasoningEffort
		bundle.AgentSettings = &desktopbridge.SyncedAgentSettings{
			ApprovalMode: config.ApprovalMode, SystemPrompt: config.SystemPrompt, ResponseVerbosity: config.ResponseVerbosity,
			Temperature: &temperature, MaxTokens: &maxTokens, EnableMultimodalMessages: &enableMultimodal,
			PlannerProfileEnabled: &plannerEnabled, PlannerModel: &plannerModel, PlannerReasoningEffort: &plannerReasoning,
			SubagentProfileEnabled: &subagentEnabled, SubagentModel: &subagentModel, SubagentReasoningEffort: &subagentReasoning,
		}
		bundle.MediaSettings = syncedMediaSettings(config)
		if request.IncludeProviderCredentials {
			credentials, err := syncedMediaCredentials(config)
			if err != nil {
				return bundle, err
			}
			bundle.MediaCredentials = credentials
		}
	}
	if request.IncludeKnowledge {
		bundle.Knowledge = exportSyncedKnowledge(config)
	}
	if request.IncludeMCP {
		servers, err := app.exportSyncedMCPServers(config, request.IncludeMCPCredentials)
		if err != nil {
			return bundle, err
		}
		bundle.MCPServers = servers
		bundle.MCPCredentialsIncluded = request.IncludeMCPCredentials
	}
	if request.IncludeSavedWorkflows {
		bundle.SavedWorkflows = exportSyncedWorkflows(app.workflows.store.backupSnapshot().Document.Workflows)
	}
	return bundle, nil
}

func syncedMediaSettings(config desktopConfig) *desktopbridge.SyncedMediaSettings {
	visionProfile := findProviderProfile(config.ProviderProfiles, config.VisionProviderProfileID)
	imageProfile := findProviderProfile(config.ProviderProfiles, config.ImageGenerationProviderProfileID)
	visionProviderID := ""
	if visionProfile != nil {
		visionProviderID = visionProfile.ProviderID
	}
	imageProviderID := ""
	if imageProfile != nil {
		imageProviderID = imageProfile.ProviderID
	}
	return &desktopbridge.SyncedMediaSettings{
		VisionRoutingEnabled: config.VisionRoutingEnabled,
		VisionProviderID:     visionProviderID, VisionProfileID: config.VisionProviderProfileID,
		VisionModel: config.VisionModel, VisionCustomBaseURL: config.VisionCustomBaseURL,
		ImageGenerationProviderID: imageProviderID, ImageGenerationProfileID: config.ImageGenerationProviderProfileID,
		ImageGenerationModel: config.ImageGenerationModel, ImageGenerationCustomBaseURL: config.ImageGenerationCustomBaseURL,
		ImageGenerationSize: config.ImageGenerationSize, ImageGenerationQuality: config.ImageGenerationQuality,
		ImageGenerationFormat: config.ImageGenerationFormat, ImageGenerationCompression: config.ImageGenerationCompression,
		ImageGenerationPartialImages: config.ImageGenerationPartialImages,
		ImageUpscaleBaseURL:          config.ImageUpscaleBaseURL, ImageUpscaleModel: config.ImageUpscaleModel,
		ImageUpscaleScale: config.ImageUpscaleScale,
	}
}

func syncedMediaCredentials(config desktopConfig) (*desktopbridge.SyncedMediaCredentials, error) {
	credentials := &desktopbridge.SyncedMediaCredentials{}
	if config.ProtectedVisionAPIKey != "" {
		plain, err := unprotectSecret(config.ProtectedVisionAPIKey)
		if err != nil {
			return nil, fmt.Errorf("无法解密独立看图 API Key：%w", err)
		}
		value := string(plain)
		clearBytes(plain)
		credentials.VisionCustomAPIKey = &value
	}
	if config.ProtectedImageGenerationAPIKey != "" {
		plain, err := unprotectSecret(config.ProtectedImageGenerationAPIKey)
		if err != nil {
			clearCredentialBundle(&desktopbridge.CredentialSyncBundle{MediaCredentials: credentials})
			return nil, fmt.Errorf("无法解密图片生成 API Key：%w", err)
		}
		value := string(plain)
		clearBytes(plain)
		credentials.ImageGenerationCustomAPIKey = &value
	}
	if config.ProtectedImageUpscaleAPIKey != "" {
		plain, err := unprotectSecret(config.ProtectedImageUpscaleAPIKey)
		if err != nil {
			clearCredentialBundle(&desktopbridge.CredentialSyncBundle{MediaCredentials: credentials})
			return nil, fmt.Errorf("无法解密 4K 超分 API Key：%w", err)
		}
		value := string(plain)
		clearBytes(plain)
		credentials.ImageUpscaleAPIKey = &value
	}
	if credentials.VisionCustomAPIKey == nil && credentials.ImageGenerationCustomAPIKey == nil && credentials.ImageUpscaleAPIKey == nil {
		return nil, nil
	}
	return credentials, nil
}

func (app *DesktopAgentApp) importCredentialBundle(
	ctx context.Context,
	bundle desktopbridge.CredentialSyncBundle,
) (desktopbridge.CredentialSyncResult, error) {
	if err := validateCredentialBundle(bundle); err != nil {
		return desktopbridge.CredentialSyncResult{}, err
	}
	previousConfig := app.store.rawConfig()
	previousWorkflows := app.workflows.store.backupSnapshot()
	providers, apiKeys, err := app.store.importSyncedProviders(bundle)
	if err != nil {
		return desktopbridge.CredentialSyncResult{}, err
	}
	result := desktopbridge.CredentialSyncResult{ImportedProviders: providers, ImportedAPIKeys: apiKeys}
	portable, err := app.store.importSyncedPortableState(bundle)
	if err != nil {
		return result, app.rollbackDeviceSync(previousConfig, previousWorkflows, err)
	}
	result.ImportedSettings = portable.ImportedSettings
	result.ImportedRules = portable.ImportedRules
	result.ImportedMemories = portable.ImportedMemories
	result.ImportedSkills = portable.ImportedSkills
	result.ImportedMCPServers = portable.ImportedMCPServers
	result.DisabledMCPServers = portable.DisabledMCPServers
	workflowState := previousWorkflows
	workflowStateChanged := false
	if len(bundle.GitHubAccounts) == 0 && bundle.GitHub != nil {
		var imported bool
		workflowState, imported, err = importSyncedGitHubCredential(workflowState, *bundle.GitHub)
		if err != nil {
			return result, app.rollbackDeviceSync(previousConfig, previousWorkflows, err)
		}
		result.ImportedGitHubToken = imported
		workflowStateChanged = true
	}
	if len(bundle.SavedWorkflows) > 0 {
		var imported, skipped int
		workflowState, imported, skipped = importSyncedWorkflows(workflowState, bundle.SavedWorkflows)
		result.ImportedWorkflows = imported
		result.SkippedWorkflows = skipped
		workflowStateChanged = true
	}
	if workflowStateChanged {
		if err := app.workflows.store.restoreBackupSnapshot(workflowState); err != nil {
			return result, app.rollbackDeviceSync(previousConfig, previousWorkflows, err)
		}
		app.workflows.rescheduleAll()
		app.workflows.emitChanged()
	}
	if len(bundle.GitHubAccounts) > 0 {
		accounts := make([]githubAccountTransfer, 0, len(bundle.GitHubAccounts))
		for _, incoming := range bundle.GitHubAccounts {
			accounts = append(accounts, githubAccountTransfer{
				ID: incoming.ID, Label: incoming.Label, Login: incoming.Login, APIBaseURL: incoming.APIBaseURL,
				Token: incoming.Token, LastUsedAt: incoming.LastUsedAt,
			})
		}
		importedToken, importErr := app.workflows.store.importGitHubAccounts(accounts, bundle.ActiveGitHubAccountID)
		if importErr != nil {
			return result, app.rollbackDeviceSync(previousConfig, previousWorkflows, importErr)
		}
		result.ImportedGitHubAccounts = len(accounts)
		result.ImportedGitHubToken = importedToken
		app.workflows.mu.Lock()
		app.workflows.viewer = app.workflows.store.state("").GitHub.Viewer
		app.workflows.mu.Unlock()
		app.workflows.emitChanged()
		app.refreshRemoteGitHubToken()
	}
	if len(bundle.CodexAccounts) == 0 && bundle.CodexAuthJSON != nil {
		email, err := app.replaceCodexAuthAndVerify(ctx, []byte(*bundle.CodexAuthJSON))
		if err != nil {
			return result, app.rollbackDeviceSync(previousConfig, previousWorkflows, err)
		}
		result.ImportedCodexLogin = true
		result.AccountEmail = stringPointer(email)
	}
	if len(bundle.Sessions) > 0 {
		merged, mergeErr := app.store.mergeSyncedSessions(bundle.SourcePlatform, bundle.Sessions)
		if mergeErr != nil {
			return result, app.rollbackDeviceSync(previousConfig, previousWorkflows, mergeErr)
		}
		result.ImportedSessions = merged.Imported
		result.ConflictSessions = merged.Conflicts
		result.SkippedSessions = merged.Skipped
	}
	if len(bundle.MCPServers) > 0 {
		if runtimeConfigs, runtimeErr := app.store.mcpRuntimeConfigs(); runtimeErr == nil {
			app.mcp.ConnectAll(context.Background(), runtimeConfigs, true)
		}
	}
	if len(bundle.GitHubAccounts) == 0 && bundle.GitHub != nil && result.ImportedGitHubToken {
		app.workflows.mu.Lock()
		app.workflows.viewer = strings.TrimSpace(bundle.GitHub.ViewerLogin)
		app.workflows.mu.Unlock()
		app.workflows.emitChanged()
	}
	if len(bundle.CodexAccounts) > 0 {
		accounts := make([]codexAccountTransfer, 0, len(bundle.CodexAccounts))
		hasAuth := false
		for _, incoming := range bundle.CodexAccounts {
			accounts = append(accounts, codexAccountTransfer{
				ID: incoming.ID, Label: incoming.Label, Email: incoming.Email, PlanType: incoming.PlanType,
				Enabled: incoming.Enabled, AuthJSON: incoming.AuthJSON, LastUsedAt: incoming.LastUsedAt,
			})
			hasAuth = hasAuth || (incoming.AuthJSON != nil && strings.TrimSpace(*incoming.AuthJSON) != "")
		}
		var settings *CodexAccountPoolSettings
		if incoming := bundle.CodexAccountSettings; incoming != nil {
			value := CodexAccountPoolSettings{
				AutoSwitch: incoming.AutoSwitch, ReservePercent: incoming.ReservePercent, CooldownMinutes: incoming.CooldownMinutes,
			}
			settings = &value
		}
		status, imported, importErr := app.codex.ImportAccountPool(accounts, bundle.ActiveCodexAccountID, settings)
		if importErr != nil {
			return result, app.rollbackDeviceSync(previousConfig, previousWorkflows, importErr)
		}
		result.ImportedCodexAccounts = imported
		result.ImportedCodexLogin = hasAuth
		for _, account := range status.AccountPool.Accounts {
			if account.Active && account.Email != "" {
				result.AccountEmail = stringPointer(account.Email)
				break
			}
		}
	}
	return result, nil
}

func (app *DesktopAgentApp) rollbackDeviceSync(
	previousConfig desktopConfig,
	previousWorkflows savedWorkflowStoreBackupSnapshot,
	cause error,
) error {
	rollbackErrors := []error{}
	if err := app.store.restoreCredentialSyncConfig(previousConfig); err != nil {
		rollbackErrors = append(rollbackErrors, err)
	}
	if err := app.workflows.store.restoreBackupSnapshot(previousWorkflows); err != nil {
		rollbackErrors = append(rollbackErrors, err)
	} else {
		app.workflows.rescheduleAll()
		app.workflows.emitChanged()
		app.refreshRemoteGitHubToken()
	}
	if len(rollbackErrors) == 0 {
		return cause
	}
	return fmt.Errorf("%w；设备同步回滚出现 %d 个错误：%v", cause, len(rollbackErrors), rollbackErrors)
}

func (store *desktopStore) restoreCredentialSyncConfig(previous desktopConfig) error {
	store.mu.Lock()
	defer store.mu.Unlock()
	previous = normalizeDesktopConfig(previous)
	if err := writeJSONAtomic(store.configPath, previous); err != nil {
		return err
	}
	store.config = previous
	return nil
}

func (store *desktopStore) importSyncedProviders(bundle desktopbridge.CredentialSyncBundle) (int, int, error) {
	if len(bundle.Providers) == 0 {
		return 0, 0, nil
	}
	store.mu.Lock()
	defer store.mu.Unlock()
	updated := store.config
	updated.ProviderProfiles = append([]ProviderProfile(nil), store.config.ProviderProfiles...)
	imported, keys := 0, 0
	for _, incoming := range bundle.Providers {
		providerID := normalizeSyncedProviderID(incoming.ProviderID)
		index := -1
		for candidate := range updated.ProviderProfiles {
			if updated.ProviderProfiles[candidate].ID == incoming.ProfileID {
				index = candidate
				break
			}
		}
		if index >= 0 && updated.ProviderProfiles[index].ProviderID != providerID {
			return 0, 0, errors.New("同步包中的模型连接 ID 与本机其他类型冲突")
		}
		profile := defaultProviderProfile(providerID)
		if index >= 0 {
			profile = updated.ProviderProfiles[index]
		}
		profile.ID = incoming.ProfileID
		profile.ProviderID = providerID
		profile.Name = incoming.Name
		profile.BaseURL = incoming.BaseURL
		profile.Model = incoming.Model
		profile.ReasoningEffort = incoming.ReasoningEffort
		profile.APIMode = incoming.APIMode
		if incoming.ContextWindowTokens != nil {
			profile.ContextWindowTokens = *incoming.ContextWindowTokens
		}
		if incoming.APIKey != nil && strings.TrimSpace(*incoming.APIKey) != "" {
			protected, err := protectSecret([]byte(strings.TrimSpace(*incoming.APIKey)))
			if err != nil {
				return 0, 0, err
			}
			profile.ProtectedAPIKey = protected
			keys++
		}
		if index >= 0 {
			updated.ProviderProfiles[index] = profile
		} else {
			if len(updated.ProviderProfiles) >= 64 {
				return 0, 0, errors.New("本机模型连接已达到 64 个上限")
			}
			updated.ProviderProfiles = append(updated.ProviderProfiles, profile)
		}
		imported++
	}
	if bundle.ActiveProviderID != nil {
		if *bundle.ActiveProviderID == "codex" {
			codex := firstProviderByKind(updated.ProviderProfiles, providerCodex)
			if codex == nil && len(updated.ProviderProfiles) >= 64 {
				return 0, 0, errors.New("无法激活 Codex：本机模型连接已达到 64 个上限")
			} else if codex == nil {
				profile := defaultProviderProfile(providerCodex)
				updated.ProviderProfiles = append(updated.ProviderProfiles, profile)
				updated.ActiveProviderProfileID = profile.ID
			} else if codex != nil {
				updated.ActiveProviderProfileID = codex.ID
			}
		} else if bundle.ActiveProfileID != nil {
			candidate := findProviderProfile(updated.ProviderProfiles, *bundle.ActiveProfileID)
			if candidate != nil && candidate.ProviderID == normalizeSyncedProviderID(*bundle.ActiveProviderID) {
				updated.ActiveProviderProfileID = candidate.ID
			}
		}
	}
	updated = normalizeDesktopConfig(updated)
	for _, profile := range updated.ProviderProfiles {
		if profile.ProviderID != providerCodex && profile.ProviderID != providerBuiltinLocal {
			if err := validateBaseURL(profile.BaseURL); err != nil {
				return 0, 0, fmt.Errorf("模型连接 %q：%w", profile.Name, err)
			}
		}
	}
	if err := writeJSONAtomic(store.configPath, updated); err != nil {
		return 0, 0, err
	}
	store.config = updated
	return imported, keys, nil
}

type portableSyncImportResult struct {
	ImportedSettings   bool
	ImportedRules      int
	ImportedMemories   int
	ImportedSkills     int
	ImportedMCPServers int
	DisabledMCPServers int
}

func exportSyncedKnowledge(config desktopConfig) *desktopbridge.SyncedKnowledge {
	result := &desktopbridge.SyncedKnowledge{
		Rules: []desktopbridge.SyncedRule{}, Memories: []desktopbridge.SyncedMemory{}, Skills: []desktopbridge.SyncedSkill{},
	}
	for _, value := range config.GlobalRules {
		result.Rules = append(result.Rules, desktopbridge.SyncedRule{
			ID: value.ID, Title: value.Title, Content: value.Content, Enabled: value.Enabled,
		})
	}
	for _, value := range config.GlobalMemories {
		result.Memories = append(result.Memories, desktopbridge.SyncedMemory{
			ID: value.ID, Title: value.Title, Content: value.Content, Enabled: value.Enabled,
		})
	}
	for _, value := range config.GlobalSkills {
		result.Skills = append(result.Skills, desktopbridge.SyncedSkill{
			ID: value.ID, Title: value.Title, Description: value.Description, Content: value.Content,
			RunAs: value.RunAs, AllowedTools: append([]string{}, value.AllowedTools...),
			PreferredModel: value.PreferredModel, Enabled: value.Enabled,
		})
	}
	return result
}

func (app *DesktopAgentApp) exportSyncedMCPServers(
	config desktopConfig,
	includeCredentials bool,
) ([]desktopbridge.SyncedMCPServer, error) {
	runtimeByID := map[string]mcpRuntimeConfig{}
	if includeCredentials {
		runtimeConfigs, err := app.store.mcpRuntimeConfigs()
		if err != nil {
			return nil, err
		}
		for _, runtimeConfig := range runtimeConfigs {
			runtimeByID[runtimeConfig.ID] = runtimeConfig
		}
	}
	result := make([]desktopbridge.SyncedMCPServer, 0, len(config.MCPServers))
	for _, server := range config.MCPServers {
		portable := desktopbridge.SyncedMCPServer{
			ID: server.ID, Name: server.Name, Transport: server.Transport, Command: server.Command,
			Args: append([]string{}, server.Args...), URL: server.URL,
			RequestTimeoutSeconds: server.RequestTimeoutSeconds,
			TrustedReadOnlyTools:  append([]string{}, server.TrustedReadOnlyTools...),
			Enabled:               server.Enabled, AutoStart: server.AutoStart,
		}
		if runtimeConfig, ok := runtimeByID[server.ID]; ok {
			portable.Environment = cloneSyncStringMap(runtimeConfig.Environment)
			portable.Headers = cloneSyncStringMap(runtimeConfig.Headers)
		}
		result = append(result, portable)
	}
	return result, nil
}

func exportSyncedWorkflows(values []SavedWorkflowDefinition) []desktopbridge.SyncedSavedWorkflow {
	result := make([]desktopbridge.SyncedSavedWorkflow, 0, len(values))
	for _, workflow := range values {
		portable := desktopbridge.SyncedSavedWorkflow{
			ID: workflow.ID, Name: workflow.Name, Template: workflow.Template,
			GitHubRepository: workflow.GitHubRepository, IntervalMinutes: workflow.IntervalMinutes,
			CreatedAt: workflow.CreatedAt, UpdatedAt: workflow.UpdatedAt,
			Nodes: make([]desktopbridge.SyncedWorkflowNode, 0, len(workflow.Nodes)),
		}
		for _, node := range workflow.Nodes {
			portable.Nodes = append(portable.Nodes, desktopbridge.SyncedWorkflowNode{
				ID: node.ID, Label: node.Label, DependsOn: append([]string{}, node.DependsOn...),
				RequiredPermission: node.RequiredPermission, TimeoutSeconds: node.TimeoutSeconds, MaxRetries: node.MaxRetries,
			})
		}
		result = append(result, portable)
	}
	return result
}

func (store *desktopStore) importSyncedPortableState(
	bundle desktopbridge.CredentialSyncBundle,
) (portableSyncImportResult, error) {
	store.mu.Lock()
	defer store.mu.Unlock()
	result := portableSyncImportResult{}
	updated := store.config
	changed := false
	if bundle.AgentSettings != nil {
		updated.ApprovalMode = bundle.AgentSettings.ApprovalMode
		updated.SystemPrompt = strings.TrimSpace(bundle.AgentSettings.SystemPrompt)
		updated.ResponseVerbosity = strings.ToUpper(strings.TrimSpace(bundle.AgentSettings.ResponseVerbosity))
		if bundle.AgentSettings.Temperature != nil {
			updated.Temperature = *bundle.AgentSettings.Temperature
		}
		if bundle.AgentSettings.MaxTokens != nil {
			updated.MaxTokens = *bundle.AgentSettings.MaxTokens
		}
		if bundle.AgentSettings.EnableMultimodalMessages != nil {
			updated.EnableMultimodalMessages = *bundle.AgentSettings.EnableMultimodalMessages
		}
		if bundle.AgentSettings.PlannerProfileEnabled != nil {
			updated.PlannerProfileEnabled = *bundle.AgentSettings.PlannerProfileEnabled
		}
		if bundle.AgentSettings.PlannerModel != nil {
			updated.PlannerModel = *bundle.AgentSettings.PlannerModel
		}
		if bundle.AgentSettings.PlannerReasoningEffort != nil {
			updated.PlannerReasoningEffort = *bundle.AgentSettings.PlannerReasoningEffort
		}
		if bundle.AgentSettings.SubagentProfileEnabled != nil {
			updated.SubagentProfileEnabled = *bundle.AgentSettings.SubagentProfileEnabled
		}
		if bundle.AgentSettings.SubagentModel != nil {
			updated.SubagentModel = *bundle.AgentSettings.SubagentModel
		}
		if bundle.AgentSettings.SubagentReasoningEffort != nil {
			updated.SubagentReasoningEffort = *bundle.AgentSettings.SubagentReasoningEffort
		}
		result.ImportedSettings = true
		changed = true
	}
	if settings := bundle.MediaSettings; settings != nil {
		updated.VisionRoutingEnabled = settings.VisionRoutingEnabled
		updated.VisionProviderProfileID = resolveSyncedMediaProfileID(
			updated.ProviderProfiles,
			settings.VisionProviderID,
			settings.VisionProfileID,
			updated.VisionProviderProfileID,
		)
		updated.VisionModel = strings.TrimSpace(settings.VisionModel)
		updated.VisionCustomBaseURL = strings.TrimSpace(settings.VisionCustomBaseURL)
		updated.ImageGenerationProviderProfileID = resolveSyncedMediaProfileID(
			updated.ProviderProfiles,
			settings.ImageGenerationProviderID,
			settings.ImageGenerationProfileID,
			updated.ImageGenerationProviderProfileID,
		)
		updated.ImageGenerationModel = strings.TrimSpace(settings.ImageGenerationModel)
		updated.ImageGenerationCustomBaseURL = strings.TrimSpace(settings.ImageGenerationCustomBaseURL)
		updated.ImageGenerationSize = settings.ImageGenerationSize
		updated.ImageGenerationQuality = settings.ImageGenerationQuality
		updated.ImageGenerationFormat = settings.ImageGenerationFormat
		updated.ImageGenerationCompression = settings.ImageGenerationCompression
		updated.ImageGenerationPartialImages = settings.ImageGenerationPartialImages
		updated.ImageUpscaleBaseURL = strings.TrimSpace(settings.ImageUpscaleBaseURL)
		updated.ImageUpscaleModel = strings.TrimSpace(settings.ImageUpscaleModel)
		updated.ImageUpscaleScale = settings.ImageUpscaleScale
		result.ImportedSettings = true
		changed = true
	}
	if credentials := bundle.MediaCredentials; credentials != nil {
		var err error
		updated.ProtectedVisionAPIKey, err = mergeSyncedProtectedSecret(
			updated.ProtectedVisionAPIKey,
			credentials.VisionCustomAPIKey,
		)
		if err != nil {
			return result, fmt.Errorf("无法保护同步的独立看图 API Key：%w", err)
		}
		updated.ProtectedImageGenerationAPIKey, err = mergeSyncedProtectedSecret(
			updated.ProtectedImageGenerationAPIKey,
			credentials.ImageGenerationCustomAPIKey,
		)
		if err != nil {
			return result, fmt.Errorf("无法保护同步的图片生成 API Key：%w", err)
		}
		updated.ProtectedImageUpscaleAPIKey, err = mergeSyncedProtectedSecret(
			updated.ProtectedImageUpscaleAPIKey,
			credentials.ImageUpscaleAPIKey,
		)
		if err != nil {
			return result, fmt.Errorf("无法保护同步的 4K 超分 API Key：%w", err)
		}
		changed = true
	}
	if bundle.Knowledge != nil {
		updated.GlobalRules = mergeSyncedRules(updated.GlobalRules, bundle.Knowledge.Rules)
		updated.GlobalMemories = mergeSyncedMemories(updated.GlobalMemories, bundle.Knowledge.Memories)
		updated.GlobalSkills = mergeSyncedSkills(updated.GlobalSkills, bundle.Knowledge.Skills)
		result.ImportedRules = len(bundle.Knowledge.Rules)
		result.ImportedMemories = len(bundle.Knowledge.Memories)
		result.ImportedSkills = len(bundle.Knowledge.Skills)
		changed = true
	}
	if len(bundle.MCPServers) > 0 {
		servers, disabled, err := mergeSyncedMCPServers(updated.MCPServers, bundle.MCPServers, bundle.MCPCredentialsIncluded)
		if err != nil {
			return result, err
		}
		updated.MCPServers = servers
		result.ImportedMCPServers = len(bundle.MCPServers)
		result.DisabledMCPServers = disabled
		changed = true
	}
	if !changed {
		return result, nil
	}
	updated = normalizeDesktopConfig(updated)
	if err := validateSyncedPortableConfig(updated, bundle); err != nil {
		return result, err
	}
	if err := writeJSONAtomic(store.configPath, updated); err != nil {
		return result, err
	}
	store.config = updated
	return result, nil
}

func resolveSyncedMediaProfileID(
	profiles []ProviderProfile,
	providerID, requestedID, currentID string,
) string {
	providerID = normalizeSyncedProviderID(providerID)
	if requested := findProviderProfile(profiles, requestedID); requested != nil &&
		(providerID == "" || requested.ProviderID == providerID) {
		return requested.ID
	}
	if current := findProviderProfile(profiles, currentID); current != nil &&
		(providerID == "" || current.ProviderID == providerID) {
		return current.ID
	}
	if providerID != "" {
		if candidate := firstProviderByKind(profiles, providerID); candidate != nil {
			return candidate.ID
		}
	}
	return ""
}

func mergeSyncedProtectedSecret(current string, incoming *string) (string, error) {
	if incoming == nil || strings.TrimSpace(*incoming) == "" {
		return current, nil
	}
	plain := []byte(strings.TrimSpace(*incoming))
	protected, err := protectSecret(plain)
	clearBytes(plain)
	if err != nil {
		return current, err
	}
	return protected, nil
}

func mergeSyncedRules(existing []GlobalRule, incoming []desktopbridge.SyncedRule) []GlobalRule {
	result := append([]GlobalRule{}, existing...)
	indexByID := map[string]int{}
	for index, value := range result {
		indexByID[value.ID] = index
	}
	for _, value := range incoming {
		converted := GlobalRule{ID: value.ID, Title: value.Title, Content: value.Content, Enabled: value.Enabled}
		if index, ok := indexByID[value.ID]; ok {
			result[index] = converted
		} else {
			indexByID[value.ID] = len(result)
			result = append(result, converted)
		}
	}
	return normalizeRules(result)
}

func mergeSyncedMemories(existing []GlobalMemory, incoming []desktopbridge.SyncedMemory) []GlobalMemory {
	result := append([]GlobalMemory{}, existing...)
	indexByID := map[string]int{}
	for index, value := range result {
		indexByID[value.ID] = index
	}
	for _, value := range incoming {
		converted := GlobalMemory{ID: value.ID, Title: value.Title, Content: value.Content, Enabled: value.Enabled}
		if index, ok := indexByID[value.ID]; ok {
			result[index] = converted
		} else {
			indexByID[value.ID] = len(result)
			result = append(result, converted)
		}
	}
	return normalizeMemories(result)
}

func mergeSyncedSkills(existing []GlobalSkill, incoming []desktopbridge.SyncedSkill) []GlobalSkill {
	result := cloneSkills(existing)
	indexByID := map[string]int{}
	for index, value := range result {
		indexByID[value.ID] = index
	}
	for _, value := range incoming {
		converted := GlobalSkill{
			ID: value.ID, Title: value.Title, Description: value.Description, Content: value.Content,
			RunAs: value.RunAs, AllowedTools: append([]string{}, value.AllowedTools...),
			PreferredModel: value.PreferredModel, Enabled: value.Enabled,
		}
		if index, ok := indexByID[value.ID]; ok {
			result[index] = converted
		} else {
			indexByID[value.ID] = len(result)
			result = append(result, converted)
		}
	}
	return normalizeSkills(result)
}

func mergeSyncedMCPServers(
	existing []MCPServerConfig,
	incoming []desktopbridge.SyncedMCPServer,
	credentialsIncluded bool,
) ([]MCPServerConfig, int, error) {
	result := cloneMCPServerConfigs(existing)
	indexByName := map[string]int{}
	for index, value := range result {
		indexByName[strings.ToLower(value.Name)] = index
	}
	disabled := 0
	for _, portable := range incoming {
		index, found := indexByName[strings.ToLower(strings.TrimSpace(portable.Name))]
		previous := MCPServerConfig{}
		if found {
			previous = result[index]
		}
		id := strings.TrimSpace(portable.ID)
		if found {
			id = previous.ID
		} else if id == "" {
			id = newID("mcp")
		}
		server := MCPServerConfig{
			ID: id, Name: portable.Name, Transport: portable.Transport, Command: portable.Command,
			Args: append([]string{}, portable.Args...), URL: portable.URL,
			RequestTimeoutSeconds: portable.RequestTimeoutSeconds,
			TrustedReadOnlyTools:  append([]string{}, portable.TrustedReadOnlyTools...),
			Enabled:               portable.Enabled, AutoStart: portable.AutoStart,
			ProtectedEnvironmentJSON: previous.ProtectedEnvironmentJSON,
			ProtectedHeadersJSON:     previous.ProtectedHeadersJSON,
		}
		server = normalizeMCPServerConfig(server)
		if server.Transport == mcpTransportStdio {
			if server.Enabled {
				disabled++
			}
			server.Enabled = false
			server.AutoStart = false
			server.Cwd = ""
		}
		environment := cloneSyncStringMap(portable.Environment)
		headers := cloneSyncStringMap(portable.Headers)
		if !credentialsIncluded {
			previousEnvironment, err := unprotectMCPSecretMap(previous.ProtectedEnvironmentJSON)
			if err != nil {
				return nil, disabled, fmt.Errorf("MCP 服务器 %q 原环境变量解密失败：%w", server.Name, err)
			}
			previousHeaders, err := unprotectMCPSecretMap(previous.ProtectedHeadersJSON)
			if err != nil {
				return nil, disabled, fmt.Errorf("MCP 服务器 %q 原请求头解密失败：%w", server.Name, err)
			}
			environment = mergeSyncStringMaps(previousEnvironment, environment)
			headers = mergeSyncStringMaps(previousHeaders, headers)
		}
		var err error
		server.ProtectedEnvironmentJSON, err = protectMCPSecretMap(environment, false)
		if err == nil {
			server.ProtectedHeadersJSON, err = protectMCPSecretMap(headers, true)
		}
		if err != nil {
			return nil, disabled, fmt.Errorf("MCP 服务器 %q 凭据导入失败：%w", server.Name, err)
		}
		if err := validateMCPServerConfig(server); err != nil {
			return nil, disabled, err
		}
		if found {
			result[index] = server
		} else {
			indexByName[strings.ToLower(server.Name)] = len(result)
			result = append(result, server)
		}
	}
	return normalizeMCPServerConfigs(result), disabled, nil
}

func validateSyncedPortableConfig(config desktopConfig, bundle desktopbridge.CredentialSyncBundle) error {
	if bundle.AgentSettings != nil {
		if config.ApprovalMode != approvalReadOnly && config.ApprovalMode != approvalAskAll &&
			config.ApprovalMode != approvalAllowlist && config.ApprovalMode != approvalYolo {
			return errors.New("设备同步审批模式无效")
		}
		if strings.TrimSpace(config.SystemPrompt) == "" || len(config.SystemPrompt) > 1<<20 {
			return errors.New("设备同步系统提示词无效或过长")
		}
		if config.ResponseVerbosity != "CONCISE" && config.ResponseVerbosity != "BALANCED" && config.ResponseVerbosity != "DETAILED" {
			return errors.New("设备同步回复详细度无效")
		}
		if config.Temperature < 0 || config.Temperature > 2 || config.MaxTokens < 1 || config.MaxTokens > 128_000 {
			return errors.New("设备同步生成参数无效")
		}
	}
	if bundle.MediaSettings != nil {
		if err := validateSyncedMediaSettings(bundle.MediaSettings); err != nil {
			return err
		}
	}
	return nil
}

func validateSyncedMediaSettings(settings *desktopbridge.SyncedMediaSettings) error {
	if settings == nil {
		return nil
	}
	for label, value := range map[string]string{
		"独立看图供应商":        settings.VisionProviderID,
		"独立看图连接":         settings.VisionProfileID,
		"独立看图模型":         settings.VisionModel,
		"独立看图 Base URL":  settings.VisionCustomBaseURL,
		"图片生成供应商":        settings.ImageGenerationProviderID,
		"图片生成连接":         settings.ImageGenerationProfileID,
		"图片生成模型":         settings.ImageGenerationModel,
		"图片生成 Base URL":  settings.ImageGenerationCustomBaseURL,
		"4K 超分 Base URL": settings.ImageUpscaleBaseURL,
		"4K 超分模型":        settings.ImageUpscaleModel,
	} {
		if value != strings.TrimSpace(value) || len(value) > 500 || strings.ContainsAny(value, "\x00\r\n") {
			return fmt.Errorf("设备同步%s无效或过长", label)
		}
	}
	for _, providerID := range []string{settings.VisionProviderID, settings.ImageGenerationProviderID} {
		if providerID != "" && !isSupportedSyncedProviderID(normalizeSyncedProviderID(providerID)) {
			return errors.New("设备同步媒体供应商无效")
		}
	}
	for label, baseURL := range map[string]string{
		"独立看图":  settings.VisionCustomBaseURL,
		"图片生成":  settings.ImageGenerationCustomBaseURL,
		"4K 超分": settings.ImageUpscaleBaseURL,
	} {
		if baseURL != "" {
			if err := validateBaseURL(baseURL); err != nil {
				return fmt.Errorf("设备同步%s Base URL：%w", label, err)
			}
		}
	}
	if normalizeImageGenerationSize(settings.ImageGenerationSize) != strings.ToLower(strings.TrimSpace(settings.ImageGenerationSize)) && settings.ImageGenerationSize != "" {
		return errors.New("设备同步图片尺寸无效")
	}
	if normalizeImageGenerationQuality(settings.ImageGenerationQuality) != strings.ToLower(strings.TrimSpace(settings.ImageGenerationQuality)) && settings.ImageGenerationQuality != "" {
		return errors.New("设备同步图片质量无效")
	}
	if normalizeImageGenerationFormat(settings.ImageGenerationFormat) != strings.ToLower(strings.TrimSpace(settings.ImageGenerationFormat)) && settings.ImageGenerationFormat != "" {
		return errors.New("设备同步图片格式无效")
	}
	if settings.ImageGenerationCompression < 0 || settings.ImageGenerationCompression > 100 ||
		settings.ImageGenerationPartialImages < 0 || settings.ImageGenerationPartialImages > 3 {
		return errors.New("设备同步图片生成参数无效")
	}
	if settings.ImageUpscaleScale != 0 && (settings.ImageUpscaleScale < 2 || settings.ImageUpscaleScale > 4) {
		return errors.New("设备同步 4K 超分倍率无效")
	}
	return nil
}

func importSyncedGitHubCredential(
	current savedWorkflowStoreBackupSnapshot,
	incoming desktopbridge.SyncedGitHubCredential,
) (savedWorkflowStoreBackupSnapshot, bool, error) {
	document := cloneSavedWorkflowDocument(current.Document)
	reconcileGitHubAccounts(&document)
	baseURL := normalizeGitHubAPIBaseURL(incoming.APIBaseURL)
	if err := validateGitHubAPIBaseURL(baseURL); err != nil {
		return current, false, err
	}
	index := activeGitHubAccountIndex(document)
	if index < 0 {
		return current, false, errors.New("当前 GitHub 账号不存在")
	}
	document.GitHubAccounts[index].APIBaseURL = baseURL
	if login := truncateRunes(strings.TrimSpace(incoming.ViewerLogin), 80); login != "" {
		document.GitHubAccounts[index].Login = login
		document.GitHubAccounts[index].Label = "@" + login
	}
	importedToken := false
	if incoming.Token != nil && strings.TrimSpace(*incoming.Token) != "" {
		plain := []byte(strings.TrimSpace(*incoming.Token))
		protected, err := protectSecret(plain)
		clearBytes(plain)
		if err != nil {
			return current, false, fmt.Errorf("无法保护同步的 GitHub Token：%w", err)
		}
		document.GitHubAccounts[index].ProtectedToken = protected
		importedToken = true
	}
	document.GitHubAccounts[index].LastUsedAt = time.Now().UnixMilli()
	document.GitHub = legacyGitHubConfig(document.GitHubAccounts[index])
	document.SchemaVersion = savedWorkflowSchemaVersion
	return savedWorkflowStoreBackupSnapshot{Document: document}, importedToken, nil
}

func importSyncedWorkflows(
	current savedWorkflowStoreBackupSnapshot,
	incoming []desktopbridge.SyncedSavedWorkflow,
) (savedWorkflowStoreBackupSnapshot, int, int) {
	document := cloneSavedWorkflowDocument(current.Document)
	indexByID := map[string]int{}
	for index, workflow := range document.Workflows {
		indexByID[workflow.ID] = index
	}
	imported, skipped := 0, 0
	for _, portable := range incoming {
		if portable.Template == workflowProjectReadDiagnostic || portable.Template == workflowDirectoryChangeSummary {
			skipped++
			continue
		}
		workflow := SavedWorkflowDefinition{
			ID: portable.ID, Name: portable.Name, Template: portable.Template,
			GitHubRepository: portable.GitHubRepository, IntervalMinutes: portable.IntervalMinutes,
			Enabled: false, CreatedAt: portable.CreatedAt, UpdatedAt: portable.UpdatedAt,
			Nodes: make([]SavedWorkflowNode, 0, len(portable.Nodes)),
		}
		for _, node := range portable.Nodes {
			workflow.Nodes = append(workflow.Nodes, SavedWorkflowNode{
				ID: node.ID, Label: node.Label, DependsOn: append([]string{}, node.DependsOn...),
				RequiredPermission: node.RequiredPermission, TimeoutSeconds: node.TimeoutSeconds, MaxRetries: node.MaxRetries,
			})
		}
		if validation := validateSavedWorkflow(workflow); len(validation) > 0 {
			skipped++
			continue
		}
		if index, ok := indexByID[workflow.ID]; ok {
			document.Workflows[index] = workflow
		} else {
			indexByID[workflow.ID] = len(document.Workflows)
			document.Workflows = append(document.Workflows, workflow)
		}
		imported++
	}
	document.SchemaVersion = savedWorkflowSchemaVersion
	return savedWorkflowStoreBackupSnapshot{Document: document}, imported, skipped
}

func cloneSyncStringMap(values map[string]string) map[string]string {
	if len(values) == 0 {
		return nil
	}
	result := make(map[string]string, len(values))
	for key, value := range values {
		result[key] = value
	}
	return result
}

func mergeSyncStringMaps(base, incoming map[string]string) map[string]string {
	result := cloneSyncStringMap(base)
	if result == nil {
		result = map[string]string{}
	}
	for key, value := range incoming {
		result[key] = value
	}
	return result
}

func (app *DesktopAgentApp) replaceCodexAuthAndVerify(ctx context.Context, auth []byte) (string, error) {
	defer clearBytes(auth)
	if err := validateCodexAuthJSON(auth); err != nil {
		return "", err
	}
	path := app.codex.codexHome
	if err := ensurePrivateCodexHome(path); err != nil {
		return "", err
	}
	previous, previousErr := readCodexAuthJSON(path)
	if previousErr != nil && !errors.Is(previousErr, os.ErrNotExist) {
		return "", previousErr
	}
	defer clearBytes(previous)
	app.codex.Close()
	if err := writeCodexAuthJSON(path, auth); err != nil {
		return "", err
	}
	preferred := app.preferredCodexExecutable()
	status, err := app.codex.Refresh(ctx, preferred)
	if err == nil && status.LoggedIn {
		return status.Email, nil
	}
	app.codex.Close()
	if previousErr == nil {
		_ = writeCodexAuthJSON(path, previous)
	} else {
		_ = os.Remove(filepath.Join(path, "auth.json"))
	}
	rollbackContext, cancel := context.WithTimeout(context.Background(), 40*time.Second)
	_, _ = app.codex.Refresh(rollbackContext, preferred)
	cancel()
	if err == nil {
		err = errors.New("同步的 ChatGPT 登录无法通过 Codex 验证")
	}
	return "", fmt.Errorf("ChatGPT 登录同步失败，已恢复电脑原登录：%w", err)
}

func (app *DesktopAgentApp) preferredCodexExecutable() string {
	config := app.store.rawConfig()
	for _, profile := range config.ProviderProfiles {
		if profile.ProviderID == providerCodex && strings.TrimSpace(profile.ExecutablePath) != "" {
			return profile.ExecutablePath
		}
	}
	return ""
}

func validateCredentialBundle(bundle desktopbridge.CredentialSyncBundle) error {
	if (bundle.SchemaVersion < 1 || bundle.SchemaVersion > 8) || (bundle.SourcePlatform != "android" && !isDesktopSourcePlatform(bundle.SourcePlatform)) {
		return errors.New("设备同步格式或来源无效")
	}
	now := time.Now().UnixMilli()
	if bundle.GeneratedAt <= 0 || bundle.GeneratedAt < now-5*time.Minute.Milliseconds() || bundle.GeneratedAt > now+time.Minute.Milliseconds() || len(bundle.Providers) > 64 {
		return errors.New("凭据同步时间或模型连接数量无效")
	}
	for _, profile := range bundle.Providers {
		providerID := normalizeSyncedProviderID(profile.ProviderID)
		if !isSupportedSyncedProviderID(providerID) {
			return errors.New("凭据同步包含不支持的模型连接")
		}
		if strings.TrimSpace(profile.ProfileID) == "" || len(profile.ProfileID) > 100 || len(profile.Name) > 100 || len(profile.Model) > 200 {
			return errors.New("凭据同步模型连接字段无效")
		}
		if profile.APIKey != nil && len(*profile.APIKey) > 16_384 {
			return errors.New("凭据同步 API Key 过长")
		}
		if !isSyncedReasoningEffort(profile.ReasoningEffort) {
			return errors.New("凭据同步推理强度无效")
		}
		if profile.ContextWindowTokens != nil && (*profile.ContextWindowTokens < 4_096 || *profile.ContextWindowTokens > 2_000_000) {
			return errors.New("凭据同步上下文窗口无效")
		}
		// Android's built-in/official relay profiles intentionally encode an
		// empty Base URL and resolve it from the provider default locally.
		// Preserve that portable meaning instead of rejecting the whole
		// encrypted sync bundle; custom non-empty addresses still receive the
		// same strict HTTP(S) validation.
		if baseURL := strings.TrimSpace(profile.BaseURL); baseURL != "" {
			if err := validateBaseURL(strings.TrimRight(baseURL, "/")); err != nil {
				return err
			}
		}
	}
	if github := bundle.GitHub; github != nil {
		if err := validateGitHubAPIBaseURL(normalizeGitHubAPIBaseURL(github.APIBaseURL)); err != nil {
			return err
		}
		if github.Token != nil && len(*github.Token) > 16_384 {
			return errors.New("设备同步 GitHub Token 过长")
		}
		if len(github.ViewerLogin) > 100 || strings.ContainsAny(github.ViewerLogin, "\x00\r\n") {
			return errors.New("设备同步 GitHub 用户名无效")
		}
	}
	if len(bundle.GitHubAccounts) > 20 {
		return errors.New("设备同步 GitHub 账号数量无效")
	}
	seenGitHubAccounts := map[string]bool{}
	for _, account := range bundle.GitHubAccounts {
		if !isSafeCodexAccountID(account.ID) || seenGitHubAccounts[account.ID] || strings.TrimSpace(account.Label) == "" || len(account.Label) > 80 ||
			len(account.Login) > 80 || len(account.Name) > 160 || len(account.AvatarURL) > 2_048 ||
			strings.ContainsAny(account.Label+account.Login+account.Name+account.AvatarURL, "\x00\r\n") {
			return errors.New("设备同步 GitHub 账号字段无效")
		}
		seenGitHubAccounts[account.ID] = true
		if err := validateGitHubAPIBaseURL(normalizeGitHubAPIBaseURL(account.APIBaseURL)); err != nil {
			return err
		}
		if account.Token != nil && len(*account.Token) > 16_384 {
			return errors.New("设备同步 GitHub 账号 Token 过长")
		}
	}
	if len(bundle.GitHubAccounts) > 0 && !seenGitHubAccounts[bundle.ActiveGitHubAccountID] {
		return errors.New("设备同步当前 GitHub 账号无效")
	}
	if len(bundle.CodexAccounts) > 20 {
		return errors.New("设备同步 Codex 账号数量无效")
	}
	seenCodexAccounts := map[string]bool{}
	for _, account := range bundle.CodexAccounts {
		if !isSafeCodexAccountID(account.ID) || seenCodexAccounts[account.ID] || strings.TrimSpace(account.Label) == "" || len(account.Label) > 80 ||
			len(account.Email) > 320 || len(account.PlanType) > 80 || strings.ContainsAny(account.Label+account.Email+account.PlanType, "\x00\r\n") {
			return errors.New("设备同步 Codex 账号字段无效")
		}
		seenCodexAccounts[account.ID] = true
		if account.AuthJSON != nil {
			auth := []byte(*account.AuthJSON)
			if err := validateCodexAuthJSON(auth); err != nil {
				clearBytes(auth)
				return err
			}
			clearBytes(auth)
		}
	}
	if len(bundle.CodexAccounts) > 0 && !seenCodexAccounts[bundle.ActiveCodexAccountID] {
		return errors.New("设备同步当前 Codex 账号无效")
	}
	if settings := bundle.CodexAccountSettings; settings != nil {
		if settings.ReservePercent < 1 || settings.ReservePercent > 50 || settings.CooldownMinutes < 1 || settings.CooldownMinutes > 1440 {
			return errors.New("设备同步 Codex 账号池设置无效")
		}
	}
	if settings := bundle.AgentSettings; settings != nil {
		if settings.ApprovalMode != approvalReadOnly && settings.ApprovalMode != approvalAskAll &&
			settings.ApprovalMode != approvalAllowlist && settings.ApprovalMode != approvalYolo {
			return errors.New("设备同步审批模式无效")
		}
		if strings.TrimSpace(settings.SystemPrompt) == "" || len(settings.SystemPrompt) > 1<<20 {
			return errors.New("设备同步系统提示词无效或过长")
		}
		verbosity := strings.ToLower(settings.ResponseVerbosity)
		if verbosity != "concise" && verbosity != "balanced" && verbosity != "detailed" {
			return errors.New("设备同步回复详细度无效")
		}
		if settings.Temperature != nil && (*settings.Temperature < 0 || *settings.Temperature > 2) {
			return errors.New("设备同步 Temperature 无效")
		}
		if settings.MaxTokens != nil && (*settings.MaxTokens < 1 || *settings.MaxTokens > 128_000) {
			return errors.New("设备同步最大输出 Token 无效")
		}
		if settings.PlannerModel != nil && normalizeExecutionProfileModel(*settings.PlannerModel) != strings.TrimSpace(*settings.PlannerModel) {
			return errors.New("设备同步规划模型无效或过长")
		}
		if settings.SubagentModel != nil && normalizeExecutionProfileModel(*settings.SubagentModel) != strings.TrimSpace(*settings.SubagentModel) {
			return errors.New("设备同步子代理默认模型无效或过长")
		}
		if settings.PlannerReasoningEffort != nil && normalizeExecutionProfileReasoning(*settings.PlannerReasoningEffort) != strings.ToLower(strings.TrimSpace(*settings.PlannerReasoningEffort)) {
			return errors.New("设备同步规划推理强度无效")
		}
		if settings.SubagentReasoningEffort != nil && normalizeExecutionProfileReasoning(*settings.SubagentReasoningEffort) != strings.ToLower(strings.TrimSpace(*settings.SubagentReasoningEffort)) {
			return errors.New("设备同步子代理默认推理强度无效")
		}
	}
	if err := validateSyncedMediaSettings(bundle.MediaSettings); err != nil {
		return err
	}
	if credentials := bundle.MediaCredentials; credentials != nil {
		if credentials.VisionCustomAPIKey != nil && len(*credentials.VisionCustomAPIKey) > 16_384 {
			return errors.New("设备同步独立看图 API Key 过长")
		}
		if credentials.ImageGenerationCustomAPIKey != nil && len(*credentials.ImageGenerationCustomAPIKey) > 16_384 {
			return errors.New("设备同步图片生成 API Key 过长")
		}
		if credentials.ImageUpscaleAPIKey != nil && len(*credentials.ImageUpscaleAPIKey) > 16_384 {
			return errors.New("设备同步 4K 超分 API Key 过长")
		}
	}
	if knowledge := bundle.Knowledge; knowledge != nil {
		if len(knowledge.Rules) > 10_000 || len(knowledge.Memories) > 10_000 || len(knowledge.Skills) > 10_000 {
			return errors.New("设备同步知识库条目数量过多")
		}
		for _, value := range knowledge.Rules {
			if !validSyncedKnowledge(value.ID, value.Title, value.Content) {
				return errors.New("设备同步规则无效或过长")
			}
		}
		for _, value := range knowledge.Memories {
			if !validSyncedKnowledge(value.ID, value.Title, value.Content) {
				return errors.New("设备同步记忆无效或过长")
			}
		}
		for _, value := range knowledge.Skills {
			if !validSyncedKnowledge(value.ID, value.Title, value.Content) || len(value.Description) > 2_000 ||
				(value.RunAs != "INLINE" && value.RunAs != "SUBAGENT") || len(value.AllowedTools) > 500 {
				return errors.New("设备同步 Skill 无效或过长")
			}
		}
	}
	if len(bundle.MCPServers) > maxMCPServers {
		return fmt.Errorf("设备同步 MCP 服务器超过 %d 个", maxMCPServers)
	}
	seenMCP := map[string]bool{}
	for _, server := range bundle.MCPServers {
		nameKey := strings.ToLower(strings.TrimSpace(server.Name))
		if nameKey == "" || len(server.Name) > 200 || seenMCP[nameKey] {
			return errors.New("设备同步 MCP 服务器名称无效或重复")
		}
		seenMCP[nameKey] = true
		if server.Transport != mcpTransportStdio && server.Transport != mcpTransportStreamableHTTP && server.Transport != mcpTransportLegacySSE {
			return errors.New("设备同步 MCP 传输类型无效")
		}
		if len(server.Args) > 64 || server.RequestTimeoutSeconds < 1 || server.RequestTimeoutSeconds > 600 {
			return errors.New("设备同步 MCP 参数或超时无效")
		}
		if _, err := normalizeMCPSecretMap(server.Environment, false); err != nil {
			return err
		}
		if _, err := normalizeMCPSecretMap(server.Headers, true); err != nil {
			return err
		}
	}
	if len(bundle.SavedWorkflows) > maximumSavedWorkflows {
		return fmt.Errorf("设备同步保存工作流超过 %d 个", maximumSavedWorkflows)
	}
	seenWorkflows := map[string]bool{}
	for _, workflow := range bundle.SavedWorkflows {
		if !validSyncedID(workflow.ID) || strings.TrimSpace(workflow.Name) == "" || len(workflow.Name) > 500 ||
			seenWorkflows[workflow.ID] || len(workflow.Nodes) > 100 || workflow.IntervalMinutes < 15 || workflow.IntervalMinutes > 10_080 {
			return errors.New("设备同步保存工作流定义无效")
		}
		seenWorkflows[workflow.ID] = true
	}
	if bundle.CodexAuthJSON != nil {
		auth := []byte(*bundle.CodexAuthJSON)
		defer clearBytes(auth)
		if err := validateCodexAuthJSON(auth); err != nil {
			return err
		}
	}
	if err := validateSyncedSessions(bundle.SourcePlatform, bundle.Sessions); err != nil {
		return err
	}
	return nil
}

func validSyncedKnowledge(id, title, content string) bool {
	return validSyncedID(id) && strings.TrimSpace(title) != "" && len(title) <= 500 &&
		strings.TrimSpace(content) != "" && len(content) <= 1<<20
}

func validSyncedID(value string) bool {
	value = strings.TrimSpace(value)
	return value != "" && len(value) <= 200 && !strings.ContainsAny(value, "\x00\r\n")
}

func isSyncedReasoningEffort(value string) bool {
	switch value {
	case "", "low", "medium", "high", "xhigh", "max", "off", "on":
		return true
	default:
		return false
	}
}

func normalizeSyncedProviderID(value string) string {
	switch strings.ToLower(strings.TrimSpace(value)) {
	case "deepseek":
		return providerDeepSeek
	case "openai", "openai-compatible":
		return providerOpenAI
	case "anthropic", "claude":
		return providerClaude
	case "kimi", "moonshot":
		return providerKimi
	case "glm", "zhipu", "bigmodel":
		return providerGLM
	case "qwen", "dashscope":
		return providerQwen
	case "minimax":
		return providerMiniMax
	case "grok", "xai":
		return providerGrok
	case "mimo", "xiaomimimo":
		return providerMiMo
	case "hy3", "hunyuan":
		return providerHy3
	case "gemini", "google":
		return providerGemini
	default:
		return strings.ToLower(strings.TrimSpace(value))
	}
}

func isSupportedSyncedProviderID(value string) bool {
	return isKnownDesktopProviderID(value)
}

func firstProviderByKind(values []ProviderProfile, kind string) *ProviderProfile {
	for index := range values {
		if values[index].ProviderID == kind {
			return &values[index]
		}
	}
	return nil
}

func clearCredentialBundle(bundle *desktopbridge.CredentialSyncBundle) {
	if bundle == nil {
		return
	}
	for index := range bundle.Providers {
		if bundle.Providers[index].APIKey != nil {
			*bundle.Providers[index].APIKey = ""
			bundle.Providers[index].APIKey = nil
		}
	}
	if bundle.CodexAuthJSON != nil {
		*bundle.CodexAuthJSON = ""
		bundle.CodexAuthJSON = nil
	}
	for index := range bundle.CodexAccounts {
		if bundle.CodexAccounts[index].AuthJSON != nil {
			*bundle.CodexAccounts[index].AuthJSON = ""
			bundle.CodexAccounts[index].AuthJSON = nil
		}
	}
	if bundle.GitHub != nil && bundle.GitHub.Token != nil {
		*bundle.GitHub.Token = ""
		bundle.GitHub.Token = nil
	}
	for index := range bundle.GitHubAccounts {
		if bundle.GitHubAccounts[index].Token != nil {
			*bundle.GitHubAccounts[index].Token = ""
			bundle.GitHubAccounts[index].Token = nil
		}
	}
	if bundle.MediaCredentials != nil {
		if bundle.MediaCredentials.VisionCustomAPIKey != nil {
			*bundle.MediaCredentials.VisionCustomAPIKey = ""
			bundle.MediaCredentials.VisionCustomAPIKey = nil
		}
		if bundle.MediaCredentials.ImageGenerationCustomAPIKey != nil {
			*bundle.MediaCredentials.ImageGenerationCustomAPIKey = ""
			bundle.MediaCredentials.ImageGenerationCustomAPIKey = nil
		}
		if bundle.MediaCredentials.ImageUpscaleAPIKey != nil {
			*bundle.MediaCredentials.ImageUpscaleAPIKey = ""
			bundle.MediaCredentials.ImageUpscaleAPIKey = nil
		}
	}
	for index := range bundle.MCPServers {
		for key := range bundle.MCPServers[index].Environment {
			bundle.MCPServers[index].Environment[key] = ""
			delete(bundle.MCPServers[index].Environment, key)
		}
		for key := range bundle.MCPServers[index].Headers {
			bundle.MCPServers[index].Headers[key] = ""
			delete(bundle.MCPServers[index].Headers, key)
		}
	}
	for index := range bundle.Sessions {
		clear(bundle.Sessions[index].Document)
		bundle.Sessions[index].Document = nil
	}
}

func stringPointer(value string) *string { return &value }

func pointerString(value *string) string {
	if value == nil {
		return ""
	}
	return *value
}
