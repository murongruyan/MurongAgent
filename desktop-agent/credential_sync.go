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
	Direction           string              `json:"direction"`
	ImportedProviders   int                 `json:"importedProviders"`
	ImportedAPIKeys     int                 `json:"importedApiKeys"`
	ImportedCodexLogin  bool                `json:"importedCodexLogin"`
	ImportedGitHubToken bool                `json:"importedGitHubToken"`
	AccountEmail        string              `json:"accountEmail,omitempty"`
	ImportedSettings    bool                `json:"importedSettings"`
	ImportedRules       int                 `json:"importedRules"`
	ImportedMemories    int                 `json:"importedMemories"`
	ImportedSkills      int                 `json:"importedSkills"`
	ImportedMCPServers  int                 `json:"importedMcpServers"`
	ImportedWorkflows   int                 `json:"importedWorkflows"`
	DisabledMCPServers  int                 `json:"disabledMcpServers"`
	SkippedWorkflows    int                 `json:"skippedWorkflows"`
	Config              PublicDesktopConfig `json:"config"`
	Codex               CodexRuntimeStatus  `json:"codex"`
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
	ctx, cancel := context.WithTimeout(context.Background(), 150*time.Second)
	defer cancel()
	result, err := app.remote.PushCredentials(ctx, bundle)
	if err != nil {
		return SyncCredentialsOperationResult{}, err
	}
	return SyncCredentialsOperationResult{
		Direction: "to_phone", ImportedProviders: result.ImportedProviders,
		ImportedAPIKeys: result.ImportedAPIKeys, ImportedCodexLogin: result.ImportedCodexLogin,
		ImportedGitHubToken: result.ImportedGitHubToken,
		AccountEmail:        pointerString(result.AccountEmail), ImportedSettings: result.ImportedSettings,
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
	ctx, cancel := context.WithTimeout(context.Background(), 150*time.Second)
	defer cancel()
	bundle, err := app.remote.PullCredentials(ctx, desktopbridge.DeviceSyncOptions{
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
	return SyncCredentialsOperationResult{
		Direction: "from_phone", ImportedProviders: result.ImportedProviders,
		ImportedAPIKeys: result.ImportedAPIKeys, ImportedCodexLogin: result.ImportedCodexLogin,
		ImportedGitHubToken: result.ImportedGitHubToken,
		AccountEmail:        pointerString(result.AccountEmail), ImportedSettings: result.ImportedSettings,
		ImportedRules: result.ImportedRules, ImportedMemories: result.ImportedMemories, ImportedSkills: result.ImportedSkills,
		ImportedMCPServers: result.ImportedMCPServers, ImportedWorkflows: result.ImportedWorkflows,
		DisabledMCPServers: result.DisabledMCPServers, SkippedWorkflows: result.SkippedWorkflows,
		Config: config, Codex: app.codex.Status(),
	}, nil
}

func (app *DesktopAgentApp) validateCredentialSyncRequest(request SyncCredentialsRequest) error {
	if !request.IncludeProviderCredentials && !request.IncludeCodexLogin && !request.IncludeGitHubCredentials && !request.IncludeAgentSettings &&
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
		SchemaVersion: 4, SourcePlatform: desktopSourcePlatform(), GeneratedAt: time.Now().UnixMilli(),
		Providers: []desktopbridge.SyncedProviderCredential{},
	}
	active := findProviderProfile(config.ProviderProfiles, config.ActiveProviderProfileID)
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
			if profile.ProviderID == providerCodex {
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
		if err := ensurePrivateCodexHome(app.codex.codexHome); err != nil {
			return bundle, err
		}
		auth, err := readCodexAuthJSON(app.codex.codexHome)
		if err == nil {
			value := string(auth)
			clearBytes(auth)
			bundle.CodexAuthJSON = &value
		} else if !errors.Is(err, os.ErrNotExist) {
			return bundle, err
		}
	}
	if request.IncludeGitHubCredentials {
		github, err := app.workflows.store.runtimeGitHub()
		if err != nil {
			return bundle, err
		}
		synced := desktopbridge.SyncedGitHubCredential{
			APIBaseURL:  github.APIBaseURL,
			ViewerLogin: app.workflows.State().GitHub.Viewer,
		}
		if strings.TrimSpace(github.Token) != "" {
			value := github.Token
			synced.Token = &value
		}
		bundle.GitHub = &synced
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
	if bundle.GitHub != nil {
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
	if bundle.CodexAuthJSON != nil {
		email, err := app.replaceCodexAuthAndVerify(ctx, []byte(*bundle.CodexAuthJSON))
		if err != nil {
			return result, app.rollbackDeviceSync(previousConfig, previousWorkflows, err)
		}
		result.ImportedCodexLogin = true
		result.AccountEmail = stringPointer(email)
	}
	if len(bundle.MCPServers) > 0 {
		if runtimeConfigs, runtimeErr := app.store.mcpRuntimeConfigs(); runtimeErr == nil {
			app.mcp.ConnectAll(context.Background(), runtimeConfigs, true)
		}
	}
	if bundle.GitHub != nil && result.ImportedGitHubToken {
		app.workflows.mu.Lock()
		app.workflows.viewer = strings.TrimSpace(bundle.GitHub.ViewerLogin)
		app.workflows.mu.Unlock()
		app.workflows.emitChanged()
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
		if profile.ProviderID != providerCodex {
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
	return nil
}

func importSyncedGitHubCredential(
	current savedWorkflowStoreBackupSnapshot,
	incoming desktopbridge.SyncedGitHubCredential,
) (savedWorkflowStoreBackupSnapshot, bool, error) {
	document := cloneSavedWorkflowDocument(current.Document)
	baseURL := normalizeGitHubAPIBaseURL(incoming.APIBaseURL)
	if err := validateGitHubAPIBaseURL(baseURL); err != nil {
		return current, false, err
	}
	document.GitHub.APIBaseURL = baseURL
	importedToken := false
	if incoming.Token != nil && strings.TrimSpace(*incoming.Token) != "" {
		plain := []byte(strings.TrimSpace(*incoming.Token))
		protected, err := protectSecret(plain)
		clearBytes(plain)
		if err != nil {
			return current, false, fmt.Errorf("无法保护同步的 GitHub Token：%w", err)
		}
		document.GitHub.ProtectedToken = protected
		importedToken = true
	}
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
	if (bundle.SchemaVersion < 1 || bundle.SchemaVersion > 4) || (bundle.SourcePlatform != "android" && !isDesktopSourcePlatform(bundle.SourcePlatform)) {
		return errors.New("设备同步格式或来源无效")
	}
	now := time.Now().UnixMilli()
	if bundle.GeneratedAt <= 0 || bundle.GeneratedAt < now-5*time.Minute.Milliseconds() || bundle.GeneratedAt > now+time.Minute.Milliseconds() || len(bundle.Providers) > 64 {
		return errors.New("凭据同步时间或模型连接数量无效")
	}
	for _, profile := range bundle.Providers {
		providerID := normalizeSyncedProviderID(profile.ProviderID)
		if providerID != providerDeepSeek && providerID != providerOpenAI && providerID != providerClaude {
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
		if err := validateBaseURL(profile.BaseURL); err != nil {
			return err
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
	case "", "low", "medium", "high", "xhigh", "max":
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
	default:
		return strings.ToLower(strings.TrimSpace(value))
	}
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
	if bundle.GitHub != nil && bundle.GitHub.Token != nil {
		*bundle.GitHub.Token = ""
		bundle.GitHub.Token = nil
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
}

func stringPointer(value string) *string { return &value }

func pointerString(value *string) string {
	if value == nil {
		return ""
	}
	return *value
}
