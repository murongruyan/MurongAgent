package main

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"time"
)

const (
	workspaceReviewKind         = "workspace_review"
	maxWorkspaceReviewFiles     = 5_000
	maxWorkspaceReviewPatchSize = 64 << 20
)

type WorkspaceReviewFile struct {
	Path      string `json:"path"`
	Kind      string `json:"kind"`
	Additions int    `json:"additions"`
	Deletions int    `json:"deletions"`
	Binary    bool   `json:"binary,omitempty"`
}

type WorkspaceReview struct {
	ID            string                `json:"id"`
	ProjectPath   string                `json:"projectPath"`
	ProjectPrefix string                `json:"projectPrefix,omitempty"`
	BeforeTree    string                `json:"beforeTree"`
	AfterTree     string                `json:"afterTree"`
	Files         []WorkspaceReviewFile `json:"files"`
	Additions     int                   `json:"additions"`
	Deletions     int                   `json:"deletions"`
	BinaryFiles   int                   `json:"binaryFiles,omitempty"`
	CreatedAt     int64                 `json:"createdAt"`
	UndoAvailable bool                  `json:"undoAvailable"`
	Undone        bool                  `json:"undone,omitempty"`
	StatusMessage string                `json:"statusMessage,omitempty"`
}

type runWorkspaceReviewCheckpoint struct {
	ID            string
	ProjectPath   string
	GitRoot       string
	ProjectPrefix string
	GitExecutable string
	BeforeTree    string
}

func cloneWorkspaceReview(review *WorkspaceReview) *WorkspaceReview {
	if review == nil {
		return nil
	}
	cloned := *review
	cloned.Files = append([]WorkspaceReviewFile(nil), review.Files...)
	return &cloned
}

func (app *DesktopAgentApp) beginRunWorkspaceReview(sessionID string) {
	if app == nil || app.store == nil {
		return
	}
	app.mu.Lock()
	if app.runWorkspaceReviews == nil {
		app.runWorkspaceReviews = map[string]runWorkspaceReviewCheckpoint{}
	}
	_, exists := app.runWorkspaceReviews[sessionID]
	app.mu.Unlock()
	if exists {
		return
	}

	session := app.store.getSession(sessionID)
	if session == nil {
		return
	}
	projectPath := strings.TrimSpace(session.ProjectPath)
	if projectPath == "" {
		projectPath = strings.TrimSpace(app.store.rawConfig().ProjectPath)
	}
	checkpoint, err := captureRunWorkspaceReviewCheckpoint(projectPath)
	if err != nil {
		return
	}
	app.mu.Lock()
	if app.runWorkspaceReviews == nil {
		app.runWorkspaceReviews = map[string]runWorkspaceReviewCheckpoint{}
	}
	if _, exists := app.runWorkspaceReviews[sessionID]; !exists {
		app.runWorkspaceReviews[sessionID] = checkpoint
	}
	app.mu.Unlock()
}

func (app *DesktopAgentApp) finalizeRunWorkspaceReview(sessionID string) {
	if app == nil || app.store == nil || app.runRestartPending(sessionID) {
		return
	}
	app.mu.Lock()
	checkpoint, exists := app.runWorkspaceReviews[sessionID]
	if exists {
		delete(app.runWorkspaceReviews, sessionID)
	}
	app.mu.Unlock()
	if !exists {
		return
	}

	afterTree, err := captureGitWorktreeTree(checkpoint)
	if err != nil || afterTree == checkpoint.BeforeTree {
		return
	}
	review, err := buildWorkspaceReview(checkpoint, afterTree)
	if err != nil || len(review.Files) == 0 {
		return
	}
	if err := app.writeWorkspaceReviewPatch(review, checkpoint.GitRoot, checkpoint.GitExecutable); err != nil {
		review.UndoAvailable = false
		review.StatusMessage = err.Error()
	} else {
		review.UndoAvailable = true
	}
	content := fmt.Sprintf("本轮已编辑 %d 个文件（+%d -%d）", len(review.Files), review.Additions, review.Deletions)
	updated, err := app.store.appendMessage(sessionID, ChatMessage{
		Role: "assistant", Kind: workspaceReviewKind, Content: content, WorkspaceReview: review,
	})
	if err == nil {
		app.emitSessionsChanged(updated)
	}
}

func captureRunWorkspaceReviewCheckpoint(projectPath string) (runWorkspaceReviewCheckpoint, error) {
	projectPath, gitRoot, gitExecutable, prefix, err := resolveWorkspaceReviewProject(projectPath)
	if err != nil {
		return runWorkspaceReviewCheckpoint{}, err
	}
	checkpoint := runWorkspaceReviewCheckpoint{
		ID: newID("review"), ProjectPath: projectPath, GitRoot: gitRoot,
		ProjectPrefix: prefix, GitExecutable: gitExecutable,
	}
	checkpoint.BeforeTree, err = captureGitWorktreeTree(checkpoint)
	return checkpoint, err
}

func captureGitWorktreeTree(checkpoint runWorkspaceReviewCheckpoint) (string, error) {
	index, err := os.CreateTemp("", ".murong-review-index-*")
	if err != nil {
		return "", err
	}
	indexPath := index.Name()
	if err := index.Close(); err != nil {
		os.Remove(indexPath)
		return "", err
	}
	if err := os.Remove(indexPath); err != nil && !errors.Is(err, os.ErrNotExist) {
		return "", err
	}
	defer os.Remove(indexPath)
	environment := reviewGitEnvironment(indexPath)
	if _, err := runReviewGit(checkpoint.GitRoot, checkpoint.GitExecutable, environment, nil, "read-tree", "HEAD"); err != nil {
		if _, emptyErr := runReviewGit(checkpoint.GitRoot, checkpoint.GitExecutable, environment, nil, "read-tree", "--empty"); emptyErr != nil {
			return "", emptyErr
		}
	}
	scope := checkpoint.ProjectPrefix
	if scope == "" {
		scope = "."
	}
	if _, err := runReviewGit(checkpoint.GitRoot, checkpoint.GitExecutable, environment, nil, "add", "-A", "--", scope); err != nil {
		return "", err
	}
	output, err := runReviewGit(checkpoint.GitRoot, checkpoint.GitExecutable, environment, nil, "write-tree")
	if err != nil {
		return "", err
	}
	tree := strings.TrimSpace(string(output))
	if !validGitObjectID(tree) {
		return "", errors.New("无法生成项目变更快照")
	}
	return tree, nil
}

func buildWorkspaceReview(checkpoint runWorkspaceReviewCheckpoint, afterTree string) (*WorkspaceReview, error) {
	scope := checkpoint.ProjectPrefix
	if scope == "" {
		scope = "."
	}
	numstat, err := runReviewGit(checkpoint.GitRoot, checkpoint.GitExecutable, nil, nil,
		"-c", "core.quotePath=false", "diff", "--numstat", "--no-renames", checkpoint.BeforeTree, afterTree, "--", scope)
	if err != nil {
		return nil, err
	}
	statusOutput, err := runReviewGit(checkpoint.GitRoot, checkpoint.GitExecutable, nil, nil,
		"-c", "core.quotePath=false", "diff", "--name-status", "--no-renames", checkpoint.BeforeTree, afterTree, "--", scope)
	if err != nil {
		return nil, err
	}
	statuses := parseWorkspaceReviewStatuses(string(statusOutput), checkpoint.ProjectPrefix)
	files := make([]WorkspaceReviewFile, 0)
	review := &WorkspaceReview{
		ID: checkpoint.ID, ProjectPath: checkpoint.ProjectPath, ProjectPrefix: checkpoint.ProjectPrefix,
		BeforeTree: checkpoint.BeforeTree, AfterTree: afterTree, CreatedAt: time.Now().UnixMilli(),
	}
	for _, line := range strings.Split(strings.TrimSpace(string(numstat)), "\n") {
		parts := strings.SplitN(strings.TrimSuffix(line, "\r"), "\t", 3)
		if len(parts) != 3 {
			continue
		}
		path, ok := workspaceReviewDisplayPath(parts[2], checkpoint.ProjectPrefix)
		if !ok {
			continue
		}
		file := WorkspaceReviewFile{Path: path, Kind: statuses[path]}
		if file.Kind == "" {
			file.Kind = "modified"
		}
		if parts[0] == "-" || parts[1] == "-" {
			file.Binary = true
			review.BinaryFiles++
		} else {
			file.Additions, _ = strconv.Atoi(parts[0])
			file.Deletions, _ = strconv.Atoi(parts[1])
			review.Additions += file.Additions
			review.Deletions += file.Deletions
		}
		files = append(files, file)
		if len(files) >= maxWorkspaceReviewFiles {
			break
		}
	}
	sort.Slice(files, func(i, j int) bool {
		return strings.ToLower(files[i].Path) < strings.ToLower(files[j].Path)
	})
	review.Files = files
	return review, nil
}

func parseWorkspaceReviewStatuses(output, prefix string) map[string]string {
	statuses := map[string]string{}
	kinds := map[byte]string{'A': "created", 'D': "deleted", 'M': "modified", 'T': "modified"}
	for _, line := range strings.Split(strings.TrimSpace(output), "\n") {
		parts := strings.SplitN(strings.TrimSuffix(line, "\r"), "\t", 2)
		if len(parts) != 2 || len(parts[0]) == 0 {
			continue
		}
		path, ok := workspaceReviewDisplayPath(parts[1], prefix)
		if ok {
			statuses[path] = kinds[parts[0][0]]
		}
	}
	return statuses
}

func workspaceReviewDisplayPath(gitPath, prefix string) (string, bool) {
	gitPath = strings.TrimSpace(filepath.ToSlash(gitPath))
	if prefix != "" {
		if !strings.HasPrefix(gitPath, prefix+"/") {
			return "", false
		}
		gitPath = strings.TrimPrefix(gitPath, prefix+"/")
	}
	relative, err := normalizeRelativePath(gitPath, false)
	return relative, err == nil
}

func (app *DesktopAgentApp) writeWorkspaceReviewPatch(review *WorkspaceReview, gitRoot, gitExecutable string) error {
	directory := filepath.Join(filepath.Dir(app.store.configPath), "workspace-reviews")
	if err := os.MkdirAll(directory, 0o700); err != nil {
		return err
	}
	temp, err := os.CreateTemp(directory, ".workspace-review-*.patch")
	if err != nil {
		return err
	}
	tempPath := temp.Name()
	defer os.Remove(tempPath)
	scope := review.ProjectPrefix
	if scope == "" {
		scope = "."
	}
	ctx, cancel := context.WithTimeout(context.Background(), 90*time.Second)
	defer cancel()
	command := exec.CommandContext(ctx, gitExecutable,
		"diff", "--binary", "--full-index", "--no-renames", review.BeforeTree, review.AfterTree, "--", scope)
	command.Dir = gitRoot
	command.Stdout = temp
	var stderr bytes.Buffer
	command.Stderr = &stderr
	prepareHiddenCommand(command)
	if err := command.Run(); err != nil {
		temp.Close()
		return fmt.Errorf("无法保存本轮撤销补丁：%s", strings.TrimSpace(stderr.String()))
	}
	if err := temp.Sync(); err != nil {
		temp.Close()
		return err
	}
	info, err := temp.Stat()
	if err != nil {
		temp.Close()
		return err
	}
	if info.Size() == 0 || info.Size() > maxWorkspaceReviewPatchSize {
		temp.Close()
		return fmt.Errorf("本轮补丁为空或超过 %d MiB，已关闭一键撤销", maxWorkspaceReviewPatchSize>>20)
	}
	if err := temp.Close(); err != nil {
		return err
	}
	return replaceFile(tempPath, app.workspaceReviewPatchPath(review.ID))
}

func (app *DesktopAgentApp) GetWorkspaceReviewDiff(reviewID, path string) (WorkspaceDiff, error) {
	review, _, err := app.store.findWorkspaceReview(reviewID)
	if err != nil {
		return WorkspaceDiff{}, err
	}
	relative, err := validateWorkspaceReviewFile(review, path)
	if err != nil {
		return WorkspaceDiff{}, err
	}
	gitRoot, gitExecutable, prefix, err := resolveWorkspaceReviewRepository(review)
	if err != nil {
		return WorkspaceDiff{}, err
	}
	gitPath := relative
	if prefix != "" {
		gitPath = prefix + "/" + relative
	}
	output, commandErr := runReviewGit(gitRoot, gitExecutable, nil, nil,
		"-c", "core.quotePath=false", "diff", "--no-ext-diff", "--no-color", "--unified=3", "--no-renames",
		review.BeforeTree, review.AfterTree, "--", gitPath)
	result := WorkspaceDiff{Path: relative, Git: true}
	if commandErr != nil {
		result.Message = "本轮快照对象已不可用，无法重建该文件的修改前后对比。"
		return result, nil
	}
	diff := strings.TrimSpace(string(output))
	if diff == "" {
		result.Message = "该文件是二进制文件或仅发生了元数据变化，没有可显示的文本 Diff。"
		return result, nil
	}
	result.Diff, result.Truncated = truncateWorkspaceDiff(diff)
	result.Available = true
	result.Message = "本轮修改前后对比"
	return result, nil
}

func (app *DesktopAgentApp) UndoWorkspaceReview(reviewID string) (*ChatSession, error) {
	review, sessionID, err := app.store.findWorkspaceReview(reviewID)
	if err != nil {
		return nil, err
	}
	if review.Undone {
		return nil, errors.New("本轮文件变更已经撤销")
	}
	if !review.UndoAvailable {
		return nil, errors.New("本轮文件变更没有可用的安全撤销补丁")
	}
	gitRoot, gitExecutable, _, err := resolveWorkspaceReviewRepository(review)
	if err != nil {
		return nil, err
	}
	patchPath := app.workspaceReviewPatchPath(review.ID)
	if info, statErr := os.Stat(patchPath); statErr != nil || info.IsDir() || info.Size() <= 0 || info.Size() > maxWorkspaceReviewPatchSize {
		return nil, errors.New("本轮撤销补丁不存在或已损坏")
	}
	if _, err := runReviewGit(gitRoot, gitExecutable, nil, nil,
		"apply", "--reverse", "--check", "--whitespace=nowarn", patchPath); err != nil {
		return nil, errors.New("当前文件在本轮结束后又有变化，无法安全撤销；请先在“审核”中处理冲突")
	}
	if app.workspace != nil {
		paths := make([]string, 0, len(review.Files))
		for _, file := range review.Files {
			paths = append(paths, file.Path)
		}
		app.workspace.IgnoreAgentPaths(review.ProjectPath, paths...)
	}
	if _, err := runReviewGit(gitRoot, gitExecutable, nil, nil,
		"apply", "--reverse", "--whitespace=nowarn", patchPath); err != nil {
		return nil, fmt.Errorf("撤销本轮文件变更失败：%w", err)
	}
	return app.store.updateWorkspaceReview(sessionID, review.ID, func(updated *WorkspaceReview) {
		updated.UndoAvailable = false
		updated.Undone = true
		updated.StatusMessage = "本轮文件变更已撤销"
	})
}

func (store *desktopStore) findWorkspaceReview(reviewID string) (*WorkspaceReview, string, error) {
	reviewID = strings.TrimSpace(reviewID)
	if !validWorkspaceReviewID(reviewID) {
		return nil, "", errors.New("文件变更记录 ID 无效")
	}
	store.mu.Lock()
	defer store.mu.Unlock()
	for sessionID, session := range store.sessions {
		for index := range session.Messages {
			review := session.Messages[index].WorkspaceReview
			if review != nil && review.ID == reviewID {
				return cloneWorkspaceReview(review), sessionID, nil
			}
		}
	}
	return nil, "", errors.New("文件变更记录不存在")
}

func (store *desktopStore) updateWorkspaceReview(
	sessionID, reviewID string,
	update func(*WorkspaceReview),
) (*ChatSession, error) {
	store.mu.Lock()
	defer store.mu.Unlock()
	session := store.sessions[sessionID]
	if session == nil {
		return nil, errors.New("会话不存在")
	}
	if err := requireDesktopExecutionAuthority(session); err != nil {
		return nil, err
	}
	for index := range session.Messages {
		review := session.Messages[index].WorkspaceReview
		if review == nil || review.ID != reviewID {
			continue
		}
		before := cloneWorkspaceReview(review)
		previousUpdatedAt := session.UpdatedAt
		update(review)
		session.UpdatedAt = time.Now().UnixMilli()
		if err := store.saveSessionsLocked(); err != nil {
			session.Messages[index].WorkspaceReview = before
			session.UpdatedAt = previousUpdatedAt
			return nil, err
		}
		return cloneSession(session), nil
	}
	return nil, errors.New("文件变更记录不存在")
}

func resolveWorkspaceReviewRepository(review *WorkspaceReview) (string, string, string, error) {
	if err := validateWorkspaceReview(review); err != nil {
		return "", "", "", err
	}
	_, gitRoot, gitExecutable, prefix, err := resolveWorkspaceReviewProject(review.ProjectPath)
	if err != nil {
		return "", "", "", err
	}
	if prefix != review.ProjectPrefix {
		return "", "", "", errors.New("项目的 Git 根目录已变化，无法读取或撤销旧记录")
	}
	return gitRoot, gitExecutable, prefix, nil
}

func resolveWorkspaceReviewProject(projectPath string) (string, string, string, string, error) {
	projectPath, err := normalizeExistingProjectPath(projectPath)
	if err != nil {
		return "", "", "", "", err
	}
	gitExecutable, err := workspaceGitExecutable()
	if err != nil {
		return "", "", "", "", err
	}
	rootOutput, err := runReviewGit(projectPath, gitExecutable, nil, nil, "rev-parse", "--show-toplevel")
	if err != nil {
		return "", "", "", "", errors.New("当前项目不是 Git 工作树")
	}
	gitRoot, err := normalizeExistingProjectPath(strings.TrimSpace(string(rootOutput)))
	if err != nil {
		return "", "", "", "", err
	}
	relative, err := filepath.Rel(gitRoot, projectPath)
	if err != nil || relative == ".." || strings.HasPrefix(relative, ".."+string(filepath.Separator)) {
		return "", "", "", "", errors.New("项目目录不在 Git 工作树中")
	}
	prefix := ""
	if relative != "." {
		prefix = filepath.ToSlash(relative)
	}
	return projectPath, gitRoot, gitExecutable, prefix, nil
}

func validateWorkspaceReviewFile(review *WorkspaceReview, value string) (string, error) {
	relative, err := normalizeRelativePath(value, false)
	if err != nil {
		return "", err
	}
	for _, file := range review.Files {
		if file.Path == relative {
			return relative, nil
		}
	}
	return "", errors.New("文件不属于这条变更记录")
}

func validateWorkspaceReview(review *WorkspaceReview) error {
	if review == nil {
		return nil
	}
	if !validWorkspaceReviewID(review.ID) || !validGitObjectID(review.BeforeTree) || !validGitObjectID(review.AfterTree) ||
		review.CreatedAt <= 0 || review.Additions < 0 || review.Deletions < 0 || review.BinaryFiles < 0 ||
		len(review.Files) == 0 || len(review.Files) > maxWorkspaceReviewFiles {
		return errors.New("文件变更记录元数据无效")
	}
	if strings.TrimSpace(review.ProjectPath) != review.ProjectPath || !filepath.IsAbs(review.ProjectPath) ||
		filepath.Clean(review.ProjectPath) != review.ProjectPath || strings.ContainsRune(review.ProjectPath, '\x00') {
		return errors.New("文件变更记录项目路径无效")
	}
	if review.ProjectPrefix != "" {
		prefix, err := normalizeRelativePath(review.ProjectPrefix, true)
		if err != nil || prefix != review.ProjectPrefix {
			return errors.New("文件变更记录项目范围无效")
		}
	}
	seen := map[string]bool{}
	additions, deletions, binaries := 0, 0, 0
	validKinds := map[string]bool{"created": true, "modified": true, "deleted": true}
	for _, file := range review.Files {
		relative, err := normalizeRelativePath(file.Path, false)
		key := strings.ToLower(relative)
		if err != nil || relative != file.Path || seen[key] || !validKinds[file.Kind] || file.Additions < 0 || file.Deletions < 0 {
			return errors.New("文件变更记录包含无效或重复文件")
		}
		seen[key] = true
		additions += file.Additions
		deletions += file.Deletions
		if file.Binary {
			binaries++
		}
	}
	if additions != review.Additions || deletions != review.Deletions || binaries != review.BinaryFiles {
		return errors.New("文件变更记录统计不一致")
	}
	return nil
}

func validWorkspaceReviewID(value string) bool {
	if !strings.HasPrefix(value, "review-") || len(value) != len("review-")+24 {
		return false
	}
	for _, char := range value[len("review-"):] {
		if !strings.ContainsRune("0123456789abcdef", char) {
			return false
		}
	}
	return true
}

func validGitObjectID(value string) bool {
	if len(value) != 40 && len(value) != 64 {
		return false
	}
	for _, char := range strings.ToLower(value) {
		if !strings.ContainsRune("0123456789abcdef", char) {
			return false
		}
	}
	return true
}

func (app *DesktopAgentApp) workspaceReviewPatchPath(reviewID string) string {
	return filepath.Join(filepath.Dir(app.store.configPath), "workspace-reviews", reviewID+".patch")
}

func workspaceGitExecutable() (string, error) {
	if path, err := exec.LookPath("git.exe"); err == nil {
		return path, nil
	}
	return exec.LookPath("git")
}

func reviewGitEnvironment(indexPath string) []string {
	environment := make([]string, 0, len(os.Environ())+1)
	for _, entry := range os.Environ() {
		if !strings.HasPrefix(strings.ToUpper(entry), "GIT_INDEX_FILE=") {
			environment = append(environment, entry)
		}
	}
	return append(environment, "GIT_INDEX_FILE="+indexPath)
}

func runReviewGit(
	directory, executable string,
	environment []string,
	input []byte,
	arguments ...string,
) ([]byte, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	command := exec.CommandContext(ctx, executable, arguments...)
	command.Dir = directory
	if environment != nil {
		command.Env = environment
	}
	if input != nil {
		command.Stdin = bytes.NewReader(input)
	}
	prepareHiddenCommand(command)
	output, err := command.CombinedOutput()
	if ctx.Err() != nil {
		return nil, errors.New("Git 文件快照操作超时")
	}
	if err != nil {
		detail := truncateRunes(strings.ToValidUTF8(string(output), "�"), 800)
		if detail == "" {
			detail = err.Error()
		}
		return nil, errors.New(detail)
	}
	return output, nil
}
