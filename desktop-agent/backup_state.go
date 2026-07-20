package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"
)

const (
	desktopBackupProviderPath     = "state/provider-settings.json"
	desktopBackupMCPPath          = "state/mcp-config.json"
	desktopBackupWorkflowsPath    = "state/saved-workflows.json"
	desktopBackupUIPath           = "state/ui-settings.json"
	desktopBackupSettingsPath     = "state/backup-settings.json"
	desktopBackupSessionsPath     = "data/conversations/sessions.json"
	desktopBackupKnowledgePath    = "data/memories/knowledge.json"
	desktopBackupProjectAuditPath = "data/project_audit/archive.json"
)

var desktopBackupRequiredPaths = []string{
	desktopBackupProviderPath,
	desktopBackupMCPPath,
	desktopBackupWorkflowsPath,
	desktopBackupUIPath,
	desktopBackupSettingsPath,
	desktopBackupSessionsPath,
	desktopBackupKnowledgePath,
}

type desktopStoreBackupSnapshot struct {
	Config   desktopConfig
	Sessions []*ChatSession
}

type savedWorkflowStoreBackupSnapshot struct {
	Document savedWorkflowDocument
}

func buildDesktopBackupPayloads(store *desktopStore, workflows *savedWorkflowStore, audit *projectAuditStore, settings DesktopBackupSettings) ([]desktopBackupPayload, error) {
	sourcePlatform := desktopSourcePlatform()
	storeSnapshot := store.backupSnapshot()
	workflowSnapshot := workflows.backupSnapshot()
	portableState, err := buildDesktopCrossPlatformBackupState(storeSnapshot, workflowSnapshot, time.Now().UnixMilli())
	if err != nil {
		return nil, err
	}
	providerProfiles := make([]desktopProviderProfileBackup, 0, len(storeSnapshot.Config.ProviderProfiles))
	for _, profile := range storeSnapshot.Config.ProviderProfiles {
		providerProfiles = append(providerProfiles, desktopProviderProfileBackup{
			ID: profile.ID, ProviderID: profile.ProviderID, Name: profile.Name, BaseURL: profile.BaseURL,
			Model: profile.Model, ReasoningEffort: profile.ReasoningEffort, APIMode: profile.APIMode,
			ContextWindowTokens: profile.ContextWindowTokens,
		})
	}
	mcpServers := make([]desktopMCPServerBackup, 0, len(storeSnapshot.Config.MCPServers))
	for _, server := range storeSnapshot.Config.MCPServers {
		mcpServers = append(mcpServers, desktopMCPServerBackup{
			ID: server.ID, Name: server.Name, Transport: server.Transport, Command: server.Command,
			Args: append([]string{}, server.Args...), Cwd: server.Cwd, URL: server.URL,
			RequestTimeoutSeconds: server.RequestTimeoutSeconds,
			TrustedReadOnlyTools:  append([]string{}, server.TrustedReadOnlyTools...),
			Enabled:               server.Enabled, AutoStart: server.AutoStart,
		})
	}
	workflowDefinitions := cloneSavedWorkflows(workflowSnapshot.Document.Workflows)
	temperature := storeSnapshot.Config.Temperature
	maxTokens := storeSnapshot.Config.MaxTokens
	enableMultimodal := storeSnapshot.Config.EnableMultimodalMessages
	states := []struct {
		path     string
		category string
		value    any
	}{
		{desktopBackupProviderPath, backupCategoryProvider, desktopProviderSettingsBackup{
			SchemaVersion: 1, SourcePlatform: sourcePlatform, ProjectPath: storeSnapshot.Config.ProjectPath,
			ApprovalMode: storeSnapshot.Config.ApprovalMode, Allowlist: append([]string{}, storeSnapshot.Config.Allowlist...),
			MaxToolIterations: storeSnapshot.Config.MaxToolIterations, SystemPrompt: storeSnapshot.Config.SystemPrompt,
			ResponseVerbosity: storeSnapshot.Config.ResponseVerbosity, ActiveProviderProfileID: storeSnapshot.Config.ActiveProviderProfileID,
			Temperature: &temperature, MaxTokens: &maxTokens, EnableMultimodalMessages: &enableMultimodal,
			PlannerProfileEnabled: storeSnapshot.Config.PlannerProfileEnabled,
			PlannerModel:          storeSnapshot.Config.PlannerModel, PlannerReasoningEffort: storeSnapshot.Config.PlannerReasoningEffort,
			SubagentProfileEnabled: storeSnapshot.Config.SubagentProfileEnabled,
			SubagentModel:          storeSnapshot.Config.SubagentModel, SubagentReasoningEffort: storeSnapshot.Config.SubagentReasoningEffort,
			ProviderProfiles: providerProfiles, EnabledBuiltinTools: append([]string{}, storeSnapshot.Config.EnabledBuiltinTools...),
			EnabledFileOperations:  append([]string{}, storeSnapshot.Config.EnabledFileOperations...),
			ProjectToolPreferences: cloneProjectToolPreferences(storeSnapshot.Config.ProjectToolPreferences),
			RecentProjects:         append([]RecentProjectRecord{}, storeSnapshot.Config.RecentProjects...),
		}},
		{desktopBackupKnowledgePath, backupCategoryMemories, desktopKnowledgeBackup{
			SchemaVersion: 1, SourcePlatform: sourcePlatform,
			GlobalRules:      append([]GlobalRule{}, storeSnapshot.Config.GlobalRules...),
			GlobalMemories:   append([]GlobalMemory{}, storeSnapshot.Config.GlobalMemories...),
			GlobalSkills:     cloneSkills(storeSnapshot.Config.GlobalSkills),
			ProjectKnowledge: cloneProjectKnowledge(storeSnapshot.Config.ProjectKnowledge),
		}},
		{desktopBackupMCPPath, backupCategoryMCP, desktopMCPBackup{SchemaVersion: 1, SourcePlatform: sourcePlatform, Servers: mcpServers}},
		{desktopBackupWorkflowsPath, backupCategoryWorkflows, desktopSavedWorkflowsBackup{
			SchemaVersion: 1, SourcePlatform: sourcePlatform,
			GitHubAPIBaseURL: normalizeGitHubAPIBaseURL(workflowSnapshot.Document.GitHub.APIBaseURL),
			Workflows:        workflowDefinitions,
		}},
		{desktopBackupUIPath, backupCategoryUI, desktopUISettingsBackup{SchemaVersion: 1, SourcePlatform: sourcePlatform}},
		{desktopBackupSettingsPath, backupCategorySettings, normalizeDesktopBackupSettings(settings)},
		{desktopBackupSessionsPath, backupCategoryConversations, desktopSessionsBackup{
			SchemaVersion: 1, SourcePlatform: sourcePlatform, Sessions: cloneDesktopSessionsForBackup(storeSnapshot.Sessions),
		}},
		{desktopBackupPortableStatePath, backupCategoryPortableState, portableState},
	}
	if audit != nil {
		states = append(states, struct {
			path     string
			category string
			value    any
		}{desktopBackupProjectAuditPath, backupCategoryProjectAudit, audit.Snapshot()})
	}
	payloads := make([]desktopBackupPayload, 0, len(states))
	for _, state := range states {
		data, err := json.MarshalIndent(state.value, "", "  ")
		if err != nil {
			return nil, fmt.Errorf("无法编码备份状态 %s：%w", state.path, err)
		}
		payloads = append(payloads, desktopBackupPayload{Path: state.path, Category: state.category, Data: data})
	}
	mediaPayloads, err := buildConversationMediaBackupPayloads(store, storeSnapshot.Sessions)
	if err != nil {
		return nil, err
	}
	payloads = append(payloads, mediaPayloads...)
	return payloads, nil
}

func decodeAndValidateDesktopPortableState(validated validatedDesktopBackup) (desktopPortableBackupState, error) {
	manifestPaths := make(map[string]bool, len(validated.Manifest.Entries))
	for _, entry := range validated.Manifest.Entries {
		manifestPaths[entry.Path] = true
	}
	read := func(path string, target any) error {
		data, err := os.ReadFile(filepath.Join(validated.PayloadRoot, filepath.FromSlash(path)))
		if err != nil {
			return err
		}
		return decodeStrictJSON(data, target)
	}
	var crossPlatform desktopCrossPlatformBackupState
	if manifestPaths[desktopBackupPortableStatePath] {
		if err := read(desktopBackupPortableStatePath, &crossPlatform); err != nil {
			return desktopPortableBackupState{}, fmt.Errorf("跨端备份状态无法解析：%w", err)
		}
		if err := validateDesktopCrossPlatformBackupState(crossPlatform); err != nil {
			return desktopPortableBackupState{}, err
		}
	} else if validated.Manifest.FormatVersion >= 2 {
		return desktopPortableBackupState{}, errors.New("v2 备份缺少跨平台可移植状态")
	}
	if crossPlatform.SchemaVersion != 0 && crossPlatform.SourcePlatform != desktopSourcePlatform() {
		return desktopPortableBackupState{CrossPlatform: &crossPlatform}, nil
	}
	for _, required := range desktopBackupRequiredPaths {
		if !manifestPaths[required] {
			return desktopPortableBackupState{}, fmt.Errorf("备份缺少必要状态：%s", required)
		}
	}
	var provider desktopProviderSettingsBackup
	var knowledge desktopKnowledgeBackup
	var mcp desktopMCPBackup
	var workflows desktopSavedWorkflowsBackup
	var ui desktopUISettingsBackup
	var settings DesktopBackupSettings
	var sessions desktopSessionsBackup
	var projectAudit desktopProjectAuditDocument
	for path, target := range map[string]any{
		desktopBackupProviderPath: &provider, desktopBackupKnowledgePath: &knowledge,
		desktopBackupMCPPath: &mcp, desktopBackupWorkflowsPath: &workflows,
		desktopBackupUIPath: &ui, desktopBackupSettingsPath: &settings,
		desktopBackupSessionsPath: &sessions,
	} {
		if err := read(path, target); err != nil {
			return desktopPortableBackupState{}, fmt.Errorf("备份状态 %s 无法解析：%w", path, err)
		}
	}
	if provider.SchemaVersion != 1 || knowledge.SchemaVersion != 1 || mcp.SchemaVersion != 1 || workflows.SchemaVersion != 1 || ui.SchemaVersion != 1 || sessions.SchemaVersion != 1 {
		return desktopPortableBackupState{}, errors.New("备份状态版本不受支持")
	}
	projectAuditIncluded := manifestPaths[desktopBackupProjectAuditPath]
	if projectAuditIncluded {
		if err := read(desktopBackupProjectAuditPath, &projectAudit); err != nil {
			return desktopPortableBackupState{}, fmt.Errorf("备份状态 %s 无法解析：%w", desktopBackupProjectAuditPath, err)
		}
		if err := validateProjectAuditDocument(projectAudit, true); err != nil {
			return desktopPortableBackupState{}, fmt.Errorf("备份项目审计无效：%w", err)
		}
	}
	for name, source := range map[string]string{
		"模型设置": provider.SourcePlatform, "知识库": knowledge.SourcePlatform, "MCP": mcp.SourcePlatform,
		"工作流": workflows.SourcePlatform, "界面设置": ui.SourcePlatform, "会话": sessions.SourcePlatform,
	} {
		if !isDesktopSourcePlatform(source) {
			return desktopPortableBackupState{}, fmt.Errorf("%s来自不受支持的平台：%s", name, source)
		}
		if source != desktopSourcePlatform() {
			return desktopPortableBackupState{}, fmt.Errorf("%s来自其他电脑系统（%s）；包含项目路径的完整备份只能在同一系统恢复", name, source)
		}
	}
	state := desktopPortableBackupState{
		ProviderSettings: provider, Knowledge: knowledge, MCP: mcp, Workflows: workflows,
		Sessions: cloneDesktopSessions(sessions.Sessions), BackupSettings: settings,
		ProjectAudit: cloneProjectAuditDocument(projectAudit), ProjectAuditIncluded: projectAuditIncluded,
	}
	if crossPlatform.SchemaVersion != 0 {
		state.CrossPlatform = &crossPlatform
	}
	if err := validateDesktopPortableState(state); err != nil {
		return desktopPortableBackupState{}, err
	}
	if err := validateConversationMediaBackup(validated, state.Sessions); err != nil {
		return desktopPortableBackupState{}, err
	}
	return state, nil
}

func validateDesktopPortableState(state desktopPortableBackupState) error {
	if state.ProjectAuditIncluded {
		if err := validateProjectAuditDocument(state.ProjectAudit, true); err != nil {
			return err
		}
	}
	provider := state.ProviderSettings
	if provider.ApprovalMode != approvalReadOnly && provider.ApprovalMode != approvalAskAll && provider.ApprovalMode != approvalAllowlist && provider.ApprovalMode != approvalYolo {
		return errors.New("备份审批模式无效")
	}
	if provider.MaxToolIterations < 1 || provider.MaxToolIterations > 999 {
		return errors.New("备份工具迭代数量无效")
	}
	if provider.ResponseVerbosity != "CONCISE" && provider.ResponseVerbosity != "BALANCED" && provider.ResponseVerbosity != "DETAILED" {
		return errors.New("备份回答详细度无效")
	}
	if provider.Temperature != nil && (*provider.Temperature < 0 || *provider.Temperature > 2) {
		return errors.New("备份 Temperature 无效")
	}
	if provider.MaxTokens != nil && (*provider.MaxTokens < 1 || *provider.MaxTokens > 128_000) {
		return errors.New("备份最大输出 Token 无效")
	}
	if normalizeExecutionProfileModel(provider.PlannerModel) != strings.TrimSpace(provider.PlannerModel) ||
		normalizeExecutionProfileReasoning(provider.PlannerReasoningEffort) != strings.ToLower(strings.TrimSpace(provider.PlannerReasoningEffort)) ||
		normalizeExecutionProfileModel(provider.SubagentModel) != strings.TrimSpace(provider.SubagentModel) ||
		normalizeExecutionProfileReasoning(provider.SubagentReasoningEffort) != strings.ToLower(strings.TrimSpace(provider.SubagentReasoningEffort)) {
		return errors.New("备份执行 Profile 的模型或推理强度无效")
	}
	if len(provider.ProviderProfiles) == 0 || len(provider.ProviderProfiles) > 64 {
		return errors.New("备份模型连接数量无效")
	}
	seenProviders := map[string]bool{}
	activeFound := false
	for _, profile := range provider.ProviderProfiles {
		if strings.TrimSpace(profile.ID) == "" || seenProviders[profile.ID] {
			return errors.New("备份模型连接包含空白或重复 ID")
		}
		seenProviders[profile.ID] = true
		activeFound = activeFound || profile.ID == provider.ActiveProviderProfileID
		if profile.ProviderID != providerOpenAI && profile.ProviderID != providerDeepSeek && profile.ProviderID != providerClaude && profile.ProviderID != providerCodex {
			return fmt.Errorf("备份模型连接 %q 的供应商无效", profile.Name)
		}
		if strings.TrimSpace(profile.Name) == "" || len([]rune(profile.Name)) > 80 || (profile.ProviderID != providerCodex && strings.TrimSpace(profile.Model) == "") {
			return errors.New("备份模型连接名称或模型无效")
		}
		if profile.ProviderID == providerCodex {
			if strings.TrimSpace(profile.BaseURL) != "" || profile.APIMode != "app-server" {
				return fmt.Errorf("备份模型连接 %q 的 Codex 配置无效", profile.Name)
			}
		} else if err := validateBaseURL(profile.BaseURL); err != nil {
			return fmt.Errorf("备份模型连接 %q：%w", profile.Name, err)
		}
	}
	if !activeFound {
		return errors.New("备份当前模型连接不存在")
	}
	if len(provider.RecentProjects) > 100 || len(provider.ProjectToolPreferences) > 1000 {
		return errors.New("备份项目设置数量超过上限")
	}
	for projectKey, preferences := range provider.ProjectToolPreferences {
		if strings.TrimSpace(projectKey) == "" {
			return errors.New("备份项目设置包含空白项目标识")
		}
		if err := validateProjectSubagentTemplates(preferences.SubagentTemplates); err != nil {
			return fmt.Errorf("备份项目 %q：%w", projectKey, err)
		}
	}
	if err := validateDesktopKnowledgeBackup(state.Knowledge); err != nil {
		return err
	}
	if len(state.MCP.Servers) > maxMCPServers {
		return fmt.Errorf("备份 MCP 服务器超过 %d 个", maxMCPServers)
	}
	seenMCP := map[string]bool{}
	seenMCPNames := map[string]bool{}
	for _, portable := range state.MCP.Servers {
		if strings.TrimSpace(portable.ID) == "" || seenMCP[portable.ID] {
			return errors.New("备份 MCP 服务器包含空白或重复 ID")
		}
		seenMCP[portable.ID] = true
		nameKey := strings.ToLower(strings.TrimSpace(portable.Name))
		if nameKey == "" || seenMCPNames[nameKey] {
			return errors.New("备份 MCP 服务器包含空白或重复名称")
		}
		seenMCPNames[nameKey] = true
		server := MCPServerConfig{
			ID: portable.ID, Name: portable.Name, Transport: portable.Transport, Command: portable.Command,
			Args: portable.Args, Cwd: portable.Cwd, URL: portable.URL,
			RequestTimeoutSeconds: portable.RequestTimeoutSeconds,
			TrustedReadOnlyTools:  portable.TrustedReadOnlyTools, Enabled: portable.Enabled, AutoStart: portable.AutoStart,
		}
		if err := validateMCPServerConfig(server); err != nil {
			return err
		}
	}
	if err := validateGitHubAPIBaseURL(normalizeGitHubAPIBaseURL(state.Workflows.GitHubAPIBaseURL)); err != nil {
		return err
	}
	if len(state.Workflows.Workflows) > maximumSavedWorkflows {
		return fmt.Errorf("备份保存工作流超过 %d 个", maximumSavedWorkflows)
	}
	seenWorkflows := map[string]bool{}
	for _, workflow := range state.Workflows.Workflows {
		if strings.TrimSpace(workflow.ID) == "" || seenWorkflows[workflow.ID] {
			return errors.New("备份保存工作流包含空白或重复 ID")
		}
		seenWorkflows[workflow.ID] = true
		if validation := validateSavedWorkflow(workflow); len(validation) > 0 {
			return fmt.Errorf("备份工作流 %q 无效：%s", workflow.Name, strings.Join(validation, "；"))
		}
	}
	settings := normalizeDesktopBackupSettings(state.BackupSettings)
	if settings.MaxBackupCount != state.BackupSettings.MaxBackupCount {
		return errors.New("备份保留数量无效")
	}
	return validateDesktopSessions(state.Sessions)
}

func validateDesktopKnowledgeBackup(knowledge desktopKnowledgeBackup) error {
	if len(knowledge.GlobalRules) > 10_000 || len(knowledge.GlobalMemories) > 10_000 || len(knowledge.GlobalSkills) > 10_000 || len(knowledge.ProjectKnowledge) > 2_000 {
		return errors.New("备份知识库数量超过上限")
	}
	if len(normalizeRules(knowledge.GlobalRules)) != len(knowledge.GlobalRules) || len(normalizeMemories(knowledge.GlobalMemories)) != len(knowledge.GlobalMemories) || len(normalizeSkills(knowledge.GlobalSkills)) != len(knowledge.GlobalSkills) {
		return errors.New("备份全局知识库包含重复或无效条目")
	}
	for key, library := range knowledge.ProjectKnowledge {
		if strings.TrimSpace(key) == "" || len(key) > 2048 {
			return errors.New("备份项目知识库键无效")
		}
		if len(library.Rules) > 10_000 || len(library.Memories) > 10_000 || len(library.Skills) > 10_000 {
			return errors.New("备份项目知识库数量超过上限")
		}
		if len(normalizeRules(library.Rules)) != len(library.Rules) || len(normalizeMemories(library.Memories)) != len(library.Memories) || len(normalizeSkills(library.Skills)) != len(library.Skills) {
			return errors.New("备份项目知识库包含重复或无效条目")
		}
	}
	return nil
}

func validateDesktopSessions(sessions []*ChatSession) error {
	if len(sessions) > 5_000 {
		return errors.New("备份会话数量超过上限")
	}
	seenSessions := map[string]bool{}
	totalMessages := 0
	for _, session := range sessions {
		if session == nil || strings.TrimSpace(session.ID) == "" || seenSessions[session.ID] {
			return errors.New("备份会话包含空白或重复 ID")
		}
		seenSessions[session.ID] = true
		if err := validateSessionUsage(session.Usage); err != nil {
			return fmt.Errorf("备份会话 %s 的用量无效：%w", session.ID, err)
		}
		if err := validateSessionCompression(session.Compression, session.Messages); err != nil {
			return fmt.Errorf("备份会话 %s 的上下文摘要无效：%w", session.ID, err)
		}
		if err := validateSessionExecutionHandoff(session.ExecutionHandoff, len(session.Messages)); err != nil {
			return fmt.Errorf("备份会话 %s 的执行权状态无效：%w", session.ID, err)
		}
		if err := validateDesktopWorkflowPlan(session.WorkflowPlan, session.Messages); err != nil {
			return fmt.Errorf("备份会话 %s 的规范计划无效：%w", session.ID, err)
		}
		if strings.TrimSpace(session.Title) == "" || len([]rune(session.Title)) > 500 || len([]rune(session.Goal)) > 20_000 {
			return fmt.Errorf("备份会话 %s 的标题或目标无效", session.ID)
		}
		if projectPath := strings.TrimSpace(session.ProjectPath); projectPath != "" {
			if len(projectPath) > 32*1024 || strings.ContainsRune(projectPath, '\x00') || !filepath.IsAbs(projectPath) || filepath.Clean(projectPath) != projectPath {
				return fmt.Errorf("备份会话 %s 的项目路径无效", session.ID)
			}
		}
		if session.CodexToolsVersion < 0 || session.CodexToolsVersion > codexDynamicToolsVersion {
			return fmt.Errorf("备份会话 %s 的 Codex 工具版本无效", session.ID)
		}
		if err := validateSubagentBackgroundJobs(session.BackgroundSubagentJobs); err != nil {
			return fmt.Errorf("备份会话 %s 的后台子代理无效：%w", session.ID, err)
		}
		totalMessages += len(session.Messages)
		if totalMessages > 500_000 {
			return errors.New("备份消息数量超过上限")
		}
		seenMessages := map[string]bool{}
		for _, message := range session.Messages {
			if strings.TrimSpace(message.ID) == "" || seenMessages[message.ID] {
				return fmt.Errorf("备份会话 %s 包含空白或重复消息 ID", session.ID)
			}
			seenMessages[message.ID] = true
			if message.Role != "user" && message.Role != "assistant" && message.Role != "tool" {
				return fmt.Errorf("备份会话 %s 包含未知消息角色", session.ID)
			}
			if len(message.Content) > 4*1024*1024 || len(message.Context) > maxComposerContextItems || normalizeComposerMode(message.Mode) != message.Mode {
				return fmt.Errorf("备份会话 %s 包含过大或无效消息", session.ID)
			}
			if len([]rune(message.ToolCallID)) > 1_000 || len([]rune(message.ToolArguments)) > 64*1024 ||
				(message.ToolStatus != "" && message.ToolStatus != "success" && message.ToolStatus != "failed") {
				return fmt.Errorf("备份会话 %s 包含无效工具收据", session.ID)
			}
			if len(message.ImageAttachments) > maxChatImagesPerMessage {
				return fmt.Errorf("备份会话 %s 的单条消息图片过多", session.ID)
			}
			seenImages := map[string]bool{}
			for _, attachment := range message.ImageAttachments {
				if seenImages[attachment.ID] {
					return fmt.Errorf("备份会话 %s 的消息包含重复图片", session.ID)
				}
				seenImages[attachment.ID] = true
				if err := validateChatImageAttachmentMetadata(attachment); err != nil {
					return fmt.Errorf("备份会话 %s 的图片元数据无效：%w", session.ID, err)
				}
			}
			for _, contextItem := range message.Context {
				if len(contextItem.ID)+len(contextItem.Label)+len(contextItem.Path)+len(contextItem.Scope) > 16*1024 {
					return fmt.Errorf("备份会话 %s 的上下文条目过大", session.ID)
				}
			}
			if err := validateMessageWorkspaceChanges(message); err != nil {
				return fmt.Errorf("备份会话 %s 的项目变化无效：%w", session.ID, err)
			}
		}
	}
	return nil
}

func buildRestoredDesktopStoreSnapshot(current desktopStoreBackupSnapshot, portable desktopPortableBackupState) (desktopStoreBackupSnapshot, error) {
	provider := portable.ProviderSettings
	temperature := 0.7
	if provider.Temperature != nil {
		temperature = *provider.Temperature
	}
	maxTokens := 8192
	if provider.MaxTokens != nil {
		maxTokens = *provider.MaxTokens
	}
	enableMultimodal := true
	if provider.EnableMultimodalMessages != nil {
		enableMultimodal = *provider.EnableMultimodalMessages
	}
	profiles := make([]ProviderProfile, 0, len(provider.ProviderProfiles))
	for _, saved := range provider.ProviderProfiles {
		profile := ProviderProfile{
			ID: saved.ID, ProviderID: saved.ProviderID, Name: saved.Name, BaseURL: saved.BaseURL,
			Model: saved.Model, ReasoningEffort: saved.ReasoningEffort, APIMode: saved.APIMode,
			ContextWindowTokens: saved.ContextWindowTokens,
		}
		if existing := matchProviderSecret(current.Config.ProviderProfiles, profile); existing != nil {
			profile.ProtectedAPIKey = existing.ProtectedAPIKey
		}
		profiles = append(profiles, profile)
	}
	mcpServers := make([]MCPServerConfig, 0, len(portable.MCP.Servers))
	for _, saved := range portable.MCP.Servers {
		server := MCPServerConfig{
			ID: saved.ID, Name: saved.Name, Transport: saved.Transport, Command: saved.Command,
			Args: append([]string{}, saved.Args...), Cwd: saved.Cwd, URL: saved.URL,
			RequestTimeoutSeconds: saved.RequestTimeoutSeconds,
			TrustedReadOnlyTools:  append([]string{}, saved.TrustedReadOnlyTools...),
			Enabled:               saved.Enabled, AutoStart: saved.AutoStart,
		}
		if existing := matchMCPSecrets(current.Config.MCPServers, server); existing != nil {
			server.ProtectedEnvironmentJSON = existing.ProtectedEnvironmentJSON
			server.ProtectedHeadersJSON = existing.ProtectedHeadersJSON
		}
		mcpServers = append(mcpServers, server)
	}
	config := desktopConfig{
		SchemaVersion: desktopConfigSchemaVersion, ProjectPath: strings.TrimSpace(provider.ProjectPath),
		ApprovalMode: provider.ApprovalMode, Allowlist: append([]string{}, provider.Allowlist...),
		MaxToolIterations: provider.MaxToolIterations, SystemPrompt: provider.SystemPrompt,
		ResponseVerbosity:        provider.ResponseVerbosity,
		Temperature:              temperature,
		MaxTokens:                maxTokens,
		EnableMultimodalMessages: enableMultimodal,
		PlannerProfileEnabled:    provider.PlannerProfileEnabled,
		PlannerModel:             provider.PlannerModel,
		PlannerReasoningEffort:   provider.PlannerReasoningEffort,
		SubagentProfileEnabled:   provider.SubagentProfileEnabled,
		SubagentModel:            provider.SubagentModel,
		SubagentReasoningEffort:  provider.SubagentReasoningEffort,
		GlobalRules:              append([]GlobalRule{}, portable.Knowledge.GlobalRules...),
		GlobalMemories:           append([]GlobalMemory{}, portable.Knowledge.GlobalMemories...),
		GlobalSkills:             cloneSkills(portable.Knowledge.GlobalSkills),
		ProjectKnowledge:         cloneProjectKnowledge(portable.Knowledge.ProjectKnowledge),
		ActiveProviderProfileID:  provider.ActiveProviderProfileID, ProviderProfiles: profiles,
		EnabledBuiltinTools:    append([]string{}, provider.EnabledBuiltinTools...),
		EnabledFileOperations:  append([]string{}, provider.EnabledFileOperations...),
		ProjectToolPreferences: cloneProjectToolPreferences(provider.ProjectToolPreferences),
		RecentProjects:         append([]RecentProjectRecord{}, provider.RecentProjects...), MCPServers: mcpServers,
	}
	if config.ProjectPath != "" {
		path, err := normalizeExistingProjectPath(config.ProjectPath)
		if err != nil {
			config.ProjectPath = ""
		} else {
			config.ProjectPath = path
		}
	}
	config = normalizeDesktopConfig(config)
	for _, profile := range config.ProviderProfiles {
		if profile.ProviderID != providerCodex {
			if err := validateBaseURL(profile.BaseURL); err != nil {
				return desktopStoreBackupSnapshot{}, err
			}
		}
	}
	for _, server := range config.MCPServers {
		if err := validateMCPServerConfig(server); err != nil {
			return desktopStoreBackupSnapshot{}, err
		}
	}
	restoredSessions := cloneDesktopSessions(portable.Sessions)
	restoredAt := time.Now().UnixMilli()
	for _, session := range restoredSessions {
		session.BackgroundSubagentJobs, _ = normalizeRestoredSubagentJobs(session.BackgroundSubagentJobs, restoredAt)
		// A portable backup must never revive a device-specific capability token.
		session.ExecutionHandoff = SessionExecutionHandoff{}
	}
	return desktopStoreBackupSnapshot{Config: config, Sessions: restoredSessions}, nil
}

func buildRestoredWorkflowSnapshot(current savedWorkflowStoreBackupSnapshot, portable desktopSavedWorkflowsBackup) savedWorkflowStoreBackupSnapshot {
	now := time.Now().UnixMilli()
	workflows := cloneSavedWorkflows(portable.Workflows)
	for index := range workflows {
		workflow := &workflows[index]
		if workflow.LastRun != nil && (workflow.LastRun.Status == workflowRunRunning || workflow.LastRun.Status == workflowRunQueued) {
			workflow.LastRun.Status = workflowRunCancelled
			workflow.LastRun.FinishedAt = now
			workflow.LastRun.Summary = "备份恢复中断了此前未完成的运行。"
			workflow.LastRun.FailureReason = "恢复时已安全取消"
		}
		if workflow.Template == workflowProjectReadDiagnostic || workflow.Template == workflowDirectoryChangeSummary {
			if _, err := normalizeExistingProjectPath(workflow.ProjectPath); err != nil {
				workflow.Enabled = false
				workflow.UpdatedAt = now
				workflow.LastRun = &SavedWorkflowRunRecord{
					Status: workflowRunBlocked, FinishedAt: now,
					Summary:       "恢复后未找到原项目目录，工作流已关闭。",
					FailureReason: "请重新选择这台电脑上的项目目录后再启用",
				}
			}
		}
	}
	return savedWorkflowStoreBackupSnapshot{Document: savedWorkflowDocument{
		SchemaVersion: savedWorkflowSchemaVersion,
		GitHub: savedGitHubConfig{
			APIBaseURL:     normalizeGitHubAPIBaseURL(portable.GitHubAPIBaseURL),
			ProtectedToken: current.Document.GitHub.ProtectedToken,
		},
		Workflows: workflows,
	}}
}

func (store *desktopStore) backupSnapshot() desktopStoreBackupSnapshot {
	store.mu.Lock()
	defer store.mu.Unlock()
	config := store.config
	config.Allowlist = append([]string{}, store.config.Allowlist...)
	config.GlobalRules = append([]GlobalRule{}, store.config.GlobalRules...)
	config.GlobalMemories = append([]GlobalMemory{}, store.config.GlobalMemories...)
	config.GlobalSkills = cloneSkills(store.config.GlobalSkills)
	config.ProjectKnowledge = cloneProjectKnowledge(store.config.ProjectKnowledge)
	config.ProviderProfiles = append([]ProviderProfile{}, store.config.ProviderProfiles...)
	config.EnabledBuiltinTools = append([]string{}, store.config.EnabledBuiltinTools...)
	config.EnabledFileOperations = append([]string{}, store.config.EnabledFileOperations...)
	config.ProjectToolPreferences = cloneProjectToolPreferences(store.config.ProjectToolPreferences)
	config.RecentProjects = append([]RecentProjectRecord{}, store.config.RecentProjects...)
	config.MCPServers = cloneMCPServerConfigs(store.config.MCPServers)
	sessions := make([]*ChatSession, 0, len(store.sessions))
	for _, session := range store.sessions {
		sessions = append(sessions, cloneSession(session))
	}
	sort.Slice(sessions, func(i, j int) bool { return sessions[i].UpdatedAt > sessions[j].UpdatedAt })
	return desktopStoreBackupSnapshot{Config: config, Sessions: sessions}
}

func (store *desktopStore) restoreBackupSnapshot(snapshot desktopStoreBackupSnapshot) error {
	store.mu.Lock()
	defer store.mu.Unlock()
	previousConfig := store.config
	if err := writeJSONAtomic(store.configPath, snapshot.Config); err != nil {
		return err
	}
	if err := writeJSONAtomic(store.sessionsPath, snapshot.Sessions); err != nil {
		rollbackErr := writeJSONAtomic(store.configPath, previousConfig)
		if rollbackErr != nil {
			return fmt.Errorf("写入会话失败：%w；配置回滚也失败：%v", err, rollbackErr)
		}
		return err
	}
	store.config = snapshot.Config
	store.sessions = make(map[string]*ChatSession, len(snapshot.Sessions))
	for _, session := range snapshot.Sessions {
		store.sessions[session.ID] = cloneSession(session)
	}
	return nil
}

func (store *savedWorkflowStore) backupSnapshot() savedWorkflowStoreBackupSnapshot {
	store.mu.Lock()
	defer store.mu.Unlock()
	return savedWorkflowStoreBackupSnapshot{Document: cloneSavedWorkflowDocument(store.document)}
}

func (store *savedWorkflowStore) restoreBackupSnapshot(snapshot savedWorkflowStoreBackupSnapshot) error {
	store.mu.Lock()
	defer store.mu.Unlock()
	document := cloneSavedWorkflowDocument(snapshot.Document)
	document.SchemaVersion = savedWorkflowSchemaVersion
	if err := writeJSONAtomic(store.path, document); err != nil {
		return err
	}
	store.document = document
	return nil
}

func cloneSavedWorkflowDocument(document savedWorkflowDocument) savedWorkflowDocument {
	copy := document
	copy.Workflows = cloneSavedWorkflows(document.Workflows)
	return copy
}

func cloneSavedWorkflows(workflows []SavedWorkflowDefinition) []SavedWorkflowDefinition {
	result := make([]SavedWorkflowDefinition, 0, len(workflows))
	for _, workflow := range workflows {
		result = append(result, cloneSavedWorkflow(workflow))
	}
	return result
}

func cloneDesktopSessions(sessions []*ChatSession) []*ChatSession {
	result := make([]*ChatSession, 0, len(sessions))
	for _, session := range sessions {
		if session != nil {
			result = append(result, cloneSession(session))
		}
	}
	sort.Slice(result, func(i, j int) bool { return result[i].UpdatedAt > result[j].UpdatedAt })
	return result
}

func clonePortableDesktopSessions(sessions []*ChatSession) []*ChatSession {
	result := make([]*ChatSession, 0, len(sessions))
	for _, session := range sessions {
		if portable := clonePortableSession(session); portable != nil {
			portable.ExecutionHandoff = SessionExecutionHandoff{}
			result = append(result, portable)
		}
	}
	sort.Slice(result, func(i, j int) bool { return result[i].UpdatedAt > result[j].UpdatedAt })
	return result
}

func cloneDesktopSessionsForBackup(sessions []*ChatSession) []*ChatSession {
	result := cloneDesktopSessions(sessions)
	for _, session := range result {
		session.CodexThreadID = ""
		session.CodexSyncedID = ""
		session.CodexToolsVersion = 0
		session.ExecutionHandoff = SessionExecutionHandoff{}
	}
	return result
}

func matchProviderSecret(existing []ProviderProfile, restored ProviderProfile) *ProviderProfile {
	for index := range existing {
		if existing[index].ID == restored.ID {
			return &existing[index]
		}
	}
	for index := range existing {
		candidate := &existing[index]
		if candidate.ProviderID == restored.ProviderID && strings.EqualFold(candidate.Name, restored.Name) && strings.EqualFold(candidate.BaseURL, restored.BaseURL) && candidate.Model == restored.Model {
			return candidate
		}
	}
	return nil
}

func matchMCPSecrets(existing []MCPServerConfig, restored MCPServerConfig) *MCPServerConfig {
	for index := range existing {
		if existing[index].ID == restored.ID {
			return &existing[index]
		}
	}
	for index := range existing {
		candidate := &existing[index]
		if candidate.Transport == restored.Transport && strings.EqualFold(candidate.Name, restored.Name) {
			return candidate
		}
	}
	return nil
}

func normalizeDesktopBackupSettings(settings DesktopBackupSettings) DesktopBackupSettings {
	if settings.MaxBackupCount < 1 {
		settings.MaxBackupCount = 7
	}
	if settings.MaxBackupCount > 100 {
		settings.MaxBackupCount = 100
	}
	return settings
}
