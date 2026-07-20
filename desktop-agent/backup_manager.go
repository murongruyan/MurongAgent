package main

import (
	"context"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"
)

type desktopBackupManager struct {
	store          *desktopStore
	workflows      *savedWorkflowStore
	audit          *projectAuditStore
	root           string
	automaticRoot  string
	preRestoreRoot string
	workRoot       string
	statePath      string

	mu       sync.Mutex
	runtime  desktopBackupRuntimeState
	ctx      context.Context
	cancel   context.CancelFunc
	wake     chan struct{}
	listener func()
}

func newDesktopBackupManager(store *desktopStore, workflows *savedWorkflowStore, audits ...*projectAuditStore) (*desktopBackupManager, error) {
	if store == nil || workflows == nil {
		return nil, errors.New("备份管理器缺少状态存储")
	}
	root := strings.TrimSpace(os.Getenv("MURONG_DESKTOP_BACKUP_DIR"))
	if root == "" {
		home, err := os.UserHomeDir()
		if err != nil {
			return nil, err
		}
		root = filepath.Join(home, "Documents", "Murong Backups")
	}
	root, err := filepath.Abs(root)
	if err != nil {
		return nil, err
	}
	dataRoot := filepath.Dir(store.configPath)
	var audit *projectAuditStore
	if len(audits) > 0 {
		audit = audits[0]
	}
	manager := &desktopBackupManager{
		store: store, workflows: workflows, audit: audit, root: root,
		automaticRoot:  filepath.Join(root, "automatic"),
		preRestoreRoot: filepath.Join(root, "pre_restore"),
		workRoot:       filepath.Join(dataRoot, "backup-work"),
		statePath:      filepath.Join(dataRoot, "desktop-agent-backups.json"),
		wake:           make(chan struct{}, 1),
		runtime:        desktopBackupRuntimeState{Settings: DesktopBackupSettings{MaxBackupCount: 7}},
	}
	if err := manager.loadRuntimeState(); err != nil {
		return nil, err
	}
	if err := os.MkdirAll(manager.workRoot, 0o700); err != nil {
		return nil, err
	}
	if err := os.MkdirAll(manager.root, 0o700); err != nil {
		return nil, err
	}
	manager.pruneStaleWorkDirectories()
	return manager, nil
}

func (manager *desktopBackupManager) SetListener(listener func()) {
	manager.mu.Lock()
	manager.listener = listener
	manager.mu.Unlock()
}

func (manager *desktopBackupManager) Start(parent context.Context) {
	manager.mu.Lock()
	if manager.cancel != nil {
		manager.cancel()
	}
	if parent == nil {
		parent = context.Background()
	}
	manager.ctx, manager.cancel = context.WithCancel(parent)
	ctx := manager.ctx
	manager.mu.Unlock()
	go manager.automaticLoop(ctx)
}

func (manager *desktopBackupManager) Close() {
	manager.mu.Lock()
	if manager.cancel != nil {
		manager.cancel()
		manager.cancel = nil
	}
	manager.mu.Unlock()
}

func (manager *desktopBackupManager) Status() DesktopBackupStatus {
	manager.mu.Lock()
	defer manager.mu.Unlock()
	return manager.statusLocked()
}

func (manager *desktopBackupManager) UpdateSettings(settings DesktopBackupSettings) (DesktopBackupStatus, error) {
	manager.mu.Lock()
	settings = normalizeDesktopBackupSettings(settings)
	manager.runtime.Settings = settings
	if err := manager.writeRuntimeStateLocked(); err != nil {
		manager.mu.Unlock()
		return DesktopBackupStatus{}, err
	}
	manager.pruneInternalBackupsLocked(settings.MaxBackupCount)
	status := manager.statusLocked()
	listener := manager.listener
	manager.mu.Unlock()
	manager.signalWake()
	if listener != nil {
		listener()
	}
	return status, nil
}

func (manager *desktopBackupManager) SuggestedManualFileName(now time.Time) string {
	if now.IsZero() {
		now = time.Now()
	}
	return fmt.Sprintf("murong_backup_%s.zip", now.Format("2006-01-02_15-04-05-000"))
}

func (manager *desktopBackupManager) CreateManualBackup(path string) (DesktopBackupOperationResult, error) {
	manager.mu.Lock()
	defer manager.mu.Unlock()
	path = strings.TrimSpace(path)
	if path == "" {
		return DesktopBackupOperationResult{Message: "已取消手动备份", Skipped: true, Status: manager.statusLocked()}, nil
	}
	absolute, err := filepath.Abs(path)
	if err != nil {
		return manager.failLocked("手动备份失败", err)
	}
	if !strings.EqualFold(filepath.Ext(absolute), ".zip") {
		absolute += ".zip"
	}
	manifest, err := manager.createArchiveUnlocked(absolute, backupKindManual, time.Now())
	if err != nil {
		return manager.failLocked("手动备份失败", err)
	}
	message := fmt.Sprintf("手动备份完成，共 %d 个条目", len(manifest.Entries))
	manager.markResultLocked(message, false)
	if err := manager.writeRuntimeStateLocked(); err != nil {
		return DesktopBackupOperationResult{}, err
	}
	result := DesktopBackupOperationResult{Manifest: &manifest, Message: message, OutputPath: absolute, Status: manager.statusLocked()}
	manager.emitChangedLocked()
	return result, nil
}

func (manager *desktopBackupManager) Restore(path string) (DesktopBackupOperationResult, error) {
	manager.mu.Lock()
	defer manager.mu.Unlock()
	path = strings.TrimSpace(path)
	if path == "" {
		return manager.failLocked("恢复失败", errors.New("未选择备份包"))
	}
	staging, err := os.MkdirTemp(manager.workRoot, "restore-validate-")
	if err != nil {
		return manager.failLocked("恢复失败", err)
	}
	defer os.RemoveAll(staging)
	validated, err := extractAndValidateDesktopBackup(path, staging)
	if err != nil {
		return manager.failLocked("备份校验失败", err)
	}
	portable, err := decodeAndValidateDesktopPortableState(validated)
	if err != nil {
		return manager.failLocked("备份内容校验失败", err)
	}
	currentStore := manager.store.backupSnapshot()
	currentWorkflows := manager.workflows.backupSnapshot()
	crossRestore := portable.CrossPlatform != nil && portable.CrossPlatform.SourcePlatform != desktopSourcePlatform()
	currentAudit := desktopProjectAuditDocument{}
	if manager.audit != nil {
		currentAudit = manager.audit.Snapshot()
	} else if !crossRestore && portable.ProjectAuditIncluded {
		return manager.failLocked("恢复状态构建失败", errors.New("备份包含项目审计，但当前审计存储不可用"))
	}
	var restoredStore desktopStoreBackupSnapshot
	var restoredWorkflows savedWorkflowStoreBackupSnapshot
	var crossResult desktopCrossPlatformRestoreResult
	if crossRestore {
		restoredStore, restoredWorkflows, crossResult, err = buildRestoredDesktopCrossPlatformSnapshots(
			currentStore,
			currentWorkflows,
			*portable.CrossPlatform,
		)
	} else {
		restoredStore, err = buildRestoredDesktopStoreSnapshot(currentStore, portable)
		restoredWorkflows = buildRestoredWorkflowSnapshot(currentWorkflows, portable.Workflows)
	}
	if err != nil {
		return manager.failLocked("恢复状态构建失败", err)
	}
	restoredAudit := currentAudit
	if !crossRestore && portable.ProjectAuditIncluded {
		restoredAudit = cloneProjectAuditDocument(portable.ProjectAudit)
	}
	now := time.Now()
	snapshotName := fmt.Sprintf("murong_pre_restore_%s.zip", now.Format("2006-01-02_15-04-05-000"))
	snapshotPath := filepath.Join(manager.preRestoreRoot, snapshotName)
	if _, err := manager.createArchiveUnlocked(snapshotPath, backupKindPreRestore, now); err != nil {
		return manager.failLocked("无法创建恢复前快照", err)
	}
	var mediaRestore *conversationMediaRestore
	if !crossRestore {
		mediaRestore, err = prepareConversationMediaRestore(manager.store, validated.PayloadRoot)
		if err != nil {
			return manager.failLocked("无法准备会话图片恢复", err)
		}
	}
	defer mediaRestore.Finish()
	previousRuntime := manager.runtime
	rollback := func(cause error) (DesktopBackupOperationResult, error) {
		rollbackErrors := []string{}
		if rollbackErr := mediaRestore.Rollback(); rollbackErr != nil {
			rollbackErrors = append(rollbackErrors, "会话图片："+rollbackErr.Error())
		}
		if rollbackErr := manager.store.restoreBackupSnapshot(currentStore); rollbackErr != nil {
			rollbackErrors = append(rollbackErrors, "配置与会话："+rollbackErr.Error())
		}
		if rollbackErr := manager.workflows.restoreBackupSnapshot(currentWorkflows); rollbackErr != nil {
			rollbackErrors = append(rollbackErrors, "工作流："+rollbackErr.Error())
		}
		if manager.audit != nil {
			if rollbackErr := manager.audit.RestoreSnapshot(currentAudit); rollbackErr != nil {
				rollbackErrors = append(rollbackErrors, "项目审计："+rollbackErr.Error())
			}
		}
		manager.runtime = previousRuntime
		if rollbackErr := manager.writeRuntimeStateLocked(); rollbackErr != nil {
			rollbackErrors = append(rollbackErrors, "备份设置："+rollbackErr.Error())
		}
		if len(rollbackErrors) > 0 {
			cause = fmt.Errorf("%w；回滚出现错误：%s", cause, strings.Join(rollbackErrors, "；"))
		}
		return manager.failLocked("恢复失败并已回滚", cause)
	}
	if err := manager.store.restoreBackupSnapshot(restoredStore); err != nil {
		return rollback(err)
	}
	if err := manager.workflows.restoreBackupSnapshot(restoredWorkflows); err != nil {
		return rollback(err)
	}
	if manager.audit != nil && !crossRestore && portable.ProjectAuditIncluded {
		if err := manager.audit.RestoreSnapshot(restoredAudit); err != nil {
			return rollback(err)
		}
	}
	if err := mediaRestore.Commit(); err != nil {
		return rollback(err)
	}
	if !crossRestore {
		manager.runtime.Settings = normalizeDesktopBackupSettings(portable.BackupSettings)
		manager.runtime.LastAutomaticBackupDay = ""
	}
	message := fmt.Sprintf("恢复完成，共恢复 %d 个条目；已保留恢复前快照", len(validated.Manifest.Entries))
	if crossRestore {
		message = fmt.Sprintf(
			"跨系统恢复完成：合并 %d 个会话，保留 %d 个冲突副本，跳过 %d 个重复会话；本机路径、凭据、项目审计和备份计划保持不变",
			crossResult.ImportedSessions,
			crossResult.ConflictCopies,
			crossResult.SkippedSessions,
		)
	}
	manager.markResultLocked(message, false)
	if err := manager.writeRuntimeStateLocked(); err != nil {
		return rollback(err)
	}
	manager.pruneInternalBackupsLocked(manager.runtime.Settings.MaxBackupCount)
	result := DesktopBackupOperationResult{
		Manifest: &validated.Manifest, Message: message, RestoredEntryCount: len(validated.Manifest.Entries),
		PreRestoreSnapshotName: snapshotName, Status: manager.statusLocked(),
	}
	manager.emitChangedLocked()
	manager.signalWake()
	return result, nil
}

func (manager *desktopBackupManager) createAutomaticBackupIfNeeded(now time.Time) (DesktopBackupOperationResult, error) {
	manager.mu.Lock()
	defer manager.mu.Unlock()
	settings := manager.runtime.Settings
	if !settings.DailyBackupEnabled {
		return DesktopBackupOperationResult{Message: "每日备份未启用", Skipped: true, Status: manager.statusLocked()}, nil
	}
	if now.Hour() < 3 {
		return DesktopBackupOperationResult{Message: "尚未到每日备份时间", Skipped: true, Status: manager.statusLocked()}, nil
	}
	day := now.Format("2006-01-02")
	if manager.runtime.LastAutomaticBackupDay == day {
		return DesktopBackupOperationResult{Message: "今天已经完成过自动备份", Skipped: true, Status: manager.statusLocked()}, nil
	}
	name := fmt.Sprintf("murong_auto_%s.zip", now.Format("2006-01-02_15-04-05-000"))
	target := filepath.Join(manager.automaticRoot, name)
	manifest, err := manager.createArchiveUnlocked(target, backupKindAutomatic, now)
	if err != nil {
		return manager.failLocked("每日备份失败", err)
	}
	manager.runtime.LastAutomaticBackupDay = day
	message := fmt.Sprintf("每日备份完成，共 %d 个条目", len(manifest.Entries))
	manager.markResultLocked(message, false)
	manager.pruneInternalBackupsLocked(settings.MaxBackupCount)
	if err := manager.writeRuntimeStateLocked(); err != nil {
		return DesktopBackupOperationResult{}, err
	}
	result := DesktopBackupOperationResult{Manifest: &manifest, Message: message, OutputPath: target, Status: manager.statusLocked()}
	manager.emitChangedLocked()
	return result, nil
}

func (manager *desktopBackupManager) createArchiveUnlocked(path, kind string, now time.Time) (DesktopBackupManifest, error) {
	payloads, err := buildDesktopBackupPayloads(manager.store, manager.workflows, manager.audit, manager.runtime.Settings)
	if err != nil {
		return DesktopBackupManifest{}, err
	}
	return writeDesktopBackupArchive(path, kind, payloads, now)
}

func (manager *desktopBackupManager) loadRuntimeState() error {
	data, err := os.ReadFile(manager.statePath)
	if errors.Is(err, os.ErrNotExist) {
		manager.runtime.Settings = normalizeDesktopBackupSettings(manager.runtime.Settings)
		return nil
	}
	if err != nil {
		return err
	}
	var state desktopBackupRuntimeState
	if err := decodeStrictJSON(data, &state); err != nil {
		return fmt.Errorf("桌面备份设置损坏：%w", err)
	}
	state.Settings = normalizeDesktopBackupSettings(state.Settings)
	manager.runtime = state
	return nil
}

func (manager *desktopBackupManager) writeRuntimeStateLocked() error {
	manager.runtime.Settings = normalizeDesktopBackupSettings(manager.runtime.Settings)
	return writeJSONAtomic(manager.statePath, manager.runtime)
}

func (manager *desktopBackupManager) statusLocked() DesktopBackupStatus {
	automatic := listDesktopBackupFiles(manager.automaticRoot)
	snapshots := listDesktopBackupFiles(manager.preRestoreRoot)
	latestSnapshot := ""
	if len(snapshots) > 0 {
		latestSnapshot = filepath.Base(snapshots[0])
	}
	return DesktopBackupStatus{
		Settings: manager.runtime.Settings, LastBackupAt: manager.runtime.LastBackupAt,
		LastBackupMessage: manager.runtime.LastBackupMessage, LastBackupFailed: manager.runtime.LastBackupFailed,
		AutomaticBackupCount: len(automatic), PreRestoreSnapshotCount: len(snapshots),
		LatestPreRestoreSnapshotName: latestSnapshot, StorageLocation: manager.root,
		ScheduleDescription: "应用打开期间每天 03:00 后自动执行一次；错过时间会在下次启动时补做。",
	}
}

func (manager *desktopBackupManager) markResultLocked(message string, failed bool) {
	manager.runtime.LastBackupAt = time.Now().UnixMilli()
	manager.runtime.LastBackupMessage = truncateRunes(strings.TrimSpace(message), 1_000)
	manager.runtime.LastBackupFailed = failed
}

func (manager *desktopBackupManager) failLocked(prefix string, cause error) (DesktopBackupOperationResult, error) {
	message := prefix
	if cause != nil && strings.TrimSpace(cause.Error()) != "" {
		message += "：" + cause.Error()
	}
	manager.markResultLocked(message, true)
	_ = manager.writeRuntimeStateLocked()
	manager.emitChangedLocked()
	return DesktopBackupOperationResult{Message: message, Status: manager.statusLocked()}, errors.New(message)
}

func (manager *desktopBackupManager) pruneInternalBackupsLocked(keep int) {
	keep = normalizeDesktopBackupSettings(DesktopBackupSettings{MaxBackupCount: keep}).MaxBackupCount
	for _, directory := range []string{manager.automaticRoot, manager.preRestoreRoot} {
		files := listDesktopBackupFiles(directory)
		if len(files) <= keep {
			continue
		}
		for _, path := range files[keep:] {
			_ = os.Remove(path)
		}
	}
}

func listDesktopBackupFiles(directory string) []string {
	entries, err := os.ReadDir(directory)
	if err != nil {
		return []string{}
	}
	type backupFile struct {
		path string
		mod  time.Time
	}
	files := []backupFile{}
	for _, entry := range entries {
		if entry.IsDir() || !strings.EqualFold(filepath.Ext(entry.Name()), ".zip") {
			continue
		}
		info, err := entry.Info()
		if err != nil || !info.Mode().IsRegular() {
			continue
		}
		files = append(files, backupFile{path: filepath.Join(directory, entry.Name()), mod: info.ModTime()})
	}
	sort.Slice(files, func(i, j int) bool {
		if files[i].mod.Equal(files[j].mod) {
			return files[i].path > files[j].path
		}
		return files[i].mod.After(files[j].mod)
	})
	result := make([]string, 0, len(files))
	for _, file := range files {
		result = append(result, file.path)
	}
	return result
}

func (manager *desktopBackupManager) automaticLoop(ctx context.Context) {
	_, _ = manager.createAutomaticBackupIfNeeded(time.Now())
	ticker := time.NewTicker(15 * time.Minute)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			_, _ = manager.createAutomaticBackupIfNeeded(time.Now())
		case <-manager.wake:
			_, _ = manager.createAutomaticBackupIfNeeded(time.Now())
		}
	}
}

func (manager *desktopBackupManager) signalWake() {
	select {
	case manager.wake <- struct{}{}:
	default:
	}
}

func (manager *desktopBackupManager) emitChangedLocked() {
	listener := manager.listener
	if listener != nil {
		go listener()
	}
}

func (manager *desktopBackupManager) pruneStaleWorkDirectories() {
	entries, err := os.ReadDir(manager.workRoot)
	if err != nil {
		return
	}
	cutoff := time.Now().Add(-24 * time.Hour)
	for _, entry := range entries {
		info, err := entry.Info()
		if err == nil && info.ModTime().Before(cutoff) {
			_ = os.RemoveAll(filepath.Join(manager.workRoot, entry.Name()))
		}
	}
}
