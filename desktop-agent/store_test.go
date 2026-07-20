package main

import (
	"os"
	"path/filepath"
	"testing"
)

func TestDesktopStorePersistsProtectedSettingsAndSessions(t *testing.T) {
	t.Setenv("MURONG_DESKTOP_DATA_DIR", t.TempDir())
	project := t.TempDir()
	store, err := newDesktopStore()
	if err != nil {
		t.Fatal(err)
	}

	public, err := store.saveSettings(SaveSettingsRequest{
		ProjectPath: project, BaseURL: "https://example.test/v1/", Model: "test-model",
		APIKey: "secret-value", ApprovalMode: approvalYolo, MaxToolIterations: 999,
		SystemPrompt: "custom desktop prompt", ResponseVerbosity: "DETAILED",
		Temperature: 0.25, MaxTokens: 4321, EnableMultimodalMessages: true,
		PlannerProfileEnabled: true, PlannerModel: "planner-model", PlannerReasoningEffort: "xhigh",
		SubagentProfileEnabled: true, SubagentModel: "child-model", SubagentReasoningEffort: "medium",
	})
	if err != nil {
		t.Fatal(err)
	}
	if !public.HasAPIKey || public.MaxToolIterations != 999 || public.BaseURL != "https://example.test/v1" || public.ResponseVerbosity != "DETAILED" || public.Temperature != 0.25 || public.MaxTokens != 4321 || !public.EnableMultimodalMessages || !public.PlannerProfileEnabled || public.PlannerModel != "planner-model" || public.PlannerReasoningEffort != "xhigh" || !public.SubagentProfileEnabled || public.SubagentModel != "child-model" || public.SubagentReasoningEffort != "medium" {
		t.Fatalf("unexpected public config: %#v", public)
	}
	_, err = store.saveKnowledgeLibrary(KnowledgeLibrary{
		Rules:    []GlobalRule{{ID: "rule-test", Title: "规则", Content: "先验证", Enabled: true}},
		Memories: []GlobalMemory{{ID: "memory-test", Title: "机器", Content: "构建较慢", Enabled: true}},
		Skills: []GlobalSkill{{
			ID: "skill-test", Title: "审查", Description: "审查改动", Content: "先读 diff", RunAs: "SUBAGENT",
			AllowedTools: []string{"read_file", "run_terminal"}, PreferredModel: "review-model", Enabled: true,
		}},
	})
	if err != nil {
		t.Fatal(err)
	}
	projectKnowledge, err := store.saveProjectKnowledgeLibrary(KnowledgeLibrary{
		Rules:    []GlobalRule{{ID: "project-rule", Title: "项目规则", Content: "先跑项目测试", Enabled: true}},
		Memories: []GlobalMemory{{ID: "project-memory", Title: "项目背景", Content: "这是 Windows 项目", Enabled: true}},
		Skills:   []GlobalSkill{{ID: "project-skill", Title: "项目审查", Content: "检查 diff", RunAs: "INLINE", Enabled: true}},
	})
	if err != nil || !projectKnowledge.HasProject || projectKnowledge.ProjectPath != project {
		t.Fatalf("unexpected project knowledge snapshot: %#v, %v", projectKnowledge, err)
	}
	data, err := os.ReadFile(store.configPath)
	if err != nil {
		t.Fatal(err)
	}
	if string(data) == "" || containsBytes(data, []byte("secret-value")) {
		t.Fatal("API key was not persisted as protected data")
	}

	session, err := store.createSession("")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := store.appendMessage(session.ID, ChatMessage{Role: "user", Content: "  hello desktop  "}); err != nil {
		t.Fatal(err)
	}

	reloaded, err := newDesktopStore()
	if err != nil {
		t.Fatal(err)
	}
	key, err := reloaded.apiKey()
	if err != nil || key != "secret-value" {
		t.Fatalf("unexpected reloaded key %q: %v", key, err)
	}
	loaded := reloaded.getSession(session.ID)
	if loaded == nil || loaded.Title != "hello desktop" || len(loaded.Messages) != 1 {
		t.Fatalf("unexpected reloaded session: %#v", loaded)
	}
	if config := reloaded.publicConfig(); config.SystemPrompt != "custom desktop prompt" || config.ResponseVerbosity != "DETAILED" || config.Temperature != 0.25 || config.MaxTokens != 4321 || !config.EnableMultimodalMessages || !config.PlannerProfileEnabled || config.PlannerModel != "planner-model" || config.PlannerReasoningEffort != "xhigh" || !config.SubagentProfileEnabled || config.SubagentModel != "child-model" || config.SubagentReasoningEffort != "medium" {
		t.Fatalf("system behavior was not reloaded: %#v", config)
	}
	knowledge := reloaded.knowledgeLibrary()
	if len(knowledge.Rules) != 1 || len(knowledge.Memories) != 1 || len(knowledge.Skills) != 1 || knowledge.Skills[0].RunAs != "SUBAGENT" || len(knowledge.Skills[0].AllowedTools) != 2 {
		t.Fatalf("knowledge library was not reloaded: %#v", knowledge)
	}
	projectReloaded := reloaded.projectKnowledge()
	if !projectReloaded.HasProject || len(projectReloaded.Library.Rules) != 1 || len(projectReloaded.Library.Memories) != 1 || len(projectReloaded.Library.Skills) != 1 {
		t.Fatalf("project knowledge was not reloaded: %#v", projectReloaded)
	}
}

func TestDesktopStoreRejectsInvalidBaseURL(t *testing.T) {
	store := &desktopStore{
		configPath: filepath.Join(t.TempDir(), "config.json"), sessionsPath: filepath.Join(t.TempDir(), "sessions.json"),
		config: defaultDesktopConfig(), sessions: map[string]*ChatSession{},
	}
	_, err := store.saveSettings(SaveSettingsRequest{BaseURL: "file:///etc/passwd", Model: "model", ApprovalMode: approvalAskAll, MaxToolIterations: 999})
	if err == nil {
		t.Fatal("expected invalid Base URL to be rejected")
	}
}

func TestDesktopStoreMigratesLegacyProviderAndProtectsEachProfileKey(t *testing.T) {
	t.Setenv("MURONG_DESKTOP_DATA_DIR", t.TempDir())
	store, err := newDesktopStore()
	if err != nil {
		t.Fatal(err)
	}
	legacyProtected, err := protectSecret([]byte("legacy-secret"))
	if err != nil {
		t.Fatal(err)
	}
	legacy := desktopConfig{
		SchemaVersion: 1, BaseURL: "https://legacy.example/v1", Model: "legacy-model", ProtectedAPIKey: legacyProtected,
		ApprovalMode: approvalYolo, MaxToolIterations: 999,
	}
	if err := writeJSONAtomic(store.configPath, legacy); err != nil {
		t.Fatal(err)
	}
	reloaded, err := newDesktopStore()
	if err != nil {
		t.Fatal(err)
	}
	config := reloaded.publicConfig()
	if config.ActiveProviderProfileID != "provider_legacy" || len(config.ProviderProfiles) != 1 || !config.ProviderProfiles[0].HasAPIKey || config.Model != "legacy-model" {
		t.Fatalf("legacy provider was not migrated: %#v", config)
	}
	if config.Temperature != 0.7 || config.MaxTokens != 8192 || !config.EnableMultimodalMessages || config.PlannerProfileEnabled || config.SubagentProfileEnabled {
		t.Fatalf("legacy generation settings were not migrated: %#v", config)
	}

	updated, err := reloaded.saveSettings(SaveSettingsRequest{
		ApprovalMode: approvalYolo, MaxToolIterations: 999,
		SystemPrompt: defaultDesktopSystemPrompt, ResponseVerbosity: "BALANCED",
		ActiveProviderProfileID: "provider_claude",
		ProviderProfiles: []SaveProviderProfile{
			{ID: "provider_legacy", ProviderID: providerOpenAI, Name: "Legacy", BaseURL: "https://legacy.example/v1", Model: "legacy-model"},
			{ID: "provider_claude", ProviderID: providerClaude, Name: "Claude relay", BaseURL: "https://claude.example", Model: "claude-model", ReasoningEffort: "high", APIKey: "claude-secret"},
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	if updated.ActiveProviderProfileID != "provider_claude" || len(updated.ProviderProfiles) != 2 || !updated.ProviderProfiles[0].HasAPIKey || !updated.ProviderProfiles[1].HasAPIKey || updated.Model != "claude-model" {
		t.Fatalf("unexpected provider profiles: %#v", updated)
	}
	data, err := os.ReadFile(reloaded.configPath)
	if err != nil {
		t.Fatal(err)
	}
	if containsBytes(data, []byte("legacy-secret")) || containsBytes(data, []byte("claude-secret")) {
		t.Fatal("provider API keys were persisted as plaintext")
	}
	key, err := reloaded.apiKey()
	if err != nil || key != "claude-secret" {
		t.Fatalf("unexpected active provider key %q: %v", key, err)
	}
}

func TestDesktopStoreRejectsDuplicateProviderProfileIDs(t *testing.T) {
	t.Setenv("MURONG_DESKTOP_DATA_DIR", t.TempDir())
	store, err := newDesktopStore()
	if err != nil {
		t.Fatal(err)
	}
	_, err = store.saveSettings(SaveSettingsRequest{
		ApprovalMode: approvalAskAll, MaxToolIterations: 999,
		SystemPrompt: defaultDesktopSystemPrompt, ResponseVerbosity: "BALANCED",
		ActiveProviderProfileID: "duplicate",
		ProviderProfiles: []SaveProviderProfile{
			{ID: "duplicate", ProviderID: providerOpenAI, BaseURL: "https://one.example/v1", Model: "one"},
			{ID: "duplicate", ProviderID: providerClaude, BaseURL: "https://two.example", Model: "two"},
		},
	})
	if err == nil {
		t.Fatal("expected duplicate profile IDs to be rejected")
	}
}

func TestDesktopStoreActivatesProviderProfileWithoutRewritingSettings(t *testing.T) {
	t.Setenv("MURONG_DESKTOP_DATA_DIR", t.TempDir())
	store, err := newDesktopStore()
	if err != nil {
		t.Fatal(err)
	}
	updated, err := store.saveSettings(SaveSettingsRequest{
		ApprovalMode: approvalYolo, MaxToolIterations: 999,
		SystemPrompt: defaultDesktopSystemPrompt, ResponseVerbosity: "BALANCED",
		ActiveProviderProfileID: "provider_one",
		ProviderProfiles: []SaveProviderProfile{
			{ID: "provider_one", ProviderID: providerOpenAI, Name: "One", BaseURL: "https://one.example/v1", Model: "model-one", APIKey: "one-key"},
			{ID: "provider_two", ProviderID: providerClaude, Name: "Two", BaseURL: "https://two.example", Model: "model-two", APIKey: "two-key"},
		},
	})
	if err != nil || updated.ActiveProviderProfileID != "provider_one" {
		t.Fatalf("provider setup failed: %#v, %v", updated, err)
	}
	activated, err := store.activateProviderProfile("provider_two")
	if err != nil {
		t.Fatal(err)
	}
	if activated.ActiveProviderProfileID != "provider_two" || activated.Model != "model-two" || activated.ApprovalMode != approvalYolo || !activated.HasAPIKey {
		t.Fatalf("unexpected activated provider: %#v", activated)
	}
	key, err := store.apiKey()
	if err != nil || key != "two-key" {
		t.Fatalf("active provider secret mismatch: %q, %v", key, err)
	}
}

func TestDesktopStoreUpdatesProviderReasoningWithoutTouchingSecretsOrOtherSettings(t *testing.T) {
	t.Setenv("MURONG_DESKTOP_DATA_DIR", t.TempDir())
	store, err := newDesktopStore()
	if err != nil {
		t.Fatal(err)
	}
	_, err = store.saveSettings(SaveSettingsRequest{
		ApprovalMode: approvalYolo, MaxToolIterations: 999,
		SystemPrompt: defaultDesktopSystemPrompt, ResponseVerbosity: "BALANCED",
		ActiveProviderProfileID: "provider_one",
		ProviderProfiles: []SaveProviderProfile{
			{ID: "provider_one", ProviderID: providerOpenAI, Name: "One", BaseURL: "https://one.example/v1", Model: "model-one", ReasoningEffort: "high", APIKey: "one-key"},
			{ID: "provider_two", ProviderID: providerClaude, Name: "Two", BaseURL: "https://two.example", Model: "model-two", ReasoningEffort: "medium", APIKey: "two-key"},
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	updated, err := store.setProviderReasoningEffort("provider_one", "xhigh")
	if err != nil {
		t.Fatal(err)
	}
	if updated.ApprovalMode != approvalYolo || updated.MaxToolIterations != 999 || updated.ProviderProfiles[0].ReasoningEffort != "xhigh" || updated.ProviderProfiles[1].ReasoningEffort != "medium" {
		t.Fatalf("reasoning update rewrote unrelated settings: %#v", updated)
	}
	key, err := store.apiKey()
	if err != nil || key != "one-key" {
		t.Fatalf("reasoning update changed the encrypted key: %q, %v", key, err)
	}
	if _, err := store.setProviderReasoningEffort("provider_one", "ultra"); err == nil {
		t.Fatal("invalid reasoning effort was accepted")
	}
	reloaded, err := newDesktopStore()
	if err != nil {
		t.Fatal(err)
	}
	if reloaded.rawConfig().ProviderProfiles[0].ReasoningEffort != "xhigh" {
		t.Fatal("reasoning effort was not persisted")
	}
}

func TestDesktopStorePersistsAndResolvesProjectToolPreferences(t *testing.T) {
	t.Setenv("MURONG_DESKTOP_DATA_DIR", t.TempDir())
	project := t.TempDir()
	store, err := newDesktopStore()
	if err != nil {
		t.Fatal(err)
	}
	config, err := store.saveSettings(SaveSettingsRequest{
		ProjectPath: project, BaseURL: "https://example.test/v1", Model: "model",
		ApprovalMode: approvalAskAll, MaxToolIterations: 999,
		SystemPrompt: defaultDesktopSystemPrompt, ResponseVerbosity: "BALANCED",
		EnabledBuiltinTools: []string{"file", "shell"}, EnabledFileOperations: []string{"read", "list"},
		SaveProjectToolPreferences: true,
		ProjectTools: ToolPreferences{
			ApprovalMode: approvalYolo, Allowlist: []string{},
			EnabledBuiltinTools: []string{"shell"}, EnabledFileOperations: []string{},
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	if len(config.EnabledBuiltinTools) != 2 || len(config.EnabledFileOperations) != 2 {
		t.Fatalf("unexpected global tool config: %#v", config)
	}
	snapshot := store.projectToolPreferences()
	if snapshot.UsesGlobal || snapshot.Preferences.ApprovalMode != approvalYolo || len(snapshot.Preferences.EnabledBuiltinTools) != 1 || snapshot.Preferences.EnabledBuiltinTools[0] != "shell" || len(snapshot.Preferences.EnabledFileOperations) != 0 {
		t.Fatalf("unexpected project tool preferences: %#v", snapshot)
	}
	resolved := resolvedToolConfig(store.rawConfig())
	if resolved.ApprovalMode != approvalYolo || !isBuiltinToolEnabled(resolved, "shell") || isBuiltinToolEnabled(resolved, "file") {
		t.Fatalf("project tool preferences were not resolved: %#v", resolved)
	}

	reloaded, err := newDesktopStore()
	if err != nil {
		t.Fatal(err)
	}
	if got := reloaded.projectToolPreferences(); got.UsesGlobal || got.Preferences.ApprovalMode != approvalYolo {
		t.Fatalf("project tool preferences were not reloaded: %#v", got)
	}
}

func TestProjectSubagentTemplatesPersistWhileToolPolicyInheritsGlobal(t *testing.T) {
	t.Setenv("MURONG_DESKTOP_DATA_DIR", t.TempDir())
	project := t.TempDir()
	store, err := newDesktopStore()
	if err != nil {
		t.Fatal(err)
	}
	template := ProjectSubagentTemplate{
		ID: "project-review", Title: "项目审查", Description: "审查当前项目", GoalMatchers: []string{"审查", "review"},
		PreferredModel: "review-model", PreferredReasoningEffort: "high", EnableWebSearch: false,
		AllowCodeEdits: true, Enabled: true,
	}
	_, err = store.saveSettings(SaveSettingsRequest{
		ProjectPath: project, BaseURL: "https://example.test/v1", Model: "model", ApprovalMode: approvalAskAll,
		MaxToolIterations: 999, SystemPrompt: defaultDesktopSystemPrompt, ResponseVerbosity: "BALANCED",
		EnabledBuiltinTools: append([]string{}, defaultEnabledBuiltinTools...), EnabledFileOperations: append([]string{}, defaultEnabledFileOperations...),
		SaveProjectToolPreferences: true, UseGlobalProjectTools: true,
		ProjectTools: ToolPreferences{SubagentTemplates: []ProjectSubagentTemplate{template}},
	})
	if err != nil {
		t.Fatal(err)
	}
	snapshot := store.projectToolPreferences()
	if !snapshot.UsesGlobal || len(snapshot.Preferences.SubagentTemplates) != 1 || snapshot.Preferences.SubagentTemplates[0].ID != template.ID {
		t.Fatalf("template was not retained beside inherited tools: %#v", snapshot)
	}
	stored := store.rawConfig().ProjectToolPreferences[projectKnowledgeKey(project)]
	if !stored.InheritGlobal || len(stored.SubagentTemplates) != 1 {
		t.Fatalf("unexpected persisted project preference: %#v", stored)
	}
	resolved := resolvedToolConfig(store.rawConfig())
	if resolved.ApprovalMode != approvalAskAll || !isBuiltinToolEnabled(resolved, "shell") {
		t.Fatalf("template entry replaced inherited global tool policy: %#v", resolved)
	}
	reloaded, err := newDesktopStore()
	if err != nil {
		t.Fatal(err)
	}
	if got := reloaded.projectToolPreferences(); !got.UsesGlobal || len(got.Preferences.SubagentTemplates) != 1 {
		t.Fatalf("template was not reloaded: %#v", got)
	}
}

func TestProjectSubagentTemplateValidationRejectsUnsafeMetadata(t *testing.T) {
	if err := validateProjectSubagentTemplates([]ProjectSubagentTemplate{{ID: "bad/id", Title: "Bad", Enabled: true}}); err == nil {
		t.Fatal("unsafe template ID was accepted")
	}
	if err := validateProjectSubagentTemplates([]ProjectSubagentTemplate{{ID: "same", Title: "One"}, {ID: "same", Title: "Two"}}); err == nil {
		t.Fatal("duplicate template IDs were accepted")
	}
	if err := validateProjectSubagentTemplates([]ProjectSubagentTemplate{{ID: "valid", Title: "Valid", PreferredReasoningEffort: "ultra"}}); err == nil {
		t.Fatal("unsupported template reasoning effort was accepted")
	}
}

func containsBytes(data, needle []byte) bool {
	if len(needle) == 0 || len(data) < len(needle) {
		return false
	}
	for index := 0; index <= len(data)-len(needle); index++ {
		match := true
		for offset := range needle {
			if data[index+offset] != needle[offset] {
				match = false
				break
			}
		}
		if match {
			return true
		}
	}
	return false
}
