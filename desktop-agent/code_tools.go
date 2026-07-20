package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"unicode/utf8"
)

type codeSearchMatch struct {
	Path    string `json:"path"`
	Line    int    `json:"line"`
	Match   string `json:"match"`
	Context string `json:"context,omitempty"`
}

var generatedDirectoryNames = map[string]bool{
	".git": true, ".gradle": true, "build": true, "out": true, "target": true,
	"intermediates": true, "mapping": true, "generated": true, "node_modules": true,
}

func (workspace *localWorkspace) searchCode(
	ctx context.Context,
	pattern, relative string,
	maxResults, contextLines int,
	fileGlob, excludeGlob string,
	caseSensitive, includeGenerated bool,
) ([]codeSearchMatch, error) {
	if strings.TrimSpace(pattern) == "" || len(pattern) > 4096 {
		return nil, errors.New("搜索 pattern 为空或超过 4096 字符")
	}
	if !caseSensitive {
		pattern = "(?i)" + pattern
	}
	expression, err := regexp.Compile(pattern)
	if err != nil {
		return nil, fmt.Errorf("搜索正则无效：%w", err)
	}
	root, err := workspace.resolveExisting(relative, true)
	if err != nil {
		return nil, err
	}
	if maxResults < 1 {
		maxResults = 20
	}
	if maxResults > 100 {
		maxResults = 100
	}
	if contextLines < 0 {
		contextLines = 0
	}
	if contextLines > 6 {
		contextLines = 6
	}
	excludes := compileGlobPatterns(excludeGlob)
	matches := make([]codeSearchMatch, 0, maxResults*2)
	searchFile := func(filePath string) error {
		if err := ctx.Err(); err != nil {
			return err
		}
		info, err := os.Stat(filePath)
		if err != nil || !info.Mode().IsRegular() || info.Size() > maxLocalTextBytes {
			return nil
		}
		rel, err := filepath.Rel(workspace.root, filePath)
		if err != nil {
			return nil
		}
		rel = filepath.ToSlash(rel)
		if fileGlob != "" && !matchesFileGlob(fileGlob, rel) {
			return nil
		}
		for _, exclude := range excludes {
			if exclude.MatchString(rel) {
				return nil
			}
		}
		data, err := os.ReadFile(filePath)
		if err != nil || !utf8.Valid(data) || bytes.IndexByte(data, 0) >= 0 {
			return nil
		}
		lines := strings.Split(strings.ReplaceAll(string(data), "\r\n", "\n"), "\n")
		for index, line := range lines {
			if !expression.MatchString(line) {
				continue
			}
			match := codeSearchMatch{Path: rel, Line: index + 1, Match: truncateRunes(line, 2000)}
			if contextLines > 0 {
				start := index - contextLines
				if start < 0 {
					start = 0
				}
				end := index + contextLines + 1
				if end > len(lines) {
					end = len(lines)
				}
				var snippet strings.Builder
				for lineIndex := start; lineIndex < end; lineIndex++ {
					marker := " "
					if lineIndex == index {
						marker = ">"
					}
					fmt.Fprintf(&snippet, "%s %4d | %s", marker, lineIndex+1, lines[lineIndex])
					if lineIndex+1 < end {
						snippet.WriteByte('\n')
					}
				}
				match.Context = snippet.String()
			}
			matches = append(matches, match)
			if len(matches) >= maxResults*5 {
				break
			}
		}
		return nil
	}
	info, err := os.Stat(root)
	if err != nil {
		return nil, err
	}
	if info.Mode().IsRegular() {
		if err := searchFile(root); err != nil {
			return nil, err
		}
	} else if info.IsDir() {
		err = filepath.WalkDir(root, func(filePath string, entry fs.DirEntry, walkErr error) error {
			if walkErr != nil {
				return walkErr
			}
			if err := ctx.Err(); err != nil {
				return err
			}
			if entry.IsDir() {
				if filePath != root && !includeGenerated && generatedDirectoryNames[strings.ToLower(entry.Name())] {
					return filepath.SkipDir
				}
				return nil
			}
			return searchFile(filePath)
		})
		if err != nil {
			return nil, err
		}
	} else {
		return nil, errors.New("代码搜索目标不是文件或目录")
	}
	sort.SliceStable(matches, func(i, j int) bool {
		left, right := codePathRank(matches[i].Path), codePathRank(matches[j].Path)
		if left != right {
			return left < right
		}
		if matches[i].Path != matches[j].Path {
			return matches[i].Path < matches[j].Path
		}
		return matches[i].Line < matches[j].Line
	})
	if len(matches) > maxResults {
		matches = matches[:maxResults]
	}
	return matches, nil
}

func compileGlobPatterns(raw string) []*regexp.Regexp {
	parts := strings.FieldsFunc(raw, func(r rune) bool { return r == ',' || r == ';' || r == '\n' })
	result := []*regexp.Regexp{}
	for _, part := range parts {
		part = strings.TrimSpace(strings.ReplaceAll(part, "\\", "/"))
		if part == "" {
			continue
		}
		quoted := regexp.QuoteMeta(part)
		quoted = strings.ReplaceAll(strings.ReplaceAll(quoted, `\*`, `.*`), `\?`, `.`)
		if expression, err := regexp.Compile("(?i)^" + quoted + "$"); err == nil {
			result = append(result, expression)
		}
	}
	return result
}

func matchesFileGlob(glob, relative string) bool {
	glob = strings.TrimSpace(strings.ReplaceAll(glob, "\\", "/"))
	if glob == "" {
		return true
	}
	if matched, _ := path.Match(glob, path.Base(relative)); matched {
		return true
	}
	matched, _ := path.Match(glob, relative)
	return matched
}

func codePathRank(value string) int {
	normalized := "/" + strings.ToLower(strings.ReplaceAll(value, "\\", "/"))
	switch {
	case strings.Contains(normalized, "/src/main/"):
		return 0
	case strings.Contains(normalized, "/src/test/"), strings.Contains(normalized, "/src/androidtest/"):
		return 1
	case strings.Contains(normalized, "/src/"):
		return 2
	case strings.Contains(normalized, "/build/"), strings.Contains(normalized, "/out/"), strings.Contains(normalized, "/target/"):
		return 4
	default:
		return 3
	}
}

func (app *DesktopAgentApp) executeCodeSearchTool(
	ctx context.Context, sessionID string, config desktopConfig, workspace *localWorkspace, call modelToolCall, raw map[string]json.RawMessage,
) (string, error) {
	pattern := rawString(raw, "pattern", "")
	relative := rawString(raw, "path", ".")
	if err := app.authorizeTool(ctx, config, ApprovalRequest{
		ID: newID("approval"), SessionID: sessionID, ToolName: call.Function.Name,
		Summary: "搜索项目代码", Detail: relative + "\n\n" + truncateRunes(pattern, 300), Arguments: call.Function.Arguments, Risk: "low",
	}, "path:"+relative); err != nil {
		return "", err
	}
	matches, err := workspace.searchCode(
		ctx, pattern, relative, rawInt(raw, "maxResults", 20), rawInt(raw, "contextLines", 2),
		rawString(raw, "fileGlob", ""), rawString(raw, "excludeGlob", ""),
		rawBool(raw, "caseSensitive", true), rawBool(raw, "includeGeneratedArtifacts", false),
	)
	return marshalToolResult(map[string]any{"success": err == nil, "engine": "go-regexp", "matches": matches, "count": len(matches)}), err
}

func (app *DesktopAgentApp) executeCodeEditTool(
	ctx context.Context, sessionID string, config desktopConfig, workspace *localWorkspace, call modelToolCall, raw map[string]json.RawMessage,
) (string, error) {
	operation := rawString(raw, "operation", "")
	relative := rawString(raw, "path", "")
	if operation == "" || relative == "" {
		return "", errors.New("code_edit 需要 operation 和 path")
	}
	risk := "low"
	summary := "查看代码文件"
	if operation != "view" {
		risk, summary = "high", "修改代码文件"
	}
	if err := app.authorizeTool(ctx, config, ApprovalRequest{
		ID: newID("approval"), SessionID: sessionID, ToolName: call.Function.Name,
		Summary: summary, Detail: relative, Arguments: call.Function.Arguments, Risk: risk,
	}, "path:"+relative); err != nil {
		return "", err
	}
	if operation != "view" {
		app.ignoreDirectWorkspaceChange(config.ProjectPath, relative)
	}
	switch operation {
	case "view":
		snapshot, err := workspace.read(relative)
		if err != nil {
			return marshalToolResult(map[string]any{"success": false}), err
		}
		start, end := rawInt(raw, "startLine", 1), rawInt(raw, "endLine", 0)
		if start < 1 {
			start = 1
		}
		lines := strings.Split(strings.ReplaceAll(snapshot.Content, "\r\n", "\n"), "\n")
		if end <= 0 || end > len(lines) {
			end = start + 199
			if end > len(lines) {
				end = len(lines)
			}
		}
		if end < start || start > len(lines) || end-start+1 > 400 {
			return "", errors.New("view 行号范围无效或超过 400 行")
		}
		return marshalToolResult(map[string]any{
			"success": true, "path": relative, "startLine": start, "endLine": end,
			"content": strings.Join(lines[start-1:end], "\n"), "sha256": snapshot.SHA256, "truncated": end < len(lines),
		}), nil
	case "search_replace":
		search, replace := rawStringPreserve(raw, "search"), rawStringPreserve(raw, "replace")
		if search == "" {
			return "", errors.New("search_replace 的 search 不能为空")
		}
		snapshot, err := workspace.read(relative)
		if err != nil {
			return "", err
		}
		count := strings.Count(snapshot.Content, search)
		if count != 1 {
			return "", fmt.Errorf("SEARCH 文本必须唯一，实际匹配 %d 次", count)
		}
		updated := strings.Replace(snapshot.Content, search, replace, 1)
		written, _, err := workspace.write(relative, updated, snapshot.SHA256)
		return marshalToolResult(map[string]any{"success": err == nil, "operation": operation, "file": written, "beforeSha256": snapshot.SHA256}), err
	case "apply_patch":
		patchText := rawStringPreserve(raw, "patch_text")
		snapshot, err := workspace.read(relative)
		if err != nil {
			return "", err
		}
		patched, err := applySingleFileUpdatePatch(relative, snapshot.Content, patchText)
		if err != nil {
			return "", err
		}
		written, _, err := workspace.write(relative, patched.Content, snapshot.SHA256)
		return marshalToolResult(map[string]any{"success": err == nil, "operation": operation, "hunkCount": patched.HunkCount, "file": written, "beforeSha256": snapshot.SHA256}), err
	case "create":
		content := rawStringPreserve(raw, "content")
		written, created, err := workspace.write(relative, content, "")
		if err == nil && !created {
			return "", errors.New("目标已存在，请使用 search_replace 或 apply_patch")
		}
		return marshalToolResult(map[string]any{"success": err == nil, "operation": operation, "created": created, "file": written}), err
	default:
		return "", fmt.Errorf("未知 code_edit operation：%s", operation)
	}
}

func rawString(raw map[string]json.RawMessage, name, fallback string) string {
	var value string
	if data := raw[name]; len(data) > 0 {
		_ = json.Unmarshal(data, &value)
	}
	if strings.TrimSpace(value) == "" {
		return fallback
	}
	return strings.TrimSpace(value)
}

func rawStringPreserve(raw map[string]json.RawMessage, name string) string {
	var value string
	if data := raw[name]; len(data) > 0 {
		_ = json.Unmarshal(data, &value)
	}
	return value
}

func rawInt(raw map[string]json.RawMessage, name string, fallback int) int {
	value := fallback
	if data := raw[name]; len(data) > 0 {
		_ = json.Unmarshal(data, &value)
	}
	return value
}

func rawBool(raw map[string]json.RawMessage, name string, fallback bool) bool {
	value := fallback
	if data := raw[name]; len(data) > 0 {
		_ = json.Unmarshal(data, &value)
	}
	return value
}
