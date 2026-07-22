package main

import (
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	desktopbridge "github.com/murong-agent/desktop-bridge"
)

func TestCredentialSyncImportsAPIKeyProtectedAndCanRestoreSnapshot(t *testing.T) {
	t.Setenv("MURONG_DESKTOP_DATA_DIR", t.TempDir())
	store, err := newDesktopStore()
	if err != nil {
		t.Fatal(err)
	}
	previous := store.rawConfig()
	key := "sync-secret-value"
	activeProvider, activeProfile := "openai-compatible", "phone-profile"
	bundle := desktopbridge.CredentialSyncBundle{
		SchemaVersion: 1, SourcePlatform: "android", GeneratedAt: time.Now().UnixMilli(),
		ActiveProviderID: &activeProvider, ActiveProfileID: &activeProfile,
		Providers: []desktopbridge.SyncedProviderCredential{{
			ProfileID: activeProfile, ProviderID: activeProvider, Name: "Phone OpenAI",
			BaseURL: "https://example.test/v1", Model: "test-model", ReasoningEffort: "high", APIKey: &key,
		}},
	}
	providers, keys, err := store.importSyncedProviders(bundle)
	if err != nil || providers != 1 || keys != 1 {
		t.Fatalf("unexpected sync result providers=%d keys=%d err=%v", providers, keys, err)
	}
	config := store.rawConfig()
	profile := findProviderProfile(config.ProviderProfiles, activeProfile)
	if profile == nil || profile.ProtectedAPIKey == "" {
		t.Fatal("synced provider API key was not protected")
	}
	plain, err := unprotectSecret(profile.ProtectedAPIKey)
	if err != nil || string(plain) != key {
		t.Fatalf("unexpected protected API key: %q, %v", plain, err)
	}
	clearBytes(plain)
	persisted, err := os.ReadFile(store.configPath)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(persisted), key) {
		t.Fatal("synced API key was persisted in plaintext")
	}
	if err := store.restoreCredentialSyncConfig(previous); err != nil {
		t.Fatal(err)
	}
	if findProviderProfile(store.rawConfig().ProviderProfiles, activeProfile) != nil {
		t.Fatal("credential sync configuration rollback did not restore the snapshot")
	}
}

func TestCredentialSyncAcceptsAndroidOfficialProviderWithoutBaseURL(t *testing.T) {
	t.Setenv("MURONG_DESKTOP_DATA_DIR", t.TempDir())
	store, err := newDesktopStore()
	if err != nil {
		t.Fatal(err)
	}
	activeProvider, activeProfile := providerDeepSeek, "android-official-deepseek"
	bundle := desktopbridge.CredentialSyncBundle{
		SchemaVersion: 4, SourcePlatform: "android", GeneratedAt: time.Now().UnixMilli(),
		ActiveProviderID: &activeProvider, ActiveProfileID: &activeProfile,
		Providers: []desktopbridge.SyncedProviderCredential{{
			ProfileID: activeProfile, ProviderID: providerDeepSeek, Name: "DeepSeek 官方",
			BaseURL: "", Model: "deepseek-chat", ReasoningEffort: "high",
		}},
	}
	if err := validateCredentialBundle(bundle); err != nil {
		t.Fatalf("Android official provider bundle was rejected: %v", err)
	}
	providers, _, err := store.importSyncedProviders(bundle)
	if err != nil || providers != 1 {
		t.Fatalf("unexpected import result providers=%d err=%v", providers, err)
	}
	profile := findProviderProfile(store.rawConfig().ProviderProfiles, activeProfile)
	if profile == nil || profile.BaseURL != defaultProviderProfile(providerDeepSeek).BaseURL {
		t.Fatalf("official provider did not resolve to the desktop default: %#v", profile)
	}
}

func TestCredentialSyncExportsAPIKeyAndCodexLoginForEncryptedDeviceTransfer(t *testing.T) {
	t.Setenv("MURONG_DESKTOP_DATA_DIR", t.TempDir())
	store, err := newDesktopStore()
	if err != nil {
		t.Fatal(err)
	}
	apiKey := "provider-secret-for-phone"
	protected, err := protectSecret([]byte(apiKey))
	if err != nil {
		t.Fatal(err)
	}
	store.mu.Lock()
	store.config.ProviderProfiles[0].ProtectedAPIKey = protected
	store.config.ProtectedAPIKey = protected
	store.config.PlannerProfileEnabled = true
	store.config.PlannerModel = "desktop-planner"
	store.config.PlannerReasoningEffort = "xhigh"
	store.config.SubagentProfileEnabled = true
	store.config.SubagentModel = "desktop-child"
	store.config.SubagentReasoningEffort = "medium"
	store.mu.Unlock()

	codexHome := filepath.Join(t.TempDir(), "codex-home")
	authJSON := []byte(`{"auth_mode":"chatgpt","tokens":{"access_token":"access-secret","refresh_token":"refresh-secret"}}`)
	if err := writeCodexAuthJSON(codexHome, authJSON); err != nil {
		t.Fatal(err)
	}
	workflowStore, err := newSavedWorkflowStore(filepath.Join(t.TempDir(), "workflows.json"))
	if err != nil {
		t.Fatal(err)
	}
	githubToken := "github-secret-for-phone"
	if err := workflowStore.saveGitHub(SaveGitHubConfigRequest{APIBaseURL: "https://github.example/api/v3", Token: githubToken}); err != nil {
		t.Fatal(err)
	}
	workflows := newSavedWorkflowManager(workflowStore, store)
	workflows.viewer = "murong-user"
	app := &DesktopAgentApp{store: store, codex: &codexAppServer{codexHome: codexHome}, workflows: workflows}
	bundle, err := app.exportCredentialBundle(SyncCredentialsRequest{
		IncludeProviderCredentials: true,
		IncludeCodexLogin:          true,
		IncludeGitHubCredentials:   true,
		IncludeAgentSettings:       true,
	})
	if err != nil {
		t.Fatal(err)
	}
	if len(bundle.Providers) != 1 || bundle.Providers[0].APIKey == nil || *bundle.Providers[0].APIKey != apiKey {
		t.Fatalf("API Key was not exported for the encrypted device channel: %#v", bundle.Providers)
	}
	if bundle.CodexAuthJSON == nil || !strings.Contains(*bundle.CodexAuthJSON, "refresh-secret") {
		t.Fatal("Codex login tokens were not exported for the encrypted device channel")
	}
	if bundle.SchemaVersion != 6 || bundle.GitHub == nil || bundle.GitHub.Token == nil || *bundle.GitHub.Token != githubToken || bundle.GitHub.ViewerLogin != "murong-user" {
		t.Fatalf("GitHub login was not exported for the encrypted device channel: %#v", bundle.GitHub)
	}
	if bundle.SchemaVersion != 6 || bundle.AgentSettings == nil || bundle.AgentSettings.PlannerProfileEnabled == nil || !*bundle.AgentSettings.PlannerProfileEnabled || bundle.AgentSettings.PlannerModel == nil || *bundle.AgentSettings.PlannerModel != "desktop-planner" || bundle.AgentSettings.PlannerReasoningEffort == nil || *bundle.AgentSettings.PlannerReasoningEffort != "xhigh" || bundle.AgentSettings.SubagentProfileEnabled == nil || !*bundle.AgentSettings.SubagentProfileEnabled || bundle.AgentSettings.SubagentModel == nil || *bundle.AgentSettings.SubagentModel != "desktop-child" || bundle.AgentSettings.SubagentReasoningEffort == nil || *bundle.AgentSettings.SubagentReasoningEffort != "medium" {
		t.Fatalf("execution profiles were not exported: %#v", bundle.AgentSettings)
	}
	clearCredentialBundle(&bundle)
	if bundle.Providers[0].APIKey != nil || bundle.CodexAuthJSON != nil || bundle.GitHub.Token != nil {
		t.Fatal("device-sync secrets were not cleared after transfer")
	}
}

func TestCredentialSyncValidationRejectsStaleAndMalformedBundles(t *testing.T) {
	stale := desktopbridge.CredentialSyncBundle{
		SchemaVersion: 1, SourcePlatform: "android", GeneratedAt: time.Now().Add(-10 * time.Minute).UnixMilli(),
	}
	if err := validateCredentialBundle(stale); err == nil {
		t.Fatal("stale credential bundle was accepted")
	}
	malformed := desktopbridge.CredentialSyncBundle{
		SchemaVersion: 1, SourcePlatform: "android", GeneratedAt: time.Now().UnixMilli(),
		Providers: []desktopbridge.SyncedProviderCredential{{
			ProfileID: "profile", ProviderID: "openai", BaseURL: "https://example.test/v1",
			Model: "model", ReasoningEffort: "ultra",
		}},
	}
	if err := validateCredentialBundle(malformed); err == nil {
		t.Fatal("credential bundle with unsupported reasoning effort was accepted")
	}
	invalidGitHub := desktopbridge.CredentialSyncBundle{
		SchemaVersion: 3, SourcePlatform: "android", GeneratedAt: time.Now().UnixMilli(),
		GitHub: &desktopbridge.SyncedGitHubCredential{APIBaseURL: "file:///windows/system32", Token: stringPointer("secret")},
	}
	if err := validateCredentialBundle(invalidGitHub); err == nil {
		t.Fatal("credential bundle with unsafe GitHub API URL was accepted")
	}
}

func TestDeviceSyncImportsPortableSettingsKnowledgeAndEncryptedMCPSecrets(t *testing.T) {
	t.Setenv("MURONG_DESKTOP_DATA_DIR", t.TempDir())
	store, err := newDesktopStore()
	if err != nil {
		t.Fatal(err)
	}
	secret := "mcp-device-sync-secret"
	temperature := 0.35
	maxTokens := 5678
	enableMultimodal := false
	plannerEnabled := true
	plannerModel, plannerReasoning := "phone-planner", "xhigh"
	subagentEnabled := true
	subagentModel, subagentReasoning := "phone-child", "medium"
	bundle := desktopbridge.CredentialSyncBundle{
		SchemaVersion: 2, SourcePlatform: "android", GeneratedAt: time.Now().UnixMilli(),
		AgentSettings: &desktopbridge.SyncedAgentSettings{
			ApprovalMode: approvalYolo, SystemPrompt: "portable prompt", ResponseVerbosity: "detailed",
			Temperature: &temperature, MaxTokens: &maxTokens, EnableMultimodalMessages: &enableMultimodal,
			PlannerProfileEnabled: &plannerEnabled, PlannerModel: &plannerModel, PlannerReasoningEffort: &plannerReasoning,
			SubagentProfileEnabled: &subagentEnabled, SubagentModel: &subagentModel, SubagentReasoningEffort: &subagentReasoning,
		},
		Knowledge: &desktopbridge.SyncedKnowledge{
			Rules:    []desktopbridge.SyncedRule{{ID: "rule-phone", Title: "Phone rule", Content: "rule body", Enabled: true}},
			Memories: []desktopbridge.SyncedMemory{{ID: "memory-phone", Title: "Phone memory", Content: "memory body", Enabled: true}},
			Skills: []desktopbridge.SyncedSkill{{
				ID: "skill-phone", Title: "Phone skill", Content: "skill body", RunAs: "INLINE", Enabled: true,
			}},
		},
		MCPServers: []desktopbridge.SyncedMCPServer{
			{
				ID: "remote", Name: "Remote MCP", Transport: mcpTransportStreamableHTTP,
				URL: "https://mcp.example.test/api", RequestTimeoutSeconds: 60, Enabled: true, AutoStart: true,
				Headers: map[string]string{"Authorization": secret},
			},
			{
				ID: "stdio", Name: "Phone stdio", Transport: mcpTransportStdio,
				Command: "sh", Args: []string{"server.sh"}, RequestTimeoutSeconds: 60, Enabled: true, AutoStart: true,
			},
		},
		MCPCredentialsIncluded: true,
	}
	if err := validateCredentialBundle(bundle); err != nil {
		t.Fatal(err)
	}
	result, err := store.importSyncedPortableState(bundle)
	if err != nil {
		t.Fatal(err)
	}
	if !result.ImportedSettings || result.ImportedRules != 1 || result.ImportedMemories != 1 ||
		result.ImportedSkills != 1 || result.ImportedMCPServers != 2 || result.DisabledMCPServers != 1 {
		t.Fatalf("unexpected portable import result: %#v", result)
	}
	config := store.rawConfig()
	if config.ApprovalMode != approvalYolo || config.SystemPrompt != "portable prompt" || config.ResponseVerbosity != "DETAILED" || config.Temperature != temperature || config.MaxTokens != maxTokens || config.EnableMultimodalMessages || !config.PlannerProfileEnabled || config.PlannerModel != plannerModel || config.PlannerReasoningEffort != plannerReasoning || !config.SubagentProfileEnabled || config.SubagentModel != subagentModel || config.SubagentReasoningEffort != subagentReasoning {
		t.Fatalf("portable settings were not imported: %#v", config)
	}
	if len(config.GlobalRules) != 1 || len(config.GlobalMemories) != 1 || len(config.GlobalSkills) != 1 {
		t.Fatalf("portable knowledge was not imported: %#v", config)
	}
	if len(config.MCPServers) != 2 || config.MCPServers[1].Enabled || config.MCPServers[1].AutoStart {
		t.Fatalf("cross-platform stdio MCP was not disabled: %#v", config.MCPServers)
	}
	headers, err := unprotectMCPSecretMap(config.MCPServers[0].ProtectedHeadersJSON)
	if err != nil || headers["Authorization"] != secret {
		t.Fatalf("MCP credential was not protected: %#v, %v", headers, err)
	}
	persisted, err := os.ReadFile(store.configPath)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(persisted), secret) {
		t.Fatal("MCP device-sync credential was persisted in plaintext")
	}
}

func TestDeviceSyncImportsPortableWorkflowsDisabledAndSkipsPathBoundTemplates(t *testing.T) {
	current := savedWorkflowStoreBackupSnapshot{Document: savedWorkflowDocument{
		SchemaVersion: savedWorkflowSchemaVersion,
		GitHub:        savedGitHubConfig{APIBaseURL: "https://api.github.com", ProtectedToken: "keep-local-token"},
		Workflows:     []SavedWorkflowDefinition{},
	}}
	incoming := []desktopbridge.SyncedSavedWorkflow{
		{
			ID: "workflow-github", Name: "GitHub", Template: workflowGitHubActionsStatus,
			GitHubRepository: "owner/repository", IntervalMinutes: 60, CreatedAt: 1, UpdatedAt: 2,
			Nodes: []desktopbridge.SyncedWorkflowNode{{
				ID: "query", Label: "Query", RequiredPermission: workflowPermissionNetworkRead, TimeoutSeconds: 60,
			}},
		},
		{
			ID: "workflow-path", Name: "Path", Template: workflowProjectReadDiagnostic,
			IntervalMinutes: 60, CreatedAt: 1, UpdatedAt: 2,
		},
	}
	githubToken := "synced-github-token"
	withGitHub, importedToken, err := importSyncedGitHubCredential(current, desktopbridge.SyncedGitHubCredential{
		APIBaseURL: "https://github.example/api/v3", Token: &githubToken, ViewerLogin: "phone-user",
	})
	if err != nil || !importedToken {
		t.Fatalf("GitHub credential import failed: imported=%t err=%v", importedToken, err)
	}
	restored, imported, skipped := importSyncedWorkflows(withGitHub, incoming)
	if imported != 1 || skipped != 1 || len(restored.Document.Workflows) != 1 {
		t.Fatalf("unexpected workflow import: imported=%d skipped=%d %#v", imported, skipped, restored)
	}
	plain, err := unprotectSecret(restored.Document.GitHub.ProtectedToken)
	if err != nil {
		t.Fatal(err)
	}
	defer clearBytes(plain)
	if restored.Document.Workflows[0].Enabled || string(plain) != githubToken || restored.Document.GitHub.APIBaseURL != "https://github.example/api/v3" {
		t.Fatalf("workflow import enabled remote work or lost the synced GitHub credential: %#v", restored)
	}
}

func TestDeviceSyncGitHubImportWithoutTokenPreservesTargetCredential(t *testing.T) {
	originalToken := "target-only-github-token"
	protected, err := protectSecret([]byte(originalToken))
	if err != nil {
		t.Fatal(err)
	}
	current := savedWorkflowStoreBackupSnapshot{Document: savedWorkflowDocument{
		SchemaVersion: savedWorkflowSchemaVersion,
		GitHub: savedGitHubConfig{
			APIBaseURL:     "https://api.github.com",
			ProtectedToken: protected,
		},
	}}

	restored, imported, err := importSyncedGitHubCredential(current, desktopbridge.SyncedGitHubCredential{
		APIBaseURL: "https://github.example/api/v3",
	})
	if err != nil {
		t.Fatal(err)
	}
	if imported {
		t.Fatal("an absent source token was reported as imported")
	}
	plain, err := unprotectSecret(restored.Document.GitHub.ProtectedToken)
	if err != nil {
		t.Fatal(err)
	}
	defer clearBytes(plain)
	if string(plain) != originalToken || restored.Document.GitHub.APIBaseURL != "https://github.example/api/v3" {
		t.Fatalf("target GitHub credential was not preserved: %#v", restored.Document.GitHub)
	}
}

func TestDeviceSyncRollbackRestoresGitHubCredentialSnapshot(t *testing.T) {
	t.Setenv("MURONG_DESKTOP_DATA_DIR", t.TempDir())
	store, err := newDesktopStore()
	if err != nil {
		t.Fatal(err)
	}
	workflowStore, err := newSavedWorkflowStore(filepath.Join(t.TempDir(), "workflows.json"))
	if err != nil {
		t.Fatal(err)
	}
	if err := workflowStore.saveGitHub(SaveGitHubConfigRequest{
		APIBaseURL: "https://github.original/api/v3",
		Token:      "original-token",
	}); err != nil {
		t.Fatal(err)
	}
	manager := newSavedWorkflowManager(workflowStore, store)
	app := &DesktopAgentApp{store: store, workflows: manager}
	previousConfig := store.rawConfig()
	previousWorkflows := workflowStore.backupSnapshot()

	if err := workflowStore.saveGitHub(SaveGitHubConfigRequest{
		APIBaseURL: "https://github.synced/api/v3",
		Token:      "synced-token",
	}); err != nil {
		t.Fatal(err)
	}
	cause := errors.New("force rollback after a later import failure")
	if rollbackErr := app.rollbackDeviceSync(previousConfig, previousWorkflows, cause); !errors.Is(rollbackErr, cause) {
		t.Fatalf("rollback lost its original cause: %v", rollbackErr)
	}
	restored, err := workflowStore.runtimeGitHub()
	if err != nil {
		t.Fatal(err)
	}
	if restored.APIBaseURL != "https://github.original/api/v3" || restored.Token != "original-token" {
		t.Fatalf("GitHub credential rollback failed: %#v", restored)
	}
}
