package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"path/filepath"
	"sort"
	"strings"
	"time"

	"github.com/wailsapp/wails/v2/pkg/runtime"
)

func (app *DesktopAgentApp) recordExternalWorkspaceAudit(projectPath string, change WorkspaceFileChange) {
	if app == nil || app.audit == nil {
		return
	}
	path := normalizeAuditPaths(projectPath, []string{change.Path})
	if len(path) == 0 {
		return
	}
	_ = app.audit.Record(ProjectAuditEntry{
		ProjectPath: projectPath, Source: projectAuditSourceExternal, Action: change.Kind,
		Outcome: projectAuditOutcomeObserved, Summary: "检测到项目外部" + workspaceChangeKindLabel(change.Kind),
		Paths: path, CreatedAt: change.ChangedAt,
	})
}

func (app *DesktopAgentApp) recordProjectToolAudit(
	sessionID, projectPath, callID, toolName, arguments, source string,
	succeeded bool,
) {
	if app == nil || app.audit == nil {
		return
	}
	action, summary, shouldRecord := projectAuditToolDescriptor(toolName, arguments)
	if !shouldRecord {
		return
	}
	projectPath = strings.TrimSpace(projectPath)
	var sessionTitle string
	if session := app.store.getSession(sessionID); session != nil {
		sessionTitle = session.Title
		if projectPath == "" {
			projectPath = session.ProjectPath
		}
	}
	if projectPath == "" {
		return
	}
	if source != projectAuditSourceCodex {
		source = projectAuditSourceAgent
	}
	outcome := projectAuditOutcomeSuccess
	if !succeeded {
		outcome = projectAuditOutcomeFailed
	}
	paths := auditPathsFromToolArguments(projectPath, arguments, toolName == "computer_workspace")
	if callID == "" {
		callID = newID("call")
	}
	_ = app.audit.Record(ProjectAuditEntry{
		ID: projectAuditReceiptID(sessionID, callID, toolName), ProjectPath: projectPath,
		SessionID: sessionID, SessionTitle: sessionTitle, Source: source, Action: action,
		Outcome: outcome, ToolName: toolName, Summary: summary, Paths: paths,
		CreatedAt: time.Now().UnixMilli(),
	})
}

func projectAuditToolDescriptor(toolName, arguments string) (string, string, bool) {
	toolName = strings.TrimSpace(toolName)
	var raw map[string]json.RawMessage
	_ = json.Unmarshal([]byte(arguments), &raw)
	switch toolName {
	case "write_file":
		return "file_write", "Agent 写入项目文件", true
	case "create_directory":
		return "directory_create", "Agent 创建项目目录", true
	case "delete_path":
		return "path_delete", "Agent 删除项目路径", true
	case "chmod_path":
		return "permission_change", "Agent 修改项目路径权限", true
	case "code_edit":
		operation := strings.ToLower(rawString(raw, "operation", ""))
		if operation == "view" {
			return "", "", false
		}
		return "code_edit", "Agent 修改项目代码", true
	case "run_terminal", "computer_terminal":
		command := rawString(raw, "command", "")
		if looksLikeGitCommand(command) {
			return "git", "Agent 执行 Git 命令", true
		}
		return "terminal", "Agent 执行项目终端命令", true
	case "computer_workspace":
		return "file_write", "Codex 修改项目文件", true
	default:
		return "", "", false
	}
}

func looksLikeGitCommand(command string) bool {
	command = strings.ToLower(strings.TrimSpace(command))
	for _, prefix := range []string{"git ", "git.exe ", "& git ", "& git.exe ", "sudo git "} {
		if strings.HasPrefix(command, prefix) {
			return true
		}
	}
	return strings.Contains(command, "&& git ") || strings.Contains(command, "; git ") || strings.Contains(command, "| git ")
}

func auditPathsFromToolArguments(projectPath, arguments string, allowObjectKeys bool) []string {
	var value any
	if json.Unmarshal([]byte(arguments), &value) != nil {
		return nil
	}
	values := []string{}
	var visit func(any, bool)
	visit = func(current any, fileChange bool) {
		switch typed := current.(type) {
		case []any:
			for _, child := range typed {
				visit(child, fileChange)
			}
		case map[string]any:
			for key, child := range typed {
				lower := strings.ToLower(strings.TrimSpace(key))
				if text, ok := child.(string); ok && (lower == "path" || lower == "filepath" || lower == "file_path" || lower == "cwd") {
					values = append(values, text)
				}
				if fileChange && looksLikeAuditPathKey(key) {
					values = append(values, key)
				}
				visit(child, fileChange)
			}
		}
	}
	visit(value, allowObjectKeys)
	return normalizeAuditPaths(projectPath, values)
}

func looksLikeAuditPathKey(value string) bool {
	value = strings.TrimSpace(strings.ReplaceAll(value, "\\", "/"))
	if value == "" || strings.ContainsAny(value, "\r\n\x00") {
		return false
	}
	return strings.Contains(value, "/") || strings.Contains(filepath.Base(value), ".")
}

func (app *DesktopAgentApp) GetProjectAudit(query ProjectAuditQuery) (ProjectAuditPage, error) {
	if err := validateProjectAuditQuery(query); err != nil {
		return ProjectAuditPage{}, err
	}
	if app.audit == nil {
		return ProjectAuditPage{Entries: []ProjectAuditEntry{}, ArchiveVersion: projectAuditSchemaVersion, StorageError: "项目审计存储不可用"}, nil
	}
	return app.audit.Query(app.store.rawConfig().ProjectPath, query), nil
}

func validateProjectAuditQuery(query ProjectAuditQuery) error {
	source := strings.ToLower(strings.TrimSpace(query.Source))
	if source != "" && source != "all" && !validProjectAuditSource(source) {
		return errors.New("项目审计来源筛选无效")
	}
	if query.Limit < 0 || query.Limit > maxProjectAuditQueryLimit || query.BeforeAt < 0 || len([]rune(query.BeforeID)) > 160 {
		return errors.New("项目审计分页参数无效")
	}
	if query.BeforeAt == 0 && strings.TrimSpace(query.BeforeID) != "" {
		return errors.New("项目审计分页游标不完整")
	}
	if len([]rune(query.Search)) > 200 || strings.IndexFunc(query.Search, func(r rune) bool { return r < 32 && r != '\t' }) >= 0 {
		return errors.New("项目审计搜索内容无效")
	}
	return nil
}

func (app *DesktopAgentApp) ClearProjectAudit(confirmed bool) (ProjectAuditPage, error) {
	if !confirmed {
		return ProjectAuditPage{}, errors.New("清空项目审计需要明确确认")
	}
	if app.audit == nil {
		return ProjectAuditPage{}, errors.New("项目审计存储不可用")
	}
	return app.audit.ClearProject(app.store.rawConfig().ProjectPath)
}

func (app *DesktopAgentApp) ExportProjectAudit(format string) (ProjectAuditExportResult, error) {
	if app.audit == nil {
		return ProjectAuditExportResult{}, errors.New("项目审计存储不可用")
	}
	if app.ctx == nil {
		return ProjectAuditExportResult{}, errors.New("窗口尚未就绪")
	}
	projectPath := strings.TrimSpace(app.store.rawConfig().ProjectPath)
	if projectPath == "" {
		return ProjectAuditExportResult{}, errors.New("尚未选择项目")
	}
	format = strings.ToLower(strings.TrimSpace(format))
	if format != "json" && format != "markdown" {
		return ProjectAuditExportResult{}, errors.New("审计导出格式无效")
	}
	document := app.audit.Snapshot()
	entries := make([]ProjectAuditEntry, 0)
	for _, entry := range document.Entries {
		if sameWorkspacePath(entry.ProjectPath, projectPath) {
			entries = append(entries, cloneProjectAuditEntry(entry))
		}
	}
	sort.SliceStable(entries, func(i, j int) bool {
		if entries[i].CreatedAt != entries[j].CreatedAt {
			return entries[i].CreatedAt < entries[j].CreatedAt
		}
		return entries[i].ID < entries[j].ID
	})
	if len(entries) == 0 {
		return ProjectAuditExportResult{}, errors.New("当前项目还没有审计记录")
	}
	extension, label := ".json", "JSON"
	data, err := projectAuditJSON(desktopProjectAuditDocument{
		SchemaVersion: projectAuditSchemaVersion, SourcePlatform: desktopSourcePlatform(), Entries: entries,
	})
	if format == "markdown" {
		extension, label = ".md", "Markdown"
		data = []byte(projectAuditMarkdown(projectPath, entries))
	}
	if err != nil {
		return ProjectAuditExportResult{}, err
	}
	fileName := fmt.Sprintf("murong_project_audit_%s_%s%s", sanitizeAuditFileName(filepath.Base(projectPath)), time.Now().Format("20060102_150405"), extension)
	path, err := runtime.SaveFileDialog(app.ctx, runtime.SaveDialogOptions{
		Title: "导出 Murong 项目审计", DefaultDirectory: projectPath, DefaultFilename: fileName,
		Filters:              []runtime.FileFilter{{DisplayName: label + " (*" + extension + ")", Pattern: "*" + extension}},
		CanCreateDirectories: true,
	})
	if err != nil {
		return ProjectAuditExportResult{}, err
	}
	if strings.TrimSpace(path) == "" {
		return ProjectAuditExportResult{Message: "已取消导出", Skipped: true}, nil
	}
	if !strings.EqualFold(filepath.Ext(path), extension) {
		path += extension
	}
	if err := writeBytesAtomic(path, data); err != nil {
		return ProjectAuditExportResult{}, err
	}
	return ProjectAuditExportResult{Path: path, EntryCount: len(entries), Message: fmt.Sprintf("已导出 %d 条项目审计记录", len(entries))}, nil
}

func projectAuditMarkdown(projectPath string, entries []ProjectAuditEntry) string {
	var output strings.Builder
	output.WriteString("# Murong 项目审计归档\n\n")
	output.WriteString("- 项目：`" + strings.ReplaceAll(projectPath, "`", "\\`") + "`\n")
	output.WriteString("- 导出时间：" + time.Now().Format(time.RFC3339) + "\n")
	output.WriteString(fmt.Sprintf("- 条目：%d\n\n", len(entries)))
	output.WriteString("| 时间 | 来源 | 结果 | 动作 | 会话 | 路径 |\n|---|---|---|---|---|---|\n")
	for _, entry := range entries {
		paths := strings.Join(entry.Paths, "、")
		if entry.Occurrences > 1 {
			paths = strings.TrimSpace(paths + fmt.Sprintf("（%d 次）", entry.Occurrences))
		}
		output.WriteString(fmt.Sprintf("| %s | %s | %s | %s | %s | %s |\n",
			auditMarkdownCell(time.UnixMilli(entry.CreatedAt).Format("2006-01-02 15:04:05")),
			auditMarkdownCell(projectAuditSourceLabel(entry.Source)), auditMarkdownCell(entry.Outcome),
			auditMarkdownCell(entry.Summary), auditMarkdownCell(entry.SessionTitle), auditMarkdownCell(paths)))
	}
	return output.String()
}

func auditMarkdownCell(value string) string {
	value = strings.ReplaceAll(strings.ReplaceAll(value, "|", "\\|"), "\n", " ")
	return strings.TrimSpace(value)
}

func sanitizeAuditFileName(value string) string {
	value = strings.TrimSpace(value)
	if value == "" {
		return "project"
	}
	value = strings.Map(func(r rune) rune {
		if strings.ContainsRune(`<>:"/\\|?*`, r) || r < 32 {
			return '_'
		}
		return r
	}, value)
	return truncateRunes(value, 60)
}

func projectAuditSourceLabel(source string) string {
	switch source {
	case projectAuditSourceExternal:
		return "外部程序"
	case projectAuditSourceCodex:
		return "内置 Codex"
	default:
		return "Murong Agent"
	}
}
