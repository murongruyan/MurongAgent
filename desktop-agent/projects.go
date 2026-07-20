package main

import (
	"errors"
	"io/fs"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"

	"github.com/wailsapp/wails/v2/pkg/runtime"
)

const (
	maxRecentProjects       = 12
	maxProjectPickerEntries = 180
)

var windowsReservedProjectNames = map[string]bool{
	"CON": true, "PRN": true, "AUX": true, "NUL": true,
	"COM1": true, "COM2": true, "COM3": true, "COM4": true, "COM5": true, "COM6": true, "COM7": true, "COM8": true, "COM9": true,
	"LPT1": true, "LPT2": true, "LPT3": true, "LPT4": true, "LPT5": true, "LPT6": true, "LPT7": true, "LPT8": true, "LPT9": true,
}

func normalizeExistingProjectPath(value string) (string, error) {
	value = strings.TrimSpace(value)
	if value == "" {
		return "", errors.New("项目目录不能为空")
	}
	resolved := canonicalWorkspacePath(value)
	info, err := os.Stat(resolved)
	if err != nil || !info.IsDir() {
		return "", errors.New("项目目录不存在或不是目录")
	}
	return resolved, nil
}

// canonicalWorkspacePath gives every spelling of the same workspace one
// stable identity. This matters on macOS where temporary paths commonly enter
// the process as /var/... while EvalSymlinks resolves them to /private/var/....
// Missing paths are still kept as cleaned absolute paths so a session can show
// its broken binding and let the user repair it.
func canonicalWorkspacePath(value string) string {
	value = strings.TrimSpace(value)
	if value == "" {
		return ""
	}
	if absolute, err := filepath.Abs(value); err == nil {
		value = absolute
	}
	value = filepath.Clean(value)
	if resolved, err := filepath.EvalSymlinks(value); err == nil {
		value = filepath.Clean(resolved)
	}
	return value
}

func normalizeRecentProjectRecords(values []RecentProjectRecord) []RecentProjectRecord {
	result := make([]RecentProjectRecord, 0, len(values))
	seen := map[string]bool{}
	for _, value := range values {
		path := canonicalWorkspacePath(value.Path)
		if path == "" {
			continue
		}
		key := strings.ToLower(path)
		if seen[key] {
			continue
		}
		seen[key] = true
		result = append(result, RecentProjectRecord{Path: path, LastOpenedAt: value.LastOpenedAt})
	}
	sort.SliceStable(result, func(i, j int) bool { return result[i].LastOpenedAt > result[j].LastOpenedAt })
	if len(result) > maxRecentProjects {
		result = result[:maxRecentProjects]
	}
	return result
}

func touchRecentProject(values []RecentProjectRecord, path string, openedAt int64) []RecentProjectRecord {
	path = canonicalWorkspacePath(path)
	result := []RecentProjectRecord{{Path: path, LastOpenedAt: openedAt}}
	for _, value := range normalizeRecentProjectRecords(values) {
		if !sameWorkspacePath(value.Path, path) {
			result = append(result, value)
		}
	}
	if len(result) > maxRecentProjects {
		result = result[:maxRecentProjects]
	}
	return result
}

func publicRecentProjects(values []RecentProjectRecord) []RecentProject {
	values = normalizeRecentProjectRecords(values)
	result := make([]RecentProject, 0, len(values))
	for _, value := range values {
		info, err := os.Stat(value.Path)
		result = append(result, RecentProject{
			Path: value.Path, Name: filepath.Base(value.Path), LastOpenedAt: value.LastOpenedAt,
			Exists: err == nil && info.IsDir(),
		})
	}
	return result
}

func validateProjectName(value string) (string, error) {
	value = strings.TrimSpace(value)
	if value == "" || value == "." || value == ".." {
		return "", errors.New("请输入项目文件夹名称")
	}
	if len([]rune(value)) > 100 || strings.ContainsAny(value, `<>:"/\|?*`) || strings.HasSuffix(value, ".") || strings.HasSuffix(value, " ") {
		return "", errors.New("项目名称包含 Windows 不允许的字符")
	}
	base := strings.ToUpper(strings.SplitN(value, ".", 2)[0])
	if windowsReservedProjectNames[base] {
		return "", errors.New("该名称是 Windows 保留名称")
	}
	return value, nil
}

func (store *desktopStore) activateProject(path string) (PublicDesktopConfig, error) {
	resolved, err := normalizeExistingProjectPath(path)
	if err != nil {
		return PublicDesktopConfig{}, err
	}
	store.mu.Lock()
	defer store.mu.Unlock()
	updated := store.config
	updated.ProjectPath = resolved
	updated.RecentProjects = touchRecentProject(updated.RecentProjects, resolved, time.Now().UnixMilli())
	if err := writeJSONAtomic(store.configPath, updated); err != nil {
		return PublicDesktopConfig{}, err
	}
	store.config = updated
	return publicConfig(updated), nil
}

func (store *desktopStore) closeProject() (PublicDesktopConfig, error) {
	store.mu.Lock()
	defer store.mu.Unlock()
	updated := store.config
	updated.ProjectPath = ""
	if err := writeJSONAtomic(store.configPath, updated); err != nil {
		return PublicDesktopConfig{}, err
	}
	store.config = updated
	return publicConfig(updated), nil
}

func (store *desktopStore) forgetRecentProject(path string) (PublicDesktopConfig, error) {
	store.mu.Lock()
	defer store.mu.Unlock()
	updated := store.config
	updated.RecentProjects = make([]RecentProjectRecord, 0, len(store.config.RecentProjects))
	for _, value := range store.config.RecentProjects {
		if !sameWorkspacePath(value.Path, path) {
			updated.RecentProjects = append(updated.RecentProjects, value)
		}
	}
	if err := writeJSONAtomic(store.configPath, updated); err != nil {
		return PublicDesktopConfig{}, err
	}
	store.config = updated
	return publicConfig(updated), nil
}

func (app *DesktopAgentApp) ensureProjectCanChange() error {
	app.mu.Lock()
	defer app.mu.Unlock()
	if len(app.runs) > 0 {
		return errors.New("有任务正在运行，请先停止任务再切换项目")
	}
	return nil
}

func (app *DesktopAgentApp) emitProjectChanged(config PublicDesktopConfig) {
	if app.workspace != nil {
		app.workspace.SetProject(config.ProjectPath)
	}
	app.emit("settings:changed", config)
	app.emit("project-knowledge:changed", app.store.projectKnowledge())
	app.emit("project-tools:changed", app.store.projectToolPreferences())
}

func (app *DesktopAgentApp) OpenProject() (*PublicDesktopConfig, error) {
	if app.ctx == nil {
		return nil, errors.New("窗口尚未就绪")
	}
	if err := app.ensureProjectCanChange(); err != nil {
		return nil, err
	}
	current := app.store.publicConfig().ProjectPath
	selected, err := runtime.OpenDirectoryDialog(app.ctx, runtime.OpenDialogOptions{
		Title: "打开 Murong 项目文件夹", DefaultDirectory: current, CanCreateDirectories: true,
	})
	if err != nil || strings.TrimSpace(selected) == "" {
		return nil, err
	}
	config, err := app.ActivateProject(selected)
	if err != nil {
		return nil, err
	}
	return &config, nil
}

func (app *DesktopAgentApp) SelectProjectParent(current string) (string, error) {
	if app.ctx == nil {
		return "", errors.New("窗口尚未就绪")
	}
	return runtime.OpenDirectoryDialog(app.ctx, runtime.OpenDialogOptions{
		Title: "选择新项目的父目录", DefaultDirectory: strings.TrimSpace(current), CanCreateDirectories: true,
	})
}

func (app *DesktopAgentApp) CreateProject(request CreateProjectRequest) (PublicDesktopConfig, error) {
	if err := app.ensureProjectCanChange(); err != nil {
		return PublicDesktopConfig{}, err
	}
	parent, err := normalizeExistingProjectPath(request.ParentPath)
	if err != nil {
		return PublicDesktopConfig{}, errors.New("新项目的父目录不存在")
	}
	name, err := validateProjectName(request.Name)
	if err != nil {
		return PublicDesktopConfig{}, err
	}
	target := filepath.Join(parent, name)
	if !pathInside(parent, target) {
		return PublicDesktopConfig{}, errors.New("新项目位置无效")
	}
	if _, err := os.Stat(target); !errors.Is(err, os.ErrNotExist) {
		return PublicDesktopConfig{}, errors.New("同名文件或文件夹已经存在")
	}
	if err := os.Mkdir(target, 0o755); err != nil {
		return PublicDesktopConfig{}, err
	}
	config, err := app.store.activateProject(target)
	if err != nil {
		_ = os.Remove(target)
		return PublicDesktopConfig{}, err
	}
	app.emitProjectChanged(config)
	return config, nil
}

func (app *DesktopAgentApp) ActivateProject(path string) (PublicDesktopConfig, error) {
	if err := app.ensureProjectCanChange(); err != nil {
		return PublicDesktopConfig{}, err
	}
	config, err := app.store.activateProject(path)
	if err == nil {
		app.emitProjectChanged(config)
	}
	return config, err
}

func (app *DesktopAgentApp) CloseProject() (PublicDesktopConfig, error) {
	if err := app.ensureProjectCanChange(); err != nil {
		return PublicDesktopConfig{}, err
	}
	config, err := app.store.closeProject()
	if err == nil {
		app.emitProjectChanged(config)
	}
	return config, err
}

func (app *DesktopAgentApp) ForgetRecentProject(path string) (PublicDesktopConfig, error) {
	config, err := app.store.forgetRecentProject(path)
	if err == nil {
		app.emit("settings:changed", config)
	}
	return config, err
}

func (app *DesktopAgentApp) SearchProjectEntries(query string) ([]ProjectEntry, error) {
	workspace, err := newLocalWorkspace(app.store.rawConfig().ProjectPath)
	if err != nil {
		return nil, err
	}
	query = strings.ToLower(strings.TrimSpace(strings.TrimPrefix(query, "@")))
	result := make([]ProjectEntry, 0, 64)
	err = filepath.WalkDir(workspace.root, func(path string, entry fs.DirEntry, walkErr error) error {
		if walkErr != nil {
			return nil
		}
		if path == workspace.root {
			return nil
		}
		if entry.IsDir() && generatedDirectoryNames[strings.ToLower(entry.Name())] {
			return filepath.SkipDir
		}
		relative, relErr := filepath.Rel(workspace.root, path)
		if relErr != nil {
			return nil
		}
		relative = filepath.ToSlash(relative)
		if query == "" && strings.Contains(relative, "/") {
			if entry.IsDir() {
				return filepath.SkipDir
			}
			return nil
		}
		if query != "" && !strings.Contains(strings.ToLower(relative), query) {
			return nil
		}
		item := ProjectEntry{Path: relative, Name: entry.Name(), Directory: entry.IsDir()}
		if !entry.IsDir() {
			if info, infoErr := entry.Info(); infoErr == nil {
				item.Size = info.Size()
			}
		}
		result = append(result, item)
		if len(result) >= maxProjectPickerEntries {
			return fs.SkipAll
		}
		return nil
	})
	if err != nil {
		return nil, err
	}
	sort.SliceStable(result, func(i, j int) bool {
		if result[i].Directory != result[j].Directory {
			return result[i].Directory
		}
		return strings.ToLower(result[i].Path) < strings.ToLower(result[j].Path)
	})
	return result, nil
}
