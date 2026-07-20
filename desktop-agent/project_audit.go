package main

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"
	"unicode"
)

const (
	projectAuditSchemaVersion = 1
	maxProjectAuditEntries    = 10_000
	maxProjectAuditPaths      = 32
	maxProjectAuditQueryLimit = 300
	maxProjectAuditFileBytes  = 32 * 1024 * 1024
	projectAuditSaveDelay     = 320 * time.Millisecond
)

const (
	projectAuditSourceExternal = "external"
	projectAuditSourceAgent    = "agent"
	projectAuditSourceCodex    = "codex"

	projectAuditOutcomeObserved = "observed"
	projectAuditOutcomeSuccess  = "success"
	projectAuditOutcomeFailed   = "failed"
)

var projectAuditActions = map[string]bool{
	"created": true, "modified": true, "deleted": true,
	"renamed_from": true, "renamed_to": true,
	"file_write": true, "directory_create": true, "path_delete": true,
	"permission_change": true, "code_edit": true, "terminal": true, "git": true,
}

type ProjectAuditEntry struct {
	ID           string   `json:"id"`
	ProjectPath  string   `json:"projectPath"`
	SessionID    string   `json:"sessionId,omitempty"`
	SessionTitle string   `json:"sessionTitle,omitempty"`
	Source       string   `json:"source"`
	Action       string   `json:"action"`
	Outcome      string   `json:"outcome"`
	ToolName     string   `json:"toolName,omitempty"`
	Summary      string   `json:"summary"`
	Paths        []string `json:"paths,omitempty"`
	CreatedAt    int64    `json:"createdAt"`
	Occurrences  int      `json:"occurrences"`
}

type ProjectAuditQuery struct {
	Search   string `json:"search,omitempty"`
	Source   string `json:"source,omitempty"`
	Limit    int    `json:"limit,omitempty"`
	BeforeAt int64  `json:"beforeAt,omitempty"`
	BeforeID string `json:"beforeId,omitempty"`
}

type ProjectAuditPage struct {
	ProjectPath    string              `json:"projectPath,omitempty"`
	Entries        []ProjectAuditEntry `json:"entries"`
	TotalCount     int                 `json:"totalCount"`
	FilteredCount  int                 `json:"filteredCount"`
	HasMore        bool                `json:"hasMore"`
	NextBeforeAt   int64               `json:"nextBeforeAt,omitempty"`
	NextBeforeID   string              `json:"nextBeforeId,omitempty"`
	StorageError   string              `json:"storageError,omitempty"`
	ArchiveVersion int                 `json:"archiveVersion"`
}

type ProjectAuditExportResult struct {
	Path       string `json:"path,omitempty"`
	EntryCount int    `json:"entryCount"`
	Message    string `json:"message"`
	Skipped    bool   `json:"skipped"`
}

type desktopProjectAuditDocument struct {
	SchemaVersion  int                 `json:"schemaVersion"`
	SourcePlatform string              `json:"sourcePlatform"`
	Entries        []ProjectAuditEntry `json:"entries"`
}

type projectAuditStore struct {
	mu        sync.Mutex
	path      string
	document  desktopProjectAuditDocument
	lastError string
	timer     *time.Timer
	closed    bool
	listener  func(ProjectAuditPage)
}

func newProjectAuditStore(path string) (*projectAuditStore, error) {
	path = strings.TrimSpace(path)
	if path == "" {
		return nil, errors.New("项目审计存储路径为空")
	}
	absolute, err := filepath.Abs(path)
	if err != nil {
		return nil, err
	}
	store := &projectAuditStore{
		path: absolute,
		document: desktopProjectAuditDocument{
			SchemaVersion: projectAuditSchemaVersion, SourcePlatform: desktopSourcePlatform(), Entries: []ProjectAuditEntry{},
		},
	}
	data, err := os.ReadFile(absolute)
	if errors.Is(err, os.ErrNotExist) {
		return store, nil
	}
	if err != nil {
		return nil, err
	}
	if len(data) > maxProjectAuditFileBytes {
		return nil, errors.New("项目审计归档超过 32 MiB 上限")
	}
	var document desktopProjectAuditDocument
	if err := decodeStrictJSON(data, &document); err != nil {
		return nil, fmt.Errorf("项目审计归档损坏：%w", err)
	}
	if err := validateProjectAuditDocument(document, true); err != nil {
		return nil, fmt.Errorf("项目审计归档无效：%w", err)
	}
	store.document = cloneProjectAuditDocument(document)
	return store, nil
}

func (store *projectAuditStore) SetListener(listener func(ProjectAuditPage)) {
	if store == nil {
		return
	}
	store.mu.Lock()
	store.listener = listener
	store.mu.Unlock()
}

func (store *projectAuditStore) Record(entry ProjectAuditEntry) error {
	if store == nil {
		return nil
	}
	entry = normalizeProjectAuditEntry(entry)
	if err := validateProjectAuditEntry(entry); err != nil {
		return err
	}
	store.mu.Lock()
	if store.closed {
		store.mu.Unlock()
		return errors.New("项目审计存储已经关闭")
	}
	for _, existing := range store.document.Entries {
		if existing.ID == entry.ID {
			store.mu.Unlock()
			return nil
		}
	}
	if entry.Source == projectAuditSourceExternal && len(store.document.Entries) > 0 {
		last := &store.document.Entries[len(store.document.Entries)-1]
		if sameWorkspacePath(last.ProjectPath, entry.ProjectPath) && last.Source == entry.Source &&
			last.Action == entry.Action && last.Outcome == entry.Outcome && equalFoldStrings(last.Paths, entry.Paths) &&
			entry.CreatedAt-last.CreatedAt >= 0 && entry.CreatedAt-last.CreatedAt <= 1_500 {
			last.CreatedAt = entry.CreatedAt
			last.Occurrences += entry.Occurrences
			if last.Occurrences > 100_000 {
				last.Occurrences = 100_000
			}
			store.scheduleSaveLocked()
			listener, page := store.listener, store.queryLocked(entry.ProjectPath, ProjectAuditQuery{Limit: 100})
			store.mu.Unlock()
			if listener != nil {
				listener(page)
			}
			return nil
		}
	}
	store.document.Entries = append(store.document.Entries, entry)
	if len(store.document.Entries) > maxProjectAuditEntries {
		store.document.Entries = append([]ProjectAuditEntry(nil), store.document.Entries[len(store.document.Entries)-maxProjectAuditEntries:]...)
	}
	store.scheduleSaveLocked()
	listener, page := store.listener, store.queryLocked(entry.ProjectPath, ProjectAuditQuery{Limit: 100})
	store.mu.Unlock()
	if listener != nil {
		listener(page)
	}
	return nil
}

func (store *projectAuditStore) Query(projectPath string, query ProjectAuditQuery) ProjectAuditPage {
	if store == nil {
		return ProjectAuditPage{Entries: []ProjectAuditEntry{}, ArchiveVersion: projectAuditSchemaVersion}
	}
	store.mu.Lock()
	defer store.mu.Unlock()
	return store.queryLocked(projectPath, query)
}

func (store *projectAuditStore) queryLocked(projectPath string, query ProjectAuditQuery) ProjectAuditPage {
	projectPath = normalizeAuditProjectPath(projectPath)
	query.Search = strings.ToLower(truncateRunes(query.Search, 200))
	query.Source = strings.ToLower(strings.TrimSpace(query.Source))
	if query.Source != "" && query.Source != "all" && !validProjectAuditSource(query.Source) {
		query.Source = ""
	}
	if query.Limit < 1 {
		query.Limit = 100
	}
	if query.Limit > maxProjectAuditQueryLimit {
		query.Limit = maxProjectAuditQueryLimit
	}
	page := ProjectAuditPage{
		ProjectPath: projectPath, Entries: []ProjectAuditEntry{}, StorageError: store.lastError,
		ArchiveVersion: projectAuditSchemaVersion,
	}
	if projectPath == "" {
		return page
	}
	matched := make([]ProjectAuditEntry, 0)
	for _, entry := range store.document.Entries {
		if !sameWorkspacePath(entry.ProjectPath, projectPath) {
			continue
		}
		page.TotalCount++
		if query.Source != "" && query.Source != "all" && entry.Source != query.Source {
			continue
		}
		if query.Search != "" && !strings.Contains(strings.ToLower(strings.Join([]string{
			entry.Summary, entry.ToolName, entry.SessionTitle, entry.SessionID, strings.Join(entry.Paths, " "),
		}, " ")), query.Search) {
			continue
		}
		matched = append(matched, cloneProjectAuditEntry(entry))
	}
	sort.SliceStable(matched, func(i, j int) bool {
		if matched[i].CreatedAt != matched[j].CreatedAt {
			return matched[i].CreatedAt > matched[j].CreatedAt
		}
		return matched[i].ID > matched[j].ID
	})
	page.FilteredCount = len(matched)
	for _, entry := range matched {
		if query.BeforeAt > 0 && (entry.CreatedAt > query.BeforeAt || (entry.CreatedAt == query.BeforeAt && entry.ID >= query.BeforeID)) {
			continue
		}
		page.Entries = append(page.Entries, entry)
		if len(page.Entries) > query.Limit {
			page.Entries = page.Entries[:query.Limit]
			page.HasMore = true
			break
		}
	}
	if len(page.Entries) > 0 {
		last := page.Entries[len(page.Entries)-1]
		page.NextBeforeAt, page.NextBeforeID = last.CreatedAt, last.ID
	}
	return page
}

func (store *projectAuditStore) ClearProject(projectPath string) (ProjectAuditPage, error) {
	if store == nil {
		return ProjectAuditPage{Entries: []ProjectAuditEntry{}, ArchiveVersion: projectAuditSchemaVersion}, nil
	}
	projectPath = normalizeAuditProjectPath(projectPath)
	if projectPath == "" {
		return ProjectAuditPage{}, errors.New("尚未选择项目")
	}
	store.mu.Lock()
	kept := make([]ProjectAuditEntry, 0, len(store.document.Entries))
	for _, entry := range store.document.Entries {
		if !sameWorkspacePath(entry.ProjectPath, projectPath) {
			kept = append(kept, entry)
		}
	}
	store.document.Entries = kept
	if err := store.writeLocked(); err != nil {
		store.mu.Unlock()
		return ProjectAuditPage{}, err
	}
	page, listener := store.queryLocked(projectPath, ProjectAuditQuery{Limit: 100}), store.listener
	store.mu.Unlock()
	if listener != nil {
		listener(page)
	}
	return page, nil
}

func (store *projectAuditStore) Snapshot() desktopProjectAuditDocument {
	if store == nil {
		return desktopProjectAuditDocument{SchemaVersion: projectAuditSchemaVersion, SourcePlatform: desktopSourcePlatform(), Entries: []ProjectAuditEntry{}}
	}
	store.mu.Lock()
	defer store.mu.Unlock()
	return cloneProjectAuditDocument(store.document)
}

func (store *projectAuditStore) RestoreSnapshot(document desktopProjectAuditDocument) error {
	if store == nil {
		return errors.New("项目审计存储不可用")
	}
	if err := validateProjectAuditDocument(document, true); err != nil {
		return err
	}
	store.mu.Lock()
	previous := store.document
	store.document = cloneProjectAuditDocument(document)
	if err := store.writeLocked(); err != nil {
		store.document = previous
		store.mu.Unlock()
		return err
	}
	listener := store.listener
	store.mu.Unlock()
	if listener != nil {
		listener(ProjectAuditPage{Entries: []ProjectAuditEntry{}, ArchiveVersion: projectAuditSchemaVersion})
	}
	return nil
}

func (store *projectAuditStore) Flush() error {
	if store == nil {
		return nil
	}
	store.mu.Lock()
	defer store.mu.Unlock()
	if store.timer != nil {
		store.timer.Stop()
		store.timer = nil
	}
	return store.writeLocked()
}

func (store *projectAuditStore) Close() error {
	if store == nil {
		return nil
	}
	store.mu.Lock()
	if store.closed {
		store.mu.Unlock()
		return nil
	}
	store.closed = true
	if store.timer != nil {
		store.timer.Stop()
		store.timer = nil
	}
	err := store.writeLocked()
	store.mu.Unlock()
	return err
}

func (store *projectAuditStore) scheduleSaveLocked() {
	if store.timer != nil {
		store.timer.Stop()
	}
	store.timer = time.AfterFunc(projectAuditSaveDelay, func() {
		store.mu.Lock()
		store.timer = nil
		_ = store.writeLocked()
		store.mu.Unlock()
	})
}

func (store *projectAuditStore) writeLocked() error {
	if err := validateProjectAuditDocument(store.document, true); err != nil {
		store.lastError = err.Error()
		return err
	}
	data, err := projectAuditJSON(store.document)
	if err != nil {
		store.lastError = err.Error()
		return err
	}
	if len(data) > maxProjectAuditFileBytes {
		err = errors.New("项目审计归档超过 32 MiB 上限")
		store.lastError = err.Error()
		return err
	}
	if err := writeBytesAtomic(store.path, data); err != nil {
		store.lastError = err.Error()
		return err
	}
	store.lastError = ""
	return nil
}

func validateProjectAuditDocument(document desktopProjectAuditDocument, requireCurrentPlatform bool) error {
	if document.SchemaVersion != projectAuditSchemaVersion {
		return errors.New("项目审计格式版本不受支持")
	}
	if !isDesktopSourcePlatform(document.SourcePlatform) {
		return errors.New("项目审计来源系统无效")
	}
	if requireCurrentPlatform && document.SourcePlatform != desktopSourcePlatform() {
		return errors.New("包含本机项目路径的审计归档只能在同一系统使用")
	}
	if len(document.Entries) > maxProjectAuditEntries {
		return errors.New("项目审计条目数量超过上限")
	}
	seen := map[string]bool{}
	for _, entry := range document.Entries {
		if err := validateProjectAuditEntry(entry); err != nil {
			return err
		}
		if seen[entry.ID] {
			return errors.New("项目审计包含重复条目 ID")
		}
		seen[entry.ID] = true
	}
	return nil
}

func validateProjectAuditEntry(entry ProjectAuditEntry) error {
	if strings.TrimSpace(entry.ID) == "" || len([]rune(entry.ID)) > 160 || entry.ID != strings.TrimSpace(entry.ID) {
		return errors.New("项目审计条目 ID 无效")
	}
	projectPath := strings.TrimSpace(entry.ProjectPath)
	if projectPath == "" || len(projectPath) > 32*1024 || !filepath.IsAbs(projectPath) || filepath.Clean(projectPath) != projectPath || strings.ContainsRune(projectPath, '\x00') {
		return errors.New("项目审计的项目路径无效")
	}
	if len([]rune(entry.SessionID)) > 160 || len([]rune(entry.SessionTitle)) > 200 || len([]rune(entry.ToolName)) > 200 || len([]rune(entry.Summary)) > 500 {
		return errors.New("项目审计字段超过长度上限")
	}
	if !validProjectAuditSource(entry.Source) || !projectAuditActions[entry.Action] ||
		(entry.Outcome != projectAuditOutcomeObserved && entry.Outcome != projectAuditOutcomeSuccess && entry.Outcome != projectAuditOutcomeFailed) {
		return errors.New("项目审计来源、动作或结果无效")
	}
	if entry.CreatedAt <= 0 || entry.Occurrences < 1 || entry.Occurrences > 100_000 || len(entry.Paths) > maxProjectAuditPaths {
		return errors.New("项目审计时间、次数或路径数量无效")
	}
	seenPaths := map[string]bool{}
	for _, value := range entry.Paths {
		relative, err := normalizeRelativePath(value, false)
		if err != nil || relative != value || relative == "." {
			return errors.New("项目审计包含无效相对路径")
		}
		key := strings.ToLower(relative)
		if seenPaths[key] {
			return errors.New("项目审计包含重复路径")
		}
		seenPaths[key] = true
	}
	for _, value := range []string{entry.SessionTitle, entry.ToolName, entry.Summary} {
		if strings.IndexFunc(value, func(r rune) bool { return unicode.IsControl(r) && r != '\t' }) >= 0 {
			return errors.New("项目审计包含控制字符")
		}
	}
	return nil
}

func normalizeProjectAuditEntry(entry ProjectAuditEntry) ProjectAuditEntry {
	entry.ID = strings.TrimSpace(entry.ID)
	if entry.ID == "" {
		entry.ID = newID("audit")
	}
	entry.ProjectPath = normalizeAuditProjectPath(entry.ProjectPath)
	entry.SessionID = truncateRunes(entry.SessionID, 160)
	entry.SessionTitle = sanitizeAuditText(entry.SessionTitle, 200)
	entry.Source = strings.ToLower(strings.TrimSpace(entry.Source))
	entry.Action = strings.ToLower(strings.TrimSpace(entry.Action))
	entry.Outcome = strings.ToLower(strings.TrimSpace(entry.Outcome))
	entry.ToolName = sanitizeAuditText(entry.ToolName, 200)
	entry.Summary = sanitizeAuditText(entry.Summary, 500)
	entry.Paths = normalizeAuditPaths(entry.ProjectPath, entry.Paths)
	if entry.CreatedAt <= 0 {
		entry.CreatedAt = time.Now().UnixMilli()
	}
	if entry.Occurrences < 1 {
		entry.Occurrences = 1
	}
	return entry
}

func normalizeAuditProjectPath(value string) string {
	value = strings.TrimSpace(value)
	if value == "" {
		return ""
	}
	if normalized, err := normalizeExistingProjectPath(value); err == nil {
		return normalized
	}
	if absolute, err := filepath.Abs(value); err == nil {
		return filepath.Clean(absolute)
	}
	return filepath.Clean(value)
}

func sanitizeAuditText(value string, limit int) string {
	value = strings.Map(func(r rune) rune {
		if unicode.IsControl(r) {
			return ' '
		}
		return r
	}, value)
	return truncateRunes(strings.Join(strings.Fields(value), " "), limit)
}

func normalizeAuditPaths(projectPath string, values []string) []string {
	result := make([]string, 0, min(len(values), maxProjectAuditPaths))
	seen := map[string]bool{}
	for _, value := range values {
		value = strings.TrimSpace(value)
		if value == "" {
			continue
		}
		if filepath.IsAbs(value) && projectPath != "" {
			if relative, err := filepath.Rel(projectPath, value); err == nil {
				value = filepath.ToSlash(relative)
			}
		}
		relative, err := normalizeRelativePath(filepath.ToSlash(value), false)
		if err != nil || relative == "." {
			continue
		}
		key := strings.ToLower(relative)
		if seen[key] {
			continue
		}
		seen[key] = true
		result = append(result, relative)
		if len(result) >= maxProjectAuditPaths {
			break
		}
	}
	return result
}

func validProjectAuditSource(source string) bool {
	return source == projectAuditSourceExternal || source == projectAuditSourceAgent || source == projectAuditSourceCodex
}

func cloneProjectAuditEntry(entry ProjectAuditEntry) ProjectAuditEntry {
	entry.Paths = append([]string(nil), entry.Paths...)
	return entry
}

func cloneProjectAuditDocument(document desktopProjectAuditDocument) desktopProjectAuditDocument {
	copy := document
	copy.Entries = make([]ProjectAuditEntry, 0, len(document.Entries))
	for _, entry := range document.Entries {
		copy.Entries = append(copy.Entries, cloneProjectAuditEntry(entry))
	}
	return copy
}

func equalFoldStrings(first, second []string) bool {
	if len(first) != len(second) {
		return false
	}
	for index := range first {
		if !strings.EqualFold(first[index], second[index]) {
			return false
		}
	}
	return true
}

func projectAuditReceiptID(sessionID, callID, toolName string) string {
	digest := sha256.Sum256([]byte(strings.TrimSpace(sessionID) + "\x00" + strings.TrimSpace(callID) + "\x00" + strings.TrimSpace(toolName)))
	return "audit-receipt-" + hex.EncodeToString(digest[:12])
}

func projectAuditJSON(document desktopProjectAuditDocument) ([]byte, error) {
	return json.MarshalIndent(document, "", "  ")
}
