package main

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"

	desktopbridge "github.com/murong-agent/desktop-bridge"
)

const (
	desktopBackupPortableStatePath     = "state/portable-state.json"
	desktopPortableBackupSchemaVersion = 1
	desktopPortableBackupMaxSessions   = 5_000
	maxPortableBackupSessionIDBytes    = 500
)

type desktopPortableBackupSession struct {
	SourceSessionID string          `json:"sourceSessionId"`
	Document        json.RawMessage `json:"document"`
}

type desktopCrossPlatformBackupState struct {
	SchemaVersion  int                                `json:"schemaVersion"`
	SourcePlatform string                             `json:"sourcePlatform"`
	GeneratedAt    int64                              `json:"generatedAt"`
	DeviceState    desktopbridge.CredentialSyncBundle `json:"deviceState"`
	Sessions       []desktopPortableBackupSession     `json:"sessions"`
}

type desktopCrossPlatformRestoreResult struct {
	ImportedSessions  int
	ConflictCopies    int
	SkippedSessions   int
	ImportedProviders int
	ImportedWorkflows int
	SkippedWorkflows  int
}

func buildDesktopCrossPlatformBackupState(
	store desktopStoreBackupSnapshot,
	workflows savedWorkflowStoreBackupSnapshot,
	generatedAt int64,
) (desktopCrossPlatformBackupState, error) {
	if generatedAt <= 0 {
		generatedAt = time.Now().UnixMilli()
	}
	config := store.Config
	bundle := desktopbridge.CredentialSyncBundle{
		SchemaVersion: 4, SourcePlatform: desktopSourcePlatform(), GeneratedAt: generatedAt,
		Providers:              []desktopbridge.SyncedProviderCredential{},
		AgentSettings:          portableBackupAgentSettings(config),
		Knowledge:              exportSyncedKnowledge(config),
		MCPServers:             portableBackupMCPServers(config.MCPServers),
		MCPCredentialsIncluded: false,
		SavedWorkflows:         exportSyncedWorkflows(workflows.Document.Workflows),
		GitHub: &desktopbridge.SyncedGitHubCredential{
			APIBaseURL: normalizeGitHubAPIBaseURL(workflows.Document.GitHub.APIBaseURL),
		},
	}
	if active := findProviderProfile(config.ProviderProfiles, config.ActiveProviderProfileID); active != nil {
		providerID := active.ProviderID
		if providerID == providerCodex {
			providerID = "codex"
		}
		bundle.ActiveProviderID = stringPointer(providerID)
		bundle.ActiveProfileID = stringPointer(active.ID)
	}
	for _, profile := range config.ProviderProfiles {
		if profile.ProviderID == providerCodex {
			continue
		}
		portable := desktopbridge.SyncedProviderCredential{
			ProfileID: profile.ID, ProviderID: profile.ProviderID, Name: profile.Name,
			BaseURL: profile.BaseURL, Model: profile.Model, ReasoningEffort: profile.ReasoningEffort,
			APIMode: profile.APIMode,
		}
		if profile.ContextWindowTokens > 0 {
			value := profile.ContextWindowTokens
			portable.ContextWindowTokens = &value
		}
		bundle.Providers = append(bundle.Providers, portable)
	}
	sessions := make([]desktopPortableBackupSession, 0, len(store.Sessions))
	for _, session := range store.Sessions {
		if session == nil {
			continue
		}
		document, err := encodeCrossPlatformSession(session)
		if err != nil {
			return desktopCrossPlatformBackupState{}, fmt.Errorf("无法生成会话 %s 的跨端备份：%w", session.ID, err)
		}
		sessions = append(sessions, desktopPortableBackupSession{
			SourceSessionID: session.ID,
			Document:        json.RawMessage(document),
		})
	}
	sort.Slice(sessions, func(i, j int) bool { return sessions[i].SourceSessionID < sessions[j].SourceSessionID })
	state := desktopCrossPlatformBackupState{
		SchemaVersion:  desktopPortableBackupSchemaVersion,
		SourcePlatform: desktopSourcePlatform(),
		GeneratedAt:    generatedAt,
		DeviceState:    bundle,
		Sessions:       sessions,
	}
	if err := validateDesktopCrossPlatformBackupState(state); err != nil {
		return desktopCrossPlatformBackupState{}, err
	}
	return state, nil
}

func portableBackupAgentSettings(config desktopConfig) *desktopbridge.SyncedAgentSettings {
	temperature := config.Temperature
	maxTokens := config.MaxTokens
	multimodal := config.EnableMultimodalMessages
	plannerEnabled := config.PlannerProfileEnabled
	plannerModel := config.PlannerModel
	plannerReasoning := config.PlannerReasoningEffort
	subagentEnabled := config.SubagentProfileEnabled
	subagentModel := config.SubagentModel
	subagentReasoning := config.SubagentReasoningEffort
	return &desktopbridge.SyncedAgentSettings{
		ApprovalMode: config.ApprovalMode, SystemPrompt: config.SystemPrompt,
		ResponseVerbosity: config.ResponseVerbosity,
		Temperature:       &temperature, MaxTokens: &maxTokens, EnableMultimodalMessages: &multimodal,
		PlannerProfileEnabled: &plannerEnabled, PlannerModel: &plannerModel,
		PlannerReasoningEffort: &plannerReasoning,
		SubagentProfileEnabled: &subagentEnabled, SubagentModel: &subagentModel,
		SubagentReasoningEffort: &subagentReasoning,
	}
}

func portableBackupMCPServers(servers []MCPServerConfig) []desktopbridge.SyncedMCPServer {
	result := make([]desktopbridge.SyncedMCPServer, 0, len(servers))
	for _, server := range servers {
		result = append(result, desktopbridge.SyncedMCPServer{
			ID: server.ID, Name: server.Name, Transport: server.Transport, Command: server.Command,
			Args: append([]string{}, server.Args...), URL: server.URL,
			RequestTimeoutSeconds: server.RequestTimeoutSeconds,
			TrustedReadOnlyTools:  append([]string{}, server.TrustedReadOnlyTools...),
			Enabled:               server.Enabled, AutoStart: server.AutoStart,
		})
	}
	return result
}

func validateDesktopCrossPlatformBackupState(state desktopCrossPlatformBackupState) error {
	if state.SchemaVersion != desktopPortableBackupSchemaVersion {
		return errors.New("跨端备份状态版本不受支持")
	}
	if !isPortableBackupSourcePlatform(state.SourcePlatform) || state.GeneratedAt <= 0 {
		return errors.New("跨端备份来源或时间无效")
	}
	if state.DeviceState.SourcePlatform != state.SourcePlatform || state.DeviceState.GeneratedAt != state.GeneratedAt {
		return errors.New("跨端备份配置来源或时间不一致")
	}
	if state.DeviceState.CodexAuthJSON != nil {
		return errors.New("跨端备份不得包含 Codex 登录")
	}
	for _, profile := range state.DeviceState.Providers {
		if profile.APIKey != nil {
			return errors.New("跨端备份不得包含 API Key")
		}
	}
	if github := state.DeviceState.GitHub; github != nil && (github.Token != nil || strings.TrimSpace(github.ViewerLogin) != "") {
		return errors.New("跨端备份不得包含 GitHub 登录状态")
	}
	if state.DeviceState.MCPCredentialsIncluded {
		return errors.New("跨端备份不得声明 MCP 凭据")
	}
	for _, server := range state.DeviceState.MCPServers {
		if len(server.Environment) > 0 || len(server.Headers) > 0 {
			return errors.New("跨端备份不得包含 MCP 环境变量或请求头")
		}
	}
	validationBundle := state.DeviceState
	validationBundle.GeneratedAt = time.Now().UnixMilli()
	if err := validateCredentialBundle(validationBundle); err != nil {
		return fmt.Errorf("跨端备份配置无效：%w", err)
	}
	if len(state.Sessions) > desktopPortableBackupMaxSessions {
		return errors.New("跨端备份会话数量超过上限")
	}
	seen := map[string]bool{}
	var total int64
	for _, record := range state.Sessions {
		if err := validatePortableBackupSourceSessionID(record.SourceSessionID); err != nil {
			return err
		}
		if seen[record.SourceSessionID] {
			return errors.New("跨端备份会话 ID 重复")
		}
		seen[record.SourceSessionID] = true
		if len(record.Document) == 0 || len(record.Document) > maxPortableSessionBytes {
			return errors.New("跨端备份会话文档为空或超过 32 MiB")
		}
		if total > desktopBackupMaxTotalPayload-int64(len(record.Document)) {
			return errors.New("跨端备份会话总大小超过上限")
		}
		total += int64(len(record.Document))
		var envelope CrossPlatformSessionEnvelope
		if err := decodeStrictJSON(record.Document, &envelope); err != nil {
			return fmt.Errorf("跨端备份会话 JSON 无效：%w", err)
		}
		if envelope.SourcePlatform != state.SourcePlatform {
			return errors.New("跨端会话来源与备份来源不一致")
		}
		if err := validateCrossPlatformEnvelope(&envelope); err != nil {
			return err
		}
	}
	return nil
}

func buildRestoredDesktopCrossPlatformSnapshots(
	currentStore desktopStoreBackupSnapshot,
	currentWorkflows savedWorkflowStoreBackupSnapshot,
	state desktopCrossPlatformBackupState,
) (desktopStoreBackupSnapshot, savedWorkflowStoreBackupSnapshot, desktopCrossPlatformRestoreResult, error) {
	result := desktopCrossPlatformRestoreResult{}
	if err := validateDesktopCrossPlatformBackupState(state); err != nil {
		return desktopStoreBackupSnapshot{}, savedWorkflowStoreBackupSnapshot{}, result, err
	}
	temporary, err := os.MkdirTemp("", "murong-portable-restore-")
	if err != nil {
		return desktopStoreBackupSnapshot{}, savedWorkflowStoreBackupSnapshot{}, result, err
	}
	defer os.RemoveAll(temporary)
	candidate := &desktopStore{
		configPath:   filepath.Join(temporary, "config.json"),
		sessionsPath: filepath.Join(temporary, "sessions.json"),
		config:       currentStore.Config,
		sessions:     map[string]*ChatSession{},
	}
	providers, _, err := candidate.importSyncedProviders(state.DeviceState)
	if err != nil {
		return desktopStoreBackupSnapshot{}, savedWorkflowStoreBackupSnapshot{}, result, err
	}
	if _, err := candidate.importSyncedPortableState(state.DeviceState); err != nil {
		return desktopStoreBackupSnapshot{}, savedWorkflowStoreBackupSnapshot{}, result, err
	}
	result.ImportedProviders = providers
	workflows := currentWorkflows
	if state.DeviceState.GitHub != nil {
		workflows, _, err = importSyncedGitHubCredential(workflows, *state.DeviceState.GitHub)
		if err != nil {
			return desktopStoreBackupSnapshot{}, savedWorkflowStoreBackupSnapshot{}, result, err
		}
	}
	workflows, result.ImportedWorkflows, result.SkippedWorkflows = importSyncedWorkflows(workflows, state.DeviceState.SavedWorkflows)
	sessions, mergeResult, err := mergePortableBackupSessions(currentStore.Sessions, state)
	if err != nil {
		return desktopStoreBackupSnapshot{}, savedWorkflowStoreBackupSnapshot{}, result, err
	}
	result.ImportedSessions = mergeResult.ImportedSessions
	result.ConflictCopies = mergeResult.ConflictCopies
	result.SkippedSessions = mergeResult.SkippedSessions
	restored := desktopStoreBackupSnapshot{Config: candidate.rawConfig(), Sessions: sessions}
	if err := validateDesktopSessions(restored.Sessions); err != nil {
		return desktopStoreBackupSnapshot{}, savedWorkflowStoreBackupSnapshot{}, result, err
	}
	return restored, workflows, result, nil
}

func mergePortableBackupSessions(
	existing []*ChatSession,
	state desktopCrossPlatformBackupState,
) ([]*ChatSession, desktopCrossPlatformRestoreResult, error) {
	result := desktopCrossPlatformRestoreResult{}
	byID := make(map[string]*ChatSession, len(existing)+len(state.Sessions))
	for _, session := range existing {
		if session != nil {
			byID[session.ID] = cloneSession(session)
		}
	}
	for _, record := range state.Sessions {
		incoming, err := decodeCrossPlatformSession(record.Document)
		if err != nil {
			return nil, result, err
		}
		primaryID := portableBackupSessionID(state.SourcePlatform, record.SourceSessionID)
		targetID := primaryID
		if current := byID[primaryID]; current != nil {
			equal, err := portableBackupSessionsEqual(current, incoming)
			if err != nil {
				return nil, result, err
			}
			if equal {
				result.SkippedSessions++
				continue
			}
			fingerprint, err := portableBackupSessionFingerprint(incoming)
			if err != nil {
				return nil, result, err
			}
			targetID = primaryID + "-" + fingerprint[:12]
			if conflict := byID[targetID]; conflict != nil {
				equal, err := portableBackupSessionsEqual(conflict, incoming)
				if err != nil {
					return nil, result, err
				}
				if equal {
					result.SkippedSessions++
					continue
				}
				return nil, result, errors.New("跨端会话冲突副本 ID 碰撞")
			}
			result.ConflictCopies++
		}
		incoming.ID = targetID
		incoming.CodexThreadID = ""
		incoming.CodexSyncedID = ""
		incoming.CodexToolsVersion = 0
		incoming.ExecutionHandoff = SessionExecutionHandoff{}
		byID[targetID] = incoming
		result.ImportedSessions++
	}
	merged := make([]*ChatSession, 0, len(byID))
	for _, session := range byID {
		merged = append(merged, cloneSession(session))
	}
	sort.Slice(merged, func(i, j int) bool { return merged[i].UpdatedAt > merged[j].UpdatedAt })
	return merged, result, nil
}

func portableBackupSessionsEqual(left, right *ChatSession) (bool, error) {
	leftHash, err := portableBackupSessionFingerprint(left)
	if err != nil {
		return false, err
	}
	rightHash, err := portableBackupSessionFingerprint(right)
	if err != nil {
		return false, err
	}
	return leftHash == rightHash, nil
}

func portableBackupSessionFingerprint(session *ChatSession) (string, error) {
	document, err := encodeCrossPlatformSession(session)
	if err != nil {
		return "", err
	}
	var envelope CrossPlatformSessionEnvelope
	if err := decodeStrictJSON(document, &envelope); err != nil {
		return "", err
	}
	canonical, err := json.Marshal(envelope.Session)
	if err != nil {
		return "", err
	}
	hash := sha256.Sum256(canonical)
	return hex.EncodeToString(hash[:]), nil
}

func portableBackupSessionID(sourcePlatform, sourceSessionID string) string {
	hash := sha256.Sum256([]byte(sourcePlatform + "\x00" + sourceSessionID))
	return "portable-" + sourcePlatform + "-" + hex.EncodeToString(hash[:])[:24]
}

func validatePortableBackupSourceSessionID(value string) error {
	if strings.TrimSpace(value) == "" || len(value) > maxPortableBackupSessionIDBytes || strings.ContainsAny(value, "\x00\r\n") {
		return errors.New("跨端备份会话 ID 无效")
	}
	return nil
}

func isPortableBackupSourcePlatform(value string) bool {
	return value == "android" || value == "desktop" || isDesktopSourcePlatform(value)
}
