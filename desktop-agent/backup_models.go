package main

const (
	desktopBackupFormat        = "murong-backup"
	desktopBackupFormatVersion = 2
	desktopBackupMinVersion    = 1
	desktopBackupAppVersion    = "desktop-agent"
	desktopBackupAppCode       = 1

	backupKindManual     = "MANUAL"
	backupKindAutomatic  = "AUTOMATIC"
	backupKindPreRestore = "PRE_RESTORE"
)

const (
	backupCategoryConversations = "CONVERSATIONS"
	backupCategoryMedia         = "CONVERSATION_MEDIA"
	backupCategoryMemories      = "MEMORIES"
	backupCategoryProvider      = "PROVIDER_SETTINGS"
	backupCategoryMCP           = "MCP_CONFIG"
	backupCategoryWorkflows     = "SAVED_WORKFLOWS"
	backupCategoryVoice         = "VOICE_SETTINGS"
	backupCategoryUI            = "UI_SETTINGS"
	backupCategorySettings      = "BACKUP_SETTINGS"
	backupCategoryProjectAudit  = "PROJECT_AUDIT"
	backupCategoryPortableState = "PORTABLE_STATE"
)

var desktopBackupDefaultExclusions = []string{
	"API Key 与本机凭据保护机制中保存的 MCP/GitHub 鉴权值",
	"Codex/ChatGPT 与 GitHub 登录状态",
	"语音离线模型",
	"终端扩展、工具链、Shell 历史和终端日志",
	"远程节点配对凭据与设备专用运行状态",
	"缓存、WebView、构建产物、分析统计与崩溃诊断",
}

type DesktopBackupEntry struct {
	Path      string `json:"path"`
	Category  string `json:"category"`
	SizeBytes int64  `json:"sizeBytes"`
	SHA256    string `json:"sha256"`
}

type DesktopBackupManifest struct {
	Format               string               `json:"format"`
	FormatVersion        int                  `json:"formatVersion"`
	CreatedAtEpochMillis int64                `json:"createdAtEpochMillis"`
	AppVersionName       string               `json:"appVersionName"`
	AppVersionCode       int                  `json:"appVersionCode"`
	Kind                 string               `json:"kind"`
	Entries              []DesktopBackupEntry `json:"entries"`
	ExcludedByDefault    []string             `json:"excludedByDefault"`
}

type DesktopBackupSettings struct {
	DailyBackupEnabled bool `json:"dailyBackupEnabled"`
	MaxBackupCount     int  `json:"maxBackupCount"`
}

type DesktopBackupStatus struct {
	Settings                     DesktopBackupSettings `json:"settings"`
	LastBackupAt                 int64                 `json:"lastBackupAt,omitempty"`
	LastBackupMessage            string                `json:"lastBackupMessage,omitempty"`
	LastBackupFailed             bool                  `json:"lastBackupFailed"`
	AutomaticBackupCount         int                   `json:"automaticBackupCount"`
	PreRestoreSnapshotCount      int                   `json:"preRestoreSnapshotCount"`
	LatestPreRestoreSnapshotName string                `json:"latestPreRestoreSnapshotName,omitempty"`
	StorageLocation              string                `json:"storageLocation"`
	ScheduleDescription          string                `json:"scheduleDescription"`
}

type DesktopBackupOperationResult struct {
	Manifest               *DesktopBackupManifest `json:"manifest,omitempty"`
	Message                string                 `json:"message"`
	OutputPath             string                 `json:"outputPath,omitempty"`
	RestoredEntryCount     int                    `json:"restoredEntryCount,omitempty"`
	PreRestoreSnapshotName string                 `json:"preRestoreSnapshotName,omitempty"`
	Skipped                bool                   `json:"skipped"`
	Status                 DesktopBackupStatus    `json:"status"`
}

type DesktopRestoreSelection struct {
	Path      string `json:"path"`
	FileName  string `json:"fileName"`
	SizeBytes int64  `json:"sizeBytes"`
}

type RestoreDesktopBackupRequest struct {
	Path      string `json:"path"`
	Confirmed bool   `json:"confirmed"`
}

type desktopBackupRuntimeState struct {
	Settings               DesktopBackupSettings `json:"settings"`
	LastAutomaticBackupDay string                `json:"lastAutomaticBackupDay,omitempty"`
	LastBackupAt           int64                 `json:"lastBackupAt,omitempty"`
	LastBackupMessage      string                `json:"lastBackupMessage,omitempty"`
	LastBackupFailed       bool                  `json:"lastBackupFailed"`
}

type desktopProviderProfileBackup struct {
	ID                  string `json:"id"`
	ProviderID          string `json:"providerId"`
	Name                string `json:"name"`
	BaseURL             string `json:"baseUrl"`
	Model               string `json:"model"`
	ReasoningEffort     string `json:"reasoningEffort"`
	APIMode             string `json:"apiMode"`
	ContextWindowTokens int    `json:"contextWindowTokens,omitempty"`
}

type desktopProviderSettingsBackup struct {
	SchemaVersion            int                            `json:"schemaVersion"`
	SourcePlatform           string                         `json:"sourcePlatform"`
	ProjectPath              string                         `json:"projectPath,omitempty"`
	ApprovalMode             string                         `json:"approvalMode"`
	Allowlist                []string                       `json:"allowlist"`
	MaxToolIterations        int                            `json:"maxToolIterations"`
	SystemPrompt             string                         `json:"systemPrompt"`
	ResponseVerbosity        string                         `json:"responseVerbosity"`
	Temperature              *float64                       `json:"temperature,omitempty"`
	MaxTokens                *int                           `json:"maxTokens,omitempty"`
	EnableMultimodalMessages *bool                          `json:"enableMultimodalMessages,omitempty"`
	PlannerProfileEnabled    bool                           `json:"plannerProfileEnabled"`
	PlannerModel             string                         `json:"plannerModel,omitempty"`
	PlannerReasoningEffort   string                         `json:"plannerReasoningEffort,omitempty"`
	SubagentProfileEnabled   bool                           `json:"subagentDefaultProfileEnabled"`
	SubagentModel            string                         `json:"subagentDefaultModel,omitempty"`
	SubagentReasoningEffort  string                         `json:"subagentDefaultReasoningEffort,omitempty"`
	ActiveProviderProfileID  string                         `json:"activeProviderProfileId"`
	ProviderProfiles         []desktopProviderProfileBackup `json:"providerProfiles"`
	EnabledBuiltinTools      []string                       `json:"enabledBuiltinTools"`
	EnabledFileOperations    []string                       `json:"enabledFileOperations"`
	ProjectToolPreferences   map[string]ToolPreferences     `json:"projectToolPreferences"`
	RecentProjects           []RecentProjectRecord          `json:"recentProjects"`
}

type desktopKnowledgeBackup struct {
	SchemaVersion    int                         `json:"schemaVersion"`
	SourcePlatform   string                      `json:"sourcePlatform"`
	GlobalRules      []GlobalRule                `json:"globalRules"`
	GlobalMemories   []GlobalMemory              `json:"globalMemories"`
	GlobalSkills     []GlobalSkill               `json:"globalSkills"`
	ProjectKnowledge map[string]KnowledgeLibrary `json:"projectKnowledge"`
}

type desktopMCPServerBackup struct {
	ID                    string   `json:"id"`
	Name                  string   `json:"name"`
	Transport             string   `json:"transport"`
	Command               string   `json:"command,omitempty"`
	Args                  []string `json:"args"`
	Cwd                   string   `json:"cwd,omitempty"`
	URL                   string   `json:"url,omitempty"`
	RequestTimeoutSeconds int      `json:"requestTimeoutSeconds"`
	TrustedReadOnlyTools  []string `json:"trustedReadOnlyTools"`
	Enabled               bool     `json:"enabled"`
	AutoStart             bool     `json:"autoStart"`
}

type desktopMCPBackup struct {
	SchemaVersion  int                      `json:"schemaVersion"`
	SourcePlatform string                   `json:"sourcePlatform"`
	Servers        []desktopMCPServerBackup `json:"servers"`
}

type desktopSavedWorkflowsBackup struct {
	SchemaVersion    int                       `json:"schemaVersion"`
	SourcePlatform   string                    `json:"sourcePlatform"`
	GitHubAPIBaseURL string                    `json:"githubApiBaseUrl"`
	Workflows        []SavedWorkflowDefinition `json:"workflows"`
}

type desktopUISettingsBackup struct {
	SchemaVersion  int    `json:"schemaVersion"`
	SourcePlatform string `json:"sourcePlatform"`
}

type desktopSessionsBackup struct {
	SchemaVersion  int            `json:"schemaVersion"`
	SourcePlatform string         `json:"sourcePlatform"`
	Sessions       []*ChatSession `json:"sessions"`
}

type desktopPortableBackupState struct {
	ProviderSettings     desktopProviderSettingsBackup
	Knowledge            desktopKnowledgeBackup
	MCP                  desktopMCPBackup
	Workflows            desktopSavedWorkflowsBackup
	Sessions             []*ChatSession
	BackupSettings       DesktopBackupSettings
	ProjectAudit         desktopProjectAuditDocument
	ProjectAuditIncluded bool
	CrossPlatform        *desktopCrossPlatformBackupState
}

type desktopBackupPayload struct {
	Path     string
	Category string
	Data     []byte
}

type validatedDesktopBackup struct {
	Manifest    DesktopBackupManifest
	PayloadRoot string
}
