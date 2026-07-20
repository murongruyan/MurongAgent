package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"net/url"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"
)

const desktopConfigSchemaVersion = 10

const (
	maxProjectSubagentTemplates = 24
	maxSubagentTemplateMatchers = 24
)

const (
	providerDeepSeek = "deepseek"
	providerOpenAI   = "openai-compatible"
	providerClaude   = "claude"
	providerCodex    = "codex-chatgpt"
)

var defaultEnabledBuiltinTools = []string{
	"shell", "file", "code_edit", "code_search", "web_fetch", "web_search",
	"subagent_launch", "explore", "research", "review", "security_review", "github", "mcp",
}

var supportedBuiltinTools = []string{
	"shell", "file", "code_edit", "code_search", "web_fetch", "web_search",
	"subagent_launch", "explore", "research", "review", "security_review", "github", "mcp", "android", "windows",
}

var defaultEnabledFileOperations = []string{"read", "list", "exists", "write", "delete", "chmod"}

const defaultDesktopSystemPrompt = `You are Murong Agent, a coding agent running as a native desktop application.
Match the user's language unless source text must remain verbatim.
Use the available tools to inspect the real environment, make requested changes, and verify the outcome.
For coding and debugging, prefer primary source files and focused reads/searches; ignore generated artifacts unless relevant.
When prior tasks may contain relevant decisions or evidence, use session_history_search to retrieve a focused excerpt instead of guessing or dumping all history.
Keep progress updates brief and meaningful. Lead the final response with the outcome, then include only useful evidence, risks, or next steps.
Respect approval and workspace boundaries. Never claim a change or verification that did not happen.`

type desktopStore struct {
	mu           sync.Mutex
	configPath   string
	sessionsPath string
	config       desktopConfig
	sessions     map[string]*ChatSession
}

func newDesktopStore() (*desktopStore, error) {
	directory := strings.TrimSpace(os.Getenv("MURONG_DESKTOP_DATA_DIR"))
	base := strings.TrimSpace(os.Getenv("LOCALAPPDATA"))
	if directory == "" && base == "" {
		var err error
		base, err = os.UserConfigDir()
		if err != nil {
			return nil, err
		}
	}
	if directory == "" {
		directory = filepath.Join(base, "Murong")
	}
	directory, err := filepath.Abs(directory)
	if err != nil {
		return nil, err
	}
	store := &desktopStore{
		configPath:   filepath.Join(directory, "desktop-agent.json"),
		sessionsPath: filepath.Join(directory, "desktop-agent-sessions.json"),
		config:       defaultDesktopConfig(),
		sessions:     map[string]*ChatSession{},
	}
	if err := store.load(); err != nil {
		return nil, err
	}
	return store, nil
}

func defaultDesktopConfig() desktopConfig {
	profile := defaultProviderProfile(providerOpenAI)
	return desktopConfig{
		SchemaVersion:            desktopConfigSchemaVersion,
		BaseURL:                  "https://api.openai.com/v1",
		Model:                    "gpt-5.6-sol",
		ApprovalMode:             approvalAskAll,
		MaxToolIterations:        999,
		SystemPrompt:             defaultDesktopSystemPrompt,
		ResponseVerbosity:        "BALANCED",
		Temperature:              0.7,
		MaxTokens:                8192,
		EnableMultimodalMessages: true,
		GlobalRules:              []GlobalRule{},
		GlobalMemories:           []GlobalMemory{},
		GlobalSkills:             []GlobalSkill{},
		ProjectKnowledge:         map[string]KnowledgeLibrary{},
		ActiveProviderProfileID:  profile.ID,
		ProviderProfiles:         []ProviderProfile{profile},
		EnabledBuiltinTools:      append([]string{}, defaultEnabledBuiltinTools...),
		EnabledFileOperations:    append([]string{}, defaultEnabledFileOperations...),
		ProjectToolPreferences:   map[string]ToolPreferences{},
		RecentProjects:           []RecentProjectRecord{},
		MCPServers:               []MCPServerConfig{},
	}
}

func (store *desktopStore) load() error {
	store.mu.Lock()
	defer store.mu.Unlock()
	if data, err := os.ReadFile(store.configPath); err == nil {
		var loaded desktopConfig
		if err := json.Unmarshal(data, &loaded); err != nil {
			return fmt.Errorf("桌面 Agent 配置损坏：%w", err)
		}
		store.config = normalizeDesktopConfig(loaded)
	} else if !errors.Is(err, os.ErrNotExist) {
		return err
	}
	if data, err := os.ReadFile(store.sessionsPath); err == nil {
		var sessions []*ChatSession
		if err := json.Unmarshal(data, &sessions); err != nil {
			return fmt.Errorf("桌面 Agent 会话损坏：%w", err)
		}
		restoredAt := time.Now().UnixMilli()
		needsSave := false
		for _, session := range sessions {
			if session != nil && session.ID != "" {
				session.Usage = normalizeSessionUsage(session.Usage)
				session.Compression = normalizeSessionCompression(session.Compression, session.Messages)
				normalizedHandoff, handoffChanged := normalizeSessionExecutionHandoff(session.ExecutionHandoff, len(session.Messages))
				session.ExecutionHandoff = normalizedHandoff
				normalizedJobs, changed := normalizeRestoredSubagentJobs(session.BackgroundSubagentJobs, restoredAt)
				session.BackgroundSubagentJobs = normalizedJobs
				needsSave = needsSave || changed || handoffChanged
				store.sessions[session.ID] = cloneSession(session)
			}
		}
		if needsSave {
			if err := store.saveSessionsLocked(); err != nil {
				return fmt.Errorf("无法保存中断的后台子代理状态：%w", err)
			}
		}
	} else if !errors.Is(err, os.ErrNotExist) {
		return err
	}
	return nil
}

func normalizeDesktopConfig(config desktopConfig) desktopConfig {
	sourceSchemaVersion := config.SchemaVersion
	config.SchemaVersion = desktopConfigSchemaVersion
	config.BaseURL = strings.TrimRight(strings.TrimSpace(config.BaseURL), "/")
	if config.BaseURL == "" {
		config.BaseURL = "https://api.openai.com/v1"
	}
	config.Model = strings.TrimSpace(config.Model)
	if config.Model == "" {
		config.Model = "gpt-5.6-sol"
	}
	switch config.ApprovalMode {
	case approvalReadOnly, approvalAskAll, approvalAllowlist, approvalYolo:
	default:
		config.ApprovalMode = approvalAskAll
	}
	if config.MaxToolIterations < 1 || config.MaxToolIterations > 999 {
		config.MaxToolIterations = 999
	}
	config.Allowlist = normalizeAllowlist(config.Allowlist)
	if strings.TrimSpace(config.SystemPrompt) == "" {
		config.SystemPrompt = defaultDesktopSystemPrompt
	}
	switch config.ResponseVerbosity {
	case "CONCISE", "BALANCED", "DETAILED":
	default:
		config.ResponseVerbosity = "BALANCED"
	}
	if sourceSchemaVersion < 7 {
		config.Temperature = 0.7
		config.MaxTokens = 8192
	}
	if sourceSchemaVersion < 8 {
		config.EnableMultimodalMessages = true
	}
	if math.IsNaN(config.Temperature) || math.IsInf(config.Temperature, 0) || config.Temperature < 0 || config.Temperature > 2 {
		config.Temperature = 0.7
	}
	if config.MaxTokens < 1 || config.MaxTokens > 128_000 {
		config.MaxTokens = 8192
	}
	config.PlannerModel = normalizeExecutionProfileModel(config.PlannerModel)
	config.PlannerReasoningEffort = normalizeExecutionProfileReasoning(config.PlannerReasoningEffort)
	config.SubagentModel = normalizeExecutionProfileModel(config.SubagentModel)
	config.SubagentReasoningEffort = normalizeExecutionProfileReasoning(config.SubagentReasoningEffort)
	config.GlobalRules = normalizeRules(config.GlobalRules)
	config.GlobalMemories = normalizeMemories(config.GlobalMemories)
	config.GlobalSkills = normalizeSkills(config.GlobalSkills)
	config.ProjectKnowledge = normalizeProjectKnowledge(config.ProjectKnowledge)
	config.EnabledBuiltinTools = normalizeToolChoices(config.EnabledBuiltinTools, supportedBuiltinTools, defaultEnabledBuiltinTools)
	config.EnabledFileOperations = normalizeToolChoices(config.EnabledFileOperations, defaultEnabledFileOperations, defaultEnabledFileOperations)
	config.ProjectToolPreferences = normalizeProjectToolPreferences(config.ProjectToolPreferences)
	config.RecentProjects = normalizeRecentProjectRecords(config.RecentProjects)
	config.MCPServers = normalizeMCPServerConfigs(config.MCPServers)
	config.ProviderProfiles = normalizeProviderProfiles(config.ProviderProfiles, config.BaseURL, config.Model, config.ProtectedAPIKey)
	if findProviderProfile(config.ProviderProfiles, config.ActiveProviderProfileID) == nil {
		config.ActiveProviderProfileID = config.ProviderProfiles[0].ID
	}
	active := findProviderProfile(config.ProviderProfiles, config.ActiveProviderProfileID)
	config.BaseURL = active.BaseURL
	config.Model = active.Model
	config.ProtectedAPIKey = active.ProtectedAPIKey
	return config
}

func defaultProviderProfile(providerID string) ProviderProfile {
	switch providerID {
	case providerDeepSeek:
		return ProviderProfile{ID: newID("provider"), ProviderID: providerDeepSeek, Name: "DeepSeek", BaseURL: "https://api.deepseek.com/v1", Model: "deepseek-v4-flash", ReasoningEffort: "high", APIMode: "chat-completions"}
	case providerClaude:
		return ProviderProfile{ID: newID("provider"), ProviderID: providerClaude, Name: "Claude", BaseURL: "https://api.anthropic.com", Model: "claude-fable-5", ReasoningEffort: "high", APIMode: "messages"}
	case providerCodex:
		return ProviderProfile{ID: newID("provider"), ProviderID: providerCodex, Name: "Codex / ChatGPT", ReasoningEffort: "high", APIMode: "app-server"}
	default:
		return ProviderProfile{ID: newID("provider"), ProviderID: providerOpenAI, Name: "OpenAI-compatible", BaseURL: "https://api.openai.com/v1", Model: "gpt-5.6-sol", ReasoningEffort: "high", APIMode: "auto"}
	}
}

func normalizeProviderProfiles(values []ProviderProfile, legacyBaseURL, legacyModel, legacyProtectedKey string) []ProviderProfile {
	if len(values) == 0 {
		profile := defaultProviderProfile(providerOpenAI)
		profile.ID = "provider_legacy"
		profile.Name = "原桌面连接"
		profile.BaseURL = legacyBaseURL
		profile.Model = legacyModel
		profile.ProtectedAPIKey = legacyProtectedKey
		values = []ProviderProfile{profile}
	}
	result := make([]ProviderProfile, 0, len(values))
	seen := map[string]bool{}
	for _, value := range values {
		value.ID = strings.TrimSpace(value.ID)
		if value.ID == "" {
			value.ID = newID("provider")
		}
		if seen[value.ID] {
			continue
		}
		seen[value.ID] = true
		switch value.ProviderID {
		case providerDeepSeek, providerOpenAI, providerClaude, providerCodex:
		default:
			value.ProviderID = providerOpenAI
		}
		defaults := defaultProviderProfile(value.ProviderID)
		value.Name = truncateRunes(strings.TrimSpace(value.Name), 80)
		if value.Name == "" {
			value.Name = defaults.Name
		}
		value.BaseURL = strings.TrimRight(strings.TrimSpace(value.BaseURL), "/")
		if value.ProviderID == providerCodex {
			value.BaseURL = ""
			value.ProtectedAPIKey = ""
		} else if value.BaseURL == "" {
			value.BaseURL = defaults.BaseURL
		}
		value.Model = strings.TrimSpace(value.Model)
		if value.Model == "" && value.ProviderID != providerCodex {
			value.Model = defaults.Model
		}
		value.ExecutablePath = strings.TrimSpace(value.ExecutablePath)
		value.ReasoningEffort = strings.ToLower(strings.TrimSpace(value.ReasoningEffort))
		switch value.ReasoningEffort {
		case "", "low", "medium", "high", "xhigh", "max":
		default:
			value.ReasoningEffort = defaults.ReasoningEffort
		}
		value.APIMode = strings.ToLower(strings.TrimSpace(value.APIMode))
		switch value.ProviderID {
		case providerCodex:
			value.APIMode = "app-server"
		case providerClaude:
			value.APIMode = "messages"
		case providerDeepSeek:
			value.APIMode = "chat-completions"
		default:
			switch value.APIMode {
			case "auto", "chat-completions", "responses":
			default:
				value.APIMode = "auto"
			}
		}
		if value.ContextWindowTokens != 0 && value.ContextWindowTokens < 4096 {
			value.ContextWindowTokens = 4096
		}
		if value.ContextWindowTokens > 2_000_000 {
			value.ContextWindowTokens = 2_000_000
		}
		result = append(result, value)
	}
	if len(result) == 0 {
		result = []ProviderProfile{defaultProviderProfile(providerOpenAI)}
	}
	return result
}

func findProviderProfile(values []ProviderProfile, id string) *ProviderProfile {
	for index := range values {
		if values[index].ID == strings.TrimSpace(id) {
			return &values[index]
		}
	}
	return nil
}

func normalizeAllowlist(values []string) []string {
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

func normalizeToolChoices(values, supported, defaults []string) []string {
	if values == nil {
		return append([]string{}, defaults...)
	}
	allowed := make(map[string]bool, len(supported))
	for _, value := range supported {
		allowed[value] = true
	}
	seen := map[string]bool{}
	result := make([]string, 0, len(values))
	for _, value := range values {
		value = strings.TrimSpace(value)
		if !allowed[value] || seen[value] {
			continue
		}
		seen[value] = true
		result = append(result, value)
	}
	return result
}

func normalizeToolPreferences(value ToolPreferences) ToolPreferences {
	switch value.ApprovalMode {
	case approvalReadOnly, approvalAskAll, approvalAllowlist, approvalYolo:
	default:
		value.ApprovalMode = approvalAskAll
	}
	value.Allowlist = normalizeAllowlist(value.Allowlist)
	value.EnabledBuiltinTools = normalizeToolChoices(value.EnabledBuiltinTools, supportedBuiltinTools, defaultEnabledBuiltinTools)
	value.EnabledFileOperations = normalizeToolChoices(value.EnabledFileOperations, defaultEnabledFileOperations, defaultEnabledFileOperations)
	value.SubagentTemplates = normalizeProjectSubagentTemplates(value.SubagentTemplates)
	return value
}

func normalizeProjectSubagentTemplates(values []ProjectSubagentTemplate) []ProjectSubagentTemplate {
	result := make([]ProjectSubagentTemplate, 0, min(len(values), maxProjectSubagentTemplates))
	seenIDs := map[string]bool{}
	for _, value := range values {
		value.ID = strings.TrimSpace(value.ID)
		value.Title = strings.TrimSpace(value.Title)
		if value.ID == "" || value.Title == "" || seenIDs[value.ID] || len(result) >= maxProjectSubagentTemplates {
			continue
		}
		seenIDs[value.ID] = true
		value.Description = strings.TrimSpace(value.Description)
		value.PreferredModel = strings.TrimSpace(value.PreferredModel)
		value.PreferredReasoningEffort = strings.ToLower(strings.TrimSpace(value.PreferredReasoningEffort))
		matchers := make([]string, 0, min(len(value.GoalMatchers), maxSubagentTemplateMatchers))
		seenMatchers := map[string]bool{}
		for _, matcher := range value.GoalMatchers {
			matcher = strings.TrimSpace(matcher)
			key := strings.ToLower(matcher)
			if matcher == "" || seenMatchers[key] || len(matchers) >= maxSubagentTemplateMatchers {
				continue
			}
			seenMatchers[key] = true
			matchers = append(matchers, matcher)
		}
		value.GoalMatchers = matchers
		result = append(result, value)
	}
	return result
}

func validateProjectSubagentTemplates(values []ProjectSubagentTemplate) error {
	if len(values) > maxProjectSubagentTemplates {
		return fmt.Errorf("每个项目最多配置 %d 个子代理模板", maxProjectSubagentTemplates)
	}
	seenIDs := map[string]bool{}
	for _, value := range values {
		id := strings.TrimSpace(value.ID)
		if id == "" || len([]rune(id)) > 80 || strings.ContainsAny(id, "\\/\x00\r\n\t") {
			return errors.New("项目子代理模板 ID 为空、过长或包含非法字符")
		}
		if seenIDs[id] {
			return fmt.Errorf("项目子代理模板 ID 重复：%s", id)
		}
		seenIDs[id] = true
		if title := strings.TrimSpace(value.Title); title == "" || len([]rune(title)) > 80 {
			return fmt.Errorf("项目子代理模板 %q 的名称为空或超过 80 字符", id)
		}
		if len([]rune(strings.TrimSpace(value.Description))) > 500 {
			return fmt.Errorf("项目子代理模板 %q 的说明超过 500 字符", id)
		}
		if len(value.GoalMatchers) > maxSubagentTemplateMatchers {
			return fmt.Errorf("项目子代理模板 %q 的自动匹配词超过 %d 个", id, maxSubagentTemplateMatchers)
		}
		for _, matcher := range value.GoalMatchers {
			if matcher = strings.TrimSpace(matcher); matcher == "" || len([]rune(matcher)) > 80 {
				return fmt.Errorf("项目子代理模板 %q 包含空白或过长匹配词", id)
			}
		}
		if len([]rune(strings.TrimSpace(value.PreferredModel))) > 160 {
			return fmt.Errorf("项目子代理模板 %q 的模型名称超过 160 字符", id)
		}
		if effort := strings.ToLower(strings.TrimSpace(value.PreferredReasoningEffort)); effort != "" && effort != "low" && effort != "medium" && effort != "high" && effort != "xhigh" && effort != "max" {
			return fmt.Errorf("项目子代理模板 %q 的推理强度无效", id)
		}
	}
	return nil
}

func normalizeProjectToolPreferences(values map[string]ToolPreferences) map[string]ToolPreferences {
	result := map[string]ToolPreferences{}
	for key, value := range values {
		key = strings.TrimSpace(key)
		if key == "" {
			continue
		}
		result[key] = normalizeToolPreferences(value)
	}
	return result
}

func (store *desktopStore) publicConfig() PublicDesktopConfig {
	store.mu.Lock()
	defer store.mu.Unlock()
	return publicConfig(store.config)
}

func publicConfig(config desktopConfig) PublicDesktopConfig {
	profiles := make([]PublicProviderProfile, 0, len(config.ProviderProfiles))
	for _, profile := range config.ProviderProfiles {
		profiles = append(profiles, PublicProviderProfile{
			ID: profile.ID, ProviderID: profile.ProviderID, Name: profile.Name, BaseURL: profile.BaseURL, Model: profile.Model,
			ReasoningEffort: profile.ReasoningEffort, APIMode: profile.APIMode, ContextWindowTokens: profile.ContextWindowTokens,
			ExecutablePath: profile.ExecutablePath, HasAPIKey: profile.ProviderID != providerCodex && profile.ProtectedAPIKey != "",
		})
	}
	return PublicDesktopConfig{
		ProjectPath:              config.ProjectPath,
		BaseURL:                  config.BaseURL,
		Model:                    config.Model,
		HasAPIKey:                config.ProtectedAPIKey != "",
		ApprovalMode:             config.ApprovalMode,
		Allowlist:                append([]string(nil), config.Allowlist...),
		MaxToolIterations:        config.MaxToolIterations,
		SystemPrompt:             config.SystemPrompt,
		ResponseVerbosity:        config.ResponseVerbosity,
		Temperature:              config.Temperature,
		MaxTokens:                config.MaxTokens,
		EnableMultimodalMessages: config.EnableMultimodalMessages,
		PlannerProfileEnabled:    config.PlannerProfileEnabled,
		PlannerModel:             config.PlannerModel,
		PlannerReasoningEffort:   config.PlannerReasoningEffort,
		SubagentProfileEnabled:   config.SubagentProfileEnabled,
		SubagentModel:            config.SubagentModel,
		SubagentReasoningEffort:  config.SubagentReasoningEffort,
		ActiveProviderProfileID:  config.ActiveProviderProfileID,
		ProviderProfiles:         profiles,
		EnabledBuiltinTools:      append([]string{}, config.EnabledBuiltinTools...),
		EnabledFileOperations:    append([]string{}, config.EnabledFileOperations...),
		RecentProjects:           publicRecentProjects(config.RecentProjects),
	}
}

func (store *desktopStore) rawConfig() desktopConfig {
	store.mu.Lock()
	defer store.mu.Unlock()
	copy := store.config
	copy.Allowlist = append([]string(nil), store.config.Allowlist...)
	copy.GlobalRules = append([]GlobalRule(nil), store.config.GlobalRules...)
	copy.GlobalMemories = append([]GlobalMemory(nil), store.config.GlobalMemories...)
	copy.GlobalSkills = cloneSkills(store.config.GlobalSkills)
	copy.ProjectKnowledge = cloneProjectKnowledge(store.config.ProjectKnowledge)
	copy.ProviderProfiles = append([]ProviderProfile{}, store.config.ProviderProfiles...)
	copy.EnabledBuiltinTools = append([]string{}, store.config.EnabledBuiltinTools...)
	copy.EnabledFileOperations = append([]string{}, store.config.EnabledFileOperations...)
	copy.ProjectToolPreferences = cloneProjectToolPreferences(store.config.ProjectToolPreferences)
	copy.RecentProjects = append([]RecentProjectRecord{}, store.config.RecentProjects...)
	copy.MCPServers = cloneMCPServerConfigs(store.config.MCPServers)
	return copy
}

func (store *desktopStore) saveSettings(request SaveSettingsRequest) (PublicDesktopConfig, error) {
	store.mu.Lock()
	defer store.mu.Unlock()
	updated := store.config
	updated.ProjectPath = strings.TrimSpace(request.ProjectPath)
	updated.ApprovalMode = request.ApprovalMode
	updated.Allowlist = request.Allowlist
	updated.MaxToolIterations = request.MaxToolIterations
	updated.SystemPrompt = strings.TrimSpace(request.SystemPrompt)
	updated.ResponseVerbosity = request.ResponseVerbosity
	updated.Temperature = request.Temperature
	updated.MaxTokens = request.MaxTokens
	updated.EnableMultimodalMessages = request.EnableMultimodalMessages
	updated.PlannerProfileEnabled = request.PlannerProfileEnabled
	updated.PlannerModel = request.PlannerModel
	updated.PlannerReasoningEffort = request.PlannerReasoningEffort
	updated.SubagentProfileEnabled = request.SubagentProfileEnabled
	updated.SubagentModel = request.SubagentModel
	updated.SubagentReasoningEffort = request.SubagentReasoningEffort
	updated.EnabledBuiltinTools = request.EnabledBuiltinTools
	updated.EnabledFileOperations = request.EnabledFileOperations
	if len(request.ProviderProfiles) > 0 {
		profiles, err := mergeProviderProfileSecrets(store.config.ProviderProfiles, request.ProviderProfiles)
		if err != nil {
			return PublicDesktopConfig{}, err
		}
		updated.ProviderProfiles = profiles
		updated.ActiveProviderProfileID = strings.TrimSpace(request.ActiveProviderProfileID)
	} else {
		activeID := store.config.ActiveProviderProfileID
		updated.ProviderProfiles = append([]ProviderProfile{}, store.config.ProviderProfiles...)
		active := findProviderProfile(updated.ProviderProfiles, activeID)
		if active == nil {
			return PublicDesktopConfig{}, errors.New("当前模型连接不存在")
		}
		active.BaseURL = request.BaseURL
		active.Model = request.Model
		if request.ClearAPIKey {
			active.ProtectedAPIKey = ""
		} else if key := strings.TrimSpace(request.APIKey); key != "" {
			protected, err := protectSecret([]byte(key))
			if err != nil {
				return PublicDesktopConfig{}, err
			}
			active.ProtectedAPIKey = protected
		}
	}
	updated = normalizeDesktopConfig(updated)
	for _, profile := range updated.ProviderProfiles {
		if profile.ProviderID != providerCodex {
			if err := validateBaseURL(profile.BaseURL); err != nil {
				return PublicDesktopConfig{}, fmt.Errorf("模型连接 %q：%w", profile.Name, err)
			}
		}
	}
	if updated.ProjectPath != "" {
		projectPath, err := normalizeExistingProjectPath(updated.ProjectPath)
		if err != nil {
			return PublicDesktopConfig{}, errors.New("项目目录不存在或不是目录")
		}
		updated.ProjectPath = projectPath
		updated.RecentProjects = touchRecentProject(updated.RecentProjects, projectPath, time.Now().UnixMilli())
	}
	if request.SaveProjectToolPreferences && updated.ProjectPath != "" {
		updated.ProjectToolPreferences = cloneProjectToolPreferences(updated.ProjectToolPreferences)
		key := projectKnowledgeKey(updated.ProjectPath)
		if err := validateProjectSubagentTemplates(request.ProjectTools.SubagentTemplates); err != nil {
			return PublicDesktopConfig{}, err
		}
		preferences := cloneToolPreferences(request.ProjectTools)
		preferences.InheritGlobal = request.UseGlobalProjectTools
		preferences = normalizeToolPreferences(preferences)
		if request.UseGlobalProjectTools && len(preferences.SubagentTemplates) == 0 {
			delete(updated.ProjectToolPreferences, key)
		} else {
			updated.ProjectToolPreferences[key] = preferences
		}
	}
	if err := writeJSONAtomic(store.configPath, updated); err != nil {
		return PublicDesktopConfig{}, err
	}
	store.config = updated
	return publicConfig(updated), nil
}

func mergeProviderProfileSecrets(existing []ProviderProfile, requests []SaveProviderProfile) ([]ProviderProfile, error) {
	protectedByID := make(map[string]string, len(existing))
	for _, profile := range existing {
		protectedByID[profile.ID] = profile.ProtectedAPIKey
	}
	result := make([]ProviderProfile, 0, len(requests))
	seen := make(map[string]bool, len(requests))
	for _, request := range requests {
		request.ID = strings.TrimSpace(request.ID)
		if request.ID == "" {
			return nil, errors.New("模型连接缺少 ID")
		}
		if seen[request.ID] {
			return nil, fmt.Errorf("模型连接 ID 重复：%s", request.ID)
		}
		seen[request.ID] = true
		profile := ProviderProfile{
			ID: request.ID, ProviderID: request.ProviderID, Name: request.Name, BaseURL: request.BaseURL, Model: request.Model,
			ReasoningEffort: request.ReasoningEffort, APIMode: request.APIMode, ContextWindowTokens: request.ContextWindowTokens,
			ExecutablePath: request.ExecutablePath, ProtectedAPIKey: protectedByID[request.ID],
		}
		if request.ClearAPIKey {
			profile.ProtectedAPIKey = ""
		} else if key := strings.TrimSpace(request.APIKey); key != "" {
			protected, err := protectSecret([]byte(key))
			if err != nil {
				return nil, err
			}
			profile.ProtectedAPIKey = protected
		}
		result = append(result, profile)
	}
	return result, nil
}

func (store *desktopStore) knowledgeLibrary() KnowledgeLibrary {
	store.mu.Lock()
	defer store.mu.Unlock()
	return knowledgeFromConfig(store.config)
}

func (store *desktopStore) projectKnowledge() ProjectKnowledgeSnapshot {
	store.mu.Lock()
	defer store.mu.Unlock()
	return projectKnowledgeFromConfig(store.config)
}

func (store *desktopStore) projectToolPreferences() ProjectToolPreferencesSnapshot {
	store.mu.Lock()
	defer store.mu.Unlock()
	return projectToolPreferencesFromConfig(store.config)
}

func projectToolPreferencesFromConfig(config desktopConfig) ProjectToolPreferencesSnapshot {
	path := strings.TrimSpace(config.ProjectPath)
	global := globalToolPreferences(config)
	if path == "" {
		return ProjectToolPreferencesSnapshot{UsesGlobal: true, Preferences: global}
	}
	preferences, exists := config.ProjectToolPreferences[projectKnowledgeKey(path)]
	if !exists {
		preferences = global
	} else if preferences.InheritGlobal {
		templates := cloneProjectSubagentTemplates(preferences.SubagentTemplates)
		preferences = global
		preferences.InheritGlobal = true
		preferences.SubagentTemplates = templates
	}
	return ProjectToolPreferencesSnapshot{
		ProjectPath: path, ProjectLabel: filepath.Base(path), HasProject: true, UsesGlobal: !exists || preferences.InheritGlobal,
		Preferences: cloneToolPreferences(preferences),
	}
}

func globalToolPreferences(config desktopConfig) ToolPreferences {
	return ToolPreferences{
		ApprovalMode: config.ApprovalMode, Allowlist: append([]string{}, config.Allowlist...),
		EnabledBuiltinTools:   append([]string{}, config.EnabledBuiltinTools...),
		EnabledFileOperations: append([]string{}, config.EnabledFileOperations...),
	}
}

func resolvedToolConfig(config desktopConfig) desktopConfig {
	if path := strings.TrimSpace(config.ProjectPath); path != "" {
		if preferences, ok := config.ProjectToolPreferences[projectKnowledgeKey(path)]; ok {
			if !preferences.InheritGlobal {
				config.ApprovalMode = preferences.ApprovalMode
				config.Allowlist = append([]string{}, preferences.Allowlist...)
				config.EnabledBuiltinTools = append([]string{}, preferences.EnabledBuiltinTools...)
				config.EnabledFileOperations = append([]string{}, preferences.EnabledFileOperations...)
			}
		}
	}
	return config
}

func cloneProjectToolPreferences(values map[string]ToolPreferences) map[string]ToolPreferences {
	result := make(map[string]ToolPreferences, len(values))
	for key, value := range values {
		result[key] = cloneToolPreferences(value)
	}
	return result
}

func cloneToolPreferences(value ToolPreferences) ToolPreferences {
	value.Allowlist = append([]string{}, value.Allowlist...)
	value.EnabledBuiltinTools = append([]string{}, value.EnabledBuiltinTools...)
	value.EnabledFileOperations = append([]string{}, value.EnabledFileOperations...)
	value.SubagentTemplates = cloneProjectSubagentTemplates(value.SubagentTemplates)
	return value
}

func cloneProjectSubagentTemplates(values []ProjectSubagentTemplate) []ProjectSubagentTemplate {
	result := make([]ProjectSubagentTemplate, len(values))
	for index, value := range values {
		value.GoalMatchers = append([]string{}, value.GoalMatchers...)
		result[index] = value
	}
	return result
}

func projectSubagentTemplates(config desktopConfig) []ProjectSubagentTemplate {
	path := strings.TrimSpace(config.ProjectPath)
	if path == "" {
		return nil
	}
	preferences, ok := config.ProjectToolPreferences[projectKnowledgeKey(path)]
	if !ok {
		return nil
	}
	return cloneProjectSubagentTemplates(preferences.SubagentTemplates)
}

func projectKnowledgeFromConfig(config desktopConfig) ProjectKnowledgeSnapshot {
	path := strings.TrimSpace(config.ProjectPath)
	if path == "" {
		return ProjectKnowledgeSnapshot{Library: emptyKnowledgeLibrary()}
	}
	library := config.ProjectKnowledge[projectKnowledgeKey(path)]
	return ProjectKnowledgeSnapshot{
		ProjectPath: path, ProjectLabel: filepath.Base(path), HasProject: true,
		Library: cloneKnowledgeLibrary(library),
	}
}

func knowledgeFromConfig(config desktopConfig) KnowledgeLibrary {
	return cloneKnowledgeLibrary(KnowledgeLibrary{Rules: config.GlobalRules, Memories: config.GlobalMemories, Skills: config.GlobalSkills})
}

func (store *desktopStore) saveKnowledgeLibrary(library KnowledgeLibrary) (KnowledgeLibrary, error) {
	store.mu.Lock()
	defer store.mu.Unlock()
	updated := store.config
	updated.GlobalRules = normalizeRules(library.Rules)
	updated.GlobalMemories = normalizeMemories(library.Memories)
	updated.GlobalSkills = normalizeSkills(library.Skills)
	if err := writeJSONAtomic(store.configPath, updated); err != nil {
		return KnowledgeLibrary{}, err
	}
	store.config = updated
	return knowledgeFromConfig(updated), nil
}

func (store *desktopStore) saveProjectKnowledgeLibrary(library KnowledgeLibrary) (ProjectKnowledgeSnapshot, error) {
	store.mu.Lock()
	defer store.mu.Unlock()
	path := strings.TrimSpace(store.config.ProjectPath)
	if path == "" {
		return ProjectKnowledgeSnapshot{}, errors.New("请先选择一个项目")
	}
	updated := store.config
	updated.ProjectKnowledge = cloneProjectKnowledge(store.config.ProjectKnowledge)
	updated.ProjectKnowledge[projectKnowledgeKey(path)] = normalizeKnowledgeLibrary(library)
	if err := writeJSONAtomic(store.configPath, updated); err != nil {
		return ProjectKnowledgeSnapshot{}, err
	}
	store.config = updated
	return projectKnowledgeFromConfig(updated), nil
}

func (store *desktopStore) mutateKnowledge(update func(*desktopConfig) error) (KnowledgeLibrary, error) {
	store.mu.Lock()
	defer store.mu.Unlock()
	updated := store.config
	updated.GlobalRules = append([]GlobalRule(nil), store.config.GlobalRules...)
	updated.GlobalMemories = append([]GlobalMemory(nil), store.config.GlobalMemories...)
	updated.GlobalSkills = cloneSkills(store.config.GlobalSkills)
	if err := update(&updated); err != nil {
		return KnowledgeLibrary{}, err
	}
	updated.GlobalRules = normalizeRules(updated.GlobalRules)
	updated.GlobalMemories = normalizeMemories(updated.GlobalMemories)
	updated.GlobalSkills = normalizeSkills(updated.GlobalSkills)
	if err := writeJSONAtomic(store.configPath, updated); err != nil {
		return KnowledgeLibrary{}, err
	}
	store.config = updated
	return knowledgeFromConfig(updated), nil
}

func (store *desktopStore) mutateProjectKnowledge(update func(*KnowledgeLibrary) error) (ProjectKnowledgeSnapshot, error) {
	store.mu.Lock()
	defer store.mu.Unlock()
	path := strings.TrimSpace(store.config.ProjectPath)
	if path == "" {
		return ProjectKnowledgeSnapshot{}, errors.New("当前没有激活的项目")
	}
	updated := store.config
	updated.ProjectKnowledge = cloneProjectKnowledge(store.config.ProjectKnowledge)
	key := projectKnowledgeKey(path)
	library := cloneKnowledgeLibrary(updated.ProjectKnowledge[key])
	if err := update(&library); err != nil {
		return ProjectKnowledgeSnapshot{}, err
	}
	updated.ProjectKnowledge[key] = normalizeKnowledgeLibrary(library)
	if err := writeJSONAtomic(store.configPath, updated); err != nil {
		return ProjectKnowledgeSnapshot{}, err
	}
	store.config = updated
	return projectKnowledgeFromConfig(updated), nil
}

func normalizeProjectKnowledge(values map[string]KnowledgeLibrary) map[string]KnowledgeLibrary {
	result := make(map[string]KnowledgeLibrary, len(values))
	for path, library := range values {
		key := projectKnowledgeKey(path)
		if key != "" {
			result[key] = normalizeKnowledgeLibrary(library)
		}
	}
	return result
}

func normalizeKnowledgeLibrary(library KnowledgeLibrary) KnowledgeLibrary {
	return KnowledgeLibrary{
		Rules: normalizeRules(library.Rules), Memories: normalizeMemories(library.Memories), Skills: normalizeSkills(library.Skills),
	}
}

func emptyKnowledgeLibrary() KnowledgeLibrary {
	return KnowledgeLibrary{Rules: []GlobalRule{}, Memories: []GlobalMemory{}, Skills: []GlobalSkill{}}
}

func cloneKnowledgeLibrary(library KnowledgeLibrary) KnowledgeLibrary {
	return KnowledgeLibrary{
		Rules: append([]GlobalRule{}, library.Rules...), Memories: append([]GlobalMemory{}, library.Memories...), Skills: cloneSkills(library.Skills),
	}
}

func cloneProjectKnowledge(values map[string]KnowledgeLibrary) map[string]KnowledgeLibrary {
	result := make(map[string]KnowledgeLibrary, len(values))
	for key, library := range values {
		result[key] = cloneKnowledgeLibrary(library)
	}
	return result
}

func projectKnowledgeKey(path string) string {
	path = strings.TrimSpace(path)
	if path == "" {
		return ""
	}
	return strings.ToLower(filepath.Clean(path))
}

func normalizeRules(values []GlobalRule) []GlobalRule {
	result := make([]GlobalRule, 0, len(values))
	seen := map[string]bool{}
	for _, value := range values {
		value.ID = strings.TrimSpace(value.ID)
		value.Title = truncateRunes(value.Title, 100)
		value.Content = strings.TrimSpace(value.Content)
		if value.ID == "" {
			value.ID = newID("rule")
		}
		if seen[value.ID] || (value.Title == "" && value.Content == "") {
			continue
		}
		seen[value.ID] = true
		result = append(result, value)
	}
	return result
}

func normalizeMemories(values []GlobalMemory) []GlobalMemory {
	result := make([]GlobalMemory, 0, len(values))
	seen := map[string]bool{}
	for _, value := range values {
		value.ID = strings.TrimSpace(value.ID)
		value.Title = truncateRunes(value.Title, 100)
		value.Content = strings.TrimSpace(value.Content)
		if value.ID == "" {
			value.ID = newID("memory")
		}
		if seen[value.ID] || (value.Title == "" && value.Content == "") {
			continue
		}
		seen[value.ID] = true
		result = append(result, value)
	}
	return result
}

func normalizeSkills(values []GlobalSkill) []GlobalSkill {
	result := make([]GlobalSkill, 0, len(values))
	seen := map[string]bool{}
	for _, value := range values {
		value.ID = strings.TrimSpace(value.ID)
		value.Title = truncateRunes(value.Title, 100)
		value.Description = truncateRunes(value.Description, 400)
		value.Content = strings.TrimSpace(value.Content)
		value.PreferredModel = strings.TrimSpace(value.PreferredModel)
		value.AllowedTools = normalizeAllowlist(value.AllowedTools)
		if value.ID == "" {
			value.ID = newID("skill")
		}
		if value.RunAs != "SUBAGENT" {
			value.RunAs = "INLINE"
		}
		if seen[value.ID] || (value.Title == "" && value.Content == "") {
			continue
		}
		seen[value.ID] = true
		result = append(result, value)
	}
	return result
}

func cloneSkills(values []GlobalSkill) []GlobalSkill {
	result := append([]GlobalSkill{}, values...)
	for index := range result {
		result[index].AllowedTools = append([]string(nil), result[index].AllowedTools...)
	}
	return result
}

func validateBaseURL(value string) error {
	parsed, err := url.Parse(value)
	if err != nil || parsed.Host == "" || (parsed.Scheme != "http" && parsed.Scheme != "https") {
		return errors.New("模型 Base URL 必须是有效的 HTTP 或 HTTPS 地址")
	}
	if parsed.User != nil || parsed.RawQuery != "" || parsed.Fragment != "" {
		return errors.New("模型 Base URL 不能包含账户信息、查询参数或片段")
	}
	return nil
}

func (store *desktopStore) apiKey() (string, error) {
	store.mu.Lock()
	profile := findProviderProfile(store.config.ProviderProfiles, store.config.ActiveProviderProfileID)
	protected := ""
	if profile != nil {
		protected = profile.ProtectedAPIKey
	}
	store.mu.Unlock()
	if protected == "" {
		return "", errors.New("尚未配置 API Key")
	}
	plain, err := unprotectSecret(protected)
	if err != nil {
		return "", fmt.Errorf("无法解密 API Key：%w", err)
	}
	return string(plain), nil
}

func (store *desktopStore) activeProviderProfile() (ProviderProfile, error) {
	store.mu.Lock()
	defer store.mu.Unlock()
	profile := findProviderProfile(store.config.ProviderProfiles, store.config.ActiveProviderProfileID)
	if profile == nil {
		return ProviderProfile{}, errors.New("当前模型连接不存在")
	}
	return *profile, nil
}

func (store *desktopStore) activateProviderProfile(id string) (PublicDesktopConfig, error) {
	store.mu.Lock()
	defer store.mu.Unlock()
	id = strings.TrimSpace(id)
	profile := findProviderProfile(store.config.ProviderProfiles, id)
	if profile == nil {
		return PublicDesktopConfig{}, errors.New("模型连接不存在")
	}
	updated := store.config
	updated.ActiveProviderProfileID = id
	updated.BaseURL = profile.BaseURL
	updated.Model = profile.Model
	updated.ProtectedAPIKey = profile.ProtectedAPIKey
	if err := writeJSONAtomic(store.configPath, updated); err != nil {
		return PublicDesktopConfig{}, err
	}
	store.config = updated
	return publicConfig(updated), nil
}

func (store *desktopStore) setProviderReasoningEffort(id, effort string) (PublicDesktopConfig, error) {
	store.mu.Lock()
	defer store.mu.Unlock()
	id = strings.TrimSpace(id)
	effort = strings.ToLower(strings.TrimSpace(effort))
	switch effort {
	case "", "low", "medium", "high", "xhigh", "max":
	default:
		return PublicDesktopConfig{}, errors.New("推理强度无效")
	}
	updated := store.config
	updated.ProviderProfiles = append([]ProviderProfile{}, store.config.ProviderProfiles...)
	profile := findProviderProfile(updated.ProviderProfiles, id)
	if profile == nil {
		return PublicDesktopConfig{}, errors.New("模型连接不存在")
	}
	profile.ReasoningEffort = effort
	if err := writeJSONAtomic(store.configPath, updated); err != nil {
		return PublicDesktopConfig{}, err
	}
	store.config = updated
	return publicConfig(updated), nil
}

func (store *desktopStore) listSessions() []SessionSummary {
	store.mu.Lock()
	defer store.mu.Unlock()
	result := make([]SessionSummary, 0, len(store.sessions))
	for _, session := range store.sessions {
		result = append(result, SessionSummary{
			ID: session.ID, Title: session.Title, UpdatedAt: session.UpdatedAt, MessageCount: len(session.Messages),
			ProjectPath: session.ProjectPath, ExecutionOwner: sessionExecutionOwner(session), HandoffStartedAt: session.ExecutionHandoff.StartedAt,
		})
	}
	sort.Slice(result, func(i, j int) bool { return result[i].UpdatedAt > result[j].UpdatedAt })
	return result
}

func (store *desktopStore) getSession(id string) *ChatSession {
	store.mu.Lock()
	defer store.mu.Unlock()
	return cloneSession(store.sessions[id])
}

func (store *desktopStore) snapshotSessions() []*ChatSession {
	store.mu.Lock()
	defer store.mu.Unlock()
	result := make([]*ChatSession, 0, len(store.sessions))
	for _, session := range store.sessions {
		result = append(result, cloneSession(session))
	}
	sort.Slice(result, func(i, j int) bool {
		if result[i].UpdatedAt == result[j].UpdatedAt {
			return result[i].ID < result[j].ID
		}
		return result[i].UpdatedAt > result[j].UpdatedAt
	})
	return result
}

func (store *desktopStore) createSession(title string) (*ChatSession, error) {
	store.mu.Lock()
	defer store.mu.Unlock()
	now := time.Now().UnixMilli()
	title = strings.TrimSpace(title)
	if title == "" {
		title = "新会话"
	}
	session := &ChatSession{
		ID: newID("session"), Title: truncateRunes(title, 60), CreatedAt: now, UpdatedAt: now,
		ProjectPath: store.config.ProjectPath, Messages: []ChatMessage{},
	}
	store.sessions[session.ID] = session
	if err := store.saveSessionsLocked(); err != nil {
		delete(store.sessions, session.ID)
		return nil, err
	}
	return cloneSession(session), nil
}

func (store *desktopStore) bindSessionProject(id, projectPath string) (*ChatSession, error) {
	id = strings.TrimSpace(id)
	projectPath = strings.TrimSpace(projectPath)
	if projectPath != "" {
		resolved, err := normalizeExistingProjectPath(projectPath)
		if err != nil {
			return nil, err
		}
		projectPath = resolved
	}
	store.mu.Lock()
	defer store.mu.Unlock()
	session := store.sessions[id]
	if session == nil {
		return nil, errors.New("会话不存在")
	}
	if err := requireDesktopExecutionAuthority(session); err != nil {
		return nil, err
	}
	if sameWorkspacePath(session.ProjectPath, projectPath) {
		return cloneSession(session), nil
	}
	previousPath, previousUpdatedAt := session.ProjectPath, session.UpdatedAt
	previousThreadID, previousSyncedID, previousToolsVersion := session.CodexThreadID, session.CodexSyncedID, session.CodexToolsVersion
	session.ProjectPath = projectPath
	session.CodexThreadID = ""
	session.CodexSyncedID = ""
	session.CodexToolsVersion = 0
	session.UpdatedAt = time.Now().UnixMilli()
	if err := store.saveSessionsLocked(); err != nil {
		session.ProjectPath, session.UpdatedAt = previousPath, previousUpdatedAt
		session.CodexThreadID, session.CodexSyncedID, session.CodexToolsVersion = previousThreadID, previousSyncedID, previousToolsVersion
		return nil, err
	}
	return cloneSession(session), nil
}

func (store *desktopStore) deleteSession(id string) error {
	store.mu.Lock()
	defer store.mu.Unlock()
	session, ok := store.sessions[id]
	if !ok {
		return errors.New("会话不存在")
	}
	if err := requireDesktopExecutionAuthority(session); err != nil {
		return err
	}
	delete(store.sessions, id)
	return store.saveSessionsLocked()
}

func (store *desktopStore) setSessionPlanMode(id string, enabled bool) (*ChatSession, error) {
	store.mu.Lock()
	defer store.mu.Unlock()
	session := store.sessions[id]
	if session == nil {
		return nil, errors.New("会话不存在")
	}
	if err := requireDesktopExecutionAuthority(session); err != nil {
		return nil, err
	}
	session.PlanModeEnabled = enabled
	if err := store.saveSessionsLocked(); err != nil {
		return nil, err
	}
	return cloneSession(session), nil
}

func (store *desktopStore) clearSessionGoal(id string) (*ChatSession, error) {
	store.mu.Lock()
	defer store.mu.Unlock()
	session := store.sessions[id]
	if session == nil {
		return nil, errors.New("会话不存在")
	}
	if err := requireDesktopExecutionAuthority(session); err != nil {
		return nil, err
	}
	session.Goal = ""
	if err := store.saveSessionsLocked(); err != nil {
		return nil, err
	}
	return cloneSession(session), nil
}

func (store *desktopStore) appendMessage(sessionID string, message ChatMessage) (*ChatSession, error) {
	validatedImages, err := store.validateMessageImages(message.ImageAttachments)
	if err != nil {
		return nil, err
	}
	store.mu.Lock()
	defer store.mu.Unlock()
	session := store.sessions[sessionID]
	if session == nil {
		return nil, errors.New("会话不存在")
	}
	if err := requireDesktopExecutionAuthority(session); err != nil {
		return nil, err
	}
	message.Content = strings.TrimSpace(message.Content)
	message.ImageAttachments = cloneImageAttachments(validatedImages)
	message.Context = cloneComposerContext(message.Context)
	message.Mode = normalizeComposerMode(message.Mode)
	if message.ID == "" {
		message.ID = newID("message")
	}
	if message.CreatedAt == 0 {
		message.CreatedAt = time.Now().UnixMilli()
	}
	session.Messages = append(session.Messages, message)
	if message.Role == "user" && (message.Mode == "goal" || message.Mode == "goal_plan") {
		session.Goal = truncateRunes(message.Content, 20_000)
	}
	session.UpdatedAt = message.CreatedAt
	if session.Title == "新会话" && message.Role == "user" && message.Content != "" {
		session.Title = truncateRunes(message.Content, 40)
	} else if session.Title == "新会话" && message.Role == "user" && len(message.ImageAttachments) > 0 {
		session.Title = truncateRunes(message.ImageAttachments[0].FileName, 40)
	}
	if err := store.saveSessionsLocked(); err != nil {
		return nil, err
	}
	return cloneSession(session), nil
}

func (store *desktopStore) saveSessionsLocked() error {
	sessions := make([]*ChatSession, 0, len(store.sessions))
	for _, session := range store.sessions {
		sessions = append(sessions, session)
	}
	sort.Slice(sessions, func(i, j int) bool { return sessions[i].UpdatedAt > sessions[j].UpdatedAt })
	return writeJSONAtomic(store.sessionsPath, sessions)
}

func writeJSONAtomic(path string, value any) error {
	data, err := json.MarshalIndent(value, "", "  ")
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return err
	}
	temp, err := os.CreateTemp(filepath.Dir(path), ".murong-desktop-*.tmp")
	if err != nil {
		return err
	}
	tempName := temp.Name()
	defer os.Remove(tempName)
	if err := temp.Chmod(0o600); err != nil {
		temp.Close()
		return err
	}
	if _, err := temp.Write(data); err != nil {
		temp.Close()
		return err
	}
	if err := temp.Sync(); err != nil {
		temp.Close()
		return err
	}
	if err := temp.Close(); err != nil {
		return err
	}
	return replaceFile(tempName, path)
}

func cloneSession(session *ChatSession) *ChatSession {
	if session == nil {
		return nil
	}
	copy := *session
	copy.Messages = append([]ChatMessage(nil), session.Messages...)
	copy.WorkflowPlan = cloneDesktopWorkflowPlan(session.WorkflowPlan)
	copy.BackgroundSubagentJobs = cloneSubagentBackgroundJobs(session.BackgroundSubagentJobs)
	for index := range copy.Messages {
		copy.Messages[index].Context = cloneComposerContext(copy.Messages[index].Context)
		copy.Messages[index].ImageAttachments = cloneImageAttachments(copy.Messages[index].ImageAttachments)
		copy.Messages[index].WorkspaceChanges = cloneWorkspaceChanges(copy.Messages[index].WorkspaceChanges)
	}
	return &copy
}

func cloneImageAttachments(values []MessageImageAttachment) []MessageImageAttachment {
	return append([]MessageImageAttachment(nil), values...)
}

func truncateRunes(value string, limit int) string {
	runes := []rune(strings.TrimSpace(value))
	if len(runes) > limit {
		runes = runes[:limit]
	}
	return string(runes)
}
