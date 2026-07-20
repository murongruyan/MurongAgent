package main

import (
	"context"
	"errors"
	"net/http"
	"strings"
	"sync"
	"time"
)

type savedWorkflowManager struct {
	store      *savedWorkflowStore
	sessions   *desktopStore
	githubHTTP *http.Client

	mu         sync.Mutex
	ctx        context.Context
	cancel     context.CancelFunc
	schedules  map[string]context.CancelFunc
	running    map[string]bool
	runCancels map[string]context.CancelFunc
	viewer     string
	listener   func()
}

func newSavedWorkflowManager(store *savedWorkflowStore, sessions *desktopStore) *savedWorkflowManager {
	return &savedWorkflowManager{
		store:      store,
		sessions:   sessions,
		githubHTTP: newGitHubHTTPClient(),
		schedules:  map[string]context.CancelFunc{},
		running:    map[string]bool{},
		runCancels: map[string]context.CancelFunc{},
	}
}

func (manager *savedWorkflowManager) SetListener(listener func()) {
	manager.mu.Lock()
	manager.listener = listener
	manager.mu.Unlock()
}

func (manager *savedWorkflowManager) Start(parent context.Context) {
	manager.mu.Lock()
	if manager.cancel != nil {
		manager.cancel()
	}
	if parent == nil {
		parent = context.Background()
	}
	manager.ctx, manager.cancel = context.WithCancel(parent)
	manager.mu.Unlock()
	manager.rescheduleAll()
	manager.emitChanged()
}

func (manager *savedWorkflowManager) Close() {
	manager.mu.Lock()
	if manager.cancel != nil {
		manager.cancel()
		manager.cancel = nil
	}
	for id, cancel := range manager.schedules {
		cancel()
		delete(manager.schedules, id)
	}
	for id, cancel := range manager.runCancels {
		cancel()
		delete(manager.runCancels, id)
	}
	manager.mu.Unlock()
}

func (manager *savedWorkflowManager) State() SavedWorkflowState {
	manager.mu.Lock()
	viewer := manager.viewer
	manager.mu.Unlock()
	return manager.store.state(viewer)
}

func (manager *savedWorkflowManager) Busy() bool {
	manager.mu.Lock()
	defer manager.mu.Unlock()
	return len(manager.running) > 0
}

func (manager *savedWorkflowManager) SaveGitHubConfig(request SaveGitHubConfigRequest) (SavedWorkflowState, error) {
	if err := manager.store.saveGitHub(request); err != nil {
		return manager.State(), err
	}
	manager.mu.Lock()
	manager.viewer = ""
	manager.mu.Unlock()
	manager.emitChanged()
	return manager.State(), nil
}

func (manager *savedWorkflowManager) TestGitHubConnection() (SavedWorkflowState, error) {
	config, err := manager.store.runtimeGitHub()
	if err != nil {
		return manager.State(), err
	}
	ctx, cancel := context.WithTimeout(manager.baseContext(), 30*time.Second)
	defer cancel()
	viewer, err := testGitHubConnection(ctx, manager.githubHTTP, config)
	if err != nil {
		return manager.State(), err
	}
	manager.mu.Lock()
	manager.viewer = viewer
	manager.mu.Unlock()
	manager.emitChanged()
	return manager.State(), nil
}

func (manager *savedWorkflowManager) SaveWorkflow(request SaveSavedWorkflowRequest) (SavedWorkflowState, error) {
	workflow, err := manager.store.saveWorkflow(request)
	if err != nil {
		return manager.State(), err
	}
	if workflow.Enabled && !savedWorkflowBackgroundSafe(workflow) {
		_, _ = manager.store.updateRun(workflow.ID, SavedWorkflowRunRecord{
			Status:        workflowRunBlocked,
			FinishedAt:    time.Now().UnixMilli(),
			Summary:       "后台只允许固定的项目只读或 GitHub Actions 状态模板。",
			FailureReason: "该模板需要在前台逐次确认",
		})
	}
	manager.reschedule(workflow.ID)
	manager.emitChanged()
	return manager.State(), nil
}

func (manager *savedWorkflowManager) DeleteWorkflow(id string) (SavedWorkflowState, error) {
	id = strings.TrimSpace(id)
	manager.cancelSchedule(id)
	manager.cancelRun(id)
	if err := manager.store.deleteWorkflow(id); err != nil {
		return manager.State(), err
	}
	manager.emitChanged()
	return manager.State(), nil
}

func (manager *savedWorkflowManager) RunNow(request RunSavedWorkflowRequest) (SavedWorkflowState, error) {
	workflow, ok := manager.store.get(request.ID)
	if !ok {
		return manager.State(), errors.New("保存工作流不存在")
	}
	if validation := validateSavedWorkflow(workflow); len(validation) > 0 {
		return manager.State(), errors.New(strings.Join(validation, "；"))
	}
	backgroundSafe := savedWorkflowBackgroundSafe(workflow)
	if !backgroundSafe && !request.Confirmed {
		return manager.State(), errors.New("该工作流需要在前台确认本次执行")
	}
	if workflow.Template == workflowSessionSummaryExport && strings.TrimSpace(request.SessionID) == "" {
		return manager.State(), errors.New("请先选择一个聊天任务后再导出")
	}
	if err := manager.queue(workflow, strings.TrimSpace(request.SessionID), false); err != nil {
		return manager.State(), err
	}
	return manager.State(), nil
}

func (manager *savedWorkflowManager) rescheduleAll() {
	manager.mu.Lock()
	for id, cancel := range manager.schedules {
		cancel()
		delete(manager.schedules, id)
	}
	manager.mu.Unlock()
	for _, workflow := range manager.store.list() {
		manager.reschedule(workflow.ID)
	}
}

func (manager *savedWorkflowManager) reschedule(id string) {
	manager.cancelSchedule(id)
	workflow, ok := manager.store.get(id)
	if !ok || !workflow.Enabled || !savedWorkflowBackgroundSafe(workflow) {
		return
	}
	manager.mu.Lock()
	parent := manager.ctx
	if parent == nil {
		parent = context.Background()
	}
	ctx, cancel := context.WithCancel(parent)
	manager.schedules[id] = cancel
	manager.mu.Unlock()
	interval := time.Duration(workflow.IntervalMinutes) * time.Minute
	go func() {
		timer := time.NewTimer(interval)
		defer timer.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-timer.C:
				current, exists := manager.store.get(id)
				if exists && current.Enabled && savedWorkflowBackgroundSafe(current) {
					_ = manager.queue(current, "", true)
				}
				timer.Reset(interval)
			}
		}
	}()
}

func (manager *savedWorkflowManager) cancelSchedule(id string) {
	manager.mu.Lock()
	if cancel := manager.schedules[id]; cancel != nil {
		cancel()
		delete(manager.schedules, id)
	}
	manager.mu.Unlock()
}

func (manager *savedWorkflowManager) queue(workflow SavedWorkflowDefinition, sessionID string, scheduled bool) error {
	if scheduled && !savedWorkflowBackgroundSafe(workflow) {
		return errors.New("后台执行被安全策略阻止")
	}
	parent := manager.baseContext()
	runContext, runCancel := context.WithCancel(parent)
	manager.mu.Lock()
	if manager.running[workflow.ID] {
		manager.mu.Unlock()
		runCancel()
		return errors.New("该工作流已经在运行")
	}
	manager.running[workflow.ID] = true
	manager.runCancels[workflow.ID] = runCancel
	manager.mu.Unlock()
	queued := SavedWorkflowRunRecord{Status: workflowRunQueued, Summary: "已加入工作流执行队列。"}
	if _, err := manager.store.updateRun(workflow.ID, queued); err != nil {
		manager.finishRunning(workflow.ID)
		return err
	}
	manager.emitChanged()
	go manager.execute(runContext, workflow.ID, sessionID)
	return nil
}

func (manager *savedWorkflowManager) execute(runContext context.Context, id, sessionID string) {
	defer manager.finishRunning(id)
	workflow, ok := manager.store.get(id)
	if !ok {
		return
	}
	startedAt := time.Now().UnixMilli()
	_, _ = manager.store.updateRun(id, SavedWorkflowRunRecord{Status: workflowRunRunning, StartedAt: startedAt, Summary: "正在执行。"})
	manager.emitChanged()
	timeout := workflowExecutionTimeout(workflow)
	ctx, cancel := context.WithTimeout(runContext, timeout)
	defer cancel()
	dependencies := workflowExecutionDependencies{sessions: manager.sessions, githubHTTP: manager.githubHTTP}
	if workflow.Template == workflowGitHubActionsStatus {
		config, err := manager.store.runtimeGitHub()
		if err != nil {
			manager.recordFailure(workflow, startedAt, err)
			return
		}
		dependencies.github = config
	}
	summary, err := executeSavedWorkflow(ctx, workflow, sessionID, dependencies)
	if err != nil {
		if errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) {
			_, _ = manager.store.updateRun(id, SavedWorkflowRunRecord{
				Status: workflowRunCancelled, StartedAt: startedAt, FinishedAt: time.Now().UnixMilli(),
				Summary: "工作流已取消或超时。", FailureReason: truncateRunes(err.Error(), 500),
			})
			manager.emitChanged()
			return
		}
		manager.recordFailure(workflow, startedAt, err)
		return
	}
	_, _ = manager.store.updateRun(id, SavedWorkflowRunRecord{
		Status: workflowRunSucceeded, StartedAt: startedAt, FinishedAt: time.Now().UnixMilli(), Summary: summary,
	})
	manager.emitChanged()
}

func (manager *savedWorkflowManager) recordFailure(workflow SavedWorkflowDefinition, startedAt int64, err error) {
	reason := truncateRunes(err.Error(), 1_000)
	if workflow.ProjectPath != "" {
		reason = strings.ReplaceAll(reason, workflow.ProjectPath, "[项目目录]")
	}
	_, _ = manager.store.updateRun(workflow.ID, SavedWorkflowRunRecord{
		Status: workflowRunFailed, StartedAt: startedAt, FinishedAt: time.Now().UnixMilli(),
		Summary: "工作流执行失败。", FailureReason: reason,
	})
	manager.emitChanged()
}

func (manager *savedWorkflowManager) finishRunning(id string) {
	manager.mu.Lock()
	if cancel := manager.runCancels[id]; cancel != nil {
		cancel()
		delete(manager.runCancels, id)
	}
	delete(manager.running, id)
	manager.mu.Unlock()
}

func (manager *savedWorkflowManager) cancelRun(id string) {
	manager.mu.Lock()
	if cancel := manager.runCancels[id]; cancel != nil {
		cancel()
	}
	manager.mu.Unlock()
}

func (manager *savedWorkflowManager) baseContext() context.Context {
	manager.mu.Lock()
	defer manager.mu.Unlock()
	if manager.ctx != nil {
		return manager.ctx
	}
	return context.Background()
}

func (manager *savedWorkflowManager) emitChanged() {
	manager.mu.Lock()
	listener := manager.listener
	manager.mu.Unlock()
	if listener != nil {
		listener()
	}
}

func workflowExecutionTimeout(workflow SavedWorkflowDefinition) time.Duration {
	seconds := 60
	for _, node := range workflow.Nodes {
		if node.TimeoutSeconds > seconds {
			seconds = node.TimeoutSeconds
		}
	}
	if seconds > 15*60 {
		seconds = 15 * 60
	}
	return time.Duration(seconds) * time.Second
}
