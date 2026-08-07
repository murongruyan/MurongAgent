package main

import (
	"errors"
	"fmt"
	"strings"
	"sync"
	"time"
)

type providerImportCoordinator struct {
	mu      sync.Mutex
	pending map[string]pendingProviderImport
}

type ProviderImportResult struct {
	Config PublicDesktopConfig `json:"config"`
	Usage  ProviderUsageState  `json:"usage"`
}

func newProviderImportCoordinator() *providerImportCoordinator {
	return &providerImportCoordinator{pending: map[string]pendingProviderImport{}}
}

func providerImportLinkFromArgs(args []string) (string, error) {
	link := ""
	for _, argument := range args {
		trimmed := strings.TrimSpace(argument)
		lower := strings.ToLower(trimmed)
		if !strings.HasPrefix(lower, "ccswitch://") && !strings.HasPrefix(lower, "murongagent://provider/import") {
			continue
		}
		if link != "" {
			return "", errors.New("一次只能导入一个供应商链接")
		}
		link = trimmed
	}
	return link, nil
}

func (app *DesktopAgentApp) queueProviderImport(link string) (ProviderImportPreview, error) {
	payload, err := parseProviderImportDeepLink(link)
	if err != nil {
		return ProviderImportPreview{}, err
	}
	requestID := newID("provider_import")
	app.providerImports.mu.Lock()
	app.providerImports.cleanupLocked()
	app.providerImports.pending[requestID] = pendingProviderImport{Payload: payload, CreatedAt: time.Now()}
	app.providerImports.mu.Unlock()
	return providerImportPreview(requestID, payload), nil
}

func (app *DesktopAgentApp) GetPendingProviderImport() *ProviderImportPreview {
	app.providerImports.mu.Lock()
	defer app.providerImports.mu.Unlock()
	app.providerImports.cleanupLocked()
	var selectedID string
	var selected pendingProviderImport
	for id, pending := range app.providerImports.pending {
		if selectedID == "" || pending.CreatedAt.After(selected.CreatedAt) {
			selectedID = id
			selected = pending
		}
	}
	if selectedID == "" {
		return nil
	}
	preview := providerImportPreview(selectedID, selected.Payload)
	return &preview
}

func (app *DesktopAgentApp) CancelProviderImport(requestID string) {
	app.providerImports.mu.Lock()
	delete(app.providerImports.pending, strings.TrimSpace(requestID))
	app.providerImports.mu.Unlock()
}

func (app *DesktopAgentApp) ConfirmProviderImport(request ConfirmProviderImportRequest) (ProviderImportResult, error) {
	request.RequestID = strings.TrimSpace(request.RequestID)
	app.providerImports.mu.Lock()
	app.providerImports.cleanupLocked()
	pending, ok := app.providerImports.pending[request.RequestID]
	if ok {
		delete(app.providerImports.pending, request.RequestID)
	}
	app.providerImports.mu.Unlock()
	if !ok {
		return ProviderImportResult{}, errors.New("导入请求已失效，请从网站重新发起")
	}
	if request.EnableUsage && pending.Payload.UsageRule == nil {
		return ProviderImportResult{}, errors.New("这段用量脚本无法安全转换，不能启用自动查询")
	}
	previousConfig := app.store.rawConfig()
	profileID := providerImportTargetProfileID(previousConfig, pending.Payload)
	previousUsage, hadPreviousUsage := app.providerUsage.recordSnapshot(profileID)
	config, profileID, err := app.store.importProviderProfile(pending.Payload, request.Activate)
	if err != nil {
		app.requeueProviderImport(request.RequestID, pending)
		return ProviderImportResult{}, err
	}
	if err := app.providerUsage.Configure(profileID, pending.Payload.UsageRule, request.EnableUsage); err != nil {
		rollbackErr := app.store.restoreCredentialSyncConfig(previousConfig)
		usageRollbackErr := app.providerUsage.restoreRecord(profileID, previousUsage, hadPreviousUsage)
		app.requeueProviderImport(request.RequestID, pending)
		if rollbackErr != nil || usageRollbackErr != nil {
			return ProviderImportResult{}, fmt.Errorf("保存余额规则失败：%w；供应商回滚失败：%v；余额规则回滚失败：%v", err, rollbackErr, usageRollbackErr)
		}
		return ProviderImportResult{}, err
	}
	app.emit("settings:changed", config)
	if request.EnableUsage {
		go func() { _ = app.providerUsage.Refresh(app.ctx, profileID) }()
	}
	return ProviderImportResult{Config: config, Usage: app.providerUsage.State()}, nil
}

func (app *DesktopAgentApp) requeueProviderImport(requestID string, pending pendingProviderImport) {
	app.providerImports.mu.Lock()
	pending.CreatedAt = time.Now()
	app.providerImports.pending[strings.TrimSpace(requestID)] = pending
	app.providerImports.mu.Unlock()
}

func (app *DesktopAgentApp) GetProviderUsageState() ProviderUsageState {
	return app.providerUsage.State()
}

func (app *DesktopAgentApp) RefreshProviderUsage(profileID string) (ProviderUsageState, error) {
	ctx := app.ctx
	if ctx == nil {
		return app.providerUsage.State(), errors.New("Murong 窗口尚未就绪")
	}
	err := app.providerUsage.Refresh(ctx, profileID)
	return app.providerUsage.State(), err
}

func (coordinator *providerImportCoordinator) cleanupLocked() {
	cutoff := time.Now().Add(-10 * time.Minute)
	for id, pending := range coordinator.pending {
		if pending.CreatedAt.Before(cutoff) {
			delete(coordinator.pending, id)
		}
	}
}
