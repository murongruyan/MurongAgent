package main

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"
)

const (
	codexAccountDocumentSchema = 1
	defaultCodexAccountID      = "codex-account-default"
	defaultCodexReservePercent = 10.0
	defaultCodexCooldownMins   = 15
)

// CodexAccountPoolSettings controls safe, between-turn account failover.
// ReservePercent is remaining quota kept in reserve, not used quota.
type CodexAccountPoolSettings struct {
	AutoSwitch      bool    `json:"autoSwitch"`
	ReservePercent  float64 `json:"reservePercent"`
	CooldownMinutes int     `json:"cooldownMinutes"`
}

type CodexAccountProfile struct {
	ID            string               `json:"id"`
	Label         string               `json:"label"`
	Email         string               `json:"email,omitempty"`
	PlanType      string               `json:"planType,omitempty"`
	Enabled       bool                 `json:"enabled"`
	Active        bool                 `json:"active"`
	LoggedIn      bool                 `json:"loggedIn"`
	LowQuota      bool                 `json:"lowQuota"`
	RateLimits    []CodexRateLimitInfo `json:"rateLimits"`
	LastCheckedAt int64                `json:"lastCheckedAt,omitempty"`
	LastUsedAt    int64                `json:"lastUsedAt,omitempty"`
	CooldownUntil int64                `json:"cooldownUntil,omitempty"`
	Error         string               `json:"error,omitempty"`
}

type CodexAccountPoolState struct {
	ActiveAccountID string                   `json:"activeAccountId"`
	Settings        CodexAccountPoolSettings `json:"settings"`
	Accounts        []CodexAccountProfile    `json:"accounts"`
}

type CodexAccountMutationRequest struct {
	AccountID string `json:"accountId"`
	Label     string `json:"label,omitempty"`
	Enabled   *bool  `json:"enabled,omitempty"`
}

type CodexAccountPoolSettingsRequest struct {
	AutoSwitch      bool    `json:"autoSwitch"`
	ReservePercent  float64 `json:"reservePercent"`
	CooldownMinutes int     `json:"cooldownMinutes"`
}

type codexSavedAccount struct {
	ID            string               `json:"id"`
	Label         string               `json:"label"`
	Email         string               `json:"email,omitempty"`
	PlanType      string               `json:"planType,omitempty"`
	Enabled       bool                 `json:"enabled"`
	ProtectedAuth string               `json:"protectedAuth,omitempty"`
	RateLimits    []CodexRateLimitInfo `json:"rateLimits,omitempty"`
	LastCheckedAt int64                `json:"lastCheckedAt,omitempty"`
	LastUsedAt    int64                `json:"lastUsedAt,omitempty"`
	CooldownUntil int64                `json:"cooldownUntil,omitempty"`
	LastError     string               `json:"lastError,omitempty"`
	CreatedAt     int64                `json:"createdAt"`
}

type codexAccountTransfer struct {
	ID         string
	Label      string
	Email      string
	PlanType   string
	Enabled    bool
	AuthJSON   *string
	LastUsedAt int64
}

type codexAccountDocument struct {
	SchemaVersion   int                      `json:"schemaVersion"`
	ActiveAccountID string                   `json:"activeAccountId"`
	Settings        CodexAccountPoolSettings `json:"settings"`
	Accounts        []codexSavedAccount      `json:"accounts"`
}

// codexAccountStore keeps account metadata in one document while every account
// gets its own CODEX_HOME. auth.json is encrypted with the platform credential
// protector while an account is inactive; only the active app-server home is
// materialized in plaintext because the official Codex process must read it.
type codexAccountStore struct {
	mu          sync.Mutex
	runtimeRoot string
	path        string
	document    codexAccountDocument
}

func newCodexAccountStore(runtimeRoot string) (*codexAccountStore, error) {
	runtimeRoot = strings.TrimSpace(runtimeRoot)
	if runtimeRoot == "" {
		return nil, errors.New("Codex 运行目录为空")
	}
	if err := os.MkdirAll(runtimeRoot, 0o700); err != nil {
		return nil, err
	}
	legacyHome := filepath.Join(runtimeRoot, "codex-home")
	if err := ensurePrivateCodexHome(legacyHome); err != nil {
		return nil, err
	}
	store := &codexAccountStore{
		runtimeRoot: runtimeRoot,
		path:        filepath.Join(runtimeRoot, "codex-accounts-v1.json"),
		document: codexAccountDocument{
			SchemaVersion:   codexAccountDocumentSchema,
			ActiveAccountID: defaultCodexAccountID,
			Settings:        defaultCodexAccountPoolSettings(),
			Accounts: []codexSavedAccount{{
				ID: defaultCodexAccountID, Label: "账号 1", Enabled: true, CreatedAt: time.Now().UnixMilli(),
			}},
		},
	}
	if data, err := os.ReadFile(store.path); err == nil {
		if err := decodeStrictJSON(data, &store.document); err != nil {
			return nil, fmt.Errorf("Codex 账号库损坏：%w", err)
		}
	} else if !errors.Is(err, os.ErrNotExist) {
		return nil, err
	}
	store.normalizeLocked()
	for _, account := range store.document.Accounts {
		removePlaintext := account.ID != store.document.ActiveAccountID
		if err := store.captureAuthLocked(account.ID, removePlaintext); err != nil && !errors.Is(err, os.ErrNotExist) {
			return nil, err
		}
	}
	if err := store.materializeAuthLocked(store.document.ActiveAccountID); err != nil {
		return nil, err
	}
	if err := store.saveLocked(); err != nil {
		return nil, err
	}
	return store, nil
}

func defaultCodexAccountPoolSettings() CodexAccountPoolSettings {
	return CodexAccountPoolSettings{
		AutoSwitch: true, ReservePercent: defaultCodexReservePercent, CooldownMinutes: defaultCodexCooldownMins,
	}
}

func (store *codexAccountStore) normalizeLocked() {
	store.document.SchemaVersion = codexAccountDocumentSchema
	if store.document.Settings.ReservePercent <= 0 || store.document.Settings.ReservePercent >= 100 {
		store.document.Settings.ReservePercent = defaultCodexReservePercent
	}
	if store.document.Settings.CooldownMinutes < 1 || store.document.Settings.CooldownMinutes > 1440 {
		store.document.Settings.CooldownMinutes = defaultCodexCooldownMins
	}
	seen := map[string]bool{}
	accounts := make([]codexSavedAccount, 0, len(store.document.Accounts)+1)
	for _, account := range store.document.Accounts {
		account.ID = strings.TrimSpace(account.ID)
		if account.ID == "" || seen[account.ID] || !isSafeCodexAccountID(account.ID) {
			continue
		}
		seen[account.ID] = true
		account.Label = truncateRunes(account.Label, 80)
		if account.Label == "" {
			account.Label = fmt.Sprintf("账号 %d", len(accounts)+1)
		}
		account.Email = truncateRunes(account.Email, 320)
		account.PlanType = truncateRunes(account.PlanType, 80)
		account.LastError = truncateRunes(account.LastError, 800)
		account.RateLimits = cloneCodexRateLimits(account.RateLimits)
		if account.CreatedAt <= 0 {
			account.CreatedAt = time.Now().UnixMilli()
		}
		accounts = append(accounts, account)
	}
	if len(accounts) == 0 {
		accounts = append(accounts, codexSavedAccount{
			ID: defaultCodexAccountID, Label: "账号 1", Enabled: true, CreatedAt: time.Now().UnixMilli(),
		})
	}
	store.document.Accounts = accounts
	if store.accountIndexLocked(store.document.ActiveAccountID) < 0 {
		store.document.ActiveAccountID = accounts[0].ID
	}
	activeIndex := store.accountIndexLocked(store.document.ActiveAccountID)
	store.document.Accounts[activeIndex].Enabled = true
}

func isSafeCodexAccountID(value string) bool {
	if len(value) < 1 || len(value) > 96 {
		return false
	}
	for _, char := range value {
		if (char >= 'a' && char <= 'z') || (char >= 'A' && char <= 'Z') ||
			(char >= '0' && char <= '9') || char == '-' || char == '_' {
			continue
		}
		return false
	}
	return true
}

func (store *codexAccountStore) accountHomeLocked(accountID string) string {
	if accountID == defaultCodexAccountID {
		return filepath.Join(store.runtimeRoot, "codex-home")
	}
	return filepath.Join(store.runtimeRoot, "codex-accounts", accountID)
}

func (store *codexAccountStore) ActiveHome() string {
	store.mu.Lock()
	defer store.mu.Unlock()
	return store.accountHomeLocked(store.document.ActiveAccountID)
}

func (store *codexAccountStore) ActiveAccountID() string {
	store.mu.Lock()
	defer store.mu.Unlock()
	return store.document.ActiveAccountID
}

func (store *codexAccountStore) State() CodexAccountPoolState {
	store.mu.Lock()
	defer store.mu.Unlock()
	return store.stateLocked()
}

func (store *codexAccountStore) stateLocked() CodexAccountPoolState {
	result := CodexAccountPoolState{
		ActiveAccountID: store.document.ActiveAccountID,
		Settings:        store.document.Settings,
		Accounts:        make([]CodexAccountProfile, 0, len(store.document.Accounts)),
	}
	for _, account := range store.document.Accounts {
		result.Accounts = append(result.Accounts, CodexAccountProfile{
			ID: account.ID, Label: account.Label, Email: account.Email, PlanType: account.PlanType,
			Enabled: account.Enabled, Active: account.ID == store.document.ActiveAccountID,
			// Newer official app-server builds may persist credentials in their
			// private state database without creating auth.json. A verified account
			// snapshot is therefore also a durable login marker.
			LoggedIn:   account.Email != "" || account.ProtectedAuth != "" || store.hasMaterializedAuthLocked(account.ID),
			LowQuota:   codexRateLimitsBelowReserve(account.RateLimits, store.document.Settings.ReservePercent),
			RateLimits: cloneCodexRateLimits(account.RateLimits), LastCheckedAt: account.LastCheckedAt,
			LastUsedAt: account.LastUsedAt, CooldownUntil: account.CooldownUntil,
			Error: account.LastError,
		})
	}
	sort.SliceStable(result.Accounts, func(i, j int) bool {
		if result.Accounts[i].Active != result.Accounts[j].Active {
			return result.Accounts[i].Active
		}
		return result.Accounts[i].LastUsedAt > result.Accounts[j].LastUsedAt
	})
	return result
}

func (store *codexAccountStore) Create(label string) (CodexAccountProfile, error) {
	store.mu.Lock()
	defer store.mu.Unlock()
	if len(store.document.Accounts) >= 20 {
		return CodexAccountProfile{}, errors.New("最多可保存 20 个 Codex 账号")
	}
	id := newID("codex-account")
	label = truncateRunes(label, 80)
	if label == "" {
		label = fmt.Sprintf("账号 %d", len(store.document.Accounts)+1)
	}
	now := time.Now().UnixMilli()
	store.document.Accounts = append(store.document.Accounts, codexSavedAccount{
		ID: id, Label: label, Enabled: true, CreatedAt: now,
	})
	if err := os.MkdirAll(store.accountHomeLocked(id), 0o700); err != nil {
		store.document.Accounts = store.document.Accounts[:len(store.document.Accounts)-1]
		return CodexAccountProfile{}, err
	}
	if err := store.saveLocked(); err != nil {
		return CodexAccountProfile{}, err
	}
	return CodexAccountProfile{ID: id, Label: label, Enabled: true}, nil
}

func (store *codexAccountStore) Update(request CodexAccountMutationRequest) error {
	store.mu.Lock()
	defer store.mu.Unlock()
	index := store.accountIndexLocked(request.AccountID)
	if index < 0 {
		return errors.New("Codex 账号不存在")
	}
	if label := truncateRunes(request.Label, 80); label != "" {
		store.document.Accounts[index].Label = label
	}
	if request.Enabled != nil {
		if store.document.Accounts[index].ID == store.document.ActiveAccountID && !*request.Enabled {
			return errors.New("当前账号不能停用，请先切换到其他账号")
		}
		store.document.Accounts[index].Enabled = *request.Enabled
	}
	return store.saveLocked()
}

func (store *codexAccountStore) UpdateSettings(request CodexAccountPoolSettingsRequest) error {
	store.mu.Lock()
	defer store.mu.Unlock()
	if request.ReservePercent < 1 || request.ReservePercent > 50 {
		return errors.New("额度保留阈值必须在 1% 到 50% 之间")
	}
	if request.CooldownMinutes < 1 || request.CooldownMinutes > 1440 {
		return errors.New("失败冷却时间必须在 1 到 1440 分钟之间")
	}
	store.document.Settings = CodexAccountPoolSettings{
		AutoSwitch: request.AutoSwitch, ReservePercent: request.ReservePercent, CooldownMinutes: request.CooldownMinutes,
	}
	return store.saveLocked()
}

// Activate persists the current account's latest refreshed token, removes its
// plaintext auth file, and materializes the target credential. The caller must
// stop the old app-server process before invoking this method.
func (store *codexAccountStore) Activate(accountID string) error {
	store.mu.Lock()
	defer store.mu.Unlock()
	accountID = strings.TrimSpace(accountID)
	target := store.accountIndexLocked(accountID)
	if target < 0 {
		return errors.New("Codex 账号不存在")
	}
	if !store.document.Accounts[target].Enabled {
		return errors.New("Codex 账号已停用")
	}
	currentID := store.document.ActiveAccountID
	if currentID == accountID {
		return store.materializeAuthLocked(accountID)
	}
	if err := store.captureAuthLocked(currentID, true); err != nil && !errors.Is(err, os.ErrNotExist) {
		return err
	}
	if err := store.materializeAuthLocked(accountID); err != nil {
		_ = store.materializeAuthLocked(currentID)
		return err
	}
	store.document.ActiveAccountID = accountID
	store.document.Accounts[target].LastUsedAt = time.Now().UnixMilli()
	return store.saveLocked()
}

func (store *codexAccountStore) Remove(accountID string) error {
	store.mu.Lock()
	defer store.mu.Unlock()
	index := store.accountIndexLocked(accountID)
	if index < 0 {
		return errors.New("Codex 账号不存在")
	}
	if accountID == store.document.ActiveAccountID {
		return errors.New("不能删除当前账号，请先切换到其他账号")
	}
	home := store.accountHomeLocked(accountID)
	store.document.Accounts = append(store.document.Accounts[:index], store.document.Accounts[index+1:]...)
	if err := store.saveLocked(); err != nil {
		return err
	}
	return os.RemoveAll(home)
}

func (store *codexAccountStore) CaptureStatus(status CodexRuntimeStatus) error {
	store.mu.Lock()
	defer store.mu.Unlock()
	index := store.accountIndexLocked(store.document.ActiveAccountID)
	if index < 0 {
		return errors.New("当前 Codex 账号不存在")
	}
	account := &store.document.Accounts[index]
	account.LastCheckedAt = time.Now().UnixMilli()
	account.LastUsedAt = account.LastCheckedAt
	if status.LoggedIn {
		if strings.TrimSpace(status.Email) != "" {
			account.Email = truncateRunes(status.Email, 320)
		}
		if strings.TrimSpace(status.PlanType) != "" {
			account.PlanType = truncateRunes(status.PlanType, 80)
		}
		if len(status.RateLimits) > 0 {
			account.RateLimits = cloneCodexRateLimits(status.RateLimits)
		}
		account.LastError = truncateRunes(status.RateLimitError, 800)
	} else if strings.TrimSpace(status.RateLimitError) != "" {
		account.LastError = truncateRunes(status.RateLimitError, 800)
	}
	if status.LoggedIn {
		if err := store.captureAuthLocked(account.ID, false); err != nil && !errors.Is(err, os.ErrNotExist) {
			return err
		}
	}
	return store.saveLocked()
}

func (store *codexAccountStore) MarkFailure(accountID string, failure error) {
	store.mu.Lock()
	defer store.mu.Unlock()
	index := store.accountIndexLocked(accountID)
	if index < 0 {
		return
	}
	account := &store.document.Accounts[index]
	if failure != nil {
		account.LastError = truncateRunes(failure.Error(), 800)
	}
	account.CooldownUntil = time.Now().Add(time.Duration(store.document.Settings.CooldownMinutes) * time.Minute).UnixMilli()
	_ = store.saveLocked()
}

func (store *codexAccountStore) ClearFailure(accountID string) {
	store.mu.Lock()
	defer store.mu.Unlock()
	index := store.accountIndexLocked(accountID)
	if index < 0 {
		return
	}
	store.document.Accounts[index].LastError = ""
	store.document.Accounts[index].CooldownUntil = 0
	_ = store.saveLocked()
}

func (store *codexAccountStore) BestAccount(preferredID, excludedID string, allowLowQuota bool) string {
	store.mu.Lock()
	defer store.mu.Unlock()
	now := time.Now().UnixMilli()
	eligible := func(account codexSavedAccount) bool {
		if !account.Enabled || account.ID == excludedID || account.CooldownUntil > now {
			return false
		}
		if account.Email == "" && account.ProtectedAuth == "" && !store.hasMaterializedAuthLocked(account.ID) {
			return false
		}
		return allowLowQuota || !codexRateLimitsBelowReserve(account.RateLimits, store.document.Settings.ReservePercent)
	}
	if preferred := store.accountIndexLocked(preferredID); preferred >= 0 && eligible(store.document.Accounts[preferred]) {
		return store.document.Accounts[preferred].ID
	}
	candidates := append([]codexSavedAccount(nil), store.document.Accounts...)
	sort.SliceStable(candidates, func(i, j int) bool {
		left := codexRateLimitRemainingScore(candidates[i].RateLimits)
		right := codexRateLimitRemainingScore(candidates[j].RateLimits)
		if left != right {
			return left > right
		}
		return candidates[i].LastUsedAt < candidates[j].LastUsedAt
	})
	for _, account := range candidates {
		if eligible(account) {
			return account.ID
		}
	}
	return ""
}

func (store *codexAccountStore) Settings() CodexAccountPoolSettings {
	store.mu.Lock()
	defer store.mu.Unlock()
	return store.document.Settings
}

func (store *codexAccountStore) AccountCredentials() ([]codexAccountTransfer, string, CodexAccountPoolSettings, error) {
	store.mu.Lock()
	defer store.mu.Unlock()
	accounts := make([]codexAccountTransfer, 0, len(store.document.Accounts))
	for _, account := range store.document.Accounts {
		transfer := codexAccountTransfer{
			ID: account.ID, Label: account.Label, Email: account.Email, PlanType: account.PlanType,
			Enabled: account.Enabled, LastUsedAt: account.LastUsedAt,
		}
		var auth []byte
		var err error
		if store.hasMaterializedAuthLocked(account.ID) {
			auth, err = readCodexAuthJSON(store.accountHomeLocked(account.ID))
		} else if account.ProtectedAuth != "" {
			auth, err = unprotectSecret(account.ProtectedAuth)
		}
		if err != nil {
			clearBytes(auth)
			return nil, "", CodexAccountPoolSettings{}, fmt.Errorf("无法读取 Codex 账号 %q：%w", account.Label, err)
		}
		if len(auth) > 0 {
			value := string(auth)
			clearBytes(auth)
			transfer.AuthJSON = &value
		}
		accounts = append(accounts, transfer)
	}
	return accounts, store.document.ActiveAccountID, store.document.Settings, nil
}

func (store *codexAccountStore) ImportAccounts(
	accounts []codexAccountTransfer,
	activeID string,
	settings *CodexAccountPoolSettings,
) (int, error) {
	store.mu.Lock()
	defer store.mu.Unlock()
	if len(accounts) == 0 {
		return 0, nil
	}
	if len(accounts) > 20 {
		return 0, errors.New("同步的 Codex 账号超过 20 个")
	}
	currentID := store.document.ActiveAccountID
	if err := store.captureAuthLocked(currentID, false); err != nil && !errors.Is(err, os.ErrNotExist) {
		return 0, err
	}
	if err := store.saveLocked(); err != nil {
		return 0, err
	}
	original := cloneCodexAccountDocument(store.document)
	next := cloneCodexAccountDocument(store.document)
	remoteToLocal := make(map[string]string, len(accounts))
	imported := 0
	for _, incoming := range accounts {
		id := strings.TrimSpace(incoming.ID)
		if !isSafeCodexAccountID(id) {
			return 0, errors.New("同步的 Codex 账号 ID 无效")
		}
		index := -1
		for candidate := range next.Accounts {
			if next.Accounts[candidate].ID == id {
				index = candidate
				break
			}
		}
		if index < 0 && strings.TrimSpace(incoming.Email) != "" {
			for candidate := range next.Accounts {
				if strings.EqualFold(next.Accounts[candidate].Email, strings.TrimSpace(incoming.Email)) {
					index = candidate
					break
				}
			}
		}
		if index < 0 {
			if len(next.Accounts) >= 20 {
				return 0, errors.New("本机 Codex 账号已达到 20 个上限")
			}
			next.Accounts = append(next.Accounts, codexSavedAccount{ID: id, Enabled: true, CreatedAt: time.Now().UnixMilli()})
			index = len(next.Accounts) - 1
		}
		account := next.Accounts[index]
		remoteToLocal[id] = account.ID
		account.Label = truncateRunes(strings.TrimSpace(incoming.Label), 80)
		if account.Label == "" {
			account.Label = fmt.Sprintf("账号 %d", index+1)
		}
		account.Email = truncateRunes(strings.TrimSpace(incoming.Email), 320)
		account.PlanType = truncateRunes(strings.TrimSpace(incoming.PlanType), 80)
		account.Enabled = incoming.Enabled
		account.LastUsedAt = incoming.LastUsedAt
		if incoming.AuthJSON != nil && strings.TrimSpace(*incoming.AuthJSON) != "" {
			auth := []byte(*incoming.AuthJSON)
			if err := validateCodexAuthJSON(auth); err != nil {
				clearBytes(auth)
				return 0, err
			}
			protected, err := protectSecret(auth)
			clearBytes(auth)
			if err != nil {
				return 0, fmt.Errorf("无法保护同步的 Codex 账号 %q：%w", account.Label, err)
			}
			account.ProtectedAuth = protected
		}
		next.Accounts[index] = account
		imported++
	}
	if settings != nil {
		if settings.ReservePercent < 1 || settings.ReservePercent > 50 || settings.CooldownMinutes < 1 || settings.CooldownMinutes > 1440 {
			return 0, errors.New("同步的 Codex 账号池设置无效")
		}
		next.Settings = *settings
	}
	targetID := currentID
	if mapped := remoteToLocal[strings.TrimSpace(activeID)]; mapped != "" {
		for _, account := range next.Accounts {
			if account.ID == mapped && (account.ProtectedAuth != "" || store.hasMaterializedAuthLocked(account.ID)) {
				targetID = mapped
				break
			}
		}
	}
	next.ActiveAccountID = targetID
	for index := range next.Accounts {
		if next.Accounts[index].ID == targetID {
			next.Accounts[index].Enabled = true
		}
	}
	store.document = next
	rollback := func(cause error) (int, error) {
		store.document = original
		_ = store.saveLocked()
		if targetID != original.ActiveAccountID {
			_ = os.Remove(filepath.Join(store.accountHomeLocked(targetID), "auth.json"))
		}
		_ = store.materializeAuthLocked(original.ActiveAccountID)
		return 0, cause
	}
	if err := store.saveLocked(); err != nil {
		return rollback(err)
	}
	if targetID != currentID {
		if err := os.Remove(filepath.Join(store.accountHomeLocked(currentID), "auth.json")); err != nil && !errors.Is(err, os.ErrNotExist) {
			return rollback(err)
		}
	}
	if err := store.materializeAuthLocked(targetID); err != nil {
		return rollback(err)
	}
	return imported, nil
}

func (store *codexAccountStore) IsLowQuota(accountID string) bool {
	store.mu.Lock()
	defer store.mu.Unlock()
	index := store.accountIndexLocked(accountID)
	return index >= 0 && codexRateLimitsBelowReserve(
		store.document.Accounts[index].RateLimits,
		store.document.Settings.ReservePercent,
	)
}

func (store *codexAccountStore) captureAuthLocked(accountID string, removePlaintext bool) error {
	index := store.accountIndexLocked(accountID)
	if index < 0 {
		return errors.New("Codex 账号不存在")
	}
	home := store.accountHomeLocked(accountID)
	data, err := readCodexAuthJSON(home)
	if err != nil {
		return err
	}
	defer clearBytes(data)
	protected, err := protectSecret(data)
	if err != nil {
		return fmt.Errorf("无法保护 Codex 登录：%w", err)
	}
	store.document.Accounts[index].ProtectedAuth = protected
	if removePlaintext {
		// Persist the encrypted replacement before deleting the only plaintext
		// copy, so a power loss during account rotation cannot lose the login.
		if err := store.saveLocked(); err != nil {
			return err
		}
		if err := os.Remove(filepath.Join(home, "auth.json")); err != nil && !errors.Is(err, os.ErrNotExist) {
			return err
		}
	}
	return nil
}

func (store *codexAccountStore) materializeAuthLocked(accountID string) error {
	index := store.accountIndexLocked(accountID)
	if index < 0 {
		return errors.New("Codex 账号不存在")
	}
	home := store.accountHomeLocked(accountID)
	if err := os.MkdirAll(home, 0o700); err != nil {
		return err
	}
	if store.hasMaterializedAuthLocked(accountID) || store.document.Accounts[index].ProtectedAuth == "" {
		return nil
	}
	data, err := unprotectSecret(store.document.Accounts[index].ProtectedAuth)
	if err != nil {
		return fmt.Errorf("无法解锁 Codex 登录：%w", err)
	}
	defer clearBytes(data)
	return writeCodexAuthJSON(home, data)
}

func (store *codexAccountStore) hasMaterializedAuthLocked(accountID string) bool {
	info, err := os.Stat(filepath.Join(store.accountHomeLocked(accountID), "auth.json"))
	return err == nil && !info.IsDir()
}

func (store *codexAccountStore) accountIndexLocked(accountID string) int {
	accountID = strings.TrimSpace(accountID)
	for index := range store.document.Accounts {
		if store.document.Accounts[index].ID == accountID {
			return index
		}
	}
	return -1
}

func (store *codexAccountStore) saveLocked() error {
	return writeJSONAtomic(store.path, store.document)
}

func cloneCodexRateLimits(values []CodexRateLimitInfo) []CodexRateLimitInfo {
	result := append([]CodexRateLimitInfo(nil), values...)
	for index := range result {
		if result[index].Primary != nil {
			value := *result[index].Primary
			result[index].Primary = &value
		}
		if result[index].Secondary != nil {
			value := *result[index].Secondary
			result[index].Secondary = &value
		}
		if result[index].Credits != nil {
			value := *result[index].Credits
			result[index].Credits = &value
		}
	}
	return result
}

func cloneCodexAccountDocument(document codexAccountDocument) codexAccountDocument {
	copy := document
	copy.Accounts = append([]codexSavedAccount(nil), document.Accounts...)
	for index := range copy.Accounts {
		copy.Accounts[index].RateLimits = cloneCodexRateLimits(copy.Accounts[index].RateLimits)
	}
	return copy
}

func codexRateLimitsBelowReserve(limits []CodexRateLimitInfo, reservePercent float64) bool {
	threshold := 100 - reservePercent
	for _, limit := range limits {
		if limit.Primary != nil && limit.Primary.UsedPercent >= threshold {
			return true
		}
		if limit.Secondary != nil && limit.Secondary.UsedPercent >= threshold {
			return true
		}
	}
	return false
}

func codexRateLimitRemainingScore(limits []CodexRateLimitInfo) float64 {
	if len(limits) == 0 {
		return -1
	}
	remaining := 100.0
	found := false
	for _, limit := range limits {
		for _, window := range []*CodexRateLimitWindow{limit.Primary, limit.Secondary} {
			if window == nil {
				continue
			}
			found = true
			value := 100 - window.UsedPercent
			if value < remaining {
				remaining = value
			}
		}
	}
	if !found {
		return -1
	}
	return remaining
}
