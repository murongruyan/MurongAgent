package desktopbridge

const DesktopAgentProtocolVersion = 3

type DesktopAgentTaskSummary struct {
	ID               string `json:"id"`
	Title            string `json:"title"`
	UpdatedAt        int64  `json:"updatedAt"`
	MessageCount     int    `json:"messageCount"`
	Running          bool   `json:"running"`
	PendingApproval  bool   `json:"pendingApproval"`
	PendingQuestion  bool   `json:"pendingQuestion,omitempty"`
	ExecutionOwner   string `json:"executionOwner,omitempty"`
	HandoffStartedAt int64  `json:"handoffStartedAt,omitempty"`
}

type DesktopAgentMessage struct {
	ID              string   `json:"id"`
	Role            string   `json:"role"`
	Content         string   `json:"content"`
	Timestamp       int64    `json:"timestamp"`
	Kind            string   `json:"kind,omitempty"`
	ToolName        string   `json:"toolName,omitempty"`
	AttachmentNames []string `json:"attachmentNames,omitempty"`
}

type DesktopAgentApproval struct {
	ID        string `json:"id"`
	SessionID string `json:"sessionId"`
	ToolName  string `json:"toolName"`
	Summary   string `json:"summary"`
	Detail    string `json:"detail"`
	Risk      string `json:"risk"`
}

type DesktopAgentAskOption struct {
	Label       string `json:"label"`
	Description string `json:"description,omitempty"`
}

type DesktopAgentAskQuestion struct {
	ID          string                  `json:"id"`
	Header      string                  `json:"header,omitempty"`
	Question    string                  `json:"question"`
	Options     []DesktopAgentAskOption `json:"options"`
	MultiSelect bool                    `json:"multiSelect,omitempty"`
}

type DesktopAgentAskRequest struct {
	ID        string                    `json:"id"`
	SessionID string                    `json:"sessionId"`
	Questions []DesktopAgentAskQuestion `json:"questions"`
	CreatedAt int64                     `json:"createdAt"`
}

type DesktopAgentAskAnswer struct {
	QuestionID      string   `json:"questionId"`
	SelectedOptions []string `json:"selectedOptions"`
}

type DesktopAgentWorkflowStepSignOff struct {
	StepIndex        int      `json:"stepIndex"`
	ResultSummary    string   `json:"resultSummary"`
	MatchedEvidence  int      `json:"matchedEvidence"`
	TotalEvidence    int      `json:"totalEvidence"`
	MatchedToolNames []string `json:"matchedToolNames,omitempty"`
	SignedOffAt      int64    `json:"signedOffAt"`
}

type DesktopAgentWorkflowPlan struct {
	ID                 string                            `json:"id"`
	Summary            string                            `json:"summary"`
	Steps              []string                          `json:"steps"`
	CurrentStepIndex   int                               `json:"currentStepIndex"`
	Status             string                            `json:"status"`
	NextStepHint       string                            `json:"nextStepHint,omitempty"`
	StepSignOffs       []DesktopAgentWorkflowStepSignOff `json:"stepSignOffs,omitempty"`
	CreatedAt          int64                             `json:"createdAt"`
	ExecutionStartedAt int64                             `json:"executionStartedAt,omitempty"`
	UpdatedAt          int64                             `json:"updatedAt"`
}

type DesktopAgentTaskDetail struct {
	ID               string                    `json:"id"`
	Title            string                    `json:"title"`
	UpdatedAt        int64                     `json:"updatedAt"`
	Running          bool                      `json:"running"`
	Messages         []DesktopAgentMessage     `json:"messages"`
	MessageCount     int                       `json:"messageCount"`
	PendingApproval  *DesktopAgentApproval     `json:"pendingApproval,omitempty"`
	PendingAsk       *DesktopAgentAskRequest   `json:"pendingAsk,omitempty"`
	WorkflowPlan     *DesktopAgentWorkflowPlan `json:"workflowPlan,omitempty"`
	ExecutionOwner   string                    `json:"executionOwner,omitempty"`
	HandoffStartedAt int64                     `json:"handoffStartedAt,omitempty"`
}

type DesktopAgentSnapshot struct {
	NodeSessionID      string                    `json:"nodeSessionId"`
	SourcePlatform     string                    `json:"sourcePlatform,omitempty"`
	SourceArchitecture string                    `json:"sourceArchitecture,omitempty"`
	Sequence           int64                     `json:"sequence"`
	GeneratedAt        int64                     `json:"generatedAt"`
	Sessions           []DesktopAgentTaskSummary `json:"sessions"`
	ActiveSession      *DesktopAgentTaskDetail   `json:"activeSession,omitempty"`
	SessionUpdates     []DesktopAgentTaskDetail  `json:"sessionUpdates,omitempty"`
}

type DesktopAgentCommand struct {
	NodeSessionID   string                  `json:"nodeSessionId"`
	RequestID       string                  `json:"requestId"`
	Operation       string                  `json:"operation"`
	SessionID       string                  `json:"sessionId,omitempty"`
	Content         string                  `json:"content,omitempty"`
	ApprovalID      string                  `json:"approvalId,omitempty"`
	Approve         *bool                   `json:"approve,omitempty"`
	AskID           string                  `json:"askId,omitempty"`
	AskAnswers      []DesktopAgentAskAnswer `json:"askAnswers,omitempty"`
	DismissAsk      bool                    `json:"dismissAsk,omitempty"`
	HandoffToken    string                  `json:"handoffToken,omitempty"`
	PortableSession string                  `json:"portableSession,omitempty"`
	HandoffEnvelope *deviceSyncEnvelope     `json:"handoffEnvelope,omitempty"`
	ExpiresAt       int64                   `json:"expiresAt"`
}

type DesktopAgentCommandResult struct {
	NodeSessionID   string                  `json:"nodeSessionId"`
	RequestID       string                  `json:"requestId"`
	Success         bool                    `json:"success"`
	ErrorCode       string                  `json:"errorCode,omitempty"`
	ErrorMessage    string                  `json:"errorMessage,omitempty"`
	Snapshot        *DesktopAgentSnapshot   `json:"snapshot,omitempty"`
	Session         *DesktopAgentTaskDetail `json:"session,omitempty"`
	HandoffToken    string                  `json:"handoffToken,omitempty"`
	PortableSession string                  `json:"portableSession,omitempty"`
	HandoffEnvelope *deviceSyncEnvelope     `json:"handoffEnvelope,omitempty"`
}

type desktopAgentRegisterRequest struct {
	NodeSessionID      string `json:"nodeSessionId"`
	ProtocolVersion    int    `json:"protocolVersion,omitempty"`
	SourcePlatform     string `json:"sourcePlatform,omitempty"`
	SourceArchitecture string `json:"sourceArchitecture,omitempty"`
	ControlAllowed     bool   `json:"controlAllowed"`
	RequestID          string `json:"requestId"`
}
