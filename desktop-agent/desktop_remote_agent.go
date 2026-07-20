package main

import (
	"encoding/json"
	"errors"
	"strings"
	"unicode/utf8"

	desktopbridge "github.com/murong-agent/desktop-bridge"
)

const (
	remoteDesktopMaxSessions     = 500
	remoteDesktopMaxMessages     = 200
	remoteDesktopMaxTitleRunes   = 100
	remoteDesktopMaxContentRunes = 20_000
	remoteDesktopContentBudget   = 1536 * 1024
	remoteDesktopUpdateBudget    = 96 * 1024
	remoteDesktopMaxUpdates      = 6
	remoteDesktopMessageMaxBytes = 64 * 1024
)

func (app *DesktopAgentApp) desktopAgentSnapshot() desktopbridge.DesktopAgentSnapshot {
	summaries := app.store.listSessions()
	if len(summaries) > remoteDesktopMaxSessions {
		summaries = summaries[:remoteDesktopMaxSessions]
	}
	running, approvals, asks := app.desktopAgentRuntimeState()
	result := desktopbridge.DesktopAgentSnapshot{
		Sessions: make([]desktopbridge.DesktopAgentTaskSummary, 0, len(summaries)),
	}
	for _, summary := range summaries {
		_, hasApproval := approvals[summary.ID]
		_, hasQuestion := asks[summary.ID]
		result.Sessions = append(result.Sessions, desktopbridge.DesktopAgentTaskSummary{
			ID:               summary.ID,
			Title:            truncateRunes(summary.Title, remoteDesktopMaxTitleRunes),
			UpdatedAt:        summary.UpdatedAt,
			MessageCount:     summary.MessageCount,
			Running:          running[summary.ID],
			PendingApproval:  hasApproval,
			PendingQuestion:  hasQuestion,
			ExecutionOwner:   summary.ExecutionOwner,
			HandoffStartedAt: summary.HandoffStartedAt,
		})
	}
	if len(summaries) > 0 {
		app.mu.Lock()
		selectedID := app.remoteSelectedSessionID
		app.mu.Unlock()
		if selectedID == "" || !containsRemoteSession(summaries, selectedID) {
			selectedID = summaries[0].ID
			app.mu.Lock()
			app.remoteSelectedSessionID = selectedID
			app.mu.Unlock()
		}
		result.ActiveSession = app.desktopAgentTaskDetailWithState(selectedID, running, approvals, asks)
		for _, summary := range summaries {
			if summary.ID == selectedID || len(result.SessionUpdates) >= remoteDesktopMaxUpdates {
				continue
			}
			detail := app.desktopAgentTaskDetailWithBudget(
				summary.ID,
				running,
				approvals,
				asks,
				remoteDesktopUpdateBudget,
			)
			if detail != nil {
				result.SessionUpdates = append(result.SessionUpdates, *detail)
			}
		}
	}
	return result
}

func (app *DesktopAgentApp) desktopAgentRuntimeState() (map[string]bool, map[string]ApprovalRequest, map[string]AskRequest) {
	app.mu.Lock()
	defer app.mu.Unlock()
	running := make(map[string]bool, len(app.runs))
	for sessionID := range app.runs {
		running[sessionID] = true
	}
	approvals := make(map[string]ApprovalRequest)
	for _, approval := range app.pendingApprovals {
		approvals[approval.SessionID] = approval
	}
	asks := make(map[string]AskRequest)
	for _, request := range app.pendingAsks {
		asks[request.SessionID] = cloneAskRequest(request)
	}
	return running, approvals, asks
}

func (app *DesktopAgentApp) desktopAgentTaskDetail(sessionID string) *desktopbridge.DesktopAgentTaskDetail {
	running, approvals, asks := app.desktopAgentRuntimeState()
	return app.desktopAgentTaskDetailWithState(sessionID, running, approvals, asks)
}

func (app *DesktopAgentApp) desktopAgentTaskDetailWithState(
	sessionID string,
	running map[string]bool,
	approvals map[string]ApprovalRequest,
	asks map[string]AskRequest,
) *desktopbridge.DesktopAgentTaskDetail {
	return app.desktopAgentTaskDetailWithBudget(
		sessionID,
		running,
		approvals,
		asks,
		remoteDesktopContentBudget,
	)
}

func (app *DesktopAgentApp) desktopAgentTaskDetailWithBudget(
	sessionID string,
	running map[string]bool,
	approvals map[string]ApprovalRequest,
	asks map[string]AskRequest,
	contentBudget int,
) *desktopbridge.DesktopAgentTaskDetail {
	session := app.store.getSession(strings.TrimSpace(sessionID))
	if session == nil {
		return nil
	}
	messages := session.Messages
	if len(messages) > remoteDesktopMaxMessages {
		messages = messages[len(messages)-remoteDesktopMaxMessages:]
	}
	detail := &desktopbridge.DesktopAgentTaskDetail{
		ID:               session.ID,
		Title:            truncateRunes(session.Title, remoteDesktopMaxTitleRunes),
		UpdatedAt:        session.UpdatedAt,
		Running:          running[session.ID],
		Messages:         make([]desktopbridge.DesktopAgentMessage, 0, len(messages)),
		MessageCount:     len(session.Messages),
		ExecutionOwner:   sessionExecutionOwner(session),
		HandoffStartedAt: session.ExecutionHandoff.StartedAt,
		WorkflowPlan:     desktopAgentWorkflowPlan(session.WorkflowPlan),
	}
	remaining := contentBudget
	reversed := make([]desktopbridge.DesktopAgentMessage, 0, len(messages))
	for index := len(messages) - 1; index >= 0 && remaining > 0; index-- {
		message := messages[index]
		limit := min(remaining, remoteDesktopMessageMaxBytes)
		if limit <= 2 {
			break
		}
		content, encodedBytes := truncateJSONEncodedString(
			truncateRunes(message.Content, remoteDesktopMaxContentRunes),
			limit,
		)
		remaining -= encodedBytes
		reversed = append(reversed, desktopbridge.DesktopAgentMessage{
			ID:              message.ID,
			Role:            message.Role,
			Content:         content,
			Timestamp:       message.CreatedAt,
			Kind:            message.Kind,
			ToolName:        message.ToolName,
			AttachmentNames: desktopMessageAttachmentNames(message.ImageAttachments),
		})
	}
	for index := len(reversed) - 1; index >= 0; index-- {
		detail.Messages = append(detail.Messages, reversed[index])
	}
	if approval, ok := approvals[session.ID]; ok {
		detail.PendingApproval = &desktopbridge.DesktopAgentApproval{
			ID:        approval.ID,
			SessionID: approval.SessionID,
			ToolName:  approval.ToolName,
			Summary:   truncateRunes(approval.Summary, 500),
			Detail:    truncateRunes(approval.Detail, 1_000),
			Risk:      approval.Risk,
		}
	}
	if request, ok := asks[session.ID]; ok {
		detail.PendingAsk = desktopAgentAskRequest(request)
	}
	return detail
}

func desktopAgentWorkflowPlan(plan *DesktopWorkflowPlan) *desktopbridge.DesktopAgentWorkflowPlan {
	if plan == nil {
		return nil
	}
	result := &desktopbridge.DesktopAgentWorkflowPlan{
		ID:                 truncateRunes(strings.TrimSpace(plan.ID), 128),
		Summary:            truncateRunes(strings.TrimSpace(plan.Summary), 1_000),
		Steps:              make([]string, 0, min(len(plan.Steps), maxWorkflowPlanSteps)),
		CurrentStepIndex:   plan.CurrentStepIndex,
		Status:             plan.Status,
		NextStepHint:       truncateRunes(strings.TrimSpace(plan.NextStepHint), 1_000),
		StepSignOffs:       make([]desktopbridge.DesktopAgentWorkflowStepSignOff, 0, len(plan.StepSignOffs)),
		CreatedAt:          plan.CreatedAt,
		ExecutionStartedAt: plan.ExecutionStartedAt,
		UpdatedAt:          plan.UpdatedAt,
	}
	for _, step := range plan.Steps {
		if len(result.Steps) >= maxWorkflowPlanSteps {
			break
		}
		result.Steps = append(result.Steps, truncateRunes(strings.TrimSpace(step), 500))
	}
	for _, signOff := range plan.StepSignOffs {
		converted := desktopbridge.DesktopAgentWorkflowStepSignOff{
			StepIndex: signOff.StepIndex, ResultSummary: truncateRunes(strings.TrimSpace(signOff.ResultSummary), 2_000),
			MatchedEvidence: signOff.MatchedEvidence, TotalEvidence: signOff.TotalEvidence, SignedOffAt: signOff.SignedOffAt,
			MatchedToolNames: make([]string, 0, min(len(signOff.MatchedToolNames), 8)),
		}
		for _, name := range signOff.MatchedToolNames {
			if len(converted.MatchedToolNames) >= 8 {
				break
			}
			if name = truncateRunes(strings.TrimSpace(name), 100); name != "" && !containsFold(converted.MatchedToolNames, name) {
				converted.MatchedToolNames = append(converted.MatchedToolNames, name)
			}
		}
		result.StepSignOffs = append(result.StepSignOffs, converted)
	}
	return result
}

func desktopAgentAskRequest(request AskRequest) *desktopbridge.DesktopAgentAskRequest {
	result := &desktopbridge.DesktopAgentAskRequest{
		ID: request.ID, SessionID: request.SessionID, CreatedAt: request.CreatedAt,
		Questions: make([]desktopbridge.DesktopAgentAskQuestion, 0, len(request.Questions)),
	}
	for _, question := range request.Questions {
		converted := desktopbridge.DesktopAgentAskQuestion{
			ID: truncateRunes(question.ID, 32), Header: truncateRunes(question.Header, askUserMaxHeaderRunes),
			Question: truncateRunes(question.Question, askUserMaxQuestionRunes), MultiSelect: question.MultiSelect,
			Options: make([]desktopbridge.DesktopAgentAskOption, 0, len(question.Options)),
		}
		for _, option := range question.Options {
			converted.Options = append(converted.Options, desktopbridge.DesktopAgentAskOption{
				Label: truncateRunes(option.Label, askUserMaxLabelRunes), Description: truncateRunes(option.Description, askUserMaxDescriptionRunes),
			})
		}
		result.Questions = append(result.Questions, converted)
	}
	return result
}

func desktopMessageAttachmentNames(values []MessageImageAttachment) []string {
	result := make([]string, 0, len(values))
	for _, value := range values {
		if name := truncateRunes(strings.TrimSpace(value.FileName), 255); name != "" {
			result = append(result, name)
		}
	}
	return result
}

func (app *DesktopAgentApp) handleDesktopAgentCommand(command desktopbridge.DesktopAgentCommand) desktopbridge.DesktopAgentCommandResult {
	result := desktopbridge.DesktopAgentCommandResult{}
	fail := func(code string, err error) desktopbridge.DesktopAgentCommandResult {
		result.ErrorCode = code
		result.ErrorMessage = err.Error()
		return result
	}
	sessionID := strings.TrimSpace(command.SessionID)
	switch strings.ToLower(strings.TrimSpace(command.Operation)) {
	case "refresh":
		snapshot := app.desktopAgentSnapshot()
		result.Success = true
		result.Snapshot = &snapshot
	case "get_session":
		detail := app.desktopAgentTaskDetail(sessionID)
		if detail == nil {
			return fail("session_not_found", errors.New("桌面任务不存在"))
		}
		app.selectRemoteSession(sessionID)
		result.Success = true
		result.Session = detail
	case "send_message":
		if err := app.SendMessage(SendMessageRequest{SessionID: sessionID, Content: command.Content}); err != nil {
			return fail("message_conflict", err)
		}
		result.Success = true
		result.Session = app.desktopAgentTaskDetail(sessionID)
	case "cancel":
		if !app.CancelRun(sessionID) {
			return fail("run_not_active", errors.New("桌面任务当前没有运行"))
		}
		result.Success = true
		result.Session = app.desktopAgentTaskDetail(sessionID)
	case "approval":
		app.mu.Lock()
		approval, exists := app.pendingApprovals[command.ApprovalID]
		app.mu.Unlock()
		if !exists || approval.SessionID != sessionID || command.Approve == nil {
			return fail("approval_stale", errors.New("审批已经处理或不属于当前桌面任务"))
		}
		if !app.ResolveApproval(ApprovalDecision{ID: command.ApprovalID, Approve: *command.Approve}) {
			return fail("approval_stale", errors.New("审批已经处理"))
		}
		result.Success = true
		result.Session = app.desktopAgentTaskDetail(sessionID)
	case "ask":
		app.mu.Lock()
		request, exists := app.pendingAsks[command.AskID]
		app.mu.Unlock()
		if !exists || request.SessionID != sessionID {
			return fail("ask_stale", errors.New("问题已经处理或不属于当前桌面任务"))
		}
		answers := make([]AskAnswer, 0, len(command.AskAnswers))
		for _, answer := range command.AskAnswers {
			answers = append(answers, AskAnswer{QuestionID: answer.QuestionID, SelectedOptions: append([]string(nil), answer.SelectedOptions...)})
		}
		if _, err := app.ResolveAsk(AskDecision{ID: command.AskID, Answers: answers, Dismiss: command.DismissAsk}); err != nil {
			return fail("ask_invalid", err)
		}
		result.Success = true
		result.Session = app.desktopAgentTaskDetail(sessionID)
	case "begin_handoff":
		pkg, err := app.beginPhoneSessionHandoff(sessionID)
		if err != nil {
			return fail("handoff_conflict", err)
		}
		result.Success = true
		result.HandoffToken = pkg.Token
		result.PortableSession = pkg.PortableSession
	case "return_handoff":
		session, err := app.store.returnSessionHandoff(sessionID, command.HandoffToken, command.PortableSession)
		if err != nil {
			return fail("handoff_merge_conflict", err)
		}
		app.emitSessionsChanged(session)
		result.Success = true
		result.Session = app.desktopAgentTaskDetail(sessionID)
	case "abort_handoff":
		session, err := app.store.abortSessionHandoff(sessionID, command.HandoffToken)
		if err != nil {
			return fail("handoff_stale", err)
		}
		app.emitSessionsChanged(session)
		result.Success = true
		result.Session = app.desktopAgentTaskDetail(sessionID)
	default:
		return fail("unsupported_operation", errors.New("电脑端不支持这个桌面任务操作"))
	}
	return result
}

func (app *DesktopAgentApp) beginPhoneSessionHandoff(sessionID string) (SessionHandoffPackage, error) {
	sessionID = strings.TrimSpace(sessionID)
	app.mu.Lock()
	if _, running := app.runs[sessionID]; running {
		app.mu.Unlock()
		return SessionHandoffPackage{}, errors.New("当前任务仍在运行，结束后才能交给手机")
	}
	for _, approval := range app.pendingApprovals {
		if approval.SessionID == sessionID {
			app.mu.Unlock()
			return SessionHandoffPackage{}, errors.New("当前任务仍有待审批工具，处理后才能交给手机")
		}
	}
	for _, request := range app.pendingAsks {
		if request.SessionID == sessionID {
			app.mu.Unlock()
			return SessionHandoffPackage{}, errors.New("当前任务仍有待回答问题，处理后才能交给手机")
		}
	}
	for _, job := range app.activeSubagentJobs {
		if job.SessionID == sessionID {
			app.mu.Unlock()
			return SessionHandoffPackage{}, errors.New("当前任务仍有后台子代理，结束后才能交给手机")
		}
	}
	pkg, session, err := app.store.beginSessionHandoff(sessionID)
	app.mu.Unlock()
	if err != nil {
		return SessionHandoffPackage{}, err
	}
	app.emitSessionsChanged(session)
	return pkg, nil
}

func containsRemoteSession(summaries []SessionSummary, sessionID string) bool {
	for _, summary := range summaries {
		if summary.ID == sessionID {
			return true
		}
	}
	return false
}

func truncateUTF8Bytes(value string, maxBytes int) string {
	if maxBytes <= 0 {
		return ""
	}
	if len(value) <= maxBytes {
		return value
	}
	end := 0
	for index, runeValue := range value {
		size := utf8.RuneLen(runeValue)
		if index+size > maxBytes {
			break
		}
		end = index + size
	}
	return value[:end]
}

// truncateJSONEncodedString budgets the actual encoding/json representation instead of raw UTF-8.
// Tool output can contain quotes, newlines or control bytes whose JSON representation is much larger.
func truncateJSONEncodedString(value string, maxBytes int) (string, int) {
	if maxBytes <= 2 {
		return "", 2
	}
	value = truncateUTF8Bytes(value, maxBytes-2)
	for {
		encoded, err := json.Marshal(value)
		if err == nil && len(encoded) <= maxBytes {
			return value, len(encoded)
		}
		if value == "" {
			return "", 2
		}
		encodedLength := len(encoded)
		if encodedLength <= 0 {
			encodedLength = len(value) + 2
		}
		nextLimit := max(0, len(value)*maxBytes/encodedLength-1)
		if nextLimit >= len(value) {
			nextLimit = len(value) - 1
		}
		value = truncateUTF8Bytes(value, nextLimit)
	}
}
