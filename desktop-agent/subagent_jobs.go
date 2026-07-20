package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"
)

const (
	maxPersistedSubagentJobs = 100
	subagentInterruptedText  = "Murong 上次退出时任务仍未完成；执行现场已丢失，未自动重跑。"
)

var activeSubagentJobStatuses = map[string]bool{
	"queued": true, "running": true, "cancelling": true,
}

type activeSubagentJob struct {
	SessionID string
	Cancel    context.CancelFunc
}

func cloneSubagentTaskResults(values []subagentTaskResult) []subagentTaskResult {
	result := append([]subagentTaskResult(nil), values...)
	for index := range result {
		result[index].DependsOn = append([]int(nil), result[index].DependsOn...)
	}
	return result
}

func cloneSubagentBackgroundJobs(values []SubagentBackgroundJob) []SubagentBackgroundJob {
	result := append([]SubagentBackgroundJob(nil), values...)
	for index := range result {
		result[index].Results = cloneSubagentTaskResults(result[index].Results)
	}
	return result
}

func normalizeRestoredSubagentJobs(values []SubagentBackgroundJob, restoredAt int64) ([]SubagentBackgroundJob, bool) {
	if restoredAt <= 0 {
		restoredAt = time.Now().UnixMilli()
	}
	changed := false
	if len(values) > maxPersistedSubagentJobs {
		values = values[len(values)-maxPersistedSubagentJobs:]
		changed = true
	}
	result := make([]SubagentBackgroundJob, 0, len(values))
	seen := map[string]bool{}
	for _, value := range values {
		value.ID = strings.TrimSpace(value.ID)
		if value.ID == "" || seen[value.ID] {
			changed = true
			continue
		}
		seen[value.ID] = true
		value.Label = truncateRunes(value.Label, 100)
		value.ParentGoal = truncateRunes(value.ParentGoal, 20_000)
		value.StatusMessage = truncateRunes(value.StatusMessage, 2_000)
		if value.Label == "" {
			value.Label = "后台子代理"
			changed = true
		}
		if value.TaskCount < 1 || value.TaskCount > maxSubagentBatchTasks {
			value.TaskCount = max(1, min(maxSubagentBatchTasks, len(value.Results)))
			changed = true
		}
		if activeSubagentJobStatuses[value.Status] {
			value.Status = "interrupted"
			value.StatusMessage = subagentInterruptedText
			value.FinishedAt = restoredAt
			changed = true
		}
		if !isSubagentBackgroundTerminalStatus(value.Status) {
			value.Status = "interrupted"
			value.StatusMessage = "后台子代理状态无效，已安全标记为中断。"
			value.FinishedAt = restoredAt
			changed = true
		}
		value.Results = cloneSubagentTaskResults(value.Results)
		result = append(result, value)
	}
	return result, changed
}

func isSubagentBackgroundTerminalStatus(status string) bool {
	switch status {
	case "completed", "failed", "cancelled", "interrupted":
		return true
	default:
		return false
	}
}

func validateSubagentBackgroundJobs(values []SubagentBackgroundJob) error {
	if len(values) > maxPersistedSubagentJobs {
		return fmt.Errorf("后台子代理记录超过 %d 项", maxPersistedSubagentJobs)
	}
	seen := map[string]bool{}
	for _, job := range values {
		if strings.TrimSpace(job.ID) == "" || seen[job.ID] {
			return errors.New("后台子代理包含空白或重复 ID")
		}
		seen[job.ID] = true
		if strings.TrimSpace(job.Label) == "" || len([]rune(job.Label)) > 100 || len([]rune(job.ParentGoal)) > 20_000 || len([]rune(job.StatusMessage)) > 2_000 {
			return fmt.Errorf("后台子代理 %s 的名称、目标或状态消息无效", job.ID)
		}
		if job.TaskCount < 1 || job.TaskCount > maxSubagentBatchTasks || len(job.Results) > job.TaskCount {
			return fmt.Errorf("后台子代理 %s 的任务数量无效", job.ID)
		}
		if !activeSubagentJobStatuses[job.Status] && !isSubagentBackgroundTerminalStatus(job.Status) {
			return fmt.Errorf("后台子代理 %s 的状态无效", job.ID)
		}
		if job.CreatedAt <= 0 || job.StartedAt < 0 || job.FinishedAt < 0 || job.Completed < 0 || job.Failed < 0 || job.Skipped < 0 || job.Cancelled < 0 ||
			job.Completed+job.Failed+job.Skipped+job.Cancelled > job.TaskCount {
			return fmt.Errorf("后台子代理 %s 的计数或时间无效", job.ID)
		}
		seenResults := map[int]bool{}
		for _, result := range job.Results {
			if result.Index < 1 || result.Index > job.TaskCount || len([]rune(result.Label)) > 100 || len([]rune(result.Goal)) > 20_000 ||
				len([]rune(result.Output)) > maxSubagentTaskResultRunes || len([]rune(result.Error)) > 2_000 {
				return fmt.Errorf("后台子代理 %s 的结果无效", job.ID)
			}
			if seenResults[result.Index] {
				return fmt.Errorf("后台子代理 %s 包含重复结果序号", job.ID)
			}
			seenResults[result.Index] = true
			switch result.Status {
			case "completed", "failed", "skipped", "cancelled":
			default:
				return fmt.Errorf("后台子代理 %s 的结果状态无效", job.ID)
			}
			for _, dependency := range result.DependsOn {
				if dependency < 1 || dependency > job.TaskCount || dependency == result.Index {
					return fmt.Errorf("后台子代理 %s 的结果依赖无效", job.ID)
				}
			}
		}
	}
	return nil
}

func (store *desktopStore) putSubagentBackgroundJob(sessionID string, job SubagentBackgroundJob) (*ChatSession, error) {
	store.mu.Lock()
	defer store.mu.Unlock()
	session := store.sessions[strings.TrimSpace(sessionID)]
	if session == nil {
		return nil, errors.New("会话不存在")
	}
	previousJobs := cloneSubagentBackgroundJobs(session.BackgroundSubagentJobs)
	previousUpdatedAt := session.UpdatedAt
	job.ID = strings.TrimSpace(job.ID)
	if job.ID == "" {
		return nil, errors.New("后台子代理 ID 为空")
	}
	for _, existing := range session.BackgroundSubagentJobs {
		if existing.ID == job.ID {
			return nil, errors.New("后台子代理 ID 已存在")
		}
	}
	job.Results = cloneSubagentTaskResults(job.Results)
	if len(session.BackgroundSubagentJobs) >= maxPersistedSubagentJobs {
		removable := -1
		for index, existing := range session.BackgroundSubagentJobs {
			if isSubagentBackgroundTerminalStatus(existing.Status) {
				removable = index
				break
			}
		}
		if removable < 0 {
			return nil, fmt.Errorf("已有 %d 个后台子代理仍在运行或等待收尾", maxPersistedSubagentJobs)
		}
		session.BackgroundSubagentJobs = append(session.BackgroundSubagentJobs[:removable], session.BackgroundSubagentJobs[removable+1:]...)
	}
	session.BackgroundSubagentJobs = append(session.BackgroundSubagentJobs, job)
	session.UpdatedAt = time.Now().UnixMilli()
	if err := store.saveSessionsLocked(); err != nil {
		session.BackgroundSubagentJobs = previousJobs
		session.UpdatedAt = previousUpdatedAt
		return nil, err
	}
	return cloneSession(session), nil
}

func (store *desktopStore) updateSubagentBackgroundJob(
	sessionID, jobID string,
	transform func(SubagentBackgroundJob) SubagentBackgroundJob,
) (*ChatSession, error) {
	store.mu.Lock()
	defer store.mu.Unlock()
	session := store.sessions[strings.TrimSpace(sessionID)]
	if session == nil {
		return nil, errors.New("会话不存在")
	}
	index := -1
	for candidate := range session.BackgroundSubagentJobs {
		if session.BackgroundSubagentJobs[candidate].ID == strings.TrimSpace(jobID) {
			index = candidate
			break
		}
	}
	if index < 0 {
		return nil, errors.New("后台子代理不存在")
	}
	previousJobs := cloneSubagentBackgroundJobs(session.BackgroundSubagentJobs)
	previousUpdatedAt := session.UpdatedAt
	updated := transform(session.BackgroundSubagentJobs[index])
	updated.ID = session.BackgroundSubagentJobs[index].ID
	updated.Results = cloneSubagentTaskResults(updated.Results)
	session.BackgroundSubagentJobs[index] = updated
	session.UpdatedAt = time.Now().UnixMilli()
	if err := store.saveSessionsLocked(); err != nil {
		session.BackgroundSubagentJobs = previousJobs
		session.UpdatedAt = previousUpdatedAt
		return nil, err
	}
	return cloneSession(session), nil
}

func (store *desktopStore) subagentBackgroundJob(sessionID, jobID string) (SubagentBackgroundJob, bool) {
	store.mu.Lock()
	defer store.mu.Unlock()
	session := store.sessions[strings.TrimSpace(sessionID)]
	if session == nil {
		return SubagentBackgroundJob{}, false
	}
	for _, job := range session.BackgroundSubagentJobs {
		if job.ID == strings.TrimSpace(jobID) {
			job.Results = cloneSubagentTaskResults(job.Results)
			return job, true
		}
	}
	return SubagentBackgroundJob{}, false
}

func (app *DesktopAgentApp) queueBackgroundSubagentPlan(
	sessionID string,
	config desktopConfig,
	plan subagentExecutionPlan,
	policies []subagentPolicy,
) (string, error) {
	if len(plan.Tasks) == 0 || len(plan.Tasks) != len(policies) {
		return "", errors.New("后台子代理计划为空或策略数量不一致")
	}
	now := time.Now().UnixMilli()
	jobID := newID("subagent_job")
	job := SubagentBackgroundJob{
		ID: jobID, Label: plan.Label, ParentGoal: plan.ParentGoal, Status: "queued",
		StatusMessage: "后台任务已提交，正在启动。", TaskCount: len(plan.Tasks), CreatedAt: now,
	}
	session, err := app.store.putSubagentBackgroundJob(sessionID, job)
	if err != nil {
		return "", err
	}
	parent := app.ctx
	if parent == nil {
		parent = context.Background()
	}
	jobContext, cancel := context.WithCancel(parent)
	app.mu.Lock()
	if app.activeSubagentJobs == nil {
		app.activeSubagentJobs = map[string]activeSubagentJob{}
	}
	app.activeSubagentJobs[jobID] = activeSubagentJob{SessionID: sessionID, Cancel: cancel}
	app.mu.Unlock()
	app.emitSessionsChanged(session)
	go app.runBackgroundSubagentPlan(jobContext, sessionID, jobID, config, plan, policies)
	return marshalToolResult(map[string]any{
		"success": true, "background": true, "jobId": jobID, "status": "queued",
		"batch": plan.Label, "taskCount": len(plan.Tasks),
		"message": "后台子代理已启动；可在聊天框上方的后台任务卡查看进度或取消。",
	}), nil
}

func (app *DesktopAgentApp) runBackgroundSubagentPlan(
	ctx context.Context,
	sessionID, jobID string,
	config desktopConfig,
	plan subagentExecutionPlan,
	policies []subagentPolicy,
) {
	defer func() {
		app.mu.Lock()
		delete(app.activeSubagentJobs, jobID)
		app.mu.Unlock()
	}()
	startedAt := time.Now().UnixMilli()
	session, err := app.store.updateSubagentBackgroundJob(sessionID, jobID, func(job SubagentBackgroundJob) SubagentBackgroundJob {
		job.Status = "running"
		job.StatusMessage = "后台子代理正在运行。"
		job.StartedAt = startedAt
		return job
	})
	if err != nil {
		return
	}
	app.emitSessionsChanged(session)

	workspace, workspaceErr := newLocalWorkspace(config.ProjectPath)
	var results []subagentTaskResult
	if workspaceErr != nil {
		results = make([]subagentTaskResult, len(plan.Tasks))
		for index, task := range plan.Tasks {
			results[index] = subagentTaskResult{
				Index: task.Index, Label: task.Label, Goal: task.Goal, DependsOn: append([]int(nil), task.DependsOn...),
				Status: "failed", Error: truncateRunes(workspaceErr.Error(), 2_000),
			}
		}
	} else {
		results = executeSubagentDependencyPlan(ctx, plan, subagentPlanConcurrency(plan), func(taskContext context.Context, task subagentTaskSpec) (string, error) {
			return app.runSubagentPolicy(taskContext, sessionID, config, workspace, policies[task.Index-1])
		})
	}
	annotateSubagentTaskResults(results, policies)
	completed, failed, skipped, cancelled := countSubagentTaskResults(results)
	status := "completed"
	statusMessage := fmt.Sprintf("后台子代理已完成：%d 成功。", completed)
	if cancelled > 0 || ctx.Err() != nil {
		status = "cancelled"
		statusMessage = fmt.Sprintf("后台子代理已取消：%d 完成，%d 取消，%d 跳过。", completed, cancelled, skipped)
	} else if failed > 0 || skipped > 0 {
		status = "failed"
		statusMessage = fmt.Sprintf("后台子代理结束：%d 完成，%d 失败，%d 跳过。", completed, failed, skipped)
	}

	app.mu.Lock()
	shuttingDown := app.shuttingDown
	app.mu.Unlock()
	if shuttingDown {
		return
	}
	finishedAt := time.Now().UnixMilli()
	session, err = app.store.updateSubagentBackgroundJob(sessionID, jobID, func(job SubagentBackgroundJob) SubagentBackgroundJob {
		job.Status = status
		job.StatusMessage = statusMessage
		job.Completed, job.Failed, job.Skipped, job.Cancelled = completed, failed, skipped, cancelled
		job.Results = cloneSubagentTaskResults(results)
		job.FinishedAt = finishedAt
		return job
	})
	if err != nil {
		return
	}
	notice := buildBackgroundSubagentCompletionNotice(plan, statusMessage, results)
	if updated, appendErr := app.store.appendMessage(sessionID, ChatMessage{
		Role: "assistant", Kind: "subagent_background", Content: notice,
	}); appendErr == nil {
		session = updated
	}
	app.emitSessionsChanged(session)
}

func countSubagentTaskResults(results []subagentTaskResult) (completed, failed, skipped, cancelled int) {
	for _, result := range results {
		switch result.Status {
		case "completed":
			completed++
		case "failed":
			failed++
		case "skipped":
			skipped++
		case "cancelled":
			cancelled++
		}
	}
	return
}

func buildBackgroundSubagentCompletionNotice(plan subagentExecutionPlan, statusMessage string, results []subagentTaskResult) string {
	lines := []string{fmt.Sprintf("后台子代理“%s”状态更新", plan.Label), statusMessage}
	for _, result := range results {
		line := fmt.Sprintf("%d. [%s] %s", result.Index, result.Label, result.Status)
		if result.Error != "" {
			line += "：" + result.Error
		} else if result.Output != "" {
			line += "\n" + result.Output
		}
		lines = append(lines, line)
	}
	return truncateRunes(strings.Join(lines, "\n\n"), 6_000)
}

func (app *DesktopAgentApp) CancelSubagentJob(request CancelSubagentJobRequest) (*ChatSession, error) {
	sessionID, jobID := strings.TrimSpace(request.SessionID), strings.TrimSpace(request.JobID)
	job, ok := app.store.subagentBackgroundJob(sessionID, jobID)
	if !ok {
		return nil, errors.New("后台子代理不存在")
	}
	if !activeSubagentJobStatuses[job.Status] {
		return nil, errors.New("后台子代理已经结束")
	}
	app.mu.Lock()
	active, running := app.activeSubagentJobs[jobID]
	app.mu.Unlock()
	if !running || active.SessionID != sessionID {
		return nil, errors.New("后台子代理执行现场不存在；重新打开任务后会显示为中断")
	}
	session, err := app.store.updateSubagentBackgroundJob(sessionID, jobID, func(value SubagentBackgroundJob) SubagentBackgroundJob {
		value.Status = "cancelling"
		value.StatusMessage = "正在取消后台子代理。"
		return value
	})
	if err != nil {
		return nil, err
	}
	active.Cancel()
	app.emitSessionsChanged(session)
	return session, nil
}

func (app *DesktopAgentApp) cancelSessionSubagentJobs(sessionID string) {
	app.mu.Lock()
	cancels := []context.CancelFunc{}
	for _, active := range app.activeSubagentJobs {
		if active.SessionID == sessionID {
			cancels = append(cancels, active.Cancel)
		}
	}
	app.mu.Unlock()
	for _, cancel := range cancels {
		cancel()
	}
}

func (app *DesktopAgentApp) executeSubagentJobsTool(
	ctx context.Context,
	sessionID string,
	config desktopConfig,
	call modelToolCall,
	raw map[string]json.RawMessage,
) (string, error) {
	action := rawString(raw, "action", "list")
	switch action {
	case "list":
		session := app.store.getSession(sessionID)
		if session == nil {
			return "", errors.New("会话不存在")
		}
		jobs := cloneSubagentBackgroundJobs(session.BackgroundSubagentJobs)
		for index := range jobs {
			jobs[index].Results = nil
		}
		return marshalToolResult(map[string]any{"success": true, "jobs": jobs}), nil
	case "get":
		jobID := rawString(raw, "jobId", "")
		if jobID == "" {
			return "", errors.New("读取后台子代理必须提供 jobId")
		}
		job, ok := app.store.subagentBackgroundJob(sessionID, jobID)
		if !ok {
			return "", errors.New("后台子代理不存在")
		}
		return marshalToolResult(map[string]any{"success": true, "job": job}), nil
	case "cancel":
		jobID := rawString(raw, "jobId", "")
		if jobID == "" {
			return "", errors.New("取消后台子代理必须提供 jobId")
		}
		if err := app.authorizeTool(ctx, config, ApprovalRequest{
			ID: newID("approval"), SessionID: sessionID, ToolName: call.Function.Name,
			Summary: "取消后台子代理", Detail: jobID, Arguments: call.Function.Arguments, Risk: "high",
		}); err != nil {
			return "", err
		}
		_, err := app.CancelSubagentJob(CancelSubagentJobRequest{SessionID: sessionID, JobID: jobID})
		return marshalToolResult(map[string]any{"success": err == nil, "jobId": jobID, "status": "cancelling"}), err
	default:
		return "", errors.New("subagent_jobs action 仅支持 list、get 或 cancel")
	}
}
