package desktopbridge

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"
	"unicode/utf8"
)

const (
	maxTextBytes       = 1024 * 1024
	maxDirectoryItems  = 500
	maxScanItems       = 5000
	maxDiffBytes       = 64 * 1024
	maxCommandChars    = 16_384
	maxCommandTimeout  = 10 * time.Minute
	defaultCommandTime = 2 * time.Minute
)

var scanExcludes = map[string]struct{}{
	".git": {}, ".gradle": {}, ".idea": {}, ".cache": {},
	"build": {}, "node_modules": {}, "dist": {}, "out": {}, "__pycache__": {},
}

type nodeFailure struct {
	code    string
	message string
}

func (failure *nodeFailure) Error() string { return failure.message }

func fail(code, message string) error {
	return &nodeFailure{code: code, message: message}
}

func failureDetails(err error) (string, string) {
	var failure *nodeFailure
	if errors.As(err, &failure) {
		return failure.code, truncateRunes(failure.message, 500)
	}
	return "node_io_error", "Desktop Node 执行文件系统操作失败"
}

type snapshotEntry struct {
	signature string
	directory bool
}

type workspace struct {
	root       string
	snapshotMu sync.Mutex
	snapshot   map[string]snapshotEntry
}

func newWorkspace(raw string) (*workspace, error) {
	if strings.TrimSpace(raw) == "" {
		return nil, errors.New("必须提供 --workspace")
	}
	absolute, err := filepath.Abs(strings.TrimSpace(raw))
	if err != nil {
		return nil, err
	}
	resolved, err := filepath.EvalSymlinks(absolute)
	if err != nil {
		return nil, fmt.Errorf("根目录不存在或无法解析：%w", err)
	}
	info, err := os.Stat(resolved)
	if err != nil {
		return nil, err
	}
	if !info.IsDir() {
		return nil, errors.New("工作区必须是目录")
	}
	return &workspace{root: filepath.Clean(resolved)}, nil
}

func validateWorkspacePath(raw string, allowRoot bool) (string, error) {
	if raw == "" || len(raw) > 1024 || raw != strings.TrimSpace(raw) {
		return "", fail("invalid_path", "相对路径无效")
	}
	if strings.ContainsAny(raw, "\\:") || strings.HasPrefix(raw, "/") {
		return "", fail("invalid_path", "不允许绝对路径、盘符或反斜杠")
	}
	for _, character := range raw {
		if character == 0 || character < 0x20 || character == 0x7f {
			return "", fail("invalid_path", "路径包含控制字符")
		}
	}
	if raw == "." {
		if !allowRoot {
			return "", fail("invalid_path", "该操作不能指向工作区根目录")
		}
		return raw, nil
	}
	segments := strings.Split(raw, "/")
	if len(segments) > 64 {
		return "", fail("path_outside_workspace", "路径层级超过 64 段")
	}
	for _, segment := range segments {
		if segment == "" || segment == "." || segment == ".." || utf8.RuneCountInString(segment) > 255 {
			return "", fail("path_outside_workspace", "路径包含空段、.、.. 或过长路径段")
		}
	}
	return strings.Join(segments, "/"), nil
}

func (workspace *workspace) resolveExisting(raw string, allowRoot bool) (string, os.FileInfo, error) {
	normalized, err := validateWorkspacePath(raw, allowRoot)
	if err != nil {
		return "", nil, err
	}
	target := workspace.root
	if normalized != "." {
		target = filepath.Join(workspace.root, filepath.FromSlash(normalized))
	}
	resolved, err := filepath.EvalSymlinks(target)
	if err != nil {
		return "", nil, err
	}
	if !pathContained(workspace.root, resolved) {
		return "", nil, fail("path_outside_workspace", "路径通过符号链接或目录联接越过工作区")
	}
	info, err := os.Stat(resolved)
	if err != nil {
		return "", nil, err
	}
	return resolved, info, nil
}

func (workspace *workspace) resolveNewTarget(raw string) (string, string, error) {
	normalized, err := validateWorkspacePath(raw, false)
	if err != nil {
		return "", "", err
	}
	segments := strings.Split(normalized, "/")
	name := segments[len(segments)-1]
	parentRelative := "."
	if len(segments) > 1 {
		parentRelative = strings.Join(segments[:len(segments)-1], "/")
	}
	parent, info, err := workspace.resolveExisting(parentRelative, true)
	if err != nil {
		return "", "", err
	}
	if !info.IsDir() {
		return "", "", fail("parent_not_directory", "目标父路径不是目录")
	}
	target := filepath.Join(parent, name)
	if !pathContained(workspace.root, target) {
		return "", "", fail("path_outside_workspace", "目标路径越过工作区")
	}
	return target, normalized, nil
}

func pathContained(root, candidate string) bool {
	relative, err := filepath.Rel(filepath.Clean(root), filepath.Clean(candidate))
	if err != nil {
		return false
	}
	return relative == "." || (relative != ".." && !strings.HasPrefix(relative, ".."+string(filepath.Separator)))
}

func (workspace *workspace) list(request workspaceRPCRequest) (workspaceRPCResult, error) {
	path, info, err := workspace.resolveExisting(request.Path, true)
	if err != nil {
		return workspaceRPCResult{}, workspace.wrapFilesystemError(err, "无法读取目录")
	}
	if !info.IsDir() {
		return workspaceRPCResult{}, fail("not_a_directory", "目标不是目录")
	}
	entries, err := os.ReadDir(path)
	if err != nil {
		return workspaceRPCResult{}, workspace.wrapFilesystemError(err, "无法读取目录")
	}
	limit := clampInt(request.MaxEntries, 1, maxDirectoryItems, maxDirectoryItems)
	if len(entries) > limit {
		return workspaceRPCResult{}, fail("directory_too_large", fmt.Sprintf("目录超过 %d 项，请读取更具体的子目录", limit))
	}
	result := make([]workspaceEntry, 0, len(entries))
	for _, entry := range entries {
		relative := entry.Name()
		if request.Path != "." {
			relative = request.Path + "/" + entry.Name()
		}
		resolved, childInfo, childErr := workspace.resolveExisting(relative, false)
		if childErr != nil {
			return workspaceRPCResult{}, workspace.wrapFilesystemError(childErr, "目录包含无法安全解析的条目")
		}
		_ = resolved
		item := workspaceEntry{Name: entry.Name(), Path: relative, Directory: childInfo.IsDir()}
		if !childInfo.IsDir() {
			size := childInfo.Size()
			item.Size = &size
		}
		modified := childInfo.ModTime().UnixMilli()
		item.LastModified = &modified
		result = append(result, item)
	}
	sort.Slice(result, func(left, right int) bool {
		if result[left].Directory != result[right].Directory {
			return result[left].Directory
		}
		return strings.ToLower(result[left].Name) < strings.ToLower(result[right].Name)
	})
	return workspaceRPCResult{Entries: result}, nil
}

func (workspace *workspace) read(request workspaceRPCRequest) (workspaceRPCResult, error) {
	path, info, err := workspace.resolveExisting(request.Path, false)
	if err != nil {
		return workspaceRPCResult{}, workspace.wrapFilesystemError(err, "无法读取文件")
	}
	content, hash, err := readTextFile(path, info, clampInt(request.MaxBytes, 1, maxTextBytes, maxTextBytes))
	if err != nil {
		return workspaceRPCResult{}, err
	}
	size := info.Size()
	modified := info.ModTime().UnixMilli()
	directory := false
	return workspaceRPCResult{
		Content: &content, SHA256: hash, Size: &size, LastModified: &modified, Directory: &directory,
	}, nil
}

func (workspace *workspace) stat(request workspaceRPCRequest) (workspaceRPCResult, error) {
	_, info, err := workspace.resolveExisting(request.Path, true)
	if err != nil {
		return workspaceRPCResult{}, workspace.wrapFilesystemError(err, "无法获取文件状态")
	}
	directory := info.IsDir()
	modified := info.ModTime().UnixMilli()
	result := workspaceRPCResult{Directory: &directory, LastModified: &modified}
	if !directory {
		size := info.Size()
		result.Size = &size
	}
	return result, nil
}

func (workspace *workspace) write(request workspaceRPCRequest) (workspaceRPCResult, error) {
	if request.Content == nil {
		return workspaceRPCResult{}, fail("invalid_request", "write 缺少 content")
	}
	contentBytes := []byte(*request.Content)
	limit := clampInt(request.MaxBytes, 1, maxTextBytes, maxTextBytes)
	if len(contentBytes) > limit {
		return workspaceRPCResult{}, fail("file_too_large", "写入内容超过 1 MiB 上限")
	}
	if !utf8.Valid(contentBytes) {
		return workspaceRPCResult{}, fail("invalid_utf8", "写入内容不是有效 UTF-8")
	}

	normalized, validationErr := validateWorkspacePath(request.Path, false)
	if validationErr != nil {
		return workspaceRPCResult{}, validationErr
	}
	existingPath, existingInfo, existingErr := workspace.resolveExisting(normalized, false)
	created := false
	before := ""
	beforeHash := ""
	target := existingPath
	if existingErr == nil {
		if existingInfo.IsDir() {
			return workspaceRPCResult{}, fail("not_a_file", "目标是目录")
		}
		var readErr error
		before, beforeHash, readErr = readTextFile(existingPath, existingInfo, limit)
		if readErr != nil {
			return workspaceRPCResult{}, readErr
		}
		if request.ExpectedSHA256 == nil || !validSHA256(*request.ExpectedSHA256) {
			return workspaceRPCResult{}, fail("expected_hash_required", "覆盖已有文件必须先读取并提供 expected_sha256")
		}
		if !strings.EqualFold(beforeHash, *request.ExpectedSHA256) {
			return workspaceRPCResult{}, fail("file_conflict", "文件已被其他程序修改，SHA-256 与读取时不一致")
		}
	} else if errors.Is(existingErr, fs.ErrNotExist) {
		if request.ExpectedSHA256 != nil && *request.ExpectedSHA256 != "" {
			return workspaceRPCResult{}, fail("file_conflict", "预期覆盖的文件已经不存在")
		}
		var resolveErr error
		target, normalized, resolveErr = workspace.resolveNewTarget(normalized)
		if resolveErr != nil {
			return workspaceRPCResult{}, workspace.wrapFilesystemError(resolveErr, "无法解析创建位置")
		}
		created = true
	} else {
		return workspaceRPCResult{}, workspace.wrapFilesystemError(existingErr, "无法解析写入目标")
	}

	diff := buildWorkspaceDiff(before, *request.Content, created)
	temp, err := os.CreateTemp(filepath.Dir(target), ".murong-write-*.tmp")
	if err != nil {
		return workspaceRPCResult{}, workspace.wrapFilesystemError(err, "无法创建原子写入临时文件")
	}
	tempName := temp.Name()
	defer removeIfExists(tempName)
	if existingInfo != nil {
		_ = temp.Chmod(existingInfo.Mode().Perm())
	}
	if _, err := temp.Write(contentBytes); err != nil {
		temp.Close()
		return workspaceRPCResult{}, workspace.wrapFilesystemError(err, "无法写入临时文件")
	}
	if err := temp.Sync(); err != nil {
		temp.Close()
		return workspaceRPCResult{}, workspace.wrapFilesystemError(err, "无法同步临时文件")
	}
	if err := temp.Close(); err != nil {
		return workspaceRPCResult{}, workspace.wrapFilesystemError(err, "无法关闭临时文件")
	}

	if created {
		if _, err := os.Lstat(target); err == nil {
			return workspaceRPCResult{}, fail("file_conflict", "目标文件已被其他程序创建")
		} else if !errors.Is(err, fs.ErrNotExist) {
			return workspaceRPCResult{}, workspace.wrapFilesystemError(err, "无法确认创建目标")
		}
	} else {
		latestInfo, err := os.Stat(target)
		if err != nil {
			return workspaceRPCResult{}, fail("file_conflict", "写入前目标文件已变化")
		}
		_, latestHash, err := readTextFile(target, latestInfo, limit)
		if err != nil || latestHash != beforeHash {
			return workspaceRPCResult{}, fail("file_conflict", "写入前文件又被修改，已取消覆盖")
		}
	}
	if err := replaceFile(tempName, target); err != nil {
		return workspaceRPCResult{}, workspace.wrapFilesystemError(err, "无法完成原子替换")
	}
	writtenInfo, err := os.Stat(target)
	if err != nil {
		return workspaceRPCResult{}, workspace.wrapFilesystemError(err, "无法校验写入结果")
	}
	_, writtenHash, err := readTextFile(target, writtenInfo, limit)
	if err != nil {
		return workspaceRPCResult{}, err
	}
	size := writtenInfo.Size()
	modified := writtenInfo.ModTime().UnixMilli()
	directory := false
	workspace.recordOwnPath(normalized, snapshotEntry{
		signature: fileSignature(writtenInfo), directory: false,
	})
	return workspaceRPCResult{
		SHA256: writtenHash, Size: &size, LastModified: &modified, Directory: &directory,
		Created: created, DiffPreview: diff,
	}, nil
}

func (workspace *workspace) mkdir(request workspaceRPCRequest) (workspaceRPCResult, error) {
	normalized, err := validateWorkspacePath(request.Path, false)
	if err != nil {
		return workspaceRPCResult{}, err
	}
	_, info, existingErr := workspace.resolveExisting(normalized, false)
	directory := true
	if existingErr == nil {
		if !info.IsDir() {
			return workspaceRPCResult{}, fail("path_exists", "目标已存在且不是目录")
		}
		return workspaceRPCResult{Directory: &directory, Created: false}, nil
	}
	if !errors.Is(existingErr, fs.ErrNotExist) {
		return workspaceRPCResult{}, workspace.wrapFilesystemError(existingErr, "无法解析目录目标")
	}
	target, normalized, err := workspace.resolveNewTarget(normalized)
	if err != nil {
		return workspaceRPCResult{}, workspace.wrapFilesystemError(err, "无法解析目录目标")
	}
	if err := os.Mkdir(target, 0o755); err != nil {
		if errors.Is(err, fs.ErrExist) {
			return workspaceRPCResult{}, fail("path_exists", "目标刚被其他程序创建")
		}
		return workspaceRPCResult{}, workspace.wrapFilesystemError(err, "无法创建目录")
	}
	createdInfo, err := os.Stat(target)
	if err != nil || !createdInfo.IsDir() {
		return workspaceRPCResult{}, fail("mkdir_failed", "目录创建后校验失败")
	}
	workspace.recordOwnPath(normalized, snapshotEntry{signature: "directory", directory: true})
	return workspaceRPCResult{Directory: &directory, Created: true}, nil
}

func (workspace *workspace) run(request workspaceRPCRequest, backend terminalBackend) (workspaceRPCResult, error) {
	if request.Command == nil || strings.TrimSpace(*request.Command) == "" {
		return workspaceRPCResult{}, fail("invalid_command", "run 缺少 command")
	}
	if utf8.RuneCountInString(*request.Command) > maxCommandChars || strings.ContainsRune(*request.Command, 0) {
		return workspaceRPCResult{}, fail("invalid_command", "终端命令为空、过长或包含 NUL")
	}
	directory, info, err := workspace.resolveExisting(request.Path, true)
	if err != nil {
		return workspaceRPCResult{}, workspace.wrapFilesystemError(err, "无法解析终端工作目录")
	}
	if !info.IsDir() {
		return workspaceRPCResult{}, fail("not_a_directory", "终端工作路径不是目录")
	}
	timeout := defaultCommandTime
	if request.TimeoutMillis != nil {
		timeout = time.Duration(*request.TimeoutMillis) * time.Millisecond
	}
	if timeout < time.Second {
		timeout = time.Second
	}
	if timeout > maxCommandTimeout {
		timeout = maxCommandTimeout
	}
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	command, commandErr := buildTerminalCommand(ctx, backend, directory, *request.Command)
	if commandErr != nil {
		return workspaceRPCResult{}, fail("terminal_unavailable", commandErr.Error())
	}
	prepareHiddenCommand(command)
	output := newCappedCommandOutput(clampInt(request.MaxBytes, 1, maxTextBytes, maxTextBytes))
	command.Stdout = output.writer(false)
	command.Stderr = output.writer(true)
	err = command.Run()
	if err != nil && command.ProcessState == nil {
		return workspaceRPCResult{}, fail("terminal_start_failed", "无法启动 "+backend.Label)
	}
	exitCode := -1
	if command.ProcessState != nil {
		exitCode = command.ProcessState.ExitCode()
	}
	stdout, stderr := output.strings()
	return workspaceRPCResult{
		Stdout:   stdout,
		Stderr:   stderr,
		ExitCode: &exitCode,
		TimedOut: errors.Is(ctx.Err(), context.DeadlineExceeded),
	}, nil
}

func readTextFile(path string, info os.FileInfo, limit int) (string, string, error) {
	if !info.Mode().IsRegular() {
		return "", "", fail("not_a_file", "目标不是普通文件")
	}
	if info.Size() > int64(limit) {
		return "", "", fail("file_too_large", fmt.Sprintf("文本文件超过 %d bytes 上限", limit))
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return "", "", fail("read_failed", "无法读取文件")
	}
	if len(data) > limit {
		return "", "", fail("file_too_large", fmt.Sprintf("文本文件超过 %d bytes 上限", limit))
	}
	probe := data
	if len(probe) > 4096 {
		probe = probe[:4096]
	}
	if bytes.IndexByte(probe, 0) >= 0 {
		return "", "", fail("binary_file", "检测到二进制文件，首版只允许 UTF-8 文本")
	}
	if !utf8.Valid(data) {
		return "", "", fail("invalid_utf8", "文件不是有效的 UTF-8 文本")
	}
	digest := sha256.Sum256(data)
	return string(data), hex.EncodeToString(digest[:]), nil
}

func validSHA256(value string) bool {
	if len(value) != 64 {
		return false
	}
	_, err := hex.DecodeString(value)
	return err == nil
}

func buildWorkspaceDiff(before, after string, created bool) string {
	if before == after {
		return "(内容没有变化)"
	}
	oldLines := strings.Split(strings.ReplaceAll(before, "\r\n", "\n"), "\n")
	newLines := strings.Split(strings.ReplaceAll(after, "\r\n", "\n"), "\n")
	prefix := 0
	for prefix < len(oldLines) && prefix < len(newLines) && oldLines[prefix] == newLines[prefix] {
		prefix++
	}
	oldSuffix := len(oldLines) - 1
	newSuffix := len(newLines) - 1
	for oldSuffix >= prefix && newSuffix >= prefix && oldLines[oldSuffix] == newLines[newSuffix] {
		oldSuffix--
		newSuffix--
	}
	oldLabel := "before"
	if created {
		oldLabel = "/dev/null"
	}
	lines := []string{"--- " + oldLabel, "+++ after", fmt.Sprintf("@@ line %d @@", prefix+1)}
	contextStart := prefix - 3
	if contextStart < 0 {
		contextStart = 0
	}
	for index := contextStart; index < prefix; index++ {
		lines = append(lines, "  "+oldLines[index])
	}
	oldEnd := minInt(oldSuffix+1, prefix+180)
	for index := prefix; index < oldEnd; index++ {
		lines = append(lines, "- "+oldLines[index])
	}
	newEnd := minInt(newSuffix+1, prefix+180)
	for index := prefix; index < newEnd; index++ {
		lines = append(lines, "+ "+newLines[index])
	}
	if oldSuffix+1 > oldEnd || newSuffix+1 > newEnd {
		lines = append(lines, "... (Diff 过长，已截断；完整内容不会在此预览重复显示)")
	}
	for index := newSuffix + 1; index < minInt(len(newLines), newSuffix+4); index++ {
		lines = append(lines, "  "+newLines[index])
	}
	diff := strings.Join(lines, "\n")
	if len(diff) > maxDiffBytes {
		diff = diff[:maxDiffBytes]
	}
	return diff
}

func (workspace *workspace) scanChanges() ([]observedChange, bool, error) {
	workspace.snapshotMu.Lock()
	defer workspace.snapshotMu.Unlock()
	next, partial, err := workspace.buildSnapshot()
	if err != nil {
		return nil, false, err
	}
	if workspace.snapshot == nil {
		workspace.snapshot = next
		return nil, partial, nil
	}
	changes := make([]observedChange, 0)
	for path, entry := range next {
		previous, exists := workspace.snapshot[path]
		if !exists {
			changes = append(changes, observedChange{Path: path, Kind: "created", Directory: entry.directory})
		} else if previous.signature != entry.signature {
			changes = append(changes, observedChange{Path: path, Kind: "modified", Directory: entry.directory})
		}
	}
	for path, previous := range workspace.snapshot {
		if _, exists := next[path]; !exists {
			changes = append(changes, observedChange{Path: path, Kind: "deleted", Directory: previous.directory})
		}
	}
	sort.Slice(changes, func(left, right int) bool { return changes[left].Path < changes[right].Path })
	workspace.snapshot = next
	return changes, partial, nil
}

func (workspace *workspace) buildSnapshot() (map[string]snapshotEntry, bool, error) {
	snapshot := make(map[string]snapshotEntry)
	count := 0
	partial := false
	err := filepath.WalkDir(workspace.root, func(path string, entry fs.DirEntry, walkErr error) error {
		if walkErr != nil {
			partial = true
			if entry != nil && entry.IsDir() {
				return fs.SkipDir
			}
			return nil
		}
		if path == workspace.root {
			return nil
		}
		if count >= maxScanItems {
			partial = true
			if entry.IsDir() {
				return fs.SkipDir
			}
			return nil
		}
		relative, err := filepath.Rel(workspace.root, path)
		if err != nil {
			partial = true
			return nil
		}
		relative = filepath.ToSlash(relative)
		if _, err := validateWorkspacePath(relative, false); err != nil {
			partial = true
			if entry.IsDir() {
				return fs.SkipDir
			}
			return nil
		}
		info, err := entry.Info()
		if err != nil {
			partial = true
			return nil
		}
		count++
		if info.Mode()&os.ModeSymlink != 0 {
			snapshot[relative] = snapshotEntry{signature: "link:" + info.ModTime().UTC().String()}
			if entry.IsDir() {
				return fs.SkipDir
			}
			return nil
		}
		if entry.IsDir() {
			snapshot[relative] = snapshotEntry{signature: "directory", directory: true}
			if _, excluded := scanExcludes[strings.ToLower(entry.Name())]; excluded {
				return fs.SkipDir
			}
			return nil
		}
		snapshot[relative] = snapshotEntry{signature: fileSignature(info)}
		return nil
	})
	if err != nil {
		return nil, partial, fail("scan_failed", "无法扫描工作区变化")
	}
	return snapshot, partial, nil
}

func (workspace *workspace) recordOwnPath(path string, entry snapshotEntry) {
	workspace.snapshotMu.Lock()
	defer workspace.snapshotMu.Unlock()
	if workspace.snapshot != nil {
		workspace.snapshot[path] = entry
	}
}

func fileSignature(info os.FileInfo) string {
	return fmt.Sprintf("file:%d:%d", info.Size(), info.ModTime().UnixMilli())
}

func (workspace *workspace) wrapFilesystemError(err error, fallback string) error {
	var failure *nodeFailure
	if errors.As(err, &failure) {
		return failure
	}
	if errors.Is(err, fs.ErrNotExist) {
		return fail("not_found", "目标不存在")
	}
	if errors.Is(err, fs.ErrPermission) {
		return fail("permission_denied", "电脑当前用户没有访问权限")
	}
	return fail("node_io_error", fallback)
}

type cappedCommandOutput struct {
	mu        sync.Mutex
	limit     int
	written   int
	stdout    bytes.Buffer
	stderr    bytes.Buffer
	truncated bool
}

type cappedStreamWriter struct {
	output *cappedCommandOutput
	stderr bool
}

func newCappedCommandOutput(limit int) *cappedCommandOutput {
	return &cappedCommandOutput{limit: limit}
}

func (output *cappedCommandOutput) writer(stderr bool) *cappedStreamWriter {
	return &cappedStreamWriter{output: output, stderr: stderr}
}

func (writer *cappedStreamWriter) Write(data []byte) (int, error) {
	writer.output.mu.Lock()
	defer writer.output.mu.Unlock()
	remaining := writer.output.limit - writer.output.written
	if remaining <= 0 {
		writer.output.truncated = true
		return len(data), nil
	}
	chunk := data
	if len(chunk) > remaining {
		chunk = chunk[:remaining]
		writer.output.truncated = true
	}
	if writer.stderr {
		_, _ = writer.output.stderr.Write(chunk)
	} else {
		_, _ = writer.output.stdout.Write(chunk)
	}
	writer.output.written += len(chunk)
	return len(data), nil
}

func (output *cappedCommandOutput) strings() (string, string) {
	output.mu.Lock()
	defer output.mu.Unlock()
	stdout := decodeCommandBytes(output.stdout.Bytes())
	stderr := decodeCommandBytes(output.stderr.Bytes())
	if output.truncated {
		marker := "...(truncated)"
		budget := output.limit - len(marker)
		if budget < 0 {
			budget = 0
			marker = marker[:minInt(len(marker), output.limit)]
		}
		stdout = truncateUTF8Bytes(stdout, budget)
		budget -= len([]byte(stdout))
		stderr = truncateUTF8Bytes(stderr, budget) + marker
	}
	return stdout, stderr
}

func truncateUTF8Bytes(value string, limit int) string {
	if limit <= 0 {
		return ""
	}
	data := []byte(value)
	if len(data) <= limit {
		return value
	}
	data = data[:limit]
	for len(data) > 0 && !utf8.Valid(data) {
		data = data[:len(data)-1]
	}
	return string(data)
}

func clampInt(value, minimum, maximum, fallback int) int {
	if value == 0 {
		return fallback
	}
	if value < minimum {
		return minimum
	}
	if value > maximum {
		return maximum
	}
	return value
}

func minInt(left, right int) int {
	if left < right {
		return left
	}
	return right
}
