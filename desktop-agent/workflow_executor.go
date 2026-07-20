package main

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"
)

const (
	maximumWorkflowScanDepth   = 3
	maximumWorkflowScanEntries = 400
)

var workflowIgnoredDirectories = map[string]bool{
	".git": true, ".gradle": true, "build": true, "node_modules": true,
	".cache": true, "__pycache__": true, "dist": true, "out": true, "target": true,
}

type workflowExecutionDependencies struct {
	sessions   *desktopStore
	github     runtimeGitHubConfig
	githubHTTP *http.Client
}

func executeSavedWorkflow(ctx context.Context, workflow SavedWorkflowDefinition, sessionID string, dependencies workflowExecutionDependencies) (string, error) {
	switch workflow.Template {
	case workflowProjectReadDiagnostic:
		return buildDirectoryReadOnlySummary(ctx, workflow.ProjectPath, "项目只读诊断")
	case workflowDirectoryChangeSummary:
		return buildDirectoryReadOnlySummary(ctx, workflow.ProjectPath, "目录快照")
	case workflowGitHubActionsStatus:
		client := dependencies.githubHTTP
		if client == nil {
			client = newGitHubHTTPClient()
		}
		return readGitHubActionsStatus(ctx, client, dependencies.github, workflow.GitHubRepository)
	case workflowSessionSummaryExport:
		return exportSessionSummary(sessionID, dependencies.sessions)
	default:
		return "", errors.New("未知工作流模板")
	}
}

type workflowScanItem struct {
	path  string
	depth int
}

func buildDirectoryReadOnlySummary(ctx context.Context, projectPath, label string) (string, error) {
	root, err := normalizeExistingProjectPath(projectPath)
	if err != nil {
		return "", errors.New("项目目录不可访问")
	}
	fileCount := 0
	directoryCount := 0
	totalBytes := int64(0)
	examples := make([]string, 0, 8)
	queue := []workflowScanItem{{path: root, depth: 0}}
	for len(queue) > 0 && fileCount+directoryCount < maximumWorkflowScanEntries {
		select {
		case <-ctx.Done():
			return "", ctx.Err()
		default:
		}
		current := queue[0]
		queue = queue[1:]
		entries, readErr := os.ReadDir(current.path)
		if readErr != nil {
			continue
		}
		sort.Slice(entries, func(i, j int) bool { return strings.ToLower(entries[i].Name()) < strings.ToLower(entries[j].Name()) })
		for _, entry := range entries {
			if fileCount+directoryCount >= maximumWorkflowScanEntries {
				break
			}
			if entry.Type()&os.ModeSymlink != 0 {
				continue
			}
			fullPath := filepath.Join(current.path, entry.Name())
			info, infoErr := entry.Info()
			if infoErr != nil {
				continue
			}
			if info.IsDir() {
				directoryCount++
				if current.depth < maximumWorkflowScanDepth && !workflowIgnoredDirectories[strings.ToLower(entry.Name())] {
					queue = append(queue, workflowScanItem{path: fullPath, depth: current.depth + 1})
				}
				continue
			}
			if !info.Mode().IsRegular() {
				continue
			}
			fileCount++
			if info.Size() > 0 {
				totalBytes += info.Size()
			}
			if len(examples) < 8 {
				relative, relativeErr := filepath.Rel(root, fullPath)
				if relativeErr == nil {
					examples = append(examples, filepath.ToSlash(relative))
				}
			}
		}
	}
	summary := fmt.Sprintf("%s：文件 %d，目录 %d，已扫描约 %d B", label, fileCount, directoryCount, totalBytes)
	if len(examples) > 0 {
		summary += "；示例：" + strings.Join(examples, "、")
	}
	if fileCount+directoryCount >= maximumWorkflowScanEntries {
		summary += "；已达到扫描上限"
	}
	return summary, nil
}

func exportSessionSummary(sessionID string, store *desktopStore) (string, error) {
	if store == nil {
		return "", errors.New("会话存储尚未就绪")
	}
	session := store.getSession(strings.TrimSpace(sessionID))
	if session == nil {
		return "", errors.New("请选择一个聊天任务后再导出")
	}
	home, err := os.UserHomeDir()
	if err != nil {
		return "", err
	}
	directory := filepath.Join(home, "Documents", "Murong Exports")
	if err := os.MkdirAll(directory, 0o700); err != nil {
		return "", fmt.Errorf("无法创建导出目录：%w", err)
	}
	stamp := time.Now().Format("20060102-150405")
	fileName := safeWorkflowFileName(session.Title) + "-" + stamp + ".md"
	path := filepath.Join(directory, fileName)
	var markdown strings.Builder
	markdown.WriteString("# ")
	markdown.WriteString(session.Title)
	markdown.WriteString("\n\n")
	if strings.TrimSpace(session.Goal) != "" {
		markdown.WriteString("目标：")
		markdown.WriteString(session.Goal)
		markdown.WriteString("\n\n")
	}
	markdown.WriteString(fmt.Sprintf("导出时间：%s\n\n", time.Now().Format("2006-01-02 15:04:05")))
	for _, message := range session.Messages {
		role := map[string]string{"user": "用户", "assistant": "Murong", "tool": "工具"}[message.Role]
		if role == "" {
			role = message.Role
		}
		markdown.WriteString("## ")
		markdown.WriteString(role)
		if message.ToolName != "" {
			markdown.WriteString(" · ")
			markdown.WriteString(message.ToolName)
		}
		markdown.WriteString("\n\n")
		markdown.WriteString(message.Content)
		markdown.WriteString("\n\n")
	}
	if err := writeBytesAtomic(path, []byte(markdown.String())); err != nil {
		return "", err
	}
	return "会话摘要已导出到文档目录 Murong Exports：" + fileName, nil
}

func safeWorkflowFileName(value string) string {
	value = truncateRunes(value, 60)
	if value == "" {
		value = "murong-session"
	}
	replacer := strings.NewReplacer("<", "_", ">", "_", ":", "_", "\"", "_", "/", "_", "\\", "_", "|", "_", "?", "_", "*", "_")
	value = strings.Trim(strings.TrimSpace(replacer.Replace(value)), ".")
	if value == "" {
		return "murong-session"
	}
	return value
}

func writeBytesAtomic(path string, data []byte) error {
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return err
	}
	temporary, err := os.CreateTemp(filepath.Dir(path), ".murong-workflow-*.tmp")
	if err != nil {
		return err
	}
	temporaryPath := temporary.Name()
	defer os.Remove(temporaryPath)
	if err := temporary.Chmod(0o600); err != nil {
		temporary.Close()
		return err
	}
	if _, err := temporary.Write(data); err != nil {
		temporary.Close()
		return err
	}
	if err := temporary.Sync(); err != nil {
		temporary.Close()
		return err
	}
	if err := temporary.Close(); err != nil {
		return err
	}
	return replaceFile(temporaryPath, path)
}
