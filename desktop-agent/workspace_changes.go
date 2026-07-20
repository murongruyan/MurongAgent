package main

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	goruntime "runtime"
	"sort"
	"strings"
	"sync"
	"time"
	"unicode/utf8"
)

const (
	workspaceChangeAdded = iota + 1
	workspaceChangeRemoved
	workspaceChangeModified
	workspaceChangeRenamedFrom
	workspaceChangeRenamedTo

	maxPendingWorkspaceChanges = 200
	maxRecentWorkspaceChanges  = 300
	maxWorkspaceDiffBytes      = 256 * 1024
)

type WorkspaceFileChange struct {
	Path      string `json:"path"`
	Kind      string `json:"kind"`
	ChangedAt int64  `json:"changedAt"`
	Directory bool   `json:"directory,omitempty"`
	Size      int64  `json:"size,omitempty"`
}

type WorkspaceChangeSnapshot struct {
	ProjectPath  string                `json:"projectPath,omitempty"`
	Changes      []WorkspaceFileChange `json:"changes,omitempty"`
	OmittedCount int                   `json:"omittedCount,omitempty"`
	CapturedAt   int64                 `json:"capturedAt,omitempty"`
}

type WorkspaceChangeState struct {
	ProjectPath    string                `json:"projectPath,omitempty"`
	Watching       bool                  `json:"watching"`
	PendingChanges []WorkspaceFileChange `json:"pendingChanges"`
	RecentChanges  []WorkspaceFileChange `json:"recentChanges"`
	OmittedCount   int                   `json:"omittedCount"`
	Error          string                `json:"error,omitempty"`
}

type WorkspaceDiff struct {
	Path      string `json:"path"`
	Diff      string `json:"diff"`
	Git       bool   `json:"git"`
	Available bool   `json:"available"`
	Truncated bool   `json:"truncated"`
	Message   string `json:"message,omitempty"`
}

type workspaceChangeTracker struct {
	mu            sync.Mutex
	projectPath   string
	watching      bool
	watchError    string
	pending       map[string]WorkspaceFileChange
	recent        []WorkspaceFileChange
	omitted       int
	ignored       map[string]time.Time
	stop          func()
	generation    uint64
	listener      func(WorkspaceChangeState)
	eventListener func(string, WorkspaceFileChange)
	notifyTimer   *time.Timer
}

func newWorkspaceChangeTracker() *workspaceChangeTracker {
	return &workspaceChangeTracker{
		pending: map[string]WorkspaceFileChange{},
		ignored: map[string]time.Time{},
	}
}

func (tracker *workspaceChangeTracker) SetListener(listener func(WorkspaceChangeState)) {
	tracker.mu.Lock()
	tracker.listener = listener
	state := tracker.stateLocked()
	tracker.mu.Unlock()
	if listener != nil {
		listener(state)
	}
}

func (tracker *workspaceChangeTracker) SetEventListener(listener func(string, WorkspaceFileChange)) {
	tracker.mu.Lock()
	tracker.eventListener = listener
	tracker.mu.Unlock()
}

func (tracker *workspaceChangeTracker) SetProject(projectPath string) {
	projectPath = strings.TrimSpace(projectPath)
	if projectPath != "" {
		if normalized, err := normalizeExistingProjectPath(projectPath); err == nil {
			projectPath = normalized
		} else {
			projectPath = filepath.Clean(projectPath)
		}
	}

	tracker.mu.Lock()
	oldStop := tracker.stop
	tracker.stop = nil
	tracker.generation++
	generation := tracker.generation
	tracker.projectPath = projectPath
	tracker.watching = false
	tracker.watchError = ""
	tracker.pending = map[string]WorkspaceFileChange{}
	tracker.recent = nil
	tracker.omitted = 0
	tracker.ignored = map[string]time.Time{}
	tracker.mu.Unlock()
	if oldStop != nil {
		oldStop()
	}
	if projectPath == "" {
		tracker.notifyNow()
		return
	}

	stop, err := startNativeWorkspaceWatcher(projectPath, func(relative string, action int) {
		tracker.recordNativeChange(generation, relative, action)
	})
	tracker.mu.Lock()
	if tracker.generation != generation || tracker.projectPath != projectPath {
		tracker.mu.Unlock()
		if stop != nil {
			stop()
		}
		return
	}
	if err != nil {
		tracker.watchError = err.Error()
	} else {
		tracker.stop = stop
		tracker.watching = true
	}
	tracker.mu.Unlock()
	tracker.notifyNow()
}

func (tracker *workspaceChangeTracker) Close() {
	tracker.mu.Lock()
	stop := tracker.stop
	tracker.stop = nil
	tracker.watching = false
	tracker.generation++
	if tracker.notifyTimer != nil {
		tracker.notifyTimer.Stop()
		tracker.notifyTimer = nil
	}
	tracker.mu.Unlock()
	if stop != nil {
		stop()
	}
}

func (tracker *workspaceChangeTracker) State() WorkspaceChangeState {
	tracker.mu.Lock()
	defer tracker.mu.Unlock()
	return tracker.stateLocked()
}

func (tracker *workspaceChangeTracker) stateLocked() WorkspaceChangeState {
	pending := make([]WorkspaceFileChange, 0, len(tracker.pending))
	for _, change := range tracker.pending {
		pending = append(pending, change)
	}
	sortWorkspaceChanges(pending)
	return WorkspaceChangeState{
		ProjectPath: tracker.projectPath, Watching: tracker.watching,
		PendingChanges: pending, RecentChanges: cloneWorkspaceChanges(tracker.recent),
		OmittedCount: tracker.omitted, Error: tracker.watchError,
	}
}

func (tracker *workspaceChangeTracker) Consume(projectPath string) WorkspaceChangeSnapshot {
	tracker.mu.Lock()
	if !sameWorkspacePath(projectPath, tracker.projectPath) {
		tracker.mu.Unlock()
		return WorkspaceChangeSnapshot{}
	}
	changes := make([]WorkspaceFileChange, 0, len(tracker.pending))
	for _, change := range tracker.pending {
		changes = append(changes, change)
	}
	sortWorkspaceChanges(changes)
	snapshot := WorkspaceChangeSnapshot{
		ProjectPath: tracker.projectPath, Changes: changes, OmittedCount: tracker.omitted,
		CapturedAt: time.Now().UnixMilli(),
	}
	tracker.pending = map[string]WorkspaceFileChange{}
	tracker.omitted = 0
	tracker.mu.Unlock()
	tracker.notifyNow()
	return snapshot
}

func (tracker *workspaceChangeTracker) Restore(snapshot WorkspaceChangeSnapshot) {
	if len(snapshot.Changes) == 0 && snapshot.OmittedCount == 0 {
		return
	}
	tracker.mu.Lock()
	if sameWorkspacePath(snapshot.ProjectPath, tracker.projectPath) {
		for _, change := range snapshot.Changes {
			tracker.mergePendingLocked(change)
		}
		tracker.omitted += snapshot.OmittedCount
		tracker.scheduleNotifyLocked()
	}
	tracker.mu.Unlock()
}

func (tracker *workspaceChangeTracker) Clear() WorkspaceChangeState {
	tracker.mu.Lock()
	tracker.pending = map[string]WorkspaceFileChange{}
	tracker.recent = nil
	tracker.omitted = 0
	state := tracker.stateLocked()
	tracker.mu.Unlock()
	tracker.notifyNow()
	return state
}

func (tracker *workspaceChangeTracker) IgnoreAgentPaths(projectPath string, paths ...string) {
	tracker.mu.Lock()
	defer tracker.mu.Unlock()
	if !sameWorkspacePath(projectPath, tracker.projectPath) {
		return
	}
	expires := time.Now().Add(2 * time.Second)
	for _, value := range paths {
		if relative, err := normalizeRelativePath(value, false); err == nil {
			tracker.ignored[strings.ToLower(relative)] = expires
		}
	}
}

func (tracker *workspaceChangeTracker) recordNativeChange(generation uint64, value string, action int) {
	relative := strings.TrimSpace(filepath.ToSlash(value))
	relative = strings.TrimPrefix(relative, "./")
	if relative == "" || workspaceChangePathIgnored(relative) {
		return
	}
	kind := map[int]string{
		workspaceChangeAdded: "created", workspaceChangeRemoved: "deleted", workspaceChangeModified: "modified",
		workspaceChangeRenamedFrom: "renamed_from", workspaceChangeRenamedTo: "renamed_to",
	}[action]
	if kind == "" {
		return
	}

	tracker.mu.Lock()
	if tracker.generation != generation || tracker.projectPath == "" || tracker.changeIgnoredLocked(relative) {
		tracker.mu.Unlock()
		return
	}
	root := tracker.projectPath
	tracker.mu.Unlock()

	change := WorkspaceFileChange{Path: relative, Kind: kind, ChangedAt: time.Now().UnixMilli()}
	if info, err := os.Stat(filepath.Join(root, filepath.FromSlash(relative))); err == nil {
		change.Directory = info.IsDir()
		if !info.IsDir() {
			change.Size = info.Size()
		}
	}

	tracker.mu.Lock()
	if tracker.generation != generation || tracker.changeIgnoredLocked(relative) {
		tracker.mu.Unlock()
		return
	}
	tracker.mergePendingLocked(change)
	tracker.mergeRecentLocked(change)
	tracker.scheduleNotifyLocked()
	listener, projectPath := tracker.eventListener, tracker.projectPath
	tracker.mu.Unlock()
	if listener != nil {
		listener(projectPath, change)
	}
}

func (tracker *workspaceChangeTracker) mergePendingLocked(change WorkspaceFileChange) {
	key := strings.ToLower(change.Path)
	if existing, ok := tracker.pending[key]; ok {
		change.Kind = coalesceWorkspaceChangeKind(existing.Kind, change.Kind)
		if change.Kind == "" {
			delete(tracker.pending, key)
			return
		}
		if existing.ChangedAt < change.ChangedAt {
			change.ChangedAt = existing.ChangedAt
		}
		tracker.pending[key] = change
		return
	}
	if len(tracker.pending) >= maxPendingWorkspaceChanges {
		tracker.omitted++
		return
	}
	tracker.pending[key] = change
}

func (tracker *workspaceChangeTracker) mergeRecentLocked(change WorkspaceFileChange) {
	if count := len(tracker.recent); count > 0 {
		last := tracker.recent[count-1]
		if strings.EqualFold(last.Path, change.Path) && change.ChangedAt-last.ChangedAt < 1_000 {
			change.Kind = coalesceWorkspaceChangeKind(last.Kind, change.Kind)
			if change.Kind == "" {
				tracker.recent = tracker.recent[:count-1]
			} else {
				tracker.recent[count-1] = change
			}
			return
		}
	}
	tracker.recent = append(tracker.recent, change)
	if len(tracker.recent) > maxRecentWorkspaceChanges {
		tracker.recent = append([]WorkspaceFileChange(nil), tracker.recent[len(tracker.recent)-maxRecentWorkspaceChanges:]...)
	}
}

func (tracker *workspaceChangeTracker) changeIgnoredLocked(relative string) bool {
	now := time.Now()
	key := strings.ToLower(relative)
	for path, expires := range tracker.ignored {
		if now.After(expires) {
			delete(tracker.ignored, path)
			continue
		}
		if key == path || strings.HasPrefix(key, path+"/") {
			return true
		}
	}
	return false
}

func (tracker *workspaceChangeTracker) scheduleNotifyLocked() {
	if tracker.notifyTimer != nil {
		tracker.notifyTimer.Stop()
	}
	tracker.notifyTimer = time.AfterFunc(180*time.Millisecond, tracker.notifyNow)
}

func (tracker *workspaceChangeTracker) notifyNow() {
	tracker.mu.Lock()
	listener := tracker.listener
	state := tracker.stateLocked()
	tracker.mu.Unlock()
	if listener != nil {
		listener(state)
	}
}

func sameWorkspacePath(first, second string) bool {
	first, second = strings.TrimSpace(first), strings.TrimSpace(second)
	if first == "" || second == "" {
		return first == second
	}
	first, second = filepath.Clean(first), filepath.Clean(second)
	if goruntime.GOOS == "windows" {
		return strings.EqualFold(first, second)
	}
	return first == second
}

func workspaceChangePathIgnored(relative string) bool {
	for _, part := range strings.Split(strings.ToLower(filepath.ToSlash(relative)), "/") {
		if generatedDirectoryNames[part] || part == ".idea" || part == ".cache" || part == ".vscode" || strings.HasPrefix(part, ".murong-agent-") {
			return true
		}
	}
	return false
}

func coalesceWorkspaceChangeKind(previous, next string) string {
	if previous == "created" && (next == "modified" || next == "renamed_to") {
		return "created"
	}
	if previous == "created" && (next == "deleted" || next == "renamed_from") {
		return ""
	}
	if (previous == "deleted" || previous == "renamed_from") && (next == "created" || next == "renamed_to") {
		return "modified"
	}
	if next == "renamed_from" {
		return "deleted"
	}
	if next == "renamed_to" {
		return "created"
	}
	return next
}

func sortWorkspaceChanges(changes []WorkspaceFileChange) {
	sort.SliceStable(changes, func(i, j int) bool {
		if changes[i].ChangedAt != changes[j].ChangedAt {
			return changes[i].ChangedAt < changes[j].ChangedAt
		}
		return strings.ToLower(changes[i].Path) < strings.ToLower(changes[j].Path)
	})
}

func cloneWorkspaceChanges(values []WorkspaceFileChange) []WorkspaceFileChange {
	return append([]WorkspaceFileChange(nil), values...)
}

func validateMessageWorkspaceChanges(message ChatMessage) error {
	if len(message.WorkspaceChanges) > maxPendingWorkspaceChanges || message.WorkspaceChangesOmitted < 0 || message.WorkspaceChangesOmitted > 1_000_000 {
		return errors.New("项目变化数量超过上限")
	}
	seen := map[string]bool{}
	validKinds := map[string]bool{"created": true, "modified": true, "deleted": true, "renamed_from": true, "renamed_to": true}
	for _, change := range message.WorkspaceChanges {
		relative, err := normalizeRelativePath(change.Path, false)
		if err != nil || relative != change.Path || !validKinds[change.Kind] || change.ChangedAt < 0 || change.Size < 0 {
			return errors.New("项目变化包含无效路径、类型、时间或大小")
		}
		key := strings.ToLower(relative)
		if seen[key] {
			return errors.New("项目变化包含重复路径")
		}
		seen[key] = true
	}
	return nil
}

func workspaceChangeSummary(changes []WorkspaceFileChange, omitted int) string {
	if len(changes) == 0 && omitted == 0 {
		return ""
	}
	lines := []string{"[Murong：自上一轮后检测到的项目外部变化]", "这些变化可能来自终端、Git、格式化器或其他编辑器。先把磁盘现状作为准确信息，必要时重新读取文件，不要覆盖外部修改。"}
	for _, change := range changes {
		lines = append(lines, fmt.Sprintf("- %s：%s", workspaceChangeKindLabel(change.Kind), change.Path))
	}
	if omitted > 0 {
		lines = append(lines, fmt.Sprintf("- 另有 %d 个变化因数量上限未展开。", omitted))
	}
	return strings.Join(lines, "\n")
}

func workspaceChangeKindLabel(kind string) string {
	return map[string]string{
		"created": "新增", "modified": "修改", "deleted": "删除",
		"renamed_from": "重命名前路径", "renamed_to": "重命名后路径",
	}[kind]
}

func (app *DesktopAgentApp) GetWorkspaceChanges() WorkspaceChangeState {
	if app.workspace == nil {
		return WorkspaceChangeState{PendingChanges: []WorkspaceFileChange{}, RecentChanges: []WorkspaceFileChange{}}
	}
	return app.workspace.State()
}

func (app *DesktopAgentApp) ClearWorkspaceChanges() WorkspaceChangeState {
	if app.workspace == nil {
		return WorkspaceChangeState{PendingChanges: []WorkspaceFileChange{}, RecentChanges: []WorkspaceFileChange{}}
	}
	return app.workspace.Clear()
}

func (app *DesktopAgentApp) GetWorkspaceDiff(path string) (WorkspaceDiff, error) {
	return workspaceDiffForProject(app.store.rawConfig().ProjectPath, path)
}

func workspaceDiffForProject(projectPath, value string) (WorkspaceDiff, error) {
	root, err := normalizeExistingProjectPath(projectPath)
	if err != nil {
		return WorkspaceDiff{}, err
	}
	relative, err := normalizeRelativePath(value, false)
	if err != nil {
		return WorkspaceDiff{}, err
	}
	result := WorkspaceDiff{Path: relative}
	gitPath, lookErr := exec.LookPath("git.exe")
	if lookErr != nil {
		gitPath, lookErr = exec.LookPath("git")
	}
	if lookErr == nil && commandSucceeds(root, gitPath, "rev-parse", "--is-inside-work-tree") {
		result.Git = true
		diff, headAvailable := runWorkspaceGit(root, gitPath, "diff", "--no-ext-diff", "--no-color", "--unified=3", "HEAD", "--", relative)
		if !headAvailable {
			cached, _ := runWorkspaceGit(root, gitPath, "diff", "--cached", "--no-ext-diff", "--no-color", "--unified=3", "--", relative)
			working, _ := runWorkspaceGit(root, gitPath, "diff", "--no-ext-diff", "--no-color", "--unified=3", "--", relative)
			diff = strings.TrimSpace(cached + "\n" + working)
		}
		if strings.TrimSpace(diff) != "" {
			result.Diff, result.Truncated = truncateWorkspaceDiff(diff)
			result.Available = true
			return result, nil
		}
		status, _ := runWorkspaceGit(root, gitPath, "status", "--porcelain=v1", "--untracked-files=all", "--", relative)
		if strings.HasPrefix(strings.TrimSpace(status), "??") {
			preview, previewErr := addedFileDiff(root, relative)
			if previewErr == nil {
				result.Diff, result.Truncated = truncateWorkspaceDiff(preview)
				result.Available = true
				return result, nil
			}
		}
		result.Message = "Git 当前没有该路径的未提交文本差异；文件可能已恢复、被忽略或只发生了元数据变化。"
		return result, nil
	}

	preview, previewErr := currentFilePreview(root, relative)
	if previewErr == nil {
		result.Diff, result.Truncated = truncateWorkspaceDiff(preview)
		result.Available = true
		result.Message = "当前项目不是 Git 工作树，无法重建修改前内容；以下显示磁盘上的当前文本。"
		return result, nil
	}
	if errors.Is(previewErr, os.ErrNotExist) {
		result.Message = "当前项目不是 Git 工作树，且路径已经不存在，无法重建删除前内容。"
		return result, nil
	}
	return WorkspaceDiff{}, previewErr
}

func commandSucceeds(directory, executable string, arguments ...string) bool {
	ctx, cancel := context.WithTimeout(context.Background(), 6*time.Second)
	defer cancel()
	command := exec.CommandContext(ctx, executable, arguments...)
	command.Dir = directory
	prepareHiddenCommand(command)
	return command.Run() == nil
}

func runWorkspaceGit(directory, executable string, arguments ...string) (string, bool) {
	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()
	command := exec.CommandContext(ctx, executable, arguments...)
	command.Dir = directory
	prepareHiddenCommand(command)
	output, err := command.CombinedOutput()
	return strings.ToValidUTF8(string(output), "�"), err == nil
}

func addedFileDiff(root, relative string) (string, error) {
	content, err := readWorkspaceDiffFile(root, relative)
	if err != nil {
		return "", err
	}
	lines := strings.Split(strings.ReplaceAll(content, "\r\n", "\n"), "\n")
	for index := range lines {
		lines[index] = "+" + lines[index]
	}
	return fmt.Sprintf("diff --git a/%s b/%s\nnew file mode 100644\n--- /dev/null\n+++ b/%s\n@@ -0,0 +1,%d @@\n%s",
		relative, relative, relative, len(lines), strings.Join(lines, "\n")), nil
}

func currentFilePreview(root, relative string) (string, error) {
	content, err := readWorkspaceDiffFile(root, relative)
	if err != nil {
		return "", err
	}
	return "当前文件 · " + relative + "\n\n" + content, nil
}

func readWorkspaceDiffFile(root, relative string) (string, error) {
	workspace, err := newLocalWorkspace(root)
	if err != nil {
		return "", err
	}
	path, err := workspace.resolveExisting(relative, false)
	if err != nil {
		return "", err
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return "", err
	}
	if len(data) > maxWorkspaceDiffBytes || !utf8.Valid(data) || bytes.IndexByte(data, 0) >= 0 {
		return "", errors.New("文件不是可预览的 UTF-8 文本或超过 256 KiB")
	}
	return string(data), nil
}

func truncateWorkspaceDiff(value string) (string, bool) {
	value = strings.ToValidUTF8(value, "�")
	if len(value) <= maxWorkspaceDiffBytes {
		return value, false
	}
	boundary := maxWorkspaceDiffBytes
	for boundary > 0 && !utf8.ValidString(value[:boundary]) {
		boundary--
	}
	return value[:boundary] + "\n\n…Diff 已按 256 KiB 上限截断…", true
}
