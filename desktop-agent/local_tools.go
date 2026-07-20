package main

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"
)

const (
	maxLocalTextBytes   = 1024 * 1024
	maxLocalListItems   = 500
	maxLocalOutputBytes = 1024 * 1024
)

type localWorkspace struct {
	root string
}

type fileSnapshot struct {
	Path    string `json:"path"`
	Content string `json:"content"`
	SHA256  string `json:"sha256"`
	Size    int64  `json:"size"`
}

type listEntry struct {
	Path      string `json:"path"`
	Directory bool   `json:"directory"`
	Size      int64  `json:"size,omitempty"`
}

type terminalResult struct {
	Terminal string `json:"terminal"`
	ExitCode int    `json:"exitCode"`
	TimedOut bool   `json:"timedOut"`
	Stdout   string `json:"stdout"`
	Stderr   string `json:"stderr"`
}

func newLocalWorkspace(root string) (*localWorkspace, error) {
	root = strings.TrimSpace(root)
	if root == "" {
		return nil, errors.New("尚未选择项目目录")
	}
	absolute, err := filepath.Abs(root)
	if err != nil {
		return nil, err
	}
	resolved, err := filepath.EvalSymlinks(absolute)
	if err != nil {
		return nil, fmt.Errorf("无法解析项目目录：%w", err)
	}
	info, err := os.Stat(resolved)
	if err != nil || !info.IsDir() {
		return nil, errors.New("项目目录不存在或不是目录")
	}
	return &localWorkspace{root: filepath.Clean(resolved)}, nil
}

func (workspace *localWorkspace) resolveExisting(relative string, allowRoot bool) (string, error) {
	normalized, err := normalizeRelativePath(relative, allowRoot)
	if err != nil {
		return "", err
	}
	candidate := workspace.root
	if normalized != "." {
		candidate = filepath.Join(workspace.root, filepath.FromSlash(normalized))
	}
	resolved, err := filepath.EvalSymlinks(candidate)
	if err != nil {
		return "", err
	}
	if !pathInside(workspace.root, resolved) {
		return "", errors.New("路径通过链接越出项目目录")
	}
	return resolved, nil
}

func (workspace *localWorkspace) resolveNew(relative string) (string, error) {
	normalized, err := normalizeRelativePath(relative, false)
	if err != nil {
		return "", err
	}
	parent := filepath.Dir(filepath.Join(workspace.root, filepath.FromSlash(normalized)))
	resolvedParent, err := filepath.EvalSymlinks(parent)
	if err != nil {
		return "", err
	}
	if !pathInside(workspace.root, resolvedParent) {
		return "", errors.New("创建位置通过链接越出项目目录")
	}
	return filepath.Join(resolvedParent, filepath.Base(normalized)), nil
}

func normalizeRelativePath(value string, allowRoot bool) (string, error) {
	value = strings.TrimSpace(strings.ReplaceAll(value, "\\", "/"))
	if value == "" {
		value = "."
	}
	if filepath.IsAbs(value) || strings.Contains(value, ":") || strings.HasPrefix(value, "/") {
		return "", errors.New("只允许项目内相对路径")
	}
	clean := filepath.ToSlash(filepath.Clean(value))
	if clean == "." {
		if allowRoot {
			return clean, nil
		}
		return "", errors.New("该操作不能以项目根目录为目标")
	}
	if clean == ".." || strings.HasPrefix(clean, "../") {
		return "", errors.New("路径越出项目目录")
	}
	for _, segment := range strings.Split(clean, "/") {
		if segment == "" || segment == "." || segment == ".." || len(segment) > 255 {
			return "", errors.New("相对路径无效")
		}
	}
	return clean, nil
}

func pathInside(root, candidate string) bool {
	relative, err := filepath.Rel(root, candidate)
	return err == nil && relative != ".." && !strings.HasPrefix(relative, ".."+string(filepath.Separator))
}

func (workspace *localWorkspace) list(relative string, depth int) ([]listEntry, error) {
	root, err := workspace.resolveExisting(relative, true)
	if err != nil {
		return nil, err
	}
	info, err := os.Stat(root)
	if err != nil || !info.IsDir() {
		return nil, errors.New("列目录目标不是目录")
	}
	if depth < 1 {
		depth = 1
	}
	if depth > 4 {
		depth = 4
	}
	baseDepth := strings.Count(filepath.Clean(root), string(filepath.Separator))
	entries := make([]listEntry, 0, 128)
	err = filepath.WalkDir(root, func(path string, entry fs.DirEntry, walkErr error) error {
		if walkErr != nil {
			return walkErr
		}
		if path == root {
			return nil
		}
		currentDepth := strings.Count(filepath.Clean(path), string(filepath.Separator)) - baseDepth
		if entry.IsDir() && (entry.Name() == ".git" || entry.Name() == "node_modules" || currentDepth >= depth) {
			if currentDepth >= depth {
				relativePath, _ := filepath.Rel(workspace.root, path)
				entries = append(entries, listEntry{Path: filepath.ToSlash(relativePath), Directory: true})
			}
			return filepath.SkipDir
		}
		if len(entries) >= maxLocalListItems {
			return fs.SkipAll
		}
		relativePath, _ := filepath.Rel(workspace.root, path)
		item := listEntry{Path: filepath.ToSlash(relativePath), Directory: entry.IsDir()}
		if !entry.IsDir() {
			if fileInfo, infoErr := entry.Info(); infoErr == nil {
				item.Size = fileInfo.Size()
			}
		}
		entries = append(entries, item)
		return nil
	})
	sort.Slice(entries, func(i, j int) bool { return entries[i].Path < entries[j].Path })
	return entries, err
}

func (workspace *localWorkspace) read(relative string) (fileSnapshot, error) {
	path, err := workspace.resolveExisting(relative, false)
	if err != nil {
		return fileSnapshot{}, err
	}
	info, err := os.Stat(path)
	if err != nil || !info.Mode().IsRegular() {
		return fileSnapshot{}, errors.New("目标不是普通文件")
	}
	if info.Size() > maxLocalTextBytes {
		return fileSnapshot{}, errors.New("文件超过 1 MiB 上限")
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return fileSnapshot{}, err
	}
	if !utf8.Valid(data) || bytes.IndexByte(data, 0) >= 0 {
		return fileSnapshot{}, errors.New("首版只读取 UTF-8 文本文件")
	}
	digest := sha256.Sum256(data)
	return fileSnapshot{Path: relative, Content: string(data), SHA256: hex.EncodeToString(digest[:]), Size: info.Size()}, nil
}

func (workspace *localWorkspace) write(relative, content, expectedSHA string) (fileSnapshot, bool, error) {
	if len([]byte(content)) > maxLocalTextBytes || !utf8.ValidString(content) || strings.ContainsRune(content, 0) {
		return fileSnapshot{}, false, errors.New("写入内容必须是不超过 1 MiB 的 UTF-8 文本")
	}
	target, err := workspace.resolveExisting(relative, false)
	created := false
	if err == nil {
		current, readErr := workspace.read(relative)
		if readErr != nil {
			return fileSnapshot{}, false, readErr
		}
		if expectedSHA == "" || !strings.EqualFold(expectedSHA, current.SHA256) {
			return fileSnapshot{}, false, errors.New("覆盖文件必须提供最近读取到的 expected_sha256，且文件不能已被修改")
		}
	} else if errors.Is(err, os.ErrNotExist) {
		target, err = workspace.resolveNew(relative)
		created = true
	} else {
		return fileSnapshot{}, false, err
	}
	if err != nil {
		return fileSnapshot{}, false, err
	}
	temp, err := os.CreateTemp(filepath.Dir(target), ".murong-agent-*.tmp")
	if err != nil {
		return fileSnapshot{}, false, err
	}
	tempName := temp.Name()
	defer os.Remove(tempName)
	if _, err := io.WriteString(temp, content); err != nil {
		temp.Close()
		return fileSnapshot{}, false, err
	}
	if err := temp.Sync(); err != nil {
		temp.Close()
		return fileSnapshot{}, false, err
	}
	if err := temp.Close(); err != nil {
		return fileSnapshot{}, false, err
	}
	if err := replaceFile(tempName, target); err != nil {
		return fileSnapshot{}, false, err
	}
	written, err := workspace.read(relative)
	return written, created, err
}

func (workspace *localWorkspace) mkdir(relative string) (bool, error) {
	if existing, err := workspace.resolveExisting(relative, false); err == nil {
		info, statErr := os.Stat(existing)
		return false, statErrOrDirectory(info, statErr)
	}
	target, err := workspace.resolveNew(relative)
	if err != nil {
		return false, err
	}
	if err := os.Mkdir(target, 0o755); err != nil {
		return false, err
	}
	return true, nil
}

func (workspace *localWorkspace) resolveEntry(relative string) (string, error) {
	normalized, err := normalizeRelativePath(relative, false)
	if err != nil {
		return "", err
	}
	candidate := filepath.Join(workspace.root, filepath.FromSlash(normalized))
	resolvedParent, err := filepath.EvalSymlinks(filepath.Dir(candidate))
	if err != nil {
		return "", err
	}
	if !pathInside(workspace.root, resolvedParent) {
		return "", errors.New("目标位置通过链接越出项目目录")
	}
	return filepath.Join(resolvedParent, filepath.Base(candidate)), nil
}

func (workspace *localWorkspace) exists(relative string) (bool, error) {
	target, err := workspace.resolveEntry(relative)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return false, nil
		}
		return false, err
	}
	_, err = os.Lstat(target)
	if errors.Is(err, os.ErrNotExist) {
		return false, nil
	}
	return err == nil, err
}

func (workspace *localWorkspace) delete(relative string, recursive bool) error {
	target, err := workspace.resolveEntry(relative)
	if err != nil {
		return err
	}
	info, err := os.Lstat(target)
	if err != nil {
		return err
	}
	if info.IsDir() && !recursive {
		return errors.New("目标是目录；递归删除必须显式设置 recursive=true")
	}
	if info.IsDir() {
		return os.RemoveAll(target)
	}
	return os.Remove(target)
}

func (workspace *localWorkspace) chmod(relative, mode string) error {
	target, err := workspace.resolveEntry(relative)
	if err != nil {
		return err
	}
	mode = strings.TrimSpace(strings.TrimPrefix(mode, "0o"))
	parsed, err := strconv.ParseUint(mode, 8, 32)
	if err != nil || parsed > 0o777 {
		return errors.New("mode 必须是 000 到 777 的八进制权限")
	}
	return os.Chmod(target, os.FileMode(parsed))
}

func statErrOrDirectory(info os.FileInfo, err error) error {
	if err != nil {
		return err
	}
	if info == nil || !info.IsDir() {
		return errors.New("目标已存在且不是目录")
	}
	return nil
}

func runLocalTerminal(ctx context.Context, workspace *localWorkspace, backend TerminalBackend, relative, commandText string, timeoutSeconds int) (terminalResult, error) {
	directory, err := workspace.resolveExisting(relative, true)
	if err != nil {
		return terminalResult{}, err
	}
	info, err := os.Stat(directory)
	if err != nil || !info.IsDir() {
		return terminalResult{}, errors.New("终端工作目录不是目录")
	}
	if timeoutSeconds < 1 {
		timeoutSeconds = 120
	}
	if timeoutSeconds > 600 {
		timeoutSeconds = 600
	}
	commandContext, cancel := context.WithTimeout(ctx, time.Duration(timeoutSeconds)*time.Second)
	defer cancel()
	command, err := buildTerminalCommand(commandContext, backend, directory, commandText)
	if err != nil {
		return terminalResult{}, err
	}
	prepareHiddenCommand(command)
	var stdout, stderr cappedBuffer
	stdout.limit = maxLocalOutputBytes / 2
	stderr.limit = maxLocalOutputBytes / 2
	command.Stdout = &stdout
	command.Stderr = &stderr
	runErr := command.Run()
	exitCode := -1
	if command.ProcessState != nil {
		exitCode = command.ProcessState.ExitCode()
	}
	result := terminalResult{
		Terminal: backend.Label,
		ExitCode: exitCode,
		TimedOut: errors.Is(commandContext.Err(), context.DeadlineExceeded),
		Stdout:   decodeCommandBytes(stdout.data),
		Stderr:   decodeCommandBytes(stderr.data),
	}
	if runErr != nil && command.ProcessState == nil {
		return result, runErr
	}
	return result, nil
}

type cappedBuffer struct {
	data  []byte
	limit int
}

func (buffer *cappedBuffer) Write(data []byte) (int, error) {
	original := len(data)
	remaining := buffer.limit - len(buffer.data)
	if remaining > 0 {
		if len(data) > remaining {
			data = data[:remaining]
		}
		buffer.data = append(buffer.data, data...)
	}
	return original, nil
}

func marshalToolResult(value any) string {
	data, err := json.Marshal(value)
	if err != nil {
		return `{"error":"无法序列化工具结果"}`
	}
	return string(data)
}
