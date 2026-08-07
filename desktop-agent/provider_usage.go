package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"sort"
	"strings"
	"sync"
	"time"
)

const providerUsageSchemaVersion = 1

type providerUsageDocument struct {
	SchemaVersion int                            `json:"schemaVersion"`
	Records       map[string]ProviderUsageStatus `json:"records"`
}

type ProviderUsageStatus struct {
	ProviderProfileID string   `json:"providerProfileId"`
	Endpoint          string   `json:"endpoint"`
	IntervalMinutes   int      `json:"intervalMinutes"`
	Enabled           bool     `json:"enabled"`
	Remaining         *float64 `json:"remaining,omitempty"`
	Unit              string   `json:"unit,omitempty"`
	LastSyncedAt      int64    `json:"lastSyncedAt,omitempty"`
	NextSyncAt        int64    `json:"nextSyncAt,omitempty"`
	LastError         string   `json:"lastError,omitempty"`
	Syncing           bool     `json:"syncing"`
}

type ProviderUsageState struct {
	Items []ProviderUsageStatus `json:"items"`
}

type desktopProviderUsageManager struct {
	mu       sync.Mutex
	path     string
	store    *desktopStore
	records  map[string]ProviderUsageStatus
	listener func(ProviderUsageState)
	client   *http.Client
}

func newDesktopProviderUsageManager(path string, store *desktopStore) (*desktopProviderUsageManager, error) {
	manager := &desktopProviderUsageManager{
		path: path, store: store, records: map[string]ProviderUsageStatus{},
		client: &http.Client{Timeout: 30 * time.Second},
	}
	if err := manager.load(); err != nil {
		return nil, err
	}
	return manager, nil
}

func (manager *desktopProviderUsageManager) load() error {
	data, err := readFileIfExists(manager.path)
	if err != nil || len(data) == 0 {
		return err
	}
	var document providerUsageDocument
	if err := json.Unmarshal(data, &document); err != nil {
		return fmt.Errorf("供应商用量状态损坏：%w", err)
	}
	for id, record := range document.Records {
		record.ProviderProfileID = strings.TrimSpace(record.ProviderProfileID)
		if record.ProviderProfileID == "" {
			record.ProviderProfileID = strings.TrimSpace(id)
		}
		if record.ProviderProfileID == "" || record.Endpoint == "" {
			continue
		}
		record.IntervalMinutes = clampProviderUsageInterval(record.IntervalMinutes)
		record.Syncing = false
		manager.records[record.ProviderProfileID] = record
	}
	return nil
}

func (manager *desktopProviderUsageManager) Start(ctx context.Context, listener func(ProviderUsageState)) {
	manager.mu.Lock()
	manager.listener = listener
	manager.mu.Unlock()
	manager.notify()
	go func() {
		manager.refreshDue(ctx)
		ticker := time.NewTicker(time.Minute)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				manager.refreshDue(ctx)
			}
		}
	}()
}

func (manager *desktopProviderUsageManager) State() ProviderUsageState {
	manager.mu.Lock()
	defer manager.mu.Unlock()
	return providerUsageStateLocked(manager.records)
}

func (manager *desktopProviderUsageManager) Configure(profileID string, rule *providerImportUsageRule, enabled bool) error {
	profileID = strings.TrimSpace(profileID)
	manager.mu.Lock()
	if !enabled || rule == nil {
		delete(manager.records, profileID)
	} else {
		manager.records[profileID] = ProviderUsageStatus{
			ProviderProfileID: profileID,
			Endpoint:          rule.Endpoint,
			IntervalMinutes:   clampProviderUsageInterval(rule.IntervalMins),
			Enabled:           true,
		}
	}
	err := manager.saveLocked()
	manager.mu.Unlock()
	manager.notify()
	return err
}

func (manager *desktopProviderUsageManager) recordSnapshot(profileID string) (ProviderUsageStatus, bool) {
	manager.mu.Lock()
	defer manager.mu.Unlock()
	record, ok := manager.records[strings.TrimSpace(profileID)]
	return record, ok
}

func (manager *desktopProviderUsageManager) restoreRecord(profileID string, record ProviderUsageStatus, existed bool) error {
	profileID = strings.TrimSpace(profileID)
	manager.mu.Lock()
	if existed {
		record.Syncing = false
		manager.records[profileID] = record
	} else {
		delete(manager.records, profileID)
	}
	err := manager.saveLocked()
	manager.mu.Unlock()
	manager.notify()
	return err
}

func (manager *desktopProviderUsageManager) Refresh(ctx context.Context, profileID string) error {
	profileID = strings.TrimSpace(profileID)
	manager.mu.Lock()
	record, ok := manager.records[profileID]
	if !ok || !record.Enabled {
		manager.mu.Unlock()
		return errors.New("这个模型连接没有启用余额查询")
	}
	if record.Syncing {
		manager.mu.Unlock()
		return nil
	}
	record.Syncing = true
	record.LastError = ""
	manager.records[profileID] = record
	manager.mu.Unlock()
	manager.notify()

	remaining, unit, err := manager.fetch(ctx, record)
	now := time.Now()
	manager.mu.Lock()
	latest, exists := manager.records[profileID]
	if exists {
		latest.Syncing = false
		latest.NextSyncAt = now.Add(time.Duration(clampProviderUsageInterval(latest.IntervalMinutes)) * time.Minute).UnixMilli()
		if err != nil {
			latest.LastError = err.Error()
		} else {
			latest.Remaining = &remaining
			latest.Unit = unit
			latest.LastSyncedAt = now.UnixMilli()
			latest.LastError = ""
		}
		manager.records[profileID] = latest
		_ = manager.saveLocked()
	}
	manager.mu.Unlock()
	manager.notify()
	return err
}

func (manager *desktopProviderUsageManager) refreshDue(ctx context.Context) {
	now := time.Now().UnixMilli()
	manager.mu.Lock()
	ids := make([]string, 0, len(manager.records))
	for id, record := range manager.records {
		if record.Enabled && !record.Syncing && (record.NextSyncAt <= 0 || record.NextSyncAt <= now) {
			ids = append(ids, id)
		}
	}
	manager.mu.Unlock()
	for _, id := range ids {
		id := id
		go func() { _ = manager.Refresh(ctx, id) }()
	}
}

func (manager *desktopProviderUsageManager) fetch(ctx context.Context, record ProviderUsageStatus) (float64, string, error) {
	apiKey, err := manager.store.providerProfileAPIKey(record.ProviderProfileID)
	if err != nil {
		return 0, "", err
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, record.Endpoint, nil)
	if err != nil {
		return 0, "", err
	}
	request.Header.Set("Accept", "application/json")
	request.Header.Set("Authorization", "Bearer "+apiKey)
	response, err := manager.client.Do(request)
	if err != nil {
		return 0, "", fmt.Errorf("余额查询失败：%w", err)
	}
	defer response.Body.Close()
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		return 0, "", fmt.Errorf("余额查询失败：HTTP %d", response.StatusCode)
	}
	data, err := io.ReadAll(io.LimitReader(response.Body, 1024*1024+1))
	if err != nil {
		return 0, "", fmt.Errorf("读取余额响应失败：%w", err)
	}
	if len(data) > 1024*1024 {
		return 0, "", errors.New("余额响应超过 1 MiB")
	}
	var payload any
	decoder := json.NewDecoder(strings.NewReader(string(data)))
	decoder.UseNumber()
	if err := decoder.Decode(&payload); err != nil {
		return 0, "", errors.New("余额响应不是有效 JSON")
	}
	if !providerUsageResponseActive(payload) {
		return 0, "", errors.New("余额接口报告当前 API Key 已停用")
	}
	remaining, unit, ok := findProviderRemaining(payload)
	if !ok {
		return 0, "", errors.New("余额响应中未识别到 remaining、quota.remaining 或 balance")
	}
	if strings.TrimSpace(unit) == "" {
		unit = "USD"
	}
	return remaining, strings.ToUpper(strings.TrimSpace(unit)), nil
}

func providerUsageResponseActive(value any) bool {
	object, ok := value.(map[string]any)
	if !ok {
		return true
	}
	for _, key := range []string{"is_active", "isActive", "isValid", "valid"} {
		if raw, exists := object[key]; exists {
			if active, valid := raw.(bool); valid && !active {
				return false
			}
		}
	}
	return true
}

func (manager *desktopProviderUsageManager) notify() {
	manager.mu.Lock()
	listener := manager.listener
	state := providerUsageStateLocked(manager.records)
	manager.mu.Unlock()
	if listener != nil {
		listener(state)
	}
}

func (manager *desktopProviderUsageManager) saveLocked() error {
	document := providerUsageDocument{SchemaVersion: providerUsageSchemaVersion, Records: manager.records}
	return writeJSONAtomic(manager.path, document)
}

func providerUsageStateLocked(records map[string]ProviderUsageStatus) ProviderUsageState {
	items := make([]ProviderUsageStatus, 0, len(records))
	for _, record := range records {
		items = append(items, record)
	}
	sort.Slice(items, func(i, j int) bool { return items[i].ProviderProfileID < items[j].ProviderProfileID })
	return ProviderUsageState{Items: items}
}

func clampProviderUsageInterval(value int) int {
	if value < 5 {
		return 30
	}
	if value > 10080 {
		return 10080
	}
	return value
}

func findProviderRemaining(value any) (float64, string, bool) {
	object, ok := value.(map[string]any)
	if ok {
		unit := providerUsageString(object["unit"])
		for _, key := range []string{"remaining", "balance", "remaining_balance", "available_balance", "total_balance"} {
			if number, found := providerUsageNumber(object[key]); found {
				return number, unit, true
			}
		}
		if quota, exists := object["quota"]; exists {
			if number, nestedUnit, found := findProviderRemaining(quota); found {
				if nestedUnit == "" {
					nestedUnit = unit
				}
				return number, nestedUnit, true
			}
		}
		for _, nested := range object {
			if number, nestedUnit, found := findProviderRemaining(nested); found {
				return number, nestedUnit, true
			}
		}
	}
	if array, ok := value.([]any); ok {
		for _, nested := range array {
			if number, unit, found := findProviderRemaining(nested); found {
				return number, unit, true
			}
		}
	}
	return 0, "", false
}

func providerUsageNumber(value any) (float64, bool) {
	switch typed := value.(type) {
	case json.Number:
		number, err := typed.Float64()
		return number, err == nil
	case float64:
		return typed, true
	case string:
		var number json.Number = json.Number(strings.TrimSpace(typed))
		parsed, err := number.Float64()
		return parsed, err == nil
	default:
		return 0, false
	}
}

func providerUsageString(value any) string {
	text, _ := value.(string)
	return text
}

func readFileIfExists(path string) ([]byte, error) {
	data, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return nil, nil
	}
	return data, err
}
