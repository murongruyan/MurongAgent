package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"regexp"
	"sort"
	"strings"
	"time"
)

const (
	workflowPlanReady     = "ready"
	workflowPlanExecuting = "executing"
	workflowPlanBlocked   = "blocked"
	workflowPlanCompleted = "completed"
	maxWorkflowPlanSteps  = 8
	maxWorkflowPlanRaw    = 40_000
	completeStepToolName  = "complete_step"
)

var workflowStepLinePattern = regexp.MustCompile(`^\s*(?:\d+[.)]|[-*•]|\[[ xX]\])\s+(.+?)\s*$`)

func parseDesktopWorkflowPlan(goal, rawPlan, sourceMessageID string, now int64) (*DesktopWorkflowPlan, error) {
	goal = strings.TrimSpace(goal)
	rawPlan = strings.TrimSpace(rawPlan)
	sourceMessageID = strings.TrimSpace(sourceMessageID)
	if goal == "" || len([]rune(goal)) > 20_000 {
		return nil, errors.New("计划目标为空或超过 20000 字符")
	}
	if rawPlan == "" || len([]rune(rawPlan)) > maxWorkflowPlanRaw {
		return nil, fmt.Errorf("计划内容为空或超过 %d 字符", maxWorkflowPlanRaw)
	}
	if sourceMessageID == "" {
		return nil, errors.New("计划缺少来源消息")
	}
	lines := strings.Split(rawPlan, "\n")
	steps := []string{}
	summary := ""
	for _, line := range lines {
		trimmed := strings.TrimSpace(line)
		if trimmed == "" {
			continue
		}
		if match := workflowStepLinePattern.FindStringSubmatch(trimmed); len(match) == 2 {
			step := truncateRunes(stripWorkflowMarkdown(match[1]), 500)
			if step != "" && !containsFold(steps, step) {
				steps = append(steps, step)
				if len(steps) >= maxWorkflowPlanSteps {
					break
				}
			}
			continue
		}
		candidate := stripWorkflowMarkdown(trimmed)
		if strings.HasPrefix(strings.ToLower(candidate), "summary:") {
			candidate = strings.TrimSpace(candidate[len("summary:"):])
		} else if strings.HasPrefix(candidate, "摘要：") {
			candidate = strings.TrimSpace(strings.TrimPrefix(candidate, "摘要："))
		}
		if summary == "" && candidate != "" && !isWorkflowPlanHeading(candidate) {
			summary = truncateRunes(candidate, 1_000)
		}
	}
	if summary == "" {
		summary = "按当前目标生成的执行计划"
	}
	if len(steps) == 0 {
		steps = []string{truncateRunes(summary, 500)}
	}
	if now <= 0 {
		now = time.Now().UnixMilli()
	}
	return &DesktopWorkflowPlan{
		ID: newID("plan"), Goal: goal, Summary: summary, Steps: steps,
		Status: workflowPlanReady, NextStepHint: steps[0], RawPlan: rawPlan,
		SourceMessageID: sourceMessageID, CreatedAt: now, UpdatedAt: now,
	}, nil
}

func stripWorkflowMarkdown(value string) string {
	value = strings.TrimSpace(value)
	value = strings.TrimSpace(strings.TrimLeft(value, "#>"))
	value = strings.TrimSpace(strings.Trim(value, "`*_"))
	return value
}

func isWorkflowPlanHeading(value string) bool {
	normalized := strings.ToLower(strings.TrimSpace(strings.TrimSuffix(value, ":")))
	switch normalized {
	case "plan", "steps", "execution plan", "计划", "步骤", "执行计划", "实施计划":
		return true
	default:
		return false
	}
}

func containsFold(values []string, wanted string) bool {
	for _, value := range values {
		if strings.EqualFold(strings.TrimSpace(value), strings.TrimSpace(wanted)) {
			return true
		}
	}
	return false
}

func sessionWorkflowGoal(session *ChatSession, sourceMessageID string) string {
	if session == nil {
		return ""
	}
	if goal := strings.TrimSpace(session.Goal); goal != "" {
		return goal
	}
	for index := len(session.Messages) - 1; index >= 0; index-- {
		message := session.Messages[index]
		if message.ID == sourceMessageID {
			continue
		}
		if message.Role == "user" && strings.TrimSpace(message.Content) != "" {
			return truncateRunes(message.Content, 20_000)
		}
	}
	return ""
}

func (store *desktopStore) captureWorkflowPlan(sessionID, sourceMessageID, rawPlan string) (*ChatSession, error) {
	store.mu.Lock()
	defer store.mu.Unlock()
	session := store.sessions[strings.TrimSpace(sessionID)]
	if session == nil {
		return nil, errors.New("会话不存在")
	}
	if err := requireDesktopExecutionAuthority(session); err != nil {
		return nil, err
	}
	plan, err := parseDesktopWorkflowPlan(sessionWorkflowGoal(session, sourceMessageID), rawPlan, sourceMessageID, time.Now().UnixMilli())
	if err != nil {
		return nil, err
	}
	previousPlan := cloneDesktopWorkflowPlan(session.WorkflowPlan)
	session.WorkflowPlan = plan
	if err := store.saveSessionsLocked(); err != nil {
		session.WorkflowPlan = previousPlan
		return nil, err
	}
	return cloneSession(session), nil
}

func (store *desktopStore) startWorkflowPlan(sessionID string) (*ChatSession, *DesktopWorkflowPlan, error) {
	store.mu.Lock()
	defer store.mu.Unlock()
	session := store.sessions[strings.TrimSpace(sessionID)]
	if session == nil {
		return nil, nil, errors.New("会话不存在")
	}
	if err := requireDesktopExecutionAuthority(session); err != nil {
		return nil, nil, err
	}
	plan := session.WorkflowPlan
	if plan == nil {
		return nil, nil, errors.New("当前任务没有可执行计划")
	}
	if plan.Status == workflowPlanCompleted {
		return nil, nil, errors.New("当前计划已经完成；请先清除或重新生成计划")
	}
	if len(plan.Steps) == 0 || plan.CurrentStepIndex < 0 || plan.CurrentStepIndex >= len(plan.Steps) {
		return nil, nil, errors.New("当前计划步骤无效")
	}
	previousPlan := cloneDesktopWorkflowPlan(plan)
	previousPlanMode := session.PlanModeEnabled
	now := time.Now().UnixMilli()
	plan.Status = workflowPlanExecuting
	plan.NextStepHint = "下一步：" + plan.Steps[plan.CurrentStepIndex]
	if plan.ExecutionStartedAt == 0 {
		plan.ExecutionStartedAt = now
	}
	plan.UpdatedAt = now
	session.PlanModeEnabled = false
	if err := store.saveSessionsLocked(); err != nil {
		session.WorkflowPlan = previousPlan
		session.PlanModeEnabled = previousPlanMode
		return nil, nil, err
	}
	return cloneSession(session), previousPlan, nil
}

func (store *desktopStore) restoreWorkflowPlan(sessionID string, previous *DesktopWorkflowPlan, planMode bool) {
	store.mu.Lock()
	defer store.mu.Unlock()
	if session := store.sessions[strings.TrimSpace(sessionID)]; session != nil {
		session.WorkflowPlan = cloneDesktopWorkflowPlan(previous)
		session.PlanModeEnabled = planMode
		_ = store.saveSessionsLocked()
	}
}

func (store *desktopStore) dismissWorkflowPlan(sessionID string) (*ChatSession, error) {
	store.mu.Lock()
	defer store.mu.Unlock()
	session := store.sessions[strings.TrimSpace(sessionID)]
	if session == nil {
		return nil, errors.New("会话不存在")
	}
	if err := requireDesktopExecutionAuthority(session); err != nil {
		return nil, err
	}
	previous := cloneDesktopWorkflowPlan(session.WorkflowPlan)
	session.WorkflowPlan = nil
	if err := store.saveSessionsLocked(); err != nil {
		session.WorkflowPlan = previous
		return nil, err
	}
	return cloneSession(session), nil
}

func (store *desktopStore) settleWorkflowPlan(sessionID, reason string) (*ChatSession, error) {
	store.mu.Lock()
	defer store.mu.Unlock()
	session := store.sessions[strings.TrimSpace(sessionID)]
	if session == nil || session.WorkflowPlan == nil || session.WorkflowPlan.Status != workflowPlanExecuting {
		return cloneSession(session), nil
	}
	plan := session.WorkflowPlan
	if plan.CurrentStepIndex >= len(plan.Steps) {
		plan.Status = workflowPlanCompleted
		plan.NextStepHint = "计划步骤已全部通过工具证据签收。"
	} else {
		plan.Status = workflowPlanBlocked
		reason = strings.TrimSpace(reason)
		if reason == "" {
			reason = "Agent 本轮已经结束，但当前步骤尚未通过 complete_step 证据签收。"
		}
		plan.NextStepHint = truncateRunes(reason+" 当前步骤："+plan.Steps[plan.CurrentStepIndex], 1_000)
	}
	plan.UpdatedAt = time.Now().UnixMilli()
	if err := store.saveSessionsLocked(); err != nil {
		return nil, err
	}
	return cloneSession(session), nil
}

func workflowExecutionPrompt(plan *DesktopWorkflowPlan) string {
	if plan == nil || (plan.Status != workflowPlanExecuting && plan.Status != workflowPlanBlocked) {
		return ""
	}
	var output strings.Builder
	output.WriteString("当前为已经由用户确认的规范执行计划。必须按步骤推进，不能只输出计划或未经证据就声称完成。\n")
	output.WriteString("计划目标：\n" + plan.Goal + "\n\n计划摘要：\n" + plan.Summary + "\n\n步骤：\n")
	for index, step := range plan.Steps {
		state := "待执行"
		if index < plan.CurrentStepIndex {
			state = "已签收"
		} else if index == plan.CurrentStepIndex {
			state = "当前"
		}
		output.WriteString(fmt.Sprintf("%d. [%s] %s\n", index+1, state, step))
	}
	output.WriteString("\n每完成一个步骤后必须调用 complete_step，并用本计划执行期间真实成功的工具收据逐条匹配 evidence。")
	output.WriteString(" 未签收完所有步骤时不得声称整个计划完成；若确实受阻，明确说明阻塞，不要伪造证据。")
	return output.String()
}

func cloneDesktopWorkflowPlan(plan *DesktopWorkflowPlan) *DesktopWorkflowPlan {
	if plan == nil {
		return nil
	}
	copy := *plan
	copy.Steps = append([]string(nil), plan.Steps...)
	copy.StepSignOffs = append([]WorkflowStepSignOff(nil), plan.StepSignOffs...)
	for index := range copy.StepSignOffs {
		copy.StepSignOffs[index].MatchedToolNames = append([]string(nil), plan.StepSignOffs[index].MatchedToolNames...)
		copy.StepSignOffs[index].EvidenceMessageIDs = append([]string(nil), plan.StepSignOffs[index].EvidenceMessageIDs...)
	}
	return &copy
}

func reconcileWorkflowPlanWithMessages(plan *DesktopWorkflowPlan, messages []ChatMessage) *DesktopWorkflowPlan {
	plan = cloneDesktopWorkflowPlan(plan)
	if plan == nil {
		return nil
	}
	messageIDs := map[string]bool{}
	for _, message := range messages {
		messageIDs[message.ID] = true
	}
	if !messageIDs[plan.SourceMessageID] {
		return nil
	}
	valid := []WorkflowStepSignOff{}
	for _, signOff := range plan.StepSignOffs {
		allPresent := len(signOff.EvidenceMessageIDs) > 0
		for _, id := range signOff.EvidenceMessageIDs {
			allPresent = allPresent && messageIDs[id]
		}
		if allPresent {
			valid = append(valid, signOff)
		}
	}
	sort.Slice(valid, func(i, j int) bool { return valid[i].StepIndex < valid[j].StepIndex })
	contiguous := []WorkflowStepSignOff{}
	for _, signOff := range valid {
		if signOff.StepIndex != len(contiguous) {
			break
		}
		contiguous = append(contiguous, signOff)
	}
	plan.StepSignOffs = contiguous
	plan.CurrentStepIndex = len(contiguous)
	if plan.CurrentStepIndex >= len(plan.Steps) {
		plan.Status = workflowPlanCompleted
		plan.NextStepHint = "计划步骤已全部通过工具证据签收。"
	} else if plan.Status != workflowPlanReady {
		plan.Status = workflowPlanBlocked
		plan.NextStepHint = "会话历史发生回退或分叉，请从当前未签收步骤继续：" + plan.Steps[plan.CurrentStepIndex]
	}
	plan.UpdatedAt = time.Now().UnixMilli()
	return plan
}

func remapWorkflowPlanMessageIDs(plan *DesktopWorkflowPlan, messageIDs map[string]string) *DesktopWorkflowPlan {
	plan = cloneDesktopWorkflowPlan(plan)
	if plan == nil {
		return nil
	}
	sourceID, ok := messageIDs[plan.SourceMessageID]
	if !ok {
		return nil
	}
	plan.SourceMessageID = sourceID
	for signOffIndex := range plan.StepSignOffs {
		for evidenceIndex, evidenceID := range plan.StepSignOffs[signOffIndex].EvidenceMessageIDs {
			if mappedID, exists := messageIDs[evidenceID]; exists {
				plan.StepSignOffs[signOffIndex].EvidenceMessageIDs[evidenceIndex] = mappedID
			}
		}
	}
	return plan
}

type completeStepEvidence struct {
	Summary          string `json:"summary"`
	ToolName         string `json:"toolName"`
	Command          string `json:"command"`
	Path             string `json:"path"`
	MessageReference string `json:"messageReference"`
	SessionID        string `json:"sessionId"`
	MustSucceed      *bool  `json:"mustSucceed"`
}

type completeStepArguments struct {
	Step     string                 `json:"step"`
	Result   string                 `json:"result"`
	Evidence []completeStepEvidence `json:"evidence"`
}

func completeStepToolDefinition() any {
	return functionTool(completeStepToolName, "用当前计划执行期间的真实工具收据签收刚完成的步骤；没有匹配证据时会拒绝推进计划", map[string]any{
		"step":   map[string]any{"type": "string", "description": "当前完成的计划步骤，必须与计划当前步骤一致"},
		"result": map[string]any{"type": "string", "description": "步骤完成结果摘要"},
		"evidence": map[string]any{"type": "array", "minItems": 1, "maxItems": 8, "items": map[string]any{
			"type": "object", "additionalProperties": false,
			"properties": map[string]any{
				"summary": map[string]any{"type": "string"}, "toolName": map[string]any{"type": "string"},
				"command": map[string]any{"type": "string"}, "path": map[string]any{"type": "string"},
				"messageReference": map[string]any{"type": "string"}, "sessionId": map[string]any{"type": "string"},
				"mustSucceed": map[string]any{"type": "boolean"},
			}, "required": []string{"summary"},
		}},
	}, []string{"step", "result", "evidence"})
}

func parseCompleteStepArguments(arguments string) (completeStepArguments, error) {
	decoder := json.NewDecoder(bytes.NewBufferString(arguments))
	decoder.DisallowUnknownFields()
	payload := completeStepArguments{}
	if err := decoder.Decode(&payload); err != nil {
		return payload, fmt.Errorf("complete_step 参数无效：%w", err)
	}
	var extra any
	if err := decoder.Decode(&extra); err != io.EOF {
		if err == nil {
			return payload, errors.New("complete_step 参数只能包含一个 JSON 对象")
		}
		return payload, fmt.Errorf("complete_step 参数尾部无效：%w", err)
	}
	payload.Step = strings.TrimSpace(payload.Step)
	payload.Result = strings.TrimSpace(payload.Result)
	if payload.Step == "" || len([]rune(payload.Step)) > 500 || payload.Result == "" || len([]rune(payload.Result)) > 2_000 {
		return payload, errors.New("complete_step 的 step/result 为空或过长")
	}
	if len(payload.Evidence) < 1 || len(payload.Evidence) > 8 {
		return payload, errors.New("complete_step 必须包含 1 到 8 条 evidence")
	}
	for index := range payload.Evidence {
		evidence := &payload.Evidence[index]
		evidence.Summary = strings.TrimSpace(evidence.Summary)
		evidence.ToolName = strings.TrimSpace(evidence.ToolName)
		evidence.Command = strings.TrimSpace(evidence.Command)
		evidence.Path = strings.TrimSpace(evidence.Path)
		evidence.MessageReference = strings.TrimSpace(evidence.MessageReference)
		evidence.SessionID = strings.TrimSpace(evidence.SessionID)
		if evidence.Summary == "" || len([]rune(evidence.Summary)) > 500 {
			return payload, fmt.Errorf("complete_step 第 %d 条 evidence 摘要为空或过长", index+1)
		}
		if evidence.ToolName == "" && evidence.Command == "" && evidence.Path == "" && evidence.MessageReference == "" && evidence.SessionID == "" {
			return payload, fmt.Errorf("complete_step 第 %d 条 evidence 没有任何可匹配锚点", index+1)
		}
		anchorLength := len([]rune(evidence.ToolName)) + len([]rune(evidence.Command)) + len([]rune(evidence.Path)) +
			len([]rune(evidence.MessageReference)) + len([]rune(evidence.SessionID))
		if anchorLength > 4_000 {
			return payload, fmt.Errorf("complete_step 第 %d 条 evidence 过长", index+1)
		}
	}
	return payload, nil
}

func (app *DesktopAgentApp) executeCompleteStep(ctx context.Context, sessionID string, call modelToolCall) (string, error) {
	if err := ctx.Err(); err != nil {
		return "", err
	}
	payload, err := parseCompleteStepArguments(call.Function.Arguments)
	if err != nil {
		return "", err
	}
	app.store.mu.Lock()
	defer app.store.mu.Unlock()
	session := app.store.sessions[strings.TrimSpace(sessionID)]
	if session == nil {
		return "", errors.New("会话不存在")
	}
	if err := requireDesktopExecutionAuthority(session); err != nil {
		return "", err
	}
	plan := session.WorkflowPlan
	if plan == nil || (plan.Status != workflowPlanExecuting && plan.Status != workflowPlanBlocked) {
		return "", errors.New("当前没有正在执行或可继续的规范计划")
	}
	if plan.CurrentStepIndex < 0 || plan.CurrentStepIndex >= len(plan.Steps) {
		return "", errors.New("当前计划没有可签收步骤")
	}
	expectedStep := plan.Steps[plan.CurrentStepIndex]
	if !workflowStepMatches(expectedStep, payload.Step) {
		return "", fmt.Errorf("complete_step 必须签收当前步骤：%s", expectedStep)
	}
	receipts := workflowToolReceipts(session)
	if len(receipts) == 0 {
		return "", errors.New("当前计划执行期间没有可供签收的真实工具收据")
	}
	remaining := append([]ChatMessage(nil), receipts...)
	matched := make([]ChatMessage, 0, len(payload.Evidence))
	for index, evidence := range payload.Evidence {
		matchIndex := -1
		for candidate := len(remaining) - 1; candidate >= 0; candidate-- {
			if completeStepEvidenceMatches(evidence, remaining[candidate]) {
				matchIndex = candidate
				break
			}
		}
		if matchIndex < 0 {
			return "", fmt.Errorf("第 %d 条 evidence 没有匹配到真实工具收据：%s", index+1, evidence.Summary)
		}
		matched = append(matched, remaining[matchIndex])
		remaining = append(remaining[:matchIndex], remaining[matchIndex+1:]...)
	}
	matchedTools := []string{}
	messageIDs := []string{}
	for _, receipt := range matched {
		if !containsFold(matchedTools, receipt.ToolName) {
			matchedTools = append(matchedTools, receipt.ToolName)
		}
		messageIDs = append(messageIDs, receipt.ID)
	}
	now := time.Now().UnixMilli()
	signOff := WorkflowStepSignOff{
		StepIndex: plan.CurrentStepIndex, Step: expectedStep, ReportedStep: payload.Step, ResultSummary: payload.Result,
		MatchedEvidence: len(matched), TotalEvidence: len(payload.Evidence), MatchedToolNames: matchedTools,
		EvidenceMessageIDs: messageIDs, SignedOffAt: now,
	}
	previous := cloneDesktopWorkflowPlan(plan)
	plan.StepSignOffs = append(plan.StepSignOffs, signOff)
	plan.CurrentStepIndex++
	if plan.CurrentStepIndex >= len(plan.Steps) {
		plan.Status = workflowPlanCompleted
		plan.NextStepHint = "计划步骤已全部通过工具证据签收。"
	} else {
		plan.Status = workflowPlanExecuting
		plan.NextStepHint = "下一步：" + plan.Steps[plan.CurrentStepIndex]
	}
	plan.UpdatedAt = now
	if err := app.store.saveSessionsLocked(); err != nil {
		session.WorkflowPlan = previous
		return "", err
	}
	return marshalToolResult(map[string]any{
		"success": true, "signedOff": signOff, "planStatus": plan.Status,
		"currentStepIndex": plan.CurrentStepIndex, "totalSteps": len(plan.Steps), "nextStepHint": plan.NextStepHint,
	}), nil
}

func workflowStepMatches(expected, reported string) bool {
	expected = normalizeWorkflowStepText(expected)
	reported = normalizeWorkflowStepText(reported)
	return expected != "" && reported != "" && (expected == reported || strings.Contains(expected, reported) || strings.Contains(reported, expected))
}

func normalizeWorkflowStepText(value string) string {
	value = strings.ToLower(strings.TrimSpace(value))
	replacer := strings.NewReplacer(" ", "", "\t", "", "\n", "", "`", "", "*", "", "_", "", "#", "", ":", "", "：", "", "-", "", ",", "", "，", "", ".", "", "。", "")
	return replacer.Replace(value)
}

func workflowToolReceipts(session *ChatSession) []ChatMessage {
	if session == nil || session.WorkflowPlan == nil {
		return nil
	}
	startedAt := session.WorkflowPlan.ExecutionStartedAt
	used := map[string]bool{}
	for _, signOff := range session.WorkflowPlan.StepSignOffs {
		for _, id := range signOff.EvidenceMessageIDs {
			used[id] = true
		}
	}
	result := []ChatMessage{}
	for _, message := range session.Messages {
		if message.Role == "tool" && message.ToolName != "" && message.ToolName != completeStepToolName &&
			message.ToolStatus != "" && message.CreatedAt >= startedAt && !used[message.ID] {
			result = append(result, message)
		}
	}
	if len(result) > 64 {
		result = result[len(result)-64:]
	}
	return result
}

func completeStepEvidenceMatches(evidence completeStepEvidence, receipt ChatMessage) bool {
	mustSucceed := evidence.MustSucceed == nil || *evidence.MustSucceed
	if mustSucceed && receipt.ToolStatus != "success" {
		return false
	}
	if evidence.ToolName != "" && !strings.EqualFold(evidence.ToolName, receipt.ToolName) {
		return false
	}
	haystack := receipt.ToolArguments + "\n" + receipt.Content
	for _, anchor := range []string{evidence.Command, evidence.Path, evidence.MessageReference, evidence.SessionID} {
		if anchor != "" && !strings.Contains(strings.ToLower(haystack), strings.ToLower(anchor)) {
			return false
		}
	}
	return true
}

func validateDesktopWorkflowPlan(plan *DesktopWorkflowPlan, messages []ChatMessage) error {
	if plan == nil {
		return nil
	}
	if strings.TrimSpace(plan.ID) == "" || strings.TrimSpace(plan.Goal) == "" || strings.TrimSpace(plan.Summary) == "" ||
		strings.TrimSpace(plan.RawPlan) == "" || strings.TrimSpace(plan.SourceMessageID) == "" ||
		len([]rune(plan.Goal)) > 20_000 || len([]rune(plan.Summary)) > 1_000 || len([]rune(plan.RawPlan)) > maxWorkflowPlanRaw ||
		len([]rune(plan.NextStepHint)) > 1_000 ||
		len(plan.Steps) < 1 || len(plan.Steps) > maxWorkflowPlanSteps || plan.CurrentStepIndex < 0 || plan.CurrentStepIndex > len(plan.Steps) ||
		plan.CreatedAt <= 0 || plan.UpdatedAt < plan.CreatedAt || plan.ExecutionStartedAt < 0 {
		return errors.New("计划基础字段无效")
	}
	switch plan.Status {
	case workflowPlanReady, workflowPlanExecuting, workflowPlanBlocked, workflowPlanCompleted:
	default:
		return errors.New("计划状态无效")
	}
	if plan.Status == workflowPlanCompleted && plan.CurrentStepIndex != len(plan.Steps) {
		return errors.New("已完成计划的步骤进度无效")
	}
	if plan.Status == workflowPlanReady {
		if plan.CurrentStepIndex != 0 || len(plan.StepSignOffs) != 0 || plan.ExecutionStartedAt != 0 {
			return errors.New("待执行计划不应包含执行进度")
		}
	} else if plan.ExecutionStartedAt < plan.CreatedAt {
		return errors.New("执行中的计划缺少有效开始时间")
	}
	if (plan.Status == workflowPlanExecuting || plan.Status == workflowPlanBlocked) && plan.CurrentStepIndex >= len(plan.Steps) {
		return errors.New("未完成计划的步骤进度无效")
	}
	if len(plan.StepSignOffs) != plan.CurrentStepIndex {
		return errors.New("计划签收数量与步骤进度不一致")
	}
	messageByID := map[string]ChatMessage{}
	for _, message := range messages {
		messageByID[message.ID] = message
	}
	source, sourceExists := messageByID[plan.SourceMessageID]
	if !sourceExists || source.Role != "assistant" || source.Kind != "plan" || strings.TrimSpace(source.Content) != strings.TrimSpace(plan.RawPlan) {
		return errors.New("计划来源消息不存在")
	}
	for index, step := range plan.Steps {
		if strings.TrimSpace(step) == "" || len([]rune(step)) > 500 {
			return fmt.Errorf("计划第 %d 个步骤无效", index+1)
		}
	}
	usedEvidence := map[string]bool{}
	for expectedIndex, signOff := range plan.StepSignOffs {
		if signOff.StepIndex != expectedIndex || signOff.Step != plan.Steps[expectedIndex] || !workflowStepMatches(signOff.Step, signOff.ReportedStep) ||
			strings.TrimSpace(signOff.ResultSummary) == "" || len([]rune(signOff.ResultSummary)) > 2_000 ||
			signOff.MatchedEvidence < 1 || signOff.TotalEvidence != signOff.MatchedEvidence || len(signOff.EvidenceMessageIDs) != signOff.MatchedEvidence ||
			signOff.SignedOffAt < plan.ExecutionStartedAt || signOff.SignedOffAt > plan.UpdatedAt {
			return errors.New("计划步骤签收无效")
		}
		actualToolNames := []string{}
		for _, id := range signOff.EvidenceMessageIDs {
			receipt, exists := messageByID[id]
			if !exists || usedEvidence[id] || receipt.Role != "tool" || receipt.ToolName == "" || receipt.ToolName == completeStepToolName ||
				receipt.ToolStatus == "" || receipt.CreatedAt < plan.ExecutionStartedAt || receipt.CreatedAt > signOff.SignedOffAt {
				return errors.New("计划签收引用的工具消息不存在")
			}
			usedEvidence[id] = true
			if !containsFold(actualToolNames, receipt.ToolName) {
				actualToolNames = append(actualToolNames, receipt.ToolName)
			}
		}
		if len(actualToolNames) != len(signOff.MatchedToolNames) {
			return errors.New("计划签收的工具名称无效")
		}
		for _, name := range signOff.MatchedToolNames {
			if !containsFold(actualToolNames, name) {
				return errors.New("计划签收的工具名称无效")
			}
		}
	}
	return nil
}
