package main

const (
	workflowProjectReadDiagnostic  = "PROJECT_READ_DIAGNOSTIC"
	workflowDirectoryChangeSummary = "DIRECTORY_CHANGE_SUMMARY"
	workflowGitHubActionsStatus    = "GITHUB_ACTIONS_STATUS"
	workflowSessionSummaryExport   = "SESSION_SUMMARY_EXPORT"

	workflowPermissionProjectRead = "PROJECT_READ"
	workflowPermissionNetworkRead = "NETWORK_READ"
	workflowPermissionFileWrite   = "FILE_WRITE"

	workflowRunNever     = "NEVER"
	workflowRunQueued    = "QUEUED"
	workflowRunRunning   = "RUNNING"
	workflowRunSucceeded = "SUCCEEDED"
	workflowRunFailed    = "FAILED"
	workflowRunBlocked   = "BLOCKED"
	workflowRunCancelled = "CANCELLED"
)

const (
	minimumWorkflowIntervalMinutes = 15
	defaultWorkflowIntervalMinutes = 60
	maximumWorkflowIntervalMinutes = 7 * 24 * 60
	maximumSavedWorkflows          = 64
)

type SavedWorkflowNode struct {
	ID                 string   `json:"id"`
	Label              string   `json:"label"`
	DependsOn          []string `json:"dependsOn"`
	RequiredPermission string   `json:"requiredPermission"`
	TimeoutSeconds     int      `json:"timeoutSeconds"`
	MaxRetries         int      `json:"maxRetries"`
}

type SavedWorkflowRunRecord struct {
	Status        string `json:"status"`
	StartedAt     int64  `json:"startedAt,omitempty"`
	FinishedAt    int64  `json:"finishedAt,omitempty"`
	Summary       string `json:"summary"`
	FailureReason string `json:"failureReason,omitempty"`
}

type SavedWorkflowDefinition struct {
	ID               string                  `json:"id"`
	Name             string                  `json:"name"`
	Template         string                  `json:"template"`
	ProjectPath      string                  `json:"projectPath,omitempty"`
	GitHubRepository string                  `json:"githubRepository,omitempty"`
	Nodes            []SavedWorkflowNode     `json:"nodes"`
	IntervalMinutes  int                     `json:"intervalMinutes"`
	Enabled          bool                    `json:"enabled"`
	CreatedAt        int64                   `json:"createdAt"`
	UpdatedAt        int64                   `json:"updatedAt"`
	LastRun          *SavedWorkflowRunRecord `json:"lastRun,omitempty"`
}

type SaveSavedWorkflowRequest struct {
	ID               string `json:"id"`
	Name             string `json:"name"`
	Template         string `json:"template"`
	ProjectPath      string `json:"projectPath"`
	GitHubRepository string `json:"githubRepository"`
	IntervalMinutes  int    `json:"intervalMinutes"`
	Enabled          bool   `json:"enabled"`
}

type RunSavedWorkflowRequest struct {
	ID        string `json:"id"`
	SessionID string `json:"sessionId"`
	Confirmed bool   `json:"confirmed"`
}

type PublicGitHubConfig struct {
	APIBaseURL string `json:"apiBaseUrl"`
	HasToken   bool   `json:"hasToken"`
	Viewer     string `json:"viewer,omitempty"`
}

type SaveGitHubConfigRequest struct {
	APIBaseURL string `json:"apiBaseUrl"`
	Token      string `json:"token"`
	ClearToken bool   `json:"clearToken"`
}

type SavedWorkflowState struct {
	GitHub    PublicGitHubConfig        `json:"github"`
	Workflows []SavedWorkflowDefinition `json:"workflows"`
}

type savedWorkflowDocument struct {
	SchemaVersion int                       `json:"schemaVersion"`
	GitHub        savedGitHubConfig         `json:"github"`
	Workflows     []SavedWorkflowDefinition `json:"workflows"`
}

type savedGitHubConfig struct {
	APIBaseURL     string `json:"apiBaseUrl"`
	ProtectedToken string `json:"protectedToken,omitempty"`
}

type runtimeGitHubConfig struct {
	APIBaseURL string
	Token      string
}

func defaultSavedWorkflowNodes(template string) []SavedWorkflowNode {
	switch template {
	case workflowProjectReadDiagnostic:
		return []SavedWorkflowNode{{ID: "inspect_project", Label: "读取项目目录与可访问性", RequiredPermission: workflowPermissionProjectRead, TimeoutSeconds: 60}}
	case workflowDirectoryChangeSummary:
		return []SavedWorkflowNode{{ID: "summarize_directory", Label: "汇总目录快照", RequiredPermission: workflowPermissionProjectRead, TimeoutSeconds: 60}}
	case workflowGitHubActionsStatus:
		return []SavedWorkflowNode{{ID: "query_github_actions", Label: "查询 GitHub Actions 状态", RequiredPermission: workflowPermissionNetworkRead, TimeoutSeconds: 60}}
	case workflowSessionSummaryExport:
		return []SavedWorkflowNode{{ID: "export_session_summary", Label: "导出会话摘要", RequiredPermission: workflowPermissionFileWrite, TimeoutSeconds: 60}}
	default:
		return []SavedWorkflowNode{}
	}
}

func cloneSavedWorkflow(workflow SavedWorkflowDefinition) SavedWorkflowDefinition {
	copy := workflow
	copy.Nodes = append([]SavedWorkflowNode(nil), workflow.Nodes...)
	for index := range copy.Nodes {
		copy.Nodes[index].DependsOn = append([]string(nil), workflow.Nodes[index].DependsOn...)
	}
	if workflow.LastRun != nil {
		record := *workflow.LastRun
		copy.LastRun = &record
	}
	return copy
}
