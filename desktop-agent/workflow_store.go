package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"sync"
	"time"
)

const savedWorkflowSchemaVersion = 1

var githubRepositoryPattern = regexp.MustCompile(`^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$`)

type savedWorkflowStore struct {
	mu       sync.Mutex
	path     string
	document savedWorkflowDocument
}

func newSavedWorkflowStore(path string) (*savedWorkflowStore, error) {
	store := &savedWorkflowStore{
		path: path,
		document: savedWorkflowDocument{
			SchemaVersion: savedWorkflowSchemaVersion,
			GitHub:        savedGitHubConfig{APIBaseURL: "https://api.github.com"},
			Workflows:     []SavedWorkflowDefinition{},
		},
	}
	if err := store.load(); err != nil {
		return nil, err
	}
	return store, nil
}

func (store *savedWorkflowStore) load() error {
	store.mu.Lock()
	defer store.mu.Unlock()
	data, err := os.ReadFile(store.path)
	if errors.Is(err, os.ErrNotExist) {
		return nil
	}
	if err != nil {
		return err
	}
	var document savedWorkflowDocument
	if err := json.Unmarshal(data, &document); err != nil {
		return fmt.Errorf("保存工作流配置损坏：%w", err)
	}
	document.SchemaVersion = savedWorkflowSchemaVersion
	document.GitHub.APIBaseURL = normalizeGitHubAPIBaseURL(document.GitHub.APIBaseURL)
	if document.Workflows == nil {
		document.Workflows = []SavedWorkflowDefinition{}
	}
	seen := make(map[string]bool, len(document.Workflows))
	reconciled := false
	for index := range document.Workflows {
		workflow := &document.Workflows[index]
		workflow.ID = strings.TrimSpace(workflow.ID)
		if workflow.ID == "" || seen[workflow.ID] {
			return fmt.Errorf("保存工作流包含空白或重复 ID")
		}
		seen[workflow.ID] = true
		workflow.Name = truncateRunes(workflow.Name, 100)
		workflow.ProjectPath = strings.TrimSpace(workflow.ProjectPath)
		workflow.GitHubRepository = strings.TrimSpace(workflow.GitHubRepository)
		if workflow.LastRun != nil && (workflow.LastRun.Status == workflowRunRunning || workflow.LastRun.Status == workflowRunQueued) {
			now := time.Now().UnixMilli()
			workflow.LastRun.Status = workflowRunCancelled
			workflow.LastRun.FinishedAt = now
			workflow.LastRun.Summary = "应用或系统中断了本次工作流。"
			workflow.LastRun.FailureReason = "运行未完成，已在恢复时标记为取消"
			workflow.UpdatedAt = now
			reconciled = true
		}
	}
	store.document = document
	if reconciled {
		return store.writeLocked()
	}
	return nil
}

func (store *savedWorkflowStore) state(viewer string) SavedWorkflowState {
	store.mu.Lock()
	defer store.mu.Unlock()
	workflows := make([]SavedWorkflowDefinition, 0, len(store.document.Workflows))
	for _, workflow := range store.document.Workflows {
		workflows = append(workflows, cloneSavedWorkflow(workflow))
	}
	sort.Slice(workflows, func(i, j int) bool { return workflows[i].UpdatedAt > workflows[j].UpdatedAt })
	return SavedWorkflowState{
		GitHub: PublicGitHubConfig{
			APIBaseURL: normalizeGitHubAPIBaseURL(store.document.GitHub.APIBaseURL),
			HasToken:   store.document.GitHub.ProtectedToken != "",
			Viewer:     strings.TrimSpace(viewer),
		},
		Workflows: workflows,
	}
}

func (store *savedWorkflowStore) get(id string) (SavedWorkflowDefinition, bool) {
	store.mu.Lock()
	defer store.mu.Unlock()
	for _, workflow := range store.document.Workflows {
		if workflow.ID == strings.TrimSpace(id) {
			return cloneSavedWorkflow(workflow), true
		}
	}
	return SavedWorkflowDefinition{}, false
}

func (store *savedWorkflowStore) list() []SavedWorkflowDefinition {
	return store.state("").Workflows
}

func (store *savedWorkflowStore) saveWorkflow(request SaveSavedWorkflowRequest) (SavedWorkflowDefinition, error) {
	store.mu.Lock()
	defer store.mu.Unlock()

	now := time.Now().UnixMilli()
	id := strings.TrimSpace(request.ID)
	existingIndex := -1
	for index, existing := range store.document.Workflows {
		if existing.ID == id && id != "" {
			existingIndex = index
			break
		}
	}
	if id == "" {
		if len(store.document.Workflows) >= maximumSavedWorkflows {
			return SavedWorkflowDefinition{}, fmt.Errorf("最多保存 %d 个工作流", maximumSavedWorkflows)
		}
		id = newID("workflow")
	}
	createdAt := now
	var lastRun *SavedWorkflowRunRecord
	if existingIndex >= 0 {
		createdAt = store.document.Workflows[existingIndex].CreatedAt
		if createdAt == 0 {
			createdAt = now
		}
		if store.document.Workflows[existingIndex].Template == strings.TrimSpace(request.Template) && store.document.Workflows[existingIndex].LastRun != nil {
			record := *store.document.Workflows[existingIndex].LastRun
			lastRun = &record
		}
	}
	workflow := SavedWorkflowDefinition{
		ID:               id,
		Name:             truncateRunes(request.Name, 100),
		Template:         strings.TrimSpace(request.Template),
		ProjectPath:      strings.TrimSpace(request.ProjectPath),
		GitHubRepository: strings.TrimSpace(request.GitHubRepository),
		IntervalMinutes:  request.IntervalMinutes,
		Enabled:          request.Enabled,
		CreatedAt:        createdAt,
		UpdatedAt:        now,
		LastRun:          lastRun,
	}
	workflow.Nodes = defaultSavedWorkflowNodes(workflow.Template)
	if workflow.Template == workflowProjectReadDiagnostic || workflow.Template == workflowDirectoryChangeSummary {
		path, err := normalizeExistingProjectPath(workflow.ProjectPath)
		if err != nil {
			return SavedWorkflowDefinition{}, errors.New("工作流项目目录不存在或不可访问")
		}
		workflow.ProjectPath = path
	} else {
		workflow.ProjectPath = ""
	}
	if workflow.Template != workflowGitHubActionsStatus {
		workflow.GitHubRepository = ""
	}
	if validation := validateSavedWorkflow(workflow); len(validation) > 0 {
		return SavedWorkflowDefinition{}, errors.New(strings.Join(validation, "；"))
	}
	if existingIndex >= 0 {
		store.document.Workflows[existingIndex] = workflow
	} else {
		store.document.Workflows = append(store.document.Workflows, workflow)
	}
	if err := store.writeLocked(); err != nil {
		return SavedWorkflowDefinition{}, err
	}
	return cloneSavedWorkflow(workflow), nil
}

func (store *savedWorkflowStore) deleteWorkflow(id string) error {
	store.mu.Lock()
	defer store.mu.Unlock()
	id = strings.TrimSpace(id)
	found := false
	updated := make([]SavedWorkflowDefinition, 0, len(store.document.Workflows))
	for _, workflow := range store.document.Workflows {
		if workflow.ID == id {
			found = true
			continue
		}
		updated = append(updated, workflow)
	}
	if !found {
		return errors.New("保存工作流不存在")
	}
	store.document.Workflows = updated
	return store.writeLocked()
}

func (store *savedWorkflowStore) updateRun(id string, record SavedWorkflowRunRecord) (SavedWorkflowDefinition, error) {
	store.mu.Lock()
	defer store.mu.Unlock()
	for index := range store.document.Workflows {
		if store.document.Workflows[index].ID != strings.TrimSpace(id) {
			continue
		}
		record.Summary = truncateRunes(record.Summary, 2_000)
		record.FailureReason = truncateRunes(record.FailureReason, 1_000)
		store.document.Workflows[index].LastRun = &record
		store.document.Workflows[index].UpdatedAt = time.Now().UnixMilli()
		if err := store.writeLocked(); err != nil {
			return SavedWorkflowDefinition{}, err
		}
		return cloneSavedWorkflow(store.document.Workflows[index]), nil
	}
	return SavedWorkflowDefinition{}, errors.New("保存工作流不存在")
}

func (store *savedWorkflowStore) saveGitHub(request SaveGitHubConfigRequest) error {
	store.mu.Lock()
	defer store.mu.Unlock()
	baseURL := normalizeGitHubAPIBaseURL(request.APIBaseURL)
	if err := validateGitHubAPIBaseURL(baseURL); err != nil {
		return err
	}
	updated := store.document.GitHub
	updated.APIBaseURL = baseURL
	if request.ClearToken {
		updated.ProtectedToken = ""
	} else if token := strings.TrimSpace(request.Token); token != "" {
		protected, err := protectSecret([]byte(token))
		if err != nil {
			return err
		}
		updated.ProtectedToken = protected
	}
	store.document.GitHub = updated
	return store.writeLocked()
}

func (store *savedWorkflowStore) runtimeGitHub() (runtimeGitHubConfig, error) {
	store.mu.Lock()
	defer store.mu.Unlock()
	config := runtimeGitHubConfig{APIBaseURL: normalizeGitHubAPIBaseURL(store.document.GitHub.APIBaseURL)}
	if store.document.GitHub.ProtectedToken == "" {
		return config, nil
	}
	plain, err := unprotectSecret(store.document.GitHub.ProtectedToken)
	if err != nil {
		return runtimeGitHubConfig{}, fmt.Errorf("无法解密 GitHub Token：%w", err)
	}
	config.Token = string(plain)
	return config, nil
}

func (store *savedWorkflowStore) writeLocked() error {
	store.document.SchemaVersion = savedWorkflowSchemaVersion
	if err := os.MkdirAll(filepath.Dir(store.path), 0o700); err != nil {
		return err
	}
	return writeJSONAtomic(store.path, store.document)
}

func normalizeGitHubAPIBaseURL(value string) string {
	value = strings.TrimRight(strings.TrimSpace(value), "/")
	if value == "" {
		return "https://api.github.com"
	}
	return value
}

func validateGitHubAPIBaseURL(value string) error {
	parsed, err := url.Parse(value)
	if err != nil || parsed.Scheme != "https" || parsed.Host == "" {
		return errors.New("GitHub API 地址必须是完整的 HTTPS URL")
	}
	if parsed.User != nil || parsed.RawQuery != "" || parsed.Fragment != "" {
		return errors.New("GitHub API 地址不能包含凭据、查询参数或片段")
	}
	return nil
}

func validateSavedWorkflow(workflow SavedWorkflowDefinition) []string {
	errorsFound := make([]string, 0)
	if strings.TrimSpace(workflow.ID) == "" {
		errorsFound = append(errorsFound, "工作流缺少 ID")
	}
	if strings.TrimSpace(workflow.Name) == "" {
		errorsFound = append(errorsFound, "工作流名称不能为空")
	}
	switch workflow.Template {
	case workflowProjectReadDiagnostic, workflowDirectoryChangeSummary:
		if strings.TrimSpace(workflow.ProjectPath) == "" {
			errorsFound = append(errorsFound, "只读目录模板必须选择项目范围")
		}
	case workflowGitHubActionsStatus:
		if !githubRepositoryPattern.MatchString(strings.TrimSpace(workflow.GitHubRepository)) {
			errorsFound = append(errorsFound, "GitHub 仓库格式应为 owner/repository")
		}
	case workflowSessionSummaryExport:
	default:
		errorsFound = append(errorsFound, "未知工作流模板")
	}
	if workflow.IntervalMinutes < minimumWorkflowIntervalMinutes || workflow.IntervalMinutes > maximumWorkflowIntervalMinutes {
		errorsFound = append(errorsFound, fmt.Sprintf("调度周期必须在 %d 到 %d 分钟之间", minimumWorkflowIntervalMinutes, maximumWorkflowIntervalMinutes))
	}
	if len(workflow.Nodes) == 0 {
		errorsFound = append(errorsFound, "工作流至少需要一个步骤")
		return errorsFound
	}
	ids := make(map[string]bool, len(workflow.Nodes))
	for _, node := range workflow.Nodes {
		id := strings.TrimSpace(node.ID)
		if id == "" || ids[id] {
			errorsFound = append(errorsFound, "工作流步骤 ID 不能为空或重复")
			continue
		}
		ids[id] = true
		if node.TimeoutSeconds < 1 || node.TimeoutSeconds > 15*60 {
			errorsFound = append(errorsFound, "工作流步骤超时必须在 1 到 900 秒之间")
		}
		if node.MaxRetries < 0 || node.MaxRetries > 3 {
			errorsFound = append(errorsFound, "工作流步骤重试次数必须在 0 到 3 之间")
		}
	}
	dependencies := make(map[string]map[string]bool, len(workflow.Nodes))
	for _, node := range workflow.Nodes {
		pending := make(map[string]bool, len(node.DependsOn))
		for _, dependency := range node.DependsOn {
			if !ids[dependency] {
				errorsFound = append(errorsFound, fmt.Sprintf("步骤 %s 依赖不存在的步骤 %s", node.ID, dependency))
			} else {
				pending[dependency] = true
			}
		}
		dependencies[node.ID] = pending
	}
	if len(errorsFound) > 0 {
		return errorsFound
	}
	for len(dependencies) > 0 {
		ready := make([]string, 0)
		for id, pending := range dependencies {
			if len(pending) == 0 {
				ready = append(ready, id)
			}
		}
		if len(ready) == 0 {
			return append(errorsFound, "工作流存在循环依赖")
		}
		for _, id := range ready {
			delete(dependencies, id)
			for _, pending := range dependencies {
				delete(pending, id)
			}
		}
	}
	return errorsFound
}

func savedWorkflowBackgroundSafe(workflow SavedWorkflowDefinition) bool {
	if len(validateSavedWorkflow(workflow)) > 0 {
		return false
	}
	expected := ""
	switch workflow.Template {
	case workflowProjectReadDiagnostic, workflowDirectoryChangeSummary:
		expected = workflowPermissionProjectRead
	case workflowGitHubActionsStatus:
		expected = workflowPermissionNetworkRead
	default:
		return false
	}
	for _, node := range workflow.Nodes {
		if node.RequiredPermission != expected {
			return false
		}
	}
	return true
}
