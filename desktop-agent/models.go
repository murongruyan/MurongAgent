package main

import desktopbridge "github.com/murong-agent/desktop-bridge"

const (
	approvalReadOnly  = "readonly"
	approvalAskAll    = "ask"
	approvalAllowlist = "allowlist"
	approvalYolo      = "yolo"
)

type desktopConfig struct {
	SchemaVersion            int                         `json:"schemaVersion"`
	ProjectPath              string                      `json:"projectPath"`
	BaseURL                  string                      `json:"baseUrl"`
	Model                    string                      `json:"model"`
	ProtectedAPIKey          string                      `json:"protectedApiKey,omitempty"`
	ApprovalMode             string                      `json:"approvalMode"`
	Allowlist                []string                    `json:"allowlist,omitempty"`
	MaxToolIterations        int                         `json:"maxToolIterations"`
	SystemPrompt             string                      `json:"systemPrompt"`
	ResponseVerbosity        string                      `json:"responseVerbosity"`
	Temperature              float64                     `json:"temperature"`
	MaxTokens                int                         `json:"maxTokens"`
	EnableMultimodalMessages bool                        `json:"enableMultimodalMessages"`
	PlannerProfileEnabled    bool                        `json:"plannerProfileEnabled"`
	PlannerModel             string                      `json:"plannerModel,omitempty"`
	PlannerReasoningEffort   string                      `json:"plannerReasoningEffort,omitempty"`
	SubagentProfileEnabled   bool                        `json:"subagentDefaultProfileEnabled"`
	SubagentModel            string                      `json:"subagentDefaultModel,omitempty"`
	SubagentReasoningEffort  string                      `json:"subagentDefaultReasoningEffort,omitempty"`
	GlobalRules              []GlobalRule                `json:"globalRules,omitempty"`
	GlobalMemories           []GlobalMemory              `json:"globalMemories,omitempty"`
	GlobalSkills             []GlobalSkill               `json:"globalSkills,omitempty"`
	ProjectKnowledge         map[string]KnowledgeLibrary `json:"projectKnowledge,omitempty"`
	ActiveProviderProfileID  string                      `json:"activeProviderProfileId"`
	ProviderProfiles         []ProviderProfile           `json:"providerProfiles,omitempty"`
	EnabledBuiltinTools      []string                    `json:"enabledBuiltinTools"`
	EnabledFileOperations    []string                    `json:"enabledFileOperations"`
	ProjectToolPreferences   map[string]ToolPreferences  `json:"projectToolPreferences,omitempty"`
	RecentProjects           []RecentProjectRecord       `json:"recentProjects,omitempty"`
	MCPServers               []MCPServerConfig           `json:"mcpServers,omitempty"`
}

const (
	mcpTransportStdio          = "stdio"
	mcpTransportStreamableHTTP = "streamable_http"
	mcpTransportLegacySSE      = "legacy_sse"
)

type MCPServerConfig struct {
	ID                       string   `json:"id"`
	Name                     string   `json:"name"`
	Transport                string   `json:"transport"`
	Command                  string   `json:"command,omitempty"`
	Args                     []string `json:"args,omitempty"`
	Cwd                      string   `json:"cwd,omitempty"`
	URL                      string   `json:"url,omitempty"`
	RequestTimeoutSeconds    int      `json:"requestTimeoutSeconds"`
	TrustedReadOnlyTools     []string `json:"trustedReadOnlyTools,omitempty"`
	Enabled                  bool     `json:"enabled"`
	AutoStart                bool     `json:"autoStart"`
	ProtectedEnvironmentJSON string   `json:"protectedEnvironmentJson,omitempty"`
	ProtectedHeadersJSON     string   `json:"protectedHeadersJson,omitempty"`
}

type PublicMCPServerConfig struct {
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
	EnvironmentKeys       []string `json:"environmentKeys"`
	HeaderKeys            []string `json:"headerKeys"`
}

type SaveMCPServerConfig struct {
	ID                    string            `json:"id"`
	Name                  string            `json:"name"`
	Transport             string            `json:"transport"`
	Command               string            `json:"command"`
	Args                  []string          `json:"args"`
	Cwd                   string            `json:"cwd"`
	URL                   string            `json:"url"`
	RequestTimeoutSeconds int               `json:"requestTimeoutSeconds"`
	TrustedReadOnlyTools  []string          `json:"trustedReadOnlyTools"`
	Enabled               bool              `json:"enabled"`
	AutoStart             bool              `json:"autoStart"`
	Environment           map[string]string `json:"environment,omitempty"`
	Headers               map[string]string `json:"headers,omitempty"`
	ClearEnvironment      bool              `json:"clearEnvironment"`
	ClearHeaders          bool              `json:"clearHeaders"`
}

type SaveMCPServersRequest struct {
	Servers []SaveMCPServerConfig `json:"servers"`
}

type MCPServerStatus struct {
	ID              string   `json:"id"`
	Name            string   `json:"name"`
	Connected       bool     `json:"connected"`
	Connecting      bool     `json:"connecting"`
	ToolCount       int      `json:"toolCount"`
	ToolNames       []string `json:"toolNames"`
	Error           string   `json:"error,omitempty"`
	LastConnectedAt int64    `json:"lastConnectedAt,omitempty"`
}

type MCPToolInfo struct {
	ID              string `json:"id"`
	Name            string `json:"name"`
	ServerID        string `json:"serverId"`
	ServerName      string `json:"serverName"`
	Description     string `json:"description,omitempty"`
	TrustedReadOnly bool   `json:"trustedReadOnly"`
}

type MCPState struct {
	Servers  []PublicMCPServerConfig `json:"servers"`
	Statuses []MCPServerStatus       `json:"statuses"`
	Tools    []MCPToolInfo           `json:"tools"`
}

type RecentProjectRecord struct {
	Path         string `json:"path"`
	LastOpenedAt int64  `json:"lastOpenedAt"`
}

type RecentProject struct {
	Path         string `json:"path"`
	Name         string `json:"name"`
	LastOpenedAt int64  `json:"lastOpenedAt"`
	Exists       bool   `json:"exists"`
}

type CreateProjectRequest struct {
	ParentPath string `json:"parentPath"`
	Name       string `json:"name"`
}

type SessionProjectBindingRequest struct {
	SessionID   string `json:"sessionId"`
	ProjectPath string `json:"projectPath"`
}

type ProjectEntry struct {
	Path      string `json:"path"`
	Name      string `json:"name"`
	Directory bool   `json:"directory"`
	Size      int64  `json:"size,omitempty"`
}

type ComposerChoice struct {
	Kind     string `json:"kind"`
	ID       string `json:"id"`
	Label    string `json:"label"`
	Detail   string `json:"detail,omitempty"`
	Scope    string `json:"scope,omitempty"`
	Disabled bool   `json:"disabled,omitempty"`
}

type ComposerCatalog struct {
	Skills    []ComposerChoice `json:"skills"`
	Subagents []ComposerChoice `json:"subagents"`
	MCPTools  []ComposerChoice `json:"mcpTools"`
}

type ComposerContextItem struct {
	Kind  string `json:"kind"`
	ID    string `json:"id,omitempty"`
	Label string `json:"label,omitempty"`
	Path  string `json:"path,omitempty"`
	Scope string `json:"scope,omitempty"`
}

type ToolPreferences struct {
	ApprovalMode          string                    `json:"approvalMode"`
	Allowlist             []string                  `json:"allowlist"`
	EnabledBuiltinTools   []string                  `json:"enabledBuiltinTools"`
	EnabledFileOperations []string                  `json:"enabledFileOperations"`
	InheritGlobal         bool                      `json:"inheritGlobal,omitempty"`
	SubagentTemplates     []ProjectSubagentTemplate `json:"subagentTemplates,omitempty"`
}

type ProjectSubagentTemplate struct {
	ID                       string   `json:"id"`
	Title                    string   `json:"title"`
	Description              string   `json:"description,omitempty"`
	GoalMatchers             []string `json:"goalMatchers,omitempty"`
	PreferredModel           string   `json:"preferredModel,omitempty"`
	PreferredReasoningEffort string   `json:"preferredReasoningEffort,omitempty"`
	EnableWebSearch          bool     `json:"enableWebSearch"`
	AllowWriteAccess         bool     `json:"allowWriteAccess"`
	AllowCodeEdits           bool     `json:"allowCodeEdits"`
	AllowShell               bool     `json:"allowShell"`
	Enabled                  bool     `json:"enabled"`
}

type ProjectToolPreferencesSnapshot struct {
	ProjectPath  string          `json:"projectPath"`
	ProjectLabel string          `json:"projectLabel"`
	HasProject   bool            `json:"hasProject"`
	UsesGlobal   bool            `json:"usesGlobal"`
	Preferences  ToolPreferences `json:"preferences"`
}

type ProviderProfile struct {
	ID                  string `json:"id"`
	ProviderID          string `json:"providerId"`
	Name                string `json:"name"`
	BaseURL             string `json:"baseUrl"`
	Model               string `json:"model"`
	ReasoningEffort     string `json:"reasoningEffort"`
	APIMode             string `json:"apiMode"`
	ContextWindowTokens int    `json:"contextWindowTokens,omitempty"`
	ExecutablePath      string `json:"executablePath,omitempty"`
	ProtectedAPIKey     string `json:"protectedApiKey,omitempty"`
}

type PublicProviderProfile struct {
	ID                  string `json:"id"`
	ProviderID          string `json:"providerId"`
	Name                string `json:"name"`
	BaseURL             string `json:"baseUrl"`
	Model               string `json:"model"`
	ReasoningEffort     string `json:"reasoningEffort"`
	APIMode             string `json:"apiMode"`
	ContextWindowTokens int    `json:"contextWindowTokens,omitempty"`
	ExecutablePath      string `json:"executablePath,omitempty"`
	HasAPIKey           bool   `json:"hasApiKey"`
}

type SaveProviderProfile struct {
	ID                  string `json:"id"`
	ProviderID          string `json:"providerId"`
	Name                string `json:"name"`
	BaseURL             string `json:"baseUrl"`
	Model               string `json:"model"`
	ReasoningEffort     string `json:"reasoningEffort"`
	APIMode             string `json:"apiMode"`
	ContextWindowTokens int    `json:"contextWindowTokens"`
	ExecutablePath      string `json:"executablePath"`
	APIKey              string `json:"apiKey"`
	ClearAPIKey         bool   `json:"clearApiKey"`
}

type SetProviderReasoningEffortRequest struct {
	ProviderProfileID string `json:"providerProfileId"`
	ReasoningEffort   string `json:"reasoningEffort"`
}

type GlobalRule struct {
	ID      string `json:"id"`
	Title   string `json:"title"`
	Content string `json:"content"`
	Enabled bool   `json:"enabled"`
}

type GlobalMemory struct {
	ID      string `json:"id"`
	Title   string `json:"title"`
	Content string `json:"content"`
	Enabled bool   `json:"enabled"`
}

type GlobalSkill struct {
	ID             string   `json:"id"`
	Title          string   `json:"title"`
	Description    string   `json:"description"`
	Content        string   `json:"content"`
	RunAs          string   `json:"runAs"`
	AllowedTools   []string `json:"allowedTools"`
	PreferredModel string   `json:"preferredModel"`
	Enabled        bool     `json:"enabled"`
}

type KnowledgeLibrary struct {
	Rules    []GlobalRule   `json:"rules"`
	Memories []GlobalMemory `json:"memories"`
	Skills   []GlobalSkill  `json:"skills"`
}

type ProjectKnowledgeSnapshot struct {
	ProjectPath  string           `json:"projectPath"`
	ProjectLabel string           `json:"projectLabel"`
	HasProject   bool             `json:"hasProject"`
	Library      KnowledgeLibrary `json:"library"`
}

type PublicDesktopConfig struct {
	ProjectPath              string                  `json:"projectPath"`
	BaseURL                  string                  `json:"baseUrl"`
	Model                    string                  `json:"model"`
	HasAPIKey                bool                    `json:"hasApiKey"`
	ApprovalMode             string                  `json:"approvalMode"`
	Allowlist                []string                `json:"allowlist"`
	MaxToolIterations        int                     `json:"maxToolIterations"`
	SystemPrompt             string                  `json:"systemPrompt"`
	ResponseVerbosity        string                  `json:"responseVerbosity"`
	Temperature              float64                 `json:"temperature"`
	MaxTokens                int                     `json:"maxTokens"`
	EnableMultimodalMessages bool                    `json:"enableMultimodalMessages"`
	PlannerProfileEnabled    bool                    `json:"plannerProfileEnabled"`
	PlannerModel             string                  `json:"plannerModel"`
	PlannerReasoningEffort   string                  `json:"plannerReasoningEffort"`
	SubagentProfileEnabled   bool                    `json:"subagentDefaultProfileEnabled"`
	SubagentModel            string                  `json:"subagentDefaultModel"`
	SubagentReasoningEffort  string                  `json:"subagentDefaultReasoningEffort"`
	ActiveProviderProfileID  string                  `json:"activeProviderProfileId"`
	ProviderProfiles         []PublicProviderProfile `json:"providerProfiles"`
	EnabledBuiltinTools      []string                `json:"enabledBuiltinTools"`
	EnabledFileOperations    []string                `json:"enabledFileOperations"`
	RecentProjects           []RecentProject         `json:"recentProjects"`
}

type SaveSettingsRequest struct {
	ProjectPath                string                `json:"projectPath"`
	BaseURL                    string                `json:"baseUrl"`
	Model                      string                `json:"model"`
	APIKey                     string                `json:"apiKey"`
	ClearAPIKey                bool                  `json:"clearApiKey"`
	ApprovalMode               string                `json:"approvalMode"`
	Allowlist                  []string              `json:"allowlist"`
	MaxToolIterations          int                   `json:"maxToolIterations"`
	SystemPrompt               string                `json:"systemPrompt"`
	ResponseVerbosity          string                `json:"responseVerbosity"`
	Temperature                float64               `json:"temperature"`
	MaxTokens                  int                   `json:"maxTokens"`
	EnableMultimodalMessages   bool                  `json:"enableMultimodalMessages"`
	PlannerProfileEnabled      bool                  `json:"plannerProfileEnabled"`
	PlannerModel               string                `json:"plannerModel"`
	PlannerReasoningEffort     string                `json:"plannerReasoningEffort"`
	SubagentProfileEnabled     bool                  `json:"subagentDefaultProfileEnabled"`
	SubagentModel              string                `json:"subagentDefaultModel"`
	SubagentReasoningEffort    string                `json:"subagentDefaultReasoningEffort"`
	ActiveProviderProfileID    string                `json:"activeProviderProfileId"`
	ProviderProfiles           []SaveProviderProfile `json:"providerProfiles"`
	EnabledBuiltinTools        []string              `json:"enabledBuiltinTools"`
	EnabledFileOperations      []string              `json:"enabledFileOperations"`
	SaveProjectToolPreferences bool                  `json:"saveProjectToolPreferences"`
	UseGlobalProjectTools      bool                  `json:"useGlobalProjectTools"`
	ProjectTools               ToolPreferences       `json:"projectTools"`
}

type ChatMessage struct {
	ID                      string                   `json:"id"`
	Role                    string                   `json:"role"`
	Content                 string                   `json:"content"`
	ImageAttachments        []MessageImageAttachment `json:"imageAttachments,omitempty"`
	CreatedAt               int64                    `json:"createdAt"`
	Kind                    string                   `json:"kind,omitempty"`
	ToolName                string                   `json:"toolName,omitempty"`
	ToolCallID              string                   `json:"toolCallId,omitempty"`
	ToolArguments           string                   `json:"toolArguments,omitempty"`
	ToolStatus              string                   `json:"toolStatus,omitempty"`
	Context                 []ComposerContextItem    `json:"context,omitempty"`
	Mode                    string                   `json:"mode,omitempty"`
	WorkspaceChanges        []WorkspaceFileChange    `json:"workspaceChanges,omitempty"`
	WorkspaceChangesOmitted int                      `json:"workspaceChangesOmitted,omitempty"`
}

type MessageImageAttachment struct {
	ID        string `json:"id"`
	FileName  string `json:"fileName"`
	MimeType  string `json:"mimeType"`
	CacheFile string `json:"cacheFile"`
	Width     int    `json:"width,omitempty"`
	Height    int    `json:"height,omitempty"`
	SizeBytes int64  `json:"sizeBytes"`
}

type SelectedChatImage struct {
	Attachment     MessageImageAttachment `json:"attachment"`
	PreviewDataURL string                 `json:"previewDataUrl"`
}

type ChatImagePreviewRequest struct {
	SessionID string `json:"sessionId"`
	MessageID string `json:"messageId"`
	ImageID   string `json:"imageId"`
}

type DiscardChatImageRequest struct {
	ImageID   string `json:"imageId"`
	CacheFile string `json:"cacheFile"`
}

type SessionUsage struct {
	ModelRequests         int    `json:"modelRequests,omitempty"`
	ReportedUsageRequests int    `json:"reportedUsageRequests,omitempty"`
	InputTokens           int64  `json:"inputTokens,omitempty"`
	OutputTokens          int64  `json:"outputTokens,omitempty"`
	TotalTokens           int64  `json:"totalTokens,omitempty"`
	CachedInputTokens     int64  `json:"cachedInputTokens,omitempty"`
	ReasoningOutputTokens int64  `json:"reasoningOutputTokens,omitempty"`
	LastProviderProfileID string `json:"lastProviderProfileId,omitempty"`
	LastProviderID        string `json:"lastProviderId,omitempty"`
	LastModel             string `json:"lastModel,omitempty"`
}

type SessionCompression struct {
	Version            int    `json:"version,omitempty"`
	Summary            string `json:"summary,omitempty"`
	SourceMessageCount int    `json:"sourceMessageCount,omitempty"`
	SourceEndMessageID string `json:"sourceEndMessageId,omitempty"`
	CreatedAt          int64  `json:"createdAt,omitempty"`
	Active             bool   `json:"active,omitempty"`
	Method             string `json:"method,omitempty"`
}

type SubagentBackgroundJob struct {
	ID            string               `json:"id"`
	Label         string               `json:"label"`
	ParentGoal    string               `json:"parentGoal"`
	Status        string               `json:"status"`
	StatusMessage string               `json:"statusMessage,omitempty"`
	TaskCount     int                  `json:"taskCount"`
	Completed     int                  `json:"completed,omitempty"`
	Failed        int                  `json:"failed,omitempty"`
	Skipped       int                  `json:"skipped,omitempty"`
	Cancelled     int                  `json:"cancelled,omitempty"`
	Results       []subagentTaskResult `json:"results,omitempty"`
	CreatedAt     int64                `json:"createdAt"`
	StartedAt     int64                `json:"startedAt,omitempty"`
	FinishedAt    int64                `json:"finishedAt,omitempty"`
}

type CancelSubagentJobRequest struct {
	SessionID string `json:"sessionId"`
	JobID     string `json:"jobId"`
}

type SessionExecutionHandoff struct {
	Version          int    `json:"version"`
	Owner            string `json:"owner"`
	Token            string `json:"token"`
	BaseMessageCount int    `json:"baseMessageCount"`
	BaseDigest       string `json:"baseDigest"`
	StartedAt        int64  `json:"startedAt"`
}

type WorkflowStepSignOff struct {
	StepIndex          int      `json:"stepIndex"`
	Step               string   `json:"step"`
	ReportedStep       string   `json:"reportedStep"`
	ResultSummary      string   `json:"resultSummary"`
	MatchedEvidence    int      `json:"matchedEvidence"`
	TotalEvidence      int      `json:"totalEvidence"`
	MatchedToolNames   []string `json:"matchedToolNames,omitempty"`
	EvidenceMessageIDs []string `json:"evidenceMessageIds,omitempty"`
	SignedOffAt        int64    `json:"signedOffAt"`
}

type DesktopWorkflowPlan struct {
	ID                 string                `json:"id"`
	Goal               string                `json:"goal"`
	Summary            string                `json:"summary"`
	Steps              []string              `json:"steps"`
	CurrentStepIndex   int                   `json:"currentStepIndex"`
	Status             string                `json:"status"`
	NextStepHint       string                `json:"nextStepHint,omitempty"`
	RawPlan            string                `json:"rawPlan,omitempty"`
	SourceMessageID    string                `json:"sourceMessageId"`
	StepSignOffs       []WorkflowStepSignOff `json:"stepSignOffs,omitempty"`
	CreatedAt          int64                 `json:"createdAt"`
	ExecutionStartedAt int64                 `json:"executionStartedAt,omitempty"`
	UpdatedAt          int64                 `json:"updatedAt"`
}

type ChatSession struct {
	ID                     string                  `json:"id"`
	Title                  string                  `json:"title"`
	CreatedAt              int64                   `json:"createdAt"`
	UpdatedAt              int64                   `json:"updatedAt"`
	ProjectPath            string                  `json:"projectPath,omitempty"`
	Goal                   string                  `json:"goal,omitempty"`
	PlanModeEnabled        bool                    `json:"planModeEnabled,omitempty"`
	WorkflowPlan           *DesktopWorkflowPlan    `json:"workflowPlan,omitempty"`
	Usage                  SessionUsage            `json:"usage,omitempty"`
	Compression            SessionCompression      `json:"compression,omitempty"`
	CodexThreadID          string                  `json:"codexThreadId,omitempty"`
	CodexSyncedID          string                  `json:"codexSyncedMessageId,omitempty"`
	CodexToolsVersion      int                     `json:"codexToolsVersion,omitempty"`
	Messages               []ChatMessage           `json:"messages"`
	BackgroundSubagentJobs []SubagentBackgroundJob `json:"backgroundSubagentJobs,omitempty"`
	ExecutionHandoff       SessionExecutionHandoff `json:"executionHandoff,omitempty"`
}

type SessionSummary struct {
	ID               string `json:"id"`
	Title            string `json:"title"`
	UpdatedAt        int64  `json:"updatedAt"`
	MessageCount     int    `json:"messageCount"`
	ProjectPath      string `json:"projectPath,omitempty"`
	ExecutionOwner   string `json:"executionOwner"`
	HandoffStartedAt int64  `json:"handoffStartedAt,omitempty"`
}

type SessionSelection struct {
	Session          *ChatSession        `json:"session"`
	Config           PublicDesktopConfig `json:"config"`
	ProjectAvailable bool                `json:"projectAvailable"`
	ProjectError     string              `json:"projectError,omitempty"`
}

type BootstrapState struct {
	Platform               DesktopPlatformInfo              `json:"platform"`
	Config                 PublicDesktopConfig              `json:"config"`
	Sessions               []SessionSummary                 `json:"sessions"`
	Active                 *ChatSession                     `json:"activeSession,omitempty"`
	ActiveProjectAvailable bool                             `json:"activeProjectAvailable"`
	ActiveProjectError     string                           `json:"activeProjectError,omitempty"`
	RemoteNode             desktopbridge.RemoteNodeSnapshot `json:"remoteNode"`
	Terminals              []TerminalBackend                `json:"terminals"`
	Knowledge              KnowledgeLibrary                 `json:"knowledge"`
	ProjectKnowledge       ProjectKnowledgeSnapshot         `json:"projectKnowledge"`
	ProjectTools           ProjectToolPreferencesSnapshot   `json:"projectTools"`
	MCP                    MCPState                         `json:"mcp"`
	SavedWorkflows         SavedWorkflowState               `json:"savedWorkflows"`
	Backup                 DesktopBackupStatus              `json:"backup"`
	WorkspaceChanges       WorkspaceChangeState             `json:"workspaceChanges"`
	Codex                  CodexRuntimeStatus               `json:"codex"`
}

type DesktopPlatformInfo struct {
	OS                   string `json:"os"`
	Architecture         string `json:"architecture"`
	Label                string `json:"label"`
	CredentialProtection string `json:"credentialProtection"`
	PackageKind          string `json:"packageKind"`
}

type CodexRuntimeRequest struct {
	ExecutablePath string `json:"executablePath"`
}

type CodexLoginInfo struct {
	LoginID         string `json:"loginId,omitempty"`
	VerificationURL string `json:"verificationUrl,omitempty"`
	UserCode        string `json:"userCode,omitempty"`
	Waiting         bool   `json:"waiting"`
}

type CodexModelInfo struct {
	ID                        string   `json:"id"`
	Model                     string   `json:"model"`
	DisplayName               string   `json:"displayName"`
	Description               string   `json:"description,omitempty"`
	DefaultReasoningEffort    string   `json:"defaultReasoningEffort,omitempty"`
	SupportedReasoningEfforts []string `json:"supportedReasoningEfforts"`
	IsDefault                 bool     `json:"isDefault"`
}

type CodexRateLimitWindow struct {
	UsedPercent        float64 `json:"usedPercent"`
	WindowDurationMins int64   `json:"windowDurationMins,omitempty"`
	ResetsAt           int64   `json:"resetsAt,omitempty"`
}

type CodexCreditsInfo struct {
	HasCredits bool   `json:"hasCredits"`
	Unlimited  bool   `json:"unlimited"`
	Balance    string `json:"balance,omitempty"`
}

type CodexRateLimitInfo struct {
	LimitID   string                `json:"limitId,omitempty"`
	LimitName string                `json:"limitName,omitempty"`
	PlanType  string                `json:"planType,omitempty"`
	Primary   *CodexRateLimitWindow `json:"primary,omitempty"`
	Secondary *CodexRateLimitWindow `json:"secondary,omitempty"`
	Credits   *CodexCreditsInfo     `json:"credits,omitempty"`
}

type CodexRuntimeStatus struct {
	Available      bool                 `json:"available"`
	Builtin        bool                 `json:"builtin"`
	Running        bool                 `json:"running"`
	ExecutablePath string               `json:"executablePath,omitempty"`
	Version        string               `json:"version,omitempty"`
	LoggedIn       bool                 `json:"loggedIn"`
	AccountType    string               `json:"accountType,omitempty"`
	Email          string               `json:"email,omitempty"`
	PlanType       string               `json:"planType,omitempty"`
	RequiresAuth   bool                 `json:"requiresAuth"`
	Models         []CodexModelInfo     `json:"models"`
	RateLimits     []CodexRateLimitInfo `json:"rateLimits"`
	RateLimitError string               `json:"rateLimitError,omitempty"`
	Login          CodexLoginInfo       `json:"login"`
	Error          string               `json:"error,omitempty"`
}

type StartRemoteNodeRequest struct {
	Config   desktopbridge.RemoteNodeConfig `json:"config"`
	PairCode string                         `json:"pairCode"`
}

type SendMessageRequest struct {
	SessionID string                   `json:"sessionId"`
	Content   string                   `json:"content"`
	Context   []ComposerContextItem    `json:"context,omitempty"`
	Images    []MessageImageAttachment `json:"images,omitempty"`
	Mode      string                   `json:"mode,omitempty"`
}

type ApprovalRequest struct {
	ID        string `json:"id"`
	SessionID string `json:"sessionId"`
	ToolName  string `json:"toolName"`
	Summary   string `json:"summary"`
	Detail    string `json:"detail"`
	Arguments string `json:"arguments"`
	Risk      string `json:"risk"`
}

type ApprovalDecision struct {
	ID      string `json:"id"`
	Approve bool   `json:"approve"`
}

type AskOption struct {
	Label       string `json:"label"`
	Description string `json:"description,omitempty"`
}

type AskQuestion struct {
	ID          string      `json:"id"`
	Header      string      `json:"header,omitempty"`
	Question    string      `json:"question"`
	Options     []AskOption `json:"options"`
	MultiSelect bool        `json:"multiSelect,omitempty"`
}

type AskRequest struct {
	ID        string        `json:"id"`
	SessionID string        `json:"sessionId"`
	Questions []AskQuestion `json:"questions"`
	CreatedAt int64         `json:"createdAt"`
}

type AskAnswer struct {
	QuestionID      string   `json:"questionId"`
	SelectedOptions []string `json:"selectedOptions"`
}

type AskDecision struct {
	ID      string      `json:"id"`
	Answers []AskAnswer `json:"answers,omitempty"`
	Dismiss bool        `json:"dismiss,omitempty"`
}
